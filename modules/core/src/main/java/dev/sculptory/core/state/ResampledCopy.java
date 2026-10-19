package dev.sculptory.core.state;

import dev.sculptory.core.transform.CellResampler;
import java.util.ArrayDeque;
import java.util.Objects;

/**
 * A free copy's resampling as the paste and move programs and the client ghost use it, for one source: the cells
 * ({@link #cells()}: the chosen way, with two-cell blocks kept whole, {@link TwoCellPairs}) and the state each result cell
 * writes ({@link #state}: the source state turned by {@link OrientedStates}, then, with smooth slopes, the stair or slab
 * {@link SlopeShapes} picks). Everyone who builds one from the same source, way and orientation gets the same cells and
 * states, so the ghost shows what the server writes.
 */
public final class ResampledCopy {
    /** The tables {@link Tables#shared} gave last, newest first. */
    private static final ArrayDeque<Tables> SHARED = new ArrayDeque<>(2);

    private final TwoCellPairs cells;
    private final CellResampler.SourceCells source;
    private final OrientedStates oriented;
    private final SlopeShapes slopes;

    private ResampledCopy(TwoCellPairs cells, CellResampler.SourceCells source, OrientedStates oriented,
                          SlopeShapes slopes) {
        this.cells = cells;
        this.source = source;
        this.oriented = oriented;
        this.slopes = slopes;
    }

    /**
     * The per-state-space lookups a copy needs (which states are halves, which blocks have stairs and slabs), built once
     * and shared by every copy over the same state space (they cache per handle and are thread-safe).
     */
    public record Tables(TwoCellBlocks blocks, StairSlabVariants variants) {
        public Tables {
            Objects.requireNonNull(blocks);
            Objects.requireNonNull(variants);
            if (blocks.states() != variants.states()) throw new IllegalArgumentException("Tables of two state spaces");
        }

        public static Tables of(StateSpace states) {
            return new Tables(new TwoCellBlocks(states), new StairSlabVariants(states));
        }

        /**
         * The tables of {@code states}, kept for the two state spaces last asked for (a singleplayer game has the
         * server's and the client's; a client gets a new one per join, and the oldest one's tables go).
         */
        public static Tables shared(StateSpace states) {
            Objects.requireNonNull(states);
            synchronized (SHARED) {
                for (Tables t : SHARED) {
                    if (t.blocks().states() == states) return t;
                }
                Tables made = of(states);
                SHARED.addFirst(made);
                if (SHARED.size() > 2) SHARED.removeLast();
                return made;
            }
        }
    }

    /** {@link #of(CellResampler, CellResampler.SourceCells, OrientedStates, boolean, long[], Tables)} with new tables. */
    public static ResampledCopy of(CellResampler base, CellResampler.SourceCells source, OrientedStates oriented,
                                   boolean smoothSlopes, long[] halves) {
        return of(base, source, oriented, smoothSlopes, halves, Tables.of(oriented.states()));
    }

    /**
     * @param base the way the cells are filled (nearest, exact count or smooth), for the source's size
     * @param source the source blocks (what smooth and the slopes weigh, and what the pairs read)
     * @param oriented the orientation's state map
     * @param smoothSlopes whether turned slopes get stairs and slabs
     * @param halves the source-local cells (packed with {@link TwoCellPairs#pack}) that may hold a two-cell block's
     *     half, or null to look through the whole source box
     * @param tables the state space's lookups ({@link Tables#of})
     */
    public static ResampledCopy of(CellResampler base, CellResampler.SourceCells source, OrientedStates oriented,
                                   boolean smoothSlopes, long[] halves, Tables tables) {
        return of(base, source, oriented, smoothSlopes, halves, tables, (x, y, z) -> true);
    }

    /**
     * {@link #of(CellResampler, CellResampler.SourceCells, OrientedStates, boolean, long[], Tables)} for a copy that
     * writes only the result cells {@code writable} accepts (a move: the cells it worked out at admission), so no
     * two-cell block is placed half outside them ({@link TwoCellPairs#of(CellResampler, CellResampler.SourceCells,
     * TwoCellBlocks, java.util.function.IntUnaryOperator, long[], TwoCellPairs.Writable)}).
     */
    public static ResampledCopy of(CellResampler base, CellResampler.SourceCells source, OrientedStates oriented,
                                   boolean smoothSlopes, long[] halves, Tables tables, TwoCellPairs.Writable writable) {
        Objects.requireNonNull(base);
        Objects.requireNonNull(source);
        Objects.requireNonNull(oriented);
        if (tables.blocks().states() != oriented.states()) {
            throw new IllegalArgumentException("Tables of another state space");
        }
        TwoCellBlocks blocks = tables.blocks();
        long[] candidates = halves != null ? halves : TwoCellPairs.halves(base, source, blocks);
        TwoCellPairs pairs = TwoCellPairs.of(base, source, blocks, oriented::map, candidates, writable);
        return new ResampledCopy(pairs, source, oriented,
                smoothSlopes ? new SlopeShapes(tables.variants()) : null);
    }

    /** Which source cell each result cell takes. */
    public CellResampler cells() {
        return cells;
    }

    /** The state result cell (x, y, z) writes for {@code state}, the state of the source cell it takes. */
    public int state(int x, int y, int z, int state) {
        int turned = oriented.map(state);
        if (slopes == null) return turned;
        int shaped = slopes.shape(cells, source, x, y, z, turned);
        return shaped == SlopeShapes.KEEP ? turned : shaped;
    }

    /** Whether the slopes are shaped (the state of a cell may then be a stair or slab of another block). */
    public boolean smoothSlopes() {
        return slopes != null;
    }

    /** Two-cell blocks dropped whole for want of room (two blocks each), for the counts the ghost shows. */
    public int droppedPairs() {
        return cells.droppedPairs();
    }

    /** Blocks a kept-whole pair displaced that found no cell (never with exact count). */
    public long lostBlocks() {
        return cells.lostBlocks();
    }

    /** Whether result cell (x, y, z) holds a placed pair's half; its other half's cell into {@code out}. */
    public boolean partner(int x, int y, int z, int[] out) {
        return cells.partner(x, y, z, out);
    }

    /** Every half of every placed two-cell block, with the other half's result cell. */
    public void forEachHalf(TwoCellPairs.PairVisitor visitor) {
        cells.forEachHalf(visitor);
    }

    /** Whether any two-cell block was placed (so {@link #partner} can answer yes). */
    public boolean hasPairs() {
        return cells.hasPairs();
    }
}
