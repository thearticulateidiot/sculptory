package dev.sculptory.paper.engine;

import dev.sculptory.mc.world.FluidTrails;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SpreadingSnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/**
 * Fluid trails on Paper ({@link FluidTrails}), fed by Bukkit's fluid events instead of Fabric's mixins: what the fluid
 * a history step wrote does afterwards is recorded so the entry's next undo takes it back too.
 *
 * <p><b>Covered</b> (each change noted on its own before it happens, the cell the event names and the other half of a
 * double block there; each cell's new state taken right after its own first change, reported by the physics event,
 * so later changes of the cell in the tick are not the fluid's; {@link FluidTrails#eventChange}):
 * <ul>
 *   <li>flowing out and down ({@link BlockFromToEvent}: the cell flowed into, a torch or plant it washes away, a tall
 *       plant's other half);</li>
 *   <li>a fluid cell's own level changing or drying up, flowing water becoming a source ({@link FluidLevelChangeEvent});</li>
 *   <li>blocks formed by the fluid: obsidian, cobblestone, stone and basalt where water and lava meet, ice where water
 *       freezes, concrete where powder meets water ({@link BlockFormEvent});</li>
 *   <li>grass dying under a full block of the fluid, ice melting ({@link BlockFadeEvent});</li>
 *   <li>fire lava sets ({@link BlockIgniteEvent} with cause LAVA).</li>
 * </ul>
 * <b>Not covered</b> (Paper fires no event a plugin could attribute): what those changes set off in other cells (a
 * redstone line losing power along its length when water washes away its first dust, a chain of vines, a block on top
 * of a washed-away one); block changes other plugins or commands make in a marked cell do not clear its mark (only
 * player placements and breaks, buckets and Sculptory's own edits do), so fluid poured there later may be taken for the
 * entry's. A noted cell whose own change fires no physics event (none of the fluid's writes above) is not recorded;
 * a tall plant's other half that does not pop, but is changed by something else later in that tick, is.
 *
 * <p>While an entry's step writes (frozen), its flows and level changes are cancelled and the fluid's tick is scheduled
 * again, as Fabric's tick hook puts them off; its random-tick changes (forming, fading, fire) are cancelled.
 *
 * <p><b>Marks with the chunks.</b> A column's marks live in its chunk's persistent data container under
 * {@value FluidTrails#NBT_KEY} (the key Fabric writes into the chunk data): read when the chunk loads, written before it
 * unloads, before a full world save, when the plugin is disabled ({@link #flushAll}: Paper disables plugins before
 * its final chunk save, so the unload and save handlers here no longer run for that save), and every {@value #FLUSH_TICKS} ticks for the loaded columns whose
 * marks changed (Moonrise's incremental autosave has no event, so a chunk it saves may carry marks up to that old). A
 * clean stop or restart keeps every mark; after a crash the
 * marks of up to the last ~5 s are lost: fluid an entry wrote just before is then no longer followed (its later flow is
 * not undone with it), and marks cleared in those seconds come back stale (fluid poured there later may be taken for
 * the entry's until the cell changes). A column whose trail still holds a part the history could not fold yet (its
 * player's step in flight, its history not loaded) is flagged unsaved at every flush ({@link FluidTrails#chunkSaved}),
 * so the autosave writes that chunk again every flush until the part is folded.
 */
final class PaperFluidTrails implements Listener {
    /** How often (ticks) changed marks are copied into their chunks' data containers. */
    static final int FLUSH_TICKS = 100;

    private final FluidTrails trails;
    private final Plugin plugin;
    /** Per world: columns whose container holds marks (as last written). */
    private final Map<ServerLevel, LongSet> written = new java.util.IdentityHashMap<>();
    /** The physics listener while announced changes are being recorded this tick, else null. */
    private RegisteredListener physics;
    /** The worlds whose physics event was switched on for it. */
    private final Set<ServerLevel> physicsOn = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    private PaperFluidTrails(Plugin plugin, FluidTrails trails) {
        this.plugin = Objects.requireNonNull(plugin);
        this.trails = Objects.requireNonNull(trails);
    }

    static PaperFluidTrails register(Plugin plugin, FluidTrails trails) {
        PaperFluidTrails listener = new PaperFluidTrails(plugin, trails);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        // Chunks loaded before the plugin was enabled: their marks are read now.
        for (org.bukkit.World world : Bukkit.getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) listener.load(level(world), chunk.getX(), chunk.getZ());
        }
        Bukkit.getScheduler().runTaskTimer(plugin, listener::flush, FLUSH_TICKS, FLUSH_TICKS);
        return listener;
    }

    // ================================================================== recording an announced change

    /**
     * An event announced a change of {@code owner}'s fluid at {@code pos} (null: no entry's): it is recorded on its
     * own ({@link FluidTrails#eventChange}), and until the end of the tick the cells' changes are reported to it
     * through the physics event, which Paper fires right after each block change (and as a neighbour update reaches a
     * cell). A listener is registered for the rest of the tick only and the world's physics event is switched on (Paper
     * decides it once per tick from whether any plugin listens).
     */
    private void announced(ServerLevel level, BlockPos pos, UUID owner) {
        if (owner == null) return;
        FluidTrails.eventChange(level, pos, owner);
        if (physics == null) {
            physics = new RegisteredListener(this, (listener, event) -> {
                if (event instanceof BlockPhysicsEvent changed && changed.getBlock() instanceof CraftBlock block
                        && block.getHandle() instanceof ServerLevel world) {
                    FluidTrails.eventCellChanged(world, block.getPosition());
                }
            }, EventPriority.LOWEST, plugin, false);
            BlockPhysicsEvent.getHandlerList().register(physics);
        }
        if (physicsOn.add(level)) level.hasPhysicsEvent = true;
    }

    /** After the tick's announced changes went into their trails: the physics listener goes, as do the switches. */
    void tickEnded() {
        if (physics == null) return;
        BlockPhysicsEvent.getHandlerList().unregister(physics);
        physics = null;
        boolean others = BlockPhysicsEvent.getHandlerList().getRegisteredListeners().length > 0;
        for (ServerLevel level : physicsOn) level.hasPhysicsEvent = others;
        physicsOn.clear();
    }

    // ================================================================== fluid changes

    /** Held still: the flow is put off (the fluid ticks again once its own delay has passed). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void holdFlow(BlockFromToEvent event) {
        ServerLevel level = level(event.getBlock().getWorld());
        BlockPos from = pos(event.getBlock());
        if (FluidTrails.heldStill(FluidTrails.eventOwner(level, from))) {
            event.setCancelled(true);
            tickLater(level, from);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void flow(BlockFromToEvent event) {
        ServerLevel level = level(event.getBlock().getWorld());
        BlockPos from = pos(event.getBlock());
        if (level.getFluidState(from).isEmpty()) return; // a dragon egg teleporting, not a fluid
        announced(level, pos(event.getToBlock()), FluidTrails.eventOwner(level, from));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void holdLevel(FluidLevelChangeEvent event) {
        ServerLevel level = level(event.getBlock().getWorld());
        BlockPos pos = pos(event.getBlock());
        if (FluidTrails.heldStill(FluidTrails.eventOwner(level, pos))) {
            event.setCancelled(true);
            tickLater(level, pos);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void level(FluidLevelChangeEvent event) {
        ServerLevel level = level(event.getBlock().getWorld());
        BlockPos pos = pos(event.getBlock());
        announced(level, pos, FluidTrails.eventOwner(level, pos));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void holdForm(BlockFormEvent event) {
        if (FluidTrails.heldStill(formOwner(event))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void form(BlockFormEvent event) {
        UUID owner = formOwner(event);
        if (owner != null) announced(level(event.getBlock().getWorld()), pos(event.getBlock()), owner);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void holdFade(BlockFadeEvent event) {
        if (FluidTrails.heldStill(fadeOwner(event))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fade(BlockFadeEvent event) {
        UUID owner = fadeOwner(event);
        if (owner != null) announced(level(event.getBlock().getWorld()), pos(event.getBlock()), owner);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void holdFire(BlockIgniteEvent event) {
        if (FluidTrails.heldStill(fireOwner(event))) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void fire(BlockIgniteEvent event) {
        UUID owner = fireOwner(event);
        if (owner != null) announced(level(event.getBlock().getWorld()), pos(event.getBlock()), owner);
    }

    /** A block formed at a cell: by the cell's own fluid (it freezes, lava turns to stone) or the fluid beside it. */
    private static UUID formOwner(BlockFormEvent event) {
        ServerLevel level = level(event.getBlock().getWorld());
        BlockPos pos = pos(event.getBlock());
        UUID owner = FluidTrails.eventOwner(level, pos);
        if (owner != null) return owner;
        // Concrete powder, or lava beside water: the fluid of a neighbour did it.
        for (net.minecraft.core.Direction direction : net.minecraft.core.Direction.values()) {
            BlockPos next = pos.relative(direction);
            if (level.getFluidState(next).isEmpty()) continue;
            owner = FluidTrails.markOwner(level, next);
            if (owner != null) return owner;
        }
        return null;
    }

    /** Ice melting is its own (marked) cell's; grass dying, the full fluid block's above it. */
    private static UUID fadeOwner(BlockFadeEvent event) {
        ServerLevel level = level(event.getBlock().getWorld());
        BlockPos pos = pos(event.getBlock());
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.ICE)) return FluidTrails.markOwner(level, pos);
        if (!(state.getBlock() instanceof SpreadingSnowyDirtBlock)) return null;
        FluidState above = level.getFluidState(pos.above());
        return above.getAmount() == 8 ? FluidTrails.markOwner(level, pos.above()) : null;
    }

    /** Fire lava set: the lava cell's entry. */
    private static UUID fireOwner(BlockIgniteEvent event) {
        if (event.getCause() != BlockIgniteEvent.IgniteCause.LAVA || event.getIgnitingBlock() == null) return null;
        return FluidTrails.markOwner(level(event.getBlock().getWorld()), pos(event.getIgnitingBlock()));
    }

    /** The fluid at {@code pos} ticks again after its own delay (its change was put off while its entry is held). */
    private static void tickLater(ServerLevel level, BlockPos pos) {
        FluidState fluid = level.getFluidState(pos);
        if (!fluid.isEmpty()) level.scheduleTick(pos, fluid.getType(), fluid.getType().getTickDelay(level));
    }

    // ================================================================== marks cleared by other changes

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placed(BlockPlaceEvent event) {
        ServerLevel level = level(event.getBlock().getWorld());
        if (event instanceof BlockMultiPlaceEvent multi) {
            for (org.bukkit.block.BlockState state : multi.getReplacedBlockStates()) {
                FluidTrails.changedOtherwise(level, new BlockPos(state.getX(), state.getY(), state.getZ()));
            }
        } else {
            FluidTrails.changedOtherwise(level, pos(event.getBlock()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        FluidTrails.changedOtherwise(level(event.getBlock().getWorld()), pos(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void emptied(PlayerBucketEmptyEvent event) {
        FluidTrails.changedOtherwise(level(event.getBlock().getWorld()), pos(event.getBlock()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void filled(PlayerBucketFillEvent event) {
        FluidTrails.changedOtherwise(level(event.getBlock().getWorld()), pos(event.getBlock()));
    }

    // ================================================================== marks in the chunks

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkLoaded(ChunkLoadEvent event) {
        load(level(event.getWorld()), event.getChunk().getX(), event.getChunk().getZ());
    }

    /** After the history hook (registered earlier at the same priority) folded the column's trails. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void chunkUnloading(ChunkUnloadEvent event) {
        ServerLevel level = level(event.getWorld());
        int cx = event.getChunk().getX(), cz = event.getChunk().getZ();
        if (event.isSaveChunk()) save(level, cx, cz);
        // Its container is on disk now (read again when the chunk loads): the flushes leave the column alone meanwhile.
        LongSet columns = written.get(level);
        if (columns != null) columns.remove(ChunkPos.asLong(cx, cz));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void worldSaving(WorldSaveEvent event) {
        ServerLevel level = level(event.getWorld());
        for (long column : columnsToWrite(level)) save(level, ChunkPos.getX(column), ChunkPos.getZ(column));
    }

    /** Every {@value #FLUSH_TICKS} ticks: the marked columns (and those that lost their marks) into their containers. */
    private void flush() {
        for (ServerLevel level : net.minecraft.server.MinecraftServer.getServer().getAllLevels()) flushWorld(level);
    }

    /**
     * The plugin is being disabled (the server stops, or the plugin is reloaded): every world's marked columns, and
     * those that lost their marks, go into their chunks' containers now. Paper disables plugins before its final save of
     * the chunks, which then writes them; neither the unload nor the save handler here runs for it any more.
     *
     * @return the columns whose container changed
     */
    int flushAll() {
        int changed = 0;
        for (ServerLevel level : net.minecraft.server.MinecraftServer.getServer().getAllLevels()) changed += flushWorld(level);
        return changed;
    }

    private int flushWorld(ServerLevel level) {
        int changed = 0;
        for (long column : columnsToWrite(level)) {
            if (save(level, ChunkPos.getX(column), ChunkPos.getZ(column))) changed++;
        }
        return changed;
    }

    private LongSet columnsToWrite(ServerLevel level) {
        LongSet columns = new LongOpenHashSet(trails.markedColumns(level));
        LongSet before = written.get(level);
        if (before != null) columns.addAll(before);
        return columns;
    }

    private void load(ServerLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return;
        Tag marks = chunk.persistentDataContainer.getRaw().get(FluidTrails.NBT_KEY);
        if (marks == null) return;
        written.computeIfAbsent(level, k -> new LongOpenHashSet()).add(ChunkPos.asLong(cx, cz));
        CompoundTag data = new CompoundTag();
        data.put(FluidTrails.NBT_KEY, marks.copy());
        FluidTrails.chunkLoaded(level, new ChunkPos(cx, cz), data);
    }

    /**
     * The column's marks into its chunk's container (removed when it has none), the chunk flagged when they changed;
     * returns whether they did.
     */
    private boolean save(ServerLevel level, int cx, int cz) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) return false;
        CompoundTag data = new CompoundTag();
        FluidTrails.chunkSaved(level, new ChunkPos(cx, cz), data, chunk);
        Tag marks = data.get(FluidTrails.NBT_KEY);
        Map<String, Tag> raw = chunk.persistentDataContainer.getRaw();
        Tag old = raw.get(FluidTrails.NBT_KEY);
        LongSet columns = written.computeIfAbsent(level, k -> new LongOpenHashSet());
        long column = ChunkPos.asLong(cx, cz);
        if (marks == null) {
            columns.remove(column);
            if (old == null) return false;
            raw.remove(FluidTrails.NBT_KEY);
        } else {
            columns.add(column);
            if (marks.equals(old)) return false;
            raw.put(FluidTrails.NBT_KEY, marks);
        }
        chunk.setUnsaved(true);
        return true;
    }

    private static ServerLevel level(org.bukkit.World world) {
        return ((CraftWorld) world).getHandle();
    }

    private static BlockPos pos(org.bukkit.block.Block block) {
        return new BlockPos(block.getX(), block.getY(), block.getZ());
    }
}
