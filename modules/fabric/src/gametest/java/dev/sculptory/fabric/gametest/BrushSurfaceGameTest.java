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
import dev.sculptory.core.brush.SurfacePlane;
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.world.border.WorldBorder;

/**
 * The terrain brushes' two modes on the server: "Terrain (from above)" writes
 * exactly what it wrote before the Surface mode existed; "Surface (any direction)" works walls and ceilings exactly as the
 * shared kernel does, undoes exactly, is refused where it should be, and a radius-32 dab computes in milliseconds.
 * Slots 940-946.
 */
public final class BrushSurfaceGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    /**
     * The area's fingerprint after the golden strokes, taken with the column kernel before the Surface mode existed
     * (2026-09-29, main fe0b6913).
     */
    private static final long TERRAIN_GOLDEN = 5634803476156156040L;

    /**
     * Terrain-mode Raise, Lower (through a pond), Smooth, Flatten and a mirrored Raise, through the server's brush lane,
     * leave the area exactly as they did before the Surface mode existed (a fingerprint of every cell's state).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_brush_surface_golden", tickLimit = LIMIT)
    public void terrainModeStrokesMatchTheirGoldenFingerprint(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 940);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 150, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        decorate(h, x0, z0);
        List<BrushSpec> specs = List.of(
                new BrushSpec(BrushTool.RAISE, 5, 0.7f, Falloff.LINEAR, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L),
                new BrushSpec(BrushTool.LOWER, 4, 1f, Falloff.CONSTANT, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 2L),
                new BrushSpec(BrushTool.SMOOTH, 6, 0.6f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 3L),
                new BrushSpec(BrushTool.FLATTEN, 5, 0.6f, Falloff.SPHERE, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 106, 4L),
                new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 5L,
                        null, new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 24), 0)));
        TimedTaskRunner runner = context.createTimedTaskRunner();
        int[] seq = {1};
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            runner.createAndAdd(() -> {
                begin(h, 70 + current, specs.get(current));
                List<Dab> dabs = path(x0, z0, current);
                DabOutcome outcome = h.service.dabs(h.player, 70 + current, seq[0]++, dabs);
                check(outcome.accepted(), "stroke " + current + ": " + outcome);
            });
            runner.createAndAdd(() -> check(h.acks.seqs.size() == seq[0] - 1, "stroke " + current + " still applying"));
            runner.createAndAdd(() -> h.service.endStroke(h.player, 70 + current));
        }
        runner.createAndAdd(() -> {
            check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
            long print = fingerprint(world, area);
            check(print == TERRAIN_GOLDEN, "the terrain-mode strokes left fingerprint " + print + ", not "
                    + TERRAIN_GOLDEN);
            forceChunks(world, area, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    /**
     * Surface-mode strokes on a wall and a ceiling through the server's brush lane: Raise on a wall's west face pushes
     * it west, Lower on its east face pulls it west, Smooth takes a wall's bumps and dents, Flatten levels an overhang's
     * underside to its plane, a Mirror Z Raise works the mirrored stretch of the same wall, and a radius-24 Smooth (a
     * size only the server runs) with a clip box and a Y mask stays in its box. Every stroke writes exactly what the
     * shared kernel writes on a snapshot, each is one history entry, and undoing them all restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_brush_surface_walls", tickLimit = LIMIT)
    public void wallAndCeilingStrokesMatchTheKernelAndUndoExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 942);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 200, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        wallAndOverhang(h, x0, z0);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);

        Box clip = box(x0 + 22, 118, z0 + 32, x0 + 38, 135, z0 + 48);
        List<BrushSpec> specs = List.of(
                surface(BrushTool.RAISE, 4, 1f, Falloff.CONSTANT, null, 1L),
                surface(BrushTool.LOWER, 3, 1f, Falloff.LINEAR, null, 2L),
                surface(BrushTool.SMOOTH, 5, 1f, Falloff.CONSTANT, null, 3L),
                surface(BrushTool.FLATTEN, 5, 1f, Falloff.CONSTANT, new SurfacePlane(Facing.DOWN, 126), 4L),
                surface(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, null, 5L)
                        .withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_Z, 0, 2 * (z0 + 40))),
                new BrushSpec(BrushTool.SMOOTH, 24, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                        new SurfaceMask.Elevation(120, 132), 0, 0, 6L, clip).withSurface(null));
        List<List<Dab>> strokes = List.of(
                line(i -> new Dab(i, (x0 + 44) * 16, (116 + i % 3) * 16 + 8, (z0 + 24 + i) * 16 + 8, 255)),
                line(i -> new Dab(i, (x0 + 51) * 16, 120 * 16 + 8, (z0 + 24 + 2 * i) * 16 + 8, 255)),
                line(i -> new Dab(i, (x0 + 44) * 16, (124 + i % 4) * 16 + 8, (z0 + 34 + i) * 16 + 8, 255)),
                line(i -> new Dab(i, (x0 + 24 + i) * 16 + 8, 126 * 16, (z0 + 36 + i % 5) * 16 + 8, 255)),
                line(i -> new Dab(i, (x0 + 44) * 16, (132 + i % 3) * 16 + 8, (z0 + 24 + i / 2) * 16 + 8, 255)),
                line(i -> new Dab(i, (x0 + 30) * 16 + 4, 126 * 16, (z0 + 40) * 16 + i, 255)));

        int[] seq = {1};
        TimedTaskRunner runner = context.createTimedTaskRunner();
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            runner.createAndAdd(() -> {
                begin(h, 90 + current, specs.get(current));
                DabOutcome outcome = h.service.dabs(h.player, 90 + current, seq[0]++, strokes.get(current));
                check(outcome.accepted(), "stroke " + current + ": " + outcome);
            });
            runner.createAndAdd(() -> check(h.acks.seqs.size() == seq[0] - 1, "stroke " + current + " still applying"));
            runner.createAndAdd(() -> h.service.endStroke(h.player, 90 + current));
        }
        // The replay mutates the snapshot, so it runs once; a failed check is retried against the same verdict.
        String[] replay = {null};
        runner.createAndAdd(() -> {
            check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
            if (replay[0] == null) replay[0] = replay(specs, strokes, snapshot);
            check(replay[0].isEmpty(), replay[0]);
            WorldSnapshot after = capture(world, area);
            int mismatches = 0;
            String first = null;
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        if (after.get(x, y, z) == snapshot.get(x, y, z)) continue;
                        mismatches++;
                        if (first == null) first = x + "," + y + "," + z;
                    }
                }
            }
            check(mismatches == 0, mismatches + " cells differ from the kernel; first " + first);
            // The right way: Raise filled x0 + 43 in front of the west face, Lower emptied x0 + 50 behind the east face,
            // Flatten took the bumps under the overhang and filled its dent.
            check(changed(before, after, x0 + 43, 114, 120, z0 + 22, z0 + 33, true) >= 10, "the west face did not move west");
            check(changed(before, after, x0 + 50, 117, 123, z0 + 22, z0 + 40, false) >= 5, "the east face did not move in");
            check(after.get(x0 + 26, 125, z0 + 38) == air(h) && after.get(x0 + 28, 126, z0 + 35) != air(h),
                    "the overhang's underside was not flattened");
            check(changed(before, after, x0 + 43, 130, 136, z0 + 50, z0 + 58, true) >= 5, "the mirrored stretch did not move");
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        boolean big = clip.contains(x, y, z);
                        // Only the large Smooth's box may change around the overhang's middle (x0 + 22..38).
                        if (x >= x0 + 22 && x <= x0 + 38 && y >= 131 && !big) {
                            check(before.get(x, y, z) == after.get(x, y, z), "outside the box at " + x + "," + y + "," + z);
                        }
                    }
                }
            }
            check(h.service.historyService().undoEntries(h.player.getUuid()).size() == specs.size(), "one entry per stroke");
        });
        List<RecordingListener> undos = new ArrayList<>();
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            runner.createAndAdd(() -> {
                if (undos.size() == current) {
                    RecordingListener undo = new RecordingListener();
                    undos.add(undo);
                    h.undo(undo);
                }
            });
            runner.createAndAdd(() -> {
                RecordingListener undo = undos.get(current);
                check(undo.result != null, "undo " + current + " running");
                check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                        "undo " + current + ": " + undo.result);
            });
        }
        runner.createAndAdd(() -> {
            checkSame(before, capture(world, area), "after undoing every Surface stroke");
            forceChunks(world, area, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    /**
     * Refusals and limits: a Surface dab over an unloaded chunk is refused UNLOADED; a lock beside a mirrored copy's
     * column (where a Surface copy looks for a surface at its own point) refuses the dab AREA_BUSY while the Terrain
     * mode, which searches only the column, admits it; a Surface stroke in the open air writes nothing and makes no
     * history entry; a Surface stroke wholly beyond the world border is refused PROTECTED and writes nothing, and one
     * across the border writes only inside it and undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_brush_surface_refused", tickLimit = LIMIT)
    public void surfaceRefusalsAndLimits(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 944);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 200, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        wallAndOverhang(h, x0, z0);
        WorldSnapshot before = capture(world, area);
        BrushSpec raise = surface(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, null, 1L);

        // Over an unloaded chunk, 3,000 blocks away.
        begin(h, 130, raise);
        DabOutcome unloaded = h.service.dabs(h.player, 130, 1, List.of(new Dab(0, (x0 + 3000) * 16, 110 * 16, z0 * 16, 255)));
        check(!unloaded.accepted() && unloaded.reason() == RejectReason.UNLOADED, "an unloaded chunk: " + unloaded);
        h.service.endStroke(h.player, 130);

        // A lock one column beside a mirrored copy's column, 50 blocks above, in the next section (locks hold whole
        // sections): the copy of column x0 + 12 around x0 + 30 is column x0 + 47, the last of its section. Only the
        // Surface copy's check (the columns around its own) reaches x0 + 48; its area stops 25 blocks above the dab.
        int dabY = top(world, x0 + 12, z0 + 70) + 1;
        Dab dab = new Dab(0, (x0 + 12) * 16 + 8, dabY * 16, (z0 + 70) * 16 + 8, 255);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 30), 0);
        Box lock = box(x0 + 48, dabY + 50, z0 + 70, x0 + 48, dabY + 50, z0 + 70);
        RecordingListener fill = new RecordingListener();
        h.fill(lock, "minecraft:glass", fill);
        begin(h, 131, raise.withSymmetry(mirror));
        DabOutcome busy = h.service.dabs(h.player, 131, 2, List.of(dab));
        check(!busy.accepted() && busy.reason() == RejectReason.AREA_BUSY, "beside the copy's column: " + busy);
        h.service.endStroke(h.player, 131);
        BrushSpec terrain = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                0, 1L, null, mirror);
        begin(h, 132, terrain);
        check(h.service.dabs(h.player, 132, 3, List.of(dab)).accepted(), "the Terrain mode searches the column only");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1 && fill.result != null, 20, "dab and fill");
        h.service.endStroke(h.player, 132);
        for (int i = 0; i < 2; i++) {
            RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "undo " + i);
            check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        }
        checkSame(before, capture(world, area), "after the refused dabs and the undone ones");

        // In the open air (30 blocks above everything): no surface in the ball, nothing written, no entry.
        int entries = h.service.historyService().undoEntries(h.player.getUuid()).size();
        begin(h, 133, surface(BrushTool.SMOOTH, 4, 1f, Falloff.CONSTANT, null, 2L));
        check(h.service.dabs(h.player, 133, 4, List.of(new Dab(0, (x0 + 60) * 16, 170 * 16, (z0 + 10) * 16, 255)))
                .accepted(), "a dab in the air");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 2, 20, "the air dab");
        h.service.endStroke(h.player, 133);
        check(h.service.historyService().undoEntries(h.player.getUuid()).size() == entries, "an empty stroke made an entry");
        checkSame(before, capture(world, area), "after the stroke in the air");

        // Beyond the world border: every cell is protected, so the lane refuses the dab PROTECTED.
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(x0 - 100_000, z0);
            border.setSize(100_000);
            begin(h, 134, raise);
            check(h.service.dabs(h.player, 134, 5, List.of(new Dab(0, (x0 + 44) * 16, 120 * 16 + 8, (z0 + 30) * 16, 255)))
                    .accepted(), "the dab beyond the border is admitted");
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 3, 20, "the border dab");
            h.service.endStroke(h.player, 134);
            check(h.events.dabRejections.equals(List.of(RejectReason.PROTECTED)), "refusals: " + h.events.dabRejections);
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        checkSame(before, capture(world, area), "after the refused border dab");

        // Partial: the border's edge runs across the wall at z0 + 30, so a Lower along the wall's west face writes the
        // cells north of it only, the rest being protected; undoing it restores the area exactly.
        try {
            border.setCenter(x0 + 44, z0 + 30 - 100_000);
            border.setSize(200_000);
            begin(h, 135, surface(BrushTool.LOWER, 3, 1f, Falloff.CONSTANT, null, 3L));
            // Each dab, on the edge (z0 + 29.5) at its own height, has fresh cells on both sides, so none is refused as
            // wholly protected.
            List<Dab> along = new ArrayList<>();
            for (int i = 0; i < 6; i++) along.add(new Dab(i, (x0 + 44) * 16, (108 + 4 * i) * 16 + 8, (z0 + 29) * 16 + 8, 255));
            check(h.service.dabs(h.player, 135, 6, along).accepted(), "the dabs across the border");
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 4, 20, "the dabs across the border");
            h.service.endStroke(h.player, 135);
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        check(h.events.dabRejections.equals(List.of(RejectReason.PROTECTED)), "refusals: " + h.events.dabRejections);
        WorldSnapshot partial = capture(world, area);
        int inside = 0;
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    if (before.get(x, y, z) == partial.get(x, y, z)) continue;
                    check(z < z0 + 30, "a protected cell changed at " + x + "," + y + "," + z);
                    inside++;
                }
            }
        }
        check(inside > 5, "only " + inside + " cells changed inside the border");
        RecordingListener undoPartial = MultiplayerGameTest.historyStep(h, h.player, true);
        MultiplayerGameTest.tickUntil(executor, () -> undoPartial.result != null, 20, "undoing the partial stroke");
        check(undoPartial.result.outcome() == JobOutcome.COMPLETED && undoPartial.result.skippedConflicts() == 0,
                "undo " + undoPartial.result);
        checkSame(before, capture(world, area), "after undoing the partial stroke");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * How long one radius-32 dab takes to compute on a real world (the kernel alone, through the server's world reader;
     * writes discarded): each Surface tool and, for comparison, its Terrain mode, on rough ground with a wall and an
     * overhang, and a Surface Smooth step of four copies (Rotate 4), the heaviest step a stroke makes. Each is one
     * stroke's dabs (the stroke's arrays reused, as the server does), after a round that warms the kernels up (run
     * alone, this test would otherwise time them interpreted); best of twenty. Logged; the bound here only catches a
     * pathological slowdown.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_brush_surface_timing", tickLimit = LIMIT)
    public void aRadius32DabComputesInMilliseconds(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 946);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 200, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        wallAndOverhang(h, x0, z0);
        FabricWorldReader reader = new FabricWorldReader(world, h.runtime.states());
        // On the wall's west face, halfway up: the ball takes in the wall, the overhang and the ground.
        Dab dab = new Dab(0, (x0 + 44) * 16, 120 * 16 + 8, (z0 + 40) * 16 + 8, 255);
        StringBuilder report = new StringBuilder();
        for (int round = 0; round < 2; round++) {
            for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.FLATTEN)) {
                BrushSpec terrain = new BrushSpec(tool, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                        118, 7L);
                SurfacePlane plane = tool == BrushTool.FLATTEN ? new SurfacePlane(Facing.WEST, x0 + 44) : null;
                BrushSpec surface = terrain.withSurface(plane);
                for (BrushSpec spec : List.of(surface, terrain)) {
                    time(round, spec.mode() + " " + tool, spec, List.of(dab), reader, report);
                }
            }
            // Four copies turned about the area's centre, each on the surface where it lands.
            BrushSpec copies = new BrushSpec(BrushTool.SMOOTH, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                    SurfaceMask.ANY, 0, 118, 7L).withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_4, 2 * x0 + 80,
                    2 * z0 + 80)).withSurface(null);
            List<Dab> step = SymmetricStep.of(copies, dab, reader).dabs();
            check(step.size() == 4, "the four-copy step has " + step.size() + " dabs");
            time(round, "SURFACE SMOOTH four copies", copies, step, reader, report);
        }
        org.slf4j.LoggerFactory.getLogger("sculptory").info("Radius-32 dab compute:{}", report);
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /** Times one step of {@code spec} twenty times as one stroke; reports the best in round 1. */
    private static void time(int round, String what, BrushSpec spec, List<Dab> step, FabricWorldReader reader,
                             StringBuilder report) {
        long best = Long.MAX_VALUE;
        int[] cells = {0};
        StrokeState stroke = new StrokeState();
        for (int run = 0; run < 20; run++) {
            cells[0] = 0;
            reader.invalidate();
            long start = System.nanoTime();
            BrushKernels.forTool(spec.tool()).applyStep(spec, step, stroke, reader, (x, y, z, s) -> cells[0]++);
            best = Math.min(best, System.nanoTime() - start);
        }
        if (round == 0) return;
        report.append(String.format(" %s %.1f ms (%d cells);", what, best / 1e6, cells[0]));
        check(best < 1_000_000_000L, what + " radius 32 took " + best / 1e6 + " ms");
    }

    // ------------------------------------------------------------------ helpers

    private static BrushSpec surface(BrushTool tool, int radius, float strength, Falloff falloff, SurfacePlane plane,
                                     long seed) {
        return new BrushSpec(tool, radius, strength, falloff, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, seed)
                .withSurface(plane);
    }

    /** Twelve dabs of a stroke. */
    private static List<Dab> line(java.util.function.IntFunction<Dab> dab) {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 12; i++) dabs.add(dab.apply(i));
        return dabs;
    }

    /**
     * A stone wall (x0 + 44..50, z0 + 20..60, from the ground to y 140) with bumps (x0 + 43) and dents (x0 + 44) on its
     * west face and a dirt band at y 126, and a stone overhang (x0 + 20..40, z0 + 30..50, y 126-130) with two bumps
     * hanging under it (y 125) and a dent in its underside, and further east a stalactite, a bump and a dent.
     */
    static void wallAndOverhang(Harness h, int x0, int z0) {
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt"), air = h.state("minecraft:air");
        for (int x = x0 + 44; x <= x0 + 50; x++) {
            for (int z = z0 + 20; z <= z0 + 60; z++) {
                for (int y = 100; y <= 140; y++) writer.write(x, y, z, x == x0 + 44 && y == 126 ? dirt : stone, null);
            }
        }
        writer.write(x0 + 43, 124, z0 + 36, stone, null);
        writer.write(x0 + 43, 127, z0 + 40, dirt, null);
        writer.write(x0 + 44, 125, z0 + 38, air, null);
        writer.write(x0 + 44, 128, z0 + 42, air, null);
        for (int x = x0 + 20; x <= x0 + 40; x++) {
            for (int z = z0 + 30; z <= z0 + 50; z++) {
                for (int y = 126; y <= 130; y++) writer.write(x, y, z, stone, null);
            }
        }
        writer.write(x0 + 26, 125, z0 + 38, stone, null);
        writer.write(x0 + 30, 125, z0 + 42, stone, null);
        writer.write(x0 + 28, 126, z0 + 35, air, null);
        // Beyond the Flatten stroke's reach, for the large Smooth: a stalactite, a bump and a dent.
        writer.write(x0 + 35, 125, z0 + 45, stone, null);
        writer.write(x0 + 35, 124, z0 + 45, stone, null);
        writer.write(x0 + 36, 125, z0 + 47, stone, null);
        writer.write(x0 + 33, 126, z0 + 47, air, null);
    }

    /** Cells in column x, y and z ranges that went from air to a block ({@code filled}) or from a block to air. */
    private static int changed(WorldSnapshot before, WorldSnapshot after, int x, int y0, int y1, int za, int zb,
                               boolean filled) {
        int air = Block.getRawIdFromState(net.minecraft.block.Blocks.AIR.getDefaultState());
        int count = 0;
        for (int y = y0; y <= y1; y++) {
            for (int z = za; z <= zb; z++) {
                boolean was = before.get(x, y, z) == air, now = after.get(x, y, z) == air;
                if (filled ? was && !now : !was && now) count++;
            }
        }
        return count;
    }

    private static int air(Harness h) {
        return h.state("minecraft:air");
    }

    /**
     * Runs each stroke's kernel on the snapshot, in order, writing through (each dab with its copies, as the lane does);
     * "" when every stroke wrote something, else what went wrong first.
     */
    private static String replay(List<BrushSpec> specs, List<List<Dab>> strokes, SnapshotWorld snapshot) {
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
                return spec.tool() + " (stroke " + s + ") failed on the snapshot: " + e;
            }
            if (writes[0] == 0) return spec.tool() + " (stroke " + s + ") wrote nothing on the snapshot";
        }
        return "";
    }

    /** A pond (sand bed at 103, water to 107), short grass and tall grass on some columns. */
    static void decorate(Harness h, int x0, int z0) {
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int water = h.state("minecraft:water"), air = h.state("minecraft:air"), sand = h.state("minecraft:sand");
        for (int x = x0 + 20; x <= x0 + 26; x++) {
            for (int z = z0 + 18; z <= z0 + 24; z++) {
                writer.write(x, 103, z, sand, null);
                for (int y = 104; y <= 118; y++) writer.write(x, y, z, y <= 107 ? water : air, null);
            }
        }
        int shortGrass = h.state("minecraft:short_grass");
        int tallLower = h.state("minecraft:tall_grass[half=lower]");
        int tallUpper = h.state("minecraft:tall_grass[half=upper]");
        for (int dx = 0; dx < 48; dx++) {
            for (int dz = 0; dz < 48; dz++) {
                int x = x0 + dx, z = z0 + dz;
                if (x >= x0 + 20 && x <= x0 + 26 && z >= z0 + 18 && z <= z0 + 24) continue;
                int plant = Math.floorMod(dx * 31 + dz * 17, 11);
                int top = top(h.world, x, z);
                if (plant == 0) writer.write(x, top + 1, z, shortGrass, null);
                if (plant == 1) {
                    writer.write(x, top + 1, z, tallLower, null);
                    writer.write(x, top + 2, z, tallUpper, null);
                }
            }
        }
    }

    /** Twelve dabs of stroke {@code s} over the middle of the area. */
    private static List<Dab> path(int x0, int z0, int s) {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int x = x0 + 14 + i * 2 + s;
            int z = z0 + 16 + (i * (s + 2)) % 12;
            dabs.add(new Dab(i, x * 16 + (i * 5) % 16, (108 + i % 3) * 16 + 4, z * 16 + (i * 7) % 16,
                    150 + (i * 29) % 106));
        }
        return dabs;
    }

    /** FNV-1a over every cell's state text, relative to the area (so the test's position makes no difference). */
    static long fingerprint(ServerWorld world, Box area) {
        long hash = 0xcbf29ce484222325L;
        net.minecraft.util.math.BlockPos.Mutable pos = new net.minecraft.util.math.BlockPos.Mutable();
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int text = world.getBlockState(pos.set(x, y, z)).toString().hashCode();
                    for (int i = 0; i < 4; i++) {
                        hash ^= (text >>> (8 * i)) & 0xFF;
                        hash *= 0x100000001b3L;
                    }
                }
            }
        }
        return hash;
    }

    /** The topmost non-air y of a column (from y 140 down to the stone floor). */
    static int top(ServerWorld world, int x, int z) {
        for (int y = 140; y >= 100; y--) {
            if (!world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)).isAir()) return y;
        }
        return 100;
    }

    static void begin(Harness h, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(h.player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
    }
}
