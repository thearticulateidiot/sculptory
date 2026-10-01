package dev.sculptory.core.edit;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * What state to write at a cell. Declarative and serializable; handles are of the job's {@code StateSpace}.
 *
 * <p>Every variant is a pure function of the cell's position, the state there now and the state space. A pattern answers {@code before} for a cell it leaves
 * alone, and every program and kernel writes a cell only when the answer differs from the state there, so an
 * unchanged cell is neither written nor recorded in history. {@link Single}, {@link Weighted} and {@link Arranged} depend
 * on the position only (a Steepness {@link Arranged} also on the ground's steepness, which its caller measures);
 * {@link Waterlog} and {@link Dry} on the state only.
 */
public sealed interface Pattern {
    /** The state to write at (x, y, z), given the state there now ({@code before}, a handle of {@code states}). */
    int apply(StateSpace states, int x, int y, int z, int before);

    /**
     * Whether {@code h} is a fluid source state (still water or lava at its source level: {@link StateFlags#FLUID_BLOCK}
     * and its own {@link StateSpace#fluidSource}), the only states a {@link Waterlog} may carry.
     */
    static boolean isFluidSource(StateSpace states, int h) {
        if (h < 0 || h >= states.size()) return false;
        return StateFlags.has(states.flags(h), StateFlags.FLUID_BLOCK) && states.fluidSource(h) == h;
    }

    /**
     * Whether a cell {@code pattern} changes from {@code before} to {@code after} keeps its block entity: a state
     * pattern ({@link Waterlog}, {@link Dry}) that only changed a property of the same block (a sign waterlogged or
     * dried keeps its text). Programs and kernels then carry the cell's tile into their output, so the writer
     * re-applies it instead of creating the block's default block entity.
     */
    static boolean keepsTile(Pattern pattern, StateSpace states, int before, int after) {
        if (!(pattern instanceof Waterlog || pattern instanceof Dry || pattern instanceof SetProperty)) return false;
        return states.blockId(before).equals(states.blockId(after));
    }

    /** Always the same state. */
    record Single(int state) implements Pattern {
        public Single {
            if (state < 0) throw new IllegalArgumentException("Negative state handle");
        }

        @Override
        public int apply(StateSpace states, int x, int y, int z, int before) {
            return state;
        }
    }

    /**
     * A weighted pick that depends only on the seed, the position and the entry order: the chained
     * SplitMix64 position hash of the old {@code SurfacePalettes.select}, reduced modulo the total weight.
     * Arrays are copied in and out.
     */
    record Weighted(int[] states, int[] weights, long seed) implements Pattern {
        public static final int MAX_ENTRIES = 64;
        public static final int MAX_WEIGHT = 1000;

        public Weighted {
            Objects.requireNonNull(states);
            Objects.requireNonNull(weights);
            if (states.length != weights.length) throw new IllegalArgumentException("States and weights differ in length");
            if (states.length == 0 || states.length > MAX_ENTRIES) {
                throw new IllegalArgumentException("Weighted pattern needs 1-" + MAX_ENTRIES + " entries");
            }
            states = states.clone();
            weights = weights.clone();
            for (int i = 0; i < states.length; i++) {
                if (states[i] < 0) throw new IllegalArgumentException("Negative state handle");
                if (weights[i] < 1 || weights[i] > MAX_WEIGHT) {
                    throw new IllegalArgumentException("Weight must be 1-" + MAX_WEIGHT);
                }
                for (int j = 0; j < i; j++) {
                    if (states[j] == states[i]) throw new IllegalArgumentException("Duplicate state in pattern");
                }
            }
        }

        @Override
        public int[] states() {
            return states.clone();
        }

        @Override
        public int[] weights() {
            return weights.clone();
        }

        public int size() {
            return states.length;
        }

        public int state(int entry) {
            return states[entry];
        }

        public int weight(int entry) {
            return weights[entry];
        }

        // The parameter shadows the record's own array, which is read as this.states.
        @Override
        public int apply(StateSpace states, int x, int y, int z, int before) {
            return this.states[entryFor(SplitMix64.hash(seed, x, y, z))];
        }

        /** The sum of the weights. */
        public long totalWeight() {
            long total = 0;
            for (int weight : weights) total += weight;
            return total;
        }

        /** The entry a random 64-bit {@code hash} picks by weight: its unsigned remainder by the total weight. */
        public int entryFor(long hash) {
            long pick = Long.remainderUnsigned(hash, totalWeight());
            long cumulative = 0;
            for (int i = 0; i < weights.length; i++) {
                cumulative += weights[i];
                if (pick < cumulative) return i;
            }
            throw new AssertionError("bounded weighted pick");
        }

        /**
         * The entry at {@code t} along the entries laid end to end, each as long as its share of the total weight: 0 up
         * to the first entry's share, and so on; at or below 0 the first, at or past 1 the last (a Gradient's or
         * Steepness's band).
         */
        public int entryAt(double t) {
            double target = t * totalWeight();
            long cumulative = 0;
            for (int i = 0; i < weights.length - 1; i++) {
                cumulative += weights[i];
                if (target < cumulative) return i;
            }
            return weights.length - 1;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Weighted other
                    && seed == other.seed
                    && Arrays.equals(states, other.states)
                    && Arrays.equals(weights, other.weights);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(states), Arrays.hashCode(weights), seed);
        }

        @Override
        public String toString() {
            return "Weighted[states=" + Arrays.toString(states) + ", weights=" + Arrays.toString(weights)
                    + ", seed=" + seed + "]";
        }
    }

    /**
     * A weighted mix laid out in space by {@code layout} instead of picked at random per block (the Pattern setting's
     * Patches, Gradient and Steepness: {@link MixLayout}). The mix gives the
     * blocks in order, their weights and the seed of the layout's noise. A position pattern: a pure function of the
     * cell's position, like {@link Weighted}, except that a {@link MixLayout.Steepness} also needs the steepness of the
     * ground at the cell, which only Palette Paint measures ({@link #apply(int, int, int, double)}); {@link #apply}
     * without it takes the ground as flat. Everything that runs patterns refuses a Steepness layout except Palette
     * Paint's brush (the codec, the op compiler and the brush admission).
     */
    record Arranged(Weighted mix, MixLayout layout) implements Pattern {
        public Arranged {
            Objects.requireNonNull(mix);
            Objects.requireNonNull(layout);
        }

        /** The state at (x, y, z); a Steepness layout takes the ground as flat (0°). */
        @Override
        public int apply(StateSpace states, int x, int y, int z, int before) {
            return apply(x, y, z, 0);
        }

        /** The state at (x, y, z) on ground {@code degrees} steep (0 flat, 90 vertical; only a Steepness layout reads it). */
        public int apply(int x, int y, int z, double degrees) {
            int entry = switch (layout) {
                case MixLayout.Patches patches -> patches.entry(mix, x, y, z);
                case MixLayout.Gradient gradient -> gradient.entry(mix, x, y, z);
                case MixLayout.Steepness steepness -> steepness.entry(mix, x, y, z, degrees);
            };
            return mix.state(entry);
        }

        /** Whether the layout reads the ground's steepness ({@link MixLayout.Steepness}). */
        public boolean steepness() {
            return layout instanceof MixLayout.Steepness;
        }
    }

    /**
     * Whether {@code pattern} is a mix laid out by the ground's steepness, which only Palette Paint can measure (a
     * {@link KeepShape} around one too).
     */
    static boolean needsSteepness(Pattern pattern) {
        if (pattern instanceof KeepShape keep) pattern = keep.inner();
        return pattern instanceof Arranged arranged && arranged.steepness();
    }

    /**
     * Fills air with a fluid and waterlogs what it can: a cell flagged {@link StateFlags#AIR} becomes
     * {@code fluidSource}; a cell with the waterlogged property ({@link StateFlags#WATERLOGGABLE}) becomes its
     * waterlogged state when the fluid is water (lava cannot waterlog, so with lava such a cell is left alone); any
     * other cell (stone, grass, an existing fluid) is unchanged. {@code fluidSource} must be a fluid source state
     * ({@link Pattern#isFluidSource}); the codec, the compiler and the brush admission refuse anything else.
     */
    record Waterlog(int fluidSource) implements Pattern {
        public Waterlog {
            if (fluidSource < 0) throw new IllegalArgumentException("Negative state handle");
        }

        @Override
        public int apply(StateSpace states, int x, int y, int z, int before) {
            int flags = states.flags(before);
            if (StateFlags.has(flags, StateFlags.AIR)) return fluidSource;
            if (StateFlags.has(flags, StateFlags.WATERLOGGABLE)
                    && StateFlags.has(states.flags(fluidSource), StateFlags.WATER)) {
                return states.withWaterlogged(before, true);
            }
            return before;
        }
    }

    /**
     * Removes fluids: a fluid block at any level ({@link StateFlags#FLUID_BLOCK}) becomes air; a waterlogged state
     * ({@link StateFlags#WATERLOGGED}) loses its water; a block that holds water without the property
     * ({@link StateFlags#WATER} and not {@link StateFlags#WATERLOGGABLE}: seagrass, kelp, a bubble column) becomes air,
     * since it cannot exist dry; anything else is unchanged.
     */
    record Dry() implements Pattern {
        @Override
        public int apply(StateSpace states, int x, int y, int z, int before) {
            int flags = states.flags(before);
            if (StateFlags.has(flags, StateFlags.FLUID_BLOCK)) return states.air();
            if (StateFlags.has(flags, StateFlags.WATERLOGGED)) return states.withWaterlogged(before, false);
            if (StateFlags.has(flags, StateFlags.WATER) && !StateFlags.has(flags, StateFlags.WATERLOGGABLE)) {
                return states.air();
            }
            return before;
        }
    }

    /**
     * Sets one property on every cell of one block, Tinker's "Apply to all like it in the selection": a cell holding {@code template}'s block gets {@code property} set to the value
     * {@code template} has, its other properties kept (a stair keeps its facing when its shape is set); a cell of any
     * other block is unchanged. {@code template} must have the property; the codec and the compiler refuse anything
     * else. The cell keeps its block entity ({@link Pattern#keepsTile}).
     */
    record SetProperty(int template, String property) implements Pattern {
        public SetProperty {
            if (template < 0) throw new IllegalArgumentException("Negative state handle");
            Objects.requireNonNull(property);
            if (property.isEmpty()) throw new IllegalArgumentException("Empty property name");
        }

        /** Whether {@code template} is a state of {@code states} with the property. */
        public boolean validIn(StateSpace states) {
            return template < states.size() && states.describe(template).get(property) != null;
        }

        /** The value the pattern sets. */
        public String value(StateSpace states) {
            return states.describe(template).get(property);
        }

        @Override
        public int apply(StateSpace states, int x, int y, int z, int before) {
            if (!states.blockId(before).equals(states.blockId(template))) return before;
            String value = states.describe(template).get(property);
            int changed = value == null ? -1 : states.withProperty(before, property, value);
            return changed < 0 ? before : changed;
        }
    }

    /**
     * Better Replace's "Keep shape": {@code inner}'s state with the
     * properties it shares with the cell's state copied over ({@link StateSpace#withSharedProperties}), so an oak stair
     * replaced by spruce stairs keeps its facing, half and shape. {@code inner} is neither a {@link KeepShape} nor a
     * {@link Remap} (the codec and this constructor refuse nesting).
     */
    record KeepShape(Pattern inner) implements Pattern {
        public KeepShape {
            Objects.requireNonNull(inner);
            if (inner instanceof KeepShape || inner instanceof Remap) {
                throw new IllegalArgumentException("Keep shape around " + inner.getClass().getSimpleName());
            }
        }

        @Override
        public int apply(StateSpace states, int x, int y, int z, int before) {
            return states.withSharedProperties(inner.apply(states, x, y, z, before), before);
        }
    }

    /**
     * Better Replace's "Whole family": each cell of a {@code from} block becomes the matching {@code to} block (its
     * default state, or with {@code keepShape} the properties it shares with the cell's state kept); a cell of any
     * other block is unchanged. The client lists the swaps ({@code BlockFamilies.swaps}); the server guesses no names.
     * 1 to {@value #MAX_SWAPS} swaps, no block swapped twice.
     */
    record Remap(List<BlockSwap> swaps, boolean keepShape) implements Pattern {
        public static final int MAX_SWAPS = 1024;

        public Remap {
            swaps = List.copyOf(swaps);
            if (swaps.isEmpty() || swaps.size() > MAX_SWAPS) {
                throw new IllegalArgumentException("A remap needs 1-" + MAX_SWAPS + " swaps");
            }
            Set<NamespacedId> from = new HashSet<>();
            for (BlockSwap swap : swaps) {
                if (!from.add(swap.from())) throw new IllegalArgumentException("A block swapped twice: " + swap.from());
            }
        }

        /**
         * The cell's counterpart: {@code before}'s block swapped for its {@code to} block's default state (with
         * {@code keepShape}, the properties both share kept from {@code before}); {@code before} itself when its block
         * is not swapped or the {@code to} block is not in {@code states}. Region programs cache this per state
         * ({@code RemapTable}); this form looks the swap up each time.
         */
        @Override
        public int apply(StateSpace states, int x, int y, int z, int before) {
            NamespacedId block = states.blockId(before);
            for (BlockSwap swap : swaps) {
                if (swap.from().equals(block)) return target(states, swap.to(), before);
            }
            return before;
        }

        /** {@code to}'s default state for a cell now {@code before} (shape kept with {@code keepShape}), or {@code before}. */
        int target(StateSpace states, NamespacedId to, int before) {
            int state = states.parse(to.value());
            if (state < 0) return before;
            return keepShape ? states.withSharedProperties(state, before) : state;
        }
    }

    /** One block of a {@link Remap}: every {@code from} cell becomes {@code to}. Not a pattern itself. */
    record BlockSwap(NamespacedId from, NamespacedId to) {
        public BlockSwap {
            Objects.requireNonNull(from);
            Objects.requireNonNull(to);
        }
    }
}
