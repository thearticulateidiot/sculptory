package dev.sculptory.fabric.config;

import dev.sculptory.fabric.SculptoryMod;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The one-time move of a folder from its Builder Suite name ({@code buildersuite}) to its Sculptory name
 * ({@code sculptory}): the client and server config folder, the game folder's library and exports, and a world's
 * saved history.
 *
 * <p>On first use in a game run: if the new folder is missing and the old one exists, the old one is moved (renamed);
 * if that fails (another drive, a locked file), it is copied into a temporary sibling that is then renamed into place,
 * and the old copy is deleted. The new folder is never overwritten or merged into: when both exist, the new one is
 * kept, the old one is left untouched, and the log says so. When neither exists nothing happens and the callers
 * write their defaults as before. When moving and copying both fail, the old folder stays untouched and is used where
 * it is for the rest of the game run (so a server config is never replaced by defaults); the next start tries again.
 * If a second game instance migrates the folder at the same moment, the one that loses finds the new folder and uses it.
 *
 * <p>Known limit: the first call for a folder copies it (when renaming fails) while holding the lock the other folders'
 * first calls take, so a large library copied across drives can stall other threads asking for a folder meanwhile.
 */
public final class FolderMigration {
    /** The folder name before the rename. */
    public static final String LEGACY_NAME = "buildersuite";
    /** The folder name now. */
    public static final String NAME = "sculptory";

    /** What {@link #migrate} did. */
    public enum Outcome {
        /** Neither folder exists: nothing to do. */
        NONE,
        /** Only the new folder exists: nothing to do. */
        ALREADY_NEW,
        /** The old folder was renamed to the new one. */
        MOVED,
        /** The old folder was copied to the new one and the old copy deleted. */
        COPIED,
        /** The old folder was copied to the new one, but deleting the old copy failed; it is left (partly) in place. */
        COPIED_OLD_LEFT,
        /** Both exist: the new one is kept, the old one left untouched. */
        BOTH_KEPT_NEW,
        /** Moving and copying failed; the old folder is untouched and the new one does not exist. */
        FAILED
    }

    /** {@link #migrate}'s outcome, with the error behind a failure ({@code null} otherwise). */
    public record Result(Outcome outcome, IOException error) {}

    /** A step of the copy fallback (copying or deleting a tree); tests swap in one that fails. */
    @FunctionalInterface
    interface TreeStep {
        void run(Path from, Path to) throws IOException;
    }

    /** The folder each parent resolved to in this game run (the new one, or the old one after a failed migration). */
    private static final Map<Path, Path> RESOLVED = new HashMap<>();

    private FolderMigration() {}

    /** {@code config/sculptory}, migrated from {@code config/buildersuite} on first use. */
    public static Path configDir() {
        return migrated(FabricLoader.getInstance().getConfigDir());
    }

    /** {@code <gameDir>/sculptory} (library, exports), migrated from {@code <gameDir>/buildersuite} on first use. */
    public static Path gameDir() {
        return migrated(FabricLoader.getInstance().getGameDir());
    }

    /** {@code <world>/sculptory} (saved history), migrated from {@code <world>/buildersuite} on first use. */
    public static Path worldDir(Path world) {
        return migrated(world);
    }

    /**
     * {@code parent/sculptory}, after migrating {@code parent/buildersuite} into it the first time this game run asks
     * for it (later calls return the same folder). Logs what the migration did. When the migration FAILED, the old
     * folder is returned for the rest of this run, so its data is used in place and no new folder is created next to
     * it; the next game run tries again.
     */
    public static Path migrated(Path parent) {
        synchronized (RESOLVED) {
            return migrated(parent, RESOLVED, FolderMigration::migrate);
        }
    }

    /** {@link #migrated(Path)} with this run's resolved folders and the migration given (for tests). */
    static Path migrated(Path parent, Map<Path, Path> resolved, BiFunction<Path, Path, Result> migration) {
        Path legacy = parent.resolve(LEGACY_NAME);
        Path target = parent.resolve(NAME);
        return resolved.computeIfAbsent(target.toAbsolutePath().normalize(), key -> {
            Result result = migration.apply(legacy, target);
            log(result, legacy, target);
            return result.outcome() == Outcome.FAILED ? legacy : target;
        });
    }

    private static void log(Result result, Path legacy, Path target) {
        switch (result.outcome()) {
            case NONE, ALREADY_NEW -> { }
            case MOVED -> SculptoryMod.LOG.info("Sculptory: moved {} to {} (the folder's old Builder Suite name)",
                    legacy, target);
            case COPIED -> SculptoryMod.LOG.info("Sculptory: copied {} to {} and removed the old folder", legacy, target);
            case COPIED_OLD_LEFT -> SculptoryMod.LOG.warn("Sculptory: copied {} to {}, but the old folder could not be"
                    + " removed; it is left in place and no longer used", legacy, target, result.error());
            case BOTH_KEPT_NEW -> SculptoryMod.LOG.warn("Sculptory: both {} and {} exist; using {} and leaving the old"
                    + " Builder Suite folder in place untouched", legacy, target, target);
            case FAILED -> SculptoryMod.LOG.error("Sculptory: could not move {} to {}; the old folder is untouched and"
                    + " used where it is for this run, and the move is tried again at the next start", legacy, target,
                    result.error());
        }
    }

    /** Moves {@code legacy} to {@code target} as the class comment describes. Never replaces or merges into {@code target}. */
    public static Result migrate(Path legacy, Path target) {
        boolean hasOld = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
        boolean hasNew = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (!hasOld) return new Result(hasNew ? Outcome.ALREADY_NEW : Outcome.NONE, null);
        if (hasNew) return new Result(Outcome.BOTH_KEPT_NEW, null);
        try {
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            // No REPLACE_EXISTING: fails rather than replacing a target that appeared meanwhile.
            Files.move(legacy, target);
            return new Result(Outcome.MOVED, null);
        } catch (FileAlreadyExistsException appeared) {
            return new Result(Outcome.BOTH_KEPT_NEW, null);
        } catch (IOException moveFailed) {
            Result raced = movedMeanwhile(legacy, target);
            return raced != null ? raced : copyThenDelete(legacy, target, moveFailed);
        }
    }

    /**
     * After a step failed: the outcome when another process (a second game instance sharing the folder) has meanwhile
     * migrated it, or {@code null} when the new folder still does not exist.
     */
    private static Result movedMeanwhile(Path legacy, Path target) {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return null;
        boolean oldLeft = Files.exists(legacy, LinkOption.NOFOLLOW_LINKS);
        return new Result(oldLeft ? Outcome.BOTH_KEPT_NEW : Outcome.ALREADY_NEW, null);
    }

    /** The fallback when renaming fails: copy into a temporary sibling, rename that into place, delete the old copy. */
    static Result copyThenDelete(Path legacy, Path target, IOException moveFailed) {
        return copyThenDelete(legacy, target, moveFailed, FolderMigration::copyTree, (root, unused) -> deleteTree(root));
    }

    /** {@link #copyThenDelete(Path, Path, IOException)} with the copy and delete steps given (for tests). */
    static Result copyThenDelete(Path legacy, Path target, IOException moveFailed, TreeStep copy, TreeStep delete) {
        Path temp = target.resolveSibling(target.getFileName() + ".migrating-" + System.nanoTime());
        try {
            copy.run(legacy, temp);
            Files.move(temp, target);
        } catch (FileAlreadyExistsException appeared) {
            deleteQuietly(temp);
            return new Result(Outcome.BOTH_KEPT_NEW, null);
        } catch (IOException copyFailed) {
            deleteQuietly(temp);
            Result raced = movedMeanwhile(legacy, target);
            if (raced != null) return raced;
            copyFailed.addSuppressed(moveFailed);
            return new Result(Outcome.FAILED, copyFailed);
        }
        try {
            delete.run(legacy, null);
            return new Result(Outcome.COPIED, null);
        } catch (IOException deleteFailed) {
            return new Result(Outcome.COPIED_OLD_LEFT, deleteFailed);
        }
    }

    /** Copies the tree at {@code from} to {@code to}; symbolic links are copied as links, never followed. */
    static void copyTree(Path from, Path to) throws IOException {
        Files.walkFileTree(from, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectory(to.resolve(from.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                // walkFileTree does not follow links, so a linked file or folder arrives here as the link itself.
                Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES,
                        LinkOption.NOFOLLOW_LINKS);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) throws IOException {
                if (failure != null) throw failure;
                try {
                    Files.delete(dir);
                } catch (DirectoryNotEmptyException e) {
                    throw new IOException("could not empty " + dir, e);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // A leftover temporary folder is harmless: it is never read.
        }
    }
}
