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
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionQuery;
import dev.sculptory.core.Box;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * WorldGuard's rule for a player changing blocks, as its own block listener applies it: {@code build} with
 * {@code block-place}, and {@code build} with {@code block-break}, asked as two queries and both required (an edit both
 * breaks what is there and places the new block), with membership, priorities, inheritance and the global region,
 * through {@code RegionQuery.testBuild}. One query naming both flags is not the same: WorldGuard combines the flags of
 * one query so that any ALLOW wins and a non-member's {@code build} DENY is dropped, so a mine region with only
 * {@code block-break allow} would let every non-member place there too. WorldGuard's own
 * bypass (the {@code worldguard.region.bypass.<world>} permission with bypass on in the player's session) allows
 * everything. Only loaded when WorldGuard is (a soft dependency: {@link SculptoryProtection#detect} names this class
 * only after checking), so the plugin runs without it.
 *
 * <p>Cost: per chunk part, one region lookup ({@code getApplicableRegions}) finds the regions touching it. With none,
 * one query answers the whole part. Otherwise a cell's answer depends only on which of those regions contain it, so
 * WorldGuard is asked once per distinct combination (at a cell that has it) and the answers are kept for the part; each
 * cell is then placed from the regions' geometry: which regions cover its column is worked out once per column
 * (integer bounds for cuboids, WorldGuard's own test for other shapes), and then only their heights are compared. No
 * {@code Location} and no query per cell, so {@code ChunkPermits.rasterize} can ask every cell of the part. A part
 * touched by more than {@value #MAX_KEYED_REGIONS} regions (the combination key is one bit per region) is answered
 * with two WorldGuard queries per cell instead: correct, only slower.
 *
 * <p>Polygons that meet a chunk only along a line (an edge on the chunk's first column or row, or a degenerate polygon
 * with all its points on one line): Sculptory answers as WorldGuard does, decided on purpose. WorldGuard's region
 * index ({@code ChunkHashTable}) files a region under a chunk only when the two overlap with a positive area (the same
 * {@code java.awt.geom.Area} test as its region lookup), so such a polygon is not filed for that chunk, and every point
 * query WorldGuard makes there (its own block listener's included) finds no region: a player may build on that line by
 * hand. The region lookup here finds the polygon ({@link Probe}), but each cell's answer comes from WorldGuard's own
 * query, which allows it; where the polygon also has area in the chunk, WorldGuard files it there and the line is
 * protected, here as for a player.
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
        ApplicableRegionSet touching = manager.getApplicableRegions(new Probe(part));
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

    /**
     * WorldGuard's answer at one cell: placing (build with block-place) and breaking (build with block-break), each
     * asked on its own as WorldGuard's block listener asks it, and both required (the class comment says why one query
     * with both flags is not the same).
     */
    static boolean allowed(RegionQuery query, World world, LocalPlayer local, int x, int y, int z) {
        Location at = new Location(world, x + 0.5, y + 0.5, z + 0.5);
        return query.testBuild(at, local, Flags.BLOCK_PLACE) && query.testBuild(at, local, Flags.BLOCK_BREAK);
    }

    /**
     * The region lookup's probe over a chunk part. WorldGuard's {@code getApplicableRegions(region)} keeps a candidate
     * only when the two regions' {@code java.awt.geom.Area}s overlap with a positive area, so it misses a polygon that
     * touches the part only along the part's min-x or min-z edge (the probe's area is the half-open rectangle
     * [x0, x1 + 1)) and a degenerate polygon (all its points on one line: no area at all), although
     * {@code ProtectedPolygonalRegion.contains} counts edges and corners as inside. This probe keeps every candidate whose
     * bounding box overlaps the part, edges included: more regions than really touch it (safe: which regions hold a
     * cell is then worked out by their own {@code contains}, and the cell's combination is answered by WorldGuard's own
     * query), never fewer. It is one block wider on every side, so the region index's own candidate search (by bounding
     * boxes, or by the chunks a region is indexed for) cannot drop an edge either.
     *
     * <p>Where WorldGuard itself does not protect: its chunk index ({@code ChunkHashTable}) files a region under a chunk
     * by the same positive-area test, so a polygon that meets a chunk only along a line (or a degenerate one) is not
     * filed there, and WorldGuard's point queries in that chunk (its block listener's too) do not see it. Such a cell's
     * combination is then answered "allowed" by WorldGuard's query, as a player placing there by hand is allowed.
     */
    static final class Probe extends ProtectedCuboidRegion {
        private final Box part;

        Probe(Box part) {
            super(PROBE_ID, true,
                    BlockVector3.at(part.min().x() - 1, part.min().y() - 1, part.min().z() - 1),
                    BlockVector3.at(part.max().x() + 1, part.max().y() + 1, part.max().z() + 1));
            this.part = part;
        }

        @Override
        protected boolean intersects(ProtectedRegion region, java.awt.geom.Area thisArea) {
            BlockVector3 min = region.getMinimumPoint(), max = region.getMaximumPoint();
            return max.x() >= part.min().x() && min.x() <= part.max().x()
                    && max.y() >= part.min().y() && min.y() <= part.max().y()
                    && max.z() >= part.min().z() && min.z() <= part.max().z();
        }
    }

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
        /** Per column of the chunk ((z & 15) << 4 | (x & 15)): the regions covering it, once known. */
        private final long[] footprints = new long[256];
        private final boolean[] footprintKnown = new boolean[256];

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
            long footprint = footprint(x, z);
            long key = 0;
            while (footprint != 0) {
                int i = Long.numberOfTrailingZeros(footprint);
                footprint &= footprint - 1;
                if (y >= bounds[i * 6 + 1] && y <= bounds[i * 6 + 4]) key |= 1L << i;
            }
            return answer(key, x, y, z);
        }

        /** The regions whose shape covers column (x, z), worked out once per column of the chunk. */
        private long footprint(int x, int z) {
            int column = ((z & 15) << 4) | (x & 15);
            if (!footprintKnown[column]) {
                long mask = 0;
                for (int i = 0; i < regions.length; i++) {
                    if (inFootprint(i, x, z)) mask |= 1L << i;
                }
                footprints[column] = mask;
                footprintKnown[column] = true;
            }
            return footprints[column];
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
