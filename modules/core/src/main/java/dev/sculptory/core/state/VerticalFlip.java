package dev.sculptory.core.state;

import dev.sculptory.core.NamespacedId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * How block states turn upside down. Minecraft has no vertical mirror of
 * its own, so every block, vanilla or modded, is flipped by its properties that have an up or down meaning:
 * <ul>
 *   <li>{@code facing} and {@code vertical_direction} {@code up} ↔ {@code down} (pistons, observers, dispensers,
 *       droppers, end rods, lightning rods, amethyst buds, barrels, shulker boxes, pointed dripstone). Where the block
 *       has no opposite (a hopper pointing down cannot point up) the state is kept.</li>
 *   <li>{@code half} {@code top} ↔ {@code bottom} (stairs, trapdoors) and {@code upper} ↔ {@code lower} (doors and
 *       other two-block-tall blocks: the halves swap places, so they stay whole), {@code type} {@code top} ↔
 *       {@code bottom} (slabs; {@code double} stays).</li>
 *   <li>{@code face} and {@code attachment} {@code floor} ↔ {@code ceiling} (buttons, levers, grindstones, bells),
 *       {@code hanging} true ↔ false (lanterns).</li>
 *   <li>{@code up} ↔ {@code down} where a state has both (mushroom blocks, chorus plants, glow lichen, sculk
 *       veins).</li>
 *   <li>{@code orientation} (jigsaws, crafters): {@code up} ↔ {@code down} in both of its parts, kept where the block
 *       lacks the result ({@code north_up} has no {@code north_down}).</li>
 * </ul>
 * Every other property stays as it is (a pointed dripstone's {@code thickness}: the cells change places, so the column
 * keeps its order; chains and logs keep their {@code axis}). A state is <b>kept</b> as it is, and counted, when it has no
 * upside-down form: a plant or a torch ({@link Hints#upright}: flowers, saplings, crops, vines, standing and wall
 * torches; a two-block plant still swaps its halves to stay whole), a block with a 0–15 {@code rotation} (standing
 * signs, banners, heads, hanging signs), a bubble column ({@code drag}), a {@code facing} or {@code orientation}
 * whose flipped value the block lacks, or a block with none of the properties above whose shape is not the same upside
 * down ({@link Hints#lopsided}: beds, carpets, rails, pressure plates, redstone, snow layers, candles, chests). Full
 * blocks, fences, walls, panes and the like have nothing to flip and are not counted.
 *
 * <p><b>Modded blocks</b> follow the same rules for the same property names. A modded state that also has a property
 * no vanilla block has is flipped by the properties above, the unknown ones are left alone, and it is counted as
 * {@link #UNKNOWN_PROPERTIES}.
 *
 * <p><b>Tables.</b> Built once, with the state space ({@link #build}): per state its flipped handle and its kind. Every
 * flip is an involution (a state flipped twice is itself), checked when the tables are built: a state whose partner does
 * not flip back is kept. Immutable and thread-safe.
 */
public final class VerticalFlip {
    /** Turned upside down, or the same either way up (a full block). */
    public static final int FLIPS = 0;
    /** No upside-down form: left as it is (a two-block plant's halves still swap). */
    public static final int KEPT = 1;
    /** A modded state with properties the flip does not know: those are left alone, the known ones flipped. */
    public static final int UNKNOWN_PROPERTIES = 2;
    /** The vanilla namespace: property names of its blocks are the known ones. */
    public static final String VANILLA_NAMESPACE = "minecraft";
    /** Flips nothing and counts nothing: for spaces that cannot tell. */
    public static final VerticalFlip NONE = new VerticalFlip(null, null, 0, 0, 0L);

    private static final Set<String> VERTICAL = Set.of("up", "down");

    /** Per handle, the flipped handle; {@code null}: the identity. */
    private final int[] flipped;
    /** Per handle, {@link #FLIPS}, {@link #KEPT} or {@link #UNKNOWN_PROPERTIES}; {@code null}: all FLIPS. */
    private final byte[] kinds;
    private final int keptStates;
    private final int unknownStates;
    private final long buildNanos;

    private VerticalFlip(int[] flipped, byte[] kinds, int keptStates, int unknownStates, long buildNanos) {
        this.flipped = flipped;
        this.kinds = kinds;
        this.keptStates = keptStates;
        this.unknownStates = unknownStates;
        this.buildNanos = buildNanos;
    }

    /** How the tables read a state's properties: its names, and {@link ModdedFacingFallback.PropertyAccess}. */
    public interface StateView extends ModdedFacingFallback.PropertyAccess {
        /** The names of state {@code h}'s properties. */
        Collection<String> names(int h);

        /** Through the text forms ({@code describe}/{@code resolve}). */
        static StateView of(StateSpace space) {
            Objects.requireNonNull(space);
            ModdedFacingFallback.PropertyAccess text = ModdedFacingFallback.PropertyAccess.of(space);
            return new StateView() {
                @Override
                public Collection<String> names(int h) {
                    return space.describe(h).properties().keySet();
                }

                @Override
                public String value(int h, String name) {
                    return text.value(h, name);
                }

                @Override
                public int with(int h, String name, String value) {
                    return text.with(h, name, value);
                }
            };
        }
    }

    /** What only the platform can tell about a state. */
    public interface Hints {
        /** Nothing upright, nothing lopsided. */
        Hints NONE = new Hints() {
            @Override
            public boolean upright(int h) {
                return false;
            }

            @Override
            public boolean lopsided(int h) {
                return false;
            }
        };

        /**
         * A plant or a torch: it grows or stands up (or hangs down) by nature and has no upside-down form, whatever its
         * shape.
         */
        boolean upright(int h);

        /** The state's shape is not the same upside down (its outline does not sit centred in the cell's height). */
        boolean lopsided(int h);
    }

    /**
     * Builds the tables for every state of {@code space}. A state whose properties fail to read is kept (and its
     * block's other states are unaffected).
     */
    public static VerticalFlip build(StateSpace space, StateView view, Hints hints) {
        Objects.requireNonNull(space);
        Objects.requireNonNull(view);
        Objects.requireNonNull(hints);
        long started = System.nanoTime();
        int size = space.size();
        int[] flipped = new int[size];
        byte[] kinds = new byte[size];
        String[] namespaces = new String[size];
        Set<String> known = new HashSet<>();
        for (int h = 0; h < size; h++) {
            flipped[h] = h;
            try {
                namespaces[h] = namespace(space.blockId(h));
                if (namespaces[h].equals(VANILLA_NAMESPACE)) known.addAll(view.names(h));
            } catch (RuntimeException e) {
                // Not a state of this space (a hole in the ids), or one whose block or properties cannot be read: kept
                // as it is and counted, as a state whose rules fail below.
                namespaces[h] = null;
                kinds[h] = KEPT;
            }
        }
        for (int h = 0; h < size; h++) {
            if (namespaces[h] == null) continue;
            try {
                Collection<String> names = view.names(h);
                int[] result = flipOf(view, hints, h, names);
                flipped[h] = result[0];
                kinds[h] = (byte) result[1];
                if (result[1] == FLIPS && !namespaces[h].equals(VANILLA_NAMESPACE) && !known.containsAll(names)) {
                    kinds[h] = UNKNOWN_PROPERTIES;
                }
            } catch (RuntimeException e) {
                flipped[h] = h;
                kinds[h] = KEPT;
            }
        }
        // Every flip must be undone by flipping again: a state whose partner does not flip back (a rule the block breaks,
        // a result out of range) is kept, until nothing changes.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int h = 0; h < size; h++) {
                int f = flipped[h];
                if (f == h) continue;
                if (f < 0 || f >= size || flipped[f] != h) {
                    flipped[h] = h;
                    kinds[h] = KEPT;
                    changed = true;
                }
            }
        }
        int kept = 0;
        int unknown = 0;
        boolean identity = true;
        for (int h = 0; h < size; h++) {
            if (kinds[h] == KEPT) kept++;
            if (kinds[h] == UNKNOWN_PROPERTIES) unknown++;
            identity &= flipped[h] == h && kinds[h] == FLIPS;
        }
        if (identity) return new VerticalFlip(null, null, 0, 0, System.nanoTime() - started);
        return new VerticalFlip(flipped, kinds, kept, unknown, System.nanoTime() - started);
    }

    /** {@link #build(StateSpace, StateView, Hints)} through the space's text forms. */
    public static VerticalFlip build(StateSpace space, Hints hints) {
        return build(space, StateView.of(space), hints);
    }

    /** The state flipped upside down ({@code h} itself when it is kept or looks the same). */
    public int flip(int h) {
        if (flipped == null || h < 0 || h >= flipped.length) return h;
        return flipped[h];
    }

    /** {@link #FLIPS}, {@link #KEPT} or {@link #UNKNOWN_PROPERTIES}. */
    public int kind(int h) {
        if (kinds == null || h < 0 || h >= kinds.length) return FLIPS;
        return kinds[h];
    }

    /** States kept as they are. */
    public int keptStates() {
        return keptStates;
    }

    /** Modded states with properties the flip does not know. */
    public int unknownStates() {
        return unknownStates;
    }

    public long buildNanos() {
        return buildNanos;
    }

    @Override
    public String toString() {
        return "VerticalFlip[" + keptStates + " states kept, " + unknownStates + " with unknown properties]";
    }

    // ------------------------------------------------------------------ rules (pure, on handles and property text)

    /** {flipped handle, kind} of state {@code h}. */
    private static int[] flipOf(StateView p, Hints hints, int h, Collection<String> names) {
        if (hints.upright(h)) return new int[] {halvesSwapped(p, h), KEPT};
        if (names.contains("rotation") || names.contains("drag")) return new int[] {h, KEPT};
        int result = h;
        boolean aware = false;
        for (String name : List.copyOf(names)) {
            String value = p.value(h, name);
            if (value == null) continue;
            String next = switch (name) {
                case "facing", "vertical_direction" -> VERTICAL.contains(value) ? opposite(value) : null;
                case "half" -> switch (value) {
                    case "top" -> "bottom";
                    case "bottom" -> "top";
                    case "upper" -> "lower";
                    case "lower" -> "upper";
                    default -> null;
                };
                case "type" -> switch (value) {
                    case "top" -> "bottom";
                    case "bottom" -> "top";
                    default -> null;
                };
                case "face", "attachment" -> switch (value) {
                    case "floor" -> "ceiling";
                    case "ceiling" -> "floor";
                    default -> null;
                };
                case "hanging" -> switch (value) {
                    case "true" -> "false";
                    case "false" -> "true";
                    default -> null;
                };
                case "up" -> names.contains("down") ? p.value(h, "down") : null;
                case "down" -> names.contains("up") ? p.value(h, "up") : null;
                case "orientation" -> orientation(value);
                default -> null;
            };
            if (next == null) {
                // A value with no up or down of its own on a property that has them (a sideways orientation, a
                // double slab) flips as itself. A wall value of face or attachment (a wall lever, a wall bell) is not
                // that: the block sits on the side, and whether it is the same upside down is its shape's to tell
                // (the lopsided hint below: a wall bell hangs from its bar, a wall lever is centred).
                aware |= name.equals("orientation")
                        || ((name.equals("type") || name.equals("half")) && hasUpDownValues(p, h, name));
                continue;
            }
            aware = true;
            if (next.equals(value)) continue;
            result = p.with(result, name, next);
            if (result < 0) {
                // The block lacks the flipped value (a hopper pointing down): kept, halves swapped as ever.
                return new int[] {halvesSwapped(p, h), KEPT};
            }
        }
        if (!aware && hints.lopsided(h)) return new int[] {h, KEPT};
        return new int[] {result, FLIPS};
    }

    /** Whether the block's {@code name} property can be top or bottom (a slab's {@code type}, a stair's {@code half}). */
    private static boolean hasUpDownValues(StateView p, int h, String name) {
        return p.with(h, name, "top") >= 0 || p.with(h, name, "upper") >= 0;
    }

    /** A kept state with its {@code half} upper ↔ lower swapped (a two-block plant stays whole), else itself. */
    private static int halvesSwapped(StateView p, int h) {
        String half = p.value(h, "half");
        if (!"upper".equals(half) && !"lower".equals(half)) return h;
        int swapped = p.with(h, "half", half.equals("upper") ? "lower" : "upper");
        return swapped < 0 ? h : swapped;
    }

    /** A jigsaw or crafter orientation ({@code front_top}) with up and down swapped in both parts, or null. */
    private static String orientation(String value) {
        int split = value.indexOf('_');
        if (split <= 0 || split == value.length() - 1) return null;
        String front = value.substring(0, split);
        String top = value.substring(split + 1);
        String flippedFront = VERTICAL.contains(front) ? opposite(front) : front;
        String flippedTop = VERTICAL.contains(top) ? opposite(top) : top;
        return flippedFront + "_" + flippedTop;
    }

    private static String opposite(String upOrDown) {
        return upOrDown.equals("up") ? "down" : "up";
    }

    private static String namespace(NamespacedId id) {
        String value = id.value();
        return value.substring(0, value.indexOf(':'));
    }
}
