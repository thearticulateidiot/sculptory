package dev.sculptory.fabric.client.editor.input;

import dev.sculptory.fabric.client.editor.ConfigFile;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads and saves {@code editor-keys.json}. A missing file is created with the defaults so players
 * can find and edit it. A file that doesn't parse is left untouched (the defaults are used and the
 * problem reported), so a hand edit with a typo is never overwritten.
 */
public final class KeymapStore {
    private final ConfigFile file;

    public KeymapStore(ConfigFile file) {
        this.file = Objects.requireNonNull(file);
    }

    public EditorKeymap load() {
        Optional<String> text = file.read();
        if (text.isEmpty()) {
            EditorKeymap defaults = EditorKeymap.defaults();
            file.write(defaults.toJson());
            return defaults;
        }
        try {
            return EditorKeymap.fromJson(text.get());
        } catch (IllegalArgumentException malformed) {
            file.keepAsIs(malformed.getMessage());
            return EditorKeymap.defaults();
        }
    }

    /** Saves the keymap if it changed. Returns true if the file now holds it. */
    public boolean save(EditorKeymap keymap) {
        return file.write(keymap.toJson());
    }
}
