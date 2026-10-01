package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.executor;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.fill;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.historyStep;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.refusal;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.run;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.tickUntil;

import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.config.UnloadedPolicy;
import dev.sculptory.fabric.engine.ChunkPermit;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.JobListener;
import dev.sculptory.fabric.engine.JobResult;
import dev.sculptory.fabric.engine.JobTicket;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.HistoryService;
import dev.sculptory.fabric.engine.impl.JobRequest;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.border.WorldBorder;

/**
 * Undo anyway and Redo anyway ({@code EngineEditService.historyOverwrite}): after a run of
 * undo (or redo) steps that kept another player's blocks, re-applying the run with OVERWRITE leaves the world exactly as
 * undoing (redoing) with OVERWRITE from the start does (a second region runs the same script that way and must match
 * cell for cell, block entities included), moves no history entry, and later redos and undos still work. Refusals write
 * nothing (an open stroke is committed first, as for undo); a cancelled overwrite reports what it wrote and finishes
 * exactly when run again; a protected column is left alone and counted. A private executor ticked by the test keeps
 * every step deterministic. GameTest regions 520-534.
 */
public final class HistoryOverwriteGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int TICKS = 64;

    // ---------------------------------------------------------------- exactness

    /**
     * Alice fills A, then B over part of A (after Bob built on a cell of B that A never touched), then C over part of
     * B. Bob then changes a cell of A only, of A and B, of B and C, of C only and of B only, and swaps the chest A buried
     * for a chest of his own. Alice undoes C, B and A, keeping all eight conflicting cells; Undo anyway of the three
     * equals the three undone with OVERWRITE in a twin region (the last entry of the run decides a shared cell: the cell
     * Bob built before B holds his block again). Redoing and undoing the three afterwards meets no conflict in either.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_undo_anyway", tickLimit = LIMIT)
    public void undoAnywayOfARunEqualsTheRunUndoneWithOverwrite(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        ServerPlayerEntity twin = h.addPlayer();
        ServerPlayerEntity twinBob = h.addPlayer();
        int[] a = regionCorner(context, 520);
        int[] b = regionCorner(context, 521);
        Scenario anyway = new Scenario(h, executor, alice, bob, a[0], a[1]);
        Scenario direct = new Scenario(h, executor, twin, twinBob, b[0], b[1]);
        for (Scenario s : List.of(anyway, direct)) s.undoSetup();

        long kept = 0;
        for (int i = 0; i < 3; i++) kept += step(h, executor, alice, true).skippedConflicts();
        check(kept == 8, "the undos kept " + kept + " cells, expected 8");
        HistoryService.Run run = h.service.historyService().session(alice.getUuid()).run().orElseThrow();
        check(run.entries().size() == 3 && run.conflicts() == 8 && run.op() == HistoryService.Op.UNDO, "run " + run);
        WorldSnapshot beforeOverwrite = capture(h.world, anyway.region);
        JobResult result = overwrite(h, executor, alice, false, 3);
        check(result.outcome() == JobOutcome.COMPLETED && result.changed() == 6 && result.skippedConflicts() == 0
                && result.skippedProtected() == 0, "Undo anyway " + result);
        check(EditTestSupport.difference(beforeOverwrite, capture(h.world, anyway.region)) != null,
                "Undo anyway changed nothing");
        check(h.history().undoLabels().isEmpty() && h.history().redoLabels().size() == 3, "the position moved: " + h.history());

        for (int i = 0; i < 3; i++) {
            RecordingListener undo = new RecordingListener();
            try {
                h.service.undo(twin, ConflictPolicy.OVERWRITE, undo);
            } catch (EditRejected e) {
                throw new GameTestException("the twin's undo refused: " + e.getMessage());
            }
            tickUntil(executor, () -> undo.result != null, TICKS, "the twin's undo " + i);
        }
        WorldSnapshot reapplied = capture(h.world, anyway.region);
        sameRelative(capture(h.world, direct.region), reapplied, "Undo anyway against the run undone with OVERWRITE");
        check(h.world.getBlockState(anyway.at(28, 110, 5)).isOf(Blocks.DIAMOND_BLOCK),
                "the cell Bob built before B holds his diamond block again");
        checkChest(h.world, anyway.at(2, 101, 2), Items.DIAMOND, 5);

        // The entries still work: redo and undo the three in both regions, conflict-free, and the regions stay equal.
        for (boolean undo : List.of(false, true)) {
            for (int i = 0; i < 3; i++) {
                check(step(h, executor, alice, undo).skippedConflicts() == 0, (undo ? "undo " : "redo ") + i + " after");
                check(step(h, executor, twin, undo).skippedConflicts() == 0, "the twin's step " + i);
            }
            sameRelative(capture(h.world, direct.region), capture(h.world, anyway.region), undo ? "undone again" : "redone");
        }
        checkSame(reapplied, capture(h.world, anyway.region), "redo then undo returns to Undo anyway's result");
        anyway.close();
        direct.close();
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * Alice fills A and B (overlapping), undoes both; Bob then builds in A only, in A and B, in B only, and replaces
     * the chest with a barrel. Alice's redos keep five cells; Redo anyway equals the two redone with OVERWRITE in a twin
     * region, and undoing both afterwards is conflict-free and gives the original world back in both.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_redo_anyway", tickLimit = LIMIT)
    public void redoAnywayOfARunEqualsTheRunRedoneWithOverwrite(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        ServerPlayerEntity twin = h.addPlayer();
        ServerPlayerEntity twinBob = h.addPlayer();
        int[] a = regionCorner(context, 522);
        int[] b = regionCorner(context, 523);
        Scenario anyway = new Scenario(h, executor, alice, bob, a[0], a[1]);
        Scenario direct = new Scenario(h, executor, twin, twinBob, b[0], b[1]);
        for (Scenario s : List.of(anyway, direct)) s.redoSetup();

        long kept = 0;
        for (int i = 0; i < 2; i++) kept += step(h, executor, alice, false).skippedConflicts();
        check(kept == 5, "the redos kept " + kept + " cells, expected 5");
        JobResult result = overwrite(h, executor, alice, true, 2);
        check(result.outcome() == JobOutcome.COMPLETED && result.changed() == 4 && result.skippedConflicts() == 0,
                "Redo anyway " + result);
        check(h.history().undoLabels().size() == 2 && h.history().redoLabels().isEmpty(), "the position moved");
        for (int i = 0; i < 2; i++) {
            RecordingListener redo = new RecordingListener();
            try {
                h.service.redo(twin, ConflictPolicy.OVERWRITE, redo);
            } catch (EditRejected e) {
                throw new GameTestException("the twin's redo refused: " + e.getMessage());
            }
            tickUntil(executor, () -> redo.result != null, TICKS, "the twin's redo " + i);
        }
        sameRelative(capture(h.world, direct.region), capture(h.world, anyway.region),
                "Redo anyway against the run redone with OVERWRITE");
        check(h.world.getBlockState(anyway.at(20, 110, 5)).isOf(Blocks.GOLD_BLOCK), "B, the last entry, decides A and B's cell");
        check(h.world.getBlockEntity(anyway.at(2, 101, 2)) == null, "the barrel went with A's stone");

        for (int i = 0; i < 2; i++) {
            check(step(h, executor, alice, true).skippedConflicts() == 0, "undo " + i + " after Redo anyway");
            check(step(h, executor, twin, true).skippedConflicts() == 0, "the twin's undo " + i);
        }
        sameRelative(anyway.original, capture(h.world, anyway.region), "Alice's undos restore the original");
        sameRelative(direct.original, capture(h.world, direct.region), "the twin's undos restore the original");
        checkChest(h.world, anyway.at(2, 101, 2), Items.DIAMOND, 5);
        anyway.close();
        direct.close();
        executor.shutdown();
        h.close();
        context.complete();
    }

    // ---------------------------------------------------------------- refusals

    /**
     * Refusals change nothing: the other direction, another step count, an undo still running (QUEUE_FULL), a push
     * since the steps (the run ended), steps that kept nothing, a job of the player's still running, and a player without
     * {@code use}. While an overwrite runs, undo is refused and the player's new edit waits to be pushed after it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_anyway_refusals", tickLimit = LIMIT)
    public void undoAnywayIsRefusedWhenItsRunDoesNotMatch(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        ServerPlayerEntity carol = h.addPlayer(false);
        int[] at = regionCorner(context, 524);
        int x = at[0], z = at[1];
        Box region = box(x, 100, z, x + 31, 111, z + 15);
        loadAndForce(h.world, region);
        Box layers = box(x, 100, z, x + 15, 111, z + 15);
        WorldSnapshot original = capture(h.world, layers);
        fillBy(h, executor, alice, box(x, 101, z, x + 15, 101, z + 15), "minecraft:stone");
        fillBy(h, executor, alice, box(x, 102, z, x + 15, 102, z + 15), "minecraft:glass");
        fillBy(h, executor, bob, box(x + 3, 101, z + 3, x + 3, 102, z + 3), "minecraft:emerald_block");
        check(step(h, executor, alice, true).skippedConflicts() == 1, "the undo of the glass kept Bob's block");

        WorldSnapshot now = capture(h.world, region);
        expectInvalid(h, alice, true, 1, "the history changed since those redo steps");
        expectInvalid(h, alice, false, 2, "the history changed since those undo steps");
        checkSame(now, capture(h.world, region), "refusals change nothing");

        RecordingListener undo = historyStep(h, alice, true);
        EditRejected busy = refusal(() -> h.service.historyOverwrite(alice, false, 1, null));
        check(busy.reason() == RejectReason.QUEUE_FULL, "during an undo: " + busy);
        tickUntil(executor, () -> undo.result != null, TICKS, "the second undo");
        check(undo.result.skippedConflicts() == 1, "undo " + undo.result);

        RecordingListener anyway = new RecordingListener();
        overwriteAdmitted(h, alice, false, 2, anyway);
        EditRejected during = refusal(() -> h.service.undo(alice, ConflictPolicy.SKIP_CONFLICTS, null));
        check(during.reason() == RejectReason.QUEUE_FULL, "undo during Undo anyway: " + during);
        EditRejected twice = refusal(() -> h.service.historyOverwrite(alice, false, 2, null));
        check(twice.reason() == RejectReason.QUEUE_FULL, "a second Undo anyway: " + twice);
        RecordingListener later = new RecordingListener();
        run(h, alice, fill(h, box(x + 20, 101, z, x + 31, 101, z + 15), "minecraft:gold_block"), later);
        tickUntil(executor, () -> anyway.result != null && later.result != null, TICKS, "Undo anyway and the new fill");
        check(anyway.result.outcome() == JobOutcome.COMPLETED && anyway.result.changed() == 2, "Undo anyway " + anyway.result);
        checkSame(original, capture(h.world, layers), "the two undone layers");
        check(h.history().undoLabels().size() == 1 && h.history().undoLabels().get(0).startsWith("Fill · 192"),
                "the fill made during Undo anyway was pushed after it: " + h.history());
        expectInvalid(h, alice, false, 2, "the history changed since those undo steps");

        check(step(h, executor, alice, true).skippedConflicts() == 0, "a clean undo");
        expectInvalid(h, alice, false, 1, "those steps kept no changed blocks");

        RecordingListener big = new RecordingListener();
        fillBy(h, executor, bob, box(x + 20, 105, z, x + 20, 105, z), "minecraft:emerald_block");
        fillBy(h, executor, alice, box(x + 20, 105, z, x + 31, 105, z + 15), "minecraft:stone");
        fillBy(h, executor, bob, box(x + 21, 105, z, x + 21, 105, z), "minecraft:diamond_block");
        check(step(h, executor, alice, true).skippedConflicts() == 1, "undo with Bob's diamond");
        run(h, alice, fill(h, box(x + 20, 108, z, x + 31, 111, z + 15), "minecraft:stone"), big);
        EditRejected running = refusal(() -> h.service.historyOverwrite(alice, false, 1, null));
        check(running.reason() == RejectReason.QUEUE_FULL && running.getMessage().contains("job is still running"),
                "while a job of hers runs: " + running);
        tickUntil(executor, () -> big.result != null, TICKS, "the fill");
        expectInvalid(h, alice, false, 1, "the history changed since those undo steps");

        EditRejected denied = refusal(() -> h.service.historyOverwrite(carol, false, 1, null));
        check(denied.reason() == RejectReason.NO_PERMISSION, "without use: " + denied);
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    // ---------------------------------------------------------------- partial failure

    /**
     * An Undo anyway over 32 sections, each holding one of Bob's blocks, is cancelled after its first section: it
     * reports the one block it wrote, the history does not move, and the run is still offered. Running it again writes
     * the other 31 and leaves every cell as the fill found it; a redo then meets no conflict.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_anyway_cancel", tickLimit = LIMIT)
    public void aCancelledUndoAnywayReportsWhatItWroteAndFinishesWhenRunAgain(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        int[] at = regionCorner(context, 526);
        int x = at[0], z = at[1];
        Box region = box(x, 112, z, x + 63, 143, z + 63); // 32 sections
        loadAndForce(h.world, region);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        for (int dx = 0; dx < 64; dx += 5) writer.write(x + dx, 112 + dx % 30, z + dx, h.state("minecraft:dirt"), null);
        WorldSnapshot original = capture(h.world, region);
        fillBy(h, executor, alice, region, "minecraft:stone");
        WorldSnapshot filled = capture(h.world, region);
        int emerald = h.state("minecraft:emerald_block");
        for (int sx = 0; sx < 4; sx++) {
            for (int sz = 0; sz < 4; sz++) {
                for (int sy = 0; sy < 2; sy++) writer.write(x + 16 * sx + 7, 112 + 16 * sy + 7, z + 16 * sz + 7, emerald, null);
            }
        }
        fillBy(h, executor, bob, box(x + 8, 120, z + 8, x + 8, 120, z + 8), "minecraft:emerald_block");
        check(step(h, executor, alice, true).skippedConflicts() == 33, "the undo kept Bob's 33 blocks");

        UUID[] id = new UUID[1];
        boolean[] cancelled = {false};
        RecordingListener first = new RecordingListener();
        JobListener cancelOnApply = new JobListener() {
            @Override
            public void progress(UUID job, long done, long total, Phase ph) {
                first.progress(job, done, total, ph);
                // APPLY comes just before the first section; the cancel takes effect after it.
                if (ph == Phase.APPLY && !cancelled[0]) cancelled[0] = h.service.cancel(alice, id[0]);
            }

            @Override
            public void finished(JobResult r) {
                first.finished(r);
            }
        };
        id[0] = overwriteAdmitted(h, alice, false, 1, cancelOnApply).jobId();
        tickUntil(executor, () -> first.result != null, TICKS, "the cancelled Undo anyway");
        check(cancelled[0] && first.result.outcome() == JobOutcome.CANCELLED, "Undo anyway " + first.result);
        check(first.result.changed() >= 1 && first.result.changed() <= 2, "one section's blocks: " + first.result);
        check(h.history().redoLabels().size() == 1 && h.history().undoLabels().isEmpty(), "the history did not move");
        check(h.service.historyService().session(alice.getUuid()).run().isPresent(), "the run is still offered");

        JobResult again = overwrite(h, executor, alice, false, 1);
        check(again.outcome() == JobOutcome.COMPLETED && again.changed() == 33 - first.result.changed(),
                "the rest " + again);
        checkSame(original, capture(h.world, region), "Undo anyway, finished");
        check(step(h, executor, alice, false).skippedConflicts() == 0, "the redo after it");
        checkSame(filled, capture(h.world, region), "redone");
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * A builder with only {@code use} (and {@code region} for the fill) undoes a layer Bob built on in two chunks.
     * Then the world border moves into chunk B: Undo anyway writes the cells it may and leaves the one beyond the
     * border, counting it as protected; the run is resolved (a second one is refused).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_anyway_protected", tickLimit = LIMIT)
    public void undoAnywayLeavesAProtectedColumnAndCountsIt(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        ServerPlayerEntity bob = h.addPlayer();
        int[] at = regionCorner(context, 527);
        int x = at[0], z = at[1];
        Box region = box(x, 100, z, x + 31, 110, z + 15);
        Box loaded = box(x - 16, 90, z - 16, x + 47, 120, z + 31);
        loadAndForce(h.world, loaded);
        WorldSnapshot original = capture(h.world, region);
        fillBy(h, executor, builder, box(x, 105, z, x + 31, 105, z + 15), "minecraft:glass");
        for (int dx : new int[] {5, 20, 28}) {
            fillBy(h, executor, bob, box(x + dx, 105, z + 5, x + dx, 105, z + 5), "minecraft:emerald_block");
        }
        EditTestSupport.grant(builder, Perm.USE);
        check(step(h, executor, builder, true).skippedConflicts() == 3, "the undo kept Bob's three blocks");
        WorldBorder border = h.world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(x + 24 - 100_000, z);
            border.setSize(200_000);
            check(!h.world.canPlayerModifyAt(builder, pos(x + 28, 105, z + 5)), "x+28 is not protected");
            check(h.world.canPlayerModifyAt(builder, pos(x + 20, 105, z + 5)), "x+20 is protected");
            JobResult result = overwrite(h, executor, builder, false, 1);
            check(result.outcome() == JobOutcome.COMPLETED && result.changed() == 2 && result.skippedProtected() == 1,
                    "Undo anyway " + result);
            check(h.world.getBlockState(pos(x + 28, 105, z + 5)).isOf(Blocks.EMERALD_BLOCK), "the protected cell changed");
            BlockPos kept = pos(x + 28, 105, z + 5);
            WorldSnapshot expected = MultiplayerGameTest.with(original, box(kept.getX(), 105, kept.getZ(), kept.getX(), 105,
                    kept.getZ()), h.state("minecraft:emerald_block"));
            checkSame(expected, capture(h.world, region), "all but the protected cell");
            expectInvalid(h, builder, false, 1, "the history changed since those undo steps");
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        forceChunks(h.world, loaded, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    // ---------------------------------------------------------------- review follow-ups (regions 528-534)

    /**
     * An open brush stroke is committed before Undo anyway is judged, as before an undo: its queued dabs are applied and
     * pushed, which is a change to the history, so the Undo anyway of the undo made before the stroke is refused. The
     * dabs are kept (written and in the history), and nothing else is written.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_anyway_stroke", tickLimit = LIMIT)
    public void anOpenStrokeIsCommittedFirstSoUndoAnywayIsRefusedAndTheDabsKept(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        int[] at = regionCorner(context, 528);
        int x = at[0], z = at[1];
        Box region = box(x, 99, z, x + 31, 111, z + 15);
        loadAndForce(h.world, region);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        MultiplayerGameTest.paint(writer, box(x, 100, z, x + 31, 100, z + 15), h.state("minecraft:stone"));
        fillBy(h, executor, alice, box(x, 105, z, x + 15, 105, z + 15), "minecraft:glass");
        fillBy(h, executor, bob, box(x + 3, 105, z + 3, x + 3, 105, z + 3), "minecraft:emerald_block");
        check(step(h, executor, alice, true).skippedConflicts() == 1, "the undo kept Bob's block");

        Box dabArea = box(x + 20, 99, z + 4, x + 28, 111, z + 12);
        WorldSnapshot beforeDab = capture(h.world, dabArea);
        WorldSnapshot glassLayer = capture(h.world, box(x, 105, z, x + 15, 105, z + 15));
        MultiplayerGameTest.begin(h, alice, 1);
        check(h.service.dabs(alice, 1, 1, List.of(EditTestSupport.dab(0, x + 24, 100, z + 8))).accepted(),
                "the dab was refused");
        check(EditTestSupport.difference(beforeDab, capture(h.world, dabArea)) == null, "the dab is still queued");
        expectInvalid(h, alice, false, 1, "the history changed since those undo steps");
        check(EditTestSupport.difference(beforeDab, capture(h.world, dabArea)) != null, "the dab was not applied");
        check(h.history().undoLabels().size() == 1 && h.history().undoLabels().get(0).startsWith("Raise stroke"),
                "the stroke is in the history: " + h.history());
        checkSame(glassLayer, capture(h.world, box(x, 105, z, x + 15, 105, z + 15)), "the refusal wrote nothing else");
        h.service.endStroke(alice, 1);
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * A builder loses {@code use} while their Undo anyway runs: the permission re-check cancels it after the section it
     * is writing (it needs only {@code use}, like undo). The history does not move and the run is still there; with
     * {@code use} back, Undo anyway again finishes it exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_anyway_revoke", tickLimit = LIMIT)
    public void losingUseCancelsUndoAnywayAndTheRunIsKeptForARetry(TestContext context) {
        EditExecutor executor = executor(context, 1024);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        ServerPlayerEntity bob = h.addPlayer();
        int[] at = regionCorner(context, 529);
        int x = at[0], z = at[1];
        Box region = box(x, 112, z, x + 15, 159, z + 15); // three sections
        loadAndForce(h.world, region);
        WorldSnapshot original = capture(h.world, region);
        fillBy(h, executor, builder, region, "minecraft:stone");
        fillBy(h, executor, bob, region, "minecraft:gold_block");
        check(step(h, executor, builder, true).skippedConflicts() == region.volume(), "the undo kept all of Bob's blocks");

        RecordingListener anyway = new RecordingListener();
        overwriteAdmitted(h, builder, false, 1, anyway);
        executor.tick();
        check(anyway.result == null && anyway.phases.contains(Phase.APPLY), "Undo anyway is not a quarter through");
        EditTestSupport.grant(builder);
        check(h.service.revalidate(builder) == 1, "Undo anyway was not cancelled");
        tickUntil(executor, () -> anyway.result != null, TICKS, "the cancelled Undo anyway");
        check(anyway.result.outcome() == JobOutcome.CANCELLED && anyway.result.changed() == 4096,
                "Undo anyway " + anyway.result);
        HistoryService.Session session = h.service.historyService().session(builder.getUuid());
        check(!session.busy() && session.run().map(run -> run.entries().size()).orElse(0) == 1, "the run is gone");
        check(h.service.history(builder).redoLabels().size() == 1 && h.service.history(builder).undoLabels().isEmpty(),
                "the history moved");
        EditRejected denied = refusal(() -> h.service.historyOverwrite(builder, false, 1, null));
        check(denied.reason() == RejectReason.NO_PERMISSION, "Undo anyway without use: " + denied);

        EditTestSupport.grant(builder, Perm.USE);
        JobResult again = overwrite(h, executor, builder, false, 1);
        check(again.outcome() == JobOutcome.COMPLETED && again.changed() == region.volume() - 4096, "again " + again);
        checkSame(original, capture(h.world, region), "finished exactly");
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * The executor's own refusals of an Undo anyway (a full wait queue, more chunk columns than one job may take,
     * unloaded chunks under the REFUSE policy) come before the overwrite begins: nothing is written, nothing is in
     * flight, and the run is kept for another try. The runs are made directly in the history service (entries recorded
     * over cells, one undo step each), so no world has to change for them.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_anyway_executor", tickLimit = LIMIT)
    public void theExecutorsRefusalsKeepTheRun(TestContext context) {
        EditExecutor executor = new EditExecutor(context.getWorld().getServer(), EngineTestSupport.runtime(context).states(),
                new EditExecutor.Settings(200_000_000L, 0, 0.4, 2, 8, 1, 64, UnloadedPolicy.REFUSE, 1024, 1));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        ServerPlayerEntity carol = h.addPlayer();
        int[] at = regionCorner(context, 530);
        int x = at[0], z = at[1];
        Box loaded = box(x, 100, z, x + 47, 101, z + 15);
        loadAndForce(h.world, loaded);
        HistoryService history = h.service.historyService();
        // Alice: two steps in two chunk columns; Bob: one step in a chunk nobody loaded.
        HistoryService.Session a = history.session(alice.getUuid());
        manufacturedRun(h, a, new int[][] {{x + 1, z + 1}, {x + 33, z + 1}});
        int[] far = regionCorner(context, 531);
        HistoryService.Session b = history.session(bob.getUuid());
        manufacturedRun(h, b, new int[][] {{far[0] + 5, far[1] + 5}});
        check(!WorldChecks.isChunkLoaded(h.world, far[0] >> 4, far[1] >> 4), "the far chunk is loaded");

        RecordingListener waiting = new RecordingListener();
        run(h, carol, fill(h, box(x, 101, z, x, 101, z), "minecraft:stone"), waiting);
        EditRejected full = refusal(() -> h.service.historyOverwrite(alice, false, 2, null));
        check(full.reason() == RejectReason.QUEUE_FULL, "with the wait queue full: " + full);
        keptRun(a, 2, "QUEUE_FULL");
        tickUntil(executor, () -> waiting.result != null, TICKS, "Carol's fill");
        WorldSnapshot untouched = capture(h.world, loaded);

        EditRejected large = refusal(() -> h.service.historyOverwrite(alice, false, 2, null));
        check(large.reason() == RejectReason.TOO_LARGE, "two columns over a cap of one: " + large);
        keptRun(a, 2, "TOO_LARGE");

        EditRejected unloaded = refusal(() -> h.service.historyOverwrite(bob, false, 1, null));
        check(unloaded.reason() == RejectReason.UNLOADED, "in an unloaded chunk: " + unloaded);
        keptRun(b, 1, "UNLOADED");
        checkSame(untouched, capture(h.world, loaded), "the refusals wrote nothing");
        forceChunks(h.world, loaded, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * Redo anyway restores a block entity from the after-states: Alice moves a chest of 5 diamonds 10 blocks east,
     * undoes it, and Bob puts stone where the chest had gone. Her redo keeps his stone (and empties the source);
     * Redo anyway puts the chest with its diamonds there. Undoing the move afterwards meets no conflict and brings the
     * chest back to where it started.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_redo_anyway_tile", tickLimit = LIMIT)
    public void redoAnywayRestoresABlockEntityOfTheAfterStates(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        int[] at = regionCorner(context, 533);
        int x = at[0], z = at[1];
        Box region = box(x, 100, z, x + 15, 103, z + 15);
        loadAndForce(h.world, region);
        BlockPos source = pos(x + 2, 101, z + 2);
        BlockPos target = pos(x + 12, 101, z + 2);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        writer.write(source.getX(), source.getY(), source.getZ(), h.state("minecraft:chest[facing=east]"), null);
        ((ChestBlockEntity) h.world.getBlockEntity(source)).setStack(0, new ItemStack(Items.DIAMOND, 5));
        RecordingListener move = new RecordingListener();
        run(h, alice, new OpSpec.Move(box(source.getX(), 101, source.getZ(), source.getX(), 101, source.getZ()),
                new dev.sculptory.core.BlockPos(10, 0, 0), Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air"))),
                move);
        tickUntil(executor, () -> move.result != null, TICKS, "the move");
        checkChest(h.world, target, Items.DIAMOND, 5);
        check(step(h, executor, alice, true).skippedConflicts() == 0, "a clean undo of the move");
        checkChest(h.world, source, Items.DIAMOND, 5);
        fillBy(h, executor, bob, box(target.getX(), 101, target.getZ(), target.getX(), 101, target.getZ()), "minecraft:stone");

        check(step(h, executor, alice, false).skippedConflicts() == 1, "the redo kept Bob's stone");
        check(h.world.getBlockState(target).isOf(Blocks.STONE) && h.world.getBlockState(source).isAir(), "after the redo");
        JobResult anyway = overwrite(h, executor, alice, true, 1);
        check(anyway.outcome() == JobOutcome.COMPLETED && anyway.changed() == 1, "Redo anyway " + anyway);
        checkChest(h.world, target, Items.DIAMOND, 5);
        check(h.world.getBlockState(source).isAir(), "the source stays empty");

        check(step(h, executor, alice, true).skippedConflicts() == 0, "undo after Redo anyway");
        checkChest(h.world, source, Items.DIAMOND, 5);
        check(h.world.getBlockState(target).isAir(), "the target is empty again");
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * A claim-style permit that allows only part of a chunk's columns ({@code ChunkPermit.Columns}, which a claims mod
     * produces through {@code canPlayerModifyAt}; a GameTest can set up no claims, so the permit is given to the
     * executor directly, as ExecutorGameTest does) applies to the Undo anyway program as to any job: of Bob's three
     * blocks the undo kept, the one in a denied column stays and is counted as protected, the other two are restored.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_history_anyway_columns", tickLimit = LIMIT)
    public void aClaimStyleColumnPermitLeavesItsColumnDuringUndoAnyway(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        ServerPlayerEntity bob = h.addPlayer();
        int[] at = regionCorner(context, 534);
        int x = at[0], z = at[1];
        Box region = box(x, 104, z, x + 31, 106, z + 15);
        loadAndForce(h.world, region);
        WorldSnapshot original = capture(h.world, region);
        fillBy(h, executor, alice, box(x, 105, z, x + 31, 105, z + 15), "minecraft:glass");
        for (int dx : new int[] {3, 12, 20}) {
            fillBy(h, executor, bob, box(x + dx, 105, z + 5, x + dx, 105, z + 5), "minecraft:emerald_block");
        }
        check(step(h, executor, alice, true).skippedConflicts() == 3, "the undo kept Bob's three blocks");
        List<HistoryEntry> run =
                h.service.historyService().session(alice.getUuid()).run().orElseThrow().entries();
        long[] westHalf = new long[4];
        for (int bit = 0; bit < 256; bit++) {
            if ((bit & 15) < 8) westHalf[bit >>> 6] |= 1L << bit;
        }
        int cx = x >> 4, cz = z >> 4;
        RecordingListener listener = new RecordingListener();
        try {
            executor.submit(JobRequest.system(h.world, HistoryPrograms.reapply(run, false), listener).withPermits(
                    (px, pz) -> px == cx && pz == cz ? new ChunkPermit.Columns(westHalf) : ChunkPermit.ALLOW));
        } catch (EditRejected e) {
            throw new GameTestException("Undo anyway refused: " + e.getMessage());
        }
        tickUntil(executor, () -> listener.result != null, TICKS, "Undo anyway under the permit");
        check(listener.result.outcome() == JobOutcome.COMPLETED && listener.result.changed() == 2
                && listener.result.skippedProtected() == 1, "Undo anyway " + listener.result);
        WorldSnapshot expected = MultiplayerGameTest.with(original, box(x + 12, 105, z + 5, x + 12, 105, z + 5),
                h.state("minecraft:emerald_block"));
        checkSame(expected, capture(h.world, region), "all but the denied column");
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * A run of undo steps made in the history service alone: one entry per cell (x, 100, z) recording air to stone,
     * all pushed, then undone newest first as steps that kept 5 blocks each. The world is not touched.
     */
    private static void manufacturedRun(Harness h, HistoryService.Session session, int[][] cells) {
        HistoryService history = h.service.historyService();
        for (int[] cell : cells) {
            RecordBuilder record = new RecordBuilder();
            record.record(cell[0], 100, cell[1], h.state("minecraft:air"), null, h.state("minecraft:stone"), null);
            history.push(session, new HistoryEntry(UUID.randomUUID(), session.player(),
                    h.world.getRegistryKey().getValue().toString(), "Made", record.build(), System.currentTimeMillis()));
        }
        for (int i = 0; i < cells.length; i++) {
            history.begin(session, HistoryService.Op.UNDO, history.candidate(session, HistoryService.Op.UNDO).orElseThrow());
            history.finish(session, true, true, 5);
        }
    }

    private static void keptRun(HistoryService.Session session, int steps, String after) {
        check(!session.busy(), "an operation is in flight after " + after);
        check(session.run().map(run -> run.entries().size()).orElse(0) == steps, "the run is gone after " + after);
    }

    // ---------------------------------------------------------------- helpers

    /** One region's script, run the same way for the player under test and for a twin. */
    private static final class Scenario {
        final Harness h;
        final EditExecutor executor;
        final ServerPlayerEntity alice;
        final ServerPlayerEntity bob;
        final int x;
        final int z;
        final Box region;
        WorldSnapshot original;

        Scenario(Harness h, EditExecutor executor, ServerPlayerEntity alice, ServerPlayerEntity bob, int x, int z) {
            this.h = h;
            this.executor = executor;
            this.alice = alice;
            this.bob = bob;
            this.x = x;
            this.z = z;
            this.region = box(x, 100, z, x + 31, 131, z + 15);
        }

        BlockPos at(int dx, int y, int dz) {
            return pos(x + dx, y, z + dz);
        }

        Box cell(int dx, int y, int dz) {
            return box(x + dx, y, z + dz, x + dx, y, z + dz);
        }

        /** Dirt ground and a chest of 5 diamonds, then remembers the region. */
        private void ground() {
            loadAndForce(h.world, region);
            BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
            int dirt = h.state("minecraft:dirt");
            for (int dx = 0; dx < 32; dx++) {
                for (int dz = 0; dz < 16; dz++) writer.write(x + dx, 100, z + dz, dirt, null);
            }
            BlockPos chest = at(2, 101, 2);
            writer.write(chest.getX(), chest.getY(), chest.getZ(), h.state("minecraft:chest[facing=east]"), null);
            ((ChestBlockEntity) h.world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 5));
            original = capture(h.world, region);
        }

        void undoSetup() {
            ground();
            fillBy(h, executor, alice, box(x, 100, z, x + 23, 115, z + 15), "minecraft:stone");      // A
            fillBy(h, executor, bob, cell(28, 110, 5), "minecraft:diamond_block");                   // in B, not in A
            fillBy(h, executor, alice, box(x + 16, 108, z, x + 31, 123, z + 15), "minecraft:gold_block"); // B
            fillBy(h, executor, alice, box(x + 8, 120, z + 4, x + 27, 131, z + 11), "minecraft:glass");   // C
            fillBy(h, executor, bob, cell(1, 101, 1), "minecraft:emerald_block");   // A only
            fillBy(h, executor, bob, cell(20, 110, 5), "minecraft:emerald_block");  // A and B
            fillBy(h, executor, bob, cell(18, 121, 6), "minecraft:emerald_block");  // B and C
            fillBy(h, executor, bob, cell(9, 125, 8), "minecraft:emerald_block");   // C only
            fillBy(h, executor, bob, cell(28, 110, 5), "minecraft:emerald_block");  // B only
            ownChest(Items.OAK_LOG);
        }

        void redoSetup() {
            ground();
            fillBy(h, executor, alice, box(x, 100, z, x + 23, 115, z + 15), "minecraft:stone");      // A
            fillBy(h, executor, alice, box(x + 16, 108, z, x + 31, 123, z + 15), "minecraft:gold_block"); // B
            for (int i = 0; i < 2; i++) check(step(h, executor, alice, true).skippedConflicts() == 0, "clean undo " + i);
            checkSame(original, capture(h.world, region), "both undone");
            fillBy(h, executor, bob, cell(1, 101, 1), "minecraft:emerald_block");   // A only
            fillBy(h, executor, bob, cell(20, 110, 5), "minecraft:emerald_block");  // A and B
            fillBy(h, executor, bob, cell(28, 110, 5), "minecraft:emerald_block");  // B only
            BlockPos chest = at(2, 101, 2);
            BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
            writer.write(chest.getX(), chest.getY(), chest.getZ(), h.state("minecraft:barrel[facing=up]"), null);
            ((LootableContainerBlockEntity) h.world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.APPLE, 9));
        }

        /** Bob replaces what stands at the chest's cell with a chest of his own (same state, other contents). */
        private void ownChest(net.minecraft.item.Item item) {
            BlockPos chest = at(2, 101, 2);
            BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
            writer.write(chest.getX(), chest.getY(), chest.getZ(), h.state("minecraft:chest[facing=east]"), null);
            ((ChestBlockEntity) h.world.getBlockEntity(chest)).setStack(3, new ItemStack(item, 7));
        }

        void close() {
            forceChunks(h.world, region, false);
        }
    }

    /** A fill by {@code player}, run to its end. */
    private static void fillBy(Harness h, EditExecutor executor, ServerPlayerEntity player, Box box, String state) {
        RecordingListener listener = new RecordingListener();
        run(h, player, fill(h, box, state), listener);
        tickUntil(executor, () -> listener.result != null, TICKS, "a fill");
        check(listener.result.outcome() == JobOutcome.COMPLETED, "fill " + listener.result);
    }

    /** One undo (or redo) step of {@code player} skipping conflicts, run to its end. */
    private static JobResult step(Harness h, EditExecutor executor, ServerPlayerEntity player, boolean undo) {
        RecordingListener listener = historyStep(h, player, undo);
        tickUntil(executor, () -> listener.result != null, TICKS, undo ? "an undo" : "a redo");
        check(listener.result.outcome() == JobOutcome.COMPLETED, "step " + listener.result);
        return listener.result;
    }

    private static JobTicket overwriteAdmitted(Harness h, ServerPlayerEntity player, boolean redo, int steps,
                                               JobListener listener) {
        try {
            return h.service.historyOverwrite(player, redo, steps, listener);
        } catch (EditRejected e) {
            throw new GameTestException((redo ? "Redo" : "Undo") + " anyway refused: " + e.getMessage());
        }
    }

    /** Undo anyway (or Redo anyway) of {@code steps} steps, run to its end. */
    private static JobResult overwrite(Harness h, EditExecutor executor, ServerPlayerEntity player, boolean redo, int steps) {
        RecordingListener listener = new RecordingListener();
        overwriteAdmitted(h, player, redo, steps, listener);
        tickUntil(executor, () -> listener.result != null, TICKS, redo ? "Redo anyway" : "Undo anyway");
        return listener.result;
    }

    private static void expectInvalid(Harness h, ServerPlayerEntity player, boolean redo, int steps, String detail) {
        EditRejected e = refusal(() -> h.service.historyOverwrite(player, redo, steps, null));
        check(e.reason() == RejectReason.INVALID && Objects.equals(e.detail(), detail)
                && e.kind().equals(EditRejected.HISTORY_RUN),
                (redo ? "Redo" : "Undo") + " anyway of " + steps + ": expected \"" + detail + "\", got " + e.getMessage());
    }

    private static void checkChest(ServerWorld world, BlockPos pos, net.minecraft.item.Item item, int count) {
        check(world.getBlockEntity(pos) instanceof ChestBlockEntity, "no chest at " + pos.toShortString());
        ItemStack stack = ((ChestBlockEntity) world.getBlockEntity(pos)).getStack(0);
        check(stack.isOf(item) && stack.getCount() == count, "the chest holds " + stack);
    }

    /** The two regions (same size) hold the same states and block entities, cell for cell. */
    private static void sameRelative(WorldSnapshot expected, WorldSnapshot actual, String what) {
        check(expected.box.sizeX() == actual.box.sizeX() && expected.box.sizeY() == actual.box.sizeY()
                && expected.box.sizeZ() == actual.box.sizeZ(), what + ": regions of different sizes");
        if (!Arrays.equals(expected.states, actual.states)) {
            int diffs = 0;
            int first = -1;
            for (int i = 0; i < expected.states.length; i++) {
                if (expected.states[i] != actual.states[i]) {
                    diffs++;
                    if (first < 0) first = i;
                }
            }
            throw new GameTestException(what + ": " + diffs + " cells differ, first at index " + first);
        }
        check(relativeTiles(expected).equals(relativeTiles(actual)), what + ": block entities differ: "
                + relativeTiles(expected) + " / " + relativeTiles(actual));
    }

    private static Map<BlockPos, NbtCompound> relativeTiles(WorldSnapshot snapshot) {
        Map<BlockPos, NbtCompound> tiles = new java.util.TreeMap<>();
        for (Map.Entry<Long, NbtCompound> entry : snapshot.tiles.entrySet()) {
            BlockPos p = BlockPos.fromLong(entry.getKey());
            tiles.put(new BlockPos(p.getX() - snapshot.box.min().x(), p.getY() - snapshot.box.min().y(),
                    p.getZ() - snapshot.box.min().z()), entry.getValue());
        }
        return tiles;
    }
}
