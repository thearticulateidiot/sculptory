package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.Arrays;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Protocol 5: the export request names its file format. */
class ExportFormatProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final UUID ID = new UUID(1, 2);

    private static ProtocolException.Reason reason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, STATES)).reason();
    }

    @Test
    void everyFormatRoundTrips() throws ProtocolException {
        for (SchematicFormat format : SchematicFormat.values()) {
            C2S.ExportClipboard export = new C2S.ExportClipboard(12, ID, format);
            byte[] frame = Codec.encodeC2S(export, STATES);
            assertEquals(export, Codec.decodeC2S(frame, STATES));
            assertEquals(OptionalInt.of(12), Codec.peekLeadingId(frame));
            assertEquals(format.ordinal(), frame[frame.length - 1], "the format is the last byte");
        }
        assertEquals(SchematicFormat.SPONGE, new C2S.ExportClipboard(1, ID).format(), "the default is .schem");
        assertEquals(5, ProtocolV2.VERSION, "the export format is new in protocol 5");
    }

    @Test
    void unknownFormatsAndProtocol4FramesAreMalformed() throws ProtocolException {
        byte[] frame = Codec.encodeC2S(new C2S.ExportClipboard(12, ID, SchematicFormat.STRUCTURE), STATES);
        byte[] unknown = frame.clone();
        unknown[unknown.length - 1] = (byte) SchematicFormat.values().length;
        assertEquals(ProtocolException.Reason.MALFORMED, reason(unknown));
        byte[] protocol4 = Arrays.copyOf(frame, frame.length - 1);
        assertEquals(ProtocolException.Reason.MALFORMED, reason(protocol4), "a frame without the format byte");
    }
}
