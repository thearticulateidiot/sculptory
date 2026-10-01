package dev.sculptory.fabric.client.editor.settings;

import java.util.List;
import java.util.Objects;

/** A collapsible group of settings in the settings window. {@code titleKey} "" means untitled. */
public record Section(String titleKey, List<SettingDef<?>> settings, boolean collapsedByDefault) {
    public Section {
        Objects.requireNonNull(titleKey);
        settings = List.copyOf(settings);
    }

    public Section(String titleKey, List<SettingDef<?>> settings) {
        this(titleKey, settings, false);
    }
}
