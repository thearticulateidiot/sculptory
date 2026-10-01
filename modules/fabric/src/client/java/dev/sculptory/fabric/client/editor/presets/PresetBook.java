package dev.sculptory.fabric.client.editor.presets;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Every tool's presets, as saved in {@code config/sculptory/editor-presets.json}:
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "tools": {
 *     "raise": {
 *       "selected": "Soft hills",
 *       "presets": [
 *         {"name": "Soft hills", "values": {"falloff": "SMOOTH", "radius": "12", "strength": "0.3"}}
 *       ]
 *     },
 *     "scatter": {
 *       "selected": "",
 *       "presets": [
 *         {"name": "Forest", "values": {"seed": "1"}, "extra": {"mix": "asset:10:<sha256>:trees/oak.schem"}}
 *       ]
 *     }
 *   }
 * }
 * }</pre>
 *
 * {@code selected} is {@code ""} for the tool's built-in defaults; {@code extra} is left out when empty. Values are
 * the settings' preset text ({@code SettingDef.encode}). Parsing rejects a document that isn't a version-1 preset file.
 * Inside one, tool and preset entries that can't be read (a bad tool id or name, a duplicate name, values that aren't
 * text, more than {@value PresetNames#MAX_PER_TOOL} presets) are skipped and counted, and fields this version doesn't
 * know are ignored; all of them are kept as JSON and written back unchanged, so neither a hand edit with a mistake nor
 * a newer build's data is lost when this one saves.
 */
public record PresetBook(SortedMap<ToolId, ToolPresets> tools, SortedMap<String, String> unreadableTools,
                         SortedMap<String, String> other) {
    public static final int VERSION = 1;
    public static final PresetBook EMPTY = new PresetBook(new TreeMap<>(), new TreeMap<>(), new TreeMap<>());

    private static final Set<String> ROOT_FIELDS = Set.of("version", "tools");
    private static final Set<String> TOOL_FIELDS = Set.of("selected", "presets");
    private static final Set<String> PRESET_FIELDS = Set.of("name", "values", "extra");

    /** A parsed file: the presets, and how many tool or preset entries were skipped because they couldn't be read. */
    public record Parsed(PresetBook book, int skipped) {}

    public PresetBook {
        SortedMap<ToolId, ToolPresets> copy = new TreeMap<>(PresetBook::compareIds);
        tools.forEach((id, presets) -> {
            if (!Objects.requireNonNull(presets).isEmpty()) {
                copy.put(Objects.requireNonNull(id), presets);
            }
        });
        tools = Collections.unmodifiableSortedMap(copy);
        SortedMap<String, String> unreadable = new TreeMap<>(Preset.copy(unreadableTools));
        copy.keySet().forEach(id -> unreadable.remove(id.value()));
        unreadableTools = Collections.unmodifiableSortedMap(unreadable);
        other = Preset.copy(other);
    }

    /** The presets of tool {@code id} (none yet: {@link ToolPresets#EMPTY}). */
    public ToolPresets tool(ToolId id) {
        return tools.getOrDefault(id, ToolPresets.EMPTY);
    }

    /** A copy with tool {@code id}'s presets replaced (an unreadable entry for that tool gives way). */
    public PresetBook with(ToolId id, ToolPresets presets) {
        SortedMap<ToolId, ToolPresets> next = new TreeMap<>(PresetBook::compareIds);
        next.putAll(tools);
        next.put(Objects.requireNonNull(id), Objects.requireNonNull(presets));
        return new PresetBook(next, unreadableTools, other);
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        SortedMap<String, JsonElement> toolEntries = new TreeMap<>();
        unreadableTools.forEach((key, json) -> toolEntries.put(key, parse(json)));
        tools.forEach((id, presets) -> toolEntries.put(id.value(), tool(presets)));
        JsonObject toolsObject = new JsonObject();
        toolEntries.forEach(toolsObject::add);
        root.add("tools", toolsObject);
        addOther(root, other);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    /** @throws IllegalArgumentException if the text isn't a version-1 preset file */
    public static Parsed fromJson(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (JsonParseException | IllegalStateException malformed) {
            throw new IllegalArgumentException("not valid JSON", malformed);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        JsonObject root = parsed.getAsJsonObject();
        JsonElement version = root.get("version");
        if (!isInt(version, VERSION)) {
            throw new IllegalArgumentException("unsupported version " + version);
        }
        JsonElement toolsElement = root.get("tools");
        if (toolsElement == null || !toolsElement.isJsonObject()) {
            throw new IllegalArgumentException("no \"tools\" object");
        }
        SortedMap<ToolId, ToolPresets> tools = new TreeMap<>(PresetBook::compareIds);
        SortedMap<String, String> unreadableTools = new TreeMap<>();
        int skipped = 0;
        for (Map.Entry<String, JsonElement> entry : toolsElement.getAsJsonObject().entrySet()) {
            ToolId id = toolId(entry.getKey());
            JsonObject tool = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject() : null;
            JsonElement presetsElement = tool == null ? null : tool.get("presets");
            if (id == null || tool == null || (presetsElement != null && !presetsElement.isJsonArray())) {
                unreadableTools.put(entry.getKey(), entry.getValue().toString());
                skipped++;
                continue;
            }
            List<Preset> presets = new ArrayList<>();
            List<String> unreadable = new ArrayList<>();
            if (presetsElement != null) {
                for (JsonElement element : presetsElement.getAsJsonArray()) {
                    Preset preset = preset(element);
                    boolean usable = preset != null && presets.size() < PresetNames.MAX_PER_TOOL
                            && presets.stream().noneMatch(other -> other.name().equalsIgnoreCase(preset.name()));
                    if (usable) {
                        presets.add(preset);
                    } else {
                        unreadable.add(element.toString());
                        skipped++;
                    }
                }
            }
            JsonElement selected = tool.get("selected");
            boolean named = selected != null && selected.isJsonPrimitive() && selected.getAsJsonPrimitive().isString();
            tools.put(id, new ToolPresets(presets, named ? selected.getAsString() : "", unreadable,
                    other(tool, TOOL_FIELDS)));
        }
        return new Parsed(new PresetBook(tools, unreadableTools, other(root, ROOT_FIELDS)), skipped);
    }

    // ---- Writing ----

    private static JsonObject tool(ToolPresets presets) {
        JsonObject tool = new JsonObject();
        tool.addProperty("selected", presets.selected());
        JsonArray array = new JsonArray();
        for (Preset preset : presets.presets()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", preset.name());
            entry.add("values", object(preset.values()));
            if (!preset.extras().isEmpty()) {
                entry.add("extra", object(preset.extras()));
            }
            addOther(entry, preset.other());
            array.add(entry);
        }
        presets.unreadable().forEach(json -> array.add(parse(json)));
        tool.add("presets", array);
        addOther(tool, presets.other());
        return tool;
    }

    private static void addOther(JsonObject object, Map<String, String> other) {
        other.forEach((key, json) -> {
            if (!object.has(key)) {
                object.add(key, parse(json));
            }
        });
    }

    private static JsonElement parse(String json) {
        return JsonParser.parseString(json);
    }

    // ---- Reading ----

    private static Preset preset(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject entry = element.getAsJsonObject();
        JsonElement name = entry.get("name");
        if (name == null || !name.isJsonPrimitive() || !name.getAsJsonPrimitive().isString()
                || !PresetNames.isValid(name.getAsString())) {
            return null;
        }
        SortedMap<String, String> values = texts(entry.get("values"));
        SortedMap<String, String> extras = entry.has("extra") ? texts(entry.get("extra")) : new TreeMap<>();
        if (values == null || extras == null) {
            return null;
        }
        return new Preset(name.getAsString(), values, extras, other(entry, PRESET_FIELDS));
    }

    /** A JSON object of texts; numbers and booleans are read as their text (a hand edit). Null if anything else. */
    private static SortedMap<String, String> texts(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        SortedMap<String, String> texts = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()) {
                return null;
            }
            texts.put(entry.getKey(), entry.getValue().getAsString());
        }
        return texts;
    }

    /** The object's fields other than {@code known}, as JSON text. */
    private static SortedMap<String, String> other(JsonObject object, Set<String> known) {
        SortedMap<String, String> other = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            if (!known.contains(entry.getKey())) {
                other.put(entry.getKey(), entry.getValue().toString());
            }
        }
        return other;
    }

    private static ToolId toolId(String text) {
        try {
            return new ToolId(text);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static boolean isInt(JsonElement element, int expected) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        return element.getAsDouble() == expected;
    }

    private static JsonObject object(Map<String, String> texts) {
        JsonObject object = new JsonObject();
        texts.forEach(object::addProperty);
        return object;
    }

    private static int compareIds(ToolId a, ToolId b) {
        return a.value().compareTo(b.value());
    }
}
