package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.checkNoEditTickets;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.handle;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.EngineTestSupport.runtime;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.fabric.engine.ChunkPermit;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.JobTicket;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.engine.impl.JobRequest;
import dev.sculptory.fabric.gametest.EngineTestSupport.BoxFill;
import dev.sculptory.fabric.gametest.EngineTestSupport.CountingSink;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * The edit executor against a real server. Each large test works in its own far-away
 * region with forced chunks and runs in its own batch, so tests never share sections or executor limits.
 */
public final class ExecutorGameTest implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_exec_budget",
            tickLimit = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT)
    public void executorBudgetSpansTicks(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        int[] at = regionCorner(context, 1);
        Box region = box(at[0], 0, at[1], at[0] + 63, 47, at[1] + 63); // 196,608 cells in 48 sections
        forceChunks(world, region, true);
        BoxFill program = new BoxFill("budget fill", region, handle(runtime.states(), "minecraft:smooth_stone"));
        LongOpenHashSet computeTicks = new LongOpenHashSet();
        program.onCompute = key -> computeTicks.add(world.getServer().getTicks());
        RecordingListener listener = new RecordingListener();
        JobTicket ticket = submit(runtime, JobRequest.system(world, program, listener));
        check(ticket.estimatedCells() == region.volume(), "estimate");
        check(listener.progressEvents == 0, "listener called before submit returned");
        context.addInstantFinalTask(() -> {
            check(listener.result != null, "job still running");
            check(listener.result.outcome() == JobOutcome.COMPLETED, "outcome " + listener.result.outcome());
            check(listener.result.changed() == region.volume(), "changed " + listener.result.changed());
            check(listener.finishedCalls == 1, "finished called " + listener.finishedCalls + " times");
            check(computeTicks.size() >= 2, "the job ran within a single tick; the budget did not split it");
            check(listener.count(Phase.APPLY) >= 1, "no APPLY progress event");
            check(listener.phases.get(listener.phases.size() - 1) == Phase.FINALIZE, "last event is not FINALIZE");
            check(listener.lastDone == region.volume() && !listener.wentBackwards, "final progress " + listener.lastDone);
            for (BlockPos corner : corners(region)) {
                check(world.getBlockState(corner).isOf(Blocks.SMOOTH_STONE), "not filled at " + corner.toShortString());
            }
            checkNoEditTickets(runtime.executor(), world, region);
            check(!runtime.executor().isLocked(world, region), "section locks leaked");
            forceChunks(world, region, false);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_exec_overlap",
            tickLimit = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT)
    public void overlappingJobsSerialize(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        EditExecutor executor = runtime.executor();
        int[] at = regionCorner(context, 2);
        int x = at[0], z = at[1];
        Box a = box(x, 0, z, x + 31, 31, z + 31);
        Box b = box(x + 16, 0, z, x + 47, 31, z + 31); // overlaps A in x + 16..31
        Box c = box(x, 64, z, x + 31, 95, z + 31); // same chunks as A, disjoint sections
        Box all = box(x, 0, z, x + 47, 95, z + 31);
        forceChunks(world, all, true);
        BoxFill fillA = new BoxFill("A", a, handle(runtime.states(), "minecraft:smooth_stone"));
        BoxFill fillB = new BoxFill("B", b, handle(runtime.states(), "minecraft:polished_andesite"));
        BoxFill fillC = new BoxFill("C", c, handle(runtime.states(), "minecraft:polished_granite"));
        RecordingListener listenerA = new RecordingListener();
        RecordingListener listenerB = new RecordingListener();
        RecordingListener listenerC = new RecordingListener();
        boolean[] bStartedBeforeAFinished = {false};
        boolean[] cStartedWhileARunning = {false};
        fillB.onCompute = key -> {
            if (listenerA.result == null) bStartedBeforeAFinished[0] = true;
        };
        fillC.onCompute = key -> {
            if (fillC.computeCount == 1) cStartedWhileARunning[0] = listenerA.result == null;
        };
        submit(runtime, JobRequest.system(world, fillA, listenerA));
        submit(runtime, JobRequest.system(world, fillB, listenerB));
        submit(runtime, JobRequest.system(world, fillC, listenerC));
        long overlapKey = BlockBuffer.keyOfBlock(x + 20, 5, z + 5);
        check(executor.isLocked(world, overlapKey), "overlap section is not locked after admission");
        check(!executor.isLocked(world, BlockBuffer.keyOfBlock(x + 200, 5, z + 5)), "unrelated section is locked");

        // Brush lane: whole items run first on the next tick, in order.
        List<Integer> brushOrder = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int n = i;
            executor.submitBrush(() -> brushOrder.add(n));
        }
        check(executor.brushQueueSize() == 3, "brush work not queued");

        context.addInstantFinalTask(() -> {
            check(listenerA.result != null && listenerB.result != null && listenerC.result != null, "jobs still running");
            check(brushOrder.equals(List.of(0, 1, 2)), "brush lane order " + brushOrder);
            check(listenerB.count(Phase.QUEUED) == 1, "B was not reported QUEUED");
            check(!bStartedBeforeAFinished[0], "B computed a section before A finished");
            check(cStartedWhileARunning[0], "C (disjoint) did not run alongside A");
            for (RecordingListener l : List.of(listenerA, listenerB, listenerC)) {
                check(l.result.outcome() == JobOutcome.COMPLETED, "outcome " + l.result.outcome());
            }
            check(world.getBlockState(pos(x + 20, 5, z + 5)).isOf(Blocks.POLISHED_ANDESITE), "overlap is not B's");
            check(world.getBlockState(pos(x + 5, 5, z + 5)).isOf(Blocks.SMOOTH_STONE), "A-only cell");
            check(world.getBlockState(pos(x + 40, 5, z + 5)).isOf(Blocks.POLISHED_ANDESITE), "B-only cell");
            check(world.getBlockState(pos(x + 5, 70, z + 5)).isOf(Blocks.POLISHED_GRANITE), "C cell");
            check(!executor.isLocked(world, overlapKey), "locks not released");
            forceChunks(world, all, false);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_exec_cancel",
            tickLimit = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT)
    public void cancelMidJob(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        EditExecutor executor = runtime.executor();
        int[] at = regionCorner(context, 3);
        Box region = box(at[0], 0, at[1], at[0] + 63, 31, at[1] + 63); // 32 sections of 4096 cells
        forceChunks(world, region, true);
        BoxFill program = new BoxFill("cancel me", region, handle(runtime.states(), "minecraft:smooth_stone"));
        UUID[] id = new UUID[1];
        program.onCompute = key -> {
            if (program.computeCount == 5) check(executor.cancel(id[0]), "cancel returned false");
        };
        RecordingListener listener = new RecordingListener();
        CountingSink sink = new CountingSink();
        id[0] = submit(runtime, JobRequest.system(world, program, listener).withRecords(sink)).jobId();

        // A queued job (behind the first one's locks) is cancelled at once, before touching anything.
        BoxFill queuedProgram = new BoxFill("queued", region, handle(runtime.states(), "minecraft:gold_block"));
        RecordingListener queuedListener = new RecordingListener();
        UUID queued = submit(runtime, JobRequest.system(world, queuedProgram, queuedListener)).jobId();
        check(executor.cancel(queued), "queued cancel returned false");
        check(queuedListener.result != null && queuedListener.result.outcome() == JobOutcome.CANCELLED
                && queuedListener.result.changed() == 0, "queued job did not end as CANCELLED");
        check(!executor.cancel(queued), "second cancel should report false");

        context.addInstantFinalTask(() -> {
            check(listener.result != null, "job still running");
            check(listener.result.outcome() == JobOutcome.CANCELLED, "outcome " + listener.result.outcome());
            check(program.computeCount == 5, "sections computed after cancel: " + program.computeCount);
            check(listener.result.changed() == 5 * 4096L, "changed " + listener.result.changed());
            check(sink.records == listener.result.changed(), "partial record has " + sink.records + " cells");
            check(queuedProgram.computeCount == 0, "cancelled queued job computed");
            long[] order = program.sectionOrder();
            for (int s = 0; s < order.length; s++) {
                long filled = countFilled(world, order[s], Blocks.SMOOTH_STONE);
                long expected = s < 5 ? 4096 : 0;
                check(filled == expected, "section " + s + " has " + filled + " filled cells, expected " + expected);
                check(sink.sections.contains(order[s]) == (s < 5), "record membership of section " + s);
            }
            check(!executor.isLocked(world, region), "locks not released");
            checkNoEditTickets(executor, world, region);
            forceChunks(world, region, false);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_exec_protect",
            tickLimit = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT)
    public void protectedCellsAreSkipped(TestContext context) {
        // Spawn protection cannot be set up in GameTest (TestServer.isSpawnProtected is always false), so the
        // corner rule is JUnit-tested (ChunkPermitsTest) and this drives the executor with a synthetic permit.
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        int[] at = regionCorner(context, 4);
        int x0 = at[0], z0 = at[1];
        Box region = box(x0, 0, z0, x0 + 31, 15, z0 + 31); // 2 x 2 chunks, one section tall
        int cx = x0 >> 4, cz = z0 >> 4;
        forceChunks(world, region, true);
        long[] westHalf = new long[4];
        for (int bit = 0; bit < 256; bit++) {
            if ((bit & 15) < 8) westHalf[bit >>> 6] |= 1L << bit;
        }
        ChunkPermit half = new ChunkPermit.Columns(westHalf);
        BoxFill program = new BoxFill("protected", region, handle(runtime.states(), "minecraft:smooth_stone"));
        RecordingListener listener = new RecordingListener();
        submit(runtime, JobRequest.system(world, program, listener).withPermits((x, z) -> {
            if (x == cx && z == cz) return ChunkPermit.DENY;
            if (x == cx + 1 && z == cz) return half;
            return ChunkPermit.ALLOW;
        }));
        context.addInstantFinalTask(() -> {
            check(listener.result != null, "job still running");
            check(listener.result.outcome() == JobOutcome.COMPLETED, "outcome " + listener.result.outcome());
            check(listener.result.skippedProtected() == 4096 + 2048, "skipped " + listener.result.skippedProtected());
            check(listener.result.changed() == region.volume() - 6144, "changed " + listener.result.changed());
            check(!world.getBlockState(pos(x0 + 3, 3, z0 + 3)).isOf(Blocks.SMOOTH_STONE), "denied chunk was written");
            check(world.getBlockState(pos(x0 + 16 + 3, 3, z0 + 3)).isOf(Blocks.SMOOTH_STONE), "allowed column skipped");
            check(!world.getBlockState(pos(x0 + 16 + 12, 3, z0 + 3)).isOf(Blocks.SMOOTH_STONE), "denied column written");
            check(world.getBlockState(pos(x0 + 20, 3, z0 + 20)).isOf(Blocks.SMOOTH_STONE), "allowed chunk skipped");
            forceChunks(world, region, false);
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_exec_queue")
    public void queueFullRejects(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        EditExecutor executor = runtime.executor();
        BlockPos cell = context.getAbsolutePos(new BlockPos(1, 1, 1));
        Box one = box(cell.getX(), cell.getY(), cell.getZ(), cell.getX(), cell.getY(), cell.getZ());
        int stone = handle(runtime.states(), "minecraft:stone");
        List<UUID> admitted = new ArrayList<>();
        int limit = executor.settings().maxQueued();
        for (int i = 0; i < limit; i++) {
            admitted.add(submit(runtime, JobRequest.system(world, new BoxFill("q" + i, one, stone), null)).jobId());
        }
        try {
            executor.submit(JobRequest.system(world, new BoxFill("overflow", one, stone), null));
            throw new GameTestException("job " + (limit + 1) + " was admitted past the queue limit");
        } catch (EditRejected e) {
            check(e.reason() == RejectReason.QUEUE_FULL, "reason " + e.reason());
        }
        for (UUID id : admitted) check(executor.cancel(id), "cancel " + id);
        check(executor.queuedJobCount() == 0 && !executor.isLocked(world, one), "queue not drained");
        Box outside = box(cell.getX(), 10_000, cell.getZ(), cell.getX(), 10_001, cell.getZ());
        try {
            executor.submit(JobRequest.system(world, new BoxFill("outside", outside, stone), null));
            throw new GameTestException("a job above the build limit was admitted");
        } catch (EditRejected e) {
            check(e.reason() == RejectReason.INVALID, "reason " + e.reason());
        }
        context.complete();
    }

    static JobTicket submit(EngineRuntime runtime, JobRequest request) {
        try {
            return runtime.executor().submit(request);
        } catch (EditRejected e) {
            throw new GameTestException("rejected: " + e.getMessage());
        }
    }

    private static long countFilled(ServerWorld world, long sectionKey, Block block) {
        int ox = BlockBuffer.keyX(sectionKey) << 4, oy = BlockBuffer.keyY(sectionKey) << 4;
        int oz = BlockBuffer.keyZ(sectionKey) << 4;
        long count = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (world.getBlockState(pos.set(ox + x, oy + y, oz + z)).isOf(block)) count++;
                }
            }
        }
        return count;
    }

    private static List<BlockPos> corners(Box box) {
        List<BlockPos> corners = new ArrayList<>();
        for (int x : new int[] {box.min().x(), box.max().x()}) {
            for (int y : new int[] {box.min().y(), box.max().y()}) {
                for (int z : new int[] {box.min().z(), box.max().z()}) corners.add(pos(x, y, z));
            }
        }
        return corners;
    }
}
