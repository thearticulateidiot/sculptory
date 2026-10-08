package dev.sculptory.fabric.client.editor.heightmap;

import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.widget.AbstractButton;
import java.util.Objects;
import java.util.function.Function;

/**
 * A button showing a height image's thumbnail and name with a drop-down caret; clicking it opens the picture picker. A
 * picture without a thumbnail (missing, or no game) shows an empty frame.
 */
public final class HeightmapChip extends AbstractButton {
    public static final int HEIGHT = 20;
    private static final int CARET_ROOM = 12;
    private static final int THUMB = 16;

    private final HeightmapView view;
    private final Function<HeightmapRef, String> names;
    private HeightmapRef ref;

    /** @param names the name shown for a picture */
    public HeightmapChip(HeightmapView view, Function<HeightmapRef, String> names, HeightmapRef ref, Runnable onClick) {
        super(onClick);
        this.view = Objects.requireNonNull(view);
        this.names = Objects.requireNonNull(names);
        this.ref = Objects.requireNonNull(ref);
    }

    public HeightmapRef ref() {
        return ref;
    }

    public HeightmapChip setRef(HeightmapRef ref) {
        this.ref = Objects.requireNonNull(ref);
        return this;
    }

    /** The name the chip shows. */
    public String label() {
        return names.apply(ref);
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int text = Math.min(110, ctx.text().width(label()));
        return new Size(THUMB + 6 + text + CARET_ROOM, HEIGHT);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean hovered = enabled && ctx.isHovered(this);
        g.fill(bounds, !enabled ? theme.controlDisabled : isPressed() && hovered ? theme.controlPressed
                : hovered ? theme.controlHover : theme.control);
        g.outline(bounds, ctx.isFocused(this) ? theme.focusRing : theme.controlBorder);
        int thumbX = bounds.x() + 2, thumbY = bounds.y() + (bounds.height() - THUMB) / 2;
        g.fill(thumbX, thumbY, THUMB, THUMB, 0xFF000000);
        view.thumbnail(ref).ifPresent(t -> g.texture(t.texture(), thumbX, thumbY, THUMB, THUMB, t.width(), t.height()));
        g.outline(thumbX - 1, thumbY - 1, THUMB + 2, THUMB + 2, theme.controlBorder);
        int textX = thumbX + THUMB + 4;
        String name = TextLayout.ellipsize(ctx.text(), label(), bounds.right() - CARET_ROOM - textX);
        int textY = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
        g.text(name, textX, textY, enabled ? theme.text : theme.textDisabled, theme.textShadow);
        g.triangleDown(bounds.right() - 7, bounds.y() + bounds.height() / 2 - 1, 3,
                enabled ? theme.textDim : theme.textDisabled);
    }

    @Override
    public String tooltip() {
        String explicit = super.tooltip();
        return explicit != null ? explicit : ref.text();
    }
}
