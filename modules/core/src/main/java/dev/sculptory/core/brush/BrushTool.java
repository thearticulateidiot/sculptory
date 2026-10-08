package dev.sculptory.core.brush;

import java.util.List;

/**
 * Brush kinds: the six terrain brushes, the Shape brush ({@link #SHAPE}, which places a solid shape at each dab:
 * {@link ShapeSpec}), the Weather brush ({@link #WEATHER}, which erodes, fills in, roughens or melts the surface:
 * {@link WeatherSpec}), and from protocol 6 the Heightmap brush ({@link #HEIGHTMAP}, which stamps a height image:
 * {@link HeightmapSpec}) and the Biome brush ({@link #BIOME}, which paints biomes: {@link BiomeSpec}). Wire order:
 * append only.
 */
public enum BrushTool {
    RAISE,
    LOWER,
    SMOOTH,
    FLATTEN,
    PAINT,
    PALETTE,
    SHAPE,
    WEATHER,
    /** Protocol 6: stamps a height image on the terrain, from above ({@link HeightmapSpec}). */
    HEIGHTMAP,
    /** Protocol 6: paints a biome ({@link BiomeSpec}); writes biomes, never blocks. */
    BIOME;

    /** The six terrain brushes, which sculpt and paint the surface ({@code TerrainKernel}). */
    public static final List<BrushTool> TERRAIN = List.of(RAISE, LOWER, SMOOTH, FLATTEN, PAINT, PALETTE);

    /** Whether this is one of the six terrain brushes ({@link #TERRAIN}); every other brush has its own kernel. */
    public boolean terrain() {
        return TERRAIN.contains(this);
    }
}
