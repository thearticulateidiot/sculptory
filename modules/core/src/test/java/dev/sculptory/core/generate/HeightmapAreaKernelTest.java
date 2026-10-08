package dev.sculptory.core.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.HeightImage;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;

/**
 * The Heightmap import ({@link HeightmapAreaKernel}): golden surfaces between Min Y and Max Y, the column's own layers
 * raised and cut, everything above the new surface cleared up to the top of the range, invert, a range wider than the
 * selection, unloaded columns, and only changed cells written.
 */
class HeightmapAreaKernelTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int air = states.air();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int grass = states.state("minecraft:grass_block");
    private final int water = states.state("minecraft:water");
    private final int plant = states.state("minecraft:short_grass");

    /** 0, 64, 128 and 255 along each row, the rows turned by one each. */
    private static final byte[] IMAGE = bytes(
            0, 64, 128, 255,
            64, 128, 255, 0,
            128, 255, 0, 64,
            255, 0, 64, 128);

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) out[i] = (byte) values[i];
        return out;
    }

    /** Stone to 57, dirt 58-59, grass at 60, short grass on even columns, a block at y 67 above (0, 0). */
    private FakeWorld world() {
        FakeWorld world = new FakeWorld(states);
        for (int x = -2; x <= 6; x++) {
            for (int z = -2; z <= 6; z++) {
                for (int y = 40; y <= 60; y++) world.set(x, y, z, y < 58 ? stone : y < 60 ? dirt : grass);
                if ((x + z) % 2 == 0) world.set(x, 61, z, plant);
            }
        }
        world.set(0, 67, 0, stone);
        return world;
    }

    private static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    private HeightmapAreaKernel.Result run(FakeWorld world, HeightmapAreaKernel.Spec spec) {
        int[] range = HeightmapAreaKernel.range(spec, world.bottomY(), world.topYExclusive());
        HeightmapAreaKernel.Result result = HeightmapAreaKernel.generate(spec,
                (x, z) -> HeightmapAreaKernel.capture(world, x, z, range[0], range[1], spec.minY()), states,
                world.bottomY(), world.topYExclusive(), 1_000_000);
        result.source().forEach(world::set);
        return result;
    }

    /** The topmost non-air, non-plant y of each column, minus 60. */
    private String surfaces(FakeWorld world, Box box) {
        StringJoiner lines = new StringJoiner("\n");
        for (int z = box.min().z(); z <= box.max().z(); z++) {
            StringJoiner line = new StringJoiner(" ");
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                int y = 66;
                while (y > 40 && (world.get(x, y, z) == air || world.get(x, y, z) == plant)) y--;
                line.add(Integer.toString(y - 60));
            }
            lines.add(line.toString());
        }
        return lines.toString();
    }

    @Test
    void eachColumnsSurfaceLiesBetweenMinAndMaxY() {
        FakeWorld world = world();
        Box box = box(0, 58, 0, 3, 66, 3);
        HeightmapAreaKernel.Result result = run(world, new HeightmapAreaKernel.Spec(box, 58, 66, IMAGE));
        assertTrue(result.unloaded().isEmpty());
        assertEquals(String.join("\n",
                "-2 0 2 6",
                "0 2 6 -2",
                "2 6 -2 0",
                "6 -2 0 2"), surfaces(world, box));
        // Raised: the old grass and everything up to the new top become dirt, grass on top.
        assertEquals(grass, world.get(3, 66, 0));
        for (int y = 58; y <= 65; y++) assertEquals(dirt, world.get(3, y, 0), "raised layer at " + y);
        // Cut: everything above goes, the new top is grass on what was dirt.
        assertEquals(grass, world.get(0, 58, 0));
        assertEquals(air, world.get(0, 59, 0));
        assertEquals(air, world.get(0, 60, 0));
        assertEquals(air, world.get(0, 61, 0), "the plant above went too");
        // Unchanged where the image asks for the surface it has; above the range nothing is touched.
        assertEquals(grass, world.get(1, 60, 0));
        assertEquals(stone, world.get(0, 67, 0));
        assertEquals(stone, world.get(0, 57, 0));
    }

    @Test
    void anInvertedImageSwapsHighAndLow() {
        FakeWorld world = world();
        Box box = box(0, 58, 0, 3, 66, 3);
        byte[] inverted = new HeightImage(4, 4, IMAGE).stretch(4, 4, 0, true);
        run(world, new HeightmapAreaKernel.Spec(box, 58, 66, inverted));
        assertEquals(String.join("\n",
                "6 4 2 -2",
                "4 2 -2 6",
                "2 -2 6 4",
                "-2 6 4 2"), surfaces(world, box));
    }

    @Test
    void minAndMaxYMayReachBeyondTheSelection() {
        FakeWorld world = world();
        world.set(0, 67, 0, air);
        Box box = box(0, 59, 0, 1, 61, 0);
        HeightmapAreaKernel.Spec spec = new HeightmapAreaKernel.Spec(box, 50, 70, bytes(0, 255));
        assertEquals(50, spec.target(0, 0));
        assertEquals(70, spec.target(1, 0));
        int[] range = HeightmapAreaKernel.range(spec, -64, 320);
        assertEquals(50, range[0]);
        assertEquals(70, range[1]);
        run(world, spec);
        assertEquals(grass, world.get(0, 50, 0));
        assertEquals(air, world.get(0, 51, 0));
        assertEquals(grass, world.get(1, 70, 0));
        assertEquals(dirt, world.get(1, 69, 0));
        assertEquals(dirt, world.get(1, 60, 0));
    }

    @Test
    void waterAboveTheNewSurfaceIsCleared() {
        FakeWorld world = world();
        for (int y = 61; y <= 63; y++) world.set(2, y, 2, water);
        Box box = box(2, 58, 2, 2, 64, 2);
        run(world, new HeightmapAreaKernel.Spec(box, 58, 64, bytes(0)));
        for (int y = 59; y <= 64; y++) assertEquals(air, world.get(2, y, 2));
        assertEquals(grass, world.get(2, 58, 2));
    }

    @Test
    void anImportOfTheSurfaceAsItIsWritesNothing() {
        Box box = box(0, 55, 0, 3, 65, 3);
        byte[] level = new byte[16];
        java.util.Arrays.fill(level, (byte) 255);
        // 55 + 255 × (60 - 55) / 255 = 60 everywhere; the plants above go.
        HeightmapAreaKernel.Result result = run(world(), new HeightmapAreaKernel.Spec(box, 55, 60, level));
        assertEquals(8, result.source().cells(), "only the eight plants");
    }

    @Test
    void unloadedColumnsAreListedAndLeftAlone() {
        FakeWorld world = world();
        world.setLoaded(1, 0, false);
        Box box = box(14, 58, 0, 17, 66, 0);
        for (int x = 14; x <= 17; x++) {
            if (x < 16) for (int y = 40; y <= 60; y++) world.set(x, y, 0, y < 60 ? stone : grass);
        }
        HeightmapAreaKernel.Result result = run(world, new HeightmapAreaKernel.Spec(box, 58, 66, bytes(255, 255, 255, 255)));
        assertEquals(2, result.unloaded().size());
        assertEquals(16, result.unloaded().get(0)[0]);
        assertEquals(grass, world.get(14, 66, 0));
        assertEquals(grass, world.get(15, 66, 0));
    }

    /** Chests (any block entity) are left as they are, above the new surface or below it, and counted. */
    @Test
    void blockEntitiesAreLeftAsTheyAreAndCounted() {
        FakeWorld world = world();
        int chest = states.state("minecraft:chest");
        world.set(1, 62, 1, chest);
        world.set(2, 59, 2, chest);
        world.set(3, 62, 3, chest);
        Box box = box(0, 58, 0, 3, 66, 3);
        HeightmapAreaKernel.Result result = run(world, new HeightmapAreaKernel.Spec(box, 58, 66, IMAGE));
        assertEquals(chest, world.get(1, 62, 1), "where a column rising to 62 puts its top block: kept");
        assertEquals(chest, world.get(2, 59, 2), "under a surface cut to 58: kept, not cleared");
        assertEquals(chest, world.get(3, 62, 3), "where the new top block goes: kept");
        assertEquals(3, result.keptBlockEntities());
        assertEquals(states.air(), world.get(2, 60, 2), "the rest of the column is cut as asked");
    }

    /** A capture keeps the column from the lower of its surface and Min Y up; one made for a Min Y serves higher ones. */
    @Test
    void aCaptureKeepsOnlyTheCellsAnImportCanTouch() {
        FakeWorld world = world();
        HeightmapAreaKernel.Column column = HeightmapAreaKernel.capture(world, 2, 0, 40, 70, 50);
        assertEquals(60, column.surface());
        assertEquals(50, column.bottom(), "from Min Y, below the surface");
        assertEquals(12, column.cells().length, "50 to 61, the plant on top included");
        HeightmapAreaKernel.Column high = HeightmapAreaKernel.capture(world, 2, 0, 40, 70, 64);
        assertEquals(60, high.bottom(), "from the surface, below Min Y");
        assertEquals(grass, high.top());
        assertEquals(dirt, high.fill());
        Box box = box(0, 40, 0, 3, 70, 0);
        byte[] picture = bytes(0, 85, 170, 255);
        HeightmapAreaKernel.Spec spec = new HeightmapAreaKernel.Spec(box, 64, 70, picture);
        HeightmapAreaKernel.Result fromLower = HeightmapAreaKernel.generate(spec,
                (x, z) -> HeightmapAreaKernel.capture(world, x, z, 40, 70, 50), states, -64, 320, 1000);
        HeightmapAreaKernel.Result exact = HeightmapAreaKernel.generate(spec,
                (x, z) -> HeightmapAreaKernel.capture(world, x, z, 40, 70, 64), states, -64, 320, 1000);
        assertEquals(exact.source().cells(), fromLower.source().cells());
        exact.source().forEach((x, y, z, state) -> assertEquals(state, fromLower.source().get(x, y, z)));
    }

    @Test
    void theCapStopsAnImportTooLarge() {
        FakeWorld world = world();
        Box box = box(0, 58, 0, 3, 66, 3);
        HeightmapAreaKernel.Spec spec = new HeightmapAreaKernel.Spec(box, 58, 66, IMAGE);
        assertThrows(GeneratedTooLargeException.class, () -> HeightmapAreaKernel.generate(spec,
                (x, z) -> HeightmapAreaKernel.capture(world, x, z, 58, 66, 58), states, -64, 320, 10));
        assertThrows(IllegalArgumentException.class, () -> new HeightmapAreaKernel.Spec(box, 60, 59, IMAGE));
        assertThrows(IllegalArgumentException.class, () -> new HeightmapAreaKernel.Spec(box, 58, 66, new byte[3]));
    }
}
