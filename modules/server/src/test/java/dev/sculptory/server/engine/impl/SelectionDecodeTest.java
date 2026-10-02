package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Uploaded selections are decoded strictly and must be the set announced ({@code ServerClipboards.decodeSelection}). */
class SelectionDecodeTest {
    private static final CellSet.Limits LIMITS = CellSet.Limits.of(10_000, 64);

    private static CellSet spread(int cells, int across) {
        Random random = new Random(cells);
        CellSet.Builder builder = CellSet.builder();
        while (builder.size() < cells) builder.add(random.nextInt(across), random.nextInt(across), random.nextInt(across));
        return builder.build();
    }

    private static RejectReason refusal(byte[] bytes, CellSet.Limits limits, Sha256 hash, Box bounds, long cells) {
        return assertThrows(EditRejected.class, () -> ServerClipboards.decodeSelection(bytes, limits, hash, bounds, cells))
                .reason();
    }

    @Test
    void theAnnouncedSetDecodes() throws EditRejected {
        CellSet set = spread(3000, 40);
        assertEquals(set, ServerClipboards.decodeSelection(set.encode(), LIMITS, set.hash(), set.bounds(), set.size()));
    }

    @Test
    void anotherSetThanAnnouncedIsInvalid() {
        CellSet set = spread(3000, 40);
        byte[] bytes = set.encode();
        assertEquals(RejectReason.INVALID, refusal(bytes, LIMITS, Sha256.digest(new byte[] {1}), set.bounds(), set.size()));
        Box wider = new Box(set.bounds().min(), set.bounds().max().offset(1, 0, 0));
        assertEquals(RejectReason.INVALID, refusal(bytes, LIMITS, set.hash(), wider, set.size()));
        assertEquals(RejectReason.INVALID, refusal(bytes, LIMITS, set.hash(), set.bounds(), set.size() - 1));
    }

    @Test
    void damagedBytesAreInvalidAndOversizedSetsTooLarge() {
        CellSet set = spread(3000, 40);
        byte[] bytes = set.encode();
        byte[] truncated = Arrays.copyOf(bytes, bytes.length / 2);
        assertEquals(RejectReason.INVALID, refusal(truncated, LIMITS, set.hash(), set.bounds(), set.size()));
        byte[] notASet = "not a cell set".getBytes();
        assertEquals(RejectReason.INVALID, refusal(notASet, LIMITS, set.hash(), set.bounds(), set.size()));
        assertEquals(RejectReason.TOO_LARGE, refusal(bytes, CellSet.Limits.of(2999, 64), set.hash(), set.bounds(), set.size()));
        // Few cells, but spread over more sections than allowed.
        CellSet sparse = spread(200, 400);
        assertEquals(RejectReason.TOO_LARGE, refusal(sparse.encode(), CellSet.Limits.of(10_000, 16), sparse.hash(),
                sparse.bounds(), sparse.size()));
    }
}
