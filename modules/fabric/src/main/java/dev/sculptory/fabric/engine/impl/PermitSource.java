package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.PermissionService;
import java.util.Objects;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * Per-chunk protection for one job, asked once per chunk column the job writes. For a player this is
 * {@link #forPlayer}: {@code permissions.chunk(player, world, cx, cz, program.bounds())}.
 */
@FunctionalInterface
public interface PermitSource {
    PermitSource ALLOW_ALL = (cx, cz) -> ChunkPermit.ALLOW;

    ChunkPermit chunk(int cx, int cz);

    /**
     * Whether the job may change column (x, z) wherever it lies, also outside the chunks it was admitted for (entities
     * are found by their UUID and may have moved). The default asks {@link #chunk}.
     */
    default boolean mayChangeColumn(int x, int z) {
        ChunkPermit permit = chunk(x >> 4, z >> 4);
        return permit != null && permit.allows(x, z);
    }

    /**
     * A player's permits for a job over {@code bounds}: {@link #chunk} by the corner rule over the part of the bounds in
     * that chunk; {@link #mayChangeColumn} for the column itself, wherever it is.
     */
    static PermitSource forPlayer(PermissionService<ServerPlayerEntity, ServerWorld> permissions, ServerPlayerEntity player, ServerWorld world,
                                  Box bounds) {
        Objects.requireNonNull(permissions);
        Objects.requireNonNull(bounds);
        return new PermitSource() {
            @Override
            public ChunkPermit chunk(int cx, int cz) {
                return permissions.chunk(player, world, cx, cz, bounds);
            }

            @Override
            public boolean mayChangeColumn(int x, int z) {
                BlockPos cell = new BlockPos(x, bounds.min().y(), z);
                return permissions.chunk(player, world, x >> 4, z >> 4, new Box(cell, cell)).allows(x, z);
            }
        };
    }
}
