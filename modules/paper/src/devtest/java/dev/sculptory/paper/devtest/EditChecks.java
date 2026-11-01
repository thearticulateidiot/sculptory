package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.aabb;
import static dev.sculptory.paper.devtest.SelfTestRun.box;
import static dev.sculptory.paper.devtest.SelfTestRun.cells;

import dev.sculptory.core.Box;
import dev.sculptory.paper.nms.PaperAccess;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The world layer's writes: a fill is exactly what was asked and its undo exact; physics-off writes leave sand in the
 * air, overwrite a chest without dropping its items, and the undo brings the chest back with them.
 */
final class EditChecks {
    private EditChecks() {
    }

    static CompletableFuture<Void> fillUndo(SelfTestRun r) {
        Box box = box(r.baseX + 2, 100, r.baseZ + 2, r.baseX + 11, 104, r.baseZ + 11);
        r.claim(box);
        int[] before = r.snapshot(box);
        int glass = Block.getId(Blocks.GLASS.defaultBlockState());
        return r.fill(box, glass).thenCompose(result -> {
            int wrong = r.countNot(box, glass);
            r.record("fill-undo: fill", wrong == 0 && result.skippedProtected() == 0,
                    result.changed() + " changed, " + wrong + " cells not glass");
            return r.undo();
        }).thenAccept(result -> {
            int differ = r.countDiffering(box, before);
            r.record("fill-undo: exact undo", differ == 0, result.changed() + " restored, " + differ + " cells differ");
        });
    }

    static CompletableFuture<Void> physicsOff(SelfTestRun r) {
        ServerLevel level = r.level;
        int x = r.baseX + 40, z = r.baseZ + 2;
        Box sand = box(x, 200, z, x + 3, 201, z + 3);
        BlockPos chestPos = new BlockPos(x + 8, 100, z);
        Box chest = box(chestPos.getX(), chestPos.getY(), chestPos.getZ(), chestPos.getX(), chestPos.getY(),
                chestPos.getZ());
        SelfTestRun.Area sandArea = r.claim(sand);
        // The chest's neighbours too: items would drop around it.
        SelfTestRun.Area chestArea = r.claim(box(chestPos.getX() - 3, chestPos.getY() - 3, chestPos.getZ() - 3,
                chestPos.getX() + 3, chestPos.getY() + 3, chestPos.getZ() + 3));
        int[] sandBefore = r.snapshot(sand);
        // A chest with items, put there without callbacks (whatever was there drops nothing) and not through the engine,
        // so the undo below is the engine's; the claimed area is put back at the end.
        PaperAccess.setBlockWithoutCallbacks(level, chestPos, Blocks.CHEST.defaultBlockState());
        level.getChunkSource().blockChanged(chestPos);
        if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity box) box.setItem(0, new ItemStack(Items.DIAMOND, 7));
        int sandId = Block.getId(Blocks.SAND.defaultBlockState());
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        // The sand must stay put in ticks the chunk runs: it would fall in them with physics on.
        return r.ready(sandArea, chestArea).thenCompose(v -> r.fill(sand, sandId))
                .thenCompose(result -> r.ticks(10).thenApply(v -> result))
                .thenCompose(result -> {
                    int notSand = r.countNot(sand, sandId);
                    int falling = level.getEntitiesOfClass(FallingBlockEntity.class, aabb(sand).inflate(2, 40, 2)).size();
                    int scheduled = 0;
                    for (BlockPos pos : cells(sand)) {
                        if (level.getBlockTicks().hasScheduledTick(pos, Blocks.SAND)) scheduled++;
                    }
                    r.record("physics-off: sand stays", notSand == 0 && falling == 0 && scheduled == 0,
                            notSand + " cells not sand, " + falling + " falling blocks, " + scheduled + " scheduled ticks");
                    return r.undo();
                })
                .thenCompose(result -> {
                    r.record("physics-off: sand undo", r.countDiffering(sand, sandBefore) == 0,
                            r.countDiffering(sand, sandBefore) + " cells differ");
                    return r.fill(chest, stone);
                })
                .thenCompose(result -> r.ticks(5).thenApply(v -> result))
                .thenCompose(result -> {
                    // Only items that appeared during the check (younger than 40 ticks).
                    int items = level.getEntitiesOfClass(ItemEntity.class, aabb(chest).inflate(3), item -> item.tickCount < 40)
                            .size();
                    boolean isStone = level.getBlockState(chestPos).is(Blocks.STONE);
                    r.record("physics-off: chest overwritten without drops", isStone && items == 0,
                            "stone=" + isStone + ", " + items + " new item entities nearby");
                    return r.undo();
                })
                .thenAccept(result -> {
                    BlockState back = level.getBlockState(chestPos);
                    int diamonds = level.getBlockEntity(chestPos) instanceof ChestBlockEntity box
                            && box.getItem(0).is(Items.DIAMOND) ? box.getItem(0).getCount() : 0;
                    r.record("physics-off: chest undo with contents", back.is(Blocks.CHEST) && diamonds == 7,
                            back + ", " + diamonds + " diamonds in slot 0");
                });
    }
}
