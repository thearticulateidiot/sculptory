package dev.sculptory.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.CellResampler;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Doors, beds and two-block plants stay whole under free orientations ({@link TwoCellPairs}): each pair lands from its
 * first cell with the other half beside it where the turned state says, or both halves are dropped and counted.
 */
class TwoCellPairsTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final TwoCellBlocks BLOCKS = new TwoCellBlocks(STATES);

    /** A source box of states, -1 where nothing is. */
    static final class Cells implements CellResampler.SourceCells {
        final int sx, sy, sz;
        final int[] states;

        Cells(int sx, int sy, int sz, int fill) {
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
            this.states = new int[sx * sy * sz];
            java.util.Arrays.fill(states, fill);
        }

        void set(int x, int y, int z, int state) {
            states[(y * sz + z) * sx + x] = state;
        }

        @Override
        public int state(int x, int y, int z) {
            return states[(y * sz + z) * sx + x];
        }

        @Override
        public boolean solid(int state) {
            return !StateFlags.has(STATES.flags(state), StateFlags.AIR);
        }
    }

    @Test
    void halvesAreKnownByTheirFlagsAndBedsByTheirPart() {
        int lower = STATES.state("minecraft:oak_door[facing=east,half=lower]");
        int upper = STATES.state("minecraft:oak_door[facing=east,half=upper]");
        assertTrue(BLOCKS.isHalf(lower) && BLOCKS.isFirst(lower));
        assertTrue(BLOCKS.isHalf(upper) && !BLOCKS.isFirst(upper));
        assertEquals(upper, BLOCKS.other(lower));
        assertEquals(lower, BLOCKS.other(upper));
        assertEquals(List.of(0, 1, 0), List.of(BLOCKS.towardsOther(lower)[0], BLOCKS.towardsOther(lower)[1],
                BLOCKS.towardsOther(lower)[2]));
        int foot = STATES.state("minecraft:red_bed[facing=west,part=foot]");
        int head = STATES.state("minecraft:red_bed[facing=west,part=head]");
        assertTrue(BLOCKS.isFirst(foot) && !BLOCKS.isFirst(head));
        assertEquals(-1, BLOCKS.towardsOther(foot)[0]);
        assertEquals(1, BLOCKS.towardsOther(head)[0]);
        assertEquals(head, BLOCKS.other(foot));
        // Stairs and a modded vertical slab have a half but are one cell.
        assertFalse(BLOCKS.isHalf(STATES.state("minecraft:oak_stairs[half=top]")));
        assertFalse(BLOCKS.isHalf(STATES.state("testmod:vertical_slab[half=upper]")));
        assertFalse(BLOCKS.isHalf(STATES.state("minecraft:stone")));
    }

    /** A 7x5x7 stone build holding a door, a tall grass and a bed, each whole. */
    private static Cells build() {
        Cells cells = new Cells(7, 5, 7, STATES.state("minecraft:stone"));
        cells.set(3, 1, 3, STATES.state("minecraft:oak_door[facing=north,half=lower]"));
        cells.set(3, 2, 3, STATES.state("minecraft:oak_door[facing=north,half=upper]"));
        cells.set(1, 0, 1, STATES.state("minecraft:tall_grass[half=lower]"));
        cells.set(1, 1, 1, STATES.state("minecraft:tall_grass[half=upper]"));
        cells.set(5, 3, 2, STATES.state("minecraft:red_bed[facing=south,part=foot]"));
        cells.set(5, 3, 3, STATES.state("minecraft:red_bed[facing=south,part=head]"));
        return cells;
    }

    @Test
    void everyPairLandsWholeOrIsDroppedWhole() {
        Cells cells = build();
        List<Orientation> orientations = List.of(Orientation.free(Transform.IDENTITY, 0, 3_000, 0),
                Orientation.free(Transform.IDENTITY, 3_000, 0, 0), Orientation.free(Transform.IDENTITY, 0, 0, 4_000),
                Orientation.free(Transform.IDENTITY, 2_000, 2_500, 1_000),
                Orientation.free(new Transform(1, dev.sculptory.core.transform.Mirror.X), 1_500, 0, 6_000));
        for (Orientation o : orientations) {
            OrientedStates oriented = new OrientedStates(STATES, o);
            for (CellResampler base : List.of(CellResampler.nearest(o, 7, 5, 7), CellResampler.exact(o, 7, 5, 7),
                    CellResampler.smooth(o, 7, 5, 7))) {
                TwoCellPairs pairs = TwoCellPairs.of(base, cells, BLOCKS, oriented::map,
                        TwoCellPairs.halves(base, cells, BLOCKS));
                String what = o + " " + base.getClass().getSimpleName();
                // Every pair has room in this build, whatever the way: none is lost.
                assertEquals(3, pairs.placedPairs(), what);
                assertEquals(0, pairs.droppedPairs(), what);
                Map<BlockPos, Integer> result = resample(pairs, cells, oriented);
                int halves = 0;
                for (Map.Entry<BlockPos, Integer> e : result.entrySet()) {
                    int state = e.getValue();
                    if (!BLOCKS.isHalf(state)) continue;
                    halves++;
                    int[] step = BLOCKS.towardsOther(state);
                    BlockPos other = e.getKey().offset(step[0], step[1], step[2]);
                    assertEquals(Integer.valueOf(BLOCKS.other(state)), result.get(other),
                            what + ": the other half of " + STATES.format(state) + " at " + e.getKey());
                }
                assertEquals(2 * pairs.placedPairs(), halves, what);
                // The door and the grass stay upright (they have no tipped form): the upper half is above the lower.
                for (Map.Entry<BlockPos, Integer> e : result.entrySet()) {
                    if (StateFlags.has(STATES.flags(e.getValue()), StateFlags.LOWER_HALF)) {
                        Integer above = result.get(e.getKey().offset(0, 1, 0));
                        assertNotNull(above, what);
                        assertTrue(StateFlags.has(STATES.flags(above), StateFlags.UPPER_HALF), what);
                    }
                }
            }
        }
    }

    private static Map<BlockPos, Integer> resample(CellResampler r, Cells cells, OrientedStates oriented) {
        Map<BlockPos, Integer> out = new HashMap<>();
        BlockPos s = r.size();
        int[] cell = new int[3];
        for (int x = 0; x < s.x(); x++) {
            for (int y = 0; y < s.y(); y++) {
                for (int z = 0; z < s.z(); z++) {
                    if (!r.source(x, y, z, cells, cell)) continue;
                    out.put(new BlockPos(x, y, z), oriented.map(cells.state(cell[0], cell[1], cell[2])));
                }
            }
        }
        return out;
    }

    @Test
    void aPairWithNoRoomForItsOtherHalfIsDroppedWholeAndCounted() {
        // A door alone, rolled onto its side: the box is one cell tall, so the upright upper half has nowhere to go.
        Cells cells = new Cells(1, 2, 1, -1);
        cells.set(0, 0, 0, STATES.state("minecraft:oak_door[half=lower]"));
        cells.set(0, 1, 0, STATES.state("minecraft:oak_door[half=upper]"));
        Orientation roll = Orientation.free(Transform.IDENTITY, 0, 0, Orientation.QUARTER_TURN);
        for (CellResampler base : List.of(CellResampler.nearest(roll, 1, 2, 1), CellResampler.exact(roll, 1, 2, 1),
                CellResampler.smooth(roll, 1, 2, 1))) {
            assertEquals(1, base.size().y());
            TwoCellPairs pairs = TwoCellPairs.of(base, cells, BLOCKS, new OrientedStates(STATES, roll)::map,
                    TwoCellPairs.halves(base, cells, BLOCKS));
            assertEquals(1, pairs.droppedPairs());
            assertEquals(0, pairs.placedPairs());
            assertTrue(resample(pairs, cells, new OrientedStates(STATES, roll)).isEmpty(), "both halves dropped");
            int[] out = new int[3 * pairs.maxTakers()];
            assertEquals(0, pairs.takers(0, 0, 0, cells, out));
            assertEquals(0, pairs.takers(0, 1, 0, cells, out));
        }
    }

    @Test
    void aLoneHalfIsAnOrdinaryCellAndTakersFollowThePairs() {
        Cells cells = new Cells(3, 3, 3, STATES.state("minecraft:stone"));
        cells.set(1, 2, 1, STATES.state("minecraft:oak_door[half=lower]"));
        Orientation o = Orientation.free(Transform.IDENTITY, 2_000, 0, 0);
        CellResampler base = CellResampler.nearest(o, 3, 3, 3);
        TwoCellPairs pairs = TwoCellPairs.of(base, cells, BLOCKS, new OrientedStates(STATES, o)::map,
                TwoCellPairs.halves(base, cells, BLOCKS));
        assertEquals(0, pairs.placedPairs() + pairs.droppedPairs());
        BlockPos s = base.size();
        int[] a = new int[3];
        int[] b = new int[3];
        for (int x = 0; x < s.x(); x++) {
            for (int y = 0; y < s.y(); y++) {
                for (int z = 0; z < s.z(); z++) {
                    assertEquals(base.source(x, y, z, cells, a), pairs.source(x, y, z, cells, b));
                }
            }
        }
        // With a whole pair, each source cell's takers are exactly the result cells that take it.
        Cells whole = build();
        Orientation pitch = Orientation.free(Transform.IDENTITY, 1_000, 3_000, 0);
        CellResampler near = CellResampler.nearest(pitch, 7, 5, 7);
        TwoCellPairs placed = TwoCellPairs.of(near, whole, BLOCKS, new OrientedStates(STATES, pitch)::map,
                TwoCellPairs.halves(near, whole, BLOCKS));
        Map<BlockPos, List<BlockPos>> takers = new HashMap<>();
        BlockPos rs = placed.size();
        for (int y = 0; y < rs.y(); y++) {
            for (int z = 0; z < rs.z(); z++) {
                for (int x = 0; x < rs.x(); x++) {
                    if (!placed.source(x, y, z, whole, a)) continue;
                    takers.computeIfAbsent(new BlockPos(a[0], a[1], a[2]), k -> new java.util.ArrayList<>())
                            .add(new BlockPos(x, y, z));
                }
            }
        }
        int[] out = new int[3 * placed.maxTakers()];
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 5; y++) {
                for (int z = 0; z < 7; z++) {
                    int n = placed.takers(x, y, z, whole, out);
                    java.util.List<BlockPos> got = new java.util.ArrayList<>();
                    for (int i = 0; i < n; i++) got.add(new BlockPos(out[3 * i], out[3 * i + 1], out[3 * i + 2]));
                    List<BlockPos> want = takers.getOrDefault(new BlockPos(x, y, z), List.of());
                    assertEquals(new java.util.HashSet<>(want), new java.util.HashSet<>(got), "cell " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void aMeadowOfTallGrassIsPairedQuicklyWhateverTheWay() {
        // 4,096 tall grass pairs over a dirt floor: placing them costs a few lookups each, not 125 smooth cells.
        Cells cells = new Cells(64, 3, 64, STATES.air());
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) {
                cells.set(x, 0, z, STATES.state("minecraft:dirt"));
                cells.set(x, 1, z, STATES.state("minecraft:tall_grass[half=lower]"));
                cells.set(x, 2, z, STATES.state("minecraft:tall_grass[half=upper]"));
            }
        }
        Orientation o = Orientation.free(Transform.IDENTITY, 3_000, 1_000, 0);
        for (CellResampler base : List.of(CellResampler.nearest(o, 64, 3, 64), CellResampler.exact(o, 64, 3, 64),
                CellResampler.smooth(o, 64, 3, 64))) {
            long started = System.nanoTime();
            TwoCellPairs pairs = TwoCellPairs.of(base, cells, BLOCKS, new OrientedStates(STATES, o)::map,
                    TwoCellPairs.halves(base, cells, BLOCKS));
            long nanos = System.nanoTime() - started;
            assertEquals(4_096, pairs.placedPairs() + pairs.droppedPairs());
            assertTrue(nanos < 3_000_000_000L, base.getClass().getSimpleName() + " took " + nanos / 1_000_000 + " ms");
        }
    }
}
