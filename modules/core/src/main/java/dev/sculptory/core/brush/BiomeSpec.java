package dev.sculptory.core.brush;

import dev.sculptory.core.NamespacedId;

/**
 * What the Biome brush paints ({@link BrushTool#BIOME}, protocol 6). Biomes are stored per quart (a 4×4×4 cell
 * aligned to multiples of 4). A quart is painted when its geometric centre ({@code min + 2.0}, a point between cells)
 * lies inside the dab's footprint, and the quart under the dab's centre always is. With {@code columns} (the default)
 * the footprint is the brush's circle or square across, a chosen quart column is painted over the world's whole build
 * height, and the masks are judged at that column's surface block; without ("3D") the footprint is the dab's ball or
 * cube and each quart is judged at its centre cell (BiomeKernel). The brush writes biomes only, never blocks, and needs
 * the {@code biome} node.
 *
 * @param biomeId the biome's registry id ("minecraft:cherry_grove"); a well-formed namespaced id
 */
public record BiomeSpec(String biomeId, boolean columns) {
    public BiomeSpec {
        new NamespacedId(biomeId); // refuses a malformed id
    }
}
