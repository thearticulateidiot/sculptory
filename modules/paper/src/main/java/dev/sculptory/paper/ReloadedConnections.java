package dev.sculptory.paper;

import dev.sculptory.protocol.v2.MessageType;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The editors that were connected before this plugin instance was enabled: a {@code /reload} (or a plugin manager)
 * disables the plugin and enables a fresh instance while players stay online. The fresh networking has no session for
 * them, and their client never says {@code Hello} again (it does so once, when it joins a world), so everything it
 * sends would count as "before the handshake" and get the player disconnected within a few frames. Instead, until such
 * a connection sends a {@code Hello}, its frames are dropped before they reach the networking, and the player is told
 * once to rejoin, which runs the handshake again. No protocol message asks a client to repeat its {@code Hello}; one
 * that does (so the editor reconnects without a rejoin) is planned for the next protocol version, and replaces this.
 *
 * <p>Keys are play connections, held weakly (a connection is forgotten once it is gone). Server thread only, but
 * synchronized since the self-test reads the counts.
 *
 * @param <K> the connection type ({@code ServerGamePacketListenerImpl} on the server; any key in tests)
 */
final class ReloadedConnections<K> {
    /** What to do with a frame from a connection. */
    enum Verdict {
        /** To the networking as usual. */
        FORWARD,
        /** Dropped, and the player is told to rejoin (the first dropped frame of the connection). */
        DROP_AND_TELL,
        /** Dropped quietly (told already). */
        DROP
    }

    /** Per connection that predates this instance: the frames dropped from it so far. */
    private final Map<K, Integer> dropped = Collections.synchronizedMap(new WeakHashMap<>());

    /** {@code connection} was online before this instance was enabled. */
    void joinedBefore(K connection) {
        dropped.putIfAbsent(connection, 0);
    }

    /** Forgets {@code connection} (its frames are forwarded again). */
    void forget(K connection) {
        dropped.remove(connection);
    }

    /** What to do with a frame of {@code type} ({@code null}: unreadable) from {@code connection}. */
    Verdict receive(K connection, MessageType type) {
        synchronized (dropped) {
            Integer count = dropped.get(connection);
            if (count == null) return Verdict.FORWARD;
            if (type == MessageType.HELLO) {
                // A client that does say Hello (its own join raced the reload) gets an ordinary handshake.
                dropped.remove(connection);
                return Verdict.FORWARD;
            }
            dropped.put(connection, count + 1);
            return count == 0 ? Verdict.DROP_AND_TELL : Verdict.DROP;
        }
    }

    /** Frames dropped from {@code connection} so far, or -1 when it is not one that predates this instance. */
    int dropped(K connection) {
        Integer count = dropped.get(connection);
        return count == null ? -1 : count;
    }

    void clear() {
        dropped.clear();
    }
}
