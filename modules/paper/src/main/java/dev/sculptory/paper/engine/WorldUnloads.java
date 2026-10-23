package dev.sculptory.paper.engine;

import dev.sculptory.mc.world.FluidTrails;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.engine.impl.ServerScatter;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A world unloaded while the server runs ({@code Bukkit.unloadWorld}: Multiverse, a minigame plugin). Bukkit fires
 * {@link WorldUnloadEvent} before it saves the world and closes its chunks; at {@code MONITOR} (no plugin cancels the
 * unload after that) the engine lets go of the world first, so nothing it runs writes there afterwards and the world on
 * disk matches the history:
 * <ol>
 *   <li>scatter previews being worked out there end ({@code UNLOADED} to their player);</li>
 *   <li>the edits end ({@link #endEdits}): jobs there end as at shutdown (a running one after its current section,
 *       {@code CANCELLED}, what it applied recorded as its entry), dabs queued for strokes there are dropped and those
 *       strokes become entries, builder-mode drags there become entries; then every loaded column's history is journaled
 *       and its fluid trails folded, as before a full save;</li>
 *   <li>the fluid marks there go into their chunks' data containers (written by the unload's save), and FluidTrails
 *       forgets the world, so a world loaded again reads them back.</li>
 * </ol>
 * Then the unload goes on. Bukkit refuses to unload a world with players in it (or the main world) before the event, so
 * no player is building there when this runs; their entries stay in their history and undo there once the world is back
 * (an undo of an entry whose world is not loaded is refused).
 */
public final class WorldUnloads implements Listener {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    private final EngineEditService<ServerPlayer, ServerLevel> service;
    private final ServerScatter<ServerPlayer, ServerLevel> scatter;
    private final PaperFluidTrails marks;
    private final FluidTrails trails;

    WorldUnloads(EngineEditService<ServerPlayer, ServerLevel> service, ServerScatter<ServerPlayer, ServerLevel> scatter,
                 PaperFluidTrails marks, FluidTrails trails) {
        this.service = Objects.requireNonNull(service);
        this.scatter = Objects.requireNonNull(scatter);
        this.marks = Objects.requireNonNull(marks);
        this.trails = Objects.requireNonNull(trails);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void unloading(WorldUnloadEvent event) {
        ServerLevel level = ((CraftWorld) event.getWorld()).getHandle();
        // Each step on its own: whatever one throws, the next still runs and the unload goes on.
        int previews = 0;
        try {
            previews = scatter.worldUnloading(level);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: ending the scatter previews in {} before it unloads failed", event.getWorld().getName(), e);
        }
        int[] ended = endEdits(service, level, event.getWorld().getName());
        int written = 0;
        try {
            written = marks.worldUnloading(level);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: writing the fluid marks of {} before it unloads failed", event.getWorld().getName(), e);
        }
        long lost = 0;
        try {
            lost = trails.worldUnloaded(level);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: letting go of the fluid trails of {} failed", event.getWorld().getName(), e);
        }
        LOG.info("Sculptory: world {} is unloading: {} jobs and {} scatter previews ended there, the history of {} "
                + "loaded columns journaled, the fluid marks of {} columns written", event.getWorld().getName(),
                ended[0], previews, ended[1], written);
        if (lost > 0) {
            LOG.warn("Sculptory: {} cells of fluid trails in {} could not be folded into their entries before it "
                    + "unloaded; their undo leaves that fluid", lost, event.getWorld().getName());
        }
    }

    /**
     * Ends {@code service}'s edits in {@code level} ({@link EngineEditService#worldUnloading}), then journals the
     * history of each of its loaded columns ({@link EngineEditService#beforeChunkSave}). Each step is logged and
     * skipped when it throws. Returns the jobs ended and the columns journaled.
     */
    public static int[] endEdits(EngineEditService<ServerPlayer, ServerLevel> service, ServerLevel level, String name) {
        int jobs = 0;
        try {
            jobs = service.worldUnloading(level);
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: ending the edits in {} before it unloads failed", name, e);
        }
        int columns = 0;
        try {
            for (Chunk chunk : level.getWorld().getLoadedChunks()) {
                service.beforeChunkSave(level, chunk.getX(), chunk.getZ());
                columns++;
            }
        } catch (RuntimeException | LinkageError e) {
            LOG.error("Sculptory: journaling the history of {} before it unloads failed", name, e);
        }
        return new int[] {jobs, columns};
    }
}
