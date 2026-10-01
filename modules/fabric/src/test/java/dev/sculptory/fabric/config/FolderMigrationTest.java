package dev.sculptory.fabric.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.sculptory.fabric.config.FolderMigration.Outcome;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

class FolderMigrationTest {
    @TempDir
    Path dir;

    private Path legacy() {
        return dir.resolve(FolderMigration.LEGACY_NAME);
    }

    private Path target() {
        return dir.resolve(FolderMigration.NAME);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /** The old folder as a Builder Suite install left it: a config file and a nested library entry. */
    private void oldFolder() throws IOException {
        write(legacy().resolve("editor-keys.json"), "{\"old\": true}");
        write(legacy().resolve("library").resolve("trees").resolve("oak.schem"), "oak");
    }

    private List<String> siblings() throws IOException {
        try (Stream<Path> list = Files.list(dir)) {
            return list.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void anOldFolderAloneIsMoved() throws IOException {
        oldFolder();
        assertEquals(Outcome.MOVED, FolderMigration.migrate(legacy(), target()).outcome());
        assertFalse(Files.exists(legacy()), "the old folder is gone");
        assertEquals("{\"old\": true}", read(target().resolve("editor-keys.json")));
        assertEquals("oak", read(target().resolve("library").resolve("trees").resolve("oak.schem")));
    }

    @Test
    void whenBothExistTheNewOneIsKeptAndTheOldOneLeftUntouched() throws IOException {
        oldFolder();
        write(target().resolve("editor-keys.json"), "{\"new\": true}");
        assertEquals(Outcome.BOTH_KEPT_NEW, FolderMigration.migrate(legacy(), target()).outcome());
        assertEquals("{\"new\": true}", read(target().resolve("editor-keys.json")), "never overwritten");
        assertFalse(Files.exists(target().resolve("library")), "never merged into");
        assertEquals("{\"old\": true}", read(legacy().resolve("editor-keys.json")), "the old folder is untouched");
        assertEquals("oak", read(legacy().resolve("library").resolve("trees").resolve("oak.schem")));
    }

    @Test
    void withNeitherNothingIsCreatedAndTheDefaultsApply() throws IOException {
        assertEquals(Outcome.NONE, FolderMigration.migrate(legacy(), target()).outcome());
        assertEquals(List.of(), siblings(), "no folder is made up");
    }

    @Test
    void aNewFolderAloneIsLeftAsItIs() throws IOException {
        write(target().resolve("server.json"), "{}");
        assertEquals(Outcome.ALREADY_NEW, FolderMigration.migrate(legacy(), target()).outcome());
        assertEquals("{}", read(target().resolve("server.json")));
        assertEquals(List.of(FolderMigration.NAME), siblings());
    }

    @Test
    void whenRenamingFailsTheFolderIsCopiedAndTheOldCopyDeleted() throws IOException {
        oldFolder();
        Files.createDirectories(legacy().resolve("empty"));
        FolderMigration.Result result = FolderMigration.copyThenDelete(legacy(), target(), new IOException("other drive"));
        assertEquals(Outcome.COPIED, result.outcome());
        assertFalse(Files.exists(legacy()), "the old copy is deleted");
        assertEquals("{\"old\": true}", read(target().resolve("editor-keys.json")));
        assertEquals("oak", read(target().resolve("library").resolve("trees").resolve("oak.schem")));
        assertTrue(Files.isDirectory(target().resolve("empty")), "empty folders come along");
        assertEquals(List.of(FolderMigration.NAME), siblings(), "no temporary folder is left behind");
    }

    @Test
    void aCopyNeverReplacesANewFolderThatAppearedMeanwhile() throws IOException {
        oldFolder();
        write(target().resolve("editor-keys.json"), "{\"new\": true}");
        FolderMigration.Result result = FolderMigration.copyThenDelete(legacy(), target(), new IOException("locked"));
        assertEquals(Outcome.BOTH_KEPT_NEW, result.outcome());
        assertEquals("{\"new\": true}", read(target().resolve("editor-keys.json")));
        assertEquals("{\"old\": true}", read(legacy().resolve("editor-keys.json")));
        assertEquals(List.of(FolderMigration.LEGACY_NAME, FolderMigration.NAME), siblings(), "the temporary copy is gone");
    }

    /** A copy step that copies part of the tree, then fails as a locked file would. */
    private static void copyPartlyThenFail(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        Files.copy(from.resolve("editor-keys.json"), to.resolve("editor-keys.json"));
        throw new IOException("The process cannot access the file because another process has locked a portion of it");
    }

    private static FolderMigration.Result failingMigration(Path legacy, Path target) {
        return FolderMigration.copyThenDelete(legacy, target, new IOException("locked"),
                FolderMigrationTest::copyPartlyThenFail, (root, unused) -> {
                    throw new AssertionError("nothing is deleted after a failed copy");
                });
    }

    @Test
    void aFailedCopyLeavesTheOldFolderAloneAndRemovesTheTemporaryOne() throws IOException {
        oldFolder();
        FolderMigration.Result result = failingMigration(legacy(), target());
        assertEquals(Outcome.FAILED, result.outcome());
        assertNotNull(result.error());
        assertEquals("{\"old\": true}", read(legacy().resolve("editor-keys.json")), "the old folder is untouched");
        assertEquals("oak", read(legacy().resolve("library").resolve("trees").resolve("oak.schem")));
        assertEquals(List.of(FolderMigration.LEGACY_NAME), siblings(), "no new or temporary folder");
    }

    @Test
    void afterAFailedMigrationTheOldFolderIsUsedForTheRunAndTheNextStartRetries() throws IOException {
        oldFolder();
        Map<Path, Path> firstRun = new HashMap<>();
        int[] tries = new int[1];
        BiFunction<Path, Path, FolderMigration.Result> failing = (legacy, target) -> {
            tries[0]++;
            return failingMigration(legacy, target);
        };
        assertEquals(legacy(), FolderMigration.migrated(dir, firstRun, failing), "the old data is used in place");
        assertEquals(legacy(), FolderMigration.migrated(dir, firstRun, failing), "for the rest of the run");
        assertEquals(1, tries[0], "tried once per run");
        assertEquals(List.of(FolderMigration.LEGACY_NAME), siblings(), "no new folder is created in that run");
        // The next start tries again, and this time the move works.
        assertEquals(target(), FolderMigration.migrated(dir, new HashMap<>(), FolderMigration::migrate));
        assertEquals("{\"old\": true}", read(target().resolve("editor-keys.json")));
        assertEquals(List.of(FolderMigration.NAME), siblings());
    }

    @Test
    void whenTheOldCopyCannotBeDeletedTheNewFolderIsUsedAndTheOldOneLeft() throws IOException {
        oldFolder();
        FolderMigration.TreeStep deletePartly = (root, unused) -> {
            Files.delete(root.resolve("editor-keys.json"));
            throw new IOException("library/trees/oak.schem is in use");
        };
        FolderMigration.Result[] result = new FolderMigration.Result[1];
        BiFunction<Path, Path, FolderMigration.Result> migration = (legacy, target) -> result[0] =
                FolderMigration.copyThenDelete(legacy, target, new IOException("other drive"), FolderMigration::copyTree,
                        deletePartly);
        assertEquals(target(), FolderMigration.migrated(dir, new HashMap<>(), migration), "the new folder is used");
        assertEquals(Outcome.COPIED_OLD_LEFT, result[0].outcome());
        assertNotNull(result[0].error());
        assertEquals("{\"old\": true}", read(target().resolve("editor-keys.json")));
        assertEquals("oak", read(target().resolve("library").resolve("trees").resolve("oak.schem")));
        assertTrue(Files.exists(legacy().resolve("library").resolve("trees").resolve("oak.schem")), "the rest is left");
        assertEquals(List.of(FolderMigration.LEGACY_NAME, FolderMigration.NAME), siblings(), "no temporary folder");
    }

    @Test
    void whenAnotherInstanceMovedTheFolderMeanwhileTheNewOneIsUsedQuietly() throws IOException {
        // The old folder is gone (the other instance renamed it) when this one's move and copy try to read it.
        write(target().resolve("editor-keys.json"), "{\"moved\": true}");
        FolderMigration.Result result = FolderMigration.copyThenDelete(legacy(), target(),
                new java.nio.file.NoSuchFileException(legacy().toString()));
        assertEquals(Outcome.ALREADY_NEW, result.outcome());
        assertNull(result.error());
        assertEquals("{\"moved\": true}", read(target().resolve("editor-keys.json")));
        assertEquals(List.of(FolderMigration.NAME), siblings(), "no temporary folder");
    }

    @Test
    void symbolicLinksAreCopiedAsLinks() throws IOException {
        Path outside = dir.resolve("outside");
        write(outside.resolve("big.schem"), "big");
        write(legacy().resolve("editor-keys.json"), "{}");
        Path link = legacy().resolve("shared-library");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException e) {
            assumeTrue(false, "this system does not let the test create symbolic links: " + e);
        }
        Path copy = dir.resolve("copy");
        FolderMigration.copyTree(legacy(), copy);
        assertTrue(Files.isSymbolicLink(copy.resolve("shared-library")), "the link is copied as a link");
        assertEquals(outside, Files.readSymbolicLink(copy.resolve("shared-library")));
        assertEquals("{}", read(copy.resolve("editor-keys.json")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aLockedFileMakesTheMigrationFailAndLeavesTheOldFolderAlone() throws IOException {
        oldFolder();
        Path locked = legacy().resolve("library").resolve("trees").resolve("oak.schem");
        try (FileChannel channel = FileChannel.open(locked, StandardOpenOption.READ, StandardOpenOption.WRITE);
             FileLock lock = channel.lock()) {
            FolderMigration.Result result = FolderMigration.migrate(legacy(), target());
            assertEquals(Outcome.FAILED, result.outcome(), "error: " + result.error());
            assertEquals(List.of(FolderMigration.LEGACY_NAME), siblings(), "no new or temporary folder");
        }
        assertEquals("oak", read(locked), "the old folder is untouched");
        assertEquals("{\"old\": true}", read(legacy().resolve("editor-keys.json")));
    }

    @Test
    void theFolderIsMigratedOncePerRunAndThenOnlyResolved() throws IOException {
        oldFolder();
        assertEquals(target(), FolderMigration.migrated(dir));
        assertTrue(Files.exists(target().resolve("editor-keys.json")));
        assertFalse(Files.exists(legacy()));
        // An old folder that turns up later in the same run is not looked at again.
        write(legacy().resolve("late.json"), "{}");
        assertEquals(target(), FolderMigration.migrated(dir));
        assertTrue(Files.exists(legacy().resolve("late.json")));
        assertFalse(Files.exists(target().resolve("late.json")));
    }
}
