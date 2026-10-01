package dev.sculptory.fabric.client.editor.render;

import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Objects;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.VertexBuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.BufferAllocator;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

/**
 * A {@link CellMesh} in vertex buffers: the faces as translucent quads and the outline edges as lines, both relative
 * to one origin cell, so moving the selection only moves the model matrix. {@link #build} writes the vertices (any
 * thread), {@link #upload} hands them to GL and {@link #draw} draws them (render thread).
 *
 * <p>Faces draw depth-tested in the overlay quad layer (pushed {@link CameraRelative#INFLATE} outwards and offset
 * towards the camera, so they don't fight the blocks' own faces). Edges draw twice from one buffer: faintly through
 * terrain, then depth-tested, pulled towards the camera as vanilla's outlines are.
 */
public final class CellMeshBuffers implements AutoCloseable {
    /** Vanilla's {@code VIEW_OFFSET_Z_LAYERING} scale, applied to the edge passes' model-view matrix by hand. */
    private static final float VIEW_OFFSET_SCALE = 0.99975586f;

    /** Vertices written off the render thread, not yet uploaded. Owns native memory until uploaded or closed. */
    public static final class Built implements AutoCloseable {
        private final int originX;
        private final int originY;
        private final int originZ;
        private @Nullable BufferAllocator fillMemory;
        private @Nullable BuiltBuffer fill;
        private @Nullable BufferAllocator edgeMemory;
        private @Nullable BuiltBuffer edges;

        private Built(int originX, int originY, int originZ) {
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
        }

        @Override
        public void close() {
            if (fill != null) fill.close();
            if (fillMemory != null) fillMemory.close();
            if (edges != null) edges.close();
            if (edgeMemory != null) edgeMemory.close();
            fill = null;
            fillMemory = null;
            edges = null;
            edgeMemory = null;
        }
    }

    private @Nullable VertexBuffer fill;
    private @Nullable VertexBuffer edges;
    private int originX;
    private int originY;
    private int originZ;
    private long bytes;

    /**
     * Writes the mesh's vertices relative to the cell {@code (originX, originY, originZ)}. Faces get {@code fillArgb};
     * edges are white, coloured when drawn. Any thread.
     */
    public static Built build(CellMesh mesh, int originX, int originY, int originZ, int fillArgb) {
        Objects.requireNonNull(mesh);
        Built built = new Built(originX, originY, originZ);
        try {
            if (mesh.quadCount() > 0 && !mesh.tooDetailed()) {
                built.fillMemory = new BufferAllocator((int) Math.min(1 << 26, Math.max(256, mesh.quadCount() * CellMesh.QUAD_BYTES)));
                BufferBuilder builder = new BufferBuilder(built.fillMemory, VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
                double[] corners = new double[12];
                for (CellMesh.Section section : mesh.sections()) {
                    float baseX = (section.sectionX() << 4) - originX;
                    float baseY = (section.sectionY() << 4) - originY;
                    float baseZ = (section.sectionZ() << 4) - originZ;
                    for (int quad : section.quads()) {
                        CellMesh.quadCorners(quad, CameraRelative.INFLATE, corners);
                        for (int i = 0; i < 4; i++) {
                            builder.vertex(baseX + (float) corners[i * 3], baseY + (float) corners[i * 3 + 1],
                                    baseZ + (float) corners[i * 3 + 2]).color(fillArgb);
                        }
                    }
                }
                built.fill = builder.endNullable();
            }
            if (mesh.edgeCount() > 0) {
                built.edgeMemory = new BufferAllocator((int) Math.min(1 << 26, Math.max(256, mesh.edgeCount() * CellMesh.EDGE_BYTES)));
                BufferBuilder builder = new BufferBuilder(built.edgeMemory, VertexFormat.DrawMode.LINES, VertexFormats.LINES);
                double[] ends = new double[6];
                for (CellMesh.Section section : mesh.sections()) {
                    float baseX = (section.sectionX() << 4) - originX;
                    float baseY = (section.sectionY() << 4) - originY;
                    float baseZ = (section.sectionZ() << 4) - originZ;
                    for (int edge : section.edges()) {
                        CellMesh.edgeEnds(edge, ends);
                        int axis = CellMesh.edgeAxis(edge);
                        float nx = axis == 0 ? 1 : 0;
                        float ny = axis == 1 ? 1 : 0;
                        float nz = axis == 2 ? 1 : 0;
                        builder.vertex(baseX + (float) ends[0], baseY + (float) ends[1], baseZ + (float) ends[2])
                                .color(0xFFFFFFFF).normal(nx, ny, nz);
                        builder.vertex(baseX + (float) ends[3], baseY + (float) ends[4], baseZ + (float) ends[5])
                                .color(0xFFFFFFFF).normal(nx, ny, nz);
                    }
                }
                built.edges = builder.endNullable();
            }
            return built;
        } catch (RuntimeException | Error failure) {
            built.close();
            throw failure;
        }
    }

    /** Replaces what is drawn with {@code built} (consumed). Render thread. */
    public void upload(Built built) {
        Objects.requireNonNull(built);
        RenderSystem.assertOnRenderThread();
        clear();
        try {
            originX = built.originX;
            originY = built.originY;
            originZ = built.originZ;
            if (built.fill != null) {
                bytes += built.fill.getDrawParameters().vertexCount() * (long) VertexFormats.POSITION_COLOR.getVertexSizeByte();
                fill = uploaded(built.fill);
                built.fill = null; // consumed by the upload
            }
            if (built.edges != null) {
                bytes += built.edges.getDrawParameters().vertexCount() * (long) VertexFormats.LINES.getVertexSizeByte();
                edges = uploaded(built.edges);
                built.edges = null;
            }
        } finally {
            built.close();
        }
    }

    private static VertexBuffer uploaded(BuiltBuffer vertices) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        buffer.bind();
        buffer.upload(vertices); // consumes (closes) the built buffer
        VertexBuffer.unbind();
        return buffer;
    }

    /** Whether anything is uploaded. */
    public boolean ready() {
        return fill != null || edges != null;
    }

    /** Vertex memory in use, in bytes. */
    public long bytes() {
        return bytes;
    }

    /**
     * Draws the mesh moved by {@code (dx, dy, dz)} blocks from where it was built. {@code view} and {@code projection}
     * are the frame's matrices; the camera is at {@code (cameraX, cameraY, cameraZ)}. Render thread.
     */
    public void draw(Matrix4f view, Matrix4f projection, double cameraX, double cameraY, double cameraZ, int dx, int dy,
            int dz, int edgeArgb, int seeThroughEdgeArgb) {
        if (!ready()) return;
        float tx = (float) (originX + (long) dx - cameraX);
        float ty = (float) (originY + (long) dy - cameraY);
        float tz = (float) (originZ + (long) dz - cameraZ);
        Matrix4f model = new Matrix4f(view).translate(tx, ty, tz);
        if (fill != null) {
            pass(OverlayLayers.QUADS, fill, model, projection, 0xFFFFFFFF);
        }
        if (edges != null) {
            Matrix4f pulled = new Matrix4f().scale(VIEW_OFFSET_SCALE).mul(model);
            pass(OverlayLayers.LINES_SEE_THROUGH, edges, pulled, projection, seeThroughEdgeArgb);
            pass(OverlayLayers.LINES, edges, pulled, projection, edgeArgb);
        }
    }

    /** One draw of a buffer, tinted {@code argb} at the Tool outlines opacity ({@link OverlayOpacity}). */
    private static void pass(RenderLayer layer, VertexBuffer buffer, Matrix4f modelView, Matrix4f projection, int argb) {
        int color = OverlayOpacity.apply(argb);
        layer.startDrawing();
        try {
            ShaderProgram shader = RenderSystem.getShader();
            if (shader == null) return;
            RenderSystem.setShaderColor(OverlayColors.red(color), OverlayColors.green(color), OverlayColors.blue(color),
                    OverlayColors.alphaFloat(color));
            buffer.bind();
            buffer.draw(modelView, projection, shader);
            VertexBuffer.unbind();
        } finally {
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            layer.endDrawing();
        }
    }

    /** Frees the buffers. Render thread. */
    public void clear() {
        if (fill != null) fill.close();
        if (edges != null) edges.close();
        fill = null;
        edges = null;
        bytes = 0;
    }

    @Override
    public void close() {
        clear();
    }
}
