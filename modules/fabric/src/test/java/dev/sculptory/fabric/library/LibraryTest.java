package dev.sculptory.fabric.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.sculptory.core.Sha256;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.protocol.v2.RejectReason;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibraryTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final Library.Viewer WRITER = new Library.Viewer(ALICE, true, false);
    private static final Library.Viewer ALICE_ONLY = new Library.Viewer(ALICE, false, false);
    private static final Library.Viewer BOB_ONLY = new Library.Viewer(BOB, false, false);
    private static final Library.Viewer ADMIN = new Library.Viewer(BOB, false, true);

    @TempDir
    Path temp;
    Path root;
    Path outside;
    Library library;

    @BeforeEach
    void setUp() throws IOException {
        root = temp.resolve("library");
        outside = Files.createDirectories(temp.resolve("outside"));
        library = new Library(root, new Library.Settings(4096, 64 << 10, 8192, 5));
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

    /** A tiny gzip v3 schematic with tags. */
    private static byte[] schem(int width, String tag) throws IOException {
        NbtCompound schematic = NbtCompound.builder()
                .putInt("Version", 3)
                .putInt("DataVersion", 3955)
                .putShort("Width", (short) width)
                .putShort("Height", (short) 2)
                .putShort("Length", (short) 3)
                .put("Metadata", NbtCompound.builder()
                        .put("Sculptory", NbtCompound.builder().put("Tags", NbtList.ofStrings(List.of(tag))).build())
                        .build())
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeGzip(out, "", NbtCompound.builder().put("Schematic", schematic).build());
        return out.toByteArray();
    }

    @Test
    void writeListReadRoundTripWithIndex() throws Exception {
        byte[] bytes = schem(4, "tree");
        String sha = library.write(file("trees/oak.schem"), bytes, WRITER, null);
        assertEquals(Sha256.digest(bytes).hex(), sha);
        assertTrue(Files.isRegularFile(root.resolve("trees").resolve("oak.schem")));

        Library.Listing top = library.list(LibraryPath.ROOT, ALICE_ONLY);
        assertEquals(List.of(new Library.Entry("trees", true, 0, ""), new Library.Entry(LibraryPath.SHARED, true, 0, "")), top.entries(),
                "the root ends with the virtual Shared with me folder");
        Library.Listing trees = library.list(folder("trees"), ALICE_ONLY);
        assertEquals(List.of(new Library.Entry("trees/oak.schem", false, bytes.length, sha)), trees.entries());
        assertFalse(trees.truncated());

        Library.FileData data = library.read(file("trees/oak.schem"), BOB_ONLY);
        assertArrayEquals(bytes, data.bytes());
        assertEquals(sha, data.sha256());
        assertEquals(file("trees/oak.schem"), library.find(sha, BOB_ONLY).orElseThrow());

        Library.Info info = library.info(file("trees/oak.schem")).orElseThrow();
        assertArrayEquals(new int[] {4, 2, 3}, info.dims());
        assertEquals(24, info.cells());
        assertEquals(List.of("tree"), info.tags());
        assertTrue(Files.isRegularFile(root.resolve(Library.INDEX_FILE)));
    }

    /** A tiny Litematica file: a {@code width} × 2 × 3 region and library tags. */
    private static byte[] litematic(int width, String tag) throws IOException {
        NbtCompound region = NbtCompound.builder()
                .put("Position", NbtCompound.builder().putInt("x", 0).putInt("y", 0).putInt("z", 0).build())
                .put("Size", NbtCompound.builder().putInt("x", width).putInt("y", 2).putInt("z", 3).build())
                .build();
        NbtCompound root = NbtCompound.builder()
                .putInt("Version", 6)
                .put("Metadata", NbtCompound.builder()
                        .put("Sculptory", NbtCompound.builder().put("Tags", NbtList.ofStrings(List.of(tag))).build())
                        .build())
                .put("Regions", NbtCompound.builder().put("main", region).build())
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeGzip(out, "", root);
        return out.toByteArray();
    }

    /** A tiny structure file of {@code width} × 2 × 3. */
    private static byte[] structure(int width) throws IOException {
        NbtCompound root = NbtCompound.builder()
                .putInt("DataVersion", 3955)
                .put("size", NbtList.of(dev.sculptory.core.nbt.NbtTag.INT, List.of(
                        new dev.sculptory.core.nbt.NbtTag.NbtInt(width), new dev.sculptory.core.nbt.NbtTag.NbtInt(2),
                        new dev.sculptory.core.nbt.NbtTag.NbtInt(3))))
                .put("palette", NbtList.EMPTY)
                .put("blocks", NbtList.EMPTY)
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeGzip(out, "", root);
        return out.toByteArray();
    }

    @Test
    void litematicaAndStructureFilesAreListedIndexedAndKeepTheirExtension() throws Exception {
        byte[] schem = schem(4, "tree");
        byte[] litematic = litematic(5, "house");
        byte[] structure = structure(6);
        String schemSha = library.write(file("mix/a.schem"), schem, WRITER, null);
        String litematicSha = library.write(file("mix/b.litematic"), litematic, WRITER, null);
        String structureSha = library.write(file("mix/c.nbt"), structure, WRITER, null);
        Files.writeString(root.resolve("mix").resolve("d.schematic"), "not listed");
        assertEquals(List.of(new Library.Entry("mix/a.schem", false, schem.length, schemSha),
                        new Library.Entry("mix/b.litematic", false, litematic.length, litematicSha),
                        new Library.Entry("mix/c.nbt", false, structure.length, structureSha)),
                library.list(folder("mix"), ALICE_ONLY).entries());
        Library.Info info = library.info(file("mix/b.litematic")).orElseThrow();
        assertArrayEquals(new int[] {5, 2, 3}, info.dims(), "a Litematica file's box, from its regions");
        assertEquals(List.of("house"), info.tags());
        assertArrayEquals(new int[] {6, 2, 3}, library.info(file("mix/c.nbt")).orElseThrow().dims());
        assertEquals(file("mix/b.litematic"), library.find(litematicSha, BOB_ONLY).orElseThrow());

        // A rename keeps the extension: the name always says what the file is.
        library.move(file("mix/b.litematic"), file("mix/house.litematic"), WRITER);
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.move(file("mix/house.litematic"), file("mix/house.schem"), WRITER)).reason());
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.move(file("mix/c.nbt"), file("mix/c.litematic"), WRITER)).reason());
        assertTrue(Files.isRegularFile(root.resolve("mix").resolve("house.litematic")));
        assertTrue(Files.isRegularFile(root.resolve("mix").resolve("c.nbt")));
    }

    @Test
    void theIndexIsADerivedCacheRebuiltWhenMissingCorruptOrStale() throws Exception {
        byte[] first = schem(4, "a");
        library.write(file("x/a.schem"), first, WRITER, null);
        Files.writeString(root.resolve(Library.INDEX_FILE), "{ not json");
        Library fresh = new Library(root, library.settings());
        Library.Listing listing = fresh.list(folder("x"), ALICE_ONLY);
        assertEquals(Sha256.digest(first).hex(), listing.entries().get(0).sha256());

        // Replace the file behind the index's back: size and time change, so the entry is refreshed.
        byte[] second = schem(9, "b");
        Files.write(root.resolve("x").resolve("a.schem"), second);
        Files.setLastModifiedTime(root.resolve("x").resolve("a.schem"),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        listing = fresh.list(folder("x"), ALICE_ONLY);
        assertEquals(Sha256.digest(second).hex(), listing.entries().get(0).sha256());
        assertTrue(fresh.find(Sha256.digest(first).hex(), ALICE_ONLY).isEmpty(), "a stale hash no longer resolves");
    }

    @Test
    void hiddenInvalidAndTemporaryEntriesAreNotListed() throws Exception {
        Files.createDirectories(root.resolve("ok"));
        Files.write(root.resolve("ok").resolve("fine.schem"), schem(2, "t"));
        Files.write(root.resolve("ok").resolve(".tmp.schem"), new byte[] {1});
        Files.write(root.resolve("ok").resolve("has space.schem"), new byte[] {1});
        Files.write(root.resolve("ok").resolve("notes.txt"), new byte[] {1});
        Files.createDirectories(root.resolve(".git"));
        List<Library.Entry> entries = library.list(folder("ok"), ALICE_ONLY).entries();
        assertEquals(List.of("ok/fine.schem"), entries.stream().map(Library.Entry::path).toList());
        assertEquals(List.of("ok", LibraryPath.SHARED), library.list(LibraryPath.ROOT, ALICE_ONLY).entries().stream()
                .map(Library.Entry::path).toList());
    }

    @Test
    void listingsAreCapped() throws Exception {
        for (int i = 0; i < 7; i++) Files.createDirectories(root.resolve("many").resolve("f" + i));
        Library.Listing listing = library.list(folder("many"), ALICE_ONLY);
        assertEquals(5, listing.entries().size());
        assertTrue(listing.truncated());
    }

    @Test
    void sharedWritesNeedLibraryWriteAndPlayerFoldersArePrivate() throws Exception {
        LibraryException refused = assertThrows(LibraryException.class,
                () -> library.write(file("trees/x.schem"), schem(2, "t"), ALICE_ONLY, null));
        assertEquals(RejectReason.NO_PERMISSION, refused.reason());

        LibraryPath mine = file("_players/" + ALICE + "/house.schem");
        library.write(mine, schem(2, "t"), ALICE_ONLY, null);
        assertEquals(RejectReason.NO_PERMISSION, assertThrows(LibraryException.class,
                () -> library.write(file("_players/" + ALICE + "/y.schem"), schem(2, "t"), BOB_ONLY, null)).reason());
        assertEquals(RejectReason.NO_PERMISSION, assertThrows(LibraryException.class,
                () -> library.read(mine, BOB_ONLY)).reason());
        assertEquals(RejectReason.NO_PERMISSION, assertThrows(LibraryException.class,
                () -> library.list(folder("_players/" + ALICE), BOB_ONLY)).reason());
        library.read(mine, ADMIN);

        // _players shows only the viewer's own folder (all of them to admins), and only to players who have one.
        assertEquals(List.of("_players", LibraryPath.SHARED), paths(library.list(LibraryPath.ROOT, ALICE_ONLY)));
        assertEquals(List.of(LibraryPath.SHARED), paths(library.list(LibraryPath.ROOT, BOB_ONLY)));
        assertEquals(List.of("_players/" + ALICE), paths(library.list(folder("_players"), ALICE_ONLY)));
        assertEquals(List.of(), paths(library.list(folder("_players"), BOB_ONLY)));
        assertEquals(List.of("_players/" + ALICE), paths(library.list(folder("_players"), ADMIN)));
        String sha = library.list(folder("_players/" + ALICE), ALICE_ONLY).entries().get(0).sha256();
        assertTrue(library.find(sha, BOB_ONLY).isEmpty(), "another player's asset is not found by hash");
        assertTrue(library.find(sha, ALICE_ONLY).isPresent());
    }

    private static List<String> paths(Library.Listing listing) {
        return listing.entries().stream().map(Library.Entry::path).toList();
    }

    @Test
    void sizeCapsPerFilePerPlayerAndTotal() throws Exception {
        assertEquals(RejectReason.TOO_LARGE, assertThrows(LibraryException.class,
                () -> library.write(file("a/big.schem"), new byte[4097], WRITER, null)).reason());
        library.write(file("_players/" + ALICE + "/a.schem"), new byte[4000], ALICE_ONLY, null);
        library.write(file("_players/" + ALICE + "/b.schem"), new byte[4000], ALICE_ONLY, null);
        assertEquals(RejectReason.TOO_LARGE, assertThrows(LibraryException.class,
                () -> library.write(file("_players/" + ALICE + "/c.schem"), new byte[1000], ALICE_ONLY, null)).reason(),
                "player folder cap");
        // Replacing a file counts only the difference.
        library.write(file("_players/" + ALICE + "/b.schem"), new byte[4090], ALICE_ONLY, null);

        Library small = new Library(temp.resolve("small"), new Library.Settings(4096, 6000, 8192, 5));
        small.write(file("a/one.schem"), new byte[4000], WRITER, null);
        assertEquals(RejectReason.TOO_LARGE, assertThrows(LibraryException.class,
                () -> small.write(file("a/two.schem"), new byte[4000], WRITER, null)).reason(), "total cap");
        try (Stream<Path> files = Files.list(temp.resolve("small").resolve("a"))) {
            assertEquals(List.of("one.schem"), files.map(p -> p.getFileName().toString()).toList(), "no leftovers");
        }
    }

    @Test
    void refusedSavesLeaveNoFoldersBehind() throws Exception {
        Library capped = new Library(root, new Library.Settings(4096, 64 << 10, 1000, 5, 1000, 1000, 3, 3));
        String mine = "_players/" + ALICE;
        capped.write(file(mine + "/a.schem"), new byte[100], ALICE_ONLY, null); // folders: _players/<alice>
        capped.write(file(mine + "/x/b.schem"), new byte[100], ALICE_ONLY, null); // + x
        // Two more levels would make 4 player folders > 3: refused before anything is created.
        assertEquals(RejectReason.TOO_LARGE, assertThrows(LibraryException.class,
                () -> capped.write(file(mine + "/y/z/c.schem"), new byte[100], ALICE_ONLY, null)).reason());
        assertFalse(Files.exists(root.resolve("_players").resolve(ALICE.toString()).resolve("y")), "a folder was left");
        // Over the byte quota in new folders: nothing created either.
        assertEquals(RejectReason.TOO_LARGE, assertThrows(LibraryException.class,
                () -> capped.write(file(mine + "/w/big.schem"), new byte[900], ALICE_ONLY, null)).reason());
        assertFalse(Files.exists(root.resolve("_players").resolve(ALICE.toString()).resolve("w")), "a folder was left");
        // The file cap: 2 files so far, a third fits, a fourth does not.
        capped.write(file(mine + "/c.schem"), new byte[10], ALICE_ONLY, null);
        assertEquals(RejectReason.TOO_LARGE, assertThrows(LibraryException.class,
                () -> capped.write(file(mine + "/d.schem"), new byte[10], ALICE_ONLY, null)).reason());
        // Replacing an existing file is not a new file.
        capped.write(file(mine + "/c.schem"), new byte[20], ALICE_ONLY, null);
        assertEquals(new Library.Usage(220, 3, 2), capped.usage(ALICE));
    }

    @Test
    void usageIsCountedOnceWithTemporaryFilesRemovedAndThenKeptUpToDate() throws Exception {
        Files.createDirectories(root.resolve("a").resolve("b"));
        Files.write(root.resolve("a").resolve("one.schem"), new byte[300]);
        Files.write(root.resolve("a").resolve("notes.txt"), new byte[999]);
        String stale = "." + UUID.randomUUID() + ".tmp";
        Files.write(root.resolve("a").resolve(stale), new byte[999]);
        Files.write(root.resolve("a").resolve(".notours.tmp"), new byte[5]);
        Files.createDirectories(root.resolve("_players").resolve(BOB.toString()));
        Files.write(root.resolve("_players").resolve(BOB.toString()).resolve("bob.schem"), new byte[50]);
        library.start();
        assertFalse(Files.exists(root.resolve("a").resolve(stale)), "a stale temporary file survived");
        assertTrue(Files.exists(root.resolve("a").resolve(".notours.tmp")), "a file not written by the library was deleted");
        assertEquals(new Library.Usage(350, 2, 4), library.usage(), "a, a/b, _players, _players/<bob>");
        assertEquals(new Library.Usage(50, 1, 1), library.usage(BOB));
        library.write(file("a/b/c/two.schem"), new byte[70], WRITER, null);
        assertEquals(new Library.Usage(420, 3, 5), library.usage());
    }

    @Test
    void theStartupWalkNeverFollowsLinksOutOfTheLibrary() throws Exception {
        Files.createDirectories(root);
        String stale = "." + UUID.randomUUID() + ".tmp";
        Files.write(outside.resolve(stale), new byte[10]);
        Files.write(outside.resolve("big.schem"), new byte[4000]);
        assumeTrue(linkDirectory(root.resolve("escape"), outside), "no directory links here");
        Files.createDirectories(root.resolve("real"));
        Files.write(root.resolve("real").resolve("in.schem"), new byte[30]);
        library.start();
        assertTrue(Files.exists(outside.resolve(stale)), "the walk deleted a file outside the library");
        Library.Usage usage = library.usage();
        assertEquals(30, usage.bytes(), "files behind the link were counted");
        assertEquals(1, usage.files());
        assertEquals(1, usage.folders(), "the link itself counted as a folder");
    }

    @Test
    void theIndexIsWrittenAtMostEveryTwoSecondsAndOnFlush() throws Exception {
        long[] now = {1_000_000};
        Library timed = new Library(root, library.settings(), () -> now[0]);
        Path index = root.resolve(Library.INDEX_FILE);
        timed.write(file("a/one.schem"), schem(2, "t"), WRITER, null);
        assertTrue(Files.isRegularFile(index), "the first change is written at once");
        String first = Files.readString(index);
        now[0] += 500;
        timed.write(file("a/two.schem"), schem(3, "t"), WRITER, null);
        assertEquals(first, Files.readString(index), "written again within two seconds");
        now[0] += Library.INDEX_SAVE_INTERVAL_MILLIS;
        timed.write(file("a/three.schem"), schem(4, "t"), WRITER, null);
        String third = Files.readString(index);
        assertTrue(third.contains("a/two.schem") && third.contains("a/three.schem"));
        assertFalse(third.contains("\n  "), "the index is compact");
        now[0] += 10;
        timed.write(file("a/four.schem"), schem(5, "t"), WRITER, null);
        assertFalse(Files.readString(index).contains("a/four.schem"));
        timed.flush();
        assertTrue(Files.readString(index).contains("a/four.schem"), "flush writes pending changes");
    }

    @Test
    void refusalsNeverNameServerPaths() throws Exception {
        Files.createDirectories(root.resolve("dir.schem"));
        Files.write(root.resolve("plain"), new byte[1]);
        String rootText = root.toString();
        for (LibraryException e : List.of(
                assertThrows(LibraryException.class, () -> library.read(file("nope/none.schem"), ALICE_ONLY)),
                assertThrows(LibraryException.class, () -> library.read(file("dir.schem"), ALICE_ONLY)),
                assertThrows(LibraryException.class, () -> library.write(file("dir.schem"), new byte[1], WRITER, null)),
                assertThrows(LibraryException.class, () -> library.write(file("plain/x.schem"), new byte[1], WRITER, null)),
                assertThrows(LibraryException.class, () -> library.list(folder("nope"), ALICE_ONLY)))) {
            assertFalse(e.getMessage().contains(rootText) || e.getMessage().contains(temp.toString()), e.getMessage());
        }
    }

    @Test
    void missingEntriesAndWrongKindsAreInvalid() throws Exception {
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.read(file("nope/none.schem"), ALICE_ONLY)).reason());
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.list(folder("nope"), ALICE_ONLY)).reason());
        Files.createDirectories(root.resolve("dir.schem"));
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.read(file("dir.schem"), ALICE_ONLY)).reason());
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.write(file("dir.schem"), new byte[1], WRITER, null)).reason());
        Files.write(root.resolve("plain"), new byte[1]);
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.write(file("plain/x.schem"), new byte[1], WRITER, null)).reason());
    }

    /**
     * A directory link: a symbolic link, or on Windows without the symlink privilege a junction ({@code mklink /J},
     * which needs none). {@code toRealPath} resolves both. False when neither can be made.
     */
    static boolean linkDirectory(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // fall through to a junction
        }
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return false;
        try {
            Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0 && Files.isDirectory(link);
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Directory links (symbolic links or junctions) that lead outside the library are refused; inner ones are fine. */
    @Test
    void directoryLinksMustNotLeaveTheLibrary() throws Exception {
        Files.createDirectories(root);
        Files.write(outside.resolve("secret.schem"), schem(2, "secret"));
        Files.createDirectories(root.resolve("real"));
        Files.write(root.resolve("real").resolve("in.schem"), schem(3, "in"));
        assumeTrue(linkDirectory(root.resolve("escape"), outside), "no directory links here");
        assumeTrue(linkDirectory(root.resolve("alias"), root.resolve("real")), "no directory links here");
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.list(folder("escape"), ALICE_ONLY)).reason());
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.read(file("escape/secret.schem"), ALICE_ONLY)).reason());
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.write(file("escape/new.schem"), new byte[1], WRITER, null)).reason());
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.write(file("escape/deeper/new.schem"), new byte[1], WRITER, null)).reason());
        assertFalse(Files.exists(outside.resolve("new.schem")), "nothing was written outside");
        assertFalse(Files.exists(outside.resolve("deeper")), "no folder was created outside");
        assertArrayEquals(schem(2, "secret"), Files.readAllBytes(outside.resolve("secret.schem")));
        String sha = Sha256.digest(schem(2, "secret")).hex();
        assertTrue(library.find(sha, ALICE_ONLY).isEmpty(), "found by hash through the link");
        // The escaping link is not listed; the one that stays inside is.
        assertEquals(List.of("alias", "real", LibraryPath.SHARED), paths(library.list(LibraryPath.ROOT, ALICE_ONLY)));
        assertEquals(List.of("alias/in.schem"), paths(library.list(folder("alias"), ALICE_ONLY)));
        library.read(file("alias/in.schem"), ALICE_ONLY);
    }

    /** A file symbolic link to a file outside the library is refused for reading and never written through. */
    @Test
    void fileSymlinksMustNotLeaveTheLibrary() throws Exception {
        Files.createDirectories(root);
        Files.write(outside.resolve("secret.schem"), schem(2, "secret"));
        try {
            Files.createSymbolicLink(root.resolve("leak.schem"), outside.resolve("secret.schem"));
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "file symbolic links are not available here: " + e);
        }
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.read(file("leak.schem"), ALICE_ONLY)).reason());
        assertEquals(RejectReason.INVALID, assertThrows(LibraryException.class,
                () -> library.write(file("leak.schem"), new byte[1], WRITER, null)).reason());
        assertArrayEquals(schem(2, "secret"), Files.readAllBytes(outside.resolve("secret.schem")));
        assertEquals(List.of(LibraryPath.SHARED), paths(library.list(LibraryPath.ROOT, ALICE_ONLY)));
    }
}
