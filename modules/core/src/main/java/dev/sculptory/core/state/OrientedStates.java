package dev.sculptory.core.state;

import dev.sculptory.core.transform.CubeTurn;
import dev.sculptory.core.transform.Orientation;
import java.util.Objects;

/**
 * Block states turned by an {@link Orientation}, as a paste, move or ghost writes them, cached per handle. A state takes
 * the {@link CubeTurn} nearest the orientation: its exact part through {@link dev.sculptory.core.transform.Transform#applyToState}
 * (the blocks' own rotate and mirror, then the flip), then its tip through {@link StateSpace#tip}. A sign, banner or head
 * standing on a 0–15 {@code rotation} that stays upright takes the nearest of its sixteen headings to where the whole
 * free rotation turns it, so a 30° turn turns it by 22.5° or 45°, not by the 0° or 90° its cube turn would. An exact
 * orientation maps exactly as its transform does.
 *
 * <p>{@link #kind} says how a state fared ({@link StateTips#TURNS}, {@link StateTips#KEPT} when the flip or the tip
 * left it as it was, {@link StateTips#UNKNOWN_PROPERTIES}), for the ghost's counts. The caches are int arrays the size
 * of the state space; concurrent use is safe because every writer stores the same deterministic value.
 */
public final class OrientedStates {
    private static final String ROTATION = "rotation";
    private static final int HEADINGS = 16;

    private final StateSpace states;
    private final Orientation orientation;
    private final CubeTurn turn;
    /** The orientation's whole linear map, row-major (for the sixteen headings). */
    private final double[] matrix;
    /** 0 = not computed yet, otherwise the mapped handle + 1. */
    private final int[] cache;
    /** 0 = not computed yet, otherwise the kind + 1. */
    private final byte[] kinds;

    public OrientedStates(StateSpace states, Orientation orientation) {
        this.states = Objects.requireNonNull(states);
        this.orientation = Objects.requireNonNull(orientation);
        this.turn = CubeTurn.nearest(orientation);
        this.matrix = orientation.matrix();
        this.cache = new int[states.size()];
        this.kinds = new byte[states.size()];
    }

    public Orientation orientation() {
        return orientation;
    }

    /** The cube turn the states take. */
    public CubeTurn turn() {
        return turn;
    }

    /** The state {@code handle} turned. */
    public int map(int handle) {
        int cached = cache[handle];
        if (cached != 0) return cached - 1;
        int mapped = compute(handle);
        if (mapped < 0 || mapped >= cache.length) {
            throw new IllegalStateException("Orienting state " + handle + " gave " + mapped);
        }
        cache[handle] = mapped + 1;
        return mapped;
    }

    /** How the state fared: {@link StateTips#TURNS}, {@link StateTips#KEPT} or {@link StateTips#UNKNOWN_PROPERTIES}. */
    public int kind(int handle) {
        int cached = kinds[handle];
        if (cached != 0) return cached - 1;
        int kind = StateTips.TURNS;
        if (turn.first().upsideDown()) kind = worse(kind, states.flipKind(handle));
        if (!turn.upright()) kind = worse(kind, states.tipKind(turn.first().applyToState(states, handle), turn.tip()));
        kinds[handle] = (byte) (kind + 1);
        return kind;
    }

    private int compute(int handle) {
        int h = turn.first().applyToState(states, handle);
        if (!turn.upright()) return states.tip(h, turn.tip());
        if (orientation.isExact()) return h;
        int heading = heading(handle);
        if (heading < 0) return h;
        int turned = states.withProperty(h, ROTATION, Integer.toString(turnedHeading(heading)));
        return turned >= 0 ? turned : h;
    }

    /** The 0–15 heading of a state that has sixteen, else -1. */
    private int heading(int h) {
        String value;
        try {
            value = states.describe(h).get(ROTATION);
        } catch (RuntimeException e) {
            return -1;
        }
        if (value == null) return -1;
        int heading;
        try {
            heading = Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            return -1;
        }
        if (heading < 0 || heading >= HEADINGS) return -1;
        if (states.withProperty(h, ROTATION, "15") < 0 || states.withProperty(h, ROTATION, "16") >= 0) return -1;
        return heading;
    }

    /**
     * The heading nearest where the orientation turns {@code heading}: heading 0 looks south, and each step turns 22.5°
     * clockwise seen from above (4 west, 8 north, 12 east), as vanilla's signs.
     */
    private int turnedHeading(int heading) {
        double angle = heading * (2 * Math.PI / HEADINGS);
        double dx = -StrictMath.sin(angle), dz = StrictMath.cos(angle);
        double x = matrix[0] * dx + matrix[2] * dz;
        double z = matrix[6] * dx + matrix[8] * dz;
        double turned = StrictMath.atan2(-x, z);
        return Math.floorMod((int) Math.round(turned / (2 * Math.PI / HEADINGS)), HEADINGS);
    }

    private static int worse(int a, int b) {
        if (a == StateTips.KEPT || b == StateTips.KEPT) return StateTips.KEPT;
        if (a == StateTips.UNKNOWN_PROPERTIES || b == StateTips.UNKNOWN_PROPERTIES) return StateTips.UNKNOWN_PROPERTIES;
        return StateTips.TURNS;
    }
}
