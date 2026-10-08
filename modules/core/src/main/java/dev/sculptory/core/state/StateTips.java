package dev.sculptory.core.state;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.transform.CubeTurn;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * How block states tip over: a quarter turn about a horizontal axis ({@link CubeTurn.Tip#PITCH} about east-west,
 * {@link CubeTurn.Tip#ROLL} about north-south), the part of a free rotation that Minecraft's own {@code rotate} and
 * {@code mirror} cannot do. Like {@link VerticalFlip}, every block, vanilla or modded, is turned by its properties:
 * <ul>
 *   <li>{@code facing} (any direction the block allows: pistons, observers, end rods, barrels; a furnace's front only
 *       while it stays level), {@code axis} and {@code horizontal_axis} (logs, chains, basalt), and a jigsaw's or
 *       crafter's {@code orientation} (both parts) turn with the block.</li>
 *   <li>The six face booleans {@code up}, {@code down}, {@code north}… (mushroom blocks, glow lichen, sculk veins,
 *       chorus plants) move round with it; a true face that would go where the block has no such property (a vine
 *       facing down) keeps the state.</li>
 *   <li>Stairs keep their shape exactly whenever the turned stair is still a stair (its long axis stays level): the
 *       block is matched by the eighths of the cell it fills ({@code half} and {@code facing} trade places, corners
 *       included). Otherwise the stair with the nearest shape is taken, and counted as kept.</li>
 *   <li>Trapdoors: the panel and its hinge turn, so a closed trapdoor tipped onto its side opens and an open one tipped
 *       flat closes; one whose hinge would stand upright is kept.</li>
 *   <li>Buttons, levers and grindstones ({@code face} and {@code facing}): the face they hang from turns (floor, wall,
 *       ceiling); one that would lie sideways on a wall takes the wall and is counted as kept.</li>
 * </ul>
 * A state is <b>kept</b> (left as it is, and counted) when it has no tipped form: plants and torches
 * ({@link VerticalFlip.Hints#upright}), two-block-tall blocks ({@code half} upper/lower: doors, tall flowers), slabs
 * (a slab on its side does not exist), signs, banners and heads with a 0–15 {@code rotation}, bells, lanterns, pointed
 * dripstone, a facing or axis the block lacks once turned, walls and redstone with connections, and blocks whose shape
 * is not the same upside down without a property that says so ({@link VerticalFlip.Hints#lopsided}: rails, beds,
 * carpets, repeaters, chests).
 *
 * <p><b>Modded blocks</b> follow the same rules for the same property names; a modded state with properties no
 * vanilla block has is tipped by the known ones and counted as {@link #UNKNOWN_PROPERTIES}. Since no modded block can
 * say how it tips, this is part of the modded facing fallback: while it is switched off ({@link #moddedEnabled}, the
 * server config {@code transform.moddedFacingFallback}) modded states are kept.
 *
 * <p><b>Tables.</b> Built once with the state space ({@link #build}): per tip and state, the tipped handle and its
 * kind. Immutable and thread-safe.
 */
public final class StateTips {
    /** Tipped (or the same either way: a full block). */
    public static final int TURNS = VerticalFlip.FLIPS;
    /** No tipped form: left as it is (or the nearest stair), and counted. */
    public static final int KEPT = VerticalFlip.KEPT;
    /** A modded state with properties the rules do not know: those are left alone, the known ones turned. */
    public static final int UNKNOWN_PROPERTIES = VerticalFlip.UNKNOWN_PROPERTIES;
    public static final String VANILLA_NAMESPACE = "minecraft";
    /** Tips nothing and counts nothing: for spaces that cannot tell. */
    public static final StateTips NONE = new StateTips(null, null, null, true, 0L);

    private static final List<String> DIRECTIONS = List.of("north", "south", "east", "west", "up", "down");
    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    private static final Set<String> KEPT_PROPERTIES = Set.of("rotation", "drag", "hanging", "vertical_direction",
            "attachment");

    /** {@code [tip - 1][h]}: the tipped handle; null: the identity. */
    private final int[][] tipped;
    /** {@code [tip - 1][h]}: TURNS, KEPT or UNKNOWN_PROPERTIES; null: all TURNS. */
    private final byte[][] kinds;
    /** The states of blocks outside the vanilla namespace; null: none. */
    private final BitSet modded;
    private final boolean moddedEnabled;
    private final long buildNanos;

    private StateTips(int[][] tipped, byte[][] kinds, BitSet modded, boolean moddedEnabled, long buildNanos) {
        this.tipped = tipped;
        this.kinds = kinds;
        this.modded = modded;
        this.moddedEnabled = moddedEnabled;
        this.buildNanos = buildNanos;
    }

    /**
     * Whether a state looks the same tipped (its outline turned by the tip is its own), for a state the rules leave as
     * it is: one that does not (a campfire, a wall sign tipped about its own facing) has no tipped form and is kept, so
     * it is counted. Spaces that cannot tell answer {@link #ANY}.
     */
    public interface Shapes {
        /** Every state looks the same tipped. */
        Shapes ANY = (h, tip) -> true;

        boolean keepsShape(int h, CubeTurn.Tip tip);
    }

    /** {@link #build(StateSpace, VerticalFlip.StateView, VerticalFlip.Hints, Shapes)} without shapes. */
    public static StateTips build(StateSpace space, VerticalFlip.StateView view, VerticalFlip.Hints hints) {
        return build(space, view, hints, Shapes.ANY);
    }

    /**
     * Builds the tables for every state of {@code space}, modded states switched on. A state whose properties fail to
     * read is kept.
     */
    public static StateTips build(StateSpace space, VerticalFlip.StateView view, VerticalFlip.Hints hints,
                                  Shapes shapes) {
        Objects.requireNonNull(shapes);
        Objects.requireNonNull(space);
        Objects.requireNonNull(view);
        Objects.requireNonNull(hints);
        long started = System.nanoTime();
        int size = space.size();
        int[][] tipped = new int[2][size];
        byte[][] kinds = new byte[2][size];
        BitSet modded = new BitSet(size);
        String[] namespaces = new String[size];
        Set<String> known = new HashSet<>();
        for (int h = 0; h < size; h++) {
            tipped[0][h] = h;
            tipped[1][h] = h;
            try {
                namespaces[h] = namespace(space.blockId(h));
                if (namespaces[h].equals(VANILLA_NAMESPACE)) known.addAll(view.names(h));
                else modded.set(h);
            } catch (RuntimeException e) {
                namespaces[h] = null;
                kinds[0][h] = KEPT;
                kinds[1][h] = KEPT;
            }
        }
        for (int h = 0; h < size; h++) {
            if (namespaces[h] == null) continue;
            for (CubeTurn.Tip tip : List.of(CubeTurn.Tip.PITCH, CubeTurn.Tip.ROLL)) {
                int t = tip.ordinal() - 1;
                try {
                    Collection<String> names = view.names(h);
                    int[] result = tipOf(view, hints, h, names, tip);
                    // Left as it is, but not the same tipped (a campfire stays standing): no tipped form.
                    if (result[0] == h && result[1] == TURNS && !shapes.keepsShape(h, tip)) {
                        result = new int[] {h, KEPT};
                    }
                    if (result[0] < 0 || result[0] >= size) result = new int[] {h, KEPT};
                    tipped[t][h] = result[0];
                    kinds[t][h] = (byte) result[1];
                    if (result[1] == TURNS && !namespaces[h].equals(VANILLA_NAMESPACE) && !known.containsAll(names)) {
                        kinds[t][h] = UNKNOWN_PROPERTIES;
                    }
                } catch (RuntimeException e) {
                    tipped[t][h] = h;
                    kinds[t][h] = KEPT;
                }
            }
        }
        return new StateTips(tipped, kinds, modded.isEmpty() ? null : modded, true, System.nanoTime() - started);
    }

    /** {@link #build(StateSpace, VerticalFlip.StateView, VerticalFlip.Hints)} through the space's text forms. */
    public static StateTips build(StateSpace space, VerticalFlip.Hints hints) {
        return build(space, VerticalFlip.StateView.of(space), hints);
    }

    /** The same tables with modded states tipped or kept. */
    public StateTips moddedEnabled(boolean on) {
        return on == moddedEnabled ? this : new StateTips(tipped, kinds, modded, on, buildNanos);
    }

    public boolean moddedEnabled() {
        return moddedEnabled;
    }

    /** The state tipped ({@code h} itself for {@link CubeTurn.Tip#NONE}, a kept state, or one that looks the same). */
    public int tip(int h, CubeTurn.Tip tip) {
        if (tip == CubeTurn.Tip.NONE || tipped == null || h < 0 || h >= tipped[0].length) return h;
        if (!moddedEnabled && modded != null && modded.get(h)) return h;
        return tipped[tip.ordinal() - 1][h];
    }

    /** {@link #TURNS}, {@link #KEPT} or {@link #UNKNOWN_PROPERTIES} for {@link #tip}. */
    public int kind(int h, CubeTurn.Tip tip) {
        if (tip == CubeTurn.Tip.NONE || kinds == null || h < 0 || h >= kinds[0].length) return TURNS;
        if (!moddedEnabled && modded != null && modded.get(h)) return KEPT;
        return kinds[tip.ordinal() - 1][h];
    }

    /** States kept by a tip (counted for both tips). */
    public int keptStates() {
        return count(KEPT);
    }

    public int unknownStates() {
        return count(UNKNOWN_PROPERTIES);
    }

    public long buildNanos() {
        return buildNanos;
    }

    @Override
    public String toString() {
        return "StateTips[" + keptStates() + " kept, " + unknownStates() + " with unknown properties]";
    }

    private int count(int kind) {
        if (kinds == null) return 0;
        int n = 0;
        for (byte[] row : kinds) {
            for (byte k : row) {
                if (k == kind) n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ rules (pure, on handles and property text)

    /** {tipped handle, kind} of state {@code h}. */
    static int[] tipOf(VerticalFlip.StateView p, VerticalFlip.Hints hints, int h, Collection<String> names,
                       CubeTurn.Tip tip) {
        if (hints.upright(h)) return kept(h);
        for (String name : names) {
            if (KEPT_PROPERTIES.contains(name)) return kept(h);
        }
        String half = names.contains("half") ? p.value(h, "half") : null;
        if ("upper".equals(half) || "lower".equals(half)) return kept(h);
        String type = names.contains("type") ? p.value(h, "type") : null;
        if ("top".equals(type) || "bottom".equals(type)) return kept(h);
        boolean upDownHalf = "top".equals(half) || "bottom".equals(half);
        if (upDownHalf && names.contains("facing") && names.contains("shape")) return stairs(p, h, tip);
        if (upDownHalf && names.contains("facing") && names.contains("open")) return trapdoor(p, h, tip);
        if (upDownHalf) return kept(h);
        if (names.contains("face") && names.contains("facing")) return face(p, h, tip);
        int result = h;
        boolean aware = false;
        boolean faces = false;
        for (String name : List.copyOf(names)) {
            String value = p.value(h, name);
            if (value == null) continue;
            String next;
            switch (name) {
                case "facing" -> {
                    next = direction(tip, value);
                    if (next == null) continue;
                    aware |= vertical(value) || vertical(next);
                }
                case "axis", "horizontal_axis" -> {
                    next = axis(tip, value);
                    if (next == null) continue;
                    aware |= value.equals("y") || next.equals("y");
                }
                case "orientation" -> {
                    next = orientation(tip, value);
                    if (next == null) return kept(h);
                    aware = true;
                }
                default -> {
                    faces |= DIRECTIONS.contains(name);
                    continue;
                }
            }
            if (next.equals(value)) continue;
            result = p.with(result, name, next);
            if (result < 0) return kept(h);
        }
        if (faces) {
            int[] turned = faces(p, h, result, names, tip);
            if (turned == null) return kept(h);
            result = turned[0];
            aware |= turned[1] != 0;
        }
        if (!aware && hints.lopsided(h)) return kept(h);
        return new int[] {result, TURNS};
    }

    private static int[] kept(int h) {
        return new int[] {h, KEPT};
    }

    /**
     * The six face properties moved round together ({@code faces[1]}: whether one of them is up or down and true, so the
     * block's vertical sides are told by them), or null when a true face cannot go where it goes, or a face property is
     * not a boolean that is false or a {@code none} (a wall's or redstone's connections).
     */
    private static int[] faces(VerticalFlip.StateView p, int h, int result, Collection<String> names, CubeTurn.Tip tip) {
        boolean booleans = true;
        Map<String, Boolean> before = new java.util.HashMap<>();
        for (String direction : DIRECTIONS) {
            if (!names.contains(direction)) continue;
            String value = p.value(h, direction);
            if ("true".equals(value)) {
                before.put(direction, true);
            } else if ("false".equals(value)) {
                before.put(direction, false);
            } else {
                booleans = false;
                if (!"none".equals(value)) return null;
            }
        }
        if (!booleans) {
            // Connections that are all "none" (and booleans false) look the same turned.
            return before.containsValue(true) ? null : new int[] {result, 0};
        }
        boolean aware = false;
        int out = result;
        for (String direction : DIRECTIONS) {
            boolean on = before.getOrDefault(direction, false);
            String to = direction(tip, direction);
            aware |= on && (vertical(direction) || vertical(to));
            if (!names.contains(to)) {
                if (on) return null;
                continue;
            }
            String value = Boolean.toString(on);
            if (!value.equals(p.value(out, to))) out = p.with(out, to, value);
            if (out < 0) return null;
        }
        return new int[] {out, aware ? 1 : 0};
    }

    // ---- stairs: matched by the eighths of the cell they fill

    private static final List<String> STAIR_SHAPES = List.of("straight", "inner_left", "inner_right", "outer_left",
            "outer_right");

    /** A stair tipped: the stair of the same block filling exactly the tipped eighths, else the nearest one (kept). */
    private static int[] stairs(VerticalFlip.StateView p, int h, CubeTurn.Tip tip) {
        String facing = p.value(h, "facing");
        String shape = p.value(h, "shape");
        if (!HORIZONTAL.contains(facing) || !STAIR_SHAPES.contains(shape)) return kept(h);
        int target = turnEighths(stairEighths(facing, p.value(h, "half"), shape), tip);
        int best = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (String f : HORIZONTAL) {
            for (String half : List.of("bottom", "top")) {
                for (String s : STAIR_SHAPES) {
                    int distance = Integer.bitCount(stairEighths(f, half, s) ^ target);
                    if (distance >= bestDistance) continue;
                    int candidate = p.with(h, "facing", f);
                    if (candidate >= 0) candidate = p.with(candidate, "half", half);
                    if (candidate >= 0) candidate = p.with(candidate, "shape", s);
                    if (candidate < 0) continue;
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        if (best < 0) return kept(h);
        return new int[] {best, bestDistance == 0 ? TURNS : KEPT};
    }

    /**
     * The eighths of the cell a stair fills, bit {@code (x << 2) | (y << 1) | z} for the eighth on the positive side of
     * each axis where the coordinate is 1: the half it stands on, the half towards its {@code facing}, and for a corner the
     * half to its left (counter-clockwise from above: west of north) or right, joined (inner) or crossed (outer).
     */
    static int stairEighths(String facing, String half, String shape) {
        int base = side(half.equals("top") ? "up" : "down");
        int back = side(facing);
        return switch (shape) {
            case "inner_left" -> base | back | side(counterClockwise(facing));
            case "inner_right" -> base | back | side(clockwise(facing));
            case "outer_left" -> base | (back & side(counterClockwise(facing)));
            case "outer_right" -> base | (back & side(clockwise(facing)));
            default -> base | back;
        };
    }

    /** The four eighths on the {@code direction} side of the cell. */
    private static int side(String direction) {
        int[] v = vector(direction);
        int bits = 0;
        for (int i = 0; i < 8; i++) {
            int x = (i >> 2) & 1, y = (i >> 1) & 1, z = i & 1;
            if ((v[0] != 0 && (2 * x - 1) == v[0]) || (v[1] != 0 && (2 * y - 1) == v[1])
                    || (v[2] != 0 && (2 * z - 1) == v[2])) {
                bits |= 1 << i;
            }
        }
        return bits;
    }

    /** The eighths moved by the tip. */
    static int turnEighths(int eighths, CubeTurn.Tip tip) {
        int out = 0;
        for (int i = 0; i < 8; i++) {
            if ((eighths & (1 << i)) == 0) continue;
            int[] v = tip.apply(2 * ((i >> 2) & 1) - 1, 2 * ((i >> 1) & 1) - 1, 2 * (i & 1) - 1);
            out |= 1 << ((((v[0] + 1) / 2) << 2) | (((v[1] + 1) / 2) << 1) | ((v[2] + 1) / 2));
        }
        return out;
    }

    // ---- trapdoors: the panel and its hinge

    /**
     * A trapdoor tipped: its panel lies on the side {@code half} names when closed and opposite its {@code facing} when
     * open, hinged along the edge opposite its facing when closed and along {@code half} when open. Both turn; the
     * trapdoor whose panel and hinge are there is taken, or the state is kept when the hinge would stand upright.
     */
    private static int[] trapdoor(VerticalFlip.StateView p, int h, CubeTurn.Tip tip) {
        String facing = p.value(h, "facing");
        if (!HORIZONTAL.contains(facing)) return kept(h);
        boolean open = "true".equals(p.value(h, "open"));
        String half = p.value(h, "half").equals("top") ? "up" : "down";
        String panel = direction(tip, open ? opposite(facing) : half);
        String hinge = direction(tip, open ? half : opposite(facing));
        int result;
        if (vertical(panel) && !vertical(hinge)) {
            result = set(p, h, "open", "false", "half", panel.equals("up") ? "top" : "bottom", "facing", opposite(hinge));
        } else if (!vertical(panel) && vertical(hinge)) {
            result = set(p, h, "open", "true", "half", hinge.equals("up") ? "top" : "bottom", "facing", opposite(panel));
        } else {
            return kept(h);
        }
        return result < 0 ? kept(h) : new int[] {result, TURNS};
    }

    // ---- buttons, levers, grindstones: the face they hang from

    /**
     * A block on a {@code face}: it hangs from the floor (below it), the ceiling, or a wall (opposite its facing); on
     * the floor or ceiling its facing turns it about the vertical, on a wall it stands upright. The face it hangs from
     * and that second direction turn; on a wall a second direction that is not up cannot be kept, so the wall is taken
     * and the state counted as kept.
     */
    private static int[] face(VerticalFlip.StateView p, int h, CubeTurn.Tip tip) {
        String face = p.value(h, "face");
        String facing = p.value(h, "facing");
        if (!HORIZONTAL.contains(facing)) return kept(h);
        String attached = switch (face) {
            case "floor" -> "down";
            case "ceiling" -> "up";
            case "wall" -> opposite(facing);
            default -> null;
        };
        if (attached == null) return kept(h);
        String second = face.equals("wall") ? "up" : facing;
        String a = direction(tip, attached);
        String s = direction(tip, second);
        int result;
        int kind = TURNS;
        if (a.equals("down") || a.equals("up")) {
            result = set(p, h, "face", a.equals("down") ? "floor" : "ceiling", "facing", s);
        } else {
            result = set(p, h, "face", "wall", "facing", opposite(a));
            if (!s.equals("up")) kind = KEPT;
        }
        return result < 0 ? kept(h) : new int[] {result, kind};
    }

    // ---- helpers

    /** {@code h} with the properties set in turn (name, value pairs), or -1. */
    private static int set(VerticalFlip.StateView p, int h, String... pairs) {
        int result = h;
        for (int i = 0; i < pairs.length && result >= 0; i += 2) {
            if (!pairs[i + 1].equals(p.value(result, pairs[i]))) result = p.with(result, pairs[i], pairs[i + 1]);
        }
        return result;
    }

    /** A direction's name tipped, or null for a value that is not a direction. */
    static String direction(CubeTurn.Tip tip, String direction) {
        int[] v = vector(direction);
        if (v == null) return null;
        int[] t = tip.apply(v[0], v[1], v[2]);
        return name(t);
    }

    /** An axis tipped ({@code x}, {@code y} or {@code z}), or null. */
    static String axis(CubeTurn.Tip tip, String axis) {
        int[] t = switch (axis) {
            case "x" -> tip.apply(1, 0, 0);
            case "y" -> tip.apply(0, 1, 0);
            case "z" -> tip.apply(0, 0, 1);
            default -> null;
        };
        if (t == null) return null;
        return t[0] != 0 ? "x" : t[1] != 0 ? "y" : "z";
    }

    /** A jigsaw or crafter orientation ({@code front_top}) with both parts tipped, or null. */
    private static String orientation(CubeTurn.Tip tip, String value) {
        int split = value.indexOf('_');
        if (split <= 0 || split == value.length() - 1) return null;
        String front = direction(tip, value.substring(0, split));
        String top = direction(tip, value.substring(split + 1));
        return front == null || top == null ? null : front + "_" + top;
    }

    private static int[] vector(String direction) {
        return switch (direction) {
            case "north" -> new int[] {0, 0, -1};
            case "south" -> new int[] {0, 0, 1};
            case "east" -> new int[] {1, 0, 0};
            case "west" -> new int[] {-1, 0, 0};
            case "up" -> new int[] {0, 1, 0};
            case "down" -> new int[] {0, -1, 0};
            default -> null;
        };
    }

    private static String name(int[] v) {
        if (v[0] != 0) return v[0] > 0 ? "east" : "west";
        if (v[1] != 0) return v[1] > 0 ? "up" : "down";
        return v[2] > 0 ? "south" : "north";
    }

    private static boolean vertical(String direction) {
        return direction.equals("up") || direction.equals("down");
    }

    private static String opposite(String direction) {
        int[] v = vector(direction);
        return name(new int[] {-v[0], -v[1], -v[2]});
    }

    private static String clockwise(String horizontal) {
        return HORIZONTAL.get((HORIZONTAL.indexOf(horizontal) + 1) % 4);
    }

    private static String counterClockwise(String horizontal) {
        return HORIZONTAL.get((HORIZONTAL.indexOf(horizontal) + 3) % 4);
    }

    private static String namespace(NamespacedId id) {
        String value = id.value();
        return value.substring(0, value.indexOf(':'));
    }
}
