package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.mask.BlockSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.BlockFamilies;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.text.Text;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Better Replace, Overlay, Naturalize and Update blocks on a real server (region slots 1120-1128): Keep shape and Whole family keep stairs' facing, slabs' half, a sign's text and a door's
 * halves; Overlay and Naturalize find each column's highest block; Update blocks connects fences, panes, walls, stairs
 * and redstone without breaking a torch or plant or dropping sand, keeps a chest's items, and fixes stale light; each is
 * one history step undone exactly (block entities and waterlogging included); refusals and protection write nothing
 * they must not; symmetry covers the mirror copy. Two opt-in benchmarks time 2,097,152-cell Update blocks and Overlay
 * jobs (SCULPTORY_BENCH=1).
 */
public final class ReplaceOpsGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int FLOOR = 100;

    // ------------------------------------------------------------------------------------------------ helpers

    private static JobResult run(Harness h, EditExecutor executor, OpSpec op) {
        RecordingListener listener = new RecordingListener();
        try {
            h.service.run(h.player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
        MultiplayerGameTest.tickUntil(executor, () -> listener.result != null, 200, op.getClass().getSimpleName());
        check(listener.result.outcome() == JobOutcome.COMPLETED, op.getClass().getSimpleName() + ": " + listener.result);
        return listener.result;
    }

    private static void step(Harness h, EditExecutor executor, boolean undo) {
        RecordingListener listener = MultiplayerGameTest.historyStep(h, h.player, undo);
        MultiplayerGameTest.tickUntil(executor, () -> listener.result != null, 200, undo ? "undo" : "redo");
        check(listener.result.outcome() == JobOutcome.COMPLETED && listener.result.skippedConflicts() == 0,
                (undo ? "undo " : "redo ") + listener.result);
    }

    /** {@code area} and one chunk around it: Update blocks reads the chunks beside the ones it writes. */
    private static Box around(Box area) {
        return box(area.min().x() - 16, area.min().y(), area.min().z() - 16, area.max().x() + 16, area.max().y(),
                area.max().z() + 16);
    }

    /** Air over {@code area}, then a stone floor at {@link #FLOOR}, its chunks and those around loaded. */
    private static BlockWriter prepare(Harness h, Box area) {
        loadAndForce(h.world, around(area));
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int air = h.state("minecraft:air"), stone = h.state("minecraft:stone");
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    writer.write(x, y, z, y == FLOOR ? stone : air, null);
                }
            }
        }
        return writer;
    }

    private static void set(Harness h, BlockWriter writer, int x, int y, int z, String spec) {
        writer.write(x, y, z, h.state(spec), null);
    }

    private static BlockState at(ServerWorld world, int x, int y, int z) {
        return world.getBlockState(pos(x, y, z));
    }

    private static void checkState(Harness h, int x, int y, int z, String spec, String what) {
        BlockState state = at(h.world, x, y, z);
        check(net.minecraft.block.Block.getRawIdFromState(state) == h.state(spec), what + ": " + state + ", not " + spec);
    }

    private static List<HistoryEntry> entries(Harness h) {
        return h.service.historyService().undoEntries(h.player.getUuid());
    }

    private static void finish(Harness h, EditExecutor executor, Box area, TestContext context) {
        executor.shutdown();
        forceChunks(h.world, around(area), false);
        h.close();
        context.complete();
    }

    private static Region cuboid(Box box) {
        return new Region.Cuboid(box);
    }

    // ------------------------------------------------------------------------------------------------ Replace

    /**
     * Keep shape turns oak stairs into spruce stairs with their facing, half and water; Whole family (oak planks to spruce
     * planks) swaps every oak block, keeping a top slab's half, a log's axis, a door's halves and a sign's text, and never
     * touches dark oak. Each is one step, undone exactly and redone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_replace_ops_replace", tickLimit = LIMIT)
    public void keepShapeAndWholeFamilyKeepEveryBlocksShape(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 1120);
        int x0 = at[0] + 2, z0 = at[1] + 2;
        Box area = box(x0, FLOOR, z0, x0 + 13, FLOOR + 3, z0 + 3);
        BlockWriter writer = prepare(h, area);
        int y = FLOOR + 1;
        set(h, writer, x0 + 1, y, z0 + 1, "minecraft:oak_stairs[facing=east,half=top]");
        set(h, writer, x0 + 2, y, z0 + 1, "minecraft:oak_stairs[facing=north,half=bottom,waterlogged=true]");
        set(h, writer, x0 + 3, y, z0 + 1, "minecraft:oak_stairs[facing=west,shape=outer_left]");
        set(h, writer, x0 + 4, y, z0 + 1, "minecraft:oak_slab[type=top]");
        set(h, writer, x0 + 5, y, z0 + 1, "minecraft:oak_planks");
        set(h, writer, x0 + 6, y, z0 + 1, "minecraft:oak_log[axis=x]");
        set(h, writer, x0 + 7, y, z0 + 1, "minecraft:oak_sign[rotation=4]");
        set(h, writer, x0 + 8, y, z0 + 1, "minecraft:dark_oak_planks");
        set(h, writer, x0 + 9, y, z0 + 1, "minecraft:oak_door[facing=south,half=lower,open=true]");
        set(h, writer, x0 + 9, y + 1, z0 + 1, "minecraft:oak_door[facing=south,half=upper,open=true]");
        SignBlockEntity sign = (SignBlockEntity) h.world.getBlockEntity(pos(x0 + 7, y, z0 + 1));
        sign.setText(sign.getFrontText().withMessage(0, Text.literal("Hello")), true);
        sign.markDirty();
        Region region = cuboid(area);
        WorldSnapshot before = capture(h.world, area);

        // Keep shape: the stairs only (From is a block set).
        run(h, executor, new OpSpec.Replace(region, BlockSet.of(new BlockSet.Block(new NamespacedId("minecraft:oak_stairs")))
                .toCellMask(h.runtime.states()), new Pattern.KeepShape(new Pattern.Single(h.state("minecraft:spruce_stairs")))));
        checkState(h, x0 + 1, y, z0 + 1, "minecraft:spruce_stairs[facing=east,half=top]", "a top stair facing east");
        checkState(h, x0 + 2, y, z0 + 1, "minecraft:spruce_stairs[facing=north,half=bottom,waterlogged=true]",
                "a waterlogged stair");
        checkState(h, x0 + 3, y, z0 + 1, "minecraft:spruce_stairs[facing=west,shape=outer_left]", "a corner stair");
        checkState(h, x0 + 5, y, z0 + 1, "minecraft:oak_planks", "not in From");
        check(entries(h).size() == 1 && entries(h).get(0).label().startsWith("Replace"), "one Replace step");
        step(h, executor, true);
        checkSame(before, capture(h.world, area), "after undoing Keep shape");

        // Whole family: oak to spruce, as the client computes it.
        List<Pattern.BlockSwap> swaps = BlockFamilies.swaps(new NamespacedId("minecraft:oak_planks"),
                new NamespacedId("minecraft:spruce_planks"), h.runtime.states());
        check(swaps.size() >= 20, "oak has " + swaps.size() + " swaps");
        run(h, executor, new OpSpec.Replace(region, new CellMask.Blocks(swaps.stream().map(Pattern.BlockSwap::from).toList()),
                new Pattern.Remap(swaps, true)));
        checkState(h, x0 + 1, y, z0 + 1, "minecraft:spruce_stairs[facing=east,half=top]", "family: a stair");
        checkState(h, x0 + 2, y, z0 + 1, "minecraft:spruce_stairs[facing=north,waterlogged=true]", "family: water kept");
        checkState(h, x0 + 4, y, z0 + 1, "minecraft:spruce_slab[type=top]", "a top slab");
        checkState(h, x0 + 5, y, z0 + 1, "minecraft:spruce_planks", "planks");
        checkState(h, x0 + 6, y, z0 + 1, "minecraft:spruce_log[axis=x]", "a log's axis");
        checkState(h, x0 + 7, y, z0 + 1, "minecraft:spruce_sign[rotation=4]", "a sign's rotation");
        checkState(h, x0 + 8, y, z0 + 1, "minecraft:dark_oak_planks", "dark oak is not oak");
        checkState(h, x0 + 9, y, z0 + 1, "minecraft:spruce_door[facing=south,half=lower,open=true]", "the door's bottom");
        checkState(h, x0 + 9, y + 1, z0 + 1, "minecraft:spruce_door[facing=south,half=upper,open=true]", "the door's top");
        SignBlockEntity spruceSign = (SignBlockEntity) h.world.getBlockEntity(pos(x0 + 7, y, z0 + 1));
        check(spruceSign != null && spruceSign.getFrontText().getMessage(0, false).getString().equals("Hello"),
                "the sign keeps its text");
        WorldSnapshot after = capture(h.world, area);
        step(h, executor, true);
        checkSame(before, capture(h.world, area), "after undoing Whole family");
        step(h, executor, false);
        checkSame(after, capture(h.world, area), "after redoing Whole family");
        finish(h, executor, area, context);
    }

    // ------------------------------------------------------------------------------------------------ Overlay, Naturalize

    /**
     * Overlay lays two cobblestone on each column's grass, through short grass, but not on a poppy, a torch, water or a
     * buried column; Naturalize turns andesite into grass, three dirt and stone, leaves a chest and its items and a column
     * under water alone. Each is one step undone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_replace_ops_layers", tickLimit = LIMIT)
    public void overlayAndNaturalizeWorkFromEachColumnsHighestBlock(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 1121);
        int x0 = at[0] + 2, z0 = at[1] + 2;
        Box area = box(x0, FLOOR, z0, x0 + 7, FLOOR + 14, z0 + 7);
        BlockWriter writer = prepare(h, area);
        int top = FLOOR + 6;
        for (int x = x0; x <= x0 + 7; x++) {
            for (int z = z0; z <= z0 + 7; z++) {
                for (int y = FLOOR + 1; y <= top; y++) set(h, writer, x, y, z, y == top ? "minecraft:grass_block" : "minecraft:andesite");
            }
        }
        set(h, writer, x0 + 1, top + 1, z0 + 1, "minecraft:poppy");
        set(h, writer, x0 + 2, top + 1, z0 + 2, "minecraft:short_grass");
        set(h, writer, x0 + 3, top + 1, z0 + 3, "minecraft:torch");
        set(h, writer, x0 + 4, top, z0 + 4, "minecraft:water");
        for (int y = top + 1; y <= FLOOR + 14; y++) set(h, writer, x0 + 5, y, z0 + 5, "minecraft:stone");
        set(h, writer, x0 + 6, FLOOR + 3, z0 + 6, "minecraft:chest");
        ((Inventory) h.world.getBlockEntity(pos(x0 + 6, FLOOR + 3, z0 + 6))).setStack(0, new ItemStack(Items.DIAMOND, 3));
        WorldSnapshot before = capture(h.world, area);
        Region region = cuboid(box(x0, FLOOR + 1, z0, x0 + 7, FLOOR + 12, z0 + 7));

        run(h, executor, new OpSpec.Overlay(region, new Pattern.Single(h.state("minecraft:cobblestone")), 2));
        for (int x = x0; x <= x0 + 7; x++) {
            for (int z = z0; z <= z0 + 7; z++) {
                int dx = x - x0, dz = z - z0;
                boolean skipped = dx == dz && dx >= 1 && dx <= 5 && dx != 2;
                String what = "overlay at " + dx + "," + dz;
                if (skipped) {
                    check(!at(h.world, x, top + 2, z).isOf(Blocks.COBBLESTONE), what + ": left alone");
                } else {
                    check(at(h.world, x, top + 1, z).isOf(Blocks.COBBLESTONE)
                            && at(h.world, x, top + 2, z).isOf(Blocks.COBBLESTONE)
                            && at(h.world, x, top + 3, z).isAir(), what);
                }
            }
        }
        check(at(h.world, x0 + 1, top + 1, z0 + 1).isOf(Blocks.POPPY) && at(h.world, x0 + 3, top + 1, z0 + 3).isOf(Blocks.TORCH)
                && at(h.world, x0 + 4, top, z0 + 4).isOf(Blocks.WATER), "the poppy, torch and water stay");
        step(h, executor, true);
        checkSame(before, capture(h.world, area), "after undoing Overlay");

        run(h, executor, new OpSpec.Naturalize(region, new Pattern.Single(h.state("minecraft:grass_block")), 1,
                new Pattern.Single(h.state("minecraft:dirt")), 3, new Pattern.Single(h.state("minecraft:stone"))));
        checkState(h, x0, top, z0, "minecraft:grass_block", "the top");
        for (int y = top - 3; y < top; y++) checkState(h, x0, y, z0, "minecraft:dirt", "dirt at " + y);
        for (int y = FLOOR + 1; y < top - 3; y++) checkState(h, x0, y, z0, "minecraft:stone", "stone at " + y);
        checkState(h, x0 + 4, top - 1, z0 + 4, "minecraft:andesite", "under water: left alone");
        check(at(h.world, x0 + 6, FLOOR + 3, z0 + 6).isOf(Blocks.CHEST)
                && ((Inventory) h.world.getBlockEntity(pos(x0 + 6, FLOOR + 3, z0 + 6))).getStack(0).getCount() == 3,
                "the chest and its diamonds stay");
        step(h, executor, true);
        checkSame(before, capture(h.world, area), "after undoing Naturalize");
        finish(h, executor, area, context);
    }

    // ------------------------------------------------------------------------------------------------ Update blocks

    /**
     * Update blocks connects a fence line (a waterlogged post stays waterlogged), a pane pair, a wall, a stair corner and
     * two redstone wires, and turns a lone half of a double chest single with its items; it breaks no wall torch without a
     * wall, sapling on stone or half sunflower, and a floating anvil or scaffolding does not fall (their ticks are dropped),
     * even 40 ticks later. One step, undone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_replace_ops_update", tickLimit = LIMIT)
    public void updateBlocksConnectsShapesWithoutPhysics(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 1122);
        int x0 = at[0] + 2, z0 = at[1] + 2;
        Box area = box(x0, FLOOR, z0, x0 + 15, FLOOR + 5, z0 + 11);
        BlockWriter writer = prepare(h, area);
        int y = FLOOR + 1;
        for (int x = x0 + 1; x <= x0 + 5; x++) {
            set(h, writer, x, y, z0 + 2, x == x0 + 5 ? "minecraft:oak_fence[waterlogged=true]" : "minecraft:oak_fence");
        }
        set(h, writer, x0 + 1, y, z0 + 4, "minecraft:glass_pane");
        set(h, writer, x0 + 2, y, z0 + 4, "minecraft:glass_pane");
        for (int x = x0 + 1; x <= x0 + 3; x++) set(h, writer, x, y, z0 + 6, "minecraft:cobblestone_wall");
        set(h, writer, x0 + 1, y, z0 + 8, "minecraft:oak_stairs[facing=north]");
        set(h, writer, x0 + 1, y, z0 + 7, "minecraft:oak_stairs[facing=east]");
        set(h, writer, x0 + 4, y, z0 + 8, "minecraft:redstone_wire");
        set(h, writer, x0 + 5, y, z0 + 8, "minecraft:redstone_wire");
        set(h, writer, x0 + 8, y, z0 + 8, "minecraft:chest[facing=north,type=left]");
        ((Inventory) h.world.getBlockEntity(pos(x0 + 8, y, z0 + 8))).setStack(0, new ItemStack(Items.EMERALD, 5));
        set(h, writer, x0 + 8, y, z0 + 2, "minecraft:torch");
        set(h, writer, x0 + 10, y + 2, z0 + 2, "minecraft:wall_torch[facing=north]");
        set(h, writer, x0 + 12, y, z0 + 2, "minecraft:oak_sapling");
        set(h, writer, x0 + 12, y, z0 + 4, "minecraft:sunflower[half=lower]");
        set(h, writer, x0 + 14, y + 3, z0 + 2, "minecraft:anvil[facing=north]");
        set(h, writer, x0 + 14, y + 3, z0 + 5, "minecraft:scaffolding[distance=7,bottom=false]");
        WorldSnapshot before = capture(h.world, area);
        check(!at(h.world, x0 + 2, y, z0 + 2).get(Properties.EAST), "the fences start unconnected");

        JobResult result = run(h, executor, new OpSpec.UpdateBlocks(cuboid(box(x0, FLOOR, z0, x0 + 15, FLOOR + 5, z0 + 11))));
        for (int x = x0 + 1; x <= x0 + 5; x++) {
            BlockState fence = at(h.world, x, y, z0 + 2);
            check(fence.isOf(Blocks.OAK_FENCE) && fence.get(Properties.EAST) == (x < x0 + 5)
                    && fence.get(Properties.WEST) == (x > x0 + 1), "the fence at " + (x - x0) + ": " + fence);
        }
        check(at(h.world, x0 + 5, y, z0 + 2).get(Properties.WATERLOGGED), "the waterlogged post keeps its water");
        check(at(h.world, x0 + 1, y, z0 + 4).get(Properties.EAST), "the panes connect");
        check(at(h.world, x0 + 2, y, z0 + 6).get(Properties.EAST_WALL_SHAPE) != net.minecraft.block.enums.WallShape.NONE,
                "the wall connects");
        check(at(h.world, x0 + 1, y, z0 + 8).get(Properties.STAIR_SHAPE) != net.minecraft.block.enums.StairShape.STRAIGHT,
                "the stair turns a corner: " + at(h.world, x0 + 1, y, z0 + 8));
        check(at(h.world, x0 + 4, y, z0 + 8).get(Properties.EAST_WIRE_CONNECTION)
                != net.minecraft.block.enums.WireConnection.NONE, "the wires join");
        BlockState chest = at(h.world, x0 + 8, y, z0 + 8);
        check(chest.isOf(Blocks.CHEST) && chest.get(Properties.CHEST_TYPE) == net.minecraft.block.enums.ChestType.SINGLE,
                "the lone chest half is single: " + chest);
        check(((Inventory) h.world.getBlockEntity(pos(x0 + 8, y, z0 + 8))).getStack(0).getCount() == 5, "its emeralds stay");
        check(entries(h).size() == 1 && entries(h).get(0).label().startsWith("Update blocks"),
                "one step: " + entries(h).stream().map(HistoryEntry::label).toList());
        check(result.changed() > 0, "changed " + result.changed());
        TimedTaskRunner runner = context.createTimedTaskRunner();
        int[] waited = {0};
        runner.createAndAdd(() -> check(++waited[0] >= 40, "waiting 40 ticks"));
        runner.createAndAdd(() -> {
            check(at(h.world, x0 + 8, y, z0 + 2).isOf(Blocks.TORCH), "the torch stands");
            check(at(h.world, x0 + 10, y + 2, z0 + 2).isOf(Blocks.WALL_TORCH), "the wall torch without a wall stands");
            check(at(h.world, x0 + 12, y, z0 + 2).isOf(Blocks.OAK_SAPLING), "the sapling on stone is not broken");
            check(at(h.world, x0 + 12, y, z0 + 4).isOf(Blocks.SUNFLOWER), "the half sunflower is not broken");
            check(at(h.world, x0 + 14, y + 3, z0 + 2).isOf(Blocks.ANVIL), "the floating anvil did not fall");
            check(at(h.world, x0 + 14, y + 3, z0 + 5).isOf(Blocks.SCAFFOLDING), "the floating scaffolding did not fall");
            step(h, executor, true);
            checkSame(before, capture(h.world, area), "after undoing Update blocks");
            finish(h, executor, area, context);
        });
        runner.completeIfSuccessful();
    }

    /**
     * Stale light (a glowstone written straight into a chunk section, which the light engine never saw, and one removed the
     * same way) is fixed by Update blocks: the dark one lights its neighbours, the removed one's light goes.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_replace_ops_light", tickLimit = LIMIT)
    public void updateBlocksFixesStaleLight(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 1123);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, FLOOR, z0, x0 + 15, FLOOR + 5, z0 + 15);
        BlockWriter writer = prepare(h, area);
        int y = FLOOR + 2;
        set(h, writer, x0 + 14, y, z0 + 14, "minecraft:glowstone");
        TimedTaskRunner runner = context.createTimedTaskRunner();
        runner.createAndAdd(() -> check(h.world.getLightLevel(LightType.BLOCK, pos(x0 + 14, y + 1, z0 + 14)) == 14,
                "the placed glowstone lights up"));
        runner.createAndAdd(() -> {
            // Behind the light engine's back: one glowstone appears, the other goes.
            raw(h.world, x0 + 1, y, z0 + 1, Blocks.GLOWSTONE.getDefaultState());
            raw(h.world, x0 + 14, y, z0 + 14, Blocks.AIR.getDefaultState());
        });
        int[] waited = {0};
        runner.createAndAdd(() -> check(++waited[0] >= 20, "waiting 20 ticks"));
        runner.createAndAdd(() -> {
            check(h.world.getLightLevel(LightType.BLOCK, pos(x0 + 1, y + 1, z0 + 1)) == 0, "the hidden glowstone is dark");
            check(h.world.getLightLevel(LightType.BLOCK, pos(x0 + 14, y + 1, z0 + 14)) == 14, "the light of the gone one stays");
            run(h, executor, new OpSpec.UpdateBlocks(cuboid(area)));
        });
        runner.createAndAdd(() -> {
            check(h.world.getLightLevel(LightType.BLOCK, pos(x0 + 1, y + 1, z0 + 1)) == 14, "the glowstone lights up now");
            check(h.world.getLightLevel(LightType.BLOCK, pos(x0 + 14, y + 1, z0 + 14)) == 0, "the stale light is gone");
            finish(h, executor, area, context);
        });
        runner.completeIfSuccessful();
    }

    /** Writes a state into its chunk section directly: no light update, no neighbour update, no client sync. */
    private static void raw(ServerWorld world, int x, int y, int z, BlockState state) {
        WorldChunk chunk = world.getWorldChunk(pos(x, y, z));
        ChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
        section.setBlockState(x & 15, y & 15, z & 15, state);
    }

    // ------------------------------------------------------------------------------------------------ refusals

    /**
     * Without the region node every op is refused; Update blocks above the world is invalid; an empty From changes
     * nothing; a protected half stays as it was while the other half is done, and undo gives the rest back exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_replace_ops_refusals", tickLimit = LIMIT)
    public void refusalsAndProtectionWriteNothingTheyMayNot(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 1124);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, FLOOR, z0, x0 + 31, FLOOR + 4, z0 + 7);
        BlockWriter writer = prepare(h, area);
        for (int x = x0; x <= x0 + 31; x += 2) set(h, writer, x, FLOOR + 1, z0 + 3, "minecraft:oak_fence");
        for (int x = x0 + 1; x <= x0 + 31; x += 2) set(h, writer, x, FLOOR + 1, z0 + 3, "minecraft:oak_fence");
        WorldSnapshot before = capture(h.world, area);
        Region region = cuboid(area);
        Pattern cobble = new Pattern.Single(h.state("minecraft:cobblestone"));

        ServerPlayerEntity denied = h.addPlayer();
        EditTestSupport.deny(denied, Perm.REGION);
        for (OpSpec op : List.of(new OpSpec.Overlay(region, cobble, 1), new OpSpec.Naturalize(region, cobble, 1, cobble, 3,
                cobble), new OpSpec.UpdateBlocks(region))) {
            EditRejected refused = ClipboardGameTest.refusal(() -> h.service.run(denied, op, RunOptions.DEFAULT, null));
            check(refused.reason() == RejectReason.NO_PERMISSION, op.getClass().getSimpleName() + ": " + refused.reason());
        }
        EditRejected above = ClipboardGameTest.refusal(() -> h.service.run(h.player, new OpSpec.UpdateBlocks(
                cuboid(box(x0, 400, z0, x0 + 3, 404, z0 + 3))), RunOptions.DEFAULT, null));
        check(above.reason() == RejectReason.INVALID, "above the world: " + above.reason());
        boolean depthRefused = false;
        try {
            new OpSpec.Overlay(region, cobble, 17);
        } catch (IllegalArgumentException e) {
            depthRefused = true;
        }
        check(depthRefused, "a depth of 17 is no op");
        JobResult empty = run(h, executor, new OpSpec.Replace(region, new CellMask.Blocks(List.of()), cobble));
        check(empty.changed() == 0, "an empty From changed " + empty.changed());
        checkSame(before, capture(h.world, area), "after the refusals");

        // The east half is protected: the fence posts there stay unconnected.
        ProtectionHook.protect(h.player, h.world, x0 + 16, z0, x0 + 31, z0 + 7);
        try {
            JobResult update = run(h, executor, new OpSpec.UpdateBlocks(region));
            check(update.skippedProtected() > 0, "protected cells skipped: " + update.skippedProtected());
            check(at(h.world, x0 + 4, FLOOR + 1, z0 + 3).get(Properties.EAST), "the west half connects");
            for (int x = x0 + 16; x <= x0 + 31; x++) {
                check(!at(h.world, x, FLOOR + 1, z0 + 3).get(Properties.EAST) && !at(h.world, x, FLOOR + 1, z0 + 3)
                        .get(Properties.WEST), "the protected fence at " + (x - x0) + " is untouched");
            }
            JobResult overlay = run(h, executor, new OpSpec.Overlay(region, cobble, 1));
            check(overlay.skippedProtected() > 0, "overlay skipped protected cells");
            check(at(h.world, x0 + 20, FLOOR + 1, z0 + 1).isAir(), "no layer on the protected half");
            check(at(h.world, x0 + 2, FLOOR + 1, z0 + 1).isOf(Blocks.COBBLESTONE), "a layer on the rest");
        } finally {
            ProtectionHook.clear(h.player);
        }
        step(h, executor, true);
        step(h, executor, true);
        checkSame(before, capture(h.world, area), "after undoing both");
        finish(h, executor, area, context);
    }

    // ------------------------------------------------------------------------------------------------ symmetry

    /**
     * With a mirror, Overlay lays its layer on both copies, Update blocks connects both fence lines and Whole family
     * swaps both, each still one step undone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_replace_ops_symmetry", tickLimit = LIMIT)
    public void theOpsHonourSymmetry(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 1125);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, FLOOR, z0, x0 + 31, FLOOR + 4, z0 + 7);
        BlockWriter writer = prepare(h, area);
        // The mirror plane x = x0 + 16: cell x lands on 2 (x0 + 16) - 1 - x.
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 16), 0);
        for (int x = x0 + 2; x <= x0 + 6; x++) {
            set(h, writer, x, FLOOR + 1, z0 + 3, "minecraft:oak_fence");
            set(h, writer, 2 * (x0 + 16) - 1 - x, FLOOR + 1, z0 + 3, "minecraft:oak_fence");
        }
        set(h, writer, x0 + 3, FLOOR + 1, z0 + 5, "minecraft:oak_stairs[facing=east]");
        set(h, writer, 2 * (x0 + 16) - 1 - (x0 + 3), FLOOR + 1, z0 + 5, "minecraft:oak_stairs[facing=west]");
        WorldSnapshot before = capture(h.world, area);
        Region west = cuboid(box(x0, FLOOR, z0, x0 + 8, FLOOR + 4, z0 + 7));

        run(h, executor, new OpSpec.UpdateBlocks(west, mirror));
        for (int x = x0 + 2; x < x0 + 6; x++) {
            check(at(h.world, x, FLOOR + 1, z0 + 3).get(Properties.EAST), "west fence " + (x - x0));
            check(at(h.world, 2 * (x0 + 16) - 1 - x, FLOOR + 1, z0 + 3).get(Properties.WEST), "mirrored fence " + (x - x0));
        }
        List<Pattern.BlockSwap> swaps = BlockFamilies.swaps(new NamespacedId("minecraft:oak_planks"),
                new NamespacedId("minecraft:birch_planks"), h.runtime.states());
        run(h, executor, new OpSpec.Replace(west, new CellMask.Blocks(swaps.stream().map(Pattern.BlockSwap::from).toList()),
                new Pattern.Remap(swaps, true), mirror));
        checkState(h, x0 + 3, FLOOR + 1, z0 + 5, "minecraft:birch_stairs[facing=east]", "the stair");
        checkState(h, 2 * (x0 + 16) - 1 - (x0 + 3), FLOOR + 1, z0 + 5, "minecraft:birch_stairs[facing=west]",
                "the mirrored stair keeps its own facing");
        check(at(h.world, 2 * (x0 + 16) - 1 - (x0 + 4), FLOOR + 1, z0 + 3).isOf(Blocks.BIRCH_FENCE), "a mirrored fence");
        run(h, executor, new OpSpec.Overlay(west, new Pattern.Single(h.state("minecraft:snow")), 1, mirror));
        check(at(h.world, x0 + 1, FLOOR + 1, z0 + 1).isOf(Blocks.SNOW), "snow on the west copy");
        check(at(h.world, 2 * (x0 + 16) - 1 - (x0 + 1), FLOOR + 1, z0 + 1).isOf(Blocks.SNOW), "snow on the mirror copy");
        check(at(h.world, x0 + 16, FLOOR + 1, z0 + 1).isAir(), "none between them");
        check(entries(h).size() == 3, "three steps");
        for (int i = 0; i < 3; i++) step(h, executor, true);
        checkSame(before, capture(h.world, area), "after undoing all three");
        finish(h, executor, area, context);
    }

    /**
     * The tick budget changes when sections run, never what they write: the same build updated by an executor with a
     * 1 ms budget (a section at a time, over many ticks) and by one with a large budget (all in a tick or two) ends the
     * same, cell for cell and block entity for block entity, and both undo exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_replace_ops_budget", tickLimit = LIMIT)
    public void aTightTickBudgetSpreadsUpdateBlocksWithoutChangingIt(TestContext context) {
        EditExecutor tight = new EditExecutor(context.getWorld().getServer(), EngineTestSupport.runtime(context).states(),
                new EditExecutor.Settings(1_000_000L, 0, 0.4, 2, 8, 32, 64,
                        dev.sculptory.server.config.UnloadedPolicy.LOAD, 1024, 16_384));
        EditExecutor wide = MultiplayerGameTest.executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, tight);
        Harness h2 = new Harness(context, null, System::nanoTime, wide);
        int[] at = regionCorner(context, 1128);
        Box a = box(at[0], FLOOR, at[1], at[0] + 47, FLOOR + 20, at[1] + 15);
        Box b = a.offset(96, 0, 0);
        String[] mix = {"minecraft:oak_fence", "minecraft:glass_pane", "minecraft:cobblestone_wall", "minecraft:oak_stairs",
                "minecraft:stone", "minecraft:air", "minecraft:redstone_wire", "minecraft:chest[type=left]"};
        for (Box area : List.of(a, b)) {
            BlockWriter writer = prepare(h, area);
            for (int x = 0; x < 48; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = FLOOR + 1; y <= FLOOR + 20; y++) {
                        set(h, writer, area.min().x() + x, y, area.min().z() + z,
                                mix[Math.floorMod(x * 31 + y * 17 + z * 7 + (x * z) % 5, mix.length)]);
                    }
                }
            }
        }
        WorldSnapshot beforeA = capture(h.world, a);
        RecordingListener slow = new RecordingListener();
        RecordingListener fast = new RecordingListener();
        try {
            h.service.run(h.player, new OpSpec.UpdateBlocks(cuboid(a)), RunOptions.DEFAULT, slow);
            h2.service.run(h2.player, new OpSpec.UpdateBlocks(cuboid(b)), RunOptions.DEFAULT, fast);
        } catch (EditRejected e) {
            throw new GameTestException("refused: " + e.getMessage());
        }
        int slowTicks = MultiplayerGameTest.ticksUntil(tight, () -> slow.result != null, 2000);
        int fastTicks = MultiplayerGameTest.ticksUntil(wide, () -> fast.result != null, 200);
        check(slow.result.outcome() == JobOutcome.COMPLETED && fast.result.outcome() == JobOutcome.COMPLETED,
                slow.result + " / " + fast.result);
        check(slowTicks > fastTicks, "the tight budget took " + slowTicks + " ticks, the wide one " + fastTicks);
        check(slow.result.changed() == fast.result.changed() && slow.result.changed() > 0,
                "changed " + slow.result.changed() + " and " + fast.result.changed());
        WorldSnapshot afterA = capture(h.world, a), afterB = capture(h.world, b);
        check(java.util.Arrays.equals(afterA.states, afterB.states), "the two budgets wrote different states");
        check(afterA.tiles.size() == afterB.tiles.size(), "block entities differ");
        RecordingListener undo = MultiplayerGameTest.historyStep(h, h.player, true);
        MultiplayerGameTest.tickUntil(tight, () -> undo.result != null, 2000, "undo");
        checkSame(beforeA, capture(h.world, a), "after undoing the sliced run");
        tight.shutdown();
        h2.close();
        finish(h, wide, a, context);
        forceChunks(h.world, around(b), false);
    }

    // ------------------------------------------------------------------------------------------------ benchmarks

    /**
     * Opt-in: Update blocks over 128³ (2,097,152) cells of a mixed build (stone, air, fences, panes, walls, stairs), on the
     * server's own executor and budget: wall time, ticks, the longest server tick; its undo.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_replace_ops_bench_update", tickLimit = 200_000)
    public void benchUpdateBlocks2M(TestContext context) {
        if (BenchSupport.skipped(context, "benchUpdateBlocks2M")) return;
        bench(context, "benchUpdateBlocks2M", 1126, true);
    }

    /** Opt-in: Overlay (depth 3) over 128³ cells of hilly terrain, timed like {@link #benchUpdateBlocks2M}. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_replace_ops_bench_overlay", tickLimit = 200_000)
    public void benchOverlay2M(TestContext context) {
        if (BenchSupport.skipped(context, "benchOverlay2M")) return;
        bench(context, "benchOverlay2M", 1127, false);
    }

    private static void bench(TestContext context, String name, int slot, boolean update) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, slot);
        Box area = box(at[0], 64, at[1], at[0] + 127, 191, at[1] + 127);
        loadAndForce(h.world, area);
        int[] mix = update
                ? new int[] {h.state("minecraft:stone"), h.state("minecraft:air"), h.state("minecraft:oak_fence"),
                        h.state("minecraft:glass_pane"), h.state("minecraft:cobblestone_wall"),
                        h.state("minecraft:oak_stairs")}
                : new int[] {h.state("minecraft:air")};
        OpSpec setUp = update
                ? new OpSpec.Fill(area, new Pattern.Weighted(mix, new int[] {30, 30, 10, 10, 10, 10}, 5L), CellMask.ANY)
                : new OpSpec.Fill(area, new Pattern.Single(mix[0]), CellMask.ANY);
        OpSpec op = update ? new OpSpec.UpdateBlocks(cuboid(area))
                : new OpSpec.Overlay(cuboid(area), new Pattern.Single(h.state("minecraft:snow_block")), 3);
        BenchSupport.TickTimes ticks = new BenchSupport.TickTimes();
        RecordingListener[] listeners = new RecordingListener[3];
        WorldSnapshot[] before = new WorldSnapshot[1];
        long[] start = new long[1];
        int[] startTick = new int[1];
        TimedTaskRunner runner = context.createTimedTaskRunner();
        runner.createAndAdd(() -> {
            listeners[0] = new RecordingListener();
            submit(h, setUp, listeners[0]);
        });
        runner.createAndAdd(() -> check(listeners[0].result != null, "setting up"));
        runner.createAndAdd(() -> {
            if (!update) {
                // Hilly terrain: stone up to a height between 100 and 160 in each column.
                BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
                int stone = h.state("minecraft:stone"), grass = h.state("minecraft:grass_block");
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    for (int z = area.min().z(); z <= area.max().z(); z++) {
                        int top = 100 + Math.floorMod((x * 7 + z * 13) / 9, 60);
                        for (int y = 64; y <= top; y++) writer.write(x, y, z, y == top ? grass : stone, null);
                    }
                }
            }
            before[0] = capture(h.world, area);
            ticks.startNextTick();
            start[0] = System.nanoTime();
            startTick[0] = h.world.getServer().getTicks();
            listeners[1] = new RecordingListener();
            submit(h, op, listeners[1]);
        });
        runner.createAndAdd(() -> check(listeners[1].result != null, "running"));
        runner.createAndAdd(() -> {
            ticks.stopAtTickEnd();
            BenchSupport.log("%s: %,d cells in %.0f ms wall, %d ticks, %,d changed", name, area.volume(),
                    BenchSupport.ms(System.nanoTime() - start[0]), h.world.getServer().getTicks() - startTick[0],
                    listeners[1].result.changed());
        });
        runner.createAndAdd(() -> {
            check(ticks.stopped(), "waiting for the tick to end");
            BenchSupport.log("%s: tick times while it ran: %s", name, ticks.summary());
            listeners[2] = MultiplayerGameTest.historyStep(h, h.player, true);
        });
        runner.createAndAdd(() -> check(listeners[2].result != null, "undoing"));
        runner.createAndAdd(() -> {
            checkSame(before[0], capture(h.world, area), name + " after undo");
            forceChunks(h.world, area, false);
            ticks.close();
            h.close();
        });
        runner.completeIfSuccessful();
    }

    private static void submit(Harness h, OpSpec op, RecordingListener listener) {
        try {
            h.service.run(h.player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }
}
