package dev.sculptory.fabric.client.editor.windows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ConfigFile;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState.WindowState;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts.Shown;
import dev.sculptory.fabric.client.editor.ui.window.WindowPlacement;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code editor-layout.json} on disk: missing, old, broken, newer and changed files, and the lock. */
class LayoutStoreTest {
    /** The reference file (version 1, before "placed"), saved at UI 50%. */
    static final String OWNERS_FILE = """
            {"version": 1, "windows": [
              {"id": "clipboard", "open": false, "collapsed": false, "anchor": "BOTTOM_LEFT",
               "x": 4, "y": 100, "width": 180, "height": 150},
              {"id": "library", "open": false, "collapsed": false, "anchor": "TOP_RIGHT",
               "x": 180, "y": 26, "width": 220, "height": 260},
              {"id": "selection", "open": true, "collapsed": false, "anchor": "TOP_LEFT",
               "x": 4, "y": 26, "width": 190, "height": 250},
              {"id": "history", "open": true, "collapsed": false, "anchor": "BOTTOM_LEFT",
               "x": 4, "y": 4, "width": 160, "height": 162},
              {"id": "keys", "open": false, "collapsed": false, "anchor": "TOP_LEFT",
               "x": 190, "y": 26, "width": 230, "height": 220},
              {"id": "tool_settings", "open": true, "collapsed": false, "anchor": "TOP_RIGHT",
               "x": 4, "y": 26, "width": 170, "height": 303}
            ]}
            """;
    private static final WindowPlacement MOVED = new WindowPlacement(Corner.TOP_LEFT, 120, 40, 160, 110, false);

    @TempDir
    Path dir;
    private final List<String> problems = new ArrayList<>();

    private Path file() {
        return dir.resolve(LayoutStore.FILE_NAME);
    }

    private LayoutStore store() {
        return new LayoutStore(new ConfigFile(file(), new AtomicFileStore(), problems::add));
    }

    private String read() throws Exception {
        return Files.readString(file(), StandardCharsets.UTF_8);
    }

    private void write(String text) throws Exception {
        Files.writeString(file(), text, StandardCharsets.UTF_8);
    }

    /** Some layouts: History moved at 100%, History open. */
    private static SizedLayouts someLayouts() {
        return new SizedLayouts(List.of(new Shown(EditorWindows.HISTORY, true, false)),
                Map.of("100", Map.of(EditorWindows.HISTORY, MOVED)));
    }

    @Test
    void noFileGivesTheDefaultsAndTheFirstSaveWritesVersionTwo() throws Exception {
        LayoutStore store = store();
        assertEquals(Optional.empty(), store.load(50));
        assertFalse(Files.exists(file()), "loading doesn't create the file");

        assertTrue(store.save(someLayouts()));
        assertTrue(read().contains("\"version\": 2"), read());
        assertEquals(Optional.of(someLayouts()), store().load(50));
        assertEquals(List.of(), problems);
    }

    @Test
    void theOwnersOldFileBecomesTheArrangementOfTheirSizeAndIsSavedAsVersionTwo() throws Exception {
        write(OWNERS_FILE);
        LayoutStore store = store();
        SizedLayouts migrated = store.load(50).orElseThrow();
        assertEquals(List.of("50"), List.copyOf(migrated.uiSizes().keySet()), "100% and the rest start from defaults");
        assertEquals(List.of(EditorWindows.SELECTION, EditorWindows.HISTORY, EditorWindows.TOOL_SETTINGS),
                List.copyOf(migrated.arrangement(50).keySet()),
                "the open windows where they were; the closed ones at an old default place open at today's");
        assertEquals(new WindowPlacement(Corner.BOTTOM_LEFT, 4, 4, 160, 162, false),
                migrated.arrangement(50).get(EditorWindows.HISTORY));
        assertEquals(6, migrated.windows().size(), "every window's open and collapsed flags");
        assertEquals(OWNERS_FILE, read(), "reading changes nothing on disk");

        List<WindowState> at100 = new ArrayList<>();
        for (WindowState state : migrated.at(100).windows()) {
            at100.add(state.id().equals(EditorWindows.HISTORY) ? state.withPlacement(MOVED) : state);
        }
        assertTrue(store.save(migrated.capture(100, new LayoutState(at100))));
        SizedLayouts again = store().load(100).orElseThrow();
        assertEquals(migrated.arrangement(50), again.arrangement(50), "read as version 2 now, 50% as it was");
        assertEquals(Map.of(EditorWindows.HISTORY, MOVED), again.arrangement(100));
        assertEquals(List.of(), problems);
    }

    @Test
    void aBrokenFileIsReportedAndNeverSavedOver() throws Exception {
        for (String broken : List.of("{\"version\": 2, \"windows\": [", "not json", "[]", "{\"version\": 2}")) {
            write(broken);
            problems.clear();
            LayoutStore store = store();
            assertEquals(Optional.empty(), store.load(50), "the default layout: " + broken);
            assertEquals(1, problems.size(), problems.toString());
            assertTrue(problems.get(0).contains("editor-layout.json was not used"), problems.get(0));
            assertFalse(store.save(someLayouts()), "a changed layout isn't saved over it");
            assertEquals(broken, read());

            // The next game start reads it again: still unusable, still left alone.
            LayoutStore next = store();
            assertEquals(Optional.empty(), next.load(50));
            assertFalse(next.save(someLayouts()));
            assertEquals(broken, read());
        }
    }

    @Test
    void aFileFromANewerVersionIsReportedAndLeftAlone() throws Exception {
        String newer = "{\"version\": 3, \"windows\": [], \"uiSizes\": {}, \"docks\": []}";
        write(newer);
        LayoutStore store = store();
        assertEquals(Optional.empty(), store.load(50));
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("version 3"), problems.get(0));
        assertFalse(store.save(someLayouts()));
        assertEquals(newer, read(), "not overwritten");
    }

    @Test
    void aSaveThatCantTakeTheLockLeavesTheFileAndWorksOnceItCan() throws Exception {
        LayoutStore store = store();
        assertEquals(Optional.empty(), store.load(50));
        assertTrue(store.save(SizedLayouts.EMPTY));
        String saved = read();
        Path lock = dir.resolve(LayoutStore.FILE_NAME + ".lock");
        Files.deleteIfExists(lock);
        Files.createDirectory(lock); // the store can't take its lock: the save fails, the file is untouched

        assertFalse(store.save(someLayouts()));
        assertEquals(saved, read());
        assertEquals(1, problems.size(), problems.toString());

        Files.delete(lock);
        assertTrue(store.save(someLayouts()), "a failure that can be retried doesn't stop saving");
        assertEquals(Optional.of(someLayouts()), store().load(50));
    }

    @Test
    void aFileChangedOutsideTheGameIsNotOverwritten() throws Exception {
        LayoutStore store = store();
        store.load(50);
        assertTrue(store.save(SizedLayouts.EMPTY));
        String outside = someLayouts().toJson();
        write(outside);

        assertFalse(store.save(someLayouts().without(100)));
        assertEquals(outside, read());
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).contains("changed outside"), problems.get(0));
        assertFalse(store.save(new SizedLayouts(List.of(new Shown(EditorWindows.KEYS, true, false)), Map.of())));
        assertEquals(1, problems.size(), "reported once, not at every save");

        // The next game start reads the edited file.
        assertEquals(Optional.of(someLayouts()), store().load(50));
    }
}
