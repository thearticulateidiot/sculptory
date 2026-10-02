package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.ScatterGameTest.FLOOR_Y;
import static dev.sculptory.fabric.gametest.ScatterGameTest.commit;
import static dev.sculptory.fabric.gametest.ScatterGameTest.find;
import static dev.sculptory.fabric.gametest.ScatterGameTest.floor;
import static dev.sculptory.fabric.gametest.ScatterGameTest.heldPlan;
import static dev.sculptory.fabric.gametest.ScatterGameTest.libraryFile;
import static dev.sculptory.fabric.gametest.ScatterGameTest.preview;
import static dev.sculptory.fabric.gametest.ScatterGameTest.refusal;
import static dev.sculptory.fabric.gametest.ScatterGameTest.region;
import static dev.sculptory.fabric.gametest.ScatterGameTest.slab;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.schem.AssetInfo;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.gametest.ScatterGameTest.Reply;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.engine.impl.AssetCache;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * Block variants in scatter (plants without schematics) against a real server world: vanilla's survival rule decides
 * where they may go (on by default), a double-tall plant is written whole, one commit is one exact undo, blocks and
 * library assets mix, a block the server cannot place is refused {@code INVALID} with the reason, nothing that
 * carries water is placed on land, standing two-block plants are never cut in half, and blocks need {@code brush} or
 * {@code region}.
 */
public final class ScatterBlocksGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    private static final ScatterSource POPPY = new ScatterSource.Block("minecraft:poppy");
    private static final ScatterSource TALL_GRASS = new ScatterSource.Block("minecraft:tall_grass[half=lower]");

    /** A preview of {@code sources} (weight 1 each) over every column of {@code area}. */
    static C2S.ScatterPreview request(ScatterArea area, int spacing, long seed, ScatterSettings.Fit fit,
                                      ScatterSource... sources) {
        List<C2S.ScatterPreview.Variant> variants = new ArrayList<>();
        for (ScatterSource source : sources) variants.add(new C2S.ScatterPreview.Variant(source, 1));
        return new C2S.ScatterPreview(1, area, new C2S.ScatterPreview.Settings(seed, spacing,
                new ScatterSettings.Density.Fraction(1), SurfaceMask.ANY, fit), variants, ScatterSettings.Transforms.ALL);
    }

    /** Grass blocks over the west half ([x0, x0 + 16)) of a 32-wide stone floor; the east half stays stone. */
    static void grassWestHalf(Harness h, int x0, int z0, int d) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int grass = h.state("minecraft:grass_block[snowy=false]");
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + d; z++) writer.write(x, FLOOR_Y, z, grass, null);
        }
    }

    static int count(Harness h, Box box, net.minecraft.block.Block block) {
        return find(h.world, box, FLOOR_Y + 1, block).size();
    }

    /**
     * With survival on (the default), poppies and tall grass land only on the grass half: the stone half's columns end
     * as SURVIVAL. Every tall grass is written whole (lower on the grass, upper above). The commit is one history
     * entry and its undo restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_blocks_survive", tickLimit = LIMIT)
    public void scatterBlocksOnlyWhereTheySurvive(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 110);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 16);
        grassWestHalf(h, x0, z0, 16);
        Box area = box(x0 - 4, FLOOR_Y - 1, z0 - 4, x0 + 35, FLOOR_Y + 6, z0 + 19);
        WorldSnapshot before = capture(h.world, area);
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 2, 11L,
                ScatterSettings.Fit.DEFAULT, POPPY, TALL_GRASS));
        RecordingListener[] jobs = new RecordingListener[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    check(plan.placements() > 20, "only " + plan.placements() + " placements");
                    Integer survival = plan.rejectedCounts().get("SURVIVAL");
                    check(survival != null && survival > 20, "the stone half not refused: " + plan.rejectedCounts());
                    for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
                        check(p.anchor().x() < x0 + 16, "a plant planned on stone at " + p.anchor());
                        check(p.anchor().y() == FLOOR_Y + 1, "a plant not on the floor: " + p.anchor());
                    }
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    Box stoneHalf = box(x0 + 16, FLOOR_Y, z0, x0 + 31, FLOOR_Y, z0 + 15);
                    check(count(h, stoneHalf, Blocks.POPPY) == 0 && count(h, stoneHalf, Blocks.TALL_GRASS) == 0,
                            "plants on the stone half");
                    List<net.minecraft.util.math.BlockPos> poppies = find(h.world, all, FLOOR_Y + 1, Blocks.POPPY);
                    List<net.minecraft.util.math.BlockPos> tall = find(h.world, all, FLOOR_Y + 1, Blocks.TALL_GRASS);
                    check(!poppies.isEmpty() && !tall.isEmpty(), poppies.size() + " poppies, " + tall.size()
                            + " tall grass");
                    check(poppies.size() + tall.size() == reply.plan.placements(), poppies.size() + " + " + tall.size()
                            + " plants for " + reply.plan.placements() + " placements");
                    for (net.minecraft.util.math.BlockPos lower : tall) {
                        BlockState low = h.world.getBlockState(lower);
                        BlockState up = h.world.getBlockState(lower.up());
                        check(low.get(TallPlantBlock.HALF) == DoubleBlockHalf.LOWER, "not a lower half at " + lower);
                        check(up.isOf(Blocks.TALL_GRASS) && up.get(TallPlantBlock.HALF) == DoubleBlockHalf.UPPER,
                                "no upper half above " + lower.toShortString() + ": " + up);
                    }
                    check(find(h.world, all, FLOOR_Y + 2, Blocks.TALL_GRASS).size() == tall.size(),
                            "an upper half without its lower half");
                    check(jobs[0].result.changed() == poppies.size() + 2L * tall.size(),
                            "changed " + jobs[0].result.changed());
                    check(h.history().undoLabels().size() == 1, "one history entry: " + h.history().undoLabels());
                    jobs[1] = new RecordingListener();
                    h.undo(jobs[1]);
                })
                .createAndAdd(() -> check(jobs[1].result != null, "undo running"))
                .createAndAdd(() -> {
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
     * With survival off, poppies land on stone too; a block with a block entity (a chest) is placed with default
     * contents, turned by its placement.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_blocks_anywhere", tickLimit = LIMIT)
    public void scatterBlocksAnywhereWithSurvivalOff(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 111);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 16, 16);
        ScatterSource chest = new ScatterSource.Block("minecraft:chest[facing=north,type=single,waterlogged=false]");
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 15, z0 + 15), 2, 5L,
                ScatterSettings.Fit.DEFAULT.withSurvive(false), POPPY, chest));
        RecordingListener[] jobs = new RecordingListener[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    check(plan.placements() > 10, "only " + plan.placements() + " placements");
                    check(!plan.rejectedCounts().containsKey("SURVIVAL"), "survival asked: " + plan.rejectedCounts());
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    List<net.minecraft.util.math.BlockPos> poppies = find(h.world, all, FLOOR_Y + 1, Blocks.POPPY);
                    List<net.minecraft.util.math.BlockPos> chests = find(h.world, all, FLOOR_Y + 1, Blocks.CHEST);
                    check(!poppies.isEmpty(), "no poppy on the stone");
                    check(!chests.isEmpty(), "no chest");
                    check(poppies.size() + chests.size() == reply.plan.placements(), poppies.size() + " + "
                            + chests.size() + " blocks for " + reply.plan.placements() + " placements");
                    java.util.Set<net.minecraft.util.math.Direction> facings = new java.util.HashSet<>();
                    for (net.minecraft.util.math.BlockPos p : chests) {
                        check(h.world.getBlockEntity(p) instanceof ChestBlockEntity entity && entity.isEmpty(),
                                "no empty chest block entity at " + p.toShortString());
                        facings.add(h.world.getBlockState(p).get(net.minecraft.block.ChestBlock.FACING));
                    }
                    check(chests.size() < 4 || facings.size() > 1, "the chests never turned: " + facings);
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A block variant and a library asset in one mix: survival holds the poppies to the grass half while the asset (a
     * gold slab, which survival does not concern) lands on both halves.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_blocks_mixed", tickLimit = LIMIT)
    public void scatterMixesBlocksAndAssets(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 112);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 16);
        grassWestHalf(h, x0, z0, 16);
        String hash = Sha256.digest(new byte[] {7, 7}).hex();
        h.service.assets().put(new AssetCache.Asset(hash, libraryFile("rocks/gold.schem"), slab(h, "minecraft:gold_block"),
                new AssetInfo(List.of(), new BlockPos(1, 0, 1), List.of(0), 1)));
        ScatterSource gold = new ScatterSource.Held(new SourceRef.Asset(hash));
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 3, 21L,
                ScatterSettings.Fit.DEFAULT, POPPY, gold));
        RecordingListener[] jobs = new RecordingListener[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    check(plan.placements() > 10, "only " + plan.placements() + " placements");
                    int slabsOnStone = 0;
                    for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
                        if (p.variant() == 0) check(p.anchor().x() < x0 + 16, "a poppy planned on stone at " + p.anchor());
                        else if (p.anchor().x() > x0 + 17) slabsOnStone++;
                    }
                    check(slabsOnStone > 0, "no gold slab planned on the stone");
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    Box grassHalf = box(x0, FLOOR_Y, z0, x0 + 15, FLOOR_Y, z0 + 15);
                    Box stoneHalf = box(x0 + 16, FLOOR_Y, z0, x0 + 31, FLOOR_Y, z0 + 15);
                    check(count(h, grassHalf, Blocks.POPPY) > 0, "no poppy on the grass");
                    check(count(h, stoneHalf, Blocks.POPPY) == 0, "a poppy on the stone");
                    check(count(h, stoneHalf, Blocks.GOLD_BLOCK) > 0, "no gold slab on the stone");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A block the server cannot place is refused {@code INVALID} before anything is held, and the reason says why:
     * malformed state text, an unknown block, properties it does not have, air, a fluid.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_blocks_invalid", tickLimit = LIMIT)
    public void scatterRefusesBlocksItCannotPlace(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 113);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 16, 16);
        ScatterArea area = region(x0, z0, x0 + 15, z0 + 15);
        String[][] cases = {
                {"minecraft:poppy[", "malformed block state"},
                {"minecraft:not_a_block", "unknown block minecraft:not_a_block"},
                {"minecraft:poppy[age=3]", "invalid properties for minecraft:poppy"},
                {"minecraft:air", "air is not a scatter variant"},
                {"minecraft:water", "minecraft:water is a fluid, not a scatter variant"},
                {"minecraft:lava[level=0]", "minecraft:lava is a fluid, not a scatter variant"},
        };
        for (String[] c : cases) {
            EditRejected refused = refusal(() -> scatter.preview(h.player, request(area, 2, 1L,
                    ScatterSettings.Fit.DEFAULT, POPPY, new ScatterSource.Block(c[0])), new Reply()));
            check(refused.reason() == RejectReason.INVALID, c[0] + " refused " + refused.reason());
            check(refused.getMessage() != null && refused.getMessage().contains(c[1]),
                    c[0] + " refused saying " + refused.getMessage());
        }
        check(!h.service.executor().isLocked(h.world, all), "a refusal held the area");
        check(h.service.scatterPlans().get(h.player.getUuid()).isEmpty(), "a refusal left a plan");
        check(h.world.getBlockState(pos(x0, FLOOR_Y + 1, z0)).isAir(), "a refusal wrote something");
        scatter.shutdown();
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * Nothing that carries water is scattered onto land: water plants and waterlogged picks (seagrass, kelp, tall
     * seagrass, a dead coral fan by default, wet sea pickles) over a dry floor make no placement (WATER), with
     * survival on or off; a bubble column is refused {@code INVALID} ("carries water"); the same blocks picked dry are
     * placed dry: no water appears, then or a second later.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_blocks_water", tickLimit = LIMIT)
    public void scatterPlacesNoWater(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 114);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 16, 16);
        ScatterArea area = region(x0, z0, x0 + 15, z0 + 15);
        EditRejected bubbles = refusal(() -> scatter.preview(h.player, request(area, 2, 1L,
                ScatterSettings.Fit.DEFAULT, new ScatterSource.Block("minecraft:bubble_column")), new Reply()));
        check(bubbles.reason() == RejectReason.INVALID, "a bubble column refused " + bubbles.reason());
        check(bubbles.getMessage() != null && bubbles.getMessage().contains("carries water"),
                "a bubble column refused saying " + bubbles.getMessage());
        ScatterSource[] wet = {new ScatterSource.Block("minecraft:seagrass"), new ScatterSource.Block("minecraft:kelp"),
                new ScatterSource.Block("minecraft:kelp_plant"), new ScatterSource.Block("minecraft:tall_seagrass[half=lower]"),
                new ScatterSource.Block("minecraft:dead_tube_coral_fan"),
                new ScatterSource.Block("minecraft:sea_pickle[pickles=2,waterlogged=true]")};
        for (ScatterSettings.Fit fit : List.of(ScatterSettings.Fit.DEFAULT, ScatterSettings.Fit.DEFAULT.withSurvive(false))) {
            Reply dry = preview(scatter, h.player, request(area, 0, 2L, fit, wet));
            ScatterGameTest.planNow(scatter, dry);
            ScatterService.PlanReady none = dry.get("water plants over land");
            check(none.placements() == 0, none.placements() + " water plants planned on land");
            Integer water = none.rejectedCounts().get("WATER");
            check(water != null && water == 256, "not refused as WATER: " + none.rejectedCounts());
        }
        ScatterSource fan = new ScatterSource.Block("minecraft:dead_tube_coral_fan[waterlogged=false]");
        ScatterSource pickles = new ScatterSource.Block("minecraft:sea_pickle[pickles=2,waterlogged=false]");
        Reply reply = preview(scatter, h.player, request(area, 2, 3L, ScatterSettings.Fit.DEFAULT, fan, pickles));
        RecordingListener[] jobs = new RecordingListener[1];
        int[] waited = {0};
        Runnable noWater = () -> {
            for (int x = x0 - 1; x <= x0 + 16; x++) {
                for (int z = z0 - 1; z <= z0 + 16; z++) {
                    for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 2; y++) {
                        check(h.world.getFluidState(pos(x, y, z)).isEmpty(), "water at " + x + "," + y + "," + z);
                    }
                }
            }
        };
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    check(plan.placements() > 10, "only " + plan.placements() + " placements");
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    List<net.minecraft.util.math.BlockPos> fans = find(h.world, all, FLOOR_Y + 1,
                            Blocks.DEAD_TUBE_CORAL_FAN);
                    List<net.minecraft.util.math.BlockPos> seaPickles = find(h.world, all, FLOOR_Y + 1, Blocks.SEA_PICKLE);
                    check(!fans.isEmpty() && !seaPickles.isEmpty(), fans.size() + " fans, " + seaPickles.size()
                            + " sea pickles");
                    check(fans.size() + seaPickles.size() == reply.plan.placements(), "blocks for placements");
                    for (net.minecraft.util.math.BlockPos p : fans) {
                        check(!h.world.getBlockState(p).get(Properties.WATERLOGGED), "a wet fan at " + p.toShortString());
                    }
                    for (net.minecraft.util.math.BlockPos p : seaPickles) {
                        BlockState state = h.world.getBlockState(p);
                        check(!state.get(Properties.WATERLOGGED) && state.get(Properties.PICKLES) == 2,
                                "sea pickles at " + p.toShortString() + ": " + state);
                    }
                    noWater.run();
                })
                .createAndAdd(() -> {
                    check(++waited[0] >= 20, "waiting a second for water to show");
                    noWater.run();
                })
                .createAndAdd(() -> {
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Plain blocks as variants need {@code brush} or {@code region} besides {@code scatter}: a scatter-only player is
     * refused {@code NO_PERMISSION} (and may still scatter a clipboard); with {@code region} the preview plans; the
     * commit asks again, so losing both refuses it (the plan stays), and with {@code brush} it runs.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_blocks_perm", tickLimit = LIMIT)
    public void scatterBlockVariantsNeedBrushOrRegion(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.SCATTER, Perm.CLIPBOARD);
        int[] at = regionCorner(context, 115);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 16, 16);
        grassWestHalf(h, x0, z0, 16);
        ScatterArea area = region(x0, z0, x0 + 15, z0 + 15);
        EditRejected refused = refusal(() -> scatter.preview(builder, request(area, 2, 1L, ScatterSettings.Fit.DEFAULT,
                POPPY), new Reply()));
        check(refused.reason() == RejectReason.NO_PERMISSION, "a scatter-only player's blocks: " + refused.reason());
        check(refused.getMessage() != null && refused.getMessage().contains(ServerScatter.BLOCK_VARIANTS_NEED),
                "refused saying " + refused.getMessage());
        SourceRef gold = ScatterGameTest.block(h, builder, "minecraft:gold_block");
        Reply clipboard = preview(scatter, builder, ScatterGameTest.request(region(x0, z0, x0 + 15, z0 + 15), 3, 1L,
                gold));
        ScatterGameTest.planNow(scatter, clipboard);
        clipboard.get("a scatter-only player's clipboard preview");

        EditTestSupport.grant(builder, Perm.USE, Perm.SCATTER, Perm.REGION);
        Reply blocks = preview(scatter, builder, request(area, 2, 1L, ScatterSettings.Fit.DEFAULT, POPPY));
        ScatterGameTest.planNow(scatter, blocks);
        ScatterService.PlanReady plan = blocks.get("a block preview with region");
        check(plan.placements() > 5, "only " + plan.placements() + " placements");
        EditTestSupport.grant(builder, Perm.USE, Perm.SCATTER);
        EditRejected late = refusal(() -> h.service.run(builder, new OpSpec.ScatterCommit(plan.planId()),
                RunOptions.DEFAULT, null));
        check(late.reason() == RejectReason.NO_PERMISSION, "a commit after losing region: " + late.reason());
        check(h.service.scatterPlans().get(builder.getUuid()).isPresent(), "the refusal dropped the plan");
        EditTestSupport.grant(builder, Perm.USE, Perm.SCATTER, Perm.BRUSH);
        RecordingListener job = commit(h, builder, plan.planId());
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(job.result != null, "commit running"))
                .createAndAdd(() -> {
                    check(job.result.outcome() == JobOutcome.COMPLETED, "commit " + job.result);
                    check(find(h.world, all, FLOOR_Y + 1, Blocks.POPPY).size() == plan.placements(), "poppies missing");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Standing tall grass and sunflowers are never cut in half: poppies scattered over a meadow of them go only where
     * no two-block plant stands, and every plant keeps both halves.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_blocks_meadow", tickLimit = LIMIT)
    public void scatterNeverTakesHalfATallPlant(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 116);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 16, 16);
        grassWestHalf(h, x0, z0, 16);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int plants = 0;
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                if (((x + z) & 1) != 0) continue;
                String block = (((x >> 1) + z) & 1) == 0 ? "minecraft:tall_grass" : "minecraft:sunflower";
                writer.write(x, FLOOR_Y + 1, z, h.state(block + "[half=lower]"), null);
                writer.write(x, FLOOR_Y + 2, z, h.state(block + "[half=upper]"), null);
                plants++;
            }
        }
        int standing = plants;
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 15, z0 + 15), 0, 4L,
                ScatterSettings.Fit.DEFAULT, POPPY));
        RecordingListener[] jobs = new RecordingListener[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    Integer collisions = plan.rejectedCounts().get("COLLISION");
                    check(collisions != null && collisions == standing, "tall plants not refused: "
                            + plan.rejectedCounts());
                    check(plan.placements() > 0, "no poppy between the tall plants");
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    int whole = 0;
                    for (int x = x0; x < x0 + 16; x++) {
                        for (int z = z0; z < z0 + 16; z++) {
                            BlockState lower = h.world.getBlockState(pos(x, FLOOR_Y + 1, z));
                            BlockState upper = h.world.getBlockState(pos(x, FLOOR_Y + 2, z));
                            if (!(lower.getBlock() instanceof TallPlantBlock)) {
                                check(!(upper.getBlock() instanceof TallPlantBlock), "a floating upper half at " + x
                                        + "," + z);
                                continue;
                            }
                            check(lower.get(TallPlantBlock.HALF) == DoubleBlockHalf.LOWER && upper.isOf(lower.getBlock())
                                    && upper.get(TallPlantBlock.HALF) == DoubleBlockHalf.UPPER, "a half plant at " + x
                                    + "," + z + ": " + lower + " / " + upper);
                            whole++;
                        }
                    }
                    check(whole == standing, whole + " whole plants of " + standing);
                    check(find(h.world, all, FLOOR_Y + 1, Blocks.POPPY).size() == reply.plan.placements(),
                            "poppies missing");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Cells that hold water without a fluid flag stay: poppies scattered over a dry meadow and a walled riverbed of
     * water, seagrass and kelp (fluids not allowed) go only on the meadow, and seagrass grown on the meadow where a
     * poppy was planned, before the commit, is left alone (that placement is skipped). The riverbed is untouched.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_blocks_riverbed", tickLimit = LIMIT)
    public void scatterLeavesSeagrassUnderWater(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 117);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 32, 16);
        grassWestHalf(h, x0, z0, 16);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt");
        int water = h.state("minecraft:water[level=0]"), seagrass = h.state("minecraft:seagrass");
        int kelp = h.state("minecraft:kelp[age=25]"); // fully grown: it does not grow into the snapshot
        int plants = 0;
        for (int x = x0 + 16; x < x0 + 32; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                boolean wall = x == x0 + 16 || x == x0 + 31 || z == z0 || z == z0 + 15;
                if (wall) {
                    writer.write(x, FLOOR_Y + 1, z, stone, null);
                    writer.write(x, FLOOR_Y + 2, z, stone, null);
                    continue;
                }
                writer.write(x, FLOOR_Y, z, dirt, null);
                int bottom = switch ((x + z) & 3) {
                    case 0 -> seagrass;
                    case 2 -> kelp;
                    default -> water;
                };
                if (bottom != water) plants++;
                writer.write(x, FLOOR_Y + 1, z, bottom, null);
                writer.write(x, FLOOR_Y + 2, z, water, null);
            }
        }
        Box riverbed = box(x0 + 16, FLOOR_Y, z0, x0 + 31, FLOOR_Y + 3, z0 + 15);
        WorldSnapshot before = capture(h.world, riverbed);
        int planted = plants;
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 0, 6L,
                ScatterSettings.Fit.DEFAULT, POPPY));
        RecordingListener[] jobs = new RecordingListener[1];
        net.minecraft.util.math.BlockPos[] grown = new net.minecraft.util.math.BlockPos[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    check(plan.placements() > 100, "only " + plan.placements() + " placements on the meadow");
                    List<ScatterPlan.Placement> placements = heldPlan(h, h.player).placements();
                    for (ScatterPlan.Placement p : placements) {
                        check(p.anchor().x() < x0 + 16, "a poppy planned in the riverbed at " + p.anchor());
                    }
                    Integer collisions = plan.rejectedCounts().get("COLLISION");
                    check(collisions != null && collisions >= 14 * 14, "the riverbed not refused: "
                            + plan.rejectedCounts());
                    check(planted > 50, planted + " water plants");
                    BlockPos anchor = placements.get(0).anchor();
                    grown[0] = pos(anchor.x(), anchor.y(), anchor.z());
                    writer.write(anchor.x(), anchor.y(), anchor.z(), seagrass, null);
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    check(jobs[0].result.skippedConflicts() == 1, "skipped " + jobs[0].result.skippedConflicts());
                    check(h.world.getBlockState(grown[0]).isOf(Blocks.SEAGRASS), "the poppy took the grown seagrass");
                    check(find(h.world, all, FLOOR_Y + 1, Blocks.POPPY).size() == reply.plan.placements() - 1,
                            "poppies missing");
                    checkSame(before, capture(h.world, riverbed), "the riverbed");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }
}
