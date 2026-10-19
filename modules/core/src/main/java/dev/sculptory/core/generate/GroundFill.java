package dev.sculptory.core.generate;

import dev.sculptory.core.brush.SurfaceScan;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Extend to Ground (Generate's Line and Sweep; Smart Surface's columns): every column a generated volume occupies is
 * filled from its lowest cell down to the terrain below it, so walls, supports and ridges come out solid.
 *
 * <p><b>The ground rule</b>, kept here and nowhere else: look straight down from the shape and stop at the first
 * terrain cell, looking through what {@link #lookedThrough} accepts: the cells the brushes look through
 * ({@link SurfaceScan#open}: air, replaceable cells, plants, leaves and other vegetation, water and lava) and tree
 * trunks ({@link SurfaceScan#trunk}: logs and wood, which Overlay and Naturalize look past too). Over a forest the fill
 * goes through the canopy and the trunks to the ground under them, and the trees in its way are replaced as any cell
 * the host's paste writes (unless the host's paste keeps existing blocks); a shape under an overhang fills down to the
 * ground beneath it, since only what lies under the shape counts. Anything else stops the look: terrain, a build's
 * blocks (planks, bricks, stairs, fences) and any block with a block entity.
 *
 * <p>Three steps, so only the second reads the world (on the world's thread) and the others may run anywhere:
 * {@link #lowest} finds each column's lowest cell, {@link #groundUnder} looks down each column from there for the
 * terrain ({@link #groundBelow} per column), and {@link #extend} writes the cells between. A column with no terrain
 * under it within the build height ({@link SurfaceReader#NONE}), or in a chunk not loaded
 * ({@link SurfaceReader#UNLOADED}), gets no fill. The volume with its fill is capped as any generated source is
 * ({@link GeneratedTooLargeException}).
 *
 * <p>A host with columns of its own (Smart Surface) asks {@link #groundBelow} for one column, or {@link #groundUnder}
 * with its own column-to-lowest-y map ({@link GroundMap#column} keys) and budget, and fills as it likes. A Straight
 * road's fill below ({@link RoadKernel#deckGround}) is not Extend to Ground: it stops on a trunk.
 */
public final class GroundFill {
    private GroundFill() {}

    /** The state a fill cell at (x, y, z) takes, given its column's state (its lowest cell's, or see {@link #extend}). */
    @FunctionalInterface
    public interface FillState {
        int state(int x, int y, int z, int columnState);
    }

    /**
     * How a host fills: each column's state is its lowest cell {@code carried} accepts ({@link #extend}), each fill cell's
     * from {@code state}.
     */
    public record Fill(IntPredicate carried, FillState state) {
        public Fill {
            Objects.requireNonNull(carried);
            Objects.requireNonNull(state);
        }

        /** Every fill cell from {@code state}, the column's state being its lowest cell's. */
        public static Fill of(FillState state) {
            return new Fill(s -> true, state);
        }
    }

    /** Each column's lowest cell of {@code source}: column key ({@link GroundMap#column}) to its y. */
    public static Long2IntMap lowest(GeneratedSource source) {
        Objects.requireNonNull(source);
        Long2IntOpenHashMap lowest = new Long2IntOpenHashMap();
        lowest.defaultReturnValue(Integer.MAX_VALUE);
        source.forEach((x, y, z, state) -> {
            long column = GroundMap.column(x, z);
            if (y < lowest.get(column)) lowest.put(column, y);
        });
        return lowest;
    }

    /**
     * Whether Extend to Ground looks down through a cell of {@code state} for the terrain under it: an open cell
     * ({@link SurfaceScan#open}) or a tree trunk without a block entity ({@link SurfaceScan#trunk}).
     */
    public static boolean lookedThrough(StateSpace states, int state) {
        int flags = states.flags(state);
        if (SurfaceScan.open(flags)) return true;
        return !StateFlags.has(flags, StateFlags.HAS_BLOCK_ENTITY) && SurfaceScan.trunk(states, state);
    }

    /**
     * The ground rule for one column: the y of the first cell under {@code fromY} in column (x, z) that is not
     * {@linkplain #lookedThrough looked through} (the terrain a fill from {@code fromY - 1} down reaches to), looking
     * down to the world's floor; {@link SurfaceReader#NONE} when there is none, {@link SurfaceReader#UNLOADED} for a
     * chunk {@code world} does not hold. A {@code fromY} above the build height starts at its top. Read on the world's
     * thread. One column is read to the floor at most; a host looking under many columns bounds the total itself, as
     * {@link #groundUnder} does with its budget.
     */
    public static int groundBelow(WorldReader world, int x, int z, int fromY) {
        return groundBelow(world, x, z, fromY, Long.MAX_VALUE);
    }

    /** {@link #groundBelow} looking through at most {@code limit} cells, else {@link #TOO_DEEP}. */
    private static int groundBelow(WorldReader world, int x, int z, int fromY, long limit) {
        Objects.requireNonNull(world);
        if (!world.isLoaded(x >> 4, z >> 4)) return SurfaceReader.UNLOADED;
        StateSpace states = world.states();
        long through = 0;
        for (int y = Math.min(fromY, world.topYExclusive()) - 1; y >= world.bottomY(); y--) {
            if (!lookedThrough(states, world.get(x, y, z))) return y;
            if (++through > limit) return TOO_DEEP;
        }
        return SurfaceReader.NONE;
    }

    /** {@link #groundBelow}'s answer for a column it gave up on: more cells to look through than it was allowed. */
    private static final int TOO_DEEP = Integer.MIN_VALUE + 2;

    /**
     * Per column of {@code lowest}, the y of the terrain under its lowest cell ({@link #groundBelow}: the first cell that
     * is not {@linkplain #lookedThrough looked through}, which the fill reaches down to), looking down to the world's
     * floor: {@link SurfaceReader#NONE} when there is none, {@link SurfaceReader#UNLOADED} for a chunk {@code world} does
     * not hold. Read on the world's thread.
     */
    public static GroundMap groundUnder(Long2IntMap lowest, WorldReader world) {
        return groundUnder(lowest, world, Long.MAX_VALUE);
    }

    /**
     * {@link #groundUnder(Long2IntMap, WorldReader)}, stopping (null) as soon as the cells looked through pass
     * {@code budget} (what the cap still leaves room for). Every cell looked through counts: under a column that finds
     * ground they are the cells its fill takes (a fill that deep would be refused anyway), and a column with no ground
     * under it (over the void, or the End's) counts the cells down to the world's floor, so the reads on the world's
     * thread stay within the budget (plus one ground cell per column) whatever lies below.
     */
    public static GroundMap groundUnder(Long2IntMap lowest, WorldReader world, long budget) {
        Objects.requireNonNull(lowest);
        Objects.requireNonNull(world);
        GroundMap ground = new GroundMap();
        int top = world.topYExclusive();
        long looked = 0;
        for (Long2IntMap.Entry entry : lowest.long2IntEntrySet()) {
            long column = entry.getLongKey();
            int x = GroundMap.columnX(column), z = GroundMap.columnZ(column);
            int from = Math.min(entry.getIntValue(), top);
            int found = groundBelow(world, x, z, from, budget - looked);
            if (found == TOO_DEEP) return null;
            // The cells looked through: down to the ground, or to the floor; none in a chunk not loaded.
            if (SurfaceReader.known(found)) {
                looked += from - 1 - found;
            } else if (found == SurfaceReader.NONE) {
                looked += Math.max(0, from - world.bottomY());
            }
            ground.put(x, z, found);
        }
        return ground;
    }

    /**
     * {@code source} with every column filled from just under its lowest cell down to just above its ground
     * ({@code ground}, as {@link #groundUnder} gives it; a column with unknown ground stays as it is), the fill's states
     * from {@code fill}. Cells already in {@code source} stay. No cell goes below {@code bottomY}.
     *
     * @throws GeneratedTooLargeException when the volume and its fill pass {@code maxCells}
     */
    public static GeneratedSource extend(GeneratedSource source, Long2IntMap lowest, SurfaceReader ground, FillState fill,
                                         long maxCells, int bottomY) {
        return extend(source, lowest, ground, state -> true, fill, maxCells, bottomY);
    }

    /**
     * {@link #extend(GeneratedSource, Long2IntMap, SurfaceReader, FillState, long, int)} with each column's state for
     * {@code fill} the lowest of its cells, from its lowest up, that {@code carried} accepts (a full cube, so stairs,
     * chains or a lantern at the bottom are not carried down), or -1 when none does; a column whose {@code fill} gives
     * -1 stays as it is.
     */
    public static GeneratedSource extend(GeneratedSource source, Long2IntMap lowest, SurfaceReader ground,
                                         IntPredicate carried, FillState fill, long maxCells, int bottomY) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(lowest);
        Objects.requireNonNull(ground);
        Objects.requireNonNull(carried);
        Objects.requireNonNull(fill);
        if (source.isEmpty()) return source;
        Long2IntOpenHashMap carry = new Long2IntOpenHashMap();
        long added = 0;
        for (Long2IntMap.Entry entry : lowest.long2IntEntrySet()) {
            int x = GroundMap.columnX(entry.getLongKey()), z = GroundMap.columnZ(entry.getLongKey());
            int under = ground.ground(x, z);
            int low = entry.getIntValue();
            if (!SurfaceReader.known(under) || low - 1 <= Math.max(under, bottomY - 1)) continue;
            int state = -1;
            for (int y = low; source.contains(x, y, z); y++) {
                if (carried.test(source.get(x, y, z))) {
                    state = source.get(x, y, z);
                    break;
                }
            }
            if (fill.state(x, low - 1, z, state) < 0) continue;
            carry.put(entry.getLongKey(), state);
            added += low - Math.max(under + 1, bottomY);
        }
        if (added == 0) return source;
        if (source.cells() + added > maxCells) throw new GeneratedTooLargeException(maxCells);
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        source.forEach(out::set);
        for (Long2IntMap.Entry entry : carry.long2IntEntrySet()) {
            int x = GroundMap.columnX(entry.getLongKey()), z = GroundMap.columnZ(entry.getLongKey());
            int under = ground.ground(x, z);
            int low = lowest.get(entry.getLongKey());
            for (int y = low - 1; y > under && y >= bottomY; y--) out.set(x, y, z, fill.state(x, y, z, entry.getIntValue()));
        }
        return out.build();
    }
}
