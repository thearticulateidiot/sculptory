package dev.sculptory.fabric.perm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** L4: the singleplayer host is recognised by UUID, never by name. */
class SingleplayerHostTest {
    private static final UUID HOST_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final GameProfile HOST = new GameProfile(HOST_ID, "Builder");

    @Test
    void sameUuidIsTheHost() {
        assertTrue(FabricPermissionService.isSingleplayerHost(false, HOST, new GameProfile(HOST_ID, "Builder")));
    }

    @Test
    void aGuestWithTheHostsNameIsNotTheHost() {
        UUID guest = UUID.fromString("99999999-2222-3333-4444-555555555555");
        assertFalse(FabricPermissionService.isSingleplayerHost(false, HOST, new GameProfile(guest, "builder")));
        assertFalse(FabricPermissionService.isSingleplayerHost(false, HOST, new GameProfile(guest, "Builder")));
    }

    @Test
    void dedicatedServersAndMissingProfilesHaveNoHost() {
        assertFalse(FabricPermissionService.isSingleplayerHost(true, HOST, HOST));
        assertFalse(FabricPermissionService.isSingleplayerHost(false, null, HOST));
        assertFalse(FabricPermissionService.isSingleplayerHost(false, HOST, null));
    }
}
