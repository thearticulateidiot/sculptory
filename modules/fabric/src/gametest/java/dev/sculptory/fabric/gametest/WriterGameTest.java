package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.handle;
import static dev.sculptory.fabric.gametest.EngineTestSupport.runtime;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.fabric.engine.impl.EngineRuntime;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.EditScope;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.fabric.world.FabricTile;
import dev.sculptory.fabric.world.FabricWorldReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.LecternBlock;
import net.minecraft.block.entity.BannerBlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.EntityType;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.event.listener.GameEventDispatcher;

/** The write path, reader and state space against a real server world. */
public final class WriterGameTest implements FabricGameTest {
    private static final String BATCH = "sculptory_writer";

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 100)
    public void waterloggedRoundTrip(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        FabricStateSpace states = runtime.states();
        String[] specs = {
            "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=true]",
            "minecraft:stone_slab[type=bottom,waterlogged=true]",
            "minecraft:water[level=0]",
            "minecraft:water[level=3]",
            "minecraft:lava[level=0]",
            "minecraft:kelp[age=25]",
            "minecraft:kelp_plant",
            "minecraft:seagrass",
            "minecraft:tall_seagrass[half=lower]",
        };
        BlockWriter writer = runtime.writer(world, BlockWriter.Options.DEFAULT);
        FabricWorldReader reader = runtime.reader(world);
        List<BlockPos> positions = new ArrayList<>();
        int[] handles = new int[specs.length];
        for (int i = 0; i < specs.length; i++) {
            BlockPos pos = context.getAbsolutePos(new BlockPos(1 + 2 * (i % 3), 2, 1 + 2 * (i / 3)));
            positions.add(pos);
            handles[i] = handle(states, specs[i]);
            writer.write(pos.getX(), pos.getY(), pos.getZ(), handles[i], null);
        }
        writer.clearTicksAtWrittenCells();
        for (int i = 0; i < specs.length; i++) {
            BlockPos pos = positions.get(i);
            check(reader.get(pos.getX(), pos.getY(), pos.getZ()) == handles[i], "not written: " + specs[i]);
            SectionBuffer section = new SectionBuffer();
            reader.copySection(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4, section);
            int index = SectionBuffer.index(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
            check(section.get(index) == handles[i], "copySection disagrees for " + specs[i]);
            check(states.format(handles[i]).equals(specs[i]), "format is not exact for " + specs[i]);
        }
        context.runAtTick(41, () -> {
            reader.invalidate();
            for (int i = 0; i < specs.length; i++) {
                BlockPos pos = positions.get(i);
                int now = reader.get(pos.getX(), pos.getY(), pos.getZ());
                check(now == handles[i], specs[i] + " changed to " + states.format(now) + " after 40 ticks");
                BlockState state = world.getBlockState(pos);
                check(!world.getBlockTickScheduler().isQueued(pos, state.getBlock()), "block tick pending: " + specs[i]);
                check(!world.getFluidTickScheduler().isQueued(pos, state.getFluidState().getFluid()),
                        "fluid tick pending: " + specs[i]);
                for (Direction direction : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                        Direction.EAST, Direction.WEST}) {
                    check(world.getBlockState(pos.offset(direction)).isAir(), specs[i] + " spread " + direction);
                }
            }
            context.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = BATCH)
    public void blockEntityNbtRoundTrip(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        FabricStateSpace states = runtime.states();
        FabricWorldReader reader = runtime.reader(world);

        BlockPos chest = context.getAbsolutePos(new BlockPos(1, 1, 1));
        BlockPos sign = context.getAbsolutePos(new BlockPos(3, 1, 1));
        BlockPos banner = context.getAbsolutePos(new BlockPos(5, 1, 1));
        BlockPos furnace = context.getAbsolutePos(new BlockPos(1, 1, 3));

        world.setBlockState(chest, Blocks.CHEST.getDefaultState());
        ChestBlockEntity chestEntity = (ChestBlockEntity) world.getBlockEntity(chest);
        chestEntity.setStack(0, new ItemStack(Items.DIAMOND, 5));
        chestEntity.setStack(13, new ItemStack(Items.OAK_LOG, 64));

        world.setBlockState(sign, Blocks.OAK_SIGN.getDefaultState());
        SignBlockEntity signEntity = (SignBlockEntity) world.getBlockEntity(sign);
        signEntity.setText(new SignText().withMessage(0, Text.literal("Builder")).withMessage(1, Text.literal("Suite")),
                true);

        world.setBlockState(banner, Blocks.RED_BANNER.getDefaultState());
        BannerBlockEntity bannerEntity = (BannerBlockEntity) world.getBlockEntity(banner);
        NbtCompound bannerNbt = new NbtCompound();
        NbtList patterns = new NbtList();
        NbtCompound layer = new NbtCompound();
        layer.putString("pattern", "minecraft:stripe_top");
        layer.putString("color", "white");
        patterns.add(layer);
        bannerNbt.put("patterns", patterns);
        bannerNbt.putString("CustomName", "\"Flag\"");
        bannerEntity.read(bannerNbt, world.getRegistryManager());
        check(!bannerEntity.getPatterns().layers().isEmpty(), "banner fixture has no patterns");

        world.setBlockState(furnace, Blocks.FURNACE.getDefaultState());
        FurnaceBlockEntity furnaceEntity = (FurnaceBlockEntity) world.getBlockEntity(furnace);
        furnaceEntity.setStack(0, new ItemStack(Items.IRON_ORE, 3));
        furnaceEntity.setStack(1, new ItemStack(Items.COAL, 2));

        BlockWriter writer = runtime.writer(world, new BlockWriter.Options(false, true));
        for (BlockPos source : List.of(chest, sign, banner, furnace)) {
            BlockEntityData captured = reader.tile(source.getX(), source.getY(), source.getZ());
            check(captured instanceof FabricTile, "no tile captured at " + source.toShortString());
            SectionBuffer section = new SectionBuffer();
            reader.copySection(source.getX() >> 4, source.getY() >> 4, source.getZ() >> 4, section);
            BlockEntityData inSection = section.tile(SectionBuffer.index(source.getX() & 15, source.getY() & 15,
                    source.getZ() & 15));
            check(captured.sameContent(inSection), "copySection tile differs at " + source.toShortString());

            BlockPos target = source.up(3);
            int h = states.handle(world.getBlockState(source));
            BlockEntityData applied = writer.write(target.getX(), target.getY(), target.getZ(), h, captured);
            check(applied != null, "tile not applied for " + captured.typeId());
            BlockEntityData copy = reader.tile(target.getX(), target.getY(), target.getZ());
            check(captured.sameContent(copy), "NBT differs for " + captured.typeId() + ":\n  source " + captured
                    + "\n  copy   " + copy);
            check(captured.sameContent(new NbtBytes(captured.typeId(), captured.nbtBytes())),
                    "byte encoding does not round-trip for " + captured.typeId());
        }
        check(writer.tileFailures() == 0 && writer.strippedNbt() == 0, "unexpected failures or stripping");
        BlockPos copied = chest.up(3);
        ChestBlockEntity copiedChest = (ChestBlockEntity) world.getBlockEntity(copied);
        check(copiedChest.getStack(0).isOf(Items.DIAMOND) && copiedChest.getStack(0).getCount() == 5, "chest items");

        // Same state again: no tile resets to the default block entity, a tile replaces the content.
        int chestHandle = states.handle(world.getBlockState(copied));
        BlockEntityData chestContent = reader.tile(copied.getX(), copied.getY(), copied.getZ());
        writer.write(copied.getX(), copied.getY(), copied.getZ(), chestHandle, null);
        check(((ChestBlockEntity) world.getBlockEntity(copied)).isEmpty(), "same-state write kept the old items");
        writer.write(copied.getX(), copied.getY(), copied.getZ(), chestHandle, chestContent);
        check(chestContent.sameContent(reader.tile(copied.getX(), copied.getY(), copied.getZ())), "same-state tile");
        check(context.getEntities(EntityType.ITEM).isEmpty(), "rewriting a chest dropped items");

        // Sign NBT needs operator rights. Server-captured content is restored as-is for a non-op (L2: undo, move);
        // the same content arriving as foreign bytes is stripped and counted.
        int signHandle = states.handle(world.getBlockState(sign));
        check((states.flags(signHandle) & StateFlags.OPERATOR_NBT) != 0, "oak_sign should be OPERATOR_NBT");
        BlockEntityData signTile = reader.tile(sign.getX(), sign.getY(), sign.getZ());
        check(FabricTile.isServerCaptured(signTile), "reader tiles should be server-captured");
        BlockWriter nonOp = runtime.writer(world, BlockWriter.Options.DEFAULT);
        BlockPos trusted = context.getAbsolutePos(new BlockPos(3, 4, 5));
        check(nonOp.write(trusted.getX(), trusted.getY(), trusted.getZ(), signHandle, signTile) != null
                && signText(world, trusted).equals("Builder"), "captured sign NBT was stripped for a non-op");
        BlockPos stripped = context.getAbsolutePos(new BlockPos(3, 4, 3));
        BlockEntityData foreign = new NbtBytes(signTile.typeId(), signTile.nbtBytes());
        BlockEntityData applied = nonOp.write(stripped.getX(), stripped.getY(), stripped.getZ(), signHandle, foreign);
        check(applied == null && nonOp.strippedNbt() == 1, "foreign operator NBT was not stripped");
        check(signText(world, stripped).isEmpty(), "stripped sign has text");
        BlockWriter distrusting = runtime.writer(world, new BlockWriter.Options(false, false, false));
        BlockPos distrusted = context.getAbsolutePos(new BlockPos(1, 4, 5));
        distrusting.write(distrusted.getX(), distrusted.getY(), distrusted.getZ(), signHandle, signTile);
        check(distrusting.strippedNbt() == 1 && signText(world, distrusted).isEmpty(),
                "trustCapturedTiles=false did not strip captured operator NBT");

        // A non-op's recorded overwrite of a sign, then its undo from the record, keeps the text.
        EngineTestSupport.MapSink sink = new EngineTestSupport.MapSink();
        check(nonOp.write(sign.getX(), sign.getY(), sign.getZ(), states.air(), null, sink), "sign not overwritten");
        EngineTestSupport.Rec rec = sink.get(sign);
        nonOp.write(sign.getX(), sign.getY(), sign.getZ(), rec.before(), rec.beforeTile());
        check(signText(world, sign).equals("Builder"), "non-op undo stripped the sign text");

        // A tile that does not fit the state is refused and the default block entity is kept.
        BlockPos mismatch = context.getAbsolutePos(new BlockPos(5, 4, 3));
        BlockEntityData chestTile = reader.tile(chest.getX(), chest.getY(), chest.getZ());
        BlockEntityData none = writer.write(mismatch.getX(), mismatch.getY(), mismatch.getZ(),
                handle(states, "minecraft:furnace[facing=north,lit=false]"), chestTile);
        check(none == null && writer.tileFailures() == 1, "mismatched tile was not refused");
        check(world.getBlockEntity(mismatch) instanceof FurnaceBlockEntity, "furnace lost its default block entity");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 100)
    public void physicsOffNoUpdates(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        FabricStateSpace states = runtime.states();
        BlockWriter writer = runtime.writer(world, BlockWriter.Options.DEFAULT);
        BlockPos sand = context.getAbsolutePos(new BlockPos(1, 4, 1));
        BlockPos torch = context.getAbsolutePos(new BlockPos(3, 4, 3));
        BlockPos water = context.getAbsolutePos(new BlockPos(5, 4, 5));
        // A torch placed normally on a block; the physics-off write then removes its support.
        world.setBlockState(torch.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(torch, Blocks.TORCH.getDefaultState());
        writer.write(torch.getX(), torch.getY() - 1, torch.getZ(), states.air(), null);
        writer.write(sand.getX(), sand.getY(), sand.getZ(), handle(states, "minecraft:sand"), null);
        writer.write(water.getX(), water.getY(), water.getZ(), handle(states, "minecraft:water[level=0]"), null);
        // Before the per-section tick clearing: the suppressed onBlockAdded scheduled nothing.
        check(!world.getBlockTickScheduler().isQueued(sand, Blocks.SAND), "sand tick scheduled by a physics-off write");
        check(!world.getFluidTickScheduler().isQueued(water, Fluids.WATER), "fluid tick scheduled by a physics-off write");
        writer.clearTicksAtWrittenCells();
        context.runAtTick(40, () -> {
            check(world.getBlockState(sand).isOf(Blocks.SAND), "floating sand fell");
            check(world.getBlockState(torch).isOf(Blocks.TORCH), "torch broke when its support was removed");
            check(world.getFluidState(water).isStill() && world.getFluidState(water).isOf(Fluids.WATER), "water source changed");
            for (Direction direction : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                    Direction.EAST, Direction.WEST}) {
                check(world.getBlockState(water.offset(direction)).isAir(), "water flowed " + direction);
            }
            check(context.getEntities(EntityType.FALLING_BLOCK).isEmpty(), "a falling block entity exists");
            check(context.getEntities(EntityType.ITEM).isEmpty(), "something dropped");
            context.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 100)
    public void physicsOnAllowsUpdates(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        FabricStateSpace states = runtime.states();
        // Contain everything: a glass floor under the sand, and a glass cup under the water.
        BlockPos sand = context.getAbsolutePos(new BlockPos(1, 5, 1));
        world.setBlockState(context.getAbsolutePos(new BlockPos(1, 0, 1)), Blocks.GLASS.getDefaultState());
        BlockPos water = context.getAbsolutePos(new BlockPos(4, 2, 4));
        BlockPos cup = water.down();
        world.setBlockState(cup.down(), Blocks.GLASS.getDefaultState());
        for (Direction side : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            world.setBlockState(cup.offset(side), Blocks.GLASS.getDefaultState());
        }
        BlockPos torch = context.getAbsolutePos(new BlockPos(6, 4, 1));
        world.setBlockState(torch.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(torch, Blocks.TORCH.getDefaultState());
        BlockWriter writer = runtime.writer(world, new BlockWriter.Options(true, false));
        writer.write(torch.getX(), torch.getY() - 1, torch.getZ(), states.air(), null);
        writer.write(sand.getX(), sand.getY(), sand.getZ(), handle(states, "minecraft:sand"), null);
        writer.write(water.getX(), water.getY(), water.getZ(), handle(states, "minecraft:water[level=0]"), null);
        context.runAtTick(40, () -> {
            check(world.getBlockState(torch).isAir(), "torch survived losing its support with physics on");
            context.killAllEntities();
            check(world.getBlockState(sand).isAir(), "sand did not fall with physics on");
            check(world.getBlockState(context.getAbsolutePos(new BlockPos(1, 1, 1))).isOf(Blocks.SAND), "sand did not land");
            check(world.getFluidState(cup).isOf(Fluids.WATER) || world.getFluidState(cup).isOf(Fluids.FLOWING_WATER),
                    "water did not flow with physics on");
            BlockWriter cleanup = runtime.writer(world, BlockWriter.Options.DEFAULT);
            cleanup.write(water.getX(), water.getY(), water.getZ(), states.air(), null);
            cleanup.write(cup.getX(), cup.getY(), cup.getZ(), states.air(), null);
            context.complete();
        });
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 100)
    public void cutDoesNotDropContents(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        BlockPos off = context.getAbsolutePos(new BlockPos(2, 1, 2));
        BlockPos on = context.getAbsolutePos(new BlockPos(5, 1, 5));
        BlockPos lectern = context.getAbsolutePos(new BlockPos(2, 1, 5));
        for (BlockPos pos : List.of(off, on)) {
            world.setBlockState(pos, Blocks.CHEST.getDefaultState());
            ChestBlockEntity chest = (ChestBlockEntity) world.getBlockEntity(pos);
            for (int slot = 0; slot < chest.size(); slot++) chest.setStack(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        world.setBlockState(lectern, Blocks.LECTERN.getDefaultState().with(LecternBlock.HAS_BOOK, true));
        ((LecternBlockEntity) world.getBlockEntity(lectern)).setBook(new ItemStack(Items.WRITABLE_BOOK));

        int air = runtime.states().air();
        runtime.writer(world, BlockWriter.Options.DEFAULT).write(off.getX(), off.getY(), off.getZ(), air, null);
        runtime.writer(world, BlockWriter.Options.DEFAULT).write(lectern.getX(), lectern.getY(), lectern.getZ(), air, null);
        runtime.writer(world, new BlockWriter.Options(true, false)).write(on.getX(), on.getY(), on.getZ(), air, null);
        context.runAtTick(5, () -> {
            for (BlockPos pos : List.of(off, on, lectern)) check(world.getBlockState(pos).isAir(), "not removed");
            check(context.getEntities(EntityType.ITEM).isEmpty(),
                    context.getEntities(EntityType.ITEM).size() + " item entities dropped");
            context.complete();
        });
    }

    /**
     * The mixin itself: inside an {@link EditScope} vanilla's onBlockAdded/onStateReplaced do nothing on this
     * thread; outside it they run as usual (the control).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = BATCH)
    public void physicsSuppressionMixin(TestContext context) {
        ServerWorld world = context.getWorld();
        BlockState air = Blocks.AIR.getDefaultState();
        BlockPos sand = context.getAbsolutePos(new BlockPos(1, 3, 1));
        BlockState sandState = Blocks.SAND.getDefaultState();
        world.setBlockState(sand, sandState, Block.FORCE_STATE, 0);
        world.getBlockTickScheduler().clearNextTicks(new BlockBox(sand));
        try (EditScope scope = EditScope.suppressPhysics()) {
            sandState.onBlockAdded(world, sand, air, false);
        }
        check(!world.getBlockTickScheduler().isQueued(sand, Blocks.SAND), "onBlockAdded ran inside an EditScope");
        sandState.onBlockAdded(world, sand, air, false);
        check(world.getBlockTickScheduler().isQueued(sand, Blocks.SAND), "control: onBlockAdded did not schedule");
        world.getBlockTickScheduler().clearNextTicks(new BlockBox(sand));

        BlockPos chest = context.getAbsolutePos(new BlockPos(4, 1, 4));
        world.setBlockState(chest, Blocks.CHEST.getDefaultState());
        ((ChestBlockEntity) world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.EMERALD, 3));
        try (EditScope scope = EditScope.suppressPhysics()) {
            world.getBlockState(chest).onStateReplaced(world, chest, air, false);
        }
        check(context.getEntities(EntityType.ITEM).isEmpty(), "onStateReplaced dropped items inside an EditScope");
        check(world.getBlockEntity(chest) instanceof ChestBlockEntity c && !c.isEmpty(), "chest block entity lost");

        BlockWriter cleanup = runtime(context).writer(world, BlockWriter.Options.DEFAULT);
        cleanup.write(chest.getX(), chest.getY(), chest.getZ(), runtime(context).states().air(), null);
        cleanup.write(sand.getX(), sand.getY(), sand.getZ(), runtime(context).states().air(), null);
        check(context.getEntities(EntityType.ITEM).isEmpty(), "cleanup dropped items");
        context.complete();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = BATCH)
    public void stateSpaceUsesBoundTags(TestContext context) {
        FabricStateSpace states = runtime(context).states();
        // Built at SERVER_STARTING: tag-only vegetation (glow lichen is only in #replaceable_by_trees) is flagged.
        check(StateFlags.has(states.flags(handle(states, "minecraft:glow_lichen")), StateFlags.VEGETATION),
                "tags were not bound when the state space was built");
        check(states.inTag(handle(states, "minecraft:oak_leaves"), new NamespacedId("minecraft:leaves")), "#leaves");
        check(states.inTag(handle(states, "minecraft:oak_log"), new NamespacedId("minecraft:logs")), "#logs");
        check(!states.inTag(handle(states, "minecraft:stone"), new NamespacedId("minecraft:leaves")), "stone in #leaves");
        check(!states.inTag(handle(states, "minecraft:stone"), new NamespacedId("minecraft:no_such_tag")), "unknown tag");
        context.complete();
    }

    /**
     * L5: a physics-off write over a lectern with a book must not clear it (clearing updates comparators). The
     * comparator reading the lectern keeps its output; with physics on (or with clearing) it would switch off.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = BATCH, tickLimit = 100)
    public void physicsOffLeavesComparatorsAlone(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        BlockPos lectern = context.getAbsolutePos(new BlockPos(2, 1, 2));
        BlockPos comparator = lectern.east();
        world.setBlockState(comparator.down(), Blocks.STONE.getDefaultState());
        world.setBlockState(lectern, Blocks.LECTERN.getDefaultState().with(LecternBlock.HAS_BOOK, true));
        ((LecternBlockEntity) world.getBlockEntity(lectern)).setBook(new ItemStack(Items.WRITABLE_BOOK));
        world.setBlockState(comparator, Blocks.COMPARATOR.getDefaultState().with(ComparatorBlock.FACING, Direction.WEST));
        world.updateComparators(lectern, Blocks.LECTERN);
        context.runAtTick(8, () -> {
            check(world.getBlockState(comparator).get(ComparatorBlock.POWERED), "setup: comparator does not read the lectern");
            BlockWriter writer = runtime.writer(world, BlockWriter.Options.DEFAULT);
            writer.write(lectern.getX(), lectern.getY(), lectern.getZ(), runtime.states().air(), null);
            writer.clearTicksAtWrittenCells();
            check(!world.getBlockTickScheduler().isQueued(comparator, Blocks.COMPARATOR), "comparator was updated");
            context.waitAndRun(10, () -> {
                check(world.getBlockState(comparator).get(ComparatorBlock.POWERED), "comparator reacted to the write");
                check(context.getEntities(EntityType.ITEM).isEmpty(), "the book dropped");
                context.complete();
            });
        });
    }

    /** M4: loading a tile replaces the default block entity's game-event listener instead of adding a second. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = BATCH)
    public void tileLoadReplacesGameEventListener(TestContext context) {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        FabricWorldReader reader = runtime.reader(world);
        BlockWriter writer = runtime.writer(world, BlockWriter.Options.DEFAULT);
        for (Block block : List.of(Blocks.SCULK_CATALYST, Blocks.SCULK_SENSOR)) {
            int row = block == Blocks.SCULK_CATALYST ? 1 : 5;
            BlockPos source = context.getAbsolutePos(new BlockPos(1, 1, row));
            BlockPos target = context.getAbsolutePos(new BlockPos(6, 1, row));
            world.setBlockState(source, block.getDefaultState());
            BlockEntityData tile = reader.tile(source.getX(), source.getY(), source.getZ());
            int h = runtime.states().handle(block.getDefaultState());
            check(writer.write(target.getX(), target.getY(), target.getZ(), h, tile) != null, "tile not applied");
            check(listenersAt(world, target) == 1, listenersAt(world, target) + " game-event listeners at the "
                    + block + " after a tile load (a stale default block entity is still listening)");
            // Writing the same state and tile again (a same-state rewrite) must not stack listeners either.
            writer.write(target.getX(), target.getY(), target.getZ(), h, tile);
            check(listenersAt(world, target) == 1, "listeners stacked after a same-state rewrite");
        }
        context.complete();
    }

    /**
     * M1: a write that throws is still recorded, so the cell can be restored. Here the physics scope is held by
     * another thread, which makes the write fail.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_writer_failure")
    public void failedWriteIsStillRecorded(TestContext context) throws InterruptedException {
        EngineRuntime runtime = runtime(context);
        ServerWorld world = context.getWorld();
        FabricStateSpace states = runtime.states();
        BlockPos chest = context.getAbsolutePos(new BlockPos(3, 1, 3));
        world.setBlockState(chest, Blocks.CHEST.getDefaultState());
        ((ChestBlockEntity) world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 7));
        int chestHandle = states.handle(world.getBlockState(chest));

        EngineTestSupport.MapSink sink = new EngineTestSupport.MapSink();
        BlockWriter writer = runtime.writer(world, BlockWriter.Options.DEFAULT);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            try (EditScope scope = EditScope.suppressPhysics()) {
                held.countDown();
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sculptory-test-scope-holder");
        holder.start();
        try {
            check(held.await(10, TimeUnit.SECONDS), "holder thread did not take the scope");
            try {
                writer.write(chest.getX(), chest.getY(), chest.getZ(), states.air(), null, sink);
                throw new GameTestException("the write succeeded while another thread held the EditScope");
            } catch (IllegalStateException expected) {
                // The write failed; it must still have been recorded.
            }
        } finally {
            release.countDown();
            holder.join(10_000);
        }
        EngineTestSupport.Rec rec = sink.get(chest);
        check(rec != null && sink.size() == 1, "the failed write was not recorded");
        check(rec.before() == chestHandle && rec.beforeTile() != null, "record lost the chest");
        // Restoring the record brings back the contents, whatever the failed write left behind.
        runtime.writer(world, BlockWriter.Options.DEFAULT)
                .write(chest.getX(), chest.getY(), chest.getZ(), rec.before(), rec.beforeTile());
        check(world.getBlockEntity(chest) instanceof ChestBlockEntity c && c.getStack(0).getCount() == 7,
                "chest contents lost");
        context.complete();
    }

    private static String signText(ServerWorld world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof SignBlockEntity s ? s.getFrontText().getMessage(0, false).getString()
                : "<no sign>";
    }

    private static int listenersAt(ServerWorld world, BlockPos pos) {
        Vec3d center = Vec3d.ofCenter(pos);
        GameEventDispatcher dispatcher = world.getWorldChunk(pos)
                .getGameEventDispatcher(ChunkSectionPos.getSectionCoord(pos.getY()));
        int[] count = {0};
        dispatcher.dispatch(GameEvent.BLOCK_CHANGE, center, GameEvent.Emitter.of(Blocks.STONE.getDefaultState()),
                (listener, at) -> {
                    if (at.squaredDistanceTo(center) < 0.01) count[0]++;
                });
        return count[0];
    }
}
