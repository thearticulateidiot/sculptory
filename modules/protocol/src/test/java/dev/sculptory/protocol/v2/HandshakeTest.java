package dev.sculptory.protocol.v2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class HandshakeTest {
    @Test
    void negotiatesTheHighestCommonVersion() {
        assertEquals(OptionalInt.of(3), Handshake.negotiate(1, 3, 2, 5));
        assertEquals(OptionalInt.of(2), Handshake.negotiate(2, 2, 1, 9));
        assertEquals(OptionalInt.empty(), Handshake.negotiate(1, 1, 2, 3));
        assertEquals(OptionalInt.empty(), Handshake.negotiate(3, 1, 1, 3), "inverted range");
        assertEquals(OptionalInt.of(ProtocolV2.VERSION), Handshake.negotiate(1, 99));
        assertTrue(Handshake.supports(ProtocolV2.VERSION));
        assertFalse(Handshake.supports(ProtocolV2.VERSION + 1));
    }

    @Test
    void answerIsWelcomeWithCommonFeaturesOrIncompatible() {
        Features server = Features.of(Features.STROKES, Features.REGION_OPS, Features.HISTORY);
        C2S.Hello hello = Handshake.hello("0.2.0", Features.of(Features.STROKES, Features.SCATTER));
        S2C.Welcome welcome = assertInstanceOf(S2C.Welcome.class,
                Handshake.answer(hello, server, Limits.DEFAULTS, PermissionMask.NONE.with(1), 42L));
        assertEquals(ProtocolV2.VERSION, welcome.protocol());
        assertEquals(Features.of(Features.STROKES), welcome.features());
        assertEquals(42L, welcome.sessionEpoch());

        C2S.Hello future = new C2S.Hello(ProtocolV2.VERSION + 1, ProtocolV2.VERSION + 3, "9.0", Features.NONE);
        S2C.Incompatible incompatible = assertInstanceOf(S2C.Incompatible.class,
                Handshake.answer(future, server, Limits.DEFAULTS, PermissionMask.NONE, 1L));
        assertEquals(Handshake.MIN_PROTOCOL, incompatible.serverMinProtocol());
        assertEquals(Handshake.MAX_PROTOCOL, incompatible.serverMaxProtocol());
    }

    /**
     * Each version adds wire forms an older peer cannot decode (3: {@code RejectReason.ASSET_NOT_LOADED}; 4: library
     * management and the listing's writable flag; 5: the build ids in {@code Welcome}, Tinker, the flip upside down in the
     * transform byte and the stack op, the export format, and builder mode), so an older client is answered
     * {@code Incompatible} at the handshake rather than failing on a later frame.
     */
    @Test
    void olderPeersAreRefusedAtTheHandshake() {
        assertEquals(5, ProtocolV2.VERSION);
        for (int older = 2; older < ProtocolV2.VERSION; older++) {
            assertFalse(Handshake.supports(older));
            C2S.Hello old = new C2S.Hello(older, older, "0.2.0", Features.NONE);
            assertInstanceOf(S2C.Incompatible.class, Handshake.answer(old, Features.NONE, Limits.DEFAULTS,
                    PermissionMask.NONE, 1L), "a v" + older + " peer");
        }
        assertEquals(RejectReason.values().length - 2, RejectReason.ASSET_NOT_LOADED.ordinal(), "appended");
        assertEquals(RejectReason.values().length - 1, RejectReason.SELECTION_NOT_LOADED.ordinal(), "appended last");
    }

    /** A peer's build id keeps only what builds write; everything else (colour codes, line breaks, markup) is "?". */
    @Test
    void buildIdsFromPeersAreCleaned() {
        assertEquals("0.2.0-dev+6a043a85.dirty", Handshake.cleanBuild("0.2.0-dev+6a043a85.dirty"));
        assertEquals("0.2.0_x", Handshake.cleanBuild("0.2.0_x"));
        assertEquals("?cred?name??", Handshake.cleanBuild("§cred\nname<>"));
        assertEquals("a?b", Handshake.cleanBuild("aéb"));
        assertEquals("x?y", Handshake.cleanBuild("x😀y"), "one mark per character, not per UTF-16 unit");
        assertEquals("", Handshake.cleanBuild(""));
        assertEquals("", Handshake.cleanBuild(null));
    }

    @Test
    void longModVersionsAreCutOnCodePointBoundaries() throws ProtocolException {
        C2S.Hello hello = Handshake.hello("😀".repeat(40), Features.NONE);
        assertTrue(hello.modVersion().getBytes(StandardCharsets.UTF_8).length <= Codec.MAX_MOD_VERSION_BYTES);
        assertEquals(16, hello.modVersion().codePointCount(0, hello.modVersion().length()));
        assertEquals(hello, Codec.decodeC2S(Codec.encodeC2S(hello, null), null));
    }
}
