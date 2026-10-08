package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CopySupport.InverseMap;
import dev.sculptory.core.edit.CopySupport.SectionCursor;
import dev.sculptory.core.edit.CopySupport.StateMapper;
import dev.sculptory.core.state.OrientedStates;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Pastes a {@link SourceBlocks} with an {@link Orientation} so that its (oriented) anchor cell lands on the origin.
 *
 * <p>An exact orientation (a {@link Transform}) maps each target cell back to its source cell with the inverse
 * transform (heights too when it is flipped upside down); states go through {@link Transform#applyToState} (cached per
 * handle). A free orientation resamples: the target box is the result box ({@link Orientation#bounds}) placed by the
 * anchor ({@link PasteGeometry#pasteTarget(BlockPos, BlockPos, Orientation, BlockPos)}), each of its cells takes the
 * source cell {@link Orientation.Sampler#source} gives (the nearest, so a turned solid has no holes), and states take
 * the nearest cube turn ({@link OrientedStates}). A symmetric copy of a free paste ({@link #compileImage}) writes the
 * exact image of the original's cells.
 *
 * <p>Tiles are copied unchanged (their NBT content is not rotated: signs, banners and the like keep their stored data).
 * Absent source cells are never written, source air only with {@link PasteOptions#includeAir()}, and a landing cell
 * only when {@link PasteOptions#into()} allows its current content (a cell left out is neither written nor claimed).
 * {@link PasteOptions#physics()} is the executor's business.
 */
final class PasteProgram implements ClaimingProgram {
    private static final long[] NO_SECTIONS = new long[0];

    private final BlockBuffer cells;
    private final BlockPos size;
    private final StateSpace states;
    private final boolean includeAir;
    private final PasteOptions.Into into;
    /** The exact path's state map and inverse; null on the free path. */
    private final StateMapper mapper;
    private final InverseMap inverse;
    /** Whether the paste is flipped upside down (a landing height maps back to {@code size.y() - 1 - y}). */
    private final boolean upsideDown;
    /** The free path's inverse map and states; null on the exact path. */
    private final Orientation.Sampler sampler;
    private final OrientedStates oriented;
    /** A symmetric copy of a free paste: how its cells map to the original's; null for the original. */
    private final CopyFrame frame;
    /** The minimum corner of the box the sampler's result cells are local to (the original's, for a copy). */
    private final BlockPos sampleMin;
    /** The whole transformed box in world coordinates. */
    private final Box target;
    /** {@link #target} clipped to the build height. */
    private final Box bounds;
    private final long estimatedCells;
    private final long[] order;

    private PasteProgram(SourceBlocks source, OpSpec.Paste op, StateSpace states, Box target, Box bounds,
                         long estimatedCells, long[] order, Orientation.Sampler sampler, CopyFrame frame,
                         BlockPos sampleMin) {
        this.cells = source.cells();
        this.size = source.size();
        this.states = states;
        this.includeAir = op.o().includeAir();
        this.into = op.o().into();
        Transform t = op.orientation().transform();
        boolean exact = sampler == null;
        this.mapper = exact ? new StateMapper(states, t) : null;
        this.inverse = exact ? InverseMap.of(t, size.x(), size.z()) : null;
        this.upsideDown = t.upsideDown();
        this.sampler = sampler;
        this.oriented = exact ? null : new OrientedStates(states, op.orientation());
        this.frame = frame;
        this.sampleMin = sampleMin;
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
        if (!op.orientation().isExact()) return compileFree(op, source, states, height, null);
        return compileExact(op, source, states, height, null);
    }

    /**
     * The exact path. With {@code scans} (a multi-paste's, per transform, of this source and include-air) a paste the
     * build height leaves whole reuses its transform's {@link #scan}, so a multi-paste reads its source once per
     * transform instead of once per placement; null scans as usual.
     */
    private static PasteProgram compileExact(OpSpec.Paste op, SourceBlocks source, StateSpace states,
                                             RegionProgram.Height height, Map<Transform, Scan> scans) {
        Transform t = op.orientation().transform();
        BlockPos size = source.size();
        Box target = CopySupport.pasteTarget(size, source.anchor(), t, op.origin());
        Box bounds = height.clip(target);
        if (bounds == null) throw new IllegalArgumentException("Paste is outside the build height: " + target);
        LongOpenHashSet sections = new LongOpenHashSet();
        long counted;
        if (scans != null && bounds.equals(target)) {
            Scan scan = scans.computeIfAbsent(t, turn -> scan(source, turn, op.o().includeAir(), states));
            BlockPos min = target.min();
            for (Box part : scan.parts()) CopySupport.addSections(part.offset(min.x(), min.y(), min.z()), sections);
            counted = scan.cells();
        } else {
            counted = scan(source, t, op.o().includeAir(), states, target, bounds,
                    part -> CopySupport.addSections(part, sections));
        }
        long[] order = sections.isEmpty() ? NO_SECTIONS : CopySupport.ordered(sections);
        return new PasteProgram(source, op, states, target, bounds, counted, order, null, null, target.min());
    }

    /**
     * What a paste of {@code source} turned by {@code t} lays when nothing is cut by the build height: the cells it
     * considers, and each source section's occupied bounds transformed, local to the target box (its minimum corner at
     * the origin).
     */
    record Scan(long cells, List<Box> parts) {}

    /** The {@link Scan} of {@code source} under {@code t}. */
    static Scan scan(SourceBlocks source, Transform t, boolean includeAir, StateSpace states) {
        BlockPos size = t.size(source.size().x(), source.size().y(), source.size().z());
        Box local = new Box(BlockPos.ORIGIN, new BlockPos(size.x() - 1, size.y() - 1, size.z() - 1));
        List<Box> parts = new ArrayList<>();
        long cells = scan(source, t, includeAir, states, local, local, parts::add);
        return new Scan(cells, List.copyOf(parts));
    }

    /**
     * One pass over the source: validates states, counts the cells that will be considered (present, not air unless
     * included, landing within {@code bounds}' heights) and hands {@code part} each source section's occupied bounds,
     * transformed into {@code target}.
     */
    private static long scan(SourceBlocks source, Transform t, boolean includeAir, StateSpace states, Box target,
                             Box bounds, Consumer<Box> part) {
        BlockPos size = source.size();
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
                if (!includeAir && StateFlags.has(states.flags(state), StateFlags.AIR)) return;
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
            part.accept(CopySupport.transformedPart(target, size, t, local[0], local[1], local[2], local[3], local[4],
                    local[5]));
        }
        return counted;
    }

    /**
     * A symmetric copy of a paste with a free orientation: the exact image under {@code frame} of what {@code original}
     * (the paste without symmetry) writes, each of its cells taking the source cell the original's pre-image cell takes,
     * its state turned by the image.
     *
     * @throws IllegalArgumentException as {@link #compile}, or if the image leaves the coordinate range
     */
    static PasteProgram compileImage(OpSpec.Paste original, CopyFrame frame, SourceBlocks source, StateSpace states,
                                     RegionProgram.Height height) {
        if (original.orientation().isExact()) throw new IllegalArgumentException("An exact paste's copy is a paste");
        return compileFree(original, source, states, height, Objects.requireNonNull(frame));
    }

    /**
     * One placement of a multi-paste ({@link OpSpec.PasteMany}) under one symmetry image, as a paste would write it:
     * {@code placement} is the original placement (the op's own, not its symmetric copy); for {@link
     * dev.sculptory.core.brush.Symmetry.Image#IDENTITY} it is pasted as it is, under any other image an exact placement
     * is pasted with its orientation followed by the image ({@link Orientation#then}) and its origin mapped, and a free
     * one as the exact image of what the original placement writes ({@link #compileImage}: composing the angles is not
     * an exact mirror image for even sizes). Placements later in the op should win, as in {@code MultiPasteProgram}.
     *
     * @throws IllegalArgumentException as {@link #compile}
     */
    static PasteProgram compilePlacement(SourceRef src, OpSpec.PasteMany.Placement placement, PasteOptions o,
                                         dev.sculptory.core.brush.Symmetry symmetry,
                                         dev.sculptory.core.brush.Symmetry.Image image, SourceBlocks source,
                                         StateSpace states, RegionProgram.Height height) {
        return compilePlacement(src, placement, o, symmetry, image, source, states, height, null);
    }

    /**
     * {@link #compilePlacement(SourceRef, OpSpec.PasteMany.Placement, PasteOptions, dev.sculptory.core.brush.Symmetry,
     * dev.sculptory.core.brush.Symmetry.Image, SourceBlocks, StateSpace, RegionProgram.Height)} sharing {@code scans}
     * (per transform, see {@link #scan}) between the op's exact placements; null scans each one.
     */
    static PasteProgram compilePlacement(SourceRef src, OpSpec.PasteMany.Placement placement, PasteOptions o,
                                         dev.sculptory.core.brush.Symmetry symmetry,
                                         dev.sculptory.core.brush.Symmetry.Image image, SourceBlocks source,
                                         StateSpace states, RegionProgram.Height height, Map<Transform, Scan> scans) {
        OpSpec.Paste original = new OpSpec.Paste(src, placement.origin(), placement.orientation(), o);
        if (image == dev.sculptory.core.brush.Symmetry.Image.IDENTITY) {
            return original.orientation().isExact() ? compileExact(original, source, states, height, scans)
                    : compileFree(original, source, states, height, null);
        }
        if (!placement.orientation().isExact()) {
            return compileImage(original, new CopyFrame(symmetry, image, states), source, states, height);
        }
        long x = symmetry.cellX(image, placement.origin().x(), placement.origin().z());
        long z = symmetry.cellZ(image, placement.origin().x(), placement.origin().z());
        if (x != (int) x || z != (int) z) {
            throw new IllegalArgumentException("A symmetric copy of " + placement.origin() + " leaves the coordinate range");
        }
        return compileExact(new OpSpec.Paste(src, new BlockPos((int) x, placement.origin().y(), (int) z),
                placement.orientation().then(image.transform()), o), source, states, height, scans);
    }

    /** The free path: the result box placed by the anchor, the target sections of each source section's turned bounds. */
    private static PasteProgram compileFree(OpSpec.Paste op, SourceBlocks source, StateSpace states,
                                            RegionProgram.Height height, CopyFrame frame) {
        Orientation orientation = op.orientation();
        BlockPos size = source.size();
        Orientation.Sampler sampler = orientation.sampler(size.x(), size.y(), size.z());
        Box original = PasteGeometry.pasteTarget(size, source.anchor(), orientation, op.origin());
        Box target = frame == null ? original : frame.imageBox(original);
        Box bounds = height.clip(target);
        if (bounds == null) throw new IllegalArgumentException("Paste is outside the build height: " + target);
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
            Box part = turnedPart(original, sampler, local);
            if (part == null) continue;
            if (frame != null) part = frame.imageBox(part);
            Box clipped = height.clip(part);
            if (clipped != null) CopySupport.addSections(clipped, sections);
        }
        long[] order = sections.isEmpty() ? NO_SECTIONS : CopySupport.ordered(sections);
        return new PasteProgram(source, op, states, target, bounds, counted, order, sampler, frame, original.min());
    }

    /**
     * The world cells of {@code target} (the result box of a source of {@code size}) that the source cells
     * {@code local[0..2]..local[3..5]} can reach: the turned box of those cells, one cell wider on every side (a result
     * cell takes the source cell its centre turns back into, which lies within a cell of where that cell turns to),
     * cut to the target. Null when nothing is left.
     */
    private static Box turnedPart(Box target, Orientation.Sampler sampler, int[] local) {
        int[] b = sampler.turnedBox(local[0], local[1], local[2], local[3], local[4], local[5]);
        if (b == null) return null;
        BlockPos min = target.min();
        return new Box(min.offset(b[0], b[1], b[2]), min.offset(b[3], b[4], b[5]));
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
        if (sampler != null) {
            computeFree(ox, oy, oz, x0, x1, y0, y1, z0, z1, source, before, out, claimed);
            return;
        }
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

    /** The free path: each result cell resampled through the sampler (from its pre-image's, for a copy). */
    private void computeFree(int ox, int oy, int oz, int x0, int x1, int y0, int y1, int z0, int z1,
                             SectionCursor source, SectionBuffer before, SectionBuffer out, long[] claimed) {
        int[] cell = new int[3];
        for (int y = y0; y <= y1; y++) {
            int ty = y - sampleMin.y();
            for (int z = z0; z <= z1; z++) {
                int base = SectionBuffer.index(0, y - oy, z - oz);
                for (int x = x0; x <= x1; x++) {
                    int i = base | (x - ox);
                    if (ClaimingProgram.claimed(claimed, i)) continue;
                    int tx, tz;
                    if (frame == null) {
                        tx = x - sampleMin.x();
                        tz = z - sampleMin.z();
                    } else {
                        tx = frame.preX(x, z) - sampleMin.x();
                        tz = frame.preZ(x, z) - sampleMin.z();
                    }
                    if (!sampler.source(tx, ty, tz, cell)) continue;
                    SectionBuffer section = source.at(cell[0], cell[1], cell[2]);
                    if (section == null) continue;
                    int si = SectionBuffer.index(cell[0] & 15, cell[1] & 15, cell[2] & 15);
                    int state = section.get(si);
                    if (state < 0) continue;
                    if (!includeAir && StateFlags.has(states.flags(state), StateFlags.AIR)) continue;
                    if (!CopySupport.writable(into, states, before.get(i))) continue;
                    ClaimingProgram.claim(claimed, i);
                    int next = oriented.map(state);
                    if (frame != null) next = frame.mapState(next);
                    CopySupport.copyCell(before, out, i, next, section.tile(si));
                }
            }
        }
    }
}
