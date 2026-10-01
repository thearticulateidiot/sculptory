package dev.sculptory.core.path;

import dev.sculptory.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Samples a {@link PathSpec}. Deterministic: {@code + - * /} and
 * {@code StrictMath} only, so the client's preview and what it uploads agree.
 *
 * <p>The line runs through the centres of its points' blocks. A point equal to the one before it adds nothing (a
 * double click); a line whose points are all one block is that block's centre alone. The kinds:
 * <ul>
 *   <li>{@link PathKind#STRAIGHT}: straight segments from point to point.</li>
 *   <li>{@link PathKind#CURVE}: a uniform 3D Catmull-Rom spline through every point, as the road generator's. With
 *   four points or more and the last equal to the first it closes into a loop (smooth where it closes).</li>
 *   <li>{@link PathKind#HANGING}: per span, a catenary that sags {@code sag} blocks below the straight chord at the
 *   span's middle. The drop is vertical and follows the catenary of the span's horizontal length (a span straight up
 *   or down does not sag).</li>
 * </ul>
 *
 * <p>Each segment is sampled evenly in its parameter, with the count doubled until no two consecutive samples are
 * more than {@code maxSpacing} apart; the samples of every segment start at its first point exactly, and the last
 * sample is the last point exactly.
 */
public final class PathSampler {
    /** The most samples one call makes: a line needing more is refused (its kernel's cell cap is far lower). */
    public static final long MAX_SAMPLES = 1L << 23;
    /** The smallest spacing a caller may ask for (blocks). */
    public static final double MIN_SPACING = 1.0 / 64;
    /** The spacing {@link #length} measures at (blocks). */
    static final double LENGTH_SPACING = 0.125;
    /** Below this the span's catenary is its parabola (the two agree to far below a block). */
    private static final double PARABOLA_BELOW = 1e-4;
    /** The catenary parameter's cap: {@code cosh} of it stays finite. */
    private static final double MAX_CATENARY_U = 700;

    private PathSampler() {}

    /** Receives samples in order along the line. */
    @FunctionalInterface
    public interface Visitor {
        void visit(double x, double y, double z, double along);
    }

    /**
     * Points along the line from its first point to its last, both included, no two consecutive ones more than
     * {@code maxSpacing} blocks apart.
     *
     * @throws IllegalArgumentException for a spacing that is not finite or below {@link #MIN_SPACING}, or a line
     *     needing more than {@link #MAX_SAMPLES} samples
     */
    public static List<PathSample> sample(PathSpec spec, double maxSpacing) {
        Objects.requireNonNull(spec);
        List<PathSample> out = new ArrayList<>();
        visit(spec, maxSpacing, (x, y, z, along) -> out.add(new PathSample(x, y, z, along)));
        return out;
    }

    /** The line's length in blocks (measured along samples {@value #LENGTH_SPACING} blocks apart at most). */
    public static double length(PathSpec spec) {
        Objects.requireNonNull(spec);
        double[] last = new double[1];
        visit(spec, LENGTH_SPACING, (x, y, z, along) -> last[0] = along);
        return last[0];
    }

    /**
     * The length of the straight chords between the points: at most {@link #length}, so a lower bound a kernel can
     * test against its cap before sampling anything.
     */
    public static double chordLength(PathSpec spec) {
        Objects.requireNonNull(spec);
        List<BlockPos> points = spec.points();
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            BlockPos a = points.get(i - 1), b = points.get(i);
            total += distance(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
        }
        return total;
    }

    /**
     * Whether the line closes into a loop: a {@link PathKind#CURVE} of four points or more (repeats dropped) whose last
     * equals its first. The other kinds just end where they began.
     */
    public static boolean closed(PathSpec spec) {
        List<BlockPos> points = distinct(spec.points());
        return spec.kind() == PathKind.CURVE && points.size() >= 4 && points.get(0).equals(points.get(points.size() - 1));
    }

    /** {@link #sample} without the list: each sample goes to {@code visitor} as it is made. */
    public static void visit(PathSpec spec, double maxSpacing, Visitor visitor) {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(visitor);
        if (!(maxSpacing >= MIN_SPACING) || !Double.isFinite(maxSpacing)) {
            throw new IllegalArgumentException("Sample spacing must be finite and at least " + MIN_SPACING);
        }
        List<BlockPos> points = distinct(spec.points());
        int n = points.size();
        double[] px = new double[n], py = new double[n], pz = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = points.get(i).x() + 0.5;
            py[i] = points.get(i).y() + 0.5;
            pz[i] = points.get(i).z() + 0.5;
        }
        if (n == 1) {
            visitor.visit(px[0], py[0], pz[0], 0);
            return;
        }
        boolean closed = closed(spec);
        // A loop's last point is its first: the controls wrap over the others.
        int controls = closed ? n - 1 : n;
        Segment segment = new Segment(spec.kind(), px, py, pz, controls, closed, spec.sag());
        double[] point = new double[3];
        long samples = 0;
        double along = 0;
        double lastX = px[0], lastY = py[0], lastZ = pz[0];
        boolean first = true;
        for (int s = 0; s + 1 < n; s++) {
            segment.select(s);
            int steps = segment.steps(maxSpacing, samples);
            for (int j = 0; j < steps; j++) {
                segment.at((double) j / steps, point);
                if (!first) along += distance(point[0] - lastX, point[1] - lastY, point[2] - lastZ);
                visitor.visit(point[0], point[1], point[2], along);
                first = false;
                lastX = point[0];
                lastY = point[1];
                lastZ = point[2];
            }
            samples += steps;
        }
        along += distance(px[n - 1] - lastX, py[n - 1] - lastY, pz[n - 1] - lastZ);
        visitor.visit(px[n - 1], py[n - 1], pz[n - 1], along);
    }

    /** The points without consecutive repeats. */
    static List<BlockPos> distinct(List<BlockPos> points) {
        List<BlockPos> out = new ArrayList<>(points.size());
        for (BlockPos point : points) {
            if (out.isEmpty() || !out.get(out.size() - 1).equals(point)) out.add(point);
        }
        return out;
    }

    static double distance(double dx, double dy, double dz) {
        return StrictMath.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** One segment of the line (from point {@code s} to point {@code s + 1}), evaluated at a parameter in [0, 1]. */
    private static final class Segment {
        private final PathKind kind;
        private final double[] px, py, pz;
        private final int controls;
        private final boolean closed;
        private final double sag;
        private int i0, i1, i2, i3;
        /** Hanging: the span's catenary half-parameter (0: its parabola), and cosh of it. */
        private double u, coshU;
        private boolean sags;

        Segment(PathKind kind, double[] px, double[] py, double[] pz, int controls, boolean closed, double sag) {
            this.kind = kind;
            this.px = px;
            this.py = py;
            this.pz = pz;
            this.controls = controls;
            this.closed = closed;
            this.sag = sag;
        }

        void select(int s) {
            i1 = s;
            i2 = s + 1;
            if (kind == PathKind.CURVE) {
                i0 = control(s - 1);
                i1 = control(s);
                i2 = control(s + 1);
                i3 = control(s + 2);
            }
            if (kind == PathKind.HANGING) {
                double span = StrictMath.sqrt(sq(px[i2] - px[i1]) + sq(pz[i2] - pz[i1]));
                sags = sag > 0 && span > 1e-9;
                u = sags ? catenaryU(span, sag) : 0;
                coshU = StrictMath.cosh(u);
            }
        }

        private int control(int index) {
            if (closed) return Math.floorMod(index, controls);
            return Math.max(0, Math.min(controls - 1, index));
        }

        /** The steps this segment is sampled in: doubled from its chord's until no step is over {@code spacing}. */
        int steps(double spacing, long samplesSoFar) {
            double chord = distance(px[i2] - px[i1], py[i2] - py[i1], pz[i2] - pz[i1]);
            double wanted = StrictMath.ceil(chord / spacing);
            long steps = (long) StrictMath.max(1, wanted);
            if (samplesSoFar + steps > MAX_SAMPLES) throw tooLong();
            double[] a = new double[3], b = new double[3];
            while (true) {
                if (samplesSoFar + steps > MAX_SAMPLES) throw tooLong();
                boolean fits = true;
                at(0, a);
                for (long j = 1; j <= steps && fits; j++) {
                    at((double) j / steps, b);
                    if (distance(b[0] - a[0], b[1] - a[1], b[2] - a[2]) > spacing) fits = false;
                    double[] t = a;
                    a = b;
                    b = t;
                }
                if (fits) return (int) steps;
                steps *= 2;
            }
        }

        void at(double t, double[] out) {
            switch (kind) {
                case STRAIGHT -> lerp(t, out);
                case CURVE -> {
                    out[0] = catmullRom(px[i0], px[i1], px[i2], px[i3], t);
                    out[1] = catmullRom(py[i0], py[i1], py[i2], py[i3], t);
                    out[2] = catmullRom(pz[i0], pz[i1], pz[i2], pz[i3], t);
                }
                case HANGING -> {
                    lerp(t, out);
                    if (sags) out[1] -= drop(t);
                }
            }
        }

        private void lerp(double t, double[] out) {
            out[0] = px[i1] + t * (px[i2] - px[i1]);
            out[1] = py[i1] + t * (py[i2] - py[i1]);
            out[2] = pz[i1] + t * (pz[i2] - pz[i1]);
        }

        /** How far below the chord the span hangs at parameter t: 0 at both ends, {@code sag} at the middle. */
        private double drop(double t) {
            if (t <= 0 || t >= 1) return 0;
            if (u < PARABOLA_BELOW) return sag * 4 * t * (1 - t);
            double v = 2 * u * (t - 0.5);
            return sag * (coshU - StrictMath.cosh(v)) / (coshU - 1);
        }
    }

    /**
     * The catenary {@code y = a cosh(x / a)} over a horizontal span that sags {@code sag} at its middle, as
     * {@code u = span / (2a)}: the root of {@code (cosh u - 1) / (2u) = sag / span}, by bisection (the left side grows
     * with u).
     */
    static double catenaryU(double span, double sag) {
        double ratio = sag / span;
        double lo = 0, hi = MAX_CATENARY_U;
        if (sagRatio(hi) <= ratio) return hi;
        for (int i = 0; i < 200; i++) {
            double mid = (lo + hi) / 2;
            if (mid <= lo || mid >= hi) break;
            if (sagRatio(mid) < ratio) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return (lo + hi) / 2;
    }

    /** {@code (cosh u - 1) / (2u)}, the sag per span of a catenary; {@code u / 4} near 0. */
    private static double sagRatio(double u) {
        if (u < PARABOLA_BELOW) return u / 4;
        return (StrictMath.cosh(u) - 1) / (2 * u);
    }

    private static double catmullRom(double p0, double p1, double p2, double p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return 0.5 * (2 * p1 + (p2 - p0) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (3 * p1 - p0 - 3 * p2 + p3) * t3);
    }

    private static double sq(double v) {
        return v * v;
    }

    private static IllegalArgumentException tooLong() {
        return new IllegalArgumentException("A line of more than " + MAX_SAMPLES + " samples");
    }
}
