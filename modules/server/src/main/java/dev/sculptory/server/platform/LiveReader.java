package dev.sculptory.server.platform;

import dev.sculptory.core.world.WorldReader;

/**
 * A {@link WorldReader} over one of the platform's live worlds ({@link Platform#reader}), for the server thread only.
 * It never loads chunks: reading an unloaded chunk throws {@link IllegalStateException} (callers check
 * {@link #isLoaded} first). It may cache the chunks it read last, so per-cell reads resolve each chunk once.
 */
public interface LiveReader extends WorldReader {
    /** Drops what it cached: chunks may have unloaded since (the executor calls it at the start of every slice). */
    void invalidate();
}
