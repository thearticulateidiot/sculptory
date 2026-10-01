package dev.sculptory.fabric.client.editor;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.session.EditorSession;
import java.util.Optional;

/**
 * Where the editor gets its session, the client state space and the client world. The real
 * implementation combines {@code FabricEditorSession}, {@code FabricStateSpace} and a client
 * {@code WorldReader}; {@link SessionProvider} holds the one in use.
 */
public interface EditorBackend {
    /** A backend with no session: the editor refuses to open ("This server doesn't run Sculptory"). */
    EditorBackend NONE = new EditorBackend() {
        @Override
        public Optional<EditorSession> session() {
            return Optional.empty();
        }

        @Override
        public StateSpace states() {
            throw new IllegalStateException("No Sculptory session");
        }

        @Override
        public WorldReader world() {
            throw new IllegalStateException("No Sculptory session");
        }
    };

    /** The session for the current connection, or empty when there is none. */
    Optional<EditorSession> session();

    /** The state space of the current connection. Only called while {@link #session()} is present. */
    StateSpace states();

    /** The client world. Only called while {@link #session()} is present. */
    WorldReader world();

    /** Called once per client tick (the mock session advances its simulated jobs here). */
    default void tick() {}
}
