package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.HeightmapGameTest.cone;
import static dev.sculptory.fabric.gametest.HeightmapGameTest.ground;
import static dev.sculptory.fabric.gametest.HeightmapGameTest.heightmap;
import static dev.sculptory.fabric.gametest.ShapeBrushGameTest.onePartATick;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.platform.WriteOptions;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;

/**
 * Heightmap steps written in parts on the brush lane (one part a tick): before each part after the planning turn the
 * step is checked again, so the brush permission revoked and a lock taken by another player's job each end the step
 * there, what the parts wrote staying one entry that undoes exactly; a player leaving mid-step stops it at the part
 * boundary, quickly, its parts saved and undone exactly on return; a column in which someone else changes a cell
 * between parts is left whole, and undo and redo are exact around it; and a stroke released with deep dabs queued
 * leaves them to the lane rather than writing them whole at once. Region slots 1410-1418. (A chunk unloaded between
 * parts goes through the same check, but a GameTest world keeps its chunks loaded, so it is not driven here.)
 */
public final class HeightmapLaneGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** A radius-32 cone 24 blocks tall: some 25,000 cells, seven parts. */
    private static BrushSpec bigStamp(boolean lower) {
        return heightmap(32, cone(32), 24, lower, SurfaceMask.ANY, null, Symmetry.NONE);
    }

    private static Dab dabAt(ServerWorld world, int index, int x, int z) {
        return new Dab(index, x * 16 + 8, (ground(world, x, z) + 1) * 16, z * 16 + 8, 255);
    }

    private static void begin(Harness h, ServerPlayerEntity player, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
    }

    /**
     * Ticks until the player's step is worked out: a piece a tick on this lane (its budget is next to nothing), and none
     * of its parts written yet when it is.
     */
    private static void workedOut(EditExecutor<ServerWorld> executor, Harness h, ServerPlayerEntity player) {
        MultiplayerGameTest.tickUntil(executor, () -> !h.service.workingOut(player.getUuid()), 2_000, "the step worked out");
    }

    /** Cells of {@code area} that differ between two snapshots. */
    private static int changed(WorldSnapshot a, WorldSnapshot b, Box area) {
        int n = 0;
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (a.get(x, y, z) != b.get(x, y, z)) n++;
                }
            }
        }
        return n;
    }

    /**
     * The permission revoked between parts, then a lock taken by another player's job over a later part's columns, then
     * a cell changed by someone else between parts: the first two end the step with NO_PERMISSION and AREA_BUSY (the
     * parts written stay, one entry each, undone exactly); the third is left alone while the rest is written, and undo
     * and redo are exact around it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_heightmap_parts", tickLimit = LIMIT)
    public void eachPartIsCheckedAgainAndUndoIsExact(TestContext context) {
        EditExecutor<ServerWorld> executor = onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1410);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 160, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.BRUSH);
        try {
            // 1. The brush node goes after two parts.
            WorldSnapshot before = capture(world, area);
            begin(h, builder, 1, bigStamp(false));
            check(h.service.dabs(builder, 1, 1, List.of(dabAt(world, 0, x0 + 40, z0 + 40))).accepted(), "the step");
            workedOut(executor, h, builder); // a piece a tick: nothing written yet
            executor.tick(); // part 1
            executor.tick(); // part 2
            int afterTwo = changed(before, capture(world, area), area);
            check(afterTwo > 4_000 && afterTwo <= 2 * (4_096 + 200), "two parts written: " + afterTwo);
            EditTestSupport.grant(builder, Perm.USE);
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 40, "the refused step");
            check(h.events.dabRejections.equals(List.of(RejectReason.NO_PERMISSION)), "refusals " + h.events.dabRejections);
            check(changed(before, capture(world, area), area) == afterTwo, "a part ran without the permission");
            h.service.endStroke(builder, 1);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(builder.getUuid()) == 0, 40, "commit");
            List<HistoryEntry> entries = h.service.historyService().undoEntries(builder.getUuid());
            check(entries.size() == 1, "entries " + entries);
            RecordingListener undo = MultiplayerGameTest.historyStep(h, builder, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 60, "the undo");
            check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
            checkSame(before, capture(world, area), "after undoing the parts written without the permission");

            // 2. Another player's job locks the columns of a later part (x0 + 58..60, written last).
            EditTestSupport.grant(builder, Perm.USE, Perm.BRUSH);
            h.events.dabRejections.clear();
            begin(h, builder, 2, bigStamp(false));
            int acks = h.acks.seqs.size();
            check(h.service.dabs(builder, 2, 2, List.of(dabAt(world, 0, x0 + 40, z0 + 40))).accepted(), "the step");
            workedOut(executor, h, builder);
            executor.tick();
            // Stone in the air at y 111 (the terrain tops out at 110) over the whole stamp: its job locks section 6 (96-111) of
            // every column, which the next part writes in. The lane runs before the job in a tick.
            Box locked = box(x0 + 8, 111, z0 + 8, x0 + 72, 111, z0 + 72);
            Box lockedColumns = locked;
            RecordingListener job = new RecordingListener();
            try {
                h.service.run(h.player, new OpSpec.Fill(locked, new Pattern.Single(h.state("minecraft:stone")),
                        CellMask.ANY), RunOptions.DEFAULT, job);
            } catch (EditRejected e) {
                throw new GameTestException("the job was refused: " + e.getMessage());
            }
            check(h.service.executor().isLocked(world, lockedColumns), "the job holds the columns");
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acks + 1, 40, "the step");
            check(h.events.dabRejections.equals(List.of(RejectReason.AREA_BUSY)), "refusals " + h.events.dabRejections);
            MultiplayerGameTest.tickUntil(executor, () -> job.result != null, 40, "the job");
            h.service.endStroke(builder, 2);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(builder.getUuid()) == 0, 40, "commit");
            RecordingListener undo2 = MultiplayerGameTest.historyStep(h, builder, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo2.result != null, 60, "the undo");
            WorldSnapshot undone = capture(world, area);
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        // In the job's layer a cell the step had already made stone (a raised column's fill) was left alone by
                        // the job and is undone with the step; every other cell of it keeps the job's stone.
                        int now = undone.get(x, y, z);
                        boolean ok = now == before.get(x, y, z)
                                || (locked.contains(x, y, z) && now == h.state("minecraft:stone"));
                        check(ok, "after undoing the step the lock ended, at " + x + "," + y + "," + z);
                    }
                }
            }

            // 3. Someone else sets a cell a later part would write: its column is left whole; undo and redo are exact around it.
            WorldSnapshot beforeThree = capture(world, area);
            h.events.dabRejections.clear();
            begin(h, builder, 3, bigStamp(false));
            int acks3 = h.acks.seqs.size();
            check(h.service.dabs(builder, 3, 3, List.of(dabAt(world, 0, x0 + 40, z0 + 40))).accepted(), "the step");
            workedOut(executor, h, builder);
            executor.tick();
            int sx = x0 + 56, sz = z0 + 40, sy = ground(world, sx, sz) + 2;
            BlockWriter other = h.runtime.writer(world, WriteOptions.DEFAULT);
            int sponge = h.state("minecraft:sponge");
            other.write(sx, sy, sz, sponge, null);
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acks3 + 1, 40, "the step");
            check(h.events.dabRejections.isEmpty(), "refusals " + h.events.dabRejections);
            WorldSnapshot written = capture(world, area);
            check(written.get(sx, sy, sz) == sponge, "the sponge was written over");
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                if (y == sy) continue;
                check(written.get(sx, y, sz) == beforeThree.get(sx, y, sz), "its column was left whole, at y " + y);
            }
            check(ground(world, sx + 1, sz) > sy, "the column beside it rose");
            h.service.endStroke(builder, 3);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(builder.getUuid()) == 0, 40, "commit");
            RecordingListener undo3 = MultiplayerGameTest.historyStep(h, builder, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo3.result != null, 60, "the undo");
            check(undo3.result.outcome() == JobOutcome.COMPLETED && undo3.result.skippedConflicts() == 0, "undo " + undo3.result);
            WorldSnapshot undone3 = capture(world, area);
            check(undone3.get(sx, sy, sz) == sponge, "undo left the sponge");
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        if (x == sx && y == sy && z == sz) continue;
                        check(undone3.get(x, y, z) == beforeThree.get(x, y, z), "undo at " + x + "," + y + "," + z);
                    }
                }
            }
            RecordingListener redo = MultiplayerGameTest.historyStep(h, builder, false);
            MultiplayerGameTest.tickUntil(executor, () -> redo.result != null, 60, "the redo");
            check(redo.result.outcome() == JobOutcome.COMPLETED && redo.result.skippedConflicts() == 0, "redo " + redo.result);
            checkSame(written, capture(world, area), "after the redo");
        } finally {
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.complete();
    }

    /**
     * A player leaving mid-step: the step stops at its part boundary, quickly (not the rest of the step), its parts are
     * saved as one step, and back on the server the player undoes it exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_heightmap_leave", tickLimit = LIMIT)
    public void leavingMidStepKeepsThePartsWritten(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1416);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 160, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        WorldSnapshot before = capture(world, area);
        Path dir = ShapeBrushLaneGameTest.folder();
        try (ShapeBrushLaneGameTest.Saved run = new ShapeBrushLaneGameTest.Saved(h, dir, onePartATick(context))) {
            run.load(h.player);
            run.begin(h.player, 1, bigStamp(false));
            check(run.service.dabs(h.player, 1, 1, List.of(dabAt(world, 0, x0 + 40, z0 + 40))).accepted(), "the step");
            run.tickUntil(() -> !run.service.workingOut(h.player.getUuid()), 2_000, "the step worked out");
            run.tick();
            run.tick();
            int written = changed(before, capture(world, area), area);
            check(written > 4_000 && written <= 2 * (4_096 + 200), "two parts: " + written);
            long start = System.nanoTime();
            run.service.playerLeft(h.player.getUuid());
            long took = (System.nanoTime() - start) / 1_000_000;
            check(took < 100, "leaving took " + took + " ms");
            for (int i = 0; i < 20; i++) run.tick();
            check(changed(before, capture(world, area), area) == written, "a part ran after the player left");
            run.load(h.player);
            List<HistoryEntry> steps = run.undoEntries(h.player);
            check(steps.size() == 1, "steps " + steps.stream().map(HistoryEntry::label).toList());
            run.undo(h.player, 200);
            checkSame(before, capture(world, area), "after undoing the parts written");
            run.stop();
        } finally {
            forceChunks(world, area, false);
            h.close();
            EngineTestBootstrap.deleteTree(dir);
        }
        context.complete();
    }

    /**
     * A stroke released with two deep radius-32 dabs queued, none of the stroke's steps worked out yet: the end of the
     * stroke leaves them to the lane (an unknown Heightmap step is heavy), writing nothing at once; the lane then writes
     * them over ticks and the stroke becomes one entry.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_heightmap_release", tickLimit = LIMIT)
    public void aReleaseWithDeepDabsQueuedLeavesThemToTheLane(TestContext context) {
        EditExecutor<ServerWorld> executor = onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1418);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, -10, z0, x0 + 79, 160, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        WorldSnapshot before = capture(world, area);
        try {
            begin(h, h.player, 1, heightmap(32, cone(32), 128, true, SurfaceMask.ANY, null, Symmetry.NONE));
            check(h.service.dabs(h.player, 1, 1, List.of(dabAt(world, 0, x0 + 38, z0 + 40), dabAt(world, 1, x0 + 42,
                    z0 + 40))).accepted(), "the dabs");
            long start = System.nanoTime();
            h.service.endStroke(h.player, 1);
            long took = (System.nanoTime() - start) / 1_000_000;
            check(h.service.queuedDabs(h.player.getUuid()) == 2, "the release wrote the dabs at once");
            check(changed(before, capture(world, area), area) == 0, "something was written at once");
            check(took < 50, "the release took " + took + " ms");
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 4_000, "the dabs");
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 4_000, "commit");
            check(h.service.historyService().undoEntries(h.player.getUuid()).size() == 1, "one entry");
            check(changed(before, capture(world, area), area) > 10_000, "the cut was written");
        } finally {
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.complete();
    }
}
