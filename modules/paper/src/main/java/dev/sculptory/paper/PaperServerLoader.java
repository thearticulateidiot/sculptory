package dev.sculptory.paper;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.ServerLoader;
import dev.sculptory.protocol.v2.Codec;
import io.netty.buffer.Unpooled;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.player.PlayerUnregisterChannelEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.messaging.Messenger;

/**
 * The {@link ServerLoader} on Paper, so the loader-free common code (ServerNet, McPermissionService, folders) runs as
 * on Fabric and NeoForge. Hooks are kept in registration order and run in it, as the common code expects.
 * <ul>
 *   <li>Lifecycle: a Bukkit plugin is enabled after the worlds have loaded and disabled before they are saved and
 *       unloaded, so {@link SculptoryPlugin} fires starting then started from {@code onEnable}, and stopping then
 *       stopped from {@code onDisable}. Each runs once per JVM (Paper runs one server).</li>
 *   <li>Ticks: Paper's {@link ServerTickStartEvent} and {@link ServerTickEndEvent} (the end event comes after the
 *       worlds and the network ticked). Paper has no per-world tick event; {@link #onStartWorldTick} hooks run for
 *       every world at the server tick's start (only the light batch used it, a no-op on Paper).</li>
 *   <li>Join and leave: {@link PlayerJoinEvent} and {@link PlayerQuitEvent}, on the server thread.</li>
 *   <li>Commands: through Paper's {@code LifecycleEvents.COMMANDS}, so they are registered again whenever Paper
 *       rebuilds the command tree (start, {@code /minecraft:reload}); see {@link #registerCommandsOnEveryBuild}.</li>
 *   <li>Network: Bukkit plugin channels {@code sculptory:c2s} (in) and {@code sculptory:s2c} (out). Paper hands
 *       incoming plugin messages to listeners on the server thread. Bukkit's Messenger drops, silently, a plugin
 *       message on a channel the client has not registered, and the Fabric client's hello arrives before its
 *       registration of {@code sculptory:s2c} (the join race, {@link S2CRoute}). So out goes through the Messenger
 *       once the client registered the channel, and before that (or with {@code -Dsculptory.paper.s2c=nms}) as a
 *       vanilla {@link ClientboundCustomPayloadPacket} to a client that has spoken {@code sculptory:c2s}. An editor
 *       that connected before this instance was enabled (a reload) has no session here and never says hello again:
 *       its frames are dropped and the player told to rejoin ({@link ReloadedConnections}).</li>
 *   <li>Permissions: Bukkit's, with the operator level as the fallback for a node nothing has set.</li>
 * </ul>
 */
public final class PaperServerLoader implements ServerLoader, Listener {
    public static final String C2S_CHANNEL = "sculptory:c2s";
    public static final String S2C_CHANNEL = "sculptory:s2c";
    private static final ResourceLocation S2C_ID = ResourceLocation.fromNamespaceAndPath("sculptory", "s2c");

    private final SculptoryPlugin plugin;
    private final boolean nmsSend = "nms".equals(System.getProperty("sculptory.paper.s2c"));

    private final List<Consumer<MinecraftServer>> starting = new CopyOnWriteArrayList<>();
    private final List<Consumer<MinecraftServer>> started = new CopyOnWriteArrayList<>();
    private final List<Consumer<MinecraftServer>> stopping = new CopyOnWriteArrayList<>();
    private final List<Consumer<MinecraftServer>> stopped = new CopyOnWriteArrayList<>();
    private final List<Consumer<MinecraftServer>> afterSave = new CopyOnWriteArrayList<>();
    private final List<Consumer<MinecraftServer>> startTick = new CopyOnWriteArrayList<>();
    private final List<Consumer<ServerLevel>> startWorldTick = new CopyOnWriteArrayList<>();
    private final List<Consumer<MinecraftServer>> endTick = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<MinecraftServer, ServerLevel>> worldUnload = new CopyOnWriteArrayList<>();
    private final List<Consumer<ServerPlayer>> join = new CopyOnWriteArrayList<>();
    private final List<Consumer<ServerPlayer>> disconnect = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<CommandDispatcher<CommandSourceStack>, CommandBuildContext>> commands =
            new CopyOnWriteArrayList<>();
    private final List<BiConsumer<ServerPlayer, byte[]>> receivers = new CopyOnWriteArrayList<>();
    private volatile int commandBuilds;
    /** Play connections that sent a sculptory:c2s frame (weak: a connection is forgotten once it is gone). */
    private final Set<ServerGamePacketListenerImpl> spokeC2S =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** Frames sent per play connection before its client registered sculptory:s2c (weak, as above). */
    private final Map<ServerGamePacketListenerImpl, Integer> unregisteredSends =
            Collections.synchronizedMap(new WeakHashMap<>());
    /** Players whose client registered sculptory:s2c (Bukkit's channel events; removed when they quit). */
    private final Set<UUID> registeredS2C = ConcurrentHashMap.newKeySet();
    /** Editors connected before this instance was enabled (a reload), until they say Hello. */
    private final ReloadedConnections<ServerGamePacketListenerImpl> reloaded = new ReloadedConnections<>();
    /** An after-save run is queued for the next tick ({@link #onWorldSaveStarting}). */
    private boolean afterSaveScheduled;

    PaperServerLoader(SculptoryPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    /** Installs this loader in {@link Loaders} (the common code reads it from there). */
    void install() {
        Loaders.installServer(this);
    }

    /** Registers the event listener and the plugin channels; from {@code onEnable}. */
    void enable() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Messenger messenger = Bukkit.getMessenger();
        messenger.registerIncomingPluginChannel(plugin, C2S_CHANNEL, (channel, player, message) -> {
            ServerPlayer handle = ((CraftPlayer) player).getHandle();
            if (handle.connection != null && !admitted(handle, message)) return;
            if (handle.connection != null) spokeC2S.add(handle.connection);
            for (BiConsumer<ServerPlayer, byte[]> receiver : receivers) receiver.accept(handle, message);
        });
        messenger.registerOutgoingPluginChannel(plugin, S2C_CHANNEL);
        // Players already online (a /reload made this instance): their registrations happened before the listener, and
        // their editors' handshakes were with the instance before this one (ReloadedConnections).
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getListeningPluginChannels().contains(S2C_CHANNEL)) registeredS2C.add(online.getUniqueId());
            ServerPlayer handle = ((CraftPlayer) online).getHandle();
            if (handle.connection != null) reloaded.joinedBefore(handle.connection);
        }
    }

    /**
     * Whether a frame goes on to the networking: not one from an editor that connected before this instance was
     * enabled and has not said {@code Hello} to it ({@link ReloadedConnections}). The first such frame tells the player
     * to rejoin.
     */
    private boolean admitted(ServerPlayer player, byte[] frame) {
        // The common case first: a connection that did not predate this instance; its frame is not looked at here.
        if (!reloaded.tracks(player.connection)) return true;
        switch (reloaded.receive(player.connection, Codec.peekType(frame))) {
            case FORWARD -> {
                return true;
            }
            case DROP_AND_TELL -> {
                Sculptory.LOG.info("Sculptory: {}'s editor connected before Sculptory was enabled again (a reload); "
                        + "what it sends is ignored until they rejoin", player.getGameProfile().getName());
                player.sendSystemMessage(Component.literal("Sculptory was reloaded on this server, so your editor lost "
                        + "its connection to it. Rejoin the server to edit again.").withStyle(ChatFormatting.YELLOW));
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    /**
     * Frames dropped from this player's editor because it connected before this instance was enabled, or -1 when it
     * did not ({@link ReloadedConnections}). The self-test reads it.
     */
    public int droppedSinceReload(ServerPlayer player) {
        return player.connection == null ? -1 : reloaded.dropped(player.connection);
    }

    /**
     * Treats this player's connection as one that predates this instance ({@code true}), as after a reload, or as an
     * ordinary one again ({@code false}). For the self-test only: a reload cannot happen inside its run.
     */
    public void treatAsConnectedBeforeEnable(ServerPlayer player, boolean before) {
        if (player.connection == null) return;
        if (before) {
            reloaded.joinedBefore(player.connection);
        } else {
            reloaded.forget(player.connection);
        }
    }

    /**
     * Unregisters the plugin channels and forgets every hook, so none of the old engine's survive into the instance a
     * {@code /reload} makes (which registers its own). Bukkit drops the plugin's event listeners itself, Paper its
     * lifecycle handlers. Enabling this same instance again is not supported: Paper closes the plugin's class loader on
     * disable, so a class first needed afterwards fails to load ({@code NoClassDefFoundError}).
     */
    void disable() {
        Messenger messenger = Bukkit.getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin);
        messenger.unregisterOutgoingPluginChannel(plugin);
        for (List<?> hooks : List.of(starting, started, stopping, stopped, afterSave, startTick, startWorldTick, endTick,
                worldUnload, join, disconnect, commands, receivers)) {
            hooks.clear();
        }
        spokeC2S.clear();
        unregisteredSends.clear();
        registeredS2C.clear();
        reloaded.clear();
        // Its task was cancelled with the plugin's others.
        afterSaveScheduled = false;
    }

    public boolean sendsThroughNms() {
        return nmsSend;
    }

    // ---- what the plugin fires ----

    void fireStarting(MinecraftServer server) {
        starting.forEach(hook -> hook.accept(server));
    }

    void fireStarted(MinecraftServer server) {
        started.forEach(hook -> hook.accept(server));
    }

    void fireStopping(MinecraftServer server) {
        stopping.forEach(hook -> hook.accept(server));
    }

    void fireStopped(MinecraftServer server) {
        stopped.forEach(hook -> hook.accept(server));
    }

    /**
     * Runs the command hooks whenever Paper builds its command tree: at start and again on every data-pack reload
     * ({@code /minecraft:reload}), which rebuilds the tree without plugin commands registered straight into the
     * dispatcher. The hooks register into a scratch dispatcher; each top-level literal then goes through Paper's
     * registrar, which adds it as {@code sculptory:<name>} plus {@code <name>}. The nodes are Mojang-typed: Paper's
     * {@code CommandSourceStack} is the server's own (it implements Paper's interface), so the cast only renames
     * the type the registrar declares.
     */
    @SuppressWarnings("unchecked")
    void registerCommandsOnEveryBuild() {
        try {
            registerLifecycleCommands();
        } catch (IllegalStateException refused) {
            // Paper takes lifecycle handlers only while plugins are enabled at start or by /reload; a plugin manager
            // loading this after start gets the old route: straight into the dispatcher, which a later
            // /minecraft:reload drops until the server restarts.
            MinecraftServer server = MinecraftServer.getServer();
            CommandBuildContext context = CommandBuildContext.simple(server.registryAccess(),
                    server.getWorldData().enabledFeatures());
            commands.forEach(hook -> hook.accept(server.getCommands().getDispatcher(), context));
            Sculptory.LOG.warn("Sculptory: enabled after start, so its commands are registered directly ({}); a "
                    + "/minecraft:reload removes them until the server restarts", refused.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void registerLifecycleCommands() {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            MinecraftServer server = MinecraftServer.getServer();
            CommandDispatcher<CommandSourceStack> scratch = new CommandDispatcher<>();
            CommandBuildContext context = CommandBuildContext.simple(server.registryAccess(),
                    server.getWorldData().enabledFeatures());
            commands.forEach(hook -> hook.accept(scratch, context));
            for (CommandNode<CommandSourceStack> node : scratch.getRoot().getChildren()) {
                if (!(node instanceof LiteralCommandNode<CommandSourceStack> literal)) continue;
                var paperTyped = (LiteralCommandNode<io.papermc.paper.command.brigadier.CommandSourceStack>)
                        (LiteralCommandNode<?>) literal;
                event.registrar().register(paperTyped, "Sculptory", List.of());
            }
            commandBuilds++;
        });
    }

    /** How many times Paper has built the command tree with ours in it (start, then once per reload). */
    public int commandBuilds() {
        return commandBuilds;
    }

    // ---- Bukkit events ----

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickStart(ServerTickStartEvent event) {
        MinecraftServer server = MinecraftServer.getServer();
        startTick.forEach(hook -> hook.accept(server));
        if (!startWorldTick.isEmpty()) {
            for (ServerLevel world : server.getAllLevels()) startWorldTick.forEach(hook -> hook.accept(world));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        MinecraftServer server = MinecraftServer.getServer();
        endTick.forEach(hook -> hook.accept(server));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        join.forEach(hook -> hook.accept(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        ServerPlayer player = ((CraftPlayer) event.getPlayer()).getHandle();
        try {
            disconnect.forEach(hook -> hook.accept(player));
        } finally {
            registeredS2C.remove(event.getPlayer().getUniqueId());
        }
    }

    /** The client registered a channel ({@code minecraft:register}): noted for {@code sculptory:s2c} only. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRegisterChannel(PlayerRegisterChannelEvent event) {
        if (S2C_CHANNEL.equals(event.getChannel())) registeredS2C.add(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnregisterChannel(PlayerUnregisterChannelEvent event) {
        if (S2C_CHANNEL.equals(event.getChannel())) registeredS2C.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Bukkit fires {@link WorldSaveEvent} per world before a full save ({@code ServerLevel.save}: {@code save-all},
     * which writes the chunks in that same call) and before Paper's periodic save ({@code saveIncrementally}, which
     * writes the level data only; Moonrise's incremental chunk autosave fires nothing). {@link #onAfterSave} means after,
     * so its hooks run on the next tick, once however many worlds were saved: either save is done within the tick that
     * fired the event.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSaveStarting(WorldSaveEvent event) {
        if (afterSaveScheduled || afterSave.isEmpty()) return;
        afterSaveScheduled = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            afterSaveScheduled = false;
            MinecraftServer server = MinecraftServer.getServer();
            afterSave.forEach(hook -> hook.accept(server));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (event.isCancelled()) return;
        MinecraftServer server = MinecraftServer.getServer();
        ServerLevel world = ((CraftWorld) event.getWorld()).getHandle();
        worldUnload.forEach(hook -> hook.accept(server, world));
    }

    // ---- LoaderInfo ----

    /**
     * The plugin's data folder, {@code plugins/Sculptory}: the one folder of the plugin's own files (server.json, the
     * library, the self-test report; PaperEditHost uses it directly, not the loaders' FolderMigration). Undo history
     * stays with each world ({@code <world>/sculptory/history}), as on Fabric and NeoForge.
     */
    @Override
    public Path configDir() {
        return plugin.getDataFolder().toPath().toAbsolutePath();
    }

    /** The data folder too ({@link #configDir}). */
    @Override
    public Path gameDir() {
        return configDir();
    }

    @Override
    public String modVersion() {
        return plugin.getPluginMeta().getVersion();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return Bukkit.getPluginManager().getPlugin(modId) != null;
    }

    @Override
    public boolean isDevelopment() {
        return Boolean.getBoolean("sculptory.dev");
    }

    // ---- ServerLoader hooks ----

    @Override
    public void onServerStarting(Consumer<MinecraftServer> hook) {
        starting.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onServerStarted(Consumer<MinecraftServer> hook) {
        started.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onServerStopping(Consumer<MinecraftServer> hook) {
        stopping.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onServerStopped(Consumer<MinecraftServer> hook) {
        stopped.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onAfterSave(Consumer<MinecraftServer> hook) {
        afterSave.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onStartServerTick(Consumer<MinecraftServer> hook) {
        startTick.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onStartWorldTick(Consumer<ServerLevel> hook) {
        startWorldTick.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onEndServerTick(Consumer<MinecraftServer> hook) {
        endTick.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onWorldUnload(BiConsumer<MinecraftServer, ServerLevel> hook) {
        worldUnload.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onPlayerJoin(Consumer<ServerPlayer> hook) {
        join.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onPlayerDisconnect(Consumer<ServerPlayer> hook) {
        disconnect.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onCommands(BiConsumer<CommandDispatcher<CommandSourceStack>, CommandBuildContext> hook) {
        commands.add(Objects.requireNonNull(hook));
    }

    // ---- networking ----

    @Override
    public void receiveC2S(BiConsumer<ServerPlayer, byte[]> handler) {
        receivers.add(Objects.requireNonNull(handler));
    }

    /**
     * The client registered {@code sculptory:s2c} ({@code minecraft:register}, which Fabric API sends for its
     * receivers), or it has sent a {@code sculptory:c2s} frame on this connection. The second is needed because of the
     * join race {@link S2CRoute} describes: the hello arrives before the client's registration, and Bukkit's Messenger
     * drops plugin messages on unregistered channels without a word, so the Welcome must not wait for it.
     */
    @Override
    public boolean canSendS2C(ServerPlayer player) {
        return route(player) != S2CRoute.NONE;
    }

    /**
     * Through the Messenger once the client registered the channel; before that (the join race, {@link S2CRoute}), with
     * {@code -Dsculptory.paper.s2c=nms}, or while the plugin is being disabled (Bukkit marks it disabled before
     * {@code onDisable} runs, and the Messenger then refuses every send with "Plugin must be enabled"), as a vanilla
     * custom-payload packet. Frames sent unregistered are counted per connection ({@link #sentBeforeRegistration}).
     */
    @Override
    public void sendS2C(ServerPlayer player, byte[] frame) {
        boolean registered = registered(player);
        S2CRoute route = route(player, registered);
        switch (route) {
            case MESSENGER -> player.getBukkitEntity().sendPluginMessage(plugin, S2C_CHANNEL, frame);
            case PACKET -> {
                if (!registered && player.connection != null) {
                    unregisteredSends.merge(player.connection, 1, Integer::sum);
                }
                sendThroughNms(player, frame);
            }
            case NONE -> {
                // The client gave no sign of the mod; the dispatcher asks canSendS2C first, so this is not reached.
            }
        }
    }

    /** The route {@link #sendS2C} takes for this player now. */
    public S2CRoute route(ServerPlayer player) {
        return route(player, registered(player));
    }

    private S2CRoute route(ServerPlayer player, boolean registered) {
        boolean spoke = player.connection != null && spokeC2S.contains(player.connection);
        return S2CRoute.of(registered, spoke, nmsSend || !plugin.isEnabled());
    }

    /**
     * Frames sent to this player's connection before its client registered {@code sculptory:s2c}: at least one (the
     * Welcome) whenever the join race happened. The self-test reports it.
     */
    public int sentBeforeRegistration(ServerPlayer player) {
        return player.connection == null ? 0 : unregisteredSends.getOrDefault(player.connection, 0);
    }

    /**
     * Whether the client registered {@code sculptory:s2c}: kept from Bukkit's channel events (and read once for
     * players online when the plugin is enabled), since {@code getListeningPluginChannels} copies the channel set on
     * every call.
     */
    private boolean registered(ServerPlayer player) {
        return registeredS2C.contains(player.getUUID());
    }

    /**
     * The frame as a vanilla clientbound custom payload, past Bukkit's Messenger (its size check and its "the client
     * registered the channel" check): what CraftPlayer itself builds for a plugin message.
     */
    public static void sendThroughNms(ServerPlayer player, byte[] frame) {
        if (player.connection == null) return;
        DiscardedPayload payload = new DiscardedPayload(S2C_ID, Unpooled.wrappedBuffer(frame));
        player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }

    // ---- permissions ----

    /**
     * A node something set for the player (a permissions plugin, or {@code plugin.yml} defaults made explicit by one)
     * decides; otherwise the operator level does, as fabric-permissions-api falls back on Fabric.
     */
    @Override
    public boolean hasPermission(ServerPlayer player, String node, int fallbackOpLevel) {
        Player bukkit = player.getBukkitEntity();
        if (bukkit.isPermissionSet(node)) return bukkit.hasPermission(node);
        return player.hasPermissions(fallbackOpLevel);
    }
}
