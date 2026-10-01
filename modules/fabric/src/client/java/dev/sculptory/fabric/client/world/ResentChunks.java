package dev.sculptory.fabric.client.world;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Keeps predicted block changes right when the server resends a whole chunk column (heavily changed columns of bulk jobs).
 *
 * <p>While a prediction (a brush dab, or vanilla's own block placing and breaking) waits for its acknowledgement, the
 * client's {@code PendingUpdateManager} holds, per predicted position, the state to put back when the ack arrives.
 * Block and delta updates refresh that state ({@code ClientWorld.handleBlockUpdate} → {@code hasPendingUpdate}); a
 * chunk packet does not, so an ack arriving after a resent column would put back the state from before the column
 * (for a refused dab inside a job's area: the block from before the job). After every chunk load (Fabric fires
 * {@code CHUNK_LOAD} for a column replaced in place too) this refreshes each pending position inside the column to the
 * state just loaded, as a delta update would have. Client thread only.
 *
 * <p>Checked with javap against yarn 1.21.1+build.3: {@code PendingUpdateManager.hasPendingUpdate(BlockPos,
 * BlockState)} sets a pending position's state and returns whether one was pending; its map is the private field
 * {@code blockPosToPendingUpdate} (widened in {@code sculptory.accesswidener}).
 */
public final class ResentChunks {
    private ResentChunks() {}

    /** Registers the chunk-load hook. Called once from the client initializer. */
    public static void install() {
        ClientChunkEvents.CHUNK_LOAD.register(ResentChunks::loaded);
    }

    private static void loaded(ClientWorld world, WorldChunk chunk) {
        refreshPending(world.getPendingUpdateManager(), chunk.getPos().x, chunk.getPos().z, chunk::getBlockState);
    }

    /**
     * Sets every pending position inside chunk column (cx, cz) to its state in {@code states} (the column just loaded).
     *
     * @return how many pending positions were refreshed
     */
    static int refreshPending(PendingUpdateManager pending, int cx, int cz, Function<BlockPos, BlockState> states) {
        Long2ObjectOpenHashMap<?> map = pending.blockPosToPendingUpdate;
        if (map.isEmpty()) return 0;
        int refreshed = 0;
        // hasPendingUpdate only changes the entry's state, never the map, so iterating the keys meanwhile is safe.
        LongIterator keys = map.keySet().iterator();
        while (keys.hasNext()) {
            long key = keys.nextLong();
            if ((BlockPos.unpackLongX(key) >> 4) != cx || (BlockPos.unpackLongZ(key) >> 4) != cz) continue;
            BlockPos pos = BlockPos.fromLong(key);
            if (pending.hasPendingUpdate(pos, states.apply(pos))) refreshed++;
        }
        return refreshed;
    }
}
