package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.refusal;
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
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.generate.FaceCells;
import dev.sculptory.core.generate.GeneratedResult;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.PathExtrudeKernel;
import dev.sculptory.core.generate.SparseUpload;
import dev.sculptory.core.generate.SweepKernel;
import dev.sculptory.core.generate.TaperKernel;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.path.PathKind;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.OpLabel;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.platform.WriteOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * Extrude along a path and to a point, Generate's Sweep and Stamp on a real world: the kernels' cells, uploaded as the
 * client does and pasted with the tool's label, land exactly and undo exactly as one step; block entities in a face
 * are left out and keep their contents; a stamp keeps a chest's contents and its entities, turns its copies, undoes
 * exactly (entities too); refusals without the clipboard node, over the volume limit and into unloaded chunks write
 * nothing; overlapping stamps write as their pastes in turn and undo exactly; a free-angle stamp with symmetry builds the
 * exact image and undoes exactly, turns its entity, gives way to the original where its image meets it, and is refused
 * when its image leaves the world. Region slots 1300-1318.
 */
public final class ExtrudePathsGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** Stone ground at y 96-99 over the area. */
    static void ground(Harness h, Box area) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = area.min().x(); x <= area.max().x(); x++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int y = 96; y <= 99; y++) writer.write(x, y, z, stone, null);
            }
        }
    }

    /** A chest holding {@code count} diamonds at (x, y, z). */
    private static void chest(Harness h, int x, int y, int z, int count) {
        h.world.setBlockAndUpdate(pos(x, y, z), net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
        ChestBlockEntity chest = (ChestBlockEntity) h.world.getBlockEntity(pos(x, y, z));
        chest.setItem(0, new ItemStack(Items.DIAMOND, count));
    }

    private static void checkChest(Harness h, int x, int y, int z, int count, String what) {
        if (!(h.world.getBlockEntity(pos(x, y, z)) instanceof ChestBlockEntity chest)) {
            throw new GameTestAssertException(what + ": no chest at " + x + "," + y + "," + z);
        }
        ItemStack stack = chest.getItem(0);
        check(stack.is(Items.DIAMOND) && stack.getCount() == count, what + ": the chest holds " + stack);
    }

    /** Every cell of {@code area} holds its source cell, else what {@code before} held. */
    private static void checkBuilt(ServerLevel world, Box area, GeneratedSource source, WorldSnapshot before, String what) {
        WorldSnapshot now = capture(world, area);
        for (int y = area.min().y(); y <= area.max().y(); y++) {
            for (int z = area.min().z(); z <= area.max().z(); z++) {
                for (int x = area.min().x(); x <= area.max().x(); x++) {
                    int generated = source.get(x, y, z);
                    int want = generated >= 0 ? generated : before.get(x, y, z);
                    if (want != now.get(x, y, z)) {
                        throw new GameTestAssertException(what + " at " + x + "," + y + "," + z + ": "
                                + Block.stateById(now.get(x, y, z)) + " instead of " + Block.stateById(want));
                    }
                }
            }
        }
    }

    /**
     * Uploads {@code source} as the client's generated upload, pastes it with "include air" under {@code label}, checks
     * the cells and the history name, undoes and checks every cell is back; {@code extra} runs after the build and after
     * the undo (true then).
     */
    static void buildAndUndo(GameTestHelper context, Harness h, Box area, GeneratedSource source, OpLabel label,
                                     Consumer<Boolean> extra) {
        WorldSnapshot before = capture(h.world, area);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        byte[] payload = SparseUpload.encode(source, h.runtime.states());
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        try {
            clips.beginGeneratedUpload(h.player, source.bounds(), source.cells(), payload.length).completed(payload, uploaded);
        } catch (EditRejected e) {
            throw new GameTestAssertException("upload refused: " + e.getMessage());
        }
        RecordingListener pasted = new RecordingListener(), undone = new RecordingListener();
        context.startSequence()
                .thenWaitUntil(() -> check(uploaded.finished(), "upload running"))
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("the upload");
                    try {
                        h.service.run(h.player, new OpSpec.Paste(new SourceRef.Clipboard(info.clipboardId()),
                                source.bounds().min(), Transform.IDENTITY, new PasteOptions(true, false, false)),
                                new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS, label), pasted);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("the paste was refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> check(pasted.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    check(pasted.result.outcome() == JobOutcome.COMPLETED, "paste " + pasted.result);
                    checkBuilt(h.world, area, source, before, label.text());
                    check(h.history().undoLabel().startsWith(label.text() + " · "), "history " + h.history().undoLabels());
                    check(h.history().undoLabels().size() == 1, "one step: " + h.history().undoLabels());
                    extra.accept(false);
                    h.undo(undone);
                })
                .thenWaitUntil(() -> check(undone.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undone.result.outcome() == JobOutcome.COMPLETED && undone.result.skippedConflicts() == 0,
                            "undo " + undone.result);
                    checkSame(before, capture(h.world, area), "after the undo of " + label.text());
                    extra.accept(true);
                    forceChunks(h.world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** The face of cells {@code (x, y, z)} as the Extrude tool reads it from the world. */
    private static FaceCells face(Harness h, int axis, int sign, List<int[]> cells) {
        int n = cells.size();
        int[] xs = new int[n], ys = new int[n], zs = new int[n], states = new int[n];
        for (int i = 0; i < n; i++) {
            int[] c = cells.get(i);
            xs[i] = c[0];
            ys[i] = c[1];
            zs[i] = c[2];
            states[i] = Block.getId(h.world.getBlockState(pos(c[0], c[1], c[2])));
        }
        return new FaceCells(axis, sign, xs, ys, zs, states);
    }

    /**
     * A 3 × 3 floor of stone bricks with a chest of diamonds in its middle, carried up and then along a slope: the cells are
     * the kernel's, the chest's column is left out (counted), the chest keeps its diamonds, and the undo is exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_extrude_path", timeoutTicks = LIMIT)
    public void aFaceCarriedAlongAPathBuildsAndUndoesExactlyLeavingItsChestOut(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1300);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 24, 120, z0 + 24);
        loadAndForce(h.world, area);
        ground(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int bricks = h.state("minecraft:stone_bricks");
        List<int[]> cells = new ArrayList<>();
        for (int x = x0; x <= x0 + 2; x++) {
            for (int z = z0; z <= z0 + 2; z++) {
                writer.write(x, 100, z, bricks, null);
                cells.add(new int[] {x, 100, z});
            }
        }
        chest(h, x0 + 1, 100, z0 + 1, 7);
        FaceCells face = face(h, 1, 1, cells);
        GeneratedResult result = PathExtrudeKernel.generate(new PathExtrudeKernel.Spec(face,
                List.of(new BlockPos(x0 + 1, 106, z0 + 1), new BlockPos(x0 + 12, 110, z0 + 8)), PathKind.STRAIGHT, 0, false),
                h.runtime.states(), 1 << 20, h.world.getMinBuildHeight(), h.world.getMaxBuildHeight());
        check(result.leftOut() == 1, "the chest is left out: " + result.leftOut());
        GeneratedSource source = result.source();
        check(!source.contains(x0 + 1, 101, z0 + 1), "nothing over the chest");
        check(source.contains(x0, 101, z0) && source.get(x0, 101, z0) == bricks, "the first layer is the face's blocks");
        buildAndUndo(context, h, area, source, OpLabel.PATH_EXTRUDE, undone -> checkChest(h, x0 + 1, 100, z0 + 1, 7,
                undone ? "after the undo" : "after the build"));
    }

    /** A wall of 5 × 5 tapered to an off-centre tip: a leaning spire that ends on its tip, undone exactly. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_extrude_taper", timeoutTicks = LIMIT)
    public void aFaceTaperedToAPointBuildsALeaningSpireAndUndoesExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1302);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 2, 96, z0 - 2, x0 + 16, 112, z0 + 10);
        loadAndForce(h.world, area);
        ground(h, area);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int bricks = h.state("minecraft:stone_bricks");
        List<int[]> cells = new ArrayList<>();
        for (int y = 100; y <= 104; y++) {
            for (int z = z0; z <= z0 + 4; z++) {
                writer.write(x0, y, z, bricks, null);
                cells.add(new int[] {x0, y, z});
            }
        }
        BlockPos tip = new BlockPos(x0 + 10, 106, z0 + 2);
        GeneratedSource source = TaperKernel.generate(new TaperKernel.Spec(face(h, 0, 1, cells), tip,
                TaperKernel.Curve.STRAIGHT), h.runtime.states(), 1 << 20, h.world.getMinBuildHeight(), h.world.getMaxBuildHeight()).source();
        check(source.contains(tip.x(), tip.y(), tip.z()), "it ends on its tip");
        buildAndUndo(context, h, area, source, OpLabel.TAPER, undone -> {});
    }

    /** A wall profile with a stair cap swept round a bend: the kernel's cells, its stairs turned, undone exactly. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_generate_sweep", timeoutTicks = LIMIT)
    public void aProfileSweptAlongAPathBuildsAndUndoesExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1304);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 30, 108, z0 + 30);
        loadAndForce(h.world, area);
        ground(h, area);
        int bricks = h.state("minecraft:stone_bricks");
        int stairs = h.state("minecraft:stone_brick_stairs[facing=east]");
        int[] xs = new int[9], ys = new int[9], zs = new int[9], states = new int[9];
        int i = 0;
        for (int y = 0; y <= 2; y++) {
            for (int z = -1; z <= 1; z++) {
                xs[i] = x0 + 28;
                ys[i] = 100 + y;
                zs[i] = z0 + 28 + z;
                states[i++] = y == 2 ? stairs : bricks;
            }
        }
        GeneratedSource source = SweepKernel.generate(new SweepKernel.Spec(new SweepKernel.Profile(xs, ys, zs, states),
                List.of(new BlockPos(x0, 99, z0), new BlockPos(x0 + 12, 99, z0), new BlockPos(x0 + 12, 99, z0 + 12)),
                PathKind.STRAIGHT, 0, false), h.runtime.states(), 1 << 20, h.world.getMinBuildHeight(), h.world.getMaxBuildHeight())
                .source();
        check(source.get(x0 + 4, 102, z0) == stairs, "the cap heads east as built");
        check(source.get(x0 + 12, 102, z0 + 8) == h.state("minecraft:stone_brick_stairs[facing=south]"),
                "the cap turned south with the path");
        buildAndUndo(context, h, area, source, OpLabel.SWEEP, undone -> {});
    }

    /**
     * A lamp post (stone, a chest of diamonds, an armor stand) copied with its entities and stamped three times, the
     * middle copy turned a quarter: one "Stamp" step; each copy has its chest with the diamonds and its armor stand;
     * the undo puts every block back and takes the three stands away.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_generate_stamp", timeoutTicks = LIMIT)
    public void aStampKeepsChestsAndEntitiesTurnsItsCopiesAndUndoesExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1306);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 40, 108, z0 + 20);
        loadAndForce(world, area);
        ground(h, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        writer.write(x0, 100, z0, stone, null);
        writer.write(x0, 101, z0, stone, null);
        chest(h, x0 + 1, 100, z0, 5);
        ArmorStand stand = EntityType.ARMOR_STAND.create(world);
        stand.moveTo(x0 + 0.5, 102, z0 + 0.5, 0, 0);
        world.addFreshEntity(stand);
        Box post = box(x0, 100, z0, x0 + 1, 102, z0);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        Box targets = box(x0 + 8, 96, z0 + 8, x0 + 36, 108, z0 + 14);
        WorldSnapshot[] before = new WorldSnapshot[1];
        RecordingListener stamped = new RecordingListener(), undone = new RecordingListener();
        net.minecraft.world.phys.AABB standArea = new net.minecraft.world.phys.AABB(targets.min().x(), 96, targets.min().z(),
                targets.max().x() + 1, 110, targets.max().z() + 1);
        context.startSequence()
                .thenWaitUntil(() -> EntitiesGameTest.ready(world, area))
                .thenWaitUntil(() -> {
                    try {
                        clips.copy(h.player, new Region.Cuboid(post), post.min(), false, CellMask.ANY, EntityFilter.ALL,
                                null, copied);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("copy refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("the copy");
                    check(info.entities() == 1, "the clipboard holds the stand: " + info.entities());
                    before[0] = capture(world, targets);
                    OpSpec.PasteMany stamp = new OpSpec.PasteMany(new SourceRef.Clipboard(info.clipboardId()), List.of(
                            new OpSpec.PasteMany.Placement(new BlockPos(x0 + 10, 100, z0 + 10), Orientation.IDENTITY),
                            new OpSpec.PasteMany.Placement(new BlockPos(x0 + 20, 100, z0 + 10),
                                    Orientation.of(Transform.rotation(1))),
                            new OpSpec.PasteMany.Placement(new BlockPos(x0 + 30, 100, z0 + 10), Orientation.IDENTITY)),
                            new PasteOptions(false, false, true));
                    try {
                        h.service.run(h.player, stamp, new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.STAMP),
                                stamped);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("the stamp was refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> check(stamped.result != null, "stamp running"))
                .thenWaitUntil(() -> {
                    check(stamped.result.outcome() == JobOutcome.COMPLETED, "stamp " + stamped.result);
                    check(h.history().undoLabel().startsWith("Stamp · ") && h.history().undoLabels().size() == 1,
                            "one Stamp step: " + h.history().undoLabels());
                    for (int x : new int[] {x0 + 10, x0 + 30}) {
                        check(world.getBlockState(pos(x, 101, z0 + 10)).is(net.minecraft.world.level.block.Blocks.STONE), "post at " + x);
                        checkChest(h, x + 1, 100, z0 + 10, 5, "the copy at " + x);
                    }
                    // Turned a quarter clockwise: the chest east of the post now lies south of it.
                    check(world.getBlockState(pos(x0 + 20, 101, z0 + 10)).is(net.minecraft.world.level.block.Blocks.STONE), "turned post");
                    checkChest(h, x0 + 20, 100, z0 + 11, 5, "the turned copy");
                    int stands = world.getEntitiesOfClass(ArmorStand.class, standArea, e -> true).size();
                    check(stands == 3, "three stamped stands, found " + stands);
                    h.undo(undone);
                })
                .thenWaitUntil(() -> check(undone.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undone.result.outcome() == JobOutcome.COMPLETED, "undo " + undone.result);
                    checkSame(before[0], capture(world, targets), "after the undo");
                    int stands = world.getEntitiesOfClass(ArmorStand.class, standArea, e -> true).size();
                    check(stands == 0, "the undo took the stamped stands away, found " + stands);
                    check(!stand.isRemoved(), "the original stand stays");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * Refusals write nothing and push nothing: a stamp without the clipboard node (use and region are not enough), a
     * stamp over the volume limit counted once per placement (each placement alone fits), a stamp with one placement
     * in a chunk that is not loaded (the others are not written either), and a generated upload without the clipboard
     * node.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_generate_stamp_refusals", timeoutTicks = LIMIT)
    public void refusedStampsAndUploadsWriteNothing(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1308);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 96, z0, x0 + 47, 140, z0 + 47);
        loadAndForce(world, area);
        ground(h, area);
        ServerPlayer builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION, Perm.CLIPBOARD);
        ServerPlayer noClipboard = h.addPlayer(false);
        EditTestSupport.grant(noClipboard, Perm.USE, Perm.REGION);
        // A 32 × 16 × 32 block of stone (16,384 cells) held as the builder's clipboard.
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        dev.sculptory.core.clipboard.Clipboard.Builder big = dev.sculptory.core.clipboard.Clipboard.builder(
                h.runtime.states(), new BlockPos(32, 16, 32));
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 32; z++) big.set(x, y, z, stone);
            }
        }
        UUID clipboardId = h.service.clipboards().install(builder.getUUID(), big.build()).id();
        h.service.clipboards().install(noClipboard.getUUID(), dev.sculptory.core.clipboard.Clipboard.builder(
                h.runtime.states(), new BlockPos(1, 1, 1)).set(0, 0, 0, stone).build());
        long limit = h.runtime.config().limits.maxOpVolume;
        int placements = (int) (limit / (32 * 16 * 32)) + 1;
        check(placements <= OpSpec.PasteMany.MAX_PLACEMENTS && 32 * 16 * 32 <= limit, "the fixture fits: " + placements);
        WorldSnapshot before = capture(world, box(x0, 96, z0, x0 + 47, 140, z0 + 47));
        List<String> undoBefore = h.service.history(builder).undoLabels();
        SourceRef source = new SourceRef.Clipboard(clipboardId);
        List<OpSpec.PasteMany.Placement> many = new ArrayList<>();
        for (int i = 0; i < placements; i++) {
            many.add(new OpSpec.PasteMany.Placement(new BlockPos(x0 + 8, 100 + (i % 2) * 20, z0 + 8), Orientation.IDENTITY));
        }
        EditRejected tooLarge = refusal(() -> h.service.run(builder, new OpSpec.PasteMany(source, many, PasteOptions.DEFAULT),
                RunOptions.DEFAULT, null));
        check(tooLarge.reason() == RejectReason.TOO_LARGE, "over the volume once per placement: " + tooLarge.reason());

        EditRejected node = refusal(() -> h.service.run(noClipboard, new OpSpec.PasteMany(new SourceRef.Clipboard(
                h.service.clipboards().get(noClipboard.getUUID()).orElseThrow().id()), List.of(new OpSpec.PasteMany.Placement(
                new BlockPos(x0 + 8, 100, z0 + 8), Orientation.IDENTITY)), PasteOptions.DEFAULT), RunOptions.DEFAULT, null));
        check(node.reason() == RejectReason.NO_PERMISSION && Perm.CLIPBOARD.node().equals(node.detail()),
                "without the clipboard node: " + node.reason() + " " + node.detail());

        // One placement far away, where no chunk is loaded: the whole stamp is refused.
        int farX = x0 + 4096 * 40;
        check(!world.hasChunk(farX >> 4, z0 >> 4), "the far chunk is not loaded");
        EditRejected unloaded = refusal(() -> h.service.run(builder, new OpSpec.PasteMany(source, List.of(
                new OpSpec.PasteMany.Placement(new BlockPos(x0 + 8, 100, z0 + 8), Orientation.IDENTITY),
                new OpSpec.PasteMany.Placement(new BlockPos(farX, 100, z0 + 8), Orientation.IDENTITY)),
                PasteOptions.DEFAULT), RunOptions.DEFAULT, null));
        check(unloaded.reason() == RejectReason.UNLOADED, "a placement in an unloaded chunk: " + unloaded.reason());

        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        GeneratedSource generated = GeneratedSource.builder(10).set(x0 + 2, 101, z0 + 2, stone).build();
        check(refusal(() -> clips.beginGeneratedUpload(noClipboard, generated.bounds(), 1, 64)).reason()
                == RejectReason.NO_PERMISSION, "a generated upload without the clipboard node");

        checkSame(before, capture(world, box(x0, 96, z0, x0 + 47, 140, z0 + 47)), "after the refusals");
        check(h.service.history(builder).undoLabels().equals(undoBefore), "a refusal pushed history");
        check(h.service.jobs(builder.getUUID()).isEmpty(), "a refusal left a job");
        forceChunks(world, area, false);
        h.close();
        context.succeed();
    }

    /**
     * Overlapping stamps (one turned, one flipped upside down) write what their pastes would, one after another, the
     * later winning; the undo puts every cell back exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_generate_stamp_overlap", timeoutTicks = LIMIT)
    public void overlappingStampsWriteAsTheirPastesInTurnAndUndoExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1310);
        int x0 = at[0], z0 = at[1];
        int apart = 32;
        Box area = box(x0, 96, z0, x0 + apart + 15, 108, z0 + 15);
        loadAndForce(world, area);
        ground(h, area);
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt");
        int stairs = h.state("minecraft:stone_brick_stairs[facing=east]");
        dev.sculptory.core.clipboard.Clipboard.Builder blocks = dev.sculptory.core.clipboard.Clipboard.builder(
                h.runtime.states(), new BlockPos(3, 2, 3));
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                blocks.set(x, 0, z, (x + z) % 2 == 0 ? stone : dirt);
                if (x == 1) blocks.set(x, 1, z, stairs);
            }
        }
        UUID id = h.service.clipboards().install(h.player.getUUID(), blocks.build()).id();
        List<OpSpec.PasteMany.Placement> placements = List.of(
                new OpSpec.PasteMany.Placement(new BlockPos(x0 + 4, 100, z0 + 4), Orientation.IDENTITY),
                new OpSpec.PasteMany.Placement(new BlockPos(x0 + 5, 100, z0 + 5), Orientation.of(Transform.rotation(1))),
                new OpSpec.PasteMany.Placement(new BlockPos(x0 + 4, 101, z0 + 5), Orientation.of(Transform.UPSIDE_DOWN)));
        Box stampBox = box(x0, 98, z0, x0 + 15, 106, z0 + 15);
        Box pastesBox = box(x0 + apart, 98, z0, x0 + apart + 15, 106, z0 + 15);
        WorldSnapshot before = capture(world, stampBox);
        List<RecordingListener> pastes = new ArrayList<>();
        RecordingListener stamped = new RecordingListener(), undone = new RecordingListener();
        SourceRef source = new SourceRef.Clipboard(id);
        var runner = context.startSequence();
        // The same placements, apart, as pastes one after another, each once the one before has finished.
        for (OpSpec.PasteMany.Placement placement : placements) {
            runner.thenWaitUntil(() -> {
                for (RecordingListener listener : pastes) check(listener.result != null, "the paste before is running");
                RecordingListener listener = new RecordingListener();
                try {
                    h.service.run(h.player, new OpSpec.Paste(source, placement.origin().offset(apart, 0, 0),
                            placement.orientation(), PasteOptions.DEFAULT), RunOptions.DEFAULT, listener);
                } catch (EditRejected e) {
                    throw new GameTestAssertException("a paste was refused: " + e.getMessage());
                }
                pastes.add(listener);
            });
        }
        runner
                .thenWaitUntil(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                    try {
                        h.service.run(h.player, new OpSpec.PasteMany(source, placements, PasteOptions.DEFAULT),
                                new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.STAMP), stamped);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("the stamp was refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> check(stamped.result != null, "stamp running"))
                .thenWaitUntil(() -> {
                    check(stamped.result.outcome() == JobOutcome.COMPLETED, "stamp " + stamped.result);
                    WorldSnapshot stampedCells = capture(world, stampBox), pasted = capture(world, pastesBox);
                    for (int y = 98; y <= 106; y++) {
                        for (int z = z0; z <= z0 + 15; z++) {
                            for (int x = x0; x <= x0 + 15; x++) {
                                if (stampedCells.get(x, y, z) != pasted.get(x + apart, y, z)) {
                                    throw new GameTestAssertException("at " + x + "," + y + "," + z + " the stamp has "
                                            + Block.stateById(stampedCells.get(x, y, z)) + ", the pastes "
                                            + Block.stateById(pasted.get(x + apart, y, z)));
                                }
                            }
                        }
                    }
                    check(h.history().undoLabel().startsWith("Stamp · "), "history " + h.history().undoLabels());
                    h.undo(undone);
                })
                .thenWaitUntil(() -> check(undone.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undone.result.outcome() == JobOutcome.COMPLETED && undone.result.skippedConflicts() == 0,
                            "undo " + undone.result);
                    checkSame(before, capture(world, stampBox), "after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * A stamp of a free-angle copy and a quarter-turned one, mirrored across the middle of block x0 + 20: each side is
     * the exact mirror image of the other, cell for cell, and the undo puts every cell back.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_generate_stamp_free", timeoutTicks = LIMIT)
    public void aFreeAngleStampWithSymmetryBuildsTheExactImageAndUndoesExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1312);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 96, z0, x0 + 40, 108, z0 + 15);
        loadAndForce(world, area);
        ground(h, area);
        int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt"), glass = h.state("minecraft:glass");
        dev.sculptory.core.clipboard.Clipboard.Builder blocks = dev.sculptory.core.clipboard.Clipboard.builder(
                h.runtime.states(), new BlockPos(4, 3, 2));
        for (int x = 0; x < 4; x++) {
            for (int z = 0; z < 2; z++) {
                blocks.set(x, 0, z, x == 3 ? dirt : stone);
                if (z == 0) blocks.set(x, 1 + x % 2, z, glass);
            }
        }
        UUID id = h.service.clipboards().install(h.player.getUUID(), blocks.build()).id();
        int middle = x0 + 20;
        dev.sculptory.core.brush.Symmetry mirror = new dev.sculptory.core.brush.Symmetry(
                dev.sculptory.core.brush.Symmetry.Mode.MIRROR_X, 2 * middle + 1, 0);
        OpSpec.PasteMany stamp = new OpSpec.PasteMany(new SourceRef.Clipboard(id), List.of(
                new OpSpec.PasteMany.Placement(new BlockPos(x0 + 6, 100, z0 + 6), Orientation.free(Transform.IDENTITY,
                        3_000, 0, 0)),
                new OpSpec.PasteMany.Placement(new BlockPos(x0 + 13, 100, z0 + 6), Orientation.of(Transform.rotation(1)))),
                PasteOptions.DEFAULT, mirror);
        check(!stamp.placements().get(0).orientation().isExact(), "the first placement turns freely");
        WorldSnapshot before = capture(world, area);
        RecordingListener stamped = new RecordingListener(), undone = new RecordingListener();
        try {
            h.service.run(h.player, stamp, new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.STAMP), stamped);
        } catch (EditRejected e) {
            throw new GameTestAssertException("the free stamp was refused: " + e.getMessage());
        }
        context.startSequence()
                .thenWaitUntil(() -> check(stamped.result != null, "stamp running"))
                .thenWaitUntil(() -> {
                    check(stamped.result.outcome() == JobOutcome.COMPLETED, "stamp " + stamped.result);
                    WorldSnapshot now = capture(world, area);
                    int written = 0;
                    for (int y = 100; y <= 106; y++) {
                        for (int z = z0; z <= z0 + 15; z++) {
                            for (int x = x0; x < middle; x++) {
                                int image = 2 * middle - x;
                                check(now.get(x, y, z) == now.get(image, y, z), "at " + x + "," + y + "," + z + ": "
                                        + Block.stateById(now.get(x, y, z)) + ", its image "
                                        + Block.stateById(now.get(image, y, z)));
                                if (now.get(x, y, z) != before.get(x, y, z)) written++;
                            }
                        }
                    }
                    check(written > 10, "the stamp wrote " + written + " cells on its own side");
                    check(h.history().undoLabel().startsWith("Stamp · "), "history " + h.history().undoLabels());
                    h.undo(undone);
                })
                .thenWaitUntil(() -> check(undone.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undone.result.outcome() == JobOutcome.COMPLETED && undone.result.skippedConflicts() == 0,
                            "undo " + undone.result);
                    checkSame(before, capture(world, area), "after the undo");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * A free placement whose mirror image would land beyond the world limit is refused INVALID (its copy is checked by the
     * image of its box), with nothing written or pushed.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_generate_stamp_free_beyond", timeoutTicks = LIMIT)
    public void aFreeStampWhoseMirrorImageLeavesTheWorldIsRefused(GameTestHelper context) {
        Harness h = new Harness(context);
        int[] at = regionCorner(context, 1314);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0, 96, z0, x0 + 15, 108, z0 + 15);
        loadAndForce(h.world, area);
        ground(h, area);
        int stone = h.state("minecraft:stone");
        UUID id = h.service.clipboards().install(h.player.getUUID(), dev.sculptory.core.clipboard.Clipboard.builder(
                h.runtime.states(), new BlockPos(3, 1, 3)).set(0, 0, 0, stone).set(2, 0, 2, stone).build()).id();
        // Mirrored across x = 29,900,000: the copy of a placement near x0 lands near 54 million, past the 30 million limit.
        dev.sculptory.core.brush.Symmetry far = new dev.sculptory.core.brush.Symmetry(
                dev.sculptory.core.brush.Symmetry.Mode.MIRROR_X, 2 * 29_900_000 + 1, 0);
        check(2L * 29_900_000 - x0 > EngineEditService.HORIZONTAL_LIMIT, "the image is beyond the limit");
        OpSpec.PasteMany stamp = new OpSpec.PasteMany(new SourceRef.Clipboard(id), List.of(new OpSpec.PasteMany.Placement(
                new BlockPos(x0 + 6, 100, z0 + 6), Orientation.free(Transform.IDENTITY, 4_500, 0, 0))),
                PasteOptions.DEFAULT, far);
        WorldSnapshot before = capture(h.world, area);
        List<String> undoBefore = h.history().undoLabels();
        EditRejected refused = refusal(() -> h.service.run(h.player, stamp, RunOptions.DEFAULT, null));
        check(refused.reason() == RejectReason.INVALID && refused.detail().contains("beyond the world limit"),
                "refused " + refused.reason() + ": " + refused.detail());
        checkSame(before, capture(h.world, area), "after the refusal");
        check(h.history().undoLabels().equals(undoBefore), "a refusal pushed history");
        forceChunks(h.world, area, false);
        h.close();
        context.succeed();
    }

    /**
     * A copy turned freely by 30 degrees carries its armor stand: the stand lands turned by the same angle, and the undo
     * takes it away and puts every block back.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_generate_stamp_free_entity", timeoutTicks = LIMIT)
    public void aFreeAngleStampTurnsItsEntityAndUndoesExactly(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1316);
        int x0 = at[0], z0 = at[1];
        Box area = box(x0 - 4, 96, z0 - 4, x0 + 30, 108, z0 + 20);
        loadAndForce(world, area);
        ground(h, area);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        int stone = h.state("minecraft:stone");
        for (int x = x0; x <= x0 + 2; x++) writer.write(x, 100, z0, stone, null);
        ArmorStand stand = EntityType.ARMOR_STAND.create(world);
        stand.moveTo(x0 + 1.5, 101, z0 + 0.5, 0, 0);
        world.addFreshEntity(stand);
        Box post = box(x0, 100, z0, x0 + 2, 102, z0);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        Box targets = box(x0 + 10, 96, z0 + 6, x0 + 26, 108, z0 + 18);
        net.minecraft.world.phys.AABB standArea = new net.minecraft.world.phys.AABB(targets.min().x(), 96, targets.min().z(),
                targets.max().x() + 1, 110, targets.max().z() + 1);
        WorldSnapshot[] before = new WorldSnapshot[1];
        RecordingListener stamped = new RecordingListener(), undone = new RecordingListener();
        context.startSequence()
                .thenWaitUntil(() -> EntitiesGameTest.ready(world, area))
                .thenWaitUntil(() -> {
                    try {
                        clips.copy(h.player, new Region.Cuboid(post), post.min(), false, CellMask.ANY, EntityFilter.ALL,
                                null, copied);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("copy refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("the copy");
                    check(info.entities() == 1, "the clipboard holds the stand: " + info.entities());
                    before[0] = capture(world, targets);
                    OpSpec.PasteMany stamp = new OpSpec.PasteMany(new SourceRef.Clipboard(info.clipboardId()), List.of(
                            new OpSpec.PasteMany.Placement(new BlockPos(x0 + 16, 100, z0 + 12),
                                    Orientation.free(Transform.IDENTITY, 3_000, 0, 0))), new PasteOptions(false, false, true));
                    try {
                        h.service.run(h.player, stamp, new RunOptions(false, ConflictPolicy.SKIP_CONFLICTS, OpLabel.STAMP),
                                stamped);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("the stamp was refused: " + e.getMessage());
                    }
                })
                .thenWaitUntil(() -> check(stamped.result != null, "stamp running"))
                .thenWaitUntil(() -> {
                    check(stamped.result.outcome() == JobOutcome.COMPLETED, "stamp " + stamped.result);
                    List<ArmorStand> stands = world.getEntitiesOfClass(ArmorStand.class, standArea, e -> true);
                    check(stands.size() == 1, "one stamped stand, found " + stands.size());
                    float turned = net.minecraft.util.Mth.wrapDegrees(stands.get(0).getYRot() - 30f);
                    check(Math.abs(turned) < 1f, "the stand turned by 30 degrees: its yaw is " + stands.get(0).getYRot());
                    h.undo(undone);
                })
                .thenWaitUntil(() -> check(undone.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undone.result.outcome() == JobOutcome.COMPLETED, "undo " + undone.result);
                    checkSame(before[0], capture(world, targets), "after the undo");
                    check(world.getEntitiesOfClass(ArmorStand.class, standArea, e -> true).isEmpty(),
                            "the undo took the stamped stand away");
                    check(!stand.isRemoved(), "the original stand stays");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * A free placement whose mirror image overlaps another placement of the original: where they meet, the original
     * copy wins over the image (copies are applied original first). Checked against the same placement pasted alone,
     * with the free placement's image alone showing that they do meet, on cells it would write differently.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_generate_stamp_original_wins", timeoutTicks = LIMIT)
    public void whereAFreePlacementsImageMeetsAnOriginalPlacementTheOriginalWins(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 1318);
        int x0 = at[0], z0 = at[1];
        int c = x0 + 20;
        Box area = box(x0, 96, z0, x0 + 40, 108, z0 + 100);
        loadAndForce(world, area);
        ground(h, area);
        List<Integer> palette = List.of(h.state("minecraft:stone"), h.state("minecraft:dirt"), h.state("minecraft:glass"),
                h.state("minecraft:stone_bricks"), h.state("minecraft:oak_planks"));
        dev.sculptory.core.clipboard.Clipboard.Builder blocks = dev.sculptory.core.clipboard.Clipboard.builder(
                h.runtime.states(), new BlockPos(4, 2, 3));
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 2; y++) {
                for (int z = 0; z < 3; z++) blocks.set(x, y, z, palette.get((x + 2 * y + 3 * z) % palette.size()));
            }
        }
        UUID id = h.service.clipboards().install(h.player.getUUID(), blocks.build()).id();
        SourceRef source = new SourceRef.Clipboard(id);
        dev.sculptory.core.brush.Symmetry mirror = new dev.sculptory.core.brush.Symmetry(
                dev.sculptory.core.brush.Symmetry.Mode.MIRROR_X, 2 * c + 1, 0);
        // A east of the mirror; B (free) west of it, its image falling over A. Each check in its own row of the area.
        OpSpec.PasteMany.Placement a = new OpSpec.PasteMany.Placement(new BlockPos(c + 1, 100, z0 + 5), Orientation.IDENTITY);
        OpSpec.PasteMany.Placement b = new OpSpec.PasteMany.Placement(new BlockPos(c - 5, 100, z0 + 5),
                Orientation.free(Transform.IDENTITY, 3_000, 0, 0));
        int aloneRow = 40, imageRow = 80;
        List<RecordingListener> jobs = new ArrayList<>();
        WorldSnapshot before = capture(world, area);
        try {
            RecordingListener both = new RecordingListener(), aAlone = new RecordingListener();
            RecordingListener bImage = new RecordingListener();
            h.service.run(h.player, new OpSpec.PasteMany(source, List.of(a, b), PasteOptions.DEFAULT, mirror),
                    RunOptions.DEFAULT, both);
            h.service.run(h.player, new OpSpec.Paste(source, a.origin().offset(0, 0, aloneRow), a.orientation(),
                    PasteOptions.DEFAULT), RunOptions.DEFAULT, aAlone);
            h.service.run(h.player, new OpSpec.PasteMany(source, List.of(new OpSpec.PasteMany.Placement(
                    b.origin().offset(0, 0, imageRow), b.orientation())), PasteOptions.DEFAULT, mirror), RunOptions.DEFAULT,
                    bImage);
            jobs.addAll(List.of(both, aAlone, bImage));
        } catch (EditRejected e) {
            throw new GameTestAssertException("refused: " + e.getMessage());
        }
        context.startSequence()
                .thenWaitUntil(() -> {
                    for (RecordingListener job : jobs) check(job.result != null, "jobs running");
                })
                .thenWaitUntil(() -> {
                    for (RecordingListener job : jobs) check(job.result.outcome() == JobOutcome.COMPLETED, "job " + job.result);
                    WorldSnapshot now = capture(world, area);
                    int met = 0, differing = 0;
                    for (int y = 100; y <= 103; y++) {
                        for (int z = z0; z <= z0 + 15; z++) {
                            for (int x = c + 1; x <= x0 + 40; x++) {
                                int alone = now.get(x, y, z + aloneRow);
                                if (alone == before.get(x, y, z + aloneRow)) continue; // A does not write this cell
                                check(now.get(x, y, z) == alone, "at " + x + "," + y + "," + z + " A lost to "
                                        + Block.stateById(now.get(x, y, z)));
                                int image = now.get(x, y, z + imageRow);
                                if (image != before.get(x, y, z + imageRow)) {
                                    met++;
                                    if (image != alone) differing++;
                                }
                            }
                        }
                    }
                    check(met > 0 && differing > 0, "the image meets A on " + met + " cells, " + differing
                            + " of them different");
                    forceChunks(world, area, false);
                    h.close();
                })
                .thenSucceed();
    }
}
