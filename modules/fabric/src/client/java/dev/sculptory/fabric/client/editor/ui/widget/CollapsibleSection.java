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

/** A titled header that shows or hides its content when clicked. Collapsed content takes no space. */
public class CollapsibleSection extends Node {
    private final Node content;
    private String title;
    private boolean expanded;
    private Consumer<Boolean> onToggle;

    public CollapsibleSection(String title, Node content, boolean expanded) {
        this.title = Objects.requireNonNull(title);
        this.content = adopt(content);
        this.expanded = expanded;
    }

    public Node content() {
        return content;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public CollapsibleSection setExpanded(boolean expanded) {
        this.expanded = expanded;
        return this;
    }

    public CollapsibleSection setTitle(String title) {
        this.title = Objects.requireNonNull(title);
        return this;
    }

    /** Called with the new expanded state when the user toggles the section. */
    public CollapsibleSection setOnToggle(Consumer<Boolean> onToggle) {
        this.onToggle = onToggle;
        return this;
    }

    public void toggle() {
        if (!isEffectivelyEnabled()) {
            return;
        }
        expanded = !expanded;
        if (onToggle != null) {
            onToggle.accept(expanded);
        }
    }

    public Rect headerRect(UiContext ctx) {
        return new Rect(bounds.x(), bounds.y(), bounds.width(), ctx.theme().controlHeight);
    }

    @Override
    public List<Node> children() {
        return expanded && content.isVisible() ? List.of(content) : List.of();
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        Theme theme = ctx.theme();
        int headerWidth = ctx.text().width(title) + theme.controlPaddingX + 12;
        if (!expanded || !content.isVisible()) {
            return new Size(headerWidth, theme.controlHeight);
        }
        int inner = maxWidth == Integer.MAX_VALUE ? maxWidth : Math.max(0, maxWidth - theme.sectionIndent);
        Size body = content.measure(ctx, inner);
        return new Size(Math.max(headerWidth, body.width() + theme.sectionIndent),
                theme.controlHeight + theme.gap + body.height());
    }

    @Override
    public void layout(UiContext ctx, Rect bounds) {
        super.layout(ctx, bounds);
        if (expanded && content.isVisible()) {
            Theme theme = ctx.theme();
            int top = theme.controlHeight + theme.gap;
            content.layout(ctx, new Rect(bounds.x() + theme.sectionIndent, bounds.y() + top,
                    bounds.width() - theme.sectionIndent, bounds.height() - top));
        }
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        Rect header = headerRect(ctx);
        boolean hovered = isEffectivelyEnabled() && ctx.isHovered(this)
                && header.contains(ctx.mouseX(), ctx.mouseY());
        g.fill(header, hovered ? theme.controlHover : theme.sectionHeader);
        if (ctx.isFocused(this)) {
            g.outline(header, theme.focusRing);
        }
        int centerY = header.y() + header.height() / 2;
        int arrowColor = isEffectivelyEnabled() ? theme.textDim : theme.textDisabled;
        if (expanded) {
            g.triangleDown(header.x() + 7, centerY - 1, 3, arrowColor);
        } else {
            g.triangleRight(header.x() + 5, centerY, 3, arrowColor);
        }
        int textX = header.x() + 14;
        String shown = TextLayout.ellipsize(ctx.text(), title, header.right() - textX - 2);
        int y = header.y() + (header.height() - ctx.text().lineHeight() + 1) / 2;
        g.text(shown, textX, y, isEffectivelyEnabled() ? theme.titleText : theme.textDisabled, theme.textShadow);
        if (expanded) {
            g.fill(bounds.x() + 2, header.bottom() + 1, 1, bounds.bottom() - header.bottom() - 1, theme.separator);
            renderChildren(g, ctx);
        }
    }

    @Override
    public boolean mouseDown(UiContext ctx, double x, double y, int button) {
        if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !headerRect(ctx).contains(x, y)) {
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
