package dev.sculptory.core.state;

/**
 * Which cells hold still water, from {@link StateFlags#WATER} and {@link StateSpace#fluidSource}: what underwater
 * scatter placements need around them, and what a lily pad stands on.
 */
public final class Water {
    private Water() {}

    /** The water block at its source level ({@code minecraft:water[level=0]}): plain still water, nothing else in it. */
    public static boolean isSourceBlock(StateSpace states, int h) {
        int flags = states.flags(h);
        return StateFlags.has(flags, StateFlags.FLUID_BLOCK) && StateFlags.has(flags, StateFlags.WATER)
                && states.fluidSource(h) == h;
    }

    /**
     * Whether the cell holds still water: the water source block, a waterlogged state, or a block that holds water
     * whatever its properties (seagrass, kelp). Flowing and falling water do not.
     */
    public static boolean holdsSource(StateSpace states, int h) {
        int flags = states.flags(h);
        if (!StateFlags.has(flags, StateFlags.WATER)) return false;
        return !StateFlags.has(flags, StateFlags.FLUID_BLOCK) || states.fluidSource(h) == h;
    }
}
