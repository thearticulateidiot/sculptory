package dev.sculptory.fabric.client.editor.world;

import java.util.Optional;
import org.joml.Vector3f;

/**
 * Turns a cursor position into a world-space ray using the previous frame's {@link CameraSnapshot},
 * so tools aim where the free mouse cursor points rather than along the crosshair.
 *
 * <p>All entry points take positions with a top-left origin and Y down, as Minecraft and GLFW report
 * them. They differ only in the pixel space:
 *
 * <ul>
 *   <li>{@link #fromScaled}: GUI-scaled coordinates, as a {@code Screen} receives them;
 *   <li>{@link #fromWindow}: window coordinates, as {@code Mouse.getX()/getY()} report them;
 *   <li>{@link #fromFramebuffer}: framebuffer pixels (differs from window size on HiDPI displays).
 * </ul>
 *
 * Each is normalized by its own screen size first, so GUI scale and the framebuffer/window ratio
 * cancel out. The GL window origin is bottom-left, so Y is flipped before unprojecting with JOML's
 * {@code Matrix4f.unprojectRay}.
 *
 * <p>The ray starts on the near plane. Every method returns empty when the snapshot is degenerate or
 * the input is not finite.
 */
public final class CursorRay {
    private CursorRay() {}

    /** Ray through GUI-scaled coordinates in a screen of {@code scaledWidth x scaledHeight}. */
    public static Optional<Ray> fromScaled(
            CameraSnapshot camera, double scaledX, double scaledY, double scaledWidth, double scaledHeight) {
        if (!(scaledWidth > 0) || !(scaledHeight > 0)) {
            return Optional.empty();
        }
        return fromNormalized(camera, scaledX / scaledWidth, scaledY / scaledHeight);
    }

    /** Ray through window coordinates (GLFW cursor position) in a window of the given size. */
    public static Optional<Ray> fromWindow(
            CameraSnapshot camera, double windowX, double windowY, double windowWidth, double windowHeight) {
        return fromScaled(camera, windowX, windowY, windowWidth, windowHeight);
    }

    /** Ray through framebuffer pixel coordinates (top-left origin). */
    public static Optional<Ray> fromFramebuffer(CameraSnapshot camera, double pixelX, double pixelY) {
        return fromScaled(camera, pixelX, pixelY, camera.viewportWidth(), camera.viewportHeight());
    }

    /** Crosshair fallback: the ray through the centre of the screen. */
    public static Optional<Ray> center(CameraSnapshot camera) {
        return fromNormalized(camera, 0.5, 0.5);
    }

    /**
     * Ray through normalized screen coordinates: {@code u} and {@code v} are 0..1 across the screen,
     * top-left origin, Y down. Values outside 0..1 (cursor outside the window) are allowed.
     */
    public static Optional<Ray> fromNormalized(CameraSnapshot camera, double u, double v) {
        if (camera.isDegenerate() || !Double.isFinite(u) || !Double.isFinite(v)) {
            return Optional.empty();
        }
        int width = camera.viewportWidth();
        int height = camera.viewportHeight();
        float winX = (float) (u * width);
        float winY = (float) ((1.0 - v) * height); // GL window coordinates grow upwards
        int[] viewport = {0, 0, width, height};
        Vector3f origin = new Vector3f();
        Vector3f direction = new Vector3f();
        camera.viewProjection().unprojectRay(winX, winY, viewport, origin, direction);
        if (!origin.isFinite() || !direction.isFinite() || direction.lengthSquared() < 1e-20f) {
            return Optional.empty();
        }
        return Optional.of(new Ray(
                camera.cameraX() + origin.x,
                camera.cameraY() + origin.y,
                camera.cameraZ() + origin.z,
                direction.x,
                direction.y,
                direction.z));
    }
}
