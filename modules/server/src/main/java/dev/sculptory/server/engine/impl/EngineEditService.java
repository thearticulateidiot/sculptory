package dev.sculptory.server.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushKernel;
import dev.sculptory.core.brush.BrushKernels;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.CellSink;
import dev.sculptory.core.brush.ColumnFilter;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.ShapeStamp;
import dev.sculptory.core.brush.ShapeStep;
import dev.sculptory.core.brush.StrokeState;
import dev.sculptory.core.brush.SymmetricStep;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CompileContext;
import dev.sculptory.core.edit.NeighbourShapes;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.EditTooLargeException;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpRegions;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.EntityHistory;
import dev.sculptory.core.history.EntityMatcher;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.history.TileMatcher;
import dev.sculptory.core.mask.BoundMask;
import dev.sculptory.core.mask.MaskSide;
import dev.sculptory.core.mask.MaskedKernel;
import dev.sculptory.core.mask.MaskedProgram;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.RegionTooLargeException;
import dev.sculptory.core.region.Regions;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.EditService;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.TinkerService;
import dev.sculptory.server.library.Library;
import dev.sculptory.server.library.LibraryPath;
import dev.sculptory.server.platform.FluidTrailHook;
import dev.sculptory.server.platform.LiveReader;
import dev.sculptory.server.platform.PlatformPermissions;
import dev.sculptory.server.platform.WorldEntities;
import dev.sculptory.server.platform.WorldWriter;
import dev.sculptory.server.platform.WriteOptions;
import dev.sculptory.server.schem.SanitizedTile;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server {@link EditService} for one running engine ({@link EngineHost}), with each player's
 * undo history ({@link HistoryService}) and brush strokes. Server thread only.
 *
 * <p><b>Region ops</b> ({@link #run}) need {@code use} and {@code region}; ops over {@code Limits.maxOpVolume}
 * cells need {@code limit.bypass} ({@code TOO_LARGE}). They compile against the player's world height and run as
 * executor jobs recording every changed cell. A job that changed anything pushes one history entry labelled like
 * "Fill · 125,000 blocks" (cancelled or failed jobs keep their applied work, marked in the label).
 *
 * <p><b>Copying ops</b> (M2). {@code Paste} needs {@code clipboard} instead of {@code region}; its source is the
 * player's own clipboard (by id) or a library asset already loaded on the server ({@link #assets()}, filled by
 * previews and library loads) that the player may read, else {@code INVALID} (an unknown clipboard) or
 * {@code ASSET_NOT_LOADED} (an asset not loaded, or not readable: previewing it loads it). {@code PasteOptions.physics}
 * turns physics on (and needs {@code physics}). {@code Move} and {@code Stack} need {@code region}; a stack makes at most
 * {@value #MAX_STACK_COUNT} copies. The volume limit applies to the written volume, checked before compiling with
 * {@code OpCompiler.targetVolume}/{@code sourceVolume} (unclipped: the paste source's volume, destination plus
 * vacated cells for a move, count × box for a stack) and passed to the compiler as its budget. Coordinates that
 * would leave the int range are {@code INVALID}.
 * <ul>
 *   <li><b>Move is all or nothing:</b> every column of its source and of its destination must be writable by the
 *       player ({@link #requireWritable}, whatever their bypass), and the destination must stay inside the build
 *       height, else {@code PROTECTED} / {@code INVALID}; a move that skipped cells would vacate content it could
 *       not place.</li>
 *   <li><b>Stack</b> sources follow the source-read rule ({@link #requireReadable}).</li>
 *   <li><b>Regions</b>. Every Select op takes a box, a shape or a cell set
 *       ({@link #admitRegion}: an unresolved upload is {@code SELECTION_NOT_LOADED}, a shape of too many rows
 *       {@code TOO_LARGE}). Volumes count cells ({@code OpCompiler.targetVolume}; a fill, replace or erase its cells
 *       inside the build height), and the move and source-read rules above ask only for the columns holding the
 *       region's cells, and those they land in ({@code Regions.columns}), not for the whole bounds. The executor locks,
 *       loads and protects only the sections the program lists, which hold region cells.</li>
 *   <li><b>Trust:</b> tiles keep their origin. World-captured tiles (copies, moves, stacks) are trusted when the
 *       player could write their source; a bypass player's stack from a protected source runs with captured tiles
 *       untrusted. Tiles read from files are never trusted (operator NBT stripped, see {@code WorldWriter}).</li>
 * </ul>
 *
 * <p><b>Scatter commits</b> (M3). {@code ScatterCommit(planId)} needs {@code scatter}; the plan must be the player's
 * live plan ({@link #scatterPlans()}, made by {@link ServerScatter}), not expired, and made in the player's current
 * world, else {@code INVALID}; the player must still be allowed to read the plan's library assets, and for a plan
 * with block variants still hold {@code brush} or {@code region} ({@code NO_PERMISSION}). Its cells count against
 * {@code maxOpVolume} like a paste's ({@code TOO_LARGE}; also the compiler's budget), and it runs with the same chunk
 * permits, world border and trust rules as a paste: every placement's tiles keep their origin (library and file tiles
 * are sanitized, world copies trusted only under the source-read rule). The plan reflects the world at preview time,
 * so the commit writes <b>only into cells that are still open</b> ({@code MultiPaste.Replace.OPEN}): once its chunks
 * are loaded, a placement any of whose cells was built on since (a chest, a wall, tall grass it would take only half
 * of) or lies where the player may not write (protection, the world border) is skipped whole; right before its first
 * write its cells are checked again, and every write is guarded per cell as well (a cell built on while the job runs
 * is left alone and its placement cut short: nothing more of it is written). Skipped and cut-short placements are
 * reported in {@code JobFinished.skippedConflicts} and through {@link EditEvents#scatterSkipped}. It is one job and one
 * history entry ("Scatter · 142 placements · 38,000 blocks"), and it consumes the plan.
 *
 * <p><b>Undo and redo</b> are executor jobs over the recorded cells ({@link HistoryPrograms}); conflicts are counted
 * and reported in {@code JobFinished.skippedConflicts}. Only one runs per player at a time: a second is refused
 * with {@code QUEUE_FULL}, and pushes wait for it (see {@link HistoryService}). They are also refused with
 * {@code QUEUE_FULL} while one of the player's own jobs is still running, since its entry would land after the
 * undo and clear the entry just undone. They run in the world the edit was made in. Their events go to the
 * listener from the constructor's provider, or only to the caller's listener with the command overloads. A
 * cancelled or failed redo that changed anything still counts as redone, so its partial result is undoable; a
 * cancelled or failed undo leaves its entry the undo candidate. <b>Undo anyway</b> ({@link #historyOverwrite})
 * re-applies the player's latest run of undo (or redo) steps with {@code OVERWRITE} under the same rules.
 *
 * <p><b>Permissions lost mid-job</b> (deop, or a node a permissions mod removed): every request re-checks the nodes,
 * and {@link #revalidate} cancels the player's admitted jobs that need a node they no longer hold (at once for a
 * deop, within {@value #PERMISSION_RECHECK_TICKS} ticks otherwise). Applied work is kept and stays in history.
 *
 * <p><b>Strokes.</b> One open stroke per player. {@link #beginStroke} needs {@code use} and {@code brush}, a radius
 * within {@code maxBrushRadius} and a Shape brush no taller than its diameter {@code 2 × maxBrushRadius + 1} (else
 * {@code TOO_LARGE}, unless {@code limit.bypass}; a Shape step over {@link ShapeStamp#MAX_STEP_CELLS} is
 * {@code TOO_LARGE} for everyone, a backstop no valid spec reaches), material states of the state
 * space and a clip box ("only inside selection") inside the world (else {@code INVALID}). The clip box travels in
 * the {@code BrushSpec}, so the kernel drops every write outside it on the client and the server alike; it only
 * narrows the cells the per-cell checks below then allow. {@link #dabs} validates a batch and queues one
 * {@link BrushWork} per dab on the executor's brush lane; each runs {@code BrushKernels.forTool} against the live
 * world, writes allowed cells with physics off, records them into the stroke's {@link RecordBuilder} (first
 * before, last after) and clears scheduled ticks. After a batch's last dab is written or dropped, the
 * {@link AckSink} gets its sequence, exactly once, in admission order.
 *
 * <p><b>Shape steps</b> are written in parts ({@link ShapeStep}: a placement's run of layers reading at most
 * {@link ShapeStep#PART_CELLS} cells, about 4 ms of writes): each turn of the player on the lane writes one, then more
 * while the next is predicted (from the measured rate) to end within the lane's budget, so a large step spans several
 * ticks and other players' dabs run between its parts. Before each part after the step's first, the permission is
 * asked again and the part's area checked for loaded chunks and locks; a refusal ends the stroke as below, and what the
 * earlier parts wrote stays in the stroke's record (undoable). The batch is acknowledged after the last part, and
 * {@link EditEvents#dabsApplied} tells the network layer, which paces the client on it.
 *
 * <p><b>Symmetry.</b> The spec's {@link Symmetry} centre must lie within the world ({@code INVALID} otherwise). The
 * server replicates each dab itself ({@link Symmetry#copies}), stands each copy on the ground where it lands and
 * applies the dab and its copies as one kernel step ({@link SymmetricStep}: the copies are located in the lane against
 * the world right before the step, the world the kernel reads), so the result is what the client predicts. Every check
 * below is made for every copy: its chunks must be loaded, the area it reads and writes at the height it stands on and
 * the column its ground was searched in must not be locked, and its writes get per-column permits. A copy that finds no
 * ground writes nothing while the others write, and {@link EditEvents#symmetryNoGround} reports it once per stroke. The
 * queued-dab budget counts every copy, grounded or not.
 *
 * <p>Refusals in the {@code DabOutcome}:
 * <ul>
 *   <li>{@code INVALID}: unknown stroke, empty or oversized batch, dab indices not increasing, another world, a dab
 *       or one of its copies beyond the world's horizontal limit;</li>
 *   <li>{@code NO_PERMISSION}, {@code DISABLED};</li>
 *   <li>{@code RATE_LIMITED}: more than {@value #MAX_QUEUED_DABS} units would be queued for the player (a terrain dab
 *       costs its copies, a Shape dab its placements in work units of {@link ShapeStamp#WORK_UNIT_CELLS} cells; the
 *       network layer reports admitted dabs as acknowledged, so the backlog must stay bounded), except a batch of one
 *       dab when nothing of the player's is queued;</li>
 *   <li>{@code AREA_BUSY}: a dab's (or a copy's) area touches a section locked by an admitted (queued or running)
 *       job, or a copy's ground search does ({@link #stepBoxes});</li>
 *   <li>{@code UNLOADED}: a dab's (or a copy's) area touches an unloaded chunk.</li>
 * </ul>
 * <b>Dabs and bulk jobs:</b> a job admitted while dabs are queued over its area wins. When the lane reaches such a
 * dab it re-checks the section locks and skips it. Dabs never write into a section a job holds, so a job's
 * captured "before" is never stale.
 *
 * <p><b>Refusals found by the lane</b> (after admission): {@code AREA_BUSY} as above, {@code UNLOADED},
 * {@code NO_PERMISSION} (the node was removed), {@code PROTECTED} (every cell the dab would change is protected)
 * and {@code INVALID} (the kernel failed). The dab is skipped, the stroke is marked rejected and
 * {@link EditEvents#dabRejected} reports it once, so the network layer can end the stroke on the client
 * ({@code StrokeStatus REJECTED}). The stroke's remaining queued dabs are skipped too, and its later
 * batches are refused with the same reason until a new {@link #beginStroke}. Batches are still acknowledged, so
 * predictions revert.
 *
 * <p>A stroke's record becomes one history entry ("Raise stroke · 1,284 blocks"; the Shape brush's "Shape · 1,284
 * blocks") when it ends:
 * {@link #endStroke}, a new {@link #beginStroke}, or disconnect and server stop when history is saved (without
 * saving, history is dropped at disconnect anyway). While open, and until its entry is pushed, its changed sections are
 * saved so a crash leaves them undoable ({@link #beforeChunkSave}). Its queued dabs are applied first, and when that and
 * building the record are small work (queued dabs predicted to take at most {@value #HEAVY_NANOS} ns, at most
 * {@value #SMALL_RECORD_SECTIONS} sections to prepare) it happens at once, so the entry is in the history when the call
 * returns; otherwise the lane finishes the dabs and builds the record a few sections a tick, journaling each
 * ({@link CommitWork}), and pushes the entry then, in order with the player's other strokes. A region op, an undo or
 * redo during a stroke, or {@value #STROKE_IDLE_SECONDS} s without dab activity ({@link #tick}), commits the record so
 * far as an entry (so the history follows the real write order) and keeps the stroke open; a region op, undo, redo,
 * Undo anyway, copy or scatter preview while such large work is still queued is refused {@code QUEUE_FULL} with
 * {@link EditRejected#STROKE_PENDING} rather than doing it in one tick ({@link #commitStroke}): the client retries it.
 * A stroke's record is also committed
 * (queued ahead of its next dabs) once it grows past {@link #STROKE_COMMIT_BYTES} or a quarter of the per-player cap,
 * whichever is smaller: open records are not counted by the global history cap, so this bounds what they can hold.
 * Empty records push nothing.
 *
 * @param <P> the platform's player type
 * @param <W> the platform's world type
 */
public final class EngineEditService<P, W> implements EditService<P>, TinkerService<P> {
    /** The refusal of a scatter commit whose every placement the mask (changed since the preview) rules out. */
    public static final String MASK_RULES_OUT_SCATTER =
            "the mask rules out every placement (change the mask or preview again)";
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    /**
     * Units of dabs a player may have admitted but not yet applied (a terrain dab costs its copies, a Shape dab its
     * placements' work units); beyond it batches are refused with RATE_LIMITED, except a single dab when nothing of
     * the player's is queued (one step larger than the cap always goes through).
     */
    public static final int MAX_QUEUED_DABS = 32;
    /**
     * How long the queued dabs a region op, undo or redo still finishes at once before it runs may take
     * ({@link #commitStroke}), predicted from the lane's measured rates as the lane predicts its pieces; beyond it the
     * request is refused {@code QUEUE_FULL} ({@link EditRejected#STROKE_PENDING}) while the lane catches up.
     */
    static final long HEAVY_NANOS = 15_000_000L;
    public static final int STROKE_IDLE_SECONDS = 5;
    /** A stroke with queued-dab activity this long ago has its record committed to history. */
    public static final long STROKE_IDLE_NANOS = STROKE_IDLE_SECONDS * 1_000_000_000L;
    /** An open stroke's record is committed to history once it holds more than this (estimated heap). */
    public static final long STROKE_COMMIT_BYTES = 16L << 20;
    /**
     * A stroke record with at most this many 16³ sections left to prepare ({@link RecordBuilder#unpreparedSections}:
     * sections holding a state both before and after, about 50 µs each) is built at once when it is committed; a larger
     * one is prepared over several ticks first ({@link CommitWork}). Sections that only replaced other states (blocks
     * placed into air) cost next to nothing to build and do not count.
     */
    static final int SMALL_RECORD_SECTIONS = 64;
    /** Time a tick may spend journaling open strokes' changed sections in the periodic save. */
    static final long STROKE_SAVE_NANOS = 2_000_000L;
    /** How far a dab's reads and writes reach beyond its radius and scan margin (see {@link #dabBox}). */
    private static final int SCAN_MARGIN = 8;
    /** Most copies one stack may make. */
    public static final int MAX_STACK_COUNT = 256;
    /** Server ticks between re-checks of the permissions of players with admitted jobs ({@link #revalidate}). */
    public static final int PERMISSION_RECHECK_TICKS = 40;
    /**
     * The game's horizontal world limit: no block lies farther than {@value} from the origin along x or z
     * (Minecraft's {@code World.HORIZONTAL_LIMIT}, the same on every platform).
     */
    public static final int HORIZONTAL_LIMIT = 30_000_000;

    /** A running job, for {@code /sculptory jobs}. */
    public record JobInfo(UUID jobId, UUID owner, String label, long estimatedCells, long done, long total,
                          Phase phase) {}

    private final EngineHost<P, W> runtime;
    private final EditExecutor<W> executor;
    /**
     * The global mask of the Move {@link #run} is compiling ({@link MaskSide#SOURCE}),
     * which {@link #compile}'s context gives as its {@code sourceMask()}; {@link BoundMask#ALL} otherwise. Server
     * thread only.
     */
    private BoundMask compileSourceMask = BoundMask.ALL;
    private final PlatformPermissions<P, W> permissions;
    private final Function<P, JobListener> listeners;
    private final AckSink<P> acks;
    private final EditEvents<P> events;
    private final LongSupplier clock;
    private final HistoryService history;
    /** What fluid written by history steps did since, taken back with their next step. */
    private final FluidTrailHook<W> trails;
    /** Builder mode: placements and breaks in normal creative play (the platform's). */
    private final BuilderMode<P> builder;
    /** Held here: {@link FluidTrailHook#register} references it weakly. */
    private final Supplier<Set<UUID>> liveEntries = this::liveEntries;
    private final Map<UUID, BrushLane> lanes = new HashMap<>();
    /**
     * Measured costs, averaged over recent work, for the brush lane's predictions ({@link BrushWork#nextPieceNanos}):
     * a Shape cell read and written, and a record section prepared (and journaled).
     */
    private double shapeCellNanos = 250;
    private long sectionNanos = 50_000;
    private final Map<UUID, TrackedJob> jobs = new LinkedHashMap<>();
    private final PlayerClipboards clipboards = new PlayerClipboards();
    private final AssetCache assets = new AssetCache();
    /** The library the cached assets' access is checked against ({@link #attachLibrary}); {@code null} without one. */
    private volatile Library library;
    private final ScatterPlans scatterPlans = new ScatterPlans();
    /** Batches dropped while being admitted, acknowledged at the next {@link #tick}. */
    private final List<DabBatch> deferredAcks = new ArrayList<>();
    /** Per player who has left at least once: the value {@link #connection} reports. */
    private final Map<UUID, Long> connections = new HashMap<>();
    private long connectionCounter;
    private int ticksSinceRecheck;
    private int ticksSinceStrokeSave;
    /** The periodic save of open strokes is under way ({@link #tickHistory}). */
    private boolean strokeSaveDue;
    private long lastCreatedMillis;
    /** Tinker: one block or entity changed in place, one history step (the platform's). */
    private final TinkerService<P> tinker;

    /**
     * @param listeners the listener for jobs started without one (undo and redo), e.g. {@code ServerNet::jobListener}
     * @param acks where dab batches are acknowledged, e.g. {@code ServerNet::predictionApplied}
     */
    public EngineEditService(EngineHost<P, W> runtime, Function<P, JobListener> listeners, AckSink<P> acks,
                             EditEvents<P> events) {
        this(runtime, listeners, acks, events, null);
    }

    /** Saving history through {@code persistence} (null: memory only). */
    public EngineEditService(EngineHost<P, W> runtime, Function<P, JobListener> listeners, AckSink<P> acks,
                             EditEvents<P> events, HistoryService.Persistence persistence) {
        this(runtime, runtime.executor(), runtime.config().toHistoryLimits(), listeners, acks, events, System::nanoTime,
                persistence);
    }

    /** With an explicit executor, history caps and clock (tests); history in memory only. */
    public EngineEditService(EngineHost<P, W> runtime, EditExecutor<W> executor, HistoryLimits limits,
                             Function<P, JobListener> listeners, AckSink<P> acks, EditEvents<P> events,
                             LongSupplier clock) {
        this(runtime, executor, limits, listeners, acks, events, clock, null);
    }

    /** With an explicit executor, history caps, clock and history persistence (null: memory only). */
    public EngineEditService(EngineHost<P, W> runtime, EditExecutor<W> executor, HistoryLimits limits,
                             Function<P, JobListener> listeners, AckSink<P> acks, EditEvents<P> events,
                             LongSupplier clock, HistoryService.Persistence persistence) {
        this.runtime = Objects.requireNonNull(runtime);
        this.executor = Objects.requireNonNull(executor);
        this.permissions = runtime.permissions();
        this.listeners = listeners == null ? p -> JobRequest.NO_LISTENER : listeners;
        this.acks = acks == null ? runtime::acknowledge : acks;
        this.events = events == null ? EditEvents.none() : events;
        this.clock = Objects.requireNonNull(clock);
        this.history = new HistoryService(limits, new HistoryEvents(), persistence);
        this.trails = runtime.fluidTrails();
        trails.register(liveEntries);
        // What an entry's fluid did goes into it (and the journal) when its history is unloaded or the server stops.
        history.trailSource(entry -> {
            W world = worldOf(entry.world());
            return world == null ? null : trails.drain(world, entry.id());
        });
        this.builder = runtime.builderMode(this, history, executor, clock);
        this.tinker = runtime.tinker(this);
    }

    /** The config in effect: the runtime's, which {@code /sculptory reload} replaces (checks read it when they run). */
    private SculptoryConfig config() {
        return runtime.config();
    }

    /** The platform this service edits (and the engine it runs). */
    public EngineHost<P, W> runtime() {
        return runtime;
    }

    public EditExecutor<W> executor() {
        return executor;
    }

    public HistoryService historyService() {
        return history;
    }

    /** The player's history as the client should see it. */
    public HistorySnapshot history(P player) {
        return history.snapshot(runtime.id(player));
    }

    /**
     * Every entry this service's history holds or will hold: the history's, the running edits' and the open strokes'
     * records ({@link FluidTrailHook} forgets the fluid of the others); {@code null} while a saved history is loading.
     */
    private Set<UUID> liveEntries() {
        Set<UUID> ids = history.liveEntries();
        if (ids == null) return null;
        for (TrackedJob job : jobs.values()) {
            if (job.record != null) ids.add(job.record.id());
        }
        for (BrushLane lane : lanes.values()) {
            if (lane.stroke != null) ids.add(lane.stroke.record.id());
            for (StrokeSession stroke : lane.closing) ids.add(stroke.record.id());
        }
        builder.liveEntries(ids);
        return ids;
    }

    // ================================================================== region ops

    @Override
    public JobTicket run(P p, OpSpec s, RunOptions o, JobListener l) throws EditRejected {
        return run(p, s, o, l, null);
    }

    /**
     * {@link #run(Object, OpSpec, RunOptions, JobListener)} with extra entity work for the job ({@code null}:
     * none): a cut's erase removes the entities its copy took, in the same history entry.
     */
    JobTicket run(P p, OpSpec s, RunOptions o, JobListener l, EntityWork extraEntities)
            throws EditRejected {
        return run(p, s, o, l, extraEntities, false);
    }

    /**
     * {@link #run(Object, OpSpec, RunOptions, JobListener, EntityWork)}; with {@code judged} the global mask
     * was applied by the caller already (a cut erases exactly the cells its copy took), so the op is not masked again.
     */
    JobTicket run(P p, OpSpec s, RunOptions o, JobListener l, EntityWork extraEntities, boolean judged)
            throws EditRejected {
        checkThread();
        Objects.requireNonNull(s);
        Objects.requireNonNull(o);
        requireEditing();
        require(p, Perm.USE);
        // The player's global mask as the op is admitted; refused while it is refused.
        BoundMask current = EditMasks.current(runtime.id(p));
        BoundMask editMask = judged ? BoundMask.ALL : current;
        Perm opNode = switch (s) {
            case OpSpec.Paste paste -> Perm.CLIPBOARD;
            case OpSpec.ScatterCommit commit -> Perm.SCATTER;
            default -> Perm.REGION;
        };
        require(p, opNode);
        Region region = OpRegions.region(s);
        boolean bypass = permissions.has(p, Perm.LIMIT_BYPASS);
        if (region != null) admitRegion(region, bypass);
        // A symmetric op is the op on its region and on each image of it; every check below covers every copy, and the copies compile into one job.
        List<OpSymmetry.Copy> copies = symmetricCopies(s);
        int copyCount = copies.size();
        if (copyCount > 1) {
            for (OpSymmetry.Copy copy : copies) {
                Region copyRegion = OpRegions.region(copy.op());
                if (copyRegion != null) requireInsideWorld(copyRegion.bounds(), "a symmetric copy");
            }
        }
        // The open stroke's dabs so far are older than this op: apply them and push them first.
        commitStroke(runtime.id(p));
        W world = runtime.world(p);
        long limit = config().limits.maxOpVolume;
        // Whether admitting this op relies on limit.bypass (then the job needs it for as long as it runs).
        boolean bypassed = false;
        boolean eroded = (s instanceof OpSpec.Hollow || s instanceof OpSpec.Walls) && !(region instanceof Region.Cuboid);
        boolean layered = s instanceof OpSpec.Overlay || s instanceof OpSpec.Naturalize || s instanceof OpSpec.UpdateBlocks;
        if (s instanceof OpSpec.Fill || s instanceof OpSpec.Replace || s instanceof OpSpec.Erase || eroded || layered) {
            // Counted before compiling (and only up to the limit): the region's cells inside the build height, times
            // the symmetric copies. A box's Hollow and Walls are checked on the cells they write, once compiled
            // (cheaply, from the box).
            long cells = Regions.cellsBetween(region, runtime.bottomY(world), runtime.topY(world) - 1, limit);
            long total = cells > Long.MAX_VALUE / copyCount ? Long.MAX_VALUE : cells * copyCount;
            // Overlay's layer may reach above the region: its target volume counts it (every copy included).
            if (s instanceof OpSpec.Overlay) total = Math.max(total, OpCompiler.targetVolume(s, null, limit));
            bypassed = checkVolume(total, limit, bypass);
        }
        RunOptions options = o;
        SourceBlocks pasteSource = null;
        MultiPaste scatter = null;
        // A scatter commit's placements the global mask denies a cell of: left out whole, counted as skipped.
        long maskedPlacements = 0;
        // The tool's name for the job and its history entry ("Road", "Flood"…), when the op alone would not say.
        if (!o.label().fits(s)) {
            throw new EditRejected(RejectReason.INVALID, "the label " + o.label() + " does not fit a " + s.getClass().getSimpleName());
        }
        String label = o.label().text();
        // Whether the source holds untrusted tiles on operator-NBT states: what the operator-NBT right is kept for.
        boolean untrustedOperatorTiles = false;
        // Paste, move, stack and scatter commits: the written volume is checked before compiling, and is the
        // compiler's budget.
        boolean copying = true;
        boolean trustSource = true;
        EntityWork entityWork = extraEntities;
        int maxEntities = config().entities.maxPerJob;
        switch (s) {
            case OpSpec.Paste paste -> {
                if (paste.o().physics() && !o.physics()) options = new RunOptions(true, o.conflictPolicy(), o.label());
                Clipboard clipboard = sourceClipboard(p, paste.src()).orElseThrow(() ->
                        paste.src() instanceof SourceRef.Asset
                                ? new EditRejected(RejectReason.ASSET_NOT_LOADED,
                                        "asset not loaded on the server (request its preview first)")
                                : new EditRejected(RejectReason.INVALID, "unknown clipboard"));
                // Checked on the cells the paste can write (the clipboard's present cells, which it knows; a sparse
                // clipboard writes far fewer than its box), times the symmetric copies, before they are copied.
                long pasteCells = clipboard.cellCount();
                bypassed |= checkVolume(pasteCells > Long.MAX_VALUE / copyCount ? Long.MAX_VALUE : pasteCells * copyCount,
                        limit, bypass);
                if (copyCount > 1) {
                    for (OpSymmetry.Copy copy : copies) {
                        OpSpec.Paste pasteCopy = (OpSpec.Paste) copy.op();
                        try {
                            requireInsideWorld(PasteGeometry.pasteTarget(clipboard.size(), clipboard.anchor(),
                                    pasteCopy.t(), pasteCopy.origin()), "a symmetric copy");
                        } catch (IllegalArgumentException e) {
                            throw new EditRejected(RejectReason.INVALID, e.getMessage());
                        }
                    }
                }
                untrustedOperatorTiles = hasUntrustedOperatorTiles(clipboard);
                pasteSource = clipboard.toSource();
                if (paste.o().entities() && clipboard.entityCount() > 0) {
                    bypassed |= checkEntities(clipboard.entityTotal(), maxEntities, bypass);
                    untrustedOperatorTiles |= hasUntrustedOperatorEntities(clipboard,
                            runtime.entityRules(world)::operator);
                    Box target;
                    try {
                        target = PasteGeometry.pasteTarget(clipboard.size(), clipboard.anchor(), paste.t(), paste.origin());
                    } catch (IllegalArgumentException e) {
                        throw new EditRejected(RejectReason.INVALID, e.getMessage());
                    }
                    entityWork = EntityJobs.paste(clipboard.entities(), clipboard.size(), paste.t(), target.min());
                }
            }
            case OpSpec.Move move -> {
                bypassed |= checkVolume(Math.max(OpCompiler.targetVolume(move, null, limit),
                        OpCompiler.sourceVolume(move, limit)), limit, bypass);
                Box from = inBuildHeight(move.box(), world);
                Box to = moveDestination(move, from, world);
                // A move vacates its source, so it must be able to write all of it and all of its destination:
                // skipping protected destination cells would destroy what they should have received. A region other
                // than a box needs only the columns its cells are in and land in. Every symmetric copy alike.
                for (OpSymmetry.Copy copy : copies) {
                    OpSpec.Move moveCopy = (OpSpec.Move) copy.op();
                    Box copyFrom = inBuildHeight(moveCopy.box(), world);
                    Box copyTo = moveDestination(moveCopy, copyFrom, world);
                    if (copyCount > 1) requireInsideWorld(copyTo, "a symmetric copy's destination");
                    if (moveCopy.region() instanceof Region.Cuboid) {
                        requireWritable(p, world, copyFrom, "source");
                        requireWritable(p, world, copyTo, "destination");
                    } else {
                        Long2ObjectOpenHashMap<long[]> columns = regionColumns(moveCopy.region(), copyFrom.min().y(),
                                copyFrom.max().y());
                        requireWritable(p, world, columns, copyFrom, "source");
                        requireWritable(p, world, Regions.movedColumns(columns, copyFrom, copyTo.min(), moveCopy.t()),
                                copyTo, "destination");
                    }
                }
                if (move.entities() != EntityFilter.NONE) {
                    bypassed |= checkEntities(takenEntities(world, move.region(), move.entities()), maxEntities, bypass);
                    entityWork = EntityJobs.move(move.region(), move.entities(), from, move.t(), to.min(),
                            bypass ? Long.MAX_VALUE : maxEntities);
                }
            }
            case OpSpec.Stack stack -> {
                if (stack.count() > MAX_STACK_COUNT) {
                    throw new EditRejected(RejectReason.TOO_LARGE, "count " + stack.count() + " > " + MAX_STACK_COUNT);
                }
                bypassed |= checkVolume(Math.max(OpCompiler.targetVolume(stack, null, limit),
                        OpCompiler.sourceVolume(stack, limit)), limit, bypass);
                Box from = inBuildHeight(stack.box(), world);
                // Every symmetric copy's source follows the source-read rule.
                for (OpSymmetry.Copy copy : copies) {
                    trustSource &= requireReadable(p, world, ((OpSpec.Stack) copy.op()).region());
                }
                bypassed |= !trustSource; // a protected source is read under limit.bypass
                // Its tiles are captured when the job runs: untrusted (as copies) only from a protected source.
                untrustedOperatorTiles = !trustSource;
                if (stack.entities() != EntityFilter.NONE) {
                    long stackedEntities = takenEntities(world, stack.region(), stack.entities()) * stack.count();
                    bypassed |= checkEntities(stackedEntities, maxEntities, bypass);
                    entityWork = EntityJobs.stack(stack.region(), stack.entities(), from, stack.dx(), stack.dy(),
                            stack.dz(), stack.count(), stack.upsideDown(), trustSource,
                            bypass ? Long.MAX_VALUE : Math.max(1, maxEntities / stack.count()));
                }
            }
            case OpSpec.ScatterCommit commit -> {
                ScatterPlans.Held held = scatterPlans
                        .find(runtime.id(p), commit.planId(), runtime.worldId(world), clock.getAsLong())
                        .orElseThrow(() -> new EditRejected(RejectReason.INVALID,
                                "unknown or expired scatter plan (preview again)"));
                bypassed |= checkVolume(held.plan().totalCells(), limit, bypass);
                if (held.blockVariants() && !ServerScatter.mayScatterBlocks(permissions, p)) {
                    throw new EditRejected(RejectReason.NO_PERMISSION, ServerScatter.BLOCK_VARIANTS_NEED);
                }
                // The library may have changed since the preview: the player must still be allowed to read the assets.
                Library.Viewer viewer = libraryViewer(p);
                for (LibraryPath path : held.libraryPaths()) {
                    if (path != null && !mayRead(path, viewer)) {
                        throw new EditRejected(RejectReason.NO_PERMISSION, "you may no longer read " + path);
                    }
                }
                for (Clipboard source : held.plan().sources()) untrustedOperatorTiles |= hasUntrustedOperatorTiles(source);
                // The mask judges each placement (each tree's cluster) whole, as the preview did, against the world
                // as the commit is admitted: one denied cell leaves the whole placement out, never a part of a tree. With the mask and the world unchanged it leaves out nothing.
                BitSet denied = new BitSet();
                if (!editMask.acceptsAll()) {
                    LiveReader live = runtime.reader(world);
                    denied = held.plan().denied((x, y, z) -> !live.isLoaded(x >> 4, z >> 4)
                            || editMask.test(x, y, z, live.get(x, y, z), live), runtime.states());
                    maskedPlacements = denied.cardinality();
                    // Nothing left to place: a readable refusal (the plan stays held), not an empty paste.
                    if (maskedPlacements > 0 && maskedPlacements == held.plan().placements().size()) {
                        throw new EditRejected(RejectReason.INVALID, MASK_RULES_OUT_SCATTER);
                    }
                }
                // Deep-copies the sources: done once, for this commit.
                scatter = held.plan().toMultiPaste(denied);
                label = "Scatter · " + count(held.plan().placements().size(), "placement");
            }
            default -> copying = false;
        }
        // Tinker's "Apply to all like it in the selection" is a Fill with the property pattern: named as the change.
        if (s instanceof OpSpec.Fill fill && fill.pattern() instanceof Pattern.SetProperty set
                && set.validIn(runtime.states())) {
            label = "Tinker · " + dev.sculptory.core.tinker.TinkerProperties.shown(set.property(),
                    set.value(runtime.states()));
        }
        // Copying ops also give the compiler their budget, so nothing large is built before a refusal.
        // A Move is masked where its blocks are lifted (compiled in), every other op where its cells land.
        boolean sourceMasked = MaskedProgram.sideOf(s) == MaskSide.SOURCE;
        compileSourceMask = sourceMasked ? editMask : BoundMask.ALL;
        EditProgram compiled;
        try {
            compiled = compile(s, copies, world, pasteSource, scatter, copying && !bypass ? limit : Long.MAX_VALUE,
                    bypass);
        } finally {
            compileSourceMask = BoundMask.ALL;
        }
        // A scatter commit was masked whole placement by whole placement above.
        if (!sourceMasked && scatter == null) compiled = MaskedProgram.wrap(compiled, editMask);
        if (!copying) bypassed |= checkVolume(compiled.estimatedCells(), limit, bypass);
        // A scatter commit reports the placements it skipped (cells no longer open) as conflicts.
        ConflictCountingProgram skipped = scatter != null ? new ConflictCountingProgram(compiled, maskedPlacements) : null;
        EditProgram program = skipped != null ? skipped : compiled;
        RecordBuilder record = new RecordBuilder();
        HistoryService.Session session = history.session(runtime.id(p));
        EnumSet<Perm> required = EnumSet.of(Perm.USE, opNode);
        if (options.physics()) required.add(Perm.PHYSICS);
        if (bypassed) required.add(Perm.LIMIT_BYPASS);
        TrackedJob job = new TrackedJob(runtime.id(p), runtime.name(p), label != null ? label : program.label(),
                l, skipped, record, runtime.worldId(world), session, null, required);
        // Saved section by section as the job writes, once it is admitted (history.editStarted below).
        job.openRecord = history.record(session, job.worldId, job.label, this::createdMillis, record);
        // Fluid the job writes is marked as the entry's: what it does later is taken back with the entry's undo.
        JobRequest<W> request = JobRequest.forPlayer(runtime, p, program, options, job,
                trails.marking(history.sink(record, job.openRecord), world, record.id()))
                .withEntities(entityWork);
        if (!trustSource) {
            // Tiles captured from a source the player could not write are not theirs to vouch for.
            WriteOptions w = request.writeOptions();
            request = request.withWriteOptions(new WriteOptions(w.physics(), w.allowOperatorNbt(), false));
        }
        // A job keeping operator-only NBT of untrusted tiles may do so only while the player may. (Fills write no
        // tiles; a move's tiles are world-captured from columns it must be able to write, so they are trusted.)
        job.operatorNbt = untrustedOperatorTiles && request.writeOptions().allowOperatorNbt();
        JobTicket ticket = executor.submit(request);
        history.editStarted(session, job.openRecord);
        // Its fluid stays put until the job has written every cell, so none flows into a cell it has yet to write.
        job.freeze(List.of(record.id()));
        job.admitted(ticket);
        // A committed plan is spent: the job holds what it writes.
        if (s instanceof OpSpec.ScatterCommit commit) scatterPlans.consume(runtime.id(p), commit.planId());
        return ticket;
    }

    /**
     * Whether the clipboard holds a tile whose operator-only NBT a player without the right would lose: one on an
     * {@link StateFlags#OPERATOR_NBT} state that is neither sanitized text nor server-captured (the tiles
     * {@code BlockWriter.shouldStrip} strips with captured tiles trusted). Takes time proportional to the tiles.
     */
    private boolean hasUntrustedOperatorTiles(Clipboard clipboard) {
        StateSpace states = runtime.states();
        return clipboard.anyTile((state, tile) -> state >= 0 && (states.flags(state) & StateFlags.OPERATOR_NBT) != 0
                && !(tile instanceof SanitizedTile) && !runtime.serverCaptured(tile));
    }

    /**
     * Whether the clipboard holds an entity whose operator-only data a player without the right would lose: untrusted
     * data of which it or any of its passengers is of an operator-only type
     * ({@link dev.sculptory.server.platform.EntityRules#operator}).
     */
    public static boolean hasUntrustedOperatorEntities(Clipboard clipboard, Predicate<String> operatorType) {
        // Found when the clipboard was made (off the server thread): nothing is decoded here.
        for (String type : clipboard.untrustedEntityTypes()) {
            if (operatorType.test(type)) return true;
        }
        return false;
    }

    /**
     * The entities (passengers included) a move or stack of {@code region} takes along now ({@code UNLOADED} when the
     * entities of a chunk they could be in are not loaded: an edit must not miss entities it cannot see).
     */
    private long takenEntities(W world, Region region, EntityFilter filter) throws EditRejected {
        return takenEntities(runtime.entities(world), region, filter);
    }

    private static <E> long takenEntities(WorldEntities<E> entities, Region region, EntityFilter filter)
            throws EditRejected {
        // Only the chunks the region's cells are in (and neighbours near a cell): a sparse selection spanning unloaded
        // chunks inside its bounds is not refused for them.
        Box bounds = region.bounds();
        long[] columns = EntityColumns.of(region, bounds.min().y(), bounds.max().y());
        String unloaded = entities.firstUnloaded(columns);
        if (unloaded != null) {
            throw new EditRejected(RejectReason.UNLOADED, "the entities of chunk " + unloaded + " are not loaded "
                    + "(or set Entities to None)");
        }
        long total = 0;
        for (E entity : entities.inRegion(region, filter, columns)) {
            total += entities.takenCount(entity, filter);
        }
        return total;
    }

    /**
     * Refuses more than {@code max} entities unless the player may bypass limits.
     *
     * @return whether the count is over the limit (admitted only through {@code limit.bypass})
     */
    static boolean checkEntities(long count, long max, boolean bypass) throws EditRejected {
        if (count <= max) return false;
        if (!bypass) throw new EditRejected(RejectReason.TOO_LARGE, count + " entities > " + max);
        return true;
    }

    /**
     * Refuses {@code volume} over {@code limit} unless the player may bypass it.
     *
     * @return whether the volume is over the limit (admitted only through {@code limit.bypass})
     */
    private static boolean checkVolume(long volume, long limit, boolean bypass) throws EditRejected {
        if (volume <= limit) return false;
        if (!bypass) throw new EditRejected(RejectReason.TOO_LARGE, volume + " > " + limit + " blocks");
        return true;
    }

    /**
     * Where a move puts its (build-height-cut) source {@code from}: the transformed box with its minimum corner at
     * {@code from.min() + offset}, or, flipped upside down, at the offset from {@code from}'s image within the
     * selection's whole bounds (the pivot the client shows; {@code MoveProgram} writes the same box, and the entities
     * follow it). {@code INVALID} when it leaves the coordinate range or the build height (the cells that would leave
     * it would be lost).
     */
    Box moveDestination(OpSpec.Move move, Box from, W world) throws EditRejected {
        BlockPos size = move.t().size(from.sizeX(), from.sizeY(), from.sizeZ());
        Box image = from;
        if (move.t().upsideDown()) {
            try {
                image = from.flippedWithin(move.box());
            } catch (IllegalArgumentException e) {
                throw new EditRejected(RejectReason.INVALID, "the moved box would leave the world");
            }
        }
        long x0 = (long) image.min().x() + move.offset().x();
        long y0 = (long) image.min().y() + move.offset().y();
        long z0 = (long) image.min().z() + move.offset().z();
        long x1 = x0 + size.x() - 1, y1 = y0 + size.y() - 1, z1 = z0 + size.z() - 1;
        if (x0 < Integer.MIN_VALUE || z0 < Integer.MIN_VALUE || x1 > Integer.MAX_VALUE || z1 > Integer.MAX_VALUE
                || y0 < runtime.bottomY(world) || y1 >= runtime.topY(world)) {
            throw new EditRejected(RejectReason.INVALID, "the moved box would leave the world");
        }
        return new Box(new BlockPos((int) x0, (int) y0, (int) z0), new BlockPos((int) x1, (int) y1, (int) z1));
    }

    // ================================================================== sources (M2)

    /** The players' clipboards (the {@code SourceRef.Clipboard} sources). */
    public PlayerClipboards clipboards() {
        return clipboards;
    }

    /** Library assets loaded for previews and pastes (the {@code SourceRef.Asset} sources). */
    public AssetCache assets() {
        return assets;
    }

    /** The players' scatter plans (M3), made by {@link ServerScatter} and committed by {@link #run}. */
    public ScatterPlans scatterPlans() {
        return scatterPlans;
    }

    /** The service's monotonic clock ({@code System.nanoTime} scale), also used for plan lifetimes. */
    public long now() {
        return clock.getAsLong();
    }

    /** Who the player is to the asset library. */
    public Library.Viewer libraryViewer(P p) {
        return new Library.Viewer(runtime.id(p), permissions.has(p, Perm.LIBRARY_WRITE),
                permissions.has(p, Perm.ADMIN));
    }

    /**
     * The library whose per-asset access the cached assets are checked against ({@code ServerClipboards} attaches
     * it); without one, only the area rule applies.
     */
    public void attachLibrary(Library library) {
        this.library = library;
    }

    /**
     * Whether the viewer may read the library file {@code path} (per-asset access, as the library last read its
     * folder's access file; no file I/O, server thread): the check for every cached asset used by hash.
     */
    public boolean mayRead(LibraryPath path, Library.Viewer viewer) {
        Library current = library;
        return current == null ? Library.mayReadArea(path, viewer) : current.mayRead(path, viewer);
    }

    /** A paste source for the player: their own clipboard by id, or a cached library asset they may read. */
    public Optional<Clipboard> sourceClipboard(P p, SourceRef ref) {
        checkThread();
        return switch (ref) {
            case SourceRef.Clipboard c -> clipboards.find(runtime.id(p), c.id()).map(PlayerClipboards.Held::clipboard);
            case SourceRef.Asset a -> assets.get(a.contentHash())
                    .filter(asset -> mayRead(asset.path(), libraryViewer(p)))
                    .map(AssetCache.Asset::clipboard);
        };
    }

    /**
     * <b>Source-read protection</b> for a copy or a stack source. Every column of {@code box} must be one the player
     * may modify ({@code PermissionService.chunk} gives {@code ALLOW} for it: no spawn protection, claim or world
     * border in the way), so nobody copies block-entity contents (chest items, signs, command blocks) out of an area
     * they could not edit. Otherwise the read is refused ({@code PROTECTED}), unless the player has
     * {@code sculptory.limit.bypass}; even then the result says so, and tiles captured from it must be treated as
     * untrusted. A box touching more than {@code maxColumnsPerJob} chunk columns is {@code TOO_LARGE}.
     *
     * @return true when the player may write every column of the box (its captured tiles may stay trusted)
     */
    public boolean requireReadable(P p, W world, Box box) throws EditRejected {
        String denied = firstDenied(p, world, box);
        if (denied == null) return true;
        if (!permissions.has(p, Perm.LIMIT_BYPASS)) {
            throw new EditRejected(RejectReason.PROTECTED, "source chunk " + denied + " is protected");
        }
        return false;
    }

    /**
     * {@link #requireReadable(Object, Object, Box)} for a region's cells inside the build height: a
     * box by all its columns, any other region by the columns holding its cells ({@link Regions#columns}), so a
     * selection whose bounds cross a protected area its cells stay out of may be read.
     */
    public boolean requireReadable(P p, W world, Region region) throws EditRejected {
        Box area = inBuildHeight(region.bounds(), world);
        if (region instanceof Region.Cuboid) return requireReadable(p, world, area);
        return requireReadable(p, world, regionColumns(region, area.min().y(), area.max().y()), area);
    }

    /**
     * {@link Regions#columns} of a region within heights, refused {@code TOO_LARGE} as soon as it reaches more chunk
     * columns than a job may ({@code executor.maxColumnsPerJob}).
     */
    public Long2ObjectOpenHashMap<long[]> regionColumns(Region region, int minY, int maxY) throws EditRejected {
        try {
            return Regions.columns(region, minY, maxY, executor.settings().maxColumnsPerJob());
        } catch (RegionTooLargeException e) {
            throw new EditRejected(RejectReason.TOO_LARGE, e.getMessage());
        }
    }

    /**
     * {@link Regions#sectionKeysBetween} of a region within heights, refused {@code TOO_LARGE} as soon as it reaches more
     * sections than a job's columns may hold ({@link #maxSections}).
     */
    public long[] regionSections(Region region, int minY, int maxY) throws EditRejected {
        try {
            return Regions.sectionKeysBetween(region, minY, maxY, maxSections(minY, maxY));
        } catch (RegionTooLargeException e) {
            throw new EditRejected(RejectReason.TOO_LARGE, e.getMessage());
        }
    }

    /** The most sections a job may list between two heights: its column cap times the sections per column. */
    long maxSections(int minY, int maxY) {
        long perColumn = (long) (maxY >> 4) - (minY >> 4) + 1;
        return Math.max(1, perColumn) * executor.settings().maxColumnsPerJob();
    }

    /**
     * {@link #requireReadable(Object, Object, Box)} for the chunk columns {@code columns}
     * ({@link Regions#columns}) of cells within {@code bounds}: only those columns must be writable.
     */
    public boolean requireReadable(P p, W world, Long2ObjectMap<long[]> columns, Box bounds)
            throws EditRejected {
        String denied = firstDenied(p, world, columns, bounds);
        if (denied == null) return true;
        if (!permissions.has(p, Perm.LIMIT_BYPASS)) {
            throw new EditRejected(RejectReason.PROTECTED, "source chunk " + denied + " is protected");
        }
        return false;
    }

    /**
     * Requires every column of {@code box} to be writable by the player, whatever their bypass (a move vacates its
     * source and must fill its whole destination).
     */
    public void requireWritable(P p, W world, Box box, String what) throws EditRejected {
        String denied = firstDenied(p, world, box);
        if (denied != null) throw new EditRejected(RejectReason.PROTECTED, what + " chunk " + denied + " is protected");
    }

    /** {@link #requireWritable(Object, Object, Box, String)} for the chunk columns {@code columns}. */
    public void requireWritable(P p, W world, Long2ObjectMap<long[]> columns, Box bounds,
                                String what) throws EditRejected {
        String denied = firstDenied(p, world, columns, bounds);
        if (denied != null) throw new EditRejected(RejectReason.PROTECTED, what + " chunk " + denied + " is protected");
    }

    /**
     * Refuses a region the engine cannot take as it is: an {@code Uploaded} set nobody resolved to its cells
     * ({@code SELECTION_NOT_LOADED}; the network layer resolves uploads it holds), or a shape in a box of more than
     * 2^29 cells spanning more rows than {@link OpCompiler#MAX_BIG_SHAPE_ROWS} ({@link OpCompiler#MAX_BIG_SHAPE_ROWS_BYPASS}
     * with {@code limit.bypass}) ({@code TOO_LARGE}), checked before anything counts its cells.
     */
    public static void admitRegion(Region region, boolean bypass) throws EditRejected {
        if (region instanceof Region.Uploaded) {
            throw new EditRejected(RejectReason.SELECTION_NOT_LOADED, "the selection was not uploaded");
        }
        try {
            OpCompiler.checkShape(region, bypass ? OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS : OpCompiler.MAX_BIG_SHAPE_ROWS);
        } catch (EditTooLargeException e) {
            throw new EditRejected(RejectReason.TOO_LARGE, e.getMessage());
        }
    }

    /**
     * The first chunk ("cx,cz") of {@code columns} holding a column the player may not modify, or {@code null}. Each
     * chunk's permit is asked for the smallest rectangle holding its columns (y from {@code bounds}); a permit mixed
     * over that rectangle is then checked column by column, so an L-shaped set of columns is judged by its own columns
     * whenever the permit service sees the mix (the four-corner sampling of {@code ChunkPermits} may not).
     */
    private String firstDenied(P p, W world, Long2ObjectMap<long[]> columns, Box bounds)
            throws EditRejected {
        if (columns.size() > executor.settings().maxColumnsPerJob()) {
            throw new EditRejected(RejectReason.TOO_LARGE,
                    columns.size() + " chunk columns > " + executor.settings().maxColumnsPerJob());
        }
        long[] chunks = columns.keySet().toLongArray();
        Arrays.sort(chunks);
        for (long chunk : chunks) {
            int cx = Regions.columnX(chunk), cz = Regions.columnZ(chunk);
            long[] held = columns.get(chunk);
            int minX = 15, maxX = 0, minZ = 15, maxZ = 0;
            for (int bit = 0; bit < 256; bit++) {
                if ((held[bit >>> 6] & (1L << bit)) == 0) continue;
                minX = Math.min(minX, bit & 15);
                maxX = Math.max(maxX, bit & 15);
                minZ = Math.min(minZ, bit >>> 4);
                maxZ = Math.max(maxZ, bit >>> 4);
            }
            if (minX > maxX) continue;
            Box rectangle = new Box(new BlockPos((cx << 4) + minX, bounds.min().y(), (cz << 4) + minZ),
                    new BlockPos((cx << 4) + maxX, bounds.max().y(), (cz << 4) + maxZ));
            if (!allowsColumns(permissions.chunk(p, world, cx, cz, rectangle), held)) return cx + "," + cz;
        }
        return null;
    }

    /** Whether the permit allows every column set in {@code held} (bit {@code ((z & 15) << 4) | (x & 15)}). */
    static boolean allowsColumns(ChunkPermit permit, long[] held) {
        if (permit == null) return false;
        return switch (permit) {
            case ChunkPermit.Allow allow -> true;
            case ChunkPermit.Deny deny -> false;
            case ChunkPermit.Columns columns -> {
                long[] allowed = columns.allowed();
                for (int word = 0; word < 4; word++) {
                    if ((held[word] & ~allowed[word]) != 0) yield false;
                }
                yield true;
            }
        };
    }

    /** The first chunk ("cx,cz") holding a column of {@code box} the player may not modify, or {@code null}. */
    private String firstDenied(P p, W world, Box box) throws EditRejected {
        int minCx = box.min().x() >> 4, maxCx = box.max().x() >> 4;
        int minCz = box.min().z() >> 4, maxCz = box.max().z() >> 4;
        long columns = ((long) maxCx - minCx + 1) * ((long) maxCz - minCz + 1);
        if (columns > executor.settings().maxColumnsPerJob()) {
            throw new EditRejected(RejectReason.TOO_LARGE, columns + " chunk columns > " + executor.settings().maxColumnsPerJob());
        }
        for (int cx = minCx; cx <= maxCx; cx++) {
            for (int cz = minCz; cz <= maxCz; cz++) {
                if (!allowsWholeRectangle(permissions.chunk(p, world, cx, cz, box), cx, cz, box)) return cx + "," + cz;
            }
        }
        return null;
    }

    /** Whether the permit allows every column of {@code box} inside chunk (cx, cz). */
    static boolean allowsWholeRectangle(ChunkPermit permit, int cx, int cz, Box box) {
        if (permit == null) return false;
        return switch (permit) {
            case ChunkPermit.Allow allow -> true;
            case ChunkPermit.Deny deny -> false;
            case ChunkPermit.Columns columns -> {
                int x0 = Math.max(box.min().x(), cx << 4), x1 = Math.min(box.max().x(), (cx << 4) + 15);
                int z0 = Math.max(box.min().z(), cz << 4), z1 = Math.min(box.max().z(), (cz << 4) + 15);
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        if (!columns.allows(x, z)) yield false;
                    }
                }
                yield true;
            }
        };
    }

    /** {@code box} cut to the world's build height ({@code INVALID} when nothing is left). */
    Box inBuildHeight(Box box, W world) throws EditRejected {
        int bottom = runtime.bottomY(world), top = runtime.topY(world) - 1;
        if (box.max().y() < bottom || box.min().y() > top) {
            throw new EditRejected(RejectReason.INVALID, "outside the build height");
        }
        return new Box(new BlockPos(box.min().x(), Math.max(bottom, box.min().y()), box.min().z()),
                new BlockPos(box.max().x(), Math.min(top, box.max().y()), box.max().z()));
    }

    // ================================================================== undo and redo

    @Override
    public JobTicket undo(P p, ConflictPolicy c) throws EditRejected {
        return undo(p, c, null);
    }

    @Override
    public JobTicket redo(P p, ConflictPolicy c) throws EditRejected {
        return redo(p, c, null);
    }

    /**
     * {@link #undo(Object, ConflictPolicy)} reporting only to {@code listener} (commands): the
     * provider's (network) listener is not told about a job its client never requested.
     */
    public JobTicket undo(P p, ConflictPolicy c, JobListener listener) throws EditRejected {
        return historyJob(p, c, HistoryService.Op.UNDO, listener);
    }

    /** {@link #redo(Object, ConflictPolicy)} reporting only to {@code listener} (commands). */
    public JobTicket redo(P p, ConflictPolicy c, JobListener listener) throws EditRejected {
        return historyJob(p, c, HistoryService.Op.REDO, listener);
    }

    private JobTicket historyJob(P p, ConflictPolicy policy, HistoryService.Op op,
                                 JobListener listener) throws EditRejected {
        checkThread();
        Objects.requireNonNull(policy);
        requireEditing();
        require(p, Perm.USE);
        UUID owner = runtime.id(p);
        commitStroke(owner);
        HistoryService.Session session = history.session(owner);
        if (session.loading()) throw new EditRejected(RejectReason.QUEUE_FULL, "your history is still loading");
        if (session.busy()) throw new EditRejected(RejectReason.QUEUE_FULL, "an undo or redo is still running");
        if (session.editsRunning() > 0) throw new EditRejected(RejectReason.QUEUE_FULL, "a job is still running");
        HistoryEntry entry = history.candidate(session, op)
                .orElseThrow(() -> new EditRejected(RejectReason.HISTORY_EMPTY));
        W world = worldOf(entry.world());
        if (world == null) throw new EditRejected(RejectReason.INVALID, "world " + entry.world() + " is not loaded");
        // What the entry's fluid did since its last step (flowed out, turned grass under it to dirt) is taken back too:
        // the step runs over the entry with that trail folded in, which replaces the entry once the step is admitted.
        EditRecord trail = trails.take(world, entry.id());
        HistoryEntry step = HistoryService.withTrail(entry, trail, op == HistoryService.Op.UNDO);
        HistoryJob job;
        try {
            job = historyStep(p, session, world, step, op, policy, listener);
        } catch (EditRejected e) {
            trails.giveBack(world, entry.id(), trail);
            // A trail reaching where this step may not go (unloaded chunks, too many columns) must not make the entry
            // impossible to undo: the step runs over the entry as it was.
            boolean trailToBlame = step != entry
                    && (e.reason() == RejectReason.UNLOADED || e.reason() == RejectReason.TOO_LARGE);
            if (!trailToBlame) throw e;
            step = entry;
            job = historyStep(p, session, world, step, op, policy, listener);
        }
        history.replace(session, step);
        history.begin(session, op, step);
        // The entry's fluid stays put while the step runs, so nothing flows past what it restores.
        job.tracked.freeze(List.of(step.id()));
        job.tracked.admitted(job.ticket);
        return job.ticket;
    }

    /** An admitted undo, redo or overwrite job and its tracker. */
    private final class HistoryJob {
        final TrackedJob tracked;
        final JobTicket ticket;

        HistoryJob(TrackedJob tracked, JobTicket ticket) {
            this.tracked = tracked;
            this.ticket = ticket;
        }
    }

    /** Builds and submits the undo or redo job of {@code entry} (throws what the executor refuses). */
    private HistoryJob historyStep(P p, HistoryService.Session session, W world,
                                   HistoryEntry entry, HistoryService.Op op, ConflictPolicy policy, JobListener listener)
            throws EditRejected {
        // Contents are compared as the game holds them (a chest filled since the step is kept like a changed block).
        TileMatcher tiles = runtime.tileMatcher(world);
        EditProgram base = op == HistoryService.Op.UNDO ? HistoryPrograms.undo(entry, policy, tiles)
                : HistoryPrograms.redo(entry, policy, tiles);
        ConflictCountingProgram program = new ConflictCountingProgram(base);
        JobListener downstream = listener != null ? listener : listeners.apply(p);
        TrackedJob job = new TrackedJob(runtime.id(p), runtime.name(p), program.label(), downstream, program,
                null, entry.world(), session, op, EnumSet.of(Perm.USE));
        // Fluid the step puts where there was none (a drain's water an undo puts back) is followed as the entry's.
        UUID id = entry.id();
        JobRequest<W> request = JobRequest.forPlayer(runtime, p, program, new RunOptions(false, policy), job,
                trails.stepMarking(RecordSink.NONE, world, (x, y, z) -> id));
        if (request.world() != world) {
            Box bounds = program.bounds();
            request = new JobRequest<>(request.owner(), world, program, request.writeOptions(),
                    PermitSource.forPlayer(permissions, p, world, bounds), request.mayLoadChunks(), request.seed(),
                    request.listener(), request.records());
        }
        // Entities follow the cells' rule: one changed since the step is kept and counted with the kept blocks.
        List<EntityHistory.Step> steps = EntityHistory.steps(entry, op == HistoryService.Op.REDO);
        if (!steps.isEmpty()) {
            request = request.withEntities(EntityJobs.history(steps, policy, EntityMatcher.ignoringVolatile()));
        }
        return new HistoryJob(job, executor.submit(request));
    }

    @Override
    public JobTicket historyOverwrite(P p, boolean redo, int steps) throws EditRejected {
        return historyOverwrite(p, redo, steps, null);
    }

    /**
     * Undo anyway (or Redo anyway): re-applies with {@code OVERWRITE} the player's current run of {@code steps} undo
     * (or redo) steps ({@link HistoryService.Run}, {@link HistoryPrograms#reapply}), as one job in the world the steps
     * were made in. It needs only {@code use}, like undo and redo, and it is a history operation: refused with
     * {@code QUEUE_FULL} while an undo, redo or overwrite of the player is in flight or one of their jobs runs, and
     * pushes wait for it. It moves no entry and pushes nothing. Refused with {@code INVALID} when the run is not
     * {@code steps} steps that way (the history changed since), skipped nothing, is no longer next to the position, or
     * spans worlds ({@link HistoryService#overwriteRefusal}). Chunk permits, the world border, the executor's budgets
     * and cancelling apply as to any job; its events go to the listener like an undo's.
     */
    public JobTicket historyOverwrite(P p, boolean redo, int steps, JobListener listener)
            throws EditRejected {
        checkThread();
        requireEditing();
        require(p, Perm.USE);
        UUID owner = runtime.id(p);
        commitStroke(owner);
        HistoryService.Session session = history.session(owner);
        if (session.loading()) throw new EditRejected(RejectReason.QUEUE_FULL, "your history is still loading");
        if (session.busy()) throw new EditRejected(RejectReason.QUEUE_FULL, "an undo or redo is still running");
        if (session.editsRunning() > 0) throw new EditRejected(RejectReason.QUEUE_FULL, "a job is still running");
        HistoryService.Op op = redo ? HistoryService.Op.REDO : HistoryService.Op.UNDO;
        String refusal = history.overwriteRefusal(session, op, steps);
        if (refusal != null) throw new EditRejected(RejectReason.INVALID, refusal, EditRejected.HISTORY_RUN);
        HistoryService.Run run = session.run().orElseThrow();
        String worldId = run.entries().get(0).world();
        W world = worldOf(worldId);
        if (world == null) throw new EditRejected(RejectReason.INVALID, "world " + worldId + " is not loaded");
        // What the entries' fluid did since goes into them as it would for their next step:
        // an Undo anyway's entries are undone, so toward after (the redo takes it back); a Redo anyway's are done, so
        // toward before (the undo takes it back). The overwrite then leaves those cells as they are (their target is
        // what they hold), and a fold made at a chunk save in the meantime means the same.
        List<EditRecord> taken = new ArrayList<>();
        List<HistoryEntry> folded = new ArrayList<>();
        for (HistoryEntry entry : run.entries()) {
            EditRecord trail = trails.take(world, entry.id());
            taken.add(trail);
            folded.add(HistoryService.withTrail(entry, trail, redo));
        }
        HistoryJob job;
        try {
            job = overwriteJob(p, session, world, worldId, folded, op, redo, listener);
        } catch (EditRejected e) {
            for (int i = 0; i < folded.size(); i++) trails.giveBack(world, folded.get(i).id(), taken.get(i));
            boolean trailToBlame = e.reason() == RejectReason.UNLOADED || e.reason() == RejectReason.TOO_LARGE;
            if (!trailToBlame || folded.equals(run.entries())) throw e;
            folded = run.entries();
            job = overwriteJob(p, session, world, worldId, folded, op, redo, listener);
        }
        List<UUID> stepIds = new ArrayList<>();
        for (HistoryEntry entry : folded) {
            history.replace(session, entry);
            stepIds.add(entry.id());
        }
        history.beginOverwrite(session);
        job.tracked.freeze(stepIds);
        job.tracked.admitted(job.ticket);
        return job.ticket;
    }

    /** Builds and submits Undo anyway (or Redo anyway) of {@code run} (throws what the executor refuses). */
    private HistoryJob overwriteJob(P p, HistoryService.Session session, W world,
                                    String worldId, List<HistoryEntry> run, HistoryService.Op op, boolean redo,
                                    JobListener listener) throws EditRejected {
        EditProgram program = HistoryPrograms.reapply(run, redo);
        JobListener downstream = listener != null ? listener : listeners.apply(p);
        TrackedJob job = new TrackedJob(runtime.id(p), runtime.name(p), program.label(), downstream, null,
                null, worldId, session, op, EnumSet.of(Perm.USE));
        job.overwrite = true;
        // A cell belongs to the entry whose target it gets: the last of the run that records it (HistoryPrograms).
        RecordSink marks = trails.stepMarking(RecordSink.NONE, world, (x, y, z) -> {
            for (int i = run.size() - 1; i >= 0; i--) {
                if (run.get(i).record().before().has(x, y, z)) return run.get(i).id();
            }
            return null;
        });
        JobRequest<W> request = JobRequest.forPlayer(runtime, p, program,
                new RunOptions(false, ConflictPolicy.OVERWRITE), job, marks);
        if (request.world() != world) {
            Box bounds = program.bounds();
            request = new JobRequest<>(request.owner(), world, program, request.writeOptions(),
                    PermitSource.forPlayer(permissions, p, world, bounds), request.mayLoadChunks(), request.seed(),
                    request.listener(), request.records());
        }
        List<EntityHistory.Step> entitySteps = EntityHistory.reapplySteps(run, redo);
        if (!entitySteps.isEmpty()) {
            request = request.withEntities(EntityJobs.history(entitySteps, ConflictPolicy.OVERWRITE,
                    EntityMatcher.ignoringVolatile()));
        }
        return new HistoryJob(job, executor.submit(request));
    }

    // ================================================================== Tinker

    /** See {@link TinkerService#block} and the platform's Tinker ({@code TinkerEdits} on Fabric). */
    @Override
    public void block(P player, BlockPos pos, int expected, int target,
                      dev.sculptory.core.tinker.SignText sign) throws EditRejected {
        tinker.block(player, pos, expected, target, sign);
    }

    /** See {@link TinkerService#entity} and the platform's Tinker ({@code TinkerEdits} on Fabric). */
    @Override
    public dev.sculptory.core.tinker.EntityView entity(P player, UUID id,
                                                          List<dev.sculptory.core.tinker.EntityEdit> edits)
            throws EditRejected {
        return tinker.entity(player, id, edits);
    }

    @Override
    public boolean cancel(P p, UUID jobId) {
        checkThread();
        return jobId != null && executor.cancel(runtime.id(p), jobId);
    }

    /** Cancels all of the player's jobs; returns how many were cancelled. */
    public int cancelAll(P p) {
        return cancelAll(runtime.id(p));
    }

    /**
     * Cancels all jobs of {@code owner}, online or not (the {@code /sculptory cancel <player>} admin command): waiting jobs end
     * at once, running ones at their next section boundary. Returns how many were cancelled.
     */
    public int cancelAll(UUID owner) {
        checkThread();
        int n = 0;
        for (JobInfo job : jobs(owner)) {
            if (executor.cancel(owner, job.jobId())) n++;
        }
        return n;
    }

    /**
     * The owner of an admitted job whose name (as recorded when the job was admitted, compared without regard to case)
     * is {@code name}: how {@code /sculptory cancel <player>} finds a player who has left.
     */
    public Optional<UUID> jobOwnerNamed(String name) {
        checkThread();
        for (TrackedJob job : jobs.values()) {
            if (job.ownerName != null && job.ownerName.equalsIgnoreCase(name)) return Optional.of(job.owner);
        }
        return Optional.empty();
    }

    /** The player's admitted jobs that have not finished, oldest first. */
    public List<JobInfo> jobs(UUID owner) {
        List<JobInfo> list = new ArrayList<>();
        for (TrackedJob job : jobs.values()) {
            if (job.owner.equals(owner)) list.add(job.info());
        }
        return list;
    }

    // ================================================================== strokes

    @Override
    public void beginStroke(P p, int strokeId, BrushSpec spec) throws EditRejected {
        checkThread();
        Objects.requireNonNull(spec);
        requireEditing();
        require(p, Perm.USE);
        require(p, Perm.BRUSH);
        String over = overBrushLimit(spec, config().limits.maxBrushRadius);
        if (over != null && !permissions.has(p, Perm.LIMIT_BYPASS)) throw new EditRejected(RejectReason.TOO_LARGE, over);
        // The Shape brush: every valid shape fits the step cap; it stands as a backstop, for everyone.
        long shapeCells = spec.shapeSpec() == null ? 0 : ShapeStamp.cellCount(spec);
        if (shapeCells * spec.symmetry().mode().copies() > ShapeStamp.MAX_STEP_CELLS) {
            throw new EditRejected(RejectReason.TOO_LARGE, "a step of " + shapeCells * spec.symmetry().mode().copies()
                    + " cells > " + ShapeStamp.MAX_STEP_CELLS);
        }
        if (spec.material() != null && !validMaterial(spec.material(), runtime.states())) {
            throw new EditRejected(RejectReason.INVALID, "material state outside the state space, or not a fluid source");
        }
        // The brush's own mask must bind here (a rule list naming an uploaded region cannot), and the global mask is
        // read once for the whole stroke.
        try {
            ColumnFilter.bind(spec.mask(), runtime.states());
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            throw new EditRejected(RejectReason.INVALID, "brush mask: " + e.getMessage());
        }
        BoundMask strokeMask = EditMasks.current(runtime.id(p));
        W world = runtime.world(p);
        if (spec.clip() != null && !insideWorld(spec.clip(), world)) {
            throw new EditRejected(RejectReason.INVALID, "clip box outside the world: " + spec.clip());
        }
        if (!insideWorld(spec.symmetry())) {
            throw new EditRejected(RejectReason.INVALID, "symmetry centre outside the world: "
                    + spec.symmetry().centreX() + ", " + spec.symmetry().centreZ());
        }
        BrushLane lane = lane(p);
        if (lane.stroke != null) closeStroke(lane);
        lane.stroke = new StrokeSession(strokeId, spec, world, runtime.worldId(world), history.session(runtime.id(p)),
                runtime.reader(world),
                runtime.writer(world, WriteOptions.DEFAULT).watchVanillaWrites(executor.clientSync(world)),
                clock.getAsLong(), shapeCells, strokeMask);
    }

    @Override
    public void predicted(P p) {
        checkThread();
        executor.predicted(runtime.id(p));
    }

    @Override
    public DabOutcome dabs(P p, int strokeId, int seq, List<Dab> dabs) {
        checkThread();
        // The client predicted these dabs before sending them, accepted or not: bulk writes near this player go out
        // as per-block updates for a while (ClientSync).
        predicted(p);
        BrushLane lane = lanes.get(runtime.id(p));
        StrokeSession stroke = lane == null ? null : lane.stroke;
        int lastIndex = dabs == null || dabs.isEmpty() || dabs.get(dabs.size() - 1) == null
                ? (stroke == null ? -1 : stroke.lastIndex) : dabs.get(dabs.size() - 1).index();
        RejectReason refusal = admission(p, lane, stroke, strokeId, seq, dabs);
        if (refusal != null) return DabOutcome.rejected(lastIndex, refusal);
        lane.player = p;
        stroke.lastIndex = lastIndex;
        stroke.lastActivity = clock.getAsLong();
        DabBatch batch = new DabBatch(lane, stroke, seq, dabs.size(), lastIndex);
        batch.admitting = true;
        for (Dab dab : dabs) {
            List<Dab> copies = stroke.spec.symmetry().copies(dab);
            DabWork work = new DabWork(batch, dab, copies, units(stroke, dab, copies));
            lane.pending.addLast(work);
            executor.submitBrush(work); // may drop it at once (the executor is stopping)
        }
        batch.admitting = false;
        return DabOutcome.accepted(lastIndex);
    }

    @Override
    public void endStroke(P p, int strokeId) {
        checkThread();
        BrushLane lane = lanes.get(runtime.id(p));
        if (lane == null || lane.stroke == null || lane.stroke.strokeId != strokeId) return;
        closeStroke(lane);
    }

    /** Dabs admitted for the player and not yet applied. */
    public int queuedDabs(UUID player) {
        BrushLane lane = lanes.get(player);
        if (lane == null) return 0;
        int dabs = 0;
        for (LaneWork work : lane.pending) {
            if (work instanceof DabWork) dabs++;
        }
        return dabs;
    }

    /**
     * The units of the dabs admitted for the player and not yet applied ({@link #MAX_QUEUED_DABS}): each terrain dab
     * counted with its symmetric copies, each Shape dab in work units of its placements.
     */
    public int queuedUnits(UUID player) {
        BrushLane lane = lanes.get(player);
        return lane == null ? 0 : pendingUnits(lane);
    }

    /** Commits queued for the player's strokes and not yet done (each pushes one entry when done). */
    public int queuedCommits(UUID player) {
        BrushLane lane = lanes.get(player);
        if (lane == null) return 0;
        int commits = 0;
        for (LaneWork work : lane.pending) {
            if (work instanceof CommitWork) commits++;
        }
        return commits;
    }

    private int pendingUnits(BrushLane lane) {
        int units = 0;
        for (LaneWork work : lane.pending) {
            if (work instanceof DabWork dab) units += dab.units;
        }
        return units;
    }

    /** The id of the player's open stroke. */
    public Optional<Integer> openStroke(UUID player) {
        BrushLane lane = lanes.get(player);
        return lane == null || lane.stroke == null ? Optional.empty() : Optional.of(lane.stroke.strokeId);
    }

    // ================================================================== builder mode

    @Override
    public void builderPowers(P p, int powers) {
        checkThread();
        builder.powers(p, powers);
    }

    @Override
    public BuilderOutcome builderPlace(P p, C2S.BuilderPlace place) {
        checkThread();
        return builder.place(p, Objects.requireNonNull(place));
    }

    @Override
    public BuilderOutcome builderBreak(P p, C2S.BuilderBreak breaks) {
        checkThread();
        return builder.breakBlocks(p, Objects.requireNonNull(breaks));
    }

    @Override
    public void builderDragEnd(P p, int dragId) {
        checkThread();
        builder.dragEnd(p, dragId);
    }

    /** Jump and Through: decided and carried out by the platform ({@link EngineHost#navigate}). */
    @Override
    public S2C.NavigateResult navigate(P p, C2S.Navigate m) {
        checkThread();
        return runtime.navigate(p, m);
    }

    /** The builder powers the player last reported (0 when none or unknown). */
    public int builderPowersOf(UUID player) {
        return builder.powersOf(player);
    }

    /** Whether the player has a builder-mode drag open (tests). */
    public boolean builderDragOpen(UUID player) {
        return builder.dragOpen(player);
    }

    /** Whether block (x, y, z) lies within the player's builder reach (tests). */
    public boolean builderReaches(P p, int x, int y, int z) {
        return builder.withinReach(p, x, y, z);
    }

    // ================================================================== lifecycle

    /**
     * Per server tick (after the executor): commits the record of strokes idle for {@value #STROKE_IDLE_SECONDS}
     * seconds (no dab admitted or applied), keeping the stroke open; and every {@value #PERMISSION_RECHECK_TICKS}
     * ticks re-checks the permissions of the online players with admitted jobs ({@link #revalidate}), which catches
     * nodes a permissions mod removed (op and deop reach {@link #revalidate} at once, through
     * {@code EditServiceHost.permissionsChanged}).
     */
    public void tick() {
        checkThread();
        sendDeferredAcks();
        if (++ticksSinceRecheck >= PERMISSION_RECHECK_TICKS) {
            ticksSinceRecheck = 0;
            recheckJobOwners();
        }
        long now = clock.getAsLong();
        for (BrushLane lane : lanes.values()) {
            StrokeSession stroke = lane.stroke;
            if (stroke == null || !lane.pending.isEmpty() || !stroke.dirty || stroke.committing) continue;
            if (now - stroke.lastActivity < STROKE_IDLE_NANOS) continue;
            if (cheapRecord(stroke)) {
                commit(stroke);
            } else {
                queueCommit(lane, stroke, true, false, false);
            }
        }
        // Lanes of players who left, once the lane has finished their work.
        lanes.values().removeIf(lane -> lane.left && lane.pending.isEmpty() && lane.closing.isEmpty());
        builder.tick();
        tickHistory();
    }

    // ================================================================== saved history

    /**
     * Store reports and rewrites, and the periodic save of open strokes (jobs are saved by the history service): every
     * {@value HistoryService#SAVE_OPEN_TICKS} ticks their changed sections are journaled, {@value #STROKE_SAVE_NANOS} ns
     * of it a tick, so a large stroke's hundreds of sections spread over several ticks.
     */
    private void tickHistory() {
        history.poll();
        if (!history.persistent()) return;
        if (++ticksSinceStrokeSave >= HistoryService.SAVE_OPEN_TICKS) {
            ticksSinceStrokeSave = 0;
            strokeSaveDue = true;
        }
        if (strokeSaveDue) strokeSaveDue = !saveOpenStrokes(System.nanoTime() + STROKE_SAVE_NANOS);
    }

    /**
     * Journals changed sections of open strokes, and of closed ones still committing, until {@code deadline} (at least
     * one). Returns whether none is left.
     */
    private boolean saveOpenStrokes(long deadline) {
        for (BrushLane lane : lanes.values()) {
            List<StrokeSession> strokes = new ArrayList<>(lane.closing);
            if (lane.stroke != null) strokes.add(lane.stroke);
            for (StrokeSession stroke : strokes) {
                if (!stroke.record.hasDirty()) continue;
                HistoryService.OpenRecord open = history.openRecord(stroke.history, stroke.worldId,
                        strokeLabel(stroke.spec.tool()), this::createdMillis, stroke.record);
                if (open == null) continue;
                for (long key : stroke.record.dirtyKeys()) {
                    history.saveSection(open, key);
                    if (System.nanoTime() >= deadline) return false;
                }
            }
        }
        return true;
    }

    /**
     * Journals the changed sections of open strokes (only those in {@code world}'s column (cx, cz) when {@code column}),
     * so a crash leaves what a stroke wrote undoable.
     */
    private Set<UUID> saveOpenStrokes(String world, int cx, int cz, boolean column) {
        Set<UUID> players = new java.util.HashSet<>();
        for (BrushLane lane : lanes.values()) {
            List<StrokeSession> strokes = new ArrayList<>(lane.closing);
            if (lane.stroke != null) strokes.add(lane.stroke);
            // The open stroke, and closed strokes whose commit is still queued.
            for (StrokeSession stroke : strokes) {
                if (!stroke.record.hasDirty()) continue;
                if (world != null && !world.equals(stroke.worldId)) continue;
                if (column && !stroke.record.dirtyIn(cx, cz)) continue;
                HistoryService.OpenRecord open = history.openRecord(stroke.history, stroke.worldId,
                        strokeLabel(stroke.spec.tool()), this::createdMillis, stroke.record);
                if (column) {
                    history.save(open, cx, cz);
                } else {
                    history.save(open);
                }
                players.add(lane.owner);
            }
        }
        return players;
    }

    /**
     * The game is about to save chunk (cx, cz) of {@code world} (the chunk-save hook): the history of every cell the
     * engine changed there is journaled first and handed to the operating system (waiting at most
     * {@code HistoryService.BARRIER_WAIT_NANOS}), so after a crash the chunk on disk is never newer than its history.
     * That includes what edited fluid did there since: each entry's trail in
     * this column is folded into the entry and journaled, when its history is loaded and no step of its player runs
     * (else it waits in memory, and the platform flags the chunk to be saved again, so a later save folds it). A no-op
     * without saved history; off the server thread (a mod saving
     * chunks elsewhere) it does nothing and returns false, which the caller counts.
     *
     * @return false when called off the server thread
     */
    public boolean beforeChunkSave(W world, int cx, int cz) {
        if (!runtime.isOnThread()) return false;
        if (!history.persistent()) return true;
        String id = runtime.worldId(world);
        Set<UUID> touched = new java.util.HashSet<>(history.saveOpen(id, cx, cz));
        touched.addAll(saveOpenStrokes(id, cx, cz, true));
        touched.addAll(trails.drainColumn(world, cx, cz, history::foldTrail));
        history.barrier(cx, cz, touched);
        return true;
    }

    /** The player joined: with saved history, their history starts loading now (it is ready by the time they edit). */
    public void playerJoined(UUID player) {
        checkThread();
        BrushLane lane = lanes.get(player);
        if (lane != null) lane.left = false;
        if (history.persistent()) history.session(player);
    }

    /**
     * Re-checks the player's rights against all their admitted jobs, in every world (op and deop): see
     * {@link #revalidate(Object, boolean)}.
     *
     * @return how many jobs were cancelled now
     */
    public int revalidate(P p) {
        return revalidate(p, true);
    }

    /**
     * Re-checks the player's rights against their admitted jobs. Each job needs, for as long as it runs:
     * <ul>
     *   <li>{@code use} and the node of its operation ({@code region}; {@code clipboard} for a paste; {@code scatter}
     *       for a scatter commit; undo and redo need only {@code use});</li>
     *   <li>{@code physics} when physics is on;</li>
     *   <li>the rights its admission used: {@code limit.bypass} when it was over a limit (or read a protected
     *       source), {@code edit.unloaded} when it touched unloaded chunks, and the operator-NBT right when it carries
     *       block entities (paste, move, stack, scatter commit) and was allowed to keep operator NBT.</li>
     * </ul>
     * A job whose owner lost one is cancelled like {@code CancelJob}: a queued job ends at once and changes nothing, a
     * running one stops at its next section boundary, keeping (and recording in history) what it applied. A check the
     * permissions mod fails (throws) cancels nothing. Dabs are re-checked by the brush lane one by one.
     *
     * @param allWorlds false for the periodic and leave re-checks: they skip jobs in a world other than the player's,
     *     since a permissions mod may grant a node in one world only (LuckPerms world contexts), and a player walking
     *     into another world must not cancel what they started where they held it
     * @return how many jobs were cancelled now
     */
    public int revalidate(P p, boolean allWorlds) {
        checkThread();
        UUID owner = runtime.id(p);
        builder.permissionsChanged(p);
        String here = runtime.worldId(runtime.world(p));
        List<TrackedJob> lost = new ArrayList<>();
        Map<Perm, Boolean> denied = new EnumMap<>(Perm.class);
        Boolean[] operatorNbtDenied = {null};
        for (TrackedJob job : jobs.values()) {
            if (!job.owner.equals(owner) || job.revoked || job.jobId == null) continue;
            if (!allWorlds && !job.worldId.equals(here)) continue;
            boolean missing = false;
            for (Perm node : job.required) {
                if (denied.computeIfAbsent(node, n -> permissions.denied(p, n))) {
                    missing = true;
                    break;
                }
            }
            if (!missing && job.operatorNbt) {
                if (operatorNbtDenied[0] == null) operatorNbtDenied[0] = permissions.operatorNbtDenied(p);
                missing = operatorNbtDenied[0];
            }
            if (missing) lost.add(job);
        }
        int cancelled = 0;
        for (TrackedJob job : lost) {
            job.revoked = true;
            // May finish a queued job right here, which removes it from the map iterated above.
            if (executor.cancel(owner, job.jobId)) {
                cancelled++;
                LOG.info("Sculptory: cancelled job {} ({}) of {}: a permission it needs was removed",
                        job.jobId, job.label, runtime.name(p));
            }
        }
        return cancelled;
    }

    /**
     * {@link #revalidate(Object, boolean)} (this world only) for every online owner of an admitted job.
     * Anything unexpected is logged and cancels nothing: the server tick must go on.
     */
    private void recheckJobOwners() {
        if (jobs.isEmpty()) return;
        Set<UUID> owners = new LinkedHashSet<>();
        for (TrackedJob job : jobs.values()) owners.add(job.owner);
        for (UUID owner : owners) {
            P online = runtime.online(owner);
            if (online == null) continue;
            try {
                revalidate(online, false);
            } catch (RuntimeException | LinkageError e) {
                LOG.error("Sculptory: re-checking {}'s permissions failed; their jobs go on",
                        runtime.name(online), e);
            }
        }
    }

    /**
     * The id of the player's current connection, given on first use and never reused: work admitted for one
     * connection and finished after the player left (a clipboard decoded off-thread, a job's report) can tell it no
     * longer belongs to them ({@link #isConnection}). Server thread only.
     */
    public long connection(UUID player) {
        return connections.computeIfAbsent(player, p -> ++connectionCounter);
    }

    /** Whether {@code id} (from {@link #connection}) is still the player's connection: they have not left since. */
    public boolean isConnection(UUID player, long id) {
        Long current = connections.get(player);
        return current != null && current == id;
    }

    /** Connection ids held, one per player active since they joined (tests). */
    public int connectionsHeld() {
        return connections.size();
    }

    /**
     * The player disconnected: cancels their jobs that have not started (they changed nothing, and would otherwise
     * run with nobody to see, undo or cancel them), stops their brush work, closes the stroke and drops their
     * clipboard. A Shape step being written stops at its part boundary (what it wrote stays recorded) and dabs not
     * started are dropped. Without saved history the stroke leaves no entry and their history is dropped: their running
     * jobs finish normally but push nothing (an admin can stop them with {@code /sculptory cancel <player>}). With saved
     * history the stroke becomes an entry, its record built a few sections a tick when large, and the history is kept
     * (saved, and unloaded once their running jobs and stroke commits have pushed). Work of theirs still in flight no
     * longer counts as theirs ({@link #isConnection}).
     */
    public void playerLeft(UUID player) {
        checkThread();
        connections.remove(player);
        for (JobInfo job : jobs(player)) {
            if (executor.isWaiting(job.jobId())) executor.cancel(player, job.jobId());
        }
        BrushLane lane = lanes.get(player);
        if (lane != null) leave(lane, history.persistent());
        builder.playerLeft(player, history.persistent());
        history.clear(player);
        clipboards.remove(player);
        scatterPlans.remove(player);
    }

    /**
     * Server stop, after {@link EditExecutor#shutdown()} (which drops queued dabs and ends jobs): forgets every
     * stroke, history and job. With saved history the open strokes become entries first (their applied dabs are in
     * the world the game saves next); the caller closes the store afterwards ({@link HistoryService#closeStore}).
     */
    public void shutdown() {
        // The executor dropped the queued work: dabs are skipped, commits done at once (CommitWork.dropped).
        for (BrushLane lane : lanes.values()) {
            while (!lane.pending.isEmpty()) lane.pending.peekFirst().dropped();
        }
        sendDeferredAcks();
        if (history.persistent()) {
            for (BrushLane lane : lanes.values()) {
                if (lane.stroke != null && lane.stroke.dirty) commit(lane.stroke);
            }
        }
        builder.shutdown(history.persistent());
        lanes.clear();
        history.clearAll();
        jobs.clear();
        connections.clear();
        clipboards.clear();
        assets.clear();
        scatterPlans.clear();
    }

    // ================================================================== brush lane internals

    /**
     * Per player: the open stroke, closed strokes whose record is still to be committed, and admitted brush work (dabs,
     * commits) in the order the executor's brush lane runs it.
     */
    final class BrushLane {
        final UUID owner;
        /** The latest entity of the player, for acknowledgements. */
        P player;
        StrokeSession stroke;
        /** Closed strokes whose record a queued {@link CommitWork} will push. */
        final List<StrokeSession> closing = new ArrayList<>();
        final ArrayDeque<LaneWork> pending = new ArrayDeque<>();
        /** The player left; the lane is forgotten once its work is done ({@link #tick}), unless they come back. */
        boolean left;

        BrushLane(P player) {
            this.owner = runtime.id(player);
            this.player = player;
        }
    }

    /** One stroke. */
    final class StrokeSession {
        final int strokeId;
        final BrushSpec spec;
        /** The tool's kernel under the global mask read at StrokeBegin. */
        final BrushKernel kernel;
        final W world;
        final String worldId;
        final HistoryService.Session history;
        final LiveReader reader;
        final WorldWriter writer;
        final StrokeState state = new StrokeState();
        /**
         * The Shape brush: the cells one placement reads ({@link ShapeStamp#cellCount}, the same for every copy, the
         * measure {@link ShapeStep} sizes its parts by); 0 for the terrain brushes.
         */
        final long shapeCells;
        /** How long this stroke's terrain dabs took, on average (0 before the first): the lane's prediction. */
        long dabNanos;
        /** Set when the lane refused one of its dabs: later dabs are skipped or refused with this reason. */
        RejectReason rejected;
        /** A copy that found no ground was reported (once per stroke). */
        boolean noGroundReported;
        RecordBuilder record = new RecordBuilder();
        /** The record holds cells not yet pushed. */
        boolean dirty;
        /** No more dabs are admitted (the stroke ended); its queued dabs still run. */
        boolean closed;
        /** A {@link CommitWork} for the record is queued. */
        boolean committing;
        int lastIndex = -1;
        long lastActivity;

        /** The global mask read at StrokeBegin ({@link BoundMask#ALL}: off). */
        final BoundMask mask;

        StrokeSession(int strokeId, BrushSpec spec, W world, String worldId, HistoryService.Session history,
                      LiveReader reader, WorldWriter writer, long now, long shapeCells, BoundMask mask) {
            this.strokeId = strokeId;
            this.spec = spec;
            this.mask = mask;
            this.kernel = MaskedKernel.wrap(BrushKernels.forTool(spec.tool()), mask);
            this.world = world;
            this.worldId = worldId;
            this.history = history;
            this.reader = reader;
            this.writer = writer;
            this.lastActivity = now;
            this.shapeCells = shapeCells;
        }
    }

    /** One admitted {@code Dabs} message: acknowledged once all its dabs ran or were dropped. */
    final class DabBatch {
        final BrushLane lane;
        final StrokeSession stroke;
        final int seq;
        /** The index of its last dab. */
        final int lastIndex;
        int remaining;
        boolean acknowledged;
        /** Inside {@link #dabs}: the dispatcher has not recorded the batch as admitted yet. */
        boolean admitting;

        DabBatch(BrushLane lane, StrokeSession stroke, int seq, int size, int lastIndex) {
            this.lane = lane;
            this.stroke = stroke;
            this.seq = seq;
            this.remaining = size;
            this.lastIndex = lastIndex;
        }
    }

    /**
     * A player's brush work, queued both on the executor's brush lane and in {@link BrushLane#pending}, in the same
     * order. {@link #flush} runs the pending work whole at once, after which the lane finds it done and skips it.
     */
    abstract class LaneWork implements BrushWork {
        final BrushLane lane;
        boolean done;

        LaneWork(BrushLane lane) {
            this.lane = lane;
        }

        @Override
        public UUID owner() {
            return lane.owner;
        }

        @Override
        public void run() {
            runPart(Long.MAX_VALUE);
        }
    }

    /** One dab and its symmetric copies, applied as one step; a Shape step in parts, over several ticks when large. */
    final class DabWork extends LaneWork {
        final DabBatch batch;
        final Dab dab;
        /** The dab and its copies under the stroke's symmetry ({@code [dab]} without symmetry). */
        final List<Dab> copies;
        /** Its cost in the player's queue ({@link #MAX_QUEUED_DABS}): copies, or the Shape brush's work units. */
        final int units;
        /** The Shape step between its first and last part. */
        ShapeRun shapeRun;

        DabWork(DabBatch batch, Dab dab, List<Dab> copies, int units) {
            super(batch.lane);
            this.batch = batch;
            this.dab = dab;
            this.copies = copies;
            this.units = units;
        }

        @Override
        public boolean runPart(long deadline) {
            return runDab(this, deadline);
        }

        @Override
        public void dropped() {
            dropDab(this);
        }

        /** A Shape part: its cells at the measured rate. A terrain dab: the stroke's dabs so far, on average. */
        @Override
        public long nextPieceNanos() {
            if (done) return 0;
            StrokeSession stroke = batch.stroke;
            if (stroke.spec.tool() != BrushTool.SHAPE) return stroke.dabNanos;
            long cells = shapeRun != null && !shapeRun.step.done() ? shapeRun.step.nextCells()
                    : Math.min(ShapeStep.PART_CELLS, stroke.shapeCells);
            return shapeNanos(cells);
        }

        /**
         * How long finishing it is predicted to take: a Shape step's cells left at the measured rate; a terrain dab the
         * stroke's dabs so far, on average (0 before its first: a terrain dab is one piece of a few milliseconds at most).
         */
        long remainingNanos() {
            if (done) return 0;
            StrokeSession stroke = batch.stroke;
            if (stroke.spec.tool() != BrushTool.SHAPE) return stroke.dabNanos;
            if (shapeRun != null) return shapeNanos(shapeRun.step.remainingCells());
            return shapeNanos(ShapeStamp.placements(stroke.spec, dab).size() * stroke.shapeCells);
        }
    }

    /** A Shape step being written in parts: the parts left, and the writer of the whole step. */
    final class ShapeRun {
        final ShapeStep step;
        final DabWriter writer;
        final SymmetricStep symmetric;
        /** What the parts write to: {@link #writer}, through the global mask when it is on. */
        final CellSink sink;

        ShapeRun(ShapeStep step, DabWriter writer, SymmetricStep symmetric, CellSink sink) {
            this.step = step;
            this.writer = writer;
            this.symmetric = symmetric;
            this.sink = sink;
        }
    }

    /**
     * Commits a stroke's record as one history entry within the lane's budget: builds its sections' parts
     * ({@link RecordBuilder#prepare}) a few at a time, journaling each as it is built when history is saved, so
     * {@link RecordBuilder#build()} at the end only collects them and the push journals only the seal; then pushes.
     * Queued behind the stroke's dabs when it closes, or ahead of its next dabs when its record passes the commit size.
     * The executor never refuses it for a full queue ({@link #essential}): it ends work already admitted.
     */
    final class CommitWork extends LaneWork {
        final StrokeSession stroke;
        final boolean push;
        /** The stroke ended: it leaves {@link BrushLane#closing} once committed. */
        final boolean close;
        /** The stroke's record as the history journal holds it while built (null without saved history). */
        HistoryService.OpenRecord open;
        long[] keys;
        int next;

        CommitWork(BrushLane lane, StrokeSession stroke, boolean push, boolean close) {
            super(lane);
            this.stroke = stroke;
            this.push = push;
            this.close = close;
        }

        @Override
        public boolean runPart(long deadline) {
            return runCommit(this, deadline);
        }

        /**
         * The executor stopped: the work queued ahead of it is done first (the executor dropped it already, so this
         * only settles its bookkeeping), then the record is committed at once.
         */
        @Override
        public void dropped() {
            while (!lane.pending.isEmpty() && lane.pending.peekFirst() != this) lane.pending.peekFirst().run();
            runCommit(this, Long.MAX_VALUE);
        }

        @Override
        public boolean essential() {
            return true;
        }

        /** Nothing will be pushed (the player left without saved history): the lane skips it. */
        void discard() {
            done = true;
            lane.pending.removeFirstOccurrence(this);
            stroke.committing = false;
        }

        @Override
        public long nextPieceNanos() {
            return done ? 0 : sectionNanos;
        }
    }

    private RejectReason admission(P p, BrushLane lane, StrokeSession stroke, int strokeId, int seq,
                                   List<Dab> dabs) {
        if (!config().editingEnabled) return RejectReason.DISABLED;
        if (stroke == null || stroke.strokeId != strokeId || stroke.closed) return RejectReason.INVALID;
        if (stroke.rejected != null) return stroke.rejected;
        if (seq < 0 || dabs == null || dabs.isEmpty() || dabs.size() > C2S.Dabs.MAX_DABS) return RejectReason.INVALID;
        int previous = stroke.lastIndex;
        List<Dab> copies = new ArrayList<>();
        int units = 0;
        for (Dab dab : dabs) {
            if (dab == null || dab.index() <= previous || !insideWorld(dab)) return RejectReason.INVALID;
            previous = dab.index();
            // Within the world, a dab's copies around a centre within the world fit dab coordinates.
            List<Dab> images = stroke.spec.symmetry().copies(dab);
            for (Dab copy : images) {
                if (!insideWorld(copy)) return RejectReason.INVALID;
                copies.add(copy);
            }
            units += units(stroke, dab, images);
        }
        if (runtime.world(p) != stroke.world) return RejectReason.INVALID;
        if (!permissions.has(p, Perm.USE) || !permissions.has(p, Perm.BRUSH)) return RejectReason.NO_PERMISSION;
        int queued = pendingUnits(lane);
        // One step larger than the cap goes through when nothing of the player's is queued (the client waits for it).
        if (queued + units > MAX_QUEUED_DABS && !(queued == 0 && dabs.size() == 1)) return RejectReason.RATE_LIMITED;
        // The executor's shared brush queue refuses (drops) work beyond its cap; refuse the batch up front instead.
        // A symmetric dab is one work item there, but runs every copy: count its units.
        if (executor.brushQueueSize() + units > executor.settings().maxBrushQueue()) return RejectReason.RATE_LIMITED;
        stroke.reader.invalidate();
        // A copy's chunks don't depend on the height it stands at. Its area does: locate the copies against the world
        // now (the lane locates them again before the step, against the world then) and check what each step reads.
        for (Dab dab : dabs) {
            for (Box area : areas(stroke, dab)) {
                if (!loaded(stroke.reader, area)) return RejectReason.UNLOADED;
            }
        }
        for (Dab dab : dabs) {
            for (Box box : stepBoxes(stroke, dab, SymmetricStep.of(stroke.spec, dab, stroke.reader))) {
                if (executor.isLockedFor(stroke.world, box, runtime.id(p))) return RejectReason.AREA_BUSY;
            }
        }
        return null;
    }

    /**
     * A dab's cost in the player's queue: its copies for the terrain brushes; for the Shape brush its placements (a dab
     * on the rotation centre still places four shapes) in work units of {@link ShapeStamp#WORK_UNIT_CELLS} cells.
     */
    private int units(StrokeSession stroke, Dab dab, List<Dab> copies) {
        if (stroke.spec.tool() != BrushTool.SHAPE) return copies.size();
        return ShapeStamp.units(ShapeStamp.placements(stroke.spec, dab).size(), stroke.shapeCells);
    }

    /**
     * Runs (the next parts of) a dab: a terrain dab whole, a Shape step's parts until {@code deadline} (at least one).
     * When it is done it leaves the pending work, its batch is acknowledged once all its dabs are, and a record past the
     * commit size is committed next. Returns whether it is done.
     */
    private boolean runDab(DabWork work, long deadline) {
        if (work.done) return true;
        DabBatch batch = work.batch;
        BrushLane lane = batch.lane;
        StrokeSession stroke = batch.stroke;
        boolean finished = true;
        try {
            if (stroke.rejected == null) {
                long start = System.nanoTime();
                finished = applyDab(lane, stroke, work, deadline);
                if (stroke.spec.tool() != BrushTool.SHAPE) {
                    long took = System.nanoTime() - start;
                    stroke.dabNanos = stroke.dabNanos == 0 ? took : (3 * stroke.dabNanos + took) / 4;
                }
            } else if (work.shapeRun != null) {
                // Refused meanwhile: what the parts so far wrote stays.
                endStep(lane, stroke, work.dab, work.shapeRun.writer, work.shapeRun.symmetric);
            }
        } catch (RuntimeException e) {
            LOG.error("Sculptory: dab {} of stroke {} failed; skipped", work.dab.index(), stroke.strokeId, e);
            reject(lane, stroke, work.dab, RejectReason.INVALID);
        }
        stroke.lastActivity = clock.getAsLong();
        if (!finished) return false;
        work.done = true;
        work.shapeRun = null;
        lane.pending.removeFirstOccurrence(work);
        completed(batch);
        long commitBytes = Math.min(STROKE_COMMIT_BYTES, history.limits().maxBytesPerPlayer() / 4);
        if (stroke.dirty && !stroke.committing && stroke.record.estimatedBytes() > commitBytes) {
            queueCommit(lane, stroke, true, false, true);
        }
        return true;
    }

    private void dropDab(DabWork work) {
        if (work.done) return;
        work.done = true;
        work.shapeRun = null;
        work.lane.pending.removeFirstOccurrence(work);
        completed(work.batch);
    }

    private void completed(DabBatch batch) {
        batch.remaining--;
        if (batch.remaining > 0 || batch.acknowledged) return;
        batch.acknowledged = true;
        if (batch.admitting) {
            // Dropped while being admitted: acknowledging now would come before the network layer records the
            // batch as admitted, so send it from the next tick() instead.
            deferredAcks.add(batch);
            return;
        }
        ack(batch);
    }

    private void ack(DabBatch batch) {
        try {
            acks.ack(batch.lane.player, batch.seq);
        } catch (RuntimeException e) {
            LOG.error("Sculptory: acknowledging dab batch {} failed", batch.seq, e);
        }
        P online = runtime.online(batch.lane.owner);
        if (online == null) return;
        try {
            events.dabsApplied(online, batch.stroke.strokeId, batch.lastIndex);
        } catch (RuntimeException e) {
            LOG.error("Sculptory: dabsApplied listener failed", e);
        }
    }

    private void sendDeferredAcks() {
        if (deferredAcks.isEmpty()) return;
        List<DabBatch> batches = new ArrayList<>(deferredAcks);
        deferredAcks.clear();
        for (DabBatch batch : batches) ack(batch);
    }

    /**
     * Applies one dab and its symmetric copies as one kernel step: re-checks what may have changed since admission
     * (permission, and every copy's loaded chunks), stands the copies on the ground where they land against the live
     * world ({@link SymmetricStep}, as the client's prediction does), checks what the step reads and writes for locks
     * ({@link #stepBoxes}), runs the kernel and writes the allowed cells through {@link DabWriter}.
     *
     * <p>A Shape step is written in parts ({@link ShapeStep}): the first each time the lane runs it, then more while the
     * next is predicted to end before {@code deadline} (its cells at the measured rate). Before each part after the
     * step's first the permission is asked again and the part's area checked for loaded chunks and locks, a refusal
     * ending the step (what the earlier parts wrote stays, recorded in the stroke). Returns whether the dab is done.
     */
    private boolean applyDab(BrushLane lane, StrokeSession stroke, DabWork work, long deadline) {
        // The latest entity is fine after a respawn or disconnect: permissions and protection go by profile.
        P player = lane.player;
        Dab dab = work.dab;
        if (work.shapeRun == null) {
            // An admitted dab stops only on a definite no: a permissions mod that fails (throws) stops nothing admitted.
            if (deniedBrush(player)) {
                reject(lane, stroke, dab, RejectReason.NO_PERMISSION);
                return true;
            }
            stroke.reader.invalidate();
            for (Box area : areas(stroke, dab)) {
                if (!loaded(stroke.reader, area)) {
                    reject(lane, stroke, dab, RejectReason.UNLOADED);
                    return true;
                }
            }
            // Against the world before this step: what the kernel reads, and what the client predicted from.
            SymmetricStep step = SymmetricStep.of(stroke.spec, dab, stroke.reader);
            for (Box box : stepBoxes(stroke, dab, step)) {
                if (executor.isLockedFor(stroke.world, box, runtime.id(player))) {
                    reject(lane, stroke, dab, RejectReason.AREA_BUSY);
                    return true;
                }
            }
            DabWriter writer = new DabWriter(stroke, player, writeBoxes(stroke, dab, step));
            if (stroke.spec.tool() != BrushTool.SHAPE) {
                try {
                    stroke.kernel.applyStep(stroke.spec, step.dabs(), stroke.state, stroke.reader, writer);
                } finally {
                    writer.finish();
                }
                endStep(lane, stroke, dab, writer, step);
                return true;
            }
            work.shapeRun = new ShapeRun(ShapeStep.of(stroke.spec, step.dabs(), stroke.state, stroke.reader), writer, step,
                    stroke.mask.acceptsAll() ? writer : new EditMasks.ShapeMaskSink(writer, stroke.mask, stroke.reader));
        }
        ShapeRun run = work.shapeRun;
        boolean first = true;
        while (!run.step.done()) {
            long cells = run.step.nextCells();
            if (!first && System.nanoTime() + shapeNanos(cells) > deadline) return false;
            if (run.writer.parts > 0) {
                // The world may have changed since the last part.
                if (deniedBrush(player)) {
                    reject(lane, stroke, dab, RejectReason.NO_PERMISSION);
                    break;
                }
                stroke.reader.invalidate();
                Box area = run.step.nextArea();
                if (!loaded(stroke.reader, area)) {
                    reject(lane, stroke, dab, RejectReason.UNLOADED);
                    break;
                }
                if (executor.isLockedFor(stroke.world, area, runtime.id(player))) {
                    reject(lane, stroke, dab, RejectReason.AREA_BUSY);
                    break;
                }
            }
            long start = System.nanoTime();
            run.writer.startPart();
            try {
                run.step.runNext(run.sink);
            } finally {
                run.writer.parts++;
                run.writer.finish();
            }
            if (cells >= 1024) {
                // Small parts are dominated by their fixed costs; they would skew the rate.
                double rate = (double) (System.nanoTime() - start) / cells;
                shapeCellNanos = (3 * shapeCellNanos + rate) / 4;
            }
            first = false;
        }
        endStep(lane, stroke, dab, run.writer, run.symmetric);
        return true;
    }

    /** How long reading and writing {@code cells} Shape cells is predicted to take. */
    private long shapeNanos(long cells) {
        return (long) (cells * shapeCellNanos);
    }

    /** Whether the player definitely lost {@code use} or {@code brush} (a failing permissions mod is not a no). */
    private boolean deniedBrush(P player) {
        return permissions.denied(player, Perm.USE) || permissions.denied(player, Perm.BRUSH);
    }

    /** After a step (or what of it was written): refused {@code PROTECTED} when protection kept every cell of it. */
    private void endStep(BrushLane lane, StrokeSession stroke, Dab dab, DabWriter writer, SymmetricStep step) {
        if (writer.written == 0 && writer.denied > 0) reject(lane, stroke, dab, RejectReason.PROTECTED);
        if (stroke.rejected == null && !step.noGround().isEmpty()) reportNoGround(lane, stroke, dab, step.noGround().size());
    }

    /**
     * Writes a step's cells: per-column protection and world border, physics off, recorded into the stroke. A chunk's
     * permit covers the parts of the step's dab areas inside it ({@link #permitBounds}), so a copy far from the dab
     * is checked where it writes.
     */
    private final class DabWriter implements CellSink {
        final StrokeSession stroke;
        final P player;
        final List<Box> boxes;
        long permitColumn = Long.MIN_VALUE;
        ChunkPermit permit;
        int written;
        int denied;
        /** Parts of a Shape step written so far. */
        int parts;

        DabWriter(StrokeSession stroke, P player, List<Box> boxes) {
            this.stroke = stroke;
            this.player = player;
            this.boxes = boxes;
        }

        /** Before each part of a Shape step: permits are asked afresh (protection may have changed since the last). */
        void startPart() {
            permitColumn = Long.MIN_VALUE;
            permit = null;
        }

        @Override
        public void set(int x, int y, int z, int handle) {
            set(x, y, z, handle, null);
        }

        /** {@code tile}: the block entity the cell keeps (a state pattern changed a property of the same block), or null. */
        @Override
        public void set(int x, int y, int z, int handle, BlockEntityData tile) {
            if (!permitFor(x >> 4, z >> 4).allows(x, z) || !runtime.insideBorder(stroke.world, x, z)) {
                denied++;
                return;
            }
            // The writer reads the live cell (state and block entity) right before writing and records that.
            if (stroke.writer.write(x, y, z, handle, tile, stroke.record::record)) {
                stroke.dirty = true;
                written++;
                // Fluid the stroke wrote is its entry's (the record's id): what it does later is undone with it. It stays
                // put until the record is committed, so none flows into a cell a later dab of the stroke writes.
                if (trails.wrote(stroke.world, x, y, z, handle, stroke.record.id())) trails.freeze(stroke.record.id());
            }
        }

        /** Clears the scheduled ticks of the cells written since the last call. */
        void finish() {
            if (written > 0) stroke.writer.clearTicksAtWrittenCells();
        }

        private ChunkPermit permitFor(int cx, int cz) {
            long column = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            if (column != permitColumn) {
                Box bounds = permitBounds(boxes, cx, cz);
                ChunkPermit found = bounds == null ? null : permissions.chunk(player, stroke.world, cx, cz, bounds);
                permit = found == null ? ChunkPermit.DENY : found;
                permitColumn = column;
            }
            return permit;
        }
    }

    /**
     * The bounds a chunk's permit is asked for: the smallest box holding the parts of {@code boxes} inside chunk
     * (cx, cz), or {@code null} when none reaches it (nothing may be written there). With one box this is its part in
     * the chunk, which gives the permit the whole box would.
     */
    static Box permitBounds(List<Box> boxes, int cx, int cz) {
        int x0 = cx << 4, z0 = cz << 4, x1 = x0 + 15, z1 = z0 + 15;
        Box bounds = null;
        for (Box box : boxes) {
            int minX = Math.max(x0, box.min().x()), maxX = Math.min(x1, box.max().x());
            int minZ = Math.max(z0, box.min().z()), maxZ = Math.min(z1, box.max().z());
            if (minX > maxX || minZ > maxZ) continue;
            Box part = new Box(new BlockPos(minX, box.min().y(), minZ), new BlockPos(maxX, box.max().y(), maxZ));
            bounds = bounds == null ? part : new Box(
                    new BlockPos(Math.min(bounds.min().x(), minX), Math.min(bounds.min().y(), box.min().y()),
                            Math.min(bounds.min().z(), minZ)),
                    new BlockPos(Math.max(bounds.max().x(), maxX), Math.max(bounds.max().y(), box.max().y()),
                            Math.max(bounds.max().z(), maxZ)));
        }
        return bounds;
    }

    /** The lane refused a dab of an admitted batch: the stroke is rejected until the next {@link #beginStroke}. */
    private void reject(BrushLane lane, StrokeSession stroke, Dab dab, RejectReason reason) {
        if (stroke.rejected != null) return;
        stroke.rejected = reason;
        P player = runtime.online(lane.owner);
        if (player == null) return;
        try {
            events.dabRejected(player, stroke.strokeId, dab.index(), reason);
        } catch (RuntimeException e) {
            LOG.error("Sculptory: dabRejected listener failed", e);
        }
    }

    /**
     * {@code copies} copies of a dab of the stroke found no ground and wrote nothing while the step went on: reported
     * once per stroke ({@link EditEvents#symmetryNoGround}).
     */
    private void reportNoGround(BrushLane lane, StrokeSession stroke, Dab dab, int copies) {
        if (stroke.noGroundReported) return;
        stroke.noGroundReported = true;
        P player = runtime.online(lane.owner);
        if (player == null) return;
        try {
            events.symmetryNoGround(player, stroke.strokeId, dab.index(), copies);
        } catch (RuntimeException e) {
            LOG.error("Sculptory: symmetryNoGround listener failed", e);
        }
    }

    /** Runs the lane's pending work now, whole and in order (each is then a no-op on the executor's lane). */
    private void flush(BrushLane lane) {
        while (!lane.pending.isEmpty()) lane.pending.peekFirst().run();
    }

    /**
     * Whether finishing the lane's pending work at once could take long: a stroke's commit, or dabs predicted to take more
     * than {@value #HEAVY_NANOS} ns ({@link DabWork#remainingNanos}).
     */
    private boolean heavy(BrushLane lane) {
        long nanos = 0;
        for (LaneWork work : lane.pending) {
            if (!(work instanceof DabWork dab)) return true;
            nanos += dab.remainingNanos();
            if (nanos > HEAVY_NANOS) return true;
        }
        return false;
    }

    /** The refusal of an operation that must wait for the player's brush work ({@link #commitStroke}). */
    private static EditRejected strokePending() {
        return new EditRejected(RejectReason.QUEUE_FULL, "a large brush stroke is still being applied",
                EditRejected.STROKE_PENDING);
    }

    /**
     * Before an operation that must follow the player's strokes in history (a region op, undo, redo, Undo anyway, a
     * copy, a scatter preview): applies their queued dabs and pushes their strokes' records so far (keeping the open
     * stroke open), so a later read of the world (a copy's snapshot) or a later history entry follows them. Callers call
     * it before any side effect of their own, since it may refuse. When that is large work (dabs of more than
     * {@value #HEAVY_UNITS} units, a record being committed, or an open stroke's record with more than
     * {@value #SMALL_RECORD_SECTIONS} sections to prepare) the operation is refused {@code QUEUE_FULL} with
     * {@link EditRejected#STROKE_PENDING} instead of doing it in one tick: the open stroke's commit is queued behind the
     * lane's work, so a retry a moment later goes ahead. The player's open builder-mode drag becomes its entry first
     * ({@link BuilderMode#commitDrag}).
     */
    public void commitStroke(UUID player) throws EditRejected {
        builder.commitDrag(player);
        BrushLane lane = lanes.get(player);
        if (lane == null) return;
        StrokeSession open = lane.stroke;
        if (!heavy(lane)) flush(lane);
        if (open == null || !open.dirty || open.committing) {
            if (!lane.pending.isEmpty()) throw strokePending();
            return;
        }
        if (lane.pending.isEmpty() && cheapRecord(open)) {
            commit(open);
            return;
        }
        queueCommit(lane, open, true, false, false);
        throw strokePending();
    }

    /**
     * Ends the lane's open stroke: no more dabs are admitted, and its record becomes one history entry. Its queued dabs
     * and its commit are done at once when that is small work; otherwise the lane finishes them over the next ticks
     * ({@link CommitWork}) and the entry is pushed then, in order with the player's other strokes.
     */
    private void closeStroke(BrushLane lane) {
        StrokeSession stroke = lane.stroke;
        if (stroke == null) return;
        lane.stroke = null;
        stroke.closed = true;
        if (!heavy(lane)) flush(lane);
        if (lane.pending.isEmpty() && cheapRecord(stroke)) {
            if (stroke.dirty) commit(stroke);
            return;
        }
        lane.closing.add(stroke);
        queueCommit(lane, stroke, true, true, false);
    }

    /**
     * The player left: the Shape step being written stops at its part boundary (what it wrote stays recorded), dabs not
     * started are dropped, and the open stroke closes. With saved history ({@code keep}) its record and those of strokes
     * still committing become entries: at once when cheap, else built a few sections a tick, their records registered
     * with the history so it stays loaded until they are pushed. Without, nothing of theirs is pushed.
     */
    private void leave(BrushLane lane, boolean keep) {
        for (LaneWork work : new ArrayList<>(lane.pending)) {
            if (work instanceof DabWork dab) {
                dropDab(dab);
            } else if (!keep) {
                ((CommitWork) work).discard();
            }
        }
        if (!keep) lane.closing.clear();
        StrokeSession stroke = lane.stroke;
        if (stroke != null) {
            lane.stroke = null;
            stroke.closed = true;
            if (keep && stroke.dirty) {
                if (lane.pending.isEmpty() && cheapRecord(stroke)) {
                    commit(stroke);
                } else {
                    lane.closing.add(stroke);
                    queueCommit(lane, stroke, true, true, false);
                }
            }
        }
        for (StrokeSession closing : lane.closing) {
            history.openRecord(closing.history, closing.worldId, strokeLabel(closing.spec.tool()), this::createdMillis,
                    closing.record);
        }
        if (lane.pending.isEmpty() && lane.closing.isEmpty()) {
            lanes.remove(lane.owner);
        } else {
            lane.left = true;
        }
    }

    /**
     * Queues the commit of {@code stroke}'s record: after the lane's pending work, or ({@code next}) before it. The
     * executor takes it even when its queue is full; if it has stopped, it runs at once ({@link CommitWork#dropped}).
     */
    private void queueCommit(BrushLane lane, StrokeSession stroke, boolean push, boolean close, boolean next) {
        CommitWork work = new CommitWork(lane, stroke, push, close);
        stroke.committing = true;
        if (next) {
            lane.pending.addFirst(work);
            executor.submitBrushNext(work);
        } else {
            lane.pending.addLast(work);
            executor.submitBrush(work);
        }
    }

    /**
     * Prepares (and journals, with saved history) the record's sections until {@code deadline} (at least one, then more
     * while the next is predicted to end in time), then builds and pushes it. Returns whether the commit is done. A
     * failure while preparing is logged and the record is built at once; the commit always ends, so the player's
     * requests never wait on it for ever.
     */
    private boolean runCommit(CommitWork work, long deadline) {
        if (work.done) return true;
        StrokeSession stroke = work.stroke;
        try {
            if (work.keys == null) {
                work.keys = stroke.record.sectionKeys();
                if (work.push) {
                    work.open = history.openRecord(stroke.history, stroke.worldId, strokeLabel(stroke.spec.tool()),
                            this::createdMillis, stroke.record);
                }
            }
            // Queued behind a large step, the record may turn out cheap (blocks placed into air), or have become cheap
            // with the sections prepared so far: built at once then.
            if (cheapRecord(stroke)) work.next = work.keys.length;
            boolean any = false;
            while (work.next < work.keys.length) {
                long start = System.nanoTime();
                if (any && start + sectionNanos > deadline) return false;
                long key = work.keys[work.next++];
                stroke.record.prepare(key);
                if (work.open != null) history.saveSection(work.open, key);
                sectionNanos = (3 * sectionNanos + System.nanoTime() - start) / 4;
                any = true;
            }
        } catch (RuntimeException e) {
            LOG.error("Sculptory: preparing the record of stroke {} failed; it is built at once", stroke.strokeId, e);
        }
        finishCommit(work);
        return true;
    }

    /** Ends a commit: it leaves the lane, and the record is pushed (a failure is logged; the lane goes on). */
    private void finishCommit(CommitWork work) {
        work.done = true;
        work.lane.pending.removeFirstOccurrence(work);
        StrokeSession stroke = work.stroke;
        stroke.committing = false;
        try {
            if (work.push && stroke.dirty) commit(stroke);
        } catch (RuntimeException e) {
            LOG.error("Sculptory: the record of stroke {} could not be pushed", stroke.strokeId, e);
        } finally {
            if (work.close) work.lane.closing.remove(stroke);
        }
    }

    /**
     * Whether building the stroke's record at once is cheap: at most {@value #SMALL_RECORD_SECTIONS} sections left to
     * prepare ({@link RecordBuilder#unpreparedSections}).
     */
    private boolean cheapRecord(StrokeSession stroke) {
        return stroke.record.unpreparedSections() <= SMALL_RECORD_SECTIONS;
    }

    /** Pushes the stroke's record so far as one entry and starts a new record. */
    private void commit(StrokeSession stroke) {
        RecordBuilder builder = stroke.record;
        EditRecord record = builder.build();
        stroke.record = new RecordBuilder();
        stroke.dirty = false;
        trails.thaw(builder.id());
        if (record.before().isEmpty()) {
            history.discard(builder.id()); // what was saved of it while open
            return;
        }
        String label = strokeLabel(stroke.spec.tool()) + " · " + blocks(record.before().cellCount());
        history.push(stroke.history, new HistoryEntry(builder.id(), stroke.history.player(), stroke.worldId,
                label, record, createdMillis()));
    }

    private BrushLane lane(P p) {
        BrushLane lane = lanes.computeIfAbsent(runtime.id(p), id -> new BrushLane(p));
        lane.player = p;
        return lane;
    }

    /**
     * The areas a dab may read and write, for the loaded checks: the Shape brush's placements' boxes
     * ({@link #shapeBoxes}); for a terrain brush each copy's {@link #dabBox} (a copy's chunks do not depend on the height
     * it stands at).
     */
    List<Box> areas(StrokeSession stroke, Dab dab) {
        int bottom = runtime.bottomY(stroke.world), top = runtime.topY(stroke.world);
        if (stroke.spec.tool() == BrushTool.SHAPE) return shapeBoxes(stroke.spec, bottom, top, dab);
        List<Box> boxes = new ArrayList<>();
        for (Dab copy : stroke.spec.symmetry().copies(dab)) boxes.add(dabBox(stroke.spec, bottom, top, copy));
        return boxes;
    }

    /**
     * The boxes of the shapes a Shape dab places ({@link ShapeStamp#placements}: its own and its copies'), y clamped to
     * {@code [bottomY, topYExclusive)}: exactly the cells it may read and write.
     */
    static List<Box> shapeBoxes(BrushSpec spec, int bottomY, int topYExclusive, Dab dab) {
        List<Box> boxes = new ArrayList<>();
        int top = topYExclusive - 1;
        for (ShapeStamp.Placement placement : ShapeStamp.placements(spec, dab)) {
            Box box = placement.box();
            int y0 = Math.max(bottomY, Math.min(top, box.min().y()));
            int y1 = Math.max(bottomY, Math.min(top, box.max().y()));
            boxes.add(new Box(new BlockPos(box.min().x(), y0, box.min().z()), new BlockPos(box.max().x(), y1, box.max().z())));
        }
        return boxes;
    }

    /** The boxes a step's writer asks chunk permits for: each placement's box, or each located dab's {@link #dabBox}. */
    private List<Box> writeBoxes(StrokeSession stroke, Dab dab, SymmetricStep step) {
        int bottom = runtime.bottomY(stroke.world), top = runtime.topY(stroke.world);
        if (stroke.spec.tool() == BrushTool.SHAPE) return shapeBoxes(stroke.spec, bottom, top, dab);
        List<Box> boxes = new ArrayList<>(step.dabs().size());
        for (Dab placed : step.dabs()) boxes.add(dabBox(stroke.spec, bottom, top, placed));
        return boxes;
    }

    /**
     * Every cell a terrain dab may read or write: its column grid (footprint plus a ring) with a margin, and vertically
     * the surface scan window plus the largest move (FLATTEN/SMOOTH up to {@code radius + 8}), paint depth and plants,
     * clamped to the world ({@code [bottomY, topYExclusive)}). It also holds everything the Surface mode reads and
     * writes: {@code radius + 2} around the dab's block on every axis, {@code radius + 10} across for Surface Raise, Lower
     * and Flatten ({@code SurfaceKernel}, the cylinder along any of six directions).
     */
    static Box dabBox(BrushSpec spec, int bottomY, int topYExclusive, Dab dab) {
        int r = spec.radius();
        long reach = (long) r + SCAN_MARGIN;
        // Surface Raise, Lower and Flatten work in a cylinder of that reach along any of the six directions
        // (SurfaceKernel), so their box is as wide across as it is tall; everything else (Smooth, and the Weather brush in
        // either mode: WeatherKernel) stays within radius + 2 across.
        boolean lines = spec.surface() && spec.tool() != BrushTool.SMOOTH && spec.tool() != BrushTool.WEATHER;
        int across = lines ? (int) reach + 2 : r + 2;
        int bx = dab.blockX(), by = dab.blockY(), bz = dab.blockZ();
        int bottom = bottomY, top = topYExclusive - 1;
        int y0 = (int) Math.max(bottom, Math.min(top, by - 2 * reach - BrushSpec.MAX_DEPTH - 1));
        int y1 = (int) Math.max(bottom, Math.min(top, by + 2 * reach + 3));
        return new Box(new BlockPos(bx - across, y0, bz - across), new BlockPos(bx + across, y1, bz + across));
    }

    /**
     * What a step reads and writes, for the lock checks. The Shape brush: its placements' boxes. A terrain brush: each
     * of its dabs' areas ({@link #dabBox}) at the height it stands at, and the column each copy's ground was searched in
     * ({@link SymmetricStep#searchBox}: the column, and in the Surface mode the columns around it, where a copy first
     * looks for a surface at its own point); a copy that found no ground has only its column, and a lock anywhere in a
     * searched column (129 blocks tall) refuses the dab {@code AREA_BUSY}, even where the copy's own area is free.
     */
    List<Box> stepBoxes(StrokeSession stroke, Dab dab, SymmetricStep step) {
        return stepBoxes(stroke.spec, runtime.bottomY(stroke.world), runtime.topY(stroke.world), dab, step);
    }

    /** {@link #stepBoxes} for a brush of {@code spec} in a world of y {@code [bottomY, topYExclusive)}. */
    static List<Box> stepBoxes(BrushSpec spec, int bottomY, int topYExclusive, Dab dab, SymmetricStep step) {
        if (spec.tool() == BrushTool.SHAPE) return shapeBoxes(spec, bottomY, topYExclusive, dab);
        List<Box> boxes = new ArrayList<>(step.dabs().size() + step.searched().size());
        for (Dab placed : step.dabs()) boxes.add(dabBox(spec, bottomY, topYExclusive, placed));
        for (Dab copy : step.searched()) {
            boxes.add(SymmetricStep.searchBox(spec, copy.blockX(), copy.blockZ(), dab.blockY(), bottomY, topYExclusive));
        }
        return boxes;
    }

    private static boolean loaded(LiveReader reader, Box box) {
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                if (!reader.isLoaded(cx, cz)) return false;
            }
        }
        return true;
    }

    /**
     * Whether a brush's clip box lies within the world: horizontally within {@link #HORIZONTAL_LIMIT} and
     * vertically within the build height. The clip only narrows what a stroke writes (permissions, protection and
     * the border still apply per cell), so this refuses nonsense rather than guarding a write.
     */
    boolean insideWorld(Box clip, W world) {
        int limit = HORIZONTAL_LIMIT;
        return Math.abs((long) clip.min().x()) <= limit && Math.abs((long) clip.max().x()) <= limit
                && Math.abs((long) clip.min().z()) <= limit && Math.abs((long) clip.max().z()) <= limit
                && clip.min().y() >= runtime.bottomY(world) && clip.max().y() < runtime.topY(world);
    }

    /** Whether a symmetry centre lies within {@link #HORIZONTAL_LIMIT} (always without symmetry). */
    public static boolean insideWorld(Symmetry symmetry) {
        long limit = 2L * HORIZONTAL_LIMIT;
        return Math.abs((long) symmetry.x2()) <= limit && Math.abs((long) symmetry.z2()) <= limit;
    }

    /**
     * Why a stroke of {@code spec} is larger than {@code maxBrushRadius} allows ({@code TOO_LARGE} without
     * {@code limit.bypass}), or {@code null}: a radius over it, or a Shape brush taller than the largest diameter,
     * {@code 2 × maxBrushRadius + 1}.
     */
    static String overBrushLimit(BrushSpec spec, int maxBrushRadius) {
        if (spec.radius() > maxBrushRadius) return "radius " + spec.radius() + " > " + maxBrushRadius;
        int maxHeight = 2 * maxBrushRadius + 1;
        // The size along the facing: a sphere's is its diameter, whatever height it carries.
        int axial = spec.shapeSpec() == null ? 0 : spec.shapeSpec().axialSize(spec.radius());
        if (axial > maxHeight) return "shape height " + axial + " > " + maxHeight;
        return null;
    }

    /** Whether a dab's block lies within {@link #HORIZONTAL_LIMIT}. */
    private static boolean insideWorld(Dab dab) {
        return Math.abs(dab.blockX()) <= HORIZONTAL_LIMIT && Math.abs(dab.blockZ()) <= HORIZONTAL_LIMIT;
    }

    private static boolean validMaterial(Pattern material, StateSpace states) {
        int[] handles = switch (material) {
            case Pattern.Single single -> new int[] {single.state()};
            case Pattern.Weighted weighted -> weighted.states();
            case Pattern.Arranged arranged -> arranged.mix().states();
            case Pattern.Waterlog waterlog -> new int[] {waterlog.fluidSource()};
            case Pattern.Dry dry -> new int[0];
            // Tinker's property pattern is for region ops only.
            case Pattern.SetProperty set -> null;
            // Better Replace's patterns are for region ops only.
            case Pattern.KeepShape keep -> null;
            case Pattern.Remap remap -> null;
        };
        if (handles == null) return false;
        for (int h : handles) {
            if (h < 0 || h >= states.size()) return false;
        }
        return !(material instanceof Pattern.Waterlog waterlog) || Pattern.isFluidSource(states, waterlog.fluidSource());
    }

    // ================================================================== jobs

    /** Wraps a job's listener: tracks progress, pushes history, fixes up the conflict count. */
    private final class TrackedJob implements JobListener {
        final UUID owner;
        /** The owner's name when the job was admitted, for {@code /sculptory cancel <player>} after they left. */
        final String ownerName;
        final String label;
        final JobListener downstream;
        final ConflictCountingProgram conflicts;
        final RecordBuilder record;
        final String worldId;
        final HistoryService.Session session;
        final HistoryService.Op op;
        /** The nodes the job needs for as long as it runs ({@link #revalidate}); edit.unloaded is added at admission. */
        final EnumSet<Perm> required;
        /** The job may keep operator-only NBT of untrusted block entities it carries, which it needs the right for. */
        boolean operatorNbt;
        /** An Undo anyway / Redo anyway of the run ({@link #historyOverwrite}), not an undo or redo step. */
        boolean overwrite;
        /** The record saved while the job writes (null when history is not saved or the job records nothing). */
        HistoryService.OpenRecord openRecord;
        /** The owner's connection when the job was admitted ({@link #connection}). */
        final long connection;
        /** Entries whose fluid is held still while this undo, redo or overwrite runs ({@link FluidTrailHook#freeze}). */
        List<UUID> frozen = List.of();
        /** Cancelled by {@link #revalidate} (a node was removed): not cancelled or logged again. */
        boolean revoked;
        UUID jobId;
        long estimatedCells;
        long done;
        long total;
        Phase phase = Phase.QUEUED;

        TrackedJob(UUID owner, String ownerName, String label, JobListener downstream, ConflictCountingProgram conflicts,
                   RecordBuilder record, String worldId, HistoryService.Session session, HistoryService.Op op,
                   Set<Perm> required) {
            this.owner = owner;
            this.ownerName = ownerName;
            this.label = label;
            this.downstream = downstream == null ? JobRequest.NO_LISTENER : downstream;
            this.conflicts = conflicts;
            this.record = record;
            this.worldId = worldId;
            this.session = session;
            this.op = op;
            this.required = EnumSet.copyOf(required);
            this.connection = connection(owner);
        }

        void freeze(List<UUID> entries) {
            frozen = List.copyOf(entries);
            for (UUID entry : frozen) trails.freeze(entry);
        }

        void admitted(JobTicket ticket) {
            jobId = ticket.jobId();
            if (executor.admittedUnloaded(jobId)) required.add(Perm.EDIT_UNLOADED);
            estimatedCells = ticket.estimatedCells();
            total = ticket.estimatedCells();
            jobs.put(jobId, this);
        }

        JobInfo info() {
            return new JobInfo(jobId, owner, label, estimatedCells, done, total, phase);
        }

        @Override
        public void progress(UUID job, long d, long t, Phase ph) {
            done = d;
            total = t;
            phase = ph;
            try {
                downstream.progress(job, d, t, ph);
            } catch (RuntimeException e) {
                LOG.error("Sculptory: job listener failed in progress()", e);
            }
        }

        @Override
        public void finished(JobResult r) {
            jobs.remove(r.jobId());
            for (UUID entry : frozen) trails.thaw(entry);
            // The executor reports the entities kept (undo, redo); the program the cells.
            JobResult result = conflicts == null ? r : new JobResult(r.jobId(), r.outcome(), r.changed(),
                    r.skippedProtected(), conflicts.conflicts() + r.skippedConflicts(), r.strippedNbt());
            try {
                if (record != null) history.editFinished(session, entry(r.outcome()), openRecord);
                if (op != null && overwrite) {
                    history.finishOverwrite(session, r.outcome() == JobOutcome.COMPLETED);
                } else if (op != null) {
                    // A partial redo must become undoable; a partial undo stays the undo candidate.
                    boolean applied = r.outcome() == JobOutcome.COMPLETED
                            || (op == HistoryService.Op.REDO && r.changed() > 0);
                    history.finish(session, applied, r.outcome() == JobOutcome.COMPLETED, result.skippedConflicts());
                }
            } catch (RuntimeException e) {
                LOG.error("Sculptory: history update for job {} ({}) failed", r.jobId(), label, e);
            }
            try {
                downstream.finished(result);
            } catch (RuntimeException e) {
                LOG.error("Sculptory: job listener failed in finished()", e);
            }
            if (op == null && conflicts != null && conflicts.conflicts() > 0) {
                // A scatter commit skipped placements whose cells were built on since the preview. Told only to the
                // connection that committed it (after a reconnect the new client never saw this job).
                P online = runtime.online(owner);
                if (online != null && isConnection(owner, connection)) {
                    try {
                        events.scatterSkipped(online, conflicts.conflicts());
                    } catch (RuntimeException e) {
                        LOG.error("Sculptory: scatterSkipped listener failed", e);
                    }
                }
            }
        }

        /** The job's history entry, or {@code null} when it changed nothing. */
        private HistoryEntry entry(JobOutcome outcome) {
            EditRecord built = record.build();
            if (built.isEmpty()) return null;
            String state = switch (outcome) {
                case COMPLETED -> "";
                case CANCELLED -> " (cancelled)";
                case FAILED -> " (failed)";
            };
            String entryLabel = label + state + " · " + blocks(built.before().cellCount())
                    + (built.entities().isEmpty() ? "" : " · " + entities(built.entities().size()));
            // The builder's id: the entry its saved sections belong to.
            return new HistoryEntry(record.id(), owner, worldId, entryLabel, built, createdMillis());
        }
    }

    /** Translates {@link HistoryService} callbacks into {@link EditEvents} for online players. */
    private final class HistoryEvents implements HistoryService.Listener {
        @Override
        public void changed(UUID player) {
            P online = runtime.online(player);
            if (online == null) return;
            try {
                events.historyChanged(online, history.snapshot(player));
            } catch (RuntimeException e) {
                LOG.error("Sculptory: historyChanged listener failed", e);
            }
        }

        @Override
        public void evicted(UUID player, int steps, boolean includesNewest) {
            P online = runtime.online(player);
            if (online == null) return;
            try {
                events.historyEvicted(online, steps, includesNewest);
            } catch (RuntimeException e) {
                LOG.error("Sculptory: historyEvicted listener failed", e);
            }
        }
    }

    // ================================================================== helpers

    /**
     * Compiles against the world's height; {@code pasteSource} is what a paste's source reference resolved to, and
     * {@code maxCells} the compiler's volume budget ({@code EditTooLargeException}, answered {@code TOO_LARGE});
     * {@code bypass} (the player has {@code limit.bypass}) raises the row cap of shapes in huge boxes.
     */
    /**
     * The op's symmetric copies ({@code OpSymmetry.copies}; the op itself without symmetry), {@code INVALID} when an
     * image leaves the coordinate range.
     */
    static List<OpSymmetry.Copy> symmetricCopies(OpSpec s) throws EditRejected {
        try {
            return OpSymmetry.copies(s);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new EditRejected(RejectReason.INVALID, "a symmetric copy leaves the world: " + e.getMessage());
        }
    }

    /** {@code INVALID} unless {@code box} lies horizontally within {@link #HORIZONTAL_LIMIT}. */
    static void requireInsideWorld(Box box, String what) throws EditRejected {
        int limit = HORIZONTAL_LIMIT;
        if (box.min().x() < -limit || box.max().x() > limit || box.min().z() < -limit || box.max().z() > limit) {
            throw new EditRejected(RejectReason.INVALID, what + " lies beyond the world limit: " + box);
        }
    }

    private EditProgram compile(OpSpec spec, List<OpSymmetry.Copy> copies, W world, SourceBlocks pasteSource,
                                MultiPaste scatter, long maxCells, boolean bypass) throws EditRejected {
        BoundMask sourceMask = compileSourceMask;
        StateSpace states = runtime.states();
        int bottom = runtime.bottomY(world);
        int top = runtime.topY(world);
        CompileContext context = new CompileContext() {
            @Override
            public long maxCells() {
                return maxCells;
            }

            @Override
            public long maxBigShapeRows() {
                return bypass ? OpCompiler.MAX_BIG_SHAPE_ROWS_BYPASS : OpCompiler.MAX_BIG_SHAPE_ROWS;
            }

            /** The global mask of a Move being compiled ({@link #compileSourceMask}). */
            @Override
            public BoundMask sourceMask() {
                return sourceMask;
            }

            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return spec instanceof OpSpec.Paste paste && paste.src().equals(ref)
                        ? Optional.ofNullable(pasteSource) : Optional.empty();
            }

            @Override
            public Optional<MultiPaste> scatterPlan(UUID planId) {
                return spec instanceof OpSpec.ScatterCommit commit && commit.planId().equals(planId)
                        ? Optional.ofNullable(scatter) : Optional.empty();
            }

            @Override
            public int bottomY() {
                return bottom;
            }

            @Override
            public int topYExclusive() {
                return top;
            }

            @Override
            public long maxSections() {
                return EngineEditService.this.maxSections(bottom, top - 1);
            }

            /** Update blocks: vanilla's neighbour updates over the live world, without physics. */
            @Override
            public NeighbourShapes neighbourShapes() {
                return spec instanceof OpSpec.UpdateBlocks ? runtime.neighbourShapes(world) : null;
            }
        };
        try {
            return OpCompiler.compile(spec, copies, context);
        } catch (UnsupportedOperationException e) {
            throw new EditRejected(RejectReason.INVALID, "not available yet: " + e.getMessage());
        } catch (EditTooLargeException e) {
            throw new EditRejected(RejectReason.TOO_LARGE, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new EditRejected(RejectReason.INVALID, e.getMessage());
        }
    }

    private W worldOf(String id) {
        for (W world : runtime.worlds()) {
            if (runtime.worldId(world).equals(id)) return world;
        }
        return null;
    }

    private void requireEditing() throws EditRejected {
        if (!config().editingEnabled) throw new EditRejected(RejectReason.DISABLED);
    }

    private void require(P p, Perm node) throws EditRejected {
        if (!permissions.has(p, node)) throw new EditRejected(RejectReason.NO_PERMISSION, node.node());
    }

    private void checkThread() {
        if (!runtime.isOnThread()) {
            throw new IllegalStateException("EngineEditService must be used on the server thread");
        }
    }

    /** Wall-clock creation time, strictly increasing, so "oldest" (global eviction) follows push order. */
    public long createdMillis() {
        lastCreatedMillis = Math.max(System.currentTimeMillis(), lastCreatedMillis + 1);
        return lastCreatedMillis;
    }

    /** A stroke's history label before its block count: "Raise stroke", "Flatten stroke"..., and "Shape". */
    static String strokeLabel(BrushTool tool) {
        return tool == BrushTool.SHAPE ? toolName(tool) : toolName(tool) + " stroke";
    }

    /** "Raise", "Flatten"... */
    static String toolName(BrushTool tool) {
        String name = tool.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    /** "1 block", "1,284 blocks". */
    public static String blocks(long n) {
        return count(n, "block");
    }

    /** "1 entity", "12 entities". */
    static String entities(long n) {
        return n == 1 ? "1 entity" : String.format(Locale.ROOT, "%,d entities", n);
    }

    /** "1 placement", "1,284 placements". */
    static String count(long n, String noun) {
        return n == 1 ? "1 " + noun : String.format(Locale.ROOT, "%,d %ss", n, noun);
    }
}
