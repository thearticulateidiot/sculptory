package dev.sculptory.mc.client.editor.render;

import dev.sculptory.mc.client.editor.world.Aabb;
import dev.sculptory.mc.client.editor.world.BoxFace;
import dev.sculptory.mc.client.editor.world.BoxHandles;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a selection box: a faint face fill, a see-through pass of the edges (so the box reads
 * through terrain), the depth-tested edges via {@code WorldRenderer.drawBox}, and the six face
 * handles from {@link BoxHandles} with the hovered one tinted; a handle behind blocks shows faintly through them
 * ({@link HandlePaint}). The bounds of a shape or cell set draw dimmer, without
 * the fill (the exact outline is drawn by {@link CellMeshBuffers}), and a cell set without handles.
 */
public final class SelectionBoxRenderer {
    private SelectionBoxRenderer() {}

    /**
     * Colours (ARGB) for a selection box.
     *
     * @param edge depth-tested edges
     * @param seeThroughEdge edges seen through terrain (about 25% alpha)
     * @param fill faint face fill (none when fully transparent)
     * @param handle face handles
     * @param handleHovered the handle under the cursor
     */
    public record Style(int edge, int seeThroughEdge, int fill, int handle, int handleHovered) {
        public static final Style DEFAULT = new Style(0xFFFFFFFF, 0x40FFFFFF, 0x18FFFFFF, 0xCC3FA0FF, 0xFFFFD23F);
        /** The bounds around a shape or cell set, whose own outline is drawn exactly. */
        public static final Style BOUNDS = new Style(0x80FFFFFF, 0x20FFFFFF, 0x00FFFFFF, 0xCC3FA0FF, 0xFFFFD23F);
    }

    /** Colours (ARGB) of the exact outline of a shape or cell set: the faces' tint, the edges, the edges seen through terrain. */
    public static final int CELL_FILL = 0x2AFFFFFF;
    public static final int CELL_EDGE = 0xFFFFFFFF;
    public static final int CELL_EDGE_SEE_THROUGH = 0x40FFFFFF;

    /**
     * The colours (ARGB) one face handle is drawn in: its {@code fill} and lighter opaque {@code edge} where it is in
     * view, and a faint {@code hiddenFill} and {@code hiddenEdge} drawn without the depth test, so a handle behind
     * blocks still shows where it is (and can be grabbed there: {@link BoxHandles#pick} ignores the world).
     */
    public record HandlePaint(int fill, int edge, int hiddenFill, int hiddenEdge) {
        /** How much of the fill's alpha the hidden fill keeps. */
        public static final double HIDDEN_FILL_SHARE = 0.3;
        /** The hidden outline's alpha (0..255): readable through a wall, fainter than the box's own edges. */
        public static final int HIDDEN_EDGE_ALPHA = 0x80;

        public static HandlePaint of(int color) {
            int edge = OverlayColors.withAlpha(OverlayColors.lighten(color, 0.4), 0xFF);
            return new HandlePaint(color, edge, OverlayColors.scaleAlpha(color, HIDDEN_FILL_SHARE),
                    OverlayColors.withAlpha(edge, HIDDEN_EDGE_ALPHA));
        }
    }

    public static void render(LineBatch lines, QuadBatch quads, Aabb box, @Nullable BoxFace hovered, Style style) {
        render(lines, quads, box, hovered, style, true);
    }

    public static void render(LineBatch lines, QuadBatch quads, Aabb box, @Nullable BoxFace hovered, Style style,
            boolean handles) {
        double inflate = CameraRelative.INFLATE;
        if (OverlayColors.alpha(style.fill()) > 0) {
            quads.boxFaces(box, inflate, style.fill(), false);
        }
        lines.box(box, inflate, style.seeThroughEdge(), true);
        lines.box(box, inflate, style.edge(), false);
        if (!handles) {
            return;
        }
        double[] halfSizes = BoxHandles.handleHalfSizes(box, lines.cameraX(), lines.cameraY(), lines.cameraZ());
        for (BoxFace face : BoxFace.values()) {
            Aabb handle = BoxHandles.handleBox(box, face, halfSizes[face.ordinal()]);
            HandlePaint paint = HandlePaint.of(face == hovered ? style.handleHovered() : style.handle());
            // The see-through pass first: where a block hides the handle, a faint fill and outline still show it.
            quads.boxFaces(handle, inflate, paint.hiddenFill(), true);
            lines.box(handle, inflate, paint.hiddenEdge(), true);
            quads.boxFaces(handle, inflate, paint.fill(), false);
            lines.box(handle, inflate, paint.edge(), false);
        }
    }
}
