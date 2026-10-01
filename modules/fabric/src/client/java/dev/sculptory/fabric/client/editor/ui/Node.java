package dev.sculptory.fabric.client.editor.ui;

import java.util.List;

/**
 * Base of the retained-mode UI tree. A frame runs {@link #measure}, then {@link #layout} with
 * absolute screen bounds, then {@link #render}. Input arrives between frames and uses the bounds
 * from the last layout.
 *
 * <p>Mouse events are dispatched to the deepest node under the cursor ({@link #hitTest}) and bubble
 * to its ancestors until one returns {@code true}. That node then receives the matching drag and
 * release events. Key and character events go to the focused node and bubble the same way.
 *
 * <p>Measurement, layout, hit-testing and focus are pure; only {@link #render} draws, through
 * {@link UiGraphics}.
 */
public abstract class Node {
    private Node parent;
    protected Rect bounds = Rect.EMPTY;
    private boolean visible = true;
    private boolean enabled = true;
    private String tooltip;
    private int minWidth;
    private int minHeight;
    private int fixedWidth = -1;
    private int fixedHeight = -1;
    private float grow;

    // ---- Tree ----

    public final Node parent() {
        return parent;
    }

    /** Current children in paint order (later children draw on top and are hit first). */
    public List<Node> children() {
        return List.of();
    }

    public final Node root() {
        Node node = this;
        while (node.parent != null) {
            node = node.parent;
        }
        return node;
    }

    /** True if {@code ancestor} is this node or one of its ancestors. */
    public final boolean isDescendantOf(Node ancestor) {
        for (Node node = this; node != null; node = node.parent) {
            if (node == ancestor) {
                return true;
            }
        }
        return false;
    }

    /** Makes {@code child} a child of this node. A node can have only one parent. */
    protected final <T extends Node> T adopt(T child) {
        Node node = child;
        if (node.parent != null && node.parent != this) {
            throw new IllegalStateException("Node already has a parent: " + child);
        }
        node.parent = this;
        return child;
    }

    protected final void release(Node child) {
        if (child.parent == this) {
            child.parent = null;
        }
    }

    // ---- Properties ----

    public final Rect bounds() {
        return bounds;
    }

    public final boolean isVisible() {
        return visible;
    }

    /** Invisible nodes take no space, are not drawn and receive no input. */
    public Node setVisible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** True if this node and all its ancestors are visible. */
    public final boolean isShown() {
        for (Node node = this; node != null; node = node.parent) {
            if (!node.visible) {
                return false;
            }
        }
        return true;
    }

    public final boolean isEnabled() {
        return enabled;
    }

    /** Disabled nodes draw greyed out, still show tooltips and swallow clicks without acting. */
    public Node setEnabled(boolean enabled) {
        this.enabled = enabled;
        return this;
    }

    /** True if this node and all its ancestors are enabled. */
    public final boolean isEffectivelyEnabled() {
        for (Node node = this; node != null; node = node.parent) {
            if (!node.enabled) {
                return false;
            }
        }
        return true;
    }

    /** Text shown after hovering for a moment, or {@code null}. */
    public String tooltip() {
        return tooltip;
    }

    public Node setTooltip(String tooltip) {
        this.tooltip = tooltip;
        return this;
    }

    /** Lower bounds for the measured size; also the floor when a container has to shrink this node. */
    public Node setMinSize(int width, int height) {
        this.minWidth = Math.max(0, width);
        this.minHeight = Math.max(0, height);
        return this;
    }

    /** Forces the width; negative clears it. */
    public Node setFixedWidth(int width) {
        this.fixedWidth = width;
        return this;
    }

    /** Forces the height; negative clears it. */
    public Node setFixedHeight(int height) {
        this.fixedHeight = height;
        return this;
    }

    public Node setFixedSize(int width, int height) {
        setFixedWidth(width);
        return setFixedHeight(height);
    }

    /** Share of spare main-axis space this node takes in a {@code Row} or {@code Column}. */
    public Node setGrow(float grow) {
        this.grow = Math.max(0, grow);
        return this;
    }

    public final float grow() {
        return grow;
    }

    public final boolean hasFixedWidth() {
        return fixedWidth >= 0;
    }

    public final boolean hasFixedHeight() {
        return fixedHeight >= 0;
    }

    /** The smallest width a container may shrink this node to. */
    public final int minWidth() {
        return hasFixedWidth() ? fixedWidth : minWidth;
    }

    /** The smallest height a container may shrink this node to. */
    public final int minHeight() {
        return hasFixedHeight() ? fixedHeight : minHeight;
    }

    // ---- Measure and layout ----

    /**
     * Preferred size, respecting fixed and minimum sizes. {@code maxWidth} is the width the parent
     * can offer ({@link Integer#MAX_VALUE} when unconstrained); wrapping text uses it.
     */
    public final Size measure(UiContext ctx, int maxWidth) {
        int limit = hasFixedWidth() ? fixedWidth : maxWidth;
        Size content = measureContent(ctx, limit);
        int width = hasFixedWidth() ? fixedWidth : Math.max(minWidth, content.width());
        int height = hasFixedHeight() ? fixedHeight : Math.max(minHeight, content.height());
        return new Size(width, height);
    }

    /** Natural size of the content, before fixed and minimum sizes are applied. */
    protected abstract Size measureContent(UiContext ctx, int maxWidth);

    /** Places this node at absolute {@code bounds}. Containers override this to place children. */
    public void layout(UiContext ctx, Rect bounds) {
        this.bounds = bounds;
    }

    // ---- Rendering ----

    /** Draws this node and its children. The default draws only the children. */
    public void render(UiGraphics g, UiContext ctx) {
        renderChildren(g, ctx);
    }

    protected final void renderChildren(UiGraphics g, UiContext ctx) {
        for (Node child : children()) {
            if (child.isVisible()) {
                child.render(g, ctx);
            }
        }
    }

    /** Colour for body text in the node's current enabled state. */
    protected final int textColor(UiContext ctx) {
        return isEffectivelyEnabled() ? ctx.theme().text : ctx.theme().textDisabled;
    }

    // ---- Hit testing ----

    /** The deepest visible node containing the point, or {@code null}. */
    public Node hitTest(double x, double y) {
        if (!visible || !bounds.contains(x, y)) {
            return null;
        }
        List<Node> children = children();
        for (int i = children.size() - 1; i >= 0; i--) {
            Node hit = children.get(i).hitTest(x, y);
            if (hit != null) {
                return hit;
            }
        }
        return this;
    }

    // ---- Input (return true to consume) ----

    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        return false;
    }

    /** Sent to the node that consumed the matching {@link #mouseDown}. */
    public void mouseUp(UiContext ctx, double x, double y, int button) {
    }

    /** Sent to the node that consumed the matching {@link #mouseDown}, with absolute coordinates. */
    public void mouseDrag(UiContext ctx, double x, double y, int button) {
    }

    /** Positive {@code amount} scrolls up. Read {@link UiContext#modifiers()} for Shift/Ctrl. */
    public boolean mouseScroll(UiContext ctx, double x, double y, double amount) {
        return false;
    }

    /** Sent to the hovered node when the pointer moves. */
    public void mouseMove(UiContext ctx, double x, double y) {
    }

    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        return false;
    }

    public boolean charTyped(UiContext ctx, char chr, int modifiers) {
        return false;
    }

    /**
     * Esc while this node or one inside it has the keyboard: return {@code true} to use it (an edit in place cancels
     * itself), before Esc clears the focus. Offered to the focused node, then its ancestors.
     */
    public boolean escapePressed(UiContext ctx) {
        return false;
    }

    // ---- Focus ----

    /** Whether keyboard focus can land here (Tab traversal, {@link UiContext#setFocus}). */
    public boolean isFocusable() {
        return false;
    }

    /**
     * Whether a click focuses this node. Only nodes that need the keyboard afterwards (text, lists)
     * take focus on click, so a click on a slider doesn't steal the editor's shortcut keys.
     */
    public boolean focusOnClick() {
        return false;
    }

    /**
     * True while this node, focused, wants every key, mouse button and scroll event whatever the pointer is over (a
     * control listening for a key to bind). The window manager sends them to it, and the editor gives nothing to
     * its tools meanwhile.
     */
    public boolean capturesAllInput() {
        return false;
    }

    protected void onFocusChanged(UiContext ctx, boolean focused) {
    }

    final void notifyFocusChanged(UiContext ctx, boolean focused) {
        onFocusChanged(ctx, focused);
    }
}
