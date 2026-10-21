package dev.sculptory.mc.client.editor.biomes;

import dev.sculptory.mc.client.editor.Translator;
import dev.sculptory.mc.client.editor.ui.Node;
import dev.sculptory.mc.client.editor.ui.PopupLayer;
import dev.sculptory.mc.client.editor.ui.Rect;
import dev.sculptory.mc.client.editor.ui.Size;
import dev.sculptory.mc.client.editor.ui.TextLayout;
import dev.sculptory.mc.client.editor.ui.UiContext;
import dev.sculptory.mc.client.editor.ui.UiGraphics;
import dev.sculptory.mc.client.editor.ui.layout.Column;
import dev.sculptory.mc.client.editor.ui.widget.Label;
import dev.sculptory.mc.client.editor.ui.widget.ListView;
import dev.sculptory.mc.client.editor.ui.widget.TextInput;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The searchable biome picker popup (protocol 6), like the block picker: a search field (focused, so the player can
 * type at once) over the list of every biome the client knows, each with its colour swatch. Click a biome, or press
 * Enter to take the first match; Esc closes it.
 */
public final class BiomePicker {
    public static final int WIDTH = 200;
    private static final int ROW_HEIGHT = 16;

    private BiomePicker() {}

    /**
     * Opens the picker below {@code anchor} in {@code ctx}'s popup layer. {@code onPick} runs with the chosen biome's id
     * after the popup closes.
     */
    public static PopupLayer.Popup open(UiContext ctx, Rect anchor, BiomeCatalog catalog, Translator translator,
                                        Consumer<String> onPick) {
        Objects.requireNonNull(onPick);
        List<BiomeCatalog.Entry> all = catalog.entries();
        ListView<BiomeCatalog.Entry> list = new ListView<>(all, (entry, index) -> new Row(entry));
        list.setRowHeight(ROW_HEIGHT);
        list.setPreferredRows(10);
        list.setActivateOnClick(true);
        list.setEmptyText(translator.translate("sculptory.picker.no_match"));
        TextInput search = new TextInput("", text -> list.setItems(BiomeCatalog.filter(all, text)));
        search.setPlaceholder(translator.translate("sculptory.picker.biome.search"));
        Label hint = Label.dim(translator.translate("sculptory.picker.hint"));
        Column content = Column.of(search, list, hint);
        content.setFixedWidth(WIDTH - 2);
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        Consumer<BiomeCatalog.Entry> choose = entry -> {
            ctx.popups().close(popup[0]);
            onPick.accept(entry.id());
        };
        list.setOnActivate(index -> choose.accept(list.items().get(index)));
        search.setOnSubmit(text -> {
            if (!list.items().isEmpty()) choose.accept(list.items().get(Math.max(0, list.selectedIndex())));
        });
        popup[0] = ctx.popups().open(null, content, anchor, WIDTH, null);
        ctx.setFocus(search);
        return popup[0];
    }

    /** One list row: the biome's swatch and name, its id as the tooltip. */
    private static final class Row extends Node {
        private final BiomeCatalog.Entry entry;

        Row(BiomeCatalog.Entry entry) {
            this.entry = entry;
            setTooltip(entry.id());
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            return new Size(80, ROW_HEIGHT);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            BiomeChip.swatch(g, entry.color(), bounds.x() + 2, bounds.y() + 2, ROW_HEIGHT - 4);
            int textX = bounds.x() + ROW_HEIGHT + 2;
            String name = TextLayout.ellipsize(ctx.text(), entry.name(), bounds.right() - textX);
            g.text(name, textX, bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2, ctx.theme().text,
                    ctx.theme().textShadow);
        }
    }
}
