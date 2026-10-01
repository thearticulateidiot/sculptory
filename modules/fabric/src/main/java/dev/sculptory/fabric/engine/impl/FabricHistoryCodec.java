package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.history.store.CorruptDataException;
import dev.sculptory.core.history.store.HistoryCodec;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.schem.SanitizedTile;
import dev.sculptory.fabric.schem.TileSanitizer;
import dev.sculptory.fabric.world.FabricTile;
import java.io.IOException;
import java.util.Objects;

/**
 * How the server's saved undo history names states and carries block entities: states by their text
 * ({@link StateSpace#format}, resolved again with {@link StateSpace#parse} when loaded, so a registry change between runs
 * is harmless), block entities with the trust they had when recorded. A server-captured {@link FabricTile} comes back
 * server-captured and a {@link SanitizedTile} comes back sanitized (cleaned again), so undo and redo after a restart
 * write exactly what they would have before it; anything else comes back as plain {@link NbtBytes} (foreign, subject to
 * operator-NBT stripping as before). The journal is the server's own file in the world folder, as trusted as the chunks.
 *
 * <p>Called from the history store's I/O thread: the state space is immutable once built, and the tile factories only
 * decode bytes.
 */
public final class FabricHistoryCodec implements HistoryCodec {
    /** Largest block-entity NBT read back for one cell. */
    static final long MAX_TILE_BYTES = 64L << 20;

    private final StateSpace states;
    private final int dataVersion;

    public FabricHistoryCodec(StateSpace states, int dataVersion) {
        this.states = Objects.requireNonNull(states);
        this.dataVersion = dataVersion;
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    @Override
    public String stateText(int handle) {
        return states.format(handle);
    }

    @Override
    public int state(String text) {
        return states.parse(text);
    }

    @Override
    public Trust trust(BlockEntityData tile) {
        if (FabricTile.isServerCaptured(tile)) return Trust.CAPTURED;
        if (tile instanceof SanitizedTile) return Trust.SANITIZED;
        return Trust.FOREIGN;
    }

    @Override
    public BlockEntityData tile(String typeId, byte[] nbt, Trust trust) throws CorruptDataException {
        return switch (trust) {
            case CAPTURED -> {
                try {
                    yield FabricTile.restoreCaptured(typeId, nbt, MAX_TILE_BYTES);
                } catch (IOException e) {
                    throw new CorruptDataException("block entity " + typeId + " does not decode", e);
                }
            }
            case SANITIZED -> TileSanitizer.restoredSign(typeId, nbt);
            case FOREIGN -> new NbtBytes(typeId, nbt);
        };
    }
}
