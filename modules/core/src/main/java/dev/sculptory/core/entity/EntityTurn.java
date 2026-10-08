package dev.sculptory.core.entity;

import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How an entity turns with a free rotation ({@code Orientation.rotationMatrix()}: a proper rotation in world axes, +X
 * east, +Y up, +Z south, row-major), after the exact part of the orientation turned it as blocks turn. Pure.
 * <ul>
 *   <li>A heading (armor stands, mobs, item displays' yaw) turns to where the rotation takes its direction, seen from
 *       above ({@link #yaw}); a heading turned straight up or down is kept.</li>
 *   <li>A face (item frames, paintings) goes to the nearest of the six directions ({@link #nearestDirection}); the
 *       server leaves out a painting whose face would point up or down.</li>
 *   <li>A display's own rotation and offset compose with the turn ({@link #display}), so a block or item display turns
 *       exactly, whatever the angle.</li>
 * </ul>
 */
public final class EntityTurn {
    private EntityTurn() {}

    /**
     * A heading (vanilla yaw in degrees: 0 south, 90 west, 180 north, 270 east) turned by {@code rotation}, wrapped into
     * [-180, 180); unchanged when the turned direction is (nearly) vertical.
     */
    public static float yaw(float yaw, double[] rotation) {
        double angle = Math.toRadians(yaw);
        double dx = -Math.sin(angle), dz = Math.cos(angle);
        double x = rotation[0] * dx + rotation[2] * dz;
        double z = rotation[6] * dx + rotation[8] * dz;
        if (Math.hypot(x, z) < 1e-6) return EntityPlacement.wrap(yaw);
        return EntityPlacement.wrap((float) Math.toDegrees(Math.atan2(-x, z)));
    }

    /**
     * The axis direction nearest where {@code rotation} takes direction {@code (dx, dy, dz)} (a unit axis vector), as a
     * new {@code {x, y, z}}; ties go to x, then y.
     */
    public static int[] nearestDirection(double[] rotation, int dx, int dy, int dz) {
        double x = rotation[0] * dx + rotation[1] * dy + rotation[2] * dz;
        double y = rotation[3] * dx + rotation[4] * dy + rotation[5] * dz;
        double z = rotation[6] * dx + rotation[7] * dy + rotation[8] * dz;
        double ax = Math.abs(x), ay = Math.abs(y), az = Math.abs(z);
        if (ax >= ay && ax >= az) return new int[] {x > 0 ? 1 : -1, 0, 0};
        if (ay >= az) return new int[] {0, y > 0 ? 1 : -1, 0};
        return new int[] {0, 0, z > 0 ? 1 : -1};
    }

    /** The unit quaternion {@code (x, y, z, w)} of a proper rotation matrix (row-major). */
    public static double[] quaternion(double[] m) {
        double trace = m[0] + m[4] + m[8];
        double x, y, z, w;
        if (trace > 0) {
            double s = Math.sqrt(trace + 1) * 2;
            w = s / 4;
            x = (m[7] - m[5]) / s;
            y = (m[2] - m[6]) / s;
            z = (m[3] - m[1]) / s;
        } else if (m[0] > m[4] && m[0] > m[8]) {
            double s = Math.sqrt(1 + m[0] - m[4] - m[8]) * 2;
            w = (m[7] - m[5]) / s;
            x = s / 4;
            y = (m[1] + m[3]) / s;
            z = (m[2] + m[6]) / s;
        } else if (m[4] > m[8]) {
            double s = Math.sqrt(1 + m[4] - m[0] - m[8]) * 2;
            w = (m[2] - m[6]) / s;
            x = (m[1] + m[3]) / s;
            y = s / 4;
            z = (m[5] + m[7]) / s;
        } else {
            double s = Math.sqrt(1 + m[8] - m[0] - m[4]) * 2;
            w = (m[3] - m[1]) / s;
            x = (m[2] + m[6]) / s;
            y = (m[5] + m[7]) / s;
            z = s / 4;
        }
        double n = Math.sqrt(x * x + y * y + z * z + w * w);
        return new double[] {x / n, y / n, z / n, w / n};
    }

    /**
     * A display's {@code translation} and {@code left_rotation} (x, y, z, w) once the display turns by {@code rotation}
     * about its own position, given its yaw and pitch (vanilla draws a fixed display turned by
     * {@code rotationYXZ(-yaw, pitch, 0)}, then its transformation): both are turned by {@code E⁻¹ · R · E}, where E is
     * that turn, so the whole display turns by R in the world. Returns {@code {translation, leftRotation}}.
     */
    public static float[][] display(float[] translation, float[] leftRotation, float yaw, float pitch,
                                    double[] rotation) {
        double[] e = times(axis(1, Math.toRadians(-yaw)), axis(0, Math.toRadians(pitch)));
        double[] r = quaternion(rotation);
        double[] local = times(times(conjugate(e), r), e);
        double[] left = times(local, new double[] {leftRotation[0], leftRotation[1], leftRotation[2], leftRotation[3]});
        double[] t = rotate(local, translation[0], translation[1], translation[2]);
        double n = Math.sqrt(left[0] * left[0] + left[1] * left[1] + left[2] * left[2] + left[3] * left[3]);
        return new float[][] {
                {(float) t[0], (float) t[1], (float) t[2]},
                {(float) (left[0] / n), (float) (left[1] / n), (float) (left[2] / n), (float) (left[3] / n)}};
    }

    /** Direction ids as vanilla stores them (0 down, 1 up, 2 north, 3 south, 4 west, 5 east), as vectors. */
    private static final int[][] DIRECTIONS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    /** A painting's horizontal facing ids (0 south, 1 west, 2 north, 3 east) as direction ids. */
    private static final int[] HORIZONTAL_IDS = {3, 4, 2, 5};

    /**
     * An entity's data turned by a free orientation before it is spawned: the faces it is attached by (an item frame's
     * {@code Facing}, a shulker's {@code AttachFace}: any of the six) and a painting's {@code facing} (the four sides
     * only) go to the nearest direction under {@code matrix} (the orientation's whole map, mirror included), and a
     * display's {@code transformation} turns by {@code rotation} ({@link #display}) given the yaw and pitch it will have
     * once the exact part has turned it. {@code null} when a painting would face up or down (it cannot hang there).
     * {@code data} itself when nothing changes; a display whose transformation is stored as a matrix is left as it is.
     */
    public static NbtCompound turnedData(String typeId, NbtCompound data, float yaw, float pitch, double[] matrix,
                                         double[] rotation) {
        NbtCompound.Builder out = data.toBuilder();
        boolean changed = false;
        for (String key : EntityNbt.ATTACHMENT_FACE_KEYS) {
            NbtTag.NbtByte face = data.get(key, NbtTag.NbtByte.class);
            if (face == null || face.value() < 0 || face.value() > 5) continue;
            int[] d = DIRECTIONS[face.value()];
            out.putByte(key, (byte) directionId(nearestDirection(matrix, d[0], d[1], d[2])));
            changed = true;
        }
        if ("minecraft:painting".equals(typeId)) {
            NbtTag.NbtByte facing = data.get("facing", NbtTag.NbtByte.class);
            if (facing != null && facing.value() >= 0 && facing.value() < 4) {
                int[] d = DIRECTIONS[HORIZONTAL_IDS[facing.value()]];
                int id = directionId(nearestDirection(matrix, d[0], d[1], d[2]));
                if (id < 2) return null;
                for (int h = 0; h < 4; h++) {
                    if (HORIZONTAL_IDS[h] == id) out.putByte("facing", (byte) h);
                }
                changed = true;
            }
        }
        if (typeId != null && typeId.startsWith("minecraft:") && typeId.endsWith("_display")) {
            NbtCompound transformation = data.getCompound("transformation");
            float[] translation = {0, 0, 0};
            float[] left = {0, 0, 0, 1};
            NbtTag scale = floats(1, 1, 1);
            NbtTag right = floats(0, 0, 0, 1);
            boolean usable = true;
            if (transformation != null) {
                float[] t = floatsOf(transformation.getList("translation"), 3);
                float[] l = floatsOf(transformation.getList("left_rotation"), 4);
                usable = t != null && l != null && transformation.get("scale") != null
                        && transformation.get("right_rotation") != null;
                if (usable) {
                    translation = t;
                    left = l;
                    scale = transformation.get("scale");
                    right = transformation.get("right_rotation");
                }
            }
            if (usable) {
                float[][] turned = display(translation, left, yaw, pitch, rotation);
                out.put("transformation", NbtCompound.builder()
                        .put("translation", floats(turned[0]))
                        .put("left_rotation", floats(turned[1]))
                        .put("scale", scale)
                        .put("right_rotation", right)
                        .build());
                changed = true;
            }
        }
        return changed ? out.build() : data;
    }

    private static int directionId(int[] v) {
        for (int id = 0; id < DIRECTIONS.length; id++) {
            if (Arrays.equals(DIRECTIONS[id], v)) return id;
        }
        throw new IllegalArgumentException("Not a direction");
    }

    private static float[] floatsOf(NbtList list, int n) {
        if (list == null || list.size() != n || list.elementType() != NbtTag.FLOAT) return null;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) out[i] = ((NbtTag.NbtFloat) list.get(i)).value();
        return out;
    }

    private static NbtList floats(float... values) {
        List<NbtTag.NbtFloat> items = new ArrayList<>(values.length);
        for (float v : values) items.add(new NbtTag.NbtFloat(v));
        return NbtList.of(NbtTag.FLOAT, items);
    }

    /** The quaternion of a right-handed turn by {@code radians} about axis 0 (x), 1 (y) or 2 (z). */
    private static double[] axis(int axis, double radians) {
        double s = Math.sin(radians / 2), c = Math.cos(radians / 2);
        return new double[] {axis == 0 ? s : 0, axis == 1 ? s : 0, axis == 2 ? s : 0, c};
    }

    private static double[] times(double[] a, double[] b) {
        return new double[] {
                a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
                a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
                a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
                a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]};
    }

    private static double[] conjugate(double[] q) {
        return new double[] {-q[0], -q[1], -q[2], q[3]};
    }

    /** Vector (x, y, z) turned by unit quaternion q. */
    private static double[] rotate(double[] q, double x, double y, double z) {
        double[] v = times(times(q, new double[] {x, y, z, 0}), conjugate(q));
        return new double[] {v[0], v[1], v[2]};
    }
}
