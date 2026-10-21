package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EditTestSupport.capture;
import static dev.sculptory.mc.gametest.EditTestSupport.checkSame;
import static dev.sculptory.mc.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.mc.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;
import static dev.sculptory.mc.gametest.HeightmapGameTest.cone;
import static dev.sculptory.mc.gametest.HeightmapGameTest.ground;
import static dev.sculptory.mc.gametest.HeightmapGameTest.heightmap;
import static dev.sculptory.mc.gametest.ShapeBrushGameTest.onePartATick;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.HeightmapSpec;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.mc.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.platform.WriteOptions;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A Heightmap step worked out over several turns of the brush lane (a piece a tick here): before each turn after the
 * first the step is checked again, so the brush permission revoked and a lock taken by another player's job each end it
 * there, before anything is written; a step past its cell limit is refused TOO_LARGE through the lane, writing nothing;
 * a player leaving mid-plan drops it, writing nothing; and a column someone else changes after the step read it is left
 * whole, the rest written and undone and redone exactly. Then wide stamps paced as the client paces them
 * (the next sent once the last is written): a radius-128 drag, a mirrored one and three quick clicks, never refused;
 * and a shared brush queue of one item still takes a heavier dab when it is empty.
 * Region slots 1704-1710. (An area unloaded between turns goes through the same check, {@code planningRefusal}, unit
 * tested; a GameTest world keeps its chunks loaded.)
 */
public final class HeightmapPlanningGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** A radius-32 cone 24 blocks tall: its working out takes a hundred turns or so on a lane of next to no budget. */
    private static BrushSpec stamp() {
        return heightmap(32, cone(32), 24, false, SurfaceMask.ANY, null, Symmetry.NONE);
    }

    private static Dab dabAt(ServerLevel world, int index, int x, int z) {
        return new Dab(index, x * 16 + 8, (ground(world, x, z) + 1) * 16, z * 16 + 8, 255);
    }

    private static void begin(EditTestSupport.Harness h, ServerPlayer player, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestAssertException("stroke refused: " + e.getMessage());
        }
    }

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

    /** Ticks a few planning turns: the step is being worked out, nothing written yet. */
    private static void someTurns(EditExecutor<ServerLevel> executor, Harness h, ServerPlayer player,
                                  WorldSnapshot before, Box area) {
        for (int i = 0; i < 4; i++) executor.tick();
        check(h.service.workingOut(player.getUUID()), "the step is still being worked out");
        check(changed(before, capture(h.world, area), area) == 0, "a planning turn wrote something");
    }

    /**
     * Between planning turns: the brush node revoked ends the step NO_PERMISSION, a job's lock over its area AREA_BUSY,
     * and a cell limit passed TOO_LARGE, each with nothing written and no entry; then another writer changes cells while
     * the step is worked out, and the step leaves those columns whole, writes the rest, and undoes and redoes exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_heightmap_planning", timeoutTicks = LIMIT)
    public void planningTurnsAreCheckedAgain(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1704);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 160, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.BRUSH);
        try {
            int entries = h.service.historyService().undoEntries(builder.getUUID()).size();
            // 1. The brush node goes mid-plan.
            WorldSnapshot before = capture(world, area);
            begin(h, builder, 1, stamp());
            check(h.service.dabs(builder, 1, 1, List.of(dabAt(world, 0, x0 + 40, z0 + 40))).accepted(), "the step");
            someTurns(executor, h, builder, before, area);
            EditTestSupport.grant(builder, Perm.USE);
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 400, "the refused step");
            check(h.events.dabRejections.equals(List.of(RejectReason.NO_PERMISSION)), "refusals " + h.events.dabRejections);
            check(changed(before, capture(world, area), area) == 0, "something was written without the permission");
            h.service.endStroke(builder, 1);

            // 2. Another player's job locks a section the step reads.
            EditTestSupport.grant(builder, Perm.USE, Perm.BRUSH);
            h.events.dabRejections.clear();
            begin(h, builder, 2, stamp());
            check(h.service.dabs(builder, 2, 2, List.of(dabAt(world, 0, x0 + 40, z0 + 40))).accepted(), "the step");
            someTurns(executor, h, builder, before, area);
            Box locked = box(x0 + 30, 125, z0 + 30, x0 + 50, 125, z0 + 50);
            RecordingListener job = new RecordingListener();
            try {
                h.service.run(h.player, new OpSpec.Fill(locked, new Pattern.Single(h.state("minecraft:glass")),
                        CellMask.ANY), RunOptions.DEFAULT, job);
            } catch (EditRejected e) {
                throw new GameTestAssertException("the job was refused: " + e.getMessage());
            }
            check(h.service.executor().isLocked(world, locked), "the job holds the area");
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 2, 400, "the refused step");
            check(h.events.dabRejections.equals(List.of(RejectReason.AREA_BUSY)), "refusals " + h.events.dabRejections);
            MultiplayerGameTest.tickUntil(executor, () -> job.result != null, 400, "the job");
            WorldSnapshot afterJob = capture(world, area);
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        check(locked.contains(x, y, z) || afterJob.get(x, y, z) == before.get(x, y, z),
                                "the refused step wrote at " + x + "," + y + "," + z);
                    }
                }
            }
            h.service.endStroke(builder, 2);
            // The glass goes again (the op's own entry), so the next steps start from the ground as it was.
            RecordingListener unglass = MultiplayerGameTest.historyStep(h, h.player, true);
            MultiplayerGameTest.tickUntil(executor, () -> unglass.result != null, 400, "undoing the glass");
            checkSame(before, capture(world, area), "the glass undone");

            // 3. A step past its cell limit is refused while worked out.
            h.events.dabRejections.clear();
            h.service.stepCellLimit(1_000);
            begin(h, builder, 3, stamp());
            check(h.service.dabs(builder, 3, 3, List.of(dabAt(world, 0, x0 + 40, z0 + 40))).accepted(), "the step");
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 3, 2_000, "the refused step");
            check(h.events.dabRejections.equals(List.of(RejectReason.TOO_LARGE)), "refusals " + h.events.dabRejections);
            check(changed(before, capture(world, area), area) == 0, "a step past its limit wrote something");
            h.service.stepCellLimit(HeightmapSpec.MAX_STEP_CELLS);
            h.service.endStroke(builder, 3);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(builder.getUUID()) == 0, 40, "commits");
            check(h.service.historyService().undoEntries(builder.getUUID()).size() == entries,
                    "refused steps left entries");

            // 4. Someone else changes cells while the step is worked out. A column the step has read already (a row a
            // turn here: four turns read x0 + 7 to x0 + 10) is left whole; one it reads later is planned as changed. Undo
            // and redo are exact around both.
            h.events.dabRejections.clear();
            begin(h, builder, 4, stamp());
            check(h.service.dabs(builder, 4, 4, List.of(dabAt(world, 0, x0 + 40, z0 + 40))).accepted(), "the step");
            someTurns(executor, h, builder, before, area);
            int tx = x0 + 9, tz = z0 + 40, ty = ground(world, tx, tz);
            int sx = x0 + 60, sz = z0 + 40, sy = ground(world, sx, sz) + 2;
            BlockWriter other = h.runtime.writer(world, WriteOptions.DEFAULT);
            int sponge = h.state("minecraft:sponge"), sand = h.state("minecraft:sand");
            other.write(tx, ty, tz, sand, null);
            other.write(sx, sy, sz, sponge, null);
            WorldSnapshot changedByOther = capture(world, area);
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 4, 2_000, "the step");
            check(h.events.dabRejections.isEmpty(), "refusals " + h.events.dabRejections);
            WorldSnapshot written = capture(world, area);
            for (int y = 60; y <= 160; y++) {
                check(written.get(tx, y, tz) == changedByOther.get(tx, y, tz), "the sand's column moved at y " + y);
            }
            boolean rose = false;
            for (int y = 60; y <= 160; y++) rose |= written.get(tx + 1, y, tz) != changedByOther.get(tx + 1, y, tz);
            check(rose, "the column beside the sand rose");
            h.service.endStroke(builder, 4);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(builder.getUUID()) == 0, 400, "commit");
            RecordingListener undo = MultiplayerGameTest.historyStep(h, builder, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 400, "the undo");
            check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
            checkSame(changedByOther, capture(world, area), "after the undo");
            RecordingListener redo = MultiplayerGameTest.historyStep(h, builder, false);
            MultiplayerGameTest.tickUntil(executor, () -> redo.result != null, 400, "the redo");
            check(redo.result.outcome() == JobOutcome.COMPLETED && redo.result.skippedConflicts() == 0, "redo " + redo.result);
            checkSame(written, capture(world, area), "after the redo");
        } finally {
            h.service.stepCellLimit(HeightmapSpec.MAX_STEP_CELLS);
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.succeed();
    }

    /** A player leaving while their step is worked out: it is dropped, nothing written, no entry saved. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_heightmap_planning_leave", timeoutTicks = LIMIT)
    public void leavingMidPlanWritesNothing(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1706);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 160, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        WorldSnapshot before = capture(world, area);
        Path dir = ShapeBrushLaneGameTest.folder();
        try (ShapeBrushLaneGameTest.Saved run = new ShapeBrushLaneGameTest.Saved(h, dir, onePartATick(context))) {
            run.load(h.player);
            run.begin(h.player, 1, stamp());
            check(run.service.dabs(h.player, 1, 1, List.of(dabAt(world, 0, x0 + 40, z0 + 40))).accepted(), "the step");
            for (int i = 0; i < 4; i++) run.tick();
            check(run.service.workingOut(h.player.getUUID()), "the step is being worked out");
            run.service.playerLeft(h.player.getUUID());
            for (int i = 0; i < 300; i++) run.tick();
            check(changed(before, capture(world, area), area) == 0, "a step dropped mid-plan wrote something");
            check(run.service.queuedDabs(h.player.getUUID()) == 0, "the dab is still queued");
            run.load(h.player);
            check(run.undoEntries(h.player).isEmpty(), "an entry was saved: " + run.undoEntries(h.player));
            run.stop();
        } finally {
            forceChunks(world, area, false);
            h.close();
            GameTestSuite.deleteTree(dir);
        }
        context.succeed();
    }

    /**
     * The engine's accounting for wide stamps sent in the order a write-paced client sends them (the next once the last
     * is written; the client's own pacing is BrushToolTest's): a radius-128 drag of three stamps 32 apart, a mirrored
     * drag of two, and three quick clicks (each its own stroke), on a dedicated server's lane, the next stroke begun
     * while the last one's record is still being committed. None is refused; a radius-128 stamp holds 16 units of the
     * player's queue (32 mirrored) until it is written.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_heightmap_wide_pacing", timeoutTicks = LIMIT)
    public void pacedWideStampsAreNeverRefused(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = new EditExecutor<>(EngineTestSupport.runtime(context),
                EditExecutor.Settings.from(SculptoryConfig.defaults(), true));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1708);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 399, 200, z0 + 299);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 400, 300);
        try {
            int cz = z0 + 150;
            List<List<Integer>> strokes = List.of(List.of(x0 + 150, x0 + 182, x0 + 214), List.of(x0 + 190, x0 + 222),
                    List.of(x0 + 200), List.of(x0 + 200), List.of(x0 + 200));
            int strokeId = 1;
            for (int s = 0; s < strokes.size(); s++) {
                boolean mirrored = s == 1;
                // Mirrored across x = x0 + 200: each copy lands 20-44 blocks from its dab, inside the area.
                Symmetry symmetry = mirrored ? new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 200), 2 * cz) : Symmetry.NONE;
                BrushSpec spec = heightmap(128, cone(128), 16, false, SurfaceMask.ANY, null, symmetry);
                int id = strokeId++;
                begin(h, h.player, id, spec);
                List<Integer> xs = strokes.get(s);
                for (int i = 0; i < xs.size(); i++) {
                    int acks = h.acks.seqs.size();
                    DabOutcome outcome = h.service.dabs(h.player, id, i, List.of(dabAt(world, i, xs.get(i), cz)));
                    check(outcome.accepted(), "stroke " + id + " dab " + i + ": " + outcome);
                    check(h.service.queuedUnits(h.player.getUUID()) == (mirrored ? 32 : 16),
                            "units queued " + h.service.queuedUnits(h.player.getUUID()));
                    MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acks + 1, 20_000,
                            "stroke " + id + " dab " + i + " written");
                    check(h.service.queuedUnits(h.player.getUUID()) == 0, "written: nothing queued");
                }
                h.service.endStroke(h.player, id);
            }
            check(h.events.dabRejections.isEmpty(), "refusals " + h.events.dabRejections);
        } finally {
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.succeed();
    }

    /**
     * A server whose shared brush queue holds one item still takes a dab worth more units (a mirrored stamp, two) when
     * the queue is empty, and refuses another RATE_LIMITED while it waits; once written, the next goes in.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_heightmap_small_queue", timeoutTicks = LIMIT)
    public void aSmallBrushQueueStillTakesOneHeavyDab(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = onePartATick(context, 1);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1710);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 119, 160, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 120, 80);
        try {
            BrushSpec mirrored = heightmap(32, cone(32), 8, false, SurfaceMask.ANY, null,
                    new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 60), 2 * (z0 + 40)));
            begin(h, h.player, 1, mirrored);
            DabOutcome first = h.service.dabs(h.player, 1, 1, List.of(dabAt(world, 0, x0 + 40, z0 + 40)));
            check(first.accepted(), "the first dab, two units, into an empty queue of one: " + first);
            DabOutcome second = h.service.dabs(h.player, 1, 2, List.of(dabAt(world, 1, x0 + 44, z0 + 40)));
            check(!second.accepted() && second.reason() == RejectReason.RATE_LIMITED, "a second while it waits: " + second);
            h.service.endStroke(h.player, 1);
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 4_000, "the first dab");
            // A small queue holds the stroke's commit too: the next dab goes in once that is done. With a very
            // small maxQueuedBrushWork a client sending right after a write can meet a commit there and be refused
            // (a known limit).
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(h.player.getUUID()) == 0, 4_000, "its commit");
            begin(h, h.player, 2, mirrored);
            DabOutcome third = h.service.dabs(h.player, 2, 3, List.of(dabAt(world, 0, x0 + 44, z0 + 40)));
            check(third.accepted(), "the queue empty again: " + third);
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 2, 4_000, "the next dab");
        } finally {
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.succeed();
    }
}
