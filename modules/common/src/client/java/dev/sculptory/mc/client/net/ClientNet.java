package dev.sculptory.mc.client.net;

import dev.sculptory.core.state.StateSpace;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.client.session.McEditorSession;
import dev.sculptory.mc.client.session.PreviewDecoder;
import dev.sculptory.mc.loader.ClientLoader;
import dev.sculptory.mc.loader.ClientLoaders;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

/**
 * Client side of protocol v2: registers the {@code sculptory:s2c} receiver with the loader (which registered both
 * payload types at startup) and drives one {@link McEditorSession} from the connection and tick events.
 *
 * <p>Threading: the {@link ClientLoader} delivers frames, join, disconnect and the tick on the render thread.
 */
public final class ClientNet {
    private static McEditorSession session;

    private ClientNet() {}

    /**
     * Installs the client networking and returns the session the editor uses. Call once from the client
     * initializer.
     *
     * @param states the client's current {@code StateSpace} (rebuilt on join); may return {@code null} until built
     */
    public static synchronized McEditorSession install(Supplier<StateSpace> states) {
        if (session != null) throw new IllegalStateException("Sculptory client networking is already installed");
        String version = Sculptory.buildId();
        // Previews are decoded on the game's worker pool and installed on the render thread at the next tick. Selections
        // are encoded for upload on a low-priority thread of their own: the largest takes up to about a second, which
        // must not hold a worker that meshes chunks.
        ExecutorService encoder = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Sculptory selection encoding");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        McEditorSession created = new McEditorSession(new NetworkTransport(), states, System::nanoTime, version,
                Util.backgroundExecutor(), encoder, PreviewDecoder.Limits.DEFAULT);
        ClientLoader loader = ClientLoaders.get();
        loader.receiveS2C(created::onFrame);
        loader.onJoin(created::onJoin);
        loader.onDisconnect(created::onDisconnect);
        loader.onClientTickEnd(created::tick);
        session = created;
        return created;
    }

    /** The installed session, or {@code null} before {@link #install}. */
    public static McEditorSession session() {
        return session;
    }

    /** Sends through the loader only when the server registered {@code sculptory:c2s} (false while not connected). */
    private static final class NetworkTransport implements McEditorSession.Transport {
        @Override
        public boolean canSend() {
            return ClientLoaders.get().canSendC2S();
        }

        @Override
        public void send(byte[] frame) {
            if (canSend()) ClientLoaders.get().sendC2S(frame);
        }

        /** The id the server gave this player at login (offline-mode servers too), from the connection's profile. */
        @Override
        public UUID player() {
            ClientPacketListener handler = Minecraft.getInstance().getConnection();
            return handler == null ? null : handler.getLocalGameProfile().getId();
        }
    }
}
