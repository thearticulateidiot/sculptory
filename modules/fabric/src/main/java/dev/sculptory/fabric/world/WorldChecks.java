package dev.sculptory.fabric.world;

import dev.sculptory.core.Box;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;

/**
 * Position checks salvaged from {@code v0-final} {@code FabricOperationService.checkPosition}: build limit, world
 * border and loaded chunk. None of these load chunks.
 */
public final class WorldChecks {
    private WorldChecks() {}

    /** Inside the build height and the ±30M horizontal limit ({@link World#isInBuildLimit}). */
    public static boolean inBuildLimit(ServerWorld world, int x, int y, int z) {
        return world.isInBuildLimit(new BlockPos(x, y, z));
    }

    /** Inside the world border (the check {@code WorldBorder.contains(BlockPos)} makes). */
    public static boolean insideBorder(WorldBorder border, int x, int z) {
        return border.contains((double) x, (double) z);
    }

    /** The chunk is loaded to FULL status; never loads it. */
    public static boolean isChunkLoaded(ServerWorld world, int cx, int cz) {
        return world.getChunkManager().getWorldChunk(cx, cz) != null;
    }

    /** All three checks for one cell, as the old service made them before every capture or write. */
    public static boolean editable(ServerWorld world, int x, int y, int z) {
        return inBuildLimit(world, x, y, z)
                && insideBorder(world.getWorldBorder(), x, z)
                && isChunkLoaded(world, x >> 4, z >> 4);
    }

    /** True when some cell of {@code box} is inside the build height and the horizontal world limit. */
    public static boolean intersectsBuildLimit(ServerWorld world, Box box) {
        if (box.max().y() < world.getBottomY() || box.min().y() >= world.getTopY()) return false;
        int limit = World.HORIZONTAL_LIMIT;
        return box.max().x() >= -limit && box.min().x() < limit && box.max().z() >= -limit && box.min().z() < limit;
    }
}
