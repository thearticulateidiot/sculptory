package dev.sculptory.fabric.client.editor;

import dev.sculptory.fabric.client.editor.mock.MockEditorSession;
import java.util.Objects;

/**
 * The static holder for the editor's {@link EditorBackend}.
 *
 * <p>Until the real client session is wired in, the default backend has no session (the editor
 * refuses to open), except with {@code -Dsculptory.mockSession=true}, which uses
 * {@link MockEditorSession#backend()}: always ready, fully permitted, simulated jobs.
 *
 * <p>The integrator swaps in the real session once from {@code SculptoryClientMod}:
 * <pre>{@code
 * SessionProvider.set(new EditorBackend() {
 *     public Optional<EditorSession> session() { return FabricEditorSession.current(); }
 *     public StateSpace states() { return FabricStateSpace.client(); }
 *     public WorldReader world() { return ClientWorldReader.current(); }
 * });
 * }</pre>
 * The mock property should keep priority, so developers can still test the editor on any server.
 */
public final class SessionProvider {
    public static final String MOCK_PROPERTY = "sculptory.mockSession";

    private static volatile EditorBackend backend = defaultBackend();

    private SessionProvider() {}

    public static EditorBackend get() {
        return backend;
    }

    public static void set(EditorBackend next) {
        backend = Objects.requireNonNull(next);
    }

    /** True when {@code -Dsculptory.mockSession=true} was given. */
    public static boolean mockRequested() {
        return Boolean.getBoolean(MOCK_PROPERTY);
    }

    private static EditorBackend defaultBackend() {
        return mockRequested() ? MockEditorSession.backend() : EditorBackend.NONE;
    }
}
