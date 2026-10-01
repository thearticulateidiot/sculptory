package dev.sculptory.core.schem;

import static dev.sculptory.core.schem.SchematicSamples.DATA_VERSION;
import static dev.sculptory.core.schem.SchematicSamples.METADATA;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SchematicFilesTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final DataFixHook identity = DataFixHook.identity(DATA_VERSION);

    private byte[] write(SchematicFormat format, Clipboard clipboard) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        SchematicFiles.write(format, out, clipboard, METADATA, DATA_VERSION);
        return out.toByteArray();
    }

    @Test
    void everyFormatIsReadAsWhatItIsWhateverItsName() throws IOException {
        Clipboard clipboard = SchematicSamples.richClipboard(states);
        for (SchematicFormat format : SchematicFormat.values()) {
            byte[] file = write(format, clipboard);
            Schematic read = SchematicFiles.read(new ByteArrayInputStream(file), states,
                    SchematicCodec.Limits.untrustedUpload(), identity);
            assertEquals(format, read.format());
            assertEquals(clipboard.contentHash(), read.clipboard().contentHash(), format.name());
            SchematicFiles.Header header = SchematicFiles.header(file);
            assertEquals(format, header.format());
            assertArrayEquals(new int[] {6, 4, 5}, header.dims(), format.name());
            assertEquals(List.of("tree", "oak"), header.tags(), format.name());
        }
    }

    @Test
    void fileNamesMapToFormats() {
        assertEquals(SchematicFormat.SPONGE, SchematicFormat.ofFileName("a/b.schem"));
        assertEquals(SchematicFormat.LITEMATIC, SchematicFormat.ofFileName("House.LITEMATIC"));
        assertEquals(SchematicFormat.STRUCTURE, SchematicFormat.ofFileName("igloo.nbt"));
        assertNull(SchematicFormat.ofFileName(".nbt"), "an extension alone is no name");
        assertNull(SchematicFormat.ofFileName("old.schematic"));
        assertNull(SchematicFormat.ofFileName("palette.palette.json"));
    }

    @Test
    void headersOfUnreadableFilesAreEmpty() {
        SchematicFiles.Header header = SchematicFiles.header(new byte[] {1, 2, 3});
        assertNull(header.dims());
        assertEquals(List.of(), header.tags());
        NbtCompound odd = NbtCompound.builder().put("Regions", NbtCompound.builder().putInt("r", 1).build()).build();
        assertNull(SchematicFiles.header(odd).dims());
    }

    @Test
    void anythingElseIsReadAsSpongeAndRefused() {
        NbtCompound random = NbtCompound.builder().putString("hello", "world").build();
        assertEquals(SchematicFormat.SPONGE, SchematicFormat.detect(random));
        assertEquals(SchematicException.Kind.MALFORMED, assertThrows(SchematicException.class,
                () -> SchematicFiles.decode(random, states, SchematicCodec.Limits.DEFAULT, identity)).kind());
    }
}
