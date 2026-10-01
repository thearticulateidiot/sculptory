package dev.sculptory.core.history;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import java.util.List;
import java.util.Objects;

/**
 * What one edit changed: the cells (their states and tiles before and after; both buffers hold the same cells) and the
 * entities (one {@link EntityChange} per entity, by UUID, each at most once).
 */
public record EditRecord(BlockBuffer before, BlockBuffer after, List<EntityChange> entities) {
    public EditRecord {
        Objects.requireNonNull(before);
        Objects.requireNonNull(after);
        entities = List.copyOf(entities);
    }

    /** A record of cells only. */
    public EditRecord(BlockBuffer before, BlockBuffer after) {
        this(before, after, List.of());
    }

    /** Whether the edit changed nothing: no cell and no entity. */
    public boolean isEmpty() {
        return before.isEmpty() && entities.isEmpty();
    }

    /**
     * The smallest box holding every recorded cell and the block of every recorded entity position (before and after),
     * or {@code null} for an empty record.
     */
    public Box bounds() {
        Box box = before.bounds();
        for (EntityChange change : entities) {
            if (change.before() != null) box = include(box, change.before());
            if (change.after() != null) box = include(box, change.after());
        }
        return box;
    }

    private static Box include(Box box, EntityState state) {
        BlockPos cell = new BlockPos(floor(state.x()), floor(state.y()), floor(state.z()));
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
        return bytes;
    }
}
