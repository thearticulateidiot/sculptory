package dev.sculptory.fabric.client.editor.clipboard;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Writes exported schematics to {@code <gameDir>/sculptory/exports/}: a sanitized name, never replacing a file
 * that is already there (a numeric suffix is added instead), and published atomically.
 *
 * <p>No-overwrite and atomicity together: the final name is first reserved by creating it exclusively
 * ({@code CREATE_NEW}, atomic on every file system), then the bytes go to a temporary file in the same folder,
 * which is forced to disk and moved over the reservation. A reader sees either the empty reservation or the whole
 * file; another program's file of that name is never touched.
 */
public final class ExportFiles {
    /** The default extension (Sponge {@code .schem}); {@link #write(Path, String, byte[], String)} takes another. */
    public static final String EXTENSION = ".schem";
    /** Extensions dropped from a requested name: the schematic formats' and WorldEdit's legacy {@code .schematic}. */
    private static final List<String> KNOWN_EXTENSIONS = List.of(".schematic", ".schem", ".litematic", ".nbt");
    /** Longest file stem kept. */
    public static final int MAX_STEM = 64;
    /** Highest numeric suffix tried before giving up. */
    public static final int MAX_SUFFIX = 9_999;
    public static final String DEFAULT_STEM = "clipboard";

    private static final Set<String> RESERVED = reserved();

    private ExportFiles() {}

    /**
     * A file stem that is safe on every platform: only {@code [A-Za-z0-9_.-]} (anything else becomes {@code _}, runs
     * of {@code _} collapse), no leading dots or dashes (hidden files, options), no trailing dots, no {@code ..}, at
     * most {@value #MAX_STEM} characters, never a Windows device name (those get a {@code _} prefix), and
     * {@value #DEFAULT_STEM} when nothing is left. Folders and a schematic extension in the request ({@code .schem},
     * {@code .litematic}, {@code .nbt} or {@code .schematic}) are dropped.
     */
    public static String sanitize(String requested) {
        String name = requested == null ? "" : requested.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : KNOWN_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                name = name.substring(0, name.length() - extension.length());
                break;
            }
        }
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '-';
            char next = safe ? c : '_';
            int last = out.length() - 1;
            if (next == '_' && last >= 0 && out.charAt(last) == '_') continue;
            if (next == '.' && last >= 0 && out.charAt(last) == '.') continue;
            out.append(next);
        }
        int start = 0;
        while (start < out.length() && (out.charAt(start) == '.' || out.charAt(start) == '-')) start++;
        String stem = out.substring(start);
        if (stem.length() > MAX_STEM) stem = stem.substring(0, MAX_STEM);
        int end = stem.length();
        while (end > 0 && (stem.charAt(end - 1) == '.' || stem.charAt(end - 1) == ' ')) end--;
        stem = stem.substring(0, end);
        if (stem.isEmpty() || stem.equals("_")) return DEFAULT_STEM;
        int dot = stem.indexOf('.');
        String device = (dot < 0 ? stem : stem.substring(0, dot)).toUpperCase(Locale.ROOT);
        if (RESERVED.contains(device)) stem = "_" + stem;
        return stem.length() > MAX_STEM ? stem.substring(0, MAX_STEM) : stem;
    }

    /** The file name for suffix {@code n}: {@code stem.schem}, then {@code stem-1.schem}, {@code stem-2.schem}... */
    public static String fileName(String stem, int n) {
        return fileName(stem, n, EXTENSION);
    }

    /** The file name for suffix {@code n} with {@code extension}: {@code stem.nbt}, then {@code stem-1.nbt}... */
    public static String fileName(String stem, int n, String extension) {
        return n == 0 ? stem + extension : stem + "-" + n + extension;
    }

    /**
     * Writes {@code bytes} into {@code directory} (created if needed) under the sanitized {@code requested} name, or
     * the first free numbered variant, and returns the file written.
     *
     * @throws IOException if the folder cannot be made or written, or every name up to {@value #MAX_SUFFIX} is taken
     */
    public static Path write(Path directory, String requested, byte[] bytes) throws IOException {
        return write(directory, requested, bytes, EXTENSION);
    }

    /**
     * As {@link #write(Path, String, byte[])} with {@code extension} ({@code .schem}, {@code .litematic} or
     * {@code .nbt}).
     */
    public static Path write(Path directory, String requested, byte[] bytes, String extension) throws IOException {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(bytes);
        Objects.requireNonNull(extension);
        Files.createDirectories(directory);
        String stem = sanitize(requested);
        for (int n = 0; n <= MAX_SUFFIX; n++) {
            Path target = directory.resolve(fileName(stem, n, extension));
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
            try {
                Files.createFile(target);
            } catch (FileAlreadyExistsException taken) {
                continue;
            } catch (AccessDeniedException e) {
                // Windows reports a name taken by a folder (or a file being deleted) this way.
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) continue;
                throw e;
            }
            publish(directory, target, bytes);
            return target;
        }
        throw new IOException("Every name from " + fileName(stem, 0, extension) + " to "
                + fileName(stem, MAX_SUFFIX, extension) + " is taken");
    }

    /** Moves the bytes over the reservation {@code target} through a synced temporary file. */
    private static void publish(Path directory, Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, ".export-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temporary);
            // Give the reserved name back, but only while it is still our empty reservation.
            try {
                if (Files.size(target) == 0) Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // gone already
            }
            throw e;
        }
    }

    private static Set<String> reserved() {
        java.util.List<String> names = new java.util.ArrayList<>(java.util.List.of("CON", "PRN", "AUX", "NUL"));
        for (int i = 0; i <= 9; i++) {
            names.add("COM" + i);
            names.add("LPT" + i);
        }
        return Set.copyOf(names);
    }
}
