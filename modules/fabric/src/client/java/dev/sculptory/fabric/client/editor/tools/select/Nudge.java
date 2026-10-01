package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.fabric.client.editor.tool.EditorAction;
import java.util.Optional;

/**
 * Camera-relative nudge directions: "forward" is the horizontal axis the camera faces most, so the
 * arrow keys move the selection the way the player looks. Minecraft yaw: 0 faces south (+Z),
 * 90 west (-X), 180 north (-Z), 270 east (+X).
 */
public final class Nudge {
    private Nudge() {}

    /** The unit step {dx, dy, dz} for a nudge action, or empty for other actions. */
    public static Optional<int[]> direction(EditorAction action, float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        int[] forward = snap(forwardX, forwardZ);
        int[] right = {-forward[2], 0, forward[0]};
        return switch (action) {
            case NUDGE_FORWARD -> Optional.of(forward);
            case NUDGE_BACK -> Optional.of(new int[] {-forward[0], 0, -forward[2]});
            case NUDGE_RIGHT -> Optional.of(right);
            case NUDGE_LEFT -> Optional.of(new int[] {-right[0], 0, -right[2]});
            case NUDGE_UP -> Optional.of(new int[] {0, 1, 0});
            case NUDGE_DOWN -> Optional.of(new int[] {0, -1, 0});
            default -> Optional.empty();
        };
    }

    /** The cardinal direction {dx, 0, dz} nearest the horizontal vector (x, z). */
    private static int[] snap(double x, double z) {
        if (Math.abs(x) > Math.abs(z)) {
            return new int[] {x > 0 ? 1 : -1, 0, 0};
        }
        return new int[] {0, 0, z >= 0 ? 1 : -1};
    }
}
