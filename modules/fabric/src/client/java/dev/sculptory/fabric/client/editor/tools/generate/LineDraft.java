package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.generate.GroundFill;
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
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.editor.tools.points.PointEditor;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.server.engine.Perm;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
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
 * A line through clicked points, previewed as a ghost and built as one generated paste: Generate's Line and Sweep, the
 * Shape brush's Line mode and Extrude's Along a path and To a point, which differ only in what they generate along the
 * path ({@link Host}).
 *
 * <p><b>Points</b> are edited with the shared {@link PointEditor}: a click on a block adds a point (the host says which
 * block the click stands for: the Shape brush's anchor may put it outside the block clicked), a click on a point selects
 * it, its gizmo's arrows, squares and centre move it, a click on the drawn line inserts one, E adds one after the
 * selected point, Backspace removes the selected (or last) point. Esc drops a point being inserted, puts a dragged point
 * back or deselects, then stops a preview being made, then clears the points; Enter builds. The points stay after a
 * build, so the line can be changed and built again. With {@link #withAirPoints} a click on open sky puts a point at the
 * air marker ({@link AirPoints}); without it (the Shape brush's Line) a click on the sky does nothing.
 *
 * <p><b>Preview.</b> Once it has its points (two, for a line) the generator runs off the client thread (it reads no world); a result of an older
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

    /**
     * How many points the line takes: a preview and a build need {@code minPoints} (else {@code needsPointsKey} is
     * toasted on Enter), and a click past {@code maxPoints} is refused, or, when the line takes one point only, moves
     * it there.
     */
    public record Rules(int minPoints, int maxPoints, String needsPointsKey) {
        /** Generate's Line and the Shape brush's: two points or more. */
        public static final Rules LINE = new Rules(PathSpec.MIN_POINTS, PathSpec.MAX_POINTS, NEEDS_POINTS);

        public Rules {
            Objects.requireNonNull(needsPointsKey);
            if (minPoints < 1 || maxPoints < minPoints) throw new IllegalArgumentException("Points " + minPoints + "-" + maxPoints);
        }
    }

    /**
     * The line a host makes of its points, with what to build and how to name it; {@code generate} runs off-thread.
     *
     * @param toGround Extend to Ground: every column the line occupies is filled down to the terrain below it, each fill
     *     cell's state from this ({@link GroundFill}; the ground is read on the client thread between two steps off it);
     *     null for none
     * @param finish a last change to the cells, after the fill (Use Stairs and Slabs); pure, run off the client thread;
     *     null for none
     */
    public record Setup(PathSpec path, Generator generator, OpLabel label, String opNameKey, String sentKey,
                        PasteOptions options, GroundFill.Fill toGround, Finisher finish) {
        public Setup {
            Objects.requireNonNull(path);
            Objects.requireNonNull(generator);
            Objects.requireNonNull(label);
            Objects.requireNonNull(opNameKey);
            Objects.requireNonNull(sentKey);
            Objects.requireNonNull(options);
        }

        /** A line without Extend to Ground. */
        public Setup(PathSpec path, Generator generator, OpLabel label, String opNameKey, String sentKey,
                     PasteOptions options) {
            this(path, generator, label, opNameKey, sentKey, options, null, null);
        }
    }

    /** The last change to a line's cells (the same cells, new states); pure, so it may run on any thread. */
    @FunctionalInterface
    public interface Finisher {
        GeneratedSource finish(GeneratedSource source);
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
    private final Rules rules;
    private final PathModel points = new PathModel();
    private final GeneratedCommit commit;
    /** The face the first point was clicked on (the Shape brush's "Clicked face"), or null. */
    private WorldCursor.Face firstFace;
    /** The air marker (the host's shared one with {@link #withAirPoints}, else this draft's own, unused for clicks). */
    private AirPoints air = new AirPoints();
    private boolean sky;
    private PointEditor editor;
    /** What a click on a block stands for, as the host said with its last event. */
    private Function<WorldCursor, BlockPos> pointOf = WorldCursor::pos;
    /** The cursor of the last pointer event (the face a first point was clicked on). */
    private WorldCursor lastCursor;
    private boolean dirty = true;
    private int generation;
    private CompletableFuture<Built> running;
    /** Extend to Ground's first step (the line, before the ground under it is read), or null. */
    private CompletableFuture<Lined> lining;
    private int runningGeneration;
    private Built shown;
    private GhostVolume ghost;
    private boolean buildWhenReady;

    /**
     * A line of {@link Rules#LINE}'s points.
     *
     * @param services the ghosts, background work, key names and confirmations (read each time: the Shape brush gets
     *     the game's after it is built)
     * @param colour the path's colour
     */
    public LineDraft(Supplier<GenerateTool.Services> services, int colour) {
        this(services, colour, Rules.LINE);
    }

    /** A line whose points follow {@code rules}. */
    public LineDraft(Supplier<GenerateTool.Services> services, int colour, Rules rules) {
        this.services = Objects.requireNonNull(services);
        this.colour = colour;
        this.rules = Objects.requireNonNull(rules);
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
        this.editor = newEditor();
    }

    private PointEditor newEditor() {
        PointEditor.View view = GenerateTool.pointView(services);
        return new PointEditor(points, air, view, new PointEditor.Host() {
            @Override
            public BlockPos pointOn(WorldCursor cursor) {
                return pointOf.apply(cursor);
            }

            @Override
            public int maxPoints() {
                return rules.maxPoints();
            }

            @Override
            public boolean takesSky() {
                return sky;
            }

            @Override
            public List<List<PathSample>> drawnSegments(List<BlockPos> nodes, boolean closed) {
                return LineDraft.this.drawnSegments(nodes);
            }

            @Override
            public void edited(ToolContext c, PointEditor.Edit edit) {
                if (!edit.changesPoints()) return;
                if (points.isEmpty()) {
                    firstFace = null;
                } else if (edit.kind() == PointEditor.EditKind.ADD && points.size() == 1 && lastCursor != null) {
                    firstFace = lastCursor.face();
                }
                changed();
            }
        });
    }

    /**
     * Lets the points go in the air ({@link AirPoints}, shared by the host's drafts): a click on open sky puts one at the
     * marker and a press picks a point floating there. Without it (the Shape brush's Line) a click on the sky does
     * nothing. Returns this draft.
     */
    public LineDraft withAirPoints(AirPoints air) {
        this.air = Objects.requireNonNull(air);
        this.sky = true;
        this.editor = newEditor();
        return this;
    }

    /** The points, in order (for tests and the host). */
    public PathModel points() {
        return points;
    }

    /** The point editor of these points (for tests and the host). */
    public PointEditor editor() {
        return editor;
    }

    /** The face the first point was clicked on, or null. */
    public WorldCursor.Face firstFace() {
        return firstFace;
    }

    public Optional<Built> preview() {
        return Optional.ofNullable(shown);
    }

    public boolean previewRunning() {
        return running != null || lining != null;
    }

    /** A setting or the points changed: the preview is made again. */
    public void changed() {
        dirty = true;
        generation++;
    }

    /**
     * The drawn line split at the points: the shown preview's samples when made for these points (with the stretch from
     * an anchor before the first point first, when the line starts there: Extrude's face), else chords.
     */
    private List<List<PathSample>> drawnSegments(List<BlockPos> nodes) {
        Built built = shown;
        if (built == null) return PointEditor.chords(nodes, false);
        List<BlockPos> drawn = built.setup().path().points();
        boolean anchored = drawn.size() == nodes.size() + 1 && drawn.subList(1, drawn.size()).equals(nodes);
        if (!anchored && !drawn.equals(nodes)) return PointEditor.chords(nodes, false);
        return PointEditor.splitAtPoints(built.samples(), drawn);
    }

    // ---- Input ----

    /**
     * A pointer event for the point editor ({@code pointOf}: the block a click on a block stands for); returns whether
     * it was taken.
     */
    public boolean onPointer(ToolContext c, PointerEvent e, Function<WorldCursor, BlockPos> pointOf) {
        this.pointOf = Objects.requireNonNull(pointOf);
        lastCursor = e.cursor();
        return editor.onPointer(c, e);
    }

    /**
     * Alt+Scroll: the air marker nearer or farther, or, while a point is dragged freely, that point. Returns whether the
     * scroll was taken.
     */
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        return editor.onScroll(c, e);
    }

    /** Whether a point is being dragged. */
    public boolean dragging() {
        return editor.dragging();
    }

    /** Esc, Delete/Backspace, the insert key and Enter; returns whether the action was taken. */
    public boolean onAction(ToolContext c, EditorAction a, Host host) {
        if (editor.onAction(c, a)) return true;
        switch (a) {
            case CANCEL -> {
                if (running != null || lining != null) {
                    generation++;
                    running = null;
                    lining = null;
                    dirty = false;
                    buildWhenReady = false;
                    return true;
                }
                if (!points.isEmpty()) {
                    editor.clear(c);
                    firstFace = null;
                    return true;
                }
                return false;
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

    /**
     * Once per frame: the point editor (marker, guide line, hovering, a held point following the camera), then takes a
     * finished preview, starts one when something changed, sends a build once encoded.
     */
    public void frame(ToolContext c, FrameInfo f, Host host, Function<WorldCursor, BlockPos> pointOf) {
        this.pointOf = Objects.requireNonNull(pointOf);
        editor.frame(c, f);
        poll(c, host);
        if (dirty && !previewRunning()) {
            start(c, host);
            poll(c, host); // an executor that runs at once (tests, tiny work) has the result already
        }
        commit.poll(c);
    }

    /** The tool is put away: drags, previews and a build being encoded are dropped; the points stay. */
    public void deactivate(ToolContext c) {
        editor.deactivate(c);
        drop();
        commit.cancel();
        dirty = true;
    }

    /** The mode or kind left the line: a drag ends, the ghost goes, the points stay (and a build on its way). */
    public void hide(ToolContext c) {
        editor.deactivate(c);
        drop();
        dirty = true;
    }

    /** A build on its way goes out once encoded, whatever mode the tool is in now. */
    public void pollBuild(ToolContext c) {
        commit.poll(c);
    }

    // ---- Previews ----

    private void start(ToolContext c, Host host) {
        dirty = false;
        Job job = job(c, host);
        if (job == null) {
            if (shown != null) drop();
            return;
        }
        runningGeneration = generation;
        if (job.setup.toGround() == null) {
            running = CompletableFuture.supplyAsync(job::built, services.get().background());
        } else {
            lining = CompletableFuture.supplyAsync(job::lined, services.get().background());
        }
    }

    private Job job(ToolContext c, Host host) {
        if (points.size() < rules.minPoints()) return null;
        StateSpace states = GenerateTool.statesOrNull(c);
        if (states == null) return null;
        Setup setup = host.setup(c, points.nodes());
        if (setup == null) return null;
        long cap = GenerateTool.bypass(c) ? GenerateTool.BYPASS_PREVIEW_CELLS : GenerateTool.limits(c).maxClipboardVolume();
        WorldReader world = GenerateTool.worldOrNull(c);
        return new Job(setup, states, cap, world == null ? Integer.MIN_VALUE : world.bottomY());
    }

    /** One preview's work, off the client thread except for {@link #poll}'s look at the ground (Extend to Ground). */
    private static final class Job {
        final Setup setup;
        final StateSpace states;
        final long cap;
        final int bottomY;

        Job(Setup setup, StateSpace states, long cap, int bottomY) {
            this.setup = setup;
            this.states = states;
            this.cap = cap;
            this.bottomY = bottomY;
        }

        /** The line, its ghost and its samples. */
        Built built() {
            List<PathSample> samples;
            try {
                samples = PathSampler.sample(setup.path(), DRAW_SPACING);
            } catch (IllegalArgumentException tooLong) {
                return tooLarge(List.of());
            }
            try {
                return baked(finished(setup.generator().generate(cap)), samples);
            } catch (GeneratedTooLargeException e) {
                return tooLarge(samples);
            }
        }

        /** Extend to Ground, first step: the line and each column's lowest cell (the ground is read next, in poll). */
        Lined lined() {
            List<PathSample> samples;
            try {
                samples = PathSampler.sample(setup.path(), DRAW_SPACING);
            } catch (IllegalArgumentException tooLong) {
                return new Lined(this, null, null, tooLarge(List.of()), List.of());
            }
            try {
                GeneratedSource line = setup.generator().generate(cap);
                return new Lined(this, line, GroundFill.lowest(line), null, samples);
            } catch (GeneratedTooLargeException e) {
                return new Lined(this, null, null, tooLarge(samples), samples);
            }
        }

        /** Extend to Ground, last step: the line filled down to the ground read, and its ghost. */
        Built extended(Lined lined, GroundMap ground) {
            try {
                GeneratedSource filled = GroundFill.extend(lined.line, lined.lowest, ground, setup.toGround().carried(),
                        setup.toGround().state(), cap, bottomY);
                return baked(finished(filled), lined.samples);
            } catch (GeneratedTooLargeException e) {
                return tooLarge(lined.samples);
            }
        }

        private GeneratedSource finished(GeneratedSource source) {
            return setup.finish() == null ? source : setup.finish().finish(source);
        }

        private Built baked(GeneratedSource source, List<PathSample> samples) {
            GhostVolume baked = source.isEmpty() || source.cells() > GenerateTool.MAX_GHOST_CELLS ? null
                    : GhostBaker.fromSparse(source, states);
            return new Built(source, baked, samples, false, cap, setup);
        }

        private Built tooLarge(List<PathSample> samples) {
            return new Built(GeneratedSource.empty(), null, samples, true, cap, setup);
        }
    }

    /** Extend to Ground between its steps: the line and its columns' lowest cells, or the preview when it ends here. */
    private record Lined(Job job, GeneratedSource line, Long2IntMap lowest, Built done, List<PathSample> samples) {}

    private void poll(ToolContext c, Host host) {
        if (lining != null && lining.isDone()) {
            CompletableFuture<Lined> done = lining;
            lining = null;
            Lined lined;
            try {
                lined = done.getNow(null);
            } catch (CompletionException | CancellationException e) {
                LOG.warn("Sculptory: generating a line failed", e.getCause() != null ? e.getCause() : e);
                lined = null;
            }
            if (lined == null || runningGeneration != generation) return;
            if (lined.done() != null) {
                show(c, lined.done(), host);
                return;
            }
            // The ground under each column is read here, on the client thread; the fill is made off it.
            WorldReader world = GenerateTool.worldOrNull(c);
            GroundMap ground = world == null ? new GroundMap()
                    : GroundFill.groundUnder(lined.lowest(), world, lined.job().cap - lined.line().cells());
            if (ground == null) {
                show(c, lined.job().tooLarge(lined.samples()), host); // the fill alone passes the cap
                return;
            }
            Lined next = lined;
            running = CompletableFuture.supplyAsync(() -> next.job().extended(next, ground), services.get().background());
        }
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
        lining = null;
        buildWhenReady = false;
        if (ghost != null) services.get().releaseGhost(ghost);
        ghost = null;
        shown = null;
        services.get().showGhosts(List.of());
    }

    // ---- Building ----

    private void build(ToolContext c, Host host) {
        if (points.size() < rules.minPoints()) {
            c.notify(Notice.of(Notice.Level.INFO, rules.needsPointsKey()));
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
        if (dirty || previewRunning() || shown == null) {
            if (dirty || !previewRunning()) start(c, host);
            if (!previewRunning()) return;
            poll(c, host);
            poll(c, host); // Extend to Ground's second step, when an executor that runs at once made the first
            if (previewRunning()) {
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
     * The points (with the selected point's gizmo, the guide line and the air marker), the path through them and, when
     * the ghost can't show it (too many cells, or {@code alwaysOutline}: a carve is air), the line's bounds.
     */
    public void renderWorld(WorldDraw d, boolean alwaysOutline) {
        Built built = shown;
        if (built != null) {
            d.seeThrough(true);
            List<PathSample> samples = built.samples();
            for (int i = 1; i < samples.size(); i++) {
                PathSample a = samples.get(i - 1), b = samples.get(i);
                d.line(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), colour);
            }
            d.seeThrough(false);
        }
        editor.render(d, POINT_COLOUR, SELECTED_COLOUR);
        if (built != null && !built.source().isEmpty() && (built.ghost() == null || alwaysOutline)) {
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
            hints.add(new KeyHint(click, sky ? "sculptory.hint.line.add_point_air" : "sculptory.hint.line.add_point"));
            if (sky) hints.add(air.hint());
            return hints;
        }
        if (editor.busy()) {
            hints.addAll(editor.hints(click, s::keyLabel));
            if (sky) hints.add(air.hint());
            return hints;
        }
        hints.add(new KeyHint(click, "sculptory.hint.line.add_or_select"));
        hints.addAll(editor.hints(click, s::keyLabel));
        if (sky) hints.add(air.hint());
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
