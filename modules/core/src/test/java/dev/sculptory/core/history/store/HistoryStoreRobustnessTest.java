package dev.sculptory.core.history.store;

import static dev.sculptory.core.history.store.StoreTestSupport.await;
import static dev.sculptory.core.history.store.StoreTestSupport.close;
import static dev.sculptory.core.history.store.StoreTestSupport.entry;
import static dev.sculptory.core.history.store.StoreTestSupport.journal;
import static dev.sculptory.core.history.store.StoreTestSupport.open;
import static dev.sculptory.core.history.store.StoreTestSupport.sparse;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.sculptory.core.history.HistoryEntry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The follow-ups of the second crash-safe history review: nothing is cut off or given up without a copy of it (a full
 * disk keeps the file as it is and the player's history in memory), a folder that exists but cannot be listed is never
 * taken as empty (nor one whose listing fails part way, which is retried and shown in the status), a file that cannot be
 * moved aside is warned about once, and copies kept aside are bounded per player.
 */
class HistoryStoreRobustnessTest {
    private static final UUID ALICE = new UUID(0xA11CE, 1);
    private static final UUID BOB = new UUID(0xB0B, 2);

    @TempDir
    Path dir;

    private Path file(UUID player) {
        return dir.resolve(player + HistoryStore.EXTENSION);
    }

    /** Names of the files kept aside for {@code player} ({@code .corrupt} and {@code .damaged}). */
    private TreeSet<String> kept(UUID player) throws IOException {
        try (var listing = Files.list(dir)) {
            TreeSet<String> names = new TreeSet<>();
            listing.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(player + HistoryStore.EXTENSION + ".")
                            && (n.endsWith(".corrupt") || n.endsWith(".damaged")))
                    .forEach(names::add);
            return names;
        }
    }

    /** A store whose warnings are collected (and printed). */
    private HistoryStore openCapturing(StorageIo io, List<String> warnings) throws IOException {
        return HistoryStore.open(dir, StoreTestSupport.CODEC, HistoryStore.Settings.DEFAULTS, io, new HistoryStore.Log() {
            @Override
            public void info(String message) {
                System.out.println("[store] " + message);
            }

            @Override
            public void warn(String message, Throwable cause) {
                System.out.println("[store WARN] " + message);
                warnings.add(message);
            }
        });
    }

    private static void waitFor(BooleanSupplier done, String what) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) fail("timed out waiting for " + what);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }
    }

    /** Polls for a while and fails on any load of {@code player} delivered meanwhile. */
    private static void noLoad(HistoryStore store, UUID player, List<HistoryStore.Event> seen, long millis) {
        long quiet = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < quiet) {
            for (HistoryStore.Event event : store.poll()) {
                assertFalse(event instanceof HistoryStore.Loaded loaded && loaded.player().equals(player),
                        "a load was delivered: " + event);
                seen.add(event);
            }
        }
    }

    /** Alice's journal holding {@code a} and {@code b}; returns where {@code b}'s records start. */
    private int twoEntries(HistoryEntry a, HistoryEntry b) throws IOException {
        HistoryStore store = open(dir);
        journal(store, a);
        assertTrue(store.flush(5000));
        int bStart = (int) Files.size(file(ALICE));
        journal(store, b);
        close(store);
        return bStart;
    }

    // ------------------------------------------------------------------------------------------------ copies

    /**
     * Damage inside a file, and the disk is full when the scan would keep a copy of it: nothing is cut off (the file is
     * left byte for byte as it was, and a partial copy is removed), the player's records stay in memory (out of sync),
     * and their load waits. Once a copy can be kept, the file is cut back and the load delivers what it holds.
     */
    @Test
    void aDamagedTailIsNotCutOffWhileNoCopyCanBeKept() throws IOException {
        HistoryEntry a = entry(ALICE, "a", sparse(3), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(4), 2);
        int bStart = twoEntries(a, b);
        byte[] damaged = Files.readAllBytes(file(ALICE));
        damaged[bStart + 4] = (byte) 0x7F; // b's first record: an unknown type, whole records after it
        Files.write(file(ALICE), damaged);

        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        io.failCopy.set(true);
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        HistoryStore store = openCapturing(io, warnings);
        List<HistoryStore.Event> seen = new ArrayList<>();
        await(store, HistoryStore.Failed.class, f -> f.player().equals(ALICE), seen);
        store.load(ALICE);
        noLoad(store, ALICE, seen, 300);
        assertTrue(io.copies.get() >= 1, "no copy was tried");
        assertArrayEquals(damaged, Files.readAllBytes(file(ALICE)), "nothing is cut off without a copy");
        assertTrue(kept(ALICE).isEmpty(), "the partial copy was removed: " + kept(ALICE));
        assertFalse(store.accepting(ALICE), "the player's records stay in memory meanwhile");
        synchronized (warnings) {
            assertTrue(warnings.stream().noneMatch(w -> w.contains("are dropped")), "" + warnings);
        }

        io.failCopy.set(false);
        HistoryStore.Loaded loaded = await(store, HistoryStore.Loaded.class, l -> l.player().equals(ALICE), seen);
        assertEquals(List.of(a.id()), loaded.entries().stream().map(HistoryStore.LoadedEntry::id).toList());
        close(store);
        assertEquals(bStart, Files.size(file(ALICE)), "cut back once the copy was kept");
        assertEquals(1, kept(ALICE).size());
        assertArrayEquals(damaged, Files.readAllBytes(dir.resolve(kept(ALICE).first())), "the copy is the file as it was");
    }

    /**
     * An entry whose data is damaged (after the scan) is read three times; if the copy of the file that must come before
     * giving it up cannot be kept, the load waits and tries again rather than give the entry up. Once the copy is kept,
     * the entry is given up and the rest delivered.
     */
    @Test
    void aDamagedEntryIsNotGivenUpWhileNoCopyCanBeKept() throws IOException {
        HistoryEntry a = entry(ALICE, "a", sparse(8), 1);
        HistoryEntry b = entry(ALICE, "b", sparse(9), 2);
        int bStart = twoEntries(a, b);
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        HistoryStore store = open(dir, io, HistoryStore.Settings.DEFAULTS);
        List<HistoryStore.Event> seen = new ArrayList<>();
        await(store, HistoryStore.Scanned.class, s -> true, seen);
        byte[] bytes = Files.readAllBytes(file(ALICE));
        int beginLength = Journal.FRAME_BYTES + Journal.getInt(bytes, bStart);
        bytes[bStart + beginLength + Journal.FRAME_BYTES + 20] ^= 0x40; // inside b's first section
        Files.write(file(ALICE), bytes);

        io.failCopy.set(true);
        store.load(ALICE);
        waitFor(() -> io.copies.get() >= 1, "the copy before giving the entry up");
        noLoad(store, ALICE, seen, 300);
        assertTrue(kept(ALICE).isEmpty(), "the partial copy was removed");
        io.failCopy.set(false);
        HistoryStore.Loaded loaded = await(store, HistoryStore.Loaded.class, l -> l.player().equals(ALICE), seen);
        assertEquals(List.of(a.id()), loaded.entries().stream().map(HistoryStore.LoadedEntry::id).toList());
        assertEquals(List.of(b.id()), loaded.failed());
        assertEquals(1, kept(ALICE).size(), "given up after its copy was kept");
        close(store);
    }

    /**
     * A file whose header is damaged and cannot be moved aside is left as it is and read again and again, but warned
     * about once (then every 10 minutes); once it can be moved, it is, and the player starts empty.
     */
    @Test
    void aFileThatCannotBeMovedAsideIsWarnedAboutOnce() throws Exception {
        Files.createDirectories(dir);
        byte[] junk = "not a journal".getBytes(StandardCharsets.UTF_8);
        Files.write(file(BOB), junk);
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        io.failReplace.set(true);
        List<String> warnings = Collections.synchronizedList(new ArrayList<>());
        HistoryStore store = openCapturing(io, warnings);
        store.load(BOB);
        Thread.sleep(3300); // the scan, then reads 1 s and 3 s later
        assertTrue(io.replaces.get() >= 2, "moved aside " + io.replaces.get() + " time(s)");
        assertArrayEquals(junk, Files.readAllBytes(file(BOB)), "left as it is");
        io.failReplace.set(false);
        HistoryStore.Loaded loaded = await(store, HistoryStore.Loaded.class, l -> l.player().equals(BOB),
                new ArrayList<>());
        assertTrue(loaded.entries().isEmpty());
        close(store);
        assertEquals(1, kept(BOB).size());
        List<String> failures;
        synchronized (warnings) {
            failures = warnings.stream().filter(w -> w.contains(BOB.toString()) && !w.contains("moved to")).toList();
        }
        assertEquals(1, failures.size(), "" + warnings);
    }

    /** Alice's journal with one entry and, after it, damage holding data: a scan keeps a copy, then cuts it off. */
    private byte[] damagedJournal() throws IOException {
        HistoryStore store = open(dir);
        journal(store, entry(ALICE, "a", sparse(3), 1));
        close(store);
        Files.write(file(ALICE), new byte[] {0, 0, 0, 5, 0x7F, 1, 2, 3, 4, 5, 6, 7, 8, 9}, StandardOpenOption.APPEND);
        return Files.readAllBytes(file(ALICE));
    }

    /** The one copy of {@code player}'s whose name is not among {@code known}, and the rest. */
    private String madeNow(UUID player, Set<String> known) throws IOException {
        List<String> made = kept(player).stream().filter(n -> !known.contains(n)).toList();
        assertEquals(1, made.size(), "copies made: " + made);
        return made.get(0);
    }

    /**
     * {@code .damaged} copies are bounded: once the damaged part a new copy holds is cut off, the new copy and the newest
     * {@value HistoryStore#MAX_KEPT_DAMAGED} - 1 others (by the time in their names, then their number) are kept.
     * {@code .corrupt} files (the only copy of a whole history) are never deleted, nor names the store did not make, nor
     * another player's copies. Nothing is deleted at start without a new copy.
     */
    @Test
    void damagedCopiesAreBoundedAndCorruptFilesNeverDeleted() throws IOException {
        byte[] damaged = damagedJournal();
        String alice = ALICE + HistoryStore.EXTENSION + ".";
        List<String> old = List.of("20200101-000000.damaged", "20200102-000000.damaged", "20200103-000000.damaged",
                "20200104-000000.damaged", "20200104-000000-10.damaged", "20200104-000000-2.damaged",
                "20200101-000000.corrupt", "20200102-000000.corrupt", "20200103-000000.corrupt", "20200104-000000.corrupt",
                "before-upgrade.damaged");
        for (String name : old) Files.write(dir.resolve(alice + name), new byte[] {1});
        String bob = BOB + HistoryStore.EXTENSION + ".";
        for (String name : List.of("20200101-000000.damaged", "20200102-000000.damaged", "20200103-000000.damaged",
                "20200104-000000.damaged")) {
            Files.write(dir.resolve(bob + name), new byte[] {1});
        }
        Set<String> before = kept(ALICE);

        HistoryStore reopened = open(dir);
        await(reopened, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        close(reopened);
        String made = madeNow(ALICE, before);
        assertArrayEquals(damaged, Files.readAllBytes(dir.resolve(made)), "the new copy holds what was cut off");
        Set<String> expected = new TreeSet<>(List.of(made, alice + "20200104-000000-10.damaged",
                alice + "20200104-000000-2.damaged", alice + "20200101-000000.corrupt", alice + "20200102-000000.corrupt",
                alice + "20200103-000000.corrupt", alice + "20200104-000000.corrupt", alice + "before-upgrade.damaged"));
        assertEquals(expected, kept(ALICE));
        assertEquals(4, kept(BOB).size(), "another player's copies are untouched, and nothing goes at start");
    }

    /**
     * The clock was ahead when older copies were made: their names sort after the copy made now. The copy just made is
     * still never deleted (the others are pruned around it), and it holds the part cut off.
     */
    @Test
    void theCopyJustMadeIsNeverDeletedWhateverTheClockSaid() throws IOException {
        byte[] damaged = damagedJournal();
        int valid = damaged.length - 14;
        String alice = ALICE + HistoryStore.EXTENSION + ".";
        for (String name : List.of("20990101-000000.damaged", "20990102-000000.damaged", "20990103-000000.damaged")) {
            Files.write(dir.resolve(alice + name), new byte[] {1});
        }
        Set<String> before = kept(ALICE);
        HistoryStore reopened = open(dir);
        await(reopened, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        close(reopened);
        assertEquals(valid, Files.size(file(ALICE)), "the damaged part was cut off");
        String made = madeNow(ALICE, before);
        assertArrayEquals(damaged, Files.readAllBytes(dir.resolve(made)));
        assertEquals(new TreeSet<>(List.of(made, alice + "20990102-000000.damaged", alice + "20990103-000000.damaged")),
                kept(ALICE));
    }

    /**
     * On a nearly full disk the copy is not even tried (it would fill the disk to the last byte at every retry): nothing
     * is written or cut off, and the load waits. With room again, the copy is made and the file cut.
     */
    @Test
    void noCopyIsTriedWithoutRoomForIt() throws IOException {
        byte[] damaged = damagedJournal();
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        io.usableSpace.set(HistoryStore.COPY_FREE_MARGIN + damaged.length - 1);
        HistoryStore store = open(dir, io, HistoryStore.Settings.DEFAULTS);
        List<HistoryStore.Event> seen = new ArrayList<>();
        await(store, HistoryStore.Failed.class, f -> f.player().equals(ALICE), seen);
        store.load(ALICE);
        noLoad(store, ALICE, seen, 1500); // the scan and a retry 1 s later
        assertEquals(0, io.copies.get(), "a copy was tried without room for it");
        assertArrayEquals(damaged, Files.readAllBytes(file(ALICE)), "nothing is cut off");
        assertTrue(kept(ALICE).isEmpty());
        io.usableSpace.set(HistoryStore.COPY_FREE_MARGIN + damaged.length);
        HistoryStore.Loaded loaded = await(store, HistoryStore.Loaded.class, l -> l.player().equals(ALICE), seen);
        assertEquals(1, loaded.entries().size());
        close(store);
        assertEquals(1, kept(ALICE).size());
        assertEquals(damaged.length - 14, Files.size(file(ALICE)));
    }

    // ------------------------------------------------------------------------------------------------ existence

    /**
     * Whether Alice's file exists cannot be told (its attributes cannot be read). It is not taken as absent: her records
     * are not written (a new file would replace hers), the file stays byte for byte, and once it can be examined her load
     * delivers her saved step.
     */
    @Test
    void aFileWhoseExistenceCannotBeToldIsNotTakenAsAbsent() throws IOException {
        HistoryEntry a = entry(ALICE, "a", sparse(3), 1);
        HistoryStore store = open(dir);
        journal(store, a);
        close(store);
        byte[] saved = Files.readAllBytes(file(ALICE));
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        io.failExists = file(ALICE).getFileName().toString();
        HistoryStore reopened = open(dir, io, HistoryStore.Settings.DEFAULTS);
        List<HistoryStore.Event> seen = new ArrayList<>();
        await(reopened, HistoryStore.Failed.class, f -> f.player().equals(ALICE), seen);
        assertFalse(reopened.accepting(ALICE));
        journal(reopened, entry(ALICE, "new", sparse(4), 2));
        reopened.load(ALICE);
        noLoad(reopened, ALICE, seen, 300);
        assertArrayEquals(saved, Files.readAllBytes(file(ALICE)), "the file is untouched");
        io.failExists = null;
        HistoryStore.Loaded loaded = await(reopened, HistoryStore.Loaded.class, l -> l.player().equals(ALICE), seen);
        assertEquals(List.of(a.id()), loaded.entries().stream().map(HistoryStore.LoadedEntry::id).toList());
        close(reopened);
    }

    /**
     * The file system says Alice's file does not exist although it does. Her first record would create the file: finding
     * data in it, the store does not write over it but leaves it and reads it again; her saved step loads.
     */
    @Test
    void aFileFoundWhereNoneWasExpectedIsNotWrittenOver() throws IOException {
        HistoryEntry a = entry(ALICE, "a", sparse(3), 1);
        HistoryStore store = open(dir);
        journal(store, a);
        close(store);
        byte[] saved = Files.readAllBytes(file(ALICE));
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        io.existsLies = file(ALICE).getFileName().toString();
        HistoryStore reopened = open(dir, io, HistoryStore.Settings.DEFAULTS);
        List<HistoryStore.Event> seen = new ArrayList<>();
        HistoryStore.Scanned scanned = await(reopened, HistoryStore.Scanned.class, s -> true, seen);
        assertTrue(scanned.histories().isEmpty(), "(the file was not listed as a history)");
        journal(reopened, entry(ALICE, "new", sparse(4), 2));
        await(reopened, HistoryStore.Failed.class, f -> f.player().equals(ALICE), seen);
        assertArrayEquals(saved, Files.readAllBytes(file(ALICE)), "not written over");
        io.existsLies = null;
        reopened.load(ALICE);
        HistoryStore.Loaded loaded = await(reopened, HistoryStore.Loaded.class, l -> l.player().equals(ALICE), seen);
        assertEquals(List.of(a.id()), loaded.entries().stream().map(HistoryStore.LoadedEntry::id).toList());
        close(reopened);
    }

    // ------------------------------------------------------------------------------------------------ listing

    /**
     * Only a folder that does not exist lists as empty. One that exists but cannot be listed (here: a file in its place)
     * is an error, never "no history"; a folder inside it is not listed.
     */
    @Test
    void absentAndUnreadableFoldersAreToldApart() throws IOException {
        assertTrue(StorageIo.SYSTEM.list(dir.resolve("missing")).isEmpty());
        Path plain = dir.resolve("plain" + HistoryStore.EXTENSION);
        Files.write(plain, new byte[] {1});
        Files.createDirectories(dir.resolve("inner"));
        assertEquals(List.of(plain), StorageIo.SYSTEM.list(dir));
        assertThrows(IOException.class, () -> StorageIo.SYSTEM.list(plain));
    }

    /**
     * A listing that fails part way (the iterator's unchecked exception) is retried like any failed listing, not
     * abandoned: meanwhile the status says so (it is shown in {@code /sculptory history}); then the scan reports every player.
     */
    @Test
    void aListingThatFailsPartWayIsRetriedAndShownInTheStatus() throws IOException {
        HistoryStore store = open(dir);
        journal(store, entry(ALICE, "a", sparse(3), 1));
        close(store);
        StoreTestSupport.FaultyIo io = new StoreTestSupport.FaultyIo();
        io.listFailsPartWay.set(2);
        HistoryStore reopened = open(dir, io, HistoryStore.Settings.DEFAULTS);
        waitFor(() -> reopened.status().contains("cannot be listed"), "the status");
        assertTrue(reopened.status().contains(dir.toString()), reopened.status());
        HistoryStore.Scanned scanned = await(reopened, HistoryStore.Scanned.class, s -> true, new ArrayList<>());
        assertEquals(List.of(ALICE), scanned.histories().stream().map(StoredHistory::player).toList());
        assertFalse(reopened.status().contains("cannot be listed"), reopened.status());
        close(reopened);
    }
}
