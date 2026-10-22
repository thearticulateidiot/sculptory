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
import static dev.sculptory.mc.gametest.HeightmapGameTest.heightmap;

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
import dev.sculptory.mc.world.LightBatch;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.LoggerFactory;

/**
 * The Heightmap brush at radius 128 (a 257-wide stamp) on the server: a cone 128 blocks tall and then a 128-deep cut,
 * each one dab through a dedicated server's brush lane, are worked out and written over many ticks with no tick past
 * the lane's allowance, raise and cut the centre by 128, and undo exactly; and the wide stamp's limits: the server's
 * {@code maxHeightmapRadius}, {@code maxHeightmapHeight} and {@code maxOpVolume} refuse it without {@code limit.bypass},
 * and a step past {@link HeightmapSpec#MAX_STEP_CELLS} is refused for everyone. Region slot 1700.
 */
public final class HeightmapWideGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    /** The ground: stone from {@value #FLOOR} to a grass top between 104 and 110, 280 columns a side. */
    private static final int FLOOR = -20;
    private static final int SIDE = 280;

    /**
     * A radius-128 cone 128 tall, then a 128-deep cut of it, on deep ground, through a dedicated server's lane: each
     * dab's working out spans several ticks and the whole dab many more, with at most
     * {@link ShapeBrushLaneGameTest#LONG_TICKS_ALLOWED} ticks past {@link ShapeBrushLaneGameTest#TICK_MICROS} and none
     * past {@link ShapeBrushLaneGameTest#STALL_MICROS} (garbage collection pauses left out); the centre rises and is cut
     * by 128; each stroke is one entry, and undoing both restores the area exactly; the light engine gets the writes'
     * light checks per chunk column and part, not a task per cell. Logged: ticks and the longest tick.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_heightmap_wide", timeoutTicks = LIMIT)
    public void aRadius128DabSpreadsOverTicksAndUndoesExactly(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = new EditExecutor<>(EngineTestSupport.runtime(context),
                EditExecutor.Settings.from(SculptoryConfig.defaults(), true));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1700);
        int x0 = at[0], z0 = at[1];
        Box ground = box(x0, FLOOR, z0, x0 + SIDE - 1, 103, z0 + SIDE - 1);
        Box area = box(x0, FLOOR - 4, z0, x0 + SIDE - 1, 245, z0 + SIDE - 1);
        loadAndForce(world, area);
        try {
            RecordingListener fill = new RecordingListener();
            try {
                h.service.run(h.player, new OpSpec.Fill(ground, new Pattern.Single(h.state("minecraft:stone")),
                        CellMask.ANY), RunOptions.DEFAULT, fill);
            } catch (EditRejected e) {
                throw new GameTestAssertException("the ground's fill was refused: " + e.getMessage());
            }
            MultiplayerGameTest.tickUntil(executor, () -> fill.result != null, 20_000, "the ground");
            StrokeGameTest.terrain(h, x0, z0, SIDE, SIDE);
            int entries = h.service.historyService().undoEntries(h.player.getUUID()).size();
            WorldSnapshot before = capture(world, area);
            int cx = x0 + SIDE / 2, cz = z0 + SIDE / 2;
            int top = surface(world, cx, cz);
            StringBuilder report = new StringBuilder();
            long batchedBefore = LightBatch.batchedCells(), vanillaBefore = LightBatch.vanillaChecks();
            int strokeId = 1;
            for (boolean lower : new boolean[] {false, true}) {
                BrushSpec spec = heightmap(128, cone(128), 128, lower, SurfaceMask.ANY, null, Symmetry.NONE);
                check(spec.heightmap().side() == 257, "a 257-wide stamp");
                int id = strokeId++;
                try {
                    h.service.beginStroke(h.player, id, spec);
                } catch (EditRejected e) {
                    throw new GameTestAssertException("the stroke was refused: " + e.getMessage());
                }
                int acks = h.acks.seqs.size();
                int y = surface(world, cx, cz) + 1;
                DabOutcome outcome = h.service.dabs(h.player, id, id, List.of(new Dab(0, cx * 16 + 8, y * 16, cz * 16 + 8,
                        255)));
                check(outcome.accepted(), "the dab: " + outcome);
                long[] planning = HeightmapGameTest.timedTicks(executor, h,
                        () -> !h.service.workingOut(h.player.getUUID()), 2_000, "working the dab out");
                long[] dab = HeightmapGameTest.timedTicks(executor, h, () -> h.acks.seqs.size() == acks + 1, 20_000,
                        "the dab");
                check(h.events.dabRejections.isEmpty(), "refusals " + h.events.dabRejections);
                h.service.endStroke(h.player, id);
                long[] commit = HeightmapGameTest.timedTicks(executor, h,
                        () -> h.service.queuedCommits(h.player.getUUID()) == 0, 20_000, "the commit");
                report.append(String.format(Locale.ROOT, " %s: worked out over %d ticks (longest %.1f ms), written over"
                        + " %d more (longest %.1f ms, %d over %d ms), committed over %d (longest %.1f ms); %.1f ms of GC"
                        + " left out, the longest pause %.1f ms, the longest tick with its pauses %.1f ms;",
                        lower ? "128 deep" : "128 tall", planning[0],
                        planning[1] / 1000.0, dab[0],
                        dab[1] / 1000.0, dab[2] + planning[2], ShapeBrushLaneGameTest.TICK_MICROS / 1000, commit[0],
                        commit[1] / 1000.0, (planning[3] + dab[3] + commit[3]) / 1000.0,
                        Math.max(planning[4], Math.max(dab[4], commit[4])) / 1000.0,
                        Math.max(planning[5], Math.max(dab[5], commit[5])) / 1000.0));
                check(planning[0] > 1, "the working out spans several ticks: " + report);
                check(planning[2] + dab[2] <= ShapeBrushLaneGameTest.LONG_TICKS_ALLOWED
                        && Math.max(planning[1], dab[1]) < ShapeBrushLaneGameTest.STALL_MICROS
                        && commit[1] < ShapeBrushLaneGameTest.STALL_MICROS, "ticks over the lane's budget:" + report);
                int now = surface(world, cx, cz);
                check(now == (lower ? top : top + 128), (lower ? "cut" : "raised") + " centre at " + now
                        + ", was " + top);
            }
            // The light checks of the cells written so far went to the light engine a batch at a time (LightBatch): a
            // vanilla light task per cell, about 100 bytes each, held 1.4 GB here. Checks that went the per-cell way are
            // counted as they are handed over (the world's own block changes during these ticks among them), so the
            // light thread's progress does not matter.
            long batched = LightBatch.batchedCells() - batchedBefore;
            check(batched > 2_000_000, "light checks batched by the two dabs: " + batched);
            long perCell = LightBatch.vanillaChecks() - vanillaBefore;
            check(perCell < 50_000, "light checks that went to the light engine one per cell: " + perCell);
            LoggerFactory.getLogger("sculptory").info("Heightmap radius-128 dabs on a dedicated server's lane:{}", report);
            check(h.service.historyService().undoEntries(h.player.getUUID()).size() == entries + 2, "one entry a stroke");
            for (int i = 0; i < 2; i++) {
                RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
                MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20_000, "undo " + i);
                check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                        "undo " + undo.result);
            }
            checkSame(before, capture(world, area), "after undoing the radius-128 strokes");
        } finally {
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.succeed();
    }

    /**
     * A radius-128 stamp's limits when a stroke begins: without {@code limit.bypass} the server's
     * {@code maxHeightmapRadius} (not {@code maxBrushRadius}), {@code maxHeightmapHeight} and {@code maxOpVolume} (the
     * stamp's expected blocks) each refuse it TOO_LARGE, and the client learns the first two in its limits; an op may.
     * A mirrored stamp white all over and 128 tall (twice {@link HeightmapSpec#MAX_STEP_CELLS}) is refused for everyone.
     * Changes the shared config for this batch only.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_heightmap_wide_limits", timeoutTicks = LIMIT)
    public void aWideStampsLimitsAreChecked(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.BRUSH);
        SculptoryConfig.LimitsConfig limits = h.runtime.config().limits;
        int savedRadius = limits.maxHeightmapRadius, savedHeight = limits.maxHeightmapHeight;
        long savedVolume = limits.maxOpVolume;
        int strokeId = 1;
        try {
            BrushSpec wide = heightmap(128, cone(128), 64, false, SurfaceMask.ANY, null, Symmetry.NONE);
            check(h.runtime.config().toLimits().maxHeightmapRadius() == 128, "the client learns radius 128 by default");
            strokeId = allowed(h, builder, wide, strokeId, "a radius-128 stamp by default (maxBrushRadius stays 32)");

            limits.maxHeightmapRadius = 64;
            check(h.runtime.config().toLimits().maxHeightmapRadius() == 64, "the client learns the server's radius");
            refused(h, builder, wide, "radius 128 > 64");
            strokeId = allowed(h, builder, heightmap(64, cone(64), 64, false, SurfaceMask.ANY, null, Symmetry.NONE),
                    strokeId, "radius 64 under maxHeightmapRadius 64");
            strokeId = allowed(h, h.player, wide, strokeId, "an op past maxHeightmapRadius");
            limits.maxHeightmapRadius = savedRadius;

            limits.maxHeightmapHeight = 40;
            check(h.runtime.config().toLimits().maxHeightmapHeight() == 40, "the client learns the server's height");
            refused(h, builder, wide, "stamp height 64 > 40");
            strokeId = allowed(h, h.player, wide, strokeId, "an op past maxHeightmapHeight");
            limits.maxHeightmapHeight = savedHeight;

            limits.maxOpVolume = 100_000;
            refused(h, builder, wide, "blocks > 100000");
            strokeId = allowed(h, h.player, wide, strokeId, "an op past maxOpVolume");
            limits.maxOpVolume = savedVolume;

            byte[] white = new byte[257 * 257];
            Arrays.fill(white, (byte) 255);
            BrushSpec twice = heightmap(128, white, 128, false, SurfaceMask.ANY, null,
                    new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0));
            EditRejected e = ClipboardGameTest.refusal(() -> h.service.beginStroke(h.player, 99, twice));
            check(e.reason() == RejectReason.TOO_LARGE && e.getMessage().contains(Long.toString(HeightmapSpec.MAX_STEP_CELLS)),
                    "past the step cap even for an op: " + e.getMessage());
            strokeId = allowed(h, h.player, heightmap(128, white, 128, false, SurfaceMask.ANY, null, Symmetry.NONE),
                    strokeId, "one white stamp 128 tall is the largest step");
        } finally {
            limits.maxHeightmapRadius = savedRadius;
            limits.maxHeightmapHeight = savedHeight;
            limits.maxOpVolume = savedVolume;
            h.close();
        }
        context.succeed();
    }

    private static void refused(Harness h, ServerPlayer player, BrushSpec spec, String why) {
        EditRejected e = ClipboardGameTest.refusal(() -> h.service.beginStroke(player, 98, spec));
        check(e.reason() == RejectReason.TOO_LARGE && e.getMessage().contains(why),
                "refused with " + e.reason() + ": " + e.getMessage() + " (expected " + why + ")");
        check(h.service.openStroke(player.getUUID()).isEmpty(), "a refused stroke is open");
    }

    private static int allowed(Harness h, ServerPlayer player, BrushSpec spec, int strokeId, String what) {
        try {
            h.service.beginStroke(player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestAssertException(what + " was refused: " + e.getMessage());
        }
        h.service.endStroke(player, strokeId);
        return strokeId + 1;
    }

    /** The topmost ground of a column (not air, plants or water), from y 300 down. */
    private static int surface(ServerLevel world, int x, int z) {
        for (int y = 300; y >= FLOOR - 4; y--) {
            BlockState state = world.getBlockState(new net.minecraft.core.BlockPos(x, y, z));
            if (!state.isAir() && !state.canBeReplaced() && state.getFluidState().isEmpty()) return y;
        }
        return FLOOR - 5;
    }
}
