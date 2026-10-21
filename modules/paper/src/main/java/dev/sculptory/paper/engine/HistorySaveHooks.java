package dev.sculptory.paper.engine;

import dev.sculptory.server.engine.impl.EngineEditService;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash-safe undo history on Paper: the engine's chunk-save hook ({@link EngineEditService#beforeChunkSave}, which
 * journals the history of the engine's changes in a chunk column and waits for it to reach the operating system) at
 * the places Paper lets a plugin run before chunks are written. On Fabric and NeoForge a mixin calls it before every
 * chunk save; Paper has no mixins and no public event before each chunk save:
 * <ul>
 *   <li>{@link ChunkUnloadEvent}: Moonrise fires it (on the server thread) when a chunk stops being loaded, before it
 *       queues the chunk's unload save. Covered: the chunk's history goes first.</li>
 *   <li>{@link WorldSaveEvent}: fired before a full save of a world ({@code save-all}, the periodic full save; at
 *       shutdown the plugin is disabled earlier and closes the history store itself). Covered: every loaded chunk
 *       column of the world goes first.</li>
 *   <li>Moonrise's incremental chunk autosave ({@code ChunkHolderManager.autoSave}, a few chunks every tick) fires no
 *       event and offers no hook; the only way in would be reflection into Moonrise's internals, which is not done. Not
 *       covered: open job records are journaled every {@value #SAVE_TICKS} ticks here (and brush strokes every
 *       {@code HistoryService.SAVE_OPEN_TICKS} ticks by the engine), so a crash right after an incremental autosave
 *       can leave up to that much of the newest edits in a chunk without undo.</li>
 * </ul>
 * The exact guarantee is logged once when the engine starts.
 */
final class HistorySaveHooks implements Listener {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** How often (ticks) open job records are journaled, for the chunks Paper autosaves without an event. */
    static final int SAVE_TICKS = 20;

    private final EngineEditService<ServerPlayer, ServerLevel> service;
    private final AtomicLong unloadSaves = new AtomicLong();
    private final AtomicLong worldSaves = new AtomicLong();
    private final AtomicLong offThread = new AtomicLong();

    private HistorySaveHooks(EngineEditService<ServerPlayer, ServerLevel> service) {
        this.service = service;
    }

    static void register(Plugin plugin, EngineEditService<ServerPlayer, ServerLevel> service) {
        HistorySaveHooks hooks = new HistorySaveHooks(service);
        Bukkit.getPluginManager().registerEvents(hooks, plugin);
        // Cancelled with the plugin's other tasks when it is disabled.
        Bukkit.getScheduler().runTaskTimer(plugin, hooks::saveOpenJobs, SAVE_TICKS, SAVE_TICKS);
        if (service.historyService().persistent()) {
            LOG.info("Sculptory: undo history on Paper is written ahead of chunk unloads and full world saves; Paper's "
                    + "incremental chunk autosave has no hook, so after a crash a chunk it saved can hold up to the "
                    + "last {} s (jobs) or {} s (brush strokes) of edits without undo", SAVE_TICKS / 20,
                    dev.sculptory.server.engine.impl.HistoryService.SAVE_OPEN_TICKS / 20);
        }
    }

    /** Lowest priority: before other plugins react to the unload (Moonrise saves the chunk after the event). */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!event.isSaveChunk()) return;
        ServerLevel level = ((CraftWorld) event.getWorld()).getHandle();
        Chunk chunk = event.getChunk();
        if (unloadSaves.incrementAndGet() == 1) LOG.info("Sculptory: the chunk-unload history hook ran (first unload)");
        if (!service.beforeChunkSave(level, chunk.getX(), chunk.getZ())) offThread.incrementAndGet();
    }

    /** Before a full save of the world writes its chunks: the history of every loaded column first. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldSave(WorldSaveEvent event) {
        ServerLevel level = ((CraftWorld) event.getWorld()).getHandle();
        long saves = worldSaves.incrementAndGet();
        int columns = 0;
        for (Chunk chunk : event.getWorld().getLoadedChunks()) {
            if (!service.beforeChunkSave(level, chunk.getX(), chunk.getZ())) offThread.incrementAndGet();
            columns++;
        }
        if (saves == 1) {
            LOG.info("Sculptory: the world-save history hook ran ({}, {} loaded columns)", event.getWorld().getName(),
                    columns);
        }
        if (offThread.get() > 0) {
            LOG.warn("Sculptory: {} chunk-save hooks ran off the server thread and did not protect their chunk",
                    offThread.get());
        }
    }

    /** Journals the open job records' changed sections (the history service's periodic save, more often). */
    private void saveOpenJobs() {
        service.historyService().saveOpen();
    }
}
