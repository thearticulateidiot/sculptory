package dev.sculptory.core.brush;

import java.util.List;

/**
 * Brush kinds: the six terrain brushes, the Shape brush ({@link #SHAPE}, which places a solid shape at each dab:
 * {@link ShapeSpec}) and the Weather brush ({@link #WEATHER}, which erodes, fills in, roughens or melts the surface:
 * {@link WeatherSpec}). Wire order: append only.
 */
public enum BrushTool {
    RAISE,
    LOWER,
    SMOOTH,
    FLATTEN,
    PAINT,
    PALETTE,
    SHAPE,
    WEATHER;

    /** The six terrain brushes, which sculpt and paint the surface ({@code TerrainKernel}). */
    public static final List<BrushTool> TERRAIN = List.of(RAISE, LOWER, SMOOTH, FLATTEN, PAINT, PALETTE);

    /** Whether this is one of the six terrain brushes (neither {@link #SHAPE} nor {@link #WEATHER}). */
    public boolean terrain() {
        return this != SHAPE && this != WEATHER;
    }
}
