package dev.sculptory.core.edit;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockBuffer;
import java.util.Objects;

/**
 * Paste source content: cells in local coordinates {@code [0, size)}, and the local cell that lands on the
 * paste origin. Absent cells are not written; air cells are written only with {@link PasteOptions#includeAir()}.
 */
public record SourceBlocks(BlockBuffer cells, BlockPos size, BlockPos anchor) {
    public SourceBlocks {
        Objects.requireNonNull(cells);
        Objects.requireNonNull(size);
        Objects.requireNonNull(anchor);
        if (size.x() <= 0 || size.y() <= 0 || size.z() <= 0) throw new IllegalArgumentException("Source size");
    }
}
