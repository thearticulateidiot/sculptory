package dev.sculptory.core.tinker;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Block properties as Tinker offers them: the order they are listed and cycled in,
 * stepping through a property's values, and their names as shown. Pure; any registered property of any block works
 * through the state space ({@link StateSpace#propertyValues}, {@link StateSpace#withProperty}).
 */
public final class TinkerProperties {
    /**
     * Properties listed first, in this order: the ones builders change most (a stair's shape, where a block faces, top or
     * bottom, a slab's type, doors and trapdoors, water, a log's axis, the connections of fences, walls, panes and
     * redstone). Every other property follows in name order.
     */
    public static final List<String> USEFUL_FIRST = List.of("shape", "facing", "half", "type", "open", "waterlogged",
            "axis", "north", "east", "south", "west", "up", "down", "hinge", "face", "attachment", "rotation",
            "horizontal_facing", "vertical_direction", "orientation", "lit", "powered", "snowy", "hanging", "attached");

    private TinkerProperties() {}

    /** {@code names} in Tinker's order: {@link #USEFUL_FIRST} ones in that order, then the rest by name. */
    public static List<String> order(Collection<String> names) {
        List<String> ordered = new ArrayList<>(names.size());
        for (String useful : USEFUL_FIRST) {
            if (names.contains(useful)) ordered.add(useful);
        }
        TreeSet<String> rest = new TreeSet<>(names);
        USEFUL_FIRST.forEach(rest::remove);
        ordered.addAll(rest);
        return List.copyOf(ordered);
    }

    /** The properties of state {@code h} in Tinker's order (empty for a block without properties). */
    public static List<String> of(StateSpace states, int h) {
        return order(states.describe(h).properties().keySet());
    }

    /**
     * The value {@code steps} places after {@code current} in {@code values}, going round (negative steps go back);
     * {@code current} itself when it is not among them or there is only one value.
     */
    public static String step(List<String> values, String current, int steps) {
        Objects.requireNonNull(values);
        int at = values.indexOf(current);
        if (at < 0 || values.size() < 2) return current;
        return values.get(Math.floorMod(at + steps, values.size()));
    }

    /**
     * State {@code h} with {@code property} moved {@code steps} values on (Scroll), or {@code h} when it has no such
     * property or only one value.
     */
    public static int stepValue(StateSpace states, int h, String property, int steps) {
        BlockDescriptor state = states.describe(h);
        String current = state.get(property);
        if (current == null) return h;
        String next = step(states.propertyValues(h, property), current, steps);
        if (next.equals(current)) return h;
        int changed = states.withProperty(h, property, next);
        return changed < 0 ? h : changed;
    }

    /**
     * The property {@code steps} places after {@code current} among {@code h}'s properties in Tinker's order, going
     * round (Shift+Scroll); the first one when {@code current} is null or not among them; null for a block without
     * properties.
     */
    public static String stepProperty(StateSpace states, int h, String current, int steps) {
        List<String> names = of(states, h);
        if (names.isEmpty()) return null;
        int at = current == null ? -1 : names.indexOf(current);
        if (at < 0) return names.get(0);
        return names.get(Math.floorMod(at + steps, names.size()));
    }

    /**
     * The property Tinker shows for a block: {@code remembered} (the one last picked for that block) when the block has
     * it, else the first in Tinker's order; null for a block without properties.
     */
    public static String chosen(StateSpace states, int h, String remembered) {
        List<String> names = of(states, h);
        if (names.isEmpty()) return null;
        return remembered != null && names.contains(remembered) ? remembered : names.get(0);
    }

    /** A property or value name as shown: underscores as spaces ("outer_left" reads "outer left"). */
    public static String shown(String name) {
        return Objects.requireNonNull(name).replace('_', ' ').toLowerCase(Locale.ROOT);
    }

    /** "shape: outer left". */
    public static String shown(String property, String value) {
        return shown(property) + ": " + shown(value);
    }
}
