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
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McTile;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Copy, cut, paste, move and stack against a real server. Each test works in its own
 * far region, above the flat test world's ground, in its own batch.
 */
public final class ClipboardGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** Every block that rotates or mirrors differently, with a block entity or two among them. */
    private static final String[][] SHOWCASE = {
            {"0,0,0", "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]"},
            {"1,0,0", "minecraft:oak_stairs[facing=east,half=top,shape=inner_left]"},
            {"2,0,0", "minecraft:oak_stairs[facing=south,half=bottom,shape=outer_right]"},
            {"3,0,0", "minecraft:oak_stairs[facing=west,half=top,shape=inner_right]"},
            {"4,0,0", "minecraft:stone_slab[type=top]"},
            {"5,0,0", "minecraft:stone_slab[type=bottom]"},
            {"0,0,2", "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false]"},
            {"0,1,2", "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false]"},
            {"1,0,2", "minecraft:oak_door[facing=north,half=lower,hinge=right,open=true]"},
            {"1,1,2", "minecraft:oak_door[facing=north,half=upper,hinge=right,open=true]"},
            {"3,0,3", "minecraft:red_bed[facing=north,part=foot]"},
            {"3,0,2", "minecraft:red_bed[facing=north,part=head]"},
            {"4,0,4", "minecraft:white_bed[facing=east,part=foot]"},
            {"5,0,4", "minecraft:white_bed[facing=east,part=head]"},
            {"0,0,4", "minecraft:rail[shape=north_south]"},
            {"1,0,4", "minecraft:rail[shape=north_east]"},
            {"2,0,4", "minecraft:rail[shape=ascending_east]"},
            {"0,0,5", "minecraft:powered_rail[shape=ascending_north]"},
            {"1,0,5", "minecraft:detector_rail[shape=east_west]"},
            {"2,0,5", "minecraft:rail[shape=south_west]"},
            {"2,1,0", "minecraft:oak_log[axis=x]"},
            {"3,1,0", "minecraft:oak_log[axis=z]"},
            {"4,1,0", "minecraft:chest[facing=west,type=left]"},
            {"4,1,1", "minecraft:chest[facing=west,type=right]"},
            {"0,2,0", "minecraft:repeater[facing=east,delay=2]"},
            {"1,2,0", "minecraft:lever[face=wall,facing=south]"},
            {"2,2,0", "minecraft:piston[facing=east]"},
            {"3,2,0", "minecraft:observer[facing=north]"},
            {"4,2,0", "minecraft:vine[north=true,east=true]"},
            {"5,2,0", "minecraft:glass_pane[north=true,east=true]"},
            {"0,3,0", "minecraft:oak_fence[north=true,west=true]"},
            {"1,3,0", "minecraft:redstone_wire[east=side,north=up]"},
            {"2,3,0", "minecraft:oak_trapdoor[facing=south,half=top,open=true]"},
            {"3,3,0", "minecraft:wall_torch[facing=west]"},
            {"4,3,0", "minecraft:white_banner[rotation=5]"},
            {"5,3,0", "minecraft:oak_sign[rotation=3]"},
            {"5,3,5", "minecraft:skeleton_skull[rotation=7]"},
            {"4,3,5", "minecraft:oak_wall_sign[facing=north]"},
            {"0,3,5", "minecraft:ladder[facing=west]"},
            {"1,3,5", "minecraft:end_rod[facing=east]"},
    };

    static net.minecraft.world.level.block.Mirror vanilla(Mirror mirror) {
        return switch (mirror) {
            case NONE -> net.minecraft.world.level.block.Mirror.NONE;
            case X -> net.minecraft.world.level.block.Mirror.FRONT_BACK;
            case Z -> net.minecraft.world.level.block.Mirror.LEFT_RIGHT;
        };
    }

    static Rotation vanilla(int turns) {
        return switch (turns & 3) {
            case 0 -> Rotation.NONE;
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            default -> Rotation.COUNTERCLOCKWISE_90;
        };
    }

    /** Copies {@code box} for {@code player} (anchor {@code origin - box.min}); the reply arrives on a later tick. */
    static Captured<ClipboardService.ClipboardInfo> copy(ServerClipboards<ServerPlayer, ServerLevel> clips,
                                                         ServerPlayer player, Box box, BlockPos origin) {
        Captured<ClipboardService.ClipboardInfo> reply = new Captured<>();
        try {
            clips.copy(player, box, origin, false, CellMask.ANY, null, reply);
        } catch (EditRejected e) {
            throw new GameTestAssertException("copy refused: " + e.getMessage());
        }
        return reply;
    }

    static JobTicket paste(Harness h, ServerPlayer player, java.util.UUID clipboardId, BlockPos origin,
                           Transform t, RecordingListener listener) {
        try {
            return h.service.run(player, new OpSpec.Paste(new SourceRef.Clipboard(clipboardId), origin, t,
                    PasteOptions.DEFAULT), RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestAssertException("paste refused: " + e.getMessage());
        }
    }

    static EditRejected refusal(ThrowingRun run) {
        try {
            run.run();
        } catch (EditRejected e) {
            return e;
        }
        throw new GameTestAssertException("expected a refusal");
    }

    @FunctionalInterface
    interface ThrowingRun {
        void run() throws EditRejected;
    }

    /**
     * A copy of stairs, slabs, doors, beds, rails and friends pasted with every turn and mirror: each pasted cell
     * holds vanilla's {@code mirror(...).rotate(...)} of its source cell, at the transformed position.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_clip_rotate", timeoutTicks = LIMIT)
    public void pasteRotatedStairsSlabsDoorsBedsRails(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 40);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 5, y0 + 3, z0 + 5);
        List<Transform> transforms = new ArrayList<>(Transform.all());
        for (int turns = 0; turns < 4; turns++) transforms.add(new Transform(turns, Mirror.Z));
        int spacing = 12;
        Box all = box(x0 - 8, y0, z0 - 8, x0 + spacing * (transforms.size() + 1) + 8, y0 + 3, z0 + 13);
        loadAndForce(world, all);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        for (String[] cell : SHOWCASE) {
            String[] xyz = cell[0].split(",");
            writer.write(x0 + Integer.parseInt(xyz[0]), y0 + Integer.parseInt(xyz[1]), z0 + Integer.parseInt(xyz[2]),
                    h.state(cell[1]), null);
        }
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, source, source.min());
        List<RecordingListener> pastes = new ArrayList<>();
        List<BlockPos> origins = new ArrayList<>();
        // Two rounds: at most 8 of a player's jobs may wait to start (executor.maxQueuedJobsPerPlayer).
        int half = transforms.size() / 2;
        Runnable secondRound = () -> {
            for (int k = half; k < transforms.size(); k++) {
                BlockPos origin = new BlockPos(x0 + spacing * (k + 1), y0, z0);
                RecordingListener listener = new RecordingListener();
                paste(h, h.player, copied.value.clipboardId(), origin, transforms.get(k), listener);
                pastes.add(listener);
                origins.add(origin);
            }
        };
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    check(info.dims().equals(new BlockPos(6, 4, 6)) && info.anchor().equals(BlockPos.ORIGIN), "info " + info);
                    for (int k = 0; k < half; k++) {
                        BlockPos origin = new BlockPos(x0 + spacing * (k + 1), y0, z0);
                        RecordingListener listener = new RecordingListener();
                        paste(h, h.player, info.clipboardId(), origin, transforms.get(k), listener);
                        pastes.add(listener);
                        origins.add(origin);
                    }
                })
                .thenWaitUntil(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .thenWaitUntil(secondRound)
                .thenWaitUntil(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .thenWaitUntil(() -> {
                    for (int k = 0; k < transforms.size(); k++) {
                        check(pastes.get(k).result.outcome() == JobOutcome.COMPLETED, "paste " + k + " " + pastes.get(k).result);
                        checkTransformed(world, source, origins.get(k), transforms.get(k));
                    }
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** Every source cell of {@code source} (anchor at its minimum corner) found transformed around {@code origin}. */
    private static void checkTransformed(ServerLevel world, Box source, BlockPos origin, Transform t) {
        int sx = source.sizeX(), sz = source.sizeZ();
        int ax = t.mapX(0, 0, sx, sz), az = t.mapZ(0, 0, sx, sz);
        net.minecraft.world.level.block.Mirror mirror = vanilla(t.mirror());
        Rotation rotation = vanilla(t.quarterTurnsCw());
        int nonAir = 0;
        for (int y = 0; y < source.sizeY(); y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    BlockState from = world.getBlockState(pos(source.min().x() + x, source.min().y() + y, source.min().z() + z));
                    if (from.isAir()) continue;
                    nonAir++;
                    BlockState expected = from.mirror(mirror).rotate(rotation);
                    net.minecraft.core.BlockPos to = pos(origin.x() + t.mapX(x, z, sx, sz) - ax, origin.y() + y,
                            origin.z() + t.mapZ(x, z, sx, sz) - az);
                    BlockState got = world.getBlockState(to);
                    if (got != expected) {
                        throw new GameTestAssertException(t + ": " + from + " at " + x + "," + y + "," + z + " should be "
                                + expected + " at " + to.toShortString() + ", got " + got);
                    }
                }
            }
        }
        // Nothing else was placed around the paste.
        int placed = 0;
        int minX = origin.x() - ax, minZ = origin.z() - az;
        int tx = t.sizeX(sx, sz), tz = t.sizeZ(sx, sz);
        for (int y = 0; y < source.sizeY(); y++) {
            for (int z = -1; z <= tz; z++) {
                for (int x = -1; x <= tx; x++) {
                    if (!world.getBlockState(pos(minX + x, origin.y() + y, minZ + z)).isAir()) placed++;
                }
            }
        }
        check(placed == nonAir, t + ": " + placed + " blocks placed, expected " + nonAir);
    }

    /** Chest items, sign text, furnace contents and a command block survive copy and paste exactly. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_clip_nbt", timeoutTicks = LIMIT)
    public void copyPasteRoundTripWithNbt(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 41);
        Box source = box(at[0], 100, at[1], at[0] + 7, 103, at[1] + 7);
        Box all = box(at[0], 100, at[1], at[0] + 63, 110, at[1] + 7);
        loadAndForce(world, all);
        decorate(h, world, source);
        WorldSnapshot before = capture(world, source);
        check(before.tiles.size() == 4, "fixture has " + before.tiles.size() + " block entities");
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        BlockPos origin = new BlockPos(source.min().x() + 2, source.min().y(), source.min().z() + 3);
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, source, origin);
        RecordingListener paste = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        BlockPos target = new BlockPos(at[0] + 40 + 2, 104, at[1] + 3); // where the anchor lands
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    check(info.anchor().equals(new BlockPos(2, 0, 3)) && info.cells() == source.volume(), "info " + info);
                    Clipboard held = h.service.clipboards().get(h.player.getUUID()).orElseThrow().clipboard();
                    check(held.tileCount() == 4, "clipboard tiles " + held.tileCount());
                    paste(h, h.player, info.clipboardId(), target, Transform.IDENTITY, paste);
                })
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.strippedNbt() == 0, "paste " + paste.result);
                    ClipTestSupport.checkShifted(world, before, pos(at[0] + 40, 104, at[1]), "the paste");
                    check(h.history().undoLabel().startsWith("Paste · "), "history " + h.history());
                    h.undo(undo);
                })
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    Box pasted = box(at[0] + 40, 104, at[1], at[0] + 47, 107, at[1] + 7);
                    WorldSnapshot now = capture(world, pasted);
                    for (int state : now.states) check(Block.stateById(state).isAir(), "undo left a block");
                    check(now.tiles.isEmpty(), "undo left block entities");
                    checkSame(before, capture(world, source), "the source");
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** Stone, planks, a chest with items, a sign, a furnace and a command block with a command. */
    static void decorate(Harness h, ServerLevel world, Box box) {
        BlockWriter writer = h.runtime.writer(world, new WriteOptions(false, true));
        int x = box.min().x(), y = box.min().y(), z = box.min().z();
        for (int dx = 0; dx < box.sizeX(); dx++) {
            for (int dz = 0; dz < box.sizeZ(); dz++) {
                writer.write(x + dx, y, z + dz, h.state((dx + dz) % 3 == 0 ? "minecraft:stone" : "minecraft:oak_planks"), null);
            }
        }
        writer.write(x + 1, y + 1, z + 1, h.state("minecraft:oak_stairs[facing=east,half=top]"), null);
        writer.write(x + 2, y + 1, z + 1, h.state("minecraft:water"), null);
        net.minecraft.core.BlockPos chest = pos(x + 3, y + 1, z + 3);
        writer.write(chest.getX(), chest.getY(), chest.getZ(), h.state("minecraft:chest[facing=east]"), null);
        ChestBlockEntity chestEntity = (ChestBlockEntity) world.getBlockEntity(chest);
        chestEntity.setItem(0, new ItemStack(Items.DIAMOND, 5));
        chestEntity.setItem(20, new ItemStack(Items.OAK_LOG, 64));
        net.minecraft.core.BlockPos sign = pos(x + 5, y + 1, z + 2);
        writer.write(sign.getX(), sign.getY(), sign.getZ(), h.state("minecraft:oak_sign[rotation=4]"), null);
        ((SignBlockEntity) world.getBlockEntity(sign)).setText(
                new SignText().setMessage(0, Component.literal("Builder")).setMessage(1, Component.literal("Suite")), true);
        net.minecraft.core.BlockPos furnace = pos(x + 6, y + 2, z + 6);
        writer.write(furnace.getX(), furnace.getY(), furnace.getZ(), h.state("minecraft:furnace[facing=south]"), null);
        ((FurnaceBlockEntity) world.getBlockEntity(furnace)).setItem(0, new ItemStack(Items.IRON_ORE, 3));
        net.minecraft.core.BlockPos command = pos(x + 1, y + 3, z + 6);
        writer.write(command.getX(), command.getY(), command.getZ(), h.state("minecraft:command_block[facing=up]"), null);
        ((CommandBlockEntity) world.getBlockEntity(command)).getCommandBlock().setCommand("say sculptory");
    }

    /** A cut copies the box, erases it with one undoable job, and the clipboard pastes the original back. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_clip_cut", timeoutTicks = LIMIT)
    public void cutIsUndoable(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 42);
        Box source = box(at[0], 100, at[1], at[0] + 7, 103, at[1] + 7);
        Box all = box(at[0], 100, at[1], at[0] + 47, 103, at[1] + 7);
        loadAndForce(world, all);
        decorate(h, world, source);
        WorldSnapshot before = capture(world, source);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> cut = new Captured<>();
        RecordingListener erase = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        RecordingListener paste = new RecordingListener();
        JobTicket ticket;
        try {
            ticket = clips.copy(h.player, source, new BlockPos(source.min().x(), source.min().y(), source.min().z()), true,
                    CellMask.ANY, erase, cut);
        } catch (EditRejected e) {
            throw new GameTestAssertException("cut refused: " + e.getMessage());
        }
        check(ticket != null && ticket.label().equals("Erase"), "cut job " + ticket);
        // While the clipboard is being made, a second copy is refused rather than racing it.
        check(clips.building(h.player.getUUID()), "the cut's clipboard is not pending");
        check(refusal(() -> clips.copy(h.player, source, new BlockPos(0, 0, 0), false, CellMask.ANY, null, new Captured<>()))
                .reason() == RejectReason.QUEUE_FULL, "a second copy was not refused");
        context.startSequence()
                .thenWaitUntil(() -> check(erase.result != null && cut.finished(), "cut running"))
                .thenWaitUntil(() -> {
                    check(erase.result.outcome() == JobOutcome.COMPLETED, "erase " + erase.result);
                    ClipboardService.ClipboardInfo info = cut.get("cut");
                    check(info.cells() == source.volume(), "clipboard cells " + info.cells());
                    WorldSnapshot erased = capture(world, source);
                    for (int state : erased.states) check(Block.stateById(state).isAir(), "the cut left a block");
                    check(erased.tiles.isEmpty(), "the cut left block entities");
                    check(h.history().undoLabel().startsWith("Erase · "), "history " + h.history());
                    h.undo(undo);
                })
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(before, capture(world, source), "after undoing the cut");
                    paste(h, h.player, cut.value.clipboardId(), new BlockPos(at[0] + 32, 100, at[1]), Transform.IDENTITY, paste);
                })
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    ClipTestSupport.checkShifted(world, before, pos(at[0] + 32, 100, at[1]), "the cut clipboard pasted");
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** A move onto an overlapping destination reads its source before writing; undo restores everything. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_clip_move", timeoutTicks = LIMIT)
    public void moveOverlappingIsExactAndUndoable(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 43);
        Box source = box(at[0], 100, at[1], at[0] + 9, 104, at[1] + 9);
        Box union = box(at[0], 100, at[1], at[0] + 12, 105, at[1] + 11);
        loadAndForce(world, union);
        decorate(h, world, source);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        for (int i = 0; i < 10; i++) writer.write(at[0] + i, 102 + (i % 3), at[1] + 9 - i, h.state("minecraft:gold_block"), null);
        WorldSnapshot sourceBefore = capture(world, source);
        WorldSnapshot unionBefore = capture(world, union);
        RecordingListener move = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        // A move whose destination leaves the build height would lose those cells: refused up front.
        check(refusal(() -> h.service.run(h.player, new OpSpec.Move(source, new BlockPos(0, 300, 0), Transform.IDENTITY, air),
                RunOptions.DEFAULT, null)).reason() == RejectReason.INVALID, "a move out of the world was admitted");
        try {
            h.service.run(h.player, new OpSpec.Move(source, new BlockPos(3, 1, 2), Transform.IDENTITY,
                    new Pattern.Single(h.state("minecraft:air"))), RunOptions.DEFAULT, move);
        } catch (EditRejected e) {
            throw new GameTestAssertException("move refused: " + e.getMessage());
        }
        Box destination = source.offset(3, 1, 2);
        context.startSequence()
                .thenWaitUntil(() -> check(move.result != null, "move running"))
                .thenWaitUntil(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED && move.result.skippedProtected() == 0,
                            "move " + move.result);
                    ClipTestSupport.checkShifted(world, sourceBefore, pos(at[0] + 3, 101, at[1] + 2), "the moved box");
                    for (int y = union.min().y(); y <= union.max().y(); y++) {
                        for (int z = union.min().z(); z <= union.max().z(); z++) {
                            for (int x = union.min().x(); x <= union.max().x(); x++) {
                                if (destination.contains(x, y, z)) continue;
                                BlockState state = world.getBlockState(pos(x, y, z));
                                if (source.contains(x, y, z)) {
                                    check(state.isAir(), "vacated cell " + x + "," + y + "," + z + " holds " + state);
                                } else {
                                    check(Block.getId(state) == unionBefore.get(x, y, z), "outside cell changed");
                                }
                            }
                        }
                    }
                    check(h.history().undoLabel().startsWith("Move · "), "history " + h.history());
                    h.undo(undo);
                })
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 0,
                            "undo " + undo.result);
                    checkSame(unionBefore, capture(world, union), "after undoing the move");
                    forceChunks(world, union, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** Three adjacent copies of a box, then one undo removes them all. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_clip_stack", timeoutTicks = LIMIT)
    public void stackUndo(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 44);
        Box source = box(at[0], 100, at[1], at[0] + 7, 103, at[1] + 7);
        Box all = box(at[0], 100, at[1], at[0] + 31, 103, at[1] + 7);
        loadAndForce(world, all);
        decorate(h, world, source);
        WorldSnapshot sourceBefore = capture(world, source);
        WorldSnapshot allBefore = capture(world, all);
        RecordingListener stack = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        check(refusal(() -> h.service.run(h.player, new OpSpec.Stack(source, 8, 0, 0, 257), RunOptions.DEFAULT, null))
                .reason() == RejectReason.TOO_LARGE, "a stack of 257 was not refused");
        // Volumes are refused before anything is compiled, for a player without limit.bypass.
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        Box huge = box(at[0], -64, at[1], at[0] + 6399, 319, at[1] + 3199);
        long started = System.nanoTime();
        check(refusal(() -> h.service.run(builder, new OpSpec.Move(huge, new BlockPos(1, 0, 0), Transform.IDENTITY,
                new Pattern.Single(h.state("minecraft:air"))), RunOptions.DEFAULT, null)).reason() == RejectReason.TOO_LARGE,
                "a huge move was not refused");
        check(refusal(() -> h.service.run(builder, new OpSpec.Stack(box(at[0], 100, at[1], at[0] + 99, 199, at[1] + 99),
                0, 100, 0, 3), RunOptions.DEFAULT, null)).reason() == RejectReason.TOO_LARGE, "3M stacked cells were admitted");
        long millis = (System.nanoTime() - started) / 1_000_000;
        check(millis < 200, "the refusals took " + millis + " ms");
        try {
            h.service.run(h.player, new OpSpec.Stack(source, 8, 0, 0, 3), RunOptions.DEFAULT, stack);
        } catch (EditRejected e) {
            throw new GameTestAssertException("stack refused: " + e.getMessage());
        }
        context.startSequence()
                .thenWaitUntil(() -> check(stack.result != null, "stack running"))
                .thenWaitUntil(() -> {
                    check(stack.result.outcome() == JobOutcome.COMPLETED && stack.result.strippedNbt() == 0,
                            "stack " + stack.result);
                    for (int k = 1; k <= 3; k++) {
                        ClipTestSupport.checkShifted(world, sourceBefore, pos(at[0] + 8 * k, 100, at[1]), "copy " + k);
                    }
                    checkSame(sourceBefore, capture(world, source), "the stack source");
                    check(h.history().undoLabel().startsWith("Stack · "), "history " + h.history());
                    h.undo(undo);
                })
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(allBefore, capture(world, all), "after undoing the stack");
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** A copy applies the player's queued brush dabs before its snapshot, so the clipboard holds their result. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_clip_dabs", timeoutTicks = LIMIT)
    public void copyAppliesQueuedDabsFirst(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 47);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 100, z0, x0 + 31, 130, z0 + 31);
        loadAndForce(world, area);
        StrokeGameTest.terrain(h, x0, z0, 32, 32);
        WorldSnapshot before = capture(world, area);
        try {
            h.service.beginStroke(h.player, 3, new BrushSpec(BrushTool.RAISE, 5, 1f, Falloff.SMOOTH, Shape.CIRCLE, null,
                    SurfaceMask.ANY, 0, 0, 1L));
        } catch (EditRejected e) {
            throw new GameTestAssertException("stroke refused: " + e.getMessage());
        }
        List<Dab> dabs = new ArrayList<>();
        for (int i = 0; i < 8; i++) dabs.add(EditTestSupport.dab(i, x0 + 12 + i, 110, z0 + 16));
        check(h.service.dabs(h.player, 3, 50, dabs).accepted(), "dabs refused");
        check(h.service.queuedDabs(h.player.getUUID()) == 8, "the dabs ran already");
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = copy(clips, h.player, area, area.min());
        check(h.service.queuedDabs(h.player.getUUID()) == 0, "the copy did not apply the queued dabs first");
        WorldSnapshot atCopy = capture(world, area);
        check(EditTestSupport.difference(before, atCopy) != null, "the dabs changed nothing");
        context.startSequence()
                .thenWaitUntil(() -> {
                    copied.get("copy");
                    Clipboard clipboard = h.service.clipboards().get(h.player.getUUID()).orElseThrow().clipboard();
                    for (int y = area.min().y(); y <= area.max().y(); y++) {
                        for (int z = area.min().z(); z <= area.max().z(); z++) {
                            for (int x = area.min().x(); x <= area.max().x(); x++) {
                                int got = clipboard.get(x - x0, y - 100, z - z0);
                                check(got == atCopy.get(x, y, z), "the clipboard missed the dabs at " + x + "," + y + "," + z);
                            }
                        }
                    }
                    h.service.endStroke(h.player, 3);
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * The border cuts one chunk in half (columns from x0 + 12 are outside it), so that chunk's permit is per column.
     * A copy of the allowed columns only is fine; one reaching the denied columns is {@code PROTECTED}. A non-op with
     * {@code limit.bypass} may stack from the half-protected chunk, but the captured tiles are then untrusted: the
     * command block arrives without its command. The same stack from an unprotected source keeps it.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_clip_bypass", timeoutTicks = LIMIT)
    public void bypassStackFromProtectedSourceIsUntrusted(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        ServerPlayer bypasser = h.addPlayer(false);
        EditTestSupport.grant(bypasser, Perm.USE, Perm.CLIPBOARD, Perm.REGION, Perm.LIMIT_BYPASS);
        check(!h.runtime.permissions().mayWriteOperatorNbt(bypasser), "the bypasser may write operator NBT");
        int[] at = regionCorner(context, 46);
        int x0 = at[0], z0 = at[1];
        Box all = box(x0 - 16, 100, z0, x0 + 15, 103, z0 + 31);
        loadAndForce(world, all);
        net.minecraft.core.BlockPos command = pos(x0 + 13, 101, z0 + 5);
        h.runtime.writer(world, new WriteOptions(false, true))
                .write(command.getX(), command.getY(), command.getZ(), h.state("minecraft:command_block"), null);
        ((CommandBlockEntity) world.getBlockEntity(command)).getCommandBlock().setCommand("op me");
        Box source = box(x0 + 8, 100, z0 + 2, x0 + 15, 102, z0 + 9);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> inside = new Captured<>();
        RecordingListener untrusted = new RecordingListener();
        RecordingListener trusted = new RecordingListener();
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        try {
            border.setCenter(x0 + 12 - 100_000, z0);
            border.setSize(200_000);
            check(!world.mayInteract(builder, command) && world.mayInteract(builder, pos(x0 + 11, 101, z0 + 5)),
                    "the border does not cut the chunk");
            clips.copy(builder, box(x0 + 2, 100, z0 + 2, x0 + 11, 102, z0 + 9), new BlockPos(x0 + 2, 100, z0 + 2), false,
                    CellMask.ANY, null, inside);
            check(refusal(() -> clips.copy(builder, box(x0 + 2, 100, z0 + 2, x0 + 12, 102, z0 + 9),
                    new BlockPos(x0 + 2, 100, z0 + 2), false, CellMask.ANY, null, new Captured<>())).reason()
                    == RejectReason.PROTECTED, "a copy reaching the protected columns");
            check(refusal(() -> h.service.run(builder, new OpSpec.Stack(source, -16, 0, 0, 1), RunOptions.DEFAULT, null))
                    .reason() == RejectReason.PROTECTED, "a non-bypass stack from protected columns");
            h.service.run(bypasser, new OpSpec.Stack(source, -16, 0, 0, 1), RunOptions.DEFAULT, untrusted);
        } catch (EditRejected e) {
            throw new GameTestAssertException("unexpected refusal: " + e.getMessage());
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        context.startSequence()
                .thenWaitUntil(() -> check(untrusted.result != null && inside.finished(), "stack running"))
                .thenWaitUntil(() -> {
                    inside.get("the copy of the allowed columns");
                    check(untrusted.result.outcome() == JobOutcome.COMPLETED && untrusted.result.strippedNbt() == 1,
                            "untrusted stack " + untrusted.result);
                    CommandBlockEntity copy = (CommandBlockEntity) world.getBlockEntity(command.offset(-16, 0, 0));
                    check(copy != null && copy.getCommandBlock().getCommand().isEmpty(),
                            "a bypass stack from a protected source kept the command");
                    try {
                        h.service.run(bypasser, new OpSpec.Stack(source, 0, 0, 16, 1), RunOptions.DEFAULT, trusted);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("control stack refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> check(trusted.result != null, "control stack running"))
                .thenWaitUntil(() -> {
                    check(trusted.result.strippedNbt() == 0, "control stack " + trusted.result);
                    CommandBlockEntity copy = (CommandBlockEntity) world.getBlockEntity(command.offset(0, 0, 16));
                    check(copy != null && copy.getCommandBlock().getCommand().equals("op me"),
                            "a stack from a writable source lost the command");
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * Source-read protection. The world border is the one protection a GameTest can set up (spawn protection is
     * off on the test server): {@code canPlayerModifyAt} fails outside it. For one synchronous step the border cuts
     * a two-chunk region in half; a non-op builder may then not copy, move or stack from the outer chunk, nor move
     * into it; an op with {@code limit.bypass} may copy it, but its tiles are no longer trusted, and may still not
     * move across it (a move is all or nothing). The border is restored in the same step.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_clip_protect", timeoutTicks = LIMIT)
    public void copyRefusedFromProtectedSource(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        check(h.runtime.permissions().has(builder, Perm.REGION) && !h.runtime.permissions().has(builder, Perm.LIMIT_BYPASS),
                "grants did not apply");
        int[] at = regionCorner(context, 45);
        int x0 = at[0], z0 = at[1];
        Box inner = box(x0 + 2, 100, z0 + 2, x0 + 9, 103, z0 + 9);            // chunk A, inside the border
        Box spanning = box(x0 + 8, 100, z0 + 2, x0 + 23, 103, z0 + 9);        // chunks A and B
        Box all = box(x0, 100, z0, x0 + 31, 110, z0 + 15);
        loadAndForce(world, all);
        BlockWriter writer = h.runtime.writer(world, new WriteOptions(false, true));
        writer.write(x0 + 20, 101, z0 + 5, h.state("minecraft:command_block"), null);
        ((CommandBlockEntity) world.getBlockEntity(pos(x0 + 20, 101, z0 + 5))).getCommandBlock().setCommand("op me");
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> allowed = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> bypassed = new Captured<>();
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        WorldBorder border = world.getWorldBorder();
        double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
        try {
            // East bound at x0 + 16: chunk A inside, chunk B outside.
            border.setCenter(x0 + 16 - 100_000, z0);
            border.setSize(200_000);
            check(!world.mayInteract(builder, pos(x0 + 20, 101, z0 + 5)), "chunk B is not protected");
            check(world.mayInteract(builder, pos(x0 + 5, 101, z0 + 5)), "chunk A is protected");

            BlockPos o = new BlockPos(x0 + 8, 100, z0 + 2);
            check(refusal(() -> clips.copy(builder, spanning, o, false, CellMask.ANY, null, new Captured<>())).reason()
                    == RejectReason.PROTECTED, "copy from a protected chunk");
            check(refusal(() -> clips.copy(builder, spanning, o, true, CellMask.ANY, null, new Captured<>())).reason()
                    == RejectReason.PROTECTED, "cut from a protected chunk");
            check(refusal(() -> h.service.run(builder, new OpSpec.Stack(spanning, 0, 5, 0, 1), RunOptions.DEFAULT, null))
                    .reason() == RejectReason.PROTECTED, "stack from a protected chunk");
            check(refusal(() -> h.service.run(builder, new OpSpec.Move(spanning, new BlockPos(0, 6, 0), Transform.IDENTITY, air),
                    RunOptions.DEFAULT, null)).reason() == RejectReason.PROTECTED, "move from a protected chunk");
            check(refusal(() -> h.service.run(builder, new OpSpec.Move(inner, new BlockPos(12, 0, 0), Transform.IDENTITY, air),
                    RunOptions.DEFAULT, null)).reason() == RejectReason.PROTECTED, "move into a protected chunk");
            check(refusal(() -> h.service.run(h.player, new OpSpec.Move(spanning, new BlockPos(0, 6, 0), Transform.IDENTITY,
                    air), RunOptions.DEFAULT, null)).reason() == RejectReason.PROTECTED, "a bypass move across the border");
            check(!h.runtime.executor().isLocked(world, all), "a refusal left a job behind");

            clips.copy(builder, inner, new BlockPos(x0 + 2, 100, z0 + 2), false, CellMask.ANY, null, allowed);
            clips.copy(h.player, spanning, o, false, CellMask.ANY, null, bypassed);
        } catch (EditRejected e) {
            throw new GameTestAssertException("unexpected refusal: " + e.getMessage());
        } finally {
            border.setCenter(centerX, centerZ);
            border.setSize(size);
        }
        context.startSequence()
                .thenWaitUntil(() -> check(allowed.finished() && bypassed.finished(), "copies running"))
                .thenWaitUntil(() -> {
                    allowed.get("the builder's copy inside chunk A");
                    bypassed.get("the bypass copy");
                    Clipboard clipboard = h.service.clipboards().get(h.player.getUUID()).orElseThrow().clipboard();
                    BlockEntityData tile = clipboard.tile(12, 1, 3);
                    check(tile != null && tile.typeId().equals("minecraft:command_block"), "tile " + tile);
                    check(!McTile.isServerCaptured(tile), "a bypass copy of a protected area kept trusted tiles");
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * A copy or cut while the player's large Shape step is still being written is refused {@code QUEUE_FULL}
     * ({@code EditRejected.STROKE_PENDING}, which the client retries) before it takes a request slot or changes anything:
     * once the step is written, a copy goes ahead, so the refusals left no slot behind.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_clip_stroke_pending", timeoutTicks = LIMIT)
    public void aCopyWaitingForAStrokeLeavesNoRequestSlotBehind(GameTestHelper context) {
        dev.sculptory.server.engine.impl.EditExecutor<ServerLevel> executor = ShapeBrushGameTest.onePartATick(context);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 758);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 86, z0, x0 + 80, 154, z0 + 80);
        loadAndForce(world, area);
        Box copied = box(x0 + 2, 100, z0 + 2, x0 + 5, 103, z0 + 5);
        h.fill(copied, "minecraft:white_wool", new RecordingListener());
        MultiplayerGameTest.tickUntil(executor, () -> h.service.jobs(h.player.getUUID()).isEmpty(), 20, "the wool");
        BrushSpec cube = BrushSpec.shape(32, new dev.sculptory.core.brush.ShapeSpec(dev.sculptory.core.brush.ShapeSpec.Kind.CUBE,
                65, dev.sculptory.core.region.Facing.UP, dev.sculptory.core.brush.ShapeSpec.Mode.PLACE, 0),
                new Pattern.Single(h.state("minecraft:glass")), 1L, null, dev.sculptory.core.brush.Symmetry.NONE);
        try {
            h.service.beginStroke(h.player, 5, cube);
        } catch (EditRejected e) {
            throw new GameTestAssertException("stroke refused: " + e.getMessage());
        }
        check(h.service.dabs(h.player, 5, 1, List.of(ShapeBrushGameTest.at(0, x0 + 44, 120, z0 + 44))).accepted(), "the step");
        executor.tick(); // its first part
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        for (boolean cut : new boolean[] {false, true, false, true}) {
            EditRejected e = refusal(() -> clips.copy(h.player, copied, copied.min(), cut, CellMask.ANY, null,
                    new Captured<>()));
            check(e.reason() == RejectReason.QUEUE_FULL && e.kind().equals(EditRejected.STROKE_PENDING),
                    (cut ? "cut " : "copy ") + e.reason() + " " + e.kind());
        }
        check(world.getBlockState(EngineTestSupport.pos(x0 + 3, 101, z0 + 3)).is(Blocks.WHITE_WOOL), "a refused cut erased");
        // The step, and the commit its record's size queues after it (built at once: glass into air).
        MultiplayerGameTest.tickUntil(executor, () -> h.acks.seqs.size() == 1
                && h.service.queuedCommits(h.player.getUUID()) == 0, 100, "the step");
        Captured<ClipboardService.ClipboardInfo> copy = copy(clips, h.player, copied, copied.min());
        context.startSequence()
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = copy.get("the copy after the step");
                    check(info != null, "no clipboard");
                    h.service.endStroke(h.player, 5);
                    forceChunks(world, area, false);
                    executor.shutdown();
                    h.close();
                })
                .thenSucceed();
    }
}
