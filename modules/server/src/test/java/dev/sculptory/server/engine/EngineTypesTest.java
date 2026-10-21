package dev.sculptory.server.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.protocol.v2.RejectReason;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EngineTypesTest {
    @Test
    void permNodesAreUniqueAndMaskRoundTrips() {
        Set<String> nodes = new HashSet<>();
        for (Perm perm : Perm.values()) {
            assertTrue(perm.node().startsWith("sculptory."));
            assertTrue(nodes.add(perm.node()));
        }
        assertEquals("sculptory.schematic.import", Perm.SCHEMATIC_IMPORT.node());
        // Appended only: Navigate is bit 14, Biome (protocol 6) bit 15.
        assertEquals(14, Perm.NAVIGATE.bit());
        assertEquals(15, Perm.BIOME.bit());
        assertEquals("sculptory.biome", Perm.BIOME.node());
        assertEquals(Perm.BIOME, Perm.values()[Perm.values().length - 1]);
        assertTrue(Perm.values().length <= 64);
        EnumSet<Perm> granted = EnumSet.of(Perm.USE, Perm.BRUSH, Perm.EDIT_UNLOADED);
        assertEquals(granted, Perm.granted(Perm.mask(granted)));
        assertTrue(Perm.mask(granted).has(Perm.BRUSH.bit()));
        assertFalse(Perm.mask(granted).has(Perm.ADMIN.bit()));
    }

    @Test
    void chunkPermitColumns() {
        long[] bits = new long[4];
        int allowedBit = (3 << 4) | 5; // x & 15 == 5, z & 15 == 3
        bits[allowedBit >>> 6] |= 1L << allowedBit;
        ChunkPermit permit = new ChunkPermit.Columns(bits);
        bits[0] = 0;
        // A column permit answers the same at every height, also far outside any box it was asked for.
        for (int y : new int[] {-64, 0, 70, 319, 10_000}) {
            assertTrue(permit.allows(16 * 7 + 5, y, -16 + 3), "y " + y);
            assertFalse(permit.allows(6, y, 3), "y " + y);
            assertTrue(ChunkPermit.ALLOW.allows(0, y, 0));
            assertFalse(ChunkPermit.DENY.allows(0, y, 0));
        }
        assertThrows(IllegalArgumentException.class, () -> new ChunkPermit.Columns(new long[3]));
    }

    @Test
    void chunkPermitCells() {
        // Layers 10..12: cell (5, 11, 3) allowed, (5, 10, 3) and (5, 12, 3) not; (0, *, 0) allowed on every layer.
        long[] bits = new long[3 * 4];
        int cell = (3 << 4) | 5;
        bits[ChunkPermit.Cells.word(10, 11, cell >>> 6)] |= 1L << cell;
        for (int y = 10; y <= 12; y++) bits[ChunkPermit.Cells.word(10, y, 0)] |= 1L;
        ChunkPermit.Cells permit = new ChunkPermit.Cells(10, 12, bits);
        bits[0] = 0;
        assertTrue(permit.allows(16 * 7 + 5, 11, -16 + 3), "low bits of x and z");
        assertFalse(permit.allows(5, 10, 3));
        assertFalse(permit.allows(5, 12, 3));
        for (int y = 10; y <= 12; y++) assertTrue(permit.allows(-16, y, 32), "the copy taken in, layer " + y);
        assertFalse(permit.allows(0, 9, 0), "below the layers: denied");
        assertFalse(permit.allows(0, 13, 0), "above the layers: denied");
        long[] out = permit.allowed();
        out[0] = 0;
        assertTrue(permit.allows(0, 10, 0), "the copy handed out");
        assertEquals(permit, new ChunkPermit.Cells(10, 12, permit.allowed()));
        assertEquals(permit.hashCode(), new ChunkPermit.Cells(10, 12, permit.allowed()).hashCode());
        long[] shifted = new long[3 * 4];
        System.arraycopy(permit.allowed(), 0, shifted, 0, shifted.length);
        assertFalse(permit.equals(new ChunkPermit.Cells(11, 13, shifted)), "the same bits at other heights");
        assertThrows(IllegalArgumentException.class, () -> new ChunkPermit.Cells(10, 12, new long[8]));
        assertThrows(IllegalArgumentException.class, () -> new ChunkPermit.Cells(12, 10, new long[0]));
    }

    @Test
    void outcomesCarryReasons() {
        assertEquals(RejectReason.AREA_BUSY, DabOutcome.rejected(4, RejectReason.AREA_BUSY).reason());
        assertTrue(DabOutcome.accepted(4).accepted());
        assertThrows(IllegalArgumentException.class, () -> new DabOutcome(true, 1, RejectReason.INVALID));
        EditRejected rejected = new EditRejected(RejectReason.TOO_LARGE, "3000000 > 2097152");
        assertEquals(RejectReason.TOO_LARGE, rejected.reason());
        assertEquals("TOO_LARGE: 3000000 > 2097152", rejected.getMessage());
    }
}
