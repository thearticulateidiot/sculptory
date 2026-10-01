package dev.sculptory.fabric.client.editor.presets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The preset file: its JSON form, and loading and saving it through {@link ConfigFile}. */
class PresetStoreTest {
    @TempDir
    Path dir;

    private final List<String> problems = new ArrayList<>();

    private Path file() {
        return dir.resolve(PresetStore.FILE_NAME);
    }

    private PresetStore store() {
        return new PresetStore(new ConfigFile(file(), new AtomicFileStore(PresetStore.MAX_BYTES), problems::add));
    }

    private static PresetBook sample() {
        Preset soft = new Preset("Soft hills", Map.of("radius", "12", "strength", "0.3", "falloff", "SMOOTH"));
        Preset forest = new Preset("Forest", new TreeMap<>(Map.of("seed", "1", "density", "12.5")),
                new TreeMap<>(Map.of("mix", "asset:10:" + "ab".repeat(32) + ":trees/oak.schem")));
        return PresetBook.EMPTY
                .with(ToolId.RAISE, new ToolPresets(List.of(soft, new Preset("Big \"quoted\" <name>", Map.of())), "Soft hills"))
                .with(ToolId.SCATTER, new ToolPresets(List.of(forest), ""));
    }

    @Test
    void theBookRoundTripsThroughJson() {
        PresetBook book = sample();
        PresetBook.Parsed parsed = PresetBook.fromJson(book.toJson());
        assertEquals(book, parsed.book());
        assertEquals(0, parsed.skipped());
        assertTrue(book.toJson().contains("\"version\": 1"));
        assertTrue(book.toJson().contains("\"selected\": \"Soft hills\""));
        assertFalse(book.toJson().contains("\\u003c"), "names are written as typed, not HTML-escaped");
    }

    @Test
    void theStoreRoundTripsAndOnlyWritesChanges() throws IOException {
        PresetStore store = store();
        PresetStore.Loaded missing = store.load();
        assertEquals(PresetBook.EMPTY, missing.book());
        assertTrue(missing.writable(), "a missing file is created by the first save");
        assertFalse(Files.exists(file()), "loading doesn't create the file");
        assertTrue(store.save(sample()));
        String written = Files.readString(file());

        PresetStore.Loaded loaded = store().load();
        assertEquals(sample(), loaded.book());
        assertTrue(loaded.writable());
        assertEquals(0, loaded.skipped());
        assertEquals(written, Files.readString(file()));
        assertEquals(List.of(), problems);
    }

    @Test
    void anotherVersionIsLeftUnchangedAndReadOnly() throws IOException {
        String newer = "{\"version\": 2, \"tools\": {\"raise\": {\"presets\": []}}}";
        Files.writeString(file(), newer);
        PresetStore store = store();
        PresetStore.Loaded loaded = store.load();
        assertEquals(PresetBook.EMPTY, loaded.book());
        assertFalse(loaded.writable());
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("unsupported version"), problems.get(0));
        assertTrue(problems.get(0).contains("left unchanged"), problems.get(0));
        assertFalse(store.writable());
        assertFalse(store.save(sample()), "a read-only file is never written");
        assertEquals(newer, Files.readString(file()));
    }

    @Test
    void aFileThatDoesNotParseIsLeftUnchanged() throws IOException {
        for (String broken : List.of("{\"version\": 1, \"tools\": {", "[1, 2]", "", "{\"version\": 1}",
                "{\"version\": \"1\", \"tools\": {}}", "{\"tools\": {}}")) {
            problems.clear();
            Files.writeString(file(), broken);
            PresetStore store = store();
            assertFalse(store.load().writable(), broken);
            assertEquals(1, problems.size(), broken);
            store.save(sample());
            assertEquals(broken, Files.readString(file()), broken);
        }
    }

    @Test
    void malformedEntriesAreSkippedAndTheRestLoads() throws IOException {
        String json = """
                {
                  "version": 1,
                  "tools": {
                    "raise": {
                      "selected": "Good",
                      "presets": [
                        {"name": "Good", "values": {"radius": "12", "strength": 0.3, "invert": true}},
                        {"name": "good", "values": {}},
                        {"name": "", "values": {}},
                        {"name": "  padded  ", "values": {}},
                        {"name": "Tab\\there", "values": {}},
                        {"name": "%s", "values": {}},
                        {"name": "Nested", "values": {"radius": {"value": 3}}},
                        {"name": "No values"},
                        {"name": "Bad extra", "values": {}, "extra": ["mix"]},
                        {"name": 7, "values": {}},
                        "not an object"
                      ]
                    },
                    "Not A Tool!": {"presets": []},
                    "lower": ["not", "an", "object"],
                    "sculpt": {"selected": "Keep", "presets": [{"name": "Keep", "values": {"size": "3"}}]}
                  },
                  "comment": "unknown fields are ignored"
                }
                """.formatted("x".repeat(49));
        Files.writeString(file(), json);
        PresetStore.Loaded loaded = store().load();
        assertTrue(loaded.writable());
        assertEquals(12, loaded.skipped());
        ToolPresets raise = loaded.book().tool(ToolId.RAISE);
        assertEquals(List.of("Good"), raise.names());
        assertEquals("Good", raise.selected());
        assertEquals(Map.of("radius", "12", "strength", "0.3", "invert", "true"), raise.presets().get(0).values(),
                "numbers and booleans typed by hand are read as text");
        ToolPresets sculpt = loaded.book().tool(new ToolId("sculpt"));
        assertEquals(List.of("Keep"), sculpt.names(), "a tool this build doesn't have is kept for the next save");
        assertEquals(List.of(), problems);

        String written = loaded.book().toJson();
        for (String kept : List.of("\"good\"", "\"  padded  \"", "\"Nested\"", "\"No values\"", "\"Bad extra\"",
                "\"not an object\"", "\"Not A Tool!\"", "\"lower\"", "\"sculpt\"", "\"comment\"", "x".repeat(49))) {
            assertTrue(written.contains(kept), "written back: " + kept);
        }
        PresetBook.Parsed again = PresetBook.fromJson(written);
        assertEquals(loaded.book(), again.book(), "reading what was written gives the same presets");
        assertEquals(12, again.skipped(), "and the same entries still can't be read");
    }

    @Test
    void fieldsThisVersionDoesNotKnowSurviveASave() {
        String json = """
                {"version": 1, "future": {"a": 1}, "tools": {"raise": {"pinned": true, "selected": "", "presets": [
                  {"name": "Tagged", "values": {"radius": "4"}, "icon": "minecraft:stone", "tags": ["hills"]}
                ]}}}
                """;
        PresetBook book = PresetBook.fromJson(json).book();
        Preset tagged = book.tool(ToolId.RAISE).find("Tagged").orElseThrow();
        assertEquals("\"minecraft:stone\"", tagged.other().get("icon"));
        PresetBook edited = book.with(ToolId.RAISE, book.tool(ToolId.RAISE)
                .with(new Preset("Tagged", Map.of("radius", "9")).keepingOther(tagged)).select("Tagged"));
        PresetBook reread = PresetBook.fromJson(edited.toJson()).book();
        Preset saved = reread.tool(ToolId.RAISE).find("Tagged").orElseThrow();
        assertEquals("9", saved.values().get("radius"));
        assertEquals("[\"hills\"]", saved.other().get("tags"), "an overwrite keeps the entry's other fields");
        assertEquals("true", reread.tool(ToolId.RAISE).other().get("pinned"));
        assertEquals("{\"a\":1}", reread.other().get("future"));
    }

    @Test
    void atMost100PresetsPerToolAreRead() throws IOException {
        StringBuilder entries = new StringBuilder();
        for (int i = 0; i < 105; i++) {
            if (i > 0) entries.append(',');
            entries.append("{\"name\": \"p").append(i).append("\", \"values\": {}}");
        }
        Files.writeString(file(), "{\"version\": 1, \"tools\": {\"paint\": {\"selected\": \"p104\", \"presets\": ["
                + entries + "]}}}");
        PresetStore.Loaded loaded = store().load();
        ToolPresets paint = loaded.book().tool(ToolId.PAINT);
        assertEquals(PresetNames.MAX_PER_TOOL, paint.presets().size());
        assertEquals(5, loaded.skipped());
        assertTrue(paint.find("p0").isPresent());
        assertTrue(paint.find("p104").isEmpty(), "the ones past the limit are skipped, in file order");
        assertEquals("", paint.selected(), "a selection that was skipped falls back to Default");
        assertTrue(loaded.book().toJson().contains("\"p104\""), "and they stay in the file");
    }

    @Test
    void aFullFileFitsTheStore() {
        List<Preset> presets = new ArrayList<>();
        StringBuilder mix = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            if (i > 0) mix.append(';');
            mix.append("1000:").append(String.format("%064x", i)).append(":some/folder/variant_").append(i).append(".schem");
        }
        for (int i = 0; i < PresetNames.MAX_PER_TOOL; i++) {
            Map<String, String> values = new TreeMap<>();
            for (int k = 0; k < 20; k++) values.put("setting.number_" + k, "minecraft:oak_stairs[facing=east,half=top]");
            presets.add(new Preset("x".repeat(40) + i, new TreeMap<>(values), new TreeMap<>(Map.of("mix", mix.toString()))));
        }
        PresetBook book = PresetBook.EMPTY;
        for (String tool : List.of("select", "raise", "lower", "smooth", "flatten", "paint", "palette", "place", "scatter")) {
            book = book.with(new ToolId(tool), new ToolPresets(presets, ""));
        }
        int bytes = book.toJson().getBytes(StandardCharsets.UTF_8).length;
        assertTrue(bytes < PresetStore.MAX_BYTES, "a worst-case file is " + bytes + " bytes");
        assertTrue(bytes > AtomicFileStore.DEFAULT_MAX_BYTES, "which is why presets have their own store");
    }

    @Test
    void parsingRejectsWhatIsNotAPresetFile() {
        assertThrows(IllegalArgumentException.class, () -> PresetBook.fromJson("{\"version\": 1.5, \"tools\": {}}"));
        assertThrows(IllegalArgumentException.class, () -> PresetBook.fromJson("{\"version\": 1, \"tools\": []}"));
        assertEquals(PresetBook.EMPTY, PresetBook.fromJson("{\"version\": 1, \"tools\": {}}").book());
    }
}
