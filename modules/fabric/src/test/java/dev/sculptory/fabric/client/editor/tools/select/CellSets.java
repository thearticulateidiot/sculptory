package dev.sculptory.fabric.client.editor.tools.select;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import java.util.HashSet;
import java.util.Set;

/** Test helpers: the cells of a cell set or region, as positions. */
final class CellSets {
    private CellSets() {}

    static Set<BlockPos> cells(CellSet set) {
        return cells(set.isEmpty() ? null : new Region.Cells(set));
    }

    static Set<BlockPos> cells(Region region) {
        Set<BlockPos> cells = new HashSet<>();
        if (region == null) return cells;
        for (long key : region.sectionKeys()) {
            int baseX = BlockBuffer.keyX(key) << 4;
            int baseY = BlockBuffer.keyY(key) << 4;
            int baseZ = BlockBuffer.keyZ(key) << 4;
            for (int i = 0; i < 4096; i++) {
                int x = baseX + (i & 15);
                int y = baseY + (i >>> 8);
                int z = baseZ + ((i >>> 4) & 15);
                if (region.contains(x, y, z)) cells.add(new BlockPos(x, y, z));
            }
        }
        return cells;
    }

    static CellSet of(BlockPos... cells) {
        CellSet.Builder builder = CellSet.builder();
        for (BlockPos cell : cells) builder.add(cell.x(), cell.y(), cell.z());
        return builder.build();
    }
}
