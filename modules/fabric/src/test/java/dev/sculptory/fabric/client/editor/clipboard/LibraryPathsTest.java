package dev.sculptory.fabric.client.editor.clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.fabric.library.LibraryPath;
import dev.sculptory.fabric.library.LibraryPathException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The client's path check against the server's rules: the server's own test vectors ({@code LibraryPathTest}) give
 * the same answer here, for files and folders.
 */
class LibraryPathsTest {
    private static final UUID PLAYER = UUID.fromString("0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0");

    /** The file paths the server's tests refuse. */
    private static List<String> refusedFiles() {
        List<String> paths = new ArrayList<>(List.of(
                "../evil.schem", "a/../../evil.schem", "a/./b.schem", "..", "a..b.schem", "a/b..schem",
                "/etc/passwd.schem", "C:/Windows/x.schem", "C:x.schem", "a/b.schem:stream", "//server/share/x.schem",
                "a\\b.schem", "..\\evil.schem", "a//b.schem", "",
                "a b.schem", "a\tb.schem", "a\u0000b.schem", "caf\u00e9.schem", ".hidden.schem", "-dash.schem",
                "_under.schem", "a*b.schem", "a?b.schem", "a<b.schem", "a|b.schem", "a\"b.schem", "~backup.schem",
                ".index.json",
                "a".repeat(60) + ".schem", "a/b/c/d/e/f/g/h/i.schem",
                "house", "house.txt", "house.SCHEM", "house.NBT", "house.Litematic", "house.schematic", "trees/.schem",
                "trees/.nbt",
                "_players/house.schem", "_players/" + PLAYER.toString().toUpperCase() + "/house.schem",
                "_players/not-a-uuid/house.schem", "trees/_players/x.schem", "_players"));
        for (String name : List.of("CON", "con", "PRN", "Aux", "NUL", "COM1", "com9", "LPT1", "lpt0")) {
            paths.add(name + ".schem");
            paths.add("ok/" + name + ".schem");
            paths.add(name + ".backup.schem");
        }
        String name64 = "a".repeat(64);
        paths.add(String.join("/", List.of(name64, name64, name64, name64, "x.schem")));
        return paths;
    }

    private static List<String> acceptedFiles() {
        return List.of("trees/Oak_big-2.v1.schem", "a".repeat(58) + ".schem", "a/b/c/d/e/f/g/h.schem",
                "_players/" + PLAYER + "/house.schem", "console.schem", "com10.schem");
    }

    private static boolean serverAccepts(String path) {
        try {
            LibraryPath.file(path);
            return true;
        } catch (LibraryPathException e) {
            return false;
        }
    }

    @Test
    void refusesWhatTheServerRefuses() {
        for (String path : refusedFiles()) {
            assertFalse(serverAccepts(path), "the server vector itself: " + path);
            assertTrue(LibraryPaths.fileProblem(path).isPresent(), path);
        }
    }

    @Test
    void acceptsWhatTheServerAccepts() {
        for (String path : acceptedFiles()) {
            assertTrue(serverAccepts(path), path);
            assertEquals(java.util.Optional.empty(), LibraryPaths.fileProblem(path), path);
        }
    }

    @Test
    void foldersFollowTheFolderRules() {
        for (String folder : List.of("..", "a/..", ".", "/", "a/", "a//b", "a b", ".git", "trailing.", "_other",
                "a".repeat(65), "CON")) {
            assertTrue(LibraryPaths.folderProblem(folder).isPresent(), folder);
        }
        for (String folder : List.of("", "a/b/c", "house.schem", "a".repeat(64), "_players")) {
            assertTrue(LibraryPaths.folderProblem(folder).isEmpty(), folder);
        }
    }

    @Test
    void theProblemExplainsItself() {
        assertEquals("'..' segments are not allowed", LibraryPaths.fileProblem("../x.schem").orElseThrow());
        assertTrue(LibraryPaths.fileProblem("x.txt").orElseThrow().contains(".schem, .litematic, .nbt"));
    }

    @Test
    void normalizingAddsTheExtensionOnlyWhenThereIsNone() {
        assertEquals("trees/oak.schem", LibraryPaths.normalizeFile("  trees/oak "));
        assertEquals("oak.schem", LibraryPaths.normalizeFile("oak.schem"));
        assertEquals("oak.txt", LibraryPaths.normalizeFile("oak.txt"), "another extension is kept, and refused");
        assertTrue(LibraryPaths.fileProblem(LibraryPaths.normalizeFile("oak.txt")).isPresent());
        assertEquals("oak.nbt", LibraryPaths.normalizeFile("oak.nbt"));
        assertEquals("oak.litematic", LibraryPaths.normalizeFile("oak", ".litematic"));
        assertEquals("", LibraryPaths.normalizeFile("   "));
        assertEquals("", LibraryPaths.normalizeFile(null));
    }

    @Test
    void theFormatChoiceSetsTheExtension() {
        assertEquals("trees/oak.litematic", LibraryPaths.withFormat("trees/oak.schem", SchematicFormat.LITEMATIC));
        assertEquals("trees/oak.nbt", LibraryPaths.withFormat(" trees/oak ", SchematicFormat.STRUCTURE));
        assertEquals("oak.schem", LibraryPaths.withFormat("oak.nbt", SchematicFormat.SPONGE));
        assertEquals("oak.v2.schem", LibraryPaths.withFormat("oak.v2.litematic", SchematicFormat.SPONGE));
        assertEquals("oak.txt", LibraryPaths.withFormat("oak.txt", SchematicFormat.SPONGE), "left for the check");
        assertEquals("", LibraryPaths.withFormat("  ", SchematicFormat.SPONGE));
        assertEquals(SchematicFormat.LITEMATIC, LibraryPaths.formatOf("a/b.litematic"));
        assertEquals(SchematicFormat.STRUCTURE, LibraryPaths.formatOf("b.nbt"));
        assertEquals(null, LibraryPaths.formatOf("b"), "no extension typed");
        assertEquals(null, LibraryPaths.formatOf("b.NBT"), "library names are lower case");
        assertEquals("b", LibraryPaths.stem("a/b.litematic"));
        assertEquals("b", LibraryPaths.stem("a/b.nbt"));
    }

    @Test
    void aRenamedFileKeepsItsExtension() {
        assertEquals(new LibraryPaths.Target("trees/elm.nbt", LibraryPaths.Problem.NONE, ""),
                LibraryPaths.newName("trees", "elm", true, "trees/oak.nbt"), "the current extension is added");
        assertEquals(new LibraryPaths.Target("trees/elm.litematic", LibraryPaths.Problem.NONE, ""),
                LibraryPaths.newName("trees", "elm.litematic", true, "trees/oak.litematic"));
        LibraryPaths.Target changed = LibraryPaths.newName("trees", "elm.schem", true, "trees/oak.nbt");
        assertEquals(LibraryPaths.Problem.INVALID, changed.problem());
        assertEquals("a file keeps its extension (.nbt)", changed.detail());
    }

    @Test
    void newNamesStayInTheirFolderAndFollowTheServersRules() {
        assertEquals(new LibraryPaths.Target("trees/elm.schem", LibraryPaths.Problem.NONE, ""),
                LibraryPaths.newName("trees", " elm ", true, "trees/oak.schem"), ".schem is added");
        assertEquals("trees/elm.schem", LibraryPaths.newName("trees", "elm.schem", true, null).path());
        assertEquals(LibraryPaths.Problem.SAME, LibraryPaths.newName("trees", "oak", true, "trees/oak.schem").problem());
        assertEquals(LibraryPaths.Problem.EMPTY, LibraryPaths.newName("trees", "  ", true, null).problem());
        assertEquals(LibraryPaths.Problem.SLASH, LibraryPaths.newName("trees", "rocks/elm", true, null).problem());
        assertEquals(LibraryPaths.Problem.SLASH, LibraryPaths.newName("trees", "..\\elm", true, null).problem());
        for (String bad : List.of("..", ".hidden", "a b", "CON", "x:y", "_x", "trailing.", "x".repeat(65))) {
            LibraryPaths.Target target = LibraryPaths.newName("trees", bad, false, null);
            assertEquals(LibraryPaths.Problem.INVALID, target.problem(), bad);
            assertFalse(target.detail().isEmpty(), bad);
        }
        assertEquals(LibraryPaths.Problem.INVALID, LibraryPaths.newName("trees", "elm.txt", true, null).problem());
        assertTrue(LibraryPaths.newName("trees", "elm.nbt", true, null).ok(), "a new structure file");
        assertEquals(LibraryPaths.Problem.RESERVED, LibraryPaths.newName("", "_players", false, null).problem());
        String player = UUID.randomUUID().toString();
        assertEquals(LibraryPaths.Problem.RESERVED, LibraryPaths.newName("_players", player, false, null).problem());
        assertTrue(LibraryPaths.newName("_players/" + player, "sub", false, null).ok(), "inside a player folder");
        // Too deep or too long for the path caps.
        String deep = String.join("/", java.util.Collections.nCopies(7, "d"));
        assertTrue(LibraryPaths.newName(deep, "x", false, null).ok());
        assertEquals(LibraryPaths.Problem.INVALID, LibraryPaths.newName(deep + "/d", "x", false, null).problem());
    }

    @Test
    void movesNameAnotherFolderThatTheServerWouldAccept() {
        assertEquals(new LibraryPaths.Target("rocks/oak.schem", LibraryPaths.Problem.NONE, ""),
                LibraryPaths.moveTo("trees/oak.schem", " rocks/ "));
        assertEquals("oak.schem", LibraryPaths.moveTo("trees/oak.schem", "").path(), "empty is the library root");
        assertEquals("oak.schem", LibraryPaths.moveTo("trees/oak.schem", "/").path());
        assertEquals(LibraryPaths.Problem.SAME, LibraryPaths.moveTo("trees/oak.schem", "trees").problem());
        for (String bad : List.of("..", "../x", "a//b", "C:", "a\\b", ".trash", "_players")) {
            assertEquals(LibraryPaths.Problem.INVALID, LibraryPaths.moveTo("trees/oak.schem", bad).problem(), bad);
        }
        String player = UUID.randomUUID().toString();
        assertTrue(LibraryPaths.moveTo("trees/oak.schem", "_players/" + player).ok(), "into a player folder");
    }

    @Test
    void thePlayerFoldersAreReserved() {
        String player = UUID.randomUUID().toString();
        assertTrue(LibraryPaths.reserved("_players"));
        assertTrue(LibraryPaths.reserved("_players/" + player));
        assertFalse(LibraryPaths.reserved("_players/" + player + "/sub"));
        assertFalse(LibraryPaths.reserved(""));
        assertFalse(LibraryPaths.reserved("trees"));
        assertFalse(LibraryPaths.reserved("../x"));
        assertFalse(LibraryPaths.reserved(null));
    }

    @Test
    void pathPieces() {
        assertEquals("oak.schem", LibraryPaths.name("trees/oak.schem"));
        assertEquals("trees", LibraryPaths.parent("trees/oak.schem"));
        assertEquals("", LibraryPaths.parent("oak.schem"));
        assertEquals("oak", LibraryPaths.stem("trees/oak.schem"));
        assertEquals("trees/oak.schem", LibraryPaths.join("trees", "oak.schem"));
        assertEquals("oak.schem", LibraryPaths.join("", "oak.schem"));
    }

    // ---------------------------------------------------------------- palettes

    @Test
    void palettePathsFollowTheServersRulesAndKeepTheirKind() {
        assertEquals("palettes/moss.palette.json", LibraryPaths.normalizePalette("  palettes/moss "));
        assertEquals("moss.palette.json", LibraryPaths.normalizePalette("moss.palette.json"));
        assertEquals("moss.schem.palette.json", LibraryPaths.normalizePalette("moss.schem"));
        assertEquals("", LibraryPaths.normalizePalette("   "));
        assertTrue(LibraryPaths.paletteProblem("palettes/moss.palette.json").isEmpty());
        assertEquals("a palette file must end in .palette.json",
                LibraryPaths.paletteProblem("trees/oak.schem").orElseThrow());
        for (String bad : List.of("../moss.palette.json", "a b.palette.json", ".palette.json", "con.palette.json",
                "_players/moss.palette.json", "moss.PALETTE.JSON", "a".repeat(52) + ".palette.json")) {
            assertTrue(LibraryPaths.paletteProblem(bad).isPresent(), bad);
        }
        assertTrue(LibraryPaths.fileProblem("moss.palette.json").isPresent(), "a schematic path is never a palette");
        assertTrue(LibraryPaths.isPalette("a/moss.palette.json"));
        assertFalse(LibraryPaths.isPalette("a/moss.schem"));
        assertEquals("moss", LibraryPaths.stem("a/moss.palette.json"));

        // A palette renamed stays a palette; a schematic stays a schematic.
        assertEquals(new LibraryPaths.Target("trees/mossy.palette.json", LibraryPaths.Problem.NONE, ""),
                LibraryPaths.newName("trees", "mossy", LibraryPath.Kind.PALETTE, "trees/moss.palette.json"));
        assertEquals(LibraryPaths.Problem.SAME,
                LibraryPaths.newName("trees", "moss", LibraryPath.Kind.PALETTE, "trees/moss.palette.json").problem());
        assertEquals(LibraryPaths.Problem.INVALID,
                LibraryPaths.newName("trees", "oak.palette.json", LibraryPath.Kind.SCHEMATIC, "trees/oak.schem")
                        .problem());
        // Moves keep the name, so the kind: a palette may be moved like an asset.
        assertEquals(new LibraryPaths.Target("rocks/moss.palette.json", LibraryPaths.Problem.NONE, ""),
                LibraryPaths.moveTo("trees/moss.palette.json", "rocks"));
    }
}
