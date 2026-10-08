package dev.sculptory.fabric.client.editor.world;

import java.util.Optional;

/**
 * Picking and dragging for the placement gizmo: X/Y/Z arrows and three rotation rings around an origin: around up (Y),
 * around east-west (X) and around north-south (Z).
 * Pure maths; {@code GizmoRenderer} draws exactly this geometry.
 *
 * <p>The gizmo keeps a constant screen size: one gizmo unit is {@link #scale(double)} blocks, which
 * grows with the distance to the camera. Picking happens in screen space: a handle is hit when the
 * cursor is within {@link #PICK_RADIUS_PX} pixels of its projected segments.
 *
 * <p>Dragging along an axis uses the point on the axis line closest to the cursor ray and snaps the
 * movement to whole blocks ({@link AxisDrag}).
 */
public final class GizmoPick {
    public static final double PICK_RADIUS_PX = 6.0;
    /** Blocks per gizmo unit, per block of camera distance. */
    public static final double SCALE_PER_DISTANCE = 0.12;
    public static final double MIN_SCALE = 0.05;
    /** Arrow shaft length, in gizmo units. */
    public static final double ARROW_LENGTH = 1.0;
    /** Arrow head length and base radius, in gizmo units. */
    public static final double HEAD_LENGTH = 0.22;
    public static final double HEAD_RADIUS = 0.07;
    /** Rotation ring radius, in gizmo units. */
    public static final double RING_RADIUS = 0.75;
    public static final int RING_SEGMENTS = 64;

    private GizmoPick() {}

    /** A gizmo part. */
    public enum Handle {
        AXIS_X(0, -1),
        AXIS_Y(1, -1),
        AXIS_Z(2, -1),
        /** The ring around up: turns the yaw. */
        ROTATE_Y(-1, 1),
        /** The ring around east-west: turns about the X axis (pitch). */
        ROTATE_X(-1, 0),
        /** The ring around north-south: turns about the Z axis (roll). */
        ROTATE_Z(-1, 2);

        private final int axis;
        private final int ringAxis;

        Handle(int axis, int ringAxis) {
            this.axis = axis;
            this.ringAxis = ringAxis;
        }

        /** 0 = X, 1 = Y, 2 = Z; -1 for a rotation ring. */
        public int axis() {
            return axis;
        }

        public boolean isAxis() {
            return axis >= 0;
        }

        /** The axis a rotation ring turns about (0 = X, 1 = Y, 2 = Z); -1 for an arrow. */
        public int ringAxis() {
            return ringAxis;
        }

        public boolean isRing() {
            return ringAxis >= 0;
        }

        /** The ring around {@code axis}. */
        public static Handle ofRing(int axis) {
            return switch (axis) {
                case 0 -> ROTATE_X;
                case 1 -> ROTATE_Y;
                case 2 -> ROTATE_Z;
                default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
            };
        }

        public static Handle ofAxis(int axis) {
            return switch (axis) {
                case 0 -> AXIS_X;
                case 1 -> AXIS_Y;
                case 2 -> AXIS_Z;
                default -> throw new IllegalArgumentException("axis must be 0, 1 or 2: " + axis);
            };
        }
    }

    /** A picked handle and the cursor's screen distance to it. */
    public record Picked(Handle handle, double distancePx) {}

    /** Blocks per gizmo unit at the given camera distance. */
    public static double scale(double distanceToCamera) {
        return Math.max(MIN_SCALE, distanceToCamera * SCALE_PER_DISTANCE);
    }

    /**
     * Picks with the camera's projection and the same constant-screen-size scale the renderer uses,
     * within {@link #PICK_RADIUS_PX} pixels.
     *
     * @param cursorX cursor position in a screen of {@code screenWidth x screenHeight} (e.g. GUI-scaled)
     */
    public static Optional<Picked> pick(
            CameraSnapshot camera,
            double originX,
            double originY,
            double originZ,
            double cursorX,
            double cursorY,
            double screenWidth,
            double screenHeight) {
        double scale = scale(camera.distanceTo(originX, originY, originZ));
        return pick(camera.projector(screenWidth, screenHeight), originX, originY, originZ, scale, cursorX, cursorY, PICK_RADIUS_PX);
    }

    /**
     * Picks the gizmo handle nearest the cursor, if any is within {@code radiusPx}.
     *
     * @param projector world to screen, in the same pixel space as the cursor
     * @param scale blocks per gizmo unit, normally {@link #scale(double)}
     */
    public static Optional<Picked> pick(
            ScreenProjector projector,
            double originX,
            double originY,
            double originZ,
            double scale,
            double cursorX,
            double cursorY,
            double radiusPx) {
        double[] a = new double[2];
        double[] b = new double[2];
        Handle best = null;
        double bestDistance = Double.POSITIVE_INFINITY;

        if (projector.project(originX, originY, originZ, a)) {
            double length = ARROW_LENGTH * scale;
            for (int axis = 0; axis < 3; axis++) {
                double tipX = originX + (axis == 0 ? length : 0);
                double tipY = originY + (axis == 1 ? length : 0);
                double tipZ = originZ + (axis == 2 ? length : 0);
                if (!projector.project(tipX, tipY, tipZ, b)) {
                    continue;
                }
                double distance = distanceToSegment(cursorX, cursorY, a[0], a[1], b[0], b[1]);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = Handle.ofAxis(axis);
                }
            }
        }

        double ringRadius = RING_RADIUS * scale;
        double[] p = new double[3];
        // The up ring first: where rings cross, it wins.
        for (int ringAxis : new int[] {1, 0, 2}) {
            ringPoint(ringAxis, ringRadius, 0, p);
            boolean previousVisible = projector.project(originX + p[0], originY + p[1], originZ + p[2], a);
            for (int i = 1; i <= RING_SEGMENTS; i++) {
                ringPoint(ringAxis, ringRadius, 2 * Math.PI * i / RING_SEGMENTS, p);
                boolean visible = projector.project(originX + p[0], originY + p[1], originZ + p[2], b);
                if (previousVisible && visible) {
                    double distance = distanceToSegment(cursorX, cursorY, a[0], a[1], b[0], b[1]);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = Handle.ofRing(ringAxis);
                    }
                }
                double[] swap = a;
                a = b;
                b = swap;
                previousVisible = visible;
            }
        }

        if (best == null || bestDistance > radiusPx) {
            return Optional.empty();
        }
        return Optional.of(new Picked(best, bestDistance));
    }

    /** Distance from point P to segment AB in 2D. */
    public static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double abX = bx - ax;
        double abY = by - ay;
        double lengthSquared = abX * abX + abY * abY;
        double t = lengthSquared > 0 ? ((px - ax) * abX + (py - ay) * abY) / lengthSquared : 0;
        t = Math.max(0, Math.min(1, t));
        double dx = px - (ax + t * abX);
        double dy = py - (ay + t * abY);
        return Math.sqrt(dx * dx + dy * dy);
    }

    /**
     * Parameter {@code s} of the point {@code origin + s * axisUnit} on the axis line closest to the
     * ray, or {@code NaN} when the ray is (nearly) parallel to the axis.
     */
    public static double closestAxisParameter(double originX, double originY, double originZ, int axis, Ray ray) {
        return closestLineParameter(
                originX, originY, originZ, axis == 0 ? 1 : 0, axis == 1 ? 1 : 0, axis == 2 ? 1 : 0, ray);
    }

    /**
     * Parameter {@code s} of the point {@code origin + s * u} on a line closest to the ray (closest
     * points of two lines), or {@code NaN} when they are (nearly) parallel.
     */
    public static double closestLineParameter(
            double originX, double originY, double originZ, double ux, double uy, double uz, Ray ray) {
        double wX = originX - ray.originX();
        double wY = originY - ray.originY();
        double wZ = originZ - ray.originZ();
        double a = ux * ux + uy * uy + uz * uz;
        double b = ux * ray.dirX() + uy * ray.dirY() + uz * ray.dirZ();
        double c = 1.0; // ray directions are unit length
        double d = ux * wX + uy * wY + uz * wZ;
        double e = ray.dirX() * wX + ray.dirY() * wY + ray.dirZ() * wZ;
        double denominator = a * c - b * b;
        if (!(a > 0) || denominator <= 1e-9 * a * c) {
            return Double.NaN;
        }
        return (b * e - c * d) / denominator;
    }

    /** Whole blocks moved from the grab parameter to the current one; 0 when either is NaN. */
    public static int snapBlocks(double grabParameter, double currentParameter) {
        if (Double.isNaN(grabParameter) || Double.isNaN(currentParameter)) {
            return 0;
        }
        return (int) Math.round(currentParameter - grabParameter);
    }

    /**
     * Angle (radians, {@code atan2(dz, dx)}) of where the ray crosses the ring's horizontal plane,
     * measured around the origin; positive turns from +X towards +Z (clockwise seen from above).
     * {@code NaN} when the ray does not reach the plane.
     */
    public static double ringAngle(double originX, double originY, double originZ, Ray ray) {
        double t = ray.distanceToHorizontalPlane(originY);
        if (Double.isNaN(t)) {
            return Double.NaN;
        }
        return Math.atan2(ray.pointZ(t) - originZ, ray.pointX(t) - originX);
    }

    /**
     * The offset from the origin of the point at {@code angle} (radians) on the ring of {@code radius} around
     * {@code axis}, into {@code out}: angle 0 lies on the next axis round (X ring: +Y, Y ring: +X, Z ring: +X) and positive
     * angles turn as the orientation's angles do (X ring: up to south, Y ring: east to south, Z ring: east to up).
     * {@code GizmoRenderer} draws the rings with it.
     */
    public static void ringPoint(int axis, double radius, double angle, double[] out) {
        double c = radius * Math.cos(angle), s = radius * Math.sin(angle);
        switch (axis) {
            case 0 -> {
                out[0] = 0;
                out[1] = c;
                out[2] = s;
            }
            case 1 -> {
                out[0] = c;
                out[1] = 0;
                out[2] = s;
            }
            default -> {
                out[0] = c;
                out[1] = s;
                out[2] = 0;
            }
        }
    }

    /**
     * Angle (radians) of where the ray crosses the plane of the ring around {@code axis}, measured around the origin as
     * {@link #ringPoint} measures it (so a positive change turns the way the orientation's angle about that axis does).
     * {@code NaN} when the ray runs along the plane or away from it.
     */
    public static double ringAngle(int axis, double originX, double originY, double originZ, Ray ray) {
        double[] origin = {originX, originY, originZ};
        double[] from = {ray.originX(), ray.originY(), ray.originZ()};
        double[] direction = {ray.dirX(), ray.dirY(), ray.dirZ()};
        if (Math.abs(direction[axis]) < 1e-9) return Double.NaN;
        double t = (origin[axis] - from[axis]) / direction[axis];
        if (t < 0) return Double.NaN;
        double dx = ray.pointX(t) - originX, dy = ray.pointY(t) - originY, dz = ray.pointZ(t) - originZ;
        return switch (axis) {
            case 0 -> Math.atan2(dz, dy);
            case 1 -> Math.atan2(dz, dx);
            default -> Math.atan2(dy, dx);
        };
    }

    /** Snaps a ring drag of every {@link #RING_SNAP} 1/100 degree. */
    public static final int RING_SNAP = 1_500;

    /**
     * A turn of {@code radians} as a whole number of 1/100 degree: snapped to {@link #RING_SNAP} (15°) steps, or to the
     * nearest 1/100 degree when {@code free} (Ctrl held).
     */
    public static int snapTurn(double radians, boolean free) {
        double centidegrees = Math.toDegrees(radians) * 100;
        if (free) return (int) Math.round(centidegrees);
        return (int) Math.round(centidegrees / RING_SNAP) * RING_SNAP;
    }

    /**
     * An in-progress drag of a rotation ring. {@link #update} returns the turn since the ring was grabbed, in 1/100
     * degree, followed round past a half turn (each move adds the change since the last one); a ray that misses the
     * ring's plane keeps the last turn.
     */
    public static final class RingDrag {
        private final int axis;
        private final double originX;
        private final double originY;
        private final double originZ;
        private double last;
        private double total;
        private int turn;

        private RingDrag(int axis, double originX, double originY, double originZ, double grab) {
            this.axis = axis;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.last = grab;
        }

        /** Starts a drag; empty when the handle is not a ring or the ray misses its plane. */
        public static Optional<RingDrag> begin(Handle handle, double originX, double originY, double originZ, Ray ray) {
            if (!handle.isRing()) return Optional.empty();
            double grab = ringAngle(handle.ringAxis(), originX, originY, originZ, ray);
            if (Double.isNaN(grab)) return Optional.empty();
            return Optional.of(new RingDrag(handle.ringAxis(), originX, originY, originZ, grab));
        }

        /** The turn so far, in 1/100 degree: snapped to 15° unless {@code free}. */
        public int update(Ray ray, boolean free) {
            double angle = ringAngle(axis, originX, originY, originZ, ray);
            if (!Double.isNaN(angle)) {
                total += Math.IEEEremainder(angle - last, 2 * Math.PI);
                last = angle;
                turn = snapTurn(total, free);
            }
            return turn;
        }

        public int turn() {
            return turn;
        }

        /** 0 = X, 1 = Y, 2 = Z. */
        public int axis() {
            return axis;
        }
    }

    /** Quarter turns (-2..2) between two ring angles, rounded; 0 when either is NaN. */
    public static int snapQuarterTurns(double grabAngle, double currentAngle) {
        if (Double.isNaN(grabAngle) || Double.isNaN(currentAngle)) {
            return 0;
        }
        double delta = Math.IEEEremainder(currentAngle - grabAngle, 2 * Math.PI);
        return (int) Math.round(delta / (Math.PI / 2));
    }

    /**
     * An in-progress drag along one gizmo axis. {@link #update} returns the whole-block offset from
     * where the axis was grabbed; if the ray turns parallel to the axis the last offset is kept.
     */
    public static final class AxisDrag {
        private final int axis;
        private final double originX;
        private final double originY;
        private final double originZ;
        private final double grab;
        private int blocks;

        private AxisDrag(int axis, double originX, double originY, double originZ, double grab) {
            this.axis = axis;
            this.originX = originX;
            this.originY = originY;
            this.originZ = originZ;
            this.grab = grab;
        }

        /** Starts a drag; empty when the handle is not an axis or the ray is parallel to it. */
        public static Optional<AxisDrag> begin(Handle handle, double originX, double originY, double originZ, Ray ray) {
            if (!handle.isAxis()) {
                return Optional.empty();
            }
            double grab = closestAxisParameter(originX, originY, originZ, handle.axis(), ray);
            if (Double.isNaN(grab)) {
                return Optional.empty();
            }
            return Optional.of(new AxisDrag(handle.axis(), originX, originY, originZ, grab));
        }

        /** Whole blocks moved along the axis for the current cursor ray. */
        public int update(Ray ray) {
            double current = closestAxisParameter(originX, originY, originZ, axis, ray);
            if (!Double.isNaN(current)) {
                blocks = snapBlocks(grab, current);
            }
            return blocks;
        }

        public int blocks() {
            return blocks;
        }

        public int axis() {
            return axis;
        }
    }
}
