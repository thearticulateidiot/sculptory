package dev.sculptory.fabric.client.editor.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Popups drawn above every window: dropdown lists and context menus. A click outside all popups
 * closes them (and is consumed); Esc closes the top one. Placement is pure and unit-testable.
 */
public final class PopupLayer {
    /** One open popup. */
    public static final class Popup {
        private final Node owner;
        private final Node content;
        private final Rect anchor;
        private final int minWidth;
        private final Runnable onClose;
        private Rect rect = Rect.EMPTY;
        private int backdrop;

        private Popup(Node owner, Node content, Rect anchor, int minWidth, Runnable onClose) {
            this.owner = owner;
            this.content = Objects.requireNonNull(content);
            this.anchor = Objects.requireNonNull(anchor);
            this.minWidth = minWidth;
            this.onClose = onClose;
        }

        /** The node that opened the popup, or {@code null}. The popup closes if the owner goes away. */
        public Node owner() {
            return owner;
        }

        public Node content() {
            return content;
        }

        public Rect anchor() {
            return anchor;
        }

        /** Where the popup is drawn, after the last layout. */
        public Rect rect() {
            return rect;
        }

        /**
         * Dims the whole screen under the popup with this colour (0: nothing, the default): for a popup the player
         * should look at alone (a picture shown full size). A click on the dimmed screen closes it like any click
         * outside.
         */
        public Popup setBackdrop(int argb) {
            this.backdrop = argb;
            return this;
        }

        public int backdrop() {
            return backdrop;
        }
    }

    private final List<Popup> popups = new ArrayList<>();

    /**
     * Opens a popup below {@code anchor} (above it if there's no room). Use a zero-size anchor at
     * the cursor for a context menu. {@code onClose} runs however the popup closes.
     */
    public Popup open(Node owner, Node content, Rect anchor, int minWidth, Runnable onClose) {
        Popup popup = new Popup(owner, content, anchor, minWidth, onClose);
        popups.add(popup);
        return popup;
    }

    public boolean isOpen() {
        return !popups.isEmpty();
    }

    public List<Popup> popups() {
        return Collections.unmodifiableList(popups);
    }

    public void close(Popup popup) {
        if (popup != null && popups.remove(popup) && popup.onClose != null) {
            popup.onClose.run();
        }
    }

    public void closeTop() {
        if (!popups.isEmpty()) {
            close(popups.get(popups.size() - 1));
        }
    }

    public void closeAll() {
        while (!popups.isEmpty()) {
            closeTop();
        }
    }

    public void closeIf(Predicate<Popup> condition) {
        for (Popup popup : List.copyOf(popups)) {
            if (condition.test(popup)) {
                close(popup);
            }
        }
    }

    /** The topmost popup containing the point, or {@code null}. */
    public Popup popupAt(double x, double y) {
        for (int i = popups.size() - 1; i >= 0; i--) {
            if (popups.get(i).rect.contains(x, y)) {
                return popups.get(i);
            }
        }
        return null;
    }

    public boolean isPopupRoot(Node root) {
        for (Popup popup : popups) {
            if (popup.content == root) {
                return true;
            }
        }
        return false;
    }

    public void layout(UiContext ctx, int screenWidth, int screenHeight) {
        for (Popup popup : popups) {
            Size size = popup.content.measure(ctx, Math.max(0, screenWidth - 2));
            int width = Math.min(Math.max(size.width() + 2, popup.minWidth), screenWidth);
            int height = Math.min(size.height() + 2, screenHeight);
            popup.rect = place(popup.anchor, width, height, screenWidth, screenHeight);
            popup.content.layout(ctx, popup.rect.inset(1));
        }
    }

    /** Below the anchor if it fits, else above, else pinned to the screen; clamped horizontally. */
    public static Rect place(Rect anchor, int width, int height, int screenWidth, int screenHeight) {
        int y = anchor.bottom();
        if (y + height > screenHeight) {
            int above = anchor.y() - height;
            y = above >= 0 ? above : Math.max(0, screenHeight - height);
        }
        int x = Math.max(0, Math.min(anchor.x(), screenWidth - width));
        return new Rect(x, y, width, height);
    }

    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        for (Popup popup : popups) {
            Rect rect = popup.rect;
            if (popup.backdrop != 0) {
                g.fill(0, 0, ctx.screenWidth(), ctx.screenHeight(), popup.backdrop);
            }
            g.fill(rect.x() + 2, rect.y() + 2, rect.width(), rect.height(), theme.windowShadow);
            g.fill(rect, theme.popupBackground);
            g.pushClip(rect.inset(1));
            popup.content.render(g, ctx);
            g.popClip();
            g.outline(rect, theme.popupBorder);
        }
    }
}
