package dev.sculptory.fabric.client.editor;

import java.util.Locale;

/**
 * The editor's fly-speed multiplier, stepped with the scroll wheel. 1× is the player's own speed
 * (vanilla creative flight unless something else changed it).
 */
public final class FlySpeed {
    private static final double[] STEPS = {0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6, 8};
    private static final int DEFAULT_INDEX = 3;

    private int index = DEFAULT_INDEX;

    public double multiplier() {
        return STEPS[index];
    }

    /** Moves up (positive) or down (negative) one step. Returns true if it changed. */
    public boolean step(int direction) {
        int next = Math.max(0, Math.min(STEPS.length - 1, index + Integer.signum(direction)));
        boolean changed = next != index;
        index = next;
        return changed;
    }

    public void reset() {
        index = DEFAULT_INDEX;
    }

    /** "1.5×". */
    public String display() {
        double value = multiplier();
        String number = value == Math.rint(value)
                ? Integer.toString((int) value)
                : String.format(Locale.ROOT, "%s", value).replaceAll("0+$", "");
        return number + "×";
    }
}
