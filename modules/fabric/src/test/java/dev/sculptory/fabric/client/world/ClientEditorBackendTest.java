package dev.sculptory.fabric.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.client.session.SessionState;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.net.ServerDispatcher;
import dev.sculptory.fabric.net.ServerNet;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Handshake;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.ProtocolException;
import dev.sculptory.protocol.v2.S2C;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The editor's state space follows the server's modded facing fallback through the real handshake: the client's Hello
 * offers it, the server offers it only while its space applies it ({@link ServerNet#offered}), and the backend hands
 * the tools the view the negotiated features ask for.
 */
class ClientEditorBackendTest {
    /** Built as the client builds it on join (SculptoryClientMod: the fallback's tables, switched on). */
    private static FabricStateSpace client;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        client = FabricStateSpace.build();
    }

    /** A client session and backend, joined and answered by a server whose space applies the fallback or not. */
    private static ClientEditorBackend handshake(Features offered) throws ProtocolException {
        List<byte[]> sent = new ArrayList<>();
        FabricEditorSession session = new FabricEditorSession(new FabricEditorSession.Transport() {
            @Override
            public boolean canSend() {
                return true;
            }

            @Override
            public void send(byte[] frame) {
                sent.add(frame);
            }
        }, () -> client, () -> 0L, "test");
        ClientEditorBackend backend = new ClientEditorBackend(session, () -> client, () -> null);
        FabricStateSpace before = assertInstanceOf(FabricStateSpace.class, backend.states());
        assertFalse(before.moddedFacingFallback(), "off until the server says otherwise");
        session.onJoin();
        C2S.Hello hello = assertInstanceOf(C2S.Hello.class, Codec.decodeC2S(sent.get(0), client));
        assertTrue(hello.features().has(Features.MODDED_FACING_FALLBACK), "the client can follow the fallback");
        S2C answer = Handshake.answer(hello, offered, Limits.DEFAULTS, Perm.mask(EnumSet.of(Perm.USE)), 1L);
        session.onFrame(Codec.encodeS2C(answer, client));
        assertEquals(SessionState.READY, session.state());
        return backend;
    }

    @Test
    void theEditorTurnsModdedBlocksExactlyWhenTheServerDoes() throws ProtocolException {
        for (boolean serverOn : new boolean[] {true, false}) {
            FabricStateSpace server = client.withModdedFacingFallback(serverOn);
            Features offered = ServerNet.offered(server);
            assertEquals(serverOn, offered.has(Features.MODDED_FACING_FALLBACK));
            assertTrue(offered.names().containsAll(ServerDispatcher.SERVER_FEATURES.names()));
            ClientEditorBackend backend = handshake(offered);
            FabricStateSpace view = assertInstanceOf(FabricStateSpace.class, backend.states());
            assertEquals(serverOn, view.moddedFacingFallback(), "server " + (serverOn ? "on" : "off"));
            assertSame(view, backend.states(), "the same view until the space or the features change");
            assertEquals(client.size(), view.size());
        }
    }

    @Test
    void anOlderServerWithoutTheFeatureGetsNoFallback() throws ProtocolException {
        FabricStateSpace view = assertInstanceOf(FabricStateSpace.class,
                handshake(ServerDispatcher.SERVER_FEATURES).states());
        assertFalse(view.moddedFacingFallback());
    }

    @Test
    void spacesWithoutAFallbackAreHandedOutAsTheyAre() {
        FakeStateSpace fake = new FakeStateSpace();
        assertSame(fake, ClientEditorBackend.following(fake, Features.of(Features.MODDED_FACING_FALLBACK)));
        assertTrue(ServerNet.offered(null).has(Features.MODDED_FACING_FALLBACK), "before the engine: the default");
    }
}
