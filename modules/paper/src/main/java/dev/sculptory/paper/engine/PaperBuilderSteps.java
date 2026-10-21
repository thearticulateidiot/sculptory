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
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.block.CraftBlock;
import org.bukkit.craftbukkit.block.CraftBlockEntityState;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.craftbukkit.inventory.CraftItemStack;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder mode's world steps on Paper ({@link BuilderSteps}), without mixins: CraftBukkit's own block capture, the way
 * Paper places a player's block ({@code ItemStack.useOn}), plus the Bukkit block events, so protection and logging
 * plugins (WorldGuard, CoreProtect) see each builder block as a player's and may cancel it.
 *
 * <ul>
 *   <li><b>Capture.</b> Each step runs with {@code Level.captureBlockStates} on: every {@code setBlock} of the step
 *       notes the cell's state (and block entity) from before its first change as a CraftBukkit block state, changes
 *       the cell, and defers its neighbour updates and client update; block entities the step sets wait in
 *       {@code capturedTileEntities}. So the action knows the first state of every cell it changed, exactly.</li>
 *   <li><b>Events.</b> A placement step then fires {@code BlockPlaceEvent} (one block) or {@code BlockMultiPlaceEvent}
 *       (a door, a bed: every block the step set), with {@code canBuild} true (the engine has checked spawn protection
 *       and the world border already). Cancelled, the step's cells are put back physics-free and the step is
 *       {@link Step#REFUSED}. A break fires {@code BlockBreakEvent} before anything changes, as a player's break does;
 *       cancelled, nothing happens. The cells vanilla's own steps reach beyond the target (a bed's other half) fire no
 *       event of their own, as for a player; the engine checks them against protection afterwards.</li>
 *   <li><b>Physics</b> ({@link Action#finish}): without Keep shape, what each kept cell's change sets off runs then,
 *       as Paper runs it after a placement event ({@code onPlace} and {@code notifyAndUpdatePhysics}), with the capture
 *       still on, so the cells that reacts change (a fence joining, a torch popping off) are noted too and their own
 *       updates run in turn, breadth first, until nothing more changes (at most {@value #MAX_CELLS} cells; past that the
 *       rest runs uncaptured, logged). With Keep shape nothing around reacts: the blocks' replace callbacks do not run
 *       during the step ({@code isBlockPlaceCancelled}), their add callbacks are deferred by the capture and never run,
 *       and each changed cell only tells the clients and the points of interest.</li>
 * </ul>
 *
 * <p>Known differences from Fabric's mixin capture: the cascade runs breadth first rather than depth first (the same
 * end state for shape updates; redstone may settle in another order), a cell changed twice runs the updates of its last
 * state once, and a block entity block ({@code BaseEntityBlock}, such as a hopper) still runs its own add callback during
 * a Keep shape step (CraftBukkit does not defer those).
 */
final class PaperBuilderSteps implements BuilderSteps {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** The most cells one action's cascade of updates is followed through. */
    static final int MAX_CELLS = 8192;

    @Override
    public Action open(ServerLevel world, ServerPlayer player, boolean keepShape) {
        if (world.captureBlockStates || world.captureTreeGeneration) {
            throw new IllegalStateException("another block capture is open on " + world.dimension().location());
        }
        return new PaperAction(world, player, keepShape);
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

    /** A cell as it was before the action first changed it. */
    private record Before(int state, BlockEntityData tile) {
    }

    /** A changed cell whose deferred updates are still to run: its state before the change and the change's flags. */
    private record Pending(BlockPos pos, BlockState old, int flags) {
    }

    /** What one captured step changed: CraftBukkit's block states from before, and the block entities it set. */
    private record Phase(List<CraftBlockState> states, Map<BlockPos, BlockEntity> tiles) {
        boolean isEmpty() {
            return states.isEmpty() && tiles.isEmpty();
        }
    }

    private static final class PaperAction implements Action {
        private final ServerLevel world;
        private final ServerPlayer player;
        private final boolean keepShape;
        private final Long2ObjectLinkedOpenHashMap<Before> befores = new Long2ObjectLinkedOpenHashMap<>();
        private final List<Pending> pending = new ArrayList<>();
        private boolean finished;
        private boolean captureFailureLogged;

        PaperAction(ServerLevel world, ServerPlayer player, boolean keepShape) {
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
            keep(capture(() -> removed[0] = BuilderPlacement.breakBlock(world, player, pos)));
            return removed[0] ? Step.DONE : Step.NOT_DONE;
        }

        @Override
        public List<BuilderChange> finish() {
            if (!finished) {
                finished = true;
                if (keepShape) {
                    // Clients and points of interest hear of each change; nothing around it is updated.
                    for (Pending p : pending) {
                        BlockState now = world.getBlockState(p.pos());
                        int flags = (p.flags() | Block.UPDATE_KNOWN_SHAPE) & ~Block.UPDATE_NEIGHBORS;
                        world.notifyAndUpdatePhysics(p.pos(), null, p.old(), now, now, flags, 0);
                    }
                } else {
                    runUpdates();
                }
            }
            List<BuilderChange> changes = new ArrayList<>();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (Long2ObjectMap.Entry<Before> entry : befores.long2ObjectEntrySet()) {
                pos.set(entry.getLongKey());
                Before before = entry.getValue();
                BlockState now = world.getBlockState(pos);
                int after = Block.getId(now);
                BlockEntityData tile = now.hasBlockEntity() ? tileAt(pos) : null;
                if (after == before.state() && sameTile(before.tile(), tile)) continue;
                changes.add(new BuilderChange(pos.immutable(), before.state(), before.tile(), after, tile));
            }
            return changes;
        }

        /**
         * Runs the deferred updates of every kept cell, breadth first, with the capture on: the cells they change are
         * noted and their own updates run in turn.
         */
        private void runUpdates() {
            for (int i = 0; i < pending.size(); i++) {
                Pending p = pending.get(i);
                if (i >= MAX_CELLS) {
                    LOG.warn("Sculptory: a builder action set off updates in more than {} cells; the rest ran without "
                            + "being recorded for its undo", MAX_CELLS);
                    for (int j = i; j < pending.size(); j++) update(pending.get(j));
                    return;
                }
                keep(capture(() -> update(p)));
            }
        }

        /** What Paper runs after a placement event for each placed cell: the block's add callback, then its updates. */
        private void update(Pending p) {
            BlockState now = world.getBlockState(p.pos());
            // A block entity block ran its add callback during the capture already (CraftBukkit lets it).
            if (now != p.old() && !(now.getBlock() instanceof BaseEntityBlock)) now.onPlace(world, p.pos(), p.old(), false);
            world.notifyAndUpdatePhysics(p.pos(), null, p.old(), now, world.getBlockState(p.pos()), p.flags(), 512);
        }

        /** Runs {@code step} with CraftBukkit's block capture on; returns what it changed (taken out of the world's capture). */
        private Phase capture(Runnable step) {
            boolean cancelled = world.isBlockPlaceCancelled;
            world.captureBlockStates = true;
            // Keep shape: the replaced blocks' own callbacks do not run (nothing drops, no cleanup reacts).
            if (keepShape) world.isBlockPlaceCancelled = true;
            RuntimeException failure = null;
            try {
                step.run();
            } catch (RuntimeException e) {
                failure = e;
            } finally {
                world.captureBlockStates = false;
                world.isBlockPlaceCancelled = cancelled;
            }
            Phase phase = new Phase(new ArrayList<>(world.capturedBlockStates.values()),
                    new LinkedHashMap<>(world.capturedTileEntities));
            world.capturedBlockStates.clear();
            world.capturedTileEntities.clear();
            if (failure != null) {
                // The game failed halfway: what it changed so far stays and is recorded (BuilderService reports
                // FAILED), and the world's capture is left empty for the next one.
                keep(phase);
                throw failure;
            }
            return phase;
        }

        /** Keeps what a step changed: its block entities go into the world, its cells are noted and wait for updates. */
        private void keep(Phase phase) {
            for (BlockEntity entity : phase.tiles().values()) world.setBlockEntity(entity);
            for (CraftBlockState state : phase.states()) {
                BlockPos pos = state.getPosition();
                if (!befores.containsKey(pos.asLong())) befores.put(pos.asLong(), before(state));
                pending.add(new Pending(pos, state.getHandle(), state.getFlag()));
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
            if (befores.containsKey(key)) return;
            BlockState state = world.getBlockState(pos);
            befores.put(key, new Before(Block.getId(state), state.hasBlockEntity() ? tileAt(pos) : null));
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
            return new Before(Block.getId(state.getHandle()), tile);
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
