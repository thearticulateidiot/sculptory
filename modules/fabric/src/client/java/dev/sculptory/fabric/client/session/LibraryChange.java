package dev.sculptory.fabric.client.session;

import dev.sculptory.server.library.LibraryPath;
import dev.sculptory.server.library.LibraryPathException;
import java.util.Objects;

/**
 * A change to the library (M4, {@code LibraryChanged}): the answer to this player's rename, move, delete, new folder or
 * palette save, or a change someone else made that the server says this player may see. {@code from} is {@code ""} for
 * a new folder or a saved palette; {@code to} is {@code ""} for a deletion, and also for a move to somewhere this player
 * may not look. A file is a schematic or a palette (by its extension).
 */
public record LibraryChange(boolean folder, String from, String to) {
    public LibraryChange {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.isEmpty() && to.isEmpty()) throw new IllegalArgumentException("A library change names a path");
    }

    /**
     * Whether a change from the server names only valid library paths of its kind (the server's own {@link LibraryPath}
     * rules; the root is never changed), so a window can follow it.
     */
    static boolean valid(boolean folder, String from, String to) {
        if (from.isEmpty() && to.isEmpty()) return false;
        return validPath(folder, from) && validPath(folder, to);
    }

    private static boolean validPath(boolean folder, String path) {
        if (path.isEmpty()) return true;
        try {
            LibraryPath parsed = folder ? LibraryPath.folder(path) : LibraryPath.anyFile(path);
            return !parsed.isRoot();
        } catch (LibraryPathException e) {
            return false;
        }
    }
}
