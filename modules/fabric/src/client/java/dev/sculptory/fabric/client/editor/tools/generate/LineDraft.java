package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.generate.GroundMap;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.path.PathSampler;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.render.ghost.GhostMasking;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A line through clicked points, previewed as a ghost and built as one generated paste: Generate's Line and the Shape brush's Line mode, which differ only in what they generate
 * along the path ({@link Host}).
 *
 * <p><b>Points.</b> A click on a block adds a point (the host says which block the click stands for: the Shape brush's
 * anchor may put it outside the block clicked). A click on a point's column selects it and a drag moves it, as the road's
 * nodes. Delete or Backspace removes the selected point, or the last one; Esc stops a drag or a preview being made, then
 * clears the points; Enter builds. The points stay after a build, so the line can be changed and built again.
 *
 * <p><b>Preview.</b> From two points the generator runs off the client thread (it reads no world); a result of an older
 * change is dropped. The ghost shows what the global mask lets through; over {@link GenerateTool#MAX_GHOST_CELLS} the
 * line's bounds are outlined instead. The path is drawn as a line through its samples. Building refuses a line over the
 * cap (the player's clipboard limit), or reaching into chunks this game has not loaded. Client thread only.
 */
public final class LineDraft {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    public static final int POINT_COLOUR = GenerateTool.NODE_COLOUR;
    public static final int SELECTED_COLOUR = GenerateTool.SELECTED_COLOUR;
    /** The path is drawn through samples this far apart (blocks). */
    static final double DRAW_SPACING = 0.5;

    public static final String NEEDS_POINTS = "sculptory.notice.line_needs_points";
    public static final String UNLOADED = "sculptory.notice.line_unloaded";
    public static final String NOTHING = "sculptory.notice.line_nothing";

    /** The line a host makes of its points, with what to build and how to name it; {@code generate} runs off-thread. */
    public record Setup(PathSpec path, Generator generator, OpLabel label, String opNameKey, String sentKey,
                        PasteOptions options) {
        public Setup {
            Objects.requireNonNull(path);
            Objects.requireNonNull(generator);
            Objects.requireNonNull(label);
            Objects.requireNonNull(opNameKey);
            Objects.requireNonNull(sentKey);
            Objects.requireNonNull(options);
        }
    }

    /** Generates the line's cells, at most {@code cap}; pure (it reads no world), so it may run on any thread. */
    @FunctionalInterface
    public interface Generator {
        GeneratedSource generate(long cap) throws GeneratedTooLargeException;
    }

    /** The tool the line belongs to. */
    @FunctionalInterface
    public interface Host {
        /**
         * The line of {@code points} (two or more) with the settings as they are now, or {@code null} when there is
         * nothing to build (after telling the player why, when a setting is the reason).
         */
        Setup setup(ToolContext c, List<BlockPos> points);
    }

    /** A preview: the cells (empty when too large), the ghost (null over the cap or when empty), the path's samples. */
    public record Built(GeneratedSource source, GhostVolume ghost, List<PathSample> samples, boolean tooLarge, long cap,
                        Setup setup) {
        public Built {
            Objects.requireNonNull(source);
            samples = List.copyOf(samples);
        }

        public long cells() {
            return source.cells();
        }
    }

    private final Supplier<GenerateTool.Services> services;
    private final int colour;
    private final PathModel points = new PathModel();
    private final GeneratedCommit commit;
    /** The face the first point was clicked on (the Shape brush's "Clicked face"), or null. */
    private WorldCursor.Face firstFace;
    private int dragging = -1;
    private BlockPos dragFrom;
    private boolean dirty = true;
    private int generation;
    private CompletableFuture<Built> running;
    private int runningGeneration;
    private Built shown;
    private GhostVolume ghost;
    private boolean buildWhenReady;

    /**
     * @param services the ghosts, background work, key names and confirmations (read each time: the Shape brush gets
     *     the game's after it is built)
     * @param colour the path's colour
     */
    public LineDraft(Supplier<GenerateTool.Services> services, int colour) {
        this.services = Objects.requireNonNull(services);
        this.colour = colour;
        this.commit = new GeneratedCommit(new GenerateTool.Services() {
            @Override
            public void showGhosts(List<GhostPlacement> placements) {
                services.get().showGhosts(placements);
            }

            @Override
            public void releaseGhost(GhostVolume volume) {
                services.get().releaseGhost(volume);
            }

            @Override
            public Executor background() {
                return services.get().background();
            }

            @Override
            public String keyLabel(KeyAction action) {
                return services.get().keyLabel(action);
            }

            @Override
            public void confirm(String opNameKey, long blocks, Runnable onConfirm) {
                services.get().confirm(opNameKey, blocks, onConfirm);
            }
        });
    }

    /** The points, in order (for tests and the host). */
    public PathModel points() {
        return points;
    }

    /** The face the first point was clicked on, or null. */
    public WorldCursor.Face firstFace() {
        return firstFace;
    }

    public Optional<Built> preview() {
        return Optional.ofNullable(shown);
    }

    public boolean previewRunning() {
        return running != null;
    }

    /** A setting or the points changed: the preview is made again. */
    public void changed() {
        dirty = true;
        generation++;
    }

    // ---- Input ----

    /**
     * A press adds a point (the block {@code pointOf} gives for the hit) or picks one to drag; returns whether the event
     * was taken.
     */
    public boolean onPointer(ToolContext c, PointerEvent e, Function<WorldCursor, BlockPos> pointOf) {
        WorldCursor cursor = e.cursor();
        switch (e.kind()) {
            case PRESS -> {
                if (e.button() != PointerEvent.LEFT || cursor.missed()) return false;
                BlockPos point = pointOf.apply(cursor);
                int index = points.indexAt(point);
                if (index >= 0) {
                    points.select(index);
                    dragging = index;
                    dragFrom = points.node(index);
                    c.setPointerCapture(true);
                } else {
                    if (points.size() >= PathSpec.MAX_POINTS) {
                        c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.line_max_points",
                                Integer.toString(PathSpec.MAX_POINTS)));
                        return true;
                    }
                    if (points.isEmpty()) firstFace = cursor.face();
                    points.add(point);
                    changed();
                }
                return true;
            }
            case DRAG -> {
                if (e.button() != PointerEvent.LEFT || dragging < 0 || cursor.missed()) return false;
                if (points.move(dragging, pointOf.apply(cursor))) changed();
                return true;
            }
            case RELEASE -> {
                if (e.button() != PointerEvent.LEFT || dragging < 0) return false;
                if (!cursor.missed() && points.move(dragging, pointOf.apply(cursor))) changed();
                endDrag(c);
                return true;
            }
            case MOVE -> {
                return false;
            }
        }
        return false;
    }

    /** Esc, Delete/Backspace and Enter; returns whether the action was taken. */
    public boolean onAction(ToolContext c, EditorAction a, Host host) {
        switch (a) {
            case CANCEL -> {
                if (dragging >= 0) {
                    points.move(dragging, dragFrom);
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
                if (!points.isEmpty()) {
                    points.clear();
                    firstFace = null;
                    changed();
                    return true;
                }
                return false;
            }
            case ERASE_SELECTION, REMOVE_NODE -> {
                if (points.isEmpty()) return false;
                if (dragging >= 0) endDrag(c);
                points.removeSelectedOrLast();
                if (points.isEmpty()) firstFace = null;
                changed();
                return true;
            }
            case COMMIT -> {
                build(c, host);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    /** Once per frame: takes a finished preview, starts one when something changed, sends a build once encoded. */
    public void frame(ToolContext c, Host host) {
        poll(c, host);
        if (dirty && running == null) {
            start(c, host);
            poll(c, host); // an executor that runs at once (tests, tiny work) has the result already
        }
        commit.poll(c);
    }

    /** The tool is put away: drags, previews and a build being encoded are dropped; the points stay. */
    public void deactivate(ToolContext c) {
        endDrag(c);
        drop();
        commit.cancel();
        dirty = true;
    }

    /** The mode or kind left the line: a drag ends, the ghost goes, the points stay (and a build on its way). */
    public void hide(ToolContext c) {
        endDrag(c);
        drop();
        dirty = true;
    }

    /** A build on its way goes out once encoded, whatever mode the tool is in now. */
    public void pollBuild(ToolContext c) {
        commit.poll(c);
    }

    private void endDrag(ToolContext c) {
        if (dragging >= 0) c.setPointerCapture(false);
        dragging = -1;
        dragFrom = null;
    }

    // ---- Previews ----

    private void start(ToolContext c, Host host) {
        dirty = false;
        Supplier<Built> task = task(c, host);
        if (task == null) {
            if (shown != null) drop();
            return;
        }
        runningGeneration = generation;
        running = CompletableFuture.supplyAsync(task, services.get().background());
    }

    private Supplier<Built> task(ToolContext c, Host host) {
        if (points.size() < 2) return null;
        StateSpace states = GenerateTool.statesOrNull(c);
        if (states == null) return null;
        Setup setup = host.setup(c, points.nodes());
        if (setup == null) return null;
        long cap = GenerateTool.bypass(c) ? GenerateTool.BYPASS_PREVIEW_CELLS : GenerateTool.limits(c).maxClipboardVolume();
        return () -> {
            List<PathSample> samples;
            try {
                samples = PathSampler.sample(setup.path(), DRAW_SPACING);
            } catch (IllegalArgumentException tooLong) {
                return new Built(GeneratedSource.empty(), null, List.of(), true, cap, setup);
            }
            try {
                GeneratedSource source = setup.generator().generate(cap);
                GhostVolume baked = source.isEmpty() || source.cells() > GenerateTool.MAX_GHOST_CELLS ? null
                        : GhostBaker.fromSparse(source, states);
                return new Built(source, baked, samples, false, cap, setup);
            } catch (GeneratedTooLargeException e) {
                return new Built(GeneratedSource.empty(), null, samples, true, cap, setup);
            }
        };
    }

    private void poll(ToolContext c, Host host) {
        if (running == null || !running.isDone()) return;
        CompletableFuture<Built> done = running;
        running = null;
        Built built;
        try {
            built = done.getNow(null);
        } catch (CompletionException | CancellationException e) {
            LOG.warn("Sculptory: generating a line failed", e.getCause() != null ? e.getCause() : e);
            built = null;
        }
        if (built == null || runningGeneration != generation) {
            if (built != null && built.ghost() != null) services.get().releaseGhost(built.ghost());
            return;
        }
        show(c, built, host);
    }

    /**
     * Shows a preview. Its ghost was baked off the client thread; the global mask is applied here (client thread), and
     * only when it leaves cells out is the ghost baked again, from what it lets through.
     */
    private void show(ToolContext c, Built built, Host host) {
        GhostVolume next = built.ghost();
        BlockPos origin = built.source().isEmpty() ? null : built.source().bounds().min();
        if (next != null) {
            WorldReader world = GenerateTool.worldOrNull(c);
            StateSpace states = GenerateTool.statesOrNull(c);
            GeneratedSource visible = world == null ? built.source() : GhostMasking.filter(built.source(), world);
            if (visible != built.source()) {
                services.get().releaseGhost(next);
                next = states == null || visible.isEmpty() ? null : GhostBaker.fromSparse(visible, states);
                origin = visible.isEmpty() ? null : visible.bounds().min();
            }
        }
        if (ghost != null && ghost != next) services.get().releaseGhost(ghost);
        ghost = next;
        shown = new Built(built.source(), next, built.samples(), built.tooLarge(), built.cap(), built.setup());
        if (next != null) {
            BlockPos min = origin;
            services.get().showGhosts(List.of(GhostPlacement.of(next, min.x(), min.y(), min.z())));
        } else {
            services.get().showGhosts(List.of());
        }
        if (buildWhenReady) {
            buildWhenReady = false;
            build(c, host);
        }
    }

    private void drop() {
        generation++;
        running = null;
        buildWhenReady = false;
        if (ghost != null) services.get().releaseGhost(ghost);
        ghost = null;
        shown = null;
        services.get().showGhosts(List.of());
    }

    // ---- Building ----

    private void build(ToolContext c, Host host) {
        if (points.size() < 2) {
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
        if (dirty || running != null || shown == null) {
            if (dirty || running == null) start(c, host);
            if (running == null) return;
            poll(c, host);
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
        if (built.source().isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, NOTHING));
            return;
        }
        int unloaded = unloadedChunks(c, built.source());
        if (unloaded > 0) {
            c.notify(Notice.of(Notice.Level.WARNING, UNLOADED, Integer.toString(unloaded)));
            return;
        }
        Setup setup = built.setup();
        commit.send(c, new GeneratedCommit.Request(built.source(), setup.label(), setup.opNameKey(), setup.sentKey(),
                setup.options()));
    }

    /** The chunks the line's cells lie in that this game has not loaded. */
    private static int unloadedChunks(ToolContext c, GeneratedSource source) {
        WorldReader world = GenerateTool.worldOrNull(c);
        if (world == null) return 0;
        LongOpenHashSet seen = new LongOpenHashSet();
        int unloaded = 0;
        for (long key : source.cellSet().sectionKeys()) {
            int cx = BlockBuffer.keyX(key), cz = BlockBuffer.keyZ(key);
            if (seen.add(GroundMap.column(cx, cz)) && !world.isLoaded(cx, cz)) unloaded++;
        }
        return unloaded;
    }

    // ---- Drawing and hints ----

    /**
     * The points, the path through them and, when the ghost can't show it (too many cells, or {@code alwaysOutline}: a carve
     * is air), the line's bounds.
     */
    public void renderWorld(WorldDraw d, boolean alwaysOutline) {
        List<BlockPos> nodes = points.nodes();
        for (int i = 0; i < nodes.size(); i++) {
            Box box = Box.of(nodes.get(i));
            boolean selected = i == points.selected();
            d.boxOutline(box, selected ? SELECTED_COLOUR : POINT_COLOUR);
            if (selected) d.boxFill(box, OverlayColors.scaleAlpha(SELECTED_COLOUR, 0.25));
        }
        Built built = shown;
        if (built == null) return;
        d.seeThrough(true);
        List<PathSample> samples = built.samples();
        for (int i = 1; i < samples.size(); i++) {
            PathSample a = samples.get(i - 1), b = samples.get(i);
            d.line(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), colour);
        }
        d.seeThrough(false);
        if (!built.source().isEmpty() && (built.ghost() == null || alwaysOutline)) {
            Box bounds = built.source().bounds();
            d.seeThrough(true);
            d.boxOutline(bounds, OverlayColors.scaleAlpha(colour, 0.35));
            d.seeThrough(false);
            d.boxOutline(bounds, colour);
        }
    }

    /** The line's key hints; {@code build} names the build ("build the line · %s blocks"). */
    public List<KeyHint> hints(String click, String buildKey) {
        GenerateTool.Services s = services.get();
        List<KeyHint> hints = new ArrayList<>();
        if (points.isEmpty()) {
            hints.add(new KeyHint(click, "sculptory.hint.line.add_point"));
            return hints;
        }
        if (dragging >= 0) {
            hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
            return hints;
        }
        hints.add(new KeyHint(click, "sculptory.hint.line.add_or_select"));
        hints.add(new KeyHint(click + " drag", "sculptory.hint.line.move_point"));
        hints.add(new KeyHint(s.keyLabel(KeyAction.ERASE_SELECTION) + "/" + s.keyLabel(KeyAction.REMOVE_NODE),
                "sculptory.hint.line.remove_point"));
        Built built = shown;
        if (built != null && built.tooLarge()) {
            hints.add(KeyHint.text("sculptory.hint.generate.too_large", SessionNotices.count(built.cap())));
        } else if (built != null && !built.source().isEmpty()) {
            hints.add(new KeyHint(s.keyLabel(KeyAction.COMMIT), buildKey, List.of(SessionNotices.count(built.cells()))));
        }
        hints.add(new KeyHint("Esc", "sculptory.hint.line.clear"));
        return hints;
    }
}
