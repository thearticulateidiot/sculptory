package dev.sculptory.fabric.client.editor.clipboard;

import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.fabric.library.LibraryPath;
import dev.sculptory.fabric.library.LibraryPathException;
import java.util.Optional;

/**
 * Library paths as the player types them, checked on the client with the server's own rules: this reuses the pure
 * {@link LibraryPath} (common code, no file system access), so a path this accepts is one the server's parser
 * accepts. The server still decides where a save lands (players without {@code library.write} save in their own
 * {@code _players/<uuid>/} folder) and checks the files themselves.
 */
public final class LibraryPaths {
    private LibraryPaths() {}

    /** What the player typed as a file path: trimmed, with {@code .schem} added when it has no extension. */
    public static String normalizeFile(String input) {
        return normalizeFile(input, LibraryPath.EXTENSION);
    }

    /** What the player typed as a file path: trimmed, with {@code extension} added when it has none. */
    public static String normalizeFile(String input, String extension) {
        String path = input == null ? "" : input.strip();
        if (path.isEmpty()) return path;
        String name = name(path);
        if (!name.contains(".")) path = path + extension;
        return path;
    }

    /**
     * The typed path with the extension of {@code format}: a schematic extension it ends in ({@code .schem},
     * {@code .litematic}, {@code .nbt}) is replaced, and one is added when it has none (so choosing a format in the
     * save dialog changes the name's extension). Anything else is left for the path check to refuse.
     */
    public static String withFormat(String typed, SchematicFormat format) {
        String path = typed == null ? "" : typed.strip();
        if (path.isEmpty() || path.endsWith("/")) return path;
        String name = name(path);
        String extension = LibraryPath.Kind.SCHEMATIC.extensionOf(name);
        if (extension != null) return path.substring(0, path.length() - extension.length()) + format.extension();
        return name.contains(".") ? path : path + format.extension();
    }

    /** The format a path's own extension names ({@code .schem}, {@code .litematic}, {@code .nbt}), or {@code null}. */
    public static SchematicFormat formatOf(String path) {
        String name = name(path == null ? "" : path.strip());
        return LibraryPath.Kind.SCHEMATIC.extensionOf(name) == null ? null : SchematicFormat.ofFileName(name);
    }

    /** Why {@code path} is not a valid library file path, or empty when it is. */
    public static Optional<String> fileProblem(String path) {
        try {
            LibraryPath.file(path == null ? "" : path);
            return Optional.empty();
        } catch (LibraryPathException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** What the player typed as a palette path (palettes): trimmed, with {@code .palette.json} added when missing. */
    public static String normalizePalette(String input) {
        String path = input == null ? "" : input.strip();
        if (path.isEmpty() || path.endsWith("/")) return path;
        return path.endsWith(LibraryPath.PALETTE_EXTENSION) ? path : path + LibraryPath.PALETTE_EXTENSION;
    }

    /** Why {@code path} is not a valid palette path, or empty when it is. */
    public static Optional<String> paletteProblem(String path) {
        try {
            LibraryPath.palette(path == null ? "" : path);
            return Optional.empty();
        } catch (LibraryPathException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** Why {@code path} is not a valid library file path of either kind, or empty when it is. */
    public static Optional<String> anyFileProblem(String path) {
        try {
            LibraryPath.anyFile(path == null ? "" : path);
            return Optional.empty();
        } catch (LibraryPathException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** Whether {@code path} names a palette file ({@code .palette.json}). */
    public static boolean isPalette(String path) {
        return path != null && LibraryPath.Kind.of(name(path)) == LibraryPath.Kind.PALETTE;
    }

    /** Why {@code path} is not a valid library folder path, or empty when it is ({@code ""} is the root). */
    public static Optional<String> folderProblem(String path) {
        try {
            LibraryPath.folder(path == null ? "" : path);
            return Optional.empty();
        } catch (LibraryPathException e) {
            return Optional.of(e.getMessage());
        }
    }

    /** The longest text the management prompts take (a whole path fits). */
    public static final int MAX_TYPED = LibraryPath.MAX_LENGTH;

    /** What is wrong with a typed name or folder, or {@code NONE}. */
    public enum Problem {
        NONE,
        EMPTY,
        /** A name was given with a folder in it (use Move… instead). */
        SLASH,
        /** The name or folder it already has. */
        SAME,
        /** {@code _players} or a player folder. */
        RESERVED,
        /** Breaks a path rule; {@link Target#detail} says which. */
        INVALID
    }

    /** Where a typed name or folder leads: the new library path, or the problem with it. */
    public record Target(String path, Problem problem, String detail) {
        public boolean ok() {
            return problem == Problem.NONE;
        }
    }

    /**
     * The path of a new name in {@code folder}: a new folder, or the new name of an entry being renamed in place
     * ({@code current} is its path, or null for a new folder). A file's name gets its current extension (a new one
     * {@code .schem}) when it has none, and may not change it. Checked with the server's rules; the server still
     * decides.
     */
    public static Target newName(String folder, String typed, boolean file, String current) {
        return newName(folder, typed, file ? LibraryPath.Kind.SCHEMATIC : null, current);
    }

    /**
     * As {@link #newName(String, String, boolean, String)} for a file of {@code kind} (palettes: a palette keeps
     * {@code .palette.json}, added when the typed name lacks it), or a folder ({@code kind} {@code null}).
     */
    public static Target newName(String folder, String typed, LibraryPath.Kind kind, String current) {
        boolean file = kind != null;
        String name = typed == null ? "" : typed.strip();
        if (name.isEmpty()) return new Target("", Problem.EMPTY, "");
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) return new Target("", Problem.SLASH, "");
        boolean palette = kind == LibraryPath.Kind.PALETTE;
        String keep = file && !palette && current != null
                ? LibraryPath.Kind.SCHEMATIC.extensionOf(name(current)) : null;
        String path = join(folder, !file ? name : palette ? normalizePalette(name)
                : normalizeFile(name, keep == null ? LibraryPath.EXTENSION : keep));
        Optional<String> problem = !file ? folderProblem(path) : palette ? paletteProblem(path) : fileProblem(path);
        if (problem.isPresent()) return new Target(path, Problem.INVALID, problem.get());
        if (keep != null && !keep.equals(LibraryPath.Kind.SCHEMATIC.extensionOf(name(path)))) {
            return new Target(path, Problem.INVALID, "a file keeps its extension (" + keep + ")");
        }
        if (!file && reserved(path)) return new Target(path, Problem.RESERVED, "");
        if (path.equals(current)) return new Target(path, Problem.SAME, "");
        return new Target(path, Problem.NONE, "");
    }

    /** Where {@code file} goes when moved into the typed folder ({@code ""} or {@code /} is the library root). */
    public static Target moveTo(String file, String typedFolder) {
        String folder = typedFolder == null ? "" : typedFolder.strip();
        while (folder.endsWith("/")) folder = folder.substring(0, folder.length() - 1);
        Optional<String> folderProblem = folderProblem(folder);
        if (folderProblem.isPresent()) return new Target("", Problem.INVALID, folderProblem.get());
        String path = join(folder, name(file));
        Optional<String> problem = anyFileProblem(path);
        if (problem.isPresent()) return new Target(path, Problem.INVALID, problem.get());
        if (folder.equals(parent(file))) return new Target(path, Problem.SAME, "");
        return new Target(path, Problem.NONE, "");
    }

    /**
     * Whether {@code path} is {@code _players} or a player folder in it, which the server makes and never lets anyone
     * create, rename or delete ({@link LibraryPath#reserved}); false for anything that is not a valid folder path.
     */
    public static boolean reserved(String path) {
        try {
            return LibraryPath.folder(path == null ? "" : path).reserved();
        } catch (LibraryPathException e) {
            return false;
        }
    }

    /** The last segment of a path ({@code ""} for the root). */
    public static String name(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    /** The folder holding a path ({@code ""} for the root and for top-level entries). */
    public static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** A file name without its schematic ({@code .schem}, {@code .litematic}, {@code .nbt}) or palette extension. */
    public static String stem(String path) {
        String name = name(path);
        LibraryPath.Kind kind = LibraryPath.Kind.of(name);
        return kind == null ? name : name.substring(0, name.length() - kind.extensionOf(name).length());
    }

    /** {@code folder/name}, or {@code name} in the root. */
    public static String join(String folder, String name) {
        return folder.isEmpty() ? name : folder + "/" + name;
    }
}
