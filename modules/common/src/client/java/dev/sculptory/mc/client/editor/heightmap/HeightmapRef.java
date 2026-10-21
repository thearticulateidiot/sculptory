package dev.sculptory.mc.client.editor.heightmap;

import java.util.Locale;
import java.util.Objects;

/**
 * Which height image a setting names, portable between machines: a built-in picture by its name ({@code builtin:hill}),
 * or a PNG in the builder's own folder by its file name ({@code file:island.png}). A preset that names a file another
 * player doesn't have keeps the name; using it then says the image is missing.
 *
 * @param builtIn whether it is one of the mod's own pictures
 * @param name the built-in's name, or the file's name in the heightmaps folder
 */
public record HeightmapRef(boolean builtIn, String name) {
    public static final String BUILT_IN_PREFIX = "builtin:";
    public static final String FILE_PREFIX = "file:";
    /** Longest name kept: file names longer than this are skipped by the library. */
    public static final int MAX_NAME = 128;

    public HeightmapRef {
        Objects.requireNonNull(name);
        if (!valid(name)) throw new IllegalArgumentException("Not a heightmap name: " + name);
    }

    public static HeightmapRef builtIn(String name) {
        return new HeightmapRef(true, name);
    }

    public static HeightmapRef file(String name) {
        return new HeightmapRef(false, name);
    }

    /** Whether {@code name} can be a built-in's or a file's name: 1-128 characters, no line break, slash or semicolon. */
    public static boolean valid(String name) {
        return !name.isEmpty() && name.length() <= MAX_NAME && name.chars().noneMatch(c -> c < 0x20 || c == '/'
                || c == '\\' || c == ';');
    }

    /** The preset text: {@code builtin:<name>} or {@code file:<name>}. */
    public String text() {
        return (builtIn ? BUILT_IN_PREFIX : FILE_PREFIX) + name;
    }

    /** Parses {@link #text}; throws {@link IllegalArgumentException} for anything else. */
    public static HeightmapRef parse(String text) {
        Objects.requireNonNull(text);
        if (text.startsWith(BUILT_IN_PREFIX)) return builtIn(text.substring(BUILT_IN_PREFIX.length()));
        if (text.startsWith(FILE_PREFIX)) return file(text.substring(FILE_PREFIX.length()));
        throw new IllegalArgumentException("Not a heightmap: " + text);
    }

    /** How the picker names it: a built-in's translated name is the caller's; a file's name without ".png". */
    public String fileLabel() {
        return name.toLowerCase(Locale.ROOT).endsWith(".png") ? name.substring(0, name.length() - 4) : name;
    }

    @Override
    public String toString() {
        return text();
    }
}
