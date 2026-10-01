package dev.sculptory.fabric.client.editor.tool;

import dev.sculptory.core.Box;
import dev.sculptory.core.region.Region;
import java.util.List;

/** World-space overlay drawing for tools, in block coordinates. Colours are ARGB. Implemented by the renderer. */
public interface WorldDraw {
    /** Box edges around the cells of {@code box}. */
    void boxOutline(Box box, int argb);

    /** Translucent faces over the cells of {@code box}. */
    void boxFill(Box box, int argb);

    void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb);

    /** A flat horizontal ring at height {@code y}; terrain-following cursors draw several. */
    void ring(double centerX, double y, double centerZ, double radius, int argb);

    /** Whether following draws show through terrain. */
    void seeThrough(boolean enabled);

    /** A translucent horizontal rectangle at height {@code y}, e.g. a tint over a column's top face. */
    default void surfaceQuad(double minX, double minZ, double maxX, double maxZ, double y, int argb) {}

    /**
     * The exact outlines of up to four shape regions, a tool's cursor (a shape and its copies): the first's edges in
     * {@code argb}, the others' in {@code copyArgb}, faintly through terrain as well, their faces faintly tinted. The
     * renderer meshes them off the render thread and reuses a mesh while its region only moves, so this is cheap to ask
     * every frame; a new outline may show a frame or two late. Boxes have no outline here (draw {@link #boxOutline}).
     * Nothing by default.
     */
    default void shapeOutlines(List<? extends Region> regions, int argb, int copyArgb) {}
}
