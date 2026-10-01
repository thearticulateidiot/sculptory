package dev.sculptory.fabric.client.editor.tutorial;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code editor-tutorial.json}: round trip, and a missing, malformed, newer, locked or hand-edited file never crashes. */
class TutorialStoreTest {
    @TempDir
    Path dir;

    private final List<String> problems = new ArrayList<>();

    private Path file() {
        return dir.resolve(TutorialStore.FILE_NAME);
    }

    private TutorialStore store() {
        return TutorialStore.of(new ConfigFile(file(), new AtomicFileStore(), problems::add));
    }

    private String read() throws IOException {
        return Files.readString(file(), StandardCharsets.UTF_8);
    }

    @Test
    void progressRoundTripsThroughTheFile() {
        TutorialStore store = store();
        assertEquals(TutorialProgress.NONE, store.load(), "no file: nothing done");
        TutorialProgress progress = TutorialProgress.NONE.withCompleted("menus").withCompleted("getting_around")
                .withCurrent("select", 3);
        assertTrue(store.save(progress));

        TutorialProgress again = store().load();
        assertEquals(Set.of("getting_around", "menus"), again.completed());
        assertEquals(Optional.of(new TutorialProgress.Current("select", 3)), again.current());
        assertEquals(List.of(), problems);
    }

    @Test
    void theFileIsSmallAndReadable() {
        String json = TutorialStore.toJson(TutorialProgress.NONE.withCompleted("menus").withCurrent("select", 2));
        assertEquals("{\n  \"version\": 1,\n  \"completed\": [\n    \"menus\"\n  ],\n  \"current\": {\n"
                + "    \"lesson\": \"select\",\n    \"step\": 2\n  }\n}\n", json);
        assertFalse(TutorialStore.toJson(TutorialProgress.NONE).contains("current"), "left out when none is in progress");
    }

    @Test
    void aMalformedFileGivesNoProgressAndIsReplacedByTheNextSave() throws IOException {
        Files.writeString(file(), "{ not json", StandardCharsets.UTF_8);
        TutorialStore store = store();
        assertEquals(TutorialProgress.NONE, store.load());
        assertTrue(store.save(TutorialProgress.NONE.withCompleted("terrain")));
        assertTrue(read().contains("\"terrain\""), read());
        assertEquals(List.of(), problems, "written by the game, so not reported");

        for (String broken : List.of("[]", "42", "{}", "{\"version\": \"one\"}", "")) {
            Files.writeString(file(), broken, StandardCharsets.UTF_8);
            assertEquals(TutorialProgress.NONE, store().load(), broken);
        }
    }

    @Test
    void entriesThatAreNotLessonsOrStepsAreSkippedAndUnknownLessonsAreKept() {
        TutorialProgress read = TutorialStore.fromJson("{\"version\": 1, \"completed\": [\"menus\", 5, \"Bad Id\", "
                + "\"from_a_newer_version\", null], \"current\": {\"lesson\": \"select\", \"step\": -1}}");
        assertEquals(Set.of("menus", "from_a_newer_version"), read.completed(), "a lesson this version lacks stays");
        assertEquals(Optional.empty(), read.current(), "a negative step is not a step");
        assertEquals(Optional.empty(), TutorialStore.fromJson(
                "{\"version\": 1, \"current\": {\"lesson\": \"select\", \"step\": 1.5}}").current());
        assertEquals(Optional.empty(), TutorialStore.fromJson(
                "{\"version\": 1, \"current\": {\"lesson\": 3, \"step\": 1}}").current());
        assertEquals(Optional.empty(), TutorialStore.fromJson("{\"version\": 1, \"current\": \"select\"}").current());
        assertEquals(Set.of(), TutorialStore.fromJson("{\"version\": 1, \"completed\": \"menus\"}").completed());
    }

    @Test
    void aFileFromANewerVersionIsLeftAsItIsAndProgressLastsTheSession() throws IOException {
        String newer = "{\"version\": 2, \"completed\": [\"menus\"], \"badges\": 7}";
        Files.writeString(file(), newer, StandardCharsets.UTF_8);
        TutorialStore store = store();
        assertEquals(TutorialProgress.NONE, store.load());
        assertEquals(1, problems.size(), "reported: " + problems);
        assertFalse(store.save(TutorialProgress.NONE.withCompleted("select")), "not written");
        assertEquals(newer, read(), "the newer file is not overwritten");
    }

    @Test
    void aLockedFileIsReportedAndTheNextSaveWritesTheLatestProgress() throws IOException {
        TutorialStore store = store();
        store.load();
        Path lock = dir.resolve(TutorialStore.FILE_NAME + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            assertFalse(store.save(TutorialProgress.NONE.withCompleted("menus")), "another writer holds the lock");
            assertEquals(1, problems.size(), problems.toString());
            assertFalse(Files.exists(file()));
        }
        assertTrue(store.save(TutorialProgress.NONE.withCompleted("menus").withCurrent("select", 0)));
        TutorialProgress again = store().load();
        assertEquals(Set.of("menus"), again.completed());
        assertEquals(Optional.of(new TutorialProgress.Current("select", 0)), again.current());
    }

    @Test
    void anUnreadableFileIsReportedAndNothingIsDone() throws IOException {
        Files.createDirectory(file());
        TutorialStore store = store();
        assertEquals(TutorialProgress.NONE, store.load());
        assertEquals(1, problems.size(), problems.toString());
        assertFalse(store.save(TutorialProgress.NONE.withCompleted("menus")), "a folder in the file's place stays");
        assertTrue(Files.isDirectory(file()));
    }

    @Test
    void anInMemoryStoreKeepsNothingAndNeverFails() {
        TutorialStore store = TutorialStore.inMemory();
        assertEquals(TutorialProgress.NONE, store.load());
        assertTrue(store.save(TutorialProgress.NONE.withCompleted("menus")));
        assertEquals(TutorialProgress.NONE, store.load());
    }
}
