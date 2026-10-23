package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.mc.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EntityChange;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.region.Facing;
import dev.sculptory.mc.engine.impl.EditServiceHost;
import dev.sculptory.mc.gametest.DurableHistoryGameTest.Run;
import dev.sculptory.mc.gametest.DurableHistoryGameTest.Scene;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.mc.world.McEntities;
import dev.sculptory.protocol.v2.BuilderPower;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.EngineEditService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.Painting;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Item frames, paintings and armor stands in the undo history.
 *
 * <p><b>Builder mode records entity actions.</b> With a builder power on, hanging a frame or a painting, placing an
 * armor stand, putting an item into a frame, dressing a stand and breaking any of them are each one history step, made
 * through the game's own paths (the item's use on a block, the interact and attack packets) on the server's own edit
 * service, so the mixins are part of what is tested; each step undoes and redoes exactly, and no item is dropped on
 * the way. A placement where builder mode would refuse a block (a protected cell) places and records nothing.
 *
 * <p><b>An edit that takes a hanging entity's support takes the entity with it.</b> A Fill of air over a wall takes its
 * frame into the Fill's step, so undoing the Fill brings the frame back with its item; undoing the step that built a
 * wall takes the frames hanging on it into that step (it is amended), so the redo puts them back, also after a
 * restart and through Undo anyway; a frame whose wall was swapped for another solid block, or one whose wall is outside
 * the edit, is left alone. A redo that would put a frame where its wall has gone does not put it back. In every case no
 * item entity appears, even after the game's own check of the frames (every 100 ticks) had time to run.
 *
 * <p>GameTest regions 2700-2719.
 */
public final class HangingEntitiesGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int FLOOR = 100;
    /** More than the 100 ticks between a hanging entity's own checks that it still hangs. */
    private static final int GAME_CHECK_TICKS = 110;
    private static final int TICKS = 2000;

    // ============================================================================================ builder mode

    /**
     * A frame hung, filled, emptied by a punch and broken by another, each one step through the game's own paths; undone
     * one by one back to the bare wall and redone back to nothing on it, exactly, with no item dropped.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_builder_frame", timeoutTicks = LIMIT)
    public void builderModeFrameStepsUndoAndRedoExactly(GameTestHelper context) {
        Builder b = new Builder(context, 2700);
        BlockPos wall = b.wall(6, 3);
        b.stand(6, 5);
        UUID[] id = {null};
        List<String> states = new ArrayList<>();
        var actions = b.sequence().thenExecute(() -> {
            b.hold(Items.ITEM_FRAME);
            b.useOn(wall, Direction.SOUTH);
            ItemFrame frame = b.only(ItemFrame.class);
            id[0] = frame.getUUID();
            b.expectLabels("Place · item frame");
            states.add(b.describe());
            b.hold(Items.DIAMOND);
            b.interact(frame);
            check(frame.getItem().is(Items.DIAMOND), "the diamond is in the frame");
            states.add(b.describe());
            b.hold(Items.AIR);
            b.attack(frame);
            check(frame.getItem().isEmpty() && !frame.isRemoved(), "the punch took the diamond out");
            states.add(b.describe());
            b.attack(frame);
            check(frame.isRemoved(), "the second punch broke the frame");
            states.add(b.describe());
            b.expectLabels("Break · item frame", "Change · item frame", "Change · item frame", "Place · item frame");
            b.noItems("after the actions");
        });
        b.undoRedo(actions, states, 4);
    }

    /** A painting hung and broken: two steps, undone and redone exactly. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_builder_painting", timeoutTicks = LIMIT)
    public void builderModePaintingStepsUndoAndRedoExactly(GameTestHelper context) {
        Builder b = new Builder(context, 2701);
        BlockPos wall = b.wall(6, 4);
        b.stand(6, 5);
        List<String> states = new ArrayList<>();
        var actions = b.sequence().thenExecute(() -> {
            b.hold(Items.PAINTING);
            b.useOn(wall.above(), Direction.SOUTH);
            Painting painting = b.only(Painting.class);
            b.expectLabels("Place · painting");
            states.add(b.describe());
            b.hold(Items.AIR);
            b.attack(painting);
            check(painting.isRemoved(), "the punch broke the painting");
            states.add(b.describe());
            b.expectLabels("Break · painting", "Place · painting");
        });
        b.undoRedo(actions, states, 2);
    }

    /** An armor stand placed, given a helmet and broken in Creative: three steps, undone and redone exactly. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_builder_stand", timeoutTicks = LIMIT)
    public void builderModeArmorStandStepsUndoAndRedoExactly(GameTestHelper context) {
        Builder b = new Builder(context, 2702);
        b.stand(6, 5);
        BlockPos floor = new BlockPos(b.x + 6, FLOOR, b.z + 3);
        List<String> states = new ArrayList<>();
        var actions = b.sequence().thenExecute(() -> {
            b.hold(Items.ARMOR_STAND);
            b.useOn(floor, Direction.UP);
            ArmorStand stand = b.only(ArmorStand.class);
            b.expectLabels("Place · armor stand");
            states.add(b.describe());
            b.hold(Items.IRON_HELMET);
            b.interactAt(stand, new Vec3(0, 1.8, 0));
            check(stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "the stand wears the helmet");
            states.add(b.describe());
            b.hold(Items.AIR);
            b.attack(stand);
            check(stand.isRemoved(), "a Creative punch broke the stand");
            states.add(b.describe());
            b.expectLabels("Break · armor stand", "Change · armor stand", "Place · armor stand");
            b.noItems("after the actions");
        });
        b.undoRedo(actions, states, 3);
    }

    /** A frame aimed at a protected cell is refused: nothing is placed and nothing recorded. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_builder_refused", timeoutTicks = LIMIT)
    public void aRefusedPlacementPlacesAndRecordsNothing(GameTestHelper context) {
        Builder b = new Builder(context, 2703);
        BlockPos wall = b.wall(6, 3);
        b.stand(6, 5);
        b.sequence().thenExecute(() -> {
            ProtectionHook.protect(b.player, b.world, wall.getX() - 2, wall.getZ() - 2, wall.getX() + 2,
                    wall.getZ() + 2);
            try {
                b.hold(Items.ITEM_FRAME);
                b.useOn(wall, Direction.SOUTH);
                check(b.all(ItemFrame.class).isEmpty(), "no frame on a protected cell");
                b.expectLabels();
                b.hold(Items.ARMOR_STAND);
                b.useOn(new BlockPos(wall.getX(), FLOOR, wall.getZ() + 1), Direction.UP);
                check(b.all(ArmorStand.class).isEmpty(), "no armor stand on a protected cell");
                b.expectLabels();
            } finally {
                ProtectionHook.clear(b.player);
            }
            // The same placement outside the protection is recorded, so the refusal above was builder mode's.
            b.hold(Items.ITEM_FRAME);
            b.useOn(b.wall(14, 3), Direction.SOUTH);
            check(b.all(ItemFrame.class).size() == 1, "the frame beside the protection");
            b.expectLabels("Place · item frame");
            b.finish();
        }).thenSucceed();
    }

    /**
     * A frame placed (with an item) and undone; another player then fills its wall with air; the redo of the placement
     * does not put the frame back where it would hang on nothing (a conflict), and no item drops.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_builder_wall_gone", timeoutTicks = LIMIT)
    public void aRedoWhereTheWallIsGoneDoesNotHangTheFrame(GameTestHelper context) {
        Builder b = new Builder(context, 2704);
        BlockPos wall = b.wall(6, 3);
        b.stand(6, 5);
        RecordingListener[] job = new RecordingListener[3];
        b.sequence().thenExecute(() -> {
            b.hold(Items.ITEM_FRAME);
            b.useOn(wall, Direction.SOUTH);
            b.hold(Items.DIAMOND);
            b.interact(b.only(ItemFrame.class));
            b.expectLabels("Change · item frame", "Place · item frame");
            job[0] = b.step(true);
        }).thenWaitUntil(() -> check(job[0].result != null, "undo of the item")).thenExecute(() -> {
            job[1] = b.step(true);
        }).thenWaitUntil(() -> check(job[1].result != null, "undo of the frame")).thenExecute(() -> {
            check(b.all(ItemFrame.class).isEmpty(), "the frame is gone");
            // Someone else takes the wall away, with an edit of their own.
            Harness other = new Harness(context);
            RecordingListener fill = new RecordingListener();
            other.fill(other.player, Box.of(new dev.sculptory.core.BlockPos(wall.getX(), wall.getY(), wall.getZ())),
                    "minecraft:air", fill);
            b.cleanups.add(other::close);
            job[2] = fill;
        }).thenWaitUntil(() -> check(job[2].result != null, "the other player's fill")).thenExecute(() -> {
            check(b.world.getBlockState(wall).isAir(), "the wall is gone");
            job[0] = b.step(false);
        }).thenWaitUntil(() -> check(job[0].result != null, "redo of the frame")).thenExecute(() -> {
            check(job[0].result.skippedConflicts() >= 1, "the frame that would hang on nothing is a conflict: "
                    + job[0].result);
            check(b.all(ItemFrame.class).isEmpty(), "no frame hangs on nothing");
        }).thenExecuteAfter(GAME_CHECK_TICKS, () -> {
            b.noItems("after the game's own check");
            b.finish();
        }).thenSucceed();
    }

    // ============================================================================================ supports

    /**
     * A Fill of air over a wall takes its frame into the Fill's step; the undo brings the wall back, then the frame with
     * its item; a frame whose wall the Fill did not reach, and one whose wall was swapped for dirt by another Fill, stay
     * as they are; no item drops.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_fill", timeoutTicks = LIMIT)
    public void aFillOverAWallTakesItsFrameAndItsUndoBringsItBack(GameTestHelper context) {
        Sync s = new Sync(context, 2705);
        BlockPos wall = s.wall(6, 3);
        BlockPos farWall = s.wall(12, 3);
        BlockPos swapped = s.wall(18, 3);
        s.sequence().thenExecute(() -> {
            ItemFrame frame = s.hang(wall, Items.DIAMOND);
            ItemFrame far = s.hang(farWall, Items.EMERALD);
            ItemFrame kept = s.hang(swapped, Items.GOLD_INGOT);
            String farBefore = s.state(far);
            s.fill(cellBox(wall), "minecraft:air");
            HistoryEntry fill = s.entries().get(0);
            check(fill.label().endsWith("· 1 entity"), "the Fill took the frame: " + fill.label());
            check(fill.record().entities().equals(List.of(new EntityChange(frame.getUUID(),
                    fill.record().entities().get(0).before(), null))), "recorded as removed");
            check(s.find(frame.getUUID()) == null, "the frame went with its wall");
            check(s.state(far).equals(farBefore), "the frame on the wall the Fill did not reach is untouched");
            s.fill(cellBox(swapped), "minecraft:dirt");
            check(s.entries().get(0).record().entities().isEmpty(), "a frame on dirt still hangs: nothing recorded");
            check(s.find(kept.getUUID()) != null, "the frame on the swapped wall stays");
            s.undo(); // the dirt
            s.undo(); // the air
            ItemFrame back = (ItemFrame) s.find(frame.getUUID());
            check(back != null && back.getItem().is(Items.DIAMOND), "the frame is back with its diamond");
            check(s.world.getBlockState(wall).is(Blocks.STONE), "on its wall");
            s.noItems("after the undo");
        }).thenExecuteAfter(GAME_CHECK_TICKS, () -> {
            s.noItems("after the game's own check");
            check(s.frames().size() == 3, "the three frames still hang: " + s.frames());
            s.finish();
        }).thenSucceed();
    }

    /**
     * Two frames on a wall a Fill built: undoing the Fill takes them into the Fill's step (amended), with no item
     * dropped; the redo puts the wall back, then both frames with their items and UUIDs; undoing again takes them away
     * again.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_undo_wall", timeoutTicks = LIMIT)
    public void undoingTheWallTakesItsFramesAndTheRedoPutsThemBack(GameTestHelper context) {
        Sync s = new Sync(context, 2706);
        Box wallCells = box(s.x + 6, FLOOR + 1, s.z + 3, s.x + 7, FLOOR + 2, s.z + 3);
        s.sequence().thenExecute(() -> {
            s.fill(wallCells, "minecraft:stone");
            ItemFrame a = s.hang(new BlockPos(s.x + 6, FLOOR + 1, s.z + 3), Items.DIAMOND);
            ItemFrame c = s.hang(new BlockPos(s.x + 7, FLOOR + 2, s.z + 3), Items.APPLE);
            String stateA = s.state(a), stateC = s.state(c);
            s.undo();
            check(s.frames().isEmpty(), "the frames went with the wall");
            s.noItems("after the undo");
            HistoryEntry amended = s.h.service.historyService().redoEntries(s.h.player.getUUID()).get(0);
            check(amended.record().entities().size() == 2, "the Fill's step took both frames: "
                    + amended.record().entities());
            for (EntityChange change : amended.record().entities()) {
                check(change.before() == null && change.after() != null, "there after the Fill, not before: " + change);
            }
            s.redo();
            check(s.world.getBlockState(new BlockPos(s.x + 6, FLOOR + 1, s.z + 3)).is(Blocks.STONE), "the wall is back");
            check(stateA.equals(s.state(s.find(a.getUUID()))) && stateC.equals(s.state(s.find(c.getUUID()))),
                    "both frames are back as they were, with their UUIDs");
            s.undo();
            check(s.frames().isEmpty(), "undone again, they go again");
        }).thenExecuteAfter(GAME_CHECK_TICKS, () -> {
            s.noItems("after the game's own check");
            s.finish();
        }).thenSucceed();
    }

    /**
     * Builder mode: a frame on a block a builder placement put there; undoing the placement takes the frame into it,
     * the redo puts it back. Breaking that block in builder mode takes the frame into the break, whose undo brings it
     * back.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_builder_block", timeoutTicks = LIMIT)
    public void builderPlacementsAndBreaksTakeTheirFrames(GameTestHelper context) {
        Sync s = new Sync(context, 2707);
        BlockPos block = new BlockPos(s.x + 6, FLOOR + 1, s.z + 3);
        s.sequence().thenExecute(() -> {
            s.h.player.moveTo(s.x + 6.5, FLOOR + 1, s.z + 6.5, 180f, 0f);
            s.h.player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE, 64));
            BuilderOutcome placed = s.h.service.builderPlace(s.h.player, new C2S.BuilderPlace(1, false,
                    new dev.sculptory.core.BlockPos(block.getX(), FLOOR, block.getZ()), Facing.UP, 0.5f, 1f, 0.5f, 0,
                    Symmetry.NONE));
            check(placed.accepted() && s.world.getBlockState(block).is(Blocks.STONE), "placed: " + placed);
            ItemFrame frame = s.hang(block, Items.DIAMOND);
            String hung = s.state(frame);
            s.undo();
            check(s.frames().isEmpty() && s.world.getBlockState(block).isAir(), "the block and its frame went");
            s.redo();
            check(hung.equals(s.state(s.find(frame.getUUID()))), "the redo hangs the frame again");
            s.h.player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            BuilderOutcome broken = s.h.service.builderBreak(s.h.player, new C2S.BuilderBreak(2, 7,
                    List.of(new dev.sculptory.core.BlockPos(block.getX(), block.getY(), block.getZ())), 0, Symmetry.NONE,
                    false, true));
            check(broken.accepted() && broken.changed() == 1, "broken: " + broken);
            check(s.frames().isEmpty(), "the break took the frame");
            String label = s.entries().get(0).label();
            check(label.equals("Break · 1 block · 1 entity"), "label " + label);
            s.undo();
            check(hung.equals(s.state(s.find(frame.getUUID()))), "the undo of the break hangs the frame again");
            s.noItems("after the undo");
        }).thenExecuteAfter(GAME_CHECK_TICKS, () -> {
            s.noItems("after the game's own check");
            s.finish();
        }).thenSucceed();
    }

    /**
     * Undo anyway: an undo that kept the frame's wall (changed since) leaves the frame; Undo anyway then takes the wall
     * and the frame, into the step (amended); the redo puts both back.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_undo_anyway", timeoutTicks = LIMIT)
    public void undoAnywayTakesTheFrameIntoTheStep(GameTestHelper context) {
        Sync s = new Sync(context, 2708);
        BlockPos wall = new BlockPos(s.x + 6, FLOOR + 1, s.z + 3);
        s.sequence().thenExecute(() -> {
            s.fill(cellBox(wall), "minecraft:stone");
            s.world.setBlock(wall, Blocks.COBBLESTONE.defaultBlockState(), 2); // changed since the Fill
            ItemFrame frame = s.hang(wall, Items.DIAMOND);
            String hung = s.state(frame);
            JobResult undo = s.undo();
            check(undo.skippedConflicts() == 1, "the changed wall is kept: " + undo);
            check(s.find(frame.getUUID()) != null, "and its frame with it");
            s.undoAnyway(1);
            check(s.world.getBlockState(wall).isAir() && s.frames().isEmpty(), "Undo anyway took the wall and frame");
            HistoryEntry amended = s.h.service.historyService().redoEntries(s.h.player.getUUID()).get(0);
            check(amended.record().entities().size() == 1, "the step took the frame: " + amended.record().entities());
            s.redo();
            check(s.world.getBlockState(wall).is(Blocks.STONE), "the redo puts the Fill's stone back");
            check(hung.equals(s.state(s.find(frame.getUUID()))), "and the frame on it");
            s.noItems("after the redo");
            s.finish();
        }).thenSucceed();
    }

    /**
     * The amendment survives a restart: the redo after it puts the frames back. (The area is loaded first and its
     * entities waited for, ticks of the server apart; the restart itself runs in one tick, as the other durable tests.)
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_hanging_restart", timeoutTicks = LIMIT)
    public void theAmendmentSurvivesARestart(GameTestHelper context) {
        int[] at = regionCorner(context, 2709);
        Box region = box(at[0], 96, at[1], at[0] + 15, 111, at[1] + 15);
        loadAndForce(context.getLevel(), region);
        context.startSequence()
                .thenWaitUntil(() -> check(McEntities.firstUnloaded(context.getLevel(), region) == null,
                        "the area's entities are not loaded yet"))
                .thenExecute(() -> restart(context, region))
                .thenSucceed();
    }

    private static void restart(GameTestHelper context, Box region) {
        try (Scene scene = new Scene(context, 2709)) {
            Harness h = scene.h;
            int x = scene.x, z = scene.z;
            ServerPlayer carol = h.addPlayer(false);
            EditTestSupport.grant(carol, Perm.USE, Perm.REGION);
            scene.area(region, 100);
            Run first = scene.run();
            first.load(carol);
            first.fill(carol, box(x + 4, 101, z + 4, x + 6, 102, z + 4), "minecraft:stone");
            ItemFrame a = hang(h.world, new BlockPos(x + 4, 101, z + 4), Items.DIAMOND);
            ItemFrame c = hang(h.world, new BlockPos(x + 6, 102, z + 4), Items.APPLE);
            String stateA = state(a), stateC = state(c);
            first.step(carol, true);
            check(frames(h.world, scene.region).isEmpty(), "the undo took the frames");
            check(items(h.world, scene.region).isEmpty(), "no item dropped");
            first.stop();

            Run second = scene.run();
            second.load(carol);
            second.step(carol, false);
            check(stateA.equals(state(h.world.getEntity(a.getUUID())))
                    && stateC.equals(state(h.world.getEntity(c.getUUID()))), "the redo after the restart hangs both");
            second.step(carol, true);
            check(frames(h.world, scene.region).isEmpty(), "undone again after the restart");
            check(items(h.world, scene.region).isEmpty(), "no item dropped");
            second.stop();
        }
    }

    // ============================================================================================ helpers

    /** A loaded, forced work area: a stone floor at {@link #FLOOR}, air above. */
    private static Box area(ServerLevel world, int x, int z) {
        Box area = box(x, FLOOR, z, x + 31, FLOOR + 12, z + 31);
        loadAndForce(world, area);
        for (int px = x; px <= x + 31; px++) {
            for (int pz = z; pz <= z + 31; pz++) {
                world.setBlock(new BlockPos(px, FLOOR, pz), Blocks.STONE.defaultBlockState(), 2);
                for (int y = FLOOR + 1; y <= FLOOR + 12; y++) {
                    world.setBlock(new BlockPos(px, y, pz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        return area;
    }

    /** A column of stone {@code height} high on the floor at (x, z): a wall to hang things on its south face. */
    private static BlockPos wall(ServerLevel world, int x, int z, int height) {
        for (int y = FLOOR + 1; y <= FLOOR + height; y++) {
            world.setBlock(new BlockPos(x, y, z), Blocks.STONE.defaultBlockState(), 2);
        }
        return new BlockPos(x, FLOOR + 1, z);
    }

    /** Hangs a frame holding {@code item} on the south face of {@code wall}, outside any history. */
    private static ItemFrame hang(ServerLevel world, BlockPos wall, Item item) {
        ItemFrame frame = new ItemFrame(world, wall.south(), Direction.SOUTH);
        frame.setItem(new ItemStack(item), false);
        check(frame.survives() && world.addFreshEntity(frame), "a frame hung at " + wall.south().toShortString());
        return frame;
    }

    private static AABB around(Box area) {
        return new AABB(area.min().x() - 3, area.min().y() - 3, area.min().z() - 3, area.max().x() + 4,
                area.max().y() + 4, area.max().z() + 4);
    }

    private static List<ItemEntity> items(ServerLevel world, Box area) {
        return world.getEntitiesOfClass(ItemEntity.class, around(area), e -> !e.isRemoved());
    }

    private static List<ItemFrame> frames(ServerLevel world, Box area) {
        return world.getEntitiesOfClass(ItemFrame.class, around(area), e -> !e.isRemoved());
    }

    /** The entity's data as undo compares it (without volatile keys), for equality checks. */
    private static String state(Entity entity) {
        check(entity != null && !entity.isRemoved(), "the entity is there");
        try {
            return dev.sculptory.core.entity.EntityNbt.withoutVolatile(McEntities.fromBytes(McEntities.bytes(
                    McEntities.save(entity, dev.sculptory.core.entity.EntityFilter.ALL)))).toString();
        } catch (java.io.IOException e) {
            throw new GameTestAssertException("entity NBT: " + e);
        }
    }

    private static Box cellBox(BlockPos cell) {
        return Box.of(new dev.sculptory.core.BlockPos(cell.getX(), cell.getY(), cell.getZ()));
    }

    /**
     * A test through the server's own edit service (so the mixins are on the path): an op player with a builder power
     * on, standing in a private area, and the steps of its history.
     */
    private static final class Builder {
        final GameTestHelper context;
        final ServerLevel world;
        final EngineEditService<ServerPlayer, ServerLevel> host;
        final ServerPlayer player;
        final int x;
        final int z;
        final Box area;
        final List<Runnable> cleanups = new ArrayList<>();

        @SuppressWarnings("removal") // makeMockServerPlayerInLevel is vanilla's test-only mock player
        Builder(GameTestHelper context, int slot) {
            this.context = context;
            this.world = context.getLevel();
            MinecraftServer server = world.getServer();
            this.host = EditServiceHost.find(server)
                    .orElseThrow(() -> new GameTestAssertException("the mod did not install the edit service"));
            int[] at = regionCorner(context, slot);
            this.x = at[0];
            this.z = at[1];
            this.area = HangingEntitiesGameTest.area(world, x, z);
            this.player = context.makeMockServerPlayerInLevel();
            server.getPlayerList().getOps().add(new ServerOpListEntry(player.getGameProfile(), 2, false));
            host.playerJoined(player.getUUID());
            host.builderPowers(player, BuilderPower.KEEP_SHAPE.bit());
        }

        BlockPos wall(int dx, int dz) {
            return HangingEntitiesGameTest.wall(world, x + dx, z + dz, 3);
        }

        /** Stands the player on the floor at (dx, dz), facing north. */
        void stand(int dx, int dz) {
            player.moveTo(x + dx + 0.5, FLOOR + 1, z + dz + 0.5, 180f, 0f);
            player.setYHeadRot(180f);
        }

        /** Waits until the area's entities and the player's history are loaded. */
        net.minecraft.gametest.framework.GameTestSequence sequence() {
            return context.startSequence().thenWaitUntil(() -> {
                check(McEntities.firstUnloaded(world, area) == null, "the area's entities are not loaded yet");
                check(!host.historyService().session(player.getUUID()).loading(), "the history is loading");
            });
        }

        void hold(Item item) {
            player.setItemInHand(InteractionHand.MAIN_HAND, item == Items.AIR ? ItemStack.EMPTY : new ItemStack(item));
        }

        /** The held item's use on {@code face} of {@code block}, as the game runs it for the use-on packet. */
        void useOn(BlockPos block, Direction face) {
            Vec3 hit = Vec3.atCenterOf(block).relative(face, 0.5);
            player.gameMode.useItemOn(player, world, player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, face, block, false));
        }

        void interact(Entity entity) {
            player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(entity, false,
                    InteractionHand.MAIN_HAND));
        }

        void interactAt(Entity entity, Vec3 at) {
            player.connection.handleInteract(ServerboundInteractPacket.createInteractionPacket(entity, false,
                    InteractionHand.MAIN_HAND, at));
        }

        void attack(Entity entity) {
            player.connection.handleInteract(ServerboundInteractPacket.createAttackPacket(entity, false));
        }

        <T extends Entity> List<T> all(Class<T> type) {
            return world.getEntitiesOfClass(type, around(area), e -> !e.isRemoved());
        }

        <T extends Entity> T only(Class<T> type) {
            List<T> found = all(type);
            check(found.size() == 1, "one " + type.getSimpleName() + ", found " + found.size());
            return found.get(0);
        }

        /** The frames, paintings and armor stands of the area as undo compares them, sorted (a state to compare). */
        String describe() {
            List<String> out = new ArrayList<>();
            for (Entity entity : world.getEntities((Entity) null, around(area),
                    e -> !e.isRemoved() && (e instanceof ItemFrame || e instanceof Painting || e instanceof ArmorStand))) {
                out.add(entity.getUUID() + "=" + state(entity));
            }
            out.sort(null);
            return out.toString();
        }

        void expectLabels(String... labels) {
            List<String> undo = host.history(player).undoLabels();
            check(undo.equals(List.of(labels)), "history " + undo + ", expected " + List.of(labels));
        }

        void noItems(String when) {
            List<ItemEntity> items = HangingEntitiesGameTest.items(world, area);
            check(items.isEmpty(), "no item entity " + when + ": " + items);
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

        /**
         * After {@code actions} (which recorded the area's state after each of its {@code steps} steps in
         * {@code after}): undoes every step back to the bare area, checking each state on the way, then redoes them all
         * back to the last, with no item dropped and no conflict; then cleans up and succeeds.
         */
        void undoRedo(net.minecraft.gametest.framework.GameTestSequence actions, List<String> after, int steps) {
            RecordingListener[] job = {null};
            for (int i = steps; i >= 1; i--) {
                int expected = i - 1;
                actions.thenExecute(() -> job[0] = step(true))
                        .thenWaitUntil(() -> check(job[0].result != null, "undo running"))
                        .thenExecute(() -> {
                            check(job[0].result.outcome() == JobOutcome.COMPLETED
                                    && job[0].result.skippedConflicts() == 0, "undo " + job[0].result);
                            String now = describe();
                            String wanted = expected == 0 ? "[]" : after.get(expected - 1);
                            check(now.equals(wanted), "after an undo: " + now + ", expected " + wanted);
                            noItems("after an undo");
                        });
            }
            for (int i = 1; i <= steps; i++) {
                int expected = i;
                actions.thenExecute(() -> job[0] = step(false))
                        .thenWaitUntil(() -> check(job[0].result != null, "redo running"))
                        .thenExecute(() -> {
                            check(job[0].result.outcome() == JobOutcome.COMPLETED
                                    && job[0].result.skippedConflicts() == 0, "redo " + job[0].result);
                            String now = describe();
                            String wanted = after.get(expected - 1);
                            check(now.equals(wanted), "after a redo: " + now + ", expected " + wanted);
                            noItems("after a redo");
                        });
            }
            actions.thenExecuteAfter(GAME_CHECK_TICKS, () -> {
                noItems("after the game's own check");
                finish();
            }).thenSucceed();
        }

        void finish() {
            for (Runnable cleanup : cleanups) cleanup.run();
            MinecraftServer server = world.getServer();
            host.builderPowers(player, 0);
            host.cancelAll(player.getUUID());
            host.playerLeft(player.getUUID());
            server.getPlayerList().getOps().remove(player.getGameProfile());
            try {
                server.getPlayerList().remove(player);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger("sculptory").warn("Could not remove the mock player", e);
            }
            forceChunks(world, area, false);
        }
    }

    /** A test on a harness with a private executor, ticked here: every edit runs to its end in one call. */
    private static final class Sync {
        final GameTestHelper context;
        final EditExecutor<ServerLevel> executor;
        final Harness h;
        final ServerLevel world;
        final int x;
        final int z;
        final Box area;

        Sync(GameTestHelper context, int slot) {
            this.context = context;
            this.executor = MultiplayerGameTest.executor(context, 0);
            this.h = new Harness(context, null, System::nanoTime, executor);
            this.world = h.world;
            int[] at = regionCorner(context, slot);
            this.x = at[0];
            this.z = at[1];
            this.area = HangingEntitiesGameTest.area(world, x, z);
        }

        BlockPos wall(int dx, int dz) {
            return HangingEntitiesGameTest.wall(world, x + dx, z + dz, 1);
        }

        net.minecraft.gametest.framework.GameTestSequence sequence() {
            return context.startSequence().thenWaitUntil(() -> check(McEntities.firstUnloaded(world, area) == null,
                    "the area's entities are not loaded yet"));
        }

        ItemFrame hang(BlockPos wall, Item item) {
            return HangingEntitiesGameTest.hang(world, wall, item);
        }

        String state(Entity entity) {
            return HangingEntitiesGameTest.state(entity);
        }

        Entity find(UUID id) {
            Entity entity = world.getEntity(id);
            return entity == null || entity.isRemoved() ? null : entity;
        }

        List<ItemFrame> frames() {
            return HangingEntitiesGameTest.frames(world, area);
        }

        void noItems(String when) {
            List<ItemEntity> items = HangingEntitiesGameTest.items(world, area);
            check(items.isEmpty(), "no item entity " + when + ": " + items);
        }

        List<HistoryEntry> entries() {
            return h.service.historyService().undoEntries(h.player.getUUID());
        }

        private JobResult await(Supplier<RecordingListener> start, String what) {
            RecordingListener listener = start.get();
            MultiplayerGameTest.tickUntil(executor, () -> listener.result != null, TICKS, what);
            h.service.tick();
            check(listener.result.outcome() == JobOutcome.COMPLETED, what + " " + listener.result);
            return listener.result;
        }

        JobResult fill(Box box, String state) {
            return await(() -> {
                RecordingListener listener = new RecordingListener();
                try {
                    h.service.run(h.player, new OpSpec.Fill(box, new Pattern.Single(h.state(state)), CellMask.ANY),
                            RunOptions.DEFAULT, listener);
                } catch (EditRejected e) {
                    throw new GameTestAssertException("the fill was refused: " + e.getMessage());
                }
                return listener;
            }, "the fill");
        }

        JobResult undo() {
            return await(() -> {
                RecordingListener listener = new RecordingListener();
                h.undo(listener);
                return listener;
            }, "the undo");
        }

        JobResult redo() {
            return await(() -> {
                RecordingListener listener = new RecordingListener();
                h.redo(listener);
                return listener;
            }, "the redo");
        }

        JobResult undoAnyway(int steps) {
            return await(() -> {
                RecordingListener listener = new RecordingListener();
                try {
                    h.service.historyOverwrite(h.player, false, steps, listener);
                } catch (EditRejected e) {
                    throw new GameTestAssertException("Undo anyway was refused: " + e.getMessage());
                }
                return listener;
            }, "Undo anyway");
        }

        void finish() {
            executor.shutdown();
            forceChunks(world, area, false);
            h.close();
        }
    }

}
