package dev.sculptory.fabric.client.editor.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.English;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The demo's script and switch: its steps, its captions' English, and how a run is configured. */
class DemoScriptTest {
    @TempDir
    Path dir;

    @Test
    void theScriptHasTheNineteenStepsInOrderWithDistinctIds() {
        List<DemoStep<Demo>> steps = DemoScript.steps();
        assertEquals(List.of("title", "screen", "select", "brushes", "paint", "shape", "symmetry", "paste", "generate",
                "extrude", "fluid", "scatter", "tinker", "library", "history", "tutorial", "wiki", "builder", "closing"),
                steps.stream().map(DemoStep::id).toList());
        assertEquals(steps.size(), new HashSet<>(steps.stream().map(DemoStep::id).toList()).size());
        // The first opens the editor itself, the last closes it; builder mode closes and reopens it inside its step.
        assertFalse(steps.get(0).startsInEditor());
        assertFalse(steps.get(steps.size() - 1).startsInEditor());
        assertTrue(steps.get(1).startsInEditor());
        assertEquals(7, DemoRunner.startIndex(steps, "paste"));
        assertEquals(7, DemoRunner.startIndex(steps, "8"));
    }

    @Test
    void everyCaptionHasEnglishUnderThePrefixAndTheNameIsOneKey() {
        List<String> keys = DemoCaptions.keys();
        assertTrue(keys.size() > 60, "captions: " + keys.size());
        Set<String> seen = new HashSet<>();
        for (String key : keys) {
            assertTrue(key.startsWith(DemoCaptions.PREFIX), key);
            assertTrue(seen.add(key), "declared twice: " + key);
            assertTrue(English.INSTANCE.has(key), "no English for " + key);
            assertFalse(English.INSTANCE.translate(key, "x").isBlank(), key);
        }
        assertTrue(keys.contains(DemoCaptions.NAME));
        assertEquals("Sculptory", English.INSTANCE.translate(DemoCaptions.NAME));
        // The title and the closing line take the name, so renaming the mod is one key.
        assertTrue(English.INSTANCE.translate(DemoCaptions.TITLE, "X").startsWith("X"));
        assertTrue(English.INSTANCE.translate(DemoCaptions.CLOSING_TITLE, "X").startsWith("X"));
        assertEquals("Press Enter when recording", English.INSTANCE.translate(DemoCaptions.READY));
    }

    @Test
    void theDemoIsOffWithoutThePropertyAndReadsItsOptions() {
        assertTrue(DemoConfig.of(null, "3", "true", "5", "false").isEmpty());
        assertTrue(DemoConfig.of(" ", null, null, null, null).isEmpty());
        DemoConfig config = DemoConfig.of(dir.toString(), " paste ", "true", " 8 ", "false").orElseThrow();
        assertEquals(dir.toAbsolutePath().normalize(), config.dir());
        assertEquals("paste", config.from());
        assertTrue(config.maximize());
        assertEquals(8, config.autoStartSeconds());
        assertFalse(config.captions());
        assertEquals(DemoPacing.WITHOUT_CAPTIONS, config.pacing());
        DemoConfig defaults = DemoConfig.of(dir.toString(), null, null, null, null).orElseThrow();
        assertEquals("", defaults.from());
        assertFalse(defaults.maximize());
        assertEquals(0, defaults.autoStartSeconds());
        assertTrue(defaults.captions());
        assertEquals(DemoPacing.WITH_CAPTIONS, defaults.pacing());
        assertTrue(DemoConfig.of(dir.toString(), null, null, null, "true").orElseThrow().captions());
        assertThrows(IllegalArgumentException.class, () -> DemoConfig.of(dir.toString(), null, null, "soon", null));
        assertThrows(IllegalArgumentException.class, () -> DemoConfig.of(dir.toString(), null, null, "-1", null));
        assertThrows(IllegalArgumentException.class, () -> DemoConfig.of("relative/dir", null, null, null, null));
        assertEquals(Optional.empty(), DemoConfig.of("", "1", null, null, null));
    }
}
