package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.LayoutStore;
import dev.sculptory.fabric.client.util.AtomicFileStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.glfw.GLFW;

/**
 * One window layout per UI size in the editor UI, on the reference screen (426x247 GUI pixels: 853x494 UI units at 50%,
 * 426x247 at 100%) with a reference {@code editor-layout.json} (version 1, saved at 50%).
 */
class UiSizeLayoutsTest {
    private static final TextMeasure TEXT = new TextMeasure() {
        @Override
        public int width(String text) {
            return text.length() * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        @Override
        public String trimToWidth(String text, int maxWidth) {
            return text.substring(0, Math.max(0, Math.min(text.length(), maxWidth / 6)));
        }
    };
    /** A real user's file, as in {@code .local/run/client/config/sculptory/editor-layout.json}. */
    private static final String REFERENCE_FILE = """
            {
              "version": 1,
              "windows": [
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
              ]
            }
            """;
    private static final int SCREEN_WIDTH = 426;
    private static final int SCREEN_HEIGHT = 247;

    private final long now = 1_000;

    /** An editor UI at {@code percent} on the reference screen, laid out and drawn once. */
    private EditorUi ui(UiScale scale, int percent) {
        EditorTestRig rig = new EditorTestRig();
        scale.set(percent);
        EditorUi ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, TEXT, new ToastStack(() -> now),
                new EditorUi.Services(Translator.KEYS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, scale));
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        ui.setEditing(true);
        rig.mode.enter();
        frame(ui);
        return ui;
    }

    private void frame(EditorUi ui) {
        ui.layout(SCREEN_WIDTH, SCREEN_HEIGHT);
        ui.render(new RecordingGraphics(), 0, 0, now, false);
    }

    /** The reference editor at 50% with the reference file, loaded as the game loads it. */
    private EditorUi reference(UiScale scale) {
        EditorUi ui = ui(scale, 50);
        ui.windows().restore(LayoutStore.parse(REFERENCE_FILE, 50), 50);
        frame(ui);
        return ui;
    }

    /** Every window's drawn rectangle by id, open or not. */
    private static Map<String, Rect> rects(EditorUi ui) {
        Map<String, Rect> rects = new LinkedHashMap<>();
        for (Window window : ui.windows().windows()) {
            rects.put(window.id(), window.rect());
        }
        return rects;
    }

    private static Map<String, Rect> openRects(EditorUi ui) {
        Map<String, Rect> rects = new LinkedHashMap<>();
        for (Window window : ui.windows().windows()) {
            if (window.isOpen()) {
                rects.put(window.id(), window.rect());
            }
        }
        return rects;
    }

    private static Map<String, LayoutState.WindowState> states(EditorUi ui) {
        Map<String, LayoutState.WindowState> states = new LinkedHashMap<>();
        ui.windows().snapshot().windows().forEach(state -> states.put(state.id(), state));
        return states;
    }

    private static List<String> order(EditorUi ui) {
        return ui.windows().windows().stream().map(Window::id).toList();
    }

    /** Drags a window by its title bar, brought to the front first (as a click on a part of it that shows does). */
    private static void move(WindowManager windows, String id, int dx, int dy) {
        windows.bringToFront(id);
        Rect at = windows.window(id).orElseThrow().rect();
        assertTrue(windows.mouseDown(at.x() + 30, at.y() + 5, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        windows.mouseDragged(at.x() + 30 + dx, at.y() + 5 + dy, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        windows.mouseUp(at.x() + 30 + dx, at.y() + 5 + dy, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    @Test
    void theReferenceFileAt50LooksExactlyAsBefore() {
        // Before: the file was one layout for every size, restored as it is.
        EditorUi before = ui(new UiScale(), 50);
        before.windows().restore(LayoutState.fromJson(REFERENCE_FILE, LayoutStore::isOldDefault));
        frame(before);
        EditorUi now = reference(new UiScale());
        assertEquals(rects(before), rects(now));
        assertEquals(order(before), order(now));
        assertEquals(List.of(EditorWindows.SELECTION, EditorWindows.HISTORY, EditorWindows.TOOL_SETTINGS),
                List.copyOf(openRects(now).keySet()));
        // Where the file's anchors put them on 853x494 units, kept inside the work area (Window.layout's rule).
        Rect work = now.windows().workArea();
        int top = Math.max(26, work.y());
        assertEquals(new Rect(4, top, 190, 250), openRects(now).get(EditorWindows.SELECTION));
        assertEquals(new Rect(now.uiWidth() - 4 - 170, top, 170, 303), openRects(now).get(EditorWindows.TOOL_SETTINGS));
        assertEquals(new Rect(4, Math.min(now.uiHeight() - 4 - 162, work.bottom() - 162), 160, 162),
                openRects(now).get(EditorWindows.HISTORY));

        // The closed windows open where they did too.
        for (String id : EditorWindows.MENU) {
            if (!now.windows().isOpen(id)) {
                before.toggleWindow(id);
                now.toggleWindow(id);
                frame(before);
                frame(now);
            }
        }
        assertEquals(rects(before), rects(now));
    }

    @Test
    void theReferenceFileAt100ShowsTheDefaultLayout() {
        UiScale scale = new UiScale();
        EditorUi reference = reference(scale);
        reference.resetUiSize(); // Ctrl+0
        frame(reference);
        assertEquals(100, scale.percent());

        // A fresh editor at 100% with the same windows open, none moved.
        EditorUi fresh = ui(new UiScale(), 100);
        fresh.toggleWindow(EditorWindows.HISTORY);
        frame(fresh);
        assertEquals(openRects(fresh), openRects(reference));
        assertTrue(reference.windows().windows().stream().noneMatch(Window::isPlaced), "nothing placed at 100%");

        // Before, the 50% layout was used at 100% too: History 160x162 at the bottom left, over most of Selection.
        EditorUi before = ui(new UiScale(), 100);
        before.windows().restore(LayoutState.fromJson(REFERENCE_FILE, LayoutStore::isOldDefault));
        frame(before);
        Rect oldHistory = openRects(before).get(EditorWindows.HISTORY);
        Rect history = openRects(reference).get(EditorWindows.HISTORY);
        assertNotEquals(oldHistory, history);
        assertEquals(new Rect(4, 42, 160, 162), oldHistory);

        // At 426 units Selection and Tool Settings leave only 50 between them, so they get narrower (to their
        // minimums) to make room for History there, as tall as they are: nothing overlaps.
        Rect selection = openRects(reference).get(EditorWindows.SELECTION);
        Rect toolSettings = openRects(reference).get(EditorWindows.TOOL_SETTINGS);
        assertEquals(new Rect(4, 26, 150, 174), selection);
        assertEquals(new Rect(158, 26, 140, 174), history);
        assertEquals(new Rect(302, 26, 120, 174), toolSettings);
        assertFalse(history.intersects(selection) || toolSettings.intersects(history)
                || toolSettings.intersects(selection));
    }

    @Test
    void switching50To100To50RestoresEachSizeExactlyAndMovingAt100LeavesThe50LayoutAlone() {
        UiScale scale = new UiScale();
        EditorUi ui = reference(scale);
        ui.toggleWindow(EditorWindows.KEYS);
        frame(ui);
        Map<String, Rect> at50 = rects(ui);
        Map<String, LayoutState.WindowState> state50 = states(ui);

        ui.resetUiSize(); // 100%, the keys' way
        frame(ui);
        move(ui.windows(), EditorWindows.HISTORY, 40, -20);
        move(ui.windows(), EditorWindows.KEYS, -30, 10);
        frame(ui);
        Map<String, Rect> at100 = rects(ui);

        scale.set(50); // however the size changes (the screenshot tour sets it directly)
        frame(ui);
        assertEquals(at50, rects(ui), "50% exactly as it was");
        assertEquals(state50, states(ui), "anchors, sizes and flags (the order is shared: History and Keys came up)");

        for (int i = 0; i < 5; i++) {
            ui.stepUiSize(1); // Ctrl+=, through 60, 70, 75 and 80 to 90%
        }
        ui.stepUiSize(1);
        assertEquals(100, scale.percent());
        frame(ui);
        assertEquals(at100, rects(ui), "100% as it was left");

        SizedLayouts saved = ui.windows().layouts();
        assertEquals(Set.of(EditorWindows.SELECTION, EditorWindows.HISTORY, EditorWindows.TOOL_SETTINGS),
                saved.arrangement(50).keySet());
        assertEquals(Set.of(EditorWindows.HISTORY, EditorWindows.KEYS), saved.arrangement(100).keySet());
        assertEquals(List.of("50", "100"), List.copyOf(saved.uiSizes().keySet()),
                "passing through 60 to 90% arranged nothing there");
    }

    @Test
    void resetLayoutAtOneSizeLeavesTheOthers() {
        UiScale scale = new UiScale();
        EditorUi ui = reference(scale);
        SizedLayouts before = ui.windows().layouts();
        scale.set(100);
        frame(ui);
        move(ui.windows(), EditorWindows.SELECTION, 20, 10);
        scale.set(75);
        frame(ui);
        move(ui.windows(), EditorWindows.TOOL_SETTINGS, -50, 20);
        SizedLayouts arranged = ui.windows().layouts();

        scale.set(100);
        frame(ui);
        ui.windows().resetLayout(); // View > Reset layout
        frame(ui);
        SizedLayouts reset = ui.windows().layouts();
        assertEquals(Map.of(), reset.arrangement(100));
        assertEquals(before.arrangement(50), reset.arrangement(50));
        assertEquals(arranged.arrangement(75), reset.arrangement(75));

        // Which windows are open stays: History is still open, at 100% at its default place, and at 50% the reference
        // windows are where they were.
        assertTrue(ui.windows().isOpen(EditorWindows.HISTORY));
        assertFalse(ui.windows().window(EditorWindows.HISTORY).orElseThrow().isPlaced());
        scale.set(50);
        frame(ui);
        assertEquals(openRects(reference(new UiScale())), openRects(ui), "every window where the reference file has it at 50%");
    }

    @Test
    void openAndCollapsedAreSharedAcrossSizes() {
        UiScale scale = new UiScale();
        EditorUi ui = reference(scale);
        ui.toggleWindow(EditorWindows.KEYS);
        ui.windows().setCollapsed(EditorWindows.TOOL_SETTINGS, true);
        List<String> order = order(ui);
        scale.set(100);
        frame(ui);
        assertTrue(ui.windows().isOpen(EditorWindows.KEYS));
        assertTrue(ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().isCollapsed());
        assertEquals(order, order(ui));

        ui.toggleWindow(EditorWindows.HISTORY);
        ui.windows().setCollapsed(EditorWindows.TOOL_SETTINGS, false);
        scale.set(50);
        frame(ui);
        assertFalse(ui.windows().isOpen(EditorWindows.HISTORY));
        assertFalse(ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().isCollapsed());
    }

    @Test
    void theLayoutsGoThroughTheFileAndComeBackAtEachSize(@TempDir Path dir) throws Exception {
        Path path = dir.resolve(LayoutStore.FILE_NAME);
        Files.writeString(path, REFERENCE_FILE, StandardCharsets.UTF_8);
        List<String> problems = new ArrayList<>();
        LayoutStore store = new LayoutStore(new ConfigFile(path, new AtomicFileStore(), problems::add));

        UiScale scale = new UiScale();
        EditorUi ui = ui(scale, 50);
        ui.windows().restore(store.load(50).orElseThrow(), 50);
        frame(ui);
        Map<String, Rect> at50 = rects(ui);
        scale.set(100);
        frame(ui);
        move(ui.windows(), EditorWindows.HISTORY, 60, -30);
        frame(ui);
        Map<String, Rect> at100 = rects(ui);
        assertTrue(store.save(ui.windows().layouts()));
        assertTrue(Files.readString(path).contains("\"version\": 2"));

        // The next game start, at 100%: the 100% arrangement; then 50% as the reference file had it.
        UiScale nextScale = new UiScale();
        EditorUi next = ui(nextScale, 100);
        next.windows().restore(new LayoutStore(new ConfigFile(path, new AtomicFileStore(), problems::add))
                .load(100).orElseThrow(), 100);
        frame(next);
        assertEquals(at100, rects(next));
        nextScale.set(50);
        frame(next);
        assertEquals(at50, rects(next));
        assertEquals(List.of(), problems);
    }
}
