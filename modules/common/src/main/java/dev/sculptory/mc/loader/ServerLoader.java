package dev.sculptory.mc.loader;

import com.mojang.brigadier.CommandDispatcher;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The server-side seam between the loader-free code and a mod loader: the loader (Fabric now; NeoForge, Forge later)
 * implements it and installs it in {@link Loaders} before anything else runs. Hooks are registered once at
 * initialisation and run for the life of the JVM (the integrated server starts and stops many times).
 *
 * <p>Threads: every lifecycle and tick hook runs on the server thread. {@link #onPlayerJoin} and
 * {@link #onPlayerDisconnect} may run on a network thread (the loader says which); the hooks here hop onto the server
 * thread themselves when they need it. {@link #receiveC2S} handlers run on the server thread.
 */
public interface ServerLoader extends LoaderInfo {
    // ---- lifecycle ----

    /** The server exists and its worlds are about to load. */
    void onServerStarting(Consumer<MinecraftServer> hook);

    /** The worlds are loaded and players may join. */
    void onServerStarted(Consumer<MinecraftServer> hook);

    /** The server is about to save and unload its worlds. */
    void onServerStopping(Consumer<MinecraftServer> hook);

    /** The worlds are gone. */
    void onServerStopped(Consumer<MinecraftServer> hook);

    /** A full world save finished (autosave, {@code /save-all}, shutdown). */
    void onAfterSave(Consumer<MinecraftServer> hook);

    /** The start of a server tick, before the worlds tick. */
    void onStartServerTick(Consumer<MinecraftServer> hook);

    /** The start of one world's tick. */
    void onStartWorldTick(Consumer<ServerLevel> hook);

    /** The end of a server tick, after the worlds and the network. */
    void onEndServerTick(Consumer<MinecraftServer> hook);

    /** A world is being unloaded (server stop). */
    void onWorldUnload(BiConsumer<MinecraftServer, ServerLevel> hook);

    /** A player's play connection is set up (after registry sync; the player is in a world). */
    void onPlayerJoin(Consumer<ServerPlayer> hook);

    /** A player's play connection ended. */
    void onPlayerDisconnect(Consumer<ServerPlayer> hook);

    /** The server's command tree is being built; register commands on the dispatcher. */
    void onCommands(BiConsumer<CommandDispatcher<CommandSourceStack>, CommandBuildContext> hook);

    // ---- networking: the sculptory:c2s and sculptory:s2c payloads ----

    /** Receives every {@code sculptory:c2s} frame, on the server thread. */
    void receiveC2S(BiConsumer<ServerPlayer, byte[]> handler);

    /** Whether the player's client accepts {@code sculptory:s2c} (it has the mod and registered the channel). */
    boolean canSendS2C(ServerPlayer player);

    /** Sends one frame on {@code sculptory:s2c}. */
    void sendS2C(ServerPlayer player, byte[] frame);

    // ---- permissions ----

    /**
     * Whether the player holds {@code node} according to the server's permissions mod, or, without one, whether their
     * operator level is at least {@code fallbackOpLevel}. May throw when the permissions mod fails; the caller decides
     * what a failure means.
     */
    boolean hasPermission(ServerPlayer player, String node, int fallbackOpLevel);
}
