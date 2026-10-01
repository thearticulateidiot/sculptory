package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.fabric.client.editor.world.Aabb;
import java.util.LinkedHashMap;
import java.util.SequencedMap;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

/**
 * Collects translucent overlay quads (fills and tints) for one frame and draws them on its own
 * {@code VertexConsumerProvider.Immediate}. See-through quads are drawn before depth-tested ones;
 * both layers are sorted back to front and drawn double-sided.
 *
 * <p>Usage per frame: {@link #begin}, add quads in world coordinates, {@link #draw}. Owns native
 * buffers: {@link #close} when done. Render thread only.
 */
public final class QuadBatch implements AutoCloseable {
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

    public QuadBatch() {
        SequencedMap<RenderLayer, BufferAllocator> layers = new LinkedHashMap<>();
        layers.put(OverlayLayers.QUADS_SEE_THROUGH, seeThroughBuffer);
        layers.put(OverlayLayers.QUADS, depthTestedBuffer);
        immediate = VertexConsumerProvider.immediate(layers, fallbackBuffer);
    }

    /** Starts a frame: later coordinates are made relative to this camera position. */
    public void begin(double cameraX, double cameraY, double cameraZ) {
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
    }

    /**
     * One quad through four world points, in order around its edge; its alpha scaled by the Tool outlines opacity
     * ({@link OverlayOpacity}).
     */
    public void quad(
            double x1, double y1, double z1,
            double x2, double y2, double z2,
            double x3, double y3, double z3,
            double x4, double y4, double z4,
            int argb,
            boolean seeThrough) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumer consumer = consumer(seeThrough);
        int color = OverlayOpacity.apply(argb);
        vertex(consumer, matrix, x1, y1, z1, color);
        vertex(consumer, matrix, x2, y2, z2, color);
        vertex(consumer, matrix, x3, y3, z3, color);
        vertex(consumer, matrix, x4, y4, z4, color);
    }

    private void vertex(VertexConsumer consumer, Matrix4f matrix, double x, double y, double z, int argb) {
        consumer.vertex(matrix, (float) (x - cameraX), (float) (y - cameraY), (float) (z - cameraZ)).color(argb);
    }

    /** A horizontal rectangle at height {@code y}, e.g. a column's top-face tint. */
    public void horizontal(double minX, double minZ, double maxX, double maxZ, double y, int argb, boolean seeThrough) {
        quad(minX, y, minZ, minX, y, maxZ, maxX, y, maxZ, maxX, y, minZ, argb, seeThrough);
    }

    /** The six faces of a world box, grown by {@code inflate}. */
    public void boxFaces(Aabb box, double inflate, int argb, boolean seeThrough) {
        double x0 = box.minX() - inflate;
        double y0 = box.minY() - inflate;
        double z0 = box.minZ() - inflate;
        double x1 = box.maxX() + inflate;
        double y1 = box.maxY() + inflate;
        double z1 = box.maxZ() + inflate;
        quad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, argb, seeThrough); // down
        quad(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, argb, seeThrough); // up
        quad(x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, argb, seeThrough); // north
        quad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, argb, seeThrough); // south
        quad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, argb, seeThrough); // west
        quad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, argb, seeThrough); // east
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
                seeThrough = immediate.getBuffer(OverlayLayers.QUADS_SEE_THROUGH);
            }
            return seeThrough;
        }
        if (depthTested == null) {
            depthTested = immediate.getBuffer(OverlayLayers.QUADS);
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
