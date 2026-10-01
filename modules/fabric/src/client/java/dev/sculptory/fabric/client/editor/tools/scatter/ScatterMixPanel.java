package dev.sculptory.fabric.client.editor.tools.scatter;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.scatter.BlockVariants;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.palettes.PaletteButtons;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.Menu;
import dev.sculptory.fabric.client.editor.ui.widget.MenuItem;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.windows.ToolSettingsWindow;
import dev.sculptory.fabric.client.session.ClipboardCache;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.SessionNotices;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Scatter tool's part of the Tool Settings window, above its generated settings: the area (its size, Use selection,
 * Clear) and the variant mix (one row per variant: name, a small "underwater" or "on water" tag for a water plant,
 * dimensions once its preview is in, weight 1-1000 and remove; Add clipboard; + Block, which opens the block picker;
 * + Tree and + Feature, which list the vanilla trees and features of {@link FeatureCatalog}). Rows are rebuilt only when
 * variants are added or removed, or a preset replaces the mix, so typing a weight keeps focus. With
 * {@link #setPalettes}, Save palette… and Load palette… follow (palettes: the mix's block rows are saved, and a load
 * replaces them, keeping asset rows). A tree or feature row is tagged "tree" or "feature".
 */
public final class ScatterMixPanel implements ToolSettingsWindow.Panel {
    /** Opens the block picker below {@code anchor}; {@code onPick} gets the chosen block. */
    @FunctionalInterface
    public interface BlockPicking {
        void pick(Rect anchor, Consumer<BlockDescriptor> onPick);
    }

    private static final int WARN_COLOR = 0xFFE0B040;
    private static final int ERROR_COLOR = 0xFFE5655D;
    private static final int WATER_COLOR = 0xFF60C8FF;
    private static final String REMOVE = "×";

    private final ScatterTool tool;
    private final Supplier<Optional<EditorSession>> session;
    private final Supplier<String> clipboardName;
    private final BlockPicking blocks;
    private final Translator tr;

    // The tree of the last build()
    private Label areaLabel;
    private Label heading;
    private Column rows;
    private Button addClipboard;
    private Button addBlock;
    private Button addTree;
    private Button addFeature;
    /** Where + Tree and + Feature open their lists, once {@link #setMenus} gave it. */
    private Supplier<UiContext> menus;
    private final List<Label> dims = new ArrayList<>();
    private final List<Label> tags = new ArrayList<>();
    private List<ScatterSource> shownSources = List.of();
    private int shownGeneration;
    /** Save palette… and Load palette… (palettes), once {@link #setPalettes} gave them. */
    private PaletteButtons palettes;
    private PaletteButtons.Buttons paletteRow;

    /**
     * @param clipboardName what the current clipboard is called (where it came from), for its row
     * @param blocks opens the block picker for + Block
     */
    public ScatterMixPanel(ScatterTool tool, Supplier<Optional<EditorSession>> session, Supplier<String> clipboardName,
                           BlockPicking blocks, Translator translator) {
        this.tool = Objects.requireNonNull(tool);
        this.session = Objects.requireNonNull(session);
        this.clipboardName = Objects.requireNonNull(clipboardName);
        this.blocks = Objects.requireNonNull(blocks);
        this.tr = Objects.requireNonNull(translator);
    }

    /** Lets + Tree and + Feature open their lists (menus) in the editor's UI context. */
    public void setMenus(Supplier<UiContext> context) {
        this.menus = Objects.requireNonNull(context);
    }

    /** Shows Save palette… and Load palette… under the mix (palettes: its block rows only). */
    public void setPalettes(PaletteButtons palettes) {
        this.palettes = Objects.requireNonNull(palettes);
    }

    @Override
    public Node build() {
        areaLabel = Label.dim("");
        areaLabel.setWrap(true);
        Button useSelection = new Button(tr.translate("sculptory.scatter.panel.use_selection"), tool::useSelection);
        useSelection.setTooltip(tr.translate("sculptory.scatter.panel.use_selection.tooltip"));
        useSelection.setGrow(1);
        Button clear = new Button(tr.translate("sculptory.scatter.panel.clear_area"), tool::clearArea);
        clear.setTooltip(tr.translate("sculptory.scatter.panel.clear_area.tooltip"));
        clear.setGrow(1);
        heading = Label.heading("");
        rows = new Column();
        rows.setGap(2);
        addClipboard = new Button(tr.translate("sculptory.scatter.panel.add_clipboard"),
                () -> session.get().ifPresent(s -> tool.addClipboard(s, clipboardName.get())));
        addClipboard.setTooltip(tr.translate("sculptory.scatter.panel.add_clipboard.tooltip"));
        addClipboard.setGrow(1);
        addBlock = new Button(tr.translate("sculptory.scatter.panel.add_block"),
                () -> blocks.pick(addBlock.bounds(), tool::addBlock));
        addBlock.setTooltip(tr.translate("sculptory.scatter.panel.add_block.tooltip"));
        addBlock.setGrow(1);
        addTree = new Button(tr.translate("sculptory.scatter.panel.add_tree"),
                () -> openCatalog(addTree.bounds(), FeatureCatalog.Kind.TREE));
        addTree.setTooltip(tr.translate("sculptory.scatter.panel.add_tree.tooltip"));
        addTree.setGrow(1);
        addFeature = new Button(tr.translate("sculptory.scatter.panel.add_feature"),
                () -> openCatalog(addFeature.bounds(), FeatureCatalog.Kind.FEATURE));
        addFeature.setTooltip(tr.translate("sculptory.scatter.panel.add_feature.tooltip"));
        addFeature.setGrow(1);
        Label hint = Label.dim(tr.translate("sculptory.scatter.panel.hint"));
        hint.setWrap(true);
        shownSources = null;
        Column column = Column.of(areaLabel, Row.of(useSelection, clear), heading, rows,
                Row.of(addClipboard, addBlock), Row.of(addTree, addFeature));
        paletteRow = palettes == null ? null : palettes.row(ToolId.SCATTER);
        if (paletteRow != null) column.add(paletteRow.node());
        column.add(hint);
        column.setGap(4);
        refresh();
        return column;
    }

    @Override
    public void refresh() {
        if (rows == null) return;
        PaintedArea area = tool.area();
        String areaText;
        if (area.box().isPresent()) {
            areaText = tr.translate("sculptory.scatter.panel.area_box", area.box().get().sizeX() + "×"
                    + area.box().get().sizeZ());
        } else if (area.stampCount() == 0) {
            areaText = tr.translate("sculptory.scatter.panel.area_empty");
        } else {
            areaText = tr.translate("sculptory.scatter.panel.area_stamps", Integer.toString(area.stampCount()),
                    Integer.toString(PaintedArea.MAX_STAMPS), SessionNotices.count(area.columns()));
        }
        areaLabel.setText(areaText);
        List<ScatterMix.Variant> variants = tool.mix().variants();
        heading.setText(tr.translate("sculptory.scatter.panel.variants", Integer.toString(variants.size()),
                Integer.toString(ScatterMix.MAX_VARIANTS)));
        List<ScatterSource> sources = variants.stream().map(ScatterMix.Variant::source).toList();
        if (!sources.equals(shownSources) || tool.mix().generation() != shownGeneration) {
            shownSources = sources;
            shownGeneration = tool.mix().generation();
            showRows(variants);
        }
        for (int i = 0; i < variants.size() && i < dims.size(); i++) {
            status(variants.get(i), dims.get(i));
            tag(variants.get(i), tags.get(i));
        }
        addClipboard.setEnabled(session.get().flatMap(s -> s.clipboards().current()).isPresent());
        if (paletteRow != null) paletteRow.refresh();
        boolean blocksAllowed = session.get().map(s -> ScatterTool.mayScatterBlocks(s.permissions())).orElse(false);
        if (blocksAllowed != addBlock.isEnabled()) {
            addBlock.setEnabled(blocksAllowed);
            addBlock.setTooltip(tr.translate(blocksAllowed ? "sculptory.scatter.panel.add_block.tooltip"
                    : "sculptory.scatter.panel.add_block.no_permission"));
        }
        // Trees and features need what blocks need (brush or region).
        if (blocksAllowed != addTree.isEnabled()) {
            addTree.setEnabled(blocksAllowed);
            addFeature.setEnabled(blocksAllowed);
            String denied = tr.translate("sculptory.scatter.panel.add_feature.no_permission");
            addTree.setTooltip(blocksAllowed ? tr.translate("sculptory.scatter.panel.add_tree.tooltip") : denied);
            addFeature.setTooltip(blocksAllowed ? tr.translate("sculptory.scatter.panel.add_feature.tooltip") : denied);
        }
    }

    /** + Tree / + Feature: a list of the catalog's entries of that kind, below the button; a pick adds it to the mix. */
    private void openCatalog(Rect anchor, FeatureCatalog.Kind kind) {
        if (menus == null) return;
        List<MenuItem> items = new ArrayList<>();
        for (FeatureCatalog.FeatureDef def : FeatureCatalog.ALL) {
            if (def.kind() != kind) continue;
            items.add(MenuItem.action(tool.featureName(def.id()), "", () -> tool.addFeature(def)).tooltip(def.id()));
        }
        new Menu(items).open(menus.get(), anchor, null);
    }

    private void showRows(List<ScatterMix.Variant> variants) {
        rows.clear();
        dims.clear();
        tags.clear();
        if (variants.isEmpty()) {
            Label empty = Label.dim(tr.translate("sculptory.scatter.panel.no_variants"));
            empty.setWrap(true);
            rows.add(empty);
            return;
        }
        for (int i = 0; i < variants.size(); i++) {
            int index = i;
            ScatterMix.Variant variant = variants.get(i);
            Label name = Label.of(variant.name());
            // A block's tooltip is its exact state: two states of one block share a name.
            name.setTooltip(variant.source() instanceof ScatterSource.Block block ? block.state()
                    : variant.source() instanceof ScatterSource.Feature feature ? feature.id() : variant.name());
            name.setGrow(1);
            Label tag = Label.dim("");
            tags.add(tag);
            Label size = Label.dim("");
            dims.add(size);
            TextInput weight = new TextInput(Integer.toString(variant.weight()), text -> {
                try {
                    int parsed = Integer.parseInt(text.trim());
                    if (parsed >= 1 && parsed <= ScatterMix.MAX_WEIGHT) tool.setWeight(index, parsed);
                } catch (NumberFormatException typing) {
                    // keep typing
                }
            });
            weight.setMaxLength(4);
            weight.setFixedWidth(30);
            weight.setTooltip(tr.translate("sculptory.scatter.panel.weight"));
            Button remove = new Button(REMOVE, () -> tool.removeVariant(index));
            remove.setTooltip(tr.translate("sculptory.scatter.panel.remove"));
            Row row = Row.of(name, tag, size, weight, remove);
            row.setGap(3);
            rows.add(row);
        }
    }

    /** The row's tag: where a water plant goes (under water, on the water surface); nothing for land variants. */
    private void tag(ScatterMix.Variant variant, Label label) {
        if (variant.source() instanceof ScatterSource.Feature feature) {
            boolean tree = FeatureCatalog.find(feature.id()).map(def -> def.kind() == FeatureCatalog.Kind.TREE)
                    .orElse(false);
            String key = tree ? "sculptory.scatter.panel.tree" : "sculptory.scatter.panel.feature";
            label.setText(tr.translate(key));
            label.setColor(0);
            label.setTooltip(tr.translate(key + ".tooltip"));
            return;
        }
        Optional<BlockVariants.Medium> medium = tool.variantMedium(variant.source());
        String key = medium.map(m -> switch (m) {
            case UNDERWATER -> "sculptory.scatter.panel.underwater";
            case WATER_SURFACE -> "sculptory.scatter.panel.on_water";
            case LAND -> null;
        }).orElse(null);
        if (key == null) {
            label.setText("");
            label.setTooltip(null);
            return;
        }
        label.setText(tr.translate(key));
        label.setColor(WATER_COLOR);
        label.setTooltip(tr.translate(key + ".tooltip"));
    }

    /** The row's size column: dimensions, or where its preview stands. */
    private void status(ScatterMix.Variant variant, Label label) {
        Optional<EditorSession> current = session.get();
        if (variant.source() instanceof ScatterSource.Held held && held.ref() instanceof SourceRef.Clipboard clipboard
                && current.map(s -> s.clipboards().get(clipboard.id()).isEmpty()).orElse(true)) {
            label.setText(tr.translate("sculptory.scatter.panel.replaced"));
            label.setColor(WARN_COLOR);
            label.setTooltip(tr.translate("sculptory.scatter.panel.replaced.tooltip"));
            return;
        }
        Optional<ClipboardCache.Preview> preview = tool.variantPreview(variant.source());
        switch (tool.variantStatus(variant.source())) {
            case FAILED -> {
                label.setText(tr.translate("sculptory.scatter.panel.failed"));
                label.setColor(ERROR_COLOR);
                label.setTooltip(tr.translate(variant.source() instanceof ScatterSource.Block
                        ? "sculptory.scatter.panel.block_unknown.tooltip" : "sculptory.scatter.panel.failed.tooltip"));
            }
            case LOADING -> {
                label.setText(preview.map(p -> dims(p.dims())).orElse("…"));
                label.setColor(0);
                label.setTooltip(null);
            }
            case READY -> {
                label.setText(preview.map(p -> dims(p.dims())).orElse(""));
                label.setColor(0);
                label.setTooltip(null);
            }
        }
    }

    private static String dims(BlockPos dims) {
        return dims.x() + "×" + dims.y() + "×" + dims.z();
    }
}
