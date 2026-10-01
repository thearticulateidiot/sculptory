package dev.sculptory.fabric.client.editor.presets;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * One tool's presets, sorted by name ignoring case, and the one last selected ({@code ""} for the tool's built-in
 * defaults). Names are valid and unique ignoring case ({@link PresetNames}); there are at most
 * {@value PresetNames#MAX_PER_TOOL}. A selection that names no preset becomes {@code ""}. {@code unreadable} holds the
 * file's preset entries that couldn't be read and {@code other} the tool's unknown fields, both as JSON text, so saving
 * writes them back unchanged. Immutable.
 */
public record ToolPresets(List<Preset> presets, String selected, List<String> unreadable,
                          SortedMap<String, String> other) {
    public static final ToolPresets EMPTY = new ToolPresets(List.of(), "");

    public ToolPresets {
        List<Preset> sorted = new ArrayList<>(presets);
        sorted.sort((a, b) -> PresetNames.compare(a.name(), b.name()));
        for (int i = 0; i < sorted.size(); i++) {
            String name = sorted.get(i).name();
            if (!PresetNames.isValid(name)) {
                throw new IllegalArgumentException("Invalid preset name: " + name);
            }
            if (i > 0 && sorted.get(i - 1).name().equalsIgnoreCase(name)) {
                throw new IllegalArgumentException("Duplicate preset name: " + name);
            }
        }
        if (sorted.size() > PresetNames.MAX_PER_TOOL) {
            throw new IllegalArgumentException("More than " + PresetNames.MAX_PER_TOOL + " presets");
        }
        presets = List.copyOf(sorted);
        Objects.requireNonNull(selected);
        String wanted = selected;
        selected = presets.stream().anyMatch(preset -> preset.name().equals(wanted)) ? selected : "";
        unreadable = List.copyOf(unreadable);
        other = Preset.copy(other);
    }

    public ToolPresets(List<Preset> presets, String selected) {
        this(presets, selected, List.of(), new TreeMap<>());
    }

    public List<String> names() {
        return presets.stream().map(Preset::name).toList();
    }

    /** The preset named exactly {@code name}. */
    public Optional<Preset> find(String name) {
        return presets.stream().filter(preset -> preset.name().equals(name)).findFirst();
    }

    /** Nothing to write for this tool. */
    public boolean isEmpty() {
        return presets.isEmpty() && selected.isEmpty() && unreadable.isEmpty() && other.isEmpty();
    }

    public boolean isFull() {
        return presets.size() >= PresetNames.MAX_PER_TOOL;
    }

    /** Adds {@code preset}, or replaces the one with the same name ignoring case. */
    public ToolPresets with(Preset preset) {
        List<Preset> next = new ArrayList<>(presets);
        next.removeIf(existing -> existing.name().equalsIgnoreCase(preset.name()));
        next.add(preset);
        String keep = selected.equalsIgnoreCase(preset.name()) ? preset.name() : selected;
        return copy(next, keep);
    }

    /** Without the preset {@code name}; when it was selected, the built-in defaults are. */
    public ToolPresets without(String name) {
        List<Preset> next = new ArrayList<>(presets);
        next.removeIf(existing -> existing.name().equals(name));
        return copy(next, selected);
    }

    /** With {@code from} renamed {@code to} (and still selected if it was). */
    public ToolPresets renamed(String from, String to) {
        Preset preset = find(from).orElseThrow(() -> new IllegalArgumentException("No preset " + from));
        List<Preset> next = new ArrayList<>(presets);
        next.remove(preset);
        next.add(preset.withName(to));
        return copy(next, selected.equals(from) ? to : selected);
    }

    /** Selects {@code name} ({@code ""} for the built-in defaults). */
    public ToolPresets select(String name) {
        return copy(presets, name);
    }

    private ToolPresets copy(List<Preset> nextPresets, String nextSelected) {
        return new ToolPresets(nextPresets, nextSelected, unreadable, other);
    }
}
