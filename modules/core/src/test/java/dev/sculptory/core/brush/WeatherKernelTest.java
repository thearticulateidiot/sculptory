package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/**
 * The Weather brush ({@link WeatherKernel}): each mode on fixed shapes (with golden results for a small cliff), both
 * sculpt modes' regions, strength as a rate, structures, plants, water and unloaded chunks, the mask and clip box,
 * symmetry, and the same result whether the sink writes through or not (the client's prediction equals the server).
 */
class WeatherKernelTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int grass = states.state("minecraft:grass_block");
    private final int sand = states.state("minecraft:sand");
    private final int water = states.state("minecraft:water");
    private final int shortGrass = states.state("minecraft:short_grass");
    private final int stairs = states.state("minecraft:oak_stairs");
    private final int chest = states.state("minecraft:chest");

    record Write(int x, int y, int z, int state) {}

    // ------------------------------------------------------------------ helpers

    private static BrushSpec spec(WeatherSpec.Mode mode, int radius, float strength, Falloff falloff, boolean surface) {
        return spec(mode, radius, strength, falloff, surface, SurfaceMask.ANY, null, Symmetry.NONE, 7L);
    }

    private static BrushSpec spec(WeatherSpec.Mode mode, int radius, float strength, Falloff falloff, boolean surface,
                                  SurfaceMask mask, Box clip, Symmetry symmetry, long seed) {
        BrushSpec spec = new BrushSpec(BrushTool.WEATHER, radius, strength, falloff, Shape.CIRCLE, null, mask, 0, 0, seed,
                clip, symmetry, null, SculptMode.TERRAIN, null, new WeatherSpec(mode));
        return surface ? spec.withSurface(null) : spec;
    }

    /** A full-pressure dab at the centre of block column (x, z), at block height y (its point a half block up). */
    private static Dab at(int index, int x, int y, int z) {
        return new Dab(index, x * 16 + 8, y * 16 + 8, z * 16 + 8, Dab.FULL_PRESSURE);
    }

    /** Stone from y 50 with a grass top at {@code height(x, z)}, for |x|, |z| <= 20. */
    private FakeWorld terrain(IntBinaryOperator height) {
        FakeWorld world = new FakeWorld(states);
        for (int x = -20; x <= 20; x++) {
            for (int z = -20; z <= 20; z++) {
                int top = height.applyAsInt(x, z);
                for (int y = 50; y <= top; y++) world.set(x, y, z, y < top ? stone : grass);
            }
        }
        return world;
    }

    /** One dab (and its copies) written through to the world; the cells written in sink order. */
    private static List<Write> dab(BrushSpec spec, StrokeState stroke, FakeWorld world, Dab dab) {
        List<Write> writes = new ArrayList<>();
        BrushKernels.forTool(BrushTool.WEATHER).apply(spec, dab, stroke, world, (x, y, z, h) -> {
            writes.add(new Write(x, y, z, h));
            world.set(x, y, z, h);
        });
        return writes;
    }

    /** {@code count} dabs at one point (a held brush), written through; every cell written. */
    private static List<Write> hold(BrushSpec spec, FakeWorld world, int x, int y, int z, int count) {
        StrokeState stroke = new StrokeState();
        List<Write> all = new ArrayList<>();
        for (int i = 0; i < count; i++) all.addAll(dab(spec, stroke, world, at(i, x, y, z)));
        return all;
    }

    /** A slice at z, rows y from {@code top} down to {@code bottom}, x from {@code x0} to {@code x1}. */
    private String slice(FakeWorld world, int z, int x0, int x1, int top, int bottom) {
        StringJoiner rows = new StringJoiner("\n");
        for (int y = top; y >= bottom; y--) {
            StringBuilder row = new StringBuilder();
            for (int x = x0; x <= x1; x++) row.append(symbol(world.get(x, y, z)));
            rows.add(row.toString());
        }
        return rows.toString();
    }

    private char symbol(int state) {
        if (state == air) return '.';
        if (state == stone) return '#';
        if (state == grass) return 'g';
        if (state == dirt) return 'd';
        if (state == sand) return 's';
        if (state == water) return '~';
        return '?';
    }

    private Map<Integer, Integer> counts(FakeWorld world, int r, int y0, int y1) {
        Map<Integer, Integer> counts = new HashMap<>();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                for (int y = y0; y <= y1; y++) counts.merge(world.get(x, y, z), 1, Integer::sum);
            }
        }
        counts.remove(air);
        return counts;
    }

    private static Set<BlockPos> cells(List<Write> writes) {
        Set<BlockPos> set = new HashSet<>();
        for (Write w : writes) set.add(new BlockPos(w.x(), w.y(), w.z()));
        return set;
    }

    /** A small cliff: stone with a grass top, four blocks high at x <= 0 (top 64), the floor at 60 beyond. */
    private FakeWorld cliff() {
        return terrain((x, z) -> x <= 0 ? 64 : 60);
    }

    // ------------------------------------------------------------------ Erode

    @Test
    void erodeTakesASpikeAndCornersInOneDabAndStraightEdgesInTwo() {
        for (boolean surface : new boolean[] {true, false}) {
            // A plateau two high (x, z -3..3, top 62) on a floor at 60, and a spike three high at (-8, 0).
            FakeWorld world = terrain((x, z) -> Math.abs(x) <= 3 && Math.abs(z) <= 3 ? 62 : x == -8 && z == 0 ? 63 : 60);
            BrushSpec spec = spec(WeatherSpec.Mode.ERODE, 10, 1f, Falloff.CONSTANT, surface);
            StrokeState stroke = new StrokeState();
            Set<BlockPos> first = cells(dab(spec, stroke, world, at(0, 0, 62, 0)));
            Set<BlockPos> expected = new HashSet<>(List.of(new BlockPos(-8, 61, 0), new BlockPos(-8, 62, 0),
                    new BlockPos(-8, 63, 0), new BlockPos(-3, 62, -3), new BlockPos(-3, 62, 3), new BlockPos(3, 62, -3),
                    new BlockPos(3, 62, 3)));
            assertEquals(expected, first, "spike (4-5 open faces) and corners (3) at once; surface " + surface);
            // The straight edges (2 open faces) wear at half the rate: they go on the second dab.
            Set<BlockPos> second = cells(dab(spec, stroke, world, at(1, 0, 62, 0)));
            for (int i = -2; i <= 2; i++) {
                assertTrue(second.contains(new BlockPos(i, 62, -3)) && second.contains(new BlockPos(-3, 62, i)), "edge " + i);
            }
            // The floor (one open face) never erodes.
            for (BlockPos cell : second) assertTrue(cell.y() > 60, "the floor stays: " + cell);
        }
    }

    @Test
    void erodeLeavesAFlatFloorAlone() {
        for (boolean surface : new boolean[] {true, false}) {
            FakeWorld world = terrain((x, z) -> 60);
            assertEquals(List.of(), hold(spec(WeatherSpec.Mode.ERODE, 8, 1f, Falloff.CONSTANT, surface), world, 0, 60, 0, 12));
        }
    }

    /**
     * Every mode on a small cliff (four high, a grass top, an overhang lip of stone), held six dabs at radius 5 with the
     * Smooth falloff at full strength, in both sculpt modes: the slice through the dab, pinned.
     */
    @Test
    void goldenSmallCliff() {
        StringBuilder all = new StringBuilder();
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            for (boolean surface : new boolean[] {true, false}) {
                FakeWorld world = cliff();
                world.set(1, 64, 0, stone);
                world.set(1, 64, 1, stone);
                hold(spec(mode, 5, 1f, Falloff.SMOOTH, surface), world, 0, 62, 0, 6);
                all.append(mode).append(surface ? " surface" : " terrain").append('\n')
                        .append(slice(world, 0, -6, 6, 66, 58)).append('\n');
            }
        }
        assertEquals(GOLDEN_CLIFF, all.toString());
    }

    private static final String GOLDEN_CLIFF = String.join("\n",
            "ERODE surface",
            ".............",
            ".............",
            "gggggg.......",
            "#######......",
            "#######......",
            "#######......",
            "#######gggggg",
            "#############",
            "#############",
            "ERODE terrain",
            ".............",
            ".............",
            "gggggg.......",
            "######.......",
            "#######......",
            "#######......",
            "#######gggggg",
            "#############",
            "#############",
            "FILL_IN surface",
            ".............",
            ".............",
            "ggggggg#.....",
            "#######......",
            "#######......",
            "#######......",
            "#######gggggg",
            "#############",
            "#############",
            "FILL_IN terrain",
            ".............",
            ".............",
            "ggggggg#.....",
            "#######......",
            "#######......",
            "#######......",
            "#######gggggg",
            "#############",
            "#############",
            "ROUGHEN surface",
            ".............",
            ".............",
            "ggggggg#.....",
            "#######......",
            "#######......",
            "#######......",
            "#######g..ggg",
            "#############",
            "#############",
            "ROUGHEN terrain",
            ".............",
            ".............",
            "ggggggg#.....",
            "#######......",
            "#######......",
            "#######......",
            "#######g..ggg",
            "#############",
            "#############",
            "MELT surface",
            ".............",
            ".............",
            "gggggg.......",
            "#######......",
            "#######......",
            "########g....",
            "#######gggggg",
            "#############",
            "#############",
            "MELT terrain",
            ".............",
            ".............",
            "gggggg.......",
            "######.......",
            "########.....",
            "#######g#....",
            "#######gggggg",
            "#############",
            "#############",
            "");

    @Test
    void erodeNeverBreaksThroughAThinWallIntoACave() {
        for (boolean surface : new boolean[] {true, false}) {
            // A wall one block thick at x 3 (y 61-66) on the floor, with a sealed cave behind it (x 4-6, y 61-63) under a
            // roof at 64-66; the dab in the open west of the wall.
            FakeWorld world = terrain((x, z) -> x >= 3 && x <= 6 ? 66 : 60);
            for (int x = 4; x <= 6; x++) {
                for (int z = -20; z <= 20; z++) {
                    for (int y = 61; y <= 63; y++) world.set(x, y, z, air);
                }
            }
            hold(spec(WeatherSpec.Mode.ERODE, 6, 1f, Falloff.CONSTANT, surface), world, 1, 62, 0, 3);
            for (int z = -3; z <= 3; z++) {
                for (int y = 61; y <= 63; y++) {
                    assertEquals(stone, world.get(3, y, z), "the wall beside the cave at " + y + " " + z + ", surface " + surface);
                }
            }
        }
    }

    @Test
    void erodedCellsUnderWaterRefillWithWater() {
        FakeWorld world = terrain((x, z) -> x == 0 && z == 0 ? 62 : 60);
        for (int x = -10; x <= 10; x++) {
            for (int z = -10; z <= 10; z++) {
                for (int y = 61; y <= 66; y++) {
                    if (world.get(x, y, z) == air) world.set(x, y, z, water);
                }
            }
        }
        List<Write> writes = hold(spec(WeatherSpec.Mode.ERODE, 5, 1f, Falloff.CONSTANT, true), world, 0, 63, 0, 2);
        assertEquals(Set.of(new BlockPos(0, 61, 0), new BlockPos(0, 62, 0)), cells(writes));
        assertEquals(water, world.get(0, 61, 0));
        assertEquals(water, world.get(0, 62, 0));
    }

    @Test
    void structuresAndTheGroundHoldingThemStay() {
        // A two-high pillar with a stair on top, and a chest on the floor beside a spike.
        FakeWorld world = terrain((x, z) -> x == 0 && z == 0 ? 62 : x == 3 && z == 0 ? 62 : 60);
        world.set(0, 63, 0, stairs);
        world.set(4, 61, 0, chest);
        List<Write> writes = hold(spec(WeatherSpec.Mode.ERODE, 6, 1f, Falloff.CONSTANT, true), world, 1, 62, 0, 6);
        assertEquals(stairs, world.get(0, 63, 0));
        assertEquals(chest, world.get(4, 61, 0));
        assertEquals(grass, world.get(0, 62, 0), "the ground under the stair stays");
        assertEquals(stone, world.get(3, 61, 0), "the ground beside the chest stays");
        assertFalse(cells(writes).contains(new BlockPos(4, 60, 0)), "nor the ground under the chest");
        assertTrue(cells(writes).contains(new BlockPos(0, 61, 0)), "the pillar below the stair erodes");
        assertTrue(cells(writes).contains(new BlockPos(3, 62, 0)), "the spike above the chest's side erodes");
    }

    // ------------------------------------------------------------------ Fill in

    @Test
    void fillInFillsHolesAndCracksWithTheBlocksAround() {
        for (boolean surface : new boolean[] {true, false}) {
            // A dirt layer under the grass; a 1x1 hole in the grass, and a crack two deep and three long.
            FakeWorld world = terrain((x, z) -> 60);
            for (int x = -20; x <= 20; x++) {
                for (int z = -20; z <= 20; z++) world.set(x, 59, z, dirt);
            }
            world.set(3, 60, 0, air);
            for (int z = -1; z <= 1; z++) {
                world.set(-3, 60, z, air);
                world.set(-3, 59, z, air);
            }
            hold(spec(WeatherSpec.Mode.FILL_IN, 7, 1f, Falloff.CONSTANT, surface), world, 0, 60, 0, 8);
            assertEquals(grass, world.get(3, 60, 0), "the hole takes the grass around it");
            for (int z = -1; z <= 1; z++) {
                assertEquals(dirt, world.get(-3, 59, z), "the crack's floor takes the dirt around it, " + z);
                assertEquals(grass, world.get(-3, 60, z), "its top the grass, " + z);
            }
            assertEquals(air, world.get(0, 61, 0), "nothing grows on the flat floor");
        }
    }

    @Test
    void fillInFillsACracksEndsFirstAndLeavesAStepsFootAlone() {
        // A crack's middle floor cell has three solid faces (half rate), its ends four (full rate).
        FakeWorld world = terrain((x, z) -> x >= 5 ? 63 : 60);
        for (int z = -1; z <= 1; z++) world.set(-3, 60, z, air);
        BrushSpec spec = spec(WeatherSpec.Mode.FILL_IN, 8, 1f, Falloff.CONSTANT, true);
        StrokeState stroke = new StrokeState();
        assertEquals(Set.of(new BlockPos(-3, 60, -1), new BlockPos(-3, 60, 1)), cells(dab(spec, stroke, world, at(0, 0, 60, 0))));
        assertEquals(Set.of(new BlockPos(-3, 60, 0)), cells(dab(spec, stroke, world, at(1, 0, 60, 0))));
        // The foot of the step at x = 5 (floor and wall: two solid faces) never fills.
        assertEquals(List.of(), hold(spec, world, 0, 60, 0, 6));
    }

    @Test
    void theSurfaceModeLeavesACaveBehindTheSurfaceAlone() {
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            for (boolean surface : new boolean[] {true, false}) {
                // A sealed pocket two below the floor, inside the ball and the footprint: not on the dab's side.
                FakeWorld world = terrain((x, z) -> 60);
                world.set(1, 57, 0, air);
                world.set(1, 57, 1, air);
                world.set(1, 56, 0, stone);
                hold(spec(mode, 6, 1f, Falloff.CONSTANT, surface, SurfaceMask.ANY, null, Symmetry.NONE, 11L), world,
                        0, 60, 0, 10);
                assertEquals(air, world.get(1, 57, 0), mode + " " + surface);
                assertEquals(air, world.get(1, 57, 1), mode + " " + surface);
            }
        }
    }

    // ------------------------------------------------------------------ Roughen

    @Test
    void roughenBreaksAFlatFloorUpBothWaysWithinItsLumpsAndStops() {
        for (boolean surface : new boolean[] {true, false}) {
            FakeWorld world = terrain((x, z) -> 60);
            BrushSpec spec = spec(WeatherSpec.Mode.ROUGHEN, 10, 1f, Falloff.CONSTANT, surface);
            StrokeState stroke = new StrokeState();
            int depth = WeatherKernel.roughenDepth(WeatherKernel.roughenScale(10));
            List<Write> all = new ArrayList<>();
            List<Write> last = List.of();
            for (int i = 0; i < 40; i++) {
                last = dab(spec, stroke, world, at(i, 0, 60, 0));
                all.addAll(last);
            }
            assertEquals(List.of(), last, "a held Roughen stops once its lumps are carved and built");
            long added = all.stream().filter(w -> w.state() != air && w.y() > 60).count();
            long removed = all.stream().filter(w -> w.state() == air).count();
            assertTrue(added > 10 && removed > 10, "added " + added + ", removed " + removed + ", surface " + surface);
            // Lumps, not speckles: side-by-side cells change together, and a lump goes a second layer deep.
            Set<BlockPos> dents = new HashSet<>(), bumps = new HashSet<>();
            for (Write w : all) (w.state() == air ? dents : bumps).add(new BlockPos(w.x(), w.y(), w.z()));
            assertTrue(dents.stream().anyMatch(c -> dents.contains(new BlockPos(c.x() + 1, c.y(), c.z()))),
                    "neighbouring dents, surface " + surface);
            assertTrue(bumps.stream().anyMatch(c -> bumps.contains(new BlockPos(c.x() + 1, c.y(), c.z()))),
                    "neighbouring bumps, surface " + surface);
            assertEquals(2, depth);
            assertTrue(dents.stream().anyMatch(c -> c.y() == 59) && bumps.stream().anyMatch(c -> c.y() == 62),
                    "two deep and two high, surface " + surface);
            for (Write w : all) {
                assertTrue(w.state() == air ? w.y() > 60 - depth : w.y() <= 60 + depth, "within the lumps: " + w);
                assertTrue(w.state() == air || w.state() == grass || w.state() == stone, "blocks from around: " + w);
            }
        }
    }

    @Test
    void roughenFollowsTheStrokesSeed() {
        FakeWorld a = terrain((x, z) -> 60), b = terrain((x, z) -> 60), c = terrain((x, z) -> 60);
        List<Write> first = hold(spec(WeatherSpec.Mode.ROUGHEN, 8, 1f, Falloff.CONSTANT, true, SurfaceMask.ANY, null,
                Symmetry.NONE, 5L), a, 0, 60, 0, 3);
        List<Write> same = hold(spec(WeatherSpec.Mode.ROUGHEN, 8, 1f, Falloff.CONSTANT, true, SurfaceMask.ANY, null,
                Symmetry.NONE, 5L), b, 0, 60, 0, 3);
        List<Write> other = hold(spec(WeatherSpec.Mode.ROUGHEN, 8, 1f, Falloff.CONSTANT, true, SurfaceMask.ANY, null,
                Symmetry.NONE, 6L), c, 0, 60, 0, 3);
        assertEquals(first, same);
        assertNotEquals(first, other);
    }

    @Test
    void theNoiseIsBoundedAndSmooth() {
        for (int scale = 2; scale <= 8; scale++) {
            for (int x = -9; x <= 9; x++) {
                int n = WeatherKernel.noise(x, 3, -2, scale, 1L), next = WeatherKernel.noise(x + 1, 3, -2, scale, 1L);
                assertTrue(n >= 0 && n < WeatherKernel.NOISE_ONE, "in range: " + n);
                assertTrue(Math.abs(n - next) <= WeatherKernel.NOISE_ONE / scale, "a step of one block moves it a little");
            }
        }
        assertEquals(2, WeatherKernel.roughenScale(1));
        assertEquals(4, WeatherKernel.roughenScale(16));
        assertEquals(8, WeatherKernel.roughenScale(32));
    }

    // ------------------------------------------------------------------ Melt

    @Test
    void meltMovesBlocksWithoutMakingOrLosingAny() {
        for (boolean surface : new boolean[] {true, false}) {
            FakeWorld world = cliff();
            // An overhang off the cliff's top.
            for (int x = 1; x <= 3; x++) {
                for (int z = -20; z <= 20; z++) world.set(x, 64, z, stone);
            }
            Map<Integer, Integer> before = counts(world, 20, 50, 80);
            List<Write> writes = hold(spec(WeatherSpec.Mode.MELT, 7, 1f, Falloff.CONSTANT, surface), world, 0, 62, 0, 30);
            assertFalse(writes.isEmpty());
            assertEquals(before, counts(world, 20, 50, 80), "surface " + surface);
        }
    }

    @Test
    void meltSlumpsACliffDownToFortyFiveDegrees() {
        for (boolean surface : new boolean[] {true, false}) {
            FakeWorld world = terrain((x, z) -> x <= 0 ? 66 : 60);
            hold(spec(WeatherSpec.Mode.MELT, 8, 1f, Falloff.CONSTANT, surface), world, 0, 63, 0, 80);
            // Along the middle of the footprint no step is steeper than one block per block any more.
            for (int x = -4; x <= 4; x++) {
                int here = top(world, x, 0), east = top(world, x + 1, 0);
                assertTrue(Math.abs(here - east) <= 1, "x " + x + ": " + here + " -> " + east + ", surface " + surface);
            }
        }
    }

    @Test
    void meltLeavesAStaircaseAndAFlatFloorAlone() {
        for (boolean surface : new boolean[] {true, false}) {
            FakeWorld stairsWorld = terrain((x, z) -> 60 + Math.max(-6, Math.min(6, x)));
            assertEquals(List.of(), hold(spec(WeatherSpec.Mode.MELT, 6, 1f, Falloff.CONSTANT, surface), stairsWorld,
                    0, 60, 0, 10), "45 degrees is stable");
            FakeWorld flat = terrain((x, z) -> 60);
            assertEquals(List.of(), hold(spec(WeatherSpec.Mode.MELT, 6, 1f, Falloff.CONSTANT, surface), flat, 0, 60, 0, 10));
        }
    }

    @Test
    void aCeilingDripsInTheSurfaceModeOnly() {
        // Solid to 70 with a cave at y 60-64 (x, z -6..6); the dab in the cave, then on the ground above it.
        FakeWorld world = terrain((x, z) -> 70);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 60; y <= 64; y++) world.set(x, y, z, air);
            }
        }
        FakeWorld terrainWorld = terrain((x, z) -> 70);
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                for (int y = 60; y <= 64; y++) terrainWorld.set(x, y, z, air);
            }
        }
        List<Write> drips = hold(spec(WeatherSpec.Mode.MELT, 4, 1f, Falloff.CONSTANT, true), world, 0, 62, 0, 1);
        assertFalse(drips.isEmpty());
        for (Write w : drips) {
            assertTrue(w.state() == air ? w.y() == 65 : w.y() == 60, "a ceiling cell falls to the floor: " + w);
        }
        assertEquals(List.of(), hold(spec(WeatherSpec.Mode.MELT, 4, 1f, Falloff.CONSTANT, false), terrainWorld,
                0, 70, 0, 10), "from above: the cave is not on the sky side");
    }

    private int top(FakeWorld world, int x, int z) {
        for (int y = 90; y >= 50; y--) {
            if (world.get(x, y, z) != air) return y;
        }
        return 49;
    }

    // ------------------------------------------------------------------ strength, plants, unloaded chunks

    @Test
    void strengthIsTheRate() {
        // Corners (full rate) at strength 0.5 go on every second dab; at 0.25 on every fourth.
        for (float strength : new float[] {0.5f, 0.25f}) {
            FakeWorld world = terrain((x, z) -> Math.abs(x) <= 3 && Math.abs(z) <= 3 ? 62 : 60);
            BrushSpec spec = spec(WeatherSpec.Mode.ERODE, 10, strength, Falloff.CONSTANT, true);
            StrokeState stroke = new StrokeState();
            int dabs = (int) (1 / strength);
            for (int i = 0; i < dabs - 1; i++) assertEquals(List.of(), dab(spec, stroke, world, at(i, 0, 62, 0)));
            assertTrue(cells(dab(spec, stroke, world, at(dabs, 0, 62, 0))).contains(new BlockPos(3, 62, 3)));
        }
        // Pressure scales it the same way, and the falloff: a Linear falloff leaves the rim alone for longer.
        FakeWorld world = terrain((x, z) -> Math.abs(x) <= 3 && Math.abs(z) <= 3 ? 62 : 60);
        StrokeState stroke = new StrokeState();
        BrushSpec spec = spec(WeatherSpec.Mode.ERODE, 10, 1f, Falloff.CONSTANT, true);
        assertEquals(List.of(), dab(spec, stroke, world, new Dab(0, 8, 62 * 16 + 8, 8, 127)));
    }

    @Test
    void plantsOnAChangedCellGoAndChangesStayInsideTheClipBox() {
        // A spike with a flower on top and grass beside it.
        FakeWorld world = terrain((x, z) -> x == 0 && z == 0 ? 62 : 60);
        world.set(0, 63, 0, shortGrass);
        world.set(2, 61, 0, shortGrass);
        hold(spec(WeatherSpec.Mode.ERODE, 5, 1f, Falloff.CONSTANT, true), world, 0, 62, 0, 1);
        assertEquals(air, world.get(0, 63, 0), "the plant on the eroded spike goes with it");
        assertEquals(shortGrass, world.get(2, 61, 0), "the one on the floor stays");
        // With a clip box that holds the spike but not the plant on it, the spike's top stays (its plant would be cut).
        FakeWorld clipped = terrain((x, z) -> x == 0 && z == 0 ? 62 : 60);
        clipped.set(0, 63, 0, shortGrass);
        Box clip = new Box(new BlockPos(-5, 55, -5), new BlockPos(5, 62, 5));
        List<Write> writes = hold(spec(WeatherSpec.Mode.ERODE, 5, 1f, Falloff.CONSTANT, true, SurfaceMask.ANY, clip,
                Symmetry.NONE, 1L), clipped, 0, 62, 0, 1);
        assertEquals(Set.of(new BlockPos(0, 61, 0)), cells(writes));
        assertEquals(grass, clipped.get(0, 62, 0));
        assertEquals(shortGrass, clipped.get(0, 63, 0));
    }

    @Test
    void nothingChangesNextToAnUnloadedChunk() {
        // The chunk at x 16-31 is missing. A cliff drops from x 14 (top 64) to x 15 (top 60, the last loaded column): Melt
        // would slide x 14's top into x 15, beside the missing chunk; a spike at x 15 would erode; a pit at x 15 fill.
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            for (boolean surface : new boolean[] {true, false}) {
                // A second cliff away from it (x 9, top 67) where Melt still works.
                FakeWorld world = terrain((x, z) -> x <= 9 ? 67 : x <= 14 ? 64 : 60);
                world.set(15, 61, 2, stone);
                world.set(15, 62, 2, grass);
                world.set(15, 60, -2, air);
                world.setLoaded(1, 0, false);
                world.setLoaded(1, -1, false);
                List<Write> writes = hold(spec(mode, 8, 1f, Falloff.CONSTANT, surface, SurfaceMask.ANY, null,
                        Symmetry.NONE, 5L), world, 12, 64, 0, 6);
                for (Write w : writes) assertTrue(w.x() < 15, mode + " beside the missing chunk: " + w + ", surface " + surface);
                if (mode == WeatherSpec.Mode.MELT) {
                    assertFalse(writes.isEmpty(), "Melt still works away from the missing chunk, surface " + surface);
                }
            }
        }
    }

    // ------------------------------------------------------------------ mask, clip

    @Test
    void theMaskSeesEachChangedCellsBlock() {
        // Erode with a grass-only mask removes only grass cells, however long it is held.
        FakeWorld world = terrain((x, z) -> Math.abs(x) <= 3 && Math.abs(z) <= 3 ? 63 : 60);
        SurfaceMask grassOnly = new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:grass_block"))));
        List<Write> writes = hold(spec(WeatherSpec.Mode.ERODE, 10, 1f, Falloff.CONSTANT, true, grassOnly, null,
                Symmetry.NONE, 1L), world, 0, 63, 0, 20);
        assertFalse(writes.isEmpty());
        for (Write w : writes) assertEquals(63, w.y(), "only the grass top: " + w);
        // Fill in sees the block a cell gains: a stone-only mask leaves a grass-lined hole open.
        FakeWorld holes = terrain((x, z) -> 60);
        holes.set(2, 60, 0, air);
        SurfaceMask stoneOnly = new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:stone"))));
        assertEquals(List.of(), hold(spec(WeatherSpec.Mode.FILL_IN, 6, 1f, Falloff.CONSTANT, true, stoneOnly, null,
                Symmetry.NONE, 1L), holes, 0, 60, 0, 4));
        // An Elevation mask keeps Melt's landing inside the range too: nothing lands below 62.
        FakeWorld cliffWorld = cliff();
        List<Write> melt = hold(spec(WeatherSpec.Mode.MELT, 6, 1f, Falloff.CONSTANT, true, new SurfaceMask.Elevation(62, 90),
                null, Symmetry.NONE, 1L), cliffWorld, 0, 62, 0, 10);
        for (Write w : melt) assertTrue(w.y() >= 62, "inside the mask's range: " + w);
    }

    @Test
    void nothingOutsideTheClipBoxChangesAndMeltConservesInsideIt() {
        Box clip = new Box(new BlockPos(-2, 55, -10), new BlockPos(3, 70, 10));
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            for (boolean surface : new boolean[] {true, false}) {
                FakeWorld world = terrain((x, z) -> x <= 0 ? 64 : 60 + (x * 7 + z * 3) % 2);
                world.set(0, 62, 2, air);
                Map<Integer, Integer> before = counts(world, 20, 50, 80);
                List<Write> writes = hold(spec(mode, 7, 1f, Falloff.CONSTANT, surface, SurfaceMask.ANY, clip,
                        Symmetry.NONE, 3L), world, 0, 62, 0, 12);
                for (Write w : writes) assertTrue(clip.contains(w.x(), w.y(), w.z()), mode + " wrote outside: " + w);
                if (mode == WeatherSpec.Mode.MELT) {
                    assertFalse(writes.isEmpty());
                    assertEquals(before, counts(world, 20, 50, 80), "surface " + surface);
                }
            }
        }
    }

    // ------------------------------------------------------------------ symmetry and steps

    @Test
    void mirroredCopiesDoTheMirroredThing() {
        // Terrain mirrored about x = 0 (cell x onto -1 - x), rough enough for every mode to act.
        IntBinaryOperator height = (x, z) -> {
            int m = x >= 0 ? x : -1 - x;
            return 60 + (m >= 10 && m <= 14 ? 4 : 0) + Math.floorMod(m * 5 + z * 3, 3) - (m == 12 && z == 1 ? 3 : 0);
        };
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0);
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            for (boolean surface : new boolean[] {true, false}) {
                FakeWorld world = terrain(height);
                // An overhang on both sides, for Melt.
                for (int z = -3; z <= 3; z++) {
                    world.set(15, 64, z, stone);
                    world.set(-16, 64, z, stone);
                }
                BrushSpec spec = spec(mode, 5, 1f, Falloff.SMOOTH, surface, SurfaceMask.ANY, null, mirror, 9L);
                // Each dab on the ground's top face where the cursor would hit it (x 13, z 0), where its copy stands too
                // (a dab left hanging in the air as the ground wears away keeps its height, while its copy stands on
                // the ground below: SymmetricStep).
                StrokeState stroke = new StrokeState();
                List<Write> writes = new ArrayList<>();
                for (int i = 0; i < 6; i++) {
                    writes.addAll(dab(spec, stroke, world, new Dab(i, 13 * 16 + 8, (top(world, 13, 0) + 1) * 16, 8, 255)));
                }
                assertFalse(writes.isEmpty(), mode + " " + surface);
                for (int x = 0; x <= 19; x++) {
                    for (int z = -20; z <= 20; z++) {
                        for (int y = 50; y <= 75; y++) {
                            assertEquals(world.get(x, y, z), world.get(-1 - x, y, z),
                                    mode + " surface " + surface + " at " + x + " " + y + " " + z);
                        }
                    }
                }
            }
        }
    }

    @Test
    void aStepsCopiesMayBeListedInAnyOrderAfterTheDab() {
        Symmetry rotate = new Symmetry(Symmetry.Mode.ROTATE_4, 0, 0);
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            BrushSpec spec = spec(mode, 6, 1f, Falloff.LINEAR, true, SurfaceMask.ANY, null, rotate, 4L);
            FakeWorld a = terrain((x, z) -> 60 + Math.floorMod(x * 3 + z * 5, 4)), b = terrain((x, z) -> 60 + Math.floorMod(x * 3 + z * 5, 4));
            List<Dab> copies = SymmetricStep.of(spec, at(0, 4, 63, 2), a).dabs();
            List<Dab> reordered = new ArrayList<>(copies.subList(1, copies.size()));
            java.util.Collections.reverse(reordered);
            reordered.add(0, copies.get(0));
            List<Write> first = new ArrayList<>(), second = new ArrayList<>();
            BrushKernels.forTool(BrushTool.WEATHER).applyStep(spec, copies, new StrokeState(), a,
                    (x, y, z, h) -> first.add(new Write(x, y, z, h)));
            BrushKernels.forTool(BrushTool.WEATHER).applyStep(spec, reordered, new StrokeState(), b,
                    (x, y, z, h) -> second.add(new Write(x, y, z, h)));
            assertEquals(first, second, mode.toString());
        }
    }

    // ------------------------------------------------------------------ client and server agree

    @Test
    void theResultIsTheSameWhetherTheSinkWritesThroughOrNot() {
        for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
            for (boolean surface : new boolean[] {true, false}) {
                IntBinaryOperator height = (x, z) -> x <= 0 ? 64 + Math.floorMod(z, 2) : 60 + Math.floorMod(x + z, 3);
                FakeWorld through = terrain(height), buffered = terrain(height);
                buffered.setHeightHints(true);
                BrushSpec spec = spec(mode, 7, 0.7f, Falloff.SMOOTH, surface);
                StrokeState a = new StrokeState(), b = new StrokeState();
                for (int i = 0; i < 8; i++) {
                    Dab d = at(i, i - 3, 63, i % 3);
                    List<Write> direct = dab(spec, a, through, d);
                    List<Write> later = new ArrayList<>();
                    BrushKernels.forTool(BrushTool.WEATHER).apply(spec, d, b, buffered,
                            (x, y, z, h) -> later.add(new Write(x, y, z, h)));
                    for (Write w : later) buffered.set(w.x(), w.y(), w.z(), w.state());
                    assertEquals(direct, later, mode + " surface " + surface + " dab " + i);
                }
            }
        }
    }

    @Test
    void refusesOtherToolsAndBadSteps() {
        BrushKernel kernel = BrushKernels.forTool(BrushTool.WEATHER);
        FakeWorld world = terrain((x, z) -> 60);
        BrushSpec smooth = new BrushSpec(BrushTool.SMOOTH, 3, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L);
        assertThrows(IllegalArgumentException.class,
                () -> kernel.applyStep(smooth, List.of(at(0, 0, 60, 0)), new StrokeState(), world, (x, y, z, h) -> {}));
        BrushSpec weather = spec(WeatherSpec.Mode.ERODE, 3, 1f, Falloff.SMOOTH, true);
        assertThrows(IllegalArgumentException.class,
                () -> kernel.applyStep(weather, List.of(), new StrokeState(), world, (x, y, z, h) -> {}));
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(weather,
                List.of(new Dab(0, (1 << 26) * 16, 60 * 16, 0, 255)), new StrokeState(), world, (x, y, z, h) -> {}));
        assertThrows(IllegalArgumentException.class, () -> new BrushSpec(BrushTool.WEATHER, 3, 1f, Falloff.SMOOTH,
                Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L));
    }
}
