package dev.sculptory.fabric.client.editor.presets;

import dev.sculptory.fabric.client.editor.ConfigFile;
import java.util.Objects;
import java.util.Optional;

/**
 * Loads and saves {@code editor-presets.json} ({@link PresetBook}) through a {@link ConfigFile}. A file that doesn't
 * parse or has another version is left unchanged ({@link ConfigFile#keepAsIs}) and presets are read-only until the
 * next game start, so a hand edit or a newer build's file is never overwritten. Presets in the file that can't be read
 * are skipped; the next save leaves them out.
 */
public final class PresetStore {
    public static final String FILE_NAME = "editor-presets.json";
    /** Room for {@value PresetNames#MAX_PER_TOOL} presets of every tool, 64-variant scatter mixes included. */
    public static final int MAX_BYTES = 16 << 20;

    /** What loading found: the presets, whether saving is possible, and how many entries were skipped. */
    public record Loaded(PresetBook book, boolean writable, int skipped) {
        public Loaded {
            Objects.requireNonNull(book);
        }
    }

    private final ConfigFile file;

    public PresetStore(ConfigFile file) {
        this.file = Objects.requireNonNull(file);
    }

    public Loaded load() {
        Optional<String> text = file.read();
        if (text.isEmpty()) {
            return new Loaded(PresetBook.EMPTY, file.canWrite(), 0);
        }
        try {
            PresetBook.Parsed parsed = PresetBook.fromJson(text.get());
            return new Loaded(parsed.book(), file.canWrite(), parsed.skipped());
        } catch (IllegalArgumentException unusable) {
            file.keepAsIs(unusable.getMessage());
            return new Loaded(PresetBook.EMPTY, false, 0);
        }
    }

    /** Saves the presets if they changed. Returns true if the file now holds them. */
    public boolean save(PresetBook book) {
        return file.write(book.toJson());
    }

    /** Whether saving is still possible (a save whose outcome was uncertain stops it until the next load). */
    public boolean writable() {
        return file.canWrite();
    }
}
