package dev.sculptory.fabric.net;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * How a {@link NetSession} reaches its player. {@link ServerNet} wraps the play network handler; tests use a
 * fake. All methods run on the server thread.
 */
public interface ServerTransport {
    /** The player's current entity (it changes on respawn). */
    ServerPlayerEntity player();

    /** Whether the client registered {@code sculptory:s2c}; never send to a client without it. */
    boolean canSend();

    void send(byte[] frame);

    /** Acknowledges a block-prediction sequence (vanilla {@code updateSequence}); ignores negative values. */
    void acknowledge(int sequence);

    /** Whether the player is currently tracking chunk (cx, cz), i.e. it is within their view distance. */
    boolean tracks(int cx, int cz);

    /** Resends one chunk if it is loaded, so the client's view matches the server. */
    void resendChunk(int cx, int cz);

    void disconnect(String reason);
}
