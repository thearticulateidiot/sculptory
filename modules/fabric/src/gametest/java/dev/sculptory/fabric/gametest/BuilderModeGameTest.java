package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.BuilderOutcome.Refusal;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.impl.EditExecutor;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.HorizontalConnectingBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.StairShape;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.OperatorEntry;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Builder mode through {@code EngineEditService}: each power writes exactly the
 * expected cells, one click or drag is one history step undone and redone exactly, and every refusal writes nothing.
 * Region slots 1060-1079.
 */
public final class BuilderModeGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int FLOOR = 100;

    /** A place on a block's top is one step, undone and redone exactly. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_place", tickLimit = LIMIT)
    public void placeIsOneStepUndoneAndRedoneExactly(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1060);
        stand(h.player, r.x + 2, r.z + 2, -90f);
        hold(h.player, Items.STONE);
        WorldSnapshot before = capture(h.world, r.area);
        BuilderOutcome outcome = place(h, h.player, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
        check(outcome.accepted() && outcome.changed() == 1, "place " + outcome);
        check(h.world.getBlockState(pos(r.x + 6, FLOOR + 1, r.z + 2)).isOf(Blocks.STONE), "stone placed");
        List<HistoryEntry> entries = entries(h);
        check(entries.size() == 1 && entries.get(0).label().equals("Place · 1 block"), "history " + labels(entries));
        WorldSnapshot after = capture(h.world, r.area);
        undoThenRedo(context, h, before, after, r);
    }

    /**
     * Without Keep shape the game's own changes around a placement (a fence joining its neighbour, a door's upper half)
     * are recorded with it, and undo takes them back exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_neighbours", tickLimit = LIMIT)
    public void vanillaNeighbourChangesAreRecordedAndUndone(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1061);
        BlockPos fence = pos(r.x + 5, FLOOR + 1, r.z + 5);
        h.world.setBlockState(fence, Blocks.OAK_FENCE.getDefaultState());
        stand(h.player, r.x + 2, r.z + 5, -90f);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.OAK_FENCE);
        BuilderOutcome outcome = place(h, h.player, r.x + 6, FLOOR, r.z + 5, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
        check(outcome.accepted() && outcome.changed() == 2, "the fence and its neighbour: " + outcome);
        check(h.world.getBlockState(fence).get(HorizontalConnectingBlock.EAST), "the old fence joined the new one");
        hold(h.player, Items.OAK_DOOR);
        outcome = place(h, h.player, r.x + 5, FLOOR, r.z + 8, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
        check(outcome.accepted() && outcome.changed() == 2, "both halves of the door: " + outcome);
        check(h.world.getBlockState(pos(r.x + 5, FLOOR + 2, r.z + 8)).get(DoorBlock.HALF) == DoubleBlockHalf.UPPER,
                "the upper half");
        check(entries(h).size() == 2, "one step per click: " + labels(entries(h)));
        undoAll(context, h, 2, before, r);
    }

    /**
     * Keep shape: placing and breaking change nothing around them (the old fence does not join, a flower whose ground is
     * broken floats, sand whose support is broken stays), and undo is exact.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_keep_shape", tickLimit = LIMIT)
    public void keepShapeLeavesNeighboursAlone(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1062);
        int keep = BuilderPower.KEEP_SHAPE.bit();
        BlockPos fence = pos(r.x + 5, FLOOR + 1, r.z + 5);
        h.world.setBlockState(fence, Blocks.OAK_FENCE.getDefaultState());
        BlockPos grass = pos(r.x + 8, FLOOR, r.z + 5);
        h.world.setBlockState(grass, Blocks.GRASS_BLOCK.getDefaultState());
        h.world.setBlockState(grass.up(), Blocks.POPPY.getDefaultState());
        BlockPos support = pos(r.x + 10, FLOOR + 1, r.z + 5);
        h.world.setBlockState(support, Blocks.STONE.getDefaultState());
        h.world.setBlockState(support.up(), Blocks.SAND.getDefaultState());
        stand(h.player, r.x + 2, r.z + 5, -90f);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.OAK_FENCE);
        BuilderOutcome placed = place(h, h.player, r.x + 6, FLOOR, r.z + 5, Facing.UP, 0.5f, 1f, 0.5f, keep, Symmetry.NONE);
        check(placed.accepted() && placed.changed() == 1, "only the new fence: " + placed);
        check(!h.world.getBlockState(fence).get(HorizontalConnectingBlock.EAST), "the old fence did not join");
        hold(h.player, Items.AIR);
        BuilderOutcome broken = breakCells(h, h.player, 1, List.of(grass, support), keep, false, true);
        check(broken.accepted() && broken.changed() == 2, "two blocks broken: " + broken);
        context.createTimedTaskRunner()
                .expectMinDurationAndRun(10, () -> {
                    check(h.world.getBlockState(grass.up()).isOf(Blocks.POPPY), "the flower floats");
                    check(h.world.getBlockState(support.up()).isOf(Blocks.SAND), "the sand stayed");
                    check(h.world.getEntitiesByClass(ItemEntity.class, itemBox(r), e -> true).isEmpty(), "nothing dropped");
                    check(entries(h).size() == 2, "two steps: " + labels(entries(h)));
                })
                .createAndAdd(() -> undoAll(context, h, 2, before, r))
                .completeIfSuccessful();
    }

    /** Place in air puts the block into the empty cell aimed at; a block that needs support needs Force place there. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_air", tickLimit = LIMIT)
    public void placeInAirAndForcePlace(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1063);
        int air = BuilderPower.PLACE_IN_AIR.bit();
        // A floating fence post: the cell beside it has no floor under it, so nothing there can hold a torch.
        BlockPos post = pos(r.x + 6, FLOOR + 4, r.z + 6);
        h.world.setBlockState(post, Blocks.OAK_FENCE.getDefaultState());
        stand(h.player, r.x + 2, r.z + 2, -90f);
        BlockPos cell = pos(r.x + 7, FLOOR + 6, r.z + 2);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.STONE);
        BuilderOutcome stone = place(h, h.player, cell.getX(), cell.getY(), cell.getZ(), Facing.WEST, 0f, 0.5f, 0.5f, air,
                Symmetry.NONE);
        check(stone.accepted() && h.world.getBlockState(cell).isOf(Blocks.STONE), "stone in the air: " + stone);
        hold(h.player, Items.POPPY);
        BlockPos flowerCell = cell.up(2);
        WorldSnapshot beforeFlower = capture(h.world, r.area);
        BuilderOutcome refused = place(h, h.player, flowerCell.getX(), flowerCell.getY(), flowerCell.getZ(), Facing.WEST,
                0f, 0.5f, 0.5f, air, Symmetry.NONE);
        check(refused.refusal() == Refusal.GAME_REFUSES, "a flower in the air without Force place: " + refused);
        checkSame(beforeFlower, capture(h.world, r.area), "a refused flower");
        BuilderOutcome forced = place(h, h.player, flowerCell.getX(), flowerCell.getY(), flowerCell.getZ(), Facing.WEST,
                0f, 0.5f, 0.5f, air | BuilderPower.FORCE_PLACE.bit(), Symmetry.NONE);
        check(forced.accepted() && h.world.getBlockState(flowerCell).isOf(Blocks.POPPY), "forced flower: " + forced);
        // A torch on the side of the post, which cannot hold one: with Force place, its wall torch facing out.
        hold(h.player, Items.TORCH);
        WorldSnapshot beforeTorch = capture(h.world, r.area);
        BuilderOutcome torchRefused = place(h, h.player, post.getX(), post.getY(), post.getZ(), Facing.WEST, 0f, 0.5f, 0.5f,
                0, Symmetry.NONE);
        check(torchRefused.refusal() == Refusal.GAME_REFUSES, "a torch on a fence side: " + torchRefused);
        checkSame(beforeTorch, capture(h.world, r.area), "a refused torch");
        BuilderOutcome torch = place(h, h.player, post.getX(), post.getY(), post.getZ(), Facing.WEST, 0f, 0.5f, 0.5f,
                BuilderPower.FORCE_PLACE.bit(), Symmetry.NONE);
        BlockState wall = h.world.getBlockState(post.west());
        check(torch.accepted() && wall.isOf(Blocks.WALL_TORCH) && wall.get(WallTorchBlock.FACING) == Direction.WEST,
                "a forced wall torch: " + torch + " " + wall);
        check(entries(h).size() == 3, "three steps: " + labels(entries(h)));
        undoAll(context, h, 3, before, r);
    }

    /** Replace swaps the clicked block, keeping its facing and half; a replaced chest drops nothing and comes back full. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_replace", tickLimit = LIMIT)
    public void replaceKeepsOrientationAndContents(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1064);
        int replace = BuilderPower.REPLACE.bit();
        BlockPos stairs = pos(r.x + 6, FLOOR + 1, r.z + 4);
        h.world.setBlockState(stairs, Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.NORTH)
                .with(StairsBlock.HALF, BlockHalf.TOP));
        BlockPos chest = pos(r.x + 6, FLOOR + 1, r.z + 7);
        h.world.setBlockState(chest, Blocks.CHEST.getDefaultState());
        ((ChestBlockEntity) h.world.getBlockEntity(chest)).setStack(3, new ItemStack(Items.DIAMOND, 5));
        stand(h.player, r.x + 2, r.z + 5, -90f);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.STONE_STAIRS);
        BuilderOutcome swapped = place(h, h.player, stairs.getX(), stairs.getY(), stairs.getZ(), Facing.WEST, 0f, 0.25f, 0.5f,
                replace, Symmetry.NONE);
        BlockState now = h.world.getBlockState(stairs);
        check(swapped.accepted() && now.isOf(Blocks.STONE_STAIRS) && now.get(StairsBlock.FACING) == Direction.NORTH
                && now.get(StairsBlock.HALF) == BlockHalf.TOP, "stairs kept their facing and half: " + now);
        hold(h.player, Items.OAK_PLANKS);
        BuilderOutcome chestSwapped = place(h, h.player, chest.getX(), chest.getY(), chest.getZ(), Facing.WEST, 0f, 0.5f,
                0.5f, replace, Symmetry.NONE);
        check(chestSwapped.accepted() && h.world.getBlockState(chest).isOf(Blocks.OAK_PLANKS), "chest replaced");
        context.createTimedTaskRunner()
                .expectMinDurationAndRun(5, () -> {
                    check(h.world.getEntitiesByClass(ItemEntity.class, itemBox(r), e -> true).isEmpty(), "nothing dropped");
                    check(entries(h).get(0).label().equals("Replace · 1 block"), "label " + labels(entries(h)));
                })
                .createAndAdd(() -> undoAll(context, h, 2, before, r))
                .completeIfSuccessful();
    }

    /** Mirror places the block and its image (turned as the editor turns copies) as one step. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_mirror", tickLimit = LIMIT)
    public void mirrorPlacesAndBreaksImagesInOneStep(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1065);
        // Mirrored across the plane x = x0 + 10 (a block edge): x0 + 6 lands on x0 + 13.
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (r.x + 10), 2 * r.z);
        int mirrorBit = BuilderPower.MIRROR.bit();
        stand(h.player, r.x + 2, r.z + 4, -90f);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.OAK_STAIRS);
        BuilderOutcome placed = place(h, h.player, r.x + 6, FLOOR, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f, mirrorBit, mirror);
        BlockState original = h.world.getBlockState(pos(r.x + 6, FLOOR + 1, r.z + 4));
        BlockState image = h.world.getBlockState(pos(r.x + 13, FLOOR + 1, r.z + 4));
        check(placed.accepted() && placed.changed() == 2, "the stairs and their image: " + placed);
        check(original.isOf(Blocks.OAK_STAIRS) && original.get(StairsBlock.FACING) == Direction.EAST, "original " + original);
        check(image.isOf(Blocks.OAK_STAIRS) && image.get(StairsBlock.FACING) == Direction.WEST, "mirrored " + image);
        List<HistoryEntry> entries = entries(h);
        check(entries.size() == 1 && entries.get(0).label().equals("Place · 2 blocks"), "one step: " + labels(entries));
        BlockPos floorCell = pos(r.x + 7, FLOOR, r.z + 8);
        BuilderOutcome broken = breakCells(h, h.player, 4, List.of(floorCell), mirrorBit, mirror, false, true);
        check(broken.accepted() && broken.changed() == 2, "a break and its image: " + broken);
        check(h.world.getBlockState(pos(r.x + 12, FLOOR, r.z + 8)).isAir(), "the image broken");
        // Without a centre inside the world, nothing is written.
        WorldSnapshot now = capture(h.world, r.area);
        BuilderOutcome outside = place(h, h.player, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, mirrorBit,
                new Symmetry(Symmetry.Mode.MIRROR_X, Symmetry.MAX_CENTRE2, 0));
        check(outside.refusal() == Refusal.SYMMETRY_OUTSIDE, "a centre beyond the world: " + outside);
        checkSame(now, capture(h.world, r.area), "after the refused mirror");
        undoAll(context, h, 2, before, r);
    }

    /**
     * A bulldozer drag of several messages is one step; a chest it breaks drops nothing and undo brings it back full.
     * With same-kind only the first block's kind goes.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_bulldozer", tickLimit = LIMIT)
    public void bulldozerDragIsOneStep(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1066);
        int dozer = BuilderPower.BULLDOZER.bit();
        BlockPos chest = pos(r.x + 9, FLOOR, r.z + 3);
        h.world.setBlockState(chest, Blocks.CHEST.getDefaultState());
        ((ChestBlockEntity) h.world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.EMERALD, 12));
        for (int x = r.x + 4; x <= r.x + 8; x++) h.world.setBlockState(pos(x, FLOOR, r.z + 6), Blocks.DIRT.getDefaultState());
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.AIR);
        WorldSnapshot before = capture(h.world, r.area);
        check(breakCells(h, h.player, 9, List.of(pos(r.x + 5, FLOOR, r.z + 3), pos(r.x + 6, FLOOR, r.z + 3)), dozer, false,
                false).changed() == 2, "first message");
        check(entries(h).isEmpty() && h.service.builderDragOpen(h.player.getUuid()), "the drag is still open");
        check(breakCells(h, h.player, 9, List.of(pos(r.x + 7, FLOOR, r.z + 3), pos(r.x + 8, FLOOR, r.z + 3)), dozer, false,
                false).changed() == 2, "second message");
        check(breakCells(h, h.player, 9, List.of(chest), dozer, false, true).changed() == 1, "the chest");
        List<HistoryEntry> entries = entries(h);
        check(entries.size() == 1 && entries.get(0).label().equals("Bulldozer · 5 blocks"), "one step: " + labels(entries));
        // Same kind: the drag starts on stone, so the dirt row is left.
        h.world.setBlockState(pos(r.x + 4, FLOOR + 1, r.z + 6), Blocks.STONE.getDefaultState());
        WorldSnapshot beforeSameKind = capture(h.world, r.area);
        BuilderOutcome sameKind = breakCells(h, h.player, 10, List.of(pos(r.x + 4, FLOOR + 1, r.z + 6),
                pos(r.x + 5, FLOOR, r.z + 6), pos(r.x + 6, FLOOR, r.z + 6)), dozer, true, true);
        check(sameKind.changed() == 1 && sameKind.skipped() == 2, "same kind: " + sameKind);
        check(h.world.getBlockState(pos(r.x + 5, FLOOR, r.z + 6)).isOf(Blocks.DIRT), "dirt kept");
        context.createTimedTaskRunner()
                .expectMinDurationAndRun(5, () -> check(h.world.getEntitiesByClass(ItemEntity.class, itemBox(r), e -> true).isEmpty(),
                        "nothing dropped"))
                .createAndAdd(() -> {
                    RecordingListener undo = new RecordingListener();
                    h.undo(undo);
                    context.createTimedTaskRunner()
                            .createAndAdd(() -> check(undo.result != null, "undo running"))
                            .createAndAdd(() -> {
                                checkSame(beforeSameKind, capture(h.world, r.area), "after undoing the same-kind drag");
                                h.world.setBlockState(pos(r.x + 4, FLOOR + 1, r.z + 6), Blocks.AIR.getDefaultState());
                                undoAll(context, h, 1, before, r);
                            })
                            .completeIfSuccessful();
                })
                .completeIfSuccessful();
    }

    /** An editor undo while a drag is open ends the drag first: its step is the one undone. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_drag_undo", tickLimit = LIMIT)
    public void undoEndsAnOpenDragFirst(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1067);
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.AIR);
        WorldSnapshot before = capture(h.world, r.area);
        breakCells(h, h.player, 3, List.of(pos(r.x + 5, FLOOR, r.z + 4), pos(r.x + 6, FLOOR, r.z + 4)),
                BuilderPower.BULLDOZER.bit(), false, false);
        check(h.service.builderDragOpen(h.player.getUuid()), "open drag");
        undoAll(context, h, 1, before, r);
    }

    /** Every refusal writes nothing and adds no step. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_refusals", tickLimit = LIMIT)
    public void refusalsWriteNothing(TestContext context) {
        long[] now = {1_000_000_000L};
        Harness h = new Harness(context, null, () -> now[0]);
        Region r = region(h, context, 1068);
        List<ServerPlayerEntity> extra = new ArrayList<>();
        try {
            stand(h.player, r.x + 2, r.z + 2, -90f);
            hold(h.player, Items.STONE);
            WorldSnapshot before = capture(h.world, r.area);
            // Out of reach (the region's far corner is 70 blocks away).
            ModePlayer far = modePlayer(context, extra, true);
            stand(far, r.x - 70, r.z + 2, -90f);
            hold(far, Items.STONE);
            expect(place(h, far, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE),
                    Refusal.OUT_OF_REACH, "out of reach");
            // Not in Creative.
            ModePlayer survival = modePlayer(context, extra, true);
            survival.creative = false;
            stand(survival, r.x + 2, r.z + 2, -90f);
            hold(survival, Items.STONE);
            expect(place(h, survival, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE),
                    Refusal.NOT_CREATIVE, "survival place");
            expect(breakCells(h, survival, 1, List.of(pos(r.x + 6, FLOOR, r.z + 2)), 0, false, true), Refusal.NOT_CREATIVE,
                    "survival break");
            // Without the builder node (a non-op, and an op a permissions mod denies it to).
            ModePlayer stranger = modePlayer(context, extra, false);
            stand(stranger, r.x + 2, r.z + 2, -90f);
            hold(stranger, Items.STONE);
            expect(place(h, stranger, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE),
                    Refusal.NO_PERMISSION, "no permission");
            ModePlayer denied = modePlayer(context, extra, true);
            EditTestSupport.deny(denied, dev.sculptory.server.engine.Perm.BUILDER);
            stand(denied, r.x + 2, r.z + 2, -90f);
            hold(denied, Items.STONE);
            expect(place(h, denied, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE),
                    Refusal.NO_PERMISSION, "builder denied");
            // Protected (spawn protection or a claim).
            ProtectionHook.protect(h.player, h.world, r.x + 5, r.z, r.x + 20, r.z + 20);
            expect(place(h, h.player, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE),
                    Refusal.PROTECTED, "protected place");
            expect(breakCells(h, h.player, 2, List.of(pos(r.x + 6, FLOOR, r.z + 2)), 0, false, true), Refusal.PROTECTED,
                    "protected break");
            ProtectionHook.clear(h.player);
            // Not a block in hand.
            hold(h.player, Items.STICK);
            expect(place(h, h.player, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE),
                    Refusal.NOT_A_BLOCK, "a stick");
            // A sword breaks nothing in Creative.
            hold(h.player, Items.DIAMOND_SWORD);
            expect(breakCells(h, h.player, 3, List.of(pos(r.x + 6, FLOOR, r.z + 2)), 0, false, true), Refusal.CANNOT_BREAK,
                    "a sword");
            // Operator blocks stay with level-2 ops: a builder with the nodes but no op level neither places one (Force
            // place or not) nor replaces one.
            ModePlayer plain = modePlayer(context, extra, false);
            EditTestSupport.grant(plain, dev.sculptory.server.engine.Perm.USE, dev.sculptory.server.engine.Perm.BUILDER);
            stand(plain, r.x + 2, r.z + 2, -90f);
            hold(plain, Items.COMMAND_BLOCK);
            expect(place(h, plain, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE),
                    Refusal.GAME_REFUSES, "a command block by a plain builder");
            expect(place(h, plain, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, BuilderPower.FORCE_PLACE.bit(),
                    Symmetry.NONE), Refusal.GAME_REFUSES, "forced");
            BlockPos operator = pos(r.x + 6, FLOOR + 1, r.z + 5);
            h.world.setBlockState(operator, Blocks.COMMAND_BLOCK.getDefaultState(), 2);
            hold(plain, Items.STONE);
            expect(place(h, plain, r.x + 6, FLOOR + 1, r.z + 5, Facing.UP, 0.5f, 1f, 0.5f, BuilderPower.REPLACE.bit(),
                    Symmetry.NONE), Refusal.CANNOT_BREAK, "replacing a command block");
            check(h.world.getBlockState(operator).isOf(Blocks.COMMAND_BLOCK), "the command block stays");
            h.world.setBlockState(operator, Blocks.AIR.getDefaultState(), 2);
            // Over the budget: 100 blocks a second, bursts of 200; the clock stands still.
            hold(h.player, Items.AIR);
            List<BlockPos> sixteen = new ArrayList<>();
            for (int i = 0; i < 16; i++) sixteen.add(pos(r.x + 4 + i % 8, FLOOR + 10 + i / 8, r.z + 12));
            Symmetry four = new Symmetry(Symmetry.Mode.MIRROR_XZ, 2 * (r.x + 10), 2 * (r.z + 10));
            for (int i = 0; i < 3; i++) {
                check(breakCells(h, h.player, 4, sixteen, BuilderPower.MIRROR.bit(), four, false, true).refusal()
                        == Refusal.NOTHING, "air spends the budget too");
            }
            expect(breakCells(h, h.player, 5, sixteen, BuilderPower.MIRROR.bit(), four, false, true), Refusal.RATE_LIMITED,
                    "over the budget");
            hold(h.player, Items.STONE);
            now[0] += 3_000_000_000L;
            // An editor job holding the area.
            RecordingListener job = new RecordingListener();
            h.fill(box(r.x, FLOOR + 30, r.z, r.x + 31, FLOOR + 40, r.z + 31), "minecraft:glass", job);
            expect(place(h, h.player, r.x + 6, FLOOR + 30, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE),
                    Refusal.AREA_BUSY, "an editor job's area");
            // Editing switched off.
            h.runtime.config().editingEnabled = false;
            try {
                expect(place(h, h.player, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE),
                        Refusal.DISABLED, "editing disabled");
            } finally {
                h.runtime.config().editingEnabled = true;
            }
            check(entries(h).isEmpty(), "no step: " + labels(entries(h)));
            checkSame(before, capture(h.world, r.area), "after the refusals");
            context.createTimedTaskRunner()
                    .createAndAdd(() -> check(job.result != null, "the job ends"))
                    .createAndAdd(() -> {
                        cleanup(h, extra, r);
                    })
                    .completeIfSuccessful();
        } catch (RuntimeException | Error e) {
            cleanup(h, extra, r);
            throw e;
        }
    }

    /**
     * Partial failure: a mirrored copy that lands on a protected cell is skipped while the original is written, and a
     * bulldozer drag over a protected cell breaks the others; each is one step with exactly what was written, undone
     * exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_partial", tickLimit = LIMIT)
    public void partialFailuresWriteTheRestAsOneStep(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1070);
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (r.x + 10), 2 * r.z);
        int mirrorBit = BuilderPower.MIRROR.bit();
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.STONE);
        // Everything from x0 + 12 on is protected: the image of x0 + 6 (x0 + 13) lies inside.
        ProtectionHook.protect(h.player, h.world, r.x + 12, r.z, r.x + 31, r.z + 31);
        WorldSnapshot before = capture(h.world, r.area);
        try {
            BuilderOutcome placed = place(h, h.player, r.x + 6, FLOOR, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f, mirrorBit, mirror);
            check(placed.accepted() && placed.changed() == 1 && placed.skipped() == 1, "original placed, copy skipped: " + placed);
            check(h.world.getBlockState(pos(r.x + 6, FLOOR + 1, r.z + 4)).isOf(Blocks.STONE), "the original");
            check(h.world.getBlockState(pos(r.x + 13, FLOOR + 1, r.z + 4)).isAir(), "the protected image untouched");
            List<HistoryEntry> entries = entries(h);
            check(entries.size() == 1 && entries.get(0).label().equals("Place · 1 block"), "one step: " + labels(entries));
            // A drag whose middle cell is protected: the two others go, one step.
            hold(h.player, Items.AIR);
            BuilderOutcome swept = breakCells(h, h.player, 7, List.of(pos(r.x + 5, FLOOR, r.z + 8), pos(r.x + 13, FLOOR, r.z + 8),
                    pos(r.x + 6, FLOOR, r.z + 8)), BuilderPower.BULLDOZER.bit(), false, true);
            check(swept.accepted() && swept.changed() == 2 && swept.skipped() == 1, "two broken, one skipped: " + swept);
            check(h.world.getBlockState(pos(r.x + 13, FLOOR, r.z + 8)).isOf(Blocks.STONE), "the protected cell kept");
            check(h.world.getBlockState(pos(r.x + 5, FLOOR, r.z + 8)).isAir() && h.world.getBlockState(pos(r.x + 6, FLOOR, r.z + 8)).isAir(),
                    "the others broken");
            entries = entries(h);
            check(entries.size() == 2 && entries.get(0).label().equals("Bulldozer · 2 blocks"), "two steps: " + labels(entries));
            // A drag entirely on protected cells writes nothing and adds no step.
            BuilderOutcome refused = breakCells(h, h.player, 8, List.of(pos(r.x + 14, FLOOR, r.z + 8)), BuilderPower.BULLDOZER.bit(),
                    false, true);
            check(refused.refusal() == Refusal.PROTECTED && refused.changed() == 0, "all protected: " + refused);
            check(entries(h).size() == 2, "no step for a refused drag: " + labels(entries(h)));
        } catch (RuntimeException | Error e) {
            cleanup(h, List.of(), r);
            throw e;
        }
        // undoAll clears the protection (cleanup) after the undos; the protected cells were never written.
        undoAll(context, h, 2, before, r);
    }

    /**
     * Cells vanilla's own steps reach beyond the target are checked too: a bed whose head lands in a protected column,
     * or in a section an editor job holds, is refused whole (both halves stay air, no step); breaking a bed's foot
     * whose head is protected leaves both halves. Regions are 16-aligned, so x0 + 15 and x0 + 16 lie in different
     * sections.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_secondary", tickLimit = LIMIT)
    public void secondaryCellsRespectProtectionAndJobLocks(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1071);
        // The job is another player's, so the builder's history holds only the bed.
        List<ServerPlayerEntity> extra = new ArrayList<>();
        ModePlayer other = modePlayer(context, extra, true);
        BlockPos foot = pos(r.x + 15, FLOOR + 1, r.z + 4);
        BlockPos head = pos(r.x + 16, FLOOR + 1, r.z + 4);
        stand(h.player, r.x + 12, r.z + 4, -90f); // facing east: the head lies at x + 1
        hold(h.player, Items.RED_BED);
        try {
            WorldSnapshot before = capture(h.world, r.area);
            ProtectionHook.protect(h.player, h.world, r.x + 16, r.z, r.x + 31, r.z + 31);
            BuilderOutcome placed = place(h, h.player, r.x + 15, FLOOR, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
            check(placed.refusal() == Refusal.PROTECTED && placed.changed() == 0, "the head is protected: " + placed);
            check(h.world.getBlockState(foot).isAir() && h.world.getBlockState(head).isAir(), "both halves put back");
            check(entries(h).isEmpty(), "no step: " + labels(entries(h)));
            checkSame(before, capture(h.world, r.area), "after the refused bed");
            ProtectionHook.clear(h.player);
            // A job holding the head's section.
            RecordingListener job = new RecordingListener();
            h.fill(other, box(r.x + 16, FLOOR + 1, r.z, r.x + 31, FLOOR + 6, r.z + 31), "minecraft:glass", job);
            BuilderOutcome busy = place(h, h.player, r.x + 15, FLOOR, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
            check(busy.refusal() == Refusal.AREA_BUSY && busy.changed() == 0, "the head's section is held: " + busy);
            check(h.world.getBlockState(foot).isAir(), "the foot put back");
            check(entries(h).isEmpty(), "still no step");
        } catch (RuntimeException | Error e) {
            cleanup(h, extra, r);
            throw e;
        }
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(h.world.getBlockState(pos(r.x + 16, FLOOR + 1, r.z + 4)).isOf(Blocks.GLASS)
                        || !h.service.jobs(other.getUuid()).isEmpty(), "the job runs"))
                .expectMinDurationAndRun(20, () -> {
                    check(h.service.jobs(other.getUuid()).isEmpty(), "the job ended");
                    // Breaking the foot takes the head with it: protected, so both halves stay.
                    h.world.setBlockState(head, Blocks.AIR.getDefaultState(), 2);
                    WorldSnapshot beforeBed = capture(h.world, r.area);
                    BuilderOutcome bed = place(h, h.player, r.x + 15, FLOOR, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
                    check(bed.accepted() && bed.changed() == 2, "the bed: " + bed);
                    check(labels(entries(h)).equals(List.of("Place · 2 blocks")), "a step: " + labels(entries(h)));
                    ProtectionHook.protect(h.player, h.world, r.x + 16, r.z, r.x + 31, r.z + 31);
                    hold(h.player, Items.AIR);
                    BuilderOutcome broken = breakCells(h, h.player, 3, List.of(foot), 0, false, true);
                    check(broken.refusal() == Refusal.PROTECTED && broken.changed() == 0, "the head is protected: " + broken);
                    check(h.world.getBlockState(foot).isOf(Blocks.RED_BED) && h.world.getBlockState(head).isOf(Blocks.RED_BED),
                            "both halves stay");
                    check(entries(h).size() == 1, "no step for the refused break: " + labels(entries(h)));
                    ProtectionHook.clear(h.player);
                    removePlayers(h, extra);
                    undoAll(context, h, 1, beforeBed, r);
                })
                .completeIfSuccessful();
    }

    /**
     * A drag ends after 5 s ({@code BuilderService.DRAG_IDLE_SECONDS}) without breaks, and when the player leaves
     * (without saved history its record goes with the player).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_drag_ends", tickLimit = LIMIT)
    public void dragsEndOnIdleAndOnLeaving(TestContext context) {
        long[] now = {1_000_000_000L};
        Harness h = new Harness(context, null, () -> now[0]);
        Region r = region(h, context, 1072);
        int dozer = BuilderPower.BULLDOZER.bit();
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.AIR);
        UUID owner = h.player.getUuid();
        try {
            check(breakCells(h, h.player, 1, List.of(pos(r.x + 5, FLOOR, r.z + 4)), dozer, false, false).changed() == 1, "first");
            check(h.service.builderDragOpen(owner) && entries(h).isEmpty(), "the drag is open");
            now[0] += 4_000_000_000L;
            h.service.tick();
            check(h.service.builderDragOpen(owner), "still open just before the timeout");
            now[0] += 2_000_000_000L;
            h.service.tick();
            check(!h.service.builderDragOpen(owner), "idle: the drag ended");
            check(labels(entries(h)).equals(List.of("Bulldozer · 1 block")), "idle: one step: " + labels(entries(h)));
            check(breakCells(h, h.player, 2, List.of(pos(r.x + 6, FLOOR, r.z + 4), pos(r.x + 7, FLOOR, r.z + 4)), dozer,
                    false, false).changed() == 2, "second drag");
            check(h.service.builderDragOpen(owner), "open again");
            h.service.playerLeft(owner);
            check(!h.service.builderDragOpen(owner), "leaving ended the drag");
            check(h.world.getBlockState(pos(r.x + 7, FLOOR, r.z + 4)).isAir(), "its breaks stand");
            // This harness keeps no saved history: a left player's history goes, the drag's record with it (with saved
            // history the drag becomes an entry first, as a stroke does; DurableHistoryGameTest covers that path).
            if (!h.service.historyService().persistent()) check(entries(h).isEmpty(), "no saved history: " + labels(entries(h)));
        } finally {
            cleanup(h, List.of(), r);
        }
        context.complete();
    }

    /**
     * Keep shape over water: breaking the block under a still water source leaves the water where it is (no neighbour
     * update, no fluid tick), and a bulldozed waterlogged slab leaves its water as the step's; the drag is undone and
     * redone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_water", tickLimit = LIMIT)
    public void keepShapeOverWaterAndWaterloggedBreaksUndoExactly(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1073);
        int powers = BuilderPower.BULLDOZER.bit() | BuilderPower.KEEP_SHAPE.bit();
        BlockPos under = pos(r.x + 10, FLOOR, r.z + 4);
        BlockPos water = pos(r.x + 10, FLOOR + 1, r.z + 4);
        BlockPos slab = pos(r.x + 5, FLOOR + 1, r.z + 4);
        h.world.setBlockState(water, Blocks.WATER.getDefaultState(), 2);
        h.world.setBlockState(slab, Blocks.OAK_SLAB.getDefaultState().with(net.minecraft.state.property.Properties.WATERLOGGED, true), 2);
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.AIR);
        WorldSnapshot before = capture(h.world, r.area);
        try {
            check(breakCells(h, h.player, 1, List.of(under), powers, false, true).changed() == 1, "the block under the water");
            check(h.world.getBlockState(under).isAir() && h.world.getBlockState(water).isOf(Blocks.WATER), "broken, water still there");
        } catch (RuntimeException | Error e) {
            cleanup(h, List.of(), r);
            throw e;
        }
        context.createTimedTaskRunner()
                .expectMinDurationAndRun(10, () -> {
                    check(h.world.getBlockState(under).isAir(), "keep shape: the water did not flow down in 10 ticks");
                    check(h.world.getFluidState(water).isStill(), "the source is still a source");
                    WorldSnapshot afterFirst = capture(h.world, r.area);
                    BuilderOutcome slabBroken = breakCells(h, h.player, 2, List.of(slab), powers, false, true);
                    check(slabBroken.accepted() && slabBroken.changed() == 1, "the waterlogged slab: " + slabBroken);
                    check(h.world.getFluidState(slab).isStill() && h.world.getBlockState(slab).isOf(Blocks.WATER),
                            "its water is left behind");
                    check(labels(entries(h)).equals(List.of("Bulldozer · 1 block", "Bulldozer · 1 block")), labels(entries(h)).toString());
                    WorldSnapshot after = capture(h.world, r.area);
                    RecordingListener undo = new RecordingListener();
                    RecordingListener redo = new RecordingListener();
                    RecordingListener undoBoth = new RecordingListener();
                    context.createTimedTaskRunner()
                            .createAndAdd(() -> h.undo(undo))
                            .createAndAdd(() -> check(undo.result != null, "undo running"))
                            .createAndAdd(() -> {
                                check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
                                checkSame(afterFirst, capture(h.world, r.area), "the slab is back exactly");
                                h.redo(redo);
                            })
                            .createAndAdd(() -> check(redo.result != null, "redo running"))
                            .createAndAdd(() -> {
                                check(redo.result.outcome() == JobOutcome.COMPLETED && redo.result.skippedConflicts() == 0, "redo " + redo.result);
                                checkSame(after, capture(h.world, r.area), "the drag redone exactly");
                                h.undo(undoBoth);
                            })
                            .createAndAdd(() -> check(undoBoth.result != null, "second undo running"))
                            .createAndAdd(() -> {
                                RecordingListener last = new RecordingListener();
                                h.undo(last);
                                context.createTimedTaskRunner()
                                        .createAndAdd(() -> check(last.result != null, "last undo running"))
                                        .createAndAdd(() -> {
                                            check(entries(h).isEmpty(), "everything undone");
                                            checkSame(before, capture(h.world, r.area), "back to the start exactly");
                                            cleanup(h, List.of(), r);
                                        })
                                        .completeIfSuccessful();
                            })
                            .completeIfSuccessful();
                })
                .completeIfSuccessful();
    }

    /** While the player's large Shape step is still being written, builder actions are refused BUSY and spend nothing. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_busy", tickLimit = LIMIT)
    public void actionsWaitBehindAStrokeBeingWritten(TestContext context) {
        EditExecutor<ServerWorld> executor = ShapeBrushGameTest.onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 1074);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 86, z0, x0 + 80, 154, z0 + 80);
        loadAndForce(h.world, area);
        BrushSpec cube = BrushSpec.shape(32, new ShapeSpec(ShapeSpec.Kind.CUBE, 65, Facing.UP, ShapeSpec.Mode.PLACE, 0),
                new Pattern.Single(h.state("minecraft:glass")), 1L, null, Symmetry.NONE);
        try {
            h.service.beginStroke(h.player, 5, cube);
        } catch (EditRejected e) {
            throw new GameTestException("stroke refused: " + e.getMessage());
        }
        check(h.service.dabs(h.player, 5, 1, List.of(ShapeBrushGameTest.at(0, x0 + 44, 120, z0 + 44))).accepted(), "the step");
        executor.tick(); // its first part
        stand(h.player, x0 + 2, z0 + 2, -90f);
        hold(h.player, Items.STONE);
        expect(place(h, h.player, x0 + 6, 86, z0 + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE), Refusal.BUSY, "place");
        hold(h.player, Items.AIR);
        expect(breakCells(h, h.player, 1, List.of(pos(x0 + 6, 86, z0 + 2)), 0, false, true), Refusal.BUSY, "break");
        check(!h.service.builderDragOpen(h.player.getUuid()), "no drag opened");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1
                && h.service.queuedCommits(h.player.getUuid()) == 0, 100, "the step");
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    hold(h.player, Items.STONE);
                    BuilderOutcome placed = place(h, h.player, x0 + 6, 86, z0 + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
                    check(placed.accepted() && placed.changed() == 1, "after the step: " + placed);
                    h.service.endStroke(h.player, 5);
                    forceChunks(h.world, area, false);
                    executor.shutdown();
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A message naming a cell twice breaks it once (the second is NOTHING) and still spends the budget for both, so a
     * flood of repeats runs into RATE_LIMITED; a level-2 op's command block placement and replacement work, a plain
     * builder's are refused ({@code refusalsWriteNothing} covers the refusals).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_repeats", tickLimit = LIMIT)
    public void repeatedCellsSpendTheBudget(TestContext context) {
        long[] now = {1_000_000_000L};
        Harness h = new Harness(context, null, () -> now[0]);
        Region r = region(h, context, 1075);
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.AIR);
        WorldSnapshot before = capture(h.world, r.area);
        try {
            BlockPos cell = pos(r.x + 5, FLOOR, r.z + 4);
            BuilderOutcome twice = breakCells(h, h.player, 1, List.of(cell, cell), 0, false, true);
            check(twice.accepted() && twice.changed() == 1 && twice.skipped() == 1, "once broken, once nothing: " + twice);
            // The budget: 100 a second, bursts of 200; 2 are spent. 16 repeats a message: 12 more messages fit, the 13th
            // (198 + 16 > 200) is refused although it would break nothing.
            List<BlockPos> sixteen = new ArrayList<>();
            for (int i = 0; i < 16; i++) sixteen.add(cell);
            for (int i = 0; i < 12; i++) {
                BuilderOutcome again = breakCells(h, h.player, 2 + i, sixteen, 0, false, true);
                check(again.refusal() == Refusal.NOTHING, "message " + i + ": " + again);
            }
            expect(breakCells(h, h.player, 20, sixteen, 0, false, true), Refusal.RATE_LIMITED, "over the budget");
            check(labels(entries(h)).equals(List.of("Break · 1 block")), labels(entries(h)).toString());
            // An op places and replaces operator blocks.
            now[0] += 3_000_000_000L;
            hold(h.player, Items.COMMAND_BLOCK);
            BuilderOutcome placed = place(h, h.player, r.x + 8, FLOOR, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
            check(placed.accepted() && h.world.getBlockState(pos(r.x + 8, FLOOR + 1, r.z + 4)).isOf(Blocks.COMMAND_BLOCK),
                    "an op's command block: " + placed);
            hold(h.player, Items.STONE);
            BuilderOutcome replaced = place(h, h.player, r.x + 8, FLOOR + 1, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f,
                    BuilderPower.REPLACE.bit(), Symmetry.NONE);
            check(replaced.accepted() && h.world.getBlockState(pos(r.x + 8, FLOOR + 1, r.z + 4)).isOf(Blocks.STONE),
                    "an op replaces it: " + replaced);
            check(entries(h).size() == 3, labels(entries(h)).toString());
        } catch (RuntimeException | Error e) {
            cleanup(h, List.of(), r);
            throw e;
        }
        undoAll(context, h, 3, before, r);
    }

    /**
     * The Tinker power sends Tinker's own messages: the server sees a {@code TinkerService.block} change exactly as the
     * editor's Tinker tool makes it (one step, undone exactly), and refuses a builder with the builder node but no
     * {@code region}.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_tinker", tickLimit = LIMIT)
    public void tinkerPowerChangesGoThroughTinkerService(TestContext context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1076);
        List<ServerPlayerEntity> extra = new ArrayList<>();
        BlockPos stairs = pos(r.x + 5, FLOOR + 1, r.z + 4);
        int straight = h.state("minecraft:oak_stairs[facing=east]");
        int outer = h.state("minecraft:oak_stairs[facing=east,shape=outer_left]");
        h.world.setBlockState(stairs, Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST), 2);
        WorldSnapshot before = capture(h.world, r.area);
        dev.sculptory.core.BlockPos cell = new dev.sculptory.core.BlockPos(stairs.getX(), stairs.getY(), stairs.getZ());
        try {
            // A builder with the builder node but without region: Tinker refuses, nothing changes.
            ModePlayer builder = modePlayer(context, extra, false);
            EditTestSupport.grant(builder, dev.sculptory.server.engine.Perm.USE, dev.sculptory.server.engine.Perm.BUILDER);
            try {
                h.service.block(builder, cell, straight, outer, null);
                throw new GameTestException("a builder without region changed a block through Tinker");
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.NO_PERMISSION, "without region: " + e.reason());
            }
            check(h.world.getBlockState(stairs).get(StairsBlock.SHAPE) == StairShape.STRAIGHT, "unchanged");
            check(entries(h).isEmpty(), "no step");
            // The op's change, as Alt+Scroll sends it.
            h.service.block(h.player, cell, straight, outer, null);
            check(h.world.getBlockState(stairs).get(StairsBlock.SHAPE) == StairShape.OUTER_LEFT, "the shape changed");
            check(entries(h).size() == 1 && entries(h).get(0).label().startsWith("Tinker"), "one Tinker step: " + labels(entries(h)));
        } catch (EditRejected e) {
            cleanup(h, extra, r);
            throw new GameTestException("Tinker refused the op: " + e.getMessage());
        } catch (RuntimeException | Error e) {
            cleanup(h, extra, r);
            throw e;
        }
        removePlayers(h, extra);
        undoAll(context, h, 1, before, r);
    }

    /** A target in a chunk that is not loaded is refused and loads nothing. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_unloaded", tickLimit = LIMIT)
    public void unloadedTargetsAreRefused(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1069);
        // A place no chunk of which is loaded: the player stands there only for this tick.
        int x = at[0] + 4096 * 3 + 8, z = at[1] + 8;
        stand(h.player, x, z, -90f);
        hold(h.player, Items.STONE);
        boolean loadedBefore = h.world.isChunkLoaded(x >> 4, z >> 4);
        BuilderOutcome outcome = place(h, h.player, x + 3, FLOOR, z, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
        check(!loadedBefore, "the far chunk was loaded before the test");
        check(outcome.refusal() == Refusal.UNLOADED, "unloaded: " + outcome);
        check(!h.world.isChunkLoaded((x + 3) >> 4, z >> 4), "the refusal loaded nothing");
        check(entries(h).isEmpty(), "no step");
        h.close();
        context.complete();
    }

    /** Long reach raises the block interaction range to 64 exactly while the power, Creative and the node all hold. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_builder_reach", tickLimit = LIMIT)
    public void longReachFollowsPowerModeAndPermission(TestContext context) {
        Harness h = new Harness(context);
        List<ServerPlayerEntity> extra = new ArrayList<>();
        ModePlayer player = modePlayer(context, extra, true);
        try {
            double base = range(player);
            h.service.builderPowers(player, BuilderPower.LONG_REACH.bit());
            check(range(player) == 64.0, "long reach: " + range(player));
            player.creative = false;
            h.service.tick();
            check(range(player) == base, "survival: " + range(player));
            player.creative = true;
            h.service.tick();
            check(range(player) == 64.0, "creative again: " + range(player));
            EditTestSupport.deny(player, dev.sculptory.server.engine.Perm.BUILDER);
            h.service.revalidate(player, true);
            check(range(player) == base, "denied: " + range(player));
            EditTestSupport.deny(player);
            h.service.revalidate(player, true);
            check(range(player) == 64.0, "granted again: " + range(player));
            h.service.builderPowers(player, 0);
            check(range(player) == base, "switched off: " + range(player));
            h.service.builderPowers(player, BuilderPower.LONG_REACH.bit());
            h.service.playerLeft(player.getUuid());
            check(range(player) == base, "left: " + range(player));
            // The reach check itself: 64 blocks and the margin.
            stand(player, 0, 0, 0f);
            check(h.service.builderReaches(player, pos(64, 100, 0)), "64 blocks away");
            check(!h.service.builderReaches(player, pos(66, 100, 0)), "66 blocks away");
        } finally {
            cleanup(h, extra, null);
        }
        context.complete();
    }

    // ============================================================================================ helpers

    /** A private work area: a stone floor at {@link #FLOOR}, air above, loaded and forced. */
    private record Region(int x, int z, Box area) {}

    private static Region region(Harness h, TestContext context, int slot) {
        int[] at = regionCorner(context, slot);
        Box area = box(at[0], FLOOR, at[1], at[0] + 31, FLOOR + 45, at[1] + 31);
        loadAndForce(h.world, area);
        for (int x = at[0]; x <= at[0] + 31; x++) {
            for (int z = at[1]; z <= at[1] + 31; z++) {
                h.world.setBlockState(pos(x, FLOOR, z), Blocks.STONE.getDefaultState(), 2);
                for (int y = FLOOR + 1; y <= FLOOR + 45; y++) {
                    h.world.setBlockState(pos(x, y, z), Blocks.AIR.getDefaultState(), 2);
                }
            }
        }
        return new Region(at[0], at[1], area);
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static net.minecraft.util.math.Box itemBox(Region r) {
        return new net.minecraft.util.math.Box(r.x - 4, FLOOR - 4, r.z - 4, r.x + 36, FLOOR + 50, r.z + 36);
    }

    /** Stands the player on the floor at (x, z), looking along {@code yaw} (-90: east), level. */
    private static void stand(ServerPlayerEntity player, int x, int z, float yaw) {
        player.refreshPositionAndAngles(x + 0.5, FLOOR + 1, z + 0.5, yaw, 0f);
        player.setHeadYaw(yaw);
    }

    private static void hold(ServerPlayerEntity player, Item item) {
        player.setStackInHand(Hand.MAIN_HAND, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, 64));
    }

    private static int nextSeq = 1;

    private static BuilderOutcome place(Harness h, ServerPlayerEntity player, int x, int y, int z, Facing side, float hx,
                                        float hy, float hz, int powers, Symmetry symmetry) {
        return h.service.builderPlace(player, new C2S.BuilderPlace(nextSeq++, false, new dev.sculptory.core.BlockPos(x, y, z),
                side, hx, hy, hz, powers, symmetry));
    }

    private static BuilderOutcome breakCells(Harness h, ServerPlayerEntity player, int dragId, List<BlockPos> cells, int powers,
                                             boolean sameKind, boolean last) {
        return breakCells(h, player, dragId, cells, powers, Symmetry.NONE, sameKind, last);
    }

    private static BuilderOutcome breakCells(Harness h, ServerPlayerEntity player, int dragId, List<BlockPos> cells, int powers,
                                             Symmetry symmetry, boolean sameKind, boolean last) {
        List<dev.sculptory.core.BlockPos> wire = new ArrayList<>();
        for (BlockPos cell : cells) wire.add(new dev.sculptory.core.BlockPos(cell.getX(), cell.getY(), cell.getZ()));
        return h.service.builderBreak(player, new C2S.BuilderBreak(nextSeq++, dragId, wire, powers, symmetry, sameKind, last));
    }

    private static void expect(BuilderOutcome outcome, Refusal refusal, String what) {
        check(outcome.refusal() == refusal && outcome.changed() == 0, what + ": " + outcome);
    }

    private static List<HistoryEntry> entries(Harness h) {
        return h.service.historyService().undoEntries(h.player.getUuid());
    }

    private static List<String> labels(List<HistoryEntry> entries) {
        return entries.stream().map(HistoryEntry::label).toList();
    }

    private static double range(ServerPlayerEntity player) {
        return player.getAttributeValue(EntityAttributes.PLAYER_BLOCK_INTERACTION_RANGE);
    }

    /** Undoes, waits, redoes, waits: the area matches the snapshots exactly each time. */
    private static void undoThenRedo(TestContext context, Harness h, WorldSnapshot before, WorldSnapshot after, Region r) {
        RecordingListener undo = new RecordingListener();
        RecordingListener redo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> h.undo(undo))
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    checkSame(before, capture(h.world, r.area), "after undo");
                    h.redo(redo);
                })
                .createAndAdd(() -> check(redo.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redo.result.outcome() == JobOutcome.COMPLETED && redo.result.skippedConflicts() == 0,
                            "redo " + redo.result);
                    checkSame(after, capture(h.world, r.area), "after redo");
                    cleanup(h, List.of(), r);
                })
                .completeIfSuccessful();
    }

    /** Undoes {@code steps} steps one after another; then the area matches {@code before} exactly. */
    private static void undoAll(TestContext context, Harness h, int steps, WorldSnapshot before, Region r) {
        RecordingListener[] undo = {null};
        int[] done = {0};
        net.minecraft.test.TimedTaskRunner runner = context.createTimedTaskRunner();
        for (int i = 0; i < steps; i++) {
            runner.createAndAdd(() -> {
                undo[0] = new RecordingListener();
                h.undo(undo[0]);
            }).createAndAdd(() -> check(undo[0].result != null, "undo running")).createAndAdd(() -> {
                check(undo[0].result.outcome() == JobOutcome.COMPLETED && undo[0].result.skippedConflicts() == 0,
                        "undo " + undo[0].result);
                done[0]++;
            });
        }
        runner.createAndAdd(() -> {
            check(done[0] == steps, "undone " + done[0]);
            check(entries(h).isEmpty(), "every step undone: " + labels(entries(h)));
            checkSame(before, capture(h.world, r.area), "after " + steps + " undos");
            cleanup(h, List.of(), r);
        }).completeIfSuccessful();
    }

    private static void cleanup(Harness h, List<ServerPlayerEntity> extra, Region r) {
        removePlayers(h, extra);
        ProtectionHook.clear(h.player);
        if (r != null) forceChunks(h.world, r.area, false);
        h.close();
    }

    /** Deops and removes the extra mock players (their engine state first). */
    private static void removePlayers(Harness h, List<ServerPlayerEntity> extra) {
        for (ServerPlayerEntity player : extra) {
            try {
                h.service.playerLeft(player.getUuid());
                h.world.getServer().getPlayerManager().getOpList().remove(player.getGameProfile());
                EditTestSupport.deny(player);
                h.world.getServer().getPlayerManager().remove(player);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger("sculptory").warn("Could not remove a mock player", e);
            }
        }
        if (!extra.isEmpty()) extra.clear();
    }

    /** A mock player whose game mode the test sets ({@code createMockCreativeServerPlayerInWorld} is always creative). */
    private static final class ModePlayer extends ServerPlayerEntity {
        boolean creative = true;

        ModePlayer(ServerWorld world, GameProfile profile, ConnectedClientData data) {
            super(world.getServer(), world, profile, data.syncedOptions());
        }

        @Override
        public boolean isCreative() {
            return creative;
        }

        @Override
        public boolean isSpectator() {
            return false;
        }
    }

    private static ModePlayer modePlayer(TestContext context, List<ServerPlayerEntity> extra, boolean op) {
        ServerWorld world = context.getWorld();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "builder-" + extra.size());
        ConnectedClientData data = ConnectedClientData.createDefault(profile, false);
        ModePlayer player = new ModePlayer(world, data.gameProfile(), data);
        ClientConnection connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(connection);
        world.getServer().getPlayerManager().onPlayerConnect(connection, player, data);
        if (op) world.getServer().getPlayerManager().getOpList().add(new OperatorEntry(profile, 2, false));
        extra.add(player);
        return player;
    }
}
