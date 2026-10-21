package dev.sculptory.mc.world;

import dev.sculptory.core.Box;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Position checks salvaged from {@code v0-final} {@code FabricOperationService.checkPosition}: build limit, world
 * border and loaded chunk. None of these load chunks.
 */
public final class WorldChecks {
    private WorldChecks() {}

    /** Inside the build height and the ±30M horizontal limit ({@link Level#isInWorldBounds}). */
    public static boolean inBuildLimit(ServerLevel world, int x, int y, int z) {
        return world.isInWorldBounds(new BlockPos(x, y, z));
    }

    /** Inside the world border (the check {@code WorldBorder.contains(BlockPos)} makes). */
    public static boolean insideBorder(WorldBorder border, int x, int z) {
        return border.isWithinBounds((double) x, (double) z);
    }

    /** The chunk is loaded to FULL status; never loads it. */
    public static boolean isChunkLoaded(ServerLevel world, int cx, int cz) {
        return world.getChunkSource().getChunkNow(cx, cz) != null;
    }

    /** All three checks for one cell, as the old service made them before every capture or write. */
    public static boolean editable(ServerLevel world, int x, int y, int z) {
        return inBuildLimit(world, x, y, z)
                && insideBorder(world.getWorldBorder(), x, z)
                && isChunkLoaded(world, x >> 4, z >> 4);
    }

    /** True when some cell of {@code box} is inside the build height and the horizontal world limit. */
    public static boolean intersectsBuildLimit(ServerLevel world, Box box) {
        if (box.max().y() < world.getMinBuildHeight() || box.min().y() >= world.getMaxBuildHeight()) return false;
        int limit = Level.MAX_LEVEL_SIZE;
        return box.max().x() >= -limit && box.min().x() < limit && box.max().z() >= -limit && box.min().z() < limit;
    }
}
