package dev.sculptory.core.tinker;

import java.util.Objects;

/**
 * A display's rotation as the Tinker panel shows it: yaw (around y), pitch (around x) and roll (around z), in degrees,
 * applied in that order (JOML's {@code rotationYXZ}); stored as the unit quaternion (x, y, z, w) of the display's
 * {@code left_rotation}. Pure.
 */
public final class DisplayRotation {
    private DisplayRotation() {}

    /** The quaternion (x, y, z, w) of yaw, then pitch, then roll, in degrees. */
    public static float[] fromEuler(double yaw, double pitch, double roll) {
        double hx = Math.toRadians(pitch) / 2, hy = Math.toRadians(yaw) / 2, hz = Math.toRadians(roll) / 2;
        double sx = Math.sin(hx), cx = Math.cos(hx);
        double sy = Math.sin(hy), cy = Math.cos(hy);
        double sz = Math.sin(hz), cz = Math.cos(hz);
        double x = cy * sx, y = sy * cx, z = sy * sx, w = cy * cx;
        return new float[] {(float) (x * cz + y * sz), (float) (y * cz - x * sz), (float) (w * sz - z * cz),
                (float) (w * cz + z * sz)};
    }

    /**
     * Yaw, pitch and roll in degrees (each within ±180; pitch within ±90) of quaternion {@code q} (x, y, z, w), which is
     * normalized first; the identity for a zero quaternion.
     */
    public static float[] toEuler(float[] q) {
        Objects.requireNonNull(q);
        if (q.length != 4) throw new IllegalArgumentException("A quaternion has 4 components");
        double norm = Math.sqrt((double) q[0] * q[0] + (double) q[1] * q[1] + (double) q[2] * q[2] + (double) q[3] * q[3]);
        if (norm < 1e-12 || !Double.isFinite(norm)) return new float[3];
        double x = q[0] / norm, y = q[1] / norm, z = q[2] / norm, w = q[3] / norm;
        double sinPitch = Math.max(-1, Math.min(1, -2 * (y * z - w * x)));
        double pitch = Math.asin(sinPitch);
        double yaw = Math.atan2(x * z + y * w, 0.5 - y * y - x * x);
        double roll = Math.atan2(y * x + w * z, 0.5 - x * x - z * z);
        return new float[] {(float) Math.toDegrees(yaw), (float) Math.toDegrees(pitch), (float) Math.toDegrees(roll)};
    }

    /** {@code q} scaled to length 1 (the identity for a zero quaternion). */
    public static float[] normalized(float[] q) {
        Objects.requireNonNull(q);
        double norm = Math.sqrt((double) q[0] * q[0] + (double) q[1] * q[1] + (double) q[2] * q[2] + (double) q[3] * q[3]);
        if (norm < 1e-12 || !Double.isFinite(norm)) return new float[] {0, 0, 0, 1};
        return new float[] {(float) (q[0] / norm), (float) (q[1] / norm), (float) (q[2] / norm), (float) (q[3] / norm)};
    }
}
