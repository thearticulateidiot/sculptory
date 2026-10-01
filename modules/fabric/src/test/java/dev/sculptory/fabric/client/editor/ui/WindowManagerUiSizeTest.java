package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts.Shown;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowPlacement;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * {@link WindowManager} with one arrangement per UI size: switching sizes, moving at one size, Reset layout, the
 * shared open and collapsed flags. (The screen stays the same size here; the editor's UI units change with the size.)
 */
class WindowManagerUiSizeTest {
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    private static final int W = 960;
    private static final int H = 540;
    private static final List<WindowSpec> SPECS = List.of(
            spec("a", Corner.TOP_LEFT, 10, 10, 100, 80),
            spec("b", Corner.TOP_RIGHT, 10, 10, 120, 90),
            spec("c", Corner.BOTTOM_LEFT, 10, 10, 100, 80));

    private static WindowSpec spec(String id, Corner corner, int x, int y, int width, int height) {
        return WindowSpec.builder(id, "title." + id, () -> Column.of(new Button("OK", null)))
                .anchor(corner, x, y).size(width, height).minSize(60, 40).build();
    }

    /** The three windows, laid out at UI size {@code percent}. */
    private static WindowManager manager(int percent) {
        WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK, UnaryOperator.identity());
        windows.arrangeForUiSize(percent);
        SPECS.forEach(windows::register);
        windows.layout(W, H);
        return windows;
    }

    private static void switchTo(WindowManager windows, int percent) {
        windows.arrangeForUiSize(percent);
        windows.layout(W, H);
    }

    /** Drags a window by its title bar, 5 units in from its top left corner, by (dx, dy). */
    private static void move(WindowManager windows, String id, int dx, int dy) {
        Rect at = rect(windows, id);
        assertTrue(windows.mouseDown(at.x() + 5, at.y() + 5, LEFT, 0));
        windows.mouseDragged(at.x() + 5 + dx, at.y() + 5 + dy, LEFT);
        windows.mouseUp(at.x() + 5 + dx, at.y() + 5 + dy, LEFT);
    }

    private static Rect rect(WindowManager windows, String id) {
        return windows.window(id).orElseThrow().rect();
    }

    private static Map<String, Rect> rects(WindowManager windows) {
        Map<String, Rect> rects = new LinkedHashMap<>();
        for (Window window : windows.windows()) {
            rects.put(window.id(), window.rect());
        }
        return rects;
    }

    private static Map<String, LayoutState.WindowState> states(WindowManager windows) {
        Map<String, LayoutState.WindowState> states = new LinkedHashMap<>();
        windows.snapshot().windows().forEach(state -> states.put(state.id(), state));
        return states;
    }

    @Test
    void eachSizeKeepsItsOwnArrangementAndSwitchingBackRestoresItExactly() {
        WindowManager windows = manager(50);
        Map<String, Rect> defaults = rects(windows);
        move(windows, "a", 200, 100);
        move(windows, "b", -300, 50);
        Map<String, Rect> at50 = rects(windows);
        Map<String, LayoutState.WindowState> state50 = states(windows);

        // 100% was never arranged: every window at its default place.
        switchTo(windows, 100);
        assertEquals(defaults, rects(windows));
        move(windows, "a", 400, 300);
        Map<String, Rect> at100 = rects(windows);
        assertNotEquals(at50.get("a"), at100.get("a"));

        switchTo(windows, 50);
        assertEquals(at50, rects(windows), "moving at 100% left 50% alone");
        assertEquals(state50, states(windows), "exactly: anchors, sizes and flags (the order is shared by all sizes)");
        switchTo(windows, 100);
        assertEquals(at100, rects(windows));
        assertEquals(defaults.get("b"), rect(windows, "b"), "b was moved at 50% only");

        SizedLayouts saved = windows.layouts();
        assertEquals(Set.of("a", "b"), saved.arrangement(50).keySet());
        assertEquals(List.of("a"), List.copyOf(saved.arrangement(100).keySet()));
        assertEquals(Map.of(), saved.arrangement(75));
    }

    @Test
    void openCollapsedAndOrderAreTheSameAtEverySize() {
        WindowManager windows = manager(50);
        windows.setCollapsed("a", true);
        windows.close("c");
        windows.bringToFront("a");
        List<String> order = windows.windows().stream().map(Window::id).toList();

        switchTo(windows, 100);
        assertTrue(windows.window("a").orElseThrow().isCollapsed());
        assertFalse(windows.isOpen("c"), "switching never opens or closes a window");
        assertEquals(order, windows.windows().stream().map(Window::id).toList());

        windows.open("c");
        windows.setCollapsed("a", false);
        switchTo(windows, 50);
        assertTrue(windows.isOpen("c"));
        assertFalse(windows.window("a").orElseThrow().isCollapsed());
        assertEquals(List.of(new Shown("b", true, false), new Shown("a", true, false), new Shown("c", true, false)),
                windows.layouts().windows());
    }

    @Test
    void resetLayoutResetsOnlyTheCurrentSizesArrangement() {
        WindowManager windows = manager(50);
        move(windows, "a", 200, 100);
        Map<String, Rect> at50 = rects(windows);
        switchTo(windows, 100);
        Map<String, Rect> defaults = rects(windows);
        move(windows, "a", 300, 0);
        move(windows, "b", -300, 200);
        switchTo(windows, 75);
        move(windows, "c", 100, -100);
        WindowPlacement c75 = windows.layouts().arrangement(75).get("c");

        switchTo(windows, 100);
        windows.resetLayout();
        assertEquals(defaults, rects(windows), "100% back at the default layout");
        SizedLayouts saved = windows.layouts();
        assertFalse(saved.uiSizes().containsKey("100"));
        assertEquals(Map.of("c", c75), saved.arrangement(75), "75% keeps its arrangement");

        switchTo(windows, 50);
        assertEquals(at50, rects(windows), "and so does 50%");
    }

    @Test
    void collapsingAtOneSizeKeepsTheTitleBarWhereItIsAtTheOthers() {
        WindowManager windows = manager(50);
        move(windows, "a", 100, 350); // into the lower half: anchored at the bottom
        Rect expanded50 = rect(windows, "a");
        assertTrue(windows.layouts().arrangement(50).get("a").anchor().isBottom());
        windows.setCollapsed("a", true);
        Rect title50 = rect(windows, "a");
        assertEquals(expanded50.y(), title50.y(), "the title bar stays where it is");

        // Expanded at 100% (collapsed is shared): at 50% it opens down from its title bar, not up from it.
        switchTo(windows, 100);
        windows.setCollapsed("a", false);
        switchTo(windows, 50);
        assertEquals(expanded50, rect(windows, "a"));

        // Collapsed at 100%: at 50% the title bar is at the top of where the window was.
        switchTo(windows, 100);
        windows.setCollapsed("a", true);
        switchTo(windows, 50);
        assertEquals(title50, rect(windows, "a"));

        // Reset layout at 100% leaves it collapsed, and its place at 50% as it was.
        switchTo(windows, 100);
        windows.resetLayout();
        assertTrue(windows.window("a").orElseThrow().isCollapsed());
        switchTo(windows, 50);
        assertEquals(title50, rect(windows, "a"));
        windows.setCollapsed("a", false);
        assertEquals(expanded50, rect(windows, "a"));
    }

    @Test
    void resetLayoutKeepsTheFlagsOfWindowsNotRegisteredAndDropsTheirPlaceAtThisSize() {
        WindowPlacement at50 = new WindowPlacement(Corner.TOP_LEFT, 300, 200, 150, 100, false);
        SizedLayouts saved = new SizedLayouts(List.of(new Shown("later", true, true)),
                Map.of("50", Map.of("later", at50), "100", Map.of("later", at50)));
        WindowManager windows = manager(100);
        windows.restore(saved, 100);
        windows.resetLayout();
        SizedLayouts reset = windows.layouts();
        assertTrue(reset.windows().contains(new Shown("later", true, true)), "still open and collapsed");
        assertEquals(Map.of(), reset.arrangement(100), "its default place at 100%");
        assertEquals(Map.of("later", at50), reset.arrangement(50), "50% keeps its arrangement");
        Window later = windows.register(WindowSpec.builder("later", "title.later", Column::new).size(120, 90).build());
        assertTrue(later.isOpen());
        assertTrue(later.isCollapsed());
        assertFalse(later.isPlaced());
    }

    @Test
    void aDragInProgressIsCancelledBySwitching() {
        WindowManager windows = manager(50);
        Rect start = rect(windows, "a");
        assertTrue(windows.mouseDown(start.x() + 5, start.y() + 5, LEFT, 0));
        windows.mouseDragged(start.x() + 200, start.y() + 100, LEFT);
        assertNotEquals(start, rect(windows, "a"));

        switchTo(windows, 100);
        assertFalse(windows.isInteracting());
        windows.mouseDragged(start.x() + 300, start.y() + 100, LEFT);
        windows.mouseUp(start.x() + 300, start.y() + 100, LEFT);
        assertEquals(start, rect(windows, "a"), "the rest of the drag moves nothing");
        switchTo(windows, 50);
        assertEquals(start, rect(windows, "a"), "at 50% it is where the drag started");
        assertEquals(Map.of(), windows.layouts().arrangement(50));
    }

    @Test
    void savedStatesOfWindowsNotRegisteredYetFollowTheirSize() {
        WindowPlacement at50 = new WindowPlacement(Corner.TOP_LEFT, 300, 200, 150, 100, false);
        WindowPlacement at100 = new WindowPlacement(Corner.BOTTOM_RIGHT, 20, 30, 110, 70, false);
        SizedLayouts saved = new SizedLayouts(List.of(new Shown("later", true, false)),
                Map.of("50", Map.of("later", at50), "100", Map.of("later", at100)));
        WindowManager windows = manager(50);
        windows.restore(saved, 50);
        switchTo(windows, 100);
        windows.register(spec("later", Corner.TOP_LEFT, 0, 0, 100, 80));
        windows.layout(W, H);
        assertEquals(new Rect(W - 20 - 110, H - 30 - 70, 110, 70), rect(windows, "later"), "its place at 100%");
        switchTo(windows, 50);
        assertEquals(new Rect(300, 200, 150, 100), rect(windows, "later"), "its place at 50%");
        assertEquals(saved.arrangement(100), windows.layouts().arrangement(100));

        // A saved state that never registers is kept as saved.
        WindowManager without = manager(100);
        without.restore(saved, 100);
        switchTo(without, 50);
        switchTo(without, 75);
        assertTrue(without.layouts().windows().contains(new Shown("later", true, false)));
        assertEquals(saved.arrangement(50), without.layouts().arrangement(50));
        assertEquals(saved.arrangement(100), without.layouts().arrangement(100));
    }

    @Test
    void restoringReplacesEverySizeAndShowsTheGivenOne() {
        WindowManager first = manager(50);
        move(first, "a", 200, 100);
        first.close("c");
        switchTo(first, 100);
        move(first, "b", -200, 150);
        SizedLayouts saved = first.layouts();

        WindowManager second = manager(100);
        move(second, "c", 50, -50);
        second.restore(saved, 100);
        second.layout(W, H);
        assertEquals(rects(first), rects(second));
        assertFalse(second.isOpen("c"));
        switchTo(first, 50);
        switchTo(second, 50);
        assertEquals(rects(first), rects(second));
        assertEquals(first.layouts(), second.layouts());
    }

    @Test
    void theFirstSizeGivenTakesTheWindowsAsTheyAre() {
        WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK, UnaryOperator.identity());
        SPECS.forEach(windows::register);
        windows.layout(W, H);
        move(windows, "a", 200, 100);
        Rect moved = rect(windows, "a");
        assertEquals(0, windows.uiSize());
        assertEquals(Map.of(), windows.layouts().uiSizes(), "no size yet: nothing saved as one");

        windows.arrangeForUiSize(50);
        windows.layout(W, H);
        assertEquals(moved, rect(windows, "a"));
        assertEquals(List.of("a"), List.copyOf(windows.layouts().arrangement(50).keySet()));
        windows.arrangeForUiSize(50);
        assertEquals(moved, rect(windows, "a"), "the same size again changes nothing");

        assertThrows(IllegalArgumentException.class, () -> windows.arrangeForUiSize(0));
        assertThrows(IllegalArgumentException.class, () -> windows.restore(SizedLayouts.EMPTY, -50));
    }
}
