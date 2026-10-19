package dev.sculptory.fabric.client.editor.tools.points;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.fabric.client.editor.render.GizmoRenderer;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.editor.tools.generate.AirPoints;
import dev.sculptory.fabric.client.editor.tools.generate.PathModel;
import dev.sculptory.fabric.client.editor.tools.generate.PathModel.PointRef;
import dev.sculptory.fabric.client.editor.tools.generate.PathModel.Role;
import dev.sculptory.fabric.client.editor.world.GizmoPick;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The shared point editor: how every path-point tool adds, picks, moves, inserts and removes its points,
 * so none keeps its own copy. One editor edits one {@link PathModel} for one host tool, which forwards its input here
 * first and rebuilds its preview on each {@link Edit}.
 *
 * <h2>Hosts</h2>
 * Open paths: Generate's Line, Sweep, Road nodes and Stamp spots, the Shape brush's Line, Extrude's Along a path and To
 * a point (a host of {@link Host#maxPoints()} 1: a click moves the tip). They only ever have {@link Role#OUTLINE}
 * points. A closed outline with inside points: Smart Surface ({@link PathModel#closedOutline()}; {@link Host#pointOn}
 * puts a point on top of the clicked block; {@link Host#roleOf} is {@link #outlineOrInside}). Today Line, Sweep, the
 * Shape brush's Line and Extrude go through {@code LineDraft}, Road through {@code GenerateTool}, Stamp through
 * {@code StampDraft}; each moves onto this editor keeping its own rules (such as Road turning its height to Straight
 * for a node in the air) and its behaviour.
 *
 * <h2>Behaviour (the contract)</h2>
 * <ul>
 *   <li><b>Placing on an open path.</b> A click puts a point on the block aimed at ({@link Host#pointOn}); a click on
 *   open sky puts it at the Alt+Scroll marker ({@link AirPoints}). With nothing selected, or the end point (the last)
 *   selected, the click adds the next end point there and selects it ({@link EditKind#ADD}). With a middle point
 *   selected a click away from the points and the gizmo only deselects. A click past {@link Host#maxPoints()} is refused
 *   with a toast, or moves the point when the host takes one point only.</li>
 *   <li><b>Placing on a closed outline.</b> {@link Host#roleOf} decides a click's role. {@link Role#INSIDE}: the click
 *   adds an inside point and selects it. {@link Role#OUTLINE}: it inserts an outline point after the selected outline
 *   point, or after the last one when none (or an inside point) is selected, and selects it. With
 *   {@link #outlineOrInside}, a click on terrain inside an outline of three or more points is an inside point and any
 *   other click extends the outline.</li>
 *   <li><b>Selecting.</b> A click on a point, outline or inside, selects it ({@link EditKind#SELECT}): the point whose
 *   cell the cursor ray enters first, before the block aimed at ({@link AirPoints#pick}), else one on the clicked block's
 *   column ({@link PathModel#indexAt}, {@link PathModel#insideIndexAt}). Moving before the release drags it freely (as
 *   the centre cube does).</li>
 *   <li><b>The gizmo.</b> The selected point (outline or inside) shows the point gizmo ({@link GizmoPick#pickPoint},
 *   {@link GizmoRenderer#renderPoint}) at its cell's centre: a centre cube, X/Y/Z arrows (red, green, blue; a drag moves
 *   along that axis only, {@link GizmoPick.AxisDrag}) and three plane squares (a drag moves in that plane,
 *   {@link GizmoPick.PlaneDrag}). Hover lightens a part. While dragging, the axes the drag moves along draw as long
 *   guide lines across the scene ({@link GizmoRenderer#renderGuides}). The centre cube follows the cursor freely at the
 *   distance from the camera it had when grabbed ({@link AirPoints#beginDrag}, {@link AirPoints#cellAt}); Alt+Scroll
 *   changes that distance mid-drag. <b>No drag ever snaps onto terrain.</b> Moves are whole blocks; each drag frame is a
 *   {@link EditKind#MOVE} with {@code finished} false, the release one with {@code finished} true; a press and release
 *   without movement moves nothing.</li>
 *   <li><b>Flying.</b> A held point follows the camera: while a drag is on, each frame works the drag out again from
 *   the current cursor ray, so flying with the movement keys carries the point along.</li>
 *   <li><b>Drawing.</b> A thin guide line runs from the point a click draws on from to the cursor's target (the point a
 *   click would add: on the aimed block, or the sky marker; {@link #guideLine()}): on an open path while its end point
 *   is selected, on a closed outline while an outline point is selected and the target is an outline one. Otherwise
 *   (a middle point of an open path, an inside point, a drag) none.</li>
 *   <li><b>Insert on the path.</b> Hovering the drawn path between two outline points highlights that segment
 *   ({@link #hoveredSegment()}, {@link #segmentAt}); a click inserts an outline point there, at index
 *   {@code segment + 1} ({@link EditKind#INSERT}), and selects it. The drawn path comes from {@link Host#drawnSegments}
 *   (straight chords by default; a curve's samples for Curve and Hanging).</li>
 *   <li><b>Insert after.</b> {@link EditorAction#EXTRUDE_POINT} (E while a point is selected) on an outline point adds an
 *   outline point right after it, on an inside point a new inside point ({@link EditKind#INSERT} or
 *   {@link EditKind#ADD}, {@code finished} false); the new point follows the cursor's target ({@link EditKind#MOVE},
 *   {@code finished} false) with a guide line from the selected point, until a click places it ({@code finished} true;
 *   it becomes selected); Esc drops it ({@link EditKind#REMOVE}).</li>
 *   <li><b>Keys.</b> Backspace ({@link EditorAction#REMOVE_NODE}, and Delete, {@link EditorAction#ERASE_SELECTION})
 *   removes the selected point: an outline point selects the one before it (the new first one when it was the first;
 *   none when there are none left), an inside point leaves nothing selected. With nothing selected it removes the last
 *   outline point, as today. Esc ({@link EditorAction#CANCEL}) drops a point being inserted, else puts a dragged point
 *   back, else deselects; with nothing selected it is not taken ({@code false}) and the host behaves as today (cancel a
 *   preview being made, then clear the points). Enter ({@link EditorAction#COMMIT}) is the host's (it builds).</li>
 *   <li><b>Undo.</b> The tools have no tool-local undo of points, so none is added: Ctrl+Z still undoes the last server
 *   edit. Every change is an {@link Edit} a host could record if one is wanted later.</li>
 * </ul>
 *
 * <h2>Built now, and not yet</h2>
 * Built: the model edits ({@link #addEnd}, {@link #addInside}, {@link #insertAfter}, {@link #insertOnSegment},
 * {@link #move}, {@link #remove}, {@link #select}, {@link #clear}) with their events and selection rules, the Esc and
 * Backspace keys, a click that adds (by role) or selects a point (no drag), drawing the points, the selected point's
 * gizmo and the guide line, the guide line's target, {@link #segmentAt} and {@link #outlineOrInside}. Not built yet
 * (marked "Not built yet" below, returning {@code false} or nothing until then): the gizmo drags (axis, plane, free),
 * dragging a point grabbed by a click, insert by clicking the hovered segment, the insert-after point following the
 * cursor, and a held point following the camera. Client thread only.
 */
public final class PointEditor {
    /** A drawn segment within this many pixels of the cursor is hovered (the gizmo's pick radius). */
    public static final double SEGMENT_PICK_PX = GizmoPick.PICK_RADIUS_PX;
    /** The guide line's colour alpha against the point colour. */
    public static final double GUIDE_ALPHA = 0.7;

    /** What a drag moves by. */
    public enum DragMode {
        NONE,
        /** An arrow: along one axis. */
        AXIS,
        /** A plane square: in one plane. */
        PLANE,
        /** The centre cube, or a point grabbed by a click: freely, at the distance it was grabbed at. */
        FREE
    }

    /** What changed. */
    public enum EditKind {
        /** A point added: at the end of the outline, or an inside point (index: its index). */
        ADD,
        /** An outline point inserted (index: its index; the outline points from there on moved up one). */
        INSERT,
        /** A point moved (index: its index; {@code finished} false while a drag or a following point is on). */
        MOVE,
        /** A point removed (index: where it was; {@code before} it). */
        REMOVE,
        /** Every point removed (index -1). */
        CLEAR,
        /** The selection changed (role and index: the selected point, or index -1); the points did not. */
        SELECT
    }

    /**
     * One change, for the host to rebuild its preview (on anything but {@link EditKind#SELECT}; it may wait for
     * {@code finished} before slow work).
     *
     * @param role which list the point is in ({@link Role#OUTLINE} for CLEAR and for a SELECT of nothing)
     * @param index the point concerned (see {@link EditKind}), or -1
     * @param before the point before the change (MOVE, REMOVE), else null
     * @param after the point after it (ADD, INSERT, MOVE), else null
     * @param finished false for the moves of a drag or a following point still on, true otherwise
     */
    public record Edit(EditKind kind, Role role, int index, BlockPos before, BlockPos after, boolean finished) {
        public Edit {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(role);
        }

        /** Whether the points changed (anything but a selection). */
        public boolean changesPoints() {
            return kind != EditKind.SELECT;
        }
    }

    /** The tool whose points these are. */
    public interface Host {
        /**
         * Where a click on a block ({@code cursor} not missed) puts a point: the block itself for the path tools, the
         * block a Shape-brush line's anchor gives, the cell on top of the block for Smart Surface.
         */
        BlockPos pointOn(WorldCursor cursor);

        /**
         * The role of a point a click would add at {@code point} ({@code onTerrain}: the click was on a block, not the
         * sky): always {@link Role#OUTLINE} for an open path (the default); {@link #outlineOrInside} for Smart Surface.
         */
        default Role roleOf(PathModel points, BlockPos point, boolean onTerrain) {
            return Role.OUTLINE;
        }

        /** The most outline points the host takes; a host of one point moves it on a click. */
        default int maxPoints() {
            return PathSpec.MAX_POINTS;
        }

        /** The most inside points the host takes (Smart Surface's own cap). */
        default int maxInsidePoints() {
            return 0;
        }

        /**
         * The drawn path, one list of samples per segment of the outline points ({@link PathModel#segmentCount}), for
         * hovering and inserting on it: straight chords between the points' centres by default; a host drawing a curve
         * gives the curve's samples split at the points.
         */
        default List<List<PathSample>> drawnSegments(List<BlockPos> points, boolean closed) {
            return chords(points, closed);
        }

        /** A change: the host rebuilds its preview (and keeps any rule of its own, such as Road's height for the air). */
        void edited(ToolContext c, Edit edit);
    }

    /** What the editor needs from the client to aim (the host's services; all empty in tests). */
    public interface View {
        /** The cursor ray of the event being handled (the crosshair ray while looking). */
        Optional<Ray> cursorRay();

        /** World to screen, for the gizmo and the drawn path (empty: neither is picked). */
        default Optional<ScreenProjector> projector() {
            return Optional.empty();
        }

        /** The camera's position (the gizmo's size), or empty. */
        default Optional<double[]> eye() {
            return Optional.empty();
        }
    }

    private final PathModel points;
    private final AirPoints air;
    private final View view;
    private final Host host;
    private DragMode dragMode = DragMode.NONE;
    private GizmoPick.Handle hovered;
    private GizmoPick.Handle active;
    private int hoveredSegment = -1;
    /** The point the insert key added that follows the cursor, or null. */
    private PointRef inserting;
    /** Where the cursor would add a point now (the guide line's end), or null, and the role it would have. */
    private BlockPos target;
    private Role targetRole = Role.OUTLINE;

    /**
     * An editor of {@code points}.
     *
     * @param air the air marker shared by the host's drafts
     */
    public PointEditor(PathModel points, AirPoints air, View view, Host host) {
        this.points = Objects.requireNonNull(points);
        this.air = Objects.requireNonNull(air);
        this.view = Objects.requireNonNull(view);
        this.host = Objects.requireNonNull(host);
    }

    // ---- State ----

    public PathModel points() {
        return points;
    }

    public AirPoints air() {
        return air;
    }

    /** The selected point, outline or inside, if any. */
    public Optional<PointRef> selection() {
        return points.selection();
    }

    /** The selected outline point, or -1 (none, or an inside point). */
    public int selected() {
        return points.selected();
    }

    public boolean isClosed() {
        return points.isClosed();
    }

    /** What the drag on now moves by ({@link DragMode#NONE} when none is). */
    public DragMode dragMode() {
        return dragMode;
    }

    public boolean dragging() {
        return dragMode != DragMode.NONE;
    }

    /** The gizmo part under the cursor (none while dragging, or with nothing selected). */
    public Optional<GizmoPick.Handle> hoveredHandle() {
        return Optional.ofNullable(hovered);
    }

    /** The gizmo part being dragged, if any. */
    public Optional<GizmoPick.Handle> activeHandle() {
        return Optional.ofNullable(active);
    }

    /** The drawn segment under the cursor, which a click inserts a point on (not built yet: always empty). */
    public OptionalInt hoveredSegment() {
        return hoveredSegment < 0 ? OptionalInt.empty() : OptionalInt.of(hoveredSegment);
    }

    /** Whether a point added with the insert key is following the cursor. */
    public boolean placingInserted() {
        return inserting != null;
    }

    /**
     * The guide line, {@code {x1, y1, z1, x2, y2, z2}} between cell centres, from the point a click draws on from to
     * where it would add the next one (as of the last {@link #frame}; see the class). Empty when there is none.
     */
    public Optional<double[]> guideLine() {
        int from = points.selected();
        if (from < 0 || dragging() || target == null || targetRole != Role.OUTLINE) return Optional.empty();
        if (!points.isClosed() && !points.isEnd(from)) return Optional.empty();
        return Optional.of(centres(points.node(from), target));
    }

    // ---- Edits (what the input does, for hosts and tests too) ----

    /**
     * Adds an outline point after the last one (a closed outline: between the last and the first) and selects it.
     * Refused past {@link Host#maxPoints()} (a toast; a host of one point moves its point instead). Returns the new
     * index, or -1.
     */
    public int addEnd(ToolContext c, BlockPos point) {
        Objects.requireNonNull(point);
        if (points.size() >= host.maxPoints()) {
            if (host.maxPoints() == 1) {
                move(c, new PointRef(Role.OUTLINE, 0), point, true);
                return 0;
            }
            tooMany(c, host.maxPoints());
            return -1;
        }
        int index = points.size();
        points.insert(index, point);
        points.select(index);
        host.edited(c, new Edit(EditKind.ADD, Role.OUTLINE, index, null, point, true));
        return index;
    }

    /**
     * Adds an inside point (a closed outline only) and selects it; refused past {@link Host#maxInsidePoints()}. Returns
     * its index, or -1.
     *
     * @throws IllegalStateException on an open path
     */
    public int addInside(ToolContext c, BlockPos point) {
        Objects.requireNonNull(point);
        if (!points.isClosed()) throw new IllegalStateException("An open path has no inside points");
        if (points.insideCount() >= host.maxInsidePoints()) {
            tooMany(c, host.maxInsidePoints());
            return -1;
        }
        int index = points.addInside(point);
        points.selectInside(index);
        host.edited(c, new Edit(EditKind.ADD, Role.INSIDE, index, null, point, true));
        return index;
    }

    /** Inserts an outline point right after outline point {@code index} and selects it. Returns its index, or -1. */
    public int insertAfter(ToolContext c, int index, BlockPos point) {
        Objects.checkIndex(index, points.size());
        return insertAt(c, index + 1, point, true);
    }

    /** Inserts an outline point on segment {@code segment} (at {@code segment + 1}) and selects it. Returns its index. */
    public int insertOnSegment(ToolContext c, int segment, BlockPos point) {
        Objects.checkIndex(segment, points.segmentCount());
        return insertAt(c, segment + 1, point, true);
    }

    private int insertAt(ToolContext c, int index, BlockPos point, boolean finished) {
        Objects.requireNonNull(point);
        if (points.size() >= host.maxPoints()) {
            tooMany(c, host.maxPoints());
            return -1;
        }
        points.insert(index, point);
        points.select(index);
        host.edited(c, new Edit(EditKind.INSERT, Role.OUTLINE, index, null, point, finished));
        return index;
    }

    /** Moves {@code point} to {@code to}; returns whether it moved ({@code finished}: see {@link Edit}). */
    public boolean move(ToolContext c, PointRef point, BlockPos to, boolean finished) {
        boolean inside = point.role() == Role.INSIDE;
        BlockPos before = inside ? points.insidePoint(point.index()) : points.node(point.index());
        boolean moved = inside ? points.moveInside(point.index(), to) : points.move(point.index(), to);
        if (!moved) return false;
        host.edited(c, new Edit(EditKind.MOVE, point.role(), point.index(), before, to, finished));
        return true;
    }

    /**
     * Removes {@code point}. A selected outline point passes the selection to the one before it (the new first one when
     * it was the first; none when no outline point is left); a selected inside point leaves nothing selected. Returns the
     * point removed.
     */
    public BlockPos remove(ToolContext c, PointRef point) {
        boolean wasSelected = points.selection().filter(point::equals).isPresent();
        boolean inside = point.role() == Role.INSIDE;
        BlockPos removed = inside ? points.removeInside(point.index()) : points.remove(point.index());
        if (inserting != null && inserting.role() == point.role()) {
            if (inserting.index() == point.index()) {
                inserting = null;
            } else if (inserting.index() > point.index()) {
                inserting = new PointRef(inserting.role(), inserting.index() - 1);
            }
        }
        if (wasSelected && !inside && points.size() > 0) points.select(Math.max(0, point.index() - 1));
        host.edited(c, new Edit(EditKind.REMOVE, point.role(), point.index(), removed, null, true));
        return removed;
    }

    /** Selects {@code point}, or nothing for null. */
    public void select(ToolContext c, PointRef point) {
        if (points.selection().equals(Optional.ofNullable(point))) return;
        points.select(point);
        host.edited(c, new Edit(EditKind.SELECT, point == null ? Role.OUTLINE : point.role(),
                point == null ? -1 : point.index(), null, null, true));
    }

    /** Removes every point (a drag or a following point ends). */
    public void clear(ToolContext c) {
        endDrag(c);
        inserting = null;
        if (points.isEmpty()) return;
        points.clear();
        host.edited(c, new Edit(EditKind.CLEAR, Role.OUTLINE, -1, null, null, true));
    }

    private static void tooMany(ToolContext c, int max) {
        c.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.line_max_points", Integer.toString(max)));
    }

    // ---- Input (the host forwards its own first) ----

    /**
     * A pointer event; returns whether it was taken. Built: a press adds (by role) or selects a point (see the class).
     * Not built yet: gizmo drags, dragging a point grabbed by a click, inserting on the hovered segment and placing the
     * insert key's point.
     */
    public boolean onPointer(ToolContext c, PointerEvent e) {
        if (e.button() != PointerEvent.LEFT) return false;
        if (e.kind() != PointerEvent.Kind.PRESS) {
            // Not built yet: DRAG and RELEASE of the gizmo drags and of a grabbed point.
            return false;
        }
        WorldCursor cursor = e.cursor();
        Ray ray = view.cursorRay().orElse(null);
        air.useWorld(c);
        // Not built yet: a press on the insert key's following point places it; one on the selected point's gizmo starts
        // its drag; one on the hovered segment inserts a point there.
        PointRef picked = pick(cursor, ray);
        if (picked != null) {
            select(c, picked);
            return true;
        }
        BlockPos point = air.place(cursor, ray, host::pointOn);
        if (point == null) return false;
        Role role = points.isClosed() ? host.roleOf(points, point, !cursor.missed()) : Role.OUTLINE;
        if (role == Role.INSIDE) {
            addInside(c, point);
            return true;
        }
        if (points.isClosed()) {
            int after = points.selected() >= 0 ? points.selected() : points.size() - 1;
            if (after < 0) {
                addEnd(c, point);
            } else {
                insertAt(c, after + 1, point, true);
            }
            return true;
        }
        int selected = points.selected();
        if (points.selection().isPresent() && !points.isEnd(selected)) {
            select(c, null); // a middle point selected: a click away only deselects
            return true;
        }
        addEnd(c, point);
        return true;
    }

    /** The point a press picks: the nearest along the ray (outline or inside), else one on the clicked column. */
    private PointRef pick(WorldCursor cursor, Ray ray) {
        List<BlockPos> all = new ArrayList<>(points.nodes());
        all.addAll(points.insideNodes());
        int index = ray != null ? AirPoints.pick(all, ray, cursor) : -1;
        if (index >= 0) {
            return index < points.size() ? new PointRef(Role.OUTLINE, index)
                    : new PointRef(Role.INSIDE, index - points.size());
        }
        if (cursor.missed()) return null;
        BlockPos at = host.pointOn(cursor);
        int outline = points.indexAt(at);
        if (outline >= 0) return new PointRef(Role.OUTLINE, outline);
        int inside = points.insideIndexAt(at);
        return inside >= 0 ? new PointRef(Role.INSIDE, inside) : null;
    }

    /**
     * Alt+Scroll: the sky marker nearer or farther (not built yet: during a free drag, the dragged point too). Returns
     * whether the scroll was taken.
     */
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        air.useWorld(c);
        return air.scroll(e);
    }

    /** Esc, Backspace or Delete, and the insert key (see the class); returns whether the action was taken. */
    public boolean onAction(ToolContext c, EditorAction a) {
        switch (a) {
            case CANCEL -> {
                if (inserting != null) {
                    PointRef at = inserting;
                    inserting = null;
                    remove(c, at);
                    return true;
                }
                if (dragging()) {
                    // Not built yet: the dragged point goes back to where the drag started.
                    endDrag(c);
                    return true;
                }
                if (points.selection().isPresent()) {
                    select(c, null);
                    return true;
                }
                return false;
            }
            case REMOVE_NODE, ERASE_SELECTION -> {
                if (points.isEmpty()) return false;
                endDrag(c);
                Optional<PointRef> selected = points.selection();
                if (selected.isPresent()) {
                    remove(c, selected.get());
                } else if (points.size() > 0) {
                    remove(c, new PointRef(Role.OUTLINE, points.size() - 1));
                } else {
                    return false;
                }
                return true;
            }
            case EXTRUDE_POINT -> {
                // Not built yet: on a selected outline point insert one right after it, on an inside point add an
                // inside point (unfinished), following the cursor's target until a click places it.
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Once per frame: the air marker, the cursor's target (the guide line's end) and its role, and the hovered gizmo
     * part. Not built yet: the hovered segment, the insert key's following point, and a drag worked out again from the
     * current ray (a held point follows the camera).
     */
    public void frame(ToolContext c, FrameInfo f) {
        WorldCursor cursor = f.cursor();
        Ray ray = view.cursorRay().orElse(null);
        air.useWorld(c);
        List<BlockPos> all = new ArrayList<>(points.nodes());
        all.addAll(points.insideNodes());
        air.frame(cursor, ray, all);
        target = cursor.missed() ? air.marker() : host.pointOn(cursor);
        targetRole = target != null && points.isClosed() ? host.roleOf(points, target, !cursor.missed()) : Role.OUTLINE;
        hovered = null;
        Optional<BlockPos> selected = selectedPoint();
        if (selected.isPresent() && !dragging()) {
            Optional<ScreenProjector> projector = view.projector();
            Optional<double[]> eye = view.eye();
            if (projector.isPresent() && eye.isPresent()) {
                double[] o = centre(selected.get());
                hovered = GizmoPick.pickPoint(projector.get(), o[0], o[1], o[2], gizmoScale(o, eye.get()), f.mouseX(),
                        f.mouseY(), GizmoPick.PICK_RADIUS_PX).map(GizmoPick.Picked::handle).orElse(null);
            }
        }
        hoveredSegment = -1; // Not built yet: segmentAt(host.drawnSegments(...), ...) when nothing else is hovered.
    }

    /** The tool is put away or the kind left: a drag ends and the insert key's point is dropped; the points stay. */
    public void deactivate(ToolContext c) {
        if (inserting != null) {
            PointRef at = inserting;
            inserting = null;
            remove(c, at);
        }
        endDrag(c);
        hovered = null;
        hoveredSegment = -1;
        target = null;
        air.hide();
    }

    private void endDrag(ToolContext c) {
        if (dragMode != DragMode.NONE) {
            c.setPointerCapture(false);
            air.endDrag();
        }
        dragMode = DragMode.NONE;
        active = null;
    }

    private Optional<BlockPos> selectedPoint() {
        return points.selection().map(ref -> ref.role() == Role.INSIDE ? points.insidePoint(ref.index())
                : points.node(ref.index()));
    }

    // ---- Drawing ----

    /**
     * Draws the points (outline and inside, in {@code colour}; the selected one in {@code selectedColour}), the air
     * marker, the guide line, and the selected point's gizmo (with the drag's guide lines while dragging).
     */
    public void render(WorldDraw d, int colour, int selectedColour) {
        Optional<PointRef> selected = points.selection();
        List<BlockPos> outline = points.nodes();
        for (int i = 0; i < outline.size(); i++) {
            boolean lit = selected.equals(Optional.of(new PointRef(Role.OUTLINE, i)));
            point(d, outline.get(i), lit ? selectedColour : colour);
        }
        List<BlockPos> inside = points.insideNodes();
        for (int i = 0; i < inside.size(); i++) {
            boolean lit = selected.equals(Optional.of(new PointRef(Role.INSIDE, i)));
            point(d, inside.get(i), lit ? selectedColour : colour);
        }
        air.render(d);
        Optional<double[]> guide = guideLine();
        if (guide.isPresent()) {
            double[] g = guide.get();
            d.seeThrough(true);
            d.line(g[0], g[1], g[2], g[3], g[4], g[5], OverlayColors.scaleAlpha(selectedColour, GUIDE_ALPHA));
            d.seeThrough(false);
        }
        Optional<BlockPos> at = selectedPoint();
        Optional<double[]> eye = view.eye();
        if (at.isEmpty() || eye.isEmpty()) return;
        double[] o = centre(at.get());
        d.seeThrough(true);
        GizmoRenderer.renderPoint(d::line, o[0], o[1], o[2], gizmoScale(o, eye.get()), hovered, active);
        if (active != null) GizmoRenderer.renderGuides(d::line, o[0], o[1], o[2], active);
        d.seeThrough(false);
    }

    private static void point(WorldDraw d, BlockPos p, int colour) {
        Box box = Box.of(p);
        d.seeThrough(true);
        d.boxOutline(box, OverlayColors.scaleAlpha(colour, 0.4));
        d.seeThrough(false);
        d.boxOutline(box, colour);
    }

    // ---- Helpers ----

    /**
     * Smart Surface's rule for {@link Host#roleOf}: once the outline has three or more points, a click on terrain whose
     * column lies inside it ({@link #insideOutline}) adds an inside point; any other click (outside, on the sky, or while
     * the outline has fewer points) extends the outline.
     */
    public static Role outlineOrInside(PathModel points, BlockPos point, boolean onTerrain) {
        if (!points.isClosed() || points.size() < 3 || !onTerrain) return Role.OUTLINE;
        return insideOutline(points.nodes(), point) ? Role.INSIDE : Role.OUTLINE;
    }

    /**
     * Whether the column of {@code point} (its cell's centre seen from above) lies inside the closed polygon of
     * {@code outline}'s cell centres (even-odd rule, so a non-convex outline works; a point on an edge counts as outside
     * one side and inside the other consistently). False for fewer than three points.
     */
    public static boolean insideOutline(List<BlockPos> outline, BlockPos point) {
        if (outline.size() < 3) return false;
        double px = point.x() + 0.5, pz = point.z() + 0.5;
        boolean inside = false;
        for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
            double xi = outline.get(i).x() + 0.5, zi = outline.get(i).z() + 0.5;
            double xj = outline.get(j).x() + 0.5, zj = outline.get(j).z() + 0.5;
            if ((zi > pz) != (zj > pz) && px < (xj - xi) * (pz - zi) / (zj - zi) + xi) inside = !inside;
        }
        return inside;
    }

    /**
     * The segment of {@code segments} (one sample list per segment) drawn nearest the cursor within {@code radiusPx}
     * pixels on screen, or -1: each segment's samples are joined into a polyline, projected, and measured as the gizmo
     * measures its arrows. Pieces off screen or behind the camera are skipped.
     */
    public static int segmentAt(List<List<PathSample>> segments, ScreenProjector projector, double mouseX, double mouseY,
                                double radiusPx) {
        int best = -1;
        double bestDistance = radiusPx;
        double[] a = new double[2];
        double[] b = new double[2];
        for (int s = 0; s < segments.size(); s++) {
            List<PathSample> samples = segments.get(s);
            for (int i = 1; i < samples.size(); i++) {
                PathSample p = samples.get(i - 1), q = samples.get(i);
                if (!projector.project(p.x(), p.y(), p.z(), a) || !projector.project(q.x(), q.y(), q.z(), b)) continue;
                double distance = GizmoPick.distanceToSegment(mouseX, mouseY, a[0], a[1], b[0], b[1]);
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = s;
                }
            }
        }
        return best;
    }

    /** Straight chords between the points' cell centres, one per segment (the default drawn path). */
    public static List<List<PathSample>> chords(List<BlockPos> points, boolean closed) {
        List<List<PathSample>> segments = new ArrayList<>();
        int count = points.size() < 2 ? 0 : closed && points.size() >= 3 ? points.size() : points.size() - 1;
        for (int s = 0; s < count; s++) {
            BlockPos from = points.get(s), to = points.get((s + 1) % points.size());
            double length = Math.sqrt(sq(to.x() - from.x()) + sq(to.y() - from.y()) + sq(to.z() - from.z()));
            segments.add(List.of(new PathSample(from.x() + 0.5, from.y() + 0.5, from.z() + 0.5, 0),
                    new PathSample(to.x() + 0.5, to.y() + 0.5, to.z() + 0.5, length)));
        }
        return segments;
    }

    private static double gizmoScale(double[] origin, double[] eye) {
        return GizmoPick.scale(Math.sqrt(sq(origin[0] - eye[0]) + sq(origin[1] - eye[1]) + sq(origin[2] - eye[2])));
    }

    private static double[] centre(BlockPos p) {
        return new double[] {p.x() + 0.5, p.y() + 0.5, p.z() + 0.5};
    }

    private static double[] centres(BlockPos from, BlockPos to) {
        return new double[] {from.x() + 0.5, from.y() + 0.5, from.z() + 0.5, to.x() + 0.5, to.y() + 0.5, to.z() + 0.5};
    }

    private static double sq(double v) {
        return v * v;
    }
}
