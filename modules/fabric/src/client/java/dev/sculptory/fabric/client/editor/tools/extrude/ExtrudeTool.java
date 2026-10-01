package dev.sculptory.fabric.client.editor.tools.extrude;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GeneratedTooLargeException;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.render.ghost.GhostPlacement;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolDescriptor;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.brush.BrushOutlines;
import dev.sculptory.fabric.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.fabric.client.editor.tools.place.GhostBaker;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.fabric.client.editor.tools.select.SelectTool;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.tools.select.SelectionModel;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.editor.world.BoxHandles;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.SessionNotices;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.protocol.v2.Limits;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Extrude tool (palette slot 12, key =): the flat face under the cursor lights
 * up ({@link FaceSelect}, found over frames by {@link FaceJob}); dragging away from it pulls the face out by whole
 * layers (one {@code OpSpec.Stack} of the face's cells), dragging into it carves that many layers (one
 * {@code OpSpec.Erase} of the face's layer and those behind it, so the face moves in), Ctrl+drag on a side of the selection does the same with that
 * side's outermost layer of blocks, and Alt+drag smears the face along the drag (one {@code OpSpec.Move} leaving air).
 * A ghost shows the layers; the hint counts them; Esc cancels with nothing sent. Every op goes through
 * {@link SelectionActions#runOn}, so the {@code region} permission, the symmetry centre, the server's volume limit and
 * the confirm dialog are the selection ops'. Client thread only.
 */
public final class ExtrudeTool implements Tool {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    public static final ToolDescriptor DESCRIPTOR =
            new ToolDescriptor(ToolId.EXTRUDE, "sculptory.tool.extrude", "minecraft:piston", Perm.REGION);
    public static final String CLICK = "LMB";
    /** The hovered face's outline and an extrusion's frame. */
    public static final int FACE_COLOUR = 0xFFFFB347;
    /** A carve's frame. */
    public static final int CARVE_COLOUR = 0xFFFF6A5A;
    /** Previews over this many cells are outlined instead of drawn as ghosts (the Place tool's capture cap). */
    public static final long MAX_GHOST_CELLS = PlaceTool.DEFAULT_MAX_CAPTURE_CELLS;
    /** The farthest a smear slides the face, in blocks. */
    public static final int MAX_SMEAR = 256;

    public static final String FACE_LIMIT = "sculptory.notice.extrude_face_limit";
    public static final String FACE_UNLOADED = "sculptory.notice.extrude_unloaded";
    public static final String NOTHING = "sculptory.notice.extrude_nothing";
    public static final String SIDE_EMPTY = "sculptory.notice.extrude_side_empty";
    public static final String SIDE_TOO_LARGE = "sculptory.notice.extrude_side_too_large";
    public static final String CARVE_TOO_LARGE = "sculptory.notice.extrude_carve_too_large";

    /** What the tool needs from the client. */
    public interface Services {
        /** The cursor ray of the event being handled (the crosshair ray while looking). */
        Optional<Ray> cursorRay();

        /** How a keymap action's chord reads, e.g. "M". */
        String keyLabel(KeyAction action);

        /** Shows these ghost previews from now on (an empty list shows none). */
        void showGhosts(List<GhostPlacement> placements);

        /** Frees a volume's meshes now (a volume no longer shown). */
        void releaseGhost(GhostVolume volume);

        /** Where ghost bakes run, off the client thread. */
        Executor background();

        /** The clock the face search's time budget is measured with. */
        default long nanoTime() {
            return System.nanoTime();
        }

        /** Services without Minecraft: no ray, default key names, no ghosts, bakes on the caller's thread. */
        static Services headless() {
            return new Services() {
                @Override
                public Optional<Ray> cursorRay() {
                    return Optional.empty();
                }

                @Override
                public String keyLabel(KeyAction action) {
                    return EditorKeymap.defaults().display(action);
                }

                @Override
                public void showGhosts(List<GhostPlacement> placements) {}

                @Override
                public void releaseGhost(GhostVolume volume) {}

                @Override
                public Executor background() {
                    return Runnable::run;
                }
            };
        }
    }

    /** What a drag does. */
    public enum DragKind {
        /** The hovered face, out (extrude) or in (carve). */
        FACE,
        /** A side of the selection, out or in. */
        SIDE,
        /** The hovered face slid along the drag, leaving air. */
        SMEAR
    }

    /** A face found: its cells (as a region, one instance so the outline's mesh is reused) and their states. */
    public static final class Face {
        private final BoxFace side;
        private final int plane;
        private final Region.Cells region;
        private final long[] cells;
        private final int[] states;
        private final boolean cutAtLimit;
        private final boolean hitUnloaded;

        Face(FaceSelect select) {
            this.side = select.face();
            this.plane = select.plane();
            this.region = new Region.Cells(select.cells());
            this.cells = select.cellArray();
            this.states = select.stateArray();
            this.cutAtLimit = select.hitLimit();
            this.hitUnloaded = select.hitUnloaded();
        }

        /** The side the face is exposed on (its outward normal). */
        public BoxFace side() {
            return side;
        }

        public Region.Cells region() {
            return region;
        }

        public long count() {
            return cells.length;
        }

        public Box bounds() {
            return region.bounds();
        }

        /** Whether the Size setting cut the face short. */
        public boolean cutAtLimit() {
            return cutAtLimit;
        }

        /** Whether the face reached chunks the client doesn't have. */
        public boolean hitUnloaded() {
            return hitUnloaded;
        }
    }

    /** A drag in progress. */
    private final class Drag {
        final DragKind kind;
        final BoxFace side;
        /** Where the drag measures from: a point on the face plane, and its coordinate along the face's axis. */
        final double[] grab;
        final double grabAlong;
        FaceJob job;
        Face face;
        /** Layers out (positive) or in (negative). */
        int layers;
        /** The smear's offset, along one axis. */
        int[] offset = new int[3];
        /** Released before the face was found: commit as soon as it is. */
        boolean releasePending;
        /** The preview: the box the op writes, and the ghost being baked or shown. */
        Box writes;
        long writesCells;
        int generation;
        CompletableFuture<GhostVolume> baking;
        int bakingGeneration;
        GhostVolume ghost;
        Box ghostBounds;

        Drag(DragKind kind, BoxFace side, double[] grab, double grabAlong) {
            this.kind = kind;
            this.side = side;
            this.grab = grab;
            this.grabAlong = grabAlong;
        }
    }

    /** What a hover search is for: the block, the side and the settings. */
    private record HoverKey(BlockPos seed, BoxFace side, SettingsValues settings) {}

    private static final BlockDescriptor STONE = BlockDescriptor.of(new NamespacedId("minecraft:stone"));

    private final Services services;
    private final SelectionActions actions;
    private WorldCursor cursor = WorldCursor.miss(0, 0, 0);
    private HoverKey hoverKey;
    private FaceJob hoverJob;
    private Face hover;
    /** The toasts once per activation. */
    private boolean toldLimit;
    private boolean toldUnloaded;
    private Drag drag;

    /**
     * @param actions the selection ops' sender, shared with the Select tool: its symmetry centre, confirm dialog and
     *                limits are the ones the extrusions run with
     */
    public ExtrudeTool(Services services, SelectionActions actions) {
        this.services = Objects.requireNonNull(services);
        this.actions = Objects.requireNonNull(actions);
    }

    /** A tool without Minecraft (tests, the default tool set): no ghosts, no cursor ray, ops confirmed at once. */
    public static ExtrudeTool headless(SymmetryCentre centre) {
        SelectionActions actions = new SelectionActions(() -> {
            throw new IllegalStateException("The Extrude tool runs its ops on its own context");
        }, () -> STONE, (message, onConfirm) -> onConfirm.run(), Translator.KEYS, () -> 0L, centre,
                action -> EditorKeymap.defaults().display(action));
        return new ExtrudeTool(Services.headless(), actions);
    }

    /** The face under the cursor, once found (for tests and hints). */
    public Optional<Face> hoveredFace() {
        return Optional.ofNullable(hover);
    }

    /** Whether a face search (hover or drag) is still running. */
    public boolean searching() {
        return hoverJob != null || (drag != null && drag.job != null);
    }

    /** The drag in progress, for tests: kind, layers and offset. */
    public Optional<DragKind> dragKind() {
        return Optional.ofNullable(drag).map(d -> d.kind);
    }

    public int dragLayers() {
        return drag == null ? 0 : drag.layers;
    }

    public int[] dragOffset() {
        return drag == null ? new int[3] : drag.offset.clone();
    }

    /** The box the drag's op would write, if a drag with layers or an offset is on. */
    public Optional<Box> preview() {
        return Optional.ofNullable(drag).map(d -> d.writes);
    }

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public SettingsSchema schema() {
        return ExtrudeSettings.SCHEMA;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.BLOCKS;
    }

    @Override
    public void activate(ToolContext c) {
        toldLimit = false;
        toldUnloaded = false;
        hoverKey = null;
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        endDrag(c);
        hoverJob = null;
        hoverKey = null;
        hover = null;
    }

    @Override
    public void onSettingsChanged(ToolContext c, SettingsValues before, SettingsValues after) {
        hoverKey = null; // the face is found again with the new settings (a drag keeps the face it began with)
    }

    // ---- Frames ----

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        cursor = f.cursor();
        if (drag != null) {
            runDragSearch(c);
            pollBake();
            return;
        }
        hover(c);
    }

    /** Keeps the hovered face current: a new block, side or settings start a new search; slices run each frame. */
    private void hover(ToolContext c) {
        HoverKey key = cursor.missed() ? null : new HoverKey(cursor.pos(), side(cursor.face()), c.settings());
        if (!Objects.equals(key, hoverKey)) {
            hoverKey = key;
            hover = null;
            hoverJob = key == null ? null : new FaceJob(FaceSelect.connected(world(c), key.seed(), key.side(),
                    key.settings().get(ExtrudeSettings.MATCH), key.settings().get(ExtrudeSettings.DIAGONALS),
                    faceLimit(c)));
        }
        if (hoverJob != null && hoverJob.run(FaceJob.FRAME_BUDGET_NANOS, services::nanoTime)) {
            FaceSelect done = hoverJob.select();
            hoverJob = null;
            hover = done.count() == 0 ? null : new Face(done);
            tell(c, done);
        }
    }

    /** The Size setting, capped by the most cells the server takes in a selection sent to it. */
    private static long faceLimit(ToolContext c) {
        return Math.max(1, Math.min(c.settings().get(ExtrudeSettings.SIZE), limits(c).maxSelectionCells()));
    }

    /** The most cells a selection's side (or a carve) may hold: what the server takes in one selection. */
    private static long selectionCap(ToolContext c) {
        return Math.max(1, Math.min(SelectSettings.MAX_CELLS, limits(c).maxSelectionCells()));
    }

    /** The face search's toasts, once per activation each. */
    private void tell(ToolContext c, FaceSelect done) {
        if (done.hitLimit() && !toldLimit) {
            toldLimit = true;
            c.notify(Notice.of(Notice.Level.INFO, FACE_LIMIT, SessionNotices.count(done.count())));
        }
        if (done.hitUnloaded() && !toldUnloaded) {
            toldUnloaded = true;
            c.notify(Notice.of(Notice.Level.INFO, FACE_UNLOADED));
        }
    }

    // ---- Pointer ----

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        cursor = e.cursor();
        return switch (e.kind()) {
            case MOVE -> false;
            case PRESS -> e.button() == PointerEvent.LEFT && press(c, e);
            case DRAG -> {
                if (e.button() != PointerEvent.LEFT || drag == null) yield false;
                update(c, e.modifiers());
                yield true;
            }
            case RELEASE -> {
                if (e.button() != PointerEvent.LEFT || drag == null) yield false;
                update(c, e.modifiers());
                if (drag.face == null) {
                    drag.releasePending = true; // the face is still being found: it goes out once it is
                } else {
                    commit(c);
                }
                yield true;
            }
        };
    }

    private boolean press(ToolContext c, PointerEvent e) {
        if (drag != null) return true;
        if (Modifiers.control(e.modifiers()) && pressSide(c)) return true; // an empty side ends the press too
        if (cursor.missed()) return false;
        hover(c); // a press right after a move: the search starts (and small faces finish) now
        if (hoverJob == null && hover == null) {
            c.notify(Notice.of(Notice.Level.INFO, NOTHING));
            return true;
        }
        BoxFace side = side(cursor.face());
        double[] grab = {cursor.hitX(), cursor.hitY(), cursor.hitZ()};
        Ray ray = services.cursorRay().orElse(null);
        double grabAlong = along(ray, grab, side.axis()).orElse(grab[side.axis()]);
        drag = new Drag(Modifiers.alt(e.modifiers()) ? DragKind.SMEAR : DragKind.FACE, side, grab, grabAlong);
        drag.job = hoverJob;
        drag.face = hover;
        hoverJob = null;
        c.setPointerCapture(true);
        return true;
    }

    /**
     * Ctrl+press on the selection's bounds: the face nearest the press (as the Select tool's handles pick it) becomes
     * the side to extrude; its outermost layer of blocks is found over frames (a side without blocks ends the press
     * with a toast). False when there is no selection or the ray misses it: the press then works on the face under the
     * cursor.
     */
    private boolean pressSide(ToolContext c) {
        Optional<Selection> state = c.selectionState();
        Optional<Ray> ray = services.cursorRay();
        if (state.isEmpty() || ray.isEmpty()) return false;
        Box bounds = state.get().bounds();
        Optional<BoxHandles.Pick> pick = BoxHandles.pick(ray.get(), SelectionModel.toAabb(bounds));
        if (pick.isEmpty()) return false;
        BoxFace side = pick.get().face();
        double t = pick.get().t();
        double[] grab = {ray.get().pointX(t), ray.get().pointY(t), ray.get().pointZ(t)};
        double grabAlong = along(ray.get(), grab, side.axis()).orElse(grab[side.axis()]);
        drag = new Drag(DragKind.SIDE, side, grab, grabAlong);
        drag.job = new FaceJob(FaceSelect.layer(world(c), state.get().region(), side, selectionCap(c)));
        c.setPointerCapture(true);
        runDragSearch(c);
        return true;
    }

    /** Runs the drag's face search for this frame's budget; a found face starts the preview (or ends an empty drag). */
    private void runDragSearch(ToolContext c) {
        if (drag == null || drag.job == null || !drag.job.run(FaceJob.FRAME_BUDGET_NANOS, services::nanoTime)) return;
        FaceSelect done = drag.job.select();
        drag.job = null;
        if (drag.kind == DragKind.SIDE && done.hitLimit()) {
            c.notify(Notice.of(Notice.Level.WARNING, SIDE_TOO_LARGE, SessionNotices.count(selectionCap(c))));
            endDrag(c);
            return;
        }
        if (done.count() == 0) {
            c.notify(Notice.of(Notice.Level.INFO, drag.kind == DragKind.SIDE ? SIDE_EMPTY : NOTHING));
            endDrag(c);
            return;
        }
        tell(c, done);
        drag.face = new Face(done);
        updatePreview(c);
        if (drag.releasePending) commit(c);
    }

    /**
     * The drag's layers (or smear offset) from the cursor ray, in whole blocks; the preview follows a change. A smear
     * slides along the face (the axis across it the cursor moved farthest along), or with Shift along its normal: a
     * 2D drag can't tell "up the normal" from "back along the plane" at most view angles, so Shift chooses.
     */
    private void update(ToolContext c, int modifiers) {
        Ray ray = services.cursorRay().orElse(null);
        if (ray == null || drag == null) return;
        int axis = drag.side.axis();
        OptionalDouble now = along(ray, drag.grab, axis);
        int alongAxis = now.isPresent() ? blocks(now.getAsDouble() - drag.grabAlong) : 0;
        if (drag.kind == DragKind.SMEAR) {
            int[] snapped = new int[3];
            if (Modifiers.shift(modifiers)) {
                snapped[axis] = now.isPresent() ? alongAxis : drag.offset[axis];
            } else {
                double[] onPlane = SelectionModel.intersectPlane(ray, axis, drag.grab[axis]);
                if (onPlane == null) return;
                int dominant = -1;
                for (int other = 0; other < 3; other++) {
                    if (other == axis) continue;
                    int moved = blocks(onPlane[other] - drag.grab[other]);
                    if (dominant < 0 || Math.abs(moved) > Math.abs(snapped[dominant])) {
                        snapped = new int[3];
                        snapped[other] = moved;
                        dominant = other;
                    }
                }
            }
            for (int i = 0; i < 3; i++) snapped[i] = Math.max(-MAX_SMEAR, Math.min(MAX_SMEAR, snapped[i]));
            if (!Arrays.equals(snapped, drag.offset)) {
                drag.offset = snapped;
                updatePreview(c);
            }
            return;
        }
        if (now.isEmpty()) return;
        int max = c.settings().get(ExtrudeSettings.MAX_LAYERS);
        int layers = Math.max(-max, Math.min(max, alongAxis * drag.side.sign()));
        if (layers != drag.layers) {
            drag.layers = layers;
            updatePreview(c);
        }
    }

    /** Where the ray passes closest to the line through {@code point} along {@code axis}, as that axis' coordinate. */
    private static OptionalDouble along(Ray ray, double[] point, int axis) {
        if (ray == null) return OptionalDouble.empty();
        return SelectionModel.closestOnAxis(ray, point[0], point[1], point[2], axis);
    }

    /** A drag distance in whole blocks, cut well within int range (a grazing ray meets the axis far away). */
    private static int blocks(double distance) {
        if (!(Math.abs(distance) < (1 << 20))) return distance < 0 ? -(1 << 20) : 1 << 20;
        return (int) Math.round(distance);
    }

    // ---- Committing ----

    /** Sends the drag's op (nothing for no layers or no offset), then ends the drag; the face is found again after. */
    private void commit(ToolContext c) {
        Drag done = drag;
        endDrag(c);
        hoverKey = null;
        Face face = done.face;
        Symmetry.Mode mode = c.settings().get(ExtrudeSettings.SYMMETRY);
        switch (done.kind) {
            case FACE, SIDE -> {
                if (done.layers > 0) {
                    BoxFace side = done.side;
                    int count = done.layers;
                    actions.runOn(c, SelectionActions.Op.EXTRUDE, face.region(), face.count() * count, mode,
                            () -> Optional.of(new OpSpec.Stack(face.region(), side.normalX(), side.normalY(), side.normalZ(),
                                    count, EntityFilter.NONE)));
                } else if (done.layers < 0) {
                    int depth = -done.layers;
                    long cells = face.count() * depth;
                    if (cells > selectionCap(c)) {
                        c.notify(Notice.of(Notice.Level.WARNING, CARVE_TOO_LARGE, SessionNotices.count(selectionCap(c))));
                        return;
                    }
                    Region.Cells carved = new Region.Cells(carveCells(face, depth));
                    actions.runOn(c, SelectionActions.Op.CARVE, carved, carved.cellCount(), mode,
                            () -> Optional.of(new OpSpec.Erase(carved, CellMask.ANY)));
                }
            }
            case SMEAR -> {
                int[] offset = done.offset;
                if (offset[0] == 0 && offset[1] == 0 && offset[2] == 0) return;
                int air = c.states().air();
                actions.runOn(c, SelectionActions.Op.SMEAR, face.region(), face.count(), mode,
                        () -> Optional.of(new OpSpec.Move(face.region(), new BlockPos(offset[0], offset[1], offset[2]),
                                Transform.IDENTITY, new Pattern.Single(air), EntityFilter.NONE)));
            }
        }
    }

    /**
     * The {@code depth} layers a carve clears, so the face ends up {@code depth} blocks further in: each face cell
     * shifted 0..depth-1 against its side (the face's own blocks and the layers behind them).
     */
    static CellSet carveCells(Face face, int depth) {
        CellSet.Builder builder = CellSet.builder();
        BoxFace side = face.side;
        for (long cell : face.cells) {
            int x = FaceSelect.cellX(cell);
            int y = FaceSelect.cellY(cell);
            int z = FaceSelect.cellZ(cell);
            for (int k = 0; k < depth; k++) {
                builder.add(x - k * side.normalX(), y - k * side.normalY(), z - k * side.normalZ());
            }
        }
        return builder.build();
    }

    /** Ends the drag (nothing more is sent); the face under the cursor is found again from the next frame. */
    private void endDrag(ToolContext c) {
        if (drag == null) return;
        Drag done = drag;
        drag = null;
        hoverKey = null;
        done.generation++;
        done.baking = null;
        if (done.ghost != null) services.releaseGhost(done.ghost);
        services.showGhosts(List.of());
        c.setPointerCapture(false);
    }

    @Override
    public boolean onAction(ToolContext c, EditorAction a) {
        switch (a) {
            case CANCEL -> {
                if (drag == null) return false;
                endDrag(c);
                return true;
            }
            case SET_SYMMETRY_CENTRE -> {
                actions.symmetryCentre().keyPressed(c, cursor, c.settings().get(ExtrudeSettings.SYMMETRY) != Symmetry.Mode.OFF);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ---- Previews ----

    /** Works out what the drag writes and bakes its ghost off the client thread (a stale bake is dropped). */
    private void updatePreview(ToolContext c) {
        Drag d = drag;
        d.generation++;
        d.writes = null;
        d.writesCells = 0;
        if (d.face == null) return;
        Face face = d.face;
        BoxFace side = d.side;
        Box bounds = face.bounds();
        switch (d.kind) {
            case FACE, SIDE -> {
                if (d.layers == 0) break;
                // Out: the layers 1..N beyond the face. In: the face's own layer and those behind it, 0..-(N-1).
                int first = d.layers > 0 ? 1 : 0;
                int last = d.layers > 0 ? d.layers : d.layers + 1;
                d.writes = union(bounds.offset(first * side.normalX(), first * side.normalY(), first * side.normalZ()),
                        bounds.offset(last * side.normalX(), last * side.normalY(), last * side.normalZ()));
                d.writesCells = face.count() * Math.abs(d.layers);
            }
            case SMEAR -> {
                if (d.offset[0] == 0 && d.offset[1] == 0 && d.offset[2] == 0) break;
                d.writes = union(bounds, bounds.offset(d.offset[0], d.offset[1], d.offset[2]));
                d.writesCells = face.count() * 2;
            }
        }
        if (d.writes == null || d.writesCells > MAX_GHOST_CELLS) {
            showGhost(d, null);
            return;
        }
        StateSpace states = c.states();
        int layers = d.layers;
        int[] offset = d.offset.clone();
        DragKind kind = d.kind;
        int generation = d.generation;
        d.bakingGeneration = generation;
        d.baking = CompletableFuture.supplyAsync(() -> {
            GeneratedSource source = previewSource(face, kind, layers, offset, states.air(), d.writesCells);
            return source == null ? null : GhostBaker.fromSparse(source, states);
        }, services.background());
        pollBake();
    }

    /** The cells a drag writes with their states: the copied layers, the carved cells as air, or the moved face. */
    static GeneratedSource previewSource(Face face, DragKind kind, int layers, int[] offset, int air, long cap) {
        BoxFace side = face.side;
        GeneratedSource.Builder builder = GeneratedSource.builder(cap);
        try {
            if (kind == DragKind.SMEAR) {
                for (int i = 0; i < face.cells.length; i++) {
                    long cell = face.cells[i];
                    builder.set(FaceSelect.cellX(cell) + offset[0], FaceSelect.cellY(cell) + offset[1],
                            FaceSelect.cellZ(cell) + offset[2], face.states[i]);
                }
                for (long cell : face.cells) {
                    int x = FaceSelect.cellX(cell);
                    int y = FaceSelect.cellY(cell);
                    int z = FaceSelect.cellZ(cell);
                    if (!builder.has(x, y, z)) builder.set(x, y, z, air);
                }
                return builder.build();
            }
            // Out: copies at 1..N beyond the face. In: air at 0..N-1 behind it (the cells carveCells erases).
            int first = layers > 0 ? 1 : 0;
            int last = layers > 0 ? layers : -layers - 1;
            int direction = layers > 0 ? 1 : -1;
            for (int i = 0; i < face.cells.length; i++) {
                long cell = face.cells[i];
                int x = FaceSelect.cellX(cell);
                int y = FaceSelect.cellY(cell);
                int z = FaceSelect.cellZ(cell);
                int state = layers > 0 ? face.states[i] : air;
                for (int k = first; k <= last; k++) {
                    builder.set(x + direction * k * side.normalX(), y + direction * k * side.normalY(),
                            z + direction * k * side.normalZ(), state);
                }
            }
            return builder.build();
        } catch (GeneratedTooLargeException | IllegalArgumentException beyondTheCapOrTheWorld) {
            return null;
        }
    }

    /** Takes a finished bake, if it is still the one wanted. */
    private void pollBake() {
        Drag d = drag;
        if (d == null || d.baking == null || !d.baking.isDone()) return;
        CompletableFuture<GhostVolume> done = d.baking;
        d.baking = null;
        GhostVolume volume;
        try {
            volume = done.getNow(null);
        } catch (CompletionException | CancellationException e) {
            LOG.warn("Sculptory: baking an extrusion preview failed", e.getCause() != null ? e.getCause() : e);
            volume = null;
        }
        if (d.bakingGeneration != d.generation) {
            if (volume != null) services.releaseGhost(volume);
            return;
        }
        showGhost(d, volume);
    }

    private void showGhost(Drag d, GhostVolume volume) {
        if (d.ghost != null && d.ghost != volume) services.releaseGhost(d.ghost);
        d.ghost = volume;
        d.ghostBounds = volume == null ? null : d.writes;
        if (volume == null || d.writes == null) {
            services.showGhosts(List.of());
            return;
        }
        BlockPos min = d.writes.min();
        services.showGhosts(List.of(GhostPlacement.of(volume, min.x(), min.y(), min.z())));
    }

    /** The smallest box holding both. */
    static Box union(Box a, Box b) {
        return new Box(new BlockPos(Math.min(a.min().x(), b.min().x()), Math.min(a.min().y(), b.min().y()),
                Math.min(a.min().z(), b.min().z())), new BlockPos(Math.max(a.max().x(), b.max().x()),
                Math.max(a.max().y(), b.max().y()), Math.max(a.max().z(), b.max().z())));
    }

    // ---- Drawing and hints ----

    /**
     * The hovered (or dragged) face's exact outline, the box the drag writes while there is no ghost for it (too large,
     * or still baking), the symmetry centre and planes, and each copy's box.
     */
    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        Face face = drag != null ? drag.face : hover;
        if (face != null) {
            d.shapeOutlines(List.of(face.region()), FACE_COLOUR, FACE_COLOUR);
        }
        Box writes = drag == null ? null : drag.writes;
        if (writes != null && (drag.ghost == null || drag.ghostBounds != writes)) {
            d.seeThrough(true);
            d.boxOutline(writes, drag.kind != DragKind.SMEAR && drag.layers < 0 ? CARVE_COLOUR : FACE_COLOUR);
            d.seeThrough(false);
        }
        renderSymmetry(c, d, writes != null ? writes : face != null ? face.bounds() : null);
    }

    private void renderSymmetry(ToolContext c, WorldDraw d, Box bounds) {
        Symmetry symmetry = actions.symmetryCentre().forOp(c.settings().get(ExtrudeSettings.SYMMETRY)).orElse(Symmetry.NONE);
        if (symmetry.isOff()) return;
        double y = bounds != null ? bounds.min().y() : cursor.missed() ? 0.0 : cursor.hitY();
        int reach = bounds != null ? Math.max(bounds.sizeX(), bounds.sizeZ()) / 2 : 0;
        BrushOutlines.symmetry(d, symmetry, y, reach, SelectTool.SYMMETRY_COLOUR);
        if (bounds == null) return;
        int dim = OverlayColors.scaleAlpha(SelectTool.SYMMETRY_COLOUR, 0.5);
        d.seeThrough(true);
        try {
            for (Symmetry.Image image : symmetry.images()) {
                if (image != Symmetry.Image.IDENTITY) d.boxOutline(symmetry.imageBox(image, bounds), dim);
            }
        } catch (IllegalArgumentException beyondTheWorld) {
            // A copy beyond the coordinate range is not drawn (the server refuses such an op).
        }
        d.seeThrough(false);
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        Symmetry.Mode mode = c.settings().get(ExtrudeSettings.SYMMETRY);
        if (drag != null) {
            if (drag.face == null) {
                hints.add(KeyHint.text("sculptory.hint.extrude.finding"));
            } else if (drag.writes == null) {
                hints.add(KeyHint.text("sculptory.hint.extrude.choose"));
            } else {
                long blocks = drag.kind == DragKind.SMEAR ? drag.face.count() : drag.writesCells;
                int copies = actions.symmetryCentre().forOp(mode).map(symmetry ->
                        SelectionActions.copies(drag.face.region(), symmetry)).orElse(1);
                String amount = drag.kind == DragKind.SMEAR ? Integer.toString(smearDistance(drag.offset))
                        : Integer.toString(Math.abs(drag.layers));
                String key = drag.kind == DragKind.SMEAR ? "sculptory.hint.extrude.smearing"
                        : drag.layers > 0 ? "sculptory.hint.extrude.extruding" : "sculptory.hint.extrude.carving";
                hints.add(copies > 1
                        ? KeyHint.text(key + "_copies", amount, SessionNotices.count(blocks), Integer.toString(copies))
                        : KeyHint.text(key, amount, SessionNotices.count(blocks)));
                if (drag.writesCells > MAX_GHOST_CELLS) hints.add(KeyHint.text("sculptory.hint.extrude.no_ghost"));
            }
            hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
            return hints;
        }
        if (hover != null) {
            hints.add(KeyHint.text(hover.cutAtLimit() ? "sculptory.hint.extrude.face_cut" : "sculptory.hint.extrude.face",
                    SessionNotices.count(hover.count())));
        } else if (hoverJob != null) {
            hints.add(KeyHint.text("sculptory.hint.extrude.finding"));
        } else {
            hints.add(KeyHint.text("sculptory.hint.extrude.aim"));
        }
        hints.add(new KeyHint(CLICK + " drag", "sculptory.hint.extrude.drag"));
        if (c.selectionState().isPresent()) hints.add(new KeyHint("Ctrl+drag", "sculptory.hint.extrude.side"));
        hints.add(new KeyHint("Alt+drag", "sculptory.hint.extrude.smear"));
        if (mode != Symmetry.Mode.OFF) {
            if (!actions.symmetryCentre().isSet()) {
                hints.add(KeyHint.text("sculptory.hint.symmetry_centre_needed", services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE)));
            }
            hints.add(new KeyHint(services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE), "sculptory.hint.brush.symmetry_centre"));
        }
        return hints;
    }

    private static int smearDistance(int[] offset) {
        return Math.abs(offset[0]) + Math.abs(offset[1]) + Math.abs(offset[2]);
    }

    // ---- Helpers ----

    /** The cursor's face as a box face (the same names in both). */
    static BoxFace side(WorldCursor.Face face) {
        return switch (face) {
            case DOWN -> BoxFace.DOWN;
            case UP -> BoxFace.UP;
            case NORTH -> BoxFace.NORTH;
            case SOUTH -> BoxFace.SOUTH;
            case WEST -> BoxFace.WEST;
            case EAST -> BoxFace.EAST;
        };
    }

    private static WorldReader world(ToolContext c) {
        return c.world();
    }

    private static Limits limits(ToolContext c) {
        try {
            return c.session().permissions().limits();
        } catch (IllegalStateException noSession) {
            return Limits.DEFAULTS;
        }
    }
}
