package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CopySupport.InverseMap;
import dev.sculptory.core.edit.CopySupport.SectionCursor;
import dev.sculptory.core.edit.CopySupport.StateMapper;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

/**
 * Pastes a {@link SourceBlocks} with a {@link Transform} so that its (transformed) anchor cell lands on the
 * origin. Each target cell is mapped back to its source cell with the inverse transform (heights too when it is flipped
 * upside down); states go through {@link Transform#applyToState} (cached per handle); tiles are copied unchanged
 * (their NBT content is not rotated: signs, banners and the like keep their stored data). Absent source cells are
 * never written, source air only with {@link PasteOptions#includeAir()}, and a landing cell only when
 * {@link PasteOptions#into()} allows its current content (a cell left out is
 * neither written nor claimed). {@link PasteOptions#physics()} is the executor's business.
 */
final class PasteProgram implements ClaimingProgram {
    private static final long[] NO_SECTIONS = new long[0];

    private final BlockBuffer cells;
    private final BlockPos size;
    private final StateSpace states;
    private final boolean includeAir;
    private final PasteOptions.Into into;
    private final StateMapper mapper;
    private final InverseMap inverse;
    /** Whether the paste is flipped upside down (a landing height maps back to {@code size.y() - 1 - y}). */
    private final boolean upsideDown;
    /** The whole transformed box in world coordinates. */
    private final Box target;
    /** {@link #target} clipped to the build height. */
    private final Box bounds;
    private final long estimatedCells;
    private final long[] order;

    private PasteProgram(SourceBlocks source, OpSpec.Paste op, StateSpace states, Box target, Box bounds,
                         long estimatedCells, long[] order) {
        this.cells = source.cells();
        this.size = source.size();
        this.states = states;
        this.includeAir = op.o().includeAir();
        this.into = op.o().into();
        this.mapper = new StateMapper(states, op.t());
        this.inverse = InverseMap.of(op.t(), size.x(), size.z());
        this.upsideDown = op.t().upsideDown();
        this.target = target;
        this.bounds = bounds;
        this.estimatedCells = estimatedCells;
        this.order = order;
    }

    /**
     * @throws IllegalArgumentException if the paste lies outside the build height or the coordinate range, or the
     *     source holds a state outside {@code states}
     */
    static PasteProgram compile(OpSpec.Paste op, SourceBlocks source, StateSpace states, RegionProgram.Height height) {
        Transform t = op.t();
        BlockPos size = source.size();
        Box target = CopySupport.pasteTarget(size, source.anchor(), t, op.origin());
        Box bounds = height.clip(target);
        if (bounds == null) throw new IllegalArgumentException("Paste is outside the build height: " + target);

        // One pass over the source: validate states, count the cells that will be considered, and list the target
        // sections that can receive them (each source section's occupied bounds, transformed).
        LongOpenHashSet sections = new LongOpenHashSet();
        long counted = 0;
        int[] local = new int[7];
        for (long key : source.cells().sortedKeys()) {
            SectionBuffer section = source.cells().section(key);
            if (section.isEmpty()) continue;
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            local[0] = local[1] = local[2] = Integer.MAX_VALUE;
            local[3] = local[4] = local[5] = Integer.MIN_VALUE;
            local[6] = 0;
            section.forEachPresent(i -> {
                int x = ox + SectionBuffer.localX(i), y = oy + SectionBuffer.localY(i), z = oz + SectionBuffer.localZ(i);
                if (x < 0 || y < 0 || z < 0 || x >= size.x() || y >= size.y() || z >= size.z()) return;
                int state = section.get(i);
                if (state >= states.size()) {
                    throw new IllegalArgumentException("Paste source state " + state + " is outside the state space");
                }
                if (!op.o().includeAir() && StateFlags.has(states.flags(state), StateFlags.AIR)) return;
                int landing = target.min().y() + t.mapY(y, size.y());
                if (landing < bounds.min().y() || landing > bounds.max().y()) return;
                local[0] = Math.min(local[0], x);
                local[1] = Math.min(local[1], y);
                local[2] = Math.min(local[2], z);
                local[3] = Math.max(local[3], x);
                local[4] = Math.max(local[4], y);
                local[5] = Math.max(local[5], z);
                local[6]++;
            });
            if (local[6] == 0) continue;
            counted += local[6];
            CopySupport.addSections(CopySupport.transformedPart(target, size, t,
                    local[0], local[1], local[2], local[3], local[4], local[5]), sections);
        }
        long[] order = sections.isEmpty() ? NO_SECTIONS : CopySupport.ordered(sections);
        return new PasteProgram(source, op, states, target, bounds, counted, order);
    }

    @Override
    public String label() {
        return "Paste";
    }

    @Override
    public Box bounds() {
        return bounds;
    }

    /** The source cells the paste considers: present, inside the build height, and not air unless included. */
    @Override
    public long estimatedCells() {
        return estimatedCells;
    }

    @Override
    public long[] sourceSections() {
        return NO_SECTIONS;
    }

    @Override
    public long[] sectionOrder() {
        return order.clone();
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        compute(key, before, out, ctx, null);
    }

    /**
     * Covers every present source cell that lands in the section (air only with {@code includeAir}) on a cell
     * {@code into} allows.
     */
    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx, long[] claimed) {
        int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
        int x0 = Math.max(bounds.min().x(), ox), x1 = Math.min(bounds.max().x(), ox + 15);
        int y0 = Math.max(bounds.min().y(), oy), y1 = Math.min(bounds.max().y(), oy + 15);
        int z0 = Math.max(bounds.min().z(), oz), z1 = Math.min(bounds.max().z(), oz + 15);
        if (x0 > x1 || y0 > y1 || z0 > z1) return;
        SectionCursor source = new SectionCursor(cells::section);
        BlockPos min = target.min();
        int tx0 = x0 - min.x();
        for (int y = y0; y <= y1; y++) {
            int sy = upsideDown ? size.y() - 1 - (y - min.y()) : y - min.y();
            for (int z = z0; z <= z1; z++) {
                int tz = z - min.z();
                int sx = inverse.x(tx0, tz), sz = inverse.z(tx0, tz);
                int base = SectionBuffer.index(0, y - oy, z - oz);
                for (int x = x0; x <= x1; x++, sx += inverse.xPerTx(), sz += inverse.zPerTx()) {
                    int i = base | (x - ox);
                    if (ClaimingProgram.claimed(claimed, i)) continue;
                    SectionBuffer section = source.at(sx, sy, sz);
                    if (section == null) continue;
                    int si = SectionBuffer.index(sx & 15, sy & 15, sz & 15);
                    int state = section.get(si);
                    if (state < 0) continue;
                    if (!includeAir && StateFlags.has(states.flags(state), StateFlags.AIR)) continue;
                    if (!CopySupport.writable(into, states, before.get(i))) continue;
                    ClaimingProgram.claim(claimed, i);
                    CopySupport.copyCell(before, out, i, mapper.map(state), section.tile(si));
                }
            }
        }
    }
}
