package dev.sculptory.fabric.client.editor.render;

/**
 * Camera-relative coordinates for overlay geometry. World coordinates are subtracted from the camera
 * position in double precision before the conversion to float, so outlines stay stable far from the
 * origin (Minecraft's vertex formats are float).
 *
 * <p>Block outlines are inflated by {@link #INFLATE} so they don't z-fight the block faces.
 */
public final class CameraRelative {
    public static final double INFLATE = 0.002;

    private CameraRelative() {}

    /** A box in camera-relative float coordinates. */
    public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}

    /** One world coordinate relative to the camera coordinate on the same axis. */
    public static float relative(double world, double camera) {
        return (float) (world - camera);
    }

    /** The outline box of one block, inflated by {@link #INFLATE}. */
    public static Bounds block(int x, int y, int z, double cameraX, double cameraY, double cameraZ) {
        return box(x, y, z, x + 1.0, y + 1.0, z + 1.0, cameraX, cameraY, cameraZ, INFLATE);
    }

    /** A world box relative to the camera, grown by {@code inflate} on every side. */
    public static Bounds box(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            double cameraX,
            double cameraY,
            double cameraZ,
            double inflate) {
        return new Bounds(
                (float) (minX - cameraX - inflate),
                (float) (minY - cameraY - inflate),
                (float) (minZ - cameraZ - inflate),
                (float) (maxX - cameraX + inflate),
                (float) (maxY - cameraY + inflate),
                (float) (maxZ - cameraZ + inflate));
    }
}
