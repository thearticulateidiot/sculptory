package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.editor.world.Ray;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The Select tool's drag state machine, free of Minecraft types:
 * <ul>
 *   <li><b>create</b>: press on terrain, drag to the opposite corner;</li>
 *   <li><b>resize</b>: drag a face handle along its axis;</li>
 *   <li><b>move</b>: Ctrl-drag inside the box, sliding it in the plane of the face that was grabbed.</li>
 * </ul>
 * Each update returns the box to show. {@link #cancel()} returns the box from before the drag
 * (possibly none), for Esc.
 */
public final class SelectionDrag {
    /** What the drag is doing. */
    public enum Mode { IDLE, CREATE, RESIZE, MOVE }

    private Mode mode = Mode.IDLE;
    private Box before;
    private Box start;
    private Box current;
    private BlockPos anchor;
    private BoxFace face;
    private double grabCoordinate;
    private double[] grabPoint;

    public Mode mode() {
        return mode;
    }

    public boolean isActive() {
        return mode != Mode.IDLE;
    }

    /** The box being dragged, or null when idle. */
    public Box current() {
        return current;
    }

    /** The face being resized or grabbed, or null. */
    public BoxFace face() {
        return face;
    }

    /** Starts a new box at {@code cell}; {@code before} is the selection to restore on cancel. */
    public Box beginCreate(Box before, BlockPos cell) {
        Objects.requireNonNull(cell);
        reset();
        this.mode = Mode.CREATE;
        this.before = before;
        this.anchor = cell;
        this.current = Box.of(cell);
        return current;
    }

    /** The box from the anchor to {@code cell}. */
    public Box updateCreate(BlockPos cell) {
        requireMode(Mode.CREATE);
        current = Box.of(anchor, Objects.requireNonNull(cell));
        return current;
    }

    /**
     * Starts dragging {@code face} of {@code box}. Returns false (and stays idle) when the ray runs
     * along the face's axis, where the drag distance is undefined.
     */
    public boolean beginResize(Box box, BoxFace face, Ray ray) {
        Objects.requireNonNull(box);
        Objects.requireNonNull(face);
        OptionalDouble grab = axisCoordinate(box, face, ray);
        if (grab.isEmpty()) {
            return false;
        }
        reset();
        this.mode = Mode.RESIZE;
        this.before = box;
        this.start = box;
        this.current = box;
        this.face = face;
        this.grabCoordinate = grab.getAsDouble();
        return true;
    }

    /** The box with the dragged face moved by the whole blocks the cursor travelled along its axis. */
    public Box updateResize(Ray ray) {
        requireMode(Mode.RESIZE);
        OptionalDouble now = axisCoordinate(start, face, ray);
        if (now.isPresent()) {
            int delta = blocks(now.getAsDouble() - grabCoordinate);
            current = SelectionModel.moveFace(start, face, delta);
        }
        return current;
    }

    /**
     * Starts moving {@code box}, grabbed at {@code grabPoint} on {@code face}; the box slides in that
     * face's plane. Returns false when the grab point can't be found on the ray.
     */
    public boolean beginMove(Box box, BoxFace face, Ray ray) {
        Objects.requireNonNull(box);
        Objects.requireNonNull(face);
        double plane = planeOf(box, face);
        double[] point = SelectionModel.intersectPlane(ray, face.axis(), plane);
        if (point == null) {
            return false;
        }
        reset();
        this.mode = Mode.MOVE;
        this.before = box;
        this.start = box;
        this.current = box;
        this.face = face;
        this.grabPoint = point;
        return true;
    }

    /** The box offset by the whole blocks the cursor moved within the grabbed face's plane. */
    public Box updateMove(Ray ray) {
        requireMode(Mode.MOVE);
        double[] point = SelectionModel.intersectPlane(ray, face.axis(), grabPoint[face.axis()]);
        if (point != null) {
            int[] delta = new int[3];
            for (int axis = 0; axis < 3; axis++) {
                if (axis != face.axis()) {
                    delta[axis] = blocks(point[axis] - grabPoint[axis]);
                }
            }
            current = SelectionModel.nudge(start, delta[0], delta[1], delta[2]);
        }
        return current;
    }

    /** Ends the drag, keeping the current box. Returns it (null if idle). */
    public Box finish() {
        Box result = current;
        reset();
        return result;
    }

    /** Abandons the drag. Returns the selection from before it started (possibly null), if a drag was active. */
    public Optional<Box> cancel() {
        if (mode == Mode.IDLE) {
            return Optional.empty();
        }
        Box restore = before;
        reset();
        return Optional.ofNullable(restore);
    }

    /** The most blocks one drag moves a face or the box: past the world on either side, far from int overflow. */
    static final int MAX_DRAG = 1 << 26;

    /**
     * A drag distance in whole blocks, cut to {@link #MAX_DRAG}: at grazing angles the cursor ray meets the drag plane
     * arbitrarily far away.
     */
    static int blocks(double distance) {
        if (!(Math.abs(distance) < MAX_DRAG)) {
            return distance < 0 ? -MAX_DRAG : MAX_DRAG; // also NaN, as the far side
        }
        return (int) Math.round(distance);
    }

    private void reset() {
        mode = Mode.IDLE;
        before = null;
        start = null;
        current = null;
        anchor = null;
        face = null;
        grabPoint = null;
    }

    private void requireMode(Mode expected) {
        if (mode != expected) {
            throw new IllegalStateException("Not in " + expected + " drag: " + mode);
        }
    }

    private static OptionalDouble axisCoordinate(Box box, BoxFace face, Ray ray) {
        var aabb = SelectionModel.toAabb(box);
        return SelectionModel.closestOnAxis(ray,
                aabb.faceCenter(face, 0), aabb.faceCenter(face, 1), aabb.faceCenter(face, 2), face.axis());
    }

    private static double planeOf(Box box, BoxFace face) {
        var aabb = SelectionModel.toAabb(box);
        return face.sign() < 0 ? aabb.min(face.axis()) : aabb.max(face.axis());
    }
}
