package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.testing.FakeStateSpace;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Per-asset access on the wire: the two requests, the answer, the listing's restricted flag, malformed input. */
class LibraryAccessProtocolTest {
    private static final FakeStateSpace STATES = new FakeStateSpace();
    private static final UUID ALICE = new UUID(1, 2);
    private static final UUID BOB = new UUID(3, 4);
    private static final AssetAccess.Grantee ALICE_G = new AssetAccess.Grantee(ALICE, "Alice");
    private static final AssetAccess.Grantee BOB_G = new AssetAccess.Grantee(BOB, "Bob_99");

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
    void requestsAndTheAnswerRoundTripWithTheirRequestIdUpFront() throws ProtocolException {
        List<AssetAccess.Grantee> many = new ArrayList<>();
        for (int i = 0; i < AssetAccess.MAX_PLAYERS; i++) many.add(new AssetAccess.Grantee(new UUID(9, i), "p" + i));
        List<C2S> requests = List.of(
                new C2S.LibraryAccessGet(7, "trees/oak.schem"),
                new C2S.LibraryAccessGet(-3, ""),
                new C2S.LibraryAccessSet(8, "trees/oak.schem", AssetAccess.EVERYONE),
                new C2S.LibraryAccessSet(9, "p.palette.json", AssetAccess.listed(List.of(ALICE_G, BOB_G))),
                new C2S.LibraryAccessSet(10, "x.schem", AssetAccess.listed(List.of(new AssetAccess.Grantee(null, "Typed")))),
                new C2S.LibraryAccessSet(11, "x.schem", AssetAccess.listed(many)));
        for (C2S request : requests) {
            byte[] frame = encode(request);
            assertEquals(request, Codec.decodeC2S(frame, STATES));
            assertEquals(request.type(), Codec.peekType(frame));
            int reqId = switch (request) {
                case C2S.LibraryAccessGet m -> m.reqId();
                case C2S.LibraryAccessSet m -> m.reqId();
                default -> throw new AssertionError(request);
            };
            assertEquals(OptionalInt.of(reqId), Codec.peekLeadingId(frame), request.type() + " leading id");
        }
        List<S2C> answers = List.of(
                new S2C.LibraryAccess(4, "trees/oak.schem", AssetAccess.EVERYONE),
                new S2C.LibraryAccess(5, "trees/oak.schem", AssetAccess.listed(List.of(ALICE_G, BOB_G))),
                new S2C.LibraryAccess(6, "x.schem", AssetAccess.listed(many)));
        for (S2C answer : answers) {
            byte[] frame = encode(answer);
            assertEquals(answer, Codec.decodeS2C(frame, STATES));
            assertEquals(OptionalInt.of(((S2C.LibraryAccess) answer).reqId()), Codec.peekLeadingId(frame));
        }
    }

    @Test
    void theListingCarriesTheRestrictedFlagPerEntry() throws ProtocolException {
        S2C.LibraryListing listing = new S2C.LibraryListing(6, "trees", List.of(
                new S2C.LibraryListing.Entry("trees/big", true, 0, ""),
                new S2C.LibraryListing.Entry("trees/oak.schem", false, 10, "ab".repeat(32),
                        S2C.LibraryListing.Entry.Kind.SCHEMATIC, true),
                new S2C.LibraryListing.Entry("trees/m.palette.json", false, 10, "", S2C.LibraryListing.Entry.Kind.PALETTE,
                        false)), true);
        byte[] frame = encode(listing);
        S2C.LibraryListing decoded = (S2C.LibraryListing) Codec.decodeS2C(frame, STATES);
        assertEquals(listing, decoded);
        assertTrue(decoded.entries().get(1).restricted());
        assertFalse(decoded.entries().get(0).restricted() || decoded.entries().get(2).restricted());
        assertThrows(IllegalArgumentException.class, () -> new S2C.LibraryListing.Entry("f", true, 0, "",
                S2C.LibraryListing.Entry.Kind.FOLDER, true), "a folder is never restricted");
        // On the wire, a folder flagged restricted is malformed rather than an exception escaping the decoder.
        byte[] one = encode(new S2C.LibraryListing(1, "", List.of(new S2C.LibraryListing.Entry("f", true, 0, "")), false));
        one[one.length - 2] = 1; // the entry's restricted flag, before the listing's writable flag
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(one));
        byte[] old = encode(new S2C.LibraryListing(1, "", List.of(new S2C.LibraryListing.Entry("f", true, 0, "")), false));
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(Arrays.copyOf(old, old.length - 1)),
                "an entry without its flag (an older server's) is refused");
    }

    @Test
    void anAccessIsChecked() {
        assertThrows(IllegalArgumentException.class, () -> new AssetAccess(AssetAccess.Mode.LISTED, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new AssetAccess(AssetAccess.Mode.EVERYONE, List.of(ALICE_G)));
        assertThrows(IllegalArgumentException.class, () -> AssetAccess.listed(List.of(ALICE_G, new AssetAccess.Grantee(ALICE, "A2"))));
        assertThrows(IllegalArgumentException.class, () -> new AssetAccess.Grantee(ALICE, ""));
        assertThrows(IllegalArgumentException.class, () -> new AssetAccess.Grantee(ALICE, "x".repeat(AssetAccess.MAX_NAME_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> new AssetAccess.Grantee(ALICE, "é".repeat(AssetAccess.MAX_NAME_BYTES / 2 + 1)));
        List<AssetAccess.Grantee> tooMany = new ArrayList<>();
        for (int i = 0; i <= AssetAccess.MAX_PLAYERS; i++) tooMany.add(new AssetAccess.Grantee(new UUID(9, i), "p" + i));
        assertThrows(IllegalArgumentException.class, () -> AssetAccess.listed(tooMany));
        assertThrows(NullPointerException.class, () -> new C2S.LibraryAccessSet(1, "a", null));
        assertThrows(NullPointerException.class, () -> new C2S.LibraryAccessGet(1, null));
        assertThrows(NullPointerException.class, () -> new S2C.LibraryAccess(1, null, AssetAccess.EVERYONE));
        AssetAccess listed = AssetAccess.listed(List.of(ALICE_G, new AssetAccess.Grantee(null, "Typed")));
        assertTrue(listed.names(ALICE) && !listed.names(BOB) && !listed.resolved() && listed.restricted());
        assertTrue(AssetAccess.listed(List.of(ALICE_G)).resolved());
        assertFalse(AssetAccess.EVERYONE.names(ALICE));
    }

    @Test
    void malformedAccessIsRefusedOnTheWire() throws ProtocolException {
        byte[] set = encode(new C2S.LibraryAccessSet(1, "a.schem", AssetAccess.listed(List.of(ALICE_G))));
        // type | reqId | path len | "a.schem" | mode | count | hasUuid | uuid | name len | name
        int mode = 1 + 1 + 1 + 7;
        byte[] badMode = set.clone();
        badMode[mode] = 2;
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(badMode), "an unknown mode");
        byte[] everyoneWithPlayers = set.clone();
        everyoneWithPlayers[mode] = 0;
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(everyoneWithPlayers));
        byte[] badBool = set.clone();
        badBool[mode + 2] = 2;
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(badBool));
        byte[] emptyName = Arrays.copyOf(set, mode + 2 + 1 + 16 + 1);
        emptyName[mode + 2 + 1 + 16] = 0;
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(emptyName), "an empty player name");
        byte[] trailing = Arrays.copyOf(set, set.length + 1);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(trailing));
        for (int length = 0; length < set.length; length++) {
            assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(set, length)), "cut to " + length);
        }

        // A listed access naming nobody, and one over the player cap.
        byte[] nobody = {(byte) MessageType.LIBRARY_ACCESS_SET.code(), 2, 1, 'a', 1, 0};
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(nobody));
        byte[] tooMany = {(byte) MessageType.LIBRARY_ACCESS_SET.code(), 2, 1, 'a', 1, (byte) 0x81, 0x02};
        assertEquals(ProtocolException.Reason.TOO_LARGE, c2sReason(tooMany));
        // The same player twice.
        byte[] twice = encode(new C2S.LibraryAccessSet(1, "a", AssetAccess.listed(List.of(ALICE_G, BOB_G))));
        byte[] duplicated = twice.clone();
        int second = 1 + 1 + 1 + 1 + 1 + 1 + 1 + 16 + 1 + 5; // up to the second grantee's hasUuid
        System.arraycopy(twice, second + 1, duplicated, second + 1, 16);
        System.arraycopy(twice, 1 + 1 + 1 + 1 + 1 + 1 + 1, duplicated, second + 1, 16);
        assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(duplicated));

        // The server's answer always carries UUIDs: one without is malformed for the client.
        byte[] answer = encode(new S2C.LibraryAccess(1, "a", AssetAccess.listed(List.of(ALICE_G))));
        byte[] noUuid = new byte[answer.length - 16];
        int hasUuid = 1 + 1 + 1 + 1 + 1 + 1;
        System.arraycopy(answer, 0, noUuid, 0, hasUuid);
        noUuid[hasUuid] = 0;
        System.arraycopy(answer, hasUuid + 1 + 16, noUuid, hasUuid + 1, answer.length - hasUuid - 1 - 16);
        assertEquals(ProtocolException.Reason.MALFORMED, s2cReason(noUuid));
        byte[] get = encode(new C2S.LibraryAccessGet(300, "a/b.schem"));
        for (int length = 0; length < get.length; length++) {
            assertEquals(ProtocolException.Reason.MALFORMED, c2sReason(Arrays.copyOf(get, length)), "cut to " + length);
        }
    }

    @Test
    void theNewRequestsShareTheOpsBucket() {
        assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(MessageType.LIBRARY_ACCESS_GET));
        assertEquals(RateLimiter.Kind.OPS, RateLimiter.frameKind(MessageType.LIBRARY_ACCESS_SET));
    }
}
