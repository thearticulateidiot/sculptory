package dev.sculptory.mc.client.editor.windows;

import dev.sculptory.mc.client.editor.Translator;
import dev.sculptory.mc.client.editor.biomes.BiomeView;
import dev.sculptory.mc.client.editor.ui.Node;
import dev.sculptory.mc.client.editor.ui.Size;
import dev.sculptory.mc.client.editor.ui.TextLayout;
import dev.sculptory.mc.client.editor.ui.UiContext;
import dev.sculptory.mc.client.editor.ui.UiGraphics;
import dev.sculptory.mc.client.editor.ui.layout.Column;
import dev.sculptory.mc.client.editor.ui.widget.Label;
import dev.sculptory.mc.client.editor.ui.widget.ScrollPane;
import java.util.List;
import java.util.Objects;

/**
 * Biome view's legend (protocol 6): the biomes tinted around the camera, most of the ground first, each with its
 * colour, name and share of the tinted columns. Rebuilt when the view's snapshot changes; call {@link #refresh()} every
 * frame while it is open.
 */
public final class BiomeLegendWindow {
    public static final int WIDTH = 170;
    public static final int HEIGHT = 150;
    private static final int ROW_HEIGHT = 14;

    private final BiomeView view;
    private final Translator tr;
    private final Column rows = new Column();
    private final Node root;
    private BiomeView.Tint shown;
    private boolean shownOnce;

    public BiomeLegendWindow(BiomeView view, Translator translator) {
        this.view = Objects.requireNonNull(view);
        this.tr = Objects.requireNonNull(translator);
        rows.setGap(1);
        root = new ScrollPane(rows);
        refresh();
    }

    public Node node() {
        return root;
    }

    /** Lists the biomes of the view's current snapshot (or says the view is off or sees nothing). */
    public void refresh() {
        BiomeView.Tint tint = view.tint().orElse(null);
        if (shownOnce && tint == shown) return;
        shownOnce = true;
        shown = tint;
        rows.clear();
        if (tint == null) {
            rows.add(Label.dim(tr.translate(view.enabled() ? "sculptory.biome.legend.nothing"
                    : "sculptory.biome.legend.off")).setWrap(true));
            return;
        }
        List<BiomeView.LegendEntry> legend = tint.legend();
        if (legend.isEmpty()) {
            rows.add(Label.dim(tr.translate("sculptory.biome.legend.nothing")).setWrap(true));
            return;
        }
        for (BiomeView.LegendEntry entry : legend) {
            int percent = (int) Math.round(100.0 * entry.columns() / Math.max(1, tint.size()));
            rows.add(new Entry(entry, percent));
        }
    }

    /** One biome: swatch, name and share. */
    private static final class Entry extends Node {
        private final BiomeView.LegendEntry entry;
        private final String share;

        Entry(BiomeView.LegendEntry entry, int percent) {
            this.entry = entry;
            this.share = percent < 1 ? "<1%" : percent + "%";
            setTooltip(entry.id());
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            return new Size(60, ROW_HEIGHT);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            int size = ROW_HEIGHT - 4;
            g.fill(bounds.x() + 1, bounds.y() + 2, size, size, 0xFF000000);
            g.fill(bounds.x() + 2, bounds.y() + 3, size - 2, size - 2, 0xFF000000 | entry.color());
            int textY = bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2;
            int shareWidth = ctx.text().width(share);
            int textX = bounds.x() + size + 5;
            String name = TextLayout.ellipsize(ctx.text(), entry.name(), bounds.right() - shareWidth - 4 - textX);
            g.text(name, textX, textY, ctx.theme().text, ctx.theme().textShadow);
            g.text(share, bounds.right() - shareWidth - 1, textY, ctx.theme().textDim, ctx.theme().textShadow);
        }
    }
}
