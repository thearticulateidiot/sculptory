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
     * Protection for the part of {@code bounds} inside chunk (cx, cz). Consumers ask
     * {@link ChunkPermit#allows(int, int, int)} for each cell they write, at its own height, and only for cells inside
     * {@code bounds} (a job asks with its program's bounds, biome work with them widened to whole quarts, a brush with
     * its dab boxes; GameTests check this with {@code -Dsculptory.checkPermitBounds=true}).
     *
     * <p>The producer contract: the answer must be right for every cell of that part, at every height in
     * {@code bounds}. {@link ChunkPermit#ALLOW}, {@link ChunkPermit#DENY} and {@link ChunkPermit.Columns} are applied at
     * any height; {@link ChunkPermit.Cells} answers its layers and denies outside them.
     * <ul>
     *   <li>Protection the same at every height (spawn protection, claims through {@code canPlayerModifyAt}) may use
     *       the column rule {@code ChunkPermits.forChunk} with a column test: four corner columns and a focus column,
     *       then every column when mixed (its documented limits: an area touching neither is missed).
     *   <li>Protection that may differ with height (regions limited in height, nested regions with priorities) must use
     *       {@code ChunkPermits.rasterize} (every cell asked), unless the producer knows the part's answer is the same
     *       everywhere (no region intersects it), when {@code ChunkPermits.uniform} or a plain ALLOW/DENY will do.
     *       There is no sampling shortcut in three dimensions.
     * </ul>
     */
    ChunkPermit chunk(P p, W w, int cx, int cz, Box bounds);
}
