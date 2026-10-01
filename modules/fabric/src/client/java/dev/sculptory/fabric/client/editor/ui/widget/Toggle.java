package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;

/**
 * An on/off switch with its label on the left and the switch on the right. A label longer than the room left of the
 * switch wraps onto more lines (the switch stays centred beside them) rather than being cut short.
 */
public class Toggle extends Node {
    private String label;
    private boolean value;
    private Consumer<Boolean> onChange;

    public Toggle(String label, boolean value, Consumer<Boolean> onChange) {
        this.label = Objects.requireNonNull(label);
        this.value = value;
        this.onChange = onChange;
    }

    public boolean value() {
        return value;
    }

    /** Sets the value without notifying the listener. */
    public Toggle setValue(boolean value) {
        this.value = value;
        return this;
    }

    public Toggle setLabel(String label) {
        this.label = Objects.requireNonNull(label);
        return this;
    }

    /** Flips the value and notifies the listener, if enabled. */
    public void toggle() {
        if (!isEffectivelyEnabled()) {
            return;
        }
        value = !value;
        if (onChange != null) {
            onChange.accept(value);
        }
    }

    private Rect trackRect(Theme theme) {
        int x = bounds.right() - theme.toggleTrackWidth;
        int y = bounds.y() + (bounds.height() - theme.toggleTrackHeight) / 2;
        return new Rect(x, y, theme.toggleTrackWidth, theme.toggleTrackHeight);
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        Theme theme = ctx.theme();
        int labelWidth = label.isEmpty() ? 0 : ctx.text().width(label) + theme.gap * 2;
        int width = labelWidth + theme.toggleTrackWidth;
        if (width <= maxWidth) {
            return new Size(width, theme.controlHeight);
        }
        int lines = labelLines(ctx, maxWidth).size();
        return new Size(maxWidth, Math.max(theme.controlHeight, linesHeight(ctx, lines) + 4));
    }

    /** The label as drawn in a toggle {@code width} wide: wrapped to the room left of the switch. */
    private List<String> labelLines(UiContext ctx, int width) {
        Theme theme = ctx.theme();
        int room = Math.max(1, width - theme.toggleTrackWidth - theme.gap * 2);
        return label.isEmpty() ? List.of() : TextLayout.wrap(ctx.text(), label, room);
    }

    private static int linesHeight(UiContext ctx, int lines) {
        return lines * ctx.text().lineHeight() + Math.max(0, lines - 1) * ctx.theme().lineSpacing;
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean hovered = enabled && ctx.isHovered(this);
        Rect track = trackRect(theme);
        int trackColor = value ? (hovered ? theme.accentHover : theme.accent)
                : (hovered ? theme.controlPressed : theme.toggleOff);
        if (!enabled) {
            trackColor = theme.controlDisabled;
        }
        g.fill(track, trackColor);
        int knobSize = track.height() - 2;
        int knobX = value ? track.right() - 1 - knobSize : track.x() + 1;
        g.fill(knobX, track.y() + 1, knobSize, knobSize, enabled ? theme.knob : theme.textDisabled);
        if (ctx.isFocused(this)) {
            g.outline(track.x() - 1, track.y() - 1, track.width() + 2, track.height() + 2, theme.focusRing);
        }
        List<String> lines = labelLines(ctx, bounds.width());
        if (lines.size() > 1 && linesHeight(ctx, lines.size()) > bounds.height()) {
            // Laid out lower than it asked for: one line, cut short.
            lines = List.of(TextLayout.ellipsize(ctx.text(), label, track.x() - bounds.x() - theme.gap));
        }
        int y = bounds.y() + (bounds.height() - linesHeight(ctx, lines.size()) + 1) / 2;
        for (String line : lines) {
            g.text(line, bounds.x(), y, textColor(ctx), theme.textShadow);
            y += ctx.text().lineHeight() + theme.lineSpacing;
        }
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        toggle();
        return true;
    }

    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER || keyCode == GLFW.GLFW_KEY_SPACE) {
            toggle();
            return true;
        }
        return false;
    }

    @Override
    public boolean isFocusable() {
        return true;
    }
}
