package dev.sculptory.paper;

import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.world.McInternals;
import dev.sculptory.paper.command.PaperCommands;
import dev.sculptory.paper.devtest.SelfTest;
import dev.sculptory.paper.engine.PaperEditHost;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.paper.perm.SculptoryProtection;
import dev.sculptory.protocol.v2.ProtocolV2;
import net.minecraft.server.MinecraftServer;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sculptory on Paper (spike). A Bukkit plugin: Paper enables it after the worlds have loaded and disables it before
 * they are saved, which is where the engine starts and stops ({@link PaperServerLoader} turns that into the loader
 * seam's lifecycle hooks for the common code).
 */
public final class SculptoryPlugin extends JavaPlugin {
    private static volatile SculptoryPlugin instance;
    private PaperServerLoader loader;
    private volatile PaperEditHost host;

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
        // Before any common code runs: the game internals it reads, Paper's way.
        McInternals.install(PaperAccess.INSTANCE);
        loader = new PaperServerLoader(this);
        loader.install();
    }

    @Override
    public void onEnable() {
        MinecraftServer server = MinecraftServer.getServer();
        Sculptory.LOG.info("Sculptory for Paper {} enabling (engine {}, protocol {}, s2c through {})",
                Sculptory.productBuildId(), Sculptory.buildId(), ProtocolV2.VERSION,
                loader.sendsThroughNms() ? "NMS packets" : "the Bukkit Messenger");
        loader.enable();
        loader.fireStarting(server);
        host = PaperEditHost.start(server, loader, SculptoryProtection.detect());
        PaperCommands commands = new PaperCommands(() -> host);
        loader.onCommands(commands::register);
        loader.registerCommandsOnEveryBuild();
        loader.fireStarted(server);
        SelfTest.runAtStartup(host);
    }

    @Override
    public void onDisable() {
        MinecraftServer server = MinecraftServer.getServer();
        if (loader != null) {
            loader.fireStopping(server);
            loader.fireStopped(server);
            loader.disable();
        }
        host = null;
    }
}
