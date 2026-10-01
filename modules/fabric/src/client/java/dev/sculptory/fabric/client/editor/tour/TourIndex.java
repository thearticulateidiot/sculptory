package dev.sculptory.fabric.client.editor.tour;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The tour's {@code index.txt}: one line per step with its picture, name, result and what it shows, under a summary
 * line starting with {@code #}.
 */
public final class TourIndex {
    public static final String FILE = "index.txt";

    public enum Status { OK, FAILED, SKIPPED }

    /**
     * One step's result.
     *
     * @param file the picture's file name, or "" when none was written
     * @param detail why it failed or was skipped, or ""
     */
    public record Entry(int number, String name, String file, Status status, String description, String detail) {
        public Entry {
            Objects.requireNonNull(name);
            Objects.requireNonNull(file);
            Objects.requireNonNull(status);
            Objects.requireNonNull(description);
            Objects.requireNonNull(detail);
        }

        /** {@code 07-tool-raise.png | tool-raise | OK | Raise brush selected...}. */
        public String line() {
            String result = status == Status.OK ? "OK" : status + ": " + oneLine(detail);
            return (file.isEmpty() ? "(no picture)" : file) + " | " + name + " | " + result + " | " + description;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();

    /**
     * The picture file of step {@code number} (1-based) out of {@code total}: the number zero-padded to at least two
     * digits (three once there are 100 steps), a dash and the name.
     */
    public static String fileName(int number, int total, String name) {
        if (number < 1 || number > total) {
            throw new IllegalArgumentException("Step " + number + " of " + total);
        }
        int digits = Math.max(2, Integer.toString(total).length());
        return String.format("%0" + digits + "d-%s.png", number, name);
    }

    /** The picture file of a step named after it alone (the wiki pictures: {@code select-box.png}). */
    public static String fileName(String name) {
        return Objects.requireNonNull(name) + ".png";
    }

    /** A line about the setup (window size, GUI scale, UI size, layout), shown under the summary with a {@code #}. */
    public void addNote(String note) {
        notes.add(oneLine(Objects.requireNonNull(note)));
    }

    public void add(Entry entry) {
        entries.add(Objects.requireNonNull(entry));
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public long count(Status status) {
        return entries.stream().filter(entry -> entry.status() == status).count();
    }

    /** The summary line, the setup notes, then one line per step. */
    public List<String> lines() {
        List<String> lines = new ArrayList<>();
        lines.add("# Sculptory screenshot tour: " + entries.size() + " steps, " + count(Status.OK) + " ok, "
                + count(Status.FAILED) + " failed, " + count(Status.SKIPPED) + " skipped");
        notes.forEach(note -> lines.add("# " + note));
        entries.forEach(entry -> lines.add(entry.line()));
        return lines;
    }

    /** Writes {@value #FILE} into {@code dir} (UTF-8), replacing an older one. */
    public Path write(Path dir) throws IOException {
        Path file = dir.resolve(FILE);
        Files.write(file, lines(), StandardCharsets.UTF_8);
        return file;
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s*\\R\\s*", " ").strip();
    }
}
