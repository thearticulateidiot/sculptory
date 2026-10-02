package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.dab;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.handle;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.engine.impl.JobRequest;
import dev.sculptory.fabric.gametest.BenchSupport.LightWait;
import dev.sculptory.fabric.gametest.BenchSupport.Watcher;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EngineTestSupport.BoxFill;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.ClientSync;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Bulk edits reach watching players exactly as the server has them ({@link ClientSync}): a mock player on an embedded
 * channel feeds every packet it is sent into a {@link ClientView}, which must then match the world block for block over
 * the whole height of the edited columns (and in the client data of signs, and in light where the model can tell).
 * Heavy edits arrive as whole columns (with their light after the light engine), light ones as vanilla block updates,
 * and a player who may hold brush predictions always gets block updates. Cancelled and failing jobs, watchers that
 * join mid-job and columns written over many ticks are covered too.
 *
 * <p>The outcome must not depend on how loaded the machine is. Whether a column goes out whole depends on how many of
 * its cells one flush holds, and the server's executor writes for a time budget per tick, so under load its jobs
 * spread over more ticks and columns can stay under the resend threshold. So every job here runs on an executor of
 * the test's own ({@link #privateExecutor}) that only a cell cap limits, ticked by the test ({@link #tick}); only
 * {@link #resentColumnsFollowTheTicksEarlierPackets} uses the server's executor, to check the mod's own tick hooks,
 * and it writes in one go. Packets sent during a test step reach the watcher's channel only when the server flushes
 * its connections, after the tick's test steps, so a step that reads what a watcher got waits a tick after the step
 * that sent it ({@code expectMinDuration(1)}). Steps that edit run once ({@code createAndAddReported}): a failed check
 * fails the test at once instead of repeating the edit next tick.
 */
public final class ClientSyncGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** 3 × 3 chunk columns of 16 × 40 × 16 = 10,240 cells each: every column crosses the resend threshold. */
    private static Box heavyRegion(TestContext context, int slot) {
        int[] at = regionCorner(context, slot);
        return box(at[0], 100, at[1], at[0] + 47, 139, at[1] + 47);
    }

    /** Varied blocks (so every column really changes) and a few signs with text, written directly. */
    private static List<BlockPos> terrainWithSigns(Harness h, Box region) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int dirt = h.state("minecraft:dirt"), planks = h.state("minecraft:oak_planks");
        for (int x = region.min().x(); x <= region.max().x(); x++) {
            for (int z = region.min().z(); z <= region.max().z(); z++) {
                int top = region.min().y() + Math.floorMod(x * 7 + z * 3, 12);
                for (int y = region.min().y(); y <= top; y++) {
                    writer.write(x, y, z, Math.floorMod(x + z, 5) == 0 ? planks : dirt, null);
                }
            }
        }
        List<BlockPos> signs = new ArrayList<>();
        int sign = h.state("minecraft:oak_sign[rotation=4,waterlogged=false]");
        for (int i = 0; i < 3; i++) {
            BlockPos at = pos(region.min().x() + 5 + i * 16, region.max().y() - 2, region.min().z() + 7 + i * 16);
            writer.write(at.getX(), at.getY(), at.getZ(), sign, null);
            ((SignBlockEntity) h.world.getBlockEntity(at)).setText(
                    new SignText().withMessage(0, Text.literal("sync " + i)).withMessage(1, Text.literal("Sculptory")), true);
            signs.add(at);
        }
        return signs;
    }

    /** The edited columns of {@code region}, grown by {@code chunks} columns, over the whole world height. */
    private static Box columns(ServerWorld world, Box region, int chunks) {
        return box(((region.min().x() >> 4) - chunks) << 4, world.getBottomY(), ((region.min().z() >> 4) - chunks) << 4,
                (((region.max().x() >> 4) + chunks) << 4) + 15, world.getTopY() - 1, (((region.max().z() >> 4) + chunks) << 4) + 15);
    }

    private static Box grow(Box box, int by) {
        return box(box.min().x() - by, box.min().y() - by, box.min().z() - by, box.max().x() + by, box.max().y() + by,
                box.max().z() + by);
    }

    private static Watcher watch(ServerWorld world, Box region, ClientView view) {
        int cx = (region.min().x() + region.max().x()) / 2, cz = (region.min().z() + region.max().z()) / 2;
        return Watcher.join(world, cx, region.max().y() + 8, cz, 5).observe(view);
    }

    /** The view must match the world over the full height of the edited columns. */
    private static void checkView(ServerWorld world, ClientView view, Watcher watcher, Box region, String what) {
        watcher.drain();
        String difference = view.differenceFrom(columns(world, region, 0));
        check(difference == null, what + ": the client view differs from the world: " + difference);
    }

    /** The server's light in the edited columns and the ring around them. */
    private static java.util.Map<String, byte[]> serverLight(ClientView view, Box region) {
        return view.serverLight((region.min().x() >> 4) - 1, (region.min().z() >> 4) - 1, (region.max().x() >> 4) + 1,
                (region.max().z() >> 4) + 1);
    }

    /**
     * Every light section of the edited columns and the ring around them that the edit changed on the server (against
     * {@code before}) must hold the server's light in the view.
     */
    private static void checkLight(ClientView view, Watcher watcher, Box region, java.util.Map<String, byte[]> before,
                                   String what) {
        watcher.drain();
        String difference = view.lightDifferenceFrom((region.min().x() >> 4) - 1, (region.min().z() >> 4) - 1,
                (region.max().x() >> 4) + 1, (region.max().z() >> 4) + 1, before, true);
        check(difference == null, what + ": the client's light differs from the server's: " + difference);
    }

    /** A private executor's time budget per tick: an hour, so it never runs out and only the cell cap counts. */
    private static final long NO_TIME_LIMIT_NANOS = 3_600_000_000_000L;

    /**
     * An executor of the test's own, ticked (and flushed) by the test ({@link #tick}). It writes {@code maxBlocksPerTick}
     * cells a tick (0: a whole job in one tick) however loaded the machine is.
     */
    private static EditExecutor privateExecutor(EngineRuntime runtime, ServerWorld world, long maxBlocksPerTick) {
        return new EditExecutor(world.getServer(), runtime.states(), new EditExecutor.Settings(NO_TIME_LIMIT_NANOS,
                maxBlocksPerTick, 0.4, 2, 8, 32, 64, UnloadedPolicy.LOAD, 1024, 16_384));
    }

    /** One tick of a private executor: its work (as at {@code START_SERVER_TICK}), then its flush (as at the end). */
    private static void tick(EditExecutor executor) {
        executor.tick();
        executor.endTick();
    }

    private static void submit(EditExecutor executor, JobRequest request) {
        try {
            executor.submit(request);
        } catch (EditRejected e) {
            throw new GameTestException("rejected: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- tests

    /**
     * A heavy fill is sent as whole columns (one per column, at the end of the tick it was written in, not before) and
     * its undo too; after each, the client view matches the world over the full height of the columns, signs restored
     * by the undo included.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_heavy", tickLimit = LIMIT)
    public void heavyEditsResendWholeColumnsExactly(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        EditExecutor executor = privateExecutor(runtime, context.getWorld(), 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        Box region = heavyRegion(context, 320);
        loadAndForce(h.world, grow(region, 16));
        List<BlockPos> signs = terrainWithSigns(h, region);
        ClientView view = new ClientView(h.world);
        Watcher watcher = watch(h.world, region, view);
        ClientSync sync = executor.clientSync(h.world);
        long[] resentBefore = new long[1];
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(watcher.ready(region), "the watcher is still loading chunks"))
                .createAndAddReported(() -> {
                    checkView(h.world, view, watcher, region, "before the fill");
                    view.resetCounts();
                    resentBefore[0] = sync.columnsResent();
                    h.fill(region, "minecraft:stone", fill);
                    executor.tick(); // the whole fill
                    check(fill.result != null && fill.result.outcome() == JobOutcome.COMPLETED, "fill " + fill.result);
                    check(!sync.isEmpty() && sync.columnsResent() == resentBefore[0],
                            "the fill's cells went out before the end of the tick");
                    executor.endTick();
                    check(sync.isEmpty() && sync.columnsResent() - resentBefore[0] == 9,
                            (sync.columnsResent() - resentBefore[0]) + " columns resent for 9 heavily changed ones");
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    checkView(h.world, view, watcher, region, "after the fill");
                    check(view.chunkPackets >= 9, view.chunkPackets + " chunk packets for 9 heavily changed columns");
                    for (BlockPos at : signs) check(h.world.getBlockState(at).isOf(Blocks.STONE), "sign left at " + at);
                    view.resetCounts();
                    resentBefore[0] = sync.columnsResent();
                    h.undo(undo);
                    tick(executor);
                    check(undo.result != null && undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    check(sync.isEmpty() && sync.columnsResent() - resentBefore[0] == 9,
                            (sync.columnsResent() - resentBefore[0]) + " columns resent for the undo");
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    checkView(h.world, view, watcher, region, "after the undo");
                    check(view.chunkPackets >= 9, view.chunkPackets + " chunk packets for the undo");
                    for (BlockPos at : signs) {
                        check(view.blockEntity(at) != null && view.blockEntity(at).toString().contains("sync"),
                                "the client has no sign text at " + at.toShortString() + ": " + view.blockEntity(at));
                    }
                    watcher.close();
                    executor.shutdown();
                    forceChunks(h.world, grow(region, 16), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A light edit (512 cells in one column) keeps vanilla's block updates: no chunk packet. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_light", tickLimit = LIMIT)
    public void lightEditsKeepVanillaBlockUpdates(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        EditExecutor executor = privateExecutor(runtime, context.getWorld(), 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        Box region = heavyRegion(context, 321);
        loadAndForce(h.world, grow(region, 16));
        terrainWithSigns(h, region);
        Box small = box(region.min().x() + 2, region.min().y() + 2, region.min().z() + 2, region.min().x() + 9,
                region.min().y() + 9, region.min().z() + 9);
        ClientView view = new ClientView(h.world);
        Watcher watcher = watch(h.world, region, view);
        ClientSync sync = executor.clientSync(h.world);
        long[] before = new long[2];
        RecordingListener fill = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(watcher.ready(region), "the watcher is still loading chunks"))
                .createAndAddReported(() -> {
                    checkView(h.world, view, watcher, region, "before the fill");
                    view.resetCounts();
                    before[0] = sync.columnsResent();
                    before[1] = sync.cellsMarked();
                    h.fill(small, "minecraft:gold_block", fill);
                    tick(executor);
                    check(fill.result != null && fill.result.outcome() == JobOutcome.COMPLETED
                            && fill.result.changed() > 0, "fill " + fill.result);
                    check(sync.isEmpty(), "cells left unsent after the fill");
                    check(sync.columnsResent() == before[0], "a light edit was resent as a column");
                    check(sync.cellsMarked() - before[1] == fill.result.changed(), (sync.cellsMarked() - before[1])
                            + " cells marked for " + fill.result.changed() + " changed");
                })
                // Vanilla sends the marked cells in the next tick's world tick.
                .createAndAdd(() -> check(view.deltaPackets > 0 || watcherDrained(watcher, view), "deltas pending"))
                .createAndAddReported(() -> {
                    checkView(h.world, view, watcher, region, "after the fill");
                    check(view.chunkPackets == 0 && view.deltaPackets > 0, view.chunkPackets + " chunk packets, "
                            + view.deltaPackets + " delta packets");
                    watcher.close();
                    executor.shutdown();
                    forceChunks(h.world, grow(region, 16), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    private static boolean watcherDrained(Watcher watcher, ClientView view) {
        watcher.drain();
        return view.deltaPackets > 0;
    }

    /**
     * A player who just sent brush dabs (through {@code EngineEditService.dabs}, refused here: they have no stroke) may
     * hold predictions, so a heavy fill and its undo reach them as block updates (with sign data as block-entity
     * updates) while another watcher gets whole columns; both views match.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_predicting", tickLimit = LIMIT)
    public void predictingPlayersGetBlockUpdates(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        EditExecutor executor = privateExecutor(runtime, context.getWorld(), 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        Box region = heavyRegion(context, 322);
        loadAndForce(h.world, grow(region, 16));
        List<BlockPos> signs = terrainWithSigns(h, region);
        ClientView predictingView = new ClientView(h.world);
        ClientView plainView = new ClientView(h.world);
        Watcher predicting = watch(h.world, region, predictingView);
        Watcher plain = watch(h.world, region, plainView);
        ClientSync sync = executor.clientSync(h.world);
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        Runnable sendDabs = () -> h.service.dabs(predicting.player, 99, 1,
                List.of(dab(0, region.min().x(), region.max().y() + 2, region.min().z())));
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(predicting.ready(region) & plain.ready(region), "the watchers are still loading chunks"))
                .createAndAddReported(() -> {
                    checkView(h.world, predictingView, predicting, region, "predicting, before");
                    checkView(h.world, plainView, plain, region, "plain, before");
                    predictingView.resetCounts();
                    plainView.resetCounts();
                    sendDabs.run();
                    check(executor.mayHavePredictions(predicting.player) && !executor.mayHavePredictions(plain.player),
                            "prediction bookkeeping");
                    h.fill(region, "minecraft:stone", fill);
                    tick(executor);
                    check(fill.result != null && fill.result.outcome() == JobOutcome.COMPLETED, "fill " + fill.result);
                    check(sync.isEmpty(), "cells left unsent after the fill");
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    checkView(h.world, predictingView, predicting, region, "predicting, after the fill");
                    checkView(h.world, plainView, plain, region, "plain, after the fill");
                    check(predictingView.chunkPackets == 0 && predictingView.deltaPackets > 0,
                            "predicting: " + predictingView.chunkPackets + " chunk packets");
                    check(plainView.chunkPackets >= 9, "plain: " + plainView.chunkPackets + " chunk packets");
                    predictingView.resetCounts();
                    plainView.resetCounts();
                    sendDabs.run();
                    h.undo(undo);
                    tick(executor);
                    check(undo.result != null && undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    check(sync.isEmpty(), "cells left unsent after the undo");
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    checkView(h.world, predictingView, predicting, region, "predicting, after the undo");
                    checkView(h.world, plainView, plain, region, "plain, after the undo");
                    check(predictingView.chunkPackets == 0 && predictingView.blockEntityPackets >= signs.size(),
                            "predicting: " + predictingView.chunkPackets + " chunk packets, "
                                    + predictingView.blockEntityPackets + " block entity packets");
                    check(plainView.chunkPackets >= 9, "plain: " + plainView.chunkPackets + " chunk packets for the undo");
                    predicting.close();
                    plain.close();
                    executor.shutdown();
                    forceChunks(h.world, grow(region, 16), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Protection: a heavy fill that may not write one column at all and half of another. The columns written (the half
     * one included) are resent as the world has them, so the client shows the protected cells unchanged.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_protected", tickLimit = LIMIT)
    public void protectedCellsReachTheClientUnchanged(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        EditExecutor executor = privateExecutor(runtime, context.getWorld(), 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        Box region = heavyRegion(context, 323);
        loadAndForce(world, grow(region, 16));
        terrainWithSigns(h, region);
        int cx = region.min().x() >> 4, cz = region.min().z() >> 4;
        long[] westHalf = new long[4];
        for (int bit = 0; bit < 256; bit++) {
            if ((bit & 15) < 8) westHalf[bit >>> 6] |= 1L << bit;
        }
        ChunkPermit half = new ChunkPermit.Columns(westHalf);
        ClientView view = new ClientView(world);
        Watcher watcher = watch(world, region, view);
        ClientSync sync = executor.clientSync(world);
        RecordingListener listener = new RecordingListener();
        long[] resentBefore = new long[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(watcher.ready(region), "the watcher is still loading chunks"))
                .createAndAddReported(() -> {
                    checkView(world, view, watcher, region, "before the fill");
                    view.resetCounts();
                    resentBefore[0] = sync.columnsResent();
                    BoxFill program = new BoxFill("protected", region, handle(runtime.states(), "minecraft:smooth_stone"));
                    submit(executor, JobRequest.system(world, program, listener).withPermits((x, z) -> {
                        if (x == cx && z == cz) return ChunkPermit.DENY;
                        if (x == cx + 1 && z == cz) return half;
                        return ChunkPermit.ALLOW;
                    }));
                    tick(executor);
                    check(listener.result != null && listener.result.outcome() == JobOutcome.COMPLETED,
                            "fill " + listener.result);
                    check(listener.result.skippedProtected() == 16 * 16 * 40 + 8 * 16 * 40,
                            "skipped " + listener.result.skippedProtected());
                    check(sync.isEmpty(), "cells left unsent after the fill");
                    // 7 columns written whole and the half one (5,120 cells); the denied one has nothing to send.
                    check(sync.columnsResent() - resentBefore[0] == 8, (sync.columnsResent() - resentBefore[0])
                            + " columns resent for 8 written ones");
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    checkView(world, view, watcher, region, "after the protected fill");
                    check(!world.getBlockState(pos(region.min().x() + 3, 120, region.min().z() + 3)).isOf(Blocks.SMOOTH_STONE),
                            "the denied column was written");
                    check(view.chunkPackets >= 8, view.chunkPackets + " chunk packets for 8 written columns");
                    watcher.close();
                    executor.shutdown();
                    forceChunks(world, grow(region, 16), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A resent column's light predates the light engine's work, and vanilla would correct it only for players at the
     * edge of their view: once the light engine has caught up, the light of the resent columns and of the ring around
     * them reaches the client and matches the server's (a 48 × 40 × 48 stone block high in the air shades the ground
     * below; its undo lights it again).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_resent_light", tickLimit = LIMIT)
    public void resentColumnsGetTheirLightAfterTheLightEngine(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        EditExecutor executor = privateExecutor(runtime, context.getWorld(), 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 325);
        Box region = box(at[0], 100, at[1], at[0] + 47, 139, at[1] + 47);
        loadAndForce(h.world, grow(region, 32));
        ClientView view = new ClientView(h.world);
        Watcher watcher = watch(h.world, region, view);
        ClientSync sync = executor.clientSync(h.world);
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        LightWait[] light = new LightWait[1];
        java.util.List<java.util.Map<String, byte[]>> before = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(watcher.ready(region), "the watcher is still loading chunks"))
                .createAndAdd(() -> light[0] = new LightWait(h.world, grow(region, 32)))
                .createAndAdd(() -> check(light[0].settled(), "light still settling before the fill"))
                .createAndAddReported(() -> {
                    watcher.drain();
                    view.resetCounts();
                    before.add(serverLight(view, region));
                    h.fill(region, "minecraft:stone", fill);
                    tick(executor); // the whole fill; resends the columns, queues the light follow-ups
                    check(fill.result != null && fill.result.outcome() == JobOutcome.COMPLETED, "fill " + fill.result);
                    check(sync.lightPending() == 9, sync.lightPending() + " light follow-ups for 9 resent columns");
                    light[0] = new LightWait(h.world, grow(region, 16));
                })
                .expectMinDuration(1)
                .createAndAdd(() -> {
                    executor.endTick();
                    check(light[0].settled() && sync.lightPending() == 0, "light still updating");
                })
                .expectMinDuration(1) // the light packets of the last flush reach the watcher at the tick's end
                .createAndAddReported(() -> {
                    checkView(h.world, view, watcher, region, "after the fill");
                    check(view.lightPackets >= 25, view.lightPackets + " light packets for 5 x 5 columns");
                    checkLight(view, watcher, region, before.get(0), "after the fill");
                    view.resetCounts();
                    before.add(serverLight(view, region));
                    h.undo(undo);
                    tick(executor);
                    check(undo.result != null && undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    light[0] = new LightWait(h.world, grow(region, 16));
                })
                .expectMinDuration(1)
                .createAndAdd(() -> {
                    executor.endTick();
                    check(light[0].settled() && sync.lightPending() == 0, "light still updating after the undo");
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    checkView(h.world, view, watcher, region, "after the undo");
                    check(view.lightPackets >= 25, view.lightPackets + " light packets for 5 x 5 columns after the undo");
                    checkLight(view, watcher, region, before.get(1), "after the undo");
                    watcher.close();
                    executor.shutdown();
                    forceChunks(h.world, grow(region, 32), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A column written over many ticks (4096 cells a tick, 81,920 cells over 20 ticks) is resent while the job runs,
     * at most once every {@value ClientSync#RESEND_INTERVAL_TICKS} ticks, and whole once the job ends; the client ends
     * up exact.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_throttle", tickLimit = LIMIT)
    public void columnsWrittenOverManyTicksAreResentSparingly(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        EditExecutor executor = privateExecutor(runtime, context.getWorld(), 4096);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 326);
        Box region = box(at[0], -48, at[1], at[0] + 15, 271, at[1] + 15); // one column, 20 sections of air
        loadAndForce(h.world, grow(region, 16));
        ClientView view = new ClientView(h.world);
        Watcher watcher = watch(h.world, region, view);
        ClientSync sync = executor.clientSync(h.world);
        RecordingListener fill = new RecordingListener();
        int[] ticks = {0};
        long[] resent = new long[2];
        int midTick = ClientSync.RESEND_INTERVAL_TICKS + 2;
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(watcher.ready(region), "the watcher is still loading chunks"))
                .createAndAdd(() -> {
                    view.resetCounts();
                    resent[0] = sync.columnsResent();
                    h.fill(region, "minecraft:stone", fill);
                })
                .createAndAdd(() -> {
                    // One executor tick per server tick, until the fill ends (a wait: its checks come after).
                    tick(executor);
                    ticks[0]++;
                    if (ticks[0] == midTick) resent[1] = sync.columnsResent() - resent[0];
                    check(fill.result != null, "fill running (tick " + ticks[0] + ")");
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED && fill.result.changed() == region.volume(),
                            "fill " + fill.result);
                    check(ticks[0] >= 20, "the fill took only " + ticks[0] + " ticks");
                    check(ticks[0] > midTick, "the fill ended before tick " + midTick);
                    check(resent[1] >= 2, resent[1] + " resends by tick " + midTick + " (expected at ticks 1 and 11)");
                    long resends = sync.columnsResent() - resent[0];
                    check(resends <= 4, resends + " resends of one column over " + ticks[0] + " ticks");
                    checkView(h.world, view, watcher, region, "after the fill");
                    watcher.close();
                    executor.shutdown();
                    forceChunks(h.world, grow(region, 16), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Writes that go out as vanilla block updates next to a resent column whose light is still pending (a few
     * glowstone blocks under a freshly resent 48 × 40 × 48 fill, in the next tick) re-arm the light follow-up behind
     * them, so the light clients finally get includes them: the resent columns and their ring match the server's light
     * exactly once the light engine has caught up.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_light_tail", tickLimit = LIMIT)
    public void writesNearAPendingResendAreLitBeforeTheLightIsSent(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        EditExecutor executor = privateExecutor(runtime, context.getWorld(), 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 328);
        Box region = box(at[0], 100, at[1], at[0] + 47, 139, at[1] + 47);
        Box lamps = box(at[0] + 2, 92, at[1] + 2, at[0] + 5, 95, at[1] + 5); // 64 cells: vanilla block updates
        loadAndForce(h.world, grow(region, 32));
        ClientView view = new ClientView(h.world);
        Watcher watcher = watch(h.world, region, view);
        ClientSync sync = executor.clientSync(h.world);
        RecordingListener fill = new RecordingListener();
        RecordingListener lamp = new RecordingListener();
        LightWait[] light = new LightWait[1];
        java.util.List<java.util.Map<String, byte[]>> before = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(watcher.ready(region), "the watcher is still loading chunks"))
                .createAndAdd(() -> light[0] = new LightWait(h.world, grow(region, 32)))
                .createAndAdd(() -> check(light[0].settled(), "light still settling before the fill"))
                .createAndAddReported(() -> {
                    watcher.drain();
                    view.resetCounts();
                    before.add(serverLight(view, region));
                    h.fill(region, "minecraft:stone", fill);
                    tick(executor); // resends the 9 columns, queues their light follow-ups
                    check(fill.result != null && fill.result.outcome() == JobOutcome.COMPLETED, "fill " + fill.result);
                    check(sync.lightPending() == 9, sync.lightPending() + " light follow-ups for 9 resent columns");
                    long rearms = sync.lightRearms();
                    h.fill(lamps, "minecraft:glowstone", lamp);
                    tick(executor); // the lamps go out as vanilla block updates
                    check(lamp.result != null && lamp.result.outcome() == JobOutcome.COMPLETED, "lamps " + lamp.result);
                    check(sync.lightRearms() > rearms, "the lamps did not re-arm the pending light follow-up");
                    light[0] = new LightWait(h.world, grow(region, 16));
                })
                .expectMinDuration(1)
                .createAndAdd(() -> {
                    executor.endTick();
                    check(light[0].settled() && sync.lightPending() == 0, "light still updating");
                })
                // The light packets of the last flush, and the lamps' block updates (sent by vanilla in the tick after
                // the lamps, at the earliest the one the wait ended in), reach the watcher at the tick's end.
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    checkView(h.world, view, watcher, region, "after the fill and the lamps");
                    check(view.deltaPackets > 0 || view.blockPackets > 0, "the lamps did not go out as block updates");
                    checkLight(view, watcher, region, before.get(0), "after the fill and the lamps");
                    watcher.close();
                    executor.shutdown();
                    forceChunks(h.world, grow(region, 32), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The same with every watcher predicting: a heavily changed column (4096 glowstone blocks under a freshly resent
     * fill) goes out as block updates only, which clients relight themselves, so it re-arms the pending light
     * follow-up too; the light clients finally get matches the server exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_light_predicting", tickLimit = LIMIT)
    public void predictedHeavyWritesNearAPendingResendAreLitBeforeTheLightIsSent(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        EditExecutor executor = privateExecutor(runtime, context.getWorld(), 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 330);
        Box region = box(at[0], 100, at[1], at[0] + 47, 139, at[1] + 47);
        Box lamps = box(at[0] + 16, 84, at[1] + 16, at[0] + 31, 99, at[1] + 31); // one section: 4096 cells
        loadAndForce(h.world, grow(region, 32));
        ClientView view = new ClientView(h.world);
        Watcher watcher = watch(h.world, region, view);
        ClientSync sync = executor.clientSync(h.world);
        RecordingListener fill = new RecordingListener();
        RecordingListener lamp = new RecordingListener();
        LightWait[] light = new LightWait[1];
        java.util.List<java.util.Map<String, byte[]>> before = new ArrayList<>();
        long[] counts = new long[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(watcher.ready(region), "the watcher is still loading chunks"))
                .createAndAdd(() -> light[0] = new LightWait(h.world, grow(region, 32)))
                .createAndAdd(() -> check(light[0].settled(), "light still settling before the fill"))
                .createAndAddReported(() -> {
                    watcher.drain();
                    view.resetCounts();
                    before.add(serverLight(view, region));
                    h.fill(region, "minecraft:stone", fill);
                    tick(executor); // resends the 9 columns, queues their light follow-ups
                    check(fill.result != null && fill.result.outcome() == JobOutcome.COMPLETED, "fill " + fill.result);
                    check(sync.lightPending() == 9, sync.lightPending() + " light follow-ups for 9 resent columns");
                    // From now on the watcher may hold predictions: heavy columns reach it as block updates.
                    executor.predicted(watcher.player.getUuid());
                    counts[0] = sync.lightRearms();
                    counts[1] = sync.columnsResent();
                    h.fill(lamps, "minecraft:glowstone", lamp);
                    tick(executor);
                    check(lamp.result != null && lamp.result.outcome() == JobOutcome.COMPLETED
                            && lamp.result.changed() == lamps.volume(), "lamps " + lamp.result);
                    check(sync.columnsResent() == counts[1], "the lamps' column was resent to a predicting watcher");
                    check(sync.lightRearms() > counts[0], "the predicted heavy column did not re-arm the light follow-up");
                    light[0] = new LightWait(h.world, grow(region, 16));
                })
                .expectMinDuration(1)
                .createAndAdd(() -> {
                    executor.endTick();
                    check(light[0].settled() && sync.lightPending() == 0, "light still updating");
                })
                .expectMinDuration(1) // the light packets of the last flush reach the watcher at the tick's end
                .createAndAddReported(() -> {
                    checkView(h.world, view, watcher, region, "after the fill and the lamps");
                    checkLight(view, watcher, region, before.get(0), "after the fill and the lamps");
                    watcher.close();
                    executor.shutdown();
                    forceChunks(h.world, grow(region, 32), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Bulk writes reach players at the end of the tick they were made in: after everything the tick sent before (here a
     * {@code PlayerActionResponseS2CPacket} sent during the tick, as vanilla's network tick sends prediction acks), so
     * a resent column never overtakes an ack of a block the server handled before it.
     *
     * <p>This one runs on the server's own executor, whose flush the mod's {@code END_SERVER_TICK} hook calls. Its jobs
     * write for a time budget, so how many cells of a column one tick holds (and so whether the column goes out whole)
     * depends on the machine's load; the cells are therefore written in one go, through that executor's
     * {@link ClientSync} as a job's writer does, every column far over the resend threshold. That the cells a job
     * writes wait for its executor's flush is checked in {@link #heavyEditsResendWholeColumnsExactly}.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_order", tickLimit = LIMIT)
    public void resentColumnsFollowTheTicksEarlierPackets(TestContext context) {
        Harness h = new Harness(context);
        Box region = heavyRegion(context, 329);
        loadAndForce(h.world, grow(region, 16));
        ClientView view = new ClientView(h.world);
        List<Object> order = new ArrayList<>();
        Watcher watcher = watch(h.world, region, view).observe(packet -> {
            order.add(packet);
            view.accept(packet);
        });
        ClientSync sync = h.runtime.executor().clientSync(h.world);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT).syncThrough(sync);
        int stone = h.state("minecraft:stone");
        long[] resentBefore = new long[1];
        int[] sentinelAt = {-1};
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(watcher.ready(region), "the watcher is still loading chunks"))
                .createAndAddReported(() -> {
                    check(sync.isEmpty(), "the server's executor holds unsent cells");
                    watcher.drain();
                    order.clear();
                    resentBefore[0] = sync.columnsResent();
                    for (int x = region.min().x(); x <= region.max().x(); x++) {
                        for (int z = region.min().z(); z <= region.max().z(); z++) {
                            for (int y = region.min().y(); y <= region.max().y(); y++) writer.write(x, y, z, stone, null);
                        }
                    }
                    // Inside the tick the cells were written in: they wait for the tick's end.
                    check(!sync.isEmpty() && sync.columnsResent() == resentBefore[0], "the cells went out at once");
                    watcher.drain();
                    sentinelAt[0] = order.size();
                    watcher.player.networkHandler.sendPacket(new PlayerActionResponseS2CPacket(4242));
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    check(sync.isEmpty() && sync.columnsResent() - resentBefore[0] == 9, "the server's executor resent "
                            + (sync.columnsResent() - resentBefore[0]) + " of 9 heavily changed columns at the tick's end");
                    watcher.drain();
                    int sentinel = -1;
                    int firstColumn = -1;
                    for (int i = 0; i < order.size(); i++) {
                        Object packet = order.get(i);
                        if (sentinel < 0 && packet instanceof PlayerActionResponseS2CPacket ack && ack.sequence() == 4242) {
                            sentinel = i;
                        }
                        if (firstColumn < 0 && packet instanceof ChunkDataS2CPacket) firstColumn = i;
                    }
                    // Unrelated packets may land between the drain and the ack; no block or column packet may.
                    check(sentinel >= sentinelAt[0], "the sentinel ack is at " + sentinel + ", sent at " + sentinelAt[0]);
                    for (int i = sentinelAt[0]; i < sentinel; i++) {
                        Object early = order.get(i);
                        check(!(early instanceof ChunkDataS2CPacket || early instanceof ChunkDeltaUpdateS2CPacket
                                || early instanceof BlockUpdateS2CPacket), "a block packet went out before the tick's ack: "
                                + early.getClass().getSimpleName());
                    }
                    check(firstColumn > sentinel, "a column was sent at " + firstColumn + ", before the tick's ack at "
                            + sentinel);
                    checkView(h.world, view, watcher, region, "after the writes");
                    watcher.close();
                    forceChunks(h.world, grow(region, 16), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Partial jobs: one cancelled after a few ticks, one whose program fails in its third section. What each wrote
     * before it ended reaches the client exactly, and so does a watcher that joined while a job was half done.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_sync_partial", tickLimit = LIMIT)
    public void cancelledAndFailedJobsAndLateWatchersStayExact(TestContext context) {
        EngineRuntime runtime = EngineTestSupport.runtime(context);
        ServerWorld world = context.getWorld();
        EditExecutor executor = privateExecutor(runtime, world, 4096);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        Box region = heavyRegion(context, 327);
        loadAndForce(world, grow(region, 16));
        terrainWithSigns(h, region);
        ClientView view = new ClientView(world);
        Watcher first = watch(world, region, view);
        ClientView lateView = new ClientView(world);
        Watcher[] late = new Watcher[1];
        RecordingListener cancelled = new RecordingListener();
        RecordingListener failed = new RecordingListener();
        UUID[] jobId = new UUID[1];
        Runnable tick = () -> tick(executor);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(first.ready(region), "the watcher is still loading chunks"))
                .createAndAddReported(() -> {
                    jobId[0] = h.fill(region, "minecraft:stone", cancelled).jobId();
                    for (int i = 0; i < 5; i++) tick.run();
                    check(executor.cancel(jobId[0]), "cancel refused");
                    for (int i = 0; i < 4 && cancelled.result == null; i++) tick.run(); // at the next section boundary
                    check(cancelled.result != null && cancelled.result.outcome() == JobOutcome.CANCELLED,
                            "cancel " + cancelled.result);
                    check(cancelled.result.changed() > 0 && cancelled.result.changed() < region.volume(),
                            "changed " + cancelled.result.changed());
                    tick.run(); // any column the job's end left over
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    checkView(world, view, first, region, "after the cancelled job");
                    BoxFill program = new BoxFill("fails", region, handle(runtime.states(), "minecraft:gold_block"));
                    program.onCompute = key -> {
                        if (program.computeCount == 3) throw new IllegalStateException("test failure in compute");
                    };
                    submit(executor, JobRequest.system(world, program, failed));
                    tick.run();
                    tick.run();
                    // A watcher joins while the job's first sections are written but not all flushed yet.
                    late[0] = watch(world, region, lateView);
                    for (int i = 0; i < 12 && failed.result == null; i++) tick.run();
                    check(failed.result != null && failed.result.outcome() == JobOutcome.FAILED, "failure " + failed.result);
                    check(failed.result.changed() > 0 && failed.result.changed() < region.volume(),
                            "changed " + failed.result.changed());
                    tick.run();
                })
                .expectMinDuration(1)
                .createAndAdd(() -> {
                    tick.run();
                    check(late[0].ready(region), "the late watcher is still loading chunks");
                })
                .expectMinDuration(1)
                .createAndAddReported(() -> {
                    checkView(world, view, first, region, "after the failed job");
                    checkView(world, lateView, late[0], region, "late watcher, after the failed job");
                    first.close();
                    late[0].close();
                    executor.shutdown();
                    forceChunks(world, grow(region, 16), false);
                    h.close();
                })
                .completeIfSuccessful();
    }
}
