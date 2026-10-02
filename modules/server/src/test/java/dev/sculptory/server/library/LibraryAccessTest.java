package dev.sculptory.server.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.protocol.v2.AssetAccess;
import dev.sculptory.protocol.v2.RejectReason;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Per-asset access on disk: the access file, who may read and change, grants following renames, moves and deletions. */
class LibraryAccessTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-4000-8000-00000000000c");
    /** Alice with {@code library.write}: a manager of the shared area. */
    private static final Library.Viewer WRITER = new Library.Viewer(ALICE, true, false);
    private static final Library.Viewer ALICE_ONLY = new Library.Viewer(ALICE, false, false);
    private static final Library.Viewer BOB_ONLY = new Library.Viewer(BOB, false, false);
    private static final Library.Viewer BOB_WRITER = new Library.Viewer(BOB, true, false);
    private static final Library.Viewer CAROL_ONLY = new Library.Viewer(CAROL, false, false);
    private static final Library.Viewer ADMIN = new Library.Viewer(CAROL, false, true);
    private static final String ALICE_DIR = "_players/" + ALICE;
    private static final AssetAccess BOB_G = AssetAccess.listed(List.of(new AssetAccess.Grantee(BOB, "Bob")));
    private static final AssetAccess CAROL_G = AssetAccess.listed(List.of(new AssetAccess.Grantee(CAROL, "Carol")));

    @TempDir
    Path temp;
    Path root;
    Library library;

    @BeforeEach
    void setUp() {
        root = temp.resolve("library");
        library = new Library(root, new Library.Settings(4096, 64 << 10, 8192, 4));
    }

    private static LibraryPath file(String path) {
        try {
            return LibraryPath.anyFile(path);
        } catch (LibraryPathException e) {
            throw new AssertionError(e);
        }
    }

    private static LibraryPath folder(String path) {
        try {
            return LibraryPath.folder(path);
        } catch (LibraryPathException e) {
            throw new AssertionError(e);
        }
    }

    private static byte[] bytes(int n, int seed) {
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) out[i] = (byte) (seed + i);
        return out;
    }

    @FunctionalInterface
    interface ThrowingRun {
        void run() throws LibraryException;
    }

    private static RejectReason refusal(ThrowingRun run) {
        return assertThrows(LibraryException.class, run::run).reason();
    }

    private List<String> listed(String path, Library.Viewer viewer) throws LibraryException {
        return library.list(path.isEmpty() ? LibraryPath.ROOT : folder(path), viewer).entries().stream()
                .map(Library.Entry::path).toList();
    }

    private Optional<Library.Entry> entry(String folder, String path, Library.Viewer viewer) throws LibraryException {
        return library.list(folder.isEmpty() ? LibraryPath.ROOT : folder(folder), viewer).entries().stream()
                .filter(entry -> entry.path().equals(path)).findFirst();
    }

    private Path accessFile(String folder) {
        Path dir = root;
        for (String segment : folder.split("/")) {
            if (!segment.isEmpty()) dir = dir.resolve(segment);
        }
        return dir.resolve(Library.ACCESS_FILE);
    }

    // ---------------------------------------------------------------- the file and the rules

    @Test
    void aGrantRoundTripsThroughTheHiddenFileAndAMissingFileMeansEveryone() throws Exception {
        String sha = library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        assertEquals(AssetAccess.EVERYONE, library.access(file("trees/oak.schem"), WRITER));
        assertFalse(Files.exists(accessFile("trees")), "nothing restricted: no file");
        assertEquals(List.of("trees/oak.schem"), listed("trees", BOB_ONLY));
        assertArrayEquals(bytes(100, 1), library.read(file("trees/oak.schem"), BOB_ONLY).bytes());

        assertEquals(AssetAccess.EVERYONE, library.setAccess(file("trees/oak.schem"), BOB_G, WRITER));
        assertTrue(Files.isRegularFile(accessFile("trees")));
        String json = Files.readString(accessFile("trees"), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"oak.schem\"") && json.contains("\"listed\"") && json.contains(BOB.toString())
                && json.contains("\"Bob\""), json);
        assertEquals(BOB_G, library.access(file("trees/oak.schem"), WRITER));

        // A fresh library (a restart) reads it at start, and the check without file I/O agrees.
        Library fresh = new Library(root, library.settings());
        fresh.start();
        assertEquals(BOB_G, fresh.access(file("trees/oak.schem"), WRITER));
        assertTrue(fresh.mayRead(file("trees/oak.schem"), BOB_ONLY));
        assertFalse(fresh.mayRead(file("trees/oak.schem"), CAROL_ONLY));
        assertEquals(Optional.of(file("trees/oak.schem")), fresh.find(sha, BOB_ONLY));
        assertEquals(Optional.empty(), fresh.find(sha, CAROL_ONLY), "a hash never leads to a restricted payload");

        // Back to everyone: the file goes.
        assertEquals(BOB_G, library.setAccess(file("trees/oak.schem"), AssetAccess.EVERYONE, WRITER));
        assertFalse(Files.exists(accessFile("trees")));
        assertTrue(library.mayRead(file("trees/oak.schem"), CAROL_ONLY));
    }

    @Test
    void aRestrictedEntryIsReadByItsGranteesItsManagersAndAdminsOnlyAndHiddenFromTheRest() throws Exception {
        String sha = library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        library.write(file("trees/moss.palette.json"), "{\"format\":\"sculptory:palette\",\"version\":1,\"entries\":[]}"
                .getBytes(StandardCharsets.UTF_8), WRITER, null);
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        library.setAccess(file("trees/moss.palette.json"), BOB_G, WRITER);

        // Carol has clipboard only and is not listed: nothing of it, not even the name.
        assertEquals(List.of(), listed("trees", CAROL_ONLY));
        // Refused exactly as a missing file is (one reason, one message): names cannot be enumerated.
        assertEquals(RejectReason.INVALID, refusal(() -> library.read(file("trees/oak.schem"), CAROL_ONLY)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.read(file("trees/moss.palette.json"), CAROL_ONLY)));
        String restrictedAnswer = assertThrows(LibraryException.class, () -> library.read(file("trees/oak.schem"), CAROL_ONLY)).getMessage();
        String missingAnswer = assertThrows(LibraryException.class, () -> library.read(file("trees/gone.schem"), CAROL_ONLY)).getMessage();
        assertEquals(restrictedAnswer.replace("oak", "gone"), missingAnswer);
        assertTrue(restrictedAnswer.contains("not found, or not shared with you"), restrictedAnswer);
        assertEquals(Optional.empty(), library.find(sha, CAROL_ONLY));
        assertFalse(library.mayRead(file("trees/oak.schem"), CAROL_ONLY));

        // Bob is listed: he sees it with the lock, reads it and finds it by hash.
        assertEquals(List.of("trees/moss.palette.json", "trees/oak.schem"), listed("trees", BOB_ONLY));
        assertTrue(entry("trees", "trees/oak.schem", BOB_ONLY).orElseThrow().restricted());
        assertArrayEquals(bytes(100, 1), library.read(file("trees/oak.schem"), BOB_ONLY).bytes());
        assertEquals(Optional.of(file("trees/oak.schem")), library.find(sha, BOB_ONLY));
        // But he may not see or change its access (he may not manage the shared area).
        assertEquals(RejectReason.NO_PERMISSION, refusal(() -> library.access(file("trees/oak.schem"), BOB_ONLY)));
        assertEquals(RejectReason.NO_PERMISSION, refusal(() -> library.setAccess(file("trees/oak.schem"), CAROL_G, BOB_ONLY)));
        assertEquals(BOB_G, library.access(file("trees/oak.schem"), WRITER), "unchanged");

        // A manager of the shared area who is not listed reads it too (they could grant themselves), and admins.
        Library.Viewer davidWriter = new Library.Viewer(new UUID(7, 7), true, false);
        assertTrue(library.mayRead(file("trees/oak.schem"), davidWriter));
        assertEquals(List.of("trees/moss.palette.json", "trees/oak.schem"), listed("trees", davidWriter));
        assertArrayEquals(bytes(100, 1), library.read(file("trees/oak.schem"), ADMIN).bytes());
        assertEquals(BOB_G, library.access(file("trees/oak.schem"), ADMIN));
        assertTrue(Library.mayChangeAccess(file("trees/oak.schem"), davidWriter));
        assertFalse(Library.mayChangeAccess(folder("trees"), WRITER), "folders have no access of their own");

        // Access changes are seen by the cache-only check at once.
        library.setAccess(file("trees/oak.schem"), CAROL_G, WRITER);
        assertTrue(library.mayRead(file("trees/oak.schem"), CAROL_ONLY) && !library.mayRead(file("trees/oak.schem"), BOB_ONLY));
        assertEquals(List.of("trees/oak.schem"), listed("trees", CAROL_ONLY));
    }

    @Test
    void aGrantInAPlayersOwnFolderLetsAnotherPlayerReadItAndListsItUnderSharedWithMe() throws Exception {
        String sha = library.write(file(ALICE_DIR + "/huts/hut.schem"), bytes(50, 3), ALICE_ONLY, null);
        library.write(file(ALICE_DIR + "/secret.schem"), bytes(50, 4), ALICE_ONLY, null);
        library.write(file("_players/" + BOB + "/mine.schem"), bytes(50, 5), BOB_ONLY, null);
        assertEquals(RejectReason.NO_PERMISSION, refusal(() -> library.read(file(ALICE_DIR + "/huts/hut.schem"), BOB_ONLY)));
        assertEquals(RejectReason.NO_PERMISSION, refusal(() -> library.setAccess(file(ALICE_DIR + "/huts/hut.schem"), BOB_G, BOB_ONLY)),
                "another player's entry needs admin");
        assertEquals(RejectReason.NO_PERMISSION, refusal(() -> library.setAccess(file(ALICE_DIR + "/huts/hut.schem"), BOB_G, BOB_WRITER)),
                "library.write does not reach into a player folder");

        library.setAccess(file(ALICE_DIR + "/huts/hut.schem"), BOB_G, ALICE_ONLY);
        assertArrayEquals(bytes(50, 3), library.read(file(ALICE_DIR + "/huts/hut.schem"), BOB_ONLY).bytes());
        assertEquals(Optional.of(file(ALICE_DIR + "/huts/hut.schem")), library.find(sha, BOB_ONLY));
        assertEquals(RejectReason.NO_PERMISSION, refusal(() -> library.read(file(ALICE_DIR + "/secret.schem"), BOB_ONLY)));
        assertEquals(RejectReason.NO_PERMISSION, refusal(() -> library.list(folder(ALICE_DIR + "/huts"), BOB_ONLY)),
                "a grant opens the entry, never the folder");

        Library.Listing shared = library.sharedWithMe(BOB_ONLY);
        assertEquals(LibraryPath.SHARED, shared.folder());
        assertEquals(List.of(ALICE_DIR + "/huts/hut.schem"), shared.entries().stream().map(Library.Entry::path).toList(),
                "granted entries only, with their real paths; never one's own");
        Library.Entry entry = shared.entries().get(0);
        assertTrue(entry.restricted() && entry.kind() == LibraryPath.Kind.SCHEMATIC && entry.bytes() == 50
                && entry.sha256().equals(sha));
        assertEquals(List.of(), library.sharedWithMe(CAROL_ONLY).entries());
        assertEquals(List.of(), library.sharedWithMe(ALICE_ONLY).entries(), "one's own folder is not shared with oneself");
        // A shared-area grant is not "shared with me" either: the entry is in its folder anyway.
        library.write(file("trees/oak.schem"), bytes(10, 6), WRITER, null);
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        assertEquals(1, library.sharedWithMe(BOB_ONLY).entries().size());

        // The root ends with the virtual folder, for everyone.
        List<String> rootEntries = listed("", BOB_ONLY);
        assertEquals(LibraryPath.SHARED, rootEntries.get(rootEntries.size() - 1));
        assertTrue(listed("", CAROL_ONLY).contains(LibraryPath.SHARED));
    }

    @Test
    void sharedWithMeIsCappedLikeAListing() throws Exception {
        for (int i = 0; i < 6; i++) {
            library.write(file(ALICE_DIR + "/a" + i + ".schem"), bytes(10, i), ALICE_ONLY, null);
            library.setAccess(file(ALICE_DIR + "/a" + i + ".schem"), BOB_G, ALICE_ONLY);
        }
        Library.Listing shared = library.sharedWithMe(BOB_ONLY);
        assertEquals(4, shared.entries().size());
        assertTrue(shared.truncated());
        assertEquals(ALICE_DIR + "/a0.schem", shared.entries().get(0).path());
    }

    // ---------------------------------------------------------------- corrupt files

    @Test
    void aCorruptAccessFileFailsClosedUntilAnAdminFixesIt() throws Exception {
        String sha = library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        library.write(file("trees/birch.schem"), bytes(100, 2), WRITER, null);
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        Files.writeString(accessFile("trees"), "{\"version\": 1, \"entries\": {\"oak.schem\": {\"mode\": \"listed\"",
                StandardCharsets.UTF_8);

        // Every entry of the folder is now admins-only: listed with the lock, unreadable, not found by hash.
        assertEquals(List.of(), listed("trees", BOB_ONLY));
        assertEquals(List.of(), listed("trees", WRITER), "managers too");
        assertEquals(RejectReason.INVALID, refusal(() -> library.read(file("trees/birch.schem"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.read(file("trees/oak.schem"), BOB_ONLY)));
        assertEquals(Optional.empty(), library.find(sha, BOB_ONLY));
        assertFalse(library.mayRead(file("trees/birch.schem"), WRITER));
        assertEquals(List.of("trees/birch.schem", "trees/oak.schem"), listed("trees", ADMIN));
        assertTrue(entry("trees", "trees/birch.schem", ADMIN).orElseThrow().restricted());
        assertArrayEquals(bytes(100, 2), library.read(file("trees/birch.schem"), ADMIN).bytes());
        // Nothing reads or rewrites it: an admin fixes it on disk.
        assertEquals(RejectReason.INVALID, refusal(() -> library.access(file("trees/oak.schem"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.setAccess(file("trees/birch.schem"), BOB_G, ADMIN)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.move(file("trees/birch.schem"), file("trees/b2.schem"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.delete(file("trees/birch.schem"), WRITER)));
        assertTrue(Files.readString(accessFile("trees")).endsWith("\"listed\""), "left as it was");
        // Other folders are untouched.
        library.write(file("rocks/stone.schem"), bytes(10, 3), WRITER, null);
        assertEquals(List.of("rocks/stone.schem"), listed("rocks", BOB_ONLY));

        // Fixed on disk: read again (the file changed), and everything is back.
        Files.writeString(accessFile("trees"), "{\"version\":1,\"entries\":{\"oak.schem\":{\"mode\":\"listed\",\"players\":[{\"uuid\":\""
                + BOB + "\",\"name\":\"Bob\"}]}}}", StandardCharsets.UTF_8);
        assertEquals(List.of("trees/birch.schem", "trees/oak.schem"), listed("trees", BOB_ONLY));
        assertEquals(List.of("trees/birch.schem"), listed("trees", CAROL_ONLY));
        assertEquals(BOB_G, library.access(file("trees/oak.schem"), WRITER));
        // Removed on disk: everyone again.
        Files.delete(accessFile("trees"));
        assertEquals(List.of("trees/birch.schem", "trees/oak.schem"), listed("trees", CAROL_ONLY));
    }

    @Test
    void invalidContentIsCorruptNotIgnored() throws Exception {
        library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        List<String> bad = List.of(
                "{\"version\": 2, \"entries\": {}}",
                "{\"version\": 1, \"entries\": {\"oak.schem\": {\"mode\": \"everyone\", \"players\": []}}}",
                "{\"version\": 1, \"entries\": {\"oak.schem\": {\"mode\": \"listed\", \"players\": []}}}",
                "{\"version\": 1, \"entries\": {\"oak.schem\": {\"mode\": \"listed\", \"players\": [{\"uuid\": \"nope\", \"name\": \"Bob\"}]}}}",
                "{\"version\": 1, \"entries\": {\"oak.schem\": {\"mode\": \"listed\", \"players\": [{\"uuid\": \"" + BOB + "\"}]}}}",
                "{\"version\": 1, \"entries\": {\"../oak.schem\": {\"mode\": \"listed\", \"players\": [{\"uuid\": \"" + BOB + "\", \"name\": \"Bob\"}]}}}",
                "{\"version\": 1, \"entries\": {\"oak.schem\": {\"mode\": \"listed\", \"players\": [{\"uuid\": \"" + BOB
                        + "\", \"name\": \"Bob\"}, {\"uuid\": \"" + BOB + "\", \"name\": \"Bob2\"}]}}}",
                "[]",
                "");
        for (String content : bad) {
            Files.writeString(accessFile("trees"), content, StandardCharsets.UTF_8);
            Files.setLastModifiedTime(accessFile("trees"), java.nio.file.attribute.FileTime.fromMillis(
                    Files.getLastModifiedTime(accessFile("trees")).toMillis() + 5000));
            assertEquals(List.of(), listed("trees", BOB_ONLY), content);
            assertEquals(List.of("trees/oak.schem"), listed("trees", ADMIN), content);
        }
    }

    // ---------------------------------------------------------------- grants follow the entry

    @Test
    void grantsFollowRenamesMovesAndFolderRenames() throws Exception {
        library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        library.write(file("trees/birch.schem"), bytes(100, 2), WRITER, null);
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        library.setAccess(file("trees/birch.schem"), CAROL_G, WRITER);

        // A rename in place.
        library.move(file("trees/oak.schem"), file("trees/old_oak.schem"), WRITER);
        assertEquals(BOB_G, library.access(file("trees/old_oak.schem"), WRITER));
        assertFalse(Files.readString(accessFile("trees")).contains("\"oak.schem\""), "the old name is gone from the file");
        assertEquals(List.of("trees/old_oak.schem"), listed("trees", BOB_ONLY));
        assertEquals(List.of("trees/birch.schem"), listed("trees", CAROL_ONLY));

        // A move to another folder (the target's file is made, the source's keeps the other grant).
        library.createFolder(folder("rocks"), WRITER);
        library.move(file("trees/old_oak.schem"), file("rocks/oak.schem"), WRITER);
        assertEquals(BOB_G, library.access(file("rocks/oak.schem"), WRITER));
        assertEquals(CAROL_G, library.access(file("trees/birch.schem"), WRITER));
        assertTrue(Files.isRegularFile(accessFile("rocks")) && Files.isRegularFile(accessFile("trees")));
        assertEquals(List.of("rocks/oak.schem"), listed("rocks", BOB_ONLY));
        assertEquals(List.of(), listed("rocks", CAROL_ONLY));

        // Across owners: into Alice's folder (made on the way) and back; the entry stays Bob's to read.
        library.move(file("rocks/oak.schem"), file(ALICE_DIR + "/oak.schem"), WRITER);
        assertFalse(Files.exists(accessFile("rocks")), "nothing restricted is left in rocks");
        assertEquals(BOB_G, library.access(file(ALICE_DIR + "/oak.schem"), ALICE_ONLY));
        assertArrayEquals(bytes(100, 1), library.read(file(ALICE_DIR + "/oak.schem"), BOB_ONLY).bytes());
        assertEquals(List.of(ALICE_DIR + "/oak.schem"), library.sharedWithMe(BOB_ONLY).entries().stream().map(Library.Entry::path).toList());
        library.move(file(ALICE_DIR + "/oak.schem"), file("rocks/oak.schem"), WRITER);
        assertEquals(BOB_G, library.access(file("rocks/oak.schem"), WRITER));
        assertEquals(List.of(), library.sharedWithMe(BOB_ONLY).entries());

        // A folder rename carries the file along; the cache-only check knows the new paths at once (no open moment
        // for a cached asset under its new path).
        library.move(folder("trees"), folder("forest"), WRITER);
        assertFalse(library.mayRead(file("forest/birch.schem"), BOB_ONLY));
        assertTrue(library.mayRead(file("forest/birch.schem"), CAROL_ONLY));
        assertEquals(CAROL_G, library.access(file("forest/birch.schem"), WRITER));
        assertEquals(List.of("forest/birch.schem"), listed("forest", CAROL_ONLY));
        assertEquals(List.of(), listed("forest", BOB_ONLY));
        assertTrue(library.mayRead(file("forest/birch.schem"), CAROL_ONLY) && !library.mayRead(file("forest/birch.schem"), BOB_ONLY));

        // The last move out of a folder's file with a single grant removes the file; the folder is then deletable.
        library.move(file("forest/birch.schem"), file("rocks/birch.schem"), WRITER);
        assertFalse(Files.exists(accessFile("forest")));
        library.delete(folder("forest"), WRITER);
        assertFalse(Files.exists(root.resolve("forest")));
    }

    @Test
    void aFolderHoldingOnlyItsAccessFileIsEmptyAndTheFileIsNeverListedNorCounted() throws Exception {
        library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        // Removed on disk by an admin: the stale access file alone must not keep the folder.
        Files.delete(root.resolve("trees").resolve("oak.schem"));
        assertEquals(List.of(), listed("trees", ADMIN));
        assertEquals(new Library.Usage(100, 1, 1), library.usage(), "counted before the deletion on disk");
        Library fresh = new Library(root, library.settings());
        fresh.start();
        assertEquals(new Library.Usage(0, 0, 1), fresh.usage(), "the access file counts toward nothing");
        fresh.delete(folder("trees"), WRITER);
        assertFalse(Files.exists(root.resolve("trees")));
        assertEquals(new Library.Usage(0, 0, 0), fresh.usage());
    }

    @Test
    void aDeletedEntryTakesItsGrantIntoTheTrash() throws Exception {
        library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        library.write(file("trees/birch.schem"), bytes(100, 2), WRITER, null);
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        library.setAccess(file("trees/birch.schem"), CAROL_G, WRITER);
        String where = library.delete(file("trees/oak.schem"), WRITER);
        Path bin = root.resolve(where).getParent();
        assertTrue(Files.isRegularFile(bin.resolve("oak.schem")));
        String kept = Files.readString(bin.resolve(Library.ACCESS_FILE), StandardCharsets.UTF_8);
        assertTrue(kept.contains("\"oak.schem\"") && kept.contains(BOB.toString()), kept);
        String left = Files.readString(accessFile("trees"), StandardCharsets.UTF_8);
        assertTrue(left.contains("\"birch.schem\"") && !left.contains("\"oak.schem\""), left);
        assertEquals(CAROL_G, library.access(file("trees/birch.schem"), WRITER));
        // A new file under the old name starts open.
        library.write(file("trees/oak.schem"), bytes(10, 9), WRITER, null);
        assertEquals(AssetAccess.EVERYONE, library.access(file("trees/oak.schem"), WRITER));
        assertEquals(List.of("trees/oak.schem"), listed("trees", BOB_ONLY));
    }

    @Test
    void aSaveOverARestrictedEntryKeepsItsAccess() throws Exception {
        library.write(file("trees/moss.palette.json"), "{}".getBytes(StandardCharsets.UTF_8), WRITER, null);
        library.setAccess(file("trees/moss.palette.json"), BOB_G, WRITER);
        library.write(file("trees/moss.palette.json"), "{\"v\":2}".getBytes(StandardCharsets.UTF_8), WRITER, null);
        assertEquals(BOB_G, library.access(file("trees/moss.palette.json"), WRITER));
        assertEquals(List.of(), listed("trees", CAROL_ONLY));
    }

    // ---------------------------------------------------------------- caps, checks, concurrency

    @Test
    void grantsNeedResolvedPlayersAndAnExistingFile() throws Exception {
        library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        AssetAccess unresolved = AssetAccess.listed(List.of(new AssetAccess.Grantee(null, "Typed")));
        assertEquals(RejectReason.INVALID, refusal(() -> library.setAccess(file("trees/oak.schem"), unresolved, WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.access(file("trees/gone.schem"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.setAccess(file("trees/gone.schem"), BOB_G, WRITER)));
        assertFalse(Files.exists(accessFile("trees")));
        List<AssetAccess.Grantee> many = new ArrayList<>();
        for (int i = 0; i < AssetAccess.MAX_PLAYERS; i++) many.add(new AssetAccess.Grantee(new UUID(5, i), "p" + i));
        library.setAccess(file("trees/oak.schem"), AssetAccess.listed(many), WRITER);
        assertEquals(AssetAccess.MAX_PLAYERS, library.access(file("trees/oak.schem"), WRITER).players().size());
        assertTrue(Files.size(accessFile("trees")) < 32 << 10, "a full grant stays small");
        assertTrue(library.mayRead(file("trees/oak.schem"), new Library.Viewer(new UUID(5, 7), false, false)));
    }

    @Test
    void twoWritersToOneFolderBothPersistUnderTheLock() throws Exception {
        library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        library.write(file("trees/birch.schem"), bytes(100, 2), WRITER, null);
        CyclicBarrier ready = new CyclicBarrier(2);
        CountDownLatch done = new CountDownLatch(2);
        List<Throwable> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        ThrowingRun[] writers = {
                () -> library.setAccess(file("trees/oak.schem"), BOB_G, WRITER),
                () -> library.setAccess(file("trees/birch.schem"), CAROL_G, WRITER)};
        for (ThrowingRun writer : writers) {
            new Thread(() -> {
                try {
                    ready.await(5, TimeUnit.SECONDS);
                    writer.run();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            }).start();
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(List.of(), failures);
        assertEquals(BOB_G, library.access(file("trees/oak.schem"), WRITER));
        assertEquals(CAROL_G, library.access(file("trees/birch.schem"), WRITER));
        try (Stream<Path> files = Files.list(root.resolve("trees"))) {
            assertEquals(List.of(Library.ACCESS_FILE, "birch.schem", "oak.schem"),
                    files.map(path -> path.getFileName().toString()).sorted().toList(), "no temp file left");
        }
    }

    @Test
    void readableUnderIsTheRuleThePushesUse() {
        LibraryPath shared = file("trees/oak.schem");
        LibraryPath own = file(ALICE_DIR + "/oak.schem");
        assertTrue(Library.readableUnder(shared, AssetAccess.EVERYONE, BOB_ONLY));
        assertFalse(Library.readableUnder(shared, CAROL_G, BOB_ONLY));
        assertTrue(Library.readableUnder(shared, CAROL_G, CAROL_ONLY));
        assertTrue(Library.readableUnder(shared, CAROL_G, BOB_WRITER), "a manager");
        assertTrue(Library.readableUnder(shared, CAROL_G, ADMIN));
        assertFalse(Library.readableUnder(own, AssetAccess.EVERYONE, BOB_ONLY));
        assertTrue(Library.readableUnder(own, BOB_G, BOB_ONLY));
        assertTrue(Library.readableUnder(own, BOB_G, ALICE_ONLY), "the owner");
        assertFalse(Library.readableUnder(own, BOB_G, BOB_WRITER.equals(CAROL_ONLY) ? ADMIN : CAROL_ONLY));
    }

    @Test
    void refusalsNeverNameServerPaths() throws Exception {
        library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        Files.writeString(accessFile("trees"), "nope", StandardCharsets.UTF_8);
        for (ThrowingRun run : List.<ThrowingRun>of(
                () -> library.read(file("trees/oak.schem"), CAROL_ONLY),
                () -> library.access(file("trees/oak.schem"), WRITER),
                () -> library.setAccess(file("trees/oak.schem"), BOB_G, WRITER),
                () -> library.setAccess(file("trees/oak.schem"), BOB_G, BOB_ONLY))) {
            String message = assertThrows(LibraryException.class, run::run).getMessage();
            assertFalse(message.contains(root.toString()) || message.contains(temp.toString()), message);
        }
    }

    // ---------------------------------------------------------------- spelling (case-insensitive file systems)

    @Test
    void aPathSpeltOtherwiseThanOnDiskIsRefusedEverywhere() throws Exception {
        // The rule itself, independent of the file system: the real path's spelling must match segment for segment.
        Path realRoot = root.toAbsolutePath();
        Path onDisk = realRoot.resolve("trees").resolve("oak.schem");
        Library.requireSpelling(file("trees/oak.schem"), onDisk, realRoot);
        Library.requireSpelling(LibraryPath.ROOT, realRoot, realRoot);
        for (String spelt : List.of("trees/OAK.schem", "Trees/oak.schem", "TREES/OAK.schem")) {
            assertEquals(RejectReason.INVALID, refusal(() -> Library.requireSpelling(file(spelt), onDisk, realRoot)), spelt);
        }
        assertEquals(RejectReason.INVALID, refusal(() -> Library.requireSpelling(folder("Trees"), realRoot.resolve("trees"), realRoot)));

        // Through the library (on a file system that ignores case this is the bypass; elsewhere the name is simply
        // not there): a differently spelt request never reads, changes or grants anything, and never sees a byte.
        library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        for (String spelt : List.of("trees/OAK.schem", "Trees/oak.schem")) {
            assertEquals(RejectReason.INVALID, refusal(() -> library.read(file(spelt), CAROL_ONLY)), spelt);
            assertEquals(RejectReason.INVALID, refusal(() -> library.read(file(spelt), WRITER)), spelt + " even for a manager");
            assertEquals(RejectReason.INVALID, refusal(() -> library.read(file(spelt), ADMIN)), spelt + " even for an admin");
            assertEquals(RejectReason.INVALID, refusal(() -> library.access(file(spelt), WRITER)), spelt);
            assertEquals(RejectReason.INVALID, refusal(() -> library.setAccess(file(spelt), CAROL_G, WRITER)), spelt);
            assertEquals(RejectReason.INVALID, refusal(() -> library.move(file(spelt), file("trees/oak2.schem"), WRITER)), spelt);
            assertEquals(RejectReason.INVALID, refusal(() -> library.delete(file(spelt), WRITER)), spelt);
        }
        assertEquals(RejectReason.INVALID, refusal(() -> library.list(folder("Trees"), WRITER)));
        assertEquals(BOB_G, library.access(file("trees/oak.schem"), WRITER), "unchanged");
        String json = Files.readString(accessFile("trees"), StandardCharsets.UTF_8);
        assertFalse(json.contains("OAK") || json.contains("oak2"), json);
        assertTrue(Files.isRegularFile(root.resolve("trees").resolve("oak.schem")), "still there under its name");
        assertArrayEquals(bytes(100, 1), library.read(file("trees/oak.schem"), BOB_ONLY).bytes());
    }

    @Test
    void aStaleGrantUnderANewOrIncomingOpenFilesNameIsCleared() throws Exception {
        library.write(file("trees/oak.schem"), bytes(100, 1), WRITER, null);
        library.write(file("rocks/stone.schem"), bytes(100, 2), WRITER, null);
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        // The entry is removed on disk (an admin), its grant left behind: a new file under the name starts open.
        Files.delete(root.resolve("trees").resolve("oak.schem"));
        library.write(file("trees/oak.schem"), bytes(10, 3), WRITER, null);
        assertEquals(AssetAccess.EVERYONE, library.access(file("trees/oak.schem"), WRITER));
        assertEquals(List.of("trees/oak.schem"), listed("trees", CAROL_ONLY));
        assertFalse(Files.exists(accessFile("trees")));
        // The same for an open file moved onto a name with a stale grant.
        library.setAccess(file("trees/oak.schem"), BOB_G, WRITER);
        Files.delete(root.resolve("trees").resolve("oak.schem"));
        library.move(file("rocks/stone.schem"), file("trees/oak.schem"), WRITER);
        assertEquals(AssetAccess.EVERYONE, library.access(file("trees/oak.schem"), WRITER));
        assertEquals(List.of("trees/oak.schem"), listed("trees", CAROL_ONLY));
    }

    @Test
    void theAccessFileNameIsNeverALibraryPath() {
        assertThrows(LibraryPathException.class, () -> LibraryPath.anyFile("trees/" + Library.ACCESS_FILE));
        assertThrows(LibraryPathException.class, () -> LibraryPath.folder(LibraryPath.SHARED));
        assertThrows(LibraryPathException.class, () -> LibraryPath.folder("trees/" + LibraryPath.SHARED));
    }
}
