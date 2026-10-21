package dev.sculptory.server.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.PermissionService;
import java.util.Objects;

/**
 * Per-chunk protection for one job, asked once per chunk column the job writes. For a player this is
 * {@link #forPlayer}: {@code permissions.chunk(player, world, cx, cz, program.bounds())}. Every answer is per cell
 * ({@link ChunkPermit#allows(int, int, int)}): protection may differ with height.
 */
@FunctionalInterface
public interface PermitSource {
    PermitSource ALLOW_ALL = (cx, cz) -> ChunkPermit.ALLOW;

    ChunkPermit chunk(int cx, int cz);

    /**
     * The permit for column (x, z) alone over the job's heights, wherever the column lies, also outside the chunks the job
     * was admitted for: for work that visits the cells of one column at several heights (biome quarts) and asks once per
     * column. The default is the column's chunk's {@link #chunk} permit.
     */
    default ChunkPermit column(int x, int z) {
        return chunk(x >> 4, z >> 4);
    }

    /**
     * Whether the job may change cell (x, y, z) wherever it lies, also outside the chunks it was admitted for (entities
     * are found by their UUID and may have moved). The default asks {@link #chunk}.
     */
    default boolean mayChange(int x, int y, int z) {
        ChunkPermit permit = chunk(x >> 4, z >> 4);
        return permit != null && permit.allows(x, y, z);
    }

    /**
     * A player's permits for a job over {@code bounds}: {@link #chunk} by the corner rule over the part of the bounds in
     * that chunk; {@link #column} for the column itself over the bounds' heights, and {@link #mayChange} for the cell
     * itself, wherever they are.
     */
    static <P, W> PermitSource forPlayer(PermissionService<P, W> permissions, P player, W world, Box bounds) {
        Objects.requireNonNull(permissions);
        Objects.requireNonNull(bounds);
        return new PermitSource() {
            @Override
            public ChunkPermit chunk(int cx, int cz) {
                return permissions.chunk(player, world, cx, cz, bounds);
            }

            @Override
            public ChunkPermit column(int x, int z) {
                Box column = new Box(new BlockPos(x, bounds.min().y(), z), new BlockPos(x, bounds.max().y(), z));
                ChunkPermit permit = permissions.chunk(player, world, x >> 4, z >> 4, column);
                return permit == null ? ChunkPermit.DENY : permit;
            }

            @Override
            public boolean mayChange(int x, int y, int z) {
                BlockPos cell = new BlockPos(x, y, z);
                ChunkPermit permit = permissions.chunk(player, world, x >> 4, z >> 4, new Box(cell, cell));
                return permit != null && permit.allows(x, y, z);
            }
        };
    }
}
