package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Dropdown;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.ui.window.WindowSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * View > Opacity…'s Panels setting where the UI draws: {@link FadedGraphics} fades a panel's surfaces and nothing else,
 * {@link PanelFade} makes a panel opaque while it is engaged (with "Fade only when not hovered"), and the window
 * manager draws each window so, keeping popups opaque.
 */
class PanelOpacityTest {
    private static final Theme THEME = Theme.DARK;

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    // ---- The settings ----

    @Test
    void valuesAreClampedToTheirRangesAndListenersHearOnlyChanges() {
        assertEquals(new UiOpacity.Values(20, false, 10), new UiOpacity.Values(0, false, -5));
        assertEquals(new UiOpacity.Values(100, true, 100), new UiOpacity.Values(400, true, 101));
        assertEquals(0.2F, new UiOpacity.Values(20, false, 100).panelAlpha());
        assertEquals(0.1F, new UiOpacity.Values(100, false, 10).outlineAlpha());

        UiOpacity opacity = new UiOpacity();
        List<UiOpacity.Values> heard = new ArrayList<>();
        opacity.addListener(heard::add);
        assertFalse(opacity.set(UiOpacity.Values.DEFAULT), "no change");
        assertTrue(opacity.set(UiOpacity.Values.DEFAULT.withPanels(50)));
        assertFalse(opacity.set(new UiOpacity.Values(50, false, 100)));
        assertEquals(List.of(new UiOpacity.Values(50, false, 100)), heard);
    }

    // ---- FadedGraphics ----

    @Test
    void aFadedPanelFadesItsSurfacesButNotWhatItShows() {
        RecordingGraphics g = new RecordingGraphics();
        UiGraphics faded = FadedGraphics.of(g, THEME, 0.4F);
        faded.fill(0, 0, 10, 10, THEME.windowBackground);
        faded.fill(0, 0, 10, 10, THEME.control);
        faded.fill(0, 0, 10, 10, THEME.paletteBackground);
        faded.fill(0, 0, 10, 10, THEME.accent);
        faded.fill(0, 0, 2, 10, THEME.knob);
        faded.fill(0, 0, 10, 10, THEME.selection);
        faded.outline(0, 0, 10, 10, THEME.windowBorder);
        faded.text("Radius", 0, 0, THEME.text, false);
        faded.triangleDown(5, 5, 2, THEME.textDim);

        List<RecordingGraphics.Fill> fills = g.drawnFills();
        assertEquals(0x66, alpha(fills.get(0).argb()), "the window's background at 40%");
        assertEquals(THEME.windowBackground & 0xFFFFFF, fills.get(0).argb() & 0xFFFFFF, "same colour");
        assertEquals(0x66, alpha(fills.get(1).argb()), "a button's background");
        assertEquals(Math.round(0xC0 * 0.4F), alpha(fills.get(2).argb()), "a see-through plate fades further");
        assertEquals(THEME.accent, fills.get(3).argb(), "an accent stays");
        assertEquals(THEME.knob, fills.get(4).argb(), "a handle stays");
        assertEquals(THEME.selection, fills.get(5).argb(), "a tint stays");
        assertTrue(fills.subList(6, fills.size()).stream().allMatch(fill -> fill.argb() == THEME.windowBorder
                || fill.argb() == THEME.textDim), "borders and drawn symbols stay: " + fills);
        assertEquals(List.of(THEME.text), g.textColors());
    }

    @Test
    void anOpaquePanelIsDrawnAsBeforeAndAFaintOneGetsTextShadows() {
        RecordingGraphics g = new RecordingGraphics();
        assertSame(g, FadedGraphics.of(g, THEME, 1.0F), "at 100% nothing is wrapped");

        List<Boolean> shadows = new ArrayList<>();
        UiGraphics shadowRecorder = new ShadowRecorder(shadows);
        FadedGraphics.of(shadowRecorder, THEME, 0.8F).text("a", 0, 0, THEME.text, false);
        FadedGraphics.of(shadowRecorder, THEME, 0.5F).text("b", 0, 0, THEME.text, false);
        FadedGraphics.of(shadowRecorder, THEME, 0.8F).text("c", 0, 0, THEME.text, true);
        assertEquals(List.of(false, true, true), shadows, "below 75% text reads over the world with a shadow");
    }

    @Test
    void aDropShadowOnlyShowsBesideItsRectangle() {
        RecordingGraphics g = new RecordingGraphics();
        Rect rect = new Rect(10, 20, 100, 50);
        g.dropShadow(rect, THEME.windowShadow);
        assertEquals(List.of(new Rect(110, 22, 2, 50), new Rect(12, 70, 98, 2)), g.filledRects());
        assertTrue(g.filledRects().stream().noneMatch(rect::intersects), "nothing under a see-through window");
    }

    // ---- PanelFade ----

    @Test
    void withoutTheToggleEveryPanelFollowsTheSliderAtOnce() {
        UiOpacity opacity = new UiOpacity();
        PanelFade fade = new PanelFade(opacity);
        Object panel = new Object();
        assertEquals(1.0F, fade.alpha(panel, false, 0));
        opacity.set(UiOpacity.Values.DEFAULT.withPanels(40));
        assertEquals(0.4F, fade.alpha(panel, true, 1), "hovered or not, at once (the slider is being dragged)");
        opacity.set(UiOpacity.Values.DEFAULT.withPanels(70));
        assertEquals(0.7F, fade.alpha(panel, false, 2));
    }

    @Test
    void withTheToggleAnEngagedPanelTurnsOpaqueOverAMoment() {
        UiOpacity opacity = new UiOpacity();
        opacity.set(new UiOpacity.Values(40, true, 100));
        PanelFade fade = new PanelFade(opacity);
        Object window = new Object();
        Object other = new Object();
        assertEquals(0.4F, fade.alpha(window, false, 1000), "a panel seen first starts where it should be");
        assertEquals(1.0F, fade.alpha(other, true, 1000));

        float halfway = fade.alpha(window, true, 1000 + PanelFade.FADE_MS / 2);
        assertTrue(halfway > 0.4F && halfway < 1.0F, "on its way: " + halfway);
        assertEquals(1.0F, fade.alpha(window, true, 1000 + PanelFade.FADE_MS));
        assertEquals(1.0F, fade.alpha(window, true, 5000));
        float leaving = fade.alpha(window, false, 5000 + PanelFade.FADE_MS / 3);
        assertTrue(leaving < 1.0F && leaving > 0.4F, "fading back: " + leaving);
        assertEquals(0.4F, fade.alpha(window, false, 5000 + PanelFade.FADE_MS), 1e-6F);
        assertEquals(0.4F, fade.alpha(other, false, 9000), "a long time later: all the way at once");
        assertEquals(0.4F, fade.alpha(other, false, 8000), "a clock going back changes nothing");
    }

    // ---- Windows ----

    private static final int W = 960;
    private static final int H = 540;

    private static WindowManager twoWindows(PanelFade fade, Dropdown<String> dropdown) {
        WindowManager windows = new WindowManager(FakeTextMeasure.INSTANCE, THEME, UnaryOperator.identity());
        windows.register(WindowSpec.builder("left", "Left", () -> Column.of(Label.of("Hello"), new Button("OK", null),
                        dropdown)).anchor(Corner.TOP_LEFT, 10, 10).size(200, 150).minSize(60, 40).build());
        windows.register(WindowSpec.builder("right", "Right", () -> Column.of(Label.of("There")))
                .anchor(Corner.TOP_LEFT, 400, 10).size(200, 150).minSize(60, 40).build());
        windows.setPanelFade(fade);
        windows.layout(W, H);
        return windows;
    }

    /** The alpha the window's body background was drawn with. */
    private static int bodyAlpha(RecordingGraphics g, Window window) {
        Rect body = window.bodyRect(THEME);
        return g.drawnFills().stream().filter(fill -> fill.rect().equals(body)
                        && (fill.argb() & 0xFFFFFF) == (THEME.windowBackground & 0xFFFFFF))
                .map(fill -> alpha(fill.argb())).findFirst().orElseThrow();
    }

    @Test
    void windowsFadeTheirBackgroundsAndKeepTheirTextAndPopupsOpaque() {
        UiOpacity opacity = new UiOpacity();
        opacity.set(UiOpacity.Values.DEFAULT.withPanels(40));
        Dropdown<String> dropdown = new Dropdown<>(List.of("One", "Two"), "One", Objects::toString, value -> { });
        WindowManager windows = twoWindows(new PanelFade(opacity), dropdown);
        Window left = windows.window("left").orElseThrow();

        RecordingGraphics g = new RecordingGraphics();
        windows.render(g, 300, 400, 0);
        assertEquals(0x66, bodyAlpha(g, left));
        assertTrue(g.drawnFills().stream().anyMatch(fill -> fill.argb() == FadedGraphics.fade(THEME.titleBar, 0.4F)
                || fill.argb() == FadedGraphics.fade(THEME.titleBarActive, 0.4F)), "title bars fade too");
        assertTrue(g.textColors().stream().allMatch(color -> alpha(color) == 0xFF), "every text stays solid");
        assertTrue(g.drawnTexts().containsAll(List.of("Left", "Hello", "OK", "One")));
        assertTrue(g.isBalanced());

        // A dropdown's list is a popup: opaque, over a window that stays faded without the toggle.
        dropdown.open(windows.context());
        windows.layout(W, H);
        RecordingGraphics withPopup = new RecordingGraphics();
        windows.render(withPopup, 300, 400, 10);
        Rect popup = windows.context().popups().popups().get(0).rect();
        assertTrue(withPopup.drawnFills().stream().anyMatch(fill -> fill.rect().equals(popup)
                && fill.argb() == THEME.popupBackground), "the popup's background is opaque");
        assertEquals(0x66, bodyAlpha(withPopup, left));
    }

    @Test
    void withTheToggleTheWindowUnderThePointerOrHoldingTheKeyboardIsOpaque() {
        UiOpacity opacity = new UiOpacity();
        opacity.set(new UiOpacity.Values(40, true, 100));
        Dropdown<String> dropdown = new Dropdown<>(List.of("One", "Two"), "One", Objects::toString, value -> { });
        WindowManager windows = twoWindows(new PanelFade(opacity), dropdown);
        Window left = windows.window("left").orElseThrow();
        Window right = windows.window("right").orElseThrow();
        Rect leftRect = left.rect();

        RecordingGraphics away = new RecordingGraphics();
        windows.render(away, 300, 400, 0);
        assertEquals(0x66, bodyAlpha(away, left), "nothing engaged: both at the Panels opacity");
        assertEquals(0x66, bodyAlpha(away, right));

        double overX = leftRect.x() + 50;
        double overY = leftRect.y() + 60;
        windows.mouseMoved(overX, overY);
        RecordingGraphics over = new RecordingGraphics();
        windows.render(over, overX, overY, PanelFade.FADE_MS);
        assertTrue(windows.isEngaged(left));
        assertEquals(0xFF, bodyAlpha(over, left), "the window under the pointer");
        assertEquals(0x66, bodyAlpha(over, right), "the other stays faded");

        // The keyboard in the left window keeps it opaque with the pointer elsewhere.
        Node ok = left.content().children().get(1);
        windows.context().setFocus(ok);
        windows.mouseMoved(300, 400);
        RecordingGraphics focused = new RecordingGraphics();
        windows.render(focused, 300, 400, 2 * PanelFade.FADE_MS);
        assertEquals(0xFF, bodyAlpha(focused, left));
        windows.context().clearFocus();
        RecordingGraphics released = new RecordingGraphics();
        windows.render(released, 300, 400, 4 * PanelFade.FADE_MS);
        assertEquals(0x66, bodyAlpha(released, left), "faded again once nothing holds it");

        // A popup the window opened keeps it opaque while the pointer is on the popup.
        dropdown.open(windows.context());
        windows.layout(W, H);
        Rect popup = windows.context().popups().popups().get(0).rect();
        windows.context().clearFocus();
        RecordingGraphics withPopup = new RecordingGraphics();
        windows.render(withPopup, popup.x() + 2, popup.y() + 2, 6 * PanelFade.FADE_MS);
        assertEquals(0xFF, bodyAlpha(withPopup, left), "its dropdown is open");
        assertEquals(0x66, bodyAlpha(withPopup, right));
    }

    @Test
    void aFadedWindowKeepsTheWikisScaleAndPixelSize() {
        ScaleProbe probe = new ScaleProbe(new ArrayList<>());
        UiGraphics faded = FadedGraphics.of(probe, Theme.DARK, 0.5F);
        faded.pushScale(12, 34, 1.5F);
        assertEquals(List.of("12,34,1.5"), probe.scales(), "the moved origin reaches the real graphics");
        assertEquals(3.0, faded.pixelScale(), "pictures and page text see the real pixel size");
    }

    /** Records scales pushed with an origin; reports 3 pixels per unit. */
    private record ScaleProbe(List<String> scales) implements UiGraphics {
        @Override
        public void pushScale(int x, int y, float factor) {
            scales.add(x + "," + y + "," + factor);
        }

        @Override
        public double pixelScale() {
            return 3.0;
        }

        @Override
        public void fill(int x, int y, int width, int height, int argb) {
        }

        @Override
        public void text(String text, int x, int y, int argb, boolean shadow) {
        }

        @Override
        public void pushClip(Rect clip) {
        }

        @Override
        public void popClip() {
        }

        @Override
        public void pushLayer(int z) {
        }

        @Override
        public void popLayer() {
        }

        @Override
        public void pushScale(float factor) {
            scales.add("plain," + factor);
        }

        @Override
        public void popScale() {
        }

        @Override
        public void item(ItemStack stack, int x, int y) {
        }

        @Override
        public void widget(ClickableWidget widget, int mouseX, int mouseY, float delta) {
        }
    }

    /** Records only whether each text asked for a shadow. */
    private record ShadowRecorder(List<Boolean> shadows) implements UiGraphics {
        @Override
        public void fill(int x, int y, int width, int height, int argb) {
        }

        @Override
        public void text(String text, int x, int y, int argb, boolean shadow) {
            shadows.add(shadow);
        }

        @Override
        public void pushClip(Rect clip) {
        }

        @Override
        public void popClip() {
        }

        @Override
        public void pushLayer(int z) {
        }

        @Override
        public void popLayer() {
        }

        @Override
        public void pushScale(float factor) {
        }

        @Override
        public void popScale() {
        }

        @Override
        public void item(ItemStack stack, int x, int y) {
        }

        @Override
        public void widget(ClickableWidget widget, int mouseX, int mouseY, float delta) {
        }
    }
}
