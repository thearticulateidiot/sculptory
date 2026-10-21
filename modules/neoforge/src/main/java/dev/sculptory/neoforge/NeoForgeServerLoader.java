package dev.sculptory.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.ServerLoader;
import dev.sculptory.mc.net.Frame;
import dev.sculptory.mc.world.McInternals;
import dev.sculptory.mc.world.WidenedMcInternals;
import dev.sculptory.server.engine.Perm;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * The {@link ServerLoader} on NeoForge: most hooks are one listener on NeoForge's game bus ({@link NeoForge#EVENT_BUS}),
 * registered at the default priority when the hook is, so hooks run in the order they were registered (the engine
 * relies on it, see {@code EngineRuntime.install}).
 * <ul>
 *   <li>{@link #onServerStarting}: {@code ServerAboutToStartEvent}, which NeoForge fires in {@code initServer} just
 *       before the worlds load ({@code ServerStartingEvent} comes after they loaded). Fabric's {@code SERVER_STARTING}
 *       fires a little earlier, before {@code initServer}; on a dedicated server the player list exists here and not
 *       there, which nothing in the hooks reads;</li>
 *   <li>{@link #onServerStarted}, {@link #onServerStopped}: {@code ServerStartedEvent}, {@code ServerStoppedEvent}, at
 *       the same places as Fabric's;</li>
 *   <li>{@link #onServerStopping}: not {@code ServerStoppingEvent}, which NeoForge skips when the tick loop crashed,
 *       while the stopping hooks close the undo history store before the worlds are saved. {@code MinecraftServerMixin}
 *       runs them at the head of {@code MinecraftServer.stopServer}, where Fabric API fires {@code SERVER_STOPPING}
 *       (fabric-lifecycle-events-v1 in Fabric API 0.116.4, read with javap): on every stop, crashed or not;</li>
 *   <li>{@link #onAfterSave}: NeoForge has no event for a finished save. {@code MinecraftServerMixin} runs the hooks at
 *       the tail of {@code MinecraftServer.saveAllChunks}, where Fabric API fires {@code AFTER_SAVE}: autosave,
 *       {@code /save-all} and the save during shutdown;</li>
 *   <li>{@link #onStartServerTick}, {@link #onEndServerTick}: {@code ServerTickEvent.Pre} and {@code .Post}, at the
 *       start and end of {@code tickServer}. Both starts come after {@code tickCount++}; NeoForge's Pre comes before
 *       {@code tickRateManager.tick()}, Fabric's {@code START_SERVER_TICK} after it (at the {@code tickChildren}
 *       call);</li>
 *   <li>{@link #onStartWorldTick}: {@code LevelTickEvent.Pre} for server worlds, just before {@code ServerLevel.tick};</li>
 *   <li>{@link #onWorldUnload}: {@code LevelEvent.Unload} for server worlds, in {@code stopServer} before each world
 *       closes (the client's world unloads fire it too and are skipped);</li>
 *   <li>{@link #onPlayerJoin}, {@link #onPlayerDisconnect}: {@code PlayerLoggedInEvent} at the end of
 *       {@code PlayerList.placeNewPlayer} and {@code PlayerLoggedOutEvent} in {@code PlayerList.remove}, both on the
 *       server thread, after the channel negotiation of the configuration phase;</li>
 *   <li>{@link #onCommands}: {@code RegisterCommandsEvent}, whenever the server builds its command tree;</li>
 *   <li>{@link #receiveC2S}, {@link #canSendS2C}, {@link #sendS2C}: the two payloads are registered optional in
 *       {@code RegisterPayloadHandlersEvent}, so a client without Sculptory joins and a Sculptory client joins servers
 *       without it. Handlers run on the main thread ({@code HandlerThread.MAIN}, the registrar's default): the server
 *       thread for {@code sculptory:c2s}, the render thread for {@code sculptory:s2c}. {@code canSendS2C} asks
 *       {@code hasChannel}: the channels negotiated with a NeoForge client, and for another loader's client the
 *       channels it announced, with {@code c:register} in the configuration phase (Fabric API's client answers
 *       NeoForge's with its play receivers) or {@code minecraft:register} (ad hoc, Fabric's client sends one when play
 *       starts). NeoForge accepts an optional payload from any client whatever it announced;</li>
 *   <li>{@link #hasPermission}: {@code PermissionAPI}, which a permissions mod (LuckPerms) backs. Its nodes must be
 *       registered up front, so every {@link Perm} node is ({@code PermissionGatherEvent.Nodes}); a node's default,
 *       used when no permissions mod decides, is the operator level the caller passes, like
 *       fabric-permissions-api's {@code Permissions.check(player, node, level)}.</li>
 * </ul>
 */
public final class NeoForgeServerLoader implements ServerLoader {
    /** The payload version NeoForge compares between client and server; Sculptory's handshake does its own. */
    private static final String PAYLOAD_VERSION = "1";

    private static NeoForgeServerLoader instance;
    /** The hooks {@code MinecraftServerMixin} runs (NeoForge has no event with Fabric's meaning for them). */
    private static final List<Consumer<MinecraftServer>> STOPPING = new CopyOnWriteArrayList<>();
    private static final List<Consumer<MinecraftServer>> AFTER_SAVE = new CopyOnWriteArrayList<>();

    /** One PermissionAPI node per {@link Perm}, and the operator level each check falls back to (see there). */
    private final PermissionMapping<ServerPlayer, PermissionNode<Boolean>> permissions;

    private volatile BiConsumer<ServerPlayer, byte[]> c2sHandler;
    private volatile Consumer<byte[]> s2cHandler;

    private NeoForgeServerLoader(IEventBus modBus) {
        modBus.addListener(this::registerPayloads);
        permissions = new PermissionMapping<>(Sculptory.MOD_ID,
                (path, fallback) -> new PermissionNode<>(Sculptory.MOD_ID, path, PermissionTypes.BOOLEAN,
                        (player, uuid, context) -> fallback.test(player)),
                ServerPlayer::hasPermissions);
        NeoForge.EVENT_BUS.addListener((PermissionGatherEvent.Nodes event) ->
                event.addNodes(List.<PermissionNode<?>>copyOf(permissions.nodes())));
    }

    /** Makes the loader (once per JVM) and installs it in {@link Loaders}, with the game internals read through the access transformer
     * ({@link WidenedMcInternals}); idempotent.
     */
    public static synchronized NeoForgeServerLoader install(IEventBus modBus) {
        if (instance == null) instance = new NeoForgeServerLoader(modBus);
        McInternals.install(WidenedMcInternals.INSTANCE);
        Loaders.installServer(instance);
        return instance;
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PAYLOAD_VERSION).optional();
        registrar.playToServer(Frame.C2S_ID, Frame.C2S_CODEC, (frame, context) -> {
            BiConsumer<ServerPlayer, byte[]> handler = c2sHandler;
            if (handler != null && context.player() instanceof ServerPlayer player) handler.accept(player, frame.bytes());
        });
        // Registered here for both sides (NeoForge wants a handler); only a client ever receives it.
        registrar.playToClient(Frame.S2C_ID, Frame.S2C_CODEC, (frame, context) -> {
            Consumer<byte[]> handler = s2cHandler;
            if (handler != null) handler.accept(frame.bytes());
        });
    }

    /** Sets the {@code sculptory:s2c} receiver (the client loader's {@code receiveS2C}). */
    public void receiveS2C(Consumer<byte[]> handler) {
        Objects.requireNonNull(handler);
        if (s2cHandler != null) throw new IllegalStateException("a sculptory:s2c receiver is already registered");
        s2cHandler = handler;
    }

    // ---- LoaderInfo ----

    @Override
    public Path configDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    @Override
    public Path gameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public String modVersion() {
        return modVersionFromModList();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopment() {
        return !FMLEnvironment.production;
    }

    /** The version in the mod's metadata ({@code neoforge.mods.toml}), or {@code "unknown"}. */
    static String modVersionFromModList() {
        return ModList.get().getModContainerById(Sculptory.MOD_ID)
                .map(mod -> mod.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    // ---- lifecycle ----

    @Override
    public void onServerStarting(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ServerAboutToStartEvent event) -> hook.accept(event.getServer()));
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> hook.accept(event.getServer()));
    }

    /** Runs from {@code MinecraftServerMixin} at the head of {@code stopServer} (see the class javadoc). */
    @Override
    public void onServerStopping(Consumer<MinecraftServer> hook) {
        STOPPING.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onServerStopped(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> hook.accept(event.getServer()));
    }

    /** Runs from {@code MinecraftServerMixin} at the tail of {@code saveAllChunks} (see the class javadoc). */
    @Override
    public void onAfterSave(Consumer<MinecraftServer> hook) {
        AFTER_SAVE.add(Objects.requireNonNull(hook));
    }

    /** The head of {@code MinecraftServer.stopServer}: the stopping hooks, in registration order. */
    public static void serverStopping(MinecraftServer server) {
        STOPPING.forEach(hook -> hook.accept(server));
    }

    /** The tail of {@code MinecraftServer.saveAllChunks}: the after-save hooks, in registration order. */
    public static void afterSave(MinecraftServer server) {
        AFTER_SAVE.forEach(hook -> hook.accept(server));
    }

    @Override
    public void onStartServerTick(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Pre event) -> hook.accept(event.getServer()));
    }

    @Override
    public void onStartWorldTick(Consumer<ServerLevel> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((LevelTickEvent.Pre event) -> {
            if (event.getLevel() instanceof ServerLevel level) hook.accept(level);
        });
    }

    @Override
    public void onEndServerTick(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> hook.accept(event.getServer()));
    }

    @Override
    public void onWorldUnload(BiConsumer<MinecraftServer, ServerLevel> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel level) hook.accept(level.getServer(), level);
        });
    }

    @Override
    public void onPlayerJoin(Consumer<ServerPlayer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) hook.accept(player);
        });
    }

    @Override
    public void onPlayerDisconnect(Consumer<ServerPlayer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) hook.accept(player);
        });
    }

    @Override
    public void onCommands(BiConsumer<CommandDispatcher<CommandSourceStack>, CommandBuildContext> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                hook.accept(event.getDispatcher(), event.getBuildContext()));
    }

    // ---- networking ----

    @Override
    public void receiveC2S(BiConsumer<ServerPlayer, byte[]> handler) {
        Objects.requireNonNull(handler);
        if (c2sHandler != null) throw new IllegalStateException("a sculptory:c2s receiver is already registered");
        c2sHandler = handler;
    }

    @Override
    public boolean canSendS2C(ServerPlayer player) {
        return player.connection != null && player.connection.hasChannel(Frame.S2C_ID);
    }

    @Override
    public void sendS2C(ServerPlayer player, byte[] frame) {
        player.connection.send(Frame.s2c(frame));
    }

    // ---- permissions ----

    @Override
    public boolean hasPermission(ServerPlayer player, String node, int fallbackOpLevel) {
        // Throws for an unknown node, and passes on what the PermissionAPI throws (an unregistered node, a failing
        // permissions mod): the caller decides what a failure means.
        return permissions.check(player, node, fallbackOpLevel,
                (target, permission) -> PermissionAPI.getPermission(target, permission));
    }
}
