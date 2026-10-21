package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.handle;
import static dev.sculptory.fabric.gametest.EngineTestSupport.runtime;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.mc.engine.impl.EngineRuntime;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.EditScope;
import dev.sculptory.mc.world.McStateSpace;
import dev.sculptory.mc.world.McTile;
import dev.sculptory.mc.world.McWorldReader;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListenerRegistry;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;

/** The write path, reader and state space against a real server world. */
public final class WriterGameTest implements FabricGameTest {
    private static final String BATCH = "sculptory_writer";

    @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
    public void waterloggedRoundTrip(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        McStateSpace states = runtime.states();
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
        BlockWriter writer = runtime.writer(world, WriteOptions.DEFAULT);
        McWorldReader reader = runtime.reader(world);
        List<BlockPos> positions = new ArrayList<>();
        int[] handles = new int[specs.length];
        for (int i = 0; i < specs.length; i++) {
            BlockPos pos = context.absolutePos(new BlockPos(1 + 2 * (i % 3), 2, 1 + 2 * (i / 3)));
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
        context.runAtTickTime(41, () -> {
            reader.invalidate();
            for (int i = 0; i < specs.length; i++) {
                BlockPos pos = positions.get(i);
                int now = reader.get(pos.getX(), pos.getY(), pos.getZ());
                check(now == handles[i], specs[i] + " changed to " + states.format(now) + " after 40 ticks");
                BlockState state = world.getBlockState(pos);
                check(!world.getBlockTicks().hasScheduledTick(pos, state.getBlock()), "block tick pending: " + specs[i]);
                check(!world.getFluidTicks().hasScheduledTick(pos, state.getFluidState().getType()),
                        "fluid tick pending: " + specs[i]);
                for (Direction direction : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                        Direction.EAST, Direction.WEST}) {
                    check(world.getBlockState(pos.relative(direction)).isAir(), specs[i] + " spread " + direction);
                }
            }
            context.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
    public void blockEntityNbtRoundTrip(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        McStateSpace states = runtime.states();
        McWorldReader reader = runtime.reader(world);

        BlockPos chest = context.absolutePos(new BlockPos(1, 1, 1));
        BlockPos sign = context.absolutePos(new BlockPos(3, 1, 1));
        BlockPos banner = context.absolutePos(new BlockPos(5, 1, 1));
        BlockPos furnace = context.absolutePos(new BlockPos(1, 1, 3));

        world.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chestEntity = (ChestBlockEntity) world.getBlockEntity(chest);
        chestEntity.setItem(0, new ItemStack(Items.DIAMOND, 5));
        chestEntity.setItem(13, new ItemStack(Items.OAK_LOG, 64));

        world.setBlockAndUpdate(sign, Blocks.OAK_SIGN.defaultBlockState());
        SignBlockEntity signEntity = (SignBlockEntity) world.getBlockEntity(sign);
        signEntity.setText(new SignText().setMessage(0, Component.literal("Builder")).setMessage(1, Component.literal("Suite")),
                true);

        world.setBlockAndUpdate(banner, Blocks.RED_BANNER.defaultBlockState());
        BannerBlockEntity bannerEntity = (BannerBlockEntity) world.getBlockEntity(banner);
        CompoundTag bannerNbt = new CompoundTag();
        ListTag patterns = new ListTag();
        CompoundTag layer = new CompoundTag();
        layer.putString("pattern", "minecraft:stripe_top");
        layer.putString("color", "white");
        patterns.add(layer);
        bannerNbt.put("patterns", patterns);
        bannerNbt.putString("CustomName", "\"Flag\"");
        bannerEntity.loadWithComponents(bannerNbt, world.registryAccess());
        check(!bannerEntity.getPatterns().layers().isEmpty(), "banner fixture has no patterns");

        world.setBlockAndUpdate(furnace, Blocks.FURNACE.defaultBlockState());
        FurnaceBlockEntity furnaceEntity = (FurnaceBlockEntity) world.getBlockEntity(furnace);
        furnaceEntity.setItem(0, new ItemStack(Items.IRON_ORE, 3));
        furnaceEntity.setItem(1, new ItemStack(Items.COAL, 2));

        BlockWriter writer = runtime.writer(world, new WriteOptions(false, true));
        for (BlockPos source : List.of(chest, sign, banner, furnace)) {
            BlockEntityData captured = reader.tile(source.getX(), source.getY(), source.getZ());
            check(captured instanceof McTile, "no tile captured at " + source.toShortString());
            SectionBuffer section = new SectionBuffer();
            reader.copySection(source.getX() >> 4, source.getY() >> 4, source.getZ() >> 4, section);
            BlockEntityData inSection = section.tile(SectionBuffer.index(source.getX() & 15, source.getY() & 15,
                    source.getZ() & 15));
            check(captured.sameContent(inSection), "copySection tile differs at " + source.toShortString());

            BlockPos target = source.above(3);
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
        BlockPos copied = chest.above(3);
        ChestBlockEntity copiedChest = (ChestBlockEntity) world.getBlockEntity(copied);
        check(copiedChest.getItem(0).is(Items.DIAMOND) && copiedChest.getItem(0).getCount() == 5, "chest items");

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
        check(McTile.isServerCaptured(signTile), "reader tiles should be server-captured");
        BlockWriter nonOp = runtime.writer(world, WriteOptions.DEFAULT);
        BlockPos trusted = context.absolutePos(new BlockPos(3, 4, 5));
        check(nonOp.write(trusted.getX(), trusted.getY(), trusted.getZ(), signHandle, signTile) != null
                && signText(world, trusted).equals("Builder"), "captured sign NBT was stripped for a non-op");
        BlockPos stripped = context.absolutePos(new BlockPos(3, 4, 3));
        BlockEntityData foreign = new NbtBytes(signTile.typeId(), signTile.nbtBytes());
        BlockEntityData applied = nonOp.write(stripped.getX(), stripped.getY(), stripped.getZ(), signHandle, foreign);
        check(applied == null && nonOp.strippedNbt() == 1, "foreign operator NBT was not stripped");
        check(signText(world, stripped).isEmpty(), "stripped sign has text");
        BlockWriter distrusting = runtime.writer(world, new WriteOptions(false, false, false));
        BlockPos distrusted = context.absolutePos(new BlockPos(1, 4, 5));
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
        BlockPos mismatch = context.absolutePos(new BlockPos(5, 4, 3));
        BlockEntityData chestTile = reader.tile(chest.getX(), chest.getY(), chest.getZ());
        BlockEntityData none = writer.write(mismatch.getX(), mismatch.getY(), mismatch.getZ(),
                handle(states, "minecraft:furnace[facing=north,lit=false]"), chestTile);
        check(none == null && writer.tileFailures() == 1, "mismatched tile was not refused");
        check(world.getBlockEntity(mismatch) instanceof FurnaceBlockEntity, "furnace lost its default block entity");
        context.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
    public void physicsOffNoUpdates(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        McStateSpace states = runtime.states();
        BlockWriter writer = runtime.writer(world, WriteOptions.DEFAULT);
        BlockPos sand = context.absolutePos(new BlockPos(1, 4, 1));
        BlockPos torch = context.absolutePos(new BlockPos(3, 4, 3));
        BlockPos water = context.absolutePos(new BlockPos(5, 4, 5));
        // A torch placed normally on a block; the physics-off write then removes its support.
        world.setBlockAndUpdate(torch.below(), Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(torch, Blocks.TORCH.defaultBlockState());
        writer.write(torch.getX(), torch.getY() - 1, torch.getZ(), states.air(), null);
        writer.write(sand.getX(), sand.getY(), sand.getZ(), handle(states, "minecraft:sand"), null);
        writer.write(water.getX(), water.getY(), water.getZ(), handle(states, "minecraft:water[level=0]"), null);
        // Before the per-section tick clearing: the suppressed onBlockAdded scheduled nothing.
        check(!world.getBlockTicks().hasScheduledTick(sand, Blocks.SAND), "sand tick scheduled by a physics-off write");
        check(!world.getFluidTicks().hasScheduledTick(water, Fluids.WATER), "fluid tick scheduled by a physics-off write");
        writer.clearTicksAtWrittenCells();
        context.runAtTickTime(40, () -> {
            check(world.getBlockState(sand).is(Blocks.SAND), "floating sand fell");
            check(world.getBlockState(torch).is(Blocks.TORCH), "torch broke when its support was removed");
            check(world.getFluidState(water).isSource() && world.getFluidState(water).is(Fluids.WATER), "water source changed");
            for (Direction direction : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH,
                    Direction.EAST, Direction.WEST}) {
                check(world.getBlockState(water.relative(direction)).isAir(), "water flowed " + direction);
            }
            check(context.getEntities(EntityType.FALLING_BLOCK).isEmpty(), "a falling block entity exists");
            check(context.getEntities(EntityType.ITEM).isEmpty(), "something dropped");
            context.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
    public void physicsOnAllowsUpdates(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        McStateSpace states = runtime.states();
        // Contain everything: a glass floor under the sand, and a glass cup under the water.
        BlockPos sand = context.absolutePos(new BlockPos(1, 5, 1));
        world.setBlockAndUpdate(context.absolutePos(new BlockPos(1, 0, 1)), Blocks.GLASS.defaultBlockState());
        BlockPos water = context.absolutePos(new BlockPos(4, 2, 4));
        BlockPos cup = water.below();
        world.setBlockAndUpdate(cup.below(), Blocks.GLASS.defaultBlockState());
        for (Direction side : new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            world.setBlockAndUpdate(cup.relative(side), Blocks.GLASS.defaultBlockState());
        }
        BlockPos torch = context.absolutePos(new BlockPos(6, 4, 1));
        world.setBlockAndUpdate(torch.below(), Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(torch, Blocks.TORCH.defaultBlockState());
        BlockWriter writer = runtime.writer(world, new WriteOptions(true, false));
        writer.write(torch.getX(), torch.getY() - 1, torch.getZ(), states.air(), null);
        writer.write(sand.getX(), sand.getY(), sand.getZ(), handle(states, "minecraft:sand"), null);
        writer.write(water.getX(), water.getY(), water.getZ(), handle(states, "minecraft:water[level=0]"), null);
        context.runAtTickTime(40, () -> {
            check(world.getBlockState(torch).isAir(), "torch survived losing its support with physics on");
            context.killAllEntities();
            check(world.getBlockState(sand).isAir(), "sand did not fall with physics on");
            check(world.getBlockState(context.absolutePos(new BlockPos(1, 1, 1))).is(Blocks.SAND), "sand did not land");
            check(world.getFluidState(cup).is(Fluids.WATER) || world.getFluidState(cup).is(Fluids.FLOWING_WATER),
                    "water did not flow with physics on");
            BlockWriter cleanup = runtime.writer(world, WriteOptions.DEFAULT);
            cleanup.write(water.getX(), water.getY(), water.getZ(), states.air(), null);
            cleanup.write(cup.getX(), cup.getY(), cup.getZ(), states.air(), null);
            context.succeed();
        });
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
    public void cutDoesNotDropContents(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        BlockPos off = context.absolutePos(new BlockPos(2, 1, 2));
        BlockPos on = context.absolutePos(new BlockPos(5, 1, 5));
        BlockPos lectern = context.absolutePos(new BlockPos(2, 1, 5));
        for (BlockPos pos : List.of(off, on)) {
            world.setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
            ChestBlockEntity chest = (ChestBlockEntity) world.getBlockEntity(pos);
            for (int slot = 0; slot < chest.getContainerSize(); slot++) chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        world.setBlockAndUpdate(lectern, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true));
        ((LecternBlockEntity) world.getBlockEntity(lectern)).setBook(new ItemStack(Items.WRITABLE_BOOK));

        int air = runtime.states().air();
        runtime.writer(world, WriteOptions.DEFAULT).write(off.getX(), off.getY(), off.getZ(), air, null);
        runtime.writer(world, WriteOptions.DEFAULT).write(lectern.getX(), lectern.getY(), lectern.getZ(), air, null);
        runtime.writer(world, new WriteOptions(true, false)).write(on.getX(), on.getY(), on.getZ(), air, null);
        context.runAtTickTime(5, () -> {
            for (BlockPos pos : List.of(off, on, lectern)) check(world.getBlockState(pos).isAir(), "not removed");
            check(context.getEntities(EntityType.ITEM).isEmpty(),
                    context.getEntities(EntityType.ITEM).size() + " item entities dropped");
            context.succeed();
        });
    }

    /**
     * The mixin itself: inside an {@link EditScope} vanilla's onBlockAdded/onStateReplaced do nothing on this
     * thread; outside it they run as usual (the control).
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
    public void physicsSuppressionMixin(GameTestHelper context) {
        ServerLevel world = context.getLevel();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockPos sand = context.absolutePos(new BlockPos(1, 3, 1));
        BlockState sandState = Blocks.SAND.defaultBlockState();
        world.setBlock(sand, sandState, Block.UPDATE_KNOWN_SHAPE, 0);
        world.getBlockTicks().clearArea(new BoundingBox(sand));
        try (EditScope scope = EditScope.suppressPhysics()) {
            sandState.onPlace(world, sand, air, false);
        }
        check(!world.getBlockTicks().hasScheduledTick(sand, Blocks.SAND), "onBlockAdded ran inside an EditScope");
        sandState.onPlace(world, sand, air, false);
        check(world.getBlockTicks().hasScheduledTick(sand, Blocks.SAND), "control: onBlockAdded did not schedule");
        world.getBlockTicks().clearArea(new BoundingBox(sand));

        BlockPos chest = context.absolutePos(new BlockPos(4, 1, 4));
        world.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) world.getBlockEntity(chest)).setItem(0, new ItemStack(Items.EMERALD, 3));
        try (EditScope scope = EditScope.suppressPhysics()) {
            world.getBlockState(chest).onRemove(world, chest, air, false);
        }
        check(context.getEntities(EntityType.ITEM).isEmpty(), "onStateReplaced dropped items inside an EditScope");
        check(world.getBlockEntity(chest) instanceof ChestBlockEntity c && !c.isEmpty(), "chest block entity lost");

        BlockWriter cleanup = runtime(context).writer(world, WriteOptions.DEFAULT);
        cleanup.write(chest.getX(), chest.getY(), chest.getZ(), runtime(context).states().air(), null);
        cleanup.write(sand.getX(), sand.getY(), sand.getZ(), runtime(context).states().air(), null);
        check(context.getEntities(EntityType.ITEM).isEmpty(), "cleanup dropped items");
        context.succeed();
    }

    @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
    public void stateSpaceUsesBoundTags(GameTestHelper context) {
        McStateSpace states = runtime(context).states();
        // Built at SERVER_STARTING: tag-only vegetation (glow lichen is only in #replaceable_by_trees) is flagged.
        check(StateFlags.has(states.flags(handle(states, "minecraft:glow_lichen")), StateFlags.VEGETATION),
                "tags were not bound when the state space was built");
        check(states.inTag(handle(states, "minecraft:oak_leaves"), new NamespacedId("minecraft:leaves")), "#leaves");
        check(states.inTag(handle(states, "minecraft:oak_log"), new NamespacedId("minecraft:logs")), "#logs");
        check(!states.inTag(handle(states, "minecraft:stone"), new NamespacedId("minecraft:leaves")), "stone in #leaves");
        check(!states.inTag(handle(states, "minecraft:stone"), new NamespacedId("minecraft:no_such_tag")), "unknown tag");
        context.succeed();
    }

    /**
     * L5: a physics-off write over a lectern with a book must not clear it (clearing updates comparators). The
     * comparator reading the lectern keeps its output; with physics on (or with clearing) it would switch off.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = BATCH, timeoutTicks = 100)
    public void physicsOffLeavesComparatorsAlone(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        BlockPos lectern = context.absolutePos(new BlockPos(2, 1, 2));
        BlockPos comparator = lectern.east();
        world.setBlockAndUpdate(comparator.below(), Blocks.STONE.defaultBlockState());
        world.setBlockAndUpdate(lectern, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.HAS_BOOK, true));
        ((LecternBlockEntity) world.getBlockEntity(lectern)).setBook(new ItemStack(Items.WRITABLE_BOOK));
        world.setBlockAndUpdate(comparator, Blocks.COMPARATOR.defaultBlockState().setValue(ComparatorBlock.FACING, Direction.WEST));
        world.updateNeighbourForOutputSignal(lectern, Blocks.LECTERN);
        context.runAtTickTime(8, () -> {
            check(world.getBlockState(comparator).getValue(ComparatorBlock.POWERED), "setup: comparator does not read the lectern");
            BlockWriter writer = runtime.writer(world, WriteOptions.DEFAULT);
            writer.write(lectern.getX(), lectern.getY(), lectern.getZ(), runtime.states().air(), null);
            writer.clearTicksAtWrittenCells();
            check(!world.getBlockTicks().hasScheduledTick(comparator, Blocks.COMPARATOR), "comparator was updated");
            context.runAfterDelay(10, () -> {
                check(world.getBlockState(comparator).getValue(ComparatorBlock.POWERED), "comparator reacted to the write");
                check(context.getEntities(EntityType.ITEM).isEmpty(), "the book dropped");
                context.succeed();
            });
        });
    }

    /** M4: loading a tile replaces the default block entity's game-event listener instead of adding a second. */
    @GameTest(template = EMPTY_STRUCTURE, batch = BATCH)
    public void tileLoadReplacesGameEventListener(GameTestHelper context) {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        McWorldReader reader = runtime.reader(world);
        BlockWriter writer = runtime.writer(world, WriteOptions.DEFAULT);
        for (Block block : List.of(Blocks.SCULK_CATALYST, Blocks.SCULK_SENSOR)) {
            int row = block == Blocks.SCULK_CATALYST ? 1 : 5;
            BlockPos source = context.absolutePos(new BlockPos(1, 1, row));
            BlockPos target = context.absolutePos(new BlockPos(6, 1, row));
            world.setBlockAndUpdate(source, block.defaultBlockState());
            BlockEntityData tile = reader.tile(source.getX(), source.getY(), source.getZ());
            int h = runtime.states().handle(block.defaultBlockState());
            check(writer.write(target.getX(), target.getY(), target.getZ(), h, tile) != null, "tile not applied");
            check(listenersAt(world, target) == 1, listenersAt(world, target) + " game-event listeners at the "
                    + block + " after a tile load (a stale default block entity is still listening)");
            // Writing the same state and tile again (a same-state rewrite) must not stack listeners either.
            writer.write(target.getX(), target.getY(), target.getZ(), h, tile);
            check(listenersAt(world, target) == 1, "listeners stacked after a same-state rewrite");
        }
        context.succeed();
    }

    /**
     * M1: a write that throws is still recorded, so the cell can be restored. Here the physics scope is held by
     * another thread, which makes the write fail.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_writer_failure")
    public void failedWriteIsStillRecorded(GameTestHelper context) throws InterruptedException {
        EngineRuntime runtime = runtime(context);
        ServerLevel world = context.getLevel();
        McStateSpace states = runtime.states();
        BlockPos chest = context.absolutePos(new BlockPos(3, 1, 3));
        world.setBlockAndUpdate(chest, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity) world.getBlockEntity(chest)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        int chestHandle = states.handle(world.getBlockState(chest));

        EngineTestSupport.MapSink sink = new EngineTestSupport.MapSink();
        BlockWriter writer = runtime.writer(world, WriteOptions.DEFAULT);
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
                throw new GameTestAssertException("the write succeeded while another thread held the EditScope");
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
        runtime.writer(world, WriteOptions.DEFAULT)
                .write(chest.getX(), chest.getY(), chest.getZ(), rec.before(), rec.beforeTile());
        check(world.getBlockEntity(chest) instanceof ChestBlockEntity c && c.getItem(0).getCount() == 7,
                "chest contents lost");
        context.succeed();
    }

    private static String signText(ServerLevel world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof SignBlockEntity s ? s.getFrontText().getMessage(0, false).getString()
                : "<no sign>";
    }

    private static int listenersAt(ServerLevel world, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        GameEventListenerRegistry dispatcher = world.getChunkAt(pos)
                .getListenerRegistry(SectionPos.blockToSectionCoord(pos.getY()));
        int[] count = {0};
        dispatcher.visitInRangeListeners(GameEvent.BLOCK_CHANGE, center, GameEvent.Context.of(Blocks.STONE.defaultBlockState()),
                (listener, at) -> {
                    if (at.distanceToSqr(center) < 0.01) count[0]++;
                });
        return count[0];
    }
}
