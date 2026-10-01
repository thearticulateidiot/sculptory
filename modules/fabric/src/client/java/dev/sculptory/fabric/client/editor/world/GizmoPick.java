package dev.sculptory.fabric.client.editor.world;

import java.util.Optional;

/**
 * Picking and dragging for the placement gizmo: X/Y/Z arrows and a Y rotation ring around an origin.
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
        AXIS_X(0),
        AXIS_Y(1),
        AXIS_Z(2),
        ROTATE_Y(-1);

        private final int axis;

        Handle(int axis) {
            this.axis = axis;
        }

        /** 0 = X, 1 = Y, 2 = Z; -1 for the rotation ring. */
        public int axis() {
            return axis;
        }

        public boolean isAxis() {
            return axis >= 0;
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
        boolean previousVisible = projector.project(originX + ringRadius, originY, originZ, a);
        for (int i = 1; i <= RING_SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / RING_SEGMENTS;
            boolean visible = projector.project(
                    originX + ringRadius * Math.cos(angle), originY, originZ + ringRadius * Math.sin(angle), b);
            if (previousVisible && visible) {
                double distance = distanceToSegment(cursorX, cursorY, a[0], a[1], b[0], b[1]);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = Handle.ROTATE_Y;
                }
            }
            double[] swap = a;
            a = b;
            b = swap;
            previousVisible = visible;
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
