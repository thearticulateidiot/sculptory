package dev.sculptory.fabric.client.editor.settings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** A tool's settings, in display order. Keys are unique across sections. */
public record SettingsSchema(List<Section> sections) {
    public static final SettingsSchema EMPTY = new SettingsSchema(List.of());

    public SettingsSchema {
        sections = List.copyOf(sections);
        Set<String> keys = new HashSet<>();
        for (Section section : sections) {
            for (SettingDef<?> def : section.settings()) {
                if (!keys.add(def.key())) throw new IllegalArgumentException("Duplicate setting key: " + def.key());
            }
        }
    }

    /** A schema with one untitled section. */
    public static SettingsSchema of(SettingDef<?>... defs) {
        return new SettingsSchema(List.of(new Section("", List.of(defs))));
    }

    /** Every setting, in display order. */
    public List<SettingDef<?>> defs() {
        List<SettingDef<?>> all = new ArrayList<>();
        for (Section section : sections) all.addAll(section.settings());
        return List.copyOf(all);
    }

    public Optional<SettingDef<?>> def(String key) {
        for (Section section : sections) {
            for (SettingDef<?> def : section.settings()) {
                if (def.key().equals(key)) return Optional.of(def);
            }
        }
        return Optional.empty();
    }
}
