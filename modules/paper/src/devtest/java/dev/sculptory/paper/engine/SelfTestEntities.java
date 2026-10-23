package dev.sculptory.paper.engine;

import dev.sculptory.server.engine.impl.EngineEditService;
import java.util.Arrays;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.plugin.Plugin;

/**
 * Builder mode's entity listener for the plugin's self-test only (dev.sculptory.paper.devtest): the self-test edits
 * through an edit service of its own, so it registers a {@link BuilderEntityListener} of that service and settles its
 * reads itself, where the plugin's own listener is settled by the end of the server tick.
 */
public final class SelfTestEntities {
    private SelfTestEntities() {
    }

    /** A listener of the self-test's service: {@link #settle} what the events of this tick announced, then close. */
    public static final class Handle implements AutoCloseable {
        private final BuilderEntityListener listener;

        private Handle(BuilderEntityListener listener) {
            this.listener = listener;
        }

        /** What the end of the tick does: every read is settled into history steps. */
        public void settle() {
            listener.tickEnded();
        }

        @Override
        public void close() {
            HandlerList.unregisterAll(listener);
        }
    }

    /** Registers a builder-mode entity listener of {@code service} with {@code plugin}. */
    public static Handle listen(Plugin plugin, EngineEditService<ServerPlayer, ServerLevel> service) {
        BuilderEntityListener listener = new BuilderEntityListener(service);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        return new Handle(listener);
    }

    /** Whether the plugin's own listener (of its service, settled by the tick hook) listens to the place events. */
    public static boolean pluginListens(Plugin plugin) {
        return Arrays.stream(HangingPlaceEvent.getHandlerList().getRegisteredListeners())
                .anyMatch(l -> l.getPlugin() == plugin && l.getListener() instanceof BuilderEntityListener);
    }
}
