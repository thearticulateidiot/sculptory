package dev.sculptory.mc.world;

import dev.sculptory.mc.loader.Loaders;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The light checks of one writer's physics-off writes, handed to the light engine about a thousand at a time instead
 * of once per cell. Vanilla's {@code WorldChunk.setBlockState} asks the server's light engine to check every cell whose
 * light may change, and {@link ThreadedLevelLightEngine#checkBlock} queues a task per cell (about 100 bytes: two
 * lambdas, the position, the priority queue's entry) that its chunk task sorter hands to the light thread one chunk's
 * tasks at a time. They pile up while an edit writes faster than the light thread takes them, and while the server
 * thread is held: 2.2 GB of a 4 GB GameTest heap after a fill, two radius-128 Heightmap stamps and their undo.
 *
 * <p>While {@link #collect} is open on the server thread, {@code LightBatchMixin} hands this batch each check the
 * written cell asks of this world's light engine; {@link #flush} queues, per chunk column, tasks of at most
 * {@value #TASK_CELLS} checks at the stage and key vanilla's own check would take ({@code PRE_UPDATE}, the column's
 * chunk), which give the positions to the block and sky light providers exactly as {@code LightingProvider.checkBlock}
 * does, on the light thread. The light engine works the same checks out, so the light it settles on is the same; it
 * gets them a little later: at the end of the writer's part or section ({@link BlockWriter#clearTicksAtWrittenCells}),
 * else before the world's next tick ({@link #flushAll} at the start of each world tick, so before its light engine's
 * tick and its chunk unloading), before the client updates are sent ({@link ClientSync#flush}, whose light follow-ups
 * must sit behind them) and at the end of the tick. Section status changes (a section filling up or emptying) are not
 * batched and still come before the checks. The light thread works up to a thousand queued tasks between two light
 * updates, so a task's size bounds how many checks one update can be handed: {@value #TASK_CELLS} keeps one such pass
 * to about a million checks rather than a whole stamp. During a GameTest fill of a million cells a light marker on a
 * quiet chunk waited at most about 0.5 s (vanilla's per-cell tasks: about 1 s), and a fresh chunk loaded as quickly as
 * before the edit.
 *
 * <p>Batching is on only when the vanilla light engine does the work: no mod known to replace it is loaded, the world's
 * light engine is {@link ThreadedLevelLightEngine} itself, its providers are vanilla's {@link BlockLightEngine}
 * and {@link SkyLightEngine} (either may be absent, not both). Otherwise every check goes the vanilla way.
 * {@code LightBatchMixin} is optional: where another mod rewrote {@code WorldChunk.setBlockState} so its call is not
 * there, nothing reaches {@link #take} and every check goes the vanilla way too. Which way it goes is logged once.
 * Server thread only.
 */
public final class LightBatch {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** Mods that replace the light engine (they keep their own checks; batching would bypass them). */
    private static final List<String> LIGHT_ENGINE_MODS = List.of("scalablelux", "starlight", "phosphor", "moonrise");
    /** Checks a light-engine task holds at most. */
    public static final int TASK_CELLS = 1_024;

    /** Batches holding checks not yet flushed. */
    private static final List<LightBatch> PENDING = new ReferenceArrayList<>();
    /** The batch collecting the current write's checks, and its thread; {@code null} outside {@link #collect}. */
    private static LightBatch collecting;
    private static Thread collectingThread;
    /** Off for tests that need the per-cell way. */
    private static boolean disabled;
    /** Checks every batch has taken so far. */
    private static long batchedCells;
    private static final Set<String> LOGGED = new HashSet<>();
    /** {@code LightBatchMixin} has handed a check to {@link #take}: its wrap is in place. */
    private static boolean wrapSeen;

    private static final Scope SCOPE = new Scope(true);
    private static final Scope NESTED = new Scope(false);

    private final ThreadedLevelLightEngine provider;
    /** Chunk column ({@link ChunkPos#toLong}) → the cells to check there, in the order written. */
    private final Long2ObjectLinkedOpenHashMap<LongArrayList> columns = new Long2ObjectLinkedOpenHashMap<>();
    private long lastColumn = Long.MIN_VALUE;
    private LongArrayList lastCells;
    private long cells;
    private long flushedTasks;

    private LightBatch(ThreadedLevelLightEngine provider) {
        this.provider = provider;
    }

    /** A batch for {@code world}'s light engine, or {@code null} when batching is off there (checks go per cell). */
    public static LightBatch of(ServerLevel world) {
        ThreadedLevelLightEngine provider = world.getChunkSource().getLightEngine();
        String off = whyOff(provider);
        if (off != null) {
            log("Sculptory: light checks of edits go to the light engine one per cell: " + off);
            return null;
        }
        if (!wrapSeen) {
            log("Sculptory: the light engine is vanilla's; edits batch the light checks the wrapped block change"
                    + " hands them (a line follows when the wrap is first seen; without it vanilla's checkBlock runs"
                    + " for each cell)");
        }
        return new LightBatch(provider);
    }

    /** Why batching is off for {@code light}, or {@code null} when it is on. */
    private static String whyOff(LevelLightEngine light) {
        if (disabled) return "turned off";
        for (String mod : LIGHT_ENGINE_MODS) {
            if (modLoaded(mod)) return "the mod " + mod + " is loaded";
        }
        if (light.getClass() != ThreadedLevelLightEngine.class) {
            return "the light engine is " + light.getClass().getName();
        }
        if (!vanillaProviders(light.blockEngine, light.skyEngine)) {
            return "the light providers are " + name(light.blockEngine) + " and " + name(light.skyEngine);
        }
        return null;
    }

    /**
     * Whether {@code block} and {@code sky} are vanilla's own block and sky light providers (either may be absent, as
     * the sky in the Nether, but not both): an engine that keeps the vanilla classes but replaces or empties these
     * would drop the checks this batch hands them.
     */
    public static boolean vanillaProviders(Object block, Object sky) {
        if (block == null && sky == null) return false;
        return (block == null || block.getClass() == BlockLightEngine.class)
                && (sky == null || sky.getClass() == SkyLightEngine.class);
    }

    private static String name(Object provider) {
        return provider == null ? "none" : provider.getClass().getName();
    }

    private static synchronized void log(String message) {
        if (LOGGED.add(message)) LOG.info(message);
    }

    /** Whether the mod is loaded; without a loader (unit tests) nothing is. A missing loader in the game is a bug. */
    private static boolean modLoaded(String id) {
        return Loaders.info().isModLoaded(id);
    }

    /** Turns batching off (or back on) for the writers made from now on: for tests of the per-cell way. */
    public static void setDisabled(boolean off) {
        disabled = off;
    }

    /** Checks every batch has taken so far (tests: batching was in use). */
    public static long batchedCells() {
        return batchedCells;
    }

    /**
     * Collects the light checks of the writes made until the scope is closed. A write made while another batch collects
     * (a write inside a write) leaves that collection as it is: its checks go to that batch when it is of the same
     * light engine, else the vanilla way.
     */
    public Scope collect() {
        if (collecting != null) return NESTED;
        collecting = this;
        collectingThread = Thread.currentThread();
        return SCOPE;
    }

    /** Ends {@link #collect} (closing a nested one changes nothing). */
    public static final class Scope implements AutoCloseable {
        private final boolean outer;

        private Scope(boolean outer) {
            this.outer = outer;
        }

        @Override
        public void close() {
            if (!outer) return;
            collecting = null;
            collectingThread = null;
        }
    }

    /**
     * Called by {@code LightBatchMixin} for each light check {@code WorldChunk.setBlockState} asks: whether the
     * collecting batch took it (the check of a cell of its world, on its thread); otherwise it goes to {@code light}.
     */
    public static boolean take(LevelLightEngine light, BlockPos pos) {
        if (!wrapSeen) {
            // Only a report: the wrap is active. Without it nothing reaches here and vanilla's checkBlock runs.
            wrapSeen = true;
            log("Sculptory: block changes' light checks are wrapped; edits hand them to the light engine " + TASK_CELLS
                    + " at a time");
        }
        LightBatch batch = collecting;
        if (batch == null || batch.provider != light || Thread.currentThread() != collectingThread) return false;
        batch.add(pos.asLong(), ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
        return true;
    }

    private void add(long pos, long column) {
        if (column != lastColumn || lastCells == null) {
            lastCells = columns.computeIfAbsent(column, c -> new LongArrayList());
            lastColumn = column;
        }
        lastCells.add(pos);
        batchedCells++;
        if (cells++ == 0) PENDING.add(this);
    }

    /** Checks collected and not flushed yet. */
    public long pendingCells() {
        return cells;
    }

    /** Light-engine tasks this batch has queued so far. */
    public long flushedTasks() {
        return flushedTasks;
    }

    /** Queues the collected checks: per chunk column, tasks of at most {@value #TASK_CELLS} checks. */
    public void flush() {
        if (cells == 0) return;
        for (var entry : columns.long2ObjectEntrySet()) {
            long column = entry.getLongKey();
            int x = ChunkPos.getX(column), z = ChunkPos.getZ(column);
            long[] all = entry.getValue().elements();
            int size = entry.getValue().size();
            for (int from = 0; from < size; from += TASK_CELLS) {
                long[] positions = Arrays.copyOfRange(all, from, Math.min(size, from + TASK_CELLS));
                provider.addTask(x, z, ThreadedLevelLightEngine.TaskType.PRE_UPDATE, () -> check(provider, positions));
                flushedTasks++;
            }
        }
        columns.clear();
        lastCells = null;
        lastColumn = Long.MIN_VALUE;
        cells = 0;
        PENDING.remove(this);
    }

    /**
     * Flushes every batch holding checks: at the start of each world's tick, before the client updates are sent and at
     * the end of the tick.
     */
    public static void flushAll() {
        while (!PENDING.isEmpty()) PENDING.get(PENDING.size() - 1).flush();
    }

    /** What {@code LightingProvider.checkBlock} does for each position, on the light thread. */
    private static void check(LevelLightEngine light, long[] positions) {
        LightEngine<?, ?> block = light.blockEngine;
        LightEngine<?, ?> sky = light.skyEngine;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (long p : positions) {
            pos.set(p);
            if (block != null) block.checkBlock(pos);
            if (sky != null) sky.checkBlock(pos);
        }
    }
}
