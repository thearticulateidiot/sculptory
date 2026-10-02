package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.fabric.world.ClientSync;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.impl.BrushWork;
import dev.sculptory.server.engine.impl.ColumnPlan;
import dev.sculptory.server.engine.impl.CountedTickets;
import dev.sculptory.server.engine.impl.SectionLocks;
import dev.sculptory.server.engine.impl.TicketWindow;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs edits on the server thread at {@code START_SERVER_TICK} within a time budget.
 *
 * <ul>
 *   <li><b>Brush lane</b> first: {@link BrushWork} items until {@code brushLaneShare} of the budget is used (at least
 *       one part per tick). Players take turns, a part of their next item each (a player's items stay in submission
 *       order), so one player's large strokes never keep another's dabs waiting behind them; an item that stops part-way
 *       ({@link BrushWork#runPart}) stays at the head of its player's queue.</li>
 *   <li><b>Bulk lane</b> with the rest: active jobs share the budget by deficit round robin. Each job goes
 *       {@code LOAD_CHUNKS → SNAPSHOT_SOURCES → APPLY → FINALIZE}; APPLY captures each section, runs
 *       {@code program.compute}, writes through {@link dev.sculptory.fabric.world.BlockWriter} and records
 *       changed cells into the job's {@link RecordSink}.</li>
 *   <li><b>Admission</b>: every admitted job queues on a section-lock table covering the sections it reads and
 *       writes; overlapping jobs run in FIFO order. Limits: active jobs per owner and globally, and a bounded
 *       wait queue, also bounded per owner so one player cannot fill it for everyone (beyond either,
 *       {@code QUEUE_FULL}). {@link #isLocked} lets the brush path refuse dabs with {@code AREA_BUSY}.</li>
 *   <li><b>Chunks</b>: jobs hold {@code sculptory:edit} tickets (radius 0, at most N in flight, released per
 *       column), reference-counted per column across jobs ({@link CountedTickets}). Touching unloaded chunks at
 *       admission needs the {@code LOAD} policy and {@code mayLoadChunks}. Sections whose column lies entirely
 *       outside the world border are dropped at admission (never loaded); sources there read as air. A job may
 *       touch at most {@code maxColumnsPerJob} columns ({@code TOO_LARGE}).</li>
 *   <li><b>Cancel</b> takes effect at the next section boundary; <b>shutdown</b> finishes the current section,
 *       then cancels everything. Applied work (and its record) is kept either way.</li>
 *   <li><b>Area holds</b> ({@link #hold}) keep jobs and dabs out of a set of chunk columns while something reads
 *       them over several ticks (scatter planning); <b>lanes</b> ({@link #addLane}) run such work inside this
 *       executor's tick budget, after the brush lane and before the bulk lane.</li>
 * </ul>
 * All public methods must be called on the server thread.
 *
 * <p><b>Known limitation (physics on):</b> a chunk loaded only by the edit ticket sits at level 33, which is loaded
 * but not block-ticking. Scheduled ticks and fluid flow caused by physics-on writes in such chunks wait until a
 * player (or another ticket) makes the chunk ticking.
 */
public final class EditExecutor {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    /** Region-op chunk ticket: radius 0, expires after 600 ticks unless refreshed. */
    public static final ChunkTicketType<ChunkPos> EDIT_TICKET =
            ChunkTicketType.create("sculptory:edit", Comparator.comparingLong(ChunkPos::toLong), 600);

    /** The brush queue key of work without an owner. */
    private static final Object NO_OWNER = new Object();
    /**
     * The share of the tick budget bulk jobs get after the brush lane even when it ran past the whole budget (its tick's
     * first piece always runs), so jobs always progress.
     */
    static final double MIN_BULK_SHARE = 0.25;
    /** Deficit round-robin quantum, in work units (about one per cell). */
    static final long QUANTUM = 4096;
    static final int TIME_CHECK_CELLS = 256;
    /** Work units charged for capturing and computing one section. */
    static final long SECTION_OVERHEAD_UNITS = 256;
    /** Tickets expire after 600 ticks; re-adding them this often keeps them alive at any tick rate. */
    static final int TICKET_REFRESH_TICKS = 200;
    /** A job fails if the chunk it waits for has not loaded after this much wall-clock time. */
    static final long CHUNK_WAIT_LIMIT_NANOS = 120_000_000_000L;
    /**
     * How long after a player's last brush dabs they may still hold unacknowledged predictions (so {@link ClientSync}
     * sends them per-block updates): the brush lane's backlog plus a round trip, with a wide margin.
     */
    static final long PREDICTION_GRACE_NANOS = 10_000_000_000L;

    /**
     * Executor limits, usually from {@link SculptoryConfig}.
     *
     * @param maxQueued jobs waiting (not active) for everyone; {@link #submit} refuses beyond it
     * @param maxBrushQueue queued brush work items; {@link #submitBrush} refuses beyond it
     * @param maxColumnsPerJob distinct chunk columns one job may read or write
     * @param maxQueuedPerOwner jobs one owner may have waiting, so one player cannot take the whole wait queue
     *     ({@link JobRequest#SYSTEM_OWNER} is exempt)
     */
    public record Settings(long tickBudgetNanos, long maxBlocksPerTick, double brushLaneShare, int maxActivePerOwner,
                           int maxActiveGlobal, int maxQueued, int maxTicketsPerJob, UnloadedPolicy unloadedPolicy,
                           int maxBrushQueue, int maxColumnsPerJob, int maxQueuedPerOwner) {
        /** Waiting jobs per owner when not configured: at most this, and at most the whole queue. */
        public static final int DEFAULT_MAX_QUEUED_PER_OWNER = 8;

        public Settings {
            if (tickBudgetNanos <= 0 || maxBlocksPerTick < 0 || !(brushLaneShare > 0 && brushLaneShare <= 1)
                    || maxActivePerOwner < 1 || maxActiveGlobal < 1 || maxQueued < 1 || maxTicketsPerJob < 1
                    || maxBrushQueue < 1 || maxColumnsPerJob < 1 || maxQueuedPerOwner < 1) {
                throw new IllegalArgumentException("Invalid executor settings");
            }
            Objects.requireNonNull(unloadedPolicy);
        }

        /** With the default per-owner wait cap ({@value #DEFAULT_MAX_QUEUED_PER_OWNER}, or {@code maxQueued} if lower). */
        public Settings(long tickBudgetNanos, long maxBlocksPerTick, double brushLaneShare, int maxActivePerOwner,
                        int maxActiveGlobal, int maxQueued, int maxTicketsPerJob, UnloadedPolicy unloadedPolicy,
                        int maxBrushQueue, int maxColumnsPerJob) {
            this(tickBudgetNanos, maxBlocksPerTick, brushLaneShare, maxActivePerOwner, maxActiveGlobal, maxQueued,
                    maxTicketsPerJob, unloadedPolicy, maxBrushQueue, maxColumnsPerJob,
                    Math.min(maxQueued, DEFAULT_MAX_QUEUED_PER_OWNER));
        }

        public static Settings from(SculptoryConfig config, boolean dedicatedServer) {
            SculptoryConfig.Executor e = config.executor;
            return new Settings(config.tickBudgetNanos(dedicatedServer), e.maxBlocksPerTick, e.brushLaneShare,
                    config.limits.maxJobsPerPlayer, e.maxActiveJobsGlobal, e.maxQueuedJobs, e.maxChunkTicketsPerJob,
                    config.unloadedChunks, e.maxQueuedBrushWork, e.maxColumnsPerJob,
                    Math.min(e.maxQueuedJobs, e.maxQueuedJobsPerPlayer));
        }
    }

    /** Section keys split by the world border: the ones to use, and the ones whose column lies outside it. */
    record BorderSplit(long[] inside, long[] outside) {}

    /** The bulk lane's remaining time and cell allowance for one tick. */
    static final class Budget {
        final long deadline;
        long cellsLeft;
        boolean timeUp;

        Budget(long deadline, long cellsLeft) {
            this.deadline = deadline;
            this.cellsLeft = cellsLeft;
        }

        boolean exhausted() {
            return timeUp || cellsLeft <= 0;
        }

        boolean checkTime() {
            if (!timeUp && System.nanoTime() >= deadline) timeUp = true;
            return timeUp;
        }
    }

    private final MinecraftServer server;
    private final FabricStateSpace states;
    /** The limits in effect ({@link #updateSettings}: replaced by a config reload). */
    private Settings settings;
    private final SectionLocks<BulkJob> locks = new SectionLocks<>();
    /** Queued brush work per owner ({@link #NO_OWNER} for work of no player), in submission order. */
    private final Map<Object, ArrayDeque<BrushWork>> brushQueues = new HashMap<>();
    /** The owners with queued brush work, in the order the lane serves them next. */
    private final ArrayDeque<Object> brushTurns = new ArrayDeque<>();
    private int brushQueued;
    private final List<BulkJob> waiting = new ArrayList<>();
    private final List<BulkJob> active = new ArrayList<>();
    private final Map<UUID, BulkJob> jobs = new HashMap<>();
    /** Jobs not finished that touched unloaded chunks at admission ({@link #admittedUnloaded}). */
    private final Set<UUID> admittedUnloaded = new HashSet<>();
    private final Map<ServerWorld, CountedTickets> tickets = new HashMap<>();
    private final List<AreaHold> holds = new ArrayList<>();
    private final List<Lane> lanes = new ArrayList<>();
    private final Map<Lane, Double> laneShares = new HashMap<>();
    /** Client sync of the bulk jobs' writes, per world; flushed at the end of every tick and when a job ends. */
    private final Map<ServerWorld, ClientSync> clientSyncs = new HashMap<>();
    /** When each player last sent brush dabs ({@code System.nanoTime}). */
    private final Map<UUID, Long> predictedAt = new HashMap<>();
    private int roundRobin;
    private long tickCount;
    private boolean shutDown;

    public EditExecutor(MinecraftServer server, FabricStateSpace states, Settings settings) {
        this.server = Objects.requireNonNull(server);
        this.states = Objects.requireNonNull(states);
        this.settings = Objects.requireNonNull(settings);
    }

    public FabricStateSpace states() {
        return states;
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Uses {@code settings} from now on ({@code /sculptory reload}): the tick budget, lane share and block cap from the next
     * tick, the queue and active-job caps for jobs admitted or started from now on. Jobs already admitted keep the chunk
     * tickets they were admitted with; nothing admitted is refused or cancelled because of the new caps.
     */
    public void updateSettings(Settings settings) {
        checkThread();
        this.settings = Objects.requireNonNull(settings);
    }

    /** Changes the tick share of a lane registered with {@link #addLane} ({@code scatter.tickShare} on a reload). */
    public void laneShare(Lane lane, double share) {
        checkThread();
        if (!(share > 0 && share <= 1)) throw new IllegalArgumentException("Lane share must be in (0, 1]");
        if (laneShares.containsKey(lane)) laneShares.put(lane, share);
    }

    /** Ticks run by this executor. */
    public long tickCount() {
        return tickCount;
    }

    /**
     * Admits a bulk job; it starts on a later tick. Never calls the listener before returning.
     *
     * @throws EditRejected {@code INVALID} outside the build limit or the world border, {@code TOO_LARGE} over the
     *     column cap, {@code QUEUE_FULL} when the wait queue is full or the owner already has
     *     {@code maxQueuedPerOwner} jobs waiting, {@code UNLOADED} when it touches unloaded chunks and may not load
     *     them, {@code DISABLED} while stopping
     */
    public JobTicket submit(JobRequest request) throws EditRejected {
        checkThread();
        if (shutDown) throw new EditRejected(RejectReason.DISABLED, "server is stopping");
        EditProgram program = request.program();
        Box bounds = Objects.requireNonNull(program.bounds(), "program bounds");
        ServerWorld world = request.world();
        if (!WorldChecks.intersectsBuildLimit(world, bounds)) {
            throw new EditRejected(RejectReason.INVALID, "outside the build limit");
        }
        if (waiting.size() >= settings.maxQueued()) throw new EditRejected(RejectReason.QUEUE_FULL);
        // One player may not take the whole wait queue from everyone else (the server's own jobs are exempt). Jobs
        // that will start on the next tick do not count, so a burst on an idle server is admitted.
        if (!request.owner().equals(JobRequest.SYSTEM_OWNER)) {
            int mine = heldBackCount(request.owner());
            if (mine >= settings.maxQueuedPerOwner()) {
                throw new EditRejected(RejectReason.QUEUE_FULL, mine + " of your edits are still waiting to start");
            }
        }
        WorldBorder border = world.getWorldBorder();
        double west = border.getBoundWest(), east = border.getBoundEast();
        double north = border.getBoundNorth(), south = border.getBoundSouth();
        BorderSplit writes = splitByBorder(program.sectionOrder(), west, east, north, south);
        BorderSplit reads = splitByBorder(program.sourceSections(), west, east, north, south);
        long[] order = writes.inside();
        long[] sources = reads.inside();
        if (order.length == 0 && program.sectionOrder().length > 0) {
            throw new EditRejected(RejectReason.INVALID, "outside the world border");
        }
        // Entity work visits columns of its own (an undo of entities only has no sections): they count and load alike.
        long[] entityColumns = request.entities() == null ? new long[0] : request.entities().admissionColumns();
        int columns = distinctColumns(order, sources, entityColumns);
        if (columns > settings.maxColumnsPerJob()) {
            throw new EditRejected(RejectReason.TOO_LARGE, columns + " chunk columns > " + settings.maxColumnsPerJob());
        }
        boolean unloaded = !allColumnsLoaded(world, order) || !allColumnsLoaded(world, sources)
                || !allEntityColumnsLoaded(world, entityColumns);
        if (unloaded) {
            if (settings.unloadedPolicy() == UnloadedPolicy.REFUSE) {
                throw new EditRejected(RejectReason.UNLOADED, "chunks are not loaded");
            }
            if (!request.mayLoadChunks()) {
                throw new EditRejected(RejectReason.UNLOADED, "loading chunks needs " + Perm.EDIT_UNLOADED.node());
            }
        }
        BulkJob job = new BulkJob(this, UUID.randomUUID(), request, order, sources, reads.outside(), states);
        // What it may use is fixed now: a config reload changes what later jobs get, not this one.
        job.admittedMaxTickets = settings.maxTicketsPerJob();
        if (unloaded) admittedUnloaded.add(job.id);
        job.blocked = locks.acquire(world.getRegistryKey(), job.lockKeys, job);
        for (AreaHold hold : holds) {
            if (hold.overlaps(job)) {
                job.heldBy++;
                hold.behind.add(job);
                if (!job.owner().equals(hold.owner)) hold.blocksSomeoneElse();
            }
        }
        waiting.add(job);
        jobs.put(job.id, job);
        return new JobTicket(job.id, program.label(), job.totalCells);
    }

    /** Cancels a job: a queued job ends now, a running one at its next section boundary. */
    public boolean cancel(UUID jobId) {
        checkThread();
        BulkJob job = jobs.get(jobId);
        if (job == null || job.isDone()) return false;
        if (job.stage == BulkJob.Stage.WAITING) {
            finish(job, JobOutcome.CANCELLED);
        } else {
            job.cancelRequested = true;
        }
        return true;
    }

    /** {@link #cancel(UUID)} restricted to the owner's own jobs. */
    public boolean cancel(UUID owner, UUID jobId) {
        checkThread();
        BulkJob job = jobs.get(jobId);
        return job != null && job.owner().equals(owner) && cancel(jobId);
    }

    /** Whether the job is admitted and has not started yet (cancelling it ends it at once, having changed nothing). */
    public boolean isWaiting(UUID jobId) {
        BulkJob job = jobs.get(jobId);
        return job != null && job.stage == BulkJob.Stage.WAITING;
    }

    /** Whether the job, still running, touched chunks that were not loaded when it was admitted. */
    public boolean admittedUnloaded(UUID jobId) {
        return admittedUnloaded.contains(jobId);
    }

    /** Whether an admitted (queued or running) job or an area hold holds this section of {@code world}. */
    public boolean isLocked(ServerWorld world, long sectionKey) {
        Object key = world.getRegistryKey();
        if (locks.isLocked(key, sectionKey)) return true;
        for (AreaHold hold : holds) {
            if (hold.covers(key, sectionKey)) return true;
        }
        return false;
    }

    /** Whether any section touched by {@code box} is locked. A query only; admission uses {@link #isLockedFor}. */
    public boolean isLocked(ServerWorld world, Box box) {
        Object key = world.getRegistryKey();
        for (AreaHold hold : holds) {
            if (hold.intersects(key, box)) return true;
        }
        return jobLocked(key, box);
    }

    /**
     * {@link #isLocked(ServerWorld, Box)} for an edit {@code requester} wants to make there (dab admission, a cut:
     * {@code AREA_BUSY}). A hold of someone else is noted as {@link AreaHold#blockedSince blocking} when it covers the
     * box, or when a job it keeps waiting locks the box. When any job locking the box is held back by no hold, that job
     * refuses the edit whatever the holds do: it is refused and no hold is charged, so a hold is never charged for a
     * refusal it did not cause. This may under-charge: that job may itself wait, through its section locks, for a job a
     * hold keeps waiting, and such chains are not followed.
     */
    public boolean isLockedFor(ServerWorld world, Box box, UUID requester) {
        Objects.requireNonNull(requester);
        Object key = world.getRegistryKey();
        List<BulkJob> locking = jobsLocking(key, box);
        for (BulkJob job : locking) {
            if (job.heldBy == 0) return true;
        }
        boolean held = false;
        for (AreaHold hold : holds) {
            boolean covers = hold.intersects(key, box);
            if (!covers && !hold.keepsWaiting(locking)) continue;
            if (covers) held = true;
            if (!requester.equals(hold.owner)) hold.blocksSomeoneElse();
        }
        return held || !locking.isEmpty();
    }

    /**
     * {@link #isLockedFor(ServerWorld, Box, UUID)} for exactly the sections {@code sectionKeys} (a region's, which need
     * not fill its bounds), charging holds the same way.
     */
    public boolean isLockedFor(ServerWorld world, long[] sectionKeys, UUID requester) {
        Objects.requireNonNull(requester);
        Object key = world.getRegistryKey();
        LongArrayList sections = LongArrayList.wrap(sectionKeys.clone());
        List<BulkJob> locking = jobsLocking(key, sections);
        for (BulkJob job : locking) {
            if (job.heldBy == 0) return true;
        }
        boolean held = false;
        for (AreaHold hold : holds) {
            boolean covers = false;
            for (int k = 0; k < sections.size() && !covers; k++) covers = hold.covers(key, sections.getLong(k));
            if (!covers && !hold.keepsWaiting(locking)) continue;
            if (covers) held = true;
            if (!requester.equals(hold.owner)) hold.blocksSomeoneElse();
        }
        return held || !locking.isEmpty();
    }

    /** The admitted (queued or running) jobs whose section locks include a section of {@code box}. */
    private List<BulkJob> jobsLocking(Object key, Box box) {
        if (!jobLocked(key, box)) return List.of();
        LongArrayList sections = new LongArrayList();
        box.forEachSectionKey(sections::add);
        return jobsLocking(key, sections);
    }

    /** The admitted (queued or running) jobs whose section locks include one of {@code sections}. */
    private List<BulkJob> jobsLocking(Object key, LongArrayList sections) {
        boolean any = false;
        for (int k = 0; k < sections.size() && !any; k++) any = locks.isLocked(key, sections.getLong(k));
        if (!any) return List.of();
        List<BulkJob> found = new ArrayList<>();
        for (BulkJob job : jobs.values()) {
            if (job.isDone() || !job.request.world().getRegistryKey().equals(key)) continue;
            for (int k = 0; k < sections.size(); k++) {
                if (Arrays.binarySearch(job.lockKeys, sections.getLong(k)) >= 0) {
                    found.add(job);
                    break;
                }
            }
        }
        return found;
    }

    private boolean jobLocked(Object key, Box box) {
        boolean[] locked = {false};
        box.forEachSectionKey(section -> {
            if (!locked[0] && locks.isLocked(key, section)) locked[0] = true;
        });
        return locked[0];
    }

    // ---------------------------------------------------------------- area holds and lanes

    /** {@link #hold(ServerWorld, LongSet, int, int)} over the rectangle of chunk columns {@code cx0..cx1 × cz0..cz1}. */
    public AreaHold hold(ServerWorld world, int cx0, int cz0, int cx1, int cz1, int sy0, int sy1) {
        if (cx0 > cx1 || cz0 > cz1 || sy0 > sy1) throw new IllegalArgumentException("Empty hold");
        LongOpenHashSet columns = new LongOpenHashSet();
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) columns.add(ColumnPlan.pack(cx, cz));
        }
        return hold(world, null, columns, sy0, sy1, System::nanoTime);
    }

    /**
     * Keeps edits out of a set of chunk columns ({@link ColumnPlan#pack packed}; sections {@code sy0..sy1}) while
     * something reads them over several ticks, such as a scatter preview. Holds never wait for each other. A hold is
     * {@link AreaHold#ready() ready} once every job admitted before it that touches its columns has finished; until
     * it is released, jobs admitted after it that touch them stay queued ({@code QUEUED}) and {@link #isLocked}
     * reports its sections, so dabs there are refused ({@code AREA_BUSY}). It never loads chunks. An empty set holds
     * nothing. The caller must {@link AreaHold#release() release} it.
     *
     * <p>The hold notes, on {@code clock}, when it first held off someone other than {@code owner} (their job queued
     * behind it, or their dab or cut refused through {@link #isLockedFor}): {@link AreaHold#blockedSince}. A
     * {@code null} owner counts every job and requester as someone else.
     */
    public AreaHold hold(ServerWorld world, UUID owner, LongSet columns, int sy0, int sy1, LongSupplier clock) {
        checkThread();
        if (sy0 > sy1) throw new IllegalArgumentException("Empty hold");
        AreaHold hold = new AreaHold(world.getRegistryKey(), owner, new LongOpenHashSet(columns), sy0, sy1,
                Objects.requireNonNull(clock));
        for (BulkJob job : jobs.values()) {
            if (!job.isDone() && hold.overlaps(job)) hold.ahead.add(job);
        }
        holds.add(hold);
        return hold;
    }

    /** Area holds not yet released. */
    public int holdCount() {
        return holds.size();
    }

    /** A hold on a set of chunk columns ({@link #hold}). Server thread only. */
    public final class AreaHold {
        /** {@link #blockedSince()} of a hold that has held off nobody else. */
        public static final long NEVER = Long.MIN_VALUE;

        private final Object world;
        private final UUID owner;
        private final LongOpenHashSet columns;
        /** The columns' bounding rectangle, for quick rejection (empty when there are no columns). */
        private final int cx0, cz0, cx1, cz1;
        private final int sy0, sy1;
        private final LongSupplier clock;
        /** Jobs admitted before the hold, touching it, not finished yet. */
        private final Set<BulkJob> ahead = Collections.newSetFromMap(new IdentityHashMap<>());
        /** Jobs admitted after the hold that wait for it. */
        private final List<BulkJob> behind = new ArrayList<>();
        private long blockedSince = NEVER;
        private boolean released;

        private AreaHold(Object world, UUID owner, LongOpenHashSet columns, int sy0, int sy1, LongSupplier clock) {
            this.world = world;
            this.owner = owner;
            this.clock = clock;
            this.columns = columns;
            int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (long column : columns) {
                int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
                x0 = Math.min(x0, cx);
                z0 = Math.min(z0, cz);
                x1 = Math.max(x1, cx);
                z1 = Math.max(z1, cz);
            }
            this.cx0 = x0;
            this.cz0 = z0;
            this.cx1 = x1;
            this.cz1 = z1;
            this.sy0 = sy0;
            this.sy1 = sy1;
        }

        /** Whether no job admitted before the hold still touches its rectangle. */
        public boolean ready() {
            return !released && ahead.isEmpty();
        }

        /** When (on the hold's clock) it first held off someone other than its owner, or {@link #NEVER}. */
        public long blockedSince() {
            return blockedSince;
        }

        /** How long, up to {@code now}, it has been holding off someone else: 0 if it never has. */
        public long blockingFor(long now) {
            return blockedSince == NEVER ? 0 : Math.max(0, now - blockedSince);
        }

        void blocksSomeoneElse() {
            if (blockedSince == NEVER && !released) blockedSince = clock.getAsLong();
        }

        /** Jobs admitted before the hold that it still waits for. */
        public int waitingFor() {
            return ahead.size();
        }

        public boolean released() {
            return released;
        }

        /** Chunk columns held. */
        public long columns() {
            return columns.size();
        }

        /** Whether chunk column (cx, cz) is held (at some height). */
        public boolean holdsColumn(int cx, int cz) {
            return columns.contains(ColumnPlan.pack(cx, cz));
        }

        /** Gives the rectangle back: the jobs waiting for it may run. Idempotent. */
        public void release() {
            checkThread();
            if (released) return;
            released = true;
            holds.remove(this);
            for (BulkJob job : behind) job.heldBy--;
            behind.clear();
            ahead.clear();
        }

        void jobFinished(BulkJob job) {
            ahead.remove(job);
        }

        /** Whether one of {@code jobs} waits for this hold. */
        boolean keepsWaiting(List<BulkJob> jobs) {
            for (BulkJob job : jobs) {
                for (BulkJob waiting : behind) {
                    if (waiting == job) return true;
                }
            }
            return false;
        }

        boolean covers(Object key, long section) {
            return key.equals(world) && covers(BlockBuffer.keyX(section), BlockBuffer.keyY(section), BlockBuffer.keyZ(section));
        }

        private boolean covers(int sx, int sy, int sz) {
            return sx >= cx0 && sx <= cx1 && sz >= cz0 && sz <= cz1 && sy >= sy0 && sy <= sy1
                    && columns.contains(ColumnPlan.pack(sx, sz));
        }

        boolean intersects(Object key, Box box) {
            if (!key.equals(world) || (box.max().y() >> 4) < sy0 || (box.min().y() >> 4) > sy1) return false;
            int x0 = Math.max(cx0, box.min().x() >> 4), x1 = Math.min(cx1, box.max().x() >> 4);
            int z0 = Math.max(cz0, box.min().z() >> 4), z1 = Math.min(cz1, box.max().z() >> 4);
            if (x0 > x1 || z0 > z1) return false;
            if (((long) x1 - x0 + 1) * ((long) z1 - z0 + 1) > columns.size()) {
                for (long column : columns) {
                    int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
                    if (cx >= x0 && cx <= x1 && cz >= z0 && cz <= z1) return true;
                }
                return false;
            }
            for (int cx = x0; cx <= x1; cx++) {
                for (int cz = z0; cz <= z1; cz++) {
                    if (columns.contains(ColumnPlan.pack(cx, cz))) return true;
                }
            }
            return false;
        }

        boolean overlaps(BulkJob job) {
            Object key = job.request.world().getRegistryKey();
            if (!key.equals(world)) return false;
            Box bounds = job.program.bounds();
            if (bounds != null && !intersects(key, bounds) && job.sources.length == 0) return false;
            for (long section : job.lockKeys) {
                if (covers(BlockBuffer.keyX(section), BlockBuffer.keyY(section), BlockBuffer.keyZ(section))) return true;
            }
            return false;
        }
    }

    /** Work that shares the executor's tick budget, such as scatter planning. Server thread only. */
    public interface Lane {
        /** Whether the lane has work this tick. */
        boolean busy();

        /** Works until about {@code deadline} ({@code System.nanoTime}), in bounded steps. */
        void run(long deadline);
    }

    /**
     * Runs {@code lane} each tick while it is busy, after the brush lane, with at most {@code share} of the tick
     * budget; the bulk lane gets what is left, so the tick stays within the budget.
     */
    public void addLane(Lane lane, double share) {
        if (!(share > 0 && share <= 1)) throw new IllegalArgumentException("Lane share must be in (0, 1]");
        lanes.add(Objects.requireNonNull(lane));
        laneShares.put(lane, share);
    }

    public void removeLane(Lane lane) {
        lanes.remove(lane);
        laneShares.remove(lane);
    }

    private void runLanes(long start, long budget) {
        for (Lane lane : lanes.toArray(new Lane[0])) {
            if (!lane.busy()) continue;
            long end = Math.min(start + budget, System.nanoTime() + (long) (budget * laneShares.get(lane)));
            try {
                lane.run(end);
            } catch (RuntimeException e) {
                LOG.error("Sculptory lane failed", e);
            }
        }
    }

    /**
     * Queues a dab (or other brush work) for the brush lane, after its owner's queued work.
     *
     * @return false when the work was refused because the queue is full ({@code maxBrushQueue}, unless the work is
     *     {@link BrushWork#essential}) or the executor has shut down; {@link BrushWork#dropped()} has then already been
     *     called
     */
    public boolean submitBrush(BrushWork work) {
        return queueBrush(work, false);
    }

    /**
     * Queues brush work at the head of its owner's queue, before their other queued work (a stroke's record committed
     * between two of its dabs). Refused as {@link #submitBrush} is.
     */
    public boolean submitBrushNext(BrushWork work) {
        return queueBrush(work, true);
    }

    private boolean queueBrush(BrushWork work, boolean first) {
        checkThread();
        Objects.requireNonNull(work);
        if (shutDown || (brushQueued >= settings.maxBrushQueue() && !work.essential())) {
            dropped(work);
            return false;
        }
        Object key = work.owner() == null ? NO_OWNER : work.owner();
        ArrayDeque<BrushWork> queue = brushQueues.get(key);
        if (queue == null) {
            queue = new ArrayDeque<>();
            brushQueues.put(key, queue);
            brushTurns.addLast(key);
        }
        if (first) {
            queue.addFirst(work);
        } else {
            queue.addLast(work);
        }
        brushQueued++;
        return true;
    }

    public int activeJobCount() {
        return active.size();
    }

    public int queuedJobCount() {
        return waiting.size();
    }

    public int brushQueueSize() {
        return brushQueued;
    }

    /** Vanilla edit tickets currently held (distinct columns), counted where they are added and removed. */
    public int ticketsInFlight() {
        int total = 0;
        for (CountedTickets counted : tickets.values()) total += counted.held();
        return total;
    }

    /** How many jobs hold the edit ticket of chunk (cx, cz) in {@code world}. */
    public int ticketHolders(ServerWorld world, int cx, int cz) {
        CountedTickets counted = tickets.get(world);
        return counted == null ? 0 : counted.holders(cx, cz);
    }

    /**
     * Notes that {@code player} sent predicted edits (brush dabs) just now: for {@link #PREDICTION_GRACE_NANOS} bulk
     * writes reach them as per-block updates ({@link ClientSync}).
     */
    public void predicted(UUID player) {
        checkThread();
        predictedAt.put(Objects.requireNonNull(player), System.nanoTime());
    }

    /** Whether the player may still hold unacknowledged predicted block changes (see {@link #predicted}). */
    public boolean mayHavePredictions(ServerPlayerEntity player) {
        Long at = predictedAt.get(player.getUuid());
        return at != null && System.nanoTime() - at < PREDICTION_GRACE_NANOS;
    }

    /** The client sync bulk jobs in {@code world} write through (see {@link ClientSync}). */
    public ClientSync clientSync(ServerWorld world) {
        return clientSyncs.computeIfAbsent(world, w -> new ClientSync(w, this::mayHavePredictions));
    }

    /** Sends the bulk writes made since the last flush to the players watching them ({@link ClientSync#flush}). */
    private void flushClientSync(boolean force) {
        for (ClientSync sync : clientSyncs.values()) {
            try {
                sync.flush(tickCount, force);
            } catch (RuntimeException e) {
                LOG.error("Sculptory could not send edited chunks to players", e);
            }
        }
    }

    /**
     * The end of a server tick; registered on {@code ServerTickEvents.END_SERVER_TICK}. Sends this tick's bulk writes
     * to players: at the end of the tick, after the network tick has sent vanilla's prediction acks, so a resent
     * column always arrives after the acks of block changes the server handled before it.
     */
    public void endTick() {
        checkThread();
        if (shutDown) return;
        flushClientSync(false);
        if (tickCount % TICKET_REFRESH_TICKS == 0) {
            // Worlds a dimension mod unloaded.
            clientSyncs.keySet().removeIf(w -> server.getWorld(w.getRegistryKey()) != w);
        }
    }

    /** One executor tick; registered on {@code ServerTickEvents.START_SERVER_TICK}. */
    public void tick() {
        checkThread();
        if (shutDown) return;
        tickCount++;
        long start = System.nanoTime();
        long budget = settings.tickBudgetNanos();
        runBrushLane(start + (long) (budget * settings.brushLaneShare()));
        long afterBrushes = System.nanoTime();
        runLanes(start, budget);
        admit();
        if (tickCount % TICKET_REFRESH_TICKS == 0) {
            for (BulkJob job : active) job.refreshTickets();
            long now = System.nanoTime();
            predictedAt.values().removeIf(at -> now - at >= PREDICTION_GRACE_NANOS);
        }
        // Bulk jobs keep a minimum share even when the brush lane ran long (its first piece of a tick always runs).
        long bulkEnd = Math.max(start + budget, afterBrushes + (long) (budget * MIN_BULK_SHARE));
        runBulkLane(new Budget(bulkEnd, settings.maxBlocksPerTick() > 0 ? settings.maxBlocksPerTick() : Long.MAX_VALUE));
        long now = System.nanoTime();
        for (BulkJob job : active.toArray(new BulkJob[0])) emitProgress(job, now);
    }

    /**
     * Server stop: drops queued dabs, finishes each running job's current section, then ends every job as
     * {@code CANCELLED}. Work already applied (and recorded) is kept.
     */
    public void shutdown() {
        if (shutDown) return;
        shutDown = true;
        while (!brushTurns.isEmpty()) {
            ArrayDeque<BrushWork> queue = brushQueues.remove(brushTurns.pollFirst());
            while (queue != null && !queue.isEmpty()) {
                brushQueued--;
                dropped(queue.pollFirst());
            }
        }
        for (BulkJob job : active.toArray(new BulkJob[0])) {
            try {
                job.completeCurrentSection();
            } catch (RuntimeException e) {
                LOG.error("Sculptory job {} failed while finishing its section at shutdown", job.id, e);
            }
            finish(job, JobOutcome.CANCELLED);
        }
        for (BulkJob job : waiting.toArray(new BulkJob[0])) finish(job, JobOutcome.CANCELLED);
        flushClientSync(true);
    }

    /**
     * Serves the owners in turn, one {@link BrushWork#runPart} of their head item each, until {@code deadline}. The
     * tick's first turn always runs; a later one only when its item's next piece is predicted to end by the deadline
     * ({@link BrushWork#nextPieceNanos}), else that owner goes first next tick. A finished item leaves its queue (by
     * identity: work it queued at the head meanwhile stays); an unfinished one stays first.
     */
    private void runBrushLane(long deadline) {
        boolean first = true;
        while (brushQueued > 0) {
            long now = System.nanoTime();
            if (!first && now >= deadline) break;
            Object key = brushTurns.pollFirst();
            ArrayDeque<BrushWork> queue = brushQueues.get(key);
            BrushWork work = queue.peekFirst();
            if (!first && now + predicted(work) > deadline) {
                brushTurns.addFirst(key);
                break;
            }
            first = false;
            boolean done;
            try {
                done = work.runPart(deadline);
            } catch (RuntimeException e) {
                LOG.error("Sculptory brush work failed", e);
                done = true;
            }
            if (done && queue.removeFirstOccurrence(work)) brushQueued--;
            if (queue.isEmpty()) {
                brushQueues.remove(key);
            } else {
                brushTurns.addLast(key);
            }
        }
    }

    private static long predicted(BrushWork work) {
        try {
            return Math.max(0, work.nextPieceNanos());
        } catch (RuntimeException e) {
            LOG.error("Sculptory brush work could not predict its cost", e);
            return 0;
        }
    }

    /** Starts waiting jobs, in admission order, that hold all their locks and fit the active limits. */
    private void admit() {
        for (BulkJob job : waiting.toArray(new BulkJob[0])) {
            if (job.isDone()) continue;
            boolean startable = job.blocked == 0 && job.heldBy == 0
                    && active.size() < settings.maxActiveGlobal()
                    && activeCount(job.owner()) < settings.maxActivePerOwner();
            if (!startable) {
                if (job.reportedPhase == null) emitPhase(job, Phase.QUEUED);
                continue;
            }
            waiting.remove(job);
            active.add(job);
            try {
                job.activate(ticketsFor(job.request.world()), job.admittedMaxTickets);
            } catch (RuntimeException e) {
                fail(job, e);
            }
        }
    }

    private void runBulkLane(Budget budget) {
        boolean progressed = true;
        while (progressed && !budget.exhausted() && !active.isEmpty()) {
            progressed = false;
            BulkJob[] round = active.toArray(new BulkJob[0]);
            int n = round.length;
            int startAt = Math.floorMod(roundRobin, n);
            for (int k = 0; k < n && !budget.exhausted(); k++) {
                BulkJob job = round[(startAt + k) % n];
                if (job.isDone()) continue;
                job.deficit += QUANTUM;
                long used;
                try {
                    used = job.run(job.deficit, budget);
                } catch (RuntimeException e) {
                    fail(job, e);
                    continue;
                }
                if (used > 0) {
                    progressed = true;
                    job.deficit = Math.max(0, job.deficit - used);
                } else {
                    job.deficit = 0;
                }
                if (job.isDone()) job.deficit = 0;
            }
        }
        roundRobin++;
    }

    /** Ends a job exactly once: releases tickets and locks, then reports FINALIZE and the result. */
    void finish(BulkJob job, JobOutcome outcome) {
        if (job.isDone()) return;
        job.stage = BulkJob.Stage.DONE;
        job.outcome = outcome;
        active.remove(job);
        waiting.remove(job);
        jobs.remove(job.id);
        admittedUnloaded.remove(job.id);
        for (AreaHold hold : holds) hold.jobFinished(job);
        job.releaseTickets();
        job.releaseBuffers();
        locks.release(job.request.world().getRegistryKey(), job.lockKeys, job, head -> head.blocked--);
        if (job.writer.tileFailures() > 0) {
            LOG.warn("Sculptory job {} ({}): {} block entities could not be restored and kept their defaults; "
                    + "first: {}", job.id, job.program.label(), job.writer.tileFailures(), job.writer.firstTileFailure());
        }
        // Its columns go out at this tick's end whatever the resend interval (as with vanilla's block updates, a
        // few may arrive just after the job's result).
        ClientSync sync = clientSyncs.get(job.request.world());
        if (sync != null) sync.flushNext(job.order);
        if (job.entityFailures() > 0) {
            LOG.warn("Sculptory job {} ({}): {} entities could not be placed and were left out", job.id,
                    job.program.label(), job.entityFailures());
        }
        emitPhase(job, Phase.FINALIZE);
        // Entities count with the cells: changed, left alone as protected, kept as changed since (undo), stripped.
        JobResult result = new JobResult(job.id, outcome, job.changed(), job.skippedProtected + job.entitiesProtected(),
                job.entityConflicts(), job.writer.strippedNbt() + job.entitiesStripped());
        try {
            job.request.listener().finished(result);
        } catch (RuntimeException e) {
            LOG.error("Sculptory job listener failed in finished()", e);
        }
    }

    void emitPhase(BulkJob job, Phase phase) {
        job.reportedPhase = phase;
        long now = System.nanoTime();
        long done = job.done();
        job.throttle.emitted(now, done);
        progress(job, done, phase);
    }

    private void emitProgress(BulkJob job, long now) {
        if (job.isDone() || job.reportedPhase == null) return;
        long done = job.done();
        if (!job.throttle.shouldEmit(now, done, job.totalCells)) return;
        job.throttle.emitted(now, done);
        progress(job, done, job.reportedPhase);
    }

    private void progress(BulkJob job, long done, Phase phase) {
        try {
            job.request.listener().progress(job.id, done, job.totalCells, phase);
        } catch (RuntimeException e) {
            LOG.error("Sculptory job listener failed in progress()", e);
        }
    }

    private void fail(BulkJob job, RuntimeException e) {
        LOG.error("Sculptory job {} ({}) failed after {} changed cells; applied work is kept",
                job.id, job.program.label(), job.changed(), e);
        finish(job, JobOutcome.FAILED);
    }

    private int activeCount(UUID owner) {
        int count = 0;
        for (BulkJob job : active) {
            if (job.owner().equals(owner)) count++;
        }
        return count;
    }

    /** The owner's admitted jobs that have not started yet. */
    public int waitingCount(UUID owner) {
        int count = 0;
        for (BulkJob job : waiting) {
            if (job.owner().equals(owner)) count++;
        }
        return count;
    }

    /**
     * The owner's waiting jobs that will not start on the next tick: those held by locks or area holds, and those
     * beyond the active slots left to the owner (and to everyone). What the per-owner wait cap counts.
     */
    int heldBackCount(UUID owner) {
        int free = Math.min(settings.maxActivePerOwner() - activeCount(owner), settings.maxActiveGlobal() - active.size());
        int count = 0;
        for (BulkJob job : waiting) {
            if (job.isDone() || !job.owner().equals(owner)) continue;
            if (free > 0 && job.blocked == 0 && job.heldBy == 0) {
                free--; // starts on the next tick
            } else {
                count++;
            }
        }
        return count;
    }

    private TicketWindow.Tickets ticketsFor(ServerWorld world) {
        return tickets.computeIfAbsent(world, w -> {
            ServerChunkManager manager = w.getChunkManager();
            return new CountedTickets(new TicketWindow.Tickets() {
                @Override
                public void add(int cx, int cz) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    manager.addTicket(EDIT_TICKET, pos, 0, pos);
                }

                @Override
                public void remove(int cx, int cz) {
                    ChunkPos pos = new ChunkPos(cx, cz);
                    manager.removeTicket(EDIT_TICKET, pos, 0, pos);
                }

                /** Adding an equal ticket again restarts its expiry timer. */
                @Override
                public void refresh(int cx, int cz) {
                    add(cx, cz);
                }
            });
        });
    }

    /**
     * Splits section keys by the world border: a section is kept when some column of its chunk lies inside the
     * border (bounds as {@code WorldBorder.getBound*}). Order is preserved.
     */
    static BorderSplit splitByBorder(long[] sectionKeys, double west, double east, double north, double south) {
        LongArrayList inside = new LongArrayList(sectionKeys.length);
        LongArrayList outside = new LongArrayList();
        for (long key : sectionKeys) {
            int x0 = BlockBuffer.keyX(key) << 4, z0 = BlockBuffer.keyZ(key) << 4;
            boolean touches = x0 + 16 > west && x0 < east && z0 + 16 > north && z0 < south;
            (touches ? inside : outside).add(key);
        }
        return new BorderSplit(inside.toLongArray(), outside.toLongArray());
    }

    static int distinctColumns(long[] a, long[] b) {
        return distinctColumns(a, b, new long[0]);
    }

    /** Distinct columns of two sets of section keys and of packed {@code columns} ({@link ColumnPlan#pack}). */
    static int distinctColumns(long[] a, long[] b, long[] columns) {
        LongOpenHashSet out = new LongOpenHashSet();
        for (long key : a) out.add(ColumnPlan.pack(BlockBuffer.keyX(key), BlockBuffer.keyZ(key)));
        for (long key : b) out.add(ColumnPlan.pack(BlockBuffer.keyX(key), BlockBuffer.keyZ(key)));
        for (long column : columns) out.add(column);
        return out.size();
    }

    /**
     * Whether every packed column's chunk is loaded (its entities, which load a tick or more later, are waited for by the
     * job itself).
     */
    private static boolean allEntityColumnsLoaded(ServerWorld world, long[] columns) {
        for (long column : columns) {
            if (!WorldChecks.isChunkLoaded(world, ColumnPlan.unpackX(column), ColumnPlan.unpackZ(column))) return false;
        }
        return true;
    }

    private static boolean allColumnsLoaded(ServerWorld world, long[] sectionKeys) {
        int minSection = world.getBottomSectionCoord();
        int topSection = world.getTopSectionCoord();
        LongOpenHashSet checked = new LongOpenHashSet();
        for (long key : sectionKeys) {
            int sy = BlockBuffer.keyY(key);
            if (sy < minSection || sy >= topSection) continue;
            int cx = BlockBuffer.keyX(key), cz = BlockBuffer.keyZ(key);
            if (checked.add(ColumnPlan.pack(cx, cz)) && !WorldChecks.isChunkLoaded(world, cx, cz)) return false;
        }
        return true;
    }

    private static void dropped(BrushWork work) {
        try {
            work.dropped();
        } catch (RuntimeException e) {
            LOG.error("Sculptory brush work failed in dropped()", e);
        }
    }

    private void checkThread() {
        if (!server.isOnThread()) throw new IllegalStateException("EditExecutor must be used on the server thread");
    }
}
