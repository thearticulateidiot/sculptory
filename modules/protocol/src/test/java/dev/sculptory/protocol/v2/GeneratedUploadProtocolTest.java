package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.Arrays;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** The generators' upload announcement. */
class GeneratedUploadProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final Box BOX = Box.of(new BlockPos(-3, 60, 4), new BlockPos(0, 63, 7));

    private static byte[] encode(C2S message) throws ProtocolException {
        return Codec.encodeC2S(message, STATES);
    }

    private static ProtocolException.Reason c2sReason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, STATES)).reason();
    }

    private static byte[] patched(byte[] frame, int index, int value) {
        byte[] copy = frame.clone();
        copy[index] = (byte) value;
        return copy;
    }

    @Test
    void theAnnouncementRoundTripsWithItsRequestIdUpFront() throws ProtocolException {
        C2S.GeneratedUpload upload = new C2S.GeneratedUpload(-9, BOX, 40, 900);
        byte[] frame = encode(upload);
        assertEquals(upload, Codec.decodeC2S(frame, STATES));
        assertEquals(OptionalInt.of(-9), Codec.peekLeadingId(frame));
        assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(MessageType.GENERATED_UPLOAD));
        assertEquals(30, MessageType.GENERATED_UPLOAD.code());
        assertEquals(MessageType.Direction.C2S, MessageType.GENERATED_UPLOAD.direction());
        assertEquals(6, StreamKind.GENERATED_UPLOAD.ordinal(), "appended after SELECTION_UPLOAD");
        assertEquals(StreamKind.SELECTION_UPLOAD.ordinal() + 1, StreamKind.GENERATED_UPLOAD.ordinal());
    }

    @Test
    void malformedAnnouncementsAreRefused() throws ProtocolException {
        C2S.GeneratedUpload upload = new C2S.GeneratedUpload(1, BOX, 10, 100);
        byte[] frame = encode(upload);
        // type, reqId, box (6 zigzag bytes), cells, totalBytes
        int cells = 2 + 6;
        assertEquals(10, frame[cells]);
        assertEquals(100, frame[cells + 1]);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(frame, cells, 0)), "no cells");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(frame, cells, 65)), "more cells than the box");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(patched(frame, cells + 1, 0)), "no bytes");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(frame, frame.length - 1)), "truncated");
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(frame, frame.length + 1)), "trailing");
        assertThrows(IllegalArgumentException.class, () -> new C2S.GeneratedUpload(1, BOX, 65, 100));
        assertThrows(IllegalArgumentException.class, () -> new C2S.GeneratedUpload(1, BOX, 0, 100));
        assertThrows(IllegalArgumentException.class, () -> new C2S.GeneratedUpload(1, BOX, 1, 0));
        // It never travels the other way.
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE,
                assertThrows(ProtocolException.class, () -> Codec.decodeS2C(frame, STATES)).reason());
    }
}
