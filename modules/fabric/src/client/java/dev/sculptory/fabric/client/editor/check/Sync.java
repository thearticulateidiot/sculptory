package dev.sculptory.fabric.client.editor.check;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How the two clients of the two-player check wait for each other: named signals, each a small file in a directory
 * both can see ({@code <check dir>/sync}), written whole (a temporary file renamed into place). A signal counts only if
 * it was written after this client started, so a directory used before can't fool a new run. Pure: files only.
 */
public final class Sync {
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    private final Path dir;
    private final long startedMillis;

    public Sync(Path dir, long startedMillis) {
        this.dir = dir;
        this.startedMillis = startedMillis;
    }

    public Path dir() {
        return dir;
    }

    /** Raises signal {@code name} with a one-line (or longer) text for the other client. */
    public void signal(String name, String text) {
        check(name);
        try {
            Files.createDirectories(dir);
            Path partial = dir.resolve(name + ".partial");
            Files.writeString(partial, text, StandardCharsets.UTF_8);
            Files.move(partial, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the signal " + name, e);
        }
    }

    /** The text of signal {@code name} if it was raised in this run, else empty. */
    public Optional<String> peek(String name) {
        check(name);
        Path file = dir.resolve(name);
        try {
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            FileTime written = Files.getLastModifiedTime(file);
            if (written.toMillis() < startedMillis) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Waits for signal {@code name} (polling every 100 ms) at most {@code timeoutMs}; its text, or empty. */
    public Optional<String> await(String name, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            Optional<String> text = peek(name);
            if (text.isPresent() || System.currentTimeMillis() > deadline) {
                return text;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    private static void check(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Signal names are lower-case words joined by '-': " + name);
        }
    }
}
