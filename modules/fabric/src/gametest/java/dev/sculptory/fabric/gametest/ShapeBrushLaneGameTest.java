package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.ShapeBrushGameTest.at;
import static dev.sculptory.fabric.gametest.ShapeBrushGameTest.onePartATick;
import static dev.sculptory.fabric.gametest.ShapeBrushGameTest.shape;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.store.HistoryStore;
import dev.sculptory.core.history.store.StorageIo;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.engine.impl.EngineEditService;
import dev.sculptory.fabric.engine.impl.FabricHistoryCodec;
import dev.sculptory.fabric.engine.impl.JobRequest;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.SnapshotWorld;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.schem.FabricDataFixHook;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.impl.BrushWork;
import dev.sculptory.server.engine.impl.HistoryService;
import dev.sculptory.server.platform.WriteOptions;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Shape brush's work on the brush lane beyond the kernel (regionCorner slots 740-756): large steps written in parts
 * and their records committed a few sections a tick, measured on a dedicated server's budget with saved history; a
 * crash between commit slices, a player leaving and a server stop mid-step, a new stroke mid-step, a full brush queue
 * when a stroke ends, a large terrain stroke undone right after it, the permission lost between parts; and the brush
 * lane itself (turns, predictions, failures). Each test runs its own executor, ticked here.
 */
public final class ShapeBrushLaneGameTest implements FabricGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final HistoryStore.Log STORE_LOG = new HistoryStore.Log() {
        @Override
        public void info(String message) {
            LOG.info(message);
        }

        @Override
        public void warn(String message, Throwable cause) {
            LOG.warn(message, cause);
        }
    };

    /** An edit service saving history in its own folder, over its own executor (as the server runs it). */
    private static final class Saved implements AutoCloseable {
        final Harness h;
        final Path dir;
        final EditExecutor executor;
        final EngineEditService service;

        Saved(Harness h, Path dir, EditExecutor executor) {
            this.h = h;
            this.dir = dir;
            this.executor = executor;
            HistoryStore store;
            try {
                store = HistoryStore.open(dir, new FabricHistoryCodec(h.runtime.states(),
                        FabricDataFixHook.currentDataVersion()), HistoryStore.Settings.DEFAULTS, StorageIo.SYSTEM, STORE_LOG);
            } catch (IOException e) {
                throw new GameTestException("the history store did not open: " + e);
            }
            this.service = new EngineEditService(h.runtime, executor, h.runtime.config().toHistoryLimits(),
                    p -> JobRequest.NO_LISTENER, h.acks, h.events, System::nanoTime,
                    new HistoryService.Persistence(store, 1L << 40, 0, System::currentTimeMillis));
        }

        /** The player joins: their saved history is loaded. */
        void load(ServerPlayerEntity player) {
            service.playerJoined(player.getUuid());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (service.historyService().find(player.getUuid()).map(HistoryService.Session::loading).orElse(false)) {
                check(System.nanoTime() < deadline, "the history did not load");
                service.tick();
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /** One server tick: the executor, then the service. */
        void tick() {
            executor.tick();
            service.tick();
        }

        void tickUntil(BooleanSupplier done, int maxTicks, String what) {
            for (int i = 0; i < maxTicks && !done.getAsBoolean(); i++) tick();
            check(done.getAsBoolean(), what + " did not finish in " + maxTicks + " ticks");
        }

        void begin(ServerPlayerEntity player, int strokeId, BrushSpec spec) {
            try {
                service.beginStroke(player, strokeId, spec);
            } catch (EditRejected e) {
                throw new GameTestException("the stroke was refused: " + e.getMessage());
            }
        }

        void undo(ServerPlayerEntity player, int maxTicks) {
            RecordingListener listener = new RecordingListener();
            try {
                service.undo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
            } catch (EditRejected e) {
                throw new GameTestException("the undo was refused: " + e.getMessage());
            }
            tickUntil(() -> listener.result != null, maxTicks, "the undo");
            check(listener.result.outcome() == JobOutcome.COMPLETED && listener.result.skippedConflicts() == 0,
                    "the undo " + listener.result);
        }

        List<HistoryEntry> undoEntries(ServerPlayerEntity player) {
            return service.historyService().undoEntries(player.getUuid());
        }

        /** A clean stop, in the server's order: jobs end, open strokes become entries, the journal is written. */
        void stop() {
            executor.shutdown();
            service.shutdown();
            check(service.historyService().closeStore(10_000), "the history store did not close");
        }

        @Override
        public void close() {
            service.historyService().closeStore(10_000);
            executor.shutdown();
        }
    }

    /** A fresh folder for saved history, in the GameTest server's temporary history folder. */
    private static Path folder() {
        String root = System.getProperty(EditServiceHost.HISTORY_DIR_PROPERTY);
        Path base = root != null ? Path.of(root) : Path.of(System.getProperty("java.io.tmpdir"), "sculptory-gametest-history");
        return base.resolve("shape-lane-tests").resolve(UUID.randomUUID().toString()).normalize();
    }

    /** A crash: the history files as they are on disk now, copied into a new folder. */
    private static Path diskNow(Path from) {
        Path copy = folder();
        try {
            Files.createDirectories(copy);
            for (Path file : StorageIo.SYSTEM.list(from)) {
                if (file.getFileName().toString().endsWith(HistoryStore.EXTENSION)) Files.copy(file, copy.resolve(file.getFileName()));
            }
        } catch (IOException e) {
            throw new GameTestException("copying the history folder failed: " + e);
        }
        return copy;
    }

    /** An executor with a dedicated server's default settings (a 10 ms budget, 4 ms of it for brushes). */
    private static EditExecutor dedicated(TestContext context) {
        return new EditExecutor(context.getWorld().getServer(), EngineTestSupport.runtime(context).states(),
                EditExecutor.Settings.from(SculptoryConfig.defaults(), true));
    }

    /** Fills {@code box} with stone and glass in a checkerboard: a mix placed over it changes cells both ways. */
    private static void checkerboard(Harness h, Box box) {
        BlockWriter writer = h.runtime.writer(h.world, new WriteOptions(false, true));
        int stone = h.state("minecraft:stone"), glass = h.state("minecraft:glass");
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) writer.write(x, y, z, ((x + y + z) & 1) == 0 ? stone : glass, null);
            }
        }
    }

    /** A 65-block cube's box around the block (x, y, z). */
    private static Box cube65(int x, int y, int z) {
        return box(x - 32, y - 32, z - 32, x + 32, y + 32, z + 32);
    }

    private static String blocks(long n) {
        return String.format(Locale.ROOT, "%,d blocks", n);
    }

    /** A tick's own work may take this long (the brush share of 4 ms plus about one piece, with room to spare). */
    static final long TICK_MICROS = 30_000;
    /** No tick may take this long: a step or record done whole, not in parts, takes several hundred ms. */
    static final long STALL_MICROS = 250_000;
    /** Ticks a stalled machine may stretch over {@link #TICK_MICROS} (per step or commit), none past the stall. */
    static final int LONG_TICKS_ALLOWED = 2;

    /**
     * Ticks run until the work was done, times in microseconds with the garbage collectors' pauses during each tick left
     * out ({@link #gcMillis}): {@code longest} is the longest tick, {@code over} how many ticks took longer than
     * {@link #TICK_MICROS}, {@code gc} the pauses left out.
     */
    record Timing(long ticks, long longest, long total, long executorLongest, long serviceLongest, int over, long gc) {
        static final Timing NONE = new Timing(0, 0, 0, 0, 0, 0, 0);

        /**
         * The lane's pieces fit its share: at most {@link #LONG_TICKS_ALLOWED} ticks (the step's first part, which reads
         * the step whole, and one the machine stalled) run over {@link #TICK_MICROS}, and none for {@link #STALL_MICROS}.
         */
        boolean withinBudget() {
            return over <= LONG_TICKS_ALLOWED && longest < STALL_MICROS;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%d ticks, longest %.1f ms (executor %.1f, service %.1f), %d over %d ms, "
                    + "%.1f ms in all, %.1f ms of GC pauses left out", ticks, longest / 1000.0, executorLongest / 1000.0,
                    serviceLongest / 1000.0, over, TICK_MICROS / 1000, total / 1000.0, gc / 1000.0);
        }
    }

    /**
     * Milliseconds the JVM's stop-the-world collections have paused every thread for so far. Only pause collectors
     * count: in JDK 21 G1 also has a "G1 Concurrent GC" bean, and ZGC and Shenandoah have "... Cycles" beans, whose time
     * is spent by collector threads while ours runs, so subtracting it would hide real work.
     */
    static long gcMillis() {
        long total = 0;
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            String name = gc.getName();
            if (name.contains("Concurrent") || name.contains("Cycles")) continue;
            total += Math.max(0, gc.getCollectionTime());
        }
        return total;
    }

    /**
     * Ticks until {@code done}, timing each tick's executor and service parts. The garbage collectors' pauses during a
     * tick are left out of its time: they come from what the whole JVM allocates (the test server's other work, a
     * loaded machine) and stop the thread wherever it is, so they say nothing about how the lane splits its work.
     */
    private static Timing timed(Saved s, BooleanSupplier done, int maxTicks, String what) {
        long ticks = 0, longest = 0, total = 0, executorLongest = 0, serviceLongest = 0, gcTotal = 0;
        int over = 0;
        while (!done.getAsBoolean() && ticks < maxTicks) {
            long gcBefore = gcMillis();
            long start = System.nanoTime();
            s.executor.tick();
            long middle = System.nanoTime();
            long gcMiddle = gcMillis();
            s.service.tick();
            long end = System.nanoTime();
            long gcExecutor = (gcMiddle - gcBefore) * 1000;
            long gcService = (gcMillis() - gcMiddle) * 1000;
            long executorTook = Math.max(0, (middle - start) / 1000 - gcExecutor);
            long serviceTook = Math.max(0, (end - middle) / 1000 - gcService);
            long took = executorTook + serviceTook;
            longest = Math.max(longest, took);
            executorLongest = Math.max(executorLongest, executorTook);
            serviceLongest = Math.max(serviceLongest, serviceTook);
            if (took > TICK_MICROS) over++;
            total += took;
            gcTotal += gcExecutor + gcService;
            ticks++;
        }
        check(done.getAsBoolean(), what + " did not finish in " + maxTicks + " ticks");
        return new Timing(ticks, longest, total, executorLongest, serviceLongest, over, gcTotal);
    }

    // ---------------------------------------------------------------------------------------------- measured

    /**
     * The largest steps on a dedicated server's default budget (10 ms, 4 ms for brushes) with saved history: a radius-32
     * cube of glass into air (274,625 cells; its record copies whole, so it is pushed when the stroke ends) and the same
     * with Rotate 4 of a stone and glass mix over a stone and glass checkerboard (1,098,500 cells; about 500 sections
     * that change both ways, so its record is prepared and journaled a few sections a tick while an undo is refused
     * {@code STROKE_PENDING}). No tick runs longer than the brush share plus about one piece. The world equals the
     * kernel's result on a snapshot; after a restart both entries read back sealed and undo exactly. Logs the costs.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_bench", tickLimit = LIMIT)
    public void maxSizeStepsAreWrittenAndCommittedAFewMillisecondsATick(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 740);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 86, z0, x0 + 236, 154, z0 + 160);
        loadAndForce(world, area);
        int[][] centres = {{44, 44}, {116, 44}, {44, 116}, {116, 116}};
        for (int[] c : centres) checkerboard(h, cube65(x0 + c[0], 120, z0 + c[1]));
        WorldSnapshot before = capture(world, area);
        SnapshotWorld snapshot = new SnapshotWorld(world, h.runtime.states(), area);
        int glass = h.state("minecraft:glass"), stone = h.state("minecraft:stone");
        BrushSpec cube = shape(32, ShapeSpec.Kind.CUBE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        BrushSpec four = shape(32, ShapeSpec.Kind.CUBE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0,
                new Pattern.Weighted(new int[] {stone, glass}, new int[] {1, 1}, 7L),
                new Symmetry(Symmetry.Mode.ROTATE_4, 2 * (x0 + 80), 2 * (z0 + 80)));
        List<BrushSpec> specs = List.of(cube, four);
        List<List<Dab>> strokes = List.of(List.of(at(0, x0 + 204, 120, z0 + 44)), List.of(at(0, x0 + 116, 120, z0 + 116)));
        List<Path> folders = new ArrayList<>();
        Path dir = folder();
        folders.add(dir);
        List<String> report = new ArrayList<>();
        try (Saved first = new Saved(h, dir, dedicated(context))) {
            first.load(h.player);
            for (int s = 0; s < 2; s++) {
                int strokeId = 60 + s;
                first.begin(h.player, strokeId, specs.get(s));
                DabOutcome outcome = first.service.dabs(h.player, strokeId, s + 1, strokes.get(s));
                check(outcome.accepted(), "stroke " + s + ": " + outcome);
                int acked = h.acks.seqs.size() + 1;
                Timing write = timed(first, () -> h.acks.seqs.size() == acked, 3_000, "step " + s);
                check(write.ticks() >= 10, "the step of stroke " + s + " took " + write.ticks() + " ticks");
                int entries = first.undoEntries(h.player).size();
                first.service.endStroke(h.player, strokeId);
                Timing commit = Timing.NONE;
                if (s == 0) {
                    check(first.service.queuedCommits(h.player.getUuid()) == 0, "glass into air has nothing to prepare");
                    check(first.undoEntries(h.player).size() == entries + 1, "pushed when the stroke ended");
                } else {
                    check(first.service.queuedCommits(h.player.getUuid()) == 1, "the record is committed over the next ticks");
                    check(first.undoEntries(h.player).size() == entries, "an entry already");
                    EditRejected busy = ClipboardGameTest.refusal(() -> first.service.undo(h.player,
                            ConflictPolicy.SKIP_CONFLICTS));
                    check(busy.reason() == RejectReason.QUEUE_FULL && busy.kind().equals(EditRejected.STROKE_PENDING),
                            "an undo meanwhile: " + busy.reason() + " " + busy.kind());
                    commit = timed(first, () -> first.service.queuedCommits(h.player.getUuid()) == 0, 3_000, "the commit");
                    check(commit.ticks() >= 3, "the commit took " + commit.ticks() + " ticks");
                    check(first.undoEntries(h.player).size() == entries + 1, "one entry per stroke");
                }
                // The brush share (4 ms) plus about one piece (a part or a few sections, a few ms) a tick, with room for
                // a loaded test machine: the first part and one tick the machine stalled may run long, a pattern of long
                // ticks may not, and none may take as long as the step done whole (the Rotate 4 step takes several
                // hundred ms).
                check(write.withinBudget() && commit.withinBudget(), "stroke " + s + ": the lane's ticks ran long "
                        + "(writing: " + write + "; committing: " + commit + ")");
                report.add(String.format(Locale.ROOT, "%s: written in %s%s",
                        s == 0 ? "r32 glass cube into air" : "r32 cube x4 (Rotate 4) of a mix over a checkerboard",
                        write, s == 0 ? ", pushed at once" : "; record prepared and journaled in " + commit));
            }
            check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
            String replay = BrushSymmetryGameTest.replay(specs, strokes, snapshot);
            check(replay.isEmpty(), replay);
            String mismatches = BrushSymmetryGameTest.mismatches(world, area, snapshot);
            check(mismatches.isEmpty(), mismatches);
            LOG.info("Shape brush max-size steps (saved history): {}", String.join("; ", report));
            first.stop();
            try (Saved second = new Saved(h, dir, dedicated(context))) {
                second.load(h.player);
                List<HistoryEntry> steps = second.undoEntries(h.player);
                check(steps.size() == 2 && steps.stream().allMatch(e -> e.label().startsWith("Shape · ")),
                        "after a restart: " + steps.stream().map(HistoryEntry::label).toList());
                second.undo(h.player, 3_000);
                second.undo(h.player, 3_000);
                checkSame(before, capture(world, area), "after undoing both strokes read back from disk");
                second.stop();
            }
        } finally {
            forceChunks(world, area, false);
            h.close();
            for (Path folder : folders) EngineTestBootstrap.deleteTree(folder);
        }
        context.complete();
    }

    // ---------------------------------------------------------------------------------------------- saved history

    /**
     * A crash while a stroke's record is being committed (some sections prepared and journaled, the rest not; the game
     * saves the chunks, through the history hook, and the process dies) leaves an "(interrupted)" step holding
     * everything the stroke wrote, which undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_crash_commit", tickLimit = LIMIT)
    public void aCrashBetweenCommitSlicesLeavesAnUndoableStep(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 742);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 86, z0, x0 + 80, 154, z0 + 80);
        loadAndForce(world, area);
        checkerboard(h, cube65(x0 + 40, 120, z0 + 40));
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass"), stone = h.state("minecraft:stone");
        BrushSpec mix = shape(32, ShapeSpec.Kind.CUBE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0,
                new Pattern.Weighted(new int[] {stone, glass}, new int[] {1, 1}, 5L), Symmetry.NONE);
        List<Path> folders = new ArrayList<>();
        Path dir = folder();
        folders.add(dir);
        try {
            Path copy;
            try (Saved first = new Saved(h, dir, onePartATick(context))) {
                first.load(h.player);
                first.begin(h.player, 1, mix);
                check(first.service.dabs(h.player, 1, 1, List.of(at(0, x0 + 40, 120, z0 + 40))).accepted(), "the step");
                first.tickUntil(() -> h.acks.seqs.size() == 1, 200, "the step");
                first.service.endStroke(h.player, 1);
                check(first.service.queuedCommits(h.player.getUuid()) == 1, "the commit is queued");
                for (int i = 0; i < 5; i++) first.tick(); // a few sections prepared and journaled, one a tick
                check(first.service.queuedCommits(h.player.getUuid()) == 1, "the commit is still running");
                for (int cx = area.min().x() >> 4; cx <= area.max().x() >> 4; cx++) {
                    for (int cz = area.min().z() >> 4; cz <= area.max().z() >> 4; cz++) first.service.beforeChunkSave(world, cx, cz);
                }
                copy = diskNow(dir);
                folders.add(copy);
            }
            try (Saved second = new Saved(h, copy, onePartATick(context))) {
                second.load(h.player);
                List<HistoryEntry> steps = second.undoEntries(h.player);
                check(steps.size() == 1 && steps.get(0).label().startsWith("Shape (interrupted) · "),
                        "steps " + steps.stream().map(HistoryEntry::label).toList());
                second.undo(h.player, 3_000);
                checkSame(before, capture(world, area), "the interrupted stroke undone");
                second.stop();
            }
        } finally {
            forceChunks(world, area, false);
            h.close();
            for (Path folder : folders) EngineTestBootstrap.deleteTree(folder);
        }
        context.complete();
    }

    /**
     * A player leaving mid-step: the step stops at its part boundary (the rest is never written, and the leave takes a
     * few milliseconds, not the rest of the step), its written parts become a step in the saved history, and back on
     * the server the player undoes it exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_leave", tickLimit = LIMIT)
    public void leavingMidStepKeepsThePartsWritten(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 744);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 86, z0, x0 + 160, 154, z0 + 160);
        loadAndForce(world, area);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass");
        // Four cubes: 88 parts, several hundred milliseconds of writes in all.
        BrushSpec cubes = shape(32, ShapeSpec.Kind.CUBE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                new Symmetry(Symmetry.Mode.ROTATE_4, 2 * (x0 + 80), 2 * (z0 + 80)));
        Path dir = folder();
        try (Saved run = new Saved(h, dir, onePartATick(context))) {
            run.load(h.player);
            run.begin(h.player, 1, cubes);
            check(run.service.dabs(h.player, 1, 1, List.of(at(0, x0 + 116, 120, z0 + 116))).accepted(), "the step");
            for (int i = 0; i < 3; i++) run.tick(); // three parts of the dab's own cube: 3 layers of 65 x 65 each
            long start = System.nanoTime();
            run.service.playerLeft(h.player.getUuid());
            long took = (System.nanoTime() - start) / 1_000_000;
            // Not the 85 parts left (which take several times this), only the commit of what was written.
            check(took < 100, "leaving took " + took + " ms");
            for (int i = 0; i < 20; i++) run.tick();
            int layers = 0;
            for (int y = 88; y <= 152; y++) {
                if (world.getBlockState(EngineTestSupport.pos(x0 + 116, y, z0 + 116)).isOf(Blocks.GLASS)) layers++;
            }
            check(layers == 9, "layers written: " + layers);
            check(!world.getBlockState(EngineTestSupport.pos(x0 + 116, 97, z0 + 116)).isOf(Blocks.GLASS)
                    && !world.getBlockState(EngineTestSupport.pos(x0 + 44, 88, z0 + 44)).isOf(Blocks.GLASS), "a later part ran");
            run.load(h.player); // back on the server
            List<HistoryEntry> steps = run.undoEntries(h.player);
            check(steps.size() == 1 && steps.get(0).label().equals("Shape · " + blocks(9L * 65 * 65)),
                    "steps " + steps.stream().map(HistoryEntry::label).toList());
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
     * A server stop mid-step: the executor drops the step between two parts, the stroke's record (what the parts wrote)
     * becomes a step, and after the restart it undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_stop", tickLimit = LIMIT)
    public void aServerStopMidStepKeepsThePartsWritten(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 746);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 86, z0, x0 + 80, 154, z0 + 80);
        loadAndForce(world, area);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass");
        BrushSpec cube = shape(32, ShapeSpec.Kind.CUBE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        Path dir = folder();
        try {
            try (Saved first = new Saved(h, dir, onePartATick(context))) {
                first.load(h.player);
                first.begin(h.player, 1, cube);
                check(first.service.dabs(h.player, 1, 1, List.of(at(0, x0 + 40, 120, z0 + 40))).accepted(), "the step");
                for (int i = 0; i < 2; i++) first.tick();
                first.stop();
            }
            check(!world.getBlockState(EngineTestSupport.pos(x0 + 40, 94, z0 + 40)).isOf(Blocks.GLASS), "a later part ran");
            try (Saved second = new Saved(h, dir, onePartATick(context))) {
                second.load(h.player);
                List<HistoryEntry> steps = second.undoEntries(h.player);
                check(steps.size() == 1 && steps.get(0).label().equals("Shape · " + blocks(6L * 65 * 65)),
                        "steps " + steps.stream().map(HistoryEntry::label).toList());
                second.undo(h.player, 200);
                checkSame(before, capture(world, area), "after the restart and the undo");
                second.stop();
            }
        } finally {
            forceChunks(world, area, false);
            h.close();
            EngineTestBootstrap.deleteTree(dir);
        }
        context.complete();
    }

    // ---------------------------------------------------------------------------------------------- the lane

    /**
     * A new stroke begun while the last one's step is still being written: the last one is finished and committed
     * first, so the history holds the two in the order they were made, and undoing both restores the area.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_next_stroke", tickLimit = LIMIT)
    public void aNewStrokeMidStepComesAfterItInHistory(TestContext context) {
        EditExecutor executor = onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 748);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 86, z0, x0 + 120, 154, z0 + 80);
        loadAndForce(world, area);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass"), wool = h.state("minecraft:white_wool");
        BrushSpec large = shape(32, ShapeSpec.Kind.CUBE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        BrushSpec small = shape(3, ShapeSpec.Kind.SPHERE, 7, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(wool),
                Symmetry.NONE);
        BrushSymmetryGameTest.begin(h, 1, large);
        check(h.service.dabs(h.player, 1, 1, List.of(at(0, x0 + 40, 120, z0 + 40))).accepted(), "the large step");
        executor.tick();
        executor.tick();
        BrushSymmetryGameTest.begin(h, 2, small); // ends the first stroke mid-step: too much to finish at once
        check(h.service.queuedCommits(h.player.getUuid()) == 1, "the first stroke's commit follows its step");
        check(h.service.dabs(h.player, 2, 2, List.of(at(0, x0 + 100, 120, z0 + 40))).accepted(), "the small dab");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 2, 100, "both strokes");
        h.service.endStroke(h.player, 2);
        MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 50, "the commits");
        List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
        check(entries.size() == 2, "entries " + entries.size());
        check(entries.get(1).label().equals("Shape · " + blocks(65L * 65 * 65)) && entries.get(0).label().startsWith("Shape · "),
                "the large stroke first: " + entries.stream().map(HistoryEntry::label).toList());
        check(h.events.dabRejections.isEmpty(), "dabs rejected: " + h.events.dabRejections);
        ShapeBrushGameTest.undoAll(h, executor, 2, 50);
        checkSame(before, capture(world, area), "after undoing both");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * The executor's brush queue full when a stroke ends: the stroke's commit is taken all the same (it ends work
     * admitted already), after the step it follows, so what the step writes is in the stroke's one entry and undoes
     * exactly; other work beyond the cap is refused.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_full_queue", tickLimit = LIMIT)
    public void aFullBrushQueueStillTakesTheCommit(TestContext context) {
        EditExecutor executor = onePartATick(context, 70);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 750);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 86, z0, x0 + 80, 154, z0 + 80);
        loadAndForce(world, area);
        WorldSnapshot before = capture(world, area);
        int glass = h.state("minecraft:glass");
        BrushSpec cube = shape(32, ShapeSpec.Kind.CUBE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        BrushSymmetryGameTest.begin(h, 1, cube);
        check(h.service.dabs(h.player, 1, 1, List.of(at(0, x0 + 40, 120, z0 + 40))).accepted(), "the step (68 units)");
        List<Piece> filler = new ArrayList<>();
        while (executor.brushQueueSize() < 70) {
            Piece piece = new Piece(null, 0, 1);
            check(executor.submitBrush(piece), "filler refused below the cap");
            filler.add(piece);
        }
        Piece over = new Piece(null, 0, 1);
        check(!executor.submitBrush(over) && over.dropped, "work beyond the cap was taken");
        h.service.endStroke(h.player, 1);
        check(h.service.queuedCommits(h.player.getUuid()) == 1, "the commit was not queued");
        MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedCommits(h.player.getUuid()) == 0, 500, "the step and commit");
        List<HistoryEntry> entries = h.service.historyService().undoEntries(h.player.getUuid());
        check(entries.size() == 1 && entries.get(0).label().equals("Shape · " + blocks(65L * 65 * 65)),
                "entries " + entries.stream().map(HistoryEntry::label).toList());
        ShapeBrushGameTest.undoAll(h, executor, 1, 200);
        checkSame(before, capture(world, area), "after the undo");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * A raise stroke over more sections than are built at once: an undo right after it waits for its commit (refused
     * {@code QUEUE_FULL} {@code STROKE_PENDING}, the kind the client holds and retries), and the retry a tick or two
     * later goes ahead and undoes it exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_terrain_undo", tickLimit = LIMIT)
    public void aLargeTerrainStrokeIsUndoneOnceCommitted(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 752);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 255, 135, z0 + 127);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 256, 128);
        WorldSnapshot before = capture(world, area);
        // Paint three deep with stone and grass: cells change both ways, so every section is compared cell by cell when
        // the record is built (a raise only fills air, which builds at once).
        BrushSpec raise = new BrushSpec(BrushTool.PAINT, 8, 1f, Falloff.SMOOTH, Shape.CIRCLE,
                new Pattern.Weighted(new int[] {h.state("minecraft:stone"), h.state("minecraft:grass_block")}, new int[] {1, 1}, 3L),
                SurfaceMask.ANY, 3, 0, 1L);
        List<Dab> dabs = new ArrayList<>();
        int index = 0;
        // Rows 12 apart, dabs 8 apart: the raise reaches every one of the 128 chunk columns.
        for (int z = 12; z <= 116; z += 12) {
            for (int x = 12; x <= 244; x += 8) dabs.add(EditTestSupport.dab(index++, x0 + x, 112, z0 + z));
        }
        BrushSymmetryGameTest.begin(h, 1, raise);
        int seq = 1;
        for (int from = 0; from < dabs.size(); from += 8) {
            DabOutcome outcome = h.service.dabs(h.player, 1, seq++, dabs.subList(from, Math.min(dabs.size(), from + 8)));
            check(outcome.accepted(), "the dabs: " + outcome);
            MultiplayerGameTest.tickUntil(executor, () -> h.service.queuedDabs(h.player.getUuid()) == 0, 20, "the dabs");
        }
        h.service.endStroke(h.player, 1);
        RecordingListener undo = new RecordingListener();
        int refusals = 0;
        for (int tick = 0; tick < 20; tick++) {
            try {
                h.service.undo(h.player, ConflictPolicy.SKIP_CONFLICTS, undo);
                break;
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.QUEUE_FULL && e.kind().equals(EditRejected.STROKE_PENDING),
                        "the undo was refused: " + e.getMessage() + " " + e.kind());
                refusals++;
                executor.tick();
            }
        }
        check(refusals > 0, "the record was built at once; the stroke is too small to test the wait");
        MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 50, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED, "the undo " + undo.result);
        checkSame(before, capture(world, area), "after the undo");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * The brush permission removed between two parts of a step: the next part is refused NO_PERMISSION, the parts
     * written stay (one step) and undo exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_permission_mid_step", tickLimit = LIMIT)
    public void thePermissionIsAskedAgainBeforeEachPart(TestContext context) {
        EditExecutor executor = onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] corner = regionCorner(context, 754);
        int x0 = corner[0], z0 = corner[1];
        Box area = box(x0, 95, z0, x0 + 47, 150, z0 + 47);
        loadAndForce(world, area);
        WorldSnapshot before = capture(world, area);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.BRUSH);
        int glass = h.state("minecraft:glass");
        // 41 layers of 41 x 41 cells, y 100 to 140, in five parts: 100-108, 109-117, 118-126, 127-135 and 136-140.
        BrushSpec large = shape(20, ShapeSpec.Kind.CUBE, 41, Facing.UP, ShapeSpec.Mode.PLACE, 0, new Pattern.Single(glass),
                Symmetry.NONE);
        try {
            h.service.beginStroke(builder, 1, large);
        } catch (EditRejected e) {
            throw new GameTestException("the stroke was refused: " + e.getMessage());
        }
        check(h.service.dabs(builder, 1, 1, List.of(at(0, x0 + 24, 120, z0 + 24))).accepted(), "the step");
        executor.tick();
        executor.tick(); // two parts
        EditTestSupport.grant(builder, Perm.USE); // the brush node is gone
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1, 20, "the step");
        check(h.events.dabRejections.equals(List.of(RejectReason.NO_PERMISSION)), "refusals " + h.events.dabRejections);
        check(world.getBlockState(EngineTestSupport.pos(x0 + 24, 117, z0 + 24)).isOf(Blocks.GLASS)
                && !world.getBlockState(EngineTestSupport.pos(x0 + 24, 118, z0 + 24)).isOf(Blocks.GLASS),
                "two parts written, no more");
        h.service.endStroke(builder, 1);
        List<HistoryEntry> entries = h.service.historyService().undoEntries(builder.getUuid());
        check(entries.size() == 1 && entries.get(0).label().equals("Shape · " + blocks(18L * 41 * 41)),
                "entries " + entries.stream().map(HistoryEntry::label).toList());
        RecordingListener undo = MultiplayerGameTest.historyStep(h, builder, true);
        MultiplayerGameTest.tickUntil(executor, () -> undo.result != null, 20, "the undo");
        checkSame(before, capture(world, area), "after the undo");
        executor.shutdown();
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /** Brush work of a test: {@code pieces} pieces, each predicted to take {@code predicted} ns; records what ran. */
    static final class Piece implements BrushWork {
        final UUID owner;
        final long predicted;
        int left;
        int runs;
        boolean dropped;
        boolean essential;
        RuntimeException failure;
        List<Piece> order;

        Piece(UUID owner, long predicted, int pieces) {
            this.owner = owner;
            this.predicted = predicted;
            this.left = pieces;
        }

        @Override
        public void run() {
            runPart(Long.MAX_VALUE);
        }

        @Override
        public boolean runPart(long deadline) {
            runs++;
            if (order != null) order.add(this);
            if (failure != null) throw failure;
            return --left <= 0;
        }

        @Override
        public void dropped() {
            dropped = true;
        }

        @Override
        public UUID owner() {
            return owner;
        }

        @Override
        public long nextPieceNanos() {
            return predicted;
        }

        @Override
        public boolean essential() {
            return essential;
        }
    }

    /**
     * The brush lane: the tick's first turn always runs; a later one only when its piece is predicted to end by the
     * lane's deadline (else that player goes first next tick); work queued with {@code submitBrushNext} runs before the
     * player's other work; a piece that throws is logged and dropped while the lane goes on; a full queue refuses work
     * but takes essential work (a commit).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_shape_lane", tickLimit = LIMIT)
    public void theBrushLaneStartsOnlyPiecesThatFitAndSurvivesFailures(TestContext context) {
        EditExecutor executor = new EditExecutor(context.getWorld().getServer(), EngineTestSupport.runtime(context).states(),
                new EditExecutor.Settings(10_000_000L, 0, 0.4, 2, 8, 32, 64, UnloadedPolicy.LOAD, 4, 16_384));
        List<Piece> order = new ArrayList<>();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Piece small = new Piece(a, 0, 2);
        Piece huge = new Piece(b, TimeUnit.SECONDS.toNanos(10), 1);
        small.order = order;
        huge.order = order;
        check(executor.submitBrush(small) && executor.submitBrush(huge), "refused");
        executor.tick();
        check(order.equals(List.of(small)), "tick 1: the huge piece started after the first turn: " + order.size());
        executor.tick();
        check(order.equals(List.of(small, huge, small)), "tick 2: the huge piece first, then the small one: " + order.size());
        check(executor.brushQueueSize() == 0, "left " + executor.brushQueueSize());

        Piece later = new Piece(a, 0, 1), next = new Piece(a, 0, 1);
        later.order = order;
        next.order = order;
        order.clear();
        executor.submitBrush(later);
        executor.submitBrushNext(next);
        executor.tick();
        check(order.equals(List.of(next, later)), "submitBrushNext did not go first");

        Piece failing = new Piece(a, 0, 1), after = new Piece(b, 0, 1);
        failing.failure = new IllegalStateException("a failing piece (test)");
        failing.order = order;
        after.order = order;
        order.clear();
        executor.submitBrush(failing);
        executor.submitBrush(after);
        executor.tick();
        check(order.equals(List.of(failing, after)) && executor.brushQueueSize() == 0, "the lane stopped at a failure");

        List<Piece> queued = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Piece piece = new Piece(a, 0, 1);
            check(executor.submitBrush(piece), "refused below the cap");
            queued.add(piece);
        }
        Piece refused = new Piece(a, 0, 1);
        check(!executor.submitBrush(refused) && refused.dropped, "a full queue took ordinary work");
        Piece commit = new Piece(a, 0, 1);
        commit.essential = true;
        check(executor.submitBrush(commit) && !commit.dropped, "a full queue refused essential work");
        for (int i = 0; i < 10 && executor.brushQueueSize() > 0; i++) executor.tick();
        check(commit.runs == 1 && queued.stream().allMatch(p -> p.runs == 1), "not all ran");
        executor.shutdown();
        context.complete();
    }
}
