package dev.sculptory.core.transform;

import dev.sculptory.core.BlockPos;
import java.util.Objects;

/**
 * How a free {@link Orientation} fills its result box from a source box: for each result cell, the source cell whose
 * block it takes, or none. Three ways, all deterministic (client ghost and server agree to the cell):
 * <ul>
 *   <li>{@link #nearest}: each result cell takes the source cell its centre turns back into ({@link Orientation.Sampler}).
 *       No holes inside a turned solid, but at angles off 90° some source cells are taken twice and some never.</li>
 *   <li>{@link #exact}: a bijection ({@link ShearTurn}): every source cell lands in exactly one result cell and no two
 *       land together, so the block count is kept. The rotation is done by three shears per axis.</li>
 *   <li>{@link #smooth}: each result cell weighs 27 points of itself turned back ({@link SmoothSampler}): it takes the
 *       most common block when more than half of them fall on blocks, else air. Fuller shapes, rounder edges.</li>
 * </ul>
 * Coordinates are local: result cells to the result box ({@link #size()}, its minimum corner at {@link #offset()} in the
 * intermediate box's coordinates, as {@link Orientation.Bounds}), source cells to the source box. A resampler is made
 * for one source box size, is immutable and is safe to share between threads.
 */
public interface CellResampler {
    /**
     * The source blocks a resampler may weigh ({@link #smooth} does; the others look only at positions).
     * Coordinates are source-local and inside the source box.
     */
    interface SourceCells {
        /** The state handle of source cell (x, y, z), or -1 when there is nothing there to take (absent, not moved). */
        int state(int x, int y, int z);

        /** Whether {@code state} is a block rather than air: what {@link #smooth} counts as covered. */
        boolean solid(int state);
    }

    /** Every source cell present and solid: a resampler's geometry alone (the cells it can fill). */
    SourceCells ALL = new SourceCells() {
        @Override
        public int state(int x, int y, int z) {
            return 0;
        }

        @Override
        public boolean solid(int state) {
            return true;
        }
    };

    /** Nearest block: {@link Orientation#sampler}. */
    static CellResampler nearest(Orientation orientation, int sx, int sy, int sz) {
        return Objects.requireNonNull(orientation).sampler(sx, sy, sz);
    }

    /** Exact count: {@link ShearTurn}. */
    static CellResampler exact(Orientation orientation, int sx, int sy, int sz) {
        return new ShearTurn(Objects.requireNonNull(orientation), sx, sy, sz);
    }

    /** Smooth: {@link SmoothSampler}. */
    static CellResampler smooth(Orientation orientation, int sx, int sy, int sz) {
        return new SmoothSampler(Objects.requireNonNull(orientation).sampler(sx, sy, sz));
    }

    Orientation orientation();

    /** The result box's size. */
    BlockPos size();

    /** The result box's minimum corner in the intermediate box's coordinates. */
    BlockPos offset();

    /** The source box's size. */
    BlockPos sourceSize();

    /**
     * The source cell result cell (x, y, z) takes, into {@code out[0..2]}; false (and {@code out} unspecified) when it
     * takes none. {@code cells} is read only by resamplers that weigh blocks.
     */
    boolean source(int x, int y, int z, SourceCells cells, int[] out);

    /** The most result cells {@link #takers} can give for one source cell. */
    int maxTakers();

    /**
     * The result cells whose {@link #source} is source cell (x, y, z), into {@code out} as triples ({@code out} holds at
     * least {@code 3 * maxTakers()} ints), in a fixed order (y, then z, then x); returns how many.
     */
    int takers(int x, int y, int z, SourceCells cells, int[] out);

    /**
     * The result cells that can take a source cell of the source-local box {@code [x0, x1] × [y0, y1] × [z0, z1]}
     * (inclusive), cut to the result box, as {@code {x0, y0, z0, x1, y1, z1}} inclusive; {@code null} when none can.
     */
    int[] turnedBox(int x0, int y0, int z0, int x1, int y1, int z1);

    /** The ideal (continuous) rotation: where source-local point (x, y, z) lands, result-local, into {@code out}. */
    void forward(double x, double y, double z, double[] out);

    /** The inverse of {@link #forward}: the source-local point result-local point (x, y, z) turns back to. */
    void back(double x, double y, double z, double[] out);
}
