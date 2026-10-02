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
        assertTrue(permit.allows(16 * 7 + 5, -16 + 3));
        assertFalse(permit.allows(6, 3));
        assertTrue(ChunkPermit.ALLOW.allows(0, 0));
        assertFalse(ChunkPermit.DENY.allows(0, 0));
        assertThrows(IllegalArgumentException.class, () -> new ChunkPermit.Columns(new long[3]));
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
