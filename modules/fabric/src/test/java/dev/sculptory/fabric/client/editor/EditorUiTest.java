package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.blocks.BlockChip;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.input.InputRouter;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.IconButton;
import dev.sculptory.fabric.client.editor.ui.widget.Menu;
import dev.sculptory.fabric.client.editor.ui.widget.MenuBar;
import dev.sculptory.fabric.client.editor.ui.widget.MenuItem;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.ToolAction;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class EditorUiTest {
    static final TextMeasure TEXT = new TextMeasure() {
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

    /**
     * Translation keys as text, like {@link Translator#KEYS}, but the top bar's words short ("file", "undo", "UI 100%"),
     * so the bar fits 640 wide as it does in English.
     */
    static final Translator SHORT_MENUS = new Translator() {
        @Override
        public String translate(String key, Object... args) {
            String menu = "sculptory.menu.";
            return switch (key) {
                case "sculptory.topbar.undo" -> "undo";
                case "sculptory.topbar.redo" -> "redo";
                case "sculptory.mask.chip" -> "mask";
                case "sculptory.mask.chip.rules" -> "mask " + args[0];
                case "sculptory.topbar.ui_size" -> "UI " + args[0];
                default -> key.startsWith(menu) ? key.substring(menu.length()) : Translator.KEYS.translate(key, args);
            };
        }

        @Override
        public boolean has(String key) {
            return false;
        }
    };

    private final EditorTestRig rig = new EditorTestRig();
    private long now = 1_000;
    private final ToastStack toasts = new ToastStack(() -> now);
    private final UiScale scale = new UiScale();
    private final List<Integer> savedSizes = new ArrayList<>();
    private EditorUi ui;

    @BeforeEach
    void build() {
        scale.addListener(savedSizes::add);
        ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, TEXT, toasts,
                new EditorUi.Services(SHORT_MENUS, BlockCatalog.EMPTY, icon -> null,
                        () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                        "config/sculptory/editor-keys.json", keymap -> true, scale));
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(640, 360);
    }

    /**
     * Ctrl+M without rules opens the Mask window; with rules it switches the mask, and the top bar's chip glows while it
     * is on. The window draws with every kind of rule in it.
     */
    @Test
    void ctrlMTogglesTheMaskAndTheChipGlows() {
        dev.sculptory.fabric.client.editor.mask.EditMaskModel mask =
                dev.sculptory.fabric.client.editor.mask.EditMaskModel.global();
        try {
            mask.setRules(List.of());
            mask.setOn(false);
            rig.controller.toggleMask();
            assertTrue(ui.windows().isOpen(EditorWindows.MASK), "no rules: the window opens to add one");
            assertFalse(mask.on());
            List<dev.sculptory.core.mask.MaskEntry> rules = new ArrayList<>();
            for (dev.sculptory.fabric.client.editor.mask.RuleKind kind
                    : dev.sculptory.fabric.client.editor.mask.RuleKind.values()) {
                rules.add(new dev.sculptory.core.mask.MaskEntry(kind.create(null, () -> 5L), kind.ordinal() % 2 == 0));
            }
            mask.setRules(rules);
            rig.controller.toggleMask();
            assertTrue(mask.active());
            ui.layout(640, 360);
            ui.render(new dev.sculptory.fabric.client.editor.ui.RecordingGraphics(), 0, 0, 0, false);
            dev.sculptory.fabric.client.editor.mask.MaskChip chip = null;
            for (int x = 0; x < 640 && chip == null; x++) {
                if (ui.hud().hitTest(x, 10) instanceof dev.sculptory.fabric.client.editor.mask.MaskChip found) chip = found;
            }
            assertTrue(chip != null && chip.glowing(), "the chip glows while the mask is on");
            rig.controller.toggleMask();
            assertFalse(mask.on());
            assertFalse(chip.glowing());
        } finally {
            mask.setRules(List.of());
            mask.setOn(false);
        }
    }

    @Test
    void theDefaultWindowsAreOpen() {
        assertTrue(ui.windows().isOpen(EditorWindows.TOOL_SETTINGS));
        assertTrue(ui.windows().isOpen(EditorWindows.SELECTION));
        assertFalse(ui.windows().isOpen(EditorWindows.HISTORY));
        assertFalse(ui.windows().isOpen(EditorWindows.KEYS));
        ui.toggleWindow(EditorWindows.HISTORY);
        assertTrue(ui.windows().isOpen(EditorWindows.HISTORY));
    }

    @Test
    void aLargeOpWaitsForEnterInTheDialog() {
        rig.ctx.setSelection(new Box(new BlockPos(0, 0, 0), new BlockPos(99, 99, 99)));
        rig.actions.fill();
        assertTrue(ui.hasPopup());
        assertEquals(List.of(), rig.session.sent());
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0));
        assertFalse(ui.hasPopup());
        OpSpec op = assertInstanceOf(ToolAction.RunOp.class, rig.session.sent().get(0)).op();
        assertInstanceOf(OpSpec.Fill.class, op);
    }

    @Test
    void escCancelsTheDialog() {
        boolean[] ran = {false};
        ui.confirm("Erase 1,000,000 blocks?", () -> ran[0] = true);
        assertTrue(ui.hasPopup());
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertFalse(ui.hasPopup());
        assertFalse(ran[0]);
    }

    @Test
    void theKeySheetClosesWithF1EscOrAClickOutsideButNotWithAClickInside() {
        ui.toggleHelp();
        assertTrue(ui.isHelpOpen());
        assertTrue(ui.hasPopup(), "the key sheet takes input like a popup");
        assertTrue(ui.hasKeyboardFocus(), "its filter box has the keyboard");
        assertTrue(ui.isOverUi(600, 300));
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_F1, 0, 0));
        assertFalse(ui.isHelpOpen());

        ui.toggleHelp();
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_K, 0, 0), "typing goes to the filter box");
        assertTrue(ui.isHelpOpen(), "other keys leave it open");
        assertTrue(ui.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0));
        assertFalse(ui.isHelpOpen());

        ui.toggleHelp();
        Rect sheet = ui.keySheet().bounds();
        assertTrue(sheet.width() > 0 && sheet.x() > 2, sheet.toString());
        assertTrue(ui.mouseDown(sheet.x() + sheet.width() / 2.0, sheet.bottom() - 30, 0, 0));
        ui.mouseUp(sheet.x() + sheet.width() / 2.0, sheet.bottom() - 30, 0);
        assertTrue(ui.isHelpOpen(), "a click inside no longer closes it");
        assertTrue(ui.mouseDown(2, sheet.y() + 10, 0, 0));
        assertFalse(ui.isHelpOpen(), "a click on the dimmed screen outside does");
    }

    @Test
    void theKeySheetsChangeKeysButtonClosesItAndOpensTheKeysWindow() {
        ui.toggleHelp();
        Button change = ui.keySheet().changeKeysButton();
        Rect bounds = change.bounds();
        ui.mouseDown(bounds.x() + 2, bounds.y() + 2, 0, 0);
        ui.mouseUp(bounds.x() + 2, bounds.y() + 2, 0);
        assertFalse(ui.isHelpOpen());
        assertTrue(ui.windows().isOpen(EditorWindows.KEYS));
        assertEquals(EditorWindows.KEYS, ui.windows().windows().get(ui.windows().windows().size() - 1).id(),
                "in front");
    }

    @Test
    void theHelpMenusKeySheetCommandOpensTheSheet() {
        assertTrue(ui.commands().run(EditorCommands.KEY_SHEET).enabled());
        assertTrue(ui.isHelpOpen());
    }

    @Test
    void tabHidesTheWindowsAndSaysHowToGetThemBack() {
        ui.toggleWindowsHidden();
        assertTrue(ui.windows().isAllHidden());
        assertEquals(List.of(Notice.of(Notice.Level.INFO, "sculptory.notice.windows_hidden", "Tab")), rig.notices);
        ui.toggleWindow(EditorWindows.KEYS);
        assertFalse(ui.windows().isAllHidden(), "opening a window shows them again");
        assertTrue(ui.windows().isOpen(EditorWindows.KEYS));
    }

    @Test
    void toolSettingsFollowTheActiveTool() {
        assertEquals(SettingsForm.Kind.SEGMENTED, ui.toolSettings().form().orElseThrow().kind("fill_with").orElseThrow());
        rig.controller.selectSlot(2);
        ui.layout(640, 360);
        assertEquals(SettingsForm.Kind.SLIDER, ui.toolSettings().form().orElseThrow().kind("radius").orElseThrow(),
                "Raise shows its brush settings");
        rig.controller.selectSlot(1);
        ui.layout(640, 360);
        SettingsForm form = ui.toolSettings().form().orElseThrow();
        rig.ctx.updateSettings(ToolId.SELECT, rig.ctx.settings(ToolId.SELECT).with(SelectSettings.THICKNESS, 5));
        assertEquals(5, form.values().get(SelectSettings.THICKNESS), "changes made elsewhere show in the open form");
    }

    @Test
    void theWorldIsNotUiButWindowsAndTheTopBarAre() {
        assertTrue(ui.isOverUi(320, 5), "the top bar spans the top");
        assertTrue(ui.isOverUi(320, 350), "the palette sits at the bottom centre");
        assertFalse(ui.isOverUi(320, 180), "the middle of the screen is the world");
    }

    // ---- UI size ----

    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;

    private InputRouter router() {
        InputRouter.Look look = new InputRouter.Look() {
            private boolean looking;

            @Override
            public boolean isLooking() {
                return looking;
            }

            @Override
            public void begin() {
                looking = true;
            }

            @Override
            public void end() {
                looking = false;
            }
        };
        InputRouter.Movement movement = new InputRouter.Movement() {
            @Override
            public boolean isMovementKey(int key, int scanCode) {
                return false;
            }

            @Override
            public void press(int key, int scanCode) {}
        };
        return new InputRouter(ui, rig.controller, rig.controller, look, movement, rig.keymap);
    }

    private void click(InputRouter router, double x, double y) {
        router.mouseMoved(x, y);
        router.mouseClicked(x, y, LEFT, 0);
        router.mouseReleased(x, y, LEFT);
    }

    private List<Rect> openWindowRects() {
        return ui.windows().windows().stream().filter(Window::isOpen).map(Window::rect).toList();
    }

    @Test
    void theUiSizeScalesUiHitTestingButTheWorldKeepsScreenCoordinates() {
        InputRouter router = router();
        assertTrue(ui.isOverUi(150, 200), "at 100% the Selection window covers this point");
        assertEquals(WindowManager.Region.NONE, ui.windows().hitTest(2, 100).region(), "left of the Selection window");

        scale.set(50);
        ui.layout(640, 360);
        assertEquals(1280, ui.uiWidth());
        assertEquals(720, ui.uiHeight());
        assertEquals(WindowManager.Region.RESIZE, ui.windows().hitTest(2, 100).region(),
                "at 50% the resize band is widened to stay 4 screen pixels wide");
        assertFalse(ui.isOverUi(150, 200), "at 50% the window is half as big");
        assertTrue(ui.isOverUi(50, 60), "it covers this point instead");

        rig.platform.pickPoints.clear();
        click(router, 150, 200);
        assertFalse(rig.platform.pickPoints.isEmpty(), "the tool picked the world");
        assertTrue(rig.platform.pickPoints.stream().allMatch("150.0,200.0"::equals),
                "world picks use the unscaled screen position: " + rig.platform.pickPoints);

        rig.platform.pickPoints.clear();
        click(router, 50, 60);
        assertEquals(List.of(), rig.platform.pickPoints, "clicks on a window never reach the world");

        scale.set(150);
        ui.layout(640, 360);
        rig.platform.pickPoints.clear();
        router.mouseMoved(300, 200);
        assertEquals(List.of("300.0,200.0"), rig.platform.pickPoints);
    }

    @Test
    void everyUiSizeKeepsEveryWindowOnScreenAndOneHundredPercentRestoresTheLayout() {
        for (String id : List.of(EditorWindows.HISTORY, EditorWindows.KEYS, EditorWindows.CLIPBOARD, EditorWindows.LIBRARY)) {
            if (!ui.windows().isOpen(id)) {
                ui.toggleWindow(id);
            }
        }
        ui.layout(640, 360);
        List<Rect> atDefault = openWindowRects();
        assertEquals(6, atDefault.size());

        List<Integer> seen = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ui.stepUiSize(-1);
        }
        for (int i = 0; i <= 9; i++) {
            seen.add(scale.percent());
            assertEquals(scale.uiLength(640), ui.uiWidth(), "the new size applies at once");
            assertEquals(scale.uiLength(360), ui.uiHeight());
            for (Rect rect : openWindowRects()) {
                assertTrue(rect.x() >= 0 && rect.y() >= 0 && rect.right() <= ui.uiWidth()
                        && rect.bottom() <= ui.uiHeight(), rect + " is on screen at " + scale.percent() + "%");
            }
            ui.stepUiSize(1);
        }
        assertEquals(UiScale.steps(), seen);

        ui.resetUiSize();
        assertEquals(100, scale.percent());
        assertEquals(atDefault, openWindowRects(), "window anchors are kept in UI units");
    }

    @Test
    void theUiSizeKeysChangeSaveAndAnnounceTheSize() {
        InputRouter router = router();
        router.keyPressed(GLFW.GLFW_KEY_MINUS, 0, GLFW.GLFW_MOD_CONTROL);
        router.keyReleased(GLFW.GLFW_KEY_MINUS, 0, GLFW.GLFW_MOD_CONTROL);
        assertEquals(90, scale.percent());
        router.keyPressed(GLFW.GLFW_KEY_MINUS, 0, GLFW.GLFW_MOD_CONTROL);
        router.keyReleased(GLFW.GLFW_KEY_MINUS, 0, GLFW.GLFW_MOD_CONTROL);
        assertEquals(80, scale.percent());
        assertEquals(List.of("sculptory.notice.ui_size[80%]"),
                toasts.visible().stream().map(ToastStack.Toast::text).toList(), "one toast, replaced on each change");
        router.keyPressed(GLFW.GLFW_KEY_EQUAL, 0, GLFW.GLFW_MOD_CONTROL);
        assertEquals(90, scale.percent());
        router.keyPressed(GLFW.GLFW_KEY_0, 0, GLFW.GLFW_MOD_CONTROL);
        assertEquals(100, scale.percent());
        assertEquals(List.of(90, 80, 90, 100), savedSizes);

        for (int i = 0; i < 8; i++) {
            ui.stepUiSize(-1);
        }
        assertEquals(50, scale.percent(), "stops at the smallest size");
        assertEquals(List.of(90, 80, 90, 100, 90, 80, 75, 70, 60, 50), savedSizes, "unchanged sizes aren't saved");
        assertEquals(List.of("sculptory.notice.ui_size[50%]"),
                toasts.visible().stream().map(ToastStack.Toast::text).toList());
    }

    @Test
    void theTopBarUiSizeMenuSetsTheSize() {
        InputRouter router = router();
        Button sizeButton = null;
        int buttonX = -1;
        for (int x = 639; x >= 0 && sizeButton == null; x--) {
            if (ui.hud().hitTest(x, 10) instanceof Button button && button.text().startsWith("UI ")) {
                sizeButton = button;
                buttonX = x;
            }
        }
        assertEquals("UI 100%", sizeButton == null ? null : sizeButton.text());
        click(router, buttonX - 2, 10);
        assertTrue(ui.hasPopup(), "the size menu opened");
        ui.layout(640, 360); // the next frame places the popup
        Menu menu = assertInstanceOf(Menu.class, ui.windows().context().popups().popups().get(0).content());
        List<String> labels = menu.items().stream().map(MenuItem::label).toList();
        assertEquals(labels, ui.commands().menuItems(ui.commands().get(EditorCommands.UI_SIZE).orElseThrow().children())
                .stream().map(MenuItem::label).toList(), "the same menu as View > UI size");
        int index = labels.indexOf("sculptory.command.view.ui_size.step[75%]");
        Rect row = menu.rowBounds(index);
        click(router, row.x() + 20, row.y() + row.height() / 2.0);
        assertFalse(ui.hasPopup());
        assertEquals(75, scale.percent());
        assertEquals(List.of(75), savedSizes);
        ui.render(new ScaleRecorder(), 0, 0, now, false);
        assertEquals("UI 75%", sizeButton.text());
    }

    // ---- Menu bar ----

    /** The top bar's controls from left to right: menu titles, then button texts ("icon" for icon buttons). */
    private List<String> topBar() {
        List<String> seen = new ArrayList<>();
        Object last = null;
        for (int x = 0; x < 640; x++) {
            Object hit = ui.hud().hitTest(x, 10);
            if (hit == null || hit == last) {
                continue;
            }
            last = hit;
            if (hit instanceof Button button) {
                seen.add(button.text());
            } else if (hit instanceof IconButton) {
                seen.add("icon");
            } else if (hit instanceof Node node && node.parent() instanceof MenuBar) {
                seen.add("menu " + ui.menuBar().titleAt(x, 10));
            }
        }
        return seen;
    }

    @Test
    void theTopBarStartsWithTheMenusAndHasNoWindowsOrHelpButton() {
        List<String> bar = topBar();
        assertEquals(List.of("menu 0", "menu 1", "menu 2", "menu 3", "menu 4", "menu 5"), bar.subList(0, 6),
                "File · Edit · Selection · Tools · View · Help at the left: " + bar);
        assertEquals(List.of("file", "edit", "selection", "tools", "view", "help"),
                ui.menuBar().entries().stream().map(MenuBar.Entry::title).toList());
        assertEquals(List.of("undo", "redo", "icon", "UI 100%"), bar.subList(bar.size() - 4, bar.size()),
                "Undo, Redo, the water-aim toggle and the UI size stay: " + bar);
        assertFalse(bar.contains("sculptory.topbar.windows"), "no Windows button");
        assertFalse(bar.contains("?"), "no ? button");
        assertInstanceOf(BlockChip.class, ui.hud().hitTest(ui.menuBar().titleBounds(5).right() + 8, 10),
                "the block chip follows the menus");
    }

    @Test
    void aClickOpensAMenuHoveringSwitchesAndEscClosesItBeforeAnythingElse() {
        InputRouter router = router();
        Rect file = ui.menuBar().titleBounds(0);
        click(router, file.x() + 3, file.y() + 3);
        assertEquals(0, ui.menuBar().openIndex());
        assertTrue(ui.hasPopup());
        ui.layout(640, 360);

        Rect view = ui.menuBar().titleBounds(4);
        router.mouseMoved(view.x() + 3, view.y() + 3);
        assertEquals(4, ui.menuBar().openIndex(), "moving over View opens it instead");
        Menu menu = ui.menuBar().openMenu().orElseThrow();
        assertEquals("sculptory.window.tool_settings", menu.items().get(0).label());
        assertTrue(menu.items().get(0).isChecked(), "Tool Settings is open");

        click(router, view.x() + 3, view.y() + 3);
        assertFalse(ui.menuBar().isMenuOpen(), "a second click on the title closes it");

        click(router, view.x() + 3, view.y() + 3);
        ui.layout(640, 360);
        router.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        router.keyReleased(GLFW.GLFW_KEY_ESCAPE, 0, 0);
        assertFalse(ui.menuBar().isMenuOpen(), "Esc closes the menu first");
        assertTrue(rig.mode.isActive(), "and does not leave the editor");
    }

    @Test
    void menuItemsRunWithTheMouseOrTheKeyboard() {
        InputRouter router = router();
        Rect view = ui.menuBar().titleBounds(4);
        click(router, view.x() + 3, view.y() + 3);
        ui.layout(640, 360);
        Menu menu = ui.menuBar().openMenu().orElseThrow();
        int history = menu.items().stream().map(MenuItem::label).toList().indexOf("sculptory.window.history");
        Rect row = menu.rowBounds(history);
        click(router, row.x() + 20, row.y() + row.height() / 2.0);
        assertTrue(ui.windows().isOpen(EditorWindows.HISTORY), "View > History opened the window");
        assertFalse(ui.hasPopup());

        Rect selection = ui.menuBar().titleBounds(2);
        click(router, selection.x() + 3, selection.y() + 3);
        ui.layout(640, 360);
        router.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0);
        router.keyReleased(GLFW.GLFW_KEY_DOWN, 0, 0);
        Menu selectionMenu = ui.menuBar().openMenu().orElseThrow();
        assertEquals("sculptory.command.selection.symmetry_centre",
                selectionMenu.items().get(selectionMenu.highlighted()).label(),
                "Down skips every item that needs a selection");
        router.keyPressed(GLFW.GLFW_KEY_RIGHT, 0, 0);
        router.keyReleased(GLFW.GLFW_KEY_RIGHT, 0, 0);
        assertEquals(3, ui.menuBar().openIndex(), "Right goes to Tools");
        router.keyPressed(GLFW.GLFW_KEY_DOWN, 0, 0);
        router.keyReleased(GLFW.GLFW_KEY_DOWN, 0, 0);
        router.keyPressed(GLFW.GLFW_KEY_ENTER, 0, 0);
        router.keyReleased(GLFW.GLFW_KEY_ENTER, 0, 0);
        assertTrue(rig.ctx.tools().isActive(ToolId.RAISE), "Tools > Raise (the item after Select)");
        assertFalse(ui.hasPopup());
    }

    @Test
    void ctrlKOpensTheCommandSearch() {
        InputRouter router = router();
        router.keyPressed(GLFW.GLFW_KEY_K, 0, GLFW.GLFW_MOD_CONTROL);
        assertTrue(ui.commandSearch().isOpen());
        assertTrue(ui.hasPopup());
    }

    // ---- Aim at water and lava ----

    private int fluidAimButtonX() {
        for (int x = ui.uiWidth() - 1; x >= 0; x--) {
            if (ui.hud().hitTest(x, 10) instanceof IconButton) {
                return x;
            }
        }
        throw new AssertionError("no aim at water and lava button in the top bar");
    }

    /**
     * A screen wide enough for every top bar item at this test's 6 units a character once a frame has filled in the
     * fly speed and the status (on 640 the UI size, fly speed and water-aim button would give way).
     */
    private void wideEnoughForTheWholeTopBar() {
        ui.layout(1000, 360);
    }

    private IconButton fluidAimButton() {
        return (IconButton) ui.hud().hitTest(fluidAimButtonX(), 10);
    }

    private void renderFrame() {
        ui.render(new ScaleRecorder(), 0, 0, now, false);
    }

    @Test
    void theTopBarButtonTurnsAimAtWaterAndLavaOnAndOffAndShowsIt() {
        wideEnoughForTheWholeTopBar();
        InputRouter router = router();
        renderFrame();
        IconButton button = fluidAimButton();
        assertFalse(button.isSelected());
        assertEquals("sculptory.topbar.fluid_aim.off", button.tooltip(), "unbound: no key in the tooltip");
        assertTrue(ui.isOverUi(fluidAimButtonX(), 10));

        rig.notices.clear();
        click(router, fluidAimButtonX(), 10);
        assertTrue(rig.controller.aimsAtFluids());
        assertTrue(rig.platform.aimAtFluids, "the cursor pick aims at fluids now");
        assertEquals(List.of("sculptory.notice.fluid_aim_on"), rig.noticeKeys());
        renderFrame();
        assertTrue(button.isSelected(), "the button is highlighted while it's on");
        assertEquals("sculptory.topbar.fluid_aim.on", button.tooltip());

        click(router, fluidAimButtonX(), 10);
        assertFalse(rig.platform.aimAtFluids);
        renderFrame();
        assertFalse(button.isSelected());
    }

    @Test
    void aBoundKeyTogglesAimAtWaterAndLavaOncePerPress() {
        wideEnoughForTheWholeTopBar();
        ui.toggleWindowsHidden();
        rig.keymap.bind(KeyAction.AIM_AT_FLUIDS, List.of(KeyChord.parse("g")));
        InputRouter router = router();
        router.keyPressed(GLFW.GLFW_KEY_G, 0, 0);
        router.keyPressed(GLFW.GLFW_KEY_G, 0, 0);
        router.keyPressed(GLFW.GLFW_KEY_G, 0, 0);
        assertTrue(rig.platform.aimAtFluids, "the key's repeats don't flicker it");
        renderFrame();
        assertTrue(fluidAimButton().isSelected());
        assertEquals("sculptory.topbar.fluid_aim.on  (G)", fluidAimButton().tooltip());
        router.keyReleased(GLFW.GLFW_KEY_G, 0, 0);
        router.keyPressed(GLFW.GLFW_KEY_G, 0, 0);
        assertFalse(rig.platform.aimAtFluids);

        List<HelpSheet.Line> lines = HelpSheet.build(rig.keymap, Translator.KEYS,
                new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E")).stream()
                .flatMap(group -> group.lines().stream()).toList();
        assertTrue(lines.contains(new HelpSheet.Line("G", "sculptory.key.aim_at_fluids", KeyAction.AIM_AT_FLUIDS)),
                "F1 lists it with its key once bound");
    }

    @Test
    void theKeySheetListsAimAtWaterAndLavaWithNoKeyWhileUnbound() {
        List<HelpSheet.Line> lines = HelpSheet.build(rig.keymap, Translator.KEYS,
                new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E")).stream()
                .flatMap(group -> group.lines().stream()).toList();
        assertTrue(lines.contains(new HelpSheet.Line("", "sculptory.key.aim_at_fluids", KeyAction.AIM_AT_FLUIDS)));
    }

    @Test
    void drawingIsUntransformedAtOneHundredPercentAndScaledOtherwise() {
        ScaleRecorder g = new ScaleRecorder();
        ui.render(g, 100, 100, now, false);
        ui.renderPassive(g, 640, 360, now);
        assertEquals(List.of(), g.scales, "no transform at 100%");

        scale.set(80);
        ui.render(g, 100, 100, now, false);
        assertEquals(800, ui.uiWidth(), "a size changed elsewhere applies on the next frame");
        ui.renderPassive(g, 640, 360, now);
        assertEquals(List.of(0.8F, 0.8F), g.scales);
        assertEquals(0, g.depth);
    }

    @Test
    void theHelpSheetAndKeysWindowListTheUiSizeKeys() {
        List<HelpSheet.Line> lines = HelpSheet.build(rig.keymap, Translator.KEYS,
                new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E")).stream()
                .flatMap(group -> group.lines().stream()).toList();
        assertTrue(lines.contains(new HelpSheet.Line("Ctrl+- / Ctrl+Keypad -", "sculptory.key.ui_smaller",
                KeyAction.UI_SMALLER)), "every chord, the keypad's included");
        assertTrue(lines.contains(new HelpSheet.Line("Ctrl+= / Ctrl+Shift+= / Ctrl+Keypad +", "sculptory.key.ui_larger",
                KeyAction.UI_LARGER)));
        assertTrue(lines.contains(new HelpSheet.Line("Ctrl+0 / Ctrl+Keypad 0", "sculptory.key.ui_reset",
                KeyAction.UI_RESET)));
    }

    /** Records scale pushes; draws nothing. */
    private static final class ScaleRecorder implements UiGraphics {
        final List<Float> scales = new ArrayList<>();
        int depth;

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
        public void pushScale(float factor) {
            scales.add(factor);
            depth++;
        }

        @Override
        public void popScale() {
            depth--;
        }

        @Override
        public void item(ItemStack stack, int x, int y) {}

        @Override
        public void widget(ClickableWidget widget, int mouseX, int mouseY, float delta) {}
    }
}
