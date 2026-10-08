package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.path.PathSampler;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Generate's Sweep: the profile stands on the line, turns with it, tilts with the slope when asked, repeats, closes. */
class SweepKernelTest {
    private static final int BOTTOM = -64, TOP = 320;
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int air = states.air();
    private final int stairsEast = states.state("minecraft:oak_stairs[facing=east]");

    /**
     * A one-block-thin profile at x = 100, far from the line: z -1..1, y 70..71; stairs facing east on top in the middle,
     * dirt on its south side, stone else, and air in its top north corner.
     */
    private SweepKernel.Profile slice() {
        List<int[]> cells = new ArrayList<>();
        for (int y = 70; y <= 71; y++) {
            for (int z = -1; z <= 1; z++) {
                int state = y == 71 && z == 0 ? stairsEast : y == 71 && z == -1 ? air : z == 1 ? dirt : stone;
                cells.add(new int[] {100, y, z, state});
            }
        }
        return profile(cells);
    }

    private static SweepKernel.Profile profile(List<int[]> cells) {
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

    private GeneratedResult sweep(SweepKernel.Profile profile, PathKind kind, boolean followSlope, int... xyz) {
        return SweepKernel.generate(spec(profile, kind, followSlope, xyz), states, 1 << 20, BOTTOM, TOP);
    }

    private static SweepKernel.Spec spec(SweepKernel.Profile profile, PathKind kind, boolean followSlope, int... xyz) {
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) points.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return new SweepKernel.Spec(profile, points, kind, 0, followSlope);
    }

    @Test
    void aSliceSweptStraightEastStandsOnTheLineAsItWasBuilt() {
        GeneratedSource source = sweep(slice(), PathKind.STRAIGHT, false, 0, 63, 0, 9, 63, 0).source();
        assertEquals(10 * 5, source.cells(), "ten blocks along, five cells across (no air)");
        for (int x = 0; x <= 9; x++) {
            assertEquals(stone, source.get(x, 64, 0), "the bottom stands on the clicked block");
            assertEquals(dirt, source.get(x, 64, 1));
            assertEquals(stairsEast, source.get(x, 65, 0));
            assertFalse(source.contains(x, 65, -1), "air is not swept");
        }
        assertFalse(source.contains(-1, 64, 0) || source.contains(10, 64, 0), "half a block past each end");
    }

    @Test
    void headingSouthTheProfileAndItsStairsTurnAQuarter() {
        GeneratedSource source = sweep(slice(), PathKind.STRAIGHT, false, 0, 63, 0, 0, 63, 9).source();
        for (int z = 0; z <= 9; z++) {
            // Right of south is west: the profile's south (dirt) side now lies west.
            assertEquals(dirt, source.get(-1, 64, z));
            assertEquals(stone, source.get(1, 64, z));
            assertEquals("south", states.describe(source.get(0, 65, z)).get("facing"));
        }
    }

    @Test
    void goingStraightUpItStaysUprightOrLiesBackFollowingTheSlope() {
        GeneratedSource upright = sweep(slice(), PathKind.STRAIGHT, false, 0, 63, 0, 0, 73, 0).source();
        upright.forEach((x, y, z, state) -> assertEquals(0, x, "upright: it slides up its own plane"));
        GeneratedSource tilted = sweep(slice(), PathKind.STRAIGHT, true, 0, 63, 0, 0, 73, 0).source();
        Set<Integer> across = new HashSet<>();
        tilted.forEach((x, y, z, state) -> {
            if (y == 68) across.add(x);
        });
        assertEquals(Set.of(-1, -2), across, "following the slope it lies back, its height pointing west");
    }

    @Test
    void aThickProfileRepeatsAlongTheLine() {
        // Three slices along x: a post (two high) in the first, a rail (one high) in all.
        List<int[]> cells = new ArrayList<>();
        for (int x = 100; x <= 102; x++) {
            for (int z = -2; z <= 2; z++) cells.add(new int[] {x, 70, z, stone});
            cells.add(new int[] {x, 71, 0, x == 100 ? dirt : air});
        }
        GeneratedSource source = sweep(profile(cells), PathKind.STRAIGHT, false, 0, 63, 0, 0, 63, 11).source();
        // x is the thinner side (3 against 5), so the profile faces along x; the line heads south, so it turns.
        List<Integer> posts = new ArrayList<>();
        for (int z = 0; z <= 11; z++) if (source.get(0, 65, z) == dirt) posts.add(z);
        assertEquals(List.of(0, 3, 6, 9), posts);
        for (int z = 0; z <= 11; z++) assertEquals(5, countAt(source, 64, z), "the rail at " + z);
    }

    @Test
    void aClosedCurveSweepsAllTheWayRoundWithoutAGap() {
        SweepKernel.Spec spec = spec(slice(), PathKind.CURVE, false, 0, 63, 0, 12, 63, 0, 12, 63, 12, 0, 63, 12, 0, 63, 0);
        assertTrue(PathSampler.closed(spec.path()));
        GeneratedSource ring = SweepKernel.generate(spec, states, 1 << 20, BOTTOM, TOP).source();
        PathSampler.visit(spec.path(), 0.25, (x, y, z, along) ->
                assertTrue(ring.contains((int) Math.floor(x), 64, (int) Math.floor(z)), "gap at " + x + "," + z));
        Map<String, Integer> again = new HashMap<>();
        SweepKernel.generate(spec, states, 1 << 20, BOTTOM, TOP).source().forEach((x, y, z, s) -> again.put(x + "," + y + "," + z, s));
        Map<String, Integer> first = new HashMap<>();
        ring.forEach((x, y, z, s) -> first.put(x + "," + y + "," + z, s));
        assertEquals(first, again, "deterministic");
    }

    @Test
    void blockEntitiesAreLeftOutAndCountedAndCapsHold() {
        List<int[]> cells = new ArrayList<>();
        cells.add(new int[] {100, 70, 0, stone});
        cells.add(new int[] {100, 71, 0, states.state("minecraft:chest[facing=north]")});
        GeneratedResult result = sweep(profile(cells), PathKind.STRAIGHT, false, 0, 63, 0, 5, 63, 0);
        assertEquals(1, result.leftOut());
        assertEquals(6, result.source().cells());
        assertThrows(GeneratedTooLargeException.class, () -> SweepKernel.generate(
                spec(slice(), PathKind.STRAIGHT, false, 0, 63, 0, 400, 63, 0), states, 100, BOTTOM, TOP));
        GeneratedSource clipped = SweepKernel.generate(spec(slice(), PathKind.STRAIGHT, false, 0, 63, 0, 5, 63, 0), states,
                1 << 20, BOTTOM, 65).source();
        clipped.forEach((x, y, z, state) -> assertEquals(64, y));
        assertThrows(IllegalArgumentException.class, () -> new SweepKernel.Profile(new int[SweepKernel.MAX_PROFILE_CELLS + 1],
                new int[SweepKernel.MAX_PROFILE_CELLS + 1], new int[SweepKernel.MAX_PROFILE_CELLS + 1],
                new int[SweepKernel.MAX_PROFILE_CELLS + 1]));
    }

    private static int countAt(GeneratedSource source, int y, int z) {
        int[] n = {0};
        source.forEach((cx, cy, cz, state) -> {
            if (cy == y && cz == z) n[0]++;
        });
        return n[0];
    }
}
