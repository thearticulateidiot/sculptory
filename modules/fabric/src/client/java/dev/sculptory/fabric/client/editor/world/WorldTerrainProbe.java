package dev.sculptory.fabric.client.editor.world;

import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

/**
 * {@link TerrainProbe} over a real world. A block is terrain when it has a collision shape and is not
 * replaceable, the same notion a {@code COLLIDER} raycast uses: grass, flowers, fluids and air are
 * skipped; leaves, logs and full blocks count.
 *
 * <p>Equality is by world identity, so a fresh probe per frame still hits the sampler's cache.
 * Unloaded chunks read as air. Render thread only.
 */
public final class WorldTerrainProbe implements TerrainProbe {
    private final BlockView world;
    private final int bottomY;
    private final int topY;
    private final BlockPos.Mutable cursor = new BlockPos.Mutable();

    public WorldTerrainProbe(BlockView world) {
        this.world = world;
        this.bottomY = world.getBottomY();
        this.topY = world.getTopY();
    }

    @Override
    public boolean isTerrainSolid(int x, int y, int z) {
        cursor.set(x, y, z);
        BlockState state = world.getBlockState(cursor);
        return isTerrainSolid(world, cursor, state);
    }

    /** The terrain rule, usable from other client code. */
    public static boolean isTerrainSolid(BlockView world, BlockPos pos, BlockState state) {
        return !state.isAir() && !state.isReplaceable() && !state.getCollisionShape(world, pos).isEmpty();
    }

    @Override
    public int bottomY() {
        return bottomY;
    }

    @Override
    public int topY() {
        return topY;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof WorldTerrainProbe probe && probe.world == world;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(world);
    }
}
