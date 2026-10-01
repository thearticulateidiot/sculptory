package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlexContainer;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Stack;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class HitTestTest {
    private final UiContext ctx = FakeTextMeasure.context();

    @Test
    void returnsDeepestNodeUnderPoint() {
        Button a = new Button("A", null);
        Button b = new Button("B", null);
        Row row = Row.of(a, b);
        row.setGap(10);
        Label label = Label.of("text");
        Column column = Column.of(row, label);
        column.layout(ctx, new Rect(0, 0, 100, 50));

        assertSame(b, column.hitTest(b.bounds().x() + 1, b.bounds().y() + 1));
        assertSame(a, column.hitTest(a.bounds().x() + 1, a.bounds().y() + 1));
        assertSame(row, column.hitTest(a.bounds().right() + 2, a.bounds().y() + 1), "gap in the row hits the row");
        assertSame(label, column.hitTest(1, label.bounds().y() + 1));
        assertNull(column.hitTest(200, 200));
        assertNull(column.hitTest(-1, 5));
    }

    @Test
    void rightAndBottomEdgesAreOutside() {
        Button button = new Button("A", null);
        button.layout(ctx, new Rect(10, 10, 20, 16));
        assertSame(button, button.hitTest(10, 10));
        assertSame(button, button.hitTest(29.9, 25.9));
        assertNull(button.hitTest(30, 20));
        assertNull(button.hitTest(20, 26));
    }

    @Test
    void laterSiblingsAreHitFirstWhenOverlapping() {
        Label under = Label.of("under");
        Label over = Label.of("over");
        Stack stack = Stack.of(under, over);
        stack.layout(ctx, new Rect(0, 0, 50, 20));
        assertSame(over, stack.hitTest(5, 5));
    }

    @Test
    void invisibleNodesAreSkipped() {
        Button hidden = new Button("A", null);
        Column column = Column.of(hidden);
        column.layout(ctx, new Rect(0, 0, 100, 50));
        hidden.setVisible(false);
        assertSame(column, column.hitTest(5, 5));
        column.setVisible(false);
        assertNull(column.hitTest(5, 5));
    }

    @Test
    void scrollPaneOnlyHitsContentInsideItsViewport() {
        List<Button> buttons = new ArrayList<>();
        Column column = new Column();
        column.setGap(0);
        for (int i = 0; i < 10; i++) {
            Button button = new Button("B" + i, null);
            buttons.add(button);
            column.add(button);
        }
        ScrollPane pane = new ScrollPane(column);
        Column root = Column.of(pane, Label.of("below"));
        pane.setFixedHeight(40);
        root.layout(ctx, new Rect(0, 0, 100, 100));

        assertSame(buttons.get(0), root.hitTest(5, 5));
        // Button 3 is laid out at y 48..64, below the 40 px viewport: it must not be hit there.
        assertFalse(root.hitTest(5, 50) instanceof Button);

        pane.scroll().setOffset(32);
        root.layout(ctx, new Rect(0, 0, 100, 100));
        assertSame(buttons.get(2), root.hitTest(5, 5));
        Node scrollbarHit = root.hitTest(pane.bounds().right() - 1, 10);
        assertSame(pane, scrollbarHit, "the scrollbar belongs to the pane, not the content");
    }

    @Test
    void disabledControlIsHitForItsTooltipButSwallowsTheClick() {
        AtomicInteger clicks = new AtomicInteger();
        AtomicInteger parentClicks = new AtomicInteger();
        Button disabled = new Button("Locked", clicks::incrementAndGet);
        disabled.setEnabled(false);
        disabled.setTooltip("You don't have permission");
        Node parent = new FlexContainer(false, Align.STRETCH) {
            @Override
            public boolean mouseDown(UiContext ctx, double x, double y, int button) {
                parentClicks.incrementAndGet();
                return true;
            }
        }.add(disabled);
        WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK, UnaryOperator.identity());
        windows.register(WindowSpec.builder("w", "w", () -> parent)
                .anchor(Corner.TOP_LEFT, 0, 0).size(120, 80).build());
        windows.layout(400, 300);

        double x = disabled.bounds().x() + 2;
        double y = disabled.bounds().y() + 2;
        assertSame(disabled, windows.hitTest(x, y).node());
        assertTrue(windows.mouseDown(x, y, 0, 0), "the click is still consumed by the window");
        windows.mouseUp(x, y, 0);
        assertEquals(0, clicks.get());
        assertEquals(0, parentClicks.get(), "the click doesn't fall through to the parent");

        windows.mouseMoved(x, y);
        RecordingGraphics g = new RecordingGraphics();
        windows.render(g, x, y, 0);
        windows.render(g, x, y, Theme.DARK.tooltipDelayMs + 1);
        assertTrue(g.texts.contains("You don't have permission"));
    }

    @Test
    void buttonFiresOnReleaseInsideOrEnterAndSpaceOnly() {
        AtomicInteger clicks = new AtomicInteger();
        Button button = new Button("Go", clicks::incrementAndGet);
        button.layout(ctx, new Rect(0, 0, 40, 16));
        assertTrue(button.mouseDown(ctx, 5, 5, 0));
        button.mouseUp(ctx, 100, 100, 0);
        assertEquals(0, clicks.get(), "dragging off before release cancels");
        button.mouseDown(ctx, 5, 5, 0);
        button.mouseUp(ctx, 6, 6, 0);
        assertEquals(1, clicks.get());
        assertFalse(button.mouseDown(ctx, 5, 5, 1), "right button is not a press");
        assertTrue(button.keyPressed(ctx, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, 0, 0));
        assertTrue(button.keyPressed(ctx, org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, 0, 0));
        assertFalse(button.keyPressed(ctx, org.lwjgl.glfw.GLFW.GLFW_KEY_A, 0, 0));
        assertEquals(3, clicks.get());
    }

    @Test
    void clicksBubbleToTheFirstAncestorThatHandlesThem() {
        AtomicInteger rowClicks = new AtomicInteger();
        Label label = Label.of("row text");
        Node row = new FlexContainer(true, Align.CENTER) {
            @Override
            public boolean mouseDown(UiContext ctx, double x, double y, int button) {
                rowClicks.incrementAndGet();
                return true;
            }
        }.add(label);
        WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK, UnaryOperator.identity());
        windows.register(WindowSpec.builder("w", "w", () -> Column.of(row))
                .anchor(Corner.TOP_LEFT, 0, 0).size(120, 80).build());
        windows.layout(400, 300);

        windows.mouseDown(label.bounds().x() + 1, label.bounds().y() + 1, 0, 0);
        assertEquals(1, rowClicks.get());
        assertSame(row, windows.context().captured(), "the handler gets pointer capture");
        windows.mouseUp(0, 0, 0);
        assertNull(windows.context().captured());
    }
}
