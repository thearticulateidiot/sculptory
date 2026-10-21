package dev.sculptory.mc.client.editor.mask;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.mask.MaskText;
import dev.sculptory.mc.client.editor.ConfigFile;
import dev.sculptory.mc.client.util.AtomicFileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The global mask's rules in {@code editor-mask.txt} ({@link EditMaskStore}): they come back after a restart; a file a
 * newer Sculptory wrote is left byte for byte, and changes then last the session; malformed text is copied aside first.
 */
class EditMaskStoreTest {
    @TempDir
    Path dir;

    private final List<String> problems = new ArrayList<>();

    private Path file() {
        return dir.resolve(EditMaskStore.FILE_NAME);
    }

    private EditMaskStore store(EditMaskModel model) {
        EditMaskStore store = new EditMaskStore(new ConfigFile(file(), new AtomicFileStore(), problems::add), model);
        store.load();
        return store;
    }

    @Test
    void theRulesComeBackAfterARestart() throws IOException {
        EditMaskModel first = new EditMaskModel();
        store(first);
        first.setRules(MaskText.decode("v1 | is minecraft:grass_block | !height 0 63").entries());
        assertEquals("v1 | is minecraft:grass_block | !height 0 63\n", Files.readString(file()));

        EditMaskModel second = new EditMaskModel();
        store(second);
        assertEquals(first.rules(), second.rules());
        assertFalse(second.on(), "the mask starts off");
        assertEquals(List.of(), problems);
    }

    @Test
    void aNewerVersionsFileIsLeftByteForByteAndChangesLastTheSession() throws IOException {
        for (String newer : List.of("v2 | glows 3\n", "v1 | solid | glows 3\r\n", "v1 strict | solid\n")) {
            problems.clear();
            byte[] bytes = newer.getBytes(StandardCharsets.UTF_8);
            Files.write(file(), bytes);
            EditMaskModel model = new EditMaskModel();
            store(model);
            assertEquals(List.of(), model.rules(), "nothing this build can't read is used: " + newer);
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains("left unchanged"), problems.get(0));

            model.setRules(List.of(MaskEntry.of(new MaskRule.Solid())));
            assertEquals(1, model.rules().size(), "the change applies for this session");
            assertArrayEquals(bytes, Files.readAllBytes(file()), "the newer file is never written over: " + newer);
            assertFalse(Files.exists(dir.resolve(EditMaskStore.FILE_NAME + ".bad")), "nor copied aside");
        }
    }

    @Test
    void malformedTextIsCopiedAsideAndReplacedByTheNextChange() throws IOException {
        Files.writeString(file(), "v1 | height 9\n");
        EditMaskModel model = new EditMaskModel();
        store(model);
        assertEquals(List.of(), model.rules());
        assertEquals("v1 | height 9\n", Files.readString(dir.resolve(EditMaskStore.FILE_NAME + ".bad")));
        assertEquals("v1\n", Files.readString(file()), "replaced at once, so not copied aside at every start");
        assertEquals(1, problems.size(), problems.toString());

        model.setRules(List.of(MaskEntry.of(new MaskRule.NotAir())));
        assertEquals("v1 | not_air\n", Files.readString(file()));
    }
}
