package dev.sculptory.fabric.client.editor.world;

/**
 * Block-level question the {@link SurfaceSampler} asks: is this block terrain surface? Implemented
 * over the client world by {@link WorldTerrainProbe} and by small fakes in tests.
 *
 * <p>The sampler compares probes with {@code equals} for caching, so a probe should be equal to
 * another probe over the same world.
 */
public interface TerrainProbe {
    /** True when the block at the position counts as solid terrain. */
    boolean isTerrainSolid(int x, int y, int z);

    /** Lowest block Y the probe can answer for (inclusive). */
    int bottomY();

    /** One above the highest block Y the probe can answer for (exclusive). */
    int topY();

    /**
     * A y at and above which column (x, z) holds no terrain (only air), or {@link #topY()} when the probe can't say:
     * where a scan down may start. Never below the column's highest non-air block.
     */
    default int heightHint(int x, int z) {
        return topY();
    }
}
