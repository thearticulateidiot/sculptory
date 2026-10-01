package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.history.store.HistoryCodec;
import dev.sculptory.core.history.store.HistoryStore;
import dev.sculptory.core.history.store.StorageIo;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HistoryService} saving to a {@link HistoryStore}: a restart
 * gives each player the same stacks and labels, loading defers pushes, a player who leaves keeps their history,
 * offline histories count toward the global cap and the age limit, records being built are saved so a crash leaves
 * them undoable, and a journal that fell out of sync is rewritten from memory.
 */
class HistoryServicePersistenceTest {
    private static final UUID P1 = new UUID(0, 1);
    private static final UUID P2 = new UUID(0, 2);
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final HistoryCodec CODEC = HistoryCodec.of(STATES, 3955);
    private static final int AIR = STATES.state("minecraft:air");
    private static final int STONE = STATES.state("minecraft:stone");
    private static final int DIRT = STATES.state("minecraft:dirt");

    @TempDir
    Path dir;

    private final List<HistoryStore> stores = new ArrayList<>();
    private final AtomicLong wallClock = new AtomicLong(1_000_000_000_000L);
    private final AtomicLong nanos = new AtomicLong(0);
    private final List<String> log = new ArrayList<>();
    private final HistoryService.Listener listener = new HistoryService.Listener() {
        @Override
        public void changed(UUID player) {
            log.add("changed " + player.getLeastSignificantBits());
        }

        @Override
        public void evicted(UUID player, int steps, boolean includesNewest) {
            log.add("evicted " + player.getLeastSignificantBits() + " " + steps);
        }
    };

    @AfterEach
    void closeStores() {
        for (HistoryStore store : stores) store.close(5000);
    }

    private HistoryStore store(StorageIo io) throws IOException {
        HistoryStore store = HistoryStore.open(dir, CODEC, HistoryStore.Settings.DEFAULTS, io, HistoryStore.Log.NONE);
        stores.add(store);
        return store;
    }

    private HistoryService service(HistoryLimits limits, StorageIo io, long maxDisk, long maxAgeMillis)
            throws IOException {
        return new HistoryService(limits, listener,
                new HistoryService.Persistence(store(io), maxDisk, maxAgeMillis, wallClock::get), nanos::get);
    }

    private HistoryService service(HistoryLimits limits) throws IOException {
        return service(limits, StorageIo.SYSTEM, 1L << 40, 0);
    }

    /** A clean stop: journals written and the store closed. */
    private static void stop(HistoryService h) {
        h.clearAll();
        assertTrue(h.closeStore(10_000));
    }

    /** A crash: what was queued reaches the operating system, nothing else is done. */
    private static void crash(HistoryService h) {
        assertTrue(h.store().orElseThrow().flush(10_000));
    }

    private static void pollUntil(HistoryService h, BooleanSupplier done, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            h.poll();
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** The player's session, loaded. */
    private static HistoryService.Session loaded(HistoryService h, UUID player) {
        HistoryService.Session session = h.session(player);
        pollUntil(h, () -> !session.loading(), "the history of " + player);
        return session;
    }

    private static HistoryEntry entry(UUID owner, String label, long created, int cells) {
        RecordBuilder builder = new RecordBuilder();
        for (int i = 0; i < cells; i++) builder.record(i, 64, i % 5, AIR, null, i % 2 == 0 ? STONE : DIRT, null);
        return new HistoryEntry(builder.id(), owner, "minecraft:overworld", label, builder.build(), created);
    }

    private static List<String> labels(List<HistoryEntry> entries) {
        return entries.stream().map(HistoryEntry::label).toList();
    }

    private static List<UUID> ids(List<HistoryEntry> entries) {
        return entries.stream().map(HistoryEntry::id).toList();
    }

    private static void assertSameStacks(HistoryService expected, HistoryService actual, UUID player) {
        assertEquals(ids(expected.undoEntries(player)), ids(actual.undoEntries(player)), "undo stack");
        assertEquals(ids(expected.redoEntries(player)), ids(actual.redoEntries(player)), "redo stack");
        assertEquals(labels(expected.undoEntries(player)), labels(actual.undoEntries(player)));
        assertEquals(labels(expected.redoEntries(player)), labels(actual.redoEntries(player)));
        assertEquals(expected.snapshot(player).bytes(), actual.snapshot(player).bytes(), "bytes as first measured");
        List<HistoryEntry> before = new ArrayList<>(expected.undoEntries(player));
        before.addAll(expected.redoEntries(player));
        List<HistoryEntry> after = new ArrayList<>(actual.undoEntries(player));
        after.addAll(actual.redoEntries(player));
        for (int i = 0; i < before.size(); i++) {
            assertEquals(before.get(i).world(), after.get(i).world());
            assertEquals(before.get(i).createdMillis(), after.get(i).createdMillis());
            assertSameBuffer(before.get(i).record().before(), after.get(i).record().before());
            assertSameBuffer(before.get(i).record().after(), after.get(i).record().after());
        }
    }

    private static void assertSameBuffer(BlockBuffer expected, BlockBuffer actual) {
        assertEquals(expected.cellCount(), actual.cellCount());
        for (long key : expected.sortedKeys()) {
            SectionBuffer a = expected.section(key), b = actual.section(key);
            for (int i = 0; i < SectionBuffer.SIZE; i++) assertEquals(a.get(i), b == null ? -1 : b.get(i));
        }
    }

    // ------------------------------------------------------------------------------------------------ restarts

    @Test
    void aRestartGivesEachPlayerTheSameStacksAndLabels() throws IOException {
        HistoryLimits limits = new HistoryLimits(4, Long.MAX_VALUE, Long.MAX_VALUE);
        HistoryService first = service(limits);
        HistoryService.Session s1 = loaded(first, P1), s2 = loaded(first, P2);
        for (int i = 0; i < 6; i++) first.push(s1, entry(P1, "one " + i, 10 + i, 3 + i)); // two evicted
        first.push(s2, entry(P2, "two a", 30, 5));
        first.push(s2, entry(P2, "two b", 31, 6));
        HistoryEntry undone = first.candidate(s1, HistoryService.Op.UNDO).orElseThrow();
        first.begin(s1, HistoryService.Op.UNDO, undone);
        first.finish(s1, true);
        HistoryEntry redone = first.candidate(s2, HistoryService.Op.UNDO).orElseThrow();
        first.begin(s2, HistoryService.Op.UNDO, redone);
        first.finish(s2, true);
        first.begin(s2, HistoryService.Op.REDO, redone);
        first.finish(s2, true);
        // A snapshot of what the first run holds, kept for comparison after it stops.
        HistoryService expected = new HistoryService(limits, null);
        copy(first, expected, P1);
        copy(first, expected, P2);
        stop(first);

        HistoryService second = service(limits);
        loaded(second, P1);
        loaded(second, P2);
        assertSameStacks(expected, second, P1);
        assertSameStacks(expected, second, P2);
        assertEquals(List.of("one 4", "one 3", "one 2"), labels(second.undoEntries(P1)));
        assertEquals(List.of("one 5"), labels(second.redoEntries(P1)));
    }

    /** Copies a player's stacks into a memory-only service (same entries, same order and position). */
    private static void copy(HistoryService from, HistoryService to, UUID player) {
        HistoryService.Session target = to.session(player);
        List<HistoryEntry> undo = from.undoEntries(player);
        for (int i = undo.size() - 1; i >= 0; i--) to.push(target, undo.get(i));
        List<HistoryEntry> redo = from.redoEntries(player);
        for (HistoryEntry e : redo) to.push(target, e);
        for (int i = redo.size() - 1; i >= 0; i--) {
            to.begin(target, HistoryService.Op.UNDO, redo.get(i));
            to.finish(target, true);
        }
    }

    /** While a history loads, pushes wait (then clear its redo side as any new edit) and the client sees it busy. */
    @Test
    void loadingDefersPushes() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(first, P1);
        HistoryEntry a = entry(P1, "a", 1, 4), b = entry(P1, "b", 2, 4);
        first.push(s, a);
        first.push(s, b);
        first.begin(s, HistoryService.Op.UNDO, b);
        first.finish(s, true);
        stop(first);

        HistoryService second = service(HistoryLimits.DEFAULTS);
        HistoryService.Session again = second.session(P1);
        assertTrue(again.loading() && again.busy());
        assertTrue(second.snapshot(P1).busy());
        second.editStarted(again);
        HistoryEntry c = entry(P1, "c", 3, 4);
        second.editFinished(again, c);
        assertEquals(1, again.deferredCount());
        pollUntil(second, () -> !again.loading(), "the load");
        assertEquals(List.of("c", "a"), labels(second.undoEntries(P1)), "the new edit landed on the loaded history");
        assertTrue(second.redoEntries(P1).isEmpty(), "and cleared its redo side");
        stop(second);
        HistoryService third = service(HistoryLimits.DEFAULTS);
        loaded(third, P1);
        assertEquals(List.of("c", "a"), labels(third.undoEntries(P1)));
    }

    /** A player who leaves keeps their history: it waits for their running job, is unloaded, and comes back whole. */
    @Test
    void leavingKeepsTheHistory() throws IOException {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(h, P1);
        h.push(s, entry(P1, "a", 1, 4));
        h.editStarted(s);
        h.clear(P1);
        assertFalse(s.closed(), "kept while the job runs");
        assertTrue(h.find(P1).isPresent());
        h.editFinished(s, entry(P1, "job", 2, 6));
        assertTrue(s.closed(), "unloaded once the job pushed");
        assertTrue(h.find(P1).isEmpty());
        assertEquals(2, h.offline(P1).orElseThrow().size());
        assertTrue(h.totalBytes() > 0, "offline histories count");
        HistoryService.Session back = loaded(h, P1);
        assertFalse(back.closed());
        assertEquals(List.of("job", "a"), labels(h.undoEntries(P1)));
        assertTrue(h.offline(P1).isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ fluid trails

    /** What an entry's fluid did: grass under it at y 63 turned to dirt, and water flowed to a cell beside it. */
    private static dev.sculptory.core.history.EditRecord trail(int x) {
        RecordBuilder builder = new RecordBuilder();
        builder.record(x, 63, 0, STONE, null, DIRT, null);
        builder.record(x + 40, 64, 0, AIR, null, STONE, null); // another chunk column
        return builder.build();
    }

    /**
     * A trail folded into a held entry between steps (the chunk-save hook):
     * toward before for a done entry, toward after for an undone one; journaled, so a restart reads the grown entries
     * back. The size the client is shown does not change (a fold is not an eviction); the counted size grows.
     */
    @Test
    void aTrailFoldedBetweenStepsIsJournaledAndReadBackAfterARestart() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(first, P1);
        HistoryEntry done = entry(P1, "done", 1, 4);
        HistoryEntry undone = entry(P1, "undone", 2, 4);
        first.push(s, done);
        first.push(s, undone);
        first.begin(s, HistoryService.Op.UNDO, undone);
        first.finish(s, true);
        long shown = first.snapshot(P1).bytes();
        long counted = first.totalBytes();
        assertTrue(first.foldable(done.id()) && first.foldable(undone.id()));
        assertEquals(P1, first.foldTrail(done.id(), trail(0)));
        assertEquals(P1, first.foldTrail(undone.id(), trail(8)));
        assertEquals(shown, first.snapshot(P1).bytes(), "the client's size leaves folds out");
        assertTrue(first.totalBytes() > counted, "the caps count the folded trails");
        HistoryEntry grownDone = first.undoEntries(P1).get(0);
        HistoryEntry grownUndone = first.redoEntries(P1).get(0);
        assertEquals(STONE, grownDone.record().before().get(0, 63, 0), "toward before: the grass comes back");
        assertEquals(DIRT, grownDone.record().after().get(0, 63, 0));
        assertEquals(DIRT, grownUndone.record().before().get(8, 63, 0), "toward after: the redo takes it back");
        assertEquals(STONE, grownUndone.record().after().get(8, 63, 0));
        assertTrue(s.run().isPresent(), "a fold does not end the run of undo steps");
        HistoryService expected = new HistoryService(HistoryLimits.DEFAULTS, null);
        copy(first, expected, P1);
        crash(first); // no stop: what the folds journaled is all there is

        HistoryService second = service(HistoryLimits.DEFAULTS);
        loaded(second, P1);
        assertEquals(ids(expected.undoEntries(P1)), ids(second.undoEntries(P1)));
        assertEquals(ids(expected.redoEntries(P1)), ids(second.redoEntries(P1)));
        assertSameBuffer(grownDone.record().before(), second.undoEntries(P1).get(0).record().before());
        assertSameBuffer(grownDone.record().after(), second.undoEntries(P1).get(0).record().after());
        assertSameBuffer(grownUndone.record().before(), second.redoEntries(P1).get(0).record().before());
        assertSameBuffer(grownUndone.record().after(), second.redoEntries(P1).get(0).record().after());
        assertTrue(second.totalBytes() >= first.totalBytes(), "sealed again with its grown size");
    }

    /**
     * No trail is folded into an entry while a step of its player is in flight (its fluid is held still, and the step
     * replaces the entry itself) or into an entry no loaded history holds; nothing changes then.
     */
    @Test
    void aTrailIsNotFoldedWhileAStepRunsOrIntoAnEntryNotHeld() throws IOException {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(h, P1);
        HistoryEntry a = entry(P1, "a", 1, 4);
        h.push(s, a);
        h.begin(s, HistoryService.Op.UNDO, a);
        assertFalse(h.foldable(a.id()));
        assertEquals(null, h.foldTrail(a.id(), trail(0)));
        assertTrue(h.undoEntries(P1).get(0) == a, "the entry was not replaced");
        h.finish(s, true);
        assertFalse(h.foldable(UUID.randomUUID()), "an entry nobody holds");
        assertEquals(null, h.foldTrail(UUID.randomUUID(), trail(0)));
    }

    /**
     * The trails of a player's entries are folded in before their history is unloaded (they left) and at a stop: the
     * next run reads them back.
     */
    @Test
    void trailsAreFoldedWhenAHistoryIsUnloadedAndAtAStop() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        java.util.Map<UUID, dev.sculptory.core.history.EditRecord> pending = new java.util.HashMap<>();
        first.trailSource(entry -> pending.remove(entry.id()));
        HistoryService.Session s1 = loaded(first, P1), s2 = loaded(first, P2);
        HistoryEntry left = entry(P1, "left", 1, 4);
        HistoryEntry stayed = entry(P2, "stayed", 2, 4);
        first.push(s1, left);
        first.push(s2, stayed);
        pending.put(left.id(), trail(0));
        pending.put(stayed.id(), trail(8));
        first.clear(P1);
        assertTrue(s1.closed(), "unloaded");
        assertFalse(pending.containsKey(left.id()), "its trail was taken first");
        stop(first);
        assertTrue(pending.isEmpty(), "every trail was taken at the stop");

        HistoryService second = service(HistoryLimits.DEFAULTS);
        loaded(second, P1);
        loaded(second, P2);
        assertEquals(DIRT, second.undoEntries(P1).get(0).record().after().get(0, 63, 0), "folded before unloading");
        assertEquals(DIRT, second.undoEntries(P2).get(0).record().after().get(8, 63, 0), "folded at the stop");
    }

    /** Offline histories count toward the global cap; their oldest entries go first, and the journal follows. */
    @Test
    void offlineHistoriesAreEvictedOldestFirst() throws IOException {
        HistoryEntry probe = entry(P1, "probe", 0, 50);
        long size = probe.record().estimatedBytes();
        HistoryLimits limits = new HistoryLimits(64, Long.MAX_VALUE, size * 5 + size / 2);
        HistoryService h = service(limits);
        HistoryService.Session s1 = loaded(h, P1);
        h.push(s1, entry(P1, "old 1", 1, 50));
        h.push(s1, entry(P1, "old 2", 2, 50));
        h.clear(P1);
        assertTrue(h.find(P1).isEmpty());
        HistoryService.Session s2 = loaded(h, P2);
        for (int i = 0; i < 4; i++) h.push(s2, entry(P2, "new " + i, 10 + i, 50));
        assertEquals(1, h.offline(P1).orElseThrow().size(), "P1's oldest entry made room");
        assertEquals("old 2", h.offline(P1).orElseThrow().entries().get(0).label());
        assertEquals(4, h.undoEntries(P2).size());
        stop(h);
        HistoryService after = service(limits);
        loaded(after, P1);
        assertEquals(List.of("old 2"), labels(after.undoEntries(P1)), "the eviction was saved");
    }

    /** Entries older than the age limit are dropped when a history loads (and journaled so). */
    @Test
    void theAgeLimitDropsOldSteps() throws IOException {
        long day = 86_400_000L;
        HistoryService h = service(HistoryLimits.DEFAULTS, StorageIo.SYSTEM, 1L << 40, 30 * day);
        HistoryService.Session s = loaded(h, P1);
        long now = wallClock.get();
        h.push(s, entry(P1, "ancient", now - 40 * day, 3));
        h.push(s, entry(P1, "recent", now - day, 3));
        stop(h);
        HistoryService after = service(HistoryLimits.DEFAULTS, StorageIo.SYSTEM, 1L << 40, 30 * day);
        loaded(after, P1);
        assertEquals(List.of("recent"), labels(after.undoEntries(P1)));
        stop(after);
        HistoryService third = service(HistoryLimits.DEFAULTS);
        loaded(third, P1);
        assertEquals(List.of("recent"), labels(third.undoEntries(P1)));
    }

    // ------------------------------------------------------------------------------------------------ crashes

    /**
     * A job's record is saved section by section as it writes (and the rest when a chunk is saved): a crash leaves
     * an "(interrupted)" entry holding exactly the cells written, which undoes like any other.
     */
    @Test
    void aJobInterruptedByACrashIsUndoable() throws IOException {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(h, P1);
        RecordBuilder builder = new RecordBuilder();
        HistoryService.OpenRecord record = h.record(s, "minecraft:overworld", "Fill", () -> 5, builder);
        assertNotNull(record);
        RecordSink sink = h.sink(builder, record);
        h.editStarted(s, record);
        // Two sections finished, a third half written.
        for (int x = 0; x < 32; x++) sink.record(x, 0, 0, AIR, null, STONE, null);
        sink.sectionFinished(BlockBuffer.keyOfBlock(0, 0, 0));
        sink.sectionFinished(BlockBuffer.keyOfBlock(16, 0, 0));
        for (int x = 32; x < 40; x++) sink.record(x, 0, 0, AIR, null, DIRT, null);
        assertFalse(h.saveOpen("minecraft:overworld", 2, 0).isEmpty(), "the chunk about to be saved had unsaved cells");
        assertTrue(h.saveOpen("minecraft:overworld", 2, 0).isEmpty(), "and has none now");
        assertTrue(h.barrier(2, 0, java.util.Set.of(P1)));
        crash(h);

        HistoryService after = service(HistoryLimits.DEFAULTS);
        loaded(after, P1);
        List<HistoryEntry> undo = after.undoEntries(P1);
        assertEquals(1, undo.size());
        assertEquals("Fill (interrupted) · 40 blocks", undo.get(0).label());
        assertSameBuffer(builder.build().before(), undo.get(0).record().before());
        assertSameBuffer(builder.build().after(), undo.get(0).record().after());
    }

    /** A job that finished normally journals only the sections it had not saved yet, and restores whole. */
    @Test
    void aFinishedJobRestoresWhole() throws IOException {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(h, P1);
        RecordBuilder builder = new RecordBuilder();
        HistoryService.OpenRecord record = h.record(s, "minecraft:overworld", "Fill", () -> 5, builder);
        RecordSink sink = h.sink(builder, record);
        h.editStarted(s, record);
        for (int x = 0; x < 20; x++) sink.record(x, 0, 0, AIR, null, STONE, null);
        sink.sectionFinished(BlockBuffer.keyOfBlock(0, 0, 0));
        // The first section is written again after it was saved (a later pass of the job).
        sink.record(3, 0, 0, STONE, null, DIRT, null);
        HistoryEntry entry = new HistoryEntry(builder.id(), P1, "minecraft:overworld", "Fill · 20 blocks",
                builder.build(), 6);
        h.editFinished(s, entry, record);
        stop(h);
        HistoryService after = service(HistoryLimits.DEFAULTS);
        loaded(after, P1);
        HistoryEntry back = after.undoEntries(P1).get(0);
        assertEquals("Fill · 20 blocks", back.label());
        assertSameBuffer(entry.record().before(), back.record().before());
        assertSameBuffer(entry.record().after(), back.record().after());
        assertEquals(DIRT, back.record().after().get(3, 0, 0), "the later write of a saved section was saved too");
    }

    /** A redo whose job was running at the crash counts as redone (it may have written cells). */
    @Test
    void aRedoInFlightAtACrashCountsAsRedone() throws IOException {
        HistoryService h = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(h, P1);
        HistoryEntry a = entry(P1, "a", 1, 3);
        h.push(s, a);
        h.begin(s, HistoryService.Op.UNDO, a);
        h.finish(s, true);
        h.begin(s, HistoryService.Op.REDO, a);
        crash(h);
        HistoryService after = service(HistoryLimits.DEFAULTS);
        loaded(after, P1);
        assertEquals(List.of("a"), labels(after.undoEntries(P1)));
        assertTrue(after.redoEntries(P1).isEmpty());
    }

    // ------------------------------------------------------------------------------------------------ failures

    /** Writes fail for a while: the history stays in memory, and is saved again (rewritten) once writing works. */
    @Test
    void aJournalThatFellOutOfSyncIsRewrittenFromMemory() throws IOException {
        TestIo io = new TestIo();
        HistoryService h = service(HistoryLimits.DEFAULTS, io, 1L << 40, 0);
        HistoryService.Session s = loaded(h, P1);
        h.push(s, entry(P1, "a", 1, 3));
        assertTrue(h.store().orElseThrow().flush(5000));
        io.failing.set(true);
        h.push(s, entry(P1, "b", 2, 3));
        HistoryStore store = h.store().orElseThrow();
        pollUntil(h, () -> store.outOfSync(P1), "the failure");
        h.push(s, entry(P1, "c", 3, 3)); // not written while out of sync
        h.clear(P1);
        assertFalse(s.closed(), "an out-of-sync history is not unloaded");
        io.failing.set(false);
        nanos.addAndGet(HistoryService.REWRITE_RETRY_NANOS + 1);
        pollUntil(h, () -> !store.outOfSync(P1) && s.closed(), "the rewrite and the unload");
        stop(h);
        HistoryService after = service(HistoryLimits.DEFAULTS);
        loaded(after, P1);
        assertEquals(List.of("c", "b", "a"), labels(after.undoEntries(P1)));
    }


    /**
     * A load that takes too long (another program holds the file): the player edits and undoes meanwhile, the file is
     * never replaced from the empty history, and when it can be read the saved steps go underneath the new ones. The
     * journal missed the new steps while the file was held, so it is rewritten then, from memory holding every step;
     * after a restart all of them are there.
     */
    @Test
    void aLoadTimeoutKeepsTheFileAndMergesTheSavedStepsUnderTheNewOnes() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(first, P1);
        first.push(s, entry(P1, "old a", 1, 3));
        first.push(s, entry(P1, "old b", 2, 4));
        HistoryEntry undone = first.candidate(s, HistoryService.Op.UNDO).orElseThrow();
        first.begin(s, HistoryService.Op.UNDO, undone);
        first.finish(s, true); // "old b" on the redo side
        stop(first);
        Path file = dir.resolve(P1 + HistoryStore.EXTENSION);
        byte[] saved = Files.readAllBytes(file);

        TestIo io = new TestIo();
        io.failOpen = P1.toString();
        HistoryService second = service(HistoryLimits.DEFAULTS, io, 1L << 40, 0);
        HistoryService.Session again = second.session(P1);
        assertTrue(again.loading());
        nanos.addAndGet(HistoryService.LOAD_TIMEOUT_NANOS + 1);
        pollUntil(second, () -> !again.loading(), "the timeout");
        second.push(again, entry(P1, "new c", 3, 5));
        second.push(again, entry(P1, "new d", 4, 6));
        HistoryEntry d = second.candidate(again, HistoryService.Op.UNDO).orElseThrow();
        second.begin(again, HistoryService.Op.UNDO, d);
        second.finish(again, true);
        assertEquals(List.of("new c"), labels(second.undoEntries(P1)));
        second.clear(P1);
        assertFalse(again.closed(), "not unloaded before its saved steps are in");
        for (int i = 0; i < 20; i++) second.poll();
        assertArrayEquals(saved, Files.readAllBytes(file), "the held file was not replaced");
        io.failOpen = null;
        pollUntil(second, () -> again.closed(), "the merge, the rewrite and the unload");
        stop(second);
        HistoryService third = service(HistoryLimits.DEFAULTS);
        loaded(third, P1);
        // The saved redo step went with the new steps (a new edit drops the redo side); "new d" is still redoable.
        assertEquals(List.of("new c", "old a"), labels(third.undoEntries(P1)));
        assertEquals(List.of("new d"), labels(third.redoEntries(P1)));
    }

    /**
     * After the load timeout the player pushes x1 (its push reaches the file), and the load, reading the file again
     * after a failed read, finds x1 on the saved stack; then, before that load is merged, the player undoes x1 and
     * pushes x2, which drops x1. The merge must not bring x1 back from the load: memory and journal both hold
     * x2 over the saved step.
     */
    @Test
    void aLateLoadNeverBringsBackAStepDroppedMeanwhile() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(first, P1);
        first.push(s, entry(P1, "old a", 1, 3));
        stop(first);
        TestIo io = new TestIo();
        HistoryService second = service(HistoryLimits.DEFAULTS, io, 1L << 40, 0);
        pollUntil(second, () -> second.offline(P1).isPresent(), "the start-up scan");
        io.failReads.set(1_000); // the load keeps failing and reading the file again
        HistoryService.Session again = second.session(P1);
        nanos.addAndGet(HistoryService.LOAD_TIMEOUT_NANOS + 1);
        pollUntil(second, () -> !again.loading(), "the timeout");
        second.push(again, entry(P1, "new x1", 2, 4));
        assertTrue(second.store().orElseThrow().flush(5000), "x1's push is in the file");
        io.readGate = new java.util.concurrent.CountDownLatch(1);
        io.failReads.set(0);
        waitFor(() -> io.readsWaiting.get() > 0, "the load reading the file again, x1 included");
        HistoryEntry x1 = second.candidate(again, HistoryService.Op.UNDO).orElseThrow();
        second.begin(again, HistoryService.Op.UNDO, x1);
        second.finish(again, true);
        second.push(again, entry(P1, "new x2", 3, 5)); // x1 leaves the redo side
        io.readGate.countDown();
        pollUntil(second, () -> labels(second.undoEntries(P1)).contains("old a"), "the merge");
        assertEquals(List.of("new x2", "old a"), labels(second.undoEntries(P1)));
        assertTrue(second.redoEntries(P1).isEmpty());
        stop(second);
        HistoryService third = service(HistoryLimits.DEFAULTS);
        loaded(third, P1);
        assertEquals(List.of("new x2", "old a"), labels(third.undoEntries(P1)), "the journal agrees");
        assertTrue(third.redoEntries(P1).isEmpty());
    }

    /**
     * After the load timeout the player's only new step is too large to keep and is evicted at once: the session holds
     * nothing new, but the push happened (the journal dropped the saved redo side), so the merge drops it too.
     */
    @Test
    void aLateLoadDropsTheSavedRedoSideEvenWhenEveryNewStepWasEvicted() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(first, P1);
        first.push(s, entry(P1, "old a", 1, 3));
        first.push(s, entry(P1, "old b", 2, 4));
        HistoryEntry b = first.candidate(s, HistoryService.Op.UNDO).orElseThrow();
        first.begin(s, HistoryService.Op.UNDO, b);
        first.finish(s, true); // "old b" on the redo side
        stop(first);
        long small = entry(P1, "probe", 0, 4).record().estimatedBytes();
        HistoryEntry huge = entry(P1, "huge", 3, 800);
        HistoryLimits limits = new HistoryLimits(64, 4 * small, 1L << 30);
        assertTrue(huge.record().estimatedBytes() > limits.maxBytesPerPlayer(), "the new step cannot be kept");
        TestIo io = new TestIo();
        HistoryService second = service(limits, io, 1L << 40, 0);
        pollUntil(second, () -> second.offline(P1).isPresent(), "the start-up scan");
        io.readGate = new java.util.concurrent.CountDownLatch(1); // the load stalls
        HistoryService.Session again = second.session(P1);
        waitFor(() -> io.readsWaiting.get() > 0, "the load");
        nanos.addAndGet(HistoryService.LOAD_TIMEOUT_NANOS + 1);
        pollUntil(second, () -> !again.loading(), "the timeout");
        log.clear();
        second.push(again, huge);
        assertTrue(log.contains("evicted 1 1"), "" + log);
        assertTrue(second.undoEntries(P1).isEmpty());
        io.readGate.countDown();
        pollUntil(second, () -> !second.undoEntries(P1).isEmpty(), "the merge");
        assertEquals(List.of("old a"), labels(second.undoEntries(P1)));
        assertTrue(second.redoEntries(P1).isEmpty(), "the saved redo side went with the push");
        stop(second);
        HistoryService third = service(HistoryLimits.DEFAULTS);
        loaded(third, P1);
        assertEquals(List.of("old a"), labels(third.undoEntries(P1)), "the journal agrees");
        assertTrue(third.redoEntries(P1).isEmpty());
    }

    /** The late merge changes the history, so the player's run of undo steps (Undo anyway's offer) ends with it. */
    @Test
    void theLateMergeEndsTheRunOfUndoSteps() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(first, P1);
        first.push(s, entry(P1, "old a", 1, 3));
        stop(first);
        TestIo io = new TestIo();
        HistoryService second = service(HistoryLimits.DEFAULTS, io, 1L << 40, 0);
        pollUntil(second, () -> second.offline(P1).isPresent(), "the start-up scan");
        io.readGate = new java.util.concurrent.CountDownLatch(1);
        HistoryService.Session again = second.session(P1);
        waitFor(() -> io.readsWaiting.get() > 0, "the load");
        nanos.addAndGet(HistoryService.LOAD_TIMEOUT_NANOS + 1);
        pollUntil(second, () -> !again.loading(), "the timeout");
        second.push(again, entry(P1, "new x", 2, 4));
        HistoryEntry x = second.candidate(again, HistoryService.Op.UNDO).orElseThrow();
        second.begin(again, HistoryService.Op.UNDO, x);
        second.finish(again, true, true, 3);
        assertTrue(again.run().isPresent(), "an undo run with skipped conflicts");
        io.readGate.countDown();
        pollUntil(second, () -> !second.undoEntries(P1).isEmpty(), "the merge");
        assertTrue(again.run().isEmpty(), "the run ended");
        assertEquals(List.of("old a"), labels(second.undoEntries(P1)));
        assertEquals(List.of("new x"), labels(second.redoEntries(P1)));
    }

    private static void waitFor(BooleanSupplier done, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /**
     * After the load timeout the player pushes x1; its record in the file is then damaged, and the late load (reading the
     * file again after failed reads) finds x1 on the saved stack and cannot restore it. The merge must not evict x1: it
     * is a step memory holds whole, and evicting it would journal its loss. The journal is written anew from memory
     * instead, so after a restart x1 is there with the saved step.
     */
    @Test
    void aStepMadeWhileLoadingThatReadsBackDamagedIsNotEvicted() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(first, P1);
        first.push(s, entry(P1, "old a", 1, 3));
        stop(first);
        TestIo io = new TestIo();
        HistoryService second = service(HistoryLimits.DEFAULTS, io, 1L << 40, 0);
        pollUntil(second, () -> second.offline(P1).isPresent(), "the start-up scan");
        io.failReads.set(1_000); // the load keeps failing and reading the file again
        HistoryService.Session again = second.session(P1);
        nanos.addAndGet(HistoryService.LOAD_TIMEOUT_NANOS + 1);
        pollUntil(second, () -> !again.loading(), "the timeout");
        Path file = dir.resolve(P1 + HistoryStore.EXTENSION);
        HistoryStore store = second.store().orElseThrow();
        assertTrue(store.flush(5000));
        long x1Start = Files.size(file);
        second.push(again, entry(P1, "new x1", 2, 4));
        assertTrue(store.flush(5000), "x1 is in the file");
        damageFirstSection(file, x1Start);
        io.failReads.set(0);
        pollUntil(second, () -> labels(second.undoEntries(P1)).contains("old a"), "the merge");
        assertEquals(List.of("new x1", "old a"), labels(second.undoEntries(P1)), "x1 is kept in memory");
        pollUntil(second, () -> !store.rewriting(), "the journal written anew");
        stop(second);
        HistoryService third = service(HistoryLimits.DEFAULTS);
        loaded(third, P1);
        assertEquals(List.of("new x1", "old a"), labels(third.undoEntries(P1)), "the journal kept x1");
    }

    /** Flips a byte inside the first section record of the entry whose records start at {@code start}. */
    private static void damageFirstSection(Path file, long start) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int begin = 9 + ByteBuffer.wrap(bytes, (int) start, 4).getInt(); // a frame of 9 bytes, then the payload
        long at = start + begin + 9 + 20;
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(new byte[] {(byte) (bytes[(int) at] ^ 0x40)}), at);
        }
    }

    /**
     * As above, but another player's rewrite is running when P1's late load is merged: P2's journal fell out of sync and
     * P2's own late load, merged first in the same tick, started its rewrite. P1's journal cannot be rewritten at once;
     * it is marked out of sync and rewritten once P2's is done, so after a restart P1 still has x1.
     */
    @Test
    void aStepReadBackDamagedIsRewrittenLaterWhenAnotherRewriteRuns() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s1 = loaded(first, P1);
        HistoryService.Session s2 = loaded(first, P2);
        first.push(s1, entry(P1, "old a", 1, 3));
        first.push(s2, entry(P2, "old b", 1, 3));
        stop(first);
        TestIo io = new TestIo();
        HistoryService second = service(HistoryLimits.DEFAULTS, io, 1L << 40, 0);
        pollUntil(second, () -> second.offline(P1).isPresent() && second.offline(P2).isPresent(), "the scan");
        io.failReads.set(1_000); // both loads keep failing and reading the files again
        HistoryService.Session again1 = second.session(P1);
        HistoryService.Session again2 = second.session(P2);
        nanos.addAndGet(HistoryService.LOAD_TIMEOUT_NANOS + 1);
        pollUntil(second, () -> !again1.loading() && !again2.loading(), "the timeouts");
        HistoryStore store = second.store().orElseThrow();
        Path file1 = dir.resolve(P1 + HistoryStore.EXTENSION);
        assertTrue(store.flush(5000));
        long x1Start = Files.size(file1);
        second.push(again1, entry(P1, "new x1", 2, 4));
        assertTrue(store.flush(5000), "x1 is in the file");
        damageFirstSection(file1, x1Start);
        io.failing.set(true); // P2's next step cannot be written: P2's journal falls out of sync
        second.push(again2, entry(P2, "new y", 2, 4));
        assertTrue(store.flush(5000));
        io.failing.set(false);
        // Both loads go on; P2's arrives first (P1's reads x1 damaged three times, 1 s apart), and the service does not
        // look before P1's has arrived too: its copy of the damaged file is made right before it is reported.
        io.failReads.set(0);
        waitFor(() -> {
            try (var listing = Files.list(dir)) {
                return listing.anyMatch(p -> p.getFileName().toString().startsWith(P1 + HistoryStore.EXTENSION + ".")
                        && p.getFileName().toString().endsWith(".damaged"));
            } catch (IOException e) {
                return false;
            }
        }, "P1's load reading x1 damaged");
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        second.poll(); // merges P2 (starting its rewrite), then P1 while it runs
        assertEquals(List.of("new x1", "old a"), labels(second.undoEntries(P1)));
        assertTrue(store.outOfSync(P1) || store.rewriting(), "P1's journal is left to be rewritten");
        pollUntil(second, () -> !store.rewriting() && !store.outOfSync(P1) && !store.outOfSync(P2), "both rewrites");
        stop(second);
        HistoryService third = service(HistoryLimits.DEFAULTS);
        loaded(third, P1);
        assertEquals(List.of("new x1", "old a"), labels(third.undoEntries(P1)), "the journal kept x1");
        loaded(third, P2);
        assertEquals(List.of("new y", "old b"), labels(third.undoEntries(P2)));
    }

    /** A read that fails while a history loads is tried again: every saved step arrives, none is evicted. */
    @Test
    void aFailedReadNeverEvictsSavedSteps() throws IOException {
        HistoryService first = service(HistoryLimits.DEFAULTS);
        HistoryService.Session s = loaded(first, P1);
        first.push(s, entry(P1, "a", 1, 3));
        first.push(s, entry(P1, "b", 2, 4));
        stop(first);
        TestIo io = new TestIo();
        HistoryService second = service(HistoryLimits.DEFAULTS, io, 1L << 40, 0);
        pollUntil(second, () -> second.offline(P1).isPresent(), "the start-up scan");
        io.failReads.set(1); // the load's first read fails
        loaded(second, P1);
        assertEquals(List.of("b", "a"), labels(second.undoEntries(P1)));
        stop(second);
        HistoryService third = service(HistoryLimits.DEFAULTS);
        loaded(third, P1);
        assertEquals(List.of("b", "a"), labels(third.undoEntries(P1)));
    }

    /** Kept entries' journal bytes stay under half of maxDiskBytes: the oldest go first, across restarts. */
    @Test
    void theDiskCapEvictsTheOldestSteps() throws IOException {
        // One entry's journal size, measured with a probe (then its file is removed).
        HistoryService probe = service(HistoryLimits.DEFAULTS);
        HistoryService.Session p = loaded(probe, P2);
        probe.push(p, entry(P2, "probe", 1, 60));
        pollUntil(probe, () -> probe.diskBytes() > 0, "the probe's journal size");
        long one = probe.diskBytes();
        stop(probe);
        Files.delete(dir.resolve(P2 + HistoryStore.EXTENSION));
        long live = 3 * one + one / 2; // room for three entries
        HistoryService h = service(HistoryLimits.DEFAULTS, StorageIo.SYSTEM, 2 * live, 0);
        HistoryService.Session s = loaded(h, P1);
        for (int i = 0; i < 6; i++) {
            h.push(s, entry(P1, "e" + i, 10 + i, 60));
            assertTrue(h.store().orElseThrow().flush(5000));
            h.poll(); // the entry's journal size comes in, and the cap is enforced
        }
        assertTrue(h.diskBytes() <= live, "disk " + h.diskBytes() + " over " + live);
        assertEquals(List.of("e5", "e4", "e3"), labels(h.undoEntries(P1)));
        stop(h);
        HistoryService after = service(HistoryLimits.DEFAULTS);
        loaded(after, P1);
        assertEquals(List.of("e5", "e4", "e3"), labels(after.undoEntries(P1)), "the evictions were saved");
    }

    /**
     * A chunk save with a disk that does not keep up: the wait gives up after its limit, says so, and later chunk saves
     * do not wait for a while. A player whose journal is out of sync is reported for the chunks they changed.
     */
    @Test
    void theBarrierGivesUpOnASlowDiskAndReportsUnsavedPlayers() throws IOException {
        TestIo io = new TestIo();
        HistoryService h = service(HistoryLimits.DEFAULTS, io, 1L << 40, 0);
        HistoryService.Session s = loaded(h, P1);
        RecordBuilder builder = new RecordBuilder();
        HistoryService.OpenRecord record = h.record(s, "minecraft:overworld", "Fill", () -> 5, builder);
        RecordSink sink = h.sink(builder, record);
        h.editStarted(s, record);
        io.gate = new java.util.concurrent.CountDownLatch(1); // the disk stops answering
        for (int x = 0; x < 8; x++) sink.record(x, 0, 0, AIR, null, STONE, null);
        java.util.Set<UUID> touched = h.saveOpen("minecraft:overworld", 0, 0);
        long start = System.nanoTime();
        assertFalse(h.barrier(0, 0, touched), "the wait gave up");
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(2));
        long again = System.nanoTime();
        assertFalse(h.barrier(0, 0, touched), "and does not wait again for a while");
        assertTrue(System.nanoTime() - again < TimeUnit.MILLISECONDS.toNanos(50));
        io.gate.countDown();
        io.gate = null;
        assertTrue(h.store().orElseThrow().flush(5000));
        // A journal out of sync: its player's changes are reported, and the chunk counts as unprotected.
        io.failing.set(true);
        for (int x = 8; x < 12; x++) sink.record(x, 0, 0, AIR, null, STONE, null);
        HistoryStore store = h.store().orElseThrow();
        h.saveOpen("minecraft:overworld", 0, 0);
        pollUntil(h, () -> store.outOfSync(P1), "the failed write");
        for (int x = 12; x < 14; x++) sink.record(x, 0, 0, AIR, null, STONE, null);
        nanos.addAndGet(HistoryService.BARRIER_PAUSE_NANOS + 1);
        assertFalse(h.barrier(0, 0, h.saveOpen("minecraft:overworld", 0, 0)), "an out-of-sync player is unprotected");
        io.failing.set(false);
    }

    /**
     * Crash ordering, for real: the disk writes the journal only while the server thread waits, and the journal is
     * copied the moment a chunk save's barrier returns (what reached the operating system then). A new service over
     * the copy holds every cell the job wrote in that chunk. A barrier that did not wait would leave the copy without
     * them, whatever the machine's load (no clock decides it).
     */
    @Test
    void whatAChunkSaveWaitsForIsOnDiskWhenItReturns() throws IOException {
        TestIo io = new TestIo();
        HistoryService h = service(HistoryLimits.DEFAULTS, io, 1L << 40, 0);
        HistoryService.Session s = loaded(h, P1);
        RecordBuilder builder = new RecordBuilder();
        HistoryService.OpenRecord record = h.record(s, "minecraft:overworld", "Fill", () -> 5, builder);
        RecordSink sink = h.sink(builder, record);
        h.editStarted(s, record);
        io.writesOnlyWhileWaiting = Thread.currentThread();
        // Six sections of the chunk column (0, 0), written by the job; none journaled yet.
        for (int y = 0; y < 96; y++) {
            for (int x = 0; x < 4; x++) sink.record(x, y, 0, AIR, null, STONE, null);
        }
        java.util.Set<UUID> touched = h.saveOpen("minecraft:overworld", 0, 0);
        assertTrue(h.barrier(0, 0, touched), "the chunk's history was written in time");
        Path copy = dir.resolve("at-chunk-save"); // a folder the store never looks into
        Files.createDirectories(copy);
        Path journal = dir.resolve(P1 + HistoryStore.EXTENSION);
        Files.copy(journal, copy.resolve(journal.getFileName()));
        io.writesOnlyWhileWaiting = null;
        HistoryStore restored = HistoryStore.open(copy, CODEC, HistoryStore.Settings.DEFAULTS, StorageIo.SYSTEM,
                HistoryStore.Log.NONE);
        stores.add(restored);
        HistoryService after = new HistoryService(HistoryLimits.DEFAULTS, listener,
                new HistoryService.Persistence(restored, 1L << 40, 0, wallClock::get), nanos::get);
        loaded(after, P1);
        List<HistoryEntry> steps = after.undoEntries(P1);
        assertEquals(1, steps.size());
        assertEquals("Fill (interrupted) · 384 blocks", steps.get(0).label());
        assertSameBuffer(builder.build().before(), steps.get(0).record().before());
    }

    /** Without persistence nothing changes: leaving drops the history. */
    @Test
    void memoryOnlyServicesDropHistoryOnLeave() {
        HistoryService h = new HistoryService(HistoryLimits.DEFAULTS, listener);
        HistoryService.Session s = h.session(P1);
        assertFalse(s.loading());
        h.push(s, entry(P1, "a", 1, 3));
        h.clear(P1);
        assertTrue(s.closed());
        assertTrue(h.undoEntries(P1).isEmpty());
        assertFalse(h.persistent());
        assertTrue(h.barrier(0, 0, java.util.Set.of()));
        assertEquals("kept in memory only (history.persist is off)", h.storageStatus());
    }

    /** A {@link StorageIo} whose writes can fail, stall or be held back, and whose opens and reads fail or stall. */
    private static final class TestIo implements StorageIo {
        final AtomicBoolean failing = new AtomicBoolean();
        final java.util.concurrent.atomic.AtomicInteger failReads = new java.util.concurrent.atomic.AtomicInteger();
        volatile String failOpen;
        /** While set, every write waits for it. */
        volatile java.util.concurrent.CountDownLatch gate;
        /** While set, every read waits for it ({@link #readsWaiting} counts the waiting ones). */
        volatile java.util.concurrent.CountDownLatch readGate;
        final java.util.concurrent.atomic.AtomicInteger readsWaiting = new java.util.concurrent.atomic.AtomicInteger();
        /**
         * While set, writes to journal files happen only while this thread is not running (waiting in a barrier, a
         * flush, a sleep): whatever that thread does between two waits, the disk has not written yet. No clock is
         * involved, so a loaded machine changes nothing.
         */
        volatile Thread writesOnlyWhileWaiting;

        @Override
        public File open(Path file) throws IOException {
            String held = failOpen;
            if (held != null && file.getFileName().toString().contains(held)) {
                throw new java.nio.file.AccessDeniedException(file.toString(), null, "used by another process");
            }
            File inner = SYSTEM.open(file);
            boolean temp = file.toString().endsWith(".tmp");
            return new File() {
                @Override
                public long size() throws IOException {
                    return inner.size();
                }

                @Override
                public int read(ByteBuffer dst, long position) throws IOException {
                    java.util.concurrent.CountDownLatch latch = readGate;
                    if (latch != null) {
                        readsWaiting.incrementAndGet();
                        try {
                            latch.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        } finally {
                            readsWaiting.decrementAndGet();
                        }
                    }
                    if (failReads.get() > 0 && failReads.getAndDecrement() > 0) throw new IOException("read failed");
                    return inner.read(dst, position);
                }

                @Override
                public void write(ByteBuffer src, long position) throws IOException {
                    java.util.concurrent.CountDownLatch latch = gate;
                    try {
                        if (latch != null) latch.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        throw new IOException(e);
                    }
                    Thread waiter = writesOnlyWhileWaiting;
                    while (!temp && waiter != null && waiter.getState() == Thread.State.RUNNABLE) {
                        java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                        waiter = writesOnlyWhileWaiting;
                    }
                    if (failing.get()) throw new IOException("No space left on device");
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
            if (failing.get()) throw new IOException("rename failed");
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
}
