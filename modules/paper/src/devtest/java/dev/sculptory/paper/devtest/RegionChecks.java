package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.aabb;
import static dev.sculptory.paper.devtest.SelfTestRun.box;

import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.paper.perm.Protection;
import dev.sculptory.paper.perm.WorldGuardTestRegions;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.RunOptions;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * WorldGuard regions (only when WorldGuard is installed; this class names WorldGuard's classes only through
 * {@link WorldGuardTestRegions}, after the check): per-cell refusals, region flags, region lookup loopholes, entities
 * and biomes under regions limited in height.
 */
final class RegionChecks {
    private RegionChecks() {
    }

    /**
     * A region of 4x2x4 cells inside one chunk, 2 high: a fill of the region's cells is refused cell by cell (all
     * skipped as protected), a fill beside it in the same chunk is written and its undo exact, a box of its own above the
     * region's height is written, and one box over the region and the two layers above it keeps the region while its
     * layers above are written (3D permits).
     */
    static CompletableFuture<Void> cells(SelfTestRun r) {
        if (!r.worldGuard()) {
            r.info("worldguard: skipped", "WorldGuard is not installed");
            return CompletableFuture.completedFuture(null);
        }
        if (r.attachment == null && r.player.hasPermissions(2)) {
            r.info("worldguard: note", r.name() + " is an operator; WorldGuard may let them bypass (run without a "
                    + "player for the fake one)");
        }
        int x = r.baseX + 80 + 4, z = r.baseZ + 4;
        String id = r.region("cells");
        Box inside = box(x, 101, z, x + 3, 102, z + 3);
        Box beside = box(x + 6, 101, z, x + 9, 102, z + 3);
        Box above = box(x, 110, z, x + 3, 111, z + 3);
        Box spanning = box(x, 101, z, x + 3, 104, z + 3);
        Box layersAbove = box(x, 103, z, x + 3, 104, z + 3);
        r.claim(box(x, 101, z, x + 9, 111, z + 3));
        WorldGuardTestRegions.add(r.level, id, x, 101, z, x + 3, 102, z + 3);
        int[] insideBefore = r.snapshot(inside);
        int[] besideBefore = r.snapshot(beside);
        int planks = Block.getId(Blocks.OAK_PLANKS.defaultBlockState());
        return r.fill(inside, planks)
                .thenCompose(result -> {
                    int changed = r.countDiffering(inside, insideBefore);
                    r.record("worldguard: fill inside the region refused", changed == 0 && result.changed() == 0,
                            result.outcome() + ", " + result.changed() + " changed, " + result.skippedProtected()
                                    + " skipped as protected, " + changed + " cells differ");
                    return r.fill(beside, planks);
                })
                .thenCompose(result -> {
                    int wrong = r.countNot(beside, planks);
                    r.record("worldguard: fill beside the region (same chunk) written", wrong == 0
                            && result.skippedProtected() == 0, result.changed() + " changed, " + wrong + " not planks");
                    return r.undo();
                })
                .thenCompose(result -> {
                    r.record("worldguard: undo of the fill beside exact", r.countDiffering(beside, besideBefore) == 0,
                            r.countDiffering(beside, besideBefore) + " cells differ");
                    return r.fill(above, planks);
                })
                .thenCompose(result -> {
                    // A box of its own above the region's height: the permit is asked for the box's heights only.
                    int wrong = r.countNot(above, planks);
                    r.record("worldguard: fill above the region's height (own box) written", wrong == 0,
                            result.changed() + " changed, " + wrong + " not planks");
                    return r.undo();
                })
                .thenCompose(result -> r.fill(spanning, planks))
                .thenAccept(result -> {
                    // One box over the region's 32 cells and the two layers above them: with per-cell (3D) permits only
                    // the region's cells are skipped, and the layers above it in the same columns are written.
                    int regionChanged = r.countDiffering(inside, insideBefore);
                    int notWritten = r.countNot(layersAbove, planks);
                    r.record("worldguard: box over the region and above it keeps the region", regionChanged == 0,
                            regionChanged + " region cells differ");
                    r.record("worldguard: only the region's cells are skipped (3D permits)",
                            notWritten == 0 && result.skippedProtected() == 32 && result.changed() == 32,
                            result.changed() + " changed, " + result.skippedProtected() + " skipped as protected, "
                                    + notWritten + " cells above the region not written");
                });
    }

    /**
     * A world without a region manager: WorldGuard has none both when regions are off in the world and when the world's
     * region data failed to load, and protects everything in the second case. Sculptory must follow: a chunk part with no
     * region in it is allowed with the regions loaded, refused with regions on and no manager (a failed load), and
     * allowed with regions off. Asks the protection directly (nothing is written); the world's regions are unloaded and
     * loaded back within the call ({@link WorldGuardTestRegions#withoutRegionManager}).
     */
    static CompletableFuture<Void> missingRegionData(SelfTestRun r) {
        if (!r.worldGuard()) {
            r.info("worldguard missing region data: skipped", "WorldGuard is not installed");
            return CompletableFuture.completedFuture(null);
        }
        if (r.attachment == null && r.player.hasPermissions(2)) {
            r.info("worldguard missing region data: skipped", r.name() + " is an operator, whom WorldGuard may let "
                    + "bypass (run without a player for the fake one)");
            return CompletableFuture.completedFuture(null);
        }
        int x = r.baseX + 80 + 4, z = r.baseZ + 40;
        Box part = box(x, 101, z, x + 3, 102, z + 3);
        Protection protection = r.host.engine().permissions().protection();
        String loaded = answer(protection.cells(r.player, r.level, part), part);
        String failedLoad = WorldGuardTestRegions.withoutRegionManager(r.level, true,
                () -> answer(protection.cells(r.player, r.level, part), part));
        String regionsOff = WorldGuardTestRegions.withoutRegionManager(r.level, false,
                () -> answer(protection.cells(r.player, r.level, part), part));
        String after = answer(protection.cells(r.player, r.level, part), part);
        r.record("worldguard missing region data: a part with no region is allowed", loaded.equals("allowed"),
                "regions loaded: " + loaded);
        r.record("worldguard missing region data: regions on and no region data (a failed load) refuses",
                failedLoad.equals("refused"), "no manager, regions on: " + failedLoad);
        r.record("worldguard missing region data: regions off allows", regionsOff.equals("allowed"),
                "no manager, regions off: " + regionsOff);
        r.record("worldguard missing region data: the regions are back afterwards", after.equals("allowed")
                && WorldGuardTestRegions.hasManager(r.level), "after: " + after);
        // Logs one "WorldGuard's API could not be used mid-edit" error on purpose (the simulated failure).
        r.record("worldguard API failure: a call that fails to link refuses the cell, nothing escapes",
                WorldGuardTestRegions.linkageErrorRefuses(), "a simulated NoSuchMethodError in a cell query");
        return CompletableFuture.completedFuture(null);
    }

    /** "allowed", "refused", or "mixed" over the cells of {@code part}. */
    private static String answer(Protection.CellTest cells, Box part) {
        int allowed = 0, total = 0;
        for (int x = part.min().x(); x <= part.max().x(); x++) {
            for (int y = part.min().y(); y <= part.max().y(); y++) {
                for (int z = part.min().z(); z <= part.max().z(); z++) {
                    total++;
                    if (cells.allowed(x, y, z)) allowed++;
                }
            }
        }
        return allowed == total ? "allowed" : allowed == 0 ? "refused" : "mixed (" + allowed + " of " + total + ")";
    }

    /**
     * WorldGuard's place and break flags: two regions with {@code passthrough allow} (membership does not decide
     * {@code build}) and {@code build} unset, one with {@code block-place deny}, one with {@code block-break deny}.
     * WorldGuard's own listener refuses a player there; an edit both breaks and places, so a fill inside either must be
     * refused too.
     */
    static CompletableFuture<Void> flags(SelfTestRun r) {
        if (!r.worldGuard()) return CompletableFuture.completedFuture(null);
        int x = r.baseX + 120 + 4, z = r.baseZ + 4;
        String placeId = r.region("place");
        String breakId = r.region("break");
        Box placeBox = box(x, 101, z, x + 3, 102, z + 3);
        Box breakBox = box(x + 6, 101, z, x + 9, 102, z + 3);
        r.claim(box(x, 101, z, x + 9, 102, z + 3));
        WorldGuardTestRegions.addHub(r.level, placeId, x, 101, z, x + 3, 102, z + 3, true, false);
        WorldGuardTestRegions.addHub(r.level, breakId, x + 6, 101, z, x + 9, 102, z + 3, false, true);
        int[] placeBefore = r.snapshot(placeBox);
        int[] breakBefore = r.snapshot(breakBox);
        int planks = Block.getId(Blocks.OAK_PLANKS.defaultBlockState());
        return r.fill(placeBox, planks)
                .thenCompose(result -> {
                    r.record("worldguard flags: block-place deny refuses a fill", r.countDiffering(placeBox, placeBefore)
                            == 0, result.changed() + " changed, " + result.skippedProtected() + " skipped as protected");
                    return r.fill(breakBox, planks);
                })
                .thenAccept(result -> r.record("worldguard flags: block-break deny refuses a fill",
                        r.countDiffering(breakBox, breakBefore) == 0, result.changed() + " changed, "
                                + result.skippedProtected() + " skipped as protected"));
    }

    /**
     * Two loopholes a region lookup could leave open. Allow flags: one region with only {@code block-break allow} (a
     * mine) and one with only {@code block-place allow}, no members; a non-member may do one of the two there, but an
     * edit both breaks and places, so a fill inside either changes nothing (one WorldGuard query naming both flags
     * answered yes for both). A polygon's edge: a full-height L-shaped polygon west of chunk column X (a multiple of 16)
     * whose edge x = X, z Z+3..Z+12, is the only part of it a fill over x X..X+5, z Z..Z+10 touches, while its foot
     * (x X..X+3, z Z+12..Z+15) lies inside the chunk, so WorldGuard indexes it for that chunk and protects the edge strip
     * x = X, z Z+3..Z+10 against players. The fill must leave that strip as it was and write the rest: WorldGuard's region
     * lookup for a box keeps only regions overlapping it with a positive area, so the polygon was missed for the fill's
     * part of the chunk and the strip written. (A polygon touching a chunk only along that line, with no area in the
     * chunk, is not indexed for the chunk by WorldGuard itself, so WorldGuard does not protect that strip from players
     * either; Sculptory gives the same answer, since every cell is decided by WorldGuard's own query.)
     */
    static CompletableFuture<Void> loopholes(SelfTestRun r) {
        if (!r.worldGuard()) return CompletableFuture.completedFuture(null);
        int x = r.baseX + 240 + 4, z = r.baseZ + 4;
        String mineId = r.region("mine");
        String placeId = r.region("placeonly");
        String polygonId = r.region("polygon");
        Box mine = box(x, 101, z, x + 3, 102, z + 3);
        Box placeOnly = box(x + 6, 101, z, x + 9, 102, z + 3);
        // The polygon lies west of chunk column X (a multiple of 16) but for its edge x = X and its foot in the chunk.
        int px = r.baseX + 288, pz = r.baseZ + 16;
        Box fillBox = box(px, 101, pz, px + 5, 102, pz + 10);
        Box strip = box(px, 101, pz + 3, px, 102, pz + 10);
        r.claim(box(x, 101, z, x + 9, 102, z + 3));
        r.claim(box(px - 8, 101, pz, px + 5, 102, pz + 15));
        WorldGuardTestRegions.addAllowing(r.level, mineId, x, 101, z, x + 3, 102, z + 3, true);
        WorldGuardTestRegions.addAllowing(r.level, placeId, x + 6, 101, z, x + 9, 102, z + 3, false);
        WorldGuardTestRegions.addPolygon(r.level, polygonId, px - 8, pz + 3, px, pz + 3, px, pz + 12, px + 3, pz + 12,
                px + 3, pz + 15, px - 8, pz + 15);
        int[] mineBefore = r.snapshot(mine), placeBefore = r.snapshot(placeOnly), stripBefore = r.snapshot(strip);
        int planks = Block.getId(Blocks.OAK_PLANKS.defaultBlockState());
        return r.fill(mine, planks)
                .thenCompose(result -> {
                    r.record("worldguard loopholes: block-break allow alone does not let a fill in",
                            r.countDiffering(mine, mineBefore) == 0, result.changed() + " changed, "
                                    + result.skippedProtected() + " skipped as protected");
                    return r.fill(placeOnly, planks);
                })
                .thenCompose(result -> {
                    r.record("worldguard loopholes: block-place allow alone does not let a fill in",
                            r.countDiffering(placeOnly, placeBefore) == 0, result.changed() + " changed, "
                                    + result.skippedProtected() + " skipped as protected");
                    return r.fill(fillBox, planks);
                })
                .thenAccept(result -> {
                    int stripChanged = r.countDiffering(strip, stripBefore);
                    long written = fillBox.volume() - r.countNot(fillBox, planks);
                    long expected = fillBox.volume() - strip.volume();
                    r.record("worldguard loopholes: a polygon whose edge alone meets the fill keeps that edge",
                            stripChanged == 0 && written == expected, stripChanged + " strip cells changed, "
                                    + written + " cells written (expected " + expected + "), "
                                    + result.skippedProtected() + " skipped as protected; "
                                    + WorldGuardTestRegions.describe(r.level, polygonId, px, 101, pz + 5));
                });
    }

    /**
     * Entities under a region limited in height (entities were once judged at the box's lowest y). A 6x6x6 box at y
     * 100-105 with two armor stands, at y 101 and y 104; 10 blocks east the same box has a region over its top three
     * layers (y 103-105) with a third stand in it. A move there is refused as a whole (a move needs all of its source and
     * destination). A stack (copy) with entities places the copy of the y-101 stand, places nothing in the region (the
     * y-104 copy's cell is protected at its own height) and leaves the region's stand alone; the undo takes the copy
     * away and still leaves the region as it was. The area must hold no entity when the check starts.
     */
    static CompletableFuture<Void> entities(SelfTestRun r) {
        if (!r.worldGuard()) return CompletableFuture.completedFuture(null);
        int x = r.baseX + 160, z = r.baseZ + 4;
        SelfTestRun.Area area = r.claim(box(x - 2, 98, z - 2, x + 18, 108, z + 8));
        // Paper loads a chunk's entities a little after the chunk: look again once they are in.
        return r.ticks(5).thenCompose(v -> {
            if (area.hadEntities() || !r.level.getEntitiesOfClass(ArmorStand.class, aabb(area.box)).isEmpty()) {
                throw new IllegalStateException("the entity check's area holds entities already; it changes nothing");
            }
            return entities(r, x, z);
        });
    }

    private static CompletableFuture<Void> entities(SelfTestRun r, int x, int z) {
        String id = r.region("entities");
        Box box = box(x, 100, z, x + 5, 105, z + 5);
        Box region = box(x + 10, 103, z, x + 15, 105, z + 5);
        WorldGuardTestRegions.add(r.level, id, x + 10, 103, z, x + 15, 105, z + 5);
        stand(r, x + 2.5, 101, z + 2.5);
        stand(r, x + 2.5, 104, z + 2.5);
        ArmorStand guarded = stand(r, x + 14.5, 104, z + 4.5);
        int air = Block.getId(Blocks.AIR.defaultBlockState());
        OpSpec.Move move = new OpSpec.Move(new Region.Cuboid(box), new dev.sculptory.core.BlockPos(10, 0, 0),
                Transform.IDENTITY, new Pattern.Single(air), EntityFilter.DECORATIONS);
        OpSpec.Stack stack = new OpSpec.Stack(new Region.Cuboid(box), 10, 0, 0, 1, EntityFilter.DECORATIONS);
        String refusal;
        try {
            JobTicket ticket = r.service.run(r.player, move, RunOptions.DEFAULT, RegionBench.NO_LISTENER);
            r.service.cancel(r.player, ticket.jobId());
            refusal = "admitted";
        } catch (EditRejected e) {
            refusal = e.getMessage();
        }
        r.record("entities: a move into the region is refused as a whole",
                refusal.startsWith("PROTECTED") && stands(r, x + 12.5, 101, z + 2.5) == 0 && inRegion(r, region) == 1,
                refusal);
        return r.job("stack", listener -> r.service.run(r.player, stack, RunOptions.DEFAULT, listener))
                .thenCompose(result -> {
                    int copied = stands(r, x + 12.5, 101, z + 2.5);
                    int placedInRegion = stands(r, x + 12.5, 104, z + 2.5);
                    boolean guardedStays = guarded.isAlive() && stands(r, x + 14.5, 104, z + 4.5) == 1;
                    r.record("entities: a copy places nothing in a region above the box's lowest layer",
                            copied == 1 && placedInRegion == 0 && guardedStays && inRegion(r, region) == 1,
                            "free stand copied " + copied + ", placed in the region " + placedInRegion
                                    + ", the region's own stand untouched " + guardedStays + " (" + result.outcome()
                                    + ", " + result.skippedProtected() + " cells skipped as protected)");
                    return r.undo();
                })
                .thenCompose(result -> r.ticks(2).thenApply(v -> result))
                .thenAccept(result -> {
                    int copyLeft = stands(r, x + 12.5, 101, z + 2.5);
                    int sources = stands(r, x + 2.5, 101, z + 2.5) + stands(r, x + 2.5, 104, z + 2.5);
                    r.record("entities: the undo takes the copy away and leaves the region as it was",
                            copyLeft == 0 && sources == 2 && inRegion(r, region) == 1 && guarded.isAlive(),
                            "copy left " + copyLeft + ", source stands " + sources + ", stands in the region "
                                    + inRegion(r, region) + " (only its own); undo " + result.outcome() + ", "
                                    + result.changed() + " changed, " + result.skippedConflicts() + " conflicts");
                });
    }

    private static int inRegion(SelfTestRun r, Box region) {
        return r.level.getEntitiesOfClass(ArmorStand.class, aabb(region), ArmorStand::isAlive).size();
    }

    static ArmorStand stand(SelfTestRun r, double x, double y, double z) {
        ArmorStand stand = new ArmorStand(r.level, x, y, z);
        stand.setNoGravity(true);
        r.level.addFreshEntity(stand);
        return stand;
    }

    /** Armor stands whose block cell is the one holding (x, y, z). */
    static int stands(SelfTestRun r, double x, double y, double z) {
        BlockPos cell = BlockPos.containing(x, y, z);
        return r.level.getEntitiesOfClass(ArmorStand.class, new AABB(cell), ArmorStand::isAlive).size();
    }

    /**
     * Biomes under a region limited in height: a biome fill over 8x16x8 (two quarts across, four up, y 96-111) with a
     * region at y 101-102 over the first quart column. The quart whose centre (y 102) is in the region keeps its biome;
     * the quarts above and below it and beside it are painted; the undo puts every quart back.
     */
    static CompletableFuture<Void> biomes(SelfTestRun r) {
        if (!r.worldGuard()) return CompletableFuture.completedFuture(null);
        int x = r.baseX + 200, z = r.baseZ + 4; // multiples of 4: whole quarts
        String id = r.region("biomes");
        Box box = box(x, 96, z, x + 7, 111, z + 7);
        r.claim(box);
        WorldGuardTestRegions.add(r.level, id, x, 101, z, x + 3, 102, z + 3);
        int qx = x >> 2, qz = z >> 2;
        Map<String, String> before = quarts(r, qx, qz);
        String cherry = "minecraft:cherry_grove";
        OpSpec.BiomeFill fill = new OpSpec.BiomeFill(new Region.Cuboid(box), cherry, false);
        return r.job("biome fill", listener -> r.service.run(r.player, fill, RunOptions.DEFAULT, listener))
                .thenCompose(result -> {
                    String kept = biome(r, qx, 25, qz), below = biome(r, qx, 24, qz), above = biome(r, qx, 26, qz);
                    String beside = biome(r, qx + 1, 25, qz);
                    r.record("biomes: the quart whose centre is in the region keeps its biome",
                            kept.equals(before.get(qx + "," + 25 + "," + qz)) && !kept.equals(cherry)
                                    && below.equals(cherry) && above.equals(cherry) && beside.equals(cherry),
                            "protected quart " + kept + ", below " + below + ", above " + above + ", beside " + beside);
                    return r.undo();
                })
                .thenAccept(result -> {
                    Map<String, String> after = quarts(r, qx, qz);
                    r.record("biomes: the undo puts every quart back", after.equals(before),
                            after.equals(before) ? "all " + after.size() + " quarts as before" : "before " + before
                                    + ", after " + after);
                });
    }

    /** The biome ids of the 2 x 4 x 2 quarts from (qx, 24, qz), keyed "qx,qy,qz". */
    private static Map<String, String> quarts(SelfTestRun r, int qx, int qz) {
        Map<String, String> out = new TreeMap<>();
        for (int dx = 0; dx < 2; dx++) {
            for (int qy = 24; qy < 28; qy++) {
                for (int dz = 0; dz < 2; dz++) out.put((qx + dx) + "," + qy + "," + (qz + dz), biome(r, qx + dx, qy, qz + dz));
            }
        }
        return out;
    }

    private static String biome(SelfTestRun r, int qx, int qy, int qz) {
        return r.level.getNoiseBiome(qx, qy, qz).unwrapKey().map(key -> key.location().toString()).orElse("?");
    }
}
