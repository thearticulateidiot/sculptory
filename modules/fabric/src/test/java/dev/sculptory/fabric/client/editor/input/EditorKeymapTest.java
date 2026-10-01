package dev.sculptory.fabric.client.editor.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

class EditorKeymapTest {
    private static final int CTRL = Modifiers.CONTROL;
    private static final int SHIFT = Modifiers.SHIFT;
    private static final int ALT = Modifiers.ALT;

    private static Optional<KeyAction> key(EditorKeymap keymap, int code, int modifiers) {
        return keymap.match(KeyChord.key(code, modifiers));
    }

    /** WASD, Space, Shift, Ctrl, T, /, E and B, as a default vanilla player has them. */
    private static List<EditorKeymap.ReservedKey> vanillaDefaults() {
        List<EditorKeymap.ReservedKey> keys = new ArrayList<>();
        int[] codes = {GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_SPACE,
                GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_LEFT_CONTROL, GLFW.GLFW_KEY_T, GLFW.GLFW_KEY_SLASH,
                GLFW.GLFW_KEY_E, GLFW.GLFW_KEY_B};
        for (int code : codes) {
            keys.add(new EditorKeymap.ReservedKey(KeyChord.Input.KEY, code, "key " + code));
        }
        return keys;
    }

    // ---- Defaults ----

    @Test
    void defaultsMatchTheArchitectureKeyTable() {
        EditorKeymap keymap = EditorKeymap.defaults();
        for (int slot = 1; slot <= 9; slot++) {
            assertEquals(Optional.of(KeyAction.toolSlot(slot)), key(keymap, GLFW.GLFW_KEY_0 + slot, 0));
        }
        assertEquals(Optional.of(KeyAction.toolSlot(10)), key(keymap, GLFW.GLFW_KEY_0, 0), "slot 10 (Shape) on 0");
        assertEquals(Optional.of(KeyAction.UNDO), key(keymap, GLFW.GLFW_KEY_Z, CTRL));
        assertEquals(Optional.of(KeyAction.REDO), key(keymap, GLFW.GLFW_KEY_Y, CTRL));
        assertEquals(Optional.of(KeyAction.REDO), key(keymap, GLFW.GLFW_KEY_Z, CTRL | SHIFT));
        assertEquals(Optional.of(KeyAction.COPY), key(keymap, GLFW.GLFW_KEY_C, CTRL));
        assertEquals(Optional.of(KeyAction.CUT), key(keymap, GLFW.GLFW_KEY_X, CTRL));
        assertEquals(Optional.of(KeyAction.PASTE), key(keymap, GLFW.GLFW_KEY_V, CTRL));
        assertEquals(Optional.of(KeyAction.ERASE_SELECTION), key(keymap, GLFW.GLFW_KEY_DELETE, 0));
        assertEquals(Optional.of(KeyAction.DESELECT), key(keymap, GLFW.GLFW_KEY_D, CTRL));
        assertEquals(Optional.of(KeyAction.COMMIT), key(keymap, GLFW.GLFW_KEY_ENTER, 0));
        assertEquals(Optional.of(KeyAction.ROTATE_CW), key(keymap, GLFW.GLFW_KEY_R, 0));
        assertEquals(Optional.of(KeyAction.ROTATE_CCW), key(keymap, GLFW.GLFW_KEY_R, SHIFT));
        assertEquals(Optional.of(KeyAction.FLIP_LEFT_RIGHT), key(keymap, GLFW.GLFW_KEY_F, 0));
        assertEquals(Optional.of(KeyAction.FLIP_FRONT_BACK), key(keymap, GLFW.GLFW_KEY_F, SHIFT));
        assertEquals(Optional.of(KeyAction.NUDGE_FORWARD), key(keymap, GLFW.GLFW_KEY_UP, 0));
        assertEquals(Optional.of(KeyAction.NUDGE_BACK), key(keymap, GLFW.GLFW_KEY_DOWN, 0));
        assertEquals(Optional.of(KeyAction.NUDGE_LEFT), key(keymap, GLFW.GLFW_KEY_LEFT, 0));
        assertEquals(Optional.of(KeyAction.NUDGE_RIGHT), key(keymap, GLFW.GLFW_KEY_RIGHT, 0));
        assertEquals(Optional.of(KeyAction.NUDGE_UP), key(keymap, GLFW.GLFW_KEY_PAGE_UP, 0));
        assertEquals(Optional.of(KeyAction.NUDGE_DOWN), key(keymap, GLFW.GLFW_KEY_PAGE_DOWN, 0));
        assertEquals(Optional.of(KeyAction.HIDE_WINDOWS), key(keymap, GLFW.GLFW_KEY_TAB, 0));
        assertEquals(Optional.of(KeyAction.HELP), key(keymap, GLFW.GLFW_KEY_F1, 0));
        assertEquals(Optional.of(KeyAction.LIBRARY), key(keymap, GLFW.GLFW_KEY_L, 0));
        assertEquals(Optional.of(KeyAction.HISTORY), key(keymap, GLFW.GLFW_KEY_H, 0));
        assertEquals(Optional.of(KeyAction.UI_SMALLER), key(keymap, GLFW.GLFW_KEY_MINUS, CTRL));
        assertEquals(Optional.of(KeyAction.UI_LARGER), key(keymap, GLFW.GLFW_KEY_EQUAL, CTRL));
        assertEquals(Optional.of(KeyAction.UI_RESET), key(keymap, GLFW.GLFW_KEY_0, CTRL));
        assertEquals(Optional.of(KeyAction.TOOL_10), key(keymap, GLFW.GLFW_KEY_0, 0), "plain 0 is the tenth tool");
        assertEquals(Optional.of(KeyAction.UI_LARGER), key(keymap, GLFW.GLFW_KEY_EQUAL, CTRL | SHIFT), "Ctrl++ on US keyboards");
        assertEquals(Optional.of(KeyAction.UI_LARGER), key(keymap, GLFW.GLFW_KEY_KP_ADD, CTRL));
        assertEquals(Optional.of(KeyAction.UI_SMALLER), key(keymap, GLFW.GLFW_KEY_KP_SUBTRACT, CTRL));
        assertEquals(Optional.of(KeyAction.UI_RESET), key(keymap, GLFW.GLFW_KEY_KP_0, CTRL));
        assertEquals("Ctrl+= / Ctrl+Shift+= / Ctrl+Keypad +", keymap.display(KeyAction.UI_LARGER));
        assertEquals("Ctrl+- / Ctrl+= / Ctrl+0", String.join(" / ", keymap.displayFirst(KeyAction.UI_SMALLER),
                keymap.displayFirst(KeyAction.UI_LARGER), keymap.displayFirst(KeyAction.UI_RESET)));
        assertEquals(Optional.of(KeyAction.TOOL_SIZE), keymap.match(KeyChord.scroll(CTRL)));
        assertEquals(Optional.of(KeyAction.TOOL_STRENGTH), keymap.match(KeyChord.scroll(ALT)));
        assertEquals(Optional.of(KeyAction.FLY_SPEED), keymap.match(KeyChord.scroll(0)));
        assertEquals(Optional.of(KeyAction.EYEDROPPER), keymap.match(KeyChord.mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0)));
    }

    @Test
    void shiftScalesNudgeAndSizeButPicksTheOtherRotation() {
        EditorKeymap keymap = EditorKeymap.defaults();
        assertEquals(Optional.of(KeyAction.NUDGE_LEFT), key(keymap, GLFW.GLFW_KEY_LEFT, SHIFT));
        assertEquals(Optional.of(KeyAction.NUDGE_UP), key(keymap, GLFW.GLFW_KEY_PAGE_UP, SHIFT));
        assertEquals(Optional.of(KeyAction.TOOL_SIZE), keymap.match(KeyChord.scroll(CTRL | SHIFT)));
        assertEquals(Optional.empty(), keymap.match(KeyChord.scroll(SHIFT)), "fly speed has no Shift variant");
        assertEquals(Optional.empty(), key(keymap, GLFW.GLFW_KEY_DELETE, SHIFT), "Delete has no Shift variant");
        assertEquals(Optional.empty(), key(keymap, GLFW.GLFW_KEY_Z, 0), "plain Z is not undo");
    }

    @Test
    void defaultsHaveNoConflictsAndLeaveVanillaKeysAlone() {
        EditorKeymap keymap = EditorKeymap.defaults();
        keymap.setReservedKeys(vanillaDefaults());
        assertEquals(List.of(), keymap.conflicts());
        for (KeyAction action : KeyAction.values()) {
            assertFalse(keymap.hasProblem(action), action + " should work with vanilla's default keys");
        }
    }

    @Test
    void aimAtWaterAndLavaIsUnboundUntilTheKeyFileBindsIt() {
        EditorKeymap keymap = EditorKeymap.defaults();
        assertEquals(List.of(), keymap.chords(KeyAction.AIM_AT_FLUIDS));
        assertEquals("", keymap.display(KeyAction.AIM_AT_FLUIDS));
        assertEquals(KeyAction.Group.TOOLS, KeyAction.AIM_AT_FLUIDS.group());
        assertEquals(Optional.of(KeyAction.AIM_AT_FLUIDS), KeyAction.byId("aim_at_fluids"));

        EditorKeymap bound = EditorKeymap.fromJson("{\"version\": 1, \"bindings\": {\"aim_at_fluids\": [\"g\"]}}");
        bound.setReservedKeys(vanillaDefaults());
        assertEquals(Optional.of(KeyAction.AIM_AT_FLUIDS), key(bound, GLFW.GLFW_KEY_G, 0));
        assertFalse(bound.hasProblem(KeyAction.AIM_AT_FLUIDS));
        assertEquals(List.of(), bound.conflicts());
        assertEquals("G", bound.display(KeyAction.AIM_AT_FLUIDS));
        assertEquals(List.of(KeyChord.parse("g")), EditorKeymap.fromJson(bound.toJson()).chords(KeyAction.AIM_AT_FLUIDS));
    }

    /**
     * A key file from before the tenth tool that gives 0 to another action keeps it there: the Shape tool goes without a
     * key rather than take it, and the Keys window says why. A file that leaves 0 free gives it to the Shape tool.
     */
    @Test
    void aNewActionNeverTakesAKeyThePlayerGaveAnotherAction() {
        EditorKeymap custom = EditorKeymap.fromJson("{\"version\": 1, \"bindings\": {\"library\": [\"0\"]}}");
        assertEquals(List.of(KeyChord.parse("0")), custom.chords(KeyAction.LIBRARY));
        assertEquals(List.of(), custom.chords(KeyAction.TOOL_10), "slot 10 goes without its default");
        assertEquals(List.of(KeyChord.parse("0")), custom.yielded(KeyAction.TOOL_10));
        assertEquals(Optional.of(KeyAction.LIBRARY), key(custom, GLFW.GLFW_KEY_0, 0), "0 still opens the library");
        assertEquals(Optional.of(KeyAction.LIBRARY), custom.boundElsewhere(KeyChord.parse("0"), KeyAction.TOOL_10));
        assertTrue(custom.conflicts().isEmpty(), "no conflict is left: " + custom.conflicts());
        String reason = dev.sculptory.fabric.client.editor.windows.KeysWindow.problem(custom, KeyAction.TOOL_10,
                dev.sculptory.fabric.client.editor.Translator.KEYS).orElseThrow();
        assertTrue(reason.contains("sculptory.keys.yielded"), reason);
        // Saved and read back, slot 10 stays unbound (the player can bind it in the file).
        EditorKeymap reread = EditorKeymap.fromJson(custom.toJson());
        assertEquals(List.of(), reread.chords(KeyAction.TOOL_10));
        assertEquals(List.of(KeyChord.parse("0")), reread.chords(KeyAction.LIBRARY));

        EditorKeymap older = EditorKeymap.fromJson("{\"version\": 1, \"bindings\": {\"library\": [\"l\"]}}");
        assertEquals(List.of(KeyChord.parse("0")), older.chords(KeyAction.TOOL_10), "a file without 0 elsewhere");
        assertEquals(List.of(), older.yielded(KeyAction.TOOL_10));
        // Binding it by hand clears the note.
        custom.bind(KeyAction.TOOL_10, List.of(KeyChord.parse("ctrl+0")));
        assertEquals(List.of(), custom.yielded(KeyAction.TOOL_10));
    }

    @Test
    void everyActionHasEnglishText() throws IOException {
        JsonObject lang;
        try (InputStream in = getClass().getResourceAsStream("/assets/sculptory/lang/en_us.json")) {
            assertNotNull(in, "en_us.json is on the test classpath");
            lang = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        for (KeyAction action : KeyAction.values()) {
            assertTrue(lang.has(action.labelKey()), action.labelKey());
        }
    }

    // ---- Conflicts and reserved keys ----

    @Test
    void conflictsAreFoundAndTheFirstActionWins() {
        EditorKeymap keymap = EditorKeymap.defaults();
        keymap.bind(KeyAction.DESELECT, List.of(KeyChord.parse("ctrl+z")));
        assertEquals(List.of(new EditorKeymap.Conflict(KeyChord.parse("ctrl+z"), List.of(KeyAction.UNDO, KeyAction.DESELECT))),
                keymap.conflicts());
        var undo = assertInstanceOf(EditorKeymap.Problem.Conflict.class,
                keymap.problem(KeyAction.UNDO, KeyChord.parse("ctrl+z")).orElseThrow());
        var deselect = assertInstanceOf(EditorKeymap.Problem.Conflict.class,
                keymap.problem(KeyAction.DESELECT, KeyChord.parse("ctrl+z")).orElseThrow());
        assertTrue(undo.winner());
        assertFalse(deselect.winner());
        assertEquals(List.of(KeyAction.DESELECT), undo.others());
        assertEquals(Optional.of(KeyAction.UNDO), key(keymap, GLFW.GLFW_KEY_Z, CTRL));
    }

    @Test
    void aShiftVariantConflictsWithTheShiftedChord() {
        EditorKeymap keymap = EditorKeymap.defaults();
        keymap.bind(KeyAction.HISTORY, List.of(KeyChord.parse("shift+up")));
        assertTrue(keymap.hasProblem(KeyAction.HISTORY));
        assertTrue(keymap.hasProblem(KeyAction.NUDGE_FORWARD));
        assertEquals(Optional.of(KeyAction.HISTORY), key(keymap, GLFW.GLFW_KEY_UP, SHIFT), "an exact chord beats a variant");
    }

    @Test
    void chordsOnThePlayersMovementOrChatKeysAreRejected() {
        EditorKeymap keymap = EditorKeymap.defaults();
        List<EditorKeymap.ReservedKey> reserved = new ArrayList<>(vanillaDefaults());
        reserved.add(new EditorKeymap.ReservedKey(KeyChord.Input.KEY, GLFW.GLFW_KEY_UP, "Walk Forwards"));
        keymap.setReservedKeys(reserved);
        var problem = assertInstanceOf(EditorKeymap.Problem.Reserved.class,
                keymap.problem(KeyAction.NUDGE_FORWARD, KeyChord.key(GLFW.GLFW_KEY_UP)).orElseThrow());
        assertEquals("Walk Forwards", problem.keyName());
        assertEquals(Optional.empty(), key(keymap, GLFW.GLFW_KEY_UP, 0), "the key keeps walking");
        assertEquals(Optional.empty(), key(keymap, GLFW.GLFW_KEY_UP, SHIFT));

        keymap.bind(KeyAction.LIBRARY, List.of(KeyChord.parse("t")));
        assertTrue(keymap.hasProblem(KeyAction.LIBRARY), "T opens chat");
        assertEquals(Optional.empty(), key(keymap, GLFW.GLFW_KEY_T, 0));
        keymap.bind(KeyAction.LIBRARY, List.of(KeyChord.parse("shift+w")));
        assertTrue(keymap.hasProblem(KeyAction.LIBRARY), "Shift doesn't make a movement key free");
    }

    @Test
    void ctrlAndAltChordsOnMovementKeysStayUsable() {
        EditorKeymap keymap = EditorKeymap.defaults();
        keymap.setReservedKeys(vanillaDefaults());
        assertFalse(keymap.hasProblem(KeyAction.DESELECT), "Ctrl+D is not the D key");
        assertEquals(Optional.of(KeyAction.DESELECT), key(keymap, GLFW.GLFW_KEY_D, CTRL));
        keymap.bind(KeyAction.LIBRARY, List.of(KeyChord.parse("alt+w")));
        assertFalse(keymap.hasProblem(KeyAction.LIBRARY));
    }

    @Test
    void reservedMouseButtonsAreRejectedToo() {
        EditorKeymap keymap = EditorKeymap.defaults();
        keymap.setReservedKeys(List.of(new EditorKeymap.ReservedKey(KeyChord.Input.MOUSE,
                GLFW.GLFW_MOUSE_BUTTON_MIDDLE, "Pick Block")));
        assertTrue(keymap.hasProblem(KeyAction.EYEDROPPER));
        assertEquals(Optional.empty(), keymap.match(KeyChord.mouse(GLFW.GLFW_MOUSE_BUTTON_MIDDLE, 0)));
    }

    // ---- Chord text ----

    @Test
    void chordsParseFormatAndDisplay() {
        assertEquals(KeyChord.key(GLFW.GLFW_KEY_Z, CTRL | SHIFT), KeyChord.parse("shift+ctrl+z"));
        assertEquals("ctrl+shift+z", KeyChord.parse("Shift+Ctrl+Z").format());
        assertEquals("Ctrl+Shift+Z", KeyChord.parse("ctrl+shift+z").display());
        assertEquals("PgUp", KeyChord.parse("page_up").display());
        assertEquals("Middle-click", KeyChord.parse("mouse.middle").display());
        assertEquals("Ctrl+Scroll", KeyChord.parse("ctrl+scroll").display());
        assertEquals(KeyChord.mouse(4, 0), KeyChord.parse("mouse.5"));
        assertEquals("key.348", KeyChord.key(348).format());
        assertEquals(KeyChord.key(348), KeyChord.parse("key.348"));
        for (String bad : List.of("", "ctrl+", "hyper+z", "ctrl+ctrl+z", "nosuchkey", "mouse.wheel", "left_shift",
                "key.0", "key.abc")) {
            assertThrows(IllegalArgumentException.class, () -> KeyChord.parse(bad), bad);
        }
    }

    @Test
    void displayListsEveryChordOfAnAction() {
        EditorKeymap keymap = EditorKeymap.defaults();
        assertEquals("Ctrl+Y / Ctrl+Shift+Z", keymap.display(KeyAction.REDO));
        keymap.bind(KeyAction.REDO, List.of());
        assertEquals("", keymap.display(KeyAction.REDO));
        assertEquals(Optional.empty(), key(keymap, GLFW.GLFW_KEY_Y, CTRL));
    }

    // ---- Persistence ----

    @Test
    void jsonRoundTripKeepsEveryBinding() {
        EditorKeymap keymap = EditorKeymap.defaults();
        keymap.bind(KeyAction.UNDO, List.of(KeyChord.parse("ctrl+z"), KeyChord.parse("alt+backspace")));
        keymap.bind(KeyAction.LIBRARY, List.of());
        keymap.bind(KeyAction.EYEDROPPER, List.of(KeyChord.parse("ctrl+mouse.left")));
        EditorKeymap loaded = EditorKeymap.fromJson(keymap.toJson());
        for (KeyAction action : KeyAction.values()) {
            assertEquals(keymap.chords(action), loaded.chords(action), action.id());
        }
        assertEquals(keymap.toJson(), loaded.toJson());
    }

    @Test
    void readingIsLenientAboutEntriesButStrictAboutTheDocument() {
        EditorKeymap loaded = EditorKeymap.fromJson("""
                {"version": 1, "bindings": {
                  "undo": ["ctrl+u", "not a chord"],
                  "redo": ["???"],
                  "library": [],
                  "no_such_action": ["ctrl+q"],
                  "help": "f1"
                }}
                """);
        assertEquals(List.of(KeyChord.parse("ctrl+u")), loaded.chords(KeyAction.UNDO), "bad chords are skipped");
        assertEquals(KeyAction.REDO.defaultChords(), loaded.chords(KeyAction.REDO), "all-bad entries keep the default");
        assertEquals(List.of(), loaded.chords(KeyAction.LIBRARY), "an empty list unbinds");
        assertEquals(KeyAction.HELP.defaultChords(), loaded.chords(KeyAction.HELP), "a non-list keeps the default");
        assertEquals(KeyAction.COPY.defaultChords(), loaded.chords(KeyAction.COPY), "missing actions keep defaults");

        assertThrows(IllegalArgumentException.class, () -> EditorKeymap.fromJson("not json {"));
        assertThrows(IllegalArgumentException.class, () -> EditorKeymap.fromJson("[]"));
        assertThrows(IllegalArgumentException.class, () -> EditorKeymap.fromJson("{\"version\": 2, \"bindings\": {}}"));
        assertThrows(IllegalArgumentException.class, () -> EditorKeymap.fromJson("{\"version\": 1}"));
    }

    @Test
    void storeCreatesTheFileWithDefaultsThenReadsItBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("config").resolve("editor-keys.json");
        List<String> problems = new ArrayList<>();
        KeymapStore store = new KeymapStore(new ConfigFile(file, new AtomicFileStore(), problems::add));
        EditorKeymap first = store.load();
        assertTrue(Files.exists(file), "a missing file is created so players can edit it");
        assertEquals(EditorKeymap.defaults().toJson(), Files.readString(file, StandardCharsets.UTF_8));

        Files.writeString(file, "{\"version\": 1, \"bindings\": {\"help\": [\"f2\"]}}", StandardCharsets.UTF_8);
        EditorKeymap edited = new KeymapStore(new ConfigFile(file, new AtomicFileStore(), problems::add)).load();
        assertEquals(List.of(KeyChord.parse("f2")), edited.chords(KeyAction.HELP));
        assertEquals(KeyAction.HELP.defaultChords(), first.chords(KeyAction.HELP));
        assertEquals(List.of(), problems);
    }

    @Test
    void storeNeverOverwritesAFileItCouldNotParse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("editor-keys.json");
        String broken = "{\"version\": 1, \"bindings\": {\"help\": [\"f2\"],,}";
        Files.writeString(file, broken, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        KeymapStore store = new KeymapStore(new ConfigFile(file, new AtomicFileStore(), problems::add));
        EditorKeymap keymap = store.load();
        assertEquals(EditorKeymap.defaults().toJson(), keymap.toJson(), "defaults are used");
        assertEquals(1, problems.size(), "the player is told why");
        keymap.bind(KeyAction.HELP, List.of(KeyChord.parse("f3")));
        assertFalse(store.save(keymap));
        assertEquals(broken, Files.readString(file, StandardCharsets.UTF_8), "the hand edit is kept");
    }
}
