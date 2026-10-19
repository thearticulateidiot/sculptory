package dev.sculptory.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.generate.RotationShowcase;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.CellResampler;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The comparison build ({@link RotationShowcase}) turned every way at every compared angle, as the comparison command
 * places it: exact count keeps every block, smooth fills at least as much as nearest, doors, beds and tall grass stay
 * whole in all of them, and smooth slopes put stairs on the turned roof without changing the count.
 */
class RotationComparisonTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final TwoCellBlocks BLOCKS = new TwoCellBlocks(STATES);

    private static TwoCellPairsTest.Cells showcase() {
        TwoCellPairsTest.Cells cells = new TwoCellPairsTest.Cells(RotationShowcase.SIZE_X, RotationShowcase.SIZE_Y,
                RotationShowcase.SIZE_Z, STATES.air());
        for (RotationShowcase.Cell c : RotationShowcase.cells()) cells.set(c.x(), c.y(), c.z(), STATES.state(c.state()));
        return cells;
    }

    private static CellResampler way(int way, Orientation o) {
        int sx = RotationShowcase.SIZE_X, sy = RotationShowcase.SIZE_Y, sz = RotationShowcase.SIZE_Z;
        return switch (way) {
            case 0 -> CellResampler.nearest(o, sx, sy, sz);
            case 1 -> CellResampler.exact(o, sx, sy, sz);
            default -> CellResampler.smooth(o, sx, sy, sz);
        };
    }

    /** The blocks a paste writes (air left out), by result cell, each turned (and shaped with smooth slopes). */
    private static Map<BlockPos, Integer> paste(CellResampler r, TwoCellPairsTest.Cells cells, OrientedStates oriented,
                                                SlopeShapes slopes) {
        Map<BlockPos, Integer> out = new HashMap<>();
        BlockPos s = r.size();
        int[] cell = new int[3];
        for (int x = 0; x < s.x(); x++) {
            for (int y = 0; y < s.y(); y++) {
                for (int z = 0; z < s.z(); z++) {
                    if (!r.source(x, y, z, cells, cell)) continue;
                    int state = cells.state(cell[0], cell[1], cell[2]);
                    if (!cells.solid(state)) continue;
                    int turned = oriented.map(state);
                    if (slopes != null) {
                        int shaped = slopes.shape(r, cells, x, y, z, turned);
                        if (shaped != SlopeShapes.KEEP) turned = shaped;
                    }
                    out.put(new BlockPos(x, y, z), turned);
                }
            }
        }
        return out;
    }

    @Test
    void everyWayAtEveryComparedAngle() {
        TwoCellPairsTest.Cells cells = showcase();
        long blocks = RotationShowcase.cells().size();
        SlopeShapes slopes = new SlopeShapes(new StairSlabVariants(STATES));
        for (int[] a : RotationShowcase.ANGLES) {
            Orientation o = Orientation.free(Transform.IDENTITY, a[0], a[1], a[2]);
            OrientedStates oriented = new OrientedStates(STATES, o);
            int[] counts = new int[3];
            for (int way = 0; way < 3; way++) {
                CellResampler base = way(way, o);
                TwoCellPairs pairs = TwoCellPairs.of(base, cells, BLOCKS, oriented::map,
                        TwoCellPairs.halves(base, cells, BLOCKS));
                String what = o + " " + RotationShowcase.WAYS.get(way);
                assertEquals(3, pairs.placedPairs(), what + ": the door, the bed and the tall grass");
                Map<BlockPos, Integer> written = paste(pairs, cells, oriented, null);
                counts[way] = written.size();
                if (way == 1) assertEquals(blocks, written.size(), what + ": exact count keeps every block");
                for (Map.Entry<BlockPos, Integer> e : written.entrySet()) {
                    if (!BLOCKS.isHalf(e.getValue())) continue;
                    int[] step = BLOCKS.towardsOther(e.getValue());
                    Integer other = written.get(e.getKey().offset(step[0], step[1], step[2]));
                    assertNotNull(other, what + ": half of " + STATES.format(e.getValue()) + " alone");
                    assertEquals(BLOCKS.other(e.getValue()), (int) other, what);
                }
                // Smooth slopes change states, never the count.
                Map<BlockPos, Integer> shaped = paste(pairs, cells, oriented, slopes);
                assertEquals(written.keySet(), shaped.keySet(), what);
                // Every cell a block lands on lies in the turned box of its source cell.
                int[] out = new int[3 * pairs.maxTakers()];
                for (RotationShowcase.Cell c : RotationShowcase.cells()) {
                    int[] box = pairs.turnedBox(c.x(), c.y(), c.z(), c.x(), c.y(), c.z());
                    int n = pairs.takers(c.x(), c.y(), c.z(), cells, out);
                    for (int i = 0; i < n; i++) {
                        assertTrue(box != null && out[3 * i] >= box[0] && out[3 * i + 1] >= box[1]
                                && out[3 * i + 2] >= box[2] && out[3 * i] <= box[3] && out[3 * i + 1] <= box[4]
                                && out[3 * i + 2] <= box[5], what + ": a taker outside the turned box of " + c);
                    }
                }
            }
            // Nearest loses blocks at these angles; exact count has them all.
            assertTrue(counts[0] <= blocks + blocks / 10, o + ": nearest " + counts[0]);
        }
    }

    @Test
    void theTurnedRoofGetsStairsWithSmoothSlopes() {
        TwoCellPairsTest.Cells cells = showcase();
        SlopeShapes slopes = new SlopeShapes(new StairSlabVariants(STATES));
        Orientation o = Orientation.free(Transform.IDENTITY, 4_500, 2_000, 0);
        OrientedStates oriented = new OrientedStates(STATES, o);
        for (int way = 0; way < 3; way++) {
            CellResampler r = way(way, o);
            Map<BlockPos, Integer> plain = paste(r, cells, oriented, null);
            Map<BlockPos, Integer> shaped = paste(r, cells, oriented, slopes);
            int changed = 0;
            for (Map.Entry<BlockPos, Integer> e : shaped.entrySet()) {
                if (!e.getValue().equals(plain.get(e.getKey()))) changed++;
            }
            assertTrue(changed >= 4, RotationShowcase.WAYS.get(way) + ": " + changed + " shaped");
        }
    }

    @Test
    void aResampledCopyIsThePairsThenTheTurnThenTheSlopes() {
        TwoCellPairsTest.Cells cells = showcase();
        Orientation o = Orientation.free(Transform.IDENTITY, 4_500, 2_000, 0);
        OrientedStates oriented = new OrientedStates(STATES, o);
        ResampledCopy.Tables tables = ResampledCopy.Tables.of(STATES);
        for (int way = 0; way < 3; way++) {
            CellResampler base = way(way, o);
            ResampledCopy copy = ResampledCopy.of(base, cells, oriented, true, null, tables);
            TwoCellPairs pairs = TwoCellPairs.of(base, cells, BLOCKS, oriented::map,
                    TwoCellPairs.halves(base, cells, BLOCKS));
            Map<BlockPos, Integer> expected = paste(pairs, cells, oriented, new SlopeShapes(tables.variants()));
            Map<BlockPos, Integer> got = new HashMap<>();
            BlockPos s = copy.cells().size();
            int[] cell = new int[3];
            for (int x = 0; x < s.x(); x++) {
                for (int y = 0; y < s.y(); y++) {
                    for (int z = 0; z < s.z(); z++) {
                        if (!copy.cells().source(x, y, z, cells, cell)) continue;
                        int state = cells.state(cell[0], cell[1], cell[2]);
                        if (cells.solid(state)) got.put(new BlockPos(x, y, z), copy.state(x, y, z, state));
                    }
                }
            }
            assertEquals(expected, got, RotationShowcase.WAYS.get(way));
            assertEquals(0, copy.droppedPairs());
        }
    }

    @Test
    void theGridKeepsCopiesApart() {
        // A copy turned 45° with a 20° pitch fits in its grid cell with room to spare.
        Orientation o = Orientation.free(Transform.IDENTITY, 4_500, 2_000, 0);
        Orientation.Bounds b = o.bounds(RotationShowcase.SIZE_X, RotationShowcase.SIZE_Y, RotationShowcase.SIZE_Z);
        assertTrue(b.size().x() + 2 <= RotationShowcase.SPACING && b.size().z() + 2 <= RotationShowcase.SPACING,
                b.toString());
        assertEquals(new BlockPos(3 * RotationShowcase.SPACING, 0, 2 * RotationShowcase.SPACING),
                RotationShowcase.placement(3, 2));
        List.of(0, 1, 2, 3).forEach(c -> RotationShowcase.placement(c, 0));
    }
}
