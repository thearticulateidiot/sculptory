package dev.sculptory.mc.client.editor.biomes;

import dev.sculptory.mc.client.editor.ui.Size;
import dev.sculptory.mc.client.editor.ui.TextLayout;
import dev.sculptory.mc.client.editor.ui.Theme;
import dev.sculptory.mc.client.editor.ui.UiContext;
import dev.sculptory.mc.client.editor.ui.UiGraphics;
import dev.sculptory.mc.client.editor.ui.widget.AbstractButton;
import java.util.Objects;

/** A button showing a biome's colour swatch and name with a drop-down caret; clicking it opens the biome picker. */
public class BiomeChip extends AbstractButton {
    public static final int HEIGHT = 18;
    private static final int SWATCH = 12;
    private static final int CARET_ROOM = 12;

    private final BiomeCatalog catalog;
    private String biome;
    private int maxTextWidth = 110;

    public BiomeChip(BiomeCatalog catalog, String biome, Runnable onClick) {
        super(onClick);
        this.catalog = Objects.requireNonNull(catalog);
        this.biome = Objects.requireNonNull(biome);
    }

    public String biome() {
        return biome;
    }

    public BiomeChip setBiome(String biome) {
        this.biome = Objects.requireNonNull(biome);
        return this;
    }

    @Override
    protected Size measureContent(UiContext ctx, int maxWidth) {
        int text = Math.min(maxTextWidth, ctx.text().width(catalog.name(biome)));
        return new Size(SWATCH + 8 + text + CARET_ROOM, HEIGHT);
    }

    @Override
    public void render(UiGraphics g, UiContext ctx) {
        Theme theme = ctx.theme();
        boolean enabled = isEffectivelyEnabled();
        boolean hovered = enabled && ctx.isHovered(this);
        g.fill(bounds, !enabled ? theme.controlDisabled : isPressed() && hovered ? theme.controlPressed
                : hovered ? theme.controlHover : theme.control);
        g.outline(bounds, ctx.isFocused(this) ? theme.focusRing : theme.controlBorder);
        swatch(g, catalog.color(biome), bounds.x() + 3, bounds.y() + (bounds.height() - SWATCH) / 2, SWATCH);
        int textX = bounds.x() + SWATCH + 6;
        String name = TextLayout.ellipsize(ctx.text(), catalog.name(biome), bounds.right() - CARET_ROOM - textX);
        int textY = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
        g.text(name, textX, textY, enabled ? theme.text : theme.textDisabled, theme.textShadow);
        g.triangleDown(bounds.right() - 7, bounds.y() + bounds.height() / 2 - 1, 3,
                enabled ? theme.textDim : theme.textDisabled);
    }

    /** A square of {@code color} with a dark rim, so a pale biome still shows against the panel. */
    static void swatch(UiGraphics g, int color, int x, int y, int size) {
        g.fill(x, y, size, size, 0xFF000000);
        g.fill(x + 1, y + 1, size - 2, size - 2, 0xFF000000 | color);
    }

    @Override
    public String tooltip() {
        String explicit = super.tooltip();
        return explicit != null ? explicit : biome;
    }
}
