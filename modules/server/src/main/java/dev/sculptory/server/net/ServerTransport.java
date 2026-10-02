package dev.sculptory.server.net;

import java.util.UUID;

/**
 * How a {@link NetSession} reaches its player. The platform wraps its connection (on Fabric, {@code ServerNet} wraps
 * the play network handler); tests use a fake. All methods run on the server thread.
 *
 * @param <P> the platform's player type
 */
public interface ServerTransport<P> {
    /** The player's current entity (it changes on respawn). */
    P player();

    /** The player's id, or {@code null} when there is no player. */
    UUID playerId();

    /** The player's account name, or {@code null} when there is no player. */
    String playerName();

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
