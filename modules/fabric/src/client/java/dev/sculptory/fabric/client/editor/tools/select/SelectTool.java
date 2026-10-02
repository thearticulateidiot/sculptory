package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.RegionTooLargeException;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.settings.SettingsSchema;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.HudDraw;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
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
import dev.sculptory.fabric.client.editor.tools.brush.GradientDrag;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.editor.world.BoxHandles;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Permissions;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Tool 1: the selection. In <b>Box</b> mode, left-drag on terrain draws a box between the two blocks (or the shape the
 * settings choose, inscribed in it); drag a face handle to resize; Ctrl-drag inside the bounds to move the selection;
 * Shift+click adds a block; Ctrl+Scroll grows the face under the cursor (or every face). In <b>Magic</b> mode a click
 * selects the connected blocks that match the clicked one ({@link MagicSelect}, spread over frames by
 * {@link MagicJob}), Shift+click adds them and Alt+click removes them; handles and Ctrl-drag work as in Box mode. In
 * both, arrows and PgUp/PgDn nudge, Delete erases, Ctrl+D deselects and Esc cancels a drag or a running magic select.
 * In <b>Brush</b> mode a drag paints spheres of blocks into the selection ({@link BrushSelect}; Alt+drag removes
 * them; Ctrl+Scroll sets the radius), and in <b>Lasso</b> mode a drag draws a loop on the ground whose inside, some
 * layers high, is selected on release ({@link LassoSelect}; Shift adds, Alt removes; Alt+Scroll sets the height).
 * Both make cell sets, as magic select does.
 *
 * <p>A box or shape keeps its kind and facing when resized or moved; a cell set (magic select) can move but not be
 * resized: handles and Ctrl+Scroll don't apply, and Shift+click in Box mode starts a new box. The ops themselves live
 * in {@link SelectionActions}.
 *
 * <p>While Fill uses the palette with the Gradient pattern, the Gradient's
 * line (shared with the brushes) shows as an arrow, and in Box mode Alt+drag draws it ({@link GradientDrag}) without
 * touching the selection; in the other modes Alt keeps removing, and the hint says to draw the line in Box mode.
 */
public final class SelectTool implements Tool {
    public static final ToolDescriptor DESCRIPTOR =
            new ToolDescriptor(ToolId.SELECT, "sculptory.tool.select", "minecraft:wooden_axe", Perm.REGION);

    /** What the editor supplies beyond {@link ToolContext}. */
    public interface Services {
        /** The cursor ray of the event being handled (the crosshair ray while looking). */
        Optional<Ray> cursorRay();

        /** Projects world points into scaled GUI pixels, from the last rendered frame. */
        Optional<ScreenProjector> projector();

        /** The camera position of the last rendered frame, as {x, y, z}. */
        Optional<double[]> eye();

        /** The camera yaw in degrees (Minecraft convention). */
        float cameraYaw();

        /** Highlights a face handle of the drawn selection (null for none). */
        void setHoveredFace(BoxFace face);

        /** How a keymap action's chord reads, e.g. "Delete". */
        String keyLabel(KeyAction action);

        /** The clock magic select's time budget is measured with. */
        default long nanoTime() {
            return System.nanoTime();
        }

        /** Why the world shows the selection simplified (a hint translation key), if it does. */
        default Optional<String> outlineNote() {
            return Optional.empty();
        }
    }

    /** Cells of a box or shape that may be listed on the client thread when adding to it or removing from it. */
    static final long INLINE_CELLS = 262_144;
    private static final int[] AXIS_COLORS = {0xFFFF7A7A, 0xFF7AE38F, 0xFF7AB4FF};
    /** The selection brush's sphere cursor and the lasso's loop: the selection's white, a little translucent. */
    static final int FREEFORM_COLOR = 0xCCFFFFFF;
    /** The Gradient line's arrow (Fill with a Gradient palette): the Palette Paint brush's violet. */
    static final int GRADIENT_COLOR = 0xFFA58BF2;
    /** How far above the lasso's plane its loop is drawn, so it isn't lost in the top faces. */
    private static final double LASSO_LIFT = 0.03;

    private final SelectionActions actions;
    private final Services services;
    private final SelectionDrag drag = new SelectionDrag();
    /** Fill's Gradient line: Alt+drag in Box mode while Fill uses the palette with the Gradient pattern. */
    private final GradientDrag gradient;
    /** The selection before the drag (Esc puts it back), and the box or shape being resized. */
    private Selection dragBefore;
    private Region dragRegion;
    private MagicJob magic;
    /** The brush drag in progress, and which of its toasts were shown. */
    private BrushSelect brush;
    private boolean brushToldLimit;
    private boolean brushToldUnloaded;
    /** The lasso drag in progress and what its loop does with the selection. */
    private LassoSelect lasso;
    private MagicJob.Combine lassoCombine;
    /** The last cursor seen (the brush cursor and the symmetry centre key use it). */
    private WorldCursor cursor = WorldCursor.miss(0, 0, 0);

    public SelectTool(SelectionActions actions, Services services) {
        this.actions = Objects.requireNonNull(actions);
        this.services = Objects.requireNonNull(services);
        this.gradient = new GradientDrag(actions.gradientLine());
    }

    public SelectionActions actions() {
        return actions;
    }

    /** Whether Fill lays its palette out along the Gradient line: the arrow is drawn then. */
    static boolean showsGradientLine(SettingsValues values) {
        return values.get(SelectSettings.FILL_WITH) == SelectSettings.FillWith.PALETTE
                && SelectSettings.PATTERN.gradient(values);
    }

    /**
     * Whether Alt+drag draws the Gradient line: while the arrow is shown, in Box mode (in Magic, Brush and Lasso mode
     * Alt removes from the selection, so the line is drawn in Box mode or in Palette Paint or the Shape brush).
     */
    static boolean drawsGradientLine(SettingsValues values) {
        return showsGradientLine(values) && values.get(SelectSettings.MODE) == SelectSettings.Mode.BOX;
    }

    /** The drag in progress, for tests and the HUD. */
    public SelectionDrag drag() {
        return drag;
    }

    /** The magic select still searching, if any. */
    public Optional<MagicSelect> magicSelect() {
        return Optional.ofNullable(magic).map(MagicJob::fill);
    }

    /** The brush drag in progress, if any (for tests). */
    Optional<BrushSelect> brushSelect() {
        return Optional.ofNullable(brush);
    }

    /** The lasso drag in progress, if any (for tests). */
    Optional<LassoSelect> lassoSelect() {
        return Optional.ofNullable(lasso);
    }

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public SettingsSchema schema() {
        return SelectSettings.SCHEMA;
    }

    @Override
    public RaycastMode raycastMode(SettingsValues s) {
        return RaycastMode.BLOCKS;
    }

    @Override
    public void deactivate(ToolContext c, DeactivateReason r) {
        gradient.cancel(c);
        finishDrag(c);
        magic = null;
        dropFreeform();
        c.setPointerCapture(false);
        services.setHoveredFace(null);
    }

    @Override
    public void onSettingsChanged(ToolContext c, SettingsValues before, SettingsValues after) {
        if (before.get(SelectSettings.MODE) != after.get(SelectSettings.MODE)) {
            magic = null;
            if (brush != null || lasso != null) {
                c.setSelectionState(dragBefore);
                dragBefore = null;
                dropFreeform();
                c.setPointerCapture(false);
            }
        }
        if (brush != null && before.get(SelectSettings.BRUSH_RADIUS) != after.get(SelectSettings.BRUSH_RADIUS)) {
            brush.setRadius(after.get(SelectSettings.BRUSH_RADIUS));
        }
        if (before.get(SelectSettings.SHAPE) != after.get(SelectSettings.SHAPE)
                || before.get(SelectSettings.AXIS) != after.get(SelectSettings.AXIS)
                || before.get(SelectSettings.FACING) != after.get(SelectSettings.FACING)) {
            actions.reshape();
        }
    }

    @Override
    public void frame(ToolContext c, FrameInfo f) {
        if (f != null) cursor = f.cursor();
        runMagic(c);
        if (f != null) {
            cursor = f.cursor();
            // The cursor moves without mouse events while flying: the brush and the lasso follow it every frame.
            if (brush != null) {
                brushTo(c, cursor);
            } else if (lasso != null) {
                lassoTo();
            }
        }
    }

    // ---- Pointer ----

    @Override
    public boolean onPointer(ToolContext c, PointerEvent e) {
        cursor = e.cursor();
        return switch (e.kind()) {
            case MOVE -> {
                updateHover(c);
                yield false;
            }
            case PRESS -> e.button() == PointerEvent.LEFT
                    && (drawsGradientLine(c.settings()) && gradient.press(c, e) || press(c, e));
            case DRAG -> {
                if (e.button() != PointerEvent.LEFT) {
                    yield false;
                }
                if (gradient.active()) {
                    gradient.drag(e);
                    yield true;
                }
                if (brush != null) {
                    brushTo(c, e.cursor());
                    yield true;
                }
                if (lasso != null) {
                    lassoTo();
                    yield true;
                }
                if (!drag.isActive()) {
                    yield false;
                }
                dragTo(c, e.cursor());
                yield true;
            }
            case RELEASE -> {
                if (e.button() != PointerEvent.LEFT) {
                    yield false;
                }
                if (gradient.active()) {
                    gradient.release(c, e);
                    yield true;
                }
                if (brush != null) {
                    finishBrush(c, e.cursor());
                    yield true;
                }
                if (lasso != null) {
                    finishLasso(c);
                    yield true;
                }
                if (!drag.isActive()) {
                    yield false;
                }
                finishDrag(c);
                updateHover(c);
                yield true;
            }
        };
    }

    private boolean press(ToolContext c, PointerEvent e) {
        Optional<Selection> state = c.selectionState();
        WorldCursor cursor = e.cursor();
        SelectSettings.Mode mode = c.settings().get(SelectSettings.MODE);
        boolean magicMode = mode == SelectSettings.Mode.MAGIC;
        boolean brushMode = mode == SelectSettings.Mode.BRUSH;
        boolean lassoMode = mode == SelectSettings.Mode.LASSO;
        boolean shift = Modifiers.shift(e.modifiers());
        boolean alt = Modifiers.alt(e.modifiers());
        if (!magicMode && !brushMode && !lassoMode && shift) {
            if (!cursor.missed()) {
                c.setSelectionRegion(state.filter(Selection::resizable)
                        .map(current -> SelectionModel.withBounds(current.region(),
                                SelectionModel.grow(current.bounds(), cursor.pos())))
                        .orElseGet(() -> SelectSettings.region(Box.of(cursor.pos()), c.settings())));
            }
            return true;
        }
        // As in Box mode, Shift (and in Magic and Lasso mode Alt, in Brush mode Alt) choose what a click adds or
        // removes, before any handle.
        boolean chord = (magicMode || lassoMode) && (shift || alt) || (brushMode && alt);
        if (!chord && grabSelection(c, e, state)) {
            return true;
        }
        if (cursor.missed()) {
            return true;
        }
        if (magicMode) {
            startMagic(c, cursor.pos(), shift ? MagicJob.Combine.ADD
                    : alt ? MagicJob.Combine.SUBTRACT : MagicJob.Combine.REPLACE);
            return true;
        }
        if (brushMode) {
            startBrush(c, cursor.pos(), alt ? BrushSelect.Combine.REMOVE : BrushSelect.Combine.ADD);
            return true;
        }
        if (lassoMode) {
            startLasso(c, cursor, shift ? MagicJob.Combine.ADD
                    : alt ? MagicJob.Combine.SUBTRACT : MagicJob.Combine.REPLACE);
            return true;
        }
        dragBefore = state.orElse(null);
        Box box = drag.beginCreate(state.map(Selection::bounds).orElse(null), cursor.pos());
        c.setSelectionRegion(SelectSettings.region(box, c.settings()));
        c.setPointerCapture(true);
        return true;
    }

    /**
     * Ctrl+press inside the bounds starts a move; a press on a box's or shape's handle starts a resize. A magic select
     * still searching is dropped: the drag decides the selection now.
     */
    private boolean grabSelection(ToolContext c, PointerEvent e, Optional<Selection> state) {
        Optional<Ray> ray = services.cursorRay();
        if (state.isEmpty() || ray.isEmpty()) {
            return false;
        }
        Box bounds = state.get().bounds();
        Optional<BoxHandles.Pick> pick = BoxHandles.pick(ray.get(), SelectionModel.toAabb(bounds));
        if (pick.isEmpty()) {
            return false;
        }
        boolean started = false;
        if (Modifiers.control(e.modifiers())) {
            started = drag.beginMove(bounds, pick.get().face(), ray.get());
        } else if (pick.get().part() == BoxHandles.Part.HANDLE && state.get().resizable()) {
            started = drag.beginResize(bounds, pick.get().face(), ray.get());
        }
        if (!started) {
            return false;
        }
        magic = null;
        dragBefore = state.get();
        dragRegion = drag.mode() == SelectionDrag.Mode.RESIZE ? state.get().region() : null;
        c.setPointerCapture(true);
        services.setHoveredFace(drag.face());
        return true;
    }

    private void dragTo(ToolContext c, WorldCursor cursor) {
        switch (drag.mode()) {
            case CREATE -> {
                if (!cursor.missed()) {
                    c.setSelectionRegion(SelectSettings.region(drag.updateCreate(cursor.pos()), c.settings()));
                }
            }
            case RESIZE -> services.cursorRay().ifPresent(ray ->
                    c.setSelectionRegion(SelectionModel.withBounds(dragRegion, drag.updateResize(ray))));
            case MOVE -> services.cursorRay().ifPresent(ray -> {
                // Only the offset moves (cheap for a cell set too); the cells move once, when something needs them.
                BlockPos to = drag.updateMove(ray).min();
                c.selectionState().ifPresent(state -> {
                    BlockPos at = state.bounds().min();
                    c.moveSelection(saturate((long) to.x() - at.x()), saturate((long) to.y() - at.y()),
                            saturate((long) to.z() - at.z()));
                });
            });
            case IDLE -> {
            }
        }
    }

    private static int saturate(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }

    private void updateHover(ToolContext c) {
        if (drag.isActive()) {
            services.setHoveredFace(drag.face());
            return;
        }
        boolean handles = c.selectionState().map(Selection::resizable).orElse(false);
        services.setHoveredFace(handles ? pickFace(c, true).orElse(null) : null);
    }

    /** The face under the cursor: only handles when {@code handlesOnly}, else any face of the bounds. */
    private Optional<BoxFace> pickFace(ToolContext c, boolean handlesOnly) {
        Optional<Box> box = c.selection();
        Optional<Ray> ray = services.cursorRay();
        if (box.isEmpty() || ray.isEmpty()) {
            return Optional.empty();
        }
        return BoxHandles.pick(ray.get(), SelectionModel.toAabb(box.get()))
                .filter(pick -> !handlesOnly || pick.part() == BoxHandles.Part.HANDLE)
                .map(BoxHandles.Pick::face);
    }

    // ---- Magic select ----

    private void startMagic(ToolContext c, BlockPos seed, MagicJob.Combine combine) {
        SettingsValues settings = c.settings();
        magic = new MagicJob(new MagicSelect(c.world(), seed, settings.get(SelectSettings.MATCH),
                settings.get(SelectSettings.CONNECT), limit(c)), combine);
        runMagic(c); // small selections finish at once
    }

    /** Runs the magic select for this frame's time budget, and applies it once it is done. */
    private void runMagic(ToolContext c) {
        if (magic != null && magic.run(MagicJob.FRAME_BUDGET_NANOS, services::nanoTime)) {
            MagicJob done = magic;
            magic = null;
            apply(c, done);
        }
    }

    /**
     * The limit setting, capped by the server's op limit unless the player may pass it, and always by the most blocks the
     * server takes in a selection sent to it (a larger magic selection could not be used).
     */
    static long limit(ToolContext c) {
        long limit = c.settings().get(SelectSettings.LIMIT);
        Permissions permissions = c.session().permissions();
        if (!permissions.has(Perm.LIMIT_BYPASS)) {
            limit = Math.min(limit, permissions.limits().maxOpVolume());
        }
        limit = Math.min(limit, permissions.limits().maxSelectionCells());
        return Math.max(1, limit);
    }

    /**
     * A finished magic select: the found blocks become, join or leave the selection, and the player hears why the
     * search stopped. Joining or leaving a box or shape needs its cells, and a moved cell set its moved cells: that work
     * runs off the client thread unless it is small, and its result replaces the selection only if the selection is
     * still the one it started from.
     */
    private void apply(ToolContext c, MagicJob job) {
        MagicSelect fill = job.fill();
        if (fill.count() == 0) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.magic_nothing"));
            return;
        }
        combine(c, fill.cells(), job.combine());
        if (fill.hitLimit()) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.magic_limit", SelectionModel.count(fill.count())));
        }
        if (fill.hitUnloaded()) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.magic_unloaded"));
        }
    }

    /**
     * The cells found (by a magic select or a lasso) become, join or leave the selection, as {@link #apply} describes
     * ({@code found} is not empty).
     */
    private void combine(ToolContext c, CellSet found, MagicJob.Combine combine) {
        Optional<Selection> current = c.selectionState();
        if (combine == MagicJob.Combine.REPLACE || current.isEmpty()) {
            if (combine != MagicJob.Combine.SUBTRACT) {
                c.setSelectionRegion(new Region.Cells(found));
            }
        } else if (!RegionWork.countable(current.get().base())) {
            tooLarge(c);
        } else if (cheapToCombine(c, current.get())) {
            try {
                finishCombine(c, combined(current.get().region(), found, combine));
            } catch (RegionTooLargeException tooLarge) {
                tooLarge(c);
            }
        } else {
            Selection before = current.get();
            c.regionWork().supply(() -> combined(before.region(), found, combine)).whenComplete((result, failure) -> {
                if (c.selectionState().orElse(null) != before) {
                    return; // the selection changed meanwhile: this result no longer applies
                }
                if (failure != null) {
                    tooLarge(c);
                } else {
                    finishCombine(c, result);
                }
            });
        }
    }

    /** Whether combining with {@code current} is quick enough for the client thread. */
    private static boolean cheapToCombine(ToolContext c, Selection current) {
        if (!current.resizable()) {
            return Arrays.equals(current.offset(), new int[3]); // a cell set that needs no move: set operations only
        }
        OptionalLong cells = c.regionWork().countNow(current.region());
        return cells.isPresent() && cells.getAsLong() <= INLINE_CELLS;
    }

    /**
     * The cells of {@code current} with {@code found} added or removed.
     *
     * @throws RegionTooLargeException past {@link SelectSettings#MAX_CELLS}
     */
    static CellSet combined(Region current, CellSet found, MagicJob.Combine combine) {
        CellSet cells = CellSet.of(current, SelectSettings.MAX_CELLS);
        CellSet result = combine == MagicJob.Combine.SUBTRACT ? cells.subtract(found) : cells.union(found);
        if (result.size() > SelectSettings.MAX_CELLS) {
            throw new RegionTooLargeException(result.size(), SelectSettings.MAX_CELLS);
        }
        return result;
    }

    private static void finishCombine(ToolContext c, CellSet result) {
        c.setSelectionRegion(result.isEmpty() ? null : new Region.Cells(result));
        if (result.isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.magic_emptied"));
        }
    }

    private static void tooLarge(ToolContext c) {
        c.notify(Notice.of(Notice.Level.WARNING, "sculptory.notice.selection_too_large",
                SelectionModel.count(SelectSettings.MAX_CELLS)));
    }

    // ---- Brush select ----

    /**
     * The most cells the brush or a lasso may put in the selection: {@link SelectSettings#MAX_CELLS} and the most the
     * server takes in a selection sent to it (as magic select is capped).
     */
    static long cap(ToolContext c) {
        return Math.max(1, Math.min(SelectSettings.MAX_CELLS, c.session().permissions().limits().maxSelectionCells()));
    }

    /**
     * A press in Brush mode: the selection becomes cells (a large box or shape is listed off the client thread while
     * the drag goes on), then the first sphere is painted at the pressed block. A magic select still searching is
     * dropped. Removing from no selection does nothing.
     */
    private void startBrush(ToolContext c, BlockPos at, BrushSelect.Combine combine) {
        Optional<Selection> state = c.selectionState();
        if (state.isEmpty() && combine == BrushSelect.Combine.REMOVE) {
            return;
        }
        if (state.isPresent() && !RegionWork.countable(state.get().base())) {
            tooLarge(c);
            return;
        }
        SettingsValues settings = c.settings();
        BrushSelect next = new BrushSelect(c.world(), settings.get(SelectSettings.BRUSH_RADIUS),
                settings.get(SelectSettings.SOLID_ONLY), combine, cap(c));
        if (state.isEmpty()) {
            next.setBase(CellSet.empty());
        } else if (cheapToCombine(c, state.get())) {
            try {
                next.setBase(CellSet.of(state.get().region(), SelectSettings.MAX_CELLS));
            } catch (RegionTooLargeException tooLarge) {
                tooLarge(c);
                return;
            }
        } else {
            Selection before = state.get();
            c.regionWork().supply(() -> CellSet.of(before.region(), SelectSettings.MAX_CELLS)).whenComplete((base, failure) -> {
                if (next.cancelled() || c.selectionState().orElse(null) != before) {
                    return; // Esc, or the selection changed meanwhile: what was painted no longer applies
                }
                if (failure != null) {
                    abandonBrush(c, next);
                    return;
                }
                next.setBase(base);
                if (next.overCap()) {
                    abandonBrush(c, next);
                    return;
                }
                publishBrush(c, next);
                if (brush != next) {
                    tellBrush(c, next); // released before the base landed: the toasts come now
                }
            });
        }
        magic = null;
        dragBefore = state.orElse(null);
        brush = next;
        brushToldLimit = false;
        brushToldUnloaded = false;
        c.setPointerCapture(true);
        services.setHoveredFace(null);
        brush.dragTo(at.x(), at.y(), at.z());
        publishBrush(c, brush);
        tellBrush(c, brush);
    }

    /** The drag reached {@code cursor}: paints there (and along the way), shows the result and any toast. */
    private void brushTo(ToolContext c, WorldCursor at) {
        if (at.missed()) {
            return;
        }
        BlockPos pos = at.pos();
        brush.dragTo(pos.x(), pos.y(), pos.z());
        publishBrush(c, brush);
        tellBrush(c, brush);
    }

    private void finishBrush(ToolContext c, WorldCursor at) {
        brushTo(c, at);
        brush = null;
        dragBefore = null;
        c.setPointerCapture(false);
        updateHover(c);
    }

    /** Shows what the brush has painted, once its base is known. */
    private static void publishBrush(ToolContext c, BrushSelect brush) {
        if (!brush.baseKnown() || !brush.changed()) {
            return;
        }
        CellSet result = brush.result();
        c.setSelectionRegion(result.isEmpty() ? null : new Region.Cells(result));
    }

    /** Each of the brush's toasts once per drag, when it first applies. */
    private void tellBrush(ToolContext c, BrushSelect brush) {
        if (brush.hitLimit() && !brushToldLimit) {
            brushToldLimit = true;
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.select_limit", SelectionModel.count(brush.count())));
        }
        if (brush.hitUnloaded() && !brushToldUnloaded) {
            brushToldUnloaded = true;
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.select_unloaded"));
        }
    }

    /** A brush whose base could not be listed, or went over the cap with it: the selection stays as it was. */
    private void abandonBrush(ToolContext c, BrushSelect abandoned) {
        tooLarge(c);
        if (brush == abandoned) {
            c.setSelectionState(dragBefore);
            dragBefore = null;
            brush = null;
            c.setPointerCapture(false);
        }
    }

    // ---- Lasso select ----

    /** A press in Lasso mode: the loop starts where the cursor's ray meets the plane over the pressed block. */
    private void startLasso(ToolContext c, WorldCursor at, MagicJob.Combine combine) {
        BlockPos pos = at.pos();
        double planeY = pos.y() + 1.0;
        double[] point = services.cursorRay().map(ray -> SelectionModel.intersectPlane(ray, 1, planeY)).orElse(null);
        if (point == null) {
            point = at.face() == WorldCursor.Face.UP ? new double[] {at.hitX(), planeY, at.hitZ()}
                    : new double[] {pos.x() + 0.5, planeY, pos.z() + 0.5};
        }
        magic = null;
        dragBefore = c.selectionState().orElse(null);
        lasso = new LassoSelect(pos.y(), point[0], point[2]);
        lassoCombine = combine;
        c.setPointerCapture(true);
        services.setHoveredFace(null);
    }

    /** The loop follows the cursor's ray on the lasso's plane. */
    private void lassoTo() {
        services.cursorRay().ifPresent(ray -> {
            double[] point = SelectionModel.intersectPlane(ray, 1, lasso.planeY());
            if (point != null) {
                lasso.addPoint(point[0], point[2]);
            }
        });
    }

    /**
     * The loop closes: its cells (a large loop is rasterised off the client thread) become, join or leave the
     * selection as a magic select's do.
     */
    private void finishLasso(ToolContext c) {
        lassoTo();
        LassoSelect done = lasso;
        MagicJob.Combine combine = lassoCombine;
        lasso = null;
        dragBefore = null;
        c.setPointerCapture(false);
        updateHover(c);
        if (!done.closed()) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.lasso_nothing"));
            return;
        }
        int height = c.settings().get(SelectSettings.LASSO_HEIGHT);
        int bottomY = c.world().bottomY();
        int topY = c.world().topYExclusive();
        long cap = cap(c);
        if (done.atMost(height, bottomY, topY) <= INLINE_CELLS) {
            applyLasso(c, done.rasterise(height, bottomY, topY, cap), combine);
            return;
        }
        Selection before = c.selectionState().orElse(null);
        c.regionWork().supply(() -> done.rasterise(height, bottomY, topY, cap)).whenComplete((result, failure) -> {
            if (c.selectionState().orElse(null) != before) {
                return; // the selection changed meanwhile: this loop no longer applies
            }
            if (failure != null) {
                tooLarge(c);
            } else {
                applyLasso(c, result, combine);
            }
        });
    }

    private void applyLasso(ToolContext c, LassoSelect.Result result, MagicJob.Combine combine) {
        if (result.cells().isEmpty()) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.lasso_nothing"));
            return;
        }
        combine(c, result.cells(), combine);
        if (result.hitLimit()) {
            c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.select_limit",
                    SelectionModel.count(result.cells().size())));
        }
    }

    /** Forgets a brush or lasso drag (a base still being listed for the brush is ignored when it lands). */
    private void dropFreeform() {
        if (brush != null) {
            brush.cancel();
            brush = null;
        }
        lasso = null;
    }

    /** The symmetry centre and copies, the brush's sphere at the cursor, and the lasso's loop while it is drawn. */
    @Override
    public void renderWorld(ToolContext c, WorldDraw d) {
        renderSymmetry(c, d);
        if (showsGradientLine(c.settings())) {
            gradient.render(d, GRADIENT_COLOR);
        }
        if (lasso != null) {
            double y = lasso.planeY() + LASSO_LIFT;
            int points = lasso.size();
            d.seeThrough(true);
            for (int i = 0; i < points; i++) {
                int next = (i + 1) % points;
                if (next == i) {
                    break;
                }
                d.line(lasso.pointX(i), y, lasso.pointZ(i), lasso.pointX(next), y, lasso.pointZ(next), FREEFORM_COLOR);
            }
            d.seeThrough(false);
            return;
        }
        if (c.settings().get(SelectSettings.MODE) == SelectSettings.Mode.BRUSH && !cursor.missed()) {
            int radius = brush != null ? brush.radius() : c.settings().get(SelectSettings.BRUSH_RADIUS);
            d.shapeOutlines(List.of(BrushSelect.sphere(cursor.pos(), radius)), FREEFORM_COLOR, FREEFORM_COLOR);
        }
    }

    // ---- Scroll and keys ----

    /**
     * Ctrl+Scroll grows (or shrinks) the face under the cursor, or every face; Shift makes steps of 4. A cell set
     * isn't resized (the scroll is taken, so it doesn't change the fly speed).
     */
    @Override
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        if (e.amount() != 0) {
            SelectSettings.Mode mode = c.settings().get(SelectSettings.MODE);
            int direction = (e.amount() > 0 ? 1 : -1) * (Modifiers.shift(e.modifiers()) ? 4 : 1);
            if (mode == SelectSettings.Mode.BRUSH && Modifiers.control(e.modifiers())) {
                SettingsValues values = c.settings();
                int radius = Math.max(SelectSettings.MIN_BRUSH_RADIUS, Math.min(SelectSettings.MAX_BRUSH_RADIUS,
                        values.get(SelectSettings.BRUSH_RADIUS) + direction));
                c.updateSettings(values.with(SelectSettings.BRUSH_RADIUS, radius));
                return true;
            }
            if (mode == SelectSettings.Mode.LASSO && Modifiers.alt(e.modifiers())) {
                SettingsValues values = c.settings();
                int height = Math.max(SelectSettings.MIN_LASSO_HEIGHT, Math.min(SelectSettings.MAX_LASSO_HEIGHT,
                        values.get(SelectSettings.LASSO_HEIGHT) + direction));
                c.updateSettings(values.with(SelectSettings.LASSO_HEIGHT, height));
                return true;
            }
        }
        if (!Modifiers.control(e.modifiers()) || e.amount() == 0) {
            return false;
        }
        Optional<Selection> state = c.selectionState();
        if (state.isEmpty() || drag.isActive() || !state.get().resizable()) {
            return state.isPresent();
        }
        int amount = (e.amount() > 0 ? 1 : -1) * (Modifiers.shift(e.modifiers()) ? 4 : 1);
        Box box = state.get().bounds();
        Optional<BoxFace> face = pickFace(c, false);
        c.setSelectionRegion(SelectionModel.withBounds(state.get().region(), face.isPresent()
                ? SelectionModel.expand(box, face.get(), amount)
                : SelectionModel.expandAll(box, amount)));
        return true;
    }

    @Override
    public boolean onAction(ToolContext c, EditorAction a) {
        switch (a) {
            case CANCEL -> {
                if (gradient.cancel(c)) {
                    return true;
                }
                if (magic != null) {
                    magic = null;
                    return true;
                }
                if (brush != null || lasso != null) {
                    dropFreeform();
                    c.setSelectionState(dragBefore);
                    dragBefore = null;
                    c.setPointerCapture(false);
                    return true;
                }
                if (!drag.isActive()) {
                    return false;
                }
                drag.cancel();
                c.setSelectionState(dragBefore);
                dragBefore = null;
                dragRegion = null;
                c.setPointerCapture(false);
                return true;
            }
            case ERASE_SELECTION -> {
                finishDrag(c);
                actions.erase();
                return true;
            }
            case DESELECT -> {
                finishDrag(c);
                magic = null;
                dropFreeform();
                c.setPointerCapture(false);
                actions.deselect();
                return true;
            }
            case NUDGE_FORWARD, NUDGE_BACK, NUDGE_LEFT, NUDGE_RIGHT, NUDGE_UP, NUDGE_DOWN -> {
                if (drag.isActive() || brush != null || lasso != null) {
                    return true;
                }
                int step = Modifiers.shift(c.modifiers()) ? 10 : 1;
                actions.nudge(a, step, services.cameraYaw());
                return true;
            }
            case SET_SYMMETRY_CENTRE -> {
                actions.symmetryCentre().keyPressed(c, cursor, actions.symmetryMode() != Symmetry.Mode.OFF);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ---- Symmetry overlay ----

    /** The colour of the symmetry centre, planes and copies' boxes (dimmer than the selection). */
    public static final int SYMMETRY_COLOUR = 0xFF7FD4FF;

    /**
     * With a symmetry mode on and a centre set: the centre line and mirror planes as the brushes draw them, and each
     * copy's bounds box, dimmer than the selection.
     */
    private void renderSymmetry(ToolContext c, WorldDraw d) {
        Symmetry symmetry = actions.symmetry().orElse(Symmetry.NONE);
        if (symmetry.isOff()) {
            return;
        }
        Optional<Box> bounds = c.selection();
        double y = bounds.map(box -> (double) box.min().y()).orElse(cursor.missed() ? 0.0 : cursor.hitY());
        int reach = bounds.map(box -> Math.max(box.sizeX(), box.sizeZ()) / 2).orElse(0);
        BrushOutlines.symmetry(d, symmetry, y, reach, SYMMETRY_COLOUR);
        if (bounds.isEmpty()) {
            return;
        }
        int dim = OverlayColors.scaleAlpha(SYMMETRY_COLOUR, 0.5);
        d.seeThrough(true);
        try {
            for (Symmetry.Image image : symmetry.images()) {
                if (image != Symmetry.Image.IDENTITY) d.boxOutline(symmetry.imageBox(image, bounds.get()), dim);
            }
        } catch (IllegalArgumentException beyondTheWorld) {
            // A copy beyond the coordinate range is not drawn (the server refuses such an op).
        }
        d.seeThrough(false);
    }

    private void finishDrag(ToolContext c) {
        if (drag.isActive()) {
            drag.finish();
            c.setPointerCapture(false);
        }
        dragBefore = null;
        dragRegion = null;
    }

    // ---- HUD ----

    /** Edge-length labels on the three edges of the bounds nearest the camera. */
    @Override
    public void renderHud(ToolContext c, HudDraw d) {
        Optional<Box> box = c.selection();
        Optional<ScreenProjector> projector = services.projector();
        Optional<double[]> eye = services.eye();
        if (box.isEmpty() || projector.isEmpty() || eye.isEmpty()) {
            return;
        }
        var aabb = SelectionModel.toAabb(box.get());
        int[] lengths = {box.get().sizeX(), box.get().sizeY(), box.get().sizeZ()};
        double[] out = new double[2];
        for (int axis = 0; axis < 3; axis++) {
            double[] point = new double[3];
            for (int other = 0; other < 3; other++) {
                if (other == axis) {
                    point[other] = aabb.center(other);
                } else {
                    double min = aabb.min(other);
                    double max = aabb.max(other);
                    point[other] = Math.abs(eye.get()[other] - min) <= Math.abs(eye.get()[other] - max) ? min : max;
                }
            }
            if (!projector.get().project(point[0], point[1], point[2], out)) {
                continue;
            }
            String text = Integer.toString(lengths[axis]);
            int width = d.textWidth(text);
            int x = (int) Math.round(out[0]) - width / 2;
            int y = (int) Math.round(out[1]) - 4;
            if (x < -width || y < -10 || x > d.width() || y > d.height()) {
                continue;
            }
            d.fill(x - 2, y - 2, x + width + 2, y + 9, 0xB0101216);
            d.text(text, x, y, AXIS_COLORS[axis]);
        }
    }

    @Override
    public List<KeyHint> hints(ToolContext c) {
        List<KeyHint> hints = new ArrayList<>();
        if (magic != null) {
            hints.add(KeyHint.text("sculptory.hint.select.selecting", SelectionModel.count(magic.fill().count())));
            hints.add(new KeyHint("Esc", "sculptory.hint.select.stop"));
            return hints;
        }
        if (brush != null) {
            hints.add(KeyHint.text("sculptory.hint.select.selecting", SelectionModel.count(brush.count())));
            hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
            return hints;
        }
        if (lasso != null) {
            hints.add(KeyHint.text("sculptory.hint.select.lasso_drawing"));
            hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
            return hints;
        }
        if (drag.isActive() || gradient.active()) {
            hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
            return hints;
        }
        Optional<Selection> region = c.selectionState();
        boolean cells = region.isPresent() && !region.get().resizable();
        SelectSettings.Mode mode = c.settings().get(SelectSettings.MODE);
        if (drawsGradientLine(c.settings())) {
            hints.add(new KeyHint("Alt+drag", "sculptory.hint.pattern.draw_line"));
        } else if (showsGradientLine(c.settings())) {
            // Alt removes in the other modes: the line is drawn in Box mode (or in Palette Paint or the Shape brush).
            hints.add(KeyHint.text("sculptory.hint.pattern.draw_line_in_box"));
        }
        if (mode == SelectSettings.Mode.MAGIC) {
            hints.add(new KeyHint("Click", "sculptory.hint.select.magic"));
            hints.add(new KeyHint("Shift+click", "sculptory.hint.select.magic_add"));
            hints.add(new KeyHint("Alt+click", "sculptory.hint.select.magic_remove"));
        } else if (mode == SelectSettings.Mode.BRUSH) {
            hints.add(new KeyHint("LMB drag", "sculptory.hint.select.brush"));
            hints.add(new KeyHint("Alt+drag", "sculptory.hint.select.brush_remove"));
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.brush.radius"));
        } else if (mode == SelectSettings.Mode.LASSO) {
            hints.add(new KeyHint("LMB drag", "sculptory.hint.select.lasso"));
            hints.add(new KeyHint("Shift+drag", "sculptory.hint.select.lasso_add"));
            hints.add(new KeyHint("Alt+drag", "sculptory.hint.select.lasso_remove"));
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_STRENGTH), "sculptory.hint.select.lasso_height"));
        } else {
            SelectSettings.SelectShape shape = c.settings().get(SelectSettings.SHAPE);
            hints.add(new KeyHint("LMB drag", shape == SelectSettings.SelectShape.BOX ? "sculptory.hint.select.drag"
                    : "sculptory.hint.select.drag." + shape.name().toLowerCase(Locale.ROOT)));
            if (region.isEmpty() || cells) {
                hints.add(new KeyHint("Shift+click", cells ? "sculptory.hint.select.new_box" : "sculptory.hint.select.add"));
            }
        }
        if (region.isEmpty()) {
            return hints;
        }
        if (cells) {
            hints.add(KeyHint.text("sculptory.hint.select.cells"));
        } else {
            hints.add(new KeyHint("Drag handle", "sculptory.hint.select.resize"));
        }
        hints.add(new KeyHint("Ctrl+drag", "sculptory.hint.select.move"));
        if (!cells && mode != SelectSettings.Mode.BRUSH) {
            hints.add(new KeyHint(services.keyLabel(KeyAction.TOOL_SIZE), "sculptory.hint.select.grow"));
        }
        hints.add(new KeyHint(services.keyLabel(KeyAction.ERASE_SELECTION), "sculptory.hint.select.erase"));
        if (actions.symmetryMode() != Symmetry.Mode.OFF) {
            if (actions.symmetry().isEmpty()) {
                hints.add(KeyHint.text("sculptory.hint.symmetry_centre_needed", services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE)));
            }
            hints.add(new KeyHint(services.keyLabel(KeyAction.SET_SYMMETRY_CENTRE), "sculptory.hint.brush.symmetry_centre"));
        }
        services.outlineNote().ifPresent(note -> hints.add(KeyHint.text(note)));
        return hints;
    }
}
