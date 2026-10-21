package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EditTestSupport.capture;
import static dev.sculptory.mc.gametest.EditTestSupport.checkSame;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.checkNoEditTickets;
import static dev.sculptory.mc.gametest.EngineTestSupport.editTickets;
import static dev.sculptory.mc.gametest.EngineTestSupport.handle;
import static dev.sculptory.mc.gametest.EngineTestSupport.pos;
import static dev.sculptory.mc.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.mc.gametest.EngineTestSupport.runtime;
import static dev.sculptory.mc.gametest.ExecutorGameTest.submit;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.HistoryPrograms;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.mc.engine.impl.EngineRuntime;
import dev.sculptory.mc.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.mc.gametest.EngineTestSupport.BoxFill;
import dev.sculptory.mc.gametest.EngineTestSupport.CountingSink;
import dev.sculptory.mc.gametest.EngineTestSupport.MapSink;
import dev.sculptory.mc.gametest.EngineTestSupport.Rec;
import dev.sculptory.mc.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McStateSpace;
import dev.sculptory.mc.world.McWorldReader;
import dev.sculptory.mc.world.WorldChecks;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.impl.BrushWork;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.JobRequest;
import dev.sculptory.server.engine.impl.PermitSource;
import dev.sculptory.server.engine.impl.RecordSink;
import dev.sculptory.server.perm.ChunkPermits;
import dev.sculptory.server.platform.WriteOptions;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.material.Fluids;

/** Executor behaviour that needs a private instance (driven by hand) or an untouched far region. */
public final class ExecutorLifecycleGameTest {
    /**
     * A private executor ticked by hand: the REFUSE policy, the per-tick block cap, and server stop (finish the
     * current section, end as CANCELLED, keep and record the applied work, drop queued dabs).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_exec_lifecycle", timeoutTicks = 400)
    public void shutdownKeepsAppliedWork(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        EditExecutor<ServerLevel> executor = new EditExecutor<>(runtime, settings(5000, 1024, 16_384));
        int stone = handle(runtime.states(), "minecraft:smooth_stone");

        int[] far = regionCorner(context, 7);
        Box unloaded = box(far[0], 0, far[1], far[0] + 15, 15, far[1] + 15);
        check(!WorldChecks.isChunkLoaded(world, far[0] >> 4, far[1] >> 4), "far region is already loaded");
        try {
            executor.submit(JobRequest.system(world, new BoxFill("refused", unloaded, stone), null));
            throw new GameTestAssertException("REFUSE policy admitted a job over unloaded chunks");
        } catch (EditRejected e) {
            check(e.reason() == RejectReason.UNLOADED, "reason " + e.reason());
        }

        int[] at = regionCorner(context, 6);
        Box region = box(at[0], 0, at[1], at[0] + 63, 15, at[1] + 63); // 16 sections
        for (int cx = at[0] >> 4; cx <= (at[0] + 63) >> 4; cx++) {
            for (int cz = at[1] >> 4; cz <= (at[1] + 63) >> 4; cz++) world.getChunk(cx, cz); // loads synchronously
        }
        BoxFill program = new BoxFill("stopped", region, stone);
        RecordingListener listener = new RecordingListener();
        CountingSink sink = new CountingSink();
        submitTo(executor, JobRequest.system(world, program, listener).withRecords(sink));

        executor.tick();
        check(sink.records == 5000, "first tick wrote " + sink.records + " cells; the cap is 5000");
        executor.tick();
        check(sink.records == 10_000, "second tick total " + sink.records);
        int[] dropped = {0};
        boolean queued = executor.submitBrush(new BrushWork() {
            @Override
            public void run() {
                throw new GameTestAssertException("queued dab ran after shutdown");
            }

            @Override
            public void dropped() {
                dropped[0]++;
            }
        });

        executor.shutdown();
        check(listener.finishedCalls == 1 && listener.result.outcome() == JobOutcome.CANCELLED,
                "not cancelled at shutdown: " + listener.result);
        check(listener.result.changed() == 3 * 4096L, "changed " + listener.result.changed()
                + "; the section in progress should be completed");
        check(sink.records == listener.result.changed(), "record has " + sink.records);
        check(listener.phases.get(listener.phases.size() - 1) == Phase.FINALIZE, "no FINALIZE");
        check(queued && dropped[0] == 1, "queued dab was not dropped");
        check(executor.activeJobCount() == 0, "jobs still active after shutdown");
        checkNoEditTickets(executor, world, region);
        check(!executor.isLocked(world, region), "locks kept after shutdown");

        executor.tick(); // no-op after shutdown
        check(program.computeCount == 3, "computed after shutdown: " + program.computeCount);
        try {
            executor.submit(JobRequest.system(world, new BoxFill("late", region, stone), null));
            throw new GameTestAssertException("job admitted after shutdown");
        } catch (EditRejected e) {
            check(e.reason() == RejectReason.DISABLED, "reason " + e.reason());
        }
        boolean lateQueued = executor.submitBrush(new BrushWork() {
            @Override
            public void run() {
                throw new GameTestAssertException("dab ran after shutdown");
            }

            @Override
            public void dropped() {
                dropped[0]++;
            }
        });
        check(!lateQueued && dropped[0] == 2, "late dab was not refused and dropped");
        context.succeed();
    }

    /** Under the LOAD policy a job over never-loaded chunks loads them with its own tickets and releases them. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_exec_load",
            timeoutTicks = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT)
    public void unloadedChunksLoadWithTickets(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        int[] at = regionCorner(context, 8);
        Box region = box(at[0], 0, at[1], at[0] + 31, 15, at[1] + 31);
        check(!WorldChecks.isChunkLoaded(world, at[0] >> 4, at[1] >> 4), "region is already loaded");
        BoxFill program = new BoxFill("load", region, handle(runtime.states(), "minecraft:smooth_stone"));
        RecordingListener listener = new RecordingListener();
        submit(runtime, JobRequest.system(world, program, listener));
        context.succeedWhen(() -> {
            check(listener.result != null, "job still running");
            check(listener.result.outcome() == JobOutcome.COMPLETED, "outcome " + listener.result.outcome());
            check(listener.result.changed() == region.volume(), "changed " + listener.result.changed());
            check(listener.count(Phase.LOAD_CHUNKS) == 1, "LOAD_CHUNKS reported " + listener.count(Phase.LOAD_CHUNKS));
            checkNoEditTickets(runtime.executor(), world, region);
        });
    }

    /** A compute failure ends the job as FAILED, keeps (and records) the sections already written, frees locks. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_exec_failure", timeoutTicks = 400)
    public void failedJobKeepsAppliedWork(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        EditExecutor<ServerLevel> executor = privateExecutor(runtime, world);
        int[] at = regionCorner(context, 9);
        Box region = box(at[0], 0, at[1], at[0] + 31, 15, at[1] + 31); // 4 sections
        loadChunks(world, region);
        BoxFill program = new BoxFill("fails", region, handle(runtime.states(), "minecraft:smooth_stone"));
        program.onCompute = key -> {
            if (program.computeCount == 3) throw new IllegalStateException("test failure in compute");
        };
        RecordingListener listener = new RecordingListener();
        CountingSink sink = new CountingSink();
        submitTo(executor, JobRequest.system(world, program, listener).withRecords(sink));
        executor.tick();
        check(listener.result != null && listener.result.outcome() == JobOutcome.FAILED, "outcome " + listener.result);
        check(listener.result.changed() == 2 * 4096L && sink.records == 2 * 4096L, "changed " + listener.result.changed());
        check(!executor.isLocked(world, region), "locks kept after failure");
        checkNoEditTickets(executor, world, region);
        executor.shutdown();
        context.succeed();
    }

    /**
     * SNAPSHOT_SOURCES: a copy onto an overlapping destination reads the pre-write snapshot, so sections written
     * earlier in the job do not leak into later reads.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_exec_sources", timeoutTicks = 400)
    public void sourcesAreSnapshottedBeforeWrites(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        McStateSpace states = runtime.states();
        EditExecutor<ServerLevel> executor = privateExecutor(runtime, world);
        int[] at = regionCorner(context, 10);
        Box source = box(at[0], 0, at[1], at[0] + 47, 15, at[1] + 15); // 3 sections along x
        loadChunks(world, box(at[0], 0, at[1], at[0] + 79, 15, at[1] + 15)); // source and shifted target
        int stone = handle(states, "minecraft:stone"), dirt = handle(states, "minecraft:dirt");
        BlockWriter writer = runtime.writer(world, WriteOptions.DEFAULT);
        for (int x = source.min().x(); x <= source.max().x(); x++) {
            for (int y = 0; y <= 15; y++) {
                for (int z = source.min().z(); z <= source.max().z(); z++) {
                    writer.write(x, y, z, pattern(x, y, z, stone, dirt), null);
                }
            }
        }
        ShiftCopy program = new ShiftCopy(source, SHIFT);
        RecordingListener listener = new RecordingListener();
        submitTo(executor, JobRequest.system(world, program, listener));
        for (int i = 0; i < 20 && listener.result == null; i++) executor.tick();
        check(listener.result != null && listener.result.outcome() == JobOutcome.COMPLETED, "outcome " + listener.result);
        check(listener.count(Phase.SNAPSHOT_SOURCES) == 1, "SNAPSHOT_SOURCES not reported");
        McWorldReader reader = runtime.reader(world);
        for (int x = source.min().x(); x <= source.max().x(); x++) {
            for (int y = 0; y <= 15; y++) {
                for (int z = source.min().z(); z <= source.max().z(); z++) {
                    int copied = reader.get(x + SHIFT, y, z);
                    check(copied == pattern(x, y, z, stone, dirt), "copy of " + x + "," + y + "," + z + " read a "
                            + "section already written by this job");
                }
            }
        }
        executor.shutdown();
        context.succeed();
    }

    /** Not a multiple of 16, so the copy straddles sections, and not a period of {@link #pattern}. */
    private static final int SHIFT = 21;

    /** A position hash: copying already-overwritten cells instead of the snapshot changes the result. */
    private static int pattern(int x, int y, int z, int a, int b) {
        int h = x * 73_856_093 ^ y * 19_349_663 ^ z * 83_492_791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return (h & 1) == 0 ? a : b;
    }

    /** Copies {@code source} to {@code source + (dx, 0, 0)}, reading only the pre-write snapshots. */
    private static final class ShiftCopy implements EditProgram {
        final Box source;
        final Box target;
        final int dx;
        final long[] sourceKeys;
        final long[] order;

        ShiftCopy(Box source, int dx) {
            this.source = source;
            this.target = source.offset(dx, 0, 0);
            this.dx = dx;
            LongArrayList keys = new LongArrayList();
            source.forEachSectionKey(keys::add);
            this.sourceKeys = keys.toLongArray();
            keys.clear();
            target.forEachSectionKey(keys::add);
            this.order = keys.toLongArray();
        }

        @Override
        public String label() {
            return "shift copy";
        }

        @Override
        public Box bounds() {
            return new Box(source.min(), target.max());
        }

        @Override
        public long estimatedCells() {
            return target.volume();
        }

        @Override
        public long[] sourceSections() {
            return sourceKeys.clone();
        }

        @Override
        public long[] sectionOrder() {
            return order.clone();
        }

        @Override
        public void compute(long key, SectionBuffer before, SectionBuffer out, ComputeContext ctx) {
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            for (int i = 0; i < SectionBuffer.SIZE; i++) {
                int x = ox + SectionBuffer.localX(i), y = oy + SectionBuffer.localY(i), z = oz + SectionBuffer.localZ(i);
                if (!target.contains(x, y, z)) continue;
                SectionBuffer snapshot = ctx.source(BlockBuffer.keyOfBlock(x - dx, y, z));
                if (snapshot == null) throw new IllegalStateException("no snapshot for a source section");
                out.set(i, snapshot.get(SectionBuffer.index((x - dx) & 15, y & 15, z & 15)));
            }
        }
    }

    private static EditExecutor<ServerLevel> privateExecutor(EngineRuntime runtime, ServerLevel world) {
        return privateExecutor(runtime, world, settings(0, 1024, 16_384));
    }

    private static EditExecutor<ServerLevel> privateExecutor(EngineRuntime runtime, ServerLevel world,
                                                             EditExecutor.Settings s) {
        return new EditExecutor<>(runtime, s);
    }

    /** A 200 ms budget (so only the caps matter), REFUSE policy. */
    private static EditExecutor.Settings settings(long maxBlocksPerTick, int maxBrushQueue, int maxColumnsPerJob) {
        return new EditExecutor.Settings(200_000_000L, maxBlocksPerTick, 0.4, 2, 8, 32, 64, UnloadedPolicy.REFUSE,
                maxBrushQueue, maxColumnsPerJob);
    }

    /**
     * H1: a job paused mid-section records what is really in a cell when it writes it. A chest placed and filled
     * after the section was captured but before its cell was written is recorded (and restored by undo).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_exec_live", timeoutTicks = 400)
    public void liveBeforeIsRecordedMidSection(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        EditExecutor<ServerLevel> executor = privateExecutor(runtime, world, settings(1000, 1024, 16_384));
        int[] at = regionCorner(context, 12);
        Box region = box(at[0], 0, at[1], at[0] + 15, 15, at[1] + 15); // one section
        loadChunks(world, region);
        int stone = handle(runtime.states(), "minecraft:smooth_stone");
        RecordingListener listener = new RecordingListener();
        MapSink sink = new MapSink();
        submitTo(executor, JobRequest.system(world, new BoxFill("live", region, stone), listener).withRecords(sink));
        executor.tick();
        check(sink.records == 1000 && listener.result == null, "setup: expected a paused section, " + sink.records);

        BlockPos chest = pos(at[0] + 5, 12, at[1] + 5); // cell index 3157: not yet written
        world.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) world.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        int chestHandle = runtime.states().handle(world.getBlockState(chest));

        for (int i = 0; i < 10 && listener.result == null; i++) executor.tick();
        check(listener.result != null && listener.result.outcome() == JobOutcome.COMPLETED, "outcome " + listener.result);
        check(world.getBlockState(chest).is(Blocks.SMOOTH_STONE), "the job did not overwrite the chest");
        Rec rec = sink.get(chest);
        check(rec != null && rec.before() == chestHandle, "recorded before is not the chest: " + rec);
        check(rec.beforeTile() != null, "the chest contents were not recorded");

        sink.undo(runtime.writer(world, WriteOptions.DEFAULT));
        check(world.getBlockEntity(chest) instanceof ChestBlockEntity c && c.getItem(0).getCount() == 7,
                "undo did not restore the chest contents");
        check(world.getEntities(EntityType.ITEM, net.minecraft.world.phys.AABB.encapsulatingFullBlocks(pos(at[0], 0, at[1]),
                pos(at[0] + 16, 16, at[1] + 16)), e -> true).isEmpty(), "items dropped");
        executor.shutdown();
        context.succeed();
    }

    /** M5: tick clearing touches only written cells; a protected cell keeps its scheduled fluid tick. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_exec_ticks", timeoutTicks = 400)
    public void skippedCellsKeepScheduledTicks(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        EditExecutor<ServerLevel> executor = privateExecutor(runtime, world);
        int[] at = regionCorner(context, 13);
        Box region = box(at[0], 0, at[1], at[0] + 15, 15, at[1] + 15);
        loadChunks(world, region);
        BlockPos skipped = pos(at[0] + 5, 3, at[1] + 5);
        BlockPos written = pos(at[0] + 9, 3, at[1] + 9);
        world.setBlockAndUpdate(skipped, Blocks.WATER.defaultBlockState());
        world.setBlockAndUpdate(written, Blocks.WATER.defaultBlockState());
        check(world.getFluidTicks().hasScheduledTick(skipped, Fluids.WATER)
                && world.getFluidTicks().hasScheduledTick(written, Fluids.WATER), "setup: water scheduled no ticks");
        long[] allowed = {-1L, -1L, -1L, -1L};
        int deniedBit = ((skipped.getZ() & 15) << 4) | (skipped.getX() & 15);
        allowed[deniedBit >>> 6] &= ~(1L << deniedBit);
        ChunkPermit permit = new ChunkPermit.Columns(allowed);
        RecordingListener listener = new RecordingListener();
        submitTo(executor, JobRequest.system(world, new BoxFill("ticks", region,
                handle(runtime.states(), "minecraft:smooth_stone")), listener).withPermits((cx, cz) -> permit));
        executor.tick();
        check(listener.result != null && listener.result.outcome() == JobOutcome.COMPLETED, "outcome " + listener.result);
        check(listener.result.skippedProtected() == 16, "skipped " + listener.result.skippedProtected());
        check(world.getBlockState(skipped).is(Blocks.WATER), "the protected cell was written");
        check(world.getFluidTicks().hasScheduledTick(skipped, Fluids.WATER),
                "the protected cell's scheduled fluid tick was cleared");
        check(!world.getFluidTicks().hasScheduledTick(written, Fluids.WATER), "a written cell kept its fluid tick");
        BlockWriter cleanup = runtime.writer(world, WriteOptions.DEFAULT);
        cleanup.write(skipped.getX(), skipped.getY(), skipped.getZ(), runtime.states().air(), null);
        cleanup.clearTicksAtWrittenCells();
        executor.shutdown();
        context.succeed();
    }

    /**
     * Protection limited in height (a per-cell permit, as a region-based producer gives; a GameTest can set up no such
     * protection, so the permit is given to the executor directly): the first of two chunks allows y 0..9 and denies
     * y 10..19. The fill writes exactly the allowed cells and counts every denied one, its record holds only what it
     * wrote, and its undo puts back exactly that.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_exec_height_permit", timeoutTicks = 400)
    public void aPermitLimitedInHeightIsHonouredCellByCell(GameTestHelper context) {
        // The GameTest runs fail any permit asked outside its bounds (PermitBounds); this run must have the check on.
        check(Boolean.getBoolean("sculptory.checkPermitBounds"),
                "the GameTest run does not set sculptory.checkPermitBounds");
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        EditExecutor<ServerLevel> executor = privateExecutor(runtime, world);
        int[] at = regionCorner(context, 31);
        Box region = box(at[0], 0, at[1], at[0] + 31, 19, at[1] + 15); // two chunks, two sections tall
        loadChunks(world, region);
        WorldSnapshot original = capture(world, region);
        int cx = at[0] >> 4, cz = at[1] >> 4;
        ChunkPermits.CellTest lowerLayers = (x, y, z) -> y < 10;
        PermitSource permits = (px, pz) -> px == cx && pz == cz
                ? ChunkPermits.rasterize(px, pz, region, lowerLayers) : ChunkPermit.ALLOW;
        check(permits.chunk(cx, cz) instanceof ChunkPermit.Cells, "not a per-cell permit: " + permits.chunk(cx, cz));
        int smooth = handle(runtime.states(), "minecraft:smooth_stone");
        RecordBuilder record = new RecordBuilder();
        RecordingListener filled = new RecordingListener();
        submitTo(executor, JobRequest.system(world, new BoxFill("height", region, smooth), filled).withPermits(permits)
                .withRecords(RecordSink.into(record)));
        for (int i = 0; i < 20 && filled.result == null; i++) executor.tick();
        check(filled.result != null && filled.result.outcome() == JobOutcome.COMPLETED, "fill " + filled.result);
        long denied = 16L * 16 * 10;
        check(filled.result.skippedProtected() == denied, "skipped " + filled.result.skippedProtected());
        check(filled.result.changed() == region.volume() - denied, "changed " + filled.result.changed());
        WorldSnapshot expected = MultiplayerGameTest.with(
                MultiplayerGameTest.with(original, box(at[0], 0, at[1], at[0] + 15, 9, at[1] + 15), smooth),
                box(at[0] + 16, 0, at[1], at[0] + 31, 19, at[1] + 15), smooth);
        checkSame(expected, capture(world, region), "the allowed cells, and only those");
        EditRecord edit = record.build();
        check(edit.before().cellCount() == region.volume() - denied, "recorded " + edit.before().cellCount());

        HistoryEntry entry = new HistoryEntry(UUID.randomUUID(), UUID.randomUUID(),
                world.dimension().location().toString(), "Fill", edit, System.currentTimeMillis());
        RecordingListener undone = new RecordingListener();
        submitTo(executor, JobRequest.system(world, HistoryPrograms.undo(entry, ConflictPolicy.SKIP_CONFLICTS), undone)
                .withPermits(permits));
        for (int i = 0; i < 20 && undone.result == null; i++) executor.tick();
        check(undone.result != null && undone.result.outcome() == JobOutcome.COMPLETED, "undo " + undone.result);
        check(undone.result.changed() == region.volume() - denied && undone.result.skippedProtected() == 0
                && undone.result.skippedConflicts() == 0, "undo " + undone.result);
        checkSame(original, capture(world, region), "after the undo");
        executor.shutdown();
        context.succeed();
    }

    /**
     * M2 and L1: two jobs share a never-loaded column (no forced chunks). When the first finishes, the second
     * still holds the vanilla edit ticket; when both are done, no edit ticket remains.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_exec_shared_ticket",
            timeoutTicks = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT)
    public void sharedColumnTicketsAreCounted(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        EditExecutor<ServerLevel> executor = runtime.executor();
        int[] at = regionCorner(context, 14);
        int cx = at[0] >> 4, cz = at[1] >> 4;
        check(!WorldChecks.isChunkLoaded(world, cx, cz), "setup: column already loaded");
        int stone = handle(runtime.states(), "minecraft:smooth_stone");
        Box small = box(at[0], 0, at[1], at[0] + 15, 15, at[1] + 15); // 1 section
        Box large = box(at[0], 16, at[1], at[0] + 15, 16 * 9 - 1, at[1] + 15); // 8 sections, same column
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        int[] atFirstFinish = {-1, -1};
        boolean[] secondRunning = {false};
        first.onFinished = () -> {
            secondRunning[0] = second.result == null;
            atFirstFinish[0] = editTickets(world, cx, cz);
            atFirstFinish[1] = executor.ticketHolders(world, cx, cz);
        };
        submit(runtime, JobRequest.system(world, new BoxFill("small", small, stone), first));
        submit(runtime, JobRequest.system(world, new BoxFill("large", large, stone), second));
        context.succeedWhen(() -> {
            check(first.result != null && second.result != null, "jobs still running");
            check(first.result.outcome() == JobOutcome.COMPLETED && second.result.outcome() == JobOutcome.COMPLETED,
                    "outcomes " + first.result.outcome() + ", " + second.result.outcome());
            check(secondRunning[0], "setup: the second job finished first");
            check(atFirstFinish[0] == 1, "vanilla edit tickets on the shared column after the first job released: "
                    + atFirstFinish[0]);
            check(atFirstFinish[1] == 1, "holders after the first job released: " + atFirstFinish[1]);
            checkNoEditTickets(executor, world, box(at[0], 0, at[1], at[0] + 15, 16 * 9 - 1, at[1] + 15));
        });
    }

    /** L6: the brush queue and the per-job column count are bounded. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_exec_caps")
    public void capsRefuse(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        EditExecutor<ServerLevel> executor = privateExecutor(runtime, world, settings(0, 2, 4));
        int[] ran = {0};
        int[] dropped = {0};
        BrushWork work = new BrushWork() {
            @Override
            public void run() {
                ran[0]++;
            }

            @Override
            public void dropped() {
                dropped[0]++;
            }
        };
        check(executor.submitBrush(work) && executor.submitBrush(work), "brush work refused below the cap");
        check(!executor.submitBrush(work) && dropped[0] == 1, "a third dab was queued past maxBrushQueue = 2");
        executor.tick();
        check(ran[0] == 2 && executor.brushQueueSize() == 0, "queued dabs did not run: " + ran[0]);

        BlockPos origin = context.absolutePos(BlockPos.ZERO);
        int x0 = (origin.getX() >> 4) << 4, z0 = (origin.getZ() >> 4) << 4;
        int stone = handle(runtime.states(), "minecraft:stone");
        Box nine = box(x0, 0, z0, x0 + 47, 0, z0 + 47); // 3 x 3 columns
        try {
            executor.submit(JobRequest.system(world, new BoxFill("wide", nine, stone), null));
            throw new GameTestAssertException("a 9-column job was admitted with maxColumnsPerJob = 4");
        } catch (EditRejected e) {
            check(e.reason() == RejectReason.TOO_LARGE, "reason " + e.reason());
        }
        check(!executor.isLocked(world, nine), "a refused job left locks");
        executor.shutdown();
        context.succeed();
    }

    private static void loadChunks(ServerLevel world, Box box) {
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) world.getChunk(cx, cz);
        }
    }

    private static void submitTo(EditExecutor<ServerLevel> executor, JobRequest<ServerLevel> request) {
        try {
            executor.submit(request);
        } catch (EditRejected e) {
            throw new GameTestAssertException("rejected: " + e.getMessage());
        }
    }
}
