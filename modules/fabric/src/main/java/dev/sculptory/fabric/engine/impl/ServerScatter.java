package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.scatter.BlockVariants;
import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.FootprintCache;
import dev.sculptory.core.scatter.GrownFeature;
import dev.sculptory.core.scatter.Outcome;
import dev.sculptory.core.scatter.ScatterArea;
import dev.sculptory.core.scatter.ScatterPlan;
import dev.sculptory.core.scatter.ScatterPlanner;
import dev.sculptory.core.scatter.ScatterSettings;
import dev.sculptory.core.scatter.ScatterSource;
import dev.sculptory.core.schem.AssetInfo;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.config.SculptoryConfig;
import dev.sculptory.fabric.engine.ChunkPermit;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.PermissionService;
import dev.sculptory.fabric.engine.ScatterService;
import dev.sculptory.fabric.library.Library;
import dev.sculptory.fabric.library.LibraryPath;
import dev.sculptory.fabric.perm.FabricPermissionService;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.fabric.world.FeatureGrower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.Codec;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.ScatterPlacements;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server {@link ScatterService} (M3) for one {@link EngineEditService}. Server thread only.
 *
 * <p><b>Admission</b> ({@link #preview}) refuses up front, with nothing changed, in this order (cheap checks first;
 * the planner is built last):
 * <ul>
 *   <li>{@code DISABLED}, {@code NO_PERMISSION}: editing off; no {@code use} or {@code scatter}.</li>
 *   <li>{@code TOO_LARGE}: over {@value #MAX_STAMPS} stamps.</li>
 *   <li>{@code RATE_LIMITED}: within {@link #DEADLINE_COOLDOWN_NANOS 10 s} of the player's last preview that ran out
 *       of planning time; the player's hold budget used up (below); or a second preview replacing the running one in
 *       the same server tick. The cooldown and the budget survive a reconnect.</li>
 *   <li>{@code QUEUE_FULL}: {@value #MAX_ACTIVE} other players' previews are already in flight.</li>
 *   <li>{@code NO_PERMISSION}: a block variant from a player without {@code brush} or {@code region}
 *       ({@link #BLOCK_VARIANTS_NEED}). {@code INVALID}: a variant clipboard that is not the player's own, or a block
 *       variant the server cannot place (the reason says which: malformed state text, an unknown block, properties it
 *       does not have, air, a fluid, a block that carries water);
 *       {@code ASSET_NOT_LOADED}: a variant asset not loaded or previewed on the server, or one the player may not
 *       read (the paste rule).</li>
 *   <li>{@code TOO_LARGE}: a source whose box volume is over {@code scatter.maxSourceVolume}; sources over
 *       {@value #MAX_TOTAL_SOURCE_VOLUME} blocks of box volume or {@link ScatterPlanner#MAX_SOURCE_CELLS} cells
 *       together; the work estimate (the area's columns, times the density fraction or all of them for a target
 *       count, times the largest source's cells) over {@code scatter.maxWork}; held columns (below) over
 *       {@value #MAX_HELD_COLUMNS}.</li>
 *   <li>{@code INVALID}: settings the core rejects (a box outside the build height, a mask naming an unknown
 *       state...). The core also caps the area (1,048,576 columns), spacing (64) and variants (64).</li>
 * </ul>
 * A player has one preview in flight: a new one supersedes the running one (its reply gets
 * {@link PreviewReply#superseded()}, the dispatcher answers {@code JobRejected(QUEUE_FULL)} and clients ignore that
 * for a request they replaced) and drops the player's live plan.
 *
 * <p><b>Variants.</b> Duplicate sources are planned once, and footprints are cached by content hash across previews.
 * An asset's turns come from its {@code AssetInfo.rotations} (none listed: unrotated only; no metadata: every turn);
 * a clipboard allows every turn. A block variant is its block, lower half first for a double-tall one (a 1x2x1
 * footprint), turned and mirrored by the state's own rotate and mirror; every turn. With {@code Fit.survive} the planner
 * keeps a block variant only where vanilla would let it stand ({@link #survivalCheck}). The planner's cell budget is
 * {@code maxOpVolume} (unlimited with {@code limit.bypass}); its work budget is {@code scatter.maxWork}. A tree or
 * feature variant ({@code ScatterSource.Feature}) must be in
 * {@code FeatureCatalog} ({@code INVALID} otherwise) and needs {@code brush} or {@code region} like a block
 * ({@code NO_PERMISSION}, {@link #FEATURES_NEED}); it is grown at each spot by a {@link FeatureGrower} (never turned),
 * reaches what its catalog entry says for the hold and the caps, and its plan may grow at most
 * {@code scatter.maxFeatureCells} cells ({@code TOO_LARGE} past that, when planning reaches it). The plan's grown cells
 * travel with the reply ({@link PlanReady#generatedPayload}).
 *
 * <p><b>Holding the area.</b> The planner reads the world from the first survey tile to the end of acceptance, so a
 * preview holds chunk columns ({@link EditExecutor#hold}): those of the painted discs (or the box) widened by the
 * variants' horizontal reach and the slope ring, over the whole build height for painted stamps and, for a box, over
 * the box's y-window widened by the variants' vertical reach. Chunks the player may not modify at all (spawn
 * protection, claims) and chunks wholly outside the world border are left out, so nobody holds what they could not
 * edit, and two dots far apart hold only their own chunks. Planning starts when the Sculptory jobs admitted there
 * before it have finished; a preview still waiting after {@link #HOLD_WAIT_NANOS} is refused {@code AREA_BUSY}. While
 * it plans, jobs admitted over the held columns queue behind it and dabs there are refused {@code AREA_BUSY}. Vanilla
 * changes are not held off: the plan reflects the world as the planner read it, and the commit only writes into cells
 * that are still open.
 *
 * <p><b>Hold budget.</b> A hold can keep other players out, so each player has a budget of blocking time
 * ({@code scatter.holdBudgetSeconds}, refilled at {@code scatter.holdRefillShare} seconds per second, service clock).
 * A preview uses it only while its hold actually blocks someone else: from the first time another player's job queued
 * behind it or their dab or cut was refused {@code AREA_BUSY} because of it ({@link EditExecutor.AreaHold#blockedSince})
 * until the area is released, overdrawing by one budget at most. A preview admitted with nothing left is refused
 * {@code RATE_LIMITED}. A player alone in the area is never charged, however long their previews take; one who keeps
 * re-previewing over someone else's work runs out.
 *
 * <p><b>Planning</b> runs in the executor's tick as a lane ({@link EditExecutor#addLane}) with {@code scatter.tickShare}
 * of the executor's budget, taken from it (not added to it), shared equally by the previews in flight. Each step is
 * one survey tile (a chunk column), the start of acceptance (no sorting: candidates are bucketed during the survey),
 * or an acceptance step of {@value #ACCEPT_STEP_WORK} work units; only the oldest preview is guaranteed a step when
 * the budget is spent. Chunks are never loaded: columns and footprints in unloaded chunks end as {@code UNLOADED}; a
 * footprint reaching a column the player may not modify (spawn protection, claims, the world border) ends as
 * {@code PROTECTED}. A preview may be given {@code scatter.maxPlanningMillis} of planning time, counted over its own
 * steps only, so a server busy with other players' work does not count against it; past that it is refused
 * {@code TOO_LARGE} and starts the player's cooldown. A preview that has been ready to plan for
 * {@link #PLANNING_WALL_LIMIT_NANOS 100 s} without finishing (a server too busy to give it time) is refused
 * {@code RATE_LIMITED}, without a cooldown.
 *
 * <p><b>Other endings</b> (the area is released on every one): the player changed world ({@code INVALID}); the planner
 * failed ({@code INVALID}, logged); the player lost {@code use} or {@code scatter} ({@code NO_PERMISSION}; at once on a
 * deop, otherwise at the next sweep); the player left or is no longer on the server, or the server stops (no reply).
 *
 * <p><b>Result.</b> The plan becomes the player's live plan ({@link ScatterPlans}: one per player, pinned to the world,
 * {@link ScatterPlans#TTL_NANOS 10 minutes}), and the reply carries its summary and the {@code SCATTER_PLACEMENTS}
 * payload ({@link ScatterPlacements}) (and the grown cells' payload): encoded off the server thread and sent on a
 * later tick. A newer preview of the same player answers a reply still being encoded as superseded.
 */
public final class ServerScatter implements ScatterService {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    /** Painted stamps per preview (the wire cap too). */
    public static final int MAX_STAMPS = Codec.MAX_SCATTER_STAMPS;
    /** Previews in flight at once, server-wide. */
    public static final int MAX_ACTIVE = 8;
    /** The variants' box volumes together, per preview. */
    public static final long MAX_TOTAL_SOURCE_VOLUME = 1L << 21;
    /** The refusal detail of a block variant without {@code brush} or {@code region}. */
    public static final String BLOCK_VARIANTS_NEED = "block variants need " + Perm.BRUSH.node() + " or "
            + Perm.REGION.node() + " (scatter clipboards or library assets instead)";
    /** The refusal detail of a tree or feature variant without {@code brush} or {@code region}. */
    public static final String FEATURES_NEED = "trees and features need " + Perm.BRUSH.node() + " or "
            + Perm.REGION.node() + " (scatter clipboards or library assets instead)";
    /** Chunk columns one preview may hold. */
    public static final int MAX_HELD_COLUMNS = 4096;
    /** A preview whose area is still busy with earlier jobs this long after admission is refused {@code AREA_BUSY}. */
    public static final long HOLD_WAIT_NANOS = 5_000_000_000L;
    /**
     * A preview ready to plan this long (service clock) without finishing is refused {@code RATE_LIMITED}: the server
     * is too busy to give it time. Not the player's fault, so no cooldown.
     */
    public static final long PLANNING_WALL_LIMIT_NANOS = 100_000_000_000L;
    /** After a preview runs out of planning time, the player's next preview waits this long ({@code RATE_LIMITED}). */
    public static final long DEADLINE_COOLDOWN_NANOS = 10_000_000_000L;
    /** The smallest per-tick planning time of {@link #tick()}, whatever the configured share. */
    static final long MIN_TICK_NANOS = 1_000_000L;
    /** Acceptance work units per step, between time checks. */
    static final long ACCEPT_STEP_WORK = 4096;
    /** Server ticks between sweeps of expired plans and plans whose player changed world. */
    static final int SWEEP_TICKS = 20;

    private final EngineEditService edits;
    private final EngineRuntime runtime;
    private final MinecraftServer server;
    private final FabricPermissionService permissions;
    private final FootprintCache footprints = new FootprintCache(64, 1L << 22);
    /** Previews in flight by player, oldest first. */
    private final LinkedHashMap<UUID, Task> tasks = new LinkedHashMap<>();
    /** Finished plans whose payloads are being encoded, by player ({@link #deliverEncoded}). */
    private final LinkedHashMap<UUID, Encoding> encoding = new LinkedHashMap<>();
    /**
     * Encodes plan payloads (plain data) off the server thread: this service's own daemon thread, started by the first
     * plan with grown cells and stopped by {@link #shutdown} (a new server, or a GameTest's service, gets its own).
     */
    private ThreadPoolExecutor encoder;
    /** Players whose last preview ran out of time: when they may preview again (service clock). Kept on leaving. */
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();
    /** Players' hold budgets, those not full; kept on leaving. */
    private final HoldBudgets holdBudgets;
    private Runnable stepObserver;
    private long ticks;

    public ServerScatter(EngineEditService edits) {
        this.edits = Objects.requireNonNull(edits);
        this.runtime = edits.runtime();
        this.server = edits.server();
        this.permissions = runtime.permissions();
        this.holdBudgets = new HoldBudgets(() -> config().scatter);
    }

    /** The config in effect: the runtime's, which {@code /sculptory reload} replaces (checks read it when they run). */
    private SculptoryConfig config() {
        return runtime.config();
    }

    /** This service's planning as an executor lane (register it with {@code scatter.tickShare}). */
    public EditExecutor.Lane lane() {
        return new EditExecutor.Lane() {
            @Override
            public boolean busy() {
                return !tasks.isEmpty() || !encoding.isEmpty();
            }

            @Override
            public void run(long deadline) {
                runLane(deadline);
            }
        };
    }

    // ================================================================== admission

    @Override
    public void preview(ServerPlayerEntity p, C2S.ScatterPreview request, PreviewReply reply) throws EditRejected {
        checkThread();
        Objects.requireNonNull(request);
        Objects.requireNonNull(reply);
        if (!config().editingEnabled) throw new EditRejected(RejectReason.DISABLED);
        require(p, Perm.USE);
        require(p, Perm.SCATTER);
        UUID owner = p.getUuid();
        ServerWorld world = p.getServerWorld();
        long now = edits.now();

        // Cheap refusals first.
        if (request.area() instanceof ScatterArea.Stamps stamps && stamps.stamps().size() > MAX_STAMPS) {
            throw new EditRejected(RejectReason.TOO_LARGE, stamps.stamps().size() + " stamps > " + MAX_STAMPS);
        }
        Long cooldown = cooldownUntil.get(owner);
        if (cooldown != null) {
            if (now - cooldown < 0) {
                throw new EditRejected(RejectReason.RATE_LIMITED, "the last scatter took too long; wait "
                        + Math.max(1, (cooldown - now) / 1_000_000_000L) + " s");
            }
            cooldownUntil.remove(owner);
        }
        Task running = tasks.get(owner);
        if (running != null && running.admittedTick == server.getTicks()) {
            throw new EditRejected(RejectReason.RATE_LIMITED, "one scatter preview per tick");
        }
        long holdLeft = holdBudgets.left(owner, now) - (running != null ? running.hold.blockingFor(now) : 0);
        if (holdLeft <= 0) {
            long wait = holdBudgets.refillNanos(holdLeft) / 1_000_000_000L + 1;
            throw new EditRejected(RejectReason.RATE_LIMITED, "your scatter previews held the area for too long; wait "
                    + wait + " s");
        }
        if (running == null && tasks.size() >= MAX_ACTIVE) {
            throw new EditRejected(RejectReason.QUEUE_FULL, "other scatter previews are planning");
        }
        C2S.ScatterPreview.Settings wanted = request.settings();
        Sources sources = resolve(p, request.variants(), wanted.columnHeight());
        Area area = Area.of(request.area());
        long maxWork = config().scatter.maxWork;
        long estimate = estimatedWork(area.columns(), wanted.density(), sources.largestCells());
        if (estimate > maxWork) {
            throw new EditRejected(RejectReason.TOO_LARGE, "about " + estimate + " cell checks > " + maxWork
                    + " (lower the density or paint a smaller area)");
        }
        // The permits asked here are kept for the planner's guard.
        Long2ObjectOpenHashMap<ChunkPermit> permits = new Long2ObjectOpenHashMap<>();
        LongOpenHashSet held = heldColumns(request.area(), sources.reach() + 1L);
        WorldBorder border = world.getWorldBorder();
        dropUnmodifiable(held, border.getBoundWest(), border.getBoundEast(), border.getBoundNorth(),
                border.getBoundSouth(), (cx, cz) -> chunkPermit(permissions, p, world, permits, cx, cz));

        // Now the planner (sources' footprints, the area mask).
        ScatterSettings settings;
        try {
            settings = new ScatterSettings(request.area(), wanted.density(), wanted.spacing(),
                    ScatterSettings.Filters.of(wanted.surface()), wanted.fit(), sources.variants(), request.transforms(),
                    wanted.seed(), wanted.columnHeight());
        } catch (IllegalArgumentException e) {
            throw new EditRejected(RejectReason.INVALID, e.getMessage());
        }
        long maxCells = permissions.has(p, Perm.LIMIT_BYPASS) ? Long.MAX_VALUE : config().limits.maxOpVolume;
        FabricWorldReader reader = runtime.reader(world);
        ScatterPlanner planner;
        try {
            ScatterPlanner.Features grown = !sources.hasFeatures() ? ScatterPlanner.Features.NONE
                    : new ScatterPlanner.Features(sources.features(), new FeatureGrower(world, runtime.states(),
                            sources.features(), wanted.fit().survive()), config().scatter.maxFeatureCells);
            planner = new ScatterPlanner(settings, sources.clipboards(), reader, maxCells, maxWork,
                    protectionGuard(permissions, p, world, permits), footprints,
                    survivalCheck(runtime.states(), world, sources.blockStates()), sources.blockStates(), grown);
        } catch (IllegalArgumentException e) {
            throw new EditRejected(RejectReason.INVALID, e.getMessage());
        }
        // The global mask judges each placement and each tree whole, before the world is changed: one cell it denies
        // leaves the whole thing out (MASKED), so the preview shows only what the commit writes. Refused while the
        // player's mask is refused, as their edits are.
        BoundMask editMask = EditMasks.current(owner);
        if (!editMask.acceptsAll()) {
            planner.cellGuard((x, y, z) -> editMask.test(x, y, z, reader.get(x, y, z), reader));
        }

        // The player's queued dabs are older than this preview: apply them before holding the area. Before replacing
        // anything: this may refuse, and a refused preview leaves the running one and the live plan as they were.
        edits.commitStroke(owner);
        // Admitted. The new preview replaces the running one and the live plan.
        if (running != null) {
            close(running);
            answer(running, running.reply::superseded);
        }
        Encoding finishing = encoding.remove(owner);
        if (finishing != null) {
            drop(finishing);
            answerEncoded(finishing.reply()::superseded);
        }
        edits.scatterPlans().remove(owner);
        int sy0, sy1;
        if (area.region()) {
            Box window = planner.areaBounds();
            sy0 = Math.max(world.getBottomSectionCoord(), (window.min().y() - sources.below() - 1) >> 4);
            sy1 = Math.min(world.getTopSectionCoord() - 1, (window.max().y() + sources.above() + 1) >> 4);
        } else {
            sy0 = world.getBottomSectionCoord();
            sy1 = world.getTopSectionCoord() - 1;
        }
        EditExecutor.AreaHold hold = edits.executor().hold(world, owner, held, sy0, sy1, edits::now);
        Task task = new Task(owner, world, EngineEditService.worldId(world), planner, reader, hold, reply, now,
                server.getTicks(), sources.libraryPaths(), sources.hasBlocks() || sources.hasFeatures(),
                config().scatter.maxPlanningMillis * 1_000_000L);
        tasks.put(owner, task);
    }

    /**
     * The chunk columns a preview of {@code area} holds: each painted disc (or the box) widened by {@code margin}
     * blocks. Erase stamps only take columns away, so they add nothing.
     *
     * @throws EditRejected {@code TOO_LARGE} over {@value #MAX_HELD_COLUMNS} columns
     */
    static LongOpenHashSet heldColumns(ScatterArea area, long margin) throws EditRejected {
        LongOpenHashSet held = new LongOpenHashSet();
        switch (area) {
            case ScatterArea.Region r -> {
                Box box = r.box();
                addRectangle(held, new ChunkRectangle((box.min().x() - margin) >> 4, (box.min().z() - margin) >> 4,
                        (box.max().x() + margin) >> 4, (box.max().z() + margin) >> 4));
            }
            case ScatterArea.Stamps s -> {
                // Stamps along a stroke mostly cover the same chunks: each chunk rectangle is added once.
                Set<ChunkRectangle> seen = new HashSet<>();
                for (ScatterArea.Stamp stamp : s.stamps()) {
                    if (stamp.erase()) continue;
                    long reach = stamp.radius() + margin;
                    ChunkRectangle rectangle = new ChunkRectangle((stamp.x() - reach) >> 4, (stamp.z() - reach) >> 4,
                            (stamp.x() + reach) >> 4, (stamp.z() + reach) >> 4);
                    if (seen.add(rectangle)) addRectangle(held, rectangle);
                }
            }
        }
        return held;
    }

    /** An inclusive rectangle of chunk columns. */
    private record ChunkRectangle(long cx0, long cz0, long cx1, long cz1) {}

    private static void addRectangle(LongOpenHashSet held, ChunkRectangle r) throws EditRejected {
        long columns = (r.cx1() - r.cx0() + 1) * (r.cz1() - r.cz0() + 1);
        if (columns > MAX_HELD_COLUMNS) throw tooManyColumns(columns);
        for (long cx = r.cx0(); cx <= r.cx1(); cx++) {
            for (long cz = r.cz0(); cz <= r.cz1(); cz++) {
                held.add(ColumnPlan.pack((int) cx, (int) cz));
                if (held.size() > MAX_HELD_COLUMNS) throw tooManyColumns(held.size());
            }
        }
    }

    private static EditRejected tooManyColumns(long columns) {
        return new EditRejected(RejectReason.TOO_LARGE, "the area and its variants span " + (columns > MAX_HELD_COLUMNS
                ? "more than " + MAX_HELD_COLUMNS : Long.toString(columns)) + " chunk columns");
    }

    /**
     * Removes the columns nobody should hold for a player: chunks wholly outside the border rectangle
     * ({@code WorldBorder.getBound*}) and chunks whose {@code permits} answer is {@link ChunkPermit.Deny} (the player
     * may not modify any of it). Chunks the player may partly modify stay held.
     */
    static void dropUnmodifiable(LongOpenHashSet held, double west, double east, double north, double south,
                                 PermitSource permits) {
        held.removeIf((long column) -> {
            int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
            int x0 = cx << 4, z0 = cz << 4;
            if (!(x0 + 16 > west && x0 < east && z0 + 16 > north && z0 < south)) return true;
            ChunkPermit permit = permits.chunk(cx, cz);
            return permit == null || permit instanceof ChunkPermit.Deny;
        });
    }

    /** An upper bound on the area's columns, without rasterizing it, and whether it is a box. */
    record Area(long columns, boolean region) {
        static Area of(ScatterArea area) {
            return switch (area) {
                case ScatterArea.Region r -> new Area((long) r.box().sizeX() * r.box().sizeZ(), true);
                case ScatterArea.Stamps s -> {
                    long x0 = Long.MAX_VALUE, z0 = Long.MAX_VALUE, x1 = Long.MIN_VALUE, z1 = Long.MIN_VALUE, discs = 0;
                    for (ScatterArea.Stamp stamp : s.stamps()) {
                        if (stamp.erase()) continue;
                        long r = stamp.radius();
                        x0 = Math.min(x0, stamp.x() - r);
                        z0 = Math.min(z0, stamp.z() - r);
                        x1 = Math.max(x1, stamp.x() + r);
                        z1 = Math.max(z1, stamp.z() + r);
                        discs += (2 * r + 1) * (2 * r + 1);
                    }
                    yield new Area(discs == 0 ? 0 : Math.min((x1 - x0 + 1) * (z1 - z0 + 1), discs), false);
                }
            };
        }
    }

    /**
     * The variant sources, deduplicated, with their turn masks and library paths; plus what the caps need: the
     * largest source's cells, and how far its cells reach from the anchor horizontally, below and above.
     */
    private record Sources(List<Clipboard> clipboards, List<ScatterSettings.Variant> variants,
                           List<LibraryPath> libraryPaths, long largestCells, int reach, int below, int above,
                           int[] blockStates, List<FeatureCatalog.FeatureDef> features) {
        boolean hasBlocks() {
            for (int state : blockStates) {
                if (state >= 0) return true;
            }
            return false;
        }

        boolean hasFeatures() {
            for (FeatureCatalog.FeatureDef def : features) {
                if (def != null) return true;
            }
            return false;
        }
    }

    /**
     * @param columns how tall column plants grow: a column plant's source counts as that many cells high for the caps
     *     and the hold
     */
    private Sources resolve(ServerPlayerEntity p, List<C2S.ScatterPreview.Variant> requested,
                            ScatterSettings.ColumnHeight columns) throws EditRejected {
        Map<ScatterSource, Integer> index = new HashMap<>();
        List<Integer> blockStates = new ArrayList<>();
        List<Clipboard> clipboards = new ArrayList<>();
        List<LibraryPath> paths = new ArrayList<>();
        List<Integer> turns = new ArrayList<>();
        List<FeatureCatalog.FeatureDef> features = new ArrayList<>();
        List<ScatterSettings.Variant> variants = new ArrayList<>(requested.size());
        long totalCells = 0, totalVolume = 0, largest = 0, reach = 0, below = 0, above = 0;
        for (C2S.ScatterPreview.Variant variant : requested) {
            Integer source = index.get(variant.source());
            if (source == null) {
                Resolved resolved = resolveOne(p, variant.source());
                Clipboard clipboard = resolved.clipboard();
                FeatureCatalog.FeatureDef def = resolved.feature();
                if (def != null) {
                    // A tree or feature: what it may reach, from its catalog entry (its growth is clipped to that).
                    largest = Math.max(largest, featureCells(def));
                    reach = Math.max(reach, FeatureGrower.reach(def));
                    below = Math.max(below, FeatureGrower.SPAN_DOWN);
                    above = Math.max(above, FeatureGrower.above(def));
                    source = clipboards.size();
                    index.put(variant.source(), source);
                    clipboards.add(clipboard);
                    paths.add(null);
                    turns.add(resolved.turns());
                    blockStates.add(-1);
                    features.add(def);
                    variants.add(new ScatterSettings.Variant(source, variant.weight(), turns.get(source)));
                    continue;
                }
                if (clipboard.volume() > config().scatter.maxSourceVolume) {
                    throw new EditRejected(RejectReason.TOO_LARGE, "a variant of " + clipboard.volume() + " blocks > "
                            + config().scatter.maxSourceVolume);
                }
                totalVolume += clipboard.volume();
                totalCells += clipboard.cellCount();
                if (totalVolume > MAX_TOTAL_SOURCE_VOLUME || totalCells > ScatterPlanner.MAX_SOURCE_CELLS) {
                    throw new EditRejected(RejectReason.TOO_LARGE, "the variants hold more than "
                            + MAX_TOTAL_SOURCE_VOLUME + " blocks");
                }
                largest = Math.max(largest, clipboard.cellCount());
                reach = Math.max(reach, reach(clipboard));
                below = Math.max(below, clipboard.anchor().y());
                above = Math.max(above, (long) clipboard.size().y() - clipboard.anchor().y());
                if (resolved.block() >= 0 && BlockVariants.isColumn(runtime.states(), resolved.block())) {
                    largest = Math.max(largest, columns.max());
                    above = Math.max(above, columns.max());
                }
                source = clipboards.size();
                index.put(variant.source(), source);
                clipboards.add(clipboard);
                paths.add(resolved.path());
                turns.add(resolved.turns());
                blockStates.add(resolved.block());
                features.add(null);
            }
            variants.add(new ScatterSettings.Variant(source, variant.weight(), turns.get(source)));
        }
        long far = 1 << 16;
        if (reach > far || below > far || above > far) {
            throw new EditRejected(RejectReason.TOO_LARGE, "a variant's anchor is too far from its blocks");
        }
        return new Sources(clipboards, variants, paths, largest, (int) reach, (int) Math.max(0, below),
                (int) Math.max(0, above), blockStates.stream().mapToInt(Integer::intValue).toArray(),
                java.util.Collections.unmodifiableList(features));
    }

    /**
     * About how many cells a growth of {@code def} writes, for the up-front work estimate: a third of its reach's box
     * (a crown is far from solid).
     */
    static long featureCells(FeatureCatalog.FeatureDef def) {
        long side = 2L * def.radius() + 1;
        return Math.max(1, side * side * def.height() / 3);
    }

    /**
     * A resolved variant source; {@code block} is the lower block's state for a block variant, else -1; {@code feature}
     * the catalog entry of a tree or feature (whose clipboard is a placeholder), else {@code null}.
     */
    private record Resolved(Clipboard clipboard, int turns, LibraryPath path, int block,
                            FeatureCatalog.FeatureDef feature) {}

    /**
     * A block variant (the player needs {@code brush} or {@code region}: {@link #requireBlockVariants}): its state text
     * resolved against the server's blocks ({@code INVALID} saying why not: malformed, an unknown block, properties it
     * does not have, air, a fluid, a block that carries water) and its one- or two-cell footprint; every turn.
     * Otherwise the paste rule: the player's own clipboard, or a cached library asset the player may read.
     */
    private Resolved resolveOne(ServerPlayerEntity p, ScatterSource source) throws EditRejected {
        if (source instanceof ScatterSource.Feature feature) {
            requireFeatures(p);
            FeatureCatalog.FeatureDef def = FeatureCatalog.find(feature.id()).orElseThrow(() -> new EditRejected(
                    RejectReason.INVALID, "not a tree or feature Scatter grows: " + clip(feature.id())));
            // Grown where it stands, never turned: one turn, and a placeholder source that is never written.
            return new Resolved(Clipboard.builder(runtime.states(), new BlockPos(1, 1, 1)).source(def.id()).build(), 1,
                    null, -1, def);
        }
        if (source instanceof ScatterSource.Block block) {
            requireBlockVariants(p);
            int state;
            try {
                state = BlockVariants.resolve(runtime.states(), block.state());
            } catch (IllegalArgumentException e) {
                throw new EditRejected(RejectReason.INVALID, e.getMessage());
            }
            return new Resolved(BlockVariants.clipboard(runtime.states(), state), ScatterSettings.Variant.ALL_TURNS,
                    null, state, null);
        }
        return switch (((ScatterSource.Held) source).ref()) {
            case SourceRef.Clipboard c -> edits.clipboards().find(p.getUuid(), c.id())
                    .map(held -> new Resolved(held.clipboard(), ScatterSettings.Variant.ALL_TURNS, null, -1, null))
                    .orElseThrow(() -> new EditRejected(RejectReason.INVALID, "unknown clipboard"));
            case SourceRef.Asset a -> {
                Library.Viewer viewer = edits.libraryViewer(p);
                Optional<AssetCache.Asset> asset = edits.assets().get(a.contentHash())
                        .filter(found -> edits.mayRead(found.path(), viewer));
                if (asset.isEmpty()) {
                    throw new EditRejected(RejectReason.ASSET_NOT_LOADED,
                            "asset not loaded on the server (request its preview first)");
                }
                yield new Resolved(asset.get().clipboard(), turnMask(asset.get().info()), asset.get().path(), -1, null);
            }
        };
    }

    /**
     * The planner's survival check for block variants: the (lower) block, turned as it will be placed, must be one
     * vanilla would keep there ({@code BlockState.canPlaceAt}: a flower on grass or dirt, a cactus on sand, sugar cane
     * next to water). Other sources always pass. {@code canPlaceAt} reads the neighbouring columns: an anchor next to a
     * chunk that is not loaded ends as {@code UNLOADED} (the planner never loads one). It is asked through a
     * {@link LoadedOnlyView}, so a rule that reads farther (a mod's) sees void air there instead of loading the chunk.
     *
     * @param blockStates per source index, the block variant's lower state, or -1 for a clipboard or asset
     */
    static ScatterPlanner.SurvivalCheck survivalCheck(FabricStateSpace states, ServerWorld world, int[] blockStates) {
        boolean anyBlock = false;
        for (int state : blockStates) anyBlock |= state >= 0;
        if (!anyBlock) return ScatterPlanner.SurvivalCheck.ALWAYS;
        return new BlockSurvival(states, new LoadedOnlyView(world), blockStates);
    }

    /**
     * {@link #survivalCheck}'s check. A column plant's column (kelp, sugar cane, cactus) is asked cell by cell, each
     * cell standing on the column's cell below it ({@link LoadedOnlyView#assume}): a cactus column stops being
     * accepted where a block beside one of its cells would break it.
     */
    private static final class BlockSurvival implements ScatterPlanner.SurvivalCheck {
        private final FabricStateSpace states;
        private final LoadedOnlyView view;
        private final int[] blockStates;
        private final net.minecraft.util.math.BlockPos.Mutable pos = new net.minecraft.util.math.BlockPos.Mutable();
        /** A column plant's cell states, bottom first, by source and height. */
        private final Map<Long, int[]> columns = new HashMap<>();

        BlockSurvival(FabricStateSpace states, LoadedOnlyView view, int[] blockStates) {
            this.states = states;
            this.view = view;
            this.blockStates = blockStates;
        }

        @Override
        public Outcome check(int source, Transform transform, int x, int y, int z) {
            return check(source, transform, x, y, z, 1);
        }

        @Override
        public Outcome check(int source, Transform transform, int x, int y, int z, int height) {
            int state = blockStates[source];
            if (state < 0) return null;
            for (int cx = (x - 1) >> 4; cx <= (x + 1) >> 4; cx++) {
                for (int cz = (z - 1) >> 4; cz <= (z + 1) >> 4; cz++) {
                    if (!view.loaded(cx, cz)) return Outcome.UNLOADED;
                }
            }
            if (height == 1) return stays(state, transform.applyToState(states, state), x, y, z) ? null : Outcome.SURVIVAL;
            int[] cells = columns.computeIfAbsent(((long) source << 8) | height, key -> {
                Clipboard column = BlockVariants.column(states, state, height);
                int[] list = new int[height];
                for (int k = 0; k < height; k++) list[k] = column.get(0, k, 0);
                return list;
            });
            try {
                for (int k = 0; k < height; k++) {
                    if (k > 0) {
                        view.assume(pos.set(x, y + k - 1, z),
                                states.state(transform.applyToState(states, cells[k - 1])));
                    }
                    if (!stays(state, transform.applyToState(states, cells[k]), x, y + k, z)) return Outcome.SURVIVAL;
                }
            } finally {
                view.forget();
            }
            return null;
        }

        /** Vanilla's {@code canPlaceAt} of {@code placed} at (x, y, z); {@code variant} names the block in a warning. */
        private boolean stays(int variant, int placed, int x, int y, int z) {
            try {
                return states.state(placed).canPlaceAt(view, pos.set(x, y, z));
            } catch (RuntimeException e) {
                // A mod's rule that expects a real World, say; the preview still fails (INVALID) as any planner failure.
                LOG.warn("Sculptory: the survival check (canPlaceAt) of {} failed in a scatter preview: {}",
                        states.blockId(variant).value(), e.toString());
                throw e;
            }
        }
    }

    /** An asset's scatter turns: its listed rotations, none listed meaning unrotated only; no metadata: all. */
    static int turnMask(AssetInfo info) {
        if (info == null) return ScatterSettings.Variant.ALL_TURNS;
        if (info.rotations().isEmpty()) return 1;
        return ScatterSettings.Variant.turnMask(info.rotations());
    }

    /** How far (horizontally, any turn or mirror) a source's cells can land from its anchor. */
    static long reach(Clipboard clipboard) {
        BlockPos size = clipboard.size(), anchor = clipboard.anchor();
        long x = Math.max(Math.abs((long) anchor.x()), Math.abs((long) size.x() - 1 - anchor.x()));
        long z = Math.max(Math.abs((long) anchor.z()), Math.abs((long) size.z() - 1 - anchor.z()));
        return Math.max(x, z);
    }

    /**
     * The up-front work estimate: the area's columns times the density fraction (every column for a target count,
     * since acceptance may have to go through all of them), times the largest source's cells.
     */
    static long estimatedWork(long columns, ScatterSettings.Density density, long largestCells) {
        double candidates = switch (density) {
            case ScatterSettings.Density.Fraction fraction -> columns * fraction.value();
            case ScatterSettings.Density.Count count -> columns;
        };
        double work = Math.ceil(candidates) * Math.max(1, largestCells);
        return work >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) work;
    }

    /**
     * The player's protection as a planner column guard: one {@code PermissionService.chunk} permit per chunk,
     * cached (never loads chunks).
     */
    public static ScatterPlanner.ColumnGuard protectionGuard(PermissionService permissions, ServerPlayerEntity p,
                                                             ServerWorld world) {
        return protectionGuard(permissions, p, world, new Long2ObjectOpenHashMap<>());
    }

    /** {@link #protectionGuard(PermissionService, ServerPlayerEntity, ServerWorld)} caching into {@code permits}. */
    static ScatterPlanner.ColumnGuard protectionGuard(PermissionService permissions, ServerPlayerEntity p,
                                                      ServerWorld world, Long2ObjectOpenHashMap<ChunkPermit> permits) {
        return (x, z) -> chunkPermit(permissions, p, world, permits, x >> 4, z >> 4).allows(x, z);
    }

    /** The player's permit for chunk (cx, cz), asked once and kept in {@code permits}. */
    private static ChunkPermit chunkPermit(PermissionService permissions, ServerPlayerEntity p, ServerWorld world,
                                           Long2ObjectOpenHashMap<ChunkPermit> permits, int cx, int cz) {
        long key = ColumnPlan.pack(cx, cz);
        ChunkPermit permit = permits.get(key);
        if (permit == null) {
            int y = world.getBottomY();
            Box chunk = new Box(new BlockPos(cx << 4, y, cz << 4), new BlockPos((cx << 4) + 15, y, (cz << 4) + 15));
            permit = permissions.chunk(p, world, cx, cz, chunk);
            if (permit == null) permit = ChunkPermit.DENY;
            permits.put(key, permit);
        }
        return permit;
    }

    // ================================================================== planning

    /** One preview in flight. */
    private static final class Task {
        final UUID owner;
        final ServerWorld world;
        final String worldId;
        final ScatterPlanner planner;
        final FabricWorldReader reader;
        final EditExecutor.AreaHold hold;
        final PreviewReply reply;
        final long admittedAt;
        final int admittedTick;
        final List<LibraryPath> libraryPaths;
        /** Whether some variant is a plain block. */
        final boolean blockVariants;
        /** Planning time it may be given ({@code scatter.maxPlanningMillis} when it was admitted). */
        final long maxPlanningNanos;
        final int tileX0, tileZ0, tilesX, tiles;
        /** When the area was first held and ready ({@link Long#MIN_VALUE} before; service clock). */
        long readyAt = Long.MIN_VALUE;
        /** Planning time given so far: the service clock's advance over this preview's own steps. */
        long planned;
        int nextTile;
        ScatterPlanner.Acceptance acceptance;
        boolean closed;

        Task(UUID owner, ServerWorld world, String worldId, ScatterPlanner planner, FabricWorldReader reader,
             EditExecutor.AreaHold hold, PreviewReply reply, long admittedAt, int admittedTick,
             List<LibraryPath> libraryPaths, boolean blockVariants, long maxPlanningNanos) {
            this.owner = owner;
            this.world = world;
            this.worldId = worldId;
            this.planner = planner;
            this.reader = reader;
            this.hold = hold;
            this.reply = reply;
            this.admittedAt = admittedAt;
            this.admittedTick = admittedTick;
            this.libraryPaths = libraryPaths;
            this.blockVariants = blockVariants;
            this.maxPlanningNanos = maxPlanningNanos;
            Box area = planner.areaBounds();
            tileX0 = area.min().x() >> 4;
            tileZ0 = area.min().z() >> 4;
            tilesX = (area.max().x() >> 4) - tileX0 + 1;
            tiles = tilesX * ((area.max().z() >> 4) - tileZ0 + 1);
        }
    }

    /**
     * Housekeeping plus planning with its own budget ({@code scatter.tickShare} of the executor's tick budget, at
     * least {@link #MIN_TICK_NANOS}), for callers without the executor lane (tests).
     */
    public void tick() {
        housekeeping();
        long budget = Math.max(MIN_TICK_NANOS,
                (long) (edits.executor().settings().tickBudgetNanos() * config().scatter.tickShare));
        runLane(System.nanoTime() + budget);
    }

    /**
     * Per server tick: every few ticks, sweeps plans that expired or whose player left or changed world, and ends
     * previews whose player lost {@code use} or {@code scatter} ({@link #revalidate}).
     */
    public void housekeeping() {
        checkThread();
        if (++ticks % SWEEP_TICKS != 0) return;
        edits.scatterPlans().sweep(edits.now(), owner -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(owner);
            return player == null ? null : EngineEditService.worldId(player.getServerWorld());
        });
        long now = edits.now();
        cooldownUntil.values().removeIf(until -> now - until >= 0);
        holdBudgets.sweep(now, tasks::containsKey);
        for (Task task : new ArrayList<>(tasks.values())) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(task.owner);
            if (player == null) continue;
            try {
                revalidate(player);
            } catch (RuntimeException | LinkageError e) {
                // A broken permissions mod: the preview goes on (it ends by itself), and the server tick must too.
                LOG.error("Sculptory: re-checking {}'s scatter permission failed",
                        player.getGameProfile().getName(), e);
            }
        }
    }

    /**
     * Ends the player's preview in flight, releasing its area, when they no longer hold {@code use} and
     * {@code scatter} (a deop, or a node a permissions mod removed): answered {@code NO_PERMISSION}. A check the
     * permissions mod fails (throws) ends nothing.
     *
     * @return whether a preview was ended
     */
    public boolean revalidate(ServerPlayerEntity player) {
        checkThread();
        Task task = tasks.get(player.getUuid());
        if (task == null || task.closed) return false;
        if (permissions.check(player, Perm.USE) != TriState.FALSE
                && permissions.check(player, Perm.SCATTER) != TriState.FALSE) {
            return false;
        }
        fail(task, RejectReason.NO_PERMISSION, "you may no longer scatter");
        return true;
    }

    /**
     * Plans the previews in flight until about {@code end} ({@code System.nanoTime}), oldest first, sharing the time
     * equally. Only the oldest preview that can plan is guaranteed one step when the time is already spent.
     */
    public void runLane(long end) {
        checkThread();
        deliverEncoded();
        List<Task> round = new ArrayList<>(tasks.values());
        boolean guaranteed = false;
        for (int i = 0; i < round.size(); i++) {
            Task task = round.get(i);
            if (task.closed) continue;
            long now = System.nanoTime();
            long until = now + Math.max(0, (end - now) / (round.size() - i));
            if (run(task, until, !guaranteed)) guaranteed = true;
        }
    }

    /** Plans one preview until {@code until}; with {@code mustStep}, at least one step. Whether it stepped. */
    private boolean run(Task task, long until, boolean mustStep) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(task.owner);
        if (player == null) {
            close(task); // no longer on the server (playerLeft not called, e.g. a mock player): nobody to answer
            return false;
        }
        if (player.getServerWorld() != task.world) {
            fail(task, RejectReason.INVALID, "you changed worlds while the scatter was planned");
            return false;
        }
        if (!task.hold.ready()) {
            if (edits.now() - task.admittedAt >= HOLD_WAIT_NANOS) {
                fail(task, RejectReason.AREA_BUSY, "another edit is still working in the area");
            }
            return false;
        }
        if (task.readyAt == Long.MIN_VALUE) task.readyAt = edits.now();
        if (edits.now() - task.readyAt >= PLANNING_WALL_LIMIT_NANOS) {
            fail(task, RejectReason.RATE_LIMITED, "the server is too busy to plan this scatter now; try again");
            return false;
        }
        task.reader.invalidate(); // chunks may have unloaded since the last tick
        long maxPlanning = task.maxPlanningNanos;
        boolean stepped = false;
        try {
            while ((mustStep && !stepped) || System.nanoTime() < until) {
                if (task.planned > maxPlanning) {
                    cooldownUntil.put(task.owner, edits.now() + DEADLINE_COOLDOWN_NANOS);
                    fail(task, RejectReason.TOO_LARGE, "planning took too long (paint a smaller area)");
                    return stepped;
                }
                long start = edits.now();
                Runnable observer = stepObserver;
                if (observer != null) observer.run();
                boolean done;
                try {
                    done = step(task);
                } finally {
                    task.planned += Math.max(0, edits.now() - start);
                }
                stepped = true;
                if (done) {
                    complete(task);
                    return true;
                }
            }
        } catch (ScatterPlanner.GrownCellsExceeded e) {
            fail(task, RejectReason.TOO_LARGE, "the trees and features would grow more than " + e.cap()
                    + " blocks (lower the density or paint a smaller area)");
        } catch (RuntimeException e) {
            LOG.error("Sculptory: scatter planning failed", e);
            fail(task, RejectReason.INVALID, "internal error");
        }
        return stepped;
    }

    /** One bounded unit of planning: a survey tile, starting acceptance, or an acceptance step. True when done. */
    private static boolean step(Task task) {
        if (task.acceptance == null) {
            if (task.nextTile < task.tiles) {
                int cx = task.tileX0 + task.nextTile % task.tilesX, cz = task.tileZ0 + task.nextTile / task.tilesX;
                task.nextTile++;
                task.planner.survey(cx << 4, cz << 4, (cx << 4) + 15, (cz << 4) + 15);
                return false;
            }
            task.acceptance = task.planner.acceptance();
            return false;
        }
        return task.acceptance.step(ACCEPT_STEP_WORK);
    }

    private void complete(Task task) {
        ScatterPlan plan = task.acceptance.plan();
        close(task);
        ScatterPlans.Held held = edits.scatterPlans().put(task.owner, task.worldId, plan, edits.now(), task.libraryPaths,
                task.blockVariants);
        TreeMap<String, Integer> counts = new TreeMap<>();
        for (Map.Entry<Outcome, Long> entry : plan.rejectedCounts().entrySet()) {
            counts.put(entry.getKey().name(), Math.toIntExact(entry.getValue()));
        }
        // The plan is the player's now (checked and held here). With grown cells its payloads are plain data, encoded off
        // this thread so the tick that finishes a large plan stays short. The answer follows on a later tick ({@link #deliverEncoded}).
        FabricStateSpace states = runtime.states();
        UUID planId = held.id();
        if (plan.grownCells() == 0) {
            // Nothing grew: the placements alone encode quickly, answered now as they always were.
            PlanReady ready = new PlanReady(planId, plan.placements().size(), counts, plan.totalCells(),
                    plan.bounds().orElse(null), ScatterPlacements.encode(plan.placements()));
            answer(task, () -> task.reply.done(ready));
            return;
        }
        FutureTask<PlanReady> ready = new FutureTask<>(() -> new PlanReady(planId, plan.placements().size(), counts,
                plan.totalCells(), plan.bounds().orElse(null), ScatterPlacements.encode(plan.placements()),
                grownPayload(plan, states)));
        encoder().execute(ready);
        encoding.put(task.owner, new Encoding(planId, task.reply, ready));
    }

    /** The encoder thread, started on first use. */
    private ThreadPoolExecutor encoder() {
        if (encoder == null) {
            encoder = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
                Thread thread = new Thread(runnable, "Sculptory scatter encoder");
                thread.setDaemon(true);
                return thread;
            });
        }
        return encoder;
    }

    /**
     * Drops a pending encoding whose reply will not be sent (superseded, the player left, the server stops): work not
     * started yet is taken off the encoder's queue and never runs, so the dropped plan is not held; work already
     * running finishes and is discarded.
     */
    private void drop(Encoding pending) {
        if (pending == null) return;
        pending.ready().cancel(false);
        if (encoder != null) encoder.remove(pending.ready());
    }

    /** Whether the encoder thread is running (test hook: GameTests check {@link #shutdown} stops it). */
    public boolean encoderRunning() {
        return encoder != null && !encoder.isShutdown();
    }

    /** Encodings waiting on the encoder's queue, not started (test hook). */
    public int encodingsQueued() {
        return encoder == null ? 0 : encoder.getQueue().size();
    }

    /** Test hook (GameTests): occupies the encoder thread until {@code gate} opens, so encodings queue behind it. */
    public void holdEncoder(CountDownLatch gate) {
        checkThread();
        encoder().execute(() -> {
            try {
                gate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** A finished plan whose payloads are being encoded, and the reply waiting for them. */
    private record Encoding(UUID planId, PreviewReply reply, FutureTask<PlanReady> ready) {}

    /**
     * Answers the previews whose payloads are ready, on the server thread. A failed encoding (never expected) drops the
     * plan and is answered {@code INVALID}.
     */
    private void deliverEncoded() {
        Iterator<Map.Entry<UUID, Encoding>> pending = encoding.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<UUID, Encoding> entry = pending.next();
            Encoding encoded = entry.getValue();
            if (!encoded.ready().isDone()) continue;
            pending.remove();
            PlanReady ready;
            try {
                ready = encoded.ready().get();
            } catch (ExecutionException | InterruptedException | RuntimeException e) {
                LOG.error("Sculptory: encoding a scatter plan failed", e);
                edits.scatterPlans().consume(entry.getKey(), encoded.planId());
                answer(encoded.reply()::failed, RejectReason.INVALID, "internal error");
                continue;
            }
            PlanReady done = ready;
            answerEncoded(() -> encoded.reply().done(done));
        }
    }

    private static void answer(java.util.function.BiConsumer<RejectReason, String> reply, RejectReason reason,
                               String detail) {
        answerEncoded(() -> reply.accept(reason, detail));
    }

    private static void answerEncoded(Runnable reply) {
        try {
            reply.run();
        } catch (RuntimeException e) {
            LOG.error("Sculptory: scatter reply failed", e);
        }
    }

    /** Whether a finished plan's payloads are still being encoded (its reply not sent yet). */
    public boolean encoding(UUID player) {
        return encoding.containsKey(player);
    }

    /** Whether the player's plan payloads are encoded and its reply goes out on the next planning tick. */
    public boolean encoded(UUID player) {
        Encoding pending = encoding.get(player);
        return pending != null && pending.ready().isDone();
    }

    /**
     * The {@code SCATTER_GENERATED} payload of a plan: every cell its trees and features grow, with the state the commit
     * writes (air included), as a sparse upload; {@code null} when they grow none. Block-entity data is not carried
     * (the ghosts do not need it).
     */
    public static byte[] grownPayload(ScatterPlan plan, FabricStateSpace states) {
        if (plan.grownCells() == 0) return null;
        GeneratedSource.Builder cells = GeneratedSource.builder(plan.grownCells());
        for (GrownFeature cluster : plan.clusters()) {
            for (int i = 0; i < cluster.size(); i++) {
                cells.set(cluster.x(i), cluster.y(i), cluster.z(i), cluster.after(i));
            }
        }
        return SparseUpload.encode(cells.build(), states);
    }

    private void fail(Task task, RejectReason reason, String detail) {
        close(task);
        answer(task, () -> task.reply.failed(reason, detail));
    }

    /** Ends a task: out of the table, area released, its blocking time charged to its player. Idempotent. */
    private void close(Task task) {
        if (task.closed) return;
        task.closed = true;
        tasks.remove(task.owner, task);
        task.hold.release();
        long now = edits.now();
        holdBudgets.charge(task.owner, task.hold.blockingFor(now), now);
    }

    private static void answer(Task task, Runnable reply) {
        try {
            reply.run();
        } catch (RuntimeException e) {
            LOG.error("Sculptory: scatter reply failed", e);
        }
    }

    // ================================================================== lifecycle

    /** Whether the player has a preview in flight. */
    public boolean planning(UUID player) {
        return tasks.containsKey(player);
    }

    /** Previews in flight. */
    public int active() {
        return tasks.size();
    }

    /** Footprints cached across previews. */
    public FootprintCache footprints() {
        return footprints;
    }

    /** How long the player's cooldown still runs (service clock), 0 when there is none. */
    public long cooldownNanos(UUID player) {
        Long until = cooldownUntil.get(player);
        return until == null ? 0 : Math.max(0, until - edits.now());
    }

    /** The player's hold budget left now (service clock; negative when overdrawn), not counting a preview in flight. */
    public long holdLeftNanos(UUID player) {
        return holdBudgets.left(player, edits.now());
    }

    /** The chunk columns the player's preview in flight holds, or 0 when none is in flight. */
    public long heldColumnCount(UUID player) {
        Task task = tasks.get(player);
        return task == null ? 0 : task.hold.columns();
    }

    /**
     * Test hook (GameTests): runs before every planning step, on the server thread; {@code null} clears it. A throwing
     * observer stands for a planner failure; one that moves the service clock makes the deadline testable.
     */
    public void observeSteps(Runnable observer) {
        stepObserver = observer;
    }

    /**
     * The player left: their preview in flight ends without a reply (their plan goes with the edit service's). Their
     * cooldown and hold budget stay, so reconnecting does not reset them; housekeeping drops them once spent.
     */
    public void playerLeft(UUID player) {
        checkThread();
        Task task = tasks.get(player);
        if (task != null) close(task);
        drop(encoding.remove(player));
    }

    /** Server stop: ends every preview without a reply and releases their areas. */
    public void shutdown() {
        for (Task task : new ArrayList<>(tasks.values())) close(task);
        for (Encoding pending : encoding.values()) drop(pending);
        encoding.clear();
        if (encoder != null) {
            encoder.shutdown();
            encoder = null;
        }
        cooldownUntil.clear();
        holdBudgets.clear();
    }

    // ================================================================== helpers

    private void require(ServerPlayerEntity p, Perm node) throws EditRejected {
        if (!permissions.has(p, node)) throw new EditRejected(RejectReason.NO_PERMISSION, node.node());
    }

    /**
     * A plain block as a scatter variant needs {@code brush} or {@code region} besides {@code scatter}: those players
     * can already place any block state, so it gives nobody a new power; a scatter-only player keeps to clipboards and
     * library assets.
     */
    private void requireBlockVariants(ServerPlayerEntity p) throws EditRejected {
        if (!mayScatterBlocks(permissions, p)) {
            throw new EditRejected(RejectReason.NO_PERMISSION, BLOCK_VARIANTS_NEED);
        }
    }

    /**
     * Trees and features need what plain blocks need ({@code brush} or {@code region}, {@link #mayScatterBlocks}): they
     * place vanilla blocks of the server's choosing, bee nests with bees included.
     */
    private void requireFeatures(ServerPlayerEntity p) throws EditRejected {
        if (!mayScatterBlocks(permissions, p)) throw new EditRejected(RejectReason.NO_PERMISSION, FEATURES_NEED);
    }

    private static String clip(String text) {
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }

    /** Whether the player may use plain blocks as scatter variants ({@code brush} or {@code region}). */
    public static boolean mayScatterBlocks(PermissionService permissions, ServerPlayerEntity p) {
        return permissions.has(p, Perm.BRUSH) || permissions.has(p, Perm.REGION);
    }

    private void checkThread() {
        if (!server.isOnThread()) throw new IllegalStateException("ServerScatter must be used on the server thread");
    }
}
