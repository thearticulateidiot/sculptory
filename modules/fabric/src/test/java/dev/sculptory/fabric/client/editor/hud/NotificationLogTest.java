package dev.sculptory.fabric.client.editor.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.session.Notice;
import java.time.LocalTime;
import java.util.List;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.item.ItemStack;
import org.junit.jupiter.api.Test;

/** The toasts' log (the Notifications window's content) and dismissing a toast with a click. */
class NotificationLogTest {
    private long now = 0;
    private LocalTime time = LocalTime.of(9, 0, 0);
    private final ToastStack toasts = new ToastStack(() -> now, () -> time);

    @Test
    void theLogKeepsTheLastHundredNewestFirst() {
        for (int i = 0; i < 105; i++) {
            toasts.show(Notice.Level.INFO, "message " + i);
        }
        List<NotificationLog.Entry> entries = toasts.log().entries();
        assertEquals(NotificationLog.CAPACITY, entries.size());
        assertEquals("message 104", entries.get(0).text(), "newest first");
        assertEquals("message 5", entries.get(99).text(), "the oldest five are gone");
        assertEquals(ToastStack.MAX, toasts.visible().size(), "the screen still shows only a few");
    }

    @Test
    void eachEntryHasItsLevelAndTimeAndARepeatStraightAfterIsCounted() {
        toasts.show(Notice.Level.WARNING, "careful");
        time = LocalTime.of(9, 0, 5);
        toasts.show(Notice.Level.WARNING, "careful");
        toasts.show(Notice.Level.ERROR, "broken");
        toasts.show(Notice.Level.WARNING, "careful");
        assertEquals(List.of(
                new NotificationLog.Entry(Notice.Level.WARNING, "careful", LocalTime.of(9, 0, 5), 1),
                new NotificationLog.Entry(Notice.Level.ERROR, "broken", LocalTime.of(9, 0, 5), 1),
                new NotificationLog.Entry(Notice.Level.WARNING, "careful", LocalTime.of(9, 0, 5), 2)),
                toasts.log().entries());
    }

    @Test
    void clearEmptiesTheLogAndBlankMessagesAreNotLogged() {
        toasts.show(Notice.Level.INFO, "one");
        toasts.show(Notice.Level.INFO, " ");
        int version = toasts.log().version();
        toasts.log().clear();
        assertTrue(toasts.log().isEmpty());
        assertNotEquals(version, toasts.log().version(), "a view can tell it changed");
        assertEquals(1, toasts.visible().size(), "clearing the log leaves the screen alone");
    }

    @Test
    void aClickOnADrawnToastDismissesItAndOnlyIt() {
        toasts.show(Notice.Level.INFO, "first");
        toasts.show(Notice.Level.INFO, "second");
        assertFalse(toasts.dismissAt(300, 20), "nothing drawn yet");
        toasts.render(new NoGraphics(), KeySheetTest.TEXT, Theme.DARK, 400, 10);
        assertTrue(toasts.toastAt(395, 12).isPresent());
        assertEquals("first", toasts.toastAt(395, 12).orElseThrow().text(), "the oldest is at the top");
        assertTrue(toasts.dismissAt(395, 12));
        assertEquals(List.of("second"), toasts.visible().stream().map(ToastStack.Toast::text).toList());
        assertFalse(toasts.dismissAt(395, 12), "gone; the one below has not moved up until the next frame");
        assertFalse(toasts.dismissAt(100, 12), "left of the toasts");
        assertEquals(2, toasts.log().entries().size(), "dismissed toasts stay in the log");
    }

    @Test
    void levelsHaveTheirColours() {
        Theme theme = Theme.DARK;
        assertEquals(theme.accent, ToastStack.levelColor(Notice.Level.INFO, theme));
        assertEquals(theme.noticeSuccess, ToastStack.levelColor(Notice.Level.SUCCESS, theme));
        assertEquals(theme.noticeWarning, ToastStack.levelColor(Notice.Level.WARNING, theme));
        assertEquals(theme.danger, ToastStack.levelColor(Notice.Level.ERROR, theme));
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
