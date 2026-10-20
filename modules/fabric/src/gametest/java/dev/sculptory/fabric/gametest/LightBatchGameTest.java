package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.EditScope;
import dev.sculptory.fabric.world.LightBatch;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.platform.WriteOptions;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javax.management.ObjectName;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

/**
 * The light checks of physics-off writes go to the light engine a batch of cells at a time ({@link LightBatch}), not
 * once per cell, and the light settles exactly where vanilla's per-cell checks settle it, on a server that ticks
 * normally: the same edits made once through a {@link BlockWriter} and once with vanilla's {@code setBlockState}
 * leave the same block and sky light in every cell in and around them, as light rises (a lit cavity, a hole in a roof)
 * and falls (an emitter removed, the roof closed over sky-lit terrain), when the checks go only at the start of the
 * next world tick (a write that never clears its ticks), after a fill job cancelled part way and after its undo. Also:
 * nested collection and a write that throws, the guard that keeps the vanilla way for a light engine that is not
 * vanilla's, and how quickly the light engine still answers (light markers) during a large edit.
 * Region slots 1720-1726.
 */
public final class LightBatchGameTest implements FabricGameTest {
    private static final int SIDE = 32, HEIGHT = 24, Y0 = 0;
    /** The vanilla copy lies this far east of the batched one: their light (at most 15 blocks) never meets. */
    private static final int APART = 96;
    private static final int MARGIN = 15;
    private static final BlockState STONE = Blocks.STONE.defaultBlockState(), AIR = Blocks.AIR.defaultBlockState();

    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_light_batch", timeoutTicks = 100_000)
    public void batchedLightChecksSettleWhereVanillasDo(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1720);
        int ax = at[0], az = at[1], bx = ax + APART;
        Box area = around(ax, az, bx);
        loadAndForce(world, area);
        Steps steps = new Steps(context, world, area, () -> {
            forceChunks(world, area, false);
            h.close();
        });

        BlockWriter[] writer = {null};
        long[] batched = {0};
        // 1. Light rises: a hollow stone block, glowstone and a sea lantern inside, a hole through its roof.
        steps.add(() -> {
            writer[0] = h.runtime.writer(world, WriteOptions.DEFAULT);
            check(writer[0].lightTasks() == 0, "the writer batches its light checks: " + writer[0].lightTasks());
            long before = lightTaskObjects();
            batched[0] = LightBatch.batchedCells();
            build((x, y, z, state) -> write(writer[0], ax + x, Y0 + y, az + z, state));
            writer[0].clearTicksAtWrittenCells();
            long tasks = lightTaskObjects() - before;
            // At most a thousand checks a task, and the block covers 2 x 2 chunk columns.
            check(writer[0].lightTasks() > 0 && writer[0].lightTasks() <= 4 + 34_000 / LightBatch.TASK_CELLS,
                    "light tasks for the batched copy: " + writer[0].lightTasks());
            check(tasks < 1_000, "vanilla light tasks queued for ~34,000 batched writes: " + tasks);
            check(LightBatch.batchedCells() - batched[0] > 30_000, "checks batched: "
                    + (LightBatch.batchedCells() - batched[0]));
            vanilla(() -> build((x, y, z, state) -> set(world, bx + x, Y0 + y, az + z, state)));
        }, () -> {
            compare(world, ax, bx, az);
            check(lit(world, ax, az) > 1_000, "block light reached " + lit(world, ax, az) + " cells");
            check(world.getBrightness(LightLayer.SKY, new BlockPos(ax + 15, Y0 + 10, az + 15)) > 0
                    && world.getBrightness(LightLayer.SKY, new BlockPos(ax + 5, Y0 + 10, az + 26)) < 15,
                    "sky light through the roof's hole, dimmer away from it");
        });
        // 2. Light falls on terrain lit before: the glowstone goes, the roof closes over the sky-lit cavity.
        int[] litBefore = {0};
        steps.add(() -> {
            litBefore[0] = lit(world, ax, az);
            darken((x, y, z, state) -> write(writer[0], ax + x, Y0 + y, az + z, state));
            writer[0].clearTicksAtWrittenCells();
            vanilla(() -> darken((x, y, z, state) -> set(world, bx + x, Y0 + y, az + z, state)));
        }, () -> {
            compare(world, ax, bx, az);
            check(lit(world, ax, az) < litBefore[0], "block light fell: " + litBefore[0] + " -> " + lit(world, ax, az));
            check(world.getBrightness(LightLayer.SKY, new BlockPos(ax + 15, Y0 + 10, az + 15)) == 0,
                    "no sky light under the closed roof");
        });
        // 3. A writer that never clears its ticks: its checks are handed on only by the tick's flushes (the start of
        // the next world tick, or the end of this one; the test cannot tell which ran first). The markers are queued
        // once they were, so the light compared depends on them.
        BlockWriter[] late = {null};
        steps.add(() -> {
            late[0] = h.runtime.writer(world, WriteOptions.DEFAULT);
            write(late[0], ax + 20, Y0 + 8, az + 20, Blocks.GLOWSTONE.defaultBlockState());
            write(late[0], ax + 10, Y0 + 18, az + 25, AIR);
            check(late[0].lightTasks() == 0, "nothing handed on before the tick: " + late[0].lightTasks());
            vanilla(() -> {
                set(world, bx + 20, Y0 + 8, az + 20, Blocks.GLOWSTONE.defaultBlockState());
                set(world, bx + 10, Y0 + 18, az + 25, AIR);
            });
        }, () -> {
            check(late[0].lightTasks() > 0, "the tick handed the late writer's checks on");
            compare(world, ax, bx, az);
        }, () -> late[0].lightTasks() > 0);
        steps.start();
    }

    /** Nested collection joins the outer batch; a write that throws inside it leaves no collection open. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_light_batch_scope")
    public void collectionNestsAndSurvivesAThrow(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1726);
        int x = at[0], z = at[1];
        Box area = box(x, Y0, z, x + 15, Y0 + 15, z + 15);
        loadAndForce(world, area);
        try {
            LightBatch batch = LightBatch.of(world);
            check(batch != null, "batching is on for the vanilla light engine");
            try (EditScope scope = EditScope.suppressPhysics()) {
                try (LightBatch.Scope outer = batch.collect()) {
                    set(world, x, Y0, z, Blocks.GLOWSTONE.defaultBlockState());
                    try (LightBatch.Scope inner = batch.collect()) {
                        set(world, x + 1, Y0, z, Blocks.GLOWSTONE.defaultBlockState());
                    }
                    set(world, x + 2, Y0, z, Blocks.GLOWSTONE.defaultBlockState());
                    throw new IllegalStateException("a write failed");
                } catch (IllegalStateException expected) {
                    // The scope closed on the way out.
                }
                check(batch.pendingCells() == 3, "the outer and nested writes' checks: " + batch.pendingCells());
                set(world, x + 3, Y0, z, Blocks.GLOWSTONE.defaultBlockState());
                check(batch.pendingCells() == 3, "a write after the throw went the vanilla way: "
                        + batch.pendingCells());
                try (LightBatch.Scope again = batch.collect()) {
                    set(world, x + 4, Y0, z, Blocks.GLOWSTONE.defaultBlockState());
                }
            }
            check(batch.pendingCells() == 4, "collecting again works: " + batch.pendingCells());
            batch.flush();
            check(batch.pendingCells() == 0 && batch.flushedTasks() == 1, "one task for one column: "
                    + batch.flushedTasks());
        } finally {
            forceChunks(world, area, false);
            h.close();
        }
        context.succeed();
    }

    /**
     * Batching only for vanilla's own light providers (either may be absent, not both); turned off, a writer's checks
     * go to the light engine one per cell, as vanilla's.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_light_batch_guard")
    public void checksGoTheVanillaWayUnlessTheLightEngineIsVanillas(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        ThreadedLevelLightEngine light = world.getChunkSource().getLightEngine();
        Object block = light.blockEngine, sky = light.skyEngine;
        check(LightBatch.vanillaProviders(block, sky), "vanilla's providers");
        check(LightBatch.vanillaProviders(block, null), "no sky (the Nether)");
        check(!LightBatch.vanillaProviders(null, null), "no providers (an engine that replaced them)");
        check(!LightBatch.vanillaProviders(new Object(), sky), "another block light provider");
        check(!LightBatch.vanillaProviders(block, new Object()), "another sky light provider");

        int[] at = regionCorner(context, 1726);
        int x = at[0], z = at[1] + 64;
        Box area = box(x, Y0, z, x + 15, Y0 + 15, z + 15);
        loadAndForce(world, area);
        LightBatch.setDisabled(true);
        try {
            BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
            check(writer.lightTasks() == -1, "the writer does not batch");
            long batched = LightBatch.batchedCells(), before = lightTaskObjects();
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) write(writer, x + dx, Y0 + 4, z + dz, STONE);
            }
            writer.clearTicksAtWrittenCells();
            check(LightBatch.batchedCells() == batched, "no check was batched");
            long tasks = lightTaskObjects() - before;
            check(tasks >= 200, "a vanilla light task for each of the 256 cells: " + tasks);
        } finally {
            LightBatch.setDisabled(false);
            forceChunks(world, area, false);
            h.close();
        }
        context.succeed();
    }

    /**
     * A fill job cancelled part way (its last section half written) and then undone: the light where it wrote equals a
     * vanilla copy of the same blocks, and after the undo the light before the fill.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_light_batch_cancel", timeoutTicks = 100_000)
    public void aCancelledFillAndItsUndoLeaveVanillasLight(GameTestHelper context) {
        // A private executor writing at most 2,000 cells a tick: a job stops part way through a section.
        EditExecutor.Settings defaults = EditExecutor.Settings.from(SculptoryConfig.defaults(), true);
        EditExecutor<ServerLevel> executor = new EditExecutor<>(EngineTestSupport.runtime(context),
                new EditExecutor.Settings(defaults.tickBudgetNanos(), 2_000, defaults.brushLaneShare(),
                        defaults.maxActivePerOwner(), defaults.maxActiveGlobal(), defaults.maxQueued(),
                        defaults.maxTicketsPerJob(), defaults.unloadedPolicy(), defaults.maxBrushQueue(),
                        defaults.maxColumnsPerJob(), defaults.maxQueuedPerOwner()));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1722);
        int ax = at[0], az = at[1], bx = ax + APART;
        Box area = around(ax, az, bx);
        loadAndForce(world, area);
        Steps steps = new Steps(context, world, area, () -> {
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        });
        // Both copies first hold the same lit structure, written the vanilla way.
        steps.add(() -> vanilla(() -> {
            build((x, y, z, state) -> set(world, ax + x, Y0 + y, az + z, state));
            build((x, y, z, state) -> set(world, bx + x, Y0 + y, az + z, state));
        }), () -> compare(world, ax, bx, az));
        int[][][][][] before = {null};
        RecordingListener fill = new RecordingListener();
        JobTicket[] ticket = {null};
        boolean[] cancelled = {false};
        steps.add(() -> {
            before[0] = lightOf(world, ax, az);
            ticket[0] = h.fill(box(ax - 8, Y0 + 2, az - 8, ax + SIDE + 7, Y0 + HEIGHT + 8, az + SIDE + 7),
                    "minecraft:stone", fill);
        }, () -> {
            check(cancelled[0] && fill.result != null && fill.result.outcome() == JobOutcome.CANCELLED
                    && fill.result.changed() > 0 && fill.result.changed() < 20_000, "the fill was cancelled part way: "
                    + fill.result);
            // The same blocks in the other copy, the vanilla way.
            vanilla(() -> copyBlocks(world, ax, bx, az));
        }, () -> {
            executor.tick();
            // Cancel as soon as it has written some cells (at most 2,000 a tick, so its section is part done).
            if (!cancelled[0] && fill.result == null && wroteAny(world, ax, az)) {
                cancelled[0] = h.service.cancel(h.player, ticket[0].jobId());
            }
            return fill.result != null;
        });
        steps.add(() -> { }, () -> compare(world, ax, bx, az));
        RecordingListener[] undo = {null};
        steps.add(() -> undo[0] = MultiplayerGameTest.historyStep(h, h.player, true), () -> {
            check(undo[0].result != null && undo[0].result.outcome() == JobOutcome.COMPLETED, "undo " + undo[0].result);
            int[][][][] after = lightOf(world, ax, az);
            for (int t = 0; t < 2; t++) {
                for (int x = 0; x < after[t].length; x++) {
                    for (int y = 0; y < after[t][x].length; y++) {
                        for (int z = 0; z < after[t][x][y].length; z++) {
                            if (after[t][x][y][z] != before[0][t][x][y][z]) {
                                throw new GameTestAssertException(LightLayer.values()[t] + " light after the undo at "
                                        + x + ", " + y + ", " + z + ": " + after[t][x][y][z] + ", was "
                                        + before[0][t][x][y][z]);
                            }
                        }
                    }
                }
            }
        }, () -> {
            executor.tick();
            return undo[0] != null && undo[0].result != null;
        });
        steps.start();
    }

    /**
     * During a fill of about a million cells and until its light is worked out, the light engine still answers: a light
     * marker queued on a quiet chunk (as chunk generation's light stages and the client light follow-ups queue theirs)
     * runs within a few seconds. Wall-clock times, logged, with a loose bound (the GameTest server ticks as fast as it
     * can, so ticks say little). No chunk outside the test's region is loaded.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_light_batch_latency", timeoutTicks = 1_000_000)
    public void theLightEngineStaysResponsiveDuringALargeEdit(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        ThreadedLevelLightEngine light = world.getChunkSource().getLightEngine();
        int[] at = regionCorner(context, 1724);
        int x0 = at[0], z0 = at[1];
        Box edit = box(x0, Y0, z0, x0 + 127, Y0 + 63, z0 + 127);
        Box quiet = box(x0 + 512, Y0, z0, x0 + 527, Y0 + 15, z0 + 15);
        loadAndForce(world, edit);
        loadAndForce(world, quiet);
        int quietX = (x0 + 512) >> 4, quietZ = z0 >> 4;
        RecordingListener fill = new RecordingListener();
        long start = System.nanoTime();
        h.fill(edit, "minecraft:stone", fill);
        long[] fillMs = {-1}, markerAt = {0}, markerWorst = {0};
        CompletableFuture<?>[] marker = {null};
        List<CompletableFuture<?>> settle = new ArrayList<>();
        int[] markers = {0};
        boolean[] done = {false};
        String[] failure = {null};
        context.succeedWhen(() -> {
            if (failure[0] != null) throw new GameTestAssertException(failure[0]);
            if (done[0]) return;
            long now = System.nanoTime();
            if (now - start > 300_000_000_000L) {
                failure[0] = "not done in 5 minutes: fill " + fill.result;
                throw new GameTestAssertException(failure[0]);
            }
            if (fill.result != null && fillMs[0] < 0) {
                fillMs[0] = (now - start) / 1_000_000;
                // Behind every check the edit queued: a marker on each of its chunks.
                for (int cx = edit.min().x() >> 4; cx <= edit.max().x() >> 4; cx++) {
                    for (int cz = edit.min().z() >> 4; cz <= edit.max().z() >> 4; cz++) {
                        settle.add(light.waitForPendingTasks(cx, cz));
                    }
                }
            }
            if (marker[0] != null && marker[0].isDone()) {
                markers[0]++;
                marker[0] = null;
            }
            if (marker[0] == null) {
                marker[0] = light.waitForPendingTasks(quietX, quietZ);
                markerAt[0] = now;
            }
            markerWorst[0] = Math.max(markerWorst[0], (now - markerAt[0]) / 1_000_000);
            boolean settled = fillMs[0] >= 0 && markers[0] > 0
                    && settle.stream().allMatch(CompletableFuture::isDone) && !light.hasLightWork();
            if (!settled) throw new GameTestAssertException("the edit and its light are not done");
            long settleMs = (now - start) / 1_000_000;
            String report = String.format(Locale.ROOT, "fill %s (%d cells) written in %d ms, its light worked out after"
                    + " %d ms; %d light markers on a quiet chunk, the slowest %d ms", fill.result.outcome(),
                    fill.result.changed(), fillMs[0], settleMs, markers[0], markerWorst[0]);
            LoggerFactory.getLogger("sculptory").info("Light engine during a large edit: {}", report);
            forceChunks(world, edit, false);
            forceChunks(world, quiet, false);
            h.close();
            done[0] = true;
            if (fill.result.outcome() != JobOutcome.COMPLETED) failure[0] = "the fill: " + report;
            else if (markerWorst[0] > 5_000) failure[0] = "light markers stalled: " + report;
            if (failure[0] != null) throw new GameTestAssertException(failure[0]);
        });
    }

    // ------------------------------------------------------------------ helpers

    private interface Cells {
        void set(int x, int y, int z, BlockState state);
    }

    /** A stone block 32 x 24 x 32, hollowed out: glowstone and a sea lantern inside, a 3 x 3 hole in its roof. */
    private static void build(Cells out) {
        for (int x = 0; x < SIDE; x++) {
            for (int z = 0; z < SIDE; z++) {
                for (int y = 0; y < HEIGHT; y++) out.set(x, y, z, STONE);
            }
        }
        for (int x = 4; x < SIDE - 4; x++) {
            for (int z = 4; z < SIDE - 4; z++) {
                for (int y = 4; y < HEIGHT - 4; y++) out.set(x, y, z, AIR);
            }
        }
        for (int x = 14; x <= 16; x++) {
            for (int z = 14; z <= 16; z++) {
                for (int y = HEIGHT - 4; y < HEIGHT; y++) out.set(x, y, z, AIR);
            }
        }
        out.set(6, 4, 6, Blocks.GLOWSTONE.defaultBlockState());
        out.set(24, 10, 9, Blocks.GLOWSTONE.defaultBlockState());
        out.set(10, 18, 25, Blocks.SEA_LANTERN.defaultBlockState());
    }

    /** The first glowstone goes and the roof's hole closes. */
    private static void darken(Cells out) {
        out.set(6, 4, 6, AIR);
        for (int x = 14; x <= 16; x++) {
            for (int z = 14; z <= 16; z++) {
                for (int y = HEIGHT - 4; y < HEIGHT; y++) out.set(x, y, z, STONE);
            }
        }
    }

    private static Box around(int ax, int az, int bx) {
        return box(ax - MARGIN - 16, Y0 - MARGIN, az - MARGIN - 16, bx + SIDE + MARGIN + 16, Y0 + HEIGHT + MARGIN,
                az + SIDE + MARGIN + 16);
    }

    private static void write(BlockWriter writer, int x, int y, int z, BlockState state) {
        writer.write(x, y, z, Block.getId(state), null);
    }

    /** Vanilla's way: one light check per changed cell. */
    private static void set(ServerLevel world, int x, int y, int z, BlockState state) {
        world.setBlock(new BlockPos(x, y, z), state, Block.UPDATE_KNOWN_SHAPE, 0);
    }

    private static void vanilla(Runnable writes) {
        try (EditScope scope = EditScope.suppressPhysics()) {
            writes.run();
        }
    }

    /** Copies the batched copy's blocks (in and around the structure) onto the vanilla copy where they differ. */
    private static void copyBlocks(ServerLevel world, int ax, int bx, int az) {
        for (int x = -MARGIN; x < SIDE + MARGIN; x++) {
            for (int z = -MARGIN; z < SIDE + MARGIN; z++) {
                for (int y = -MARGIN; y < HEIGHT + MARGIN; y++) {
                    BlockState state = world.getBlockState(new BlockPos(ax + x, Y0 + y, az + z));
                    BlockPos b = new BlockPos(bx + x, Y0 + y, az + z);
                    if (world.getBlockState(b) != state) world.setBlock(b, state, Block.UPDATE_KNOWN_SHAPE, 0);
                }
            }
        }
    }

    private static void compare(ServerLevel world, int ax, int bx, int az) {
        for (int x = -MARGIN; x < SIDE + MARGIN; x++) {
            for (int z = -MARGIN; z < SIDE + MARGIN; z++) {
                for (int y = -MARGIN; y < HEIGHT + MARGIN; y++) {
                    BlockPos a = new BlockPos(ax + x, Y0 + y, az + z), b = new BlockPos(bx + x, Y0 + y, az + z);
                    for (LightLayer type : LightLayer.values()) {
                        int batched = world.getBrightness(type, a), vanilla = world.getBrightness(type, b);
                        if (batched != vanilla) {
                            throw new GameTestAssertException(type + " light at " + x + ", " + y + ", " + z + ": " + batched
                                    + " batched, " + vanilla + " the vanilla way");
                        }
                    }
                }
            }
        }
    }

    /** Whether the fill (stone over the copy at (ax, az) and around it) has written any cell of the air around it. */
    private static boolean wroteAny(ServerLevel world, int ax, int az) {
        for (int x = -8; x < SIDE + 8; x++) {
            for (int z = -8; z < SIDE + 8; z++) {
                for (int y = 2; y <= HEIGHT + 8; y++) {
                    boolean inside = x >= 0 && x < SIDE && z >= 0 && z < SIDE && y < HEIGHT;
                    if (!inside && world.getBlockState(new BlockPos(ax + x, Y0 + y, az + z)).is(Blocks.STONE)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Cells in and around the batched copy with block light. */
    private static int lit(ServerLevel world, int ax, int az) {
        int lit = 0;
        for (int x = -MARGIN; x < SIDE + MARGIN; x++) {
            for (int z = -MARGIN; z < SIDE + MARGIN; z++) {
                for (int y = -MARGIN; y < HEIGHT + MARGIN; y++) {
                    if (world.getBrightness(LightLayer.BLOCK, new BlockPos(ax + x, Y0 + y, az + z)) > 0) lit++;
                }
            }
        }
        return lit;
    }

    /** Sky and block light in and around the copy at (ax, az), by [type][x][y][z]. */
    private static int[][][][] lightOf(ServerLevel world, int ax, int az) {
        int w = SIDE + 2 * MARGIN, hgt = HEIGHT + 2 * MARGIN;
        int[][][][] light = new int[2][w][hgt][w];
        for (int t = 0; t < 2; t++) {
            LightLayer type = LightLayer.values()[t];
            for (int x = 0; x < w; x++) {
                for (int y = 0; y < hgt; y++) {
                    for (int z = 0; z < w; z++) {
                        light[t][x][y][z] = world.getBrightness(type,
                                new BlockPos(ax - MARGIN + x, Y0 - MARGIN + y, az - MARGIN + z));
                    }
                }
            }
        }
        return light;
    }

    /**
     * Steps run one after another on a ticking server: each makes its edits, waits until it is done (if it says when)
     * and the light engine has worked out every check queued before markers on all of {@code area}'s chunks, then
     * checks. A check runs once; its failure stays (the task is retried every tick while it throws).
     */
    private static final class Steps {
        private final GameTestHelper context;
        private final ThreadedLevelLightEngine light;
        private final Box area;
        private final Runnable cleanup;
        private final List<Runnable> edits = new ArrayList<>();
        private final List<Runnable> checks = new ArrayList<>();
        private final List<BooleanSupplier> dones = new ArrayList<>();
        private int step = -1;
        private boolean editsDone;
        private List<CompletableFuture<?>> markers;
        private int settledTicks;
        private String failure;
        private boolean finished;

        Steps(GameTestHelper context, ServerLevel world, Box area, Runnable cleanup) {
            this.context = context;
            this.light = world.getChunkSource().getLightEngine();
            this.area = area;
            this.cleanup = cleanup;
        }

        void add(Runnable edit, Runnable check) {
            add(edit, check, () -> true);
        }

        void add(Runnable edit, Runnable check, BooleanSupplier done) {
            edits.add(edit);
            checks.add(check);
            dones.add(done);
        }

        void start() {
            context.succeedWhen(this::tick);
        }

        private void tick() {
            if (failure != null) throw new GameTestAssertException(failure);
            if (finished) return;
            try {
                if (step < 0 || markers != null && settled()) {
                    if (step >= 0) checks.get(step).run();
                    step++;
                    markers = null;
                    editsDone = false;
                    if (step == edits.size()) {
                        finished = true;
                        cleanup.run();
                        return;
                    }
                    edits.get(step).run();
                }
                if (markers == null && dones.get(step).getAsBoolean()) {
                    markers = new ArrayList<>();
                    for (int cx = area.min().x() >> 4; cx <= area.max().x() >> 4; cx++) {
                        for (int cz = area.min().z() >> 4; cz <= area.max().z() >> 4; cz++) {
                            markers.add(light.waitForPendingTasks(cx, cz));
                        }
                    }
                    settledTicks = 0;
                }
            } catch (GameTestAssertException e) {
                failure = "step " + (step + 1) + ": " + e.getMessage();
                finished = true;
                cleanup.run();
                throw e;
            }
            throw new GameTestAssertException("step " + (step + 1) + " is not done yet");
        }

        /** Every check queued before the markers is worked out once each marker has run and no update is left. */
        private boolean settled() {
            boolean done = markers.stream().allMatch(CompletableFuture::isDone) && !light.hasLightWork();
            if (!done) {
                settledTicks = 0;
                return false;
            }
            return ++settledTicks >= 5;
        }
    }

    /** Live instances of vanilla's light tasks ({@code ServerLightingProvider} lambdas; a full GC). */
    static long lightTaskObjects() {
        try {
            String out = (String) ManagementFactory.getPlatformMBeanServer().invoke(
                    new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcClassHistogram",
                    new Object[] {new String[0]}, new String[] {String[].class.getName()});
            long n = 0;
            for (String line : out.split("\n")) {
                if (!line.contains("net.minecraft.server.level.ThreadedLevelLightEngine$$Lambda")) continue;
                n += Long.parseLong(line.trim().split(" +")[1]);
            }
            return n;
        } catch (Exception e) {
            throw new GameTestAssertException("no class histogram: " + e);
        }
    }
}
