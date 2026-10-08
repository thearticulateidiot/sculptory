package dev.sculptory.core.biome;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Clipboard biomes (protocol 6): the quart grid and its phase, the content hash with and without biomes, capturing the
 * quarts a copy holds, mapping through exact and free orientations with the shift rounded to the nearest quart, and
 * the quarts a fill touches.
 */
class BiomeGridTest {
    private static final String PLAINS = "minecraft:plains";
    private static final String DESERT = "minecraft:desert";
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");

    /** The world's biomes: desert where quart x is negative, else plains. */
    private static String world(int qx, int qy, int qz) {
        return qx < 0 ? DESERT : PLAINS;
    }

    private Clipboard solid(BlockPos size) {
        Clipboard.Builder builder = Clipboard.builder(states, size);
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) builder.set(x, y, z, stone);
            }
        }
        return builder.build();
    }

    @Test
    void theGridKeepsTheBoxPhase() {
        // A box from x = 3 spans quarts 0 and 1 for x 3..7 (local 0 is in quart 0, local 1..4 in quart 1).
        BiomeGrid.Builder builder = BiomeGrid.builder(new BlockPos(3, 0, 0), new BlockPos(5, 1, 1));
        assertEquals(new BlockPos(2, 1, 1), builder.quartSize());
        builder.set(0, 0, 0, DESERT).set(1, 0, 0, PLAINS);
        BiomeGrid grid = builder.build();
        assertEquals(DESERT, grid.biomeAt(0, 0, 0));
        assertEquals(PLAINS, grid.biomeAt(1, 0, 0));
        assertEquals(PLAINS, grid.biomeAt(4, 0, 0));
        assertNull(grid.biomeAt(5, 0, 0), "outside");
        assertTrue(grid.fits(new BlockPos(5, 1, 1)));
        assertFalse(grid.fits(new BlockPos(6, 1, 1)));
        assertNull(BiomeGrid.builder(BlockPos.ORIGIN, new BlockPos(1, 1, 1)).build(), "no biome: no grid");
        assertThrows(IllegalArgumentException.class, () -> BiomeGrid.builder(new BlockPos(4, 0, 0), new BlockPos(1, 1, 1)));
    }

    @Test
    void aClipboardHashesItsBiomesOnlyWhenItHasThem() {
        Clipboard plain = solid(new BlockPos(8, 4, 8));
        assertEquals(plain.contentHash(), plain.withBiomes(null).contentHash(), "no biomes: the hash as before");
        Clipboard withBiomes = plain.withBiomesFrom(new BlockPos(-4, 64, 0), BiomeGridTest::world);
        assertNotEquals(plain.contentHash(), withBiomes.contentHash());
        assertEquals(withBiomes.contentHash(), plain.withBiomesFrom(new BlockPos(-4, 64, 0), BiomeGridTest::world)
                .contentHash(), "equal content, equal hash");
        assertNotEquals(withBiomes.contentHash(), plain.withBiomesFrom(new BlockPos(-3, 64, 0), BiomeGridTest::world)
                .contentHash(), "another phase is other content");
        assertEquals(withBiomes.biomes(), withBiomes.withAnchor(new BlockPos(1, 2, 3)).biomes(), "kept with the anchor");
        assertEquals(DESERT, withBiomes.biomes().biomeAt(0, 0, 0));
        assertEquals(PLAINS, withBiomes.biomes().biomeAt(4, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> solid(new BlockPos(9, 4, 8)).withBiomes(withBiomes.biomes()),
                "a grid that does not fit");
    }

    @Test
    void aCopyCapturesOnlyTheQuartsItsCellsAreIn() {
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(8, 1, 1));
        builder.set(0, 0, 0, stone); // only the first quart holds a cell
        Clipboard sparse = builder.build().withBiomesFrom(BlockPos.ORIGIN, BiomeGridTest::world);
        assertEquals(1, sparse.biomes().presentCount());
        assertNull(sparse.biomes().biomeAt(5, 0, 0));
        Clipboard unread = solid(new BlockPos(4, 4, 4)).withBiomesFrom(BlockPos.ORIGIN, (qx, qy, qz) -> null);
        assertNull(unread.biomes(), "nothing read, no biomes");

        Region cells = new Region.Cells(CellSet.builder().add(1, 70, 1).add(9, 70, 1).build());
        BiomeGrid captured = BiomeMapping.capture(cells, cells.bounds(), -64, 320, BiomeGridTest::world);
        assertEquals(2, captured.presentCount(), "the two quarts holding a cell");
    }

    /**
     * A plain shift: every quart the pasted box touches takes the source biome at its centre moved into the box, so an
     * aligned paste copies the grid exactly and a shifted one moves it by the shift rounded to the nearest quart, the
     * quarts the box only partly covers taking the nearest source biome.
     */
    @Test
    void aShiftedPasteMovesTheGridByTheShiftRoundedToTheNearestQuart() {
        // An 8-wide source aligned to quarts: desert in its first quart column, plains in its second.
        Clipboard source = solid(new BlockPos(8, 4, 4)).withBiomesFrom(new BlockPos(-4, 0, 0), BiomeGridTest::world);
        Map<Integer, List<String>> expected = Map.of(
                0, List.of(DESERT, PLAINS),
                1, List.of(DESERT, PLAINS, PLAINS),
                2, List.of(DESERT, PLAINS, PLAINS),
                3, List.of(DESERT, DESERT, PLAINS),
                4, List.of(DESERT, PLAINS));
        for (Map.Entry<Integer, List<String>> shift : expected.entrySet()) {
            int min = 100 + shift.getKey();
            Map<Long, String> out = paste(source, Orientation.IDENTITY, new BlockPos(min, 0, 0));
            List<String> row = new java.util.ArrayList<>();
            for (int qx = min >> 2; qx <= (min + 7) >> 2; qx++) row.add(out.get(key(qx, 0, 0)));
            assertEquals(shift.getValue(), row, "shift " + shift.getKey());
            assertEquals(row.size(), out.size());
        }
        // A paste one block tall still carries its biomes.
        Clipboard thin = solid(new BlockPos(8, 1, 4)).withBiomesFrom(new BlockPos(-4, 70, 0), BiomeGridTest::world);
        assertEquals(2, paste(thin, Orientation.IDENTITY, new BlockPos(100, 64, 0)).size());
    }

    @Test
    void quarterTurnsAndFlipsMoveTheGridExactly() {
        Clipboard source = solid(new BlockPos(8, 4, 8)).withBiomesFrom(new BlockPos(-4, 0, 0), BiomeGridTest::world);
        // Half a turn: desert goes to the east half.
        Map<Long, String> turned = paste(source, Orientation.of(new Transform(2, Mirror.NONE)),
                BlockPos.ORIGIN);
        assertEquals(4, turned.size());
        assertEquals(PLAINS, turned.get(key(0, 0, 0)));
        assertEquals(DESERT, turned.get(key(1, 0, 0)));
        // A quarter turn clockwise (east to south): desert goes to the north half, plains to the south.
        Map<Long, String> quarter = paste(source, Orientation.of(new Transform(1, Mirror.NONE)),
                BlockPos.ORIGIN);
        assertEquals(DESERT, quarter.get(key(0, 0, 0)));
        assertEquals(DESERT, quarter.get(key(1, 0, 0)));
        assertEquals(PLAINS, quarter.get(key(0, 0, 1)));
        assertEquals(PLAINS, quarter.get(key(1, 0, 1)));
    }

    /** Free orientations resample: 90° of pitch exactly, 30° of yaw with no hole inside the turned box. */
    @Test
    void freeOrientationsResampleTheGrid() {
        Clipboard source = solid(new BlockPos(16, 4, 16)).withBiomesFrom(new BlockPos(-8, 0, 0), BiomeGridTest::world);
        Map<Long, String> pitched = paste(source, Orientation.free(Transform.IDENTITY, 0, 9_000, 0), BlockPos.ORIGIN);
        assertFalse(pitched.isEmpty());
        assertTrue(pitched.containsValue(DESERT) && pitched.containsValue(PLAINS));
        Orientation thirty = Orientation.free(Transform.IDENTITY, 3_000, 0, 0);
        Map<Long, String> turned = paste(source, thirty, BlockPos.ORIGIN);
        BlockPos result = thirty.bounds(16, 4, 16).size();
        // Every quart whose centre maps back inside the source takes a biome: the middle of the result is covered.
        int mid = (result.x() / 2) >> 2;
        assertTrue(turned.containsKey(key(mid, 0, (result.z() / 2) >> 2)), turned.toString());
        long expected = 0;
        for (int qx = 0; qx * 4 < result.x(); qx++) {
            for (int qz = 0; qz * 4 < result.z(); qz++) {
                int cx = Math.min(qx * 4 + 2, result.x() - 1), cz = Math.min(qz * 4 + 2, result.z() - 1);
                if (thirty.inverseCell(cx, 2, cz, 16, 4, 16) != null) expected++;
            }
        }
        assertEquals(expected, turned.size());
        assertTrue(expected >= 12, expected + " quarts");
    }

    private static Map<Long, String> paste(Clipboard source, Orientation orientation, BlockPos resultMin) {
        Map<Long, String> out = new HashMap<>();
        BiomeMapping.map(source.biomes(), source.size(), orientation, resultMin, -64, 320,
                (qx, qy, qz, id) -> assertEquals(null, out.put(key(qx, qy, qz), id), "each quart once"));
        return out;
    }

    private static long key(int qx, int qy, int qz) {
        return dev.sculptory.core.history.BiomeHistory.key(qx, qy, qz);
    }

    @Test
    void aFillTouchesEveryQuartItsRegionHasACellIn() {
        Region box = new Region.Cuboid(new Box(new BlockPos(3, 63, 0), new BlockPos(4, 64, 0)));
        QuartSelection threeD = QuartSelection.touched(box, false, -64, 320);
        // x 3..4 spans quarts 0 and 1, y 63..64 quarts 15 and 16, z 0 quart 0.
        assertEquals(4, threeD.count());
        assertTrue(threeD.contains(0, 15, 0) && threeD.contains(1, 16, 0));
        assertEquals(4, QuartSelection.estimate(box, false, -64, 320));
        QuartSelection columns = QuartSelection.touched(box, true, -64, 320);
        assertEquals(2 * 96, columns.count(), "two quart columns, the whole height");
        assertTrue(columns.contains(1, -16, 0) && columns.contains(1, 79, 0));
        assertFalse(columns.contains(1, 80, 0));
        assertEquals(new Box(new BlockPos(0, -64, 0), new BlockPos(7, 319, 3)), columns.bounds());

        Region cell = new Region.Cells(CellSet.builder().add(-1, -64, -1).build());
        QuartSelection one = QuartSelection.touched(cell, false, -64, 320);
        assertEquals(1, one.count());
        assertTrue(one.contains(-1, -16, -1));
        Region ball = new Region.Shape(new Box(new BlockPos(0, 0, 0), new BlockPos(31, 31, 31)), ShapeKind.ELLIPSOID,
                Facing.UP);
        QuartSelection round = QuartSelection.touched(ball, false, -64, 320);
        assertTrue(round.count() < 512 && round.count() > 256, round.count() + " quarts: the ball misses the corners");
        assertTrue(QuartSelection.touched(new Region.Cuboid(new Box(new BlockPos(0, 400, 0), new BlockPos(1, 401, 1))),
                false, -64, 320).isEmpty(), "above the build height");
        assertThrows(IllegalArgumentException.class, () -> QuartSelection.touched(box, true, Integer.MIN_VALUE,
                Integer.MAX_VALUE), "needs a world's height");
    }
}
