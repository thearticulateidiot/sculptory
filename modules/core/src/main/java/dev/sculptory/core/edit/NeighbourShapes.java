package dev.sculptory.core.edit;

import dev.sculptory.core.world.WorldReader;

/**
 * Update blocks: the state a cell takes once it has looked at its
 * neighbours (fences and panes connect, stairs shape, walls raise their posts), without physics. Fabric:
 * {@code getStateForNeighborUpdate} for each direction, over {@code view}. The server gives one through
 * {@link CompileContext#neighbourShapes}.
 */
@FunctionalInterface
public interface NeighbourShapes {
    /** The state cell (x, y, z), now {@code state}, takes after its neighbour updates; {@code state} when none applies. */
    int reshape(int x, int y, int z, int state, WorldReader view);
}
