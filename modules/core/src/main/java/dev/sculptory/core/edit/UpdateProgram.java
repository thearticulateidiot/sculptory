package dev.sculptory.core.edit;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;

/**
 * Update blocks: every cell of the region takes the shape its
 * neighbours give it ({@link NeighbourShapes}: fences, walls and panes connect, stairs shape, redstone wire joins),
 * without physics, and the runner fixes the light of the sections afterwards ({@link #relightsAfter}).
 *
 * <p>Only a change of properties of the same block is kept: a result of another block (a torch or plant without
 * support turning to air, a door half without its other half) is dropped, so nothing breaks or falls. A cell whose
 * block entity survives a property change (a chest joining its neighbour) carries it into the output, so its contents
 * stay. States of blocks with a single state (stone, dirt, air), fluids and air are never asked about: they cannot
 * change. Neighbours are read from the live world: a section's neighbours in sections written earlier already have
 * their new shapes (the result can depend on the section order, which is fixed).
 */
final class UpdateProgram implements ClaimingProgram {
    private static final byte UNKNOWN = 0, SKIP = 1, TRY = 2;

    private final Region region;
    private final RegionProgram.Height height;
    private final Box bounds;
    private final long estimatedCells;
    private final long[] sectionOrder;
    private final NeighbourShapes shapes;
    private final StateSpace states;
    /** Per state: whether it may change ({@link #TRY}), worked out on first use. */
    private final byte[] candidates;
    private final int[] rows = new int[Regions.ROWS];

    UpdateProgram(Region region, RegionProgram.Height height, NeighbourShapes shapes, StateSpace states, byte[] candidates,
                  long maxSections) {
        this.region = Objects.requireNonNull(region);
        this.height = height;
        this.bounds = height.bounds(region.bounds());
        this.shapes = Objects.requireNonNull(shapes);
        this.states = Objects.requireNonNull(states);
        this.candidates = candidates;
        this.sectionOrder = ColumnProgram.sections(region, height, maxSections);
        this.estimatedCells = region instanceof Region.Cuboid ? bounds.volume()
                : Regions.cellsBetween(region, height.bottom(), height.top());
    }

    /** A candidate table for {@link UpdateProgram}s over {@code states}, shared by an op's copies. */
    static byte[] candidates(StateSpace states) {
        return new byte[states.size()];
    }

    @Override
    public String label() {
        return "Update blocks";
    }

    @Override
    public Box bounds() {
        return bounds;
    }

    @Override
    public long estimatedCells() {
        return estimatedCells;
    }

    @Override
    public long[] sourceSections() {
        return new long[0];
    }

    @Override
    public long[] sectionOrder() {
        return sectionOrder.clone();
    }

    /** The four chunk columns beside the section's: a cell on the chunk's edge reads its neighbour there. */
    @Override
    public long[] readColumns(long key) {
        int sx = BlockBuffer.keyX(key), sz = BlockBuffer.keyZ(key);
        return new long[] {EditProgram.column(sx - 1, sz), EditProgram.column(sx + 1, sz), EditProgram.column(sx, sz - 1),
                EditProgram.column(sx, sz + 1)};
    }

    @Override
    public boolean relightsAfter() {
        return true;
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        compute(key, before, out, ctx, null);
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx, long[] claimed) {
        WorldReader world = ctx.world();
        if (world == null) return;
        if (Regions.rows(region, key, height.bottom(), height.top(), rows) == 0) return;
        int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
        for (int r = 0; r < Regions.ROWS; r++) {
            int row = rows[r];
            int ly = r >>> 4, lz = r & 15;
            while (row != 0) {
                int lx = Integer.numberOfTrailingZeros(row);
                row &= row - 1;
                int i = SectionBuffer.index(lx, ly, lz);
                if (ClaimingProgram.claimed(claimed, i)) continue;
                ClaimingProgram.claim(claimed, i);
                int current = before.get(i);
                if (!candidate(current)) continue;
                int next = shapes.reshape(ox + lx, oy + ly, oz + lz, current, world);
                if (next == current || next < 0 || next >= states.size()) continue;
                // Only the shape: another block would be physics (an unsupported torch turning to air).
                if (!states.blockId(next).equals(states.blockId(current))) continue;
                out.set(i, next);
                if (StateFlags.has(states.flags(current), StateFlags.HAS_BLOCK_ENTITY)) out.setTile(i, before.tile(i));
            }
        }
    }

    /** Whether {@code state} may take another shape: a block of more than one state, not air or a fluid. */
    private boolean candidate(int state) {
        byte known = candidates[state];
        if (known == UNKNOWN) {
            int flags = states.flags(state);
            boolean may = !StateFlags.has(flags, StateFlags.AIR) && !StateFlags.has(flags, StateFlags.FLUID_BLOCK)
                    && !states.describe(state).properties().isEmpty();
            known = may ? TRY : SKIP;
            candidates[state] = known;
        }
        return known == TRY;
    }
}
