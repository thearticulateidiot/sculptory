package dev.sculptory.paper;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import com.mojang.brigadier.CommandDispatcher;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.ServerLoader;
import io.netty.buffer.Unpooled;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
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
 *   <li>Commands: registered straight into the server's Brigadier dispatcher (Mojang-named; Paper bridges it to
 *       Bukkit's command map), once, when the plugin is enabled.</li>
 *   <li>Network: Bukkit plugin channels {@code sculptory:c2s} (in) and {@code sculptory:s2c} (out). Paper hands
 *       incoming plugin messages to listeners on the server thread. Out goes through the Messenger once the client
 *       registered the channel (else, or with {@code -Dsculptory.paper.s2c=nms}, straight as a vanilla
 *       {@link ClientboundCustomPayloadPacket}).</li>
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
    /** Play connections that sent a sculptory:c2s frame (weak: a connection is forgotten once it is gone). */
    private final Set<ServerGamePacketListenerImpl> spokeC2S =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

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
            if (handle.connection != null) spokeC2S.add(handle.connection);
            for (BiConsumer<ServerPlayer, byte[]> receiver : receivers) receiver.accept(handle, message);
        });
        messenger.registerOutgoingPluginChannel(plugin, S2C_CHANNEL);
    }

    void disable() {
        Messenger messenger = Bukkit.getMessenger();
        messenger.unregisterIncomingPluginChannel(plugin);
        messenger.unregisterOutgoingPluginChannel(plugin);
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

    void fireCommands(MinecraftServer server) {
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandBuildContext context = CommandBuildContext.simple(server.registryAccess(),
                server.getWorldData().enabledFeatures());
        commands.forEach(hook -> hook.accept(dispatcher, context));
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
        disconnect.forEach(hook -> hook.accept(player));
    }

    /** Bukkit fires this per world before it saves; the common hook wants "after a save": close enough for now. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        MinecraftServer server = MinecraftServer.getServer();
        afterSave.forEach(hook -> hook.accept(server));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        if (event.isCancelled()) return;
        MinecraftServer server = MinecraftServer.getServer();
        ServerLevel world = ((CraftWorld) event.getWorld()).getHandle();
        worldUnload.forEach(hook -> hook.accept(server, world));
    }

    // ---- LoaderInfo ----

    /** The plugins folder: the common code's config lives in {@code plugins/sculptory/} (FolderMigration). */
    @Override
    public Path configDir() {
        return plugin.getDataFolder().toPath().toAbsolutePath().getParent();
    }

    /** The plugins folder too, so the library is {@code plugins/sculptory/library/}. */
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
     * receivers), or it has sent a {@code sculptory:c2s} frame on this connection. The second is needed: the Fabric
     * client sends its hello as soon as it sees the server's channels, before its own registration of
     * {@code sculptory:s2c} reaches Paper (seen on Paper 1.21.1 build 133), so the Welcome would otherwise be dropped
     * and the client would give up ("no handshake answer"). A client that speaks the protocol has the mod.
     */
    @Override
    public boolean canSendS2C(ServerPlayer player) {
        return registered(player) || (player.connection != null && spokeC2S.contains(player.connection));
    }

    private static boolean registered(ServerPlayer player) {
        return player.getBukkitEntity().getListeningPluginChannels().contains(S2C_CHANNEL);
    }

    /**
     * Through the Messenger when the client registered the channel (CraftPlayer silently drops a plugin message on a
     * channel the client did not register), else, or with {@code -Dsculptory.paper.s2c=nms}, as a vanilla packet.
     */
    @Override
    public void sendS2C(ServerPlayer player, byte[] frame) {
        if (!nmsSend && registered(player)) {
            player.getBukkitEntity().sendPluginMessage(plugin, S2C_CHANNEL, frame);
        } else {
            sendThroughNms(player, frame);
        }
    }

    /**
     * The frame as a vanilla clientbound custom payload, past Bukkit's Messenger (its size check and its "the client
     * registered the channel" check): what CraftPlayer itself builds for a plugin message.
     */
    public static void sendThroughNms(ServerPlayer player, byte[] frame) {
        if (player.connection == null) return;
        player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(S2C_ID, Unpooled.wrappedBuffer(frame))));
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
