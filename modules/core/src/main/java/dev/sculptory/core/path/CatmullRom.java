package dev.sculptory.core.path;

/**
 * The centripetal Catmull-Rom spline (alpha 0.5) every Curve uses: Generate's Line and Sweep, the Shape brush's Line,
 * Extrude's Along a path ({@link PathSampler}) and the Road's centre line ({@code RoadKernel}, in x and z).
 *
 * <p>Each segment runs from {@code p1} to {@code p2}, shaped by the points either side, {@code p0} and {@code p3}; the
 * knots are spaced by the square root of the distance between neighbouring points, so a short step next to a long one
 * no longer swings past its points (the uniform spline did: a short step followed by a long climb dipped below the start
 * and drifted sideways). An end of the line repeats its end point as the outer control ({@code p0 == p1} or
 * {@code p3 == p2}): the curve then leaves (or reaches) the end along its chord. A segment whose two points coincide is
 * that point.
 *
 * <p>Deterministic: {@code + - * /} and {@code StrictMath} only, so the client's preview and what it builds agree.
 */
public final class CatmullRom {
    /** The knot exponent: 0.5 is the centripetal spline (0 would be the uniform one, 1 the chordal one). */
    public static final double ALPHA = 0.5;
    /** Points closer than this (blocks) count as one: the knot between them is empty. */
    static final double SAME_POINT = 1e-9;

    private CatmullRom() {}

    /**
     * The point at {@code t} (0 at {@code p1}, 1 at {@code p2}) of the segment from {@code p1} to {@code p2}, into
     * {@code out}; every array has the same length (2 for x and z, 3 for x, y and z).
     */
    public static void at(double[] p0, double[] p1, double[] p2, double[] p3, double t, double[] out) {
        int n = out.length;
        double d01 = distance(p0, p1), d12 = distance(p1, p2), d23 = distance(p2, p3);
        if (d12 < SAME_POINT) {
            System.arraycopy(p1, 0, out, 0, n);
            return;
        }
        // Knot intervals: the square root of each chord (alpha 0.5).
        double k01 = StrictMath.sqrt(d01), k12 = StrictMath.sqrt(d12), k23 = StrictMath.sqrt(d23);
        double t2 = t * t, t3 = t2 * t;
        double h00 = 2 * t3 - 3 * t2 + 1, h10 = t3 - 2 * t2 + t, h01 = -2 * t3 + 3 * t2, h11 = t3 - t2;
        for (int i = 0; i < n; i++) {
            double chord = (p2[i] - p1[i]) / k12;
            // The tangents of the non-uniform spline at p1 and p2 (per unit knot); an empty interval at an end leaves
            // the tangent along the chord.
            double m1 = d01 < SAME_POINT ? chord : (p1[i] - p0[i]) / k01 - (p2[i] - p0[i]) / (k01 + k12) + chord;
            double m2 = d23 < SAME_POINT ? chord : chord - (p3[i] - p1[i]) / (k12 + k23) + (p3[i] - p2[i]) / k23;
            out[i] = h00 * p1[i] + h10 * k12 * m1 + h01 * p2[i] + h11 * k12 * m2;
        }
    }

    private static double distance(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            double d = b[i] - a[i];
            sum += d * d;
        }
        return StrictMath.sqrt(sum);
    }
}
