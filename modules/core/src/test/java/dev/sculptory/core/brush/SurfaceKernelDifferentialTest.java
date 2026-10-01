package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Surface Smooth ({@link SurfaceKernel}) writes exactly what it wrote before it was made faster
 * ({@link SurfaceKernelReference}, main 6a043a85): random rough scenes (ground, walls, overhangs, caves, water, plants,
 * structures, chunks not loaded, a short world), random Smooth strokes at radius 1 to 32, both shapes, every falloff,
 * symmetry copies, masks and clip boxes; every step's writes compared in order, and applied before the next step.
 * Raise, Lower and Flatten are not compared: they work in a cylinder since the brush feel round (2026-09-29), the
 * reference still in the ball; {@link SurfaceKernelFingerprintTest} pins them.
 */
class SurfaceKernelDifferentialTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int grass = states.state("minecraft:grass_block");
    private final int sand = states.state("minecraft:sand");
    private final int water = states.state("minecraft:water");
    private final int shortGrass = states.state("minecraft:short_grass");
    private final int tallLower = states.state("minecraft:tall_grass[half=lower]");
    private final int tallUpper = states.state("minecraft:tall_grass[half=upper]");
    private final int stairs = states.state("minecraft:oak_stairs");
    private final int chest = states.state("minecraft:chest");
    private final int slab = states.state("minecraft:oak_slab");

    private static final int EXTENT = 36;

    @Test
    void theFastKernelWritesWhatTheOldOneWrote() {
        Random random = new Random(20260929L);
        int steps = 0, writes = 0, smoothSteps = 0;
        for (int scene = 0; scene < 16; scene++) {
            boolean shortWorld = scene % 6 == 5;
            FakeWorld world = scene(random, shortWorld);
            int base = shortWorld ? 40 : 50;
            for (int strokeNo = 0; strokeNo < 12; strokeNo++) {
                BrushSpec spec = randomSpec(random, base);
                StrokeState fast = new StrokeState(), old = new StrokeState();
                int dabs = 1 + random.nextInt(6);
                int px = random.nextInt(2 * EXTENT) - EXTENT, pz = random.nextInt(2 * EXTENT) - EXTENT;
                int py = base + random.nextInt(shortWorld ? 40 : 30);
                for (int d = 0; d < dabs; d++) {
                    Dab dab = new Dab(d, px * 16 + random.nextInt(16), py * 16 + random.nextInt(16),
                            pz * 16 + random.nextInt(16), 1 + random.nextInt(255));
                    List<Dab> step = SymmetricStep.of(spec, dab, world).dabs();
                    if (random.nextInt(5) == 0) {
                        // Another dab of the same stroke in the same step, overlapping.
                        List<Dab> more = new ArrayList<>(step);
                        if (more.size() < Symmetry.MAX_COPIES) {
                            more.add(new Dab(d, dab.x16() + random.nextInt(97) - 48, dab.y16() + random.nextInt(97) - 48,
                                    dab.z16() + random.nextInt(97) - 48, 255));
                            step = more;
                        }
                    }
                    String what = "scene " + scene + " stroke " + strokeNo + " dab " + d + " " + spec + " step " + step;
                    List<int[]> got = new ArrayList<>();
                    BrushKernels.forTool(spec.tool()).applyStep(spec, step, fast, world,
                            (x, y, z, h) -> got.add(new int[] {x, y, z, h}));
                    List<int[]> expected = new ArrayList<>();
                    old.checkMaterial(spec.material(), world.states());
                    SurfaceKernelReference.apply(spec, step.get(0), TerrainKernel.sorted(step), old, world,
                            (x, y, z, h) -> expected.add(new int[] {x, y, z, h}));
                    assertEquals(expected.size(), got.size(), what);
                    for (int i = 0; i < got.size(); i++) {
                        assertEquals(java.util.Arrays.toString(expected.get(i)), java.util.Arrays.toString(got.get(i)),
                                what + " write " + i);
                    }
                    assertEquals(old.trackedSurface(), fast.trackedSurface(), what);
                    for (int[] w : got) world.set(w[0], w[1], w[2], w[3]);
                    steps++;
                    writes += got.size();
                    if (spec.tool() == BrushTool.SMOOTH) smoothSteps++;
                }
            }
        }
        System.out.printf("Surface kernel vs reference: %d steps (%d Smooth), %d writes, all the same%n", steps,
                smoothSteps, writes);
        assertTrue(smoothSteps > 300 && writes > 20_000, steps + " steps, " + writes + " writes");
    }

    private BrushSpec randomSpec(Random random, int base) {
        // The tool draw is kept so the scenes and strokes stay those of the fingerprinted run; only Smooth is compared.
        random.nextInt(8);
        BrushTool tool = BrushTool.SMOOTH;
        int radius = switch (random.nextInt(6)) {
            case 0 -> 1 + random.nextInt(3);
            case 1 -> 32;
            case 2 -> 16 + random.nextInt(17);
            default -> 3 + random.nextInt(12);
        };
        Shape shape = random.nextInt(4) == 0 ? Shape.SQUARE : Shape.CIRCLE;
        Falloff falloff = Falloff.values()[random.nextInt(Falloff.values().length)];
        float strength = random.nextBoolean() ? 1f : 0.05f + random.nextFloat() * 0.9f;
        SurfaceMask mask = switch (random.nextInt(8)) {
            case 0 -> new SurfaceMask.Slope(random.nextInt(8), 8 + random.nextInt(9));
            case 1 -> new SurfaceMask.Not(new SurfaceMask.SurfaceBlocks(
                    new CellMask.Blocks(List.of(new NamespacedId("minecraft:dirt")))));
            case 2 -> new SurfaceMask.Elevation(base + random.nextInt(10), base + 10 + random.nextInt(30));
            case 3 -> new SurfaceMask.And(List.of(new SurfaceMask.Slope(0, 10), new SurfaceMask.Elevation(base, base + 25)));
            default -> SurfaceMask.ANY;
        };
        BrushSpec spec = new BrushSpec(tool, radius, strength, falloff, shape, null, mask, 0, 0, random.nextLong());
        if (random.nextInt(4) == 0) {
            int x = random.nextInt(40) - 20, y = base + random.nextInt(20), z = random.nextInt(40) - 20;
            spec = spec.withClip(new Box(new BlockPos(x, y, z), new BlockPos(x + 4 + random.nextInt(30),
                    y + 3 + random.nextInt(20), z + 4 + random.nextInt(30))));
        }
        if (random.nextInt(3) == 0) {
            Symmetry.Mode mode = Symmetry.Mode.values()[1 + random.nextInt(Symmetry.Mode.values().length - 1)];
            int x2 = 2 * (random.nextInt(20) - 10), z2 = 2 * (random.nextInt(20) - 10);
            if (mode != Symmetry.Mode.ROTATE_4 && random.nextBoolean()) x2++;
            if (mode != Symmetry.Mode.ROTATE_4 && random.nextBoolean()) z2++;
            spec = spec.withSymmetry(new Symmetry(mode, x2, z2));
        }
        SurfacePlane plane = null;
        if (tool == BrushTool.FLATTEN) {
            Facing facing = Facing.values()[random.nextInt(Facing.values().length)];
            int target = facing.axis() == 1 ? base + 5 + random.nextInt(15) : random.nextInt(30) - 15;
            plane = new SurfacePlane(facing, target);
        }
        return spec.withSurface(plane);
    }

    /** A rough random scene around the origin. */
    private FakeWorld scene(Random random, boolean shortWorld) {
        int bottom = shortWorld ? 32 : -64, top = shortWorld ? 96 : 320;
        FakeWorld world = new FakeWorld(states, bottom, top);
        int base = shortWorld ? 40 : 50;
        int e = EXTENT + 34;
        int phase = random.nextInt(100);
        for (int x = -e; x <= e; x++) {
            for (int z = -e; z <= e; z++) {
                int height = base + 6 + (int) Math.round(4 * Math.sin((x + phase) / 7.0) + 3 * Math.cos((z - phase) / 5.0))
                        + Math.floorMod(x * 13 + z * 7, 3);
                for (int y = Math.max(bottom, base - 6); y <= height && y < top; y++) {
                    world.set(x, y, z, y == height ? grass : y > height - 3 ? dirt : stone);
                }
                int decor = Math.floorMod(x * 31 + z * 17 + phase, 23);
                if (height + 2 < top) {
                    if (decor == 0) world.set(x, height + 1, z, shortGrass);
                    if (decor == 1) {
                        world.set(x, height + 1, z, tallLower);
                        world.set(x, height + 2, z, tallUpper);
                    }
                    if (decor == 2) world.set(x, height, z, sand);
                }
            }
        }
        // Walls, pillars and overhangs.
        for (int n = 0; n < 8; n++) {
            int x = random.nextInt(2 * e - 20) - e + 10, z = random.nextInt(2 * e - 20) - e + 10;
            int w = 1 + random.nextInt(8), d = 1 + random.nextInt(20), h = 3 + random.nextInt(20);
            int y = base + random.nextInt(25);
            fill(world, x, y, z, x + w, Math.min(y + h, top - 1), z + d, random.nextBoolean() ? stone : dirt);
        }
        // Caves and dents.
        for (int n = 0; n < 10; n++) {
            int cx = random.nextInt(2 * e) - e, cy = base + random.nextInt(20), cz = random.nextInt(2 * e) - e;
            int r = 1 + random.nextInt(5);
            for (int x = cx - r; x <= cx + r; x++) {
                for (int y = Math.max(cy - r, bottom); y <= Math.min(cy + r, top - 1); y++) {
                    for (int z = cz - r; z <= cz + r; z++) {
                        int dx = x - cx, dy = y - cy, dz = z - cz;
                        if (dx * dx + dy * dy + dz * dz <= r * r) world.set(x, y, z, air);
                    }
                }
            }
        }
        // Water, structures and loose blocks.
        int wx = random.nextInt(40) - 20, wz = random.nextInt(40) - 20;
        fill(world, wx, base + 4, wz, wx + 6, base + 9, wz + 6, water);
        for (int n = 0; n < 30; n++) {
            int x = random.nextInt(2 * EXTENT) - EXTENT, y = base + random.nextInt(25), z = random.nextInt(2 * EXTENT) - EXTENT;
            int[] choice = {stairs, chest, slab, stone, dirt, air};
            world.set(x, y, z, choice[random.nextInt(choice.length)]);
        }
        if (random.nextInt(3) == 0) world.setLoaded(random.nextInt(6) - 3, random.nextInt(6) - 3, false);
        return world;
    }

    private static void fill(FakeWorld world, int x0, int y0, int z0, int x1, int y1, int z1, int state) {
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) world.set(x, y, z, state);
            }
        }
    }
}
