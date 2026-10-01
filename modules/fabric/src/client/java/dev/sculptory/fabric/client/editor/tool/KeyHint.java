package dev.sculptory.fabric.client.editor.tool;

import java.util.List;
import java.util.Objects;

/**
 * One entry of the HUD hint line, e.g. ("Ctrl+Scroll", "sculptory.hint.radius"). {@code args} fill the
 * description's placeholders (e.g. a block count); most hints have none.
 */
public record KeyHint(String keys, String descriptionKey, List<String> args) {
    public KeyHint {
        Objects.requireNonNull(keys);
        Objects.requireNonNull(descriptionKey);
        args = List.copyOf(args);
    }

    public KeyHint(String keys, String descriptionKey) {
        this(keys, descriptionKey, List.of());
    }

    /** A hint with no keys, only text (e.g. "Paste · 1,000 blocks"). */
    public static KeyHint text(String descriptionKey, String... args) {
        return new KeyHint("", descriptionKey, List.of(args));
    }
}
