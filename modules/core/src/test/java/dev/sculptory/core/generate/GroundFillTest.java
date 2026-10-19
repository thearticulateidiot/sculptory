package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Extend to Ground: each column filled from its lowest cell down to the terrain below, looking through open cells and
 * tree trunks.
 */
class GroundFillTest {
    private static final long CAP = 1 << 20;
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int grass = states.state("minecraft:short_grass");
    private final int water = states.state("minecraft:water");

    /** Stone up to y 63 over x, z in -8..23. */
    private FakeWorld ground() {
        FakeWorld world = new FakeWorld(states);
        for (int x = -8; x < 24; x++) {
            for (int z = -8; z < 24; z++) {
                for (int y = 55; y <= 63; y++) world.set(x, y, z, stone);
            }
        }
        return world;
    }

    private GeneratedSource cells(int state, int... xyz) {
        GeneratedSource.Builder out = GeneratedSource.builder(CAP);
        for (int i = 0; i < xyz.length; i += 3) out.set(xyz[i], xyz[i + 1], xyz[i + 2], state);
        return out.build();
    }

    @Test
    void eachColumnFillsFromItsLowestCellDownToTheTerrainThroughOpenCells() {
        FakeWorld world = ground();
        world.set(0, 64, 0, grass); // a plant and water are looked through
        world.set(2, 64, 0, water);
        world.set(2, 65, 0, water);
        GeneratedSource bridge = cells(dirt, 0, 70, 0, 0, 71, 0, 2, 68, 0, 4, 63, 0);
        Long2IntMap lowest = GroundFill.lowest(bridge);
        assertEquals(70, lowest.get(GroundMap.column(0, 0)), "the column's lowest cell");
        GroundMap under = GroundFill.groundUnder(lowest, world);
        assertEquals(63, under.ground(0, 0));
        assertEquals(63, under.ground(2, 0));
        assertEquals(62, under.ground(4, 0), "a cell already in the terrain: the terrain under it");
        GeneratedSource filled = GroundFill.extend(bridge, lowest, under, (x, y, z, low) -> stone, CAP, world.bottomY());
        for (int y = 64; y <= 69; y++) assertEquals(stone, filled.get(0, y, 0), "filled at " + y);
        assertEquals(dirt, filled.get(0, 70, 0), "the volume's own cells stay");
        assertFalse(filled.contains(0, 63, 0), "the terrain itself is not written");
        for (int y = 64; y <= 67; y++) assertEquals(stone, filled.get(2, y, 0));
        assertEquals(bridge.cells() + 6 + 4, filled.cells(), "nothing under a cell already on the ground");
    }

    @Test
    void theFillTakesTheStateTheHostGivesItAndColumnsWithoutKnownGroundStayAsTheyAre() {
        FakeWorld world = ground();
        world.setLoaded(2, 0, false); // x 32-47
        GeneratedSource source = cells(dirt, 1, 66, 1, 40, 66, 1, 30, 66, 30);
        Long2IntMap lowest = GroundFill.lowest(source);
        GroundMap under = GroundFill.groundUnder(lowest, world);
        assertEquals(SurfaceReader.UNLOADED, under.ground(40, 1));
        assertEquals(SurfaceReader.NONE, under.ground(30, 30), "nothing below within the build height");
        GeneratedSource filled = GroundFill.extend(source, lowest, under, (x, y, z, low) -> low, CAP, world.bottomY());
        assertEquals(dirt, filled.get(1, 64, 1), "the lowest cell's state, carried down");
        assertEquals(source.cells() + 2, filled.cells());
        assertFalse(filled.contains(40, 65, 1));
        assertFalse(filled.contains(30, 65, 30));
    }

    @Test
    void aFillPastTheCapIsRefusedAndNoFillIsTheSameSource() {
        FakeWorld world = ground();
        GeneratedSource high = cells(dirt, 0, 200, 0, 1, 200, 0);
        Long2IntMap lowest = GroundFill.lowest(high);
        GroundMap under = GroundFill.groundUnder(lowest, world);
        assertThrows(GeneratedTooLargeException.class,
                () -> GroundFill.extend(high, lowest, under, (x, y, z, low) -> stone, 200, world.bottomY()));
        assertEquals(2 + 2 * 136, GroundFill.extend(high, lowest, under, (x, y, z, low) -> stone, 274, world.bottomY()).cells(),
                "exactly at the cap is fine");
        GeneratedSource onGround = cells(dirt, 0, 64, 0);
        Long2IntMap low = GroundFill.lowest(onGround);
        assertSame(onGround, GroundFill.extend(onGround, low, GroundFill.groundUnder(low, world), (x, y, z, l) -> stone,
                CAP, world.bottomY()), "standing on the ground: nothing to add");
    }

    @Test
    void aLineOverAValleyBecomesAWallStandingOnIt() {
        FakeWorld world = ground();
        for (int x = 4; x <= 12; x++) for (int y = 58; y <= 63; y++) world.set(x, y, 0, states.air()); // a valley
        LineKernel.Spec spec = new LineKernel.Spec(new PathSpec(List.of(new BlockPos(0, 66, 0), new BlockPos(16, 66, 0)),
                PathKind.STRAIGHT), 1, LineKernel.Profile.ROUND, new Pattern.Single(stone));
        GeneratedSource line = LineKernel.generate(spec, states, CAP);
        Long2IntMap lowest = GroundFill.lowest(line);
        GeneratedSource wall = GroundFill.extend(line, lowest, GroundFill.groundUnder(lowest, world),
                (x, y, z, low) -> stone, CAP, world.bottomY());
        for (int x = 0; x <= 16; x++) {
            int floor = x >= 4 && x <= 12 ? 58 : 64;
            for (int y = floor; y <= 66; y++) assertTrue(wall.contains(x, y, 0), "solid at " + x + "," + y);
            assertFalse(wall.contains(x, floor - 1, 0));
        }
    }

    @Test
    void theGroundScanStopsOnceTheCellsLookedThroughPassTheBudgetOverTheVoidToo() {
        FakeWorld world = ground();
        GeneratedSource high = cells(dirt, 0, 200, 0, 1, 200, 0, 40, 200, 40);
        Long2IntMap lowest = GroundFill.lowest(high);
        assertEquals(null, GroundFill.groundUnder(lowest, world, 200), "two columns of 136 fill cells pass 200");
        // Over the void (40, 40) the look runs from 199 to the floor at -64: 264 cells, which count too.
        assertEquals(null, GroundFill.groundUnder(lowest, world, 272 + 263), "one cell short");
        GroundMap enough = GroundFill.groundUnder(lowest, world, 272 + 264);
        assertEquals(63, enough.ground(0, 0));
        assertEquals(SurfaceReader.NONE, enough.ground(40, 40), "nothing under it");
    }

    @Test
    void aSheetOverTheVoidReadsNoMoreThanItsBudget() {
        // 64 x 64 columns at y 300 over nothing: unbounded, each would be read to the floor (364 cells, 1.5 million).
        FakeWorld empty = new FakeWorld(states);
        long[] reads = {0};
        WorldReader counting = new WorldReader() {
            @Override
            public dev.sculptory.core.state.StateSpace states() {
                return empty.states();
            }

            @Override
            public int bottomY() {
                return empty.bottomY();
            }

            @Override
            public int topYExclusive() {
                return empty.topYExclusive();
            }

            @Override
            public boolean isLoaded(int cx, int cz) {
                return true;
            }

            @Override
            public int get(int x, int y, int z) {
                reads[0]++;
                return empty.get(x, y, z);
            }

            @Override
            public dev.sculptory.core.buffer.BlockEntityData tile(int x, int y, int z) {
                return null;
            }

            @Override
            public void copySection(int sx, int sy, int sz, dev.sculptory.core.buffer.SectionBuffer into) {
                empty.copySection(sx, sy, sz, into);
            }
        };
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap lowest = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        for (int x = 0; x < 64; x++) {
            for (int z = 0; z < 64; z++) lowest.put(GroundMap.column(x, z), 300);
        }
        assertNull(GroundFill.groundUnder(lowest, counting, 10_000), "the void is deeper than the budget");
        assertTrue(reads[0] <= 10_001, reads[0] + " reads for a budget of 10,000");
    }

    @Test
    void aColumnCarriesItsLowestFullBlockDownNotTheStairsOrLanternUnderIt() {
        FakeWorld world = ground();
        int stairs = states.state("minecraft:oak_stairs[half=top]");
        GeneratedSource.Builder wall = GeneratedSource.builder(CAP);
        wall.set(0, 70, 0, stairs).set(0, 71, 0, dirt); // upside-down stairs under the wall's dirt
        wall.set(2, 70, 0, stairs); // a column of stairs only
        GeneratedSource source = wall.build();
        Long2IntMap lowest = GroundFill.lowest(source);
        GroundMap under = GroundFill.groundUnder(lowest, world);
        java.util.function.IntPredicate full = s -> s == dirt || s == stone;
        GeneratedSource filled = GroundFill.extend(source, lowest, under, full,
                (x, y, z, column) -> column >= 0 ? column : -1, CAP, world.bottomY());
        for (int y = 64; y < 70; y++) assertEquals(dirt, filled.get(0, y, 0), "the dirt carried down at " + y);
        assertEquals(stairs, filled.get(0, 70, 0), "the stairs stay where they were");
        assertFalse(filled.contains(2, 69, 0), "no full block in the column and no other: no fill");
    }

    private final int log = states.state("minecraft:oak_log");
    private final int leaves = states.state("minecraft:oak_leaves");

    /** An oak on the stone at (x, z): a trunk at 64-67, leaves over the 3 x 3 around it at 66-68. */
    private void tree(FakeWorld world, int x, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 66; y <= 68; y++) world.set(x + dx, y, z + dz, leaves);
            }
        }
        for (int y = 64; y <= 67; y++) world.set(x, y, z, log);
    }

    @Test
    void overAForestTheFillGoesThroughTheLeavesAndTheTrunkToTheGround() {
        FakeWorld world = ground();
        tree(world, 0, 0);
        assertTrue(GroundFill.lookedThrough(states, leaves) && GroundFill.lookedThrough(states, log));
        assertTrue(GroundFill.lookedThrough(states, grass) && GroundFill.lookedThrough(states, water));
        assertFalse(GroundFill.lookedThrough(states, stone), "terrain stops the look");
        assertFalse(GroundFill.lookedThrough(states, states.state("minecraft:oak_planks")), "so does a build's block");
        assertFalse(GroundFill.lookedThrough(states, states.state("minecraft:chest")), "and a block entity");
        GeneratedSource beam = cells(dirt, -2, 72, 0, -1, 72, 0, 0, 72, 0, 1, 72, 0, 2, 72, 0);
        Long2IntMap lowest = GroundFill.lowest(beam);
        GroundMap under = GroundFill.groundUnder(lowest, world);
        for (int x = -2; x <= 2; x++) assertEquals(63, under.ground(x, 0), "the ground under the tree at " + x);
        assertEquals(63, GroundFill.groundBelow(world, 0, 0, 72), "the single-column form agrees");
        GeneratedSource wall = GroundFill.extend(beam, lowest, under, (x, y, z, low) -> stone, CAP, world.bottomY());
        for (int x = -2; x <= 2; x++) {
            for (int y = 64; y <= 71; y++) assertEquals(stone, wall.get(x, y, 0), "filled at " + x + "," + y);
        }
        assertEquals(beam.cells() + 5 * 8, wall.cells(), "the trunk and the leaves in the way are filled over");
    }

    @Test
    void aBuildUnderTheCanopyStopsTheFillAsTheGroundDoes() {
        FakeWorld world = ground();
        tree(world, 0, 0);
        world.set(1, 64, 0, states.state("minecraft:oak_planks")); // a hut's roof under the leaves
        world.set(-1, 65, 0, states.state("minecraft:chest"));
        Long2IntMap lowest = GroundFill.lowest(cells(dirt, -1, 72, 0, 1, 72, 0));
        GroundMap under = GroundFill.groundUnder(lowest, world);
        assertEquals(64, under.ground(1, 0));
        assertEquals(65, under.ground(-1, 0));
    }

    @Test
    void underAnOverhangTheFillReachesTheGroundBeneathAndOnALedgeItStopsOnTheLedge() {
        FakeWorld world = ground();
        for (int x = 0; x <= 4; x++) for (int y = 73; y <= 75; y++) world.set(x, y, 0, stone); // the overhang
        world.set(6, 70, 0, stone); // a ledge
        // Under the overhang, a column of the shape inside it, and over the ledge.
        GeneratedSource shape = cells(dirt, 0, 70, 0, 0, 71, 0, 2, 70, 0, 2, 74, 0, 6, 72, 0);
        Long2IntMap lowest = GroundFill.lowest(shape);
        GroundMap under = GroundFill.groundUnder(lowest, world);
        assertEquals(63, under.ground(0, 0), "under the overhang: the ground beneath it");
        assertEquals(63, under.ground(2, 0), "the shape's lowest cell counts, not the overhang it passes through");
        assertEquals(70, under.ground(6, 0), "over a ledge: the ledge is the terrain");
        GeneratedSource filled = GroundFill.extend(shape, lowest, under, (x, y, z, low) -> stone, CAP, world.bottomY());
        for (int y = 64; y <= 69; y++) assertEquals(stone, filled.get(0, y, 0));
        assertEquals(stone, filled.get(6, 71, 0));
        assertFalse(filled.contains(6, 70, 0));
        assertEquals(shape.cells() + 6 + 6 + 1, filled.cells());
    }

    @Test
    void theBudgetCountsTheCellsThroughTheTreesAndUnloadedColumnsAreSkipped() {
        FakeWorld world = ground();
        tree(world, 0, 0);
        world.setLoaded(2, 0, false); // x 32-47
        GeneratedSource beam = cells(dirt, 0, 72, 0, 40, 72, 0);
        Long2IntMap lowest = GroundFill.lowest(beam);
        assertNull(GroundFill.groundUnder(lowest, world, 7), "8 cells through the trunk pass a budget of 7");
        GroundMap under = GroundFill.groundUnder(lowest, world, 8);
        assertEquals(63, under.ground(0, 0));
        assertEquals(SurfaceReader.UNLOADED, under.ground(40, 0), "an unloaded column costs nothing and fills nothing");
        assertEquals(SurfaceReader.UNLOADED, GroundFill.groundBelow(world, 40, 0, 72));
        assertEquals(SurfaceReader.NONE, GroundFill.groundBelow(world, 30, 30, 72), "nothing under it");
        GeneratedSource filled = GroundFill.extend(beam, lowest, under, (x, y, z, low) -> stone, CAP, world.bottomY());
        assertEquals(beam.cells() + 8, filled.cells());
        assertFalse(filled.contains(40, 71, 0));
        assertThrows(GeneratedTooLargeException.class,
                () -> GroundFill.extend(beam, lowest, under, (x, y, z, low) -> stone, beam.cells() + 7, world.bottomY()),
                "the fill through the trees is capped as any fill");
    }
}
