package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.IntConsumer;
import org.lwjgl.glfw.GLFW;

/**
 * A menu popup: a column of {@link MenuItem}s, each with its label, its key on the right, a check mark and a submenu
 * arrow, separators between groups, disabled items dimmed with their reason as tooltip.
 *
 * <p>Pointer: moving over an item highlights it (and opens its submenu, closing any other); a click runs it (after
 * closing the whole menu) or opens its submenu. Clicking a disabled item or a separator does nothing. Keyboard (the
 * menu takes focus when it opens): Up/Down move, skipping separators and disabled items; Right opens the highlighted
 * submenu or, in a top-level menu, asks its {@link #setNavigator navigator} for the next menu; Left leaves a submenu
 * or asks for the previous menu; Enter and Space run the highlighted item. Esc closes the top popup (a submenu first),
 * like every popup.
 */
public final class Menu extends Node {
    private final List<MenuItem> items;
    private final List<ItemRow> rows = new ArrayList<>();
    private Menu parent;
    private IntConsumer navigator;
    private UiContext ctx;
    private PopupLayer.Popup popup;
    private Menu child;
    private int childIndex = -1;
    private int highlighted = -1;
    private int pressed = -1;

    public Menu(List<MenuItem> items) {
        this.items = List.copyOf(items);
        for (int i = 0; i < this.items.size(); i++) {
            rows.add(adopt(new ItemRow(i)));
        }
    }

    /**
     * What Left and Right do in a top-level menu: called with -1 or +1 to open the neighbouring menu. Without one
     * they do nothing there.
     */
    public Menu setNavigator(IntConsumer navigator) {
        this.navigator = navigator;
        return this;
    }

    /**
     * Opens this menu as a popup below {@code anchor} (above if there's no room) and gives it the keyboard.
     * {@code onClose} runs however it closes.
     */
    public PopupLayer.Popup open(UiContext ctx, Rect anchor, Runnable onClose) {
        this.ctx = Objects.requireNonNull(ctx);
        popup = ctx.popups().open(null, this, anchor, ctx.theme().menuMinWidth, () -> {
            closeChild();
            if (onClose != null) {
                onClose.run();
            }
        });
        ctx.setFocus(this);
        return popup;
    }

    public List<MenuItem> items() {
        return items;
    }

    /** The highlighted item's index, or -1. */
    public int highlighted() {
        return highlighted;
    }

    /** Highlights the first item that can run (for menus opened from the keyboard). */
    public void highlightFirst() {
        highlighted = step(-1, 1);
    }

    public boolean isOpen() {
        return popup != null && ctx.popups().popups().contains(popup);
    }

    /** The open submenu, or null. */
    public Menu child() {
        return child != null && child.isOpen() ? child : null;
    }

    /** The menu this one is a submenu of, or null. */
    public Menu parentMenu() {
        return parent;
    }

    /** Where item {@code index} was laid out. */
    public Rect rowBounds(int index) {
        return rows.get(index).bounds();
    }

    /** Closes this menu and its submenus. */
    public void close() {
        closeChild();
        if (ctx != null) {
            ctx.popups().close(popup);
            if (ctx.focused() == this) {
                ctx.clearFocus();
            }
        }
    }

    /** Closes the whole chain this menu belongs to (its top-level menu and every submenu). */
    public void closeAll() {
        Menu root = this;
        while (root.parent != null) {
            root = root.parent;
        }
        root.close();
    }

    /** Runs item {@code index} as a click would: opens its submenu, or closes the menus and runs it. */
    public void activate(int index) {
        if (index < 0 || index >= items.size() || !items.get(index).isSelectable()) {
            return;
        }
        MenuItem item = items.get(index);
        if (item.hasSubmenu()) {
            openChild(index, true);
            return;
        }
        closeAll();
        item.run();
    }

    // ---- Submenus ----

    private void openChild(int index, boolean focus) {
        if (child() != null && childIndex == index) {
            if (focus) {
                child.highlightFirst();
                ctx.setFocus(child);
            }
            return;
        }
        closeChild();
        Theme theme = ctx.theme();
        Menu sub = new Menu(items.get(index).submenuItems());
        sub.parent = this;
        Rect row = rows.get(index).bounds();
        child = sub;
        childIndex = index;
        highlighted = index;
        sub.open(ctx, new Rect(row.right(), row.y() - theme.menuPaddingY - 1, 0, 0), () -> {
            if (child == sub) {
                child = null;
                childIndex = -1;
            }
            if (ctx.focused() == sub && isOpen()) {
                ctx.setFocus(this);
            }
        });
        if (focus) {
            sub.highlightFirst();
        } else {
            ctx.setFocus(this);
        }
    }

    private void closeChild() {
        Menu open = child;
        child = null;
        childIndex = -1;
        if (open != null) {
            open.close();
        }
    }

    // ---- Pointer ----

    private void hover(int index) {
        MenuItem item = items.get(index);
        if (!item.isSelectable()) {
            return;
        }
        highlighted = index;
        if (item.hasSubmenu()) {
            openChild(index, false);
        } else if (child() != null) {
            closeChild();
        }
    }

    private int rowAt(double y) {
        for (int i = 0; i < rows.size(); i++) {
            Rect bounds = rows.get(i).bounds();
            if (y >= bounds.y() && y < bounds.bottom()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return true;
        }
        int index = rowAt(y);
        pressed = -1;
        if (index >= 0 && items.get(index).isSelectable()) {
            if (items.get(index).hasSubmenu()) {
                openChild(index, false);
            } else {
                pressed = index;
            }
        }
        return true;
    }

    @Override
    public void mouseUp(UiContext ctx, double x, double y, int button) {
        int index = pressed;
        pressed = -1;
        if (index >= 0 && index == rowAt(y) && bounds.contains(x, y)) {
            activate(index);
        }
    }

    // ---- Keyboard ----

    /** The next selectable index from {@code from} in {@code direction}, wrapping; -1 if there is none. */
    private int step(int from, int direction) {
        int count = items.size();
        for (int i = 1; i <= count; i++) {
            int index = Math.floorMod(from + direction * i, count);
            if (from < 0 && direction < 0) {
                index = count - i;
            }
            if (items.get(index).isSelectable()) {
                return index;
            }
        }
        return -1;
    }

    private void moveHighlight(int direction) {
        int next = step(highlighted, direction);
        if (next >= 0) {
            highlighted = next;
            if (child() != null && childIndex != next) {
                closeChild();
            }
        }
    }

    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        switch (keyCode) {
            case GLFW.GLFW_KEY_DOWN -> moveHighlight(1);
            case GLFW.GLFW_KEY_UP -> moveHighlight(-1);
            case GLFW.GLFW_KEY_HOME -> highlighted = step(-1, 1);
            case GLFW.GLFW_KEY_END -> highlighted = step(-1, -1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> activate(highlighted);
            case GLFW.GLFW_KEY_RIGHT -> {
                if (highlighted >= 0 && items.get(highlighted).hasSubmenu() && items.get(highlighted).isSelectable()) {
                    openChild(highlighted, true);
                } else if (parent == null && navigator != null) {
                    navigator.accept(1);
                }
            }
            case GLFW.GLFW_KEY_LEFT -> {
                if (parent != null) {
                    close();
                } else if (navigator != null) {
                    navigator.accept(-1);
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }

    // ---- Layout and drawing ----

    @Override
    public List<Node> children() {
        return List.copyOf(rows);
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        Theme theme = ctx.theme();
        TextMeasure text = ctx.text();
        int labels = 0;
        int keys = 0;
        int height = 2 * theme.menuPaddingY;
        for (MenuItem item : items) {
            height += item.isSeparator() ? theme.menuSeparatorHeight : theme.menuRowHeight;
            labels = Math.max(labels, text.width(item.label()));
            keys = Math.max(keys, text.width(item.keyText()));
        }
        int width = theme.menuCheckWidth + labels + (keys > 0 ? theme.menuKeyGap + keys : 0) + theme.menuArrowWidth;
        return new Size(width, height);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        Theme theme = ctx.theme();
        int y = bounds.y() + theme.menuPaddingY;
        for (int i = 0; i < rows.size(); i++) {
            int height = items.get(i).isSeparator() ? theme.menuSeparatorHeight : theme.menuRowHeight;
            rows.get(i).layout(ctx, new Rect(bounds.x(), y, bounds.width(), height));
            y += height;
        }
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        for (ItemRow row : rows) {
            row.render(g, ctx);
        }
    }

    /** One item's slot: gives the item its own hover (and so its own tooltip); the menu handles clicks. */
    private final class ItemRow extends Node {
        private final int index;

        ItemRow(int index) {
            this.index = index;
        }

        @Override
        public String tooltip() {
            return items.get(index).tooltipText();
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            return Size.ZERO;
        }

        @Override
        public void mouseMove(UiContext ctx, double x, double y) {
            hover(index);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            Theme theme = ctx.theme();
            TextMeasure text = ctx.text();
            MenuItem item = items.get(index);
            if (item.isSeparator()) {
                g.fill(bounds.x() + 4, bounds.y() + bounds.height() / 2, bounds.width() - 8, 1, theme.separator);
                return;
            }
            if (index == highlighted || index == childIndex) {
                g.fill(bounds, theme.menuHighlight);
            }
            int textY = bounds.y() + (bounds.height() - text.lineHeight() + 1) / 2;
            int color = item.isEnabled() ? theme.text : theme.textDisabled;
            int dim = item.isEnabled() ? theme.textDim : theme.textDisabled;
            if (item.isChecked()) {
                String check = "✓";
                g.text(check, bounds.x() + (theme.menuCheckWidth - text.width(check)) / 2, textY, color, theme.textShadow);
            }
            int keyRight = bounds.right() - theme.menuArrowWidth;
            int keyWidth = text.width(item.keyText());
            if (keyWidth > 0) {
                g.text(item.keyText(), keyRight - keyWidth, textY, dim, theme.textShadow);
            }
            int labelX = bounds.x() + theme.menuCheckWidth;
            int labelRoom = keyRight - labelX - (keyWidth > 0 ? keyWidth + theme.menuKeyGap / 2 : 0);
            g.text(TextLayout.ellipsize(text, item.label(), labelRoom), labelX, textY, color, theme.textShadow);
            if (item.hasSubmenu()) {
                g.triangleRight(bounds.right() - theme.menuArrowWidth + 3, bounds.y() + bounds.height() / 2, 3, dim);
            }
        }
    }
}
