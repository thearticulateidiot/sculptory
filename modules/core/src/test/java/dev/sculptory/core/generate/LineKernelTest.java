package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSampler;
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

    /**
     * A Curve whose points are in a line keeps the spline's sample spacing (it is not sampled as a Straight line, whose
     * evenly spaced samples can land on block corners): its cells are the ones it had before the exact straight sampling,
     * recorded here from that code.
     */
    @Test
    void aCurveThroughPointsInALineKeepsItsCells() {
        // Diagonal, with unevenly spaced points: as a Straight line these took the blocks beside each corner too.
        assertEquals(Set.of("0,0,0", "-1,0,1", "-2,0,2", "-3,0,3"),
                cells(line(path(PathKind.CURVE, 0, 0, 0, -1, 0, 1, -3, 0, 3), 1, LineKernel.Profile.ROUND)));
        assertEquals(Set.of("0,0,0", "1,0,-1", "2,0,-2", "3,0,-3"),
                cells(line(path(PathKind.CURVE, 0, 0, 0, 1, 0, -1, 3, 0, -3), 1, LineKernel.Profile.ROUND)));
        assertEquals(Set.of("0,0,0", "1,0,0", "1,1,0", "2,1,0", "3,1,0", "3,2,0", "4,2,0", "5,2,0", "5,3,0", "6,3,0",
                        "7,3,0", "7,4,0", "8,4,0", "9,4,0", "9,5,0", "10,5,0", "11,5,0", "11,6,0", "12,6,0"),
                cells(line(path(PathKind.CURVE, 0, 0, 0, 4, 2, 0, 12, 6, 0), 1, LineKernel.Profile.ROUND)));
        assertEquals(Set.of("0,0,0", "1,0,1", "2,0,2", "3,0,3", "4,0,4", "5,0,5", "6,0,6", "7,0,7", "8,0,8", "9,0,9"),
                cells(line(path(PathKind.CURVE, 0, 0, 0, 3, 0, 3, 9, 0, 9), 1, LineKernel.Profile.ROUND)));
        assertEquals(Set.of("0,0,0", "0,0,1", "0,1,0", "0,1,1", "1,0,0", "1,0,1", "1,0,2", "1,1,0", "1,1,1", "1,1,2",
                        "1,1,3", "1,2,3", "2,0,2", "2,1,2", "2,1,3", "2,1,4", "2,1,5", "2,2,3", "2,2,4", "2,2,5", "3,1,4",
                        "3,1,5", "3,1,6", "3,2,4", "3,2,5", "3,2,6", "3,2,7", "3,3,7", "4,1,6", "4,2,6", "4,2,7", "4,2,8",
                        "4,2,9", "4,3,7", "4,3,8", "4,3,9", "5,2,10", "5,2,8", "5,2,9", "5,3,10", "5,3,11", "5,3,8", "5,3,9",
                        "5,4,11", "6,2,10", "6,3,10", "6,3,11", "6,3,12", "6,4,11"),
                cells(line(path(PathKind.CURVE, 0, 0, 0, 2, 1, 4, 6, 3, 12), 2, LineKernel.Profile.SQUARE)));
        Set<String> tube = new HashSet<>();
        for (int x = 0; x <= 16; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) tube.add(x + "," + y + "," + z);
            }
        }
        assertEquals(tube, cells(line(path(PathKind.CURVE, 0, 0, 0, 5, 0, 0, 16, 0, 0), 3, LineKernel.Profile.ROUND)));
    }

    /**
     * A Straight line's samples are exact: from (0, 0, 0) to (-1, 0, 1) the middle one of its 6 steps is exactly on the
     * corner between the two end blocks, and counts toward +x and +z ({@code floor}), so the line also takes block
     * (0, 0, 1). That bias toward the + side of a boundary a sample lies on is how Straight lines have always behaved.
     */
    @Test
    void aStraightLineSampleOnACornerIsExactlyThere() {
        assertEquals(Set.of("0,0,0", "0,0,1", "-1,0,1"),
                cells(line(path(PathKind.STRAIGHT, 0, 0, 0, -1, 0, 1), 1, LineKernel.Profile.ROUND)));
        List<double[]> corner = new ArrayList<>();
        PathSampler.visit(path(PathKind.STRAIGHT, 0, 0, 0, -1, 0, 1), LineKernel.CORE_SPACING, (x, y, z, along) -> {
            if (x == 0 && z == 1) corner.add(new double[] {x, y, z});
        });
        assertEquals(1, corner.size(), "one sample exactly on the corner");
    }

    /**
     * A thin line rising half a block a block (101 to 107 over 13 blocks) rises through a block boundary in the middle
     * of every odd block (no sample lands exactly there): it steps up at blocks 1, 3, 5, 7, 9 and 11, where Use Stairs
     * and Slabs puts a stair, wherever it is drawn (the moves of {@link #aLineDrawnElsewhereIsTheSameLineMoved}), along x
     * and along z.
     */
    @Test
    void aLineRisingHalfABlockABlockStepsAtEveryOddBlockAnywhere() {
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
        for (int k = 1; k <= 11; k += 2) expected.add(k + "," + (101 + (k + 1) / 2));
        for (int dx : new int[] {-4096, 16, 1_000_000, 2303 * 4096, 29_000_000}) {
            for (int step = -1024; step <= 1024; step += 16) {
                int dz = step, dy = (Math.floorMod(dz / 16, 3) - 1) * 20;
                for (boolean alongZ : new boolean[] {false, true}) {
                    BlockPos end = alongZ ? new BlockPos(dx, 107 + dy, dz + 12) : new BlockPos(dx + 12, 107 + dy, dz);
                    PathSpec path = new PathSpec(List.of(new BlockPos(dx, 101 + dy, dz), end), PathKind.STRAIGHT);
                    GeneratedSource line = LineKernel.generate(new LineKernel.Spec(path, 1, LineKernel.Profile.ROUND,
                            new Pattern.Single(bricks)), states, 1 << 16);
                    int stair = alongZ ? south : east;
                    Set<String> found = new HashSet<>();
                    SlopeSteps.apply(line, SlopeSteps.along(PathSampler.sample(path, 0.5)), variants, states.air())
                            .source().forEach((x, y, z, state) -> {
                                if (state == stair) found.add((alongZ ? z - dz : x - dx) + "," + (y - dy));
                            });
                    assertEquals(expected, found, "stairs " + (alongZ ? "along z" : "along x") + " moved by " + dx + ", "
                            + dy + ", " + dz);
                }
            }
        }
    }

    /**
     * The same line drawn anywhere is the same line moved there: its samples' rounding noise grows with the size of
     * their coordinates, and these lines have cell centres exactly on their edge and samples exactly on block
     * boundaries. Each is drawn from (0, 0, 0) and moved by whole blocks up to 29 million out (and 20 up or down); its
     * cells moved back must be the very same.
     */
    @Test
    void aLineDrawnElsewhereIsTheSameLineMoved() {
        checkMoved("thin, rising half a block a block", path(PathKind.STRAIGHT, 0, 101, 0, 12, 107, 0), 1,
                LineKernel.Profile.ROUND);
        checkMoved("thick, along x", path(PathKind.STRAIGHT, 0, 104, 0, 16, 104, 0), 3, LineKernel.Profile.SQUARE);
        checkMoved("even, diagonal", path(PathKind.STRAIGHT, 0, 100, 0, 9, 104, 7), 2, LineKernel.Profile.ROUND);
        checkMoved("a round curve", path(PathKind.CURVE, 0, 100, 0, 8, 103, 5, 16, 101, -3), 3, LineKernel.Profile.ROUND);
        checkMoved("an even square curve", path(PathKind.CURVE, 0, 100, 0, 8, 103, 5, 16, 101, -3), 4,
                LineKernel.Profile.SQUARE);
    }

    private void checkMoved(String what, PathSpec path, int thickness, LineKernel.Profile profile) {
        Set<String> reference = cells(line(path, thickness, profile));
        for (int dx : new int[] {-4096, 16, 1_000_000, 2303 * 4096, 29_000_000}) {
            for (int step = -1024; step <= 1024; step += 16) {
                int dz = step, dy = (Math.floorMod(dz / 16, 3) - 1) * 20;
                List<BlockPos> points = new ArrayList<>();
                for (BlockPos p : path.points()) points.add(new BlockPos(p.x() + dx, p.y() + dy, p.z() + dz));
                Set<String> moved = new HashSet<>();
                line(new PathSpec(points, path.kind()), thickness, profile).forEach((x, y, z, state) ->
                        moved.add((x - dx) + "," + (y - dy) + "," + (z - dz)));
                assertEquals(reference, moved, what + ": the line moved by " + dx + ", " + dy + ", " + dz);
            }
        }
    }
}
