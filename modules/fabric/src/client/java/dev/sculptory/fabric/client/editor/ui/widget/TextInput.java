package dev.sculptory.fabric.client.editor.ui.widget;

import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/**
 * Single-line text entry. Wraps a vanilla {@link TextFieldWidget} as a leaf so caret movement,
 * selection and clipboard shortcuts behave exactly like the rest of Minecraft. This node draws the
 * box; the vanilla widget draws only the text and caret. The placeholder shows while the field is empty, also while it
 * has the keyboard (as the vanilla widget's grey suggestion after the caret), so a search box that opens focused
 * still says what it is for.
 *
 * <p>The vanilla widget is created on first render, so layout and focus work without a running client (and without
 * one, as in unit tests, the text is drawn plainly). While focused, the input consumes every key except Esc, Tab and
 * F1-F25, so typing never triggers editor shortcuts.
 */
public class TextInput extends Node {
    private TextFieldWidget widget;
    private String text;
    private String placeholder = "";
    private int maxLength = 256;
    private Consumer<String> onChange;
    private Consumer<String> onSubmit;
    private boolean selectAllPending;

    public TextInput(String text, Consumer<String> onChange) {
        this.text = Objects.requireNonNull(text);
        this.onChange = onChange;
        setMinSize(30, 0);
    }

    public String text() {
        return widget != null ? widget.getText() : text;
    }

    /** Replaces the text without notifying {@code onChange}. */
    public TextInput setText(String text) {
        this.text = Objects.requireNonNull(text);
        if (widget != null) {
            Consumer<String> listener = onChange;
            onChange = null;
            widget.setText(text);
            onChange = listener;
            showPlaceholder();
        }
        return this;
    }

    /** Grey hint shown while the field is empty, whether it has the keyboard or not. */
    public TextInput setPlaceholder(String placeholder) {
        this.placeholder = Objects.requireNonNull(placeholder);
        showPlaceholder();
        return this;
    }

    public String placeholder() {
        return placeholder;
    }

    /**
     * The vanilla widget draws its own placeholder only while unfocused; its suggestion (grey text after the caret, at
     * the start of an empty field) shows either way, so the placeholder goes there instead.
     */
    private void showPlaceholder() {
        if (widget != null) {
            widget.setSuggestion(widget.getText().isEmpty() && !placeholder.isEmpty() ? placeholder : null);
        }
    }

    public TextInput setMaxLength(int maxLength) {
        this.maxLength = Math.max(1, maxLength);
        if (widget != null) {
            widget.setMaxLength(this.maxLength);
        }
        return this;
    }

    /** Selects the whole text, so typing replaces it. */
    public TextInput selectAll() {
        if (widget != null) {
            widget.setCursorToEnd(false);
            widget.setSelectionEnd(0);
        } else {
            selectAllPending = true;
        }
        return this;
    }

    /** Called with the text when Enter is pressed. */
    public TextInput setOnSubmit(Consumer<String> onSubmit) {
        this.onSubmit = onSubmit;
        return this;
    }

    /** The wrapped vanilla widget, created on first use. Requires a running client. */
    private TextFieldWidget widget(UiContext ctx) {
        if (widget == null) {
            attach(MinecraftClient.getInstance().textRenderer, ctx);
        }
        return widget;
    }

    /**
     * Creates the vanilla widget with {@code textRenderer} (the client's; a test's own without a client). A
     * {@link #selectAll} asked for before is applied last: moving the caret, as the scroll below does, clears a
     * selection, and a slider's typed value must replace what the field holds ("20", not "10020").
     */
    void attach(TextRenderer textRenderer, UiContext ctx) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, 0, 0, 10, 10, Text.literal(placeholder));
        field.setDrawsBackground(false);
        field.setMaxLength(maxLength);
        field.setText(text);
        field.setEditableColor(ctx.theme().text);
        field.setChangedListener(value -> {
            text = value;
            showPlaceholder();
            if (onChange != null) {
                onChange.accept(value);
            }
        });
        field.setFocused(ctx.isFocused(this));
        widget = field;
        showPlaceholder();
        position(ctx);
        // The text was set while the field was 10 px wide, which scrolled it to its last character; scroll again
        // at the real width so text given before the first frame shows from its start.
        field.setCursorToStart(false);
        field.setCursorToEnd(false);
        if (selectAllPending) {
            selectAllPending = false;
            field.setCursorToEnd(false);
            field.setSelectionEnd(0);
        }
    }

    /** Places the vanilla field inside this node's box, vertically centred. */
    private void position(UiContext ctx) {
        if (widget == null) {
            return;
        }
        Theme theme = ctx.theme();
        int inset = theme.controlPaddingX / 2 + 1;
        int lineHeight = ctx.text().lineHeight();
        widget.setX(bounds.x() + inset);
        widget.setY(bounds.y() + (bounds.height() - lineHeight + 1) / 2);
        widget.setWidth(Math.max(1, bounds.width() - 2 * inset));
        widget.setHeight(lineHeight);
    }

    private Rect fieldRect() {
        return new Rect(widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight());
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        return new Size(100, ctx.theme().controlHeight);
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        position(ctx);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean focused = ctx.isFocused(this);
        g.fill(bounds, enabled ? theme.inputBackground : theme.controlDisabled);
        int border = focused ? theme.focusRing : ctx.isHovered(this) && enabled ? theme.textDim : theme.controlBorder;
        g.outline(bounds, border);
        if (MinecraftClient.getInstance() == null) {
            renderPlain(g, ctx, enabled);
            return;
        }
        TextFieldWidget field = widget(ctx);
        field.setEditable(enabled);
        g.pushClip(bounds.inset(1));
        g.widget(field, (int) ctx.mouseX(), (int) ctx.mouseY(), 0);
        g.popClip();
    }

    /**
     * Without a running client (unit tests drawing into a recording {@link UiGraphics}) there is no vanilla field: the
     * text, or the placeholder dimmed, is drawn plainly, clipped to the box.
     */
    private void renderPlain(UiGraphics g, UiContext ctx, boolean enabled) {
        Theme theme = ctx.theme();
        boolean empty = text.isEmpty();
        int inset = theme.controlPaddingX / 2 + 1;
        int y = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
        g.pushClip(bounds.inset(1));
        g.text(empty ? placeholder : text, bounds.x() + inset, y,
                empty || !enabled ? theme.textDim : theme.text, theme.textShadow);
        g.popClip();
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (widget != null) {
            Rect field = fieldRect();
            double clampedX = Math.max(field.x(), Math.min(x, field.right() - 1));
            double clampedY = Math.max(field.y(), Math.min(y, field.bottom() - 1));
            widget.setFocused(true);
            widget.mouseClicked(clampedX, clampedY, button);
        }
        return true;
    }

    @Override
    public void mouseDrag(UiContext ctx, double x, double y, int button) {
        if (widget != null) {
            widget.mouseDragged(x, y, button, 0, 0);
        }
    }

    @Override
    public void mouseUp(UiContext ctx, double x, double y, int button) {
        if (widget != null) {
            widget.mouseReleased(x, y, button);
        }
    }

    @Override
    public boolean keyPressed(UiContext ctx, int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_TAB
                || (keyCode >= GLFW.GLFW_KEY_F1 && keyCode <= GLFW.GLFW_KEY_F25)) {
            return false;
        }
        if ((keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) && onSubmit != null) {
            onSubmit.accept(text());
            return true;
        }
        if (widget != null) {
            widget.keyPressed(keyCode, scanCode, modifiers);
        }
        return true;
    }

    @Override
    public boolean charTyped(UiContext ctx, char chr, int modifiers) {
        if (widget != null) {
            widget.charTyped(chr, modifiers);
        }
        return true;
    }

    @Override
    protected void onFocusChanged(UiContext ctx, boolean focused) {
        if (widget != null) {
            widget.setFocused(focused);
        }
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
