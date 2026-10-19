package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSpec;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Paths from the ground into the sky: straight up 32 blocks, up and over, and a diagonal climb, for Extrude along a
 * path (wall face upright and following the slope, floor face), Sweep and Line. Every result is one connected body
 * with blocks at every height it climbs through, and a straight vertical stretch lays the same layer at every height
 * (no flips or twists on the way up).
 */
class SteepPathsTest {
    private static final int BOTTOM = -64, TOP = 320;
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int stairsEast = states.state("minecraft:oak_stairs[facing=east]");

    // ---- Shapes ----

    /** A wall face looking east at x: y in y0..y1, z in z0..z1; stairs on its top row, dirt on its z0 column. */
    private FaceCells eastWall(int x, int y0, int y1, int z0, int z1) {
        List<int[]> cells = new ArrayList<>();
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) cells.add(new int[] {x, y, z, y == y1 ? stairsEast : z == z0 ? dirt : stone});
        }
        return face(0, 1, cells);
    }

    /** A floor face at y: x in x0..x1, z in z0..z1, facing up; dirt on its x0 column, stairs at (x1, z1), stone else. */
    private FaceCells floor(int x0, int x1, int y, int z0, int z1) {
        List<int[]> cells = new ArrayList<>();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                cells.add(new int[] {x, y, z, x == x0 ? dirt : x == x1 && z == z1 ? stairsEast : stone});
            }
        }
        return face(1, 1, cells);
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

    /** A profile one block thin at x = 100: z -1..1, y 70..71; stairs on top in the middle, dirt on its south side. */
    private SweepKernel.Profile slice() {
        List<int[]> cells = new ArrayList<>();
        for (int y = 70; y <= 71; y++) {
            for (int z = -1; z <= 1; z++) {
                cells.add(new int[] {100, y, z, y == 71 && z == 0 ? stairsEast : z == 1 ? dirt : stone});
            }
        }
        int n = cells.size();
        int[] xs = new int[n], ys = new int[n], zs = new int[n], s = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = cells.get(i)[0];
            ys[i] = cells.get(i)[1];
            zs[i] = cells.get(i)[2];
            s[i] = cells.get(i)[3];
        }
        return new SweepKernel.Profile(xs, ys, zs, s);
    }

    private static List<BlockPos> points(int... xyz) {
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) points.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return points;
    }

    private static double sag(PathKind kind) {
        return kind == PathKind.HANGING ? 4 : 0;
    }

    private GeneratedSource extrude(FaceCells face, PathKind kind, boolean followSlope, int... xyz) {
        return PathExtrudeKernel.generate(new PathExtrudeKernel.Spec(face, points(xyz), kind, sag(kind), followSlope),
                states, 1 << 20, BOTTOM, TOP).source();
    }

    private GeneratedSource sweep(PathKind kind, boolean followSlope, int... xyz) {
        return SweepKernel.generate(new SweepKernel.Spec(slice(), points(xyz), kind, sag(kind), followSlope), states,
                1 << 20, BOTTOM, TOP).source();
    }

    private GeneratedSource line(int thickness, LineKernel.Profile profile, PathKind kind, int... xyz) {
        return LineKernel.generate(new LineKernel.Spec(new PathSpec(points(xyz), kind, sag(kind)), thickness, profile,
                new Pattern.Single(stone)), states, 1 << 20);
    }

    // ---- Checks ----

    /** Per height, the layer's cells as "x,z=state", sorted. */
    private static TreeMap<Integer, Set<String>> layers(GeneratedSource source) {
        TreeMap<Integer, Set<String>> layers = new TreeMap<>();
        source.forEach((x, y, z, state) -> layers.computeIfAbsent(y, k -> new TreeSet<>()).add(x + "," + z + "=" + state));
        return layers;
    }

    /** Not empty, one 26-connected body, a layer at every height from {@code y0} to {@code y1}. */
    private static void solid(String what, GeneratedSource source, int y0, int y1) {
        assertFalse(source.isEmpty(), what + ": empty");
        Set<List<Integer>> cells = new HashSet<>();
        source.forEach((x, y, z, state) -> cells.add(List.of(x, y, z)));
        List<Integer> start = cells.iterator().next();
        Set<List<Integer>> seen = new HashSet<>();
        ArrayDeque<List<Integer>> queue = new ArrayDeque<>();
        queue.add(start);
        seen.add(start);
        while (!queue.isEmpty()) {
            List<Integer> c = queue.poll();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        List<Integer> n = List.of(c.get(0) + dx, c.get(1) + dy, c.get(2) + dz);
                        if (cells.contains(n) && seen.add(n)) queue.add(n);
                    }
                }
            }
        }
        assertEquals(cells.size(), seen.size(), what + ": not one connected body\n" + dump(source));
        TreeMap<Integer, Set<String>> layers = layers(source);
        for (int y = y0; y <= y1; y++) assertTrue(layers.containsKey(y), what + ": nothing at y " + y + " " + layers.keySet());
    }

    /** Every layer from {@code y0} to {@code y1} is the same (cells and states). */
    private static void sameLayers(String what, GeneratedSource source, int y0, int y1) {
        TreeMap<Integer, Set<String>> layers = layers(source);
        Set<String> first = layers.get(y0);
        assertTrue(first != null && !first.isEmpty(), what + ": nothing at y " + y0);
        for (int y = y0 + 1; y <= y1; y++) {
            assertEquals(first, layers.get(y), what + ": layer " + y + " differs from " + y0 + "\n" + dump(source));
        }
    }

    /**
     * A straight line's vertical stretch: the same layer all the way ({@link #sameLayers}). A curve's may drift a block
     * aside as it climbs (a Curve overshoots), so there every cell keeps to the states its row across z holds
     * ({@code rows}, z to states): a section flipped or twisted on the way up would swap its sides or turn its stairs.
     */
    private static void steady(PathKind kind, String what, GeneratedSource source, int y0, int y1,
                               Map<Integer, Set<Integer>> rows) {
        if (kind == PathKind.STRAIGHT) {
            sameLayers(what, source, y0, y1);
            return;
        }
        source.forEach((x, y, z, state) -> {
            if (y < y0 || y > y1) return;
            Set<Integer> allowed = rows.get(z);
            assertTrue(allowed != null && allowed.contains(state),
                    what + ": " + x + "," + y + "," + z + " holds " + state + ", row " + z + " holds " + allowed + "\n"
                            + dump(source));
        });
    }

    /**
     * A line's vertical stretch: straight, the same layer all the way; a curve's, drifting aside as it climbs, keeps its
     * cross-section's size within a row of cells ({@code thickness}) of the first layer's (a tear or a pinch would not).
     */
    private static void steadySize(PathKind kind, String what, GeneratedSource source, int y0, int y1, int thickness) {
        if (kind == PathKind.STRAIGHT) {
            sameLayers(what, source, y0, y1);
            return;
        }
        TreeMap<Integer, Set<String>> layers = layers(source);
        int first = layers.get(y0).size();
        for (int y = y0; y <= y1; y++) {
            int size = layers.get(y) == null ? 0 : layers.get(y).size();
            assertTrue(Math.abs(size - first) <= thickness,
                    what + ": layer " + y + " holds " + size + " cells, layer " + y0 + " " + first + "\n" + dump(source));
        }
    }

    /**
     * A face carried a block aside and then straight up: the same layer all the way, except that a Curve (centripetal)
     * leans up to a block toward that first short step partway up the climb, so there each layer keeps its size within
     * a row of cells ({@link #steadySize}) and every cell lies within one cell, across, of the first layer's.
     */
    private static void uprightLayers(PathKind kind, String what, GeneratedSource source, int y0, int y1) {
        if (kind == PathKind.CURVE) {
            steadySize(kind, what, source, y0, y1, 3);
            Set<Long> first = new java.util.HashSet<>();
            source.forEach((x, y, z, state) -> {
                if (y == y0) first.add(GroundMap.column(x, z));
            });
            source.forEach((x, y, z, state) -> {
                if (y < y0 || y > y1) return;
                boolean near = false;
                for (int dx = -1; dx <= 1 && !near; dx++) {
                    for (int dz = -1; dz <= 1 && !near; dz++) near = first.contains(GroundMap.column(x + dx, z + dz));
                }
                assertTrue(near, what + ": " + x + "," + y + "," + z + " drifts more than a cell" + "\n" + dump(source));
            });
        } else {
            sameLayers(what, source, y0, y1);
        }
    }

    // ---- Steep starts that drift aside ----

    /** The block states in {@code source} other than stone and dirt (the stairs, as they were turned). */
    private Set<Integer> stairStates(GeneratedSource source) {
        Set<Integer> found = new TreeSet<>();
        source.forEach((x, y, z, state) -> {
            if (state != stone && state != dirt) found.add(state);
        });
        return found;
    }

    @Test
    void aWallClimbingSteeplyAsideTurnsItsStairsWithItFromTheStart() {
        // An east-facing wall; the path climbs 30 steeply north, drifting a little west, then runs level north. The
        // section turns to the north from the start, and so do its stairs: one facing all the way.
        FaceCells face = eastWall(0, 64, 66, 0, 2);
        for (boolean follow : new boolean[] {false, true}) {
            for (PathKind kind : PathKind.values()) {
                GeneratedSource climb = extrude(face, kind, follow, -1, 95, -9, -1, 95, -25);
                assertEquals(1, stairStates(climb).size(), kind + " follow " + follow + ": stairs " + stairStates(climb)
                        + "\n" + dump(climb));
            }
        }
    }

    @Test
    void aSweepClimbingSteeplyAsideTurnsItsStairsWithItFromTheStart() {
        for (boolean follow : new boolean[] {false, true}) {
            for (PathKind kind : PathKind.values()) {
                GeneratedSource climb = sweep(kind, follow, 0, 63, 0, -1, 93, -10, -1, 93, -26);
                assertEquals(1, stairStates(climb).size(), kind + " follow " + follow + ": stairs " + stairStates(climb)
                        + "\n" + dump(climb));
            }
        }
    }

    private static String dump(GeneratedSource source) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<Integer, Set<String>> e : layers(source).entrySet()) {
            out.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        return out.toString();
    }

    // ---- Extrude along a path ----

    @Test
    void aWallFaceStraightUpStaysOneUprightSlab() {
        FaceCells face = eastWall(0, 64, 66, 0, 2);
        for (PathKind kind : PathKind.values()) {
            GeneratedSource up = extrude(face, kind, false, 1, 65, 1, 1, 97, 1);
            solid("upright " + kind, up, 64, 98);
            uprightLayers(kind, "upright " + kind, up, 70, 92);
        }
    }

    @Test
    void aWallFaceFollowingTheSlopeStraightUpIsAPillar() {
        FaceCells face = eastWall(0, 64, 66, 0, 2);
        for (PathKind kind : PathKind.values()) {
            GeneratedSource up = extrude(face, kind, true, 1, 65, 1, 1, 97, 1);
            solid("follow slope " + kind, up, 64, 97);
            uprightLayers(kind, "follow slope " + kind, up, 70, 92);
        }
    }

    @Test
    void aWallFaceUpAndOverHasNoFlipOnTheWayUp() {
        FaceCells face = eastWall(0, 64, 66, 0, 2);
        for (boolean follow : new boolean[] {false, true}) {
            for (PathKind kind : PathKind.values()) {
                GeneratedSource over = extrude(face, kind, follow, 2, 65, 1, 2, 97, 1, 14, 97, 1);
                solid("over " + kind + " " + follow, over, 64, 97);
                steady(kind, "over " + kind + " follow " + follow, over, 72, 88,
                        Map.of(0, Set.of(dirt, stairsEast), 1, Set.of(stone, stairsEast), 2, Set.of(stone, stairsEast)));
            }
        }
    }

    @Test
    void aWallFaceClimbsADiagonalWithoutHoles() {
        FaceCells face = eastWall(0, 64, 66, 0, 2);
        for (boolean follow : new boolean[] {false, true}) {
            for (PathKind kind : PathKind.values()) {
                solid("diagonal " + kind + " " + follow, extrude(face, kind, follow, 21, 85, 1), 65, 86);
                solid("steep diagonal " + kind + " " + follow, extrude(face, kind, follow, 15, 95, 9), 65, 95);
            }
        }
    }

    @Test
    void aFloorFaceStraightUpAndOverIsAColumnWithoutTwist() {
        FaceCells face = floor(0, 2, 64, 0, 2);
        for (PathKind kind : PathKind.values()) {
            GeneratedSource up = extrude(face, kind, false, 1, 96, 1);
            solid("floor up " + kind, up, 65, 96);
            sameLayers("floor up " + kind, up, 65, 96);
            GeneratedSource over = extrude(face, kind, false, 1, 96, 1, 13, 96, 1);
            solid("floor over " + kind, over, 65, 96);
            steady(kind, "floor over " + kind, over, 65, 86,
                        Map.of(0, Set.of(dirt, stone), 1, Set.of(dirt, stone), 2, Set.of(dirt, stone, stairsEast)));
            solid("floor diagonal " + kind, extrude(face, kind, false, 21, 85, 1), 65, 86);
        }
    }

    // ---- Sweep ----

    @Test
    void aSweepStraightUpAndOverAndAlongADiagonal() {
        for (boolean follow : new boolean[] {false, true}) {
            for (PathKind kind : PathKind.values()) {
                String what = kind + " follow " + follow;
                GeneratedSource up = sweep(kind, follow, 0, 63, 0, 0, 95, 0);
                solid("sweep up " + what, up, 64, 95);
                sameLayers("sweep up " + what, up, 68, 92);
                GeneratedSource over = sweep(kind, follow, 0, 63, 0, 0, 95, 0, 12, 95, 0);
                solid("sweep over " + what, over, 64, 96);
                steady(kind, "sweep over " + what, over, 70, 86,
                        Map.of(-1, Set.of(stone), 0, Set.of(stone, stairsEast), 1, Set.of(dirt)));
                solid("sweep ramp " + what, sweep(kind, follow, 0, 63, 0, 20, 83, 0), 64, 84);
            }
        }
    }

    // ---- Line ----

    @Test
    void aLineStraightUpAndOverAndAlongADiagonal() {
        for (int thickness : new int[] {1, 3, 4}) {
            for (LineKernel.Profile profile : LineKernel.Profile.values()) {
                for (PathKind kind : PathKind.values()) {
                    String what = thickness + " " + profile + " " + kind;
                    GeneratedSource up = line(thickness, profile, kind, 0, 64, 0, 0, 96, 0);
                    solid("line up " + what, up, 64, 96);
                    sameLayers("line up " + what, up, 66, 94);
                    GeneratedSource over = line(thickness, profile, kind, 0, 64, 0, 0, 96, 0, 12, 96, 0);
                    solid("line over " + what, over, 64, 96);
                    steadySize(kind, "line over " + what, over, 70, 88, thickness);
                    solid("line ramp " + what, line(thickness, profile, kind, 0, 64, 0, 20, 84, 13), 64, 84);
                }
            }
        }
    }
}
