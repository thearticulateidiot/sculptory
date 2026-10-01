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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Pins what the Surface mode writes on a rough scene ({@link SurfaceScene}) at large radii, over whole strokes: every
 * write of every step, in order, as a fingerprint taken on main 6a043a85 before the Smooth kernel was made faster
 * (2026-09-29); Raise, Lower and Flatten pinned again the same day, after the brush feel round gave them the cylinder
 * and Flatten its first-dab block (the moving radius-7 Raise and Lower strokes came out the same). Any change to the output of Raise, Lower, Smooth or Flatten in the Surface mode fails here: strokes with
 * accumulators, falloffs, both shapes, symmetry copies whose balls overlap, steps listed in any order, masks (slope
 * included), the clip box, an unloaded chunk, the world's bottom and top, structures, plants and water.
 */
class SurfaceKernelFingerprintTest {
    private final FakeStateSpace states = new FakeStateSpace();

    /** A case's fingerprint and how many cells it wrote. */
    record Print(long hash, int writes) {
        @Override
        public String toString() {
            return String.format("%016x/%d", hash, writes);
        }
    }

    private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("smooth r32 wall", "f09092d4328bcb1d/2340");
        EXPECTED.put("smooth r32 overhang, twice", "f5973b29a347391c/13");
        EXPECTED.put("smooth r10 along the wall", "2d1b677fed802cd1/4");
        EXPECTED.put("smooth r24 held still", "156da9162de94e6d/1280");
        EXPECTED.put("smooth r16 square linear mirror x", "facc112804708eea/318");
        EXPECTED.put("smooth r12 sphere rotate 4", "5cdbf54c634468a1/643");
        EXPECTED.put("smooth r14 overlapping step", "346fb1165d006f81/28");
        EXPECTED.put("smooth r20 masked and clipped", "7f4bf374c3496399/88");
        EXPECTED.put("smooth r20 by an unloaded chunk", "9b05ce697d205d5b/500");
        EXPECTED.put("smooth r32 at the bottom and top", "6d4b442cf21b57e6/1955");
        EXPECTED.put("RAISE r32 wall", "fe33af6f748fb3f1/1765");
        EXPECTED.put("RAISE r7 stroke", "f9a119e340b2d264/304");
        EXPECTED.put("LOWER r32 wall", "1fb230603db5e7a5/1796");
        EXPECTED.put("LOWER r7 stroke", "c20a09b7d99f3495/264");
        EXPECTED.put("FLATTEN r32 wall", "53f78721e65f45fe/1673");
        EXPECTED.put("FLATTEN r7 stroke", "052946451dd006cd/45");
    }

    @Test
    void theSurfaceModeWritesExactlyWhatItWroteBefore() {
        Map<String, Print> actual = new LinkedHashMap<>();
        // Smooth.
        actual.put("smooth r32 wall", stroke(spec(BrushTool.SMOOTH, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE), world(),
                s -> s.dab(SurfaceScene.wallDab(0, 80, 0))));
        actual.put("smooth r32 overhang, twice", stroke(spec(BrushTool.SMOOTH, 32, 0.7f, Falloff.SMOOTH, Shape.CIRCLE),
                world(), s -> {
                    s.dab(new Dab(0, -8 * 16, 84 * 16 + 8, 0, 255));
                    s.dab(new Dab(1, -8 * 16 + 3, 84 * 16 + 5, 7, 200));
                }));
        actual.put("smooth r10 along the wall", stroke(spec(BrushTool.SMOOTH, 10, 0.35f, Falloff.SMOOTH, Shape.CIRCLE),
                world(), s -> {
                    for (int i = 0; i < 12; i++) s.dab(SurfaceScene.wallDab(i, 70 + i, -14 + 3 * i));
                }));
        actual.put("smooth r24 held still", stroke(spec(BrushTool.SMOOTH, 24, 0.3f, Falloff.CONSTANT, Shape.CIRCLE),
                world(), s -> {
                    for (int i = 0; i < 5; i++) s.dab(SurfaceScene.wallDab(i, 74, -4));
                }));
        actual.put("smooth r16 square linear mirror x", stroke(spec(BrushTool.SMOOTH, 16, 0.8f, Falloff.LINEAR,
                Shape.SQUARE).withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, 2 * SurfaceScene.WALL_X - 6, 0)),
                world(), s -> {
                    for (int i = 0; i < 6; i++) s.dab(SurfaceScene.wallDab(i, 72 + 2 * i, -6 + 2 * i));
                }));
        actual.put("smooth r12 sphere rotate 4", stroke(spec(BrushTool.SMOOTH, 12, 1f, Falloff.SPHERE, Shape.CIRCLE)
                .withSymmetry(new Symmetry(Symmetry.Mode.ROTATE_4, 2, 2)), world(), s -> {
                    for (int i = 0; i < 4; i++) s.dab(new Dab(i, (-6 + i) * 16 + 8, 66 * 16, (3 + i) * 16 + 8, 255));
                }));
        actual.put("smooth r14 overlapping step", stroke(spec(BrushTool.SMOOTH, 14, 0.9f, Falloff.LINEAR, Shape.CIRCLE),
                world(), s -> {
                    s.step(List.of(SurfaceScene.wallDab(0, 80, 0), SurfaceScene.wallDab(0, 76, 5),
                            new Dab(0, 0, 70 * 16, 16, 255)));
                    s.step(List.of(new Dab(1, 0, 70 * 16, 16, 255), SurfaceScene.wallDab(1, 76, 5),
                            SurfaceScene.wallDab(1, 80, 0)));
                }));
        SurfaceMask masked = new SurfaceMask.And(List.of(new SurfaceMask.Slope(4, 16), new SurfaceMask.Not(
                new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:dirt")))))));
        Box clip = new Box(new BlockPos(-10, 60, -12), new BlockPos(SurfaceScene.WALL_X + 1, 95, 6));
        actual.put("smooth r20 masked and clipped", stroke(new BrushSpec(BrushTool.SMOOTH, 20, 1f, Falloff.CONSTANT,
                Shape.CIRCLE, null, masked, 0, 0, 5L).withClip(clip).withSurface(null), world(), s -> {
                    s.dab(SurfaceScene.wallDab(0, 78, -2));
                    s.dab(SurfaceScene.wallDab(1, 84, 2));
                }));
        FakeWorld partial = world();
        partial.setLoaded(-1, 0, false);
        actual.put("smooth r20 by an unloaded chunk", stroke(spec(BrushTool.SMOOTH, 20, 1f, Falloff.CONSTANT,
                Shape.CIRCLE), partial, s -> s.dab(SurfaceScene.wallDab(0, 76, 3))));
        FakeWorld low = new FakeWorld(states, 64, 128);
        SurfaceScene.build(states, (x, y, z, h) -> {
            if (y >= 64 && y < 128) low.set(x, y, z, h);
        });
        actual.put("smooth r32 at the bottom and top", stroke(spec(BrushTool.SMOOTH, 32, 1f, Falloff.CONSTANT,
                Shape.CIRCLE), low, s -> {
                    s.dab(new Dab(0, 8, 66 * 16, 8, 255));
                    s.dab(SurfaceScene.wallDab(1, 120, 0));
                }));
        // Raise, Lower and Flatten, for anything their shared reads change.
        for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.FLATTEN)) {
            SurfacePlane plane = tool == BrushTool.FLATTEN ? new SurfacePlane(Facing.WEST, SurfaceScene.WALL_X) : null;
            BrushSpec big = terrain(tool, 32, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSurface(plane);
            actual.put(tool + " r32 wall", stroke(big, world(), s -> s.dab(SurfaceScene.wallDab(0, 80, 0))));
            BrushSpec small = terrain(tool, 7, 0.6f, Falloff.SMOOTH, Shape.SQUARE)
                    .withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_Z, 0, 1)).withSurface(plane);
            actual.put(tool + " r7 stroke", stroke(small, world(), s -> {
                for (int i = 0; i < 8; i++) s.dab(SurfaceScene.wallDab(i, 70 + i, -8 + i));
            }));
        }
        Map<String, String> got = new LinkedHashMap<>();
        actual.forEach((name, print) -> got.put(name, print.toString()));
        if (!got.equals(EXPECTED)) {
            // For a deliberate change only: the lines to paste above.
            StringBuilder listing = new StringBuilder();
            got.forEach((name, print) -> listing.append(String.format("        EXPECTED.put(\"%s\", \"%s\");%n", name,
                    print)));
            System.out.print(listing);
        }
        assertEquals(EXPECTED, got);
    }

    @Test
    void aColumnSpanHoldsExactlyTheCellsWithinReach() {
        java.util.Random random = new java.util.Random(7);
        FakeWorld world = new FakeWorld(states);
        int checked = 0;
        for (int trial = 0; trial < 100; trial++) {
            int radius = 1 + random.nextInt(32);
            Shape shape = random.nextBoolean() ? Shape.CIRCLE : Shape.SQUARE;
            Dab dab = new Dab(0, random.nextInt(4000) - 2000, random.nextInt(3000) - 500, random.nextInt(4000) - 2000,
                    255);
            SurfaceKernel.Ball ball = new SurfaceKernel.Ball(new BrushSpec(BrushTool.SMOOTH, radius, 1f, Falloff.CONSTANT,
                    shape, null, SurfaceMask.ANY, 0, 0, 1L).withSurface(null), dab, world);
            for (long reach16 : new long[] {ball.r16, ball.r16 + 16, ball.r16 + 32}) {
                for (int x = ball.x0; x < ball.x0 + ball.size; x++) {
                    for (int z = ball.z0; z < ball.z0 + ball.size; z++) {
                        long span = ball.span(x, z, reach16);
                        for (int y = ball.y0 - 1; y <= ball.y0 + ball.size; y++) {
                            boolean in = SurfaceKernel.spanLow(span) <= y && y <= SurfaceKernel.spanHigh(span);
                            if (in != ball.within(x, y, z, reach16)) {
                                throw new AssertionError("radius " + radius + " " + shape + " reach " + reach16 + " at "
                                        + x + "," + y + "," + z + ": span says " + in);
                            }
                            checked++;
                        }
                    }
                }
            }
        }
        assertTrue(checked > 1_000_000);
    }

    // ------------------------------------------------------------------ helpers

    private FakeWorld world() {
        FakeWorld world = new FakeWorld(states);
        SurfaceScene.build(states, world::set);
        return world;
    }

    private static BrushSpec spec(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape) {
        return terrain(tool, radius, strength, falloff, shape).withSurface(null);
    }

    private static BrushSpec terrain(BrushTool tool, int radius, float strength, Falloff falloff, Shape shape) {
        return new BrushSpec(tool, radius, strength, falloff, shape, null, SurfaceMask.ANY, 0, 0, 3L);
    }

    /** One stroke: dabs (with their symmetry copies) or explicit steps, each step's writes applied before the next. */
    private final class Stroke {
        final BrushSpec spec;
        final FakeWorld world;
        final StrokeState state = new StrokeState();
        long hash = 0xcbf29ce484222325L;
        int writes;

        Stroke(BrushSpec spec, FakeWorld world) {
            this.spec = spec;
            this.world = world;
        }

        void dab(Dab dab) {
            List<int[]> out = new ArrayList<>();
            BrushKernels.forTool(spec.tool()).apply(spec, dab, state, world, (x, y, z, h) -> out.add(new int[] {x, y, z, h}));
            take(out);
        }

        void step(List<Dab> dabs) {
            List<int[]> out = new ArrayList<>();
            BrushKernels.forTool(spec.tool()).applyStep(spec, dabs, state, world,
                    (x, y, z, h) -> out.add(new int[] {x, y, z, h}));
            take(out);
        }

        private void take(List<int[]> out) {
            for (int[] w : out) {
                for (int v : w) hash = (hash ^ v) * 0x100000001b3L;
                writes++;
                world.set(w[0], w[1], w[2], w[3]);
            }
            hash = (hash ^ 0xFFFF) * 0x100000001b3L;
        }
    }

    private Print stroke(BrushSpec spec, FakeWorld world, Consumer<Stroke> dabs) {
        Stroke stroke = new Stroke(spec, world);
        dabs.accept(stroke);
        return new Print(stroke.hash, stroke.writes);
    }
}
