package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Path generator: footprints, heights and cells on small synthetic terrains. */
class RoadKernelTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int grass = states.state("minecraft:grass_block");
    private final int air = states.air();

    /** Grass at {@code height(x, z)} over stone down to y 50, x and z within ±40. */
    private FakeWorld world(HeightFunction height) {
        FakeWorld world = new FakeWorld(states);
        for (int x = -40; x <= 40; x++) {
            for (int z = -40; z <= 40; z++) {
                int top = height.at(x, z);
                for (int y = 50; y <= top; y++) world.set(x, y, z, y == top ? grass : stone);
            }
        }
        return world;
    }

    @FunctionalInterface
    private interface HeightFunction {
        int at(int x, int z);
    }

    private static RoadKernel.Spec spec(List<BlockPos> nodes, int width, Pattern border, RoadKernel.HeightMode mode,
                                        boolean level, int fill, int clear) {
        return new RoadKernel.Spec(nodes, width, new Pattern.Single(1), border, mode, level, fill, clear, 0);
    }

    private static List<BlockPos> nodes(int... xyz) {
        List<BlockPos> nodes = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) nodes.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return nodes;
    }

    private static Set<String> columns(RoadKernel.Path path) {
        Set<String> set = new HashSet<>();
        for (RoadKernel.Column column : path.columns()) set.add(column.x() + "," + column.z());
        return set;
    }

    // ---------------------------------------------------------------- footprints

    @Test
    void aStraightRoadOfOddWidthIsCentredOnTheNodesAndEndsSquare() {
        RoadKernel.Path path = RoadKernel.path(spec(nodes(0, 63, 0, 10, 63, 0), 3, null,
                RoadKernel.HeightMode.STRAIGHT, true, 0, 0));
        Set<String> expected = new HashSet<>();
        for (int x = 0; x <= 10; x++) {
            for (int z = -1; z <= 1; z++) expected.add(x + "," + z);
        }
        assertEquals(expected, columns(path));
        assertEquals(33, path.columns().size());
        for (RoadKernel.Column column : path.columns()) {
            assertEquals(Math.abs(column.z()), column.distance(), 1e-9);
            assertEquals(column.z() != 0, column.border(), "the outer columns are the border: " + column);
            assertEquals(column.x(), column.along(), 1e-9, "arc length along x: " + column);
        }
        assertEquals(10.0, path.length(), 1e-9);
        assertFalse(path.closed());
    }

    @Test
    void anEvenWidthRunsThroughTheNodesCornerAndIsSymmetricAboutIt() {
        RoadKernel.Path path = RoadKernel.path(spec(nodes(0, 63, 0, 10, 63, 0), 4, null,
                RoadKernel.HeightMode.STRAIGHT, true, 0, 0));
        Set<String> expected = new HashSet<>();
        for (int x = 1; x <= 10; x++) {
            for (int z = -1; z <= 2; z++) expected.add(x + "," + z);
        }
        assertEquals(expected, columns(path));
        for (RoadKernel.Column column : path.columns()) {
            assertEquals(column.z() == -1 || column.z() == 2, column.border(), column.toString());
        }
        RoadKernel.Path one = RoadKernel.path(spec(nodes(0, 63, 0, 10, 63, 0), 1, null,
                RoadKernel.HeightMode.STRAIGHT, true, 0, 0));
        assertEquals(11, one.columns().size(), "a one-wide road is the nodes' own row");
        assertTrue(one.columns().stream().allMatch(RoadKernel.Column::border), "every column of a narrow road is border");
    }

    @Test
    void aCurvedPathFollowsTheSplineThroughEveryNode() {
        RoadKernel.Path path = RoadKernel.path(spec(nodes(0, 63, 0, 10, 63, 0, 10, 63, 10), 1, null,
                RoadKernel.HeightMode.STRAIGHT, true, 0, 0));
        Set<String> columns = columns(path);
        assertTrue(columns.contains("0,0") && columns.contains("10,0") && columns.contains("10,10"), columns.toString());
        assertTrue(path.columns().size() >= 20 && path.columns().size() <= 40, "cells: " + path.columns().size());
        for (RoadKernel.Column column : path.columns()) assertTrue(column.distance() < 0.5, column.toString());
        assertTrue(path.length() > 20 && path.length() < 24, "a rounded corner: " + path.length());
        // The corner is cut: the spline overshoots the middle node a little, never by more than a couple of blocks.
        for (RoadKernel.Column column : path.columns()) {
            assertTrue(column.x() <= 12 && column.z() >= -2, "far from the corner: " + column);
        }
    }

    @Test
    void aPathWhoseLastNodeIsItsFirstIsAClosedLoop() {
        RoadKernel.Path path = RoadKernel.path(spec(nodes(0, 63, 0, 10, 63, 0, 10, 63, 10, 0, 63, 10, 0, 63, 0), 1, null,
                RoadKernel.HeightMode.STRAIGHT, true, 0, 0));
        assertTrue(path.closed());
        Set<String> columns = columns(path);
        // The spline bulges a block outside the square between the corners; every side has a column mid-way.
        assertTrue(columns.contains("0,0") && columns.contains("10,0") && columns.contains("10,10") && columns.contains("0,10"));
        assertTrue(columns.contains("-1,5") || columns.contains("0,5"), "the closing side (west): " + columns);
        assertTrue(columns.contains("5,-1") || columns.contains("5,0"), "north: " + columns);
        assertTrue(columns.contains("11,5") || columns.contains("10,5"), "east: " + columns);
        assertTrue(columns.contains("5,11") || columns.contains("5,10"), "south: " + columns);
        assertFalse(columns.contains("5,5"), "the inside stays empty");
        RoadKernel.Path open = RoadKernel.path(spec(nodes(0, 63, 0, 10, 63, 0, 10, 63, 10, 0, 63, 10), 1, null,
                RoadKernel.HeightMode.STRAIGHT, true, 0, 0));
        assertFalse(open.closed());
        Set<String> openColumns = columns(open);
        assertFalse(openColumns.contains("-1,5") || openColumns.contains("0,5"), "the open path has no closing side");
    }

    @Test
    void fewerThanTwoNodesMakeNoFootprint() {
        assertTrue(RoadKernel.path(spec(List.of(), 3, null, RoadKernel.HeightMode.STRAIGHT, true, 0, 0)).columns().isEmpty());
        RoadKernel.Path one = RoadKernel.path(spec(nodes(3, 63, 4), 3, null, RoadKernel.HeightMode.STRAIGHT, true, 0, 0));
        assertTrue(one.columns().isEmpty());
        assertEquals(63, one.nodeHeightAt(5), 1e-9);
        RoadKernel.Result result = RoadKernel.generate(spec(nodes(3, 63, 4), 3, null, RoadKernel.HeightMode.STRAIGHT,
                true, 0, 0), (x, z) -> 63, -64, 320, 1000);
        assertTrue(result.source().isEmpty());
    }

    // ---------------------------------------------------------------- heights and cells

    @Test
    void straightBetweenPointsInterpolatesTheNodesHeightsAndReadsNoWorld() {
        RoadKernel.Spec spec = spec(nodes(0, 60, 0, 10, 70, 0), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 0, 0);
        RoadKernel.Result result = RoadKernel.generate(spec, (x, z) -> {
            throw new AssertionError("read the world");
        }, -64, 320, 1000);
        assertTrue(result.unloaded().isEmpty());
        assertEquals(11, result.source().cells());
        for (int x = 0; x <= 10; x++) assertEquals(1, result.source().get(x, 60 + x, 0), "x " + x);
    }

    @Test
    void followTerrainPavesEachColumnsGroundOnFlatTerrain() {
        FakeWorld world = world((x, z) -> 63);
        RoadKernel.Spec spec = spec(nodes(0, 63, 0, 8, 63, 0), 3, null, RoadKernel.HeightMode.FOLLOW_TERRAIN, true, 0, 0);
        RoadKernel.Result result = RoadKernel.generate(spec, SurfaceReader.of(world), world.bottomY(), world.topYExclusive(),
                1000);
        assertEquals(27, result.source().cells());
        for (int x = 0; x <= 8; x++) {
            for (int z = -1; z <= 1; z++) {
                assertEquals(1, result.source().get(x, 63, z), x + "," + z);
                assertEquals(-1, result.source().get(x, 64, z), "nothing above");
                assertEquals(-1, result.source().get(x, 62, z), "nothing below");
            }
        }
    }

    @Test
    void levelAcrossSmoothsTheCentreLineAndHuggingFollowsEachColumn() {
        FakeWorld world = world((x, z) -> x == 4 ? 70 : 63);
        RoadKernel.Spec level = spec(nodes(0, 63, 0, 8, 63, 0), 3, null, RoadKernel.HeightMode.FOLLOW_TERRAIN, true, 0, 0);
        GeneratedSource levelled = RoadKernel.generate(level, SurfaceReader.of(world), -64, 320, 1000).source();
        // The spike at x 4 is averaged over a window three samples wide: 65 at x 3-5, 63 elsewhere, across the width.
        for (int z = -1; z <= 1; z++) {
            for (int x = 0; x <= 8; x++) {
                int expected = x >= 3 && x <= 5 ? 65 : 63;
                assertEquals(1, levelled.get(x, expected, z), x + "," + z + " at " + expected);
            }
        }
        assertEquals(27, levelled.cells());

        RoadKernel.Spec hug = spec(nodes(0, 63, 0, 8, 63, 0), 3, null, RoadKernel.HeightMode.FOLLOW_TERRAIN, false, 0, 0);
        GeneratedSource hugged = RoadKernel.generate(hug, SurfaceReader.of(world), -64, 320, 1000).source();
        for (int z = -1; z <= 1; z++) {
            for (int x = 0; x <= 8; x++) {
                assertEquals(1, hugged.get(x, x == 4 ? 70 : 63, z), x + "," + z);
            }
        }
    }

    @Test
    void aColumnWithoutASurfaceTakesTheCentreLinesHeight() {
        FakeWorld world = world((x, z) -> 63);
        int stairs = states.state("minecraft:oak_stairs");
        world.set(2, 64, 0, stairs); // a structure on the ground: no surface there
        RoadKernel.Spec hug = spec(nodes(0, 63, 0, 6, 63, 0), 1, null, RoadKernel.HeightMode.FOLLOW_TERRAIN, false, 0, 0);
        GeneratedSource source = RoadKernel.generate(hug, SurfaceReader.of(world), -64, 320, 1000).source();
        assertEquals(SurfaceReader.NONE, SurfaceReader.of(world).ground(2, 0));
        assertEquals(1, source.get(2, 63, 0), "the centre line's neighbours give x 2 its height");
        assertEquals(7, source.cells());
    }

    @Test
    void borderFillBelowAndClearAboveWriteTheirCells() {
        FakeWorld world = world((x, z) -> 63);
        Pattern border = new Pattern.Single(2);
        RoadKernel.Spec spec = spec(nodes(0, 63, 0, 4, 63, 0), 3, border, RoadKernel.HeightMode.FOLLOW_TERRAIN, true, 2, 1);
        GeneratedSource source = RoadKernel.generate(spec, SurfaceReader.of(world), -64, 320, 1000).source();
        assertEquals(15 * 4, source.cells());
        for (int x = 0; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) {
                int material = z == 0 ? 1 : 2;
                assertEquals(material, source.get(x, 63, z), "surface " + x + "," + z);
                assertEquals(material, source.get(x, 62, z), "fill " + x + "," + z);
                assertEquals(material, source.get(x, 61, z), "fill " + x + "," + z);
                assertEquals(-1, source.get(x, 60, z), "fill stops");
                assertEquals(air, source.get(x, 64, z), "clear " + x + "," + z);
                assertEquals(-1, source.get(x, 65, z), "clear stops");
            }
        }
    }

    @Test
    void cellsOutsideTheBuildHeightAreDropped() {
        RoadKernel.Spec spec = spec(nodes(0, 1, 0, 2, 1, 0), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 3, 3);
        GeneratedSource source = RoadKernel.generate(spec, (x, z) -> 1, 0, 3, 1000).source();
        assertEquals(3 * 3, source.cells(), "y 0-2 only");
        assertEquals(-1, source.get(0, -1, 0));
        assertEquals(-1, source.get(0, 3, 0));
    }

    @Test
    void unloadedColumnsWriteNothingAndAreReported() {
        FakeWorld world = world((x, z) -> 63);
        world.setLoaded(0, 0, false); // chunk x 0-15, z 0-15
        RoadKernel.Spec follow = spec(nodes(-5, 63, 2, 20, 63, 2), 1, null, RoadKernel.HeightMode.FOLLOW_TERRAIN, true, 0, 0);
        RoadKernel.Result result = RoadKernel.generate(follow, SurfaceReader.of(world), -64, 320, 1000);
        assertEquals(16, result.unloaded().size());
        for (RoadKernel.Column column : result.unloaded()) assertTrue(column.x() >= 0 && column.x() <= 15, column.toString());
        assertEquals(26 - 16, result.source().cells());
        for (int x = -5; x <= 20; x++) {
            assertEquals(x >= 0 && x <= 15 ? -1 : 1, result.source().get(x, 63, 2), "x " + x);
        }
        RoadKernel.Spec straight = spec(nodes(-5, 63, 2, 20, 63, 2), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 0, 0);
        assertTrue(RoadKernel.generate(straight, SurfaceReader.of(world), -64, 320, 1000).unloaded().isEmpty(),
                "straight between points reads no chunks");
        GroundMap map = new GroundMap();
        assertEquals(SurfaceReader.UNLOADED, map.ground(3, 3), "a column not captured is unloaded");
        map.capture(SurfaceReader.of(world), new long[] {GroundMap.column(-2, 2), GroundMap.column(3, 2)});
        assertEquals(63, map.ground(-2, 2));
        assertEquals(SurfaceReader.UNLOADED, map.ground(3, 2));
    }

    @Test
    void aStraightRoadThroughTheAirIsAFloatingDeckWithNoFillUnderIt() {
        FakeWorld world = world((x, z) -> 63);
        RoadKernel.Spec spec = spec(nodes(0, 100, 0, 10, 100, 0), 3, null, RoadKernel.HeightMode.STRAIGHT, true, 4, 0);
        RoadKernel.Path path = RoadKernel.path(spec);
        RoadKernel.Result result = RoadKernel.generate(spec, path, RoadKernel.deckGround(spec, path, world), -64, 320,
                10_000);
        assertTrue(result.unloaded().isEmpty());
        assertEquals(0, result.bareColumns());
        assertEquals(11 * 3, result.source().cells(), "the deck alone");
        result.source().forEach((x, y, z, state) -> assertEquals(100, y, "nothing hangs under the deck"));
    }

    @Test
    void straightFillBelowReachesDownOnlyWhereItMeetsTheGroundWithinItsDepth() {
        FakeWorld world = world((x, z) -> 63);
        // A ramp from the ground (63) up into the air (83), one block up per block along, fill 3.
        RoadKernel.Spec spec = spec(nodes(0, 63, 0, 20, 83, 0), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 3, 0);
        RoadKernel.Path path = RoadKernel.path(spec);
        GeneratedSource source = RoadKernel.generate(spec, path, RoadKernel.deckGround(spec, path, world), -64, 320,
                10_000).source();
        for (int x = 0; x <= 20; x++) {
            int y = 63 + x;
            assertEquals(1, source.get(x, y, 0), "surface at " + x);
            boolean meets = y - 3 - 1 <= 63;
            for (int k = 1; k <= 3; k++) {
                assertEquals(meets ? 1 : -1, source.get(x, y - k, 0), "fill " + k + " under " + x);
            }
            assertEquals(-1, source.get(x, y - 4, 0), "fill stops at its depth at " + x);
        }
        // On the ground the fill is as it was: its full depth into the ground; a tunnel floor meets it too.
        RoadKernel.Spec flat = spec(nodes(0, 63, 0, 4, 63, 0), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 2, 0);
        RoadKernel.Path flatPath = RoadKernel.path(flat);
        assertEquals(5 * 3, RoadKernel.generate(flat, flatPath, RoadKernel.deckGround(flat, flatPath, world), -64, 320,
                1000).source().cells());
        RoadKernel.Spec tunnel = spec(nodes(0, 55, 0, 4, 55, 0), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 2, 0);
        RoadKernel.Path tunnelPath = RoadKernel.path(tunnel);
        assertEquals(5 * 3, RoadKernel.generate(tunnel, tunnelPath, RoadKernel.deckGround(tunnel, tunnelPath, world), -64,
                320, 1000).source().cells());
    }

    @Test
    void straightFillLooksDownFromTheDeckNotDownFromTheSky() {
        // A stone overhang at 90 over a deck at 80, ground at 63: from the sky the overhang is the surface, within the
        // fill's depth of nothing; from the deck down there is only air within it. The deck stands alone.
        FakeWorld world = world((x, z) -> 63);
        for (int x = -2; x <= 12; x++) {
            for (int z = -3; z <= 3; z++) world.set(x, 90, z, stone);
        }
        RoadKernel.Spec spec = spec(nodes(0, 80, 0, 10, 80, 0), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 3, 0);
        RoadKernel.Path path = RoadKernel.path(spec);
        GeneratedSource source = RoadKernel.generate(spec, path, RoadKernel.deckGround(spec, path, world), -64, 320,
                1000).source();
        assertEquals(11, source.cells(), "the deck alone under the overhang");
        // A lantern on the ground under a low deck: something under the deck within the fill's depth, so it fills
        // (from the sky the lantern made the column surfaceless).
        int lantern = states.state("minecraft:lantern");
        world.set(22, 64, 0, lantern);
        RoadKernel.Spec low = spec(nodes(20, 66, 0, 24, 66, 0), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 2, 0);
        RoadKernel.Path lowPath = RoadKernel.path(low);
        assertEquals(SurfaceReader.NONE, SurfaceReader.of(world).ground(22, 0), "no surface from the sky");
        GeneratedSource lit = RoadKernel.generate(low, lowPath, RoadKernel.deckGround(low, lowPath, world), -64, 320,
                1000).source();
        assertEquals(1, lit.get(22, 65, 0));
        assertEquals(1, lit.get(22, 64, 0), "the fill reaches the lantern's cell");
    }

    @Test
    void followTerrainKeepsItsFillEvenOverADrop() {
        // A cliff across the road: z <= 0 at 63, z >= 1 at 53. Level across, the high side's height wins; the low side's
        // columns stand over the drop and still get their fill, as Follow terrain always did.
        FakeWorld world = world((x, z) -> z <= 0 ? 63 : 53);
        RoadKernel.Spec spec = spec(nodes(0, 63, 0, 6, 63, 0), 3, null, RoadKernel.HeightMode.FOLLOW_TERRAIN, true, 2, 0);
        GeneratedSource source = RoadKernel.generate(spec, SurfaceReader.of(world), -64, 320, 1000).source();
        for (int x = 0; x <= 6; x++) {
            assertEquals(1, source.get(x, 63, 1), "the surface over the drop at " + x);
            assertEquals(1, source.get(x, 62, 1), "its fill at " + x);
            assertEquals(1, source.get(x, 61, 1), "its fill at " + x);
            assertEquals(1, source.get(x, 61, -1), "the ground side's fill at " + x);
        }
    }

    @Test
    void aStraightRoadWithFillOverUnloadedColumnsIsABareDeckThereAndCountsThem() {
        RoadKernel.Spec spec = spec(nodes(0, 63, 0, 4, 63, 0), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 2, 0);
        RoadKernel.Result result = RoadKernel.generate(spec, new GroundMap(), -64, 320, 1000);
        assertTrue(result.unloaded().isEmpty(), "nothing refused");
        assertEquals(5, result.bareColumns(), "every column unloaded");
        assertEquals(5, result.source().cells(), "the deck alone");
        RoadKernel.Spec noFill = spec(nodes(0, 63, 0, 4, 63, 0), 1, null, RoadKernel.HeightMode.STRAIGHT, true, 0, 0);
        RoadKernel.Result plain = RoadKernel.generate(noFill, new GroundMap(), -64, 320, 1000);
        assertEquals(0, plain.bareColumns(), "without fill nothing is read");
        assertTrue(plain.unloaded().isEmpty());
        // Follow terrain still refuses unloaded columns.
        RoadKernel.Spec follow = spec(nodes(0, 63, 0, 4, 63, 0), 1, null, RoadKernel.HeightMode.FOLLOW_TERRAIN, true, 2, 0);
        assertEquals(5, RoadKernel.generate(follow, new GroundMap(), -64, 320, 1000).unloaded().size());
    }

    /**
     * A Straight road rising half a block a column (99 to 105 over 13 columns) is exactly on half blocks at every odd
     * column, and rounds those up: its deck rises at columns 1, 3, 5, 7, 9 and 11, where Use Stairs and Slabs puts a
     * stair across its width, wherever it is built (the moves of {@link #aRoadBuiltElsewhereIsTheSameRoadMoved}), along
     * x and along z.
     */
    @Test
    void aRoadRisingHalfABlockAColumnStepsAtEveryOddColumnAnywhere() {
        int bricks = states.state("minecraft:stone_bricks");
        int east = states.state("minecraft:stone_brick_stairs[facing=east,half=bottom]");
        int south = states.state("minecraft:stone_brick_stairs[facing=south,half=bottom]");
        SlopeSteps.Variants variants = new SlopeSteps.Variants() {
            @Override
            public int stairs(int state, String facing, String half) {
                return state == bricks ? states.state("minecraft:stone_brick_stairs[facing=" + facing + ",half=" + half
                        + "]") : -1;
            }

            @Override
            public int slab(int state, String type) {
                return state == bricks ? states.state("minecraft:stone_brick_slab[type=" + type + "]") : -1;
            }
        };
        Set<String> expected = new HashSet<>();
        for (int k = 1; k <= 11; k += 2) {
            for (int side = -1; side <= 1; side++) expected.add(k + "," + (99 + (k + 1) / 2) + "," + side);
        }
        for (int dx : new int[] {-4096, 16, 1_000_000, 2303 * 4096, 29_000_000}) {
            for (int step = -1024; step <= 1024; step += 16) {
                int dz = step, dy = (Math.floorMod(dz / 16, 3) - 1) * 20;
                for (boolean alongZ : new boolean[] {false, true}) {
                    BlockPos end = alongZ ? new BlockPos(dx, 105 + dy, dz + 12) : new BlockPos(dx + 12, 105 + dy, dz);
                    RoadKernel.Spec spec = new RoadKernel.Spec(List.of(new BlockPos(dx, 99 + dy, dz), end), 3,
                            new Pattern.Single(bricks), null, RoadKernel.HeightMode.STRAIGHT, true, 0, 2, air);
                    RoadKernel.Path laid = RoadKernel.path(spec);
                    for (RoadKernel.Column column : laid.columns()) {
                        int k = alongZ ? column.z() - dz : column.x() - dx;
                        assertEquals(99 + dy + (k + 1) / 2, RoadKernel.deckHeight(laid, column),
                                "the deck at column " + k + " moved by " + dx + ", " + dy + ", " + dz);
                    }
                    GeneratedSource road = RoadKernel.generate(spec, laid, new GroundMap(), -64, 320, 1 << 16).source();
                    int stair = alongZ ? south : east;
                    Set<String> found = new HashSet<>();
                    SlopeSteps.apply(road, SlopeSteps.byColumn(laid.climbAxes()), variants, air).source()
                            .forEach((x, y, z, state) -> {
                                if (state != stair) return;
                                int along = alongZ ? z - dz : x - dx, across = alongZ ? x - dx : z - dz;
                                found.add(along + "," + (y - dy) + "," + across);
                            });
                    assertEquals(expected, found, "stairs " + (alongZ ? "along z" : "along x") + " moved by " + dx + ", "
                            + dy + ", " + dz);
                }
            }
        }
    }

    /**
     * The same road built anywhere is the same road moved there. The spline's rounding noise grows with the size of
     * its coordinates, and these roads have ties: a deck exactly on half blocks, column centres exactly on the end
     * caps' planes, an even width's centre line on block boundaries, averaged ground exactly between two heights. Each
     * road is built at (0, 0, 0) and moved by whole blocks up to 29 million out (and 20 up or down), over ground moved
     * with it; its cells moved back must be the very same.
     */
    @Test
    void aRoadBuiltElsewhereIsTheSameRoadMoved() {
        // Ground for Follow terrain and fill below: uneven, so its averages fall on and between whole blocks.
        HeightFunction terrain = (x, z) -> 100 + Math.floorMod(3 * x + 5 * z, 7) / 2;
        RoadKernel.HeightMode straight = RoadKernel.HeightMode.STRAIGHT, follow = RoadKernel.HeightMode.FOLLOW_TERRAIN;
        checkMoved("along x, rising half a block a column", nodes(0, 99, 0, 12, 105, 0), 3, straight, true, 0, 2,
                terrain);
        checkMoved("along z", nodes(0, 99, 0, 0, 105, 12), 3, straight, true, 0, 2, terrain);
        checkMoved("diagonal", nodes(0, 99, 0, 9, 104, 7), 3, straight, true, 0, 2, terrain);
        checkMoved("reversed", nodes(12, 105, 0, 0, 99, 0), 3, straight, true, 0, 2, terrain);
        checkMoved("even width", nodes(0, 99, 0, 12, 105, 0), 2, straight, true, 0, 2, terrain);
        checkMoved("even width, diagonal", nodes(0, 99, 0, 9, 104, 7), 4, straight, true, 2, 1, terrain);
        checkMoved("a curve with fill below", nodes(0, 99, 0, 8, 102, 5, 16, 104, -3), 5, straight, true, 3, 2,
                terrain);
        checkMoved("Follow terrain, level across", nodes(0, 100, 0, 8, 100, 5, 16, 100, -3), 5, follow, true, 1, 2,
                terrain);
        checkMoved("Follow terrain, even width", nodes(0, 100, 0, 12, 100, 0), 2, follow, true, 0, 1, terrain);
        checkMoved("Follow terrain, each column its own ground", nodes(0, 100, 0, 9, 100, 7), 3, follow, false, 1, 1,
                terrain);
    }

    private static void checkMoved(String what, List<BlockPos> nodes, int width, RoadKernel.HeightMode mode, boolean level,
                                   int fill, int clear, HeightFunction terrain) {
        Set<String> reference = roadCells(nodes, width, mode, level, fill, clear, terrain, 0, 0, 0);
        assertTrue(reference.size() > 20, what + ": the road is there");
        int moves = 0;
        for (int dx : new int[] {-4096, 16, 1_000_000, 2303 * 4096, 29_000_000}) {
            for (int dz = -1024; dz <= 1024; dz += 16) {
                int dy = (Math.floorMod(dz / 16, 3) - 1) * 20;
                Set<String> moved = roadCells(nodes, width, mode, level, fill, clear, terrain, dx, dy, dz);
                assertEquals(reference, moved, what + ": the road moved by " + dx + ", " + dy + ", " + dz);
                moves++;
            }
        }
        assertEquals(5 * 129, moves);
    }

    /** The road's cells built moved by (dx, dy, dz) over the terrain moved with it, moved back, as x,y,z keys. */
    private static Set<String> roadCells(List<BlockPos> nodes, int width, RoadKernel.HeightMode mode, boolean level,
                                         int fill, int clear, HeightFunction terrain, int dx, int dy, int dz) {
        List<BlockPos> moved = new ArrayList<>();
        for (BlockPos node : nodes) moved.add(new BlockPos(node.x() + dx, node.y() + dy, node.z() + dz));
        RoadKernel.Spec spec = spec(moved, width, null, mode, level, fill, clear);
        SurfaceReader ground = (x, z) -> terrain.at(x - dx, z - dz) + dy;
        Set<String> cells = new HashSet<>();
        RoadKernel.generate(spec, ground, -64, 320, 1 << 16).source()
                .forEach((x, y, z, state) -> cells.add((x - dx) + "," + (y - dy) + "," + (z - dz) + "=" + state));
        return cells;
    }

    @Test
    void theCapRefusesTheCellOverIt() {
        RoadKernel.Spec spec = spec(nodes(0, 63, 0, 10, 63, 0), 3, null, RoadKernel.HeightMode.STRAIGHT, true, 0, 0);
        GeneratedTooLargeException e = assertThrows(GeneratedTooLargeException.class,
                () -> RoadKernel.generate(spec, (x, z) -> 63, -64, 320, 32));
        assertEquals(32, e.maxCells());
        assertEquals(33, RoadKernel.generate(spec, (x, z) -> 63, -64, 320, 33).source().cells());
    }

    @Test
    void theSpecChecksItsRanges() {
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(), 0, null, RoadKernel.HeightMode.STRAIGHT, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(), 33, null, RoadKernel.HeightMode.STRAIGHT, true, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(), 3, null, RoadKernel.HeightMode.STRAIGHT, true, 9, 0));
        assertThrows(IllegalArgumentException.class, () -> spec(List.of(), 3, null, RoadKernel.HeightMode.STRAIGHT, true, 0, -1));
        assertEquals(dirt, new Pattern.Single(dirt).apply(null, 0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> new RoadKernel.Spec(List.of(), 3, new Pattern.Dry(), null,
                RoadKernel.HeightMode.STRAIGHT, true, 0, 0, 0));
    }
}
