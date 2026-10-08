package dev.sculptory.core.history;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import java.util.List;
import java.util.Objects;

/**
 * What one edit changed: the cells (their states and tiles before and after; both buffers hold the same cells), the
 * entities (one {@link EntityChange} per entity, by UUID, each at most once) and, from protocol 6, the biomes (one
 * {@link BiomeChange} per quart, each at most once; empty for every edit until the biome tools land, and not yet saved
 * in the history journal).
 */
public record EditRecord(BlockBuffer before, BlockBuffer after, List<EntityChange> entities, List<BiomeChange> biomes) {
    public EditRecord {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        entities = List.copyOf(entities);
        biomes = List.copyOf(biomes);
    }

    /** A record of cells and entities, without biomes. */
    public EditRecord(BlockBuffer before, BlockBuffer after, List<EntityChange> entities) {
        this(before, after, entities, List.of());
    }

    /** A record of cells only. */
    public EditRecord(BlockBuffer before, BlockBuffer after) {
        this(before, after, List.of());
    }

    /** Whether the edit changed nothing: no cell, no entity and no biome. */
    public boolean isEmpty() {
        return before.isEmpty() && entities.isEmpty() && biomes.isEmpty();
    }

    /**
     * The smallest box holding every recorded cell, the block of every recorded entity position (before and after) and
     * every block of every recorded biome quart, or {@code null} for an empty record.
     */
    public Box bounds() {
        Box box = before.bounds();
        for (EntityChange change : entities) {
            if (change.before() != null) box = include(box, change.before());
            if (change.after() != null) box = include(box, change.after());
        }
        for (BiomeChange change : biomes) {
            box = include(box, new BlockPos(change.blockX(), change.blockY(), change.blockZ()));
            box = include(box, new BlockPos(change.blockX() + 3, change.blockY() + 3, change.blockZ() + 3));
        }
        return box;
    }

    private static Box include(Box box, EntityState state) {
        return include(box, new BlockPos(floor(state.x()), floor(state.y()), floor(state.z())));
    }

    private static Box include(Box box, BlockPos cell) {
        if (box == null) return new Box(cell, cell);
        return new Box(new BlockPos(Math.min(box.min().x(), cell.x()), Math.min(box.min().y(), cell.y()),
                Math.min(box.min().z(), cell.z())), new BlockPos(Math.max(box.max().x(), cell.x()),
                Math.max(box.max().y(), cell.y()), Math.max(box.max().z(), cell.z())));
    }

    private static int floor(double v) {
        return (int) Math.floor(v);
    }

    public long estimatedBytes() {
        long bytes = 32 + before.estimatedBytes() + after.estimatedBytes();
        for (EntityChange change : entities) bytes += change.estimatedBytes();
        for (BiomeChange change : biomes) bytes += change.estimatedBytes();
        return bytes;
    }
}
