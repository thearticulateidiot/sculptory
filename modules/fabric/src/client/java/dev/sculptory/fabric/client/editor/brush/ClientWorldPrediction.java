package dev.sculptory.fabric.client.editor.brush;

import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.prediction.BlockStatePredictionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * {@link BrushPredictor.Target} over the client world. Each scope is one vanilla prediction sequence:
 * {@code getPendingUpdateManager().incrementSequence()} opens it, every predicted cell is written with
 * {@code setBlockState(pos, state, NOTIFY_ALL | FORCE_STATE, 0)} (which records the old state for rollback
 * while the sequence is open), and {@code close()} ends it. When the server acknowledges the sequence,
 * vanilla replaces each predicted cell with the state the server sent for it, or restores the old one.
 *
 * <p>State handles are {@code Block.getRawIdFromState} ids (the Fabric state space), so
 * {@link Block#stateById} maps them back. Only use this with the Fabric session and state space.
 * Render thread only.
 */
public final class ClientWorldPrediction implements BrushPredictor.Target {
    private static final int FLAGS = Block.UPDATE_ALL | Block.UPDATE_KNOWN_SHAPE;

    private final Minecraft client;

    public ClientWorldPrediction(Minecraft client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public BrushPredictor.Scope open() {
        ClientLevel world = client.level;
        if (world == null || client.player == null) {
            return null;
        }
        BlockStatePredictionHandler pending = world.getBlockStatePredictionHandler().startPredicting();
        return new Scope(world, pending);
    }

    private static final class Scope implements BrushPredictor.Scope {
        private final ClientLevel world;
        private final BlockStatePredictionHandler pending;
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        Scope(ClientLevel world, BlockStatePredictionHandler pending) {
            this.world = world;
            this.pending = pending;
        }

        @Override
        public int sequence() {
            return pending.currentSequence();
        }

        @Override
        public void set(int x, int y, int z, int handle) {
            world.setBlock(pos.set(x, y, z), Block.stateById(handle), FLAGS, 0);
        }

        @Override
        public void close() {
            pending.close();
        }
    }
}
