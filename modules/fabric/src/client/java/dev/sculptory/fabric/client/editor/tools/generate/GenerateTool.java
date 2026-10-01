package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.generate.GroundMap;
import dev.sculptory.core.generate.LineKernel;
import dev.sculptory.core.generate.RoadKernel;
import dev.sculptory.core.generate.RoofKernel;
import dev.sculptory.core.generate.RoofStates;
import dev.sculptory.core.generate.StairShapes;
import dev.sculptory.core.generate.SurfaceReader;
import dev.sculptory.core.generate.UnknownMaterialException;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMasking;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Generate tool (palette slot 11, key -): roads along a
 * path of clicked nodes, roofs over the selection and lines through clicked points, generated on this client by the
 * {@code core.generate} kernels, previewed as ghosts and built through one sparse clipboard upload followed by an
 * ordinary paste (one job, one undo step; {@link GeneratedCommit}).
 *
 * <p><b>Path.</b> A click on the ground adds a node at the hit block. A click on a node's column selects it; a drag
 * moves it. Delete or Backspace removes the selected node, or the last one. Esc first stops a drag or a preview being
 * made, then clears the path. From two nodes on the road's ghost follows the nodes and every setting; Enter builds it.
 * The nodes stay, so the road can be adjusted and built again. Ctrl+Scroll changes the width (Shift ×4).
 *
 * <p><b>Roof.</b> The selection's bounds are the footprint: its bottom layer is the eaves level, its height is
 * ignored. The ghost follows the selection and every setting; Enter builds it.
 *
 * <p><b>Line</b> ({@link LineDraft}). Clicked blocks are its points,
 * picked, dragged and removed as the road's nodes; the path runs Straight, Curve or Hanging through them, 1-16 blocks
 * thick, round or square, of a block or a mix ({@link LineKernel}). Ctrl+Scroll changes the thickness; Enter builds it
 * (the paste is named "Line").
 *
 * <p><b>Previews</b> run on the background executor: the Path generator first snapshots the ground of the footprint's
 * columns from the client world on the client thread ({@link GroundMap}), the kernel and the ghost bake then run off
 * it; a result of an older change is dropped. A source over {@link #MAX_GHOST_CELLS} shows its bounds as an outline;
 * unloaded columns are outlined per chunk and refuse the build. Client thread only.
 */
public final class GenerateTool implements Tool {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    public static final String CLICK = "LMB";
    public static final int PATH_COLOUR = 0xFFFFC857;
    public static final int NODE_COLOUR = 0xFFFFE8A8;
    public static final int SELECTED_COLOUR = 0xFF7FD4FF;
    public static final int UNLOADED_COLOUR = 0xFFFF4A4A;
    /** Sources over this many cells are outlined instead of drawn as ghosts (the Place tool's capture cap). */
    public static final long MAX_GHOST_CELLS = PlaceTool.DEFAULT_MAX_CAPTURE_CELLS;
    /** The roof ghost's outline (and a road's bounds when it has no footprint): the path colour, a little dimmer. */
    public static final int OUTLINE_COLOUR = OverlayColors.scaleAlpha(PATH_COLOUR, 0.8);
    /** The most cells a preview holds for a player with {@code limit.bypass} (memory, not policy). */
    static final long BYPASS_PREVIEW_CELLS = 1L << 24;
    /** The seed of a weighted material mix: fixed, so rebuilding the same road gives the same mix. */
    static final long MIX_SEED = 0x5EEDL;

    public static final String NEEDS_NODES = "sculptory.notice.generate_needs_nodes";
    public static final String NEEDS_SELECTION = "sculptory.notice.generate_needs_selection";
    public static final String UNLOADED = "sculptory.notice.generate_unloaded";
    public static final String NOTHING = "sculptory.notice.generate_nothing";
    /** Hint while a node building needs is missing (the node as its argument). */
    public static final String NEEDS_PERMISSION_HINT = "sculptory.hint.generate.needs_permission";
    public static final String BLOCK_ENTITY = "sculptory.notice.generate_block_entity";
    public static final String SENT_PATH = "sculptory.notice.generate_path_sent";
    public static final String SENT_ROOF = "sculptory.notice.generate_roof_sent";
    public static final String OP_PATH = "sculptory.op.generate_path";
    public static final String OP_ROOF = "sculptory.op.generate_roof";
    public static final String OP_LINE = "sculptory.op.generate_line";
    public static final String SENT_LINE = "sculptory.notice.generate_line_sent";

    /** What the tool needs from the client. */
    public interface Services {
        /** Shows these ghost previews from now on (an empty list shows none). */
        void showGhosts(List<GhostPlacement> placements);

        /** Frees a volume's meshes now (a volume no longer shown). */
        void releaseGhost(GhostVolume volume);

        /** Where kernels, ghost bakes and payload encoding run, off the client thread. */
        Executor background();

        String keyLabel(KeyAction action);

        /** Asks the player to confirm a large build; {@code onConfirm} runs only if they do. */
        void confirm(String opNameKey, long blocks, Runnable onConfirm);

        /** Services without Minecraft: no ghosts, default key names, work on the caller's thread, confirms at once. */
        static Services headless() {
            return new Services() {
                @Override
                public void showGhosts(List<GhostPlacement> placements) {}

                @Override
                public void releaseGhost(GhostVolume volume) {}

                @Override
                public Executor background() {
                    return Runnable::run;
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return dev.sculptory.fabric.client.editor.input.EditorKeymap.defaults().display(action);
                }

                @Override
                public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
                    onConfirm.run();
                }
            };
        }
    }

    /**
     * A preview made: the cells, the road's unloaded columns, the ghost (null when over the cap or empty), the road's
     * centre line, ground and footprint (null for a roof, or a road over {@link RoadFootprint#MAX_COLUMNS} columns), or
     * {@code tooLarge} when the kernel passed the cap.
     */
    public record Built(GeneratedSource source, List<RoadKernel.Column> unloaded, GhostVolume ghost, RoadKernel.Path path,
                        GroundMap ground, RoadFootprint footprint, boolean tooLarge, long cap) {
        public Built {
            Objects.requireNonNull(source);
            unloaded = List.copyOf(unloaded);
        }

        static Built tooLarge(RoadKernel.Path path, GroundMap ground, long cap) {
            return new Built(GeneratedSource.empty(), List.of(), null, path, ground, null, true, cap);
        }

        public long cells() {
            return source.cells();
        }

        /** Whether it can be built as it is. */
        public boolean buildable() {
            return !tooLarge && unloaded.isEmpty() && !source.isEmpty();
        }
    }

    private final Services services;
    /**
     * Greyed out in the palette without {@code region}: a generated road or roof may hold any block state, as a Fill
     * (a {@code region} operation) may place it. Building also needs {@code clipboard}, for the upload and its paste
     * ({@link #missingNode}).
     */
    private final ToolDescriptor descriptor = new ToolDescriptor(ToolId.GENERATE, "sculptory.tool.generate",
            "minecraft:oak_stairs", Perm.REGION);
    private final PathModel path = new PathModel();
    private ToolContext context;
    private WorldCursor cursor = WorldCursor.miss(0, 0, 0);
    /** The node being dragged and where it was when the drag began, or -1. */
    private int dragging = -1;
    private BlockPos dragFrom;
    /** The preview needs making again (a node, selection or setting changed). */
    private boolean dirty = true;
    /** Bumped on every change, so a result of an older change is dropped. */
    private int generation;
    private CompletableFuture<Built> running;
    private int runningGeneration;
    /** The latest preview, or null. */
    private Built shown;
    private GhostVolume ghost;
    /** The selection bounds the roof preview was made for. */
    private Box roofFootprint;
    /** Enter came while the preview was being made: build as soon as it lands. */
    private boolean buildWhenReady;
    /** Builds the road's and the roof's sources. */
    private final GeneratedCommit commit;
    /** The Line's points, preview and build. */
    private final LineDraft line;
    /** Where the shown ghost is placed: the source's corner, or the masked cells'. */
    private BlockPos ghostOrigin;
    /** The kind the last frame ran with: a change drops the other kind's preview. */
    private GenerateSettings.Kind lastKind;

    public GenerateTool(Services services) {
        this.services = Objects.requireNonNull(services);
        this.commit = new GeneratedCommit(services);
        this.line = new LineDraft(() -> services, PATH_COLOUR);
    }

    /** The path's nodes (for tests and the HUD). */
    public PathModel path() {
        return path;
    }

    /** The Line's points and preview (for tests and the HUD). */
    public LineDraft line() {
        return line;
    }

    /** The latest preview, if any. */
    public Optional<Built> preview() {
        return Optional.ofNullable(shown);
    }

    /** Whether a preview is being made. */
    public boolean previewRunning() {
        return running != null;
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SettingsSchema schema() {
        return GenerateSettings.SCHEMA;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.TERRAIN;
    }

    @Override
    public void activate(ToolContext c) {
        context = c;
        dirty = true;
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        endDrag(c);
        dropPreview();
        commit.cancel();
        line.deactivate(c);
        context = null;
    }

    // ---- Input ----

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        cursor = e.cursor();
        GenerateSettings.Kind kind = c.settings().get(GenerateSettings.KIND);
        if (kind == GenerateSettings.Kind.LINE) return line.onPointer(c, e, WorldCursor::pos);
        if (kind != GenerateSettings.Kind.PATH) return false;
        switch (e.kind()) {
            case PRESS -> {
                if (e.button() != PointerEvent.LEFT || cursor.missed()) return false;
                BlockPos hit = cursor.pos();
                int index = path.indexAt(hit);
                if (index >= 0) {
                    path.select(index);
                    dragging = index;
                    dragFrom = path.node(index);
                    c.setPointerCapture(true);
                } else {
                    path.add(hit);
                    changed();
                }
                return true;
            }
            case DRAG -> {
                if (e.button() != PointerEvent.LEFT || dragging < 0 || cursor.missed()) return false;
                if (path.move(dragging, cursor.pos())) changed();
                return true;
            }
            case RELEASE -> {
                if (e.button() != PointerEvent.LEFT || dragging < 0) return false;
                if (!cursor.missed() && path.move(dragging, cursor.pos())) changed();
                endDrag(c);
                return true;
            }
            case MOVE -> {
                return false;
            }
        }
        return false;
    }

    @Override
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        GenerateSettings.Kind kind = c.settings().get(GenerateSettings.KIND);
        if (e.amount() == 0 || kind == GenerateSettings.Kind.ROOF) return false;
        if (!Modifiers.control(e.modifiers())) return false;
        int step = (e.amount() > 0 ? 1 : -1) * (Modifiers.shift(e.modifiers()) ? 4 : 1);
        SettingsValues values = c.settings();
        if (kind == GenerateSettings.Kind.LINE) {
            int thickness = Math.max(LineKernel.MIN_THICKNESS, Math.min(LineKernel.MAX_THICKNESS,
                    values.get(GenerateSettings.LINE_THICKNESS) + step));
            c.updateSettings(values.with(GenerateSettings.LINE_THICKNESS, thickness));
            return true;
        }
        int width = Math.max(RoadKernel.MIN_WIDTH, Math.min(RoadKernel.MAX_WIDTH, values.get(GenerateSettings.WIDTH) + step));
        c.updateSettings(values.with(GenerateSettings.WIDTH, width));
        return true;
    }

    @Override
    public boolean onAction(ToolContext c, EditorAction a) {
        if (c.settings().get(GenerateSettings.KIND) == GenerateSettings.Kind.LINE) return line.onAction(c, a, this::lineSetup);
        boolean pathMode = c.settings().get(GenerateSettings.KIND) == GenerateSettings.Kind.PATH;
        switch (a) {
            case CANCEL -> {
                if (dragging >= 0) {
                    path.move(dragging, dragFrom);
                    endDrag(c);
                    changed();
                    return true;
                }
                if (running != null) {
                    generation++;
                    running = null;
                    dirty = false;
                    buildWhenReady = false;
                    return true;
                }
                if (pathMode && !path.isEmpty()) {
                    path.clear();
                    changed();
                    return true;
                }
                return false;
            }
            case ERASE_SELECTION, REMOVE_NODE -> {
                if (!pathMode || path.isEmpty()) return false;
                if (dragging >= 0) endDrag(c);
                path.removeSelectedOrLast();
                changed();
                return true;
            }
            case COMMIT -> {
                build(c);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public void onSettingsChanged(ToolContext c, SettingsValues before, SettingsValues after) {
        if (!before.get(GenerateSettings.STAIRS).equals(after.get(GenerateSettings.STAIRS))) {
            StateSpace states = statesOrNull(c);
            if (states != null) {
                RoofMaterials.Derived derived = RoofMaterials.derive(states, after.get(GenerateSettings.STAIRS));
                SettingsValues filled = after;
                if (derived.slab().isPresent()) filled = filled.with(GenerateSettings.SLAB, derived.slab().get());
                if (derived.full().isPresent()) filled = filled.with(GenerateSettings.FULL, derived.full().get());
                if (!filled.equals(after)) {
                    c.updateSettings(filled);
                    return; // the update comes back through here
                }
            }
        }
        changed();
        line.changed();
    }

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        cursor = f.cursor();
        GenerateSettings.Kind kind = c.settings().get(GenerateSettings.KIND);
        if (kind != lastKind) {
            // The Line shows its own ghost: the road's or roof's goes (and comes back when its kind does), and back.
            if (lastKind == GenerateSettings.Kind.LINE) line.hide(c);
            else if (kind == GenerateSettings.Kind.LINE && lastKind != null) dropPreview();
            if (dragging >= 0) endDrag(c);
            dirty = true;
            lastKind = kind;
        }
        if (kind == GenerateSettings.Kind.LINE) {
            line.frame(c, this::lineSetup);
            commit.poll(c);
            return;
        }
        if (kind == GenerateSettings.Kind.ROOF) {
            Box footprint = c.selection().orElse(null);
            if (!Objects.equals(footprint, roofFootprint)) {
                roofFootprint = footprint;
                changed();
            }
        }
        pollPreview();
        if (dirty && running == null) {
            start(c);
            pollPreview(); // an executor that runs at once (tests, tiny work) has the result already
        }
        commit.poll(c);
        line.pollBuild(c);
    }

    // ---- Previews ----

    private void changed() {
        dirty = true;
        generation++;
    }

    private void endDrag(ToolContext c) {
        if (dragging >= 0) c.setPointerCapture(false);
        dragging = -1;
        dragFrom = null;
    }

    /** Takes a finished preview, if it is still the one wanted. */
    private void pollPreview() {
        if (running == null || !running.isDone()) return;
        CompletableFuture<Built> done = running;
        running = null;
        Built built;
        try {
            built = done.getNow(null);
        } catch (CompletionException | CancellationException e) {
            LOG.warn("Sculptory: generating a preview failed", e.getCause() != null ? e.getCause() : e);
            built = null;
        }
        if (built == null || runningGeneration != generation) {
            if (built != null && built.ghost() != null) services.releaseGhost(built.ghost());
            return;
        }
        show(built);
    }

    private void show(Built built) {
        built = masked(built);
        if (ghost != null && ghost != built.ghost()) services.releaseGhost(ghost);
        ghost = built.ghost();
        shown = built;
        if (buildWhenReady && context != null) {
            buildWhenReady = false;
            build(context);
        }
        if (ghost != null) {
            BlockPos min = ghostOrigin;
            services.showGhosts(List.of(GhostPlacement.of(ghost, min.x(), min.y(), min.z())));
        } else {
            services.showGhosts(List.of());
        }
    }

    /**
     * {@code built} with its ghost showing only what the global mask lets through (the mask is judged here, on the
     * client thread): the same preview while the mask leaves every cell in, else one whose ghost is baked again.
     */
    private Built masked(Built built) {
        ghostOrigin = built.source().isEmpty() ? null : built.source().bounds().min();
        if (built.ghost() == null || context == null) return built;
        WorldReader world = worldOrNull(context);
        StateSpace states = statesOrNull(context);
        if (world == null || states == null) return built;
        GeneratedSource visible = GhostMasking.filter(built.source(), world);
        if (visible == built.source()) return built;
        services.releaseGhost(built.ghost());
        GhostVolume baked = visible.isEmpty() ? null : GhostBaker.fromSparse(visible, states);
        ghostOrigin = visible.isEmpty() ? null : visible.bounds().min();
        return new Built(built.source(), built.unloaded(), baked, built.path(), built.ground(), built.footprint(),
                built.tooLarge(), built.cap());
    }

    private void dropPreview() {
        generation++;
        running = null;
        buildWhenReady = false;
        if (ghost != null) services.releaseGhost(ghost);
        ghost = null;
        shown = null;
        services.showGhosts(List.of());
    }

    /** Starts making the preview for the settings, nodes and selection as they are now. */
    private void start(ToolContext c) {
        dirty = false;
        Supplier<Built> task = task(c);
        if (task == null) {
            if (shown != null) dropPreview();
            return;
        }
        runningGeneration = generation;
        running = CompletableFuture.supplyAsync(task, services.background());
    }

    /** The work of a preview, or null when there is nothing to preview (after telling the player why, if a setting). */
    private Supplier<Built> task(ToolContext c) {
        SettingsValues values = c.settings();
        StateSpace states = statesOrNull(c);
        WorldReader world = worldOrNull(c);
        if (states == null || world == null) return null;
        long cap = bypass(c) ? BYPASS_PREVIEW_CELLS : limits(c).maxClipboardVolume();
        int bottom = world.bottomY(), top = world.topYExclusive();
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.PATH) {
            if (path.size() < 2) return null;
            RoadKernel.Spec spec = roadSpec(c, values, states);
            if (spec == null) return null;
            RoadKernel.Path laid = RoadKernel.path(spec);
            GroundMap ground = new GroundMap();
            if (spec.heightMode() == RoadKernel.HeightMode.FOLLOW_TERRAIN) {
                SurfaceReader surface = SurfaceReader.of(world);
                ground.capture(surface, laid.columnKeys());
                ground.capture(surface, laid.sampleColumnKeys());
            }
            return () -> {
                try {
                    RoadKernel.Result result = RoadKernel.generate(spec, laid, ground, bottom, top, cap);
                    return new Built(result.source(), result.unloaded(), ghostOf(result.source(), states), laid, ground,
                            RoadFootprint.of(result.source(), states.air()), false, cap);
                } catch (GeneratedTooLargeException e) {
                    return Built.tooLarge(laid, ground, cap);
                }
            };
        }
        Box footprint = c.selection().orElse(null);
        if (footprint == null) return null;
        RoofKernel.Spec spec = roofSpec(values, footprint, states.air());
        RoofStates roof;
        try {
            roof = RoofStates.resolve(states, GenerateSettings.materials(values));
        } catch (UnknownMaterialException e) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", e.state().format()));
            return null;
        }
        if (!placeable(c, roof.full(), states) || !placeable(c, roof.slab(), states)
                || !placeable(c, roof.stair(dev.sculptory.core.region.Facing.NORTH, StairShapes.Shape.STRAIGHT), states)) {
            return null;
        }
        return () -> {
            try {
                GeneratedSource source = RoofKernel.generate(spec, roof, bottom, top, cap);
                return new Built(source, List.of(), ghostOf(source, states), null, null, null, false, cap);
            } catch (GeneratedTooLargeException e) {
                return Built.tooLarge(null, null, cap);
            }
        };
    }

    private static GhostVolume ghostOf(GeneratedSource source, StateSpace states) {
        if (source.isEmpty() || source.cells() > MAX_GHOST_CELLS) return null;
        return GhostBaker.fromSparse(source, states);
    }

    /** The road spec of the settings, or null (after a toast) when a block can't be used. */
    private RoadKernel.Spec roadSpec(ToolContext c, SettingsValues values, StateSpace states) {
        Pattern material;
        if (values.get(GenerateSettings.MATERIAL) == GenerateSettings.Material.PALETTE) {
            material = mix(c, values.get(GenerateSettings.PALETTE), states);
        } else {
            material = single(c, values.get(GenerateSettings.BLOCK), states);
        }
        if (material == null) return null;
        Pattern border = null;
        if (values.get(GenerateSettings.BORDER)) {
            border = single(c, values.get(GenerateSettings.BORDER_BLOCK), states);
            if (border == null) return null;
        }
        return new RoadKernel.Spec(path.nodes(), values.get(GenerateSettings.WIDTH), material, border,
                values.get(GenerateSettings.HEIGHT), values.get(GenerateSettings.LEVEL_ACROSS),
                values.get(GenerateSettings.FILL_BELOW), values.get(GenerateSettings.CLEAR_ABOVE), states.air());
    }

    static RoofKernel.Spec roofSpec(SettingsValues values, Box footprint, int air) {
        return new RoofKernel.Spec(footprint, values.get(GenerateSettings.STYLE), values.get(GenerateSettings.RIDGE),
                values.get(GenerateSettings.LOW_SIDE), values.get(GenerateSettings.PITCH), values.get(GenerateSettings.OVERHANG),
                values.get(GenerateSettings.THICKNESS), values.get(GenerateSettings.GABLE_WALLS),
                values.get(GenerateSettings.INSIDE), air);
    }

    private static Pattern single(ToolContext c, BlockDescriptor block, StateSpace states) {
        int state = states.resolve(block);
        if (state < 0) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", block.format()));
            return null;
        }
        if (!placeable(c, state, states)) return null;
        return new Pattern.Single(state);
    }

    /** Whether a state can be generated: the server refuses block-entity states, so say so before uploading. */
    private static boolean placeable(ToolContext c, int state, StateSpace states) {
        if (!StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) return true;
        c.notify(Notice.of(Notice.Level.WARNING, BLOCK_ENTITY, states.describe(state).format()));
        return false;
    }

    private static Pattern mix(ToolContext c, List<SettingDef.WeightedBlock> entries, StateSpace states) {
        if (entries.isEmpty()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.palette_empty"));
            return null;
        }
        int[] handles = new int[entries.size()];
        int[] weights = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            int state = states.resolve(entries.get(i).block());
            if (state < 0) {
                c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", entries.get(i).block().format()));
                return null;
            }
            if (!placeable(c, state, states)) return null;
            handles[i] = state;
            weights[i] = entries.get(i).weight();
        }
        return new Pattern.Weighted(handles, weights, MIX_SEED);
    }

    /**
     * The Line of {@code points} with the settings as they are now, or null (after a toast) when its block can't be
     * used. It is clipped to the build height; nothing of the world is read.
     */
    private LineDraft.Setup lineSetup(ToolContext c, List<BlockPos> points) {
        SettingsValues values = c.settings();
        StateSpace states = statesOrNull(c);
        if (states == null) return null;
        Pattern material = values.get(GenerateSettings.LINE_MATERIAL) == GenerateSettings.Material.PALETTE
                ? mix(c, values.get(GenerateSettings.LINE_PALETTE), states)
                : single(c, values.get(GenerateSettings.LINE_BLOCK), states);
        if (material == null) return null;
        PathKind kind = values.get(GenerateSettings.LINE_PATH);
        PathSpec path = new PathSpec(points, kind, kind == PathKind.HANGING ? values.get(GenerateSettings.LINE_SAG) : 0);
        LineKernel.Spec spec = new LineKernel.Spec(path, values.get(GenerateSettings.LINE_THICKNESS),
                values.get(GenerateSettings.LINE_PROFILE), material);
        WorldReader world = worldOrNull(c);
        int bottom = world == null ? Integer.MIN_VALUE : world.bottomY();
        int top = world == null ? Integer.MAX_VALUE : world.topYExclusive();
        return new LineDraft.Setup(path, cap -> LineKernel.generate(spec, states, cap, bottom, top), OpLabel.LINE, OP_LINE,
                SENT_LINE, new PasteOptions(true, false, false));
    }

    // ---- Building ----

    /** Enter: builds what the preview shows (making the preview first when it is not current). */
    private void build(ToolContext c) {
        SettingsValues values = c.settings();
        GenerateSettings.Kind kind = values.get(GenerateSettings.KIND);
        if (kind == GenerateSettings.Kind.PATH && path.size() < 2) {
            c.notify(Notice.of(Notice.Level.INFO, NEEDS_NODES));
            return;
        }
        if (kind == GenerateSettings.Kind.ROOF && c.selection().isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, NEEDS_SELECTION));
            return;
        }
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return;
        }
        Optional<Perm> missing = missingNode(session.permissions());
        if (missing.isPresent()) {
            // The server would refuse the upload (it needs both); say which node before anything is made or sent.
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", missing.get().node()));
            return;
        }
        if (dirty || running != null || shown == null) {
            // The preview is not current: build once it lands (pollPreview), without holding the client thread.
            if (dirty || running == null) start(c);
            if (running == null) return;
            pollPreview();
            if (running != null) {
                buildWhenReady = true;
                return;
            }
            if (shown == null) return;
        }
        Built built = shown;
        Limits limits = session.permissions().limits();
        if (built.tooLarge()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", "> " + SessionNotices.count(built.cap()),
                    SessionNotices.count(limits.maxClipboardVolume())));
            return;
        }
        if (!built.unloaded().isEmpty()) {
            c.notify(Notice.of(Notice.Level.WARNING, UNLOADED, Integer.toString(built.unloaded().size())));
            return;
        }
        if (built.cells() == 0) {
            c.notify(Notice.of(Notice.Level.INFO, NOTHING));
            return;
        }
        boolean road = kind == GenerateSettings.Kind.PATH;
        commit.send(c, new GeneratedCommit.Request(built.source(), road ? OpLabel.ROAD : OpLabel.ROOF,
                road ? OP_PATH : OP_ROOF, road ? SENT_PATH : SENT_ROOF, new PasteOptions(true, false, false)));
    }

    // ---- Drawing and hints ----

    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        SettingsValues values = c.settings();
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.LINE) {
            line.renderWorld(d, false);
            return;
        }
        Built built = shown;
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.PATH) {
            List<BlockPos> nodes = path.nodes();
            for (int i = 0; i < nodes.size(); i++) {
                Box box = Box.of(nodes.get(i));
                boolean selected = i == path.selected();
                d.boxOutline(box, selected ? SELECTED_COLOUR : NODE_COLOUR);
                if (selected) d.boxFill(box, OverlayColors.scaleAlpha(SELECTED_COLOUR, 0.25));
            }
            if (built != null && built.path() != null) {
                // The road replaces the ground, so its ghost is mostly inside the terrain: the footprint shows where it
                // goes; the centre line stays on top of it.
                if (built.footprint() != null) built.footprint().draw(d, PATH_COLOUR);
                drawCentreLine(d, built);
                drawUnloaded(d, built);
            }
        }
        if (built == null || built.source().isEmpty()) return;
        boolean roof = built.path() == null;
        if (roof || built.ghost() == null || built.footprint() == null) {
            // A roof stands in the air, where a ghost alone is hard to read against the terrain behind it: its bounds
            // are outlined (faintly through terrain, fully in view). A road over the ghost or footprint cap gets the same.
            outline(d, built.source().bounds(), OUTLINE_COLOUR);
        }
    }

    /** A box's edges twice: faintly through the terrain, then depth-tested, as the cell outlines are drawn. */
    private static void outline(WorldDraw d, Box box, int argb) {
        d.seeThrough(true);
        d.boxOutline(box, OverlayColors.scaleAlpha(argb, 0.35));
        d.seeThrough(false);
        d.boxOutline(box, argb);
    }

    /** The centre line on the terrain: each vertex at its column's ground (the last preview's), else the nodes' height. */
    private static void drawCentreLine(WorldDraw d, Built built) {
        RoadKernel.Path line = built.path();
        double lastX = 0, lastY = 0, lastZ = 0;
        for (int i = 0; i < line.vertexCount(); i++) {
            double x = line.x(i), z = line.z(i);
            int ground = built.ground() == null ? SurfaceReader.UNLOADED
                    : built.ground().ground((int) Math.floor(x), (int) Math.floor(z));
            double y = (SurfaceReader.known(ground) ? ground : line.nodeHeightAt(line.along(i))) + 1.05;
            if (i > 0) d.line(lastX, lastY, lastZ, x, y, z, PATH_COLOUR);
            lastX = x;
            lastY = y;
            lastZ = z;
        }
    }

    /** One outline per chunk holding unloaded footprint columns, at the nodes' height there. */
    private static void drawUnloaded(WorldDraw d, Built built) {
        if (built.unloaded().isEmpty()) return;
        Long2ObjectOpenHashMap<int[]> chunks = new Long2ObjectOpenHashMap<>();
        for (RoadKernel.Column column : built.unloaded()) {
            long key = GroundMap.column(column.x() >> 4, column.z() >> 4);
            int y = (int) Math.floor(built.path().nodeHeightAt(column.along()) + 0.5);
            int[] box = chunks.get(key);
            if (box == null) {
                chunks.put(key, new int[] {column.x(), y, column.z(), column.x(), y, column.z()});
            } else {
                box[0] = Math.min(box[0], column.x());
                box[1] = Math.min(box[1], y);
                box[2] = Math.min(box[2], column.z());
                box[3] = Math.max(box[3], column.x());
                box[4] = Math.max(box[4], y);
                box[5] = Math.max(box[5], column.z());
            }
        }
        d.seeThrough(true);
        for (int[] box : chunks.values()) {
            d.boxOutline(new Box(new BlockPos(box[0], box[1], box[2]), new BlockPos(box[3], box[4], box[5])), UNLOADED_COLOUR);
        }
        d.seeThrough(false);
    }

    /**
     * The first node building needs that the player lacks: {@code region} (a generated clipboard may hold any block
     * state, as a Fill may place it) and {@code clipboard} (the upload and its paste); the server checks both again.
     */
    public static Optional<Perm> missingNode(Permissions permissions) {
        for (Perm node : List.of(Perm.REGION, Perm.CLIPBOARD)) {
            if (!permissions.has(node)) return Optional.of(node);
        }
        return Optional.empty();
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        try {
            missingNode(c.session().permissions())
                    .ifPresent(node -> hints.add(KeyHint.text(NEEDS_PERMISSION_HINT, node.node())));
        } catch (IllegalStateException noSession) {
            // No session: nothing can be built anyway, and the palette says why.
        }
        SettingsValues values = c.settings();
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.LINE) {
            hints.addAll(line.hints(CLICK, "sculptory.hint.line.build"));
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.line.thickness"));
            return hints;
        }
        Built built = shown;
        String enter = services.keyLabel(KeyAction.COMMIT);
        if (values.get(GenerateSettings.KIND) == GenerateSettings.Kind.ROOF) {
            if (c.selection().isEmpty()) {
                hints.add(KeyHint.text("sculptory.hint.generate.roof_needs_selection"));
                return hints;
            }
            hints.add(KeyHint.text("sculptory.hint.generate.roof_eaves"));
            if (built != null && built.tooLarge()) {
                hints.add(KeyHint.text("sculptory.hint.generate.too_large", SessionNotices.count(built.cap())));
            } else if (built != null && !built.source().isEmpty()) {
                hints.add(new KeyHint(enter, "sculptory.hint.generate.build_roof", List.of(SessionNotices.count(built.cells()))));
            }
            return hints;
        }
        if (path.isEmpty()) {
            hints.add(new KeyHint(CLICK, "sculptory.hint.generate.add_node"));
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.generate.width"));
            return hints;
        }
        if (dragging >= 0) {
            hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
            return hints;
        }
        hints.add(new KeyHint(CLICK, "sculptory.hint.generate.add_or_select"));
        hints.add(new KeyHint(CLICK + " drag", "sculptory.hint.generate.move_node"));
        hints.add(new KeyHint(services.keyLabel(KeyAction.ERASE_SELECTION) + "/" + services.keyLabel(KeyAction.REMOVE_NODE),
                "sculptory.hint.generate.remove_node"));
        if (built != null && built.tooLarge()) {
            hints.add(KeyHint.text("sculptory.hint.generate.too_large", SessionNotices.count(built.cap())));
        } else if (built != null && !built.unloaded().isEmpty()) {
            hints.add(KeyHint.text("sculptory.hint.generate.unloaded", Integer.toString(built.unloaded().size())));
        } else if (built != null && !built.source().isEmpty()) {
            hints.add(new KeyHint(enter, "sculptory.hint.generate.build_path", List.of(SessionNotices.count(built.cells()))));
        }
        hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.generate.width"));
        hints.add(new KeyHint("Esc", "sculptory.hint.generate.clear"));
        return hints;
    }

    // ---- Helpers ----

    static StateSpace statesOrNull(ToolContext c) {
        try {
            return c.states();
        } catch (IllegalStateException noSession) {
            return null;
        }
    }

    static WorldReader worldOrNull(ToolContext c) {
        try {
            return c.world();
        } catch (IllegalStateException noSession) {
            return null;
        }
    }

    static Limits limits(ToolContext c) {
        try {
            return c.session().permissions().limits();
        } catch (IllegalStateException noSession) {
            return Limits.DEFAULTS;
        }
    }

    static boolean bypass(ToolContext c) {
        try {
            return c.session().permissions().has(Perm.LIMIT_BYPASS);
        } catch (IllegalStateException noSession) {
            return false;
        }
    }
}
