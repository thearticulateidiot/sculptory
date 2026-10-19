package dev.sculptory.fabric.client.editor.tools.points;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.path.PathSample;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.render.GizmoRenderer;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
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
import java.util.function.Function;

/**
 * The shared point editor: how every path-point tool adds, picks, moves, inserts and removes its points,
 * so none keeps its own copy. One editor edits one {@link PathModel} for one host tool, which forwards its input here
 * first and rebuilds its preview on each {@link Edit}.
 *
 * <h2>Hosts</h2>
 * Open paths: Generate's Line, Sweep, Road nodes and Stamp spots, the Shape brush's Line, Extrude's Along a path and To
 * a point (a host of {@link Host#maxPoints()} 1: a click moves the tip). They only ever have {@link Role#OUTLINE}
 * points. Line, Sweep, the Shape brush's Line and Extrude reach it through {@code LineDraft}, Road through
 * {@code GenerateTool}, Stamp through {@code StampDraft}; each keeps its own rules (such as Road turning its height to
 * Straight for a node in the air). A closed outline with inside points: Smart Surface ({@link PathModel#closedOutline()};
 * {@link Host#pointOn} puts a point on top of the clicked block; {@link Host#roleOf} is {@link #outlineOrInside}).
 *
 * <h2>Behaviour</h2>
 * <ul>
 *   <li><b>Placing on an open path.</b> A click puts a point on the block aimed at ({@link Host#pointOn}); a click on
 *   open sky puts it at the Alt+Scroll marker ({@link AirPoints}; not for a host without {@link Host#takesSky()}). With
 *   nothing selected, or the end point (the last) selected, the click adds the next end point there and selects it
 *   ({@link EditKind#ADD}). With a middle point selected a click away from the points, the gizmo and the drawn path only
 *   deselects. A click past {@link Host#maxPoints()} is refused with a toast, or moves the point when the host takes one
 *   point only.</li>
 *   <li><b>Placing on a closed outline.</b> {@link Host#roleOf} decides a click's role. {@link Role#INSIDE}: the click
 *   adds an inside point and selects it. {@link Role#OUTLINE}: it inserts an outline point after the selected outline
 *   point, or after the last one when none (or an inside point) is selected, and selects it.</li>
 *   <li><b>Selecting.</b> A click on a point, outline or inside, selects it ({@link EditKind#SELECT}): the point whose
 *   cell the cursor ray enters first, before the block aimed at ({@link AirPoints#pick}), else one on the clicked block's
 *   column ({@link PathModel#indexAt}, {@link PathModel#insideIndexAt}). Moving before the release drags it freely, as
 *   the centre cube does.</li>
 *   <li><b>The gizmo.</b> The selected point shows the point gizmo ({@link GizmoPick#pickPoint},
 *   {@link GizmoRenderer#renderPoint}) at its cell's centre: a centre cube, X/Y/Z arrows (red, green, blue; a drag moves
 *   along that axis only, {@link GizmoPick.AxisDrag}) and three plane squares (a drag moves in that plane,
 *   {@link GizmoPick.PlaneDrag}). Hover lightens a part. While dragging, the axes the drag moves along draw as long
 *   guide lines across the scene ({@link GizmoRenderer#renderGuides}). The centre cube follows the cursor freely at the
 *   distance from the camera it had when grabbed (the point keeps where on its cell it was grabbed); Alt+Scroll changes
 *   that distance mid-drag. <b>No drag ever snaps onto terrain</b>: the cursor's block plays no part. A free drag does
 *   snap onto another point the cursor ray picks (so the last point dragged onto the first closes a loop), but not onto
 *   one the ray passed through when the drag began. Moves are whole
 *   blocks, kept within the build height; each drag frame is a {@link EditKind#MOVE} with {@code finished} false, the
 *   release one with {@code finished} true; a press and release without movement moves nothing.</li>
 *   <li><b>Flying.</b> A held point follows the camera: while a drag is on, each frame works the drag out again from
 *   the current cursor ray, so flying with the movement keys carries the point along.</li>
 *   <li><b>Drawing.</b> A thin guide line runs from the point a click draws on from to the cursor's target (the point a
 *   click would add: on the aimed block, or the sky marker; {@link #guideLine()}), ending in a small cube: on an open path
 *   while its end point is selected, on a closed outline while an outline point is selected and the target is an outline
 *   one. Otherwise (a middle point of an open path, an inside point, a drag, a host of one point) none.</li>
 *   <li><b>Insert on the path.</b> Hovering the drawn path between two outline points highlights that segment
 *   ({@link #hoveredSegment()}, {@link #segmentAt}); a click inserts an outline point there, in the cell of the drawn path
 *   nearest the cursor, at index {@code segment + 1} ({@link EditKind#INSERT}), and selects it. The drawn path comes from
 *   {@link Host#drawnSegments}. Points and the gizmo come first; a host of one point has no path to insert on.</li>
 *   <li><b>Insert after.</b> {@link EditorAction#EXTRUDE_POINT} (E while a point is selected) on an outline point adds an
 *   outline point right after it, on an inside point a new inside point ({@link EditKind#INSERT} or
 *   {@link EditKind#ADD}, {@code finished} false); the new point follows the cursor's target ({@link EditKind#MOVE},
 *   {@code finished} false) with a guide line from the selected point, until a click places it ({@code finished} true;
 *   it becomes selected); Esc (or Backspace) drops it ({@link EditKind#REMOVE}). With nothing selected the key is not
 *   taken, so E stays the inventory key.</li>
 *   <li><b>Keys.</b> Backspace ({@link EditorAction#REMOVE_NODE}, and Delete, {@link EditorAction#ERASE_SELECTION})
 *   removes the selected point: an outline point selects the one before it (the new first one when it was the first;
 *   none when there are none left), an inside point leaves nothing selected. With nothing selected it removes the last
 *   outline point. Esc ({@link EditorAction#CANCEL}) drops a point being inserted, else puts a dragged point back, else
 *   deselects; with nothing selected it is not taken ({@code false}) and the host behaves as before (cancel a preview
 *   being made, then clear the points). Enter ({@link EditorAction#COMMIT}) is the host's (it builds).</li>
 *   <li><b>Undo.</b> The tools have no tool-local undo of points, so none is added: Ctrl+Z still undoes the last server
 *   edit. Every change is an {@link Edit} a host could record if one is wanted later.</li>
 * </ul>
 * Client thread only.
 */
public final class PointEditor {
    /** A drawn segment within this many pixels of the cursor is hovered (the gizmo's pick radius). */
    public static final double SEGMENT_PICK_PX = GizmoPick.PICK_RADIUS_PX;
    /** The guide line's alpha where it shows through terrain. */
    public static final double GUIDE_ALPHA = 0.35;
    /** The guide line and its end cube: a thin dark line, as a pencil stroke. */
    public static final int GUIDE_COLOUR = 0xFF15181C;
    /** Half the side of the cube at the guide line's end (blocks). */
    static final double TARGET_HALF = 0.15;
    /** How far a point's cube stands out of its block (blocks). */
    static final double POINT_GROW = 0.06;
    /** The hovered segment's colour. */
    public static final int HOVERED_SEGMENT_COLOUR = GizmoRenderer.COLOR_HOVERED;

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

        /**
         * The toast for a point past the host's cap of {@code role}'s points ({@link #maxPoints()} or
         * {@link #maxInsidePoints()}; its argument: the most); the Line's by default.
         */
        default String tooManyNotice(Role role) {
            return "sculptory.notice.line_max_points";
        }

        /** The most inside points the host takes (Smart Surface's own cap). */
        default int maxInsidePoints() {
            return 0;
        }

        /**
         * Whether a click on open sky puts a point at the air marker ({@link AirPoints}); true by default. Without it
         * (the Shape brush's Line) a click on the sky does nothing and no marker shows; points still drag freely.
         */
        default boolean takesSky() {
            return true;
        }

        /**
         * The drawn path, one list of samples per segment of the outline points ({@link PathModel#segmentCount}), for
         * hovering and inserting on it: straight chords between the points' centres by default; a host drawing a curve
         * gives the curve's samples split at the points ({@link #splitAtPoints}). A host whose path starts at an anchor
         * of its own before the first point (Extrude's face) puts that stretch first, one segment more: a click there
         * inserts a point before the first.
         */
        default List<List<PathSample>> drawnSegments(List<BlockPos> points, boolean closed) {
            return chords(points, closed);
        }

        /**
         * The point a click on drawn segment {@code segment} (of {@link #drawnSegments}) at drawn point {@code at} inserts:
         * the cell {@code at} lies in by default. A host drawing its path raised off the points (Road's centre line over the
         * ground, Stamp's over the blocks' tops) gives the cell without the lift.
         */
        default BlockPos insertPoint(List<BlockPos> points, int segment, double[] at) {
            return new BlockPos((int) Math.floor(at[0]), (int) Math.floor(at[1]), (int) Math.floor(at[2]));
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
    /** The point dragged, where it was when grabbed, and whether it has moved since. */
    private PointRef dragged;
    private BlockPos dragFrom;
    private boolean moved;
    private GizmoPick.AxisDrag axisDrag;
    private GizmoPick.PlaneDrag planeDrag;
    /** A free drag: where on its cell the point was grabbed, from the ray's point at the drag distance (blocks). */
    private double[] grabOffset;
    /** A free drag: another point the ray passed through when it began (not snapped onto until the ray leaves it). */
    private BlockPos behindGrab;
    /** The changes reported so far, by kind and role ({@link #count}). */
    private final long[] counts = new long[EditKind.values().length * Role.values().length];

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

    /** The gizmo part being dragged, if any ({@link GizmoPick.Handle#CENTRE} for a point grabbed by a click). */
    public Optional<GizmoPick.Handle> activeHandle() {
        return Optional.ofNullable(active);
    }

    /** The drawn segment under the cursor, which a click inserts a point on. */
    public OptionalInt hoveredSegment() {
        return hoveredSegment < 0 ? OptionalInt.empty() : OptionalInt.of(hoveredSegment);
    }

    /** Whether a point added with the insert key is following the cursor. */
    public boolean placingInserted() {
        return inserting != null;
    }

    /**
     * The guide line, {@code {x1, y1, z1, x2, y2, z2}} between cell centres, from the point a click draws on from to
     * where it would add the next one, or from the selected point to the point the insert key added (as of the last
     * {@link #frame}; see the class). Empty when there is none.
     */
    public Optional<double[]> guideLine() {
        if (inserting != null) {
            Optional<BlockPos> from = selectedPoint();
            return from.map(p -> centres(p, pointAt(inserting)));
        }
        int from = points.selected();
        if (from < 0 || dragging() || target == null || targetRole != Role.OUTLINE || host.maxPoints() == 1) {
            return Optional.empty();
        }
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
                points.select(0);
                return 0;
            }
            tooMany(c, Role.OUTLINE, host.maxPoints());
            return -1;
        }
        int index = points.size();
        points.insert(index, point);
        points.select(index);
        report(c, new Edit(EditKind.ADD, Role.OUTLINE, index, null, point, true));
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
            tooMany(c, Role.INSIDE, host.maxInsidePoints());
            return -1;
        }
        int index = points.addInside(point);
        points.selectInside(index);
        report(c, new Edit(EditKind.ADD, Role.INSIDE, index, null, point, true));
        return index;
    }

    /** Inserts an outline point right after outline point {@code index} and selects it. Returns its index, or -1. */
    public int insertAfter(ToolContext c, int index, BlockPos point) {
        Objects.checkIndex(index, points.size());
        return insertAt(c, index + 1, point, true, true);
    }

    /** Inserts an outline point on segment {@code segment} (at {@code segment + 1}) and selects it. Returns its index. */
    public int insertOnSegment(ToolContext c, int segment, BlockPos point) {
        Objects.checkIndex(segment, points.segmentCount());
        return insertAt(c, segment + 1, point, true, true);
    }

    private int insertAt(ToolContext c, int index, BlockPos point, boolean finished, boolean select) {
        Objects.requireNonNull(point);
        if (points.size() >= host.maxPoints()) {
            tooMany(c, Role.OUTLINE, host.maxPoints());
            return -1;
        }
        points.insert(index, point);
        if (select) points.select(index);
        report(c, new Edit(EditKind.INSERT, Role.OUTLINE, index, null, point, finished));
        return index;
    }

    /** Moves {@code point} to {@code to}; returns whether it moved ({@code finished}: see {@link Edit}). */
    public boolean move(ToolContext c, PointRef point, BlockPos to, boolean finished) {
        return move(c, point, to, finished, true);
    }

    /** {@link #move}; {@code counted} false leaves it out of {@link #count} (a cancelled drag put back). */
    private boolean move(ToolContext c, PointRef point, BlockPos to, boolean finished, boolean counted) {
        boolean inside = point.role() == Role.INSIDE;
        BlockPos before = inside ? points.insidePoint(point.index()) : points.node(point.index());
        boolean moved = inside ? points.moveInside(point.index(), to) : points.move(point.index(), to);
        if (!moved) return false;
        report(c, new Edit(EditKind.MOVE, point.role(), point.index(), before, to, finished), counted);
        return true;
    }

    /**
     * Removes {@code point}. A selected outline point passes the selection to the one before it (the new first one when
     * it was the first; none when no outline point is left); a selected inside point leaves nothing selected. Returns the
     * point removed.
     */
    public BlockPos remove(ToolContext c, PointRef point) {
        return remove(c, point, true);
    }

    /** {@link #remove}; {@code counted} false leaves it out of {@link #count} (the insert key's point dropped). */
    private BlockPos remove(ToolContext c, PointRef point, boolean counted) {
        boolean wasSelected = points.selection().filter(point::equals).isPresent();
        boolean inside = point.role() == Role.INSIDE;
        if (dragged != null && dragged.equals(point)) endDrag(c);
        BlockPos removed = inside ? points.removeInside(point.index()) : points.remove(point.index());
        if (inserting != null && inserting.role() == point.role()) {
            if (inserting.index() == point.index()) {
                inserting = null;
            } else if (inserting.index() > point.index()) {
                inserting = new PointRef(inserting.role(), inserting.index() - 1);
            }
        }
        if (wasSelected && !inside && points.size() > 0) points.select(Math.max(0, point.index() - 1));
        report(c, new Edit(EditKind.REMOVE, point.role(), point.index(), removed, null, true), counted);
        return removed;
    }

    /** Selects {@code point}, or nothing for null. */
    public void select(ToolContext c, PointRef point) {
        if (points.selection().equals(Optional.ofNullable(point))) return;
        points.select(point);
        report(c, new Edit(EditKind.SELECT, point == null ? Role.OUTLINE : point.role(),
                point == null ? -1 : point.index(), null, null, true));
    }

    /** Removes every point (a drag or a following point ends). */
    public void clear(ToolContext c) {
        endDrag(c);
        inserting = null;
        if (points.isEmpty()) return;
        points.clear();
        report(c, new Edit(EditKind.CLEAR, Role.OUTLINE, -1, null, null, true));
    }

    /**
     * How many changes of {@code kind} to {@code role} points this editor has made (the tutorial's steps count clicks
     * with it): the finished {@link Edit}s, so a drag counts once, as it ends, and the insert key's point counts as an
     * INSERT (an inside one as an ADD) once a click puts it down. A drag put back with Esc, and the insert key's point
     * dropped, count nothing. A CLEAR, and a SELECT of nothing, count as {@link Role#OUTLINE}.
     */
    public long count(EditKind kind, Role role) {
        return counts[kind.ordinal() * Role.values().length + role.ordinal()];
    }

    /** Counts {@code edit} once finished and tells the host. */
    private void report(ToolContext c, Edit edit) {
        report(c, edit, true);
    }

    /** Tells the host; {@code counted} false leaves the edit out of {@link #count}. */
    private void report(ToolContext c, Edit edit, boolean counted) {
        if (counted && edit.finished()) tally(edit.kind(), edit.role());
        host.edited(c, edit);
    }

    private void tally(EditKind kind, Role role) {
        counts[kind.ordinal() * Role.values().length + role.ordinal()]++;
    }

    private void tooMany(ToolContext c, Role role, int max) {
        c.notify(Notice.of(Notice.Level.INFO, host.tooManyNotice(role), Integer.toString(max)));
    }

    // ---- Input (the host forwards its own first) ----

    /**
     * A pointer event; returns whether it was taken. A press, in this order: places the insert key's following point; on
     * the selected point's gizmo starts its drag; on a point selects it (and a drag from there moves it freely); on the
     * hovered drawn path inserts a point there; else adds (by role) or deselects (see the class). Drags and the release
     * move the dragged point.
     */
    public boolean onPointer(ToolContext c, PointerEvent e) {
        if (e.button() != PointerEvent.LEFT) return false;
        Ray ray = view.cursorRay().orElse(null);
        air.useWorld(c);
        switch (e.kind()) {
            case DRAG -> {
                if (!dragging()) return false;
                follow(c, ray, false);
                return true;
            }
            case RELEASE -> {
                if (!dragging()) return false;
                // A press and release with no movement between (a click on a point) moves nothing.
                if (moved) follow(c, ray, true);
                endDrag(c);
                return true;
            }
            case MOVE -> {
                return false;
            }
            default -> {
            }
        }
        WorldCursor cursor = e.cursor();
        if (inserting != null) {
            placeInserted(c, cursor, ray);
            return true;
        }
        Optional<BlockPos> selected = selectedPoint();
        if (selected.isPresent() && ray != null) {
            GizmoPick.Handle handle = gizmoAt(selected.get(), e.mouseX(), e.mouseY());
            if (handle != null) {
                beginDrag(c, points.selection().orElseThrow(), handle, ray);
                return true;
            }
        }
        PointRef picked = pick(cursor, ray);
        if (picked != null) {
            select(c, picked);
            if (ray != null) beginDrag(c, picked, GizmoPick.Handle.CENTRE, ray);
            return true;
        }
        int segment = segmentAt(e.mouseX(), e.mouseY());
        if (segment >= 0 && insertOnDrawn(c, segment, e.mouseX(), e.mouseY())) return true;
        BlockPos point = cursor.missed() && !host.takesSky() ? null : air.place(cursor, ray, host::pointOn);
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
                insertAt(c, after + 1, point, true, true);
            }
            return true;
        }
        int selectedIndex = points.selected();
        if (points.selection().isPresent() && !points.isEnd(selectedIndex)) {
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

    /** The gizmo part of the point at {@code at} under the mouse, or null (no projector or camera known). */
    private GizmoPick.Handle gizmoAt(BlockPos at, double mouseX, double mouseY) {
        Optional<ScreenProjector> projector = view.projector();
        Optional<double[]> eye = view.eye();
        if (projector.isEmpty() || eye.isEmpty()) return null;
        double[] o = centre(at);
        return GizmoPick.pickPoint(projector.get(), o[0], o[1], o[2], gizmoScale(o, eye.get()), mouseX, mouseY,
                GizmoPick.PICK_RADIUS_PX).map(GizmoPick.Picked::handle).orElse(null);
    }

    /**
     * The drawn segment under the mouse (an index into {@link Host#drawnSegments}), or -1: no projector, a host of one
     * point, or none near.
     */
    private int segmentAt(double mouseX, double mouseY) {
        Optional<ScreenProjector> projector = view.projector();
        if (projector.isEmpty() || host.maxPoints() == 1) return -1;
        List<List<PathSample>> segments = host.drawnSegments(points.nodes(), points.isClosed());
        int lead = segments.size() - points.segmentCount();
        if (segments.isEmpty() || lead < 0 || lead > 1) return -1;
        return segmentAt(segments, projector.get(), mouseX, mouseY, SEGMENT_PICK_PX);
    }

    /**
     * A click on drawn segment {@code drawn}: a point inserted where the drawn path passes nearest the mouse (the host's
     * {@link Host#insertPoint}), after the segment's first point (before the first point on a host's leading stretch).
     * Returns whether the click was taken.
     */
    private boolean insertOnDrawn(ToolContext c, int drawn, double mouseX, double mouseY) {
        Optional<ScreenProjector> projector = view.projector();
        if (projector.isEmpty()) return false;
        List<BlockPos> nodes = points.nodes();
        List<List<PathSample>> segments = host.drawnSegments(nodes, points.isClosed());
        if (drawn >= segments.size()) return false;
        double[] at = nearestOnScreen(segments.get(drawn), projector.get(), mouseX, mouseY);
        if (at == null) return false;
        int lead = segments.size() - points.segmentCount();
        BlockPos point = air.withinHeight(host.insertPoint(nodes, drawn, at));
        insertAt(c, drawn - lead + 1, point, true, true); // refused past the cap with a toast, the click taken
        return true;
    }

    /**
     * The point of the polyline through {@code samples} drawn nearest the mouse: the nearest projected piece, and the
     * same fraction along it in the world. Null when no piece is on screen.
     */
    static double[] nearestOnScreen(List<PathSample> samples, ScreenProjector projector, double mouseX, double mouseY) {
        double[] a = new double[2];
        double[] b = new double[2];
        double best = Double.POSITIVE_INFINITY;
        double[] found = null;
        for (int i = 1; i < samples.size(); i++) {
            PathSample p = samples.get(i - 1), q = samples.get(i);
            if (!projector.project(p.x(), p.y(), p.z(), a) || !projector.project(q.x(), q.y(), q.z(), b)) continue;
            double abX = b[0] - a[0], abY = b[1] - a[1];
            double length2 = abX * abX + abY * abY;
            double t = length2 > 0 ? ((mouseX - a[0]) * abX + (mouseY - a[1]) * abY) / length2 : 0;
            t = Math.max(0, Math.min(1, t));
            double distance = Math.hypot(mouseX - (a[0] + t * abX), mouseY - (a[1] + t * abY));
            if (distance < best) {
                best = distance;
                found = new double[] {p.x() + t * (q.x() - p.x()), p.y() + t * (q.y() - p.y()), p.z() + t * (q.z() - p.z())};
            }
        }
        return found;
    }

    /** Starts dragging {@code point} by {@code handle} (the centre: freely); false when the ray can't start it. */
    private boolean beginDrag(ToolContext c, PointRef point, GizmoPick.Handle handle, Ray ray) {
        endDrag(c);
        BlockPos at = pointAt(point);
        double[] o = centre(at);
        if (handle.isAxis()) {
            axisDrag = GizmoPick.AxisDrag.begin(handle, o[0], o[1], o[2], ray).orElse(null);
            if (axisDrag == null) return false;
            dragMode = DragMode.AXIS;
        } else if (handle.isPlane()) {
            planeDrag = GizmoPick.PlaneDrag.begin(handle, o[0], o[1], o[2], ray).orElse(null);
            if (planeDrag == null) return false;
            dragMode = DragMode.PLANE;
        } else {
            air.beginDrag(ray, at);
            dragged = point;
            behindGrab = pointOnRay(ray); // what the grab ray passes through (behindGrab is null here)
            double d = air.dragDistance();
            grabOffset = new double[] {o[0] - ray.pointX(d), o[1] - ray.pointY(d), o[2] - ray.pointZ(d)};
            dragMode = DragMode.FREE;
        }
        dragged = point;
        dragFrom = at;
        moved = false;
        active = handle;
        hovered = null;
        c.setPointerCapture(true);
        return true;
    }

    /** Where the drag puts the dragged point for {@code ray}: never the cursor's block, always within the build height. */
    private BlockPos dragTarget(Ray ray) {
        int[] offset = new int[3];
        switch (dragMode) {
            case AXIS -> offset[axisDrag.axis()] = axisDrag.update(ray);
            case PLANE -> offset = planeDrag.update(ray);
            case FREE -> {
                BlockPos onto = pointOnRay(ray);
                if (onto != null) return onto;
                double d = air.dragDistance();
                return air.withinHeight(new BlockPos((int) Math.floor(ray.pointX(d) + grabOffset[0]),
                        (int) Math.floor(ray.pointY(d) + grabOffset[1]), (int) Math.floor(ray.pointZ(d) + grabOffset[2])));
            }
            default -> {
                return dragFrom;
            }
        }
        return air.withinHeight(dragFrom.offset(offset[0], offset[1], offset[2]));
    }

    /**
     * Another point the ray picks while a point is dragged freely (its cell: the drag snaps onto it, so the last point
     * dragged onto the first closes a loop), or null. Points only, never terrain.
     */
    private BlockPos pointOnRay(Ray ray) {
        List<BlockPos> others = new ArrayList<>(points.nodes());
        others.addAll(points.insideNodes());
        others.remove(dragged.role() == Role.OUTLINE ? dragged.index() : points.size() + dragged.index());
        int index = AirPoints.pick(others, ray, WorldCursor.miss(ray.pointX(512), ray.pointY(512), ray.pointZ(512)));
        BlockPos onto = index < 0 ? null : others.get(index);
        // A point the ray already passed through when the drag began is not snapped onto until the ray has left it.
        if (onto != null && onto.equals(behindGrab)) return null;
        behindGrab = null;
        return onto;
    }

    /**
     * Works the drag out again for {@code ray} (a mouse move, a frame while flying, Alt+Scroll) and moves the point;
     * {@code release}: the drag's last move, finished (only when the point moved during the drag).
     */
    private void follow(ToolContext c, Ray ray, boolean release) {
        if (dragged == null) return;
        BlockPos to = ray == null ? pointAt(dragged) : dragTarget(ray);
        if (move(c, dragged, to, release)) {
            moved = true;
        } else if (release && moved) {
            // The last frame already put it there: the host still hears that the drag is done.
            report(c, new Edit(EditKind.MOVE, dragged.role(), dragged.index(), dragFrom, to, true));
        }
    }

    /** A click while the insert key's point follows the cursor: it stays where the cursor's target is, selected. */
    private void placeInserted(ToolContext c, WorldCursor cursor, Ray ray) {
        PointRef at = inserting;
        inserting = null;
        BlockPos to = targetOf(cursor, ray);
        if (to == null) to = pointAt(at);
        BlockPos before = pointAt(at);
        if (!move(c, at, to, true, false)) {
            report(c, new Edit(EditKind.MOVE, at.role(), at.index(), before, to, true), false);
        }
        // Put down, the point is inserted (an inside one added): counted now, not when the key was pressed.
        tally(at.role() == Role.INSIDE ? EditKind.ADD : EditKind.INSERT, at.role());
        select(c, at);
    }

    /** Where a click with {@code cursor} would put a point: on the aimed block, else at the sky marker; or null. */
    private BlockPos targetOf(WorldCursor cursor, Ray ray) {
        if (!cursor.missed()) return host.pointOn(cursor);
        return host.takesSky() ? air.place(cursor, ray, host::pointOn) : null;
    }

    /** Alt+Scroll: the sky marker nearer or farther, or during a free drag the dragged point. Returns whether taken. */
    public boolean onScroll(ToolContext c, ScrollEvent e) {
        air.useWorld(c);
        if (!air.scroll(e)) return false;
        if (dragMode == DragMode.FREE) follow(c, view.cursorRay().orElse(null), false);
        return true;
    }

    /** Esc, Backspace or Delete, and the insert key (see the class); returns whether the action was taken. */
    public boolean onAction(ToolContext c, EditorAction a) {
        switch (a) {
            case CANCEL -> {
                if (dropInserted(c)) return true;
                if (dragging()) {
                    PointRef point = dragged;
                    BlockPos back = dragFrom;
                    endDrag(c);
                    move(c, point, back, true, false);
                    return true;
                }
                if (points.selection().isPresent()) {
                    select(c, null);
                    return true;
                }
                return false;
            }
            case REMOVE_NODE, ERASE_SELECTION -> {
                if (dropInserted(c)) return true;
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
                return insertFollowing(c);
            }
            default -> {
                return false;
            }
        }
    }

    private boolean dropInserted(ToolContext c) {
        if (inserting == null) return false;
        PointRef at = inserting;
        inserting = null;
        remove(c, at, false);
        return true;
    }

    /**
     * The insert key: a new point right after the selected outline point (or a new inside point next to the selected
     * inside one) that follows the cursor until a click. Not taken with nothing selected (E stays the inventory key).
     */
    private boolean insertFollowing(ToolContext c) {
        Optional<PointRef> selected = points.selection();
        // Nothing selected, or a host of one point (Extrude's tip): E stays the inventory key.
        if (selected.isEmpty() || (selected.get().role() == Role.OUTLINE && host.maxPoints() == 1)) return false;
        if (inserting != null || dragging()) return true;
        PointRef from = selected.get();
        BlockPos start = target != null ? target : pointAt(from);
        if (from.role() == Role.INSIDE) {
            if (points.insideCount() >= host.maxInsidePoints()) {
                tooMany(c, Role.INSIDE, host.maxInsidePoints());
                return true;
            }
            int index = points.addInside(start);
            inserting = new PointRef(Role.INSIDE, index);
            report(c, new Edit(EditKind.ADD, Role.INSIDE, index, null, start, false));
            return true;
        }
        int index = insertAt(c, from.index() + 1, start, false, false);
        if (index >= 0) inserting = new PointRef(Role.OUTLINE, index);
        return true;
    }

    /**
     * Once per frame: the air marker, the cursor's target (the guide line's end) and its role, the insert key's point
     * following it, a drag worked out again from the current ray (a held point follows the camera), and what the cursor
     * hovers: a gizmo part, else the drawn path.
     */
    public void frame(ToolContext c, FrameInfo f) {
        WorldCursor cursor = f.cursor();
        Ray ray = view.cursorRay().orElse(null);
        air.useWorld(c);
        if (host.takesSky()) {
            List<BlockPos> all = new ArrayList<>(points.nodes());
            all.addAll(points.insideNodes());
            if (inserting != null) all.remove(inserting.role() == Role.OUTLINE ? inserting.index() : points.size() + inserting.index());
            air.frame(cursor, ray, all);
        } else {
            air.hide();
        }
        target = cursor.missed() ? (host.takesSky() ? air.marker() : null) : host.pointOn(cursor);
        targetRole = target != null && points.isClosed() ? host.roleOf(points, target, !cursor.missed()) : Role.OUTLINE;
        if (inserting != null && target != null) move(c, inserting, target, false);
        if (dragging() && ray != null) follow(c, ray, false);
        hovered = null;
        hoveredSegment = -1;
        if (dragging() || inserting != null) return;
        Optional<BlockPos> selected = selectedPoint();
        if (selected.isPresent()) hovered = gizmoAt(selected.get(), f.mouseX(), f.mouseY());
        if (hovered == null && pick(cursor, ray) == null) hoveredSegment = segmentAt(f.mouseX(), f.mouseY());
    }

    /** The tool is put away or the kind left: a drag ends and the insert key's point is dropped; the points stay. */
    public void deactivate(ToolContext c) {
        dropInserted(c);
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
        dragged = null;
        dragFrom = null;
        axisDrag = null;
        planeDrag = null;
        grabOffset = null;
        behindGrab = null;
    }

    private Optional<BlockPos> selectedPoint() {
        return points.selection().map(this::pointAt);
    }

    private BlockPos pointAt(PointRef ref) {
        return ref.role() == Role.INSIDE ? points.insidePoint(ref.index()) : points.node(ref.index());
    }

    // ---- Hints ----

    /** Whether a drag or the insert key's following point is on (the host shows only {@link #hints} then). */
    public boolean busy() {
        return dragging() || inserting != null;
    }

    /**
     * The point editor's own hints: while {@link #busy}, how to finish or cancel; with a point selected, its gizmo, the
     * insert key and Esc; with a drawn path, clicking it inserts. {@code keyLabel} names a bound key.
     */
    public List<KeyHint> hints(String click, Function<KeyAction, String> keyLabel) {
        List<KeyHint> hints = new ArrayList<>();
        if (inserting != null) {
            hints.add(new KeyHint(click, HINT_PLACE));
            hints.add(new KeyHint("Esc", HINT_DROP));
            return hints;
        }
        if (dragging()) {
            hints.add(new KeyHint("Esc", "sculptory.hint.cancel_drag"));
            return hints;
        }
        if (points.selection().isPresent()) {
            hints.add(new KeyHint(click + " drag", HINT_GIZMO));
            String insert = keyLabel.apply(KeyAction.EXTRUDE_POINT);
            if (!insert.isEmpty() && host.maxPoints() > 1) hints.add(new KeyHint(insert, HINT_INSERT));
            hints.add(new KeyHint("Esc", HINT_DESELECT));
        }
        if (points.segmentCount() > 0 && host.maxPoints() > 1) hints.add(new KeyHint(click, HINT_INSERT_ON_PATH));
        return hints;
    }

    public static final String HINT_PLACE = "sculptory.hint.points.place";
    public static final String HINT_DROP = "sculptory.hint.points.drop";
    public static final String HINT_GIZMO = "sculptory.hint.points.gizmo";
    public static final String HINT_INSERT = "sculptory.hint.points.insert";
    public static final String HINT_DESELECT = "sculptory.hint.points.deselect";
    public static final String HINT_INSERT_ON_PATH = "sculptory.hint.points.insert_on_path";

    // ---- Drawing ----

    /**
     * Draws the points (cubes a little larger than a block, outline and inside, in {@code colour}; the selected one in
     * {@code selectedColour}), the air marker, the hovered drawn segment, the guide line with its end cube, and the
     * selected point's gizmo (with the drag's guide lines while dragging).
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
        if (host.takesSky()) air.render(d);
        if (hoveredSegment >= 0) {
            List<List<PathSample>> segments = host.drawnSegments(outline, points.isClosed());
            if (hoveredSegment < segments.size()) {
                List<PathSample> samples = segments.get(hoveredSegment);
                d.seeThrough(true);
                for (int i = 1; i < samples.size(); i++) {
                    PathSample a = samples.get(i - 1), b = samples.get(i);
                    d.line(a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), HOVERED_SEGMENT_COLOUR);
                }
                d.seeThrough(false);
            }
        }
        Optional<double[]> guide = guideLine();
        if (guide.isPresent()) {
            double[] g = guide.get();
            d.seeThrough(true);
            guide(d, g, OverlayColors.scaleAlpha(GUIDE_COLOUR, GUIDE_ALPHA));
            d.seeThrough(false);
            guide(d, g, GUIDE_COLOUR);
        }
        Optional<BlockPos> at = selectedPoint();
        Optional<double[]> eye = view.eye();
        if (at.isEmpty() || eye.isEmpty() || inserting != null) return;
        double[] o = centre(at.get());
        d.seeThrough(true);
        GizmoRenderer.renderPoint(d::line, o[0], o[1], o[2], gizmoScale(o, eye.get()), hovered, active);
        if (active != null) GizmoRenderer.renderGuides(d::line, o[0], o[1], o[2], active);
        d.seeThrough(false);
    }

    /** The guide line and the small cube at its end. */
    private static void guide(WorldDraw d, double[] g, int argb) {
        d.line(g[0], g[1], g[2], g[3], g[4], g[5], argb);
        cube(d, g[3] - TARGET_HALF, g[4] - TARGET_HALF, g[5] - TARGET_HALF, g[3] + TARGET_HALF, g[4] + TARGET_HALF,
                g[5] + TARGET_HALF, argb);
    }

    /** A point: a translucent cube a little larger than its block, its edges faint through terrain. */
    private static void point(WorldDraw d, BlockPos p, int colour) {
        double g = POINT_GROW;
        d.boxFill(Box.of(p), OverlayColors.scaleAlpha(colour, 0.3));
        d.seeThrough(true);
        cube(d, p.x() - g, p.y() - g, p.z() - g, p.x() + 1 + g, p.y() + 1 + g, p.z() + 1 + g,
                OverlayColors.scaleAlpha(colour, 0.4));
        d.seeThrough(false);
        cube(d, p.x() - g, p.y() - g, p.z() - g, p.x() + 1 + g, p.y() + 1 + g, p.z() + 1 + g, colour);
    }

    private static void cube(WorldDraw d, double x0, double y0, double z0, double x1, double y1, double z1, int argb) {
        double[] ys = {y0, y1}, zs = {z0, z1}, xs = {x0, x1};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                d.line(x0, ys[i], zs[j], x1, ys[i], zs[j], argb);
                d.line(xs[i], y0, zs[j], xs[i], y1, zs[j], argb);
                d.line(xs[i], ys[j], z0, xs[i], ys[j], z1, argb);
            }
        }
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

    /**
     * An open path's samples (through each point's cell centre in order, as {@code PathSampler} makes them) cut into one
     * list per segment of {@code points}: each segment runs from its point's sample to the next point's. A segment
     * between two equal points is that point alone. Chords when the samples don't pass through the points in order
     * (samples made for other points).
     */
    public static List<List<PathSample>> splitAtPoints(List<PathSample> samples, List<BlockPos> points) {
        if (points.size() < 2) return List.of();
        List<List<PathSample>> segments = new ArrayList<>();
        int at = 0;
        int start = indexOf(samples, points.get(0), 0);
        if (start < 0) return chords(points, false);
        at = start;
        for (int s = 0; s + 1 < points.size(); s++) {
            BlockPos to = points.get(s + 1);
            if (to.equals(points.get(s))) {
                segments.add(List.of(samples.get(at), samples.get(at)));
                continue;
            }
            int end = indexOf(samples, to, at + 1);
            if (end < 0) return chords(points, false);
            segments.add(List.copyOf(samples.subList(at, end + 1)));
            at = end;
        }
        return segments;
    }

    private static int indexOf(List<PathSample> samples, BlockPos point, int from) {
        double x = point.x() + 0.5, y = point.y() + 0.5, z = point.z() + 0.5;
        for (int i = from; i < samples.size(); i++) {
            PathSample s = samples.get(i);
            if (Math.abs(s.x() - x) < 1e-9 && Math.abs(s.y() - y) < 1e-9 && Math.abs(s.z() - z) < 1e-9) return i;
        }
        return -1;
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
