package dev.sculptory.fabric.world;

import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.LightUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerLightingProvider;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How bulk writes reach the players watching them. Server thread only.
 *
 * <p>Vanilla sends one {@code ChunkDeltaUpdateS2CPacket} per changed section and tick, with a varlong per changed cell
 * that compresses poorly (about 1.7 wire bytes per cell for a 1M-block fill), and the client applies it block by
 * block, relighting each one. For bulk jobs the writer instead collects the changed cells here ({@link #changed}); the
 * executor calls {@link #flush} at the end of every server tick (after vanilla has sent that tick's prediction acks),
 * which sends, per chunk column:
 * <ul>
 *   <li>fewer than {@value #RESEND_COLUMN_CELLS} changed cells: nothing special, the cells are marked for vanilla's
 *       own block updates ({@code ServerChunkManager.markForUpdate}), sent in the next tick's world tick;</li>
 *   <li>at least that many: the whole column as one {@code ChunkDataS2CPacket} (blocks, block entities, heightmaps and
 *       the light as it stands), built once and sent to every player watching the column. The client replaces the
 *       column in place, as for a resync, and does not relight it. Its light then predates the light engine's work on
 *       the new blocks, and vanilla sends light updates only to players at the edge of their view, so once the light
 *       engine has caught up with the column and its neighbours, their light is sent too ({@link #sendLight}). A
 *       column is resent at
 *       most once every {@value #RESEND_INTERVAL_TICKS} ticks: changes in between are kept for the next resend, so a
 *       column a job writes over many ticks is not sent whole every tick. {@link #flushNext} (a job ended) lifts that
 *       for the job's columns at the next flush.</li>
 * </ul>
 * A player who may hold predicted block changes ({@code predicting}: one who recently sent brush dabs) never gets the
 * column packet: a client without {@code ResentChunks} would not refresh its pending predictions from it, so a later
 * acknowledgement could put back a block the server has since changed. Such a player gets exactly what vanilla would
 * send instead: a delta (or single block update) per changed section, then the block entity updates of the changed
 * cells.
 *
 * <p>Only the cells' final states are sent, read when flushing, so it does not matter how often a cell was written. A
 * column whose packets fail to build falls back to vanilla's block updates.
 */
public final class ClientSync {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    /** Changed cells in one column from which the column is resent whole (one section's worth). */
    public static final int RESEND_COLUMN_CELLS = SectionBuffer.SIZE;
    /** Ticks between two resends of the same column. */
    public static final int RESEND_INTERVAL_TICKS = 10;
    /** Columns whose light is sent per flush at most (each light packet copies up to 52 light sections). */
    public static final int MAX_LIGHT_COLUMNS_PER_FLUSH = 64;
    /** Ticks after which a light follow-up is sent even if the light engine has not caught up. */
    public static final int LIGHT_WAIT_LIMIT_TICKS = 1200;
    /** Ticks a batch of due light waits for outstanding follow-ups at most. */
    public static final int LIGHT_BATCH_TICKS = 20;

    private final ServerWorld world;
    private final Predicate<ServerPlayerEntity> predicting;
    /** Section key → 4096-bit set of cells changed and not yet sent, in first-change order. */
    private final Long2ObjectLinkedOpenHashMap<long[]> sections = new Long2ObjectLinkedOpenHashMap<>();
    /** Column ({@code ChunkPos.toLong}) → the flush tick it was last resent in. */
    private final Long2LongOpenHashMap lastResend = new Long2LongOpenHashMap();
    /** Resent columns waiting for the light engine before their light (and their neighbours') is sent. */
    private final Long2ObjectLinkedOpenHashMap<LightFollowUp> lightPending = new Long2ObjectLinkedOpenHashMap<>();
    /** Columns whose light is to be sent (their follow-ups are done), and the tick the batch began. */
    private final LongLinkedOpenHashSet lightDue = new LongLinkedOpenHashSet();
    private long lightDueSince;
    /** Columns the next flush sends whatever the resend interval (a job there ended). */
    private final LongOpenHashSet flushNext = new LongOpenHashSet();
    /** Columns written through vanilla block updates this tick outside this sync (see {@link #vanillaWrite}). */
    private final LongOpenHashSet vanillaWritten = new LongOpenHashSet();
    private boolean loggedFailure;
    private boolean loggedLightFailure;
    private long columnsResent;
    private long cellsMarked;
    private long lightColumnsSent;
    private long lightRearms;

    /**
     * Light-engine markers queued behind a resend (or behind later writes near one) on the column and its loaded
     * neighbours, and the tick they were queued in. {@link #rearm}: more writes near the column came after these
     * markers were queued, so once they have run, fresh markers are queued behind those writes.
     */
    private static final class LightFollowUp {
        final CompletableFuture<?>[] markers;
        final long tick;
        boolean rearm;

        LightFollowUp(CompletableFuture<?>[] markers, long tick) {
            this.markers = markers;
            this.tick = tick;
        }

        boolean done(long now) {
            if (now - tick > LIGHT_WAIT_LIMIT_TICKS) return true;
            for (CompletableFuture<?> marker : markers) {
                if (!marker.isDone()) return false;
            }
            return true;
        }
    }

    /**
     * @param predicting players who may hold unacknowledged predicted block changes; they get per-block updates
     */
    public ClientSync(ServerWorld world, Predicate<ServerPlayerEntity> predicting) {
        this.world = Objects.requireNonNull(world);
        this.predicting = Objects.requireNonNull(predicting);
    }

    public ServerWorld world() {
        return world;
    }

    /** Notes a changed cell; sent by a later {@link #flush}. */
    public void changed(BlockPos pos) {
        long key = BlockBuffer.keyOfBlock(pos.getX(), pos.getY(), pos.getZ());
        long[] bits = sections.get(key);
        if (bits == null) {
            bits = new long[SectionBuffer.SIZE / 64];
            sections.put(key, bits);
        }
        int i = SectionBuffer.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
        bits[i >>> 6] |= 1L << i;
    }

    /** Whether cells are waiting to be sent. */
    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /**
     * The next {@link #flush} sends the columns of these sections however recently they were resent (the job that
     * wrote them has ended, so its last cells go out now).
     */
    public void flushNext(long[] sectionKeys) {
        for (long key : sectionKeys) flushNext.add(ChunkPos.toLong(BlockBuffer.keyX(key), BlockBuffer.keyZ(key)));
    }

    /**
     * Notes that a cell of column (cx, cz) changed and reaches clients as a vanilla block update, written by code that
     * does not go through this sync (brush strokes, physics-on jobs). Clients relight such cells themselves, so a
     * pending light follow-up near it must not send light taken before the light engine has processed it: the next
     * {@link #flush} re-arms the follow-ups near the column. Returns at once while no follow-up is outstanding.
     */
    public void vanillaWrite(int cx, int cz) {
        if (lightPending.isEmpty() && lightDue.isEmpty()) return;
        vanillaWritten.add(ChunkPos.toLong(cx, cz));
    }

    /** Columns sent whole so far (to at least one player). */
    public long columnsResent() {
        return columnsResent;
    }

    /** Cells handed to vanilla's per-block updates so far. */
    public long cellsMarked() {
        return cellsMarked;
    }

    /** Columns whose light has been sent after a resend so far (to at least one player). */
    public long lightColumnsSent() {
        return lightColumnsSent;
    }

    /** Times vanilla-path writes near an outstanding light follow-up queued markers behind them. */
    public long lightRearms() {
        return lightRearms;
    }

    /** Light follow-ups still waiting for the light engine, plus columns whose light is due but not sent yet. */
    public int lightPending() {
        return lightPending.size() + lightDue.size();
    }

    /**
     * Sends what was noted (see the class comment), except heavily changed columns resent less than
     * {@value #RESEND_INTERVAL_TICKS} ticks before {@code tick}, which are kept for a later flush.
     *
     * @param tick a counter that grows by one per server tick
     * @param force send every column now (server stop)
     */
    public void flush(long tick, boolean force) {
        if (!lastResend.isEmpty()) lastResend.values().removeIf(at -> tick - at >= RESEND_INTERVAL_TICKS);
        sendLight(tick);
        ServerChunkManager manager = world.getChunkManager();
        if (!vanillaWritten.isEmpty()) {
            // After sendLight: the markers queued here sit behind every write made this tick.
            for (long column : vanillaWritten) {
                rearmLightNear(manager, ChunkPos.getPackedX(column), ChunkPos.getPackedZ(column), tick);
            }
            vanillaWritten.clear();
        }
        try {
            flushSections(manager, tick, force);
        } finally {
            flushNext.clear();
        }
    }

    private void flushSections(ServerChunkManager manager, long tick, boolean all) {
        if (sections.isEmpty()) return;
        // Group the sections by column, keeping first-change order.
        Long2ObjectLinkedOpenHashMap<LongArrayList> columns = new Long2ObjectLinkedOpenHashMap<>();
        for (long key : sections.keySet()) {
            long column = ChunkPos.toLong(BlockBuffer.keyX(key), BlockBuffer.keyZ(key));
            columns.computeIfAbsent(column, c -> new LongArrayList()).add(key);
        }
        for (Long2ObjectMap.Entry<LongArrayList> entry : columns.long2ObjectEntrySet()) {
            long column = entry.getLongKey();
            LongArrayList keys = entry.getValue();
            try {
                if (!flushColumn(manager, column, keys, tick, all)) continue; // kept for a later flush
            } catch (RuntimeException e) {
                if (!loggedFailure) {
                    loggedFailure = true;
                    LOG.error("Sculptory could not send chunk {},{} as a whole; using block updates",
                            ChunkPos.getPackedX(column), ChunkPos.getPackedZ(column), e);
                }
                try {
                    markForVanilla(manager, keys);
                    rearmLightNear(manager, ChunkPos.getPackedX(column), ChunkPos.getPackedZ(column), tick);
                } catch (RuntimeException again) {
                    LOG.error("Sculptory could not mark edited cells for block updates", again);
                }
            }
            for (int k = 0; k < keys.size(); k++) sections.remove(keys.getLong(k));
        }
    }

    /** @return false when the column is kept for a later flush (resent too recently) */
    private boolean flushColumn(ServerChunkManager manager, long column, LongArrayList keys, long tick, boolean all) {
        int cells = 0;
        for (int k = 0; k < keys.size(); k++) cells += count(sections.get(keys.getLong(k)));
        int cx = ChunkPos.getPackedX(column), cz = ChunkPos.getPackedZ(column);
        WorldChunk chunk = cells < RESEND_COLUMN_CELLS ? null : manager.getWorldChunk(cx, cz);
        if (chunk == null) {
            markForVanilla(manager, keys);
            rearmLightNear(manager, cx, cz, tick);
            return true;
        }
        if (!all && !flushNext.contains(column) && lastResend.containsKey(column)) return false;
        List<ServerPlayerEntity> watchers = manager.chunkLoadingManager.getPlayersWatchingChunk(new ChunkPos(cx, cz), false);
        ChunkDataS2CPacket whole = null;
        boolean sentWhole = false;
        ShortSet[] positions = new ShortSet[keys.size()];
        try {
            for (ServerPlayerEntity player : watchers) {
                if (predicting.test(player)) {
                    sendLikeVanilla(player, chunk, keys, positions);
                } else {
                    if (whole == null) whole = new ChunkDataS2CPacket(chunk, manager.getLightingProvider(), null, null);
                    player.networkHandler.sendPacket(whole);
                    sentWhole = true;
                }
            }
        } finally {
            if (sentWhole) {
                // Also when a later player's packets failed: whoever got the column needs its light.
                columnsResent++;
                scheduleLight(manager, cx, cz, tick);
            } else if (!watchers.isEmpty()) {
                // Everyone got block updates, which clients relight themselves: as for any vanilla-path write.
                rearmLightNear(manager, cx, cz, tick);
            }
        }
        lastResend.put(column, tick);
        return true;
    }

    /**
     * After a resend, the column's light (as the packet had it) predates the light engine's work on the new blocks,
     * and vanilla sends light updates only to players at the edge of their view (the others relight from block
     * updates, which a column packet does not trigger on the client). So once the light engine has run a marker
     * queued behind the edit on the column and each loaded neighbour, {@link #sendLight} sends their light.
     *
     * <p>A column that already has a follow-up keeps it (so it completes even while the column is resent every
     * {@value #RESEND_INTERVAL_TICKS} ticks and the light engine lags) and is re-armed: fresh markers are queued once
     * it has run.
     */
    private void scheduleLight(ServerChunkManager manager, int cx, int cz, long tick) {
        long key = ChunkPos.toLong(cx, cz);
        LightFollowUp existing = lightPending.get(key);
        if (existing != null) {
            existing.rearm = true;
            return;
        }
        lightPending.put(key, new LightFollowUp(markers(manager, cx, cz), tick));
    }

    private static CompletableFuture<?>[] markers(ServerChunkManager manager, int cx, int cz) {
        ServerLightingProvider light = manager.getLightingProvider();
        List<CompletableFuture<?>> markers = new ArrayList<>(9);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (manager.getWorldChunk(cx + dx, cz + dz) != null) markers.add(light.enqueue(cx + dx, cz + dz));
            }
        }
        return markers.toArray(new CompletableFuture<?>[0]);
    }

    /**
     * Cells of column (cx, cz) went out as vanilla block updates, which clients relight themselves. If a light
     * follow-up whose packets can cover the column's neighbourhood is outstanding (a resent column up to two columns
     * away, or a due column next to it), its light could be taken before the light engine has processed these cells
     * and would then overwrite the clients' own relight. So markers are queued behind these cells too, and the column's
     * neighbourhood is sent again once they have run.
     */
    private void rearmLightNear(ServerChunkManager manager, int cx, int cz, long tick) {
        if (lightPending.isEmpty() && lightDue.isEmpty()) return;
        boolean near = false;
        for (int dx = -2; dx <= 2 && !near; dx++) {
            for (int dz = -2; dz <= 2 && !near; dz++) {
                long column = ChunkPos.toLong(cx + dx, cz + dz);
                near = lightPending.containsKey(column)
                        || (Math.abs(dx) <= 1 && Math.abs(dz) <= 1 && lightDue.contains(column));
            }
        }
        if (near && manager.getWorldChunk(cx, cz) != null) {
            lightRearms++;
            scheduleLight(manager, cx, cz, tick);
        }
    }

    /**
     * Moves the columns whose light-engine markers have run, with their neighbours (light spreads up to 15 blocks), to
     * the columns whose light is due, and sends those to every player watching them once no follow-up is outstanding,
     * or {@value #LIGHT_BATCH_TICKS} ticks after the batch began. Batching sends each column once however many
     * follow-ups around it finished at different times. At most {@value #MAX_LIGHT_COLUMNS_PER_FLUSH} columns are
     * sent per flush; an open batch keeps draining at that rate.
     */
    private void sendLight(long tick) {
        if (lightPending.isEmpty() && lightDue.isEmpty()) return;
        LongArrayList rearmed = null;
        var entries = lightPending.long2ObjectEntrySet().iterator();
        while (entries.hasNext()) {
            Long2ObjectMap.Entry<LightFollowUp> entry = entries.next();
            LightFollowUp followUp = entry.getValue();
            if (!followUp.done(tick)) continue;
            long column = entry.getLongKey(); // read before remove(): the entry may be reused afterwards
            entries.remove();
            if (followUp.rearm) {
                if (rearmed == null) rearmed = new LongArrayList();
                rearmed.add(column);
            }
            if (lightDue.isEmpty()) lightDueSince = tick;
            int cx = ChunkPos.getPackedX(column), cz = ChunkPos.getPackedZ(column);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) lightDue.add(ChunkPos.toLong(cx + dx, cz + dz));
            }
        }
        ServerChunkManager manager = world.getChunkManager();
        if (rearmed != null) {
            for (int i = 0; i < rearmed.size(); i++) {
                long column = rearmed.getLong(i);
                int cx = ChunkPos.getPackedX(column), cz = ChunkPos.getPackedZ(column);
                if (manager.getWorldChunk(cx, cz) != null) {
                    lightPending.put(column, new LightFollowUp(markers(manager, cx, cz), tick));
                }
            }
        }
        if (lightDue.isEmpty() || (!lightPending.isEmpty() && tick - lightDueSince < LIGHT_BATCH_TICKS)) return;
        int sent = 0;
        while (sent < MAX_LIGHT_COLUMNS_PER_FLUSH && !lightDue.isEmpty()) {
            long column = lightDue.removeFirstLong();
            try {
                ChunkPos pos = new ChunkPos(column);
                if (manager.getWorldChunk(pos.x, pos.z) == null) continue;
                List<ServerPlayerEntity> watchers = manager.chunkLoadingManager.getPlayersWatchingChunk(pos, false);
                if (watchers.isEmpty()) continue;
                LightUpdateS2CPacket packet = new LightUpdateS2CPacket(pos, manager.getLightingProvider(), null, null);
                for (ServerPlayerEntity player : watchers) player.networkHandler.sendPacket(packet);
                lightColumnsSent++;
                sent++;
            } catch (RuntimeException e) {
                if (!loggedLightFailure) {
                    loggedLightFailure = true;
                    LOG.error("Sculptory could not send the light of an edited chunk", e);
                }
            }
        }
    }

    private void markForVanilla(ServerChunkManager manager, LongArrayList keys) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int k = 0; k < keys.size(); k++) {
            long key = keys.getLong(k);
            forEachCell(key, sections.get(key), (x, y, z) -> {
                manager.markForUpdate(pos.set(x, y, z));
                cellsMarked++;
            });
        }
    }

    /**
     * What {@code ChunkHolder.flushUpdates} would send this player for the cells: one block update or one delta per
     * section, then the block entity update of each changed cell that has one. {@code positions} caches each section's
     * packed positions for the column's other players.
     */
    private void sendLikeVanilla(ServerPlayerEntity player, WorldChunk chunk, LongArrayList keys, ShortSet[] positions) {
        for (int k = 0; k < keys.size(); k++) {
            long key = keys.getLong(k);
            int sy = BlockBuffer.keyY(key);
            ChunkSectionPos sectionPos = ChunkSectionPos.from(BlockBuffer.keyX(key), sy, BlockBuffer.keyZ(key));
            if (positions[k] == null) positions[k] = packedPositions(sections.get(key));
            ShortSet cells = positions[k];
            if (cells.size() == 1) {
                BlockPos pos = sectionPos.unpackBlockPos(cells.iterator().nextShort());
                BlockState state = world.getBlockState(pos);
                player.networkHandler.sendPacket(new BlockUpdateS2CPacket(pos, state));
                sendBlockEntity(player, pos, state);
            } else {
                ChunkSection section = chunk.getSection(world.sectionCoordToIndex(sy));
                ChunkDeltaUpdateS2CPacket delta = new ChunkDeltaUpdateS2CPacket(sectionPos, cells, section);
                player.networkHandler.sendPacket(delta);
                delta.visitUpdates((pos, state) -> sendBlockEntity(player, pos, state));
            }
        }
    }

    /** The cells as {@code ChunkSectionPos.packLocal} shorts: {@code x << 8 | z << 4 | y}. */
    private static ShortSet packedPositions(long[] bits) {
        ShortSet set = new ShortOpenHashSet();
        for (int w = 0; w < bits.length; w++) {
            long word = bits[w];
            while (word != 0) {
                int i = (w << 6) | Long.numberOfTrailingZeros(word);
                word &= word - 1;
                set.add((short) (SectionBuffer.localX(i) << 8 | SectionBuffer.localZ(i) << 4 | SectionBuffer.localY(i)));
            }
        }
        return set;
    }

    private void sendBlockEntity(ServerPlayerEntity player, BlockPos pos, BlockState state) {
        if (!state.hasBlockEntity()) return;
        BlockEntity entity = world.getBlockEntity(pos);
        if (entity == null) return;
        Packet<?> update = entity.toUpdatePacket();
        if (update != null) player.networkHandler.sendPacket(update);
    }

    @FunctionalInterface
    private interface CellVisitor {
        void visit(int x, int y, int z);
    }

    private static void forEachCell(long key, long[] bits, CellVisitor visitor) {
        int ox = BlockBuffer.keyX(key) << 4, oy = BlockBuffer.keyY(key) << 4, oz = BlockBuffer.keyZ(key) << 4;
        for (int w = 0; w < bits.length; w++) {
            long word = bits[w];
            while (word != 0) {
                int i = (w << 6) | Long.numberOfTrailingZeros(word);
                word &= word - 1;
                visitor.visit(ox + SectionBuffer.localX(i), oy + SectionBuffer.localY(i), oz + SectionBuffer.localZ(i));
            }
        }
    }

    private static int count(long[] bits) {
        int n = 0;
        for (long word : bits) n += Long.bitCount(word);
        return n;
    }
}
