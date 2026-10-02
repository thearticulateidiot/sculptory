package dev.sculptory.server.library;

import dev.sculptory.core.schem.SchematicFormat;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A validated path inside the asset library, as the client names it: segments joined by {@code /}, relative to the
 * library root. Pure string rules, no file system access (the file-system checks, such as symlinks that leave the
 * library, are {@link Library}'s).
 *
 * <p><b>Rules</b> (every one refuses with {@link LibraryPathException}):
 * <ul>
 *   <li>at most {@value #MAX_LENGTH} characters and {@value #MAX_DEPTH} segments;</li>
 *   <li>{@code /} is the only separator: no {@code \}, no leading {@code /} (absolute), no trailing {@code /}, no
 *       empty segment ({@code a//b});</li>
 *   <li>no {@code :} anywhere (drive letters such as {@code C:}, NTFS alternate streams);</li>
 *   <li>no {@code .} or {@code ..} segment, no {@code ..} inside a name, no name ending in {@code .} (Windows drops
 *       trailing dots, which would alias another name);</li>
 *   <li>each name matches {@code [A-Za-z0-9][A-Za-z0-9_.-]{0,63}}: no spaces, no control characters, nothing
 *       hidden (a leading dot), which also keeps the derived {@code .index.json} and temporary files out of
 *       reach;</li>
 *   <li>no reserved Windows device name as a name's stem (the part before the first dot), whatever the case:
 *       CON, PRN, AUX, NUL, COM0-9, LPT0-9;</li>
 *   <li>files end in a lower-case extension of their kind ({@value #EXTENSION}, {@code .litematic} or
 *       {@code .nbt} for schematics, {@value #PALETTE_EXTENSION} for palettes) with a non-empty stem; folders have no
 *       such rule;</li>
 *   <li>the only name allowed to start with {@code _} is a first segment {@value #PLAYERS}, which must be followed
 *       by a canonical lower-case player UUID folder ({@code _players/<uuid>/...}); a file cannot sit directly in
 *       {@code _players}.</li>
 * </ul>
 * The empty string is the root folder.
 *
 * <p><b>Kinds of file</b> (palettes): a file is a schematic ({@value #EXTENSION}, {@code .litematic} or {@code .nbt}:
 * {@link #format}) or a block palette ({@value #PALETTE_EXTENSION}), by its lower-case extension ({@link Kind#of}).
 * {@link #file} parses schematics only, so no schematic code path ever takes a palette; {@link #palette} parses
 * palettes only, and {@link #anyFile} either (library management, which works on both). A file keeps its extension: a
 * rename or move never changes it (so never its kind or format either).
 */
public final class LibraryPath {
    /** The default schematic extension (Sponge); a schematic file may also end in {@code .litematic} or {@code .nbt}. */
    public static final String EXTENSION = ".schem";
    /** The extension of a block palette file (palettes). */
    public static final String PALETTE_EXTENSION = ".palette.json";
    public static final String PLAYERS = "_players";
    /**
     * The id of the virtual "Shared with me" folder (per-asset access): what other players granted the viewer, listed
     * with the entries' real paths. It starts with {@code _}, so it never parses as a path and no real folder can be
     * named so.
     */
    public static final String SHARED = "_shared";
    public static final int MAX_LENGTH = 256;
    public static final int MAX_DEPTH = 8;
    public static final int MAX_NAME = 64;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0," + (MAX_NAME - 1) + "}");
    private static final Pattern UUID_NAME =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final Set<String> RESERVED = deviceNames();

    public static final LibraryPath ROOT = new LibraryPath(List.of(), false);

    /** What a library file holds, by its lower-case extension (palettes). */
    public enum Kind {
        /** A schematic asset: Sponge {@value LibraryPath#EXTENSION} (the default), Litematica or a structure file. */
        SCHEMATIC(List.of(EXTENSION, ".litematic", ".nbt")),
        /** A block palette, {@value LibraryPath#PALETTE_EXTENSION}. */
        PALETTE(List.of(PALETTE_EXTENSION));

        private final List<String> extensions;

        Kind(List<String> extensions) {
            this.extensions = extensions;
        }

        /** The kind's default extension (a new file's when none is typed). */
        public String extension() {
            return extensions.get(0);
        }

        /** Every extension a file of this kind may have, lower case, the default first. */
        public List<String> extensions() {
            return extensions;
        }

        /** The extension of this kind {@code name} ends in after a non-empty stem, or {@code null}. */
        public String extensionOf(String name) {
            for (String extension : extensions) {
                if (name.endsWith(extension) && name.length() > extension.length()) return extension;
            }
            return null;
        }

        /** The kind of a file named {@code name} (an extension after a non-empty stem), or {@code null}. */
        public static Kind of(String name) {
            for (Kind kind : values()) {
                if (kind.extensionOf(name) != null) return kind;
            }
            return null;
        }
    }

    private static final String SCHEMATIC_ENDINGS = String.join(", ", Kind.SCHEMATIC.extensions());
    private static final Set<Kind> SCHEMATICS = Set.of(Kind.SCHEMATIC);
    private static final Set<Kind> PALETTES = Set.of(Kind.PALETTE);
    private static final Set<Kind> ANY_KIND = Set.of(Kind.SCHEMATIC, Kind.PALETTE);

    private final List<String> segments;
    private final boolean file;
    /** A file's kind; {@code null} for a folder. */
    private final Kind kind;

    private LibraryPath(List<String> segments, boolean file) {
        this.segments = List.copyOf(segments);
        this.file = file;
        this.kind = file ? Kind.of(this.segments.get(this.segments.size() - 1)) : null;
        if (file && kind == null) throw new IllegalArgumentException("Not a library file name: " + name());
    }

    /** A folder path; {@code ""} is the root. */
    public static LibraryPath folder(String text) throws LibraryPathException {
        return parse(text, false, Set.of());
    }

    /** A {@value #EXTENSION} file path: schematics only, never a palette. */
    public static LibraryPath file(String text) throws LibraryPathException {
        return parse(text, true, SCHEMATICS);
    }

    /** A {@value #PALETTE_EXTENSION} file path: palettes only (palettes). */
    public static LibraryPath palette(String text) throws LibraryPathException {
        return parse(text, true, PALETTES);
    }

    /** A file path of either kind, for what works on every library file (rename, move, delete). */
    public static LibraryPath anyFile(String text) throws LibraryPathException {
        return parse(text, true, ANY_KIND);
    }

    private static LibraryPath parse(String text, boolean file, Set<Kind> kinds) throws LibraryPathException {
        Objects.requireNonNull(text);
        if (text.isEmpty()) {
            if (file) throw new LibraryPathException("empty file path");
            return ROOT;
        }
        if (text.length() > MAX_LENGTH) throw new LibraryPathException("longer than " + MAX_LENGTH + " characters");
        if (text.indexOf('\\') >= 0) throw new LibraryPathException("'\\' is not a separator; use '/'");
        if (text.indexOf(':') >= 0) throw new LibraryPathException("':' is not allowed (drive letters, streams)");
        if (text.startsWith("/")) throw new LibraryPathException("absolute paths are not allowed");
        if (text.endsWith("/")) throw new LibraryPathException("trailing '/'");
        String[] parts = text.split("/", -1);
        if (parts.length > MAX_DEPTH) throw new LibraryPathException("deeper than " + MAX_DEPTH + " levels");
        List<String> segments = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i];
            boolean last = i == parts.length - 1;
            if (name.isEmpty()) throw new LibraryPathException("empty path segment");
            if (name.equals(".") || name.equals("..")) throw new LibraryPathException("'" + name + "' segments are not allowed");
            if (i == 0 && name.equals(PLAYERS)) {
                segments.add(name);
                continue;
            }
            if (i == 1 && segments.get(0).equals(PLAYERS)) {
                if (!UUID_NAME.matcher(name).matches()) {
                    throw new LibraryPathException("'" + PLAYERS + "' holds only lower-case player UUID folders");
                }
                segments.add(name);
                continue;
            }
            checkName(name);
            if (last && file) {
                Kind kind = Kind.of(name);
                if (kind == null || !kinds.contains(kind)) {
                    throw new LibraryPathException(kinds.equals(PALETTES) ? "a palette file must end in " + PALETTE_EXTENSION
                            : kinds.equals(SCHEMATICS) ? "a library file must end in " + SCHEMATIC_ENDINGS
                            : "a library file must end in " + SCHEMATIC_ENDINGS + " or " + PALETTE_EXTENSION);
                }
            }
            segments.add(name);
        }
        if (file && segments.get(0).equals(PLAYERS) && segments.size() < 3) {
            throw new LibraryPathException("files belong in a player folder, not directly in " + PLAYERS);
        }
        if (file && segments.get(segments.size() - 1).equals(PLAYERS)) {
            throw new LibraryPathException(PLAYERS + " is a folder");
        }
        return new LibraryPath(segments, file);
    }

    /** Whether {@code name} is an acceptable file or folder name inside a folder (not the {@code _players} parts). */
    public static boolean validName(String name) {
        try {
            checkName(name);
            return true;
        } catch (LibraryPathException e) {
            return false;
        }
    }

    /** Whether {@code name} is a canonical player folder name inside {@code _players}. */
    public static boolean validPlayerFolder(String name) {
        return name != null && UUID_NAME.matcher(name).matches();
    }

    private static void checkName(String name) throws LibraryPathException {
        if (name.length() > MAX_NAME) throw new LibraryPathException("name longer than " + MAX_NAME + " characters");
        if (!NAME.matcher(name).matches()) {
            throw new LibraryPathException("'" + printable(name) + "' has characters outside [A-Za-z0-9_.-] or does "
                    + "not start with a letter or digit");
        }
        if (name.contains("..")) throw new LibraryPathException("'..' inside a name");
        if (name.endsWith(".")) throw new LibraryPathException("a name must not end with '.'");
        int dot = name.indexOf('.');
        String stem = (dot < 0 ? name : name.substring(0, dot)).toUpperCase(Locale.ROOT);
        if (RESERVED.contains(stem)) throw new LibraryPathException("'" + name + "' is a reserved Windows device name");
    }

    public List<String> segments() {
        return segments;
    }

    public boolean isFile() {
        return file;
    }

    /** A file's kind, by its extension; {@code null} for a folder. */
    public Kind kind() {
        return kind;
    }

    /** A file's extension (lower case, with its dot); {@code null} for a folder. */
    public String extension() {
        return file ? kind.extensionOf(name()) : null;
    }

    /** A schematic file's format, by its extension; {@code null} for a folder or a palette. */
    public SchematicFormat format() {
        return kind == Kind.SCHEMATIC ? SchematicFormat.ofFileName(name()) : null;
    }

    /** Whether this is a block palette file (palettes). */
    public boolean isPalette() {
        return kind == Kind.PALETTE;
    }

    public boolean isRoot() {
        return segments.isEmpty();
    }

    /** The last segment, or {@code ""} for the root. */
    public String name() {
        return segments.isEmpty() ? "" : segments.get(segments.size() - 1);
    }

    /** A file's name without its extension ({@link #extension}). */
    public String stem() {
        String name = name();
        return file ? name.substring(0, name.length() - extension().length()) : name;
    }

    /** Whether this path lies in (or is) the {@code _players} area. */
    public boolean inPlayersArea() {
        return !segments.isEmpty() && segments.get(0).equals(PLAYERS);
    }

    /**
     * Whether this is {@code _players} or a player folder {@code _players/<uuid>}: the server makes them (a player's
     * own folder on their first save), and they are never created, renamed, moved or deleted on request. What lies
     * inside a player folder is not reserved.
     */
    public boolean reserved() {
        return !file && inPlayersArea() && segments.size() <= 2;
    }

    /** The player whose folder holds this path ({@code _players/<uuid>/...}). */
    public Optional<UUID> owner() {
        if (segments.size() < 2 || !segments.get(0).equals(PLAYERS)) return Optional.empty();
        return Optional.of(UUID.fromString(segments.get(1)));
    }

    /** This path moved under {@code _players/<player>/} (unchanged when it is already in the players area). */
    public LibraryPath under(UUID player) {
        if (inPlayersArea()) return this;
        List<String> moved = new ArrayList<>(segments.size() + 2);
        moved.add(PLAYERS);
        moved.add(player.toString());
        moved.addAll(segments);
        if (moved.size() > MAX_DEPTH) return null;
        LibraryPath path = new LibraryPath(moved, file);
        return path.toString().length() > MAX_LENGTH ? null : path;
    }

    /** The folder holding this path; the root's parent is the root. */
    public LibraryPath parent() {
        if (segments.size() <= 1) return ROOT;
        return new LibraryPath(segments.subList(0, segments.size() - 1), false);
    }

    /**
     * A child of this folder; {@code name} must already be valid, and a file's name must end in an extension of a
     * {@link Kind} ({@link IllegalArgumentException} otherwise).
     */
    public LibraryPath child(String name, boolean isFile) {
        if (file) throw new IllegalStateException("A file has no children");
        List<String> more = new ArrayList<>(segments);
        more.add(name);
        return new LibraryPath(more, isFile);
    }

    /** The location under {@code root}, joined segment by segment (never parsed by the file system as a whole). */
    public Path resolve(Path root) {
        Path path = root;
        for (String segment : segments) path = path.resolve(segment);
        return path;
    }

    @Override
    public String toString() {
        return String.join("/", segments);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof LibraryPath other && file == other.file && segments.equals(other.segments);
    }

    @Override
    public int hashCode() {
        return segments.hashCode() * 31 + (file ? 1 : 0);
    }

    private static String printable(String name) {
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length() && out.length() < 80; i++) {
            char c = name.charAt(i);
            out.append(c < 0x20 || c == 0x7f ? '?' : c);
        }
        return out.toString();
    }

    private static Set<String> deviceNames() {
        List<String> names = new ArrayList<>(List.of("CON", "PRN", "AUX", "NUL"));
        for (int i = 0; i <= 9; i++) {
            names.add("COM" + i);
            names.add("LPT" + i);
        }
        return Set.copyOf(names);
    }
}
