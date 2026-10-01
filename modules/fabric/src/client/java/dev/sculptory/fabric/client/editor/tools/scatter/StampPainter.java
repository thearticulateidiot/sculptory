package dev.sculptory.fabric.client.editor.tools.scatter;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a painting cursor path into stamp centres: one where the path starts, then one every {@code spacing} blocks
 * along it (the gaps of a fast drag are filled in). A press that starts off the terrain begins its path at the first
 * point on it. Pure; client thread only.
 */
public final class StampPainter {
    /** Stamps added by one cursor move at most (the area's cap stops painting long before). */
    static final int MAX_PER_MOVE = 256;

    private boolean pressed;
    private boolean started;
    private double lastX;
    private double lastZ;

    /** The spacing of stamps of radius {@code radius}: half the radius, at least one block. */
    public static double spacing(int radius) {
        return Math.max(1.0, radius / 2.0);
    }

    /** A press began; its path starts at the first point given. */
    public void press() {
        pressed = true;
        started = false;
    }

    /** The cursor is at (x, z) while pressed: the stamp centres now due, oldest first. */
    public List<int[]> moveTo(double x, double z, double spacing) {
        List<int[]> centres = new ArrayList<>();
        if (!pressed) return centres;
        if (!started) {
            started = true;
            lastX = x;
            lastZ = z;
            centres.add(new int[] {(int) Math.floor(x), (int) Math.floor(z)});
            return centres;
        }
        double dx = x - lastX;
        double dz = z - lastZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < spacing) return centres;
        double stepX = dx / distance * spacing;
        double stepZ = dz / distance * spacing;
        int steps = (int) Math.min(MAX_PER_MOVE, Math.floor(distance / spacing));
        for (int i = 0; i < steps; i++) {
            lastX += stepX;
            lastZ += stepZ;
            centres.add(new int[] {(int) Math.floor(lastX), (int) Math.floor(lastZ)});
        }
        if (steps == MAX_PER_MOVE) {
            // A jump too long to fill: carry on from where the cursor is.
            lastX = x;
            lastZ = z;
        }
        return centres;
    }

    /** The press ended. */
    public void release() {
        pressed = false;
        started = false;
    }

    public boolean pressed() {
        return pressed;
    }
}
