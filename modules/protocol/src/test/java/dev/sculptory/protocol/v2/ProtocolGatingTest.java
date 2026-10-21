package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.region.Facing;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A session never carries a message type newer than its negotiated protocol: the protocol-aware codec entry points
 * refuse such a type both ways, and pass everything else through unchanged.
 */
class ProtocolGatingTest {
    private static final C2S.Navigate NAVIGATE = new C2S.Navigate(1, NavigateMode.JUMP, new BlockPos(3, 64, -2), Facing.UP, 0, 1, 0);
    private static final S2C.Notice NOTICE = new S2C.Notice(S2C.Notice.Level.INFO, "sculptory.test", List.of());

    @Test
    void everyTypeDatesFromThisBuildsRangeOrEarlier() {
        for (MessageType type : MessageType.values()) {
            assertTrue(type.since() >= MessageType.FIRST && type.since() <= ProtocolV2.VERSION,
                    type + " since " + type.since());
        }
        assertEquals(5, MessageType.NAVIGATE.since());
        assertEquals(MessageType.FIRST, MessageType.HELLO.since());
    }

    @Test
    void aTypeNewerThanTheSessionIsRefusedBothWays() throws ProtocolException {
        int older = MessageType.NAVIGATE.since() - 1;
        ProtocolException encode = assertThrows(ProtocolException.class, () -> Codec.encodeC2S(NAVIGATE, null, older));
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, encode.reason());
        byte[] frame = Codec.encodeC2S(NAVIGATE, null);
        ProtocolException decode = assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, null, older));
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, decode.reason());
        S2C.NavigateResult result = S2C.NavigateResult.refused(1, RejectReason.DISABLED);
        assertThrows(ProtocolException.class, () -> Codec.encodeS2C(result, null, older));
        byte[] resultFrame = Codec.encodeS2C(result, null);
        assertThrows(ProtocolException.class, () -> Codec.decodeS2C(resultFrame, null, older));
    }

    @Test
    void atOrAboveItsProtocolATypePassesUnchanged() throws ProtocolException {
        int since = MessageType.NAVIGATE.since();
        assertArrayEquals(Codec.encodeC2S(NAVIGATE, null), Codec.encodeC2S(NAVIGATE, null, since));
        assertArrayEquals(Codec.encodeC2S(NAVIGATE, null), Codec.encodeC2S(NAVIGATE, null, ProtocolV2.VERSION));
        assertEquals(NAVIGATE, Codec.decodeC2S(Codec.encodeC2S(NAVIGATE, null), null, since));
        assertArrayEquals(Codec.encodeS2C(NOTICE, null), Codec.encodeS2C(NOTICE, null, MessageType.FIRST));
        assertEquals(NOTICE, Codec.decodeS2C(Codec.encodeS2C(NOTICE, null), null, MessageType.FIRST));
    }

    @Test
    void anUnknownTypeIsStillTheDecodersError() {
        byte[] unknown = {127};
        ProtocolException e = assertThrows(ProtocolException.class, () -> Codec.decodeC2S(unknown, null, ProtocolV2.VERSION));
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, e.reason());
    }
}
