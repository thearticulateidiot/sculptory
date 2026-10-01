package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/** Scrolling a pane from the keyboard while a control inside it has focus, through the window manager. */
class ScrollPaneKeysTest {
    private final WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK,
            UnaryOperator.identity());
    private final UiContext ctx = windows.context();
    private Button top;
    private Slider slider;
    private ScrollPane pane;

    @BeforeEach
    void open() {
        top = new Button("Top", null);
        slider = Slider.ofInt("Size", 0, 10, 5, null);
        pane = new ScrollPane(Column.of(top, slider, new Spacer(10, 1000)));
        windows.register(WindowSpec.builder("test", "test", () -> pane)
                .anchor(Corner.TOP_LEFT, 0, 0).size(200, 150).build());
        windows.layout(800, 600);
    }

    private boolean key(int keyCode) {
        boolean consumed = windows.keyPressed(keyCode, 0, 0);
        windows.layout(800, 600);
        return consumed;
    }

    @Test
    void pageKeysScrollByAPageLessARow() {
        ctx.setFocus(top);
        windows.layout(800, 600);
        int page = pane.scroll().viewportSize() - Theme.DARK.rowHeight;
        assertTrue(key(GLFW.GLFW_KEY_PAGE_DOWN));
        assertEquals(page, pane.scroll().offset());
        assertTrue(key(GLFW.GLFW_KEY_PAGE_DOWN));
        assertEquals(2 * page, pane.scroll().offset());
        assertTrue(key(GLFW.GLFW_KEY_PAGE_UP));
        assertEquals(page, pane.scroll().offset());
    }

    @Test
    void endAndHomeGoToTheBottomAndTop() {
        ctx.setFocus(top);
        windows.layout(800, 600);
        assertTrue(key(GLFW.GLFW_KEY_END));
        assertEquals(pane.scroll().maxOffset(), pane.scroll().offset());
        assertTrue(key(GLFW.GLFW_KEY_HOME));
        assertEquals(0, pane.scroll().offset());
    }

    @Test
    void theFocusedControlKeepsTheKeysItUses() {
        ctx.setFocus(slider);
        assertTrue(key(GLFW.GLFW_KEY_END));
        assertEquals(10, slider.intValue(), "End sets the slider to its maximum");
        assertEquals(0, pane.scroll().offset(), "and does not scroll");
        assertTrue(key(GLFW.GLFW_KEY_DOWN));
        assertEquals(9, slider.intValue(), "the arrows stay with the slider");
        assertEquals(0, pane.scroll().offset());
        assertTrue(key(GLFW.GLFW_KEY_PAGE_DOWN), "Page Down, which the slider doesn't use, scrolls");
        assertTrue(pane.scroll().offset() > 0);
    }

    @Test
    void withoutFocusInsideThePaneKeysAreNotTaken() {
        ctx.clearFocus();
        assertFalse(key(GLFW.GLFW_KEY_PAGE_DOWN), "the editor keeps Page Down (a selection nudge)");
        assertEquals(0, pane.scroll().offset());
    }

    @Test
    void aFocusablePaneTakesTheArrowKeysItself() {
        pane.setFocusable(true);
        ctx.setFocus(pane);
        assertTrue(key(GLFW.GLFW_KEY_DOWN));
        assertEquals(2 * Theme.DARK.rowHeight, pane.scroll().offset());
        assertTrue(key(GLFW.GLFW_KEY_UP));
        assertEquals(0, pane.scroll().offset());
    }

    @Test
    void aPaneThatFitsTakesNoKeys() {
        ScrollPane small = new ScrollPane(Column.of(new Button("Only", null)));
        small.layout(ctx, new Rect(0, 0, 100, 100));
        assertFalse(small.keyPressed(ctx, GLFW.GLFW_KEY_PAGE_DOWN, 0, 0));
    }

    @Test
    void aRememberedOffsetAppliesOnceTheContentIsLaidOut() {
        ScrollPane fresh = new ScrollPane(Column.of(new Spacer(10, 1000)));
        fresh.restoreOffset(300);
        fresh.layout(ctx, new Rect(0, 0, 100, 100));
        assertEquals(300, fresh.scroll().offset());
        fresh.restoreOffset(5000);
        fresh.layout(ctx, new Rect(0, 0, 100, 100));
        assertEquals(fresh.scroll().maxOffset(), fresh.scroll().offset(), "clamped to the content");
    }

    @Test
    void scrollIntoViewShowsANodeAfterTheNextLayout() {
        Button far = new Button("Far", null);
        ScrollPane fresh = new ScrollPane(Column.of(new Spacer(10, 500), far, new Spacer(10, 500)));
        fresh.layout(ctx, new Rect(0, 0, 100, 100));
        fresh.scrollIntoView(far);
        fresh.layout(ctx, new Rect(0, 0, 100, 100));
        assertTrue(far.bounds().y() >= 0 && far.bounds().bottom() <= 100, "in view: " + far.bounds());
    }
}
