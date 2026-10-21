package dev.sculptory.mc.net;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.ServerLoader;
import dev.sculptory.mc.world.McStateSpace;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditService;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.PermissionService;
import dev.sculptory.server.engine.ScatterService;
import dev.sculptory.server.engine.TinkerService;
import dev.sculptory.server.net.NetSession;
import dev.sculptory.server.net.ServerDispatcher;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Server side of protocol v2: registers the {@code sculptory:c2s} receiver with the installed {@link ServerLoader}
 * (which registers the payload types), keeps one {@link NetSession} per connection, and exposes push APIs for the
 * engine.
 *
 * <p>Threading: the loader runs {@code sculptory:c2s} handlers on the server thread ({@link ServerLoader#receiveC2S}),
 * so no extra hop is needed. Every public method here must be called on the server thread.
 *
 * <p>Vanilla clients are unaffected: a session exists only after the client sends a frame, and nothing is
 * sent unless {@link ServerLoader#canSendS2C} is true for the player.
 */
public final class ServerNet {
    private static final Map<ServerGamePacketListenerImpl, NetSession<ServerPlayer>> SESSIONS = new IdentityHashMap<>();
    private static final JobListener NO_JOB_LISTENER = new JobListener() {
        @Override
        public void progress(UUID job, long done, long total, Phase ph) {}

        @Override
        public void finished(JobResult r) {}
    };
    private static ServerDispatcher<ServerPlayer> dispatcher;
    private static volatile Consumer<ServerPlayer> permissionCheckObserver;

    private ServerNet() {}

    /**
     * Undoes {@link #install}: every session is closed and the dispatcher dropped, so {@code install} may run again.
     * Only for a platform whose loader also forgets the hooks {@code install} registered (Paper's, when the plugin
     * is disabled and may be enabled again); the Fabric and NeoForge loaders keep hooks for the JVM's life and never
     * call this.
     */
    public static synchronized void uninstall() {
        if (dispatcher == null) return;
        SESSIONS.values().forEach(dispatcher::close);
        SESSIONS.clear();
        dispatcher = null;
    }

    /**
     * Installs the server networking. Call once from the common initializer (again only after {@link #uninstall}).
     *
     * @param states the server's {@code StateSpace}; read when frames are decoded, so it may be built later
     *     (e.g. on server start). While it returns {@code null}, messages carrying block states are refused.
     */
    public static synchronized void install(EditService<ServerPlayer> edits, ClipboardService<ServerPlayer> clipboards, ScatterService<ServerPlayer> scatter,
                                            TinkerService<ServerPlayer> tinker, PermissionService<ServerPlayer, ServerLevel> permissions,
                                            Supplier<Limits> limits, Supplier<StateSpace> states) {
        if (dispatcher != null) throw new IllegalStateException("Sculptory server networking is already installed");
        dispatcher = new ServerDispatcher<>(edits, clipboards, scatter, permissions, limits, states, System::nanoTime);
        dispatcher.offerFeatures(() -> offered(states.get()));
        dispatcher.serveTinker(tinker);
        dispatcher.buildId(Sculptory.buildId());
        ServerLoader loader = Loaders.server();
        loader.receiveC2S(ServerNet::receive);
        // The session is keyed by the play connection, which the player still holds when it disconnects.
        loader.onPlayerDisconnect(player -> {
            ServerGamePacketListenerImpl handler = player.connection;
            player.server.execute(() -> close(handler));
        });
        loader.onEndServerTick(server -> tick());
        loader.onServerStopped(server -> {
            SESSIONS.values().forEach(dispatcher::close);
            SESSIONS.clear();
        });
    }

    /**
     * The features this server offers in {@code Welcome}: {@link ServerDispatcher#SERVER_FEATURES}, plus
     * {@link Features#MODDED_FACING_FALLBACK} while the server's state space applies the fallback (config
     * {@code transform.moddedFacingFallback}), so clients turn their previews exactly as pastes turn. Before the engine
     * has a state space the config default (on) is offered; edits are refused then anyway.
     */
    public static Features offered(StateSpace serverStates) {
        boolean fallback = !(serverStates instanceof McStateSpace fabric) || fabric.moddedFacingFallback();
        return fallback ? ServerDispatcher.SERVER_FEATURES.with(Features.MODDED_FACING_FALLBACK)
                : ServerDispatcher.SERVER_FEATURES;
    }

    /**
     * Re-checks the player's permissions and limits and pushes {@code PermissionsChanged} if they changed. Called by
     * {@code PlayerManagerMixin} whenever vanilla resends the command tree (op, deop, join, respawn); a permissions
     * mod's changes are caught by the dispatcher's periodic re-check. A no-op for players without a Sculptory
     * session. Safe from any thread: off the server thread it hops onto it.
     */
    public static void permissionsChanged(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer server = player.server;
        if (server != null && !server.isSameThread()) {
            server.execute(() -> permissionsChanged(player));
            return;
        }
        Consumer<ServerPlayer> observer = permissionCheckObserver;
        if (observer != null) observer.accept(player);
        NetSession<ServerPlayer> session = session(player);
        if (session != null) dispatcher.permissionsChanged(session);
    }

    /** Test hook (GameTests): sees every {@link #permissionsChanged} call on the server thread; {@code null} clears it. */
    public static void observePermissionChecks(Consumer<ServerPlayer> observer) {
        permissionCheckObserver = observer;
    }

    /**
     * A listener that reports a job to the player's client. The engine must use it for jobs started without a
     * listener (undo and redo), or the client never sees them finish. It reports to the connection the player has now
     * (the one that asked for the job): after that connection ends it reports nothing, even once the player has
     * reconnected, since the new client never saw the job. A no-op when the player has no session.
     */
    public static JobListener jobListener(ServerPlayer player) {
        NetSession<ServerPlayer> session = session(Objects.requireNonNull(player));
        return session == null ? NO_JOB_LISTENER : dispatcher.jobListener(session);
    }

    /**
     * The brush lane wrote (or dropped) a dab batch admitted under prediction sequence {@code seq}. Call it
     * exactly once per admitted batch, instead of {@code updateSequence}: acknowledgements are cumulative, so
     * {@link PredictionAcks} sends them in sequence order and never ahead of a pending lower batch. Batches the
     * engine refused in its {@code DabOutcome} are acknowledged by the dispatcher and must not be reported here.
     * Without a session (no Sculptory handshake) this acknowledges directly.
     */
    public static void predictionApplied(ServerPlayer player, int seq) {
        NetSession<ServerPlayer> session = session(player);
        if (session != null) {
            dispatcher.predictionApplied(session, seq);
        } else if (player != null && seq >= 0) {
            player.connection.ackBlockChangesUpTo(seq);
        }
    }

    /** The brush lane finished the stroke's dabs up to {@code lastIndex} ({@code EditEvents.dabsApplied}). */
    public static void dabsApplied(ServerPlayer player, int strokeId, int lastIndex) {
        NetSession<ServerPlayer> session = session(player);
        if (session != null) dispatcher.dabsApplied(session, strokeId, lastIndex);
    }

    /** Pushes a history state, e.g. after a stroke's idle timeout creates an entry. */
    public static void sendHistoryState(ServerPlayer player, S2C.HistoryState state) {
        NetSession<ServerPlayer> session = session(player);
        if (session != null && session.ready()) dispatcher.send(session, Objects.requireNonNull(state));
    }

    public static void sendNotice(ServerPlayer player, S2C.Notice notice) {
        NetSession<ServerPlayer> session = session(player);
        if (session != null && session.ready()) dispatcher.send(session, Objects.requireNonNull(notice));
    }

    /**
     * Pushes a stroke status outside a {@code Dabs} reply: the brush lane refused a dab it had admitted, which
     * ends the stroke on the client. The session keeps the stroke open until the client's
     * {@code StrokeEnd} or next {@code StrokeBegin}, as for any refusal.
     */
    public static void sendStrokeStatus(ServerPlayer player, S2C.StrokeStatus status) {
        NetSession<ServerPlayer> session = session(player);
        if (session != null && session.ready()) dispatcher.send(session, Objects.requireNonNull(status));
    }

    /** Whether the player completed the Sculptory handshake. */
    public static boolean isReady(ServerPlayer player) {
        NetSession<ServerPlayer> session = session(player);
        return session != null && session.ready();
    }

    /**
     * The build id the player's client sent at the handshake ({@code /sculptory version}), or empty when their client has not
     * sent one (no Sculptory, or not yet). Set whether or not the handshake succeeded.
     */
    public static Optional<String> clientBuild(ServerPlayer player) {
        NetSession<ServerPlayer> session = session(player);
        if (session == null) return Optional.empty();
        String build = session.clientBuild();
        return build.isEmpty() ? Optional.empty() : Optional.of(build);
    }

    /** Whether the player's editor was refused at the handshake (a different protocol). */
    public static boolean isIncompatible(ServerPlayer player) {
        NetSession<ServerPlayer> session = session(player);
        return session != null && session.stage() == NetSession.Stage.INCOMPATIBLE;
    }

    private static NetSession<ServerPlayer> session(ServerPlayer player) {
        if (dispatcher == null || player == null) return null;
        return SESSIONS.get(player.connection);
    }

    private static void receive(ServerPlayer player, byte[] frame) {
        ServerGamePacketListenerImpl handler = player.connection;
        NetSession<ServerPlayer> session = SESSIONS.computeIfAbsent(handler, h -> dispatcher.open(new PlayerTransport(h)));
        dispatcher.receive(session, frame);
    }

    private static void close(ServerGamePacketListenerImpl handler) {
        NetSession<ServerPlayer> session = SESSIONS.remove(handler);
        if (session != null) dispatcher.close(session);
    }

    private static void tick() {
        if (SESSIONS.isEmpty()) return;
        for (NetSession<ServerPlayer> session : SESSIONS.values()) dispatcher.tick(session);
    }

    /** The real transport: the player's play network handler. */
    private record PlayerTransport(ServerGamePacketListenerImpl handler) implements McTransport {
        @Override
        public ServerPlayer player() {
            return handler.player;
        }

        @Override
        public boolean canSend() {
            return Loaders.server().canSendS2C(handler.player);
        }

        @Override
        public void send(byte[] frame) {
            Loaders.server().sendS2C(handler.player, frame);
        }

        @Override
        public void acknowledge(int sequence) {
            if (sequence >= 0) handler.ackBlockChangesUpTo(sequence);
        }

        @Override
        public boolean tracks(int cx, int cz) {
            // The chunks vanilla is currently sending this player (its view-distance cylinder).
            return handler.player.getChunkTrackingView().contains(cx, cz);
        }

        @Override
        public void resendChunk(int cx, int cz) {
            ServerLevel world = handler.player.serverLevel();
            ServerChunkCache chunks = world.getChunkSource();
            // Loaded chunks only: getWorldChunk never loads or generates.
            LevelChunk chunk = chunks.getChunkNow(cx, cz);
            if (chunk != null) {
                handler.send(new ClientboundLevelChunkWithLightPacket(chunk, chunks.getLightEngine(), null, null));
            }
        }

        @Override
        public void disconnect(String reason) {
            handler.disconnect(Component.literal(reason));
        }
    }
}
