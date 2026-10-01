package dev.sculptory.fabric.library;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.Sha256;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.protocol.v2.RejectReason;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Palettes in the library: the path and kind rules, listing, read and write caps, the quotas, player folders and rights,
 * management and the trash, and that no schematic path (index, hash lookup) ever meets a palette.
 */
class LibraryPaletteTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final Library.Viewer WRITER = new Library.Viewer(ALICE, true, false);
    private static final Library.Viewer ALICE_ONLY = new Library.Viewer(ALICE, false, false);
    private static final Library.Viewer BOB_ONLY = new Library.Viewer(BOB, false, false);
    private static final Library.Viewer ADMIN = new Library.Viewer(BOB, false, true);
    private static final String ALICE_DIR = "_players/" + ALICE;
    private static final long NOW = 1_790_000_000_000L;
    private static final byte[] MOSS = PaletteFile.encode(new BlockPalette(List.of(
            new BlockPalette.Entry("minecraft:moss_block", 4), new BlockPalette.Entry("minecraft:stone", 1))), 3955);

    @TempDir
    Path temp;
    Path root;
    Library library;

    @BeforeEach
    void setUp() {
        root = temp.resolve("library");
        library = new Library(root, new Library.Settings(1 << 20, 4 << 20, 200 << 10, 50), () -> NOW);
    }

    private static LibraryPath palette(String path) {
        try {
            return LibraryPath.palette(path);
        } catch (LibraryPathException e) {
            throw new AssertionError(e);
        }
    }

    private static LibraryPath schem(String path) {
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

    private RejectReason refusal(ThrowingRun run) {
        return assertThrows(LibraryException.class, run::run).reason();
    }

    @FunctionalInterface
    interface ThrowingRun {
        void run() throws LibraryException;
    }

    // ---------------------------------------------------------------- paths

    @Test
    void theExtensionDecidesTheKindAndEachParserTakesOnlyItsOwn() throws Exception {
        LibraryPath moss = LibraryPath.palette("biomes/Moss_mix-2.palette.json");
        assertTrue(moss.isFile());
        assertTrue(moss.isPalette());
        assertEquals(LibraryPath.Kind.PALETTE, moss.kind());
        assertEquals("Moss_mix-2", moss.stem());
        assertEquals(LibraryPath.Kind.SCHEMATIC, LibraryPath.file("a.palette.json.schem").kind());
        assertEquals(LibraryPath.Kind.PALETTE, LibraryPath.palette("a.schem.palette.json").kind());
        assertEquals("a.schem", LibraryPath.palette("a.schem.palette.json").stem());
        assertEquals(moss, LibraryPath.anyFile("biomes/Moss_mix-2.palette.json"));
        assertEquals(LibraryPath.Kind.SCHEMATIC, LibraryPath.anyFile("trees/oak.schem").kind());
        assertNull(LibraryPath.folder("biomes").kind());

        // Schematic code paths parse with file(): a palette is never one. And the other way round.
        assertThrows(LibraryPathException.class, () -> LibraryPath.file("biomes/moss.palette.json"));
        assertThrows(LibraryPathException.class, () -> LibraryPath.palette("trees/oak.schem"));
        for (String bad : List.of(".palette.json", "palette.json", "moss.palette", "moss.PALETTE.JSON",
                "moss.palette.json.", "moss..palette.json", "a/../moss.palette.json", "_players/moss.palette.json",
                "con.palette.json", "moss palette.palette.json", "moss.json", "c:moss.palette.json",
                "/moss.palette.json", "a\\moss.palette.json", "a/b/c/d/e/f/g/h/i.palette.json",
                "x".repeat(52) + ".palette.json")) {
            assertThrows(LibraryPathException.class, () -> LibraryPath.anyFile(bad), bad);
        }
        LibraryPath.palette("x".repeat(51) + ".palette.json"); // a 64-character name
        assertEquals("a palette file must end in .palette.json",
                assertThrows(LibraryPathException.class, () -> LibraryPath.palette("a.schem")).getMessage());
        assertEquals("a library file must end in .schem, .litematic, .nbt",
                assertThrows(LibraryPathException.class, () -> LibraryPath.file("a.palette.json")).getMessage());

        // The kind goes along everywhere a path is rebuilt.
        LibraryPath own = moss.under(ALICE);
        assertEquals(ALICE_DIR + "/biomes/Moss_mix-2.palette.json", own.toString());
        assertTrue(own.isPalette());
        assertEquals(palette("biomes/x.palette.json"), folder("biomes").child("x.palette.json", true));
        assertThrows(IllegalArgumentException.class, () -> folder("biomes").child("x.txt", true));
    }

    // ---------------------------------------------------------------- files

    @Test
    void palettesAreWrittenListedAndReadButNeverIndexedOrHashed() throws Exception {
        String sha = library.write(palette("biomes/moss.palette.json"), MOSS, WRITER, null);
        library.write(schem("biomes/rock.schem"), new byte[] {1, 2, 3}, WRITER, null);
        assertArrayEquals(MOSS, Files.readAllBytes(root.resolve("biomes").resolve("moss.palette.json")));
        assertEquals(Sha256.digest(MOSS).hex(), sha);

        List<Library.Entry> entries = library.list(folder("biomes"), BOB_ONLY).entries();
        assertEquals(List.of(new Library.Entry("biomes/moss.palette.json", false, MOSS.length, "", LibraryPath.Kind.PALETTE),
                new Library.Entry("biomes/rock.schem", false, 3, Sha256.digest(new byte[] {1, 2, 3}).hex())), entries);
        assertArrayEquals(MOSS, library.read(palette("biomes/moss.palette.json"), BOB_ONLY).bytes());

        // No content hash leads to a palette, so no asset preview, paste or scatter variant can be one.
        assertTrue(library.find(sha, ADMIN).isEmpty());
        library.flush();
        String index = Files.readString(root.resolve(Library.INDEX_FILE));
        assertFalse(index.contains("palette"), index);
        assertTrue(index.contains("rock.schem"), index);

        // An index an admin edited by hand to name a palette is still never used for one.
        Files.writeString(root.resolve(Library.INDEX_FILE), "{\"version\":1,\"entries\":{\"biomes/moss.palette.json\":"
                + "{\"size\":" + MOSS.length + ",\"mtime\":0,\"sha256\":\"" + sha + "\"}}}");
        Library fresh = new Library(root, library.settings());
        assertTrue(fresh.find(sha, ADMIN).isEmpty());
        assertEquals("", fresh.list(folder("biomes"), ADMIN).entries().get(0).sha256());
    }

    @Test
    void aPaletteIsCappedAtItsOwnSizeBothWays() throws Exception {
        byte[] big = new byte[PaletteFile.MAX_BYTES + 1];
        assertEquals(RejectReason.TOO_LARGE, refusal(() -> library.write(palette("big.palette.json"), big, WRITER, null)));
        assertFalse(Files.exists(root.resolve("big.palette.json")));
        // A schematic of that size is fine (its own cap is 1 MiB here).
        library.write(schem("big.schem"), big, WRITER, null);
        // One put there by hand is refused when read, not loaded into memory whole.
        Files.write(root.resolve("hand.palette.json"), big);
        assertEquals(RejectReason.TOO_LARGE, refusal(() -> library.read(palette("hand.palette.json"), WRITER)));
        // A library whose file cap is smaller than a palette's uses the smaller one.
        Library tiny = new Library(temp.resolve("tiny"), new Library.Settings(1024, 1 << 20, 1 << 20, 50));
        assertEquals(RejectReason.TOO_LARGE,
                refusal(() -> tiny.write(palette("a.palette.json"), new byte[1025], WRITER, null)));
    }

    @Test
    void writesAreAtomicAndReplaceOnlyAPalette() throws Exception {
        LibraryPath path = palette("biomes/moss.palette.json");
        library.write(path, MOSS, WRITER, null);
        byte[] other = PaletteFile.encode(BlockPalette.of("minecraft:dirt", 7), 3955);
        library.write(path, other, WRITER, null);
        assertArrayEquals(other, Files.readAllBytes(root.resolve("biomes").resolve("moss.palette.json")));
        try (Stream<Path> files = Files.list(root.resolve("biomes"))) {
            assertEquals(List.of("moss.palette.json"), files.map(p -> p.getFileName().toString()).toList(),
                    "no temporary file is left");
        }
        // A refused write leaves the palette as it was.
        assertEquals(RejectReason.TOO_LARGE,
                refusal(() -> library.write(path, new byte[PaletteFile.MAX_BYTES + 1], WRITER, null)));
        assertArrayEquals(other, Files.readAllBytes(root.resolve("biomes").resolve("moss.palette.json")));
        // A folder in the palette's place is never replaced.
        Files.createDirectories(root.resolve("biomes").resolve("dir.palette.json"));
        assertEquals(RejectReason.INVALID,
                refusal(() -> library.write(palette("biomes/dir.palette.json"), MOSS, WRITER, null)));
    }

    @Test
    void palettesFollowTheLibraryRightsAndQuotas() throws Exception {
        // The shared area needs library.write; a player's own folder is theirs; another player's needs admin.
        assertEquals(RejectReason.NO_PERMISSION,
                refusal(() -> library.write(palette("shared.palette.json"), MOSS, ALICE_ONLY, null)));
        library.write(palette(ALICE_DIR + "/mine.palette.json"), MOSS, ALICE_ONLY, null);
        assertEquals(RejectReason.NO_PERMISSION,
                refusal(() -> library.write(palette(ALICE_DIR + "/bob.palette.json"), MOSS, BOB_ONLY, null)));
        assertEquals(RejectReason.NO_PERMISSION,
                refusal(() -> library.read(palette(ALICE_DIR + "/mine.palette.json"), BOB_ONLY)));
        library.read(palette(ALICE_DIR + "/mine.palette.json"), ADMIN);
        library.write(palette(ALICE_DIR + "/admin.palette.json"), MOSS, ADMIN, null);

        // A palette counts as a file like any other, in the library and in the player folder.
        assertEquals(new Library.Usage(2L * MOSS.length, 2, 1), library.usage(ALICE));
        assertEquals(new Library.Usage(2L * MOSS.length, 2, 2), library.usage());
        assertEquals(new Library.Usage(2L * MOSS.length, 2, 1), new Library(root, library.settings()).usage(ALICE),
                "and when counted afresh at start");
        Library full = new Library(temp.resolve("full"), new Library.Settings(1 << 20, 4 << 20, MOSS.length, 50));
        full.write(palette(ALICE_DIR + "/a.palette.json"), MOSS, ALICE_ONLY, null);
        assertEquals(RejectReason.TOO_LARGE,
                refusal(() -> full.write(palette(ALICE_DIR + "/b.palette.json"), MOSS, ALICE_ONLY, null)));
        Library fewFiles = new Library(temp.resolve("few"),
                new Library.Settings(1 << 20, 4 << 20, 1 << 20, 50, 20_000, 4_096, 1, 64));
        fewFiles.write(palette(ALICE_DIR + "/a.schem.palette.json"), MOSS, ALICE_ONLY, null);
        assertEquals(RejectReason.TOO_LARGE,
                refusal(() -> fewFiles.write(schem(ALICE_DIR + "/b.schem"), new byte[1], ALICE_ONLY, null)),
                "one file per player folder, palettes and schematics together");
    }

    // ---------------------------------------------------------------- management

    @Test
    void palettesAreRenamedMovedAndDeletedLikeAssetsButKeepTheirKind() throws Exception {
        library.write(palette("biomes/moss.palette.json"), MOSS, WRITER, null);
        library.createFolder(folder("old"), WRITER);
        library.move(palette("biomes/moss.palette.json"), palette("biomes/mossy.palette.json"), WRITER);
        library.move(palette("biomes/mossy.palette.json"), palette("old/mossy.palette.json"), WRITER);
        assertArrayEquals(MOSS, library.read(palette("old/mossy.palette.json"), BOB_ONLY).bytes());

        // A file keeps its kind: no rename turns a palette into a schematic or back.
        assertEquals(RejectReason.INVALID,
                refusal(() -> library.move(palette("old/mossy.palette.json"), schem("old/mossy.schem"), WRITER)));
        library.write(schem("old/rock.schem"), new byte[] {9}, WRITER, null);
        assertEquals(RejectReason.INVALID,
                refusal(() -> library.move(schem("old/rock.schem"), palette("old/rock.palette.json"), WRITER)));
        assertTrue(Files.exists(root.resolve("old").resolve("mossy.palette.json")));

        // Management rights as for assets.
        assertEquals(RejectReason.NO_PERMISSION,
                refusal(() -> library.move(palette("old/mossy.palette.json"), palette("old/x.palette.json"), BOB_ONLY)));
        assertEquals(RejectReason.NO_PERMISSION, refusal(() -> library.delete(palette("old/mossy.palette.json"), BOB_ONLY)));

        // Moving it into a player folder moves its bytes to that player's quota.
        library.move(palette("old/mossy.palette.json"), palette(ALICE_DIR + "/mossy.palette.json"), WRITER);
        assertEquals(new Library.Usage(MOSS.length, 1, 1), library.usage(ALICE));

        // Delete goes to the trash, as for assets, and out of the quota.
        String kept = library.delete(palette(ALICE_DIR + "/mossy.palette.json"), ALICE_ONLY);
        assertTrue(kept.matches("\\.trash/\\d{8}-\\d{6}-\\d{3}-[0-9a-f]{8}/_players/" + ALICE + "/mossy\\.palette\\.json"),
                kept);
        assertArrayEquals(MOSS, Files.readAllBytes(root.resolve(kept)));
        assertEquals(new Library.Usage(0, 0, 1), library.usage(ALICE));
        assertEquals(MOSS.length, library.trashBytes(ALICE));
        assertEquals(List.of("old/rock.schem"), library.list(folder("old"), ADMIN).entries().stream()
                .map(Library.Entry::path).toList());
    }

    @Test
    void aFolderHoldingOnlyAPaletteIsNotEmpty() throws Exception {
        library.write(palette("biomes/moss.palette.json"), MOSS, WRITER, null);
        String detail = assertThrows(LibraryException.class, () -> library.delete(folder("biomes"), WRITER)).getMessage();
        assertEquals("the folder is not empty: biomes holds 1 entry", detail);
        library.delete(palette("biomes/moss.palette.json"), WRITER);
        library.delete(folder("biomes"), WRITER);
        assertFalse(Files.exists(root.resolve("biomes")));
    }
}
