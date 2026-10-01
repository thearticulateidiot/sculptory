package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.world.WorldReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Symmetry copies stand on the ground where they land ({@link SymmetricStep}): the ground search on flat ground,
 * slopes, cliffs, overhangs, caves, water, lava, plants and structures, without ground and at the ends of the build
 * height; copies on uneven ground doing exactly what the dab does on its own ground; a copy without ground writing
 * nothing; Flatten's one plane; paint depth and masks; one order-independent step; bounded reads.
 */
class SymmetricStepTest {
    private final BrushFixture f = new BrushFixture();
    private final int lava = f.states.state("minecraft:lava");
    private final int chest = f.states.state("minecraft:chest");

    /** Mirror east / west across x = 0.5: column x maps onto column -x. */
    private static final Symmetry MIRROR_X = new Symmetry(Symmetry.Mode.MIRROR_X, 1, 1);

    /** A full-pressure dab centred on column (x, z), standing on ground at {@code ground} (where a hit on it lies). */
    private static Dab on(int index, int x, int ground, int z) {
        return new Dab(index, x * 16 + 8, (ground + 1) * 16, z * 16 + 8, Dab.FULL_PRESSURE);
    }

    /** Column (x, z): stone from {@code from} to {@code top - 1}, grass at {@code top}, air above up to y 200. */
    private void column(FakeWorld world, int x, int z, int from, int top) {
        int last = Math.min(200, world.topYExclusive() - 1);
        for (int y = from; y <= last; y++) world.set(x, y, z, y < top ? f.stone : y == top ? f.grass : f.air);
    }

    /** The topmost terrain-solid y of a column between y 200 and {@code BrushFixture.FLOOR}, or FLOOR - 1. */
    private int top(FakeWorld world, int x, int z) {
        for (int y = 200; y >= BrushFixture.FLOOR; y--) {
            if (SurfaceScan.ground(f.states.flags(world.get(x, y, z)))) return y;
        }
        return BrushFixture.FLOOR - 1;
    }

    private static List<BrushFixture.Write> apply(BrushSpec spec, StrokeState state, FakeWorld world, Dab dab) {
        return BrushFixture.dab(spec, state, world, dab);
    }

    // ---- The ground search ----

    @Test
    void theSearchFindsTheTopmostGroundUnderOpenCellsWithin64BlocksOfTheDab() {
        FakeWorld world = new FakeWorld(f.states);
        int dabY = 61; // a dab on ground at 60
        column(world, 0, 0, 50, 60);
        assertEquals(60, SymmetricStep.ground(world, 0, 0, dabY), "flat");
        column(world, 1, 0, 50, 100);
        assertEquals(100, SymmetricStep.ground(world, 1, 0, dabY), "a hill 39 blocks higher");
        column(world, 2, 0, 50, 124);
        assertEquals(124, SymmetricStep.ground(world, 2, 0, dabY), "ground at 124: the window's top (125) is open");
        column(world, 3, 0, 50, 125);
        assertEquals(SurfaceScan.NONE, SymmetricStep.ground(world, 3, 0, dabY),
                "ground reaching 61 + 64: the window starts inside it");
        column(world, 4, 0, -64, -3);
        assertEquals(-3, SymmetricStep.ground(world, 4, 0, dabY), "a valley 64 blocks lower");
        column(world, 5, 0, -64, -4);
        assertEquals(SurfaceScan.NONE, SymmetricStep.ground(world, 5, 0, dabY), "lower than 64: only air in the window");
        assertEquals(SurfaceScan.NONE, SymmetricStep.ground(world, 6, 0, dabY), "nothing at all");

        // An overhang: the copy lands on top of it, as the brush's own surface scan does.
        column(world, 7, 0, 50, 60);
        for (int y = 70; y <= 72; y++) world.set(7, y, 0, f.stone);
        assertEquals(72, SymmetricStep.ground(world, 7, 0, dabY), "the top of the overhang");
        // A cave below the surface: the surface above it, when the window reaches the open air.
        column(world, 8, 0, 50, 100);
        for (int y = 55; y <= 65; y++) world.set(8, y, 0, f.air);
        assertEquals(100, SymmetricStep.ground(world, 8, 0, 56), "a dab on a cave floor: the ground above the cave");
        column(world, 9, 0, 50, 130);
        for (int y = 55; y <= 65; y++) world.set(9, y, 0, f.air);
        assertEquals(SurfaceScan.NONE, SymmetricStep.ground(world, 9, 0, 56), "rock above the whole window: no ground");

        // Fluids are open: the ground under them (the bed), as the brush sculpts it.
        column(world, 10, 0, 50, 50);
        for (int y = 51; y <= 60; y++) world.set(10, y, 0, f.water);
        assertEquals(50, SymmetricStep.ground(world, 10, 0, dabY), "under water");
        column(world, 11, 0, 50, 52);
        for (int y = 53; y <= 58; y++) world.set(11, y, 0, lava);
        assertEquals(52, SymmetricStep.ground(world, 11, 0, dabY), "under lava");
        column(world, 12, 0, 50, 60);
        world.set(12, 61, 0, f.shortGrass);
        assertEquals(60, SymmetricStep.ground(world, 12, 0, dabY), "plants are open");

        // A structure first: no ground (the brush never sculpts under stairs or chests either).
        column(world, 13, 0, 50, 60);
        world.set(13, 61, 0, f.stairs);
        assertEquals(SurfaceScan.NONE, SymmetricStep.ground(world, 13, 0, dabY), "stairs on top");
        column(world, 14, 0, 50, 60);
        world.set(14, 61, 0, chest);
        assertEquals(SurfaceScan.NONE, SymmetricStep.ground(world, 14, 0, dabY), "a chest on top");

        // An unloaded chunk: no ground, and nothing read.
        column(world, 40, 0, 50, 60);
        world.setLoaded(2, 0, false);
        assertEquals(SurfaceScan.NONE, SymmetricStep.ground(world, 40, 0, dabY));
        assertEquals(new Box(new BlockPos(3, -3, 4), new BlockPos(3, 125, 4)),
                SymmetricStep.searchColumn(3, 4, dabY, -64, 320));
    }

    @Test
    void theSearchIsClampedToTheBuildHeight() {
        FakeWorld world = new FakeWorld(f.states, 0, 128);
        column(world, 0, 0, 0, 126);
        assertEquals(126, SymmetricStep.ground(world, 0, 0, 125), "a dab near the top");
        world.set(0, 127, 0, f.stone);
        assertEquals(SurfaceScan.NONE, SymmetricStep.ground(world, 0, 0, 125),
                "ground up to the build limit: nothing above it is open");
        FakeWorld low = new FakeWorld(f.states, 0, 128);
        low.set(0, 0, 0, f.grass);
        assertEquals(0, SymmetricStep.ground(low, 0, 0, 3), "ground on the lowest cell");
        assertEquals(0, SymmetricStep.ground(low, 0, 0, -60), "a dab below the world: the window is clamped");
        assertEquals(SurfaceScan.NONE, SymmetricStep.ground(low, 0, 0, 5000), "far above: only the top cell, air");
        assertEquals(new Box(new BlockPos(0, 0, 0), new BlockPos(0, 67, 0)), SymmetricStep.searchColumn(0, 0, 3, 0, 128));
        assertEquals(new Box(new BlockPos(0, 127, 0), new BlockPos(0, 127, 0)),
                SymmetricStep.searchColumn(0, 0, 5000, 0, 128));

        // A copy standing on the highest ground still applies (its window is clamped like any dab's).
        FakeWorld tall = new FakeWorld(f.states, 0, 128);
        for (int x = -12; x <= 12; x++) {
            for (int z = -6; z <= 6; z++) column(tall, x, z, 0, x > 0 ? 126 : 100);
        }
        BrushSpec raise = BrushFixture.spec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE).withSymmetry(MIRROR_X);
        SymmetricStep step = SymmetricStep.of(raise, on(0, -6, 100, 0), tall);
        assertEquals(127 * 16, step.dabs().get(1).y16());
        List<BrushFixture.Write> writes = apply(raise, new StrokeState(), tall, on(0, -6, 100, 0));
        assertTrue(writes.stream().anyMatch(w -> w.x() > 0 && w.y() == 127), "the copy raised its columns to the limit");
    }

    // ---- The step ----

    @Test
    void theStepListsTheDabThenEachGroundedCopyAndTheCopiesWithoutGround() {
        FakeWorld world = new FakeWorld(f.states);
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) column(world, x, z, 50, x > 0 && z <= 0 ? 90 : 60);
        }
        column(world, 10, 10, 50, 140); // a pillar under the south-east copy's centre
        BrushSpec spec = BrushFixture.spec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE)
                .withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_XZ, 1, 1));
        Dab dab = on(4, -10, 60, -10);
        SymmetricStep step = SymmetricStep.of(spec, dab, world);
        List<Dab> copies = spec.symmetry().copies(dab);
        assertSame(dab, step.dabs().get(0), "the dab keeps its height");
        assertEquals(List.of(new Dab(4, 10 * 16 + 8, 91 * 16, -10 * 16 + 8, 255), new Dab(4, -10 * 16 + 8, 61 * 16, 10 * 16 + 8, 255)),
                step.dabs().subList(1, 3), "the north-east copy on the plateau, the south-west one on the plain");
        assertEquals(copies.subList(1, 4), step.searched());
        assertEquals(List.of(copies.get(3)), step.noGround(), "the pillar reaches past the window");
        assertEquals(step, SymmetricStep.of(spec, dab, world), "the same world gives the same step");

        // Without symmetry, or for a dab on every plane, nothing is searched.
        BrushSpec plain = BrushFixture.spec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        assertEquals(new SymmetricStep(List.of(dab), List.of(), List.of()), SymmetricStep.of(plain, dab, world));
        Dab centre = new Dab(0, 8, 61 * 16, 8, 255);
        assertEquals(List.of(centre), SymmetricStep.of(spec, centre, world).dabs());
    }

    @Test
    void copiesOutsideTheClipOrBeyondTheLimitAreNotSearched() {
        FakeWorld world = f.flat(60);
        Box westOnly = new Box(new BlockPos(-40, 50, -40), new BlockPos(-2, 100, 40));
        BrushSpec clipped = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                0, 1L, westOnly, MIRROR_X);
        Dab dab = on(0, -10, 60, 0);
        SymmetricStep step = SymmetricStep.of(clipped, dab, world);
        assertEquals(clipped.symmetry().copies(dab), step.dabs(), "the east copy misses the box: kept as it is");
        assertEquals(List.of(), step.searched());
        // A copy beyond the kernel's limit is kept as it is too, and fails the step as before.
        BrushSpec far = BrushFixture.spec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE)
                .withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, Symmetry.MAX_CENTRE2, 0));
        assertEquals(far.symmetry().copies(dab), SymmetricStep.of(far, dab, world).dabs());
        assertThrows(IllegalArgumentException.class, () -> apply(far, new StrokeState(), world, dab));
    }

    // ---- Copies on uneven ground ----

    /** Gentle bumps, the same on both sides of the plane: a function of |x| (column x mirrors onto -x). */
    private static int bumps(int x, int z) {
        return Math.floorMod(Math.abs(x) / 2 + z / 3 + ((Math.abs(x) * 5 + z * 3) & 1), 4);
    }

    /** West of the plane at 60 + bumps, east of it the same shape 30 blocks higher (a cliff at x = 0.5). */
    private FakeWorld cliff() {
        FakeWorld world = new FakeWorld(f.states);
        for (int x = -24; x <= 24; x++) {
            for (int z = -12; z <= 12; z++) column(world, x, z, BrushFixture.FLOOR, 60 + bumps(x, z) + (x > 0 ? 30 : 0));
        }
        return world;
    }

    @Test
    void aCopyOnHigherGroundChangesItExactlyAsTheDabChangesItsOwn() {
        Pattern sand = new Pattern.Single(f.sand);
        for (BrushTool tool : List.of(BrushTool.RAISE, BrushTool.LOWER, BrushTool.SMOOTH, BrushTool.PAINT)) {
            BrushSpec spec = new BrushSpec(tool, 4, 0.8f, Falloff.SMOOTH, Shape.CIRCLE, tool == BrushTool.PAINT ? sand : null,
                    SurfaceMask.ANY, 3, 0, 1L, null, MIRROR_X);
            FakeWorld world = cliff();
            StrokeState state = new StrokeState();
            int writes = 0;
            for (int i = 0; i < 10; i++) {
                // Each dab on its own column's ground, as the cursor lays it; west of the plane, footprints off it.
                int x = -9 + i % 3, z = -6 + i;
                writes += apply(spec, state, world, on(i, x, top(world, x, z), z)).size();
            }
            assertTrue(writes > 0, tool + " wrote nothing");
            int eastChanged = 0;
            FakeWorld before = cliff();
            for (int x = -16; x <= -1; x++) {
                for (int z = -12; z <= 12; z++) {
                    for (int y = BrushFixture.FLOOR; y <= 110; y++) {
                        assertEquals(world.get(x, y, z), world.get(-x, y + 30, z),
                                tool + ": column " + -x + "," + z + " is column " + x + " 30 blocks up, at y " + y);
                        if (world.get(-x, y + 30, z) != before.get(-x, y + 30, z)) eastChanged++;
                    }
                }
            }
            assertTrue(eastChanged > 0, tool + ": the copy on the higher ground changed nothing");
        }
    }

    @Test
    void aCopyOnLowerGroundOrUnderWaterWorksThereToo() {
        // East of the plane: a basin 25 blocks lower, flooded to 62. Lower there refills with water.
        FakeWorld world = new FakeWorld(f.states);
        for (int x = -16; x <= 16; x++) {
            for (int z = -10; z <= 10; z++) {
                if (x > 0) {
                    column(world, x, z, 20, 35);
                    for (int y = 36; y <= 62; y++) world.set(x, y, z, f.water);
                } else {
                    column(world, x, z, 20, 60);
                }
            }
        }
        BrushSpec lower = new BrushSpec(BrushTool.LOWER, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                0, 1L, null, MIRROR_X);
        Dab dab = on(0, -8, 60, 0);
        assertEquals(36 * 16, SymmetricStep.of(lower, dab, world).dabs().get(1).y16(), "the copy stands on the bed");
        List<BrushFixture.Write> writes = apply(lower, new StrokeState(), world, dab);
        assertEquals(f.water, world.get(8, 35, 0), "the bed lowered and refilled with water");
        assertEquals(f.air, world.get(-8, 60, 0), "the dab's own column lowered");
        assertTrue(writes.stream().anyMatch(w -> w.x() > 0 && w.y() == 35 && w.state() == f.water));
    }

    @Test
    void aCopyWithoutGroundWritesNothingAnywhere() {
        // Both sides flat at 60, but the east copy's centre column is a pillar up to 140 (past 61 + 64), or holds stairs:
        // the copy writes nothing, although the ground around its centre is at the dab's height.
        for (boolean stairs : new boolean[] {false, true}) {
            FakeWorld world = f.flat(60);
            if (stairs) {
                world.set(8, 61, 0, f.stairs);
            } else {
                column(world, 8, 0, 50, 140);
            }
            FakeWorld before = f.flat(60);
            if (stairs) {
                before.set(8, 61, 0, f.stairs);
            } else {
                column(before, 8, 0, 50, 140);
            }
            BrushSpec raise = new BrushSpec(BrushTool.RAISE, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                    0, 1L, null, MIRROR_X);
            Dab dab = on(0, -8, 60, 0);
            SymmetricStep step = SymmetricStep.of(raise, dab, world);
            assertEquals(List.of(dab), step.dabs());
            assertEquals(1, step.noGround().size());
            List<BrushFixture.Write> writes = apply(raise, new StrokeState(), world, dab);
            assertFalse(writes.isEmpty(), "the dab's own side is raised");
            for (BrushFixture.Write w : writes) assertTrue(w.x() < 0, (stairs ? "stairs" : "pillar") + ": wrote at " + w);
            for (int x = 1; x <= 20; x++) {
                for (int z = -8; z <= 8; z++) {
                    for (int y = 50; y <= 150; y++) assertEquals(before.get(x, y, z), world.get(x, y, z));
                }
            }
        }
    }

    // ---- Flatten, depth and masks ----

    @Test
    void flattenBringsEveryCopyToTheStrokesOnePlane() {
        // West flat at 60, the target; east a plateau at 80 (north) and a valley at 44 (south), both beyond the brush's
        // reach of 12 blocks from the dab's height. Every copy flattens toward y 60, 12 blocks a dab at most.
        FakeWorld world = new FakeWorld(f.states);
        for (int x = -16; x <= 16; x++) {
            for (int z = -16; z <= 16; z++) column(world, x, z, 20, x <= 0 ? 60 : z < 0 ? 80 : 44);
        }
        BrushSpec flatten = new BrushSpec(BrushTool.FLATTEN, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                60, 1L, null, new Symmetry(Symmetry.Mode.MIRROR_XZ, 1, 1));
        StrokeState state = new StrokeState();
        List<Integer> northEast = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            apply(flatten, state, world, on(i, -8, 60, -8));
            northEast.add(top(world, 8, -8));
        }
        assertEquals(List.of(68, 60, 60, 60), northEast, "the plateau cut down 12 blocks, then to the plane");
        assertEquals(60, top(world, 8, 8), "the valley filled up to the plane");
        for (int x = 5; x <= 11; x++) {
            for (int z = 5; z <= 11; z++) {
                if ((x - 8) * (x - 8) + (z - 8) * (z - 8) > 16) continue;
                assertEquals(60, top(world, x, -z), "north-east column " + x + "," + -z);
                assertEquals(60, top(world, x, z), "south-east column " + x + "," + z);
            }
        }
        assertEquals(60, top(world, -8, -8), "the dab's own ground was already on the plane");
    }

    @Test
    void paintDepthAndMasksWorkOnACopyAsOnTheDab() {
        FakeWorld world = cliff();
        BrushSpec paint = new BrushSpec(BrushTool.PAINT, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(f.sand),
                SurfaceMask.ANY, 3, 0, 1L, null, MIRROR_X);
        int ground = top(world, -8, 0);
        apply(paint, new StrokeState(), world, on(0, -8, ground, 0));
        for (int y = ground + 28; y <= ground + 30; y++) assertEquals(f.sand, world.get(8, y, 0), "the copy's top 3 at " + y);
        assertEquals(f.stone, world.get(8, ground + 27, 0), "depth 3, not more");
        // A height mask is absolute: the copy on the higher ground is outside 55-70, the dab inside it.
        FakeWorld masked = cliff();
        BrushSpec low = new BrushSpec(BrushTool.PAINT, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, new Pattern.Single(f.sand),
                new SurfaceMask.Elevation(55, 70), 1, 0, 1L, null, MIRROR_X);
        List<BrushFixture.Write> writes = apply(low, new StrokeState(), masked, on(0, -8, ground, 0));
        assertFalse(writes.isEmpty());
        for (BrushFixture.Write w : writes) assertTrue(w.x() < 0 && w.y() <= 70, "painted at " + w);
    }

    // ---- One step, one snapshot ----

    @Test
    void aGroundedStepIsTheSameInAnyOrder() {
        // Rotate 4 around (0.5, 0.5) over quadrants at different heights, the dab near the centre: the four footprints
        // overlap and the copies stand at four heights.
        IntBinaryOperator quadrants = (x, z) -> 58 + Math.floorMod(x + 2 * z, 3) + (x > 0 ? 5 : 0) + (z > 0 ? 9 : 0);
        for (BrushTool tool : List.of(BrushTool.SMOOTH, BrushTool.RAISE, BrushTool.FLATTEN)) {
            BrushSpec spec = new BrushSpec(tool, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 62, 2L,
                    null, new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1));
            Dab dab = new Dab(0, 32, 62 * 16, -8, 255);
            SymmetricStep step = SymmetricStep.of(spec, dab, f.terrain(quadrants));
            assertEquals(4, step.dabs().size());
            assertEquals(4, step.dabs().stream().map(Dab::y16).distinct().count(), "four heights: " + step.dabs());
            List<BrushFixture.Write> first = null;
            List<List<Dab>> orders = List.of(step.dabs(), List.of(step.dabs().get(3), step.dabs().get(1),
                    step.dabs().get(0), step.dabs().get(2)), List.of(step.dabs().get(2), step.dabs().get(3),
                    step.dabs().get(1), step.dabs().get(0)));
            for (List<Dab> order : orders) {
                FakeWorld world = f.terrain(quadrants);
                List<BrushFixture.Write> writes = new ArrayList<>();
                BrushKernels.forTool(tool).applyStep(spec, order, new StrokeState(), world, (x, y, z, h) -> {
                    writes.add(new BrushFixture.Write(x, y, z, h));
                    world.set(x, y, z, h);
                });
                if (first == null) {
                    first = writes;
                    assertFalse(writes.isEmpty(), tool.toString());
                } else {
                    assertEquals(first, writes, tool + " in another order");
                }
            }
            // apply is exactly the located step.
            FakeWorld world = f.terrain(quadrants);
            assertEquals(first, apply(spec, new StrokeState(), world, dab), tool.toString());
        }
    }

    // ---- Columns shared by dabs at different heights ----

    /**
     * Ground at y 90 west of the plane (x <= 0) and at y 60 east of it, a cliff at the mirror plane x = 0.5, with bumps
     * of up to two blocks along z (none on the row z = 0).
     */
    private FakeWorld cliffAtThePlane() {
        FakeWorld world = new FakeWorld(f.states);
        for (int x = -24; x <= 24; x++) {
            for (int z = -12; z <= 12; z++) column(world, x, z, 20, (x >= 1 ? 60 : 90) + Math.floorMod(x * 3 + z * 5, 3));
        }
        return world;
    }

    /** "" when {@code actual} holds {@code east}'s cells for columns x >= 1 and {@code west}'s for the rest. */
    private static String sides(FakeWorld actual, FakeWorld east, FakeWorld west) {
        for (int x = -24; x <= 24; x++) {
            for (int z = -12; z <= 12; z++) {
                for (int y = 20; y <= 130; y++) {
                    int expected = (x >= 1 ? east : west).get(x, y, z);
                    if (actual.get(x, y, z) != expected) {
                        return x + "," + y + "," + z + ": " + actual.get(x, y, z) + ", alone " + expected;
                    }
                }
            }
        }
        return "";
    }

    private static void step(BrushSpec spec, List<Dab> dabs, StrokeState state, FakeWorld world) {
        BrushKernels.forTool(spec.tool()).applyStep(spec, dabs, state, world, world::set);
    }

    /**
     * A cliff at the mirror plane, the dab within its radius of it: a radius-8 dab 3 columns east of the plane on the low
     * side (y 60) has its copy 3 columns west on the cliff top (y 90), and their footprints share the columns -5 to 5. On
     * each side only the dab standing there finds the surface, so each side ends exactly as a dab without symmetry at that
     * side's position and height leaves it, for every tool and in either step order. (Before, the copy, first in step
     * order, decided the shared columns and found nothing on the low side: they never moved.)
     */
    @Test
    void aCliffAtThePlaneIsShapedOnEachSideAsEachDabAloneWould() {
        Pattern mix = new Pattern.Weighted(new int[] {f.sand, f.dirt}, new int[] {2, 1}, 5L);
        for (BrushTool tool : BrushTool.TERRAIN) {
            Pattern material = tool == BrushTool.PAINT ? new Pattern.Single(f.sand) : tool == BrushTool.PALETTE ? mix : null;
            BrushSpec spec = new BrushSpec(tool, 8, 1f, Falloff.LINEAR, Shape.CIRCLE, material, SurfaceMask.ANY, 2, 75, 1L,
                    null, MIRROR_X);
            BrushSpec plain = spec.withSymmetry(Symmetry.NONE);
            Dab dab = on(0, 3, 60, 0);
            SymmetricStep located = SymmetricStep.of(spec, dab, cliffAtThePlane());
            assertEquals(List.of(dab, new Dab(0, -3 * 16 + 8, 91 * 16, 8, 255)), located.dabs());
            FakeWorld east = cliffAtThePlane(), west = cliffAtThePlane();
            step(plain, List.of(dab), new StrokeState(), east);
            step(plain, List.of(located.dabs().get(1)), new StrokeState(), west);
            assertFalse(sides(east, cliffAtThePlane(), cliffAtThePlane()).isEmpty(), tool + ": the dab alone changes nothing");
            assertFalse(sides(cliffAtThePlane(), cliffAtThePlane(), west).isEmpty(), tool + ": the copy alone changes nothing");
            for (List<Dab> order : List.of(located.dabs(), located.dabs().reversed())) {
                FakeWorld world = cliffAtThePlane();
                step(spec, order, new StrokeState(), world);
                assertEquals("", sides(world, east, west), tool + " listed as " + order);
            }
            FakeWorld applied = cliffAtThePlane();
            apply(spec, new StrokeState(), applied, dab);
            assertEquals("", sides(applied, east, west), tool + " through apply");
        }
        // Raise at full strength: every footprint column on each side rises one block, next to the plane too.
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 8, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L, null, MIRROR_X);
        FakeWorld world = cliffAtThePlane();
        apply(raise, new StrokeState(), world, on(0, 3, 60, 0));
        for (int x = 1; x <= 5; x++) assertEquals(61, top(world, x, 0), "the dab's column " + x);
        for (int x = -5; x <= 0; x++) assertEquals(91, top(world, x, 0), "the copy's column " + x);
    }

    /**
     * A column's weight counts only the dabs whose scan found its surface: over the cliff, dab A stands low (y 61) at
     * column -1, next to the cliff top, and B stands high (y 91) at column 3, over the low side; A finds only the low
     * surface, B only the high one. Column 0 (high) is a block from A and three from B: only B's weight (0.4 with linear
     * falloff at radius 5) counts, so three steps raise it one block; with A's 0.8 they would raise it two. Each side
     * ends as its dab alone leaves it.
     */
    @Test
    void aSharedColumnsWeightCountsOnlyTheDabsThatFoundItsSurface() {
        BrushSpec raise = new BrushSpec(BrushTool.RAISE, 5, 1f, Falloff.LINEAR, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L);
        FakeWorld world = cliffAtThePlane(), east = cliffAtThePlane(), west = cliffAtThePlane();
        StrokeState both = new StrokeState(), lowOnly = new StrokeState(), highOnly = new StrokeState();
        for (int i = 0; i < 3; i++) {
            Dab a = new Dab(i, -1 * 16 + 8, 61 * 16, 8, 255), b = new Dab(i, 3 * 16 + 8, 91 * 16, 8, 255);
            step(raise, List.of(a, b), both, world);
            step(raise, List.of(a), lowOnly, east);
            step(raise, List.of(b), highOnly, west);
        }
        assertEquals(91, top(world, 0, 0), "B's weight alone: 3 × 0.4 is one block");
        assertEquals(90, top(world, -1, 0), "3 × 0.2 from B: not a block");
        assertEquals(61, top(world, 1, 0), "A's side: 3 × 0.6 is one block");
        assertEquals("", sides(world, east, west));
    }

    @Test
    void locatingReadsOneBoundedColumnPerCopyAndNothingForTheDab() {
        FakeWorld terrain = cliff();
        for (boolean hints : new boolean[] {false, true}) {
            terrain.setHeightHints(hints);
            CountingReader reader = new CountingReader(terrain);
            BrushSpec spec = BrushFixture.spec(BrushTool.RAISE, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE)
                    .withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_XZ, 1, 1));
            Dab dab = on(0, -8, top(terrain, -8, -6), -6);
            SymmetricStep step = SymmetricStep.of(spec, dab, reader);
            assertEquals(3, step.searched().size());
            List<Box> columns = new ArrayList<>();
            for (Dab copy : step.searched()) {
                columns.add(SymmetricStep.searchColumn(copy.blockX(), copy.blockZ(), dab.blockY(), -64, 320));
            }
            for (int[] read : reader.reads) {
                assertTrue(columns.stream().anyMatch(b -> b.contains(read[0], read[1], read[2])),
                        "read outside the copies' columns: " + Arrays.toString(read));
            }
            int most = 3 * (2 * SymmetricStep.GROUND_SEARCH + 1);
            assertTrue(reader.reads.size() <= most, reader.reads.size() + " reads");
            if (hints) assertTrue(reader.reads.size() <= 3 * 3, "with height hints, a few cells: " + reader.reads.size());
            reader.reads.clear();
            SymmetricStep.of(BrushFixture.spec(BrushTool.RAISE, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE), dab, reader);
            assertEquals(0, reader.reads.size(), "no copies, nothing read");
        }
    }

    /** A reader that records every cell read. */
    private static final class CountingReader implements WorldReader {
        final FakeWorld world;
        final List<int[]> reads = new ArrayList<>();

        CountingReader(FakeWorld world) {
            this.world = world;
        }

        @Override
        public StateSpace states() {
            return world.states();
        }

        @Override
        public int bottomY() {
            return world.bottomY();
        }

        @Override
        public int topYExclusive() {
            return world.topYExclusive();
        }

        @Override
        public boolean isLoaded(int cx, int cz) {
            return world.isLoaded(cx, cz);
        }

        @Override
        public int get(int x, int y, int z) {
            reads.add(new int[] {x, y, z});
            return world.get(x, y, z);
        }

        @Override
        public BlockEntityData tile(int x, int y, int z) {
            return world.tile(x, y, z);
        }

        @Override
        public void copySection(int sx, int sy, int sz, SectionBuffer into) {
            world.copySection(sx, sy, sz, into);
        }

        @Override
        public int heightHint(int x, int z) {
            return world.heightHint(x, z);
        }
    }
}
