package dev.sculptory.fabric.client.world;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.EditorBackend;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.Features;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.client.world.ClientWorld;

/**
 * The editor's real backend: the protocol session, the client state space and a {@link ClientWorldReader}.
 *
 * <p>The session is always present, whatever its state, so the editor explains a refusal from the session state
 * ({@code HANDSHAKING}, {@code NO_SERVER_SUPPORT}, {@code INCOMPATIBLE}, no permission) and keeps one notice
 * subscription for the whole game. The editor only runs tools while the session is {@code READY}, which needs a
 * joined world; {@link #states()} and {@link #world()} throw outside one.
 *
 * <p>The editor's space follows the server: the modded facing fallback is on exactly when the handshake negotiated
 * {@link Features#MODDED_FACING_FALLBACK} ({@link #following}), so ghost previews turn modded blocks as the server's
 * paste will. Before the handshake it is off.
 *
 * <p>Render thread only (as {@link ClientStateSpaces}); callers hand the space they got to background work.
 */
public final class ClientEditorBackend implements EditorBackend {
    private final EditorSession session;
    private final Supplier<StateSpace> states;
    private final Supplier<ClientWorld> world;
    private ClientWorldReader reader;
    /** The last space handed out, for the connection's space and features it was made for (kept for identity). */
    private StateSpace viewOf;
    private Features viewFor;
    private StateSpace view;

    /**
     * @param states the current connection's space ({@link ClientStateSpaces})
     * @param world the current client world, e.g. {@code () -> MinecraftClient.getInstance().world}
     */
    public ClientEditorBackend(EditorSession session, Supplier<StateSpace> states, Supplier<ClientWorld> world) {
        this.session = Objects.requireNonNull(session);
        this.states = Objects.requireNonNull(states);
        this.world = Objects.requireNonNull(world);
    }

    @Override
    public Optional<EditorSession> session() {
        return Optional.of(session);
    }

    /** The connection's space, following the server's modded facing fallback; the same object until either changes. */
    @Override
    public StateSpace states() {
        StateSpace space = states.get();
        if (space == null) throw new IllegalStateException("No block-state space: not in a world, or it failed to build");
        Features features = session.capabilities().features();
        // Identity checks: a new join or tag reload builds a new space, a new handshake brings new features.
        if (space != viewOf || features != viewFor) {
            view = following(space, features);
            viewOf = space;
            viewFor = features;
        }
        return view;
    }

    /** {@code space} as the editor uses it with a server that negotiated {@code features}. */
    public static StateSpace following(StateSpace space, Features features) {
        return space instanceof FabricStateSpace fabric ? fabric.followingServer(features) : space;
    }

    /** A reader over the current world, reused until the world or the state space changes. */
    @Override
    public WorldReader world() {
        ClientWorld current = world.get();
        if (current == null) throw new IllegalStateException("Not in a world");
        StateSpace space = states();
        if (reader == null || !reader.readsFrom(current, space)) {
            reader = new ClientWorldReader(current, space);
        }
        return reader;
    }
}
