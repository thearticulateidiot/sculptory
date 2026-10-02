package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.pos;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.FLOOR;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.SIDE;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.WATER_TICKS;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.checkExact;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.count;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.fluidOutside;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.leftover;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.nudge;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.settles;
import static dev.sculptory.fabric.gametest.FluidUndoGameTest.terrain;

import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.store.StorageIo;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.JobRequest;
import dev.sculptory.fabric.gametest.DurableHistoryGameTest.Run;
import dev.sculptory.fabric.gametest.DurableHistoryGameTest.Scene;
import dev.sculptory.fabric.gametest.DurableHistoryGameTest.WritesWhileWaiting;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.gametest.mixin.ChunkUnloadAccessor;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FluidTrails;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.platform.WriteOptions;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.command.FillBiomeCommand;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.ChunkSerializer;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeKeys;

/**
 * What edited fluid did survives a restart: its trail is folded into the
 * entry and journaled when the game saves the chunks it lies in (and when the player's history is unloaded or the
 * server stops), and its marks are saved in the chunks' own data, so water still flowing after a restart is followed.
 *
 * <p>A <b>restart</b> here is the durable-history one ({@link DurableHistoryGameTest}: a new edit service over the same
 * history folder) plus fluid trails starting again: the chunks are serialized as the game saves them (the real
 * {@code ChunkSerializer.serialize}, whose hook adds the marks), {@link FluidTrails#restart} forgets everything, and the
 * chunks are "loaded" from that data ({@link FluidTrails#chunkLoaded}, what the {@code deserialize} hook calls). The
 * world itself keeps running, so nothing ticks between the stop and the start (it all happens within one tick).
 * {@link #marksTravelWithRealChunkSavesAndLoads} checks the hooks with a real unload and reload. regionCorner slots
 * 950-957 and 961-963.
 */
public final class FluidRestartGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    // =================================================================== helpers

    /** The chunks under {@code area} as the game would save them now: their data, fluid marks included. */
    static Map<Long, NbtCompound> saveChunks(ServerWorld world, Box area) {
        Map<Long, NbtCompound> saved = new LinkedHashMap<>();
        for (int cx = area.min().x() >> 4; cx <= area.max().x() >> 4; cx++) {
            for (int cz = area.min().z() >> 4; cz <= area.max().z() >> 4; cz++) {
                saved.put(ChunkPos.toLong(cx, cz), ChunkSerializer.serialize(world, world.getChunk(cx, cz)));
            }
        }
        return saved;
    }

    /** Fluid trails start again: memory is forgotten, and the chunks load from {@code saved}. */
    static void restartTrails(ServerWorld world, FluidTrails trails, Map<Long, NbtCompound> saved) {
        trails.restart();
        for (Map.Entry<Long, NbtCompound> chunk : saved.entrySet()) {
            FluidTrails.chunkLoaded(world, new ChunkPos(chunk.getKey()), chunk.getValue());
        }
    }

    /** The game saves every chunk column under {@code area}: the chunk-save hook of {@code run}'s service runs. */
    static void chunkSaves(Run run, ServerWorld world, Box area) {
        for (int cx = area.min().x() >> 4; cx <= area.max().x() >> 4; cx++) {
            for (int cz = area.min().z() >> 4; cz <= area.max().z() >> 4; cz++) {
                check(run.service.beforeChunkSave(world, cx, cz), "the chunk-save hook ran off the server thread");
            }
        }
    }

    /** Puts {@code snapshot}'s states back (physics off, no scheduled ticks): the world as a chunk save left it. */
    static void restore(Harness h, WorldSnapshot snapshot) {
        BlockWriter writer = h.runtime.writer(h.world, WriteOptions.DEFAULT);
        Box box = snapshot.box;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) writer.write(x, y, z, snapshot.get(x, y, z), null);
            }
        }
        writer.clearTicksAtWrittenCells();
    }

    /** Neighbour updates along the Fill's west side, as a player placing blocks there sends: the water wakes. */
    static void wake(ServerWorld world, Box fill) {
        for (int z = fill.min().z(); z <= fill.max().z(); z++) nudge(world, fill.min().x() - 1, fill.min().y(), z);
    }

    static JobResult fill(Run run, ServerPlayerEntity player, Region region, String state) {
        RecordingListener listener = new RecordingListener();
        try {
            run.service.run(player, new OpSpec.Fill(region, new Pattern.Single(run.h.state(state)), CellMask.ANY),
                    RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException("the fill was refused: " + e.getMessage());
        }
        MultiplayerGameTest.tickUntil(run.executor, () -> listener.result != null, 2000, "the fill");
        run.service.tick();
        check(listener.result.outcome() == JobOutcome.COMPLETED, "the fill " + listener.result);
        return listener.result;
    }

    /** The player's next undo step (a failed check, not an exception in the server tick, when there is none). */
    static HistoryEntry newestEntry(Run run, ServerPlayerEntity player) {
        List<HistoryEntry> steps = run.undoEntries(player);
        check(!steps.isEmpty(), "the player has no step to undo");
        return steps.get(0);
    }

    static UUID newest(Run run, ServerPlayerEntity player) {
        return newestEntry(run, player).id();
    }

    /** A clean stop, then a start over the same history folder: the new run, with the player's history loaded. */
    static Run restart(Scene s, Run first, ServerPlayerEntity player, Box area) {
        first.stop();
        restartTrails(s.h.world, s.h.runtime.fluidTrails(), saveChunks(s.h.world, area));
        Run second = s.run();
        second.load(player);
        return second;
    }

    // =================================================================== restarts

    /**
     * The fluid scenario across a restart: a water Fill on grass; random ticks kill the grass under it and the water
     * flows out. The server stops and starts again. Undo leaves the area exactly as before the Fill, the grass
     * included, and it stays so; redo puts back the water as the undo found it; undo again restores the area.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_grass", tickLimit = LIMIT)
    public void aWaterFillOnGrassUndoesExactlyAfterARestart(TestContext context) {
        Scene s = new Scene(context, 950);
        Harness h = s.h;
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        ServerPlayerEntity alice = h.player;
        int x0 = s.x, z0 = s.z;
        Box area = terrain(h, x0, z0, "minecraft:grass_block");
        s.region = area;
        Box fill = box(x0 + 18, FLOOR + 1, z0 + 18, x0 + 29, FLOOR + 2, z0 + 29);
        BlockPos inside = pos(x0 + 20, FLOOR + 1, z0 + 20);
        WorldSnapshot before = capture(world, area);
        Run first = s.run();
        first.load(alice);
        fill(first, alice, new Region.Cuboid(fill), "minecraft:water");
        UUID id = newest(first, alice);
        WorldSnapshot[] flowed = new WorldSnapshot[1];
        Run[] second = new Run[1];
        context.createTimedTaskRunner()
                .createAndAddReported(() -> {
                    check(trails.markedBy(world, inside, id), "the Fill's water is marked");
                    for (int i = 0; i < 8 && count(world, area, Blocks.DIRT) == 0; i++) {
                        world.tickChunk(world.getChunk((x0 + 18) >> 4, (z0 + 18) >> 4), 4096);
                    }
                    check(count(world, area, Blocks.DIRT) > 0, "grass under the water died");
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the water flowed out of the Fill");
                    flowed[0] = capture(world, area);
                    second[0] = restart(s, first, alice, area);
                    check(trails.markedBy(world, inside, id), "the marks came back with the chunks");
                    HistoryEntry entry = newestEntry(second[0], alice);
                    check(entry.id().equals(id) && entry.record().before().cellCount() > fill.volume(),
                            "the saved step holds what the water did: " + entry.record().before().cellCount());
                    JobResult undo = second[0].step(alice, true);
                    check(undo.skippedConflicts() == 0, "nothing kept: " + undo);
                    checkExact(world, before, "restart_grass: undo after the restart");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "restart_grass: " + WATER_TICKS + " ticks after the undo");
                    check(second[0].step(alice, false).skippedConflicts() == 0, "the redo met conflicts");
                    checkSame(flowed[0], capture(world, area), "the redo puts back the water as the undo found it");
                    second[0].step(alice, true);
                    checkExact(world, before, "restart_grass: undo, redo and undo after the restart");
                    second[0].stop();
                    s.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The server stops while the water still flows: the chunks keep its scheduled ticks, and after the restart it goes
     * on flowing, followed from the first tick (its marks load with the chunks, before they tick). An undo refused then
     * (the queue is full) gives the trail back and changes nothing; the next undo leaves the area as before the Fill.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_mid_flow", tickLimit = LIMIT)
    public void waterStillFlowingAtARestartIsFollowedAndARefusedUndoGivesItsTrailBack(TestContext context) {
        Scene s = new Scene(context, 951);
        Harness h = s.h;
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        ServerPlayerEntity alice = h.player;
        int x0 = s.x, z0 = s.z;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        s.region = area;
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        WorldSnapshot before = capture(world, area);
        Run first = s.run();
        first.load(alice);
        fill(first, alice, new Region.Cuboid(fill), "minecraft:water");
        UUID id = newest(first, alice);
        wake(world, fill);
        Run[] second = new Run[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the water flows"))
                .createAndAddReported(() -> {
                    second[0] = restart(s, first, alice, area);
                    check(trails.trailCells(world, id) == 0, "nothing recorded since the restart yet");
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    long trail = trails.trailCells(world, id);
                    check(trail > 0, "the water that flowed after the restart was followed");
                    HistoryEntry held = newestEntry(second[0], alice);
                    EditExecutor executor = second[0].executor;
                    Box one = box(x0, FLOOR + 8, z0, x0, FLOOR + 8, z0);
                    List<UUID> fillers = new ArrayList<>();
                    try {
                        for (int i = 0; i <= executor.settings().maxQueued() + 8; i++) {
                            fillers.add(executor.submit(JobRequest.system(world, new EngineTestSupport.BoxFill(
                                    "filler " + i, one, h.state("minecraft:air")), null)).jobId());
                        }
                        throw new GameTestException("the queue never filled");
                    } catch (EditRejected e) {
                        check(e.reason() == RejectReason.QUEUE_FULL, "filler " + e.getMessage());
                    }
                    try {
                        second[0].service.undo(alice, ConflictPolicy.SKIP_CONFLICTS, new RecordingListener());
                        throw new GameTestException("the undo was admitted into a full queue");
                    } catch (EditRejected e) {
                        check(e.reason() == RejectReason.QUEUE_FULL, "refused " + e.getMessage());
                    } finally {
                        for (UUID filler : fillers) executor.cancel(filler);
                    }
                    check(trails.trailCells(world, id) == trail, "the trail was given back");
                    check(newestEntry(second[0], alice).record() == held.record(), "the entry was not replaced");
                    check(!trails.isFrozen(id), "the entry is not held still");
                    MultiplayerGameTest.tickUntil(executor,
                            () -> executor.activeJobCount() + executor.queuedJobCount() == 0, 200, "the fillers");
                    JobResult undo = second[0].step(alice, true);
                    check(undo.skippedConflicts() == 0, "nothing kept: " + undo);
                    checkExact(world, before, "restart_mid_flow: undo after the restart");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "restart_mid_flow: " + WATER_TICKS + " ticks after the undo");
                    second[0].stop();
                    s.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A crash between a journal write and more flow: the game saves the chunks while the water flows (the hook folds
     * what it did into the entry and writes the journal first; the disk takes writes only while the server thread
     * waits), the water flows on, and the process dies. The next run starts from what reached the disk: the history
     * folder copied at the save, and the world as the chunks were saved (with their marks). Woken again, the water is
     * followed; undo leaves the area exactly as before the Fill.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_crash", tickLimit = LIMIT)
    public void aCrashAfterAChunkSaveKeepsWhatTheSaveHeld(TestContext context) {
        Scene s = new Scene(context, 952);
        Harness h = s.h;
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        ServerPlayerEntity alice = h.player;
        int x0 = s.x, z0 = s.z;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        s.region = area;
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        WorldSnapshot before = capture(world, area);
        WritesWhileWaiting disk = new WritesWhileWaiting(Thread.currentThread());
        Run first = s.run(s.dir, 0, disk);
        first.load(alice);
        fill(first, alice, new Region.Cuboid(fill), "minecraft:water");
        UUID id = newest(first, alice);
        wake(world, fill);
        WorldSnapshot[] atSave = new WorldSnapshot[1];
        Map<Long, NbtCompound> saved = new LinkedHashMap<>();
        Path[] copy = new Path[1];
        Run[] second = new Run[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(fluidOutside(world, area, new Region.Cuboid(fill)) > 2, "the water flows"))
                .createAndAddReported(() -> {
                    // The game saves the chunks: the hook folds the trail and waits for the journal, then serializes.
                    chunkSaves(first, world, area);
                    // Standing in for a barrier that had time (its 100 ms can run out on a loaded machine; its timing is
                    // DurableHistoryGameTest's subject): the server thread waits until what was queued is written. The
                    // disk takes writes only while it waits, so the copy holds exactly what the save moment queued.
                    check(first.service.historyService().store().orElseThrow().flush(10_000), "the journal was written");
                    copy[0] = s.diskNow(s.dir);
                    atSave[0] = capture(world, area);
                    saved.putAll(saveChunks(world, area));
                    disk.holder = null;
                    check(trails.trailCells(world, id) == 0, "the save folded the whole trail");
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(EditTestSupport.difference(atSave[0], capture(world, area)) != null,
                            "the water flowed on after the save");
                    // The crash: nothing is stopped or written any more; the next run sees the disk as it was.
                    first.release();
                    restore(h, atSave[0]);
                    restartTrails(world, trails, saved);
                    second[0] = s.run(copy[0], 0, StorageIo.SYSTEM);
                    second[0].load(alice);
                    HistoryEntry entry = newestEntry(second[0], alice);
                    check(entry.id().equals(id) && entry.record().before().cellCount() > fill.volume(),
                            "the journal copy holds what the water did before the save: "
                                    + entry.record().before().cellCount() + " cells");
                    check(trails.markedBy(world, pos(x0 + 23, FLOOR + 1, z0 + 23), id), "the saved marks are back");
                    wake(world, fill);
                })
                .createAndAdd(settles(world, area, 100, "the water after the crash"))
                .createAndAddReported(() -> {
                    check(trails.trailCells(world, id) > 0, "the water was followed after the crash");
                    JobResult undo = second[0].step(alice, true);
                    check(undo.skippedConflicts() == 0, "nothing kept: " + undo);
                    checkExact(world, before, "restart_crash: undo after the crash");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "restart_crash: " + WATER_TICKS + " ticks after the undo");
                    second[0].stop();
                    s.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A journal from before trails were saved (a Fill saved before its water moved) and chunks without marks: the
     * history loads unchanged; the water is not followed, and undo restores the Fill's own cells and leaves what
     * flowed, as before this change.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_old_journal", tickLimit = LIMIT)
    public void anOldJournalWithoutTrailsLoadsAndUndoesTheEditsOwnCells(TestContext context) {
        Scene s = new Scene(context, 953);
        Harness h = s.h;
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        ServerPlayerEntity alice = h.player;
        int x0 = s.x, z0 = s.z;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        s.region = area;
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        Run first = s.run();
        first.load(alice);
        fill(first, alice, new Region.Cuboid(fill), "minecraft:water");
        UUID id = newest(first, alice);
        long cells = newestEntry(first, alice).record().before().cellCount();
        // Stopped before the water moved: the journal holds the Fill as any earlier build wrote it.
        first.stop();
        // Chunks saved by an earlier build hold no marks.
        trails.restart();
        Run second = s.run();
        second.load(alice);
        check(newestEntry(second, alice).record().before().cellCount() == cells, "the step reads back as saved");
        check(!trails.markedBy(world, pos(x0 + 23, FLOOR + 1, z0 + 23), id), "no marks without saved ones");
        wake(world, fill);
        context.createTimedTaskRunner()
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(trails.trailCells(world, id) == 0, "water without marks is not followed");
                    second.step(alice, true);
                    for (int x = fill.min().x(); x <= fill.max().x(); x++) {
                        for (int z = fill.min().z(); z <= fill.max().z(); z++) {
                            check(world.getBlockState(pos(x, FLOOR + 1, z)).isAir(), "the Fill's cells are restored");
                        }
                    }
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the water that flowed stays");
                    second.stop();
                    s.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Past its trail cap an entry is no longer followed, and stays so after a restart: its marks are gone from memory
     * and from the chunks saved since. Undo restores the Fill's cells (and what was recorded before the cap); the rest
     * stays, the documented limit.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_capped", tickLimit = LIMIT)
    public void anEntryPastItsTrailCapIsNotFollowedAfterARestart(TestContext context) {
        Scene s = new Scene(context, 954);
        Harness h = s.h;
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        ServerPlayerEntity alice = h.player;
        int x0 = s.x, z0 = s.z;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        s.region = area;
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        BlockPos inside = pos(x0 + 23, FLOOR + 1, z0 + 23);
        Run first = s.run();
        first.load(alice);
        fill(first, alice, new Region.Cuboid(fill), "minecraft:water");
        UUID id = newest(first, alice);
        check(trails.markedBy(world, inside, id), "the Fill's water is marked");
        trails.capTrail(id, 1);
        wake(world, fill);
        Run[] second = new Run[1];
        context.createTimedTaskRunner()
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(!trails.markedBy(world, inside, id), "past the cap the entry's marks are gone");
                    for (NbtCompound chunk : saveChunks(world, area).values()) {
                        check(!chunk.contains(FluidTrails.NBT_KEY), "a chunk was saved with the forgotten marks");
                    }
                    second[0] = restart(s, first, alice, area);
                    check(!trails.markedBy(world, inside, id), "the marks stay gone after the restart");
                    second[0].step(alice, true);
                    for (int x = fill.min().x(); x <= fill.max().x(); x++) {
                        for (int z = fill.min().z(); z <= fill.max().z(); z++) {
                            check(world.getBlockState(pos(x, FLOOR + 1, z)).isAir(), "the Fill's cells are restored");
                        }
                    }
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0,
                            "water the entry no longer followed stays");
                    second[0].stop();
                    s.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Undo anyway and redo after a restart: an L-shaped water Fill on stone, a player's block in the water wakes it, the
     * notch of the L fills with sources, and the player builds where the water flowed. After a restart, undo keeps the
     * player's two blocks and takes back everything else; Undo anyway overwrites them (the area is exactly as before
     * the Fill); redo puts the water back, and undo again restores the area.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_anyway", tickLimit = LIMIT)
    public void undoAnywayAndRedoWorkAfterARestart(TestContext context) {
        Scene s = new Scene(context, 955);
        Harness h = s.h;
        ServerWorld world = h.world;
        ServerPlayerEntity alice = h.player;
        int x0 = s.x, z0 = s.z;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        s.region = area;
        CellSet.Builder cells = CellSet.builder();
        for (int x = x0 + 16; x <= x0 + 27; x++) {
            for (int z = z0 + 16; z <= z0 + 21; z++) cells.add(x, FLOOR + 1, z);
        }
        for (int x = x0 + 16; x <= x0 + 21; x++) {
            for (int z = z0 + 22; z <= z0 + 27; z++) cells.add(x, FLOOR + 1, z);
        }
        Region.Cells ell = new Region.Cells(cells.build());
        BlockPos corner = pos(x0 + 22, FLOOR + 1, z0 + 22);
        BlockPos outside = pos(x0 + 13, FLOOR + 1, z0 + 18);
        WorldSnapshot before = capture(world, area);
        Run first = s.run();
        first.load(alice);
        fill(first, alice, ell, "minecraft:water");
        // A player's block in the water, with vanilla's block updates.
        world.setBlockState(pos(x0 + 27, FLOOR + 1, z0 + 18), Blocks.STONE.getDefaultState());
        Run[] second = new Run[1];
        context.createTimedTaskRunner()
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(world.getFluidState(corner).isStill(), "the notch became a source");
                    check(!world.getFluidState(outside).isEmpty(), "the water reached " + outside.toShortString());
                    world.setBlockState(outside, Blocks.OAK_PLANKS.getDefaultState()); // built where the water flowed
                    second[0] = restart(s, first, alice, area);
                    JobResult undo = second[0].step(alice, true);
                    FluidUndoGameTest.Leftover left = leftover(world, before);
                    check(undo.skippedConflicts() == 2, "the player's two blocks were kept: " + undo);
                    check(left.cells() == 2 && left.sources() == 0 && left.flowing() == 0, "only they differ: " + left);
                    second[0].undoAnyway(alice);
                    checkExact(world, before, "restart_anyway: after Undo anyway");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "restart_anyway: " + WATER_TICKS + " ticks after Undo anyway");
                    check(second[0].step(alice, false).skippedConflicts() == 0, "the redo met conflicts");
                    check(world.getFluidState(corner).isStill(), "the redo put the notch's source back");
                    second[0].step(alice, true);
                    checkExact(world, before, "restart_anyway: undo, Undo anyway, redo and undo");
                    second[0].stop();
                    s.close();
                })
                .completeIfSuccessful();
    }

    /**
     * The player leaves (singleplayer: they quit, then the server stops): their history is unloaded, and what the water
     * did is folded into it first. After the restart undo leaves the area as before the Fill.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_leave", tickLimit = LIMIT)
    public void whatTheWaterDidIsSavedWhenThePlayerLeaves(TestContext context) {
        Scene s = new Scene(context, 957);
        Harness h = s.h;
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        ServerPlayerEntity alice = h.player;
        int x0 = s.x, z0 = s.z;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        s.region = area;
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        WorldSnapshot before = capture(world, area);
        Run first = s.run();
        first.load(alice);
        fill(first, alice, new Region.Cuboid(fill), "minecraft:water");
        UUID id = newest(first, alice);
        wake(world, fill);
        Run[] second = new Run[1];
        context.createTimedTaskRunner()
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(trails.trailCells(world, id) > 0, "the water left a trail");
                    first.service.playerLeft(alice.getUuid());
                    check(first.service.historyService().find(alice.getUuid()).isEmpty(), "the history was unloaded");
                    check(trails.trailCells(world, id) == 0, "the trail was folded into the entry first");
                    second[0] = restart(s, first, alice, area);
                    JobResult undo = second[0].step(alice, true);
                    check(undo.skippedConflicts() == 0, "nothing kept: " + undo);
                    checkExact(world, before, "restart_leave: undo after leaving and a restart");
                    second[0].stop();
                    s.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Ice the Fill's water froze into, across a restart: in a snowy biome the edge sources freeze; the server stops and
     * starts again (the marks stay on the ice: they are saved in the chunks by cell, whatever the cell holds). After the
     * restart a player's glowstone melts some of the ice and the meltwater runs out, followed by the entry loaded from
     * the chunk marks. Undo leaves the area exactly as before the Fill (the ice and the meltwater gone, the stone the
     * meltwater covered back), and stays so; redo puts back the water, the ice and the meltwater as the undo found them;
     * undo again restores the area.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_frozen", tickLimit = LIMIT)
    public void iceTheWaterFrozeIntoIsTakenBackAfterARestart(TestContext context) {
        Scene s = new Scene(context, 961);
        Harness h = s.h;
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        ServerPlayerEntity alice = h.player;
        int x0 = s.x, z0 = s.z;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        s.region = area;
        RegistryEntry<Biome> snowy = world.getRegistryManager().get(RegistryKeys.BIOME)
                .getEntry(BiomeKeys.SNOWY_PLAINS).orElseThrow();
        check(FillBiomeCommand.fillBiome(world, pos(x0, FLOOR - 3, z0), pos(x0 + SIDE - 1, FLOOR + 8, z0 + SIDE - 1),
                snowy).left().isPresent(), "the area became a snowy plain");
        world.setWeather(24_000, 0, false, false); // no snow falls on the stone meanwhile
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        BlockPos lit = pos(x0 + 20, FLOOR + 1, z0 + 23);
        BlockPos lamp = lit.up();
        WorldSnapshot before = capture(world, area);
        Run first = s.run();
        first.load(alice);
        fill(first, alice, new Region.Cuboid(fill), "minecraft:water");
        UUID id = newest(first, alice);
        WorldSnapshot[] found = new WorldSnapshot[1];
        Run[] second = new Run[1];
        context.createTimedTaskRunner()
                .createAndAddReported(() -> {
                    // The world's ice-and-snow tick of every column of the Fill: the edge sources freeze.
                    for (int x = fill.min().x(); x <= fill.max().x(); x++) {
                        for (int z = fill.min().z(); z <= fill.max().z(); z++) world.tickIceAndSnow(pos(x, FLOOR + 1, z));
                    }
                    int ice = count(world, area, Blocks.ICE);
                    check(ice >= 28, "at least the Fill's 28 edge sources froze: " + ice);
                    check(world.getBlockState(lit).isOf(Blocks.ICE), "the ice at " + lit.toShortString());
                    check(trails.markedBy(world, lit, id), "the ice is the entry's");
                    second[0] = restart(s, first, alice, area);
                    check(trails.markedBy(world, lit, id), "the mark on the ice came back with the chunk");
                    check(trails.trailCells(world, id) == 0, "nothing followed since the restart yet");
                    world.setBlockState(lamp, Blocks.GLOWSTONE.getDefaultState()); // a player's light over the ice
                })
                // Random ticks until the light has reached the ice and melted it.
                .createAndAdd(() -> {
                    world.tickChunk(world.getChunk(lit.getX() >> 4, lit.getZ() >> 4), 4096);
                    check(world.getFluidState(lit).isStill(), "waiting for the ice under the glowstone to melt");
                })
                .createAndAdd(settles(world, area, 100, "the meltwater"))
                .createAndAddReported(() -> {
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the meltwater ran out");
                    check(trails.trailCells(world, id) > 0, "the melting and the meltwater were followed after the restart");
                    world.setBlockState(lamp, Blocks.AIR.getDefaultState()); // the player takes the glowstone away
                })
                .expectMinDuration(100)
                .createAndAddReported(() -> {
                    found[0] = capture(world, area);
                    JobResult undo = second[0].step(alice, true);
                    check(undo.skippedConflicts() == 0, "nothing kept: " + undo);
                    checkExact(world, before, "restart_frozen: undo after the restart");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "restart_frozen: " + WATER_TICKS + " ticks after the undo");
                    check(second[0].step(alice, false).skippedConflicts() == 0, "the redo met conflicts");
                    checkSame(found[0], capture(world, area), "the redo puts back the water, ice and meltwater as found");
                    second[0].step(alice, true);
                    checkExact(world, before, "restart_frozen: undo, redo and undo after the restart");
                    second[0].stop();
                    s.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A chunk saved while the player's undo is in flight (the review's finding): the save hook cannot fold the water's
     * part then (the step replaces the entry itself), so the chunk on disk would hold what the water did while the
     * journal lacks it, and a chunk that changes no more would never be saved again. {@code FluidTrails.chunkSaved}
     * flags the chunk to be saved again; the next save, once the undo is done, folds the part. A crash right after that
     * save leaves a journal that holds the flow: the next run undoes the Fill exactly.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_busy_save", tickLimit = LIMIT)
    public void aChunkSavedWhileThePlayerIsBusyIsFlaggedAndTheNextSaveFoldsIt(TestContext context) {
        Scene s = new Scene(context, 962);
        Harness h = s.h;
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        ServerPlayerEntity alice = h.player;
        int x0 = s.x, z0 = s.z;
        Box area = terrain(h, x0, z0, "minecraft:stone");
        s.region = area;
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        // A block of stone elsewhere in the area, 1,024 cells: at 32 cells a tick its undo is in flight for 32 ticks.
        Box block = box(x0 + 2, FLOOR + 1, z0 + 2, x0 + 17, FLOOR + 4, z0 + 17);
        WorldSnapshot before = capture(world, area);
        Run first = s.run(s.dir, 32, StorageIo.SYSTEM);
        first.load(alice);
        fill(first, alice, new Region.Cuboid(fill), "minecraft:water");
        UUID id = newest(first, alice);
        wake(world, fill);
        WorldChunk chunk = world.getChunk((x0 + 20) >> 4, (z0 + 20) >> 4);
        RecordingListener undoBlock = new RecordingListener();
        WorldSnapshot[] atSave = new WorldSnapshot[1];
        Map<Long, NbtCompound> saved = new LinkedHashMap<>();
        Path[] copy = new Path[1];
        Run[] second = new Run[1];
        context.createTimedTaskRunner()
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(fluidOutside(world, area, new Region.Cuboid(fill)) > 0, "the water flowed out of the Fill");
                    check(trails.trailCells(world, id) > 0, "the flow is in the trail");
                    fill(first, alice, new Region.Cuboid(block), "minecraft:stone");
                    try {
                        first.service.undo(alice, ConflictPolicy.SKIP_CONFLICTS, undoBlock);
                    } catch (EditRejected e) {
                        throw new GameTestException("the undo of the block was refused: " + e.getMessage());
                    }
                    check(first.service.historyService().session(alice.getUuid()).busy(), "the undo is in flight");
                    // The game saves the chunks meanwhile: the hook cannot fold the water's part, the serializer writes
                    // the flowed water, and the chunk is flagged for another save.
                    chunk.setNeedsSaving(false);
                    chunkSaves(first, world, area);
                    saveChunks(world, area);
                    check(trails.trailCells(world, id) > 0, "the part stayed in memory while the player was busy");
                    check(chunk.needsSaving(), "the chunk is flagged to be saved again");
                    MultiplayerGameTest.tickUntil(first.executor, () -> undoBlock.result != null, 2000, "the undo");
                    first.service.tick();
                    check(undoBlock.result.outcome() == JobOutcome.COMPLETED, "the undo of the block " + undoBlock.result);
                    // The next save folds the part and journals it; the chunk needs no further save for it.
                    chunk.setNeedsSaving(false);
                    chunkSaves(first, world, area);
                    check(trails.trailCells(world, id) == 0, "the next save folded the part");
                    check(first.service.historyService().store().orElseThrow().flush(10_000), "the journal was written");
                    copy[0] = s.diskNow(s.dir);
                    atSave[0] = capture(world, area);
                    saved.putAll(saveChunks(world, area));
                    check(!chunk.needsSaving(), "nothing left to fold: the chunk is not flagged");
                    // The crash: the next run sees the disk as the second save left it.
                    first.release();
                    restore(h, atSave[0]);
                    restartTrails(world, trails, saved);
                    second[0] = s.run(copy[0], 0, StorageIo.SYSTEM);
                    second[0].load(alice);
                    HistoryEntry entry = newestEntry(second[0], alice);
                    check(entry.id().equals(id) && entry.record().before().cellCount() > fill.volume(),
                            "the journal copy holds what the water did: " + entry.record().before().cellCount());
                    JobResult undo = second[0].step(alice, true);
                    check(undo.skippedConflicts() == 0, "nothing kept: " + undo);
                    checkExact(world, before, "restart_busy_save: undo after the crash");
                })
                .expectMinDuration(WATER_TICKS)
                .createAndAddReported(() -> {
                    checkExact(world, before, "restart_busy_save: " + WATER_TICKS + " ticks after the undo");
                    second[0].stop();
                    s.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Corrupt or foreign chunk marks: saved marks of another version, an element without an entry, with a bit set of
     * the wrong length or with a section outside the build height mark nothing and throw nothing (each kind logged
     * once); marks of an entry no history holds load, and the next sweep drops them and flags the chunk to be saved
     * again without them.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_bad_marks", tickLimit = LIMIT)
    public void corruptChunkMarksAreSkippedAndForeignOnesSwept(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        int[] at = regionCorner(context, 963);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        BlockPos cell = pos(x0 + 20, FLOOR + 1, z0 + 20);
        ChunkPos column = new ChunkPos(cell);
        WorldChunk chunk = world.getChunk(column.x, column.z);
        int sy = cell.getY() >> 4;
        UUID foreign = UUID.randomUUID();
        long[] bits = new long[SectionBuffer.SIZE / 64];
        int i = SectionBuffer.index(cell.getX() & 15, cell.getY() & 15, cell.getZ() & 15);
        bits[i >> 6] |= 1L << (i & 63);
        NbtCompound otherVersion = marks(2, element(foreign, sy, bits));
        NbtCompound noEntry = marks(1, element(null, sy, bits));
        NbtCompound shortBits = marks(1, element(foreign, sy, new long[3]));
        NbtCompound badSection = marks(1, element(foreign, world.getTopSectionCoord() + 4, bits));
        NbtCompound valid = marks(1, element(foreign, sy, bits));
        for (NbtCompound bad : List.of(otherVersion, noEntry, shortBits, badSection)) {
            trails.restart();
            FluidTrails.chunkLoaded(world, column, bad);
            check(!trails.markedBy(world, cell, foreign), "nothing marked from " + bad);
        }
        check(trails.chunkDataProblems().equals(java.util.Set.of("version", "malformed")),
                "each kind logged once: " + trails.chunkDataProblems());
        trails.restart();
        FluidTrails.chunkLoaded(world, column, valid);
        check(trails.markedBy(world, cell, foreign), "well-formed marks of an unknown entry load");
        // Chunks saved before the rename hold their marks under the old key: read as a fallback, saved under the new.
        trails.restart();
        FluidTrails.chunkLoaded(world, column, legacy(valid));
        check(trails.markedBy(world, cell, foreign), "marks under the old Builder Suite key load");
        NbtCompound saved = ChunkSerializer.serialize(world, chunk);
        check(saved.contains(FluidTrails.NBT_KEY, net.minecraft.nbt.NbtElement.COMPOUND_TYPE)
                        && !saved.contains(FluidTrails.LEGACY_NBT_KEY),
                "the marks read under the old key are saved under the new key only");
        trails.restart();
        FluidTrails.chunkLoaded(world, column, saved);
        check(trails.markedBy(world, cell, foreign), "the marks saved under the new key load back");
        UUID newer = UUID.randomUUID();
        NbtCompound both = legacy(valid);
        both.put(FluidTrails.NBT_KEY, marks(1, element(newer, sy, bits)).getCompound(FluidTrails.NBT_KEY));
        trails.restart();
        FluidTrails.chunkLoaded(world, column, both);
        check(trails.markedBy(world, cell, newer) && !trails.markedBy(world, cell, foreign),
                "with both keys the new one is read");
        saved = ChunkSerializer.serialize(world, chunk);
        check(!saved.contains(FluidTrails.LEGACY_NBT_KEY), "a chunk is never saved under the old key");
        trails.restart();
        FluidTrails.chunkLoaded(world, column, saved);
        check(trails.markedBy(world, cell, newer) && !trails.markedBy(world, cell, foreign),
                "only the new key's marks are saved");
        trails.restart();
        FluidTrails.chunkLoaded(world, column, valid);
        chunk.setNeedsSaving(false);
        trails.register(java.util.Set::of);
        trails.sweep();
        check(!trails.markedBy(world, cell, foreign), "the sweep dropped the marks no history holds");
        check(chunk.needsSaving(), "the chunk is flagged to be saved again without them");
        forceChunks(world, area, false);
        h.close();
        context.complete();
    }

    /** Saved marks of {@code version} holding {@code elements}, as chunk data ({@link FluidTrails#NBT_KEY}). */
    private static NbtCompound marks(int version, NbtCompound... elements) {
        NbtList list = new NbtList();
        for (NbtCompound element : elements) list.add(element);
        NbtCompound marks = new NbtCompound();
        marks.putInt("version", version);
        marks.put("marks", list);
        NbtCompound chunkData = new NbtCompound();
        chunkData.put(FluidTrails.NBT_KEY, marks);
        return chunkData;
    }

    /** {@code chunkData}'s marks under the key chunks saved before the rename used. */
    private static NbtCompound legacy(NbtCompound chunkData) {
        NbtCompound old = new NbtCompound();
        old.put(FluidTrails.LEGACY_NBT_KEY, chunkData.getCompound(FluidTrails.NBT_KEY).copy());
        return old;
    }

    /** One saved element: the entry ({@code null} for none), a section and its bits. */
    private static NbtCompound element(UUID entry, int sy, long[] bits) {
        NbtCompound element = new NbtCompound();
        if (entry != null) element.putUuid("entry", entry);
        element.putInt("y", sy);
        element.putLongArray("bits", bits);
        return element;
    }

    // =================================================================== wiring

    /**
     * The chunk hooks for real: water a Fill wrote is marked; its chunks unload (the game saves them, with the marks in
     * their data); fluid trails forget everything, as a restart does; the chunks load again from disk, and the marks
     * are back before they tick. Woken, the water is followed, and undo leaves the area as before the Fill.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_fluid_restart_wiring", tickLimit = LIMIT)
    public void marksTravelWithRealChunkSavesAndLoads(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        FluidTrails trails = h.runtime.fluidTrails();
        int[] at = regionCorner(context, 956);
        int x0 = at[0], z0 = at[1];
        Box area = terrain(h, x0, z0, "minecraft:stone");
        Box fill = box(x0 + 20, FLOOR + 1, z0 + 20, x0 + 27, FLOOR + 1, z0 + 27);
        BlockPos inside = pos(x0 + 23, FLOOR + 1, z0 + 23);
        WorldSnapshot before = capture(world, area);
        RecordingListener filled = new RecordingListener(), undone = new RecordingListener();
        h.fill(fill, "minecraft:water", filled);
        UUID[] id = new UUID[1];
        context.createTimedTaskRunner()
                .createAndAdd(() -> check(filled.result != null, "fill running"))
                .createAndAddReported(() -> {
                    check(filled.result.outcome() == JobOutcome.COMPLETED, "fill " + filled.result);
                    id[0] = h.service.historyService().undoEntries(h.player.getUuid()).get(0).id();
                    check(trails.markedBy(world, inside, id[0]), "the Fill's water is marked");
                    forceChunks(world, area, false);
                })
                .createAndAdd(() -> {
                    ChunkUnloadAccessor chunks = (ChunkUnloadAccessor) world.getChunkManager().chunkLoadingManager;
                    for (int cx = area.min().x() >> 4; cx <= area.max().x() >> 4; cx++) {
                        for (int cz = area.min().z() >> 4; cz <= area.max().z() >> 4; cz++) {
                            long key = ChunkPos.toLong(cx, cz);
                            // Saved and gone: loading it again reads it from its saved data.
                            check(!WorldChecks.isChunkLoaded(world, cx, cz)
                                    && !chunks.sculptory$currentChunkHolders().containsKey(key)
                                    && !chunks.sculptory$chunksToUnload().containsKey(key),
                                    "waiting for chunk " + cx + "," + cz + " to unload");
                        }
                    }
                })
                .createAndAddReported(() -> {
                    trails.restart();
                    check(!trails.markedBy(world, inside, id[0]), "memory forgot the marks");
                    loadAndForce(world, area);
                    check(trails.markedBy(world, inside, id[0]), "the marks loaded with the chunk");
                    check(!trails.markedBy(world, pos(x0 + 23, FLOOR, z0 + 23), id[0]), "the stone is not marked");
                    wake(world, fill);
                })
                .createAndAdd(settles(world, area, 100, "the water"))
                .createAndAddReported(() -> {
                    check(trails.trailCells(world, id[0]) > 0, "the reloaded water was followed");
                    try {
                        h.service.undo(h.player, ConflictPolicy.SKIP_CONFLICTS, undone);
                    } catch (EditRejected e) {
                        throw new GameTestException("undo refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(undone.result != null, "undo running"))
                .createAndAddReported(() -> {
                    check(undone.result.outcome() == JobOutcome.COMPLETED, "undo " + undone.result);
                    checkExact(world, before, "restart_wiring: undo after the reload");
                    forceChunks(world, area, false);
                    h.close();
                })
                .completeIfSuccessful();
    }
}
