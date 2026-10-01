package dev.sculptory.protocol.v2;

import java.util.EnumSet;
import java.util.Set;

/**
 * Builder mode's powers: Sculptory's editing in normal creative play,
 * outside the editor. A set of powers travels as a bitmask, bit {@link #bit()} per power ({@code BuilderPowers}, and the
 * powers that apply to one {@code BuilderPlace} or {@code BuilderBreak}). Wire order: append only.
 */
public enum BuilderPower {
    /** Place and break up to 64 blocks away (the server raises the player's block interaction range). */
    LONG_REACH,
    /** Aiming at nothing: place the held block a set distance ahead. */
    PLACE_IN_AIR,
    /** Right-click swaps the clicked block for the held one. */
    REPLACE,
    /** Left-click breaks at once; hold and drag to break every block the crosshair sweeps. */
    BULLDOZER,
    /** Placing and breaking don't change neighbours (physics-off writes). */
    KEEP_SHAPE,
    /** Place where the game refuses (a flower on stone, a torch on glass). */
    FORCE_PLACE,
    /** Placements and breaks are mirrored with the editor's symmetry. */
    MIRROR,
    /** Alt+scroll and Alt+click change the aimed block in place. */
    TINKER;

    /** Every bit a known power may set. */
    public static final int ALL = (1 << values().length) - 1;

    public int bit() {
        return 1 << ordinal();
    }

    /** Whether {@code powers} holds this power. */
    public boolean in(int powers) {
        return (powers & bit()) != 0;
    }

    /** Whether {@code powers} sets only bits of known powers. */
    public static boolean valid(int powers) {
        return (powers & ~ALL) == 0;
    }

    /** The mask of {@code powers}. */
    public static int mask(Set<BuilderPower> powers) {
        int mask = 0;
        for (BuilderPower power : powers) mask |= power.bit();
        return mask;
    }

    /** The powers of {@code mask} (unknown bits ignored). */
    public static EnumSet<BuilderPower> of(int mask) {
        EnumSet<BuilderPower> powers = EnumSet.noneOf(BuilderPower.class);
        for (BuilderPower power : values()) {
            if (power.in(mask)) powers.add(power);
        }
        return powers;
    }
}
