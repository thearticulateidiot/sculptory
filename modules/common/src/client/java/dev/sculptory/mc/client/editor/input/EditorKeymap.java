package dev.sculptory.mc.client.editor.input;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.sculptory.mc.client.util.KeptJson;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * The editor's own keymap: {@link KeyAction}s bound to {@link KeyChord}s, separate from vanilla's
 * key bindings (only B, which toggles the editor, is a vanilla binding).
 *
 * <p><b>Reserved keys.</b> The player's movement, chat, command, inventory and editor-toggle keys
 * always keep working in the editor. A chord on one of those keys without Ctrl or Alt would steal
 * it, so such a chord is rejected: it never fires and the Keys window shows it in red. Chords with
 * Ctrl or Alt (Ctrl+D) stay usable. One action may share a reserved key ({@link KeyAction#sharesReservedKey}): the
 * insert-point key is E while a path point is selected; the rest of the time E opens the inventory as always.
 *
 * <p><b>Conflicts.</b> Two actions on the same chord are both flagged; the one listed first in
 * {@link KeyAction} wins until the player fixes it, in the Keys window or the file.
 *
 * <p>Saved as {@code config/sculptory/editor-keys.json}:
 * <pre>{@code
 * {"version": 1, "bindings": {"undo": ["ctrl+z"], "redo": ["ctrl+y", "ctrl+shift+z"]}}
 * }</pre>
 * Actions missing from the file keep their defaults, except a default chord the file gives to another action: the
 * player's own binding wins, and the new action goes without it ({@link #yielded}, which the Keys window shows), so an
 * action added by an update (the tenth tool on 0) never takes a key the player chose. An empty list unbinds the action.
 *
 * <p>Nothing in the file is lost when this build saves it: what it can't use is kept and written back as it was, so a
 * player who tries another build and comes back finds their keys. Actions this build doesn't have (a newer build's, or
 * an older one's) and fields other than {@code version} and {@code bindings} are kept as they are. Chords this build
 * can't read (a newer build's syntax, a typo) are kept after the action's other chords, which it binds; an action none
 * of whose chords it can read, or whose value isn't a list, keeps its defaults and its entry as it was. Either is kept
 * until the player binds that action again (or resets the keymap).
 */
public final class EditorKeymap {
    public static final int VERSION = 1;

    /**
     * A vanilla key the editor must leave alone, e.g. (KEY, W, "Walk Forwards"). {@code shareable}: an action that
     * {@link KeyAction#sharesReservedKey shares a reserved key} may sit on it (only the inventory key).
     */
    public record ReservedKey(KeyChord.Input input, int code, String name, boolean shareable) {
        public ReservedKey {
            Objects.requireNonNull(input);
            Objects.requireNonNull(name);
        }

        /** A key no editor action may share. */
        public ReservedKey(KeyChord.Input input, int code, String name) {
            this(input, code, name, false);
        }
    }

    /** Why a bound chord doesn't (or might not) work. */
    public sealed interface Problem {
        /** The chord collides with a vanilla key the player uses; it never fires. */
        record Reserved(String keyName) implements Problem {}

        /** Other actions use the same chord; the first action in {@link KeyAction} order wins. */
        record Conflict(List<KeyAction> others, boolean winner) implements Problem {
            public Conflict {
                others = List.copyOf(others);
            }
        }
    }

    /** A chord bound to more than one action. */
    public record Conflict(KeyChord chord, List<KeyAction> actions) {
        public Conflict {
            actions = List.copyOf(actions);
        }
    }

    private final EnumMap<KeyAction, List<KeyChord>> bindings = new EnumMap<>(KeyAction.class);
    /** Default chords an action went without because the key file gives them to another action. */
    private final EnumMap<KeyAction, List<KeyChord>> yielded = new EnumMap<>(KeyAction.class);
    /** Entries of actions this build doesn't have, by id, as JSON text: written back unchanged. */
    private final Map<String, String> unknownActions = new LinkedHashMap<>();
    /** An action's entry as the file had it (JSON text) when none of it could be read: written instead, until rebound. */
    private final EnumMap<KeyAction, String> unreadableEntries = new EnumMap<>(KeyAction.class);
    /** Chords of an action this build couldn't read (JSON text): written after its chords, until it is rebound. */
    private final EnumMap<KeyAction, List<String>> unreadableChords = new EnumMap<>(KeyAction.class);
    /** The file's fields other than {@code version} and {@code bindings}, as JSON text: written back unchanged. */
    private final Map<String, String> otherFields = new LinkedHashMap<>();
    private List<ReservedKey> reserved = List.of();

    private EditorKeymap() {
        for (KeyAction action : KeyAction.values()) {
            bindings.put(action, action.defaultChords());
        }
    }

    /** Every action on its default chords. */
    public static EditorKeymap defaults() {
        return new EditorKeymap();
    }

    public EditorKeymap copy() {
        EditorKeymap copy = new EditorKeymap();
        copy.bindings.putAll(bindings);
        copy.yielded.putAll(yielded);
        copy.unknownActions.putAll(unknownActions);
        copy.unreadableEntries.putAll(unreadableEntries);
        copy.unreadableChords.putAll(unreadableChords);
        copy.otherFields.putAll(otherFields);
        copy.reserved = reserved;
        return copy;
    }

    public List<KeyChord> chords(KeyAction action) {
        return bindings.get(action);
    }

    /** Replaces an action's chords; an empty list unbinds it. */
    public void bind(KeyAction action, List<KeyChord> chords) {
        Objects.requireNonNull(action);
        bindings.put(action, List.copyOf(new LinkedHashSet<>(chords)));
        yielded.remove(action);
        unreadableEntries.remove(action);
        unreadableChords.remove(action);
    }

    /** Every action on its default chords; the file's entries of actions this build doesn't have are still kept. */
    public void resetToDefaults() {
        for (KeyAction action : KeyAction.values()) {
            bindings.put(action, action.defaultChords());
        }
        yielded.clear();
        unreadableEntries.clear();
        unreadableChords.clear();
    }

    /**
     * The default chords {@code action} went without because the key file it was read from gives them to another action
     * (and names none for {@code action}); empty for most actions.
     */
    public List<KeyChord> yielded(KeyAction action) {
        return yielded.getOrDefault(action, List.of());
    }

    /** The action the loaded key file binds {@code chord} to, other than {@code except}, if any. */
    public Optional<KeyAction> boundElsewhere(KeyChord chord, KeyAction except) {
        for (Map.Entry<KeyAction, List<KeyChord>> entry : bindings.entrySet()) {
            if (entry.getKey() != except && entry.getValue().contains(chord)) return Optional.of(entry.getKey());
        }
        return Optional.empty();
    }

    /** The player's vanilla keys the editor must not take; refreshed each time the editor opens. */
    public void setReservedKeys(List<ReservedKey> keys) {
        reserved = List.copyOf(keys);
    }

    public List<ReservedKey> reservedKeys() {
        return reserved;
    }

    // ---- Matching ----

    /**
     * The action for a pressed chord: an exact match first, then (with Shift held) a shift-variant
     * action on the same chord without Shift. Reserved chords never match, except for an action that shares its key
     * ({@link KeyAction#sharesReservedKey}: the router lets the key through when the tool does not take it).
     */
    public Optional<KeyAction> match(KeyChord pressed) {
        Objects.requireNonNull(pressed);
        for (KeyAction action : KeyAction.values()) {
            for (KeyChord chord : bindings.get(action)) {
                if (chord.equals(pressed) && reservedFor(action, chord).isEmpty()) {
                    return Optional.of(action);
                }
            }
        }
        if (pressed.shift()) {
            KeyChord plain = pressed.withoutShift();
            for (KeyAction action : KeyAction.values()) {
                if (!action.shiftVariant()) {
                    continue;
                }
                for (KeyChord chord : bindings.get(action)) {
                    if (chord.equals(plain) && reservedBy(chord).isEmpty()) {
                        return Optional.of(action);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The reserved key {@code chord} takes from the player for {@code action}: none when the action may share it. */
    private Optional<ReservedKey> reservedFor(KeyAction action, KeyChord chord) {
        return reservedBy(chord).filter(key -> !(action.sharesReservedKey() && key.shareable()));
    }

    /** The reserved key a chord collides with, if any. */
    public Optional<ReservedKey> reservedBy(KeyChord chord) {
        if (chord.input() == KeyChord.Input.SCROLL || chord.hasCommandModifier()) {
            return Optional.empty();
        }
        for (ReservedKey key : reserved) {
            if (key.input() == chord.input() && key.code() == chord.code()) {
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /**
     * What is wrong with one of an action's chords, if anything. A reserved chord is reported first, except for an
     * action that shares its key ({@link KeyAction#sharesReservedKey}).
     */
    public Optional<Problem> problem(KeyAction action, KeyChord chord) {
        Optional<ReservedKey> reservedKey = reservedFor(action, chord);
        if (reservedKey.isPresent()) {
            return Optional.of(new Problem.Reserved(reservedKey.get().name()));
        }
        List<KeyAction> others = new ArrayList<>();
        boolean winner = true;
        for (KeyAction other : KeyAction.values()) {
            if (other == action) {
                continue;
            }
            for (KeyChord otherChord : bindings.get(other)) {
                if (overlaps(action, chord, other, otherChord)) {
                    others.add(other);
                    if (other.ordinal() < action.ordinal()) {
                        winner = false;
                    }
                    break;
                }
            }
        }
        return others.isEmpty() ? Optional.empty() : Optional.of(new Problem.Conflict(others, winner));
    }

    /** True if any chord of the action has a problem. */
    public boolean hasProblem(KeyAction action) {
        for (KeyChord chord : bindings.get(action)) {
            if (problem(action, chord).isPresent()) {
                return true;
            }
        }
        return false;
    }

    /** Every chord bound to more than one action. */
    public List<Conflict> conflicts() {
        Map<KeyChord, LinkedHashSet<KeyAction>> byChord = new LinkedHashMap<>();
        for (KeyAction action : KeyAction.values()) {
            for (KeyChord chord : bindings.get(action)) {
                problem(action, chord).ifPresent(problem -> {
                    if (problem instanceof Problem.Conflict conflict) {
                        LinkedHashSet<KeyAction> sharing = byChord.computeIfAbsent(chord, key -> new LinkedHashSet<>());
                        sharing.add(action);
                        sharing.addAll(conflict.others());
                    }
                });
            }
        }
        List<Conflict> conflicts = new ArrayList<>();
        for (Map.Entry<KeyChord, LinkedHashSet<KeyAction>> entry : byChord.entrySet()) {
            List<KeyAction> actions = new ArrayList<>(entry.getValue());
            actions.sort(null);
            conflicts.add(new Conflict(entry.getKey(), actions));
        }
        return conflicts;
    }

    private static boolean overlaps(KeyAction a, KeyChord chordA, KeyAction b, KeyChord chordB) {
        if (chordA.equals(chordB)) {
            return true;
        }
        // A shift-variant chord also answers to Shift+chord.
        return (a.shiftVariant() && !chordA.shift() && chordB.shift() && chordB.withoutShift().equals(chordA))
                || (b.shiftVariant() && !chordB.shift() && chordA.shift() && chordA.withoutShift().equals(chordB));
    }

    /** The action's chords for display, e.g. "Ctrl+Y / Ctrl+Shift+Z"; "" when unbound. */
    public String display(KeyAction action) {
        StringJoiner joined = new StringJoiner(" / ");
        for (KeyChord chord : bindings.get(action)) {
            joined.add(chord.display());
        }
        return joined.toString();
    }

    /** The action's first chord for display, e.g. "Ctrl+="; "" when unbound. For places with room for one. */
    public String displayFirst(KeyAction action) {
        List<KeyChord> chords = bindings.get(action);
        return chords.isEmpty() ? "" : chords.get(0).display();
    }

    // ---- Persistence ----

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject map = new JsonObject();
        for (KeyAction action : KeyAction.values()) {
            String unreadable = unreadableEntries.get(action);
            if (unreadable != null) {
                map.add(action.id(), JsonParser.parseString(unreadable));
                continue;
            }
            JsonArray chords = new JsonArray();
            for (KeyChord chord : bindings.get(action)) {
                chords.add(chord.format());
            }
            for (String kept : unreadableChords.getOrDefault(action, List.of())) {
                chords.add(JsonParser.parseString(kept));
            }
            map.add(action.id(), chords);
        }
        unknownActions.forEach((id, json) -> {
            if (!map.has(id)) map.add(id, JsonParser.parseString(json));
        });
        root.add("bindings", map);
        otherFields.forEach((key, json) -> {
            if (!root.has(key)) root.add(key, JsonParser.parseString(json));
        });
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    /**
     * Reads {@link #toJson()} output leniently (see the class description).
     *
     * @throws IllegalArgumentException if the text isn't a version-1 keymap document
     */
    public static EditorKeymap fromJson(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IllegalArgumentException("Editor keymap is not valid JSON", malformed);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Editor keymap must be a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement version = root.get("version");
        if (!(version instanceof JsonPrimitive primitive) || !primitive.isNumber() || primitive.getAsDouble() != VERSION) {
            throw new IllegalArgumentException("Unsupported editor keymap version: " + version);
        }
        JsonElement bindings = root.get("bindings");
        if (bindings == null || !bindings.isJsonObject()) {
            throw new IllegalArgumentException("Editor keymap has no bindings object");
        }
        EditorKeymap keymap = defaults();
        java.util.EnumSet<KeyAction> named = java.util.EnumSet.noneOf(KeyAction.class);
        for (Map.Entry<String, JsonElement> entry : bindings.getAsJsonObject().entrySet()) {
            Optional<KeyAction> action = KeyAction.byId(entry.getKey());
            if (action.isEmpty()) {
                keep(keymap.unknownActions, entry.getKey(), entry.getValue());
                continue;
            }
            if (!entry.getValue().isJsonArray()) {
                keep(keymap.unreadableEntries, action.get(), entry.getValue());
                continue;
            }
            List<KeyChord> chords = new ArrayList<>();
            List<String> malformed = new ArrayList<>();
            boolean unreadable = false;
            for (JsonElement element : entry.getValue().getAsJsonArray()) {
                if (element instanceof JsonPrimitive text && text.isString()) {
                    try {
                        chords.add(KeyChord.parse(text.getAsString()));
                        continue;
                    } catch (IllegalArgumentException ignored) {
                        // fall through
                    }
                }
                String kept = KeptJson.text(element);
                if (kept != null) malformed.add(kept);
                unreadable = true;
            }
            if (!chords.isEmpty() || !unreadable) {
                keymap.bind(action.get(), chords);
                named.add(action.get());
                if (!malformed.isEmpty()) keymap.unreadableChords.put(action.get(), List.copyOf(malformed));
            } else {
                keep(keymap.unreadableEntries, action.get(), entry.getValue());
            }
        }
        KeptJson.eachField(root.entrySet(), (key, text) -> {
            if (!key.equals("version") && !key.equals("bindings")) keymap.otherFields.put(key, text);
        });
        // An action the file does not name keeps its defaults, but not one the file gives to another action.
        for (KeyAction action : KeyAction.values()) {
            if (named.contains(action)) continue;
            List<KeyChord> kept = new ArrayList<>();
            List<KeyChord> given = new ArrayList<>();
            for (KeyChord chord : action.defaultChords()) {
                boolean taken = false;
                for (KeyAction other : named) {
                    if (keymap.bindings.get(other).contains(chord)) taken = true;
                }
                (taken ? given : kept).add(chord);
            }
            if (!given.isEmpty()) {
                keymap.bindings.put(action, List.copyOf(kept));
                keymap.yielded.put(action, List.copyOf(given));
            }
        }
        return keymap;
    }

    /** Keeps {@code value} as JSON text under {@code key}, unless it nests too deep to write back. */
    private static <K> void keep(Map<K, String> into, K key, JsonElement value) {
        String text = KeptJson.text(value);
        if (text != null) into.put(key, text);
    }
}
