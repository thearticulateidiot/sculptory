package dev.sculptory.core.schem;

import java.io.IOException;
import java.util.Objects;

/**
 * A schematic that cannot be read or written. NBT-level problems surface as
 * {@link dev.sculptory.core.nbt.NbtException} (and its {@code NbtLimitException}) instead.
 */
public final class SchematicException extends IOException {
    public enum Kind {
        /** Not a Sponge schematic version this codec reads (1-3). */
        UNSUPPORTED,
        /** Missing or ill-typed required fields, or inconsistent block data. */
        MALFORMED,
        /** Over a {@link SchematicCodec.Limits} limit, or too large for the format. */
        TOO_LARGE
    }

    private final Kind kind;

    public SchematicException(Kind kind, String message) {
        super(message);
        this.kind = Objects.requireNonNull(kind);
    }

    public SchematicException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind);
    }

    public Kind kind() {
        return kind;
    }
}
