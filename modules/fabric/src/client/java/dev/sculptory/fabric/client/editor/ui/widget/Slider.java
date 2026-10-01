package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import org.lwjgl.glfw.GLFW;

/**
 * A horizontal slider for an integer or decimal value, with its label and value drawn inside. The handle at the fill's
 * end never runs through them: where it crosses the text only its ends above and below the text show, so the label and
 * value read the same at any value. Drag or click to set; scroll while hovering, or use the arrow keys while focused,
 * to step (Shift for ten steps; Home/End for the limits). {@code onChange} fires on every change; {@code onRelease}
 * fires once when a drag or key/scroll step ends, for expensive updates.
 *
 * <p>An exact value is typed in place: double-click or Ctrl+click the slider, or press Enter while it is focused,
 * and a text field with the value covers it. Enter keeps the typed value (snapped and clamped to the range, as a
 * drag would), Esc cancels, and clicking away keeps it too. Text that isn't a number changes nothing.
 */
public class Slider extends Node {
    /** The handle's width at the fill's end. */
    private static final int HANDLE_WIDTH = 2;

    private final String label;
    private final double min;
    private final double max;
    private final double step;
    private final int decimals;
    private double value;
    private DoubleConsumer onChange;
    private DoubleConsumer onRelease;
    private boolean dragging;
    /** The inline value field while an exact value is being typed, or null. */
    private TextInput editor;
    /** Whether the edit started from the keyboard (focus comes back to the slider when it ends). */
    private boolean editFromKeyboard;
    private long lastPressMs = Long.MIN_VALUE / 2;
    /** The value before the last press, restored when that press turns out to start a double-click. */
    private double valueBeforePress;
    /** Units at the right end kept clear of the value and the typing field, for a button drawn over them. */
    private int trailingInset;

    public Slider(String label, double min, double max, double step, int decimals, double value,
            DoubleConsumer onChange) {
        if (!(max > min)) {
            throw new IllegalArgumentException("Slider needs max > min");
        }
        this.label = Objects.requireNonNull(label);
        this.min = min;
        this.max = max;
        this.step = Math.max(0, step);
        this.decimals = Math.max(0, Math.min(6, decimals));
        this.value = snap(value);
        this.onChange = onChange;
        setMinSize(40, 0);
    }

    public static Slider ofInt(String label, int min, int max, int value, IntConsumer onChange) {
        return new Slider(label, min, max, 1, 0, value,
                onChange == null ? null : v -> onChange.accept((int) Math.round(v)));
    }

    /** A decimal slider; the number of decimals shown follows {@code step} (0.05 shows two). */
    public static Slider ofDecimal(String label, double min, double max, double step, double value,
            DoubleConsumer onChange) {
        int decimals = Math.max(0, BigDecimal.valueOf(step).stripTrailingZeros().scale());
        return new Slider(label, min, max, step, decimals, value, onChange);
    }

    public Slider setOnRelease(DoubleConsumer onRelease) {
        this.onRelease = onRelease;
        return this;
    }

    /**
     * Keeps the value (and the field an exact value is typed into) this many units in from the right end, for a button
     * drawn over it there; the track still spans the whole slider, so dragging maps the same.
     */
    public Slider setTrailingInset(int units) {
        this.trailingInset = Math.max(0, units);
        return this;
    }

    public double value() {
        return value;
    }

    public int intValue() {
        return (int) Math.round(value);
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /** Sets the value (snapped and clamped) without notifying listeners. */
    public Slider setValue(double value) {
        this.value = snap(value);
        return this;
    }

    /** Clamps to the range, rounds to the nearest step from {@code min}, then to the shown decimals. */
    public double snap(double raw) {
        double v = Math.max(min, Math.min(max, raw));
        if (step > 0) {
            v = min + Math.round((v - min) / step) * step;
            v = Math.max(min, Math.min(max, v));
        }
        return BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).doubleValue();
    }

    public String formattedValue() {
        return format(value);
    }

    private String format(double shown) {
        return String.format(Locale.ROOT, "%." + decimals + "f", shown);
    }

    /** The value a click at {@code x} selects, from the last layout. */
    public double valueAt(double x) {
        int span = Math.max(1, bounds.width() - 2);
        double t = Math.max(0, Math.min(1, (x - bounds.x() - 1) / span));
        return snap(min + t * (max - min));
    }

    private boolean change(double newValue) {
        double snapped = snap(newValue);
        if (snapped == value) {
            return false;
        }
        value = snapped;
        if (onChange != null) {
            onChange.accept(value);
        }
        return true;
    }

    /** Changes the value as one complete edit: both listeners fire if it changed. */
    private void commit(double newValue) {
        if (change(newValue) && onRelease != null) {
            onRelease.accept(value);
        }
    }

    private void stepBy(int steps) {
        double unit = step > 0 ? step : (max - min) / 100;
        commit(value + steps * unit);
    }

    // ---- Typing a value ----

    /** Whether the inline value field is open. */
    public boolean isEditing() {
        return editor != null;
    }

    /** The inline value field while it is open. */
    public Optional<TextInput> editor() {
        return Optional.ofNullable(editor);
    }

    /**
     * Opens the inline value field over the slider with the current value selected, and puts the keyboard in it.
     * Does nothing while disabled or already editing.
     */
    public void startEditing(UiContext ctx) {
        if (editor != null || !isEffectivelyEnabled()) {
            return;
        }
        editFromKeyboard = ctx.isFocused(this);
        TextInput field = new TextInput(formattedValue(), null) {
            @Override
            protected void onFocusChanged(UiContext focusCtx, boolean focused) {
                super.onFocusChanged(focusCtx, focused);
                if (!focused && Slider.this.editor == this) {
                    // Clicked away (or the window closed): keep what was typed.
                    finishEditing(focusCtx, true);
                }
            }
        };
        field.setMaxLength(24);
        field.selectAll();
        field.setOnSubmit(text -> finishEditing(ctx, true));
        editor = adopt(field);
        if (bounds.width() > 0) {
            editor.layout(ctx, editorBounds());
        }
        ctx.setFocus(editor);
    }

    /**
     * Closes the inline value field: with {@code keep}, the typed value (snapped and clamped) becomes the slider's,
     * as one complete edit; text that isn't a number changes nothing.
     */
    public void finishEditing(UiContext ctx, boolean keep) {
        TextInput field = editor;
        if (field == null) {
            return;
        }
        editor = null;
        release(field);
        if (keep) {
            parse(field.text()).ifPresent(this::commit);
        }
        if (ctx.isFocused(field)) {
            // Enter or Esc: the keyboard goes back to the slider if it came from there.
            ctx.setFocus(editFromKeyboard ? this : null);
        }
    }

    /** A typed number ("12", "0.35", "-4", ",5" with a comma for the point), or empty when it isn't one. */
    static OptionalDouble parse(String text) {
        String cleaned = text.strip().replace(',', '.');
        if (cleaned.isEmpty()) {
            return OptionalDouble.empty();
        }
        try {
            double parsed = Double.parseDouble(cleaned);
            return Double.isFinite(parsed) ? OptionalDouble.of(parsed) : OptionalDouble.empty();
        } catch (NumberFormatException malformed) {
            return OptionalDouble.empty();
        }
    }

    @Override
    public List<Node> children() {
        return editor == null ? List.of() : List.of(editor);
    }

    @Override
    public boolean escapePressed(UiContext ctx) {
        if (editor == null) {
            return false;
        }
        finishEditing(ctx, false);
        return true;
    }

    // ---- Measure, layout, render ----

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        Theme theme = ctx.theme();
        String widest = format(Math.abs(min) > Math.abs(max) ? min : max);
        int width = ctx.text().width(label) + ctx.text().width(widest) + 3 * theme.controlPaddingX;
        return new Size(Math.max(80, width), theme.controlHeight);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        if (editor != null) {
            editor.layout(ctx, editorBounds());
        }
    }

    private Rect editorBounds() {
        return bounds.withSize(Math.max(1, bounds.width() - trailingInset), bounds.height());
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        if (editor != null) {
            editor.render(g, ctx);
            return;
        }
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean hot = enabled && (ctx.isHovered(this) || dragging);
        g.fill(bounds, enabled ? (hot ? theme.controlHover : theme.control) : theme.controlDisabled);
        double t = (value - min) / (max - min);
        int fill = (int) Math.round(t * (bounds.width() - 2));
        if (fill > 0) {
            g.fill(bounds.x() + 1, bounds.y() + 1, fill, bounds.height() - 2,
                    enabled ? theme.accentDim : theme.controlPressed);
        }

        int textY = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
        int padding = theme.controlPaddingX;
        String shownValue = formattedValue();
        int valueWidth = ctx.text().width(shownValue);
        int valueX = bounds.right() - trailingInset - padding - valueWidth;
        int labelRoom = bounds.width() - trailingInset - valueWidth - 3 * padding;
        String shownLabel = !label.isEmpty() && labelRoom > ctx.text().width(TextLayout.ELLIPSIS)
                ? TextLayout.ellipsize(ctx.text(), label, labelRoom) : "";
        int labelX = bounds.x() + padding;

        int handleX = Math.max(bounds.x() + 1, Math.min(bounds.right() - 3, bounds.x() + 1 + fill - 1));
        int handleColor = enabled ? (hot ? theme.accentHover : theme.accent) : theme.textDisabled;
        int top = bounds.y() + 1;
        int bottom = bounds.bottom() - 1;
        boolean overText = crosses(handleX, valueX, valueWidth)
                || !shownLabel.isEmpty() && crosses(handleX, labelX, ctx.text().width(shownLabel));
        if (overText) {
            // Where the handle crosses the label or the value it would read as a stroke of the text ("0|80"): only
            // its ends above and below the text show there, and the fill's edge marks the value between them.
            int textTop = textY - 1;
            int textBottom = textY + ctx.text().lineHeight();
            if (textTop > top) {
                g.fill(handleX, top, HANDLE_WIDTH, textTop - top, handleColor);
            }
            if (bottom > textBottom) {
                g.fill(handleX, textBottom, HANDLE_WIDTH, bottom - textBottom, handleColor);
            }
        } else {
            g.fill(handleX, top, HANDLE_WIDTH, bottom - top, handleColor);
        }
        g.outline(bounds, ctx.isFocused(this) ? theme.focusRing : theme.controlBorder);

        int foreground = enabled ? theme.text : theme.textDisabled;
        g.text(shownValue, valueX, textY, foreground, theme.textShadow);
        if (!shownLabel.isEmpty()) {
            g.text(shownLabel, labelX, textY, enabled ? theme.textDim : theme.textDisabled, theme.textShadow);
        }
    }

    /** Whether the handle at {@code handleX} touches text {@code width} wide at {@code textX} (a unit to spare). */
    private static boolean crosses(int handleX, int textX, int width) {
        return handleX + HANDLE_WIDTH > textX - 1 && handleX < textX + width + 1;
    }

    // ---- Input ----

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || editor != null) {
            return false;
        }
        if (ctx.controlDown()) {
            startEditing(ctx);
            return true;
        }
        long now = ctx.now();
        if (now - lastPressMs < ctx.theme().doubleClickMs) {
            // The first press of the double-click moved the value: put it back, then type.
            lastPressMs = Long.MIN_VALUE / 2;
            commit(valueBeforePress);
            startEditing(ctx);
            return true;
        }
        lastPressMs = now;
        valueBeforePress = value;
        dragging = true;
        change(valueAt(x));
        return true;
    }

    @Override
    public void mouseDrag(UiContext ctx, double x, double y, int button) {
        if (dragging) {
            change(valueAt(x));
        }
    }

    @Override
    public void mouseUp(UiContext ctx, double x, double y, int button) {
        if (dragging) {
            dragging = false;
            if (onRelease != null) {
                onRelease.accept(value);
            }
        }
    }

    @Override
    public boolean mouseScroll(UiContext ctx, double x, double y, double amount) {
        if (amount == 0 || editor != null) {
            return false;
        }
        int steps = (amount > 0 ? 1 : -1) * (ctx.shiftDown() ? 10 : 1);
        stepBy(steps);
        return true;
    }

    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        if (editor != null) {
            return false;
        }
        int multiplier = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0 ? 10 : 1;
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_DOWN -> stepBy(-multiplier);
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_UP -> stepBy(multiplier);
            case GLFW.GLFW_KEY_HOME -> commit(min);
            case GLFW.GLFW_KEY_END -> commit(max);
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> startEditing(ctx);
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
