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
import java.util.ArrayList;
import java.util.List;
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
        ChunkPermits.CellTest any = (x, y, z) -> true;
        assertThrows(IllegalArgumentException.class, () -> ChunkPermits.evaluate(0, 0, 0, 16, 5, 0, any, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ChunkPermits.evaluate(0, 6, 0, 3, 5, 0, any, List.of()));
    }

    // ------------------------------------------------------------------ three dimensions (protection limited in height)

    /** Chunk (0, 0), layers 0..19: two sections, so a cell test may differ between them. */
    private static final Box TALL = new Box(new BlockPos(0, 0, 0), new BlockPos(15, 19, 15));

    @Test
    void uniformCellAnswersUseTheCornersOnly() {
        AtomicInteger calls = new AtomicInteger();
        ChunkPermits.CellTest allowed = (x, y, z) -> {
            calls.incrementAndGet();
            return true;
        };
        assertSame(ChunkPermit.ALLOW, ChunkPermits.forChunk(0, 0, TALL, allowed));
        assertEquals(8, calls.get(), "eight corners, no cell between");
        calls.set(0);
        ChunkPermits.CellTest denied = (x, y, z) -> {
            calls.incrementAndGet();
            return false;
        };
        assertSame(ChunkPermit.DENY, ChunkPermits.forChunk(0, 0, TALL, denied));
        assertEquals(8, calls.get());
        calls.set(0);
        assertSame(ChunkPermit.ALLOW, ChunkPermits.forChunk(0, 0, TALL, allowed, List.of(new BlockPos(6, 8, 7))));
        assertEquals(9, calls.get(), "and one per focus point");
        // A box one column wide has two distinct corners; one cell, one.
        calls.set(0);
        ChunkPermits.forChunk(0, 0, new Box(new BlockPos(3, 0, 4), new BlockPos(3, 19, 4)), allowed);
        assertEquals(2, calls.get());
        calls.set(0);
        ChunkPermits.forChunk(0, 0, new Box(new BlockPos(3, 7, 4), new BlockPos(3, 7, 4)), allowed);
        assertEquals(1, calls.get());
        assertSame(ChunkPermit.ALLOW, ChunkPermits.forChunk(1, 0, TALL, (x, y, z) -> false), "untouched chunk");
    }

    @Test
    void cornersAreThoseOfTheBoxPartInsideTheChunkAtItsHeights() {
        // Box covers x 20..40, y 5..9, z -5..3; in chunk (1, 0) the part is x 20..31, z 0..3.
        Box box = new Box(new BlockPos(20, 5, -5), new BlockPos(40, 9, 3));
        List<String> seen = new ArrayList<>();
        ChunkPermits.forChunk(1, 0, box, (x, y, z) -> {
            seen.add(x + "," + y + "," + z);
            return true;
        });
        assertEquals(List.of("20,5,0", "20,5,3", "20,9,0", "20,9,3", "31,5,0", "31,5,3", "31,9,0", "31,9,3"), seen);
    }

    @Test
    void protectionLimitedInHeightIsKeptPerCell() {
        // Allowed at y 0..9, denied at y 10..19, in every column.
        AtomicInteger calls = new AtomicInteger();
        ChunkPermits.CellTest lowerHalf = (x, y, z) -> {
            calls.incrementAndGet();
            return y < 10;
        };
        ChunkPermit permit = ChunkPermits.forChunk(0, 0, TALL, lowerHalf);
        ChunkPermit.Cells cells = assertInstanceOf(ChunkPermit.Cells.class, permit);
        assertEquals(0, cells.minY());
        assertEquals(19, cells.maxY());
        assertEquals(8 + 16 * 16 * 20, calls.get(), "the corners, then every cell once");
        long denied = 0;
        for (int y = 0; y <= 19; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(y < 10, permit.allows(x, y, z), x + "," + y + "," + z);
                    if (!permit.allows(x, y, z)) denied++;
                }
            }
        }
        assertEquals(16 * 16 * 10, denied);
        // The permit says nothing beyond the box's heights: denied there.
        assertFalse(permit.allows(0, -1, 0));
        assertFalse(permit.allows(0, 20, 0));
    }

    @Test
    void anAllowedLayerBetweenProtectedOnesIsFoundThroughAFocusPoint() {
        // Denied below y 5 and from y 10: every corner of the box is denied.
        ChunkPermits.CellTest band = (x, y, z) -> y >= 5 && y < 10;
        assertSame(ChunkPermit.DENY, ChunkPermits.forChunk(0, 0, TALL, band), "the corners alone deny it all");
        ChunkPermit permit = ChunkPermits.forChunk(0, 0, TALL, band, List.of(new BlockPos(8, 7, 8)));
        for (int y = 0; y <= 19; y++) assertEquals(y >= 5 && y < 10, permit.allows(3, y, 11), "y " + y);
    }

    @Test
    void aMixedAnswerTheSameAtEveryHeightIsKeptAsColumns() {
        ChunkPermit permit = ChunkPermits.forChunk(0, 0, TALL, (x, y, z) -> x < 5);
        assertEquals(ChunkPermits.forChunk(0, 0, TALL, (x, z) -> x < 5), permit, "what a column test gives");
    }

    @Test
    void aSmallProtectedBoxTouchingNoCornerIsFoundThroughAFocusPoint() {
        // A region of x 5..7, y 8..9, z 6..8 in the middle of the chunk: no corner of the box lies in it.
        ChunkPermits.CellTest outsideRegion = (x, y, z) -> !(x >= 5 && x <= 7 && y >= 8 && y <= 9 && z >= 6 && z <= 8);
        assertSame(ChunkPermit.ALLOW, ChunkPermits.forChunk(0, 0, TALL, outsideRegion),
                "the corners alone miss it, which is why focus points exist");
        // The region's centre as the focus: the cell of the box nearest it is in the region, so every cell is asked.
        ChunkPermit permit = ChunkPermits.forChunk(0, 0, TALL, outsideRegion, List.of(new BlockPos(6, 8, 7)));
        assertInstanceOf(ChunkPermit.Cells.class, permit);
        long denied = 0;
        for (int y = 0; y <= 19; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(outsideRegion.allowed(x, y, z), permit.allows(x, y, z), x + "," + y + "," + z);
                    if (!permit.allows(x, y, z)) denied++;
                }
            }
        }
        assertEquals(3 * 2 * 3, denied);
        // A box that reaches only the region's top layer, from above: still found, by the cell nearest the focus.
        Box above = new Box(new BlockPos(0, 9, 0), new BlockPos(15, 30, 15));
        ChunkPermit top = ChunkPermits.forChunk(0, 0, above, outsideRegion, List.of(new BlockPos(6, 8, 7)));
        assertFalse(top.allows(6, 9, 7));
        assertTrue(top.allows(6, 10, 7));
        // A region the box does not reach changes nothing.
        Box high = new Box(new BlockPos(0, 10, 0), new BlockPos(15, 30, 15));
        assertSame(ChunkPermit.ALLOW, ChunkPermits.forChunk(0, 0, high, outsideRegion, List.of(new BlockPos(6, 8, 7))));
    }
}
