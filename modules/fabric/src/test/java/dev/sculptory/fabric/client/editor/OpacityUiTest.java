package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.commands.EditorCommands;
import dev.sculptory.fabric.client.editor.commands.SearchEntry;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.hud.ToastStack;
import dev.sculptory.fabric.client.editor.ui.FadedGraphics;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PanelFade;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiOpacity;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Menu;
import dev.sculptory.fabric.client.editor.ui.widget.MenuItem;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.editor.windows.OpacityPopup;
import dev.sculptory.fabric.client.session.Notice;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

/**
 * View > Opacity… on the whole editor UI: the popup from the View menu and the command
 * search, laid out at UI 50% and 100% on the reference screen in English with Minecraft's font widths, its sliders taking
 * typed values and resetting, and the Panels opacity reaching the top bar, palette, hint line and toasts but not the
 * quick start card.
 */
class OpacityUiTest {
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;
    /** The reference screen in GUI pixels: 2560x1600 at 150% and GUI scale 6 is about 427x249. */
    private static final int SCREEN_W = 427;
    private static final int SCREEN_H = 249;
    private static final Theme THEME = Theme.DARK;

    private final EditorTestRig rig = new EditorTestRig();
    private final UiScale scale = new UiScale();
    private final UiOpacity opacity = new UiOpacity();
    private long now = 1_000;
    private final ToastStack toasts = new ToastStack(() -> now);
    private final EditorUi ui = new EditorUi(rig.ctx, rig.controller, rig.platform, rig.actions, McFontText.INSTANCE,
            toasts, new EditorUi.Services(English.INSTANCE, BlockCatalog.EMPTY, icon -> null,
                    () -> new HelpSheet.VanillaKeys("W A S D", "Space", "Shift", "B", "T", "/", "E"),
                    "config/sculptory/editor-keys.json", keymap -> true, scale, opacity));

    OpacityUiTest() {
        rig.controller.setUi(ui);
        rig.confirmer = ui;
        ui.setEditing(true);
        rig.mode.enter();
        ui.layout(SCREEN_W, SCREEN_H);
    }

    private UiContext popupContext() {
        return ui.windows().context();
    }

    private PopupLayer.Popup onlyPopup() {
        List<PopupLayer.Popup> popups = popupContext().popups().popups();
        assertEquals(1, popups.size());
        return popups.get(0);
    }

    // ---- Finding it ----

    @Test
    void viewOpacityAndTheCommandSearchOpenThePopup() {
        assertEquals("Opacity…", ui.commands().label(ui.commands().get(EditorCommands.OPACITY).orElseThrow()));
        assertTrue(ui.commands().run(EditorCommands.OPACITY).enabled());
        ui.layout(SCREEN_W, SCREEN_H);
        assertTrue(onlyPopup().content().isShown());
        popupContext().popups().closeAll();

        // From the View menu: the menu closes, then the popup opens and stays.
        ui.menuBar().open(4, false);
        ui.layout(SCREEN_W, SCREEN_H);
        Menu view = ui.menuBar().openMenu().orElseThrow();
        List<String> labels = view.items().stream().map(MenuItem::label).toList();
        assertEquals(labels.indexOf("UI size") + 1, labels.indexOf("Opacity…"), "after UI size: " + labels);
        view.activate(labels.indexOf("Opacity…"));
        ui.layout(SCREEN_W, SCREEN_H);
        assertFalse(ui.menuBar().isMenuOpen());
        assertTrue(onlyPopup().content() instanceof Padding, "the Opacity popup");
        popupContext().popups().closeAll();

        ui.commandSearch().open(20);
        ui.commandSearch().setQuery("opacity");
        SearchEntry best = ui.commandSearch().results().get(0);
        assertEquals("Opacity…", best.name());
        assertEquals("View", best.category());
        assertTrue(ui.commandSearch().runSelected(), "Enter runs it");
        ui.layout(SCREEN_W, SCREEN_H);
        assertFalse(ui.commandSearch().isOpen());
        assertTrue(onlyPopup().rect().width() > 0, "the popup took the search's place");
    }

    // ---- Layout ----

    @Test
    void thePopupFitsWholeAtTheOwnersSizesWithItsResetButtonsShowingOrNot() {
        for (int percent : List.of(50, 100)) {
            scale.set(percent);
            opacity.set(UiOpacity.Values.DEFAULT);
            popupContext().popups().closeAll();
            ui.layout(SCREEN_W, SCREEN_H);
            OpacityPopup popup = ui.openOpacity();
            ui.layout(SCREEN_W, SCREEN_H);
            assertFits(popup, percent + "% at the defaults");
            assertFalse(popup.form().resetButton("panels").orElseThrow().isVisible());

            opacity.set(new UiOpacity.Values(55, true, 30));
            popup.form().refresh(OpacityPopup.values(opacity.values()));
            ui.layout(SCREEN_W, SCREEN_H);
            assertTrue(popup.form().resetButton("panels").orElseThrow().isVisible());
            assertTrue(popup.form().resetButton("fade_unless_hovered").orElseThrow().isVisible());
            assertFits(popup, percent + "% changed");
        }
    }

    private void assertFits(OpacityPopup popup, String where) {
        Rect rect = popup.popup().rect();
        assertTrue(rect.x() >= 0 && rect.y() >= 0 && rect.right() <= ui.uiWidth() && rect.bottom() <= ui.uiHeight(),
                where + ": on screen " + rect + " in " + ui.uiWidth() + "x" + ui.uiHeight());
        Node content = popup.popup().content();
        assertTrue(content.measure(popupContext(), rect.width() - 2).height() <= rect.height() - 2,
                where + ": nothing cut off at the bottom");
        RecordingGraphics g = new RecordingGraphics();
        content.render(g, popupContext());
        List<String> texts = g.drawnTexts();
        assertEquals(List.of(), texts.stream().filter(text -> text.endsWith(TextLayout.ELLIPSIS)).toList(), where);
        assertTrue(texts.containsAll(List.of("Opacity", "Panels (%)", "Fade only when not hovered", "Tool outlines (%)",
                "Changes apply at once · Esc closes")), where + ": " + texts);
    }

    // ---- Using it ----

    private void key(int keyCode) {
        ui.windows().keyPressed(keyCode, 0, 0);
        ui.layout(SCREEN_W, SCREEN_H);
    }

    private void click(Node node) {
        double x = node.bounds().x() + node.bounds().width() / 2.0;
        double y = node.bounds().y() + node.bounds().height() / 2.0;
        ui.windows().mouseDown(x, y, LEFT, 0);
        ui.windows().mouseUp(x, y, LEFT);
        ui.layout(SCREEN_W, SCREEN_H);
    }

    @Test
    void theSlidersTakeTypedValuesAndResetLikeToolSettings() {
        OpacityPopup popup = ui.openOpacity();
        ui.layout(SCREEN_W, SCREEN_H);
        Slider panels = (Slider) popup.form().control("panels").orElseThrow();
        assertSame(panels, popupContext().focused(), "the keyboard starts on Panels");
        assertTrue(panels.tooltip().startsWith("How opaque the backgrounds"), panels.tooltip());

        key(GLFW.GLFW_KEY_ENTER);
        assertTrue(panels.isEditing());
        panels.editor().orElseThrow().setText("55");
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(55, opacity.values().panels(), "typed and applied at once");

        key(GLFW.GLFW_KEY_ENTER);
        panels.editor().orElseThrow().setText("5");
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(20, opacity.values().panels(), "clamped to the lowest setting");

        key(GLFW.GLFW_KEY_ENTER);
        panels.editor().orElseThrow().setText("half");
        key(GLFW.GLFW_KEY_ENTER);
        assertEquals(20, opacity.values().panels(), "not a number: nothing changes");

        Slider outlines = (Slider) popup.form().control("tool_outlines").orElseThrow();
        assertTrue(outlines.tooltip().startsWith("How opaque everything tools draw"), outlines.tooltip());
        popupContext().setFocus(outlines);
        key(GLFW.GLFW_KEY_HOME);
        assertEquals(10, opacity.values().toolOutlines(), "Home: the lowest setting, never 0");

        Toggle fade = (Toggle) popup.form().control("fade_unless_hovered").orElseThrow();
        click(fade);
        assertTrue(opacity.values().fadeUnlessHovered());
        assertEquals(new UiOpacity.Values(20, true, 10), opacity.values());

        Button reset = popup.form().resetButton("panels").orElseThrow();
        assertTrue(reset.isVisible());
        assertEquals("Reset to default: 100", reset.tooltip());
        click(reset);
        assertEquals(100, opacity.values().panels());
        assertEquals(100, panels.intValue(), "the slider shows it");
        assertFalse(reset.isVisible());
        click(popup.form().resetButton("tool_outlines").orElseThrow());
        click(popup.form().resetButton("fade_unless_hovered").orElseThrow());
        assertEquals(UiOpacity.Values.DEFAULT, opacity.values());

        key(GLFW.GLFW_KEY_ESCAPE);
        popupContext().popups().closeTop();
        assertFalse(popupContext().popups().isOpen());
    }

    // ---- What fades ----

    /** Draws a frame with the pointer at a screen point and returns what was drawn. */
    private RecordingGraphics frame(double x, double y) {
        ui.layout(SCREEN_W, SCREEN_H);
        RecordingGraphics g = new RecordingGraphics();
        ui.render(g, x, y, now, false);
        return g;
    }

    private static boolean filled(RecordingGraphics g, int argb) {
        return g.drawnFills().stream().anyMatch(fill -> fill.argb() == argb);
    }

    @Test
    void thePanelsOpacityFadesTheHudAndToastsButNotTheQuickStartCard() {
        toasts.show(Notice.Level.INFO, "Filled 10 blocks");
        ui.showQuickStart();
        opacity.set(UiOpacity.Values.DEFAULT.withPanels(40));
        RecordingGraphics g = frame(200, 200);
        assertEquals(0.4F, ui.hudAlpha());
        assertEquals(0.4F, ui.toastAlpha());
        assertTrue(filled(g, FadedGraphics.fade(THEME.topBarBackground, 0.4F)), "the top bar");
        assertTrue(filled(g, FadedGraphics.fade(THEME.paletteBackground, 0.4F)), "the palette");
        assertTrue(filled(g, FadedGraphics.fade(THEME.hintBackground, 0.4F)), "the hint line");
        assertTrue(filled(g, FadedGraphics.fade(THEME.popupBackground, 0.4F)), "the toast");
        assertTrue(filled(g, THEME.popupBackground), "the quick start card stays opaque");
        assertTrue(g.textColors().stream().allMatch(color -> color >>> 24 == 0xFF), "all text solid");
        assertTrue(g.drawnTexts().contains("Filled 10 blocks"));
        assertTrue(g.isBalanced());
    }

    @Test
    void withTheToggleTheHudIsOpaqueWhileThePointerIsOnIt() {
        opacity.set(new UiOpacity.Values(40, true, 100));
        frame(200, 200);
        assertEquals(0.4F, ui.hudAlpha(), "the pointer over the world");

        now += PanelFade.FADE_MS;
        RecordingGraphics overTopBar = frame(100, 5);
        assertEquals(1.0F, ui.hudAlpha(), "over the top bar: the top bar, palette and hint line together");
        assertTrue(filled(overTopBar, THEME.topBarBackground));
        assertTrue(filled(overTopBar, THEME.paletteBackground));

        now += PanelFade.FADE_MS;
        frame(200, 120);
        assertEquals(0.4F, ui.hudAlpha(), 1e-6F);

        Rect slot = ui.paletteSlotBounds(1).orElseThrow();
        now += PanelFade.FADE_MS;
        double factor = scale.factor();
        frame((slot.x() + 2) * factor, (slot.y() + 2) * factor);
        assertEquals(1.0F, ui.hudAlpha(), "over the palette");
    }

    // ---- What shows through a faded panel ----

    private void drag(double fromX, double fromY, double toX, double toY) {
        assertTrue(ui.windows().mouseDown(fromX, fromY, LEFT, 0));
        ui.windows().mouseDragged(toX, toY, LEFT);
        ui.windows().mouseUp(toX, toY, LEFT);
    }

    private Rect rect(String id) {
        return ui.windows().window(id).orElseThrow().rect();
    }

    @Test
    void aFadedWindowShowsTheWorldThroughItButNotTheWindowUnderIt() {
        scale.set(50);
        ui.toggleWindow(EditorWindows.KEYS);
        ui.toggleWindow(EditorWindows.HISTORY);
        frame(0, 0);
        Rect keys = rect(EditorWindows.KEYS);
        Rect history = rect(EditorWindows.HISTORY);
        // History dragged over the middle of Keys (whose search field draws plainly without a running client).
        drag(history.x() + 30, history.y() + 5, keys.x() + 40, keys.y() + 45);
        Rect over = rect(EditorWindows.HISTORY);
        assertTrue(over.intersects(keys));

        for (int panels : List.of(100, 60, 20)) {
            opacity.set(UiOpacity.Values.DEFAULT.withPanels(panels));
            RecordingGraphics g = frame(0, 0);
            String at = " at Panels " + panels + "%";
            assertEquals(List.of(), g.textsShowingThrough(over), "no text of Keys through History" + at);
            assertTrue(g.drawnTexts().contains("Keys"), "Keys is still drawn where History leaves it" + at);
            assertTrue(g.isBalanced());
            if (panels < 100) {
                assertTrue(filled(g, FadedGraphics.fade(THEME.windowBackground, panels / 100.0F)),
                        "History's body still lets the world through" + at);
            }
        }
    }

    @Test
    void aFadedWindowOverTheTopBarAndFadedToastsOverAWindowHideWhatIsUnderThem() {
        scale.set(100);
        opacity.set(UiOpacity.Values.DEFAULT.withPanels(40));
        frame(0, 0);
        // Selection dragged up over the middle of the top bar (and over Tool Settings), right of the File menu.
        Rect selection = rect(EditorWindows.SELECTION);
        drag(selection.x() + 30, selection.y() + 5, selection.x() + 180, 8);
        RecordingGraphics g = frame(0, 0);
        Rect moved = rect(EditorWindows.SELECTION);
        assertTrue(moved.y() < 20, "over the top bar: " + moved);
        assertEquals(List.of(), g.textsShowingThrough(moved), "no top bar or Tool Settings text through Selection");
        assertTrue(g.drawnTexts().contains("File"), "the menus the window leaves are drawn");

        // Toasts over Selection and Tool Settings (under their title bars): no window text through them. (Text under
        // a toast's shadow, between two toasts, is only darkened, as at any opacity.)
        for (int i = 0; i < ToastStack.MAX; i++) {
            toasts.show(Notice.Level.INFO, "a message long enough to wrap onto a second line of the toast " + i);
        }
        frame(0, 0);
        g = frame(0, 0);
        List<Rect> drawn = toasts.drawnRects();
        assertEquals(ToastStack.MAX, drawn.size());
        for (Rect toast : drawn) {
            assertTrue(toast.intersects(moved) || toast.intersects(rect(EditorWindows.TOOL_SETTINGS)));
            assertEquals(List.of(), g.textsShowingThrough(toast), "no window text through the toast at " + toast);
        }
        assertTrue(g.isBalanced());
    }

    @Test
    void withTheToggleAnOpaqueWindowUnderAFadedOneIsLeftOutUnderIt() {
        scale.set(50);
        opacity.set(new UiOpacity.Values(40, true, 100));
        ui.toggleWindow(EditorWindows.KEYS);
        ui.toggleWindow(EditorWindows.HISTORY);
        frame(0, 0);
        Rect keys = rect(EditorWindows.KEYS);
        Rect history = rect(EditorWindows.HISTORY);
        drag(history.x() + 30, history.y() + 5, keys.x() + 40, keys.y() + 45);
        Rect over = rect(EditorWindows.HISTORY);
        // Pointer over the part of Keys that History leaves: Keys opaque, History faded over it.
        double factor = scale.factor();
        now += PanelFade.FADE_MS;
        frame((keys.x() + 5) * factor, (keys.bottom() - 5) * factor);
        now += PanelFade.FADE_MS;
        RecordingGraphics g = frame((keys.x() + 5) * factor, (keys.bottom() - 5) * factor);
        assertTrue(filled(g, THEME.windowBackground), "Keys drawn opaque while hovered");
        assertTrue(filled(g, FadedGraphics.fade(THEME.windowBackground, 0.4F)), "History faded");
        assertEquals(List.of(), g.textsShowingThrough(over));
    }
}
