package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.fabric.client.editor.world.GizmoPick;
import org.jetbrains.annotations.Nullable;

/**
 * Draws the placement gizmo: X/Y/Z arrows and three rotation rings (around up, east-west and north-south, each in its
 * axis's colour; {@code rings} false leaves them out, for a stack) at constant screen size, using the
 * geometry {@link GizmoPick} picks against. The gizmo is drawn see-through so it stays usable when
 * buried in terrain; the hovered handle is highlighted. It also draws the path points' gizmo ({@link #renderPoint}:
 * arrows, plane squares and a centre cube) and its drag guide lines ({@link #renderGuides}), drawn into any
 * {@link Lines} sink (a tool's {@code WorldDraw}).
 */
public final class GizmoRenderer {
    public static final int COLOR_X = 0xFFE8483C;
    public static final int COLOR_Y = 0xFF5CD13C;
    public static final int COLOR_Z = 0xFF3F82FF;
    public static final int COLOR_RING = 0xFFE8B83C;
    public static final int COLOR_HOVERED = 0xFFFFF27A;
    private static final int HEAD_SPOKES = 8;

    private GizmoRenderer() {}

    public static void render(LineBatch lines, double originX, double originY, double originZ, @Nullable GizmoPick.Handle hovered) {
        render(lines, originX, originY, originZ, hovered, true);
    }

    public static void render(LineBatch lines, double originX, double originY, double originZ,
                              @Nullable GizmoPick.Handle hovered, boolean rings) {
        double dx = originX - lines.cameraX();
        double dy = originY - lines.cameraY();
        double dz = originZ - lines.cameraZ();
        double scale = GizmoPick.scale(Math.sqrt(dx * dx + dy * dy + dz * dz));

        for (int axis = 0; axis < 3; axis++) {
            GizmoPick.Handle handle = GizmoPick.Handle.ofAxis(axis);
            int color = handle == hovered ? COLOR_HOVERED : axisColor(axis);
            arrow(seeThrough(lines), originX, originY, originZ, axis, scale, color);
        }
        if (!rings) return;
        for (int axis = 0; axis < 3; axis++) {
            GizmoPick.Handle handle = GizmoPick.Handle.ofRing(axis);
            int color = handle == hovered ? COLOR_HOVERED : axisColor(axis);
            ring(lines, originX, originY, originZ, axis, GizmoPick.RING_RADIUS * scale, color);
        }
    }

    /**
     * Where the point gizmo's lines go, in block coordinates: a tool's {@code WorldDraw::line} (the tool sets see-through
     * first) or the overlay's {@link LineBatch}.
     */
    @FunctionalInterface
    public interface Lines {
        void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb);
    }

    /** How far the guide lines of a point drag run each way from the point (blocks). */
    public static final double GUIDE_LENGTH = 256;
    /** The guide lines' colour alpha, against the axis colours. */
    public static final double GUIDE_ALPHA = 0.55;

    /**
     * Draws the path points' gizmo at {@code origin}, at {@code scale} blocks per gizmo unit
     * ({@link GizmoPick#scale}): the X/Y/Z arrows, the three plane squares (outlined and hatched, each in the colour of
     * the axis it is across) and the centre cube, the geometry {@link GizmoPick#pickPoint} picks. The part being dragged
     * ({@code active}) and, when nothing is dragged, the hovered one draw lighter ({@link #lighter}). No rings.
     */
    public static void renderPoint(Lines lines, double originX, double originY, double originZ, double scale,
                                   @Nullable GizmoPick.Handle hovered, @Nullable GizmoPick.Handle active) {
        GizmoPick.Handle lit = active != null ? active : hovered;
        for (int axis = 0; axis < 3; axis++) {
            GizmoPick.Handle handle = GizmoPick.Handle.ofAxis(axis);
            int color = handle == lit ? lighter(axisColor(axis)) : axisColor(axis);
            arrow(lines, originX, originY, originZ, axis, scale, color);
        }
        double[] a = new double[3];
        double[] b = new double[3];
        for (int normal = 0; normal < 3; normal++) {
            GizmoPick.Handle handle = GizmoPick.Handle.ofPlane(normal);
            int color = handle == lit ? lighter(axisColor(normal)) : axisColor(normal);
            for (int i = 0; i < 4; i++) {
                GizmoPick.planeCorner(normal, i, scale, a);
                GizmoPick.planeCorner(normal, (i + 1) % 4, scale, b);
                lines.line(originX + a[0], originY + a[1], originZ + a[2], originX + b[0], originY + b[1], originZ + b[2],
                        color);
            }
            // Hatching: three lines across the square, so it reads as a surface.
            int u = (normal + 1) % 3;
            for (int k = 1; k <= 3; k++) {
                double at = (GizmoPick.PLANE_INNER + (GizmoPick.PLANE_OUTER - GizmoPick.PLANE_INNER) * k / 4) * scale;
                GizmoPick.planeCorner(normal, 0, scale, a);
                GizmoPick.planeCorner(normal, 3, scale, b);
                a[u] = at;
                b[u] = at;
                lines.line(originX + a[0], originY + a[1], originZ + a[2], originX + b[0], originY + b[1], originZ + b[2],
                        OverlayColors.scaleAlpha(color, 0.6));
            }
        }
        int centre = lit == GizmoPick.Handle.CENTRE ? COLOR_HOVERED : 0xFFFFFFFF;
        double h = GizmoPick.CENTRE_HALF * scale;
        cube(lines, originX - h, originY - h, originZ - h, originX + h, originY + h, originZ + h, centre);
    }

    /**
     * While a point is dragged by {@code active}: a long line through {@code origin} along each axis the drag moves
     * along ({@link GizmoPick.Handle#moves}; none for the centre, which moves freely), {@link #GUIDE_LENGTH} blocks each
     * way, in the axis colours.
     */
    public static void renderGuides(Lines lines, double originX, double originY, double originZ,
                                    GizmoPick.Handle active) {
        if (active.isCentre()) return;
        for (int axis = 0; axis < 3; axis++) {
            if (!active.moves(axis)) continue;
            double[] from = along(originX, originY, originZ, axis, -GUIDE_LENGTH);
            double[] to = along(originX, originY, originZ, axis, GUIDE_LENGTH);
            lines.line(from[0], from[1], from[2], to[0], to[1], to[2], OverlayColors.scaleAlpha(axisColor(axis), GUIDE_ALPHA));
        }
    }

    /** A colour halfway to white, alpha kept: how a hovered or dragged point gizmo part shows. */
    public static int lighter(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (argb & 0xFF000000) | ((r + 255) / 2) << 16 | ((g + 255) / 2) << 8 | (b + 255) / 2;
    }

    private static void cube(Lines lines, double x0, double y0, double z0, double x1, double y1, double z1, int color) {
        double[] xs = {x0, x1}, ys = {y0, y1}, zs = {z0, z1};
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                lines.line(x0, ys[i], zs[j], x1, ys[i], zs[j], color);
                lines.line(xs[i], y0, zs[j], xs[i], y1, zs[j], color);
                lines.line(xs[i], ys[j], z0, xs[i], ys[j], z1, color);
            }
        }
    }

    /** {@code batch}'s lines drawn see-through, as the placement gizmo is. */
    private static Lines seeThrough(LineBatch batch) {
        return (x1, y1, z1, x2, y2, z2, argb) -> batch.line(x1, y1, z1, x2, y2, z2, argb, true);
    }

    static int axisColor(int axis) {
        return switch (axis) {
            case 0 -> COLOR_X;
            case 1 -> COLOR_Y;
            default -> COLOR_Z;
        };
    }

    private static void arrow(Lines lines, double ox, double oy, double oz, int axis, double scale, int color) {
        double length = GizmoPick.ARROW_LENGTH * scale;
        double headBase = (GizmoPick.ARROW_LENGTH - GizmoPick.HEAD_LENGTH) * scale;
        double headRadius = GizmoPick.HEAD_RADIUS * scale;
        double[] tip = along(ox, oy, oz, axis, length);
        double[] base = along(ox, oy, oz, axis, headBase);
        lines.line(ox, oy, oz, tip[0], tip[1], tip[2], color);

        // Wireframe cone: spokes from the tip to a circle around the head base, plus the circle.
        int u = (axis + 1) % 3;
        int v = (axis + 2) % 3;
        double[] previous = null;
        for (int i = 0; i <= HEAD_SPOKES; i++) {
            double angle = 2 * Math.PI * (i % HEAD_SPOKES) / HEAD_SPOKES;
            double[] point = base.clone();
            point[u] += headRadius * Math.cos(angle);
            point[v] += headRadius * Math.sin(angle);
            if (i < HEAD_SPOKES) {
                lines.line(tip[0], tip[1], tip[2], point[0], point[1], point[2], color);
            }
            if (previous != null) {
                lines.line(previous[0], previous[1], previous[2], point[0], point[1], point[2], color);
            }
            previous = point;
        }
    }

    private static void ring(LineBatch lines, double ox, double oy, double oz, int axis, double radius, int color) {
        double[] previous = new double[3];
        double[] next = new double[3];
        GizmoPick.ringPoint(axis, radius, 0, previous);
        for (int i = 1; i <= GizmoPick.RING_SEGMENTS; i++) {
            GizmoPick.ringPoint(axis, radius, 2 * Math.PI * i / GizmoPick.RING_SEGMENTS, next);
            lines.line(ox + previous[0], oy + previous[1], oz + previous[2], ox + next[0], oy + next[1], oz + next[2],
                    color, true);
            double[] swap = previous;
            previous = next;
            next = swap;
        }
    }

    private static double[] along(double ox, double oy, double oz, int axis, double distance) {
        return new double[] {
            ox + (axis == 0 ? distance : 0),
            oy + (axis == 1 ? distance : 0),
            oz + (axis == 2 ? distance : 0)
        };
    }
}
