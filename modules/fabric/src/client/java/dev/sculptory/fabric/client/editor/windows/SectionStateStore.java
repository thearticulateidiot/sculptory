package dev.sculptory.fabric.client.editor.windows;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Which Tool Settings sections the player opened or closed, per tool and section title key, kept across restarts in
 * {@code config/sculptory/editor-sections.json}:
 * <pre>{@code
 * {"version": 1, "tools": {"raise": {"sculptory.setting.brush.mask": true}}}
 * }</pre>
 * A section not listed opens as its schema says. A missing or malformed file gives no remembered states and the next
 * change replaces it; a file of another version (from a newer Sculptory) is reported and left unchanged. Each
 * change is saved at once (a click, so rare; the file write is skipped when nothing changed).
 */
public final class SectionStateStore {
    public static final String FILE_NAME = "editor-sections.json";
    public static final int VERSION = 1;
    private static final String KEY_PREFIX = "sculptory.";
    /** The title keys' prefix in files saved before the rename: read as {@link #KEY_PREFIX}, never written. */
    private static final String LEGACY_KEY_PREFIX = "buildersuite.";

    /** Where the states are saved, or null to keep them for this session only. */
    private final ConfigFile file;
    private final Map<String, Map<String, Boolean>> states = new TreeMap<>();

    private SectionStateStore(ConfigFile file) {
        this.file = file;
    }

    /** States saved in {@code file}; call {@link #load} once before use. */
    public static SectionStateStore of(ConfigFile file) {
        return new SectionStateStore(Objects.requireNonNull(file));
    }

    /** States kept for this session only (tests, or before the editor has its config directory). */
    public static SectionStateStore inMemory() {
        return new SectionStateStore(null);
    }

    /** Reads the saved states, replacing those held. */
    public void load() {
        states.clear();
        if (file == null) {
            return;
        }
        Optional<String> text = file.read();
        if (text.isEmpty()) {
            return;
        }
        try {
            states.putAll(fromJson(text.get()));
        } catch (OtherVersionException newer) {
            file.keepAsIs(newer.getMessage());
        } catch (IllegalArgumentException malformed) {
            // Written by the game, not by hand: the next change replaces it.
        }
    }

    /** Whether the player left section {@code titleKey} of {@code tool} open, or empty when they never toggled it. */
    public Optional<Boolean> expanded(ToolId tool, String titleKey) {
        Map<String, Boolean> sections = states.get(tool.value());
        return Optional.ofNullable(sections == null ? null : sections.get(titleKey));
    }

    /** Remembers that the player opened or closed a section, and saves. */
    public void set(ToolId tool, String titleKey, boolean expanded) {
        Objects.requireNonNull(titleKey);
        Boolean before = states.computeIfAbsent(tool.value(), id -> new TreeMap<>()).put(titleKey, expanded);
        if (file != null && !Boolean.valueOf(expanded).equals(before)) {
            file.write(toJson(states));
        }
    }

    /** The form's view of one tool's sections. */
    public SettingsForm.SectionMemory memoryFor(ToolId tool) {
        Objects.requireNonNull(tool);
        return new SettingsForm.SectionMemory() {
            @Override
            public Optional<Boolean> expanded(String titleKey) {
                return SectionStateStore.this.expanded(tool, titleKey);
            }

            @Override
            public void toggled(String titleKey, boolean expanded) {
                set(tool, titleKey, expanded);
            }
        };
    }

    // ---- File format ----

    /** A file written by another version of Sculptory. */
    static final class OtherVersionException extends IllegalArgumentException {
        OtherVersionException(String message) {
            super(message);
        }
    }

    static String toJson(Map<String, Map<String, Boolean>> states) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject tools = new JsonObject();
        for (Map.Entry<String, Map<String, Boolean>> tool : states.entrySet()) {
            JsonObject sections = new JsonObject();
            tool.getValue().forEach(sections::addProperty);
            tools.add(tool.getKey(), sections);
        }
        root.add("tools", tools);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    /**
     * The states in {@link #toJson} text; entries that aren't a tool id with true/false values are skipped.
     *
     * @throws OtherVersionException for a file of another version
     * @throws IllegalArgumentException for text that isn't such a file
     */
    static Map<String, Map<String, Boolean>> fromJson(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IllegalArgumentException("Not JSON", malformed);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Not a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        if (!(root.get("version") instanceof JsonPrimitive version) || !version.isNumber()) {
            throw new IllegalArgumentException("No version");
        }
        if (version.getAsInt() != VERSION) {
            throw new OtherVersionException("version " + version.getAsString() + ", this Sculptory reads "
                    + VERSION);
        }
        Map<String, Map<String, Boolean>> states = new TreeMap<>();
        if (root.get("tools") instanceof JsonObject tools) {
            for (Map.Entry<String, JsonElement> tool : tools.entrySet()) {
                if (!(tool.getValue() instanceof JsonObject sections) || !isToolId(tool.getKey())) {
                    continue;
                }
                Map<String, Boolean> read = new TreeMap<>();
                for (Map.Entry<String, JsonElement> section : sections.entrySet()) {
                    if (section.getValue() instanceof JsonPrimitive flag && flag.isBoolean()) {
                        String key = section.getKey();
                        if (key.startsWith(LEGACY_KEY_PREFIX)) {
                            // A title key saved before the rename; the next save writes it under the new name.
                            key = KEY_PREFIX + key.substring(LEGACY_KEY_PREFIX.length());
                            if (sections.has(key)) continue;
                        }
                        read.put(key, flag.getAsBoolean());
                    }
                }
                states.put(tool.getKey(), read);
            }
        }
        return states;
    }

    private static boolean isToolId(String text) {
        try {
            new ToolId(text);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
