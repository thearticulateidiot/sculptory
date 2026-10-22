package dev.sculptory.paper.perm;

import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.engine.impl.ServerScatter;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Permission changes of online players applied as they happen on Paper, what Fabric's {@code PlayerManagerMixin} does:
 * the player's Sculptory permissions are read again and pushed to their client ({@link ServerNet#permissionsChanged}),
 * and their admitted jobs and scatter preview are checked against them ({@link EngineEditService#revalidate}).
 * <ul>
 *   <li>{@link PlayerCommandSendEvent}: Paper sends the command tree again whenever a player's rights may have changed
 *       (joining, respawning, changing world, {@code /op} and {@code /deop}, a plugin's {@code updateCommands()}, which
 *       LuckPerms calls after a change by default). The jobs are checked in the player's current world, since a
 *       permissions plugin may grant a node in one world only; after an op change (the player's op status differs from
 *       when it was last seen), which holds in every world, in every world. Paper builds the tree off the server thread
 *       and fires the event back on it later, so it may come for a player who has quit meanwhile: then nothing is
 *       checked and the player's op entry is dropped. With {@code tab-complete: -1} in spigot.yml Paper sends an empty
 *       tree and never fires this event: {@code /op} and {@code /deop} then apply at the periodic re-checks below
 *       (within {@value EngineEditService#PERMISSION_RECHECK_TICKS} ticks), unless LuckPerms reports them.</li>
 *   <li>LuckPerms' {@code UserDataRecalculateEvent}, when LuckPerms is installed ({@link LuckPermsHook}).</li>
 *   <li>Otherwise the engine's own periodic re-checks catch a change within two seconds: the dispatcher re-reads every
 *       session's permissions and the edit service re-checks its job owners every
 *       {@value EngineEditService#PERMISSION_RECHECK_TICKS} ticks.</li>
 * </ul>
 * The nodes are Bukkit's, three-valued as on Fabric ({@code PaperServerLoader.hasPermission}): set true, set false, or
 * unset (the operator level decides); a permissions backend that throws grants nothing new and cancels nothing.
 */
public final class PermissionWatch implements Listener {
    private final EngineEditService<ServerPlayer, ServerLevel> service;
    private final ServerScatter<ServerPlayer, ServerLevel> scatter;
    /**
     * Each online player's op status when last checked, with the session (the CraftPlayer, new at each join) it was
     * seen for: a late event for an earlier session of the same player never touches the current one's entry.
     */
    private final Map<UUID, Seen> ops = new ConcurrentHashMap<>();
    private final Plugin plugin;

    private record Seen(Player session, boolean op) {
    }

    private PermissionWatch(Plugin plugin, EngineEditService<ServerPlayer, ServerLevel> service,
                            ServerScatter<ServerPlayer, ServerLevel> scatter) {
        this.plugin = Objects.requireNonNull(plugin);
        this.service = Objects.requireNonNull(service);
        this.scatter = Objects.requireNonNull(scatter);
    }

    /** Registers the listener, and the LuckPerms hook when LuckPerms is enabled. */
    public static PermissionWatch register(Plugin plugin, EngineEditService<ServerPlayer, ServerLevel> service,
                                           ServerScatter<ServerPlayer, ServerLevel> scatter) {
        PermissionWatch watch = new PermissionWatch(plugin, service, scatter);
        Bukkit.getPluginManager().registerEvents(watch, plugin);
        for (Player online : Bukkit.getOnlinePlayers()) watch.ops.put(online.getUniqueId(), new Seen(online, online.isOp()));
        if (Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            try {
                LuckPermsHook.register(plugin, watch);
                Sculptory.LOG.info("Sculptory: LuckPerms permission changes apply at once");
            } catch (LinkageError | RuntimeException e) {
                // Another LuckPerms API than 5.x: the command-tree event and the periodic re-checks still catch changes.
                Sculptory.LOG.warn("Sculptory: LuckPerms' events could not be used ({}); permission changes apply when "
                        + "the command tree is sent again or within two seconds", e.toString());
            }
        }
        return watch;
    }

    /** Monitor: the tree is being sent, so the rights it was built from are the ones to check against. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void commandsSent(PlayerCommandSendEvent event) {
        recheck(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        forget(event.getPlayer());
    }

    /** Drops {@code session}'s op entry, unless the player has joined again since (the entry is the new session's). */
    private void forget(Player session) {
        ops.computeIfPresent(session.getUniqueId(), (id, seen) -> seen.session() == session ? null : seen);
    }

    /** Re-checks {@code player} on the server thread (now when on it, else on the next tick). */
    void recheckSoon(UUID id) {
        if (Bukkit.isPrimaryThread()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) recheck(player);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null) recheck(player);
        });
    }

    /** Applies the player's current rights: their client's permissions, their jobs and their scatter preview. */
    void recheck(Player bukkit) {
        ServerPlayer player = ((CraftPlayer) bukkit).getHandle();
        if (player.hasDisconnected()) {
            // A late event for a player who has quit (the command tree is built off the thread): nothing to apply, and
            // no op entry may outlive them.
            forget(bukkit);
            return;
        }
        boolean op = bukkit.isOp();
        Seen was = ops.put(bukkit.getUniqueId(), new Seen(bukkit, op));
        // An op change of this session holds in every world; a new session starts from what it has.
        boolean allWorlds = was != null && was.session() == bukkit && was.op() != op;
        // Each on its own: a failure in one must not skip the others.
        try {
            ServerNet.permissionsChanged(player);
        } catch (RuntimeException | LinkageError e) {
            Sculptory.LOG.error("Sculptory: pushing the editor permissions failed", e);
        }
        try {
            service.revalidate(player, allWorlds);
            scatter.revalidate(player);
        } catch (RuntimeException | LinkageError e) {
            Sculptory.LOG.error("Sculptory: re-checking {}'s editor jobs failed", bukkit.getName(), e);
        }
    }
}
