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
    void theScriptHasTwentyStepsInOrderWithDistinctIds() {
        List<DemoStep<Demo>> steps = DemoScript.steps();
        assertEquals(List.of("title", "builder", "screen", "tutorial", "wiki", "history", "paste", "library", "brushes",
                "fluid", "select", "overlay", "shape", "symmetry", "generate", "extrude", "paint", "scatter", "tinker",
                "closing"), steps.stream().map(DemoStep::id).toList());
        assertEquals(steps.size(), new HashSet<>(steps.stream().map(DemoStep::id).toList()).size());
        // Builder mode comes first, before the editor was ever opened, and opens it at its end; the closing step
        // opens and closes it itself. Every other step starts in the editor.
        List<String> outside = steps.stream().filter(step -> !step.startsInEditor()).map(DemoStep::id).toList();
        assertEquals(List.of("title", "builder", "closing"), outside);
        assertEquals(6, DemoRunner.startIndex(steps, "paste"));
        assertEquals(6, DemoRunner.startIndex(steps, "7"));
    }

    @Test
    void theStepsAreSevenChaptersEachStepInExactlyOne() {
        List<DemoChapter<Demo>> chapters = DemoScript.chapters();
        assertEquals(List.of("builder", "learning", "library", "land", "building", "detailing", "closing"),
                chapters.stream().map(DemoChapter::id).toList());
        assertEquals(DemoScript.steps().stream().map(DemoStep::id).toList(),
                chapters.stream().flatMap(chapter -> chapter.steps().stream()).map(DemoStep::id).toList());
        assertEquals(List.of("title", "builder"), ids(chapters.get(0)));
        assertEquals(List.of("screen", "tutorial", "wiki"), ids(chapters.get(1)));
        assertEquals(List.of("history", "paste", "library"), ids(chapters.get(2)));
        Set<String> titles = new HashSet<>();
        for (DemoChapter<Demo> chapter : chapters) {
            assertEquals(DemoCaptions.PREFIX + "chapter." + chapter.id(), chapter.titleKey());
            assertTrue(English.INSTANCE.has(chapter.titleKey()), chapter.titleKey());
            assertTrue(titles.add(English.INSTANCE.translate(chapter.titleKey())), chapter.id());
        }
        assertEquals("Builder mode", English.INSTANCE.translate(chapters.get(0).titleKey()));
        assertEquals(DemoScript.steps().size(), DemoChapters.byStep(chapters).size());
    }

    private static List<String> ids(DemoChapter<Demo> chapter) {
        return chapter.steps().stream().map(DemoStep::id).toList();
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
