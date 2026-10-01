package dev.sculptory.fabric.client.editor.commands;

import java.util.Objects;

/**
 * One thing the command search can find: a command, a setting of the active tool or one of its presets.
 *
 * @param id stable id, used for "recently run" (a command's id, {@code setting:<tool>:<key>}, {@code preset:<tool>:<name>})
 * @param name what the row says and what the query matches
 * @param category where it lives, shown dim (the menu, or the tool)
 * @param keyText the key that also runs it, or ""
 * @param availability whether Enter runs it; when not, why
 * @param run what Enter does (for a command: through {@link CommandRegistry#run})
 */
public record SearchEntry(String id, String name, String category, String keyText, Availability availability,
        Runnable run) {
    public SearchEntry {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(category);
        Objects.requireNonNull(keyText);
        Objects.requireNonNull(availability);
        Objects.requireNonNull(run);
    }
}
