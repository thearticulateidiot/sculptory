package dev.sculptory.core.state;

import java.util.List;
import java.util.Objects;

/**
 * Blocks made of two cells that must stay together: the two halves of a door or a two-block plant (tall grass,
 * sunflowers, small dripleaf, tall seagrass: {@link StateFlags#LOWER_HALF} below {@link StateFlags#UPPER_HALF}), and the
 * foot and head of a bed ({@code part} {@code foot}/{@code head}, the head one cell along {@code facing}). The lower
 * half and the foot are the <i>first</i> cell, the one a pair is placed from. Cached per handle; concurrent use is safe
 * because every writer stores the same deterministic value.
 */
public final class TwoCellBlocks {
    /** Unit steps by direction index: east, west, up, down, south, north. */
    private static final int[][] STEPS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    private static final List<String> FACINGS = List.of("east", "west", "up", "down", "south", "north");

    private final StateSpace states;
    /**
     * Per handle, in one int so a lookup is published whole: 0 = not looked up, 1 = not a half, else in the low 4 bits
     * 2 + the direction to the other half + 8 if it is the first, and above them the other half's state + 1.
     */
    private final int[] info;

    public TwoCellBlocks(StateSpace states) {
        this.states = Objects.requireNonNull(states);
        this.info = new int[states.size()];
    }

    public StateSpace states() {
        return states;
    }

    /** Whether {@code h} is one half of a two-cell block. */
    public boolean isHalf(int h) {
        return info(h) != 1;
    }

    /** Whether {@code h} is the first half (the lower half, a bed's foot): the one its pair is placed from. */
    public boolean isFirst(int h) {
        int i = info(h);
        return i != 1 && (i & 8) != 0;
    }

    /**
     * The direction from {@code h}'s cell to its other half's, as a unit step {@code {dx, dy, dz}} (a fresh array), or
     * null when {@code h} is not a half.
     */
    public int[] towardsOther(int h) {
        int i = info(h);
        if (i == 1) return null;
        return STEPS[(i - 2) & 7].clone();
    }

    /** The state of {@code h}'s other half (its properties but the half or part), or -1 when it is not a half. */
    public int other(int h) {
        int i = info(h);
        if (i == 1) return -1;
        return (i >>> 4) - 1;
    }

    /** The handle's lookup (see {@link #info}); the low 4 bits are the kind and direction. */
    private int info(int h) {
        int i = info[h];
        if (i == 0) {
            i = lookUp(h);
            info[h] = i;
        }
        return i;
    }

    private int lookUp(int h) {
        int flags = states.flags(h);
        int direction;
        boolean first;
        int other;
        if (StateFlags.has(flags, StateFlags.LOWER_HALF)) {
            direction = 2;
            first = true;
            other = states.withProperty(h, "half", "upper");
        } else if (StateFlags.has(flags, StateFlags.UPPER_HALF)) {
            direction = 3;
            first = false;
            other = states.withProperty(h, "half", "lower");
        } else {
            String part = value(h, "part");
            String facing = value(h, "facing");
            int towards = facing == null ? -1 : FACINGS.indexOf(facing);
            if (towards < 0 || towards == 2 || towards == 3 || !("foot".equals(part) || "head".equals(part))) return 1;
            first = part.equals("foot");
            // The head lies along the facing from the foot.
            direction = first ? towards : towards ^ 1;
            other = states.withProperty(h, "part", first ? "head" : "foot");
        }
        if (other < 0) return 1;
        return 2 + direction + (first ? 8 : 0) | (other + 1) << 4;
    }

    private String value(int h, String property) {
        try {
            return states.describe(h).get(property);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
