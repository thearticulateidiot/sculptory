package dev.sculptory.paper.perm;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import net.minecraft.server.level.ServerLevel;

/**
 * Dev only (the self-test and the region benchmark): adds and removes WorldGuard cuboid regions. Name this class only
 * when WorldGuard is enabled.
 */
public final class WorldGuardTestRegions {
    private WorldGuardTestRegions() {
    }

    public static boolean exists(ServerLevel level, String id) {
        return manager(level).hasRegion(id);
    }

    /** A plain region: no members, no flags, so only its owners and bypassing players may build in it. */
    public static void add(ServerLevel level, String id, int x0, int y0, int z0, int x1, int y1, int z1) {
        manager(level).addRegion(new ProtectedCuboidRegion(id, BlockVector3.at(x0, y0, z0), BlockVector3.at(x1, y1, z1)));
    }

    /**
     * A "hub": {@code passthrough allow} (membership no longer decides {@code build}), {@code build} unset, and
     * {@code block-place deny} and/or {@code block-break deny}. A rule that asks {@code build} alone lets everyone edit
     * here; WorldGuard's own block listener does not.
     */
    public static void addHub(ServerLevel level, String id, int x0, int y0, int z0, int x1, int y1, int z1,
                              boolean denyPlace, boolean denyBreak) {
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id, BlockVector3.at(x0, y0, z0),
                BlockVector3.at(x1, y1, z1));
        region.setFlag(Flags.PASSTHROUGH, StateFlag.State.ALLOW);
        if (denyPlace) region.setFlag(Flags.BLOCK_PLACE, StateFlag.State.DENY);
        if (denyBreak) region.setFlag(Flags.BLOCK_BREAK, StateFlag.State.DENY);
        manager(level).addRegion(region);
    }

    /** A region with a priority (the benchmark's overlapping regions). */
    public static void addWithPriority(ServerLevel level, String id, int x0, int y0, int z0, int x1, int y1, int z1,
                                       int priority) {
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id, BlockVector3.at(x0, y0, z0),
                BlockVector3.at(x1, y1, z1));
        region.setPriority(priority);
        manager(level).addRegion(region);
    }

    public static void remove(ServerLevel level, String id) {
        manager(level).removeRegion(id);
    }

    private static RegionManager manager(ServerLevel level) {
        RegionManager manager = WorldGuard.getInstance().getPlatform().getRegionContainer()
                .get(BukkitAdapter.adapt(level.getWorld()));
        if (manager == null) throw new IllegalStateException("WorldGuard has regions off in " + level.dimension());
        return manager;
    }
}
