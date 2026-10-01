package dev.sculptory.core.mask;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Objects;

/**
 * Applies the global mask to a compiled bulk edit: each cell the program would
 * write is kept only when the mask accepts it, tested against the world before the edit (the section's
 * {@code before} and, for rules that read neighbours, a {@link SnapshotReader} over pre-write snapshots of the
 * sections around it). The edit service wraps every op it runs whose {@link #sideOf side} is
 * {@link MaskSide#DESTINATION}; undo and redo are never masked.
 *
 * <p>For a mask that reads neighbours ({@link BoundMask#reach()} {@code > 0}, at most one section), every section the
 * program writes is snapshotted before any write ({@link #sourceSections()}): a neighbour in a section the job wrote
 * earlier is read from its snapshot, one in a section the job does not write from the live world (unchanged by the
 * job), and the chunk columns around each section are asked to be loaded ({@link #readColumns}). A cell-only mask
 * snapshots nothing. Everything else (bounds, estimates, {@code mayReplace}, relighting) is the program's.
 */
public final class MaskedProgram implements EditProgram {
    private final EditProgram program;
    private final BoundMask mask;
    /** The program's snapshots, plus every section it writes when the mask reads neighbours. */
    private final long[] sources;

    private MaskedProgram(EditProgram program, BoundMask mask) {
        this.program = program;
        this.mask = mask;
        long[] own = program.sourceSections();
        if (mask.reach() == 0) {
            this.sources = own;
        } else {
            LongLinkedOpenHashSet keys = new LongLinkedOpenHashSet(own);
            for (long key : program.sectionOrder()) keys.add(key);
            this.sources = keys.toLongArray();
        }
    }

    /** {@code program} with every write outside {@code mask} left out; {@code program} itself when the mask is off. */
    public static EditProgram wrap(EditProgram program, BoundMask mask) {
        Objects.requireNonNull(program);
        Objects.requireNonNull(mask);
        if (mask.acceptsAll()) return program;
        if (mask.reach() < 0 || mask.reach() > BoundMask.REACH_LIMIT) {
            throw new IllegalArgumentException("A mask reaching " + mask.reach() + " blocks");
        }
        return new MaskedProgram(program, mask);
    }

    /**
     * Where the global mask judges {@code op}'s cells: at the source for a Move (only the matching blocks are lifted,
     * the rest stays; the landing writes are not masked), where they land for every other op. A source-masked op
     * reads the mask at compile time ({@code CompileContext.sourceMask()}) and is not wrapped by {@link #wrap}.
     */
    public static MaskSide sideOf(OpSpec op) {
        return op instanceof OpSpec.Move ? MaskSide.SOURCE : MaskSide.DESTINATION;
    }

    @Override
    public String label() {
        return program.label();
    }

    @Override
    public Box bounds() {
        return program.bounds();
    }

    @Override
    public long estimatedCells() {
        return program.estimatedCells();
    }

    @Override
    public long[] sourceSections() {
        return sources.clone();
    }

    @Override
    public long[] sectionOrder() {
        return program.sectionOrder();
    }

    @Override
    public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
        program.compute(key, before, out, ctx);
        if (out.isEmpty()) return;
        filter(mask, key, before, out, mask.reach() == 0 ? null : new SnapshotReader(ctx, ctx.world()), ctx.states());
    }

    /**
     * Clears from {@code out} (section {@code key}'s writes) every cell {@code mask} rejects, testing each against
     * {@code before} and, for neighbours outside the section, {@code around} ({@code null} for a cell-only mask).
     */
    static void filter(BoundMask mask, long key, SectionBuffer before, SectionBuffer out, WorldReader around,
                       StateSpace states) {
        int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
        WorldReader view = new SectionView(ox, oy, oz, before, around, states);
        BoundRules rules = mask instanceof BoundRules r ? r : null;
        BoundRules.Scratch scratch = rules != null && rules.usesSlope() ? new BoundRules.Scratch() : null;
        int[] rejected = new int[SectionBuffer.SIZE];
        int[] n = {0};
        out.forEachPresent(i -> {
            int x = ox + SectionBuffer.localX(i), y = oy + SectionBuffer.localY(i), z = oz + SectionBuffer.localZ(i);
            int state = before.get(i);
            boolean ok = rules != null ? rules.test(x, y, z, state, view, BoundRules.MEASURE_SLOPE, scratch)
                    : mask.test(x, y, z, state, view);
            if (!ok) rejected[n[0]++] = i;
        });
        for (int k = 0; k < n[0]; k++) out.clear(rejected[k]);
    }

    @Override
    public long[] readColumns(long key) {
        long[] own = program.readColumns(key);
        if (mask.reach() == 0) return own;
        int cx = BlockBuffer.keyX(key), cz = BlockBuffer.keyZ(key);
        long[] columns = new long[own.length + 8];
        System.arraycopy(own, 0, columns, 0, own.length);
        int n = own.length;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) columns[n++] = EditProgram.column(cx + dx, cz + dz);
            }
        }
        return columns;
    }

    @Override
    public boolean mayReplace(long key, int index, int liveState, ComputeContext ctx) {
        return program.mayReplace(key, index, liveState, ctx);
    }

    @Override
    public boolean mayReplace(long key, int index, int liveState, BlockEntityData liveTile, ComputeContext ctx) {
        return program.mayReplace(key, index, liveState, liveTile, ctx);
    }

    @Override
    public boolean relightsAfter() {
        return program.relightsAfter();
    }

    /**
     * One section's world before the edit: its own cells from {@code before}, every other cell from {@code around}
     * (air without it). Its own chunk is loaded; others as {@code around} says.
     */
    private static final class SectionView implements WorldReader {
        private final int ox, oy, oz;
        private final SectionBuffer before;
        private final WorldReader around;
        private final StateSpace states;

        SectionView(int ox, int oy, int oz, SectionBuffer before, WorldReader around, StateSpace states) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.before = before;
            this.around = around;
            this.states = states;
        }

        @Override
        public StateSpace states() {
            return states;
        }

        @Override
        public int bottomY() {
            return around == null ? Integer.MIN_VALUE : around.bottomY();
        }

        @Override
        public int topYExclusive() {
            return around == null ? Integer.MAX_VALUE : around.topYExclusive();
        }

        @Override
        public boolean isLoaded(int cx, int cz) {
            if (cx == ox >> 4 && cz == oz >> 4) return true;
            return around != null && around.isLoaded(cx, cz);
        }

        @Override
        public int get(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            if ((lx | ly | lz) >>> 4 == 0) {
                int state = before.get(SectionBuffer.index(lx, ly, lz));
                return state < 0 ? states.air() : state;
            }
            if (around == null) return states.air();
            if (y < around.bottomY() || y >= around.topYExclusive()) return states.air();
            return around.get(x, y, z);
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            int lx = x - ox, ly = y - oy, lz = z - oz;
            if ((lx | ly | lz) >>> 4 == 0) return before.tile(SectionBuffer.index(lx, ly, lz));
            return around == null ? null : around.tile(x, y, z);
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            if (sx == ox >> 4 && sy == oy >> 4 && sz == oz >> 4) {
                into.clearAll();
                for (int i = 0; i < SectionBuffer.SIZE; i++) {
                    int state = before.get(i);
                    into.set(i, state < 0 ? states.air() : state);
                }
                before.forEachTile(into::setTile);
            } else if (around != null) {
                around.copySection(sx, sy, sz, into);
            } else {
                into.clearAll();
                for (int i = 0; i < SectionBuffer.SIZE; i++) into.set(i, states.air());
            }
        }
    }
}
