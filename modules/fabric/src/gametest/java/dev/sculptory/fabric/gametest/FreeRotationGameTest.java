package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.deny;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.FlipGameTest.settled;
import static dev.sculptory.fabric.gametest.FlipGameTest.undoAll;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.OrientedStates;
import dev.sculptory.core.state.ResampledCopy;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.state.StateTips;
import dev.sculptory.core.state.TwoCellPairs;
import dev.sculptory.core.transform.CellResampler;
import dev.sculptory.core.transform.CubeTurn;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.EmptyBlockView;

/**
 * Free rotation against a real server: pastes and moves turned by any angle on any axis resample exactly as the
 * reference model says (each result cell the nearest source cell, its state the nearest cube turn), a quarter turn of
 * pitch is an exact permutation, Rotate turns in place as one "Rotate" step, entities turn with the blocks, symmetric
 * copies are exact images, everything undoes exactly, and the refusals hold (permission, limits, protection, unloaded
 * chunks, the build height). The tip tables are checked against vanilla's own outlines. Regions {@code regionCorner}
 * 1200-1206.
 */
public final class FreeRotationGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /**
     * Checks the world against the resampled model: every cell of the result box of {@code size} at {@code targetMin}
     * whose source cell (in {@code before}, a snapshot of the source box) is not air holds that state as the programs
     * resample it ({@link ResampledCopy}: the orientation's method, two-cell blocks whole, smooth slopes); the rest
     * of the box holds what {@code around} says (null: air).
     */
    static void checkResampled(ServerWorld world, StateSpace states, WorldSnapshot before, BlockPos targetMin,
                               Orientation orientation, WorldSnapshot around, String what) {
        checkResampled(world, states, before, targetMin, orientation, around, null, false, what);
    }

    /**
     * {@link #checkResampled}; with {@code move}, the result is a move's (two-cell blocks keep to its landing cells),
     * and a cell of {@code vacated} the result does not land on must be air.
     */
    static void checkResampled(ServerWorld world, StateSpace states, WorldSnapshot before, BlockPos targetMin,
                               Orientation orientation, WorldSnapshot around, Box vacated, boolean move, String what) {
        ResampledCopy copy = model(states, before, orientation, move);
        Box source = before.box;
        CellResampler base = orientation.resampler(source.sizeX(), source.sizeY(), source.sizeZ());
        CellResampler.SourceCells cells = snapshotCells(before, move);
        modelCheck(world, before, targetMin, orientation, around, vacated, what, copy, base, cells);
    }

    /**
     * The source box's snapshot as the programs read it: every cell present, air included for a move ({@code air});
     * a paste that leaves air out reads its air as absent.
     */
    static CellResampler.SourceCells snapshotCells(WorldSnapshot before, boolean air) {
        Box source = before.box;
        return new CellResampler.SourceCells() {
            @Override
            public int state(int x, int y, int z) {
                if (x < 0 || y < 0 || z < 0 || x >= source.sizeX() || y >= source.sizeY() || z >= source.sizeZ()) {
                    return -1;
                }
                int state = before.get(source.min().x() + x, source.min().y() + y, source.min().z() + z);
                return !air && Block.getStateFromRawId(state).isAir() ? -1 : state;
            }

            @Override
            public boolean solid(int state) {
                return !Block.getStateFromRawId(state).isAir();
            }
        };
    }

    /**
     * The programs' own composition of a turned copy of {@code before}: the method's cells, two-cell blocks whole
     * (within a move's landing cells), smooth slopes.
     */
    static ResampledCopy model(StateSpace states, WorldSnapshot before, Orientation orientation, boolean move) {
        Box source = before.box;
        CellResampler base = orientation.resampler(source.sizeX(), source.sizeY(), source.sizeZ());
        CellResampler.SourceCells cells = snapshotCells(before, move);
        int[] taken = new int[3];
        TwoCellPairs.Writable writable = move
                ? (x, y, z) -> base.source(x, y, z, cells, taken) && cells.state(taken[0], taken[1], taken[2]) >= 0
                : (x, y, z) -> true;
        return ResampledCopy.of(base, cells, new OrientedStates(states, orientation), orientation.smoothSlopes(), null,
                ResampledCopy.Tables.shared(states), writable);
    }

    /** The blocks of two-cell blocks a turned copy of {@code before} leaves out for want of room. */
    static int droppedBlocks(StateSpace states, WorldSnapshot before, Orientation orientation, boolean move) {
        return 2 * model(states, before, orientation, move).droppedPairs();
    }

    private static void modelCheck(ServerWorld world, WorldSnapshot before, BlockPos targetMin, Orientation orientation,
                                   WorldSnapshot around, Box vacated, String what, ResampledCopy copy,
                                   CellResampler base, CellResampler.SourceCells cells) {
        CellResampler sampler = copy.cells();
        BlockPos size = sampler.size();
        int[] cell = new int[3];
        int[] baseCell = new int[3];
        for (int y = 0; y < size.y(); y++) {
            for (int z = 0; z < size.z(); z++) {
                for (int x = 0; x < size.x(); x++) {
                    int wx = targetMin.x() + x, wy = targetMin.y() + y, wz = targetMin.z() + z;
                    int got = Block.getRawIdFromState(world.getBlockState(pos(wx, wy, wz)));
                    int want = -1;
                    int air = Block.getRawIdFromState(net.minecraft.block.Blocks.AIR.getDefaultState());
                    if (sampler.source(x, y, z, cells, cell)) {
                        int from = cells.state(cell[0], cell[1], cell[2]);
                        if (from >= 0 && !Block.getStateFromRawId(from).isAir()) want = copy.state(x, y, z, from);
                    } else if (vacated != null && base.source(x, y, z, cells, baseCell)) {
                        // A move's landing cell whose block a kept-whole door or bed took is left as air.
                        want = air;
                    }
                    if (want < 0) {
                        want = around == null || (vacated != null && vacated.contains(wx, wy, wz)) ? air
                                : around.get(wx, wy, wz);
                    }
                    if (got != want) {
                        throw new GameTestException(what + " " + orientation + ": at " + wx + "," + wy + "," + wz
                                + " expected " + Block.getStateFromRawId(want) + ", got " + Block.getStateFromRawId(got));
                    }
                }
            }
        }
    }

    private static int nonAir(ServerWorld world, Box box) {
        int n = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (!world.getBlockState(pos(x, y, z)).isAir()) n++;
                }
            }
        }
        return n;
    }

    private static void run(Harness h, OpSpec op, RunOptions options, RecordingListener listener) {
        try {
            h.service.run(h.player, op, options, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op.getClass().getSimpleName() + " refused: " + e.getMessage());
        }
    }

    private static EditRejected refusal(ClipboardGameTest.ThrowingRun run) {
        return ClipboardGameTest.refusal(run);
    }

    /**
     * The showcase of directional blocks (stairs, slabs, trapdoors, pistons, observers, buttons, levers, doors, logs,
     * chains, a crafter, a jigsaw, plants) pasted with free angles about every axis, a quarter turn of pitch and a
     * mirror: each result cell is its nearest source cell turned to the nearest facing; the quarter pitch keeps every
     * block (an exact permutation); all of it undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_free_paste", tickLimit = LIMIT)
    public void freePastesResampleTheShowcaseAndUndoExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1200);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 5, y0 + 3, z0 + 5);
        List<Orientation> orientations = List.of(
                Orientation.free(Transform.IDENTITY, 3_000, 0, 0),
                Orientation.free(Transform.IDENTITY, 0, Orientation.QUARTER_TURN, 0),
                Orientation.free(Transform.IDENTITY, 0, 0, 4_500),
                Orientation.free(Transform.IDENTITY, 2_000, 3_500, 1_000),
                Orientation.free(new Transform(0, Mirror.X), 1_500, 0, 6_000));
        int spacing = 16;
        Box all = box(x0 - 4, y0 - 2, z0 - 8, x0 + spacing * (orientations.size() + 1) + 8, y0 + 16, z0 + 16);
        loadAndForce(world, all);
        FlipGameTest.build(h, source.min());
        WorldSnapshot before = capture(world, source);
        WorldSnapshot allBefore = capture(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = ClipboardGameTest.copy(clips, h.player, source, source.min());
        List<RecordingListener> pastes = new ArrayList<>();
        List<BlockPos> origins = new ArrayList<>();
        for (int k = 0; k < orientations.size(); k++) origins.add(new BlockPos(x0 + spacing * (k + 1), y0 + 5, z0 + 2));
        context.createTimedTaskRunner()
                .createAndAdd(() -> {
                    UUID clipboard = copied.get("copy").clipboardId();
                    for (int k = 0; k < orientations.size(); k++) {
                        RecordingListener listener = new RecordingListener();
                        run(h, new OpSpec.Paste(new SourceRef.Clipboard(clipboard), origins.get(k), orientations.get(k),
                                PasteOptions.DEFAULT), RunOptions.DEFAULT, listener);
                        pastes.add(listener);
                    }
                })
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .createAndAdd(EntitiesGameTest.once(() -> {
                    for (int k = 0; k < orientations.size(); k++) {
                        Orientation o = orientations.get(k);
                        check(pastes.get(k).result.outcome() == JobOutcome.COMPLETED, "paste " + o + " " + pastes.get(k).result);
                        Box target = PasteGeometry.pasteTarget(new BlockPos(6, 4, 6), BlockPos.ORIGIN, o, origins.get(k));
                        checkResampled(world, h.runtime.states(), before, target.min(), o, null, "paste");
                        if (o.pitch() == Orientation.QUARTER_TURN && o.yaw() == 0 && o.roll() == 0) {
                            // An exact permutation: every block is there once.
                            // An exact permutation: every block is there once, but for a bed that cannot stand on end
                            // (two-cell blocks stay whole or are left out whole).
                            int dropped = droppedBlocks(h.runtime.states(), before, o, false);
                            check(nonAir(world, target) == nonAir(world, source) - dropped, "the quarter pitch keeps "
                                    + "every block: " + nonAir(world, target) + " of " + nonAir(world, source) + " - " + dropped);
                        }
                    }
                    check(h.history().undoLabel().startsWith("Paste"), "history " + h.history().undoLabel());
                }))
                .createAndAdd(undoAll(h, orientations.size()))
                .createAndAdd(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the free pastes");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A free move takes the showcase away turned (the source vacated, the result resampled), then Rotate turns it in
     * place as one step labelled "Rotate"; both undo exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_free_move", tickLimit = LIMIT)
    public void aFreeMoveAndARotateInPlaceUndoExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1201);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0 + 4, y0, z0 + 4, x0 + 9, y0 + 3, z0 + 9);
        Box all = box(x0 - 4, y0 - 4, z0 - 4, x0 + 36, y0 + 12, z0 + 20);
        loadAndForce(world, all);
        FlipGameTest.build(h, source.min());
        WorldSnapshot before = capture(world, source);
        WorldSnapshot allBefore = capture(world, all);
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        Orientation away = Orientation.free(Transform.IDENTITY, 3_000, 1_500, 0);
        Orientation inPlace = Orientation.free(Transform.IDENTITY, 0, 0, 3_000);
        BlockPos offset = new BlockPos(18, 2, 1);
        RecordingListener moved = new RecordingListener();
        RecordingListener rotated = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(EntitiesGameTest.once(() -> run(h, new OpSpec.Move(new Region.Cuboid(source), offset, away,
                        air, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING), RunOptions.DEFAULT, moved)))
                .createAndAdd(() -> check(moved.result != null, "move running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(moved.result.outcome() == JobOutcome.COMPLETED, "move " + moved.result);
                    Orientation.Bounds bounds = away.bounds(6, 4, 6);
                    BlockPos targetMin = source.min().add(bounds.offset()).add(offset);
                    checkResampled(world, h.runtime.states(), before, targetMin, away, null, null, true, "moved away");
                    for (int y = source.min().y(); y <= source.max().y(); y++) {
                        for (int z = source.min().z(); z <= source.max().z(); z++) {
                            for (int x = source.min().x(); x <= source.max().x(); x++) {
                                check(world.getBlockState(pos(x, y, z)).isAir(), "vacated " + x + "," + y + "," + z);
                            }
                        }
                    }
                    check(h.history().undoLabel().startsWith("Move"), "history " + h.history().undoLabel());
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the free move");
                    run(h, new OpSpec.Move(new Region.Cuboid(source), BlockPos.ORIGIN, inPlace, air, EntityFilter.NONE,
                            Symmetry.NONE, PasteOptions.Into.EVERYTHING),
                            new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.ROTATE), rotated);
                }))
                .createAndAdd(() -> check(rotated.result != null, "rotate running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(rotated.result.outcome() == JobOutcome.COMPLETED, "rotate " + rotated.result);
                    Orientation.Bounds bounds = inPlace.bounds(6, 4, 6);
                    // The source box's cells the turned blocks do not land on are air; the rest is the model.
                    checkResampled(world, h.runtime.states(), before, source.min().add(bounds.offset()), inPlace,
                            allBefore, source, true, "rotated in place");
                    check(h.history().undoLabel().startsWith("Rotate"), "the step is labelled Rotate: "
                            + h.history().undoLabel());
                    check(h.history().undoLabels().size() == 1, "one step: " + h.history().undoLabels());
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the rotate");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Entities turn with a free move: an armor stand's position follows the turn exactly and its heading turns by the
     * yaw, a block display composes the turn into its own rotation, item frames and paintings keep the nearest wall;
     * the move undoes exactly. A quarter pitch pasting the same entities lays the item frame on the floor and leaves the
     * painting out (it cannot hang there).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_free_entities", tickLimit = LIMIT)
    public void entitiesTurnWithAFreeMoveAndPaste(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1202);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box all = box(x0 - 8, y0 - 6, z0 - 8, x0 + 56, y0 + 12, z0 + 24);
        loadAndForce(world, all);
        Orientation yaw = Orientation.free(Transform.IDENTITY, 3_000, 0, 0);
        Orientation pitch = Orientation.free(Transform.IDENTITY, 0, Orientation.QUARTER_TURN, 0);
        BlockPos offset = new BlockPos(16, 0, 0);
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        RecordingListener move = new RecordingListener();
        RecordingListener paste = new RecordingListener();
        List<Entity> made = new ArrayList<>();
        List<Map<UUID, dev.sculptory.core.nbt.NbtCompound>> before = new ArrayList<>();
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        BlockPos pasteOrigin = new BlockPos(x0 + 40, y0 + 4, z0 + 8);
        context.createTimedTaskRunner()
                .createAndAdd(() -> EntitiesGameTest.ready(world, all))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    made.addAll(EntitiesGameTest.decorate(h, x0, y0, z0));
                    before.add(EntitiesGameTest.comparable(EntitiesGameTest.entitiesIn(world, source)));
                    EntitiesGameTest.copyInto(copied, clips, h.player, source, source.min(), EntityFilter.ALL);
                    run(h, new OpSpec.Move(new Region.Cuboid(source), offset, yaw, air, EntityFilter.ALL, Symmetry.NONE,
                            PasteOptions.Into.EVERYTHING), RunOptions.DEFAULT, move);
                }))
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    BlockPos destination = source.min().add(yaw.bounds(4, 3, 4).offset()).add(offset);
                    // The armor stand (x0 + 1.5, y0 + 1, z0 + 3.5, yaw 30): its feet mapped exactly, yaw 60.
                    double[] stand = yaw.mapPoint(1.5, 1, 3.5, 4, 3, 4);
                    Entity movedStand = EntitiesGameTest.checkPlaced(world, new EntitiesGameTest.Expected(
                            EntityType.ARMOR_STAND, new net.minecraft.util.math.Vec3d(destination.x() + stand[0],
                            destination.y() + stand[1], destination.z() + stand[2]), null, 60f), "the armor stand");
                    check(((ArmorStandEntity) movedStand).getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD)
                            .isOf(net.minecraft.item.Items.IRON_HELMET), "the helmet came along");
                    // The block display (x0 + 3.5, y0 + 1, z0 + 3.25, yaw 45): its own rotation took the turn, a
                    // half-angle quaternion of -15 degrees about y (the game's axes); its heading stays.
                    double[] display = yaw.mapPoint(3.5, 1, 3.25, 4, 3, 4);
                    Entity movedDisplay = EntitiesGameTest.checkPlaced(world, new EntitiesGameTest.Expected(
                            EntityType.BLOCK_DISPLAY, new net.minecraft.util.math.Vec3d(destination.x() + display[0],
                            destination.y() + display[1], destination.z() + display[2]), null, 45f), "the display");
                    NbtList left = movedDisplay.writeNbt(new NbtCompound()).getCompound("transformation")
                            .getList("left_rotation", NbtElement.FLOAT_TYPE);
                    check(left.size() == 4 && Math.abs(left.getFloat(1) + (float) Math.sin(Math.toRadians(15))) < 1e-4
                            && Math.abs(left.getFloat(3) - (float) Math.cos(Math.toRadians(15))) < 1e-4
                            && Math.abs(left.getFloat(0)) < 1e-4 && Math.abs(left.getFloat(2)) < 1e-4,
                            "the display's left rotation " + left);
                    // The item frame and the painting still face south (30 degrees snaps back to south), where a wall is
                    // still behind them; one whose wall turned away is left out rather than hung on nothing.
                    Box landed = box(destination.x() - 2, destination.y() - 2, destination.z() - 2, destination.x() + 8,
                            destination.y() + 6, destination.z() + 8);
                    List<Entity> there = EntitiesGameTest.entitiesIn(world, landed);
                    long frames = there.stream().filter(e -> e instanceof ItemFrameEntity f
                            && f.getHorizontalFacing() == Direction.SOUTH).count();
                    long paintings = there.stream().filter(e -> e instanceof PaintingEntity p
                            && p.getHorizontalFacing() == Direction.SOUTH).count();
                    check(frames <= 1 && paintings <= 1 && frames + paintings >= 1, "frames " + frames + ", paintings "
                            + paintings + " in " + there);
                    for (Entity e : there) {
                        if (e instanceof net.minecraft.entity.decoration.AbstractDecorationEntity d) {
                            check(d.canStayAttached(), "hung on nothing: " + d);
                        }
                    }
                    for (Entity entity : made) check(entity.isRemoved(), "left behind: " + entity);
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(EntitiesGameTest.comparable(EntitiesGameTest.entitiesIn(world, source)).equals(before.get(0)),
                            "the same entities are back: " + EntitiesGameTest.changedSince(before.get(0),
                                    EntitiesGameTest.comparable(EntitiesGameTest.entitiesIn(world, source))));
                    try {
                        h.service.run(h.player, new OpSpec.Paste(new SourceRef.Clipboard(copied.get("copy").clipboardId()),
                                pasteOrigin, pitch, new PasteOptions(false, false, true)), RunOptions.DEFAULT, paste);
                    } catch (EditRejected e) {
                        throw new GameTestException("paste refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    Box target = PasteGeometry.pasteTarget(new BlockPos(4, 3, 4), BlockPos.ORIGIN, pitch, pasteOrigin);
                    Box around = box(target.min().x() - 2, target.min().y() - 2, target.min().z() - 2,
                            target.max().x() + 2, target.max().y() + 2, target.max().z() + 2);
                    List<Entity> there = EntitiesGameTest.entitiesIn(world, around);
                    long frames = there.stream().filter(e -> e instanceof ItemFrameEntity f
                            && f.getHorizontalFacing() == Direction.DOWN).count();
                    long paintings = there.stream().filter(e -> e instanceof PaintingEntity).count();
                    check(frames == 1, "the frame lies face down: " + there);
                    check(paintings == 0, "the painting cannot hang on a ceiling: " + there);
                    check(there.stream().anyMatch(e -> e instanceof ArmorStandEntity), "the armor stand: " + there);
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(() -> {
                    check(EntitiesGameTest.entitiesIn(world, box(pasteOrigin.x() - 8, pasteOrigin.y() - 8,
                            pasteOrigin.z() - 8, pasteOrigin.x() + 8, pasteOrigin.y() + 8, pasteOrigin.z() + 8)).isEmpty(),
                            "the pasted entities are gone");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Item frames turned by a free angle hang only where a wall is behind them: those whose wall the resampling left
     * diagonal are left out rather than placed to drop off, so after the game's own checks (well past 100 ticks) no
     * item lies on the ground, every frame still hangs, and undo brings back exactly the frames there were (no item
     * twice).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_free_frames", tickLimit = LIMIT)
    public void turnedFramesHangOnlyOnAWallAndDropNothing(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1205);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box wall = box(x0, y0, z0, x0 + 7, y0 + 3, z0 + 1);
        Box all = box(x0 - 8, y0 - 6, z0 - 8, x0 + 40, y0 + 12, z0 + 20);
        loadAndForce(world, all);
        RecordingListener move = new RecordingListener();
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> EntitiesGameTest.ready(world, all))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    var writer = h.runtime.writer(world, dev.sculptory.server.platform.WriteOptions.DEFAULT);
                    for (int x = x0; x <= x0 + 7; x++) {
                        for (int y = y0; y <= y0 + 3; y++) writer.write(x, y, z0, h.state("minecraft:stone"), null);
                    }
                    for (int x = x0; x <= x0 + 7; x++) {
                        for (int y = y0; y <= y0 + 2; y += 2) {
                            ItemFrameEntity frame = new ItemFrameEntity(world, pos(x, y, z0 + 1), Direction.SOUTH);
                            frame.setHeldItemStack(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND), false);
                            check(world.spawnEntity(frame), "a frame spawned");
                        }
                    }
                    run(h, new OpSpec.Move(new Region.Cuboid(wall), new BlockPos(20, 0, 4),
                            Orientation.free(Transform.IDENTITY, 2_000, 0, 0), new Pattern.Single(h.state("minecraft:air")),
                            EntityFilter.DECORATIONS, Symmetry.NONE, PasteOptions.Into.EVERYTHING), RunOptions.DEFAULT, move);
                }))
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(() -> {
                    waited[0]++;
                    check(waited[0] > 140, "letting the game check the frames: " + waited[0]);
                })
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    List<Entity> there = EntitiesGameTest.entitiesIn(world, all);
                    long dropped = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                            new net.minecraft.util.math.Box(x0 - 8, y0 - 6, z0 - 8, x0 + 40, y0 + 12, z0 + 20),
                            e -> true).size();
                    check(dropped == 0, "items on the ground: " + dropped);
                    for (Entity e : there) {
                        if (e instanceof ItemFrameEntity f) check(f.canStayAttached(), "a frame hangs on nothing: " + f);
                    }
                    check(there.stream().anyMatch(e -> e instanceof ItemFrameEntity), "some frames hang: " + there);
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    List<Entity> back = EntitiesGameTest.entitiesIn(world, all);
                    long frames = back.stream().filter(e -> e instanceof ItemFrameEntity f
                            && f.getHeldItemStack().isOf(net.minecraft.item.Items.DIAMOND)).count();
                    long items = world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class,
                            new net.minecraft.util.math.Box(x0 - 8, y0 - 6, z0 - 8, x0 + 40, y0 + 12, z0 + 20),
                            e -> true).size();
                    check(frames == 16 && items == 0, "after undo: " + frames + " frames, " + items + " items");
                    forceChunks(world, all, false);
                    h.close();
                }))
                .completeIfSuccessful();
    }

    /**
     * A free paste and a free move with mirror symmetry: each copy is exactly the mirror image of what the original
     * wrote (even sizes included), one step that undoes exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_free_symmetry", tickLimit = LIMIT)
    public void symmetricCopiesOfFreeEditsAreExactImages(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1203);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 5, y0 + 3, z0 + 5);
        Box all = box(x0 - 4, y0 - 4, z0 - 4, x0 + 60, y0 + 14, z0 + 28);
        loadAndForce(world, all);
        FlipGameTest.build(h, source.min());
        WorldSnapshot allBefore = capture(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = ClipboardGameTest.copy(clips, h.player, source, source.min());
        // The plane x = x0 + 34.5.
        Symmetry mirror = new Symmetry(Symmetry.Mode.MIRROR_X, 2 * (x0 + 34) + 1, 0);
        Orientation o = Orientation.free(Transform.IDENTITY, 2_500, 1_000, 500);
        BlockPos origin = new BlockPos(x0 + 16, y0 + 4, z0 + 14);
        RecordingListener paste = new RecordingListener();
        StateSpace states = h.runtime.states();
        context.createTimedTaskRunner()
                .createAndAdd(() -> run(h, new OpSpec.Paste(new SourceRef.Clipboard(copied.get("copy").clipboardId()),
                        origin, o, PasteOptions.DEFAULT, mirror), RunOptions.DEFAULT, paste))
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(EntitiesGameTest.once(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    Box target = PasteGeometry.pasteTarget(new BlockPos(6, 4, 6), BlockPos.ORIGIN, o, origin);
                    Transform image = Symmetry.Image.MIRROR_X.transform();
                    int written = 0;
                    for (int y = target.min().y(); y <= target.max().y(); y++) {
                        for (int z = target.min().z(); z <= target.max().z(); z++) {
                            for (int x = target.min().x(); x <= target.max().x(); x++) {
                                BlockState state = world.getBlockState(pos(x, y, z));
                                int ix = (int) mirror.cellX(Symmetry.Image.MIRROR_X, x, z);
                                BlockState copy = world.getBlockState(pos(ix, y, z));
                                int want = image.applyToState(states, Block.getRawIdFromState(state));
                                check(Block.getRawIdFromState(copy) == want, "the copy at " + ix + "," + y + "," + z
                                        + " is " + copy + ", the image of " + state);
                                if (!state.isAir()) written++;
                            }
                        }
                    }
                    check(written > 20, "the original wrote " + written);
                    check(h.history().undoLabels().size() == 1, "one step: " + h.history().undoLabels());
                }))
                .createAndAdd(undoAll(h, 1))
                .createAndAdd(() -> {
                    checkSame(settled(allBefore), settled(capture(world, all)), "after undoing the symmetric paste");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Free edits are refused as plain ones are, and leave the world and the history alone: a paste without the
     * clipboard node, a move without the region node, a move over the volume limit, a move into protected columns, a
     * move into chunks nobody loaded (for a player who may not load them), and a turn that would leave the build height.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_free_refusals", tickLimit = LIMIT)
    public void freeEditsAreRefusedLikeOthers(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 1204);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box area = box(x0, y0 - 1, z0, x0 + 31, y0 + 8, z0 + 31);
        loadAndForce(world, area);
        FlipGameTest.build(h, new BlockPos(x0 + 2, y0, z0 + 2));
        WorldSnapshot original = capture(world, area);
        Region region = new Region.Cuboid(box(x0 + 2, y0, z0 + 2, x0 + 7, y0 + 3, z0 + 7));
        Pattern air = new Pattern.Single(h.state("minecraft:air"));
        Orientation o = Orientation.free(Transform.IDENTITY, 3_000, 2_000, 0);
        OpSpec.Move move = new OpSpec.Move(region, new BlockPos(12, 0, 12), o, air, EntityFilter.NONE, Symmetry.NONE,
                PasteOptions.Into.EVERYTHING);

        ServerPlayerEntity noClipboard = h.addPlayer(false);
        EditTestSupport.grant(noClipboard, Perm.USE, Perm.REGION);
        EditRejected paste = refusal(() -> h.service.run(noClipboard, new OpSpec.Paste(new SourceRef.Clipboard(
                new UUID(3, 3)), new BlockPos(x0 + 20, y0, z0 + 20), o, PasteOptions.DEFAULT), RunOptions.DEFAULT, null));
        check(paste.reason() == RejectReason.NO_PERMISSION, "a free paste without the clipboard node: " + paste.reason());
        ServerPlayerEntity noRegion = h.addPlayer(false);
        EditTestSupport.grant(noRegion, Perm.USE, Perm.CLIPBOARD);
        EditRejected noNode = refusal(() -> h.service.run(noRegion, move, RunOptions.DEFAULT, null));
        check(noNode.reason() == RejectReason.NO_PERMISSION, "a free move without the region node: " + noNode.reason());

        // Without limit.bypass (an operator has it).
        ServerPlayerEntity limited = h.addPlayer(false);
        EditTestSupport.grant(limited, Perm.USE, Perm.REGION);
        EditRejected large = refusal(() -> h.service.run(limited, new OpSpec.Move(new Region.Cuboid(box(x0, y0, z0,
                x0 + 199, y0 + 59, z0 + 199)), BlockPos.ORIGIN, o, air, EntityFilter.NONE, Symmetry.NONE,
                PasteOptions.Into.EVERYTHING), RunOptions.DEFAULT, null));
        check(large.reason() == RejectReason.TOO_LARGE, "a free move over the limit: " + large.reason());

        try {
            ProtectionHook.protect(h.player, world, x0 + 14, z0 + 14, x0 + 31, z0 + 31);
            EditRejected guarded = refusal(() -> h.service.run(h.player, move, RunOptions.DEFAULT, null));
            check(guarded.reason() == RejectReason.PROTECTED, "a free move into protected columns: " + guarded.reason());
        } finally {
            ProtectionHook.clear(h.player);
        }

        ServerPlayerEntity grounded = h.addPlayer();
        deny(grounded, Perm.EDIT_UNLOADED);
        EditRejected unloaded = refusal(() -> h.service.run(grounded, new OpSpec.Move(region, new BlockPos(5000, 0, 0), o,
                air, EntityFilter.NONE, Symmetry.NONE, PasteOptions.Into.EVERYTHING), RunOptions.DEFAULT, null));
        check(unloaded.reason() == RejectReason.UNLOADED, "a free move into unloaded chunks: " + unloaded.reason());

        // Rolled a quarter turn, a 32-wide box stands 32 tall about its middle at y0 + 1: through the world's top.
        int top = world.getTopY();
        Region high = new Region.Cuboid(box(x0, top - 8, z0, x0 + 31, top - 6, z0 + 1));
        EditRejected leaves = refusal(() -> h.service.run(h.player, new OpSpec.Move(high, BlockPos.ORIGIN,
                Orientation.free(Transform.IDENTITY, 0, 0, Orientation.QUARTER_TURN), air, EntityFilter.NONE,
                Symmetry.NONE, PasteOptions.Into.EVERYTHING), RunOptions.DEFAULT, null));
        check(leaves.reason() == RejectReason.INVALID, "a turn out of the build height: " + leaves.reason());

        check(h.service.jobs(h.player.getUuid()).isEmpty(), "a refusal left a job");
        check(h.history().undoLabels().isEmpty(), "a refusal pushed history: " + h.history().undoLabels());
        checkSame(original, capture(world, area), "after the refusals");
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /**
     * The tip tables against vanilla's own shapes: every state of the vanilla stairs, trapdoors, buttons, levers, end
     * rods, chains, lightning rods and amethyst clusters that a tip turns (not kept) has the outline of the original
     * turned the same way, sampled at the centres of the cell's 8×8×8 sub-cells.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_free_outlines", tickLimit = LIMIT)
    public void tippedStatesKeepVanillasOutlines(TestContext context) {
        StateSpace states = EngineTestSupport.runtime(context).states();
        List<String> families = List.of("_stairs", "_trapdoor", "_button", "lever", "end_rod", "chain", "lightning_rod",
                "amethyst_cluster", "observer", "piston");
        List<String> wrong = new ArrayList<>();
        int checked = 0;
        for (int h = 0; h < states.size(); h++) {
            BlockState state = Block.getStateFromRawId(h);
            if (state == null || state.isAir()) continue;
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            if (!id.startsWith("minecraft:") || families.stream().noneMatch(id::endsWith)) continue;
            for (CubeTurn.Tip tip : List.of(CubeTurn.Tip.PITCH, CubeTurn.Tip.ROLL)) {
                if (states.tipKind(h, tip) != StateTips.TURNS) continue;
                BlockState tipped = Block.getStateFromRawId(states.tip(h, tip));
                checked++;
                boolean[] want = turned(occupancy(state), tip);
                boolean[] got = occupancy(tipped);
                if (!java.util.Arrays.equals(want, got) && wrong.size() < 12) wrong.add(state + " " + tip + " -> " + tipped);
            }
        }
        check(checked > 500, "checked " + checked);
        check(wrong.isEmpty(), "tipped outlines differ: " + wrong);
        context.complete();
    }

    /** Which of the 8×8×8 sub-cell centres the outline holds, index (x · 8 + y) · 8 + z. */
    private static boolean[] occupancy(BlockState state) {
        VoxelShape shape = state.getOutlineShape(EmptyBlockView.INSTANCE, net.minecraft.util.math.BlockPos.ORIGIN);
        boolean[] out = new boolean[512];
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 8; z++) out[(x * 8 + y) * 8 + z] = inside(shape, x, y, z);
            }
        }
        return out;
    }

    /** {@link #occupancy} turned by the tip: each sub-cell takes the one the tip brings to it (the tip's inverse). */
    private static boolean[] turned(boolean[] occupancy, CubeTurn.Tip tip) {
        int[] m = tip.matrix();
        boolean[] out = new boolean[512];
        for (int x = 0; x < 8; x++) {
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 8; z++) {
                    int vx = 2 * x - 7, vy = 2 * y - 7, vz = 2 * z - 7;
                    // The inverse of a rotation is its transpose.
                    int ux = m[0] * vx + m[3] * vy + m[6] * vz, uy = m[1] * vx + m[4] * vy + m[7] * vz;
                    int uz = m[2] * vx + m[5] * vy + m[8] * vz;
                    out[(x * 8 + y) * 8 + z] = occupancy[(((ux + 7) / 2) * 8 + (uy + 7) / 2) * 8 + (uz + 7) / 2];
                }
            }
        }
        return out;
    }

    private static boolean inside(VoxelShape shape, int x, int y, int z) {
        double px = (x + 0.5) / 8, py = (y + 0.5) / 8, pz = (z + 0.5) / 8;
        for (net.minecraft.util.math.Box b : shape.getBoundingBoxes()) {
            if (px > b.minX && px < b.maxX && py > b.minY && py < b.maxY && pz > b.minZ && pz < b.maxZ) return true;
        }
        return false;
    }
}
