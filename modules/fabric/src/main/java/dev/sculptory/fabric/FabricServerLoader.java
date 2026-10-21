package dev.sculptory.fabric;

import com.mojang.brigadier.CommandDispatcher;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.ServerLoader;
import dev.sculptory.mc.net.Frame;
import dev.sculptory.mc.world.McInternals;
import dev.sculptory.mc.world.WidenedMcInternals;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The {@link ServerLoader} on Fabric: each hook is one Fabric API event, registered in the default phase, so hooks run
 * in the order they were registered (Fabric's {@code Event} keeps registration order within a phase; the engine relies
 * on it, see {@code EngineRuntime.install}).
 * <ul>
 *   <li>{@link #onServerStarting}, {@link #onServerStarted}, {@link #onServerStopping}, {@link #onServerStopped},
 *       {@link #onAfterSave}: {@code ServerLifecycleEvents.SERVER_STARTING}, {@code SERVER_STARTED},
 *       {@code SERVER_STOPPING}, {@code SERVER_STOPPED}, {@code AFTER_SAVE} (server thread);</li>
 *   <li>{@link #onStartServerTick}, {@link #onStartWorldTick}, {@link #onEndServerTick}:
 *       {@code ServerTickEvents.START_SERVER_TICK}, {@code START_WORLD_TICK}, {@code END_SERVER_TICK} (server thread);</li>
 *   <li>{@link #onWorldUnload}: {@code ServerWorldEvents.UNLOAD} (server thread, during the stop);</li>
 *   <li>{@link #onPlayerJoin}, {@link #onPlayerDisconnect}: {@code ServerPlayConnectionEvents.JOIN} and
 *       {@code DISCONNECT}, given the handler's player; Fabric does not promise the thread, so the common hooks hop onto
 *       the server thread themselves;</li>
 *   <li>{@link #onCommands}: {@code CommandRegistrationCallback.EVENT}, whenever the server builds its command tree;</li>
 *   <li>{@link #receiveC2S}: {@code ServerPlayNetworking.registerGlobalReceiver} for {@code sculptory:c2s}. Fabric 1.21.1
 *       (networking API 4.3.0) runs play payload handlers through {@code MinecraftServer.execute}, i.e. on the server
 *       thread (checked with javap), so no extra hop is needed;</li>
 *   <li>{@link #canSendS2C}, {@link #sendS2C}: {@code ServerPlayNetworking.canSend} and {@code getSender} on the
 *       player's play connection;</li>
 *   <li>{@link #hasPermission}: fabric-permissions-api's {@code Permissions.check}, which falls back to the operator
 *       level without a permissions mod.</li>
 * </ul>
 * The two payload types ({@link Frame}) are registered with {@code PayloadTypeRegistry} once, when the loader is made.
 *
 * <p>One instance per JVM ({@link #install}): the mod initializer installs it, and so do the test mods' initializers,
 * since Fabric Loader does not order entrypoints by dependency (and shuffles mods in development runs).
 */
public final class FabricServerLoader implements ServerLoader {
    private static FabricServerLoader instance;

    private FabricServerLoader() {
        PayloadTypeRegistry.playC2S().register(Frame.C2S_ID, Frame.C2S_CODEC);
        PayloadTypeRegistry.playS2C().register(Frame.S2C_ID, Frame.S2C_CODEC);
    }

    /** Makes the loader (once per JVM) and installs it in {@link Loaders}, with the game internals read through the access widener
     * ({@link WidenedMcInternals}); idempotent.
     */
    public static synchronized FabricServerLoader install() {
        if (instance == null) instance = new FabricServerLoader();
        McInternals.install(WidenedMcInternals.INSTANCE);
        Loaders.installServer(instance);
        return instance;
    }

    // ---- LoaderInfo ----

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer(Sculptory.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    // ---- lifecycle ----

    @Override
    public void onServerStarting(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        ServerLifecycleEvents.SERVER_STARTING.register(hook::accept);
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        ServerLifecycleEvents.SERVER_STARTED.register(hook::accept);
    }

    @Override
    public void onServerStopping(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        ServerLifecycleEvents.SERVER_STOPPING.register(hook::accept);
    }

    @Override
    public void onServerStopped(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        ServerLifecycleEvents.SERVER_STOPPED.register(hook::accept);
    }

    @Override
    public void onAfterSave(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> hook.accept(server));
    }

    @Override
    public void onStartServerTick(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        ServerTickEvents.START_SERVER_TICK.register(hook::accept);
    }

    @Override
    public void onStartWorldTick(Consumer<ServerLevel> hook) {
        Objects.requireNonNull(hook);
        ServerTickEvents.START_WORLD_TICK.register(hook::accept);
    }

    @Override
    public void onEndServerTick(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        ServerTickEvents.END_SERVER_TICK.register(hook::accept);
    }

    @Override
    public void onWorldUnload(BiConsumer<MinecraftServer, ServerLevel> hook) {
        Objects.requireNonNull(hook);
        ServerWorldEvents.UNLOAD.register(hook::accept);
    }

    @Override
    public void onPlayerJoin(Consumer<ServerPlayer> hook) {
        Objects.requireNonNull(hook);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> hook.accept(handler.player));
    }

    @Override
    public void onPlayerDisconnect(Consumer<ServerPlayer> hook) {
        Objects.requireNonNull(hook);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> hook.accept(handler.player));
    }

    @Override
    public void onCommands(BiConsumer<CommandDispatcher<CommandSourceStack>, CommandBuildContext> hook) {
        Objects.requireNonNull(hook);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                hook.accept(dispatcher, registryAccess));
    }

    // ---- networking ----

    @Override
    public void receiveC2S(BiConsumer<ServerPlayer, byte[]> handler) {
        Objects.requireNonNull(handler);
        ServerPlayNetworking.registerGlobalReceiver(Frame.C2S_ID,
                (frame, context) -> handler.accept(context.player(), frame.bytes()));
    }

    @Override
    public boolean canSendS2C(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player.connection, Frame.S2C_ID);
    }

    @Override
    public void sendS2C(ServerPlayer player, byte[] frame) {
        ServerPlayNetworking.getSender(player.connection).sendPacket(Frame.s2c(frame));
    }

    // ---- permissions ----

    @Override
    public boolean hasPermission(ServerPlayer player, String node, int fallbackOpLevel) {
        return Permissions.check(player, node, fallbackOpLevel);
    }
}
