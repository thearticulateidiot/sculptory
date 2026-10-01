package dev.sculptory.fabric.client.editor.brush;

import java.util.Objects;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

/**
 * {@link BrushPredictor.Target} over the client world. Each scope is one vanilla prediction sequence:
 * {@code getPendingUpdateManager().incrementSequence()} opens it, every predicted cell is written with
 * {@code setBlockState(pos, state, NOTIFY_ALL | FORCE_STATE, 0)} (which records the old state for rollback
 * while the sequence is open), and {@code close()} ends it. When the server acknowledges the sequence,
 * vanilla replaces each predicted cell with the state the server sent for it, or restores the old one.
 *
 * <p>State handles are {@code Block.getRawIdFromState} ids (the Fabric state space), so
 * {@link Block#getStateFromRawId} maps them back. Only use this with the Fabric session and state space.
 * Render thread only.
 */
public final class ClientWorldPrediction implements BrushPredictor.Target {
    private static final int FLAGS = Block.NOTIFY_ALL | Block.FORCE_STATE;

    private final MinecraftClient client;

    public ClientWorldPrediction(MinecraftClient client) {
        this.client = Objects.requireNonNull(client);
    }

    @Override
    public BrushPredictor.Scope open() {
        ClientWorld world = client.world;
        if (world == null || client.player == null) {
            return null;
        }
        PendingUpdateManager pending = world.getPendingUpdateManager().incrementSequence();
        return new Scope(world, pending);
    }

    private static final class Scope implements BrushPredictor.Scope {
        private final ClientWorld world;
        private final PendingUpdateManager pending;
        private final BlockPos.Mutable pos = new BlockPos.Mutable();

        Scope(ClientWorld world, PendingUpdateManager pending) {
            this.world = world;
            this.pending = pending;
        }

        @Override
        public int sequence() {
            return pending.getSequence();
        }

        @Override
        public void set(int x, int y, int z, int handle) {
            world.setBlockState(pos.set(x, y, z), Block.getStateFromRawId(handle), FLAGS, 0);
        }

        @Override
        public void close() {
            pending.close();
        }
    }
}
