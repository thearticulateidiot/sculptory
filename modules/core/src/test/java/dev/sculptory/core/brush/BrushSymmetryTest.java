package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Brush symmetry: each mode's copies (half-block and whole-block centres), repeats dropped on planes and at the
 * centre, a step read as one snapshot whatever order its dabs are listed in, and the mask and clip box on every copy.
 */
class BrushSymmetryTest {
    private final BrushFixture f = new BrushFixture();

    private static Dab dab16(int x16, int y16, int z16) {
        return new Dab(3, x16, y16, z16, 200);
    }

    private static List<int[]> positions(List<Dab> dabs) {
        List<int[]> out = new ArrayList<>();
        for (Dab d : dabs) out.add(new int[] {d.x16(), d.z16()});
        return out;
    }

    private static String text(List<Dab> dabs) {
        StringBuilder s = new StringBuilder();
        for (int[] p : positions(dabs)) s.append('(').append(p[0]).append(',').append(p[1]).append(')');
        return s.toString();
    }

    // ---- The copies ----

    @Test
    void eachModeCopiesTheDabAroundAHalfBlockCentre() {
        // Centre (10.5, -3.5): a block centre. The dab at (13.25, -1.75) is 2.75 east and 1.75 south of it.
        Dab dab = dab16(212, 1000, -28);
        int x2 = 21, z2 = -7;
        assertEquals("(212,-28)", text(new Symmetry(Symmetry.Mode.OFF, x2, z2).copies(dab)));
        assertEquals("(212,-28)(124,-28)", text(new Symmetry(Symmetry.Mode.MIRROR_X, x2, z2).copies(dab)));
        assertEquals("(212,-28)(212,-84)", text(new Symmetry(Symmetry.Mode.MIRROR_Z, x2, z2).copies(dab)));
        assertEquals("(212,-28)(124,-28)(212,-84)(124,-84)", text(new Symmetry(Symmetry.Mode.MIRROR_XZ, x2, z2).copies(dab)));
        assertEquals("(212,-28)(124,-84)", text(new Symmetry(Symmetry.Mode.ROTATE_2, x2, z2).copies(dab)));
        // Quarter turns clockwise seen from above: east of the centre goes south, south goes west.
        // Offsets (44, 28) -> (-28, 44) -> (-44, -28) -> (28, -44) around (168, -56).
        assertEquals("(212,-28)(140,-12)(124,-84)(196,-100)", text(new Symmetry(Symmetry.Mode.ROTATE_4, x2, z2).copies(dab)));
        for (Dab copy : new Symmetry(Symmetry.Mode.ROTATE_4, x2, z2).copies(dab)) {
            assertEquals(dab.index(), copy.index());
            assertEquals(dab.y16(), copy.y16());
            assertEquals(dab.pressure(), copy.pressure());
        }
    }

    @Test
    void aBlockEdgeCentreMirrorsColumnsOntoColumns() {
        // Centre x = 4 (a block edge): column 5 (centre 5.5) mirrors onto column 2 (centre 2.5).
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 8, 0);
        Dab onColumn = dab16(5 * 16 + 8, 0, 8);
        assertEquals("(88,8)(40,8)", text(mirror.copies(onColumn)));
        // A block centre (4.5): column 6 mirrors onto column 2.
        assertEquals("(104,8)(40,8)", text(new Symmetry(Symmetry.Mode.MIRROR_X, 9, 0).copies(dab16(6 * 16 + 8, 0, 8))));
    }

    @Test
    void copiesOnAPlaneOrAtTheCentreAreDroppedAndCounted() {
        Symmetry mirrorX = new Symmetry(Symmetry.Mode.MIRROR_X, 21, -7);
        Dab onPlane = dab16(168, 900, 40);  // x = 10.5, on the plane
        assertEquals(1, mirrorX.copies(onPlane).size());
        assertSame(onPlane, mirrorX.copies(onPlane).get(0));
        assertEquals(1, mirrorX.copyCount(onPlane));
        Dab nearPlane = dab16(169, 900, 40); // 1/16 block off it: two copies
        assertEquals(2, mirrorX.copies(nearPlane).size());

        Symmetry both = new Symmetry(Symmetry.Mode.MIRROR_XZ, 21, -7);
        assertEquals("(168,40)(168,-152)", text(both.copies(onPlane)), "on the x plane: its z mirror only");
        assertEquals(2, both.copyCount(onPlane));
        Dab centre = dab16(168, 900, -56);
        for (Symmetry.Mode mode : Symmetry.Mode.values()) {
            Symmetry s = new Symmetry(mode, 21, -7);
            assertEquals(1, s.copies(centre).size(), mode + " at the centre");
            assertEquals(1, s.copyCount(centre), mode + " at the centre");
            assertEquals(mode.copies(), s.copyCount(dab16(300, 0, 17)), mode + " off every plane");
            assertEquals(mode.copies(), s.copies(dab16(300, 0, 17)).size(), mode + " off every plane");
        }
        // Rotate 2 around a block edge: only the centre point itself maps onto itself.
        Symmetry half = new Symmetry(Symmetry.Mode.ROTATE_2, 0, 0);
        assertEquals(2, half.copyCount(dab16(0, 0, 8)));
        assertEquals(1, half.copyCount(dab16(0, 0, 0)));
    }

    @Test
    void theSymmetryValueIsValidated() {
        assertEquals(Symmetry.NONE, new Symmetry(Symmetry.Mode.OFF, 5, -9), "Off has no centre");
        int max = Symmetry.MAX_CENTRE2;
        assertDoesNotThrow(() -> new Symmetry(Symmetry.Mode.MIRROR_X, max, -max));
        assertThrows(IllegalArgumentException.class, () -> new Symmetry(Symmetry.Mode.MIRROR_X, max + 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Symmetry(Symmetry.Mode.MIRROR_Z, 0, -max - 1));
        assertThrows(NullPointerException.class, () -> new Symmetry(null, 0, 0));
        // Rotate 4 needs a block centre or a block corner: x and z both odd or both even.
        assertDoesNotThrow(() -> new Symmetry(Symmetry.Mode.ROTATE_4, 3, -5));
        assertDoesNotThrow(() -> new Symmetry(Symmetry.Mode.ROTATE_4, 4, -6));
        assertThrows(IllegalArgumentException.class, () -> new Symmetry(Symmetry.Mode.ROTATE_4, 3, 4));
        assertDoesNotThrow(() -> new Symmetry(Symmetry.Mode.ROTATE_2, 3, 4), "a half turn works around any centre");
        // A copy beyond the integer range of dab coordinates.
        Symmetry far = new Symmetry(Symmetry.Mode.MIRROR_X, max, 0);
        Dab west = new Dab(0, -Integer.MAX_VALUE, 0, 0, 255);
        assertThrows(IllegalArgumentException.class, () -> far.copies(west));
        assertEquals(2, far.copyCount(west), "counting never throws");
    }

    @Test
    void theSpecCarriesItsSymmetry() {
        BrushSpec plain = BrushFixture.spec(BrushTool.RAISE, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        assertEquals(Symmetry.NONE, plain.symmetry());
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 1, 1);
        BrushSpec mirrored = plain.withSymmetry(mirror);
        assertEquals(mirror, mirrored.symmetry());
        assertNotEquals(plain, mirrored, "the symmetry is part of the spec");
        Box clip = Box.of(new BlockPos(0, 0, 0), new BlockPos(3, 3, 3));
        assertEquals(mirror, mirrored.withClip(clip).symmetry(), "a clip keeps the symmetry");
        assertEquals(plain, mirrored.withSymmetry(Symmetry.NONE));
        assertThrows(NullPointerException.class, () -> plain.withSymmetry(null));
    }

    // ---- Steps on symmetric terrain ----

    /** Terrain symmetric under every mode around centre (x2, z2): a function of the offsets' squares. */
    private static IntBinaryOperator symmetricTerrain(int x2, int z2) {
        return (x, z) -> {
            long ux = 2L * x + 1 - x2, uz = 2L * z + 1 - z2;
            long r = ux * ux + uz * uz, p = ux * ux * uz * uz;
            return 57 + (int) ((r / 29) % 5) + (int) (p % 3);
        };
    }

    /** The column (x, z) maps to under the mode's transform {@code k}, around (x2, z2). */
    private static int[] image(Symmetry.Mode mode, int k, int x, int z, int x2, int z2) {
        int mx = x2 - 1 - x, mz = z2 - 1 - z;
        // Offsets doubled: ux = 2x + 1 - x2; a quarter turn maps (ux, uz) to (-uz, ux).
        int ux = 2 * x + 1 - x2, uz = 2 * z + 1 - z2;
        return switch (mode) {
            case OFF -> new int[] {x, z};
            case MIRROR_X -> new int[] {mx, z};
            case MIRROR_Z -> new int[] {x, mz};
            case MIRROR_XZ -> k == 1 ? new int[] {mx, z} : k == 2 ? new int[] {x, mz} : new int[] {mx, mz};
            case ROTATE_2 -> new int[] {mx, mz};
            case ROTATE_4 -> {
                int rx = ux, rz = uz;
                for (int turn = 0; turn < k; turn++) {
                    int t = rx;
                    rx = -rz;
                    rz = t;
                }
                yield new int[] {(rx - 1 + x2) / 2, (rz - 1 + z2) / 2};
            }
        };
    }

    /** "" when every column of the area holds what its images hold, else the first difference. */
    private String asymmetry(FakeWorld world, Symmetry.Mode mode, int x2, int z2, int reach) {
        int cx = Math.floorDiv(x2, 2), cz = Math.floorDiv(z2, 2);
        for (int x = cx - reach; x <= cx + reach; x++) {
            for (int z = cz - reach; z <= cz + reach; z++) {
                for (int k = 1; k < mode.copies(); k++) {
                    int[] other = image(mode, k, x, z, x2, z2);
                    for (int y = BrushFixture.FLOOR; y <= 100; y++) {
                        if (world.get(x, y, z) != world.get(other[0], y, other[1])) {
                            return mode + ": " + x + "," + y + "," + z + " differs from " + other[0] + "," + y + "," + other[1];
                        }
                    }
                }
            }
        }
        return "";
    }

    /** A wandering stroke of eight dabs, all at least a little off every plane. */
    private static List<Dab> wander(int x2, int z2) {
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            dabs.add(new Dab(i, 8 * x2 + 37 + i * 21, 61 * 16 + (i % 3) * 4, 8 * z2 + 9 + i * i * 5 - 40, 140 + (i * 23) % 116));
        }
        return dabs;
    }

    @Test
    void everyModeKeepsSymmetricTerrainSymmetricWithEveryTool() {
        int[][] centres = {{1, 1}, {0, 0}, {-3, 5}, {2, 7}};
        Pattern sand = new Pattern.Single(f.sand);
        for (int[] c : centres) {
            int x2 = c[0], z2 = c[1];
            for (Symmetry.Mode mode : Symmetry.Mode.values()) {
                if (mode == Symmetry.Mode.OFF || (mode == Symmetry.Mode.ROTATE_4 && ((x2 ^ z2) & 1) != 0)) continue;
                Symmetry symmetry = new Symmetry(mode, x2, z2);
                for (BrushTool tool : BrushTool.TERRAIN) {
                    Pattern material = tool == BrushTool.PAINT || tool == BrushTool.PALETTE ? sand : null;
                    BrushSpec spec = new BrushSpec(tool, 4, 0.8f, Falloff.SMOOTH, tool == BrushTool.LOWER ? Shape.SQUARE : Shape.CIRCLE,
                            material, SurfaceMask.ANY, 2, 60, 1L, null, symmetry);
                    FakeWorld world = f.terrain(symmetricTerrain(x2, z2));
                    assertEquals("", asymmetry(world, mode, x2, z2, 20), "the terrain itself");
                    StrokeState state = new StrokeState();
                    int writes = 0;
                    for (Dab d : wander(x2, z2)) writes += BrushFixture.dab(spec, state, world, d).size();
                    assertTrue(writes > 0, tool + " " + mode + " wrote nothing");
                    assertEquals("", asymmetry(world, mode, x2, z2, 20), tool + " around " + x2 / 2.0 + "," + z2 / 2.0);
                }
            }
        }
    }

    @Test
    void aDabOnThePlaneWritesWhatTheSameDabWithoutSymmetryWrites() {
        BrushSpec plain = new BrushSpec(BrushTool.RAISE, 5, 0.9f, Falloff.LINEAR, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, 1L);
        BrushSpec mirrored = plain.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_XZ, 1, 1));
        Dab centre = new Dab(0, 8, 61 * 16, 8, 255);
        assertEquals(BrushFixture.dab(plain, new StrokeState(), f.terrain((x, z) -> 60 + (x * x + z * z) % 3), centre),
                BrushFixture.dab(mirrored, new StrokeState(), f.terrain((x, z) -> 60 + (x * x + z * z) % 3), centre));
    }

    // ---- One step, one snapshot ----

    /** Rolling, asymmetric terrain. */
    private static final IntBinaryOperator ROLLING = (x, z) -> 58 + Math.floorMod(x / 2 + z / 3 + ((x * 5 + z * 3) & 1), 6);

    private static List<List<Dab>> permutations(List<Dab> dabs) {
        List<List<Dab>> all = new ArrayList<>();
        permute(new ArrayList<>(dabs), 0, all);
        return all;
    }

    private static void permute(List<Dab> dabs, int from, List<List<Dab>> out) {
        if (from == dabs.size()) {
            out.add(List.copyOf(dabs));
            return;
        }
        for (int i = from; i < dabs.size(); i++) {
            java.util.Collections.swap(dabs, from, i);
            permute(dabs, from + 1, out);
            java.util.Collections.swap(dabs, from, i);
        }
    }

    private List<BrushFixture.Write> step(BrushSpec spec, List<Dab> dabs, FakeWorld world, StrokeState state) {
        List<BrushFixture.Write> writes = new ArrayList<>();
        BrushKernels.forTool(spec.tool()).applyStep(spec, dabs, state, world, (x, y, z, h) -> {
            writes.add(new BrushFixture.Write(x, y, z, h));
            world.set(x, y, z, h);
        });
        return writes;
    }

    @Test
    void overlappingCopiesGiveTheSameStepInAnyOrder() {
        for (BrushTool tool : List.of(BrushTool.SMOOTH, BrushTool.RAISE, BrushTool.LOWER, BrushTool.FLATTEN, BrushTool.PALETTE)) {
            Pattern mix = new Pattern.Weighted(new int[] {f.sand, f.dirt}, new int[] {1, 2}, 4L);
            BrushSpec spec = new BrushSpec(tool, 5, 1f, Falloff.CONSTANT, Shape.CIRCLE,
                    tool == BrushTool.PALETTE ? mix : null, SurfaceMask.ANY, 1, 59, 2L, null,
                    new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1));
            // 1.5 blocks east and 1 north of the centre (0.5, 0.5): all four footprints overlap.
            List<Dab> copies = spec.symmetry().copies(new Dab(0, 32, 62 * 16, -8, 255));
            assertEquals(4, copies.size());
            List<BrushFixture.Write> first = null;
            int[] firstHeights = null;
            for (List<Dab> order : permutations(copies)) {
                FakeWorld world = f.terrain(ROLLING);
                StrokeState state = new StrokeState();
                List<BrushFixture.Write> writes = new ArrayList<>(step(spec, order, world, state));
                writes.addAll(step(spec, order, world, state)); // a second step, over the accumulators left
                int[] heights = new int[21 * 21];
                for (int x = -10; x <= 10; x++) {
                    for (int z = -10; z <= 10; z++) heights[(x + 10) * 21 + z + 10] = f.surface(world, x, z);
                }
                if (first == null) {
                    first = writes;
                    firstHeights = heights;
                    assertFalse(writes.isEmpty(), tool.toString());
                } else {
                    assertEquals(first, writes, tool + " listed as " + text(order));
                    assertTrue(java.util.Arrays.equals(firstHeights, heights), tool.toString());
                }
            }
            // A dab listed twice counts once.
            List<Dab> three = copies.subList(0, 3);
            List<Dab> doubled = List.of(copies.get(2), copies.get(0), copies.get(1), copies.get(2));
            assertEquals(step(spec, three, f.terrain(ROLLING), new StrokeState()),
                    step(spec, doubled, f.terrain(ROLLING), new StrokeState()), tool.toString());
        }
    }

    @Test
    void overlappingCopiesMoveAColumnOnceByTheStrongestWeight() {
        // Radius 3, full strength, no falloff: each dab raises its columns exactly one block. The mirrored copy
        // one block away covers nearly the same columns; a column under both still rises one block, not two.
        BrushSpec spec = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 1, 1));
        FakeWorld world = f.flat(60);
        BrushFixture.dab(spec, new StrokeState(), world, new Dab(0, 24, 61 * 16, 8, 255));
        for (int x = -6; x <= 6; x++) {
            for (int z = -6; z <= 6; z++) {
                int top = f.surface(world, x, z);
                assertTrue(top == 60 || top == 61, "column " + x + "," + z + " at " + top);
            }
        }
        assertEquals(61, f.surface(world, 0, 0), "a column under both copies rose one block");
        // Smooth reads the world before the step: the copies see the same heights, and the result is mirrored.
        BrushSpec smooth = new BrushSpec(BrushTool.SMOOTH, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY,
                0, 0, 1L, null, new Symmetry(Symmetry.Mode.MIRROR_X, 1, 1));
        FakeWorld ridged = f.terrain((x, z) -> 58 + Math.abs(2 * x + 1 - 1) / 2 % 4 + (z & 1));
        BrushFixture.dab(smooth, new StrokeState(), ridged, new Dab(0, 20, 61 * 16, 8, 255));
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                assertEquals(f.surface(ridged, x, z), f.surface(ridged, -x, z), "column " + x + "," + z + " and its mirror");
            }
        }
    }

    @Test
    void stepsHoldOneToFourDabs() {
        BrushSpec spec = BrushFixture.spec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        BrushKernel kernel = BrushKernels.forTool(BrushTool.RAISE);
        FakeWorld world = f.flat(60);
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(spec, List.of(), new StrokeState(), world, (x, y, z, h) -> {}));
        List<Dab> five = new ArrayList<>();
        for (int i = 0; i < 5; i++) five.add(new Dab(0, i * 16, 61 * 16, 0, 255));
        assertThrows(IllegalArgumentException.class, () -> kernel.applyStep(spec, five, new StrokeState(), world, (x, y, z, h) -> {}));
        // A copy outside the world fails the step like a dab outside it.
        BrushSpec far = spec.withSymmetry(new Symmetry(Symmetry.Mode.MIRROR_X, Symmetry.MAX_CENTRE2, 0));
        assertThrows(IllegalArgumentException.class,
                () -> kernel.apply(far, new Dab(0, 0, 61 * 16, 0, 255), new StrokeState(), world, (x, y, z, h) -> {}));
    }

    // ---- Masks and the clip box on every copy ----

    @Test
    void theMaskAppliesToEveryCopy() {
        // Sand on the west half only: a mirrored Raise masked to sand changes the west copy and not the east one.
        FakeWorld world = f.flat(60);
        for (int x = -20; x <= 0; x++) {
            for (int z = -20; z <= 20; z++) world.set(x, 60, z, f.sand);
        }
        SurfaceMask onSand = new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:sand"))));
        BrushSpec spec = new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, onSand, 0, 0, 1L,
                null, new Symmetry(Symmetry.Mode.MIRROR_X, 1, 1));
        List<BrushFixture.Write> writes = BrushFixture.dab(spec, new StrokeState(), world, new Dab(0, 10 * 16 + 8, 61 * 16, 8, 255));
        assertFalse(writes.isEmpty());
        for (BrushFixture.Write w : writes) assertTrue(w.x() <= 0 && w.x() >= -13, "wrote at " + w);
        assertEquals(61, f.surface(world, -10, 0));
        assertEquals(60, f.surface(world, 10, 0));
        // Inverted: the east copy only.
        FakeWorld inverted = f.flat(60);
        for (int x = -20; x <= 0; x++) {
            for (int z = -20; z <= 20; z++) inverted.set(x, 60, z, f.sand);
        }
        BrushFixture.dab(new BrushSpec(BrushTool.RAISE, 3, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, new SurfaceMask.Not(onSand),
                0, 0, 1L, null, spec.symmetry()), new StrokeState(), inverted, new Dab(0, 10 * 16 + 8, 61 * 16, 8, 255));
        assertEquals(60, f.surface(inverted, -10, 0));
        assertEquals(61, f.surface(inverted, 10, 0));
    }

    @Test
    void theClipBoxAppliesToEveryCopy() {
        Symmetry rotate = new Symmetry(Symmetry.Mode.ROTATE_4, 1, 1);
        // The box holds the east copy's footprint and half of the south one's; the other two miss it.
        Box clip = new Box(new BlockPos(-2, 50, -2), new BlockPos(20, 100, 20));
        for (BrushTool tool : BrushTool.TERRAIN) {
            Pattern material = tool == BrushTool.PAINT || tool == BrushTool.PALETTE ? new Pattern.Single(f.sand) : null;
            BrushSpec spec = new BrushSpec(tool, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, material, SurfaceMask.ANY, 1, 58, 1L,
                    clip, rotate);
            FakeWorld world = f.terrain(ROLLING);
            FakeWorld before = f.terrain(ROLLING);
            List<BrushFixture.Write> writes = new ArrayList<>();
            StrokeState state = new StrokeState();
            for (int i = 0; i < 4; i++) writes.addAll(BrushFixture.dab(spec, state, world, new Dab(i, 12 * 16 + 8, 62 * 16, 16 * 3 + 8, 255)));
            assertFalse(writes.isEmpty(), tool + " wrote nothing inside the box");
            for (BrushFixture.Write w : writes) assertTrue(clip.contains(w.x(), w.y(), w.z()), tool + " wrote outside the box at " + w);
            boolean south = writes.stream().anyMatch(w -> w.z() > 8 && w.x() < 4);
            boolean east = writes.stream().anyMatch(w -> w.x() > 8);
            if (tool != BrushTool.FLATTEN) assertTrue(east && south, tool + ": east " + east + ", south " + south);
            for (int x = -20; x < -2; x++) {
                for (int z = -20; z <= 20; z++) {
                    for (int y = 50; y <= 100; y++) assertEquals(before.get(x, y, z), world.get(x, y, z), tool + " west of the box");
                }
            }
        }
    }
}
