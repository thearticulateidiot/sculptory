package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.lwjgl.glfw.GLFW;

/**
 * Picks one option from a list shown in a popup. Click opens the list; click an option (or use
 * the arrows and Enter) to choose it. While the dropdown is focused, Up/Down change the value
 * without opening the list. A dim suffix may follow the selected label ({@link #setSuffix}).
 */
public class Dropdown<T> extends Node {
    private static final int CARET_ROOM = 12;

    private final List<T> options;
    private final Function<T, String> labeler;
    private final Consumer<T> onChange;
    private T selected;
    private PopupLayer.Popup popup;
    private int widestOption = -1;
    private String suffix = "";
    private boolean reselectNotifies;
    private boolean listOnly;
    private Function<T, String> optionTooltips = option -> null;

    public Dropdown(List<T> options, T selected, Function<T, String> labeler, Consumer<T> onChange) {
        if (options.isEmpty()) {
            throw new IllegalArgumentException("Dropdown needs at least one option");
        }
        this.options = List.copyOf(options);
        this.labeler = Objects.requireNonNull(labeler);
        this.onChange = onChange;
        this.selected = this.options.contains(selected) ? selected : this.options.get(0);
    }

    public T selected() {
        return selected;
    }

    /** Sets the value without notifying the listener. */
    public Dropdown<T> setSelected(T value) {
        if (options.contains(value)) {
            selected = value;
        }
        return this;
    }

    public List<T> options() {
        return options;
    }

    public boolean isOpen() {
        return popup != null;
    }

    /** Dim text drawn after the selected label in the closed control (not in the list), e.g. "(modified)". */
    public Dropdown<T> setSuffix(String suffix) {
        this.suffix = Objects.requireNonNull(suffix);
        return this;
    }

    public String suffix() {
        return suffix;
    }

    /**
     * Whether picking the selected option again from the list notifies the listener (a preset list reloads the
     * preset). The arrow keys never do.
     */
    public Dropdown<T> setReselectNotifies(boolean notifies) {
        this.reselectNotifies = notifies;
        return this;
    }

    /**
     * Whether the value changes only through the list: Up/Down then open it instead of choosing (for choices that
     * replace a lot at once, like a preset).
     */
    public Dropdown<T> setListOnly(boolean listOnly) {
        this.listOnly = listOnly;
        return this;
    }

    /** Each option's own tooltip, shown over its row in the open list (null for none). */
    public Dropdown<T> setOptionTooltips(Function<T, String> optionTooltips) {
        this.optionTooltips = Objects.requireNonNull(optionTooltips);
        return this;
    }

    /** Chooses {@code value} as a click in the list would, notifying the listener. */
    public void pick(T value) {
        if (options.contains(value)) {
            choose(value, reselectNotifies);
        }
    }

    private void choose(T value) {
        choose(value, false);
    }

    private void choose(T value, boolean notifyUnchanged) {
        boolean changed = !value.equals(selected);
        selected = value;
        if ((changed || notifyUnchanged) && onChange != null) {
            onChange.accept(value);
        }
    }

    public void open(UiContext ctx) {
        if (popup != null || !isEffectivelyEnabled()) {
            return;
        }
        Theme theme = ctx.theme();
        ListView<T> list = new ListView<>(options, (option, index) -> {
            Label row = new Label(labeler.apply(option));
            row.setTooltip(optionTooltips.apply(option));
            return row;
        });
        list.setPreferredRows(Math.min(options.size(), theme.popupMaxRows));
        list.setSelectedIndex(options.indexOf(selected));
        list.setActivateOnClick(true);
        int width = Math.max(bounds.width(), widestOption(ctx) + 2 * theme.rowInset + theme.scrollbarWidth + 4);
        boolean hadFocus = ctx.isFocused(this);
        PopupLayer.Popup opened = ctx.popups().open(this, list, bounds, width, () -> {
            popup = null;
            if (hadFocus) {
                ctx.setFocus(this);
            }
        });
        list.setOnActivate(index -> {
            pick(options.get(index));
            ctx.popups().close(opened);
        });
        popup = opened;
        list.scrollToIndex(Math.max(0, list.selectedIndex()));
        ctx.setFocus(list);
    }

    public void close(UiContext ctx) {
        ctx.popups().close(popup);
    }

    /** Width of the longest option label; measured once, since the options never change. */
    private int widestOption(UiContext ctx) {
        if (widestOption < 0) {
            int widest = 0;
            for (T option : options) {
                widest = Math.max(widest, ctx.text().width(labeler.apply(option)));
            }
            widestOption = widest;
        }
        return widestOption;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        Theme theme = ctx.theme();
        return new Size(widestOption(ctx) + 2 * theme.controlPaddingX + CARET_ROOM, theme.controlHeight);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean hovered = enabled && ctx.isHovered(this);
        g.fill(bounds, !enabled ? theme.controlDisabled : hovered || isOpen() ? theme.controlHover : theme.control);
        g.outline(bounds, isOpen() || ctx.isFocused(this) ? theme.focusRing : theme.controlBorder);
        int room = bounds.width() - 2 * theme.controlPaddingX - CARET_ROOM;
        String tail = suffix.isEmpty() ? "" : " " + suffix;
        int tailWidth = Math.min(ctx.text().width(tail), room / 2);
        String shown = TextLayout.ellipsize(ctx.text(), labeler.apply(selected), room - tailWidth);
        int x = bounds.x() + theme.controlPaddingX;
        int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
        g.text(shown, x, y, textColor(ctx), theme.textShadow);
        if (!tail.isEmpty()) {
            int after = x + ctx.text().width(shown);
            g.text(TextLayout.ellipsize(ctx.text(), tail, room - (after - x)), after, y,
                    enabled ? theme.textDim : theme.textDisabled, theme.textShadow);
        }
        int caretX = bounds.right() - theme.controlPaddingX - 3;
        g.triangleDown(caretX, bounds.y() + bounds.height() / 2 - 1, 3, enabled ? theme.textDim : theme.textDisabled);
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (isOpen()) {
            close(ctx);
        } else {
            open(ctx);
        }
        return true;
    }

    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        int index = options.indexOf(selected);
        if (listOnly && (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN)) {
            open(ctx);
            return true;
        }
        switch (keyCode) {
            case GLFW.GLFW_KEY_UP -> choose(options.get(Math.max(0, index - 1)));
            case GLFW.GLFW_KEY_DOWN -> choose(options.get(Math.min(options.size() - 1, index + 1)));
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER, GLFW.GLFW_KEY_SPACE -> open(ctx);
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
}
