package dev.sculptory.paper.engine;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.mc.builder.BuilderChange;
import dev.sculptory.mc.builder.BuilderPlacement;
import dev.sculptory.mc.builder.BuilderSteps;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McTile;
import dev.sculptory.paper.nms.PaperAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.SignItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.CraftBlockEntityState;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder mode's world steps on Paper ({@link BuilderSteps}), without mixins: CraftBukkit's own block capture, the way
 * Paper places a player's block ({@code ItemStack.useOn}), plus the Bukkit block events, so protection and logging
 * plugins (WorldGuard, CoreProtect) see each builder block as a player's and may cancel it.
 *
 * <ul>
 *   <li><b>Capture.</b> A placement step runs with {@code Level.captureBlockStates} on: every {@code setBlock} of the
 *       step notes the cell's state (and block entity) from before its first change as a CraftBukkit block state,
 *       changes the cell, and defers its neighbour updates and client update; block entities the step sets wait in
 *       {@code capturedTileEntities}. So the action knows the first state of every cell the step changed, exactly.</li>
 *   <li><b>Events.</b> A placement step then fires {@code BlockPlaceEvent} (one block) or {@code BlockMultiPlaceEvent}
 *       (a door, a bed: every block the step set), with {@code canBuild} true (the engine has checked spawn protection
 *       and the world border already). Cancelled, the step's cells are put back physics-free and the step is
 *       {@link Step#REFUSED}. A break fires {@code BlockBreakEvent} before anything changes, as a player's break does;
 *       cancelled, nothing happens. The cells vanilla's own steps reach beyond the target (a bed's other half) fire no
 *       event of their own, as for a player; the engine checks them against protection afterwards.</li>
 *   <li><b>Physics</b> ({@link Action#finish}), without Keep shape: what each changed cell sets off runs then, with the
 *       capture off, as Paper runs it after a placement event: the block's add callback once for the cell's final
 *       state, then its neighbour and shape updates ({@code notifyAndUpdatePhysics}); everything those set off runs at
 *       once, depth first, as in vanilla (Paper drops neighbour updates while a capture is on, so a lamp, a door or a
 *       redstone line would not react). A wither skull completing a wither's pattern is checked then too, as Paper does
 *       after the event. A break without Keep shape needs no capture (its event came first): it runs with its physics
 *       at once, as a player's break does.</li>
 *   <li><b>Recording what the physics change.</b> With the capture off, the cells the updates change are found through
 *       {@link BlockPhysicsEvent}: before the updates run, every loaded cell within {@value #WATCH_RADIUS} blocks of
 *       each changed cell (of the target, for a break) is noted with its state; while they run, a listener on the
 *       physics event (registered for the action only, first in line) notes every cell within {@value #WATCH_RADIUS}
 *       blocks of each cell an update reaches, before that cell reacts. Paper fires the event for every neighbour update
 *       before the block reacts, and for every changed cell before its shape updates go out, so a cell the cascade
 *       changes has been noted before it changes. At the end every noted cell whose state differs is a change of the
 *       action, with the block entity it had (saved then, from the object noted). At most
 *       {@value #MAX_WATCHED_CELLS} cells are noted per action; past that (a long redstone contraption) the rest of the
 *       cascade still runs, but what it changes is not recorded for the undo (logged).</li>
 *   <li><b>Keep shape:</b> nothing around reacts: the replaced blocks' callbacks do not run during the step
 *       ({@code isBlockPlaceCancelled}), their add callbacks are deferred by the capture and never run, and each
 *       changed cell only tells the clients and the points of interest. Breaks run captured for this.</li>
 * </ul>
 *
 * <p><b>Events and the history.</b> The place and break events fire for every step, also for one the engine puts back
 * afterwards (a secondary cell another protection refuses, the global mask, a failure), and undo and redo fire none:
 * a logging plugin sees the builder action as placed and broken by the player, and does not see the undo.
 *
 * <p>Known differences from Fabric's mixin capture: what a replaced block's own removal sets off beyond its neighbours
 * does not run when a placement replaces it (Replace over a powered lever: its attached block's neighbours keep their
 * power), as for a Paper player placing into a replaceable block; a block entity block ({@code BaseEntityBlock}, such as
 * a hopper) still runs its own add callback during a Keep shape step (CraftBukkit does not defer those); with Keep shape
 * a wither skull completing the pattern builds no wither (its pattern could only be cleared with physics); with
 * Paper's alternate redstone implementations (Eigencraft, Alternate Current) the wires a network update sets at once
 * beyond {@value #WATCH_RADIUS} blocks of an updated cell are not recorded; a block entity a cascade changes without
 * changing the block (a command block's powered flag) is not recorded, as on Fabric.
 */
public final class PaperBuilderSteps implements BuilderSteps {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** The most cells one action notes while its physics run (each a state and a reference, about 100 bytes). */
    static final int MAX_WATCHED_CELLS = 32768;
    /** How far around a changed or updated cell the cells are noted before they may change. */
    static final int WATCH_RADIUS = 2;

    private final Plugin plugin;

    /** {@code plugin} owns the physics listener an action registers while it watches (PaperEngine; the self-test). */
    public PaperBuilderSteps(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin);
    }

    @Override
    public Action open(ServerLevel world, ServerPlayer player, boolean keepShape) {
        if (world.captureBlockStates || world.captureTreeGeneration) {
            throw new IllegalStateException("another block capture is open on " + world.dimension().location());
        }
        return new PaperAction(plugin, world, player, keepShape);
    }

    @Override
    public void revert(ServerLevel world, List<BuilderChange> changes) {
        for (int i = changes.size() - 1; i >= 0; i--) {
            BuilderChange change = changes.get(i);
            putBack(world, change.pos(), Block.stateById(change.before()));
            change.putBackTile(world, Block.stateById(change.before()));
            world.getChunkSource().blockChanged(change.pos());
        }
    }

    /**
     * Writes {@code state} at {@code pos} physics-free: no callbacks (nothing drops), no neighbour or comparator
     * updates; a block entity the new state does not share goes through the chunk.
     */
    private static void putBack(ServerLevel world, BlockPos pos, BlockState state) {
        if (world.getBlockState(pos).hasBlockEntity()) BlockWriter.removeBlockEntityQuietly(world, pos);
        PaperAccess.setBlockWithoutCallbacks(world, pos, state);
    }

    /**
     * A cell as it was before the action first changed it. A cell a step changed has its block entity saved
     * ({@code tile}); a cell only noted around the physics ({@code watched}) keeps a reference to its block entity
     * ({@code entity}), saved only if the cell's state turns out changed (most noted cells never change).
     */
    private record Before(int state, BlockEntityData tile, BlockEntity entity, boolean watched) {
    }

    /** A changed cell whose deferred updates are still to run: its state before the action and its last change's flags. */
    private record Pending(BlockPos pos, BlockState old, int flags) {
    }

    /** What one captured step changed: CraftBukkit's block states from before, and the block entities it set. */
    private record Phase(List<CraftBlockState> states, Map<BlockPos, BlockEntity> tiles) {
        boolean isEmpty() {
            return states.isEmpty() && tiles.isEmpty();
        }
    }

    private static final class PaperAction implements Action, Listener, EventExecutor {
        private final Plugin plugin;
        private final ServerLevel world;
        private final ServerPlayer player;
        private final boolean keepShape;
        private final Long2ObjectLinkedOpenHashMap<Before> befores = new Long2ObjectLinkedOpenHashMap<>();
        /** The cells the captured steps changed, each once, in the order first changed. */
        private final Long2ObjectLinkedOpenHashMap<Pending> pending = new Long2ObjectLinkedOpenHashMap<>();
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        /** The physics listener while the action watches, else null. */
        private RegisteredListener watch;
        private boolean physicsEventBefore;
        private int watched;
        private boolean watchFull;
        private boolean finished;
        private boolean captureFailureLogged;
        private LevelChunk lastChunk;

        PaperAction(Plugin plugin, ServerLevel world, ServerPlayer player, boolean keepShape) {
            this.plugin = plugin;
            this.world = world;
            this.player = player;
            this.keepShape = keepShape;
        }

        @Override
        public void emptyContainer(BlockPos pos) {
            BlockEntity entity = world.getBlockEntity(pos);
            if (!(entity instanceof Clearable)) return;
            note(pos);
            // Clearing a lectern or a jukebox changes its block too: captured like a step, without an event.
            keep(capture(() -> Clearable.tryClear(entity)));
        }

        @Override
        public Step place(BlockItem item, BlockPlaceContext context, BlockState state, boolean copy) {
            boolean[] placed = {false};
            SignItem.openSign = null;
            Phase phase = capture(() -> placed[0] = BuilderPlacement.place(item, context, state, copy));
            // A sign's editor waits for the event (CraftBukkit's SignItem notes where, as for a player's placement).
            BlockPos sign = SignItem.openSign;
            SignItem.openSign = null;
            if (phase.isEmpty()) return placed[0] ? Step.DONE : Step.NOT_DONE;
            if (!placeAllowed(context, phase)) {
                revertPhase(phase);
                return Step.REFUSED;
            }
            keep(phase);
            if (sign != null && !copy) openSignEditor(sign);
            return placed[0] ? Step.DONE : Step.NOT_DONE;
        }

        @Override
        public Step breakBlock(BlockPos pos) {
            BlockBreakEvent event = new BlockBreakEvent(CraftBlock.at(world, pos), player.getBukkitEntity());
            // A creative break drops nothing (and builder mode empties containers into the history first).
            event.setDropItems(false);
            event.setExpToDrop(0);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return Step.REFUSED;
            emptyContainer(pos);
            boolean[] removed = {false};
            if (keepShape) {
                keep(capture(() -> removed[0] = BuilderPlacement.breakBlock(world, player, pos)));
                return removed[0] ? Step.DONE : Step.NOT_DONE;
            }
            // The event came first, so nothing needs deferring: the break runs with its physics now (a lever's or a
            // torch's own updates included), watched like the deferred updates of a placement.
            startWatching();
            watchAround(pos);
            removed[0] = BuilderPlacement.breakBlock(world, player, pos);
            // The break's own updates have run: an emptied container's deferred ones are not run again.
            pending.remove(pos.asLong());
            return removed[0] ? Step.DONE : Step.NOT_DONE;
        }

        @Override
        public List<BuilderChange> finish() {
            if (!finished) {
                finished = true;
                try {
                    if (keepShape) {
                        notifyOnly();
                    } else {
                        runUpdates();
                    }
                } finally {
                    stopWatching();
                }
                if (watchFull) {
                    LOG.warn("Sculptory: a builder action set off updates beyond the {} cells followed per action; what "
                            + "they changed further out is not undone with it", MAX_WATCHED_CELLS);
                }
            }
            List<BuilderChange> changes = new ArrayList<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (Long2ObjectMap.Entry<Before> entry : befores.long2ObjectEntrySet()) {
                pos.set(entry.getLongKey());
                Before before = entry.getValue();
                BlockState now = world.getBlockState(pos);
                int after = Block.getId(now);
                if (before.watched() && after == before.state()) continue;
                BlockEntityData beforeTile = before.watched() ? saved(pos, before.entity()) : before.tile();
                BlockEntityData tile = now.hasBlockEntity() ? tileAt(pos) : null;
                if (after == before.state() && sameTile(beforeTile, tile)) continue;
                changes.add(new BuilderChange(pos.immutable(), before.state(), beforeTile, after, tile));
            }
            return changes;
        }

        /** Keep shape: clients and points of interest hear of each changed cell; nothing around it is updated. */
        private void notifyOnly() {
            for (Pending p : pending.values()) {
                BlockState now = world.getBlockState(p.pos());
                int flags = (p.flags() | Block.UPDATE_KNOWN_SHAPE) & ~Block.UPDATE_NEIGHBORS;
                world.notifyAndUpdatePhysics(p.pos(), null, p.old(), now, now, flags, 0);
            }
        }

        /**
         * Without Keep shape: each changed cell's add callback (once, for its final state) and updates, with the
         * capture off and the cells around watched; then a wither skull's pattern check, as Paper's {@code useOn} runs
         * it after the event (it does nothing while a capture is on). An iron or snow golem is built by the pumpkin's
         * add callback here.
         */
        private void runUpdates() {
            if (pending.isEmpty()) return;
            startWatching();
            for (Pending p : pending.values()) watchAround(p.pos());
            for (Pending p : pending.values()) update(p);
            for (Pending p : pending.values()) {
                BlockState now = world.getBlockState(p.pos());
                if (now.is(Blocks.WITHER_SKELETON_SKULL) || now.is(Blocks.WITHER_SKELETON_WALL_SKULL)) {
                    WitherSkullBlock.checkSpawn(world, p.pos());
                }
            }
        }

        /** What Paper runs after a placement event for each placed cell: the block's add callback, then its updates. */
        private void update(Pending p) {
            BlockState now = world.getBlockState(p.pos());
            if (now == p.old()) {
                // Changed and changed back by the steps: nothing to set off; the clients still hear (they were not told).
                world.getChunkSource().blockChanged(p.pos());
                return;
            }
            // A block entity block ran its add callback during the capture already (CraftBukkit lets it).
            if (!(now.getBlock() instanceof BaseEntityBlock)) now.onPlace(world, p.pos(), p.old(), false);
            world.notifyAndUpdatePhysics(p.pos(), null, p.old(), now, world.getBlockState(p.pos()), p.flags(), 512);
        }

        // ============================================================ watching the physics

        /**
         * Starts noting the cells around every block update: a physics listener for this action only, called first,
         * and the world's physics event switched on (Paper fires it for a changed cell's shape updates only while some
         * plugin listens, decided once per tick).
         */
        private void startWatching() {
            if (watch != null) return;
            physicsEventBefore = world.hasPhysicsEvent;
            watch = new RegisteredListener(this, this, EventPriority.LOWEST, plugin, false);
            BlockPhysicsEvent.getHandlerList().register(watch);
            world.hasPhysicsEvent = true;
        }

        private void stopWatching() {
            if (watch == null) return;
            BlockPhysicsEvent.getHandlerList().unregister(watch);
            watch = null;
            // What the server would decide at the next tick: another plugin may have started listening meanwhile.
            world.hasPhysicsEvent = physicsEventBefore
                    || BlockPhysicsEvent.getHandlerList().getRegisteredListeners().length > 0;
        }

        /** The physics listener: the cell the update reaches and those around it are noted before it reacts. */
        @Override
        public void execute(Listener listener, Event event) {
            if (!(event instanceof BlockPhysicsEvent physics)) return;
            if (!(physics.getBlock() instanceof CraftBlock block) || block.getHandle() != world) return;
            watchAround(block.getPosition());
        }

        /** Notes every loaded cell within {@value #WATCH_RADIUS} blocks of {@code center} not noted yet, as it is now. */
        private void watchAround(BlockPos center) {
            if (watchFull) return;
            for (int dy = -WATCH_RADIUS; dy <= WATCH_RADIUS; dy++) {
                int y = center.getY() + dy;
                if (world.isOutsideBuildHeight(y)) continue;
                for (int dz = -WATCH_RADIUS; dz <= WATCH_RADIUS; dz++) {
                    for (int dx = -WATCH_RADIUS; dx <= WATCH_RADIUS; dx++) {
                        cursor.set(center.getX() + dx, y, center.getZ() + dz);
                        long key = cursor.asLong();
                        if (befores.containsKey(key)) continue;
                        LevelChunk chunk = loadedChunk(cursor.getX() >> 4, cursor.getZ() >> 4);
                        if (chunk == null) continue;
                        if (watched >= MAX_WATCHED_CELLS) {
                            watchFull = true;
                            return;
                        }
                        BlockState state = chunk.getBlockState(cursor);
                        BlockEntity entity = state.hasBlockEntity()
                                ? chunk.getBlockEntity(cursor, LevelChunk.EntityCreationType.CHECK) : null;
                        befores.put(key, new Before(Block.getId(state), null, entity, true));
                        watched++;
                    }
                }
            }
        }

        /** The chunk at (cx, cz) if it is loaded (never loads one). */
        private LevelChunk loadedChunk(int cx, int cz) {
            LevelChunk last = lastChunk;
            if (last != null && last.getPos().x == cx && last.getPos().z == cz) return last;
            LevelChunk chunk = world.getChunkSource().getChunkNow(cx, cz);
            if (chunk != null) lastChunk = chunk;
            return chunk;
        }

        // ============================================================ the capture

        /**
         * Runs {@code step} with CraftBukkit's block capture on; returns what it changed (taken out of the world's
         * capture). Whatever the step throws, an {@link Error} included, the capture is switched off and the world's
         * captured states and block entities are emptied before it goes on (the next placement anywhere would take
         * them for its own); what the step changed so far is kept and recorded first.
         */
        private Phase capture(Runnable step) {
            boolean cancelled = world.isBlockPlaceCancelled;
            world.captureBlockStates = true;
            // Keep shape: the replaced blocks' own callbacks do not run (nothing drops, no cleanup reacts).
            if (keepShape) world.isBlockPlaceCancelled = true;
            Throwable failure = null;
            try {
                step.run();
            } catch (Throwable e) {
                failure = e;
            } finally {
                world.captureBlockStates = false;
                world.isBlockPlaceCancelled = cancelled;
            }
            Phase phase;
            try {
                phase = new Phase(new ArrayList<>(world.capturedBlockStates.values()),
                        new LinkedHashMap<>(world.capturedTileEntities));
            } finally {
                world.capturedBlockStates.clear();
                world.capturedTileEntities.clear();
            }
            if (failure == null) return phase;
            // The game failed halfway: what it changed so far stays and is recorded (BuilderService reports FAILED,
            // or puts it back after an Error).
            keep(phase);
            if (failure instanceof RuntimeException e) throw e;
            if (failure instanceof Error e) throw e;
            throw new IllegalStateException(failure);
        }

        /** Keeps what a step changed: its block entities go into the world, its cells are noted and wait for updates. */
        private void keep(Phase phase) {
            for (BlockEntity entity : phase.tiles().values()) world.setBlockEntity(entity);
            for (CraftBlockState state : phase.states()) {
                BlockPos pos = state.getPosition();
                long key = pos.asLong();
                Before known = befores.get(key);
                // A cell only watched so far (unchanged since) takes the capture's exact first state instead.
                if (known == null || (known.watched() && known.state() == Block.getId(state.getHandle()))) {
                    befores.put(key, before(state));
                }
                Pending first = pending.get(key);
                pending.put(key, new Pending(pos, first == null ? state.getHandle() : first.old(), state.getFlag()));
            }
        }

        /**
         * Puts back what a refused step changed, newest first, physics-free; its block entities never reach the world.
         * Points of interest are left alone, as Paper's own revert of a cancelled placement does: the step's changes
         * never registered theirs (the capture deferred it), so removing them now would find nothing (a bed's).
         */
        private void revertPhase(Phase phase) {
            List<CraftBlockState> states = phase.states();
            boolean prevent = world.preventPoiUpdated;
            world.preventPoiUpdated = true;
            try {
                revertStates(states);
            } finally {
                world.preventPoiUpdated = prevent;
            }
        }

        private void revertStates(List<CraftBlockState> states) {
            for (int i = states.size() - 1; i >= 0; i--) {
                CraftBlockState state = states.get(i);
                BlockPos pos = state.getPosition();
                Before before = before(state);
                putBack(world, pos, state.getHandle());
                new BuilderChange(pos, before.state(), before.tile(), before.state(), null)
                        .putBackTile(world, state.getHandle());
                world.getChunkSource().blockChanged(pos);
            }
        }

        /** Whether the place event for a step lets it stand (no plugin cancelled it). */
        private boolean placeAllowed(BlockPlaceContext context, Phase phase) {
            List<org.bukkit.block.BlockState> states = new ArrayList<>(phase.states());
            if (states.isEmpty()) return true;
            BlockPos clicked = context.replacingClickedOnBlock() ? context.getClickedPos()
                    : context.getClickedPos().relative(context.getClickedFace().getOpposite());
            org.bukkit.block.Block against = CraftBlock.at(world, clicked);
            org.bukkit.inventory.ItemStack item = CraftItemStack.asBukkitCopy(context.getItemInHand());
            EquipmentSlot hand = context.getHand() == InteractionHand.OFF_HAND ? EquipmentSlot.OFF_HAND
                    : EquipmentSlot.HAND;
            BlockPlaceEvent event;
            if (states.size() > 1) {
                event = new BlockMultiPlaceEvent(states, against, item, player.getBukkitEntity(), true, hand);
            } else {
                org.bukkit.block.BlockState replaced = states.get(0);
                event = new BlockPlaceEvent(replaced.getBlock(), replaced, against, item, player.getBukkitEntity(),
                        true, hand);
            }
            Bukkit.getPluginManager().callEvent(event);
            return !event.isCancelled() && event.canBuild();
        }

        /** The sign editor a player's sign placement opens, once its event let it stand. */
        private void openSignEditor(BlockPos pos) {
            if (world.getBlockEntity(pos) instanceof SignBlockEntity sign
                    && world.getBlockState(pos).getBlock() instanceof SignBlock block) {
                block.openTextEdit(player, sign, true, io.papermc.paper.event.player.PlayerOpenSignEvent.Cause.PLACE);
            }
        }

        /** Notes the cell as it is now (before a change no capture sees: clearing a container). First state wins. */
        private void note(BlockPos pos) {
            long key = pos.asLong();
            Before known = befores.get(key);
            if (known != null && !known.watched()) return;
            BlockState state = world.getBlockState(pos);
            if (known != null && known.state() != Block.getId(state)) return;
            befores.put(key, new Before(Block.getId(state), state.hasBlockEntity() ? tileAt(pos) : null, null, false));
        }

        /** A cell's first state as CraftBukkit captured it, its block entity included. */
        private Before before(CraftBlockState state) {
            BlockEntityData tile = null;
            if (state instanceof CraftBlockEntityState<?> entityState) {
                try {
                    CompoundTag nbt = entityState.getSnapshotNBT();
                    BlockEntity copy = BlockEntity.loadStatic(state.getPosition(), state.getHandle(), nbt,
                            world.registryAccess());
                    if (copy != null) tile = McTile.capture(copy, world.registryAccess());
                } catch (RuntimeException e) {
                    logCaptureFailure(state.getPosition(), e);
                }
            }
            return new Before(Block.getId(state.getHandle()), tile, null, false);
        }

        /**
         * A watched cell's block entity as noted, saved now: the object keeps its data after the block is gone (a sign
         * popping off), so its text comes back with the undo. A container a cascade destroyed has dropped its items by
         * then and is saved empty (no duplicates; no block's physics destroys one in vanilla).
         */
        private BlockEntityData saved(BlockPos pos, BlockEntity entity) {
            if (entity == null) return null;
            try {
                return McTile.capture(entity, world.registryAccess());
            } catch (RuntimeException e) {
                logCaptureFailure(pos, e);
                return null;
            }
        }

        private BlockEntityData tileAt(BlockPos pos) {
            try {
                BlockEntity entity = world.getBlockEntity(pos);
                return entity == null || entity.isRemoved() ? null : McTile.capture(entity, world.registryAccess());
            } catch (RuntimeException e) {
                logCaptureFailure(pos, e);
                return null;
            }
        }

        private void logCaptureFailure(BlockPos pos, RuntimeException e) {
            if (captureFailureLogged) return;
            captureFailureLogged = true;
            LOG.warn("Sculptory: a block entity at {} could not be saved for the history; it is taken as none",
                    pos.toShortString(), e);
        }

        private static boolean sameTile(BlockEntityData a, BlockEntityData b) {
            if (a == null || b == null) return Objects.equals(a, b);
            return a.sameContent(b);
        }
    }
}
