package dev.sculptory.fabric.client.editor.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Keys rebound in the Keys window go through {@link KeymapStore#save} and come back from the same file format. */
class KeymapStoreTest {
    private final List<String> problems = new ArrayList<>();

    private KeymapStore store(Path file) {
        return new KeymapStore(new ConfigFile(file, new AtomicFileStore(), problems::add));
    }

    private static List<KeyChord> chords(String... texts) {
        List<KeyChord> chords = new ArrayList<>();
        for (String text : texts) {
            chords.add(KeyChord.parse(text));
        }
        return chords;
    }

    @Test
    void reboundChordsRoundTripThroughTheFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("editor-keys.json");
        KeymapStore store = store(file);
        EditorKeymap keymap = store.load();
        keymap.bind(KeyAction.UNDO, chords("ctrl+u", "f5"));
        keymap.bind(KeyAction.EYEDROPPER, chords("alt+mouse.middle"));
        keymap.bind(KeyAction.FLY_SPEED, chords("ctrl+scroll"));
        keymap.bind(KeyAction.LIBRARY, List.of());
        assertTrue(store.save(keymap));
        assertEquals(keymap.toJson(), Files.readString(file, StandardCharsets.UTF_8));

        EditorKeymap loaded = store(file).load();
        assertEquals(chords("ctrl+u", "f5"), loaded.chords(KeyAction.UNDO));
        assertEquals(chords("alt+mouse.middle"), loaded.chords(KeyAction.EYEDROPPER));
        assertEquals(chords("ctrl+scroll"), loaded.chords(KeyAction.FLY_SPEED));
        assertEquals(List.of(), loaded.chords(KeyAction.LIBRARY), "an unbound action stays unbound");
        assertEquals(KeyAction.REDO.defaultChords(), loaded.chords(KeyAction.REDO));
        assertEquals(keymap.toJson(), loaded.toJson());
        assertEquals(List.of(), problems);
    }

    @Test
    void theFileFormatIsUnchangedSoHandEditsStillWork(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("editor-keys.json");
        KeymapStore store = store(file);
        EditorKeymap keymap = store.load();
        keymap.bind(KeyAction.UNDO, chords("ctrl+u", "f5"));
        assertTrue(store.save(keymap));

        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(Set.of("version", "bindings"), root.keySet());
        assertEquals(EditorKeymap.VERSION, root.get("version").getAsInt());
        JsonObject bindings = root.getAsJsonObject("bindings");
        Set<String> ids = new TreeSet<>();
        for (KeyAction action : KeyAction.values()) {
            ids.add(action.id());
        }
        assertEquals(ids, new TreeSet<>(bindings.keySet()), "one entry per action, by its id");
        for (Map.Entry<String, JsonElement> entry : bindings.entrySet()) {
            assertTrue(entry.getValue().isJsonArray(), entry.getKey() + " is a list");
            for (JsonElement chord : entry.getValue().getAsJsonArray()) {
                assertTrue(chord.isJsonPrimitive() && chord.getAsJsonPrimitive().isString(), entry.getKey());
                KeyChord.parse(chord.getAsString());
            }
        }
        assertEquals("[\"ctrl+u\",\"f5\"]", bindings.get("undo").toString(), "the documented chord text");

        // A hand edit of the saved file is read as before.
        String edited = Files.readString(file, StandardCharsets.UTF_8).replace("\"f5\"", "\"f9\"");
        Files.writeString(file, edited, StandardCharsets.UTF_8);
        assertEquals(chords("ctrl+u", "f9"), store(file).load().chords(KeyAction.UNDO));
        assertEquals(List.of(), problems);
    }

    @Test
    void actionsTheFileDoesNotNameKeepTheirDefaultsAcrossASave(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("editor-keys.json");
        Files.writeString(file, "{\"version\": 1, \"bindings\": {\"help\": [\"f2\"], \"no_such_action\": [\"ctrl+q\"]}}",
                StandardCharsets.UTF_8);
        KeymapStore store = store(file);
        EditorKeymap keymap = store.load();
        assertEquals(chords("f2"), keymap.chords(KeyAction.HELP));
        assertEquals(KeyAction.UNDO.defaultChords(), keymap.chords(KeyAction.UNDO), "not named: the default");

        keymap.bind(KeyAction.UNDO, chords("ctrl+u"));
        assertTrue(store.save(keymap));
        EditorKeymap loaded = store(file).load();
        assertEquals(chords("f2"), loaded.chords(KeyAction.HELP), "the hand-made binding survives the save");
        assertEquals(chords("ctrl+u"), loaded.chords(KeyAction.UNDO));
        assertEquals(KeyAction.COPY.defaultChords(), loaded.chords(KeyAction.COPY));
        assertEquals(List.of(), problems);
    }

    /**
     * What another build wrote survives a save exactly: actions this build doesn't have, fields it doesn't know, chords it
     * can't read (kept after the action's readable ones), and an entry none of which it can read (kept instead of the
     * defaults it uses). A rebind replaces what the player rebound; a reset replaces every action's but keeps the
     * actions this build doesn't have.
     */
    @Test
    void whatAnotherBuildWroteSurvivesASave(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("editor-keys.json");
        String written = "{\"version\": 1, \"profile\": {\"name\": \"left hand\", \"layers\": [1, 2]},"
                + " \"bindings\": {\"undo\": [\"ctrl+u\", \"hyper+u\", 7], \"redo\": [\"hyper+r\"],"
                + " \"copy\": \"ctrl+c\", \"teleport_to_cursor\": [\"ctrl+t\"], \"tool_99\": [\"f9\", {\"chord\": \"x\"}]},"
                + " \"theme\": \"dark\"}";
        Files.writeString(file, written, StandardCharsets.UTF_8);
        KeymapStore store = store(file);
        EditorKeymap keymap = store.load();
        assertEquals(chords("ctrl+u"), keymap.chords(KeyAction.UNDO), "the readable chords are bound");
        assertEquals(KeyAction.REDO.defaultChords(), keymap.chords(KeyAction.REDO), "none readable: the defaults");
        assertEquals(KeyAction.COPY.defaultChords(), keymap.chords(KeyAction.COPY), "not a list: the defaults");

        keymap.bind(KeyAction.LIBRARY, List.of());
        assertTrue(store.save(keymap));
        JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        JsonObject original = JsonParser.parseString(written).getAsJsonObject();
        JsonObject bindings = saved.getAsJsonObject("bindings");
        JsonObject originalBindings = original.getAsJsonObject("bindings");
        assertEquals(JsonParser.parseString("[\"ctrl+u\", \"hyper+u\", 7]"), bindings.get("undo"));
        for (String kept : List.of("redo", "copy", "teleport_to_cursor", "tool_99")) {
            assertEquals(originalBindings.get(kept), bindings.get(kept), kept + " kept exactly");
        }
        assertEquals(original.get("profile"), saved.get("profile"));
        assertEquals(original.get("theme"), saved.get("theme"));
        assertEquals(JsonParser.parseString("[]"), bindings.get("library"), "the player's own change is saved");

        EditorKeymap again = store(file).load();
        assertEquals(chords("ctrl+u"), again.chords(KeyAction.UNDO));
        assertEquals(saved, JsonParser.parseString(again.toJson()), "a second round trip changes nothing");

        again.bind(KeyAction.UNDO, chords("f5"));
        again.bind(KeyAction.REDO, chords("f6"));
        JsonObject rebound = JsonParser.parseString(again.toJson()).getAsJsonObject().getAsJsonObject("bindings");
        assertEquals(JsonParser.parseString("[\"f5\"]"), rebound.get("undo"), "rebinding replaces the unreadable chords");
        assertEquals(JsonParser.parseString("[\"f6\"]"), rebound.get("redo"));
        assertEquals(originalBindings.get("copy"), rebound.get("copy"), "an action not rebound is still kept");

        EditorKeymap copied = again.copy();
        copied.resetToDefaults();
        JsonObject reset = JsonParser.parseString(copied.toJson()).getAsJsonObject();
        assertEquals(JsonParser.parseString("[\"ctrl+c\"]"), reset.getAsJsonObject("bindings").get("copy"));
        assertEquals(originalBindings.get("tool_99"), reset.getAsJsonObject("bindings").get("tool_99"),
                "another build's actions are not this build's to reset");
        assertEquals(original.get("profile"), reset.get("profile"));
        assertEquals(List.of(), problems);
    }

    /** A file of another version is used for nothing and left byte for byte, whatever the player rebinds. */
    @Test
    void aNewerVersionsFileIsLeftByteForByte(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("editor-keys.json");
        byte[] newer = "{\"version\": 2, \"bindings\": {\"undo\": [\"ctrl+u\"]}, \"layers\": {}}\n"
                .getBytes(StandardCharsets.UTF_8);
        Files.write(file, newer);
        KeymapStore store = store(file);
        EditorKeymap keymap = store.load();
        assertEquals(KeyAction.UNDO.defaultChords(), keymap.chords(KeyAction.UNDO));
        keymap.bind(KeyAction.UNDO, chords("f5"));
        store.save(keymap);
        org.junit.jupiter.api.Assertions.assertArrayEquals(newer, Files.readAllBytes(file));
        assertEquals(1, problems.size(), problems.toString());
        assertTrue(problems.get(0).contains("left unchanged"), problems.get(0));
    }

    @Test
    void savingTheSameKeysAgainIsNotAWrite(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("editor-keys.json");
        KeymapStore store = store(file);
        EditorKeymap keymap = store.load();
        long stamp = Files.getLastModifiedTime(file).toMillis();
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(stamp - 60_000));
        assertTrue(store.save(keymap));
        assertEquals(stamp - 60_000, Files.getLastModifiedTime(file).toMillis(), "unchanged text is not written");
    }
}
