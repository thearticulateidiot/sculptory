package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.ShapeStamp;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;

/**
 * The fluid patterns on a real server (regionCorner slots
 * 900, 902, 904 and 906): Waterlog and Dry against the real state space; a flood of a basin writes exactly its pocket,
 * waterlogs the stairs on its rim and a sign (keeping its text) and nothing above the level, as one history step undone
 * and redone exactly; a drain of water, waterlogged stairs, a waterlogged sign (text kept) and kelp; a fluid-ball
 * stroke with a Waterlog material (a sign inside keeps its text) and its undo; refusals (over {@code maxOpVolume}, a
 * Waterlog of stone) and protected cells skipped.
 */
public final class FluidGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int FLOOR = 100;

    private static JobTicket run(Harness h, ServerPlayerEntity player, OpSpec op, RecordingListener listener) {
        try {
            return h.service.run(player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }

    private static RejectReason refusal(Harness h, ServerPlayerEntity player, OpSpec op) {
        try {
            h.service.run(player, op, RunOptions.DEFAULT, null);
        } catch (EditRejected e) {
            return e.reason();
        }
        throw new GameTestException(op.getClass().getSimpleName() + " was not refused");
    }

    /** A stone basin: the floor at {@link #FLOOR}, walls up to FLOOR + 6 around an 18 × 18 interior of air. */
    private static void basin(Harness h, BlockWriter writer, int x0, int z0) {
        int stone = h.state("minecraft:stone");
        for (int x = x0; x <= x0 + 19; x++) {
            for (int z = z0; z <= z0 + 19; z++) {
                writer.write(x, FLOOR, z, stone, null);
                boolean wall = x == x0 || x == x0 + 19 || z == z0 || z == z0 + 19;
                for (int y = FLOOR + 1; y <= FLOOR + 6; y++) {
                    writer.write(x, y, z, wall ? stone : h.state("minecraft:air"), null);
                }
            }
        }
    }

    /** Every cell of {@code area} holds {@code before} with {@code pattern} applied over {@code region}. */
    private static void checkPattern(Harness h, Box area, WorldSnapshot before, Region region, Pattern pattern, String what) {
        StateSpace states = h.runtime.states();
        WorldSnapshot now = capture(h.world, area);
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int was = before.get(x, y, z);
                    int want = region.contains(x, y, z) ? pattern.apply(states, x, y, z, was) : was;
                    int got = now.get(x, y, z);
                    if (want != got) {
                        throw new GameTestException(what + " at " + x + "," + y + "," + z + ": "
                                + Block.getStateFromRawId(got) + " instead of " + Block.getStateFromRawId(want));
                    }
                }
            }
        }
    }

    private static long changes(Harness h, Box area, WorldSnapshot before, Region region, Pattern pattern) {
        StateSpace states = h.runtime.states();
        long changed = 0;
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int was = before.get(x, y, z);
                    if (region.contains(x, y, z) && pattern.apply(states, x, y, z, was) != was) changed++;
                }
            }
        }
        return changed;
    }

    /** A standing oak sign at (x, y, z) in {@code state} reading "Hi" on its front. */
    private static void sign(Harness h, BlockWriter writer, int x, int y, int z, String state) {
        writer.write(x, y, z, h.state(state), null);
        ((SignBlockEntity) h.world.getBlockEntity(pos(x, y, z))).setText(new SignText().withMessage(0, Text.literal("Hi")), true);
    }

    /** The sign at (x, y, z) holds {@code state} and still reads "Hi". */
    private static void checkSign(Harness h, int x, int y, int z, String state, String what) {
        check(world(h).getBlockState(pos(x, y, z)).equals(Block.getStateFromRawId(h.state(state))), what + ": sign state");
        SignBlockEntity sign = (SignBlockEntity) world(h).getBlockEntity(pos(x, y, z));
        String text = sign == null ? null : sign.getFrontText().getMessage(0, false).getString();
        check("Hi".equals(text), what + ": the sign reads " + text + " instead of Hi");
    }

    private static ServerWorld world(Harness h) {
        return h.world;
    }

    private static void checkLabel(Harness h, String label) {
        List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
        check(entries.size() == 1, "one history entry, got " + entries.size());
        check(entries.get(0).label().equals(label), "label " + entries.get(0).label() + ", expected " + label);
    }

    // =================================================================== the patterns

    /** Waterlog and Dry evaluated against the real state space (the unit tests use the fake). */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_patterns", tickLimit = LIMIT)
    public void thePatternsWorkOnRealStates(TestContext context) {
        FabricStateSpace states = EngineTestSupport.runtime(context).states();
        int air = states.air();
        int water = EngineTestSupport.handle(states, "minecraft:water[level=0]");
        int flowing = EngineTestSupport.handle(states, "minecraft:water[level=5]");
        int lava = EngineTestSupport.handle(states, "minecraft:lava[level=0]");
        int dryStairs = EngineTestSupport.handle(states, "minecraft:oak_stairs[facing=north,half=top]");
        int wetStairs = EngineTestSupport.handle(states, "minecraft:oak_stairs[facing=north,half=top,waterlogged=true]");
        int dryFence = EngineTestSupport.handle(states, "minecraft:oak_fence");
        int wetFence = EngineTestSupport.handle(states, "minecraft:oak_fence[waterlogged=true]");
        int stone = EngineTestSupport.handle(states, "minecraft:stone");
        int grass = EngineTestSupport.handle(states, "minecraft:short_grass");
        int kelp = EngineTestSupport.handle(states, "minecraft:kelp[age=3]");
        int kelpPlant = EngineTestSupport.handle(states, "minecraft:kelp_plant");
        int seagrass = EngineTestSupport.handle(states, "minecraft:seagrass");
        int bubbles = EngineTestSupport.handle(states, "minecraft:bubble_column");
        check(Pattern.isFluidSource(states, water) && Pattern.isFluidSource(states, lava), "still water and lava are sources");
        check(!Pattern.isFluidSource(states, flowing) && !Pattern.isFluidSource(states, wetStairs)
                && !Pattern.isFluidSource(states, kelp) && !Pattern.isFluidSource(states, stone), "nothing else is");
        Pattern waterlog = new Pattern.Waterlog(water), lavalog = new Pattern.Waterlog(lava), dry = new Pattern.Dry();
        check(waterlog.apply(states, 0, 0, 0, air) == water, "air floods");
        check(waterlog.apply(states, 0, 0, 0, dryStairs) == wetStairs, "stairs waterlog");
        check(waterlog.apply(states, 0, 0, 0, dryFence) == wetFence, "fences waterlog");
        check(waterlog.apply(states, 0, 0, 0, wetStairs) == wetStairs, "wet stairs stay");
        check(waterlog.apply(states, 0, 0, 0, stone) == stone, "stone stays");
        check(waterlog.apply(states, 0, 0, 0, grass) == grass, "grass stays");
        check(waterlog.apply(states, 0, 0, 0, flowing) == flowing, "flowing water stays");
        check(waterlog.apply(states, 0, 0, 0, kelp) == kelp, "kelp has no property");
        check(lavalog.apply(states, 0, 0, 0, air) == lava, "air fills with lava");
        check(lavalog.apply(states, 0, 0, 0, dryStairs) == dryStairs, "lava never waterlogs");
        check(dry.apply(states, 0, 0, 0, water) == air && dry.apply(states, 0, 0, 0, flowing) == air
                && dry.apply(states, 0, 0, 0, lava) == air, "fluids dry to air");
        check(dry.apply(states, 0, 0, 0, wetStairs) == dryStairs && dry.apply(states, 0, 0, 0, wetFence) == dryFence,
                "waterlogged blocks dry");
        check(dry.apply(states, 0, 0, 0, kelp) == air && dry.apply(states, 0, 0, 0, kelpPlant) == air
                && dry.apply(states, 0, 0, 0, seagrass) == air && dry.apply(states, 0, 0, 0, bubbles) == air,
                "water plants and bubble columns go");
        check(dry.apply(states, 0, 0, 0, dryStairs) == dryStairs && dry.apply(states, 0, 0, 0, stone) == stone
                && dry.apply(states, 0, 0, 0, air) == air, "the rest stays");
        context.complete();
    }

    // =================================================================== flood and drain

    /**
     * A flood of the basin up to level FLOOR + 3: the pocket becomes water, the dry stairs inside it and in its wall are
     * waterlogged, the wet stairs, the stone and everything above the level are untouched (the region names some of
     * them, and they are not counted); one history entry "Fill · N blocks"; undone and redone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_flood", tickLimit = LIMIT)
    public void floodFillsThePocketWaterlogsTheRimAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 900);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, FLOOR - 2, z0, x0 + 19, FLOOR + 8, z0 + 19);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        basin(h, writer, x0, z0);
        writer.write(x0 + 5, FLOOR + 2, z0 + 5, h.state("minecraft:oak_stairs[facing=north]"), null);
        writer.write(x0 + 6, FLOOR + 2, z0 + 6, h.state("minecraft:oak_stairs[facing=south,waterlogged=true]"), null);
        writer.write(x0, FLOOR + 3, z0 + 7, h.state("minecraft:oak_stairs[facing=east]"), null);
        sign(h, writer, x0 + 7, FLOOR + 1, z0 + 7, "minecraft:oak_sign[rotation=4]");
        int level = FLOOR + 3;
        CellSet.Builder cells = CellSet.builder();
        for (int x = x0 + 1; x <= x0 + 18; x++) {
            for (int z = z0 + 1; z <= z0 + 18; z++) {
                for (int y = FLOOR + 1; y <= level; y++) cells.add(x, y, z);
            }
        }
        cells.add(x0, FLOOR + 3, z0 + 7); // the wall's stairs: on the rim
        cells.add(x0 + 3, FLOOR, z0 + 3); // a floor cell: named, untouched
        Region.Cells region = new Region.Cells(cells.build());
        int water = h.state("minecraft:water[level=0]");
        Pattern waterlog = new Pattern.Waterlog(water);
        WorldSnapshot before = capture(world, area);
        long expected = changes(h, area, before, region, waterlog);
        check(expected == 18 * 18 * 3 - 2 + 2, "expected changes " + expected);
        WorldSnapshot[] flooded = new WorldSnapshot[1];
        RecordingListener fill = new RecordingListener(), undo = new RecordingListener(), redo = new RecordingListener();
        JobTicket ticket = run(h, h.player, new OpSpec.Fill(region, waterlog, CellMask.ANY), fill);
        check(ticket.estimatedCells() == region.cellCount(), "estimate " + ticket.estimatedCells());
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "flood running"))
                .createAndAdd(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED, "flood " + fill.result);
                    check(fill.result.changed() == expected, "changed " + fill.result.changed() + ", expected " + expected);
                    checkPattern(h, area, before, region, waterlog, "flood");
                    check(world.getBlockState(pos(x0 + 4, level + 1, z0 + 4)).isAir(), "nothing above the level");
                    checkSign(h, x0 + 7, FLOOR + 1, z0 + 7, "minecraft:oak_sign[rotation=4,waterlogged=true]", "flooded");
                    checkLabel(h, String.format(Locale.ROOT, "Fill · %,d blocks", expected));
                    flooded[0] = capture(world, area);
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    checkSame(before, capture(world, area), "after the undo");
                    h.redo(redo);
                })
                .createAndAdd(() -> check(redo.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redo.result.outcome() == JobOutcome.COMPLETED, "redo " + redo.result);
                    checkSame(flooded[0], capture(world, area), "after the redo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A drain of the flooded basin: the water goes, the waterlogged stairs are dried, kelp and seagrass go with the
     * water, the dry stairs and the floor stay; one history entry; undone and redone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_drain", tickLimit = LIMIT)
    public void drainRemovesWaterWaterloggingAndWaterPlantsAndUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 902);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, FLOOR - 2, z0, x0 + 19, FLOOR + 8, z0 + 19);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        basin(h, writer, x0, z0);
        int water = h.state("minecraft:water[level=0]");
        for (int x = x0 + 1; x <= x0 + 18; x++) {
            for (int z = z0 + 1; z <= z0 + 18; z++) {
                for (int y = FLOOR + 1; y <= FLOOR + 3; y++) writer.write(x, y, z, water, null);
            }
        }
        writer.write(x0 + 5, FLOOR + 2, z0 + 5, h.state("minecraft:oak_stairs[facing=north,waterlogged=true]"), null);
        writer.write(x0 + 6, FLOOR + 3, z0 + 6, h.state("minecraft:oak_stairs[facing=south]"), null);
        writer.write(x0 + 8, FLOOR + 1, z0 + 8, h.state("minecraft:kelp_plant"), null);
        writer.write(x0 + 8, FLOOR + 2, z0 + 8, h.state("minecraft:kelp[age=3]"), null);
        writer.write(x0 + 9, FLOOR + 1, z0 + 9, h.state("minecraft:seagrass"), null);
        sign(h, writer, x0 + 7, FLOOR + 1, z0 + 7, "minecraft:oak_sign[rotation=4,waterlogged=true]");
        CellSet.Builder cells = CellSet.builder();
        for (int x = x0 + 1; x <= x0 + 18; x++) {
            for (int z = z0 + 1; z <= z0 + 18; z++) {
                for (int y = FLOOR + 1; y <= FLOOR + 3; y++) cells.add(x, y, z);
            }
        }
        cells.add(x0 + 3, FLOOR, z0 + 3);
        Region.Cells region = new Region.Cells(cells.build());
        Pattern dry = new Pattern.Dry();
        WorldSnapshot before = capture(world, area);
        long expected = changes(h, area, before, region, dry);
        check(expected == 18 * 18 * 3 - 1, "expected changes " + expected);
        WorldSnapshot[] drained = new WorldSnapshot[1];
        RecordingListener fill = new RecordingListener(), undo = new RecordingListener(), redo = new RecordingListener();
        run(h, h.player, new OpSpec.Fill(region, dry, CellMask.ANY), fill);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "drain running"))
                .createAndAdd(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED, "drain " + fill.result);
                    check(fill.result.changed() == expected, "changed " + fill.result.changed() + ", expected " + expected);
                    checkPattern(h, area, before, region, dry, "drain");
                    check(world.getBlockState(pos(x0 + 8, FLOOR + 2, z0 + 8)).isAir(), "the kelp went with the water");
                    checkSign(h, x0 + 7, FLOOR + 1, z0 + 7, "minecraft:oak_sign[rotation=4]", "drained");
                    check(world.getBlockState(pos(x0 + 5, FLOOR + 2, z0 + 5)).equals(
                            Block.getStateFromRawId(h.state("minecraft:oak_stairs[facing=north]"))), "the stairs are dry");
                    checkLabel(h, String.format(Locale.ROOT, "Fill · %,d blocks", expected));
                    drained[0] = capture(world, area);
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    checkSame(before, capture(world, area), "after the undo");
                    h.redo(redo);
                })
                .createAndAdd(() -> check(redo.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redo.result.outcome() == JobOutcome.COMPLETED, "redo " + redo.result);
                    checkSame(drained[0], capture(world, area), "after the redo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // =================================================================== the fluid ball

    /**
     * A Shape-brush sphere with a Waterlog material over a floor, a stone pillar and stairs: the sphere's air becomes
     * water, its dry stairs are waterlogged, its stone and wet stairs are untouched; one entry "Shape · N blocks"; undone
     * exactly. A stroke whose Waterlog carries stone is refused INVALID.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_ball", tickLimit = LIMIT)
    public void aFluidBallWaterlogsWhatItCanAndUndoesExactly(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 904);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, FLOOR - 2, z0, x0 + 31, FLOOR + 12, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = x0; x <= x0 + 31; x++) {
            for (int z = z0; z <= z0 + 31; z++) {
                for (int y = FLOOR - 2; y <= FLOOR; y++) writer.write(x, y, z, stone, null);
            }
        }
        for (int y = FLOOR + 1; y <= FLOOR + 3; y++) writer.write(x0 + 13, y, z0 + 13, stone, null);
        writer.write(x0 + 12, FLOOR + 1, z0 + 12, h.state("minecraft:oak_stairs[facing=north]"), null);
        writer.write(x0 + 11, FLOOR + 1, z0 + 11, h.state("minecraft:oak_stairs[facing=west,waterlogged=true]"), null);
        writer.write(x0 + 12, FLOOR + 2, z0 + 10, h.state("minecraft:oak_fence"), null);
        sign(h, writer, x0 + 12, FLOOR + 2, z0 + 13, "minecraft:oak_sign[rotation=8]");
        int water = h.state("minecraft:water[level=0]");
        Pattern waterlog = new Pattern.Waterlog(water);
        BrushSpec spec = ShapeBrushGameTest.shape(3, ShapeSpec.Kind.SPHERE, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0, waterlog,
                Symmetry.NONE);
        Dab dab = ShapeBrushGameTest.at(0, x0 + 12, FLOOR + 3, z0 + 12);
        Region region = ShapeStamp.placement(spec, dab).region();
        WorldSnapshot before = capture(world, area);
        long expected = changes(h, area, before, region, waterlog);
        check(expected > 0 && expected < region.cellCount(), "some cells of the sphere change, not all: " + expected);
        ShapeBrushGameTest.stroke(h, executor, 10, spec, List.of(dab), 1);
        checkPattern(h, area, before, region, waterlog, "ball");
        check(region.contains(x0 + 12, FLOOR + 2, z0 + 13), "the sign is inside the sphere");
        checkSign(h, x0 + 12, FLOOR + 2, z0 + 13, "minecraft:oak_sign[rotation=8,waterlogged=true]", "ball");
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        checkLabel(h, String.format(Locale.ROOT, "Shape · %,d blocks", expected));
        ShapeBrushGameTest.undoAll(h, executor, 1, 20);
        checkSame(before, capture(world, area), "after the undo");
        BrushSpec bad = ShapeBrushGameTest.shape(3, ShapeSpec.Kind.SPHERE, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0,
                new Pattern.Waterlog(stone), Symmetry.NONE);
        try {
            h.service.beginStroke(h.player, 11, bad);
            throw new GameTestException("a Waterlog of stone began a stroke");
        } catch (EditRejected e) {
            check(e.reason() == RejectReason.INVALID, "refused " + e.reason());
        }
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    // =================================================================== refusals and protection

    /**
     * A flood over {@code maxOpVolume} is refused TOO_LARGE, a Waterlog of stone INVALID; a flood across a protected
     * chunk skips the protected cells and writes the rest, and undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_refusals", tickLimit = LIMIT)
    public void refusalsAndProtectedCells(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        int[] at = regionCorner(context, 906);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, FLOOR - 2, z0, x0 + 31, FLOOR + 4, z0 + 31);
        loadAndForce(world, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = x0; x <= x0 + 31; x++) {
            for (int z = z0; z <= z0 + 31; z++) writer.write(x, FLOOR, z, stone, null);
        }
        int water = h.state("minecraft:water[level=0]");
        Pattern waterlog = new Pattern.Waterlog(water);
        long over = h.runtime.config().toLimits().maxOpVolume() + 1;
        int side = (int) Math.ceil(Math.sqrt(over));
        check(refusal(h, builder, new OpSpec.Fill(box(x0, FLOOR + 1, z0, x0 + side, FLOOR + 1, z0 + side), waterlog,
                CellMask.ANY)) == RejectReason.TOO_LARGE, "a flood over the op limit");
        check(refusal(h, h.player, new OpSpec.Fill(box(x0, FLOOR + 1, z0, x0 + 3, FLOOR + 1, z0 + 3),
                new Pattern.Waterlog(stone), CellMask.ANY)) == RejectReason.INVALID, "a Waterlog of stone");
        check(refusal(h, h.player, new OpSpec.Fill(box(x0, FLOOR + 1, z0, x0 + 3, FLOOR + 1, z0 + 3),
                new Pattern.Waterlog(h.state("minecraft:water[level=2]")), CellMask.ANY)) == RejectReason.INVALID,
                "flowing water is not a source");

        CellSet.Builder cells = CellSet.builder();
        for (int x = x0 + 8; x <= x0 + 23; x++) cells.add(x, FLOOR + 1, z0 + 4);
        Region.Cells line = new Region.Cells(cells.build());
        ProtectionHook.protect(builder, world, x0 + 16, z0, x0 + 31, z0 + 31);
        check(!world.canPlayerModifyAt(builder, pos(x0 + 20, FLOOR + 1, z0 + 4)), "the east chunk is protected");
        check(world.canPlayerModifyAt(builder, pos(x0 + 10, FLOOR + 1, z0 + 4)), "the west chunk is not");
        WorldSnapshot before = capture(world, area);
        RecordingListener fill = new RecordingListener(), undo = new RecordingListener();
        try {
            h.service.run(builder, new OpSpec.Fill(line, waterlog, CellMask.ANY), RunOptions.DEFAULT, fill);
        } catch (EditRejected e) {
            ProtectionHook.clear(builder);
            throw new GameTestException("flood refused: " + e.getMessage());
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "flood running"))
                .createAndAdd(() -> {
                    ProtectionHook.clear(builder);
                    check(fill.result.outcome() == JobOutcome.COMPLETED, "flood " + fill.result);
                    check(fill.result.skippedProtected() == 8, "skipped " + fill.result.skippedProtected() + " protected cells");
                    check(fill.result.changed() == 8, "changed " + fill.result.changed());
                    for (int x = x0 + 8; x <= x0 + 23; x++) {
                        int got = Block.getRawIdFromState(world.getBlockState(pos(x, FLOOR + 1, z0 + 4)));
                        int want = x < x0 + 16 ? water : before.get(x, FLOOR + 1, z0 + 4);
                        check(got == want, "cell " + x + " after the protected flood");
                    }
                    try {
                        h.service.undo(builder, dev.sculptory.core.history.ConflictPolicy.SKIP_CONFLICTS, undo);
                    } catch (EditRejected e) {
                        throw new GameTestException("undo refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(before, capture(world, area), "after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }
}
