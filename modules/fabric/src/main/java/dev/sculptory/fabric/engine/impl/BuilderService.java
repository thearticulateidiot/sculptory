package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.builder.BuilderCapture;
import dev.sculptory.fabric.builder.BuilderPlacement;
import dev.sculptory.fabric.builder.BuilderPlacementContext;
import dev.sculptory.fabric.config.SculptoryConfig;
import dev.sculptory.fabric.engine.BuilderOutcome;
import dev.sculptory.fabric.engine.BuilderOutcome.Refusal;
import dev.sculptory.fabric.engine.ChunkPermit;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.perm.FabricPermissionService;
import dev.sculptory.fabric.world.EditScope;
import dev.sculptory.fabric.world.FabricTile;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.fabric.world.FluidTrails;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.TokenBucket;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.OperatorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Clearable;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder mode on the server: the placements and breaks a player makes in
 * normal creative play with a power on, carried out through the engine. Server thread only; owned by
 * {@link EngineEditService}.
 *
 * <p><b>Who may.</b> Every action needs editing enabled, {@code sculptory.use} and {@code sculptory.builder}, and
 * Creative mode. The target (the clicked block, or the empty cell Place in air aims at, and every cell a drag breaks)
 * must lie within {@code builder.maxReach} (64) blocks of the player's eyes, plus {@value #REACH_MARGIN}; the cell
 * written must be loaded, inside the build height and the world border, writable by the player ({@code ChunkPermit}:
 * spawn protection, claims) and not held by an editor job. A refused action writes nothing and says why
 * ({@link BuilderOutcome}). Mirrored copies are checked each on its own: one that fails is skipped, the others are
 * written. Cells vanilla's own steps reach beyond those (a bed's head, a door's upper half, the base of a broken
 * piston head) are checked once the action ran: one the player may not write puts everything the action changed back
 * and refuses it. Replace takes an operator block, and Force place puts one down, only for a level-2 creative op. Every block written, copies included, spends one token of the player's budget
 * ({@code builder.maxBlocksPerSecond}, bursts of twice that); an action over it is refused whole.
 *
 * <p><b>Writes</b> are vanilla's own placement and creative-break steps ({@link BuilderPlacement}) inside a
 * {@link BuilderCapture}, which records every cell they change, the door's upper half and the fence that joined
 * included: exact undo of whatever vanilla did in that call. Keep shape makes them physics-free (the capture's
 * {@code FORCE_STATE} writes and an {@link EditScope}), and clears scheduled ticks at the changed cells as the editor's
 * writes do. Containers are emptied (into the record, not onto the ground) before they are broken or replaced, so nothing
 * drops and undo cannot duplicate items. Fluid the action leaves is its entry's ({@link FluidTrails}).
 *
 * <p><b>History.</b> A placement is one entry ("Place · 3 blocks", "Replace · 1 block"), pushed at once. Breaks
 * belong to a drag: a click is a drag of one message; a bulldozer drag's messages build one record, which becomes one
 * entry when the drag ends ({@code last}, {@code BuilderDragEnd}, another drag, a placement, an editor edit or undo
 * ({@link #commitDrag}), {@value #DRAG_IDLE_SECONDS} s without breaks, the player leaving, the server stopping). While a
 * drag is open its record is an edit in progress for the history: saved as it grows and before its chunks are saved, and
 * undo waits for it. Actions go after the player's brush strokes in the history ({@code commitStroke}); one refused
 * because a large stroke is still being written is {@link Refusal#BUSY}.
 *
 * <p><b>Long reach</b> adds a transient modifier ({@value #LONG_REACH_PATH}) that raises the player's block interaction
 * range to 64 while the player holds the power and may use builder mode, so vanilla's aiming, outline and interactions
 * reach as far; it is removed as soon as they may not (checked every tick for the game mode and the modifier, every
 * {@value #RECHECK_TICKS} ticks and on op changes for the nodes), and when they leave.
 */
final class BuilderService {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** Added to the reach in the distance check, as vanilla adds to its own. */
    static final double REACH_MARGIN = 1.0;
    static final int DRAG_IDLE_SECONDS = 5;
    static final long DRAG_IDLE_NANOS = DRAG_IDLE_SECONDS * 1_000_000_000L;
    static final String LONG_REACH_PATH = "long_reach";
    static final Identifier LONG_REACH = Identifier.of("sculptory", LONG_REACH_PATH);
    /** Server ticks between re-checks of the nodes of players holding Long reach. */
    static final int RECHECK_TICKS = 40;

    private final EngineEditService edits;
    private final MinecraftServer server;
    /** The config in effect ({@code /sculptory reload} replaces it): read whenever a check runs. */
    private final Supplier<SculptoryConfig> configs;
    private final FabricPermissionService permissions;
    private final EditExecutor executor;
    private final HistoryService history;
    private final FluidTrails trails;
    private final StateSpace states;
    private final LongSupplier clock;
    private final Map<UUID, PlayerState> players = new HashMap<>();
    private int ticksSinceRecheck;

    /** One player's powers, budget and open drag. */
    private static final class PlayerState {
        int powers;
        /** Blocks per second the budget was made for (a reload may change it). */
        int budgetRate;
        TokenBucket budget;
        Drag drag;
        /** The player may use builder mode, as last checked (nodes and editing enabled; not the game mode). */
        boolean allowed = true;

        PlayerState() {}
    }

    /** The breaks of one click or drag, becoming one history entry. */
    private static final class Drag {
        final int dragId;
        final ServerWorld world;
        final String worldId;
        final HistoryService.Session session;
        final String label;
        final RecordBuilder record = new RecordBuilder();
        /** The record as the history saves it while the drag is open (null when history is not saved). */
        HistoryService.OpenRecord open;
        /** The kind of the drag's first block broken, for same-kind drags. */
        Block firstKind;
        boolean frozen;
        long lastActivity;

        Drag(int dragId, ServerWorld world, String worldId, HistoryService.Session session, String label, long now) {
            this.dragId = dragId;
            this.world = world;
            this.worldId = worldId;
            this.session = session;
            this.label = label;
            this.lastActivity = now;
        }
    }

    BuilderService(EngineEditService edits, Supplier<SculptoryConfig> configs, FabricPermissionService permissions,
                   EditExecutor executor, HistoryService history, FluidTrails trails, StateSpace states,
                   LongSupplier clock) {
        this.edits = Objects.requireNonNull(edits);
        this.server = edits.server();
        this.configs = Objects.requireNonNull(configs);
        this.permissions = Objects.requireNonNull(permissions);
        this.executor = Objects.requireNonNull(executor);
        this.history = Objects.requireNonNull(history);
        this.trails = Objects.requireNonNull(trails);
        this.states = Objects.requireNonNull(states);
        this.clock = Objects.requireNonNull(clock);
    }

    // ================================================================== powers and Long reach

    void powers(ServerPlayerEntity p, int powers) {
        if (!BuilderPower.valid(powers)) return;
        PlayerState state = state(p);
        state.powers = powers;
        state.allowed = mayUse(p);
        updateReach(p, state);
    }

    /** The powers the player last reported (0 when none or unknown). */
    int powersOf(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? 0 : state.powers;
    }

    /** Re-reads whether the player may use builder mode (op changes) and applies it to Long reach at once. */
    void permissionsChanged(ServerPlayerEntity p) {
        PlayerState state = players.get(p.getUuid());
        if (state == null) return;
        state.allowed = mayUse(p);
        updateReach(p, state);
    }

    private boolean mayUse(ServerPlayerEntity p) {
        return config().editingEnabled && permissions.has(p, Perm.USE) && permissions.has(p, Perm.BUILDER);
    }

    /** Adds or removes the Long reach modifier to match the power, the game mode and the permission. */
    private void updateReach(ServerPlayerEntity p, PlayerState state) {
        EntityAttributeInstance range = p.getAttributeInstance(EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE);
        if (range == null) return;
        boolean wanted = BuilderPower.LONG_REACH.in(state.powers) && state.allowed && p.isCreative();
        EntityAttributeModifier held = range.getModifier(LONG_REACH);
        // Clamped by the attribute to 64 (its maximum); builder.maxReach may be less (and a reload may change it).
        double add = Math.max(0, config().builder.maxReach - range.getBaseValue());
        if (held != null && (!wanted || held.value() != add)) {
            range.removeModifier(LONG_REACH);
            held = null;
        }
        if (wanted && held == null) {
            range.addTemporaryModifier(new EntityAttributeModifier(LONG_REACH, add, EntityAttributeModifier.Operation.ADD_VALUE));
        }
    }

    // ================================================================== placing

    BuilderOutcome place(ServerPlayerEntity p, C2S.BuilderPlace m) {
        Refusal gate = gate(p);
        if (gate != null) return BuilderOutcome.refused(gate, "");
        BoundMask mask = mask(p);
        if (mask == null) return BuilderOutcome.refused(Refusal.INVALID, EditMasks.REFUSED_DETAIL);
        ServerWorld world = p.getServerWorld();
        executor.predicted(p.getUuid());
        boolean replace = BuilderPower.REPLACE.in(m.powers());
        boolean force = BuilderPower.FORCE_PLACE.in(m.powers());
        boolean keepShape = BuilderPower.KEEP_SHAPE.in(m.powers());
        Symmetry symmetry = BuilderPower.MIRROR.in(m.powers()) ? m.symmetry() : Symmetry.NONE;
        BlockPos target = new BlockPos(m.pos().x(), m.pos().y(), m.pos().z());
        if (!withinReach(p, target)) return BuilderOutcome.refused(Refusal.OUT_OF_REACH, target.toShortString());
        if (!EngineEditService.insideWorld(symmetry)) return BuilderOutcome.refused(Refusal.SYMMETRY_OUTSIDE, "");
        Direction side = direction(m.side());
        // Deciding reads the target, the cell beside it and their neighbours (a fence looks around): all loaded, never
        // loaded by the read.
        if (!neighbourhoodLoaded(world, target) || !neighbourhoodLoaded(world, target.offset(side))) {
            return BuilderOutcome.refused(Refusal.UNLOADED, target.toShortString());
        }
        Vec3d hitPos = new Vec3d(target.getX() + m.hitX(), target.getY() + m.hitY(), target.getZ() + m.hitZ());
        Hand hand = m.offHand() ? Hand.OFF_HAND : Hand.MAIN_HAND;
        BuilderPlacement.Outcome decided = BuilderPlacement.decide(world, p, hand, new BlockHitResult(hitPos, side, target,
                false), replace, force);
        if (decided.refusal() != null) return BuilderOutcome.refused(refusal(decided.refusal()), "");
        BuilderPlacement.Decision decision = decided.decision();
        BlockPos cell = decision.pos();
        Refusal blocked = writable(p, world, cell);
        if (blocked != null) return BuilderOutcome.refused(blocked, cell.toShortString());
        if (replace && !mayReplace(p, world, cell)) return BuilderOutcome.refused(Refusal.CANNOT_BREAK, cell.toShortString());
        List<BuilderPlacement.Copy> copies = BuilderPlacement.copies(symmetry, cell);
        Refusal order = followStrokes(p);
        if (order != null) return BuilderOutcome.refused(order, "");
        PlayerState state = state(p);
        if (!budget(state).tryAcquire(copies.size())) return BuilderOutcome.refused(Refusal.RATE_LIMITED, "");
        closeDrag(p.getUuid(), true);

        RecordBuilder record = new RecordBuilder();
        int skipped = 0;
        RuntimeException failure = null;
        BuilderCapture capture = BuilderCapture.open(world, keepShape, true);
        EditScope scope = keepShape ? EditScope.suppressPhysics() : null;
        try {
            if (replace) emptyContainer(capture, world, cell);
            if (!BuilderPlacement.place(decision.item(), decision.context(), decision.state(), false)) skipped++;
            ItemStack stack = decision.context().getStack();
            for (BuilderPlacement.Copy copy : copies.subList(1, copies.size())) {
                BlockPos at = copy.pos();
                if (writable(p, world, at) != null || (replace && !mayReplace(p, world, at))) {
                    skipped++;
                    continue;
                }
                Direction copySide = direction(Symmetry.imageFacing(copy.image(), m.side()));
                ItemPlacementContext context = BuilderPlacementContext.at(world, p, hand, stack, at, copySide);
                if (!replace && !world.getBlockState(at).canReplace(context)) {
                    skipped++;
                    continue;
                }
                if (replace) emptyContainer(capture, world, at);
                BlockState copyState = BuilderPlacement.imageState(states, copy.image(), decision.state());
                if (!BuilderPlacement.place(decision.item(), context, copyState, true)) skipped++;
            }
        } catch (RuntimeException e) {
            failure = e;
            LOG.error("Sculptory: a builder placement at {} failed; what it changed stays in the history",
                    cell.toShortString(), e);
        } finally {
            if (scope != null) scope.close();
        }
        List<Change> changes = changes(capture);
        // Vanilla's own steps reach other cells (a bed's head, a door's upper half): each must be writable too.
        Blocked secondary = failure == null ? blocked(p, world, changes) : null;
        if (secondary == null && failure == null) secondary = masked(mask, world, changes);
        if (secondary != null) {
            revert(world, changes);
            return BuilderOutcome.refused(secondary.refusal(), secondary.pos().toShortString());
        }
        int changed = record(world, record, changes, keepShape).changed();
        if (changed > 0) {
            String label = replace ? "Replace" : "Place";
            push(history.session(p.getUuid()), EngineEditService.worldId(world), label, record);
        }
        if (failure != null) return new BuilderOutcome(Refusal.FAILED, failure.toString(), changed, skipped);
        return BuilderOutcome.done(changed, skipped);
    }

    // ================================================================== breaking

    BuilderOutcome breakBlocks(ServerPlayerEntity p, C2S.BuilderBreak m) {
        UUID owner = p.getUuid();
        Refusal gate = gate(p);
        if (gate != null) {
            if (m.last()) closeDrag(owner, true);
            return BuilderOutcome.refused(gate, "");
        }
        BoundMask mask = mask(p);
        if (mask == null) {
            if (m.last()) closeDrag(owner, true);
            return BuilderOutcome.refused(Refusal.INVALID, EditMasks.REFUSED_DETAIL);
        }
        ServerWorld world = p.getServerWorld();
        executor.predicted(owner);
        boolean keepShape = BuilderPower.KEEP_SHAPE.in(m.powers());
        Symmetry symmetry = BuilderPower.MIRROR.in(m.powers()) ? m.symmetry() : Symmetry.NONE;
        if (!EngineEditService.insideWorld(symmetry)) {
            if (m.last()) closeDrag(owner, true);
            return BuilderOutcome.refused(Refusal.SYMMETRY_OUTSIDE, "");
        }
        List<List<BuilderPlacement.Copy>> targets = new ArrayList<>(m.cells().size());
        int writes = 0;
        for (dev.sculptory.core.BlockPos cell : m.cells()) {
            List<BuilderPlacement.Copy> copies = BuilderPlacement.copies(symmetry, new BlockPos(cell.x(), cell.y(), cell.z()));
            targets.add(copies);
            writes += copies.size();
        }
        PlayerState state = state(p);
        Drag drag = state.drag;
        if (drag != null && (drag.dragId != m.dragId() || drag.world != world)) {
            closeDrag(owner, true);
            drag = null;
        }
        if (drag == null) {
            Refusal order = followStrokes(p);
            if (order != null) return BuilderOutcome.refused(order, "");
        }
        // Charged after the stroke check: waiting for a stroke spends nothing.
        if (!budget(state).tryAcquire(writes)) {
            if (m.last()) closeDrag(owner, true);
            return BuilderOutcome.refused(Refusal.RATE_LIMITED, "");
        }
        if (drag == null) {
            HistoryService.Session session = history.session(owner);
            String worldId = EngineEditService.worldId(world);
            String label = BuilderPower.BULLDOZER.in(m.powers()) ? "Bulldozer" : "Break";
            drag = new Drag(m.dragId(), world, worldId, session, label, clock.getAsLong());
            drag.open = history.record(session, worldId, label, edits::createdMillis, drag.record);
            history.editStarted(session, drag.open);
            state.drag = drag;
        }
        drag.lastActivity = clock.getAsLong();

        // The global mask judges every cell to break against the world before this message: the cells it rejects are skipped, as Bulldozer skips what it may not break.
        Set<BlockPos> rejected = rejectedTargets(mask, world, targets);
        Refusal first = null;
        int skipped = 0;
        RuntimeException failure = null;
        BuilderCapture capture = BuilderCapture.open(world, keepShape, true);
        EditScope scope = keepShape ? EditScope.suppressPhysics() : null;
        try {
            for (List<BuilderPlacement.Copy> copies : targets) {
                BlockPos cell = copies.get(0).pos();
                Refusal refused = withinReach(p, cell) ? writable(p, world, cell) : Refusal.OUT_OF_REACH;
                if (refused == null) refused = breakable(p, world, cell, drag, m.sameKind());
                // Skipped quietly, like a cell with nothing to break: the orange Mask chip says why.
                if (refused == null && rejected.contains(cell)) refused = Refusal.NOTHING;
                if (refused != null) {
                    if (first == null || first == Refusal.NOTHING) first = refused;
                    skipped++;
                    continue;
                }
                if (drag.firstKind == null) drag.firstKind = world.getBlockState(cell).getBlock();
                breakAt(capture, world, p, cell);
                for (BuilderPlacement.Copy copy : copies.subList(1, copies.size())) {
                    BlockPos at = copy.pos();
                    if (writable(p, world, at) != null || breakable(p, world, at, drag, m.sameKind()) != null
                            || rejected.contains(at)) {
                        skipped++;
                        continue;
                    }
                    breakAt(capture, world, p, at);
                }
            }
        } catch (RuntimeException e) {
            failure = e;
            LOG.error("Sculptory: a builder break failed; what it changed stays in the history", e);
        } finally {
            if (scope != null) scope.close();
        }
        List<Change> changes = changes(capture);
        // What a break took with it (a door's other half, a piston's base) must be writable too, else nothing of this
        // message stands; the drag's earlier messages are untouched.
        Blocked secondary = failure == null ? blocked(p, world, changes) : null;
        if (secondary == null && failure == null) secondary = masked(mask, world, changes);
        if (secondary != null) {
            revert(world, changes);
            if (m.last()) closeDrag(owner, true);
            return BuilderOutcome.refused(secondary.refusal(), secondary.pos().toShortString());
        }
        Finished finished = record(world, drag.record, changes, keepShape);
        if (finished.fluid() && !drag.frozen) {
            // Water a broken waterlogged block left stays put until the drag's entry is pushed, so it never flows into a
            // cell a later break of the drag takes (the record would take that water as the cell's first state).
            trails.freeze(drag.record.id());
            drag.frozen = true;
        }
        if (m.last()) closeDrag(owner, true);
        int changed = finished.changed();
        if (failure != null) return new BuilderOutcome(Refusal.FAILED, failure.toString(), changed, skipped);
        // Nothing broken: the refusal of the first cell tells why (a drag sweeping air says nothing).
        if (changed == 0 && first != null) return BuilderOutcome.refused(first, "");
        return BuilderOutcome.done(changed, skipped);
    }

    void dragEnd(ServerPlayerEntity p, int dragId) {
        PlayerState state = players.get(p.getUuid());
        if (state == null || state.drag == null || state.drag.dragId != dragId) return;
        closeDrag(p.getUuid(), true);
    }

    /**
     * Why the (loaded) block at {@code pos} is not broken: an operator block for a non-op, or an item that breaks nothing
     * in Creative (a sword) are {@link Refusal#CANNOT_BREAK}; air, a bare fluid, or another kind than the drag's first in
     * a same-kind drag are {@link Refusal#NOTHING}.
     */
    private static Refusal breakable(ServerPlayerEntity p, ServerWorld world, BlockPos pos, Drag drag, boolean sameKind) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof FluidBlock) return Refusal.NOTHING;
        if (!BuilderPlacement.breakable(world, p, pos)) return Refusal.CANNOT_BREAK;
        if (!p.getMainHandStack().getItem().canMine(state, world, pos, p)) return Refusal.CANNOT_BREAK;
        if (sameKind && drag.firstKind != null && !state.isOf(drag.firstKind)) return Refusal.NOTHING;
        return null;
    }

    private void breakAt(BuilderCapture capture, ServerWorld world, ServerPlayerEntity p, BlockPos pos) {
        emptyContainer(capture, world, pos);
        BuilderPlacement.breakBlock(world, p, pos);
    }

    // ================================================================== drags and history

    /**
     * Ends the player's open drag: its record becomes one history entry (nothing when it changed nothing). With
     * {@code push} false (the player left without saved history) the record is dropped.
     */
    void closeDrag(UUID owner, boolean push) {
        PlayerState state = players.get(owner);
        if (state == null || state.drag == null) return;
        Drag drag = state.drag;
        state.drag = null;
        if (drag.frozen) trails.thaw(drag.record.id());
        EditRecord record = drag.record.build();
        HistoryEntry entry = !push || record.before().isEmpty() ? null : new HistoryEntry(drag.record.id(),
                drag.session.player(), drag.worldId, drag.label + " · " + EngineEditService.blocks(record.before().cellCount()),
                record, edits.createdMillis());
        history.editFinished(drag.session, entry, drag.open);
    }

    /** {@link #closeDrag} for the player's drag, before an editor edit, undo or redo (history order). */
    void commitDrag(UUID owner) {
        closeDrag(owner, true);
    }

    /** The entries open drags are building, which {@link FluidTrails} must not forget. */
    void liveEntries(Set<UUID> ids) {
        for (PlayerState state : players.values()) {
            if (state.drag != null) ids.add(state.drag.record.id());
        }
    }

    /** The player's open drag, if any (tests). */
    boolean dragOpen(UUID owner) {
        PlayerState state = players.get(owner);
        return state != null && state.drag != null;
    }

    private void push(HistoryService.Session session, String worldId, String label, RecordBuilder builder) {
        EditRecord record = builder.build();
        if (record.before().isEmpty()) return;
        history.push(session, new HistoryEntry(builder.id(), session.player(), worldId,
                label + " · " + EngineEditService.blocks(record.before().cellCount()), record, edits.createdMillis()));
    }

    /** What an action changed: the cells, and whether any of them now holds fluid marked as the entry's. */
    private record Finished(int changed, boolean fluid) {}

    /** One cell an action changed, as the capture saw it: its first and last state and block entity. */
    private record Change(BlockPos pos, int before, BlockEntityData beforeTile, int after, BlockEntityData afterTile) {}

    /** A changed cell the player may not write, and why. */
    private record Blocked(Refusal refusal, BlockPos pos) {}

    /** Closes the capture: every cell the action changed, in the order they were first changed. */
    private static List<Change> changes(BuilderCapture capture) {
        List<Change> changes = new ArrayList<>();
        capture.finish((x, y, z, before, beforeTile, after, afterTile) ->
                changes.add(new Change(new BlockPos(x, y, z), before, beforeTile, after, afterTile)));
        return changes;
    }

    /** The player's global mask, or {@code null} while their last mask was refused (their actions are refused). */
    private BoundMask mask(ServerPlayerEntity p) {
        try {
            return EditMasks.current(p.getUuid());
        } catch (EditRejected refused) {
            return null;
        }
    }

    /**
     * The first changed cell the global mask rejects, judged against the world before the action: {@link Refusal#MASKED}, or {@code null} when it accepts them all (or is off). Like the protection
     * check, one rejected cell refuses the whole action.
     */
    private Blocked masked(BoundMask mask, ServerWorld world, List<Change> changes) {
        if (mask.acceptsAll() || changes.isEmpty()) return null;
        EditMasks.BeforeView before = new EditMasks.BeforeView(edits.runtime().reader(world));
        for (Change change : changes) before.remember(change.pos().getX(), change.pos().getY(), change.pos().getZ(), change.before());
        for (Change change : changes) {
            BlockPos pos = change.pos();
            if (!mask.test(pos.getX(), pos.getY(), pos.getZ(), change.before(), before)) return new Blocked(Refusal.MASKED, pos);
        }
        return null;
    }

    /** The cells to break (targets and their copies) the global mask rejects, judged against the world as it is now. */
    private Set<BlockPos> rejectedTargets(BoundMask mask, ServerWorld world, List<List<BuilderPlacement.Copy>> targets) {
        if (mask.acceptsAll()) return Set.of();
        FabricWorldReader reader = edits.runtime().reader(world);
        Set<BlockPos> rejected = new HashSet<>();
        for (List<BuilderPlacement.Copy> copies : targets) {
            for (BuilderPlacement.Copy copy : copies) {
                BlockPos pos = copy.pos();
                if (!reader.isLoaded(pos.getX() >> 4, pos.getZ() >> 4) || world.isOutOfHeightLimit(pos)) continue;
                if (!mask.test(pos.getX(), pos.getY(), pos.getZ(), reader.get(pos.getX(), pos.getY(), pos.getZ()), reader)) {
                    rejected.add(pos);
                }
            }
        }
        return rejected;
    }

    /**
     * The first changed cell the player may not write ({@link #writable}: outside the world, unloaded, protected, held
     * by a job), or {@code null}. The target and the mirrored copies were checked before the action; this catches the
     * cells vanilla's own steps reached (a bed's head, a door's upper half, the base of a broken piston head).
     */
    private Blocked blocked(ServerPlayerEntity p, ServerWorld world, List<Change> changes) {
        for (Change change : changes) {
            Refusal refusal = writable(p, world, change.pos());
            if (refusal != null) return new Blocked(refusal, change.pos());
        }
        return null;
    }

    /**
     * Puts every changed cell back as it was, newest first, physics-free ({@code FORCE_STATE}, no neighbour updates,
     * the blocks' own callbacks cancelled so nothing drops or reacts); block entities are restored with their contents.
     * Clients hear the writes, and the action's prediction is reverted by its acknowledgement anyway.
     */
    private static void revert(ServerWorld world, List<Change> changes) {
        try (EditScope scope = EditScope.suppressPhysics()) {
            for (int i = changes.size() - 1; i >= 0; i--) {
                Change change = changes.get(i);
                BlockPos pos = change.pos();
                BlockState before = Block.getStateFromRawId(change.before());
                if (world.getBlockState(pos).hasBlockEntity()) world.removeBlockEntity(pos);
                world.setBlockState(pos, before, Block.FORCE_STATE | Block.NOTIFY_LISTENERS, 0);
                if (change.beforeTile() != null) restoreTile(world, pos, before, change.beforeTile());
            }
        }
    }

    private static void restoreTile(ServerWorld world, BlockPos pos, BlockState state, BlockEntityData tile) {
        try {
            NbtCompound nbt = FabricTile.from(tile).copyNbt();
            BlockEntity entity = BlockEntity.createFromNbt(pos, state, nbt, world.getRegistryManager());
            if (entity == null) return;
            world.removeBlockEntity(pos);
            world.addBlockEntity(entity);
            world.getWorldChunk(pos).setNeedsSaving(true);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Sculptory: a block entity at {} could not be put back after a refused builder action",
                    pos.toShortString(), e);
        }
    }

    /**
     * Records the changes into {@code record}, marking fluid the action left as the record's entry's; with Keep shape
     * it clears scheduled ticks at the changed cells, as the editor's physics-off writes do.
     */
    private Finished record(ServerWorld world, RecordBuilder record, List<Change> changes, boolean keepShape) {
        boolean fluid = false;
        for (Change change : changes) {
            BlockPos pos = change.pos();
            record.record(pos.getX(), pos.getY(), pos.getZ(), change.before(), change.beforeTile(), change.after(),
                    change.afterTile());
            fluid |= trails.wrote(world, pos.getX(), pos.getY(), pos.getZ(), change.after(), record.id());
            if (keepShape) {
                BlockBox box = new BlockBox(pos);
                world.getBlockTickScheduler().clearNextTicks(box);
                world.getFluidTickScheduler().clearNextTicks(box);
            }
        }
        return new Finished(changes.size(), fluid);
    }

    /** Whether Replace may take the block at {@code pos}: an operator block only for a level-2 creative op. */
    private static boolean mayReplace(ServerPlayerEntity p, ServerWorld world, BlockPos pos) {
        return !(world.getBlockState(pos).getBlock() instanceof OperatorBlock) || p.isCreativeLevelTwoOp();
    }

    // ================================================================== checks

    /** Editing enabled, the nodes, Creative. */
    private Refusal gate(ServerPlayerEntity p) {
        if (!config().editingEnabled) return Refusal.DISABLED;
        if (!permissions.has(p, Perm.USE) || !permissions.has(p, Perm.BUILDER)) return Refusal.NO_PERMISSION;
        if (!p.isCreative()) return Refusal.NOT_CREATIVE;
        return null;
    }

    /** Whether {@code pos}'s block lies within the builder reach of the player's eyes (the vanilla test, farther). */
    boolean withinReach(ServerPlayerEntity p, BlockPos pos) {
        double reach = config().builder.maxReach + REACH_MARGIN;
        return new net.minecraft.util.math.Box(pos).squaredMagnitude(p.getEyePos()) < reach * reach;
    }

    /** Why the player may not write {@code pos}: outside the world, unloaded, protected, held by a job. */
    private Refusal writable(ServerPlayerEntity p, ServerWorld world, BlockPos pos) {
        if (!World.isValid(pos) || !WorldChecks.inBuildLimit(world, pos.getX(), pos.getY(), pos.getZ())
                || !WorldChecks.insideBorder(world.getWorldBorder(), pos.getX(), pos.getZ())) {
            return Refusal.OUTSIDE_WORLD;
        }
        if (!neighbourhoodLoaded(world, pos)) return Refusal.UNLOADED;
        Box cell = Box.of(new dev.sculptory.core.BlockPos(pos.getX(), pos.getY(), pos.getZ()));
        ChunkPermit permit = permissions.chunk(p, world, pos.getX() >> 4, pos.getZ() >> 4, cell);
        if (permit == null || !permit.allows(pos.getX(), pos.getZ())) return Refusal.PROTECTED;
        if (executor.isLockedFor(world, cell, p.getUuid())) return Refusal.AREA_BUSY;
        return null;
    }

    /**
     * Whether the chunks holding {@code pos} and its horizontal neighbours are loaded: an action reads and may update
     * the neighbours, and a read of an unloaded cell would load its chunk on the server thread.
     */
    private static boolean neighbourhoodLoaded(ServerWorld world, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                if (!WorldChecks.isChunkLoaded(world, (pos.getX() + dx) >> 4, (pos.getZ() + dz) >> 4)) return false;
            }
        }
        return true;
    }

    /** Applies the player's queued brush work first, so the history follows the real order ({@code commitStroke}). */
    private Refusal followStrokes(ServerPlayerEntity p) {
        try {
            edits.commitStroke(p.getUuid());
            return null;
        } catch (EditRejected e) {
            return Refusal.BUSY;
        }
    }

    /**
     * Empties a container before it is broken or replaced: its contents go into the record (the cell is noted first),
     * not onto the ground, so undo restores them without duplicating anything.
     */
    private static void emptyContainer(BuilderCapture capture, ServerWorld world, BlockPos pos) {
        BlockEntity entity = world.getBlockEntity(pos);
        if (!(entity instanceof Clearable)) return;
        capture.touch(pos);
        try (EditScope scope = EditScope.suppressPhysics()) {
            Clearable.clear(entity);
        }
    }

    private static Refusal refusal(BuilderPlacement.Refusal refusal) {
        return switch (refusal) {
            case NOT_A_BLOCK -> Refusal.NOT_A_BLOCK;
            case DISABLED_BLOCK -> Refusal.DISABLED_BLOCK;
            case OCCUPIED -> Refusal.OCCUPIED;
            case GAME_REFUSES -> Refusal.GAME_REFUSES;
        };
    }

    static Direction direction(Facing facing) {
        return switch (facing) {
            case UP -> Direction.UP;
            case DOWN -> Direction.DOWN;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case EAST -> Direction.EAST;
            case WEST -> Direction.WEST;
        };
    }

    // ================================================================== lifecycle

    private PlayerState state(ServerPlayerEntity p) {
        return players.computeIfAbsent(p.getUuid(), id -> new PlayerState());
    }

    private SculptoryConfig config() {
        return configs.get();
    }

    /** The player's budget, made again when {@code builder.maxBlocksPerSecond} changed (a reload). */
    private TokenBucket budget(PlayerState state) {
        int rate = config().builder.maxBlocksPerSecond;
        if (state.budget == null || state.budgetRate != rate) {
            state.budget = new TokenBucket(rate, 2L * rate, clock);
            state.budgetRate = rate;
        }
        return state.budget;
    }

    /**
     * Per server tick: ends drags idle for {@value #DRAG_IDLE_SECONDS} s, and keeps Long reach matching the game mode
     * (every tick) and the nodes (every {@value #RECHECK_TICKS} ticks).
     */
    void tick() {
        long now = clock.getAsLong();
        boolean recheck = ++ticksSinceRecheck >= RECHECK_TICKS;
        if (recheck) ticksSinceRecheck = 0;
        for (Iterator<Map.Entry<UUID, PlayerState>> it = players.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PlayerState> entry = it.next();
            PlayerState state = entry.getValue();
            if (state.drag != null && now - state.drag.lastActivity >= DRAG_IDLE_NANOS) closeDrag(entry.getKey(), true);
            if (!BuilderPower.LONG_REACH.in(state.powers)) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) continue;
            if (recheck) {
                // A permissions mod that fails to answer takes nothing away.
                boolean denied = !config().editingEnabled || permissions.check(player, Perm.USE) == TriState.FALSE
                        || permissions.check(player, Perm.BUILDER) == TriState.FALSE;
                if (denied) state.allowed = false;
                else if (!state.allowed) state.allowed = mayUse(player);
            }
            updateReach(player, state);
        }
    }

    /** The player left: their drag becomes an entry ({@code keep}: history is saved) and Long reach goes. */
    void playerLeft(UUID owner, boolean keep) {
        closeDrag(owner, keep);
        players.remove(owner);
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(owner);
        if (player != null) {
            EntityAttributeInstance range = player.getAttributeInstance(EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE);
            if (range != null) range.removeModifier(LONG_REACH);
        }
    }

    /** Server stop: open drags become entries when history is saved. */
    void shutdown(boolean keep) {
        for (UUID owner : List.copyOf(players.keySet())) closeDrag(owner, keep);
        players.clear();
    }
}
