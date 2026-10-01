package dev.sculptory.fabric.client.editor.commands;

import dev.sculptory.fabric.client.editor.Translator;
import java.util.List;
import java.util.Objects;

/**
 * Whether a command can run now and, when it can't, why: a translation key with its arguments ("Needs a selection",
 * "No permission: region", "Nothing to undo"). Menus show the reason as the dimmed item's tooltip; the command search
 * toasts it.
 */
public record Availability(boolean enabled, String reasonKey, List<String> args) {
    public static final Availability OK = new Availability(true, "", List.of());

    public Availability {
        Objects.requireNonNull(reasonKey);
        args = List.copyOf(args);
    }

    /** Can't run, because of {@code reasonKey} (with {@code args}). */
    public static Availability no(String reasonKey, String... args) {
        return new Availability(false, reasonKey, List.of(args));
    }

    /** The reason in words, or "" when the command can run. */
    public String reason(Translator tr) {
        return enabled ? "" : tr.translate(reasonKey, args);
    }
}
