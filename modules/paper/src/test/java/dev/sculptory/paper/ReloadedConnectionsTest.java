package dev.sculptory.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.paper.ReloadedConnections.Verdict;
import dev.sculptory.protocol.v2.MessageType;
import org.junit.jupiter.api.Test;

class ReloadedConnectionsTest {
    private final ReloadedConnections<Object> connections = new ReloadedConnections<>();

    @Test
    void aConnectionThatJoinedAfterEnableIsForwarded() {
        Object connection = new Object();
        assertFalse(connections.tracks(connection));
        assertEquals(Verdict.FORWARD, connections.receive(connection, MessageType.STROKE_BEGIN));
        assertEquals(-1, connections.dropped(connection));
    }

    /** The editor connected before a /reload: its frames never reach the networking, and the player is told once. */
    @Test
    void framesFromAnEditorConnectedBeforeEnableAreDroppedAndThePlayerToldOnce() {
        Object connection = new Object();
        connections.joinedBefore(connection);
        assertEquals(Verdict.DROP_AND_TELL, connections.receive(connection, MessageType.STROKE_BEGIN));
        for (int i = 0; i < 20; i++) {
            assertEquals(Verdict.DROP, connections.receive(connection, MessageType.STREAM_ABORT));
        }
        assertEquals(Verdict.DROP, connections.receive(connection, null));
        assertEquals(22, connections.dropped(connection));
        assertTrue(connections.tracks(connection));
    }

    @Test
    void aHelloStartsAnOrdinaryHandshake() {
        Object connection = new Object();
        connections.joinedBefore(connection);
        connections.receive(connection, MessageType.STROKE_BEGIN);
        assertEquals(Verdict.FORWARD, connections.receive(connection, MessageType.HELLO));
        assertFalse(connections.tracks(connection));
        assertEquals(Verdict.FORWARD, connections.receive(connection, MessageType.STROKE_BEGIN));
        assertEquals(-1, connections.dropped(connection));
    }

    @Test
    void otherConnectionsAreNotAffected() {
        Object before = new Object();
        Object after = new Object();
        connections.joinedBefore(before);
        assertEquals(Verdict.FORWARD, connections.receive(after, MessageType.STROKE_BEGIN));
        assertEquals(Verdict.DROP_AND_TELL, connections.receive(before, MessageType.STROKE_BEGIN));
        connections.forget(before);
        assertEquals(Verdict.FORWARD, connections.receive(before, MessageType.STROKE_BEGIN));
    }
}
