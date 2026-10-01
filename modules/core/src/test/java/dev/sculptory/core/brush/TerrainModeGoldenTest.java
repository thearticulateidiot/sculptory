package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * "Terrain (from above)" is today's behaviour, bit for bit: a fingerprint of every cell each of a fixed set of strokes
 * writes, in sink order, taken with the column kernel as it was before the Surface mode existed (2026-09-29, main
 * fe0b6913). Every terrain brush, falloff and shape, masks, the clip box, symmetry, water, plants and structures take
 * part. A change to the column kernel's output changes the fingerprint.
 */
class TerrainModeGoldenTest {
    /** The fingerprint the column kernel gave before the Surface mode was added. */
    private static final long GOLDEN = 5014186211600594402L;

    @Test
    void terrainModeWritesWhatItWroteBeforeSurfaceModeExisted() {
        Fingerprint print = new Fingerprint();
        FakeStateSpace states = new FakeStateSpace();
        for (Stroke stroke : strokes(states)) {
            FakeWorld world = terrain(states);
            StrokeState state = new StrokeState();
            BrushKernel kernel = BrushKernels.forTool(stroke.spec.tool());
            print.add(-1, stroke.spec.tool().ordinal(), 0, 0);
            for (Dab dab : stroke.dabs) {
                kernel.apply(stroke.spec, dab, state, world, (x, y, z, h) -> {
                    print.add(x, y, z, h);
                    world.set(x, y, z, h);
                });
            }
        }
        assertTrue(print.count > 5_000, "the strokes wrote " + print.count + " cells");
        assertEquals(GOLDEN, print.hash, "fingerprint of " + print.count + " writes");
    }

    /** FNV-1a over the writes' coordinates and states. */
    private static final class Fingerprint {
        long hash = 0xcbf29ce484222325L;
        long count;

        void add(int x, int y, int z, int h) {
            mix(x);
            mix(y);
            mix(z);
            mix(h);
            count++;
        }

        private void mix(int value) {
            for (int i = 0; i < 4; i++) {
                hash ^= (value >>> (8 * i)) & 0xFF;
                hash *= 0x100000001b3L;
            }
        }
    }

    private record Stroke(BrushSpec spec, List<Dab> dabs) {}

    /** A fixed set of strokes: every tool, falloff and shape, masks, clip boxes and symmetry. */
    static List<Stroke> strokes(FakeStateSpace states) {
        int sand = states.state("minecraft:sand");
        int dirt = states.state("minecraft:dirt");
        int grass = states.state("minecraft:grass_block");
        SurfaceMask grassOnly = new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(
                List.of(new NamespacedId("minecraft:grass_block"))));
        SurfaceMask notLowOrSteep = new SurfaceMask.Not(new SurfaceMask.And(List.of(
                new SurfaceMask.Elevation(50, 62), new SurfaceMask.Slope(0, 1))));
        Box clip = new Box(new BlockPos(-6, 58, -9), new BlockPos(7, 66, 4));
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 1, 0);
        Symmetry turns = new Symmetry(Symmetry.Mode.ROTATE_4, 0, 0);
        List<Stroke> strokes = new ArrayList<>();
        int seed = 0;
        for (BrushTool tool : BrushTool.TERRAIN) {
            Pattern material = switch (tool) {
                case PAINT -> new Pattern.Single(sand);
                case PALETTE -> new Pattern.Weighted(new int[] {sand, dirt, grass}, new int[] {3, 1, 2}, 7L);
                default -> null;
            };
            int depth = material == null ? 0 : 2;
            for (Falloff falloff : Falloff.values()) {
                for (Shape shape : Shape.values()) {
                    seed++;
                    int radius = 2 + seed % 5;
                    float strength = 0.35f + 0.13f * (seed % 5);
                    SurfaceMask mask = seed % 3 == 0 ? grassOnly : seed % 3 == 1 ? notLowOrSteep : SurfaceMask.ANY;
                    Box box = seed % 4 == 0 ? clip : null;
                    Symmetry symmetry = seed % 5 == 0 ? mirror : seed % 7 == 0 ? turns : Symmetry.NONE;
                    BrushSpec spec = new BrushSpec(tool, radius, strength, falloff, shape, material, mask, depth,
                            59 + seed % 5, seed, box, symmetry);
                    strokes.add(new Stroke(spec, path(seed)));
                }
            }
        }
        // Large brushes, as the server runs them unpredicted.
        strokes.add(new Stroke(new BrushSpec(BrushTool.SMOOTH, 20, 1f, Falloff.SPHERE, Shape.CIRCLE, null,
                SurfaceMask.ANY, 0, 0, 99L), path(3)));
        strokes.add(new Stroke(new BrushSpec(BrushTool.FLATTEN, 18, 0.8f, Falloff.LINEAR, Shape.SQUARE, null,
                SurfaceMask.ANY, 0, 61, 98L), path(4)));
        return strokes;
    }

    /** Twelve dabs wandering over the middle of the terrain, at fractional positions and pressures. */
    private static List<Dab> path(int seed) {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int x16 = (-8 + i + seed % 3) * 16 + (i * 5 + seed) % 16;
            int z16 = (-5 + (i * (seed % 4 + 1)) % 11) * 16 + (i * 11) % 16;
            int y16 = (62 + i % 3) * 16 + (seed * 3) % 16;
            dabs.add(new Dab(i, x16, y16, z16, 120 + (i * 37 + seed) % 136));
        }
        return dabs;
    }

    /**
     * Rolling ground (stone, a grass top) from y 50 around y 58-66, with a pond, grass and tall grass, a stair, a chest,
     * sand and an overhang.
     */
    static FakeWorld terrain(FakeStateSpace states) {
        FakeWorld world = new FakeWorld(states);
        int stone = states.state("minecraft:stone");
        int grass = states.state("minecraft:grass_block");
        int water = states.state("minecraft:water");
        int sand = states.state("minecraft:sand");
        int shortGrass = states.state("minecraft:short_grass");
        int tallLower = states.state("minecraft:tall_grass[half=lower]");
        int tallUpper = states.state("minecraft:tall_grass[half=upper]");
        for (int x = -30; x <= 30; x++) {
            for (int z = -30; z <= 30; z++) {
                int top = 58 + Math.floorMod(x / 3 + z / 4 + ((x * 7 + z * 3) & 1), 7) + ((x * x + z) % 5 == 0 ? 1 : 0);
                for (int y = 50; y <= top; y++) world.set(x, y, z, y == top ? grass : stone);
                int plant = Math.floorMod(x * 31 + z * 17, 11);
                if (plant == 0) world.set(x, top + 1, z, shortGrass);
                if (plant == 1) {
                    world.set(x, top + 1, z, tallLower);
                    world.set(x, top + 2, z, tallUpper);
                }
                if (plant == 2) world.set(x, top, z, sand);
            }
        }
        // A pond: sand bed at 57, water to 61.
        for (int x = 2; x <= 7; x++) {
            for (int z = -4; z <= 1; z++) {
                for (int y = 57; y <= 70; y++) world.set(x, y, z, y == 57 ? sand : y <= 61 ? water : 0);
            }
        }
        world.set(-3, 70, 2, states.state("minecraft:oak_stairs"));
        for (int y = 50; y < 70; y++) world.set(-3, y, 2, stone);
        world.set(-1, 66, -2, states.state("minecraft:chest"));
        // An overhang over (5..8, 4..6) at y 68-69.
        for (int x = 5; x <= 8; x++) {
            for (int z = 4; z <= 6; z++) {
                world.set(x, 68, z, stone);
                world.set(x, 69, z, grass);
            }
        }
        return world;
    }
}
