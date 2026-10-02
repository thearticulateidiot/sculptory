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
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.impl.EditExecutor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import org.slf4j.LoggerFactory;

/**
 * The Weather brush on the server: real strokes of every mode in both sculpt modes
 * through the brush lane write exactly what the shared kernel writes on a snapshot and undo exactly, one entry a stroke;
 * a stroke is refused without the {@code brush} node and writes nothing where the player may not build; and a radius-32
 * dab computes in a few milliseconds and runs through a dedicated server's brush lane within its budget. Region slots
 * 1140-1159 (1140, 1142, 1144 used).
 */
public final class WeatherGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * Every dab here stays radius + 3 inside the area, so the kernel reads only the snapshot's cells.
     *
     * <p>Twelve strokes on rough ground with a wall, an overhang, a pond and plants: each mode in the Surface mode (the
     * overhang's underside, the wall's dented face, open ground, a stepped slope) and in the Terrain mode, a mirrored
     * Melt with an Elevation mask and a clip box, and a Roughen and a Fill in with the Square shape and Linear falloff.
     * The world then equals the kernel's replay on a snapshot cell for cell; each stroke made one entry; undoing them
     * all restores the area exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_weather_strokes", tickLimit = LIMIT)
    public void everyModesStrokesMatchTheKernelAndUndoExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1140);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 200, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        BrushSurfaceGameTest.wallAndOverhang(h, x0, z0);
        BrushSurfaceGameTest.decorate(h, x0, z0);
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);

        List<BrushSpec> specs = new ArrayList<>();
        List<List<Dab>> strokes = new ArrayList<>();
        // The Surface mode: under the overhang (its bumps and the stalactite), the wall's face (its dents), open ground
        // with plants, and a stepped slope.
        specs.add(weather(WeatherSpec.Mode.ERODE, 4, true));
        strokes.add(line(i -> new Dab(i, (x0 + 24 + i) * 16 + 8, 125 * 16, (z0 + 38 + i * 7 / 11) * 16 + 8, 255)));
        specs.add(weather(WeatherSpec.Mode.FILL_IN, 4, true));
        strokes.add(line(i -> new Dab(i, (x0 + 44) * 16 - 4, (125 + i % 4) * 16 + 8, (z0 + 34 + i) * 16 + 8, 255)));
        specs.add(weather(WeatherSpec.Mode.ROUGHEN, 5, true));
        strokes.add(onGround(world, i -> new int[] {x0 + 8 + i, z0 + 62 + i % 3}));
        specs.add(weather(WeatherSpec.Mode.MELT, 5, true));
        strokes.add(onGround(world, i -> new int[] {x0 + 60 + i, z0 + 8 + i % 4}));
        // The Terrain mode.
        specs.add(weather(WeatherSpec.Mode.ERODE, 5, false));
        strokes.add(onGround(world, i -> new int[] {x0 + 8 + i, z0 + 8 + i % 3}));
        specs.add(weather(WeatherSpec.Mode.FILL_IN, 5, false));
        strokes.add(onGround(world, i -> new int[] {x0 + 60 + i, z0 + 66 + i % 3}));
        specs.add(weather(WeatherSpec.Mode.ROUGHEN, 6, false));
        strokes.add(onGround(world, i -> new int[] {x0 + 28 + i, z0 + 66 + i % 2}));
        specs.add(weather(WeatherSpec.Mode.MELT, 5, false));
        strokes.add(onGround(world, i -> new int[] {x0 + 8 + i, z0 + 32 + i % 4}));
        // A mirrored Melt (about z0 + 40) with an Elevation mask and a clip box; the copies stand on the ground.
        Box clip = box(x0 + 58, 100, z0 + 20, x0 + 74, 160, z0 + 62);
        specs.add(new BrushSpec(BrushTool.WEATHER, 5, 1f, Falloff.SMOOTH, Shape.CIRCLE, null,
                new SurfaceMask.Elevation(100, 109), 0, 0, 21L, clip, new Symmetry(Symmetry.Mode.MIRROR_Z, 0, 2 * (z0 + 40)),
                null, SculptMode.TERRAIN, null, new WeatherSpec(WeatherSpec.Mode.MELT)));
        strokes.add(onGround(world, i -> new int[] {x0 + 60 + i, z0 + 24 + i % 5}));
        // The Square shape and Linear falloff, and a half pressure.
        specs.add(new BrushSpec(BrushTool.WEATHER, 4, 0.8f, Falloff.LINEAR, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0,
                22L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, new WeatherSpec(WeatherSpec.Mode.ROUGHEN))
                .withSurface(null));
        strokes.add(onGround(world, i -> new int[] {x0 + 30 + i, z0 + 8 + i % 3}));
        specs.add(new BrushSpec(BrushTool.WEATHER, 4, 1f, Falloff.LINEAR, Shape.SQUARE, null, SurfaceMask.ANY, 0, 0, 23L,
                null, Symmetry.NONE, null, SculptMode.TERRAIN, null, new WeatherSpec(WeatherSpec.Mode.FILL_IN)));
        List<Dab> half = new ArrayList<>();
        for (Dab d : onGround(world, i -> new int[] {x0 + 8 + i, z0 + 46 + i % 2})) {
            half.add(new Dab(d.index(), d.x16(), d.y16(), d.z16(), 128));
        }
        strokes.add(half);
        // Along the pond's west bank (the pond is x0 + 20..26, z0 + 18..24, water to 107): the bank's exposed edges wear
        // away and fill with water.
        specs.add(weather(WeatherSpec.Mode.ERODE, 3, true));
        strokes.add(line(i -> new Dab(i, (x0 + 20) * 16 + 2, 106 * 16 + 8, (z0 + 18 + i / 2) * 16 + 8, 255)));

        int[] seq = {1};
        TimedTaskRunner runner = context.createTimedTaskRunner();
        for (int s = 0; s < specs.size(); s++) {
            int current = s;
            runner.createAndAdd(() -> {
                begin(h, h.player, 200 + current, specs.get(current));
                DabOutcome outcome = h.service.dabs(h.player, 200 + current, seq[0]++, strokes.get(current));
                check(outcome.accepted(), "stroke " + current + ": " + outcome);
            });
            runner.createAndAdd(() -> check(h.acks.seqs.size() == seq[0] - 1, "stroke " + current + " still applying"));
            runner.createAndAdd(() -> h.service.endStroke(h.player, 200 + current));
        }
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
            check(h.service.historyService().undoEntries(h.player.getUuid()).size() == specs.size(), "one entry per stroke");
            // The mirrored Melt's copies (z0 + 51..55, which no other stroke reaches) did their share.
            int mirrored = 0;
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = z0 + 48; z <= z0 + 60; z++) {
                    for (int x = x0 + 54; x <= x0 + 79; x++) {
                        if (before.get(x, y, z) != after.get(x, y, z)) mirrored++;
                    }
                }
            }
            check(mirrored > 0, "the mirrored Melt's copies changed nothing");
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
            checkSame(before, capture(world, area), "after undoing every Weather stroke");
            forceChunks(world, area, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    /**
     * Refusals: a player without the {@code brush} node cannot begin a Weather stroke; a stroke wholly on columns the
     * player may not build on (spawn protection or a claim) is refused {@code PROTECTED} and writes nothing; one across
     * the protected area's edge writes only outside it and undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_weather_refused", tickLimit = LIMIT)
    public void refusedWithoutTheBrushNodeAndWhereProtected(TestContext context) {
        EditExecutor<ServerWorld> executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1142);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 63, 160, z0 + 63);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 64, 64);
        WorldSnapshot before = capture(world, area);
        BrushSpec erode = weather(WeatherSpec.Mode.ERODE, 4, true);
        try {
            // No brush node: refused before anything is admitted.
            ServerPlayerEntity user = h.addPlayer(false);
            EditTestSupport.grant(user, Perm.USE, Perm.REGION);
            EditRejected refused = ClipboardGameTest.refusal(() -> h.service.beginStroke(user, 40, erode));
            check(refused.reason() == RejectReason.NO_PERMISSION && refused.getMessage().contains(Perm.BRUSH.node()),
                    "without brush: " + refused.reason() + " " + refused.getMessage());
            check(h.service.openStroke(user.getUuid()).isEmpty(), "a refused stroke is open");

            // Wholly protected: nothing written, the lane refuses the dabs.
            ProtectionHook.protect(h.player, world, x0, z0, x0 + 63, z0 + 63);
            begin(h, h.player, 41, erode);
            DabOutcome outcome = h.service.dabs(h.player, 41, 1, onGround(world, i -> new int[] {x0 + 20 + i, z0 + 20}));
            if (outcome.accepted()) {
                MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 40, "the protected dabs");
                check(h.events.dabRejections.contains(RejectReason.PROTECTED), "refusals " + h.events.dabRejections);
            } else {
                check(outcome.reason() == RejectReason.PROTECTED, "protected: " + outcome);
            }
            h.service.endStroke(h.player, 41);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 40, "commit");
            check(h.service.historyService().undoEntries(h.player.getUuid()).isEmpty(), "a protected stroke made an entry");
            checkSame(before, capture(world, area), "after the protected stroke");

            // Across the edge (columns from x0 + 32 east protected): only the west side changes, and undoes exactly.
            ProtectionHook.protect(h.player, world, x0 + 32, z0, x0 + 63, z0 + 63);
            h.events.dabRejections.clear();
            begin(h, h.player, 42, weather(WeatherSpec.Mode.ROUGHEN, 6, false));
            int acks = h.acks.seqs.size();
            DabOutcome across = h.service.dabs(h.player, 42, 2, onGround(world, i -> new int[] {x0 + 26 + i, z0 + 30}));
            check(across.accepted(), "across the edge: " + across);
            MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == acks + 1, 40, "the dabs across the edge");
            h.service.endStroke(h.player, 42);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 40, "commit");
            WorldSnapshot partial = capture(world, area);
            int changed = 0;
            for (int y = area.min().y(); y <= area.max().y(); y++) {
                for (int z = area.min().z(); z <= area.max().z(); z++) {
                    for (int x = area.min().x(); x <= area.max().x(); x++) {
                        if (before.get(x, y, z) == partial.get(x, y, z)) continue;
                        check(x < x0 + 32, "a protected cell changed at " + x + "," + y + "," + z);
                        changed++;
                    }
                }
            }
            check(changed > 5, "only " + changed + " cells changed outside the protected columns");
            ProtectionHook.clear(h.player);
            RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
            MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 40, "the undo");
            check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
            checkSame(before, capture(world, area), "after undoing the stroke across the edge");
        } finally {
            ProtectionHook.clear(h.player);
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.complete();
    }

    /**
     * What a radius-32 Weather dab costs: the kernel alone on a real world (through the server's world reader, writes
     * discarded), each mode in both sculpt modes on ground with a wall and an overhang in the ball, a Rotate 4 step of
     * Surface Erode, and Surface Smooth for scale; each one stroke's dabs after a warm-up round, the garbage collector's
     * pauses during a dab left out, best and median of twenty. Then a stroke of twelve radius-32 dabs of each Surface mode
     * through a dedicated server's brush lane (10 ms a tick, 4 ms for brushes): no tick's own work, pauses left out,
     * runs past {@link ShapeBrushLaneGameTest#TICK_MICROS} more than {@link ShapeBrushLaneGameTest#LONG_TICKS_ALLOWED}
     * times or reaches {@link ShapeBrushLaneGameTest#STALL_MICROS}. Logged; the kernel's bound only catches a
     * pathological slowdown.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_weather_timing", tickLimit = LIMIT)
    public void aRadius32DabComputesInMillisecondsAndFitsTheLane(TestContext context) {
        EditExecutor<ServerWorld> executor = new EditExecutor<>(EngineTestSupport.runtime(context),
                EditExecutor.Settings.from(SculptoryConfig.defaults(), true));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1144);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 60, z0, x0 + 79, 200, z0 + 79);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 80, 80);
        BrushSurfaceGameTest.wallAndOverhang(h, x0, z0);
        try {
            FabricWorldReader reader = new FabricWorldReader(world, h.runtime.states());
            // On the wall's west face below the overhang: the ball takes in the wall, the overhang and the ground.
            Dab dab = new Dab(0, (x0 + 44) * 16 - 4, 120 * 16 + 8, (z0 + 40) * 16 + 8, 255);
            StringBuilder report = new StringBuilder();
            for (int round = 0; round < 2; round++) {
                BrushSpec smooth = new BrushSpec(BrushTool.SMOOTH, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                        SurfaceMask.ANY, 0, 0, 7L).withSurface(null);
                time(round, "SURFACE SMOOTH (for scale)", smooth, List.of(dab), reader, report);
                for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
                    BrushSpec terrain = new BrushSpec(BrushTool.WEATHER, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                            SurfaceMask.ANY, 0, 0, 7L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null,
                            new WeatherSpec(mode));
                    time(round, "SURFACE " + mode, terrain.withSurface(null), List.of(dab), reader, report);
                    time(round, "TERRAIN " + mode, terrain, List.of(dab), reader, report);
                }
                BrushSpec copies = new BrushSpec(BrushTool.WEATHER, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                        SurfaceMask.ANY, 0, 0, 7L, null, new Symmetry(Symmetry.Mode.ROTATE_4, 2 * x0 + 80, 2 * z0 + 80),
                        null, SculptMode.TERRAIN, null, new WeatherSpec(WeatherSpec.Mode.ERODE)).withSurface(null);
                List<Dab> step = SymmetricStep.of(copies, dab, reader).dabs();
                time(round, "SURFACE ERODE " + step.size() + " copies", copies, step, reader, report);
            }
            LoggerFactory.getLogger("sculptory").info("Weather radius-32 dab compute:{}", report);

            // Through a dedicated server's brush lane: twelve radius-32 dabs a stroke, the ticks timed.
            StringBuilder lane = new StringBuilder();
            int strokeId = 300;
            for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
                BrushSpec spec = new BrushSpec(BrushTool.WEATHER, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
                        SurfaceMask.ANY, 0, 0, 9L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null,
                        new WeatherSpec(mode)).withSurface(null);
                int id = strokeId++;
                begin(h, h.player, id, spec);
                int acks = h.acks.seqs.size();
                // Within the loaded area with the dab box's radius + 2 to spare (x0 + 10..78, z0 + 4..74).
                List<Dab> dabs = line(i -> new Dab(i, (x0 + 44) * 16 - 4, (116 + i) * 16 + 8, (z0 + 38 + i / 4) * 16 + 8, 255));
                DabOutcome outcome = h.service.dabs(h.player, id, id, dabs);
                check(outcome.accepted(), mode + ": " + outcome);
                long[] timing = timedTicks(executor, h, () -> h.acks.seqs.size() == acks + 1, 400, mode + " stroke");
                h.service.endStroke(h.player, id);
                timedTicks(executor, h, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 400, mode + " commit");
                lane.append(String.format(Locale.ROOT, " %s: %d ticks, longest %.1f ms, %d over %d ms, %.1f ms of GC left out;",
                        mode, timing[0], timing[1] / 1000.0, timing[2], ShapeBrushLaneGameTest.TICK_MICROS / 1000,
                        timing[3] / 1000.0));
                check(timing[2] <= ShapeBrushLaneGameTest.LONG_TICKS_ALLOWED && timing[1] < ShapeBrushLaneGameTest.STALL_MICROS,
                        mode + " stroke ticks over the lane's budget: " + lane);
            }
            LoggerFactory.getLogger("sculptory").info("Weather radius-32 strokes on a dedicated server's lane:{}", lane);
        } finally {
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
        context.complete();
    }

    /**
     * Ticks the executor and the service until {@code done}; {ticks, longest tick, ticks over
     * {@link ShapeBrushLaneGameTest#TICK_MICROS}, GC pauses left out}, times in microseconds, each tick's time without
     * the garbage collectors' pauses during it ({@link ShapeBrushLaneGameTest#gcMillis}).
     */
    private static long[] timedTicks(EditExecutor<ServerWorld> executor, Harness h,
                                     java.util.function.BooleanSupplier done, int max, String what) {
        long ticks = 0, longest = 0, over = 0, gc = 0;
        while (!done.getAsBoolean() && ticks < max) {
            long gcBefore = ShapeBrushLaneGameTest.gcMillis();
            long start = System.nanoTime();
            executor.tick();
            h.service.tick();
            long took = (System.nanoTime() - start) / 1000;
            long paused = (ShapeBrushLaneGameTest.gcMillis() - gcBefore) * 1000;
            took = Math.max(0, took - paused);
            gc += paused;
            longest = Math.max(longest, took);
            if (took > ShapeBrushLaneGameTest.TICK_MICROS) over++;
            ticks++;
        }
        check(done.getAsBoolean(), what + " did not finish in " + max + " ticks");
        return new long[] {ticks, longest, over, gc};
    }

    /** Times one step of {@code spec} twenty times as one stroke, GC pauses left out; reports best and median in round 1. */
    private static void time(int round, String what, BrushSpec spec, List<Dab> step, FabricWorldReader reader,
                             StringBuilder report) {
        long[] took = new long[20];
        int[] cells = {0};
        StrokeState stroke = new StrokeState();
        for (int run = 0; run < took.length; run++) {
            cells[0] = 0;
            reader.invalidate();
            long gcBefore = ShapeBrushLaneGameTest.gcMillis();
            long start = System.nanoTime();
            BrushKernels.forTool(spec.tool()).applyStep(spec, step, stroke, reader, (x, y, z, s) -> cells[0]++);
            long nanos = System.nanoTime() - start;
            took[run] = Math.max(0, nanos - (ShapeBrushLaneGameTest.gcMillis() - gcBefore) * 1_000_000L);
        }
        if (round == 0) return;
        Arrays.sort(took);
        report.append(String.format(Locale.ROOT, " %s best %.1f ms, median %.1f ms (%d cells);", what, took[0] / 1e6,
                took[took.length / 2] / 1e6, cells[0]));
        check(took[0] < 1_000_000_000L, what + " radius 32 took " + took[0] / 1e6 + " ms");
    }

    // ------------------------------------------------------------------ helpers

    private static BrushSpec weather(WeatherSpec.Mode mode, int radius, boolean surface) {
        BrushSpec spec = new BrushSpec(BrushTool.WEATHER, radius, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                0, 11L + mode.ordinal(), null, Symmetry.NONE, null, SculptMode.TERRAIN, null, new WeatherSpec(mode));
        return surface ? spec.withSurface(null) : spec;
    }

    /** Twelve dabs of a stroke. */
    private static List<Dab> line(IntFunction<Dab> dab) {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 12; i++) dabs.add(dab.apply(i));
        return dabs;
    }

    /** Twelve dabs on the ground's top face at the columns {@code column} gives, where the cursor would hit it. */
    private static List<Dab> onGround(ServerWorld world, IntFunction<int[]> column) {
        return line(i -> {
            int[] c = column.apply(i);
            return new Dab(i, c[0] * 16 + 8, (ground(world, c[0], c[1]) + 1) * 16, c[1] * 16 + 8, 255);
        });
    }

    /** The topmost ground of a column (not air, plants or water), from y 150 down. */
    private static int ground(ServerWorld world, int x, int z) {
        for (int y = 150; y >= 100; y--) {
            BlockState state = world.getBlockState(new net.minecraft.util.math.BlockPos(x, y, z));
            if (!state.isAir() && !state.isReplaceable() && state.getFluidState().isEmpty()) return y;
        }
        return 100;
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
                return spec.weather().mode() + " (stroke " + s + ") failed on the snapshot: " + e;
            }
            if (writes[0] == 0) return spec.weather().mode() + " (stroke " + s + ") wrote nothing on the snapshot";
        }
        return "";
    }

    private static void begin(Harness h, ServerPlayerEntity player, int strokeId, BrushSpec spec) {
        try {
            h.service.beginStroke(player, strokeId, spec);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
    }
}
