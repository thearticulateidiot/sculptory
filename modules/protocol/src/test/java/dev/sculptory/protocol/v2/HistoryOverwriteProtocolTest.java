package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.testing.FakeStateSpace;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Undo anyway / Redo anyway on the wire: {@code HistoryOverwrite} (code 21), {@code zigzag reqId | bool redo | varint steps}. */
class HistoryOverwriteProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();

    private static byte[] encode(C2S message) throws ProtocolException {
        return Codec.encodeC2S(message, STATES);
    }

    private static ProtocolException.Reason reason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, STATES)).reason();
    }

    @Test
    void roundTripsAndCarriesItsRequestIdUpFront() throws ProtocolException {
        List<C2S.HistoryOverwrite> requests = List.of(
                new C2S.HistoryOverwrite(7, false, 1),
                new C2S.HistoryOverwrite(-3, true, 64),
                new C2S.HistoryOverwrite(Integer.MAX_VALUE, false, C2S.HistoryOverwrite.MAX_STEPS),
                new C2S.HistoryOverwrite(Integer.MIN_VALUE, true, 300));
        for (C2S.HistoryOverwrite request : requests) {
            byte[] frame = encode(request);
            assertEquals(request, Codec.decodeC2S(frame, STATES));
            assertEquals(MessageType.HISTORY_OVERWRITE, Codec.peekType(frame));
            assertEquals(OptionalInt.of(request.reqId()), Codec.peekLeadingId(frame));
        }
        assertEquals(21, MessageType.HISTORY_OVERWRITE.code());
        assertArrayEquals(new byte[] {21, 14, 1, 3}, encode(new C2S.HistoryOverwrite(7, true, 3)), "the exact wire form");
        assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(MessageType.HISTORY_OVERWRITE));
    }

    @Test
    void stepCountsOutsideOneToTheCapAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> new C2S.HistoryOverwrite(1, false, 0));
        assertThrows(IllegalArgumentException.class, () -> new C2S.HistoryOverwrite(1, false, -1));
        assertThrows(IllegalArgumentException.class, () -> new C2S.HistoryOverwrite(1, true, C2S.HistoryOverwrite.MAX_STEPS + 1));
        // On the wire the same counts are malformed, never an exception escaping the decoder.
        assertEquals(ProtocolException.Reason.MALFORMED, reason(new byte[] {21, 2, 0, 0}), "zero steps");
        assertEquals(ProtocolException.Reason.MALFORMED,
                reason(new byte[] {21, 2, 1, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F}), "negative steps");
        assertEquals(ProtocolException.Reason.MALFORMED, reason(new byte[] {21, 2, 1, (byte) 0x81, (byte) 0x80, 0x04}),
                "one over the cap (65,537)");
    }

    @Test
    void truncatedFramesBadBooleansAndTrailingBytesAreMalformed() throws ProtocolException {
        byte[] frame = encode(new C2S.HistoryOverwrite(300, true, 200));
        for (int length = 0; length < frame.length; length++) {
            assertEquals(ProtocolException.Reason.MALFORMED, reason(Arrays.copyOf(frame, length)), "cut to " + length);
        }
        byte[] badBool = encode(new C2S.HistoryOverwrite(1, false, 2));
        badBool[2] = 2; // type, reqId, then the redo flag
        assertEquals(ProtocolException.Reason.MALFORMED, reason(badBool));
        byte[] trailing = Arrays.copyOf(frame, frame.length + 1);
        assertEquals(ProtocolException.Reason.MALFORMED, reason(trailing));
        byte[] longVarint = {21, 2, 0, (byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00};
        assertEquals(ProtocolException.Reason.MALFORMED, reason(longVarint), "a six-byte varint");
    }

    @Test
    void itIsAClientMessageOnly() throws ProtocolException {
        byte[] frame = encode(new C2S.HistoryOverwrite(1, false, 1));
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE,
                assertThrows(ProtocolException.class, () -> Codec.decodeS2C(frame, STATES)).reason());
    }
}
