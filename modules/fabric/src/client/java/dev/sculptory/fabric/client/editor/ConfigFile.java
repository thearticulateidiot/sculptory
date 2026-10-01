package dev.sculptory.fabric.client.editor;

import dev.sculptory.fabric.client.util.AtomicFileStore;
import dev.sculptory.fabric.client.util.FileStoreException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * One small UTF-8 config file (editor keys, window layout, presets) read and written through
 * {@link AtomicFileStore}. Writes are skipped when the text is unchanged. Problems are reported in
 * plain language and never thrown: the editor works with defaults when a file can't be used. Once the
 * file has changed outside the game, writes stop until it is read again, so that change is never
 * overwritten (and not reported again at every save).
 */
public final class ConfigFile {
    private final Path path;
    private final AtomicFileStore store;
    private final Consumer<String> problems;
    private String lastWritten;
    private boolean readOnly;
    private boolean changedOutside;

    public ConfigFile(Path path, AtomicFileStore store, Consumer<String> problems) {
        this.path = Objects.requireNonNull(path);
        this.store = Objects.requireNonNull(store);
        this.problems = Objects.requireNonNull(problems);
    }

    public Path path() {
        return path;
    }

    /** The file's text, or empty when it is missing or can't be read. */
    public Optional<String> read() {
        readOnly = false;
        changedOutside = false;
        try {
            Optional<byte[]> bytes = store.load(path);
            Optional<String> text = bytes.map(content -> new String(content, StandardCharsets.UTF_8));
            lastWritten = text.orElse(null);
            return text;
        } catch (FileStoreException failure) {
            problems.accept("Could not read " + path.getFileName() + ": " + failure.getMessage());
            return Optional.empty();
        }
    }

    /** Never write this file again until it is read again (it holds content we couldn't parse). */
    public void keepAsIs(String reason) {
        readOnly = true;
        problems.accept(path.getFileName() + " was not used (" + reason + "); it is left unchanged");
    }

    /**
     * The file holds content that can't be used and needn't stay where it is: it is copied as it is next to itself,
     * as {@code <name>.bad} ({@code <name>.bad-2}, {@code -3}, ... when that exists: an existing file is never
     * overwritten), and reported; the next {@link #write} then replaces it. When the copy can't be made the file is
     * kept as it is instead ({@link #keepAsIs}), so it is never lost. Returns where the copy went.
     */
    public Optional<Path> keepAside(String reason) {
        Optional<Path> aside = copy();
        if (aside.isPresent()) {
            problems.accept(path.getFileName() + " was not used (" + reason + "); it was kept as "
                    + aside.get().getFileName() + ", and a new one is written at the next save");
        } else {
            keepAsIs(reason + "; it couldn't be copied aside");
        }
        return aside;
    }

    /**
     * The file is partly usable: what could be read is used, and the rest would be lost at the next {@link #write},
     * so the file as it is is first copied next to itself as {@link #keepAside} does. The caller reports the copy
     * (with what it skipped). When the copy can't be made the file is kept as it is instead ({@link #keepAsIs},
     * reported here), so nothing in it is lost. Returns where the copy went.
     */
    public Optional<Path> copyAside(String reason) {
        Optional<Path> aside = copy();
        if (aside.isEmpty()) {
            readOnly = true;
            problems.accept(path.getFileName() + ": " + reason + "; it couldn't be copied aside, so it is left"
                    + " unchanged");
        }
        return aside;
    }

    /** Copies the file next to itself as {@code <name>.bad} (never over an existing file); empty when it can't be. */
    private Optional<Path> copy() {
        Path parent = path.toAbsolutePath().getParent();
        String name = path.getFileName().toString();
        for (int attempt = 1; attempt <= 100 && parent != null; attempt++) {
            Path aside = parent.resolve(name + ".bad" + (attempt == 1 ? "" : "-" + attempt));
            try {
                Files.copy(path, aside, LinkOption.NOFOLLOW_LINKS);
                return Optional.of(aside);
            } catch (FileAlreadyExistsException taken) {
                // The next name.
            } catch (IOException | UnsupportedOperationException | SecurityException failure) {
                break;
            }
        }
        return Optional.empty();
    }

    /** Reports a problem with this file in plain language (logged and shown, as the file's other problems are). */
    public void report(String message) {
        problems.accept(path.getFileName() + ": " + Objects.requireNonNull(message));
    }

    /**
     * Whether {@link #write} can save now: the file was read (or found missing), isn't kept as is, hasn't
     * changed outside the game, and no save has had an uncertain outcome since. False after a read that failed.
     */
    public boolean canWrite() {
        return !readOnly && !changedOutside && store.canSave(path);
    }

    /** Writes the text if it differs from what the file holds. Returns true if the file now holds it. */
    public boolean write(String text) {
        Objects.requireNonNull(text);
        if (text.equals(lastWritten)) {
            return true;
        }
        if (!canWrite()) {
            return false;
        }
        try {
            store.save(path, text.getBytes(StandardCharsets.UTF_8));
            lastWritten = text;
            return true;
        } catch (FileStoreException failure) {
            if (failure.kind() == FileStoreException.Kind.CHANGED_EXTERNALLY) {
                changedOutside = true;
            }
            problems.accept("Could not save " + path.getFileName() + ": " + failure.getMessage());
            return false;
        }
    }
}
