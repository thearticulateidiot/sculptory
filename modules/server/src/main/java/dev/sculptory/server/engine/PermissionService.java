package dev.sculptory.server.engine;

import dev.sculptory.core.Box;

/**
 * Permission nodes and per-chunk protection. The singleplayer host is always allowed; otherwise nodes are
 * checked with a fallback to op level 2.
 *
 * @param <P> the platform's player type
 * @param <W> the platform's world type
 */
public interface PermissionService<P, W> {
    boolean has(P p, Perm node);

    /**
     * Protection for the part of {@code bounds} inside chunk (cx, cz): all allowed, all denied, per column when mixed
     * ({@link ChunkPermit.Columns}, protection the same at every height: {@code canPlayerModifyAt} on its four corner
     * columns, {@code ChunkPermits.forChunk} with a column test), or per cell over the bounds' heights
     * ({@link ChunkPermit.Cells}, protection limited in height: {@code ChunkPermits.forChunk} with a cell test).
     * Consumers ask {@link ChunkPermit#allows(int, int, int)} for each cell, at its own height.
     */
    ChunkPermit chunk(P p, W w, int cx, int cz, Box bounds);
}
