package dev.sculptory.fabric.client.editor.clipboard;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportFilesTest {
    @TempDir
    Path directory;

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private List<String> files() throws IOException {
        try (Stream<Path> list = Files.list(directory)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void namesAreSanitized() {
        assertEquals("clipboard", ExportFiles.sanitize("clipboard.schem"));
        assertEquals("oak_tree", ExportFiles.sanitize("oak tree.schem"));
        assertEquals("passwd", ExportFiles.sanitize("../../etc/passwd"));
        assertEquals("evil", ExportFiles.sanitize("..\\..\\Windows\\evil.schematic"));
        assertEquals("hidden", ExportFiles.sanitize(".hidden"));
        assertEquals("rf", ExportFiles.sanitize("-rf"));
        assertEquals("a.b", ExportFiles.sanitize("a..b"));
        assertEquals("trailing", ExportFiles.sanitize("trailing..."));
        assertEquals("caf_", ExportFiles.sanitize("café"));
        assertEquals("a_b", ExportFiles.sanitize("a:*?<>|\"b"));
        assertEquals("_CON", ExportFiles.sanitize("CON"));
        assertEquals("_com1.old", ExportFiles.sanitize("com1.old.schem"));
        assertEquals("console", ExportFiles.sanitize("console"));
        assertEquals("clipboard", ExportFiles.sanitize(""));
        assertEquals("clipboard", ExportFiles.sanitize(null));
        assertEquals("clipboard", ExportFiles.sanitize("???"));
        assertEquals("clipboard", ExportFiles.sanitize("..."));
        assertEquals(64, ExportFiles.sanitize("x".repeat(300)).length());
        assertEquals("House_1", ExportFiles.sanitize("  House 1  "));
    }

    @Test
    void writesUnderTheSanitizedName() throws IOException {
        Path written = ExportFiles.write(directory, "my house.schem", bytes("one"));
        assertEquals(directory.resolve("my_house.schem"), written);
        assertArrayEquals(bytes("one"), Files.readAllBytes(written));
        assertEquals(List.of("my_house.schem"), files(), "no temporary file is left behind");
    }

    @Test
    void neverOverwritesAndNumbersTheNextFiles() throws IOException {
        Files.writeString(directory.resolve("clipboard.schem"), "someone else's file");
        Path first = ExportFiles.write(directory, "clipboard.schem", bytes("first"));
        Path second = ExportFiles.write(directory, "clipboard.schem", bytes("second"));
        assertEquals(directory.resolve("clipboard-1.schem"), first);
        assertEquals(directory.resolve("clipboard-2.schem"), second);
        assertEquals("someone else's file", Files.readString(directory.resolve("clipboard.schem")));
        assertArrayEquals(bytes("first"), Files.readAllBytes(first));
        assertArrayEquals(bytes("second"), Files.readAllBytes(second));
        assertEquals(List.of("clipboard-1.schem", "clipboard-2.schem", "clipboard.schem"), files());
    }

    @Test
    void aDirectoryInTheWayIsSkippedToo() throws IOException {
        Files.createDirectory(directory.resolve("tree.schem"));
        assertEquals(directory.resolve("tree-1.schem"), ExportFiles.write(directory, "tree", bytes("x")));
    }

    @Test
    void createsTheExportFolder() throws IOException {
        Path nested = directory.resolve("sculptory").resolve("exports");
        Path written = ExportFiles.write(nested, "a", bytes("abc"));
        assertTrue(Files.isRegularFile(written));
        assertEquals(nested.resolve("a.schem"), written);
    }

    @Test
    void aFolderThatCannotBeMadeFails() throws IOException {
        Path file = directory.resolve("not-a-folder");
        Files.writeString(file, "x");
        assertThrows(IOException.class, () -> ExportFiles.write(file.resolve("exports"), "a", bytes("abc")));
    }

    @Test
    void numberedNames() {
        assertEquals("a.schem", ExportFiles.fileName("a", 0));
        assertEquals("a-7.schem", ExportFiles.fileName("a", 7));
        assertEquals("a-2.nbt", ExportFiles.fileName("a", 2, ".nbt"));
    }

    @Test
    void otherFormatsGetTheirExtensionAndDropTheTypedOne() throws IOException {
        assertEquals("ship", ExportFiles.sanitize("ship.litematic"));
        assertEquals("igloo", ExportFiles.sanitize("igloo.NBT"));
        assertEquals(directory.resolve("ship.litematic"), ExportFiles.write(directory, "ship.schem", bytes("a"), ".litematic"));
        assertEquals(directory.resolve("ship-1.litematic"), ExportFiles.write(directory, "ship", bytes("b"), ".litematic"));
        assertEquals(directory.resolve("ship.nbt"), ExportFiles.write(directory, "ship.litematic", bytes("c"), ".nbt"));
        assertEquals(List.of("ship-1.litematic", "ship.litematic", "ship.nbt"), files());
    }
}
