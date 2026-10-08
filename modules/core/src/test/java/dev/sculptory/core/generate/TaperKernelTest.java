package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Extrude to a point: pyramids, cones, leaning spires, the three curves, refusals and caps. */
class TaperKernelTest {
    private static final int BOTTOM = -64, TOP = 320;
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int chest = states.state("minecraft:chest[facing=north]");

    /** A square floor face of side {@code n} at y 64 from (0, 0), facing up; dirt in its middle cell, stone else. */
    private FaceCells square(int n) {
        List<int[]> cells = new ArrayList<>();
        for (int x = 0; x < n; x++) {
            for (int z = 0; z < n; z++) cells.add(new int[] {x, 64, z, x == n / 2 && z == n / 2 ? dirt : stone});
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

    private GeneratedResult taper(FaceCells face, BlockPos tip, TaperKernel.Curve curve) {
        return TaperKernel.generate(new TaperKernel.Spec(face, tip, curve), states, 1 << 20, BOTTOM, TOP);
    }

    /** Cells per layer (y), from the source. */
    private static Map<Integer, Integer> layers(GeneratedSource source) {
        Map<Integer, Integer> counts = new HashMap<>();
        source.forEach((x, y, z, state) -> counts.merge(y, 1, Integer::sum));
        return counts;
    }

    @Test
    void aSquareFaceTapersIntoAPyramidEndingOnItsTip() {
        GeneratedSource pyramid = taper(square(5), new BlockPos(2, 69, 2), TaperKernel.Curve.STRAIGHT).source();
        assertEquals(Map.of(65, 16, 66, 9, 67, 4, 68, 1, 69, 1), layers(pyramid));
        assertEquals(dirt, pyramid.get(2, 69, 2), "the tip takes the face's middle block");
        // Each layer is a scaled copy: its cells take the face cells they map back to.
        assertEquals(stone, pyramid.get(0, 65, 0));
        assertTrue(pyramid.contains(2, 68, 2));
    }

    @Test
    void aRoundFaceMakesAConeAndAnOffCentreTipALeaningSpire() {
        List<int[]> disc = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) if (x * x + z * z <= 16) disc.add(new int[] {x, 64, z, stone});
        }
        GeneratedSource cone = taper(face(1, 1, disc), new BlockPos(0, 76, 0), TaperKernel.Curve.STRAIGHT).source();
        Map<Integer, Integer> counts = layers(cone);
        for (int y = 66; y <= 76; y++) assertTrue(counts.get(y) <= counts.get(y - 1), "narrows at " + y);
        assertTrue(cone.contains(0, 76, 0));
        // Leaning: the tip 10 blocks east; each layer's middle moves toward it.
        GeneratedSource spire = taper(face(1, 1, disc), new BlockPos(10, 74, 0), TaperKernel.Curve.STRAIGHT).source();
        assertTrue(spire.contains(10, 74, 0), "it ends on its tip");
        double previous = -1;
        for (int y = 65; y <= 73; y++) {
            double sum = 0;
            int n = 0;
            int layer = y;
            List<Integer> xs = new ArrayList<>();
            spire.forEach((x, cy, z, state) -> {
                if (cy == layer) xs.add(x);
            });
            for (int x : xs) {
                sum += x;
                n++;
            }
            double middle = sum / n;
            assertTrue(middle > previous, "the middle moves east at " + y);
            previous = middle;
        }
    }

    @Test
    void bulgeStaysFullerThanStraightAndPinchThinner() {
        Map<Integer, Integer> straight = layers(taper(square(9), new BlockPos(4, 72, 4), TaperKernel.Curve.STRAIGHT).source());
        Map<Integer, Integer> bulge = layers(taper(square(9), new BlockPos(4, 72, 4), TaperKernel.Curve.BULGE).source());
        Map<Integer, Integer> pinch = layers(taper(square(9), new BlockPos(4, 72, 4), TaperKernel.Curve.PINCH).source());
        for (int y = 65; y <= 72; y++) {
            assertTrue(bulge.get(y) >= straight.get(y), "bulge at " + y);
            assertTrue(pinch.get(y) <= straight.get(y), "pinch at " + y);
        }
        assertTrue(bulge.get(70) > straight.get(70) && pinch.get(67) < straight.get(67));
    }

    @Test
    void aWallFaceTapersSidewaysAndTheSameSpecGivesTheSameCells() {
        List<int[]> wall = new ArrayList<>();
        for (int y = 64; y <= 68; y++) {
            for (int z = 0; z <= 4; z++) wall.add(new int[] {0, y, z, stone});
        }
        FaceCells west = face(0, -1, wall);
        GeneratedSource a = taper(west, new BlockPos(-6, 66, 2), TaperKernel.Curve.PINCH).source();
        a.forEach((x, y, z, state) -> assertTrue(x < 0 && x >= -6));
        assertTrue(a.contains(-6, 66, 2));
        Map<String, Integer> first = new HashMap<>();
        a.forEach((x, y, z, state) -> first.put(x + "," + y + "," + z, state));
        Map<String, Integer> again = new HashMap<>();
        taper(face(0, -1, wall), new BlockPos(-6, 66, 2), TaperKernel.Curve.PINCH).source()
                .forEach((x, y, z, state) -> again.put(x + "," + y + "," + z, state));
        assertEquals(first, again);
    }

    @Test
    void refusalsCapsTheBuildHeightAndBlockEntities() {
        assertThrows(IllegalArgumentException.class, () -> taper(square(3), new BlockPos(1, 64, 1), TaperKernel.Curve.STRAIGHT),
                "a tip in the face's plane");
        assertThrows(IllegalArgumentException.class, () -> taper(square(3), new BlockPos(1, 60, 1), TaperKernel.Curve.STRAIGHT),
                "a tip behind the face");
        assertThrows(GeneratedTooLargeException.class, () -> TaperKernel.generate(new TaperKernel.Spec(square(20),
                new BlockPos(10, 100, 10), TaperKernel.Curve.STRAIGHT), states, 500, BOTTOM, TOP));
        GeneratedSource clipped = TaperKernel.generate(new TaperKernel.Spec(square(5), new BlockPos(2, 69, 2),
                TaperKernel.Curve.STRAIGHT), states, 1 << 20, BOTTOM, 67).source();
        assertEquals(Map.of(65, 16, 66, 9), layers(clipped));
        List<int[]> withChest = new ArrayList<>();
        for (int x = 0; x < 3; x++) withChest.add(new int[] {x, 64, 0, x == 0 ? chest : stone});
        // Two blocks far apart: few cells laid, a vast area looked at; refused rather than scanned.
        List<int[]> sparse = List.of(new int[] {0, 64, 0, stone}, new int[] {600, 64, 600, stone});
        assertThrows(GeneratedTooLargeException.class, () -> TaperKernel.generate(new TaperKernel.Spec(face(1, 1, sparse),
                new BlockPos(300, 100, 300), TaperKernel.Curve.STRAIGHT), states, 1_000, BOTTOM, TOP));
        GeneratedResult result = taper(face(1, 1, withChest), new BlockPos(1, 66, 0), TaperKernel.Curve.STRAIGHT);
        assertEquals(1, result.leftOut());
        result.source().forEach((x, y, z, state) -> assertEquals(stone, state));
    }
}
