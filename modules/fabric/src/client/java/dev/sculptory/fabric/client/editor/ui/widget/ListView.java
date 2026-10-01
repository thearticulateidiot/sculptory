package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.ScrollModel;
import dev.sculptory.fabric.client.editor.ui.Scrollbar;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntConsumer;
import org.lwjgl.glfw.GLFW;

/**
 * A scrolling list of fixed-height rows. Virtualized: row nodes exist only for the rows in view,
 * so a list of 100,000 entries costs the same per frame as a list of ten. Rows scrolled out of
 * view are discarded and rebuilt by the {@link RowFactory} when they come back.
 *
 * <p>Click selects; double-click or Enter activates (or a single click, with
 * {@link #setActivateOnClick}). Arrow keys, Page Up/Down and Home/End move the selection while
 * the list is focused.
 */
public class ListView<T> extends Node {
    /** Builds the node for one row. Called only for rows scrolled into view. */
    @FunctionalInterface
    public interface RowFactory<T> {
        Node create(T item, int index);
    }

    private final RowFactory<T> factory;
    private final ScrollModel scroll = new ScrollModel();
    private final Scrollbar scrollbar = new Scrollbar(scroll);
    private final TreeMap<Integer, Node> rows = new TreeMap<>();
    private List<Node> rowView = List.of();
    private List<T> items;
    private int rowHeight = -1;
    private int preferredRows = 8;
    private int selected = -1;
    private IntConsumer onSelect;
    private IntConsumer onActivate;
    private boolean activateOnClick;
    private String emptyText = "";
    private int first;
    private int last = -1;
    private int lastClickIndex = -1;
    private long lastClickMs;
    private int pendingScrollIndex = -1;
    private int laidOutRowHeight;

    public ListView(List<T> items, RowFactory<T> factory) {
        this.items = List.copyOf(items);
        this.factory = Objects.requireNonNull(factory);
    }

    /** A list whose rows are plain labels. */
    public static <T> ListView<T> ofLabels(List<T> items, Function<T, String> text) {
        return new ListView<>(items, (item, index) -> new Label(text.apply(item)));
    }

    // ---- Configuration ----

    /** Replaces the items. Built rows are discarded; the selection is kept if still in range. */
    public ListView<T> setItems(List<T> items) {
        this.items = List.copyOf(items);
        discardRows();
        if (selected >= this.items.size()) {
            selected = -1;
        }
        return this;
    }

    public List<T> items() {
        return items;
    }

    /** Row height in pixels; negative uses the theme's row height. */
    public ListView<T> setRowHeight(int rowHeight) {
        this.rowHeight = rowHeight;
        discardRows();
        return this;
    }

    /** How many rows tall the list asks to be. */
    public ListView<T> setPreferredRows(int rows) {
        this.preferredRows = Math.max(1, rows);
        return this;
    }

    /** Called when the selection changes by click or key. */
    public ListView<T> setOnSelect(IntConsumer onSelect) {
        this.onSelect = onSelect;
        return this;
    }

    /** Called on double-click or Enter (or single click, see {@link #setActivateOnClick}). */
    public ListView<T> setOnActivate(IntConsumer onActivate) {
        this.onActivate = onActivate;
        return this;
    }

    public ListView<T> setActivateOnClick(boolean activateOnClick) {
        this.activateOnClick = activateOnClick;
        return this;
    }

    /** Text shown when there are no items. */
    public ListView<T> setEmptyText(String emptyText) {
        this.emptyText = Objects.requireNonNull(emptyText);
        return this;
    }

    // ---- State ----

    public int selectedIndex() {
        return selected;
    }

    public Optional<T> selectedItem() {
        return selected >= 0 && selected < items.size() ? Optional.of(items.get(selected)) : Optional.empty();
    }

    /** Selects without notifying listeners; -1 clears. */
    public void setSelectedIndex(int index) {
        selected = index >= 0 && index < items.size() ? index : -1;
    }

    public ScrollModel scroll() {
        return scroll;
    }

    public int rowHeight(UiContext ctx) {
        return rowHeight > 0 ? rowHeight : ctx.theme().rowHeight;
    }

    /**
     * Scrolls the least amount that shows row {@code index}. Before the first layout the request is
     * kept and applied once the viewport size is known.
     */
    public void scrollToIndex(int index) {
        if (laidOutRowHeight > 0) {
            scroll.setExtent(items.size() * laidOutRowHeight, bounds.height());
            scroll.ensureVisible(index * laidOutRowHeight, (index + 1) * laidOutRowHeight);
        } else {
            pendingScrollIndex = index;
        }
    }

    /** First row in view after the last layout. */
    public int firstVisibleIndex() {
        return first;
    }

    /** Last row in view after the last layout, or -1 when none. */
    public int lastVisibleIndex() {
        return last;
    }

    /** Row nodes currently built, which is only the rows in view. */
    public int builtRowCount() {
        return rows.size();
    }

    /** The row index at screen y, or -1. */
    public int rowIndexAt(UiContext ctx, double y) {
        if (y < bounds.y() || y >= bounds.bottom()) {
            return -1;
        }
        int index = (int) Math.floor((y - bounds.y() + scroll.offset()) / rowHeight(ctx));
        return index >= 0 && index < items.size() ? index : -1;
    }

    // ---- Layout ----

    private void discardRows() {
        for (Node row : rows.values()) {
            release(row);
        }
        rows.clear();
        rowView = List.of();
    }

    private Rect trackRect(UiContext ctx) {
        return Scrollbar.track(bounds, ctx.theme());
    }

    private Rect rowRect(UiContext ctx, int index) {
        Theme theme = ctx.theme();
        int height = rowHeight(ctx);
        int width = bounds.width() - (scroll.isScrollable() ? theme.scrollbarWidth : 0);
        return new Rect(bounds.x(), bounds.y() + index * height - scroll.offset(), width, height);
    }

    @Override
    public List<Node> children() {
        return rowView;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        return new Size(80, rowHeight(ctx) * preferredRows);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        int height = rowHeight(ctx);
        laidOutRowHeight = bounds.height() > 0 ? height : 0;
        scroll.setExtent(items.size() * height, bounds.height());
        if (pendingScrollIndex >= 0) {
            scroll.ensureVisible(pendingScrollIndex * height, (pendingScrollIndex + 1) * height);
            pendingScrollIndex = -1;
        }
        if (items.isEmpty() || bounds.height() == 0) {
            first = 0;
            last = -1;
        } else {
            first = scroll.offset() / height;
            last = Math.min(items.size() - 1, (scroll.offset() + bounds.height() - 1) / height);
        }
        var iterator = rows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, Node> entry = iterator.next();
            if (entry.getKey() < first || entry.getKey() > last) {
                release(entry.getValue());
                iterator.remove();
            }
        }
        int inset = ctx.theme().rowInset;
        for (int index = first; index <= last; index++) {
            final int rowIndex = index;
            Node row = rows.computeIfAbsent(index, key -> adopt(factory.create(items.get(rowIndex), rowIndex)));
            Rect rect = rowRect(ctx, index);
            row.layout(ctx, new Rect(rect.x() + inset, rect.y(), rect.width() - 2 * inset, rect.height()));
        }
        rowView = Collections.unmodifiableList(new ArrayList<>(rows.values()));
    }

    @Override
    public Node hitTest(double x, double y) {
        if (!isVisible() || !bounds.contains(x, y)) {
            return null;
        }
        for (int i = rowView.size() - 1; i >= 0; i--) {
            Node hit = rowView.get(i).hitTest(x, y);
            if (hit != null) {
                return hit;
            }
        }
        return this;
    }

    // ---- Rendering ----

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        g.fill(bounds, theme.listBackground);
        g.pushClip(bounds);
        Rect track = trackRect(ctx);
        boolean overBar = scroll.isScrollable() && track.contains(ctx.mouseX(), ctx.mouseY());
        int hoverIndex = ctx.isHoveredWithin(this) && !overBar ? rowIndexAt(ctx, ctx.mouseY()) : -1;
        for (Map.Entry<Integer, Node> entry : rows.entrySet()) {
            int index = entry.getKey();
            Rect rect = rowRect(ctx, index);
            if (index == selected) {
                g.fill(rect, theme.selection);
            } else if (index == hoverIndex && isEffectivelyEnabled()) {
                g.fill(rect, theme.rowHover);
            }
            entry.getValue().render(g, ctx);
        }
        if (items.isEmpty() && !emptyText.isEmpty()) {
            int width = ctx.text().width(emptyText);
            g.text(emptyText, bounds.x() + (bounds.width() - width) / 2, bounds.y() + theme.padding,
                    theme.textDim, theme.textShadow);
        }
        scrollbar.render(g, ctx, track);
        g.popClip();
        if (ctx.isFocused(this)) {
            g.outline(bounds, theme.focusRing);
        }
    }

    // ---- Input ----

    private void select(int index, boolean notify) {
        if (index < 0 || index >= items.size()) {
            return;
        }
        boolean changed = index != selected;
        selected = index;
        if (changed && notify && onSelect != null) {
            onSelect.accept(index);
        }
    }

    private void activate(int index) {
        if (onActivate != null && index >= 0 && index < items.size()) {
            onActivate.accept(index);
        }
    }

    private void moveSelection(UiContext ctx, int target) {
        if (items.isEmpty()) {
            return;
        }
        int index = Math.max(0, Math.min(items.size() - 1, target));
        select(index, true);
        scrollToIndex(index);
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (scrollbar.mouseDown(trackRect(ctx), ctx.theme(), x, y)) {
            return true;
        }
        int index = rowIndexAt(ctx, y);
        if (index < 0) {
            return true;
        }
        boolean doubleClick = index == lastClickIndex && ctx.now() - lastClickMs < ctx.theme().doubleClickMs;
        lastClickIndex = doubleClick ? -1 : index;
        lastClickMs = ctx.now();
        select(index, true);
        if (activateOnClick || doubleClick) {
            activate(index);
        }
        return true;
    }

    @Override
    public void mouseDrag(UiContext ctx, double x, double y, int button) {
        scrollbar.mouseDrag(trackRect(ctx), ctx.theme(), y);
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
        scroll.scrollBy((int) Math.round(-amount * rowHeight(ctx) * 3));
        return true;
    }

    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        int page = Math.max(1, bounds.height() / rowHeight(ctx) - 1);
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> moveSelection(ctx, selected < 0 ? 0 : selected - 1);
            case GLFW.GLFW_KEY_DOWN -> moveSelection(ctx, selected + 1);
            case GLFW.GLFW_KEY_PAGE_UP -> moveSelection(ctx, selected - page);
            case GLFW.GLFW_KEY_PAGE_DOWN -> moveSelection(ctx, selected + page);
            case GLFW.GLFW_KEY_HOME -> moveSelection(ctx, 0);
            case GLFW.GLFW_KEY_END -> moveSelection(ctx, items.size() - 1);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> activate(selected);
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

    @Override
    public boolean focusOnClick() {
        return true;
    }
}
