package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.widget.Menu;
import dev.sculptory.fabric.client.editor.ui.widget.MenuBar;
import dev.sculptory.fabric.client.editor.ui.widget.MenuItem;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * The menu bar and its menus, driven like the editor drives them: the bar in its own context (the HUD's), the menus in
 * the window manager's popup layer.
 */
class MenuTest {
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    private static final int W = 640;
    private static final int H = 360;

    private final List<String> ran = new ArrayList<>();
    private final WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, Theme.DARK,
            UnaryOperator.identity());
    private final UiContext barCtx = FakeTextMeasure.context();
    private int fileOpened;
    private boolean saveEnabled;
    private MenuBar bar;

    @BeforeEach
    void build() {
        bar = new MenuBar(List.of(
                new MenuBar.Entry("File", () -> {
                    fileOpened++;
                    MenuItem save = MenuItem.action("Save", "", () -> ran.add("save"));
                    return List.of(
                            MenuItem.action("Open", "Ctrl+O", () -> ran.add("open")),
                            MenuItem.separator(),
                            MenuItem.action("Fill", "", () -> ran.add("fill")).disabled("Needs a selection"),
                            saveEnabled ? save : save.disabled("Nothing to save"),
                            MenuItem.action("Close", "B", () -> ran.add("close")));
                }),
                new MenuBar.Entry("Edit", () -> List.of(
                        MenuItem.action("Undo", "Ctrl+Z", () -> ran.add("undo")),
                        MenuItem.submenu("Size", () -> List.of(
                                MenuItem.action("Small", "", () -> ran.add("small")),
                                MenuItem.action("Large", "", () -> ran.add("large")).checked(true))),
                        MenuItem.action("Redo", "Ctrl+Y", () -> ran.add("redo")))),
                new MenuBar.Entry("View", () -> List.of(MenuItem.action("Keys", "", () -> ran.add("keys"))))),
                windows::context);
        windows.layout(W, H);
        barCtx.setScreenSize(W, H);
        bar.layout(barCtx, new Rect(0, 0, bar.measure(barCtx, W).width(), Theme.DARK.controlHeight));
    }

    private void clickTitle(int index) {
        Rect title = bar.titleBounds(index);
        Node node = bar.hitTest(title.x() + 2, title.y() + 2);
        assertNotNull(node);
        assertTrue(node.mouseDown(barCtx, title.x() + 2, title.y() + 2, LEFT));
        windows.layout(W, H);
    }

    private void hoverTitle(int index) {
        Rect title = bar.titleBounds(index);
        bar.hitTest(title.x() + 2, title.y() + 2).mouseMove(barCtx, title.x() + 2, title.y() + 2);
        windows.layout(W, H);
    }

    private void key(int key) {
        windows.keyPressed(key, 0, 0);
    }

    private Menu open() {
        return bar.openMenu().orElseThrow();
    }

    private void click(double x, double y) {
        windows.mouseMoved(x, y);
        windows.mouseDown(x, y, LEFT, 0);
        windows.mouseUp(x, y, LEFT);
    }

    private void clickRow(Menu menu, int index) {
        Rect row = menu.rowBounds(index);
        click(row.x() + 20, row.y() + row.height() / 2.0);
    }

    private List<String> labels(Menu menu) {
        return menu.items().stream().map(item -> item.isSeparator() ? "---" : item.label()).toList();
    }

    @Test
    void theTitlesSitSideBySide() {
        assertEquals(List.of("File", "Edit", "View"), bar.entries().stream().map(MenuBar.Entry::title).toList());
        assertEquals(0, bar.titleBounds(0).x());
        assertEquals(bar.titleBounds(0).right(), bar.titleBounds(1).x());
        assertEquals("File".length() * 6 + 2 * Theme.DARK.menuTitlePaddingX, bar.titleBounds(0).width());
        assertEquals(1, bar.titleAt(bar.titleBounds(1).x() + 1, 3));
        assertEquals(-1, bar.titleAt(bar.titleBounds(2).right() + 5, 3));
    }

    @Test
    void aClickOpensTheMenuBelowItsTitleAndASecondClickClosesIt() {
        clickTitle(0);
        assertTrue(bar.isMenuOpen());
        assertEquals(0, bar.openIndex());
        Menu menu = open();
        assertEquals(List.of("Open", "---", "Fill", "Save", "Close"), labels(menu));
        assertSame(menu, windows.context().focused(), "the menu has the keyboard");
        Rect rect = windows.context().popups().popups().get(0).rect();
        assertEquals(bar.titleBounds(0).x(), rect.x());
        assertTrue(rect.y() >= bar.titleBounds(0).bottom(), "below the title");
        assertEquals(-1, menu.highlighted(), "a click highlights nothing yet");

        clickTitle(0);
        assertFalse(bar.isMenuOpen());
        assertFalse(windows.context().popups().isOpen());
        assertEquals(List.of(), ran);
    }

    @Test
    void itemsAreBuiltAnewEachTimeTheMenuOpens() {
        clickTitle(0);
        assertFalse(open().items().get(3).isEnabled());
        clickTitle(0);
        saveEnabled = true;
        clickTitle(0);
        assertTrue(open().items().get(3).isEnabled(), "Save can run now");
        assertEquals(2, fileOpened);
    }

    @Test
    void hoveringAnotherTitleWhileOneIsOpenSwitchesMenus() {
        hoverTitle(1);
        assertFalse(bar.isMenuOpen(), "hovering alone opens nothing");
        clickTitle(0);
        hoverTitle(1);
        assertEquals(1, bar.openIndex());
        assertEquals(List.of("Undo", "Size", "Redo"), labels(open()));
        assertEquals(1, windows.context().popups().popups().size(), "the File menu closed");
        hoverTitle(1);
        assertEquals(1, bar.openIndex(), "hovering the open title keeps it");
        clickTitle(2);
        assertEquals(2, bar.openIndex(), "a click on another title opens that one");
    }

    @Test
    void upAndDownSkipSeparatorsAndDisabledItemsAndWrap() {
        clickTitle(0);
        Menu menu = open();
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(0, menu.highlighted());
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(4, menu.highlighted(), "past the separator, Fill and Save (disabled)");
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(0, menu.highlighted(), "wraps to the top");
        key(GLFW.GLFW_KEY_UP);
        assertEquals(4, menu.highlighted(), "and to the bottom");
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(List.of("close"), ran);
        assertFalse(bar.isMenuOpen(), "running an item closes the menu");
        assertFalse(windows.context().popups().isOpen());
    }

    @Test
    void upFromNothingHighlightsTheLastItemAndSpaceRunsIt() {
        clickTitle(1);
        key(GLFW.GLFW_KEY_UP);
        assertEquals(2, open().highlighted());
        key(GLFW.GLFW_KEY_SPACE);
        assertEquals(List.of("redo"), ran);
    }

    @Test
    void leftAndRightGoToTheNeighbouringMenuWithItsFirstItemHighlighted() {
        clickTitle(0);
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals(1, bar.openIndex());
        assertEquals(0, open().highlighted());
        assertSame(open(), windows.context().focused());
        key(GLFW.GLFW_KEY_LEFT);
        assertEquals(0, bar.openIndex());
        key(GLFW.GLFW_KEY_LEFT);
        assertEquals(2, bar.openIndex(), "Left on the first menu wraps to the last");
        key(GLFW.GLFW_KEY_RIGHT);
        assertEquals(0, bar.openIndex());
        assertEquals(1, windows.context().popups().popups().size());
    }

    @Test
    void rightEntersASubmenuAndLeftOrEscLeavesIt() {
        clickTitle(1);
        Menu menu = open();
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        assertEquals(1, menu.highlighted());
        key(GLFW.GLFW_KEY_RIGHT);
        Menu child = menu.child();
        assertNotNull(child, "Right opens the submenu");
        assertEquals(1, bar.openIndex(), "and stays in the Edit menu");
        assertSame(child, windows.context().focused());
        assertEquals(0, child.highlighted());
        assertTrue(child.items().get(1).isChecked());
        windows.layout(W, H);
        Rect parentRow = menu.rowBounds(1);
        assertEquals(parentRow.right(), windows.context().popups().popups().get(1).rect().x(), "opens to the right");

        key(GLFW.GLFW_KEY_LEFT);
        assertNull(menu.child(), "Left leaves the submenu");
        assertSame(menu, windows.context().focused(), "the keyboard is back in the menu");
        assertTrue(bar.isMenuOpen());

        key(GLFW.GLFW_KEY_ENTER);
        child = menu.child();
        assertNotNull(child, "Enter opens a submenu too");
        key(GLFW.GLFW_KEY_ESCAPE);
        assertNull(menu.child(), "Esc closes the submenu first");
        assertTrue(bar.isMenuOpen());
        key(GLFW.GLFW_KEY_ESCAPE);
        assertFalse(bar.isMenuOpen(), "then the menu");
        assertEquals(List.of(), ran);
    }

    @Test
    void aSubmenuItemRunsAndClosesEveryMenu() {
        clickTitle(1);
        Menu menu = open();
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_RIGHT);
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(List.of("large"), ran);
        assertFalse(windows.context().popups().isOpen());
        assertFalse(bar.isMenuOpen());
        assertFalse(menu.isOpen());
    }

    @Test
    void thePointerHighlightsRunsAndOpensSubmenus() {
        clickTitle(1);
        Menu menu = open();
        Rect size = menu.rowBounds(1);
        windows.mouseMoved(size.x() + 20, size.y() + 3);
        windows.layout(W, H);
        assertEquals(1, menu.highlighted());
        Menu child = menu.child();
        assertNotNull(child, "hovering a submenu item opens it");
        assertSame(menu, windows.context().focused(), "without taking the keyboard");

        Rect undo = menu.rowBounds(0);
        windows.mouseMoved(undo.x() + 20, undo.y() + 3);
        windows.layout(W, H);
        assertEquals(0, menu.highlighted());
        assertNull(menu.child(), "hovering another item closes it");

        windows.mouseMoved(size.x() + 20, size.y() + 3);
        windows.layout(W, H);
        clickRow(menu.child(), 0);
        assertEquals(List.of("small"), ran);
        assertFalse(windows.context().popups().isOpen());
    }

    @Test
    void disabledItemsAndSeparatorsIgnoreClicksAndSayWhy() {
        clickTitle(0);
        Menu menu = open();
        clickRow(menu, 2);
        clickRow(menu, 1);
        assertEquals(List.of(), ran);
        assertTrue(bar.isMenuOpen(), "the menu stays open");
        assertSame(menu, windows.context().focused(), "and keeps the keyboard");
        assertEquals(-1, menu.highlighted(), "hovering a disabled item highlights nothing");

        Rect fill = menu.rowBounds(2);
        Node row = windows.context().popups().popups().get(0).content().hitTest(fill.x() + 20, fill.y() + 3);
        assertEquals("Needs a selection", row.tooltip());

        clickRow(menu, 0);
        assertEquals(List.of("open"), ran);
        assertFalse(bar.isMenuOpen());
    }

    @Test
    void aClickOutsideClosesTheMenu() {
        clickTitle(0);
        click(W - 10, H - 10);
        assertFalse(bar.isMenuOpen());
        assertFalse(windows.context().popups().isOpen());
        assertEquals(List.of(), ran);
    }

    @Test
    void aMenuIsDrawnWithItsKeysChecksAndArrows() {
        clickTitle(1);
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_DOWN);
        key(GLFW.GLFW_KEY_RIGHT);
        RecordingGraphics g = new RecordingGraphics();
        windows.render(g, 0, 0, 0);
        assertTrue(g.texts.containsAll(List.of("Undo", "Ctrl+Z", "Size", "Redo", "Ctrl+Y", "Small", "Large", "✓")),
                g.texts.toString());
        assertTrue(g.balanced());
        RecordingGraphics barGraphics = new RecordingGraphics();
        bar.render(barGraphics, barCtx);
        assertEquals(List.of("File", "Edit", "View"), barGraphics.texts);
    }

    @Test
    void theMenuIsWideEnoughForLabelsAndKeys() {
        clickTitle(0);
        Rect rect = windows.context().popups().popups().get(0).rect();
        Theme theme = Theme.DARK;
        int content = theme.menuCheckWidth + "Close".length() * 6 + theme.menuKeyGap + "Ctrl+O".length() * 6
                + theme.menuArrowWidth;
        assertEquals(Math.max(content, theme.menuMinWidth - 2) + 2, rect.width());
        int rows = 4 * theme.menuRowHeight + theme.menuSeparatorHeight + 2 * theme.menuPaddingY;
        assertEquals(rows + 2, rect.height());
    }
}
