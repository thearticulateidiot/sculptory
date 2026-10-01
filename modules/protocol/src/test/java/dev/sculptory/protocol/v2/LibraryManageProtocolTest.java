package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.testing.FakeStateSpace;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** M4 library management on the wire: move, delete, create folder, the change reply/push and the listing's flag. */
class LibraryManageProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();

    private static byte[] encode(C2S message) throws ProtocolException {
        return Codec.encodeC2S(message, STATES);
    }

    private static byte[] encode(S2C message) throws ProtocolException {
        return Codec.encodeS2C(message, STATES);
    }

    private static ProtocolException.Reason c2sReason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeC2S(frame, STATES)).reason();
    }

    private static ProtocolException.Reason s2cReason(byte[] frame) {
        return assertThrows(ProtocolException.class, () -> Codec.decodeS2C(frame, STATES)).reason();
    }

    @Test
    void requestsRoundTripAndCarryTheirRequestIdUpFront() throws ProtocolException {
        List<C2S> requests = List.of(
                new C2S.LibraryMove(7, false, "trees/oak.schem", "rocks/oak.schem"),
                new C2S.LibraryMove(-3, true, "trees", "forest"),
                new C2S.LibraryMove(0, false, "", ""),
                new C2S.LibraryDelete(8, false, "_players/00000000-0000-4000-8000-00000000000a/x.schem"),
                new C2S.LibraryDelete(9, true, "empty"),
                new C2S.LibraryCreateFolder(10, "trees/big"),
                new C2S.LibraryCreateFolder(11, ""));
        for (C2S request : requests) {
            byte[] frame = encode(request);
            assertEquals(request, Codec.decodeC2S(frame, STATES));
            assertEquals(request.type(), Codec.peekType(frame));
            int reqId = switch (request) {
                case C2S.LibraryMove m -> m.reqId();
                case C2S.LibraryDelete m -> m.reqId();
                case C2S.LibraryCreateFolder m -> m.reqId();
                default -> throw new AssertionError(request);
            };
            assertEquals(OptionalInt.of(reqId), Codec.peekLeadingId(frame), request.type() + " leading id");
        }
    }

    @Test
    void theChangeAndTheListingFlagRoundTrip() throws ProtocolException {
        List<S2C> messages = List.of(
                new S2C.LibraryChanged(4, false, "trees/oak.schem", "trees/old_oak.schem"),
                new S2C.LibraryChanged(S2C.LibraryChanged.PUSH, false, "trees/oak.schem", ""),
                new S2C.LibraryChanged(5, true, "", "trees/big"),
                new S2C.LibraryListing(6, "trees", List.of(new S2C.LibraryListing.Entry("trees/big", true, 0, "")), true),
                new S2C.LibraryListing(6, "trees", List.of(), false));
        for (S2C message : messages) {
            byte[] frame = encode(message);
            assertEquals(message, Codec.decodeS2C(frame, STATES));
        }
        byte[] writable = encode(new S2C.LibraryListing(1, "", List.of(), true));
        byte[] readOnly = encode(new S2C.LibraryListing(1, "", List.of(), false));
        assertEquals(1, writable[writable.length - 1], "the flag is the listing's last byte");
        assertEquals(0, readOnly[readOnly.length - 1]);
        assertEquals(OptionalInt.of(4), Codec.peekLeadingId(encode(messages.get(0))));
    }

    @Test
    void aChangeNamesAtLeastOnePath() throws ProtocolException {
        assertThrows(IllegalArgumentException.class, () -> new S2C.LibraryChanged(1, false, "", ""));
        assertThrows(NullPointerException.class, () -> new S2C.LibraryChanged(1, false, null, "a"));
        assertThrows(NullPointerException.class, () -> new C2S.LibraryMove(1, false, "a", null));
        assertThrows(NullPointerException.class, () -> new C2S.LibraryDelete(1, false, null));
        assertThrows(NullPointerException.class, () -> new C2S.LibraryCreateFolder(1, null));
        // On the wire: a change with two empty paths is malformed, not an exception escaping the decoder.
        byte[] empty = {(byte) MessageType.LIBRARY_CHANGED.code(), 2, 0, 0, 0};
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(empty));
    }

    @Test
    void truncatedFramesAreMalformedAtEveryLength() throws ProtocolException {
        List<byte[]> frames = List.of(
                encode(new C2S.LibraryMove(300, true, "a/b", "a/c")),
                encode(new C2S.LibraryDelete(300, false, "a/b.schem")),
                encode(new C2S.LibraryCreateFolder(300, "a/b")));
        for (byte[] frame : frames) {
            for (int length = 0; length < frame.length; length++) {
                byte[] cut = Arrays.copyOf(frame, length);
                assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(cut), "cut to " + length);
            }
        }
        byte[] change = encode(new S2C.LibraryChanged(300, true, "a/b", "a/c"));
        for (int length = 0; length < change.length; length++) {
            assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(Arrays.copyOf(change, length)), "cut to " + length);
        }
        byte[] listing = encode(new S2C.LibraryListing(1, "a", List.of(), true));
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(Arrays.copyOf(listing, listing.length - 1)),
                "a listing without its flag (an older server's) is refused, not read as read-only");
    }

    @Test
    void badBooleansTrailingBytesAndHugeStringsAreRefused() throws ProtocolException {
        byte[] move = encode(new C2S.LibraryMove(1, false, "a.schem", "b.schem"));
        byte[] badBool = move.clone();
        badBool[2] = 2; // type, reqId, then the folder flag
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(badBool));
        byte[] trailing = Arrays.copyOf(move, move.length + 1);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(trailing));

        byte[] delete = encode(new C2S.LibraryDelete(1, true, "x"));
        delete[2] = (byte) 0xFF;
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(delete));

        byte[] listing = encode(new S2C.LibraryListing(1, "", List.of(), false));
        listing[listing.length - 1] = 5;
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(listing));

        // A path length of Integer.MAX_VALUE (over the cap) and of -1 (0xFFFFFFFF), refused before allocating.
        byte[] huge = {(byte) MessageType.LIBRARY_CREATE_FOLDER.code(), 2, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, 0x07};
        assertEquals(ProtocolException.Reason.TOO_LARGE, c2sReason(huge));
        byte[] negative = {(byte) MessageType.LIBRARY_CREATE_FOLDER.code(), 2, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, 0x0F};
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(negative));
        // A length within the cap that the frame does not hold.
        byte[] short_ = {(byte) MessageType.LIBRARY_DELETE.code(), 2, 0, 50, 'a', 'b'};
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(short_));
    }

    @Test
    void pathsOverTheCapAreRefusedBothWays() throws ProtocolException {
        String longPath = "a".repeat(Codec.MAX_PATH_BYTES + 1);
        assertEquals(ProtocolException.Reason.TOO_LARGE,
                assertThrows(ProtocolException.class, () -> encode(new C2S.LibraryMove(1, false, "a.schem", longPath)))
                        .reason());
        assertEquals(ProtocolException.Reason.TOO_LARGE,
                assertThrows(ProtocolException.class, () -> encode(new S2C.LibraryChanged(1, false, longPath, "")))
                        .reason());
        // Hand-made: a 1025-byte path in a create-folder frame.
        byte[] body = new byte[Codec.MAX_PATH_BYTES + 1];
        Arrays.fill(body, (byte) 'a');
        byte[] frame = new byte[4 + body.length];
        frame[0] = (byte) MessageType.LIBRARY_CREATE_FOLDER.code();
        frame[1] = 2;
        frame[2] = (byte) 0x81; // varint 1025
        frame[3] = 0x08;
        System.arraycopy(body, 0, frame, 4, body.length);
        assertEquals(ProtocolException.Reason.TOO_LARGE, c2sReason(frame));
        // Exactly at the cap decodes.
        String atCap = "b".repeat(Codec.MAX_PATH_BYTES);
        assertEquals(new C2S.LibraryCreateFolder(1, atCap), Codec.decodeC2S(encode(new C2S.LibraryCreateFolder(1, atCap)), STATES));
    }

    @Test
    void theNewTypesKeepTheirDirectionAndBucket() throws ProtocolException {
        byte[] change = encode(new S2C.LibraryChanged(1, false, "a.schem", ""));
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, c2sReason(change), "a server message sent to the server");
        byte[] move = encode(new C2S.LibraryMove(1, false, "a.schem", "b.schem"));
        assertEquals(ProtocolException.Reason.UNKNOWN_TYPE, s2cReason(move), "a client message sent to the client");
        for (MessageType type : List.of(MessageType.LIBRARY_MOVE, MessageType.LIBRARY_DELETE,
                MessageType.LIBRARY_CREATE_FOLDER)) {
            assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(type), type + " is charged like other requests");
            assertTrue(type.clientToServer());
            assertFalse(type.serverToClient());
        }
        assertTrue(MessageType.LIBRARY_CHANGED.serverToClient());
        assertArrayEquals(new int[] {18, 19, 20, 80}, new int[] {MessageType.LIBRARY_MOVE.code(),
                MessageType.LIBRARY_DELETE.code(), MessageType.LIBRARY_CREATE_FOLDER.code(),
                MessageType.LIBRARY_CHANGED.code()});
    }
}
