package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.fabric.client.editor.world.Aabb;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Collects overlay lines for one frame and draws them on its own
 * {@code VertexConsumerProvider.Immediate}, so it never flushes or interleaves with the world's
 * buffers. See-through lines are drawn before depth-tested ones.
 *
 * <p>Usage per frame: {@link #begin}, add lines and boxes in world coordinates, {@link #draw}. The
 * batch converts to camera-relative coordinates itself. Owns native buffers: {@link #close} when
 * done. Render thread only.
 */
public final class LineBatch implements AutoCloseable {
    private static final int LAYER_BUFFER_BYTES = 1 << 16;

    private final BufferAllocator seeThroughBuffer = new BufferAllocator(LAYER_BUFFER_BYTES);
    private final BufferAllocator depthTestedBuffer = new BufferAllocator(LAYER_BUFFER_BYTES);
    private final BufferAllocator fallbackBuffer = new BufferAllocator(256);
    private final VertexConsumerProvider.Immediate immediate;
    private final MatrixStack matrices = new MatrixStack();
    private VertexConsumer seeThrough;
    private VertexConsumer depthTested;
    private double cameraX;
    private double cameraY;
    private double cameraZ;

    public LineBatch() {
        SequencedMap<RenderLayer, BufferAllocator> layers = new LinkedHashMap<>();
        layers.put(OverlayLayers.LINES_SEE_THROUGH, seeThroughBuffer);
        layers.put(OverlayLayers.LINES, depthTestedBuffer);
        immediate = VertexConsumerProvider.immediate(layers, fallbackBuffer);
    }

    /** Starts a frame: later coordinates are made relative to this camera position. */
    public void begin(double cameraX, double cameraY, double cameraZ) {
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
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

    /**
     * A line between two world points, its alpha scaled by the Tool outlines opacity ({@link OverlayOpacity}).
     * Zero-length lines are skipped.
     */
    public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb, boolean seeThrough) {
        emitLine(consumer(seeThrough), matrices.peek(), (float) (x1 - cameraX), (float) (y1 - cameraY),
                (float) (z1 - cameraZ), (float) (x2 - cameraX), (float) (y2 - cameraY), (float) (z2 - cameraZ), argb);
    }

    /** Writes one line between two camera-relative points, its alpha scaled by {@link OverlayOpacity}. */
    static void emitLine(VertexConsumer consumer, MatrixStack.Entry entry, float ax, float ay, float az, float bx,
            float by, float bz, int argb) {
        float nx = bx - ax;
        float ny = by - ay;
        float nz = bz - az;
        float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (!(length > 1e-6f)) {
            return;
        }
        nx /= length;
        ny /= length;
        nz /= length;
        int color = OverlayOpacity.apply(argb);
        // The lines shader expands each segment in screen space along its normal (= direction).
        consumer.vertex(entry, ax, ay, az).color(color).normal(entry, nx, ny, nz);
        consumer.vertex(entry, bx, by, bz).color(color).normal(entry, nx, ny, nz);
    }

    /**
     * The 12 edges of a world box, grown by {@code inflate}, via {@code WorldRenderer.drawBox}; alpha scaled by the
     * Tool outlines opacity ({@link OverlayOpacity}).
     */
    public void box(Aabb box, double inflate, int argb, boolean seeThrough) {
        CameraRelative.Bounds b = CameraRelative.box(
                box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ(),
                cameraX, cameraY, cameraZ, inflate);
        int color = OverlayOpacity.apply(argb);
        WorldRenderer.drawBox(
                matrices, consumer(seeThrough),
                b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(),
                OverlayColors.red(color), OverlayColors.green(color), OverlayColors.blue(color),
                OverlayColors.alphaFloat(color));
    }

    /** Draws everything collected since {@link #begin} and resets the batch. */
    public void draw() {
        immediate.draw();
        seeThrough = null;
        depthTested = null;
    }

    private VertexConsumer consumer(boolean wantSeeThrough) {
        if (wantSeeThrough) {
            if (seeThrough == null) {
                seeThrough = immediate.getBuffer(OverlayLayers.LINES_SEE_THROUGH);
            }
            return seeThrough;
        }
        if (depthTested == null) {
            depthTested = immediate.getBuffer(OverlayLayers.LINES);
        }
        return depthTested;
    }

    @Override
    public void close() {
        seeThroughBuffer.close();
        depthTestedBuffer.close();
        fallbackBuffer.close();
    }
}
