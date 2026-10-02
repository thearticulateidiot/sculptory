package dev.sculptory.fabric.net;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.engine.ClipboardService;
import dev.sculptory.fabric.engine.EditService;
import dev.sculptory.fabric.engine.PermissionService;
import dev.sculptory.fabric.engine.ScatterService;
import dev.sculptory.fabric.engine.TinkerService;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.Features;
import dev.sculptory.protocol.v2.Limits;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Server side of protocol v2 on Fabric: registers the payload types and the {@code sculptory:c2s}
 * receiver, keeps one {@link NetSession} per connection, and exposes push APIs for the engine.
 *
 * <p>Threading: Fabric 1.21.1 (networking API 4.3.0) runs play payload handlers through
 * {@code MinecraftServer.execute}, i.e. on the server thread (checked with javap), so no extra hop is needed.
 * Every public method here must be called on the server thread.
 *
 * <p>Vanilla clients are unaffected: a session exists only after the client sends a frame, and nothing is
 * sent unless {@code ServerPlayNetworking.canSend} is true for {@code sculptory:s2c}.
 */
public final class ServerNet {
    private static final Map<ServerPlayNetworkHandler, NetSession> SESSIONS = new IdentityHashMap<>();
    private static final JobListener NO_JOB_LISTENER = new JobListener() {
        @Override
        public void progress(UUID job, long done, long total, Phase ph) {}

        @Override
        public void finished(JobResult r) {}
    };
    private static ServerDispatcher dispatcher;
    private static volatile Consumer<ServerPlayerEntity> permissionCheckObserver;

    private ServerNet() {}

    /**
     * Installs the server networking. Call once from the common initializer.
     *
     * @param states the server's {@code StateSpace}; read when frames are decoded, so it may be built later
     *     (e.g. on server start). While it returns {@code null}, messages carrying block states are refused.
     */
    public static synchronized void install(EditService edits, ClipboardService clipboards, ScatterService scatter,
                                            TinkerService tinker, PermissionService permissions,
                                            Supplier<Limits> limits, Supplier<StateSpace> states) {
        if (dispatcher != null) throw new IllegalStateException("Sculptory server networking is already installed");
        Frame.registerTypes();
        dispatcher = new ServerDispatcher(edits, clipboards, scatter, permissions, limits, states, System::nanoTime);
        dispatcher.offerFeatures(() -> offered(states.get()));
        dispatcher.serveTinker(tinker);
        dispatcher.buildId(SculptoryMod.buildId());
        ServerPlayNetworking.registerGlobalReceiver(Frame.C2S_ID, (frame, context) -> receive(context.player(), frame));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> close(handler)));
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
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
        boolean fallback = !(serverStates instanceof FabricStateSpace fabric) || fabric.moddedFacingFallback();
        return fallback ? ServerDispatcher.SERVER_FEATURES.with(Features.MODDED_FACING_FALLBACK)
                : ServerDispatcher.SERVER_FEATURES;
    }

    /**
     * Re-checks the player's permissions and limits and pushes {@code PermissionsChanged} if they changed. Called by
     * {@code PlayerManagerMixin} whenever vanilla resends the command tree (op, deop, join, respawn); a permissions
     * mod's changes are caught by the dispatcher's periodic re-check. A no-op for players without a Sculptory
     * session. Safe from any thread: off the server thread it hops onto it.
     */
    public static void permissionsChanged(ServerPlayerEntity player) {
        if (player == null) return;
        MinecraftServer server = player.server;
        if (server != null && !server.isOnThread()) {
            server.execute(() -> permissionsChanged(player));
            return;
        }
        Consumer<ServerPlayerEntity> observer = permissionCheckObserver;
        if (observer != null) observer.accept(player);
        NetSession session = session(player);
        if (session != null) dispatcher.permissionsChanged(session);
    }

    /** Test hook (GameTests): sees every {@link #permissionsChanged} call on the server thread; {@code null} clears it. */
    public static void observePermissionChecks(Consumer<ServerPlayerEntity> observer) {
        permissionCheckObserver = observer;
    }

    /**
     * A listener that reports a job to the player's client. The engine must use it for jobs started without a
     * listener (undo and redo), or the client never sees them finish. It reports to the connection the player has now
     * (the one that asked for the job): after that connection ends it reports nothing, even once the player has
     * reconnected, since the new client never saw the job. A no-op when the player has no session.
     */
    public static JobListener jobListener(ServerPlayerEntity player) {
        NetSession session = session(Objects.requireNonNull(player));
        return session == null ? NO_JOB_LISTENER : dispatcher.jobListener(session);
    }

    /**
     * The brush lane wrote (or dropped) a dab batch admitted under prediction sequence {@code seq}. Call it
     * exactly once per admitted batch, instead of {@code updateSequence}: acknowledgements are cumulative, so
     * {@link PredictionAcks} sends them in sequence order and never ahead of a pending lower batch. Batches the
     * engine refused in its {@code DabOutcome} are acknowledged by the dispatcher and must not be reported here.
     * Without a session (no Sculptory handshake) this acknowledges directly.
     */
    public static void predictionApplied(ServerPlayerEntity player, int seq) {
        NetSession session = session(player);
        if (session != null) {
            dispatcher.predictionApplied(session, seq);
        } else if (player != null && seq >= 0) {
            player.networkHandler.updateSequence(seq);
        }
    }

    /** The brush lane finished the stroke's dabs up to {@code lastIndex} ({@code EditEvents.dabsApplied}). */
    public static void dabsApplied(ServerPlayerEntity player, int strokeId, int lastIndex) {
        NetSession session = session(player);
        if (session != null) dispatcher.dabsApplied(session, strokeId, lastIndex);
    }

    /** Pushes a history state, e.g. after a stroke's idle timeout creates an entry. */
    public static void sendHistoryState(ServerPlayerEntity player, S2C.HistoryState state) {
        NetSession session = session(player);
        if (session != null && session.ready()) dispatcher.send(session, Objects.requireNonNull(state));
    }

    public static void sendNotice(ServerPlayerEntity player, S2C.Notice notice) {
        NetSession session = session(player);
        if (session != null && session.ready()) dispatcher.send(session, Objects.requireNonNull(notice));
    }

    /**
     * Pushes a stroke status outside a {@code Dabs} reply: the brush lane refused a dab it had admitted, which
     * ends the stroke on the client. The session keeps the stroke open until the client's
     * {@code StrokeEnd} or next {@code StrokeBegin}, as for any refusal.
     */
    public static void sendStrokeStatus(ServerPlayerEntity player, S2C.StrokeStatus status) {
        NetSession session = session(player);
        if (session != null && session.ready()) dispatcher.send(session, Objects.requireNonNull(status));
    }

    /** Whether the player completed the Sculptory handshake. */
    public static boolean isReady(ServerPlayerEntity player) {
        NetSession session = session(player);
        return session != null && session.ready();
    }

    /**
     * The build id the player's client sent at the handshake ({@code /sculptory version}), or empty when their client has not
     * sent one (no Sculptory, or not yet). Set whether or not the handshake succeeded.
     */
    public static Optional<String> clientBuild(ServerPlayerEntity player) {
        NetSession session = session(player);
        if (session == null) return Optional.empty();
        String build = session.clientBuild;
        return build.isEmpty() ? Optional.empty() : Optional.of(build);
    }

    /** Whether the player's editor was refused at the handshake (a different protocol). */
    public static boolean isIncompatible(ServerPlayerEntity player) {
        NetSession session = session(player);
        return session != null && session.stage == NetSession.Stage.INCOMPATIBLE;
    }

    private static NetSession session(ServerPlayerEntity player) {
        if (dispatcher == null || player == null) return null;
        return SESSIONS.get(player.networkHandler);
    }

    private static void receive(ServerPlayerEntity player, Frame frame) {
        ServerPlayNetworkHandler handler = player.networkHandler;
        NetSession session = SESSIONS.computeIfAbsent(handler, h -> dispatcher.open(new PlayerTransport(h)));
        dispatcher.receive(session, frame.bytes());
    }

    private static void close(ServerPlayNetworkHandler handler) {
        NetSession session = SESSIONS.remove(handler);
        if (session != null) dispatcher.close(session);
    }

    private static void tick() {
        if (SESSIONS.isEmpty()) return;
        for (NetSession session : SESSIONS.values()) dispatcher.tick(session);
    }

    /** The real transport: the player's play network handler. */
    private record PlayerTransport(ServerPlayNetworkHandler handler) implements ServerTransport {
        @Override
        public ServerPlayerEntity player() {
            return handler.player;
        }

        @Override
        public boolean canSend() {
            return ServerPlayNetworking.canSend(handler, Frame.S2C_ID);
        }

        @Override
        public void send(byte[] frame) {
            ServerPlayNetworking.getSender(handler).sendPacket(Frame.s2c(frame));
        }

        @Override
        public void acknowledge(int sequence) {
            if (sequence >= 0) handler.updateSequence(sequence);
        }

        @Override
        public boolean tracks(int cx, int cz) {
            // The chunks vanilla is currently sending this player (its view-distance cylinder).
            return handler.player.getChunkFilter().isWithinDistance(cx, cz);
        }

        @Override
        public void resendChunk(int cx, int cz) {
            ServerWorld world = handler.player.getServerWorld();
            ServerChunkManager chunks = world.getChunkManager();
            // Loaded chunks only: getWorldChunk never loads or generates.
            WorldChunk chunk = chunks.getWorldChunk(cx, cz);
            if (chunk != null) {
                handler.sendPacket(new ChunkDataS2CPacket(chunk, chunks.getLightingProvider(), null, null));
            }
        }

        @Override
        public void disconnect(String reason) {
            handler.disconnect(Text.literal(reason));
        }
    }
}
