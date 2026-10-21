package dev.sculptory.paper;

/**
 * How one {@code sculptory:s2c} frame goes out on Paper ({@link PaperServerLoader#sendS2C}). Kept apart from Bukkit so
 * the rule is unit-tested (S2CRouteTest).
 *
 * <p>The race this covers: the Fabric client sends its hello as soon as the server's {@code minecraft:register}
 * (which Paper sends on join) tells it the server listens on {@code sculptory:c2s}, and its own
 * {@code minecraft:register} for {@code sculptory:s2c} reaches Paper after that hello (every join on Paper 1.21.1
 * build 133 so far). Bukkit's Messenger drops a plugin message, silently, on a channel the client has not registered
 * ({@code CraftPlayer.sendPluginMessage}), so the Welcome would be lost and the client would give up ("no handshake
 * answer"). A client that has sent a {@code sculptory:c2s} frame has the mod and its payload codec for
 * {@code sculptory:s2c}, so until its registration arrives frames go to it as a vanilla custom-payload packet.
 */
public enum S2CRoute {
    /** Through Bukkit's Messenger: the client registered {@code sculptory:s2c}. */
    MESSENGER,
    /** As a vanilla {@code ClientboundCustomPayloadPacket}: not registered (yet) but the client spoke, or forced. */
    PACKET,
    /** Not at all: the client neither registered the channel nor spoke the protocol (no Sculptory, as far as known). */
    NONE;

    /**
     * @param registered the client registered {@code sculptory:s2c}
     * @param spokeC2S the client sent a {@code sculptory:c2s} frame on this connection
     * @param forcePacket {@code -Dsculptory.paper.s2c=nms}: never the Messenger
     */
    static S2CRoute of(boolean registered, boolean spokeC2S, boolean forcePacket) {
        if (!registered && !spokeC2S) return NONE;
        return registered && !forcePacket ? MESSENGER : PACKET;
    }
}
