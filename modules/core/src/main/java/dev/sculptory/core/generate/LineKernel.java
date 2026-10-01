package dev.sculptory.core.generate;

import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.path.CellBits;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.path.PathSampler;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Generate's Line: a round or square tube of blocks along a
 * {@link PathSpec}. Commits like Road and Roof, as a generated clipboard pasted with the {@code LINE} label.
 *
 * <p><b>Cells.</b> A line of thickness 1 is the cells its samples ({@value #CORE_SPACING} blocks apart) fall in: a
 * chain of cells that touch at least at a corner, like a drawn line. A thicker line is every cell whose centre lies
 * within {@code thickness / 2} of the line (as the Shape brush's cylinders: a thickness of 3 is a 3 × 3 square across),
 * or, square, within {@code thickness / 2} along both axes of the line's frame (one horizontal, square to the line; one
 * square to both). Open lines end flat at their first and last point; round lines bend round at their points, square
 * ones fill the outside of each bend (up to half the thickness past it). An even thickness has no middle block:
 * its line runs half a block off the points' centres (toward +x, +y, +z, across the line only, so its ends stay).
 */
public final class LineKernel {
    public static final int MIN_THICKNESS = 1;
    public static final int MAX_THICKNESS = 16;
    /** Thickness 1: samples this far apart at most, so consecutive ones fall in touching cells. */
    static final double CORE_SPACING = 0.25;
    /** Thicker lines: the curve is followed with segments this long at most. */
    static final double SEGMENT_SPACING = 0.5;
    /** Straight lines: pieces this long at most (only the bends between them matter). */
    private static final double STRAIGHT_SPACING = 2;
    private static final double EPSILON = 1e-9;

    private LineKernel() {}

    /** The cross-section of the line. */
    public enum Profile {
        ROUND,
        SQUARE
    }

    /**
     * @param thickness the tube's width across, {@value #MIN_THICKNESS}-{@value #MAX_THICKNESS} blocks
     * @param material the blocks laid (a block or a palette)
     */
    public record Spec(PathSpec path, int thickness, Profile profile, Pattern material) {
        public Spec {
            Objects.requireNonNull(path);
            Objects.requireNonNull(profile);
            Objects.requireNonNull(material);
            if (thickness < MIN_THICKNESS || thickness > MAX_THICKNESS) {
                throw new IllegalArgumentException("Line thickness must be " + MIN_THICKNESS + "-" + MAX_THICKNESS);
            }
            // A line's material is a block choice: a pattern reading the cell's state has no cell to read here.
            if (!(material instanceof Pattern.Single || material instanceof Pattern.Weighted
                    || material instanceof Pattern.Arranged)) {
                throw new IllegalArgumentException("A line's material is a block or a mix: " + material);
            }
        }
    }

    /**
     * The line's cells, with states of {@code states}.
     *
     * @throws GeneratedTooLargeException when it would have more than {@code maxCells} cells
     */
    public static GeneratedSource generate(Spec spec, StateSpace states, long maxCells) throws GeneratedTooLargeException {
        return generate(spec, states, maxCells, Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    /**
     * {@link #generate(Spec, StateSpace, long)} within {@code [bottomY, topYExclusive)}: the cells above or below are
     * left out (and not counted).
     */
    public static GeneratedSource generate(Spec spec, StateSpace states, long maxCells, int bottomY, int topYExclusive)
            throws GeneratedTooLargeException {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(states);
        CellBits cells = cells(spec, maxCells, bottomY, topYExclusive);
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        Pattern material = spec.material();
        cells.forEach((x, y, z) -> out.set(x, y, z, material.apply(states, x, y, z, -1)));
        return out.build();
    }

    /**
     * The cells of the line (see the class comment), within {@code [bottomY, topYExclusive)}.
     *
     * @throws GeneratedTooLargeException over {@code maxCells}
     */
    public static CellBits cells(Spec spec, long maxCells, int bottomY, int topYExclusive) {
        Objects.requireNonNull(spec);
        // Every block of a line's length adds a cell at least every √3 blocks: refuse a hopeless one before sampling.
        if (PathSampler.chordLength(spec.path()) / StrictMath.sqrt(3) > (double) maxCells + 1) {
            throw new GeneratedTooLargeException(maxCells);
        }
        CellBits cells = new CellBits(maxCells);
        try {
            if (spec.thickness() == 1) {
                PathSampler.visit(spec.path(), CORE_SPACING, (x, y, z, along) -> {
                    int cy = floor(y);
                    if (cy >= bottomY && cy < topYExclusive) cells.add(floor(x), cy, floor(z));
                });
            } else {
                double spacing = spec.path().kind() == PathKind.STRAIGHT ? STRAIGHT_SPACING : SEGMENT_SPACING;
                tube(PathSampler.sample(spec.path(), spacing), PathSampler.closed(spec.path()), spec.thickness(),
                        spec.profile(), cells, bottomY, topYExclusive);
            }
        } catch (CellBits.FullException full) {
            throw new GeneratedTooLargeException(maxCells);
        } catch (IllegalArgumentException tooManySamples) {
            throw new GeneratedTooLargeException(maxCells);
        }
        return cells;
    }

    /** The cells within the thickness of the polyline through {@code samples}. */
    private static void tube(List<PathSample> samples, boolean closed, int thickness, Profile profile, CellBits cells,
                             int bottomY, int topYExclusive) {
        double r = thickness / 2.0;
        boolean even = (thickness & 1) == 0;
        // The polyline without repeated samples.
        List<double[]> points = new ArrayList<>(samples.size());
        List<Double> along = new ArrayList<>(samples.size());
        for (PathSample s : samples) {
            double[] last = points.isEmpty() ? null : points.get(points.size() - 1);
            if (last != null && last[0] == s.x() && last[1] == s.y() && last[2] == s.z()) continue;
            points.add(new double[] {s.x(), s.y(), s.z()});
            along.add(s.along());
        }
        Raster bounds = new Raster(r, bottomY, topYExclusive, profile);
        int n = points.size();
        if (n == 1) {
            // All the points in one block: a ball (or cube) of the thickness around it.
            double[] p = points.get(0).clone();
            if (even) {
                p[0] += 0.5;
                p[1] += 0.5;
                p[2] += 0.5;
            }
            bounds.point(p, cells);
            return;
        }
        double total = along.get(n - 1);
        Ends ends = closed ? null : new Ends(points, even);
        int segments = n - 1;
        for (int i = 0; i < segments; i++) {
            double[] a = points.get(i), b = points.get(i + 1);
            // How far the segment reaches past each end: square lines fill the outside of each bend.
            double before = 0, after = 0;
            if (profile == Profile.SQUARE) {
                if (i > 0 || closed) before = mitre(points.get(i > 0 ? i - 1 : n - 2), a, b, r);
                if (i + 1 < segments || closed) after = mitre(a, b, points.get(i + 1 < segments ? i + 2 : 1), r);
            }
            boolean nearStart = ends != null && along.get(i) < r + 2 + before;
            boolean nearEnd = ends != null && total - along.get(i + 1) < r + 2 + after;
            bounds.segment(a, b, even, before, after, nearStart ? ends : null, nearEnd ? ends : null, cells);
        }
    }

    /** How far past the bend at {@code b} (from {@code a}, on to {@code c}) a square segment must reach: r tan(θ / 2). */
    private static double mitre(double[] a, double[] b, double[] c, double r) {
        double x1 = b[0] - a[0], y1 = b[1] - a[1], z1 = b[2] - a[2];
        double x2 = c[0] - b[0], y2 = c[1] - b[1], z2 = c[2] - b[2];
        double l1 = StrictMath.sqrt(x1 * x1 + y1 * y1 + z1 * z1), l2 = StrictMath.sqrt(x2 * x2 + y2 * y2 + z2 * z2);
        if (l1 == 0 || l2 == 0) return r;
        double cos = (x1 * x2 + y1 * y2 + z1 * z2) / (l1 * l2);
        cos = StrictMath.max(-1, StrictMath.min(1, cos));
        if (cos >= 1) return 0;
        // Past a right angle the tangent grows without bound: half the thickness is as far as it helps.
        if (cos <= 0) return r;
        return StrictMath.min(r, r * StrictMath.sqrt((1 - cos) / (1 + cos)));
    }

    /** The planes an open line ends flat at: through its first and last point, square to the line there. */
    private static final class Ends {
        final double[] start, startDir, end, endDir;

        Ends(List<double[]> points, boolean even) {
            int n = points.size();
            double[] s0 = points.get(0), s1 = points.get(1);
            double[] e0 = points.get(n - 1), e1 = points.get(n - 2);
            start = s0.clone();
            startDir = new double[] {s1[0] - s0[0], s1[1] - s0[1], s1[2] - s0[2]};
            end = e0.clone();
            endDir = new double[] {e0[0] - e1[0], e0[1] - e1[1], e0[2] - e1[2]};
            if (even) {
                shift(start, startDir[0], startDir[1], startDir[2]);
                shift(end, endDir[0], endDir[1], endDir[2]);
            }
        }

        /** Whether the cell centre (x, y, z) lies before the start or past the end. */
        boolean outsideStart(double x, double y, double z) {
            return (x - start[0]) * startDir[0] + (y - start[1]) * startDir[1] + (z - start[2]) * startDir[2] < -EPSILON;
        }

        boolean outsideEnd(double x, double y, double z) {
            return (x - end[0]) * endDir[0] + (y - end[1]) * endDir[1] + (z - end[2]) * endDir[2] > EPSILON;
        }
    }

    /** Rasterizes segments and points of one thickness and profile into cells, within the build height. */
    private static final class Raster {
        private final double r, r2;
        private final int bottomY, topYExclusive;
        private final Profile profile;

        Raster(double r, int bottomY, int topYExclusive, Profile profile) {
            this.r = r;
            this.r2 = r * r + EPSILON;
            this.bottomY = bottomY;
            this.topYExclusive = topYExclusive;
            this.profile = profile;
        }

        /** A lone point: a ball, or a cube, of the thickness. */
        void point(double[] p, CellBits cells) {
            int x0 = floor(p[0] - r - 1), x1 = floor(p[0] + r + 1);
            int y0 = clampY(floor(p[1] - r - 1)), y1 = clampTop(floor(p[1] + r + 1));
            int z0 = floor(p[2] - r - 1), z1 = floor(p[2] + r + 1);
            for (int y = y0; y <= y1; y++) {
                double qy = y + 0.5 - p[1];
                for (int z = z0; z <= z1; z++) {
                    double qz = z + 0.5 - p[2];
                    for (int x = x0; x <= x1; x++) {
                        double qx = x + 0.5 - p[0];
                        boolean inside = profile == Profile.ROUND ? qx * qx + qy * qy + qz * qz <= r2
                                : StrictMath.abs(qx) <= r + EPSILON && StrictMath.abs(qy) <= r + EPSILON
                                        && StrictMath.abs(qz) <= r + EPSILON;
                        if (inside) cells.add(x, y, z);
                    }
                }
            }
        }

        /**
         * One segment from {@code a} to {@code b} (distinct): round, the cells within r of it; square, within r of it
         * along both axes of its frame, reaching {@code before} and {@code after} blocks past its ends.
         */
        void segment(double[] a, double[] b, boolean even, double before, double after, Ends start, Ends end,
                     CellBits cells) {
            double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
            double length2 = dx * dx + dy * dy + dz * dz;
            double length = StrictMath.sqrt(length2);
            double[] pa = a.clone(), pb = b.clone();
            if (even) {
                shift(pa, dx, dy, dz);
                shift(pb, dx, dy, dz);
            }
            // The square frame: u horizontal and square to the segment (x for a vertical one), v square to both.
            double tx = dx / length, ty = dy / length, tz = dz / length;
            double ux = -tz, uz = tx;
            double uLength = StrictMath.sqrt(ux * ux + uz * uz);
            if (uLength < 1e-9) {
                ux = 1;
                uz = 0;
            } else {
                ux /= uLength;
                uz /= uLength;
            }
            double vx = ty * uz, vy = tz * ux - tx * uz, vz = -ty * ux;
            double reach = profile == Profile.ROUND ? r + 1 : r * StrictMath.sqrt(2) + StrictMath.max(before, after) + 1;
            int x0 = floor(StrictMath.min(pa[0], pb[0]) - reach), x1 = floor(StrictMath.max(pa[0], pb[0]) + reach);
            int y0 = clampY(floor(StrictMath.min(pa[1], pb[1]) - reach));
            int y1 = clampTop(floor(StrictMath.max(pa[1], pb[1]) + reach));
            int z0 = floor(StrictMath.min(pa[2], pb[2]) - reach), z1 = floor(StrictMath.max(pa[2], pb[2]) + reach);
            double lo = -before / length, hi = 1 + after / length;
            for (int y = y0; y <= y1; y++) {
                double cy = y + 0.5;
                for (int z = z0; z <= z1; z++) {
                    double cz = z + 0.5;
                    for (int x = x0; x <= x1; x++) {
                        double cx = x + 0.5;
                        if (start != null && start.outsideStart(cx, cy, cz)) continue;
                        if (end != null && end.outsideEnd(cx, cy, cz)) continue;
                        double wx = cx - pa[0], wy = cy - pa[1], wz = cz - pa[2];
                        double raw = (wx * dx + wy * dy + wz * dz) / length2;
                        boolean inside;
                        if (profile == Profile.ROUND) {
                            double t = StrictMath.max(0, StrictMath.min(1, raw));
                            double qx = wx - t * dx, qy = wy - t * dy, qz = wz - t * dz;
                            inside = qx * qx + qy * qy + qz * qz <= r2;
                        } else {
                            if (raw < lo - EPSILON || raw > hi + EPSILON) continue;
                            double qx = wx - raw * dx, qy = wy - raw * dy, qz = wz - raw * dz;
                            inside = StrictMath.abs(qx * ux + qz * uz) <= r + EPSILON
                                    && StrictMath.abs(qx * vx + qy * vy + qz * vz) <= r + EPSILON;
                        }
                        if (inside) cells.add(x, y, z);
                    }
                }
            }
        }

        private int clampY(int y) {
            return StrictMath.max(y, bottomY);
        }

        private int clampTop(int y) {
            return (int) StrictMath.min(y, (long) topYExclusive - 1);
        }
    }

    /** Moves {@code p} half a block toward +x, +y, +z, across the direction (dx, dy, dz) only. */
    private static void shift(double[] p, double dx, double dy, double dz) {
        double length2 = dx * dx + dy * dy + dz * dz;
        double along = length2 == 0 ? 0 : (0.5 * dx + 0.5 * dy + 0.5 * dz) / length2;
        p[0] += 0.5 - along * dx;
        p[1] += 0.5 - along * dy;
        p[2] += 0.5 - along * dz;
    }

    static int floor(double v) {
        return (int) StrictMath.floor(v);
    }
}
