package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.box;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.engine.WorldUnloads;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * A world unloaded while the server runs ({@code WorldUnloads}), in a flat world of the run's own
 * ({@code sculptory_selftest_<run>}, created for it and deleted at the end), as the fake player only (a real player
 * would be in the overworld; Bukkit does not unload a world with players in it):
 * <ul>
 *   <li>a fill job runs there (slowed to a few thousand blocks a tick) and a builder-mode break drag is open there;</li>
 *   <li>refused: another plugin cancels the unload: the job runs on and the drag stays open;</li>
 *   <li>the unload: by the time {@code unloadWorld} returns, the job has ended {@code CANCELLED} with what it applied as
 *       its entry, the drag is an entry, nothing of the engine holds the world;</li>
 *   <li>partial failure made consistent: the world loaded again holds exactly the cells the job reported changed (and the
 *       broken block), so nothing wrote after the unload's save;</li>
 *   <li>exact undo: both entries undone in the reloaded world put every cell back.</li>
 * </ul>
 */
final class WorldChecks {
    private static final int TOP = -61; // the flat world's grass
    private static final int FILL_BLOCKS_PER_TICK = 4096;
    /** The flat world's layers, given in full (Paper logs an error for empty flat settings before it falls back). */
    private static final String FLAT_LAYERS = "{\"layers\":[{\"block\":\"minecraft:bedrock\",\"height\":1},"
            + "{\"block\":\"minecraft:dirt\",\"height\":2},{\"block\":\"minecraft:grass_block\",\"height\":1}],"
            + "\"biome\":\"minecraft:plains\"}";

    private WorldChecks() {
    }

    /** Cancels the unload of one world (another plugin's veto). */
    private static final class Veto implements Listener {
        World world;

        @EventHandler(priority = EventPriority.LOWEST)
        public void unloading(WorldUnloadEvent event) {
            if (event.getWorld() == world) event.setCancelled(true);
        }
    }

    /** The run's throwaway service lets go of the world as the host's does (WorldUnloads registers the host's). */
    private static final class ServiceUnload implements Listener {
        final SelfTestRun r;

        ServiceUnload(SelfTestRun r) {
            this.r = r;
        }

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        public void unloading(WorldUnloadEvent event) {
            ServerLevel level = ((CraftWorld) event.getWorld()).getHandle();
            WorldUnloads.endEdits(r.service, level, event.getWorld().getName());
        }
    }

    static CompletableFuture<Void> unload(SelfTestRun r) {
        if (r.attachment == null) {
            r.info("world unload", "skipped: runs as the fake player only (a real player keeps the world loaded)");
            return CompletableFuture.completedFuture(null);
        }
        String name = "sculptory_selftest_" + r.runId;
        Path folder = Bukkit.getWorldContainer().toPath().resolve(name).toAbsolutePath().normalize();
        if (Files.exists(folder) || Bukkit.getWorld(name) != null) {
            throw new IllegalStateException("a world or folder " + name + " exists already; the check changes nothing");
        }
        ServiceUnload service = new ServiceUnload(r);
        Veto veto = new Veto();
        Bukkit.getPluginManager().registerEvents(service, SculptoryPlugin.instance());
        Bukkit.getPluginManager().registerEvents(veto, SculptoryPlugin.instance());
        EditExecutor<ServerLevel> executor = r.service.executor();
        EditExecutor.Settings settings = executor.settings();
        // Whatever happens: the player back in the run's world, the executor's speed back, the world gone.
        r.cleanup(() -> {
            HandlerList.unregisterAll(service);
            HandlerList.unregisterAll(veto);
            executor.updateSettings(settings);
            r.player.setServerLevel(r.level);
            World left = Bukkit.getWorld(name);
            if (left != null) Bukkit.unloadWorld(left, false);
            delete(folder);
        });
        World world = create(name);
        ServerLevel level = ((CraftWorld) world).getHandle();
        String id = r.host.engine().worldId(level);
        hold(world);
        r.player.setServerLevel(level);
        r.stand(38.5, TOP + 1, 5.5, 90f, ItemStack.EMPTY);
        executor.updateSettings(slowed(settings));
        Box fill = box(0, TOP + 1, 0, 31, TOP + 16, 31);
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        CompletableFuture<JobResult> job = r.job("fill in " + name, listener -> r.service.run(r.player,
                new OpSpec.Fill(fill, new Pattern.Single(stone), CellMask.ANY), RunOptions.DEFAULT, listener));
        BlockPos broken = new BlockPos(36, TOP, 5);
        UUID owner = r.player.getUUID();
        return r.ticks(2).thenCompose(v -> {
            BuilderOutcome drag = r.service.builderBreak(r.player, new C2S.BuilderBreak(9001, 77,
                    List.of(new dev.sculptory.core.BlockPos(broken.getX(), broken.getY(), broken.getZ())), 0,
                    Symmetry.NONE, false, false));
            boolean running = !r.service.jobs(owner).isEmpty();
            r.record("world unload: a job runs and a drag is open in the world", running && drag.changed() == 1
                    && r.service.builderDragOpen(owner), "job running " + running + ", drag " + drag + ", drag open "
                    + r.service.builderDragOpen(owner));
            // Refused: another plugin vetoes the unload; nothing of the engine's work there ends.
            veto.world = world;
            boolean vetoed = !Bukkit.unloadWorld(world, true);
            veto.world = null;
            r.record("world unload: an unload another plugin cancels ends nothing", vetoed
                            && !r.service.jobs(owner).isEmpty() && r.service.builderDragOpen(owner) && !job.isDone(),
                    "unload refused " + vetoed + ", job running " + !r.service.jobs(owner).isEmpty() + ", drag open "
                            + r.service.builderDragOpen(owner));
            int before = r.service.historyService().undoEntries(owner).size();
            boolean unloaded = Bukkit.unloadWorld(world, true);
            JobResult result = job.getNow(null);
            List<HistoryEntry> entries = r.service.historyService().undoEntries(owner);
            boolean ended = result != null && result.outcome() == JobOutcome.CANCELLED && result.changed() > 0
                    && result.changed() < fill.volume();
            boolean released = r.service.jobs(owner).isEmpty() && !executor.hasJobIn(level)
                    && !r.service.builderDragOpen(owner);
            boolean recorded = entries.size() == before + 2 && entries.get(0).world().equals(id)
                    && entries.get(1).world().equals(id);
            r.record("world unload: the job ends with what it applied, the drag becomes an entry, nothing holds the "
                    + "world", unloaded && ended && released && recorded, "unloaded " + unloaded + ", job "
                    + (result == null ? "still running" : result.outcome() + " after " + result.changed() + " of "
                    + fill.volume() + " cells") + ", released " + released + ", entries " + entries.stream().limit(2)
                    .map(e -> e.label() + " in " + e.world()).toList());
            r.player.setServerLevel(r.level);
            executor.updateSettings(settings);
            long changed = result == null ? -1 : result.changed();
            return r.ticks(10).thenCompose(w -> reloaded(r, name, id, fill, broken, changed));
        });
    }

    /**
     * The world loaded again: it holds exactly what the history says (the job's changed cells, the broken block); both
     * entries undone there put every cell back.
     */
    private static CompletableFuture<Void> reloaded(SelfTestRun r, String name, String id, Box fill, BlockPos broken,
                                                    long changed) {
        World world = create(name);
        ServerLevel level = ((CraftWorld) world).getHandle();
        hold(world);
        long stone = 0;
        for (BlockPos cell : SelfTestRun.cells(fill)) {
            if (level.getBlockState(cell).is(Blocks.STONE)) stone++;
        }
        boolean gone = level.getBlockState(broken).isAir();
        r.record("world unload: the world loaded again holds exactly what the history recorded", stone == changed && gone
                        && r.host.engine().worldId(level).equals(id),
                stone + " stone cells in the box, the job reported " + changed + "; the broken block air " + gone);
        r.player.setServerLevel(level);
        r.stand(38.5, TOP + 1, 5.5, 90f, ItemStack.EMPTY);
        return r.undo().thenCompose(v -> r.undo()).thenAccept(result -> {
            long left = 0;
            for (BlockPos cell : SelfTestRun.cells(fill)) {
                if (!level.getBlockState(cell).isAir()) left++;
            }
            boolean grass = level.getBlockState(broken).is(Blocks.GRASS_BLOCK);
            r.record("world unload: both entries undone in the reloaded world put every cell back", left == 0 && grass,
                    left + " cells of the box not air, the broken block back " + grass + " (" + result.outcome() + ", "
                            + result.changed() + " changed)");
            r.player.setServerLevel(r.level);
        });
    }

    private static World create(String name) {
        World world = new WorldCreator(name).type(WorldType.FLAT).generatorSettings(FLAT_LAYERS)
                .generateStructures(false).createWorld();
        if (world == null) throw new IllegalStateException("the world " + name + " could not be created");
        return world;
    }

    /** The chunks the check uses, loaded and held for it (released when the world unloads). */
    private static void hold(World world) {
        for (int cx = 0; cx <= 2; cx++) {
            for (int cz = 0; cz <= 1; cz++) {
                world.addPluginChunkTicket(cx, cz, SculptoryPlugin.instance());
                world.getChunkAt(cx, cz);
            }
        }
    }

    /** The executor's settings with at most {@value #FILL_BLOCKS_PER_TICK} blocks a tick, so the fill spans ticks. */
    private static EditExecutor.Settings slowed(EditExecutor.Settings s) {
        return new EditExecutor.Settings(s.tickBudgetNanos(), FILL_BLOCKS_PER_TICK, s.brushLaneShare(),
                s.maxActivePerOwner(), s.maxActiveGlobal(), s.maxQueued(), s.maxTicketsPerJob(), s.unloadedPolicy(),
                s.maxBrushQueue(), s.maxColumnsPerJob(), s.maxQueuedPerOwner());
    }

    /** Removes the run's own world folder (made by this run, its name unique to it), if it is there. */
    private static void delete(Path folder) {
        if (!Files.isDirectory(folder) || !folder.getFileName().toString().startsWith("sculptory_selftest_")) return;
        try (Stream<Path> files = Files.walk(folder)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            Sculptory.LOG.warn("Sculptory self-test: the world folder {} could not be removed", folder, e);
        }
    }
}
