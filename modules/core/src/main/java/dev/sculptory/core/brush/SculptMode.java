package dev.sculptory.core.brush;

/**
 * How Raise, Lower, Smooth, Flatten and Weather sculpt: {@link #TERRAIN}
 * works as seen from above (the column kernel moves each column of the footprint up or down as a unit; Weather works on
 * the sky side of the footprint's columns); {@link #SURFACE} works on whatever surface the dab touches, floor, wall,
 * ceiling or overhang, inside the brush's ball ({@code SurfaceKernel}, {@code WeatherKernel}). Paint, Palette Paint and
 * the Shape brush have no mode ({@link #TERRAIN}). Wire order: append only.
 */
public enum SculptMode {
    /** "Terrain (from above)": the column kernel, unchanged since before the Surface mode. */
    TERRAIN,
    /** "Surface (any direction)": the ball kernel ({@code SurfaceKernel}). */
    SURFACE;

    /** Whether {@code tool} can sculpt in the Surface mode: Raise, Lower, Smooth, Flatten and Weather. */
    public static boolean surfaceTool(BrushTool tool) {
        return tool == BrushTool.RAISE || tool == BrushTool.LOWER || tool == BrushTool.SMOOTH
                || tool == BrushTool.FLATTEN || tool == BrushTool.WEATHER;
    }
}
