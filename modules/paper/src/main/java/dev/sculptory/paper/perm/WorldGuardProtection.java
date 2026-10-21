package dev.sculptory.paper.perm;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import dev.sculptory.core.Box;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * WorldGuard's build rule ({@code RegionQuery.testBuild}: membership, the {@code build} flag, priorities, the global
 * region), cell by cell. Only loaded when WorldGuard is (a soft dependency: {@link SculptoryProtection#detect} names
 * this class only after checking), so the plugin runs without it.
 *
 * <p>Per chunk part, one region lookup first: with no region touching the part, every cell has the global region's
 * answer (one test). WorldGuard's own bypass (the {@code worldguard.region.bypass.<world>} permission with bypass on in
 * the player's session) allows everything.
 */
final class WorldGuardProtection implements Protection {
    private static final String PROBE_ID = "__sculptory_probe__";

    /** Links against WorldGuard now, so an incompatible API fails here (SculptoryProtection) and not mid-edit. */
    WorldGuardProtection() {
        WorldGuard.getInstance().getPlatform().getRegionContainer();
        WorldGuardPlugin.inst();
    }

    @Override
    public CellTest cells(ServerPlayer player, ServerLevel level, Box part) {
        WorldGuard worldGuard = WorldGuard.getInstance();
        LocalPlayer local = WorldGuardPlugin.inst().wrapPlayer(player.getBukkitEntity());
        World world = BukkitAdapter.adapt(level.getWorld());
        if (worldGuard.getPlatform().getSessionManager().hasBypass(local, world)) return CellTest.ALLOW;
        RegionContainer container = worldGuard.getPlatform().getRegionContainer();
        RegionQuery query = container.createQuery();
        RegionManager manager = container.get(world);
        if (manager == null) return CellTest.ALLOW; // regions are off in this world
        ProtectedCuboidRegion probe = new ProtectedCuboidRegion(PROBE_ID, true,
                BlockVector3.at(part.min().x(), part.min().y(), part.min().z()),
                BlockVector3.at(part.max().x(), part.max().y(), part.max().z()));
        if (manager.getApplicableRegions(probe).size() == 0) {
            // Only the global region applies: one answer for the whole part.
            return query.testBuild(cellCentre(world, part.min().x(), part.min().y(), part.min().z()), local)
                    ? CellTest.ALLOW : CellTest.DENY;
        }
        return (x, y, z) -> query.testBuild(cellCentre(world, x, y, z), local);
    }

    private static Location cellCentre(World world, int x, int y, int z) {
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }

    @Override
    public String name() {
        return "WorldGuard";
    }
}
