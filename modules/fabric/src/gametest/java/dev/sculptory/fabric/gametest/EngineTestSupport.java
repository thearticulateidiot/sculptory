package dev.sculptory.fabric.gametest;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.ComputeContext;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.RecordSink;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;
import net.minecraft.server.world.ChunkTicket;
import net.minecraft.server.world.ChunkTicketManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.collection.SortedArraySet;
import net.minecraft.util.math.ChunkPos;

/** Helpers shared by the engine GameTests. Everything here runs on the server thread. */
final class EngineTestSupport {
    /**
     * Tick limit for tests that wait for chunk generation. The GameTest server ticks unthrottled (about a thousand
     * ticks per second while tests wait), so a tick limit is a poor clock; this one allows tens of seconds.
     */
    static final int CHUNK_GENERATION_TICK_LIMIT = 20_000;

    private EngineTestSupport() {}

    static EngineRuntime runtime(TestContext context) {
        return EngineRuntime.get(context.getWorld().getServer());
    }

    static int handle(FabricStateSpace states, String spec) {
        int h = states.parse(spec);
        if (h < 0) throw new GameTestException("Unknown block state " + spec);
        return h;
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new GameTestException(message);
    }

    static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return new Box(new BlockPos(x0, y0, z0), new BlockPos(x1, y1, z1));
    }

    static net.minecraft.util.math.BlockPos pos(int x, int y, int z) {
        return new net.minecraft.util.math.BlockPos(x, y, z);
    }

    /**
     * The chunk-aligned (x, z) corner of a private work region, {@code slot * 4096} blocks east of this test.
     * The GameTest world persists between runs but tests are placed at a random origin each run, so regions
     * relative to the test are fresh (fixed coordinates would still hold the previous run's blocks).
     */
    static int[] regionCorner(TestContext context, int slot) {
        net.minecraft.util.math.BlockPos origin = context.getAbsolutePos(net.minecraft.util.math.BlockPos.ORIGIN);
        return new int[] {((origin.getX() >> 4) << 4) + slot * 4096, (origin.getZ() >> 4) << 4};
    }

    /** Forces (or releases) every chunk under {@code box}; idempotent. */
    static void forceChunks(ServerWorld world, Box box, boolean forced) {
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                world.setChunkForced(cx, cz, forced);
            }
        }
    }

    /**
     * A test-local program (the real {@code OpCompiler} is WS1 work): fills {@code box} with one state, section by
     * section in {@link Box#forEachSectionKey} order. {@link #onCompute} runs before each section is computed.
     */
    static final class BoxFill implements EditProgram {
        final String label;
        final Box box;
        final int handle;
        final long[] order;
        LongConsumer onCompute = key -> {};
        int computeCount;

        BoxFill(String label, Box box, int handle) {
            this.label = label;
            this.box = box;
            this.handle = handle;
            LongArrayList keys = new LongArrayList();
            box.forEachSectionKey(keys::add);
            this.order = keys.toLongArray();
        }

        @Override
        public String label() {
            return label;
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
            computeCount++;
            onCompute.accept(key);
            int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
            int x0 = Math.max(box.min().x(), ox), x1 = Math.min(box.max().x(), ox + 15);
            int y0 = Math.max(box.min().y(), oy), y1 = Math.min(box.max().y(), oy + 15);
            int z0 = Math.max(box.min().z(), oz), z1 = Math.min(box.max().z(), oz + 15);
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    for (int x = x0; x <= x1; x++) {
                        out.set(SectionBuffer.index(x - ox, y - oy, z - oz), handle);
                    }
                }
            }
        }
    }

    /**
     * The number of {@code sculptory:edit} tickets vanilla holds for chunk (cx, cz), read from the chunk ticket
     * manager (reflection on the yarn-named dev runtime; test-only).
     */
    @SuppressWarnings("unchecked")
    static int editTickets(ServerWorld world, int cx, int cz) {
        try {
            ChunkTicketManager manager = world.getChunkManager().chunkLoadingManager.getTicketManager();
            Field field = ChunkTicketManager.class.getDeclaredField("ticketsByPosition");
            field.setAccessible(true);
            Long2ObjectOpenHashMap<SortedArraySet<ChunkTicket<?>>> byPosition =
                    (Long2ObjectOpenHashMap<SortedArraySet<ChunkTicket<?>>>) field.get(manager);
            SortedArraySet<ChunkTicket<?>> tickets = byPosition.get(ChunkPos.toLong(cx, cz));
            if (tickets == null) return 0;
            int count = 0;
            for (ChunkTicket<?> ticket : tickets) {
                if (ticket.getType() == EngineRuntime.EDIT_TICKET) count++;
            }
            return count;
        } catch (ReflectiveOperationException e) {
            throw new GameTestException("cannot read chunk tickets: " + e);
        }
    }

    /** Fails unless no edit ticket (vanilla side) and no holder (executor side) remains on any column of box. */
    static void checkNoEditTickets(EditExecutor<ServerWorld> executor, ServerWorld world, Box box) {
        check(executor.ticketsInFlight() == 0, executor.ticketsInFlight() + " edit tickets still held");
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                check(editTickets(world, cx, cz) == 0, "edit ticket left on chunk " + cx + "," + cz);
            }
        }
    }

    /** One recorded cell: the first before and the last after. */
    record Rec(int before, BlockEntityData beforeTile, int after, BlockEntityData afterTile) {}

    /** A RecordSink keeping the first before and last after per cell, like the history's coalescing. */
    static final class MapSink implements RecordSink {
        private final Map<net.minecraft.util.math.BlockPos, Rec> cells = new HashMap<>();
        long records;

        @Override
        public void record(int x, int y, int z, int before, BlockEntityData beforeTile, int after,
                           BlockEntityData afterTile) {
            records++;
            cells.merge(pos(x, y, z), new Rec(before, beforeTile, after, afterTile),
                    (first, last) -> new Rec(first.before(), first.beforeTile(), last.after(), last.afterTile()));
        }

        Rec get(net.minecraft.util.math.BlockPos pos) {
            return cells.get(pos);
        }

        int size() {
            return cells.size();
        }

        /** Writes every recorded before back (a test-local undo; the real one is WS1's HistoryPrograms). */
        void undo(BlockWriter writer) {
            cells.forEach((pos, rec) -> writer.write(pos.getX(), pos.getY(), pos.getZ(), rec.before(), rec.beforeTile()));
        }
    }

    /** Records listener callbacks. */
    static final class RecordingListener implements JobListener {
        final List<Phase> phases = new ArrayList<>();
        int progressEvents;
        long lastDone = -1;
        boolean wentBackwards;
        JobResult result;
        int finishedCalls;
        /** Runs inside {@link #finished}, after the result is stored. */
        Runnable onFinished = () -> {};

        @Override
        public void progress(UUID job, long done, long total, Phase ph) {
            progressEvents++;
            phases.add(ph);
            if (done < lastDone) wentBackwards = true;
            lastDone = done;
        }

        @Override
        public void finished(JobResult r) {
            finishedCalls++;
            result = r;
            onFinished.run();
        }

        int count(Phase phase) {
            int n = 0;
            for (Phase p : phases) {
                if (p == phase) n++;
            }
            return n;
        }
    }

    /** Counts records and remembers the section keys they fell in. */
    static final class CountingSink implements RecordSink {
        long records;
        final LongOpenHashSet sections = new LongOpenHashSet();

        @Override
        public void record(int x, int y, int z, int before, BlockEntityData beforeTile, int after,
                           BlockEntityData afterTile) {
            records++;
            sections.add(BlockBuffer.keyOfBlock(x, y, z));
        }
    }
}
