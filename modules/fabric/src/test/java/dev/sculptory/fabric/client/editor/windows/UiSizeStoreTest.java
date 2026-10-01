package dev.sculptory.fabric.client.editor.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.ui.UiOpacity;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The quick start card's "seen" flag and the opacity (View > Opacity…) in {@code editor-ui.json}, next to the UI size. */
class UiSizeStoreTest {
    private final List<String> problems = new ArrayList<>();

    private UiSizeStore store(Path path) {
        return new UiSizeStore(new ConfigFile(path, new AtomicFileStore(), problems::add));
    }

    private static String read(Path path) throws Exception {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void theFlagIsUnsetUntilDismissedThenSavedAtOnceWithTheSize(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        UiSizeStore store = store(path);
        assertEquals(100, store.load());
        assertFalse(store.quickStartSeen(), "no file: the card shows");

        store.changed(75, 0); // still settling
        store.markQuickStartSeen();
        assertTrue(store.quickStartSeen());
        assertEquals(75, UiScale.fromJson(read(path)), "the settling size is written with the flag");
        assertTrue(read(path).contains("\"quickStartSeen\": true"), read(path));

        UiSizeStore reopened = store(path);
        assertEquals(75, reopened.load());
        assertTrue(reopened.quickStartSeen());
        reopened.changed(90, 0);
        reopened.saveNow();
        UiSizeStore third = store(path);
        assertEquals(90, third.load());
        assertTrue(third.quickStartSeen(), "a size change keeps the flag");
        assertEquals(List.of(), problems);
    }

    @Test
    void anOldFileWithoutTheFlagShowsTheCardAndKeepsItsSize(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        Files.writeString(path, UiScale.toJson(80), StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(80, store.load());
        assertFalse(store.quickStartSeen());
        store.changed(80, 0);
        store.saveNow();
        assertEquals(UiScale.toJson(80), read(path), "unchanged format until the card is dismissed");
    }

    @Test
    void aMalformedFlagReadsAsUnseenAndIsReplacedByTheNextSave(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        Files.writeString(path, "{\"version\": 1, \"uiSize\": 60, \"quickStartSeen\": \"yes\"}", StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(60, store.load());
        assertFalse(store.quickStartSeen(), "only a JSON true counts");
        store.markQuickStartSeen();
        UiSizeStore again = store(path);
        assertEquals(60, again.load());
        assertTrue(again.quickStartSeen());
        assertEquals(List.of(), problems);
    }

    @Test
    void aFileOfAnotherVersionIsLeftAloneAndTheCardCountsAsSeen(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        String newer = "{\"version\": 2, \"uiSize\": 60}";
        Files.writeString(path, newer, StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(100, store.load());
        assertTrue(store.quickStartSeen(), "it could not be saved, so it doesn't show every time");
        store.markQuickStartSeen();
        store.changed(90, 0);
        store.saveNow();
        assertEquals(newer, read(path));
        assertEquals(1, problems.size(), problems.toString());
    }

    // ---- Opacity (View > Opacity…) ----

    private static final UiOpacity.Values CHANGED = new UiOpacity.Values(60, true, 40);

    @Test
    void theOpacityRoundTripsWithTheSizeAndTheFlag(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        UiSizeStore store = store(path);
        store.load();
        assertEquals(UiOpacity.Values.DEFAULT, store.opacity(), "no file: everything opaque");
        store.changed(80, 0);
        store.markQuickStartSeen();
        store.opacityChanged(CHANGED, 100);
        store.saveNow();
        assertEquals("{\n  \"version\": 1,\n  \"uiSize\": 80,\n  \"quickStartSeen\": true,\n  \"panelOpacity\": 60,\n"
                + "  \"fadeWhenNotHovered\": true,\n  \"toolOutlineOpacity\": 40\n}\n", read(path));

        UiSizeStore reopened = store(path);
        assertEquals(80, reopened.load());
        assertTrue(reopened.quickStartSeen());
        assertEquals(CHANGED, reopened.opacity());
        reopened.opacityChanged(UiOpacity.Values.DEFAULT, 0);
        reopened.saveNow();
        assertEquals("{\n  \"version\": 1,\n  \"uiSize\": 80,\n  \"quickStartSeen\": true\n}\n", read(path),
                "values back at their defaults are left out again");
        assertEquals(List.of(), problems);
    }

    @Test
    void aBuildFromBeforeStillReadsTheSizeAndTheFlag(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        UiSizeStore store = store(path);
        store.load();
        store.changed(60, 0);
        store.opacityChanged(CHANGED, 0);
        store.markQuickStartSeen();
        // What main's reader does with the file: UiScale.fromJson, and the flag as a JSON true.
        String text = read(path);
        assertEquals(60, UiScale.fromJson(text), "the same version, so an older build keeps the size");
        assertTrue(JsonParser.parseString(text).getAsJsonObject().get("quickStartSeen").getAsBoolean());
    }

    @Test
    void anOldFileGetsTheDefaultOpacity(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        Files.writeString(path, "{\"version\": 1, \"uiSize\": 75, \"quickStartSeen\": true}", StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(75, store.load());
        assertEquals(UiOpacity.Values.DEFAULT, store.opacity());
        assertTrue(store.quickStartSeen());
        assertEquals(List.of(), problems);
    }

    @Test
    void opacitiesOutOfRangeAreClampedAndMalformedOnesAreDefaults(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        assertEquals(new UiOpacity.Values(20, false, 100), loaded(path,
                "{\"version\": 1, \"uiSize\": 100, \"panelOpacity\": 5, \"toolOutlineOpacity\": 250}"));
        assertEquals(new UiOpacity.Values(100, false, 10), loaded(path,
                "{\"version\": 1, \"uiSize\": 100, \"panelOpacity\": 1e400, \"toolOutlineOpacity\": -3}"));
        assertEquals(new UiOpacity.Values(56, false, 10), loaded(path,
                "{\"version\": 1, \"uiSize\": 100, \"panelOpacity\": 55.6, \"toolOutlineOpacity\": 0}"),
                "rounded to a whole percent; 0 would hide the outlines, so it is the lowest setting");
        assertEquals(UiOpacity.Values.DEFAULT, loaded(path, "{\"version\": 1, \"uiSize\": 100, \"panelOpacity\": \"60\","
                + " \"fadeWhenNotHovered\": \"yes\", \"toolOutlineOpacity\": {\"value\": 40}}"));
        assertEquals(UiOpacity.Values.DEFAULT, loaded(path, "{\"version\": 1, \"uiSize\": 100, \"panelOpacity\": null,"
                + " \"fadeWhenNotHovered\": 1, \"toolOutlineOpacity\": [40]}"));
        assertEquals(UiOpacity.Values.DEFAULT, loaded(path, "{\"version\": 1, \"uiSize\": 100, \"panelOpacity\": NaN}"));
        assertEquals(UiOpacity.Values.DEFAULT, loaded(path, "not json"));
        assertEquals(UiOpacity.Values.DEFAULT, loaded(path, "[60, true, 40]"));
        assertEquals(List.of(), problems);

        // The next save replaces what couldn't be used.
        Files.writeString(path, "{\"version\": 1, \"uiSize\": 90, \"panelOpacity\": \"half\", \"toolOutlineOpacity\": 3}",
                StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(90, store.load());
        store.opacityChanged(store.opacity().withPanels(50), 0);
        store.saveNow();
        assertEquals(new UiOpacity.Values(50, false, 10), loaded(path, read(path)));
    }

    @Test
    void theOpacityOfANewerFileIsNotUsedAndTheFileIsLeftAlone(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        String newer = "{\"version\": 2, \"uiSize\": 60, \"panelOpacity\": 40, \"toolOutlineOpacity\": 30}";
        Files.writeString(path, newer, StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(100, store.load());
        assertEquals(UiOpacity.Values.DEFAULT, store.opacity(), "another version's fields may mean something else");
        store.opacityChanged(CHANGED, 0);
        store.saveIfSettled(UiSizeStore.SETTLE_MS);
        store.saveNow();
        assertEquals(newer, read(path), "kept as it is");
        assertEquals(CHANGED, store.opacity(), "the change still lasts while the game runs");
        assertEquals(1, problems.size(), problems.toString());
    }

    @Test
    void fieldsOfANewerBuildOfTheSameVersionAreKept(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        Files.writeString(path, "{\"version\": 1, \"uiSize\": 70, \"panelOpacity\": 40, \"theme\": \"light\","
                + " \"extra\": {\"a\": [1, 2]}}", StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(70, store.load());
        store.opacityChanged(store.opacity().withToolOutlines(30), 0);
        store.saveNow();
        JsonObject saved = JsonParser.parseString(read(path)).getAsJsonObject();
        assertEquals(List.of("version", "uiSize", "panelOpacity", "toolOutlineOpacity", "theme", "extra"),
                List.copyOf(saved.keySet()), "this build's fields first, then the others as they were");
        assertEquals("light", saved.get("theme").getAsString());
        assertEquals("{\"a\":[1,2]}", saved.get("extra").toString());
        assertEquals(new UiOpacity.Values(40, false, 30), loaded(path, read(path)));
    }

    @Test
    void opacityChangesAreSavedOnceTheySettle(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        UiSizeStore store = store(path);
        store.load();
        // A dragged slider: a change every 20 ms.
        for (int i = 0; i < 10; i++) {
            store.opacityChanged(new UiOpacity.Values(100 - 5 * i, false, 100), i * 20L);
            store.saveIfSettled(i * 20L);
        }
        store.saveIfSettled(180 + UiSizeStore.SETTLE_MS - 1);
        assertFalse(Files.exists(path), "nothing is written while the slider moves");
        store.saveIfSettled(180 + UiSizeStore.SETTLE_MS);
        assertEquals(new UiOpacity.Values(55, false, 100), loaded(path, read(path)));
        assertEquals(List.of(), problems);
    }

    @Test
    void aSizeChangeAndALaterOpacityChangeSettleTogether(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        UiSizeStore store = store(path);
        store.load();
        store.changed(75, 0);
        store.opacityChanged(CHANGED, 500);
        store.saveIfSettled(UiSizeStore.SETTLE_MS);
        assertFalse(Files.exists(path), "the later change restarts the wait");
        store.saveIfSettled(500 + UiSizeStore.SETTLE_MS);
        assertEquals(75, UiScale.fromJson(read(path)));
        assertEquals(CHANGED, loaded(path, read(path)));
    }

    @Test
    void dismissingTheCardWritesASettlingOpacityAtOnce(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        UiSizeStore store = store(path);
        store.load();
        store.opacityChanged(CHANGED, 0);
        store.markQuickStartSeen();
        String written = read(path);
        assertTrue(written.contains("\"quickStartSeen\": true"), written);
        assertEquals(CHANGED, loaded(path, written));
    }

    @Test
    void nothingChangedMeansNothingIsWrittenOverAFileAsItWas(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        String edited = "{\"version\":1,\"uiSize\":90,\"panelOpacity\":400,\"fadeWhenNotHovered\":\"on\",\"x\":[1]}";
        Files.writeString(path, edited, StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(90, store.load());
        store.saveIfSettled(10 * UiSizeStore.SETTLE_MS);
        store.saveNow();
        assertEquals(edited, read(path), "a hand edit stays exactly as it is until something changes");
        assertEquals(List.of(), problems);
    }

    @Test
    void aFileThatIsntJsonIsReplacedByAVersionOneFile(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        Files.writeString(path, "not json {", StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(100, store.load());
        store.opacityChanged(CHANGED, 0);
        store.saveNow();
        String written = read(path);
        assertEquals(100, UiScale.fromJson(written), "a valid version-1 file");
        assertEquals(CHANGED, loaded(path, written));
        assertEquals(List.of(), problems);
    }

    @Test
    void aFileChangedOutsideTheGameIsNotOverwrittenAndReportedOnce(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        Files.writeString(path, UiScale.toJson(80), StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(80, store.load());
        String outside = "{\"version\": 1, \"uiSize\": 60, \"panelOpacity\": 30}";
        Files.writeString(path, outside, StandardCharsets.UTF_8);

        store.opacityChanged(CHANGED, 0);
        store.saveNow();
        assertEquals(outside, read(path));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("changed outside"), problems.get(0));
        store.changed(90, 0);
        store.saveNow();
        store.markQuickStartSeen();
        assertEquals(outside, read(path));
        assertEquals(1, problems.size(), "reported once, not at every save");
        assertEquals(new UiOpacity.Values(30, false, 100), loaded(path, outside), "the next start reads the edit");
    }

    @Test
    void anUnreadableFileGivesTheDefaultsAndIsNeverWritten(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        Files.createDirectory(path); // something that can't be read as a file
        UiSizeStore store = store(path);
        assertEquals(100, store.load());
        assertEquals(UiOpacity.Values.DEFAULT, store.opacity());
        assertEquals(1, problems.size(), problems.toString());
        store.opacityChanged(CHANGED, 0);
        store.saveNow();
        store.markQuickStartSeen();
        assertTrue(Files.isDirectory(path), "left as it is");
        assertEquals(1, problems.size(), "a save that can't know what it would replace doesn't try");
    }

    @Test
    void aFieldNestedTooDeepIsDroppedInsteadOfOverflowingTheStack(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("editor-ui.json");
        int depth = 20_000;
        String deep = "[".repeat(depth) + "]".repeat(depth);
        Files.writeString(path, "{\"version\": 1, \"uiSize\": 100, \"panelOpacity\": 50, \"deep\": " + deep
                + ", \"shallow\": [[1]]}", StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        assertEquals(100, store.load(), "read without an exception (or taken as malformed by a stricter Gson)");
        store.opacityChanged(store.opacity().withToolOutlines(40), 0);
        store.saveNow();
        JsonObject saved = JsonParser.parseString(read(path)).getAsJsonObject();
        assertFalse(saved.has("deep"));
        assertEquals(40, saved.get("toolOutlineOpacity").getAsInt());

        // 100 deep: every Gson reads it (a stricter one stops at 255), and the field is dropped, the rest kept.
        String hundred = "[".repeat(100) + "]".repeat(100);
        Files.writeString(path, "{\"version\": 1, \"uiSize\": 80, \"panelOpacity\": 50, \"deep\": " + hundred
                + ", \"shallow\": [[1]]}", StandardCharsets.UTF_8);
        UiSizeStore parsed = store(path);
        assertEquals(80, parsed.load());
        assertEquals(50, parsed.opacity().panels());
        parsed.opacityChanged(parsed.opacity().withToolOutlines(40), 0);
        parsed.saveNow();
        JsonObject kept = JsonParser.parseString(read(path)).getAsJsonObject();
        assertEquals(List.of("version", "uiSize", "panelOpacity", "toolOutlineOpacity", "shallow"),
                List.copyOf(kept.keySet()));

        assertTrue(UiSizeStore.deeperThan(JsonParser.parseString(hundred), UiSizeStore.MAX_KEPT_DEPTH));
        assertFalse(UiSizeStore.deeperThan(JsonParser.parseString("{\"a\": [[{\"b\": [1, [2]]}]]}"),
                UiSizeStore.MAX_KEPT_DEPTH));
    }

    /** The opacity a store reads from a file holding {@code json}. */
    private UiOpacity.Values loaded(Path path, String json) throws Exception {
        Files.writeString(path, json, StandardCharsets.UTF_8);
        UiSizeStore store = store(path);
        store.load();
        return store.opacity();
    }
}
