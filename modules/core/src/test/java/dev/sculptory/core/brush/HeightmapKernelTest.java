package dev.sculptory.core.brush;

import static dev.sculptory.core.brush.BrushFixture.at;
import static dev.sculptory.core.brush.BrushFixture.dab;
import static dev.sculptory.core.brush.BrushFixture.grid;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.testing.FakeWorld;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Heightmap brush's kernel ({@link HeightmapKernel}): golden heights of a known stamp, the dab's column, layers
 * raised and cut, a drag laying a ridge instead of a pile, turned stamps, the mask judged once per column, the clip box,
 * and mirrored copies stamping the mirrored picture.
 */
class HeightmapKernelTest {
    private final BrushFixture f = new BrushFixture();

    /** A 5 × 5 hill, with one odd pixel in the south-east corner so a turn or a mirror shows. */
    private static final byte[] HILL = bytes(
            0, 0, 0, 0, 0,
            0, 64, 128, 64, 0,
            0, 128, 255, 128, 0,
            0, 64, 128, 64, 0,
            0, 0, 0, 0, 51);

    static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    static BrushSpec spec(int radius, byte[] heights, int heightBlocks, boolean lower, SurfaceMask mask, Box clip,
                          Symmetry symmetry) {
        return new BrushSpec(BrushTool.HEIGHTMAP, radius, 1f, Falloff.CONSTANT, Shape.SQUARE, null, mask, 0, 0, 1L, clip,
                symmetry, null, SculptMode.TERRAIN, null, null,
                new HeightmapSpec(2 * radius + 1, heights, heightBlocks, lower), null);
    }

    static BrushSpec spec(int radius, byte[] heights, int heightBlocks, boolean lower) {
        return spec(radius, heights, heightBlocks, lower, SurfaceMask.ANY, null, Symmetry.NONE);
    }

    @Test
    void liftRoundsToTheNearestBlock() {
        assertEquals(0, HeightmapKernel.lift(0, 128));
        assertEquals(128, HeightmapKernel.lift(255, 128));
        assertEquals(2, HeightmapKernel.lift(64, 8));
        assertEquals(4, HeightmapKernel.lift(128, 8));
        assertEquals(1, HeightmapKernel.lift(1, 128));
        assertEquals(64, HeightmapKernel.lift(127, 128));
    }

    @Test
    void aStampRaisesEachColumnByItsPixel() {
        FakeWorld world = f.flat(60);
        dab(spec(2, HILL, 8, false), new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(grid(
                "0 0 0 0 0",
                "0 2 4 2 0",
                "0 4 8 4 0",
                "0 2 4 2 0",
                "0 0 0 0 2"), f.heights(world, 0, 0, 2, 60));
        assertEquals(grid(
                "0 0 0 0 0 0 0",
                "0 0 0 0 0 0 0",
                "0 0 2 4 2 0 0",
                "0 0 4 8 4 0 0",
                "0 0 2 4 2 0 0",
                "0 0 0 0 0 2 0",
                "0 0 0 0 0 0 0"), f.heights(world, 0, 0, 3, 60), "nothing outside the footprint");
    }

    @Test
    void aStampLowersEachColumnByItsPixel() {
        FakeWorld world = f.flat(60);
        dab(spec(2, HILL, 8, true), new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(grid(
                "0 0 0 0 0",
                "0 -2 -4 -2 0",
                "0 -4 -8 -4 0",
                "0 -2 -4 -2 0",
                "0 0 0 0 -2"), f.heights(world, 0, 0, 2, 60));
        assertEquals(f.grass, world.get(0, 52, 0), "the cut's floor takes the column's top block");
        assertEquals(f.air, world.get(0, 53, 0));
    }

    /** The dab's column is floor(x16 / 16), floor(z16 / 16): pixel (0, 0) lies over (dabX - r, dabZ - r). */
    @Test
    void pixelZeroLiesOverTheDabColumnMinusTheRadius() {
        byte[] corner = new byte[25];
        corner[0] = (byte) 255;
        for (int[] point : new int[][] {{-1, -1}, {15, 15}, {16, -17}, {-16, 0}}) {
            FakeWorld world = f.flat(60);
            Dab d = new Dab(0, point[0], 61 * 16, point[1], Dab.FULL_PRESSURE);
            dab(spec(2, corner, 4, false), new StrokeState(), world, d);
            int x = Math.floorDiv(point[0], 16) - 2, z = Math.floorDiv(point[1], 16) - 2;
            assertEquals(64, f.surface(world, x, z), "dab " + point[0] + "," + point[1]);
            assertEquals(60, f.surface(world, x + 1, z));
            assertEquals(60, f.surface(world, x, z + 1));
        }
    }

    @Test
    void raisingMovesTheColumnsOwnLayersUpAndClearsItsPlants() {
        FakeWorld world = layered();
        byte[] spike = new byte[9];
        spike[4] = (byte) 255;
        dab(spec(1, spike, 5, false), new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(f.grass, world.get(0, 65, 0), "the top block stays on top");
        for (int y = 58; y <= 64; y++) assertEquals(f.dirt, world.get(0, y, 0), "the layer beneath extends at " + y);
        assertEquals(f.stone, world.get(0, 57, 0));
        assertEquals(f.air, world.get(0, 66, 0), "the plant went with the old surface");
        assertEquals(f.shortGrass, world.get(1, 61, 0), "a column the stamp leaves alone keeps its plant");
    }

    @Test
    void loweringCutsDownAndTheNewTopTakesTheOriginalTopBlock() {
        FakeWorld world = layered();
        byte[] spike = new byte[9];
        spike[4] = (byte) 255;
        dab(spec(1, spike, 3, true), new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(f.grass, world.get(0, 57, 0));
        for (int y = 58; y <= 61; y++) assertEquals(f.air, world.get(0, y, 0), "cut at " + y);
        assertEquals(f.stone, world.get(0, 56, 0));
    }

    @Test
    void aCutBelowALakeFillsWithWater() {
        FakeWorld world = f.flat(60);
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 61; y <= 63; y++) world.set(x, y, z, f.water);
            }
        }
        byte[] spike = new byte[9];
        spike[4] = (byte) 255;
        dab(spec(1, spike, 2, true), new StrokeState(), world, at(0, 0, 64, 0));
        assertEquals(f.water, world.get(0, 59, 0));
        assertEquals(f.water, world.get(0, 60, 0));
        assertEquals(f.grass, world.get(0, 58, 0));
    }

    /**
     * Dragging lays a ridge: each column ends at the highest target any dab of the stroke asked of it, never the sum; a
     * dab repeated in place changes nothing; a new stroke starts again from the ground it finds.
     */
    @Test
    void aDragLaysARidgeInsteadOfPilingUp() {
        FakeWorld world = f.flat(60);
        BrushSpec spec = spec(2, HILL, 8, false);
        StrokeState stroke = new StrokeState();
        int[] xs = {0, 1, 2, 3, 2, 0};
        for (int i = 0; i < xs.length; i++) dab(spec, stroke, world, at(i, xs[i], 61 + i % 2 * 6, 0));
        for (int x = -4; x <= 7; x++) {
            for (int z = -3; z <= 3; z++) {
                int want = 0;
                for (int c : new int[] {0, 1, 2, 3}) {
                    int u = x - c + 2, v = z + 2;
                    if (u >= 0 && u < 5 && v >= 0 && v < 5) want = Math.max(want, HeightmapKernel.lift(HILL[v * 5 + u] & 0xFF, 8));
                }
                assertEquals(60 + want, f.surface(world, x, z), "column " + x + "," + z);
            }
        }
        List<BrushFixture.Write> again = dab(spec, stroke, world, at(9, 1, 68, 0));
        assertTrue(again.isEmpty(), "a repeated dab wrote " + again);
        // A second stroke stacks on the first: it finds the ridge as its ground.
        dab(spec, new StrokeState(), world, at(0, 0, 69, 0));
        assertEquals(76, f.surface(world, 0, 0));
    }

    /** A stamp turned 90° clockwise lays the turned heights: the corner pixel moves from south-east to south-west. */
    @Test
    void aTurnedStampLaysTheTurnedHeights() {
        HeightImage image = new HeightImage(5, 5, HILL);
        FakeWorld world = f.flat(60);
        dab(spec(2, image.stamp(5, 90), 8, false), new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(grid(
                "0 0 0 0 0",
                "0 2 4 2 0",
                "0 4 8 4 0",
                "0 2 4 2 0",
                "2 0 0 0 0"), f.heights(world, 0, 0, 2, 60));
    }

    /**
     * The brush's mask is judged once per column, at its surface before the stroke: a height mask doesn't stop a ridge
     * half way once the column has risen past it, and a column it turns down stays down for the whole stroke.
     */
    @Test
    void theMaskIsJudgedOncePerColumnBeforeTheStroke() {
        FakeWorld world = f.terrain((x, z) -> x == 2 ? 61 : 60);
        BrushSpec spec = spec(2, HILL, 8, false, new SurfaceMask.Elevation(60, 60), null, Symmetry.NONE);
        StrokeState stroke = new StrokeState();
        dab(spec, stroke, world, at(0, -1, 61, 0));
        assertEquals(64, f.surface(world, 0, 0), "the first dab lifts column 0 by 4");
        dab(spec, stroke, world, at(1, 0, 61, 0));
        assertEquals(68, f.surface(world, 0, 0), "the column rose past the mask's height, and keeps rising");
        assertEquals(61, f.surface(world, 2, 0), "a column above the mask's height before the stroke stays");
        assertEquals(61, f.surface(world, 2, 1));
    }

    @Test
    void theClipBoxKeepsEveryWriteInside() {
        FakeWorld world = f.flat(60);
        Box clip = new Box(new BlockPos(-10, 50, -10), new BlockPos(0, 64, 10));
        dab(spec(2, HILL, 8, false, SurfaceMask.ANY, clip, Symmetry.NONE), new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(grid(
                "0 0 0 0 0",
                "0 2 4 0 0",
                "0 4 4 0 0",
                "0 2 4 0 0",
                "0 0 0 0 0"), f.heights(world, 0, 0, 2, 60), "the centre column is cut at the clip's top");
    }

    /**
     * Mirror and turn symmetry: each copy stamps the mirrored or turned picture, so the stroke is its exact mirror image,
     * also when the dab sits on a block edge and its mirrored copy's own block lies a column away from the mirrored
     * footprint.
     */
    @Test
    void symmetricCopiesStampTheMirroredOrTurnedPicture() {
        for (int x16 : new int[] {5 * 16 + 8, 5 * 16, 6 * 16 + 15}) {
            FakeWorld world = f.flat(60);
            Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 0, 0);
            dab(spec(2, HILL, 8, false, SurfaceMask.ANY, null, mirror), new StrokeState(), world,
                    new Dab(0, x16, 61 * 16, 8, Dab.FULL_PRESSURE));
            int changed = 0;
            for (int x = 0; x <= 12; x++) {
                for (int z = -4; z <= 4; z++) {
                    assertEquals(f.surface(world, x, z), f.surface(world, -1 - x, z), "mirror of " + x + "," + z);
                    if (f.surface(world, x, z) != 60) changed++;
                }
            }
            assertEquals(10, changed, "the whole stamp, once on each side");
            int bx = Math.floorDiv(x16, 16);
            assertEquals(62, f.surface(world, bx + 2, 2), "the odd pixel on the stroke's side");
            assertEquals(62, f.surface(world, -1 - (bx + 2), 2), "and mirrored on the copy's");
        }
        FakeWorld world = f.flat(60);
        Symmetry turn = new Symmetry(Symmetry.Mode.ROTATE_4, 0, 0);
        dab(spec(2, HILL, 8, false, SurfaceMask.ANY, null, turn), new StrokeState(), world, at(0, 10, 61, 3));
        // A quarter turn clockwise about (0, 0) maps cell (x, z) to (-1 - z, x).
        for (int x = 6; x <= 14; x++) {
            for (int z = -1; z <= 7; z++) {
                int h = f.surface(world, x, z);
                assertEquals(h, f.surface(world, -1 - z, x), "quarter turn of " + x + "," + z);
                assertEquals(h, f.surface(world, -1 - x, -1 - z), "half turn of " + x + "," + z);
                assertEquals(h, f.surface(world, z, -1 - x), "three quarters of " + x + "," + z);
            }
        }
        assertEquals(62, f.surface(world, 12, 5));
    }

    /**
     * A dab on the mirror plane (Mirror X through a block's centre) or on the rotation centre (Rotate 4) is its own image:
     * its copies coincide with it and are left out of the step, yet it stamps every image at once, so the result is
     * symmetric (a lopsided picture is not stamped unmirrored). Mirror Z off the plane mirrors across z.
     */
    @Test
    void aDabOnTheMirrorPlaneOrTheCentreStampsASymmetricResult() {
        FakeWorld world = f.flat(60);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 11, 0);
        Dab onPlane = new Dab(0, 88, 61 * 16, 8, Dab.FULL_PRESSURE);
        assertEquals(1, mirror.copies(onPlane).size(), "the copy coincides with the dab");
        dab(spec(2, HILL, 8, false, SurfaceMask.ANY, null, mirror), new StrokeState(), world, onPlane);
        for (int d = 0; d <= 4; d++) {
            for (int z = -3; z <= 3; z++) {
                assertEquals(f.surface(world, 5 + d, z), f.surface(world, 5 - d, z), "mirror of 5+" + d + "," + z);
            }
        }
        assertEquals(62, f.surface(world, 7, 2), "the odd pixel");
        assertEquals(62, f.surface(world, 3, 2), "and its mirror image, from the same dab");

        FakeWorld turned = f.flat(60);
        Symmetry turn = new Symmetry(Symmetry.Mode.ROTATE_4, 11, 11);
        Dab centre = new Dab(0, 88, 61 * 16, 88, Dab.FULL_PRESSURE);
        assertEquals(1, turn.copies(centre).size());
        dab(spec(2, HILL, 8, false, SurfaceMask.ANY, null, turn), new StrokeState(), turned, centre);
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) {
                assertEquals(f.surface(turned, x, z), f.surface(turned, 10 - z, x), "quarter turn of " + x + "," + z);
            }
        }
        assertEquals(62, f.surface(turned, 7, 7));
        assertEquals(62, f.surface(turned, 3, 7));
        assertEquals(62, f.surface(turned, 3, 3));
        assertEquals(62, f.surface(turned, 7, 3));

        FakeWorld across = f.flat(60);
        dab(spec(2, HILL, 8, false, SurfaceMask.ANY, null, new Symmetry(Symmetry.Mode.MIRROR_Z, 0, 0)), new StrokeState(),
                across, at(0, 0, 61, 6));
        for (int x = -3; x <= 3; x++) {
            for (int z = 0; z <= 10; z++) {
                assertEquals(f.surface(across, x, z), f.surface(across, x, -1 - z), "mirror of " + x + "," + z);
            }
        }
        assertEquals(62, f.surface(across, 2, 8));
        assertEquals(62, f.surface(across, 2, -9));
    }

    @Test
    void stampsNearTheWorldsBottomAndTopStopThere() {
        FakeWorld world = new FakeWorld(f.states);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = -64; y <= -62; y++) world.set(x, y, z, y == -62 ? f.grass : f.stone);
                for (int y = 300; y <= 316; y++) world.set(x + 20, y, z, y == 316 ? f.grass : f.stone);
            }
        }
        byte[] full = new byte[9];
        java.util.Arrays.fill(full, (byte) 255);
        dab(spec(1, full, 10, true), new StrokeState(), world, at(0, 0, -61, 0));
        assertEquals(f.grass, world.get(0, -64, 0), "cut down to the bottom of the world, which takes the top block");
        assertEquals(f.air, world.get(0, -63, 0));
        assertEquals(f.air, world.get(0, -62, 0));
        assertEquals(f.grass, world.get(3, -62, 0), "outside the stamp nothing changes");
        dab(spec(1, full, 10, false), new StrokeState(), world, at(0, 20, 317, 0));
        assertEquals(f.grass, world.get(20, 319, 0), "raised to the top of the world");
        assertEquals(f.stone, world.get(20, 318, 0));
    }

    @Test
    void structuresAreNeverOverwritten() {
        FakeWorld world = f.flat(60);
        world.set(0, 63, 0, f.stairs);
        world.set(1, 58, 0, f.stairs);
        byte[] flat = new byte[9];
        java.util.Arrays.fill(flat, (byte) 255);
        dab(spec(1, flat, 6, false), new StrokeState(), world, at(0, 0, 61, 0));
        assertEquals(60, f.surface(world, 0, 0), "a column under a structure has no surface from above: left alone");
        assertEquals(f.stairs, world.get(0, 63, 0));
        assertEquals(66, f.surface(world, -1, 0));
        FakeWorld cut = f.flat(60);
        cut.set(1, 58, 0, f.stairs);
        dab(spec(1, flat, 6, true), new StrokeState(), cut, at(0, 0, 61, 0));
        assertEquals(f.stairs, cut.get(1, 58, 0), "the cut stops on the stairs");
        assertEquals(f.air, cut.get(1, 59, 0));
        assertEquals(54, f.surface(cut, 0, 0));
    }

    @Test
    void anotherToolsSpecIsRefused() {
        BrushSpec raise = BrushFixture.spec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> BrushKernels
                .forTool(BrushTool.HEIGHTMAP).apply(raise, at(0, 0, 61, 0), new StrokeState(), f.flat(60), (x, y, z, h) -> {}));
    }

    /** Stone to 57, dirt 58-59, grass at 60, with short grass on (0, 0) and (1, 0). */
    private FakeWorld layered() {
        FakeWorld world = new FakeWorld(f.states);
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) {
                for (int y = BrushFixture.FLOOR; y <= 60; y++) world.set(x, y, z, y < 58 ? f.stone : y < 60 ? f.dirt : f.grass);
            }
        }
        world.set(0, 61, 0, f.shortGrass);
        world.set(1, 61, 0, f.shortGrass);
        return world;
    }
}
