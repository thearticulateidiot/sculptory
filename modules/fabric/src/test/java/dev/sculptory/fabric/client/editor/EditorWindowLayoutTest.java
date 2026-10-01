package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.EditorWindowLayout;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowPlacement;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.LayoutStore;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * The editor's default window layout ({@code EditorWindowLayout} through {@code EditorUi}) on the reference screen at
 * UI 100% (426x247 UI units) and 50% (853x494), and at 533x300 and 1280x720; and what a window drawn over another
 * leaves visible.
 */
class EditorWindowLayoutTest {
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
    private static final int[][] SIZES = {{426, 247}, {533, 300}, {853, 494}, {1280, 720}};
    private static final List<String> OPEN_FROM_THE_START = List.of(EditorWindows.SELECTION,
            EditorWindows.TOOL_SETTINGS);
    /**
     * A reference {@code editor-layout.json}, written before windows were saved as placed: every window at the place
     * it had then (Keys at the old default, 4 units over Selection's right edge), History open at the bottom left.
     */
    private static final String REFERENCE_LAYOUT = """
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
            ]}""";

    private final EditorTestRig rig = new EditorTestRig();
    private final UiScale scale = new UiScale();
    private final long now = 1_000;
    private final ToastStack toasts = new ToastStack(() -> now);
    private EditorUi ui;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, TEXT, toasts,
                new EditorUi.Services(Translator.KEYS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, scale));
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(640, 360);
    }

    /** Lays out and draws a frame (for the hint line). */
    private void frame(int width, int height) {
        ui.layout(width, height);
        ui.render(new RecordingGraphics(), 0, 0, now, false);
    }

    private void openAll() {
        for (String id : EditorWindows.MENU) {
            if (!ui.windows().isOpen(id)) {
                ui.toggleWindow(id);
            }
        }
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

    /** The top bar: the HUD element at the top left spanning the screen. */
    private Rect topBar() {
        return ui.hud().shownBounds().stream().filter(r -> r.y() == 0 && r.width() == ui.uiWidth()).findFirst()
                .orElseThrow();
    }

    /** The top of the hint line and palette. */
    private int stripTop() {
        return ui.hud().shownBounds().stream().filter(r -> r.y() > ui.uiHeight() / 2).mapToInt(Rect::y).min()
                .orElseThrow();
    }

    @Test
    void theDefaultLayoutFitsBetweenTheTopBarAndTheHintLineAtEverySize() {
        openAll();
        for (int[] size : SIZES) {
            frame(size[0], size[1]);
            String at = " at " + size[0] + "x" + size[1];
            Rect top = topBar();
            int stripTop = stripTop();
            assertTrue(stripTop < size[1] - 30, "the hint line is shown" + at);
            Map<String, Rect> rects = openRects();
            assertEquals(6, rects.size());
            for (Map.Entry<String, Rect> entry : rects.entrySet()) {
                Rect r = entry.getValue();
                String what = entry.getKey() + " " + r + at;
                assertTrue(r.x() >= 0 && r.right() <= size[0], what + " is on screen");
                assertTrue(r.y() >= top.bottom(), what + " is below the top bar (" + top.bottom() + ")");
                assertTrue(r.bottom() <= stripTop, what + " is above the hint line (" + stripTop + ")");
                for (Rect hud : ui.hud().shownBounds()) {
                    assertFalse(r.intersects(hud), what + " covers the HUD element at " + hud);
                }
            }
            assertFalse(rects.get(EditorWindows.SELECTION).intersects(rects.get(EditorWindows.TOOL_SETTINGS)),
                    "the windows open from the start don't overlap" + at);
        }
    }

    @Test
    void withRoomEveryWindowHasItsOwnPlaceAndAtFullSizeItsPreferredSize() {
        openAll();
        for (int[] size : new int[][] {{853, 494}, {1280, 720}}) {
            frame(size[0], size[1]);
            List<Map.Entry<String, Rect>> rects = new ArrayList<>(openRects().entrySet());
            for (int i = 0; i < rects.size(); i++) {
                for (int j = i + 1; j < rects.size(); j++) {
                    assertFalse(rects.get(i).getValue().intersects(rects.get(j).getValue()),
                            rects.get(i) + " and " + rects.get(j) + " at " + size[0] + "x" + size[1]);
                }
            }
        }
        assertEquals(EditorWindowLayout.TOOL_SETTINGS_MAX_HEIGHT,
                ui.windows().window(EditorWindows.TOOL_SETTINGS).orElseThrow().rect().height(), "up to its limit");
        for (Window window : ui.windows().windows()) {
            if (!window.isOpen()) {
                continue;
            }
            assertEquals(window.spec().size().width(), window.rect().width(), window.id());
            if (!window.id().equals(EditorWindows.TOOL_SETTINGS)) {
                assertEquals(window.spec().size().height(), window.rect().height(), window.id());
            }
        }
    }

    @Test
    void theReferenceScreenAtFiftyAndOneHundredPercent() {
        // 2560x1482 at GUI scale 6: 426x247 GUI pixels.
        scale.set(50);
        frame(426, 247);
        assertEquals(852, ui.uiWidth());
        Rect selection = openRects().get(EditorWindows.SELECTION);
        Rect toolSettings = openRects().get(EditorWindows.TOOL_SETTINGS);
        assertEquals(190, selection.width());
        assertEquals(250, selection.height(), "the preferred size where it fits");
        assertEquals(topBar().bottom() + 4, selection.y());
        assertEquals(new Rect(ui.uiWidth() - 4 - 170, selection.y(), 170, stripTop() - 4 - selection.y()), toolSettings,
                "Tool Settings takes the height there is");

        scale.set(100);
        frame(426, 247);
        selection = openRects().get(EditorWindows.SELECTION);
        assertEquals(stripTop() - 4, selection.bottom(), "as tall as the space allows");
        assertEquals(topBar().bottom() + 4, selection.y());
    }

    @Test
    void notificationsOpensUnderSelectionClearOfTheOtherWindowsAndTheToastsOnTheReferenceScreen() {
        scale.set(50);
        frame(426, 247);
        openAll();
        ui.toggleWindow(EditorWindows.NOTIFICATIONS);
        assertFalse(ui.windows().window(EditorWindows.NOTIFICATIONS).orElseThrow().isPlaced(), "its default place");
        for (int i = 0; i < ToastStack.MAX; i++) {
            toasts.show(Notice.Level.INFO, "a message long enough to wrap onto a second line of the toast " + i);
        }
        frame(426, 247);
        Map<String, Rect> rects = openRects();
        Rect notifications = rects.get(EditorWindows.NOTIFICATIONS);
        Rect selection = rects.get(EditorWindows.SELECTION);
        assertEquals(new Rect(selection.x(), selection.bottom() + 4, selection.width(),
                Math.min(170, stripTop() - 4 - selection.bottom() - 4)), notifications, "under Selection, as wide");
        assertTrue(notifications.height() >= 80, notifications.toString());
        for (Map.Entry<String, Rect> other : rects.entrySet()) {
            if (!other.getKey().equals(EditorWindows.NOTIFICATIONS)) {
                assertFalse(notifications.intersects(other.getValue()), other.toString());
            }
        }
        assertEquals(ToastStack.MAX, toasts.drawnRects().size());
        for (Rect toast : toasts.drawnRects()) {
            assertFalse(notifications.intersects(toast), "clear of the toast at " + toast);
        }

        scale.set(100);
        frame(426, 247);
        Rect small = openRects().get(EditorWindows.NOTIFICATIONS);
        assertTrue(small.y() >= topBar().bottom() && small.bottom() <= stripTop(), "in the work area: " + small);
    }

    @Test
    void onTheReferenceLayoutNotificationsOpensClearOfHistoryAndStaysThere() {
        scale.set(50);
        frame(426, 247);
        ui.windows().restore(LayoutStore.parse(REFERENCE_LAYOUT, 50), 50);
        for (int i = 0; i < ToastStack.MAX; i++) {
            toasts.show(Notice.Level.INFO, "a message long enough to wrap onto a second line of the toast " + i);
        }
        frame(426, 247);
        Rect history = openRects().get(EditorWindows.HISTORY);
        assertTrue(history.x() == 4 && history.y() > ui.uiHeight() / 2 && history.bottom() <= stripTop(),
                "History at the bottom left: " + history);

        ui.toggleWindow(EditorWindows.NOTIFICATIONS);
        frame(426, 247);
        frame(426, 247); // the toast column's height is the last frame's
        Map<String, Rect> rects = openRects();
        assertEquals(List.of(EditorWindows.SELECTION, EditorWindows.HISTORY, EditorWindows.TOOL_SETTINGS,
                EditorWindows.NOTIFICATIONS), List.copyOf(rects.keySet()));
        Rect notifications = rects.get(EditorWindows.NOTIFICATIONS);
        assertEquals(new Size(190, 170), new Size(notifications.width(), notifications.height()), "its full size");
        assertTrue(notifications.y() >= topBar().bottom() && notifications.bottom() <= stripTop(), notifications.toString());
        for (Map.Entry<String, Rect> other : rects.entrySet()) {
            if (!other.getKey().equals(EditorWindows.NOTIFICATIONS)) {
                assertFalse(notifications.intersects(other.getValue()), "clear of " + other);
            }
        }
        for (Rect toast : toasts.drawnRects()) {
            assertFalse(notifications.intersects(toast), "clear of the toast at " + toast);
        }

        Window window = ui.windows().window(EditorWindows.NOTIFICATIONS).orElseThrow();
        assertFalse(window.isPlaced(), "not a window the user put there");
        assertFalse(ui.windows().snapshot().window(EditorWindows.NOTIFICATIONS).orElseThrow().placed(), "saved so");

        // It stays where it opened while other windows move, and goes back to its default place on Reset layout (which
        // leaves it and History open, History at its own default place).
        drag(ui.windows(), history.x() + 30, history.y() + 8, history.x() + 250, history.y() + 8);
        frame(426, 247);
        assertEquals(notifications, openRects().get(EditorWindows.NOTIFICATIONS));
        ui.windows().resetLayout();
        frame(426, 247);
        rects = openRects();
        assertTrue(rects.containsKey(EditorWindows.HISTORY) && rects.containsKey(EditorWindows.NOTIFICATIONS));
        Rect selection = rects.get(EditorWindows.SELECTION);
        assertEquals(selection.bottom() + 4, rects.get(EditorWindows.NOTIFICATIONS).y(), "under Selection again");
        assertFalse(rects.get(EditorWindows.NOTIFICATIONS).intersects(rects.get(EditorWindows.HISTORY)));
    }

    @Test
    void onTheReferenceLayoutKeysOpensBesideSelection() {
        scale.set(50);
        frame(426, 247);
        ui.windows().restore(LayoutStore.parse(REFERENCE_LAYOUT, 50), 50);
        ui.toggleWindow(EditorWindows.KEYS);
        frame(426, 247);
        Map<String, Rect> rects = openRects();
        Rect keys = rects.get(EditorWindows.KEYS);
        assertEquals(rects.get(EditorWindows.SELECTION).right() + 4, keys.x(), "4 right of Selection");
        for (Map.Entry<String, Rect> other : rects.entrySet()) {
            if (!other.getKey().equals(EditorWindows.KEYS)) {
                assertFalse(keys.intersects(other.getValue()), "clear of " + other);
            }
        }
    }

    @Test
    void onTheReferenceLayoutNotificationsFindsRoomAgainAtAnotherUiSizeAndAfterHistoryCloses() {
        scale.set(50);
        frame(426, 247);
        ui.windows().restore(LayoutStore.parse(REFERENCE_LAYOUT, 50), 50);
        ui.toggleWindow(EditorWindows.NOTIFICATIONS);
        frame(426, 247);
        Rect at50 = openRects().get(EditorWindows.NOTIFICATIONS);
        Rect selection = openRects().get(EditorWindows.SELECTION);
        assertTrue(at50.y() != selection.bottom() + 4, "not under Selection, where History is: " + at50);

        // At UI 100% (its own arrangement: the default layout) the place found at 50% is not kept (nor saved): it is
        // decided again for the smaller work area, the same as opening it at 100% (with four windows on 426x247 units
        // nothing is clear there), and back at 50% it is where it was.
        scale.set(100);
        frame(426, 247);
        Rect at100 = openRects().get(EditorWindows.NOTIFICATIONS);
        assertTrue(at100.y() >= topBar().bottom() && at100.bottom() <= stripTop(), "in the work area: " + at100);
        ui.toggleWindow(EditorWindows.NOTIFICATIONS);
        ui.toggleWindow(EditorWindows.NOTIFICATIONS);
        frame(426, 247);
        assertEquals(at100, openRects().get(EditorWindows.NOTIFICATIONS), "as if opened at 100%");
        assertFalse(ui.windows().window(EditorWindows.NOTIFICATIONS).orElseThrow().isPlaced());
        scale.set(50);
        frame(426, 247);
        assertEquals(at50, openRects().get(EditorWindows.NOTIFICATIONS));

        // History closed: Notifications opens at its default place under Selection again, not at the fallback.
        ui.toggleWindow(EditorWindows.NOTIFICATIONS);
        ui.toggleWindow(EditorWindows.HISTORY);
        ui.toggleWindow(EditorWindows.NOTIFICATIONS);
        frame(426, 247);
        Window notifications = ui.windows().window(EditorWindows.NOTIFICATIONS).orElseThrow();
        assertEquals(Optional.empty(), notifications.clearPlace(), "its default place");
        assertTrue(Math.abs(notifications.rect().y() - (selection.bottom() + 4)) <= 2 && notifications.rect().x() == 4,
                "under Selection: " + notifications.rect() + " " + selection);
    }

    @Test
    void notificationsClearPlaceKeepsOffTheToastColumnAndUsesWhereSelectionAndToolSettingsAre() {
        scale.set(50);
        frame(426, 247);
        Map<String, WindowSpec> specs = new LinkedHashMap<>();
        ui.windows().windows().forEach(window -> specs.put(window.id(), window.spec()));
        Rect work = ui.windows().workArea();
        EditorWindowLayout plain = new EditorWindowLayout();
        // Selection, Tool Settings and Notifications at their default places; the user put History under Selection.
        Set<String> shown = Set.of(EditorWindows.SELECTION, EditorWindows.TOOL_SETTINGS, EditorWindows.NOTIFICATIONS);
        Map<String, Rect> places = plain.place(work, specs, shown);
        Rect selection = places.get(EditorWindows.SELECTION);
        Rect toolSettings = places.get(EditorWindows.TOOL_SETTINGS);
        // A window the user put over Notifications' default place, under Selection.
        Map<String, Rect> occupied = new LinkedHashMap<>();
        occupied.put(EditorWindows.SELECTION, selection);
        occupied.put(EditorWindows.TOOL_SETTINGS, toolSettings);
        occupied.put(EditorWindows.HISTORY, places.get(EditorWindows.NOTIFICATIONS));
        Rect first = plain.clearPlace(work, specs, shown, EditorWindows.NOTIFICATIONS, occupied).orElseThrow();
        assertEquals(selection.right() + 4, first.x(), "the bottom left of the space between");

        // The toast column reaching down over that spot: the next clear one.
        EditorWindowLayout withToasts = new EditorWindowLayout(() -> first);
        Rect avoiding = withToasts.clearPlace(work, specs, shown, EditorWindows.NOTIFICATIONS, occupied).orElseThrow();
        assertFalse(avoiding.intersects(first), avoiding + " clear of the toasts at " + first);
        assertEquals(toolSettings.x() - 4, avoiding.right(), "the bottom right of the space between");

        // Tool Settings made narrower by the user, and something at the default space's right end: the bottom right
        // of the space between where the two windows are drawn is clear.
        Rect narrowTools = Rect.ofEdges(toolSettings.x() + 30, toolSettings.y(), toolSettings.right(),
                toolSettings.bottom());
        occupied.put(EditorWindows.TOOL_SETTINGS, narrowTools);
        occupied.put(EditorWindows.CLIPBOARD, new Rect(avoiding.x(), avoiding.y(), 10, avoiding.height()));
        Rect drawnBetween = withToasts.clearPlace(work, specs, shown, EditorWindows.NOTIFICATIONS, occupied).orElseThrow();
        assertEquals(narrowTools.x() - 4, drawnBetween.right(), drawnBetween.toString());
        for (Rect other : occupied.values()) {
            assertFalse(drawnBetween.intersects(other), drawnBetween + " clear of " + other);
        }
    }

    @Test
    void anOldLayoutsClosedWindowTheUserMovedStaysWhereTheyPutIt() {
        scale.set(50);
        frame(426, 247);
        // Keys moved on main (which still writes no "placed"), then closed: not an old default place.
        String moved = REFERENCE_LAYOUT.replace("\"x\": 190, \"y\": 26, \"width\": 230, \"height\": 220",
                "\"x\": 300, \"y\": 60, \"width\": 230, \"height\": 220");
        assertTrue(LayoutStore.parse(moved, 50).arrangement(50).containsKey(EditorWindows.KEYS));
        ui.windows().restore(LayoutStore.parse(moved, 50), 50);
        ui.toggleWindow(EditorWindows.KEYS);
        frame(426, 247);
        Rect keys = openRects().get(EditorWindows.KEYS);
        assertEquals(new Rect(300, 60, 230, 220), keys, "where the user left it");
        assertTrue(ui.windows().snapshot().window(EditorWindows.KEYS).orElseThrow().placed(), "and saved placed");

        // A real user's file: Keys at the old default (x 190) is unplaced and opens 4 right of Selection.
        Map<String, WindowPlacement> reference = LayoutStore.parse(REFERENCE_LAYOUT, 50).arrangement(50);
        assertFalse(reference.containsKey(EditorWindows.KEYS));
        assertFalse(reference.containsKey(EditorWindows.LIBRARY));
        assertFalse(reference.containsKey(EditorWindows.CLIPBOARD));
        assertTrue(reference.containsKey(EditorWindows.HISTORY), "open: kept where it is seen");
    }

    @Test
    void toastsStartUnderTheTopBarLeftOfTheWindowAtTheTopRight() {
        scale.set(50);
        for (int i = 0; i < ToastStack.MAX; i++) {
            toasts.show(Notice.Level.INFO, "a message long enough to wrap onto a second line of the toast " + i);
        }
        frame(426, 247);
        frame(426, 247); // the column's height is the last frame's
        Rect toolSettings = openRects().get(EditorWindows.TOOL_SETTINGS);
        List<Rect> drawn = toasts.drawnRects();
        assertEquals(ToastStack.MAX, drawn.size());
        assertEquals(topBar().bottom() + 4, drawn.get(0).y(), "under the top bar");
        for (Rect toast : drawn) {
            assertEquals(toolSettings.x() - 4, toast.right(), "left of Tool Settings: " + toast);
            for (Rect window : openRects().values()) {
                assertFalse(toast.intersects(window), toast + " covers " + window);
            }
        }

        ui.toggleWindowsHidden();
        frame(426, 247);
        assertEquals(ui.uiWidth() - 4, toasts.drawnRects().get(0).right(), "windows hidden: at the right edge");
    }

    @Test
    void aWindowMovedKeepsOffTheHintLineInAShorterGameWindowAndComesBack() {
        scale.set(50);
        frame(426, 247);
        WindowManager windows = ui.windows();
        Rect start = windows.window(EditorWindows.SELECTION).orElseThrow().rect();
        int down = stripTop() - 10 - start.bottom();
        drag(windows, start.x() + 30, start.y() + 8, start.x() + 30, start.y() + 8 + down);
        Rect moved = windows.window(EditorWindows.SELECTION).orElseThrow().rect();
        assertEquals(start.y() + down, moved.y());

        // A shorter game window. (Another UI size shows that size's own arrangement: UiSizeLayoutsTest.)
        frame(426, 200);
        Rect small = windows.window(EditorWindows.SELECTION).orElseThrow().rect();
        assertTrue(small.bottom() <= stripTop() && small.y() >= topBar().bottom(), small.toString());

        frame(426, 247);
        assertEquals(moved, windows.window(EditorWindows.SELECTION).orElseThrow().rect(), "back where it was put");

        windows.resetLayout();
        frame(426, 247);
        assertEquals(start, windows.window(EditorWindows.SELECTION).orElseThrow().rect());
    }

    @Test
    void noTextShowsThroughAWindowDrawnOverIt() {
        // History dragged over Keys (whose search field draws plainly without a running client).
        ui.toggleWindow(EditorWindows.SELECTION);
        ui.toggleWindow(EditorWindows.TOOL_SETTINGS);
        ui.toggleWindow(EditorWindows.KEYS);
        ui.toggleWindow(EditorWindows.HISTORY);
        frame(853, 494);
        Rect keys = openRects().get(EditorWindows.KEYS);
        Rect history = openRects().get(EditorWindows.HISTORY);
        drag(ui.windows(), history.x() + 30, history.y() + 8, keys.x() + 32, keys.y() + 48);
        RecordingGraphics g = new RecordingGraphics();
        ui.render(g, 0, 0, now, false);

        Rect over = openRects().get(EditorWindows.HISTORY);
        assertFalse(g.textsPaintedOver(over).isEmpty(), "Keys text lies under History");
        assertEquals(List.of(), g.textsShowingThrough(over));
    }

    @Test
    void draggingAWindowShortenedByAShorterGameWindowKeepsItsFittedSize() {
        scale.set(50);
        frame(426, 247);
        WindowManager windows = ui.windows();
        Rect start = windows.window(EditorWindows.SELECTION).orElseThrow().rect();
        drag(windows, start.x() + 30, start.y() + 8, start.x() + 30, start.y() + 60);
        frame(426, 150);
        Window selection = windows.window(EditorWindows.SELECTION).orElseThrow();
        Rect fitted = selection.rect();
        assertTrue(fitted.height() < 250, "shortened to fit: " + fitted);

        assertTrue(windows.mouseDown(fitted.x() + 30, fitted.y() + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        assertEquals(fitted, selection.rect(), "a press doesn't move it");
        windows.mouseDragged(fitted.x() + 45, fitted.y() + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        windows.mouseUp(fitted.x() + 45, fitted.y() + 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        Rect after = selection.rect();
        assertEquals(fitted.height(), after.height(), "the drag keeps the fitted size");
        assertTrue(after.y() >= topBar().bottom() && after.bottom() <= stripTop(), after.toString());
        assertFalse(selection.isOverReserved());
    }

    private static void drag(WindowManager windows, double fromX, double fromY, double toX, double toY) {
        assertTrue(windows.mouseDown(fromX, fromY, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        windows.mouseDragged(toX, toY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        windows.mouseUp(toX, toY, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }
}
