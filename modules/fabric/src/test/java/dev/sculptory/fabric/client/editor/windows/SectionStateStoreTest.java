package dev.sculptory.fabric.client.editor.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SectionStateStoreTest {
    private static final String MASK = "sculptory.setting.brush.mask";
    private static final String SYMMETRY = "sculptory.setting.brush.symmetry_section";

    @TempDir
    Path dir;

    private final List<String> problems = new ArrayList<>();

    private SectionStateStore store() {
        SectionStateStore store = SectionStateStore.of(new ConfigFile(dir.resolve(SectionStateStore.FILE_NAME),
                new AtomicFileStore(), problems::add));
        store.load();
        return store;
    }

    @Test
    void statesRoundTripThroughTheFilePerTool() throws IOException {
        SectionStateStore first = store();
        assertEquals(Optional.empty(), first.expanded(ToolId.RAISE, MASK), "nothing toggled yet: the default");
        first.set(ToolId.RAISE, MASK, true);
        first.set(ToolId.RAISE, SYMMETRY, false);
        first.set(ToolId.PAINT, MASK, false);
        assertTrue(Files.exists(dir.resolve(SectionStateStore.FILE_NAME)), "saved at once");

        SectionStateStore restarted = store();
        assertEquals(Optional.of(true), restarted.expanded(ToolId.RAISE, MASK));
        assertEquals(Optional.of(false), restarted.expanded(ToolId.RAISE, SYMMETRY));
        assertEquals(Optional.of(false), restarted.expanded(ToolId.PAINT, MASK), "each tool has its own");
        assertEquals(Optional.empty(), restarted.expanded(ToolId.LOWER, MASK));
        assertEquals(List.of(), problems);
    }

    @Test
    void theFormSeesOneToolsSections() {
        SectionStateStore store = store();
        SettingsForm.SectionMemory raise = store.memoryFor(ToolId.RAISE);
        raise.toggled(MASK, true);
        assertEquals(Optional.of(true), raise.expanded(MASK));
        assertEquals(Optional.empty(), store.memoryFor(ToolId.SMOOTH).expanded(MASK));
    }

    @Test
    void theFileFormatIsSmallAndReadable() {
        String json = SectionStateStore.toJson(Map.of("raise", Map.of(MASK, true)));
        assertEquals("{\n  \"version\": 1,\n  \"tools\": {\n    \"raise\": {\n      \"" + MASK
                + "\": true\n    }\n  }\n}\n", json);
    }

    @Test
    void aMalformedFileGivesTheDefaultsAndIsReplacedByTheNextChange() throws IOException {
        Path file = dir.resolve(SectionStateStore.FILE_NAME);
        Files.writeString(file, "{ not json");
        SectionStateStore store = store();
        assertEquals(Optional.empty(), store.expanded(ToolId.RAISE, MASK));
        store.set(ToolId.RAISE, MASK, true);
        assertTrue(Files.readString(file).contains("\"version\": 1"));
    }

    @Test
    void entriesThatAreNotStatesAreSkipped() {
        Map<String, Map<String, Boolean>> states = SectionStateStore.fromJson("{\"version\": 1, \"tools\": {"
                + "\"raise\": {\"a\": true, \"b\": 3}, \"Not A Tool\": {\"a\": true}, \"paint\": 5}}");
        assertEquals(Map.of("raise", Map.of("a", true)), states);
    }

    @Test
    void titleKeysSavedBeforeTheRenameAreReadUnderTheNewName() {
        Map<String, Map<String, Boolean>> states = SectionStateStore.fromJson("{\"version\": 1, \"tools\": {"
                + "\"raise\": {\"buildersuite.setting.brush.mask\": true},"
                + " \"paint\": {\"buildersuite.setting.brush.mask\": true, \"sculptory.setting.brush.mask\": false}}}");
        assertEquals(Map.of("raise", Map.of("sculptory.setting.brush.mask", true),
                "paint", Map.of("sculptory.setting.brush.mask", false)), states, "the new key wins over the old one");
        assertFalse(SectionStateStore.toJson(states).contains("buildersuite"), "saved under the new name only");
    }

    @Test
    void aFileFromANewerVersionIsLeftUnchanged() throws IOException {
        Path file = dir.resolve(SectionStateStore.FILE_NAME);
        String newer = "{\"version\": 2, \"tools\": {\"raise\": {\"" + MASK + "\": true}}}";
        Files.writeString(file, newer);
        SectionStateStore store = store();
        assertEquals(Optional.empty(), store.expanded(ToolId.RAISE, MASK));
        assertFalse(problems.isEmpty(), "reported");
        store.set(ToolId.RAISE, MASK, false);
        assertEquals(Optional.of(false), store.expanded(ToolId.RAISE, MASK), "kept for this session");
        assertEquals(newer, Files.readString(file), "the newer file is not overwritten");
    }

    @Test
    void inMemoryStatesLastTheSession() {
        SectionStateStore store = SectionStateStore.inMemory();
        store.load();
        store.set(ToolId.SELECT, "x", true);
        assertEquals(Optional.of(true), store.expanded(ToolId.SELECT, "x"));
    }
}
