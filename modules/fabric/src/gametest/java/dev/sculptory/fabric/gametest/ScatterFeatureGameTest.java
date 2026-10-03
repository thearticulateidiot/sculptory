package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.ScatterGameTest.FLOOR_Y;
import static dev.sculptory.fabric.gametest.ScatterGameTest.commit;
import static dev.sculptory.fabric.gametest.ScatterGameTest.floor;
import static dev.sculptory.fabric.gametest.ScatterGameTest.heldPlan;
import static dev.sculptory.fabric.gametest.ScatterGameTest.preview;
import static dev.sculptory.fabric.gametest.ScatterGameTest.refusal;
import static dev.sculptory.fabric.gametest.ScatterGameTest.region;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.generate.SparseUploadException;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.GrownFeature;
import dev.sculptory.core.scatter.Outcome;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.gametest.ScatterGameTest.Reply;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricTile;
import dev.sculptory.fabric.world.FeatureGrower;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.engine.impl.EditMasks;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.engine.impl.ServerScatter;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BeehiveBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.LoggerFactory;

/**
 * Vanilla trees and features in Scatter against a real server world:
 * every catalog entry grows (or fails readably) without touching the world, the preview's grown cells are exactly what
 * the commit writes (grass under trunks turned to dirt, bee nests with their bees), the commit undoes exactly, a tree
 * reaching a protected column or an unloaded chunk is skipped whole and the chunk stays unloaded, the grown-cell cap
 * refuses the preview, re-rolling grows other trees, a one-spot preview (Alt+click) places one, the global mask keeps
 * or skips a tree whole, and dropped encodings are cancelled. Region slots
 * 1180-1199.
 */
public final class ScatterFeatureGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final ScatterSource OAK = new ScatterSource.Feature("minecraft:oak");
    private static final ScatterSource BIRCH = new ScatterSource.Feature("minecraft:birch");
    private static final ScatterSource FANCY_OAK = new ScatterSource.Feature("minecraft:fancy_oak");
    private static final ScatterSource BEE_OAK = new ScatterSource.Feature("minecraft:fancy_oak_bees");
    private static final ScatterSource SPRUCE = new ScatterSource.Feature("minecraft:spruce");

    // ---------------------------------------------------------------- helpers

    /** A preview of {@code sources} (weight 1 each) over {@code area}. */
    static C2S.ScatterPreview request(ScatterArea area, int spacing, ScatterSettings.Density density, long seed,
                                      ScatterSource... sources) {
        List<C2S.ScatterPreview.Variant> variants = new ArrayList<>();
        for (ScatterSource source : sources) variants.add(new C2S.ScatterPreview.Variant(source, 1));
        return new C2S.ScatterPreview(1, area, new C2S.ScatterPreview.Settings(seed, spacing, density, SurfaceMask.ANY,
                ScatterSettings.Fit.DEFAULT), variants, ScatterSettings.Transforms.ALL);
    }

    /** A stone floor at y = 100 over [x0, x0 + w) × [z0, z0 + d), topped with {@code top} over the same columns. */
    static Box floorOf(Harness h, int x0, int z0, int w, int d, String top) {
        Box all = floor(h, x0, z0, w, d);
        paint(h, x0, z0, w, d, top);
        return all;
    }

    static void paint(Harness h, int x0, int z0, int w, int d, String top) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int state = h.state(top);
        for (int x = x0; x < x0 + w; x++) {
            for (int z = z0; z < z0 + d; z++) writer.write(x, FLOOR_Y, z, state, null);
        }
    }

    /** The {@code SCATTER_GENERATED} payload, decoded as the client does. */
    static GeneratedSource grown(Harness h, ScatterService.PlanReady plan) {
        check(plan.generatedPayload() != null, "no grown payload");
        try {
            return SparseUpload.decodePreview(plan.generatedPayload(), h.runtime.states(), Long.MAX_VALUE, 1 << 20);
        } catch (SparseUploadException e) {
            throw new GameTestException("the grown payload does not decode: " + e.getMessage());
        }
    }

    /** Plans synchronously, tick after tick, waiting for payloads encoded off the server thread (a server has 50 ms). */
    static void planNow(ServerScatter<ServerPlayerEntity, ServerWorld> scatter, Reply reply,
                        ServerPlayerEntity player) {
        long until = System.nanoTime() + 60_000_000_000L;
        while (!reply.finished() && System.nanoTime() < until) {
            scatter.tick();
            if (scatter.encoding(player.getUuid())) Thread.onSpinWait();
        }
        check(reply.finished(), "planning did not finish");
    }

    // ---------------------------------------------------------------- tests

    /**
     * Every catalog entry is a configured feature of this world and grows on a suitable floor within a few seeds,
     * inside its span (nothing clipped); an ice spike on grass and an oak on stone fail readably (FEATURE_FAILED,
     * SURVIVAL). Growing never changes the world.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_catalog", tickLimit = LIMIT)
    public void everyCatalogEntryGrowsOrFailsReadably(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1180);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 16, 16, "minecraft:grass_block");
        // Ground under the floor (the test world is void below it): an ice spike's base and mangrove roots stop there.
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        for (int x = x0 - 4; x < x0 + 20; x++) {
            for (int z = z0 - 4; z < z0 + 20; z++) {
                for (int y = FLOOR_Y - 3; y < FLOOR_Y; y++) writer.write(x, y, z, h.state("minecraft:stone"), null);
            }
        }
        check(FeatureGrower.missing(h.world).isEmpty(), "not in this world: " + FeatureGrower.missing(h.world));
        int cx = x0 + 8, cz = z0 + 8;
        Box around = box(x0 - 14, FLOOR_Y - 18, z0 - 14, x0 + 30, FLOOR_Y + 60, z0 + 30);
        List<String> problems = new ArrayList<>();
        TreeMap<String, Integer> cells = new TreeMap<>();
        for (FeatureCatalog.FeatureDef def : FeatureCatalog.ALL) {
            paint(h, x0, z0, 16, 16, floorFor(def.id()));
            WorldSnapshot before = capture(h.world, around);
            FeatureGrower grower = new FeatureGrower(h.world, h.runtime.states(), List.of(def), true);
            ScatterPlanner.Growth growth = null;
            for (long seed = 1; seed <= 12 && (growth == null || growth.grown() == null); seed++) {
                growth = grower.grow(0, cx, FLOOR_Y + 1, cz, seed, ScatterPlanner.GrownView.NONE);
            }
            if (growth.grown() == null) {
                problems.add(def.id() + " did not grow on " + floorFor(def.id()) + ": " + growth.failure());
            } else {
                cells.put(def.id(), growth.grown().size());
                if (grower.lastClipped() > 0) problems.add(def.id() + " clipped " + grower.lastClipped() + " writes");
                Box b = growth.grown().bounds();
                int reach = FeatureGrower.reach(def);
                if (b.min().x() < cx - reach || b.max().x() > cx + reach || b.max().y() > FLOOR_Y + 1
                        + FeatureGrower.above(def)) {
                    problems.add(def.id() + " outside its span: " + b);
                }
            }
            String changed = EditTestSupport.difference(before, capture(h.world, around));
            if (changed != null) problems.add(def.id() + " changed the world: " + changed);
        }
        check(problems.isEmpty(), String.join("; ", problems));
        LoggerFactory.getLogger("sculptory").info("Sculptory: grown cells per catalog entry {}", cells);
        // Readable failures.
        paint(h, x0, z0, 16, 16, "minecraft:grass_block");
        FeatureCatalog.FeatureDef spike = FeatureCatalog.find("minecraft:ice_spike").orElseThrow();
        ScatterPlanner.Growth noSnow = new FeatureGrower(h.world, h.runtime.states(), List.of(spike), true)
                .grow(0, cx, FLOOR_Y + 1, cz, 1L, ScatterPlanner.GrownView.NONE);
        check(noSnow.failure() == Outcome.FEATURE_FAILED, "an ice spike on grass: " + noSnow);
        paint(h, x0, z0, 16, 16, "minecraft:stone");
        FeatureCatalog.FeatureDef oak = FeatureCatalog.find("minecraft:oak").orElseThrow();
        ScatterPlanner.Growth onStone = new FeatureGrower(h.world, h.runtime.states(), List.of(oak), true)
                .grow(0, cx, FLOOR_Y + 1, cz, 1L, ScatterPlanner.GrownView.NONE);
        check(onStone.failure() == Outcome.SURVIVAL, "an oak on stone: " + onStone);
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /** A floor each entry grows on. */
    static String floorFor(String id) {
        return switch (id) {
            case "minecraft:ice_spike" -> "minecraft:snow_block";
            case "minecraft:crimson_fungus" -> "minecraft:crimson_nylium";
            case "minecraft:warped_fungus" -> "minecraft:warped_nylium";
            case "minecraft:huge_red_mushroom", "minecraft:huge_brown_mushroom" -> "minecraft:mycelium";
            case "minecraft:mangrove", "minecraft:tall_mangrove" -> "minecraft:mud";
            default -> "minecraft:grass_block";
        };
    }

    /**
     * How a nest in the world differs from the one the plan holds, or null when it is the same. A nest's block entity
     * ticks: every tick adds one to each bee's {@code ticks_in_hive}. So each bee may have gained between 0 and
     * {@code maxTicks} ticks since the commit wrote it (added to {@code gained[0]}); everything else (the bees, their
     * data, their minimum time, the flower) must be exactly as planned.
     */
    /** Ticks the nests get between the commit and the checks and undo of the commit test. */
    private static final int NEST_TICKS = 6;

    static String nestChange(net.minecraft.nbt.NbtCompound inWorld, net.minecraft.nbt.NbtCompound planned,
                             long maxTicks, long[] gainedTotal) {
        net.minecraft.nbt.NbtCompound seen = inWorld.copy();
        net.minecraft.nbt.NbtList seenBees = seen.getList("bees", net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
        net.minecraft.nbt.NbtList plannedBees = planned.getList("bees", net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
        if (seenBees.size() != plannedBees.size()) {
            return seenBees.size() + " bees, planned " + plannedBees.size();
        }
        for (int b = 0; b < seenBees.size(); b++) {
            net.minecraft.nbt.NbtCompound bee = seenBees.getCompound(b);
            int gained = bee.getInt("ticks_in_hive") - plannedBees.getCompound(b).getInt("ticks_in_hive");
            if (gained < 0 || gained > maxTicks) {
                return "bee " + b + " gained " + gained + " ticks in the nest, at most " + maxTicks + " passed";
            }
            gainedTotal[0] += gained;
            bee.putInt("ticks_in_hive", plannedBees.getCompound(b).getInt("ticks_in_hive"));
        }
        return seen.equals(planned) ? null : seen + " not " + planned;
    }

    /**
     * Preview equals commit: the grown cells the preview streams are exactly what the commit writes, every other cell
     * stays as it was, grass under the trunks becomes dirt, bee nests come with their bees (the block entity the
     * plan holds), and the one history entry undoes exactly, block entities and leaves included.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_commit", tickLimit = LIMIT)
    public void theCommitWritesWhatThePreviewGrewAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1181);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 32, 32, "minecraft:grass_block");
        // Bees stay in their nests at night, so only their time in the nest changes between the commit and the undo.
        long time = h.world.getTimeOfDay();
        h.world.setTimeOfDay(18_000);
        Box area = box(x0 - 12, FLOOR_Y - 2, z0 - 12, x0 + 43, FLOOR_Y + 30, z0 + 43);
        WorldSnapshot before = capture(h.world, area);
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 31), 7,
                new ScatterSettings.Density.Fraction(0.08), 21L, OAK, BIRCH, BEE_OAK));
        planNow(scatter, reply, h.player);
        ScatterService.PlanReady plan = reply.get("preview");
        ScatterPlan held = heldPlan(h, h.player);
        check(plan.placements() >= 6, "only " + plan.placements() + " trees: " + plan.rejectedCounts());
        GeneratedSource grown = grown(h, plan);
        check(java.util.Arrays.equals(plan.generatedPayload(), ServerScatter.grownPayload(held, h.runtime.states())),
                "the payload encoded off the server thread differs from the plan's");
        check(grown.cells() == held.grownCells() && plan.totalCells() == held.grownCells(),
                grown.cells() + " streamed, " + held.grownCells() + " grown, " + plan.totalCells() + " total");
        int nests = 0;
        for (GrownFeature cluster : held.clusters()) nests += cluster.tileCount();
        check(nests > 0, "no bee nest grew");
        RecordingListener[] jobs = new RecordingListener[2];
        long committedAt = h.world.getTime();
        int[] waited = {0};
        jobs[0] = commit(h, h.player, plan.planId());
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                // The nests tick a few times before the checks and the undo, as they would before a player undoes.
                .createAndAdd(() -> check(++waited[0] >= NEST_TICKS, "letting the nests tick"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED && jobs[0].result.skippedConflicts() == 0,
                            "commit " + jobs[0].result);
                    check(jobs[0].result.changed() == held.grownCells(), "changed " + jobs[0].result.changed() + " of "
                            + held.grownCells());
                    // Byte for byte: every streamed cell holds the streamed state; nothing else changed.
                    Set<Long> cells = new HashSet<>();
                    List<String> wrong = new ArrayList<>();
                    grown.forEach((x, y, z, state) -> {
                        cells.add(net.minecraft.util.math.BlockPos.asLong(x, y, z));
                        int now = h.runtime.states().handle(h.world.getBlockState(pos(x, y, z)));
                        if (now != state && wrong.size() < 5) {
                            wrong.add(x + "," + y + "," + z + ": " + h.runtime.states().format(now) + " not "
                                    + h.runtime.states().format(state));
                        }
                    });
                    check(wrong.isEmpty(), "the commit wrote other states than the preview: " + wrong);
                    WorldSnapshot after = capture(h.world, area);
                    for (int y = area.min().y(); y <= area.max().y(); y++) {
                        for (int z = area.min().z(); z <= area.max().z(); z++) {
                            for (int x = area.min().x(); x <= area.max().x(); x++) {
                                if (cells.contains(net.minecraft.util.math.BlockPos.asLong(x, y, z))) continue;
                                check(after.get(x, y, z) == before.get(x, y, z), "the commit changed " + x + "," + y
                                        + "," + z + ", which the preview did not grow");
                            }
                        }
                    }
                    // Grass under the trunks became dirt.
                    int dirt = 0;
                    for (ScatterPlan.Placement p : held.placements()) {
                        BlockState below = h.world.getBlockState(pos(p.anchor().x(), p.anchor().y() - 1, p.anchor().z()));
                        if (below.isOf(Blocks.DIRT)) dirt++;
                    }
                    check(dirt == held.placements().size(), dirt + " of " + held.placements().size()
                            + " trunks stand on dirt");
                    // The nests hold the bees the plan holds.
                    int bees = 0;
                    long[] gained = {0};
                    for (GrownFeature cluster : held.clusters()) {
                        for (int i = 0; i < cluster.size(); i++) {
                            if (cluster.tile(i) == null) continue;
                            BlockEntity entity = h.world.getBlockEntity(pos(cluster.x(i), cluster.y(i), cluster.z(i)));
                            check(entity instanceof BeehiveBlockEntity, "no nest at " + cluster.x(i) + "," + cluster.y(i)
                                    + "," + cluster.z(i));
                            bees += ((BeehiveBlockEntity) entity).getBeeCount();
                            String changed = nestChange(entity.createNbtWithId(h.world.getRegistryManager()),
                                    ((FabricTile) cluster.tile(i)).copyNbt(), h.world.getTime() - committedAt + 1,
                                    gained);
                            check(changed == null, "a nest's block entity changed at " + cluster.x(i) + ","
                                    + cluster.y(i) + "," + cluster.z(i) + ": " + changed);
                        }
                    }
                    check(bees > 0, "the nests are empty");
                    // So the undo below meets nests whose data changed by itself since the commit.
                    check(gained[0] > 0, "no nest ticked since the commit");
                    check(h.history().undoLabels().size() == 1, "one history entry: " + h.history().undoLabels());
                    jobs[1] = new RecordingListener();
                    h.undo(jobs[1]);
                })
                .createAndAdd(() -> check(jobs[1].result != null, "undo running"))
                .createAndAdd(() -> {
                    h.world.setTimeOfDay(time);
                    check(jobs[1].result.outcome() == JobOutcome.COMPLETED && jobs[1].result.skippedConflicts() == 0,
                            "undo " + jobs[1].result);
                    checkSame(before, capture(h.world, area), "after undo");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Protection: while the preview plans, the world border cuts the area at x0 + 16, and every tree whose cells reach
     * past it is skipped whole (PROTECTED). Before the commit the border moves to x0 + 12: the clusters reaching past
     * it are skipped whole at commit time too (reported), the others written.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_protect", tickLimit = LIMIT)
    public void treesReachingProtectedColumnsAreSkippedWhole(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1182);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 32, 16, "minecraft:grass_block");
        WorldBorder border = h.world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        Runnable restore = () -> {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        };
        Reply reply;
        try {
            border.setCenter(x0 + 16 - 100_000, z0);
            border.setSize(200_000);
            // Spacing 7 keeps the oak and birch crowns apart: each tree is a cluster of its own.
            reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 7,
                    new ScatterSettings.Density.Fraction(0.3), 4L, OAK, BIRCH));
            planNow(scatter, reply, h.player);
        } finally {
            restore.run();
        }
        ScatterService.PlanReady plan = reply.get("preview");
        Integer protectedCount = plan.rejectedCounts().get("PROTECTED");
        check(protectedCount != null && protectedCount > 0, "nothing protected: " + plan.rejectedCounts());
        ScatterPlan held = heldPlan(h, h.player);
        check(!held.clusters().isEmpty(), "no trees");
        for (GrownFeature cluster : held.clusters()) {
            check(cluster.bounds().max().x() < x0 + 16, "a tree reaches the protected columns: " + cluster.bounds());
        }
        List<GrownFeature> crossing = new ArrayList<>();
        for (GrownFeature cluster : held.clusters()) {
            if (cluster.bounds().max().x() >= x0 + 12) crossing.add(cluster);
        }
        check(!crossing.isEmpty() && crossing.size() < held.clusters().size(), crossing.size() + " of "
                + held.clusters().size() + " clusters reach past x0 + 12");
        long kept = 0;
        for (GrownFeature cluster : held.clusters()) {
            if (!crossing.contains(cluster)) kept += cluster.size();
        }
        long expected = kept;
        RecordingListener job = new RecordingListener();
        job.onFinished = restore;
        border.setCenter(x0 + 12 - 100_000, z0);
        border.setSize(200_000);
        try {
            h.service.run(h.player, new dev.sculptory.core.edit.OpSpec.ScatterCommit(plan.planId()),
                    RunOptions.DEFAULT, job);
        } catch (EditRejected | RuntimeException e) {
            restore.run();
            throw new GameTestException("commit refused: " + e.getMessage());
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(job.result != null, "commit running"))
                .createAndAdd(() -> {
                    check(job.result.outcome() == JobOutcome.COMPLETED, "commit " + job.result);
                    check(job.result.skippedConflicts() == crossing.size(), "skipped " + job.result.skippedConflicts()
                            + " of " + crossing.size() + " crossing clusters");
                    check(job.result.changed() == expected, "changed " + job.result.changed() + ", expected " + expected);
                    for (GrownFeature cluster : crossing) {
                        for (int i = 0; i < cluster.size(); i++) {
                            BlockState now = h.world.getBlockState(pos(cluster.x(i), cluster.y(i), cluster.z(i)));
                            check(h.runtime.states().handle(now) == cluster.before(i), "a skipped tree was half built");
                        }
                    }
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A tree next to a chunk nobody loaded: its growth reaches the chunk, so it ends as UNLOADED, and the chunk is still
     * not loaded after planning. Trees that stay in loaded chunks are planned.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_unloaded", tickLimit = LIMIT)
    public void anUnloadedNeighbourChunkIsNeverLoaded(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1183);
        int x0 = at[0], z0 = at[1];
        int cx0 = x0 >> 4, cz0 = z0 >> 4;
        Box forced = box(x0, FLOOR_Y, z0, x0 + 15, FLOOR_Y, z0 + 15);
        loadAndForce(h.world, forced);
        // The last loaded chunk to the east, and the unloaded one after it.
        int last = cx0;
        while (last < cx0 + 4 && WorldChecks.isChunkLoaded(h.world, last + 1, cz0)) last++;
        int edge = last;
        check(!WorldChecks.isChunkLoaded(h.world, edge + 1, cz0), "no unloaded chunk east of " + cx0);
        int ex = edge << 4;
        paint(h, ex, z0 + 2, 16, 12, "minecraft:grass_block");
        Reply reply = preview(scatter, h.player, request(region(ex + 6, z0 + 4, ex + 15, z0 + 11), 3,
                new ScatterSettings.Density.Fraction(0.5), 8L, OAK));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    Integer unloaded = plan.rejectedCounts().get("UNLOADED");
                    check(unloaded != null && unloaded > 0, "UNLOADED " + plan.rejectedCounts());
                    check(plan.placements() > 0, "no tree fits: " + plan.rejectedCounts());
                    for (GrownFeature cluster : heldPlan(h, h.player).clusters()) {
                        check(cluster.bounds().max().x() >> 4 <= edge, "a tree in the unloaded chunk: "
                                + cluster.bounds());
                    }
                    check(!WorldChecks.isChunkLoaded(h.world, edge + 1, cz0), "planning loaded chunk " + (edge + 1)
                            + "," + cz0);
                    scatter.shutdown();
                    forceChunks(h.world, forced, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Refusals: a plan whose trees would grow more than {@code scatter.maxFeatureCells} is refused TOO_LARGE and leaves
     * no plan; an id that is not in the catalog is refused INVALID; a player without brush or region may not grow trees.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_refusals", tickLimit = LIMIT)
    public void theGrownCellCapAndTheCatalogRefuse(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1184);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 32, 32, "minecraft:grass_block");
        long cap = h.runtime.config().scatter.maxFeatureCells;
        Reply reply;
        try {
            h.runtime.config().scatter.maxFeatureCells = 120;
            reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 31), 6,
                    new ScatterSettings.Density.Fraction(0.1), 2L, OAK, BIRCH));
            planNow(scatter, reply, h.player);
        } finally {
            h.runtime.config().scatter.maxFeatureCells = cap;
        }
        check(reply.reason == RejectReason.TOO_LARGE && reply.detail.contains("120"), "the cap: " + reply.reason + " "
                + reply.detail);
        check(h.service.scatterPlans().get(h.player.getUuid()).isEmpty(), "a refused preview left a plan");
        check(!h.service.executor().isLocked(h.world, all), "a refused preview held the area");

        EditRejected unknown = refusal(() -> scatter.preview(h.player, request(region(x0, z0, x0 + 7, z0 + 7), 0,
                new ScatterSettings.Density.Count(1), 1L, new ScatterSource.Feature("minecraft:end_spike")),
                new Reply()));
        check(unknown.reason() == RejectReason.INVALID, "an id outside the catalog: " + unknown.reason());

        ServerPlayerEntity scatterOnly = h.addPlayer(false);
        EditTestSupport.grant(scatterOnly, Perm.USE, Perm.SCATTER);
        EditRejected denied = refusal(() -> scatter.preview(scatterOnly, request(region(x0, z0, x0 + 7, z0 + 7), 0,
                new ScatterSettings.Density.Count(1), 1L, OAK), new Reply()));
        check(denied.reason() == RejectReason.NO_PERMISSION && denied.detail().equals(ServerScatter.FEATURES_NEED),
                "trees without brush or region: " + denied.reason() + " " + denied.detail());
        scatter.shutdown();
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /** The same seed grows the same plan; another seed (Re-roll) grows other trees. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_reroll", tickLimit = LIMIT)
    public void reRollingGrowsOtherTrees(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1185);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 32, 32, "minecraft:grass_block");
        ScatterArea area = region(x0, z0, x0 + 31, z0 + 31);
        ScatterSettings.Density density = new ScatterSettings.Density.Fraction(0.08);
        List<ScatterPlan> plans = new ArrayList<>();
        for (long seed : new long[] {5L, 5L, 6L}) {
            Reply reply = preview(scatter, h.player, request(area, 6, density, seed, OAK, SPRUCE));
            planNow(scatter, reply, h.player);
            reply.get("preview " + seed);
            plans.add(heldPlan(h, h.player));
        }
        check(plans.get(0).hash().equals(plans.get(1).hash()), "the same seed grew another plan");
        check(!plans.get(0).hash().equals(plans.get(2).hash()), "re-rolling grew the same plan");
        check(plans.get(0).grownCells() > 0 && plans.get(2).grownCells() > 0, "nothing grew");
        scatter.shutdown();
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * Alt+click: a one-spot preview (a stamp of radius 0, a target count of 1) plans one tree there, and committing it
     * grows exactly that tree.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_one", tickLimit = LIMIT)
    public void aOneSpotPreviewPlacesOneTree(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1186);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 16, 16, "minecraft:grass_block");
        int x = x0 + 8, z = z0 + 8;
        Reply reply = preview(scatter, h.player, request(new ScatterArea.Stamps(List.of(ScatterArea.Stamp.paint(x, z, 0))),
                ScatterSettings.MAX_SPACING, new ScatterSettings.Density.Count(1), 99L, OAK, BIRCH));
        planNow(scatter, reply, h.player);
        ScatterService.PlanReady plan = reply.get("preview");
        check(plan.placements() == 1, plan.placements() + " placements: " + plan.rejectedCounts());
        ScatterPlan held = heldPlan(h, h.player);
        check(held.placements().get(0).anchor().equals(new dev.sculptory.core.BlockPos(x, FLOOR_Y + 1, z)),
                "planned at " + held.placements().get(0).anchor());
        check(held.clusters().size() == 1, held.clusters().size() + " clusters");
        RecordingListener job = commit(h, h.player, plan.planId());
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(job.result != null, "commit running"))
                .createAndAdd(() -> {
                    check(job.result.outcome() == JobOutcome.COMPLETED, "commit " + job.result);
                    check(job.result.changed() == held.grownCells(), "changed " + job.result.changed());
                    Block log = h.world.getBlockState(pos(x, FLOOR_Y + 1, z)).getBlock();
                    check(log == Blocks.OAK_LOG || log == Blocks.BIRCH_LOG, "no trunk at the spot: " + log);
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** The global mask "not inside" the columns x0 + from to x0 + 17 (every height). */
    private static void maskOutColumns(Harness h, int x0, int z0, int from) throws EditRejected {
        Region strip = new Region.Cuboid(box(x0 + from, FLOOR_Y - 8, z0 - 8, x0 + 17, FLOOR_Y + 40, z0 + 23));
        EditMasks.set(h.player.getUuid(), new EditMask(List.of(new MaskEntry(new MaskRule.Inside(strip), true)), false),
                region -> region, h.runtime.states());
    }

    /**
     * Under the global mask a tree is planned and committed whole or not at all, as with protected columns: a tree
     * reaching a masked cell is left out of the preview (MASKED), and when the mask grows after the preview the commit
     * skips each tree that now reaches a masked cell whole (counted as skipped) and writes the others exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_mask", tickLimit = LIMIT)
    public void underTheMaskATreeIsPlannedAndCommittedWholeOrNotAtAll(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1189);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 32, 16, "minecraft:grass_block");
        // Where the trees land depends on the seed and on where the test runs, so the setup takes the first seed whose
        // preview masks a tree and has trees both reaching and clear of the columns the mask grows to (x0 + 10 ..
        // x0 + 13); with a single seed some test positions had every tree on one side.
        ScatterService.PlanReady plan = null;
        ScatterPlan held = null;
        List<GrownFeature> crossing = new ArrayList<>();
        long kept = 0;
        try {
            maskOutColumns(h, x0, z0, 14);
            for (long seed = 4; seed < 24; seed++) {
                // Spacing 7 keeps the oak and birch crowns apart: each tree is a cluster of its own.
                Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 7,
                        new ScatterSettings.Density.Fraction(0.3), seed, OAK, BIRCH));
                planNow(scatter, reply, h.player);
                plan = reply.get("preview " + seed);
                held = heldPlan(h, h.player);
                crossing = new ArrayList<>();
                kept = 0;
                for (GrownFeature cluster : held.clusters()) {
                    boolean reaches = false;
                    for (int i = 0; i < cluster.size(); i++) {
                        reaches |= cluster.x(i) >= x0 + 10 && cluster.x(i) <= x0 + 17;
                    }
                    if (reaches) {
                        crossing.add(cluster);
                    } else {
                        kept += cluster.size();
                    }
                }
                Integer masked = plan.rejectedCounts().get("MASKED");
                if (masked != null && masked > 0 && !crossing.isEmpty() && crossing.size() < held.clusters().size()) {
                    break;
                }
            }
        } catch (EditRejected e) {
            EditMasks.reset(h.player.getUuid());
            throw new GameTestException("mask refused: " + e.getMessage());
        }
        Integer maskedCount = plan.rejectedCounts().get("MASKED");
        check(maskedCount != null && maskedCount > 0, "nothing masked: " + plan.rejectedCounts());
        for (GrownFeature cluster : held.clusters()) {
            for (int i = 0; i < cluster.size(); i++) {
                check(cluster.x(i) < x0 + 14 || cluster.x(i) > x0 + 17, "a planned tree reaches the masked columns");
            }
        }
        // The mask grows after the preview: trees reaching x0 + 10 .. x0 + 13 are now masked.
        check(!crossing.isEmpty() && crossing.size() < held.clusters().size(), crossing.size() + " of "
                + held.clusters().size() + " clusters reach the grown mask (seeds 4 to 23)");
        List<GrownFeature> masked = crossing;
        long expected = kept;
        RecordingListener job;
        try {
            maskOutColumns(h, x0, z0, 10);
            job = commit(h, h.player, plan.planId());
        } catch (EditRejected | RuntimeException e) {
            EditMasks.reset(h.player.getUuid());
            throw new GameTestException("commit refused: " + e.getMessage());
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(job.result != null, "commit running"))
                .createAndAdd(() -> {
                    EditMasks.reset(h.player.getUuid());
                    check(job.result.outcome() == JobOutcome.COMPLETED, "commit " + job.result);
                    check(job.result.skippedConflicts() == masked.size(), "skipped " + job.result.skippedConflicts()
                            + " of " + masked.size() + " masked trees");
                    check(job.result.changed() == expected, "changed " + job.result.changed() + ", expected " + expected);
                    for (GrownFeature cluster : masked) {
                        for (int i = 0; i < cluster.size(); i++) {
                            BlockState now = h.world.getBlockState(pos(cluster.x(i), cluster.y(i), cluster.z(i)));
                            check(h.runtime.states().handle(now) == cluster.before(i), "a masked tree was half built");
                        }
                    }
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A mask grown after the preview to rule out every placement: the commit is refused with a readable reason, the
     * world is unchanged and the plan stays held (a narrower mask, or no mask, can still commit it).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_all_masked", tickLimit = LIMIT)
    public void aMaskRulingOutEveryPlacementRefusesTheCommitReadably(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1190);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 32, 16, "minecraft:grass_block");
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 7,
                new ScatterSettings.Density.Fraction(0.3), 4L, OAK, BIRCH));
        planNow(scatter, reply, h.player);
        ScatterService.PlanReady plan = reply.get("preview");
        check(plan.placements() > 0, "no trees: " + plan.rejectedCounts());
        WorldSnapshot before = capture(h.world, all);
        EditRejected refused;
        try {
            Region everything = new Region.Cuboid(box(x0 - 16, FLOOR_Y - 8, z0 - 16, x0 + 47, FLOOR_Y + 40, z0 + 31));
            EditMasks.set(h.player.getUuid(), new EditMask(List.of(new MaskEntry(new MaskRule.Inside(everything), true)),
                    false), region -> region, h.runtime.states());
            refused = refusal(() -> h.service.run(h.player, new dev.sculptory.core.edit.OpSpec.ScatterCommit(
                    plan.planId()), RunOptions.DEFAULT, new RecordingListener()));
        } catch (EditRejected e) {
            throw new GameTestException("mask refused: " + e.getMessage());
        } finally {
            EditMasks.reset(h.player.getUuid());
        }
        check(refused.reason() == RejectReason.INVALID
                && EngineEditService.MASK_RULES_OUT_SCATTER.equals(refused.detail()),
                "refused " + refused.reason() + ": " + refused.detail());
        check(Arrays.equals(before.states, capture(h.world, all).states), "a refused commit changed the world");
        check(h.service.scatterPlans().get(h.player.getUuid()).isPresent(), "the refusal dropped the plan");
        scatter.shutdown();
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /** Ticks until the player's finished plan waits on the encoder (or fails the test). */
    private static void planUntilEncoding(ServerScatter<ServerPlayerEntity, ServerWorld> scatter, Reply reply,
                                          ServerPlayerEntity player) {
        for (int i = 0; i < 100_000 && !scatter.encoding(player.getUuid()) && !reply.finished(); i++) scatter.tick();
        check(scatter.encoding(player.getUuid()), "no encoding pending: " + reply.reason + " " + reply.detail);
    }

    /**
     * The encoder is the service's own: no thread until a grown plan needs one, stopped by {@code shutdown} (a new
     * service starts its own). A superseded preview, a player leaving and the shutdown each take their encoding that has
     * not started off the queue, so it never runs and its plan is not held.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_encoder", tickLimit = LIMIT)
    public void droppedEncodingsAreCancelledAndShutdownStopsTheEncoder(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1188);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 32, 32, "minecraft:grass_block");
        ScatterArea area = region(x0, z0, x0 + 31, z0 + 31);
        ScatterSettings.Density density = new ScatterSettings.Density.Fraction(0.08);
        check(!scatter.encoderRunning(), "an encoder before any grown plan");
        CountDownLatch gate = new CountDownLatch(1);
        try {
            scatter.holdEncoder(gate);
            long until = System.nanoTime() + 10_000_000_000L;
            while (scatter.encodingsQueued() > 0 && System.nanoTime() < until) Thread.onSpinWait();
            check(scatter.encoderRunning() && scatter.encodingsQueued() == 0, "the encoder is not held");
            // Superseded.
            Reply first = preview(scatter, h.player, request(area, 6, density, 5L, OAK));
            planUntilEncoding(scatter, first, h.player);
            check(scatter.encodingsQueued() == 1, scatter.encodingsQueued() + " queued");
            Reply second = preview(scatter, h.player, request(area, 6, density, 6L, OAK));
            check(first.superseded && first.calls == 1, "the first preview was not answered superseded");
            check(scatter.encodingsQueued() == 0, "the superseded encoding is still queued");
            // The player leaves.
            planUntilEncoding(scatter, second, h.player);
            check(scatter.encodingsQueued() == 1, scatter.encodingsQueued() + " queued after the second");
            scatter.playerLeft(h.player.getUuid());
            check(scatter.encodingsQueued() == 0 && !scatter.encoding(h.player.getUuid()), "leaving kept the encoding");
            check(!second.finished(), "a player who left was answered");
            // The server stops.
            Reply third = preview(scatter, h.player, request(area, 6, density, 7L, OAK));
            planUntilEncoding(scatter, third, h.player);
            scatter.shutdown();
            check(scatter.encodingsQueued() == 0 && !scatter.encoderRunning(), "shutdown left the encoder running");
            check(!third.finished(), "a shutdown preview was answered");
        } finally {
            gate.countDown();
        }
        // A new service (a new server, the next GameTest) encodes on a fresh thread of its own.
        var next = new ServerScatter<>(h.service);
        check(!next.encoderRunning(), "the new service shares an encoder");
        Reply fresh = preview(next, h.player, request(area, 6, density, 8L, OAK));
        planNow(next, fresh, h.player);
        check(fresh.get("fresh preview").placements() > 0, "nothing planned");
        check(next.encoderRunning(), "the new service did not start its encoder");
        next.shutdown();
        check(!next.encoderRunning(), "the new service's encoder kept running");
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * Plans 200 trees tick by tick: {ticks, the longest tick, the 95th-percentile tick, the last tick (the answer, once
     * the payloads were encoded off the server thread), the total}, in nanoseconds.
     */
    private static long[] plan200(ServerScatter<ServerPlayerEntity, ServerWorld> scatter, ServerPlayerEntity player,
                                  ScatterArea area, long seed) {
        Reply reply = preview(scatter, player, request(area, 6, new ScatterSettings.Density.Count(200), seed, OAK, BIRCH,
                SPRUCE, FANCY_OAK));
        List<Long> took = new ArrayList<>();
        long last = 0, total = 0;
        while (!reply.finished() && took.size() < 10_000) {
            // Server ticks are 50 ms apart: the payloads encoded off the server thread are in by the next one.
            long waitUntil = System.nanoTime() + 10_000_000_000L;
            while (scatter.encoding(player.getUuid()) && !scatter.encoded(player.getUuid())
                    && System.nanoTime() < waitUntil) {
                Thread.onSpinWait();
            }
            long start = System.nanoTime();
            scatter.tick();
            long tick = System.nanoTime() - start;
            if (reply.finished()) last = tick;
            took.add(tick);
            total += tick;
        }
        ScatterService.PlanReady plan = reply.get("preview " + seed);
        check(plan.placements() == 200, plan.placements() + " trees: " + plan.rejectedCounts());
        List<Long> sorted = new ArrayList<>(took);
        sorted.sort(null);
        long longest = sorted.get(sorted.size() - 1);
        long p95 = sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95) - 1));
        return new long[] {took.size(), longest, p95, last, total};
    }

    /**
     * The server cost of a 200-tree preview, planned tick by tick with the scatter lane's budget: logged (ticks, the
     * longest tick, the 95th percentile, the last, the total; cold and warm), and bounded loosely so a regression shows.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_features_cost", tickLimit = LIMIT)
    public void twoHundredTreesPlanWithinTheTickBudget(TestContext context) {
        Harness h = new Harness(context);
        var scatter = new ServerScatter<>(h.service);
        int[] at = regionCorner(context, 1187);
        int x0 = at[0], z0 = at[1];
        Box all = floorOf(h, x0, z0, 112, 112, "minecraft:grass_block");
        ScatterArea area = region(x0, z0, x0 + 111, z0 + 111);
        // The first preview runs cold (the JIT has not compiled vanilla's feature code or the plan's finishing yet); the
        // fourth one is measured warm.
        long[] cold = plan200(scatter, h.player, area, 2L);
        plan200(scatter, h.player, area, 4L);
        plan200(scatter, h.player, area, 5L);
        long[] warm = plan200(scatter, h.player, area, 3L);
        LoggerFactory.getLogger("sculptory").info("Sculptory: a 200-tree preview ({} grown cells) planned warm in "
                + "{} ticks, max {} ms, p95 {} ms, last (answering; payloads encoded off the server thread) {} ms, {} ms "
                + "in all; cold: {} ticks, max {} ms, p95 {} ms, last {} ms, {} ms in all",
                heldPlan(h, h.player).grownCells(), warm[0], ms(warm[1]), ms(warm[2]), ms(warm[3]), ms(warm[4]),
                cold[0], ms(cold[1]), ms(cold[2]), ms(cold[3]), ms(cold[4]));
        // Loose bound (a shared build machine): the aim is under 20 ms a tick.
        check(warm[1] < 40_000_000L, "a planning tick took " + warm[1] / 1_000_000 + " ms");
        scatter.shutdown();
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    private static String ms(long nanos) {
        return String.format("%.2f", nanos / 1e6);
    }
}
