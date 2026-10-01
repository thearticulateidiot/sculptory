package dev.sculptory.fabric.client.editor.presets;

import dev.sculptory.fabric.client.editor.settings.Validation;
import java.util.Collection;
import java.util.Objects;

/**
 * Preset name rules: 1 to {@value #MAX_LENGTH} characters once trimmed, no control characters, and unique within a
 * tool ignoring case. A tool keeps at most {@value #MAX_PER_TOOL} presets.
 */
public final class PresetNames {
    public static final int MAX_LENGTH = 48;
    public static final int MAX_PER_TOOL = 100;

    private PresetNames() {}

    /** The name as it is saved: the typed text, trimmed. */
    public static String normalize(String typed) {
        return typed == null ? "" : typed.strip();
    }

    /** Whether {@code name} is a well-formed saved name (already trimmed); uniqueness is not checked. */
    public static boolean isValid(String name) {
        return name != null && name.equals(normalize(name)) && format(name).isValid();
    }

    /**
     * Why {@code typed} can't name a preset among {@code existing}, as a translatable error; OK when it can.
     * {@code except} is the preset being renamed (it may keep its own name, or change its case), or null.
     */
    public static Validation problem(String typed, Collection<String> existing, String except) {
        Objects.requireNonNull(existing);
        String name = normalize(typed);
        Validation format = format(name);
        if (!format.isValid()) {
            return format;
        }
        for (String other : existing) {
            if (other.equalsIgnoreCase(name) && (except == null || !other.equalsIgnoreCase(except))) {
                return Validation.error("sculptory.preset.name.taken");
            }
        }
        return Validation.ok();
    }

    /** Case-insensitive name order, then exact order so the sort is total. */
    public static int compare(String a, String b) {
        int order = String.CASE_INSENSITIVE_ORDER.compare(a, b);
        return order != 0 ? order : a.compareTo(b);
    }

    private static Validation format(String name) {
        if (name.isEmpty()) {
            return Validation.error("sculptory.preset.name.empty");
        }
        if (name.codePointCount(0, name.length()) > MAX_LENGTH) {
            return Validation.error("sculptory.preset.name.too_long", MAX_LENGTH);
        }
        boolean control = name.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c) == Character.LINE_SEPARATOR
                || Character.getType(c) == Character.PARAGRAPH_SEPARATOR);
        if (control) {
            return Validation.error("sculptory.preset.name.control");
        }
        return Validation.ok();
    }
}
