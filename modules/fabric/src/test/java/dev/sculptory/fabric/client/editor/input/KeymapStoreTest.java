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
