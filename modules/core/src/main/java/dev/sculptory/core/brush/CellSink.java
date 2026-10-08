package dev.sculptory.core.brush;

import dev.sculptory.core.buffer.BlockEntityData;

/** Receives the cells a brush kernel writes. */
@FunctionalInterface
public interface CellSink {
    void set(int x, int y, int z, int handle);

    /**
     * A cell written with the block entity it keeps: a state pattern changed a property of the same block (a
     * waterlogged sign keeps its text; {@code Pattern.keepsTile}). Sinks that carry tiles override this; by default
     * the tile is dropped and the state written alone.
     */
    default void set(int x, int y, int z, int handle, BlockEntityData tile) {
        set(x, y, z, handle);
    }

    /**
     * A biome quart written (protocol 6, the Biome brush): quart {@code (qx, qy, qz)} (block coordinates shifted right
     * by 2) set to {@code biomeId}. Sinks that write biomes override this; by default it is dropped (the client's block
     * prediction).
     */
    default void biome(int qx, int qy, int qz, String biomeId) {}

    /**
     * A cell written over {@code before}, the state it held in the world the step read: kernels that know it pass it on
     * ({@link CellPlan}) for sinks that keep it; by default it is written as {@link #set(int, int, int, int)} writes it.
     */
    default void changed(int x, int y, int z, int handle, int before) {
        set(x, y, z, handle);
    }
}
