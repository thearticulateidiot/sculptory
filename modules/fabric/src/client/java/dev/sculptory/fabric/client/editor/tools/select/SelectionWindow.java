package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.blocks.BlockCatalog;
import dev.sculptory.fabric.client.editor.blocks.BlockChip;
import dev.sculptory.fabric.client.editor.blocks.BlockPicker;
import dev.sculptory.fabric.client.editor.hud.JobBars;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.FlowRow;
import dev.sculptory.fabric.client.editor.ui.layout.Padding;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Label;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import dev.sculptory.fabric.client.editor.ui.widget.TextInput;
import dev.sculptory.fabric.client.editor.ui.widget.Toggle;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.clipboard.ExportFiles;
import dev.sculptory.fabric.client.editor.windows.ExportDialog;
import dev.sculptory.fabric.client.editor.windows.SaveAssetDialog;
import dev.sculptory.fabric.client.session.JobTracker;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Selection window: what is selected (a box, a shape, or a "Block selection": the set of blocks magic, brush or
 * lasso select makes), its size and exact block count, the min/max corners (editable for a box or shape; read-only for
 * a block selection, with Convert to box), and the Fill, Replace…, Erase, Hollow and Walls buttons with the wall
 * thickness, the clipboard buttons (Copy, Cut, Move, Stack, Save as asset…, Export…), plus the status of the last
 * op. The button rows wrap onto more lines in a narrow window. Call {@link #refresh()} every frame while it is open.
 */
public final class SelectionWindow {
    /** What the window needs from the editor. */
    public interface Host {
        /** The selection as held: cheap bounds and kind, read every frame. */
        Optional<Selection> selectionState();

        /** Sets or (with {@code null}) clears the selection (typed corners). */
        void setSelectionRegion(Region region);

        /** The Select tool's settings. */
        SettingsValues settings();

        void updateSettings(SettingsValues values);

        BlockDescriptor activeBlock();

        /** The block under the cursor when the window was last used over the world, if known. */
        Optional<BlockDescriptor> hoveredBlock();

        Optional<JobTracker.Job> job(UUID jobId);

        /** Opens the block picker below {@code anchor}. */
        void pickBlock(Rect anchor, BlockDescriptor current, Consumer<BlockDescriptor> onPick);

        /** The UI context whose popup layer holds the Replace popup. */
        UiContext popups();
    }

    private final SelectionActions actions;
    private final Host host;
    private final Translator tr;
    private final BlockCatalog blocks;
    private final Label size = Label.of("");
    private final Label status = Label.dim("");
    private final TextInput[] min = new TextInput[3];
    private final TextInput[] max = new TextInput[3];
    private final List<Button> opButtons;
    private final Button convert;
    private final Slider thickness;
    private final Node root;
    // The dialogs' choices, kept while the game runs.
    private BlockSet replaceFrom;
    private BlockDescriptor replaceTo;
    private boolean replacePalette;
    private boolean replaceKeepShape = true;
    private boolean replaceFamily;
    private int overlayDepth = 1;
    private BlockDescriptor naturalTop = BlockDescriptor.parse("minecraft:grass_block");
    private BlockDescriptor naturalMiddle = BlockDescriptor.parse("minecraft:dirt");
    private BlockDescriptor naturalBottom = BlockDescriptor.parse("minecraft:stone");
    private int naturalTopDepth = 1;
    private int naturalMiddleDepth = 3;
    private Box shown;
    private boolean shownResizable;
    private boolean shownOnce;

    /** Without clipboard buttons (Copy, Cut, Move, Stack, Save as asset…, Export…). */
    public SelectionWindow(SelectionActions actions, Host host, Translator translator, BlockCatalog blocks) {
        this(actions, host, translator, blocks, null);
    }

    /** @param clipboard the clipboard actions behind Copy, Cut, Move, Stack, Save as asset… and Export… */
    public SelectionWindow(SelectionActions actions, Host host, Translator translator, BlockCatalog blocks,
            ClipboardActions clipboard) {
        this.actions = Objects.requireNonNull(actions);
        this.host = Objects.requireNonNull(host);
        this.tr = Objects.requireNonNull(translator);
        this.blocks = Objects.requireNonNull(blocks);
        size.setWrap(true);
        status.setWrap(true);
        for (int axis = 0; axis < 3; axis++) {
            min[axis] = coordinateInput(axis, true);
            max[axis] = coordinateInput(axis, false);
        }
        Button fill = button("sculptory.op.fill", "sculptory.selection.fill.tooltip", actions::fill);
        Button replace = button("sculptory.op.replace_dots", "sculptory.selection.replace.tooltip", () -> { });
        replace.setOnClick(() -> openReplace(replace.bounds()));
        Button erase = button("sculptory.op.erase", "sculptory.selection.erase.tooltip", actions::erase);
        erase.setStyle(Button.Style.DANGER);
        Button hollow = button("sculptory.op.hollow", "sculptory.selection.hollow.tooltip", actions::hollow);
        Button walls = button("sculptory.op.walls", "sculptory.selection.walls.tooltip", actions::walls);
        Button overlay = button("sculptory.op.overlay_dots", "sculptory.selection.overlay.tooltip", () -> { });
        overlay.setOnClick(() -> openOverlay(overlay.bounds()));
        Button naturalize = button("sculptory.op.naturalize_dots", "sculptory.selection.naturalize.tooltip", () -> { });
        naturalize.setOnClick(() -> openNaturalize(naturalize.bounds()));
        Button update = button("sculptory.op.update_blocks", "sculptory.selection.update_blocks.tooltip",
                actions::updateBlocks);
        List<Button> buttons = new ArrayList<>(List.of(fill, replace, erase, hollow, walls, overlay, naturalize, update));
        thickness = Slider.ofInt(tr.translate("sculptory.selection.thickness"), SelectSettings.THICKNESS.min(),
                SelectSettings.THICKNESS.max(), host.settings().get(SelectSettings.THICKNESS),
                value -> host.updateSettings(host.settings().with(SelectSettings.THICKNESS, value)));
        thickness.setTooltip(tr.translate("sculptory.selection.thickness.tooltip"));
        convert = button("sculptory.selection.convert", "sculptory.selection.convert.tooltip",
                actions::convertToBox);
        Column column = Column.of(
                size,
                Row.of(axisLabel("sculptory.selection.min"), min[0], min[1], min[2]),
                Row.of(axisLabel("sculptory.selection.max"), max[0], max[1], max[2]),
                FlowRow.of(grow(convert)),
                FlowRow.of(grow(fill), grow(replace), grow(erase)),
                FlowRow.of(grow(hollow), grow(walls)),
                thickness,
                FlowRow.of(grow(overlay), grow(naturalize), grow(update)));
        if (clipboard != null) {
            Button copy = button("sculptory.selection.copy", "sculptory.selection.copy.tooltip",
                    () -> clipboard.copySelection(false));
            Button cut = button("sculptory.selection.cut", "sculptory.selection.cut.tooltip",
                    () -> clipboard.copySelection(true));
            Button move = button("sculptory.selection.move", "sculptory.selection.move.tooltip", clipboard::move);
            Button stack = button("sculptory.selection.stack", "sculptory.selection.stack.tooltip", clipboard::stack);
            Button save = button("sculptory.selection.save", "sculptory.selection.save.tooltip", () -> { });
            save.setOnClick(() -> SaveAssetDialog.open(host.popups(), save.bounds(), tr, "my_build.schem",
                    clipboard.format(), clipboard::saveSelection));
            Button export = button("sculptory.selection.export", "sculptory.selection.export.tooltip", () -> { });
            export.setOnClick(() -> ExportDialog.open(host.popups(), export.bounds(), tr, ExportFiles.DEFAULT_STEM,
                    clipboard.format(), (name, format) -> clipboard.exportSelection(format, name)));
            buttons.addAll(List.of(copy, cut, move, stack, save, export));
            column.add(FlowRow.of(grow(copy), grow(cut), grow(move), grow(stack)),
                    FlowRow.of(grow(save), grow(export)));
        }
        column.add(status);
        opButtons = List.copyOf(buttons);
        column.setGap(5);
        root = new ScrollPane(column);
        refresh();
    }

    public Node node() {
        return root;
    }

    /** Updates the labels, corner fields and buttons from the current selection and job. */
    public void refresh() {
        Optional<Selection> selection = host.selectionState();
        boolean has = selection.isPresent();
        boolean resizable = has && selection.get().resizable();
        for (Button button : opButtons) {
            button.setEnabled(has);
        }
        convert.setVisible(has && !(selection.get().base() instanceof Region.Cuboid));
        size.setText(has ? summary(selection.get()) : tr.translate("sculptory.selection.none"));
        Box box = selection.map(Selection::bounds).orElse(null);
        if (!shownOnce || !Objects.equals(box, shown) || resizable != shownResizable) {
            shownOnce = true;
            shown = box;
            shownResizable = resizable;
            for (int axis = 0; axis < 3; axis++) {
                min[axis].setText(box == null ? "" : Integer.toString(SelectionModel.coordinate(box.min(), axis)));
                max[axis].setText(box == null ? "" : Integer.toString(SelectionModel.coordinate(box.max(), axis)));
                min[axis].setEnabled(resizable);
                max[axis].setEnabled(resizable);
                min[axis].setTooltip(cornerTooltip(axis, true, has && !resizable));
                max[axis].setTooltip(cornerTooltip(axis, false, has && !resizable));
            }
        }
        thickness.setValue(host.settings().get(SelectSettings.THICKNESS));
        status.setText(jobStatus());
    }

    private String jobStatus() {
        Optional<UUID> job = actions.lastJob();
        if (job.isEmpty()) {
            return "";
        }
        return host.job(job.get()).map(state -> JobBars.describe(state, tr)).orElse("");
    }

    /**
     * "Sphere  ·  12 × 5 × 8  ·  1,234 blocks": the kind, the bounds' size and the exact number of blocks. A large
     * shape's count comes from the background ("counting…" until then); moving never changes it.
     */
    private String summary(Selection selection) {
        String kind = tr.translate(kindKey(selection.base()));
        String size = SelectionModel.dimensions(selection.bounds());
        OptionalLong cells = actions.regionWork().countNow(selection.base());
        if (cells.isPresent()) {
            return tr.translate("sculptory.selection.summary", kind, size, SelectionModel.count(cells.getAsLong()));
        }
        return tr.translate(RegionWork.countable(selection.base()) ? "sculptory.selection.summary_counting"
                : "sculptory.selection.summary_uncounted", kind, size);
    }

    /** The translation key naming what is selected. */
    static String kindKey(Region region) {
        return switch (region) {
            case Region.Cuboid cuboid -> "sculptory.selection.kind.box";
            case Region.Shape shape -> "sculptory.selection.kind."
                    + SelectSettings.SelectShape.of(shape.kind()).name().toLowerCase(Locale.ROOT);
            // A set of blocks, from magic, brush or lasso select: a "Block selection".
            case Region.Cells cells -> "sculptory.selection.kind.cells";
            case Region.Uploaded uploaded -> "sculptory.selection.kind.cells";
        };
    }

    private String cornerTooltip(int axis, boolean isMin, boolean locked) {
        if (locked) {
            return tr.translate("sculptory.selection.corners_locked");
        }
        return tr.translate(isMin ? "sculptory.selection.min.tooltip" : "sculptory.selection.max.tooltip",
                new String[] {"X", "Y", "Z"}[axis]);
    }

    /** A corner field: typing a coordinate resizes a box or shape (which keeps its kind); a cell set can't be. */
    private TextInput coordinateInput(int axis, boolean isMin) {
        TextInput input = new TextInput("", text -> {
            Integer value = parse(text);
            Optional<Region> next = value == null ? Optional.empty()
                    : host.selectionState().filter(Selection::resizable)
                            .flatMap(state -> withCorner(state.region(), axis, isMin, value));
            if (next.isPresent()) {
                shown = next.get().bounds();
                host.setSelectionRegion(next.get());
            }
        });
        // Three share the row after the label, and narrow with the window rather than run past it.
        input.setMinSize(24, 0);
        input.setGrow(1);
        input.setMaxLength(9);
        input.setTooltip(cornerTooltip(axis, isMin, false));
        return input;
    }

    /**
     * A box or shape with one coordinate of its min or max corner typed (axes passing the other corner swap into
     * order); empty for a cell set, whose corners can't be typed.
     */
    static Optional<Region> withCorner(Region region, int axis, boolean isMin, int value) {
        if (!SelectionModel.resizable(region)) {
            return Optional.empty();
        }
        Box box = region.bounds();
        BlockPos changed = SelectionModel.with(isMin ? box.min() : box.max(), axis, value);
        Box next = isMin ? SelectionModel.withMin(box, changed) : SelectionModel.withMax(box, changed);
        return Optional.of(SelectionModel.withBounds(region, next));
    }

    // ---- For tests ----

    Label summaryLabel() {
        return size;
    }

    Button convertButton() {
        return convert;
    }

    TextInput corner(int axis, boolean isMin) {
        return isMin ? min[axis] : max[axis];
    }

    List<Button> opButtons() {
        return opButtons;
    }

    /**
     * Opens the Replace… dialog below {@code anchor} (its button, or the menu bar's Selection > Replace…): From (up to 16
     * blocks or block tags, starting with the block under the cursor), To (a block, or the Select palette), Keep shape
     * (on by default) and Whole family. The choices are kept
     * until the game closes; From starts again from the block under the cursor.
     */
    public void openReplace(Rect anchor) {
        UiContext ctx = host.popups();
        replaceFrom = BlockSet.of(new BlockSet.Block(host.hoveredBlock().orElse(host.activeBlock()).block()));
        if (replaceTo == null) replaceTo = host.activeBlock();
        Button from = new Button(fromSummary(replaceFrom), () -> { });
        from.setTooltip(tr.translate("sculptory.selection.replace.from.tooltip"));
        BlockChip to = new BlockChip(blocks, replaceTo, null);
        from.setGrow(1);
        to.setGrow(1);
        Label hint = Label.dim("").setWrap(true);
        Runnable[] changed = {() -> { }};
        Toggle palette = new Toggle(tr.translate("sculptory.selection.replace.palette"), replacePalette,
                on -> changed[0].run());
        palette.setTooltip(tr.translate("sculptory.selection.replace.palette.tooltip"));
        Toggle keepShape = new Toggle(tr.translate("sculptory.selection.replace.keep_shape"), replaceKeepShape,
                on -> changed[0].run());
        keepShape.setTooltip(tr.translate("sculptory.selection.replace.keep_shape.tooltip"));
        Toggle family = new Toggle(tr.translate("sculptory.selection.replace.family"), replaceFamily,
                on -> changed[0].run());
        family.setTooltip(tr.translate("sculptory.selection.replace.family.tooltip"));
        Runnable update = () -> {
            replacePalette = palette.value();
            replaceKeepShape = keepShape.value();
            replaceFamily = family.value();
            // Whole family always keeps the shape and swaps block for block: no palette.
            palette.setEnabled(!replaceFamily);
            keepShape.setEnabled(!replaceFamily);
            replaceTo = to.block();
            from.setText(fromSummary(replaceFrom));
            hint.setText(replaceHint());
        };
        changed[0] = update;
        from.setOnClick(() -> BlockPicker.openSet(ctx, from.bounds(), blocks, tr, replaceFrom, BlockSet.MAX_ENTRIES,
                set -> {
                    replaceFrom = set;
                    update.run();
                }));
        to.setOnClick(() -> host.pickBlock(to.bounds(), to.block(), block -> {
            to.setBlock(block);
            update.run();
        }));
        update.run();
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        Button cancel = new Button(tr.translate("sculptory.dialog.cancel"), () -> ctx.popups().close(popup[0]));
        Button confirm = new Button(tr.translate("sculptory.op.replace"), () -> {
            ctx.popups().close(popup[0]);
            runReplace();
        });
        confirm.setStyle(Button.Style.PRIMARY);
        Column content = Column.of(
                Label.heading(tr.translate("sculptory.selection.replace.title")),
                Row.of(fixedLabel("sculptory.selection.replace.from", 30), from),
                Row.of(fixedLabel("sculptory.selection.replace.to", 30), to),
                palette, keepShape, family, hint,
                Row.of(Spacer.flexible(), cancel, confirm));
        content.setGap(5);
        content.setFixedWidth(220);
        popup[0] = ctx.popups().open(null, new Padding(Insets.all(5), content), anchor, 232, null);
        ctx.setFocus(confirm);
    }

    /** Sends the Replace the dialog's choices describe (its Replace button). */
    boolean runReplace() {
        if (replaceFamily) {
            Optional<BlockDescriptor> single = singleBlock(replaceFrom);
            if (single.isEmpty()) {
                actions.notice("sculptory.notice.family_needs_one_block");
                return false;
            }
            return actions.replaceFamily(single.get(), replaceTo);
        }
        return actions.replace(replaceFrom, replaceTo, replacePalette, replaceKeepShape);
    }

    /** The dialog's line under the choices: what the Replace will do. */
    String replaceHint() {
        if (replaceFamily) {
            Optional<BlockDescriptor> single = singleBlock(replaceFrom);
            if (single.isEmpty()) return tr.translate("sculptory.notice.family_needs_one_block");
            int swaps = actions.familySwaps(single.get(), replaceTo).size();
            return swaps == 0
                    ? tr.translate("sculptory.selection.replace.family_none", blocks.name(single.get()),
                            blocks.name(replaceTo))
                    : tr.translate("sculptory.selection.replace.family_hint", blocks.name(single.get()),
                            blocks.name(replaceTo), Integer.toString(swaps));
        }
        return tr.translate(replacePalette ? "sculptory.selection.replace.hint_palette"
                : replaceKeepShape ? "sculptory.selection.replace.hint_keep" : "sculptory.selection.replace.hint");
    }

    /** "Oak Planks", "Oak Planks + 2 more", "#minecraft:logs". */
    String fromSummary(BlockSet set) {
        String first = switch (set.entries().get(0)) {
            case BlockSet.Block block -> blocks.name(BlockDescriptor.of(block.id()));
            case BlockSet.Tag tag -> "#" + tag.tag().value();
            case BlockSet.State state -> state.state().format();
        };
        int more = set.entries().size() - 1;
        return more == 0 ? first : tr.translate("sculptory.selection.replace.from_more", first, Integer.toString(more));
    }

    /** The set's block when it is exactly one block (Whole family needs that). */
    private static Optional<BlockDescriptor> singleBlock(BlockSet set) {
        return set.entries().size() == 1 && set.entries().get(0) instanceof BlockSet.Block block
                ? Optional.of(BlockDescriptor.of(block.id())) : Optional.empty();
    }

    /** Opens the Overlay… dialog below {@code anchor}: the depth (1-16), then Overlay. */
    public void openOverlay(Rect anchor) {
        UiContext ctx = host.popups();
        Slider depth = Slider.ofInt(tr.translate("sculptory.selection.depth"), 1, OpSpec.MAX_LAYER_DEPTH,
                overlayDepth, value -> overlayDepth = value);
        depth.setTooltip(tr.translate("sculptory.selection.overlay.depth.tooltip"));
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        Button cancel = new Button(tr.translate("sculptory.dialog.cancel"), () -> ctx.popups().close(popup[0]));
        Button confirm = new Button(tr.translate("sculptory.op.overlay"), () -> {
            ctx.popups().close(popup[0]);
            actions.overlay(overlayDepth);
        });
        confirm.setStyle(Button.Style.PRIMARY);
        Column content = Column.of(
                Label.heading(tr.translate("sculptory.selection.overlay.title")),
                Label.dim(tr.translate("sculptory.selection.overlay.hint")).setWrap(true),
                depth,
                Row.of(Spacer.flexible(), cancel, confirm));
        content.setGap(5);
        content.setFixedWidth(200);
        popup[0] = ctx.popups().open(null, new Padding(Insets.all(5), content), anchor, 212, null);
        ctx.setFocus(confirm);
    }

    /** Opens the Naturalize… dialog below {@code anchor}: the top, middle and bottom blocks and depths. */
    public void openNaturalize(Rect anchor) {
        UiContext ctx = host.popups();
        BlockChip top = new BlockChip(blocks, naturalTop, null);
        BlockChip middle = new BlockChip(blocks, naturalMiddle, null);
        BlockChip bottom = new BlockChip(blocks, naturalBottom, null);
        top.setOnClick(() -> host.pickBlock(top.bounds(), top.block(),
                block -> naturalTop = top.setBlock(block).block()));
        middle.setOnClick(() -> host.pickBlock(middle.bounds(), middle.block(),
                block -> naturalMiddle = middle.setBlock(block).block()));
        bottom.setOnClick(() -> host.pickBlock(bottom.bounds(), bottom.block(),
                block -> naturalBottom = bottom.setBlock(block).block()));
        top.setGrow(1);
        middle.setGrow(1);
        bottom.setGrow(1);
        Slider topDepth = Slider.ofInt(tr.translate("sculptory.selection.depth"), 1, OpSpec.MAX_LAYER_DEPTH,
                naturalTopDepth, value -> naturalTopDepth = value);
        Slider middleDepth = Slider.ofInt(tr.translate("sculptory.selection.depth"), 0, OpSpec.MAX_LAYER_DEPTH,
                naturalMiddleDepth, value -> naturalMiddleDepth = value);
        PopupLayer.Popup[] popup = new PopupLayer.Popup[1];
        Button cancel = new Button(tr.translate("sculptory.dialog.cancel"), () -> ctx.popups().close(popup[0]));
        Button confirm = new Button(tr.translate("sculptory.op.naturalize"), () -> {
            ctx.popups().close(popup[0]);
            actions.naturalize(naturalTop, naturalTopDepth, naturalMiddle, naturalMiddleDepth, naturalBottom);
        });
        confirm.setStyle(Button.Style.PRIMARY);
        Column content = Column.of(
                Label.heading(tr.translate("sculptory.selection.naturalize.title")),
                Row.of(fixedLabel("sculptory.selection.naturalize.top", 40), top),
                topDepth,
                Row.of(fixedLabel("sculptory.selection.naturalize.middle", 40), middle),
                middleDepth,
                Row.of(fixedLabel("sculptory.selection.naturalize.bottom", 40), bottom),
                Label.dim(tr.translate("sculptory.selection.naturalize.hint")).setWrap(true),
                Row.of(Spacer.flexible(), cancel, confirm));
        content.setGap(5);
        content.setFixedWidth(200);
        popup[0] = ctx.popups().open(null, new Padding(Insets.all(5), content), anchor, 212, null);
        ctx.setFocus(confirm);
    }

    private Button button(String textKey, String tooltipKey, Runnable action) {
        Button button = new Button(tr.translate(textKey), action);
        button.setTooltip(tr.translate(tooltipKey));
        return button;
    }

    private Label axisLabel(String key) {
        return fixedLabel(key, 24);
    }

    private Label fixedLabel(String key, int width) {
        Label label = Label.dim(tr.translate(key));
        label.setFixedWidth(width);
        return label;
    }

    private static <T extends Node> T grow(T node) {
        node.setGrow(1);
        return node;
    }

    private static Integer parse(String text) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException malformed) {
            return null;
        }
    }
}
