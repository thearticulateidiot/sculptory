package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.layout.Stack;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.IconButton;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.ProgressBar;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.ui.window.ResizeHandle;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager.Region;
import dev.sculptory.fabric.client.editor.ui.window.WindowSnapper;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class WindowManagerTest {
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    private static final int W = 960;
    private static final int H = 540;

    private static WindowManager manager() {
        return new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK, UnaryOperator.identity());
    }

    private static WindowSpec spec(String id, Corner corner, int x, int y, int width, int height) {
        return spec(id, corner, x, y, width, height, () -> Column.of(new Button("OK", null)));
    }

    private static WindowSpec spec(String id, Corner corner, int x, int y, int width, int height,
            Supplier<? extends Node> content) {
        return WindowSpec.builder(id, "title." + id, content)
                .anchor(corner, x, y)
                .size(width, height)
                .minSize(60, 40)
                .build();
    }

    private static Rect rect(WindowManager windows, String id) {
        return windows.window(id).orElseThrow().rect();
    }

    private static void drag(WindowManager windows, double fromX, double fromY, double toX, double toY) {
        assertTrue(windows.mouseDown(fromX, fromY, LEFT, 0));
        windows.mouseDragged(toX, toY, LEFT);
        windows.mouseUp(toX, toY, LEFT);
    }

    private static List<String> order(WindowManager windows) {
        return windows.windows().stream().map(Window::id).toList();
    }

    @Test
    void windowsOpenAtTheirAnchorCorner() {
        WindowManager windows = manager();
        windows.register(spec("tl", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.register(spec("tr", Corner.TOP_RIGHT, 10, 20, 100, 80));
        windows.register(spec("bl", Corner.BOTTOM_LEFT, 5, 5, 100, 80));
        windows.register(spec("br", Corner.BOTTOM_RIGHT, 0, 0, 100, 80));
        windows.layout(W, H);
        assertEquals(new Rect(10, 10, 100, 80), rect(windows, "tl"));
        assertEquals(new Rect(850, 20, 100, 80), rect(windows, "tr"));
        assertEquals(new Rect(5, 455, 100, 80), rect(windows, "bl"));
        assertEquals(new Rect(860, 460, 100, 80), rect(windows, "br"));
    }

    @Test
    void duplicateIdsAndBadIdsAreRejected() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 0, 0, 100, 80));
        assertThrows(IllegalArgumentException.class, () -> windows.register(spec("a", Corner.TOP_LEFT, 0, 0, 100, 80)));
        assertThrows(IllegalArgumentException.class, () -> spec("Bad Id", Corner.TOP_LEFT, 0, 0, 100, 80));
    }

    @Test
    void draggingTheTitleBarMovesTheWindow() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        drag(windows, 15, 15, 305, 205);
        assertEquals(new Rect(300, 200, 100, 80), rect(windows, "a"));
        windows.layout(W, H);
        assertEquals(new Rect(300, 200, 100, 80), rect(windows, "a"), "position survives the next layout");
    }

    @Test
    void draggingSnapsToScreenEdgesWithinEightPixels() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 100, 100, 100, 80));
        windows.layout(W, H);
        drag(windows, 105, 105, 10, 11);
        assertEquals(new Rect(0, 0, 100, 80), rect(windows, "a"), "5 and 6 px from the edges snap flush");

        drag(windows, 5, 5, 5 + 853, 5 + 300);
        assertEquals(960, rect(windows, "a").right(), "7 px from the right edge snaps");

        drag(windows, 865, 305, 865 - 9, 305);
        assertEquals(951, rect(windows, "a").right(), "9 px away stays put");
    }

    @Test
    void draggingSnapsToTheWorkAreaEdgesNotToScreenEdgesUnderTheHud() {
        WindowManager windows = manager();
        windows.setReserved(new Insets(0, 20, 0, 40)); // top bar 0-20, bottom strip 500-540
        windows.register(spec("a", Corner.TOP_LEFT, 4, 4, 100, 80));
        windows.layout(W, H);
        assertEquals(new Rect(4, 24, 100, 80), rect(windows, "a"));
        Window a = windows.window("a").orElseThrow();

        drag(windows, 15, 32, 15, 32 + 399); // y 423, bottom 503: 3 into the strip
        assertEquals(new Rect(0, 420, 100, 80), rect(windows, "a"), "stops on the strip's top edge, flush left");
        assertFalse(a.isOverReserved());

        drag(windows, 15, 428, 15, 428 - 403); // y 17: 3 over the top bar
        assertEquals(new Rect(0, 20, 100, 80), rect(windows, "a"), "stops under the top bar");

        drag(windows, 15, 28, 15, 28 + 435); // bottom 535: 5 from the screen's bottom, 35 into the strip
        assertEquals(new Rect(0, 455, 100, 80), rect(windows, "a"), "no snap to the screen edge under the strip");
        assertTrue(a.isOverReserved(), "dropped over the strip on purpose");
    }

    @Test
    void draggingSnapsToAnotherWindowsEdges() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.register(spec("b", Corner.TOP_LEFT, 300, 300, 100, 80));
        windows.layout(W, H);
        drag(windows, 305, 305, 120, 20);
        assertEquals(new Rect(110, 10, 100, 80), rect(windows, "b"), "flush against a's right edge, tops aligned");

        assertEquals(new Rect(50, 50, 10, 10),
                WindowSnapper.snap(new Rect(50, 50, 10, 10), List.of(new Rect(200, 200, 10, 10)), new Rect(0, 0, W, H), 8),
                "windows far apart don't snap");
    }

    @Test
    void draggingKeepsTheWindowOnScreen() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        drag(windows, 15, 15, 2000, 2000);
        assertEquals(new Rect(860, 460, 100, 80), rect(windows, "a"));
        drag(windows, 865, 465, -500, -500);
        assertEquals(new Rect(0, 0, 100, 80), rect(windows, "a"));
    }

    @Test
    void escapeCancelsAWindowDrag() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        windows.mouseDown(15, 15, LEFT, 0);
        windows.mouseDragged(300, 300, LEFT);
        assertTrue(windows.isInteracting());
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertEquals(new Rect(10, 10, 100, 80), rect(windows, "a"));
        assertFalse(windows.isInteracting());
    }

    @Test
    void clickingAWindowBringsItToTheFront() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.register(spec("b", Corner.TOP_LEFT, 50, 50, 100, 80));
        windows.layout(W, H);
        assertEquals(List.of("a", "b"), order(windows));
        assertEquals("b", windows.hitTest(80, 80).window().id(), "later windows are on top");

        windows.mouseDown(20, 40, LEFT, 0);
        windows.mouseUp(20, 40, LEFT);
        assertEquals(List.of("b", "a"), order(windows));
        assertEquals("a", windows.hitTest(80, 80).window().id());
    }

    @Test
    void hitTestFindsEachPartOfAWindow() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        assertEquals(Region.TITLE, windows.hitTest(20, 15).region());
        assertEquals(Region.CLOSE, windows.hitTest(100, 15).region());
        assertEquals(Region.COLLAPSE, windows.hitTest(88, 15).region());
        assertEquals(Region.RESIZE, windows.hitTest(107, 87).region());
        assertEquals(Region.BODY, windows.hitTest(50, 60).region());
        assertEquals(Region.NONE, windows.hitTest(200, 200).region());
        assertTrue(windows.hitTest(20, 35).node() instanceof Button);
        assertTrue(windows.isMouseOverUi(50, 60));
        assertFalse(windows.isMouseOverUi(200, 200));
    }

    @Test
    void collapseKeepsTheTitleBarAndCloseHidesTheWindow() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);

        windows.mouseDown(88, 15, LEFT, 0);
        windows.mouseUp(88, 15, LEFT);
        assertTrue(windows.window("a").orElseThrow().isCollapsed());
        assertEquals(new Rect(10, 10, 100, Theme.DARK.titleBarHeight), rect(windows, "a"));
        assertEquals(Region.NONE, windows.hitTest(50, 60).region());

        windows.mouseDown(88, 15, LEFT, 0);
        assertEquals(new Rect(10, 10, 100, 80), rect(windows, "a"));

        windows.mouseDown(100, 15, LEFT, 0);
        assertFalse(windows.isOpen("a"));
        assertNull(windows.hitTest(50, 60).window());

        windows.open("a");
        assertTrue(windows.isOpen("a"));
        assertEquals(new Rect(10, 10, 100, 80), rect(windows, "a"));
    }

    @Test
    void collapsingABottomAnchoredWindowKeepsItsTitleBarInPlace() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.BOTTOM_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        assertEquals(450, rect(windows, "a").y());
        windows.setCollapsed("a", true);
        assertEquals(450, rect(windows, "a").y());
        windows.layout(W, H);
        assertEquals(450, rect(windows, "a").y());
        windows.setCollapsed("a", false);
        assertEquals(new Rect(10, 450, 100, 80), rect(windows, "a"));
    }

    @Test
    void resizeGripRespectsMinimumSize() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        windows.mouseDown(107, 87, LEFT, 0);
        windows.mouseDragged(157, 137, LEFT);
        assertEquals(new Rect(10, 10, 150, 130), rect(windows, "a"));
        windows.mouseDragged(20, 20, LEFT);
        windows.mouseUp(20, 20, LEFT);
        assertEquals(new Rect(10, 10, 60, 40), rect(windows, "a"));
        assertEquals(60, windows.window("a").orElseThrow().width());
    }

    // ---- Resizing from any edge or corner ----

    private static ResizeHandle handleAt(WindowManager windows, double x, double y) {
        WindowManager.HitResult hit = windows.hitTest(x, y);
        return hit.region() == Region.RESIZE ? hit.handle() : null;
    }

    @Test
    void edgesAndCornersResizeButTitleButtonsAndContentKeepTheirClicks() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        // The window spans x 10..109 and y 10..89; the band is 4 px, corners reach 8 px.
        assertEquals(ResizeHandle.W, handleAt(windows, 10, 50));
        assertEquals(ResizeHandle.W, handleAt(windows, 13, 50));
        assertEquals(Region.BODY, windows.hitTest(14, 50).region(), "just inside the band is content");
        assertEquals(ResizeHandle.E, handleAt(windows, 109, 50));
        assertEquals(ResizeHandle.S, handleAt(windows, 50, 89));
        assertEquals(ResizeHandle.N, handleAt(windows, 50, 10), "the top edge of the title bar");
        assertEquals(Region.TITLE, windows.hitTest(50, 14).region(), "the rest of the title bar drags");

        assertEquals(ResizeHandle.NW, handleAt(windows, 10, 10));
        assertEquals(ResizeHandle.NW, handleAt(windows, 17, 11), "corners win over edges along 8 px");
        assertEquals(ResizeHandle.NW, handleAt(windows, 11, 17));
        assertEquals(ResizeHandle.N, handleAt(windows, 18, 11));
        assertEquals(ResizeHandle.NE, handleAt(windows, 109, 11));
        assertEquals(ResizeHandle.SW, handleAt(windows, 10, 89));
        assertEquals(ResizeHandle.SE, handleAt(windows, 109, 89));
        assertEquals(ResizeHandle.SE, handleAt(windows, 105, 81), "the grip outside the content area");
        assertEquals(Region.BODY, windows.hitTest(101, 81).region(), "the grip never covers content");

        // The close button spans x 96..107, y 12..23: the band's outer rim wins over it.
        assertEquals(Region.CLOSE, windows.hitTest(100, 15).region());
        assertEquals(Region.CLOSE, windows.hitTest(105, 20).region());
        assertEquals(ResizeHandle.N, handleAt(windows, 100, 13), "the top edge runs above the close button");
        assertEquals(ResizeHandle.NE, handleAt(windows, 107, 15), "the corner reaches down beside it");
        assertEquals(ResizeHandle.E, handleAt(windows, 107, 20), "and so does the right edge");
        assertEquals(Region.COLLAPSE, windows.hitTest(88, 15).region());
        assertTrue(windows.hitTest(20, 35).node() instanceof Button, "content is still clickable");
        assertEquals(Region.NONE, windows.hitTest(9, 50).region(), "nothing outside the window at 100%");
    }

    @Test
    void aWiderResizeReachExtendsOutsideTheBorderButNeverOverContent() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.setResizeReach(8, 16); // what the editor passes at 50% UI size
        windows.layout(W, H);
        // Padding is 5, so 5 units of the band lie inside the window and 3 outside.
        assertEquals(ResizeHandle.W, handleAt(windows, 7, 50));
        assertEquals(Region.NONE, windows.hitTest(6, 50).region());
        assertEquals(ResizeHandle.W, handleAt(windows, 14, 50));
        assertEquals(Region.BODY, windows.hitTest(15, 50).region(), "content starts where the band ends");
        assertEquals(ResizeHandle.E, handleAt(windows, 112, 50));
        assertEquals(ResizeHandle.S, handleAt(windows, 50, 92));
        assertEquals(ResizeHandle.N, handleAt(windows, 50, 7));
        assertEquals(ResizeHandle.NW, handleAt(windows, 25, 8), "corners reach 16 along the edges");
        assertEquals(ResizeHandle.SE, handleAt(windows, 112, 92));
        assertTrue(windows.isMouseOverUi(7, 50), "the outer band belongs to the window, not the world");
        drag(windows, 8, 50, 38, 50);
        assertEquals(new Rect(40, 10, 70, 80), rect(windows, "a"));

        windows.setResizeReach(2, 2);
        assertEquals(ResizeHandle.W, handleAt(windows, 43, 50), "never below the theme's reach");
        assertEquals(Region.BODY, windows.hitTest(44, 50).region());
        assertEquals(Region.NONE, windows.hitTest(39, 50).region());
    }

    @Test
    void aScrollbarInTheBottomRightCornerStillGetsItsDrag() {
        WindowManager windows = manager();
        ScrollPane[] pane = new ScrollPane[1];
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80, () -> {
            Column column = new Column();
            IntStream.range(0, 40).forEach(i -> column.add(Label.of("row " + i)));
            pane[0] = new ScrollPane(column);
            return pane[0];
        }));
        windows.layout(W, H);
        // Content is at (15, 31, 90, 54); the scrollbar track runs down x 100..104 to y 84.
        windows.mouseScrolled(50, 60, -1000, 0);
        int end = pane[0].scroll().offset();
        assertTrue(end > 0 && end == pane[0].scroll().maxOffset(), "scrolled to the end");
        assertEquals(Region.BODY, windows.hitTest(103, 83).region(), "the thumb's bottom is under the grip");

        windows.mouseDown(103, 83, LEFT, 0);
        windows.mouseDragged(103, 50, LEFT);
        windows.mouseUp(103, 50, LEFT);
        assertTrue(pane[0].scroll().offset() < end, "dragging the thumb scrolled back up");
        assertEquals(new Rect(10, 10, 100, 80), rect(windows, "a"), "and didn't resize the window");

        windows.mouseScrolled(103, 83, -1000, 0);
        assertEquals(end, pane[0].scroll().offset(), "the wheel works there too");
    }

    @Test
    void unresizableWindowsHaveNoResizeBand() {
        WindowManager windows = manager();
        windows.register(WindowSpec.builder("a", "title.a", () -> Column.of(new Button("OK", null)))
                .anchor(Corner.TOP_LEFT, 10, 10).size(100, 80).resizable(false).build());
        windows.layout(W, H);
        assertEquals(Region.BODY, windows.hitTest(10, 50).region());
        assertEquals(Region.TITLE, windows.hitTest(50, 10).region());
        assertEquals(Region.BODY, windows.hitTest(109, 89).region());
    }

    @Test
    void draggingEachEdgeOrCornerMovesOnlyThoseEdges() {
        record Case(double fromX, double fromY, double dx, double dy, Rect expected) {}
        // The window spans x 100..199 and y 100..179.
        List<Case> cases = List.of(
                new Case(199, 140, 30, 0, new Rect(100, 100, 130, 80)),
                new Case(100, 140, -30, 0, new Rect(70, 100, 130, 80)),
                new Case(140, 100, 0, -20, new Rect(100, 80, 100, 100)),
                new Case(140, 179, 0, 20, new Rect(100, 100, 100, 100)),
                new Case(100, 100, -10, -10, new Rect(90, 90, 110, 90)),
                new Case(199, 100, 10, -10, new Rect(100, 90, 110, 90)),
                new Case(100, 179, -10, 10, new Rect(90, 100, 110, 90)),
                new Case(199, 179, 10, 10, new Rect(100, 100, 110, 90)),
                new Case(100, 140, 20.7, 5, new Rect(120, 100, 80, 80)));
        for (Case c : cases) {
            WindowManager windows = manager();
            windows.register(spec("a", Corner.TOP_LEFT, 100, 100, 100, 80));
            windows.layout(W, H);
            drag(windows, c.fromX(), c.fromY(), c.fromX() + c.dx(), c.fromY() + c.dy());
            assertEquals(c.expected(), rect(windows, "a"), c.toString());
            Window window = windows.window("a").orElseThrow();
            assertEquals(c.expected().width(), window.width());
            assertEquals(c.expected().height(), window.height());
            windows.layout(W, H);
            assertEquals(c.expected(), rect(windows, "a"), "the new rect survives the next layout: " + c);
        }
    }

    @Test
    void resizingFromTheLeftOrTopKeepsTheMinimumSizeAndTheOppositeEdge() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 100, 100, 100, 80));
        windows.layout(W, H);
        drag(windows, 100, 140, 190, 140);
        assertEquals(new Rect(140, 100, 60, 80), rect(windows, "a"), "60 wide at most, right edge still at 200");
        drag(windows, 150, 100, 150, 300);
        assertEquals(new Rect(140, 140, 60, 40), rect(windows, "a"), "40 tall at most, bottom still at 180");
    }

    @Test
    void resizingStopsAtTheScreenEdges() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 100, 100, 100, 80));
        windows.layout(W, H);
        drag(windows, 100, 140, -500, 140);
        assertEquals(new Rect(0, 100, 200, 80), rect(windows, "a"));
        drag(windows, 100, 100, 100, -500);
        assertEquals(new Rect(0, 0, 200, 180), rect(windows, "a"));
        drag(windows, 199, 100, 5000, 100);
        assertEquals(W, rect(windows, "a").right());
        drag(windows, 100, 179, 100, 5000);
        assertEquals(new Rect(0, 0, W, H), rect(windows, "a"));
    }

    @Test
    void escapeDuringAResizeRestoresTheWindowExactly() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.BOTTOM_RIGHT, 50, 40, 100, 80));
        windows.layout(W, H);
        Window a = windows.window("a").orElseThrow();
        Rect before = a.rect();
        windows.mouseDown(before.x(), before.y() + 40, LEFT, 0);
        windows.mouseDragged(before.x() - 200, before.y() + 40, LEFT);
        assertEquals(before.x() - 200, a.rect().x());
        assertEquals(UiCursor.RESIZE_EW, windows.cursor(), "the resize cursor stays for the whole drag");
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertEquals(before, a.rect());
        assertEquals(Corner.BOTTOM_RIGHT, a.anchor());
        assertEquals(50, a.offsetX());
        assertEquals(40, a.offsetY());
        assertEquals(100, a.width());
        assertFalse(windows.isInteracting());
        windows.mouseUp(before.x() - 200, before.y() + 40, LEFT);
        assertEquals(before, a.rect(), "the release after Esc changes nothing");
    }

    @Test
    void collapsedWindowsResizeOnlySideways() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        windows.setCollapsed("a", true);
        assertEquals(ResizeHandle.W, handleAt(windows, 10, 15));
        assertEquals(ResizeHandle.E, handleAt(windows, 109, 12));
        assertEquals(Region.TITLE, windows.hitTest(50, 10).region(), "no top edge when collapsed");
        drag(windows, 109, 18, 149, 60);
        assertEquals(new Rect(10, 10, 140, Theme.DARK.titleBarHeight), rect(windows, "a"));
        windows.setCollapsed("a", false);
        assertEquals(new Rect(10, 10, 140, 80), rect(windows, "a"), "the expanded height is kept");
    }

    @Test
    void theCursorShowsWhichWayAWindowResizes() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        windows.mouseMoved(50, 50);
        assertEquals(UiCursor.DEFAULT, windows.cursor());
        windows.mouseMoved(10, 50);
        assertEquals(UiCursor.RESIZE_EW, windows.cursor());
        windows.mouseMoved(50, 89);
        assertEquals(UiCursor.RESIZE_NS, windows.cursor());
        windows.mouseMoved(109, 89);
        assertEquals(UiCursor.RESIZE_NWSE, windows.cursor());
        windows.mouseMoved(10, 89);
        assertEquals(UiCursor.RESIZE_NESW, windows.cursor());
        windows.mouseMoved(300, 300);
        assertEquals(UiCursor.DEFAULT, windows.cursor(), "back to normal off the window");

        windows.mouseMoved(10, 50);
        windows.mouseDown(10, 50, LEFT, 0);
        windows.mouseDragged(-40, 50, LEFT);
        windows.mouseMoved(500, 300);
        assertEquals(UiCursor.RESIZE_EW, windows.cursor());
        windows.mouseUp(500, 300, LEFT);
        assertEquals(UiCursor.DEFAULT, windows.cursor());
    }

    @Test
    void anchorsKeepWindowsInPlaceAcrossGuiScales() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.register(spec("b", Corner.TOP_LEFT, 10, 200, 100, 80));
        windows.layout(W, H);
        drag(windows, 15, 205, 805, 405);
        Window b = windows.window("b").orElseThrow();
        assertEquals(new Rect(800, 400, 100, 80), b.rect());
        assertEquals(Corner.BOTTOM_RIGHT, b.anchor(), "re-anchored to the nearest corner");
        assertEquals(60, b.offsetX());
        assertEquals(60, b.offsetY());

        // 1920x1080 at GUI scale 3 instead of 2.
        windows.layout(640, 360);
        assertEquals(new Rect(480, 220, 100, 80), b.rect(), "same distance from the bottom-right corner");
        assertEquals(new Rect(10, 10, 100, 80), rect(windows, "a"), "top-left window stays top-left");

        windows.layout(W, H);
        assertEquals(new Rect(800, 400, 100, 80), b.rect());

        windows.layout(150, 100);
        assertEquals(new Rect(0, 0, 100, 80), b.rect(), "clamped on a tiny screen");
        windows.layout(W, H);
        assertEquals(new Rect(800, 400, 100, 80), b.rect(), "clamping doesn't overwrite the saved anchor");
    }

    @Test
    void windowLargerThanTheScreenShrinksToFitWithoutLosingItsSize() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_RIGHT, 0, 0, 500, 400));
        windows.layout(300, 200);
        assertEquals(new Rect(0, 0, 300, 200), rect(windows, "a"));
        assertEquals(500, windows.window("a").orElseThrow().width());
        windows.layout(W, H);
        assertEquals(new Rect(460, 0, 500, 400), rect(windows, "a"));
    }

    @Test
    void hidingAllWindowsIgnoresInputUntilShownAgain() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        windows.setAllHidden(true);
        assertEquals(Region.NONE, windows.hitTest(50, 50).region());
        assertFalse(windows.mouseDown(15, 15, LEFT, 0));
        assertFalse(windows.mouseScrolled(50, 50, 1, 0));
        RecordingGraphics g = new RecordingGraphics();
        windows.render(g, 0, 0, 0);
        assertEquals(0, g.layersPushed);

        windows.setAllHidden(false);
        assertTrue(windows.isOpen("a"));
        assertEquals(Region.TITLE, windows.hitTest(15, 15).region());
    }

    @Test
    void layoutsRoundTripThroughJsonAndRestoreIntoAFreshManager() {
        WindowManager first = manager();
        first.arrangeForUiSize(100);
        List<WindowSpec> specs = List.of(
                spec("a", Corner.TOP_LEFT, 10, 10, 100, 80),
                spec("b", Corner.TOP_RIGHT, 10, 10, 120, 90),
                spec("c", Corner.BOTTOM_LEFT, 10, 10, 100, 80));
        specs.forEach(first::register);
        first.layout(W, H);
        drag(first, 845, 15, 705, 355);
        first.setCollapsed("a", true);
        first.close("c");
        first.bringToFront("a");

        SizedLayouts saved = first.layouts();
        String json = saved.toJson();
        assertTrue(json.contains("\"anchor\": \"BOTTOM_RIGHT\""), json);
        SizedLayouts parsed = SizedLayouts.fromJson(json, 100, state -> false);
        assertEquals(saved, parsed);
        assertEquals(List.of("b"), List.copyOf(parsed.arrangement(100).keySet()), "only the window moved is placed");

        WindowManager second = manager();
        specs.forEach(second::register);
        second.layout(W, H);
        second.restore(parsed, 100);
        assertSameLayout(first, second);

        WindowManager third = manager();
        third.restore(parsed, 100);
        third.layout(W, H);
        assertEquals(3, third.snapshot().windows().size(), "unregistered windows keep their saved state");
        specs.forEach(third::register);
        assertSameLayout(first, third);
    }

    private static void assertSameLayout(WindowManager expected, WindowManager actual) {
        assertEquals(order(expected), order(actual));
        for (Window window : expected.windows()) {
            Window other = actual.window(window.id()).orElseThrow();
            assertEquals(window.isOpen(), other.isOpen(), window.id());
            assertEquals(window.isCollapsed(), other.isCollapsed(), window.id());
            assertEquals(window.rect(), other.rect(), window.id());
        }
    }

    @Test
    void layoutJsonRejectsBadDocumentsAndSkipsBadEntries() {
        assertThrows(IllegalArgumentException.class, () -> LayoutState.fromJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> LayoutState.fromJson(""));
        assertThrows(IllegalArgumentException.class, () -> LayoutState.fromJson("[]"));
        assertThrows(IllegalArgumentException.class, () -> LayoutState.fromJson("{\"version\": 2, \"windows\": []}"));
        assertThrows(IllegalArgumentException.class, () -> LayoutState.fromJson("{\"version\": 1}"));

        String good = "{\"id\": \"ok\", \"open\": true, \"collapsed\": false, \"anchor\": \"TOP_LEFT\","
                + " \"x\": 1, \"y\": 2, \"width\": 100, \"height\": 80}";
        String json = "{\"version\": 1, \"windows\": [" + good + ","
                + good.replace("\"ok\"", "\"corner\"").replace("TOP_LEFT", "MIDDLE") + ","
                + good.replace("\"ok\"", "\"tiny\"").replace("100", "-5") + ","
                + good.replace("\"ok\"", "\"fraction\"").replace("\"x\": 1", "\"x\": 1.5") + ","
                + "{\"id\": \"missing\"},"
                + good.replace("\"x\": 1", "\"x\": 99") + ","
                + "42]}";
        LayoutState state = LayoutState.fromJson(json);
        assertEquals(1, state.windows().size());
        assertEquals(new LayoutState.WindowState("ok", true, false, Corner.TOP_LEFT, 1, 2, 100, 80),
                state.windows().get(0), "the first of duplicate ids wins");
    }

    @Test
    void restoredSizesRespectMinimumsAndUnclosableWindowsStayOpen() {
        WindowManager windows = manager();
        windows.register(WindowSpec.builder("pinned", "pinned", () -> new Spacer(1, 1))
                .size(100, 80).minSize(60, 40).closable(false).build());
        windows.layout(W, H);
        windows.restore(new LayoutState(List.of(
                new LayoutState.WindowState("pinned", false, false, Corner.TOP_LEFT, 0, 0, 10, 10))));
        Window pinned = windows.window("pinned").orElseThrow();
        assertTrue(pinned.isOpen());
        assertEquals(60, pinned.width());
        assertEquals(40, pinned.height());
        assertTrue(pinned.closeButtonRect(Theme.DARK).isEmpty());
    }

    @Test
    void resetLayoutRestoresSpecDefaultsAndLeavesWindowsOpenClosedAndCollapsed() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.register(spec("b", Corner.TOP_LEFT, 200, 10, 100, 80));
        windows.layout(W, H);
        drag(windows, 15, 15, 505, 305);
        drag(windows, 205, 15, 305, 215);
        windows.setCollapsed("b", true);
        windows.close("a");
        List<String> order = windows.windows().stream().map(Window::id).toList();

        windows.resetLayout();
        assertFalse(windows.isOpen("a"), "closed stays closed");
        assertTrue(windows.isOpen("b"));
        assertTrue(windows.window("b").orElseThrow().isCollapsed(), "collapsed stays collapsed");
        assertEquals(order, windows.windows().stream().map(Window::id).toList(), "the order stays");
        assertEquals(new Rect(200, 10, 100, Theme.DARK.titleBarHeight), rect(windows, "b"), "its default place");
        windows.open("a");
        assertEquals(new Rect(10, 10, 100, 80), rect(windows, "a"));
        windows.setCollapsed("b", false);
        assertEquals(new Rect(200, 10, 100, 80), rect(windows, "b"));
    }

    @Test
    void dropdownPopupDrawsAboveWindowsAndClosesOnChoiceOutsideClickOrEscape() {
        AtomicReference<String> chosen = new AtomicReference<>();
        AtomicReference<Dropdown<String>> dropdown = new AtomicReference<>();
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 150, 60, () -> {
            Dropdown<String> node = new Dropdown<>(List.of("A", "B", "C"), "A", s -> s, chosen::set);
            dropdown.set(node);
            return Column.of(node);
        }));
        windows.layout(W, H);
        Dropdown<String> node = dropdown.get();
        assertEquals(new Rect(15, 31, 140, 16), node.bounds());

        windows.mouseDown(20, 35, LEFT, 0);
        windows.mouseUp(20, 35, LEFT);
        assertTrue(node.isOpen());
        Rect popup = windows.context().popups().popups().get(0).rect();
        assertEquals(new Rect(15, 47, 140, 44), popup, "below the dropdown, extending past the window");
        assertTrue(windows.isMouseOverUi(20, 85));
        assertNull(windows.hitTest(20, 85).window(), "the popup is outside the window itself");

        RecordingGraphics g = new RecordingGraphics();
        windows.render(g, 0, 0, 0);
        assertEquals(2, g.layersPushed, "window layer plus popup layer");
        assertTrue(g.balanced());

        windows.mouseDown(20, 48 + 2 * Theme.DARK.rowHeight + 2, LEFT, 0);
        windows.mouseUp(20, 48 + 2 * Theme.DARK.rowHeight + 2, LEFT);
        assertEquals("C", chosen.get());
        assertEquals("C", node.selected());
        assertFalse(node.isOpen());

        windows.mouseDown(20, 35, LEFT, 0);
        assertTrue(node.isOpen());
        assertTrue(windows.mouseDown(500, 500, LEFT, 0), "the dismissing click is consumed");
        assertFalse(node.isOpen());

        windows.mouseDown(20, 35, LEFT, 0);
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertFalse(node.isOpen());

        windows.mouseDown(20, 35, LEFT, 0);
        windows.close("a");
        assertFalse(node.isOpen(), "closing the owner's window closes its popup");
    }

    @Test
    void dropdownKeyboardNavigationInsideThePopup() {
        AtomicReference<String> chosen = new AtomicReference<>();
        AtomicReference<Dropdown<String>> dropdown = new AtomicReference<>();
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 150, 60, () -> {
            dropdown.set(new Dropdown<>(List.of("A", "B", "C"), "A", s -> s, chosen::set));
            return Column.of(dropdown.get());
        }));
        windows.layout(W, H);
        UiContext ctx = windows.context();

        windows.mouseDown(20, 35, LEFT, 0);
        windows.mouseUp(20, 35, LEFT);
        assertTrue(ctx.focused() instanceof ListView<?>, "the open list takes the keyboard");
        windows.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0);
        windows.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertEquals("B", chosen.get());
        assertFalse(ctx.popups().isOpen());
        assertNull(ctx.focused(), "opened by mouse: focus doesn't linger on the dropdown");

        ctx.setFocus(dropdown.get());
        windows.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0);
        assertEquals("C", chosen.get(), "arrows change a focused, closed dropdown directly");
        windows.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertTrue(dropdown.get().isOpen());
        windows.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        assertFalse(dropdown.get().isOpen());
        assertSame(dropdown.get(), ctx.focused(), "opened by keyboard: focus returns to the dropdown");
    }

    @Test
    void scrollOverAWindowIsConsumedEvenWhenNothingScrolls() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 100, 80));
        windows.layout(W, H);
        assertTrue(windows.mouseScrolled(50, 60, 1, 0), "the editor mustn't change fly speed under a window");
        assertFalse(windows.mouseScrolled(500, 500, 1, 0));
    }

    @Test
    void sliderScrollStepsAndShiftMultiplies() {
        AtomicReference<Slider> slider = new AtomicReference<>();
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 150, 60, () -> {
            slider.set(Slider.ofInt("Radius", 1, 64, 8, null));
            return Column.of(slider.get());
        }));
        windows.layout(W, H);
        Rect bounds = slider.get().bounds();
        windows.mouseScrolled(bounds.x() + 5, bounds.y() + 5, 1, 0);
        assertEquals(9, slider.get().intValue());
        windows.mouseScrolled(bounds.x() + 5, bounds.y() + 5, -1, GLFW.GLFW_MOD_SHIFT);
        assertEquals(1, slider.get().intValue());
    }

    @Test
    void sliderDragSetsValueAndReleaseFiresOnce() {
        List<Double> released = new ArrayList<>();
        Slider slider = Slider.ofDecimal("Strength", 0, 1, 0.05, 0.5, null).setOnRelease(released::add);
        UiContext ctx = FakeTextMeasure.context();
        slider.layout(ctx, new Rect(0, 0, 102, 16));
        slider.mouseDown(ctx, 1, 5, LEFT);
        assertEquals(0.0, slider.value());
        slider.mouseDrag(ctx, 76, 5, LEFT);
        assertEquals(0.75, slider.value());
        slider.mouseDrag(ctx, 500, 5, LEFT);
        assertEquals(1.0, slider.value());
        slider.mouseUp(ctx, 500, 5, LEFT);
        assertEquals(List.of(1.0), released);
        assertEquals("1.00", slider.formattedValue());
        assertEquals(0.35, slider.snap(0.3499999));
    }

    @Test
    void rebuildingContentDropsFocusOnTheOldTree() {
        AtomicInteger builds = new AtomicInteger();
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 10, 10, 150, 80, () -> {
            builds.incrementAndGet();
            return Column.of(new Button("Go", null));
        }));
        windows.layout(W, H);
        Window window = windows.window("a").orElseThrow();
        Node oldContent = window.content();
        Node button = oldContent.children().get(0);
        windows.context().setFocus(button);
        window.rebuildContent();
        windows.layout(W, H);
        assertNull(windows.context().focused());
        assertNotSame(oldContent, window.content());
        assertEquals(2, builds.get());
    }

    @Test
    void renderingEveryWidgetBalancesClipsAndLayers() {
        WindowManager windows = manager();
        windows.register(spec("widgets", Corner.TOP_LEFT, 0, 0, 220, 300, () -> {
            IconButton icon = new IconButton(null, null);
            icon.setEnabled(false);
            ProgressBar busy = new ProgressBar().setIndeterminate(true);
            return new ScrollPane(Column.of(
                    Label.heading("Heading"),
                    Label.dim("A long wrapped paragraph of help text.").setWrap(true),
                    Row.of(new Button("Default", null), new Button("Primary", null).setStyle(Button.Style.PRIMARY),
                            new Button("Danger", null).setStyle(Button.Style.DANGER)),
                    Row.of(icon, new Button("Flat", null).setStyle(Button.Style.FLAT), Spacer.flexible()),
                    new Toggle("Toggle", true, null),
                    Slider.ofInt("Int", 0, 10, 3, null),
                    new Dropdown<>(List.of(1, 2, 3), 2, String::valueOf, null),
                    new CollapsibleSection("Section", Padding.all(2, Label.of("inside")), true),
                    ListView.ofLabels(IntStream.range(0, 100).boxed().toList(), String::valueOf).setPreferredRows(4),
                    new ProgressBar().setProgress(0.4).setText("40%"),
                    busy,
                    Stack.of(new Spacer(10, 10), Label.of("over"))));
        }));
        windows.register(spec("other", Corner.TOP_RIGHT, 0, 0, 150, 100));
        windows.layout(W, H);

        RecordingGraphics g = new RecordingGraphics();
        windows.mouseMoved(30, 30);
        windows.render(g, 30, 30, 0);
        windows.render(g, 30, 30, 10_000);
        assertTrue(g.balanced());
        assertTrue(g.texts.contains("title.widgets"));
        assertTrue(g.texts.contains("title.other"));
        assertTrue(g.texts.contains("Heading"));
        assertTrue(g.texts.contains("40%"));
        assertTrue(g.maxClipDepth >= 3, "window body, scroll pane and list clips nest");
    }

    // ---- Painter's order ----

    @Test
    void aWindowOnTopHidesTheTextOfTheWindowUnderIt() {
        WindowManager windows = manager();
        windows.register(spec("under", Corner.TOP_LEFT, 10, 10, 200, 150,
                () -> Column.of(IntStream.range(0, 12).mapToObj(i -> Label.of("under " + i)).toArray(Node[]::new))));
        windows.register(spec("over", Corner.TOP_LEFT, 12, 60, 200, 150, () -> Column.of(Label.of("over"))));
        windows.layout(W, H);
        RecordingGraphics g = new RecordingGraphics();
        windows.render(g, 0, 0, 0);

        Rect over = rect(windows, "over");
        assertFalse(g.textsPaintedOver(over).isEmpty(), "the lower window's text lies under the upper window");
        assertEquals(List.of(), g.textsShowingThrough(over));
    }

    @Test
    void surfacesDrawnOverOtherUiAreOpaque() {
        Theme theme = Theme.DARK;
        for (int argb : new int[] {theme.windowBackground, theme.titleBar, theme.titleBarActive, theme.popupBackground,
                theme.tooltipBackground}) {
            assertEquals(0xFF, argb >>> 24, Integer.toHexString(argb));
        }
    }

    // ---- Work area and default places ----

    @Test
    void windowsOpenInsideTheWorkAreaAtTheirSpecOrTheDefaultLayout() {
        WindowManager windows = manager();
        windows.setReserved(new Insets(0, 20, 0, 40));
        windows.register(spec("tall", Corner.TOP_LEFT, 4, 4, 100, 900));
        windows.register(spec("low", Corner.BOTTOM_RIGHT, 4, 4, 100, 80));
        windows.register(spec("laid", Corner.TOP_LEFT, 0, 0, 100, 80));
        windows.setDefaultLayout((work, specs, shown) -> {
            assertEquals(List.of("tall", "low", "laid"), List.copyOf(specs.keySet()));
            return Map.of("laid", new Rect(work.x() + 300, work.y() + 7, 120, 90));
        });
        windows.layout(W, H);

        assertEquals(new Rect(0, 20, W, H - 60), windows.workArea());
        assertEquals(new Rect(4, 20, 100, H - 60), rect(windows, "tall"), "no taller than the work area");
        assertEquals(new Rect(W - 104, H - 40 - 4 - 80, 100, 80), rect(windows, "low"));
        assertEquals(new Rect(300, 27, 120, 90), rect(windows, "laid"));
        assertFalse(windows.window("laid").orElseThrow().isPlaced());
    }

    @Test
    void aMovedWindowKeepsOffTheReservedEdgesAfterAScreenChangeUnlessLeftOnThem() {
        WindowManager windows = manager();
        windows.setReserved(new Insets(0, 20, 0, 40));
        windows.register(spec("a", Corner.TOP_LEFT, 4, 4, 100, 80));
        windows.register(spec("b", Corner.TOP_LEFT, 300, 4, 100, 80));
        windows.layout(W, H);
        drag(windows, 15, 32, 15, 32 + 420); // a: y 24 -> 444, bottom 524; the strip starts at 500
        Rect aAtFull = rect(windows, "a");
        assertTrue(windows.window("a").orElseThrow().isOverReserved(), "left over the bottom strip on purpose");
        drag(windows, 315, 32, 315, 32 + 156); // b: y 180, bottom 260, top-anchored
        Rect bAtFull = rect(windows, "b");
        assertFalse(windows.window("b").orElseThrow().isOverReserved());

        windows.layout(W, 280); // a shorter screen: the strip is 240-280
        assertEquals(new Rect(bAtFull.x(), 240 - 80, 100, 80), rect(windows, "b"), "moved up above the strip");
        assertTrue(rect(windows, "a").bottom() > 240, "a stays over the strip where the user left it");

        windows.layout(W, 120); // work area 20-80: b is drawn 60 tall (its minimum, 40, fits)
        assertEquals(new Rect(bAtFull.x(), 20, 100, 60), rect(windows, "b"));
        windows.layout(W, 70); // work area 20-30: the minimum doesn't fit; under the top bar, over the strip
        assertEquals(new Rect(bAtFull.x(), 20, 100, 40), rect(windows, "b"));

        windows.layout(W, H);
        assertEquals(bAtFull, rect(windows, "b"), "back where the user put it");
        assertEquals(aAtFull, rect(windows, "a"));
        assertEquals(80, windows.window("b").orElseThrow().height(), "the stored size never changed");
    }

    @Test
    void movingADefaultWindowStartsFromItsDefaultPlaceAndEscPutsItBack() {
        WindowManager windows = manager();
        windows.setReserved(new Insets(0, 20, 0, 40));
        windows.register(spec("a", Corner.TOP_LEFT, 0, 0, 100, 80));
        windows.setDefaultLayout((work, specs, shown) -> Map.of("a", new Rect(50, 30, 150, 120)));
        windows.layout(W, H);
        assertEquals(new Rect(50, 30, 150, 120), rect(windows, "a"));

        assertTrue(windows.mouseDown(60, 35, LEFT, 0));
        windows.mouseDragged(70, 45, LEFT);
        assertEquals(new Rect(60, 40, 150, 120), rect(windows, "a"), "the drawn size is kept, not the spec's");
        assertTrue(windows.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertFalse(windows.window("a").orElseThrow().isPlaced());
        assertEquals(new Rect(50, 30, 150, 120), rect(windows, "a"));

        drag(windows, 60, 35, 160, 135);
        Window a = windows.window("a").orElseThrow();
        assertTrue(a.isPlaced());
        assertEquals(new Rect(150, 130, 150, 120), a.rect());
        windows.resetLayout();
        assertFalse(a.isPlaced());
        assertEquals(new Rect(50, 30, 150, 120), a.rect());
    }

    /** "a" at (50, 30) and "b" at (60, 40) by default; "b" goes to (300, 30) when that covers something. */
    private WindowManager coveringDefaults(List<Map<String, Rect>> asked) {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.TOP_LEFT, 0, 0, 100, 80));
        windows.register(WindowSpec.builder("b", "title.b", () -> Column.of(new Button("OK", null)))
                .size(100, 80).minSize(60, 40).openByDefault(false).build());
        windows.setDefaultLayout(new WindowManager.DefaultLayout() {
            @Override
            public Map<String, Rect> place(Rect work, Map<String, WindowSpec> specs, Set<String> shown) {
                return Map.of("a", new Rect(50, 30, 150, 120), "b", new Rect(60, 40, 100, 80));
            }

            @Override
            public Optional<Rect> clearPlace(Rect work, Map<String, WindowSpec> specs, Set<String> shown, String id,
                    Map<String, Rect> occupied) {
                if (!id.equals("b")) {
                    return Optional.empty();
                }
                asked.add(occupied);
                return Optional.of(new Rect(300, work.y() + 30, 100, 80));
            }
        });
        windows.layout(W, H);
        return windows;
    }

    @Test
    void aWindowOpeningOverAnotherGoesWhereTheDefaultLayoutFindsRoomWhileItIsOpen() {
        List<Map<String, Rect>> asked = new ArrayList<>();
        WindowManager windows = coveringDefaults(asked);
        windows.close("a");
        windows.open("b");
        assertEquals(new Rect(60, 40, 100, 80), rect(windows, "b"), "nothing to cover: its default place");
        assertEquals(List.of(), asked);
        windows.close("b");
        windows.open("a");

        windows.open("b");
        assertEquals(List.of(Map.of("a", new Rect(50, 30, 150, 120))), asked, "asked with the open window it covered");
        Window b = windows.window("b").orElseThrow();
        assertEquals(new Rect(300, 30, 100, 80), b.rect());
        assertEquals(Optional.of(new Rect(300, 30, 100, 80)), b.clearPlace());
        drag(windows, 60, 35, 320, 35);
        assertEquals(new Rect(300, 30, 100, 80), b.rect(), "it doesn't move when another window does");
        assertEquals(1, asked.size(), "decided once while open");

        // Closed and opened again (the other window moved away meanwhile): decided anew, its default place now.
        windows.close("b");
        windows.open("b");
        assertEquals(new Rect(60, 40, 100, 80), b.rect());
        assertEquals(Optional.empty(), b.clearPlace());

        // Reset layout forgets where it went and decides again (it stays open): a is back at its default place.
        windows.resetLayout();
        assertTrue(b.isOpen());
        assertEquals(new Rect(300, 30, 100, 80), b.rect(), "a back at its default place: covered again");
        assertEquals(2, asked.size(), "decided anew");
        windows.close("a");
        windows.resetLayout();
        assertEquals(Optional.empty(), b.clearPlace(), "nothing to cover now: its default place");
        assertEquals(new Rect(60, 40, 100, 80), b.rect());
    }

    @Test
    void aClearPlaceIsNotAPlacementAndIsNotSaved() {
        List<Map<String, Rect>> asked = new ArrayList<>();
        WindowManager windows = coveringDefaults(asked);
        windows.open("b");
        Window b = windows.window("b").orElseThrow();
        assertEquals(new Rect(300, 30, 100, 80), b.rect());
        assertFalse(b.isPlaced(), "not a window the user put there");
        LayoutState.WindowState saved = windows.snapshot().window("b").orElseThrow();
        assertFalse(saved.placed(), "saved unplaced");
        assertEquals(List.of(Corner.TOP_LEFT, 8, 8, 100, 80), List.of(saved.anchor(), saved.offsetX(), saved.offsetY(),
                saved.width(), saved.height()), "its spec's place and size, not the clear place");

        // Restored into a new session with "a" open: decided again there.
        WindowManager next = coveringDefaults(asked);
        next.restore(SizedLayouts.fromJson(windows.layouts().toJson(), 100, state -> false), 100);
        next.layout(W, H);
        assertEquals(new Rect(300, 30, 100, 80), rect(next, "b"));
        assertFalse(next.window("b").orElseThrow().isPlaced());
    }

    @Test
    void aClearPlaceIsDecidedAgainWhenTheWorkAreaChanges() {
        List<Map<String, Rect>> asked = new ArrayList<>();
        WindowManager windows = coveringDefaults(asked);
        windows.open("b");
        assertEquals(new Rect(300, 30, 100, 80), rect(windows, "b"));

        // A UI size or screen change moves the work area: its default place is asked about again there.
        windows.setReserved(new Insets(0, 20, 0, 0));
        windows.layout(W, H);
        assertEquals(2, asked.size());
        assertEquals(new Rect(300, 50, 100, 80), rect(windows, "b"), "the clear place for the new work area");

        // Where the default place covers nothing any more, it opens there.
        windows.close("a");
        windows.setReserved(Insets.NONE);
        windows.layout(W, H);
        assertEquals(new Rect(60, 40, 100, 80), rect(windows, "b"));
        assertEquals(2, asked.size(), "nothing covered: nothing asked");
        assertFalse(windows.window("b").orElseThrow().isPlaced());
    }

    @Test
    void collapsingADefaultWindowKeepsItsTitleBarAtTheDefaultPlace() {
        WindowManager windows = manager();
        windows.register(spec("a", Corner.BOTTOM_LEFT, 10, 10, 100, 80));
        windows.setDefaultLayout((work, specs, shown) -> Map.of("a", new Rect(40, 300, 100, 80)));
        windows.layout(W, H);
        windows.setCollapsed("a", true);
        assertEquals(new Rect(40, 300, 100, Theme.DARK.titleBarHeight), rect(windows, "a"));
        assertFalse(windows.window("a").orElseThrow().isPlaced());
        windows.setCollapsed("a", false);
        assertEquals(new Rect(40, 300, 100, 80), rect(windows, "a"));
    }

    @Test
    void placedAndOverReservedAreSavedAndOldFilesKeepTheirOpenWindowsInPlaceOffTheHud() {
        WindowManager first = manager();
        first.arrangeForUiSize(100);
        first.setReserved(new Insets(0, 20, 0, 40));
        List<WindowSpec> specs = List.of(spec("a", Corner.TOP_LEFT, 4, 4, 100, 80),
                spec("b", Corner.TOP_LEFT, 300, 4, 100, 80), spec("c", Corner.TOP_LEFT, 600, 4, 100, 80));
        specs.forEach(first::register);
        first.layout(W, H);
        drag(first, 15, 32, 15, 32 + 420);
        drag(first, 315, 32, 315, 132);
        LayoutState snapshot = first.snapshot();
        assertEquals(List.of(true, true, false), Stream.of("a", "b", "c")
                .map(id -> snapshot.window(id).orElseThrow().placed()).toList());
        assertEquals(List.of(true, false, false), Stream.of("a", "b", "c")
                .map(id -> snapshot.window(id).orElseThrow().overReserved()).toList());
        SizedLayouts parsed = SizedLayouts.fromJson(first.layouts().toJson(), 100, state -> false);
        assertEquals(first.layouts(), parsed);
        assertEquals(List.of(true, false), Stream.of("a", "b")
                .map(id -> parsed.arrangement(100).get(id).overReserved()).toList());
        assertFalse(parsed.arrangement(100).containsKey("c"), "at its default place");

        WindowManager second = manager();
        second.setReserved(new Insets(0, 20, 0, 40));
        specs.forEach(second::register);
        second.layout(W, H);
        second.restore(parsed, 100);
        assertSameLayout(first, second);
        second.layout(W, 400);
        assertTrue(rect(second, "a").bottom() > 360, "still over the strip");

        String old = "{\"version\": 1, \"windows\": [{\"id\": \"a\", \"open\": true, \"collapsed\": false,"
                + " \"anchor\": \"TOP_LEFT\", \"x\": 4, \"y\": 450, \"width\": 100, \"height\": 80}]}";
        LayoutState.WindowState legacy = LayoutState.fromJson(old).windows().get(0);
        assertTrue(legacy.placed());
        assertFalse(legacy.overReserved());
        WindowManager third = manager();
        third.setReserved(new Insets(0, 20, 0, 40));
        specs.forEach(third::register);
        third.restore(LayoutState.fromJson(old));
        third.layout(W, H);
        assertEquals(new Rect(4, H - 40 - 80, 100, 80), rect(third, "a"), "an old layout is kept off the strip");

        String badPlaced = old.replace("\"height\": 80", "\"height\": 80, \"placed\": \"yes\"");
        assertEquals(List.of(), LayoutState.fromJson(badPlaced).windows(), "a malformed flag skips the entry");
        String badReserved = old.replace("\"height\": 80", "\"height\": 80, \"overReserved\": 1");
        assertEquals(List.of(), LayoutState.fromJson(badReserved).windows());

        // Old files saved closed windows at their old default places too: one exactly at an old default place (as the
        // caller tells) opens at today's default place; one elsewhere was moved by the user and stays placed.
        String oldClosed = old.replace("\"open\": true", "\"open\": false");
        Predicate<LayoutState.WindowState> oldDefault = state -> state.id().equals("a") && state.offsetY() == 450;
        assertTrue(LayoutState.fromJson(oldClosed).windows().get(0).placed(), "without a list of old places: placed");
        LayoutState.WindowState closed = LayoutState.fromJson(oldClosed, oldDefault).windows().get(0);
        assertFalse(closed.placed());
        String movedClosed = oldClosed.replace("\"y\": 450", "\"y\": 300");
        assertTrue(LayoutState.fromJson(movedClosed, oldDefault).windows().get(0).placed(), "moved by the user");
        assertTrue(LayoutState.fromJson(old, oldDefault).windows().get(0).placed(), "open: kept where it is seen");
        WindowManager fourth = manager();
        fourth.setReserved(new Insets(0, 20, 0, 40));
        specs.forEach(fourth::register);
        fourth.restore(LayoutState.fromJson(oldClosed, oldDefault));
        fourth.open("a");
        fourth.layout(W, H);
        assertEquals(new Rect(4, 20 + 4, 100, 80), rect(fourth, "a"), "at its spec's place in the work area");
        String closedPlaced = oldClosed.replace("\"height\": 80", "\"height\": 80, \"placed\": true");
        assertTrue(LayoutState.fromJson(closedPlaced, oldDefault).windows().get(0).placed(), "a saved flag is kept");
    }
}
