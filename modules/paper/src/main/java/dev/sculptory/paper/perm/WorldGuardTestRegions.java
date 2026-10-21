package dev.sculptory.paper.perm;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import net.minecraft.server.level.ServerLevel;

/**
 * Dev only (the self-test): adds and removes a plain WorldGuard cuboid region, no members, no flags, so only its owners
 * and bypassing players may build in it. Name this class only when WorldGuard is enabled.
 */
public final class WorldGuardTestRegions {
    private WorldGuardTestRegions() {
    }

    public static void add(ServerLevel level, String id, int x0, int y0, int z0, int x1, int y1, int z1) {
        manager(level).addRegion(new ProtectedCuboidRegion(id, BlockVector3.at(x0, y0, z0), BlockVector3.at(x1, y1, z1)));
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
