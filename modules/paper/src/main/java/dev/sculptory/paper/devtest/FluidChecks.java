package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.box;
import static dev.sculptory.paper.devtest.SelfTestRun.cells;

import dev.sculptory.core.Box;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.mc.world.FluidTrails;
import dev.sculptory.paper.nms.PaperAccess;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;

/**
 * Fluid trails on Paper (PaperFluidTrails), one covered case and the recorded result of uncovered ones:
 * <ul>
 *   <li>covered: water written by a fill (still, physics off) is set flowing; it spreads over a stone floor, Bukkit's
 *       flow and level events record it into the fill's trail, its marks reach the chunk's data container at a full
 *       save, and the fill's undo takes every cell of it back;</li>
 *   <li>uncovered (information, not failures): the same water washing away the first dust of a powered redstone line:
 *       the line's far dust loses power in that same tick with no event, so after the undo it stays unpowered; and a
 *       block another plugin sets in a marked cell keeps the cell's mark.</li>
 * </ul>
 */
final class FluidChecks {
    private static final int FLOOR = 100;

    private FluidChecks() {
    }

    static CompletableFuture<Void> run(SelfTestRun r) {
        int x0 = r.baseX + 448, z0 = r.baseZ; // chunk-aligned
        Box area = box(x0, FLOOR, z0, x0 + 31, FLOOR + 4, z0 + 15);
        r.claim(area);
        for (BlockPos cell : cells(area)) {
            BlockState state = cell.getY() == FLOOR ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (r.level.getBlockState(cell) != state) PaperAccess.setBlockWithoutCallbacks(r.level, cell, state);
            r.level.getChunkSource().blockChanged(cell);
        }
        return covered(r, x0, z0).thenCompose(v -> redstone(r, x0 + 16, z0)).thenCompose(v -> otherPlugin(r, x0, z0));
    }

    /** Water from a fill flows out; its trail and marks are recorded; the fill's undo takes all of it back. */
    private static CompletableFuture<Void> covered(SelfTestRun r, int x0, int z0) {
        Box floor = box(x0, FLOOR + 1, z0, x0 + 15, FLOOR + 1, z0 + 15);
        int[] before = r.snapshot(floor);
        BlockPos source = new BlockPos(x0 + 8, FLOOR + 1, z0 + 8);
        Box one = box(source.getX(), source.getY(), source.getZ(), source.getX(), source.getY(), source.getZ());
        int water = Block.getId(Blocks.WATER.defaultBlockState());
        UUID[] entry = {null};
        return r.fill(one, water).thenCompose(result -> {
            entry[0] = newest(r);
            // The written water lies still (physics off) until something updates it: its own tick, as a neighbour's
            // change would set off.
            r.level.scheduleTick(source, Fluids.WATER, 1);
            return r.ticks(60);
        }).thenCompose(v -> {
            int wet = 0;
            for (BlockPos cell : cells(floor)) if (!r.level.getFluidState(cell).isEmpty()) wet++;
            long trail = r.host.engine().fluidTrails().trailCells(r.level, entry[0]);
            BlockPos edge = source.east(3);
            boolean marked = r.host.engine().fluidTrails().markedBy(r.level, edge, entry[0]);
            r.record("fluid trails: water a fill wrote flows out into its entry's trail", wet > 20 && trail >= wet - 1
                    && marked, wet + " wet cells, " + trail + " cells in the trail, the cell 3 east marked " + marked);
            // A full save writes the column's marks into the chunk's data container.
            r.level.save(null, false, false);
            LevelChunk chunk = r.level.getChunkSource().getChunkNow(source.getX() >> 4, source.getZ() >> 4);
            boolean saved = chunk != null && chunk.persistentDataContainer.getRaw().containsKey(FluidTrails.NBT_KEY);
            r.record("fluid trails: the marks go into the chunk's data container", saved,
                    "key " + FluidTrails.NBT_KEY + " present " + saved);
            return r.undo();
        }).thenCompose(result -> r.ticks(20).thenApply(v -> result)).thenAccept(result -> {
            int differ = r.countDiffering(floor, before);
            r.record("fluid trails: the fill's undo takes the flowed water back", differ == 0,
                    result.outcome() + ", " + result.changed() + " changed, " + differ + " cells differ");
        });
    }

    /**
     * A redstone block powering a line of six dust eastwards; water written above the first dust and set flowing washes
     * it away. Reported: which cells differ after the fill's undo (the far dust that lost power in the same tick).
     */
    private static CompletableFuture<Void> redstone(SelfTestRun r, int x0, int z0) {
        int z = z0 + 8;
        Box line = box(x0 + 2, FLOOR + 1, z, x0 + 8, FLOOR + 1, z);
        r.level.setBlockAndUpdate(new BlockPos(x0 + 2, FLOOR + 1, z), Blocks.REDSTONE_BLOCK.defaultBlockState());
        for (int x = x0 + 3; x <= x0 + 8; x++) {
            r.level.setBlockAndUpdate(new BlockPos(x, FLOOR + 1, z), Blocks.REDSTONE_WIRE.defaultBlockState());
        }
        Box all = box(x0, FLOOR + 1, z0, x0 + 15, FLOOR + 2, z0 + 15);
        int[] before = r.snapshot(all);
        BlockPos source = new BlockPos(x0 + 3, FLOOR + 2, z);
        Box one = box(source.getX(), source.getY(), source.getZ(), source.getX(), source.getY(), source.getZ());
        return r.fill(one, Block.getId(Blocks.WATER.defaultBlockState())).thenCompose(result -> {
            r.level.scheduleTick(source, Fluids.WATER, 1);
            return r.ticks(60);
        }).thenCompose(v -> r.undo()).thenCompose(result -> r.ticks(10)).thenAccept(v -> {
            List<String> differ = new ArrayList<>();
            List<BlockPos> cells = cells(all);
            int[] now = r.snapshot(all);
            for (int i = 0; i < now.length; i++) {
                if (now[i] == before[i]) continue;
                BlockState was = Block.stateById(before[i]), is = Block.stateById(now[i]);
                differ.add(cells.get(i).toShortString() + " " + describe(was) + " -> " + describe(is));
            }
            r.info("fluid trails, uncovered: a redstone line the water cuts", differ.isEmpty()
                    ? "the undo restored everything" : differ.size() + " cells differ after the undo: " + differ);
            r.record("fluid trails: the redstone check's own cells are restored where events cover them",
                    r.level.getBlockState(new BlockPos(x0 + 3, FLOOR + 1, z)).is(Blocks.REDSTONE_WIRE)
                            && r.level.getFluidState(source).isEmpty(),
                    "first dust back " + r.level.getBlockState(new BlockPos(x0 + 3, FLOOR + 1, z)));
        });
    }

    /** A block another plugin sets in a cell the fill's water marks: reported whether the mark stays. */
    private static CompletableFuture<Void> otherPlugin(SelfTestRun r, int x0, int z0) {
        BlockPos cell = new BlockPos(x0 + 2, FLOOR + 1, z0 + 2);
        Box one = box(cell.getX(), cell.getY(), cell.getZ(), cell.getX(), cell.getY(), cell.getZ());
        return r.fill(one, Block.getId(Blocks.WATER.defaultBlockState())).thenAccept(result -> {
            UUID entry = newest(r);
            boolean before = r.host.engine().fluidTrails().markedBy(r.level, cell, entry);
            r.level.setBlock(cell, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
            boolean after = r.host.engine().fluidTrails().markedBy(r.level, cell, entry);
            r.info("fluid trails, uncovered: another plugin's block in a marked cell", "marked before " + before
                    + ", after the plugin set stone there " + after + (after ? " (the mark stays; on Fabric the "
                    + "block-change hook clears it)" : ""));
        });
    }

    private static UUID newest(SelfTestRun r) {
        List<HistoryEntry> entries = r.service.historyService().undoEntries(r.player.getUUID());
        return entries.isEmpty() ? null : entries.get(0).id();
    }

    private static String describe(BlockState state) {
        if (state.getBlock() instanceof RedStoneWireBlock) return "dust power " + state.getValue(RedStoneWireBlock.POWER);
        return state.getBlock().getDescriptionId().replace("block.minecraft.", "");
    }
}
