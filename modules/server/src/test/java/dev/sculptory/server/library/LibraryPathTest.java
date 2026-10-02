package dev.sculptory.server.library;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LibraryPathTest {
    private static final UUID PLAYER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");

    private static void refusedFile(String path) {
        assertThrows(LibraryPathException.class, () -> LibraryPath.file(path), path);
    }

    private static void refusedFolder(String path) {
        assertThrows(LibraryPathException.class, () -> LibraryPath.folder(path), path);
    }

    @Test
    void acceptsCategoryFilesAndFolders() throws LibraryPathException {
        LibraryPath file = LibraryPath.file("trees/Oak_big-2.v1.schem");
        assertEquals(List.of("trees", "Oak_big-2.v1.schem"), file.segments());
        assertTrue(file.isFile());
        assertEquals("Oak_big-2.v1", file.stem());
        assertEquals("trees/Oak_big-2.v1.schem", file.toString());
        assertEquals(LibraryPath.folder("trees"), file.parent());
        assertTrue(LibraryPath.folder("").isRoot());
        assertEquals("a/b/c", LibraryPath.folder("a/b/c").toString());
        assertEquals(Path.of("root", "a", "b.schem"), LibraryPath.file("a/b.schem").resolve(Path.of("root")));
    }

    @Test
    void refusesTraversal() {
        refusedFile("../evil.schem");
        refusedFile("a/../../evil.schem");
        refusedFile("a/./b.schem");
        refusedFile("..");
        refusedFolder("..");
        refusedFolder("a/..");
        refusedFolder(".");
        refusedFile("a..b.schem");
        refusedFile("a/b..schem");
    }

    @Test
    void refusesAbsolutePathsAndDriveLetters() {
        refusedFile("/etc/passwd.schem");
        refusedFolder("/");
        refusedFile("C:/Windows/x.schem");
        refusedFile("C:x.schem");
        refusedFile("a/b.schem:stream");
        refusedFile("//server/share/x.schem");
    }

    @Test
    void refusesOtherSeparatorsEmptyAndTrailingSegments() {
        refusedFile("a\\b.schem");
        refusedFile("..\\evil.schem");
        refusedFile("a//b.schem");
        refusedFolder("a/");
        refusedFolder("a//b");
        refusedFile("");
    }

    @Test
    void refusesReservedWindowsNamesInAnyCase() {
        for (String name : List.of("CON", "con", "PRN", "Aux", "NUL", "COM1", "com9", "LPT1", "lpt0")) {
            refusedFile(name + ".schem");
            refusedFile("ok/" + name + ".schem");
            refusedFolder(name);
            refusedFile(name + ".backup.schem");
        }
        assertTrue(LibraryPath.validName("console"));
        assertTrue(LibraryPath.validName("com10"));
    }

    @Test
    void enforcesTheCharsetAndHiddenNames() {
        for (String bad : List.of("a b.schem", "a\tb.schem", "a\u0000b.schem", "caf\u00e9.schem", ".hidden.schem",
                "-dash.schem", "_under.schem", "a*b.schem", "a?b.schem", "a<b.schem", "a|b.schem", "a\"b.schem",
                "~backup.schem", ".index.json")) {
            refusedFile(bad);
        }
        refusedFolder("a b");
        refusedFolder(".git");
        refusedFolder("trailing.");
        assertTrue(LibraryPath.validName("A-z_0.9"));
    }

    @Test
    void enforcesLengthAndDepth() throws LibraryPathException {
        String name64 = "a".repeat(64);
        LibraryPath.folder(name64);
        refusedFolder("a".repeat(65));
        refusedFile("a".repeat(60) + ".schem");
        LibraryPath.file("a".repeat(58) + ".schem");
        LibraryPath.file("a/b/c/d/e/f/g/h.schem");
        refusedFile("a/b/c/d/e/f/g/h/i.schem");
        String deep = String.join("/", List.of(name64, name64, name64, name64, "x.schem"));
        assertTrue(deep.length() > LibraryPath.MAX_LENGTH);
        refusedFile(deep);
    }

    @Test
    void enforcesASchematicExtension() throws LibraryPathException {
        refusedFile("house");
        refusedFile("house.SCHEM");
        refusedFile("house.NBT");
        refusedFile("house.Litematic");
        refusedFile("house.schematic");
        refusedFile("trees/.schem");
        refusedFile("trees/.nbt");
        refusedFile("trees/.litematic");
        LibraryPath.folder("house.schem"); // a folder may be named anything valid
    }

    @Test
    void schematicsMayBeLitematicaAndStructureFiles() throws LibraryPathException {
        LibraryPath schem = LibraryPath.file("trees/oak.schem");
        LibraryPath litematic = LibraryPath.file("trees/oak.litematic");
        LibraryPath structure = LibraryPath.file("trees/oak.nbt");
        for (LibraryPath path : List.of(schem, litematic, structure)) {
            assertEquals(LibraryPath.Kind.SCHEMATIC, path.kind());
            assertEquals("oak", path.stem());
        }
        assertEquals(dev.sculptory.core.schem.SchematicFormat.SPONGE, schem.format());
        assertEquals(dev.sculptory.core.schem.SchematicFormat.LITEMATIC, litematic.format());
        assertEquals(dev.sculptory.core.schem.SchematicFormat.STRUCTURE, structure.format());
        assertEquals(".litematic", litematic.extension());
        assertEquals(".nbt", LibraryPath.anyFile("a.nbt").extension());
        assertEquals("a.b", LibraryPath.file("a.b.nbt").stem());
        assertEquals("a library file must end in .schem, .litematic, .nbt",
                assertThrows(LibraryPathException.class, () -> LibraryPath.file("a.txt")).getMessage());
    }

    @Test
    void playerFoldersNeedACanonicalUuid() throws LibraryPathException {
        LibraryPath own = LibraryPath.file("_players/" + PLAYER + "/house.schem");
        assertEquals(PLAYER, own.owner().orElseThrow());
        assertTrue(own.inPlayersArea());
        assertTrue(LibraryPath.folder("_players").inPlayersArea());
        assertTrue(LibraryPath.folder("_players").owner().isEmpty());
        refusedFile("_players/house.schem");
        refusedFile("_players/" + PLAYER.toString().toUpperCase() + "/house.schem");
        refusedFile("_players/not-a-uuid/house.schem");
        refusedFolder("_other");
        refusedFile("trees/_players/x.schem");
        refusedFile("_players");
    }

    @Test
    void movingUnderAPlayerFolder() throws LibraryPathException {
        LibraryPath moved = LibraryPath.file("trees/oak.schem").under(PLAYER);
        assertEquals("_players/" + PLAYER + "/trees/oak.schem", moved.toString());
        assertEquals(PLAYER, moved.owner().orElseThrow());
        LibraryPath already = LibraryPath.file("_players/" + PLAYER + "/x.schem");
        assertEquals(already, already.under(UUID.randomUUID()));
        assertNull(LibraryPath.file("a/b/c/d/e/f/g.schem").under(PLAYER), "too deep once moved");
        assertFalse(LibraryPath.validPlayerFolder("x"));
    }
}
