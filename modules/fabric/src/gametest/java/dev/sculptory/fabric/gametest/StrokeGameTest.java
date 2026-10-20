package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.dab;
import static dev.sculptory.fabric.gametest.EditTestSupport.difference;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.BoxFill;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.engine.impl.JobRequest;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/** Brush strokes through {@code EngineEditService}: the brush lane, acknowledgements, admission and history. */
public final class StrokeGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int BASE = 100;

    /** One stroke of several dab batches is one history entry, and undoing it restores the terrain exactly. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_stroke_undo", timeoutTicks = LIMIT)
    public void strokeCoalescesToOneUndo(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 18);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 170, z0 + 47);
        loadAndForce(world, area);
        terrain(h, x0, z0, 48, 48);
        WorldSnapshot before = capture(world, area);
        BrushSpec spec = new BrushSpec(BrushTool.RAISE, 5, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L);
        begin(h, 7, spec);
        int index = 0;
        for (int batch = 0; batch < 3; batch++) {
            List<Dab> dabs = new ArrayList<>();
            // Overlapping dabs: many cells are written by several dabs of the stroke.
            for (int i = 0; i < 10; i++, index++) dabs.add(dab(index, x0 + 14 + index % 20, 110, z0 + 16 + index / 3));
            DabOutcome outcome = h.service.dabs(h.player, 7, 100 + batch, dabs);
            check(outcome.accepted() && outcome.lastIndex() == index - 1, "batch " + batch + ": " + outcome);
        }
        RecordingListener undo = new RecordingListener();
        int[] changedCells = {0};
        context.startSequence()
                .thenWaitUntil(() -> check(h.acks.seqs.size() == 3, "acks " + h.acks.seqs))
                .thenWaitUntil(() -> {
                    check(h.acks.seqs.equals(List.of(100, 101, 102)), "ack order " + h.acks.seqs);
                    check(h.service.historyService().undoEntries(h.player.getUUID()).isEmpty(), "entry before the end");
                    h.service.endStroke(h.player, 7);
                    List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUUID());
                    check(entries.size() == 1, "entries after the stroke: " + entries.size());
                    WorldSnapshot after = capture(world, area);
                    for (int i = 0; i < before.states.length; i++) {
                        if (before.states[i] != after.states[i]) changedCells[0]++;
                    }
                    check(changedCells[0] > 30, "the stroke changed only " + changedCells[0] + " cells");
                    long recorded = entries.get(0).record().before().cellCount();
                    check(recorded == changedCells[0], "record has " + recorded + " cells, world changed " + changedCells[0]);
                    String label = entries.get(0).label();
                    check(label.equals(String.format(java.util.Locale.ROOT, "Raise stroke · %,d blocks", recorded)),
                            "label " + label);
                    h.undo(undo);
                })
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    check(undo.result.changed() == changedCells[0], "undo changed " + undo.result.changed());
                    checkSame(before, capture(world, area), "after undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * Four strokes (raise, lower through a pond, smooth, paint) applied by the server's brush lane match the same
     * kernels run on a snapshot of the area (no height hints there), cell for cell.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_stroke_kernel", timeoutTicks = LIMIT)
    public void brushServerMatchesSnapshotKernel(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 19);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 170, z0 + 47);
        loadAndForce(world, area);
        terrain(h, x0, z0, 48, 48);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        // A pond, plants, a stair and a chest (structures the brushes must leave alone).
        for (int x = x0 + 20; x <= x0 + 26; x++) {
            for (int z = z0 + 20; z <= z0 + 25; z++) {
                for (int y = 103; y <= 112; y++) {
                    writer.write(x, y, z, y <= 105 ? h.state("minecraft:water") : h.state("minecraft:air"), null);
                }
                writer.write(x, 102, z, h.state("minecraft:sand"), null);
            }
        }
        for (int i = 0; i < 40; i++) {
            int x = x0 + 8 + (i * 7) % 32, z = z0 + 8 + (i * 11) % 32;
            int top = topSolid(world, x, z);
            if (world.getBlockState(new BlockPos(x, top, z)).is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)) {
                writer.write(x, top + 1, z, h.state("minecraft:short_grass"), null);
            }
        }
        writer.write(x0 + 30, topSolid(world, x0 + 30, z0 + 14) + 1, z0 + 14, h.state("minecraft:oak_stairs"), null);
        writer.write(x0 + 16, topSolid(world, x0 + 16, z0 + 30) + 1, z0 + 30, h.state("minecraft:chest"), null);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);

        int dirt = h.state("minecraft:dirt");
        int cobble = h.state("minecraft:cobblestone");
        List<BrushSpec> specs = List.of(
                new BrushSpec(BrushTool.RAISE, 5, 0.9f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L),
                new BrushSpec(BrushTool.LOWER, 4, 1f, Falloff.LINEAR, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 2L),
                new BrushSpec(BrushTool.SMOOTH, 6, 0.7f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 3L),
                new BrushSpec(BrushTool.PALETTE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE,
                        new Pattern.Weighted(new int[] {dirt, cobble}, new int[] {2, 1}, 9L), SurfaceMask.ANY, 2, 0, 4L));
        List<List<Dab>> strokes = new ArrayList<>();
        for (int s = 0; s < specs.size(); s++) {
            List<Dab> dabs = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                // Paths in 1/16 blocks with sub-block offsets, varying height and pressure.
                int x16 = (x0 + 12 + i + s * 2) * 16 + (i * 5) % 16;
                int z16 = (z0 + 14 + (i * (s + 1)) % 18) * 16 + (i * 3) % 16;
                dabs.add(new Dab(i, x16, (108 + i % 3) * 16 + 4, z16, 140 + (i * 23) % 116));
            }
            strokes.add(dabs);
        }

        int[] seq = {1};
        GameTestSequence runner = context.startSequence();
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            runner.thenWaitUntil(() -> {
                begin(h, 20 + current, specs.get(current));
                List<Dab> dabs = strokes.get(current);
                for (int from = 0; from < dabs.size(); from += 8) {
                    DabOutcome outcome = h.service.dabs(h.player, 20 + current, seq[0]++,
                            dabs.subList(from, Math.min(dabs.size(), from + 8)));
                    check(outcome.accepted(), "stroke " + current + " batch " + from + ": " + outcome);
                }
            });
            runner.thenWaitUntil(() -> check(h.acks.seqs.size() == seq[0] - 1, "stroke " + current + " still applying"));
            runner.thenWaitUntil(() -> h.service.endStroke(h.player, 20 + current));
        }
        runner.thenWaitUntil(() -> {
            check(h.acks.seqs.equals(increasing(1, seq[0] - 1)), "ack order " + h.acks.seqs);
            check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
            int changed = 0;
            for (int s = 0; s < specs.size(); s++) {
                BrushSpec spec = specs.get(s);
                StrokeState state = new StrokeState();
                int[] writes = {0};
                for (Dab dab : strokes.get(s)) {
                    BrushKernels.forTool(spec.tool()).apply(spec, dab, state, snapshot, (x, y, z, handle) -> {
                        snapshot.set(x, y, z, handle);
                        writes[0]++;
                    });
                }
                check(writes[0] > 0, spec.tool() + " wrote nothing on the snapshot");
                changed += writes[0];
            }
            int mismatches = 0;
            String first = null;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        int server = Block.getId(world.getBlockState(pos.set(x, y, z)));
                        int kernel = snapshot.get(x, y, z);
                        if (server == kernel) continue;
                        mismatches++;
                        if (first == null) {
                            first = x + "," + y + "," + z + ": server " + Block.stateById(server) + ", kernel "
                                    + Block.stateById(kernel);
                        }
                    }
                }
            }
            check(mismatches == 0, mismatches + " cells differ (" + changed + " kernel writes); first " + first);
            check(h.service.historyService().undoEntries(h.player.getUUID()).size() == specs.size(), "one entry per stroke");
            forceChunks(world, area, false);
            h.close();
        });
        runner.thenSucceed();
    }

    /**
     * A dab touching a section a job holds is refused with AREA_BUSY (and not acknowledged by the engine). A dab
     * admitted before a job locked its area is skipped when the lane reaches it, reported, and still acknowledged.
     * Uses a private executor ticked by hand, so the job cannot run (and unlock) before the lane reaches the dab.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_stroke_locked", timeoutTicks = LIMIT)
    public void dabRefusedOnLockedSection(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = new EditExecutor<>(EngineTestSupport.runtime(context),
                new EditExecutor.Settings(10_000_000_000L, 0, 1.0, 2, 8, 32, 64, UnloadedPolicy.REFUSE, 256, 16_384));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 20);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 63, 170, z0 + 15);
        loadAndForce(world, area);
        terrain(h, x0, z0, 64, 16);
        int stone = h.state("minecraft:polished_andesite");
        Box ground2 = box(x0 + 32, 90, z0, x0 + 47, 127, z0 + 15);
        Box ground3 = box(x0 + 48, 90, z0, x0 + 63, 127, z0 + 15);
        WorldSnapshot before2 = capture(world, ground2);
        WorldSnapshot before3 = capture(world, ground3);
        RecordingListener jobA = new RecordingListener();
        RecordingListener jobB = new RecordingListener();
        submit(executor, world, new BoxFill("lock A", box(x0, 130, z0, x0 + 15, 135, z0 + 15), stone), jobA);

        begin(h, 3, new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L));
        DabOutcome locked = h.service.dabs(h.player, 3, 1, List.of(dab(0, x0 + 8, 107, z0 + 8)));
        check(!locked.accepted() && locked.reason() == RejectReason.AREA_BUSY, "locked dab: " + locked);
        check(locked.lastIndex() == 0, "lastIndex " + locked.lastIndex());
        DabOutcome free = h.service.dabs(h.player, 3, 2, List.of(dab(1, x0 + 56, 107, z0 + 8)));
        check(free.accepted(), "free dab: " + free);
        Box ground1 = box(x0 + 16, 90, z0, x0 + 31, 127, z0 + 15);
        WorldSnapshot before1 = capture(world, ground1);
        // Dab 2 will be locked when the lane reaches it; dab 3 (free chunk 1) is queued behind it.
        DabOutcome later = h.service.dabs(h.player, 3, 3, List.of(dab(2, x0 + 40, 107, z0 + 8), dab(3, x0 + 24, 107, z0 + 8)));
        check(later.accepted(), "dabs before the lock: " + later);
        // A job (not the player's own, which would apply their queued dabs first) locks the queued dab's area.
        submit(executor, world, new BoxFill("lock B", box(x0 + 32, 130, z0, x0 + 47, 135, z0 + 15), stone), jobB);
        check(h.acks.seqs.isEmpty(), "acknowledged before the lane ran: " + h.acks.seqs);

        executor.tick(); // brush lane first (every queued dab), then both jobs
        check(h.acks.seqs.equals(List.of(2, 3)), "acks " + h.acks.seqs + "; the refused batch 1 must not be");
        check(h.events.dabRejections.equals(List.of(RejectReason.AREA_BUSY)), "events " + h.events.dabRejections);
        checkSame(before2, capture(world, ground2), "the skipped dab's ground");
        checkSame(before1, capture(world, ground1), "a dab queued after the rejection");
        check(difference(before3, capture(world, ground3)) != null, "the free dab changed nothing");
        check(jobA.result != null && jobA.result.outcome() == JobOutcome.COMPLETED, "job A " + jobA.result);
        check(jobB.result != null && jobB.result.outcome() == JobOutcome.COMPLETED, "job B " + jobB.result);
        // The stroke stays rejected (the client was told it ended) until a new stroke begins.
        DabOutcome afterRejection = h.service.dabs(h.player, 3, 4, List.of(dab(4, x0 + 24, 107, z0 + 8)));
        check(!afterRejection.accepted() && afterRejection.reason() == RejectReason.AREA_BUSY, "after: " + afterRejection);
        h.service.endStroke(h.player, 3);
        check(h.service.historyService().undoEntries(h.player.getUUID()).size() == 1, "one stroke entry");
        begin(h, 4, new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L));
        check(h.service.dabs(h.player, 4, 5, List.of(dab(0, x0 + 24, 107, z0 + 8))).accepted(), "new stroke refused");
        executor.tick();
        check(h.acks.seqs.equals(List.of(2, 3, 5)), "acks " + h.acks.seqs);
        check(difference(before1, capture(world, ground1)) != null, "the new stroke's dab changed nothing");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.succeed();
    }

    /**
     * At most 32 dabs may wait in a player's lane; the next batch is refused with RATE_LIMITED. Admitted batches
     * are acknowledged once each, in order, after all their dabs ran.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_stroke_backlog", timeoutTicks = LIMIT)
    public void strokeBacklogCapped(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 21);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 31, 170, z0 + 31);
        loadAndForce(world, area);
        terrain(h, x0, z0, 32, 32);
        begin(h, 9, new BrushSpec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L));
        int index = 0;
        for (int batch = 0; batch < 2; batch++) {
            List<Dab> dabs = new ArrayList<>();
            for (int i = 0; i < 16; i++, index++) dabs.add(dab(index, x0 + 8 + index % 16, 108, z0 + 8 + index / 16));
            check(h.service.dabs(h.player, 9, 11 + batch, dabs).accepted(), "batch " + batch);
        }
        check(h.service.queuedDabs(h.player.getUUID()) == 32, "queued " + h.service.queuedDabs(h.player.getUUID()));
        DabOutcome over = h.service.dabs(h.player, 9, 13, List.of(dab(32, x0 + 20, 108, z0 + 20)));
        check(!over.accepted() && over.reason() == RejectReason.RATE_LIMITED, "over the cap: " + over);
        context.startSequence()
                .thenWaitUntil(() -> check(h.acks.seqs.size() == 2, "acks " + h.acks.seqs))
                .thenWaitUntil(() -> {
                    check(h.acks.seqs.equals(List.of(11, 12)), "acks " + h.acks.seqs);
                    check(h.acks.queuedAtAck.equals(List.of(16, 0)), "queued at each ack " + h.acks.queuedAtAck
                            + "; a batch must be acknowledged only after all its dabs ran");
                    DabOutcome next = h.service.dabs(h.player, 9, 14, List.of(dab(33, x0 + 20, 108, z0 + 20)));
                    check(next.accepted(), "after the lane drained: " + next);
                })
                .thenWaitUntil(() -> check(h.acks.seqs.size() == 3, "acks " + h.acks.seqs))
                .thenWaitUntil(() -> {
                    check(h.acks.seqs.equals(List.of(11, 12, 14)), "acks " + h.acks.seqs);
                    h.service.endStroke(h.player, 9);
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * Five seconds without dab activity commits the stroke's record as an entry and keeps the stroke open; the
     * next dabs go into a new entry. Leaving drops the history.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_stroke_idle", timeoutTicks = LIMIT)
    public void strokeIdleTimeoutCommits(GameTestHelper context) {
        long[] now = {0};
        Harness h = new Harness(context, null, () -> now[0]);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 22);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 31, 170, z0 + 31);
        loadAndForce(world, area);
        terrain(h, x0, z0, 32, 32);
        begin(h, 5, new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L));
        check(h.service.dabs(h.player, 5, 1, List.of(dab(0, x0 + 10, 108, z0 + 10))).accepted(), "first dab");
        context.startSequence()
                .thenWaitUntil(() -> check(h.acks.seqs.size() == 1, "first dab applying"))
                .thenWaitUntil(() -> {
                    now[0] += EngineEditService.STROKE_IDLE_NANOS - 1;
                    h.service.tick();
                    check(h.service.historyService().undoEntries(h.player.getUUID()).isEmpty(), "committed too early");
                    now[0] += 1;
                    h.service.tick();
                    check(h.service.historyService().undoEntries(h.player.getUUID()).size() == 1, "not committed when idle");
                    check(h.service.openStroke(h.player.getUUID()).orElse(-1) == 5, "the stroke was closed");
                    check(h.service.dabs(h.player, 5, 2, List.of(dab(1, x0 + 20, 108, z0 + 20))).accepted(), "later dab");
                })
                .thenWaitUntil(() -> check(h.acks.seqs.size() == 2, "later dab applying"))
                .thenWaitUntil(() -> {
                    h.service.endStroke(h.player, 5);
                    check(h.service.historyService().undoEntries(h.player.getUUID()).size() == 2, "second entry");
                    check(h.service.openStroke(h.player.getUUID()).isEmpty(), "stroke still open");
                    h.service.playerLeft(h.player.getUUID());
                    check(h.history().equals(dev.sculptory.server.engine.impl.HistorySnapshot.EMPTY),
                            "history kept after leaving");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * Review M2: a region op during an open stroke commits the stroke's record first, so the history follows the
     * real write order (the fill is undone first).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_stroke_then_fill", timeoutTicks = LIMIT)
    public void regionOpCommitsOpenStrokeFirst(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 28);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 170, z0 + 31);
        loadAndForce(world, area);
        terrain(h, x0, z0, 48, 32);
        begin(h, 6, new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L));
        check(h.service.dabs(h.player, 6, 1, List.of(dab(0, x0 + 10, 108, z0 + 10), dab(1, x0 + 12, 108, z0 + 10)))
                .accepted(), "dabs");
        RecordingListener fill = new RecordingListener();
        // Before the lane ran: the fill applies the queued dabs and pushes the stroke's entry first.
        h.fill(box(x0 + 32, 140, z0, x0 + 39, 147, z0 + 7), "minecraft:gold_block", fill);
        check(h.acks.seqs.equals(List.of(1)), "queued dabs not applied before the fill: " + h.acks.seqs);
        List<HistoryEntry> committed = h.service.historyService().undoEntries(h.player.getUUID());
        check(committed.size() == 1 && committed.get(0).label().startsWith("Raise stroke · "), "entries " + committed.size());
        context.startSequence()
                .thenWaitUntil(() -> check(fill.result != null, "fill running"))
                .thenWaitUntil(() -> {
                    List<String> labels = h.history().undoLabels();
                    check(labels.size() == 2 && labels.get(0).equals("Fill · 512 blocks")
                            && labels.get(1).startsWith("Raise stroke · "), "undo order " + labels);
                    check(h.service.openStroke(h.player.getUUID()).orElse(-1) == 6, "the stroke was closed");
                    h.service.endStroke(h.player, 6);
                    check(h.history().undoLabels().size() == 2, "an empty stroke record was pushed");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * Shutdown: queued dabs are dropped and every admitted batch is acknowledged exactly once, in order; a batch
     * admitted after the executor stopped is dropped during admission and acknowledged on the next service tick
     * (never before the network layer records it as admitted).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_stroke_shutdown", timeoutTicks = LIMIT)
    public void acksAtShutdown(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = new EditExecutor<>(EngineTestSupport.runtime(context),
                new EditExecutor.Settings(10_000_000_000L, 0, 1.0, 2, 8, 32, 64, UnloadedPolicy.REFUSE, 256, 16_384));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 29);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 31, 170, z0 + 31);
        loadAndForce(world, area);
        terrain(h, x0, z0, 32, 32);
        WorldSnapshot before = capture(world, area);
        begin(h, 8, new BrushSpec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L));
        check(h.service.dabs(h.player, 8, 5, List.of(dab(0, x0 + 8, 108, z0 + 8), dab(1, x0 + 9, 108, z0 + 8))).accepted(), "5");
        check(h.service.dabs(h.player, 8, 6, List.of(dab(2, x0 + 20, 108, z0 + 20))).accepted(), "6");
        executor.shutdown();
        check(h.acks.seqs.equals(List.of(5, 6)), "acks at executor shutdown " + h.acks.seqs);
        check(h.service.queuedDabs(h.player.getUUID()) == 0, "dabs still queued");
        checkSame(before, capture(world, area), "dropped dabs wrote");

        check(h.service.dabs(h.player, 8, 7, List.of(dab(3, x0 + 8, 108, z0 + 20))).accepted(), "7 after shutdown");
        check(h.acks.seqs.equals(List.of(5, 6)), "acknowledged during admission: " + h.acks.seqs);
        h.service.tick();
        check(h.acks.seqs.equals(List.of(5, 6, 7)), "deferred ack " + h.acks.seqs);
        h.service.shutdown();
        h.service.tick();
        check(h.acks.seqs.equals(List.of(5, 6, 7)), "acknowledged twice: " + h.acks.seqs);
        checkSame(before, capture(world, area), "dropped dabs wrote");
        forceChunks(world, area, false);
        h.close();
        context.succeed();
    }

    // ------------------------------------------------------------------ helpers

    /** Stone from y {@value #BASE} with a grass top at 104-110 varying across the area. */
    static void terrain(Harness h, int x0, int z0, int w, int d) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone"), grass = h.state("minecraft:grass_block");
        for (int dx = 0; dx < w; dx++) {
            for (int dz = 0; dz < d; dz++) {
                int top = 104 + Math.floorMod(dx / 3 + dz / 4 + ((dx * 7 + dz * 3) & 1), 7);
                for (int y = BASE; y <= top; y++) writer.write(x0 + dx, y, z0 + dz, y == top ? grass : stone, null);
            }
        }
    }

    private static int topSolid(ServerLevel world, int x, int z) {
        for (int y = 140; y >= BASE; y--) {
            if (!world.getBlockState(new BlockPos(x, y, z)).isAir()) return y;
        }
        return BASE;
    }

    private static void begin(Harness h, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(h.player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestAssertException("stroke refused: " + e.getMessage());
        }
    }

    private static void submit(EditExecutor<ServerLevel> executor, ServerLevel world, BoxFill program,
                               RecordingListener listener) {
        try {
            executor.submit(JobRequest.system(world, program, listener));
        } catch (EditRejected e) {
            throw new GameTestAssertException("job refused: " + e.getMessage());
        }
    }

    private static List<Integer> increasing(int from, int to) {
        List<Integer> list = new ArrayList<>();
        for (int i = from; i <= to; i++) list.add(i);
        return list;
    }
}
