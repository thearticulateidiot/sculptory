package dev.sculptory.paper.perm;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.LocalPlayer;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import dev.sculptory.core.Box;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * WorldGuard's rule for a player changing blocks, as its own block listener applies it: {@code build} together with
 * {@code block-place} and {@code block-break} (an edit both breaks what is there and places the new block), with
 * membership, priorities, inheritance and the global region, through {@code RegionQuery.testBuild}. WorldGuard's own
 * bypass (the {@code worldguard.region.bypass.<world>} permission with bypass on in the player's session) allows
 * everything. Only loaded when WorldGuard is (a soft dependency: {@link SculptoryProtection#detect} names this class
 * only after checking), so the plugin runs without it.
 *
 * <p>Cost: per chunk part, one region lookup ({@code getApplicableRegions}) finds the regions touching it. With none,
 * one query answers the whole part. Otherwise a cell's answer depends only on which of those regions contain it, so
 * WorldGuard is asked once per distinct combination (at a cell that has it) and the answers are kept for the part; each
 * cell is then placed from the regions' geometry (integer bounds for cuboids, WorldGuard's own test for other shapes),
 * and a column (the 2D permits' question) is split at the regions' heights rather than walked cell by cell.
 */
final class WorldGuardProtection implements Protection {
    private static final String PROBE_ID = "__sculptory_probe__";
    /** Distinct region combinations are keyed by a bit per region; beyond this many regions, cells are asked one by one. */
    private static final int MAX_KEYED_REGIONS = 64;

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
        RegionManager manager = container.get(world);
        if (manager == null) return CellTest.ALLOW; // regions are off in this world
        RegionQuery query = container.createQuery();
        ProtectedCuboidRegion probe = new ProtectedCuboidRegion(PROBE_ID, true,
                BlockVector3.at(part.min().x(), part.min().y(), part.min().z()),
                BlockVector3.at(part.max().x(), part.max().y(), part.max().z()));
        ApplicableRegionSet touching = manager.getApplicableRegions(probe);
        if (touching.size() == 0) {
            // Only the global region applies: one answer for the whole part.
            return allowed(query, world, local, part.min().x(), part.min().y(), part.min().z())
                    ? CellTest.ALLOW : CellTest.DENY;
        }
        List<ProtectedRegion> regions = new ArrayList<>(touching.size());
        for (ProtectedRegion region : touching) regions.add(region);
        if (regions.size() > MAX_KEYED_REGIONS) {
            return (x, y, z) -> allowed(query, world, local, x, y, z);
        }
        return new RegionCells(regions, query, world, local);
    }

    /** WorldGuard's answer at one cell: build with block-place and block-break, as its block listener asks. */
    static boolean allowed(RegionQuery query, World world, LocalPlayer local, int x, int y, int z) {
        return query.testBuild(new Location(world, x + 0.5, y + 0.5, z + 0.5), local, PLACE_AND_BREAK);
    }

    private static final StateFlag[] PLACE_AND_BREAK = {Flags.BLOCK_PLACE, Flags.BLOCK_BREAK};

    @Override
    public String name() {
        return "WorldGuard";
    }

    /** The cells of one chunk part, answered from the regions touching it (see the class comment). */
    private static final class RegionCells implements CellTest {
        private final ProtectedRegion[] regions;
        /** Per region: min x, min y, min z, max x, max y, max z (inclusive). */
        private final int[] bounds;
        /** Per region: whether it is a cuboid (its bounds are its shape). */
        private final boolean[] cuboid;
        private final RegionQuery query;
        private final World world;
        private final LocalPlayer local;
        /** Answers per combination of containing regions (bit i: region i). */
        private final Long2BooleanOpenHashMap answers = new Long2BooleanOpenHashMap();

        RegionCells(List<ProtectedRegion> regions, RegionQuery query, World world, LocalPlayer local) {
            this.regions = regions.toArray(new ProtectedRegion[0]);
            this.bounds = new int[this.regions.length * 6];
            this.cuboid = new boolean[this.regions.length];
            for (int i = 0; i < this.regions.length; i++) {
                ProtectedRegion region = this.regions[i];
                BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
                int[] b = {min.x(), min.y(), min.z(), max.x(), max.y(), max.z()};
                System.arraycopy(b, 0, bounds, i * 6, 6);
                cuboid[i] = region instanceof ProtectedCuboidRegion;
            }
            this.query = query;
            this.world = world;
            this.local = local;
        }

        @Override
        public boolean allowed(int x, int y, int z) {
            long key = 0;
            for (int i = 0; i < regions.length; i++) {
                if (y < bounds[i * 6 + 1] || y > bounds[i * 6 + 4]) continue;
                if (inFootprint(i, x, z)) key |= 1L << i;
            }
            return answer(key, x, y, z);
        }

        /** The column split at the heights where a region containing it starts or ends; one answer per piece. */
        @Override
        public boolean columnAllowed(int x, int z, int y0, int y1) {
            int[] edges = new int[regions.length * 2 + 2];
            int n = 0;
            long inColumn = 0;
            for (int i = 0; i < regions.length; i++) {
                int min = bounds[i * 6 + 1], max = bounds[i * 6 + 4];
                if (max < y0 || min > y1 || !inFootprint(i, x, z)) continue;
                inColumn |= 1L << i;
                if (min > y0) edges[n++] = min;
                if (max < y1) edges[n++] = max + 1;
            }
            edges[n++] = y0;
            Arrays.sort(edges, 0, n);
            for (int e = 0; e < n; e++) {
                int y = edges[e];
                if (e > 0 && y == edges[e - 1]) continue;
                long key = 0;
                for (int i = 0; i < regions.length; i++) {
                    if ((inColumn & (1L << i)) != 0 && y >= bounds[i * 6 + 1] && y <= bounds[i * 6 + 4]) key |= 1L << i;
                }
                if (!answer(key, x, y, z)) return false;
            }
            return true;
        }

        /** Whether region i's shape covers column (x, z) (at a height inside the region). */
        private boolean inFootprint(int i, int x, int z) {
            int o = i * 6;
            if (x < bounds[o] || x > bounds[o + 3] || z < bounds[o + 2] || z > bounds[o + 5]) return false;
            return cuboid[i] || regions[i].contains(BlockVector3.at(x, bounds[o + 1], z));
        }

        private boolean answer(long key, int x, int y, int z) {
            if (answers.containsKey(key)) return answers.get(key);
            boolean allowed = WorldGuardProtection.allowed(query, world, local, x, y, z);
            answers.put(key, allowed);
            return allowed;
        }
    }
}
