package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.tinker.DisplayRotation;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricEntities;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.platform.WriteOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.decoration.painting.PaintingVariants;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.math.Direction;

/**
 * Tinker on a real server (regionCorner slots 1020-1026 and 1028): a property
 * change as one exact step, with no block updates and the block entity kept; a door's other half; Apply to all like it
 * in the selection, also across a protected column (the rest changes, exact undo); sign text set as plain text and
 * sanitized for a non-operator; each entity kind's edits undone and redone exactly; and refusals (permission: no
 * region, no use, editing off; protection, unloaded, stale state, a busy area for blocks and entities, an entity
 * removed meanwhile, a painting that would not fit) that write nothing.
 */
public final class TinkerGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int FLOOR = 100;

    // ------------------------------------------------------------------------------------------------ helpers

    private static void tinker(Harness h, ServerPlayerEntity player, int x, int y, int z, String expected, String target,
                               SignText sign) {
        try {
            h.service.block(player, new BlockPos(x, y, z), h.state(expected), h.state(target), sign);
        } catch (EditRejected e) {
            throw new GameTestException("Tinker refused " + x + "," + y + "," + z + ": " + e.getMessage());
        }
    }

    private static EntityView edit(Harness h, ServerPlayerEntity player, UUID id, EntityEdit... edits) {
        try {
            return h.service.entity(player, id, List.of(edits));
        } catch (EditRejected e) {
            throw new GameTestException("Tinker refused an entity edit: " + e.getMessage());
        }
    }

    private static RejectReason refusal(EntitiesGameTest.ThrowingRun run) {
        return EntitiesGameTest.refusal(run);
    }

    private static String stateAt(ServerWorld world, int x, int y, int z) {
        return world.getBlockState(pos(x, y, z)).toString();
    }

    private static void checkState(Harness h, int x, int y, int z, String spec, String what) {
        int got = Block.getRawIdFromState(h.world.getBlockState(pos(x, y, z)));
        check(got == h.state(spec), what + ": " + stateAt(h.world, x, y, z) + " instead of " + spec);
    }

    private static List<HistoryEntry> entries(Harness h, ServerPlayerEntity player) {
        return h.service.historyService().undoEntries(player.getUuid());
    }

    private static void checkLatestLabel(Harness h, ServerPlayerEntity player, String label) {
        List<HistoryEntry> entries = entries(h, player);
        check(!entries.isEmpty(), "no history entry");
        check(entries.get(0).label().equals(label), "label '" + entries.get(0).label() + "', expected '" + label + "'");
    }

    /**
     * Steps that each run once; a step may start an undo or redo, and the next step waits until it has finished (its
     * listener has a result).
     */
    private static final class Script {
        private final Harness h;
        private final ServerPlayerEntity player;
        private final TimedTaskRunner runner;
        private RecordingListener running;

        Script(Harness h, ServerPlayerEntity player, TestContext context) {
            this.h = h;
            this.player = player;
            this.runner = context.createTimedTaskRunner();
        }

        Script then(Runnable step) {
            runner.createAndAdd(() -> check(running == null || running.result != null, "a history step is running"));
            runner.createAndAdd(EntitiesGameTest.once(() -> {
                if (running != null) {
                    check(running.result.outcome() == JobOutcome.COMPLETED, "the history step " + running.result);
                    check(running.result.skippedConflicts() == 0, "the history step kept "
                            + running.result.skippedConflicts() + " changed blocks or entities");
                    running = null;
                }
                step.run();
            }));
            return this;
        }

        /** A check tried again every tick until it passes (a wait). */
        Script waitFor(Runnable check) {
            runner.createAndAdd(check);
            return this;
        }

        Script undo() {
            return then(() -> {
                running = new RecordingListener();
                try {
                    h.service.undo(player, ConflictPolicy.SKIP_CONFLICTS, running);
                } catch (EditRejected e) {
                    throw new GameTestException("undo refused: " + e.getMessage());
                }
            });
        }

        Script redo() {
            return then(() -> {
                running = new RecordingListener();
                try {
                    h.service.redo(player, ConflictPolicy.SKIP_CONFLICTS, running);
                } catch (EditRejected e) {
                    throw new GameTestException("redo refused: " + e.getMessage());
                }
            });
        }

        void done(Runnable last) {
            then(last);
            runner.completeIfSuccessful();
        }
    }

    /** An entity's data as a Tinker step compares it: without volatile keys, but with where it stands and looks. */
    private static dev.sculptory.core.nbt.NbtCompound placed(Entity entity) {
        try {
            return EntityNbt.withoutVolatileKeepingPlacement(FabricEntities.fromBytes(
                    FabricEntities.bytes(FabricEntities.save(entity, EntityFilter.ALL))));
        } catch (IOException e) {
            throw new GameTestException("entity NBT: " + e);
        }
    }

    private static Entity live(Harness h, UUID id, String what) {
        Entity entity = h.world.getEntity(id);
        check(entity != null && !entity.isRemoved(), what + ": the entity is gone");
        return entity;
    }

    private static void checkPlaced(Harness h, UUID id, dev.sculptory.core.nbt.NbtCompound expected, String what) {
        dev.sculptory.core.nbt.NbtCompound now = placed(live(h, id, what));
        check(EntityNbt.nearlyEqual(now, expected), what + ": " + EntitiesGameTest.changedSince(java.util.Map.of(id, expected),
                java.util.Map.of(id, now)));
    }

    private static void clear(Harness h, Box area) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int air = h.state("minecraft:air");
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) writer.write(x, y, z, air, null);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ blocks

    /**
     * Three changes, each one history step: a stair's shape (next to another stair that would reshape it with block
     * updates), a chest's facing (its items stay) and a fence's connection towards nothing. None is undone by block
     * updates a few ticks later; three undos give the area back exactly and three redos the changes.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_tinker_blocks", tickLimit = LIMIT)
    public void propertyChangesAreExactStepsWithoutBlockUpdates(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1020);
        int x0 = at[0] + 4, z0 = at[1] + 4;
        Box area = box(x0, FLOOR, z0, x0 + 7, FLOOR + 2, z0 + 3);
        loadAndForce(h.world, area);
        clear(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        for (int x = x0; x <= x0 + 7; x++) {
            for (int z = z0; z <= z0 + 3; z++) writer.write(x, FLOOR, z, h.state("minecraft:stone"), null);
        }
        writer.write(x0 + 1, FLOOR + 1, z0 + 1, h.state("minecraft:oak_stairs[facing=north,shape=straight]"), null);
        writer.write(x0 + 2, FLOOR + 1, z0 + 1, h.state("minecraft:oak_stairs[facing=east]"), null);
        writer.write(x0 + 4, FLOOR + 1, z0 + 1, h.state("minecraft:chest[facing=north]"), null);
        ((Inventory) h.world.getBlockEntity(pos(x0 + 4, FLOOR + 1, z0 + 1))).setStack(3, new ItemStack(Items.DIAMOND, 7));
        writer.write(x0 + 6, FLOOR + 1, z0 + 1, h.state("minecraft:oak_fence"), null);
        writer.write(x0 + 6, FLOOR + 1, z0, h.state("minecraft:stone"), null);
        WorldSnapshot[] snapshots = new WorldSnapshot[2];
        new Script(h, h.player, context)
                .then(() -> {
                    snapshots[0] = capture(h.world, area);
                    tinker(h, h.player, x0 + 1, FLOOR + 1, z0 + 1, "minecraft:oak_stairs[facing=north,shape=straight]",
                            "minecraft:oak_stairs[facing=north,shape=outer_left]", null);
                    checkLatestLabel(h, h.player, "Tinker · Oak Stairs · shape: outer left");
                    tinker(h, h.player, x0 + 4, FLOOR + 1, z0 + 1, "minecraft:chest[facing=north]",
                            "minecraft:chest[facing=east]", null);
                    tinker(h, h.player, x0 + 6, FLOOR + 1, z0 + 1, "minecraft:oak_fence",
                            "minecraft:oak_fence[east=true]", null);
                    check(entries(h, h.player).size() == 3, "three steps, got " + entries(h, h.player).size());
                    ItemStack kept = ((Inventory) h.world.getBlockEntity(pos(x0 + 4, FLOOR + 1, z0 + 1))).getStack(3);
                    check(kept.isOf(Items.DIAMOND) && kept.getCount() == 7, "the chest keeps its diamonds: " + kept);
                })
                .then(() -> {
                    // A few ticks later: no block update came to put back what vanilla would have chosen.
                    checkState(h, x0 + 1, FLOOR + 1, z0 + 1, "minecraft:oak_stairs[facing=north,shape=outer_left]", "stairs");
                    checkState(h, x0 + 6, FLOOR + 1, z0 + 1, "minecraft:oak_fence[east=true]", "fence");
                    checkState(h, x0 + 4, FLOOR + 1, z0 + 1, "minecraft:chest[facing=east]", "chest");
                    snapshots[1] = capture(h.world, area);
                })
                .undo().undo().undo()
                .then(() -> checkSame(snapshots[0], capture(h.world, area), "after three undos"))
                .redo().redo().redo()
                .done(() -> {
                    checkSame(snapshots[1], capture(h.world, area), "after three redos");
                    h.close();
                });
    }

    /**
     * A door's open state set on its lower half opens the upper half in the same step, undone as one; Apply to all like
     * it in the selection (a Fill with the property pattern) sets the shape of the oak stairs only, facings kept,
     * labelled after the change, and undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_tinker_blocks", tickLimit = LIMIT)
    public void aDoorsOtherHalfFollowsAndApplyToAllChangesOnlyItsBlock(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1021);
        int x0 = at[0] + 4, z0 = at[1] + 4;
        Box area = box(x0, FLOOR, z0, x0 + 7, FLOOR + 3, z0 + 3);
        loadAndForce(h.world, area);
        clear(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        writer.write(x0, FLOOR + 1, z0, h.state("minecraft:oak_door[facing=north,half=lower,open=false]"), null);
        writer.write(x0, FLOOR + 2, z0, h.state("minecraft:oak_door[facing=north,half=upper,open=false]"), null);
        String[] row = {"minecraft:oak_stairs[facing=north]", "minecraft:oak_stairs[facing=east,half=top]",
                "minecraft:stone_brick_stairs[facing=south]", "minecraft:oak_stairs[facing=west,waterlogged=true]",
                "minecraft:stone"};
        for (int i = 0; i < row.length; i++) writer.write(x0 + 2 + i, FLOOR + 1, z0 + 2, h.state(row[i]), null);
        Box rowBox = box(x0 + 2, FLOOR + 1, z0 + 2, x0 + 6, FLOOR + 1, z0 + 2);
        WorldSnapshot[] snapshots = new WorldSnapshot[3];
        RecordingListener fill = new RecordingListener();
        new Script(h, h.player, context)
                .then(() -> {
                    snapshots[0] = capture(h.world, area);
                    tinker(h, h.player, x0, FLOOR + 1, z0, "minecraft:oak_door[facing=north,half=lower,open=false]",
                            "minecraft:oak_door[facing=north,half=lower,open=true]", null);
                    checkState(h, x0, FLOOR + 2, z0, "minecraft:oak_door[facing=north,half=upper,open=true]", "upper half");
                    checkLatestLabel(h, h.player, "Tinker · Oak Door · open: true");
                    check(entries(h, h.player).get(0).record().before().cellCount() == 2, "both halves in one step");
                    snapshots[1] = capture(h.world, area);
                    try {
                        h.service.run(h.player, new OpSpec.Fill(rowBox, new Pattern.SetProperty(
                                h.state("minecraft:oak_stairs[shape=outer_right]"), "shape"), CellMask.ANY),
                                RunOptions.DEFAULT, fill);
                    } catch (EditRejected e) {
                        throw new GameTestException("apply to all refused: " + e.getMessage());
                    }
                })
                .waitFor(() -> check(fill.result != null, "the fill finished"))
                .then(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED && fill.result.changed() == 3,
                            "the fill " + fill.result);
                    checkState(h, x0 + 2, FLOOR + 1, z0 + 2, "minecraft:oak_stairs[facing=north,shape=outer_right]", "1");
                    checkState(h, x0 + 3, FLOOR + 1, z0 + 2,
                            "minecraft:oak_stairs[facing=east,half=top,shape=outer_right]", "2");
                    checkState(h, x0 + 4, FLOOR + 1, z0 + 2, "minecraft:stone_brick_stairs[facing=south]", "another block");
                    checkState(h, x0 + 5, FLOOR + 1, z0 + 2,
                            "minecraft:oak_stairs[facing=west,shape=outer_right,waterlogged=true]", "4");
                    checkState(h, x0 + 6, FLOOR + 1, z0 + 2, "minecraft:stone", "stone");
                    checkLatestLabel(h, h.player, "Tinker · shape: outer right · 3 blocks");
                    snapshots[2] = capture(h.world, area);
                })
                .undo()
                .then(() -> checkSame(snapshots[1], capture(h.world, area), "the fill undone"))
                .undo()
                .then(() -> checkSame(snapshots[0], capture(h.world, area), "the door undone, both halves"))
                .redo().redo()
                .done(() -> {
                    checkSame(snapshots[2], capture(h.world, area), "both redone");
                    h.close();
                });
    }

    /**
     * Apply to all over a row that crosses a protected column (slot 1028): the protected cells are skipped and counted,
     * the rest change as one step, and undo gives the row back exactly with the protection still on.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_tinker_blocks", tickLimit = LIMIT)
    public void applyToAllSkipsProtectedCellsAndUndoesTheRestExactly(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1028);
        int x0 = at[0] + 4, z0 = at[1] + 4;
        Box area = box(x0, FLOOR, z0, x0 + 7, FLOOR + 2, z0 + 3);
        loadAndForce(h.world, area);
        clear(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        String stairs = "minecraft:oak_stairs[facing=north]";
        String outer = "minecraft:oak_stairs[facing=north,shape=outer_right]";
        for (int i = 0; i < 5; i++) writer.write(x0 + 2 + i, FLOOR + 1, z0 + 2, h.state(stairs), null);
        Box rowBox = box(x0 + 2, FLOOR + 1, z0 + 2, x0 + 6, FLOOR + 1, z0 + 2);
        ProtectionHook.protect(h.player, h.world, x0 + 5, z0, x0 + 6, z0 + 3);
        check(!h.world.canPlayerModifyAt(h.player, pos(x0 + 5, FLOOR + 1, z0 + 2)), "the last two columns are protected");
        check(h.world.canPlayerModifyAt(h.player, pos(x0 + 4, FLOOR + 1, z0 + 2)), "the first three are not");
        WorldSnapshot[] snapshots = new WorldSnapshot[2];
        RecordingListener fill = new RecordingListener();
        new Script(h, h.player, context)
                .then(() -> {
                    snapshots[0] = capture(h.world, area);
                    try {
                        h.service.run(h.player, new OpSpec.Fill(rowBox, new Pattern.SetProperty(h.state(outer), "shape"),
                                CellMask.ANY), RunOptions.DEFAULT, fill);
                    } catch (EditRejected e) {
                        throw new GameTestException("apply to all refused: " + e.getMessage());
                    }
                })
                .waitFor(() -> check(fill.result != null, "the fill finished"))
                .then(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED && fill.result.changed() == 3
                            && fill.result.skippedProtected() == 2, "the fill " + fill.result);
                    for (int i = 0; i < 3; i++) checkState(h, x0 + 2 + i, FLOOR + 1, z0 + 2, outer, "changed " + i);
                    for (int i = 3; i < 5; i++) checkState(h, x0 + 2 + i, FLOOR + 1, z0 + 2, stairs, "protected " + i);
                    checkLatestLabel(h, h.player, "Tinker · shape: outer right · 3 blocks");
                    snapshots[1] = capture(h.world, area);
                })
                .undo()
                .then(() -> checkSame(snapshots[0], capture(h.world, area), "undone exactly, protection still on"))
                .redo()
                .done(() -> {
                    checkSame(snapshots[1], capture(h.world, area), "redone");
                    ProtectionHook.clear(h.player);
                    h.close();
                });
    }

    /**
     * Sign text from the client becomes literal text: a formatting code in a line is dropped, colour and glow are set.
     * For a player without operator rights the sign is sanitized as signs from files are (the click event on its back
     * goes, its text stays); an operator keeps the side they left. Undo gives back the sign exactly, click event
     * included.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_tinker_blocks", tickLimit = LIMIT)
    public void signTextIsPlainAndSanitized(TestContext context) {
        Harness h = new Harness(context);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        int[] at = regionCorner(context, 1022);
        int x0 = at[0] + 4, z0 = at[1] + 4;
        Box area = box(x0, FLOOR, z0, x0 + 3, FLOOR + 2, z0 + 3);
        loadAndForce(h.world, area);
        clear(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        String sign = "minecraft:oak_sign[rotation=0]";
        Supplier<SignBlockEntity> live = () -> (SignBlockEntity) h.world.getBlockEntity(pos(x0 + 1, FLOOR + 1, z0 + 1));
        writer.write(x0 + 1, FLOOR + 1, z0 + 1, h.state(sign), null);
        live.get().setText(new net.minecraft.block.entity.SignText().withMessage(0, Text.literal("Hi")), true);
        live.get().setText(new net.minecraft.block.entity.SignText().withMessage(0, Text.literal("Click")
                .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/say pwned")))), false);
        SignText.Side back = new SignText.Side(List.of("Click", "", "", ""), "black", false);
        SignText text = new SignText(new SignText.Side(List.of("§cRed", "Two", "", ""), "red", true), back);
        WorldSnapshot[] snapshots = new WorldSnapshot[2];
        new Script(h, builder, context)
                .then(() -> {
                    snapshots[0] = capture(h.world, area);
                    tinker(h, builder, x0 + 1, FLOOR + 1, z0 + 1, sign, sign, text);
                    checkLatestLabel(h, builder, "Tinker · Oak Sign · text");
                    net.minecraft.block.entity.SignText front = live.get().getFrontText();
                    check(front.getMessage(0, false).getString().equals("Red"), "the code is dropped: "
                            + front.getMessage(0, false));
                    check(front.getMessage(0, false).getStyle().getColor() == null, "no colour from the code");
                    check(front.getMessage(1, false).getString().equals("Two"), "line two");
                    check(front.getColor() == DyeColor.RED && front.isGlowing(), "colour and glow");
                    net.minecraft.block.entity.SignText backText = live.get().getBackText();
                    check(backText.getMessage(0, false).getString().equals("Click"), "the back keeps its text");
                    check(backText.getMessage(0, false).getStyle().getClickEvent() == null,
                            "the back's click event is gone for a player without operator rights");
                    snapshots[1] = capture(h.world, area);
                })
                .undo()
                .then(() -> {
                    checkSame(snapshots[0], capture(h.world, area), "undone");
                    check(live.get().getBackText().getMessage(0, false).getStyle().getClickEvent() != null,
                            "the undo puts the click event back");
                })
                .redo()
                .then(() -> {
                    checkSame(snapshots[1], capture(h.world, area), "redone");
                    tinker(h, builder, x0 + 1, FLOOR + 1, z0 + 1, sign, sign, text); // reads so already
                    check(entries(h, builder).size() == 1, "the same text again is no step");
                })
                .undo()
                .done(() -> {
                    checkSame(snapshots[0], capture(h.world, area), "undone again");
                    // An operator (op level 2) keeps the back side's click event.
                    tinker(h, h.player, x0 + 1, FLOOR + 1, z0 + 1, sign, sign,
                            text.withSide(true, new SignText.Side(List.of("Op", "", "", ""), "black", false)));
                    check(live.get().getFrontText().getMessage(0, false).getString().equals("Op"), "the op's text");
                    check(live.get().getBackText().getMessage(0, false).getStyle().getClickEvent() != null,
                            "an operator keeps the side they left");
                    h.close();
                });
    }

    /**
     * Nothing is written, and no step pushed, when Tinker is refused: without {@code region}, in a protected column, in
     * a chunk that is not loaded, when the block is no longer what the client saw (a stale state), when the target is
     * another block, when a running edit holds the area, for an entity removed meanwhile or of a kind Tinker does not
     * change, an entity in a protected column, an edit that does not fit its kind, and a painting that would not fit
     * its wall (the painting stays, with its UUID).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_tinker_refusals", tickLimit = LIMIT)
    public void refusalsWriteNothing(TestContext context) {
        Harness h = new Harness(context);
        ServerPlayerEntity stranger = h.addPlayer(false);
        EditTestSupport.grant(stranger, Perm.USE);
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        ServerPlayerEntity noUse = h.addPlayer(false);
        EditTestSupport.grant(noUse, Perm.REGION);
        int[] at = regionCorner(context, 1023);
        int x0 = at[0] + 4, z0 = at[1] + 4;
        Box area = box(x0, FLOOR, z0, x0 + 7, FLOOR + 4, z0 + 7);
        loadAndForce(h.world, area);
        clear(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        String stairs = "minecraft:oak_stairs[facing=north]";
        String outer = "minecraft:oak_stairs[facing=north,shape=outer_left]";
        writer.write(x0 + 1, FLOOR + 1, z0 + 1, h.state(stairs), null);
        for (int x = x0 + 2; x <= x0 + 5; x++) {
            for (int y = FLOOR + 1; y <= FLOOR + 3; y++) writer.write(x, y, z0 + 5, h.state("minecraft:stone"), null);
        }
        List<Entity> made = new ArrayList<>();
        new Script(h, builder, context)
                .waitFor(() -> EntitiesGameTest.ready(h.world, area))
                .then(() -> {
                    PaintingEntity painting = new PaintingEntity(h.world, pos(x0 + 3, FLOOR + 2, z0 + 6), Direction.SOUTH,
                            h.world.getRegistryManager().get(RegistryKeys.PAINTING_VARIANT).entryOf(PaintingVariants.POOL));
                    ArmorStandEntity stand = new ArmorStandEntity(h.world, x0 + 1.5, FLOOR + 1, z0 + 3.5);
                    Entity pig = EntitiesGameTest.load(h.world, "minecraft:pig", x0 + 6.5, FLOOR + 1, z0 + 2.5, 0f, n -> { });
                    for (Entity e : List.of(painting, stand, pig)) {
                        check(h.world.spawnEntity(e), "fixture " + e);
                        made.add(e);
                    }
                })
                .then(() -> {
                    WorldSnapshot before = capture(h.world, area);
                    Entity painting = made.get(0), stand = made.get(1), pig = made.get(2);
                    dev.sculptory.core.nbt.NbtCompound paintingBefore = placed(painting);
                    BlockPos cell = new BlockPos(x0 + 1, FLOOR + 1, z0 + 1);
                    int stairsState = h.state(stairs), outerState = h.state(outer);
                    check(refusal(() -> h.service.block(stranger, cell, stairsState, outerState, null))
                            == RejectReason.NO_PERMISSION, "without region");
                    check(refusal(() -> h.service.block(noUse, cell, stairsState, outerState, null))
                            == RejectReason.NO_PERMISSION, "without use");
                    check(refusal(() -> h.service.entity(noUse, stand.getUuid(), List.of(new EntityEdit.Yaw(90))))
                            == RejectReason.NO_PERMISSION, "an entity without use");
                    h.runtime.config().editingEnabled = false;
                    try {
                        check(refusal(() -> h.service.block(builder, cell, stairsState, outerState, null))
                                == RejectReason.DISABLED, "editing off");
                        check(refusal(() -> h.service.entity(builder, stand.getUuid(), List.of(new EntityEdit.Yaw(90))))
                                == RejectReason.DISABLED, "an entity with editing off");
                    } finally {
                        h.runtime.config().editingEnabled = true;
                    }
                    check(refusal(() -> h.service.block(builder, cell, h.state("minecraft:oak_stairs[facing=east]"),
                            h.state("minecraft:oak_stairs[facing=east,shape=outer_left]"), null)) == RejectReason.INVALID,
                            "a stale state");
                    check(refusal(() -> h.service.block(builder, cell, stairsState, h.state("minecraft:stone"), null))
                            == RejectReason.INVALID, "another block");
                    check(refusal(() -> h.service.block(builder, new BlockPos(x0 + 40_000, FLOOR, z0), stairsState,
                            outerState, null)) == RejectReason.UNLOADED, "a chunk that is not loaded");
                    check(refusal(() -> h.service.block(builder, cell, stairsState, stairsState,
                            SignText.EMPTY)) == RejectReason.INVALID, "sign text for a block without a sign");
                    check(refusal(() -> h.service.entity(builder, UUID.randomUUID(), List.of(new EntityEdit.Yaw(90))))
                            == RejectReason.INVALID, "no such entity");
                    check(refusal(() -> h.service.entity(builder, pig.getUuid(), List.of(new EntityEdit.Yaw(90))))
                            == RejectReason.INVALID, "a pig is not Tinker's");
                    check(refusal(() -> h.service.entity(builder, stand.getUuid(), List.of(new EntityEdit.ItemRotation(2))))
                            == RejectReason.INVALID, "an edit the kind does not take");
                    check(refusal(() -> h.service.entity(builder, painting.getUuid(),
                            List.of(new EntityEdit.PaintingVariant("minecraft:pointer")))) == RejectReason.INVALID,
                            "a painting too large for its wall");
                    check(refusal(() -> h.service.entity(builder, painting.getUuid(),
                            List.of(new EntityEdit.PaintingVariant("minecraft:no_such_painting")))) == RejectReason.INVALID,
                            "an unknown painting");
                    checkPlaced(h, painting.getUuid(), paintingBefore, "the painting after its refusals");
                    ProtectionHook.protect(builder, h.world, x0, z0, x0 + 7, z0 + 7);
                    try {
                        check(refusal(() -> h.service.block(builder, cell, stairsState, outerState, null))
                                == RejectReason.PROTECTED, "a protected column");
                        check(refusal(() -> h.service.entity(builder, stand.getUuid(), List.of(new EntityEdit.Yaw(45))))
                                == RejectReason.PROTECTED, "a protected entity");
                    } finally {
                        ProtectionHook.clear(builder);
                    }
                    // An entity removed meanwhile.
                    UUID gone = stand.getUuid();
                    stand.discard();
                    check(refusal(() -> h.service.entity(builder, gone, List.of(new EntityEdit.Yaw(45))))
                            == RejectReason.INVALID, "an entity removed meanwhile");
                    checkSame(before, capture(h.world, area), "nothing written");
                    check(entries(h, builder).isEmpty() && entries(h, stranger).isEmpty() && entries(h, noUse).isEmpty(),
                            "no step pushed");
                    for (Entity e : made) e.discard();
                })
                .done(h::close);
    }

    /**
     * A running edit holds the section: Tinker is refused {@code AREA_BUSY} until it is done, then goes ahead; so is an
     * entity standing in it, and a move into a section another edit holds (the stand's own section being free).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_tinker_busy", tickLimit = LIMIT)
    public void aBusyAreaIsRefused(TestContext context) {
        dev.sculptory.fabric.engine.impl.EditExecutor executor = new dev.sculptory.fabric.engine.impl.EditExecutor(
                context.getWorld().getServer(), EngineTestSupport.runtime(context).states(),
                new dev.sculptory.fabric.engine.impl.EditExecutor.Settings(200_000_000L, 4096, 0.4, 2, 8, 32, 64,
                        dev.sculptory.server.config.UnloadedPolicy.LOAD, 1024, 16_384));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 1024);
        int x0 = at[0] + 4, z0 = at[1] + 4;
        Box area = box(x0, FLOOR, z0, x0 + 3, FLOOR + 1, z0 + 3);
        loadAndForce(h.world, area);
        clear(h, area);
        String stairs = "minecraft:oak_stairs[facing=north]";
        h.runtime.writer(h.world, WriteOptions.DEFAULT).write(x0 + 1, FLOOR + 1, z0 + 1, h.state(stairs), null);
        ArmorStandEntity stand = new ArmorStandEntity(h.world, x0 + 1.5, FLOOR + 1, z0 + 2.5);
        check(h.world.spawnEntity(stand), "fixture stand");
        RecordingListener fill = new RecordingListener();
        h.fill(box(x0 + 2, FLOOR, z0, x0 + 3, FLOOR, z0 + 3), "minecraft:stone", fill);
        BlockPos cell = new BlockPos(x0 + 1, FLOOR + 1, z0 + 1);
        int from = h.state(stairs), to = h.state("minecraft:oak_stairs[facing=north,shape=inner_left]");
        check(refusal(() -> h.service.block(h.player, cell, from, to, null)) == RejectReason.AREA_BUSY,
                "the fill (not yet run) holds the section");
        checkState(h, x0 + 1, FLOOR + 1, z0 + 1, stairs, "unchanged");
        // The section above (y 112-127): a second edit holds it while the stand's own section is free.
        Box above = box(x0 + 2, FLOOR + 13, z0, x0 + 3, FLOOR + 13, z0 + 3);
        RecordingListener aboveFill = new RecordingListener();
        String innerLeft = "minecraft:oak_stairs[facing=north,shape=inner_left]";
        boolean[] aboveStarted = {false};
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    dev.sculptory.core.nbt.NbtCompound standBefore = placed(live(h, stand.getUuid(), "the stand"));
                    check(refusal(() -> h.service.entity(h.player, stand.getUuid(), List.of(new EntityEdit.Yaw(90))))
                            == RejectReason.AREA_BUSY, "the fill (not yet run) holds the stand's section");
                    checkPlaced(h, stand.getUuid(), standBefore, "the stand after the refusal");
                })
                .createAndAdd(() -> {
                    executor.tick();
                    check(fill.result != null, "the fill finished");
                })
                // One action per step: a step that fails is retried on the next tick, and a retried Tinker of a block
                // it already changed would be refused as "changed meanwhile", hiding the check that failed first.
                .createAndAdd(() -> tinker(h, h.player, x0 + 1, FLOOR + 1, z0 + 1, stairs, innerLeft, null))
                .createAndAdd(() -> edit(h, h.player, stand.getUuid(), new EntityEdit.Yaw(90)))
                .createAndAdd(() -> {
                    if (!aboveStarted[0]) h.fill(above, "minecraft:stone", aboveFill);
                    aboveStarted[0] = true;
                })
                .createAndAdd(() -> {
                    check(refusal(() -> h.service.entity(h.player, stand.getUuid(), List.of(new EntityEdit.Position(
                            x0 + 1.5, FLOOR + 13, z0 + 2.5)))) == RejectReason.AREA_BUSY, "a move into a held section");
                    check(live(h, stand.getUuid(), "the stand").getY() < FLOOR + 2, "the stand did not move");
                })
                .createAndAdd(() -> edit(h, h.player, stand.getUuid(), new EntityEdit.Yaw(45))) // its own section is free
                .createAndAdd(() -> {
                    executor.tick();
                    check(aboveFill.result != null, "the second fill finished");
                    edit(h, h.player, stand.getUuid(), new EntityEdit.Position(x0 + 1.5, FLOOR + 13, z0 + 2.5));
                    check(live(h, stand.getUuid(), "the moved stand").getY() > FLOOR + 12, "moved once the section is free");
                    live(h, stand.getUuid(), "the moved stand").discard();
                    executor.shutdown();
                    h.close();
                })
                .completeIfSuccessful();
    }

    // ------------------------------------------------------------------------------------------------ entities

    /**
     * An armor stand's pose and flags, a move by a sixteenth and a turn of 15°, each one step: three undos give back the
     * stand exactly where and as it was (with its UUID), three redos the edited one.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_tinker_entities", tickLimit = LIMIT)
    public void armorStandEditsUndoAndRedoExactly(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1025);
        int x0 = at[0] + 4, z0 = at[1] + 4;
        Box area = box(x0, FLOOR, z0, x0 + 3, FLOOR + 3, z0 + 3);
        loadAndForce(h.world, area);
        clear(h, area);
        UUID[] id = new UUID[1];
        dev.sculptory.core.nbt.NbtCompound[] states = new dev.sculptory.core.nbt.NbtCompound[2];
        new Script(h, h.player, context)
                .waitFor(() -> EntitiesGameTest.ready(h.world, area))
                .then(() -> {
                    ArmorStandEntity stand = new ArmorStandEntity(h.world, x0 + 1.5, FLOOR + 1, z0 + 1.5);
                    stand.setYaw(30f);
                    stand.setNoGravity(true);
                    stand.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
                    check(h.world.spawnEntity(stand), "the stand spawned");
                    id[0] = stand.getUuid();
                })
                .then(() -> {
                    states[0] = placed(live(h, id[0], "before"));
                    EntityView view = edit(h, h.player, id[0]);
                    check(view.yaw() == 30f && view.flag(EntityEdit.Flag.NO_GRAVITY), "the view " + view);
                    view = edit(h, h.player, id[0], new EntityEdit.Pose(EntityEdit.Part.RIGHT_ARM, -90, 10, 0),
                            new EntityEdit.Toggle(EntityEdit.Flag.SMALL, true),
                            new EntityEdit.Toggle(EntityEdit.Flag.SHOW_ARMS, true));
                    ArmorStandEntity stand = (ArmorStandEntity) live(h, id[0], "posed");
                    check(stand.isSmall() && stand.shouldShowArms(), "small with arms");
                    check(stand.getRightArmRotation().getPitch() == -90f && stand.getRightArmRotation().getYaw() == 10f,
                            "the right arm " + stand.getRightArmRotation());
                    check(stand.getEquippedStack(EquipmentSlot.HEAD).isOf(Items.IRON_HELMET), "the helmet stays");
                    check(view.flag(EntityEdit.Flag.SMALL) && view.pose(EntityEdit.Part.RIGHT_ARM)[0] == -90f,
                            "the view after");
                    checkLatestLabel(h, h.player, "Tinker · Armor Stand · 3 settings");
                    edit(h, h.player, id[0], new EntityEdit.Position(x0 + 1.5625, FLOOR + 1, z0 + 1.5));
                    edit(h, h.player, id[0], new EntityEdit.Yaw(45));
                    Entity moved = live(h, id[0], "moved");
                    check(moved.getX() == x0 + 1.5625 && moved.getYaw() == 45f, "moved and turned: " + moved.getPos()
                            + " " + moved.getYaw());
                    check(entries(h, h.player).size() == 3, "three steps");
                    states[1] = placed(moved);
                })
                .undo().undo().undo()
                .then(() -> checkPlaced(h, id[0], states[0], "after three undos"))
                .redo().redo().redo()
                .done(() -> {
                    checkPlaced(h, id[0], states[1], "after three redos");
                    live(h, id[0], "done").discard();
                    h.close();
                });
    }

    /**
     * An item frame's item rotation, Fixed and Invisible, and a move up a block along its wall; a painting's picture; a
     * block display's transformation, billboard, light and block; an item display's item and a text display's text:
     * each set, then every step undone (each entity exactly as before) and redone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_tinker_entities", tickLimit = LIMIT)
    public void framesPaintingsAndDisplaysUndoAndRedoExactly(TestContext context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1026);
        int x0 = at[0] + 4, z0 = at[1] + 4;
        Box area = box(x0, FLOOR, z0, x0 + 7, FLOOR + 5, z0 + 5);
        loadAndForce(h.world, area);
        clear(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        for (int x = x0; x <= x0 + 7; x++) {
            for (int y = FLOOR; y <= FLOOR + 4; y++) writer.write(x, y, z0, h.state("minecraft:stone"), null);
        }
        List<UUID> ids = new ArrayList<>();
        List<dev.sculptory.core.nbt.NbtCompound> before = new ArrayList<>();
        List<dev.sculptory.core.nbt.NbtCompound> after = new ArrayList<>();
        int[] steps = {0};
        Script script = new Script(h, h.player, context).waitFor(() -> EntitiesGameTest.ready(h.world, area));
        script.then(() -> {
            ItemFrameEntity frame = new ItemFrameEntity(h.world, pos(x0 + 1, FLOOR + 1, z0 + 1), Direction.SOUTH);
            frame.setHeldItemStack(new ItemStack(Items.DIAMOND), false);
            PaintingEntity painting = new PaintingEntity(h.world, pos(x0 + 4, FLOOR + 1, z0 + 1), Direction.SOUTH,
                    h.world.getRegistryManager().get(RegistryKeys.PAINTING_VARIANT).entryOf(PaintingVariants.POOL));
            Entity block = EntitiesGameTest.load(h.world, "minecraft:block_display", x0 + 1.5, FLOOR + 1, z0 + 3.5, 0f,
                    n -> n.put("block_state", EntitiesGameTest.compound("Name", "minecraft:stone")));
            Entity item = EntitiesGameTest.load(h.world, "minecraft:item_display", x0 + 3.5, FLOOR + 1, z0 + 3.5, 0f,
                    n -> { });
            Entity text = EntitiesGameTest.load(h.world, "minecraft:text_display", x0 + 5.5, FLOOR + 1, z0 + 3.5, 0f,
                    n -> n.putString("text", "{\"text\":\"old\"}"));
            for (Entity e : List.of(frame, painting, block, item, text)) {
                check(h.world.spawnEntity(e), "fixture " + e);
                ids.add(e.getUuid());
            }
        }).then(() -> {
            for (UUID id : ids) before.add(placed(live(h, id, "fixture")));
            EntityView frame = edit(h, h.player, ids.get(0), new EntityEdit.ItemRotation(3),
                    new EntityEdit.Toggle(EntityEdit.Flag.FIXED, true), new EntityEdit.Toggle(EntityEdit.Flag.INVISIBLE, true));
            check(frame.itemRotation() == 3 && frame.flag(EntityEdit.Flag.FIXED) && frame.item().equals("minecraft:diamond"),
                    "the frame's view " + frame);
            ItemFrameEntity liveFrame = (ItemFrameEntity) live(h, ids.get(0), "frame");
            check(liveFrame.getRotation() == 3 && liveFrame.isInvisible(), "the frame turned its item and hides");
            edit(h, h.player, ids.get(0), new EntityEdit.Position(liveFrame.getX(), liveFrame.getY() + 1, liveFrame.getZ()));
            check(((ItemFrameEntity) live(h, ids.get(0), "frame moved")).getAttachedBlockPos()
                    .equals(pos(x0 + 1, FLOOR + 2, z0 + 1)), "the frame hangs a block higher");
            EntityView painting = edit(h, h.player, ids.get(1), new EntityEdit.PaintingVariant("minecraft:courbet"));
            check(painting.variant().equals("minecraft:courbet"), "the picture " + painting.variant());
            EntityView block = edit(h, h.player, ids.get(2),
                    new EntityEdit.Transformation(new float[] {0, 0.5f, 0}, DisplayRotation.fromEuler(45, 0, 0),
                            new float[] {0.5f, 0.5f, 0.5f}),
                    new EntityEdit.BillboardMode(EntityEdit.Billboard.VERTICAL), new EntityEdit.Brightness(15, 15),
                    new EntityEdit.DisplayBlock(h.state("minecraft:oak_stairs[facing=east]")));
            check(block.blockState().equals("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]"),
                    "the block display shows " + block.blockState());
            check(block.billboard() == EntityEdit.Billboard.VERTICAL && block.brightness().block() == 15,
                    "billboard and light");
            check(Math.abs(DisplayRotation.toEuler(block.rotation())[0] - 45) < 0.01, "turned 45°");
            EntityView item = edit(h, h.player, ids.get(3), new EntityEdit.DisplayItem("minecraft:diamond_sword"));
            check(item.item().equals("minecraft:diamond_sword"), "the item display shows " + item.item());
            EntityView text = edit(h, h.player, ids.get(4), new EntityEdit.DisplayText("Hello\nthere"),
                    new EntityEdit.Yaw(90));
            check(text.text().equals("Hello\nthere") && text.yaw() == 90f, "the text display " + text.text());
            steps[0] = entries(h, h.player).size();
            check(steps[0] == 6, "six steps, got " + steps[0]);
            for (UUID id : ids) after.add(placed(live(h, id, "edited")));
        });
        for (int i = 0; i < 6; i++) script.undo();
        script.then(() -> {
            for (int i = 0; i < ids.size(); i++) checkPlaced(h, ids.get(i), before.get(i), "entity " + i + " undone");
        });
        for (int i = 0; i < 6; i++) script.redo();
        script.done(() -> {
            for (int i = 0; i < ids.size(); i++) checkPlaced(h, ids.get(i), after.get(i), "entity " + i + " redone");
            for (UUID id : ids) live(h, id, "done").discard();
            h.close();
        });
    }
}
