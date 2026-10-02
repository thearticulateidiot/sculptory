package dev.sculptory.server.engine;

import dev.sculptory.core.Box;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.Perm;

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
     * Protection for the part of {@code bounds} inside chunk (cx, cz), from {@code canPlayerModifyAt} on its
     * four corner columns: all allowed, all denied, or per-column when mixed.
     */
    ChunkPermit chunk(P p, W w, int cx, int cz, Box bounds);
}
