package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
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
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;

/**
 * Brush symmetry on the server (regionCorner slots 500-519 and 560-564): symmetric strokes of every mode give exactly
 * what the shared kernel (the client's prediction) gives on a snapshot, each stroke is one exact undo, the server-only
 * large Smooth and Flatten replicate too, and refusals and budgets count every copy. Copies stand on the ground where
 * they land: on higher ground, without ground (skipped and reported) and under Flatten's one plane. Each test runs its
 * own executor, ticked here, so the brush lane runs exactly when the test says.
 */
public final class BrushSymmetryGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * Mirror X+Z Raise, Rotate 4 Lower (a copy through a pond, refilled with water), Mirror X Paint and Rotate 2 Smooth,
     * with copies overlapping near the planes: the server's result equals the kernel's on a snapshot cell for cell,
     * every quadrant changed, each stroke is one history entry, and undoing them restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_strokes", tickLimit = LIMIT)
    public void symmetricStrokesMatchTheKernelAndUndoExactly(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 500);
        int x0 = at[0], z0 = at[1];
        // Up to y 180: a copy's ground is searched up to 64 blocks above its dab (y 108-110).
        Box area = box(x0, 90, z0, x0 + 63, 180, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        pond(h, x0 + 38, z0 + 38, x0 + 46, z0 + 46);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);

        // A block corner (x0 + 32, z0 + 32) and a block centre (x0 + 31.5, z0 + 31.5), in half blocks.
        int cornerX = 2 * (x0 + 32), cornerZ = 2 * (z0 + 32);
        int middleX = 2 * (x0 + 31) + 1, middleZ = 2 * (z0 + 31) + 1;
        List<BrushSpec> specs = List.of(
                new BrushSpec(BrushTool.RAISE, 5, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L, null,
                        new Symmetry(Symmetry.Mode.MIRROR_XZ, cornerX, cornerZ)),
                new BrushSpec(BrushTool.LOWER, 4, 1f, Falloff.LINEAR, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 2L, null,
                        new Symmetry(Symmetry.Mode.ROTATE_4, middleX, middleZ)),
                new BrushSpec(BrushTool.PAINT, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(h.state("minecraft:sand")),
                        SurfaceMask.ANY, 2, 0, 3L, null, new Symmetry(Symmetry.Mode.MIRROR_X, middleX, cornerZ)),
                new BrushSpec(BrushTool.SMOOTH, 6, 0.8f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 4L, null,
                        new Symmetry(Symmetry.Mode.ROTATE_2, cornerX + 1, middleZ)));
        List<List<Dab>> strokes = new ArrayList<>();
        for (int s = 0; s < specs.size(); s++) {
            List<Dab> dabs = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                // Starting a block or two off the planes (the copies overlap), wandering south-east.
                int x = x0 + 33 + i, z = z0 + 33 + (i * (s + 2)) % 9;
                dabs.add(new Dab(i, x * 16 + (i * 5) % 16, (108 + i % 3) * 16 + 4, z * 16 + (i * 7) % 16, 150 + (i * 29) % 106));
            }
            strokes.add(dabs);
        }

        int seq = 1;
        for (int s = 0; s < specs.size(); s++) {
            begin(h, 60 + s, specs.get(s));
            List<Dab> dabs = strokes.get(s);
            for (int from = 0; from < dabs.size(); from += 6) {
                DabOutcome outcome = h.service.dabs(h.player, 60 + s, seq++, dabs.subList(from, from + 6));
                check(outcome.accepted(), "stroke " + s + " batch " + from + ": " + outcome);
                int acked = seq - 1;
                MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acked, 4, "stroke " + s);
            }
            h.service.endStroke(h.player, 60 + s);
        }
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        String replay = replay(specs, strokes, snapshot);
        check(replay.isEmpty(), replay);
        check(mismatches(world, area, snapshot).isEmpty(), mismatches(world, area, snapshot));
        WorldSnapshot after = capture(world, area);
        int[] quadrants = new int[4];
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (before.get(x, y, z) != after.get(x, y, z)) quadrants[(x < x0 + 32 ? 0 : 1) + (z < z0 + 32 ? 0 : 2)]++;
                }
            }
        }
        for (int q = 0; q < 4; q++) check(quadrants[q] > 50, "quadrant " + q + " changed " + quadrants[q] + " cells");
        List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
        check(entries.size() == specs.size(), "one entry per stroke, got " + entries.size());
        for (int i = 0; i < specs.size(); i++) {
            RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "undo " + i);
            check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        }
        checkSame(before, capture(world, area), "after undoing every stroke");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * The server-only sizes (radius 24, which the client does not predict) replicate too: a Mirror X Smooth with an
     * inverted mask and a Rotate 2 Flatten, both clipped, match the kernel on a snapshot; both sides of the plane
     * change, nothing outside the clip boxes does.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_large", tickLimit = LIMIT)
    public void largeServerOnlySmoothAndFlattenReplicateToo(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 502);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 95, 180, z0 + 95); // the ground search reads up to y 174
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 96, 96);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);
        SurfaceMask notHigh = new SurfaceMask.Not(new SurfaceMask.And(List.of(
                new SurfaceMask.Elevation(108, 120), new SurfaceMask.Slope(0, 1))));
        Box smoothClip = box(x0 + 16, 90, z0 + 20, x0 + 80, 170, z0 + 76);
        Box flattenClip = box(x0 + 22, 100, z0 + 16, x0 + 74, 170, z0 + 62);
        List<BrushSpec> specs = List.of(
                new BrushSpec(BrushTool.SMOOTH, 24, 1f, Falloff.CONSTANT, Shape.SQUARE, null, notHigh, 0, 0, 6L, smoothClip,
                        new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 48), 0)),
                new BrushSpec(BrushTool.FLATTEN, 24, 0.8f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 106, 5L,
                        flattenClip, new Symmetry(Symmetry.Mode.ROTATE_2, 2 * (x0 + 47) + 1, 2 * (z0 + 47) + 1)));
        List<List<Dab>> strokes = new ArrayList<>();
        for (int s = 0; s < specs.size(); s++) {
            List<Dab> dabs = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                int x = x0 + 44 + i % 4, z = z0 + 46 + (i * 3) % 5;
                dabs.add(new Dab(i, x * 16 + (i * 5) % 16, (108 + i % 3) * 16 + 4, z * 16 + (i * 7) % 16, 150 + (i * 29) % 106));
            }
            strokes.add(dabs);
        }
        for (int s = 0; s < specs.size(); s++) {
            begin(h, 70 + s, specs.get(s));
            DabOutcome outcome = h.service.dabs(h.player, 70 + s, 20 + s, strokes.get(s));
            check(outcome.accepted(), "stroke " + s + ": " + outcome);
            int acked = s + 1;
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acked, 20, "stroke " + s);
            h.service.endStroke(h.player, 70 + s);
        }
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        String replay = replay(specs, strokes, snapshot);
        check(replay.isEmpty(), replay);
        check(mismatches(world, area, snapshot).isEmpty(), mismatches(world, area, snapshot));
        WorldSnapshot after = capture(world, area);
        int west = 0, east = 0;
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (before.get(x, y, z) == after.get(x, y, z)) continue;
                    check(smoothClip.contains(x, y, z) || flattenClip.contains(x, y, z), "changed outside both boxes at "
                            + x + "," + y + "," + z);
                    if (x < x0 + 48) west++;
                    else east++;
                }
            }
        }
        check(west > 200 && east > 200, "changed " + west + " cells west of the plane and " + east + " east");
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == specs.size(), "one entry per stroke");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * Refusals and partial failures count every copy: a centre outside the world is INVALID at the stroke's start, and
     * so is a dab whose copy lands beyond the world's limit although the centre is inside it; a batch whose copies
     * would pass the 32-dab queue is RATE_LIMITED (the same dabs without symmetry fit); a copy in an
     * unloaded chunk refuses the dab UNLOADED and a copy in a section a job holds refuses it AREA_BUSY, writing nothing
     * on either side; a copy beyond the world border writes nothing while the dab's own side changes, and that stroke
     * still undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_refused", tickLimit = LIMIT)
    public void refusalsAndBudgetsCountEveryCopy(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 504);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 170, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L);

        // A centre beyond the world's horizontal limit.
        int far = 2 * World.HORIZONTAL_LIMIT + 2;
        for (Symmetry outside : List.of(new Symmetry(Symmetry.Mode.MIRROR_X, far, 0), new Symmetry(Symmetry.Mode.ROTATE_2, 0, -far))) {
            try {
                h.service.beginStroke(h.player, 80, raise.withSymmetry(outside));
                throw new GameTestException("a centre outside the world was accepted: " + outside);
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.INVALID, "refused with " + e.reason());
            }
            check(h.service.openStroke(h.player.getUuid()).isEmpty(), "a refused stroke is open");
        }

        // A centre inside the world (x 29,900,000) whose copy of a dab near the region lands beyond the limit.
        WorldSnapshot untouched = capture(world, area);
        begin(h, 80, raise.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 2 * 29_900_000, 0)));
        DabOutcome beyond = h.service.dabs(h.player, 80, 30, row(0, 1, x0 + 10, z0 + 10));
        check(!beyond.accepted() && beyond.reason() == RejectReason.INVALID, "a copy beyond the world limit: " + beyond);
        check(h.service.queuedDabs(h.player.getUuid()) == 0, "the refused dab was queued");
        h.service.endStroke(h.player, 80);
        checkSame(untouched, capture(world, area), "after the refused dab");

        // The queue budget: 9 dabs are 36 copies under Rotate 4, over the 32 a player may have queued.
        BrushSpec rotate = raise.withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_4, 2 * (x0 + 24), 2 * (z0 + 24)));
        begin(h, 81, rotate);
        DabOutcome over = h.service.dabs(h.player, 81, 1, row(0, 9, x0 + 28, z0 + 30));
        check(!over.accepted() && over.reason() == RejectReason.RATE_LIMITED, "36 copies: " + over);
        check(h.service.dabs(h.player, 81, 2, row(0, 8, x0 + 28, z0 + 30)).accepted(), "32 copies were refused");
        check(h.service.queuedDabs(h.player.getUuid()) == 8 && h.service.queuedUnits(h.player.getUuid()) == 32,
                "queued " + h.service.queuedDabs(h.player.getUuid()) + " dabs, " + h.service.queuedUnits(h.player.getUuid()));
        DabOutcome full = h.service.dabs(h.player, 81, 3, row(8, 1, x0 + 28, z0 + 30));
        check(!full.accepted() && full.reason() == RejectReason.RATE_LIMITED, "the queue is full: " + full);
        // Refused batches are never acknowledged by the service (the network layer answers them).
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 4, "the queued dabs");
        check(h.service.dabs(h.player, 81, 4, row(9, 1, x0 + 28, z0 + 30)).accepted(), "after the lane drained");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 2, 4, "the last dab");
        h.service.endStroke(h.player, 81);
        begin(h, 82, raise);
        check(h.service.dabs(h.player, 82, 5, row(0, 9, x0 + 28, z0 + 30)).accepted(), "9 dabs without symmetry");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 3, 4, "the plain dabs");
        h.service.endStroke(h.player, 82);
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);

        // A copy in an unloaded chunk (the mirror plane 3,000 blocks east) refuses the whole dab.
        WorldSnapshot quiet = capture(world, area);
        begin(h, 83, raise.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 3000), 0)));
        DabOutcome unloaded = h.service.dabs(h.player, 83, 6, row(20, 1, x0 + 10, z0 + 10));
        check(!unloaded.accepted() && unloaded.reason() == RejectReason.UNLOADED, "a copy in an unloaded chunk: " + unloaded);
        h.service.endStroke(h.player, 83);

        // A copy in a section a queued job holds refuses the whole dab.
        RecordingListener fill = new RecordingListener();
        h.fill(box(x0 + 30, 125, z0 + 4, x0 + 40, 125, z0 + 14), "minecraft:glass", fill);
        begin(h, 84, raise.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 24), 0)));
        DabOutcome busy = h.service.dabs(h.player, 84, 7, row(21, 1, x0 + 12, z0 + 9));
        check(!busy.accepted() && busy.reason() == RejectReason.AREA_BUSY, "a copy under a job: " + busy);
        h.service.endStroke(h.player, 84);
        checkSame(quiet, capture(world, area), "after the refused dabs");
        MultiplayerGameTest.tickUntil(executor, () -> fill.result != null, 20, "the fill");
        RecordingListener unfill = MultiplayerGameTest.historyStep(h, h.player, true);
        MultiplayerGameTest.tickUntil(executor, () -> unfill.result != null, 20, "undoing the fill");
        checkSame(quiet, capture(world, area), "after undoing the fill");

        // A copy beyond the world border (east of x0 + 16) writes nothing; the dab's own side changes. Undo is exact.
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        WorldSnapshot beforeBorder = capture(world, area);
        Box eastSide = box(x0 + 16, 90, z0, x0 + 47, 170, z0 + 47);
        WorldSnapshot eastBefore = capture(world, eastSide);
        try {
            border.setCenter(x0 + 16 - 100_000, z0);
            border.setSize(200_000);
            begin(h, 85, raise.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 16), 0)));
            check(h.service.dabs(h.player, 85, 8, row(0, 4, x0 + 9, z0 + 20)).accepted(), "the border stroke");
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 4, 4, "the border stroke");
            h.service.endStroke(h.player, 85);
            check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
            checkSame(eastBefore, capture(world, eastSide), "beyond the border");
            check(EditTestSupport.difference(beforeBorder, capture(world, area)) != null, "the dab's own side did not change");
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
        MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "undoing the border stroke");
        checkSame(beforeBorder, capture(world, area), "after undoing the border stroke");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * A protected copy writes nothing while the other copies write, as protection works per column: with the world
     * border protecting the east half (the one protection a GameTest can set up; it reaches the engine through
     * {@code canPlayerModifyAt} like spawn protection and claims), a Rotate 4 dab in the west writes its two western
     * copies and neither eastern one, and the stroke goes on. A step whose every copy is protected writes nothing and
     * is refused PROTECTED. The first stroke undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_protected", tickLimit = LIMIT)
    public void aProtectedCopyWritesNothingWhileTheOthersWrite(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 506);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 63, 170, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        Box east = box(x0 + 32, 90, z0, x0 + 63, 170, z0 + 63);
        Box northWest = box(x0, 90, z0, x0 + 31, 170, z0 + 31);
        Box southWest = box(x0, 90, z0 + 32, x0 + 31, 170, z0 + 63);
        WorldSnapshot before = capture(world, area);
        WorldSnapshot eastBefore = capture(world, east);
        WorldSnapshot northWestBefore = capture(world, northWest);
        WorldSnapshot southWestBefore = capture(world, southWest);
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L);
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(x0 + 32 - 100_000, z0);
            border.setSize(200_000);
            check(!world.canPlayerModifyAt(h.player, new net.minecraft.util.math.BlockPos(x0 + 40, 110, z0 + 20)),
                    "the east half is not protected");
            // Around the corner (x0 + 32, z0 + 32): the dab 12 blocks north-west of it, its copies north-east,
            // south-east and south-west.
            begin(h, 90, raise.withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_4, 2 * (x0 + 32), 2 * (z0 + 32))));
            check(h.service.dabs(h.player, 90, 1, row(0, 3, x0 + 19, z0 + 20)).accepted(), "the rotated stroke");
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 4, "the rotated stroke");
            h.service.endStroke(h.player, 90);
            check(h.events.dabRejections.isEmpty(), "a stroke with protected copies was refused: " + h.events.dabRejections);
            checkSame(eastBefore, capture(world, east), "the protected east half");
            check(EditTestSupport.difference(northWestBefore, capture(world, northWest)) != null,
                    "the dab's own quarter did not change");
            check(EditTestSupport.difference(southWestBefore, capture(world, southWest)) != null,
                    "the western copy did not change its quarter");

            // Every copy of this step is in the east: nothing is written and the dab is refused PROTECTED.
            WorldSnapshot afterFirst = capture(world, area);
            begin(h, 91, raise.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_Z, 0, 2 * (z0 + 32))));
            check(h.service.dabs(h.player, 91, 2, row(0, 1, x0 + 44, z0 + 20)).accepted(), "the eastern stroke");
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 2, 4, "the eastern stroke");
            h.service.endStroke(h.player, 91);
            check(h.events.dabRejections.equals(List.of(RejectReason.PROTECTED)), "refusals: " + h.events.dabRejections);
            checkSame(afterFirst, capture(world, area), "after the wholly protected step");
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == 1, "one entry: the rotated stroke");
        RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
        MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        checkSame(before, capture(world, area), "after the undo");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * The brush lane checks every copy again when it runs the dab: another player's job admitted over a copy's area
     * after the dab was admitted makes the lane refuse the dab AREA_BUSY, and nothing of the step is written, the dab's
     * own area included.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_recheck", tickLimit = LIMIT)
    public void theLaneChecksEveryCopyAgain(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 508);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 170, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        WorldSnapshot before = capture(world, area);
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 24), 0));
        begin(h, 92, raise);
        // The dab at x0 + 12, its mirror at x0 + 35.
        check(h.service.dabs(h.player, 92, 1, row(0, 1, x0 + 12, z0 + 20)).accepted(), "the dab");
        // Another player's fill over the mirror's area only, admitted before the lane runs the dab.
        net.minecraft.server.network.ServerPlayerEntity other = h.addPlayer();
        RecordingListener fill = new RecordingListener();
        h.fill(other, box(x0 + 32, 125, z0 + 16, x0 + 40, 125, z0 + 24), "minecraft:glass", fill);
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 4, "the dab");
        check(h.events.dabRejections.equals(List.of(RejectReason.AREA_BUSY)), "refusals: " + h.events.dabRejections);
        h.service.endStroke(h.player, 92);
        check(h.service.historyService().undoEntries(h.player.getUuid()).isEmpty(), "the refused step made an entry");
        MultiplayerGameTest.tickUntil(executor, () -> fill.result != null, 20, "the fill");
        Box glass = box(x0 + 32, 125, z0 + 16, x0 + 40, 125, z0 + 24);
        WorldSnapshot after = capture(world, area);
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (glass.contains(x, y, z)) continue;
                    check(before.get(x, y, z) == after.get(x, y, z), "the refused step changed " + x + "," + y + "," + z);
                }
            }
        }
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    // ------------------------------------------------------------------ copies follow the terrain (slots 560-564)

    /**
     * Copies follow the terrain: east of a mirror plane the ground is a slope 14 to 29 blocks above the west side, beyond
     * a radius-5 brush's reach of 13 (copies that kept the dab's height changed nothing there). Mirror X Raise, Smooth and
     * Paint strokes on the west change the slope under their copies; the server's result equals the kernel's (the
     * client's prediction) on a snapshot cell for cell, no copy is reported without ground, each stroke is one history
     * entry and undoing them restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_terrain_slope", tickLimit = LIMIT)
    public void copiesFollowASlopeOnHigherGroundAndUndoExactly(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 560);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 63, 190, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        int plane = x0 + 32;
        heighten(h, plane, z0, x0 + 63, z0 + 63, x -> 14 + (x - plane) / 2);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * plane, 0);
        List<BrushSpec> specs = List.of(
                new BrushSpec(BrushTool.RAISE, 5, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L, null,
                        mirror),
                new BrushSpec(BrushTool.SMOOTH, 4, 1f, Falloff.CONSTANT, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 2L, null,
                        mirror),
                new BrushSpec(BrushTool.PAINT, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(h.state("minecraft:sand")),
                        SurfaceMask.ANY, 2, 0, 3L, null, mirror));
        List<List<Dab>> strokes = new ArrayList<>();
        int seq = 1;
        for (int s = 0; s < specs.size(); s++) {
            // West of the plane, each dab on the ground where a cursor would hit it now.
            List<Dab> dabs = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int x = x0 + 14 + i, z = z0 + 20 + (i * (s + 3)) % 17;
                dabs.add(new Dab(i, x * 16 + (i * 5) % 16, (topSolid(world, x, z) + 1) * 16, z * 16 + (i * 7) % 16, 255));
            }
            strokes.add(dabs);
            begin(h, 100 + s, specs.get(s));
            for (int from = 0; from < dabs.size(); from += 6) {
                DabOutcome outcome = h.service.dabs(h.player, 100 + s, seq++, dabs.subList(from, from + 6));
                check(outcome.accepted(), "stroke " + s + " batch " + from + ": " + outcome);
                int acked = seq - 1;
                MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acked, 4, "stroke " + s);
            }
            h.service.endStroke(h.player, 100 + s);
        }
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        check(h.events.noGround.isEmpty(), "copies without ground: " + h.events.noGround);
        String replay = replay(specs, strokes, snapshot);
        check(replay.isEmpty(), replay);
        check(mismatches(world, area, snapshot).isEmpty(), mismatches(world, area, snapshot));
        WorldSnapshot after = capture(world, area);
        int onTheSlope = 0;
        for (int y = 118; y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = plane; x <= area.max().x(); x++) {
                    if (before.get(x, y, z) != after.get(x, y, z)) onTheSlope++;
                }
            }
        }
        check(onTheSlope > 100, "the copies changed " + onTheSlope + " cells of the slope (its lowest top is y 118)");
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == specs.size(), "one entry per stroke");
        for (int i = 0; i < specs.size(); i++) {
            RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "undo " + i);
            check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        }
        checkSame(before, capture(world, area), "after undoing every stroke");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * A copy that finds no ground writes nothing and is reported once: the mirror of a Raise stroke lands on a stone
     * pillar rising past its ground search (64 blocks above the dab), so that copy is left out of every step while the
     * dab's own side is raised. The ground around the pillar, at the dab's height, stays as it was: a copy never works at
     * another height. The stroke is not refused, makes one history entry and undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_terrain_no_ground", tickLimit = LIMIT)
    public void aCopyWithoutGroundWritesNothingAndIsReported(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 562);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 190, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        // The dabs at x0 + 8 .. 11 mirror (around x0 + 24) onto x0 + 39 .. 36: the pillar covers them, not the rest of
        // the copies' footprints (x0 + 33 .. 42, z0 + 17 .. 23).
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = x0 + 32; x <= x0 + 39; x++) {
            for (int z = z0 + 18; z <= z0 + 22; z++) {
                for (int y = topSolid(world, x, z) + 1; y <= 180; y++) writer.write(x, y, z, stone, null);
            }
        }
        WorldSnapshot before = capture(world, area);
        Box east = box(x0 + 25, 90, z0, x0 + 47, 190, z0 + 47);
        Box west = box(x0, 90, z0, x0 + 23, 190, z0 + 47);
        WorldSnapshot eastBefore = capture(world, east);
        WorldSnapshot westBefore = capture(world, west);
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 24), 0));
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            int x = x0 + 8 + i, z = z0 + 20;
            dabs.add(new Dab(i, x * 16 + 8, (topSolid(world, x, z) + 1) * 16, z * 16 + 8, 255));
        }
        begin(h, 95, raise);
        check(h.service.dabs(h.player, 95, 1, dabs).accepted(), "the stroke");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 4, "the stroke");
        h.service.endStroke(h.player, 95);
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        check(h.events.noGround.equals(List.of("95:0:1")), "reported once, at the first dab: " + h.events.noGround);
        checkSame(eastBefore, capture(world, east), "the copies' side");
        check(EditTestSupport.difference(westBefore, capture(world, west)) != null, "the dab's own side did not change");
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == 1, "one entry");
        RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
        MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        checkSame(before, capture(world, area), "after the undo");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * Flatten keeps one target plane for every copy: west of the plane the ground is the usual y 104-110, east of it a
     * plateau 20 blocks higher. A Mirror X Flatten to y 106 on the west flattens its own footprint and cuts the plateau
     * under its copy down to the same y 106 (13 blocks a dab at most, the copy standing on the ground left by the
     * previous dab), matching the kernel on a snapshot; the stroke undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_terrain_flatten", tickLimit = LIMIT)
    public void flattenBringsEveryCopyToTheStrokesPlane(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 564);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 63, 190, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        heighten(h, x0 + 32, z0, x0 + 63, z0 + 63, x -> 20);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);
        BrushSpec flatten = new BrushSpec(BrushTool.FLATTEN, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                106, 7L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 32), 0));
        // Six dabs on the same spot, x0 + 20.5, z0 + 30.5; the copy's centre is x0 + 43.5.
        List<Dab> dabs = new ArrayList<>();
        int dabY = topSolid(world, x0 + 20, z0 + 30) + 1;
        for (int i = 0; i < 6; i++) dabs.add(new Dab(i, (x0 + 20) * 16 + 8, dabY * 16, (z0 + 30) * 16 + 8, 255));
        begin(h, 97, flatten);
        check(h.service.dabs(h.player, 97, 1, dabs).accepted(), "the stroke");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 10, "the stroke");
        h.service.endStroke(h.player, 97);
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        String replay = replay(List.of(flatten), List.of(dabs), snapshot);
        check(replay.isEmpty(), replay);
        check(mismatches(world, area, snapshot).isEmpty(), mismatches(world, area, snapshot));
        for (int dx = -5; dx <= 5; dx++) {
            for (int dz = -5; dz <= 5; dz++) {
                if (dx * dx + dz * dz > 25) continue;
                int west = topSolid(world, x0 + 20 + dx, z0 + 30 + dz), east = topSolid(world, x0 + 43 + dx, z0 + 30 + dz);
                check(west == 106 && east == 106, "column offset " + dx + "," + dz + ": west at " + west + ", east at " + east);
            }
        }
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == 1, "one entry");
        RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
        MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        checkSame(before, capture(world, area), "after the undo");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * A cliff at the mirror plane: west of it the usual ground (y 104-110), east of it 30 blocks higher. A Mirror X Raise
     * of radius 8 on the west, 2.5 blocks from the plane, has its copy on the cliff top 2.5 blocks east of it, and their
     * footprints share the columns x0 + 26 to 37. On each side only the dab standing there finds the surface, so each side
     * ends exactly as a dab without symmetry at that side's position and height leaves it (the kernel applied to each
     * alone, on two snapshots), including the shared columns next to the plane. The stroke undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_terrain_cliff", tickLimit = LIMIT)
    public void aCliffAtThePlaneIsShapedOnEachSideAsEachDabAloneWould(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 566);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 63, 200, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        int plane = x0 + 32;
        heighten(h, plane, z0, x0 + 63, z0 + 63, x -> 30);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld west = new SnapshotWorld(world, h.runtime.states(), area);
        SnapshotWorld east = new SnapshotWorld(world, h.runtime.states(), area);
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 8, 1f, Falloff.LINEAR, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 2 * plane, 0));
        BrushSpec plain = raise.withSymmetry(Symmetry.NONE);
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int z = z0 + 28 + i % 3;
            dabs.add(new Dab(i, (plane - 3) * 16 + 8, (topSolid(world, plane - 3, z) + 1) * 16, z * 16 + 8, 255));
        }
        begin(h, 110, raise);
        check(h.service.dabs(h.player, 110, 1, dabs).accepted(), "the stroke");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 10, "the stroke");
        h.service.endStroke(h.player, 110);
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);

        // Each alone: the dab on one snapshot, its copy (stood on the ground as the server does) on the other.
        StrokeState dabAlone = new StrokeState(), copyAlone = new StrokeState();
        for (Dab dab : dabs) {
            SymmetricStep step = SymmetricStep.of(raise, dab, east);
            check(step.dabs().size() == 2 && step.dabs().get(1).blockY() > dab.blockY() + 20, "the copy on the cliff: " + step);
            BrushKernels.forTool(BrushTool.RAISE).applyStep(plain, List.of(dab), dabAlone, west, west::set);
            BrushKernels.forTool(BrushTool.RAISE).applyStep(plain, List.of(step.dabs().get(1)), copyAlone, east, east::set);
        }
        net.minecraft.util.math.BlockPos.Mutable pos = new net.minecraft.util.math.BlockPos.Mutable();
        String first = null;
        for (int y = area.min().y(); y <= area.max().y() && first == null; y++) {
            for (int z = area.min().z(); z <= area.max().z() && first == null; z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int server = Block.getRawIdFromState(world.getBlockState(pos.set(x, y, z)));
                    int alone = (x < plane ? west : east).get(x, y, z);
                    if (server != alone) {
                        first = x + "," + y + "," + z + ": server " + Block.getStateFromRawId(server) + ", alone "
                                + Block.getStateFromRawId(alone);
                        break;
                    }
                }
            }
        }
        check(first == null, "a side differs from its dab alone at " + first);
        WorldSnapshot after = capture(world, area);
        for (int x : new int[] {plane - 2, plane - 1, plane, plane + 1}) {
            boolean moved = false;
            for (int y = area.min().y(); y <= area.max().y() && !moved; y++) moved = before.get(x, y, z0 + 29) != after.get(x, y, z0 + 29);
            check(moved, "the shared column " + (x - x0) + " (x0 +) next to the plane did not move");
        }
        RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
        MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        checkSame(before, capture(world, area), "after the undo");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * A lock on a copy's searched column alone refuses the dab: a job holding only a section 50 blocks above the copy's
     * ground (outside the areas the dab and its copy read and write, inside the copy's 64-block ground search) makes the
     * symmetric dab AREA_BUSY at admission, while the same dab without symmetry is admitted; a dab admitted before such a
     * job is refused AREA_BUSY by the lane, writing nothing. Everything undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_symmetry_terrain_search_lock", tickLimit = LIMIT)
    public void aLockOnACopysSearchedColumnRefusesTheDab(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 568);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 200, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        WorldSnapshot before = capture(world, area);
        // The dab at column x0 + 12 mirrors (around x0 + 24) onto x0 + 35. Both stand at y 105-111, so their areas reach
        // at most y 111 + 2 × 11 + 3 = 136 (section 8); the copy's search reaches y 169 or more (section 10).
        BrushSpec mirrored = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 24), 0));
        BrushSpec plain = mirrored.withSymmetry(Symmetry.NONE);
        int dabY = topSolid(world, x0 + 12, z0 + 20) + 1;
        check(topSolid(world, x0 + 35, z0 + 20) + 1 + 25 < 160 && dabY + 64 >= 160, "the heights the test relies on");
        Dab dab = new Dab(0, (x0 + 12) * 16 + 8, dabY * 16, (z0 + 20) * 16 + 8, 255);
        Box lock = box(x0 + 35, 162, z0 + 20, x0 + 35, 162, z0 + 20);

        RecordingListener fill = new RecordingListener();
        h.fill(lock, "minecraft:glass", fill);
        begin(h, 120, mirrored);
        DabOutcome busy = h.service.dabs(h.player, 120, 1, List.of(dab));
        check(!busy.accepted() && busy.reason() == RejectReason.AREA_BUSY, "the searched column is held: " + busy);
        h.service.endStroke(h.player, 120);
        begin(h, 121, plain);
        check(h.service.dabs(h.player, 121, 2, List.of(dab)).accepted(), "the same dab without symmetry");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1 && fill.result != null, 20, "the dab and the fill");
        h.service.endStroke(h.player, 121);
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        for (int i = 0; i < 2; i++) {
            RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "undo " + i);
            check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        }
        checkSame(before, capture(world, area), "after undoing the plain dab and the fill");

        // Admitted first, then another player's job takes the searched column: the lane refuses the dab.
        begin(h, 122, mirrored);
        check(h.service.dabs(h.player, 122, 3, List.of(dab)).accepted(), "the symmetric dab, nothing held");
        net.minecraft.server.network.ServerPlayerEntity other = h.addPlayer();
        RecordingListener otherFill = new RecordingListener();
        h.fill(other, lock, "minecraft:glass", otherFill);
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 2 && otherFill.result != null, 20, "the lane");
        check(h.events.dabRejections.equals(List.of(RejectReason.AREA_BUSY)), "refusals: " + h.events.dabRejections);
        h.service.endStroke(h.player, 122);
        check(h.service.historyService().undoEntries(h.player.getUuid()).isEmpty(), "the refused step made an entry");
        WorldSnapshot after = capture(world, area);
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (lock.contains(x, y, z)) continue;
                    check(before.get(x, y, z) == after.get(x, y, z), "the refused step changed " + x + "," + y + "," + z);
                }
            }
        }
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    // ------------------------------------------------------------------ helpers

    /** The highest non-air y of column (x, z) from y 200 down, or 0. */
    private static int topSolid(ServerWorld world, int x, int z) {
        net.minecraft.util.math.BlockPos.Mutable pos = new net.minecraft.util.math.BlockPos.Mutable();
        for (int y = 200; y > 0; y--) {
            if (!world.getBlockState(pos.set(x, y, z)).isAir()) return y;
        }
        return 0;
    }

    /** Raises every column of the box by {@code by(x)} blocks: stone up to a new grass top. */
    private static void heighten(Harness h, int xa, int za, int xb, int zb, java.util.function.IntUnaryOperator by) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone"), grass = h.state("minecraft:grass_block");
        for (int x = xa; x <= xb; x++) {
            for (int z = za; z <= zb; z++) {
                int top = topSolid(h.world, x, z), up = by.applyAsInt(x);
                for (int y = top; y <= top + up; y++) writer.write(x, y, z, y < top + up ? stone : grass, null);
            }
        }
    }

    /** {@code count} full-pressure dabs east along z = {@code z}, one block apart from column {@code x}, at y 108. */
    private static List<Dab> row(int firstIndex, int count, int x, int z) {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < count; i++) dabs.add(new Dab(firstIndex + i, (x + i) * 16 + 8, 108 * 16, z * 16 + 8, 255));
        return dabs;
    }

    /**
     * Runs each stroke's kernel on the snapshot, as the client's prediction does (the same kernel on the same spec, each
     * dab replicated by it); "" when every stroke wrote something, else what went wrong.
     */
    static String replay(List<BrushSpec> specs, List<List<Dab>> strokes, SnapshotWorld snapshot) {
        for (int s = 0; s < specs.size(); s++) {
            BrushSpec spec = specs.get(s);
            StrokeState state = new StrokeState();
            int[] writes = {0};
            try {
                for (Dab dab : strokes.get(s)) {
                    BrushKernels.forTool(spec.tool()).apply(spec, dab, state, snapshot, (x, y, z, handle) -> {
                        snapshot.set(x, y, z, handle);
                        writes[0]++;
                    });
                }
            } catch (RuntimeException e) {
                return spec.tool() + " (stroke " + s + ") failed on the snapshot: " + e.getMessage();
            }
            if (writes[0] == 0) return spec.tool() + " (stroke " + s + ") wrote nothing on the snapshot";
        }
        return "";
    }

    /** "" when the world equals the snapshot over {@code area}, else the count and the first difference. */
    static String mismatches(ServerWorld world, Box area, SnapshotWorld snapshot) {
        int mismatches = 0;
        String first = null;
        net.minecraft.util.math.BlockPos.Mutable pos = new net.minecraft.util.math.BlockPos.Mutable();
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int server = Block.getRawIdFromState(world.getBlockState(pos.set(x, y, z)));
                    int kernel = snapshot.get(x, y, z);
                    if (server == kernel) continue;
                    mismatches++;
                    if (first == null) {
                        first = x + "," + y + "," + z + ": server " + Block.getStateFromRawId(server) + ", kernel "
                                + Block.getStateFromRawId(kernel);
                    }
                }
            }
        }
        return mismatches == 0 ? "" : mismatches + " cells differ; first " + first;
    }

    /** Water from a sand floor (y 101) up to y 106 over the box's columns, air above to y 115. */
    private static void pond(Harness h, int xa, int za, int xb, int zb) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int water = h.state("minecraft:water"), air = h.state("minecraft:air"), sand = h.state("minecraft:sand");
        for (int x = xa; x <= xb; x++) {
            for (int z = za; z <= zb; z++) {
                writer.write(x, 101, z, sand, null);
                for (int y = 102; y <= 115; y++) writer.write(x, y, z, y <= 106 ? water : air, null);
            }
        }
    }

    static void begin(Harness h, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(h.player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
    }
}
