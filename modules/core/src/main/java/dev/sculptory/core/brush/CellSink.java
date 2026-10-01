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
}
