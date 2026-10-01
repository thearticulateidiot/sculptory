package dev.sculptory.fabric.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A resent chunk column refreshes the client's pending predictions inside it to the loaded states, as a block update
 * would, so a later acknowledgement puts back what the server has now (not what it had before the column).
 */
class ResentChunksTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    /** Adds a pending update directly (vanilla's own path needs a client player for its position). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void pend(PendingUpdateManager pending, BlockPos pos, BlockState rollback) throws Exception {
        Class<?> type = Class.forName("net.minecraft.client.network.PendingUpdateManager$PendingUpdate");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, BlockState.class, Vec3d.class);
        constructor.setAccessible(true);
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap map = pending.blockPosToPendingUpdate;
        map.put(pos.asLong(), constructor.newInstance(7, rollback, Vec3d.ZERO));
    }

    private static BlockState rollbackState(PendingUpdateManager pending, BlockPos pos) throws Exception {
        Object update = pending.blockPosToPendingUpdate.get(pos.asLong());
        Field field = update.getClass().getDeclaredField("blockState");
        field.setAccessible(true);
        return (BlockState) field.get(update);
    }

    @Test
    void pendingPredictionsInsideTheColumnTakeTheLoadedStates() throws Exception {
        PendingUpdateManager pending = new PendingUpdateManager();
        BlockPos inside = new BlockPos(16 * 3 + 5, 70, 16 * -2 + 9);
        BlockPos alsoInside = new BlockPos(16 * 3, -60, 16 * -2 + 15);
        BlockPos outside = new BlockPos(16 * 4 + 1, 70, 16 * -2 + 9);
        pend(pending, inside, Blocks.DIRT.getDefaultState());
        pend(pending, alsoInside, Blocks.DIRT.getDefaultState());
        pend(pending, outside, Blocks.DIRT.getDefaultState());

        int refreshed = ResentChunks.refreshPending(pending, 3, -2, pos -> Blocks.STONE.getDefaultState());

        assertEquals(2, refreshed);
        assertSame(Blocks.STONE.getDefaultState(), rollbackState(pending, inside));
        assertSame(Blocks.STONE.getDefaultState(), rollbackState(pending, alsoInside));
        assertSame(Blocks.DIRT.getDefaultState(), rollbackState(pending, outside), "another column is left alone");
        assertEquals(3, pending.blockPosToPendingUpdate.size(), "entries are updated, never added or removed");
    }

    @Test
    void nothingPendingIsANoOp() {
        assertEquals(0, ResentChunks.refreshPending(new PendingUpdateManager(), 0, 0, pos -> {
            throw new AssertionError("no state should be read");
        }));
    }
}
