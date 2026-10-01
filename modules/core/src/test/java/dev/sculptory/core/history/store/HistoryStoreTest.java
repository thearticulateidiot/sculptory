package dev.sculptory.core.history.store;

import static dev.sculptory.core.history.store.StoreTestSupport.AIR;
import static dev.sculptory.core.history.store.StoreTestSupport.CODEC;
import static dev.sculptory.core.history.store.StoreTestSupport.DATA_VERSION;
import static dev.sculptory.core.history.store.StoreTestSupport.STATES;
import static dev.sculptory.core.history.store.StoreTestSupport.STONE;
import static dev.sculptory.core.history.store.StoreTestSupport.WORLD;
import static dev.sculptory.core.history.store.StoreTestSupport.assertSameRecord;
import static dev.sculptory.core.history.store.StoreTestSupport.await;
import static dev.sculptory.core.history.store.StoreTestSupport.close;
import static dev.sculptory.core.history.store.StoreTestSupport.entry;
import static dev.sculptory.core.history.store.StoreTestSupport.fill;
import static dev.sculptory.core.history.store.StoreTestSupport.journal;
import static dev.sculptory.core.history.store.StoreTestSupport.load;
import static dev.sculptory.core.history.store.StoreTestSupport.medium;
import static dev.sculptory.core.history.store.StoreTestSupport.open;
import static dev.sculptory.core.history.store.StoreTestSupport.record;
import static dev.sculptory.core.history.store.StoreTestSupport.sections;
import static dev.sculptory.core.history.store.StoreTestSupport.sparse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.core.history.PlayerHistory;
import dev.sculptory.core.history.RecordBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryStoreTest {
    private static final UUID ALICE = new UUID(0xA11CE, 1);
    private static final UUID BOB = new UUID(0xB0B, 2);

    @TempDir
    Path dir;

    private Path file(UUID player) {
        return dir.resolve(player + HistoryStore.EXTENSION);
    }

    private static List<UUID> ids(List<HistoryStore.LoadedEntry> entries) {
        List<UUID> ids = new ArrayList<>();
        for (HistoryStore.LoadedEntry e : entries) ids.add(e.id());
        return ids;
    }

    /** The model's entries oldest first, and how many are undoable. */
    private static List<UUID> ids(PlayerHistory history) {
        List<UUID> ids = new ArrayList<>();
        List<HistoryEntry> undo = history.undoEntries();
        for (int i = undo.size() - 1; i >= 0; i--) ids.add(undo.get(i).id());
        for (HistoryEntry e : history.redoEntries()) ids.add(e.id());
        return ids;
    }

    private static HistoryEntry find(PlayerHistory history, UUID id) {
        for (HistoryEntry e : history.undoEntries()) {
            if (e.id().equals(id)) return e;
        }
        for (HistoryEntry e : history.redoEntries()) {
            if (e.id().equals(id)) return e;
        }
        throw new AssertionError("no entry " + id);
    }

    private static void assertMatches(PlayerHistory model, HistoryStore.Loaded loaded) {
        assertEquals(ids(model), ids(loaded.entries()), "stack order");
        assertEquals(model.undoEntries().size(), loaded.applied(), "undoable entries");
        assertTrue(loaded.failed().isEmpty(), "failed: " + loaded.problems());
        for (HistoryStore.LoadedEntry e : loaded.entries()) {
            HistoryEntry expected = find(model, e.id());
            assertEquals(expected.label(), e.label());
            assertEquals(expected.world(), e.world());
            assertEquals(expected.createdMillis(), e.createdMillis());
            assertEquals(model.bytesOf(e.id()), e.bytes(), "bytes as first measured");
            assertTrue(e.diskBytes() > 0);
            assertSameRecord(expected.record(), e.record());
        }
    }

    // ------------------------------------------------------------------------------------------ round trips

    @Test
    void everyEntryKindSurvivesARestart() throws IOException {
        PlayerHistory model = new PlayerHistory(HistoryLimits.DEFAULTS);
        HistoryStore store = open(dir);
        List<HistoryEntry> entries = List.of(
                entry(ALICE, "Fill · 4,096 blocks", fill(0, 0, 0), 10),
                entry(ALICE, "Raise stroke · 41 blocks", sparse(1), 11),
                entry(ALICE, "Paste · 1,000 blocks", medium(), 12),
                entry(ALICE, "Move · 8,192 blocks", merge(fill(5, -1, 5), fill(-3, 2, 7)), 13),
                entry(ALICE, "Scatter · 3 placements · 41 blocks", sparse(2), 14),
                entry(ALICE, "Chest contents · 1 block", record(b -> b.record(9, 9, 9, StoreTestSupport.CHEST,
                        StoreTestSupport.tile("a"), StoreTestSupport.CHEST, StoreTestSupport.tile("b"))), 15));
        for (HistoryEntry e : entries) {
            model.push(e);
            journal(store, e);
        }
        // Two undone (the redo side), one redone again.
        for (int i = 0; i < 3; i++) {
            HistoryEntry e = model.undoCandidate().orElseThrow();
            model.markUndone(e.id());
            store.undone(ALICE, e.id());
        }
        HistoryEntry redo = model.redoCandidate().orElseThrow();
        store.redoBegin(ALICE, redo.id());
        model.markRedone(redo.id());
        store.redone(ALICE, redo.id());
        close(store);

        HistoryStore reopened = open(dir);
        HistoryStore.Scanned scanned = await(reopened, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        assertEquals(1, scanned.histories().size());
        StoredHistory stored = scanned.histories().get(0);
        assertEquals(ALICE, stored.player());
        assertEquals(6, stored.size());
        assertEquals(4, stored.applied());
        assertMatches(model, load(reopened, ALICE));
        assertTrue(load(reopened, BOB).entries().isEmpty(), "Bob has no history");
        close(reopened);
    }

    private static EditRecord merge(EditRecord a, EditRecord b) {
        BlockBuffer before = new BlockBuffer(), after = new BlockBuffer();
        for (EditRecord r : List.of(a, b)) {
            for (long key : r.before().sortedKeys()) {
                before.putSection(key, r.before().section(key));
                after.putSection(key, r.after().section(key));
            }
        }
        return new EditRecord(before, after);
    }

    /** Random edits, undos, redos and evictions for two players: after a restart both stacks load as they were. */
    @Test
    void randomHistoriesSurviveRestartsExactly() throws IOException {
        Random random = new Random(7);
        HistoryLimits limits = new HistoryLimits(12, 3_000_000, Long.MAX_VALUE);
        PlayerHistory alice = new PlayerHistory(limits), bob = new PlayerHistory(limits);
        for (int restart = 0; restart < 3; restart++) {
            HistoryStore store = open(dir);
            if (restart > 0) {
                assertMatches(alice, load(store, ALICE));
                assertMatches(bob, load(store, BOB));
            }
            for (int step = 0; step < 60; step++) {
                UUID player = random.nextBoolean() ? ALICE : BOB;
                PlayerHistory model = player == ALICE ? alice : bob;
                int op = random.nextInt(10);
                if (op < 5) {
                    EditRecord r = switch (random.nextInt(3)) {
                        case 0 -> fill(random.nextInt(8), random.nextInt(4), random.nextInt(8));
                        case 1 -> sparse(random.nextInt());
                        default -> medium();
                    };
                    HistoryEntry e = entry(player, "edit " + restart + "." + step, r, restart * 1000L + step);
                    List<HistoryEntry> evicted = model.push(e);
                    journal(store, e);
                    for (HistoryEntry gone : evicted) store.evict(player, gone.id());
                } else if (op < 7) {
                    model.undoCandidate().ifPresent(e -> {
                        model.markUndone(e.id());
                        store.undone(player, e.id());
                    });
                } else if (op < 9) {
                    model.redoCandidate().ifPresent(e -> {
                        store.redoBegin(player, e.id());
                        model.markRedone(e.id());
                        store.redone(player, e.id());
                    });
                } else {
                    model.evictOldest().ifPresent(e -> store.evict(player, e.id()));
                }
            }
            close(store);
        }
        HistoryStore last = open(dir);
        assertMatches(alice, load(last, ALICE));
        assertMatches(bob, load(last, BOB));
        close(last);
    }

    /** A player whose last entry goes has no file at all. */
    @Test
    void emptyHistoriesLeaveNoFile() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry e = entry(ALICE, "a", sparse(3), 1);
        journal(store, e);
        assertTrue(store.flush(5000));
        assertTrue(Files.exists(file(ALICE)));
        store.evict(ALICE, e.id());
        assertTrue(store.flush(5000));
        assertFalse(Files.exists(file(ALICE)));
        close(store);
    }

    // ------------------------------------------------------------------------------------------ crash recovery

    /**
     * A file cut at any byte of its last records (a crash while writing) loads as the records before the cut, is
     * truncated there, and takes new records that survive the next restart.
     */
    @Test
    void aTornTailIsDroppedAtEveryCut() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", fill(0, 0, 0), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(4), 2);
        journal(store, a);
        assertTrue(store.flush(5000));
        long afterA = Files.size(file(ALICE));
        journal(store, b);
        store.undone(ALICE, b.id());
        close(store);
        byte[] whole = Files.readAllBytes(file(ALICE));
        for (long cut = afterA; cut < whole.length; cut += Math.max(1, (whole.length - afterA) / 97)) {
            Files.write(file(ALICE), Arrays.copyOf(whole, (int) cut));
            HistoryStore reopened = open(dir);
            HistoryStore.Loaded loaded = load(reopened, ALICE);
            List<UUID> got = ids(loaded.entries());
            // b's push is the only record that can make b appear; before it only a is on the stack. A cut inside b's
            // data leaves b begun but unsealed: with cells it is restored as interrupted, which is also a push.
            assertTrue(got.equals(List.of(a.id())) || got.equals(List.of(a.id(), b.id())), "cut " + cut + ": " + got);
            assertSameRecord(a.record(), loaded.entries().get(0).record());
            HistoryEntry c = entry(ALICE, "c", sparse(5), 3);
            journal(reopened, c);
            close(reopened);
            HistoryStore again = open(dir);
            List<UUID> after = ids(load(again, ALICE).entries());
            assertEquals(c.id(), after.get(after.size() - 1), "the record after the cut survives");
            close(again);
            Files.write(file(ALICE), whole);
        }
    }

    @Test
    void aBadChecksumEndsTheReadable() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", sparse(6), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(7), 2);
        journal(store, a);
        assertTrue(store.flush(5000));
        long afterA = Files.size(file(ALICE));
        journal(store, b);
        close(store);
        byte[] bytes = Files.readAllBytes(file(ALICE));
        bytes[(int) afterA + 20] ^= 0x55; // inside b's begin record
        Files.write(file(ALICE), bytes);
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(List.of(a.id()), ids(loaded.entries()));
        close(reopened);
        assertEquals(afterA, Files.size(file(ALICE)), "the damaged tail was cut off");
    }

    @Test
    void shortOrForeignFilesNeverStopTheStore() throws IOException {
        Files.createDirectories(dir);
        Files.write(file(ALICE), new byte[] {'B', 'S'}); // shorter than a header
        Files.write(file(BOB), "not a journal at all, just some text in the wrong place".getBytes());
        Files.write(dir.resolve("notes.txt"), new byte[] {1, 2, 3});
        Files.write(dir.resolve(UUID.randomUUID() + HistoryStore.EXTENSION + ".tmp"), new byte[] {1});
        HistoryStore store = open(dir);
        await(store, HistoryStore.Scanned.class, s -> s.histories().isEmpty(), new ArrayList<>());
        assertTrue(load(store, ALICE).entries().isEmpty());
        assertTrue(load(store, BOB).entries().isEmpty());
        HistoryEntry e = entry(BOB, "fresh", sparse(8), 1);
        journal(store, e);
        close(store);
        try (var listing = Files.list(dir)) {
            assertTrue(listing.anyMatch(p -> p.getFileName().toString().startsWith(BOB + HistoryStore.EXTENSION + ".")
                    && p.getFileName().toString().endsWith(".corrupt")), "moved aside");
        }
        assertTrue(Files.exists(dir.resolve("notes.txt")), "unrelated files are left alone");
        try (var listing = Files.list(dir)) {
            assertTrue(listing.noneMatch(p -> p.toString().endsWith(".tmp")), "stale temp files are deleted");
        }
        HistoryStore reopened = open(dir);
        assertEquals(List.of(e.id()), ids(load(reopened, BOB).entries()));
        close(reopened);
    }

    /** A file of another format version is left untouched and the player's history is not saved over it. */
    @Test
    void anotherFormatVersionIsLeftAlone() throws IOException {
        HistoryStore store = open(dir);
        journal(store, entry(ALICE, "a", sparse(9), 1));
        close(store);
        byte[] bytes = Files.readAllBytes(file(ALICE));
        bytes[5] = 2; // version 2
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(bytes, 0, Journal.HEADER_BYTES - 4);
        Journal.putInt(bytes, Journal.HEADER_BYTES - 4, (int) crc.getValue());
        Files.write(file(ALICE), bytes);

        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertTrue(loaded.entries().isEmpty());
        assertFalse(loaded.problems().isEmpty());
        assertFalse(reopened.accepting(ALICE), "records of that player are not taken");
        assertTrue(reopened.needsRewrite().isEmpty(), "and never rewritten over the newer file");
        journal(reopened, entry(ALICE, "b", sparse(10), 2));
        close(reopened);
        assertTrue(Arrays.equals(bytes, Files.readAllBytes(file(ALICE))), "the file is unchanged");
    }

    /** Records that repeat or name unknown entries are skipped; the rest replays. */
    @Test
    void duplicateAndMissingRecordsAreSkipped() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", sparse(11), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(12), 2);
        journal(store, a);
        store.push(ALICE, a.id()); // duplicate push
        store.undone(ALICE, UUID.randomUUID()); // not the candidate
        store.section(ALICE, UUID.randomUUID(), 0, null, null); // section of nothing
        store.push(ALICE, UUID.randomUUID()); // push of an entry never begun
        journal(store, b);
        store.begin(ALICE, a.id(), 1, WORLD, "again"); // duplicate begin
        store.evict(ALICE, UUID.randomUUID());
        close(store);
        HistoryStore reopened = open(dir);
        assertEquals(List.of(a.id(), b.id()), ids(load(reopened, ALICE).entries()));
        close(reopened);
    }

    /**
     * What a crash leaves open is settled at the next start: a redo in flight counts as redone, a sealed entry whose
     * push waited is pushed, a job that recorded cells becomes an "(interrupted)" entry holding exactly the sections
     * journaled, and one that recorded nothing is dropped.
     */
    @Test
    void openWorkIsSettledAfterACrash() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", sparse(13), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(14), 2);
        journal(store, a);
        journal(store, b);
        store.undone(ALICE, b.id());
        store.redoBegin(ALICE, b.id()); // the redo job was running at the crash
        HistoryEntry deferred = entry(ALICE, "deferred", fill(1, 1, 1), 3);
        store.begin(ALICE, deferred.id(), 3, WORLD, "Fill");
        sections(store, deferred);
        store.seal(ALICE, deferred.id(), deferred.label(), 3, deferred.record().estimatedBytes());
        // A job interrupted after two of its three sections: they were journaled as it finished them.
        EditRecord job = merge(fill(4, 0, 4), fill(5, 0, 4));
        UUID jobId = UUID.randomUUID();
        store.begin(ALICE, jobId, 4, "minecraft:the_nether", "Fill");
        for (long key : job.before().sortedKeys()) {
            store.section(ALICE, jobId, key, job.before().section(key), job.after().section(key));
        }
        UUID idle = UUID.randomUUID();
        store.begin(ALICE, idle, 5, WORLD, "Raise stroke"); // began, recorded nothing
        store.section(ALICE, idle, 0, null, null);
        assertTrue(store.flush(5000));
        // A crash: the store is not closed; the next one reads what reached the operating system.
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(List.of(a.id(), b.id(), deferred.id(), jobId), ids(loaded.entries()));
        assertEquals(4, loaded.applied(), "b was redone; the pushes follow it");
        HistoryStore.LoadedEntry interrupted = loaded.entries().get(3);
        assertEquals("Fill (interrupted) · 8,192 blocks", interrupted.label());
        assertEquals("minecraft:the_nether", interrupted.world());
        assertSameRecord(job, interrupted.record());
        assertSameRecord(deferred.record(), loaded.entries().get(2).record());
        close(reopened);
        close(store);
        // Settled for good: the next start finds nothing open.
        HistoryStore third = open(dir);
        assertEquals(ids(loaded.entries()), ids(load(third, ALICE).entries()));
        close(third);
    }

    /** An undo in flight at a crash leaves its entry the undo candidate (running it again finishes it). */
    @Test
    void anUndoInFlightStaysTheCandidate() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", sparse(15), 1);
        journal(store, a);
        assertTrue(store.flush(5000));
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(1, loaded.applied());
        close(reopened);
        close(store);
    }

    // ------------------------------------------------------------------------------------------ failures

    /** A full disk mid-record: the player goes out of sync, later records are dropped, a rewrite heals the file. */
    @Test
    void aFullDiskPutsThePlayerOutOfSyncUntilARewrite() throws IOException {
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore store = open(dir, io, HistoryStore.Settings.DEFAULTS);
        PlayerHistory model = new PlayerHistory(HistoryLimits.DEFAULTS);
        HistoryEntry a = entry(ALICE, "a", fill(0, 0, 0), 1);
        model.push(a);
        journal(store, a);
        assertTrue(store.flush(5000));
        io.writeBudget.set(100); // the disk fills up inside the next entry's data
        HistoryEntry b = entry(ALICE, "b", fill(1, 0, 0), 2);
        model.push(b);
        journal(store, b);
        List<HistoryStore.Event> seen = new ArrayList<>();
        await(store, HistoryStore.Failed.class, f -> f.player().equals(ALICE), seen);
        assertTrue(store.needsRewrite().contains(ALICE));
        assertFalse(store.accepting(ALICE));
        HistoryEntry c = entry(ALICE, "c", sparse(16), 3);
        model.push(c);
        journal(store, c); // dropped: out of sync
        assertTrue(store.flush(5000));
        // Bob is not affected once there is room again.
        io.writeBudget.set(-1);
        HistoryEntry bobs = entry(BOB, "bob", sparse(17), 4);
        journal(store, bobs);
        // The server rewrites Alice's file from memory.
        List<HistoryStore.SnapshotEntry> snapshot = new ArrayList<>();
        for (UUID id : ids(model)) {
            HistoryEntry e = find(model, id);
            snapshot.add(new HistoryStore.SnapshotEntry(e.id(), e.world(), e.label(), e.createdMillis(),
                    model.bytesOf(e.id()), e.record()));
        }
        store.rewrite(ALICE, snapshot, model.undoEntries().size(), List.of(), List.of());
        assertTrue(store.accepting(ALICE));
        await(store, HistoryStore.Rewritten.class, r -> r.player().equals(ALICE) && r.ok(), seen);
        assertTrue(store.needsRewrite().isEmpty());
        HistoryEntry d = entry(ALICE, "d", sparse(18), 5);
        model.push(d);
        journal(store, d);
        close(store);
        HistoryStore reopened = open(dir);
        assertMatches(model, load(reopened, ALICE));
        assertEquals(List.of(bobs.id()), ids(load(reopened, BOB).entries()));
        close(reopened);
    }

    /** A rewrite that fails keeps the old file and leaves the player out of sync for another try. */
    @Test
    void aFailedRewriteKeepsTheOldFile() throws IOException {
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore store = open(dir, io, HistoryStore.Settings.DEFAULTS);
        HistoryEntry a = entry(ALICE, "a", sparse(19), 1);
        journal(store, a);
        assertTrue(store.flush(5000));
        io.failReplace.set(true);
        store.rewrite(ALICE, List.of(new HistoryStore.SnapshotEntry(a.id(), WORLD, "renamed", 1, 5, null)), 1,
                List.of(), List.of());
        List<HistoryStore.Event> seen = new ArrayList<>();
        await(store, HistoryStore.Rewritten.class, r -> !r.ok(), seen);
        assertTrue(store.needsRewrite().contains(ALICE));
        io.failReplace.set(false);
        close(store);
        HistoryStore reopened = open(dir);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(List.of(a.id()), ids(loaded.entries()));
        assertEquals("a", loaded.entries().get(0).label(), "the old file is kept");
        close(reopened);
    }

    /** The write queue is bounded: a player whose record does not fit goes out of sync; nothing blocks the caller. */
    @Test
    void aFullQueueDropsRecordsWithoutBlocking() throws IOException, InterruptedException {
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore store = open(dir, io, new HistoryStore.Settings(5_000, 1000, 64 << 10, 64, 1 << 20));
        await(store, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        CountDownLatch gate = new CountDownLatch(1);
        io.gate = gate; // the disk stalls
        long start = System.nanoTime();
        int accepted = 0;
        for (int i = 0; i < 40; i++) {
            if (store.accepting(ALICE)) accepted++;
            journal(store, entry(ALICE, "e" + i, fill(i, 0, 0), i));
        }
        long took = System.nanoTime() - start;
        assertTrue(took < TimeUnit.SECONDS.toNanos(5), "journaling never waits for the disk: " + took / 1_000_000 + " ms");
        assertTrue(accepted < 40, "the queue filled up");
        assertTrue(store.outOfSync(ALICE));
        assertFalse(store.awaitWritten(store.enqueued(), TimeUnit.MILLISECONDS.toNanos(50)), "still stalled");
        gate.countDown();
        io.gate = null;
        assertTrue(store.flush(10_000));
        List<HistoryStore.Event> events = store.poll();
        assertTrue(events.stream().anyMatch(e -> e instanceof HistoryStore.Failed), "reported: " + events);
        close(store);
    }

    /** A compaction whose final rename fails leaves the old file, which still loads exactly. */
    @Test
    void aFailedCompactionKeepsTheOldFile() throws IOException {
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore.Settings settings = new HistoryStore.Settings(128L << 20, 1000, 1024, 64, 1 << 16);
        HistoryStore store = open(dir, io, settings);
        PlayerHistory model = new PlayerHistory(new HistoryLimits(3, Long.MAX_VALUE, Long.MAX_VALUE));
        io.failReplace.set(true);
        for (int i = 0; i < 20; i++) {
            HistoryEntry e = entry(ALICE, "e" + i, fill(i, 0, 0), i);
            List<HistoryEntry> evicted = model.push(e);
            journal(store, e);
            for (HistoryEntry gone : evicted) store.evict(ALICE, gone.id());
        }
        assertTrue(store.flush(5000));
        long size = Files.size(file(ALICE));
        close(store);
        io.failReplace.set(false);
        try (var listing = Files.list(dir)) {
            assertTrue(listing.noneMatch(p -> p.toString().endsWith(".tmp")), "the copy was removed");
        }
        HistoryStore reopened = open(dir, StorageIo.SYSTEM, settings);
        assertMatches(model, load(reopened, ALICE));
        assertTrue(reopened.awaitIdle(10_000));
        close(reopened);
        assertTrue(Files.size(file(ALICE)) < size, "compacted once the rename works: " + size + " -> "
                + Files.size(file(ALICE)));
    }

    /**
     * The disk fills up in the middle of a compaction's copy: the copy is dropped, the journal keeps taking records, and
     * a restart (with room again) loads the exact history and compacts it then.
     */
    @Test
    void aWriteFailingInsideACompactionKeepsTheJournal() throws IOException {
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore.Settings settings = new HistoryStore.Settings(128L << 20, 1000, 1024, 64, 1 << 12);
        HistoryStore store = open(dir, io, settings);
        PlayerHistory model = new PlayerHistory(new HistoryLimits(3, Long.MAX_VALUE, Long.MAX_VALUE));
        io.failTempWrites.set(true);
        for (int i = 0; i < 30; i++) {
            HistoryEntry e = entry(ALICE, "e" + i, fill(i, 0, 0), i);
            List<HistoryEntry> evicted = model.push(e);
            journal(store, e);
            for (HistoryEntry gone : evicted) store.evict(ALICE, gone.id());
        }
        assertTrue(store.flush(5000));
        assertTrue(store.awaitIdle(10_000));
        assertTrue(store.accepting(ALICE), "a failed compaction does not stop the journal");
        long size = Files.size(file(ALICE));
        close(store);
        try (var listing = Files.list(dir)) {
            assertTrue(listing.noneMatch(p -> p.toString().endsWith(".tmp")), "the failed copy was removed");
        }
        io.failTempWrites.set(false);
        HistoryStore reopened = open(dir, io, settings);
        assertMatches(model, load(reopened, ALICE));
        assertTrue(reopened.awaitIdle(10_000));
        close(reopened);
        assertTrue(Files.size(file(ALICE)) < size, "compacted once there is room: " + size + " -> "
                + Files.size(file(ALICE)));
    }

    /** Dead records are compacted away while the player keeps editing; the file stays about twice its live data. */
    @Test
    void compactionKeepsTheFileBoundedAcrossRestarts() throws IOException {
        HistoryStore store = open(dir, StorageIo.SYSTEM, new HistoryStore.Settings(128L << 20, 1000, 4096, 64, 1 << 15));
        PlayerHistory model = new PlayerHistory(new HistoryLimits(4, Long.MAX_VALUE, Long.MAX_VALUE));
        long largest = 0;
        for (int i = 0; i < 60; i++) {
            HistoryEntry e = entry(ALICE, "e" + i, i % 2 == 0 ? fill(i, 0, 0) : sparse(i), i);
            List<HistoryEntry> evicted = model.push(e);
            journal(store, e);
            for (HistoryEntry gone : evicted) store.evict(ALICE, gone.id());
            if (i % 3 == 0) {
                model.undoCandidate().ifPresent(x -> {
                    model.markUndone(x.id());
                    store.undone(ALICE, x.id());
                });
            }
            assertTrue(store.flush(5000));
            largest = Math.max(largest, Files.size(file(ALICE)));
        }
        close(store);
        long total = 0;
        for (UUID id : ids(model)) total += find(model, id).record().estimatedBytes();
        assertTrue(largest < 8 * total + 64 * 1024, "the file stayed bounded: " + largest + " vs " + total);
        HistoryStore reopened = open(dir);
        assertMatches(model, load(reopened, ALICE));
        close(reopened);
    }

    /**
     * An entry that grew after its push (a fluid trail folded in) has its
     * changed sections journaled again and is sealed again with its new size, on either side of the stack. After a
     * restart it reads back as it grew; compactions keep only the latest sections and seal. The format is unchanged (a
     * later section of an entry always replaced an earlier one), so builds from before trails were saved read such a
     * file the same way, and this build reads their files as before.
     */
    @Test
    void sectionsJournaledAgainAfterThePushReplaceTheOldOnes() throws IOException {
        HistoryStore store = open(dir, StorageIo.SYSTEM, new HistoryStore.Settings(128L << 20, 1000, 0, 64, 1 << 15));
        PlayerHistory model = new PlayerHistory(HistoryLimits.DEFAULTS);
        HistoryEntry water = entry(ALICE, "Fill · 4,096 blocks", fill(0, 4, 0), 10);
        HistoryEntry drain = entry(ALICE, "Drain · 41 blocks", sparse(3), 11);
        for (HistoryEntry e : List.of(water, drain)) {
            model.push(e);
            journal(store, e);
        }
        model.markUndone(drain.id());
        store.undone(ALICE, drain.id());
        // Folded several times, as the game saves chunks while the water keeps flowing: a new section each time (grass
        // under the fill dying further along) and a cell of the fill's own section changing again.
        for (int i = 0; i < 6; i++) {
            int n = i;
            EditRecord trail = record(b -> {
                b.record(n, 63, 0, StoreTestSupport.DIRT, null, AIR, null);
                b.record(5, 70, 5, i(n), null, i(n + 1), null);
            });
            fold(store, model, water.id(), trail, true);
        }
        fold(store, model, drain.id(), record(b -> b.record(40, 0, 40, AIR, null, STONE, null)), false);
        HistoryEntry grownWater = find(model, water.id());
        assertTrue(grownWater.record().before().cellCount() > water.record().before().cellCount());
        close(store);

        HistoryStore reopened = open(dir);
        assertMatches(model, load(reopened, ALICE));
        close(reopened);
    }

    /** Stone and air in turn: a cell a trail changes again and again. */
    private static int i(int n) {
        return n % 2 == 0 ? STONE : AIR;
    }

    /**
     * What the server does when it folds a trail into a held entry: replace it (toward before when it is done, after
     * when undone), then journal the sections that changed (the fold shares the others) and seal it again.
     */
    private static void fold(HistoryStore store, PlayerHistory model, UUID id, EditRecord trail, boolean towardBefore) {
        HistoryEntry old = find(model, id);
        EditRecord folded = dev.sculptory.core.history.TrailFold.fold(old.record(), trail, towardBefore);
        HistoryEntry now = new HistoryEntry(id, old.owner(), old.world(), old.label(), folded, old.createdMillis());
        assertTrue(model.replace(now));
        for (long key : folded.before().sortedKeys()) {
            if (folded.before().section(key) == old.record().before().section(key)) continue;
            store.section(ALICE, id, key, folded.before().section(key), folded.after().section(key));
        }
        store.seal(ALICE, id, now.label(), now.createdMillis(), model.bytesOf(id));
    }

    /** Entries recorded by another game data version, or holding a state this game lacks, are reported, not loaded. */
    @Test
    void unrestorableEntriesAreReportedAndSkipped() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry keep = entry(ALICE, "keep", sparse(20), 1);
        HistoryEntry modded = entry(ALICE, "modded", record(b -> b.record(0, 0, 0, AIR, null,
                STATES.state("testmod:widget[facing=up]"), null)), 2);
        journal(store, keep);
        journal(store, modded);
        close(store);
        HistoryCodec withoutMod = new HistoryCodec() {
            @Override
            public int dataVersion() {
                return DATA_VERSION;
            }

            @Override
            public String stateText(int handle) {
                return CODEC.stateText(handle);
            }

            @Override
            public int state(String text) {
                return text.startsWith("testmod:") ? -1 : CODEC.state(text);
            }

            @Override
            public Trust trust(dev.sculptory.core.buffer.BlockEntityData tile) {
                return Trust.FOREIGN;
            }

            @Override
            public dev.sculptory.core.buffer.BlockEntityData tile(String typeId, byte[] nbt, Trust trust)
                    throws CorruptDataException {
                return CODEC.tile(typeId, nbt, trust);
            }
        };
        HistoryStore reopened = HistoryStore.open(dir, withoutMod, HistoryStore.Settings.DEFAULTS, StorageIo.SYSTEM,
                HistoryStore.Log.NONE);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(List.of(keep.id()), ids(loaded.entries()));
        assertEquals(List.of(modded.id()), loaded.failed());
        assertTrue(loaded.problems().get(0).contains("testmod:widget"), loaded.problems().toString());
        close(reopened);
        HistoryStore newerGame = HistoryStore.open(dir, HistoryCodec.of(STATES, DATA_VERSION + 1),
                HistoryStore.Settings.DEFAULTS, StorageIo.SYSTEM, HistoryStore.Log.NONE);
        HistoryStore.Loaded upgraded = load(newerGame, ALICE);
        assertTrue(upgraded.entries().isEmpty());
        assertEquals(2, upgraded.failed().size());
        close(newerGame);
    }

    // ------------------------------------------------------------------------------------------ lazy loading, threads

    /** The start-up scan reports each history without block data; one player's records load on their own. */
    @Test
    void theScanKeepsOnlyAnIndexAndLoadsArePerPlayer() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", fill(0, 0, 0), 1);
        HistoryEntry b = entry(BOB, "b", fill(2, 0, 0), 2);
        journal(store, a);
        journal(store, b);
        close(store);
        HistoryStore reopened = open(dir);
        HistoryStore.Scanned scanned = await(reopened, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        assertEquals(2, scanned.histories().size());
        for (StoredHistory history : scanned.histories()) {
            StoredHistory.Entry only = history.entries().get(0);
            assertTrue(only.bytes() > 0 && only.diskBytes() > 0);
            assertEquals(history.player().equals(ALICE) ? "a" : "b", only.label());
        }
        // Bob's file is damaged after the scan: Alice's load does not touch it.
        Files.write(file(BOB), new byte[] {0});
        assertEquals(List.of(a.id()), ids(load(reopened, ALICE).entries()));
        close(reopened);
    }

    /** The start-up scan of many players' files keeps at most maxOpenFiles of them open. */
    @Test
    void theScanDoesNotKeepEveryFileOpen() throws IOException {
        HistoryStore store = open(dir);
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID player = new UUID(77, i);
            players.add(player);
            journal(store, entry(player, "p" + i, sparse(i), i));
        }
        close(store);
        HistoryStore reopened = open(dir, StorageIo.SYSTEM,
                new HistoryStore.Settings(128L << 20, 1000, 64 << 10, 3, 1 << 20));
        HistoryStore.Scanned scanned = await(reopened, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        assertEquals(12, scanned.histories().size());
        assertTrue(reopened.awaitIdle(10_000));
        assertTrue(reopened.openFileCount() <= 3, "open files: " + reopened.openFileCount());
        for (UUID player : players) assertEquals(1, load(reopened, player).entries().size());
        assertTrue(reopened.awaitIdle(10_000));
        assertTrue(reopened.openFileCount() <= 3, "open files after loads: " + reopened.openFileCount());
        close(reopened);
    }

    /** Journaling from the server thread while the I/O thread writes and loads: nothing is lost or reordered. */
    @Test
    void theServerThreadAndTheWriterWorkConcurrently() throws IOException {
        java.util.concurrent.atomic.AtomicInteger compactions = new java.util.concurrent.atomic.AtomicInteger();
        HistoryStore.Log counting = new HistoryStore.Log() {
            @Override
            public void info(String message) {
                if (message.contains("compacted")) compactions.incrementAndGet();
            }

            @Override
            public void warn(String message, Throwable cause) {
                StoreTestSupport.LOG.warn(message, cause);
            }
        };
        HistoryStore store = HistoryStore.open(dir, CODEC, new HistoryStore.Settings(1L << 30, 10, 64 << 10, 4, 1 << 12),
                StorageIo.SYSTEM, counting);
        List<PlayerHistory> models = new ArrayList<>();
        List<UUID> players = new ArrayList<>();
        for (int p = 0; p < 12; p++) {
            players.add(new UUID(99, p));
            models.add(new PlayerHistory(new HistoryLimits(10, Long.MAX_VALUE, Long.MAX_VALUE)));
        }
        Random random = new Random(3);
        for (int step = 0; step < 1500; step++) {
            int p = random.nextInt(players.size());
            UUID player = players.get(p);
            PlayerHistory model = models.get(p);
            if (random.nextInt(4) > 0) {
                HistoryEntry e = entry(player, "e" + step, sparse(step), step);
                List<HistoryEntry> evicted = model.push(e);
                journal(store, e);
                for (HistoryEntry gone : evicted) store.evict(player, gone.id());
            } else {
                model.undoCandidate().ifPresent(e -> {
                    model.markUndone(e.id());
                    store.undone(player, e.id());
                });
            }
            if (step % 250 == 0) store.load(players.get(random.nextInt(players.size()))); // reads meanwhile
        }
        assertTrue(store.flush(20_000));
        assertTrue(store.awaitIdle(20_000));
        assertTrue(compactions.get() > 0, "files were compacted while records kept coming");
        close(store);
        HistoryStore reopened = open(dir);
        for (int p = 0; p < players.size(); p++) assertMatches(models.get(p), load(reopened, players.get(p)));
        close(reopened);
    }

    /** Closing with a disk that never answers gives up after the timeout instead of hanging the server stop. */
    @Test
    void closeGivesUpOnAHungDisk() throws IOException {
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore store = open(dir, io, HistoryStore.Settings.DEFAULTS);
        await(store, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        CountDownLatch gate = new CountDownLatch(1);
        io.gate = gate;
        journal(store, entry(ALICE, "a", sparse(21), 1));
        long start = System.nanoTime();
        assertFalse(store.close(200));
        assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        gate.countDown();
        assertNotNull(store.status());
    }

    /** The builder's saving hooks: dirty sections, per column, and snapshots equal to what build() makes. */
    @Test
    void recordBuilderSnapshotsMatchTheBuiltRecord() {
        RecordBuilder builder = new RecordBuilder();
        for (int i = 0; i < 100; i++) builder.record(i, 5, 3, AIR, null, STONE, null);
        assertTrue(builder.hasDirty());
        assertTrue(builder.dirtyIn(0, 0) && builder.dirtyIn(6, 0) && !builder.dirtyIn(7, 0));
        long[] column = builder.drainDirty(1, 0);
        assertEquals(1, column.length);
        assertFalse(builder.dirtyIn(1, 0));
        long[] rest = builder.drainDirty();
        assertEquals(6, rest.length);
        assertFalse(builder.hasDirty());
        builder.record(20, 5, 3, STONE, null, STONE, null); // recorded again (the cell keeps its first before)
        assertTrue(builder.dirtyIn(1, 0), "recording again makes the section dirty again");
        EditRecord built = builder.build();
        for (long key : built.before().sortedKeys()) {
            SectionBuffer[] snapshot = builder.snapshotSection(key);
            StoreTestSupport.assertSameSection(built.before().section(key), snapshot[0]);
            StoreTestSupport.assertSameSection(built.after().section(key), snapshot[1]);
        }
        builder.markAllDirty();
        assertEquals(7, builder.drainDirty().length);
    }
}
