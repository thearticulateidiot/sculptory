package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** F6 and Shift+F6: the keyboard into the next or previous open window, in screen order ({@link WindowManager}). */
class WindowFocusTest {
    private final WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK, UnaryOperator.identity());
    /** Each window's first and second button. */
    private final Map<String, Button[]> buttons = new HashMap<>();

    private void add(String id, Corner corner, int x, int y) {
        Button first = new Button(id + " 1", null);
        Button second = new Button(id + " 2", null);
        buttons.put(id, new Button[] {first, second});
        windows.register(WindowSpec.builder(id, "title." + id, () -> Column.of(Label.of(id), first, second))
                .anchor(corner, x, y).size(120, 90).minSize(60, 40).build());
    }

    @BeforeEach
    void build() {
        // Registered in another order than they stand on screen, left to right: left, middle-top, middle-low, right.
        add("right", Corner.TOP_RIGHT, 10, 10);
        add("middle-low", Corner.TOP_LEFT, 300, 200);
        add("left", Corner.TOP_LEFT, 10, 10);
        add("middle-top", Corner.TOP_LEFT, 300, 20);
        windows.layout(960, 540);
    }

    private Button focused() {
        return (Button) windows.context().focused();
    }

    private String top() {
        List<Window> all = windows.windows();
        return all.get(all.size() - 1).id();
    }

    @Test
    void f6GoesLeftToRightThenTopToBottomAndWraps() {
        for (String id : List.of("left", "middle-top", "middle-low", "right", "left")) {
            assertTrue(windows.focusNextWindow(true));
            assertSame(buttons.get(id)[0], focused(), "the first control of " + id);
            assertEquals(id, top(), id + " comes to the front");
        }
    }

    @Test
    void shiftF6GoesBackAndStartsFromTheLastWindow() {
        assertTrue(windows.focusNextWindow(false));
        assertSame(buttons.get("right")[0], focused(), "nothing focused: Shift+F6 starts at the last");
        windows.focusNextWindow(false);
        assertSame(buttons.get("middle-low")[0], focused());
        windows.focusNextWindow(true);
        assertSame(buttons.get("right")[0], focused());
    }

    @Test
    void theStepStartsFromTheWindowHoldingTheFocusedControlAndTabStaysInside() {
        windows.context().setFocus(buttons.get("middle-top")[1]);
        windows.focusNextWindow(true);
        assertSame(buttons.get("middle-low")[0], focused());
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
        assertSame(buttons.get("middle-low")[1], focused(), "Tab moves within the window");
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertNull(windows.context().focused(), "Esc clears the focus");
    }

    @Test
    void closedCollapsedAndControlFreeWindowsAreSkipped() {
        windows.close("middle-top");
        windows.setCollapsed("middle-low", true);
        buttons.get("right")[0].setEnabled(false);
        buttons.get("right")[1].setEnabled(false);
        windows.layout(960, 540);
        windows.focusNextWindow(true);
        assertSame(buttons.get("left")[0], focused());
        windows.focusNextWindow(true);
        assertSame(buttons.get("left")[0], focused(), "the only window with a control that takes the keyboard");
    }

    @Test
    void nothingHappensWithTheWindowsHiddenOrWithoutControls() {
        windows.setAllHidden(true);
        assertFalse(windows.focusNextWindow(true));
        assertNull(windows.context().focused());
        windows.setAllHidden(false);
        for (String id : List.of("left", "middle-top", "middle-low", "right")) {
            windows.close(id);
        }
        assertFalse(windows.focusNextWindow(true));
    }
}
