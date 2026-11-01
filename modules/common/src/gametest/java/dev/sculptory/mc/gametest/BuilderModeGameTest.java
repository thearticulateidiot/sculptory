package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EditTestSupport.capture;
import static dev.sculptory.mc.gametest.EditTestSupport.checkSame;
import static dev.sculptory.mc.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.mc.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.ShapeSpec;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.mask.EditMask;
import dev.sculptory.core.mask.MaskEntry;
import dev.sculptory.core.mask.MaskRule;
import dev.sculptory.core.region.Facing;
import dev.sculptory.mc.builder.BuilderCapture;
import dev.sculptory.mc.builder.BuilderChange;
import dev.sculptory.mc.builder.BuilderSteps;
import dev.sculptory.mc.builder.CapturedBuilderSteps;
import dev.sculptory.mc.engine.impl.BuilderService;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.mc.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.mc.world.EditScope;
import dev.sculptory.mc.world.McEntities;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.BuilderOutcome.Refusal;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.EditMasks;
import dev.sculptory.server.engine.impl.EngineEditService;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Builder mode through {@code EngineEditService}: each power writes exactly the
 * expected cells, one click or drag is one history step undone and redone exactly, and every refusal writes nothing.
 * Region slots 1060-1079 and 1081-1086 (Record, through the server's own edit service).
 */
public final class BuilderModeGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int FLOOR = 100;

    /** A place on a block's top is one step, undone and redone exactly. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_place", timeoutTicks = LIMIT)
    public void placeIsOneStepUndoneAndRedoneExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1060);
        stand(h.player, r.x + 2, r.z + 2, -90f);
        hold(h.player, Items.STONE);
        WorldSnapshot before = capture(h.world, r.area);
        BuilderOutcome outcome = place(h, h.player, r.x + 6, FLOOR, r.z + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
        check(outcome.accepted() && outcome.changed() == 1, "place " + outcome);
        check(h.world.getBlockState(pos(r.x + 6, FLOOR + 1, r.z + 2)).is(Blocks.STONE), "stone placed");
        List<HistoryEntry> entries = entries(h);
        check(entries.size() == 1 && entries.get(0).label().equals("Place · 1 block"), "history " + labels(entries));
        WorldSnapshot after = capture(h.world, r.area);
        undoThenRedo(context, h, before, after, r);
    }

    /**
     * Without Keep shape the game's own changes around a placement (a fence joining its neighbour, a door's upper half)
     * are recorded with it, and undo takes them back exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_neighbours", timeoutTicks = LIMIT)
    public void vanillaNeighbourChangesAreRecordedAndUndone(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1061);
        BlockPos fence = pos(r.x + 5, FLOOR + 1, r.z + 5);
        h.world.setBlockAndUpdate(fence, Blocks.OAK_FENCE.defaultBlockState());
        stand(h.player, r.x + 2, r.z + 5, -90f);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.OAK_FENCE);
        BuilderOutcome outcome = place(h, h.player, r.x + 6, FLOOR, r.z + 5, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
        check(outcome.accepted() && outcome.changed() == 2, "the fence and its neighbour: " + outcome);
        check(h.world.getBlockState(fence).getValue(CrossCollisionBlock.EAST), "the old fence joined the new one");
        hold(h.player, Items.OAK_DOOR);
        outcome = place(h, h.player, r.x + 5, FLOOR, r.z + 8, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
        check(outcome.accepted() && outcome.changed() == 2, "both halves of the door: " + outcome);
        check(h.world.getBlockState(pos(r.x + 5, FLOOR + 2, r.z + 8)).getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER,
                "the upper half");
        check(entries(h).size() == 2, "one step per click: " + labels(entries(h)));
        undoAll(context, h, 2, before, r);
    }

    /**
     * Keep shape: placing and breaking change nothing around them (the old fence does not join, a flower whose ground is
     * broken floats, sand whose support is broken stays), and undo is exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_keep_shape", timeoutTicks = LIMIT)
    public void keepShapeLeavesNeighboursAlone(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1062);
        int keep = BuilderPower.KEEP_SHAPE.bit();
        BlockPos fence = pos(r.x + 5, FLOOR + 1, r.z + 5);
        h.world.setBlockAndUpdate(fence, Blocks.OAK_FENCE.defaultBlockState());
        BlockPos grass = pos(r.x + 8, FLOOR, r.z + 5);
        h.world.setBlockAndUpdate(grass, Blocks.GRASS_BLOCK.defaultBlockState());
        h.world.setBlockAndUpdate(grass.above(), Blocks.POPPY.defaultBlockState());
        BlockPos support = pos(r.x + 10, FLOOR + 1, r.z + 5);
        h.world.setBlockAndUpdate(support, Blocks.STONE.defaultBlockState());
        h.world.setBlockAndUpdate(support.above(), Blocks.SAND.defaultBlockState());
        stand(h.player, r.x + 2, r.z + 5, -90f);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.OAK_FENCE);
        BuilderOutcome placed = place(h, h.player, r.x + 6, FLOOR, r.z + 5, Facing.UP, 0.5f, 1f, 0.5f, keep, Symmetry.NONE);
        check(placed.accepted() && placed.changed() == 1, "only the new fence: " + placed);
        check(!h.world.getBlockState(fence).getValue(CrossCollisionBlock.EAST), "the old fence did not join");
        hold(h.player, Items.AIR);
        // The sand set above has its own fall check scheduled (2 ticks): break its support only after that has run on
        // the stone, as for sand that has lain there a while; breaking first left the sand to that check and its timing.
        // Waited for, not counted in ticks: scheduled ticks run only in a chunk that ticks with its entities loaded,
        // which a loaded machine may reach later than 5 ticks after the chunk was forced. Once it ran, a fall can come
        // only from a check the break schedules, and with Keep shape there must be none.
        BlockPos sand = support.above();
        context.startSequence()
                .thenWaitUntil(() -> check(!h.world.getBlockTicks().hasScheduledTick(sand, Blocks.SAND),
                        "the sand's own fall check is still scheduled"))
                .thenExecute(() -> {
                    check(h.world.getBlockState(sand).is(Blocks.SAND), "the sand lay on its support");
                    BuilderOutcome broken = breakCells(h, h.player, 1, List.of(grass, support), keep, false, true);
                    check(broken.accepted() && broken.changed() == 2, "two blocks broken: " + broken);
                    check(!h.world.getBlockTicks().hasScheduledTick(sand, Blocks.SAND),
                            "Keep shape scheduled a fall check for the sand");
                })
                .thenExecuteAfter(10, () -> {
                    check(h.world.getBlockState(grass.above()).is(Blocks.POPPY), "the flower floats");
                    check(h.world.getBlockState(support.above()).is(Blocks.SAND), "the sand stayed");
                    check(h.world.getEntitiesOfClass(ItemEntity.class, itemBox(r), e -> true).isEmpty(), "nothing dropped");
                    check(entries(h).size() == 2, "two steps: " + labels(entries(h)));
                })
                .thenWaitUntil(() -> undoAll(context, h, 2, before, r))
                .thenSucceed();
    }

    /** Place in air puts the block into the empty cell aimed at; a block that needs support needs Force place there. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_air", timeoutTicks = LIMIT)
    public void placeInAirAndForcePlace(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1063);
        int air = BuilderPower.PLACE_IN_AIR.bit();
        // A floating fence post: the cell beside it has no floor under it, so nothing there can hold a torch.
        BlockPos post = pos(r.x + 6, FLOOR + 4, r.z + 6);
        h.world.setBlockAndUpdate(post, Blocks.OAK_FENCE.defaultBlockState());
        stand(h.player, r.x + 2, r.z + 2, -90f);
        BlockPos cell = pos(r.x + 7, FLOOR + 6, r.z + 2);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.STONE);
        BuilderOutcome stone = place(h, h.player, cell.getX(), cell.getY(), cell.getZ(), Facing.WEST, 0f, 0.5f, 0.5f, air,
                Symmetry.NONE);
        check(stone.accepted() && h.world.getBlockState(cell).is(Blocks.STONE), "stone in the air: " + stone);
        hold(h.player, Items.POPPY);
        BlockPos flowerCell = cell.above(2);
        WorldSnapshot beforeFlower = capture(h.world, r.area);
        BuilderOutcome refused = place(h, h.player, flowerCell.getX(), flowerCell.getY(), flowerCell.getZ(), Facing.WEST,
                0f, 0.5f, 0.5f, air, Symmetry.NONE);
        check(refused.refusal() == Refusal.GAME_REFUSES, "a flower in the air without Force place: " + refused);
        checkSame(beforeFlower, capture(h.world, r.area), "a refused flower");
        BuilderOutcome forced = place(h, h.player, flowerCell.getX(), flowerCell.getY(), flowerCell.getZ(), Facing.WEST,
                0f, 0.5f, 0.5f, air | BuilderPower.FORCE_PLACE.bit(), Symmetry.NONE);
        check(forced.accepted() && h.world.getBlockState(flowerCell).is(Blocks.POPPY), "forced flower: " + forced);
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
        check(torch.accepted() && wall.is(Blocks.WALL_TORCH) && wall.getValue(WallTorchBlock.FACING) == Direction.WEST,
                "a forced wall torch: " + torch + " " + wall);
        check(entries(h).size() == 3, "three steps: " + labels(entries(h)));
        undoAll(context, h, 3, before, r);
    }

    /** Replace swaps the clicked block, keeping its facing and half; a replaced chest drops nothing and comes back full. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_replace", timeoutTicks = LIMIT)
    public void replaceKeepsOrientationAndContents(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1064);
        int replace = BuilderPower.REPLACE.bit();
        BlockPos stairs = pos(r.x + 6, FLOOR + 1, r.z + 4);
        h.world.setBlockAndUpdate(stairs, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH)
                .setValue(StairBlock.HALF, Half.TOP));
        BlockPos chest = pos(r.x + 6, FLOOR + 1, r.z + 7);
        h.world.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) h.world.getBlockEntity(chest)).setItem(3, new ItemStack(Items.DIAMOND, 5));
        stand(h.player, r.x + 2, r.z + 5, -90f);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.STONE_STAIRS);
        BuilderOutcome swapped = place(h, h.player, stairs.getX(), stairs.getY(), stairs.getZ(), Facing.WEST, 0f, 0.25f, 0.5f,
                replace, Symmetry.NONE);
        BlockState now = h.world.getBlockState(stairs);
        check(swapped.accepted() && now.is(Blocks.STONE_STAIRS) && now.getValue(StairBlock.FACING) == Direction.NORTH
                && now.getValue(StairBlock.HALF) == Half.TOP, "stairs kept their facing and half: " + now);
        hold(h.player, Items.OAK_PLANKS);
        BuilderOutcome chestSwapped = place(h, h.player, chest.getX(), chest.getY(), chest.getZ(), Facing.WEST, 0f, 0.5f,
                0.5f, replace, Symmetry.NONE);
        check(chestSwapped.accepted() && h.world.getBlockState(chest).is(Blocks.OAK_PLANKS), "chest replaced");
        context.startSequence()
                .thenExecuteAfter(5, () -> {
                    check(h.world.getEntitiesOfClass(ItemEntity.class, itemBox(r), e -> true).isEmpty(), "nothing dropped");
                    check(entries(h).get(0).label().equals("Replace · 1 block"), "label " + labels(entries(h)));
                })
                .thenWaitUntil(() -> undoAll(context, h, 2, before, r))
                .thenSucceed();
    }

    /**
     * A Replace whose placement does not happen (a chest replaced by the same chest: the game sets nothing, so the step
     * is not done) leaves the container as it was: its diamonds stay, nothing is recorded, every cell is the same. A
     * mirrored copy another plugin refuses is the Paper self-test's case: no mod cancels a builder step here.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_replace", timeoutTicks = LIMIT)
    public void replaceThatDoesNotHappenKeepsTheContainerFull(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1079);
        BlockPos chest = pos(r.x + 6, FLOOR + 1, r.z + 7);
        h.world.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) h.world.getBlockEntity(chest)).setItem(3, new ItemStack(Items.DIAMOND, 5));
        stand(h.player, r.x + 2, r.z + 7, -90f);
        WorldSnapshot before = capture(h.world, r.area);
        hold(h.player, Items.CHEST);
        BuilderOutcome same = place(h, h.player, chest.getX(), chest.getY(), chest.getZ(), Facing.WEST, 0f, 0.5f, 0.5f,
                BuilderPower.REPLACE.bit(), Symmetry.NONE);
        ItemStack kept = ((ChestBlockEntity) h.world.getBlockEntity(chest)).getItem(3);
        check(same.changed() == 0, "nothing placed: " + same);
        check(kept.is(Items.DIAMOND) && kept.getCount() == 5, "the chest keeps its diamonds: " + kept);
        check(entries(h).isEmpty(), "no step: " + labels(entries(h)));
        checkSame(before, capture(h.world, r.area), "after the Replace that did not happen");
        cleanup(h, List.of(), r);
        context.succeed();
    }

    /** Mirror places the block and its image (turned as the editor turns copies) as one step. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_mirror", timeoutTicks = LIMIT)
    public void mirrorPlacesAndBreaksImagesInOneStep(GameTestHelper context) {
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
        check(original.is(Blocks.OAK_STAIRS) && original.getValue(StairBlock.FACING) == Direction.EAST, "original " + original);
        check(image.is(Blocks.OAK_STAIRS) && image.getValue(StairBlock.FACING) == Direction.WEST, "mirrored " + image);
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
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_bulldozer", timeoutTicks = LIMIT)
    public void bulldozerDragIsOneStep(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1066);
        int dozer = BuilderPower.BULLDOZER.bit();
        BlockPos chest = pos(r.x + 9, FLOOR, r.z + 3);
        h.world.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) h.world.getBlockEntity(chest)).setItem(0, new ItemStack(Items.EMERALD, 12));
        for (int x = r.x + 4; x <= r.x + 8; x++) h.world.setBlockAndUpdate(pos(x, FLOOR, r.z + 6), Blocks.DIRT.defaultBlockState());
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.AIR);
        WorldSnapshot before = capture(h.world, r.area);
        check(breakCells(h, h.player, 9, List.of(pos(r.x + 5, FLOOR, r.z + 3), pos(r.x + 6, FLOOR, r.z + 3)), dozer, false,
                false).changed() == 2, "first message");
        check(entries(h).isEmpty() && h.service.builderDragOpen(h.player.getUUID()), "the drag is still open");
        check(breakCells(h, h.player, 9, List.of(pos(r.x + 7, FLOOR, r.z + 3), pos(r.x + 8, FLOOR, r.z + 3)), dozer, false,
                false).changed() == 2, "second message");
        check(breakCells(h, h.player, 9, List.of(chest), dozer, false, true).changed() == 1, "the chest");
        List<HistoryEntry> entries = entries(h);
        check(entries.size() == 1 && entries.get(0).label().equals("Bulldozer · 5 blocks"), "one step: " + labels(entries));
        // Same kind: the drag starts on stone, so the dirt row is left.
        h.world.setBlockAndUpdate(pos(r.x + 4, FLOOR + 1, r.z + 6), Blocks.STONE.defaultBlockState());
        WorldSnapshot beforeSameKind = capture(h.world, r.area);
        BuilderOutcome sameKind = breakCells(h, h.player, 10, List.of(pos(r.x + 4, FLOOR + 1, r.z + 6),
                pos(r.x + 5, FLOOR, r.z + 6), pos(r.x + 6, FLOOR, r.z + 6)), dozer, true, true);
        check(sameKind.changed() == 1 && sameKind.skipped() == 2, "same kind: " + sameKind);
        check(h.world.getBlockState(pos(r.x + 5, FLOOR, r.z + 6)).is(Blocks.DIRT), "dirt kept");
        context.startSequence()
                .thenExecuteAfter(5, () -> check(h.world.getEntitiesOfClass(ItemEntity.class, itemBox(r), e -> true).isEmpty(),
                        "nothing dropped"))
                .thenWaitUntil(() -> {
                    RecordingListener undo = new RecordingListener();
                    h.undo(undo);
                    context.startSequence()
                            .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                            .thenWaitUntil(() -> {
                                checkSame(beforeSameKind, capture(h.world, r.area), "after undoing the same-kind drag");
                                h.world.setBlockAndUpdate(pos(r.x + 4, FLOOR + 1, r.z + 6), Blocks.AIR.defaultBlockState());
                                undoAll(context, h, 1, before, r);
                            })
                            .thenSucceed();
                })
                .thenSucceed();
    }

    /** An editor undo while a drag is open ends the drag first: its step is the one undone. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_drag_undo", timeoutTicks = LIMIT)
    public void undoEndsAnOpenDragFirst(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1067);
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.AIR);
        WorldSnapshot before = capture(h.world, r.area);
        breakCells(h, h.player, 3, List.of(pos(r.x + 5, FLOOR, r.z + 4), pos(r.x + 6, FLOOR, r.z + 4)),
                BuilderPower.BULLDOZER.bit(), false, false);
        check(h.service.builderDragOpen(h.player.getUUID()), "open drag");
        undoAll(context, h, 1, before, r);
    }

    /** Every refusal writes nothing and adds no step. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_refusals", timeoutTicks = LIMIT)
    public void refusalsWriteNothing(GameTestHelper context) {
        long[] now = {1_000_000_000L};
        Harness h = new Harness(context, null, () -> now[0]);
        Region r = region(h, context, 1068);
        List<ServerPlayer> extra = new ArrayList<>();
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
            h.world.setBlock(operator, Blocks.COMMAND_BLOCK.defaultBlockState(), 2);
            hold(plain, Items.STONE);
            expect(place(h, plain, r.x + 6, FLOOR + 1, r.z + 5, Facing.UP, 0.5f, 1f, 0.5f, BuilderPower.REPLACE.bit(),
                    Symmetry.NONE), Refusal.CANNOT_BREAK, "replacing a command block");
            check(h.world.getBlockState(operator).is(Blocks.COMMAND_BLOCK), "the command block stays");
            h.world.setBlock(operator, Blocks.AIR.defaultBlockState(), 2);
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
            context.startSequence()
                    .thenWaitUntil(() -> check(job.result != null, "the job ends"))
                    .thenWaitUntil(() -> {
                        cleanup(h, extra, r);
                    })
                    .thenSucceed();
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
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_partial", timeoutTicks = LIMIT)
    public void partialFailuresWriteTheRestAsOneStep(GameTestHelper context) {
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
            check(h.world.getBlockState(pos(r.x + 6, FLOOR + 1, r.z + 4)).is(Blocks.STONE), "the original");
            check(h.world.getBlockState(pos(r.x + 13, FLOOR + 1, r.z + 4)).isAir(), "the protected image untouched");
            List<HistoryEntry> entries = entries(h);
            check(entries.size() == 1 && entries.get(0).label().equals("Place · 1 block"), "one step: " + labels(entries));
            // A drag whose middle cell is protected: the two others go, one step.
            hold(h.player, Items.AIR);
            BuilderOutcome swept = breakCells(h, h.player, 7, List.of(pos(r.x + 5, FLOOR, r.z + 8), pos(r.x + 13, FLOOR, r.z + 8),
                    pos(r.x + 6, FLOOR, r.z + 8)), BuilderPower.BULLDOZER.bit(), false, true);
            check(swept.accepted() && swept.changed() == 2 && swept.skipped() == 1, "two broken, one skipped: " + swept);
            check(h.world.getBlockState(pos(r.x + 13, FLOOR, r.z + 8)).is(Blocks.STONE), "the protected cell kept");
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
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_secondary", timeoutTicks = LIMIT)
    public void secondaryCellsRespectProtectionAndJobLocks(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1071);
        // The job is another player's, so the builder's history holds only the bed.
        List<ServerPlayer> extra = new ArrayList<>();
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
        context.startSequence()
                .thenWaitUntil(() -> check(h.world.getBlockState(pos(r.x + 16, FLOOR + 1, r.z + 4)).is(Blocks.GLASS)
                        || !h.service.jobs(other.getUUID()).isEmpty(), "the job runs"))
                .thenExecuteAfter(20, () -> {
                    check(h.service.jobs(other.getUUID()).isEmpty(), "the job ended");
                    // Breaking the foot takes the head with it: protected, so both halves stay.
                    h.world.setBlock(head, Blocks.AIR.defaultBlockState(), 2);
                    WorldSnapshot beforeBed = capture(h.world, r.area);
                    BuilderOutcome bed = place(h, h.player, r.x + 15, FLOOR, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
                    check(bed.accepted() && bed.changed() == 2, "the bed: " + bed);
                    check(labels(entries(h)).equals(List.of("Place · 2 blocks")), "a step: " + labels(entries(h)));
                    ProtectionHook.protect(h.player, h.world, r.x + 16, r.z, r.x + 31, r.z + 31);
                    hold(h.player, Items.AIR);
                    BuilderOutcome broken = breakCells(h, h.player, 3, List.of(foot), 0, false, true);
                    check(broken.refusal() == Refusal.PROTECTED && broken.changed() == 0, "the head is protected: " + broken);
                    check(h.world.getBlockState(foot).is(Blocks.RED_BED) && h.world.getBlockState(head).is(Blocks.RED_BED),
                            "both halves stay");
                    check(entries(h).size() == 1, "no step for the refused break: " + labels(entries(h)));
                    ProtectionHook.clear(h.player);
                    removePlayers(h, extra);
                    undoAll(context, h, 1, beforeBed, r);
                })
                .thenSucceed();
    }

    /**
     * A drag ends after 5 s ({@code BuilderService.DRAG_IDLE_SECONDS}) without breaks, and when the player leaves
     * (without saved history its record goes with the player).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_drag_ends", timeoutTicks = LIMIT)
    public void dragsEndOnIdleAndOnLeaving(GameTestHelper context) {
        long[] now = {1_000_000_000L};
        Harness h = new Harness(context, null, () -> now[0]);
        Region r = region(h, context, 1072);
        int dozer = BuilderPower.BULLDOZER.bit();
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.AIR);
        UUID owner = h.player.getUUID();
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
        context.succeed();
    }

    /**
     * Keep shape over water: breaking the block under a still water source leaves the water where it is (no neighbour
     * update, no fluid tick), and a bulldozed waterlogged slab leaves its water as the step's; the drag is undone and
     * redone exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_water", timeoutTicks = LIMIT)
    public void keepShapeOverWaterAndWaterloggedBreaksUndoExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1073);
        int powers = BuilderPower.BULLDOZER.bit() | BuilderPower.KEEP_SHAPE.bit();
        BlockPos under = pos(r.x + 10, FLOOR, r.z + 4);
        BlockPos water = pos(r.x + 10, FLOOR + 1, r.z + 4);
        BlockPos slab = pos(r.x + 5, FLOOR + 1, r.z + 4);
        h.world.setBlock(water, Blocks.WATER.defaultBlockState(), 2);
        h.world.setBlock(slab, Blocks.OAK_SLAB.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true), 2);
        stand(h.player, r.x + 2, r.z + 4, -90f);
        hold(h.player, Items.AIR);
        WorldSnapshot before = capture(h.world, r.area);
        try {
            check(breakCells(h, h.player, 1, List.of(under), powers, false, true).changed() == 1, "the block under the water");
            check(h.world.getBlockState(under).isAir() && h.world.getBlockState(water).is(Blocks.WATER), "broken, water still there");
        } catch (RuntimeException | Error e) {
            cleanup(h, List.of(), r);
            throw e;
        }
        context.startSequence()
                .thenExecuteAfter(10, () -> {
                    check(h.world.getBlockState(under).isAir(), "keep shape: the water did not flow down in 10 ticks");
                    check(h.world.getFluidState(water).isSource(), "the source is still a source");
                    WorldSnapshot afterFirst = capture(h.world, r.area);
                    BuilderOutcome slabBroken = breakCells(h, h.player, 2, List.of(slab), powers, false, true);
                    check(slabBroken.accepted() && slabBroken.changed() == 1, "the waterlogged slab: " + slabBroken);
                    check(h.world.getFluidState(slab).isSource() && h.world.getBlockState(slab).is(Blocks.WATER),
                            "its water is left behind");
                    check(labels(entries(h)).equals(List.of("Bulldozer · 1 block", "Bulldozer · 1 block")), labels(entries(h)).toString());
                    WorldSnapshot after = capture(h.world, r.area);
                    RecordingListener undo = new RecordingListener();
                    RecordingListener redo = new RecordingListener();
                    RecordingListener undoBoth = new RecordingListener();
                    context.startSequence()
                            .thenWaitUntil(() -> h.undo(undo))
                            .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                            .thenWaitUntil(() -> {
                                check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0, "undo " + undo.result);
                                checkSame(afterFirst, capture(h.world, r.area), "the slab is back exactly");
                                h.redo(redo);
                            })
                            .thenWaitUntil(() -> check(redo.result != null, "redo running"))
                            .thenWaitUntil(() -> {
                                check(redo.result.outcome() == JobOutcome.COMPLETED && redo.result.skippedConflicts() == 0, "redo " + redo.result);
                                checkSame(after, capture(h.world, r.area), "the drag redone exactly");
                                h.undo(undoBoth);
                            })
                            .thenWaitUntil(() -> check(undoBoth.result != null, "second undo running"))
                            .thenWaitUntil(() -> {
                                RecordingListener last = new RecordingListener();
                                h.undo(last);
                                context.startSequence()
                                        .thenWaitUntil(() -> check(last.result != null, "last undo running"))
                                        .thenWaitUntil(() -> {
                                            check(entries(h).isEmpty(), "everything undone");
                                            checkSame(before, capture(h.world, r.area), "back to the start exactly");
                                            cleanup(h, List.of(), r);
                                        })
                                        .thenSucceed();
                            })
                            .thenSucceed();
                })
                .thenSucceed();
    }

    /** While the player's large Shape step is still being written, builder actions are refused BUSY and spend nothing. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_busy", timeoutTicks = LIMIT)
    public void actionsWaitBehindAStrokeBeingWritten(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = ShapeBrushGameTest.onePartATick(context);
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
            throw new GameTestAssertException("stroke refused: " + e.getMessage());
        }
        check(h.service.dabs(h.player, 5, 1, List.of(ShapeBrushGameTest.at(0, x0 + 44, 120, z0 + 44))).accepted(), "the step");
        executor.tick(); // its first part
        stand(h.player, x0 + 2, z0 + 2, -90f);
        hold(h.player, Items.STONE);
        expect(place(h, h.player, x0 + 6, 86, z0 + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE), Refusal.BUSY, "place");
        hold(h.player, Items.AIR);
        expect(breakCells(h, h.player, 1, List.of(pos(x0 + 6, 86, z0 + 2)), 0, false, true), Refusal.BUSY, "break");
        check(!h.service.builderDragOpen(h.player.getUUID()), "no drag opened");
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1
                && h.service.queuedCommits(h.player.getUUID()) == 0, 100, "the step");
        context.startSequence()
                .thenWaitUntil(() -> {
                    hold(h.player, Items.STONE);
                    BuilderOutcome placed = place(h, h.player, x0 + 6, 86, z0 + 2, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
                    check(placed.accepted() && placed.changed() == 1, "after the step: " + placed);
                    h.service.endStroke(h.player, 5);
                    forceChunks(h.world, area, false);
                    executor.shutdown();
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * A message naming a cell twice breaks it once (the second is NOTHING) and still spends the budget for both, so a
     * flood of repeats runs into RATE_LIMITED; a level-2 op's command block placement and replacement work, a plain
     * builder's are refused ({@code refusalsWriteNothing} covers the refusals).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_repeats", timeoutTicks = LIMIT)
    public void repeatedCellsSpendTheBudget(GameTestHelper context) {
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
            check(placed.accepted() && h.world.getBlockState(pos(r.x + 8, FLOOR + 1, r.z + 4)).is(Blocks.COMMAND_BLOCK),
                    "an op's command block: " + placed);
            hold(h.player, Items.STONE);
            BuilderOutcome replaced = place(h, h.player, r.x + 8, FLOOR + 1, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f,
                    BuilderPower.REPLACE.bit(), Symmetry.NONE);
            check(replaced.accepted() && h.world.getBlockState(pos(r.x + 8, FLOOR + 1, r.z + 4)).is(Blocks.STONE),
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
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_tinker", timeoutTicks = LIMIT)
    public void tinkerPowerChangesGoThroughTinkerService(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1076);
        List<ServerPlayer> extra = new ArrayList<>();
        BlockPos stairs = pos(r.x + 5, FLOOR + 1, r.z + 4);
        int straight = h.state("minecraft:oak_stairs[facing=east]");
        int outer = h.state("minecraft:oak_stairs[facing=east,shape=outer_left]");
        h.world.setBlock(stairs, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST), 2);
        WorldSnapshot before = capture(h.world, r.area);
        dev.sculptory.core.BlockPos cell = new dev.sculptory.core.BlockPos(stairs.getX(), stairs.getY(), stairs.getZ());
        try {
            // A builder with the builder node but without region: Tinker refuses, nothing changes.
            ModePlayer builder = modePlayer(context, extra, false);
            EditTestSupport.grant(builder, dev.sculptory.server.engine.Perm.USE, dev.sculptory.server.engine.Perm.BUILDER);
            try {
                h.service.block(builder, cell, straight, outer, null);
                throw new GameTestAssertException("a builder without region changed a block through Tinker");
            } catch (EditRejected e) {
                check(e.reason() == RejectReason.NO_PERMISSION, "without region: " + e.reason());
            }
            check(h.world.getBlockState(stairs).getValue(StairBlock.SHAPE) == StairsShape.STRAIGHT, "unchanged");
            check(entries(h).isEmpty(), "no step");
            // The op's change, as Alt+Scroll sends it.
            h.service.block(h.player, cell, straight, outer, null);
            check(h.world.getBlockState(stairs).getValue(StairBlock.SHAPE) == StairsShape.OUTER_LEFT, "the shape changed");
            check(entries(h).size() == 1 && entries(h).get(0).label().startsWith("Tinker"), "one Tinker step: " + labels(entries(h)));
        } catch (EditRejected e) {
            cleanup(h, extra, r);
            throw new GameTestAssertException("Tinker refused the op: " + e.getMessage());
        } catch (RuntimeException | Error e) {
            cleanup(h, extra, r);
            throw e;
        }
        removePlayers(h, extra);
        undoAll(context, h, 1, before, r);
    }

    /**
     * A refused action is put back without touching comparators: a Keep shape bed whose head lands in a protected
     * column is reverted, and the comparator beside its head gets no neighbour update when the head's block entity goes
     * (NeoForge's {@code Level.removeBlockEntity} sends one). The comparator reads a lectern with a book but was set
     * without updates, so it is off and any neighbour update would schedule it a tick. It sits by the head, not the
     * foot: vanilla's bed placement updates the foot's neighbours itself ({@code BedBlock.setPlacedBy}), Keep shape or
     * not, while the head is written through the keep-shape capture without neighbour updates.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_revert_comparator", timeoutTicks = LIMIT)
    public void refusedActionLeavesComparatorsAlone(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1077);
        BlockPos foot = pos(r.x + 15, FLOOR + 1, r.z + 4);
        BlockPos head = pos(r.x + 16, FLOOR + 1, r.z + 4);
        BlockPos comparator = head.north();
        BlockPos lectern = comparator.north();
        try {
            h.world.setBlockAndUpdate(lectern, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true));
            ((LecternBlockEntity) h.world.getBlockEntity(lectern)).setBook(new ItemStack(Items.WRITABLE_BOOK));
            BlockState reading = Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.NORTH);
            h.world.setBlock(comparator, reading, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            check(!h.world.getBlockTicks().hasScheduledTick(comparator, Blocks.COMPARATOR), "setup: a comparator tick");
            stand(h.player, r.x + 12, r.z + 4, -90f); // facing east: the head lies at x + 1
            hold(h.player, Items.RED_BED);
            ProtectionHook.protect(h.player, h.world, r.x + 16, r.z, r.x + 31, r.z + 31);
            BuilderOutcome placed = place(h, h.player, r.x + 15, FLOOR, r.z + 4, Facing.UP, 0.5f, 1f, 0.5f,
                    BuilderPower.KEEP_SHAPE.bit(), Symmetry.NONE);
            check(placed.refusal() == Refusal.PROTECTED && placed.changed() == 0, "the head is protected: " + placed);
            check(h.world.getBlockState(foot).isAir() && h.world.getBlockState(head).isAir(), "both halves put back");
            check(!h.world.getBlockTicks().hasScheduledTick(comparator, Blocks.COMPARATOR), "the comparator was updated");
            check(entries(h).isEmpty(), "no step: " + labels(entries(h)));
        } catch (RuntimeException | Error e) {
            cleanup(h, List.of(), r);
            throw e;
        }
        context.runAfterDelay(10, () -> {
            try {
                check(!h.world.getBlockState(comparator).getValue(ComparatorBlock.POWERED), "the comparator reacted");
            } finally {
                cleanup(h, List.of(), r);
            }
            context.succeed();
        });
    }

    /**
     * A step that throws an {@link Error} (here a stack overflow, after the game placed its block) still ends its
     * action: the Keep shape scope and the builder capture are released (the server thread would otherwise suppress
     * every block callback for the rest of the run), what the step changed is put back, no step is added, and the error
     * goes on to the caller. A normal placement works right after.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_error", timeoutTicks = LIMIT)
    public void anErrorInAStepStillEndsTheAction(GameTestHelper context) {
        Harness h = new Harness(context);
        Region r = region(h, context, 1078);
        try {
            stand(h.player, r.x + 2, r.z + 2, -90f);
            hold(h.player, Items.STONE);
            BuilderService failing = new BuilderService(h.service, h.runtime, h.world.getServer(), new ErrorSteps(),
                    h.runtime.executor(), h.service.historyService(), System::nanoTime);
            BlockPos cell = pos(r.x + 6, FLOOR + 1, r.z + 2);
            StackOverflowError thrown = null;
            try {
                failing.place(h.player, new C2S.BuilderPlace(nextSeq++, false,
                        new dev.sculptory.core.BlockPos(cell.getX(), FLOOR, cell.getZ()), Facing.UP, 0.5f, 1f, 0.5f,
                        BuilderPower.KEEP_SHAPE.bit(), Symmetry.NONE));
            } catch (StackOverflowError e) {
                thrown = e;
            }
            check(thrown != null && ErrorSteps.MESSAGE.equals(thrown.getMessage()), "the error went on: " + thrown);
            check(!EditScope.isSuppressing(), "the Keep shape scope is still held by the server thread");
            try (BuilderCapture again = BuilderCapture.open(h.world, false, true)) {
                check(again.touchedCells() == 0, "a fresh capture");
            } catch (IllegalStateException e) {
                throw new GameTestAssertException("the builder capture is still open: " + e.getMessage());
            }
            check(h.world.getBlockState(cell).isAir(), "what the step placed is put back: " + h.world.getBlockState(cell));
            check(entries(h).isEmpty(), "no step: " + labels(entries(h)));
            BuilderOutcome next = place(h, h.player, cell.getX(), FLOOR, cell.getZ(), Facing.UP, 0.5f, 1f, 0.5f, 0,
                    Symmetry.NONE);
            check(next.accepted() && next.changed() == 1, "a placement right after: " + next);
        } finally {
            cleanup(h, List.of(), r);
        }
        context.succeed();
    }

    /** Fabric's and NeoForge's steps, except that a placement throws a {@link StackOverflowError} once it is done. */
    private static final class ErrorSteps implements BuilderSteps {
        static final String MESSAGE = "a builder step failed (test)";

        @Override
        public Action open(ServerLevel world, ServerPlayer player, boolean keepShape) {
            Action inner = CapturedBuilderSteps.INSTANCE.open(world, player, keepShape);
            return new Action() {
                @Override
                public void emptyContainer(BlockPos pos) {
                    inner.emptyContainer(pos);
                }

                @Override
                public Step place(BlockItem item, BlockPlaceContext context, BlockState state, boolean copy) {
                    inner.place(item, context, state, copy);
                    throw new StackOverflowError(MESSAGE);
                }

                @Override
                public Step breakBlock(BlockPos pos) {
                    return inner.breakBlock(pos);
                }

                @Override
                public List<BuilderChange> finish() {
                    return inner.finish();
                }
            };
        }

        @Override
        public void revert(ServerLevel world, List<BuilderChange> changes) {
            CapturedBuilderSteps.INSTANCE.revert(world, changes);
        }
    }

    /** A target in a chunk that is not loaded is refused and loads nothing. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_unloaded", timeoutTicks = LIMIT)
    public void unloadedTargetsAreRefused(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1069);
        // A place no chunk of which is loaded: the player stands there only for this tick, at the east edge of a chunk,
        // and the target (3 blocks east) is in the next one. Moving an entity loads its chunk on NeoForge (a patch in
        // Entity.setPosRaw), so only the target's chunk is unloaded on every loader.
        int x = at[0] + 4096 * 3 + 14, z = at[1] + 8;
        stand(h.player, x, z, -90f);
        hold(h.player, Items.STONE);
        boolean loadedBefore = h.world.hasChunk((x + 3) >> 4, z >> 4);
        BuilderOutcome outcome = place(h, h.player, x + 3, FLOOR, z, Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE);
        check(!loadedBefore, "the far chunk was loaded before the test");
        check(outcome.refusal() == Refusal.UNLOADED, "unloaded: " + outcome);
        check(!h.world.hasChunk((x + 3) >> 4, z >> 4), "the refusal loaded nothing");
        check(entries(h).isEmpty(), "no step");
        h.close();
        context.succeed();
    }

    /** Long reach raises the block interaction range to 64 exactly while the power, Creative and the node all hold. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_reach", timeoutTicks = LIMIT)
    public void longReachFollowsPowerModeAndPermission(GameTestHelper context) {
        Harness h = new Harness(context);
        List<ServerPlayer> extra = new ArrayList<>();
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
            h.service.playerLeft(player.getUUID());
            check(range(player) == base, "left: " + range(player));
            // The reach check itself: 64 blocks and the margin.
            stand(player, 0, 0, 0f);
            BlockPos near = pos(64, 100, 0), far = pos(66, 100, 0);
            check(h.service.builderReaches(player, near.getX(), near.getY(), near.getZ()), "64 blocks away");
            check(!h.service.builderReaches(player, far.getX(), far.getY(), far.getZ()), "66 blocks away");
        } finally {
            cleanup(h, extra, null);
        }
        context.succeed();
    }

    // ============================================================================================ Record

    /**
     * With only the Record power on, builder mode takes the player's plain clicks: a place and a break (the client sends
     * them as builder actions) are one step each, the game's own placement of a held block is held back (the click came
     * as {@code BuilderPlace}), an item frame hung through the game's own item use is a step, Long reach stays off, and
     * undoing the three steps puts the area back exactly, with no item dropped. Through the server's own edit service,
     * so the mixins are part of what is tested.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_record", timeoutTicks = LIMIT)
    public void recordAloneRecordsPlacesBreaksAndFramesAndUndoesExactly(GameTestHelper context) {
        Installed s = new Installed(context, 1081, true);
        double base = range(s.player);
        s.host.builderPowers(s.player, BuilderPower.RECORD.bit());
        check(s.host.builderPowersOf(s.player.getUUID()) == BuilderPower.RECORD.bit(), "the server keeps Record");
        check(range(s.player) == base, "Record is not Long reach: " + range(s.player));
        Region r = s.r;
        BlockPos wall = pos(r.x + 6, FLOOR + 1, r.z + 3);
        s.world.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
        stand(s.player, r.x + 6, r.z + 7, 180f);
        WorldSnapshot before = capture(s.world, r.area);
        RecordingListener[] undo = {null};
        int[] undone = {0};
        net.minecraft.gametest.framework.GameTestSequence steps = s.sequence().thenExecute(() -> {
            hold(s.player, Items.STONE);
            BuilderOutcome placed = s.host.builderPlace(s.player, new C2S.BuilderPlace(nextSeq++, false,
                    new dev.sculptory.core.BlockPos(r.x + 6, FLOOR, r.z + 5), Facing.UP, 0.5f, 1f, 0.5f, 0,
                    Symmetry.NONE));
            check(placed.accepted() && placed.changed() == 1, "place " + placed);
            check(s.world.getBlockState(pos(r.x + 6, FLOOR + 1, r.z + 5)).is(Blocks.STONE), "stone placed");
            s.useOn(pos(r.x + 4, FLOOR, r.z + 5), Direction.UP);
            check(s.world.getBlockState(pos(r.x + 4, FLOOR + 1, r.z + 5)).isAir(),
                    "the game's own placement is held back while builder mode takes the clicks");
            BuilderOutcome broken = s.host.builderBreak(s.player, new C2S.BuilderBreak(nextSeq++, 41,
                    List.of(new dev.sculptory.core.BlockPos(r.x + 8, FLOOR, r.z + 5)), 0, Symmetry.NONE, false, true));
            check(broken.accepted() && broken.changed() == 1, "break " + broken);
            check(s.world.getBlockState(pos(r.x + 8, FLOOR, r.z + 5)).isAir(), "the floor block is broken");
            hold(s.player, Items.ITEM_FRAME);
            s.useOn(wall, Direction.SOUTH);
            check(s.frames().size() == 1, "one frame hung: " + s.frames().size());
            s.expectLabels("Place · item frame", "Break · 1 block", "Place · 1 block");
            s.noItems("after the actions");
        });
        for (int i = 0; i < 3; i++) {
            steps.thenExecute(() -> undo[0] = s.step(true))
                    .thenWaitUntil(() -> check(undo[0].result != null, "undo running"))
                    .thenExecute(() -> {
                        check(undo[0].result.outcome() == JobOutcome.COMPLETED
                                && undo[0].result.skippedConflicts() == 0, "undo " + undo[0].result);
                        undone[0]++;
                    });
        }
        steps.thenExecute(() -> {
            check(undone[0] == 3, "undone " + undone[0]);
            s.expectLabels();
            check(s.frames().isEmpty(), "the frame is gone again");
            checkSame(before, capture(s.world, r.area), "after the three undos");
            s.noItems("after the undos");
            s.finish();
        }).thenSucceed();
    }

    /**
     * The negative control: with no power on, the game's own placement, break and frame placement happen as in vanilla
     * and nothing goes into the history.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_record_off", timeoutTicks = LIMIT)
    public void withNoPowerOnNothingIsRecorded(GameTestHelper context) {
        Installed s = new Installed(context, 1082, true);
        s.host.builderPowers(s.player, 0);
        Region r = s.r;
        BlockPos wall = pos(r.x + 6, FLOOR + 1, r.z + 3);
        s.world.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
        stand(s.player, r.x + 6, r.z + 7, 180f);
        s.sequence().thenExecute(() -> {
            hold(s.player, Items.STONE);
            BlockPos placed = pos(r.x + 4, FLOOR + 1, r.z + 5);
            s.useOn(placed.below(), Direction.UP);
            check(s.world.getBlockState(placed).is(Blocks.STONE), "the game placed the stone");
            check(s.player.gameMode.destroyBlock(placed), "the game broke it");
            check(s.world.getBlockState(placed).isAir(), "broken");
            hold(s.player, Items.ITEM_FRAME);
            s.useOn(wall, Direction.SOUTH);
            check(s.frames().size() == 1, "the game hung the frame: " + s.frames().size());
            s.expectLabels();
            s.frames().forEach(frame -> frame.discard());
            s.noItems("after the actions");
            s.finish();
        }).thenSucceed();
    }

    /**
     * Record on where builder mode cannot act (Survival, or without the builder node): the client clicks the vanilla way
     * there, so the server leaves the game's own placement alone and records nothing; in Creative with the node the same
     * click is held back again (it comes as {@code BuilderPlace}).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_record_survival", timeoutTicks = LIMIT)
    public void recordWhereBuilderModeCannotActLeavesTheGamesPlacement(GameTestHelper context) {
        Installed s = new Installed(context, 1083, false);
        s.host.builderPowers(s.player, BuilderPower.RECORD.bit());
        Region r = s.r;
        BlockPos wall = pos(r.x + 6, FLOOR + 1, r.z + 3);
        s.world.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
        stand(s.player, r.x + 6, r.z + 7, 180f);
        s.sequence().thenExecute(() -> {
            try {
                hold(s.player, Items.STONE);
                s.useOn(pos(r.x + 4, FLOOR, r.z + 5), Direction.UP);
                check(s.world.getBlockState(pos(r.x + 4, FLOOR + 1, r.z + 5)).is(Blocks.STONE),
                        "in Survival the game places the block");
                hold(s.player, Items.ITEM_FRAME);
                s.useOn(wall, Direction.SOUTH);
                check(s.frames().size() == 1, "in Survival the game hangs the frame: " + s.frames().size());
                s.expectLabels();

                s.creative(true);
                hold(s.player, Items.STONE);
                s.useOn(pos(r.x + 5, FLOOR, r.z + 5), Direction.UP);
                check(s.world.getBlockState(pos(r.x + 5, FLOOR + 1, r.z + 5)).isAir(),
                        "in Creative the click is builder mode's: the game's placement is held back");

                EditTestSupport.deny(s.player, dev.sculptory.server.engine.Perm.BUILDER);
                s.useOn(pos(r.x + 7, FLOOR, r.z + 5), Direction.UP);
                check(s.world.getBlockState(pos(r.x + 7, FLOOR + 1, r.z + 5)).is(Blocks.STONE),
                        "without the builder node the game places the block");
                s.expectLabels();
            } finally {
                EditTestSupport.deny(s.player);
                s.frames().forEach(frame -> frame.discard());
                s.finish();
            }
        }).thenSucceed();
    }

    /** A spectator with Record on: the game's item use does nothing, builder actions are refused, nothing recorded. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_record_spectator", timeoutTicks = LIMIT)
    public void recordForASpectatorPlacesAndRecordsNothing(GameTestHelper context) {
        Installed s = new Installed(context, 1084, false);
        s.host.builderPowers(s.player, BuilderPower.RECORD.bit());
        Region r = s.r;
        BlockPos wall = pos(r.x + 6, FLOOR + 1, r.z + 3);
        s.world.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
        stand(s.player, r.x + 6, r.z + 7, 180f);
        WorldSnapshot before = capture(s.world, r.area);
        s.sequence().thenExecute(() -> {
            try {
                s.player.spectator = true;
                s.player.setGameMode(GameType.SPECTATOR);
                hold(s.player, Items.STONE);
                s.useOn(pos(r.x + 4, FLOOR, r.z + 5), Direction.UP);
                hold(s.player, Items.ITEM_FRAME);
                s.useOn(wall, Direction.SOUTH);
                hold(s.player, Items.STONE);
                BuilderOutcome placed = s.host.builderPlace(s.player, new C2S.BuilderPlace(nextSeq++, false,
                        new dev.sculptory.core.BlockPos(r.x + 6, FLOOR, r.z + 5), Facing.UP, 0.5f, 1f, 0.5f, 0,
                        Symmetry.NONE));
                expect(placed, Refusal.NOT_CREATIVE, "a spectator's place");
                BuilderOutcome broken = s.host.builderBreak(s.player, new C2S.BuilderBreak(nextSeq++, 42,
                        List.of(new dev.sculptory.core.BlockPos(r.x + 8, FLOOR, r.z + 5)), 0, Symmetry.NONE, false,
                        true));
                expect(broken, Refusal.NOT_CREATIVE, "a spectator's break");
                check(s.frames().isEmpty(), "no frame hung by a spectator");
                checkSame(before, capture(s.world, r.area), "a spectator changed blocks");
                s.expectLabels();
            } finally {
                s.player.spectator = false;
                s.frames().forEach(frame -> frame.discard());
                s.finish();
            }
        }).thenSucceed();
    }

    /**
     * Record on, clicks refused where builder mode refuses a block: on a protected cell and on a cell the global mask
     * rejects, a place, a break and a frame are refused (the masked break is skipped: NOTHING), nothing is placed or
     * broken and nothing recorded.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_record_refused", timeoutTicks = LIMIT)
    public void recordRefusalsOnProtectedAndMaskedCellsWriteNothing(GameTestHelper context) {
        Installed s = new Installed(context, 1085, true);
        s.host.builderPowers(s.player, BuilderPower.RECORD.bit());
        Region r = s.r;
        BlockPos wall = pos(r.x + 6, FLOOR + 1, r.z + 3);
        s.world.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
        BlockPos maskedWall = pos(r.x + 20, FLOOR + 3, r.z + 3);
        s.world.setBlock(maskedWall, Blocks.STONE.defaultBlockState(), 2);
        stand(s.player, r.x + 6, r.z + 7, 180f);
        WorldSnapshot before = capture(s.world, r.area);
        s.sequence().thenExecute(() -> {
            try {
                ProtectionHook.protect(s.player, s.world, r.x, r.z, r.x + 12, r.z + 12);
                hold(s.player, Items.STONE);
                expect(s.host.builderPlace(s.player, new C2S.BuilderPlace(nextSeq++, false,
                        new dev.sculptory.core.BlockPos(r.x + 6, FLOOR, r.z + 5), Facing.UP, 0.5f, 1f, 0.5f, 0,
                        Symmetry.NONE)), Refusal.PROTECTED, "a place on a protected cell");
                expect(s.host.builderBreak(s.player, new C2S.BuilderBreak(nextSeq++, 43,
                        List.of(new dev.sculptory.core.BlockPos(r.x + 8, FLOOR, r.z + 5)), 0, Symmetry.NONE, false,
                        true)), Refusal.PROTECTED, "a break of a protected cell");
                hold(s.player, Items.ITEM_FRAME);
                s.useOn(wall, Direction.SOUTH);
                check(s.frames().isEmpty(), "no frame on a protected cell");
                ProtectionHook.clear(s.player);

                // The global mask lets through only cells up to the floor + 1.
                EditMasks.set(s.player.getUUID(), new EditMask(List.of(MaskEntry.of(
                        new MaskRule.Height(FLOOR - 10, FLOOR + 1))), false), region -> region, s.states());
                stand(s.player, r.x + 20, r.z + 7, 180f);
                hold(s.player, Items.STONE);
                // On top of the raised stone: the new block would be at the floor + 4, above the mask.
                expect(s.host.builderPlace(s.player, new C2S.BuilderPlace(nextSeq++, false,
                        new dev.sculptory.core.BlockPos(maskedWall.getX(), maskedWall.getY(), maskedWall.getZ()),
                        Facing.UP, 0.5f, 1f, 0.5f, 0, Symmetry.NONE)), Refusal.MASKED, "a place above the mask");
                // A break's cells the mask rejects are skipped, as Bulldozer skips them: with none left the break
                // says NOTHING and changes nothing.
                expect(s.host.builderBreak(s.player, new C2S.BuilderBreak(nextSeq++, 44,
                        List.of(new dev.sculptory.core.BlockPos(maskedWall.getX(), maskedWall.getY(), maskedWall.getZ())),
                        0, Symmetry.NONE, false, true)), Refusal.NOTHING, "a break above the mask");
                check(s.world.getBlockState(maskedWall).is(Blocks.STONE), "the masked block is still there");
                hold(s.player, Items.ITEM_FRAME);
                s.useOn(maskedWall, Direction.SOUTH);
                check(s.frames().isEmpty(), "no frame where the mask refuses");
                checkSame(before, capture(s.world, r.area), "a refused click changed blocks");
                s.expectLabels();
                s.noItems("after the refusals");
            } catch (EditRejected e) {
                throw new GameTestAssertException("mask refused: " + e.getMessage());
            } finally {
                ProtectionHook.clear(s.player);
                EditMasks.reset(s.player.getUUID());
                s.frames().forEach(frame -> frame.discard());
                s.finish();
            }
        }).thenSucceed();
    }

    /**
     * Editing switched off (the config, or the failsafe for a malformed one): builder mode does not act, so the game's
     * own placement and frame use go ahead and nothing is recorded (the client is told it has no use or builder node,
     * see ServerDispatcher); switched on again, the same click is held back and builder actions are recorded again.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_builder_record_disabled", timeoutTicks = LIMIT)
    public void editingSwitchedOffLeavesTheGamesPlacementAndRecordsNothing(GameTestHelper context) {
        Installed s = new Installed(context, 1086, true);
        s.host.builderPowers(s.player, BuilderPower.RECORD.bit());
        Region r = s.r;
        BlockPos wall = pos(r.x + 6, FLOOR + 1, r.z + 3);
        s.world.setBlock(wall, Blocks.STONE.defaultBlockState(), 2);
        stand(s.player, r.x + 6, r.z + 7, 180f);
        var config = dev.sculptory.mc.engine.impl.EngineRuntime.find(s.world.getServer())
                .orElseThrow(() -> new GameTestAssertException("no engine runtime")).config();
        s.sequence().thenExecute(() -> {
            try {
                config.editingEnabled = false;
                check(!s.host.editingEnabled(s.player), "the service says editing is off");
                hold(s.player, Items.STONE);
                s.useOn(pos(r.x + 4, FLOOR, r.z + 5), Direction.UP);
                check(s.world.getBlockState(pos(r.x + 4, FLOOR + 1, r.z + 5)).is(Blocks.STONE),
                        "editing off: the game places the block");
                hold(s.player, Items.ITEM_FRAME);
                s.useOn(wall, Direction.SOUTH);
                check(s.frames().size() == 1, "editing off: the game hangs the frame");
                s.expectLabels();

                config.editingEnabled = true;
                check(s.host.editingEnabled(s.player), "on again");
                hold(s.player, Items.STONE);
                s.useOn(pos(r.x + 8, FLOOR, r.z + 5), Direction.UP);
                check(s.world.getBlockState(pos(r.x + 8, FLOOR + 1, r.z + 5)).isAir(),
                        "editing on: the click is builder mode's, the game's placement is held back");
                BuilderOutcome placed = s.host.builderPlace(s.player, new C2S.BuilderPlace(nextSeq++, false,
                        new dev.sculptory.core.BlockPos(r.x + 8, FLOOR, r.z + 5), Facing.UP, 0.5f, 1f, 0.5f, 0,
                        Symmetry.NONE));
                check(placed.accepted() && placed.changed() == 1, "editing on: the place " + placed);
                s.expectLabels("Place · 1 block");
            } finally {
                config.editingEnabled = true;
                s.frames().forEach(frame -> frame.discard());
                s.finish();
            }
        }).thenSucceed();
    }

    /**
     * A player of the server's own edit service, the one the mixins call (unlike {@link Harness}'s private one): a mock
     * op player whose game mode the test sets, in a private area.
     */
    private static final class Installed {
        final GameTestHelper context;
        final ServerLevel world;
        final EngineEditService<ServerPlayer, ServerLevel> host;
        final List<ServerPlayer> extra = new ArrayList<>();
        final ModePlayer player;
        final Region r;

        Installed(GameTestHelper context, int slot, boolean creative) {
            this.context = context;
            this.world = context.getLevel();
            this.host = EditServiceHost.find(world.getServer())
                    .orElseThrow(() -> new GameTestAssertException("the mod did not install the edit service"));
            this.r = region(world, context, slot);
            this.player = modePlayer(context, extra, true);
            host.playerJoined(player.getUUID());
            creative(creative);
        }

        /** The server's block states, for a mask. */
        dev.sculptory.core.state.StateSpace states() {
            return dev.sculptory.mc.engine.impl.EngineRuntime.find(world.getServer())
                    .orElseThrow(() -> new GameTestAssertException("no engine runtime")).states();
        }

        /** Creative for builder mode's gate and for the game's own item use (no item spent, nothing dropped). */
        void creative(boolean on) {
            player.creative = on;
            player.setGameMode(on ? GameType.CREATIVE : GameType.SURVIVAL);
        }

        /** Waits until the area's entities and the player's history are loaded. */
        net.minecraft.gametest.framework.GameTestSequence sequence() {
            return context.startSequence().thenWaitUntil(() -> {
                check(McEntities.firstUnloaded(world, r.area) == null, "the area's entities are not loaded yet");
                check(!host.historyService().session(player.getUUID()).loading(), "the history is loading");
            });
        }

        /** The held item's use on {@code face} of {@code block}, as the game runs it for the use-on packet. */
        void useOn(BlockPos block, Direction face) {
            Vec3 hit = Vec3.atCenterOf(block).relative(face, 0.5);
            player.gameMode.useItemOn(player, world, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, face, block, false));
        }

        List<ItemFrame> frames() {
            return world.getEntitiesOfClass(ItemFrame.class, itemBox(r), e -> !e.isRemoved());
        }

        void noItems(String when) {
            List<ItemEntity> items = world.getEntitiesOfClass(ItemEntity.class, itemBox(r), e -> !e.isRemoved());
            check(items.isEmpty(), "no item entity " + when + ": " + items);
        }

        void expectLabels(String... labels) {
            List<String> undo = host.history(player).undoLabels();
            check(undo.equals(List.of(labels)), "history " + undo + ", expected " + List.of(labels));
        }

        RecordingListener step(boolean undo) {
            RecordingListener listener = new RecordingListener();
            try {
                if (undo) {
                    host.undo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
                } else {
                    host.redo(player, ConflictPolicy.SKIP_CONFLICTS, listener);
                }
            } catch (EditRejected e) {
                throw new GameTestAssertException((undo ? "undo" : "redo") + " refused: " + e.getMessage());
            }
            return listener;
        }

        void finish() {
            host.builderPowers(player, 0);
            host.cancelAll(player.getUUID());
            for (ServerPlayer p : extra) {
                try {
                    host.playerLeft(p.getUUID());
                    world.getServer().getPlayerList().getOps().remove(p.getGameProfile());
                    world.getServer().getPlayerList().remove(p);
                } catch (RuntimeException e) {
                    org.slf4j.LoggerFactory.getLogger("sculptory").warn("Could not remove a mock player", e);
                }
            }
            extra.clear();
            forceChunks(world, r.area, false);
        }
    }

    // ============================================================================================ helpers

    /** A private work area: a stone floor at {@link #FLOOR}, air above, loaded and forced. */
    private record Region(int x, int z, Box area) {}

    private static Region region(Harness h, GameTestHelper context, int slot) {
        return region(h.world, context, slot);
    }

    private static Region region(ServerLevel world, GameTestHelper context, int slot) {
        int[] at = regionCorner(context, slot);
        Box area = box(at[0], FLOOR, at[1], at[0] + 31, FLOOR + 45, at[1] + 31);
        loadAndForce(world, area);
        for (int x = at[0]; x <= at[0] + 31; x++) {
            for (int z = at[1]; z <= at[1] + 31; z++) {
                world.setBlock(pos(x, FLOOR, z), Blocks.STONE.defaultBlockState(), 2);
                for (int y = FLOOR + 1; y <= FLOOR + 45; y++) {
                    world.setBlock(pos(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        return new Region(at[0], at[1], area);
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static net.minecraft.world.phys.AABB itemBox(Region r) {
        return new net.minecraft.world.phys.AABB(r.x - 4, FLOOR - 4, r.z - 4, r.x + 36, FLOOR + 50, r.z + 36);
    }

    /** Stands the player on the floor at (x, z), looking along {@code yaw} (-90: east), level. */
    private static void stand(ServerPlayer player, int x, int z, float yaw) {
        player.moveTo(x + 0.5, FLOOR + 1, z + 0.5, yaw, 0f);
        player.setYHeadRot(yaw);
    }

    private static void hold(ServerPlayer player, Item item) {
        player.setItemInHand(InteractionHand.MAIN_HAND, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item, 64));
    }

    private static int nextSeq = 1;

    private static BuilderOutcome place(Harness h, ServerPlayer player, int x, int y, int z, Facing side, float hx,
                                        float hy, float hz, int powers, Symmetry symmetry) {
        return h.service.builderPlace(player, new C2S.BuilderPlace(nextSeq++, false, new dev.sculptory.core.BlockPos(x, y, z),
                side, hx, hy, hz, powers, symmetry));
    }

    private static BuilderOutcome breakCells(Harness h, ServerPlayer player, int dragId, List<BlockPos> cells, int powers,
                                             boolean sameKind, boolean last) {
        return breakCells(h, player, dragId, cells, powers, Symmetry.NONE, sameKind, last);
    }

    private static BuilderOutcome breakCells(Harness h, ServerPlayer player, int dragId, List<BlockPos> cells, int powers,
                                             Symmetry symmetry, boolean sameKind, boolean last) {
        List<dev.sculptory.core.BlockPos> wire = new ArrayList<>();
        for (BlockPos cell : cells) wire.add(new dev.sculptory.core.BlockPos(cell.getX(), cell.getY(), cell.getZ()));
        return h.service.builderBreak(player, new C2S.BuilderBreak(nextSeq++, dragId, wire, powers, symmetry, sameKind, last));
    }

    private static void expect(BuilderOutcome outcome, Refusal refusal, String what) {
        check(outcome.refusal() == refusal && outcome.changed() == 0, what + ": " + outcome);
    }

    private static List<HistoryEntry> entries(Harness h) {
        return h.service.historyService().undoEntries(h.player.getUUID());
    }

    private static List<String> labels(List<HistoryEntry> entries) {
        return entries.stream().map(HistoryEntry::label).toList();
    }

    private static double range(ServerPlayer player) {
        return player.getAttributeValue(Attributes.BLOCK_INTERACTION_RANGE);
    }

    /** Undoes, waits, redoes, waits: the area matches the snapshots exactly each time. */
    private static void undoThenRedo(GameTestHelper context, Harness h, WorldSnapshot before, WorldSnapshot after, Region r) {
        RecordingListener undo = new RecordingListener();
        RecordingListener redo = new RecordingListener();
        context.startSequence()
                .thenWaitUntil(() -> h.undo(undo))
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    checkSame(before, capture(h.world, r.area), "after undo");
                    h.redo(redo);
                })
                .thenWaitUntil(() -> check(redo.result != null, "redo running"))
                .thenWaitUntil(() -> {
                    check(redo.result.outcome() == JobOutcome.COMPLETED && redo.result.skippedConflicts() == 0,
                            "redo " + redo.result);
                    checkSame(after, capture(h.world, r.area), "after redo");
                    cleanup(h, List.of(), r);
                })
                .thenSucceed();
    }

    /** Undoes {@code steps} steps one after another; then the area matches {@code before} exactly. */
    private static void undoAll(GameTestHelper context, Harness h, int steps, WorldSnapshot before, Region r) {
        RecordingListener[] undo = {null};
        int[] done = {0};
        net.minecraft.gametest.framework.GameTestSequence runner = context.startSequence();
        for (int i = 0; i < steps; i++) {
            runner.thenWaitUntil(() -> {
                undo[0] = new RecordingListener();
                h.undo(undo[0]);
            }).thenWaitUntil(() -> check(undo[0].result != null, "undo running")).thenWaitUntil(() -> {
                check(undo[0].result.outcome() == JobOutcome.COMPLETED && undo[0].result.skippedConflicts() == 0,
                        "undo " + undo[0].result);
                done[0]++;
            });
        }
        runner.thenWaitUntil(() -> {
            check(done[0] == steps, "undone " + done[0]);
            check(entries(h).isEmpty(), "every step undone: " + labels(entries(h)));
            checkSame(before, capture(h.world, r.area), "after " + steps + " undos");
            cleanup(h, List.of(), r);
        }).thenSucceed();
    }

    private static void cleanup(Harness h, List<ServerPlayer> extra, Region r) {
        removePlayers(h, extra);
        ProtectionHook.clear(h.player);
        if (r != null) forceChunks(h.world, r.area, false);
        h.close();
    }

    /** Deops and removes the extra mock players (their engine state first). */
    private static void removePlayers(Harness h, List<ServerPlayer> extra) {
        for (ServerPlayer player : extra) {
            try {
                h.service.playerLeft(player.getUUID());
                h.world.getServer().getPlayerList().getOps().remove(player.getGameProfile());
                EditTestSupport.deny(player);
                h.world.getServer().getPlayerList().remove(player);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger("sculptory").warn("Could not remove a mock player", e);
            }
        }
        if (!extra.isEmpty()) extra.clear();
    }

    /** A mock player whose game mode the test sets ({@code createMockCreativeServerPlayerInWorld} is always creative). */
    private static final class ModePlayer extends ServerPlayer {
        boolean creative = true;
        boolean spectator;

        ModePlayer(ServerLevel world, GameProfile profile, CommonListenerCookie data) {
            super(world.getServer(), world, profile, data.clientInformation());
        }

        @Override
        public boolean isCreative() {
            return creative;
        }

        @Override
        public boolean isSpectator() {
            return spectator;
        }
    }

    private static ModePlayer modePlayer(GameTestHelper context, List<ServerPlayer> extra, boolean op) {
        ServerLevel world = context.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "builder-" + extra.size());
        CommonListenerCookie data = CommonListenerCookie.createInitial(profile, false);
        ModePlayer player = new ModePlayer(world, data.gameProfile(), data);
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        world.getServer().getPlayerList().placeNewPlayer(connection, player, data);
        if (op) world.getServer().getPlayerList().getOps().add(new ServerOpListEntry(profile, 2, false));
        extra.add(player);
        return player;
    }
}
