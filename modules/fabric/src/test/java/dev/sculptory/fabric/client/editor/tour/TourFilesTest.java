package dev.sculptory.fabric.client.editor.tour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.LayoutStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Step names and numbering, the index lines and the output directory switch. */
class TourFilesTest {
    @TempDir
    Path dir;

    @Test
    void pictureFilesAreNumberedWithAtLeastTwoDigits() {
        assertEquals("01-editor-opened.png", TourIndex.fileName(1, 28, "editor-opened"));
        assertEquals("28-last.png", TourIndex.fileName(28, 28, "last"));
        assertEquals("007-more.png", TourIndex.fileName(7, 120, "more"));
        assertThrows(IllegalArgumentException.class, () -> TourIndex.fileName(0, 3, "x"));
        assertThrows(IllegalArgumentException.class, () -> TourIndex.fileName(4, 3, "x"));
    }

    @Test
    void stepNamesAreLowerCaseWordsJoinedByDashes() {
        TourStep.Action<Object> nothing = tour -> {};
        assertEquals("tool-raise", TourStep.of("tool-raise", "Raise", nothing).name());
        for (String bad : new String[] {"", "Tool", "tool raise", "tool_raise", "-tool", "tool-", "a--b", "../x"}) {
            assertThrows(IllegalArgumentException.class, () -> TourStep.of(bad, "d", nothing), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> TourStep.of("x", " ", nothing));
        assertThrows(IllegalArgumentException.class, () -> TourStep.of("x", "two\nlines", nothing));
        assertThrows(IllegalArgumentException.class, () -> TourStep.of("x", "d", nothing).withFrames(0));
        TourStep<Object> step = TourStep.of("x", "d", nothing).withFrames(3).withMinMillis(700);
        assertEquals(3, step.frames());
        assertEquals(700, step.minMillis());
    }

    @Test
    void indexLinesKeepFailuresOnOneLine() {
        TourIndex index = new TourIndex();
        index.add(new TourIndex.Entry(1, "a", "01-a.png", TourIndex.Status.OK, "Shows a", ""));
        index.add(new TourIndex.Entry(2, "b", "", TourIndex.Status.FAILED, "Shows b", "Boom:\n  at line 3"));
        assertEquals("# Sculptory screenshot tour: 2 steps, 1 ok, 1 failed, 0 skipped", index.lines().get(0));
        assertEquals("01-a.png | a | OK | Shows a", index.lines().get(1));
        assertEquals("(no picture) | b | FAILED: Boom: at line 3 | Shows b", index.lines().get(2));
    }

    @Test
    void theTourIsOffWithoutTheProperty() {
        assertEquals("sculptory.tour", TourConfig.PROPERTY);
        assertEquals(Optional.empty(), TourConfig.outputDir(null));
        assertEquals(Optional.empty(), TourConfig.outputDir(""));
        assertEquals(Optional.empty(), TourConfig.outputDir("   "));
        // The test JVM runs without it, like any normal game start.
        assertEquals(Optional.empty(), TourConfig.outputDir(System.getProperty(TourConfig.PROPERTY)));
    }

    @Test
    void theOutputDirectoryMustBeAbsolute() {
        assertThrows(IllegalArgumentException.class, () -> TourConfig.outputDir("tour/out"));
        Path absolute = dir.resolve("a").resolve("..").resolve("out");
        assertEquals(Optional.of(dir.resolve("out")), TourConfig.outputDir(" " + absolute + " "));
    }

    @Test
    void preparingCreatesTheDirectoryAndRemovesOnlyAnEarlierTour() throws IOException {
        Path out = dir.resolve("new").resolve("tour");
        TourConfig.prepare(out);
        assertTrue(Files.isDirectory(out));

        Files.writeString(out.resolve("01-editor-opened.png"), "old");
        Files.writeString(out.resolve("123-later-step.png"), "old");
        Files.writeString(out.resolve(TourIndex.FILE), "old");
        Files.writeString(out.resolve("notes.txt"), "keep");
        Files.writeString(out.resolve("photo.png"), "keep");
        Files.createDirectories(out.resolve("02-folder.png"));
        TourConfig.prepare(out);
        assertFalse(Files.exists(out.resolve("01-editor-opened.png")));
        assertFalse(Files.exists(out.resolve("123-later-step.png")));
        assertFalse(Files.exists(out.resolve(TourIndex.FILE)));
        assertTrue(Files.exists(out.resolve("notes.txt")));
        assertTrue(Files.exists(out.resolve("photo.png")));
        assertTrue(Files.isDirectory(out.resolve("02-folder.png")));
    }

    @Test
    void theBaseUiSizeIsOneOfTheSteps() {
        assertEquals(100, TourConfig.baseUiSize(null));
        assertEquals(100, TourConfig.baseUiSize(" "));
        assertEquals(50, TourConfig.baseUiSize("50"));
        assertEquals(150, TourConfig.baseUiSize(" 150 "));
        assertThrows(IllegalArgumentException.class, () -> TourConfig.baseUiSize("55"));
        assertThrows(IllegalArgumentException.class, () -> TourConfig.baseUiSize("big"));
    }

    @Test
    void theBaseLayoutIsAnAbsoluteFileOrTheDefaults() {
        assertEquals(Optional.empty(), TourConfig.layoutFile(null));
        assertEquals(Optional.empty(), TourConfig.layoutFile(""));
        Path file = dir.resolve("editor-layout.json");
        assertEquals(Optional.of(file), TourConfig.layoutFile(file.toString()));
        assertThrows(IllegalArgumentException.class, () -> TourConfig.layoutFile("config/editor-layout.json"));
    }

    @Test
    void anOldBaseLayoutIsTheArrangementOfTheUiSizeSavedBesideIt() throws IOException {
        Path layout = dir.resolve("editor-layout.json");
        Path ui = dir.resolve("editor-ui.json");
        assertEquals(100, TourConfig.layoutUiSize(layout), "no editor-ui.json: 100%, as in the game");
        Files.writeString(ui, "{\"version\": 1, \"uiSize\": 50}");
        assertEquals(50, TourConfig.layoutUiSize(layout));
        Files.writeString(ui, "not json");
        assertEquals(100, TourConfig.layoutUiSize(layout));
        Files.writeString(ui, "{\"version\": 2, \"uiSize\": 50}");
        assertEquals(100, TourConfig.layoutUiSize(layout), "another version: 100%, as in the game");

        // A real version-1 file at 50%: a -UiSize 100 tour shows the default layout there.
        Files.writeString(ui, "{\"version\": 1, \"uiSize\": 50}");
        String owners = "{\"version\": 1, \"windows\": [{\"id\": \"history\", \"open\": true, \"collapsed\": false,"
                + " \"anchor\": \"BOTTOM_LEFT\", \"x\": 4, \"y\": 4, \"width\": 160, \"height\": 162}]}";
        SizedLayouts base = LayoutStore.parse(owners, TourConfig.layoutUiSize(layout));
        assertEquals(List.of(EditorWindows.HISTORY), List.copyOf(base.arrangement(50).keySet()));
        assertEquals(Map.of(), base.arrangement(100));
    }

    @Test
    void setupNotesFollowTheSummary() {
        TourIndex index = new TourIndex();
        index.addNote("Base UI size: 50%");
        index.add(new TourIndex.Entry(1, "a", "01-a.png", TourIndex.Status.OK, "Shows a", ""));
        assertEquals(List.of("# Sculptory screenshot tour: 1 steps, 1 ok, 0 failed, 0 skipped",
                "# Base UI size: 50%", "01-a.png | a | OK | Shows a"), index.lines());
    }

    @Test
    void theWikiModeIsOffWithoutItsPropertyAndNeedsAnAbsoluteDirectory() {
        assertEquals("sculptory.tour.wiki", TourConfig.WIKI_PROPERTY);
        assertEquals(Optional.empty(), TourConfig.wikiDir(null));
        assertEquals(Optional.empty(), TourConfig.wikiDir("  "));
        assertThrows(IllegalArgumentException.class, () -> TourConfig.wikiDir("docs/wiki/images"));
        assertEquals(Optional.of(dir.resolve("images")), TourConfig.wikiDir(" " + dir.resolve("images") + " "));
    }
}
