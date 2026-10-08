package dev.sculptory.fabric.client.editor.heightmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.brush.HeightImage;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The picture library and its PNG reader: built-ins first, then the folder's PNGs by name; a bad file skipped and
 * reported once per version; changes and removals picked up on refresh; references as preset text; every PNG colour
 * type read as brightness.
 */
class HeightmapLibraryTest {
    @TempDir
    Path folder;

    private static HeightImage gradient(int width, int height) {
        byte[] pixels = new byte[width * height];
        for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (i * 7);
        return new HeightImage(width, height, pixels);
    }

    @Test
    void thePickerListsTheBuiltInsThenTheFoldersPicturesAndSkipsBadOnes() throws IOException {
        Files.write(folder.resolve("Zebra.png"), PngImages.writeGray(gradient(3, 2)));
        Files.write(folder.resolve("island.PNG"), PngImages.writeGray(gradient(5, 5)));
        Files.writeString(folder.resolve("notes.png"), "not a picture", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("readme.txt"), "ignored", StandardCharsets.UTF_8);
        Files.createDirectories(folder.resolve("sub.png"));
        HeightmapLibrary library = new HeightmapLibrary(folder, HeightmapLibrary.BuiltIns.CLASS_PATH);

        List<HeightmapLibrary.BadFile> bad = library.refresh();
        assertEquals(List.of(new HeightmapLibrary.BadFile("notes.png", PngImages.Reason.NOT_PNG)), bad);
        List<String> names = library.entries().stream().map(e -> e.ref().text()).toList();
        assertEquals(HeightmapLibrary.BUILT_IN.size() + 2, names.size());
        assertEquals("builtin:hill", names.get(0));
        assertEquals(List.of("file:island.PNG", "file:Zebra.png"), names.subList(names.size() - 2, names.size()));
        assertEquals(gradient(5, 5), library.image(HeightmapRef.file("island.PNG")).orElseThrow());
        assertTrue(library.image(HeightmapRef.file("notes.png")).isEmpty());

        assertTrue(library.refresh().isEmpty(), "a bad file is reported once");
        long version = library.entry(HeightmapRef.file("Zebra.png")).orElseThrow().version();
        Files.write(folder.resolve("Zebra.png"), PngImages.writeGray(gradient(4, 4)));
        Files.setLastModifiedTime(folder.resolve("Zebra.png"), FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Files.writeString(folder.resolve("notes.png"), "still not a picture", StandardCharsets.UTF_8);
        Files.delete(folder.resolve("island.PNG"));
        assertEquals(List.of("notes.png"), library.refresh().stream().map(HeightmapLibrary.BadFile::name).toList(),
                "a changed bad file is reported again");
        HeightmapLibrary.Entry zebra = library.entry(HeightmapRef.file("Zebra.png")).orElseThrow();
        assertEquals(gradient(4, 4), zebra.image());
        assertNotEquals(version, zebra.version(), "a changed picture has a new version");
        assertTrue(library.entry(HeightmapRef.file("island.PNG")).isEmpty(), "a removed file goes");
    }

    @Test
    void aMissingFolderIsMadeAndABuiltInOnlyLibraryHasNoFiles() {
        Path missing = folder.resolve("sculptory").resolve("heightmaps");
        HeightmapLibrary library = new HeightmapLibrary(missing, HeightmapLibrary.BuiltIns.CLASS_PATH);
        assertTrue(library.refresh().isEmpty());
        assertTrue(Files.isDirectory(missing), "the folder is there for the builder to fill");
        assertEquals(HeightmapLibrary.BUILT_IN.size(), library.entries().size());
        assertFalse(HeightmapLibrary.builtInOnly().folder().isPresent());
    }

    @Test
    void referencesTravelAsText() {
        assertEquals(HeightmapRef.builtIn("hill"), HeightmapRef.parse("builtin:hill"));
        assertEquals(HeightmapRef.file("my island.png"), HeightmapRef.parse("file:my island.png"));
        assertEquals("file:a.png", HeightmapRef.file("a.png").text());
        assertEquals("my island", HeightmapRef.file("my island.png").fileLabel());
        assertThrows(IllegalArgumentException.class, () -> HeightmapRef.parse("hill"));
        assertThrows(IllegalArgumentException.class, () -> HeightmapRef.parse("file:"));
        assertThrows(IllegalArgumentException.class, () -> HeightmapRef.parse("file:a/b.png"));
        assertThrows(IllegalArgumentException.class, () -> HeightmapRef.parse("file:a;b.png"));
    }

    @Test
    void grayPngsRoundTrip() throws Exception {
        HeightImage image = gradient(37, 11);
        assertEquals(image, PngImages.read(PngImages.writeGray(image)));
    }

    /** Colour, palette, alpha and 16-bit pictures read as their brightness (Java's own writer makes them). */
    @Test
    void everyColourTypeReadsAsBrightness() throws Exception {
        int w = 4, h = 3;
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        BufferedImage gray16 = new BufferedImage(w, h, BufferedImage.TYPE_USHORT_GRAY);
        byte[] reds = {0, (byte) 255, 0, (byte) 200}, greens = {0, 0, (byte) 255, (byte) 200}, blues = {0, 0, 0, (byte) 200};
        BufferedImage indexed = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED,
                new IndexColorModel(2, 4, reds, greens, blues));
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                rgb.setRGB(x, y, 0xFF0000 * (x == 1 ? 1 : 0) | (x == 2 ? 0x00FF00 : 0) | (x == 3 ? 0xC8C8C8 : 0));
                argb.setRGB(x, y, (x * 85) << 24 | 0xFFFFFF);
                gray16.getRaster().setSample(x, y, 0, (x * 85) * 257);
                indexed.getRaster().setSample(x, y, 0, x);
            }
        }
        int[] colours = {0, 76, 150, 200};
        HeightImage fromRgb = PngImages.read(png(rgb));
        HeightImage fromIndexed = PngImages.read(png(indexed));
        HeightImage fromAlpha = PngImages.read(png(argb));
        HeightImage fromGray16 = PngImages.read(png(gray16));
        for (int x = 0; x < w; x++) {
            assertEquals(colours[x], fromRgb.value(x, 1), "rgb " + x);
            assertEquals(colours[x], fromIndexed.value(x, 2), "palette " + x);
            assertEquals(x * 85, fromAlpha.value(x, 0), "white with alpha " + x);
            assertEquals(x * 85, fromGray16.value(x, 0), "16-bit gray " + x);
        }
    }

    @Test
    void damagedFilesAreRefusedWithAReason() throws Exception {
        byte[] good = PngImages.writeGray(gradient(16, 16));
        byte[] cut = java.util.Arrays.copyOf(good, good.length - 30);
        PngImages.BadImageException shortened = assertThrows(PngImages.BadImageException.class, () -> PngImages.read(cut));
        assertEquals(PngImages.Reason.DAMAGED, shortened.reason());
        assertEquals(PngImages.Reason.NOT_PNG, assertThrows(PngImages.BadImageException.class,
                () -> PngImages.read("GIF89a............................".getBytes(StandardCharsets.US_ASCII))).reason());
        byte[] huge = good.clone();
        huge[16] = 0;
        huge[17] = 0;
        huge[18] = 0x20;
        huge[19] = 1;
        assertEquals(PngImages.Reason.TOO_LARGE, assertThrows(PngImages.BadImageException.class,
                () -> PngImages.read(huge)).reason(), "a header claiming 8,193 pixels a side, refused before any is read");
        assertArrayEquals(gradient(16, 16).pixels(), PngImages.read(good).pixels());
    }

    /** A picture over 1,024 pixels a side is shrunk as it is read: each pixel the mean of a whole block. */
    @Test
    void aLargePictureIsShrunkAsItIsRead() throws Exception {
        byte[] pixels = new byte[2050 * 4];
        for (int y = 0; y < 4; y++) {
            for (int x = 0; x < 2050; x++) pixels[y * 2050 + x] = (byte) (x % 2 == 0 ? 100 : 200);
        }
        HeightImage shrunk = PngImages.read(PngImages.writeGray(new HeightImage(2050, 4, pixels)));
        assertEquals(684, shrunk.width(), "a factor of 3 fits 2,050 into 1,024");
        assertEquals(2, shrunk.height());
        assertEquals(133, shrunk.value(0, 0), "(100 + 200 + 100) / 3");
        assertEquals(167, shrunk.value(1, 0), "(200 + 100 + 200) / 3");
        assertEquals(200, shrunk.value(683, 1), "the last block holds one pixel: column 2,049 (odd) of row 3");
    }

    /** One-bit gray and a palette with see-through entries (a tRNS chunk) read as brightness. */
    @Test
    void lowBitDepthsAndPaletteTransparencyRead() throws Exception {
        BufferedImage bits = new BufferedImage(9, 2, BufferedImage.TYPE_BYTE_BINARY);
        for (int x = 0; x < 9; x++) bits.getRaster().setSample(x, 0, 0, x % 2);
        HeightImage fromBits = PngImages.read(png(bits));
        for (int x = 0; x < 9; x++) assertEquals(x % 2 == 0 ? 0 : 255, fromBits.value(x, 0), "bit " + x);
        byte[] white = {(byte) 255, (byte) 255}, alpha = {(byte) 255, (byte) 51};
        BufferedImage seeThrough = new BufferedImage(2, 1, BufferedImage.TYPE_BYTE_INDEXED,
                new IndexColorModel(1, 2, white, white, white, alpha));
        seeThrough.getRaster().setSample(1, 0, 0, 1);
        HeightImage fromAlpha = PngImages.read(png(seeThrough));
        assertEquals(255, fromAlpha.value(0, 0));
        assertEquals(51, fromAlpha.value(1, 0), "a fifth opaque white is a fifth as high");
    }

    /** Files are decoded on the decoder's executor and join the list on a later look; a name presets can't hold is refused. */
    @Test
    void filesAreDecodedOffTheCallersThreadAndBadNamesRefused() throws IOException {
        Files.write(folder.resolve("later.png"), PngImages.writeGray(gradient(3, 3)));
        Files.write(folder.resolve("a;b.png"), PngImages.writeGray(gradient(3, 3)));
        List<Runnable> queued = new java.util.ArrayList<>();
        HeightmapLibrary library = new HeightmapLibrary(folder, HeightmapLibrary.BuiltIns.CLASS_PATH, queued::add);
        assertEquals(List.of(new HeightmapLibrary.BadFile("a;b.png", PngImages.Reason.BAD_NAME)), library.refresh());
        assertEquals(1, queued.size(), "only the good file goes to the decoder");
        assertTrue(library.entry(HeightmapRef.file("later.png")).isEmpty(), "not decoded yet");
        queued.forEach(Runnable::run);
        assertEquals(gradient(3, 3), library.image(HeightmapRef.file("later.png")).orElseThrow());
        assertTrue(library.refresh().isEmpty());
        assertEquals(1, queued.size(), "an unchanged file is not decoded again");
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", out));
        return out.toByteArray();
    }
}
