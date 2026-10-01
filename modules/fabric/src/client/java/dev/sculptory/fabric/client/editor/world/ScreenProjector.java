package dev.sculptory.fabric.client.editor.world;

/**
 * Maps a world point to screen coordinates (top-left origin, Y down) in whatever pixel space the
 * caller measures its cursor in. {@link CameraSnapshot#projector} supplies the real one; tests can
 * supply a simple fake.
 */
@FunctionalInterface
public interface ScreenProjector {
    /**
     * Writes {@code out[0] = x} and {@code out[1] = y} in pixels.
     *
     * @return false when the point is behind the camera (or the projection is unusable); {@code out}
     *     is then unspecified
     */
    boolean project(double x, double y, double z, double[] out);
}
