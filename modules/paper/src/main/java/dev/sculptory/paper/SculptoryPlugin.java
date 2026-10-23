package dev.sculptory.paper;

import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.net.ServerNet;
import dev.sculptory.mc.world.McInternals;
import dev.sculptory.paper.command.PaperCommands;
import dev.sculptory.paper.engine.PaperEditHost;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.paper.perm.SculptoryProtection;
import dev.sculptory.protocol.v2.ProtocolV2;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sculptory on Paper. A Bukkit plugin: Paper enables it after the worlds have loaded and disables it before
 * they are saved, which is where the engine starts and stops ({@link PaperServerLoader} turns that into the loader
 * seam's lifecycle hooks for the common code).
 */
public final class SculptoryPlugin extends JavaPlugin {
    private static volatile SculptoryPlugin instance;
    private PaperServerLoader loader;
    private volatile PaperEditHost host;
    /** The dev jar's self-test (empty in the release jar). */
    private final Optional<DevExtras> extras = DevExtras.find();
    /** Why the plugin stays disabled on this server (another Minecraft version), or empty. */
    private Optional<String> refusal = Optional.empty();

    /** The loader seam this plugin installed (the self-test asks it about the network). */
    public PaperServerLoader loader() {
        return loader;
    }

    public static SculptoryPlugin instance() {
        SculptoryPlugin plugin = instance;
        if (plugin == null) throw new IllegalStateException("Sculptory is not loaded");
        return plugin;
    }

    @Override
    public void onLoad() {
        instance = this;
        // Before anything touches the server's internals: on another Minecraft version they may not be what this jar
        // was compiled against (MinecraftVersionGuard), so nothing more is set up there.
        refusal = MinecraftVersionGuard.refusalFor(MinecraftVersionGuard::builtFor, Bukkit::getMinecraftVersion);
        if (refusal.isPresent()) {
            Sculptory.LOG.error(refusal.get());
            return;
        }
        // Before any common code runs: the game internals it reads, Paper's way.
        McInternals.install(PaperAccess.INSTANCE);
        loader = new PaperServerLoader(this);
        loader.install();
    }

    @Override
    public void onEnable() {
        if (refusal.isPresent() || loader == null) {
            // The refusal was logged when the server loaded the plugin; only an unexpected state is logged here.
            if (refusal.isEmpty()) {
                Sculptory.LOG.error("Sculptory for Paper was not set up when the server loaded it, so it stays disabled");
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MinecraftServer server = MinecraftServer.getServer();
        Sculptory.LOG.info("Sculptory for Paper {} enabling (engine {}, protocol {}, s2c through {})",
                Sculptory.productBuildId(), Sculptory.buildId(), ProtocolV2.VERSION,
                loader.sendsThroughNms() ? "NMS packets" : "the Bukkit Messenger");
        loader.enable();
        loader.fireStarting(server);
        host = PaperEditHost.start(this, server, loader, SculptoryProtection.detect());
        PaperCommands commands = new PaperCommands(() -> host, extras);
        loader.onCommands(commands::register);
        loader.registerCommandsOnEveryBuild();
        loader.fireStarted(server);
        extras.ifPresent(dev -> dev.started(host));
    }

    @Override
    public void onDisable() {
        if (refusal.isPresent()) return; // nothing was set up (another Minecraft version, or it could not be checked)
        // A self-test in progress puts its test areas back while the plugin can still write (dev only).
        extras.ifPresent(DevExtras::disabling);
        MinecraftServer server = MinecraftServer.getServer();
        if (loader != null) {
            // Each step runs even when one before it throws: the stop hooks close the history store, and the loader
            // must forget its hooks (a /reload makes a fresh instance; enabling this one again is not supported: Paper
            // closes its class loader on disable).
            try {
                loader.fireStopping(server);
            } finally {
                try {
                    loader.fireStopped(server);
                } finally {
                    // After ServerNet's own stopped hook closed its sessions: a /reload loads the plugin again in this
                    // JVM, and its start installs the networking anew.
                    ServerNet.uninstall();
                    loader.disable();
                }
            }
        }
        host = null;
    }
}
