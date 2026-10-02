package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.ClipboardGameTest.copy;
import static dev.sculptory.fabric.gametest.ClipboardGameTest.paste;
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
import dev.sculptory.core.NamespacedId;
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
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.net.PreviewPayload;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.test.TimedTaskRunner;
import vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity;

/**
 * Region ops, copy and paste with every transform, and library assets on Chipped and Farmer's Delight blocks: exact states, block-entity contents kept, and exact undo and redo.
 */
public final class FidelityEditGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** Farmer's Delight blocks with a block entity in the build. */
    private static final List<NamespacedId> KITCHEN_BLOCKS = List.of(new NamespacedId("farmersdelight:stove"),
            new NamespacedId("farmersdelight:cooking_pot"), new NamespacedId("farmersdelight:skillet"),
            new NamespacedId("farmersdelight:cutting_board"), new NamespacedId("farmersdelight:oak_cabinet"),
            new NamespacedId("farmersdelight:basket"));

    /**
     * Replace (by block, and by one exact modded state), Erase of the kitchen's block entities (nothing drops) and a
     * weighted Fill of modded states over the top of the build, each checked; then four undos give back the build
     * exactly (states and block-entity contents), four redos the edited state, and four more undos the build again.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_region", tickLimit = LIMIT)
    public void regionOpsOnModdedBlocksUndoAndRedoExactly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 200);
        int x0 = at[0] + 4, y0 = 100, z0 = at[1] + 4;
        Box all = box(x0 - 4, y0, z0 - 4, x0 + 11, y0 + 7, z0 + 11);
        loadAndForce(world, all);
        FidelitySupport.build(h, world, x0, y0, z0);
        WorldSnapshot[] original = new WorldSnapshot[1];

        int bricks = h.state("chipped:angry_mossy_stone_bricks");
        int trapdoor = h.state(
                "chipped:airy_birch_trapdoor[facing=south,half=top,open=true,powered=false,waterlogged=true]");
        int otherTrapdoor = h.state(
                "chipped:airy_birch_trapdoor[facing=west,half=bottom,open=false,powered=true,waterlogged=false]");
        Pattern chippedMix = new Pattern.Weighted(new int[] {h.state("chipped:boxed_oak_planks"),
                h.state("chipped:bundled_acacia_log[axis=z]"), h.state("farmersdelight:rich_soil_farmland[moisture=3]")},
                new int[] {3, 2, 1}, 42L);
        List<OpSpec> edits = List.of(
                new OpSpec.Replace(all, new CellMask.Blocks(List.of(new NamespacedId("farmersdelight:rich_soil"))),
                        new Pattern.Single(bricks)),
                new OpSpec.Replace(all, new CellMask.States(new int[] {trapdoor}), new Pattern.Single(otherTrapdoor)),
                new OpSpec.Erase(all, new CellMask.Blocks(KITCHEN_BLOCKS)),
                new OpSpec.Fill(box(x0, y0 + 3, z0, x0 + 7, y0 + 4, z0 + 7), chippedMix, CellMask.ANY));
        List<RecordingListener> jobs = new ArrayList<>();
        WorldSnapshot[] edited = new WorldSnapshot[1];
        TimedTaskRunner runner = context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(() -> original[0] = capture(world, all));
        for (int i = 0; i < edits.size(); i++) {
            int step = i;
            runner.createAndAdd(() -> {
                if (step > 0) checkEdit(h, world, all, original[0], step - 1, jobs.get(step - 1));
                jobs.add(run(h, edits.get(step)));
            });
            runner.createAndAdd(() -> check(jobs.get(step).result != null, "edit " + step + " running"));
        }
        runner.createAndAdd(() -> {
            checkEdit(h, world, all, original[0], edits.size() - 1, jobs.get(edits.size() - 1));
            edited[0] = capture(world, all);
        });
        undoRedo(runner, h, edits.size(), true);
        runner.createAndAdd(() -> checkSame(original[0], capture(world, all), "after undoing every edit"));
        undoRedo(runner, h, edits.size(), false);
        runner.createAndAdd(() -> checkSame(edited[0], capture(world, all), "after redoing every edit"));
        undoRedo(runner, h, edits.size(), true);
        runner.createAndAdd(() -> {
            checkSame(original[0], capture(world, all), "after undoing again");
            check(FidelitySupport.itemEntities(world, all) == 0, "an edit dropped items");
            forceChunks(world, all, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    /** What edit {@code step} of the region test must have done. */
    private static void checkEdit(Harness h, ServerWorld world, Box all, WorldSnapshot original, int step,
                                  RecordingListener job) {
        check(job.result.outcome() == JobOutcome.COMPLETED && job.result.skippedProtected() == 0
                && job.result.strippedNbt() == 0, "edit " + step + ": " + job.result);
        WorldSnapshot now = capture(world, all);
        int changed = 0;
        for (int y = all.min().y(); y <= all.max().y(); y++) {
            for (int z = all.min().z(); z <= all.max().z(); z++) {
                for (int x = all.min().x(); x <= all.max().x(); x++) {
                    if (now.get(x, y, z) != original.get(x, y, z)) changed++;
                }
            }
        }
        switch (step) {
            case 0 -> {
                int soil = count(h, original, "farmersdelight:rich_soil");
                check(soil > 0 && job.result.changed() == soil && count(h, now, "farmersdelight:rich_soil") == 0
                        && count(h, now, "chipped:angry_mossy_stone_bricks")
                        == count(h, original, "chipped:angry_mossy_stone_bricks") + soil,
                        "rich soil not replaced: " + job.result);
            }
            case 1 -> check(job.result.changed() == 1 && count(h, now,
                    "chipped:airy_birch_trapdoor[facing=west,half=bottom,open=false,powered=true,waterlogged=false]") == 1,
                    "the exact trapdoor state was not replaced: " + job.result);
            case 2 -> {
                check(job.result.changed() == KITCHEN_BLOCKS.size() && now.tiles.size() == FidelitySupport.TILES.size()
                        - KITCHEN_BLOCKS.size(), "erase: " + job.result + ", " + now.tiles.size() + " block entities left");
                check(FidelitySupport.itemEntities(world, all) == 0, "erasing the kitchen dropped its contents");
            }
            default -> check(job.result.changed() == 128 && count(h, now, "chipped:boxed_oak_planks") > 0
                    && count(h, now, "chipped:bundled_acacia_log[axis=z]") > 0
                    && count(h, now, "farmersdelight:rich_soil_farmland[moisture=3]") > 0, "weighted fill: " + job.result);
        }
        check(changed > 0, "edit " + step + " changed nothing");
    }

    /** Cells of a snapshot in {@code spec}: every state of the block, or one exact state. */
    private static int count(Harness h, WorldSnapshot snapshot, String spec) {
        boolean exact = spec.contains("[");
        int n = 0;
        for (int state : snapshot.states) {
            String id = Registries.BLOCK.getId(Block.getStateFromRawId(state).getBlock()).toString();
            if (exact ? h.runtime.states().format(state).equals(spec) : id.equals(spec)) n++;
        }
        return n;
    }

    /** Pastes the clipboard with transforms {@code from} to {@code to} (exclusive), {@code spacing} apart along x. */
    static void pasteRange(Harness h, ClipboardService.ClipboardInfo info, List<Transform> transforms,
            int from, int to, int x0, int y0, int z0, int spacing, List<RecordingListener> pastes,
            List<BlockPos> origins) {
        for (int k = from; k < to; k++) {
            BlockPos origin = new BlockPos(x0 + spacing * (k + 1), y0, z0);
            RecordingListener listener = new RecordingListener();
            paste(h, h.player, info.clipboardId(), origin, transforms.get(k), listener);
            pastes.add(listener);
            origins.add(origin);
        }
    }

    /** Adds {@code count} undos (or redos) in a row, each waiting for the previous and checking it was exact. */
    static void undoRedo(TimedTaskRunner runner, Harness h, int count, boolean undo) {
        RecordingListener[] last = {null};
        for (int i = 0; i < count; i++) {
            int step = i;
            runner.createAndAdd(() -> {
                if (last[0] != null) {
                    check(last[0].result != null, (undo ? "undo " : "redo ") + (step - 1) + " running");
                    checkHistoryJob(last[0], undo, step - 1);
                }
                last[0] = new RecordingListener();
                if (undo) {
                    h.undo(last[0]);
                } else {
                    h.redo(last[0]);
                }
            });
        }
        runner.createAndAdd(() -> {
            check(last[0].result != null, (undo ? "undo " : "redo ") + (count - 1) + " running");
            checkHistoryJob(last[0], undo, count - 1);
        });
    }

    private static void checkHistoryJob(RecordingListener job, boolean undo, int step) {
        check(job.result.outcome() == JobOutcome.COMPLETED && job.result.skippedConflicts() == 0
                && job.result.strippedNbt() == 0, (undo ? "undo " : "redo ") + step + ": " + job.result);
    }

    private static RecordingListener run(Harness h, OpSpec op) {
        RecordingListener listener = new RecordingListener();
        try {
            h.service.run(h.player, op, RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException(op + " refused: " + e.getMessage());
        }
        return listener;
    }

    /** Every transform: four turns with no mirror, with the X mirror (Transform.all) and with the Z mirror. */
    static List<Transform> everyTransform() {
        List<Transform> transforms = new ArrayList<>(Transform.all());
        for (int turns = 0; turns < 4; turns++) transforms.add(new Transform(turns, Mirror.Z));
        return transforms;
    }

    /**
     * The modded build copied and pasted with every turn and both mirrors: each cell is its source cell turned
     * ({@link FidelitySupport#expectedTurn}: vanilla's {@code mirror(...).rotate(...)}, and the modded facing fallback for
     * the pot, skillet, pie, feasts and lanterns, which do not turn themselves), every block entity keeps its contents,
     * and undoing all twelve pastes leaves exactly the world from before.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_paste", tickLimit = LIMIT)
    public void moddedPasteWithEveryTransformKeepsContents(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 201);
        int x0 = at[0] + 4, y0 = 100, z0 = at[1] + 4;
        Box source = FidelitySupport.buildBox(x0, y0, z0);
        List<Transform> transforms = everyTransform();
        // A turned copy reaches up to 7 blocks west or north of its origin, so pastes are 20 apart.
        int spacing = 20;
        Box all = box(x0 - 4, y0, z0 - 12, x0 + spacing * (transforms.size() + 1) + 12, y0 + 3, z0 + 11);
        loadAndForce(world, all);
        FidelitySupport.build(h, world, x0, y0, z0);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        WorldSnapshot[] before = new WorldSnapshot[2];
        List<Captured<ClipboardService.ClipboardInfo>> copied = new ArrayList<>();
        List<RecordingListener> pastes = new ArrayList<>();
        List<BlockPos> origins = new ArrayList<>();
        TimedTaskRunner runner = context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(() -> {
                    before[0] = capture(world, source);
                    before[1] = capture(world, all);
                    copied.add(copy(clips, h.player, source, source.min()));
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copied.get(0).get("copy");
                    check(h.service.clipboards().get(h.player.getUuid()).orElseThrow().clipboard().tileCount()
                            == FidelitySupport.TILES.size(), "the clipboard lost block entities");
                    // Two rounds: at most 8 of a player's jobs may wait to start (executor.maxQueuedJobsPerPlayer).
                    pasteRange(h, info, transforms, 0, transforms.size() / 2, x0, y0, z0, spacing, pastes, origins);
                })
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "first round running");
                    pasteRange(h, copied.get(0).get("copy"), transforms, transforms.size() / 2, transforms.size(),
                            x0, y0, z0, spacing, pastes, origins);
                })
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .createAndAdd(() -> {
                    for (int k = 0; k < transforms.size(); k++) {
                        check(pastes.get(k).result.outcome() == JobOutcome.COMPLETED
                                && pastes.get(k).result.strippedNbt() == 0, "paste " + k + " " + pastes.get(k).result);
                        FidelitySupport.checkTransformed(world, before[0], origins.get(k), transforms.get(k), "paste");
                    }
                    CookingPotBlockEntity pot = FidelitySupport.entity(world, origins.get(0).x() + 1, y0 + 1,
                            origins.get(0).z(), CookingPotBlockEntity.class);
                    check(pot.getInventory().getStackInSlot(7).isOf(Items.BOWL)
                            && pot.getInventory().getStackInSlot(7).getCount() == 4, "the pasted pot lost its bowls");
                    Inventory cabinet = FidelitySupport.entity(world, origins.get(0).x() + 4, y0 + 1, origins.get(0).z(),
                            Inventory.class);
                    check(cabinet.getStack(0).isOf(Items.BREAD) && cabinet.getStack(0).getCount() == 5,
                            "the pasted cabinet lost its bread");
                });
        undoRedo(runner, h, transforms.size(), true);
        runner.createAndAdd(() -> {
            checkSame(before[1], capture(world, all), "after undoing every paste");
            check(FidelitySupport.itemEntities(world, all) == 0, "a paste or undo dropped items");
            forceChunks(world, all, false);
            h.close();
        });
        runner.completeIfSuccessful();
    }

    /**
     * Library assets of a modded build: saved, previewed (the preview names the modded states), placed by hash with
     * a turn and a mirror, undone exactly, then loaded as a clipboard and placed again. File tiles are sanitized
     * for everyone: the kitchen's block entities keep their contents, and the canvas signs their text.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fidelity_asset", tickLimit = LIMIT)
    public void moddedAssetSaveAndPlace(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 202);
        int x0 = at[0] + 4, y0 = 100, z0 = at[1] + 4;
        Box source = FidelitySupport.buildBox(x0, y0, z0);
        Box all = box(x0 - 4, y0, z0 - 12, x0 + 48, y0 + 3, z0 + 11);
        loadAndForce(world, all);
        FidelitySupport.build(h, world, x0, y0, z0);
        Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        WorldSnapshot[] before = new WorldSnapshot[1];
        List<Captured<ClipboardService.ClipboardInfo>> copied = new ArrayList<>();
        Captured<ClipboardService.Saved> saved = new Captured<>();
        Captured<ClipboardService.Outbound> preview = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> loaded = new Captured<>();
        RecordingListener assetPaste = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        RecordingListener clipboardPaste = new RecordingListener();
        RecordingListener undoAgain = new RecordingListener();
        Transform turned = new Transform(1, Mirror.X);
        BlockPos assetAt = new BlockPos(x0 + 20, y0, z0);
        BlockPos clipboardAt = new BlockPos(x0 + 36, y0, z0);
        WorldSnapshot[] allBefore = new WorldSnapshot[1];
        String[] hash = new String[1];
        context.createTimedTaskRunner()
                .createAndAdd(FidelitySupport.settled(context, world, all))
                .createAndAdd(() -> {
                    before[0] = capture(world, source);
                    copied.add(copy(clips, h.player, source, source.min()));
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copied.get(0).get("copy");
                    run(() -> clips.save(h.player, info.clipboardId(), "fidelity/kitchen.schem", saved));
                })
                .createAndAdd(() -> {
                    ClipboardService.Saved result = saved.get("save");
                    check(result.notices().isEmpty(), "save notices " + result.notices());
                    hash[0] = result.contentHash();
                    run(() -> clips.preview(h.player, new SourceRef.Asset(hash[0]), preview));
                })
                .createAndAdd(() -> {
                    ClipboardService.Outbound out = preview.get("asset preview");
                    try {
                        PreviewPayload.Decoded decoded = PreviewPayload.decode(out.payload(), 64L << 20);
                        check("farmersdelight:cooking_pot[facing=south,support=none,waterlogged=false]".equals(
                                decoded.get(1, 1, 0)), "preview cell " + decoded.get(1, 1, 0));
                        check("chipped:big_lantern[facing=east,waterlogged=true]".equals(decoded.get(4, 1, 2)),
                                "preview cell " + decoded.get(4, 1, 2));
                    } catch (IOException e) {
                        throw new GameTestException("undecodable preview: " + e);
                    }
                    allBefore[0] = capture(world, all);
                    run(() -> h.service.run(h.player, new OpSpec.Paste(new SourceRef.Asset(hash[0]), assetAt, turned,
                            PasteOptions.DEFAULT), RunOptions.DEFAULT, assetPaste));
                })
                .createAndAdd(() -> check(assetPaste.result != null, "asset paste running"))
                .createAndAdd(() -> {
                    check(assetPaste.result.outcome() == JobOutcome.COMPLETED && assetPaste.result.strippedNbt() == 0,
                            "asset paste " + assetPaste.result);
                    FidelitySupport.checkTransformed(world, before[0], assetAt, turned, "asset");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    checkHistoryJob(undo, true, 0);
                    checkSame(allBefore[0], capture(world, all), "after undoing the asset");
                    run(() -> clips.load(h.player, "fidelity/kitchen.schem", loaded));
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = loaded.get("load");
                    check(info.notices().isEmpty(), "load notices " + info.notices());
                    paste(h, h.player, info.clipboardId(), clipboardAt, Transform.IDENTITY, clipboardPaste);
                })
                .createAndAdd(() -> check(clipboardPaste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(clipboardPaste.result.outcome() == JobOutcome.COMPLETED, "paste " + clipboardPaste.result);
                    ClipTestSupport.checkShifted(world, before[0], pos(clipboardAt.x(), clipboardAt.y(), clipboardAt.z()),
                            "the loaded asset");
                    h.undo(undoAgain);
                })
                .createAndAdd(() -> check(undoAgain.result != null, "undo running"))
                .createAndAdd(() -> {
                    checkHistoryJob(undoAgain, true, 1);
                    checkSame(allBefore[0], capture(world, all), "after undoing the loaded asset");
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    private static void run(ClipboardGameTest.ThrowingRun request) {
        try {
            request.run();
        } catch (EditRejected e) {
            throw new GameTestException("refused: " + e.getMessage());
        }
    }
}
