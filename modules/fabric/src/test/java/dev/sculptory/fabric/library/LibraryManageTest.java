package dev.sculptory.fabric.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.sculptory.core.Sha256;
import dev.sculptory.protocol.v2.RejectReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** M4 library management on disk: rename, move, delete into the trash, folders, permissions, races, the trash purge. */
class LibraryManageTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    /** Alice with {@code library.write}. */
    private static final Library.Viewer WRITER = new Library.Viewer(ALICE, true, false);
    /** Alice without it: her own folder only. */
    private static final Library.Viewer ALICE_ONLY = new Library.Viewer(ALICE, false, false);
    private static final Library.Viewer BOB_ONLY = new Library.Viewer(BOB, false, false);
    private static final Library.Viewer BOB_WRITER = new Library.Viewer(BOB, true, false);
    private static final Library.Viewer ADMIN = new Library.Viewer(BOB, false, true);
    private static final String ALICE_DIR = "_players/" + ALICE;
    private static final String BOB_DIR = "_players/" + BOB;
    private static final long NOW = 1_790_000_000_000L; // 2026-09-21

    @TempDir
    Path temp;
    Path root;
    long[] clock = {NOW};
    Library library;

    @BeforeEach
    void setUp() {
        root = temp.resolve("library");
        library = new Library(root, new Library.Settings(4096, 64 << 10, 8192, 50), () -> clock[0]);
    }

    private static LibraryPath file(String path) {
        try {
            return LibraryPath.file(path);
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

    private RejectReason refusal(ThrowingRun run) {
        return assertThrows(LibraryException.class, run::run).reason();
    }

    private String detail(ThrowingRun run) {
        return assertThrows(LibraryException.class, run::run).getMessage();
    }

    @FunctionalInterface
    interface ThrowingRun {
        void run() throws LibraryException;
    }

    private List<String> listed(String path, Library.Viewer viewer) throws LibraryException {
        return library.list(path.isEmpty() ? LibraryPath.ROOT : folder(path), viewer).entries().stream()
                .map(Library.Entry::path).toList();
    }

    private List<Path> trashFiles() throws IOException {
        Path trash = root.resolve(Library.TRASH_FOLDER);
        if (!Files.exists(trash)) return List.of();
        try (Stream<Path> files = Files.walk(trash)) {
            return files.filter(Files::isRegularFile).toList();
        }
    }

    // ---------------------------------------------------------------- rename and move

    @Test
    void aRenameKeepsTheContentAndTheHashFindsItUnderItsNewName() throws Exception {
        byte[] content = bytes(300, 1);
        String sha = library.write(file("trees/oak.schem"), content, WRITER, null);
        Path before = root.resolve("trees").resolve("oak.schem");
        FileTime mtime = Files.getLastModifiedTime(before);
        library.move(file("trees/oak.schem"), file("trees/old_oak.schem"), WRITER);

        assertFalse(Files.exists(before));
        Path after = root.resolve("trees").resolve("old_oak.schem");
        assertArrayEquals(content, Files.readAllBytes(after));
        assertEquals(mtime, Files.getLastModifiedTime(after), "a rename keeps the modification time");
        // References by content hash (scatter mixes, presets, pastes) find the renamed file without re-hashing.
        assertEquals(file("trees/old_oak.schem"), library.find(sha, BOB_ONLY).orElseThrow());
        assertEquals(List.of("trees/old_oak.schem"), listed("trees", BOB_ONLY));
        assertEquals(sha, library.list(folder("trees"), BOB_ONLY).entries().get(0).sha256());
        // The index on disk follows too (a fresh library, as after a restart, trusts it).
        library.flush();
        Library fresh = new Library(root, library.settings());
        assertEquals(file("trees/old_oak.schem"), fresh.find(sha, BOB_ONLY).orElseThrow());
        assertEquals(new Library.Usage(300, 1, 1), library.usage());
    }

    @Test
    void aFileMovesToAnotherFolderAndItsBytesChangeQuota() throws Exception {
        library.write(file("trees/oak.schem"), bytes(1000, 2), WRITER, null);
        library.createFolder(folder("rocks"), WRITER);
        library.move(file("trees/oak.schem"), file("rocks/oak.schem"), WRITER);
        assertEquals(List.of("rocks/oak.schem"), listed("rocks", WRITER));
        assertEquals(List.of(), listed("trees", WRITER));

        // Into Alice's own folder (made on the way, as for a save): the bytes now count as hers.
        library.move(file("rocks/oak.schem"), file(ALICE_DIR + "/oak.schem"), WRITER);
        assertEquals(new Library.Usage(1000, 1, 1), library.usage(ALICE));
        assertTrue(Files.isRegularFile(root.resolve("_players").resolve(ALICE.toString()).resolve("oak.schem")));
        // And back out of it into the shared area.
        library.move(file(ALICE_DIR + "/oak.schem"), file("trees/oak.schem"), WRITER);
        assertEquals(new Library.Usage(0, 0, 1), library.usage(ALICE));
        assertEquals(new Library.Usage(1000, 1, 4), library.usage(), "trees, rocks, _players, _players/<alice>");

        // A folder that does not exist is not made for a move.
        assertEquals("no such folder: nowhere", detail(() -> library.move(file("trees/oak.schem"),
                file("nowhere/oak.schem"), WRITER)));
        assertFalse(Files.exists(root.resolve("nowhere")));
        // Nor a sub-folder of a player folder.
        assertEquals(RejectReason.INVALID, refusal(() -> library.move(file("trees/oak.schem"),
                file(ALICE_DIR + "/sub/oak.schem"), WRITER)));
    }

    @Test
    void aMoveIntoAFullPlayerFolderIsRefusedAndChangesNothing() throws Exception {
        library.write(file(ALICE_DIR + "/mine.schem"), bytes(4000, 3), ALICE_ONLY, null);
        library.write(file(ALICE_DIR + "/two.schem"), bytes(4000, 4), ALICE_ONLY, null);
        library.write(file("shared/big.schem"), bytes(1000, 5), WRITER, null);
        assertEquals(RejectReason.TOO_LARGE, refusal(() -> library.move(file("shared/big.schem"),
                file(ALICE_DIR + "/big.schem"), WRITER)), "8000 + 1000 bytes > 8192");
        assertTrue(Files.exists(root.resolve("shared").resolve("big.schem")));
        assertEquals(new Library.Usage(8000, 2, 1), library.usage(ALICE));
    }

    @Test
    void nothingIsEverReplaced() throws Exception {
        byte[] oak = bytes(100, 6);
        byte[] birch = bytes(200, 7);
        String oakSha = library.write(file("trees/oak.schem"), oak, WRITER, null);
        String birchSha = library.write(file("trees/birch.schem"), birch, WRITER, null);
        assertEquals("already exists: trees/birch.schem", detail(() -> library.move(file("trees/oak.schem"),
                file("trees/birch.schem"), WRITER)));
        assertArrayEquals(oak, Files.readAllBytes(root.resolve("trees").resolve("oak.schem")));
        assertArrayEquals(birch, Files.readAllBytes(root.resolve("trees").resolve("birch.schem")));
        assertEquals(file("trees/oak.schem"), library.find(oakSha, WRITER).orElseThrow());
        assertEquals(file("trees/birch.schem"), library.find(birchSha, WRITER).orElseThrow());

        // Folders: onto an existing folder, and onto a file-named entry.
        library.createFolder(folder("a"), WRITER);
        library.createFolder(folder("b"), WRITER);
        library.write(file("a/x.schem"), bytes(10, 8), WRITER, null);
        assertEquals(RejectReason.INVALID, refusal(() -> library.move(folder("a"), folder("b"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.createFolder(folder("b"), WRITER)));
        assertEquals(List.of("a/x.schem"), listed("a", WRITER));
        // A folder named like a file is not a file.
        Files.createDirectories(root.resolve("trees").resolve("dir.schem"));
        assertEquals(RejectReason.INVALID, refusal(() -> library.move(file("trees/oak.schem"),
                file("trees/dir.schem"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.move(file("trees/dir.schem"),
                file("trees/new.schem"), WRITER)), "not a file");
    }

    @Test
    void aMissingSourceIsACleanRefusal() throws Exception {
        library.write(file("trees/oak.schem"), bytes(100, 9), WRITER, null);
        Files.delete(root.resolve("trees").resolve("oak.schem")); // gone behind the library's back
        assertEquals("not found: trees/oak.schem", detail(() -> library.move(file("trees/oak.schem"),
                file("trees/elm.schem"), WRITER)));
        assertEquals("not found: trees/oak.schem", detail(() -> library.delete(file("trees/oak.schem"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.move(folder("nope"), folder("nope2"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.delete(folder("nope"), WRITER)));
        assertFalse(Files.exists(root.resolve("trees").resolve("elm.schem")));
        assertEquals(List.of(), trashFiles());
    }

    @Test
    void aChangeOfCaseOnlyIsARename() throws Exception {
        byte[] content = bytes(50, 10);
        String sha = library.write(file("trees/Oak.schem"), content, WRITER, null);
        library.move(file("trees/Oak.schem"), file("trees/oak.schem"), WRITER);
        try (Stream<Path> files = Files.list(root.resolve("trees"))) {
            assertEquals(List.of("oak.schem"), files.map(p -> p.getFileName().toString()).toList(),
                    "renamed, and no intermediate name left");
        }
        assertEquals(file("trees/oak.schem"), library.find(sha, WRITER).orElseThrow());
        library.createFolder(folder("Rocks"), WRITER);
        library.move(folder("Rocks"), folder("rocks"), WRITER);
        try (Stream<Path> files = Files.list(root)) {
            assertTrue(files.map(p -> p.getFileName().toString()).toList().contains("rocks"));
        }
    }

    @Test
    void foldersAreRenamedInPlaceWithEverythingInThem() throws Exception {
        byte[] deep = bytes(64, 11);
        String sha = library.write(file("trees/big/oak.schem"), deep, WRITER, null);
        library.write(file("trees/small.schem"), bytes(32, 12), WRITER, null);
        library.move(folder("trees"), folder("forest"), WRITER);
        assertEquals(List.of("forest/big", "forest/small.schem"), listed("forest", WRITER));
        assertEquals(file("forest/big/oak.schem"), library.find(sha, WRITER).orElseThrow());
        assertFalse(Files.exists(root.resolve("trees")));
        assertEquals(new Library.Usage(96, 2, 2), library.usage());

        assertEquals(RejectReason.INVALID, refusal(() -> library.move(folder("forest"), folder("elsewhere/forest"),
                WRITER)), "folders are only renamed, not moved");
        assertEquals(RejectReason.INVALID, refusal(() -> library.move(folder("forest"), file("forest.schem"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.move(folder("forest"), folder("forest"), WRITER)));
    }

    @Test
    void aFolderRenameMayNotPushAPathInsidePastTheLengthCap() throws Exception {
        String name = "n".repeat(60);
        String deepFile = String.join("/", "a", name, name, name, name, "x.schem"); // 1 + 4 * 61 + 8 = 253 characters
        library.write(file(deepFile), bytes(10, 13), WRITER, null);
        String longName = "a" + "b".repeat(63);
        assertTrue(detail(() -> library.move(folder("a"), folder(longName), WRITER)).contains("longer than 256"));
        assertTrue(Files.exists(root.resolve("a")));
        library.move(folder("a"), folder("a2"), WRITER);
    }

    // ---------------------------------------------------------------- delete and the trash

    @Test
    void aDeletedFileGoesToTheTrashAndIsNeitherListedNorReadable() throws Exception {
        byte[] content = bytes(500, 14);
        String sha = library.write(file("trees/oak.schem"), content, WRITER, null);
        String kept = library.delete(file("trees/oak.schem"), WRITER);

        assertTrue(kept.matches("\\.trash/20260921-\\d{6}-\\d{3}-[0-9a-f]{8}/trees/oak\\.schem"), kept);
        assertArrayEquals(content, Files.readAllBytes(root.resolve(kept)), "recoverable on disk");
        assertFalse(Files.exists(root.resolve("trees").resolve("oak.schem")));
        assertEquals(List.of("trees", LibraryPath.SHARED), listed("", ADMIN), "the trash is never listed, even for admins");
        assertEquals(List.of(), listed("trees", ADMIN));
        assertTrue(library.find(sha, ADMIN).isEmpty(), "the hash no longer finds it");
        assertEquals(RejectReason.INVALID, refusal(() -> library.read(file("trees/oak.schem"), ADMIN)));
        for (String name : List.of(".trash", ".trash/x.schem", kept)) {
            assertThrows(LibraryPathException.class, () -> LibraryPath.file(name), name + " is not a library path");
            assertThrows(LibraryPathException.class, () -> LibraryPath.folder(name), name + " is not a library path");
        }
        assertEquals(new Library.Usage(0, 0, 1), library.usage(), "deleted bytes leave the quota");
        // A fresh count (a restart) leaves the trash out as well.
        assertEquals(new Library.Usage(0, 0, 1), new Library(root, library.settings()).usage());
        // The same name can be used again, and deleted again into its own place in the trash.
        library.write(file("trees/oak.schem"), bytes(20, 15), WRITER, null);
        String again = library.delete(file("trees/oak.schem"), WRITER);
        assertFalse(again.equals(kept));
        assertEquals(2, trashFiles().size());
    }

    @Test
    void onlyAnEmptyFolderCanBeDeleted() throws Exception {
        library.write(file("trees/oak.schem"), bytes(10, 16), WRITER, null);
        assertEquals("the folder is not empty: trees holds 1 entry",
                detail(() -> library.delete(folder("trees"), WRITER)));
        assertTrue(Files.exists(root.resolve("trees").resolve("oak.schem")));
        // Only files the library doesn't show: still refused, and they stay.
        Files.createDirectories(root.resolve("odd"));
        Files.write(root.resolve("odd").resolve("readme.txt"), bytes(3, 17));
        assertTrue(detail(() -> library.delete(folder("odd"), WRITER)).contains("doesn't show"));
        assertTrue(Files.exists(root.resolve("odd").resolve("readme.txt")));

        library.createFolder(folder("empty"), WRITER);
        assertEquals(new Library.Usage(10, 1, 2), library.usage(), "trees and empty (odd was made behind its back)");
        library.delete(folder("empty"), WRITER);
        assertFalse(Files.exists(root.resolve("empty")));
        assertEquals(new Library.Usage(10, 1, 1), library.usage());
        // Folders made behind the library's back (not counted) are deleted without a count going below zero.
        Files.createDirectories(root.resolve("stray"));
        Files.createDirectories(root.resolve("stray2"));
        library.delete(folder("stray"), WRITER);
        library.delete(folder("stray2"), WRITER);
        assertEquals(new Library.Usage(10, 1, 0), library.usage());
        assertEquals(List.of(), trashFiles(), "an empty folder is removed, not trashed");
    }

    @Test
    void oldTrashIsPurgedAtStartAndRecentTrashIsKept() throws Exception {
        library.write(file("a/old.schem"), bytes(10, 18), WRITER, null);
        library.write(file("a/new.schem"), bytes(10, 19), WRITER, null);
        String old = library.delete(file("a/old.schem"), WRITER);
        clock[0] += 29L * 86_400_000L;
        String recent = library.delete(file("a/new.schem"), WRITER);
        Files.createDirectories(root.resolve(Library.TRASH_FOLDER).resolve("not-a-stamp"));
        clock[0] += 2L * 86_400_000L; // the first deletion is now 31 days old, the second 2

        Library restarted = new Library(root, library.settings(), () -> clock[0]);
        restarted.start();
        assertFalse(Files.exists(root.resolve(old)), "a deletion older than 30 days is purged");
        assertTrue(Files.exists(root.resolve(recent)), "a recent one is kept");
        assertTrue(Files.isDirectory(root.resolve(Library.TRASH_FOLDER).resolve("not-a-stamp")),
                "what the library did not put there is left alone");
        // trashDays 0 keeps everything.
        clock[0] += 400L * 86_400_000L;
        Library keeping = new Library(root, library.settings().withTrashDays(0), () -> clock[0]);
        assertEquals(0, keeping.purgeTrash());
        assertTrue(Files.exists(root.resolve(recent)));
        assertThrows(IllegalArgumentException.class, () -> library.settings().withTrashDays(-1));
    }

    // ---------------------------------------------------------------- folders

    @Test
    void foldersAreCreatedOneLevelAtATime() throws Exception {
        library.createFolder(folder("trees"), WRITER);
        library.createFolder(folder("trees/big"), WRITER);
        assertEquals(List.of("trees/big"), listed("trees", WRITER));
        assertEquals("no such folder: rocks", detail(() -> library.createFolder(folder("rocks/small"), WRITER)));
        assertEquals("already exists: trees", detail(() -> library.createFolder(folder("trees"), WRITER)));
        // A player's own folder is made on the way, as for a save.
        library.createFolder(folder(ALICE_DIR + "/mine"), ALICE_ONLY);
        assertEquals(new Library.Usage(0, 0, 2), library.usage(ALICE));
        assertEquals(RejectReason.INVALID, refusal(() -> library.createFolder(file("x.schem"), WRITER)));
    }

    @Test
    void theFolderQuotasApplyToNewFolders() throws Exception {
        Library capped = new Library(root, new Library.Settings(4096, 64 << 10, 8192, 50, 1000, 3, 10, 2));
        capped.createFolder(folder("a"), WRITER);
        capped.createFolder(folder("b"), WRITER);
        capped.createFolder(folder("c"), WRITER);
        assertEquals(RejectReason.TOO_LARGE, refusal(() -> capped.createFolder(folder("d"), WRITER)));
        assertFalse(Files.exists(root.resolve("d")));
    }

    // ---------------------------------------------------------------- who may change what

    @Test
    void theOwnFolderIsAlwaysWritableSharedNeedsWriteAndOthersNeedAdmin() throws Exception {
        library.write(file("shared/tree.schem"), bytes(10, 20), WRITER, null);
        library.write(file(ALICE_DIR + "/mine.schem"), bytes(10, 21), ALICE_ONLY, null);
        library.write(file(BOB_DIR + "/bobs.schem"), bytes(10, 22), BOB_ONLY, null);

        // Alice without library.write: her own folder only.
        library.move(file(ALICE_DIR + "/mine.schem"), file(ALICE_DIR + "/renamed.schem"), ALICE_ONLY);
        library.createFolder(folder(ALICE_DIR + "/sub"), ALICE_ONLY);
        library.move(file(ALICE_DIR + "/renamed.schem"), file(ALICE_DIR + "/sub/renamed.schem"), ALICE_ONLY);
        for (ThrowingRun run : List.<ThrowingRun>of(
                () -> library.move(file("shared/tree.schem"), file("shared/t2.schem"), ALICE_ONLY),
                () -> library.delete(file("shared/tree.schem"), ALICE_ONLY),
                () -> library.createFolder(folder("new"), ALICE_ONLY),
                () -> library.move(file(ALICE_DIR + "/sub/renamed.schem"), file("shared/renamed.schem"), ALICE_ONLY),
                () -> library.move(file(BOB_DIR + "/bobs.schem"), file(BOB_DIR + "/x.schem"), ALICE_ONLY),
                () -> library.delete(file(BOB_DIR + "/bobs.schem"), WRITER),
                () -> library.createFolder(folder(BOB_DIR + "/x"), WRITER),
                () -> library.move(file("shared/tree.schem"), file(BOB_DIR + "/tree.schem"), WRITER))) {
            assertEquals(RejectReason.NO_PERMISSION, refusal(run));
        }
        assertTrue(Files.exists(root.resolve("shared").resolve("tree.schem")));
        assertTrue(Files.exists(root.resolve("_players").resolve(BOB.toString()).resolve("bobs.schem")));

        // library.write: the shared area; still not another player's folder.
        library.move(file("shared/tree.schem"), file("shared/t2.schem"), BOB_WRITER);
        assertTrue(detail(() -> library.delete(file(ALICE_DIR + "/sub/renamed.schem"), BOB_WRITER))
                .contains("sculptory.admin"));
        // Admins: everything but the reserved folders.
        library.move(file(ALICE_DIR + "/sub/renamed.schem"), file(ALICE_DIR + "/back.schem"), ADMIN);
        library.delete(file(BOB_DIR + "/bobs.schem"), ADMIN);
        library.move(file("shared/t2.schem"), file(ALICE_DIR + "/t2.schem"), ADMIN);

        assertTrue(Library.mayChangeIn(folder(ALICE_DIR), ALICE_ONLY));
        assertTrue(Library.mayChangeIn(folder(ALICE_DIR + "/sub"), ALICE_ONLY));
        assertFalse(Library.mayChangeIn(LibraryPath.ROOT, ALICE_ONLY));
        assertTrue(Library.mayChangeIn(LibraryPath.ROOT, WRITER));
        assertFalse(Library.mayChangeIn(folder(BOB_DIR), WRITER));
        assertTrue(Library.mayChangeIn(folder(BOB_DIR), ADMIN));
        assertFalse(Library.mayChangeIn(folder("_players"), ADMIN), "_players holds only player folders");
    }

    @Test
    void theRootAndThePlayerFoldersAreNeverChanged() throws Exception {
        library.write(file(ALICE_DIR + "/mine.schem"), bytes(10, 23), ALICE_ONLY, null);
        library.createFolder(folder("trees"), WRITER);
        for (ThrowingRun run : List.<ThrowingRun>of(
                () -> library.delete(folder("_players"), ADMIN),
                () -> library.delete(folder(ALICE_DIR), ALICE_ONLY),
                () -> library.move(folder(ALICE_DIR), folder("_players/" + BOB), ADMIN),
                () -> library.move(folder("trees"), folder("_players"), ADMIN),
                () -> library.createFolder(folder("_players/" + UUID.randomUUID()), ADMIN),
                () -> library.createFolder(LibraryPath.ROOT, ADMIN),
                () -> library.delete(LibraryPath.ROOT, ADMIN))) {
            assertEquals(RejectReason.INVALID, refusal(run));
        }
        assertTrue(Files.isRegularFile(root.resolve("_players").resolve(ALICE.toString()).resolve("mine.schem")));
        assertTrue(Files.isDirectory(root.resolve("trees")));
        assertTrue(folder("_players").reserved());
        assertTrue(folder(ALICE_DIR).reserved());
        assertFalse(folder(ALICE_DIR + "/sub").reserved());
        assertFalse(file(ALICE_DIR + "/x.schem").reserved());
        assertFalse(LibraryPath.ROOT.reserved());
    }

    @Test
    void linksAreNeverChanged() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.write(outside.resolve("secret.schem"), bytes(10, 24));
        Files.createDirectories(root);
        assumeTrue(LibraryTest.linkDirectory(root.resolve("link"), outside), "no directory links here");
        assertEquals(RejectReason.INVALID, refusal(() -> library.move(folder("link"), folder("link2"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.delete(folder("link"), WRITER)));
        assertEquals(RejectReason.INVALID, refusal(() -> library.delete(file("link/secret.schem"), WRITER)),
                "through a link out of the library");
        assertTrue(Files.exists(outside.resolve("secret.schem")));
        assertTrue(Files.exists(root.resolve("link")));
    }

    /** A trash folder an admin turned into a link is never used, nor purged through: it could lead out of the library. */
    @Test
    void aLinkedTrashIsRefused() throws Exception {
        Path outside = Files.createDirectories(temp.resolve("elsewhere"));
        Path oldBin = Files.createDirectories(outside.resolve("20000101-000000-000-0123abcd"));
        Files.write(oldBin.resolve("keep.schem"), bytes(5, 28));
        library.write(file("a/x.schem"), bytes(10, 27), WRITER, null);
        assumeTrue(LibraryTest.linkDirectory(root.resolve(Library.TRASH_FOLDER), outside), "no directory links here");
        assertTrue(detail(() -> library.delete(file("a/x.schem"), WRITER)).contains("not a plain folder"));
        assertTrue(Files.exists(root.resolve("a").resolve("x.schem")), "the file stays");
        new Library(root, library.settings(), () -> clock[0]).start();
        assertTrue(Files.exists(oldBin.resolve("keep.schem")), "the purge went through the link");
        try (Stream<Path> files = Files.list(outside)) {
            assertEquals(1, files.count(), "nothing went through the link");
        }
    }

    /** A link inside the library could put a change in another area than its path says: changes never go through one. */
    @Test
    void nothingIsChangedThroughALinkedFolder() throws Exception {
        library.write(file(BOB_DIR + "/bobs.schem"), bytes(10, 29), BOB_ONLY, null);
        assumeTrue(LibraryTest.linkDirectory(root.resolve("alias"), root.resolve("_players").resolve(BOB.toString())),
                "no directory links here");
        assertEquals(List.of("alias/bobs.schem"), listed("alias", WRITER), "the link is readable as before");
        for (ThrowingRun run : List.<ThrowingRun>of(
                () -> library.delete(file("alias/bobs.schem"), WRITER),
                () -> library.move(file("alias/bobs.schem"), file("alias/mine.schem"), WRITER),
                () -> library.createFolder(folder("alias/sub"), WRITER))) {
            assertTrue(detail(run).contains("reached through a link"));
        }
        library.write(file("shared/x.schem"), bytes(10, 30), WRITER, null);
        assertTrue(detail(() -> library.move(file("shared/x.schem"), file("alias/x.schem"), WRITER))
                .contains("reached through a link"), "not into another player's folder either");
        assertTrue(Files.exists(root.resolve("_players").resolve(BOB.toString()).resolve("bobs.schem")));
        assertFalse(Files.exists(root.resolve("_players").resolve(BOB.toString()).resolve("x.schem")));
    }

    @Test
    void theTrashIsCappedAndTheOldestDeletionsGoFirst() throws Exception {
        Library capped = new Library(root, library.settings().withTrash(30, 250, 250), () -> clock[0]);
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            capped.write(file("a/f" + i + ".schem"), bytes(100, 40 + i), WRITER, null);
            clock[0] += 1;
            kept.add(capped.delete(file("a/f" + i + ".schem"), WRITER));
        }
        assertFalse(Files.exists(root.resolve(kept.get(0))), "the oldest deletion made room");
        assertTrue(Files.exists(root.resolve(kept.get(1))) && Files.exists(root.resolve(kept.get(2))));
        assertEquals(200, capped.trashBytes());
        assertEquals(2, trashFiles().size());
        // A file the trash could never hold is refused, and stays.
        capped.write(file("a/big.schem"), bytes(300, 50), WRITER, null);
        assertEquals(RejectReason.TOO_LARGE, refusal(() -> capped.delete(file("a/big.schem"), WRITER)));
        assertTrue(Files.exists(root.resolve("a").resolve("big.schem")));
        assertEquals(200, new Library(root, capped.settings(), () -> clock[0]).trashBytes(), "counted again on disk");
    }

    /**
     * A player deleting file after file in their own folder only ever pushes out their own older deletions: the shared
     * area's recoverable trash (and another player's) stays, and so it does after a restart's recount and purge.
     */
    @Test
    void ownFolderDeletionsNeverEvictAnyoneElsesTrash() throws Exception {
        Library capped = new Library(root, library.settings().withTrash(30, 1000, 250), () -> clock[0]);
        capped.write(file("shared/tree.schem"), bytes(400, 60), WRITER, null);
        String shared = capped.delete(file("shared/tree.schem"), WRITER);
        capped.write(file(BOB_DIR + "/bobs.schem"), bytes(200, 61), BOB_ONLY, null);
        clock[0] += 1;
        String bobs = capped.delete(file(BOB_DIR + "/bobs.schem"), BOB_ONLY);
        List<String> alices = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            clock[0] += 1;
            capped.write(file(ALICE_DIR + "/f" + i + ".schem"), bytes(100, 62 + i), ALICE_ONLY, null);
            alices.add(capped.delete(file(ALICE_DIR + "/f" + i + ".schem"), ALICE_ONLY));
        }
        assertTrue(Files.exists(root.resolve(shared)), "a shared deletion was evicted by a player's own deletions");
        assertTrue(Files.exists(root.resolve(bobs)), "another player's deletion was evicted");
        assertEquals(400, capped.trashBytes(null));
        assertEquals(200, capped.trashBytes(BOB));
        assertEquals(200, capped.trashBytes(ALICE), "Alice's share: 250 bytes, so her last two deletions");
        Library restarted = new Library(root, capped.settings(), () -> clock[0]);
        restarted.start();
        assertTrue(Files.exists(root.resolve(shared)) && Files.exists(root.resolve(bobs)), "counted again by area");
        assertEquals(200, restarted.trashBytes(ALICE));
        assertEquals(800, restarted.trashBytes());
    }

    /** Within a player's share, the oldest of their deletions go first, and only as many as needed. */
    @Test
    void aPlayersShareDropsTheirOldestDeletionsFirst() throws Exception {
        Library capped = new Library(root, library.settings().withTrash(30, 1000, 300), () -> clock[0]);
        capped.write(file("shared/s.schem"), bytes(10, 69), WRITER, null);
        String shared = capped.delete(file("shared/s.schem"), WRITER); // older than all of Alice's
        List<String> kept = new ArrayList<>();
        int[] sizes = {100, 150, 40, 80, 200};
        for (int i = 0; i < sizes.length; i++) {
            clock[0] += 1;
            capped.write(file(ALICE_DIR + "/f" + i + ".schem"), bytes(sizes[i], 70 + i), ALICE_ONLY, null);
            kept.add(capped.delete(file(ALICE_DIR + "/f" + i + ".schem"), ALICE_ONLY));
            if (i == 3) {
                // 100 + 150 + 40 = 290 fit; with 80 it is 370: only the oldest (100) goes, leaving 270.
                assertEquals(List.of(false, true, true, true), existing(kept));
                assertEquals(270, capped.trashBytes(ALICE));
            }
        }
        // 270 + 200 = 470: 150 goes (320), then 40 (280), oldest first, until it fits.
        assertEquals(List.of(false, false, false, true, true), existing(kept));
        assertEquals(280, capped.trashBytes(ALICE));
        assertTrue(Files.exists(root.resolve(shared)), "the older shared deletion is not Alice's to push out");
    }

    private List<Boolean> existing(List<String> kept) {
        return kept.stream().map(path -> Files.exists(root.resolve(path))).toList();
    }

    @Test
    void oldDeletionsArePurgedAtTheNextDeletionToo() throws Exception {
        library.write(file("a/one.schem"), bytes(10, 51), WRITER, null);
        library.write(file("a/two.schem"), bytes(10, 52), WRITER, null);
        String first = library.delete(file("a/one.schem"), WRITER);
        clock[0] += 31L * 86_400_000L;
        String second = library.delete(file("a/two.schem"), WRITER);
        assertFalse(Files.exists(root.resolve(first)), "purged without waiting for a restart");
        assertTrue(Files.exists(root.resolve(second)));
    }

    @Test
    void aFileChangedOnDiskKeepsNoStaleHashThroughARename() throws Exception {
        byte[] before = bytes(100, 53);
        String oldSha = library.write(file("a/x.schem"), before, WRITER, null);
        Path onDisk = root.resolve("a").resolve("x.schem");
        Files.write(onDisk, bytes(100, 54)); // same size, other content
        Files.setLastModifiedTime(onDisk, FileTime.fromMillis(Files.getLastModifiedTime(onDisk).toMillis() + 5000));
        library.move(file("a/x.schem"), file("a/y.schem"), WRITER);
        assertTrue(library.find(oldSha, WRITER).isEmpty(), "the old hash would name other content");
        String newSha = library.list(folder("a"), WRITER).entries().get(0).sha256();
        assertEquals(Sha256.digest(bytes(100, 54)).hex(), newSha);
        assertEquals(file("a/y.schem"), library.find(newSha, WRITER).orElseThrow());
    }

    // ---------------------------------------------------------------- races

    /** Two players rename (or rename and delete) the same file at once: one succeeds, the other is refused cleanly. */
    @Test
    void racingChangesOfOneFileGiveOneSuccessAndOneCleanRefusal() throws Exception {
        for (int round = 0; round < 20; round++) {
            String dir = "race" + round;
            library.write(file(dir + "/x.schem"), bytes(40, round), WRITER, null);
            boolean deleteSecond = round % 2 == 1;
            List<ThrowingRun> runs = List.of(
                    () -> library.move(file(dir + "/x.schem"), file(dir + "/a.schem"), WRITER),
                    deleteSecond ? () -> library.delete(file(dir + "/x.schem"), BOB_WRITER)
                            : () -> library.move(file(dir + "/x.schem"), file(dir + "/b.schem"), BOB_WRITER));
            List<Object> results = race(runs);
            long successes = results.stream().filter(r -> r == null).count();
            assertEquals(1, successes, "round " + round + ": " + results);
            for (Object result : results) {
                if (result != null) {
                    LibraryException refused = (LibraryException) result;
                    assertEquals(RejectReason.INVALID, refused.reason());
                    assertTrue(refused.getMessage().startsWith("not found: "), refused.getMessage());
                }
            }
            int present = 0;
            for (String name : List.of("x.schem", "a.schem", "b.schem")) {
                if (Files.exists(root.resolve(dir).resolve(name))) present++;
            }
            int trashed = (int) trashFiles().stream().filter(p -> p.toString().contains(dir)).count();
            assertEquals(1, present + trashed, "round " + round + ": the file is in exactly one place");
            assertFalse(Files.exists(root.resolve(dir).resolve("x.schem")));
        }
    }

    /** Runs each task on its own thread, released together; null for a success, else the exception. */
    private static List<Object> race(List<ThrowingRun> runs) throws Exception {
        CyclicBarrier start = new CyclicBarrier(runs.size());
        CountDownLatch done = new CountDownLatch(runs.size());
        Object[] results = new Object[runs.size()];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            int index = i;
            Thread thread = new Thread(() -> {
                try {
                    start.await(5, TimeUnit.SECONDS);
                    runs.get(index).run();
                    results[index] = null;
                } catch (Exception e) {
                    results[index] = e;
                } finally {
                    done.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return java.util.Arrays.asList(results);
    }

    @Test
    void refusalsNeverNameServerPaths() throws Exception {
        library.write(file("a/x.schem"), bytes(10, 25), WRITER, null);
        library.write(file("a/y.schem"), bytes(10, 26), WRITER, null);
        for (ThrowingRun run : List.<ThrowingRun>of(
                () -> library.move(file("a/x.schem"), file("a/y.schem"), WRITER),
                () -> library.move(file("a/z.schem"), file("a/w.schem"), WRITER),
                () -> library.delete(folder("a"), WRITER),
                () -> library.createFolder(folder("a"), WRITER),
                () -> library.createFolder(folder("q/r"), WRITER))) {
            String message = assertThrows(LibraryException.class, run::run).getMessage();
            assertFalse(message.contains(temp.toString()), message);
        }
        String sha = Sha256.digest(bytes(10, 25)).hex();
        assertEquals(file("a/x.schem"), library.find(sha, WRITER).orElseThrow());
    }
}
