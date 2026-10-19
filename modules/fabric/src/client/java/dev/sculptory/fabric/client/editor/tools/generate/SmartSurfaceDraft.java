package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.generate.GroundFill;
import dev.sculptory.core.generate.GroundMap;
import dev.sculptory.core.generate.SmartSurfaceKernel;
import dev.sculptory.core.generate.SurfaceReader;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMasking;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeSettings;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.editor.tools.points.PointEditor;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.engine.Perm;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generate's Smart Surface: points on blocks make a closed outline (and inside points), and a terrain-like sheet fills
 * it ({@link SmartSurfaceKernel}), previewed as a ghost with its terrace outlines and built with Enter through
 * {@link GeneratedCommit} as one paste ({@link OpLabel#SMART_SURFACE}, one undo step).
 *
 * <p><b>Points</b> go through the shared {@link PointEditor} on a {@link PathModel#closedOutline()}: a click puts a point
 * on top of the clicked block (or at the sky marker); once the outline has three points a click on terrain inside it
 * adds an inside point, any other click extends the outline after the selected outline point
 * ({@link PointEditor#outlineOrInside}). A click on a point selects it; Backspace or Delete removes the selected point
 * (or the last outline point); Esc drops what the editor holds (a point being inserted, a drag, the selection), then a
 * preview being made, then clears the points.
 *
 * <p><b>Preview.</b> Every change of the points or the settings makes the preview again off the client thread: with
 * Extend to Ground it takes three steps: the surface off the client thread, then on it the ground under each column by
 * the shared rule ({@link GroundFill#groundUnder} from under the surface cell, looking through plants, leaves and trunks;
 * stopped once the fill would pass what the cap leaves, which shows as too large), then the cells off it again. The ghost shows what the global mask lets through and, with Keep Existing, only the
 * cells that are air now. The sheet's terrace outlines (where a column stands above its neighbour or the sheet's edge)
 * are drawn on top; Mesh view also draws the triangles. Inside points outside the outline are drawn red.
 *
 * <p><b>Building</b> needs {@code region} and {@code clipboard}; it is refused (with a toast) for points that make no
 * surface, over the player's limits, for columns in chunks the game has not loaded, and when nothing would be placed.
 * Keep Existing pastes {@link PasteOptions.Into#AIR}; the server applies the mask and protection as for any paste.
 * Client thread only.
 */
public final class SmartSurfaceDraft implements PointEditor.Host {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    public static final int COLOUR = 0xFF9BE07A;
    public static final int SELECTED_COLOUR = GenerateTool.SELECTED_COLOUR;
    public static final int IGNORED_COLOUR = GenerateTool.UNLOADED_COLOUR;
    /** The terrace outlines and the mesh: the point colour, dimmer. */
    static final int TERRACE_COLOUR = OverlayColors.scaleAlpha(COLOUR, 0.75);
    static final int MESH_COLOUR = 0xC0FFFFFF;
    /** Sheets over this many columns are previewed without terrace outlines (too many lines to draw each frame). */
    public static final int MAX_TERRACE_COLUMNS = 1 << 16;
    /**
     * Keep Existing's ghost shows only the cells that are air now, read on the client thread: up to this many cells. Past
     * it the ghost shows every cell (a hint says so); the paste still keeps what is there.
     */
    public static final long MAX_KEEP_FILTER_CELLS = 1 << 18;

    public static final String OP = "sculptory.op.generate_smart_surface";
    public static final String SENT = "sculptory.notice.generate_smart_surface_sent";
    public static final String NEEDS_POINTS = "sculptory.notice.generate_smart_surface_needs_points";
    /** A refusal of the kernel's, per {@link SmartSurfaceKernel.Problem}: this plus the problem's name in lower case. */
    public static final String PROBLEM = "sculptory.notice.smart_surface.";
    /** A click past the points cap ([the most of that role]). */
    public static final String TOO_MANY_OUTLINE = "sculptory.notice.smart_surface.too_many_outline";
    public static final String TOO_MANY_INSIDE = "sculptory.notice.smart_surface.too_many_inside";

    /**
     * A preview made: the surface (null when the points make none), its cells, ghost (null when empty or over
     * {@link GenerateTool#MAX_GHOST_CELLS}), terrace outline segments (six floats each), the columns left out for unloaded
     * ground, {@code tooLarge} past the cap, and the refusal when the points make no surface.
     */
    public record Built(SmartSurfaceKernel.Surface surface, GeneratedSource source, GhostVolume ghost, float[] terraces,
                        long[] unloaded, boolean tooLarge, long cap, SmartSurfaceKernel.RefusedException refused,
                        PasteOptions options) {
        public Built {
            Objects.requireNonNull(source);
            Objects.requireNonNull(terraces);
            Objects.requireNonNull(unloaded);
            Objects.requireNonNull(options);
        }

        public long cells() {
            return source.cells();
        }
    }

    private final GenerateTool.Services services;
    private final PathModel points = PathModel.closedOutline();
    private final PointEditor editor;
    private final GeneratedCommit commit;
    private boolean dirty = true;
    private int generation;
    /** Extend to Ground's first step (the surface) being worked out, or null. */
    private CompletableFuture<Planned> planning;
    private CompletableFuture<Built> running;
    private int runningGeneration;
    private Built shown;
    private GhostVolume ghost;
    private boolean buildWhenReady;
    /** The active block and the cap the preview shown or being made started with ({@link #stale}). */
    private BlockDescriptor previewBlock;
    private long previewCap = -1;
    /** Keep Existing's ghost left unfiltered: more cells than {@link #MAX_KEEP_FILTER_CELLS}. */
    private boolean keepUnfiltered;

    SmartSurfaceDraft(GenerateTool.Services services, AirPoints air) {
        this.services = Objects.requireNonNull(services);
        this.commit = new GeneratedCommit(services);
        this.editor = new PointEditor(points, air, GenerateTool.pointView(() -> services), this);
    }

    /** The points (for tests and the host). */
    public PathModel points() {
        return points;
    }

    /** The point editor (for tests). */
    public PointEditor editor() {
        return editor;
    }

    public Optional<Built> preview() {
        return Optional.ofNullable(shown);
    }

    public boolean previewRunning() {
        return planning != null || running != null;
    }

    /** A setting or the points changed: the preview is made again. */
    public void changed() {
        dirty = true;
        generation++;
        // Enter meant the surface as it was: a change since makes the player press it again.
        buildWhenReady = false;
    }

    /**
     * Whether an input the preview read outside its settings and points changed since it started: the active block (when
     * the top or body uses it) or the player's cap. Settings and points mark the preview changed themselves.
     */
    private boolean stale(ToolContext c) {
        if (shown == null && !previewRunning()) return false;
        if (usesActiveBlock(c.settings()) && !Objects.equals(services.activeBlock(), previewBlock)) return true;
        return capOf(c) != previewCap;
    }

    private static boolean usesActiveBlock(SettingsValues values) {
        return values.get(GenerateSettings.SMART_TOP_BLOCKS) == ShapeSettings.Blocks.ACTIVE_BLOCK
                || values.get(GenerateSettings.SMART_BODY_BLOCKS) == ShapeSettings.Blocks.ACTIVE_BLOCK;
    }

    private static long capOf(ToolContext c) {
        return GenerateTool.bypass(c) ? GenerateTool.BYPASS_PREVIEW_CELLS : GenerateTool.limits(c).maxClipboardVolume();
    }

    // ---- PointEditor.Host ----

    /** On top of the clicked block. */
    @Override
    public BlockPos pointOn(WorldCursor cursor) {
        return cursor.pos().offset(0, 1, 0);
    }

    @Override
    public PathModel.Role roleOf(PathModel model, BlockPos point, boolean onTerrain) {
        return PointEditor.outlineOrInside(model, point, onTerrain);
    }

    /** Outline and inside points together are at most the kernel's {@link SmartSurfaceKernel#MAX_POINTS}. */
    @Override
    public int maxPoints() {
        return SmartSurfaceKernel.MAX_POINTS - points.insideCount();
    }

    @Override
    public int maxInsidePoints() {
        return SmartSurfaceKernel.MAX_POINTS - points.size();
    }

    /** Outline and inside points have their own toasts (both caps are what is left of the shared total). */
    @Override
    public String tooManyNotice(PathModel.Role role) {
        return role == PathModel.Role.INSIDE ? TOO_MANY_INSIDE : TOO_MANY_OUTLINE;
    }

    @Override
    public void edited(ToolContext c, PointEditor.Edit edit) {
        if (edit.changesPoints()) changed();
    }

    // ---- Input ----

    public boolean onPointer(ToolContext c, PointerEvent e) {
        return editor.onPointer(c, e);
    }

    /** Alt+Scroll: the sky marker (and a point dragged in the air). */
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        if (!Modifiers.alt(e.modifiers()) || Modifiers.control(e.modifiers())) return false;
        return editor.onScroll(c, e);
    }

    /** Esc, Backspace/Delete, the insert key and Enter; returns whether the action was taken. */
    public boolean onAction(ToolContext c, EditorAction a) {
        if (a == EditorAction.COMMIT) {
            build(c);
            return true;
        }
        if (editor.onAction(c, a)) return true;
        if (a != EditorAction.CANCEL) return false;
        if (previewRunning()) {
            // The preview being made stops, and the one before it goes too: Enter makes a new one rather than build it.
            drop();
            dirty = false;
            return true;
        }
        if (!points.isEmpty()) {
            editor.clear(c);
            return true;
        }
        return false;
    }

    /** Once per frame: the editor's hover and target, a finished preview, a new one when something changed, a build. */
    public void frame(ToolContext c, FrameInfo f) {
        editor.frame(c, f);
        if (stale(c)) changed();
        poll(c);
        if (dirty && !previewRunning()) {
            start(c);
            poll(c); // an executor that runs at once (tests, tiny work) has the result already
        }
        commit.poll(c);
    }

    /** A build on its way goes out once encoded, whatever kind the tool is on now. */
    public void pollBuild(ToolContext c) {
        commit.poll(c);
    }

    /** The tool is put away: the editor lets go, previews and a build being encoded are dropped; the points stay. */
    public void deactivate(ToolContext c) {
        editor.deactivate(c);
        drop();
        commit.cancel();
        dirty = true;
    }

    /** The kind left Smart Surface: the editor lets go and the ghost goes; the points (and a build on its way) stay. */
    public void hide(ToolContext c) {
        editor.deactivate(c);
        drop();
        dirty = true;
    }

    // ---- Previews ----

    /**
     * What a preview is made from, read on the client thread when it starts (the settings and limits as they were then).
     */
    record Inputs(SmartSurfaceKernel.Shape shape, SmartSurfaceKernel.Fill fill, StateSpace states, int bottom, int top,
                  long cap, PasteOptions options) {}

    /** The surface worked out (Extend to Ground's first step), or why the points make none. */
    record Planned(Inputs inputs, SmartSurfaceKernel.Surface surface, SmartSurfaceKernel.RefusedException refused) {}

    /**
     * Starts the preview for the points and settings as they are now. Without Extend to Ground it is one task off the
     * client thread. With it, three steps: the surface off the client thread, the ground under each column on it
     * ({@link GroundFill#groundUnder} from under the surface cell, bounded by what the cap leaves), then the cells off it.
     */
    private void start(ToolContext c) {
        dirty = false;
        previewBlock = services.activeBlock();
        previewCap = capOf(c);
        Inputs inputs = inputs(c);
        if (inputs == null) {
            if (shown != null) drop();
            return;
        }
        runningGeneration = generation;
        if (inputs.fill().extendToGround()) {
            planning = CompletableFuture.supplyAsync(() -> plan(inputs), services.background());
        } else {
            running = CompletableFuture.supplyAsync(() -> make(inputs.shape(), inputs.fill(), null, inputs.states(),
                    inputs.bottom(), inputs.top(), inputs.cap(), inputs.options()), services.background());
        }
    }

    /** The preview's inputs, read on the client thread; null when there is nothing to preview. */
    private Inputs inputs(ToolContext c) {
        if (points.size() < SmartSurfaceKernel.MIN_OUTLINE_POINTS) return null;
        StateSpace states = GenerateTool.statesOrNull(c);
        WorldReader world = GenerateTool.worldOrNull(c);
        if (states == null || world == null) return null;
        SettingsValues values = c.settings();
        SmartSurfaceKernel.Fill fill = fill(c, values, states);
        if (fill == null) return null;
        SmartSurfaceKernel.Shape shape = new SmartSurfaceKernel.Shape(points.nodes(), points.insideNodes(),
                values.get(GenerateSettings.SMART_FACETED));
        long cap = GenerateTool.bypass(c) ? GenerateTool.BYPASS_PREVIEW_CELLS : GenerateTool.limits(c).maxClipboardVolume();
        return new Inputs(shape, fill, states, world.bottomY(), world.topYExclusive(), cap, options(values));
    }

    /** The paste options: Keep Existing writes only cells that are air when the paste lands. */
    static PasteOptions options(SettingsValues values) {
        PasteOptions options = new PasteOptions(true, false, false);
        return values.get(GenerateSettings.SMART_KEEP_EXISTING) ? options.withInto(PasteOptions.Into.AIR) : options;
    }

    /** The surface of the inputs' points (any thread). */
    static Planned plan(Inputs inputs) {
        try {
            return new Planned(inputs, SmartSurfaceKernel.surface(inputs.shape()), null);
        } catch (SmartSurfaceKernel.RefusedException refused) {
            return new Planned(inputs, null, refused);
        }
    }

    /**
     * The ground under each column of {@code surface}, looked for in {@code world} on its thread: the shared rule
     * ({@link GroundFill#groundUnder}) from under each surface cell, stopping (null) once the cells the fill would take
     * pass what {@code cap} leaves after the surface cells themselves.
     */
    static GroundMap groundUnder(SmartSurfaceKernel.Surface surface, WorldReader world, long cap) {
        return GroundFill.groundUnder(surface.tops(), world, Math.max(0, cap - surface.columnCount()));
    }

    /** The preview of {@code shape} filled with {@code fill} over {@code ground} (any thread). */
    static Built make(SmartSurfaceKernel.Shape shape, SmartSurfaceKernel.Fill fill, SurfaceReader ground,
                      StateSpace states, int bottom, int top, long cap, PasteOptions options) {
        SmartSurfaceKernel.Surface surface;
        try {
            surface = SmartSurfaceKernel.surface(shape);
        } catch (SmartSurfaceKernel.RefusedException refused) {
            return refusedBuilt(refused, cap, options);
        }
        return finish(surface, fill, ground, states, bottom, top, cap, options);
    }

    private static Built refusedBuilt(SmartSurfaceKernel.RefusedException refused, long cap, PasteOptions options) {
        return new Built(null, GeneratedSource.empty(), null, new float[0], new long[0], false, cap, refused, options);
    }

    private static Built tooLargeBuilt(SmartSurfaceKernel.Surface surface, long cap, PasteOptions options) {
        return new Built(surface, GeneratedSource.empty(), null, terraces(surface), new long[0], true, cap, null, options);
    }

    /** The cells of {@code surface} (any thread). */
    static Built finish(SmartSurfaceKernel.Surface surface, SmartSurfaceKernel.Fill fill, SurfaceReader ground,
                        StateSpace states, int bottom, int top, long cap, PasteOptions options) {
        float[] terraces = terraces(surface);
        try {
            SmartSurfaceKernel.Result result = SmartSurfaceKernel.generate(surface, fill, ground, bottom, top, cap);
            GeneratedSource source = result.source();
            GhostVolume baked = source.isEmpty() || source.cells() > GenerateTool.MAX_GHOST_CELLS ? null
                    : GhostBaker.fromSparse(source, states);
            return new Built(surface, source, baked, terraces, result.unloaded(), false, cap, null, options);
        } catch (GeneratedTooLargeException e) {
            return tooLargeBuilt(surface, cap, options);
        }
    }

    /**
     * The fill of the settings: the top and body are each the active block or their mix (the mix's seed fixed, so the
     * same points give the same blocks). Null after a toast when a block can't be used.
     */
    private SmartSurfaceKernel.Fill fill(ToolContext c, SettingsValues values, StateSpace states) {
        Pattern top = values.get(GenerateSettings.SMART_TOP_BLOCKS) == ShapeSettings.Blocks.PALETTE
                ? GenerateTool.mix(c, values.get(GenerateSettings.SMART_TOP_PALETTE), states)
                : GenerateTool.single(c, services.activeBlock(), states);
        if (top == null) return null;
        Pattern body = values.get(GenerateSettings.SMART_BODY_BLOCKS) == ShapeSettings.Blocks.PALETTE
                ? GenerateTool.mix(c, values.get(GenerateSettings.SMART_BODY_PALETTE), states)
                : GenerateTool.single(c, services.activeBlock(), states);
        if (body == null) return null;
        boolean extend = values.get(GenerateSettings.SMART_EXTEND_TO_GROUND);
        // Extend to Ground fills to the terrain and meets the neighbours too (its Gap-free setting is hidden).
        boolean gapFree = extend || values.get(GenerateSettings.SMART_GAP_FREE);
        return new SmartSurfaceKernel.Fill(top, values.get(GenerateSettings.SMART_TOP_DEPTH), body, extend,
                values.get(GenerateSettings.SMART_THICKNESS), gapFree);
    }

    /**
     * The terrace outlines: where a column's top stands above its neighbour's (or the sheet's edge), the top edge of that
     * side, joined along each row or column while the height stays; none over {@link #MAX_TERRACE_COLUMNS} columns.
     */
    static float[] terraces(SmartSurfaceKernel.Surface surface) {
        if (surface.columnCount() > MAX_TERRACE_COLUMNS) return new float[0];
        FloatArrayList out = new FloatArrayList();
        int x0 = surface.minX(), z0 = surface.minZ(), sx = surface.sizeX(), sz = surface.sizeZ();
        // North (-z) and south (+z) faces: edges along x.
        for (int side = -1; side <= 1; side += 2) {
            for (int z = z0; z < z0 + sz; z++) {
                int runStart = 0, runY = Integer.MIN_VALUE;
                for (int x = x0; x <= x0 + sx; x++) {
                    int y = x < x0 + sx ? stepUp(surface, x, z, 0, side) : Integer.MIN_VALUE;
                    if (y != runY) {
                        if (runY != Integer.MIN_VALUE) {
                            float ez = side < 0 ? z : z + 1;
                            add(out, runStart, runY + 1, ez, x, runY + 1, ez);
                        }
                        runStart = x;
                        runY = y;
                    }
                }
            }
        }
        // West (-x) and east (+x) faces: edges along z.
        for (int side = -1; side <= 1; side += 2) {
            for (int x = x0; x < x0 + sx; x++) {
                int runStart = 0, runY = Integer.MIN_VALUE;
                for (int z = z0; z <= z0 + sz; z++) {
                    int y = z < z0 + sz ? stepUp(surface, x, z, side, 0) : Integer.MIN_VALUE;
                    if (y != runY) {
                        if (runY != Integer.MIN_VALUE) {
                            float ex = side < 0 ? x : x + 1;
                            add(out, ex, runY + 1, runStart, ex, runY + 1, z);
                        }
                        runStart = z;
                        runY = y;
                    }
                }
            }
        }
        return out.toFloatArray();
    }

    /** Column (x, z)'s surface when it stands above its neighbour toward (dx, dz) or that is off the sheet, else MIN. */
    private static int stepUp(SmartSurfaceKernel.Surface surface, int x, int z, int dx, int dz) {
        if (!surface.covers(x, z)) return Integer.MIN_VALUE;
        int y = surface.surfaceY(x, z);
        if (!surface.covers(x + dx, z + dz)) return y;
        return surface.surfaceY(x + dx, z + dz) < y ? y : Integer.MIN_VALUE;
    }

    private static void add(FloatArrayList out, float x1, float y1, float z1, float x2, float y2, float z2) {
        out.add(x1);
        out.add(y1);
        out.add(z1);
        out.add(x2);
        out.add(y2);
        out.add(z2);
    }

    /** Takes what finished: a surface planned (then reads the ground and starts the cells), then the cells. */
    private void poll(ToolContext c) {
        if (planning != null && planning.isDone()) {
            CompletableFuture<Planned> done = planning;
            planning = null;
            Planned planned;
            try {
                planned = done.getNow(null);
            } catch (CompletionException | CancellationException e) {
                LOG.warn("Sculptory: working out a smart surface failed", e.getCause() != null ? e.getCause() : e);
                planned = null;
            }
            if (runningGeneration == generation) {
                if (planned == null) {
                    drop(); // a failure leaves no preview to build: Enter makes a new one
                } else {
                    proceed(c, planned);
                }
            }
        }
        if (running == null || !running.isDone()) return;
        CompletableFuture<Built> done = running;
        running = null;
        Built built;
        try {
            built = done.getNow(null);
        } catch (CompletionException | CancellationException e) {
            LOG.warn("Sculptory: generating a smart surface failed", e.getCause() != null ? e.getCause() : e);
            built = null;
        }
        if (runningGeneration != generation) {
            if (built != null && built.ghost() != null) services.releaseGhost(built.ghost());
            return;
        }
        if (built == null) {
            drop(); // a failure leaves no preview to build: Enter makes a new one
            return;
        }
        show(c, built);
    }

    /** Extend to Ground's second step, on the client thread: the ground under the planned surface, then the cells. */
    private void proceed(ToolContext c, Planned planned) {
        Inputs in = planned.inputs();
        if (planned.refused() != null) {
            show(c, refusedBuilt(planned.refused(), in.cap(), in.options()));
            return;
        }
        WorldReader world = GenerateTool.worldOrNull(c);
        if (world == null) return;
        GroundMap ground = groundUnder(planned.surface(), world, in.cap());
        if (ground == null) {
            show(c, tooLargeBuilt(planned.surface(), in.cap(), in.options()));
            return;
        }
        running = CompletableFuture.supplyAsync(() -> finish(planned.surface(), in.fill(), ground, in.states(),
                in.bottom(), in.top(), in.cap(), in.options()), services.background());
    }

    /**
     * Shows a preview: its ghost was baked off the client thread; the global mask and Keep Existing are judged here
     * against the world as it is now, and only when they leave cells out is the ghost baked again.
     */
    private void show(ToolContext c, Built built) {
        keepUnfiltered = false;
        GhostVolume next = built.ghost();
        BlockPos origin = built.source().isEmpty() ? null : built.source().bounds().min();
        if (next != null) {
            WorldReader world = GenerateTool.worldOrNull(c);
            StateSpace states = GenerateTool.statesOrNull(c);
            GeneratedSource visible = built.source();
            if (world != null) {
                visible = GhostMasking.filter(visible, world);
                // Keep Existing reads every cell here, on the client thread: only up to a bound.
                keepUnfiltered = built.options().into() == PasteOptions.Into.AIR && visible.cells() > MAX_KEEP_FILTER_CELLS;
                if (built.options().into() == PasteOptions.Into.AIR && !keepUnfiltered) visible = airOnly(visible, world);
            }
            if (visible != built.source()) {
                services.releaseGhost(next);
                next = states == null || visible.isEmpty() ? null : GhostBaker.fromSparse(visible, states);
                origin = visible.isEmpty() ? null : visible.bounds().min();
            }
        }
        if (ghost != null && ghost != next) services.releaseGhost(ghost);
        ghost = next;
        shown = new Built(built.surface(), built.source(), next, built.terraces(), built.unloaded(), built.tooLarge(),
                built.cap(), built.refused(), built.options());
        if (next != null) {
            services.showGhosts(List.of(GhostPlacement.of(next, origin.x(), origin.y(), origin.z())));
        } else {
            services.showGhosts(List.of());
        }
        if (buildWhenReady) {
            buildWhenReady = false;
            build(c);
        }
    }

    /** {@code source} cut down to the cells that are air in {@code world} now (all of them in a chunk not loaded here). */
    static GeneratedSource airOnly(GeneratedSource source, WorldReader world) {
        if (source.isEmpty()) return source;
        StateSpace states = world.states();
        GeneratedSource.Builder kept = GeneratedSource.builder(source.cells());
        boolean[] dropped = {false};
        source.forEach((x, y, z, state) -> {
            boolean air = !world.isLoaded(x >> 4, z >> 4)
                    || StateFlags.has(states.flags(world.get(x, y, z)), StateFlags.AIR);
            if (air) {
                kept.set(x, y, z, state);
            } else {
                dropped[0] = true;
            }
        });
        return dropped[0] ? kept.build() : source;
    }

    private void drop() {
        generation++;
        if (planning != null) planning.cancel(false);
        if (running != null) running.cancel(false);
        planning = null;
        running = null;
        buildWhenReady = false;
        if (ghost != null) services.releaseGhost(ghost);
        ghost = null;
        shown = null;
        services.showGhosts(List.of());
    }

    // ---- Building ----

    private void build(ToolContext c) {
        if (points.size() < SmartSurfaceKernel.MIN_OUTLINE_POINTS) {
            c.notify(Notice.of(Notice.Level.INFO, NEEDS_POINTS));
            return;
        }
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return;
        }
        Optional<Perm> missing = GenerateTool.missingNode(session.permissions());
        if (missing.isPresent()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", missing.get().node()));
            return;
        }
        if (stale(c)) changed();
        if (dirty || previewRunning() || shown == null) {
            if (dirty || !previewRunning()) start(c);
            if (!previewRunning()) return;
            poll(c);
            if (previewRunning()) {
                buildWhenReady = true;
                return;
            }
            if (shown == null) return;
        }
        Built built = shown;
        if (built.refused() != null) {
            c.notify(problem(built.refused()));
            return;
        }
        Limits limits = session.permissions().limits();
        if (built.tooLarge()) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", "> " + SessionNotices.count(built.cap()),
                    SessionNotices.count(limits.maxClipboardVolume())));
            return;
        }
        int unloaded = built.unloaded().length > 0 ? built.unloaded().length : LineDraft.unloadedChunks(c, built.source());
        if (unloaded > 0) {
            c.notify(Notice.of(Notice.Level.WARNING, GenerateTool.UNLOADED, Integer.toString(unloaded)));
            return;
        }
        if (built.source().isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, GenerateTool.NOTHING));
            return;
        }
        commit.send(c, new GeneratedCommit.Request(built.source(), OpLabel.SMART_SURFACE, OP, SENT, built.options()));
    }

    /** The toast for a kernel refusal. */
    static Notice problem(SmartSurfaceKernel.RefusedException refused) {
        return Notice.of(Notice.Level.WARNING, problemKey(refused.problem()), problemArgs(refused.problem()));
    }

    static String problemKey(SmartSurfaceKernel.Problem problem) {
        return PROBLEM + problem.name().toLowerCase(Locale.ROOT);
    }

    private static String[] problemArgs(SmartSurfaceKernel.Problem problem) {
        return switch (problem) {
            case TOO_FEW_POINTS -> new String[] {Integer.toString(SmartSurfaceKernel.MIN_OUTLINE_POINTS)};
            case TOO_MANY_POINTS -> new String[] {Integer.toString(SmartSurfaceKernel.MAX_POINTS)};
            case TOO_WIDE -> new String[] {Integer.toString(SmartSurfaceKernel.MAX_SIDE)};
            case SHARED_COLUMN, NO_AREA -> new String[0];
        };
    }

    // ---- Drawing and hints ----

    /**
     * The points (through the editor: outline and inside, the selected one's gizmo, the guide line), the outline loop,
     * inside points the sheet does not use in red, the terrace outlines, Mesh view's triangles, and the bounds when the
     * ghost can't show the cells.
     */
    public void renderWorld(ToolContext c, WorldDraw d) {
        List<BlockPos> outline = points.nodes();
        d.seeThrough(true);
        if (outline.size() >= 2) {
            for (int i = 0; i < outline.size(); i++) {
                if (outline.size() == 2 && i == 1) break;
                BlockPos a = outline.get(i), b = outline.get((i + 1) % outline.size());
                d.line(a.x() + 0.5, a.y() + 0.5, a.z() + 0.5, b.x() + 0.5, b.y() + 0.5, b.z() + 0.5,
                        OverlayColors.scaleAlpha(COLOUR, 0.8));
            }
        }
        d.seeThrough(false);
        editor.render(d, COLOUR, SELECTED_COLOUR);
        Built built = shown;
        if (built == null) return;
        if (built.surface() != null) {
            for (BlockPos p : built.surface().ignored()) d.boxOutline(Box.of(p), IGNORED_COLOUR);
            float[] t = built.terraces();
            for (int i = 0; i + 5 < t.length; i += 6) d.line(t[i], t[i + 1], t[i + 2], t[i + 3], t[i + 4], t[i + 5], TERRACE_COLOUR);
            if (c.settings().get(GenerateSettings.SMART_MESH_VIEW)) drawMesh(d, built.surface());
        }
        if (!built.source().isEmpty() && built.ghost() == null) {
            Box bounds = built.source().bounds();
            d.seeThrough(true);
            d.boxOutline(bounds, OverlayColors.scaleAlpha(COLOUR, 0.35));
            d.seeThrough(false);
            d.boxOutline(bounds, COLOUR);
        }
    }

    /** Each triangle edge once, between the points' top faces, through the terrain. */
    private static void drawMesh(WorldDraw d, SmartSurfaceKernel.Surface surface) {
        List<BlockPos> sites = surface.sites();
        int[] t = surface.triangles();
        LongOpenHashSet drawn = new LongOpenHashSet();
        d.seeThrough(true);
        for (int k = 0; k < t.length; k += 3) {
            for (int e = 0; e < 3; e++) {
                int u = t[k + e], v = t[k + (e + 1) % 3];
                long key = u < v ? ((long) u << 32) | v : ((long) v << 32) | u;
                if (!drawn.add(key)) continue;
                BlockPos a = sites.get(u), b = sites.get(v);
                d.line(a.x() + 0.5, a.y() + 1.02, a.z() + 0.5, b.x() + 0.5, b.y() + 1.02, b.z() + 0.5, MESH_COLOUR);
            }
        }
        d.seeThrough(false);
    }

    public List<KeyHint> hints(ToolContext c, String click) {
        List<KeyHint> hints = new ArrayList<>();
        if (editor.busy()) {
            hints.addAll(editor.hints(click, services::keyLabel));
            return hints;
        }
        AirPoints air = editor.air();
        if (points.isEmpty()) {
            hints.add(new KeyHint(click, "sculptory.hint.smart.start"));
            hints.add(air.hint());
            return hints;
        }
        if (points.size() < SmartSurfaceKernel.MIN_OUTLINE_POINTS) {
            hints.add(new KeyHint(click, "sculptory.hint.smart.more_points",
                    List.of(Integer.toString(SmartSurfaceKernel.MIN_OUTLINE_POINTS - points.size()))));
        } else {
            hints.add(new KeyHint(click, "sculptory.hint.smart.add"));
        }
        hints.add(air.hint());
        hints.add(new KeyHint(services.keyLabel(KeyAction.ERASE_SELECTION) + "/" + services.keyLabel(KeyAction.REMOVE_NODE),
                "sculptory.hint.smart.remove_point"));
        Built built = shown;
        if (built != null && built.refused() != null) {
            hints.add(KeyHint.text(problemKey(built.refused().problem()), problemArgs(built.refused().problem())));
        } else if (built != null && built.tooLarge()) {
            hints.add(KeyHint.text("sculptory.hint.generate.too_large", SessionNotices.count(built.cap())));
        } else if (built != null && built.unloaded().length > 0) {
            hints.add(KeyHint.text("sculptory.hint.generate.unloaded", Integer.toString(built.unloaded().length)));
        } else if (built != null && !built.source().isEmpty()) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.COMMIT), "sculptory.hint.smart.build",
                    List.of(SessionNotices.count(built.cells()))));
        }
        if (built != null && keepUnfiltered) hints.add(KeyHint.text("sculptory.hint.smart.keep_unfiltered"));
        if (built != null && built.surface() != null && !built.surface().ignored().isEmpty()) {
            hints.add(KeyHint.text("sculptory.hint.smart.ignored", Integer.toString(built.surface().ignored().size())));
        }
        // The editor's own: the gizmo, the insert key and Esc for a selected point, a click on an edge to insert.
        hints.addAll(editor.hints(click, services::keyLabel));
        if (points.selection().isEmpty()) hints.add(new KeyHint("Esc", "sculptory.hint.smart.clear"));
        return hints;
    }
}
