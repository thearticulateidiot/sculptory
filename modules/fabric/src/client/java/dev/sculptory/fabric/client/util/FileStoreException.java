package dev.sculptory.fabric.client.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** A typed {@link AtomicFileStore} failure. {@link #kind()} says what happened and whether to retry. */
public final class FileStoreException extends IOException {
    public enum Kind {
        /** The path exists but is a directory, link or other non-regular file. Nothing was changed. */
        NOT_REGULAR_FILE,
        /** The file on disk, or the bytes to save, exceed the store's size limit. Nothing was changed. */
        TOO_LARGE,
        /** The file couldn't be read. Nothing was changed. */
        READ_FAILED,
        /** {@code save} before a successful {@code load} of the path. Nothing was changed. */
        NOT_LOADED,
        /** The file changed on disk since it was loaded or saved. It was not overwritten; reload first. */
        CHANGED_EXTERNALLY,
        /** Another writer holds the file's lock. Nothing was changed; retry later. */
        BUSY,
        /** Nothing was published and the previous file is intact. Retrying is safe. */
        WRITE_FAILED,
        /**
         * Publishing was attempted and the file now matches neither the old nor the new bytes, or a
         * fatal error interrupted it. Saves to this path are refused until it is loaded again.
         */
        UNCERTAIN
    }

    private final Kind kind;
    private final Path path;

    public FileStoreException(Kind kind, Path path, String message, Throwable cause) {
        super(message, cause);
        this.kind = Objects.requireNonNull(kind);
        this.path = path;
    }

    public Kind kind() {
        return kind;
    }

    public Path path() {
        return path;
    }

    /** True if the same save may simply be tried again. */
    public boolean retryable() {
        return kind == Kind.BUSY || kind == Kind.WRITE_FAILED;
    }
}
