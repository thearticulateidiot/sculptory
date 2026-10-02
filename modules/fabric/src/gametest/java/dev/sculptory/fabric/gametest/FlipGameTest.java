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
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.OpSymmetry;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Direction;

/**
 * The flip upside down against a real server: pastes, moves and stacks
 * turned over (with every turn and mirror, symmetric copies and Paste into), each cell exactly the source cell's state
 * flipped where the flip puts it, doors and two-block plants whole, entities turned over, and every edit undone exactly.
 * Regions {@code regionCorner} 1000-1007.
 */
public final class FlipGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** Blocks with an up or down meaning, blocks with none, and blocks with no upside-down form, in a 6×4×6 box. */
    static final String[][] SHOWCASE = {
            {"0,0,0", "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]"},
            {"1,0,0", "minecraft:oak_stairs[facing=east,half=top,shape=inner_left]"},
            {"2,0,0", "minecraft:stone_slab[type=top]"},
            {"3,0,0", "minecraft:stone_slab[type=bottom]"},
            {"4,0,0", "minecraft:spruce_trapdoor[facing=south,half=bottom,open=false]"},
            {"5,0,0", "minecraft:spruce_trapdoor[facing=west,half=top,open=true]"},
            {"0,1,0", "minecraft:piston[facing=up]"},
            {"1,1,0", "minecraft:observer[facing=down]"},
            {"2,1,0", "minecraft:dispenser[facing=up]"},
            {"3,1,0", "minecraft:end_rod[facing=down]"},
            {"4,1,0", "minecraft:lightning_rod[facing=up]"},
            {"5,1,0", "minecraft:amethyst_cluster[facing=down]"},
            {"0,2,0", "minecraft:barrel[facing=up]"},
            {"1,2,0", "minecraft:hopper[facing=down]"},
            {"2,2,0", "minecraft:stone_button[face=floor,facing=east]"},
            {"3,2,0", "minecraft:lever[face=ceiling,facing=north]"},
            {"4,2,0", "minecraft:grindstone[face=floor,facing=west]"},
            {"5,2,0", "minecraft:bell[attachment=ceiling,facing=north]"},
            {"0,3,0", "minecraft:lantern[hanging=true]"},
            {"1,3,0", "minecraft:soul_lantern[hanging=false]"},
            {"2,3,0", "minecraft:pointed_dripstone[thickness=tip,vertical_direction=down]"},
            {"3,3,0", "minecraft:chain[axis=y]"},
            {"4,3,0", "minecraft:oak_log[axis=x]"},
            {"5,3,0", "minecraft:crafter[orientation=down_east]"},
            {"0,0,2", "minecraft:torch"},
            {"1,0,2", "minecraft:poppy"},
            {"2,0,2", "minecraft:red_bed[facing=north,part=foot]"},
            {"2,0,1", "minecraft:red_bed[facing=north,part=head]"},
            {"3,0,2", "minecraft:white_carpet"},
            {"4,0,2", "minecraft:chest[facing=north,type=single]"},
            {"5,0,2", "minecraft:glass"},
            {"0,1,3", "minecraft:oak_door[facing=east,half=lower,hinge=left,open=false]"},
            {"0,2,3", "minecraft:oak_door[facing=east,half=upper,hinge=left,open=false]"},
            {"2,1,3", "minecraft:sunflower[half=lower]"},
            {"2,2,3", "minecraft:sunflower[half=upper]"},
            {"4,1,3", "minecraft:brown_mushroom_block[up=true,down=false]"},
            {"5,1,3", "minecraft:glow_lichen[down=true,north=true,up=false]"},
            {"0,3,5", "minecraft:jigsaw[orientation=up_north]"},
            {"1,3,5", "minecraft:cobblestone_wall[up=true,north=low]"},
            {"5,3,5", "minecraft:oak_fence"},
    };

    /** Writes {@link #SHOWCASE} with its minimum corner at {@code min}. */
    static void build(Harness h, BlockPos min) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        for (String[] cell : SHOWCASE) {
            String[] xyz = cell[0].split(",");
            writer.write(min.x() + Integer.parseInt(xyz[0]), min.y() + Integer.parseInt(xyz[1]),
                    min.z() + Integer.parseInt(xyz[2]), h.state(cell[1]), null);
        }
    }

    /**
     * Every cell of {@code before} (a snapshot of {@code source}) holds, in the world, {@code t}'s state at the cell
     * {@code t} puts it in the box starting at {@code targetMin}; air cells are left alone ({@code includeAir} off), and
     * nothing else in the box changed from {@code around} (a snapshot of at least the target box before the edit, or null
     * to require air).
     */
    static void checkTransformed(ServerWorld world, StateSpace states, WorldSnapshot before, BlockPos targetMin,
                                 Transform t, WorldSnapshot around, String what) {
        Box source = before.box;
        int sx = source.sizeX(), sy = source.sizeY(), sz = source.sizeZ();
        BlockPos size = t.size(sx, sy, sz);
        int[] expected = new int[Math.toIntExact((long) size.x() * size.y() * size.z())];
        java.util.Arrays.fill(expected, -1);
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int from = before.get(source.min().x() + x, source.min().y() + y, source.min().z() + z);
                    if (Block.getStateFromRawId(from).isAir()) continue;
                    int tx = t.mapX(x, z, sx, sz), ty = t.mapY(y, sy), tz = t.mapZ(x, z, sx, sz);
                    expected[(ty * size.z() + tz) * size.x() + tx] = t.applyToState(states, from);
                }
            }
        }
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    int wx = targetMin.x() + x, wy = targetMin.y() + y, wz = targetMin.z() + z;
                    int got = Block.getRawIdFromState(world.getBlockState(pos(wx, wy, wz)));
                    int want = expected[(y * size.z() + z) * size.x() + x];
                    if (want < 0) want = around == null ? got : around.get(wx, wy, wz);
                    if (got != want) {
                        throw new GameTestException(what + " " + t + ": at " + wx + "," + wy + "," + wz + " expected "
                                + Block.getStateFromRawId(want) + ", got " + Block.getStateFromRawId(got));
                    }
                }
            }
        }
    }

    /**
     * {@code snapshot} without what block entities change by themselves as they tick (a hopper's transfer cooldown), so
     * a snapshot taken before an edit compares with one taken after its undo.
     */
    static WorldSnapshot settled(WorldSnapshot snapshot) {
        WorldSnapshot out = new WorldSnapshot(snapshot.box, snapshot.states);
        snapshot.tiles.forEach((key, nbt) -> {
            net.minecraft.nbt.NbtCompound copy = nbt.copy();
            copy.remove("TransferCooldown");
            out.tiles.put(key, copy);
        });
        return out;
    }

    static void run(Harness h, OpSpec op, RecordingListener listener) {
        try {
            h.service.run(h.player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }

    /**
     * A timed-task step that undoes the player's last {@code count} steps one after another (retried each tick until
     * all are done), each completing without a conflict.
     */
    static Runnable undoAll(Harness h, int count) {
        int[] undone = {0};
        RecordingListener[] current = {null};
        return () -> {
            if (current[0] == null || current[0].result != null) {
                if (current[0] != null) {
                    check(current[0].result.outcome() == JobOutcome.COMPLETED
                            && current[0].result.skippedConflicts() == 0, "undo " + current[0].result);
                    undone[0]++;
                }
                if (undone[0] < count) {
                    current[0] = new RecordingListener();
                    h.undo(current[0]);
                }
            }
            check(undone[0] == count, "undoing: " + undone[0] + " of " + count);
        };
    }

    /**
     * The showcase pasted upside down with every turn and mirror: each cell is its source's state flipped (and turned)
     * where the flip puts it; blocks with no upside-down form keep their state, the door and the sunflower their halves in
     * order; then the eight pastes undo exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_flip_paste", tickLimit = LIMIT)
    public void pasteUpsideDownWithEveryTurnAndMirrorUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1000);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 5, y0 + 3, z0 + 5);
        List<Transform> transforms = new ArrayList<>();
        for (Transform t : Transform.all()) transforms.add(t.withUpsideDown(true));
        int spacing = 12;
        Box all = box(x0 - 8, y0 - 1, z0 - 8, x0 + spacing * (transforms.size() + 1) + 8, y0 + 4, z0 + 13);
        loadAndForce(world, all);
        build(h, source.min());
        WorldSnapshot before = capture(world, source);
        WorldSnapshot allBefore = capture(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = ClipboardGameTest.copy(clips, h.player, source, source.min());
        List<RecordingListener> pastes = new ArrayList<>();
        int half = transforms.size() / 2;
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    for (int k = 0; k < half; k++) {
                        RecordingListener listener = new RecordingListener();
                        ClipboardGameTest.paste(h, h.player, info.clipboardId(),
                                new BlockPos(x0 + spacing * (k + 1), y0 + 3, z0), transforms.get(k), listener);
                        pastes.add(listener);
                    }
                })
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .createAndAdd(() -> {
                    for (int k = half; k < transforms.size(); k++) {
                        RecordingListener listener = new RecordingListener();
                        ClipboardGameTest.paste(h, h.player, copied.value.clipboardId(),
                                new BlockPos(x0 + spacing * (k + 1), y0 + 3, z0), transforms.get(k), listener);
                        pastes.add(listener);
                    }
                })
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .createAndAdd(() -> {
                    for (int k = 0; k < transforms.size(); k++) {
                        Transform t = transforms.get(k);
                        check(pastes.get(k).result.outcome() == JobOutcome.COMPLETED, "paste " + t + " " + pastes.get(k).result);
                        // The anchor (the source's minimum corner) turns over to the top of the box, so it goes 3 up.
                        BlockPos origin = new BlockPos(x0 + spacing * (k + 1), y0 + 3, z0);
                        BlockPos targetMin = PasteGeometry.pasteTarget(new BlockPos(6, 4, 6), BlockPos.ORIGIN, t, origin).min();
                        checkTransformed(world, h.runtime.states(), before, targetMin, t, null, "paste");
                        check(targetMin.y() == y0, t + ": the box starts at " + targetMin);
                    }
                    // The door (east, lower at y 1) turned over: its halves change places and stay a door.
                    BlockPos plain = PasteGeometry.pasteTarget(new BlockPos(6, 4, 6), BlockPos.ORIGIN, transforms.get(0),
                            new BlockPos(x0 + spacing, y0 + 3, z0)).min();
                    BlockState lower = world.getBlockState(pos(plain.x(), plain.y() + 1, plain.z() + 3));
                    BlockState upper = world.getBlockState(pos(plain.x(), plain.y() + 2, plain.z() + 3));
                    check(lower.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER
                            && upper.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER, "door " + lower + " / " + upper);
                    BlockState torch = world.getBlockState(pos(plain.x(), plain.y() + 3, plain.z() + 2));
                    check(torch.isOf(net.minecraft.block.Blocks.TORCH), "the torch stays a torch: " + torch);
                })
                .createAndAdd(undoAll(h, transforms.size()))
                .createAndAdd(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the flipped pastes");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A move upside down in place (the box stays, its contents turn over), then turned and flipped to another place:
     * exact, and each undone exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_flip_move", tickLimit = LIMIT)
    public void moveUpsideDownInPlaceAndAwayUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1001);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 5, y0 + 3, z0 + 5);
        Box all = box(x0 - 4, y0 - 2, z0 - 4, x0 + 24, y0 + 8, z0 + 12);
        loadAndForce(world, all);
        build(h, source.min());
        WorldSnapshot before = capture(world, source);
        WorldSnapshot allBefore = capture(world, all);
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        RecordingListener inPlace = new RecordingListener();
        RecordingListener away = new RecordingListener();
        Transform turned = new Transform(1, Mirror.X, true);
        BlockPos offset = new BlockPos(12, 2, 1);
        context.createTimedTaskRunner()
                .createAndAdd(EntitiesGameTest.once(() -> run(h, new OpSpec.Move(source, BlockPos.ORIGIN,
                        Transform.UPSIDE_DOWN, air), inPlace)))
                .createAndAdd(() -> check(inPlace.result != null, "move running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(inPlace.result.outcome() == JobOutcome.COMPLETED, "move " + inPlace.result);
                    checkTransformed(world, h.runtime.states(), before, source.min(), Transform.UPSIDE_DOWN, null,
                            "in place");
                    check(h.history().undoLabel().startsWith("Move · "), "history " + h.history());
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the move in place");
                    run(h, new OpSpec.Move(source, offset, turned, air), away);
                }))
                .createAndAdd(() -> check(away.result != null, "second move running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(away.result.outcome() == JobOutcome.COMPLETED, "move " + away.result);
                    checkTransformed(world, h.runtime.states(), before, source.min().add(offset), turned, null, "away");
                    for (int y = source.min().y(); y <= source.max().y(); y++) {
                        for (int z = source.min().z(); z <= source.max().z(); z++) {
                            for (int x = source.min().x(); x <= source.max().x(); x++) {
                                check(world.getBlockState(pos(x, y, z)).isAir(), "vacated " + x + "," + y + "," + z);
                            }
                        }
                    }
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the flipped move");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Three flipped copies side by side and two flipped copies stacked upwards, each undone exactly. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_flip_stack", tickLimit = LIMIT)
    public void stackUpsideDownUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1002);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 5, y0 + 3, z0 + 5);
        Box all = box(x0, y0, z0, x0 + 31, y0 + 12, z0 + 5);
        loadAndForce(world, all);
        build(h, source.min());
        WorldSnapshot before = capture(world, source);
        WorldSnapshot allBefore = capture(world, all);
        RecordingListener sideways = new RecordingListener();
        RecordingListener upwards = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(EntitiesGameTest.once(() -> run(h, new OpSpec.Stack(new Region.Cuboid(source), 8, 0, 0, 3,
                        EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING, true), sideways)))
                .createAndAdd(() -> check(sideways.result != null, "stack running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(sideways.result.outcome() == JobOutcome.COMPLETED, "stack " + sideways.result);
                    for (int k = 1; k <= 3; k++) {
                        checkTransformed(world, h.runtime.states(), before, source.min().offset(8 * k, 0, 0),
                                Transform.UPSIDE_DOWN, null, "copy " + k);
                    }
                    checkSame(settled(before), settled(capture(world, source)), "the stack source");
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the sideways stack");
                    run(h, new OpSpec.Stack(new Region.Cuboid(source), 0, 4, 0, 2, EntityFilter.NONE, Symmetry.NONE,
                            PasteOptions.Into.EVERYTHING, true), upwards);
                }))
                .createAndAdd(() -> check(upwards.result != null, "upward stack running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(upwards.result.outcome() == JobOutcome.COMPLETED, "stack " + upwards.result);
                    for (int k = 1; k <= 2; k++) {
                        checkTransformed(world, h.runtime.states(), before, source.min().offset(0, 4 * k, 0),
                                Transform.UPSIDE_DOWN, null, "upward copy " + k);
                    }
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the upward stack");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A flipped paste with a mirror symmetry and Paste into Only air: both copies are the flip (and the image), written
     * only where there was air; one undo takes both back.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_flip_symmetry", tickLimit = LIMIT)
    public void symmetricFlippedPasteIntoAirUndoesExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1003);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 5, y0 + 3, z0 + 5);
        Box all = box(x0 - 2, y0, z0 - 2, x0 + 48, y0 + 8, z0 + 8);
        loadAndForce(world, all);
        build(h, source.min());
        // Something already there where the copies land: Only air leaves it alone.
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int gold = h.state("minecraft:gold_block");
        for (int x = x0 + 12; x <= x0 + 42; x += 3) writer.write(x, y0 + 2, z0 + 1, gold, null);
        WorldSnapshot before = capture(world, source);
        WorldSnapshot allBefore = capture(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = ClipboardGameTest.copy(clips, h.player, source, source.min());
        Transform t = new Transform(3, Mirror.NONE, true);
        Symmetry symmetry = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 27), 2 * z0);
        RecordingListener paste = new RecordingListener();
        List<OpSymmetry.Copy> copies = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> copied.get("copy"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    OpSpec.Paste op = new OpSpec.Paste(new SourceRef.Clipboard(info.clipboardId()),
                            new BlockPos(x0 + 14, y0 + 3, z0 + 5), t, PasteOptions.DEFAULT.withInto(PasteOptions.Into.AIR),
                            symmetry);
                    copies.addAll(OpSymmetry.copies(op));
                    check(copies.size() == 2, "copies " + copies.size());
                    run(h, op, paste);
                }))
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    for (OpSymmetry.Copy copy : copies) {
                        OpSpec.Paste c = (OpSpec.Paste) copy.op();
                        check(c.t().upsideDown(), "the copy is upside down: " + c.t());
                        BlockPos targetMin = PasteGeometry.pasteTarget(new BlockPos(6, 4, 6), BlockPos.ORIGIN, c.t(),
                                c.origin()).min();
                        checkIntoAir(world, h.runtime.states(), before, targetMin, c.t(), allBefore, copy.image().toString());
                    }
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the symmetric flipped paste");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Like {@link #checkTransformed}, but a source block lands only where the world held air before. */
    private static void checkIntoAir(ServerWorld world, StateSpace states, WorldSnapshot before, BlockPos targetMin,
                                     Transform t, WorldSnapshot around, String what) {
        Box source = before.box;
        int sx = source.sizeX(), sy = source.sizeY(), sz = source.sizeZ();
        for (int y = 0; y < sy; y++) {
            for (int z = 0; z < sz; z++) {
                for (int x = 0; x < sx; x++) {
                    int from = before.get(source.min().x() + x, source.min().y() + y, source.min().z() + z);
                    if (Block.getStateFromRawId(from).isAir()) continue;
                    int wx = targetMin.x() + t.mapX(x, z, sx, sz), wy = targetMin.y() + t.mapY(y, sy);
                    int wz = targetMin.z() + t.mapZ(x, z, sx, sz);
                    int was = around.get(wx, wy, wz);
                    int want = Block.getStateFromRawId(was).isAir() ? t.applyToState(states, from) : was;
                    int got = Block.getRawIdFromState(world.getBlockState(pos(wx, wy, wz)));
                    check(got == want, what + " " + t + ": at " + wx + "," + wy + "," + wz + " expected "
                            + Block.getStateFromRawId(want) + ", got " + Block.getStateFromRawId(got));
                }
            }
        }
    }

    /**
     * Entities come along turned over: an item frame on the floor hangs from the ceiling (the floor turned over), one on
     * a wall stays on it at the mirrored height, and an armor stand keeps the space its box had, its feet lowered by its
     * height; after vanilla's support check (100 ticks) the frames still hang; undo takes them away exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_flip_entities", tickLimit = LIMIT)
    public void entitiesComeAlongUpsideDown(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1004);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 3, z0 + 3);
        Box target = box(x0 + 20, y0, z0, x0 + 23, y0 + 3, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 48, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        List<Entity> fixture = new ArrayList<>();
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        RecordingListener paste = new RecordingListener();
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> EntitiesGameTest.ready(world, all))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    fixture.addAll(entityFixture(h, x0, y0, z0));
                    EntitiesGameTest.copyInto(copied, clips, h.player, source, source.min(), EntityFilter.DECORATIONS);
                }))
                .createAndAdd(() -> copied.get("copy"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    check(info.entities() == 3, "the clipboard holds " + info.entities() + " entities");
                    // The anchor (the minimum corner) turns over to the top, so the origin is 3 up for the box to start at y0.
                    EntitiesGameTest.paste(h, info.clipboardId(), target.min().offset(0, 3, 0), Transform.UPSIDE_DOWN,
                            paste);
                }))
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    List<Entity> there = EntitiesGameTest.entitiesIn(world, target);
                    check(there.size() == 3, "3 entities pasted: " + there);
                    checkFlippedEntities(world, target, x0 + 20, y0);
                }))
                .createAndAdd(() -> check(++waited[0] > 110, "waiting past vanilla's support check"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    checkFlippedEntities(world, target, x0 + 20, y0);
                    h.undo(new RecordingListener());
                }))
                .createAndAdd(() -> check(EntitiesGameTest.entitiesIn(world, target).isEmpty(),
                        "undo takes them: " + EntitiesGameTest.entitiesIn(world, target)))
                .createAndAdd(() -> {
                    check(EntitiesGameTest.entitiesIn(world, source).size() == 3, "the source keeps its entities");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A move upside down in place with its entities, and a flipped stack of them: the floor frame hangs from the ceiling
     * the floor turned into, the wall frame keeps its wall, the stand keeps the space its box had; the move undone puts
     * the same entities back where they were.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_flip_entities_move", tickLimit = LIMIT)
    public void movesAndStacksTakeEntitiesAlongUpsideDown(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1006);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 3, z0 + 3);
        Box copyBox = source.offset(8, 0, 0);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 48, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        List<Entity> fixture = new ArrayList<>();
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        RecordingListener move = new RecordingListener();
        RecordingListener stack = new RecordingListener();
        java.util.Map<java.util.UUID, dev.sculptory.core.nbt.NbtCompound> before = new java.util.HashMap<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> EntitiesGameTest.ready(world, all))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    fixture.addAll(entityFixture(h, x0, y0, z0));
                    before.putAll(EntitiesGameTest.comparable(fixture));
                    run(h, new OpSpec.Move(new Region.Cuboid(source), BlockPos.ORIGIN, Transform.UPSIDE_DOWN, air,
                            EntityFilter.DECORATIONS), move);
                }))
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    check(EntitiesGameTest.entitiesIn(world, source).size() == 3, "3 entities moved: "
                            + EntitiesGameTest.entitiesIn(world, source));
                    checkFlippedEntities(world, source, x0, y0);
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(EntitiesGameTest.comparable(EntitiesGameTest.entitiesIn(world, source)).equals(before),
                            "the undo puts back the same entities");
                    run(h, new OpSpec.Stack(new Region.Cuboid(source), 8, 0, 0, 1, EntityFilter.DECORATIONS,
                            Symmetry.NONE, PasteOptions.Into.EVERYTHING, true), stack);
                }))
                .createAndAdd(() -> check(stack.result != null, "stack running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(stack.result.outcome() == JobOutcome.COMPLETED, "stack " + stack.result);
                    check(EntitiesGameTest.entitiesIn(world, copyBox).size() == 3, "3 entities stacked: "
                            + EntitiesGameTest.entitiesIn(world, copyBox));
                    checkFlippedEntities(world, copyBox, x0 + 8, y0);
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(() -> {
                    check(EntitiesGameTest.entitiesIn(world, copyBox).isEmpty(), "the undo takes the stacked entities");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A selection reaching 4 blocks past the top of the world, the fixture in its top 4 rows: turned over within the
     * selection's whole bounds (the pivot the client shows), the part inside the world lands 4 higher, so moved upside
     * down by -4 it stays in place and stacked upside down by -8 the copy is 4 lower. The entities land in the cells
     * their blocks land in (the edit service's destination and the entity jobs follow the same pivot as the block
     * programs), and both undo exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_flip_entities_height", tickLimit = LIMIT)
    public void movesAndStacksPastTheBuildHeightTakeEntitiesAlongUpsideDown(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1007);
        int top = world.getTopY() - 1;
        int x0 = at[0], y0 = top - 3, z0 = at[1];
        Box fixture = box(x0, y0, z0, x0 + 3, y0 + 3, z0 + 3);
        Box selection = box(x0, y0, z0, x0 + 3, y0 + 7, z0 + 3);
        Box copyBox = fixture.offset(0, -4, 0);
        Box all = box(x0 - 16, y0 - 12, z0 - 16, x0 + 16, top, z0 + 16);
        loadAndForce(world, all);
        List<Entity> entities = new ArrayList<>();
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        RecordingListener move = new RecordingListener();
        RecordingListener stack = new RecordingListener();
        java.util.Map<java.util.UUID, dev.sculptory.core.nbt.NbtCompound> before = new java.util.HashMap<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> EntitiesGameTest.ready(world, all))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    entities.addAll(entityFixture(h, x0, y0, z0));
                    before.putAll(EntitiesGameTest.comparable(entities));
                    run(h, new OpSpec.Move(new Region.Cuboid(selection), new BlockPos(0, -4, 0), Transform.UPSIDE_DOWN,
                            air, EntityFilter.DECORATIONS), move);
                }))
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    checkFloorAndWall(world, x0, y0, z0, "moved");
                    check(EntitiesGameTest.entitiesIn(world, fixture).size() == 3, "3 entities moved: "
                            + EntitiesGameTest.entitiesIn(world, fixture));
                    checkFlippedEntities(world, fixture, x0, y0);
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(EntitiesGameTest.comparable(EntitiesGameTest.entitiesIn(world, fixture)).equals(before),
                            "the undo puts back the same entities");
                    check(isStone(world, x0 + 1, y0, z0 + 2) && !isStone(world, x0 + 1, y0 + 3, z0 + 2),
                            "the undo puts the floor back");
                    run(h, new OpSpec.Stack(new Region.Cuboid(selection), 0, -8, 0, 1, EntityFilter.DECORATIONS,
                            Symmetry.NONE, PasteOptions.Into.EVERYTHING, true), stack);
                }))
                .createAndAdd(() -> check(stack.result != null, "stack running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(stack.result.outcome() == JobOutcome.COMPLETED, "stack " + stack.result);
                    checkFloorAndWall(world, x0, y0 - 4, z0, "stacked");
                    check(EntitiesGameTest.entitiesIn(world, copyBox).size() == 3, "3 entities stacked: "
                            + EntitiesGameTest.entitiesIn(world, copyBox));
                    checkFlippedEntities(world, copyBox, x0, y0 - 4);
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(() -> {
                    check(EntitiesGameTest.entitiesIn(world, copyBox).isEmpty(), "the undo takes the stacked entities");
                    check(!isStone(world, x0 + 1, y0 - 1, z0 + 2), "the undo takes the stacked floor");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** The fixture's floor and wall turned over into the box starting at (x0, y, z0): the floor on top, the wall whole. */
    private static void checkFloorAndWall(ServerWorld world, int x0, int y, int z0, String what) {
        check(isStone(world, x0 + 1, y + 3, z0 + 2) && !isStone(world, x0 + 1, y, z0 + 2), what + ": the floor is on top");
        check(isStone(world, x0 + 2, y, z0) && isStone(world, x0 + 2, y + 3, z0), what + ": the wall is whole");
    }

    private static boolean isStone(ServerWorld world, int x, int y, int z) {
        return world.getBlockState(pos(x, y, z)).isOf(net.minecraft.block.Blocks.STONE);
    }

    /**
     * A stone floor and a wall along x at {@code z0} (a box 4×4×4 from (x0, y0, z0)), a diamond item frame on the floor,
     * an empty one on the wall and an armor stand (no gravity, pitch 20) on the floor; spawned.
     */
    private static List<Entity> entityFixture(Harness h, int x0, int y0, int z0) {
        ServerWorld world = h.world;
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = x0; x <= x0 + 3; x++) {
            for (int z = z0; z <= z0 + 3; z++) writer.write(x, y0, z, stone, null);
            for (int y = y0; y <= y0 + 3; y++) writer.write(x, y, z0, stone, null);
        }
        List<Entity> fixture = new ArrayList<>();
        ItemFrameEntity floor = new ItemFrameEntity(world, pos(x0 + 1, y0 + 1, z0 + 2), Direction.UP);
        floor.setHeldItemStack(new ItemStack(Items.DIAMOND), false);
        ItemFrameEntity wall = new ItemFrameEntity(world, pos(x0 + 2, y0 + 1, z0 + 1), Direction.SOUTH);
        ArmorStandEntity stand = new ArmorStandEntity(world, x0 + 2.5, y0 + 1, z0 + 2.5);
        stand.setPitch(20f);
        stand.setNoGravity(true);
        fixture.add(floor);
        fixture.add(wall);
        fixture.add(stand);
        for (Entity entity : fixture) check(world.spawnEntity(entity), "fixture " + entity + " spawned");
        return fixture;
    }

    /** The fixture of {@link #entitiesComeAlongUpsideDown} pasted upside down with its box starting at (x, y). */
    private static void checkFlippedEntities(ServerWorld world, Box target, int x, int y) {
        int frames = 0;
        for (Entity entity : EntitiesGameTest.entitiesIn(world, target)) {
            switch (entity) {
                case ItemFrameEntity frame when frame.getHeldItemStack().isOf(Items.DIAMOND) -> {
                    // On the floor at local y 1 (the cell above the floor at 0); turned over, local y 2, on the ceiling.
                    check(frame.getHorizontalFacing() == Direction.DOWN, "the floor frame faces " + frame.getHorizontalFacing());
                    check(frame.getBlockPos().equals(pos(x + 1, y + 2, target.min().z() + 2)), "floor frame at "
                            + frame.getBlockPos().toShortString());
                    frames++;
                }
                case ItemFrameEntity frame -> {
                    check(frame.getHorizontalFacing() == Direction.SOUTH, "the wall frame faces " + frame.getHorizontalFacing());
                    check(frame.getBlockPos().equals(pos(x + 2, y + 2, target.min().z() + 1)), "wall frame at "
                            + frame.getBlockPos().toShortString());
                    frames++;
                }
                case ArmorStandEntity stand -> {
                    // Feet at local 1.0 in a box 4 tall: the image is 3.0, less the stand's height.
                    double feet = y + 4 - 1.0 - stand.getHeight();
                    check(Math.abs(stand.getY() - feet) < 1e-6, "the stand stands at " + stand.getY() + ", expected " + feet);
                    check(Math.abs(stand.getPitch() + 20f) < 1e-3, "the stand's pitch turns over: " + stand.getPitch());
                }
                default -> throw new GameTestException("unexpected " + entity);
            }
        }
        check(frames == 2, "two frames: " + frames);
    }
}
