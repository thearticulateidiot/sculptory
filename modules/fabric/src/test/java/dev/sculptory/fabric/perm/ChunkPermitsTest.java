package dev.sculptory.fabric.perm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.fabric.engine.ChunkPermit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** The per-chunk corner rule. Spawn protection cannot be enabled in GameTest. */
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
            for (int z = 0; z < 16; z++) assertEquals(x < 5, permit.allows(x, z), x + "," + z);
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
            for (int z = -16; z < 0; z++) assertEquals(z >= -8, permit.allows(x, z));
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
                        assertEquals(outsideSpawn.allowed(x, z), permit.allows(x, z), "column " + x + "," + z);
                        if (!permit.allows(x, z)) denied++;
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
                        assertEquals(test.allowed(x, z), permit.allows(x, z), "column " + x + "," + z + " radius " + radius);
                        if (!permit.allows(x, z)) denied++;
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
}
