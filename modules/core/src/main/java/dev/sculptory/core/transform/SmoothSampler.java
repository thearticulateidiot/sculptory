package dev.sculptory.core.transform;

import dev.sculptory.core.BlockPos;
import java.util.Objects;

/**
 * The "smooth" resampler: each result cell weighs 27 points of itself (a 3×3×3 grid at its centre and a third of a block
 * either side along each axis), turned back into the source box with the arithmetic of {@link Orientation.Sampler}
 * (its centre point is exactly the nearest resampler's).
 * <ul>
 *   <li>More than half the points on blocks ({@link SourceCells#solid}): the cell takes the most common block among
 *       them. A tie goes to the block at the centre point if it is one of the tied, else to the tied block met first in
 *       the fixed point order (centre first).</li>
 *   <li>Otherwise, more than half the points on source cells at all (air included): the cell takes air, from the centre
 *       point's cell when that is air, else from the first air point.</li>
 *   <li>Otherwise it takes nothing.</li>
 * </ul>
 * So a turned solid keeps its inside (every point inside is on a block), and its edges are where the shape covers half
 * the cell, not where one point happens to fall: fuller shapes, rounder edges. Quarter turns are exact (all 27 points of
 * a cell fall in one source cell). The cell given back for a block is a cell holding it (for its block entity). The
 * result box is the nearest resampler's: a cell whose centre lies outside the turned box has at most 13 of its points
 * inside, as the points are symmetric about the centre and the box is convex. Immutable and thread-safe.
 */
public final class SmoothSampler implements CellResampler {
    /** The points' offsets from the cell centre, in blocks, the centre first. */
    private static final double[] OFFSETS = offsets();
    private static final int POINTS = 27;
    /** More than half the points. */
    private static final int MAJORITY = POINTS / 2 + 1;

    private final Orientation.Sampler sampler;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    SmoothSampler(Orientation.Sampler sampler) {
        this.sampler = Objects.requireNonNull(sampler);
    }

    private static final class Scratch {
        final int[] cell = new int[3];
        final int[] cells = new int[3 * POINTS];
        final int[] states = new int[POINTS];
        final boolean[] solid = new boolean[POINTS];
        final int[] distinct = new int[POINTS];
        final int[] counts = new int[POINTS];
        final int[] first = new int[POINTS];
        final double[] point = new double[3];
    }

    private static double[] offsets() {
        double third = 1.0 / 3;
        double[] steps = {0, -third, third};
        double[] out = new double[3 * POINTS];
        int n = 0;
        for (double ey : steps) {
            for (double ez : steps) {
                for (double ex : steps) {
                    out[3 * n] = ex;
                    out[3 * n + 1] = ey;
                    out[3 * n + 2] = ez;
                    n++;
                }
            }
        }
        return out;
    }

    @Override
    public Orientation orientation() {
        return sampler.orientation();
    }

    @Override
    public BlockPos size() {
        return sampler.size();
    }

    @Override
    public BlockPos offset() {
        return sampler.offset();
    }

    @Override
    public BlockPos sourceSize() {
        return sampler.sourceSize();
    }

    @Override
    public boolean source(int x, int y, int z, SourceCells cells, int[] out) {
        Scratch s = scratch.get();
        int present = 0, solid = 0;
        for (int p = 0; p < POINTS; p++) {
            s.states[p] = -1;
            if (!sampler.sourceAt(x, y, z, OFFSETS[3 * p], OFFSETS[3 * p + 1], OFFSETS[3 * p + 2], s.cell)) continue;
            int state = cells.state(s.cell[0], s.cell[1], s.cell[2]);
            if (state < 0) continue;
            s.states[p] = state;
            s.cells[3 * p] = s.cell[0];
            s.cells[3 * p + 1] = s.cell[1];
            s.cells[3 * p + 2] = s.cell[2];
            s.solid[p] = cells.solid(state);
            present++;
            if (s.solid[p]) solid++;
        }
        if (present < MAJORITY) return false;
        int pick = -1;
        if (solid >= MAJORITY) {
            // The most common block; ties to the centre's, then to the first met.
            int n = 0;
            for (int p = 0; p < POINTS; p++) {
                if (s.states[p] < 0 || !s.solid[p]) continue;
                int i = 0;
                while (i < n && s.distinct[i] != s.states[p]) i++;
                if (i == n) {
                    s.distinct[n] = s.states[p];
                    s.counts[n] = 0;
                    s.first[n] = p;
                    n++;
                }
                s.counts[i]++;
            }
            int best = 0;
            for (int i = 1; i < n; i++) {
                if (s.counts[i] > s.counts[best]) best = i;
            }
            // Among equally common blocks the centre's wins, else the first met (distinct is in first-met order).
            if (s.states[0] >= 0 && s.solid[0]) {
                for (int i = 0; i < n; i++) {
                    if (s.distinct[i] == s.states[0] && s.counts[i] == s.counts[best]) best = i;
                }
            }
            pick = s.first[best];
        } else {
            for (int p = 0; p < POINTS; p++) {
                if (s.states[p] >= 0 && !s.solid[p]) {
                    pick = p;
                    break;
                }
            }
        }
        out[0] = s.cells[3 * pick];
        out[1] = s.cells[3 * pick + 1];
        out[2] = s.cells[3 * pick + 2];
        return true;
    }

    /** A source cell is taken by result cells whose centres lie within about 1.5 blocks of where it lands. */
    @Override
    public int maxTakers() {
        return 125;
    }

    @Override
    public int takers(int x, int y, int z, SourceCells cells, int[] out) {
        Scratch s = scratch.get();
        sampler.forward(x + 0.5, y + 0.5, z + 0.5, s.point);
        int bx = (int) Math.floor(s.point[0]), by = (int) Math.floor(s.point[1]), bz = (int) Math.floor(s.point[2]);
        BlockPos size = size();
        int[] cell = new int[3];
        int n = 0;
        for (int ry = by - 2; ry <= by + 2; ry++) {
            if (ry < 0 || ry >= size.y()) continue;
            for (int rz = bz - 2; rz <= bz + 2; rz++) {
                if (rz < 0 || rz >= size.z()) continue;
                for (int rx = bx - 2; rx <= bx + 2; rx++) {
                    if (rx < 0 || rx >= size.x()) continue;
                    if (!source(rx, ry, rz, cells, cell) || cell[0] != x || cell[1] != y || cell[2] != z) continue;
                    out[3 * n] = rx;
                    out[3 * n + 1] = ry;
                    out[3 * n + 2] = rz;
                    n++;
                }
            }
        }
        return n;
    }

    /** The nearest resampler's turned box, one more cell wider (the points reach a third of a block further). */
    @Override
    public int[] turnedBox(int x0, int y0, int z0, int x1, int y1, int z1) {
        return sampler.turnedBox(x0, y0, z0, x1, y1, z1, 2);
    }

    @Override
    public void forward(double x, double y, double z, double[] out) {
        sampler.forward(x, y, z, out);
    }

    @Override
    public void back(double x, double y, double z, double[] out) {
        sampler.back(x, y, z, out);
    }
}
