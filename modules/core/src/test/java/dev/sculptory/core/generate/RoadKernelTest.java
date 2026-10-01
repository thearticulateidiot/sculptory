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
