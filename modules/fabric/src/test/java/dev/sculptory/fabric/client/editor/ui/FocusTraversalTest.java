package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class FocusTraversalTest {
    private final AtomicInteger aClicks = new AtomicInteger();
    private Button a;
    private Toggle b;
    private Slider c;
    private Row row;
    private CollapsibleSection s;
    private Button d;
    private TextInput e;
    private Button f;
    private Column root;

    @BeforeEach
    void build() {
        a = new Button("A", aClicks::incrementAndGet);
        b = new Toggle("B", false, null);
        b.setEnabled(false);
        c = Slider.ofInt("C", 0, 10, 5, null);
        row = Row.of(b, c);
        d = new Button("D", null);
        s = new CollapsibleSection("S", Column.of(d), false);
        e = new TextInput("", null);
        f = new Button("F", null);
        f.setVisible(false);
        root = Column.of(a, row, Label.of("not focusable"), s, e, f);
    }

    @Test
    void orderIsTreeOrderSkippingDisabledHiddenAndCollapsedNodes() {
        assertEquals(List.of(a, c, s, e), FocusTraversal.focusables(root));
    }

    @Test
    void expandingASectionAddsItsContent() {
        s.setExpanded(true);
        assertEquals(List.of(a, c, s, d, e), FocusTraversal.focusables(root));
    }

    @Test
    void disabledContainerRemovesItsWholeSubtree() {
        row.setEnabled(false);
        assertEquals(List.of(a, s, e), FocusTraversal.focusables(root));
    }

    @Test
    void nextAndPreviousWrapAround() {
        assertSame(a, FocusTraversal.next(root, null));
        assertSame(c, FocusTraversal.next(root, a));
        assertSame(a, FocusTraversal.next(root, e));
        assertSame(e, FocusTraversal.previous(root, a));
        assertSame(e, FocusTraversal.previous(root, null));
        assertSame(a, FocusTraversal.next(root, Label.of("stranger")), "unknown current starts at the first");
    }

    @Test
    void treeWithoutFocusableNodesHasNoNext() {
        Column plain = Column.of(Label.of("x"), Label.of("y"));
        assertNull(FocusTraversal.next(plain, null));
        assertNull(FocusTraversal.previous(plain, null));
    }

    @Test
    void contextOnlyFocusesFocusableEnabledShownNodes() {
        UiContext ctx = FakeTextMeasure.context();
        ctx.setFocus(Label.of("x"));
        assertNull(ctx.focused());
        ctx.setFocus(b);
        assertNull(ctx.focused(), "disabled");
        ctx.setFocus(f);
        assertNull(ctx.focused(), "hidden");
        ctx.setFocus(a);
        assertSame(a, ctx.focused());
    }

    @Test
    void focusChangesNotifyBothNodes() {
        List<String> events = new ArrayList<>();
        class Probe extends Label {
            Probe(String name) {
                super(name);
            }

            @Override
            public boolean isFocusable() {
                return true;
            }

            @Override
            protected void onFocusChanged(UiContext ctx, boolean focused) {
                events.add(text() + (focused ? "+" : "-"));
            }
        }
        Probe one = new Probe("one");
        Probe two = new Probe("two");
        UiContext ctx = FakeTextMeasure.context();
        ctx.setFocus(one);
        ctx.setFocus(two);
        ctx.clearFocus();
        assertEquals(List.of("one+", "one-", "two+", "two-"), events);
    }

    private WindowManager twoWindows() {
        WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK, UnaryOperator.identity());
        windows.register(WindowSpec.builder("first", "first", () -> root)
                .anchor(Corner.TOP_LEFT, 0, 0).size(200, 200).build());
        windows.register(WindowSpec.builder("second", "second", () -> Column.of(new Button("X", null)))
                .anchor(Corner.TOP_RIGHT, 0, 0).size(200, 200).build());
        windows.layout(800, 600);
        return windows;
    }

    private static void click(WindowManager windows, Node node) {
        double x = node.bounds().x() + 2;
        double y = node.bounds().y() + 2;
        windows.mouseDown(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        windows.mouseUp(x, y, GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    @Test
    void clickFocusesTextInputAndTabCyclesWithinItsWindow() {
        WindowManager windows = twoWindows();
        UiContext ctx = windows.context();
        click(windows, e);
        assertSame(e, ctx.focused());

        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
        assertSame(a, ctx.focused(), "wraps to the first control of the same window");
        windows.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
        assertSame(c, ctx.focused());
        windows.keyPressed(GLFW.GLFW_KEY_TAB, 0, GLFW.GLFW_MOD_SHIFT);
        assertSame(a, ctx.focused());
        windows.keyPressed(GLFW.GLFW_KEY_TAB, 0, GLFW.GLFW_MOD_SHIFT);
        assertSame(e, ctx.focused());
    }

    @Test
    void tabWithoutFocusIsLeftForTheEditor() {
        WindowManager windows = twoWindows();
        assertFalse(windows.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0));
        assertNull(windows.context().focused());
    }

    @Test
    void escapeClearsFocusThenIsNotConsumed() {
        WindowManager windows = twoWindows();
        click(windows, e);
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertNull(windows.context().focused());
        assertFalse(windows.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
    }

    @Test
    void clickingAButtonActivatesItAndClearsTextFocus() {
        WindowManager windows = twoWindows();
        click(windows, e);
        click(windows, a);
        assertEquals(1, aClicks.get());
        assertNull(windows.context().focused(), "buttons don't take focus on click, so shortcuts keep working");
    }

    @Test
    void clickingEmptySpaceOutsideWindowsClearsFocus() {
        WindowManager windows = twoWindows();
        click(windows, e);
        assertFalse(windows.mouseDown(400, 500, GLFW.GLFW_MOUSE_BUTTON_LEFT, 0));
        assertNull(windows.context().focused());
    }

    @Test
    void keysGoToTheFocusedControl() {
        WindowManager windows = twoWindows();
        windows.context().setFocus(c);
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0));
        assertEquals(6, c.intValue());
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_LEFT, 0, GLFW.GLFW_MOD_SHIFT));
        assertEquals(0, c.intValue(), "shift steps by ten, clamped to the minimum");
        windows.context().clearFocus();
        assertFalse(windows.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0), "unfocused keys stay with the editor");
    }

    @Test
    void focusedTextInputSwallowsTypingKeysButNotEscapeTabOrFunctionKeys() {
        WindowManager windows = twoWindows();
        click(windows, e);
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_L, 0, 0), "typing L must not open the library");
        assertTrue(windows.charTyped('l', 0));
        assertFalse(windows.keyPressed(GLFW.GLFW_KEY_F1, 0, 0));
        assertSame(e, windows.context().focused());
    }

    @Test
    void closingOrCollapsingAWindowDropsFocusInsideIt() {
        WindowManager windows = twoWindows();
        click(windows, e);
        windows.setCollapsed("first", true);
        assertNull(windows.context().focused());

        windows.setCollapsed("first", false);
        windows.context().setFocus(e);
        windows.close("first");
        assertNull(windows.context().focused());
    }
}
