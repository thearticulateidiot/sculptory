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
import static dev.sculptory.fabric.gametest.ScatterGameTest.floor;
import static dev.sculptory.fabric.gametest.ScatterGameTest.heldPlan;
import static dev.sculptory.fabric.gametest.ScatterGameTest.preview;
import static dev.sculptory.fabric.gametest.ScatterGameTest.region;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.fabric.engine.ScatterService;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.gametest.ScatterGameTest.Reply;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * Water plants and column plants in a real server world: over a shore (a meadow beside a walled lake), seagrass, kelp
 * columns and coral fans land only on the seabed in still water, lily pads only on the water, nothing on land and no
 * dry cell under water, and one undo restores the water exactly; land plants with survival off still go only where
 * they went before; a mix of land and water plants puts each on its side; cactus columns stand where vanilla keeps
 * every one of their cells.
 */
public final class ScatterWaterGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    /** The lake's seabed is the floor (dirt); still water fills 101-103, so its surface is at 103. */
    private static final int SURFACE_Y = FLOOR_Y + 3;

    private static final ScatterSource SEAGRASS = new ScatterSource.Block("minecraft:seagrass");
    /** Fully grown kelp: it does not grow into the snapshot. */
    private static final ScatterSource KELP = new ScatterSource.Block("minecraft:kelp[age=25]");
    private static final ScatterSource FAN = new ScatterSource.Block("minecraft:tube_coral_fan");
    private static final ScatterSource LILY = new ScatterSource.Block("minecraft:lily_pad");
    private static final ScatterSource PICKLES = new ScatterSource.Block("minecraft:sea_pickle[pickles=3,waterlogged=true]");
    private static final ScatterSource POPPY = new ScatterSource.Block("minecraft:poppy");
    private static final ScatterSource TALL_GRASS = new ScatterSource.Block("minecraft:tall_grass[half=lower]");

    /** A preview of {@code sources} (weight 1 each) over every column of {@code area}. */
    static C2S.ScatterPreview request(ScatterArea area, int spacing, long seed, ScatterSettings.Fit fit,
                                      ScatterSettings.ColumnHeight columns, ScatterSource... sources) {
        List<C2S.ScatterPreview.Variant> variants = new ArrayList<>();
        for (ScatterSource source : sources) variants.add(new C2S.ScatterPreview.Variant(source, 1));
        return new C2S.ScatterPreview(1, area, new C2S.ScatterPreview.Settings(seed, spacing,
                new ScatterSettings.Density.Fraction(1), SurfaceMask.ANY, fit, columns), variants,
                ScatterSettings.Transforms.ALL);
    }

    /**
     * A shore over [x0, x0 + 32) × [z0, z0 + 16): the west half a grass meadow at the floor, the east half a lake: a
     * stone rim up to the water surface around a dirt seabed at the floor under still water. Returns the loaded box.
     */
    static Box shore(Harness h, int x0, int z0) {
        Box all = floor(h, x0, z0, 32, 16);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int grass = h.state("minecraft:grass_block[snowy=false]"), dirt = h.state("minecraft:dirt");
        int stone = h.state("minecraft:stone"), water = h.state("minecraft:water[level=0]");
        for (int x = x0; x < x0 + 32; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                if (x < x0 + 16) {
                    writer.write(x, FLOOR_Y, z, grass, null);
                    continue;
                }
                boolean rim = x == x0 + 16 || x == x0 + 31 || z == z0 || z == z0 + 15;
                if (!rim) writer.write(x, FLOOR_Y, z, dirt, null);
                for (int y = FLOOR_Y + 1; y <= SURFACE_Y; y++) writer.write(x, y, z, rim ? stone : water, null);
            }
        }
        return all;
    }

    /** The lake's water columns: inside the rim. */
    static boolean inLake(int x0, int z0, int x, int z) {
        return x > x0 + 16 && x < x0 + 31 && z > z0 && z < z0 + 15;
    }

    static boolean stillWater(FluidState fluid) {
        return fluid.isStill() && fluid.getFluid() == Fluids.WATER;
    }

    /** No cell of the lake between the seabed and the surface is without still water. */
    static void noDryCellUnderWater(Harness h, int x0, int z0) {
        for (int x = x0 + 17; x < x0 + 31; x++) {
            for (int z = z0 + 1; z < z0 + 15; z++) {
                for (int y = FLOOR_Y + 1; y <= SURFACE_Y; y++) {
                    check(stillWater(h.world.getFluidState(pos(x, y, z))), "a dry cell under water at " + x + "," + y
                            + "," + z + ": " + h.world.getBlockState(pos(x, y, z)));
                }
            }
        }
    }

    /** Counts {@code block} in the box's columns between y0 and y1. */
    static int count(Harness h, int x0, int z0, int x1, int z1, int y0, int y1, Block block) {
        int n = 0;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                for (int y = y0; y <= y1; y++) {
                    if (h.world.getBlockState(pos(x, y, z)).isOf(block)) n++;
                }
            }
        }
        return n;
    }

    /**
     * Seagrass, kelp columns and coral fans go on the lake's seabed in still water, lily pads on its surface, nothing
     * on the meadow or the rim; every lake cell keeps still water; kelp is kelp_plant under a kelp top, at most as tall
     * as the water; the fans stay alive; one undo restores the shore exactly, water included.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_water_lake", tickLimit = LIMIT)
    public void scatterWaterPlantsGoUnderWaterOrOnIt(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 150);
        int x0 = at[0], z0 = at[1];
        Box all = shore(h, x0, z0);
        Box area = box(x0 - 2, FLOOR_Y - 1, z0 - 2, x0 + 33, SURFACE_Y + 4, z0 + 17);
        WorldSnapshot before = capture(h.world, area);
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 0, 17L,
                ScatterSettings.Fit.DEFAULT, new ScatterSettings.ColumnHeight(1, 6), SEAGRASS, KELP, FAN, LILY));
        RecordingListener[] jobs = new RecordingListener[2];
        int[] planned = new int[4];
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    check(plan.placements() > 100, "only " + plan.placements() + " placements: " + plan.rejectedCounts());
                    Integer water = plan.rejectedCounts().get("WATER");
                    check(water != null && water >= 16 * 16, "the meadow and the rim not refused: " + plan.rejectedCounts());
                    int taller = 0;
                    for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
                        int x = p.anchor().x(), y = p.anchor().y(), z = p.anchor().z();
                        planned[p.variant()]++;
                        check(inLake(x0, z0, x, z), "a placement off the lake: " + p);
                        check(y == (p.variant() == 3 ? SURFACE_Y + 1 : FLOOR_Y + 1), "a placement off its level: " + p);
                        check(p.height() <= 3, "a kelp column above the surface: " + p);
                        if (p.height() > 1) taller++;
                    }
                    for (int v = 0; v < 4; v++) check(planned[v] > 10, "variant " + v + " planned " + planned[v]);
                    check(taller > 5, "only " + taller + " kelp columns over one block");
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    check(jobs[0].result.changed() == reply.plan.totalCells(), "changed " + jobs[0].result.changed()
                            + " of " + reply.plan.totalCells());
                    for (int x = x0; x < x0 + 16; x++) {
                        for (int z = z0; z < z0 + 16; z++) {
                            for (int y = FLOOR_Y + 1; y <= SURFACE_Y + 2; y++) {
                                check(h.world.getBlockState(pos(x, y, z)).isAir(), "something on the meadow at " + x + ","
                                        + y + "," + z);
                            }
                        }
                    }
                    Set<Block> underwater = Set.of(Blocks.WATER, Blocks.SEAGRASS, Blocks.KELP, Blocks.KELP_PLANT,
                            Blocks.TUBE_CORAL_FAN);
                    for (int x = x0 + 17; x < x0 + 31; x++) {
                        for (int z = z0 + 1; z < z0 + 15; z++) {
                            for (int y = FLOOR_Y + 1; y <= SURFACE_Y; y++) {
                                BlockState state = h.world.getBlockState(pos(x, y, z));
                                check(underwater.contains(state.getBlock()), "under water at " + x + "," + y + "," + z
                                        + ": " + state);
                                if (state.isOf(Blocks.KELP_PLANT)) {
                                    BlockState up = h.world.getBlockState(pos(x, y + 1, z));
                                    check(up.isOf(Blocks.KELP) || up.isOf(Blocks.KELP_PLANT), "kelp_plant without a top at "
                                            + x + "," + y + "," + z + ": " + up);
                                }
                                if (state.isOf(Blocks.TUBE_CORAL_FAN)) {
                                    check(state.get(Properties.WATERLOGGED), "a dry coral fan at " + x + "," + y + "," + z);
                                }
                            }
                            BlockState above = h.world.getBlockState(pos(x, SURFACE_Y + 1, z));
                            check(above.isAir() || above.isOf(Blocks.LILY_PAD), "above the lake: " + above);
                        }
                    }
                    noDryCellUnderWater(h, x0, z0);
                    int lake0 = x0 + 17, lake1 = x0 + 30;
                    check(count(h, lake0, z0 + 1, lake1, z0 + 14, FLOOR_Y + 1, FLOOR_Y + 1, Blocks.SEAGRASS) == planned[0],
                            "seagrass for placements");
                    check(count(h, lake0, z0 + 1, lake1, z0 + 14, FLOOR_Y + 1, SURFACE_Y, Blocks.KELP) == planned[1],
                            "kelp tops for placements");
                    check(count(h, lake0, z0 + 1, lake1, z0 + 14, FLOOR_Y + 1, FLOOR_Y + 1, Blocks.TUBE_CORAL_FAN)
                            == planned[2], "coral fans for placements");
                    check(count(h, lake0, z0 + 1, lake1, z0 + 14, SURFACE_Y + 1, SURFACE_Y + 1, Blocks.LILY_PAD)
                            == planned[3], "lily pads for placements");
                    check(h.history().undoLabels().size() == 1, "one history entry: " + h.history().undoLabels());
                })
                .createAndAdd(() -> {
                    check(++waited[0] >= 40, "waiting two seconds: coral dies out of water");
                    check(count(h, x0 + 17, z0 + 1, x0 + 30, z0 + 14, FLOOR_Y + 1, FLOOR_Y + 1, Blocks.DEAD_TUBE_CORAL_FAN)
                            == 0, "a coral fan died");
                    noDryCellUnderWater(h, x0, z0);
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
     * Land plants with survival off go where they went before: poppies on the meadow and on the stone rim, never into
     * the lake (its columns are collisions), and the lake is untouched. With fluids allowed too, a block variant still
     * stays out of the water (that setting is for assets and clipboards).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_water_land", tickLimit = LIMIT)
    public void scatterLandPlantsWithSurvivalOffStayOnLand(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 151);
        int x0 = at[0], z0 = at[1];
        Box all = shore(h, x0, z0);
        Box lake = box(x0 + 17, FLOOR_Y, z0 + 1, x0 + 30, SURFACE_Y + 1, z0 + 14);
        WorldSnapshot lakeBefore = capture(h.world, lake);
        int rimColumns = 2 * 16 + 2 * 14;
        Reply wet = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 0, 4L,
                ScatterSettings.Fit.DEFAULT.withSurvive(false).withAllowInFluid(true), ScatterSettings.ColumnHeight.ONE,
                POPPY));
        ScatterGameTest.planNow(scatter, wet);
        ScatterService.PlanReady wetPlan = wet.get("a preview with fluids allowed");
        check(wetPlan.placements() == 16 * 16 + rimColumns, wetPlan.placements() + " poppies with fluids allowed: "
                + wetPlan.rejectedCounts());
        for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
            check(!inLake(x0, z0, p.anchor().x(), p.anchor().z()), "a poppy in the lake with fluids allowed: " + p);
        }
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 0, 4L,
                ScatterSettings.Fit.DEFAULT.withSurvive(false), ScatterSettings.ColumnHeight.ONE, POPPY));
        RecordingListener[] jobs = new RecordingListener[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    int rim = 2 * 16 + 2 * 14;
                    check(plan.placements() == 16 * 16 + rim, plan.placements() + " poppies: " + plan.rejectedCounts());
                    Integer collisions = plan.rejectedCounts().get("COLLISION");
                    check(collisions != null && collisions == 14 * 14, "the lake not refused: " + plan.rejectedCounts());
                    check(!plan.rejectedCounts().containsKey("SURVIVAL") && !plan.rejectedCounts().containsKey("WATER"),
                            "survival or water asked: " + plan.rejectedCounts());
                    for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
                        check(!inLake(x0, z0, p.anchor().x(), p.anchor().z()), "a poppy in the lake: " + p);
                    }
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    check(count(h, x0, z0, x0 + 15, z0 + 15, FLOOR_Y + 1, FLOOR_Y + 1, Blocks.POPPY) == 16 * 16,
                            "poppies missing on the meadow");
                    check(count(h, x0 + 16, z0, x0 + 31, z0 + 15, SURFACE_Y + 1, SURFACE_Y + 1, Blocks.POPPY)
                            == 2 * 16 + 2 * 14, "poppies missing on the rim (survival is off)");
                    checkSame(lakeBefore, capture(h.world, lake), "the lake");
                    scatter.shutdown();
                    forceChunks(h.world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A mix of land and water plants over the shore puts each on its side: poppies and tall grass on the meadow,
     * seagrass and wet sea pickles on the seabed, lily pads on the water; no dry cell under water; one undo is exact.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_water_mix", tickLimit = LIMIT)
    public void scatterShorelineMixPutsEachPlantOnItsSide(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 152);
        int x0 = at[0], z0 = at[1];
        Box all = shore(h, x0, z0);
        Box area = box(x0 - 2, FLOOR_Y - 1, z0 - 2, x0 + 33, SURFACE_Y + 4, z0 + 17);
        WorldSnapshot before = capture(h.world, area);
        Reply reply = preview(scatter, h.player, request(region(x0, z0, x0 + 31, z0 + 15), 1, 23L,
                ScatterSettings.Fit.DEFAULT, ScatterSettings.ColumnHeight.ONE, POPPY, TALL_GRASS, SEAGRASS, PICKLES,
                LILY));
        RecordingListener[] jobs = new RecordingListener[2];
        int[] planned = new int[5];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
                        int x = p.anchor().x(), y = p.anchor().y(), z = p.anchor().z();
                        planned[p.variant()]++;
                        switch (p.variant()) {
                            case 0, 1 -> check(x < x0 + 16 && y == FLOOR_Y + 1, "a land plant off the meadow: " + p);
                            case 2, 3 -> check(inLake(x0, z0, x, z) && y == FLOOR_Y + 1, "a water plant off the seabed: " + p);
                            default -> check(inLake(x0, z0, x, z) && y == SURFACE_Y + 1, "a lily pad off the water: " + p);
                        }
                    }
                    for (int v = 0; v < 5; v++) check(planned[v] > 3, "variant " + v + " planned " + planned[v]);
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    int lake0 = x0 + 17, lake1 = x0 + 30;
                    check(count(h, x0, z0, x0 + 15, z0 + 15, FLOOR_Y + 1, FLOOR_Y + 1, Blocks.POPPY) == planned[0],
                            "poppies for placements");
                    check(count(h, x0, z0, x0 + 15, z0 + 15, FLOOR_Y + 1, FLOOR_Y + 1, Blocks.TALL_GRASS) == planned[1],
                            "tall grass for placements");
                    check(count(h, lake0, z0 + 1, lake1, z0 + 14, FLOOR_Y + 1, FLOOR_Y + 1, Blocks.SEAGRASS) == planned[2],
                            "seagrass for placements");
                    check(count(h, lake0, z0 + 1, lake1, z0 + 14, FLOOR_Y + 1, FLOOR_Y + 1, Blocks.SEA_PICKLE) == planned[3],
                            "sea pickles for placements");
                    check(count(h, lake0, z0 + 1, lake1, z0 + 14, SURFACE_Y + 1, SURFACE_Y + 1, Blocks.LILY_PAD)
                            == planned[4], "lily pads for placements");
                    for (int x = lake0; x <= lake1; x++) {
                        for (int z = z0 + 1; z <= z0 + 14; z++) {
                            BlockState bottom = h.world.getBlockState(pos(x, FLOOR_Y + 1, z));
                            check(!bottom.isOf(Blocks.POPPY) && !bottom.isOf(Blocks.TALL_GRASS), "a land plant under water");
                            if (bottom.isOf(Blocks.SEA_PICKLE)) {
                                check(bottom.get(Properties.WATERLOGGED) && bottom.get(Properties.PICKLES) == 3,
                                        "sea pickles at " + x + "," + z + ": " + bottom);
                            }
                        }
                    }
                    noDryCellUnderWater(h, x0, z0);
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
     * Cactus columns on sand, three blocks tall (vanilla neither grows nor ages a cactus that tall, so random ticks
     * leave them alone), stand only where vanilla keeps every cell: none beside the stone blocks set one above the
     * sand at some spots (a column whose second cell would touch one is refused), none under one (no room), every
     * placed cactus cell passes {@code canPlaceAt}; one undo is exact.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_scatter_water_cactus", tickLimit = LIMIT)
    public void scatterCactusColumnsStandWhereEveryCellSurvives(TestContext context) {
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 153);
        int x0 = at[0], z0 = at[1];
        Box all = floor(h, x0, z0, 16, 16);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int sand = h.state("minecraft:sand"), stone = h.state("minecraft:stone");
        for (int x = x0; x < x0 + 16; x++) {
            for (int z = z0; z < z0 + 16; z++) {
                writer.write(x, FLOOR_Y - 1, z, stone, null);
                writer.write(x, FLOOR_Y, z, sand, null);
                if ((x - x0) % 5 == 2 && (z - z0) % 5 == 2) writer.write(x, FLOOR_Y + 2, z, stone, null);
            }
        }
        Box area = box(x0 - 2, FLOOR_Y - 2, z0 - 2, x0 + 17, FLOOR_Y + 6, z0 + 17);
        WorldSnapshot before = capture(h.world, area);
        ScatterArea columns = new ScatterArea.Region(box(x0, FLOOR_Y, z0, x0 + 15, FLOOR_Y + 1, z0 + 15));
        Reply reply = preview(scatter, h.player, request(columns, 2, 8L, ScatterSettings.Fit.DEFAULT,
                new ScatterSettings.ColumnHeight(3, 3), new ScatterSource.Block("minecraft:cactus")));
        RecordingListener[] jobs = new RecordingListener[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    scatter.tick();
                    check(reply.finished(), "planning");
                })
                .createAndAdd(() -> {
                    ScatterService.PlanReady plan = reply.get("preview");
                    check(plan.placements() > 15, "only " + plan.placements() + " cacti: " + plan.rejectedCounts());
                    for (ScatterPlan.Placement p : heldPlan(h, h.player).placements()) {
                        check(p.anchor().y() == FLOOR_Y + 1 && p.height() == 3, "not a 3-tall cactus on the sand: " + p);
                    }
                    Integer survival = plan.rejectedCounts().get("SURVIVAL");
                    check(survival != null && survival > 0, "no column refused beside a stone: " + plan.rejectedCounts());
                    jobs[0] = commit(h, h.player, plan.planId());
                })
                .createAndAdd(() -> check(jobs[0].result != null, "commit running"))
                .createAndAdd(() -> {
                    check(jobs[0].result.outcome() == JobOutcome.COMPLETED, "commit " + jobs[0].result);
                    check(jobs[0].result.changed() == reply.plan.totalCells(), "changed " + jobs[0].result.changed());
                    int cells = 0;
                    for (int x = x0; x < x0 + 16; x++) {
                        for (int z = z0; z < z0 + 16; z++) {
                            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 3; y++) {
                                BlockPos p = pos(x, y, z);
                                BlockState state = h.world.getBlockState(p);
                                if (!state.isOf(Blocks.CACTUS)) continue;
                                cells++;
                                check(state.canPlaceAt(h.world, p), "a cactus that would break at " + p.toShortString());
                            }
                        }
                    }
                    check(cells == reply.plan.totalCells(), cells + " cactus cells of " + reply.plan.totalCells());
                    for (int x = x0 + 2; x < x0 + 16; x += 5) {
                        for (int z = z0 + 2; z < z0 + 16; z += 5) {
                            check(h.world.getBlockState(pos(x, FLOOR_Y + 1, z)).isAir(), "a cactus under a stone at " + x
                                    + "," + z);
                        }
                    }
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
}
