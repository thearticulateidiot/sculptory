package dev.sculptory.core.nav;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Where Jump and Through put the player: feet positions with two
 * free cells ({@code StateFlags.NO_COLLISION}) for the feet and the head.
 *
 * <p><b>Free.</b> A cell is free when a player passes through it ({@code NO_COLLISION}) and it does not hurt: not lava,
 * fire or soul fire, not a lava-bearing cauldron or powder snow (both without collision in part), and not a portal.
 * Cells above the build height are air, so a player may stand on the topmost layer; the feet are never below the bottom
 * of the world, nor above its top. Only loaded chunks are read: a spot in an unloaded one is never found.
 *
 * <p><b>Jump</b> ({@link #onTop}) stands the player on the first spot above the block looked at (in it, for a block one
 * walks through) whose feet and head cells are free and whose cell below has collision (something to stand on, so
 * not lava or water): on top of the block, or, when blocks are piled on it, on top of the first gap tall enough.
 * <b>Through</b> ({@link #through}) walks the look direction cell by cell from the block looked at: through the blocks
 * of the wall, then the first free cell with a free cell below it (the feet there, the head here: standing when
 * looking level, hanging under a floor when looking down) or above it (the feet here), going on up to
 * {@code maxDepth} blocks from the block looked at. Through does not need ground under the spot (the player flies).
 */
public final class Landing {
    /** Free of collision but harmful: never a spot for the feet or the head. */
    private static final Set<NamespacedId> HARMFUL = Set.of(new NamespacedId("minecraft:lava"),
            new NamespacedId("minecraft:fire"), new NamespacedId("minecraft:soul_fire"),
            new NamespacedId("minecraft:powder_snow"), new NamespacedId("minecraft:lava_cauldron"),
            new NamespacedId("minecraft:sweet_berry_bush"), new NamespacedId("minecraft:wither_rose"),
            new NamespacedId("minecraft:cobweb"), new NamespacedId("minecraft:nether_portal"),
            new NamespacedId("minecraft:end_portal"), new NamespacedId("minecraft:end_gateway"));

    private Landing() {}

    /** Jump: the first free standing spot on top of the column of the block {@code hit}, or empty when there is none. */
    public static Optional<BlockPos> onTop(WorldReader world, BlockPos hit) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(hit);
        if (!world.isLoaded(hit.x() >> 4, hit.z() >> 4)) return Optional.empty();
        int top = world.topYExclusive();
        // The feet stand on the block looked at, or higher; at most on the topmost layer. A block looked at that one
        // walks through (a flower, grass) is where the feet go, on what is under it.
        long first = free(world, hit.x(), hit.y(), hit.z()) ? hit.y() : (long) hit.y() + 1;
        int from = (int) Math.max(first, (long) world.bottomY() + 1);
        for (long y = from; y <= top; y++) {
            int feet = (int) y;
            if (!free(world, hit.x(), feet, hit.z()) || !free(world, hit.x(), feet + 1, hit.z())) continue;
            if (standsOn(world, hit.x(), feet - 1, hit.z())) return Optional.of(new BlockPos(hit.x(), feet, hit.z()));
        }
        return Optional.empty();
    }

    /**
     * Through: the first free standing spot past the wall the player looks into at {@code hit}, going on along the look
     * direction (dx, dy, dz) at most {@code maxDepth} blocks, or empty when there is none.
     */
    public static Optional<BlockPos> through(WorldReader world, BlockPos hit, double dx, double dy, double dz,
                                             int maxDepth) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(hit);
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!(length > 0) || !Double.isFinite(length) || maxDepth < 1) return Optional.empty();
        dx /= length;
        dy /= length;
        dz /= length;
        // Walk the cells the ray from the hit block's centre passes through (Amanatides-Woo).
        int x = hit.x(), y = hit.y(), z = hit.z();
        int stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dx);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dy);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1 / Math.abs(dz);
        double tMaxX = tDeltaX / 2, tMaxY = tDeltaY / 2, tMaxZ = tDeltaZ / 2;
        if (!loaded(world, x, z)) return Optional.empty();
        boolean inWall = !free(world, x, y, z);
        for (int cells = 0; cells < 4 * maxDepth; cells++) {
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                if (tMaxX > maxDepth) break;
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                if (tMaxY > maxDepth) break;
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > maxDepth) break;
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (!loaded(world, x, z)) return Optional.empty();
            if (y < world.bottomY() || y > world.topYExclusive()) return Optional.empty();
            if (!free(world, x, y, z)) {
                inWall = true;
                continue;
            }
            if (!inWall) continue;
            // Past the wall: the head here with the feet below (standing, when looking level), or the feet here.
            if (fits(world, x, y - 1, z)) return Optional.of(new BlockPos(x, y - 1, z));
            if (fits(world, x, y, z)) return Optional.of(new BlockPos(x, y, z));
        }
        return Optional.empty();
    }

    /** Whether feet at (x, y, z) and the head above are free, within the world's height. */
    private static boolean fits(WorldReader world, int x, int y, int z) {
        if (y < world.bottomY() || y > world.topYExclusive()) return false;
        return free(world, x, y, z) && free(world, x, y + 1, z);
    }

    /**
     * Whether a player may occupy the cell: no collision and no harm (see the class comment); cells above the build
     * height are air, cells below its bottom are not free (the void).
     */
    static boolean free(WorldReader world, int x, int y, int z) {
        if (y < world.bottomY()) return false;
        if (y >= world.topYExclusive()) return true;
        int state = world.get(x, y, z);
        StateSpace states = world.states();
        int flags = states.flags(state);
        if (!StateFlags.has(flags, StateFlags.NO_COLLISION)) return false;
        // Lava is a fluid that is not water; the others are named.
        if (StateFlags.has(flags, StateFlags.FLUID_BLOCK) && !StateFlags.has(flags, StateFlags.WATER)) return false;
        return !HARMFUL.contains(states.blockId(state));
    }

    /** Whether feet at {@code feet} stand on something: the cell below has collision, inside the world. */
    public static boolean grounded(WorldReader world, BlockPos feet) {
        return world.isLoaded(feet.x() >> 4, feet.z() >> 4) && standsOn(world, feet.x(), feet.y() - 1, feet.z());
    }

    /** Whether a player stands on the cell: it has collision (lava, water and plants do not), inside the world. */
    static boolean standsOn(WorldReader world, int x, int y, int z) {
        if (y < world.bottomY() || y >= world.topYExclusive()) return false;
        return !StateFlags.has(world.states().flags(world.get(x, y, z)), StateFlags.NO_COLLISION);
    }

    private static boolean loaded(WorldReader world, int x, int z) {
        return world.isLoaded(x >> 4, z >> 4);
    }
}
