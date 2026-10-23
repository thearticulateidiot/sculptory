package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CompileContext;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.edit.MultiPaste;
import dev.sculptory.core.edit.OpCompiler;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.PermissionService;
import dev.sculptory.server.perm.ChunkPermits;
import dev.sculptory.server.platform.BorderBounds;
import dev.sculptory.server.platform.ClientUpdates;
import dev.sculptory.server.platform.LiveReader;
import dev.sculptory.server.platform.WorldWriter;
import dev.sculptory.server.platform.WriteOptions;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Protection limited in height, driven through the executor's bulk jobs against a fake world: a chunk allowed below
 * y 10 and denied from y 10 up. Denied cells are skipped and counted cell by cell, the record holds exactly the cells
 * written, and an undo restores exactly those (under the same protection, or skipping and counting what became
 * protected since). Nested regions are honoured, and a scatter placement reaching a denied layer is skipped whole. The
 * JUnit runs set {@code sculptory.checkPermitBounds}, so every permit is asked only inside its bounds here.
 */
class HeightPermitJobTest {
    private static final Object WORLD = "world";
    /** Chunks (0, 0) and (1, 0), y 0..19: two sections tall. */
    private static final Box AREA = new Box(new BlockPos(0, 0, 0), new BlockPos(31, 19, 15));
    private static final long CHUNK_CELLS = 16 * 16;

    private final StateSpace states = new FakeStateSpace();
    private final int stone = states.parse("minecraft:stone");
    private final int dirt = states.parse("minecraft:dirt");
    private final FakeWorld world = new FakeWorld(states, -64, 320);

    /**
     * Protection as a 3D producer gives it, judged cell by cell ({@code ChunkPermits.rasterize}): chunk (0, 0) is
     * denied at y {@code deniedFrom} and above and below {@code allowedFrom}; chunk (1, 0) is free.
     */
    private record Region(int allowedFrom, int deniedFrom) implements PermissionService<Object, Object> {
        @Override
        public boolean has(Object p, Perm node) {
            return true;
        }

        @Override
        public ChunkPermit chunk(Object p, Object w, int cx, int cz, Box bounds) {
            return ChunkPermits.rasterize(cx, cz, bounds, this::allows);
        }

        boolean allows(int x, int y, int z) {
            return x >= 16 || (y >= allowedFrom && y < deniedFrom);
        }
    }

    /** Any cell test as a player's protection, judged cell by cell. */
    private static PermissionService<Object, Object> exact(ChunkPermits.CellTest test) {
        return new PermissionService<>() {
            @Override
            public boolean has(Object p, Perm node) {
                return true;
            }

            @Override
            public ChunkPermit chunk(Object p, Object w, int cx, int cz, Box bounds) {
                return ChunkPermits.rasterize(cx, cz, bounds, test);
            }
        };
    }

    @Test
    void aFillWritesExactlyTheAllowedCellsAndItsUndoRestoresExactlyThose() throws EditRejected {
        world.fill(AREA, stone);
        EditExecutor<Object> executor = new EditExecutor<>(host(), settings());
        Region region = new Region(-64, 10);
        Fill fill = new Fill(AREA, dirt);
        RecordBuilder record = new RecordBuilder();
        JobResult filled = run(executor, JobRequest.system(WORLD, fill, null)
                .withPermits(PermitSource.forPlayer(region, null, WORLD, AREA)).withRecords(RecordSink.into(record)));
        assertEquals(JobOutcome.COMPLETED, filled.outcome());
        long denied = CHUNK_CELLS * 10; // chunk (0, 0), y 10..19
        assertEquals(denied, filled.skippedProtected(), "the denied cells, counted one by one");
        assertEquals(AREA.volume() - denied, filled.changed());
        assertTrue(fill.mayWriteBelow && !fill.mayWriteAbove, "a decision is answered at the cell's own height");
        EditRecord edit = record.build();
        assertEquals(AREA.volume() - denied, edit.before().cellCount(), "the record holds the written cells only");
        forEachCell(AREA, (x, y, z) -> {
            boolean allowed = region.allows(x, y, z);
            assertEquals(allowed ? dirt : stone, world.get(x, y, z), "after the fill at " + x + "," + y + "," + z);
            assertEquals(allowed, edit.before().has(x, y, z), "recorded at " + x + "," + y + "," + z);
        });

        HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "world", "Fill", edit, 0L);
        EditProgram undo = HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS);
        JobResult undone = run(executor, JobRequest.system(WORLD, undo, null)
                .withPermits(PermitSource.forPlayer(region, null, WORLD, undo.bounds())));
        assertEquals(JobOutcome.COMPLETED, undone.outcome());
        assertEquals(AREA.volume() - denied, undone.changed(), "every written cell is put back");
        assertEquals(0, undone.skippedProtected());
        assertEquals(0, undone.skippedConflicts());
        forEachCell(AREA, (x, y, z) -> assertEquals(stone, world.get(x, y, z), "after the undo at " + x + "," + y + "," + z));
    }

    @Test
    void anUndoUnderProtectionGrownSinceLeavesTheNewlyProtectedLayersAndCountsThem() throws EditRejected {
        world.fill(AREA, stone);
        EditExecutor<Object> executor = new EditExecutor<>(host(), settings());
        Region before = new Region(-64, 10);
        RecordBuilder record = new RecordBuilder();
        run(executor, JobRequest.system(WORLD, new Fill(AREA, dirt), null)
                .withPermits(PermitSource.forPlayer(before, null, WORLD, AREA)).withRecords(RecordSink.into(record)));
        EditRecord edit = record.build();

        // Since the fill, layers 0..4 of chunk (0, 0) became protected too: only y 5..9 stays open there.
        Region after = new Region(5, 10);
        HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "world", "Fill", edit, 0L);
        EditProgram undo = HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS);
        JobResult undone = run(executor, JobRequest.system(WORLD, undo, null)
                .withPermits(PermitSource.forPlayer(after, null, WORLD, undo.bounds())));
        assertEquals(JobOutcome.COMPLETED, undone.outcome());
        long newlyProtected = CHUNK_CELLS * 5;
        assertEquals(newlyProtected, undone.skippedProtected(), "y 0..4 of chunk (0, 0)");
        assertEquals(edit.before().cellCount() - newlyProtected, undone.changed());
        forEachCell(AREA, (x, y, z) -> {
            int expected = x < 16 && y < 5 ? dirt : stone;
            assertEquals(expected, world.get(x, y, z), "after the undo at " + x + "," + y + "," + z);
        });
    }

    /**
     * Nested regions with priorities (an outer region denying, an inner one allowing again), which corner sampling
     * reads as all allowed: the fill skips exactly the one-cell ring between them. The undo asks for the record's
     * bounds, smaller than the fill's, and judges every cell as the fill did: it puts back exactly what was written.
     */
    @Test
    void nestedRegionsAreHonouredAndTheUndoJudgesEveryCellAsTheFillDid() throws EditRejected {
        world.fill(AREA, stone);
        EditExecutor<Object> executor = new EditExecutor<>(host(), settings());
        ChunkPermits.CellTest nested = (x, y, z) -> {
            if (x >= 3 && x <= 12 && z >= 3 && z <= 12 && y >= 5 && y <= 15) return true; // R2, the higher priority
            return !(x >= 2 && x <= 13 && z >= 2 && z <= 13 && y >= 5 && y <= 15); // R1
        };
        PermissionService<Object, Object> protection = exact(nested);
        RecordBuilder record = new RecordBuilder();
        JobResult filled = run(executor, JobRequest.system(WORLD, new Fill(AREA, dirt), null)
                .withPermits(PermitSource.forPlayer(protection, null, WORLD, AREA)).withRecords(RecordSink.into(record)));
        long ring = (12L * 12 - 10 * 10) * 11;
        assertEquals(ring, filled.skippedProtected());
        assertEquals(AREA.volume() - ring, filled.changed());
        forEachCell(AREA, (x, y, z) -> assertEquals(nested.allowed(x, y, z) ? dirt : stone, world.get(x, y, z),
                "after the fill at " + x + "," + y + "," + z));

        EditRecord edit = record.build();
        HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(), "world", "Fill", edit, 0L);
        EditProgram undo = HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS);
        JobResult undone = run(executor, JobRequest.system(WORLD, undo, null)
                .withPermits(PermitSource.forPlayer(protection, null, WORLD, undo.bounds())));
        assertEquals(AREA.volume() - ring, undone.changed());
        assertEquals(0, undone.skippedProtected(), "no written cell is judged protected by the undo");
        forEachCell(AREA, (x, y, z) -> assertEquals(stone, world.get(x, y, z), "after the undo at " + x + "," + y + "," + z));
    }

    /**
     * A scatter commit under protection limited in height: a placement reaching a denied layer is skipped whole (none
     * of its cells written), counted once as a skipped placement, never cell by cell; the others are written.
     */
    @Test
    void aScatterPlacementReachingADeniedLayerIsSkippedWhole() throws EditRejected {
        world.fill(new Box(new BlockPos(0, 0, 0), new BlockPos(31, 0, 15)), stone);
        EditExecutor<Object> executor = new EditExecutor<>(host(), settings());
        // Pillars three cells tall, standing on the floor (y 1..3). Chunk (0, 0) is denied from y 3 up: the two pillars
        // there reach it with their top cell.
        List<MultiPaste.Placement> placements = List.of(new MultiPaste.Placement(new BlockPos(4, 1, 4), 0,
                Transform.IDENTITY), new MultiPaste.Placement(new BlockPos(20, 1, 4), 0, Transform.IDENTITY),
                new MultiPaste.Placement(new BlockPos(9, 1, 11), 0, Transform.IDENTITY));
        ConflictCountingProgram commit = commit(new MultiPaste(List.of(pillar(3)), placements, MultiPaste.Replace.OPEN));
        Region region = new Region(-64, 3);
        JobResult result = run(executor, JobRequest.system(WORLD, commit, null)
                .withPermits(PermitSource.forPlayer(region, null, WORLD, commit.bounds())));
        assertEquals(JobOutcome.COMPLETED, result.outcome());
        assertEquals(3, result.changed(), "the pillar in the free chunk");
        assertEquals(0, result.skippedProtected(), "nothing denied cell by cell");
        assertEquals(2, commit.conflicts(), "each skipped placement counted once");
        for (int y = 1; y <= 3; y++) {
            assertEquals(dirt, world.get(20, y, 4), "the free pillar at y " + y);
            assertEquals(states.air(), world.get(4, y, 4), "nothing of a skipped pillar at y " + y);
            assertEquals(states.air(), world.get(9, y, 11), "nothing of a skipped pillar at y " + y);
        }
    }

    /**
     * A source whose permits were asked for less than the job writes (heights 0..9 of a fill up to 19): the first cell
     * above them fails the job loudly under the check the tests run with, rather than being judged by a permit that
     * says nothing about it. The check compares against the bounds the source asked for, not the program's.
     */
    @Test
    void aCellOutsideTheBoundsThePermitsWereAskedForFailsTheJob() throws EditRejected {
        world.fill(AREA, stone);
        EditExecutor<Object> executor = new EditExecutor<>(host(), settings());
        Box lower = new Box(AREA.min(), new BlockPos(31, 9, 15));
        JobResult result = run(executor, JobRequest.system(WORLD, new Fill(AREA, dirt), null)
                .withPermits(PermitSource.forPlayer(new Region(-64, 5), null, WORLD, lower)));
        assertEquals(JobOutcome.FAILED, result.outcome());
    }

    /**
     * Protection grown between two slices of a scatter commit: a placement decided while its cells were all open, and
     * not started yet, is checked again right before its first write, against protection as it is then (the decision
     * permits are asked afresh each slice), so it is skipped whole and counted once, never half built with its denied
     * cells counted one by one. Its section is written one cell per tick, so decision and first write fall in
     * different slices.
     */
    @Test
    void protectionGrownBetweenSlicesSkipsAPlacementWholeBeforeItStarts() throws EditRejected {
        world.fill(new Box(new BlockPos(0, 0, 0), new BlockPos(31, 0, 15)), stone);
        EditExecutor<Object> executor = new EditExecutor<>(host(), settings(1));
        // In one section: a single block (index 324, written first) and a pillar of three (first index 441).
        List<MultiPaste.Placement> placements = List.of(
                new MultiPaste.Placement(new BlockPos(4, 1, 4), 0, Transform.IDENTITY),
                new MultiPaste.Placement(new BlockPos(9, 1, 11), 1, Transform.IDENTITY));
        ConflictCountingProgram commit = commit(new MultiPaste(List.of(pillar(1), pillar(3)), placements,
                MultiPaste.Replace.OPEN));
        int[] deniedFrom = {Integer.MAX_VALUE};
        PermitSource permits = PermitSource.forPlayer(exact((x, y, z) -> y < deniedFrom[0]), null, WORLD,
                commit.bounds());
        JobResult[] result = {null};
        executor.submit(new JobRequest<>(JobRequest.SYSTEM_OWNER, WORLD, commit, WriteOptions.DEFAULT,
                permits, true, 0L, listener(result), RecordSink.NONE));
        for (int tick = 0; tick < 100 && world.get(4, 1, 4) != dirt; tick++) executor.tick();
        assertEquals(dirt, world.get(4, 1, 4), "the single block, written first");
        assertEquals(states.air(), world.get(9, 1, 11), "the pillar not started yet");

        deniedFrom[0] = 3; // the pillar's top cell becomes protected
        for (int tick = 0; tick < 100 && result[0] == null; tick++) executor.tick();
        assertNotNull(result[0], "the commit did not finish");
        assertEquals(JobOutcome.COMPLETED, result[0].outcome());
        for (int y = 1; y <= 3; y++) assertEquals(states.air(), world.get(9, y, 11), "nothing of the pillar at y " + y);
        assertEquals(1, commit.conflicts(), "the pillar skipped, counted once");
        assertEquals(0, result[0].skippedProtected(), "no cell of it denied one by one");
        assertEquals(1, result[0].changed());
    }

    // ---------------------------------------------------------------- harness

    @FunctionalInterface
    private interface CellVisitor {
        void visit(int x, int y, int z);
    }

    private static void forEachCell(Box box, CellVisitor visitor) {
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) visitor.visit(x, y, z);
            }
        }
    }

    /** Submits {@code request} (its listener replaced) and ticks until it finishes. */
    private static JobResult run(EditExecutor<Object> executor, JobRequest<Object> request) throws EditRejected {
        JobResult[] result = {null};
        executor.submit(new JobRequest<>(request.owner(), request.world(), request.program(), request.writeOptions(),
                request.permits(), request.mayLoadChunks(), request.seed(), listener(result), request.records()));
        for (int tick = 0; tick < 1000 && result[0] == null; tick++) {
            executor.tick();
            executor.endTick();
        }
        assertNotNull(result[0], "the job did not finish");
        return result[0];
    }

    /** A listener keeping the job's result in {@code result[0]}. */
    private static JobListener listener(JobResult[] result) {
        return new JobListener() {
            @Override
            public void progress(UUID job, long done, long total, Phase ph) {}

            @Override
            public void finished(JobResult r) {
                result[0] = r;
            }
        };
    }

    private static EditExecutor.Settings settings() {
        return settings(1_000_000);
    }

    /** At most {@code maxBlocksPerTick} cells written per tick (a section then spans several slices). */
    private static EditExecutor.Settings settings(long maxBlocksPerTick) {
        return new EditExecutor.Settings(1_000_000_000L, maxBlocksPerTick, 0.4, 2, 8, 32, 64, UnloadedPolicy.REFUSE, 64,
                1024);
    }

    /** A scatter commit of {@code plan} (each skipped or cut-short placement counted once in its conflicts). */
    private ConflictCountingProgram commit(MultiPaste plan) {
        UUID planId = UUID.randomUUID();
        return new ConflictCountingProgram(OpCompiler.compile(new OpSpec.ScatterCommit(planId), new CompileContext() {
            @Override
            public StateSpace states() {
                return states;
            }

            @Override
            public Optional<SourceBlocks> source(SourceRef ref) {
                return Optional.empty();
            }

            @Override
            public Optional<MultiPaste> scatterPlan(UUID id) {
                return id.equals(planId) ? Optional.of(plan) : Optional.empty();
            }
        }));
    }

    /** A column of {@code height} dirt cells, anchored at its foot. */
    private SourceBlocks pillar(int height) {
        BlockBuffer cells = new BlockBuffer();
        for (int y = 0; y < height; y++) cells.set(0, y, 0, dirt);
        return new SourceBlocks(cells, new BlockPos(1, height, 1), BlockPos.ORIGIN);
    }

    /** The platform the executor needs for blocks only, over {@link #world}; anything else is not supported. */
    @SuppressWarnings("unchecked")
    private EngineHost<Object, Object> host() {
        LiveReader reader = new LiveReader() {
            @Override
            public StateSpace states() {
                return world.states();
            }

            @Override
            public int bottomY() {
                return world.bottomY();
            }

            @Override
            public int topYExclusive() {
                return world.topYExclusive();
            }

            @Override
            public boolean isLoaded(int cx, int cz) {
                return world.isLoaded(cx, cz);
            }

            @Override
            public int get(int x, int y, int z) {
                return world.get(x, y, z);
            }

            @Override
            public BlockEntityData tile(int x, int y, int z) {
                return world.tile(x, y, z);
            }

            @Override
            public void copySection(int sx, int sy, int sz, SectionBuffer into) {
                world.copySection(sx, sy, sz, into);
            }

            @Override
            public void invalidate() {}
        };
        ClientUpdates noUpdates = new ClientUpdates() {
            @Override
            public void flush(long tick, boolean force) {}

            @Override
            public void flushNext(long[] sectionKeys) {}
        };
        TicketWindow.Tickets noTickets = new TicketWindow.Tickets() {
            @Override
            public void add(int cx, int cz) {}

            @Override
            public void remove(int cx, int cz) {}

            @Override
            public void refresh(int cx, int cz) {}
        };
        return (EngineHost<Object, Object>) Proxy.newProxyInstance(EngineHost.class.getClassLoader(),
                new Class<?>[] {EngineHost.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isOnThread", "exists" -> true;
                    case "states" -> states;
                    case "worldKey" -> args[0];
                    case "bottomY" -> world.bottomY();
                    case "topY" -> world.topYExclusive();
                    case "bottomSection" -> world.bottomY() >> 4;
                    case "topSection" -> world.topYExclusive() >> 4;
                    case "intersectsBuildLimit" -> {
                        Box box = (Box) args[1];
                        yield box.max().y() >= world.bottomY() && box.min().y() < world.topYExclusive();
                    }
                    case "inBuildLimit" -> (int) args[2] >= world.bottomY() && (int) args[2] < world.topYExclusive();
                    case "chunkLoaded" -> world.isLoaded((int) args[1], (int) args[2]);
                    case "insideBorder" -> true;
                    case "border" -> new BorderBounds(-3.0e7, 3.0e7, -3.0e7, 3.0e7);
                    case "reader" -> reader;
                    case "writer" -> new Writer();
                    case "chunkTickets" -> noTickets;
                    case "clientUpdates" -> noUpdates;
                    case "relightSection" -> 0;
                    // No entities: every chunk's are "not loaded", so a job's last sweep for hanging entities finds none.
                    case "entities" -> nothingThere(dev.sculptory.server.platform.WorldEntities.class);
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "test host";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** An empty {@code type}: false, zero or another such object from every method (a world without entities). */
    @SuppressWarnings("unchecked")
    private static <T> T nothingThere(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            Class<?> returns = method.getReturnType();
            if (returns == boolean.class) return false;
            if (returns == long.class) return 0L;
            if (returns == int.class) return 0;
            if (returns == List.class) return List.of();
            if (returns.isInterface()) return nothingThere(returns);
            return null;
        });
    }

    /** Writes into {@link #world}: reads the live cell, asks the guard, records and writes, as the platform writer does. */
    private final class Writer implements WorldWriter {
        long changed;

        @Override
        public boolean write(int x, int y, int z, int handle, BlockEntityData tile, RecordSink sink) {
            return write(x, y, z, handle, tile, sink, null);
        }

        @Override
        public boolean write(int x, int y, int z, int handle, BlockEntityData tile, RecordSink sink, Guard guard) {
            int live = world.get(x, y, z);
            BlockEntityData liveTile = world.tile(x, y, z);
            if (guard != null && !guard.mayReplace(live, liveTile)) return false;
            if (live == handle && Objects.equals(liveTile, tile)) return false;
            sink.record(x, y, z, live, liveTile, handle, tile);
            world.set(x, y, z, handle);
            world.setTile(x, y, z, tile);
            changed++;
            return true;
        }

        @Override
        public void clearTicksAtWrittenCells() {}

        @Override
        public WorldWriter syncThrough(ClientUpdates sync) {
            return this;
        }

        @Override
        public WorldWriter watchVanillaWrites(ClientUpdates sync) {
            return this;
        }

        @Override
        public long changed() {
            return changed;
        }

        @Override
        public long strippedNbt() {
            return 0;
        }

        @Override
        public long tileFailures() {
            return 0;
        }

        @Override
        public String firstTileFailure() {
            return null;
        }
    }

    /**
     * Fills a box with one state. Its first section also asks {@link ComputeContext#mayWrite} about column (0, 0)
     * below and above y 10, as a program deciding about cells beyond its section does.
     */
    private static final class Fill implements EditProgram {
        final Box box;
        final int handle;
        final long[] order;
        boolean asked;
        boolean mayWriteBelow;
        boolean mayWriteAbove;

        Fill(Box box, int handle) {
            this.box = box;
            this.handle = handle;
            LongArrayList keys = new LongArrayList();
            box.forEachSectionKey(keys::add);
            this.order = keys.toLongArray();
        }

        @Override
        public String label() {
            return "Fill";
        }

        @Override
        public Box bounds() {
            return box;
        }

        @Override
        public long estimatedCells() {
            return box.volume();
        }

        @Override
        public long[] sourceSections() {
            return new long[0];
        }

        @Override
        public long[] sectionOrder() {
            return order.clone();
        }

        @Override
        public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
            if (!asked) {
                asked = true;
                mayWriteBelow = ctx.mayWrite(0, 9, 0);
                mayWriteAbove = ctx.mayWrite(0, 10, 0);
            }
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            int x0 = Math.max(box.min().x(), ox), x1 = Math.min(box.max().x(), ox + 15);
            int y0 = Math.max(box.min().y(), oy), y1 = Math.min(box.max().y(), oy + 15);
            int z0 = Math.max(box.min().z(), oz), z1 = Math.min(box.max().z(), oz + 15);
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) out.set(SectionBuffer.index(x & 15, y & 15, z & 15), handle);
                }
            }
        }
    }
}
