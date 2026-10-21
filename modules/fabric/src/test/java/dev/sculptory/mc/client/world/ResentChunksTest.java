package dev.sculptory.mc.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A resent chunk column refreshes the client's pending predictions inside it to the loaded states, as a block update
 * would, so a later acknowledgement puts back what the server has now (not what it had before the column).
 */
class ResentChunksTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    /** Adds a pending update directly (vanilla's own path needs a client player for its position). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void pend(BlockStatePredictionHandler pending, BlockPos pos, BlockState rollback) throws Exception {
        Class<?> type = Class.forName("net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler$ServerVerifiedState");
        Constructor<?> constructor = type.getDeclaredConstructor(int.class, BlockState.class, Vec3.class);
        constructor.setAccessible(true);
        it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap map = pending.serverVerifiedStates;
        map.put(pos.asLong(), constructor.newInstance(7, rollback, Vec3.ZERO));
    }

    private static BlockState rollbackState(BlockStatePredictionHandler pending, BlockPos pos) throws Exception {
        Object update = pending.serverVerifiedStates.get(pos.asLong());
        Field field = update.getClass().getDeclaredField("blockState");
        field.setAccessible(true);
        return (BlockState) field.get(update);
    }

    @Test
    void pendingPredictionsInsideTheColumnTakeTheLoadedStates() throws Exception {
        BlockStatePredictionHandler pending = new BlockStatePredictionHandler();
        BlockPos inside = new BlockPos(16 * 3 + 5, 70, 16 * -2 + 9);
        BlockPos alsoInside = new BlockPos(16 * 3, -60, 16 * -2 + 15);
        BlockPos outside = new BlockPos(16 * 4 + 1, 70, 16 * -2 + 9);
        pend(pending, inside, Blocks.DIRT.defaultBlockState());
        pend(pending, alsoInside, Blocks.DIRT.defaultBlockState());
        pend(pending, outside, Blocks.DIRT.defaultBlockState());

        int refreshed = ResentChunks.refreshPending(pending, 3, -2, pos -> Blocks.STONE.defaultBlockState());

        assertEquals(2, refreshed);
        assertSame(Blocks.STONE.defaultBlockState(), rollbackState(pending, inside));
        assertSame(Blocks.STONE.defaultBlockState(), rollbackState(pending, alsoInside));
        assertSame(Blocks.DIRT.defaultBlockState(), rollbackState(pending, outside), "another column is left alone");
        assertEquals(3, pending.serverVerifiedStates.size(), "entries are updated, never added or removed");
    }

    @Test
    void nothingPendingIsANoOp() {
        assertEquals(0, ResentChunks.refreshPending(new BlockStatePredictionHandler(), 0, 0, pos -> {
            throw new AssertionError("no state should be read");
        }));
    }
}
