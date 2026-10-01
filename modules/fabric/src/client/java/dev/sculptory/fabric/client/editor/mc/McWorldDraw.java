package dev.sculptory.fabric.client.editor.mc;

import dev.sculptory.core.Box;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.client.editor.render.CameraRelative;
import dev.sculptory.fabric.client.editor.render.LineBatch;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.render.QuadBatch;
import dev.sculptory.fabric.client.editor.render.RegionOutlines;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.select.SelectionModel;
import java.util.List;
import org.joml.Matrix4f;

/**
 * {@link WorldDraw} for tools, on its own line and quad batches (so tool drawing never mixes with
 * the overlay renderer's buffers), and shape outlines in vertex buffers ({@link RegionOutlines}). Per frame:
 * {@link #begin}, the tool draws, {@link #end}. Render thread only; {@link #close} frees the native buffers.
 */
public final class McWorldDraw implements WorldDraw, AutoCloseable {
    private static final int RING_SEGMENTS = 48;
    /** The faces of a tool's shape outlines: a faint white. */
    private static final int OUTLINE_FILL = 0x16FFFFFF;

    private LineBatch lines;
    private QuadBatch quads;
    private RegionOutlines outlines;
    private boolean seeThrough;
    private Matrix4f view;
    private Matrix4f projection;
    private double cameraX;
    private double cameraY;
    private double cameraZ;
    /** The shape outlines asked for this frame, drawn at {@link #end}. */
    private List<Region> outlineRegions = List.of();
    private int outlineArgb;
    private int outlineCopyArgb;

    /** Starts a frame with its camera position and matrices. */
    public void begin(double cameraX, double cameraY, double cameraZ, Matrix4f view, Matrix4f projection) {
        if (lines == null) {
            lines = new LineBatch();
            quads = new QuadBatch();
        }
        lines.begin(cameraX, cameraY, cameraZ);
        quads.begin(cameraX, cameraY, cameraZ);
        seeThrough = false;
        this.view = view;
        this.projection = projection;
        this.cameraX = cameraX;
        this.cameraY = cameraY;
        this.cameraZ = cameraZ;
        outlineRegions = List.of();
    }

    public void end() {
        if (lines != null) {
            quads.draw();
            lines.draw();
        }
        if (outlines != null || !outlineRegions.isEmpty()) {
            if (outlines == null) outlines = new RegionOutlines(OUTLINE_FILL);
            int n = RegionOutlines.SLOTS;
            int[] edges = new int[n];
            int[] through = new int[n];
            for (int i = 0; i < n; i++) {
                edges[i] = i == 0 ? outlineArgb : outlineCopyArgb;
                through[i] = OverlayColors.scaleAlpha(edges[i], 0.35);
            }
            outlines.draw(outlineRegions, edges, through, view, projection, cameraX, cameraY, cameraZ);
        }
    }

    @Override
    public void shapeOutlines(List<? extends Region> regions, int argb, int copyArgb) {
        outlineRegions = List.copyOf(regions.subList(0, Math.min(RegionOutlines.SLOTS, regions.size())));
        outlineArgb = argb;
        outlineCopyArgb = copyArgb;
    }

    @Override
    public void boxOutline(Box box, int argb) {
        lines.box(SelectionModel.toAabb(box), CameraRelative.INFLATE, argb, seeThrough);
    }

    @Override
    public void boxFill(Box box, int argb) {
        quads.boxFaces(SelectionModel.toAabb(box), CameraRelative.INFLATE, argb, seeThrough);
    }

    @Override
    public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
        lines.line(x1, y1, z1, x2, y2, z2, argb, seeThrough);
    }

    @Override
    public void ring(double centerX, double y, double centerZ, double radius, int argb) {
        double previousX = centerX + radius;
        double previousZ = centerZ;
        for (int i = 1; i <= RING_SEGMENTS; i++) {
            double angle = Math.PI * 2 * i / RING_SEGMENTS;
            double x = centerX + Math.cos(angle) * radius;
            double z = centerZ + Math.sin(angle) * radius;
            lines.line(previousX, y, previousZ, x, y, z, argb, seeThrough);
            previousX = x;
            previousZ = z;
        }
    }

    @Override
    public void seeThrough(boolean enabled) {
        seeThrough = enabled;
    }

    @Override
    public void surfaceQuad(double minX, double minZ, double maxX, double maxZ, double y, int argb) {
        quads.horizontal(minX, minZ, maxX, maxZ, y, argb, seeThrough);
    }

    /** Frees the shape outlines' meshes and vertex buffers (the editor closed); they are made again when next asked. */
    public void releaseOutlines() {
        outlineRegions = List.of();
        if (outlines != null) {
            outlines.close();
            outlines = null;
        }
    }

    @Override
    public void close() {
        releaseOutlines();
        if (lines != null) {
            lines.close();
            quads.close();
            lines = null;
            quads = null;
        }
    }
}
