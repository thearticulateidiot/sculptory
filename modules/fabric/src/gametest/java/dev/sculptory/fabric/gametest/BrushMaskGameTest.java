package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryEntry;
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
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.world.World;

/**
 * Brush masks on the server: the clip box ("only inside selection") drops every write outside it, strokes stay one
 * exact undo, a clip box outside the world is refused, and clipped and masked strokes (the server-only large Flatten
 * and Smooth included) give exactly what the shared kernel gives on a snapshot.
 */
public final class BrushMaskGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * A clipped Lower through a pond (with its water refill) and a clipped Raise change only cells inside the box;
     * each stroke is one history entry, and undoing both restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_clip_undo", tickLimit = LIMIT)
    public void clippedStrokesLeaveTheOutsideAloneAndUndoExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 100);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 47, 170, z0 + 47);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 48, 48);
        pond(h, x0 + 20, z0 + 18, x0 + 27, z0 + 25);
        WorldSnapshot before = capture(world, area);
        // The box takes the pond's west shore and cuts through the middle of both strokes.
        Box clip = box(x0 + 17, 103, z0 + 16, x0 + 23, 111, z0 + 27);
        BrushSpec lower = new BrushSpec(BrushTool.LOWER, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY,
                0, 0, 1L, clip);
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.SMOOTH, Shape.SQUARE, null, SurfaceMask.ANY,
                0, 0, 2L, clip);
        begin(h, 30, lower);
        check(h.service.dabs(h.player, 30, 1, path(x0 + 14, z0 + 21, 108, 12)).accepted(), "lower dabs");
        RecordingListener undo = new RecordingListener();
        RecordingListener undoAgain = new RecordingListener();
        int[] inside = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(h.acks.seqs.size() == 1, "lower applying"))
                .createAndAdd(() -> {
                    h.service.endStroke(h.player, 30);
                    begin(h, 31, raise);
                    check(h.service.dabs(h.player, 31, 2, path(x0 + 15, z0 + 23, 108, 10)).accepted(), "raise dabs");
                })
                .createAndAdd(() -> check(h.acks.seqs.size() == 2, "raise applying"))
                .createAndAdd(() -> {
                    h.service.endStroke(h.player, 31);
                    inside[0] = 0; // a failed check retries this step
                    check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
                    List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
                    check(entries.size() == 2, "one entry per stroke, got " + entries.size());
                    WorldSnapshot after = capture(world, area);
                    int water = h.state("minecraft:water");
                    boolean refilled = false;
                    for (int y = area.min().y(); y <= area.max().y(); y++) {
                        for (int z = area.min().z(); z <= area.max().z(); z++) {
                            for (int x = area.min().x(); x <= area.max().x(); x++) {
                                int was = before.get(x, y, z), now = after.get(x, y, z);
                                if (was == now) continue;
                                check(clip.contains(x, y, z), "changed outside the box at " + x + "," + y + "," + z + ": "
                                        + Block.getStateFromRawId(was) + " -> " + Block.getStateFromRawId(now));
                                inside[0]++;
                                if (now == water) refilled = true;
                            }
                        }
                    }
                    check(inside[0] > 20, "the strokes changed only " + inside[0] + " cells inside the box");
                    check(refilled, "lowering beside the pond refilled no cell with water");
                    long recorded = 0;
                    for (HistoryEntry entry : entries) recorded += entry.record().before().cellCount();
                    check(recorded >= inside[0], "records hold " + recorded + " cells for " + inside[0] + " changed");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "first undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    h.undo(undoAgain);
                })
                .createAndAdd(() -> check(undoAgain.result != null, "second undo running"))
                .createAndAdd(() -> {
                    check(undoAgain.result.outcome() == JobOutcome.COMPLETED && undoAgain.result.skippedConflicts() == 0,
                            "undo " + undoAgain.result);
                    checkSame(before, capture(world, area), "after undoing both strokes");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A clip box outside the world (beyond the build height or the horizontal limit) is refused as INVALID. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_clip_refused", tickLimit = LIMIT)
    public void clipBoxesOutsideTheWorldAreRefused(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int bottom = world.getBottomY(), top = world.getTopY();
        BrushSpec free = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L);
        int far = World.HORIZONTAL_LIMIT + 1;
        List<Box> outside = List.of(
                box(0, top - 4, 0, 3, top, 3),                   // one layer above the build height
                box(0, bottom - 1, 0, 3, bottom + 4, 3),         // one layer below it
                box(far, 64, 0, far + 2, 70, 2),                 // beyond the horizontal limit (inside the spec's range)
                box(0, 64, -far - 2, 2, 70, -far));
        int id = 40;
        for (Box clip : outside) {
            try {
                h.service.beginStroke(h.player, id, free.withClip(clip));
                throw new GameTestException("a clip box outside the world was accepted: " + clip);
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.INVALID, "refused with " + e.reason() + " for " + clip);
            }
            check(h.service.openStroke(h.player.getUuid()).isEmpty(), "a refused stroke is open");
            id++;
        }
        // The whole build height and the world's edge are fine.
        begin(h, id, free.withClip(box(-World.HORIZONTAL_LIMIT, bottom, 0, World.HORIZONTAL_LIMIT, top - 1, 5)));
        check(h.service.openStroke(h.player.getUuid()).orElse(-1) == id, "the in-world clip box was not accepted");
        h.service.endStroke(h.player, id);
        h.close();
        context.complete();
    }

    /**
     * Clipped and masked strokes on the server match the same kernels run on a snapshot, cell for cell: exact-state
     * and inverted masks, Lower through a pond, and a radius-24 Flatten and Smooth (sizes the client does not
     * predict, so only the server runs them). Every kernel write is inside its box.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mask_clip_kernel", tickLimit = LIMIT)
    public void clippedAndMaskedStrokesMatchTheSnapshotKernel(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 102);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 63, 170, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        pond(h, x0 + 34, z0 + 28, x0 + 40, z0 + 34);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int snowy = h.state("minecraft:grass_block[snowy=true]");
        for (int x = x0 + 20; x <= x0 + 44; x += 2) {
            for (int z = z0 + 20; z <= z0 + 44; z += 3) {
                int y = surface(world, x, z);
                if (world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)).isOf(Blocks.GRASS_BLOCK)) {
                    writer.write(x, y, z, snowy, null);
                }
            }
        }
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);

        SurfaceMask exactSnowy = new SurfaceMask.SurfaceBlocks(new CellMask.States(new int[] {snowy}));
        SurfaceMask notGrassBlocks = new SurfaceMask.Not(new SurfaceMask.SurfaceBlocks(
                new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block")))));
        SurfaceMask notHigh = new SurfaceMask.Not(new SurfaceMask.And(List.of(
                new SurfaceMask.Elevation(107, 120), new SurfaceMask.Slope(0, 1))));
        int sand = h.state("minecraft:sand");
        int cobble = h.state("minecraft:cobblestone");
        List<BrushSpec> specs = List.of(
                new BrushSpec(BrushTool.PAINT, 6, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(sand), exactSnowy,
                        1, 0, 1L, box(x0 + 22, 90, z0 + 22, x0 + 30, 170, z0 + 40)),
                new BrushSpec(BrushTool.PALETTE, 5, 1f, Falloff.CONSTANT, Shape.SQUARE,
                        new Pattern.Weighted(new int[] {sand, cobble}, new int[] {1, 2}, 5L), notGrassBlocks, 2, 0, 2L,
                        box(x0 + 26, 100, z0 + 26, x0 + 40, 108, z0 + 36)),
                new BrushSpec(BrushTool.LOWER, 5, 1f, Falloff.LINEAR, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 3L,
                        box(x0 + 30, 104, z0 + 26, x0 + 36, 112, z0 + 36)),
                new BrushSpec(BrushTool.RAISE, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, notHigh, 0, 0, 4L,
                        box(x0 + 24, 90, z0 + 30, x0 + 42, 170, z0 + 33)),
                new BrushSpec(BrushTool.FLATTEN, 24, 0.8f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 106, 5L,
                        box(x0 + 20, 100, z0 + 14, x0 + 44, 170, z0 + 30)),
                new BrushSpec(BrushTool.SMOOTH, 24, 1f, Falloff.CONSTANT, Shape.SQUARE, null, notHigh, 0, 0, 6L,
                        box(x0 + 28, 90, z0 + 18, x0 + 50, 170, z0 + 46)));
        List<List<Dab>> strokes = new ArrayList<>();
        for (int s = 0; s < specs.size(); s++) {
            List<Dab> dabs = new ArrayList<>();
            boolean large = specs.get(s).radius() > 20;
            for (int i = 0; i < 12; i++) {
                // Large brushes stay near the centre so their reads fit the snapshot; the others wander more.
                int x = large ? x0 + 30 + i % 4 : x0 + 22 + i * 2 + s;
                int z = large ? z0 + 30 + (i * 3) % 4 : z0 + 24 + (i * (s + 2)) % 16;
                dabs.add(new Dab(i, x * 16 + (i * 5) % 16, (108 + i % 3) * 16 + 4, z * 16 + (i * 7) % 16,
                        150 + (i * 29) % 106));
            }
            strokes.add(dabs);
        }

        int[] seq = {1};
        TimedTaskRunner runner = context.createTimedTaskRunner();
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            runner.createAndAdd(() -> {
                begin(h, 50 + current, specs.get(current));
                List<Dab> dabs = strokes.get(current);
                for (int from = 0; from < dabs.size(); from += 6) {
                    DabOutcome outcome = h.service.dabs(h.player, 50 + current, seq[0]++,
                            dabs.subList(from, Math.min(dabs.size(), from + 6)));
                    check(outcome.accepted(), "stroke " + current + " batch " + from + ": " + outcome);
                }
            });
            runner.createAndAdd(() -> check(h.acks.seqs.size() == seq[0] - 1, "stroke " + current + " still applying"));
            runner.createAndAdd(() -> h.service.endStroke(h.player, 50 + current));
        }
        // The replay mutates the snapshot, so it runs once; a failed check is retried against the same verdict.
        String[] replay = {null};
        runner.createAndAdd(() -> {
            check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
            if (replay[0] == null) replay[0] = replay(specs, strokes, snapshot);
            check(replay[0].isEmpty(), replay[0]);
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
            check(mismatches == 0, mismatches + " cells differ; first " + first);
            check(h.service.historyService().undoEntries(h.player.getUuid()).size() == specs.size(), "one entry per stroke");
            forceChunks(world, area, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs each stroke's kernel on the snapshot, in order, writing through; "" when every stroke wrote something
     * and only inside its box, else what went wrong first.
     */
    private static String replay(List<BrushSpec> specs, List<List<Dab>> strokes, SnapshotWorld snapshot) {
        for (int s = 0; s < specs.size(); s++) {
            BrushSpec spec = specs.get(s);
            StrokeState state = new StrokeState();
            int[] writes = {0};
            String[] outside = {null};
            try {
                for (Dab dab : strokes.get(s)) {
                    BrushKernels.forTool(spec.tool()).apply(spec, dab, state, snapshot, (x, y, z, handle) -> {
                        if (!spec.clip().contains(x, y, z) && outside[0] == null) outside[0] = x + "," + y + "," + z;
                        snapshot.set(x, y, z, handle);
                        writes[0]++;
                    });
                }
            } catch (RuntimeException e) {
                return spec.tool() + " (stroke " + s + ") failed on the snapshot: " + e.getMessage();
            }
            if (outside[0] != null) return spec.tool() + " (stroke " + s + ") wrote outside its box at " + outside[0];
            if (writes[0] == 0) return spec.tool() + " (stroke " + s + ") wrote nothing on the snapshot";
        }
        return "";
    }

    /** Water from the stone floor (y 101) up to y 106 over the box's columns, air above to y 115. */
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

    /** {@code count} dabs east along z = {@code z}, one block apart, from column {@code x}. */
    private static List<Dab> path(int x, int z, int y, int count) {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < count; i++) dabs.add(new Dab(i, (x + i) * 16 + 8, y * 16, z * 16 + (i * 5) % 16, 255));
        return dabs;
    }

    /** The topmost non-air y of a column (from y 140 down to the stone floor). */
    private static int surface(ServerWorld world, int x, int z) {
        for (int y = 140; y >= 100; y--) {
            if (!world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z)).isAir()) return y;
        }
        return 100;
    }

    private static void begin(Harness h, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(h.player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
    }
}
