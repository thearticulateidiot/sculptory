package dev.sculptory.core.path;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Frames along a line: at each of its samples, where it heads ({@code t}), which way is right ({@code u}) and which way
 * is up ({@code v}), for carrying a cross-section along it (Extrude along a path, Generate's Sweep) or giving a line a
 * square profile (the Line's square frame). Every frame is unit and square, with {@code v = u × t}: heading east,
 * right is south and up is up. Deterministic: {@code + - * /} and {@code StrictMath} only.
 *
 * <p>The kinds:
 * <ul>
 *   <li>{@link Mode#UPRIGHT}: {@code t} is the heading (the tangent flattened), {@code u} its right, {@code v} straight
 *       up. The cross-section turns with the heading and never tilts.</li>
 *   <li>{@link Mode#FOLLOW_SLOPE}: {@code t} is the tangent, {@code u} the horizontal right of it, {@code v} up tilted
 *       with the climb ({@link #square}).</li>
 *   <li>{@link Mode#MINIMAL_TWIST}: from a start frame, each frame is the one before turned the least way onto the new
 *       tangent (a rotation-minimizing frame, by double reflection), so a cross-section carried along twists no more
 *       than the line makes it.</li>
 * </ul>
 * Where the heading is not defined (straight up or down), the frame keeps the right of the frame before it (or the
 * fallback given, at the start).
 */
public final class PathFrame {
    private static final double EPSILON = 1e-9;
    /** A flattened tangent shorter than this has no heading. */
    private static final double FLAT = 1e-6;

    private PathFrame() {}

    /** How the frames turn along the line. */
    public enum Mode {
        UPRIGHT,
        FOLLOW_SLOPE,
        MINIMAL_TWIST
    }

    /** A frame at a point {@code along} blocks along the line: the tangent, right and up, each unit. */
    public record Frame(double x, double y, double z, double along, double tx, double ty, double tz, double ux, double uy,
                        double uz, double vx, double vy, double vz) {
        /** The world point {@code a} blocks to the right, {@code b} up and {@code c} ahead of the frame's point. */
        public void point(double a, double b, double c, double[] out) {
            out[0] = x + a * ux + b * vx + c * tx;
            out[1] = y + a * uy + b * vy + c * ty;
            out[2] = z + a * uz + b * vz + c * tz;
        }
    }

    /**
     * The square frame of a straight piece heading (tx, ty, tz) (unit): {@code u} horizontal and square to it ({@code x}
     * for a vertical piece), {@code v = u × t}. Returns {@code {ux, uy, uz, vx, vy, vz}}. The Line's square profile.
     */
    public static double[] square(double tx, double ty, double tz) {
        double ux = -tz, uz = tx;
        double uLength = StrictMath.sqrt(ux * ux + uz * uz);
        if (uLength < EPSILON) {
            ux = 1;
            uz = 0;
        } else {
            ux /= uLength;
            uz /= uLength;
        }
        // v = u × t, with uy = 0.
        return new double[] {ux, 0, uz, -uz * ty, uz * tx - ux * tz, ux * ty};
    }

    /**
     * Unit tangents at each sample: central differences (one-sided at an open line's ends; a loop wraps around). A
     * sample whose neighbours coincide takes the tangent before it, or after it at the start; a line of one point
     * heads along +x.
     */
    public static double[][] tangents(List<PathSample> samples, boolean closed) {
        int n = samples.size();
        double[][] out = new double[n][];
        for (int i = 0; i < n; i++) {
            int before = i - 1, after = i + 1;
            if (closed) {
                // A loop's last sample is its first: its neighbours wrap past it.
                before = i == 0 ? n - 2 : before;
                after = i == n - 1 ? 1 : after;
            }
            PathSample a = samples.get(Math.max(0, Math.min(n - 1, before)));
            PathSample b = samples.get(Math.max(0, Math.min(n - 1, after)));
            out[i] = unit(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
        }
        // Fill the undefined ones from a neighbour.
        for (int i = 1; i < n; i++) if (out[i] == null) out[i] = out[i - 1];
        for (int i = n - 2; i >= 0; i--) if (out[i] == null) out[i] = out[i + 1];
        for (int i = 0; i < n; i++) if (out[i] == null) out[i] = new double[] {1, 0, 0};
        return out;
    }

    /**
     * Upright frames: {@code t} the heading, {@code u} its right, {@code v} up. A sample heading straight up or down
     * keeps the heading before it; {@code fallback} (horizontal, unit) is the heading until the line first has one.
     */
    public static List<Frame> upright(List<PathSample> samples, boolean closed, double[] fallback) {
        double[][] tangents = tangents(samples, closed);
        List<Frame> out = new ArrayList<>(samples.size());
        double[] heading = firstHeading(tangents, fallback);
        for (int i = 0; i < samples.size(); i++) {
            double[] flat = unit(tangents[i][0], 0, tangents[i][2], FLAT);
            if (flat != null) heading = flat;
            PathSample s = samples.get(i);
            double hx = heading[0], hz = heading[2];
            out.add(new Frame(s.x(), s.y(), s.z(), s.along(), hx, 0, hz, -hz, 0, hx, 0, 1, 0));
        }
        return out;
    }

    /**
     * Frames that follow the slope: {@code t} the tangent, {@code u} horizontal and square to it, {@code v = u × t}.
     * A sample heading straight up or down keeps the right of the sample before it; {@code fallback} (horizontal, unit)
     * is the heading until the line first has one.
     */
    public static List<Frame> followSlope(List<PathSample> samples, boolean closed, double[] fallback) {
        double[][] tangents = tangents(samples, closed);
        List<Frame> out = new ArrayList<>(samples.size());
        double[] heading = firstHeading(tangents, fallback);
        for (int i = 0; i < samples.size(); i++) {
            double[] t = tangents[i];
            double[] flat = unit(t[0], 0, t[2], FLAT);
            if (flat != null) heading = flat;
            double ux = -heading[2], uz = heading[0];
            PathSample s = samples.get(i);
            out.add(new Frame(s.x(), s.y(), s.z(), s.along(), t[0], t[1], t[2], ux, 0, uz,
                    -uz * t[1], uz * t[0] - ux * t[2], ux * t[1]));
        }
        return out;
    }

    /**
     * Rotation-minimizing frames from a start frame: the cross-section square to {@code startT} with right
     * {@code startU} (unit, square to each other) is first turned the least way onto the tangent at the first sample,
     * then carried along by double reflection.
     */
    public static List<Frame> minimalTwist(List<PathSample> samples, boolean closed, double[] startT, double[] startU) {
        Objects.requireNonNull(startT);
        Objects.requireNonNull(startU);
        double[][] tangents = tangents(samples, closed);
        List<Frame> out = new ArrayList<>(samples.size());
        double[] t = tangents[0];
        double[] u = turnLeastWay(startT, t, startU);
        for (int i = 0; i < samples.size(); i++) {
            PathSample s = samples.get(i);
            if (i > 0) {
                PathSample p = samples.get(i - 1);
                double[] next = tangents[i];
                double v1x = s.x() - p.x(), v1y = s.y() - p.y(), v1z = s.z() - p.z();
                double c1 = v1x * v1x + v1y * v1y + v1z * v1z;
                if (c1 > EPSILON) {
                    // Reflect the frame in the plane square to the step, then in the one mapping the tangent home.
                    double ru = 2 / c1 * (v1x * u[0] + v1y * u[1] + v1z * u[2]);
                    double[] uL = {u[0] - ru * v1x, u[1] - ru * v1y, u[2] - ru * v1z};
                    double rt = 2 / c1 * (v1x * t[0] + v1y * t[1] + v1z * t[2]);
                    double[] tL = {t[0] - rt * v1x, t[1] - rt * v1y, t[2] - rt * v1z};
                    double v2x = next[0] - tL[0], v2y = next[1] - tL[1], v2z = next[2] - tL[2];
                    double c2 = v2x * v2x + v2y * v2y + v2z * v2z;
                    if (c2 > EPSILON) {
                        double r2 = 2 / c2 * (v2x * uL[0] + v2y * uL[1] + v2z * uL[2]);
                        uL = new double[] {uL[0] - r2 * v2x, uL[1] - r2 * v2y, uL[2] - r2 * v2z};
                    }
                    u = uL;
                } else {
                    u = turnLeastWay(t, next, u);
                }
                t = next;
                u = orthonormal(t, u);
            }
            double vx = u[1] * t[2] - u[2] * t[1], vy = u[2] * t[0] - u[0] * t[2], vz = u[0] * t[1] - u[1] * t[0];
            out.add(new Frame(s.x(), s.y(), s.z(), s.along(), t[0], t[1], t[2], u[0], u[1], u[2], vx, vy, vz));
        }
        return out;
    }

    /**
     * {@code vector} turned by the least rotation that takes unit {@code from} onto unit {@code to} (when they are
     * opposite, a half turn about the vertical for a level {@code from}, else about a horizontal axis square to it).
     */
    public static double[] turnLeastWay(double[] from, double[] to, double[] vector) {
        double cos = from[0] * to[0] + from[1] * to[1] + from[2] * to[2];
        double kx = from[1] * to[2] - from[2] * to[1];
        double ky = from[2] * to[0] - from[0] * to[2];
        double kz = from[0] * to[1] - from[1] * to[0];
        double sin = StrictMath.sqrt(kx * kx + ky * ky + kz * kz);
        if (sin < EPSILON) {
            if (cos > 0) return vector.clone();
            // Opposite: a half turn about an axis square to from: straight up for a level one (up stays up), else x.
            double[] axis = StrictMath.abs(from[1]) < 0.5 ? new double[] {0, 1, 0}
                    : orthonormal(from, new double[] {1, 0, 0});
            return rotate(vector, axis, -1, 0);
        }
        return rotate(vector, new double[] {kx / sin, ky / sin, kz / sin}, cos, sin);
    }

    /** {@code vector} turned about unit {@code axis} by the angle of (cos, sin) (Rodrigues). */
    static double[] rotate(double[] v, double[] k, double cos, double sin) {
        double dot = k[0] * v[0] + k[1] * v[1] + k[2] * v[2];
        double cx = k[1] * v[2] - k[2] * v[1], cy = k[2] * v[0] - k[0] * v[2], cz = k[0] * v[1] - k[1] * v[0];
        return new double[] {
                v[0] * cos + cx * sin + k[0] * dot * (1 - cos),
                v[1] * cos + cy * sin + k[1] * dot * (1 - cos),
                v[2] * cos + cz * sin + k[2] * dot * (1 - cos)};
    }

    /** {@code u} made square to unit {@code t} and unit again (any square vector when it lies along {@code t}). */
    static double[] orthonormal(double[] t, double[] u) {
        double d = t[0] * u[0] + t[1] * u[1] + t[2] * u[2];
        double[] out = unit(u[0] - d * t[0], u[1] - d * t[1], u[2] - d * t[2]);
        if (out != null) return out;
        double[] flat = unit(-t[2], 0, t[0]);
        return flat != null ? flat : new double[] {1, 0, 0};
    }

    /** The first heading the tangents have (flattened), else {@code fallback}, else +x. */
    private static double[] firstHeading(double[][] tangents, double[] fallback) {
        for (double[] t : tangents) {
            double[] flat = unit(t[0], 0, t[2], FLAT);
            if (flat != null) return flat;
        }
        if (fallback != null) {
            double[] flat = unit(fallback[0], 0, fallback[2], FLAT);
            if (flat != null) return flat;
        }
        return new double[] {1, 0, 0};
    }

    static double[] unit(double x, double y, double z) {
        return unit(x, y, z, EPSILON);
    }

    private static double[] unit(double x, double y, double z, double shortest) {
        double length = StrictMath.sqrt(x * x + y * y + z * z);
        if (length < shortest) return null;
        return new double[] {x / length, y / length, z / length};
    }
}
