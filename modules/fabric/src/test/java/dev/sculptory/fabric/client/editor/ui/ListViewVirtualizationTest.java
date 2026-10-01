package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class ListViewVirtualizationTest {
    private static final int ROW = Theme.DARK.rowHeight;
    private static final int COUNT = 10_000;

    private final UiContext ctx = FakeTextMeasure.context();
    private final List<Integer> built = new ArrayList<>();
    private ListView<String> list;

    @BeforeEach
    void build() {
        List<String> items = IntStream.range(0, COUNT).mapToObj(i -> "item " + i).toList();
        list = new ListView<>(items, (item, index) -> {
            built.add(index);
            return new Label(item);
        });
    }

    @Test
    void onlyRowsInViewAreBuilt() {
        list.layout(ctx, new Rect(0, 0, 100, 5 * ROW));
        assertEquals(List.of(0, 1, 2, 3, 4), built);
        assertEquals(5, list.builtRowCount());
        assertEquals(5, list.children().size());
        assertEquals(0, list.firstVisibleIndex());
        assertEquals(4, list.lastVisibleIndex());
    }

    @Test
    void partiallyVisibleRowIsBuilt() {
        list.layout(ctx, new Rect(0, 0, 100, 5 * ROW + 3));
        assertEquals(6, list.builtRowCount());
    }

    @Test
    void scrollingBuildsOnlyNewlyVisibleRowsAndDropsHiddenOnes() {
        Rect bounds = new Rect(0, 0, 100, 5 * ROW);
        list.layout(ctx, bounds);
        built.clear();

        list.scroll().setOffset(2 * ROW);
        list.layout(ctx, bounds);
        assertEquals(List.of(5, 6), built, "rows 2-4 are reused, only 5 and 6 are new");
        assertEquals(5, list.builtRowCount());
        assertEquals(2, list.firstVisibleIndex());
        assertEquals(6, list.lastVisibleIndex());
        for (Node row : list.children()) {
            assertEquals(list, row.parent());
        }
    }

    @Test
    void jumpingFarAwayBuildsOnlyTheNewWindow() {
        Rect bounds = new Rect(0, 0, 100, 5 * ROW);
        list.layout(ctx, bounds);
        built.clear();
        list.scroll().setOffset(9000 * ROW);
        list.layout(ctx, bounds);
        assertEquals(List.of(9000, 9001, 9002, 9003, 9004), built);
    }

    @Test
    void renderDrawsOnlyVisibleRows() {
        list.layout(ctx, new Rect(0, 0, 100, 3 * ROW));
        RecordingGraphics g = new RecordingGraphics();
        list.render(g, ctx);
        assertEquals(List.of("item 0", "item 1", "item 2"), g.texts);
        assertTrue(g.balanced());
        assertEquals(1, g.maxClipDepth);
    }

    @Test
    void rowsArePlacedAtTheirScrolledPositions() {
        list.layout(ctx, new Rect(10, 20, 100, 5 * ROW));
        list.scroll().setOffset(ROW / 2);
        list.layout(ctx, new Rect(10, 20, 100, 5 * ROW));
        Node first = list.children().get(0);
        assertEquals(20 - ROW / 2, first.bounds().y());
        assertEquals(0, list.rowIndexAt(ctx, 20));
        assertEquals(1, list.rowIndexAt(ctx, 20 + ROW / 2 + 1));
        assertEquals(-1, list.rowIndexAt(ctx, 19));
    }

    @Test
    void wheelScrollsThreeRowsPerNotch() {
        Rect bounds = new Rect(0, 0, 100, 5 * ROW);
        list.layout(ctx, bounds);
        assertTrue(list.mouseScroll(ctx, 5, 5, -1));
        assertEquals(3 * ROW, list.scroll().offset());
        assertTrue(list.mouseScroll(ctx, 5, 5, 2));
        assertEquals(0, list.scroll().offset());
    }

    @Test
    void shortListDoesNotScrollOrConsumeTheWheel() {
        list.setItems(List.of("a", "b"));
        list.layout(ctx, new Rect(0, 0, 100, 5 * ROW));
        assertFalse(list.scroll().isScrollable());
        assertFalse(list.mouseScroll(ctx, 5, 5, -1));
    }

    @Test
    void replacingItemsDiscardsRowsAndClampsScrollAndSelection() {
        Rect bounds = new Rect(0, 0, 100, 5 * ROW);
        list.layout(ctx, bounds);
        list.scroll().setOffset(500 * ROW);
        list.setSelectedIndex(600);
        list.setItems(List.of("x", "y", "z"));
        assertEquals(0, list.builtRowCount());
        list.layout(ctx, bounds);
        assertEquals(0, list.scroll().offset());
        assertEquals(-1, list.selectedIndex());
        assertEquals(3, list.builtRowCount());
    }

    @Test
    void keyboardSelectionScrollsIntoView() {
        Rect bounds = new Rect(0, 0, 100, 5 * ROW);
        list.layout(ctx, bounds);
        List<Integer> selections = new ArrayList<>();
        list.setOnSelect(selections::add);

        assertTrue(list.keyPressed(ctx, GLFW.GLFW_KEY_END, 0, 0));
        list.layout(ctx, bounds);
        assertEquals(COUNT - 1, list.selectedIndex());
        assertEquals(COUNT - 1, list.lastVisibleIndex());

        list.keyPressed(ctx, GLFW.GLFW_KEY_HOME, 0, 0);
        list.keyPressed(ctx, GLFW.GLFW_KEY_DOWN, 0, 0);
        list.layout(ctx, bounds);
        assertEquals(1, list.selectedIndex());
        assertEquals(0, list.firstVisibleIndex());
        assertEquals(List.of(COUNT - 1, 0, 1), selections);
    }

    @Test
    void clickSelectsAndDoubleClickActivates() {
        Rect bounds = new Rect(0, 0, 100, 5 * ROW);
        list.layout(ctx, bounds);
        List<Integer> activated = new ArrayList<>();
        list.setOnActivate(activated::add);

        ctx.setNow(1000);
        list.mouseDown(ctx, 5, ROW + 2, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertEquals(1, list.selectedIndex());
        assertTrue(activated.isEmpty());
        ctx.setNow(1100);
        list.mouseDown(ctx, 5, ROW + 2, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertEquals(List.of(1), activated);
        ctx.setNow(3000);
        list.mouseDown(ctx, 5, 2 * ROW + 2, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertEquals(2, list.selectedIndex());
        assertEquals(List.of(1), activated, "a single click later is not a double click");
    }

    @Test
    void draggingTheScrollbarThumbToTheBottomReachesTheEnd() {
        Rect bounds = new Rect(0, 0, 100, 5 * ROW);
        list.layout(ctx, bounds);
        int trackX = bounds.right() - 2;
        Rect thumb = new Scrollbar(list.scroll()).thumb(Scrollbar.track(bounds, Theme.DARK), Theme.DARK);
        assertEquals(Theme.DARK.minThumbLength, thumb.height(), "a huge list gets the minimum thumb");

        assertTrue(list.mouseDown(ctx, trackX, thumb.y() + 1, GLFW.GLFW_MOUSE_BUTTON_LEFT));
        list.mouseDrag(ctx, trackX, bounds.bottom() + 50, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        list.mouseUp(ctx, trackX, bounds.bottom() + 50, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        assertEquals(list.scroll().maxOffset(), list.scroll().offset());
        assertEquals(-1, list.selectedIndex(), "scrollbar clicks don't select rows");
    }

    @Test
    void scrollModelThumbGeometry() {
        ScrollModel model = new ScrollModel();
        model.setExtent(400, 100);
        assertEquals(25, model.thumbLength(100, 10));
        assertEquals(0, model.thumbStart(100, 10));
        model.setOffset(300);
        assertEquals(75, model.thumbStart(100, 10));
        assertEquals(148, model.offsetForThumbStart(37, 100, 10));
        model.setOffset(10_000);
        assertEquals(300, model.offset(), "offset is clamped");
        model.ensureVisible(0, 10);
        assertEquals(0, model.offset());
        model.ensureVisible(350, 380);
        assertEquals(280, model.offset());
    }
}
