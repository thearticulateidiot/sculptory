package dev.sculptory.fabric.client.editor.blocks;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.mask.MaskSummary;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.TextLayout;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ListView;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.item.ItemStack;

/**
 * The searchable block picker popup: a search field (focused, so the player can type at once) over
 * a virtualized list of every block with its icon. Click a block, or press Enter to take the first
 * match; Esc closes it.
 */
public final class BlockPicker {
    public static final int WIDTH = 200;
    /** The set picker is wider: its chosen entries sit above the search. */
    public static final int SET_WIDTH = 240;
    private static final String REMOVE = "×";
    private static final int ROW_HEIGHT = 18;

    private BlockPicker() {}

    /** Blocks whose name or id contains every word of {@code query}, ignoring case. */
    public static List<BlockCatalog.Entry> filter(List<BlockCatalog.Entry> entries, String query) {
        String[] words = query.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length == 1 && words[0].isEmpty()) {
            return entries;
        }
        List<BlockCatalog.Entry> matches = new ArrayList<>();
        for (BlockCatalog.Entry entry : entries) {
            String name = entry.name().toLowerCase(Locale.ROOT);
            String id = entry.block().block().value();
            boolean all = true;
            for (String word : words) {
                if (!name.contains(word) && !id.contains(word)) {
                    all = false;
                    break;
                }
            }
            if (all) {
                matches.add(entry);
            }
        }
        return matches;
    }

    /** One row of the set picker: a block tag or a block. */
    public sealed interface SetItem permits TagItem, BlockItem {}

    /** A block tag of the catalog. */
    public record TagItem(BlockCatalog.Tag tag) implements SetItem {}

    /** A block of the catalog (every state of it). */
    public record BlockItem(BlockCatalog.Entry entry) implements SetItem {}

    /**
     * The set picker's rows for {@code query}: the tags whose id contains every word (every tag for a query that is
     * just {@code #}; a query starting with {@code #} lists tags only), then the blocks as {@link #filter} finds them.
     * An empty query lists the blocks only.
     */
    public static List<SetItem> filterSet(List<BlockCatalog.Tag> tags, List<BlockCatalog.Entry> blocks, String query) {
        String trimmed = query.trim().toLowerCase(Locale.ROOT);
        boolean tagsOnly = trimmed.startsWith("#");
        String words = tagsOnly ? trimmed.substring(1).trim() : trimmed;
        List<SetItem> items = new ArrayList<>();
        if (tagsOnly || !words.isEmpty()) {
            String[] parts = words.split("\\s+");
            for (BlockCatalog.Tag tag : tags) {
                String id = tag.id().value();
                boolean all = true;
                for (String word : parts) {
                    if (!word.isEmpty() && !id.contains(word)) {
                        all = false;
                        break;
                    }
                }
                if (all) items.add(new TagItem(tag));
            }
        }
        if (!tagsOnly) {
            for (BlockCatalog.Entry entry : filter(blocks, words)) items.add(new BlockItem(entry));
        }
        return items;
    }

    /**
     * An exact state typed in the search ({@code minecraft:oak_log[axis=x]}, the namespace optional), or empty when the
     * text is not one.
     */
    public static Optional<BlockSet.State> typedState(String text) {
        String trimmed = text.trim();
        if (trimmed.indexOf('[') < 0) return Optional.empty();
        try {
            BlockDescriptor state = BlockDescriptor.parse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
            return state.properties().isEmpty() ? Optional.empty() : Optional.of(new BlockSet.State(state));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /** The set entry a picker row stands for. */
    public static BlockSet.Entry entryOf(SetItem item) {
        return switch (item) {
            case TagItem tag -> new BlockSet.Tag(tag.tag().id());
            case BlockItem block -> new BlockSet.Block(block.entry().block().block());
        };
    }

    /**
     * Opens the picker for a block set: blocks and block tags, up to {@code max}
     * entries, starting from {@code initial} ({@code null}: empty). The entries chosen show at the top, each a button
     * that removes it; a click on a row of the list (or Enter, for the first match) adds it, and Enter on a typed exact
     * state ({@code oak_log[axis=x]}) adds that state. {@code onPick} runs with the set when the popup closes (Done, Esc
     * or a click outside), unless it is empty.
     */
    public static PopupLayer.Popup openSet(UiContext ctx, Rect anchor, BlockCatalog catalog, Translator translator,
            BlockSet initial, int max, Consumer<BlockSet> onPick) {
        Objects.requireNonNull(onPick);
        if (max < 1 || max > BlockSet.MAX_ENTRIES) throw new IllegalArgumentException("A block set of 1-16 entries");
        List<BlockSet.Entry> chosen = new ArrayList<>(initial == null ? List.of() : initial.entries());
        List<BlockCatalog.Entry> all = catalog.entries();
        List<BlockCatalog.Tag> tags = catalog.tags();
        FlowRow current = new FlowRow();
        current.setGap(2);
        Label count = Label.dim("");
        ListView<SetItem> list = new ListView<>(filterSet(tags, all, ""),
                (item, index) -> new SetRow(catalog, translator, item));
        list.setRowHeight(ROW_HEIGHT);
        list.setPreferredRows(9);
        list.setActivateOnClick(true);
        list.setEmptyText(translator.translate("sculptory.picker.no_match"));
        Runnable[] show = new Runnable[1];
        Consumer<BlockSet.Entry> add = entry -> {
            if (chosen.contains(entry) || chosen.size() >= max) return;
            chosen.add(entry);
            show[0].run();
        };
        show[0] = () -> {
            current.clear();
            for (BlockSet.Entry entry : List.copyOf(chosen)) {
                Button chip = new Button(MaskSummary.entry(entry, catalog) + " " + REMOVE, null);
                chip.setOnClick(() -> {
                    chosen.remove(entry);
                    show[0].run();
                });
                chip.setTooltip(translator.translate("sculptory.picker.set.remove", entryText(entry)));
                current.add(chip);
            }
            count.setText(translator.translate("sculptory.picker.set.count", chosen.size(), max));
        };
        show[0].run();
        TextInput search = new TextInput("", text -> list.setItems(filterSet(tags, all, text)));
        search.setPlaceholder(translator.translate("sculptory.picker.set.search"));
        list.setOnActivate(index -> add.accept(entryOf(list.items().get(index))));
        search.setOnSubmit(text -> {
            Optional<BlockSet.State> state = typedState(text);
            if (state.isPresent()) {
                add.accept(state.get());
            } else if (!list.items().isEmpty()) {
                add.accept(entryOf(list.items().get(Math.max(0, list.selectedIndex()))));
            }
        });
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        Button done = new Button(translator.translate("sculptory.picker.set.done"), () -> ctx.popups().close(popup[0]));
        done.setStyle(Button.Style.PRIMARY);
        Label hint = Label.dim(translator.translate("sculptory.picker.set.hint"));
        hint.setWrap(true);
        dev.sculptory.fabric.client.editor.ui.layout.Row footer = dev.sculptory.fabric.client.editor.ui.layout.Row.of(
                count, Spacer.flexible(), done);
        footer.setGap(4);
        Column content = Column.of(current, search, list, hint, footer);
        content.setGap(3);
        content.setFixedWidth(SET_WIDTH - 2);
        popup[0] = ctx.popups().open(null, content, anchor, SET_WIDTH, () -> {
            if (!chosen.isEmpty()) onPick.accept(new BlockSet(chosen));
        });
        ctx.setFocus(search);
        return popup[0];
    }

    private static String entryText(BlockSet.Entry entry) {
        return switch (entry) {
            case BlockSet.Block block -> block.id().value();
            case BlockSet.Tag tag -> "#" + tag.tag().value();
            case BlockSet.State state -> state.state().format();
        };
    }

    /** One row of the set picker: a block's icon and name, or a tag's id and size. */
    private static final class SetRow extends Node {
        private final BlockCatalog catalog;
        private final SetItem item;
        private final String text;

        SetRow(BlockCatalog catalog, Translator translator, SetItem item) {
            this.catalog = catalog;
            this.item = item;
            this.text = switch (item) {
                case TagItem tag -> translator.translate("sculptory.picker.set.tag", "#" + tag.tag().id().value(),
                        tag.tag().blocks());
                case BlockItem block -> block.entry().name();
            };
            setTooltip(switch (item) {
                case TagItem tag -> "#" + tag.tag().id().value();
                case BlockItem block -> block.entry().block().block().value();
            });
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            return new Size(80, ROW_HEIGHT);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            if (item instanceof BlockItem block) {
                ItemStack icon = catalog.icon(block.entry().block());
                if (icon != null && !icon.isEmpty()) g.item(icon, bounds.x(), bounds.y() + 1);
            }
            int textX = bounds.x() + 20;
            String shown = TextLayout.ellipsize(ctx.text(), text, bounds.right() - textX);
            int color = item instanceof TagItem ? ctx.theme().accentHover : ctx.theme().text;
            g.text(shown, textX, bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2, color,
                    ctx.theme().textShadow);
        }
    }

    /**
     * Opens the picker below {@code anchor} in {@code ctx}'s popup layer. {@code onPick} runs with
     * the chosen block after the popup closes.
     */
    public static PopupLayer.Popup open(UiContext ctx, Rect anchor, BlockCatalog catalog, Translator translator,
            Consumer<BlockDescriptor> onPick) {
        Objects.requireNonNull(onPick);
        List<BlockCatalog.Entry> all = catalog.entries();
        ListView<BlockCatalog.Entry> list = new ListView<>(all, (entry, index) -> new Row(catalog, entry));
        list.setRowHeight(ROW_HEIGHT);
        list.setPreferredRows(10);
        list.setActivateOnClick(true);
        list.setEmptyText(translator.translate("sculptory.picker.no_match"));
        TextInput search = new TextInput("", text -> list.setItems(filter(all, text)));
        search.setPlaceholder(translator.translate("sculptory.picker.search"));
        Label hint = Label.dim(translator.translate("sculptory.picker.hint"));
        Column content = Column.of(search, list, hint);
        content.setFixedWidth(WIDTH - 2);
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        Consumer<BlockCatalog.Entry> choose = entry -> {
            ctx.popups().close(popup[0]);
            onPick.accept(entry.block());
        };
        list.setOnActivate(index -> choose.accept(list.items().get(index)));
        search.setOnSubmit(text -> {
            if (!list.items().isEmpty()) {
                choose.accept(list.items().get(Math.max(0, list.selectedIndex())));
            }
        });
        popup[0] = ctx.popups().open(null, content, anchor, WIDTH, null);
        ctx.setFocus(search);
        return popup[0];
    }

    /** One list row: the block's icon and name. */
    private static final class Row extends Node {
        private final BlockCatalog catalog;
        private final BlockCatalog.Entry entry;

        Row(BlockCatalog catalog, BlockCatalog.Entry entry) {
            this.catalog = catalog;
            this.entry = entry;
            setTooltip(entry.block().block().value());
        }

        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            return new Size(80, ROW_HEIGHT);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            ItemStack icon = catalog.icon(entry.block());
            if (icon != null && !icon.isEmpty()) {
                g.item(icon, bounds.x(), bounds.y() + 1);
            }
            int textX = bounds.x() + 20;
            String name = TextLayout.ellipsize(ctx.text(), entry.name(), bounds.right() - textX);
            g.text(name, textX, bounds.y() + (bounds.height() - ctx.text().lineHeight() + 1) / 2,
                    ctx.theme().text, ctx.theme().textShadow);
        }
    }
}
