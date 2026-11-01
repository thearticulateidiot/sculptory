package dev.sculptory.paper.engine;

import dev.sculptory.server.engine.impl.EngineEditService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

/**
 * Builder mode's block-click listener for the plugin's self-test only (dev.sculptory.paper.devtest): the self-test
 * edits through an edit service of its own, so it registers a {@link BuilderModeListener} of that service to see the
 * game's own placement held back (or not) for the self-test player.
 */
public final class SelfTestBuilderMode {
    private SelfTestBuilderMode() {
    }

    /** Registers a {@link BuilderModeListener} of {@code service} with {@code plugin}; the result unregisters it. */
    public static Runnable listen(Plugin plugin, EngineEditService<ServerPlayer, ServerLevel> service) {
        BuilderModeListener listener = new BuilderModeListener(service);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        return () -> HandlerList.unregisterAll(listener);
    }
}
