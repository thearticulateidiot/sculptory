package dev.sculptory.fabric.client.session;

import java.util.Objects;
import java.util.UUID;

/**
 * A clipboard exported by the server as a Sponge v3 {@code .schem} ({@code SCHEM_FILE} stream).
 *
 * @param fileName the name the server suggests ({@code clipboard.schem}); not yet safe for the file system
 * @param bytes the file; not copied
 */
public record ExportedFile(UUID clipboardId, String fileName, byte[] bytes) {
    public ExportedFile {
        Objects.requireNonNull(clipboardId);
        Objects.requireNonNull(fileName);
        Objects.requireNonNull(bytes);
    }
}
