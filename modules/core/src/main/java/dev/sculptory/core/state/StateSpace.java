package dev.sculptory.core.state;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.transform.Mirror;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Exact-state int handles in {@code [0, size())}. Immutable once built. Rebuilt on every client join
 * (registry sync remaps raw ids), so handles never cross the wire or reach disk: use {@link #format} there.
 *
 * <p>Methods taking a handle throw {@link IndexOutOfBoundsException} for handles outside the space.
 */
public interface StateSpace {
    int size();

    /** The handle of the plain air state. */
    int air();

    /** {@link StateFlags} bitset, O(1). */
    int flags(int h);

    /** The {@link BlockDescriptor#format()} text of a state. Wire/schematic boundary only. */
    String format(int h);

    /**
     * Parses {@link BlockDescriptor} text. Properties that are left out take the block's default value.
     * Wire/schematic boundary only.
     *
     * @return the handle, or -1 if the text is malformed, the block is unknown, or a property name or
     *     value is unknown for that block
     */
    int parse(String spec);

    BlockDescriptor describe(int h);

    /** Same rules as {@link #parse}: missing properties take defaults; unknown input returns -1. */
    int resolve(BlockDescriptor d);

    NamespacedId blockId(int h);

    boolean inTag(int h, NamespacedId tag);

    /**
     * Rotates around +Y, clockwise seen from above. Fabric: {@code BlockState.rotate(BlockRotation)}, or for a modded
     * block that does not turn itself the {@link ModdedFacingFallback} when it is switched on.
     */
    int rotate(int h, int clockwiseQuarterTurns);

    /**
     * Fabric: {@code BlockState.mirror(BlockMirror)}; {@link Mirror#X} is FRONT_BACK, {@link Mirror#Z} LEFT_RIGHT. Like
     * {@link #rotate}, a modded block that does not mirror itself may be mirrored by the {@link ModdedFacingFallback}.
     */
    int mirror(int h, Mirror m);

    /**
     * Flips a state upside down: the block part of a {@code Transform} that is {@code upsideDown} (the rules are {@link VerticalFlip}'s). A state with no upside-down form comes back as it
     * is ({@link #flipKind} tells which). Flipping twice gives the state back. Spaces that cannot flip answer
     * {@code h}.
     */
    default int flip(int h) {
        return h;
    }

    /**
     * How {@link #flip} treats a state: {@link VerticalFlip#FLIPS}, {@link VerticalFlip#KEPT} (no upside-down form, left
     * as it is) or {@link VerticalFlip#UNKNOWN_PROPERTIES} (a modded state with properties the flip does not know).
     */
    default int flipKind(int h) {
        return VerticalFlip.FLIPS;
    }

    /** Sets the waterlogged property; identity when the state is not waterloggable. */
    int withWaterlogged(int h, boolean on);

    /** The water/lava source state for fluid-bearing states (fluid blocks and waterlogged states), else -1. */
    int fluidSource(int h);

    /**
     * Whether block entities of type {@code typeId} (a namespaced id) hold sign text: vanilla's sign and hanging sign,
     * or a modded type built on them. Sanitizers keep such NBT as text only. Spaces that cannot tell answer false.
     */
    default boolean isSignBlockEntity(String typeId) {
        return false;
    }

    /**
     * For a plant that grows as a column straight up (sugar cane, cactus, bamboo, kelp; on Fabric also any
     * upward-growing {@code AbstractPlantPartBlock}, such as twisting vines): the state of a cell in a column that
     * {@code h} belongs to, its top cell when {@code top}, else a cell below the top. A kelp column is {@code kelp} on
     * top of {@code kelp_plant}; a sugar cane, cactus or bamboo column repeats {@code h}. -1 when {@code h} is not
     * such a plant; spaces that cannot tell answer -1 for everything.
     */
    default int columnPart(int h, boolean top) {
        return -1;
    }

    /**
     * The state {@code h} with {@code property} set to {@code value} (a value's text form, as {@link #format} writes
     * it), or -1 when the state has no such property or its block no such value. For Tinker: scrolling through a property's values, and the property pattern. The default goes through
     * {@link #describe} and {@link #resolve}; spaces with a faster way override it.
     */
    default int withProperty(int h, String property, String value) {
        BlockDescriptor state = describe(h);
        String current = state.get(property);
        if (current == null || value == null) return -1;
        if (current.equals(value)) return h;
        try {
            return resolve(state.with(property, value));
        } catch (IllegalArgumentException invalid) {
            return -1;
        }
    }

    /**
     * {@code target} with every property it shares with {@code source} set to {@code source}'s value, where
     * {@code target}'s block has that value (Better Replace's "Keep shape": an oak stair replaced by a spruce stair keeps
     * its facing, half and shape). A property only one of them
     * has, or a value {@code target}'s block lacks, is left as {@code target} has it. The default goes through
     * {@link #describe} and {@link #withProperty}; spaces with a faster way override it.
     */
    default int withSharedProperties(int target, int source) {
        if (target == source) return target;
        int result = target;
        for (var property : describe(source).properties().entrySet()) {
            int changed = withProperty(result, property.getKey(), property.getValue());
            if (changed >= 0) result = changed;
        }
        return result;
    }

    /**
     * The values {@code property} can take on {@code h}'s block, in the block's own order (a stair's shapes from
     * {@code straight} to {@code outer_right}); empty when the state has no such property. The default collects them
     * from every state of the block, in handle order; spaces that know their blocks' properties override it.
     */
    default List<String> propertyValues(int h, String property) {
        BlockDescriptor state = describe(h);
        if (state.get(property) == null) return List.of();
        NamespacedId block = state.block();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (int other = 0; other < size(); other++) {
            if (!blockId(other).equals(block)) continue;
            String value = describe(other).get(property);
            if (value != null) values.add(value);
        }
        return List.copyOf(values);
    }

    /**
     * Whether the block entity of a cell in state {@code from} still fits once the cell is {@code to}, so an edit that
     * keeps the cell's shape may carry it over (Better Replace's Keep shape: a sign turned into another wood's sign keeps
     * its text, a shulker box dyed another colour its contents).
     * The default says so when both states carry a block entity and belong to the same block; the Fabric space asks
     * the block-entity type of {@code from}'s block whether it supports {@code to}.
     */
    default boolean keepsBlockEntity(int from, int to) {
        return StateFlags.has(flags(from), StateFlags.HAS_BLOCK_ENTITY)
                && StateFlags.has(flags(to), StateFlags.HAS_BLOCK_ENTITY)
                && blockId(from).equals(blockId(to));
    }
}
