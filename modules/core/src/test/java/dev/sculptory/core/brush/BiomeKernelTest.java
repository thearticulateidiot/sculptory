package dev.sculptory.core.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.history.BiomeHistory;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.MaskedKernel;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The Biome brush's kernel (protocol 6): quarts whose geometric centre is in the footprint plus the one under the dab,
 * whole columns or 3D, the brush mask at the column's surface, the clip box and unloaded chunks; never a block.
 */
class BiomeKernelTest {
    private static final String CHERRY = "minecraft:cherry_grove";
    private final BrushFixture f = new BrushFixture();

    private static BrushSpec spec(int radius, Shape shape, boolean columns, SurfaceMask mask, Box clip) {
        return new BrushSpec(BrushTool.BIOME, radius, 1f, Falloff.CONSTANT, shape, null, mask, 0, 0, 1L, clip,
                Symmetry.NONE, null, SculptMode.TERRAIN, null, null, null, new BiomeSpec(CHERRY, columns));
    }

    /** The quarts one step writes, each once (as keys), failing on any block write. */
    private static Set<Long> paint(BrushSpec spec, FakeWorld world, BrushKernel kernel, Dab... dabs) {
        Set<Long> quarts = new LinkedHashSet<>();
        kernel.applyStep(spec, List.of(dabs), new StrokeState(), world, new CellSink() {
            @Override
            public void set(int x, int y, int z, int handle) {
                throw new AssertionError("the biome brush wrote block " + x + "," + y + "," + z);
            }

            @Override
            public void biome(int qx, int qy, int qz, String biomeId) {
                assertEquals(CHERRY, biomeId);
                assertTrue(quarts.add(BiomeHistory.key(qx, qy, qz)), "a quart once per step");
            }
        });
        return quarts;
    }

    private static Set<Long> paint(BrushSpec spec, FakeWorld world, Dab... dabs) {
        return paint(spec, world, BrushKernels.forTool(BrushTool.BIOME), dabs);
    }

    /** A full-pressure dab at the point (x, y, z) (a corner between blocks). */
    private static Dab point(int x, int y, int z) {
        return new Dab(0, x * 16, y * 16, z * 16, Dab.FULL_PRESSURE);
    }

    private static List<Long> columnsOf(Set<Long> quarts) {
        List<Long> columns = new ArrayList<>();
        for (long key : quarts) {
            long column = BiomeHistory.key(BiomeHistory.keyX(key), 0, BiomeHistory.keyZ(key));
            if (!columns.contains(column)) columns.add(column);
        }
        return columns;
    }

    @Test
    void columnsPaintWholeQuartColumnsWhoseCentreIsInTheFootprint() {
        FakeWorld world = f.flat(60);
        // A dab at the point (2, 61, 2): (2, 2) is quart column (0, 0)'s centre.
        Set<Long> one = paint(spec(1, Shape.CIRCLE, true, SurfaceMask.ANY, null), world, point(2, 61, 2));
        assertEquals(1, columnsOf(one).size(), "radius 1 reaches no other quart centre");
        assertEquals(96, one.size(), "the whole height, y -64 to 319");
        assertTrue(one.contains(BiomeHistory.key(0, -16, 0)) && one.contains(BiomeHistory.key(0, 79, 0)));
        // Radius 4 around the same point reaches the four neighbouring quart centres 4 away, not the diagonals (5.7).
        Set<Long> cross = paint(spec(4, Shape.CIRCLE, true, SurfaceMask.ANY, null), world, point(2, 61, 2));
        assertEquals(5, columnsOf(cross).size());
        assertEquals(9, columnsOf(paint(spec(4, Shape.SQUARE, true, SurfaceMask.ANY, null), world,
                point(2, 61, 2))).size(), "a square reaches the diagonals");
    }

    @Test
    void theQuartUnderTheDabIsAlwaysPainted() {
        FakeWorld world = f.flat(60);
        // Block (3, 61, 3)'s centre is 1.5 blocks from the nearest quart centre on each axis: radius 1 reaches none, and
        // the dab's own quart is painted all the same.
        Set<Long> columns = paint(spec(1, Shape.CIRCLE, true, SurfaceMask.ANY, null), world, BrushFixture.at(0, 3, 61, 3));
        assertEquals(List.of(BiomeHistory.key(0, 0, 0)), columnsOf(columns));
        Set<Long> cell = paint(spec(1, Shape.CIRCLE, false, SurfaceMask.ANY, null), world, BrushFixture.at(0, 3, 61, 3));
        assertEquals(Set.of(BiomeHistory.key(0, 15, 0)), cell, "3D: just the dab's quart");
    }

    @Test
    void threeDPaintsOnlyTheQuartsInTheBall() {
        FakeWorld world = f.flat(60);
        Dab dab = new Dab(0, 2 * 16, 62 * 16, 2 * 16, Dab.FULL_PRESSURE); // the point (2, 62, 2): quart (0, 15, 0)'s centre
        Set<Long> ball = paint(spec(4, Shape.CIRCLE, false, SurfaceMask.ANY, null), world, dab);
        assertEquals(7, ball.size(), "the centre and its six face neighbours");
        assertTrue(ball.contains(BiomeHistory.key(0, 16, 0)) && ball.contains(BiomeHistory.key(0, 14, 0)));
        assertEquals(27, paint(spec(4, Shape.SQUARE, false, SurfaceMask.ANY, null), world, dab).size(), "a cube");
    }

    @Test
    void theBrushMaskJudgesAColumnAtItsSurfaceAndTheClipKeepsQuartCentres() {
        FakeWorld world = f.flat(60);
        world.set(6, 60, 2, f.dirt); // quart column (1, 0)'s representative column (6, 2) is dirt on top
        SurfaceMask dirtOnly = new SurfaceMask.SurfaceBlocks(new CellMask.Blocks(List.of(new NamespacedId("minecraft:dirt"))));
        Set<Long> masked = paint(spec(4, Shape.SQUARE, true, dirtOnly, null), world, point(2, 61, 2));
        assertEquals(List.of(BiomeHistory.key(1, 0, 0)), columnsOf(masked));

        Box clip = new Box(new BlockPos(-100, 60, -100), new BlockPos(100, 69, 100));
        Set<Long> clipped = paint(spec(1, Shape.CIRCLE, true, SurfaceMask.ANY, clip), world, point(2, 61, 2));
        // Quart centres y 62 and 66 lie in 60..69 (70 does not).
        assertEquals(Set.of(BiomeHistory.key(0, 15, 0), BiomeHistory.key(0, 16, 0)), clipped);
    }

    @Test
    void theGlobalMaskJudgesTheSurfaceToo() {
        FakeWorld world = f.flat(60);
        world.set(6, 60, 2, f.dirt);
        BoundMask dirt = new BoundMask() {
            @Override
            public boolean test(int x, int y, int z, int before, dev.sculptory.core.world.WorldReader view) {
                return before == f.dirt;
            }

            @Override
            public int reach() {
                return 0;
            }
        };
        BrushKernel kernel = MaskedKernel.wrap(BrushKernels.forTool(BrushTool.BIOME), dirt);
        Set<Long> masked = paint(spec(4, Shape.SQUARE, true, SurfaceMask.ANY, null), world, kernel,
                point(2, 61, 2));
        assertEquals(List.of(BiomeHistory.key(1, 0, 0)), columnsOf(masked));
    }

    @Test
    void unloadedChunksAreLeftAloneAndOtherBrushesAreRefused() {
        FakeWorld world = f.flat(60);
        world.setLoaded(-1, 0, false);
        world.setLoaded(-1, -1, false);
        Set<Long> painted = paint(spec(8, Shape.SQUARE, true, SurfaceMask.ANY, null), world, BrushFixture.at(0, 0, 61, 2));
        for (long column : columnsOf(painted)) assertTrue(BiomeHistory.keyX(column) >= 0, "nothing west of x 0");
        assertFalse(painted.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> BrushKernels.forTool(BrushTool.BIOME).applyStep(
                BrushFixture.spec(BrushTool.RAISE, 2, 1f, Falloff.CONSTANT, Shape.CIRCLE),
                List.of(BrushFixture.at(0, 0, 61, 0)), new StrokeState(), world, (x, y, z, h) -> {}));
    }
}
