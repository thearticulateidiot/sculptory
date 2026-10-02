package dev.sculptory.fabric.client.editor.tools.fluid;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.brush.BrushOutlines;
import dev.sculptory.fabric.client.editor.tools.brush.BrushServices;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeBrushTool;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeSettings;
import dev.sculptory.fabric.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.tools.select.SelectionModel;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.client.session.ToolAction;
import dev.sculptory.fabric.client.session.ToolResult;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The Fluid tool (palette slot 13, key [): floods air pockets,
 * drains bodies of water or lava, and paints balls of a fluid.
 *
 * <p><b>Flood.</b> Hovering shows the air pocket beside the block under the cursor that still water poured there would
 * fill: the air connected to the cell against the aimed face, at or below that cell's level ({@link FluidFlood}, spread
 * over frames by {@link FluidJob}), as the selection's translucent outline. A click sends one {@code Fill} of those
 * cells with the fluid; with "Waterlog on the way" the stairs, slabs, fences and other waterloggable blocks touching
 * the pocket are waterlogged by the same fill ({@code Pattern.Waterlog}).
 *
 * <p><b>Drain.</b> Hovering shows the body of water or lava under the cursor ({@link FluidDrain}; the cursor stops at
 * fluid surfaces in this mode); a click sends one {@code Fill} of it with {@code Pattern.Dry}: fluid cells become air,
 * waterlogged blocks lose their water, water plants go.
 *
 * <p><b>Fluid ball.</b> A sphere of the fluid at the cursor (Ctrl+Scroll: radius), a drag paints balls: exactly the
 * Shape brush's stroke, prediction and pacing (an inner {@link ShapeBrushTool} in a preset), with "Waterlog where
 * possible" placing {@code Pattern.Waterlog} (air filled, waterloggable blocks waterlogged, nothing else touched),
 * else the plain fluid into air and replaceables.
 *
 * <p>Sizes, the confirm dialog and limits are the Select operations'; a symmetry mode repeats the op or the dabs around
 * the shared centre (M). Esc drops a running search or stops a stroke. The tool itself needs only {@code use}; Flood
 * and Drain ask for {@code region} at the click, the ball for {@code brush} at the press (each with a toast). Client
 * thread only.
 */
public final class FluidTool implements Tool {
    public static final String CLICK = "LMB";
    /** The Fluid tool's colour (ARGB): the outline of the cells a click would touch. */
    public static final int COLOUR = 0xFF4FA8FF;
    /** How often the outline is remeshed while a search runs. */
    static final long PREVIEW_INTERVAL_NANOS = 250_000_000L;
    /** How long after a sent op the search runs again (the client world has the new blocks by then). */
    static final long REFRESH_AFTER_NANOS = 1_000_000_000L;

    public static final String NOTHING_TO_FLOOD = "sculptory.notice.fluid_nothing_flood";
    public static final String NOTHING_TO_DRAIN = "sculptory.notice.fluid_nothing_drain";
    public static final String LIMIT = "sculptory.notice.fluid_limit";
    public static final String UNLOADED = "sculptory.notice.fluid_unloaded";
    public static final String FLOOD_SENT = "sculptory.notice.fluid_flood_sent";
    public static final String DRAIN_SENT = "sculptory.notice.fluid_drain_sent";
    public static final String OP_FLOOD = "sculptory.op.flood";
    public static final String OP_DRAIN = "sculptory.op.drain";

    /** What the tool needs from the client beyond its context and the brush services. */
    public interface Services {
        String keyLabel(KeyAction action);

        /** Asks the player to confirm a large op ({@code copies} 1 without symmetry); {@code onConfirm} runs only if they do. */
        void confirm(String opNameKey, long blocks, int copies, Runnable onConfirm);

        /** The clock the searches' frame budget is measured with. */
        default long nanoTime() {
            return System.nanoTime();
        }

        /** Services without Minecraft: default key names, confirms at once. */
        static Services headless() {
            return new Services() {
                @Override
                public String keyLabel(KeyAction action) {
                    return EditorKeymap.defaults().display(action);
                }

                @Override
                public void confirm(String opNameKey, long blocks, int copies, Runnable onConfirm) {
                    onConfirm.run();
                }
            };
        }
    }

    /** What a search was started for; a change starts it again. */
    private record Key(FluidSettings.Mode mode, BlockPos seed, FluidSettings.Fluid fluid, long limit, boolean waterlog,
                       Box bounds) {}

    /** {@code use} only: each mode asks for its own node (Flood and Drain {@code region}, the ball {@code brush}). */
    private final ToolDescriptor descriptor = new ToolDescriptor(ToolId.FLUID, "sculptory.tool.fluid",
            "minecraft:water_bucket", Perm.USE);
    private final Services services;
    private final SymmetryCentre centre;
    private final ShapeBrushTool ball;
    private final BallView ballView = new BallView();

    private ToolContext context;
    private WorldCursor cursor = WorldCursor.miss(0, 0, 0);
    /** Whether the inner brush is active (Fluid ball mode, between activate and deactivate). */
    private boolean ballActive;
    // The search.
    private Key key;
    private FluidJob job;
    /** The last finished search, for {@link #key}, or null. */
    private FluidSearch result;
    /**
     * The cells drawn, or null: one region instance for as long as the cells are the same, because the outline mesher
     * tells cell sets apart by identity and starts its build over for a new one.
     */
    private Region.Cells previewRegion;
    private long previewAt;
    /** A click came while the search ran: commit when it lands. */
    private boolean commitWhenDone;
    /** When to run the search again after a sent op (0: no refresh due). */
    private long refreshAt;

    /**
     * @param brushes the brush services the fluid ball's stroke uses (prediction, cursor, keys, seeds)
     * @param centre the symmetry centre shared with the brushes and the Select and Place tools
     */
    public FluidTool(Services services, BrushServices brushes, SymmetryCentre centre) {
        this.services = Objects.requireNonNull(services);
        this.centre = Objects.requireNonNull(centre);
        this.ball = new ShapeBrushTool(Objects.requireNonNull(brushes), centre, descriptor, this::ballMaterial);
    }

    /** The inner Shape brush the fluid ball runs on (tests and diagnostics). */
    public ShapeBrushTool ball() {
        return ball;
    }

    /** The search still running, if any. */
    public Optional<FluidSearch> searching() {
        return Optional.ofNullable(job).map(FluidJob::search);
    }

    /** The finished search for the cursor as it is, if any. */
    public Optional<FluidSearch> found() {
        return Optional.ofNullable(result);
    }

    @Override
    public ToolDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public SettingsSchema schema() {
        return FluidSettings.SCHEMA;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return s.get(FluidSettings.MODE) == FluidSettings.Mode.DRAIN ? RaycastMode.FLUIDS : RaycastMode.BLOCKS;
    }

    /** The ball's: while a press paints balls, the cursor looks through the balls it placed. */
    @Override
    public RayOverlay rayOverlay() {
        return ballActive ? ball.rayOverlay() : null;
    }

    @Override
    public void activate(ToolContext c) {
        context = c;
        if (mode(c) == FluidSettings.Mode.BALL) activateBall();
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        context = c;
        deactivateBall(r);
        dropSearch();
        result = null;
        setPreview(null);
        context = null;
    }

    // ---- Input ----

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        context = c;
        cursor = e.cursor();
        if (mode(c) == FluidSettings.Mode.BALL) {
            if (e.kind() == PointerEvent.Kind.PRESS && e.button() == PointerEvent.LEFT && !permitted(c, Perm.BRUSH)) {
                return true;
            }
            return ball.onPointer(ballView, e);
        }
        if (e.kind() != PointerEvent.Kind.PRESS || e.button() != PointerEvent.LEFT) return false;
        commit(c);
        return true;
    }

    @Override
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        context = c;
        return mode(c) == FluidSettings.Mode.BALL && ball.onScroll(ballView, e);
    }

    @Override
    public boolean onAction(ToolContext c, EditorAction a) {
        context = c;
        boolean ballMode = mode(c) == FluidSettings.Mode.BALL;
        switch (a) {
            case CANCEL -> {
                if (ballMode) return ball.onAction(ballView, a);
                if (job == null) return false;
                dropSearch();
                return true;
            }
            case SET_SYMMETRY_CENTRE -> {
                if (ballMode) return ball.onAction(ballView, a);
                centre.keyPressed(c, cursor, c.settings().get(FluidSettings.SYMMETRY) != Symmetry.Mode.OFF);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public void onSettingsChanged(ToolContext c, SettingsValues before, SettingsValues after) {
        context = c;
        boolean wasBall = before.get(FluidSettings.MODE) == FluidSettings.Mode.BALL;
        boolean isBall = after.get(FluidSettings.MODE) == FluidSettings.Mode.BALL;
        if (wasBall && !isBall) deactivateBall(DeactivateReason.SWITCHED_TOOL);
        if (isBall && !wasBall) activateBall();
        if (isBall && wasBall) {
            ball.onSettingsChanged(ballView, FluidSettings.shapeSettings(before), FluidSettings.shapeSettings(after));
        }
        // The next frame compares the search's key with the settings and starts again when they differ.
    }

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        context = c;
        cursor = f.cursor();
        if (mode(c) == FluidSettings.Mode.BALL) {
            dropSearch();
            result = null;
            setPreview(null);
            ball.frame(ballView, f);
            return;
        }
        // One clock for the frame budget, the preview interval and the refresh after a sent op (tests drive it).
        long now = services.nanoTime();
        if (refreshAt != 0 && now - refreshAt >= 0) {
            refreshAt = 0;
            key = null;
        }
        Key wanted = keyFor(c);
        if (!Objects.equals(wanted, key)) {
            key = wanted;
            dropSearch();
            result = null;
            setPreview(null);
            if (wanted != null) job = new FluidJob(search(c, wanted));
        }
        if (job == null) return;
        if (job.run(FluidJob.FRAME_BUDGET_NANOS, services::nanoTime)) {
            result = job.search();
            job = null;
            setPreview(result.count() == 0 ? null : result.cells());
            if (commitWhenDone) {
                commitWhenDone = false;
                commit(c);
            }
        } else if (previewRegion == null || now - previewAt >= PREVIEW_INTERVAL_NANOS) {
            // The first cells found show at once, then at most every PREVIEW_INTERVAL_NANOS.
            previewAt = now;
            FluidSearch running = job.search();
            setPreview(running.count() == 0 ? null : running.cells());
        }
    }

    // ---- Searches ----

    private void setPreview(CellSet cells) {
        previewRegion = cells == null || cells.isEmpty() ? null : new Region.Cells(cells);
    }

    private Key keyFor(ToolContext c) {
        if (cursor.missed()) return null;
        SettingsValues values = c.settings();
        FluidSettings.Mode mode = values.get(FluidSettings.MODE);
        BlockPos seed = mode == FluidSettings.Mode.FLOOD ? cursor.adjacent() : cursor.pos();
        boolean waterlog = mode == FluidSettings.Mode.FLOOD ? FluidSettings.floodWaterlogs(values)
                : FluidSettings.drainsWaterlogged(values);
        return new Key(mode, seed, values.get(FluidSettings.FLUID), limit(c, values), waterlog,
                c.selection().orElse(null));
    }

    private FluidSearch search(ToolContext c, Key key) {
        WorldReader world = c.world();
        return key.mode() == FluidSettings.Mode.FLOOD
                ? new FluidFlood(world, key.seed(), key.limit(), key.bounds(), key.waterlog())
                : new FluidDrain(world, key.seed(), key.limit(), key.bounds(), key.waterlog());
    }

    /** The search's limit: the setting, capped by the server's op and selection limits unless the player bypasses them. */
    private static long limit(ToolContext c, SettingsValues values) {
        long limit = values.get(FluidSettings.LIMIT);
        Optional<Permissions> permissions = permissions(c);
        if (permissions.isEmpty() || permissions.get().has(Perm.LIMIT_BYPASS)) return limit;
        Limits limits = permissions.get().limits();
        return Math.max(1, Math.min(limit, Math.min(limits.maxOpVolume(), limits.maxSelectionCells())));
    }

    private void dropSearch() {
        job = null;
        commitWhenDone = false;
    }

    // ---- Committing ----

    /** A click in Flood or Drain mode: the op over the cells found (once the search is done). */
    private void commit(ToolContext c) {
        if (job != null) {
            commitWhenDone = true;
            return;
        }
        SettingsValues values = c.settings();
        FluidSettings.Mode mode = values.get(FluidSettings.MODE);
        boolean flood = mode == FluidSettings.Mode.FLOOD;
        if (result == null || result.count() == 0) {
            c.notify(Notice.of(Notice.Level.INFO, flood ? NOTHING_TO_FLOOD : NOTHING_TO_DRAIN));
            return;
        }
        EditorSession session;
        try {
            session = c.session();
        } catch (IllegalStateException noSession) {
            return;
        }
        Permissions permissions = session.permissions();
        if (!permitted(c, Perm.REGION)) return;
        Symmetry.Mode symmetryMode = values.get(FluidSettings.SYMMETRY);
        Optional<Symmetry> symmetry = centre.forOp(symmetryMode);
        if (symmetry.isEmpty()) {
            c.notify(Notice.of(Notice.Level.WARNING, SelectionActions.SYMMETRY_CENTRE_NEEDED,
                    services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE)));
            return;
        }
        Pattern pattern;
        if (flood) {
            int fluid = FluidSettings.fluidSource(values, c.states());
            if (fluid < 0) {
                c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", values.get(FluidSettings.FLUID).block()));
                return;
            }
            // Always Waterlog: the server then fills air (and waterlogs the rim cells sent) even if a block was placed
            // in the pocket between the hover and the click, or the client's chunk was stale; Single would overwrite it.
            pattern = new Pattern.Waterlog(fluid);
        } else {
            pattern = new Pattern.Dry();
        }
        CellSet cells = result.cells();
        Region region = new Region.Cells(cells);
        int copies = SelectionActions.copies(region, symmetry.get());
        long volume = saturatingTimes(cells.size(), copies);
        long limit = permissions.has(Perm.LIMIT_BYPASS) ? Long.MAX_VALUE : permissions.limits().maxOpVolume();
        if (volume > limit) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.too_large", SelectionModel.count(volume),
                    SelectionModel.count(permissions.limits().maxOpVolume())));
            return;
        }
        if (result.hitLimit()) {
            c.notify(Notice.of(Notice.Level.INFO, LIMIT, SelectionModel.count(result.count())));
        }
        if (result.hitUnloaded()) {
            c.notify(Notice.of(Notice.Level.INFO, UNLOADED));
        }
        OpSpec spec = OpSymmetry.withSymmetry(new OpSpec.Fill(region, pattern, CellMask.ANY), symmetry.get());
        Runnable send = () -> send(c, session, spec, flood, volume);
        if (volume > SelectionActions.CONFIRM_VOLUME) {
            services.confirm(flood ? OP_FLOOD : OP_DRAIN, volume, copies, send);
        } else {
            send.run();
        }
    }

    private void send(ToolContext c, EditorSession session, OpSpec spec, boolean flood, long volume) {
        session.send(new ToolAction.RunOp(spec, false, flood ? OpLabel.FLOOD : OpLabel.DRAIN)).thenAccept(outcome -> {
            switch (outcome) {
                case ToolResult.Accepted accepted -> {
                    c.notify(Notice.of(Notice.Level.INFO, flood ? FLOOD_SENT : DRAIN_SENT, SelectionModel.count(volume)));
                    // The cells are about to change: search again once the client world shows them.
                    result = null;
                    setPreview(null);
                    refreshAt = services.nanoTime() + REFRESH_AFTER_NANOS;
                }
                case ToolResult.Rejected rejected -> c.notify(SessionNotices.rejection(rejected.reason(),
                        SessionNotices.Subject.EDIT, session.permissions().limits()));
                case ToolResult.Done done -> {
                }
            }
        });
    }

    /** Whether the player has {@code perm}, toasting the node otherwise (true without a session: the session refuses). */
    private static boolean permitted(ToolContext c, Perm perm) {
        Optional<Permissions> permissions = permissions(c);
        if (permissions.isEmpty() || permissions.get().has(perm)) return true;
        c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.needs_permission", perm.node()));
        return false;
    }

    private static Optional<Permissions> permissions(ToolContext c) {
        try {
            return Optional.of(c.session().permissions());
        } catch (IllegalStateException noSession) {
            return Optional.empty();
        }
    }

    private static long saturatingTimes(long cells, long count) {
        return cells > Long.MAX_VALUE / Math.max(1, count) ? Long.MAX_VALUE : cells * count;
    }

    // ---- The fluid ball ----

    private void activateBall() {
        if (ballActive) return;
        ballActive = true;
        ball.activate(ballView);
    }

    private void deactivateBall(DeactivateReason reason) {
        if (!ballActive) return;
        ballActive = false;
        ball.deactivate(ballView, reason);
    }

    /** The ball's material: the fluid source, waterlogging where possible when the switch is on. */
    private Pattern ballMaterial(ToolContext c) {
        SettingsValues values = context.settings();
        int fluid = FluidSettings.fluidSource(values, c.states());
        if (fluid < 0) {
            c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.unknown_block", values.get(FluidSettings.FLUID).block()));
            return null;
        }
        return FluidSettings.ballWaterlogs(values) ? new Pattern.Waterlog(fluid) : new Pattern.Single(fluid);
    }

    /**
     * The inner brush's view of the editor: the Fluid tool's context, with the Shape brush's settings filled from the
     * Fluid settings ({@link FluidSettings#shapeSettings}) and a radius change written back.
     */
    private final class BallView implements ToolContext {
        private ToolContext outer() {
            ToolContext c = context;
            if (c == null) throw new IllegalStateException("The Fluid tool is not active");
            return c;
        }

        @Override
        public EditorSession session() {
            return outer().session();
        }

        @Override
        public StateSpace states() {
            return outer().states();
        }

        @Override
        public WorldReader world() {
            return outer().world();
        }

        @Override
        public SettingsValues settings() {
            return FluidSettings.shapeSettings(outer().settings());
        }

        @Override
        public void updateSettings(SettingsValues values) {
            SettingsValues own = outer().settings();
            int radius = values.get(ShapeSettings.RADIUS);
            if (radius != own.get(FluidSettings.RADIUS)) outer().updateSettings(own.with(FluidSettings.RADIUS, radius));
        }

        @Override
        public Optional<Box> selection() {
            return outer().selection();
        }

        @Override
        public void setSelection(Box box) {
            outer().setSelection(box);
        }

        @Override
        public Optional<Region> selectionRegion() {
            return outer().selectionRegion();
        }

        @Override
        public void setSelectionRegion(Region region) {
            outer().setSelectionRegion(region);
        }

        @Override
        public Optional<Selection> selectionState() {
            return outer().selectionState();
        }

        @Override
        public void setSelectionState(Selection selection) {
            outer().setSelectionState(selection);
        }

        @Override
        public void moveSelection(int dx, int dy, int dz) {
            outer().moveSelection(dx, dy, dz);
        }

        @Override
        public RegionWork regionWork() {
            return outer().regionWork();
        }

        @Override
        public int modifiers() {
            return outer().modifiers();
        }

        @Override
        public void setPointerCapture(boolean captured) {
            outer().setPointerCapture(captured);
        }

        @Override
        public void notify(Notice notice) {
            outer().notify(notice);
        }
    }

    // ---- Drawing and hints ----

    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        context = c;
        SettingsValues values = c.settings();
        if (values.get(FluidSettings.MODE) == FluidSettings.Mode.BALL) {
            ball.renderWorld(ballView, d);
            return;
        }
        Symmetry symmetry = centre.forOp(values.get(FluidSettings.SYMMETRY)).orElse(Symmetry.NONE);
        if (!symmetry.isOff()) {
            double y = cursor.missed() ? 0 : cursor.hitY();
            BrushOutlines.symmetry(d, symmetry, y, 0, OverlayColors.scaleAlpha(COLOUR, 0.7));
        }
        Region.Cells region = previewRegion;
        if (region == null) return;
        // The cells' exact outline (meshed off the render thread; a set too detailed to mesh shows its box alone),
        // inside their bounds drawn dimmer, as the Select tool draws a cell set.
        d.shapeOutlines(List.of(region), COLOUR, OverlayColors.scaleAlpha(COLOUR, 0.7));
        int dim = OverlayColors.scaleAlpha(COLOUR, 0.5);
        d.seeThrough(true);
        d.boxOutline(region.bounds(), dim);
        if (symmetry.isOff()) {
            d.seeThrough(false);
            return;
        }
        // Each copy's bounds, dimmer, as the Select tool shows its copies.
        CellSet cells = region.cells();
        try {
            for (Symmetry.Image image : symmetry.images()) {
                if (image != Symmetry.Image.IDENTITY) d.boxOutline(symmetry.imageBox(image, cells.bounds()), dim);
            }
        } catch (IllegalArgumentException beyondTheWorld) {
            // A copy beyond the coordinate range is not drawn (the server refuses such an op).
        }
        d.seeThrough(false);
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        SettingsValues values = c.settings();
        FluidSettings.Mode mode = values.get(FluidSettings.MODE);
        String fluid = values.get(FluidSettings.FLUID).name().toLowerCase(java.util.Locale.ROOT);
        if (mode == FluidSettings.Mode.BALL) {
            if (ball.controller().pressed()) {
                hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
            } else {
                hints.add(new KeyHint(CLICK, "sculptory.hint.fluid.ball", List.of(fluid)));
            }
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.brush.radius"));
            addSymmetryHints(hints, values);
            return hints;
        }
        boolean flood = mode == FluidSettings.Mode.FLOOD;
        if (job != null) {
            hints.add(KeyHint.text("sculptory.hint.fluid.searching", SelectionModel.count(job.search().count())));
            hints.add(new KeyHint("Esc", "sculptory.hint.fluid.stop"));
            return hints;
        }
        if (result == null) {
            hints.add(KeyHint.text(flood ? "sculptory.hint.fluid.aim_flood" : "sculptory.hint.fluid.aim_drain"));
        } else if (result.count() == 0) {
            hints.add(KeyHint.text(flood ? "sculptory.hint.fluid.nothing_flood" : "sculptory.hint.fluid.nothing_drain"));
        } else {
            int copies = SelectionActions.copies(new Region.Cells(result.cells()),
                    centre.forOp(values.get(FluidSettings.SYMMETRY)).orElse(Symmetry.NONE));
            String count = SelectionModel.count(result.count());
            if (copies > 1) {
                hints.add(new KeyHint(CLICK, flood ? "sculptory.hint.fluid.flood_copies" : "sculptory.hint.fluid.drain_copies",
                        List.of(count, Integer.toString(copies))));
            } else {
                hints.add(new KeyHint(CLICK, flood ? "sculptory.hint.fluid.flood" : "sculptory.hint.fluid.drain",
                        List.of(count)));
            }
            if (result.hitLimit()) hints.add(KeyHint.text("sculptory.hint.fluid.limit", count));
            if (result.hitUnloaded()) hints.add(KeyHint.text("sculptory.hint.fluid.unloaded"));
        }
        addSymmetryHints(hints, values);
        return hints;
    }

    private void addSymmetryHints(List<KeyHint> hints, SettingsValues values) {
        if (values.get(FluidSettings.SYMMETRY) == Symmetry.Mode.OFF) return;
        if (!centre.isSet()) {
            hints.add(KeyHint.text("sculptory.hint.symmetry_centre_needed", services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE)));
        }
        hints.add(new KeyHint(services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE), "sculptory.hint.brush.symmetry_centre"));
    }

    private static FluidSettings.Mode mode(ToolContext c) {
        return c.settings().get(FluidSettings.MODE);
    }
}
