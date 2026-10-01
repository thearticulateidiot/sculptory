package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.ScrollModel;
import dev.sculptory.fabric.client.editor.ui.Scrollbar;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.List;
import org.lwjgl.glfw.GLFW;

/**
 * Scrolls one child vertically when it is taller than the pane, with a scrollbar and clipping.
 * Use it as a window's content when that content can outgrow the window (settings forms).
 * Content shorter than the pane is stretched to the pane's height, so a child that grows (a list) uses the room.
 * Keyboard focus moving onto a hidden child scrolls it into view. While a control inside the pane has the keyboard,
 * Page Up/Down and Home/End that the control doesn't use scroll the pane (arrow keys stay with the control); a pane
 * made focusable ({@link #setFocusable}) also takes them, and the arrow keys, itself. For long uniform lists use
 * {@link ListView}, which doesn't build off-screen rows.
 */
public class ScrollPane extends Node {
    private final Node content;
    private final ScrollModel scroll = new ScrollModel();
    private final Scrollbar scrollbar = new Scrollbar(scroll);
    private Rect track = Rect.EMPTY;
    private Node lastFocused;
    private boolean focusable;
    /** An offset to apply at the next layout (the content's height isn't known before), or -1. */
    private int pendingOffset = -1;
    /** A node to scroll into view at the next layout, or null. */
    private Node pendingReveal;

    public ScrollPane(Node content) {
        this.content = adopt(content);
    }

    public Node content() {
        return content;
    }

    public ScrollModel scroll() {
        return scroll;
    }

    /** Whether the pane itself can take the keyboard (Tab, or a click on it) to scroll with keys. Off by default. */
    public ScrollPane setFocusable(boolean focusable) {
        this.focusable = focusable;
        return this;
    }

    /** Scrolls to {@code offset} pixels once the content has been laid out (a remembered scroll position). */
    public void restoreOffset(int offset) {
        pendingOffset = Math.max(0, offset);
        scroll.setOffset(offset);
    }

    /** Scrolls the least amount that shows {@code node} (inside the content), at the next layout. */
    public void scrollIntoView(Node node) {
        pendingReveal = node;
    }

    @Override
    public List<Node> children() {
        return List.of(content);
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int barWidth = ctx.theme().scrollbarWidth + 1;
        int inner = maxWidth == Integer.MAX_VALUE ? maxWidth : Math.max(0, maxWidth - barWidth);
        Size size = content.measure(ctx, inner);
        return new Size(size.width() + barWidth, size.height());
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        int barWidth = ctx.theme().scrollbarWidth + 1;
        Size natural = content.measure(ctx, bounds.width());
        boolean needsBar = natural.height() > bounds.height();
        int width = needsBar ? bounds.width() - barWidth : bounds.width();
        Size size = needsBar ? content.measure(ctx, width) : natural;
        scroll.setExtent(size.height(), bounds.height());
        if (pendingOffset >= 0) {
            scroll.setOffset(pendingOffset);
            pendingOffset = -1;
        }
        track = needsBar ? Scrollbar.track(bounds, ctx.theme()) : Rect.EMPTY;
        Node focused = ctx.focused();
        if (pendingReveal == null && focused != lastFocused && focused != null && focused != this
                && focused.isDescendantOf(content)) {
            int top = focused.bounds().y() - content.bounds().y();
            scroll.ensureVisible(top, top + focused.bounds().height());
        }
        lastFocused = focused;
        Rect contentRect = new Rect(bounds.x(), bounds.y() - scroll.offset(), width,
                Math.max(size.height(), bounds.height()));
        content.layout(ctx, contentRect);
        if (pendingReveal != null) {
            Node reveal = pendingReveal;
            pendingReveal = null;
            if (reveal.isShown() && reveal.isDescendantOf(content)) {
                int before = scroll.offset();
                int top = reveal.bounds().y() - content.bounds().y();
                scroll.ensureVisible(top, top + reveal.bounds().height());
                if (scroll.offset() != before) {
                    content.layout(ctx, new Rect(bounds.x(), bounds.y() - scroll.offset(), width,
                            Math.max(size.height(), bounds.height())));
                }
            }
        }
    }

    @Override
    public Node hitTest(double x, double y) {
        if (!isVisible() || !bounds.contains(x, y)) {
            return null;
        }
        if (track.contains(x, y)) {
            return this;
        }
        Node hit = content.hitTest(x, y);
        return hit != null ? hit : this;
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        g.pushClip(bounds);
        content.render(g, ctx);
        g.popClip();
        scrollbar.render(g, ctx, track);
        if (ctx.isFocused(this)) {
            g.outline(bounds, ctx.theme().focusRing);
        }
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        return scrollbar.mouseDown(track, ctx.theme(), x, y) || focusable && scroll.isScrollable();
    }

    @Override
    public void mouseDrag(UiContext ctx, double x, double y, int button) {
        scrollbar.mouseDrag(track, ctx.theme(), y);
    }

    @Override
    public void mouseUp(UiContext ctx, double x, double y, int button) {
        scrollbar.mouseUp();
    }

    @Override
    public boolean mouseScroll(UiContext ctx, double x, double y, double amount) {
        if (!scroll.isScrollable() || amount == 0) {
            return false;
        }
        scroll.scrollBy((int) Math.round(-amount * ctx.theme().rowHeight * 2));
        return true;
    }

    /**
     * Page Up/Down scroll by a page (less a row, so a line stays in view), Home/End to the top and bottom; the arrow
     * keys scroll by two rows only while the pane itself is focused. Keys reach the pane after the focused control
     * inside it has passed on them.
     */
    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        if (!scroll.isScrollable()) {
            return false;
        }
        int row = ctx.theme().rowHeight;
        int page = Math.max(row, scroll.viewportSize() - row);
        boolean self = ctx.isFocused(this);
        switch (keyCode) {
            case GLFW.GLFW_KEY_PAGE_UP -> scroll.scrollBy(-page);
            case GLFW.GLFW_KEY_PAGE_DOWN -> scroll.scrollBy(page);
            case GLFW.GLFW_KEY_HOME -> scroll.setOffset(0);
            case GLFW.GLFW_KEY_END -> scroll.setOffset(scroll.maxOffset());
            case GLFW.GLFW_KEY_UP -> {
                if (!self) {
                    return false;
                }
                scroll.scrollBy(-2 * row);
            }
            case GLFW.GLFW_KEY_DOWN -> {
                if (!self) {
                    return false;
                }
                scroll.scrollBy(2 * row);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isFocusable() {
        return focusable;
    }

    @Override
    public boolean focusOnClick() {
        return focusable;
    }
}
