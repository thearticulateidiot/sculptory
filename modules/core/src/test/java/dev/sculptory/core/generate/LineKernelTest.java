package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Generate's Line: thickness, profiles, ends, bends and the cell cap. */
class LineKernelTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");

    private static PathSpec path(PathKind kind, int... xyz) {
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) points.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return new PathSpec(points, kind);
    }

    private GeneratedSource line(PathSpec path, int thickness, LineKernel.Profile profile) {
        return LineKernel.generate(new LineKernel.Spec(path, thickness, profile, new Pattern.Single(stone)), states, 1 << 20);
    }

    private static Set<String> cells(GeneratedSource source) {
        Set<String> cells = new HashSet<>();
        source.forEach((x, y, z, state) -> cells.add(x + "," + y + "," + z));
        return cells;
    }

    /** The (y, z) cells of the cross-section at x. */
    private static Set<String> section(GeneratedSource source, int x) {
        Set<String> cells = new HashSet<>();
        source.forEach((cx, y, z, state) -> {
            if (cx == x) cells.add(y + "," + z);
        });
        return cells;
    }

    @Test
    void aThinStraightLineIsTheCellsBetweenItsPoints() {
        GeneratedSource line = line(path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0), 1, LineKernel.Profile.ROUND);
        assertEquals(11, line.cells());
        for (int x = 0; x <= 10; x++) assertEquals(stone, line.get(x, 64, 0));
        assertEquals(new Box(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0)), line.bounds());
    }

    @Test
    void aThinDiagonalLineIsAChainOfTouchingCells() {
        GeneratedSource line = line(path(PathKind.STRAIGHT, 0, 64, 0, 7, 71, 7), 1, LineKernel.Profile.ROUND);
        for (int i = 0; i <= 7; i++) assertTrue(line.contains(i, 64 + i, i), "misses " + i);
        GeneratedSource steep = line(path(PathKind.STRAIGHT, 0, 64, 0, 3, 64, 17), 1, LineKernel.Profile.SQUARE);
        // Every z between the ends has a cell, and each cell touches the next one at least at a corner.
        List<int[]> ordered = new ArrayList<>();
        steep.forEach((x, y, z, state) -> ordered.add(new int[] {x, y, z}));
        ordered.sort((a, b) -> Integer.compare(a[2], b[2]));
        for (int z = 0; z <= 17; z++) {
            int zz = z;
            assertTrue(ordered.stream().anyMatch(c -> c[2] == zz), "no cell at z " + z);
        }
        for (int[] cell : ordered) {
            boolean touches = ordered.stream().anyMatch(o -> o != cell && Math.abs(o[0] - cell[0]) <= 1
                    && Math.abs(o[1] - cell[1]) <= 1 && Math.abs(o[2] - cell[2]) <= 1);
            assertTrue(touches, "a lone cell");
        }
    }

    @Test
    void roundAndSquareCrossSectionsHaveTheirThickness() {
        PathSpec along = path(PathKind.STRAIGHT, 0, 64, 0, 20, 64, 0);
        // 3 across: as the Shape brush's cylinders, a full 3 × 3.
        assertEquals(9, section(line(along, 3, LineKernel.Profile.ROUND), 10).size());
        assertEquals(9, section(line(along, 3, LineKernel.Profile.SQUARE), 10).size());
        // 5 across: round leaves the four corners out.
        Set<String> round = section(line(along, 5, LineKernel.Profile.ROUND), 10);
        assertEquals(21, round.size());
        assertFalse(round.contains((64 + 2) + "," + 2));
        assertTrue(round.contains((64 + 2) + "," + 1));
        assertEquals(25, section(line(along, 5, LineKernel.Profile.SQUARE), 10).size());
        // 16 across fits in 16 × 16.
        Set<String> wide = section(line(along, 16, LineKernel.Profile.SQUARE), 10);
        assertEquals(256, wide.size());
    }

    @Test
    void anEvenThicknessRunsHalfABlockTowardPositiveAndKeepsItsEnds() {
        GeneratedSource two = line(path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0), 2, LineKernel.Profile.ROUND);
        assertEquals(Set.of("64,0", "64,1", "65,0", "65,1"), section(two, 5));
        assertEquals(new Box(new BlockPos(0, 64, 0), new BlockPos(10, 65, 1)), two.bounds());
        GeneratedSource four = line(path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0), 4, LineKernel.Profile.ROUND);
        assertEquals(12, section(four, 5).size());
        assertEquals(new Box(new BlockPos(0, 63, -1), new BlockPos(10, 66, 2)), four.bounds());
    }

    @Test
    void openLinesEndFlatAtTheirPoints() {
        for (LineKernel.Profile profile : LineKernel.Profile.values()) {
            for (int thickness : new int[] {3, 7, 16}) {
                GeneratedSource line = line(path(PathKind.STRAIGHT, 0, 64, 0, 20, 64, 0), thickness, profile);
                assertEquals(0, line.bounds().min().x(), profile + " " + thickness);
                assertEquals(20, line.bounds().max().x(), profile + " " + thickness);
            }
        }
    }

    @Test
    void squareBendsAreFilledOnTheOutside() {
        // An L: along x, then along z. Its outer corner (x 11, z -1) lies past both segments' ends.
        GeneratedSource square = line(path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0, 10, 64, 10), 3, LineKernel.Profile.SQUARE);
        assertTrue(square.contains(11, 64, -1), "the outer corner is open");
        assertTrue(square.contains(11, 65, -1));
        GeneratedSource round = line(path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0, 10, 64, 10), 3, LineKernel.Profile.ROUND);
        // Round bends are round: the corner cell is 1.41 from the bend, within 1.5.
        assertTrue(round.contains(11, 64, -1));
        GeneratedSource wideRound = line(path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0, 10, 64, 10), 5,
                LineKernel.Profile.ROUND);
        assertFalse(wideRound.contains(12, 64, -2), "a round bend has a square corner");
        assertTrue(line(path(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0, 10, 64, 10), 5, LineKernel.Profile.SQUARE)
                .contains(12, 64, -2));
    }

    @Test
    void curvedAndHangingLinesAreContinuousTubes() {
        PathSpec curve = path(PathKind.CURVE, 0, 64, 0, 10, 70, 6, 20, 64, -6, 30, 60, 0);
        PathSpec hanging = new PathSpec(curve.points(), PathKind.HANGING, 8);
        for (PathSpec spec : List.of(curve, hanging)) {
            GeneratedSource line = line(spec, 3, LineKernel.Profile.ROUND);
            // Every x slice between the ends holds cells, and the ends' centres are in.
            for (int x = 0; x <= 30; x++) assertFalse(section(line, x).isEmpty(), spec.kind() + " gap at x " + x);
            assertTrue(line.contains(0, 64, 0) && line.contains(30, 60, 0), spec.kind() + " misses an end");
        }
    }

    @Test
    void aLinePointAloneIsABallOrACube() {
        GeneratedSource ball = line(path(PathKind.CURVE, 5, 64, 5, 5, 64, 5), 3, LineKernel.Profile.ROUND);
        assertEquals(19, ball.cells());
        GeneratedSource cube = line(path(PathKind.STRAIGHT, 5, 64, 5, 5, 64, 5), 3, LineKernel.Profile.SQUARE);
        assertEquals(27, cube.cells());
        assertEquals(1, line(path(PathKind.STRAIGHT, 5, 64, 5, 5, 64, 5), 1, LineKernel.Profile.ROUND).cells());
    }

    @Test
    void aClosedCurveHasNoEnds() {
        PathSpec loop = path(PathKind.CURVE, 0, 64, 0, 20, 64, 0, 20, 64, 20, 0, 64, 20, 0, 64, 0);
        // Around its start the loop is whole: every cell within the thickness of the start is laid (an open line ends
        // flat there, leaving out the half behind it).
        Set<String> ball = cells(line(path(PathKind.CURVE, 0, 64, 0, 0, 64, 0), 3, LineKernel.Profile.ROUND));
        assertTrue(cells(line(loop, 3, LineKernel.Profile.ROUND)).containsAll(ball), "the loop ends at its start");
        PathSpec open = path(PathKind.CURVE, 0, 64, 0, 20, 64, 0, 20, 64, 20, 0, 64, 20, 0, 64, 1);
        assertFalse(cells(line(open, 3, LineKernel.Profile.ROUND)).containsAll(ball));
    }

    @Test
    void theMaterialDecidesEachCell() {
        Pattern.Weighted mix = new Pattern.Weighted(new int[] {stone, dirt}, new int[] {1, 1}, 42);
        GeneratedSource line = LineKernel.generate(new LineKernel.Spec(path(PathKind.STRAIGHT, 0, 64, 0, 40, 64, 0), 1,
                LineKernel.Profile.ROUND, mix), states, 1000);
        Set<Integer> seen = new HashSet<>();
        line.forEach((x, y, z, state) -> {
            assertEquals(mix.apply(states, x, y, z, -1), state);
            seen.add(state);
        });
        assertEquals(Set.of(stone, dirt), seen);
        assertThrows(IllegalArgumentException.class, () -> new LineKernel.Spec(path(PathKind.STRAIGHT, 0, 0, 0, 1, 0, 0), 1,
                LineKernel.Profile.ROUND, new Pattern.Dry()));
        assertThrows(IllegalArgumentException.class, () -> new LineKernel.Spec(path(PathKind.STRAIGHT, 0, 0, 0, 1, 0, 0), 17,
                LineKernel.Profile.ROUND, new Pattern.Single(stone)));
        assertThrows(IllegalArgumentException.class, () -> new LineKernel.Spec(path(PathKind.STRAIGHT, 0, 0, 0, 1, 0, 0), 0,
                LineKernel.Profile.ROUND, new Pattern.Single(stone)));
    }

    @Test
    void aLineOverTheCapIsRefused() {
        LineKernel.Spec spec = new LineKernel.Spec(path(PathKind.STRAIGHT, 0, 64, 0, 100, 64, 0), 3, LineKernel.Profile.ROUND,
                new Pattern.Single(stone));
        assertEquals(909, LineKernel.generate(spec, states, 909).cells());
        GeneratedTooLargeException e = assertThrows(GeneratedTooLargeException.class,
                () -> LineKernel.generate(spec, states, 908));
        assertEquals(908, e.maxCells());
        // A line far longer than the cap is refused before it is sampled.
        LineKernel.Spec huge = new LineKernel.Spec(path(PathKind.CURVE, -20_000_000, 64, 0, 20_000_000, 64, 0), 1,
                LineKernel.Profile.ROUND, new Pattern.Single(stone));
        assertThrows(GeneratedTooLargeException.class, () -> LineKernel.generate(huge, states, 1_000_000));
    }

    @Test
    void cellsOutsideTheBuildHeightAreLeftOut() {
        LineKernel.Spec spec = new LineKernel.Spec(path(PathKind.STRAIGHT, 0, 310, 0, 0, 330, 0), 3,
                LineKernel.Profile.SQUARE, new Pattern.Single(stone));
        GeneratedSource clipped = LineKernel.generate(spec, states, 1000, -64, 320);
        assertEquals(319, clipped.bounds().max().y());
        assertEquals(9 * 10, clipped.cells());
        assertEquals(9 * 21, LineKernel.generate(spec, states, 1000).cells());
    }
}
