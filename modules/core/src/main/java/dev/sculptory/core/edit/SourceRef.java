package dev.sculptory.core.edit;

import dev.sculptory.core.Sha256;
import java.util.Objects;
import java.util.UUID;

/** A server-held paste source. */
public sealed interface SourceRef {
    /** A clipboard held by the server for the player. */
    record Clipboard(UUID id) implements SourceRef {
        public Clipboard {
            Objects.requireNonNull(id);
        }
    }

    /** A library asset, by the lowercase hex SHA-256 of its content. */
    record Asset(String contentHash) implements SourceRef {
        public Asset {
            new Sha256(contentHash);
        }
    }
}
