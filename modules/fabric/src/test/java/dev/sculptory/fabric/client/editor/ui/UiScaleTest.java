package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.windows.UiSizeStore;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UiScaleTest {
    @Test
    void stepsGoUpAndDownAndStopAtTheEnds() {
        UiScale scale = new UiScale();
        assertEquals(100, scale.percent());
        assertEquals(List.of(50, 60, 70, 75, 80, 90, 100, 110, 125, 150), UiScale.steps());
        assertEquals(110, scale.stepped(1));
        assertEquals(90, scale.stepped(-1));
        scale.set(50);
        assertEquals(50, scale.stepped(-1));
        scale.set(150);
        assertEquals(150, scale.stepped(5), "any positive direction is one step");
    }

    @Test
    void onlyStepSizesAreAcceptedAndTheListenerHearsEachChange() {
        UiScale scale = new UiScale();
        List<Integer> heard = new ArrayList<>();
        scale.addListener(heard::add);
        assertFalse(scale.set(85));
        assertFalse(scale.set(0));
        assertFalse(scale.set(200));
        assertFalse(scale.set(100), "unchanged");
        assertTrue(scale.set(75));
        assertEquals(75, scale.percent());
        assertEquals(List.of(75), heard);
    }

    @Test
    void uiUnitsAreScreenPixelsDividedByTheFactor() {
        UiScale scale = new UiScale();
        assertTrue(scale.isIdentity());
        assertEquals(123.25, scale.toUi(123.25), "100% leaves coordinates untouched");
        assertEquals(427, scale.uiLength(427));

        scale.set(50);
        assertEquals(0.5F, scale.factor());
        assertEquals(200.0, scale.toUi(100));
        assertEquals(854, scale.uiLength(427));

        scale.set(75);
        assertEquals(569, scale.uiLength(427), "427 / 0.75 = 569.3: whole UI units that fit");
        assertEquals(40.0, scale.toUi(30), 1e-9);

        scale.set(150);
        assertEquals(284, scale.uiLength(427));
        assertEquals(20.0, scale.toUi(30), 1e-9);
    }

    @Test
    void drawingIsScaledOnlyAwayFromOneHundredPercent() {
        UiScale scale = new UiScale();
        RecordingGraphics g = new RecordingGraphics();
        scale.draw(g, () -> g.fill(0, 0, 1, 1, 0));
        assertEquals(List.of(), g.scales, "no transform at 100%");
        assertEquals(1, g.fills);

        scale.set(80);
        scale.draw(g, () -> assertEquals(1, g.scaleDepth));
        assertEquals(List.of(0.8F), g.scales);
        assertTrue(g.balanced());
    }

    @Test
    void jsonRoundTripsEveryStep() {
        for (int step : UiScale.steps()) {
            assertEquals(step, UiScale.fromJson(UiScale.toJson(step)));
        }
        assertEquals("{\n  \"version\": 1,\n  \"uiSize\": 80\n}\n", UiScale.toJson(80));
    }

    @Test
    void malformedOrOutOfRangeFilesFallBackToOneHundredPercent() {
        for (String json : List.of("", "{", "[]", "null", "80", "{\"version\": \"1\", \"uiSize\": 80}",
                "{\"uiSize\": 80}", "{\"version\": 1}", "{\"version\": 1, \"uiSize\": \"80\"}",
                "{\"version\": 1, \"uiSize\": null}", "{\"version\": 1, \"uiSize\": 49}",
                "{\"version\": 1, \"uiSize\": 151}", "{\"version\": 1, \"uiSize\": -80}",
                "{\"version\": 1, \"uiSize\": 1e9}")) {
            assertEquals(100, UiScale.fromJson(json), json);
        }
    }

    @Test
    void inRangeValuesSnapToTheNearestStep() {
        assertEquals(80, UiScale.fromJson("{\"version\": 1, \"uiSize\": 85}"), "a tie goes to the smaller step");
        assertEquals(75, UiScale.fromJson("{\"version\": 1, \"uiSize\": 77}"));
        assertEquals(125, UiScale.fromJson("{\"version\": 1, \"uiSize\": 118}"));
        assertEquals(50, UiScale.fromJson("{\"version\": 1, \"uiSize\": 50.4}"));
        assertEquals(150, UiScale.fromJson("{\"version\": 1, \"uiSize\": 150}"));
    }

    @Test
    void anotherVersionIsRefusedRatherThanRead() {
        assertThrows(IllegalArgumentException.class, () -> UiScale.fromJson("{\"version\": 2, \"uiSize\": 80}"));
        assertThrows(IllegalArgumentException.class, () -> UiScale.fromJson("{\"version\": 0}"));
    }

    @Test
    void grabZonesKeepAMinimumScreenSize() {
        UiScale scale = new UiScale();
        assertEquals(4, scale.reach(4));
        scale.set(50);
        assertEquals(8, scale.reach(4), "4 screen pixels are 8 UI units at 50%");
        scale.set(75);
        assertEquals(6, scale.reach(4));
        scale.set(150);
        assertEquals(4, scale.reach(4), "never fewer than the theme's units");
    }

    private static UiSizeStore store(Path path, List<String> problems) {
        return new UiSizeStore(new ConfigFile(path, new AtomicFileStore(), problems::add));
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void theStoreSavesOnceTheSizeSettles(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        List<String> problems = new ArrayList<>();
        UiSizeStore store = store(path, problems);
        assertEquals(100, store.load(), "no file yet");
        assertFalse(Files.exists(path), "loading doesn't create the file");

        // A held key: a step every 30 ms.
        for (int i = 0; i < 5; i++) {
            store.changed(90 - 10 * i, i * 30L);
            store.saveIfSettled(i * 30L);
        }
        store.saveIfSettled(120 + UiSizeStore.SETTLE_MS - 1);
        assertFalse(Files.exists(path), "nothing is written while the size keeps changing");
        store.saveIfSettled(120 + UiSizeStore.SETTLE_MS);
        assertEquals(50, UiScale.fromJson(read(path)));

        store.changed(70, 2_000);
        store.saveNow();
        assertEquals(70, store(path, problems).load(), "closing the editor saves at once");
        assertEquals(List.of(), problems);
    }

    @Test
    void anUnusableValueIsReplacedByTheNextChange(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        List<String> problems = new ArrayList<>();
        Files.writeString(path, "{\"version\": 1, \"uiSize\": 400}", StandardCharsets.UTF_8);
        UiSizeStore edited = store(path, problems);
        assertEquals(100, edited.load());
        edited.changed(90, 0);
        edited.saveNow();
        assertEquals(90, UiScale.fromJson(read(path)));

        Files.writeString(path, "not json", StandardCharsets.UTF_8);
        assertEquals(100, store(path, problems).load());
        assertEquals(List.of(), problems, "a broken file is quietly replaced later");
    }

    @Test
    void aFileFromANewerVersionIsReportedAndLeftAlone(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        String newer = "{\"version\": 2, \"uiSize\": 80, \"theme\": \"light\"}";
        Files.writeString(path, newer, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        UiSizeStore store = store(path, problems);
        assertEquals(100, store.load());
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("editor-ui.json was not used"), problems.get(0));
        store.changed(80, 0);
        store.saveNow();
        assertEquals(newer, read(path), "not overwritten");
    }
}
