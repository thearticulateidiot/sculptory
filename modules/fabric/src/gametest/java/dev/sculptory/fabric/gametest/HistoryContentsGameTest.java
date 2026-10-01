package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.executor;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.fill;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.historyStep;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.run;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.tickUntil;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.EditRejected;
import dev.sculptory.fabric.engine.JobResult;
import dev.sculptory.fabric.engine.Perm;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.HistoryService;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.schem.TileSanitizer;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.protocol.v2.JobOutcome;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.CommandBlockBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/**
 * Undo and redo keep blocks whose contents changed since the step, on a real world: a chest another player filled after the edit is kept by undo and counted
 * with the kept blocks (the Undo anyway offer), and Undo anyway removes it; a chest emptied after the undo is kept by
 * redo, and Redo anyway replaces it; contents changed while the undo is still being written are kept at write time; and
 * a paste of untouched block entities recorded in a file's own form (a sanitized sign, a chest and a furnace with their
 * items, a hopper whose cooldown ticked, a barrel) still undoes and redoes exactly. A private executor ticked by the
 * test keeps every step deterministic. A non-op's paste whose command-block NBT the writer stripped undoes cleanly too.
 * GameTest regions 600-609.
 */
public final class HistoryContentsGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int TICKS = 400;

    /** Dirt at y 100 under the region, the rest as it is; the region is loaded and forced. */
    private static Box ground(Harness h, int x, int z) {
        Box region = box(x, 96, z, x + 15, 104, z + 15);
        loadAndForce(h.world, region);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        int dirt = h.state("minecraft:dirt"), air = h.state("minecraft:air");
        for (int y = 96; y <= 104; y++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) writer.write(x + dx, y, z + dz, y == 100 ? dirt : air, null);
            }
        }
        return region;
    }

    private static void fillBy(Harness h, EditExecutor executor, ServerPlayerEntity player, Box box, String state) {
        RecordingListener listener = new RecordingListener();
        run(h, player, fill(h, box, state), listener);
        tickUntil(executor, () -> listener.result != null, TICKS, "a fill");
        check(listener.result.outcome() == JobOutcome.COMPLETED, "fill " + listener.result);
    }

    private static JobResult step(Harness h, EditExecutor executor, ServerPlayerEntity player, boolean undo) {
        RecordingListener listener = historyStep(h, player, undo);
        tickUntil(executor, () -> listener.result != null, TICKS, undo ? "an undo" : "a redo");
        check(listener.result.outcome() == JobOutcome.COMPLETED, "step " + listener.result);
        return listener.result;
    }

    /** Undo anyway (or Redo anyway) of the player's whole run, run to its end. */
    private static JobResult anyway(Harness h, EditExecutor executor, ServerPlayerEntity player, boolean redo) {
        HistoryService.Run run = h.service.historyService().session(player.getUuid()).run().orElseThrow(
                () -> new GameTestException("no " + (redo ? "Redo" : "Undo") + " anyway offer"));
        RecordingListener listener = new RecordingListener();
        try {
            h.service.historyOverwrite(player, redo, run.entries().size(), listener);
        } catch (EditRejected e) {
            throw new GameTestException((redo ? "Redo" : "Undo") + " anyway refused: " + e.getMessage());
        }
        tickUntil(executor, () -> listener.result != null, TICKS, redo ? "Redo anyway" : "Undo anyway");
        check(listener.result.outcome() == JobOutcome.COMPLETED, "anyway " + listener.result);
        return listener.result;
    }

    private static int count(ServerWorld world, Box box, Block block) {
        int n = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (world.getBlockState(pos(x, y, z)).isOf(block)) n++;
                }
            }
        }
        return n;
    }

    private static void checkChest(ServerWorld world, BlockPos pos, net.minecraft.item.Item item, int count) {
        check(world.getBlockEntity(pos) instanceof ChestBlockEntity, "no chest at " + pos.toShortString());
        ItemStack stack = ((ChestBlockEntity) world.getBlockEntity(pos)).getStack(0);
        check(count == 0 ? stack.isEmpty() : stack.isOf(item) && stack.getCount() == count, "the chest holds " + stack);
    }

    private static void noDrops(ServerWorld world, Box region) {
        check(world.getEntitiesByType(EntityType.ITEM, new net.minecraft.util.math.Box(region.min().x(), region.min().y(),
                region.min().z(), region.max().x() + 1, region.max().y() + 1, region.max().z() + 1), e -> true).isEmpty(),
                "items dropped");
    }

    private static NbtCompound item(int slot, String id, int count) {
        // A byte count, as older tools write it: the game keeps an int.
        return NbtCompound.builder().putByte("Slot", (byte) slot).putString("id", id).putByte("count", (byte) count)
                .build();
    }

    private static NbtCompound signSide(String json) {
        return NbtCompound.builder().put("messages", NbtList.ofStrings(List.of(json, "\"\"", "\"\"", "\"\"")))
                .putString("color", "black").build();
    }

    private static NbtBytes foreign(String type, NbtCompound data) {
        return new NbtBytes(type, NbtIo.toBytes(data));
    }

    /**
     * A row of six cells as a schematic brings them, sanitized as a file's clipboard is: a chest with items (items first,
     * byte counts, no id), a sign whose text has a click event (the sanitizer removes it), a furnace with raw iron and
     * none of its timers, then a hopper and a barrel without data and a stone block. None of that is in the form the game
     * writes it back.
     */
    static Clipboard untouchedContents(Harness h) {
        String click = "{\"text\":\"Hi\",\"clickEvent\":{\"action\":\"run_command\",\"value\":\"/op @a\"}}";
        Clipboard.Builder b = Clipboard.builder(h.runtime.states(), new dev.sculptory.core.BlockPos(6, 1, 1));
        b.set(0, 0, 0, h.state("minecraft:chest[facing=north]"));
        b.setTile(0, 0, 0, foreign("minecraft:chest", NbtCompound.builder()
                .put("Items", NbtList.of(List.of(item(0, "minecraft:diamond", 5), item(13, "minecraft:oak_log", 64))))
                .build()));
        b.set(1, 0, 0, h.state("minecraft:oak_sign[rotation=4]"));
        b.setTile(1, 0, 0, foreign("minecraft:sign", NbtCompound.builder().put("front_text", signSide(click)).build()));
        b.set(2, 0, 0, h.state("minecraft:furnace[facing=north]"));
        b.setTile(2, 0, 0, foreign("minecraft:furnace", NbtCompound.builder()
                .put("Items", NbtList.of(List.of(item(0, "minecraft:raw_iron", 3)))).build()));
        b.set(3, 0, 0, h.state("minecraft:hopper[facing=down]"));
        b.set(4, 0, 0, h.state("minecraft:barrel[facing=up]"));
        b.set(5, 0, 0, h.state("minecraft:stone"));
        return TileSanitizer.sanitize(b.build()).clipboard();
    }

    /** The hopper's cooldown changes as on its first tick (-1 to 0), which nobody sees or sets. */
    static void tickHopper(ServerWorld world, BlockPos at) {
        HopperBlockEntity hopper = (HopperBlockEntity) world.getBlockEntity(at);
        check(hopper != null, "no hopper at " + at.toShortString());
        net.minecraft.nbt.NbtCompound nbt = hopper.createNbt(world.getRegistryManager());
        check(nbt.getInt("TransferCooldown") != 0, "the hopper's cooldown was already 0");
        nbt.putInt("TransferCooldown", 0);
        hopper.read(nbt, world.getRegistryManager());
    }

    // ---------------------------------------------------------------- undo

    /**
     * Alice places a row of five chests with a fill; Bob puts diamonds in the middle one. Alice's undo takes back the
     * four untouched chests and keeps Bob's, counted as one kept block (the Undo anyway offer shows it); Undo anyway
     * removes it too, and the region is as before the fill, with nothing dropped.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_contents_undo", tickLimit = LIMIT)
    public void undoKeepsAChestFilledSinceTheEditAndUndoAnywayRemovesIt(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        int[] at = regionCorner(context, 600);
        int x = at[0], z = at[1];
        Box region = ground(h, x, z);
        WorldSnapshot original = capture(h.world, region);
        fillBy(h, executor, alice, box(x + 2, 101, z + 2, x + 6, 101, z + 2), "minecraft:chest[facing=south]");
        check(count(h.world, region, Blocks.CHEST) == 5, "the fill placed five chests");
        BlockPos filled = pos(x + 4, 101, z + 2);
        ((ChestBlockEntity) h.world.getBlockEntity(filled)).setStack(0, new ItemStack(Items.DIAMOND, 5));

        JobResult undo = step(h, executor, alice, true);
        check(undo.skippedConflicts() == 1 && undo.changed() == 4, "the undo " + undo);
        check(count(h.world, region, Blocks.CHEST) == 1, "only Bob's chest is left");
        checkChest(h.world, filled, Items.DIAMOND, 5);
        HistoryService.Run run = h.service.historyService().session(alice.getUuid()).run().orElseThrow();
        check(run.conflicts() == 1 && run.op() == HistoryService.Op.UNDO, "the offer " + run);

        JobResult anyway = anyway(h, executor, alice, false);
        check(anyway.changed() == 1 && anyway.skippedConflicts() == 0, "Undo anyway " + anyway);
        checkSame(original, capture(h.world, region), "Undo anyway removed the filled chest");
        noDrops(h.world, region);
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * Alice pastes a row of block entities recorded in a file's form, not the game's (see {@link #untouchedContents}),
     * and the hopper's cooldown ticks. Nobody touches them: undo restores the region exactly with nothing kept, redo
     * brings the paste back exactly, and undo again restores it once more.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_contents_untouched", tickLimit = LIMIT)
    public void anUntouchedPasteOfChestSignAndFurnaceUndoesAndRedoesExactly(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        int[] at = regionCorner(context, 602);
        int x = at[0], z = at[1];
        Box region = ground(h, x, z);
        WorldSnapshot original = capture(h.world, region);
        UUID clipboard = h.service.clipboards().install(alice.getUuid(), untouchedContents(h)).id();
        RecordingListener paste = new RecordingListener();
        run(h, alice, new OpSpec.Paste(new SourceRef.Clipboard(clipboard), new dev.sculptory.core.BlockPos(x + 2, 101,
                z + 2), Transform.IDENTITY, PasteOptions.DEFAULT), paste);
        tickUntil(executor, () -> paste.result != null, TICKS, "the paste");
        check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.changed() == 6, "the paste " + paste.result);
        WorldSnapshot pasted = capture(h.world, region);
        checkChest(h.world, pos(x + 2, 101, z + 2), Items.DIAMOND, 5);
        String text = ((SignBlockEntity) h.world.getBlockEntity(pos(x + 3, 101, z + 2))).getText(true)
                .getMessage(0, false).getString();
        check(text.equals("Hi"), "the sign reads \"" + text + "\"");
        tickHopper(h.world, pos(x + 5, 101, z + 2));

        JobResult undo = step(h, executor, alice, true);
        check(undo.skippedConflicts() == 0 && undo.changed() == 6, "the undo " + undo);
        checkSame(original, capture(h.world, region), "undo of the untouched paste");
        JobResult redo = step(h, executor, alice, false);
        check(redo.skippedConflicts() == 0 && redo.changed() == 6, "the redo " + redo);
        checkSame(pasted, capture(h.world, region), "redo of the paste");
        check(step(h, executor, alice, true).skippedConflicts() == 0, "the second undo kept blocks");
        checkSame(original, capture(h.world, region), "undo after the redo");
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * The undo of a fill of 1,024 chests is written 64 cells a tick; after its first tick, Bob fills a chest it has not
     * reached yet. The write-time check sees the change: that chest is kept and counted, every other chest is removed.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_contents_while_writing", tickLimit = LIMIT)
    public void contentsChangedWhileTheUndoIsWrittenAreKept(TestContext context) {
        EditExecutor executor = executor(context, 64);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        int[] at = regionCorner(context, 604);
        int x = at[0], z = at[1];
        Box region = ground(h, x, z);
        Box chests = box(x, 96, z, x + 15, 99, z + 15); // one section, below the dirt
        fillBy(h, executor, alice, chests, "minecraft:chest[facing=west]");
        BlockPos first = pos(x, 96, z);
        BlockPos last = pos(x + 15, 99, z + 15); // written last: the section's highest index
        RecordingListener undo = historyStep(h, alice, true);
        for (int i = 0; i < 10 && h.world.getBlockState(first).isOf(Blocks.CHEST); i++) executor.tick();
        check(!h.world.getBlockState(first).isOf(Blocks.CHEST) && undo.result == null
                && h.world.getBlockState(last).isOf(Blocks.CHEST), "setup: expected the undo partly written");
        ((ChestBlockEntity) h.world.getBlockEntity(last)).setStack(0, new ItemStack(Items.EMERALD, 3));
        tickUntil(executor, () -> undo.result != null, TICKS, "the undo");
        check(undo.result.outcome() == JobOutcome.COMPLETED && undo.result.skippedConflicts() == 1
                && undo.result.changed() == 1023, "the undo " + undo.result);
        check(count(h.world, chests, Blocks.CHEST) == 1, "only the filled chest is left");
        checkChest(h.world, last, Items.EMERALD, 3);
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    /**
     * Carol has no operator rights and pastes a command block carrying a command: the writer strips its NBT, so the
     * world holds the default command block while the step records no data for it. Her undo sees that default as what
     * the step left (nothing kept) and restores the region exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_contents_stripped", tickLimit = LIMIT)
    public void aNonOpsStrippedCommandBlockPasteUndoesCleanly(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity carol = h.addPlayer(false);
        EditTestSupport.grant(carol, Perm.USE, Perm.REGION, Perm.CLIPBOARD);
        int[] at = regionCorner(context, 609);
        int x = at[0], z = at[1];
        Box region = ground(h, x, z);
        WorldSnapshot original = capture(h.world, region);
        UUID clipboard = h.service.clipboards().install(carol.getUuid(), MultiplayerGameTest.commandBlockClipboard(h))
                .id();
        RecordingListener paste = new RecordingListener();
        run(h, carol, MultiplayerGameTest.paste(clipboard, new dev.sculptory.core.BlockPos(x + 3, 101, z + 3)), paste);
        tickUntil(executor, () -> paste.result != null, TICKS, "the paste");
        check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.changed() == 1
                && paste.result.strippedNbt() == 1, "the paste " + paste.result);
        check(h.world.getBlockEntity(pos(x + 3, 101, z + 3)) instanceof CommandBlockBlockEntity command
                && command.getCommandExecutor().getCommand().isEmpty(), "the command was not stripped");

        JobResult undo = step(h, executor, carol, true);
        check(undo.skippedConflicts() == 0 && undo.changed() == 1, "the undo " + undo);
        checkSame(original, capture(h.world, region), "the stripped paste undone");
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }

    // ---------------------------------------------------------------- redo

    /**
     * Two chests of diamonds are under Alice's fill of stone; her undo brings both back. Bob empties the first one. Her
     * redo keeps it (one kept block) and fills the other with stone as before; Redo anyway replaces the emptied chest
     * too, and the region is as the fill left it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_contents_redo", tickLimit = LIMIT)
    public void redoKeepsAChestEmptiedSinceTheUndoAndRedoAnywayReplacesIt(TestContext context) {
        EditExecutor executor = executor(context, 0);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerPlayerEntity alice = h.player;
        int[] at = regionCorner(context, 606);
        int x = at[0], z = at[1];
        Box region = ground(h, x, z);
        BlockWriter writer = h.runtime.writer(h.world, BlockWriter.Options.DEFAULT);
        BlockPos emptied = pos(x + 3, 101, z + 3);
        BlockPos kept = pos(x + 6, 101, z + 3);
        for (BlockPos chest : List.of(emptied, kept)) {
            writer.write(chest.getX(), chest.getY(), chest.getZ(), h.state("minecraft:chest[facing=east]"), null);
            ((ChestBlockEntity) h.world.getBlockEntity(chest)).setStack(0, new ItemStack(Items.DIAMOND, 5));
        }
        WorldSnapshot original = capture(h.world, region);
        fillBy(h, executor, alice, box(x + 2, 101, z + 2, x + 8, 101, z + 4), "minecraft:stone");
        WorldSnapshot filled = capture(h.world, region);
        check(step(h, executor, alice, true).skippedConflicts() == 0, "the undo kept blocks");
        checkSame(original, capture(h.world, region), "the undo");
        ((ChestBlockEntity) h.world.getBlockEntity(emptied)).setStack(0, ItemStack.EMPTY);

        JobResult redo = step(h, executor, alice, false);
        check(redo.skippedConflicts() == 1 && redo.changed() == 20, "the redo " + redo);
        checkChest(h.world, emptied, Items.DIAMOND, 0);
        check(h.world.getBlockState(kept).isOf(Blocks.STONE), "the untouched chest was not redone");
        HistoryService.Run run = h.service.historyService().session(alice.getUuid()).run().orElseThrow();
        check(run.conflicts() == 1 && run.op() == HistoryService.Op.REDO, "the offer " + run);

        JobResult anyway = anyway(h, executor, alice, true);
        check(anyway.changed() == 1, "Redo anyway " + anyway);
        checkSame(filled, capture(h.world, region), "Redo anyway replaced the emptied chest");
        noDrops(h.world, region);
        forceChunks(h.world, region, false);
        executor.shutdown();
        h.close();
        context.complete();
    }
}
