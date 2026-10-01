package dev.sculptory.fabric.client.net;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.client.session.PreviewDecoder;
import dev.sculptory.fabric.net.Frame;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.util.Util;

/**
 * Client side of protocol v2 on Fabric: registers the payload types and the {@code sculptory:s2c} receiver
 * and drives one {@link FabricEditorSession} from the connection and tick events.
 *
 * <p>Threading: Fabric 1.21.1 (networking API 4.3.0) runs play payload handlers through
 * {@code MinecraftClient.execute}, i.e. on the render thread (checked with javap). {@code JOIN} and the tick
 * event also fire on the render thread; the disconnect is re-queued onto it to be safe.
 */
public final class ClientNet {
    private static FabricEditorSession session;

    private ClientNet() {}

    /**
     * Installs the client networking and returns the session the editor uses. Call once from the client
     * initializer.
     *
     * @param states the client's current {@code StateSpace} (rebuilt on join); may return {@code null} until built
     */
    public static synchronized FabricEditorSession install(Supplier<StateSpace> states) {
        if (session != null) throw new IllegalStateException("Sculptory client networking is already installed");
        Frame.registerTypes();
        String version = SculptoryMod.buildId();
        // Previews are decoded on the game's worker pool and installed on the render thread at the next tick. Selections
        // are encoded for upload on a low-priority thread of their own: the largest takes up to about a second, which
        // must not hold a worker that meshes chunks.
        ExecutorService encoder = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Sculptory selection encoding");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        FabricEditorSession created = new FabricEditorSession(new NetworkTransport(), states, System::nanoTime, version,
                Util.getMainWorkerExecutor(), encoder, PreviewDecoder.Limits.DEFAULT);
        ClientPlayNetworking.registerGlobalReceiver(Frame.S2C_ID, (frame, context) -> created.onFrame(frame.bytes()));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> created.onJoin());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(created::onDisconnect));
        ClientTickEvents.END_CLIENT_TICK.register(client -> created.tick());
        session = created;
        return created;
    }

    /** The installed session, or {@code null} before {@link #install}. */
    public static FabricEditorSession session() {
        return session;
    }

    /** Sends through Fabric only when the server registered {@code sculptory:c2s}. */
    private static final class NetworkTransport implements FabricEditorSession.Transport {
        @Override
        public boolean canSend() {
            try {
                return ClientPlayNetworking.canSend(Frame.C2S_ID);
            } catch (IllegalStateException notConnected) {
                return false;
            }
        }

        @Override
        public void send(byte[] frame) {
            if (canSend()) ClientPlayNetworking.send(Frame.c2s(frame));
        }

        /** The id the server gave this player at login (offline-mode servers too), from the connection's profile. */
        @Override
        public UUID player() {
            ClientPlayNetworkHandler handler = MinecraftClient.getInstance().getNetworkHandler();
            return handler == null ? null : handler.getProfile().getId();
        }
    }
}
