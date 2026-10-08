package dev.sculptory.core.history;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import java.util.List;
import java.util.Objects;

/**
 * What one edit changed: the cells (their states and tiles before and after; both buffers hold the same cells), the
 * entities (one {@link EntityChange} per entity, by UUID, each at most once) and, from protocol 6, the biomes (one
 * {@link BiomeChange} per quart, each at most once). The biomes are always held as a compact {@link BiomeChanges}
 * (whatever list is given is copied into one), so a record of millions of quarts stays small; {@link #biomeChanges()}
 * gives that type.
 */
public record EditRecord(BlockBuffer before, BlockBuffer after, List<EntityChange> entities, List<BiomeChange> biomes) {
    public EditRecord {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        entities = List.copyOf(entities);
        biomes = BiomeChanges.of(biomes);
    }

    /** A record of cells and entities, without biomes. */
    public EditRecord(BlockBuffer before, BlockBuffer after, List<EntityChange> entities) {
        this(before, after, entities, BiomeChanges.EMPTY);
    }

    /** A record of cells only. */
    public EditRecord(BlockBuffer before, BlockBuffer after) {
        this(before, after, List.of());
    }

    /** The biome changes, compact (what {@link #biomes()} holds). */
    public BiomeChanges biomeChanges() {
        return (BiomeChanges) biomes;
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
        BiomeChanges quarts = biomeChanges();
        for (int i = 0; i < quarts.size(); i++) {
            int x = quarts.qx(i) << 2, y = quarts.qy(i) << 2, z = quarts.qz(i) << 2;
            box = include(box, new BlockPos(x, y, z));
            box = include(box, new BlockPos(x + 3, y + 3, z + 3));
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
        bytes += biomeChanges().estimatedBytes();
        return bytes;
    }
}
