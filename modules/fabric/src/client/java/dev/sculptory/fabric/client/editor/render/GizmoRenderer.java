package dev.sculptory.fabric.client.editor.render;

import dev.sculptory.fabric.client.editor.world.GizmoPick;
import org.jetbrains.annotations.Nullable;

/**
 * Draws the placement gizmo: X/Y/Z arrows and a Y rotation ring at constant screen size, using the
 * geometry {@link GizmoPick} picks against. The gizmo is drawn see-through so it stays usable when
 * buried in terrain; the hovered handle is highlighted.
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
        double dx = originX - lines.cameraX();
        double dy = originY - lines.cameraY();
        double dz = originZ - lines.cameraZ();
        double scale = GizmoPick.scale(Math.sqrt(dx * dx + dy * dy + dz * dz));

        for (int axis = 0; axis < 3; axis++) {
            GizmoPick.Handle handle = GizmoPick.Handle.ofAxis(axis);
            int color = handle == hovered ? COLOR_HOVERED : axisColor(axis);
            arrow(lines, originX, originY, originZ, axis, scale, color);
        }
        int ringColor = hovered == GizmoPick.Handle.ROTATE_Y ? COLOR_HOVERED : COLOR_RING;
        ring(lines, originX, originY, originZ, GizmoPick.RING_RADIUS * scale, ringColor);
    }

    static int axisColor(int axis) {
        return switch (axis) {
            case 0 -> COLOR_X;
            case 1 -> COLOR_Y;
            default -> COLOR_Z;
        };
    }

    private static void arrow(LineBatch lines, double ox, double oy, double oz, int axis, double scale, int color) {
        double length = GizmoPick.ARROW_LENGTH * scale;
        double headBase = (GizmoPick.ARROW_LENGTH - GizmoPick.HEAD_LENGTH) * scale;
        double headRadius = GizmoPick.HEAD_RADIUS * scale;
        double[] tip = along(ox, oy, oz, axis, length);
        double[] base = along(ox, oy, oz, axis, headBase);
        lines.line(ox, oy, oz, tip[0], tip[1], tip[2], color, true);

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
                lines.line(tip[0], tip[1], tip[2], point[0], point[1], point[2], color, true);
            }
            if (previous != null) {
                lines.line(previous[0], previous[1], previous[2], point[0], point[1], point[2], color, true);
            }
            previous = point;
        }
    }

    private static void ring(LineBatch lines, double ox, double oy, double oz, double radius, int color) {
        double previousX = ox + radius;
        double previousZ = oz;
        for (int i = 1; i <= GizmoPick.RING_SEGMENTS; i++) {
            double angle = 2 * Math.PI * i / GizmoPick.RING_SEGMENTS;
            double x = ox + radius * Math.cos(angle);
            double z = oz + radius * Math.sin(angle);
            lines.line(previousX, oy, previousZ, x, oy, z, color, true);
            previousX = x;
            previousZ = z;
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
