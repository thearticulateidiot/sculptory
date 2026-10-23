package dev.sculptory.mc.engine.impl;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.mc.builder.BuilderChange;
import dev.sculptory.mc.builder.BuilderPlacement;
import dev.sculptory.mc.builder.BuilderPlacementContext;
import dev.sculptory.mc.builder.BuilderSteps;
import dev.sculptory.mc.world.McTile;
import dev.sculptory.mc.world.McWorldReader;
import dev.sculptory.mc.world.WorldChecks;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.TokenBucket;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.BuilderOutcome.Refusal;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.impl.BuilderMode;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.EditMasks;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.engine.impl.HistoryService;
import dev.sculptory.server.platform.FluidTrailHook;
import dev.sculptory.server.platform.PlatformPermissions;
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
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builder mode on the server: the placements and breaks a player makes in
 * normal creative play with a power on, carried out through the engine (the host's {@code builderMode}: the loaders'
 * EngineRuntime, Paper's PaperEngine). Server thread only; owned by {@link EngineEditService}.
 *
 * <p><b>Who may.</b> Every action needs editing enabled, {@code sculptory.use} and {@code sculptory.builder}, and
 * Creative mode. The target (the clicked block, or the empty cell Place in air aims at, and every cell a drag breaks)
 * must lie within {@code builder.maxReach} (64) blocks of the player's eyes, plus {@value #REACH_MARGIN}; the cell
 * written must be loaded, inside the build height and the world border, writable by the player ({@code ChunkPermit}:
 * spawn protection, claims) and not held by an editor job. A refused action writes nothing and says why
 * ({@link BuilderOutcome}). Mirrored copies are checked each on its own: one that fails is skipped, the others are
 * written. Cells vanilla's own steps reach beyond those (a bed's head, a door's upper half, the base of a broken
 * piston head) are checked once the action ran: one the player may not write puts everything the action changed back
 * and refuses it. Replace takes an operator block, and Force place puts one down, only for a level-2 creative op.
 * Where the platform has block events (Paper: {@code BlockPlaceEvent}, {@code BlockMultiPlaceEvent},
 * {@code BlockBreakEvent}), every block placed or broken is announced like a player's, and one another plugin cancels is
 * refused like a protected cell: the target refuses the action whole ({@link Refusal#PROTECTED}), a copy or a cell of a
 * drag is skipped. Every block written, copies included, spends one token of the player's budget
 * ({@code builder.maxBlocksPerSecond}, bursts of twice that); an action over it is refused whole.
 *
 * <p><b>Writes</b> are vanilla's own placement and creative-break steps ({@link BuilderPlacement}), carried out by the
 * platform's {@link BuilderSteps}, which records every cell they change, the door's upper half and the fence that
 * joined included: exact undo of whatever vanilla did in that call. Keep shape makes them physics-free, and clears
 * scheduled ticks at the changed cells as the editor's writes do. Containers are emptied (into the record, not onto the
 * ground) before they are broken or replaced, so nothing drops and undo cannot duplicate items. Fluid the action leaves
 * is its entry's (the host's fluid trails).
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
public final class BuilderService implements BuilderMode<ServerPlayer> {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** Added to the reach in the distance check, as vanilla adds to its own. */
    static final double REACH_MARGIN = 1.0;
    static final int DRAG_IDLE_SECONDS = 5;
    static final long DRAG_IDLE_NANOS = DRAG_IDLE_SECONDS * 1_000_000_000L;
    static final String LONG_REACH_PATH = "long_reach";
    static final ResourceLocation LONG_REACH = ResourceLocation.fromNamespaceAndPath("sculptory", LONG_REACH_PATH);
    /** Server ticks between re-checks of the nodes of players holding Long reach. */
    static final int RECHECK_TICKS = 40;

    private final EngineEditService<ServerPlayer, ServerLevel> edits;
    private final McHost runtime;
    private final MinecraftServer server;
    /** The platform's way of carrying out the steps ({@link BuilderSteps}). */
    private final BuilderSteps steps;
    /** The config in effect ({@code /sculptory reload} replaces it): read whenever a check runs. */
    private final Supplier<SculptoryConfig> configs;
    private final PlatformPermissions<ServerPlayer, ServerLevel> permissions;
    private final EditExecutor<ServerLevel> executor;
    private final HistoryService history;
    private final FluidTrailHook<ServerLevel> trails;
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
        final ServerLevel world;
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

        Drag(int dragId, ServerLevel world, String worldId, HistoryService.Session session, String label, long now) {
            this.dragId = dragId;
            this.world = world;
            this.worldId = worldId;
            this.session = session;
            this.label = label;
            this.lastActivity = now;
        }
    }

    /**
     * @param runtime the engine host (config, permissions, fluid trails, states, readers)
     * @param server the running server (players by id)
     * @param steps how this platform carries out the world steps
     */
    public BuilderService(EngineEditService<ServerPlayer, ServerLevel> edits, McHost runtime, MinecraftServer server,
                          BuilderSteps steps, EditExecutor<ServerLevel> executor, HistoryService history,
                          LongSupplier clock) {
        this.edits = Objects.requireNonNull(edits);
        this.runtime = Objects.requireNonNull(runtime);
        this.server = Objects.requireNonNull(server);
        this.steps = Objects.requireNonNull(steps);
        this.configs = runtime::config;
        this.permissions = runtime.permissions();
        this.executor = Objects.requireNonNull(executor);
        this.history = Objects.requireNonNull(history);
        this.trails = runtime.fluidTrails();
        this.states = runtime.states();
        this.clock = Objects.requireNonNull(clock);
    }

    // ================================================================== powers and Long reach

    @Override
    public void powers(ServerPlayer p, int powers) {
        if (!BuilderPower.valid(powers)) return;
        PlayerState state = state(p);
        state.powers = powers;
        state.allowed = mayUse(p);
        updateReach(p, state);
    }

    /** The powers the player last reported (0 when none or unknown). */
    @Override
    public int powersOf(UUID player) {
        PlayerState state = players.get(player);
        return state == null ? 0 : state.powers;
    }

    /** Re-reads whether the player may use builder mode (op changes) and applies it to Long reach at once. */
    @Override
    public void permissionsChanged(ServerPlayer p) {
        PlayerState state = players.get(p.getUUID());
        if (state == null) return;
        state.allowed = mayUse(p);
        updateReach(p, state);
    }

    private boolean mayUse(ServerPlayer p) {
        return config().editingEnabled && permissions.has(p, Perm.USE) && permissions.has(p, Perm.BUILDER);
    }

    /** Adds or removes the Long reach modifier to match the power, the game mode and the permission. */
    private void updateReach(ServerPlayer p, PlayerState state) {
        AttributeInstance range = p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        if (range == null) return;
        boolean wanted = BuilderPower.LONG_REACH.in(state.powers) && state.allowed && p.isCreative();
        AttributeModifier held = range.getModifier(LONG_REACH);
        // Clamped by the attribute to 64 (its maximum); builder.maxReach may be less (and a reload may change it).
        double add = Math.max(0, config().builder.maxReach - range.getBaseValue());
        if (held != null && (!wanted || held.amount() != add)) {
            range.removeModifier(LONG_REACH);
            held = null;
        }
        if (wanted && held == null) {
            range.addTransientModifier(new AttributeModifier(LONG_REACH, add, AttributeModifier.Operation.ADD_VALUE));
        }
    }

    // ================================================================== placing

    @Override
    public BuilderOutcome place(ServerPlayer p, C2S.BuilderPlace m) {
        Refusal gate = gate(p);
        if (gate != null) return BuilderOutcome.refused(gate, "");
        BoundMask mask = mask(p);
        if (mask == null) return BuilderOutcome.refused(Refusal.INVALID, EditMasks.REFUSED_DETAIL);
        ServerLevel world = p.serverLevel();
        executor.predicted(p.getUUID());
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
        if (!neighbourhoodLoaded(world, target) || !neighbourhoodLoaded(world, target.relative(side))) {
            return BuilderOutcome.refused(Refusal.UNLOADED, target.toShortString());
        }
        Vec3 hitPos = new Vec3(target.getX() + m.hitX(), target.getY() + m.hitY(), target.getZ() + m.hitZ());
        InteractionHand hand = m.offHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
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
        closeDrag(p.getUUID(), true);

        RecordBuilder record = new RecordBuilder();
        int skipped = 0;
        boolean refused = false;
        RuntimeException failure = null;
        BuilderSteps.Action action = steps.open(world, p, keepShape);
        try {
            BuilderChange full = replace ? emptyContainer(world, action, cell) : null;
            BuilderSteps.Step placed = action.place(decision.item(), decision.context(), decision.state(), false);
            // Another plugin refused the block itself (its place event): the action is refused whole, as protection.
            refused = placed == BuilderSteps.Step.REFUSED;
            if (placed == BuilderSteps.Step.NOT_DONE) {
                skipped++;
                refill(world, full);
            }
            ItemStack stack = decision.context().getItemInHand();
            List<BuilderPlacement.Copy> others = refused ? List.of() : copies.subList(1, copies.size());
            for (BuilderPlacement.Copy copy : others) {
                BlockPos at = copy.pos();
                if (writable(p, world, at) != null || (replace && !mayReplace(p, world, at))) {
                    skipped++;
                    continue;
                }
                Direction copySide = direction(Symmetry.imageFacing(copy.image(), m.side()));
                BlockPlaceContext context = BuilderPlacementContext.at(world, p, hand, stack, at, copySide);
                if (!replace && !world.getBlockState(at).canBeReplaced(context)) {
                    skipped++;
                    continue;
                }
                BuilderChange copyFull = replace ? emptyContainer(world, action, at) : null;
                BlockState copyState = BuilderPlacement.imageState(states, copy.image(), decision.state());
                // A copy another plugin refused is skipped, as a protected copy is; its container is filled again, so
                // the copy changes nothing and records nothing.
                if (action.place(decision.item(), context, copyState, true) != BuilderSteps.Step.DONE) {
                    skipped++;
                    refill(world, copyFull);
                }
            }
        } catch (RuntimeException e) {
            failure = e;
            LOG.error("Sculptory: a builder placement at {} failed; what it changed stays in the history",
                    cell.toShortString(), e);
        } catch (Error e) {
            throw ended(world, action, e);
        }
        Ended done = end(world, action, failure);
        List<BuilderChange> changes = done.changes();
        failure = done.failure();
        if (refused) {
            // What the refused step changed is already put back; a container emptied for Replace is not.
            steps.revert(world, changes);
            return BuilderOutcome.refused(Refusal.PROTECTED, cell.toShortString());
        }
        // Vanilla's own steps reach other cells (a bed's head, a door's upper half): each must be writable too.
        Blocked secondary = failure == null ? blocked(p, world, changes) : null;
        if (secondary == null && failure == null) secondary = masked(mask, world, changes);
        if (secondary != null) {
            steps.revert(world, changes);
            return BuilderOutcome.refused(secondary.refusal(), secondary.pos().toShortString());
        }
        int changed = record(world, record, changes, keepShape).changed();
        if (changed > 0) {
            String label = replace ? "Replace" : "Place";
            push(history.session(p.getUUID()), runtime.worldId(world), label, record);
        }
        if (failure != null) return new BuilderOutcome(Refusal.FAILED, failure.toString(), changed, skipped);
        return BuilderOutcome.done(changed, skipped);
    }

    /** How an action ended: every cell it changed, and the failure to report (a step's, or the deferred physics'). */
    private record Ended(List<BuilderChange> changes, RuntimeException failure) {
    }

    /**
     * Ends the action: what the platform deferred runs now ({@code finish}; Paper's physics of the steps). If that
     * throws a RuntimeException halfway, what changed is still given ({@code abort}) and recorded like a step that
     * failed, as on Fabric, whose physics run inside the steps; an Error ends the action and puts it back
     * ({@link #ended}).
     */
    private Ended end(ServerLevel world, BuilderSteps.Action action, RuntimeException failure) {
        try {
            return new Ended(action.finish(), failure);
        } catch (RuntimeException e) {
            LOG.error("Sculptory: what a builder action set off failed; what it changed stays in the history", e);
            if (failure != null) failure.addSuppressed(e);
            return new Ended(action.abort(), failure != null ? failure : e);
        } catch (Error e) {
            throw ended(world, action, e);
        }
    }

    /**
     * A step threw an {@link Error} (a stack overflow, a linkage error): the action is still ended ({@code abort}: its
     * capture and Keep shape scope released, the world's own block capture left empty, the deferred physics of the
     * half-done work not run), and what it changed so far is put back (no history step is pushed for it); then the error
     * goes on, with whatever the clean-up threw added to it. The revert runs whatever ending the action threw.
     */
    private Error ended(ServerLevel world, BuilderSteps.Action action, Error error) {
        List<BuilderChange> changes = List.of();
        try {
            changes = action.abort();
        } catch (Throwable cleanup) {
            error.addSuppressed(cleanup);
        }
        try {
            steps.revert(world, changes);
        } catch (Throwable cleanup) {
            error.addSuppressed(cleanup);
        }
        return error;
    }

    // ================================================================== breaking

    @Override
    public BuilderOutcome breakBlocks(ServerPlayer p, C2S.BuilderBreak m) {
        UUID owner = p.getUUID();
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
        ServerLevel world = p.serverLevel();
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
            String worldId = runtime.worldId(world);
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
        BuilderSteps.Action action = steps.open(world, p, keepShape);
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
                Block kind = world.getBlockState(cell).getBlock();
                if (action.breakBlock(cell) == BuilderSteps.Step.REFUSED) {
                    // Another plugin refused the break (its break event): skipped, as a protected cell is.
                    if (first == null || first == Refusal.NOTHING) first = Refusal.PROTECTED;
                    skipped++;
                    continue;
                }
                if (drag.firstKind == null) drag.firstKind = kind;
                for (BuilderPlacement.Copy copy : copies.subList(1, copies.size())) {
                    BlockPos at = copy.pos();
                    if (writable(p, world, at) != null || breakable(p, world, at, drag, m.sameKind()) != null
                            || rejected.contains(at)) {
                        skipped++;
                        continue;
                    }
                    if (action.breakBlock(at) == BuilderSteps.Step.REFUSED) skipped++;
                }
            }
        } catch (RuntimeException e) {
            failure = e;
            LOG.error("Sculptory: a builder break failed; what it changed stays in the history", e);
        } catch (Error e) {
            throw ended(world, action, e);
        }
        Ended done = end(world, action, failure);
        List<BuilderChange> changes = done.changes();
        failure = done.failure();
        // What a break took with it (a door's other half, a piston's base) must be writable too, else nothing of this
        // message stands; the drag's earlier messages are untouched.
        Blocked secondary = failure == null ? blocked(p, world, changes) : null;
        if (secondary == null && failure == null) secondary = masked(mask, world, changes);
        if (secondary != null) {
            steps.revert(world, changes);
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

    @Override
    public void dragEnd(ServerPlayer p, int dragId) {
        PlayerState state = players.get(p.getUUID());
        if (state == null || state.drag == null || state.drag.dragId != dragId) return;
        closeDrag(p.getUUID(), true);
    }

    /**
     * Why the (loaded) block at {@code pos} is not broken: an operator block for a non-op, or an item that breaks nothing
     * in Creative (a sword) are {@link Refusal#CANNOT_BREAK}; air, a bare fluid, or another kind than the drag's first in
     * a same-kind drag are {@link Refusal#NOTHING}.
     */
    private static Refusal breakable(ServerPlayer p, ServerLevel world, BlockPos pos, Drag drag, boolean sameKind) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) return Refusal.NOTHING;
        if (!BuilderPlacement.breakable(world, p, pos)) return Refusal.CANNOT_BREAK;
        if (!p.getMainHandItem().getItem().canAttackBlock(state, world, pos, p)) return Refusal.CANNOT_BREAK;
        if (sameKind && drag.firstKind != null && !state.is(drag.firstKind)) return Refusal.NOTHING;
        return null;
    }

    /**
     * Replace empties a container before its cell is replaced (its contents go into the record, not onto the ground).
     * Returns the cell as it was before, to fill it again when the placement then does not happen, or null when the
     * cell holds no container.
     */
    private BuilderChange emptyContainer(ServerLevel world, BuilderSteps.Action action, BlockPos pos) {
        BlockEntity entity = world.getBlockEntity(pos);
        BuilderChange full = null;
        if (entity instanceof Clearable) {
            int state = Block.getId(world.getBlockState(pos));
            full = new BuilderChange(pos.immutable(), state, McTile.capture(entity, world.registryAccess()), state, null);
        }
        action.emptyContainer(pos);
        return full;
    }

    /**
     * A placement that did not happen (the item's own steps refused it, or another plugin cancelled it and its changes
     * were put back) leaves its cell's container as it was: emptied for Replace, it is filled again physics-free while
     * the action is still open, so the action's changes do not include it and nothing goes into the history. Only when
     * the cell still holds the container's block (a step that did change the cell keeps its own result).
     */
    private void refill(ServerLevel world, BuilderChange full) {
        if (full == null || !world.getBlockState(full.pos()).is(Block.stateById(full.before()).getBlock())) return;
        steps.revert(world, List.of(full));
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
    @Override
    public void commitDrag(UUID owner) {
        closeDrag(owner, true);
    }

    /** The entries open drags are building, which the fluid trails must not forget. */
    @Override
    public void liveEntries(Set<UUID> ids) {
        for (PlayerState state : players.values()) {
            if (state.drag != null) ids.add(state.drag.record.id());
        }
    }

    /** The player's open drag, if any (tests). */
    @Override
    public boolean dragOpen(UUID owner) {
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

    /** A changed cell the player may not write, and why. */
    private record Blocked(Refusal refusal, BlockPos pos) {}

    /** The player's global mask, or {@code null} while their last mask was refused (their actions are refused). */
    private BoundMask mask(ServerPlayer p) {
        try {
            return EditMasks.current(p.getUUID());
        } catch (EditRejected refused) {
            return null;
        }
    }

    /**
     * The first changed cell the global mask rejects, judged against the world before the action: {@link Refusal#MASKED}, or {@code null} when it accepts them all (or is off). Like the protection
     * check, one rejected cell refuses the whole action.
     */
    private Blocked masked(BoundMask mask, ServerLevel world, List<BuilderChange> changes) {
        if (mask.acceptsAll() || changes.isEmpty()) return null;
        EditMasks.BeforeView before = new EditMasks.BeforeView(runtime.reader(world));
        for (BuilderChange change : changes) before.remember(change.pos().getX(), change.pos().getY(), change.pos().getZ(), change.before());
        for (BuilderChange change : changes) {
            BlockPos pos = change.pos();
            if (!mask.test(pos.getX(), pos.getY(), pos.getZ(), change.before(), before)) return new Blocked(Refusal.MASKED, pos);
        }
        return null;
    }

    /** The cells to break (targets and their copies) the global mask rejects, judged against the world as it is now. */
    private Set<BlockPos> rejectedTargets(BoundMask mask, ServerLevel world, List<List<BuilderPlacement.Copy>> targets) {
        if (mask.acceptsAll()) return Set.of();
        McWorldReader reader = runtime.reader(world);
        Set<BlockPos> rejected = new HashSet<>();
        for (List<BuilderPlacement.Copy> copies : targets) {
            for (BuilderPlacement.Copy copy : copies) {
                BlockPos pos = copy.pos();
                if (!reader.isLoaded(pos.getX() >> 4, pos.getZ() >> 4) || world.isOutsideBuildHeight(pos)) continue;
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
    private Blocked blocked(ServerPlayer p, ServerLevel world, List<BuilderChange> changes) {
        for (BuilderChange change : changes) {
            Refusal refusal = writable(p, world, change.pos());
            if (refusal != null) return new Blocked(refusal, change.pos());
        }
        return null;
    }

    /**
     * Records the changes into {@code record}, marking fluid the action left as the record's entry's; with Keep shape
     * it clears scheduled ticks at the changed cells, as the editor's physics-off writes do.
     */
    private Finished record(ServerLevel world, RecordBuilder record, List<BuilderChange> changes, boolean keepShape) {
        boolean fluid = false;
        for (BuilderChange change : changes) {
            BlockPos pos = change.pos();
            record.record(pos.getX(), pos.getY(), pos.getZ(), change.before(), change.beforeTile(), change.after(),
                    change.afterTile());
            fluid |= trails.wrote(world, pos.getX(), pos.getY(), pos.getZ(), change.after(), record.id());
            if (keepShape) {
                BoundingBox box = new BoundingBox(pos);
                world.getBlockTicks().clearArea(box);
                world.getFluidTicks().clearArea(box);
            }
        }
        return new Finished(changes.size(), fluid);
    }

    /** Whether Replace may take the block at {@code pos}: an operator block only for a level-2 creative op. */
    private static boolean mayReplace(ServerPlayer p, ServerLevel world, BlockPos pos) {
        return !(world.getBlockState(pos).getBlock() instanceof GameMasterBlock) || p.canUseGameMasterBlocks();
    }

    // ================================================================== checks

    /** Editing enabled, the nodes, Creative. */
    private Refusal gate(ServerPlayer p) {
        if (!config().editingEnabled) return Refusal.DISABLED;
        if (!permissions.has(p, Perm.USE) || !permissions.has(p, Perm.BUILDER)) return Refusal.NO_PERMISSION;
        if (!p.isCreative()) return Refusal.NOT_CREATIVE;
        return null;
    }

    @Override
    public boolean withinReach(ServerPlayer p, int x, int y, int z) {
        return withinReach(p, new BlockPos(x, y, z));
    }

    /** Whether {@code pos}'s block lies within the builder reach of the player's eyes (the vanilla test, farther). */
    boolean withinReach(ServerPlayer p, BlockPos pos) {
        double reach = config().builder.maxReach + REACH_MARGIN;
        return new net.minecraft.world.phys.AABB(pos).distanceToSqr(p.getEyePosition()) < reach * reach;
    }

    /** Why the player may not write {@code pos}: outside the world, unloaded, protected, held by a job. */
    private Refusal writable(ServerPlayer p, ServerLevel world, BlockPos pos) {
        if (!Level.isInSpawnableBounds(pos) || !WorldChecks.inBuildLimit(world, pos.getX(), pos.getY(), pos.getZ())
                || !WorldChecks.insideBorder(world.getWorldBorder(), pos.getX(), pos.getZ())) {
            return Refusal.OUTSIDE_WORLD;
        }
        if (!neighbourhoodLoaded(world, pos)) return Refusal.UNLOADED;
        Box cell = Box.of(new dev.sculptory.core.BlockPos(pos.getX(), pos.getY(), pos.getZ()));
        ChunkPermit permit = permissions.chunk(p, world, pos.getX() >> 4, pos.getZ() >> 4, cell);
        if (permit == null || !permit.allows(pos.getX(), pos.getY(), pos.getZ())) return Refusal.PROTECTED;
        if (executor.isLockedFor(world, cell, p.getUUID())) return Refusal.AREA_BUSY;
        return null;
    }

    /**
     * Whether the chunks holding {@code pos} and its horizontal neighbours are loaded: an action reads and may update
     * the neighbours, and a read of an unloaded cell would load its chunk on the server thread.
     */
    private static boolean neighbourhoodLoaded(ServerLevel world, BlockPos pos) {
        for (int dx = -1; dx <= 1; dx += 2) {
            for (int dz = -1; dz <= 1; dz += 2) {
                if (!WorldChecks.isChunkLoaded(world, (pos.getX() + dx) >> 4, (pos.getZ() + dz) >> 4)) return false;
            }
        }
        return true;
    }

    /** Applies the player's queued brush work first, so the history follows the real order ({@code commitStroke}). */
    private Refusal followStrokes(ServerPlayer p) {
        try {
            edits.commitStroke(p.getUUID());
            return null;
        } catch (EditRejected e) {
            return Refusal.BUSY;
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

    private PlayerState state(ServerPlayer p) {
        return players.computeIfAbsent(p.getUUID(), id -> new PlayerState());
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
    @Override
    public void tick() {
        long now = clock.getAsLong();
        boolean recheck = ++ticksSinceRecheck >= RECHECK_TICKS;
        if (recheck) ticksSinceRecheck = 0;
        for (Iterator<Map.Entry<UUID, PlayerState>> it = players.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, PlayerState> entry = it.next();
            PlayerState state = entry.getValue();
            if (state.drag != null && now - state.drag.lastActivity >= DRAG_IDLE_NANOS) closeDrag(entry.getKey(), true);
            if (!BuilderPower.LONG_REACH.in(state.powers)) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) continue;
            if (recheck) {
                // A permissions mod that fails to answer takes nothing away.
                boolean denied = !config().editingEnabled || permissions.denied(player, Perm.USE)
                        || permissions.denied(player, Perm.BUILDER);
                if (denied) state.allowed = false;
                else if (!state.allowed) state.allowed = mayUse(player);
            }
            updateReach(player, state);
        }
    }

    /** The player left: their drag becomes an entry ({@code keep}: history is saved) and Long reach goes. */
    @Override
    public void playerLeft(UUID owner, boolean keep) {
        closeDrag(owner, keep);
        players.remove(owner);
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) {
            AttributeInstance range = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
            if (range != null) range.removeModifier(LONG_REACH);
        }
    }

    /** A world is unloading while the server goes on: the drags open in it become entries now. */
    @Override
    public void worldUnloading(String worldId) {
        for (Map.Entry<UUID, PlayerState> entry : List.copyOf(players.entrySet())) {
            Drag drag = entry.getValue().drag;
            if (drag != null && drag.worldId.equals(worldId)) closeDrag(entry.getKey(), true);
        }
    }

    /** Server stop: open drags become entries when history is saved. */
    @Override
    public void shutdown(boolean keep) {
        for (UUID owner : List.copyOf(players.keySet())) closeDrag(owner, keep);
        players.clear();
    }
}
