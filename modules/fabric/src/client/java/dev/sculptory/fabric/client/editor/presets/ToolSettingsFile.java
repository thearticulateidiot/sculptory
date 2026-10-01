package dev.sculptory.fabric.client.editor.presets;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Each tool's settings as the player last left them, as saved in {@code config/sculptory/editor-tool-settings.json}
 * ({@link ToolSettingsStore}):
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "tools": {
 *     "raise": {"values": {"radius": "12", "strength": "0.8"}},
 *     "select": {"values": {}},
 *     "scatter": {"values": {"seed": "7"}, "extra": {"mix": "block:1:minecraft:pink_petals"}}
 *   }
 * }
 * }</pre>
 *
 * Every tool of the build that wrote it is listed, by its id, with the settings whose values differ from the tool's
 * defaults (as the settings' preset text, {@code SettingDef.encode}, as presets hold them; a setting not listed is at
 * its default) and the state it keeps outside its settings ({@link PresetExtra}, as presets hold it; {@code extra} is
 * left out when empty). A tool listed with no values is at its defaults.
 *
 * <p>Reading: text that isn't a JSON object with a whole-number {@code version}, or whose {@code tools} isn't an
 * object, can't be used ({@link IllegalArgumentException}); another version is a newer (or older) Sculptory's
 * ({@link OtherVersionException}). Inside a version-1 file an entry that can't be read (a tool id that isn't one, a
 * tool that isn't an object, a value or extra that isn't text) is skipped and counted. Tools this build doesn't have
 * and settings a tool doesn't have are read like the others; {@link ToolSettingsStore} leaves them unused and writes
 * them back unchanged. Fields this version doesn't know are dropped when it saves, so a later format that adds any
 * must have a new version.
 *
 * @param tools   by tool id
 * @param skipped how many entries couldn't be read
 */
public record ToolSettingsFile(SortedMap<String, Entry> tools, int skipped) {
    public static final int VERSION = 1;
    public static final ToolSettingsFile EMPTY = new ToolSettingsFile(new TreeMap<>(), 0);

    /** One tool's saved state: settings that differ from its defaults, and its extras, each by key. */
    public record Entry(SortedMap<String, String> values, SortedMap<String, String> extra) {
        public static final Entry DEFAULTS = new Entry(new TreeMap<>(), new TreeMap<>());

        public Entry {
            values = Collections.unmodifiableSortedMap(new TreeMap<>(values));
            extra = Collections.unmodifiableSortedMap(new TreeMap<>(extra));
        }
    }

    /** A file written by another version of Sculptory. */
    public static final class OtherVersionException extends IllegalArgumentException {
        OtherVersionException(String message) {
            super(message);
        }
    }

    public ToolSettingsFile {
        tools = Collections.unmodifiableSortedMap(new TreeMap<>(tools));
        if (skipped < 0) {
            throw new IllegalArgumentException("Negative skipped count");
        }
    }

    /** The file's text: tools by id, values and extras by key. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        JsonObject toolsObject = new JsonObject();
        for (Map.Entry<String, Entry> tool : tools.entrySet()) {
            JsonObject entry = new JsonObject();
            entry.add("values", strings(tool.getValue().values()));
            if (!tool.getValue().extra().isEmpty()) {
                entry.add("extra", strings(tool.getValue().extra()));
            }
            toolsObject.add(tool.getKey(), entry);
        }
        root.add("tools", toolsObject);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    private static JsonObject strings(Map<String, String> map) {
        JsonObject object = new JsonObject();
        map.forEach(object::addProperty);
        return object;
    }

    /**
     * Reads {@link #toJson} text (see the class comment for what is skipped).
     *
     * @throws OtherVersionException for a file of another version
     * @throws IllegalArgumentException for text that can't be used: not JSON, not an object, no whole-number version,
     *                                  or {@code tools} that isn't an object
     */
    public static ToolSettingsFile fromJson(String json) {
        Objects.requireNonNull(json);
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IllegalArgumentException("not JSON");
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        Integer version = wholeNumber(root.get("version"));
        if (version == null) {
            throw new IllegalArgumentException("no version");
        }
        if (version != VERSION) {
            throw new OtherVersionException("version " + version + ", this Sculptory reads " + VERSION);
        }
        JsonElement toolsElement = root.get("tools");
        if (toolsElement == null) {
            return EMPTY;
        }
        if (!toolsElement.isJsonObject()) {
            throw new IllegalArgumentException("its tools are not an object");
        }
        SortedMap<String, Entry> tools = new TreeMap<>();
        int skipped = 0;
        for (Map.Entry<String, JsonElement> tool : toolsElement.getAsJsonObject().entrySet()) {
            if (!isToolId(tool.getKey()) || !tool.getValue().isJsonObject()) {
                skipped++;
                continue;
            }
            JsonObject entry = tool.getValue().getAsJsonObject();
            SortedMap<String, String> values = new TreeMap<>();
            SortedMap<String, String> extra = new TreeMap<>();
            skipped += readStrings(entry.get("values"), values);
            skipped += readStrings(entry.get("extra"), extra);
            tools.put(tool.getKey(), new Entry(values, extra));
        }
        return new ToolSettingsFile(tools, skipped);
    }

    /**
     * Puts {@code element}'s text fields into {@code into}; returns how many couldn't be read (one for the whole when it
     * isn't an object).
     */
    private static int readStrings(JsonElement element, Map<String, String> into) {
        if (element == null) {
            return 0;
        }
        if (!element.isJsonObject()) {
            return 1;
        }
        int skipped = 0;
        for (Map.Entry<String, JsonElement> field : element.getAsJsonObject().entrySet()) {
            if (field.getValue() instanceof JsonPrimitive text && text.isString()) {
                into.put(field.getKey(), text.getAsString());
            } else {
                skipped++;
            }
        }
        return skipped;
    }

    private static Integer wholeNumber(JsonElement element) {
        if (!(element instanceof JsonPrimitive number) || !number.isNumber()) {
            return null;
        }
        try {
            double value = number.getAsDouble();
            return value == Math.rint(value) && Math.abs(value) < Integer.MAX_VALUE ? (int) value : null;
        } catch (NumberFormatException malformed) {
            return null;
        }
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
