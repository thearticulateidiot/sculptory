package dev.sculptory.server.engine.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
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
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Protection limited in height, driven through the executor's bulk jobs against a fake world: a chunk allowed below
 * y 10 and denied from y 10 up. Denied cells are skipped and counted cell by cell, the record holds exactly the cells
 * written, and an undo restores exactly those (under the same protection, or skipping and counting what became
 * protected since).
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
     * Protection as a 3D producer gives it: chunk (0, 0) is denied at y {@code deniedFrom} and above and below
     * {@code allowedFrom}; chunk (1, 0) is free. Like a region-based producer, it names a point inside each of its
     * areas as a focus, so an area between the box's corners is not missed.
     */
    private record Region(int allowedFrom, int deniedFrom) implements PermissionService<Object, Object> {
        @Override
        public boolean has(Object p, Perm node) {
            return true;
        }

        @Override
        public ChunkPermit chunk(Object p, Object w, int cx, int cz, Box bounds) {
            List<BlockPos> areas = List.of(new BlockPos(8, allowedFrom - 1, 8),
                    new BlockPos(8, (allowedFrom + deniedFrom - 1) >> 1, 8), new BlockPos(8, deniedFrom, 8));
            return ChunkPermits.forChunk(cx, cz, bounds, this::allows, areas);
        }

        boolean allows(int x, int y, int z) {
            return x >= 16 || (y >= allowedFrom && y < deniedFrom);
        }
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

    @Test
    void aSmallRegionInsideAChunkIsHonouredWhenItsProducerNamesIt() throws EditRejected {
        // A 3 x 2 x 3 region in the middle of chunk (0, 0), touching no corner of the fill; the producer names its
        // centre as a focus point (ChunkPermits.forChunk), so the permit is judged cell by cell.
        world.fill(AREA, stone);
        EditExecutor<Object> executor = new EditExecutor<>(host(), settings());
        ChunkPermits.CellTest outside = (x, y, z) -> !(x >= 5 && x <= 7 && y >= 8 && y <= 9 && z >= 6 && z <= 8);
        PermitSource permits = (cx, cz) -> ChunkPermits.forChunk(cx, cz, AREA, outside,
                List.of(new BlockPos(6, 8, 7)));
        JobResult filled = run(executor, JobRequest.system(WORLD, new Fill(AREA, dirt), null).withPermits(permits));
        assertEquals(3 * 2 * 3, filled.skippedProtected());
        assertEquals(AREA.volume() - 18, filled.changed());
        forEachCell(AREA, (x, y, z) -> assertEquals(outside.allowed(x, y, z) ? dirt : stone, world.get(x, y, z),
                x + "," + y + "," + z));
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
        JobListener listener = new JobListener() {
            @Override
            public void progress(UUID job, long done, long total, Phase ph) {}

            @Override
            public void finished(JobResult r) {
                result[0] = r;
            }
        };
        executor.submit(new JobRequest<>(request.owner(), request.world(), request.program(), request.writeOptions(),
                request.permits(), request.mayLoadChunks(), request.seed(), listener, request.records()));
        for (int tick = 0; tick < 1000 && result[0] == null; tick++) {
            executor.tick();
            executor.endTick();
        }
        assertNotNull(result[0], "the job did not finish");
        return result[0];
    }

    private static EditExecutor.Settings settings() {
        return new EditExecutor.Settings(1_000_000_000L, 1_000_000, 0.4, 2, 8, 32, 64, UnloadedPolicy.REFUSE, 64,
                1024);
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
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "test host";
                    default -> throw new UnsupportedOperationException(method.getName());
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
