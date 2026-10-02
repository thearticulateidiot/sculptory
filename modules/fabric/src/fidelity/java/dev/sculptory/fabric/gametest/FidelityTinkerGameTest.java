package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.tinker.TinkerProperties;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.RunOptions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Tinker on Chipped and Farmer's Delight blocks (regionCorner slot 1027): modded
 * properties read and cycled through their own state definitions, a Chipped door's other half following, a cooking
 * pot turned with its ingredients kept, a canvas sign's text, and Apply to all on Chipped logs; every step undone and
 * redone exactly (states and block-entity contents).
 */
public final class FidelityTinkerGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    private static void tinker(Harness h, int x, int y, int z, String expected, String target, SignText sign) {
        try {
            h.service.block(h.player, new BlockPos(x, y, z), h.state(expected), h.state(target), sign);
        } catch (EditRejected e) {
            throw new GameTestException("Tinker refused " + expected + ": " + e.getMessage());
        }
    }

    private static void checkState(Harness h, int x, int y, int z, String spec) {
        int got = Block.getRawIdFromState(h.world.getBlockState(pos(x, y, z)));
        check(got == h.state(spec), x + "," + y + "," + z + ": " + h.world.getBlockState(pos(x, y, z))
                + " instead of " + spec);
    }

    /** Every modded property's values, as Tinker lists them, are the property's own values in its own order. */
    private static void checkModdedProperties(FabricStateSpace states, String... specs) {
        for (String spec : specs) {
            int h = states.parse(spec);
            check(h >= 0, "unknown state " + spec);
            BlockState state = states.state(h);
            for (Property<?> property : state.getProperties()) {
                List<String> expected = new ArrayList<>();
                values(property, expected);
                check(states.propertyValues(h, property.getName()).equals(expected), spec + " " + property.getName()
                        + ": " + states.propertyValues(h, property.getName()) + " instead of " + expected);
                String next = TinkerProperties.step(expected, states.describe(h).get(property.getName()), 1);
                check(states.withProperty(h, property.getName(), next) >= 0, spec + ": " + property.getName() + "=" + next);
            }
        }
    }

    private static <T extends Comparable<T>> void values(Property<T> property, List<String> into) {
        for (T value : property.getValues()) into.add(property.name(value));
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_tinker", tickLimit = LIMIT)
    public void tinkerOnModdedBlocksUndoesAndRedoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1027);
        int x0 = at[0] + 4, y0 = 100, z0 = at[1] + 4;
        Box all = EngineTestSupport.box(x0 - 2, y0, z0 - 2, x0 + 9, y0 + 5, z0 + 9);
        loadAndForce(world, all);
        FidelitySupport.build(h, world, x0, y0, z0);
        checkModdedProperties(h.runtime.states(),
                "chipped:barred_birch_door[facing=east,half=lower,hinge=left,open=false,powered=false]",
                "farmersdelight:cooking_pot[facing=south,support=none,waterlogged=false]",
                "farmersdelight:tomatoes[age=3,ropelogged=false]", "chipped:big_lantern[facing=east,waterlogged=true]",
                "farmersdelight:rope[east=false,north=true,south=true,tied_to_bell=false,waterlogged=false,west=true]");
        String doorLower = "chipped:barred_birch_door[facing=east,half=lower,hinge=left,open=false,powered=false]";
        String pot = "farmersdelight:cooking_pot[facing=south,support=none,waterlogged=false]";
        String sign = "farmersdelight:canvas_sign[rotation=6,waterlogged=false]";
        String trapdoor = "chipped:airy_birch_trapdoor[facing=south,half=top,open=true,powered=false,waterlogged=true]";
        WorldSnapshot[] snapshots = new WorldSnapshot[2];
        RecordingListener fill = new RecordingListener();
        List<RecordingListener> steps = new ArrayList<>();
        TimedTaskRunner runner = context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    snapshots[0] = capture(world, all);
                    tinker(h, x0, y0 + 1, z0 + 2, doorLower,
                            "chipped:barred_birch_door[facing=east,half=lower,hinge=left,open=true,powered=false]", null);
                    checkState(h, x0, y0 + 2, z0 + 2,
                            "chipped:barred_birch_door[facing=east,half=upper,hinge=left,open=true,powered=false]");
                    tinker(h, x0 + 1, y0 + 1, z0, pot,
                            "farmersdelight:cooking_pot[facing=west,support=none,waterlogged=false]", null);
                    CookingPotBlockEntity cooking = FidelitySupport.entity(world, x0 + 1, y0 + 1, z0,
                            CookingPotBlockEntity.class);
                    ItemStack carrots = cooking.getInventory().getStackInSlot(0);
                    check(carrots.isOf(Items.CARROT) && carrots.getCount() == 2, "the pot keeps its carrots: " + carrots);
                    tinker(h, x0 + 1, y0 + 1, z0 + 2, trapdoor,
                            "chipped:airy_birch_trapdoor[facing=south,half=top,open=true,powered=false,waterlogged=false]",
                            null);
                    SignText text = new SignText(new SignText.Side(List.of("Soup", "of the day", "", ""), "green", false),
                            SignText.Side.EMPTY.withLine(2, "back of Kitchen"));
                    tinker(h, x0 + 4, y0 + 1, z0 + 4, sign, sign, text);
                    SignBlockEntity canvas = FidelitySupport.entity(world, x0 + 4, y0 + 1, z0 + 4, SignBlockEntity.class);
                    check(canvas.getFrontText().getMessage(0, false).getString().equals("Soup")
                            && canvas.getFrontText().getMessage(1, false).getString().equals("of the day"),
                            "the canvas sign reads " + canvas.getFrontText().getMessage(0, false));
                    try {
                        h.service.run(h.player, new OpSpec.Fill(all, new Pattern.SetProperty(
                                h.state("chipped:bundled_acacia_log[axis=y]"), "axis"), CellMask.ANY), RunOptions.DEFAULT,
                                fill);
                    } catch (EditRejected e) {
                        throw new GameTestException("apply to all refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(fill.result != null, "the fill finished"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED && fill.result.changed() == 2, "fill " + fill.result);
                    checkState(h, x0 + 2, y0 + 1, z0 + 2, "chipped:bundled_acacia_log[axis=y]");
                    checkState(h, x0 + 3, y0 + 1, z0 + 2, "chipped:bundled_acacia_log[axis=y]");
                    check(h.service.historyService().undoEntries(h.player.getUuid()).size() == 5, "five steps");
                    snapshots[1] = capture(world, all);
                }));
        for (int i = 0; i < 5; i++) step(runner, h, steps, true);
        runner.createAndAdd(() -> checkSame(snapshots[0], capture(world, all), "after five undos"));
        for (int i = 0; i < 5; i++) step(runner, h, steps, false);
        runner.createAndAdd(() -> {
            checkSame(snapshots[1], capture(world, all), "after five redos");
            check(FidelitySupport.itemEntities(world, all) == 0, "nothing dropped");
            forceChunks(world, all, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    /** One undo (or redo) and its end: completed, nothing kept. */
    private static void step(TimedTaskRunner runner, Harness h, List<RecordingListener> steps, boolean undo) {
        runner.createAndAdd(EntitiesGameTest.once(() -> {
            RecordingListener listener = new RecordingListener();
            steps.add(listener);
            try {
                if (undo) {
                    h.service.undo(h.player, ConflictPolicy.SKIP_CONFLICTS, listener);
                } else {
                    h.service.redo(h.player, ConflictPolicy.SKIP_CONFLICTS, listener);
                }
            } catch (EditRejected e) {
                throw new GameTestException((undo ? "undo" : "redo") + " refused: " + e.getMessage());
            }
        }));
        runner.createAndAdd(() -> {
            RecordingListener last = steps.get(steps.size() - 1);
            check(last.result != null, "the step is running");
            check(last.result.outcome() == JobOutcome.COMPLETED && last.result.skippedConflicts() == 0,
                    "the step " + last.result);
        });
    }
}
