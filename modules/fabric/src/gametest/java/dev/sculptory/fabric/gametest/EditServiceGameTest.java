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
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.HistoryLimits;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.EditServiceHost;
import dev.sculptory.fabric.engine.impl.EngineEditService;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.config.UnloadedPolicy;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.impl.HistorySnapshot;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.FurnaceBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * {@code EngineEditService} region ops, undo and redo against a real server. Each test
 * works in its own far region, above the flat test world's ground, in its own batch.
 */
public final class EditServiceGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    /** 100,000 cells with block entities: filled, then undone to the exact states and NBT. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_fill", tickLimit = LIMIT)
    public void fill100kThenUndo(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 11);
        int x = at[0], z = at[1];
        Box region = box(x, 100, z, x + 49, 139, z + 49);
        loadAndForce(world, region);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        for (int dx = 0; dx < 50; dx++) {
            for (int dz = 0; dz < 50; dz++) writer.write(x + dx, 100, z + dz, h.state("minecraft:dirt"), null);
        }
        writer.write(x + 7, 101, z + 7, h.state("minecraft:water"), null);
        BlockPos chest = pos(x + 3, 101, z + 3);
        writer.write(chest.getX(), chest.getY(), chest.getZ(), h.state("minecraft:chest[facing=east]"), null);
        ChestBlockEntity chestEntity = (ChestBlockEntity) world.getBlockEntity(chest);
        chestEntity.setStack(0, new ItemStack(Items.DIAMOND, 5));
        chestEntity.setStack(20, new ItemStack(Items.OAK_LOG, 64));
        BlockPos sign = pos(x + 10, 110, z + 10);
        writer.write(sign.getX(), sign.getY(), sign.getZ(), h.state("minecraft:oak_sign"), null);
        ((SignBlockEntity) world.getBlockEntity(sign)).setText(
                new SignText().withMessage(0, Text.literal("Builder")).withMessage(1, Text.literal("Suite")), true);
        BlockPos furnace = pos(x + 40, 139, z + 49);
        writer.write(furnace.getX(), furnace.getY(), furnace.getZ(), h.state("minecraft:furnace"), null);
        ((FurnaceBlockEntity) world.getBlockEntity(furnace)).setStack(0, new ItemStack(Items.IRON_ORE, 3));
        WorldSnapshot before = capture(world, region);
        check(before.tiles.size() == 3, "fixture has " + before.tiles.size() + " block entities");

        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        JobTicket ticket = h.fill(region, "minecraft:stone", fill);
        check(ticket.estimatedCells() == 100_000, "estimate " + ticket.estimatedCells());
        check(ticket.label().equals("Fill"), "label " + ticket.label());
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> {
                    check(fill.result.outcome() == JobOutcome.COMPLETED, "fill " + fill.result);
                    check(fill.result.changed() == 100_000, "changed " + fill.result.changed());
                    check(fill.progressEvents > 1, "progress events: " + fill.progressEvents);
                    check(world.getBlockEntity(chest) == null && world.getBlockState(chest).isOf(Blocks.STONE), "chest kept");
                    HistorySnapshot history = h.history();
                    check(history.canUndo() && history.undoLabel().equals("Fill · 100,000 blocks"),
                            "history " + history);
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    check(undo.result.changed() == 100_000 && undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    checkSame(before, capture(world, region), "after undo");
                    ChestBlockEntity restored = (ChestBlockEntity) world.getBlockEntity(chest);
                    check(restored.getStack(0).isOf(Items.DIAMOND) && restored.getStack(0).getCount() == 5, "chest items");
                    HistorySnapshot history = h.history();
                    check(!history.canUndo() && history.canRedo() && history.redoLabel().equals("Fill · 100,000 blocks"),
                            "history " + history);
                    forceChunks(world, region, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Cells changed by someone else after the edit keep that change; the undo counts them as conflicts. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_conflict", tickLimit = LIMIT)
    public void undoConflictSkipsForeignCells(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 12);
        Box region = box(at[0], 112, at[1], at[0] + 15, 127, at[1] + 15);
        loadAndForce(world, region);
        WorldSnapshot before = capture(world, region);
        BlockPos[] foreign = {pos(at[0], 112, at[1]), pos(at[0] + 8, 120, at[1] + 3), pos(at[0] + 15, 127, at[1] + 15)};
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        h.fill(region, "minecraft:stone", fill);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> {
                    for (BlockPos p : foreign) world.setBlockState(p, Blocks.GOLD_BLOCK.getDefaultState());
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    check(undo.result.skippedConflicts() == foreign.length, "conflicts " + undo.result.skippedConflicts());
                    check(undo.result.changed() == 4096 - foreign.length, "changed " + undo.result.changed());
                    for (BlockPos p : foreign) {
                        check(world.getBlockState(p).isOf(Blocks.GOLD_BLOCK), "foreign change lost at " + p.toShortString());
                        world.setBlockState(p, Blocks.AIR.getDefaultState());
                    }
                    checkSame(before, capture(world, region), "the rest after undo");
                    forceChunks(world, region, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The chest scenario: a fill replaced chest A; someone placed a chest of the same state holding other items.
     * Undo keeps the new chest's items and counts one conflict.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_nbt", tickLimit = LIMIT)
    public void undoKeepsNbtAddedAfterEdit(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 13);
        Box region = box(at[0], 112, at[1], at[0] + 15, 119, at[1] + 15);
        loadAndForce(world, region);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        BlockPos chest = pos(at[0] + 5, 113, at[1] + 5);
        int chestState = h.state("minecraft:chest[facing=south]");
        writer.write(chest.getX(), chest.getY(), chest.getZ(), chestState, null);
        ((ChestBlockEntity) world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 7));
        WorldSnapshot before = capture(world, region);
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        h.fill(region, "minecraft:sand", fill);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> {
                    check(world.getBlockState(chest).isOf(Blocks.SAND), "chest not replaced");
                    writer.write(chest.getX(), chest.getY(), chest.getZ(), chestState, null);
                    ((ChestBlockEntity) world.getBlockEntity(chest)).setStack(3, new ItemStack(Items.EMERALD, 2));
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    check(undo.result.skippedConflicts() == 1, "conflicts " + undo.result.skippedConflicts());
                    ChestBlockEntity kept = (ChestBlockEntity) world.getBlockEntity(chest);
                    check(kept.getStack(3).isOf(Items.EMERALD) && kept.getStack(0).isEmpty(),
                            "the new chest's items were replaced");
                    WorldSnapshot after = capture(world, region);
                    after.tiles.put(chest.asLong(), before.tiles.get(chest.asLong()));
                    checkSame(before, after, "the rest after undo");
                    forceChunks(world, region, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_redo", tickLimit = LIMIT)
    public void redoAfterUndo(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 14);
        Box region = box(at[0], 112, at[1], at[0] + 20, 125, at[1] + 20);
        loadAndForce(world, region);
        WorldSnapshot before = capture(world, region);
        WorldSnapshot[] filled = new WorldSnapshot[1];
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        RecordingListener redo = new RecordingListener();
        h.fill(region, "minecraft:polished_granite", fill);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> {
                    filled[0] = capture(world, region);
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    checkSame(before, capture(world, region), "after undo");
                    h.redo(redo);
                })
                .createAndAdd(() -> check(redo.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redo.result.outcome() == JobOutcome.COMPLETED && redo.result.skippedConflicts() == 0,
                            "redo " + redo.result);
                    checkSame(filled[0], capture(world, region), "after redo");
                    HistorySnapshot history = h.history();
                    check(history.canUndo() && !history.canRedo() && history.undoLabels().size() == 1,
                            "history " + history);
                    forceChunks(world, region, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_clear_redo", tickLimit = LIMIT)
    public void newEditClearsRedo(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 15);
        Box first = box(at[0], 112, at[1], at[0] + 9, 121, at[1] + 9);
        Box second = box(at[0] + 20, 112, at[1], at[0] + 29, 121, at[1] + 9);
        Box all = box(at[0], 112, at[1], at[0] + 29, 121, at[1] + 9);
        loadAndForce(world, all);
        RecordingListener fillA = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        RecordingListener fillB = new RecordingListener();
        h.fill(first, "minecraft:stone", fillA);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fillA.result != null, "fill A running"))
                .createAndAdd(() -> h.undo(undo))
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(h.history().canRedo(), "nothing to redo after the undo");
                    h.fill(second, "minecraft:dirt", fillB);
                })
                .createAndAdd(() -> check(fillB.result != null, "fill B running"))
                .createAndAdd(() -> {
                    HistorySnapshot history = h.history();
                    check(history.canUndo() && !history.canRedo(), "history " + history);
                    check(history.undoLabel().equals("Fill · 1,000 blocks") && history.undoLabels().size() == 1,
                            "undo labels " + history.undoLabels());
                    try {
                        h.service.redo(h.player, ConflictPolicy.SKIP_CONFLICTS);
                        throw new GameTestException("redo was admitted after a new edit");
                    } catch (EditRejected e) {
                        check(e.reason() == RejectReason.HISTORY_EMPTY, "reason " + e.reason());
                    }
                    check(world.getBlockState(pos(at[0] + 3, 115, at[1] + 3)).isAir(), "fill A came back");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Per-player entry cap, then the global byte cap: the oldest entry across players goes, and each eviction is
     * reported. Every entry is one full section of air turned to stone, so all have the same size.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_cap", tickLimit = LIMIT)
    public void historyMemoryCapEvicts(TestContext context) {
        RecordBuilder probe = new RecordBuilder();
        for (int i = 0; i < 4096; i++) probe.record(i & 15, 112 + (i >> 8), (i >> 4) & 15, 0, null, 1, null);
        EditRecord probeRecord = probe.build();
        long size = probeRecord.estimatedBytes();
        Harness h = new Harness(context, new HistoryLimits(2, Long.MAX_VALUE, size * 5 / 2));
        ServerPlayerEntity other = h.addPlayer();
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 16);
        Box[] sections = new Box[4];
        for (int i = 0; i < 4; i++) sections[i] = box(at[0] + 16 * i, 112, at[1], at[0] + 16 * i + 15, 127, at[1] + 15);
        Box all = box(at[0], 112, at[1], at[0] + 63, 127, at[1] + 15);
        loadAndForce(world, all);
        RecordingListener[] fills = new RecordingListener[4];
        for (int i = 0; i < 4; i++) fills[i] = new RecordingListener();
        UUID p1 = h.player.getUuid(), p2 = other.getUuid();
        h.fill(sections[0], "minecraft:stone", fills[0]);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fills[0].result != null, "fill 1 running"))
                .createAndAdd(() -> h.fill(sections[1], "minecraft:stone", fills[1]))
                .createAndAdd(() -> check(fills[1].result != null, "fill 2 running"))
                .createAndAdd(() -> {
                    check(h.events.evictions.isEmpty(), "early evictions " + h.events.evictions);
                    h.fill(sections[2], "minecraft:stone", fills[2]);
                })
                .createAndAdd(() -> check(fills[2].result != null, "fill 3 running"))
                .createAndAdd(() -> {
                    // Entry cap 2: player 1's first entry is discarded.
                    check(h.events.evictions.equals(List.of(p1 + ":1")), "evictions " + h.events.evictions);
                    check(h.service.historyService().undoEntries(p1).size() == 2, "player 1 entries");
                    h.fill(other, sections[3], "minecraft:stone", fills[3]);
                })
                .createAndAdd(() -> check(fills[3].result != null, "fill 4 running"))
                .createAndAdd(() -> {
                    // Three entries exceed 2.5 entries' bytes: player 1's older entry is the oldest overall.
                    check(h.events.evictions.equals(List.of(p1 + ":1", p1 + ":1")), "evictions " + h.events.evictions);
                    check(h.service.historyService().undoEntries(p1).size() == 1, "player 1 entries");
                    check(h.service.historyService().undoEntries(p2).size() == 1, "player 2 entries");
                    check(h.service.historyService().totalBytes() <= size * 5 / 2, "total " + h.service.historyService().totalBytes());
                    check(h.service.historyService().undoEntries(p1).get(0).record().estimatedBytes() == size,
                            "entry size differs from the probe");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The {@code /sculptory} debug commands drive the server's own edit service: fill, undo and redo change and restore
     * every cell exactly, history and jobs report it, refusals are reported, and cancel stops a queued fill.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_commands", tickLimit = LIMIT)
    public void debugCommandsFillUndoRedo(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        EngineEditService host = EditServiceHost.find(world.getServer())
                .orElseThrow(() -> new GameTestException("the mod did not install the edit service"));
        int[] at = regionCorner(context, 23);
        Box region = box(at[0], 112, at[1], at[0] + 7, 119, at[1] + 7);
        Box big = box(at[0] + 32, 112, at[1], at[0] + 95, 143, at[1] + 63);
        loadAndForce(world, box(at[0], 112, at[1], at[0] + 95, 143, at[1] + 63));
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        for (int i = 0; i < 8; i++) writer.write(at[0] + i, 112 + i, at[1] + 7 - i, h.state("minecraft:dirt"), null);
        WorldSnapshot before = capture(world, region);
        WorldSnapshot bigBefore = capture(world, big);
        UUID id = h.player.getUuid();
        EditTestSupport.CapturedOutput out = new EditTestSupport.CapturedOutput();
        String fill = "sculptory fill " + at[0] + " 112 " + at[1] + " " + (at[0] + 7) + " 119 " + (at[1] + 7) + " minecraft:gold_block";
        command(h, out, fill);
        check(out.all().contains("Fill started"), "fill output: " + out.all());
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(host.jobs(id).isEmpty(), "fill running"))
                .createAndAdd(() -> {
                    check(countOf(world, region, Blocks.GOLD_BLOCK) == region.volume(), "not every cell is gold");
                    check(host.history(h.player).undoLabel().equals("Fill · 512 blocks"), "history " + host.history(h.player));
                    out.lines.clear();
                    command(h, out, "sculptory history");
                    check(out.all().contains("1. Fill · 512 blocks"), "history output: " + out.all());
                    out.lines.clear();
                    command(h, out, "sculptory jobs");
                    check(out.all().contains("Your jobs: 0"), "jobs output: " + out.all());
                    command(h, out, "sculptory undo");
                })
                .createAndAdd(() -> check(host.jobs(id).isEmpty(), "undo running"))
                .createAndAdd(() -> {
                    checkSame(before, capture(world, region), "after /sculptory undo");
                    check(host.history(h.player).canRedo(), "history " + host.history(h.player));
                    command(h, out, "sculptory redo");
                })
                .createAndAdd(() -> check(host.jobs(id).isEmpty(), "redo running"))
                .createAndAdd(() -> {
                    check(countOf(world, region, Blocks.GOLD_BLOCK) == region.volume(), "redo did not refill");
                    out.lines.clear();
                    command(h, out, "sculptory redo");
                    check(out.all().contains("Redo refused: HISTORY_EMPTY"), "redo output: " + out.all());
                    // A fill that is still queued (it starts next tick) is cancelled at once and changes nothing.
                    command(h, out, "sculptory fill " + big.min().x() + " 112 " + big.min().z() + " " + big.max().x() + " 143 "
                            + big.max().z() + " minecraft:stone");
                    out.lines.clear();
                    command(h, out, "sculptory cancel");
                    check(out.all().contains("Cancelled 1 job"), "cancel output: " + out.all());
                    check(host.jobs(id).isEmpty(), "the cancelled job is still listed");
                })
                .createAndAdd(() -> {
                    checkSame(bigBefore, capture(world, big), "the cancelled fill");
                    HistorySnapshot history = host.history(h.player);
                    check(history.undoLabels().equals(List.of("Fill · 512 blocks")) && !history.canRedo(),
                            "history " + history);
                    host.playerLeft(id);
                    forceChunks(world, box(at[0], 112, at[1], at[0] + 95, 143, at[1] + 63), false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    private static void command(Harness h, EditTestSupport.CapturedOutput out, String command) {
        h.world.getServer().getCommandManager().executeWithPrefix(h.player.getCommandSource().withOutput(out), command);
    }

    private static long countOf(ServerWorld world, Box box, net.minecraft.block.Block block) {
        long n = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (world.getBlockState(pos(x, y, z)).isOf(block)) n++;
                }
            }
        }
        return n;
    }

    /**
     * Regression (review H1): an undo while the player's own job is still running is refused, so the job's entry
     * cannot land after the undo and discard the entry just undone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_undo_running", tickLimit = LIMIT)
    public void undoRefusedWhileOwnJobRuns(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 24);
        Box region = box(at[0], 112, at[1], at[0] + 63, 143, at[1] + 63);
        loadAndForce(world, region);
        WorldSnapshot before = capture(world, region);
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        RecordingListener undoSecond = new RecordingListener();
        RecordingListener undoFirst = new RecordingListener();
        h.fill(region, "minecraft:stone", first);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(first.result != null, "first fill running"))
                .createAndAdd(() -> {
                    h.fill(region, "minecraft:dirt", second);
                    try {
                        h.service.undo(h.player, ConflictPolicy.SKIP_CONFLICTS);
                        throw new GameTestException("undo admitted while the player's fill is running");
                    } catch (EditRejected e) {
                        check(e.reason() == RejectReason.QUEUE_FULL, "reason " + e.reason());
                    }
                })
                .createAndAdd(() -> check(second.result != null, "second fill running"))
                .createAndAdd(() -> {
                    check(h.history().undoLabels().size() == 2, "history " + h.history());
                    h.undo(undoSecond);
                })
                .createAndAdd(() -> check(undoSecond.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undoSecond.result.skippedConflicts() == 0, "conflicts " + undoSecond.result);
                    check(h.history().undoLabels().size() == 1 && h.history().redoLabels().size() == 1,
                            "history " + h.history());
                    h.undo(undoFirst);
                })
                .createAndAdd(() -> check(undoFirst.result != null, "undo running"))
                .createAndAdd(() -> {
                    checkSame(before, capture(world, region), "after both undos");
                    forceChunks(world, region, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Review M1: a redo cancelled after its first section still counts as redone, so its partial result is
     * undoable; after a new edit, undoing everything restores the region exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_partial_redo", tickLimit = LIMIT)
    public void cancelledRedoStaysUndoable(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 25);
        Box region = box(at[0], 112, at[1], at[0] + 63, 143, at[1] + 63); // 32 sections
        Box other = box(at[0] + 80, 112, at[1], at[0] + 87, 119, at[1] + 7);
        Box all = box(at[0], 112, at[1], at[0] + 87, 143, at[1] + 63);
        loadAndForce(world, all);
        WorldSnapshot before = capture(world, region);
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        RecordingListener redo = new RecordingListener();
        RecordingListener newEdit = new RecordingListener();
        RecordingListener undoNew = new RecordingListener();
        RecordingListener undoPartial = new RecordingListener();
        UUID[] redoId = new UUID[1];
        JobListener cancelRedo = new JobListener() {
            @Override
            public void progress(UUID job, long done, long total, Phase ph) {
                redo.progress(job, done, total, ph);
                if (ph == Phase.APPLY && redoId[0] != null) h.service.cancel(h.player, redoId[0]);
            }

            @Override
            public void finished(JobResult r) {
                redo.finished(r);
            }
        };
        h.fill(region, "minecraft:stone", fill);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> h.undo(undo))
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> redoId[0] = h.redo(cancelRedo).jobId())
                .createAndAdd(() -> check(redo.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redo.result.outcome() == JobOutcome.CANCELLED && redo.result.changed() == 4096,
                            "redo " + redo.result);
                    HistorySnapshot history = h.history();
                    check(history.canUndo() && !history.canRedo() && history.undoLabel().equals("Fill · 131,072 blocks"),
                            "the partial redo is not undoable: " + history);
                    h.fill(other, "minecraft:gold_block", newEdit);
                })
                .createAndAdd(() -> check(newEdit.result != null, "new edit running"))
                .createAndAdd(() -> h.undo(undoNew))
                .createAndAdd(() -> check(undoNew.result != null, "undo of the new edit running"))
                .createAndAdd(() -> h.undo(undoPartial))
                .createAndAdd(() -> check(undoPartial.result != null, "undo of the partial redo running"))
                .createAndAdd(() -> {
                    check(undoPartial.result.skippedConflicts() == 0 && undoPartial.result.changed() == 4096,
                            "undo " + undoPartial.result);
                    checkSame(before, capture(world, region), "after undoing the partial redo");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A non-op builder (nodes granted by a permissions mod, no operator NBT right) undoes a fill over a sign: the
     * sign's text, operator-only NBT, comes back intact because history tiles are server-captured and trusted.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_nonop_nbt", tickLimit = LIMIT)
    public void nonOpUndoRestoresTrustedNbt(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.REGION);
        check(h.runtime.permissions().has(builder, Perm.REGION), "grant did not apply");
        check(!h.runtime.permissions().mayWriteOperatorNbt(builder), "the builder may write operator NBT");
        int[] at = regionCorner(context, 26);
        Box region = box(at[0], 112, at[1], at[0] + 7, 119, at[1] + 7);
        loadAndForce(world, region);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        BlockPos sign = pos(at[0] + 3, 113, at[1] + 3);
        writer.write(sign.getX(), sign.getY(), sign.getZ(), h.state("minecraft:oak_sign"), null);
        ((SignBlockEntity) world.getBlockEntity(sign)).setText(
                new SignText().withMessage(0, Text.literal("Builder")).withMessage(1, Text.literal("Suite")), true);
        WorldSnapshot before = capture(world, region);
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        h.fill(builder, region, "minecraft:stone", fill);
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> {
                    check(world.getBlockEntity(sign) == null, "the sign survived the fill");
                    try {
                        h.service.undo(builder, ConflictPolicy.SKIP_CONFLICTS, undo);
                    } catch (EditRejected e) {
                        throw new GameTestException("undo refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.strippedNbt() == 0,
                            "undo " + undo.result);
                    SignBlockEntity restored = (SignBlockEntity) world.getBlockEntity(sign);
                    check(restored != null && restored.getFrontText().getMessage(0, false).getString().equals("Builder")
                            && restored.getFrontText().getMessage(1, false).getString().equals("Suite"),
                            "sign text lost");
                    checkSame(before, capture(world, region), "after the non-op undo");
                    forceChunks(world, region, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The player leaves while their undo runs: it finishes in the world, history is dropped, nothing is pushed. (An undo
     * still waiting to start is cancelled on leaving, as any waiting job: see {@code MultiplayerGameTest}.) A private
     * executor ticked by hand, so the undo is really running when the player leaves.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_leave_undo", tickLimit = LIMIT)
    public void disconnectDuringUndo(TestContext context) {
        EditExecutor executor = new EditExecutor(context.getWorld().getServer(), EngineTestSupport.runtime(context).states(),
                new EditExecutor.Settings(200_000_000L, 4096, 0.4, 2, 8, 32, 64, UnloadedPolicy.LOAD, 1024, 16_384));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 27);
        Box region = box(at[0], 112, at[1], at[0] + 63, 143, at[1] + 63);
        loadAndForce(world, region);
        WorldSnapshot before = capture(world, region);
        UUID id = h.player.getUuid();
        RecordingListener fill = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        RecordingListener later = new RecordingListener();
        h.fill(region, "minecraft:stone", fill);
        runUntilFinished(executor, fill, "the fill");
        h.undo(undo);
        executor.tick();
        check(undo.result == null && undo.phases.contains(Phase.APPLY), "the undo is not running: " + undo.phases);
        check(h.service.historyService().find(id).map(s -> s.busy()).orElse(false), "undo not in flight");
        h.service.playerLeft(id);
        check(h.service.historyService().find(id).isEmpty(), "history kept after leaving");
        runUntilFinished(executor, undo, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
        checkSame(before, capture(world, region), "the undo after leaving");
        check(h.history().equals(HistorySnapshot.EMPTY), "history " + h.history());
        // Back again: a fresh, idle history.
        h.fill(box(at[0], 112, at[1], at[0] + 3, 115, at[1] + 3), "minecraft:gold_block", later);
        runUntilFinished(executor, later, "the later fill");
        HistorySnapshot history = h.history();
        check(history.undoLabels().equals(List.of("Fill · 64 blocks")) && !history.busy(), "history " + history);
        executor.shutdown();
        forceChunks(world, region, false);
        h.close();
        context.complete();
    }

    /** A job cancelled after its first section keeps (and records) that section; undoing it restores the region. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_cancel", tickLimit = LIMIT)
    public void cancelMidJobUndoable(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 17);
        Box region = box(at[0], 112, at[1], at[0] + 63, 143, at[1] + 63); // 32 sections
        loadAndForce(world, region);
        BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
        for (int dx = 0; dx < 64; dx += 3) {
            for (int dz = 0; dz < 64; dz += 2) writer.write(at[0] + dx, 112 + (dx + dz) % 30, at[1] + dz, h.state("minecraft:dirt"), null);
        }
        WorldSnapshot before = capture(world, region);
        UUID[] id = new UUID[1];
        boolean[] cancelled = {false};
        RecordingListener undo = new RecordingListener();
        RecordingListener fill = new RecordingListener();
        JobListener cancelOnApply = new JobListener() {
            @Override
            public void progress(UUID job, long done, long total, Phase ph) {
                fill.progress(job, done, total, ph);
                // APPLY is reported just before the first section; cancel takes effect after it.
                if (ph == Phase.APPLY && !cancelled[0]) cancelled[0] = h.service.cancel(h.player, id[0]);
            }

            @Override
            public void finished(JobResult r) {
                fill.finished(r);
            }
        };
        id[0] = h.fill(region, "minecraft:stone", cancelOnApply).jobId();
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fill.result != null, "fill running"))
                .createAndAdd(() -> {
                    check(cancelled[0], "cancel returned false");
                    check(fill.result.outcome() == JobOutcome.CANCELLED, "outcome " + fill.result.outcome());
                    check(fill.result.changed() == 4096, "changed " + fill.result.changed() + "; expected one section");
                    HistorySnapshot history = h.history();
                    check(history.undoLabel().equals("Fill (cancelled) · 4,096 blocks"), "history " + history);
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.outcome() == JobOutcome.COMPLETED, "undo " + undo.result);
                    checkSame(before, capture(world, region), "after undo");
                    forceChunks(world, region, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A copy of {@code snapshot} with the states at {@code cells} set to {@code states} (no tiles there). */
    private static WorldSnapshot with(WorldSnapshot snapshot, List<BlockPos> cells, List<Integer> states) {
        WorldSnapshot copy = new WorldSnapshot(snapshot.box, snapshot.states.clone());
        copy.tiles.putAll(snapshot.tiles);
        for (int i = 0; i < cells.size(); i++) {
            BlockPos p = cells.get(i);
            copy.states[copy.index(p.getX(), p.getY(), p.getZ())] = states.get(i);
        }
        return copy;
    }

    private static void runUntilFinished(EditExecutor executor, RecordingListener listener, String what) {
        for (int i = 0; i < 10_000 && listener.result == null; i++) executor.tick();
        check(listener.result != null, what + " did not finish");
    }

    /**
     * An undo, then a redo, each written over many ticks (8 cells a tick, one section): a cell changed after the
     * section was computed but before it is written survives and is reported as a conflict (the write-time check). Redo
     * after that partial undo, and undo again, leave every other cell exact.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_edit_history_race", tickLimit = LIMIT)
    public void undoAndRedoLeaveACellChangedWhileTheyWrite(TestContext context) {
        EditExecutor executor = new EditExecutor(context.getWorld().getServer(), EngineTestSupport.runtime(context).states(),
                new EditExecutor.Settings(200_000_000L, 8, 0.4, 2, 8, 32, 64, UnloadedPolicy.LOAD, 1024, 16_384));
        Harness h = new Harness(context, null, System::nanoTime, executor);
        int[] at = regionCorner(context, 92);
        int x = at[0], z = at[1];
        Box layer = box(x, 100, z, x + 15, 100, z + 15);
        Box around = box(x, 99, z, x + 15, 101, z + 15);
        loadAndForce(h.world, around);
        WorldSnapshot original = capture(h.world, around);
        RecordingListener fill = new RecordingListener();
        h.fill(layer, "minecraft:stone", fill);
        runUntilFinished(executor, fill, "the fill");
        check(fill.result.outcome() == JobOutcome.COMPLETED && fill.result.changed() == 256, "fill " + fill.result);
        WorldSnapshot filled = capture(h.world, around);
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        int goldState = h.state("minecraft:gold_block"), diamondState = h.state("minecraft:diamond_block");
        BlockPos gold = pos(x + 15, 100, z + 15); // written last
        BlockPos diamond = pos(x + 14, 100, z + 15);

        RecordingListener undo = new RecordingListener();
        h.undo(undo);
        executor.tick();
        check(undo.result == null && undo.phases.contains(Phase.APPLY), "the undo is not writing: " + undo.phases);
        check(h.world.getBlockState(pos(x, 100, z)).isAir(), "nothing undone in the first tick");
        check(h.world.getBlockState(gold).isOf(Blocks.STONE), "the last cell is already undone");
        writer.write(gold.getX(), gold.getY(), gold.getZ(), goldState, null);
        runUntilFinished(executor, undo, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 1, "undo " + undo.result);
        check(h.world.getBlockState(gold).isOf(Blocks.GOLD_BLOCK), "the undo overwrote the gold block");
        checkSame(with(original, List.of(gold), List.of(goldState)), capture(h.world, around), "after the undo");

        RecordingListener redo = new RecordingListener();
        h.redo(redo);
        executor.tick();
        check(redo.result == null && redo.phases.contains(Phase.APPLY), "the redo is not writing: " + redo.phases);
        check(h.world.getBlockState(diamond).isAir(), "the cell is already redone");
        writer.write(diamond.getX(), diamond.getY(), diamond.getZ(), diamondState, null);
        runUntilFinished(executor, redo, "the redo");
        check(redo.result.outcome() == JobOutcome.COMPLETED && redo.result.skippedConflicts() == 2,
                "redo (the gold block at compute, the diamond block at write) " + redo.result);
        check(h.world.getBlockState(diamond).isOf(Blocks.DIAMOND_BLOCK), "the redo overwrote the diamond block");
        checkSame(with(filled, List.of(gold, diamond), List.of(goldState, diamondState)), capture(h.world, around),
                "after the redo");

        RecordingListener again = new RecordingListener();
        h.undo(again);
        runUntilFinished(executor, again, "the second undo");
        check(again.result.skippedConflicts() == 2, "second undo " + again.result);
        checkSame(with(original, List.of(gold, diamond), List.of(goldState, diamondState)), capture(h.world, around),
                "after undoing again");
        executor.shutdown();
        forceChunks(h.world, around, false);
        h.close();
        context.complete();
    }
}
