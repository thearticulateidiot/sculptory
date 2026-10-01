package dev.sculptory.core.state;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.transform.Mirror;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * The rotation fallback for modded blocks that do not turn themselves: Farmer's Delight
 * cooking pots, skillets, pies and feasts, Chipped's special lanterns and the like implement neither {@code rotate} nor
 * {@code mirror}, so vanilla's {@code BlockState.rotate}/{@code mirror} leave them as they are. WorldEdit turns such
 * blocks by their facing-like properties, and so does this, the way vanilla turns the equivalent vanilla blocks:
 * <ul>
 *   <li>{@code facing} with a horizontal value (furnaces, dispensers): rotated clockwise, north → east → south → west;
 *       {@link Mirror#X} swaps east and west, {@link Mirror#Z} north and south (vanilla's {@code BlockMirror.apply}).
 *       Up and down stay.</li>
 *   <li>{@code axis} and {@code horizontal_axis} (pillars): {@code x} and {@code z} swap on odd turns; mirrors keep
 *       them.</li>
 *   <li>{@code rotation} with the sixteen values 0–15 (signs, banners, skulls): plus 4 per quarter turn; {@link Mirror#X}
 *       gives {@code 16 − r}, {@link Mirror#Z} {@code 8 − r} (mod 16), as vanilla's {@code BlockMirror.mirror(r, 16)}.
 *       A {@code rotation} property with other values is left alone.</li>
 * </ul>
 * Every such property of a state turns together, and nothing else about it changes.
 *
 * <p><b>When it applies.</b> Only to states of blocks outside the {@code minecraft} namespace, for each step (a turn by
 * 1, 2 or 3, or a mirror) where the block's own step returns the state unchanged. A state is handled at all only if
 * <ul>
 *   <li>every turned and mirrored form of it is a state of the block (a {@code facing} without east and west could
 *       turn by 2 but not by 1), and</li>
 *   <li>each of the block's own steps either leaves it unchanged or already gives the fallback's result: a block that
 *       rotates itself but does not mirror is mirrored here, while a block with its own idea of turning (a door, whose
 *       mirror also flips its hinge; a pillar lying on its side that turns its axis but not its facing) is left to its
 *       own steps.</li>
 * </ul>
 * So every step of a handled state gives exactly the fallback's result, and {@code Transform.applyToState}'s
 * mirror-then-rotate composes them as it does for vanilla blocks: four turns, or the same mirror twice, give the state
 * back, and {@code Transform.compose} holds on states. A state whose own rotate or mirror throws is left to that own
 * step (which then fails as before); a block whose properties fail to read or resolve is left alone whole. Block
 * entities are not involved: only the state changes.
 *
 * <p><b>Tables.</b> Everything is computed once, by {@link #build}, when the state space is built: per state, the result
 * of each turn (1–3) and each mirror. {@link #rotate}/{@link #mirror} are array reads. {@link #enabled(boolean)} gives
 * the same tables switched on or off (the server config {@code transform.moddedFacingFallback}; the client follows the
 * server). Immutable and thread-safe.
 */
public final class ModdedFacingFallback {
    /** The namespace whose blocks the fallback never touches. */
    public static final String VANILLA_NAMESPACE = "minecraft";
    /** Property names the fallback turns. */
    public static final String FACING = "facing";
    public static final String AXIS = "axis";
    public static final String HORIZONTAL_AXIS = "horizontal_axis";
    public static final String ROTATION = "rotation";
    /** A fallback over no states, switched off: {@link #rotate} and {@link #mirror} always answer -1. */
    public static final ModdedFacingFallback NONE = new ModdedFacingFallback(null, 0, 0, 0L, false);

    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    private static final List<String> AXES = List.of(AXIS, HORIZONTAL_AXIS);
    private static final int ROTATION_STEPS = 16;
    /** Steps, in table order: turns by 1, 2 and 3, then {@link Mirror#X} and {@link Mirror#Z}. */
    private static final int STEPS = 5;
    private static final Mirror[] MIRRORS = {Mirror.X, Mirror.Z};

    /** {@code [step][h]}: the handle the fallback gives, or -1 where it does not apply. Null: nowhere. */
    private final int[][] steps;
    private final int turnedStates;
    private final int turnedBlocks;
    private final long buildNanos;
    private final boolean enabled;

    private ModdedFacingFallback(int[][] steps, int turnedStates, int turnedBlocks, long buildNanos, boolean enabled) {
        this.steps = steps;
        this.turnedStates = turnedStates;
        this.turnedBlocks = turnedBlocks;
        this.buildNanos = buildNanos;
        this.enabled = enabled;
    }

    /**
     * How the fallback reads and sets a state's properties. {@link #of} works on any space through its text forms
     * ({@code describe}/{@code resolve}); a platform may pass a faster one that must answer the same.
     */
    public interface PropertyAccess {
        /** The value of property {@code name} of state {@code h} in text form, or {@code null} when it has none. */
        String value(int h, String name);

        /** State {@code h} with property {@code name} set to {@code value}, or -1 when the block does not allow it. */
        int with(int h, String name, String value);

        static PropertyAccess of(StateSpace space) {
            Objects.requireNonNull(space);
            return new PropertyAccess() {
                @Override
                public String value(int h, String name) {
                    return space.describe(h).get(name);
                }

                @Override
                public int with(int h, String name, String value) {
                    return space.resolve(space.describe(h).with(name, value));
                }
            };
        }
    }

    /** {@link #build(StateSpace, IntPredicate, PropertyAccess)} over every handle, through the text forms. */
    public static ModdedFacingFallback build(StateSpace own) {
        return build(own, h -> true, PropertyAccess.of(own));
    }

    /**
     * Builds the tables, switched on.
     *
     * @param own the space whose {@code rotate}/{@code mirror} are the blocks' own (vanilla's, without this fallback)
     * @param candidates a cheap filter: handles it refuses are never looked at (the Fabric space passes the states that
     *     carry one of the property names above). It may refuse more, never less.
     * @param properties reads and sets the properties of {@code own}'s states
     */
    public static ModdedFacingFallback build(StateSpace own, IntPredicate candidates, PropertyAccess properties) {
        Objects.requireNonNull(own);
        Objects.requireNonNull(candidates);
        Objects.requireNonNull(properties);
        long started = System.nanoTime();
        int size = own.size();
        int[][] steps = null;
        Map<NamespacedId, Boolean> sixteenRotations = new HashMap<>();
        Set<NamespacedId> failed = new HashSet<>();
        for (int h = 0; h < size; h++) {
            if (!candidates.test(h)) continue;
            NamespacedId block = own.blockId(h);
            if (namespace(block).equals(VANILLA_NAMESPACE) || failed.contains(block)) continue;
            int[] results;
            try {
                results = stepsOf(own, properties, h, block, sixteenRotations);
            } catch (RuntimeException e) {
                failed.add(block);
                continue;
            }
            if (results == null) continue;
            if (steps == null) steps = table(size);
            for (int step = 0; step < STEPS; step++) steps[step][h] = results[step];
        }
        int turnedStates = 0;
        Set<NamespacedId> turnedBlocks = new HashSet<>();
        if (steps != null) {
            for (int h = 0; h < size; h++) {
                boolean turned = false;
                for (int step = 0; step < STEPS; step++) turned |= steps[step][h] >= 0;
                if (!turned) continue;
                NamespacedId block = own.blockId(h);
                if (failed.contains(block)) {
                    // A block that failed part way is left to its own steps whole, so its states' orbits stay closed.
                    for (int step = 0; step < STEPS; step++) steps[step][h] = -1;
                    continue;
                }
                turnedStates++;
                turnedBlocks.add(block);
            }
        }
        return new ModdedFacingFallback(steps, turnedStates, turnedBlocks.size(), System.nanoTime() - started, true);
    }

    /** The same tables, switched on or off. */
    public ModdedFacingFallback enabled(boolean on) {
        return on == enabled ? this : new ModdedFacingFallback(steps, turnedStates, turnedBlocks, buildNanos, on);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * {@code h} turned clockwise by the fallback, or -1 where it does not apply: switched off, no turn, a vanilla block,
     * a block that turns itself, or nothing to turn.
     */
    public int rotate(int h, int clockwiseQuarterTurns) {
        int turns = Math.floorMod(clockwiseQuarterTurns, 4);
        if (turns == 0) return -1;
        return lookup(turns - 1, h);
    }

    /** {@code h} mirrored by the fallback, or -1 where it does not apply (as {@link #rotate}). */
    public int mirror(int h, Mirror m) {
        if (Objects.requireNonNull(m) == Mirror.NONE) return -1;
        return lookup(2 + m.ordinal(), h);
    }

    /** States the fallback turns or mirrors in at least one way (whether switched on or not). */
    public int turnedStates() {
        return turnedStates;
    }

    /** Blocks with such states. */
    public int turnedBlocks() {
        return turnedBlocks;
    }

    /** How long {@link #build} took. */
    public long buildNanos() {
        return buildNanos;
    }

    @Override
    public String toString() {
        return "ModdedFacingFallback[" + (enabled ? "on" : "off") + ", " + turnedStates + " states of " + turnedBlocks
                + " blocks]";
    }

    private int lookup(int step, int h) {
        if (!enabled || steps == null) return -1;
        int[] table = steps[step];
        return h >= 0 && h < table.length ? table[h] : -1;
    }

    // ------------------------------------------------------------------ rules (pure, on handles and property text)

    /**
     * The fallback's result for each step of state {@code h} (-1 where the block's own step stands), or {@code null}
     * where the fallback does not handle the state.
     */
    private static int[] stepsOf(StateSpace own, PropertyAccess p, int h, NamespacedId block,
                                 Map<NamespacedId, Boolean> sixteenRotations) {
        // Cheap first: a state that every one of its own steps changes (a door) has nothing to fall back for.
        int[] ownResults = new int[STEPS];
        boolean anyStill = false;
        for (int step = 0; step < STEPS; step++) {
            ownResults[step] = ownStep(own, h, step);
            anyStill |= ownResults[step] == h;
        }
        if (!anyStill) return null;
        boolean rotation = p.value(h, ROTATION) != null
                && sixteenRotations.computeIfAbsent(block, id -> hasSixteenRotations(p, h));
        // The steps the block leaves undone decide whether there is anything to do (for a block that turns itself
        // that is a mirror along its facing, which changes nothing here either)...
        int[] forms = new int[STEPS];
        boolean useful = false;
        for (int step = 0; step < STEPS; step++) {
            if (ownResults[step] != h) continue;
            forms[step] = form(p, h, step, rotation);
            if (forms[step] < 0) return null;
            useful |= forms[step] != h;
        }
        if (!useful) return null;
        // ...and the steps it does must already give the fallback's result, or the state is left to its own steps.
        int[] results = new int[STEPS];
        for (int step = 0; step < STEPS; step++) {
            if (ownResults[step] == h) {
                results[step] = forms[step] == h ? -1 : forms[step];
            } else {
                int form = form(p, h, step, rotation);
                if (form < 0 || form != ownResults[step]) return null;
                results[step] = -1;
            }
        }
        return results;
    }

    /** The handle of the state after a step: {@code h} when nothing changes, -1 when the block lacks the result. */
    private static int form(PropertyAccess p, int h, int step, boolean rotation) {
        return step < 3 ? turned(p, h, step + 1, rotation) : mirrored(p, h, MIRRORS[step - 3], rotation);
    }

    /** State {@code h} turned {@code turns} (1–3) quarter turns clockwise; {@code h} if nothing changes, else -1. */
    static int turned(PropertyAccess p, int h, int turns, boolean rotation) {
        int result = h;
        String facing = p.value(h, FACING);
        if (facing != null && HORIZONTAL.contains(facing)) {
            result = p.with(result, FACING, HORIZONTAL.get((HORIZONTAL.indexOf(facing) + turns) % 4));
        }
        if ((turns & 1) == 1) {
            for (String name : AXES) {
                if (result < 0) return -1;
                String axis = p.value(h, name);
                if ("x".equals(axis)) result = p.with(result, name, "z");
                else if ("z".equals(axis)) result = p.with(result, name, "x");
            }
        }
        if (rotation && result >= 0) {
            int value = Integer.parseInt(p.value(h, ROTATION));
            result = p.with(result, ROTATION, Integer.toString((value + turns * ROTATION_STEPS / 4) % ROTATION_STEPS));
        }
        return result;
    }

    /** State {@code h} mirrored; {@code h} if nothing changes, -1 if the block lacks the result. */
    static int mirrored(PropertyAccess p, int h, Mirror mirror, boolean rotation) {
        int result = h;
        String facing = p.value(h, FACING);
        if (facing != null) {
            String swapped = switch (mirror) {
                case X -> swap(facing, "east", "west");
                case Z -> swap(facing, "north", "south");
                case NONE -> facing;
            };
            if (!swapped.equals(facing)) result = p.with(result, FACING, swapped);
        }
        if (rotation && result >= 0) {
            int value = Integer.parseInt(p.value(h, ROTATION));
            // Vanilla's BlockMirror.mirror(value, 16): FRONT_BACK (X) is -value, LEFT_RIGHT (Z) is 8 - value, mod 16.
            int mirroredValue = switch (mirror) {
                case X -> ROTATION_STEPS - value;
                case Z -> ROTATION_STEPS / 2 - value;
                case NONE -> value;
            };
            result = p.with(result, ROTATION, Integer.toString(Math.floorMod(mirroredValue, ROTATION_STEPS)));
        }
        return result;
    }

    /**
     * Whether the block's {@code rotation} property has exactly the values 0–15, as vanilla's signs: 0 and 15 are
     * values of it and 16 is not (setting keeps the state's other properties).
     */
    private static boolean hasSixteenRotations(PropertyAccess p, int h) {
        try {
            Integer.parseInt(p.value(h, ROTATION));
        } catch (NumberFormatException notANumber) {
            return false;
        }
        return p.with(h, ROTATION, "0") >= 0 && p.with(h, ROTATION, Integer.toString(ROTATION_STEPS - 1)) >= 0
                && p.with(h, ROTATION, Integer.toString(ROTATION_STEPS)) < 0;
    }

    /**
     * The block's own result for a step (turns 1–3, then mirrors). A block whose rotate or mirror throws counts as doing
     * something else (never unchanged, never the fallback's result), so it is left to its own path, which then fails.
     */
    private static int ownStep(StateSpace own, int h, int step) {
        try {
            return step < 3 ? own.rotate(h, step + 1) : own.mirror(h, MIRRORS[step - 3]);
        } catch (RuntimeException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static String swap(String value, String a, String b) {
        if (value.equals(a)) return b;
        if (value.equals(b)) return a;
        return value;
    }

    private static String namespace(NamespacedId id) {
        String value = id.value();
        return value.substring(0, value.indexOf(':'));
    }

    private static int[][] table(int size) {
        int[][] table = new int[STEPS][size];
        for (int[] row : table) Arrays.fill(row, -1);
        return table;
    }
}
