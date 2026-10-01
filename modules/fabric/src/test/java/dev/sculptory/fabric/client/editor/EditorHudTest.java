package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.HintLine;
import dev.sculptory.fabric.client.editor.hud.NotificationLog;
import dev.sculptory.fabric.client.editor.hud.PaletteButton;
import dev.sculptory.fabric.client.editor.hud.QuickStart;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.input.InputRouter;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.KeysWindow;
import dev.sculptory.fabric.client.session.Notice;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * The HUD parts of the navigation overhaul on the whole editor UI: the palette's
 * groups and live key labels, the hint line's tool name, the quick start card, the Notifications window and toast
 * clicks, and F6 between windows.
 */
class EditorHudTest {
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;

    private final EditorTestRig rig = new EditorTestRig();
    private long now = 1_000;
    private final ToastStack toasts = new ToastStack(() -> now, () -> LocalTime.of(12, 34, 56));
    private EditorUi ui;
    private InputRouter router;

    @BeforeEach
    void build() {
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, EditorUiTest.TEXT, toasts,
                new EditorUi.Services(EditorUiTest.SHORT_MENUS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, new UiScale()));
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(640, 360);
        router = new InputRouter(ui, rig.controller, rig.controller, new InputRouter.Look() {
            @Override
            public boolean isLooking() {
                return false;
            }

            @Override
            public void begin() {}

            @Override
            public void end() {}
        }, new InputRouter.Movement() {
            @Override
            public boolean isMovementKey(int key, int scanCode) {
                return false;
            }

            @Override
            public void press(int key, int scanCode) {}
        }, rig.keymap);
    }

    /** A frame, drawing nothing. */
    private void frame() {
        ui.render(new NoGraphics(), 0, 0, now, false);
    }

    private void press(int key, int modifiers) {
        router.keyPressed(key, 0, modifiers);
        router.keyReleased(key, 0, modifiers);
    }

    private void click(double x, double y) {
        router.mouseMoved(x, y);
        router.mouseClicked(x, y, LEFT, 0);
        router.mouseReleased(x, y, LEFT);
    }

    // ---- Palette and hint line ----

    @Test
    void thePaletteHasAWiderGapBetweenTheToolsMenusGroups() {
        Theme theme = Theme.DARK;
        for (int slot = 1; slot < ToolRegistry.PALETTE_SLOTS; slot++) {
            Rect here = ui.paletteSlotBounds(slot).orElseThrow();
            Rect next = ui.paletteSlotBounds(slot + 1).orElseThrow();
            int gap = next.x() - here.right();
            if (EditorCommands.TOOL_GROUP_STARTS.contains(slot + 1)) {
                assertEquals(theme.paletteGroupGap, gap, "a group starts at slot " + (slot + 1));
            } else {
                assertEquals(theme.paletteSlotGap, gap, "slots " + slot + " and " + (slot + 1) + " share a group");
            }
        }
        assertEquals(List.of(2, 8, 10, 15), EditorCommands.TOOL_GROUP_STARTS.stream().sorted().toList(),
                "Select · Terrain 2-7 · Place and scatter 8-9 · Build 10-14 · Weather 15");
    }

    @Test
    void eachSlotShowsItsKeyAsBoundAndFollowsTheKeysWindow() {
        frame();
        List<String> labels = palette();
        assertEquals(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "-", "=", "[", "]", "\\"), labels,
                "slots 11-15 read -, =, [, ], \\ (not 1-5)");

        rig.keymap.bind(KeyAction.TOOL_11, List.of(KeyChord.parse("ctrl+1")));
        rig.keymap.bind(KeyAction.TOOL_12, List.of());
        frame();
        assertEquals("^1", ui.paletteButton(11).orElseThrow().keyLabel(), "Ctrl shows as ^");
        assertEquals("", ui.paletteButton(12).orElseThrow().keyLabel(), "unbound: nothing");

        // Removing a key in the Keys window shows on the palette at the next frame.
        KeysWindow.Controls controls = ui.keysWindow().controls(KeyAction.TOOL_13);
        controls.removes().get(0).click();
        frame();
        assertEquals("", ui.paletteButton(13).orElseThrow().keyLabel());
    }

    @Test
    void theCornerLabelIsTheFirstChordShort() {
        assertEquals("", PaletteButton.keyLabel(List.of()));
        assertEquals("-", PaletteButton.keyLabel(List.of(KeyChord.parse("minus"), KeyChord.parse("f2"))));
        assertEquals("^⇧⎇F2", PaletteButton.keyLabel(List.of(KeyChord.parse("ctrl+shift+alt+f2"))));
    }

    private List<String> palette() {
        return java.util.stream.IntStream.rangeClosed(1, ToolRegistry.PALETTE_SLOTS)
                .mapToObj(slot -> ui.paletteButton(slot).orElseThrow().keyLabel())
                .toList();
    }

    @Test
    void theHintLineStartsWithTheActiveToolsName() {
        assertTrue(rig.controller.selectTool(ToolId.RAISE));
        frame();
        HintLine hint = ui.hintLine();
        assertEquals("sculptory.tool.raise", hint.toolName());
        assertTrue(hint.text().startsWith("sculptory.tool.raise" + HintLine.SEPARATOR), hint.text());
        assertEquals(hint.toolName() + HintLine.SEPARATOR + HintLine.format(rig.controller.hints(), EditorUiTest.SHORT_MENUS),
                hint.text());
    }

    // ---- Quick start ----

    /** A flag kept in memory, as the editor UI settings file keeps it. */
    private static final class Flag implements QuickStart.Flag {
        boolean seen;
        int marks;

        @Override
        public boolean seen() {
            return seen;
        }

        @Override
        public void markSeen() {
            seen = true;
            marks++;
        }
    }

    @Test
    void theQuickStartCardShowsUntilGotItAndEnterSetsTheFlag() {
        Flag flag = new Flag();
        ui.setQuickStartFlag(flag);
        ui.showQuickStartIfNew();
        assertTrue(ui.quickStart().isShown());
        ui.layout(640, 360);
        assertTrue(ui.isOverUi(320, 180), "the card sits in the middle, above the windows");
        assertFalse(ui.hasPopup(), "the card does not block the editor");

        press(GLFW.GLFW_KEY_2, 0);
        assertTrue(rig.ctx.tools().isActive(ToolId.RAISE), "keys still work while the card shows");
        assertTrue(ui.quickStart().isShown());

        press(GLFW.GLFW_KEY_ENTER, 0);
        assertFalse(ui.quickStart().isShown(), "Enter is Got it");
        assertTrue(flag.seen);
        ui.showQuickStartIfNew();
        assertFalse(ui.quickStart().isShown(), "not again once seen");
    }

    @Test
    void escAndTheButtonAlsoDismissTheCardForGoodAndHelpQuickStartShowsItAgain() {
        Flag flag = new Flag();
        ui.setQuickStartFlag(flag);
        ui.showQuickStartIfNew();
        press(GLFW.GLFW_KEY_ESCAPE, 0);
        assertFalse(ui.quickStart().isShown());
        assertTrue(flag.seen, "Esc sets the flag too");
        assertTrue(rig.mode.isActive(), "that Esc did not also leave the editor");

        assertTrue(ui.commands().run(EditorCommands.QUICK_START).enabled());
        assertTrue(ui.quickStart().isShown(), "Help > Quick start");
        ui.layout(640, 360);
        Rect button = ui.quickStart().gotItButton().bounds();
        click(button.x() + 2, button.y() + 2);
        assertFalse(ui.quickStart().isShown(), "Got it");
        assertEquals(2, flag.marks);
    }

    @Test
    void theCardsLinesShowTheKeysAsBound() {
        rig.keymap.bind(KeyAction.COMMAND_SEARCH, List.of(KeyChord.parse("ctrl+p")));
        ui.showQuickStart();
        List<QuickStart.Line> lines = ui.quickStart().lines();
        assertEquals(6, lines.size());
        assertEquals(new QuickStart.Line("sculptory.help.mouse.right_drag", "sculptory.quick_start.look"),
                lines.get(0));
        assertEquals(new QuickStart.Line("1–9, 0, -, =, [", "sculptory.quick_start.tools"), lines.get(1));
        assertEquals(new QuickStart.Line("Ctrl+P", "sculptory.quick_start.find"), lines.get(2));
        assertEquals(new QuickStart.Line("F1", "sculptory.quick_start.keys"), lines.get(3));
        assertEquals(new QuickStart.Line("sculptory.help.key.esc", "sculptory.quick_start.back"), lines.get(4));
        assertEquals(new QuickStart.Line("B", "sculptory.quick_start.leave"), lines.get(5), "the player's editor key");
    }

    // ---- Notifications ----

    @Test
    void viewNotificationsListsTheToastsNewestFirstAndClearEmptiesIt() {
        toasts.show(Notice.Level.INFO, "one");
        toasts.show(Notice.Level.WARNING, "two");
        toasts.show(Notice.Level.ERROR, "three");
        assertTrue(ui.commands().run(EditorCommands.NOTIFICATIONS).enabled());
        assertTrue(ui.windows().isOpen(EditorWindows.NOTIFICATIONS));
        ui.notificationsWindow().refresh();
        List<NotificationLog.Entry> shown = ui.notificationsWindow().shown();
        assertEquals(List.of("three", "two", "one"), shown.stream().map(NotificationLog.Entry::text).toList());
        assertEquals(LocalTime.of(12, 34, 56), shown.get(0).time());
        assertEquals(Notice.Level.ERROR, shown.get(0).level());

        ui.notificationsWindow().clearButton().click();
        assertEquals(List.of(), ui.notificationsWindow().shown());
        assertTrue(toasts.log().isEmpty());
        assertFalse(ui.notificationsWindow().clearButton().isEnabled());
    }

    @Test
    void aClickOnAToastDismissesIt() {
        ui.toggleWindowsHidden(); // its own toast is the first one
        toasts.clear();
        toasts.show(Notice.Level.INFO, "hello");
        frame();
        double x = ui.uiWidth() - 10;
        double y = 30;
        assertTrue(ui.isOverUi(x, y), "a toast is UI while shown");
        click(x, y);
        assertEquals(List.of(), toasts.visible());
        assertEquals("hello", toasts.log().entries().get(0).text(), "still in the log");
        assertFalse(ui.isOverUi(x, y));
    }

    // ---- F6 ----

    @Test
    void f6MovesTheKeyboardIntoTheNextWindowAndShiftF6Back() {
        ui.toggleWindow(EditorWindows.KEYS);
        ui.layout(640, 360);
        press(GLFW.GLFW_KEY_F6, 0);
        Node first = ui.windows().context().focused();
        assertNotNull(first, "F6 put the keyboard on a window's control");
        Window firstWindow = windowOf(first).orElseThrow();
        assertSame(firstWindow, topWindow(), "and brought that window to the front");

        press(GLFW.GLFW_KEY_F6, 0);
        Node second = ui.windows().context().focused();
        assertNotNull(second);
        assertNotSame(firstWindow, windowOf(second).orElseThrow(), "the next window");

        press(GLFW.GLFW_KEY_F6, GLFW.GLFW_MOD_SHIFT);
        assertSame(firstWindow, windowOf(ui.windows().context().focused()).orElseThrow(), "Shift+F6 goes back");

        press(GLFW.GLFW_KEY_ESCAPE, 0);
        assertEquals(null, ui.windows().context().focused(), "Esc first clears the focus");
        assertTrue(rig.mode.isActive());
    }

    @Test
    void f6ShowsHiddenWindowsAndIsRebindable() {
        assertEquals(List.of(KeyChord.parse("f6")), KeyAction.FOCUS_NEXT_WINDOW.defaultChords());
        assertEquals(KeyAction.Group.WINDOWS, KeyAction.FOCUS_NEXT_WINDOW.group());
        ui.toggleWindowsHidden();
        rig.keymap.bind(KeyAction.FOCUS_NEXT_WINDOW, List.of(KeyChord.parse("ctrl+tab")));
        press(GLFW.GLFW_KEY_TAB, GLFW.GLFW_MOD_CONTROL);
        assertFalse(ui.windows().isAllHidden());
        assertNotNull(ui.windows().context().focused());
    }

    private Optional<Window> windowOf(Node node) {
        return ui.windows().windows().stream()
                .filter(window -> window.isOpen() && window.content() == node.root())
                .findFirst();
    }

    private Window topWindow() {
        List<Window> all = ui.windows().windows();
        return all.get(all.size() - 1);
    }

    /** Draws nothing. */
    private static final class NoGraphics implements UiGraphics {
        @Override
        public void fill(int x, int y, int width, int height, int argb) {}

        @Override
        public void text(String text, int x, int y, int argb, boolean shadow) {}

        @Override
        public void pushClip(Rect clip) {}

        @Override
        public void popClip() {}

        @Override
        public void pushLayer(int z) {}

        @Override
        public void popLayer() {}

        @Override
        public void pushScale(float factor) {}

        @Override
        public void popScale() {}

        @Override
        public void item(ItemStack stack, int x, int y) {}

        @Override
        public void widget(ClickableWidget widget, int mouseX, int mouseY, float delta) {}
    }
}
