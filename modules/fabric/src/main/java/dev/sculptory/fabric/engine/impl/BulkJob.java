package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.EntityWriter;
import dev.sculptory.fabric.world.FabricEntities;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.fabric.world.Relighter;
import dev.sculptory.fabric.world.EntityTypeRules;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.impl.ColumnPlan;
import dev.sculptory.server.engine.impl.ProgressThrottle;
import dev.sculptory.server.engine.impl.TicketWindow;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Arrays;
import java.util.UUID;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.border.WorldBorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One bulk job's state and its resumable work loop. Owned by {@link EditExecutor}; server thread only.
 *
 * <p>Work happens section by section: capture the section's current content, {@code compute} the writes, then
 * write them cell by cell. A section may be written across several ticks (the budget is checked every 256
 * cells), but cancellation only takes effect between sections. The capture only feeds {@code compute}: every
 * write re-reads the live cell, so a cell changed by someone else while the section is paused is recorded with
 * its real prior content, and the program's {@link EditProgram#mayReplace} is asked about that live content (state and
 * block entity) first (a refused cell is neither written nor recorded).
 *
 * <p>Before computing a section the job also waits for the other chunks the program reads
 * ({@link EditProgram#readColumns}; those inside the world border that the player may modify at least in part),
 * holding a ticket on each one not loaded (and not in its ticket window) until the next section shows it needs it no
 * more ({@link ReadTickets}). The job holds at most {@code maxTicketsPerJob} such tickets at any moment, so at most
 * twice that many in all: a section that would need more takes what room there is, in order, and is decided without
 * the rest; the placements reaching those are skipped and reported.
 *
 * <p><b>Entities</b>. A job with {@link JobRequest#entities() entity work} does its
 * part before the blocks first ({@link Stage#ENTITIES_BEFORE}) and its part after them last
 * ({@link Stage#ENTITIES_AFTER}), a chunk column at a time: each column is ticketed like the job's sections (at most
 * {@code maxTickets} ahead) and waited for until its chunk is loaded with its entities. A column's work runs in steps of
 * at most {@link EntityWork#ENTITIES_PER_STEP} entities, with the tick's time budget checked and its block cap charged
 * ({@link #ENTITY_UNITS} per entity) between steps. Cancelling takes effect between steps as between sections: a move
 * cancelled after taking its entities leaves them removed (and recorded) until it is undone.
 */
final class BulkJob {
    enum Stage { WAITING, ENTITIES_BEFORE, SOURCES, APPLY, ENTITIES_AFTER, DONE }

    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    private static final long NOT_WAITING = Long.MIN_VALUE;
    /** Work units (and blocks of the tick's block cap) an entity handled counts for: it costs about this many cells. */
    private static final long ENTITY_UNITS = 64;

    final EditExecutor executor;
    final UUID id;
    final JobRequest request;
    final EditProgram program;
    final long[] order;
    /** Source sections read from the world (inside the world border). */
    final long[] sources;
    /** Source sections outside the world border: snapshotted as air, never loaded. */
    final long[] airSources;
    /** Distinct, sorted union of {@link #order} and {@link #sources}. */
    final long[] lockKeys;
    final long totalCells;
    final FabricWorldReader reader;
    final BlockWriter writer;
    final ProgressThrottle throttle = new ProgressThrottle();
    private final ComputeContext context;
    private final int minSection;
    private final int topSection;
    private final int air;
    /** Asks the program about each cell's live content; {@link #guardKey}/{@link #guardIndex} name the cell. */
    private final BlockWriter.Guard guard;
    private long guardKey;
    private int guardIndex;

    Stage stage = Stage.WAITING;
    /** The last phase reported to the listener, or {@code null}. */
    Phase reportedPhase;
    /** Keys whose lock queue another job still heads; runnable at 0. */
    int blocked;
    /** Area holds made before this job over its sections and not yet released; runnable at 0. */
    int heldBy;
    /** Chunks it may keep loaded at a time: {@code executor.maxChunkTicketsPerJob} when it was admitted. */
    int admittedMaxTickets = 1;
    long deficit;
    boolean cancelRequested;
    JobOutcome outcome;

    private Long2ObjectOpenHashMap<SectionBuffer> snapshots;
    private TicketWindow.Tickets tickets;
    private ColumnPlan sourcePlan;
    private TicketWindow sourceTickets;
    private int sourceCursor;
    private ColumnPlan applyPlan;
    private TicketWindow applyTickets;
    private int sectionCursor;
    private SectionWork current;
    private long permitColumn = Long.MIN_VALUE;
    private ChunkPermit permit;
    /** Permits asked through {@link ComputeContext#mayWrite} during one slice (a decision may span chunks). */
    private final Long2ObjectOpenHashMap<ChunkPermit> decisionPermits = new Long2ObjectOpenHashMap<>();
    private long waitingSince = NOT_WAITING;
    /** The section index whose read columns ({@link #reads}) are known, or -1. */
    private int readColumnsFor = -1;
    /** Tickets on the chunks the decisions of a section read, at most the ticket window's size; set on activation. */
    private ReadTickets reads;
    private final ReadTickets.Chunks chunks = new ReadTickets.Chunks() {
        @Override
        public boolean loaded(long column) {
            return reader.chunkOrNull(EditProgram.columnX(column), EditProgram.columnZ(column)) != null;
        }

        @Override
        public boolean windowHolds(long column) {
            return applyTickets.holds(column);
        }
    };
    private boolean loggedCappedOut;
    /** The tick budget a section was computed under last ({@link #prepare}), and a running mean of that cost. */
    private EditExecutor.Budget preparedIn;
    private long prepareNanos;
    /** Light checks the section just finished queued ({@link Relighter}), charged to the budget. */
    private int relit;
    /** The job's entity work and what it counts, or {@code null}. */
    private final EntityWork entityWork;
    private EntityWork.Context entities;
    private ColumnPlan entityPlan;
    private TicketWindow entityTickets;
    private int entityCursor;
    private int maxTickets;

    long skippedProtected;

    BulkJob(EditExecutor executor, UUID id, JobRequest request, long[] order, long[] sources, long[] airSources,
            StateSpace states) {
        this.executor = executor;
        this.id = id;
        this.request = request;
        this.program = request.program();
        this.order = order;
        this.sources = sources;
        this.airSources = airSources;
        this.lockKeys = union(order, sources);
        this.totalCells = Math.max(0L, program.estimatedCells());
        ServerWorld world = request.world();
        this.reader = new FabricWorldReader(world, executor.states());
        // Physics-off writes reach players through the executor's per-world client sync (whole columns when heavily
        // changed), flushed at the end of each tick and when the job ends.
        this.writer = new BlockWriter(world, executor.states(), request.writeOptions())
                .syncThrough(executor.clientSync(world));
        this.minSection = world.getBottomSectionCoord();
        this.topSection = world.getTopSectionCoord();
        this.air = states.air();
        this.context = new ComputeContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public long seed() {
                return request.seed();
            }

            @Override
            public SectionBuffer source(long key) {
                return snapshots == null ? null : snapshots.get(key);
            }

            /** The job's own reader: never loads chunks, invalidated at the start of every slice. */
            @Override
            public WorldReader world() {
                return reader;
            }

            /** The same rule {@link #writeCell} applies: the player's chunk permit and the world border. */
            @Override
            public boolean mayWrite(int x, int z) {
                return decisionPermit(x >> 4, z >> 4).allows(x, z)
                        && WorldChecks.insideBorder(request.world().getWorldBorder(), x, z);
            }
        };
        this.guard = (live, liveTile) -> program.mayReplace(guardKey, guardIndex, live, liveTile, context);
        this.entityWork = request.entities();
    }

    UUID owner() {
        return request.owner();
    }

    boolean isDone() {
        return stage == Stage.DONE;
    }

    /** Cells written and recorded so far, and entities placed, put back or removed. */
    long changed() {
        return writer.changed() + (entities == null ? 0 : entities.changed());
    }

    /** Entities left alone because their block was protected. */
    long entitiesProtected() {
        return entities == null ? 0 : entities.protectedEntities;
    }

    /** Entities kept because they changed since the step (undo, redo). */
    long entityConflicts() {
        return entities == null ? 0 : entities.conflicts;
    }

    /** Entities whose operator-only data was left out. */
    long entitiesStripped() {
        return entities == null ? 0 : entities.writer.stripped();
    }

    /** Entities that could not be placed. */
    long entityFailures() {
        return entities == null ? 0 : entities.writer.failures();
    }

    /** Called once when the job gets an active slot. */
    void activate(TicketWindow.Tickets tickets, int maxTickets) {
        this.tickets = tickets;
        this.maxTickets = maxTickets;
        this.reads = new ReadTickets(tickets, maxTickets);
        applyPlan = ColumnPlan.of(order);
        applyTickets = new TicketWindow(applyPlan, tickets, maxTickets);
        if (sources.length > 0 || airSources.length > 0) {
            snapshots = new Long2ObjectOpenHashMap<>(sources.length + airSources.length);
            for (long key : airSources) snapshots.put(key, SectionBuffer.uniform(air));
        }
        if (entityWork != null) {
            ServerWorld world = request.world();
            entities = new EntityWork.Context(world, new EntityWriter(world, request.writeOptions(),
                    EntityTypeRules.scan(world)), request.permits(), request.records());
            if (beginEntities(entityWork.beforeColumns())) {
                stage = Stage.ENTITIES_BEFORE;
                return;
            }
        }
        beginSources();
    }

    /** The SOURCES stage, or APPLY when the program reads no sources. */
    private void beginSources() {
        if (sources.length > 0) {
            sourcePlan = ColumnPlan.of(sources);
            sourceTickets = new TicketWindow(sourcePlan, tickets, maxTickets);
            sourceTickets.advance(0);
            stage = Stage.SOURCES;
        } else {
            applyTickets.advance(0);
            stage = Stage.APPLY;
        }
    }

    /** Plans a column-by-column entity stage over {@code columns}; false when there are none. */
    private boolean beginEntities(long[] columns) {
        if (entityTickets != null) entityTickets.releaseAll();
        entityTickets = null;
        entityCursor = 0;
        if (columns == null || columns.length == 0) {
            entityPlan = null;
            return false;
        }
        long[] keys = new long[columns.length];
        for (int i = 0; i < columns.length; i++) {
            keys[i] = BlockBuffer.key(ColumnPlan.unpackX(columns[i]), 0, ColumnPlan.unpackZ(columns[i]));
        }
        entityPlan = ColumnPlan.of(keys);
        entityTickets = new TicketWindow(entityPlan, tickets, maxTickets);
        entityTickets.advance(0);
        return true;
    }

    /**
     * One step of an entity stage: at most {@link EntityWork#ENTITIES_PER_STEP} entities of the current column, once its
     * chunk and entities are loaded; the column is left when its work is done.
     *
     * @return units used, or -1 when the job must wait for a chunk
     */
    private long entityStep(boolean before, EditExecutor.Budget budget) {
        long column = entityPlan.column(entityPlan.columnOfSection(entityCursor));
        int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
        if (!FabricEntities.loaded(request.world(), cx, cz)) {
            waitFor(cx, cz);
            return -1;
        }
        waitingSince = NOT_WAITING;
        report(Phase.APPLY);
        entities.allowance = EntityWork.ENTITIES_PER_STEP;
        boolean done = before ? entityWork.before(column, entities) : entityWork.after(column, entities);
        long handled = EntityWork.ENTITIES_PER_STEP - entities.allowance;
        // Each entity counts against the tick's block cap ({@code maxBlocksPerTick}) as {@value #ENTITY_UNITS} blocks.
        budget.cellsLeft -= handled * ENTITY_UNITS;
        request.records().entitiesFinished();
        if (done) entityTickets.advance(++entityCursor);
        return EditExecutor.SECTION_OVERHEAD_UNITS + handled * ENTITY_UNITS;
    }

    /**
     * Works until {@code maxUnits} units (one per cell visited, plus a fixed overhead per section) are used, the
     * budget runs out, or the job must wait for a chunk. May finish the job.
     *
     * @return units used
     */
    long run(long maxUnits, EditExecutor.Budget budget) {
        long used = 0;
        reader.invalidate();
        decisionPermits.clear();
        while (used < maxUnits && !budget.exhausted()) {
            if (current == null && cancelRequested) {
                executor.finish(this, JobOutcome.CANCELLED);
                return used;
            }
            if (stage == Stage.ENTITIES_BEFORE || stage == Stage.ENTITIES_AFTER) {
                boolean before = stage == Stage.ENTITIES_BEFORE;
                if (entityCursor >= entityPlan.sectionCount()) {
                    entityTickets.releaseAll();
                    if (before) {
                        beginSources();
                    } else {
                        executor.finish(this, JobOutcome.COMPLETED);
                        return used;
                    }
                    continue;
                }
                long step = entityStep(before, budget);
                if (step < 0) return used;
                used += step;
                budget.checkTime();
                continue;
            }
            if (stage == Stage.SOURCES) {
                if (sourceCursor >= sources.length) {
                    beginApply();
                    continue;
                }
                long key = sources[sourceCursor];
                int sy = BlockBuffer.keyY(key);
                boolean inWorld = sy >= minSection && sy < topSection;
                if (inWorld && !columnReady(sourcePlan, sourceCursor)) return used;
                waitingSince = NOT_WAITING;
                report(Phase.SNAPSHOT_SOURCES);
                SectionBuffer snapshot = new SectionBuffer();
                reader.copySection(BlockBuffer.keyX(key), sy, BlockBuffer.keyZ(key), snapshot);
                snapshots.put(key, snapshot);
                sourceTickets.advance(++sourceCursor);
                used += EditExecutor.SECTION_OVERHEAD_UNITS;
                budget.checkTime();
                continue;
            }
            if (current == null) {
                if (sectionCursor >= order.length) {
                    if (entityWork != null) {
                        applyTickets.releaseAll();
                        if (beginEntities(entityWork.afterColumns())) {
                            stage = Stage.ENTITIES_AFTER;
                            continue;
                        }
                    }
                    executor.finish(this, JobOutcome.COMPLETED);
                    return used;
                }
                long key = order[sectionCursor];
                int sy = BlockBuffer.keyY(key);
                if (sy < minSection || sy >= topSection) {
                    applyTickets.advance(++sectionCursor);
                    continue;
                }
                if (!columnReady(applyPlan, sectionCursor) || !readColumnsReady(key)) return used;
                waitingSince = NOT_WAITING;
                // A section is computed in one piece: once one was this tick, the next starts only if it should fit.
                if (preparedIn == budget && System.nanoTime() + prepareNanos > budget.deadline) break;
                report(Phase.APPLY);
                long start = System.nanoTime();
                current = prepare(key);
                long spent = System.nanoTime() - start;
                prepareNanos = prepareNanos == 0 ? spent : prepareNanos + (spent - prepareNanos) / 8;
                preparedIn = budget;
                used += EditExecutor.SECTION_OVERHEAD_UNITS;
                if (budget.checkTime()) break;
            }
            used += write(current, maxUnits - used, budget);
            if (current.done()) {
                finishSection();
                if (program.relightsAfter()) {
                    // A light check costs about what writing a cell does: charged so, they spread over ticks.
                    used += relit;
                    budget.cellsLeft -= relit;
                    relit = 0;
                    budget.checkTime();
                }
                current = null;
                applyTickets.advance(++sectionCursor); // read tickets carry over to the next section (ReadTickets.begin)
            }
        }
        return used;
    }

    /** Finishes a partly written section without a budget (server stop). */
    void completeCurrentSection() {
        if (current == null) return;
        while (!current.done()) writeCell(current, current.indices[current.pos++]);
        finishSection();
        current = null;
        sectionCursor++;
    }

    /** Progress in units of {@link #totalCells}, by sections written. */
    long done() {
        if (outcome == JobOutcome.COMPLETED || stage == Stage.ENTITIES_AFTER) return totalCells;
        if (stage == Stage.WAITING || stage == Stage.ENTITIES_BEFORE || stage == Stage.SOURCES || order.length == 0) {
            return 0;
        }
        double sections = sectionCursor + (current == null ? 0 : current.fraction());
        return Math.min(totalCells, (long) (totalCells * (sections / order.length)));
    }

    void refreshTickets() {
        if (sourceTickets != null) sourceTickets.refresh();
        if (applyTickets != null) applyTickets.refresh();
        if (reads != null) reads.refresh();
        if (entityTickets != null) entityTickets.refresh();
    }

    void releaseTickets() {
        if (sourceTickets != null) sourceTickets.releaseAll();
        if (applyTickets != null) applyTickets.releaseAll();
        if (reads != null) reads.releaseAll();
        if (entityTickets != null) entityTickets.releaseAll();
    }

    /** Drops the source snapshots and the section in progress (the job has ended). */
    void releaseBuffers() {
        snapshots = null;
        current = null;
    }

    private void beginApply() {
        sourceTickets.releaseAll();
        applyTickets.advance(0);
        stage = Stage.APPLY;
    }

    private void report(Phase phase) {
        if (reportedPhase != phase) executor.emitPhase(this, phase);
    }

    /** True when the chunk of section {@code s} is loaded; otherwise waits for it ({@link #waitFor}). */
    private boolean columnReady(ColumnPlan plan, int s) {
        long column = plan.column(plan.columnOfSection(s));
        int cx = ColumnPlan.unpackX(column), cz = ColumnPlan.unpackZ(column);
        if (reader.chunkOrNull(cx, cz) != null) return true;
        waitFor(cx, cz);
        return false;
    }

    /**
     * True when every chunk the program reads to compute section {@code key} is loaded, among those worth loading
     * ({@link #worthLoading}) and kept by {@link ReadTickets} (at most the ticket window's size of extra tickets at any
     * moment; columns beyond that are dropped, the section is decided with what is loaded, and the placements reaching
     * them are skipped and reported as conflicts). Otherwise waits for one.
     */
    private boolean readColumnsReady(long key) {
        if (readColumnsFor != sectionCursor) {
            reads.begin(worthLoading(program.readColumns(key)), chunks);
            readColumnsFor = sectionCursor;
        }
        boolean ready = reads.ready(chunks);
        if (reads.cappedOut() && !loggedCappedOut) {
            loggedCappedOut = true;
            LOG.info("Sculptory job {} ({}): a section's placements needed more chunks than its {} extra tickets; "
                    + "placements reaching the chunks not loaded are skipped", id, program.label(), reads.max());
        }
        if (!ready) waitFor(EditProgram.columnX(reads.missing()), EditProgram.columnZ(reads.missing()));
        return ready;
    }

    /**
     * The columns worth loading for a decision: those whose chunk lies at least partly inside the world border (the
     * others are never loaded) and that the player may modify at least in part (a placement reaching a denied column
     * is skipped whether or not it is loaded).
     */
    private long[] worthLoading(long[] columns) {
        if (columns.length == 0) return columns;
        WorldBorder border = request.world().getWorldBorder();
        double west = border.getBoundWest(), east = border.getBoundEast();
        double north = border.getBoundNorth(), south = border.getBoundSouth();
        LongArrayList kept = new LongArrayList(columns.length);
        for (long column : columns) {
            int cx = EditProgram.columnX(column), cz = EditProgram.columnZ(column);
            int x0 = cx << 4, z0 = cz << 4;
            if (!(x0 + 16 > west && x0 < east && z0 + 16 > north && z0 < south)) continue;
            if (decisionPermit(cx, cz) instanceof ChunkPermit.Deny) continue;
            kept.add(column);
        }
        return kept.toLongArray();
    }

    /**
     * Times a wait for chunk (cx, cz) and reports LOAD_CHUNKS once; the caller resets the timer when it can go on.
     * The limit is wall-clock time, not ticks: the GameTest server and {@code /tick sprint} run ticks faster than
     * chunks generate.
     */
    private void waitFor(int cx, int cz) {
        long now = System.nanoTime();
        if (waitingSince == NOT_WAITING) {
            waitingSince = now;
        } else if (now - waitingSince > EditExecutor.CHUNK_WAIT_LIMIT_NANOS) {
            throw new IllegalStateException("Chunk " + cx + "," + cz + " did not load within "
                    + EditExecutor.CHUNK_WAIT_LIMIT_NANOS / 1_000_000_000L + " s");
        }
        if (reportedPhase == null || reportedPhase == Phase.QUEUED) executor.emitPhase(this, Phase.LOAD_CHUNKS);
    }

    private SectionWork prepare(long key) {
        int sx = BlockBuffer.keyX(key), sy = BlockBuffer.keyY(key), sz = BlockBuffer.keyZ(key);
        SectionBuffer before = new SectionBuffer();
        reader.copySection(sx, sy, sz, before);
        SectionBuffer out = new SectionBuffer();
        program.compute(key, before, out, context);
        int[] indices = new int[out.presentCount()];
        int[] n = {0};
        out.forEachPresent(i -> indices[n[0]++] = i);
        int ox = sx << 4, oz = sz << 4;
        WorldBorder border = request.world().getWorldBorder();
        boolean insideBorder = WorldChecks.insideBorder(border, ox, oz) && WorldChecks.insideBorder(border, ox + 15, oz)
                && WorldChecks.insideBorder(border, ox, oz + 15) && WorldChecks.insideBorder(border, ox + 15, oz + 15);
        return new SectionWork(key, ox, sy << 4, oz, out, indices, permitFor(sx, sz), insideBorder, border);
    }

    private ChunkPermit permitFor(int cx, int cz) {
        long column = ColumnPlan.pack(cx, cz);
        if (column != permitColumn) {
            ChunkPermit found = request.permits().chunk(cx, cz);
            permit = found == null ? ChunkPermit.DENY : found;
            permitColumn = column;
        }
        return permit;
    }

    /** A chunk's permit for {@link ComputeContext#mayWrite}, asked once per slice. */
    private ChunkPermit decisionPermit(int cx, int cz) {
        long column = ColumnPlan.pack(cx, cz);
        ChunkPermit found = decisionPermits.get(column);
        if (found == null) {
            found = request.permits().chunk(cx, cz);
            if (found == null) found = ChunkPermit.DENY;
            decisionPermits.put(column, found);
        }
        return found;
    }

    private long write(SectionWork s, long maxCells, EditExecutor.Budget budget) {
        long n = 0;
        while (s.pos < s.indices.length && n < maxCells && budget.cellsLeft > 0) {
            writeCell(s, s.indices[s.pos++]);
            n++;
            budget.cellsLeft--;
            if ((n & (EditExecutor.TIME_CHECK_CELLS - 1)) == 0 && budget.checkTime()) break;
        }
        return n;
    }

    private void writeCell(SectionWork s, int i) {
        int x = s.ox + SectionBuffer.localX(i);
        int y = s.oy + SectionBuffer.localY(i);
        int z = s.oz + SectionBuffer.localZ(i);
        if (!s.permit.allows(x, z) || (!s.insideBorder && !WorldChecks.insideBorder(s.border, x, z))) {
            skippedProtected++;
            return;
        }
        guardKey = s.key;
        guardIndex = i;
        writer.write(x, y, z, s.out.get(i), s.out.tile(i), request.records(), guard);
    }

    private void finishSection() {
        if (!request.writeOptions().physics()) writer.clearTicksAtWrittenCells();
        // Update blocks fixes the light of every section it covered, once the section is written.
        if (program.relightsAfter()) {
            long key = current.key;
            relit = Relighter.relightSection(request.world(), BlockBuffer.keyX(key), BlockBuffer.keyY(key),
                    BlockBuffer.keyZ(key));
        }
        // Lets the history record build this section's part now, inside the tick budget, not all at the job's end.
        request.records().sectionFinished(current.key);
    }

    private static long[] union(long[] a, long[] b) {
        LongOpenHashSet set = new LongOpenHashSet(a.length + b.length);
        for (long key : a) set.add(key);
        for (long key : b) set.add(key);
        long[] keys = set.toLongArray();
        Arrays.sort(keys);
        return keys;
    }

    /** One section being written. */
    static final class SectionWork {
        final long key;
        final int ox, oy, oz;
        final SectionBuffer out;
        final int[] indices;
        final ChunkPermit permit;
        final boolean insideBorder;
        final WorldBorder border;
        int pos;

        SectionWork(long key, int ox, int oy, int oz, SectionBuffer out, int[] indices, ChunkPermit permit,
                    boolean insideBorder, WorldBorder border) {
            this.key = key;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.out = out;
            this.indices = indices;
            this.permit = permit;
            this.insideBorder = insideBorder;
            this.border = border;
        }

        boolean done() {
            return pos >= indices.length;
        }

        double fraction() {
            return indices.length == 0 ? 1.0 : pos / (double) indices.length;
        }
    }
}
