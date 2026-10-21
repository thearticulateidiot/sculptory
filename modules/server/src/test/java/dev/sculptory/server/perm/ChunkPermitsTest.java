package dev.sculptory.server.perm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.server.engine.ChunkPermit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The per-chunk corner rule, in columns and in cells. Spawn protection cannot be enabled in GameTest. */
class ChunkPermitsTest {
    private static final Box CHUNK_0 = new Box(new BlockPos(0, 0, 0), new BlockPos(15, 15, 15));

    @Test
    void allCornersAllowedOrDeniedUseFourChecks() {
        AtomicInteger calls = new AtomicInteger();
        assertSame(ChunkPermit.ALLOW, ChunkPermits.forChunk(0, 0, CHUNK_0, (x, z) -> {
            calls.incrementAndGet();
            return true;
        }));
        assertEquals(4, calls.get());
        assertSame(ChunkPermit.DENY, ChunkPermits.forChunk(0, 0, CHUNK_0, (x, z) -> false));
    }

    @Test
    void mixedCornersFallBackToEveryColumn() {
        ChunkPermit permit = ChunkPermits.forChunk(0, 0, CHUNK_0, (x, z) -> x < 5);
        assertInstanceOf(ChunkPermit.Columns.class, permit);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // A column answer holds at every height (2D producers are unchanged).
                for (int y = -64; y < 320; y += 7) assertEquals(x < 5, permit.allows(x, y, z), x + "," + y + "," + z);
            }
        }
    }

    @Test
    void cornersAreThoseOfTheBoxPartInsideTheChunk() {
        // Box covers x 20..40, z -5..3; in chunk (1, 0) the part is x 20..31, z 0..3.
        Box box = new Box(new BlockPos(20, 0, -5), new BlockPos(40, 10, 3));
        StringBuilder seen = new StringBuilder();
        ChunkPermits.forChunk(1, 0, box, (x, z) -> {
            seen.append(x).append(',').append(z).append(' ');
            return true;
        });
        assertEquals("20,0 31,0 20,3 31,3 ", seen.toString());
        assertSame(ChunkPermit.ALLOW, ChunkPermits.forChunk(5, 5, box, (x, z) -> false), "untouched chunk");
    }

    @Test
    void negativeCoordinatesUseLowBits() {
        Box box = new Box(new BlockPos(-16, 0, -16), new BlockPos(-1, 0, -1));
        ChunkPermit permit = ChunkPermits.forChunk(-1, -1, box, (x, z) -> z >= -8);
        for (int x = -16; x < 0; x++) {
            for (int z = -16; z < 0; z++) assertEquals(z >= -8, permit.allows(x, 0, z));
        }
    }

    @Test
    void spawnProtectionPerChunkSkips() {
        // Vanilla spawn protection: |dx| <= r and |dz| <= r around spawn are protected. With r larger than a
        // chunk, every chunk touching the square has a corner inside it, so the rule is exact per column.
        int spawnX = 37, spawnZ = -11, radius = 20;
        ChunkPermits.ColumnTest outsideSpawn = (x, z) -> Math.max(Math.abs(x - spawnX), Math.abs(z - spawnZ)) > radius;
        Box edit = new Box(new BlockPos(-40, 0, -60), new BlockPos(100, 64, 40));
        long denied = 0;
        for (int cx = edit.min().x() >> 4; cx <= edit.max().x() >> 4; cx++) {
            for (int cz = edit.min().z() >> 4; cz <= edit.max().z() >> 4; cz++) {
                ChunkPermit permit = ChunkPermits.forChunk(cx, cz, edit, outsideSpawn);
                for (int x = Math.max(edit.min().x(), cx << 4); x <= Math.min(edit.max().x(), (cx << 4) + 15); x++) {
                    for (int z = Math.max(edit.min().z(), cz << 4); z <= Math.min(edit.max().z(), (cz << 4) + 15); z++) {
                        assertEquals(outsideSpawn.allowed(x, z), permit.allows(x, edit.min().y(), z),
                                "column " + x + "," + z);
                        if (!permit.allows(x, edit.max().y(), z)) denied++;
                    }
                }
            }
        }
        assertEquals((2L * radius + 1) * (2L * radius + 1), denied);
    }

    /** Vanilla spawn protection: Chebyshev distance to spawn at most the radius. */
    private static ChunkPermits.ColumnTest spawnProtection(int spawnX, int spawnZ, int radius) {
        return (x, z) -> Math.max(Math.abs(x - spawnX), Math.abs(z - spawnZ)) > radius;
    }

    /** Checks every column of {@code edit} against the model, with the spawn as focus; returns the denied count. */
    private static long checkExact(Box edit, int spawnX, int spawnZ, int radius) {
        ChunkPermits.ColumnTest test = spawnProtection(spawnX, spawnZ, radius);
        long denied = 0;
        for (int cx = edit.min().x() >> 4; cx <= edit.max().x() >> 4; cx++) {
            for (int cz = edit.min().z() >> 4; cz <= edit.max().z() >> 4; cz++) {
                ChunkPermit permit = ChunkPermits.forChunk(cx, cz, edit, test, spawnX, spawnZ);
                for (int x = Math.max(edit.min().x(), cx << 4); x <= Math.min(edit.max().x(), (cx << 4) + 15); x++) {
                    for (int z = Math.max(edit.min().z(), cz << 4); z <= Math.min(edit.max().z(), (cz << 4) + 15); z++) {
                        assertEquals(test.allowed(x, z), permit.allows(x, edit.min().y(), z),
                                "column " + x + "," + z + " radius " + radius);
                        if (!permit.allows(x, edit.max().y(), z)) denied++;
                    }
                }
            }
        }
        return denied;
    }

    /** M3: a protected square smaller than a chunk, inside the rectangle, touches no corner. */
    @Test
    void smallSpawnProtectionInsideAChunkIsFound() {
        Box edit = new Box(new BlockPos(-32, 0, -32), new BlockPos(47, 10, 47));
        for (int radius : new int[] {0, 1, 6}) {
            // Spawn in the middle of chunk (1, 1): the square never reaches the chunk's corners.
            long denied = checkExact(edit, 24, 24, radius);
            assertEquals((2L * radius + 1) * (2L * radius + 1), denied, "radius " + radius);
            // The corner-only rule misses it, which is why the focus sample exists.
            assertSame(ChunkPermit.ALLOW, ChunkPermits.forChunk(1, 1, edit, spawnProtection(24, 24, radius)));
        }
    }

    @Test
    void smallSpawnProtectionStraddlingAnEdgeIsFound() {
        Box edit = new Box(new BlockPos(-32, 0, -32), new BlockPos(47, 10, 47));
        // Spawn on the boundary between chunks (0, 1) and (1, 1), mid-way along z: both chunks see only an edge.
        for (int radius : new int[] {0, 1, 6}) {
            long denied = checkExact(edit, 16, 24, radius);
            assertEquals((2L * radius + 1) * (2L * radius + 1), denied, "radius " + radius);
        }
        // Spawn just outside the edit box: the rectangle's edge column is still the nearest one sampled.
        Box narrow = new Box(new BlockPos(0, 0, 0), new BlockPos(15, 10, 15));
        assertEquals(3 * 1, checkExact(narrow, 16, 8, 1)); // columns x = 15, z = 7..9
    }

    @Test
    void rectangleMustStayInOneChunk() {
        assertThrows(IllegalArgumentException.class, () -> ChunkPermits.evaluate(0, 0, 16, 0, (x, z) -> true));
        assertThrows(IllegalArgumentException.class, () -> ChunkPermits.evaluate(3, 0, 2, 0, (x, z) -> true));
    }

    // ------------------------------------------------------------------ three dimensions (protection limited in height)

    /** Chunk (0, 0), layers 0..19: two sections, so a cell test may differ between them. */
    private static final Box TALL = new Box(new BlockPos(0, 0, 0), new BlockPos(15, 19, 15));

    /** Checks {@code permit} against {@code test} on every cell of {@code box}; returns the denied count. */
    private static long checkEveryCell(ChunkPermit permit, ChunkPermits.CellTest test, Box box) {
        long denied = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    assertEquals(test.allowed(x, y, z), permit.allows(x, y, z), x + "," + y + "," + z);
                    if (!permit.allows(x, y, z)) denied++;
                }
            }
        }
        return denied;
    }

    @Test
    void uniformAsksOneCell() {
        AtomicInteger calls = new AtomicInteger();
        assertSame(ChunkPermit.ALLOW, ChunkPermits.uniform(0, 0, TALL, (x, y, z) -> {
            calls.incrementAndGet();
            return true;
        }));
        assertEquals(1, calls.get());
        assertSame(ChunkPermit.DENY, ChunkPermits.uniform(0, 0, TALL, (x, y, z) -> false));
        assertSame(ChunkPermit.ALLOW, ChunkPermits.uniform(1, 0, TALL, (x, y, z) -> false), "untouched chunk");
    }

    @Test
    void rasterizeAsksEveryCellOnceAndKeepsAgreementCompact() {
        AtomicInteger calls = new AtomicInteger();
        assertSame(ChunkPermit.ALLOW, ChunkPermits.rasterize(0, 0, TALL, (x, y, z) -> {
            calls.incrementAndGet();
            return true;
        }));
        assertEquals(16 * 16 * 20, calls.get(), "every cell, once");
        assertSame(ChunkPermit.DENY, ChunkPermits.rasterize(0, 0, TALL, (x, y, z) -> false));
        // Only the part of the box inside the chunk: x 20..31, y 5..9, z 0..3 of chunk (1, 0).
        Box box = new Box(new BlockPos(20, 5, -5), new BlockPos(40, 9, 3));
        calls.set(0);
        ChunkPermits.rasterize(1, 0, box, (x, y, z) -> {
            calls.incrementAndGet();
            assertTrue(x >= 20 && x <= 31 && y >= 5 && y <= 9 && z >= 0 && z <= 3, x + "," + y + "," + z);
            return true;
        });
        assertEquals(12 * 5 * 4, calls.get());
        assertSame(ChunkPermit.ALLOW, ChunkPermits.rasterize(5, 5, box, (x, y, z) -> false), "untouched chunk");
        // A mixed answer the same at every height: what a column test gives.
        assertEquals(ChunkPermits.forChunk(0, 0, TALL, (x, z) -> x < 5), ChunkPermits.rasterize(0, 0, TALL,
                (x, y, z) -> x < 5));
    }

    @Test
    void protectionLimitedInHeightIsKeptPerCell() {
        // Allowed at y 0..9, denied at y 10..19, in every column.
        ChunkPermits.CellTest lowerHalf = (x, y, z) -> y < 10;
        ChunkPermit permit = ChunkPermits.rasterize(0, 0, TALL, lowerHalf);
        ChunkPermit.Cells cells = assertInstanceOf(ChunkPermit.Cells.class, permit);
        assertEquals(0, cells.minY());
        assertEquals(19, cells.maxY());
        assertEquals(16 * 16 * 10, checkEveryCell(permit, lowerHalf, TALL));
        // The permit says nothing beyond the box's heights: denied there.
        assertFalse(permit.allows(0, -1, 0));
        assertFalse(permit.allows(0, 20, 0));
        // An allowed layer between protected ones, which no corner of the box shows.
        ChunkPermits.CellTest band = (x, y, z) -> y >= 5 && y < 10;
        assertEquals(16 * 16 * 15, checkEveryCell(ChunkPermits.rasterize(0, 0, TALL, band), band, TALL));
        // A small protected box touching no corner of the part.
        ChunkPermits.CellTest small = (x, y, z) -> !(x >= 5 && x <= 7 && y >= 8 && y <= 9 && z >= 6 && z <= 8);
        assertEquals(3 * 2 * 3, checkEveryCell(ChunkPermits.rasterize(0, 0, TALL, small), small, TALL));
    }

    /**
     * Nested regions with priorities, which sampling gets wrong: R1 (x 2..13, z 2..13, y 5..15) denies, R2 inside it
     * (x 3..12, z 3..12, same heights) allows with a higher priority. Every corner of the chunk lies outside R1 and both
     * regions' centres inside R2, all allowed; only the one-cell ring R1 \ R2 is denied, and it must be.
     */
    @Test
    void nestedRegionsWithPrioritiesKeepTheirRingDenied() {
        Box chunk = new Box(new BlockPos(0, 0, 0), new BlockPos(15, 20, 15));
        ChunkPermits.CellTest nested = (x, y, z) -> {
            boolean inR2 = x >= 3 && x <= 12 && z >= 3 && z <= 12 && y >= 5 && y <= 15;
            if (inR2) return true;
            boolean inR1 = x >= 2 && x <= 13 && z >= 2 && z <= 13 && y >= 5 && y <= 15;
            return !inR1;
        };
        ChunkPermit permit = ChunkPermits.rasterize(0, 0, chunk, nested);
        long ring = (12L * 12 - 10 * 10) * 11; // 44 columns, y 5..15
        assertEquals(ring, checkEveryCell(permit, nested, chunk));
        assertFalse(permit.allows(2, 5, 2), "a ring cell");
        assertFalse(permit.allows(13, 15, 7), "a ring cell");
        assertTrue(permit.allows(3, 10, 3), "inside R2");
        assertTrue(permit.allows(2, 4, 2), "below R1");
        assertTrue(permit.allows(2, 16, 2), "above R1");
    }

    /**
     * A fill asks for its box, an undo for the record's (smaller) bounds: an exact permit judges every cell the same way
     * in both.
     */
    @Test
    void aCellIsJudgedTheSameWhateverBoundsItIsAskedWith() {
        ChunkPermits.CellTest thinStrip = (x, y, z) -> !(z == 9 && y >= 3 && y <= 4);
        Box fill = new Box(new BlockPos(0, 0, 0), new BlockPos(15, 19, 15));
        Box undo = new Box(new BlockPos(6, 2, 6), new BlockPos(11, 5, 11));
        ChunkPermit wide = ChunkPermits.rasterize(0, 0, fill, thinStrip);
        ChunkPermit narrow = ChunkPermits.rasterize(0, 0, undo, thinStrip);
        for (int y = 2; y <= 5; y++) {
            for (int z = 6; z <= 11; z++) {
                for (int x = 6; x <= 11; x++) {
                    assertEquals(wide.allows(x, y, z), narrow.allows(x, y, z), x + "," + y + "," + z);
                    assertEquals(thinStrip.allowed(x, y, z), narrow.allows(x, y, z), x + "," + y + "," + z);
                }
            }
        }
    }
}
