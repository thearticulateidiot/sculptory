package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.dab;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.checkNoEditTickets;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.engine.impl.EngineEditService;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.engine.impl.ServerScatter;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import dev.sculptory.server.platform.WriteOptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.OperatorList;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.LoggerFactory;

/**
 * M4 multiplayer hardening: several players editing at once (queueing, fairness, per-player history and clipboards),
 * permissions at every entry point and lost mid-session, protection on every write path, leaving mid-job and server
 * stop with several players' jobs. Most tests drive a private executor by hand, so every step is deterministic; each
 * works in its own far region, in its own batch.
 */
public final class MultiplayerGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final BrushSpec RAISE = new BrushSpec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE, null,
            SurfaceMask.ANY, 0, 0, 1L);

    // ---------------------------------------------------------------- concurrent edits

    /**
     * Alice fills two sections; Bob's fill and his paste overlapping her second section are admitted after it. They
     * queue behind her fill on the section locks (FIFO) and write nothing while it runs, and his paste waits for his
     * fill. His brush in her area is refused AREA_BUSY while her job holds it, and he cannot paste her clipboard (nor she
     * his). Every cell ends as the last job admitted over it left it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_overlap", tickLimit = LIMIT)
    public void overlappingEditsFromTwoPlayersQueueAndNeverInterleave(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 120);
        int x = at[0], z = at[1];
        Box region = box(x, 112, z, x + 31, 127, z + 15);
        Box aliceBox = box(x, 112, z, x + 23, 127, z + 15);
        Box bobBox = box(x + 16, 112, z, x + 31, 127, z + 15);
        Box pasteBox = box(x + 20, 112, z + 4, x + 27, 119, z + 11);
        Box loaded = box(x - 16, 112, z - 16, x + 47, 127, z + 31);
        loadAndForce(world, loaded);
        WorldSnapshot original = capture(world, region);
        int stone = h.state("minecraft:stone"), gold = h.state("minecraft:gold_block");
        int diamond = h.state("minecraft:diamond_block");

        UUID aliceClipboard = h.service.clipboards().install(alice.getUuid(), cube(h, 2, "minecraft:emerald_block")).id();
        UUID bobClipboard = h.service.clipboards().install(bob.getUuid(), cube(h, 8, "minecraft:diamond_block")).id();
        check(refusal(() -> h.service.run(alice, paste(bobClipboard, pasteBox.min()), RunOptions.DEFAULT, null)).reason()
                == RejectReason.INVALID, "Alice pasted Bob's clipboard");
        check(refusal(() -> h.service.run(bob, paste(aliceClipboard, pasteBox.min()), RunOptions.DEFAULT, null)).reason()
                == RejectReason.INVALID, "Bob pasted Alice's clipboard");

        RecordingListener fillA = new RecordingListener();
        RecordingListener fillB = new RecordingListener();
        RecordingListener pasteB = new RecordingListener();
        run(h, alice, fill(h, aliceBox, "minecraft:stone"), fillA);
        run(h, bob, fill(h, bobBox, "minecraft:gold_block"), fillB);
        run(h, bob, paste(bobClipboard, pasteBox.min()), pasteB);
        check(executor.isLocked(world, bobBox), "the shared area is not locked after admission");

        // Bob's brush where Alice's fill holds the area: refused, and not queued.
        begin(h, bob, 1);
        DabOutcome busy = h.service.dabs(bob, 1, 1, List.of(dab(0, x + 8, 113, z + 8)));
        check(!busy.accepted() && busy.reason() == RejectReason.AREA_BUSY, "Bob's dab over Alice's job: " + busy);
        check(h.service.queuedDabs(bob.getUuid()) == 0, "a refused dab was queued");
        h.service.endStroke(bob, 1);

        int ticks = 0;
        while (pasteB.result == null) {
            check(++ticks <= 64, "the three jobs did not finish in 64 ticks");
            executor.tick();
            long golds = count(world, region, Blocks.GOLD_BLOCK), diamonds = count(world, region, Blocks.DIAMOND_BLOCK);
            if (fillA.result == null) {
                check(golds == 0 && diamonds == 0, "Bob wrote while Alice's fill ran (tick " + ticks + ")");
            }
            if (fillB.result == null) check(diamonds == 0, "Bob's paste wrote before his fill finished (tick " + ticks + ")");
        }
        check(ticks >= 12, "the jobs were not spread over ticks (" + ticks + "); the test proves nothing");
        check(fillA.result.outcome() == JobOutcome.COMPLETED && fillA.result.changed() == aliceBox.volume(), "Alice " + fillA.result);
        check(fillB.result.outcome() == JobOutcome.COMPLETED && fillB.result.changed() == bobBox.volume(), "Bob " + fillB.result);
        check(pasteB.result.outcome() == JobOutcome.COMPLETED && pasteB.result.changed() == pasteBox.volume(),
                "Bob's paste " + pasteB.result);
        check(fillB.count(Phase.QUEUED) == 1 && pasteB.count(Phase.QUEUED) == 1, "Bob's jobs were not reported QUEUED");
        WorldSnapshot expected = with(with(with(original, aliceBox, stone), bobBox, gold), pasteBox, diamond);
        checkSame(expected, capture(world, region), "after both players' edits");
        check(h.history().undoLabels().equals(List.of("Fill · 6,144 blocks")), "Alice's history " + h.history());
        check(h.service.history(bob).undoLabels().size() == 2, "Bob's history " + h.service.history(bob));
        check(!executor.isLocked(world, region), "locks left");
        checkNoEditTickets(executor, world, loaded);
        executor.shutdown();
        forceChunks(world, loaded, false);
        h.close();
        context.complete();
    }

    /**
     * Per-player history over shared cells. Alice fills, then Bob fills over part of it. Alice's undo (skipping
     * conflicts) restores only her cells Bob did not touch and counts the others; Bob's undo restores exactly what his
     * fill found (Alice's blocks). Redoing both, then undoing both in reverse order, returns every cell exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_history", tickLimit = LIMIT)
    public void perPlayerUndoSkipsTheOtherPlayersCellsAndBothHistoriesStayExact(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 121);
        int x = at[0], z = at[1];
        Box region = box(x, 112, z, x + 31, 127, z + 15);
        Box aliceBox = box(x, 112, z, x + 23, 127, z + 15);
        Box bobBox = box(x + 16, 112, z, x + 31, 127, z + 15);
        Box overlap = box(x + 16, 112, z, x + 23, 127, z + 15);
        loadAndForce(world, region);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        for (int dx = 0; dx < 32; dx += 3) writer.write(x + dx, 112 + dx % 16, z + dx % 16, h.state("minecraft:dirt"), null);
        WorldSnapshot original = capture(world, region);
        int stone = h.state("minecraft:stone"), gold = h.state("minecraft:gold_block");

        RecordingListener fillA = new RecordingListener();
        RecordingListener fillB = new RecordingListener();
        run(h, alice, fill(h, aliceBox, "minecraft:stone"), fillA);
        run(h, bob, fill(h, bobBox, "minecraft:gold_block"), fillB);
        tickUntil(executor, () -> fillB.result != null, 8, "the two fills");
        WorldSnapshot both = with(with(original, aliceBox, stone), bobBox, gold);
        checkSame(both, capture(world, region), "after both fills");

        RecordingListener undoA = historyStep(h, alice, true);
        tickUntil(executor, () -> undoA.result != null, 8, "Alice's undo");
        check(undoA.result.outcome() == JobOutcome.COMPLETED && undoA.result.changed() == aliceBox.volume() - overlap.volume()
                && undoA.result.skippedConflicts() == overlap.volume(), "Alice's undo " + undoA.result);
        checkSame(with(original, bobBox, gold), capture(world, region), "Alice's undo kept Bob's cells");

        RecordingListener undoB = historyStep(h, bob, true);
        tickUntil(executor, () -> undoB.result != null, 8, "Bob's undo");
        check(undoB.result.changed() == bobBox.volume() && undoB.result.skippedConflicts() == 0, "Bob's undo " + undoB.result);
        checkSame(with(original, overlap, stone), capture(world, region), "Bob's undo restores what his fill found");

        RecordingListener redoA = historyStep(h, alice, false);
        tickUntil(executor, () -> redoA.result != null, 8, "Alice's redo");
        // Her cells under the overlap already hold what her redo writes: nothing to do there, and no conflict.
        check(redoA.result.changed() == aliceBox.volume() - overlap.volume() && redoA.result.skippedConflicts() == 0,
                "Alice's redo " + redoA.result);
        RecordingListener redoB = historyStep(h, bob, false);
        tickUntil(executor, () -> redoB.result != null, 8, "Bob's redo");
        check(redoB.result.changed() == bobBox.volume() && redoB.result.skippedConflicts() == 0, "Bob's redo " + redoB.result);
        checkSame(both, capture(world, region), "after both redos");

        // In reverse order the undos meet no conflict and restore everything.
        RecordingListener undoB2 = historyStep(h, bob, true);
        tickUntil(executor, () -> undoB2.result != null, 8, "Bob's second undo");
        RecordingListener undoA2 = historyStep(h, alice, true);
        tickUntil(executor, () -> undoA2.result != null, 8, "Alice's second undo");
        check(undoB2.result.skippedConflicts() == 0 && undoA2.result.skippedConflicts() == 0
                && undoA2.result.changed() == aliceBox.volume(), "second undos " + undoB2.result + " / " + undoA2.result);
        checkSame(original, capture(world, region), "after undoing both in reverse order");
        check(h.history().redoLabels().size() == 1 && h.service.history(bob).redoLabels().size() == 1,
                "each player keeps exactly their own redo");
        executor.shutdown();
        forceChunks(world, region, false);
        h.close();
        context.complete();
    }

    /**
     * Fairness. Alice's 1,048,576-block fill runs over hundreds of ticks; Bob's small fill admitted after it finishes
     * within a few ticks (the bulk lane shares the budget round robin). Alice may have at most 8 jobs waiting to start:
     * the ninth is refused QUEUE_FULL while Bob's next job is still admitted and done promptly. Cancelling Alice's jobs
     * ends the waiting ones at once and the fill at a section boundary; undoing that partial fill restores it exactly,
     * and Bob's cells are left alone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_fairness", tickLimit = LIMIT)
    public void aSmallEditIsNotStarvedByAnotherPlayersMillionBlockJob(TestContext context) {
        EditExecutor executor = executor(context, 4096);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 122);
        int x = at[0], z = at[1];
        Box big = box(x, 112, z, x + 127, 175, z + 127);
        Box small1 = box(x + 160, 112, z, x + 167, 119, z + 7);
        Box small2 = box(x + 160, 120, z, x + 167, 127, z + 7);
        Box all = box(x, 112, z, x + 175, 175, z + 127);
        loadAndForce(world, all);
        WorldSnapshot bigBefore = capture(world, big);

        RecordingListener million = new RecordingListener();
        JobTicket ticket = run(h, alice, fill(h, big, "minecraft:stone"), million);
        check(ticket.estimatedCells() == 1_048_576, "estimate " + ticket.estimatedCells());
        executor.tick();
        check(million.result == null && million.phases.contains(Phase.APPLY), "Alice's fill is not running");

        RecordingListener first = new RecordingListener();
        run(h, bob, fill(h, small1, "minecraft:gold_block"), first);
        int ticks = ticksUntil(executor, () -> first.result != null, 10);
        check(ticks <= 3, "Bob's 512 blocks took " + ticks + " ticks next to Alice's fill");
        check(first.result.outcome() == JobOutcome.COMPLETED && million.result == null, "Bob " + first.result);

        // Alice floods: 8 jobs may wait (these wait for her fill's locks); the ninth is refused, and nobody else is.
        int maxWaiting = executor.settings().maxQueuedPerOwner();
        check(maxWaiting == 8, "per-player wait cap " + maxWaiting);
        for (int i = 0; i < maxWaiting; i++) {
            run(h, alice, fill(h, box(x + i, 170, z, x + i, 170, z), "minecraft:dirt"), null);
        }
        EditRejected full = refusal(() -> h.service.run(alice, fill(h, box(x + 9, 170, z, x + 9, 170, z), "minecraft:dirt"),
                RunOptions.DEFAULT, null));
        check(full.reason() == RejectReason.QUEUE_FULL && full.getMessage().contains("8 of your edits"), "9th: " + full);
        check(executor.queuedJobCount() == maxWaiting, "queued " + executor.queuedJobCount());
        RecordingListener second = new RecordingListener();
        run(h, bob, fill(h, small2, "minecraft:gold_block"), second);
        ticks = ticksUntil(executor, () -> second.result != null, 10);
        check(ticks <= 3 && million.result == null, "Bob's second fill took " + ticks + " ticks behind Alice's flood");

        check(h.service.cancelAll(alice) == maxWaiting + 1, "cancelled");
        check(executor.queuedJobCount() == 0, "Alice's waiting jobs were not ended at once");
        tickUntil(executor, () -> million.result != null, 4, "the cancelled fill");
        long partial = million.result.changed();
        check(million.result.outcome() == JobOutcome.CANCELLED && partial > 0 && partial % 4096 == 0
                && partial < big.volume(), "Alice's fill " + million.result);
        HistorySnapshot history = h.history();
        check(history.undoLabels().size() == 1 && history.undoLabel().startsWith("Fill (cancelled)"), "history " + history);

        RecordingListener undo = historyStep(h, alice, true);
        tickUntil(executor, () -> undo.result != null, 400, "Alice's undo");
        check(undo.result.changed() == partial && undo.result.skippedConflicts() == 0, "undo " + undo.result);
        checkSame(bigBefore, capture(world, big), "Alice's area after undoing the cut-short fill");
        check(count(world, small1, Blocks.GOLD_BLOCK) == 512 && count(world, small2, Blocks.GOLD_BLOCK) == 512,
                "Alice's undo touched Bob's cells");
        check(h.service.history(bob).undoLabels().equals(List.of("Fill · 512 blocks", "Fill · 512 blocks")),
                "Bob's history " + h.service.history(bob));
        checkNoEditTickets(executor, world, all);
        executor.shutdown();
        forceChunks(world, all, false);
        h.close();
        context.complete();
    }

    // ---------------------------------------------------------------- permissions

    /**
     * Every server entry point checks its node before any work, and the op-level fallback is exactly level 2: a player
     * without nodes (or a level-1 op) is refused everywhere with {@code sculptory.use}; a player with only
     * {@code use} is refused each operation with that operation's node (undo and redo need only {@code use}); partial
     * grants are refused exactly at the missing node; and a permissions mod denying {@code region} to an op beats the
     * op fallback for that node only. The masks sent to clients say the same. Nothing refused leaves a job, a lease, a
     * preview or a stroke behind.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_nodes", tickLimit = LIMIT)
    public void everyEntryPointChecksItsNodeWithTheOpLevelFallback(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        ServerScatter scatter = new ServerScatter(h.service);
        MinecraftServer server = h.world.getServer();
        ServerPlayerEntity nobody = h.addPlayer(false);
        ServerPlayerEntity levelOne = h.addPlayer(false);
        server.getPlayerManager().getOpList().add(new OperatorEntry(levelOne.getGameProfile(), 1, false));
        ServerPlayerEntity user = h.addPlayer(false);
        EditTestSupport.grant(user, Perm.USE);
        ServerPlayerEntity clipper = h.addPlayer(false);
        EditTestSupport.grant(clipper, Perm.USE, Perm.CLIPBOARD);
        ServerPlayerEntity regionOnly = h.addPlayer(false);
        EditTestSupport.grant(regionOnly, Perm.USE, Perm.REGION);
        ServerPlayerEntity deniedOp = h.addPlayer();
        EditTestSupport.deny(deniedOp, Perm.REGION);

        int[] at = regionCorner(context, 123);
        int x = at[0], z = at[1];
        Box box = box(x, 112, z, x + 7, 119, z + 7);
        loadAndForce(h.world, box);
        BlockPos origin = box.min();
        Pattern stone = new Pattern.Single(h.state("minecraft:stone"));
        UUID unknown = UUID.randomUUID();

        Map<String, Attempt> entries = new LinkedHashMap<>();
        entries.put("fill", p -> h.service.run(p, new OpSpec.Fill(box, stone, CellMask.ANY), RunOptions.DEFAULT, null));
        entries.put("replace", p -> h.service.run(p, new OpSpec.Replace(box, CellMask.ANY, stone), RunOptions.DEFAULT, null));
        entries.put("erase", p -> h.service.run(p, new OpSpec.Erase(box, CellMask.ANY), RunOptions.DEFAULT, null));
        entries.put("hollow", p -> h.service.run(p, new OpSpec.Hollow(box, 1, stone), RunOptions.DEFAULT, null));
        entries.put("walls", p -> h.service.run(p, new OpSpec.Walls(box, 1, stone), RunOptions.DEFAULT, null));
        entries.put("move", p -> h.service.run(p, new OpSpec.Move(box, new BlockPos(1, 0, 0), Transform.IDENTITY,
                new Pattern.Single(h.state("minecraft:air"))), RunOptions.DEFAULT, null));
        entries.put("stack", p -> h.service.run(p, new OpSpec.Stack(box, 8, 0, 0, 2), RunOptions.DEFAULT, null));
        entries.put("paste", p -> h.service.run(p, paste(unknown, origin), RunOptions.DEFAULT, null));
        entries.put("scatter commit", p -> h.service.run(p, new OpSpec.ScatterCommit(unknown), RunOptions.DEFAULT, null));
        entries.put("undo", p -> h.service.undo(p, ConflictPolicy.SKIP_CONFLICTS));
        entries.put("redo", p -> h.service.redo(p, ConflictPolicy.SKIP_CONFLICTS));
        entries.put("brush stroke", p -> h.service.beginStroke(p, 1, RAISE));
        entries.put("copy", p -> clips.copy(p, box, origin, false, CellMask.ANY, null, new Captured<>()));
        entries.put("cut", p -> clips.copy(p, box, origin, true, CellMask.ANY, null, new Captured<>()));
        entries.put("clipboard preview", p -> clips.preview(p, new SourceRef.Clipboard(unknown), new Captured<>()));
        entries.put("schematic export", p -> clips.export(p, unknown, new Captured<>()));
        entries.put("schematic import", p -> clips.beginUpload(p, "upload.schem", 100));
        entries.put("library list", p -> clips.list(p, "", new Captured<>()));
        entries.put("library load", p -> clips.load(p, "shared/tree.schem", new Captured<>()));
        entries.put("save asset", p -> clips.save(p, unknown, "shared/tree.schem", new Captured<>()));
        entries.put("scatter preview", p -> scatter.preview(p, ScatterGameTest.request(
                ScatterGameTest.region(x, z, x + 15, z + 15), 4, 1L, new SourceRef.Clipboard(unknown)),
                new ScatterGameTest.Reply()));

        // No node, or op level 1 (below the fallback level 2): refused everywhere at sculptory.use.
        for (ServerPlayerEntity p : List.of(nobody, levelOne)) {
            for (Map.Entry<String, Attempt> entry : entries.entrySet()) {
                expect(p, entry.getKey(), entry.getValue(), RejectReason.NO_PERMISSION, Perm.USE);
            }
        }
        // Only use: each operation is refused at its own node; undo and redo need nothing more.
        Map<String, Perm> needs = new LinkedHashMap<>();
        for (String op : List.of("fill", "replace", "erase", "hollow", "walls", "move", "stack")) needs.put(op, Perm.REGION);
        needs.put("paste", Perm.CLIPBOARD);
        needs.put("scatter commit", Perm.SCATTER);
        needs.put("brush stroke", Perm.BRUSH);
        for (String op : List.of("copy", "cut", "clipboard preview", "schematic export", "schematic import", "library list",
                "library load", "save asset")) {
            needs.put(op, Perm.CLIPBOARD);
        }
        needs.put("scatter preview", Perm.SCATTER);
        for (Map.Entry<String, Perm> need : needs.entrySet()) {
            expect(user, need.getKey(), entries.get(need.getKey()), RejectReason.NO_PERMISSION, need.getValue());
        }
        expect(user, "undo", entries.get("undo"), RejectReason.HISTORY_EMPTY, null);
        expect(user, "redo", entries.get("redo"), RejectReason.HISTORY_EMPTY, null);

        // Partial grants are refused exactly at the missing node (or pass it and fail on the unknown id).
        expect(clipper, "cut", entries.get("cut"), RejectReason.NO_PERMISSION, Perm.REGION);
        expect(clipper, "schematic export", entries.get("schematic export"), RejectReason.NO_PERMISSION, Perm.SCHEMATIC_EXPORT);
        expect(clipper, "schematic import", entries.get("schematic import"), RejectReason.NO_PERMISSION, Perm.SCHEMATIC_IMPORT);
        expect(clipper, "fill", entries.get("fill"), RejectReason.NO_PERMISSION, Perm.REGION);
        expect(clipper, "paste", entries.get("paste"), RejectReason.INVALID, null);
        expect(clipper, "clipboard preview", entries.get("clipboard preview"), RejectReason.INVALID, null);
        expect(clipper, "save asset", entries.get("save asset"), RejectReason.INVALID, null);
        expect(regionOnly, "physics fill", p -> h.service.run(p, new OpSpec.Fill(box, stone, CellMask.ANY),
                new RunOptions(true, ConflictPolicy.SKIP_CONFLICTS), null), RejectReason.NO_PERMISSION, Perm.PHYSICS);
        expect(regionOnly, "physics paste", p -> h.service.run(p, new OpSpec.Paste(new SourceRef.Clipboard(unknown), origin,
                Transform.IDENTITY, new PasteOptions(false, true)), RunOptions.DEFAULT, null), RejectReason.NO_PERMISSION,
                Perm.CLIPBOARD);
        expect(regionOnly, "brush stroke", entries.get("brush stroke"), RejectReason.NO_PERMISSION, Perm.BRUSH);

        // An op whom a permissions mod denies region: refused there, and only there.
        for (String op : List.of("fill", "replace", "erase", "hollow", "walls", "move", "stack", "cut")) {
            expect(deniedOp, op, entries.get(op), RejectReason.NO_PERMISSION, Perm.REGION);
        }
        expect(deniedOp, "paste", entries.get("paste"), RejectReason.INVALID, null);
        expect(deniedOp, "undo", entries.get("undo"), RejectReason.HISTORY_EMPTY, null);
        try {
            h.service.beginStroke(deniedOp, 5, RAISE);
        } catch (EditRejected e) {
            throw new GameTestException("the op without region may not brush: " + e.getMessage());
        }
        h.service.endStroke(deniedOp, 5);

        // The masks sent in Welcome / PermissionsChanged say the same.
        var permissions = h.runtime.permissions();
        check(permissions.mask(nobody).bits() == 0 && permissions.mask(levelOne).bits() == 0, "no-node masks");
        check(Perm.granted(permissions.mask(user)).equals(EnumSet.of(Perm.USE)), "use-only mask");
        check(Perm.granted(permissions.mask(clipper)).equals(EnumSet.of(Perm.USE, Perm.CLIPBOARD)), "clipboard mask");
        check(Perm.granted(permissions.mask(h.player)).equals(EnumSet.allOf(Perm.class)), "a level-2 op holds every node");
        Set<Perm> allButRegion = EnumSet.allOf(Perm.class);
        allButRegion.remove(Perm.REGION);
        check(Perm.granted(permissions.mask(deniedOp)).equals(allButRegion), "the denied op's mask "
                + Perm.granted(permissions.mask(deniedOp)));

        // Nothing refused left anything behind.
        check(executor.queuedJobCount() == 0 && executor.activeJobCount() == 0 && executor.holdCount() == 0,
                "a refused request left a job or hold");
        for (ServerPlayerEntity p : List.of(nobody, levelOne, user, clipper, regionOnly, deniedOp)) {
            check(clips.requests(p.getUuid()) == 0, "a refused request left a lease");
            check(!scatter.planning(p.getUuid()), "a refused preview is planning");
            check(h.service.openStroke(p.getUuid()).isEmpty(), "a refused stroke is open");
        }
        executor.shutdown();
        clips.shutdown();
        ClipTestSupport.deleteTree(root.getParent());
        forceChunks(h.world, box, false);
        h.close();
        context.complete();
    }

    /**
     * A permissions mod takes {@code region} away mid-session (use stays). The builder's queued job ends at once and
     * changes nothing; the running one stops at its next section boundary, keeping that whole section, which stays in
     * history and undoes exactly (undo needs only use). New region requests are refused; another player's job is not
     * affected. A scatter preview in flight ends NO_PERMISSION and releases its area when scatter goes, and the
     * periodic re-checks catch a removal without an op change.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_revoke", tickLimit = LIMIT)
    public void removedNodesCancelTheJobsAndPreviewsThatNeedThem(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.SCATTER);
        ServerPlayerEntity other = h.player;
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 124);
        int x = at[0], z = at[1];
        Box region = box(x, 112, z, x + 31, 127, z + 15);
        Box otherBox = box(x + 64, 112, z, x + 71, 119, z + 7);
        loadAndForce(world, box(x, 112, z, x + 79, 127, z + 15));
        WorldSnapshot before = capture(world, region);

        RecordingListener running = new RecordingListener();
        RecordingListener queued = new RecordingListener();
        RecordingListener others = new RecordingListener();
        run(h, builder, fill(h, region, "minecraft:stone"), running);
        executor.tick();
        check(running.result == null && count(world, region, Blocks.STONE) == 1024, "the fill is not a quarter through");
        run(h, builder, fill(h, region, "minecraft:dirt"), queued);
        run(h, other, fill(h, otherBox, "minecraft:gold_block"), others);

        EditTestSupport.grant(builder, Perm.USE, Perm.SCATTER);
        check(h.service.revalidate(builder) == 2, "both of the builder's jobs should be cancelled");
        check(queued.result != null && queued.result.outcome() == JobOutcome.CANCELLED && queued.result.changed() == 0,
                "the queued job " + queued.result);
        check(running.result == null, "the running job stopped mid-section");
        check(h.service.revalidate(other) == 0, "the other player lost a job");
        tickUntil(executor, () -> running.result != null && others.result != null, 16, "the jobs");
        check(running.result.outcome() == JobOutcome.CANCELLED && running.result.changed() == 4096,
                "the running job did not stop at its section boundary: " + running.result);
        check(others.result.outcome() == JobOutcome.COMPLETED && others.result.changed() == otherBox.volume(),
                "the other player's job " + others.result);
        check(h.service.revalidate(builder) == 0, "cancelled twice");
        EditRejected refused = refusal(() -> h.service.run(builder, fill(h, region, "minecraft:dirt"), RunOptions.DEFAULT,
                null));
        check(refused.reason() == RejectReason.NO_PERMISSION && refused.getMessage().contains(Perm.REGION.node()),
                "a new fill: " + refused);
        HistorySnapshot history = h.service.history(builder);
        check(history.undoLabels().equals(List.of("Fill (cancelled) · 4,096 blocks")), "history " + history);
        RecordingListener undo = historyStep(h, builder, true);
        tickUntil(executor, () -> undo.result != null, 16, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.changed() == 4096, "undo " + undo.result);
        checkSame(before, capture(world, region), "after undoing the cut-short fill");

        // A preview in flight: ended at once by the permission hook, or by the next sweep.
        ServerScatter scatter = new ServerScatter(h.service);
        int sx = x + 256;
        Box floor = ScatterGameTest.floor(h, sx, z, 16, 16);
        SourceRef log = ScatterGameTest.block(h, builder, "minecraft:oak_log");
        ScatterGameTest.Reply first = ScatterGameTest.preview(scatter, builder,
                ScatterGameTest.request(ScatterGameTest.region(sx, z, sx + 15, z + 15), 4, 1L, log));
        check(executor.holdCount() == 1, "the preview holds nothing");
        EditTestSupport.grant(builder, Perm.USE);
        check(scatter.revalidate(builder), "the preview was not ended");
        check(first.reason == RejectReason.NO_PERMISSION && first.calls == 1, "answered " + first.reason);
        check(executor.holdCount() == 0 && !scatter.planning(builder.getUuid()), "the preview's area is still held");

        EditTestSupport.grant(builder, Perm.USE, Perm.SCATTER, Perm.REGION);
        ScatterGameTest.Reply second = ScatterGameTest.preview(scatter, builder,
                ScatterGameTest.request(ScatterGameTest.region(sx, z, sx + 15, z + 15), 4, 2L, log));
        RecordingListener late = new RecordingListener();
        run(h, builder, fill(h, region, "minecraft:dirt"), late);
        EditTestSupport.grant(builder, Perm.USE);
        for (int i = 1; i < EngineEditService.PERMISSION_RECHECK_TICKS; i++) h.service.tick();
        check(late.result == null, "cancelled before the periodic re-check");
        h.service.tick();
        check(late.result != null && late.result.outcome() == JobOutcome.CANCELLED && late.result.changed() == 0,
                "the periodic re-check did not cancel the job: " + late.result);
        for (int i = 0; i < 20 && !second.finished(); i++) scatter.housekeeping();
        check(second.reason == RejectReason.NO_PERMISSION, "the sweep did not end the preview: " + second.reason);
        check(executor.holdCount() == 0, "the swept preview's area is still held");
        executor.shutdown();
        forceChunks(world, box(x, 112, z, x + 79, 127, z + 15), false);
        forceChunks(world, floor, false);
        h.close();
        context.complete();
    }

    /**
     * {@code /deop} end to end on the server's own edit service: {@code PlayerManager.removeFromOperators} (what
     * {@code /deop} calls) cancels the player's job at once through {@code PlayerManagerMixin}, ends their scatter
     * preview, and new requests and undo are refused.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_deop", tickLimit = LIMIT)
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void deopCancelsThePlayersJobsAtOnce(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        EngineEditService host = EditServiceHost.find(server)
                .orElseThrow(() -> new GameTestException("the mod did not install the edit service"));
        ServerScatter hostScatter = EditServiceHost.findScatter(server)
                .orElseThrow(() -> new GameTestException("the mod did not install the scatter service"));
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        UUID id = player.getUuid();
        OperatorList ops = server.getPlayerManager().getOpList();
        ops.add(new OperatorEntry(player.getGameProfile(), 2, false));
        try {
            int[] at = regionCorner(context, 125);
            int x = at[0], z = at[1];
            Box far = box(x, 112, z, x + 15, 127, z + 15);
            OpSpec.Fill fill = new OpSpec.Fill(far, new Pattern.Single(EngineTestSupport.handle(host.runtime().states(),
                    "minecraft:stone")), CellMask.ANY);
            RecordingListener job = new RecordingListener();
            try {
                host.run(player, fill, RunOptions.DEFAULT, job); // starts on a later tick
            } catch (EditRejected e) {
                throw new GameTestException("the op's fill was refused: " + e.getMessage());
            }
            Clipboard one = Clipboard.builder(host.runtime().states(), new BlockPos(1, 1, 1))
                    .set(0, 0, 0, EngineTestSupport.handle(host.runtime().states(), "minecraft:oak_log")).build();
            SourceRef log = new SourceRef.Clipboard(host.clipboards().install(id, one).id());
            ScatterGameTest.Reply preview = ScatterGameTest.preview(hostScatter, player,
                    ScatterGameTest.request(ScatterGameTest.region(x + 64, z, x + 79, z + 15), 4, 1L, log));
            check(job.result == null && host.jobs(id).size() == 1 && hostScatter.planning(id), "nothing in flight");

            server.getPlayerManager().removeFromOperators(player.getGameProfile()); // what /deop calls
            check(job.result != null && job.result.outcome() == JobOutcome.CANCELLED && job.result.changed() == 0,
                    "the deop did not cancel the job: " + job.result);
            check(host.jobs(id).isEmpty(), "the job is still listed");
            check(preview.reason == RejectReason.NO_PERMISSION && !hostScatter.planning(id),
                    "the deop did not end the preview: " + preview.reason);
            check(refusal(() -> host.run(player, fill, RunOptions.DEFAULT, null)).reason() == RejectReason.NO_PERMISSION,
                    "a fill after the deop");
            check(refusal(() -> host.undo(player, ConflictPolicy.SKIP_CONFLICTS)).reason() == RejectReason.NO_PERMISSION,
                    "an undo after the deop");
        } finally {
            ops.remove(player.getGameProfile());
            hostScatter.playerLeft(id);
            host.playerLeft(id);
            try {
                server.getPlayerManager().remove(player);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
        }
        context.complete();
    }

    /**
     * Losing {@code use} in the middle of an undo: the undo stops at its section boundary, its entry stays the undo
     * candidate (nothing moves to redo), and further undos are refused. With {@code use} back, undoing again finishes
     * the rest, meeting no conflict, and the area is exactly as before the fill.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_revoke_undo", tickLimit = LIMIT)
    public void anUndoCutShortByAPermissionLossFinishesExactlyLater(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 131);
        int x = at[0], z = at[1];
        Box region = box(x, 112, z, x + 31, 127, z + 15);
        loadAndForce(world, region);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        for (int dx = 0; dx < 32; dx += 5) writer.write(x + dx, 112 + dx % 16, z + 3, h.state("minecraft:dirt"), null);
        WorldSnapshot before = capture(world, region);
        RecordingListener fill = new RecordingListener();
        run(h, builder, fill(h, region, "minecraft:stone"), fill);
        tickUntil(executor, () -> fill.result != null, 16, "the fill");
        RecordingListener undo = historyStep(h, builder, true);
        executor.tick();
        check(undo.result == null && undo.phases.contains(Phase.APPLY), "the undo is not a quarter through");

        EditTestSupport.grant(builder);
        check(h.service.revalidate(builder) == 1, "the undo was not cancelled");
        tickUntil(executor, () -> undo.result != null, 8, "the cancelled undo");
        check(undo.result.outcome() == JobOutcome.CANCELLED && undo.result.changed() == 4096, "undo " + undo.result);
        HistorySnapshot history = h.service.history(builder);
        check(history.undoLabels().equals(List.of("Fill · 8,192 blocks")) && !history.canRedo(),
                "the cut-short undo moved its entry: " + history);
        check(refusal(() -> h.service.undo(builder, ConflictPolicy.SKIP_CONFLICTS)).reason() == RejectReason.NO_PERMISSION,
                "an undo without use");

        EditTestSupport.grant(builder, Perm.USE);
        RecordingListener again = historyStep(h, builder, true);
        tickUntil(executor, () -> again.result != null, 16, "the second undo");
        check(again.result.outcome() == JobOutcome.COMPLETED && again.result.changed() == 4096
                && again.result.skippedConflicts() == 0, "second undo " + again.result);
        checkSame(before, capture(world, region), "after finishing the undo");
        check(h.service.history(builder).redoLabels().equals(List.of("Fill · 8,192 blocks")), "history");
        executor.shutdown();
        forceChunks(world, region, false);
        h.close();
        context.complete();
    }

    /**
     * A permissions mod whose checks throw: the periodic re-checks (edit service and scatter sweep, both run from the
     * server tick) neither let the exception escape nor cancel anything; the work goes on once checks work again.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_broken_perms", tickLimit = LIMIT)
    public void aThrowingPermissionCheckCancelsNothingAndNeverEscapesTheTick(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.SCATTER);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 130);
        int x = at[0], z = at[1];
        Box region = box(x, 112, z, x + 15, 127, z + 15);
        loadAndForce(h.world, region);
        Box floor = ScatterGameTest.floor(h, x + 128, z, 16, 16);
        RecordingListener fill = new RecordingListener();
        run(h, builder, fill(h, region, "minecraft:stone"), fill);
        ScatterGameTest.Reply preview = ScatterGameTest.preview(scatter, builder, ScatterGameTest.request(
                ScatterGameTest.region(x + 128, z, x + 143, z + 15), 4, 1L,
                ScatterGameTest.block(h, builder, "minecraft:oak_log")));
        EditTestSupport.failPermissionChecks(builder, true);
        try {
            int before = EditTestSupport.failedChecks(builder);
            for (int i = 0; i < EngineEditService.PERMISSION_RECHECK_TICKS; i++) h.service.tick();
            check(EditTestSupport.failedChecks(builder) > before, "the periodic re-check did not run the check");
            before = EditTestSupport.failedChecks(builder);
            for (int i = 0; i < 20; i++) scatter.housekeeping();
            check(EditTestSupport.failedChecks(builder) > before, "the scatter sweep did not run the check");
            // New requests meanwhile: a failed check grants nothing, so they are refused NO_PERMISSION.
            expect(builder, "fill", p -> h.service.run(p, fill(h, box(x, 130, z, x, 130, z), "minecraft:dirt"),
                    RunOptions.DEFAULT, null), RejectReason.NO_PERMISSION, Perm.USE);
            expect(builder, "undo", p -> h.service.undo(p, ConflictPolicy.SKIP_CONFLICTS), RejectReason.NO_PERMISSION,
                    Perm.USE);
            expect(builder, "stroke", p -> h.service.beginStroke(p, 9, RAISE), RejectReason.NO_PERMISSION, Perm.USE);
            expect(builder, "scatter preview", p -> scatter.preview(p, ScatterGameTest.request(
                    ScatterGameTest.region(x + 128, z, x + 143, z + 15), 4, 2L, new SourceRef.Clipboard(UUID.randomUUID())),
                    new ScatterGameTest.Reply()), RejectReason.NO_PERMISSION, Perm.USE);
        } catch (RuntimeException e) {
            throw new GameTestException("a failing permission check escaped: " + e);
        } finally {
            EditTestSupport.failPermissionChecks(builder, false);
        }
        check(fill.result == null && !preview.finished() && scatter.planning(builder.getUuid()),
                "a failing permission check cancelled work");
        tickUntil(executor, () -> fill.result != null, 16, "the fill");
        check(fill.result.outcome() == JobOutcome.COMPLETED && fill.result.changed() == region.volume(), "fill " + fill.result);
        scatter.shutdown();
        check(executor.holdCount() == 0, "the preview's area is still held");
        executor.shutdown();
        forceChunks(h.world, region, false);
        forceChunks(h.world, floor, false);
        h.close();
        context.complete();
    }

    /**
     * The rights a job's admission used are needed for as long as it runs: {@code limit.bypass} for a fill over
     * {@code maxOpVolume}, {@code edit.unloaded} for a job over unloaded chunks, the operator-NBT right for a paste of untrusted
     * operator-NBT tiles it was allowed to keep. Losing one cancels exactly the jobs that used it; a small fill over loaded
     * chunks (which carries no block entities) survives losing all three.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_admission_rights", tickLimit = LIMIT)
    public void losingARightTheAdmissionUsedCancelsTheJobsThatUsedIt(TestContext context) {
        EditExecutor executor = executor(context, 0); // never ticked: every job stays waiting
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity builder = h.addPlayer(false);
        Perm[] all = {Perm.USE, Perm.REGION, Perm.CLIPBOARD, Perm.LIMIT_BYPASS, Perm.EDIT_UNLOADED, Perm.NBT_OPERATOR};
        EditTestSupport.grant(builder, all);
        check(h.runtime.permissions().mayWriteOperatorNbt(builder) && !builder.isCreativeLevelTwoOp(), "operator NBT right");
        int[] near = regionCorner(context, 132);
        int[] far = regionCorner(context, 133);
        int[] far2 = regionCorner(context, 134);
        Box loaded = box(near[0], 112, near[1], near[0] + 15, 127, near[1] + 15);
        loadAndForce(h.world, loaded);
        check(!dev.sculptory.fabric.world.WorldChecks.isChunkLoaded(h.world, far[0] >> 4, far[1] >> 4)
                && !dev.sculptory.fabric.world.WorldChecks.isChunkLoaded(h.world, far2[0] >> 4, far2[1] >> 4),
                "the far regions are loaded");
        long limit = h.runtime.config().limits.maxOpVolume;
        Box huge = box(far[0], 0, far[1], far[0] + 128, 127, far[1] + 127); // 129 × 128 × 128
        check(huge.volume() > limit, "the huge fill is not over the limit");
        UUID clipboard = h.service.clipboards().install(builder.getUuid(), commandBlockClipboard(h)).id();

        RecordingListener overLimit = new RecordingListener();
        RecordingListener unloaded = new RecordingListener();
        RecordingListener pasted = new RecordingListener();
        RecordingListener plain = new RecordingListener();
        run(h, builder, fill(h, huge, "minecraft:stone"), overLimit);
        run(h, builder, fill(h, box(far2[0], 112, far2[1], far2[0] + 3, 115, far2[1] + 3), "minecraft:stone"), unloaded);
        run(h, builder, paste(clipboard, new BlockPos(near[0], 120, near[1])), pasted);
        run(h, builder, fill(h, box(near[0], 112, near[1], near[0] + 3, 115, near[1] + 3), "minecraft:stone"), plain);
        check(h.service.revalidate(builder) == 0, "a job was cancelled with every right held");

        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.CLIPBOARD, Perm.EDIT_UNLOADED, Perm.NBT_OPERATOR);
        check(h.service.revalidate(builder) == 1 && overLimit.result != null && unloaded.result == null,
                "losing limit.bypass: " + overLimit.result + " / " + unloaded.result);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.CLIPBOARD, Perm.NBT_OPERATOR);
        check(h.service.revalidate(builder) == 1 && unloaded.result != null && pasted.result == null,
                "losing edit.unloaded: " + unloaded.result + " / " + pasted.result);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.CLIPBOARD);
        check(!h.runtime.permissions().mayWriteOperatorNbt(builder), "the operator NBT right is still held");
        check(h.service.revalidate(builder) == 1 && pasted.result != null && plain.result == null,
                "losing the operator NBT right: " + pasted.result + " / " + plain.result);
        for (RecordingListener l : List.of(overLimit, unloaded, pasted)) {
            check(l.result.outcome() == JobOutcome.CANCELLED && l.result.changed() == 0, "cancelled job " + l.result);
        }
        check(h.service.jobs(builder.getUuid()).size() == 1, "the plain fill was cancelled");
        executor.shutdown();
        forceChunks(h.world, loaded, false);
        h.close();
        context.complete();
    }

    /**
     * World-scoped grants (a permissions mod granting region in one world only): the periodic re-check skips jobs in a
     * world other than the player's, so walking into the nether does not cancel a fill they started in the overworld;
     * an op or deop re-check still covers every world.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_world_contexts", tickLimit = LIMIT)
    public void thePeriodicRecheckLeavesJobsInOtherWorldsAlone(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        int[] at = regionCorner(context, 135);
        Box region = box(at[0], 112, at[1], at[0] + 15, 143, at[1] + 15); // two sections
        loadAndForce(h.world, region);
        RecordingListener fill = new RecordingListener();
        run(h, builder, fill(h, region, "minecraft:stone"), fill);
        executor.tick();
        check(fill.result == null && fill.phases.contains(Phase.APPLY), "the fill is not running");
        ServerWorld nether = h.world.getServer().getWorld(net.minecraft.world.World.NETHER);
        check(nether != null && builder.teleport(nether, 0.5, 100, 0.5, Set.of(), 0, 0), "teleport failed");
        EditTestSupport.grant(builder, Perm.USE); // region only applies in the overworld now
        for (int i = 0; i < 3 * EngineEditService.PERMISSION_RECHECK_TICKS; i++) h.service.tick();
        EditServiceHost.playerLeft(h.service, null, builder); // leaving from the nether: the same rule
        check(fill.result == null && h.service.jobs(builder.getUuid()).size() == 1,
                "a re-check from another world cancelled the job: " + fill.result);
        check(h.service.revalidate(builder) == 1, "the op/deop re-check skipped the job in another world");
        tickUntil(executor, () -> fill.result != null, 8, "the cancelled fill");
        check(fill.result.outcome() == JobOutcome.CANCELLED && fill.result.changed() == 4096, "the fill " + fill.result);
        executor.shutdown();
        forceChunks(h.world, region, false);
        h.close();
        context.complete();
    }

    /**
     * Leaving (the DISCONNECT hook): the player's waiting jobs are cancelled at once (they changed nothing), their
     * running job finishes as designed, and their rights are checked once more on the way out, so a running job whose
     * node was removed just before leaving is cancelled too. Back again, their old jobs hold nothing that stops a new
     * edit and its undo.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_leave_jobs", tickLimit = LIMIT)
    public void leavingCancelsWaitingJobsAndRechecksTheRunningOnes(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer(false);
        EditTestSupport.grant(bob, Perm.USE, Perm.REGION);
        ServerScatter scatter = new ServerScatter(h.service);
        int[] at = regionCorner(context, 136);
        int x = at[0], z = at[1];
        Box aliceBox = box(x, 112, z, x + 31, 127, z + 15);
        Box aliceLater = box(x + 64, 112, z, x + 71, 119, z + 7);
        Box bobBox = box(x + 128, 112, z, x + 159, 127, z + 15);
        Box all = box(x, 112, z, x + 159, 127, z + 15);
        loadAndForce(h.world, all);
        WorldSnapshot aliceBefore = capture(h.world, aliceBox);

        RecordingListener running = new RecordingListener();
        RecordingListener waiting = new RecordingListener();
        RecordingListener bobRunning = new RecordingListener();
        run(h, alice, fill(h, aliceBox, "minecraft:stone"), running);
        run(h, bob, fill(h, bobBox, "minecraft:gold_block"), bobRunning);
        executor.tick(); // 1024 cells a tick, round robin: both fills are a quarter into their first section
        executor.tick();
        check(count(h.world, aliceBox, Blocks.STONE) == 1024 && count(h.world, bobBox, Blocks.GOLD_BLOCK) == 1024,
                "the two fills are not both running");
        run(h, alice, fill(h, aliceBox, "minecraft:dirt"), waiting); // behind her running fill's locks
        check(running.result == null && bobRunning.result == null && executor.isWaiting(h.service.jobs(alice.getUuid())
                .get(1).jobId()), "not one job running and one waiting");

        EditServiceHost.playerLeft(h.service, scatter, alice); // Alice keeps her rights
        check(waiting.result != null && waiting.result.outcome() == JobOutcome.CANCELLED && waiting.result.changed() == 0,
                "the waiting job of a player who left: " + waiting.result);
        check(running.result == null, "the running job of a player who left was stopped");
        EditTestSupport.grant(bob); // a permissions mod removed Bob's nodes, then he left
        EditServiceHost.playerLeft(h.service, scatter, bob);
        tickUntil(executor, () -> running.result != null && bobRunning.result != null, 64, "the running jobs");
        check(running.result.outcome() == JobOutcome.COMPLETED && running.result.changed() == aliceBox.volume(),
                "Alice's running fill " + running.result);
        check(bobRunning.result.outcome() == JobOutcome.CANCELLED && bobRunning.result.changed() > 0
                && bobRunning.result.changed() < bobBox.volume() && bobRunning.result.changed() % 4096 == 0,
                "Bob's fill after losing his nodes and leaving " + bobRunning.result);
        check(h.service.connectionsHeld() == 0, "connection ids kept for players who left");

        // Alice back (the mock stays online, as the same player reconnected would be): a new edit and its undo run.
        RecordingListener again = new RecordingListener();
        run(h, alice, fill(h, aliceLater, "minecraft:gold_block"), again);
        tickUntil(executor, () -> again.result != null, 8, "the new fill");
        RecordingListener undo = historyStep(h, alice, true);
        tickUntil(executor, () -> undo.result != null, 8, "the undo after coming back");
        check(undo.result.outcome() == JobOutcome.COMPLETED && count(h.world, aliceLater, Blocks.GOLD_BLOCK) == 0,
                "undo " + undo.result);
        check(EditTestSupport.difference(with(aliceBefore, aliceBox, h.state("minecraft:stone")),
                capture(h.world, aliceBox)) == null, "Alice's old fill");
        executor.shutdown();
        forceChunks(h.world, all, false);
        h.close();
        context.complete();
    }

    /**
     * The per-player wait cap counts only jobs that will not start on the next tick: a burst of ten edits on an idle
     * server is admitted (two start at once, eight wait), the eleventh is refused.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_burst", tickLimit = LIMIT)
    public void aBurstOfTenEditsOnAnIdleServerIsAdmitted(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 137);
        int x = at[0], z = at[1];
        Box row = box(x, 112, z, x + 16 * 11 - 1, 112, z);
        loadAndForce(h.world, row);
        List<RecordingListener> listeners = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            RecordingListener listener = new RecordingListener();
            run(h, h.player, fill(h, box(x + 16 * i, 112, z, x + 16 * i, 112, z), "minecraft:stone"), listener);
            listeners.add(listener);
        }
        EditRejected eleventh = refusal(() -> h.service.run(h.player, fill(h, box(x + 160, 112, z, x + 160, 112, z),
                "minecraft:stone"), RunOptions.DEFAULT, null));
        check(eleventh.reason() == RejectReason.QUEUE_FULL, "the eleventh: " + eleventh);
        tickUntil(executor, () -> listeners.stream().allMatch(l -> l.result != null), 16, "the burst");
        check(listeners.stream().allMatch(l -> l.result.outcome() == JobOutcome.COMPLETED), "results");
        check(h.history().undoLabels().size() == 10, "history " + h.history());
        executor.shutdown();
        forceChunks(h.world, row, false);
        h.close();
        context.complete();
    }

    /**
     * {@code /sculptory cancel <player>} on the server's own edit service: the console and an admin cancel another player's jobs
     * by online name, by the name recorded on their jobs once they are gone, and by UUID; an unknown name is reported;
     * a player without admin may not, not even through {@code execute as} from the console. The jobs wait behind an
     * area hold, so nothing depends on how fast they would run.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_admin_cancel", tickLimit = LIMIT)
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void anAdminCancelsAnotherPlayersJobs(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        ServerWorld world = context.getWorld();
        EngineEditService host = EditServiceHost.find(server)
                .orElseThrow(() -> new GameTestException("the mod did not install the edit service"));
        String name = "bs" + UUID.randomUUID().toString().substring(0, 8);
        ServerPlayerEntity target = EditTestSupport.namedMockPlayer(context, name);
        ServerPlayerEntity admin = context.createMockCreativeServerPlayerInWorld();
        ServerPlayerEntity other = context.createMockCreativeServerPlayerInWorld();
        OperatorList ops = server.getPlayerManager().getOpList();
        ops.add(new OperatorEntry(target.getGameProfile(), 2, false));
        ops.add(new OperatorEntry(admin.getGameProfile(), 2, false));
        EditTestSupport.grant(other, Perm.USE, Perm.REGION); // no admin
        int[] at = regionCorner(context, 138);
        int x = at[0], z = at[1];
        Box area = box(x, 112, z, x + 15, 127, z + 15);
        loadAndForce(world, area);
        EditExecutor.AreaHold hold = host.executor().hold(world, x >> 4, z >> 4, x >> 4, z >> 4, 112 >> 4, 127 >> 4);
        OpSpec.Fill fill = new OpSpec.Fill(area, new Pattern.Single(EngineTestSupport.handle(host.runtime().states(),
                "minecraft:stone")), CellMask.ANY);
        String uuid = target.getUuid().toString();
        EditTestSupport.CapturedOutput out = new EditTestSupport.CapturedOutput();
        boolean[] targetOnline = {true};
        try {
            RecordingListener first = new RecordingListener();
            host.run(target, fill, RunOptions.DEFAULT, first);
            check(host.executor().isWaiting(host.jobs(target.getUuid()).get(0).jobId()), "the job is not held back");

            // A player without admin does not see /sculptory, neither directly nor through "execute as" from the console...
            server.getCommandManager().executeWithPrefix(other.getCommandSource().withOutput(out), "sculptory cancel " + uuid);
            server.getCommandManager().executeWithPrefix(server.getCommandSource().withOutput(out),
                    "execute as " + other.getUuidAsString() + " run sculptory cancel " + uuid);
            check(!out.all().contains("Cancelled"), "a non-admin reached the command: " + out.all());
            // ...and the command itself refuses one acting for the console (parsed as the console, run as the player).
            CommandDispatcher<ServerCommandSource> commands = server.getCommandManager().getDispatcher();
            ParseResults<ServerCommandSource> parsed = commands.parse("sculptory cancel " + uuid, server.getCommandSource());
            parsed.getContext().withSource(server.getCommandSource().withOutput(out).withEntity(other));
            try {
                commands.execute(parsed);
                throw new GameTestException("a non-admin acting for the console cancelled the jobs");
            } catch (CommandSyntaxException e) {
                check(e.getMessage().contains(Perm.ADMIN.node()), "refusal " + e.getMessage());
            }
            check(first.result == null && host.jobs(target.getUuid()).size() == 1, "a non-admin cancelled the job");

            // An admin may, by the target's online name.
            out.lines.clear();
            server.getCommandManager().executeWithPrefix(admin.getCommandSource().withOutput(out), "sculptory cancel " + name);
            check(out.all().contains("Cancelled 1 job of " + name), "admin output: " + out.all());
            check(first.result != null && first.result.outcome() == JobOutcome.CANCELLED && first.result.changed() == 0,
                    "the admin's cancel " + first.result);

            // The target is no longer online (the edit service not told): the name recorded on the job finds them.
            RecordingListener second = new RecordingListener();
            host.run(target, fill, RunOptions.DEFAULT, second);
            server.getPlayerManager().remove(target);
            targetOnline[0] = false;
            check(server.getPlayerManager().getPlayer(name) == null, "the target is still online");
            out.lines.clear();
            server.getCommandManager().executeWithPrefix(server.getCommandSource().withOutput(out), "sculptory cancel " + name);
            check(out.all().contains("Cancelled 1 job of " + name), "console output by job owner name: " + out.all());
            check(second.result != null && second.result.outcome() == JobOutcome.CANCELLED, "the console's cancel");

            // By UUID; and an unknown name is reported, not looked up elsewhere.
            RecordingListener third = new RecordingListener();
            host.run(admin, fill, RunOptions.DEFAULT, third);
            out.lines.clear();
            server.getCommandManager().executeWithPrefix(server.getCommandSource().withOutput(out),
                    "sculptory cancel " + admin.getUuidAsString());
            check(out.all().contains("Cancelled 1 job of " + admin.getUuidAsString()) && third.result != null,
                    "console output by UUID: " + out.all());
            out.lines.clear();
            server.getCommandManager().executeWithPrefix(server.getCommandSource().withOutput(out), "sculptory cancel NoSuchPlayer9");
            check(out.all().contains("No such player, and no jobs for that name"), "unknown name output: " + out.all());
            check(host.jobs(target.getUuid()).isEmpty() && host.jobs(admin.getUuid()).isEmpty(), "jobs are still listed");
        } catch (EditRejected e) {
            throw new GameTestException("the fill was refused: " + e.getMessage());
        } finally {
            hold.release();
            ops.remove(target.getGameProfile());
            ops.remove(admin.getGameProfile());
            EditTestSupport.grant(other);
            for (ServerPlayerEntity p : List.of(target, admin, other)) {
                host.cancelAll(p.getUuid());
                host.playerLeft(p.getUuid());
                if (p == target && !targetOnline[0]) continue;
                try {
                    server.getPlayerManager().remove(p);
                } catch (RuntimeException e) {
                    LoggerFactory.getLogger("sculptory").warn("Could not remove a mock player", e);
                }
            }
            forceChunks(world, area, false);
        }
        context.complete();
    }

    /**
     * A player who left keeps their running job (it finishes as designed), but it can still be stopped: cancelling all
     * of that player's jobs by UUID (what {@code /sculptory cancel <player>} does) stops it at its next section boundary.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_cancel_leaver", tickLimit = LIMIT)
    public void theRunningJobOfAPlayerWhoLeftCanBeCancelled(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 140);
        Box region = box(at[0], 112, at[1], at[0] + 15, 143, at[1] + 15); // two sections
        loadAndForce(h.world, region);
        RecordingListener fill = new RecordingListener();
        run(h, h.player, fill(h, region, "minecraft:stone"), fill);
        executor.tick();
        check(fill.result == null && fill.phases.contains(Phase.APPLY), "the fill is not running");
        UUID id = h.player.getUuid();
        EditServiceHost.playerLeft(h.service, null, h.player);
        executor.tick();
        check(fill.result == null && h.service.jobs(id).size() == 1, "leaving stopped the running fill");
        check(h.service.cancelAll(id) == 1, "the leaver's job was not cancelled");
        tickUntil(executor, () -> fill.result != null, 8, "the cancelled fill");
        check(fill.result.outcome() == JobOutcome.CANCELLED && fill.result.changed() == 4096, "the fill " + fill.result);
        check(h.service.historyService().find(id).isEmpty(), "the job of a player who left pushed history");
        executor.shutdown();
        forceChunks(h.world, region, false);
        h.close();
        context.complete();
    }

    /**
     * {@code /deop} end to end with a running job: {@code PlayerManager.removeFromOperators} cancels it through
     * {@code PlayerManagerMixin}, and it stops at its next section boundary with its applied sections kept in history.
     * The fill is 5,242,880 blocks, so it cannot finish within the tick in which it is seen running and deopped (the
     * GameTest server is not dedicated: a 20 ms budget, a few hundred thousand blocks here).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_deop_running", tickLimit = LIMIT)
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void deopStopsARunningJobThroughTheMixin(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        EngineEditService host = EditServiceHost.find(server)
                .orElseThrow(() -> new GameTestException("the mod did not install the edit service"));
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        OperatorList ops = server.getPlayerManager().getOpList();
        ops.add(new OperatorEntry(player.getGameProfile(), 2, false));
        int[] at = regionCorner(context, 139);
        int x = at[0], z = at[1];
        Box big = box(x, -64, z, x + 127, 255, z + 127);
        loadAndForce(context.getWorld(), big);
        RecordingListener job = new RecordingListener();
        try {
            // An op holds limit.bypass through the op-level fallback, so the fill may exceed maxOpVolume.
            host.run(player, new OpSpec.Fill(big, new Pattern.Single(EngineTestSupport.handle(host.runtime().states(),
                    "minecraft:stone")), CellMask.ANY), RunOptions.DEFAULT, job);
        } catch (EditRejected e) {
            throw new GameTestException("the fill was refused: " + e.getMessage());
        }
        Runnable cleanup = () -> {
            ops.remove(player.getGameProfile());
            host.cancelAll(player.getUuid());
            host.playerLeft(player.getUuid());
            try {
                server.getPlayerManager().remove(player);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
            forceChunks(context.getWorld(), big, false);
        };
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    check(job.phases.contains(Phase.APPLY), "the fill is not running");
                    // In the tick the fill is first seen running (it has had one tick of work): /deop.
                    if (job.result != null) {
                        cleanup.run();
                        throw new GameTestException("the fill finished too soon to test: " + job.result);
                    }
                    server.getPlayerManager().removeFromOperators(player.getGameProfile()); // what /deop calls
                    check(job.result == null, "the running fill stopped mid-section");
                })
                .createAndAdd(() -> check(job.result != null, "the deop did not stop the running fill"))
                .createAndAdd(() -> {
                    try {
                        check(job.result.outcome() == JobOutcome.CANCELLED && job.result.changed() > 0
                                && job.result.changed() < big.volume() && job.result.changed() % 4096 == 0,
                                "the fill " + job.result);
                        check(host.history(player).undoLabel().startsWith("Fill (cancelled)"), "history " + host.history(player));
                    } finally {
                        cleanup.run();
                    }
                })
                .completeIfSuccessful();
    }

    /**
     * Vanilla resends the command tree when a player changes world, before moving them. With a grant scoped to the
     * overworld, a player whose job waits in the overworld goes to the nether and comes back: the re-checks on the way
     * (in the nether, the grant does not apply) leave the job alone, since they look only at the player's world. A
     * {@code /deop} ({@code removeFromOperators}) still re-checks every world: from the nether it cancels an overworld
     * job the player may no longer run.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_world_change", tickLimit = LIMIT)
    @SuppressWarnings("removal") // createMockCreativeServerPlayerInWorld is vanilla's test-only mock player
    public void aChangeOfWorldRechecksOnlyTheWorldThePlayerIsIn(TestContext context) {
        MinecraftServer server = context.getWorld().getServer();
        ServerWorld overworld = context.getWorld();
        ServerWorld nether = server.getWorld(net.minecraft.world.World.NETHER);
        EngineEditService host = EditServiceHost.find(server)
                .orElseThrow(() -> new GameTestException("the mod did not install the edit service"));
        check(nether != null && overworld.getRegistryKey() == net.minecraft.world.World.OVERWORLD, "worlds");
        ServerPlayerEntity player = context.createMockCreativeServerPlayerInWorld();
        EditTestSupport.grant(player, Perm.USE);
        EditTestSupport.grantIn(player, net.minecraft.world.World.OVERWORLD, Perm.REGION);
        int[] at = regionCorner(context, 141);
        int x = at[0], z = at[1];
        Box area = box(x, 112, z, x + 15, 127, z + 15);
        loadAndForce(overworld, area);
        EditExecutor.AreaHold hold = host.executor().hold(overworld, x >> 4, z >> 4, x >> 4, z >> 4, 112 >> 4, 127 >> 4);
        OpSpec.Fill fill = new OpSpec.Fill(area, new Pattern.Single(EngineTestSupport.handle(host.runtime().states(),
                "minecraft:stone")), CellMask.ANY);
        OperatorList ops = server.getPlayerManager().getOpList();
        BlockPos home = new BlockPos(x, 130, z);
        try {
            RecordingListener scoped = new RecordingListener();
            host.run(player, fill, RunOptions.DEFAULT, scoped);
            check(player.teleport(nether, 0.5, 100, 0.5, Set.of(), 0, 0), "teleport to the nether failed");
            check(!host.runtime().permissions().has(player, Perm.REGION), "the scoped grant holds in the nether");
            check(player.teleport(overworld, home.x(), home.y(), home.z(), Set.of(), 0, 0), "teleport back failed");
            check(scoped.result == null && host.jobs(player.getUuid()).size() == 1,
                    "a change of world cancelled the overworld job: " + scoped.result);
            check(host.runtime().permissions().has(player, Perm.REGION), "the scoped grant is lost in the overworld");

            // Opped (every node), a second job; then /deop from the nether: every world is re-checked.
            ops.add(new OperatorEntry(player.getGameProfile(), 2, false));
            RecordingListener opped = new RecordingListener();
            host.run(player, fill, RunOptions.DEFAULT, opped);
            check(player.teleport(nether, 0.5, 100, 0.5, Set.of(), 0, 0), "teleport to the nether failed");
            check(opped.result == null && scoped.result == null, "the move to the nether cancelled a job");
            server.getPlayerManager().removeFromOperators(player.getGameProfile());
            check(opped.result != null && opped.result.outcome() == JobOutcome.CANCELLED
                    && scoped.result != null && scoped.result.outcome() == JobOutcome.CANCELLED,
                    "the deop from the nether did not cancel the overworld jobs: " + opped.result + " / " + scoped.result);
        } catch (EditRejected e) {
            throw new GameTestException("the fill was refused: " + e.getMessage());
        } finally {
            hold.release();
            ops.remove(player.getGameProfile());
            host.cancelAll(player.getUuid());
            host.playerLeft(player.getUuid());
            EditTestSupport.grant(player);
            EditTestSupport.clearWorldGrants(player);
            try {
                server.getPlayerManager().remove(player);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
            forceChunks(overworld, area, false);
        }
        context.complete();
    }

    /**
     * The operator-NBT right is needed only by jobs that would lose operator NBT without it: a paste whose clipboard
     * holds untrusted tiles on operator-NBT states (a command block from a bypass copy, say). A move (world-captured
     * tiles from columns it may write), a stack from a source the player may write and a paste of plain blocks are not
     * cancelled when the right goes. A stack from a protected source reads it under {@code limit.bypass}, so it needs
     * that right, and is cancelled when it goes.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_operator_nbt", tickLimit = LIMIT)
    public void theOperatorNbtRightIsNeededOnlyForUntrustedOperatorTiles(TestContext context) {
        EditExecutor executor = executor(context, 0); // never ticked: every job stays waiting
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity builder = h.addPlayer(false);
        Perm[] all = {Perm.USE, Perm.REGION, Perm.CLIPBOARD, Perm.NBT_OPERATOR, Perm.LIMIT_BYPASS};
        EditTestSupport.grant(builder, all);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 142);
        int x = at[0], z = at[1];
        Box loaded = box(x - 16, 90, z - 16, x + 47, 130, z + 31);
        loadAndForce(world, loaded);
        int commandBlock = h.state("minecraft:command_block[facing=up]");
        check((h.runtime.states().flags(commandBlock) & dev.sculptory.core.state.StateFlags.OPERATOR_NBT) != 0,
                "command blocks are not operator-NBT states");
        UUID plain = h.service.clipboards().install(builder.getUuid(), cube(h, 2, "minecraft:stone")).id();
        RecordingListener move = new RecordingListener();
        RecordingListener stack = new RecordingListener();
        RecordingListener plainPaste = new RecordingListener();
        RecordingListener commandPaste = new RecordingListener();
        run(h, builder, new OpSpec.Move(box(x + 2, 101, z + 2, x + 3, 101, z + 3), new BlockPos(0, 2, 0),
                Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air"))), move);
        run(h, builder, new OpSpec.Stack(box(x + 2, 104, z + 2, x + 3, 104, z + 3), 3, 0, 0, 2), stack);
        run(h, builder, paste(plain, new BlockPos(x + 8, 106, z + 8)), plainPaste);
        UUID commands = h.service.clipboards().install(builder.getUuid(), commandBlockClipboard(h)).id();
        run(h, builder, paste(commands, new BlockPos(x + 12, 106, z + 12)), commandPaste);
        check(h.service.revalidate(builder) == 0, "a job was cancelled with every right held");

        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.CLIPBOARD, Perm.LIMIT_BYPASS);
        check(!h.runtime.permissions().mayWriteOperatorNbt(builder), "the operator NBT right is still held");
        check(h.service.revalidate(builder) == 1 && commandPaste.result != null
                && commandPaste.result.outcome() == JobOutcome.CANCELLED && commandPaste.result.changed() == 0,
                "losing the operator NBT right: " + commandPaste.result);
        check(move.result == null && stack.result == null && plainPaste.result == null,
                "a job without untrusted operator NBT was cancelled");

        // A stack from a protected source (chunk B beyond the world border): admitted only under limit.bypass.
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(x + 16 - 100_000, z);
            border.setSize(200_000);
            RecordingListener protectedStack = new RecordingListener();
            run(h, builder, new OpSpec.Stack(box(x + 20, 101, z + 2, x + 21, 101, z + 3), -8, 0, 0, 2), protectedStack);
            EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.CLIPBOARD);
            check(h.service.revalidate(builder) == 1 && protectedStack.result != null
                    && protectedStack.result.outcome() == JobOutcome.CANCELLED,
                    "losing limit.bypass kept the stack from a protected source: " + protectedStack.result);
            check(move.result == null && stack.result == null && plainPaste.result == null,
                    "losing limit.bypass cancelled jobs that did not use it");
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        executor.shutdown();
        forceChunks(world, loaded, false);
        h.close();
        context.complete();
    }

    /**
     * An undo still waiting to start (behind another player's job on the same cells) is cancelled when its player
     * leaves, having changed nothing; the other player's job runs on.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_leave_undo", tickLimit = LIMIT)
    public void aWaitingUndoIsCancelledWhenThePlayerLeaves(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        int[] at = regionCorner(context, 143);
        Box region = box(at[0], 112, at[1], at[0] + 15, 127, at[1] + 15);
        loadAndForce(h.world, region);
        RecordingListener aliceFill = new RecordingListener();
        run(h, alice, fill(h, region, "minecraft:stone"), aliceFill);
        tickUntil(executor, () -> aliceFill.result != null, 8, "Alice's fill");
        RecordingListener bobFill = new RecordingListener();
        run(h, bob, fill(h, region, "minecraft:gold_block"), bobFill);
        executor.tick();
        check(bobFill.result == null && bobFill.phases.contains(Phase.APPLY), "Bob's fill is not running");
        RecordingListener undo = historyStep(h, alice, true);
        check(executor.isWaiting(h.service.jobs(alice.getUuid()).get(0).jobId()), "Alice's undo is not waiting");

        EditServiceHost.playerLeft(h.service, null, alice);
        check(undo.result != null && undo.result.outcome() == JobOutcome.CANCELLED && undo.result.changed() == 0,
                "the waiting undo of a player who left: " + undo.result);
        check(h.service.historyService().find(alice.getUuid()).isEmpty(), "Alice's history was kept");
        tickUntil(executor, () -> bobFill.result != null, 8, "Bob's fill");
        check(bobFill.result.outcome() == JobOutcome.COMPLETED && count(h.world, region, Blocks.GOLD_BLOCK) == region.volume(),
                "Bob's fill " + bobFill.result);
        executor.shutdown();
        forceChunks(h.world, region, false);
        h.close();
        context.complete();
    }

    // ---------------------------------------------------------------- protection

    /**
     * Protection on every write path, for a builder without {@code limit.bypass}: chunk B lies outside the world border
     * (the one protection a GameTest can set up; it reaches the engine through {@code canPlayerModifyAt} like spawn
     * protection and claims). Fills and pastes across both chunks write chunk A only; a move into B is refused whole; a
     * stack's copies landing in B are not written; a dab wholly in B is refused PROTECTED and one on the edge writes A
     * only; an undo made after B became protected leaves B as it is. Chunk B never changes.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_protection", tickLimit = LIMIT)
    public void protectionHoldsOnEveryWritePath(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.CLIPBOARD, Perm.BRUSH);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 126);
        int x = at[0], z = at[1];
        Box chunkA = box(x, 100, z, x + 15, 110, z + 15);
        Box chunkB = box(x + 16, 100, z, x + 31, 110, z + 15);
        Box loaded = box(x - 16, 90, z - 16, x + 47, 130, z + 31);
        loadAndForce(world, loaded);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int dx = 0; dx < 32; dx++) {
            for (int dz = 0; dz < 16; dz++) writer.write(x + dx, 100, z + dz, stone, null);
        }
        // An edit made while both chunks are open: undone after B is protected.
        RecordingListener early = new RecordingListener();
        run(h, builder, fill(h, box(x, 108, z, x + 31, 108, z + 15), "minecraft:glass"), early);
        tickUntil(executor, () -> early.result != null, 4, "the early fill");
        WorldSnapshot bBefore = capture(world, chunkB);
        UUID clipboard = h.service.clipboards().install(builder.getUuid(), cube(h, 4, "minecraft:emerald_block")).id();
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(x + 16 - 100_000, z);
            border.setSize(200_000);
            check(!world.canPlayerModifyAt(builder, pos(x + 20, 101, z + 5)), "chunk B is not protected");
            check(world.canPlayerModifyAt(builder, pos(x + 5, 101, z + 5)), "chunk A is protected");

            RecordingListener fill = new RecordingListener();
            run(h, builder, fill(h, box(x, 101, z, x + 31, 101, z + 15), "minecraft:gold_block"), fill);
            tickUntil(executor, () -> fill.result != null, 4, "the fill");
            check(fill.result.changed() == 256 && count(world, chunkA, Blocks.GOLD_BLOCK) == 256, "fill " + fill.result);

            RecordingListener paste = new RecordingListener();
            run(h, builder, paste(clipboard, new BlockPos(x + 14, 102, z)), paste);
            tickUntil(executor, () -> paste.result != null, 4, "the paste");
            check(paste.result.changed() == 2 * 4 * 4 && count(world, chunkA, Blocks.EMERALD_BLOCK) == 32,
                    "paste " + paste.result);

            // Six copies of 2 × 2 gold cells, stepping 4 east and 1 up: three land in A, three in B.
            RecordingListener stack = new RecordingListener();
            run(h, builder, new OpSpec.Stack(box(x + 2, 101, z + 2, x + 3, 101, z + 3), 4, 1, 0, 6), stack);
            tickUntil(executor, () -> stack.result != null, 4, "the stack");
            check(stack.result.changed() == 3 * 4, "the stack wrote copies in B: " + stack.result);

            EditRejected move = refusal(() -> h.service.run(builder, new OpSpec.Move(box(x + 2, 103, z + 2, x + 5, 103, z + 5),
                    new BlockPos(16, 0, 0), Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air"))),
                    RunOptions.DEFAULT, null));
            check(move.reason() == RejectReason.PROTECTED, "a move into B: " + move);

            begin(h, builder, 3);
            check(h.service.dabs(builder, 3, 1, List.of(dab(0, x + 24, 100, z + 8))).accepted(), "the dab in B was refused");
            executor.tick();
            check(h.events.dabRejections.equals(List.of(RejectReason.PROTECTED)), "dab in B: " + h.events.dabRejections);
            h.service.endStroke(builder, 3);
            begin(h, builder, 4);
            WorldSnapshot aBeforeDab = capture(world, chunkA);
            check(h.service.dabs(builder, 4, 2, List.of(dab(0, x + 15, 100, z + 8))).accepted(), "the edge dab was refused");
            executor.tick();
            check(EditTestSupport.difference(aBeforeDab, capture(world, chunkA)) != null, "the edge dab changed nothing in A");
            h.service.endStroke(builder, 4);

            // Undo the edits in turn; the early fill's glass in B stays, as B may no longer be modified.
            for (int i = 0; i < 5; i++) {
                RecordingListener undo = historyStep(h, builder, true);
                tickUntil(executor, () -> undo.result != null, 4, "undo " + i);
            }
            check(count(world, chunkA, Blocks.GLASS) == 0 && count(world, chunkB, Blocks.GLASS) == 256,
                    "the undo of the early fill wrote the protected chunk");
            checkSame(bBefore, capture(world, chunkB), "chunk B");
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        executor.shutdown();
        forceChunks(world, loaded, false);
        h.close();
        context.complete();
    }

    // ---------------------------------------------------------------- clipboards and connections

    /**
     * Two players copy at the same time: each gets their own clipboard, and neither can paste, preview, export or save
     * the other's. A copy still being built when its player's connection ends is dropped, even though the player (a new
     * connection, same UUID) is back when it finishes: it never replaces the new connection's clipboard.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_clipboards", tickLimit = LIMIT)
    public void clipboardsBelongToTheirPlayerAndConnection(TestContext context) {
        Harness h = new Harness(context);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        ServerWorld world = h.world;
        Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        int[] at = regionCorner(context, 127);
        int x = at[0], z = at[1];
        Box aliceArea = box(x, 112, z, x + 3, 115, z + 3);
        Box bobArea = box(x + 16, 112, z, x + 21, 113, z + 5);
        Box all = box(x, 112, z, x + 31, 115, z + 15);
        loadAndForce(world, all);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        paint(writer, aliceArea, h.state("minecraft:stone"));
        paint(writer, bobArea, h.state("minecraft:gold_block"));
        Captured<ClipboardService.ClipboardInfo> aliceCopy = ClipboardGameTest.copy(clips, alice, aliceArea, aliceArea.min());
        Captured<ClipboardService.ClipboardInfo> bobCopy = ClipboardGameTest.copy(clips, bob, bobArea, bobArea.min());
        List<Captured<ClipboardService.ClipboardInfo>> late = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(aliceCopy.finished() && bobCopy.finished(), "copies running"))
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo a = aliceCopy.get("Alice's copy");
                    ClipboardService.ClipboardInfo b = bobCopy.get("Bob's copy");
                    check(!a.clipboardId().equals(b.clipboardId()), "one clipboard id for both");
                    check(a.cells() == aliceArea.volume() && b.cells() == bobArea.volume(), "cells " + a + " / " + b);
                    Clipboard aliceHeld = h.service.clipboards().get(alice.getUuid()).orElseThrow().clipboard();
                    Clipboard bobHeld = h.service.clipboards().get(bob.getUuid()).orElseThrow().clipboard();
                    check(aliceHeld.get(0, 0, 0) == h.state("minecraft:stone")
                            && bobHeld.get(0, 0, 0) == h.state("minecraft:gold_block"), "the clipboards crossed");
                    UUID bobs = b.clipboardId();
                    check(refusal(() -> h.service.run(alice, paste(bobs, new BlockPos(x, 120, z)), RunOptions.DEFAULT, null))
                            .reason() == RejectReason.INVALID, "Alice pasted Bob's clipboard");
                    check(refusal(() -> clips.preview(alice, new SourceRef.Clipboard(bobs), new Captured<>())).reason()
                            == RejectReason.INVALID, "Alice previewed Bob's clipboard");
                    check(refusal(() -> clips.export(alice, bobs, new Captured<>())).reason() == RejectReason.INVALID,
                            "Alice exported Bob's clipboard");
                    check(refusal(() -> clips.save(alice, bobs, "shared/bob.schem", new Captured<>())).reason()
                            == RejectReason.INVALID, "Alice saved Bob's clipboard");
                    // Alice copies again and her connection ends before the clipboard is built; the mock player stays
                    // online, as the same player reconnected would be.
                    late.add(ClipboardGameTest.copy(clips, alice, aliceArea, aliceArea.min()));
                    h.service.playerLeft(alice.getUuid());
                })
                .createAndAdd(() -> check(late.get(0).finished(), "the late copy is running"))
                .createAndAdd(() -> {
                    late.get(0).failedWith(RejectReason.INVALID, "a copy finished after its connection ended");
                    check(h.service.clipboards().get(alice.getUuid()).isEmpty(),
                            "the old connection's copy became the new connection's clipboard");
                    check(h.service.clipboards().get(bob.getUuid()).isPresent(), "Bob's clipboard left with Alice");
                    check(clips.requests(alice.getUuid()) == 0 && clips.requests(bob.getUuid()) == 0, "leases held");
                    clips.shutdown();
                    ClipTestSupport.deleteTree(root.getParent());
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // ---------------------------------------------------------------- leaving and stopping

    /**
     * Alice leaves while her fill runs, Bob's fill waits behind it, her scatter preview holds an area Bob's second job
     * waits for, and a dab is queued in her brush lane. Her dab is applied and acknowledged at once, her stroke and
     * history are dropped, her preview's area is released, and her running fill finishes (pushing nothing). Bob's jobs
     * then run, his history stays exact, and no lock, ticket or hold is left.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_leave", tickLimit = LIMIT)
    public void aPlayerLeavingMidJobFreesEverythingAndOthersCarryOn(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        UUID aliceId = alice.getUuid();
        ServerScatter scatter = new ServerScatter(h.service);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 128);
        int x = at[0], z = at[1];
        Box aliceBox = box(x, 112, z, x + 31, 127, z + 15);
        Box bobBox = box(x + 16, 112, z, x + 31, 127, z + 15);
        Box jobs = box(x, 112, z, x + 31, 127, z + 15);
        loadAndForce(world, jobs);
        int sx = x + 128, bx = x + 256;
        Box scatterFloor = ScatterGameTest.floor(h, sx, z, 16, 16);
        Box brushFloor = ScatterGameTest.floor(h, bx, z, 16, 16);
        Box held = box(sx + 2, 101, z + 2, sx + 5, 104, z + 5);
        WorldSnapshot jobsBefore = capture(world, jobs);
        WorldSnapshot heldBefore = capture(world, held);

        RecordingListener aliceFill = new RecordingListener();
        RecordingListener bobFill = new RecordingListener();
        RecordingListener bobHeld = new RecordingListener();
        run(h, alice, fill(h, aliceBox, "minecraft:stone"), aliceFill);
        run(h, bob, fill(h, bobBox, "minecraft:gold_block"), bobFill);
        ScatterGameTest.Reply preview = ScatterGameTest.preview(scatter, alice, ScatterGameTest.request(
                ScatterGameTest.region(sx, z, sx + 15, z + 15), 4, 1L, ScatterGameTest.block(h, alice, "minecraft:oak_log")));
        run(h, bob, fill(h, held, "minecraft:gold_block"), bobHeld);
        executor.tick();
        check(aliceFill.result == null && bobFill.result == null && bobHeld.result == null,
                "the jobs are not in flight: " + aliceFill.result + " / " + bobFill.result + " / " + bobHeld.result);
        check(executor.holdCount() == 1, "the preview holds nothing");
        begin(h, alice, 7);
        check(h.service.dabs(alice, 7, 3, List.of(dab(0, bx + 8, 100, z + 8))).accepted(), "Alice's dab was refused");
        check(h.acks.seqs.isEmpty() && h.service.queuedDabs(aliceId) == 1, "the dab is not queued");

        // Alice disconnects: EditServiceHost runs the scatter service's hook, then the edit service's.
        scatter.playerLeft(aliceId);
        h.service.playerLeft(aliceId);
        check(!preview.finished() && executor.holdCount() == 0, "the preview of a player who left holds its area");
        check(h.acks.seqs.equals(List.of(3)) && h.service.queuedDabs(aliceId) == 0, "the queued dab: " + h.acks.seqs);
        check(h.service.openStroke(aliceId).isEmpty(), "Alice's stroke is still open");
        check(h.service.historyService().find(aliceId).isEmpty(), "Alice's history was kept");

        tickUntil(executor, () -> aliceFill.result != null && bobFill.result != null && bobHeld.result != null, 64, "jobs");
        check(aliceFill.result.outcome() == JobOutcome.COMPLETED && aliceFill.result.changed() == aliceBox.volume(),
                "Alice's fill " + aliceFill.result);
        check(bobFill.result.outcome() == JobOutcome.COMPLETED && bobHeld.result.outcome() == JobOutcome.COMPLETED,
                "Bob's jobs " + bobFill.result + " / " + bobHeld.result);
        check(h.service.historyService().find(aliceId).isEmpty(), "the fill of a player who left pushed history");
        int stone = h.state("minecraft:stone"), gold = h.state("minecraft:gold_block");
        checkSame(with(with(jobsBefore, aliceBox, stone), bobBox, gold), capture(world, jobs), "after the fills");

        // His fill behind Alice's finished last, so it is undone first (it restores her stone), then the held one.
        RecordingListener undo1 = historyStep(h, bob, true);
        tickUntil(executor, () -> undo1.result != null, 8, "Bob's first undo");
        RecordingListener undo2 = historyStep(h, bob, true);
        tickUntil(executor, () -> undo2.result != null, 8, "Bob's second undo");
        check(undo1.result.skippedConflicts() == 0 && undo2.result.skippedConflicts() == 0, "conflicts");
        checkSame(heldBefore, capture(world, held), "the held area after Bob's undo");
        checkSame(with(jobsBefore, aliceBox, stone), capture(world, jobs), "Alice's fill after Bob's undo");
        check(!executor.isLocked(world, jobs) && !executor.isLocked(world, held), "locks left");
        checkNoEditTickets(executor, world, jobs);
        executor.shutdown();
        forceChunks(world, jobs, false);
        forceChunks(world, scatterFloor, false);
        forceChunks(world, brushFloor, false);
        h.close();
        context.complete();
    }

    /**
     * Server stop with three players' work in flight: Alice's and Carol's fills are each a quarter through a section,
     * Bob's fill waits behind Alice's and a dab of his is queued. Stopping finishes the section each running job is in
     * (so every section is whole: written entirely or not at all), ends every job CANCELLED, drops and acknowledges the
     * dab, keeps each job's applied work in its owner's history, and leaves no lock or ticket.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_mp_stop", tickLimit = LIMIT)
    public void serverStopEndsEveryPlayersJobsOnWholeSections(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        ServerPlayerEntity carol = h.addPlayer();
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 129);
        int x = at[0], z = at[1];
        Box aliceBox = box(x, 112, z, x + 31, 127, z + 15);
        Box bobBox = box(x + 16, 112, z, x + 31, 127, z + 15);
        Box carolBox = box(x + 64, 112, z, x + 79, 143, z + 15);
        Box all = box(x, 112, z, x + 79, 143, z + 15);
        loadAndForce(world, all);
        int bx = x + 256;
        Box brushFloor = ScatterGameTest.floor(h, bx, z, 16, 16);
        Box dabArea = box(bx, 96, z, bx + 15, 110, z + 15);
        WorldSnapshot dabBefore = capture(world, dabArea);

        RecordingListener aliceFill = new RecordingListener();
        RecordingListener bobFill = new RecordingListener();
        RecordingListener carolFill = new RecordingListener();
        run(h, alice, fill(h, aliceBox, "minecraft:stone"), aliceFill);
        run(h, bob, fill(h, bobBox, "minecraft:gold_block"), bobFill);
        run(h, carol, fill(h, carolBox, "minecraft:polished_granite"), carolFill);
        executor.tick();
        executor.tick();
        check(count(world, aliceBox, Blocks.STONE) == 1024 && count(world, carolBox, Blocks.POLISHED_GRANITE) == 1024,
                "Alice's and Carol's fills are not a quarter through a section");
        begin(h, bob, 2);
        check(h.service.dabs(bob, 2, 9, List.of(dab(0, bx + 8, 100, z + 8))).accepted(), "Bob's dab was refused");

        executor.shutdown(); // SERVER_STOPPING: the executor first, then the services
        for (RecordingListener l : List.of(aliceFill, bobFill, carolFill)) {
            check(l.result != null && l.result.outcome() == JobOutcome.CANCELLED && l.finishedCalls == 1, "result " + l.result);
        }
        check(aliceFill.result.changed() == 4096 && carolFill.result.changed() == 4096 && bobFill.result.changed() == 0,
                "changed " + aliceFill.result.changed() + " / " + bobFill.result.changed() + " / " + carolFill.result.changed());
        checkWholeSections(world, aliceBox, Blocks.STONE, 4096);
        checkWholeSections(world, carolBox, Blocks.POLISHED_GRANITE, 4096);
        check(count(world, bobBox, Blocks.GOLD_BLOCK) == 0, "Bob's waiting fill wrote");
        check(h.acks.seqs.equals(List.of(9)), "Bob's dab was not acknowledged: " + h.acks.seqs);
        checkSame(dabBefore, capture(world, dabArea), "Bob's dropped dab");
        check(h.history().undoLabels().equals(List.of("Fill (cancelled) · 4,096 blocks"))
                && h.service.history(carol).undoLabels().equals(List.of("Fill (cancelled) · 4,096 blocks"))
                && h.service.history(bob).undoLabels().isEmpty(), "histories");
        check(!executor.isLocked(world, all), "locks left");
        checkNoEditTickets(executor, world, all);
        check(refusal(() -> h.service.run(bob, fill(h, bobBox, "minecraft:gold_block"), RunOptions.DEFAULT, null)).reason()
                == RejectReason.DISABLED, "a fill after the stop");
        h.service.shutdown();
        check(h.service.jobs(alice.getUuid()).isEmpty() && h.service.historyService().totalBytes() == 0, "service state");
        forceChunks(world, all, false);
        forceChunks(world, brushFloor, false);
        h.close();
        context.complete();
    }

    // ---------------------------------------------------------------- helpers

    @FunctionalInterface
    interface Attempt {
        void run(ServerPlayerEntity player) throws EditRejected;
    }

    /** A private executor ticked by the test: the LOAD policy and at most {@code cellsPerTick} cells a tick (0: any). */
    static EditExecutor executor(TestContext context, long cellsPerTick) {
        return new EditExecutor(context.getWorld().getServer(), EngineTestSupport.runtime(context).states(),
                new EditExecutor.Settings(200_000_000L, cellsPerTick, 0.4, 2, 8, 32, 64, UnloadedPolicy.LOAD, 1024, 16_384));
    }

    static void tickUntil(EditExecutor executor, BooleanSupplier done, int maxTicks, String what) {
        ticksUntil(executor, done, maxTicks);
        check(done.getAsBoolean(), what + " did not finish in " + maxTicks + " ticks");
    }

    /** Ticks until {@code done} (at most {@code maxTicks}); how many ticks that took. */
    static int ticksUntil(EditExecutor executor, BooleanSupplier done, int maxTicks) {
        int ticks = 0;
        while (!done.getAsBoolean() && ticks < maxTicks) {
            executor.tick();
            ticks++;
        }
        check(done.getAsBoolean(), "not done after " + maxTicks + " ticks");
        return ticks;
    }

    static OpSpec.Fill fill(Harness h, Box box, String state) {
        return new OpSpec.Fill(box, new Pattern.Single(h.state(state)), CellMask.ANY);
    }

    static OpSpec.Paste paste(UUID clipboard, BlockPos origin) {
        return new OpSpec.Paste(new SourceRef.Clipboard(clipboard), origin, Transform.IDENTITY, PasteOptions.DEFAULT);
    }

    static JobTicket run(Harness h, ServerPlayerEntity player, OpSpec op, JobListener listener) {
        try {
            return h.service.run(player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }

    static RecordingListener historyStep(Harness h, ServerPlayerEntity player, boolean undo) {
        RecordingListener listener = new RecordingListener();
        try {
            if (undo) {
                h.service.undo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
            } else {
                h.service.redo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
            }
        } catch (EditRejected e) {
            throw new GameTestException((undo ? "undo" : "redo") + " refused: " + e.getMessage());
        }
        return listener;
    }

    static void begin(Harness h, ServerPlayerEntity player, int strokeId) {
        try {
            h.service.beginStroke(player, strokeId, RAISE);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
    }

    static EditRejected refusal(ClipboardGameTest.ThrowingRun run) {
        return ClipboardGameTest.refusal(run);
    }

    /** Requires {@code attempt} to be refused with {@code reason}, naming {@code node} when one is given. */
    static void expect(ServerPlayerEntity player, String what, Attempt attempt, RejectReason reason, Perm node) {
        String who = player.getGameProfile().getName();
        try {
            attempt.run(player);
        } catch (EditRejected e) {
            check(e.reason() == reason, what + " for " + who + ": expected " + reason + ", got " + e.reason() + " "
                    + e.getMessage());
            if (node != null) {
                check(e.getMessage() != null && e.getMessage().contains(node.node()), what + " for " + who
                        + ": refused at " + e.getMessage() + ", expected " + node.node());
            }
            return;
        }
        throw new GameTestException(what + " for " + who + " was not refused (expected " + reason + ")");
    }

    /** One command block with its command, as an untrusted tile (neither server-captured nor sanitized). */
    static Clipboard commandBlockClipboard(Harness h) {
        return Clipboard.builder(h.runtime.states(), new BlockPos(1, 1, 1))
                .set(0, 0, 0, h.state("minecraft:command_block[facing=up]"))
                .setTile(0, 0, 0, dev.sculptory.core.nbt.BlockEntityNbt.toNbtBytes("minecraft:command_block",
                        dev.sculptory.core.nbt.NbtCompound.builder().putString("Command", "say hi").build()))
                .build();
    }

    /** A clipboard: a {@code size}-block cube of {@code state}, anchored at its minimum corner. */
    static Clipboard cube(Harness h, int size, String state) {
        Clipboard.Builder builder = Clipboard.builder(h.runtime.states(), new BlockPos(size, size, size));
        int handle = h.state(state);
        for (int dx = 0; dx < size; dx++) {
            for (int dy = 0; dy < size; dy++) {
                for (int dz = 0; dz < size; dz++) builder.set(dx, dy, dz, handle);
            }
        }
        return builder.build();
    }

    static void paint(BlockWriter writer, Box box, int state) {
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) writer.write(x, y, z, state, null);
            }
        }
    }

    static long count(ServerWorld world, Box box, Block block) {
        long n = 0;
        net.minecraft.util.math.BlockPos.Mutable at = new net.minecraft.util.math.BlockPos.Mutable();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (world.getBlockState(at.set(x, y, z)).isOf(block)) n++;
                }
            }
        }
        return n;
    }

    /** Every 16³ section of {@code box} holds {@code block} everywhere or nowhere, {@code total} cells in all. */
    static void checkWholeSections(ServerWorld world, Box box, Block block, long total) {
        long sum = 0;
        for (int sy = box.min().y() >> 4; sy <= box.max().y() >> 4; sy++) {
            for (int sx = box.min().x() >> 4; sx <= box.max().x() >> 4; sx++) {
                for (int sz = box.min().z() >> 4; sz <= box.max().z() >> 4; sz++) {
                    Box section = box(sx << 4, sy << 4, sz << 4, (sx << 4) + 15, (sy << 4) + 15, (sz << 4) + 15);
                    long n = count(world, section, block);
                    check(n == 0 || n == 4096, "section " + sx + "," + sy + "," + sz + " is partly written: " + n);
                    sum += n;
                }
            }
        }
        check(sum == total, "written " + sum + ", expected " + total);
    }

    /** A copy of {@code base} with every cell of {@code box} inside it set to {@code state} (and no block entity). */
    static WorldSnapshot with(WorldSnapshot base, Box box, int state) {
        WorldSnapshot copy = new WorldSnapshot(base.box, base.states.clone());
        copy.tiles.putAll(base.tiles);
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (!base.box.contains(x, y, z)) continue;
                    copy.states[copy.index(x, y, z)] = state;
                    copy.tiles.remove(net.minecraft.util.math.BlockPos.asLong(x, y, z));
                }
            }
        }
        return copy;
    }
}
