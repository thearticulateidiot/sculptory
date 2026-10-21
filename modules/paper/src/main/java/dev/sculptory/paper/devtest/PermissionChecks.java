package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.ticks;

import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.mc.perm.Tri;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.perm.PaperPermissions;
import dev.sculptory.server.engine.Perm;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.permissions.PermissionAttachment;

/**
 * Permissions on Paper: the three-valued node answers (set true, set false, unset: the operator-level fallback), and a
 * change applied at once when Paper sends the command tree again (PermissionWatch): the player's permissions are read
 * again for their client. LuckPerms' own event is reported when LuckPerms is installed.
 */
final class PermissionChecks {
    private PermissionChecks() {
    }

    static CompletableFuture<Void> run(SelfTestRun r) {
        PaperPermissions permissions = r.host.engine().permissions();
        // The fake player's own grants (two attachments setting one node would leave the answer to their order), or a
        // new attachment for a real player, removed at the end of the run.
        PermissionAttachment grants = r.attachment != null ? r.attachment
                : r.player.getBukkitEntity().addAttachment(SculptoryPlugin.instance());
        if (r.attachment == null) r.cleanup(grants::remove);
        grants.setPermission(Perm.BUILDER.node(), true);
        Tri granted = permissions.check(r.player, Perm.BUILDER);
        grants.setPermission(Perm.BUILDER.node(), false);
        Tri refused = permissions.check(r.player, Perm.BUILDER);
        boolean denied = permissions.denied(r.player, Perm.BUILDER);
        grants.unsetPermission(Perm.BUILDER.node());
        boolean unsetAnswer = permissions.has(r.player, Perm.BUILDER);
        boolean unsetSet = r.player.getBukkitEntity().isPermissionSet(Perm.BUILDER.node());
        boolean fallback = r.player.hasPermissions(r.host.engine().config().permissionFallbackOpLevel);
        // The fake player gets its grant back for the checks after these.
        if (r.attachment != null) grants.setPermission(Perm.BUILDER.node(), true);
        r.record("permissions: a node set true or false is TRUE or FALSE", granted == Tri.TRUE && refused == Tri.FALSE
                && denied, "set true " + granted + ", set false " + refused + " (denied " + denied + ")");
        r.record("permissions: unset, the node falls back to the operator level", unsetSet || unsetAnswer == fallback,
                unsetSet ? "set elsewhere (a permissions plugin)" : "has " + unsetAnswer + ", operator level gives "
                        + fallback);

        List<ServerPlayer> seen = new ArrayList<>();
        ServerNet.observePermissionChecks(seen::add);
        r.cleanup(() -> ServerNet.observePermissionChecks(null));
        if (r.player.connection != null) {
            // A real client: Paper builds and sends the tree, then fires the event on the server thread.
            r.player.getBukkitEntity().updateCommands();
        } else {
            Bukkit.getPluginManager().callEvent(new PlayerCommandSendEvent(r.player.getBukkitEntity(),
                    new LinkedHashSet<>()));
        }
        return ticks(20).thenAccept(v -> {
            r.record("permissions: a command tree sent again re-reads the player's permissions at once",
                    seen.contains(r.player), seen.size() + " re-reads seen for " + r.name());
            r.info("permissions: LuckPerms", Bukkit.getPluginManager().isPluginEnabled("LuckPerms")
                    ? "installed: its recalculation event re-reads permissions at once"
                    : "not installed: the command tree and the engine's two-second re-checks apply changes");
        });
    }
}
