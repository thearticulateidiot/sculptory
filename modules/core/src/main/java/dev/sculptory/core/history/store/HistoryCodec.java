package dev.sculptory.core.history.store;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.state.StateSpace;
import java.util.Objects;

/**
 * How stored history names block states and carries block entities. State handles are only valid within one run of
 * the game (registry sync and mods can move them), so the journal stores each state as its text
 * ({@link StateSpace#format}) and resolves it again when an entry is loaded. Block entities are stored as their type
 * id and uncompressed binary NBT, with their {@link Trust}, so a restored tile is treated exactly as the one recorded
 * (server-captured tiles are restored as-is for everyone; foreign ones are still subject to operator-NBT stripping).
 *
 * <p>Implementations must be safe to call from the store's I/O thread while the server thread runs.
 */
public interface HistoryCodec {
    /** Where a block entity's content came from, which decides whether it may be written as-is. Codes are stored. */
    enum Trust {
        /** Anything not known to be safe: files, the wire. Code 0. */
        FOREIGN,
        /** Read from this server's own world. Code 1. */
        CAPTURED,
        /** Sign text reduced to plain text by the schematic sanitizer. Code 2. */
        SANITIZED;

        static Trust of(int code) throws CorruptDataException {
            Trust[] values = values();
            if (code < 0 || code >= values.length) throw new CorruptDataException("Unknown tile trust " + code);
            return values[code];
        }
    }

    /**
     * The running game's data version. Entries recorded under another data version are not restored (block states and
     * NBT would need upgrading).
     */
    int dataVersion();

    /** A state's text, as {@link StateSpace#format}. */
    String stateText(int handle);

    /** A state's handle from its text, or -1 if the running game does not know it. */
    int state(String text);

    /** Where {@code tile} came from. */
    Trust trust(BlockEntityData tile);

    /**
     * Restores a stored block entity with the trust it was recorded with.
     *
     * @throws CorruptDataException if the NBT does not decode
     */
    BlockEntityData tile(String typeId, byte[] nbt, Trust trust) throws CorruptDataException;

    /**
     * A codec over {@code states} whose tiles are plain {@link NbtBytes} (every tile foreign), for code without a game
     * (tests).
     */
    static HistoryCodec of(StateSpace states, int dataVersion) {
        Objects.requireNonNull(states);
        return new HistoryCodec() {
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
                return Trust.FOREIGN;
            }

            @Override
            public BlockEntityData tile(String typeId, byte[] nbt, Trust trust) {
                return new NbtBytes(typeId, nbt);
            }
        };
    }
}
