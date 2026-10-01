package dev.sculptory.core.history.store;

import static dev.sculptory.core.history.store.StoreTestSupport.AIR;
import static dev.sculptory.core.history.store.StoreTestSupport.CHEST;
import static dev.sculptory.core.history.store.StoreTestSupport.WORLD;
import static dev.sculptory.core.history.store.StoreTestSupport.assertSameRecord;
import static dev.sculptory.core.history.store.StoreTestSupport.await;
import static dev.sculptory.core.history.store.StoreTestSupport.close;
import static dev.sculptory.core.history.store.StoreTestSupport.entry;
import static dev.sculptory.core.history.store.StoreTestSupport.fill;
import static dev.sculptory.core.history.store.StoreTestSupport.journal;
import static dev.sculptory.core.history.store.StoreTestSupport.load;
import static dev.sculptory.core.history.store.StoreTestSupport.open;
import static dev.sculptory.core.history.store.StoreTestSupport.record;
import static dev.sculptory.core.history.store.StoreTestSupport.sparse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The store under the failures the review raised: hostile sizes, other versions, files another program holds, failing
 * reads, damaged data, big rewrites next to other players, chunk saves behind busy players, and the order of what a
 * crash left open. Nothing may lose saved history, block the caller, or allocate what the data does not hold.
 */
class HistoryStoreFailureTest {
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

    /** Files kept aside for {@code player} with {@code suffix} ({@code .corrupt}, {@code .damaged}). */
    private List<Path> kept(UUID player, String suffix) throws IOException {
        try (var listing = Files.list(dir)) {
            return listing.filter(p -> p.getFileName().toString().startsWith(player + HistoryStore.EXTENSION + ".")
                    && p.getFileName().toString().endsWith(suffix)).toList();
        }
    }

    /** A file holding a header for {@code player}, then {@code tail}. */
    private void writeFile(UUID player, byte[] tail) throws IOException {
        Files.createDirectories(dir);
        try (OutputStream out = Files.newOutputStream(file(player))) {
            out.write(Journal.header(player));
            out.write(tail);
        }
    }

    // ------------------------------------------------------------------------------------------------ sizes

    /**
     * Frames whose length field lies (larger than the file, larger than the type allows, or a section claiming 40 MiB of
     * junk) never make the scan read or allocate more than its 1 MiB buffer: a length past the file is a torn tail, a
     * length the type cannot have is malformed, and a section's checksum is checked in pieces.
     */
    @Test
    void aHostileLengthFieldNeverAllocatesMoreThanABuffer() throws IOException {
        byte[] mark = new byte[Journal.FRAME_BYTES];
        Journal.putInt(mark, 0, Integer.MAX_VALUE); // a stack mark claiming 2 GiB
        mark[4] = (byte) Journal.Type.PUSH.code;
        writeFile(ALICE, mark);
        byte[] section = new byte[Journal.FRAME_BYTES + (40 << 20)];
        Journal.putInt(section, 0, 40 << 20); // a section claiming 40 MiB, all zeros: a bad checksum
        section[4] = (byte) Journal.Type.SECTION.code;
        writeFile(BOB, section);
        UUID carol = new UUID(0xCA201, 3);
        byte[] beyond = new byte[Journal.FRAME_BYTES + 100];
        Journal.putInt(beyond, 0, 60 << 20); // a section claiming more than the file holds
        beyond[4] = (byte) Journal.Type.SECTION.code;
        writeFile(carol, beyond);

        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore store = open(dir, io, HistoryStore.Settings.DEFAULTS);
        await(store, HistoryStore.Scanned.class, s -> s.histories().isEmpty(), new ArrayList<>());
        assertTrue(load(store, ALICE).entries().isEmpty());
        assertTrue(load(store, BOB).entries().isEmpty());
        assertTrue(load(store, carol).entries().isEmpty());
        close(store);
        assertTrue(io.largestRead.get() <= 1 << 20, "largest read " + io.largestRead.get());
        // Each file held nothing whole: it is cut back to its header, and a history left empty has no file.
        for (UUID player : List.of(ALICE, BOB, carol)) {
            assertTrue(!Files.exists(file(player)) || Files.size(file(player)) == Journal.HEADER_BYTES, "" + player);
        }
        // What was cut off is kept aside where it held more than a frame (a damaged length can look like a cut-off
        // write): Bob's bad section and Carol's section past the end, not Alice's lone frame.
        assertEquals(0, kept(ALICE, ".damaged").size());
        assertEquals(1, kept(BOB, ".damaged").size());
        assertEquals(1, kept(carol, ".damaged").size());
    }

    /** A section too large for a record (a block entity over the limit) is not saved; the rest of the history is. */
    @Test
    void aSectionTooLargeToSaveAbortsOnlyItsEntry() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry small = entry(ALICE, "small", sparse(1), 1);
        byte[] huge = new byte[SectionCodec.MAX_TILE_BYTES + 1];
        HistoryEntry big = entry(ALICE, "big", record(b -> b.record(0, 0, 0, AIR, null, CHEST,
                new NbtBytes("minecraft:chest", huge))), 2);
        HistoryEntry after = entry(ALICE, "after", sparse(2), 3);
        journal(store, small);
        journal(store, big);
        journal(store, after);
        close(store);
        HistoryStore reopened = open(dir);
        assertEquals(List.of(small.id(), after.id()), ids(load(reopened, ALICE).entries()));
        close(reopened);
    }

    // ------------------------------------------------------------------------------------------------ versions

    /**
     * A header of another version is left alone whatever its checksum and length (a later layout may differ), and so
     * is version 1 with flags this build does not know; the file is byte for byte unchanged. When the newer build comes
     * back (here: the original header restored), the history is all there.
     */
    @Test
    void otherVersionsAndFlagsAreLeftAlone() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", sparse(3), 1);
        journal(store, a);
        close(store);
        byte[] original = Files.readAllBytes(file(ALICE));

        byte[] newer = original.clone();
        newer[5] = 7; // version 7, header checksum now wrong
        byte[] flagged = original.clone();
        flagged[7] = 2; // version 1 with flag 2 (unknown; flag 1 marks entity records), checksum fixed
        CRC32C crc = new CRC32C();
        crc.update(flagged, 0, Journal.HEADER_BYTES - 4);
        Journal.putInt(flagged, Journal.HEADER_BYTES - 4, (int) crc.getValue());
        for (byte[] variant : List.of(newer, flagged)) {
            Files.write(file(ALICE), variant);
            HistoryStore reopened = open(dir);
            HistoryStore.Loaded loaded = load(reopened, ALICE);
            assertTrue(loaded.entries().isEmpty());
            assertFalse(reopened.accepting(ALICE));
            journal(reopened, entry(ALICE, "b", sparse(4), 2));
            close(reopened);
            assertArrayEquals(variant, Files.readAllBytes(file(ALICE)), "left as it was");
            assertTrue(kept(ALICE, ".corrupt").isEmpty(), "not quarantined");
        }
        Files.write(file(ALICE), original); // the newer build reads its own file again
        HistoryStore back = open(dir);
        assertEquals(List.of(a.id()), ids(load(back, ALICE).entries()));
        close(back);
    }

    // ------------------------------------------------------------------------------------------------ reads

    /**
     * Another program holds the file (a backup tool on Windows): the scan leaves it alone, the player's records are not
     * written to it, and their load keeps trying; once the file can be opened again, the load delivers every saved step
     * and the file was never replaced.
     */
    @Test
    void anUnreadableFileIsLeftAloneAndLoadedWhenItCanBeRead() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", fill(0, 0, 0), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(5), 2);
        journal(store, a);
        journal(store, b);
        close(store);
        byte[] saved = Files.readAllBytes(file(ALICE));

        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        io.failOpen = ALICE.toString();
        HistoryStore reopened = open(dir, io, HistoryStore.Settings.DEFAULTS);
        List<HistoryStore.Event> seen = new ArrayList<>();
        // The scan reports the file it could not read (the player is out of sync), then its histories without it.
        await(reopened, HistoryStore.Failed.class, f -> f.player().equals(ALICE), seen);
        HistoryStore.Scanned scanned = await(reopened, HistoryStore.Scanned.class, s -> true, seen);
        assertTrue(scanned.histories().isEmpty(), "an unreadable file is not reported as a history");
        reopened.load(ALICE);
        assertFalse(reopened.accepting(ALICE), "records are not written to a file that cannot be read");
        assertTrue(reopened.needsRewrite().contains(ALICE), "the server rewrites it only once it holds every step");
        journal(reopened, entry(ALICE, "dropped", sparse(6), 3));
        long quiet = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        while (System.nanoTime() < quiet) {
            for (HistoryStore.Event event : reopened.poll()) {
                assertFalse(event instanceof HistoryStore.Loaded, "no load while the file cannot be read: " + event);
            }
        }
        assertArrayEquals(saved, Files.readAllBytes(file(ALICE)), "the held file is untouched");
        io.failOpen = null; // the other program lets go
        HistoryStore.Loaded loaded = await(reopened, HistoryStore.Loaded.class, l -> l.player().equals(ALICE), seen);
        assertEquals(List.of(a.id(), b.id()), ids(loaded.entries()));
        assertTrue(loaded.failed().isEmpty());
        assertSameRecord(a.record(), loaded.entries().get(0).record());
        close(reopened);
    }

    /** Reads that fail while a history loads are tried again; nothing is reported as failed for them. */
    @Test
    void aFailedReadIsRetriedNotReportedAsLost() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", fill(0, 0, 0), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(7), 2);
        journal(store, a);
        journal(store, b);
        close(store);
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore reopened = open(dir, io, HistoryStore.Settings.DEFAULTS);
        await(reopened, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        io.failReads.set(2);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(List.of(a.id(), b.id()), ids(loaded.entries()));
        assertTrue(loaded.failed().isEmpty(), "failed: " + loaded.problems());
        close(reopened);
    }

    /**
     * Two entries whose data was damaged after the scan (their checksums no longer match): each is read
     * {@value HistoryStore#DAMAGED_ATTEMPTS} times, then given up for good, so the load never goes round in circles
     * between them (a given-up entry is not read again when the load starts over for the other). The rest loads, and
     * one copy of the file is kept.
     */
    @Test
    void damagedEntriesAreEachRetriedThenGivenUpOnce() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", sparse(8), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(9), 2);
        HistoryEntry c = entry(ALICE, "c", sparse(10), 3);
        HistoryEntry d = entry(ALICE, "d", sparse(11), 4);
        journal(store, a);
        List<Long> starts = new ArrayList<>();
        for (HistoryEntry e : List.of(b, c, d)) {
            assertTrue(store.flush(5000));
            starts.add(Files.size(file(ALICE)));
            journal(store, e);
        }
        close(store);
        HistoryStore reopened = open(dir);
        await(reopened, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        // After the scan: flip a byte inside b's and d's first section records (after their begin records).
        byte[] bytes = Files.readAllBytes(file(ALICE));
        for (int i : new int[] {0, 2}) {
            int start = (int) (long) starts.get(i);
            int beginLength = Journal.FRAME_BYTES + Journal.getInt(bytes, start);
            bytes[start + beginLength + Journal.FRAME_BYTES + 20] ^= 0x40;
        }
        Files.write(file(ALICE), bytes);
        HistoryStore.Loaded loaded = load(reopened, ALICE);
        assertEquals(List.of(a.id(), c.id()), ids(loaded.entries()));
        assertEquals(List.of(b.id(), d.id()), loaded.failed());
        assertEquals(2, loaded.problems().size());
        assertEquals(1, kept(ALICE, ".damaged").size(), "one copy is kept");
        close(reopened);
    }

    /**
     * Damage inside a file with whole records after it (a type byte, a length its type cannot have, a length running
     * past the end): the file is cut back to before the damage, and each time a copy of the file as it was is kept
     * first, under a new name. Zeros at the end (space the file system never filled) are cut off without a copy.
     */
    @Test
    void damageInsideAFileIsKeptAsideBeforeItIsCutOff() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", sparse(3), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(4), 2);
        journal(store, a);
        assertTrue(store.flush(5000));
        int bStart = (int) Files.size(file(ALICE));
        journal(store, b);
        close(store);
        byte[] whole = Files.readAllBytes(file(ALICE));
        byte[] badType = whole.clone();
        badType[bStart + 4] = (byte) 0x7F;
        byte[] overCap = whole.clone();
        Journal.putInt(overCap, bStart, Journal.MAX_DATA_PAYLOAD + 1); // longer than a BEGIN can be
        byte[] pastEnd = whole.clone();
        Journal.putInt(pastEnd, bStart, Journal.MAX_DATA_PAYLOAD); // a BEGIN's length, but past the file's end
        int copies = 0;
        for (byte[] variant : List.of(badType, overCap, pastEnd)) {
            Files.write(file(ALICE), variant);
            HistoryStore reopened = open(dir);
            assertEquals(List.of(a.id()), ids(load(reopened, ALICE).entries()));
            close(reopened);
            assertEquals(bStart, Files.size(file(ALICE)), "cut back to before the damage");
            List<Path> kept = kept(ALICE, ".damaged");
            assertEquals(++copies, kept.size(), "a new copy each time");
            boolean found = false;
            for (Path copy : kept) found |= java.util.Arrays.equals(variant, Files.readAllBytes(copy));
            assertTrue(found, "the copy holds the file as it was");
        }
        Files.write(file(ALICE), java.util.Arrays.copyOf(whole, whole.length + 4096));
        HistoryStore reopened = open(dir);
        assertEquals(List.of(a.id(), b.id()), ids(load(reopened, ALICE).entries()));
        close(reopened);
        assertEquals(whole.length, Files.size(file(ALICE)), "the zeros are cut off");
        assertEquals(copies, kept(ALICE, ".damaged").size(), "without a copy");
    }

    /** A file whose header is damaged is moved aside under a new name each time: an older one is never replaced. */
    @Test
    void filesMovedAsideNeverReplaceOlderOnes() throws IOException {
        Files.createDirectories(dir);
        for (int i = 0; i < 2; i++) {
            Files.write(file(BOB), ("not a journal " + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            HistoryStore store = open(dir);
            assertTrue(load(store, BOB).entries().isEmpty());
            close(store);
        }
        List<String> contents = new ArrayList<>();
        for (Path corrupt : kept(BOB, ".corrupt")) {
            contents.add(Files.readString(corrupt, java.nio.charset.StandardCharsets.UTF_8));
        }
        contents.sort(null);
        assertEquals(List.of("not a journal 0", "not a journal 1"), contents);
    }

    /**
     * The history folder cannot be listed at start: no scan report is made (nobody's history is taken as empty), a
     * player's own file is still read when they load, and the listing is tried again until it works.
     */
    @Test
    void aFolderThatCannotBeListedIsNeverTakenAsEmpty() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry a = entry(ALICE, "a", sparse(3), 1);
        journal(store, a);
        close(store);
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        io.failList.set(true);
        HistoryStore reopened = open(dir, io, HistoryStore.Settings.DEFAULTS);
        List<HistoryStore.Event> seen = new ArrayList<>();
        reopened.load(ALICE);
        HistoryStore.Loaded loaded = await(reopened, HistoryStore.Loaded.class, l -> true, seen);
        assertEquals(List.of(a.id()), ids(loaded.entries()));
        assertTrue(seen.stream().noneMatch(e -> e instanceof HistoryStore.Scanned),
                "no report while it cannot be listed");
        io.failList.set(false);
        HistoryStore.Scanned scanned = await(reopened, HistoryStore.Scanned.class, s -> true, seen);
        assertEquals(List.of(ALICE), scanned.histories().stream().map(StoredHistory::player).toList());
        close(reopened);
    }

    /** A file that stays unreadable is read again and again, but warned about once (then every 10 minutes). */
    @Test
    void aFileThatStaysUnreadableIsWarnedAboutOnce() throws Exception {
        HistoryStore store = open(dir);
        journal(store, entry(ALICE, "a", sparse(3), 1));
        close(store);
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        io.failOpen = ALICE.toString();
        List<String> warnings = java.util.Collections.synchronizedList(new ArrayList<>());
        HistoryStore held = HistoryStore.open(dir, StoreTestSupport.CODEC, HistoryStore.Settings.DEFAULTS, io,
                new HistoryStore.Log() {
                    @Override
                    public void info(String message) {}

                    @Override
                    public void warn(String message, Throwable cause) {
                        warnings.add(message);
                    }
                });
        held.load(ALICE);
        Thread.sleep(3300); // the scan, then reads 1 s and 3 s later
        io.failOpen = null;
        HistoryStore.Loaded loaded = await(held, HistoryStore.Loaded.class, l -> true, new ArrayList<>());
        assertEquals(1, loaded.entries().size());
        close(held);
        assertEquals(1, warnings.stream().filter(w -> w.contains("cannot be read")).count(), "" + warnings);
    }

    // ------------------------------------------------------------------------------------------------ rewrites

    /**
     * A big rewrite is not counted in the write queue: another player's records keep fitting and stay in sync while
     * it runs, and both files come out right.
     */
    @Test
    void aBigRewriteDoesNotPushOthersOutOfSync() throws IOException {
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore store = open(dir, io, new HistoryStore.Settings(32 << 10, 1000, 64 << 10, 64, 4096));
        await(store, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        List<HistoryStore.SnapshotEntry> snapshot = new ArrayList<>();
        long heap = 0;
        for (int i = 0; i < 40; i++) {
            EditRecord r = fill(i, 0, 0);
            heap += r.estimatedBytes();
            snapshot.add(new HistoryStore.SnapshotEntry(UUID.randomUUID(), WORLD, "e" + i, i, r.estimatedBytes(), r));
        }
        assertTrue(heap > 32 << 10, "the rewrite is larger than the whole queue: " + heap);
        store.rewrite(ALICE, snapshot, 40, List.of(), List.of());
        List<HistoryEntry> bobs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            int x = i;
            HistoryEntry e = entry(BOB, "bob " + i, record(b -> b.record(x, 1, 1, AIR, null, CHEST, null)), i);
            bobs.add(e);
            journal(store, e);
            assertTrue(store.accepting(BOB), "Bob's record " + i + " was refused while Alice's history is rewritten");
            assertTrue(store.flush(5000));
        }
        await(store, HistoryStore.Rewritten.class, r -> r.player().equals(ALICE) && r.ok(), new ArrayList<>());
        assertTrue(store.flush(10_000));
        assertTrue(store.accepting(BOB));
        close(store);
        HistoryStore reopened = open(dir);
        assertEquals(40, load(reopened, ALICE).entries().size());
        List<UUID> expected = new ArrayList<>();
        for (HistoryEntry e : bobs) expected.add(e.id());
        assertEquals(expected, ids(load(reopened, BOB).entries()));
        close(reopened);
    }

    /** Rewrites queued before the store closes (a server stop saves out-of-sync histories) are finished first. */
    @Test
    void closeFinishesAQueuedRewrite() throws IOException {
        HistoryStore store = open(dir);
        await(store, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        HistoryEntry a = entry(ALICE, "a", fill(1, 0, 0), 1);
        store.rewrite(ALICE, List.of(new HistoryStore.SnapshotEntry(a.id(), WORLD, "a", 1, 10, a.record())), 1,
                List.of(), List.of());
        close(store);
        HistoryStore reopened = open(dir);
        assertEquals(List.of(a.id()), ids(load(reopened, ALICE).entries()));
        close(reopened);
    }

    // ------------------------------------------------------------------------------------------------ chunk saves

    /**
     * A chunk save waits only for the records of its own column: with a slow disk and many of Alice's records queued,
     * Bob's section in the saved column is written first and the wait ends long before Alice's records are done.
     */
    @Test
    void aChunkSaveWaitsOnlyForItsColumn() throws IOException {
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore store = open(dir, io, HistoryStore.Settings.DEFAULTS);
        await(store, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        io.writeDelayMillis = 15; // every journal write takes 15 ms
        for (int i = 0; i < 30; i++) journal(store, entry(ALICE, "a" + i, fill(i + 10, 0, 0), i));
        EditRecord bobs = fill(3, 0, 7);
        HistoryEntry b = entry(BOB, "b", bobs, 1);
        journal(store, b);
        long start = System.nanoTime();
        HistoryStore.ColumnWait wait = store.awaitColumn(HistoryStore.column(3, 7), TimeUnit.SECONDS.toNanos(2));
        long took = System.nanoTime() - start;
        assertTrue(wait.written() && wait.unprotected().isEmpty(), "wait " + wait);
        assertTrue(store.hasUnwritten(), "Alice's records are still being written");
        assertTrue(took < TimeUnit.MILLISECONDS.toNanos(600), "the chunk save waited " + took / 1_000_000 + " ms");
        io.writeDelayMillis = 0;
        close(store);
    }

    /** A player whose file is being rewritten is reported, not waited for (their records wait for the rewrite). */
    @Test
    void aPlayerBeingRewrittenIsReportedNotWaitedFor() throws IOException {
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore store = open(dir, io, HistoryStore.Settings.DEFAULTS);
        await(store, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        CountDownLatch held = new CountDownLatch(1);
        io.tempGate = held;
        HistoryEntry old = entry(ALICE, "old", sparse(11), 1);
        store.rewrite(ALICE, List.of(new HistoryStore.SnapshotEntry(old.id(), WORLD, "old", 1, 10, old.record())), 1,
                List.of(), List.of());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(dir.resolve(ALICE + HistoryStore.EXTENSION + ".rewrite.tmp"))) {
            assertTrue(System.nanoTime() < deadline, "the rewrite did not start");
        }
        HistoryEntry fresh = entry(ALICE, "fresh", fill(2, 0, 5), 2);
        journal(store, fresh);
        // The I/O thread is stuck in the rewrite's write: a wait for Alice's column would only time out.
        new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                // Released below anyway.
            }
            held.countDown();
        }).start();
        HistoryStore.ColumnWait wait = store.awaitColumn(HistoryStore.column(2, 5), TimeUnit.MILLISECONDS.toNanos(100));
        assertEquals(java.util.Set.of(ALICE), wait.unprotected());
        io.tempGate = null;
        await(store, HistoryStore.Rewritten.class, r -> r.ok(), new ArrayList<>());
        close(store);
        HistoryStore reopened = open(dir);
        assertEquals(List.of(old.id(), fresh.id()), ids(load(reopened, ALICE).entries()), "records made during the "
                + "rewrite follow it");
        close(reopened);
    }

    // ------------------------------------------------------------------------------------------------ recovery

    /**
     * Pushes that waited behind an undo are pushed at recovery in the order their entries were sealed (the order their
     * jobs finished), not the order they began.
     */
    @Test
    void recoveryPushesInTheOrderEntriesWereSealed() throws IOException {
        HistoryStore store = open(dir);
        HistoryEntry first = entry(ALICE, "began first", sparse(12), 1);
        HistoryEntry second = entry(ALICE, "began second", sparse(13), 2);
        store.begin(ALICE, first.id(), 1, WORLD, first.label());
        store.begin(ALICE, second.id(), 2, WORLD, second.label());
        StoreTestSupport.sections(store, first);
        StoreTestSupport.sections(store, second);
        store.seal(ALICE, second.id(), second.label(), 2, 10); // finished first
        store.seal(ALICE, first.id(), first.label(), 1, 10);
        assertTrue(store.flush(5000));
        HistoryStore reopened = open(dir);
        assertEquals(List.of(second.id(), first.id()), ids(load(reopened, ALICE).entries()));
        close(reopened);
        close(store);
    }
}
