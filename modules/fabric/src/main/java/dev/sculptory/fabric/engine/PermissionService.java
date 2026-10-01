package dev.sculptory.fabric.engine;

import dev.sculptory.core.Box;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Permission nodes and per-chunk protection. The singleplayer host is always allowed; otherwise nodes are
 * checked with a fallback to op level 2.
 */
public interface PermissionService {
    boolean has(ServerPlayerEntity p, Perm node);

    /**
     * Protection for the part of {@code bounds} inside chunk (cx, cz), from {@code canPlayerModifyAt} on its
     * four corner columns: all allowed, all denied, or per-column when mixed.
     */
    ChunkPermit chunk(ServerPlayerEntity p, ServerWorld w, int cx, int cz, Box bounds);
}
