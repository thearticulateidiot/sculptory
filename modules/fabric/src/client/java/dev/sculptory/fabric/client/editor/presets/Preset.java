package dev.sculptory.fabric.client.editor.presets;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A named snapshot of one tool's settings: every setting in its preset text form ({@code SettingsValues.encode()}),
 * and the state the tool keeps outside its schema ({@link PresetExtra}, e.g. the Scatter tool's variant mix) by extra
 * key. Values are kept as saved, including keys this version doesn't know, so reading and writing the file never loses
 * them; applying is lenient ({@link PresetResolver}). {@code other} holds the entry's fields this version doesn't know
 * (as JSON text), written back unchanged.
 */
public record Preset(String name, SortedMap<String, String> values, SortedMap<String, String> extras,
                     SortedMap<String, String> other) {
    public Preset {
        Objects.requireNonNull(name);
        values = copy(values);
        extras = copy(extras);
        other = copy(other);
    }

    public Preset(String name, Map<String, String> values, Map<String, String> extras) {
        this(name, new TreeMap<>(values), new TreeMap<>(extras), new TreeMap<>());
    }

    public Preset(String name, Map<String, String> values) {
        this(name, values, Map.of());
    }

    public Preset withName(String newName) {
        return new Preset(newName, values, extras, other);
    }

    /** This preset carrying {@code previous}' unknown fields (an overwrite keeps a newer version's metadata). */
    public Preset keepingOther(Preset previous) {
        return new Preset(name, values, extras, previous.other());
    }

    static SortedMap<String, String> copy(Map<String, String> map) {
        SortedMap<String, String> copy = new TreeMap<>();
        map.forEach((key, value) -> copy.put(Objects.requireNonNull(key), Objects.requireNonNull(value)));
        return Collections.unmodifiableSortedMap(copy);
    }
}
