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
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StructureBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.WitherSkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.StructureMode;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
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
import org.bukkit.event.block.BlockRedstoneEvent;
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
 *       changes has been noted before it changes. A noted cell's block entity is saved when an update reaches the
 *       cell, before it reacts (a crafter sets its block and its block entity's {@code triggered} in one reaction;
 *       block entities react only to neighbour updates); one no update reached is saved at the end from the object
 *       noted (a sign popping off keeps its data), so a wall of full chests beside the action costs nothing. The
 *       action's own targets and emptied containers are saved at once. At the end every noted cell whose state
 *       differs is a change of the action. Two blocks change cells far from any update, before their own events: a
 *       tripwire hook a string's or hook's change reaches sets the opposite hook and the strings between, up to 41
 *       blocks away, so whenever a string or a hook is noted, the cells along its line (both ways along each
 *       horizontal axis of a string, along a hook's axis) are noted too, 42 blocks each way; and a structure block in
 *       LOAD mode, powered, places its template at once (emptying the containers under it first, without an event),
 *       so when the physics event reaches such a block as the update that powers it (just before it loads), the
 *       template's box is noted, each cell with its block entity saved and counting as changed when only its data
 *       changed. Not followed: what other plugins write from inside the events these physics fire (a listener may
 *       change any cell anywhere); it stays and is not undone with the action.
 *       At most {@value #DEFAULT_MAX_WATCHED_CELLS} cells are noted around the physics per action; past that (a long
 *       redstone contraption, a large template) the rest of the cascade still runs, but what it changes further out is
 *       not recorded for the undo (logged). The action's own targets and the containers it empties are always noted,
 *       cap or not, and the cells around the action's own changes come before any load box (a box is noted only once
 *       the physics power its block); a load box that fills the cap still leaves later cells of the same cascade, and
 *       the later targets of a break message, unrecorded.</li>
 *   <li><b>Keep shape:</b> nothing around reacts: the replaced blocks' callbacks do not run during the step
 *       ({@code isBlockPlaceCancelled}), their add callbacks are deferred by the capture and never run, and each
 *       changed cell only tells the clients and the points of interest. Breaks run captured for this.</li>
 * </ul>
 *
 * <p><b>Events and the history.</b> The place and break events fire for every step, also for one the engine puts back
 * afterwards (a secondary cell another protection refuses, the global mask, a failure), and undo and redo fire none:
 * a logging plugin sees the builder action as placed and broken by the player, and does not see the undo.
 *
 * <p><b>Refused after the physics.</b> The cells the physics changed are part of the action's changes, so the engine
 * checks them against protection and the global mask like the action's own: a lamp reacting in a protected or masked
 * cell refuses the whole action, which is then put back physics-free. What the physics did that is not a block change
 * (items dropped, ticks scheduled, entities) stays. This is Fabric's rule too.
 *
 * <p><b>An Error in a step</b> ends the action through {@link Action#abort}: the capture and the physics listener are
 * released and the changed cells given for the revert, without running the deferred physics (no golem or wither built,
 * nothing primed) of half-done work. <b>A RuntimeException in the deferred physics</b> leaves the action as it stands
 * (recorded, reported FAILED): the changed cells whose updates did not run yet only tell the clients and the points of
 * interest, as with Keep shape.
 *
 * <p>Known differences from Fabric's mixin capture: what a replaced block's own removal sets off beyond its neighbours
 * does not run when a placement replaces it (Replace over a powered lever: its attached block's neighbours keep their
 * power), as for a Paper player placing into a replaceable block; a block entity block ({@code BaseEntityBlock}, such as
 * a hopper) still runs its own add callback during a Keep shape step (CraftBukkit does not defer those); with Keep shape
 * a wither skull completing the pattern builds no wither (its pattern could only be cleared with physics); with
 * Paper's alternate redstone implementations (Eigencraft, Alternate Current), which set a network's wires without
 * physics events, each wire and the cells around it are noted at the wire power event fired before it changes, but
 * Eigencraft updates the blocks beside its wires directly (no event): a block entity reacting there (a crafter) is
 * saved only at the end, after it reacted; a block entity a cascade changes without changing the block (a command
 * block's powered flag) is not recorded, as on Fabric.
 */
public final class PaperBuilderSteps implements BuilderSteps {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /**
     * The most cells one action notes around its physics, by default: each a state and a reference, roughly 100 bytes
     * (a block entity is saved only for a cell an update reaches before it reacts, or that changed).
     */
    public static final int DEFAULT_MAX_WATCHED_CELLS = 32768;
    /** How far around a changed or updated cell the cells are noted before they may change. */
    static final int WATCH_RADIUS = 2;
    /** How far along a tripwire's line the cells are noted: a hook finds its opposite hook up to 41 blocks away. */
    static final int TRIPWIRE_REACH = 42;

    private final Plugin plugin;
    private final int maxWatchedCells;
    private final Consumer<BlockPos> beforeUpdate;

    /** {@code plugin} owns the physics listener an action registers while it watches (PaperEngine; the self-test). */
    public PaperBuilderSteps(Plugin plugin) {
        this(plugin, DEFAULT_MAX_WATCHED_CELLS);
    }

    /** With another cap on the cells noted around an action's physics (the self-test checks what happens past it). */
    public PaperBuilderSteps(Plugin plugin, int maxWatchedCells) {
        this(plugin, maxWatchedCells, null);
    }

    /**
     * With {@code beforeUpdate} called before each changed cell's deferred updates run (null: none). Only the self-test
     * uses it, through {@link SelfTestSteps}, to make the deferred physics fail halfway.
     */
    PaperBuilderSteps(Plugin plugin, int maxWatchedCells, Consumer<BlockPos> beforeUpdate) {
        if (maxWatchedCells < 0) throw new IllegalArgumentException("a negative cap: " + maxWatchedCells);
        this.plugin = Objects.requireNonNull(plugin);
        this.maxWatchedCells = maxWatchedCells;
        this.beforeUpdate = beforeUpdate;
    }

    @Override
    public Action open(ServerLevel world, ServerPlayer player, boolean keepShape) {
        if (world.captureBlockStates || world.captureTreeGeneration) {
            throw new IllegalStateException("another block capture is open on " + world.dimension().location());
        }
        return new PaperAction(plugin, maxWatchedCells, beforeUpdate, world, player, keepShape);
    }

    /**
     * Puts the changes back physics-free. For the changes an aborted action gave ({@link Action#abort}), the cells its
     * captured steps changed whose deferred updates never ran are put back without touching points of interest, as
     * Paper's own revert of a cancelled placement does: they never registered theirs (removing one now would log a
     * point-of-interest mismatch).
     */
    @Override
    public void revert(ServerLevel world, List<BuilderChange> changes) {
        LongSet quiet = changes instanceof AbortedChanges aborted ? aborted.quiet : null;
        for (int i = changes.size() - 1; i >= 0; i--) {
            BuilderChange change = changes.get(i);
            boolean prevent = world.preventPoiUpdated;
            if (quiet != null && quiet.contains(change.pos().asLong())) world.preventPoiUpdated = true;
            try {
                putBack(world, change.pos(), Block.stateById(change.before()));
                change.putBackTile(world, Block.stateById(change.before()));
            } finally {
                world.preventPoiUpdated = prevent;
            }
            world.getChunkSource().blockChanged(change.pos());
        }
    }

    /**
     * The changes an aborted action gave, with the cells whose deferred updates never ran ({@code quiet}). The set
     * travels with the list, so nothing is kept for an aborted action whose changes are never reverted.
     */
    private static final class AbortedChanges extends ArrayList<BuilderChange> {
        private final LongSet quiet;

        AbortedChanges(List<BuilderChange> changes, LongSet quiet) {
            super(changes);
            this.quiet = quiet;
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
     * A cell as it was before the action first changed it: its block entity saved ({@code tile}), or for a cell only
     * noted around the physics ({@code watched}) possibly only referenced ({@code entity}) until an update reaches it.
     * A watched cell counts as changed only when its block did (as on Fabric, whose hook sees block changes).
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
        private final int maxWatchedCells;
        private final Consumer<BlockPos> beforeUpdate;
        /** The pending cells whose deferred updates (or Keep shape notification) have run to the end. */
        private final LongOpenHashSet notified = new LongOpenHashSet();
        private final ServerLevel world;
        private final ServerPlayer player;
        private final boolean keepShape;
        private final Long2ObjectLinkedOpenHashMap<Before> befores = new Long2ObjectLinkedOpenHashMap<>();
        /** The cells the captured steps changed, each once, in the order first changed. */
        private final Long2ObjectLinkedOpenHashMap<Pending> pending = new Long2ObjectLinkedOpenHashMap<>();
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        /** The last physics event's cell and source (BlockPos keys), to skip its immediate repeat. */
        private long lastEventCell = Long.MIN_VALUE;
        private long lastEventSource = Long.MIN_VALUE;
        /** The structure blocks whose load boxes this action noted (each once). */
        private final LongOpenHashSet loadsNoted = new LongOpenHashSet();
        /** The physics listener while the action watches, else null. */
        private RegisteredListener watch;
        private boolean physicsEventBefore;
        private int watched;
        private boolean watchFull;
        private boolean finished;
        private boolean captureFailureLogged;
        private LevelChunk lastChunk;

        PaperAction(Plugin plugin, int maxWatchedCells, Consumer<BlockPos> beforeUpdate, ServerLevel world,
                    ServerPlayer player, boolean keepShape) {
            this.plugin = plugin;
            this.maxWatchedCells = maxWatchedCells;
            this.beforeUpdate = beforeUpdate;
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
            // The target itself is always noted, whatever the cascade of an earlier target of the message noted.
            note(pos);
            noteTripwireLine(pos, world.getBlockState(pos));
            watchAround(pos);
            removed[0] = BuilderPlacement.breakBlock(world, player, pos);
            // The break's own updates have run: an emptied container's deferred ones are not run again.
            pending.remove(pos.asLong());
            return removed[0] ? Step.DONE : Step.NOT_DONE;
        }

        @Override
        public List<BuilderChange> finish() {
            return end(true);
        }

        /** After an Error in a step: the deferred physics of the half-done work do not run (see the class doc). */
        @Override
        public List<BuilderChange> abort() {
            return end(false);
        }

        private List<BuilderChange> end(boolean physics) {
            if (!finished) {
                finished = true;
                try {
                    if (physics && keepShape) {
                        notifyOnly();
                    } else if (physics) {
                        runUpdates();
                    }
                } catch (RuntimeException e) {
                    // The deferred physics failed halfway: the action is recorded as it stands (BuilderService reports
                    // it FAILED, its step undoable). The changed cells whose updates did not run still reach the
                    // clients and the points of interest, so the step's undo finds a bed's point registered.
                    notifyRest(e);
                    throw e;
                } finally {
                    stopWatching();
                    // Also when the physics failed: what they changed past the cap is not undone either way.
                    if (watchFull) {
                        LOG.warn("Sculptory: a builder action set off updates beyond the {} cells followed per action; "
                                + "what they changed further out is not undone with it", maxWatchedCells);
                    }
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
                // A watched cell not snapshotted before it changed (no update reached it): the object noted, saved now.
                BlockEntityData beforeTile = beforeTile(pos, before);
                BlockEntityData tile = now.hasBlockEntity() ? tileAt(pos) : null;
                if (after == before.state() && sameTile(beforeTile, tile)) continue;
                changes.add(new BuilderChange(pos.immutable(), before.state(), beforeTile, after, tile));
            }
            if (!physics) {
                // Aborted: the cells whose deferred updates never ran are put back without touching points of interest.
                LongSet quiet = new LongOpenHashSet();
                for (long key : pending.keySet()) {
                    if (!notified.contains(key)) quiet.add(key);
                }
                if (!quiet.isEmpty()) return new AbortedChanges(changes, quiet);
            }
            return changes;
        }

        /** Keep shape: clients and points of interest hear of each changed cell; nothing around it is updated. */
        private void notifyOnly() {
            for (Pending p : pending.values()) notifyQuietly(p);
        }

        /**
         * After the deferred physics failed: each changed cell whose updates did not run to the end is notified as with
         * Keep shape (no neighbour updates, no add callback: nothing more of the failed physics runs). What fails here
         * is added to {@code failure}.
         */
        private void notifyRest(RuntimeException failure) {
            for (Pending p : pending.values()) {
                if (notified.contains(p.pos().asLong())) continue;
                try {
                    notifyQuietly(p);
                } catch (RuntimeException e) {
                    failure.addSuppressed(e);
                }
            }
        }

        /** The clients and the points of interest hear of the cell's change; nothing around it is updated. */
        private void notifyQuietly(Pending p) {
            BlockState now = world.getBlockState(p.pos());
            int flags = (p.flags() | Block.UPDATE_KNOWN_SHAPE) & ~Block.UPDATE_NEIGHBORS;
            world.notifyAndUpdatePhysics(p.pos(), null, p.old(), now, now, flags, 0);
            notified.add(p.pos().asLong());
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
            for (Pending p : pending.values()) {
                // A string or hook placed or replaced: its far hook is reached by the add or replace callback.
                noteTripwireLine(p.pos(), p.old());
                noteTripwireLine(p.pos(), world.getBlockState(p.pos()));
                watchAround(p.pos());
            }
            for (Pending p : pending.values()) {
                if (beforeUpdate != null) beforeUpdate.accept(p.pos());
                update(p);
            }
            for (Pending p : pending.values()) {
                BlockState now = world.getBlockState(p.pos());
                if (now.is(Blocks.WITHER_SKELETON_SKULL) || now.is(Blocks.WITHER_SKELETON_WALL_SKULL)) {
                    WitherSkullBlock.checkSpawn(world, p.pos());
                }
            }
        }

        /**
         * What Paper runs after a placement event for each placed cell: the block's add callback, then its updates. The
         * cell counts as notified only once they ran to the end: the point of interest is registered last
         * ({@code notifyAndUpdatePhysics}), so a cell whose updates threw has not registered it.
         */
        private void update(Pending p) {
            BlockState now = world.getBlockState(p.pos());
            if (now == p.old()) {
                // Changed and changed back by the steps: nothing to set off; the clients still hear (they were not told).
                world.getChunkSource().blockChanged(p.pos());
            } else {
                // A block entity block ran its add callback during the capture already (CraftBukkit lets it).
                if (!(now.getBlock() instanceof BaseEntityBlock)) now.onPlace(world, p.pos(), p.old(), false);
                world.notifyAndUpdatePhysics(p.pos(), null, p.old(), now, world.getBlockState(p.pos()), p.flags(), 512);
            }
            notified.add(p.pos().asLong());
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
            // Paper's Eigencraft redstone sets wires and updates their neighbours without physics events; the wire
            // power event still fires before each wire changes (wireChanging).
            BlockRedstoneEvent.getHandlerList().register(watch);
            world.hasPhysicsEvent = true;
        }

        private void stopWatching() {
            if (watch == null) return;
            BlockPhysicsEvent.getHandlerList().unregister(watch);
            BlockRedstoneEvent.getHandlerList().unregister(watch);
            watch = null;
            // What the server would decide at the next tick: another plugin may have started listening meanwhile.
            world.hasPhysicsEvent = physicsEventBefore
                    || BlockPhysicsEvent.getHandlerList().getRegisteredListeners().length > 0;
        }

        /**
         * The physics listener: the cell the update reaches and those around it are noted before it reacts, then the
         * cell's own block entity is saved if it is still as noted (a block entity reacts only to a neighbour update,
         * whose event fires at its cell before it does: a crafter sets its {@code triggered} with its block). Noting
         * comes first, so a cell first reached by its own event is saved too (Paper fires the event twice for each
         * neighbour of a multi-neighbour update, a single neighbour update fires it once). A structure block this
         * update is about to make load its template has its load box noted now ({@link #noteLoad}): the cell itself
         * unless the event is for its own change, and its six neighbours unless the action placed them.
         */
        @Override
        public void execute(Listener listener, Event event) {
            if (event instanceof BlockRedstoneEvent redstone) {
                wireChanging(redstone);
                return;
            }
            if (!(event instanceof BlockPhysicsEvent physics)) return;
            if (!(physics.getBlock() instanceof CraftBlock block) || block.getHandle() != world) return;
            BlockPos pos = block.getPosition();
            long key = pos.asLong();
            org.bukkit.block.Block source = physics.getSourceBlock();
            long sourceKey = BlockPos.asLong(source.getX(), source.getY(), source.getZ());
            // A multi-neighbour update fires the event twice in a row for each neighbour (runNext, then executeUpdate)
            // with nothing changed between: the second is the first again.
            if (key == lastEventCell && sourceKey == lastEventSource) return;
            lastEventCell = key;
            lastEventSource = sourceKey;
            watchAround(pos);
            Before noted = befores.get(key);
            if (noted != null && noted.watched() && noted.entity() != null && noted.tile() == null
                    && Block.getId(world.getBlockState(pos)) == noted.state()) {
                befores.put(key, new Before(noted.state(), saved(pos, noted.entity()), noted.entity(), true));
            }
            // The event Paper fires for a cell's own change (Level.notifyAndUpdatePhysics; also a plant checking its
            // own shape) names the cell as its own source; a neighbour update names the neighbour that sent it. The
            // game never runs a block's neighbour reaction for its own change, so a structure block placed beside
            // power does not load (and its box is not noted) until a neighbour updates it.
            if (sourceKey == key) return;
            noteLoad(pos, true);
            // A block powered without an event of its own (Eigencraft's wires update their neighbours directly):
            // tested at its neighbours' events, as long as the action did not place it (its own placement does not
            // make it load; see above). A signal present without an update ever arriving notes a box that never loads.
            // Not at a cell's own event: what changed there sends its neighbours updates of their own.
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (!pending.containsKey(next.asLong())) noteLoad(next, true);
            }
        }

        /**
         * Paper's wire power event, fired before a redstone wire's power changes, with every redstone implementation:
         * the wire and the cells around it are noted before it changes. With Eigencraft, which sets the wires of a
         * network without physics events and updates the 24 cells around each changed wire directly (whether it gains
         * or loses power), the structure blocks in LOAD mode within 2 blocks are tested here too: beside a wire gaining
         * power its box is noted without a signal to test (not there yet; a block the wire does not point at notes a
         * box that never loads), beside one losing power only with a signal from elsewhere (an armed block loads on
         * any update). The other implementations update those cells through the neighbour updater, whose physics
         * events test the signal, so they note nothing here.
         */
        private void wireChanging(BlockRedstoneEvent event) {
            if (!(event.getBlock() instanceof CraftBlock block) || block.getHandle() != world) return;
            BlockPos pos = block.getPosition();
            if (!world.getBlockState(pos).is(Blocks.REDSTONE_WIRE)) return;
            lastEventCell = Long.MIN_VALUE;
            watchAround(pos);
            if (world.paperConfig().misc.redstoneImplementation
                    != io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.EIGENCRAFT) {
                return;
            }
            if (event.getNewCurrent() == event.getOldCurrent()) return;
            boolean gaining = event.getNewCurrent() > event.getOldCurrent();
            BlockPos.MutableBlockPos near = new BlockPos.MutableBlockPos();
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 2) continue;
                        noteLoad(near.setWithOffset(pos, dx, dy, dz).immutable(), !gaining);
                    }
                }
            }
        }

        /**
         * Notes every loaded cell within {@value #WATCH_RADIUS} blocks of {@code center} not noted yet, as it is now
         * (a string or hook among them: its line too).
         */
        private void watchAround(BlockPos center) {
            if (watchFull) return;
            for (int dy = -WATCH_RADIUS; dy <= WATCH_RADIUS; dy++) {
                int y = center.getY() + dy;
                if (world.isOutsideBuildHeight(y)) continue;
                for (int dz = -WATCH_RADIUS; dz <= WATCH_RADIUS; dz++) {
                    for (int dx = -WATCH_RADIUS; dx <= WATCH_RADIUS; dx++) {
                        BlockState state = noteWatched(center.getX() + dx, y, center.getZ() + dz);
                        if (watchFull) return;
                        if (state != null) {
                            noteTripwireLine(new BlockPos(center.getX() + dx, y, center.getZ() + dz), state);
                        }
                    }
                }
            }
        }

        /**
         * Pre-notes a tripwire's line when {@code state} at {@code pos} is a string (both ways along both horizontal
         * axes) or a hook (both ways along its axis), {@value #TRIPWIRE_REACH} cells each way: a hook's update sets the
         * opposite hook and the strings between before their own events fire.
         */
        private void noteTripwireLine(BlockPos pos, BlockState state) {
            List<Direction> directions;
            if (state.is(Blocks.TRIPWIRE)) {
                directions = List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST);
            } else if (state.getBlock() instanceof TripWireHookBlock) {
                Direction facing = state.getValue(TripWireHookBlock.FACING);
                directions = List.of(facing, facing.getOpposite());
            } else {
                return;
            }
            for (Direction direction : directions) {
                for (int i = 1; i <= TRIPWIRE_REACH && !watchFull; i++) {
                    noteWatched(pos.getX() + direction.getStepX() * i, pos.getY(),
                            pos.getZ() + direction.getStepZ() * i);
                }
            }
        }

        /**
         * Notes loaded cell (x, y, z) as it is now, with a reference to its block entity (saved when an update reaches
         * the cell, {@link #execute}, or at the end if the block changed without one), unless noted already or the
         * action's cap is reached (then {@link #watchFull}). Returns the state noted now, or null.
         */
        private BlockState noteWatched(int x, int y, int z) {
            cursor.set(x, y, z);
            long key = cursor.asLong();
            if (befores.containsKey(key)) return null;
            LevelChunk chunk = loadedChunk(x >> 4, z >> 4);
            if (chunk == null) return null;
            if (watched >= maxWatchedCells) {
                watchFull = true;
                return null;
            }
            BlockState state = chunk.getBlockState(cursor);
            BlockEntity entity = state.hasBlockEntity()
                    ? chunk.getBlockEntity(cursor, LevelChunk.EntityCreationType.CHECK) : null;
            befores.put(key, new Before(Block.getId(state), null, entity, true));
            watched++;
            return state;
        }

        /**
         * If {@code pos} holds a structure block in LOAD mode that is about to be powered, notes the box of the template
         * it names (once per block and action). {@code StructureBlock.neighborChanged} loads when the cell has a signal
         * and its block entity was not powered yet; with {@code signal} that is tested here (at the physics event, right
         * before the block reacts, or at a neighbour's event), without it the caller knows power is on its way (a wire
         * gaining power beside it, whose signal is not set yet). The template's cells fire no event before they change
         * (a cell with block entity data is emptied, set to a barrier without updates, then set), so each is noted with
         * its block entity saved, and counts as changed when only its data changed (a chest the template puts back
         * empty). Decided where the power arrives, so it holds however it travels (a tripwire hook far down a line, a
         * block in another load box, a wire) and for a template a SAVE block made earlier in the cascade; an unpowered
         * LOAD block nearby notes nothing. Only loaded cells are read (a block at a chunk's edge whose signal would read
         * an unloaded chunk is left out). The cap bounds the box like any other note.
         */
        private void noteLoad(BlockPos pos, boolean signal) {
            for (int dx = -2; dx <= 2; dx += 4) {
                for (int dz = -2; dz <= 2; dz += 4) {
                    if (loadedChunk((pos.getX() + dx) >> 4, (pos.getZ() + dz) >> 4) == null) return;
                }
            }
            if (world.isOutsideBuildHeight(pos.getY())) return;
            BlockState state = world.getBlockState(pos);
            if (!(state.getBlock() instanceof StructureBlock)) return;
            if (!(world.getBlockEntity(pos) instanceof StructureBlockEntity structure)) return;
            if (structure.getMode() != StructureMode.LOAD || structure.isPowered()) return;
            if (loadsNoted.contains(pos.asLong()) || (signal && !world.hasNeighborSignal(pos))) return;
            loadsNoted.add(pos.asLong());
            ResourceLocation name = structure.hasStructureName()
                    ? ResourceLocation.tryParse(structure.getStructureName()) : null;
            StructureTemplate template = name == null ? null : world.getStructureManager().get(name).orElse(null);
            if (template == null) return;
            BoundingBox box = template.getBoundingBox(new StructurePlaceSettings().setMirror(structure.getMirror())
                    .setRotation(structure.getRotation()), pos.offset(structure.getStructurePos()));
            for (int y = box.minY(); y <= box.maxY() && !watchFull; y++) {
                if (world.isOutsideBuildHeight(y)) continue;
                for (int z = box.minZ(); z <= box.maxZ() && !watchFull; z++) {
                    for (int x = box.minX(); x <= box.maxX() && !watchFull; x++) noteSaved(x, y, z);
                }
            }
        }

        /**
         * Notes loaded cell (x, y, z) as it is now with its block entity saved, as a cell of the action's own (a change
         * of its block entity alone counts). A cell noted around the physics before becomes one too, unless it changed
         * since without its block entity saved (it stays watched and is saved at the end from the object noted).
         * Counts against the cap like {@link #noteWatched}.
         */
        private void noteSaved(int x, int y, int z) {
            cursor.set(x, y, z);
            long key = cursor.asLong();
            Before known = befores.get(key);
            if (known != null) {
                if (!known.watched()) return;
                boolean changed = Block.getId(world.getBlockState(cursor)) != known.state();
                if (changed && known.tile() == null && known.entity() != null) return;
                befores.put(key, new Before(known.state(), beforeTile(cursor.immutable(), known), null, false));
                return;
            }
            LevelChunk chunk = loadedChunk(x >> 4, z >> 4);
            if (chunk == null) return;
            if (watched >= maxWatchedCells) {
                watchFull = true;
                return;
            }
            BlockState state = chunk.getBlockState(cursor);
            BlockEntity entity = state.hasBlockEntity()
                    ? chunk.getBlockEntity(cursor, LevelChunk.EntityCreationType.CHECK) : null;
            Before now = new Before(Block.getId(state), null, entity, true);
            befores.put(key, new Before(now.state(), beforeTile(cursor.immutable(), now), null, false));
            watched++;
        }

        /**
         * A noted cell's block entity data from before the action: saved already, else the object noted, saved now;
         * else, for a cell read as it was ({@code watched}: its block has a block entity the chunk had not made yet), a
         * new block entity's defaults. That is what the chunk would have made for the block then; waiting for the object
         * to appear instead would take whatever the cascade wrote into it as the data from before. A cell of the
         * action's own whose data could not be saved (the failure is logged) stays without data.
         */
        private BlockEntityData beforeTile(BlockPos pos, Before before) {
            if (before.tile() != null) return before.tile();
            if (before.entity() != null) return saved(pos, before.entity());
            if (!before.watched()) return null;
            BlockState state = Block.stateById(before.state());
            if (!state.hasBlockEntity() || !(state.getBlock() instanceof EntityBlock block)) return null;
            return saved(pos, block.newBlockEntity(pos, state));
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
         * A noted cell's block entity, saved as it is now (before anything reacts), so the undo puts back the data that
         * went with the block's first state.
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
            LOG.warn("Sculptory: a block entity at {} could not be saved for the history; the cell is recorded "
                    + "without its data", pos.toShortString(), e);
        }

        private static boolean sameTile(BlockEntityData a, BlockEntityData b) {
            if (a == null || b == null) return Objects.equals(a, b);
            return a.sameContent(b);
        }
    }
}
