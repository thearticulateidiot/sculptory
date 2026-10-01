package dev.sculptory.fabric.client.editor.demo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.schem.DataFixHook;
import dev.sculptory.core.schem.Schematic;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.mock.MockStateSpace;
import dev.sculptory.fabric.library.PaletteFile;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The demo's starter kit: what it writes, that it reads back, and that only its own folder is emptied. */
class DemoLibraryTest {
    private static final int DATA_VERSION = 3955;
    private static final List<String> FILES = List.of(DemoLibrary.COTTAGE, DemoLibrary.BOULDER,
            DemoLibrary.LAMP_POST, DemoLibrary.MOSSY_STONE, DemoLibrary.FOREST_FLOOR);

    @TempDir
    Path root;

    private Schematic readSchematic(String path) throws IOException {
        return SchematicCodec.read(new ByteArrayInputStream(Files.readAllBytes(root.resolve(path))),
                new MockStateSpace(), SchematicCodec.Limits.DEFAULT, DataFixHook.identity(DATA_VERSION));
    }

    /** Every cell of {@code read} is the state {@code drawn} has there (absent cells as air). */
    private static void assertSameBlocks(Clipboard drawn, Clipboard read) {
        assertEquals(drawn.size(), read.size());
        for (int x = 0; x < drawn.size().x(); x++) {
            for (int y = 0; y < drawn.size().y(); y++) {
                for (int z = 0; z < drawn.size().z(); z++) {
                    assertEquals(format(drawn, x, y, z), format(read, x, y, z), "at " + x + " " + y + " " + z);
                }
            }
        }
    }

    private static String format(Clipboard clipboard, int x, int y, int z) {
        StateSpace states = clipboard.states();
        int state = clipboard.get(x, y, z);
        return states.format(state < 0 ? states.air() : state);
    }

    @Test
    void theKitIsThreeSchematicsAndTwoPalettesInTheStarterKitFolder() throws Exception {
        DemoLibrary.seed(root, DATA_VERSION);
        for (String file : FILES) {
            assertTrue(file.startsWith(DemoLibrary.FOLDER + "/"), file);
            assertTrue(Files.isRegularFile(root.resolve(file)), file);
        }
        assertFalse(Files.exists(root.resolve(DemoLibrary.SAVED_HOUSE)));
    }

    @Test
    void theSchematicsReadBackAsDrawn() throws Exception {
        DemoLibrary.seed(root, DATA_VERSION);
        record Piece(String path, String name, BlockPos size, Function<StateSpace, Clipboard> draw) {}
        List<Piece> pieces = List.of(
                new Piece(DemoLibrary.COTTAGE, "Cottage", new BlockPos(9, 8, 9), DemoLibrary::cottage),
                new Piece(DemoLibrary.BOULDER, "Boulder", new BlockPos(5, 4, 5), DemoLibrary::boulder),
                new Piece(DemoLibrary.LAMP_POST, "Lamp post", new BlockPos(2, 5, 1), DemoLibrary::lampPost));
        for (Piece piece : pieces) {
            Schematic read = readSchematic(piece.path());
            Clipboard drawn = piece.draw().apply(new MockStateSpace());
            assertEquals(SchematicFormat.SPONGE, read.format(), piece.path());
            assertEquals(3, read.formatVersion(), piece.path());
            assertEquals(DATA_VERSION, read.dataVersion(), piece.path());
            assertEquals(piece.size(), read.dims(), piece.path());
            assertEquals(drawn.anchor(), read.clipboard().anchor(), piece.path());
            assertEquals(piece.name(), read.metadata().name());
            assertEquals("Sculptory demo", read.metadata().author());
            assertTrue(read.report().isLossless(), piece.path() + ": " + read.report());
            assertSameBlocks(drawn, read.clipboard());
        }
        // The pieces stand on their bottom layer, anchored at its middle.
        assertEquals(new BlockPos(4, 0, 4), readSchematic(DemoLibrary.COTTAGE).clipboard().anchor());
        // The lamp post at its post, not over the air under its arm.
        assertEquals(BlockPos.ORIGIN, readSchematic(DemoLibrary.LAMP_POST).clipboard().anchor());
        assertEquals("minecraft:lantern[hanging=true]",
                format(readSchematic(DemoLibrary.LAMP_POST).clipboard(), 1, 3, 0));
    }

    @Test
    void thePalettesReadBackWithTheirEntriesAndPattern() throws Exception {
        DemoLibrary.seed(root, DATA_VERSION);
        PaletteFile.Content mossy = PaletteFile.decode(Files.readAllBytes(root.resolve(DemoLibrary.MOSSY_STONE)));
        assertEquals(4, mossy.entries().size());
        assertEquals(DemoLibrary.mossyStone().entries(), mossy.entries());
        assertEquals(OptionalInt.of(DATA_VERSION), mossy.dataVersion());
        assertEquals(PalettePattern.Kind.RANDOM, mossy.pattern().kind());

        PaletteFile.Content forest = PaletteFile.decode(Files.readAllBytes(root.resolve(DemoLibrary.FOREST_FLOOR)));
        assertEquals(4, forest.entries().size());
        assertEquals(DemoLibrary.forestFloor().entries(), forest.entries());
        assertEquals(PalettePattern.Kind.PATCHES, forest.pattern().kind());
        assertEquals(4, forest.pattern().patchSize());
        assertEquals("minecraft:podzol", forest.entries().get(0).state());
    }

    @Test
    void seedingEmptiesOnlyTheStarterKitFolder() throws Exception {
        Path kit = root.resolve(DemoLibrary.FOLDER);
        Files.createDirectories(kit.resolve("nested/deeper"));
        Files.writeString(kit.resolve("old.schem"), "an earlier run's save");
        Files.writeString(kit.resolve(DemoLibrary.SAVED_HOUSE.substring(DemoLibrary.FOLDER.length() + 1)), "house");
        Files.writeString(kit.resolve("nested/deeper/lost.palette.json"), "{}");
        Files.createDirectories(root.resolve("palettes"));
        Files.writeString(root.resolve("keep.schem"), "a builder's own");
        Files.writeString(root.resolve("palettes/mine.palette.json"), "{\"mine\": true}");

        DemoLibrary.seed(root, DATA_VERSION);

        assertFalse(Files.exists(kit.resolve("old.schem")));
        assertFalse(Files.exists(root.resolve(DemoLibrary.SAVED_HOUSE)));
        assertFalse(Files.exists(kit.resolve("nested")));
        assertEquals("a builder's own", Files.readString(root.resolve("keep.schem")));
        assertEquals("{\"mine\": true}", Files.readString(root.resolve("palettes/mine.palette.json")));
        for (String file : FILES) {
            assertTrue(Files.isRegularFile(root.resolve(file)), file);
        }
    }

    @Test
    void seedingAgainWritesTheSameBytes() throws Exception {
        DemoLibrary.seed(root, DATA_VERSION);
        List<byte[]> first = FILES.stream().map(file -> read(root.resolve(file))).toList();
        DemoLibrary.seed(root, DATA_VERSION);
        for (int i = 0; i < FILES.size(); i++) {
            assertArrayEquals(first.get(i), read(root.resolve(FILES.get(i))), FILES.get(i));
        }
    }

    private static byte[] read(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new AssertionError(file + " unreadable", e);
        }
    }
}
