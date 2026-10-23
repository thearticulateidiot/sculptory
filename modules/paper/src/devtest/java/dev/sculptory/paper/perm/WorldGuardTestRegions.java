package dev.sculptory.paper.perm;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.config.WorldConfiguration;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import java.lang.reflect.Method;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;

/**
 * Dev only (the self-test and the region benchmark): adds and removes WorldGuard regions. Name this class only
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

    /**
     * A region where non-members may do one of the two: {@code block-break allow} (a mine) or {@code block-place allow},
     * no members, {@code build} unset. An edit both breaks and places, so a non-member may edit neither.
     */
    public static void addAllowing(ServerLevel level, String id, int x0, int y0, int z0, int x1, int y1, int z1,
                                   boolean allowBreak) {
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id, BlockVector3.at(x0, y0, z0),
                BlockVector3.at(x1, y1, z1));
        region.setFlag(allowBreak ? Flags.BLOCK_BREAK : Flags.BLOCK_PLACE, StateFlag.State.ALLOW);
        manager(level).addRegion(region);
    }

    /** A polygonal region over the world's whole height, corners in order (x, z pairs), no members, no flags. */
    public static void addPolygon(ServerLevel level, String id, int... xz) {
        java.util.List<BlockVector2> points = new java.util.ArrayList<>();
        for (int i = 0; i + 1 < xz.length; i += 2) points.add(BlockVector2.at(xz[i], xz[i + 1]));
        manager(level).addRegion(new ProtectedPolygonalRegion(id, points, level.getMinBuildHeight(),
                level.getMaxBuildHeight() - 1));
    }

    /** A region with a priority (the benchmark's overlapping regions). */
    public static void addWithPriority(ServerLevel level, String id, int x0, int y0, int z0, int x1, int y1, int z1,
                                       int priority) {
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(id, BlockVector3.at(x0, y0, z0),
                BlockVector3.at(x1, y1, z1));
        region.setPriority(priority);
        manager(level).addRegion(region);
    }

    /** Dev diagnostics: whether region {@code id} contains (x, y, z) and how many regions WorldGuard finds there. */
    public static String describe(ServerLevel level, String id, int x, int y, int z) {
        RegionManager manager = manager(level);
        var region = manager.getRegion(id);
        return id + ": " + (region == null ? "missing" : "contains=" + region.contains(x, y, z) + " min="
                + region.getMinimumPoint() + " max=" + region.getMaximumPoint()) + ", regions at the point "
                + manager.getApplicableRegions(BlockVector3.at(x, y, z)).size();
    }


    /**
     * Runs {@code body} while {@code level} has no region manager: with {@code regionsOn}, as when the world's region
     * data failed to load (WorldGuard then protects everything there); without, as when regions are off in the world.
     * The world's regions are unloaded first (WorldGuard saves them as it does) and loaded back afterwards, whatever
     * {@code body} does. Server thread, within one call, so no tick passes without them.
     */
    public static <T> T withoutRegionManager(ServerLevel level, boolean regionsOn, Supplier<T> body) {
        World world = BukkitAdapter.adapt(level.getWorld());
        RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
        WorldConfiguration config = WorldGuard.getInstance().getPlatform().getGlobalStateManager().get(world);
        boolean wasOn = config.useRegions;
        container.unload(world);
        config.useRegions = regionsOn;
        try {
            if (container.get(world) != null) {
                throw new IllegalStateException("WorldGuard kept a region manager for " + level.dimension().location());
            }
            return body.get();
        } finally {
            config.useRegions = wasOn;
            load(container, world);
        }
    }

    /** WorldGuard loads a world's regions in its protected {@code RegionContainer.load(World)} (what world load calls). */
    private static void load(RegionContainer container, World world) {
        try {
            Method load = RegionContainer.class.getDeclaredMethod("load", World.class);
            load.setAccessible(true);
            if (load.invoke(container, world) == null) {
                throw new IllegalStateException("WorldGuard did not load the regions of " + world.getName() + " back");
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not load the regions of " + world.getName() + " back", e);
        }
    }

    /** Whether WorldGuard lets {@code player} bypass its regions in {@code level} (an operator does by default). */
    public static boolean bypasses(net.minecraft.server.level.ServerPlayer player, ServerLevel level) {
        return WorldGuard.getInstance().getPlatform().getSessionManager().hasBypass(
                com.sk89q.worldguard.bukkit.WorldGuardPlugin.inst().wrapPlayer(player.getBukkitEntity()),
                BukkitAdapter.adapt(level.getWorld()));
    }

    /** Whether WorldGuard has a region manager for {@code level} (regions on and loaded). */
    public static boolean hasManager(ServerLevel level) {
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(level.getWorld())) != null;
    }

    /**
     * A per-cell answer whose WorldGuard call fails to link (as with another WorldGuard API) refuses the cell instead of
     * throwing into the executor: true when the guarded answer was "refused" and nothing escaped.
     */
    public static boolean linkageErrorRefuses() {
        Protection.CellTest guarded = WorldGuardProtection.guarded((x, y, z) -> {
            throw new NoSuchMethodError("com.sk89q.worldguard.protection.regions.RegionQuery.testBuild (self-test)");
        });
        try {
            return !guarded.allowed(0, 0, 0);
        } catch (LinkageError e) {
            return false;
        }
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
