package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.BlockFamilies;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tools.brush.GradientLine;
import dev.sculptory.fabric.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.OpLabel;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The operations on the shared selection: Fill, Replace, Erase, Hollow and Walls, plus deselect, nudge, reshape and
 * convert to box. Each op is built from the selection's region (a box, a shape or a cell set: the op applies to
 * exactly its cells), the active block (or the Select tool's palette; a Fill's Into setting becomes its mask,
 * {@link #intoMask}) and sent as {@code ToolAction.RunOp}. Sizes
 * count the region's cells: ops over {@link #CONFIRM_VOLUME} blocks ask first; ops over the server's volume limit are
 * refused here with the limit in the message, and a shape without cells is refused with a toast.
 *
 * <p>With a symmetry mode set ({@link SelectSettings#SYMMETRY}) the op carries the mode around the shared
 * {@link SymmetryCentre}, sizes count every copy
 * ("Fill 12,000 blocks (4 copies)"), and without a set centre the op is refused with a toast (there is no fallback to
 * the selection's centre: a mirror about it would change nothing).
 *
 * <p>Used by the Select tool (keys), the Selection window (buttons) and the editor (Delete with any
 * tool active). Client thread only.
 */
public final class SelectionActions {
    /** Ops over this many blocks need confirmation (Enter confirms, Esc cancels). */
    public static final long CONFIRM_VOLUME = 500_000L;
    /** The toast of an op with a symmetry mode on and no centre set. */
    public static final String SYMMETRY_CENTRE_NEEDED = "sculptory.notice.symmetry_centre_needed";

    /** Asks the player to confirm a large op. {@code onConfirm} runs only if they do. */
    @FunctionalInterface
    public interface Confirmer {
        void confirm(String message, Runnable onConfirm);
    }

    /**
     * The ops, for labels (Extrude, Carve and Smear are the Extrude tool's, sent through {@link #runOn}; Tinker is
     * {@link #applyProperty}).
     */
    public enum Op {
        FILL, REPLACE, ERASE, HOLLOW, WALLS, EXTRUDE, CARVE, SMEAR, TINKER, OVERLAY, NATURALIZE, UPDATE_BLOCKS;

        public String nameKey() {
            return "sculptory.op." + name().toLowerCase(Locale.ROOT);
        }

        /**
         * The label the server names the job and its history entry with: the Extrude tool's ops carry their own name;
         * Tinker's fill is named by the server from its property pattern ("Tinker · shape: outer left · N blocks").
         */
        public OpLabel label() {
            return switch (this) {
                case EXTRUDE -> OpLabel.EXTRUDE;
                case CARVE -> OpLabel.CARVE;
                case SMEAR -> OpLabel.SMEAR;
                case FILL, REPLACE, ERASE, HOLLOW, WALLS, TINKER, OVERLAY, NATURALIZE, UPDATE_BLOCKS -> OpLabel.NONE;
            };
        }
    }

    private final Supplier<ToolContext> context;
    private final Supplier<BlockDescriptor> activeBlock;
    private final Confirmer confirmer;
    private final Translator translator;
    private final LongSupplier seeds;
    private final SymmetryCentre symmetryCentre;
    private final Function<KeyAction, String> keyLabels;
    private UUID lastJob;
    private Op lastOp;

    /**
     * @param context the Select tool's context (settings and selection)
     * @param activeBlock the editor's active block
     * @param seeds seeds for palette patterns
     */
    public SelectionActions(Supplier<ToolContext> context, Supplier<BlockDescriptor> activeBlock, Confirmer confirmer,
            Translator translator, LongSupplier seeds) {
        this(context, activeBlock, confirmer, translator, seeds, new SymmetryCentre(), KeyAction::id);
    }

    /**
     * @param symmetryCentre the symmetry centre the ops use, shared with the brushes and the Place tool
     * @param keyLabels the label of a key, for toasts naming the symmetry centre key
     */
    public SelectionActions(Supplier<ToolContext> context, Supplier<BlockDescriptor> activeBlock, Confirmer confirmer,
            Translator translator, LongSupplier seeds, SymmetryCentre symmetryCentre, Function<KeyAction, String> keyLabels) {
        this.context = Objects.requireNonNull(context);
        this.activeBlock = Objects.requireNonNull(activeBlock);
        this.confirmer = Objects.requireNonNull(confirmer);
        this.translator = Objects.requireNonNull(translator);
        this.seeds = Objects.requireNonNull(seeds);
        this.symmetryCentre = Objects.requireNonNull(symmetryCentre);
        this.keyLabels = Objects.requireNonNull(keyLabels);
    }

    /** The selection's bounds. */
    public Optional<Box> selection() {
        return context.get().selection();
    }

    /** The selection: a box, a shape or a cell set (applies a pending move of a cell set). */
    public Optional<Region> selectionRegion() {
        return context.get().selectionRegion();
    }

    /** The selection as held, with its cheap bounds and kind. */
    public Optional<Selection> selectionState() {
        return context.get().selectionState();
    }

    /** Where exact cell counts come from (the Selection window's readout). */
    public RegionWork regionWork() {
        return context.get().regionWork();
    }

    /** The symmetry centre the ops use (shared with the brushes and the Place tool). */
    public SymmetryCentre symmetryCentre() {
        return symmetryCentre;
    }

    /** The label of a key, for hints and toasts. */
    public String keyLabel(KeyAction action) {
        return keyLabels.apply(action);
    }

    /** The job of the last op sent and accepted, if any. */
    public Optional<UUID> lastJob() {
        return Optional.ofNullable(lastJob);
    }

    public Optional<Op> lastOp() {
        return Optional.ofNullable(lastOp);
    }

    // ---- Ops ----

    /** Fills the selection with the pattern, into the blocks the Into setting allows ({@link #intoMask}). */
    public boolean fill() {
        return run(Op.FILL, region -> pattern().map(pattern -> new OpSpec.Fill(region, pattern,
                intoMask(context.get().settings().get(SelectSettings.INTO), context.get().states()))));
    }

    /**
     * The mask a Fill sends for an Into choice (the server has no fill-into
     * field, the mask says the same): Everything is every cell, Only air the states flagged air (the state space's AIR
     * flag: {@code minecraft:air}, {@code cave_air}, {@code void_air} and any modded air; by block id if the space
     * flags none), Only existing blocks the rest, so fluids and plants count as existing.
     */
    public static CellMask intoMask(PasteOptions.Into into, StateSpace states) {
        if (into == PasteOptions.Into.EVERYTHING) return CellMask.ANY;
        int[] air = new int[states.size()];
        int count = 0;
        // A mask lists at most CellMask.MAX_LIST states; vanilla flags three, so the cap is never reached in practice.
        for (int handle = 0; handle < states.size() && count < CellMask.MAX_LIST; handle++) {
            if (StateFlags.has(states.flags(handle), StateFlags.AIR)) air[count++] = handle;
        }
        CellMask airMask = count > 0 ? new CellMask.States(Arrays.copyOf(air, count))
                : new CellMask.Blocks(List.of(new NamespacedId("minecraft:air"), new NamespacedId("minecraft:cave_air"),
                        new NamespacedId("minecraft:void_air")));
        return into == PasteOptions.Into.AIR ? airMask : new CellMask.Not(airMask);
    }

    /** Replaces every block of {@code from}'s type (any properties) with {@code to}. */
    public boolean replace(BlockDescriptor from, BlockDescriptor to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        return run(Op.REPLACE, region -> resolve(to).map(handle ->
                new OpSpec.Replace(region, new CellMask.Blocks(List.of(from.block())), new Pattern.Single(handle))));
    }

    /**
     * Better Replace: every cell whose block is in {@code from}
     * (blocks, tags or exact states) becomes {@code to}, or with {@code palette} the Select tool's palette laid out by its
     * Pattern setting; with {@code keepShape} each cell keeps the properties it shares with what it becomes (a stair its
     * facing and half, a slab top or bottom, waterlogged), and the block entity where the new block can hold it.
     */
    public boolean replace(BlockSet from, BlockDescriptor to, boolean palette, boolean keepShape) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        return run(Op.REPLACE, region -> (palette ? palettePattern() : resolve(to).map(Pattern.Single::new))
                .map(pattern -> new OpSpec.Replace(region, from.toCellMask(context.get().states()),
                        keepShape ? new Pattern.KeepShape(pattern) : pattern)));
    }

    /**
     * Better Replace's Whole family: every block of {@code from}'s family becomes its counterpart in {@code to}'s
     * ({@link BlockFamilies#swaps}; oak planks to spruce planks turns oak stairs into spruce stairs, oak logs into
     * spruce logs...), always keeping each cell's shape (a door's top half stays a top half, a bed's head a head).
     * Refused with a toast when the two share no family blocks to swap.
     */
    public boolean replaceFamily(BlockDescriptor from, BlockDescriptor to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        return run(Op.REPLACE, region -> {
            List<Pattern.BlockSwap> swaps = BlockFamilies.swaps(from.block(), to.block(), context.get().states());
            if (swaps.isEmpty()) {
                notify(Notice.Level.INFO, "sculptory.notice.no_family", from.block().value(), to.block().value());
                return Optional.empty();
            }
            List<NamespacedId> blocks = swaps.stream().map(Pattern.BlockSwap::from).toList();
            return Optional.of(new OpSpec.Replace(region, new CellMask.Blocks(blocks), new Pattern.Remap(swaps, true)));
        });
    }

    /** The swaps {@link #replaceFamily} would send (the Replace dialog's preview line); empty when there are none. */
    public List<Pattern.BlockSwap> familySwaps(BlockDescriptor from, BlockDescriptor to) {
        return BlockFamilies.swaps(from.block(), to.block(), context.get().states());
    }

    /**
     * Overlay: lays the Fill pattern (the active block, or the palette in the Select settings) on top of the highest
     * block of every column of the selection, {@code depth} (1-16) deep. Refused with a toast outside that range.
     */
    public boolean overlay(int depth) {
        if (!depthOk(depth, 1)) return false;
        return run(Op.OVERLAY, region -> pattern().map(pattern -> new OpSpec.Overlay(region, pattern, depth)));
    }

    /**
     * Naturalize: from the highest block of every column down, {@code topDepth} (1-16) of {@code top}, then
     * {@code middleDepth} (0-16) of {@code middle}, then {@code bottom}, on the ground only.
     */
    public boolean naturalize(BlockDescriptor top, int topDepth, BlockDescriptor middle, int middleDepth,
                              BlockDescriptor bottom) {
        if (!depthOk(topDepth, 1) || !depthOk(middleDepth, 0)) return false;
        return run(Op.NATURALIZE, region -> resolve(top).flatMap(t -> resolve(middle).flatMap(m -> resolve(bottom).map(b ->
                new OpSpec.Naturalize(region, new Pattern.Single(t), topDepth, new Pattern.Single(m), middleDepth,
                        new Pattern.Single(b))))));
    }

    /** Update blocks: re-runs the shape updates of every block in the selection (no physics) and fixes its light. */
    public boolean updateBlocks() {
        return run(Op.UPDATE_BLOCKS, region -> Optional.of(new OpSpec.UpdateBlocks(region)));
    }

    private boolean depthOk(int depth, int min) {
        if (depth >= min && depth <= OpSpec.MAX_LAYER_DEPTH) return true;
        notify(Notice.Level.WARNING, "sculptory.notice.layer_depth", Integer.toString(min),
                Integer.toString(OpSpec.MAX_LAYER_DEPTH));
        return false;
    }

    public boolean erase() {
        return run(Op.ERASE, region -> Optional.of(new OpSpec.Erase(region, CellMask.ANY)));
    }

    /**
     * Keeps a shell of the configured thickness and clears the inside. A box must be thicker than two walls; a shape
     * or cell set too thin to have an inside simply keeps everything (the server clears nothing).
     */
    public boolean hollow() {
        int thickness = thickness();
        Optional<Selection> selected = selectionState();
        if (selected.isPresent() && selected.get().base() instanceof Region.Cuboid(Box box)
                && Math.min(box.sizeX(), Math.min(box.sizeY(), box.sizeZ())) <= 2 * thickness) {
            notify(Notice.Level.INFO, "sculptory.notice.too_thin_to_hollow", Integer.toString(thickness));
            return false;
        }
        return run(Op.HOLLOW, region -> Optional.of(
                new OpSpec.Hollow(region, thickness, new Pattern.Single(context.get().states().air()))));
    }

    /** Fills the sides (a box's four walls; the horizontal edge of a shape or cell set) with the configured thickness. */
    public boolean walls() {
        int thickness = thickness();
        return run(Op.WALLS, region -> pattern().map(pattern -> new OpSpec.Walls(region, thickness, pattern)));
    }

    public void deselect() {
        context.get().setSelectionRegion(null);
    }

    /**
     * Moves the selection one step (or {@code step} blocks) in a camera-relative direction.
     * Returns false for non-nudge actions or when nothing is selected.
     */
    public boolean nudge(EditorAction action, int step, float cameraYaw) {
        Optional<int[]> direction = Nudge.direction(action, cameraYaw);
        if (direction.isEmpty() || selectionState().isEmpty()) {
            return false;
        }
        int[] d = direction.get();
        context.get().moveSelection(d[0] * step, d[1] * step, d[2] * step);
        return true;
    }

    /**
     * Gives a box or shape selection the shape the Select settings choose now (the same bounds); a cell set stays as
     * it is. Returns whether the selection changed.
     */
    public boolean reshape() {
        Optional<Selection> state = selectionState();
        if (state.isEmpty() || !state.get().resizable()) {
            return false;
        }
        Region shaped = SelectSettings.region(state.get().bounds(), context.get().settings());
        if (shaped.equals(state.get().region())) {
            return false;
        }
        context.get().setSelectionRegion(shaped);
        return true;
    }

    /** Selects the selection's whole bounding box instead of its shape or cells. */
    public boolean convertToBox() {
        Optional<Selection> state = selectionState();
        if (state.isEmpty() || state.get().base() instanceof Region.Cuboid) {
            return false;
        }
        context.get().setSelectionRegion(new Region.Cuboid(state.get().bounds()));
        return true;
    }

    // ---- Symmetry ----

    /** The symmetry mode the Select settings choose. */
    public Symmetry.Mode symmetryMode() {
        return context.get().settings().get(SelectSettings.SYMMETRY);
    }

    /**
     * The symmetry the ops run with now: {@link Symmetry#NONE} with the mode off, the mode around the set centre, or
     * empty when the mode is on and no centre is set (the op is refused with a toast).
     */
    public Optional<Symmetry> symmetry() {
        return symmetryCentre.forOp(symmetryMode());
    }

    /** The symmetry {@code mode} runs with, toasting "Set the symmetry centre first (M)" to {@code ctx} when it can't. */
    private Optional<Symmetry> symmetryOrExplain(ToolContext ctx, Symmetry.Mode mode) {
        Optional<Symmetry> symmetry = symmetryCentre.forOp(mode);
        if (symmetry.isEmpty()) {
            notify(ctx, Notice.Level.WARNING, SYMMETRY_CENTRE_NEEDED, keyLabels.apply(KeyAction.SET_SYMMETRY_CENTRE));
        }
        return symmetry;
    }

    // ---- Running ----

    /**
     * Checks and sends an op over the selection. The exact cell count decides the limit, the confirmation and whether
     * a shape is empty; a large shape is counted off the client thread first (a toast says so), and the op goes on
     * once the count is in, if the selection is still the same. Sizes count every symmetric copy.
     */
    private boolean run(Op op, Function<Region, Optional<OpSpec>> build) {
        return run(op, build, true);
    }

    /**
     * Tinker's "Apply to all like it in the selection": a Fill of the selection with
     * the property pattern, setting {@code property} to {@code template}'s value on every block of {@code template}'s
     * type in it (the other blocks are left alone). The usual checks, size limit and confirmation apply, counted on the
     * selection's cells; the Select tool's symmetry does not (the change is meant for the selection itself).
     */
    public boolean applyProperty(int template, String property) {
        return run(Op.TINKER, region -> Optional.of(new OpSpec.Fill(region, new Pattern.SetProperty(template, property),
                CellMask.ANY)), false);
    }

    /** {@link #run(Op, Function)}; without {@code symmetric} the Select tool's symmetry mode is not applied. */
    private boolean run(Op op, Function<Region, Optional<OpSpec>> build, boolean symmetric) {
        ToolContext ctx = context.get();
        Optional<Selection> state = ctx.selectionState();
        if (state.isEmpty()) {
            notify(Notice.Level.INFO, "sculptory.notice.select_first");
            return false;
        }
        Permissions permissions = ctx.session().permissions();
        if (!permissions.has(Perm.REGION)) {
            notify(Notice.Level.WARNING, "sculptory.notice.needs_permission", Perm.REGION.node());
            return false;
        }
        Optional<Symmetry> symmetry = symmetric ? symmetryOrExplain(ctx, symmetryMode()) : Optional.of(Symmetry.NONE);
        if (symmetry.isEmpty()) {
            return false;
        }
        Region region = state.get().region();
        if (!RegionWork.countable(region)) {
            notify(Notice.Level.WARNING, "sculptory.notice.too_large", SelectionModel.count(RegionWork.atMost(region)),
                    SelectionModel.count(permissions.limits().maxOpVolume()));
            return false;
        }
        int copies = copies(region, symmetry.get());
        OptionalLong cells = ctx.regionWork().countNow(region);
        if (cells.isPresent() || saturatingTimes(RegionWork.atMost(region), copies)
                <= Math.min(opLimit(permissions), CONFIRM_VOLUME)) {
            return runCounted(ctx, op, build, region, symmetry.get(), cells);
        }
        Selection counted = state.get();
        notify(Notice.Level.INFO, "sculptory.notice.counting");
        ctx.regionWork().count(region).thenAccept(count -> {
            if (context.get().selectionState().orElse(null) == counted) {
                runCounted(context.get(), op, build, region, symmetry.get(), OptionalLong.of(count));
            }
        });
        return true;
    }

    /** The rest of {@link #run}: {@code cells} is exact, or empty when the bounds alone are under every threshold. */
    private boolean runCounted(ToolContext ctx, Op op, Function<Region, Optional<OpSpec>> build, Region region,
                               Symmetry symmetry, OptionalLong cells) {
        if (cells.isPresent() && cells.getAsLong() == 0) {
            notify(ctx, Notice.Level.INFO, "sculptory.notice.empty_shape");
            return false;
        }
        long blocks = cells.orElse(RegionWork.atMost(region));
        return dispatch(ctx, op, region, blocks, symmetry, () -> build.apply(region));
    }

    /**
     * Checks and sends an op another tool built over {@code region} (the Extrude tool's stacks, erases and moves),
     * with the checks the selection ops share: the {@code region} permission, the symmetry mode around the shared
     * centre (refused with a toast when the mode is on and no centre is set), the server's volume limit and the
     * confirmation over {@link #CONFIRM_VOLUME}, both counting {@code blocks} (what one copy writes: a stack's cells
     * times its count) times the copies. Toasts go to {@code ctx}. Returns whether the op was sent or handed to the
     * confirm dialog.
     *
     * @param build the op without symmetry; empty (after its own toast) to give up
     */
    public boolean runOn(ToolContext ctx, Op op, Region region, long blocks, Symmetry.Mode mode,
                         Supplier<Optional<OpSpec>> build) {
        Objects.requireNonNull(ctx);
        Objects.requireNonNull(op);
        Objects.requireNonNull(region);
        Objects.requireNonNull(mode);
        Objects.requireNonNull(build);
        if (blocks < 1) throw new IllegalArgumentException("An op writes at least one block: " + blocks);
        if (!ctx.session().permissions().has(Perm.REGION)) {
            notify(ctx, Notice.Level.WARNING, "sculptory.notice.needs_permission", Perm.REGION.node());
            return false;
        }
        Optional<Symmetry> symmetry = symmetryOrExplain(ctx, mode);
        if (symmetry.isEmpty()) {
            return false;
        }
        return dispatch(ctx, op, region, blocks, symmetry.get(), build);
    }

    /** The limit check, the confirmation and the sending: {@code blocks} is what one copy writes. */
    private boolean dispatch(ToolContext ctx, Op op, Region region, long blocks, Symmetry symmetry,
                             Supplier<Optional<OpSpec>> build) {
        Permissions permissions = ctx.session().permissions();
        int copies = copies(region, symmetry);
        long volume = saturatingTimes(blocks, copies);
        if (volume > opLimit(permissions)) {
            notify(ctx, Notice.Level.WARNING, "sculptory.notice.too_large",
                    SelectionModel.count(volume), SelectionModel.count(permissions.limits().maxOpVolume()));
            return false;
        }
        Optional<OpSpec> spec = build.get().map(built -> OpSymmetry.withSymmetry(built, symmetry));
        if (spec.isEmpty()) {
            return false;
        }
        if (volume > CONFIRM_VOLUME) {
            String message = copies > 1
                    ? translator.translate("sculptory.confirm.large_op_copies", translator.translate(op.nameKey()),
                            SelectionModel.count(volume), Integer.toString(copies))
                    : translator.translate("sculptory.confirm.large_op", translator.translate(op.nameKey()),
                            SelectionModel.count(volume));
            confirmer.confirm(message, () -> send(ctx, op, spec.get()));
            return true;
        }
        send(ctx, op, spec.get());
        return true;
    }

    /** How many copies an op on {@code region} makes under {@code symmetry} (1 without one). */
    public static int copies(Region region, Symmetry symmetry) {
        if (symmetry.isOff()) return 1;
        return OpSymmetry.copyCount(new OpSpec.Erase(region, CellMask.ANY, symmetry));
    }

    private static long opLimit(Permissions permissions) {
        return permissions.has(Perm.LIMIT_BYPASS) ? Long.MAX_VALUE : permissions.limits().maxOpVolume();
    }

    private static long saturatingTimes(long cells, long count) {
        return cells > Long.MAX_VALUE / Math.max(1, count) ? Long.MAX_VALUE : cells * count;
    }

    private void send(ToolContext ctx, Op op, OpSpec spec) {
        ctx.session().send(new ToolAction.RunOp(spec, false, op.label())).thenAccept(result -> {
            switch (result) {
                case ToolResult.Accepted accepted -> {
                    lastJob = accepted.jobId();
                    lastOp = op;
                }
                case ToolResult.Rejected rejected -> ctx.notify(SessionNotices.rejection(
                        rejected.reason(), SessionNotices.Subject.EDIT, ctx.session().permissions().limits()));
                case ToolResult.Done done -> {
                }
            }
        });
    }

    // ---- Patterns ----

    private int thickness() {
        return context.get().settings().get(SelectSettings.THICKNESS);
    }

    /** What Fill and Walls write, from the Select tool's settings. Empty (after a toast) if a block is unknown. */
    Optional<Pattern> pattern() {
        SettingsValues settings = context.get().settings();
        if (settings.get(SelectSettings.FILL_WITH) == SelectSettings.FillWith.ACTIVE_BLOCK) {
            return resolve(activeBlock.get()).map(Pattern.Single::new);
        }
        return palettePattern();
    }

    /**
     * The Select tool's palette as a pattern, laid out by its Pattern setting (Replace's "To: Palette" uses it whatever
     * Fill with says). Empty (after a toast) if a block is unknown or the palette is empty.
     */
    Optional<Pattern> palettePattern() {
        SettingsValues settings = context.get().settings();
        Map<Integer, Integer> weights = new LinkedHashMap<>();
        for (SettingDef.WeightedBlock entry : settings.get(SelectSettings.PALETTE)) {
            Optional<Integer> handle = resolve(entry.block());
            if (handle.isEmpty()) {
                return Optional.empty();
            }
            weights.merge(handle.get(), entry.weight(), (a, b) -> Math.min(Pattern.Weighted.MAX_WEIGHT, a + b));
        }
        if (weights.isEmpty()) {
            notify(Notice.Level.INFO, "sculptory.notice.palette_empty");
            return Optional.empty();
        }
        if (weights.size() == 1) {
            return Optional.of(new Pattern.Single(weights.keySet().iterator().next()));
        }
        int[] states = weights.keySet().stream().mapToInt(Integer::intValue).toArray();
        int[] counts = weights.values().stream().mapToInt(Integer::intValue).toArray();
        // Laid out by the Pattern setting (Random: this Fill's own seed, as before; a Gradient needs its line).
        return Optional.ofNullable(SelectSettings.PATTERN.pattern(context.get(), settings,
                new Pattern.Weighted(states, counts, seeds.getAsLong()), symmetryCentre.gradientLine()));
    }

    /** The Gradient pattern's line, shared with the brushes. */
    public GradientLine gradientLine() {
        return symmetryCentre.gradientLine();
    }

    private Optional<Integer> resolve(BlockDescriptor block) {
        int handle = context.get().states().resolve(block);
        if (handle < 0) {
            notify(Notice.Level.WARNING, "sculptory.notice.unknown_block", block.format());
            return Optional.empty();
        }
        return Optional.of(handle);
    }

    /** Shows an information toast (the Selection window's dialogs). */
    public void notice(String key, String... args) {
        notify(Notice.Level.INFO, key, args);
    }

    private void notify(Notice.Level level, String key, String... args) {
        notify(context.get(), level, key, args);
    }

    private static void notify(ToolContext ctx, Notice.Level level, String key, String... args) {
        ctx.notify(Notice.of(level, key, args));
    }
}
