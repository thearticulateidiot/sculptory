package dev.sculptory.mc.client.editor.heightmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.brush.HeightImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The built-in pictures are exactly what {@link BuiltInHeightmaps} draws, each a small file (at most 64 KB), dark at its
 * edges, and the library lists them all. A picture missing from the source tree is written there (that is how they were
 * made: run this test, then commit the files).
 */
class BuiltInHeightmapsTest {
    private static final Path SOURCE = Path.of("../common/src/client/resources/assets/sculptory/heightmaps");

    @Test
    void theShippedPicturesAreTheGeneratorsOwn() throws Exception {
        for (String name : HeightmapLibrary.BUILT_IN) {
            HeightImage drawn = BuiltInHeightmaps.generate(name);
            Path file = SOURCE.resolve(name + ".png");
            if (!Files.exists(file) && Files.isDirectory(SOURCE.getParent())) {
                Files.createDirectories(SOURCE);
                Files.write(file, PngImages.writeGray(drawn));
            }
            byte[] bytes = Files.readAllBytes(file);
            assertTrue(bytes.length <= 64 * 1024, name + " is " + bytes.length + " bytes");
            assertEquals(drawn, PngImages.read(bytes), name + " differs from the generator's picture");
            assertEquals(drawn, BuiltInHeightmaps.generate(name), name + " is drawn the same every time");
            int edge = 0, peak = 0;
            for (int i = 0; i < BuiltInHeightmaps.SIDE; i++) {
                edge = Math.max(edge, Math.max(drawn.value(i, 0), drawn.value(0, i)));
                edge = Math.max(edge, Math.max(drawn.value(i, BuiltInHeightmaps.SIDE - 1), drawn.value(BuiltInHeightmaps.SIDE - 1, i)));
            }
            for (byte b : drawn.pixels()) peak = Math.max(peak, b & 0xFF);
            assertEquals(0, edge, name + " is not dark at its edge");
            assertTrue(peak >= 150, name + " peaks at only " + peak);
        }
    }

    @Test
    void theLibraryListsEveryBuiltIn() throws IOException {
        HeightmapLibrary library = HeightmapLibrary.builtInOnly();
        assertEquals(HeightmapLibrary.BUILT_IN, library.entries().stream().map(e -> e.ref().name()).toList());
        assertTrue(library.image(HeightmapLibrary.DEFAULT).isPresent());
        assertTrue(library.refresh().isEmpty());
    }
}
