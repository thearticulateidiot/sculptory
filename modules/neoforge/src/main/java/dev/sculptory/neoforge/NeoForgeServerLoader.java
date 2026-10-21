package dev.sculptory.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.ServerLoader;
import dev.sculptory.mc.net.Frame;
import dev.sculptory.server.engine.Perm;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * The {@link ServerLoader} on NeoForge: each hook is one listener on NeoForge's game bus ({@link NeoForge#EVENT_BUS}),
 * registered at the default priority when the hook is, so hooks run in the order they were registered (the engine
 * relies on it, see {@code EngineRuntime.install}).
 * <ul>
 *   <li>{@link #onServerStarting}: {@code ServerAboutToStartEvent}, which NeoForge fires in {@code initServer} just
 *       before the worlds load ({@code ServerStartingEvent} comes after they loaded). Fabric's {@code SERVER_STARTING}
 *       fires a little earlier, before {@code initServer}; on a dedicated server the player list exists here and not
 *       there, which nothing in the hooks reads;</li>
 *   <li>{@link #onServerStarted}, {@link #onServerStopped}: {@code ServerStartedEvent}, {@code ServerStoppedEvent}, at
 *       the same places as Fabric's;</li>
 *   <li>{@link #onServerStopping}: {@code ServerStoppingEvent}, when the tick loop ends normally. Unlike Fabric's
 *       {@code SERVER_STOPPING} (the head of {@code stopServer}) it does not fire when the tick loop crashed: then only
 *       {@link #onServerStopped} runs;</li>
 *   <li>{@link #onAfterSave}: NeoForge has no event for a finished full save. {@code LevelEvent.Save} fires once per
 *       world inside {@code saveAllChunks}; the hook runs after the last world's, while the server is running (so
 *       autosave and {@code /save-all}, like Fabric's {@code AFTER_SAVE} on {@code saveEverything}, and not the save
 *       during shutdown). A world saved with {@code /save-off} fires no event;</li>
 *   <li>{@link #onStartServerTick}, {@link #onEndServerTick}: {@code ServerTickEvent.Pre} and {@code .Post}, at the
 *       start and end of {@code tickServer} (Pre after the tick counter went up, Fabric's before);</li>
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
 *       NeoForge's negotiated channels ({@code hasChannel}, which also knows channels another loader announced with
 *       {@code minecraft:register});</li>
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

    /**
     * The operator level the check in progress on this thread falls back to: {@link #hasPermission} sets it around
     * {@code PermissionAPI.getPermission}, whose default resolver reads it (a permissions mod asks the resolver only
     * when it has no answer itself, on the same call).
     */
    private static final ThreadLocal<Integer> FALLBACK_OP_LEVEL = new ThreadLocal<>();
    /** One permission node per {@link Perm}, by node name. */
    private final Map<String, PermissionNode<Boolean>> nodes = new HashMap<>();

    private volatile BiConsumer<ServerPlayer, byte[]> c2sHandler;
    private volatile Consumer<byte[]> s2cHandler;

    private NeoForgeServerLoader(IEventBus modBus) {
        modBus.addListener(this::registerPayloads);
        for (Perm perm : Perm.values()) {
            String name = perm.node();
            String prefix = Sculptory.MOD_ID + ".";
            if (!name.startsWith(prefix)) throw new IllegalStateException("permission node outside the mod: " + name);
            nodes.put(name, new PermissionNode<>(Sculptory.MOD_ID, name.substring(prefix.length()),
                    PermissionTypes.BOOLEAN, (player, uuid, context) -> {
                        Integer level = FALLBACK_OP_LEVEL.get();
                        // An offline player (no ServerPlayer) has no operator level to compare.
                        return player != null && level != null && player.hasPermissions(level);
                    }));
        }
        NeoForge.EVENT_BUS.addListener((PermissionGatherEvent.Nodes event) ->
                event.addNodes(List.<PermissionNode<?>>copyOf(nodes.values())));
    }

    /** Makes the loader (once per JVM) and installs it in {@link Loaders}; idempotent. */
    public static synchronized NeoForgeServerLoader install(IEventBus modBus) {
        if (instance == null) instance = new NeoForgeServerLoader(modBus);
        Loaders.installServer(instance);
        return instance;
    }

    /** The installed loader (the client loader hands it the {@code sculptory:s2c} receiver). */
    static NeoForgeServerLoader instance() {
        NeoForgeServerLoader loader = instance;
        if (loader == null) throw new IllegalStateException("the NeoForge server loader is not installed");
        return loader;
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

    @Override
    public void onServerStopping(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> hook.accept(event.getServer()));
    }

    @Override
    public void onServerStopped(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> hook.accept(event.getServer()));
    }

    @Override
    public void onAfterSave(Consumer<MinecraftServer> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((LevelEvent.Save event) -> {
            if (!(event.getLevel() instanceof ServerLevel level)) return;
            MinecraftServer server = level.getServer();
            if (server.isRunning() && isLastLevel(server, level)) hook.accept(server);
        });
    }

    /** Whether {@code level} is the last world {@code saveAllChunks} saves (it saves them in this order). */
    private static boolean isLastLevel(MinecraftServer server, ServerLevel level) {
        ServerLevel last = null;
        for (Iterator<ServerLevel> it = server.getAllLevels().iterator(); it.hasNext(); ) {
            last = it.next();
        }
        return last == level;
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
        PermissionNode<Boolean> permission = nodes.get(node);
        if (permission == null) {
            throw new IllegalArgumentException("not a registered Sculptory permission node: " + node);
        }
        Integer outer = FALLBACK_OP_LEVEL.get();
        FALLBACK_OP_LEVEL.set(fallbackOpLevel);
        try {
            return Boolean.TRUE.equals(PermissionAPI.getPermission(player, permission));
        } finally {
            if (outer == null) {
                FALLBACK_OP_LEVEL.remove();
            } else {
                FALLBACK_OP_LEVEL.set(outer);
            }
        }
    }
}
