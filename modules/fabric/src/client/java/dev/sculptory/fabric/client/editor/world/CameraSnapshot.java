package dev.sculptory.fabric.client.editor.world;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;

/**
 * The camera of one rendered frame: projection and view ("position") matrices, camera position and
 * framebuffer size. Captured in {@code WorldRenderEvents.LAST} (see {@link #capture}) and used until
 * the next frame to turn cursor positions into rays and world points into screen positions.
 *
 * <p>In 1.21.1 the view matrix is the camera rotation only; world geometry is drawn relative to the
 * camera position, so {@link #viewProjection()} maps camera-relative points to clip space.
 *
 * <p>Immutable; matrices are defensive copies. The constructor is Minecraft-free for tests.
 */
public final class CameraSnapshot {
    private static final float MIN_ABS_DETERMINANT = 1e-12f;

    private final Matrix4f projection;
    private final Matrix4f view;
    private final Matrix4f viewProjection;
    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;
    private final int viewportWidth;
    private final int viewportHeight;
    private final long frame;
    private final boolean degenerate;

    /**
     * @param projection the frame's projection matrix
     * @param view the frame's view matrix (camera rotation, no translation)
     * @param viewportWidth framebuffer width in pixels
     * @param viewportHeight framebuffer height in pixels
     * @param frame a counter that changes every frame; per-frame caches key on it
     */
    public CameraSnapshot(
            Matrix4fc projection,
            Matrix4fc view,
            double cameraX,
            double cameraY,
            double cameraZ,
            int viewportWidth,
            int viewportHeight,
            long frame) {
        this.projection = new Matrix4f(projection);
        this.view = new Matrix4f(view);
        this.viewProjection = new Matrix4f(projection).mul(view);
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
        this.frame = frame;
        this.degenerate = computeDegenerate();
    }

    /**
     * Captures the current frame. Call from {@code WorldRenderEvents.LAST} on the render thread.
     *
     * @param frame a counter the caller increments once per frame
     */
    public static CameraSnapshot capture(WorldRenderContext context, long frame) {
        Vec3d camera = context.camera().getPos();
        Window window = MinecraftClient.getInstance().getWindow();
        return new CameraSnapshot(
                context.projectionMatrix(),
                context.positionMatrix(),
                camera.x,
                camera.y,
                camera.z,
                window.getFramebufferWidth(),
                window.getFramebufferHeight(),
                frame);
    }

    private boolean computeDegenerate() {
        if (viewportWidth <= 0 || viewportHeight <= 0) {
            return true;
        }
        if (!Double.isFinite(cameraX) || !Double.isFinite(cameraY) || !Double.isFinite(cameraZ)) {
            return true;
        }
        if (!projection.isFinite() || !view.isFinite() || !viewProjection.isFinite()) {
            return true;
        }
        float determinant = viewProjection.determinant();
        return !Float.isFinite(determinant) || Math.abs(determinant) < MIN_ABS_DETERMINANT;
    }

    public Matrix4fc projection() {
        return projection;
    }

    public Matrix4fc view() {
        return view;
    }

    /** {@code projection * view}: camera-relative world point to clip space. */
    public Matrix4fc viewProjection() {
        return viewProjection;
    }

    public double cameraX() {
        return cameraX;
    }

    public double cameraY() {
        return cameraY;
    }

    public double cameraZ() {
        return cameraZ;
    }

    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    public long frame() {
        return frame;
    }

    /** True when the matrices or viewport cannot be used for unprojection (zero size, singular, NaN). */
    public boolean isDegenerate() {
        return degenerate;
    }

    public double distanceTo(double x, double y, double z) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Projects a world point to normalized screen coordinates: {@code out[0]} and {@code out[1]} in
     * 0..1 across the visible screen, top-left origin, Y down.
     *
     * @return false when the point is behind the camera or the snapshot is degenerate
     */
    public boolean projectNormalized(double x, double y, double z, double[] out) {
        if (degenerate) {
            return false;
        }
        Vector4f clip = new Vector4f((float) (x - cameraX), (float) (y - cameraY), (float) (z - cameraZ), 1f);
        viewProjection.transform(clip);
        if (!(clip.w > 1e-6f)) {
            return false;
        }
        double ndcX = clip.x / clip.w;
        double ndcY = clip.y / clip.w;
        out[0] = (ndcX + 1.0) * 0.5;
        out[1] = (1.0 - ndcY) * 0.5;
        return Double.isFinite(out[0]) && Double.isFinite(out[1]);
    }

    /**
     * A projector into a screen of the given size, e.g. the GUI-scaled size for cursor picking in
     * scaled pixels, or the framebuffer size for real pixels.
     */
    public ScreenProjector projector(double screenWidth, double screenHeight) {
        return (x, y, z, out) -> {
            if (!projectNormalized(x, y, z, out)) {
                return false;
            }
            out[0] *= screenWidth;
            out[1] *= screenHeight;
            return true;
        };
    }
}
