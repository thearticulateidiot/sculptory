package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.ticks;

import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.paper.PaperServerLoader;
import dev.sculptory.paper.S2CRoute;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.protocol.v2.ProtocolException;
import java.util.concurrent.CompletableFuture;
import org.bukkit.craftbukkit.entity.CraftPlayer;

/** The network with a real Sculptory client (runs only for a named, connected player). */
final class NetChecks {
    private NetChecks() {
    }

    /**
     * The join race ({@code S2CRoute}): the Fabric client's hello arrives before its registration of
     * {@code sculptory:s2c}, and the Messenger would drop the Welcome. First reports whether this player's own join hit
     * the race (frames sent before the registration); then reproduces it: the client's registration is taken away on the
     * server, as if it had not arrived yet, a harmless frame (a chunk of a stream the client never opened, which its
     * session ignores) is sent through the loader, and it must have gone out as a vanilla packet; the registration is
     * then given back, and the client must still be connected a second later.
     */
    static CompletableFuture<Void> joinRace(SelfTestRun r) {
        PaperServerLoader loader = SculptoryPlugin.instance().loader();
        if (r.attachment != null || r.player.connection == null) {
            r.info("network: join race", "skipped: needs a connected Sculptory client (/sculptory selftest <player>)");
            return CompletableFuture.completedFuture(null);
        }
        if (!ServerNet.isReady(r.player)) {
            r.record("network: join race: the handshake completed", false, r.name() + " has no ready Sculptory session");
            return CompletableFuture.completedFuture(null);
        }
        int atJoin = loader.sentBeforeRegistration(r.player);
        r.info("network: join race on this join", atJoin > 0
                ? atJoin + " frame(s), the Welcome first, went out before the client registered sculptory:s2c"
                : "the client's registration arrived before the Welcome this time");
        CraftPlayer bukkit = r.player.getBukkitEntity();
        int before = loader.sentBeforeRegistration(r.player);
        S2CRoute route;
        boolean canSend;
        bukkit.removeChannel(PaperServerLoader.S2C_CHANNEL);
        try {
            route = loader.route(r.player);
            canSend = loader.canSendS2C(r.player);
            loader.sendS2C(r.player, NetTest.frameOf(64));
        } catch (ProtocolException e) {
            return CompletableFuture.failedFuture(e);
        } finally {
            if (!bukkit.getListeningPluginChannels().contains(PaperServerLoader.S2C_CHANNEL)) {
                bukkit.addChannel(PaperServerLoader.S2C_CHANNEL);
            }
        }
        int sent = loader.sentBeforeRegistration(r.player) - before;
        r.record("network: join race: a frame before the registration goes as a vanilla packet",
                canSend && route == S2CRoute.PACKET && sent == 1, "canSend=" + canSend + ", route=" + route
                        + ", counted " + sent);
        return ticks(20).thenAccept(v -> r.record("network: join race: the client stays connected",
                r.player.connection != null && !r.player.hasDisconnected()
                        && bukkit.getListeningPluginChannels().contains(PaperServerLoader.S2C_CHANNEL),
                "connected=" + !r.player.hasDisconnected() + ", registration back="
                        + bukkit.getListeningPluginChannels().contains(PaperServerLoader.S2C_CHANNEL)));
    }
}
