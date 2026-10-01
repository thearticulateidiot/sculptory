package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * The windows, toasts and Reset layout on the reference screen (2560x1482 at GUI scale 6: 426x247 GUI pixels), at UI
 * 100% (426x247 units) and 50% (852x494), in English measured like Minecraft's font: windows opened at their default
 * places don't overlap wherever their minimum sizes fit side by side, the Keys window opens as tall as the room
 * between the top bar and the hint line, toasts leave the windows' title bars free, and Reset layout puts every open
 * window back on screen and leaves which are open alone.
 */
class OwnersScreenLayoutTest {
    private static final int SCREEN_WIDTH = 426;
    private static final int SCREEN_HEIGHT = 247;
    /** The windows that open on demand at a default place of their own. */
    private static final List<String> ON_DEMAND = List.of(EditorWindows.KEYS, EditorWindows.HISTORY,
            EditorWindows.LIBRARY, EditorWindows.CLIPBOARD, EditorWindows.NOTIFICATIONS, EditorWindows.TUTORIAL,
            EditorWindows.WIKI);

    private final EditorTestRig rig = new EditorTestRig();
    private final UiScale scale = new UiScale();
    private long now = 1_000;
    private final ToastStack toasts = new ToastStack(() -> now);
    private EditorUi ui;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, McFontText.INSTANCE, toasts,
                new EditorUi.Services(English.INSTANCE, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Caps Lock", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, scale));
        rig.controller.setUi(ui);
        ui.setEditing(true);
        rig.mode.enter();
    }

    private void frame() {
        ui.layout(SCREEN_WIDTH, SCREEN_HEIGHT);
        ui.render(new RecordingGraphics(), 0, 0, now, false);
        now += 16;
    }

    private Map<String, Rect> openRects() {
        Map<String, Rect> rects = new LinkedHashMap<>();
        for (Window window : ui.windows().windows()) {
            if (window.isOpen()) {
                rects.put(window.id(), window.rect());
            }
        }
        return rects;
    }

    /** Opens exactly {@code ids} of the on-demand windows (Selection and Tool Settings stay open). */
    private void showOnly(List<String> ids) {
        for (String id : ON_DEMAND) {
            if (ui.windows().isOpen(id) != ids.contains(id)) {
                ui.toggleWindow(id);
            }
        }
        frame();
    }

    /** No two open windows overlap, and each lies in the work area. */
    private void assertNoOverlap(String at) {
        Rect work = ui.windows().workArea();
        List<Map.Entry<String, Rect>> rects = new ArrayList<>(openRects().entrySet());
        for (int i = 0; i < rects.size(); i++) {
            Rect r = rects.get(i).getValue();
            assertEquals(r, work.intersect(r), rects.get(i).getKey() + " " + r + " in the work area " + work + at);
            for (int j = i + 1; j < rects.size(); j++) {
                assertFalse(r.intersects(rects.get(j).getValue()), rects.get(i) + " and " + rects.get(j) + at);
            }
        }
    }

    @Test
    void atUi100TheWindowsWhoseMinimumsFitBesideSelectionAndToolSettingsOverlapNothing() {
        scale.set(100);
        frame();
        assertNoOverlap(" with the windows open from the start");
        Rect selection = openRects().get(EditorWindows.SELECTION);
        Rect toolSettings = openRects().get(EditorWindows.TOOL_SETTINGS);
        assertEquals(190, selection.width(), "their preferred widths while nothing else is open");
        assertEquals(170, toolSettings.width());

        // The reference windows (History open), and each window that fits beside them alone or with History. Library,
        // Tutorial and Wiki are wider at their smallest than the room there is; they open over Selection and Tool
        // Settings.
        List<List<String>> fitting = List.of(List.of(EditorWindows.HISTORY), List.of(EditorWindows.KEYS),
                List.of(EditorWindows.CLIPBOARD), List.of(EditorWindows.NOTIFICATIONS),
                List.of(EditorWindows.HISTORY, EditorWindows.NOTIFICATIONS),
                List.of(EditorWindows.KEYS, EditorWindows.HISTORY));
        for (List<String> ids : fitting) {
            showOnly(ids);
            assertNoOverlap(" with " + ids + " at UI 100%");
        }

        showOnly(List.of(EditorWindows.HISTORY));
        assertEquals(new Rect(4, 26, 150, 174), openRects().get(EditorWindows.SELECTION), "narrower to make room");
        assertEquals(new Rect(158, 26, 140, 174), openRects().get(EditorWindows.HISTORY));
        assertEquals(new Rect(302, 26, 120, 174), openRects().get(EditorWindows.TOOL_SETTINGS));
        showOnly(List.of());
        assertEquals(selection, openRects().get(EditorWindows.SELECTION), "the room back when History closes");
        assertEquals(toolSettings, openRects().get(EditorWindows.TOOL_SETTINGS));
    }

    @Test
    void atUi100TheKeysWindowOpensAsTallAsTheRoomThereIs() {
        scale.set(100);
        showOnly(List.of(EditorWindows.KEYS));
        Rect keys = openRects().get(EditorWindows.KEYS);
        Rect work = ui.windows().workArea();
        assertEquals(work.height() - 8, keys.height(), "from under the top bar to over the hint line: " + keys);
        assertTrue(keys.height() > 3 * 80 / 2, "well over its minimum height (it opened at 80 before)");
    }

    @Test
    void atUi50AnyTwoWindowsOpenedBesideTheOthersOverlapNothing() {
        scale.set(50);
        frame();
        for (int i = 0; i < ON_DEMAND.size(); i++) {
            for (int j = i; j < ON_DEMAND.size(); j++) {
                List<String> ids = List.of(ON_DEMAND.get(i), ON_DEMAND.get(j));
                showOnly(ids);
                assertNoOverlap(" with " + ids + " at UI 50%");
            }
        }
        showOnly(List.of(EditorWindows.KEYS, EditorWindows.HISTORY, EditorWindows.LIBRARY, EditorWindows.CLIPBOARD,
                EditorWindows.NOTIFICATIONS));
        assertNoOverlap(" with every window of the View menu at UI 50%");
    }

    /** Four two-line toasts, as many as show at once. */
    private void fourToasts() {
        for (int i = 0; i < ToastStack.MAX; i++) {
            toasts.show(Notice.Level.INFO, "a message long enough to wrap onto a second line of the toast " + i);
        }
        frame();
        frame(); // the column's height is the last frame's
    }

    private void assertToastsLeaveTheTitleBarsFree(String at) {
        List<Rect> drawn = toasts.drawnRects();
        assertEquals(ToastStack.MAX, drawn.size(), "every toast shows" + at);
        Rect work = ui.windows().workArea();
        for (Rect toast : drawn) {
            assertTrue(toast.y() >= work.y() && toast.bottom() <= work.bottom(), toast + " in the work area" + at);
            for (Window window : ui.windows().windows()) {
                if (window.isOpen()) {
                    assertFalse(toast.intersects(window.titleBarRect(Theme.DARK)),
                            toast + " covers " + window.id() + "'s title bar" + at);
                }
            }
        }
    }

    @Test
    void toastsLeaveTheWindowsTitleBarsFree() {
        scale.set(100);
        frame();
        fourToasts();
        assertToastsLeaveTheTitleBarsFree(" with the windows open from the start at UI 100%");

        // The reference windows and Notifications, in the column between Selection and Tool Settings, under History.
        showOnly(List.of(EditorWindows.HISTORY, EditorWindows.NOTIFICATIONS));
        fourToasts();
        assertToastsLeaveTheTitleBarsFree(" with History and Notifications at UI 100%");

        scale.set(50);
        frame();
        fourToasts();
        assertToastsLeaveTheTitleBarsFree(" at UI 50%");
        Rect toolSettings = openRects().get(EditorWindows.TOOL_SETTINGS);
        assertEquals(toolSettings.x() - 4, toasts.drawnRects().get(0).right(), "left of Tool Settings, as before");
    }

    @Test
    void resetLayoutPutsEveryOpenWindowBackOnScreenAndLeavesOpenAndCollapsedAlone() {
        for (int percent : List.of(50, 100)) {
            scale.set(percent);
            showOnly(List.of(EditorWindows.KEYS, EditorWindows.HISTORY, EditorWindows.NOTIFICATIONS));
            WindowManager windows = ui.windows();
            // Drag each window far off, over the edges and the top bar, and collapse Keys.
            for (Window window : List.copyOf(windows.windows())) {
                if (window.isOpen()) {
                    Rect at = window.rect();
                    drag(windows, at.x() + 20, at.y() + 5, at.x() + 20 + 300, at.y() + 5 - 40);
                }
            }
            windows.setCollapsed(EditorWindows.KEYS, true);
            frame();
            Map<String, Boolean> open = new LinkedHashMap<>();
            windows.windows().forEach(window -> open.put(window.id(), window.isOpen()));

            windows.resetLayout();
            frame();
            String at = " after Reset layout at UI " + percent + "%";
            Map<String, Boolean> after = new LinkedHashMap<>();
            windows.windows().forEach(window -> after.put(window.id(), window.isOpen()));
            assertEquals(open, after, "the same windows open" + at);
            assertTrue(windows.window(EditorWindows.KEYS).orElseThrow().isCollapsed(), "Keys still collapsed" + at);
            Rect work = windows.workArea();
            for (Window window : windows.windows()) {
                if (window.isOpen()) {
                    assertFalse(window.isPlaced(), window.id() + " at its default place" + at);
                    assertEquals(window.rect(), work.intersect(window.rect()),
                            window.id() + " " + window.rect() + " on screen, in the work area" + at);
                }
            }
            windows.setCollapsed(EditorWindows.KEYS, false);
        }
    }

    private static void drag(WindowManager windows, double fromX, double fromY, double toX, double toY) {
        assertTrue(windows.mouseDown(fromX, fromY, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        windows.mouseDragged(toX, toY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        windows.mouseUp(toX, toY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
}
