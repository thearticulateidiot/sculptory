package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Extrude along a path: straight pulls, bends, walls upright or following the slope, floors twisting least, caps. */
class PathExtrudeKernelTest {
    private static final int BOTTOM = -64, TOP = 320;
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int chest = states.state("minecraft:chest[facing=north]");

    /** A floor face: the cells (x, y, z) for x in x0..x1, z in z0..z1, facing up; dirt on its x0 column, stone else. */
    private FaceCells floor(int x0, int x1, int y, int z0, int z1) {
        List<int[]> cells = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) cells.add(new int[] {x, y, z, x == x0 ? dirt : stone});
        }
        return face(1, 1, cells);
    }

    /** A wall face looking east at x: y in y0..y1, z in z0..z1; stairs facing east on its top row, stone below. */
    private FaceCells eastWall(int x, int y0, int y1, int z0, int z1) {
        List<int[]> cells = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                cells.add(new int[] {x, y, z, y == y1 ? states.state("minecraft:oak_stairs[facing=east]") : stone});
            }
        }
        return face(0, 1, cells);
    }

    private static FaceCells face(int axis, int sign, List<int[]> cells) {
        int n = cells.size();
        int[] xs = new int[n], ys = new int[n], zs = new int[n], s = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = cells.get(i)[0];
            ys[i] = cells.get(i)[1];
            zs[i] = cells.get(i)[2];
            s[i] = cells.get(i)[3];
        }
        return new FaceCells(axis, sign, xs, ys, zs, s);
    }

    private GeneratedResult extrude(FaceCells face, PathKind kind, boolean followSlope, int... points) {
        return PathExtrudeKernel.generate(spec(face, kind, followSlope, points), states, 1 << 20, BOTTOM, TOP);
    }

    private static PathExtrudeKernel.Spec spec(FaceCells face, PathKind kind, boolean followSlope, int... xyz) {
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) points.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return new PathExtrudeKernel.Spec(face, points, kind, 0, followSlope);
    }

    private static Map<String, Integer> cells(GeneratedSource source) {
        Map<String, Integer> cells = new HashMap<>();
        source.forEach((x, y, z, state) -> cells.put(x + "," + y + "," + z, state));
        return cells;
    }

    @Test
    void aFloorPulledStraightUpIsExactlyItsOwnBlocksOncePerLayer() {
        FaceCells face = floor(0, 2, 64, 0, 2);
        assertEquals(new BlockPos(1, 64, 1), face.centre());
        GeneratedResult result = extrude(face, PathKind.STRAIGHT, false, 1, 70, 1);
        GeneratedSource source = result.source();
        assertEquals(0, result.leftOut());
        assertEquals(9 * 6, source.cells(), "six layers of nine");
        for (int y = 65; y <= 70; y++) {
            for (int x = 0; x <= 2; x++) {
                for (int z = 0; z <= 2; z++) assertEquals(x == 0 ? dirt : stone, source.get(x, y, z), x + "," + y + "," + z);
            }
        }
        assertFalse(source.contains(1, 64, 1), "the face itself is not written");
        // An even face is carried by the cell past its middle.
        assertEquals(new BlockPos(2, 64, 2), floor(0, 3, 64, 0, 3).centre());
        assertEquals(16 * 4, extrude(floor(0, 3, 64, 0, 3), PathKind.STRAIGHT, false, 2, 68, 2).source().cells());
    }

    @Test
    void theSameSpecGivesTheSameCellsEveryTime() {
        FaceCells face = floor(0, 4, 64, 0, 3);
        Map<String, Integer> first = cells(extrude(face, PathKind.CURVE, false, 2, 72, 2, 12, 76, 8, 20, 70, 0).source());
        for (int i = 0; i < 3; i++) {
            assertEquals(first, cells(extrude(floor(0, 4, 64, 0, 3), PathKind.CURVE, false, 2, 72, 2, 12, 76, 8, 20, 70, 0)
                    .source()));
        }
    }

    @Test
    void aFloorCarriedUpAndOverTurnsTheLeastWayAndLeavesNoGaps() {
        // Up from the floor, then a sharp bend east: the face turns onto its side about north-south.
        FaceCells face = floor(0, 2, 64, 0, 0);
        GeneratedSource source = extrude(face, PathKind.STRAIGHT, false, 1, 70, 0, 12, 70, 0).source();
        for (int y = 65; y <= 68; y++) {
            for (int x = 0; x <= 2; x++) assertTrue(source.contains(x, y, 0), "the climb at " + x + "," + y);
        }
        for (int x = 4; x <= 12; x++) {
            for (int y = 69; y <= 71; y++) assertTrue(source.contains(x, y, 0), "the run east at " + x + "," + y);
            assertFalse(source.contains(x, 72, 0) || source.contains(x, 68, 0), "the run east is 3 high at " + x);
            // The face's x0 column (dirt) is its lowest row now: +x turned down.
            assertEquals(dirt, source.get(x, 71, 0), "the dirt side at " + x);
        }
    }

    @Test
    void aWallAroundABendStaysUprightAndItsStairsTurnAQuarter() {
        FaceCells wall = eastWall(0, 64, 66, -1, 1);
        GeneratedSource source = extrude(wall, PathKind.STRAIGHT, false, 10, 65, 0, 10, 65, 12).source();
        for (int x = 1; x <= 8; x++) {
            for (int y = 64; y <= 66; y++) {
                for (int z = -1; z <= 1; z++) assertTrue(source.contains(x, y, z), "east leg " + x + "," + y + "," + z);
            }
            assertEquals("east", states.describe(source.get(x, 66, 0)).get("facing"));
        }
        for (int z = 3; z <= 12; z++) {
            for (int y = 64; y <= 66; y++) {
                for (int x = 9; x <= 11; x++) assertTrue(source.contains(x, y, z), "south leg " + x + "," + y + "," + z);
            }
            assertEquals("south", states.describe(source.get(10, 66, z)).get("facing"), "turned a quarter at " + z);
        }
        source.forEach((x, y, z, state) -> assertTrue(y >= 64 && y <= 66, "upright: never above or below the face"));
    }

    @Test
    void aWallGoingUpSlidesUprightOrTiltsBackFollowingTheSlope() {
        FaceCells wall = eastWall(0, 64, 66, 0, 0);
        GeneratedSource upright = extrude(wall, PathKind.STRAIGHT, false, 0, 76, 0).source();
        upright.forEach((x, y, z, state) -> assertEquals(0, x, "upright, the face slides up its own plane"));
        GeneratedSource tilted = extrude(wall, PathKind.STRAIGHT, true, 0, 76, 0).source();
        Set<Integer> across = new HashSet<>();
        tilted.forEach((x, y, z, state) -> {
            if (y == 72) across.add(x);
        });
        assertEquals(Set.of(-1, 0, 1), across, "following the slope it lies level, its top pointing back west");
        assertEquals(stone, tilted.get(1, 72, 0));
        assertTrue(upright.cells() > 0);
    }

    @Test
    void blockEntitiesAreLeftOutAndCounted() {
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x <= 2; x++) cells.add(new int[] {x, 64, 0, x == 1 ? chest : stone});
        GeneratedResult result = extrude(face(1, 1, cells), PathKind.STRAIGHT, false, 1, 67, 0);
        assertEquals(1, result.leftOut());
        assertEquals(2 * 3, result.source().cells());
        for (int y = 65; y <= 67; y++) assertFalse(result.source().contains(1, y, 0));
        List<int[]> chests = new ArrayList<>();
        chests.add(new int[] {0, 64, 0, chest});
        GeneratedResult none = extrude(face(1, 1, chests), PathKind.STRAIGHT, false, 0, 70, 0);
        assertEquals(1, none.leftOut());
        assertTrue(none.source().isEmpty());
    }

    @Test
    void itStopsAtTheBuildHeightAndRefusesPastItsCap() {
        FaceCells face = floor(0, 2, 316, 0, 2);
        GeneratedSource clipped = extrude(face, PathKind.STRAIGHT, false, 1, 330, 1).source();
        clipped.forEach((x, y, z, state) -> assertTrue(y < TOP));
        assertEquals(9 * 3, clipped.cells(), "317 to 319");
        assertThrows(GeneratedTooLargeException.class, () -> PathExtrudeKernel.generate(
                spec(floor(0, 9, 64, 0, 9), PathKind.STRAIGHT, false, 5, 100, 5), states, 1000, BOTTOM, TOP));
        assertThrows(GeneratedTooLargeException.class, () -> PathExtrudeKernel.generate(
                spec(floor(0, 9, 64, 0, 9), PathKind.STRAIGHT, false, 5, 1_000_000, 5), states, 1 << 20, -2_000_000,
                2_000_000));
    }

    @Test
    void aClosedCurveComesBackRoundWithoutAGap() {
        // A wall carried round a loop that ends on its own centre cell.
        FaceCells wall = eastWall(0, 63, 65, -1, 1);
        PathExtrudeKernel.Spec spec = spec(wall, PathKind.CURVE, false, 10, 64, 0, 10, 64, 10, 0, 64, 10, 0, 64, 0);
        assertTrue(dev.sculptory.core.path.PathSampler.closed(spec.path()));
        GeneratedSource ring = PathExtrudeKernel.generate(spec, states, 1 << 20, BOTTOM, TOP).source();
        ring.forEach((x, y, z, state) -> assertTrue(y >= 63 && y <= 65, "upright round the loop"));
        // Every cell the centre line passes through is laid, the seam included (away from the face itself).
        double length = dev.sculptory.core.path.PathSampler.length(spec.path());
        dev.sculptory.core.path.PathSampler.visit(spec.path(), 0.25, (x, y, z, along) -> {
            if (along > 2 && along < length - 2) {
                assertTrue(ring.contains((int) Math.floor(x), 64, (int) Math.floor(z)), "gap at " + x + "," + z);
            }
        });
    }
}
