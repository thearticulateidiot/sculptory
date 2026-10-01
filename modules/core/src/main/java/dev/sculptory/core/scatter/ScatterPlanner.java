package dev.sculptory.core.scatter;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.SplitMix64;
import dev.sculptory.core.brush.ColumnFilter;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SurfaceScan;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellPredicate;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.scatter.BlockVariants.Medium;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.state.Water;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plans a scatter: which area columns get which variant, deterministically from the settings, the sources and the
 * world. Pure Java; reads the world only through {@link WorldReader}.
 *
 * <p><b>1. Survey</b> (per column, in any partition): a column's rank is {@code splitmix64(seed ^ (x·K1 + z·K2))}.
 * The density prefilter keeps it when the rank is below {@code density × 2⁶⁴}. Its chunk must be loaded. Its variant
 * is then picked (a weighted pick from its own hash domain of (seed, x, z), so it does not depend on the world, the
 * partition or the order), and the column must have a surface for that variant's {@link Medium medium} inside the
 * area's scan window that passes the {@link ScatterSettings.Filters filters}: for {@link Medium#LAND} (every held
 * source) and {@link Medium#UNDERWATER} the ground ({@link SurfaceScan}, the brush definition; under water that is
 * the seabed, and the cell above it must hold still water, else {@link Outcome#WATER}), for
 * {@link Medium#WATER_SURFACE} a still water surface (the first cell holding a fluid below open dry cells, if it is
 * still water; none is {@link Outcome#WATER}). Survivors become candidates anchored on the cell above the surface.
 * The filters read the surface: its y, the steepest step to the cardinal neighbours' surfaces of the same kind, and
 * its block (the seabed block under water, the water for a variant that goes on water).
 *
 * <p><b>Why a sum</b>: an obvious rank would be {@code splitmix64(seed ^ x·K1 ^ z·K2)}:
 * the rank combines the coordinates with a sum, {@code x·K1 + z·K2}. With the xor, odd (x, z) and (-x, -z) always
 * got the same rank (for odd a, {@code -a == ~a ^ 1}, and the two flipped low bits cancel), and with it the same
 * density decision, variant and transform, so scatters were point-symmetric around the world origin. The sum is
 * injective over the world (see {@link #rank}), so distinct columns never share a rank.
 *
 * <p><b>2. Acceptance</b> (sequential): candidates sorted by (unsigned rank, x, z) are accepted in turn unless the
 * count is reached, an accepted anchor is closer than the spacing ({@link SpatialGrid}), the footprint would pass
 * the cell budget, reaches an unloaded chunk, leaves the build height, overlaps an accepted footprint
 * ({@link Occupancy}) or a world cell it may not replace, or lacks support (see {@link ScatterSettings.Fit}: only
 * {@link SurfaceScan#open open} cells that are not fluid blocks or waterlogged may be replaced, unless the fit
 * allows fluids; the anchor column and at least the fit's share of the footprint's lowest layer need
 * {@link SurfaceScan#ground ground} directly beneath). A candidate's transform is a uniform pick from those its
 * variant allows, from its own hash domain of (seed, x, z), so it does not depend on the partition or the order.
 *
 * <p><b>Water plants</b> (block variants, {@link BlockVariants#medium}). An underwater variant's cells must each hold
 * plain still water (the water source block; with fluids allowed, any open cell holding still water, such as
 * seagrass or kelp): a cell that holds no still water (air, flowing water) is {@link Outcome#WATER}, one that holds
 * something else is {@link Outcome#COLLISION}. It stands on ground as on land. A variant that goes on water needs
 * open dry cells as on land, and still water (not ground) directly beneath: none under the anchor is
 * {@link Outcome#WATER}, too few under its lowest layer {@link Outcome#SUPPORT}. The fit's {@code allowInFluid} is for held
 * sources (assets and clipboards, such as a reef) and lets underwater variants replace water plants already there: a
 * land or water-surface block variant never replaces a cell holding a fluid, so no dry block is left under water.
 *
 * <p><b>Column plants</b> (block variants that grow as a column, {@link StateSpace#columnPart}): a placement's
 * height is picked in the settings' {@link ScatterSettings.ColumnHeight} from its own hash domain of (seed, x, z),
 * then capped by the room above its anchor: the cells up from the anchor that its medium's cell rule accepts, in no
 * earlier placement, below the build limit and not half of a two-block plant (for kelp: still water, up to the
 * surface). Room below the range's minimum rejects it ({@link Outcome#WATER} when still water ran out under water,
 * else {@link Outcome#COLLISION}); otherwise its footprint is a column of that height ({@link BlockVariants#column}),
 * checked like any other.
 *
 * <p><b>Work budget.</b> Acceptance counts deterministic work units: one per candidate considered, one per chunk
 * whose loading it checks, and one per cell it tests or marks (a column's room, occupancy, support, world cells, and
 * the footprint of each accepted placement); a growth adds {@value #GROWTH_WORK} per cell it read or wrote. Once the
 * count reaches {@code maxWork}, acceptance stops before the next
 * candidate and every remaining candidate ends as {@link Outcome#WORK_LIMIT} (the count overshoots by at most one
 * candidate's work, about three times its footprint). So a pathological request (huge footprints that all collide
 * late) is refused in bounded time instead of stalling.
 *
 * <p><b>Running it.</b> Use {@link #plan} for one pass. Or create a planner, {@link #survey} tiles (for example chunk
 * by chunk over several ticks; overlaps are ignored), then start the {@link #acceptance()} and {@link Acceptance#step
 * step} it over several ticks, or call {@link #finish()}. Any survey partition and any step sizes give the same
 * plan, <b>provided the world does not change</b> between the first survey and the end of acceptance (the planner
 * reads it throughout; a server that spreads the work over ticks should lock or re-plan on change). A planner is
 * single-use and not thread-safe.
 *
 * <p><b>Ordering is lazy.</b> The survey files each candidate into one of {@code 2^}{@value #BUCKET_BITS} buckets by
 * the top bits of its unsigned rank; acceptance sorts a bucket by (unsigned rank, x, z) only when it reaches it. The
 * order is exactly a full sort's (buckets partition the rank range in order), and no call sorts more than one
 * bucket per bucket it enters, so starting and stepping acceptance stay bounded. The buckets do not fill evenly: the
 * density prefilter keeps only ranks below {@code density × 2⁶⁴}, so a density fraction fills just the lowest
 * {@code density × 4096} buckets, about {@code candidates / (density × 4096)} each (the kept ranks are spread evenly
 * below the cut because splitmix64 mixes the coordinates); a target count keeps every rank and fills all 4096.
 *
 * <p>An optional {@link ColumnGuard} (the requester's protection) rejects candidates whose footprint reaches a
 * denied column as {@link Outcome#PROTECTED}. An optional {@link FootprintCache} shares footprints between planners.
 *
 * <p><b>Trees and features</b> ({@link Features}). A tree or feature
 * source has no footprint: when one of its candidates is reached in acceptance, the {@link Grower} grows it on the spot
 * (every random choice from the candidate's own hash domain of (seed, x, z), and reading the cells the growths accepted
 * before it write, so trees grow around each other as in world generation) and its cells, air included, are its
 * footprint: checked for chunks, the guard and collisions with held and block placements (see
 * {@link Acceptance#decideGrown}). Growths whose cells overlap form one cluster, committed together. Such placements
 * are never transformed, and the settings' support and survival rules do not apply to them (a grower decides where a
 * tree may stand). Growing is deterministic like the rest, given the world.
 */
public final class ScatterPlanner {
    /** The most present source cells a planner takes, summed over its sources. */
    public static final long MAX_SOURCE_CELLS = 1L << 21;
    /** Candidates are bucketed by this many top bits of their rank. */
    static final int BUCKET_BITS = 12;
    /**
     * The default acceptance work budget: 2²⁵ (about 33.5M) work units, sixteen times the default 2M-cell op volume,
     * and roughly a second of acceptance on a server.
     */
    public static final long DEFAULT_MAX_WORK = 1L << 25;

    /**
     * Which world columns a placement may write, for example the requester's protection (spawn protection, claims,
     * the world border). A candidate with a footprint cell in a denied column is rejected as
     * {@link Outcome#PROTECTED}. Called on the planner's thread during acceptance.
     */
    @FunctionalInterface
    public interface ColumnGuard {
        ColumnGuard ALLOW_ALL = (x, z) -> true;

        boolean allows(int x, int z);
    }

    /**
     * Which cells a placement may write: the requester's global mask, judged against the world as the planner reads
     * it. A placement, tree or feature with any cell it writes denied is rejected whole as {@link Outcome#MASKED}, as
     * a protected column is, so the plan (and its preview) never holds a part of one. Called on the planner's thread
     * during acceptance; set with {@link #cellGuard} before acceptance starts.
     */
    @FunctionalInterface
    public interface CellGuard {
        CellGuard ALLOW_ALL = (x, y, z) -> true;

        boolean allows(int x, int y, int z);
    }

    /**
     * Whether the variant from source {@code source}, turned by {@code transform}, could stay in the game with its
     * anchor at (x, y, z): a server asks vanilla's {@code canPlaceAt} about a block variant's (lower) block and passes
     * every other source. Asked last, only when {@link ScatterSettings.Fit#survive}. It may read the world around the
     * anchor (the anchor's chunk is loaded). Called on the planner's thread.
     */
    @FunctionalInterface
    public interface SurvivalCheck {
        SurvivalCheck ALWAYS = (source, transform, x, y, z) -> null;

        /**
         * {@code null} if it may stand there; else why not: {@link Outcome#SURVIVAL}, or {@link Outcome#UNLOADED}
         * when that cannot be told without a chunk that is not loaded (any other outcome counts as SURVIVAL).
         */
        Outcome check(int source, Transform transform, int x, int y, int z);

        /**
         * The check for a placement {@code height} cells tall (a column plant's column; 1 for anything else), which
         * the planner asks. By default the anchor's cell alone; a server asks every cell of a column, each standing on
         * the column's cell below it.
         */
        default Outcome check(int source, Transform transform, int x, int y, int z, int height) {
            return check(source, transform, x, y, z);
        }
    }

    /**
     * The cells earlier accepted growths write, as a grower reads them before the world: a cell's latest grown state,
     * or -1 when no accepted growth writes it.
     */
    @FunctionalInterface
    public interface GrownView {
        GrownView NONE = (x, y, z) -> -1;

        int get(int x, int y, int z);
    }

    /**
     * Grows the vanilla trees and features of {@link ScatterSource.Feature} sources. Called on the planner's thread, once per candidate that reaches it, in acceptance order.
     */
    @FunctionalInterface
    public interface Grower {
        /**
         * Grows source {@code source}'s feature on the spot whose anchor (the cell above the surface) is (x, y, z),
         * with every random choice drawn from {@code seed}. It reads the world through {@code grown} first (so a tree
         * grows around the trees accepted before it, as in world generation) and never changes the world.
         */
        Growth grow(int source, int x, int y, int z, long seed, GrownView grown);
    }

    /**
     * What one growth gave: its cells, or why there are none ({@link Outcome#FEATURE_FAILED}: the feature did not grow
     * there; {@link Outcome#SURVIVAL}: its sapling could not stand there; {@link Outcome#UNLOADED}: it reached a chunk
     * that is not loaded); and its work units (cells read and written), which acceptance counts.
     */
    public record Growth(GrownFeature grown, Outcome failure, long work) {
        public Growth {
            if ((grown == null) == (failure == null)) throw new IllegalArgumentException("Grown cells or a failure");
            if (work < 0) throw new IllegalArgumentException("Negative work");
        }

        public static Growth grown(GrownFeature grown, long work) {
            return new Growth(Objects.requireNonNull(grown), null, work);
        }

        public static Growth failed(Outcome why, long work) {
            return new Growth(null, Objects.requireNonNull(why), work);
        }
    }

    /**
     * A planner's trees and features: per source its catalog entry ({@code null} for a held or block source), who grows
     * them, and the most cells they may grow in one plan, all spots together.
     */
    public static final class Features {
        /** No source is a tree or feature. */
        public static final Features NONE = new Features(List.of(), (source, x, y, z, seed, grown) -> {
            throw new IllegalStateException("No trees or features to grow");
        }, 0);

        private final FeatureCatalog.FeatureDef[] defs;
        private final Grower grower;
        private final long maxCells;
        private final long maxClusterCells;

        /** @param defs per source index, its catalog entry or {@code null}; may hold nulls */
        public Features(List<FeatureCatalog.FeatureDef> defs, Grower grower, long maxCells) {
            this(defs, grower, maxCells, MAX_CLUSTER_CELLS);
        }

        /** With another cluster cap than {@link #MAX_CLUSTER_CELLS} (tests). */
        public Features(List<FeatureCatalog.FeatureDef> defs, Grower grower, long maxCells, long maxClusterCells) {
            this.defs = defs.toArray(new FeatureCatalog.FeatureDef[0]);
            this.grower = Objects.requireNonNull(grower);
            if (maxCells < 0 || maxClusterCells < 1) throw new IllegalArgumentException("Invalid grown cell caps");
            this.maxCells = maxCells;
            this.maxClusterCells = maxClusterCells;
        }

        /** The most cells one cluster may hold ({@link #MAX_CLUSTER_CELLS} by default). */
        public long maxClusterCells() {
            return maxClusterCells;
        }

        /** Source {@code source}'s catalog entry, or {@code null}. */
        public FeatureCatalog.FeatureDef def(int source) {
            return source < defs.length ? defs[source] : null;
        }

        public Grower grower() {
            return grower;
        }

        public long maxCells() {
            return maxCells;
        }

        int size() {
            return defs.length;
        }
    }

    /**
     * Thrown by acceptance when the plan's trees and features would grow more than {@link Features#maxCells} cells: the
     * preview is refused as too large (server setting {@code scatter.maxFeatureCells}).
     */
    public static final class GrownCellsExceeded extends RuntimeException {
        private final long cap;

        GrownCellsExceeded(long cap) {
            super("The trees and features would grow more than " + cap + " blocks");
            this.cap = cap;
        }

        public long cap() {
            return cap;
        }
    }

    private static final long K1 = 0x9E3779B97F4A7C15L;
    private static final long K2 = 0xC2B2AE3D27D4EB4FL;
    private static final long VARIANT_DOMAIN = 0x632BE59BD9B4E019L;
    private static final long TRANSFORM_DOMAIN = 0x8CB92BA72F3D8DD7L;
    private static final long HEIGHT_DOMAIN = 0x4F1BBCDCBFA53E0BL;
    private static final long FEATURE_DOMAIN = 0x2545F4914F6CDD1DL;
    /**
     * Work units per cell a growth reads or writes: vanilla feature code costs several times a planner cell check, so a
     * server spreading acceptance over ticks by work units ({@link Acceptance#step}) grows about one tree per step.
     */
    static final long GROWTH_WORK = 8;
    /**
     * The most cells (per growth, overlaps counted twice) growths joined into one cluster may hold: a growth that would
     * join a larger one is refused as {@link Outcome#COLLISION}. A cluster commits all or nothing, so this bounds what one
     * cell built on since the preview can take away (about 200 big oaks).
     */
    public static final long MAX_CLUSTER_CELLS = 1L << 16;
    /** Footprint cells must stay inside the section key range. */
    private static final long MIN_XZ = -(1L << 25), MAX_XZ = (1L << 25) - 1;
    private static final int UNKNOWN = Integer.MAX_VALUE;
    private static final int FLUID = StateFlags.FLUID_BLOCK | StateFlags.WATERLOGGED;

    private final ScatterSettings settings;
    private final List<Clipboard> sources;
    private final WorldReader world;
    private final StateSpace states;
    private final long maxCells;
    private final long maxWork;
    private final AreaMask area;
    private final int worldBottom, worldTop;
    private final Footprint[] footprints;
    /** Per source: where its placements go ({@link Medium#LAND} for held sources). */
    private final Medium[] media;
    /** Per variant: its source's medium. */
    private final Medium[] variantMedia;
    /** Per source: a column plant's top state, or -1. */
    private final int[] columnTop;
    /**
     * Per source: whether its placements may replace cells holding a fluid (the fit's {@code allowInFluid}, for held
     * sources and underwater block variants only: a land or water-surface block variant never goes into a fluid).
     */
    private final boolean[] fluids;
    /** Per source: the commit rule its placements follow. */
    private final List<MultiPaste.Replace> rules;
    private final ScatterSettings.ColumnHeight columnHeight;
    /** Taller columns built so far, by {@link ScatterPlan#columnKey}. */
    private final Map<Integer, Clipboard> columnClipboards = new HashMap<>();
    private final Map<Integer, Footprint> columnFootprints = new HashMap<>();
    private final List<List<Transform>> variantTransforms;
    private final int[] cumulativeWeights;
    private final int totalWeight;
    /** A column is kept when {@code rank >>> 11} is below this (2⁵³ keeps all). */
    private final long densityThreshold;
    private final ScatterSettings.Filters filters;
    private final CellPredicate substrate;
    /** {@code null} when the extra mask accepts everything. */
    private final ColumnFilter extra;
    private final boolean needsSlope;
    private final boolean allowInFluid;
    private final boolean survive;
    private final double minSupportFraction;
    private final long variantSeed, transformSeed, heightSeed, featureSeed;
    private final ColumnGuard guard;
    private CellGuard cellGuard = CellGuard.ALLOW_ALL;
    private final SurvivalCheck survival;
    private final Features features;

    private final BitSet surveyed = new BitSet();
    private long surveyedColumns;
    private final long[] counts = new long[Outcome.values().length];
    private long[] candidateRank = new long[1024];
    private int[] candidateX = new int[1024], candidateY = new int[1024], candidateZ = new int[1024];
    private int[] candidateVariant = new int[1024];
    private int candidates;
    /** Candidate indices by the top {@link #bucketBits} bits of their unsigned rank; created with the first one. */
    private IntArrayList[] buckets;
    private int bucketBits = BUCKET_BITS;
    /** Candidates sorted so far, over all buckets (tests: a step sorts only the buckets it enters). */
    long sortedCandidates;
    /** Set once acceptance starts: no more surveys. */
    private Acceptance acceptance;

    /** A planner with the {@link #DEFAULT_MAX_WORK default work budget} and no column guard. */
    public ScatterPlanner(ScatterSettings settings, List<Clipboard> sources, WorldReader world, long maxCells) {
        this(settings, sources, world, maxCells, DEFAULT_MAX_WORK);
    }

    /** A planner without a column guard. */
    public ScatterPlanner(ScatterSettings settings, List<Clipboard> sources, WorldReader world, long maxCells,
                          long maxWork) {
        this(settings, sources, world, maxCells, maxWork, ColumnGuard.ALLOW_ALL);
    }

    /** A planner computing its own footprints. */
    public ScatterPlanner(ScatterSettings settings, List<Clipboard> sources, WorldReader world, long maxCells,
                          long maxWork, ColumnGuard guard) {
        this(settings, sources, world, maxCells, maxWork, guard, null);
    }

    /**
     * @param sources the variant contents, in the world's state space; {@link ScatterSettings.Variant#source()}
     *     indexes this list
     * @param maxCells the cell budget: the sum of the accepted footprints never exceeds it
     * @param maxWork the acceptance work budget (see the class comment)
     * @param guard the columns placements may write ({@link ColumnGuard#ALLOW_ALL} for any)
     * @param footprintCache where to find and keep the sources' footprints, or {@code null}
     * @throws IllegalArgumentException if a variant names a missing source, a source has no blocks or a state
     *     outside the world's state space, the sources hold more than {@value #MAX_SOURCE_CELLS} cells, a mask
     *     names an unknown state, a box area lies outside the build height, or a budget is negative
     */
    public ScatterPlanner(ScatterSettings settings, List<Clipboard> sources, WorldReader world, long maxCells,
                          long maxWork, ColumnGuard guard, FootprintCache footprintCache) {
        this(settings, sources, world, maxCells, maxWork, guard, footprintCache, SurvivalCheck.ALWAYS);
    }

    /**
     * As {@link #ScatterPlanner(ScatterSettings, List, WorldReader, long, long, ColumnGuard, FootprintCache)}, with
     * the check that block variants could stay where they are placed (asked only with
     * {@link ScatterSettings.Fit#survive}). Every source goes on land.
     */
    public ScatterPlanner(ScatterSettings settings, List<Clipboard> sources, WorldReader world, long maxCells,
                          long maxWork, ColumnGuard guard, FootprintCache footprintCache, SurvivalCheck survival) {
        this(settings, sources, world, maxCells, maxWork, guard, footprintCache, survival, null);
    }

    /**
     * As {@link #ScatterPlanner(ScatterSettings, List, WorldReader, long, long, ColumnGuard, FootprintCache,
     * SurvivalCheck)}, knowing which sources are block variants: those go where their {@link BlockVariants#medium}
     * says, and column plants grow to the settings' {@link ScatterSettings.ColumnHeight}.
     *
     * @param blockStates {@code null} when no source is a block variant; else per source index its block state (as
     *     {@link BlockVariants#resolve} gives it; the source must be its {@link BlockVariants#clipboard}), or -1 for a
     *     clipboard or asset
     * @throws IllegalArgumentException also for a block state list of the wrong length, or a state that is outside the
     *     state space or not a placeable block variant
     */
    public ScatterPlanner(ScatterSettings settings, List<Clipboard> sources, WorldReader world, long maxCells,
                          long maxWork, ColumnGuard guard, FootprintCache footprintCache, SurvivalCheck survival,
                          int[] blockStates) {
        this(settings, sources, world, maxCells, maxWork, guard, footprintCache, survival, blockStates, Features.NONE);
    }

    /**
     * As {@link #ScatterPlanner(ScatterSettings, List, WorldReader, long, long, ColumnGuard, FootprintCache,
     * SurvivalCheck, int[])}, with trees and features: a source with a catalog entry in {@code features} is grown at
     * each of its spots by the grower instead of pasted (its entry in {@code sources} is a placeholder and is never
     * written; its block state is -1). See the class comment.
     *
     * @throws IllegalArgumentException also when {@code features} lists entries for another number of sources
     */
    public ScatterPlanner(ScatterSettings settings, List<Clipboard> sources, WorldReader world, long maxCells,
                          long maxWork, ColumnGuard guard, FootprintCache footprintCache, SurvivalCheck survival,
                          int[] blockStates, Features features) {
        this.features = Objects.requireNonNull(features);
        if (features != Features.NONE && features.size() != sources.size()) {
            throw new IllegalArgumentException("One feature entry (or null) per scatter source");
        }
        this.survival = Objects.requireNonNull(survival);
        this.guard = Objects.requireNonNull(guard);
        this.settings = Objects.requireNonNull(settings);
        this.sources = List.copyOf(sources);
        this.world = Objects.requireNonNull(world);
        this.states = Objects.requireNonNull(world.states());
        if (maxCells < 0) throw new IllegalArgumentException("Negative cell budget");
        if (maxWork < 0) throw new IllegalArgumentException("Negative work budget");
        this.maxCells = maxCells;
        this.maxWork = maxWork;
        if (world.topYExclusive() <= world.bottomY()) throw new IllegalArgumentException("Empty build height");
        this.worldBottom = world.bottomY();
        this.worldTop = world.topYExclusive() - 1;
        this.area = AreaMask.of(settings.area(), worldBottom, world.topYExclusive());

        long sourceCells = 0;
        for (int s = 0; s < this.sources.size(); s++) {
            if (features.def(s) == null) sourceCells += this.sources.get(s).cellCount();
        }
        if (sourceCells > MAX_SOURCE_CELLS) {
            throw new IllegalArgumentException("Scatter sources hold " + sourceCells + " cells, more than " + MAX_SOURCE_CELLS);
        }
        List<ScatterSettings.Variant> variants = settings.variants();
        for (ScatterSettings.Variant variant : variants) {
            if (variant.source() >= this.sources.size()) {
                throw new IllegalArgumentException("Variant source " + variant.source() + " is not in the source list");
            }
        }
        this.footprints = new Footprint[this.sources.size()];
        for (ScatterSettings.Variant variant : variants) {
            int source = variant.source();
            if (this.footprints[source] != null || features.def(source) != null) continue;
            Clipboard clipboard = this.sources.get(source);
            this.footprints[source] = footprintCache == null ? Footprint.of(clipboard, states)
                    : footprintCache.get(clipboard, states);
        }
        media = new Medium[this.sources.size()];
        Arrays.fill(media, Medium.LAND);
        columnTop = new int[this.sources.size()];
        Arrays.fill(columnTop, -1);
        if (blockStates != null) {
            if (blockStates.length != this.sources.size()) {
                throw new IllegalArgumentException("One block state (or -1) per scatter source");
            }
            for (int s = 0; s < blockStates.length; s++) {
                int state = blockStates[s];
                if (state < 0) continue;
                if (features.def(s) != null) throw new IllegalArgumentException("A tree or feature is not a block");
                if (state >= states.size()) {
                    throw new IllegalArgumentException("Block variant state " + state + " is outside the state space");
                }
                BlockVariants.checkPlaceable(states, state);
                media[s] = BlockVariants.medium(states, state);
                if (BlockVariants.isColumn(states, state)) columnTop[s] = BlockVariants.columnTop(states, state);
            }
        }
        columnHeight = settings.columnHeight();
        for (int s = 0; s < columnTop.length; s++) {
            // A column plant whose cells below the top cannot be placed fails here, not in acceptance.
            if (columnTop[s] >= 0 && columnHeight.max() > 1) BlockVariants.column(states, columnTop[s], 2);
        }
        variantMedia = new Medium[variants.size()];
        variantTransforms = new ArrayList<>(variants.size());
        cumulativeWeights = new int[variants.size()];
        int total = 0;
        for (int v = 0; v < variants.size(); v++) {
            variantMedia[v] = media[variants.get(v).source()];
            variantTransforms.add(settings.transforms().forVariant(variants.get(v)));
            total += variants.get(v).weight();
            cumulativeWeights[v] = total;
        }
        totalWeight = total;
        densityThreshold = settings.density() instanceof ScatterSettings.Density.Fraction fraction
                ? (long) (fraction.value() * 0x1p53) : 1L << 53;

        filters = settings.filters();
        try {
            substrate = filters.substrate().bind(states);
            extra = filters.extra() instanceof SurfaceMask.Any ? null : ColumnFilter.compile(filters.extra(), states);
        } catch (IndexOutOfBoundsException unknownState) {
            throw new IllegalArgumentException("Scatter filter names a state outside the state space", unknownState);
        }
        needsSlope = filters.needsSlope();
        allowInFluid = settings.fit().allowInFluid();
        survive = settings.fit().survive();
        minSupportFraction = settings.fit().minSupportFraction();
        variantSeed = SplitMix64.mix(settings.seed() ^ VARIANT_DOMAIN);
        transformSeed = SplitMix64.mix(settings.seed() ^ TRANSFORM_DOMAIN);
        heightSeed = SplitMix64.mix(settings.seed() ^ HEIGHT_DOMAIN);
        featureSeed = SplitMix64.mix(settings.seed() ^ FEATURE_DOMAIN);
        fluids = new boolean[this.sources.size()];
        List<MultiPaste.Replace> sourceRules = new ArrayList<>(this.sources.size());
        for (int s = 0; s < fluids.length; s++) {
            boolean block = blockStates != null && blockStates[s] >= 0;
            boolean underwater = media[s] == Medium.UNDERWATER;
            fluids[s] = allowInFluid && (!block || underwater);
            sourceRules.add(underwater
                    ? (fluids[s] ? MultiPaste.Replace.WATER_OR_WET_PLANT : MultiPaste.Replace.WATER)
                    : (fluids[s] ? MultiPaste.Replace.OPEN_OR_FLUID : MultiPaste.Replace.OPEN));
        }
        rules = List.copyOf(sourceRules);
    }

    /** Plans in one pass with the default work budget: {@code new ScatterPlanner(...).finish()}. */
    public static ScatterPlan plan(ScatterSettings settings, List<Clipboard> sources, WorldReader world, long maxCells) {
        return new ScatterPlanner(settings, sources, world, maxCells).finish();
    }

    /** Plans in one pass: {@code new ScatterPlanner(...).finish()}. */
    public static ScatterPlan plan(ScatterSettings settings, List<Clipboard> sources, WorldReader world, long maxCells,
                                   long maxWork) {
        return new ScatterPlanner(settings, sources, world, maxCells, maxWork).finish();
    }

    /** The area's bounding rectangle and surface scan window (x, z and y inclusive). */
    public Box areaBounds() {
        return new Box(new BlockPos(area.x0, area.yBottom, area.z0), new BlockPos(area.x1, area.yTop, area.z1));
    }

    /** The number of columns in the area. */
    public long columns() {
        return area.columns;
    }

    /** Where source {@code source}'s placements go. */
    public Medium medium(int source) {
        return media[source];
    }

    /**
     * Surveys the area columns in the rectangle {@code [minX, maxX] × [minZ, maxZ]} that were not surveyed yet.
     * Reads the world: surfaces of those columns, and of their cardinal neighbours when a filter needs the slope.
     */
    public void survey(int minX, int minZ, int maxX, int maxZ) {
        if (acceptance != null) throw new IllegalStateException("Acceptance already started");
        if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("Empty survey rectangle");
        int x0 = Math.max(minX, area.x0), x1 = Math.min(maxX, area.x1);
        int z0 = Math.max(minZ, area.z0), z1 = Math.min(maxZ, area.z1);
        if (x0 > x1 || z0 > z1) return;
        new Survey(x0, z0, x1, z1).run();
    }

    /** The candidates found so far. */
    public int candidateCount() {
        return candidates;
    }

    /**
     * Sets the cells placements may write ({@link CellGuard}; {@link CellGuard#ALLOW_ALL} by default). Before
     * acceptance only.
     *
     * @return this planner
     */
    public ScatterPlanner cellGuard(CellGuard guard) {
        Objects.requireNonNull(guard);
        if (acceptance != null) throw new IllegalStateException("Acceptance has started");
        this.cellGuard = guard;
        return this;
    }

    /**
     * Starts acceptance: surveys the columns not surveyed yet (none, when the caller surveyed every tile). Ordering
     * is left to the steps (see the class comment). Call once; no survey may follow.
     */
    public Acceptance acceptance() {
        if (acceptance != null) throw new IllegalStateException("Acceptance already started");
        if (surveyedColumns < area.columns) survey(area.x0, area.z0, area.x1, area.z1);
        acceptance = new Acceptance();
        return acceptance;
    }

    /** Tests: buckets candidates by {@code bits} top rank bits instead (0: one bucket, a full sort). */
    void bucketBits(int bits) {
        if (bits < 0 || bits > 16) throw new IllegalArgumentException("0-16 bucket bits");
        if (buckets != null) throw new IllegalStateException("Candidates are already bucketed");
        bucketBits = bits;
    }

    /** Surveys the remaining columns, accepts every candidate in one go and returns the plan. Call once. */
    public ScatterPlan finish() {
        Acceptance run = acceptance();
        while (!run.step(Long.MAX_VALUE)) {
            // Each step handles at least one candidate.
        }
        return run.plan();
    }

    // ---------------------------------------------------------------- survey

    /**
     * One rectangle's columns, with lazily filled surface grids (ground, and still water surfaces when a variant goes
     * on water) covering it and a one-column ring.
     */
    private final class Survey {
        private final int x0, z0, x1, z1;
        private final int gx0, gz0, gd;
        private final int[] height;
        private final int[] surface;
        /** Still water surfaces and their states; created on first use. */
        private int[] waterHeight, waterState;
        private final int[] found = new int[2];
        private int chunkX, chunkZ;
        private boolean chunkLoaded, chunkKnown;

        Survey(int x0, int z0, int x1, int z1) {
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            gx0 = x0 - 1;
            gz0 = z0 - 1;
            gd = z1 - z0 + 3;
            int cells = (x1 - x0 + 3) * gd;
            height = new int[cells];
            surface = new int[cells];
            Arrays.fill(height, UNKNOWN);
        }

        void run() {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (!area.contains(x, z)) continue;
                    int bit = area.index(x, z);
                    if (surveyed.get(bit)) continue;
                    surveyed.set(bit);
                    surveyedColumns++;
                    column(x, z);
                }
            }
        }

        private void column(int x, int z) {
            long rank = rank(settings.seed(), x, z);
            if ((rank >>> 11) >= densityThreshold) {
                reject(Outcome.DENSITY);
                return;
            }
            if (!loaded(x, z)) {
                reject(Outcome.UNLOADED);
                return;
            }
            int variant = pickVariant(x, z);
            Medium medium = variantMedia[variant];
            boolean onWater = medium == Medium.WATER_SURFACE;
            int i = grid(x, z);
            int h = onWater ? waterHeight(x, z, i) : height(x, z, i);
            if (h == SurfaceScan.NONE) {
                reject(onWater ? Outcome.WATER : Outcome.NO_SURFACE);
                return;
            }
            if (medium == Medium.UNDERWATER && (h >= worldTop || !Water.holdsSource(states, world.get(x, h + 1, z)))) {
                reject(Outcome.WATER);
                return;
            }
            if (h < filters.minY() || h > filters.maxY()) {
                reject(Outcome.ELEVATION);
                return;
            }
            int slope = 0;
            if (needsSlope) {
                slope = Math.max(Math.max(step(x, z - 1, h, onWater), step(x, z + 1, h, onWater)),
                        Math.max(step(x - 1, z, h, onWater), step(x + 1, z, h, onWater)));
                if (slope < filters.minSlope() || slope > filters.maxSlope()) {
                    reject(Outcome.SLOPE);
                    return;
                }
            }
            int state = onWater ? waterState[i] : surface[i];
            if (!substrate.test(x, h, z, state)) {
                reject(Outcome.SUBSTRATE);
                return;
            }
            if (extra != null && !extra.test(x, h, z, state, slope)) {
                reject(Outcome.FILTER);
                return;
            }
            addCandidate(rank, x, h + 1, z, variant);
        }

        /** The height step to a neighbour's surface of the same kind; 0 when it has none (as in the brushes). */
        private int step(int x, int z, int h, boolean onWater) {
            int i = grid(x, z);
            int other = onWater ? waterHeight(x, z, i) : height(x, z, i);
            return other == SurfaceScan.NONE ? 0 : Math.abs(other - h);
        }

        private int height(int x, int z, int i) {
            int h = height[i];
            if (h != UNKNOWN) return h;
            h = SurfaceScan.NONE;
            if (loaded(x, z)) {
                h = SurfaceScan.scan(world, states, x, z, area.yTop, area.yBottom, found);
                if (h != SurfaceScan.NONE) surface[i] = found[0];
            }
            height[i] = h;
            return h;
        }

        private int waterHeight(int x, int z, int i) {
            if (waterHeight == null) {
                waterHeight = new int[height.length];
                waterState = new int[height.length];
                Arrays.fill(waterHeight, UNKNOWN);
            }
            int h = waterHeight[i];
            if (h != UNKNOWN) return h;
            h = loaded(x, z) ? waterSurface(x, z, i) : SurfaceScan.NONE;
            waterHeight[i] = h;
            return h;
        }

        /**
         * The y of column (x, z)'s still water surface within the scan window: scanning down from the window's top
         * (or the reader's height hint, as {@link SurfaceScan#scan} does) through open cells that hold no fluid, the
         * first cell that holds one, if it holds still water ({@link Water#holdsSource}). {@link SurfaceScan#NONE}
         * when ground or a structure comes first, the first fluid is flowing water or not water, or the window's top
         * cell already holds a fluid (the surface is above the window).
         */
        private int waterSurface(int x, int z, int i) {
            int yTop = area.yTop, yBottom = area.yBottom;
            int start = Math.max(yBottom, Math.min(yTop, world.heightHint(x, z)));
            if (start < yTop && !StateFlags.has(states.flags(world.get(x, start, z)), StateFlags.AIR)) start = yTop;
            for (int y = start; y >= yBottom; y--) {
                int state = world.get(x, y, z);
                int flags = states.flags(state);
                if (StateFlags.has(flags, StateFlags.WATER) || states.fluidSource(state) >= 0) {
                    if (y == start || !Water.holdsSource(states, state)) return SurfaceScan.NONE;
                    waterState[i] = state;
                    return y;
                }
                if (!SurfaceScan.open(flags)) return SurfaceScan.NONE;
            }
            return SurfaceScan.NONE;
        }

        private boolean loaded(int x, int z) {
            int cx = x >> 4, cz = z >> 4;
            if (!chunkKnown || cx != chunkX || cz != chunkZ) {
                chunkX = cx;
                chunkZ = cz;
                chunkLoaded = world.isLoaded(cx, cz);
                chunkKnown = true;
            }
            return chunkLoaded;
        }

        private int grid(int x, int z) {
            return (x - gx0) * gd + (z - gz0);
        }
    }

    private void reject(Outcome outcome) {
        counts[outcome.ordinal()]++;
    }

    private void addCandidate(long rank, int x, int y, int z, int variant) {
        if (candidates == candidateRank.length) {
            int size = candidates * 2;
            candidateRank = Arrays.copyOf(candidateRank, size);
            candidateX = Arrays.copyOf(candidateX, size);
            candidateY = Arrays.copyOf(candidateY, size);
            candidateZ = Arrays.copyOf(candidateZ, size);
            candidateVariant = Arrays.copyOf(candidateVariant, size);
        }
        candidateRank[candidates] = rank;
        candidateX[candidates] = x;
        candidateY[candidates] = y;
        candidateZ[candidates] = z;
        candidateVariant[candidates] = variant;
        if (buckets == null) buckets = new IntArrayList[1 << bucketBits];
        int b = bucketBits == 0 ? 0 : (int) (rank >>> (64 - bucketBits));
        IntArrayList bucket = buckets[b];
        if (bucket == null) {
            bucket = new IntArrayList(4);
            buckets[b] = bucket;
        }
        bucket.add(candidates);
        candidates++;
    }

    /** Sorts candidate indices by (unsigned rank, x, z); columns are unique, so the order is total. */
    private void sortBucket(int[] indices, int size) {
        long[] rank = candidateRank;
        int[] xs = candidateX, zs = candidateZ;
        IntArrays.quickSort(indices, 0, size, (a, b) -> {
            int c = Long.compareUnsigned(rank[a], rank[b]);
            if (c != 0) return c;
            c = Integer.compare(xs[a], xs[b]);
            return c != 0 ? c : Integer.compare(zs[a], zs[b]);
        });
        sortedCandidates += size;
    }

    // ---------------------------------------------------------------- acceptance

    /**
     * Acceptance, resumable in {@link #step steps} so a server can spread it over ticks. Candidates are decided in
     * (rank, x, z) order and the work count is cumulative, so the plan does not depend on the step sizes.
     */
    public final class Acceptance {
        private final int limit = settings.countLimit();
        private final SpatialGrid spacing = settings.spacing() > 0 ? new SpatialGrid(settings.spacing()) : null;
        private final Occupancy occupancy = new Occupancy();
        private final List<ScatterPlan.Placement> placements = new ArrayList<>();
        /** The taller columns accepted placements use, by {@link ScatterPlan#columnKey}. */
        private final Map<Integer, Clipboard> usedColumns = new HashMap<>();
        private int next;
        private long work;
        private long total;
        private long bx0 = Long.MAX_VALUE, by0 = Long.MAX_VALUE, bz0 = Long.MAX_VALUE;
        private long bx1 = Long.MIN_VALUE, by1 = Long.MIN_VALUE, bz1 = Long.MIN_VALUE;
        private ScatterPlan plan;
        /** The bucket being decided (sorted), and the position in it. */
        private int bucket = -1;
        private int[] current;
        private int currentSize;
        private int position;
        /** Why the last {@link #columnRoom} stopped. */
        private Outcome roomRefusal;
        /** Cells of held and block placements (the footprints); grown cells are counted per cluster by the plan. */
        private long footprintCells;
        /** Accepted growths, in acceptance order, and per growth its placement index and union-find parent. */
        private final List<GrownFeature> growths = new ArrayList<>();
        private final IntArrayList growthPlacement = new IntArrayList();
        private int[] parent = new int[16];
        /** Per cluster root, the cells of its growths together (overlaps counted per growth). */
        private long[] clusterCells = new long[16];
        private long grownTotal;
        /** Per grown cell ({@link GrownFeature#pack}): the latest growth writing it {@code << 32 | its state}. */
        private final Long2LongOpenHashMap grownCells = new Long2LongOpenHashMap();
        private final GrownView grownView = (x, y, z) -> {
            if (y < -GrownFeature.MAX_Y || y >= GrownFeature.MAX_Y) return -1;
            long value = grownCells.get(GrownFeature.pack(x, y, z));
            return value < 0 ? -1 : (int) value;
        };

        private Acceptance() {
            grownCells.defaultReturnValue(-1);
        }

        /** The next undecided candidate in (unsigned rank, x, z) order; sorts each bucket as it is entered. */
        private int nextCandidate() {
            while (current == null || position == currentSize) {
                bucket++;
                IntArrayList list = buckets[bucket];
                if (list == null || list.isEmpty()) {
                    current = null;
                    continue;
                }
                current = list.elements();
                currentSize = list.size();
                position = 0;
                sortBucket(current, currentSize);
            }
            return current[position++];
        }

        /**
         * Decides candidates until this call has spent at least {@code workUnits} work units (always at least one
         * candidate) or none are left.
         *
         * @return whether acceptance is done ({@link #plan()} is then available)
         */
        public boolean step(long workUnits) {
            if (workUnits < 1) throw new IllegalArgumentException("A step needs a positive work allowance");
            long start = work;
            while (plan == null && work - start < workUnits) decideNext();
            return plan != null;
        }

        public boolean isDone() {
            return plan != null;
        }

        /** Work units spent so far. */
        public long work() {
            return work;
        }

        /** The candidates not decided yet. */
        public int remaining() {
            return plan != null ? 0 : candidates - next;
        }

        /** The finished plan. */
        public ScatterPlan plan() {
            if (plan == null) throw new IllegalStateException("Acceptance is not done");
            return plan;
        }

        private void decideNext() {
            if (next < candidates && placements.size() >= limit) {
                counts[Outcome.COUNT_LIMIT.ordinal()] += candidates - next;
                next = candidates;
            }
            if (next < candidates && work >= maxWork) {
                counts[Outcome.WORK_LIMIT.ordinal()] += candidates - next;
                next = candidates;
            }
            if (next == candidates) {
                Box bounds = placements.isEmpty() ? null : new Box(new BlockPos((int) bx0, (int) by0, (int) bz0),
                        new BlockPos((int) bx1, (int) by1, (int) bz1));
                plan = new ScatterPlan(settings, sources, List.of(media), rules, usedColumns, placements, counts,
                        area.columns, candidates, footprintCells, bounds, grownParts());
                return;
            }
            int c = nextCandidate();
            next++;
            work++;
            int x = candidateX[c], y = candidateY[c], z = candidateZ[c];
            if (spacing != null && spacing.tooClose(x, z)) {
                reject(Outcome.SPACING);
                return;
            }
            int variant = candidateVariant[c];
            int source = settings.variants().get(variant).source();
            if (features.def(source) != null) {
                decideGrown(x, y, z, variant, source);
                return;
            }
            Transform transform = pickTransform(variant, x, z);
            Medium medium = media[source];
            int height = 1;
            Footprint footprint = footprints[source];
            if (columnTop[source] >= 0 && columnHeight.max() > 1) {
                height = columnRoom(medium, fluids[source], x, y, z, pickHeight(x, z));
                if (height < columnHeight.min()) {
                    reject(roomRefusal);
                    return;
                }
                if (height > 1) footprint = columnFootprint(source, height);
            }
            if (footprint.cells > maxCells - total) {
                reject(Outcome.BUDGET);
                return;
            }
            Footprint.Oriented o = footprint.orient(transform);
            Outcome outcome = fit(footprint, o, x, y, z, source, transform, medium, height);
            if (outcome != null) {
                reject(outcome);
                return;
            }
            for (int i = 0; i < footprint.cells; i++) {
                int rx = footprint.rx[i], rz = footprint.rz[i];
                occupancy.set(x + o.dx(rx, rz), y + footprint.ry[i], z + o.dz(rx, rz));
            }
            work += footprint.cells;
            if (spacing != null) spacing.add(x, z);
            total += footprint.cells;
            footprintCells += footprint.cells;
            if (height > 1) {
                int key = ScatterPlan.columnKey(source, height);
                usedColumns.put(key, columnClipboards.get(key));
            }
            placements.add(new ScatterPlan.Placement(new BlockPos(x, y, z), variant, transform, height));
            bx0 = Math.min(bx0, (long) x + o.minDx());
            bx1 = Math.max(bx1, (long) x + o.maxDx());
            by0 = Math.min(by0, (long) y + footprint.minRy);
            by1 = Math.max(by1, (long) y + footprint.maxRy);
            bz0 = Math.min(bz0, (long) z + o.minDz());
            bz1 = Math.max(bz1, (long) z + o.maxDz());
        }

        /**
         * A tree or feature candidate: its anchor's chunk must be loaded; then it is grown (seeded by its column,
         * reading the growths accepted so far before the world) and refused as the growth says, or when its cells would
         * take the plan past its cell budget ({@link Outcome#BUDGET}), reach a chunk that is not loaded, a column the
         * guard denies ({@link Outcome#PROTECTED}), a cell of a held or block placement or one holding a block entity,
         * or would make its cluster larger than {@link #MAX_CLUSTER_CELLS} ({@link Outcome#COLLISION}); past
         * {@link Features#maxCells} the whole plan is refused ({@link GrownCellsExceeded}). Cells it
         * shares with earlier growths are fine: it grew around them, so the two are committed together as one cluster.
         * Never transformed.
         */
        private void decideGrown(int x, int y, int z, int variant, int source) {
            work++;
            if (!world.isLoaded(x >> 4, z >> 4)) {
                reject(Outcome.UNLOADED);
                return;
            }
            Growth growth = features.grower().grow(source, x, y, z, rank(featureSeed, x, z), grownView);
            Objects.requireNonNull(growth, "growth");
            work += growth.work() * GROWTH_WORK;
            if (growth.failure() != null) {
                reject(growth.failure());
                return;
            }
            GrownFeature grown = growth.grown();
            int n = grown.size();
            if (n > maxCells - total) {
                reject(Outcome.BUDGET);
                return;
            }
            long lastChunk = Long.MIN_VALUE;
            int lastX = 0, lastZ = 0;
            boolean checked = false;
            for (int i = 0; i < n; i++) {
                int cx = grown.x(i), cz = grown.z(i);
                if (checked && cx == lastX && cz == lastZ) continue;
                checked = true;
                lastX = cx;
                lastZ = cz;
                long chunk = ((long) (cx >> 4) << 32) ^ ((cz >> 4) & 0xFFFFFFFFL);
                if (chunk != lastChunk) {
                    work++;
                    if (!world.isLoaded(cx >> 4, cz >> 4)) {
                        reject(Outcome.UNLOADED);
                        return;
                    }
                    lastChunk = chunk;
                }
                if (guard != ColumnGuard.ALLOW_ALL) {
                    work++;
                    if (!guard.allows(cx, cz)) {
                        reject(Outcome.PROTECTED);
                        return;
                    }
                }
            }
            if (cellGuard != CellGuard.ALLOW_ALL) {
                // Every cell the growth writes (air included) must pass the mask, or the tree is left out whole.
                for (int i = 0; i < n; i++) {
                    work++;
                    if (!cellGuard.allows(grown.x(i), grown.y(i), grown.z(i))) {
                        reject(Outcome.MASKED);
                        return;
                    }
                }
            }
            IntArrayList joins = new IntArrayList();
            for (int i = 0; i < n; i++) {
                work++;
                if (!occupancy.occupied(grown.x(i), grown.y(i), grown.z(i))) continue;
                long owner = grownCells.get(grown.cell(i));
                if (owner < 0) {
                    reject(Outcome.COLLISION); // a held or block placement's cell
                    return;
                }
                int root = find((int) (owner >>> 32));
                if (!joins.contains(root)) joins.add(root);
            }
            // Never over a block entity the world holds (a chest a boulder or a mushroom cap would take).
            for (int i = 0; i < n; i++) {
                if (StateFlags.has(states.flags(grown.before(i)), StateFlags.HAS_BLOCK_ENTITY)) {
                    reject(Outcome.COLLISION);
                    return;
                }
            }
            long clustered = n;
            for (int i = 0; i < joins.size(); i++) clustered += clusterCells[joins.getInt(i)];
            if (clustered > features.maxClusterCells()) {
                reject(Outcome.COLLISION); // it would join trees into a cluster too large to commit as one
                return;
            }
            if (grownTotal + n > features.maxCells()) throw new GrownCellsExceeded(features.maxCells());
            int ordinal = growths.size();
            growths.add(grown);
            growthPlacement.add(placements.size());
            if (ordinal == parent.length) {
                parent = Arrays.copyOf(parent, ordinal * 2);
                clusterCells = Arrays.copyOf(clusterCells, ordinal * 2);
            }
            parent[ordinal] = ordinal;
            for (int i = 0; i < joins.size(); i++) union(ordinal, joins.getInt(i));
            clusterCells[find(ordinal)] = clustered;
            for (int i = 0; i < n; i++) {
                occupancy.set(grown.x(i), grown.y(i), grown.z(i));
                grownCells.put(grown.cell(i), ((long) ordinal << 32) | grown.after(i));
            }
            work += n;
            total += n;
            grownTotal += n;
            if (spacing != null) spacing.add(x, z);
            placements.add(new ScatterPlan.Placement(new BlockPos(x, y, z), variant, Transform.IDENTITY));
            Box b = grown.bounds();
            bx0 = Math.min(bx0, b.min().x());
            by0 = Math.min(by0, b.min().y());
            bz0 = Math.min(bz0, b.min().z());
            bx1 = Math.max(bx1, b.max().x());
            by1 = Math.max(by1, b.max().y());
            bz1 = Math.max(bz1, b.max().z());
        }

        private int find(int a) {
            while (parent[a] != a) {
                parent[a] = parent[parent[a]];
                a = parent[a];
            }
            return a;
        }

        /** Joins two growths' clusters under the older root (so a cluster's root is its first growth). */
        private void union(int a, int b) {
            int ra = find(a), rb = find(b);
            if (ra == rb) return;
            if (ra < rb) {
                parent[rb] = ra;
            } else {
                parent[ra] = rb;
            }
        }

        private ScatterPlan.Grown grownParts() {
            int n = growths.size();
            int[] cluster = new int[n];
            for (int i = 0; i < n; i++) cluster[i] = find(i);
            return new ScatterPlan.Grown(growths, growthPlacement.toIntArray(), cluster);
        }

        /**
         * How many cells a column plant's column may take from anchor (x, y, z) up, at most {@code wanted}: those its
         * medium's cell rule accepts, in no earlier placement, below the build limit and not half of a two-block plant.
         * Sets {@link #roomRefusal} to why it stopped short: {@link Outcome#UNLOADED}, {@link Outcome#WATER} (a cell
         * without still water under water) or {@link Outcome#COLLISION}. A work unit per chunk check and cell.
         */
        private int columnRoom(Medium medium, boolean fluid, int x, int y, int z, int wanted) {
            work++;
            if (!world.isLoaded(x >> 4, z >> 4)) {
                roomRefusal = Outcome.UNLOADED;
                return 0;
            }
            roomRefusal = Outcome.COLLISION;
            int room = 0;
            while (room < wanted) {
                int cy = y + room;
                if (cy > worldTop) break;
                work++;
                if (occupancy.occupied(x, cy, z)) break;
                int state = world.get(x, cy, z);
                Outcome refused = cellRefusal(medium, fluid, state);
                if (refused != null) {
                    roomRefusal = refused;
                    break;
                }
                if (StateFlags.has(states.flags(state), StateFlags.DOUBLE_TALL)) break;
                room++;
            }
            return room;
        }

        /**
         * Why the footprint cannot go at anchor (x, y, z), or {@code null} if it fits. Counts one work unit per
         * chunk checked and per cell tested, as it goes, so an early exit costs only what it did.
         */
        private Outcome fit(Footprint footprint, Footprint.Oriented o, int x, int y, int z, int source,
                            Transform transform, Medium medium, int height) {
            long minX = (long) x + o.minDx(), maxX = (long) x + o.maxDx();
            long minZ = (long) z + o.minDz(), maxZ = (long) z + o.maxDz();
            long minY = (long) y + footprint.minRy, maxY = (long) y + footprint.maxRy;
            if (minY < worldBottom || maxY > worldTop || minX < MIN_XZ || maxX > MAX_XZ || minZ < MIN_XZ || maxZ > MAX_XZ) {
                return Outcome.COLLISION;
            }
            // The anchor column is read for support even when the footprint's chunks do not include it.
            work++;
            if (!world.isLoaded(x >> 4, z >> 4)) return Outcome.UNLOADED;
            for (long cx = minX >> 4; cx <= maxX >> 4; cx++) {
                for (long cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                    work++;
                    if (!world.isLoaded((int) cx, (int) cz)) return Outcome.UNLOADED;
                }
            }
            int[] rxs = footprint.rx, rys = footprint.ry, rzs = footprint.rz;
            if (guard != ColumnGuard.ALLOW_ALL) {
                // One test per run of cells in the same column (a work unit each).
                int lastX = 0, lastZ = 0;
                boolean checked = false;
                for (int i = 0; i < footprint.cells; i++) {
                    int cx = x + o.dx(rxs[i], rzs[i]), cz = z + o.dz(rxs[i], rzs[i]);
                    if (checked && cx == lastX && cz == lastZ) continue;
                    work++;
                    if (!guard.allows(cx, cz)) return Outcome.PROTECTED;
                    lastX = cx;
                    lastZ = cz;
                    checked = true;
                }
            }
            for (int i = 0; i < footprint.cells; i++) {
                int rx = rxs[i], rz = rzs[i];
                work++;
                if (occupancy.occupied(x + o.dx(rx, rz), y + rys[i], z + o.dz(rx, rz))) return Outcome.COLLISION;
            }
            // Support: ground (still water, for a variant that goes on water) below the base's level in the anchor
            // column, and under enough base cells (contacts).
            boolean onWater = medium == Medium.WATER_SURFACE;
            int below = y + footprint.minRy - 1;
            work++;
            if (below < worldBottom || !supportAt(onWater, x, below, z)) {
                return onWater ? Outcome.WATER : Outcome.SUPPORT;
            }
            int supported = 0;
            for (int i = 0; i < footprint.contacts; i++) {
                int rx = rxs[i], rz = rzs[i];
                work++;
                if (supportAt(onWater, x + o.dx(rx, rz), below, z + o.dz(rx, rz))) supported++;
            }
            if (supported < minSupportFraction * footprint.contacts) return Outcome.SUPPORT;
            for (int i = 0; i < footprint.cells; i++) {
                int rx = rxs[i], rz = rzs[i];
                work++;
                int state = world.get(x + o.dx(rx, rz), y + rys[i], z + o.dz(rx, rz));
                Outcome refused = cellRefusal(medium, fluids[source], state);
                if (refused != null) return refused;
                // Half of a two-block plant only goes with its other half (the same column, one up or down), or
                // that half would be left standing alone.
                int flags = states.flags(state);
                if (StateFlags.has(flags, StateFlags.DOUBLE_TALL)) {
                    int other = rys[i] + (StateFlags.has(flags, StateFlags.LOWER_HALF) ? 1 : -1);
                    if (!footprint.covers(rx, other, rz)) return Outcome.COLLISION;
                }
            }
            if (cellGuard != CellGuard.ALLOW_ALL) {
                for (int i = 0; i < footprint.cells; i++) {
                    int rx = rxs[i], rz = rzs[i];
                    work++;
                    if (!cellGuard.allows(x + o.dx(rx, rz), y + rys[i], z + o.dz(rx, rz))) return Outcome.MASKED;
                }
            }
            if (survive && survival != SurvivalCheck.ALWAYS) {
                work++;
                Outcome refused = survival.check(source, transform, x, y, z, height);
                if (refused != null) return refused == Outcome.UNLOADED ? Outcome.UNLOADED : Outcome.SURVIVAL;
            }
            return null;
        }
    }

    private boolean supportAt(boolean onWater, int x, int y, int z) {
        int state = world.get(x, y, z);
        return onWater ? Water.holdsSource(states, state) : SurfaceScan.ground(states.flags(state));
    }

    /**
     * Why a world cell holding {@code state} may not take a cell of a placement in {@code medium} (from a source whose
     * placements may replace fluids when {@code fluid}), or {@code null} when it may. Under water: {@link Outcome#WATER}
     * unless the cell holds still water, and {@link Outcome#COLLISION} unless that is the water source block (with
     * fluids allowed, any open cell holding still water: seagrass, kelp). Elsewhere: {@link Outcome#COLLISION} unless
     * {@link #replaceable}, and unless fluids are allowed, for any other cell that holds a fluid.
     */
    private Outcome cellRefusal(Medium medium, boolean fluid, int state) {
        int flags = states.flags(state);
        if (medium == Medium.UNDERWATER) {
            if (!Water.holdsSource(states, state)) return Outcome.WATER;
            boolean open = Water.isSourceBlock(states, state) || (fluid && SurfaceScan.open(flags));
            return open ? null : Outcome.COLLISION;
        }
        if (!replaceable(flags, fluid) || (!fluid && states.fluidSource(state) >= 0)) {
            return Outcome.COLLISION;
        }
        return null;
    }

    /** The column of {@code height} (at least 2) cells of column plant source {@code source}, built once. */
    private Footprint columnFootprint(int source, int height) {
        int key = ScatterPlan.columnKey(source, height);
        Footprint footprint = columnFootprints.get(key);
        if (footprint == null) {
            Clipboard column = BlockVariants.column(states, columnTop[source], height);
            columnClipboards.put(key, column);
            footprint = Footprint.of(column, states);
            columnFootprints.put(key, footprint);
        }
        return footprint;
    }

    /**
     * Whether a placement may overwrite a world cell with these flags: {@link SurfaceScan#open open} (air,
     * replaceable, vegetation, fluid, without a block entity), and not a fluid block or waterlogged unless
     * {@code allowInFluid}. The planner also refuses, unless {@code allowInFluid}, any other cell that holds a fluid
     * ({@code StateSpace.fluidSource}: seagrass, kelp, bubble columns, which have no fluid flag), so no dry cell is
     * left under water.
     */
    static boolean replaceable(int flags, boolean allowInFluid) {
        return SurfaceScan.open(flags) && (allowInFluid || (flags & FLUID) == 0);
    }

    private int pickVariant(int x, int z) {
        if (cumulativeWeights.length == 1) return 0;
        long pick = Long.remainderUnsigned(rank(variantSeed, x, z), totalWeight);
        int variant = 0;
        while (pick >= cumulativeWeights[variant]) variant++;
        return variant;
    }

    private Transform pickTransform(int variant, int x, int z) {
        List<Transform> allowed = variantTransforms.get(variant);
        if (allowed.size() == 1) return allowed.get(0);
        return allowed.get((int) Long.remainderUnsigned(rank(transformSeed, x, z), allowed.size()));
    }

    /** A column plant's wanted height at (x, z): uniform in the settings' column height, from its own hash domain. */
    private int pickHeight(int x, int z) {
        int span = columnHeight.max() - columnHeight.min() + 1;
        if (span == 1) return columnHeight.min();
        return columnHeight.min() + (int) Long.remainderUnsigned(rank(heightSeed, x, z), span);
    }

    /**
     * {@code splitmix64(seed ^ (x·K1 + z·K2))}; other hash domains pass a derived seed.
     *
     * <p>The sum, not the {@code x·K1 ^ z·K2} (see the class comment). The sum is injective
     * over the world: the shortest non-zero (dx, dz) with {@code dx·K1 + dz·K2 ≡ 0 (mod 2⁶⁴)} is
     * (1346530022, 1795967550), longer than any difference of two columns within ±2²⁵, and {@code splitmix64} is a
     * bijection. So distinct columns never share a rank.
     */
    static long rank(long seed, int x, int z) {
        return SplitMix64.mix(seed ^ ((long) x * K1 + (long) z * K2));
    }
}
