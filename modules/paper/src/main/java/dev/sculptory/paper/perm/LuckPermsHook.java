package dev.sculptory.paper.perm;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import org.bukkit.plugin.Plugin;

/**
 * LuckPerms' {@code UserDataRecalculateEvent} (a user's permissions were recalculated: a node granted or taken, a group
 * changed, a context changed) applied to the online player ({@link PermissionWatch#recheckSoon}). LuckPerms fires its
 * events on its own threads, so the check hops onto the server thread. Named only when LuckPerms is enabled (a soft
 * dependency); the subscription goes when this plugin is disabled (LuckPerms drops a plugin's handlers then).
 */
final class LuckPermsHook {
    private LuckPermsHook() {
    }

    static void register(Plugin plugin, PermissionWatch watch) {
        LuckPerms luckPerms = LuckPermsProvider.get();
        luckPerms.getEventBus().subscribe(plugin, UserDataRecalculateEvent.class,
                event -> watch.recheckSoon(event.getUser().getUniqueId()));
    }
}
