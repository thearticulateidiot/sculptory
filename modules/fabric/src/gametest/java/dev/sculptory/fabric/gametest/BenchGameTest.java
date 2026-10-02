package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.BenchSupport.log;
import static dev.sculptory.fabric.gametest.BenchSupport.ms;
import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.dab;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.BenchSupport.LightWait;
import dev.sculptory.fabric.gametest.BenchSupport.TickTimes;
import dev.sculptory.fabric.gametest.BenchSupport.Watcher;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.net.PreviewPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;

/**
 * Opt-in benchmarks: a 1,000,000-block fill with undo and redo, a 1M-cell paste, radius-32
 * brush strokes and a scatter commit, each timed (wall time, ticks, whole-server tick times while it runs, time until
 * the light engine has caught up) and, where a watching player is present, with the vanilla packets it is sent sized
 * as a real connection would send them. Every one checks that undo restores the area exactly.
 *
 * <p>They are skipped (pass at once) unless enabled, so {@code check} stays fast. Run them with:
 * <pre>
 * $env:SCULPTORY_BENCH = "1"; scripts/gradle.ps1 :fabric:runGameTest --console=plain
 * </pre>
 * (or {@code -Dsculptory.bench=true} on the game JVM) and read the {@code bench} lines in the log. The GameTest
 * server does not count as dedicated ({@code TestServer.isDedicated()} is false), so the executor uses its integrated
 * budget (20 ms per tick by default); it ticks without sleeping, so wall time is close to pure engine time. Each benchmark has a batch of its own, so none runs alongside
 * another test.
 */
public final class BenchGameTest implements FabricGameTest {
    private static final int LIMIT = 200_000;

    static boolean enabled() {
        return BenchSupport.enabled();
    }

    // ---------------------------------------------------------------- 1M fill, undo, redo

    /** A 100³ fill of stone into air, its undo and its redo, with no player watching (engine and light only). */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_bench_fill", tickLimit = LIMIT)
    public void bench1MFill(TestContext context) {
        if (BenchSupport.skipped(context, "bench1MFill")) return;
        fillBench(context, "bench1MFill", 30, false, "minecraft:stone");
    }

    /** The same fill and undo with a player watching the area: packets and bytes it is sent, tick times. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_bench_fill_watched", tickLimit = LIMIT)
    public void bench1MFillWatched(TestContext context) {
        if (BenchSupport.skipped(context, "bench1MFillWatched")) return;
        fillBench(context, "bench1MFillWatched", 31, true, "minecraft:stone");
    }

    /**
     * A 100³ fill of water into air, its undo and its redo: every cell is marked as the entry's fluid (FluidTrails), and
     * undo and redo fold its (empty) trail. Compare with {@link #bench1MFill}.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_bench_fill_water", tickLimit = LIMIT)
    public void bench1MWaterFill(TestContext context) {
        if (BenchSupport.skipped(context, "bench1MWaterFill")) return;
        fillBench(context, "bench1MWaterFill", 938, false, "minecraft:water");
    }

    private static void fillBench(TestContext context, String name, int slot, boolean watched, String block) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, slot);
        Box region = box(at[0], 100, at[1], at[0] + 99, 199, at[1] + 99); // 1,000,000 cells
        long loadStart = System.nanoTime();
        loadAndForce(world, region);
        log("%s: loaded %d chunks in %.0f ms", name, 7 * 7, ms(System.nanoTime() - loadStart));
        WorldSnapshot before = capture(world, region);
        Bench bench = new Bench(context, h, name, region, watched ? watcherOver(world, region) : null);
        bench.run("fill", listener -> h.fill(region, block, listener), 1_000_000);
        bench.run("undo", h::undo, 1_000_000);
        bench.then(() -> {
            long compareStart = System.nanoTime();
            checkSame(before, capture(world, region), "after undo");
            log("%s: exact-restore check of 1,000,000 cells in %.0f ms", name, ms(System.nanoTime() - compareStart));
        });
        if (!watched) {
            bench.run("redo", h::redo, 1_000_000);
            bench.then(() -> {
                int filled = h.state(block);
                WorldSnapshot after = capture(world, region);
                for (int state : after.states) check(state == filled, "redo left a cell that is not " + block);
            });
        }
        bench.finish(() -> forceChunks(world, region, false));
    }

    // ---------------------------------------------------------------- 1M paste

    /**
     * A 128 × 64 × 128 (1,048,576-cell) mixed source (stone, andesite, dirt, planks, glass and air) copied, its preview
     * payload encoded, pasted elsewhere with a player watching, and undone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_bench_paste", tickLimit = LIMIT)
    public void benchPaste1M(TestContext context) {
        if (BenchSupport.skipped(context, "benchPaste1M")) return;
        String name = "benchPaste1M";
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 32);
        Box source = box(at[0], 100, at[1], at[0] + 127, 163, at[1] + 127);
        Box target = source.offset(160, 0, 0);
        loadAndForce(world, source);
        loadAndForce(world, target);
        int[] states = {h.state("minecraft:stone"), h.state("minecraft:andesite"), h.state("minecraft:dirt"),
                h.state("minecraft:oak_planks"), h.state("minecraft:glass"), h.state("minecraft:air")};
        Pattern mixed = new Pattern.Weighted(states, new int[] {40, 15, 15, 10, 5, 15}, 7L);
        Bench bench = new Bench(context, h, name, target, watcherOver(world, target));
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        WorldSnapshot[] before = new WorldSnapshot[1];
        List<Captured<ClipboardService.ClipboardInfo>> copied = new ArrayList<>();
        long[] copyStart = new long[1];
        bench.runQuiet("mixed fill of the source", listener -> run(h, new OpSpec.Fill(source, mixed, CellMask.ANY), listener));
        bench.then(() -> {
            // The section capture alone (FabricWorldReader.copySection), which copies and every job section use.
            dev.sculptory.fabric.world.FabricWorldReader reader = h.runtime.reader(world);
            it.unimi.dsi.fastutil.longs.LongArrayList keys = new it.unimi.dsi.fastutil.longs.LongArrayList();
            source.forEachSectionKey(keys::add);
            for (int round = 0; round < 3; round++) {
                long start = System.nanoTime();
                for (int k = 0; k < keys.size(); k++) {
                    long key = keys.getLong(k);
                    dev.sculptory.core.buffer.SectionBuffer section = new dev.sculptory.core.buffer.SectionBuffer();
                    reader.copySection(dev.sculptory.core.buffer.BlockBuffer.keyX(key),
                            dev.sculptory.core.buffer.BlockBuffer.keyY(key),
                            dev.sculptory.core.buffer.BlockBuffer.keyZ(key), section);
                }
                log("%s: copySection of %d sections (mixed content), round %d: %.1f ms", name, keys.size(), round,
                        ms(System.nanoTime() - start));
            }
            before[0] = capture(world, target);
            copyStart[0] = System.nanoTime();
            copied.add(ClipboardGameTest.copy(clips, h.player, source, source.min()));
            log("%s: copy of %,d cells: %.1f ms on the server thread (section snapshot)", name, source.volume(),
                    ms(System.nanoTime() - copyStart[0]));
        });
        UUID[] clipboardId = new UUID[1];
        bench.then(() -> {
            ClipboardService.ClipboardInfo info = copied.get(0).get("copy");
            log("%s: copy finished after %.0f ms (clipboard built off-thread); %,d cells", name,
                    ms(System.nanoTime() - copyStart[0]), info.cells());
            clipboardId[0] = info.clipboardId();
            Clipboard clipboard = h.service.clipboards().find(h.player.getUuid(), info.clipboardId())
                    .orElseThrow(() -> new GameTestException("clipboard not held")).clipboard();
            long encodeStart = System.nanoTime();
            byte[] payload = PreviewPayload.encode(clipboard);
            log("%s: preview payload (bspv1) %,d bytes for %,d cells (%.2f bytes/cell), encoded in %.0f ms (off-thread "
                    + "in the game)", name, payload.length, clipboard.cellCount(), payload.length / (double) clipboard.cellCount(),
                    ms(System.nanoTime() - encodeStart));
        });
        bench.run("paste", listener -> run(h, new OpSpec.Paste(new SourceRef.Clipboard(clipboardIdOrThrow(clipboardId)),
                target.min(), Transform.IDENTITY, dev.sculptory.core.edit.PasteOptions.DEFAULT), listener), -1);
        bench.run("undo", h::undo, -1);
        bench.then(() -> checkSame(before[0], capture(world, target), "after undo"));
        bench.finish(() -> {
            forceChunks(world, source, false);
            forceChunks(world, target, false);
            ClipTestSupport.deleteTree(ClipTestSupport.libraryRoot(context).getParent());
        });
    }

    private static UUID clipboardIdOrThrow(UUID[] holder) {
        if (holder[0] == null) throw new GameTestException("no clipboard");
        return holder[0];
    }

    // ---------------------------------------------------------------- radius-32 strokes

    /**
     * Radius-32 strokes over hilly terrain with a player watching: a Raise stroke of 16 dabs (spacing 8, the client's
     * 0.25 × radius) and a Smooth stroke of 8 dabs, each ended and undone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_bench_brush", tickLimit = LIMIT)
    public void benchBrushR32(TestContext context) {
        if (BenchSupport.skipped(context, "benchBrushR32")) return;
        String name = "benchBrushR32";
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 33);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 90, z0, x0 + 223, 180, z0 + 111);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 224, 112);
        Bench bench = new Bench(context, h, name, area, watcherOver(world, area));
        WorldSnapshot[] before = new WorldSnapshot[1];
        bench.then(() -> before[0] = capture(world, area));
        stroke(bench, h, 1, new BrushSpec(BrushTool.RAISE, 32, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY,
                0, 0, 1L), x0 + 48, z0 + 56, 16, 8);
        bench.run("raise undo", h::undo, -1);
        bench.then(() -> checkSame(before[0], capture(world, area), "after the raise undo"));
        stroke(bench, h, 2, new BrushSpec(BrushTool.SMOOTH, 32, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY,
                0, 0, 1L), x0 + 48, z0 + 56, 8, 16);
        bench.run("smooth undo", h::undo, -1);
        bench.then(() -> checkSame(before[0], capture(world, area), "after the smooth undo"));
        bench.finish(() -> forceChunks(world, area, false));
    }

    /** One stroke of {@code dabs} dabs along +x from (x, z), sent as one batch; ended once acknowledged. */
    private static void stroke(Bench bench, Harness h, int strokeId, BrushSpec spec, int x, int z, int dabs, int spacing) {
        String what = spec.tool().name().toLowerCase(java.util.Locale.ROOT) + " r" + spec.radius() + " stroke";
        int[] acksBefore = new int[1];
        long[] cells = new long[1];
        bench.then(() -> {
            try {
                h.service.beginStroke(h.player, strokeId, spec);
            } catch (EditRejected e) {
                throw new GameTestException("stroke refused: " + e.getMessage());
            }
            List<Dab> list = new ArrayList<>();
            for (int i = 0; i < dabs; i++) list.add(dab(i, x + i * spacing, 110, z));
            acksBefore[0] = h.acks.seqs.size();
            bench.startTimer();
            DabOutcome outcome = h.service.dabs(h.player, strokeId, strokeId * 100, list);
            check(outcome.accepted(), what + " dabs refused: " + outcome);
        });
        bench.then(() -> {
            check(h.acks.seqs.size() > acksBefore[0], what + " not acknowledged yet");
            bench.stopTimer(System.nanoTime(), h.world.getServer().getTicks());
            h.service.endStroke(h.player, strokeId);
            cells[0] = h.service.historyService().undoEntries(h.player.getUuid()).get(0).record().before().cellCount();
        });
        bench.logTimer(what, cells, " (" + dabs + " dabs)");
        bench.settle(what);
    }

    // ---------------------------------------------------------------- scatter

    /** A scatter of small trees (13 blocks each) over a 192 × 192 floor at spacing 4: planning, commit, undo. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_bench_scatter", tickLimit = LIMIT)
    public void benchScatterCommit(TestContext context) {
        if (BenchSupport.skipped(context, "benchScatterCommit")) return;
        String name = "benchScatterCommit";
        Harness h = new Harness(context);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 34);
        int x0 = at[0], z0 = at[1];
        Box all = ScatterGameTest.floor(h, x0, z0, 192, 192);
        Clipboard.Builder tree = Clipboard.builder(h.runtime.states(), new BlockPos(3, 4, 3)).anchor(new BlockPos(1, 0, 1));
        for (int y = 0; y < 3; y++) tree.set(1, y, 1, h.state("minecraft:oak_log[axis=y]"));
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) tree.set(x, 3, z, h.state("minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"));
        }
        SourceRef source = new SourceRef.Clipboard(h.service.clipboards().install(h.player.getUuid(), tree.build()).id());
        Box area = box(x0 - 4, ScatterGameTest.FLOOR_Y - 1, z0 - 4, x0 + 195, ScatterGameTest.FLOOR_Y + 8, z0 + 195);
        Bench bench = new Bench(context, h, name, area, watcherOver(h.world, area));
        WorldSnapshot[] before = new WorldSnapshot[1];
        ScatterGameTest.Reply[] reply = new ScatterGameTest.Reply[1];
        long[] planStart = new long[1];
        int[] planTicks = new int[1];
        bench.then(() -> {
            before[0] = capture(h.world, area);
            planStart[0] = System.nanoTime();
            reply[0] = ScatterGameTest.preview(scatter, h.player, ScatterGameTest.request(
                    ScatterGameTest.region(x0, z0, x0 + 191, z0 + 191), 4, 11L, source));
        });
        bench.then(() -> {
            scatter.tick();
            planTicks[0]++;
            check(reply[0].finished(), "planning");
        });
        UUID[] planId = new UUID[1];
        bench.then(() -> {
            ScatterService.PlanReady plan = reply[0].get("preview");
            planId[0] = plan.planId();
            log("%s: planned %,d placements in %.0f ms wall over %d scatter ticks; placements payload %,d bytes", name,
                    plan.placements(), ms(System.nanoTime() - planStart[0]), planTicks[0], plan.placementsPayload().length);
        });
        bench.run("commit", listener -> run(h, new OpSpec.ScatterCommit(planIdOrThrow(planId)), listener), -1);
        bench.run("undo", h::undo, -1);
        bench.then(() -> checkSame(before[0], capture(h.world, area), "after undo"));
        bench.finish(() -> {
            scatter.shutdown();
            forceChunks(h.world, all, false);
        });
    }

    private static UUID planIdOrThrow(UUID[] holder) {
        if (holder[0] == null) throw new GameTestException("no plan");
        return holder[0];
    }

    // ---------------------------------------------------------------- plumbing

    private static Watcher watcherOver(ServerWorld world, Box box) {
        int cx = (box.min().x() + box.max().x()) / 2, cz = (box.min().z() + box.max().z()) / 2;
        int radius = Math.max(box.sizeX(), box.sizeZ()) / 32 + 2;
        return Watcher.join(world, cx, box.max().y() + 8, cz, radius);
    }

    private static void run(Harness h, OpSpec spec, JobListener listener) {
        try {
            h.service.run(h.player, spec, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(spec.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }

    @FunctionalInterface
    private interface Submit {
        void submit(JobListener listener);
    }

    /**
     * A sequence of timed steps on one {@link TimedTaskRunner}: jobs (timed from submission to {@code finished}, then
     * until the light engine has caught up over {@link #area}), plain steps, and a closing step that removes the
     * watcher and players.
     */
    private static final class Bench {
        final TestContext context;
        final Harness h;
        final String name;
        final Box area;
        final Watcher watcher;
        final TimedTaskRunner runner;
        final TickTimes ticks = new TickTimes();
        long timerStart;
        int timerTick;
        long timerNanos;
        int timerTicks;

        Bench(TestContext context, Harness h, String name, Box area, Watcher watcher) {
            this.context = context;
            this.h = h;
            this.name = name;
            this.area = area;
            this.watcher = watcher;
            this.runner = context.createTimedTaskRunner();
            if (watcher != null) runner.createAndAdd(() -> check(watcher.ready(area), "the watcher is still loading chunks"));
        }

        void then(Runnable step) {
            runner.createAndAdd(step);
        }

        /** Starts timing work submitted now: wall time, ticks, tick times from the next tick, the watcher's packets. */
        void startTimer() {
            if (watcher != null) watcher.record();
            ticks.startNextTick();
            timerStart = System.nanoTime();
            timerTick = h.world.getServer().getTicks();
        }

        /** Ends the timed work at {@code endNanos} (tick {@code endTick}); tick times include the current tick. */
        void stopTimer(long endNanos, int endTick) {
            timerNanos = endNanos - timerStart;
            timerTicks = endTick - timerTick;
            ticks.stopAtTickEnd();
        }

        /** A step that waits for the tick times to close, then logs the timed work. */
        void logTimer(String what, long[] cells, String detail) {
            runner.createAndAdd(() -> {
                check(ticks.stopped(), "waiting for the tick to end");
                log("%s: %s of %,d cells%s: %.0f ms wall, %d ticks, %.2f us/cell; tick times: %s", name, what, cells[0],
                        detail, ms(timerNanos), timerTicks, ms(timerNanos) * 1000.0 / Math.max(1, cells[0]),
                        ticks.summary());
            });
        }

        /** Waits for the light engine over {@link #area}, then logs the wait and the watcher's packets. */
        void settle(String what) {
            LightWait[] light = new LightWait[1];
            runner.createAndAdd(() -> {
                light[0] = new LightWait(h.world, area);
                ticks.startNextTick();
            });
            runner.createAndAdd(() -> {
                check(light[0].settled(), "light still updating");
                ticks.stopAtTickEnd();
            });
            runner.createAndAdd(() -> {
                check(ticks.stopped(), "waiting for the tick to end");
                log("%s: %s: light caught up %.0f ms after the edit; tick times meanwhile: %s", name, what,
                        ms(light[0].nanos()), ticks.summary());
                if (watcher != null) {
                    log("%s: %s", name, watcher.report(what));
                    watcher.pause();
                }
            });
        }

        /** Submits a job, waits for it, requires COMPLETED (and {@code expectChanged} cells unless negative), settles. */
        void run(String what, Submit submit, long expectChanged) {
            Timing timing = new Timing();
            long[] cells = new long[1];
            runner.createAndAdd(() -> {
                startTimer();
                submit.submit(timing);
            });
            runner.createAndAdd(() -> check(timing.result != null, what + " running"));
            runner.createAndAdd(() -> {
                JobResult r = timing.result;
                check(r.outcome() == JobOutcome.COMPLETED, what + " " + r);
                check(expectChanged < 0 || r.changed() == expectChanged, what + " changed " + r.changed());
                cells[0] = r.changed();
                stopTimer(timing.endNanos, timing.endTick);
            });
            logTimer(what, cells, "");
            settle(what);
        }

        /** A job run for set-up: waited for and required to complete, not reported. */
        void runQuiet(String what, Submit submit) {
            Timing timing = new Timing();
            runner.createAndAdd(() -> submit.submit(timing));
            runner.createAndAdd(() -> check(timing.result != null, what + " running"));
            runner.createAndAdd(() -> check(timing.result.outcome() == JobOutcome.COMPLETED, what + " " + timing.result));
        }

        void finish(Runnable cleanup) {
            runner.createAndAdd(() -> {
                ticks.close();
                if (watcher != null) watcher.close();
                cleanup.run();
                h.close();
            });
            runner.completeIfSuccessful();
        }

        /** {@code finished} time and tick of one job. */
        private final class Timing implements JobListener {
            long endNanos;
            int endTick;
            int progressEvents;
            JobResult result;

            @Override
            public void progress(UUID job, long done, long total, Phase ph) {
                progressEvents++;
            }

            @Override
            public void finished(JobResult r) {
                endNanos = System.nanoTime();
                endTick = h.world.getServer().getTicks();
                result = r;
            }
        }
    }
}
