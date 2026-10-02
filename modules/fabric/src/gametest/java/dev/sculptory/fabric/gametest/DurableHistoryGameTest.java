package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.dab;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.tickUntil;

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
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.store.HistoryStore;
import dev.sculptory.core.history.store.StorageIo;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.engine.impl.EngineEditService;
import dev.sculptory.fabric.engine.impl.FabricHistoryCodec;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.schem.FabricDataFixHook;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.HistoryService;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import dev.sculptory.server.engine.impl.JobRequest;
import dev.sculptory.server.platform.WriteOptions;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash-safe undo history on a real world. Each test runs edit services
 * that save history in a private folder: a <b>restart</b> stops one as the server does and starts a fresh one over the
 * same folder; a <b>crash</b> copies the history folder at the moment the game saved a chunk (what reached the disk
 * then; the journal is written only while the server thread waits, so a chunk-save hook that did not wait would leave
 * the copy without the chunk's history) and starts the next run over the copy. After a restart, undo and redo are exact
 * (block entities included, for a player without operator rights); an entry whose cells never reached the saved world
 * undoes as nothing to change; a job or brush stroke cut off by a crash leaves an "(interrupted)" entry holding exactly
 * what it wrote; an open stroke becomes a step when its player leaves and at a server stop; two players' histories stay
 * their own; and the game's chunk saves go through the history hook. A failing check still closes every store and
 * deletes the folders. GameTest regions 580-599 and 608.
 */
public final class DurableHistoryGameTest implements FabricGameTest {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int TICKS = 2000;
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

    /** One run of the server's edit service: its own executor, and history saved in {@code dir}. */
    static final class Run {
        final Harness h;
        final EditExecutor<ServerWorld> executor;
        final EngineEditService service;

        Run(Harness h, Path dir, long cellsPerTick, StorageIo io) {
            this.h = h;
            this.executor = MultiplayerGameTest.executor(h.context, cellsPerTick);
            HistoryStore store;
            try {
                store = HistoryStore.open(dir, new FabricHistoryCodec(h.runtime.states(),
                        FabricDataFixHook.currentDataVersion()), HistoryStore.Settings.DEFAULTS, io, STORE_LOG);
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
                check(System.nanoTime() < deadline,
                        "the history of " + player.getGameProfile().getName() + " did not load");
                service.tick();
                pause();
            }
        }

        JobResult fill(ServerPlayerEntity player, Box box, String state) {
            RecordingListener listener = new RecordingListener();
            try {
                service.run(player, new OpSpec.Fill(box, new Pattern.Single(h.state(state)), CellMask.ANY),
                        RunOptions.DEFAULT, listener);
            } catch (EditRejected e) {
                throw new GameTestException("the fill was refused: " + e.getMessage());
            }
            tickUntil(executor, () -> listener.result != null, TICKS, "the fill");
            service.tick();
            check(listener.result.outcome() == JobOutcome.COMPLETED, "the fill " + listener.result);
            return listener.result;
        }

        JobResult step(ServerPlayerEntity player, boolean undo) {
            RecordingListener listener = new RecordingListener();
            try {
                if (undo) {
                    service.undo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
                } else {
                    service.redo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
                }
            } catch (EditRejected e) {
                throw new GameTestException((undo ? "undo" : "redo") + " was refused: " + e.getMessage());
            }
            tickUntil(executor, () -> listener.result != null, TICKS, undo ? "the undo" : "the redo");
            service.tick();
            check(listener.result.outcome() == JobOutcome.COMPLETED, (undo ? "the undo " : "the redo ") + listener.result);
            return listener.result;
        }

        /** Pastes {@code clipboard} (made the player's clipboard) at {@code origin}, run to its end. */
        JobResult paste(ServerPlayerEntity player, Clipboard clipboard, BlockPos origin) {
            UUID id = service.clipboards().install(player.getUuid(), clipboard).id();
            RecordingListener listener = new RecordingListener();
            try {
                service.run(player, new OpSpec.Paste(new SourceRef.Clipboard(id), new dev.sculptory.core.BlockPos(
                        origin.getX(), origin.getY(), origin.getZ()), Transform.IDENTITY, PasteOptions.DEFAULT),
                        RunOptions.DEFAULT, listener);
            } catch (EditRejected e) {
                throw new GameTestException("the paste was refused: " + e.getMessage());
            }
            tickUntil(executor, () -> listener.result != null, TICKS, "the paste");
            service.tick();
            check(listener.result.outcome() == JobOutcome.COMPLETED, "the paste " + listener.result);
            return listener.result;
        }

        /** Undo anyway of the player's run of undo steps, run to its end. */
        JobResult undoAnyway(ServerPlayerEntity player) {
            int steps = service.historyService().session(player.getUuid()).run()
                    .orElseThrow(() -> new GameTestException("no Undo anyway offer")).entries().size();
            RecordingListener listener = new RecordingListener();
            try {
                service.historyOverwrite(player, false, steps, listener);
            } catch (EditRejected e) {
                throw new GameTestException("Undo anyway was refused: " + e.getMessage());
            }
            tickUntil(executor, () -> listener.result != null, TICKS, "Undo anyway");
            service.tick();
            check(listener.result.outcome() == JobOutcome.COMPLETED, "Undo anyway " + listener.result);
            return listener.result;
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

        /** Lets go of whatever the run still holds (idempotent; after a crash, or a failed check). */
        void release() {
            service.historyService().closeStore(10_000);
            executor.shutdown();
        }
    }

    /** One test's harness, region, folders and runs; {@link #close()} releases all of them, whatever happened. */
    static final class Scene implements AutoCloseable {
        final TestContext context;
        final Harness h;
        final int x;
        final int z;
        final Path dir;
        final List<Path> folders = new ArrayList<>();
        final List<Run> runs = new ArrayList<>();
        Box region;

        Scene(TestContext context, int slot) {
            this.context = context;
            this.h = new Harness(context);
            int[] at = regionCorner(context, slot);
            this.x = at[0];
            this.z = at[1];
            this.dir = folder();
        }

        /** A fresh folder for saved history, in the GameTest server's temporary history folder. */
        Path folder() {
            String root = System.getProperty(EditServiceHost.HISTORY_DIR_PROPERTY);
            Path base = root != null ? Path.of(root)
                    : Path.of(System.getProperty("java.io.tmpdir"), "sculptory-gametest-history");
            Path folder = base.resolve("durable-tests").resolve(UUID.randomUUID().toString()).normalize();
            folders.add(folder);
            return folder;
        }

        /** Loads and forces {@code box}, and lays the ground in it. */
        void area(Box box, int top) {
            region = box;
            loadAndForce(h.world, box);
            ground(h, box, top);
        }

        Run run(Path folder, long cellsPerTick, StorageIo io) {
            Run run = new Run(h, folder, cellsPerTick, io);
            runs.add(run);
            return run;
        }

        Run run() {
            return run(dir, 0, StorageIo.SYSTEM);
        }

        /**
         * A crash at the moment the game saved chunks: copies the history files as they are on disk now into a new
         * folder (the run itself is left as the crash left it).
         */
        Path diskNow(Path from) {
            Path copy = folder();
            try {
                Files.createDirectories(copy);
                for (Path file : StorageIo.SYSTEM.list(from)) {
                    if (file.getFileName().toString().endsWith(HistoryStore.EXTENSION)) {
                        Files.copy(file, copy.resolve(file.getFileName()));
                    }
                }
            } catch (IOException e) {
                throw new GameTestException("copying the history folder failed: " + e);
            }
            return copy;
        }

        @Override
        public void close() {
            for (Run run : runs) {
                try {
                    run.release();
                } catch (RuntimeException e) {
                    LOG.warn("Releasing a test run failed", e);
                }
            }
            if (region != null) forceChunks(h.world, region, false);
            h.close();
            for (Path folder : folders) EngineTestBootstrap.deleteTree(folder);
        }
    }

    /**
     * The real file system, except that journal writes happen only while {@link #holder} (the server thread) is not
     * running: waiting in a chunk save's barrier, a flush or a sleep. Whatever the server thread does between two
     * waits has not reached the disk yet, so a chunk-save hook that did not wait would leave the copy taken right after
     * it without the chunk's history. No clock is involved: a loaded machine changes nothing.
     */
    static final class WritesWhileWaiting implements StorageIo {
        volatile Thread holder;

        WritesWhileWaiting(Thread holder) {
            this.holder = holder;
        }

        @Override
        public File open(Path file) throws IOException {
            File inner = SYSTEM.open(file);
            boolean temp = file.toString().endsWith(".tmp");
            return new File() {
                @Override
                public long size() throws IOException {
                    return inner.size();
                }

                @Override
                public int read(ByteBuffer dst, long position) throws IOException {
                    return inner.read(dst, position);
                }

                @Override
                public void write(ByteBuffer src, long position) throws IOException {
                    Thread waiter = holder;
                    while (!temp && waiter != null && waiter.getState() == Thread.State.RUNNABLE) {
                        LockSupport.parkNanos(100_000);
                        waiter = holder;
                    }
                    inner.write(src, position);
                }

                @Override
                public void truncate(long size) throws IOException {
                    inner.truncate(size);
                }

                @Override
                public void force() throws IOException {
                    inner.force();
                }

                @Override
                public void close() throws IOException {
                    inner.close();
                }
            };
        }

        @Override
        public boolean exists(Path file) throws IOException {
            return SYSTEM.exists(file);
        }

        @Override
        public List<Path> list(Path dir) throws IOException {
            return SYSTEM.list(dir);
        }

        @Override
        public void createDirectories(Path dir) throws IOException {
            SYSTEM.createDirectories(dir);
        }

        @Override
        public void replace(Path source, Path target) throws IOException {
            SYSTEM.replace(source, target);
        }

        @Override
        public void delete(Path file) throws IOException {
            SYSTEM.delete(file);
        }

        @Override
        public void copy(Path source, Path target) throws IOException {
            SYSTEM.copy(source, target);
        }
    }

    private static void pause() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stone, then two layers of dirt, grass at {@code top} and air above, in every cell of {@code region}. */
    private static void ground(Harness h, Box region, int top) {
        BlockWriter writer = h.runtime.writer(h.world, new WriteOptions(false, true));
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt");
        int grass = h.state("minecraft:grass_block"), air = h.state("minecraft:air");
        for (int y = region.min().y(); y <= region.max().y(); y++) {
            int state = y < top - 2 ? stone : y < top ? dirt : y == top ? grass : air;
            for (int z = region.min().z(); z <= region.max().z(); z++) {
                for (int x = region.min().x(); x <= region.max().x(); x++) writer.write(x, y, z, state, null);
            }
        }
    }

    private static int count(Harness h, Box box, Block block) {
        int n = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (h.world.getBlockState(pos(x, y, z)).isOf(block)) n++;
                }
            }
        }
        return n;
    }

    private static String blocks(long n) {
        return n == 1 ? "1 block" : String.format(Locale.ROOT, "%,d blocks", n);
    }

    private static List<String> labels(List<HistoryEntry> entries) {
        return entries.stream().map(HistoryEntry::label).toList();
    }

    /** A raise stroke of six dabs around (x + 12, z + 16), left open (the mouse still down). */
    private static void stroke(Run run, ServerPlayerEntity player, int x, int z) {
        BrushSpec spec = new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L);
        try {
            run.service.beginStroke(player, 1, spec);
        } catch (EditRejected e) {
            throw new GameTestException("the stroke was refused: " + e.getMessage());
        }
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 6; i++) dabs.add(dab(i, x + 10 + i, 101, z + 16));
        DabOutcome outcome = run.service.dabs(player, 1, 1, dabs);
        check(outcome.accepted(), "the dabs " + outcome);
        tickUntil(run.executor, () -> run.service.queuedDabs(player.getUuid()) == 0, TICKS, "the dabs");
    }

    // ---------------------------------------------------------------------------------------------- restarts

    /**
     * Carol (no operator rights, only use and region) fills a layer holding a sign with text and a chest of diamonds
     * with glass, fills gold over part of it and undoes the gold. After a restart her history reads the same; redo
     * brings the gold back, and undoing both restores the world exactly: the sign's text and the chest's diamonds come
     * back for her as they would have before the restart (the saved block entities kept their server origin).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_restart", tickLimit = LIMIT)
    public void editsUndoAndRedoExactlyAfterARestart(TestContext context) {
        try (Scene s = new Scene(context, 580)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            ServerPlayerEntity carol = h.addPlayer(false);
            EditTestSupport.grant(carol, Perm.USE, Perm.REGION);
            s.area(box(x, 96, z, x + 31, 111, z + 15), 100);
            BlockWriter writer = h.runtime.writer(h.world, new WriteOptions(false, true));
            BlockPos sign = pos(x + 2, 101, z + 2);
            writer.write(sign.getX(), sign.getY(), sign.getZ(), h.state("minecraft:oak_sign"), null);
            ((SignBlockEntity) h.world.getBlockEntity(sign))
                    .setText(new SignText().withMessage(0, Text.literal("kept")), true);
            BlockPos chest = pos(x + 5, 101, z + 5);
            writer.write(chest.getX(), chest.getY(), chest.getZ(), h.state("minecraft:chest[facing=east]"), null);
            ((ChestBlockEntity) h.world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 5));
            WorldSnapshot original = capture(h.world, s.region);

            Run first = s.run();
            first.load(carol);
            check(first.fill(carol, box(x, 100, z, x + 31, 102, z + 15), "minecraft:glass").changed() == 1536,
                    "the glass");
            first.fill(carol, box(x + 4, 101, z + 4, x + 9, 106, z + 9), "minecraft:gold_block");
            WorldSnapshot both = capture(h.world, s.region);
            first.step(carol, true);
            WorldSnapshot glassOnly = capture(h.world, s.region);
            HistorySnapshot saved = first.service.history(carol);
            first.stop();

            Run second = s.run();
            second.load(carol);
            HistorySnapshot restored = second.service.history(carol);
            check(restored.undoLabels().equals(saved.undoLabels()) && restored.redoLabels().equals(saved.redoLabels())
                    && restored.bytes() == saved.bytes(), "restored " + restored + ", saved " + saved);
            check(second.step(carol, false).skippedConflicts() == 0, "the redo after the restart met conflicts");
            checkSame(both, capture(h.world, s.region), "redo after the restart");
            second.step(carol, true);
            checkSame(glassOnly, capture(h.world, s.region), "undo of the gold after the restart");
            check(second.step(carol, true).skippedConflicts() == 0, "the undo of the glass met conflicts");
            checkSame(original, capture(h.world, s.region), "undo of the glass after the restart");
            String text = ((SignBlockEntity) h.world.getBlockEntity(sign)).getText(true).getMessage(0, false)
                    .getString();
            check(text.equals("kept"), "the sign reads \"" + text + "\"");
            ItemStack stack = ((ChestBlockEntity) h.world.getBlockEntity(chest)).getStack(0);
            check(stack.isOf(Items.DIAMOND) && stack.getCount() == 5, "the chest holds " + stack);
            second.stop();
        }
        context.complete();
    }

    /** An undone edit is redone after a restart, exactly, and undoes again. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_redo", tickLimit = LIMIT)
    public void redoWorksAfterARestart(TestContext context) {
        try (Scene s = new Scene(context, 581)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            ServerPlayerEntity alice = h.player;
            s.area(box(x, 96, z, x + 15, 107, z + 15), 99);
            WorldSnapshot original = capture(h.world, s.region);

            Run first = s.run();
            first.load(alice);
            first.fill(alice, box(x + 2, 98, z + 2, x + 13, 103, z + 13), "minecraft:oak_planks");
            WorldSnapshot filled = capture(h.world, s.region);
            first.step(alice, true);
            checkSame(original, capture(h.world, s.region), "undo before the restart");
            first.stop();

            Run second = s.run();
            second.load(alice);
            HistorySnapshot history = second.service.history(alice);
            check(!history.canUndo() && history.canRedo(), "after the restart: " + history);
            second.step(alice, false);
            checkSame(filled, capture(h.world, s.region), "redo after the restart");
            second.step(alice, true);
            checkSame(original, capture(h.world, s.region), "undo of the redo");
            second.stop();
        }
        context.complete();
    }

    /** Alice and Bob each keep their own steps across a restart; each undo touches only its owner's area. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_players", tickLimit = LIMIT)
    public void twoPlayersKeepTheirOwnHistoriesAcrossARestart(TestContext context) {
        try (Scene s = new Scene(context, 582)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            ServerPlayerEntity alice = h.player;
            ServerPlayerEntity bob = h.addPlayer();
            s.area(box(x, 96, z, x + 31, 107, z + 15), 99);
            Box alicesArea = box(x, 96, z, x + 15, 107, z + 15);
            Box bobsArea = box(x + 16, 96, z, x + 31, 107, z + 15);
            WorldSnapshot aliceOriginal = capture(h.world, alicesArea);
            WorldSnapshot bobOriginal = capture(h.world, bobsArea);

            Run first = s.run();
            first.load(alice);
            first.load(bob);
            first.fill(alice, box(x + 1, 100, z + 1, x + 14, 101, z + 14), "minecraft:glass");
            first.fill(bob, box(x + 17, 100, z + 1, x + 30, 104, z + 14), "minecraft:gold_block");
            first.fill(alice, box(x + 3, 102, z + 3, x + 8, 105, z + 8), "minecraft:stone");
            WorldSnapshot bobsFill = capture(h.world, bobsArea);
            first.stop();

            Run second = s.run();
            second.load(alice);
            second.load(bob);
            check(second.undoEntries(alice).size() == 2 && second.undoEntries(bob).size() == 1, "alice "
                    + second.undoEntries(alice).size() + " steps, bob " + second.undoEntries(bob).size());
            check(second.undoEntries(bob).get(0).owner().equals(bob.getUuid()), "bob's step belongs to him");
            second.step(alice, true);
            second.step(alice, true);
            checkSame(aliceOriginal, capture(h.world, alicesArea), "alice's two undos");
            checkSame(bobsFill, capture(h.world, bobsArea), "bob's area after alice's undos");
            second.step(bob, true);
            checkSame(bobOriginal, capture(h.world, bobsArea), "bob's undo");
            second.stop();
        }
        context.complete();
    }

    /** A brush stroke is one step after a restart too: undo restores the terrain exactly, redo raises it again. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_stroke", tickLimit = LIMIT)
    public void aBrushStrokeUndoesExactlyAfterARestart(TestContext context) {
        try (Scene s = new Scene(context, 583)) {
            Harness h = s.h;
            ServerPlayerEntity alice = h.player;
            s.area(box(s.x, 90, s.z, s.x + 31, 120, s.z + 31), 100);
            WorldSnapshot original = capture(h.world, s.region);

            Run first = s.run();
            first.load(alice);
            stroke(first, alice, s.x, s.z);
            first.service.endStroke(alice, 1);
            WorldSnapshot raised = capture(h.world, s.region);
            check(EditTestSupport.difference(original, raised) != null, "the stroke changed nothing");
            first.stop();

            Run second = s.run();
            second.load(alice);
            List<HistoryEntry> steps = second.undoEntries(alice);
            check(steps.size() == 1 && steps.get(0).label().startsWith("Raise stroke · "), "steps " + labels(steps));
            second.step(alice, true);
            checkSame(original, capture(h.world, s.region), "the stroke undone after the restart");
            second.step(alice, false);
            checkSame(raised, capture(h.world, s.region), "the stroke redone after the restart");
            second.stop();
        }
        context.complete();
    }

    /** A stroke still open when its player leaves becomes a step of their saved history (no end-of-stroke message). */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_leave_stroke", tickLimit = LIMIT)
    public void anOpenStrokeBecomesAStepWhenThePlayerLeaves(TestContext context) {
        try (Scene s = new Scene(context, 588)) {
            Harness h = s.h;
            ServerPlayerEntity alice = h.player;
            s.area(box(s.x, 90, s.z, s.x + 31, 120, s.z + 31), 100);
            WorldSnapshot original = capture(h.world, s.region);

            Run first = s.run();
            first.load(alice);
            stroke(first, alice, s.x, s.z);
            first.service.playerLeft(alice.getUuid()); // the connection drops, the mouse still down
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (first.service.historyService().find(alice.getUuid()).isPresent() && System.nanoTime() < deadline) {
                first.service.tick();
                pause();
            }
            check(first.service.historyService().find(alice.getUuid()).isEmpty()
                    && first.service.historyService().offline(alice.getUuid()).map(o -> o.size() == 1).orElse(false),
                    "the stroke became a step and the history was kept (unloaded)");
            first.stop();

            Run second = s.run();
            second.load(alice);
            List<HistoryEntry> steps = second.undoEntries(alice);
            check(steps.size() == 1 && steps.get(0).label().startsWith("Raise stroke · "), "steps " + labels(steps));
            second.step(alice, true);
            checkSame(original, capture(h.world, s.region), "the stroke undone after leaving and a restart");
            second.stop();
        }
        context.complete();
    }

    /** A stroke still open at a server stop becomes a step (the stop commits it before the world is saved). */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_stop_stroke", tickLimit = LIMIT)
    public void anOpenStrokeBecomesAStepAtServerStop(TestContext context) {
        try (Scene s = new Scene(context, 589)) {
            Harness h = s.h;
            ServerPlayerEntity alice = h.player;
            s.area(box(s.x, 90, s.z, s.x + 31, 120, s.z + 31), 100);
            WorldSnapshot original = capture(h.world, s.region);

            Run first = s.run();
            first.load(alice);
            stroke(first, alice, s.x, s.z);
            first.stop(); // no end-of-stroke message

            Run second = s.run();
            second.load(alice);
            List<HistoryEntry> steps = second.undoEntries(alice);
            check(steps.size() == 1 && steps.get(0).label().startsWith("Raise stroke · "), "steps " + labels(steps));
            second.step(alice, true);
            checkSame(original, capture(h.world, s.region), "the stroke undone after the stop");
            second.stop();
        }
        context.complete();
    }

    // ---------------------------------------------------------------------------------------------- crashes

    /**
     * The world on disk older than the journal: after a restart, the fill's cells in one of its two chunk columns hold
     * what they held before it (that chunk was never saved after the fill). Undo changes only the other column, counts
     * no conflict and leaves the world as before the fill; redo then fills both again.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_older_world", tickLimit = LIMIT)
    public void anEditWhoseCellsNeverReachedTheSavedWorldUndoesAsNothingToChange(TestContext context) {
        try (Scene s = new Scene(context, 584)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            ServerPlayerEntity alice = h.player;
            s.area(box(x, 96, z, x + 31, 107, z + 15), 99);
            WorldSnapshot original = capture(h.world, s.region);

            Run first = s.run();
            first.load(alice);
            check(first.fill(alice, box(x, 100, z, x + 31, 101, z + 15), "minecraft:glass").changed() == 1024,
                    "the fill");
            WorldSnapshot filled = capture(h.world, s.region);
            first.stop();

            // The first chunk column as an older save left it.
            BlockWriter writer = h.runtime.writer(h.world, new WriteOptions(false, true));
            for (int y = 100; y <= 101; y++) {
                for (int zz = z; zz <= z + 15; zz++) {
                    for (int xx = x; xx <= x + 15; xx++) writer.write(xx, y, zz, original.get(xx, y, zz), null);
                }
            }
            Run second = s.run();
            second.load(alice);
            JobResult undo = second.step(alice, true);
            check(undo.changed() == 512 && undo.skippedConflicts() == 0 && undo.skippedProtected() == 0,
                    "undo " + undo);
            checkSame(original, capture(h.world, s.region), "undo over the older world");
            JobResult redo = second.step(alice, false);
            check(redo.changed() == 1024 && redo.skippedConflicts() == 0, "redo " + redo);
            checkSame(filled, capture(h.world, s.region), "redo after it");
            second.stop();
        }
        context.complete();
    }

    /**
     * A fill of three sections is cut off by a crash in its second section. The game saves the fill's chunks (through
     * the hook, which journals their changes and waits for them), then the process dies: the history folder as it is on
     * disk right then is all the next run gets (the disk writes only while the server thread waits). One
     * "(interrupted)" step holds exactly the cells written, and undoing it restores the world before the fill.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_crash_job", tickLimit = LIMIT)
    public void aJobInterruptedByACrashUndoesExactlyWhatItWrote(TestContext context) {
        try (Scene s = new Scene(context, 585)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            ServerPlayerEntity alice = h.player;
            s.area(box(x, 94, z, x + 47, 113, z + 15), 98);
            Box fill = box(x, 96, z, x + 47, 111, z + 15); // three whole sections
            WorldSnapshot original = capture(h.world, s.region);

            WritesWhileWaiting disk = new WritesWhileWaiting(Thread.currentThread());
            Run first = s.run(s.dir, 2500, disk);
            first.load(alice);
            RecordingListener job = new RecordingListener();
            try {
                first.service.run(alice, new OpSpec.Fill(fill, new Pattern.Single(h.state("minecraft:glass")),
                        CellMask.ANY), RunOptions.DEFAULT, job);
            } catch (EditRejected e) {
                throw new GameTestException("the fill was refused: " + e.getMessage());
            }
            int written = 0;
            for (int tick = 0; tick < 50 && (written = count(h, fill, Blocks.GLASS)) <= 4096; tick++) {
                first.executor.tick();
                first.service.tick();
            }
            check(written > 4096 && written < fill.volume() && job.result == null,
                    "the fill is not part way: " + written);
            // The game saves the fill's chunks (the hook journals their changes and waits), then the process dies.
            for (int cx = x >> 4; cx <= (x + 47) >> 4; cx++) first.service.beforeChunkSave(h.world, cx, z >> 4);
            Path copy = s.diskNow(s.dir);
            disk.holder = null;

            Run second = s.run(copy, 0, StorageIo.SYSTEM);
            second.load(alice);
            List<HistoryEntry> steps = second.undoEntries(alice);
            check(steps.size() == 1 && steps.get(0).label().equals("Fill (interrupted) · " + blocks(written)),
                    "steps " + labels(steps) + " for " + written + " cells written");
            check(steps.get(0).record().before().cellCount() == written, "the step holds "
                    + steps.get(0).record().before().cellCount() + " cells, " + written + " were written");
            JobResult undo = second.step(alice, true);
            check(undo.changed() == written && undo.skippedConflicts() == 0, "undo " + undo);
            checkSame(original, capture(h.world, s.region), "the interrupted fill undone");
            second.stop();
        }
        context.complete();
    }

    /**
     * A brush stroke still open at a crash (the history folder taken as it is on disk when the game saved the
     * stroke's chunks) leaves an "(interrupted)" step that undoes exactly what it raised.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_crash_stroke", tickLimit = LIMIT)
    public void anOpenStrokeInterruptedByACrashIsUndoable(TestContext context) {
        try (Scene s = new Scene(context, 586)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            ServerPlayerEntity alice = h.player;
            s.area(box(x, 90, z, x + 31, 120, z + 31), 100);
            WorldSnapshot original = capture(h.world, s.region);

            WritesWhileWaiting disk = new WritesWhileWaiting(Thread.currentThread());
            Run first = s.run(s.dir, 0, disk);
            first.load(alice);
            stroke(first, alice, x, z); // the mouse is still down
            check(EditTestSupport.difference(original, capture(h.world, s.region)) != null,
                    "the stroke changed nothing");
            for (int cx = x >> 4; cx <= (x + 31) >> 4; cx++) {
                for (int cz = z >> 4; cz <= (z + 31) >> 4; cz++) first.service.beforeChunkSave(h.world, cx, cz);
            }
            Path copy = s.diskNow(s.dir);
            disk.holder = null;

            Run second = s.run(copy, 0, StorageIo.SYSTEM);
            second.load(alice);
            List<HistoryEntry> steps = second.undoEntries(alice);
            check(steps.size() == 1 && steps.get(0).label().startsWith("Raise stroke (interrupted) · "),
                    "steps " + labels(steps));
            second.step(alice, true);
            checkSame(original, capture(h.world, s.region), "the interrupted stroke undone");
            second.stop();
        }
        context.complete();
    }

    // ---------------------------------------------------------------------------------------------- contents

    /**
     * Steps restored from disk treat changed contents as a live history does. Alice fills a row of three chests, then
     * pastes a row of block entities recorded in a file's form ({@link HistoryContentsGameTest#untouchedContents}); the
     * server restarts. Bob fills the middle chest. Undoing the paste restores its cells exactly (nothing kept: the
     * restored data of the sign, chest and furnace compares as the game holds it); undoing the fill keeps Bob's chest,
     * counted; Undo anyway removes it, and the world is as before both edits.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_contents", tickLimit = LIMIT)
    public void restoredStepsKeepChestsFilledSinceAndUndoUntouchedOnesExactly(TestContext context) {
        try (Scene s = new Scene(context, 608)) {
            Harness h = s.h;
            int x = s.x, z = s.z;
            ServerPlayerEntity alice = h.player;
            s.area(box(x, 96, z, x + 15, 107, z + 15), 100);
            WorldSnapshot original = capture(h.world, s.region);

            Run first = s.run();
            first.load(alice);
            check(first.fill(alice, box(x + 2, 101, z + 2, x + 4, 101, z + 2), "minecraft:chest[facing=south]")
                    .changed() == 3, "the chests");
            WorldSnapshot chests = capture(h.world, s.region);
            check(first.paste(alice, HistoryContentsGameTest.untouchedContents(h), pos(x + 2, 101, z + 6)).changed() == 6,
                    "the paste");
            first.stop();

            Run second = s.run();
            second.load(alice);
            check(second.undoEntries(alice).size() == 2, "restored " + labels(second.undoEntries(alice)));
            BlockPos filled = pos(x + 3, 101, z + 2);
            ((ChestBlockEntity) h.world.getBlockEntity(filled)).setStack(0, new ItemStack(Items.DIAMOND, 5));
            JobResult paste = second.step(alice, true);
            check(paste.changed() == 6 && paste.skippedConflicts() == 0, "the paste's undo " + paste);
            WorldSnapshot withBobs = capture(h.world, s.region);
            withBobs.tiles.put(filled.asLong(), chests.tiles.get(filled.asLong()));
            checkSame(chests, withBobs, "the paste undone after the restart");
            JobResult fill = second.step(alice, true);
            check(fill.changed() == 2 && fill.skippedConflicts() == 1, "the fill's undo " + fill);
            check(h.world.getBlockEntity(filled) instanceof ChestBlockEntity chest && chest.getStack(0).getCount() == 5,
                    "Bob's chest was not kept");
            check(second.undoAnyway(alice).changed() == 1, "Undo anyway");
            checkSame(original, capture(h.world, s.region), "Undo anyway after the restart");
            second.stop();
        }
        context.complete();
    }

    /**
     * Entities in steps restored from disk. Alice moves a wall with an item frame,
     * a painting, an armor stand and a block display; the server restarts. Undo puts the same entities (same UUIDs,
     * same data) back where they were and removes the moved ones, exactly; redo moves them again as the move left them.
     * Entities load a tick after their chunks, so the test waits for them before its synchronous part.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_entities", tickLimit = LIMIT)
    public void movedEntitiesUndoAndRedoExactlyAfterARestart(TestContext context) {
        Scene s = new Scene(context, 650);
        Harness h = s.h;
        int x = s.x, z = s.z;
        s.area(box(x, 96, z, x + 15, 107, z + 15), 100);
        Box source = box(x + 2, 101, z + 2, x + 5, 103, z + 5);
        Box destination = box(x + 10, 101, z + 2, x + 13, 103, z + 5);
        context.createTimedTaskRunner()
                .createAndAdd(() -> EntitiesGameTest.ready(h.world, s.region))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    try (s) {
                        movedEntitiesAfterARestart(s, source, destination);
                    }
                }))
                .completeIfSuccessful();
    }

    private static void movedEntitiesAfterARestart(Scene s, Box source, Box destination) {
        Harness h = s.h;
        ServerPlayerEntity alice = h.player;
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> before =
                EntitiesGameTest.comparable(EntitiesGameTest.decorate(h, source.min().x(), 101, source.min().z()));
        check(before.size() == 4, "the fixture: " + before.size());
        WorldSnapshot original = capture(h.world, s.region);

        Run first = s.run();
        first.load(alice);
        RecordingListener move = new RecordingListener();
        try {
            first.service.run(alice, new OpSpec.Move(new Region.Cuboid(source),
                    new dev.sculptory.core.BlockPos(8, 0, 0), Transform.IDENTITY,
                    new Pattern.Single(h.state("minecraft:air")), EntityFilter.DECORATIONS), RunOptions.DEFAULT, move);
        } catch (EditRejected e) {
            throw new GameTestException("the move was refused: " + e.getMessage());
        }
        tickUntil(first.executor, () -> move.result != null, TICKS, "the move");
        first.service.tick();
        check(move.result.outcome() == JobOutcome.COMPLETED, "the move " + move.result);
        check(EntitiesGameTest.entitiesIn(h.world, source).isEmpty(), "entities left behind");
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> moved =
                EntitiesGameTest.comparable(EntitiesGameTest.entitiesIn(h.world, destination));
        check(moved.size() == 4, "moved " + moved.size());
        WorldSnapshot afterMove = capture(h.world, s.region);
        HistorySnapshot saved = first.service.history(alice);
        // Four taken away and four placed: the step changes eight entities.
        check(saved.undoLabels().get(0).contains("8 entities"), "the step " + saved.undoLabels());
        first.stop();

        Run second = s.run();
        second.load(alice);
        HistorySnapshot restored = second.service.history(alice);
        check(restored.undoLabels().equals(saved.undoLabels()) && restored.bytes() == saved.bytes(),
                "restored " + restored + ", saved " + saved);
        check(second.step(alice, true).skippedConflicts() == 0, "the undo after the restart met conflicts");
        checkSame(original, capture(h.world, s.region), "the blocks after the undo");
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> back =
                EntitiesGameTest.comparable(EntitiesGameTest.entitiesIn(h.world, source));
        check(back.equals(before), "the entities after the undo: " + EntitiesGameTest.changedSince(before, back));
        check(EntitiesGameTest.entitiesIn(h.world, destination).isEmpty(), "the moved entities stayed");

        check(second.step(alice, false).skippedConflicts() == 0, "the redo after the restart met conflicts");
        checkSame(afterMove, capture(h.world, s.region), "the blocks after the redo");
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> again =
                EntitiesGameTest.comparable(EntitiesGameTest.entitiesIn(h.world, destination));
        check(again.equals(moved), "the entities after the redo: " + EntitiesGameTest.changedSince(moved, again));
        check(EntitiesGameTest.entitiesIn(h.world, source).isEmpty(), "the redo left entities behind");
        second.stop();
    }

    /**
     * A server stop between an undo's (or a redo's) entity stages loses and doubles nothing. Alice moves an armor stand;
     * her undo is stopped after it took the moved stand away and before it put the original back; after the restart her
     * undo puts the original back, once. Her redo is stopped the same way (after it took the original away): it counts
     * as done, and after the next restart her undo puts the original back, once.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_entities_stop", tickLimit = LIMIT)
    public void anUndoOfAMoveStoppedMidwayLosesNoEntity(TestContext context) {
        Scene s = new Scene(context, 666);
        Harness h = s.h;
        int x = s.x, z = s.z;
        s.area(box(x, 96, z, x + 31, 107, z + 15), 100);
        Box source = box(x + 2, 101, z + 2, x + 5, 103, z + 5);
        context.createTimedTaskRunner()
                .createAndAdd(() -> EntitiesGameTest.ready(h.world, s.region))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    try (s) {
                        stoppedMidway(s, source);
                    }
                }))
                .completeIfSuccessful();
    }

    private static void stoppedMidway(Scene s, Box source) {
        Harness h = s.h;
        ServerPlayerEntity alice = h.player;
        Box destination = source.offset(16, 0, 0);
        Entity stand = EntitiesGameTest.load(h.world, "minecraft:armor_stand", source.min().x() + 1.5, 101,
                source.min().z() + 1.5, 0f, nbt -> nbt.putBoolean("NoGravity", true));
        check(h.world.spawnEntity(stand), "the stand spawned");
        UUID id = stand.getUuid();

        Run first = s.run(s.dir, 64, StorageIo.SYSTEM);
        first.load(alice);
        RecordingListener move = new RecordingListener();
        try {
            first.service.run(alice, new OpSpec.Move(new Region.Cuboid(source), new dev.sculptory.core.BlockPos(16, 0,
                    0), Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air")), EntityFilter.DECORATIONS),
                    RunOptions.DEFAULT, move);
        } catch (EditRejected e) {
            throw new GameTestException("the move was refused: " + e.getMessage());
        }
        tickUntil(first.executor, () -> move.result != null, TICKS, "the move");
        first.service.tick();
        check(EntitiesGameTest.entitiesIn(h.world, destination).size() == 1, "moved");
        startStep(first, alice, true);
        tickUntil(first.executor, () -> EntitiesGameTest.entitiesIn(h.world, destination).isEmpty(), TICKS,
                "the undo taking the moved stand");
        first.stop(); // the server stops before the undo put the original back
        check(EntitiesGameTest.entitiesIn(h.world, source).isEmpty(), "put back before the stop");

        Run second = s.run(s.dir, 64, StorageIo.SYSTEM);
        second.load(alice);
        second.step(alice, true);
        Set<UUID> all = new java.util.HashSet<>();
        EntitiesGameTest.entitiesIn(h.world, s.region).forEach(e -> all.add(e.getUuid()));
        check(all.equals(Set.of(id)), "the original is back, once: " + all);

        startStep(second, alice, false);
        tickUntil(second.executor, () -> EntitiesGameTest.entitiesIn(h.world, source).isEmpty(), TICKS,
                "the redo taking the original");
        second.stop(); // the server stops before the redo placed the moved one

        Run third = s.run(s.dir, 64, StorageIo.SYSTEM);
        third.load(alice);
        check(EntitiesGameTest.entitiesIn(h.world, s.region).isEmpty(), "taken, not placed yet");
        check(!third.undoEntries(alice).isEmpty(), "the redo that changed something can be undone");
        third.step(alice, true);
        all.clear();
        EntitiesGameTest.entitiesIn(h.world, s.region).forEach(e -> all.add(e.getUuid()));
        check(all.equals(Set.of(id)), "the original is back, once: " + all);
        EntitiesGameTest.entitiesIn(h.world, s.region).forEach(Entity::discard);
        third.stop();
    }

    /** Starts an undo ({@code undo}) or redo of the player's next step, without waiting for it. */
    private static void startStep(Run run, ServerPlayerEntity player, boolean undo) {
        try {
            if (undo) {
                run.service.undo(player, ConflictPolicy.SKIP_CONFLICTS, new RecordingListener());
            } else {
                run.service.redo(player, ConflictPolicy.SKIP_CONFLICTS, new RecordingListener());
            }
        } catch (EditRejected e) {
            throw new GameTestException((undo ? "undo" : "redo") + " was refused: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------------------------------- wiring

    /**
     * The server itself saves undo history (in the GameTest server, in its temporary history folder), and the game's
     * chunk saves go through the hook that writes the journal first ({@code ServerChunkLoadingManagerMixin}).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_durable_wiring", tickLimit = LIMIT)
    public void theServerSavesHistoryAndChunkSavesGoThroughTheHook(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        EngineEditService host = EditServiceHost.find(server)
                .orElseThrow(() -> new GameTestException("the mod did not install the edit service"));
        String status = host.historyService().storageStatus();
        check(host.historyService().persistent() && status.contains(EditServiceHost.historyDir(server).toString()),
                "the server does not save undo history: " + status);
        long hooks = EditServiceHost.chunkSaveHooks();
        context.getWorld().setBlockState(context.getAbsolutePos(new BlockPos(1, 2, 1)), Blocks.STONE.getDefaultState());
        context.getWorld().getChunkManager().save(true);
        check(EditServiceHost.chunkSaveHooks() > hooks, "no chunk save went through the history hook");
        check(EditServiceHost.chunkSaveHookStatus().startsWith("chunk-save hook active"),
                EditServiceHost.chunkSaveHookStatus());
        context.complete();
    }
}
