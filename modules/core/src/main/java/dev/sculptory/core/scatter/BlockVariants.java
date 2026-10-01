package dev.sculptory.core.scatter;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Scatter variants that are one block ({@link ScatterSource.Block}): resolving their state text, where they go
 * ({@link Medium}), and their footprint.
 *
 * <p>Any block state may be one except air, fluid blocks (water, lava) and blocks that hold a fluid without being a
 * water plant (bubble columns). Where a block goes follows from its state:
 * <ul>
 *   <li>{@link Medium#UNDERWATER}: an {@link StateFlags#AQUATIC aquatic} block (seagrass, tall seagrass, kelp, live
 *       coral), which is made waterlogged when it can be, or a waterloggable block whose state is waterlogged (a sea
 *       pickle or a dead coral fan by default, or any waterlogged plant the player picked): placed in still water on
 *       the ground under it (the seabed), every cell replacing still water.</li>
 *   <li>{@link Medium#WATER_SURFACE}: a block that goes on water ({@link StateFlags#ON_WATER}: lily pads,
 *       frogspawn), made dry: placed on the air cell above still water.</li>
 *   <li>{@link Medium#LAND}: everything else, which then holds no water (a waterloggable block given as
 *       {@code waterlogged=false}, or without the property): placed on the ground in open, dry cells. So no water is
 *       ever left on land.</li>
 * </ul>
 * A block that stands two cells tall ({@link StateFlags#DOUBLE_TALL}: a vanilla two-block plant or a door) places both
 * halves: the lower on the anchor, the upper above it, whichever half was picked; anything else is placed as one cell,
 * even with a {@code half} property. A column plant ({@link StateSpace#columnPart}: sugar cane, cactus, bamboo, kelp)
 * is given by its top state and placed as a column ({@link #column}) as tall as the scatter's
 * {@link ScatterSettings.ColumnHeight} allows. Block-entity data is never carried: such a block gets its default
 * (empty) block entity.
 */
public final class BlockVariants {
    private static final String HALF = "half";
    private static final String LOWER = "lower";
    private static final String UPPER = "upper";

    /** Where a block variant's placements go (every other scatter source goes on {@link #LAND}). */
    public enum Medium {
        /** On the ground, in open dry cells. */
        LAND,
        /** On the ground under water (the seabed), every cell replacing still water. */
        UNDERWATER,
        /** On the air cell right above still water. */
        WATER_SURFACE
    }

    private BlockVariants() {}

    /**
     * The handle of a block variant's state text: made waterlogged if aquatic, dry if it goes on water, the lower half
     * for a double-tall block and the top for a column plant.
     *
     * @throws IllegalArgumentException saying why: malformed text, an unknown block, properties the block does not
     *     have or values it does not take, air, a fluid block, a block that holds a fluid and is not a water plant, or
     *     a state text over {@value ScatterSource#MAX_STATE_BYTES} bytes
     */
    public static int resolve(StateSpace states, String text) {
        Objects.requireNonNull(states);
        Objects.requireNonNull(text);
        BlockDescriptor descriptor;
        try {
            descriptor = BlockDescriptor.parse(text);
        } catch (IllegalArgumentException malformed) {
            throw new IllegalArgumentException("malformed block state \"" + clip(text) + "\"");
        }
        int state = states.resolve(descriptor);
        if (state < 0) {
            if (states.resolve(BlockDescriptor.of(descriptor.block())) < 0) {
                throw new IllegalArgumentException("unknown block " + descriptor.block().value());
            }
            throw new IllegalArgumentException("invalid properties for " + descriptor.block().value() + ": "
                    + clip(descriptor.properties().toString()));
        }
        int flags = states.flags(state);
        if (StateFlags.has(flags, StateFlags.AQUATIC)) {
            state = states.withWaterlogged(state, true);
        } else if (StateFlags.has(flags, StateFlags.ON_WATER)) {
            state = states.withWaterlogged(state, false);
        }
        checkPlaceable(states, state);
        int normal = columnTop(states, lowerHalf(states, state));
        if (states.format(normal).getBytes(StandardCharsets.UTF_8).length > ScatterSource.MAX_STATE_BYTES) {
            throw new IllegalArgumentException("the state of " + descriptor.block().value() + " is over "
                    + ScatterSource.MAX_STATE_BYTES + " bytes");
        }
        return normal;
    }

    /**
     * Throws unless {@code state} may be a variant: not air, not a fluid block, and holding a fluid only as a water
     * plant (an aquatic block, or a waterlogged one), not as a block that goes on water, and an aquatic waterloggable
     * block only waterlogged.
     */
    public static void checkPlaceable(StateSpace states, int state) {
        int flags = states.flags(state);
        String id = states.blockId(state).value();
        if (StateFlags.has(flags, StateFlags.AIR)) throw new IllegalArgumentException("air is not a scatter variant");
        if (StateFlags.has(flags, StateFlags.FLUID_BLOCK)) {
            throw new IllegalArgumentException(id + " is a fluid, not a scatter variant");
        }
        boolean wet = states.fluidSource(state) >= 0;
        if (wet && !StateFlags.has(flags, StateFlags.WATER)) {
            throw new IllegalArgumentException(id + " holds a fluid other than water");
        }
        if (wet && !StateFlags.has(flags, StateFlags.AQUATIC) && !StateFlags.has(flags, StateFlags.WATERLOGGED)) {
            throw new IllegalArgumentException(id + " carries water but is not a water plant");
        }
        if (wet && StateFlags.has(flags, StateFlags.ON_WATER)) {
            throw new IllegalArgumentException(id + " goes on water, not in it");
        }
        if (StateFlags.has(flags, StateFlags.AQUATIC) && StateFlags.has(flags, StateFlags.WATERLOGGABLE)
                && !StateFlags.has(flags, StateFlags.WATERLOGGED)) {
            throw new IllegalArgumentException(id + " dies out of water: it goes in waterlogged");
        }
    }

    /** Where the placements of a (placeable) block variant go; see the class comment. */
    public static Medium medium(StateSpace states, int state) {
        int flags = states.flags(state);
        if (StateFlags.has(flags, StateFlags.ON_WATER)) return Medium.WATER_SURFACE;
        if (StateFlags.has(flags, StateFlags.AQUATIC) || StateFlags.has(flags, StateFlags.WATER)) {
            return Medium.UNDERWATER;
        }
        return Medium.LAND;
    }

    /** Whether {@code state} is one half of a block that stands two cells tall. */
    public static boolean doubleTall(StateSpace states, int state) {
        return StateFlags.has(states.flags(state), StateFlags.DOUBLE_TALL);
    }

    /** Whether {@code state} is part of a plant that grows as a column ({@link StateSpace#columnPart}). */
    public static boolean isColumn(StateSpace states, int state) {
        return states.columnPart(state, true) >= 0;
    }

    /** The lower half of a double-tall block; any other state as it is. */
    public static int lowerHalf(StateSpace states, int state) {
        return doubleTall(states, state) ? withHalf(states, state, LOWER) : state;
    }

    /** The upper half of a double-tall block. */
    public static int upperHalf(StateSpace states, int state) {
        if (!doubleTall(states, state)) throw new IllegalArgumentException(states.format(state) + " is not double-tall");
        return withHalf(states, state, UPPER);
    }

    /** The top of a column plant's column (kelp for kelp_plant; the state itself for a top); any other state as it is. */
    public static int columnTop(StateSpace states, int state) {
        int top = states.columnPart(state, true);
        return top >= 0 ? top : state;
    }

    /**
     * A block variant's footprint as a clipboard anchored on its only (or lower) cell: 1 × 1 × 1, or 1 × 2 × 1 with
     * the upper half above for a double-tall block. A column plant's is its top alone (a column of height 1).
     */
    public static Clipboard clipboard(StateSpace states, int state) {
        checkPlaceable(states, state);
        int lower = lowerHalf(states, state);
        if (!doubleTall(states, lower)) {
            return Clipboard.builder(states, new BlockPos(1, 1, 1)).set(0, 0, 0, lower).build();
        }
        return Clipboard.builder(states, new BlockPos(1, 2, 1)).set(0, 0, 0, lower)
                .set(0, 1, 0, upperHalf(states, lower)).build();
    }

    /**
     * A column plant as a 1 × {@code height} × 1 clipboard anchored on its bottom cell: the column's cells below the
     * top ({@link StateSpace#columnPart}, waterlogged like {@code state} where they can be) and {@code state}'s top on
     * top; {@link #clipboard} for height 1.
     *
     * @throws IllegalArgumentException for a state that is not a placeable column plant, or a height outside
     *     1-{@value ScatterSettings#MAX_COLUMN_HEIGHT}
     */
    public static Clipboard column(StateSpace states, int state, int height) {
        if (height < 1 || height > ScatterSettings.MAX_COLUMN_HEIGHT) {
            throw new IllegalArgumentException("Column height must be 1-" + ScatterSettings.MAX_COLUMN_HEIGHT);
        }
        if (!isColumn(states, state)) throw new IllegalArgumentException(states.format(state) + " is not a column plant");
        int top = columnTop(states, state);
        if (height == 1) return clipboard(states, top);
        checkPlaceable(states, top);
        boolean wet = StateFlags.has(states.flags(top), StateFlags.WATERLOGGED);
        int body = states.withWaterlogged(states.columnPart(top, false), wet);
        checkPlaceable(states, body);
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(1, height, 1));
        for (int y = 0; y < height - 1; y++) builder.set(0, y, 0, body);
        return builder.set(0, height - 1, 0, top).build();
    }

    private static int withHalf(StateSpace states, int state, String half) {
        int h = states.resolve(states.describe(state).with(HALF, half));
        if (h < 0) throw new IllegalArgumentException(states.format(state) + " has no " + half + " half");
        return h;
    }

    private static String clip(String text) {
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
