package dev.sculptory.core.brush;

import dev.sculptory.core.NamespacedId;

/**
 * What the Biome brush paints ({@link BrushTool#BIOME}, protocol 6). Biomes are stored per quart (a 4×4×4 cell
 * aligned to multiples of 4). With {@code columns} (the default) a quart column is painted over the world's whole build
 * height when its centre column ({@code min + 2} on x and z) lies inside the dab's footprint, and the brush mask is
 * judged at that column's surface block; without ("3D"), each quart is painted when its centre cell ({@code min + 2} on
 * every axis) lies inside the dab's ball, judged at that cell. The brush writes biomes only, never blocks, and needs the
 * {@code biome} node.
 *
 * @param biomeId the biome's registry id ("minecraft:cherry_grove"); a well-formed namespaced id
 */
public record BiomeSpec(String biomeId, boolean columns) {
    public BiomeSpec {
        new NamespacedId(biomeId); // refuses a malformed id
    }
}
