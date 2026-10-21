package dev.sculptory.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class S2CRouteTest {
    /** The join race: the hello came, the client's registration of sculptory:s2c not yet. The Welcome must go out. */
    @Test
    void helloBeforeRegistrationGoesAsPacket() {
        assertEquals(S2CRoute.PACKET, S2CRoute.of(false, true, false));
    }

    @Test
    void registeredClientGetsTheMessenger() {
        assertEquals(S2CRoute.MESSENGER, S2CRoute.of(true, true, false));
        assertEquals(S2CRoute.MESSENGER, S2CRoute.of(true, false, false));
    }

    @Test
    void forcedPacketNeverUsesTheMessenger() {
        assertEquals(S2CRoute.PACKET, S2CRoute.of(true, true, true));
        assertEquals(S2CRoute.PACKET, S2CRoute.of(true, false, true));
    }

    /** A client with no sign of the mod gets nothing, forced or not. */
    @Test
    void silentUnregisteredClientGetsNothing() {
        assertEquals(S2CRoute.NONE, S2CRoute.of(false, false, false));
        assertEquals(S2CRoute.NONE, S2CRoute.of(false, false, true));
    }
}
