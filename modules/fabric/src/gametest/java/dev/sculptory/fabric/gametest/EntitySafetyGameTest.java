package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.capture;
import static dev.sculptory.fabric.gametest.EditTestSupport.checkSame;
import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.comparable;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.compound;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.copyInto;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.decorate;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.entitiesIn;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.load;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.once;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.paste;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.ready;
import static dev.sculptory.fabric.gametest.EntitiesGameTest.refusal;
import static dev.sculptory.fabric.gametest.MultiplayerGameTest.tickUntil;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.EditExecutor;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.schem.FabricDataFixHook;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricEntities;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.platform.WriteOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.vehicle.CommandBlockMinecartEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtList;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.text.Text;

/**
 * Entities under hostile input and failure: files may not bring more passengers
 * than allowed, entities that are never placed, or operator data; entity work is bounded per step; placements and
 * restores that fail stay exact under undo; a cancelled move, and a cancelled undo or redo of one, lose no entity; Undo
 * anyway over chained moves restores the entity; masks, turns and the entity data cap apply to entities. Work regions:
 * {@code regionCorner} slots 653-661, 665, 667.
 */
public final class EntitySafetyGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    private static final int TICKS = 2000;

    // ------------------------------------------------------------------------------------------------ files

    /**
     * A file (WorldEdit-style v3) with an armor stand carrying 20 armor stands, primed TNT, a boat carrying TNT and a
     * command block minecart (ids without a namespace, as the game still loads them), a wither, a fireball with the
     * largest explosion, and an armor stand claiming to hang on a block while standing outside the box. The import keeps
     * the stand with 16 riders and the boat with the minecart, without its command; everything else is left out and
     * counted. Pasted by an operator, nothing else appears.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_file_rules", tickLimit = LIMIT)
    public void filesCannotBringNeverKindsOrMorePassengers(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 653);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 32);
        loadAndForce(world, all);
        java.nio.file.Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        BlockPos target = new BlockPos(x0 + 4, y0, z0 + 4);
        Box pasted = box(target.x() - 4, y0 - 1, target.z() - 4, target.x() + 10, y0 + 6, target.z() + 10);
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> ClipTestSupport.upload(clips, h.player, "hostile.schem", hostileFile(),
                        uploaded)))
                .createAndAdd(() -> check(uploaded.finished(), "upload running"))
                .createAndAdd(once(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("the upload");
                    check(info.entities() == 19, "the stand with 16 riders and the boat with its minecart: "
                            + info.entities());
                    List<String> notices = info.notices().stream().map(n -> n.key() + n.args()).toList();
                    check(notices.contains("sculptory.notice.import_entities_skipped[9]"), "skipped: " + notices);
                    check(notices.contains(ServerClipboards.NOTICE_IMPORT_ENTITY_DATA + "[1]"), "stripped: " + notices);
                    paste(h, info.clipboardId(), target, Transform.IDENTITY, paste);
                }))
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    List<Entity> placed = new ArrayList<>();
                    world.collectEntitiesByType(net.minecraft.util.TypeFilter.instanceOf(Entity.class),
                            new net.minecraft.util.math.Box(pasted.min().x(), pasted.min().y(), pasted.min().z(),
                                    pasted.max().x() + 1, pasted.max().y() + 1, pasted.max().z() + 1),
                            e -> !e.isPlayer() && !e.isRemoved(), placed);
                    long stands = placed.stream().filter(e -> e instanceof ArmorStandEntity).count();
                    check(stands == 1 + EntityNbt.MAX_RIDERS, "armor stands placed: " + stands);
                    List<Entity> carts = placed.stream().filter(e -> e instanceof CommandBlockMinecartEntity).toList();
                    check(carts.size() == 1 && carts.get(0).getVehicle() != null, "the minecart rides the boat: " + carts);
                    check(((CommandBlockMinecartEntity) carts.get(0)).getCommandExecutor().getCommand().isEmpty(),
                            "the file's command survived, for an operator");
                    check(placed.stream().noneMatch(e -> FabricEntities.kind(e) == FabricEntities.Kind.NEVER
                            || e.getType() == EntityType.WITHER || e.getType() == EntityType.TNT
                            || e.getType() == EntityType.FIREBALL), "something never placed was: " + placed);
                    check(placed.size() == 1 + EntityNbt.MAX_RIDERS + 2, "entities placed: " + placed.size());
                    placed.forEach(Entity::discard);
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** The hostile file of {@link #filesCannotBringNeverKindsOrMorePassengers}: 6 × 3 × 6, a stone floor. */
    static byte[] hostileFile() {
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:stone", 1);
        byte[] data = new byte[6 * 3 * 6];
        for (int i = 0; i < 36; i++) data[i] = 1; // y = 0
        NbtCompound blocks = new NbtCompound();
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", data);
        blocks.put("BlockEntities", new NbtList());

        NbtList riders = new NbtList();
        for (int i = 0; i < 20; i++) riders.add(standData());
        NbtCompound loaded = standData();
        loaded.put("Passengers", riders);
        NbtCompound tntRider = new NbtCompound();
        tntRider.putString("id", "tnt");
        NbtCompound cartRider = new NbtCompound();
        cartRider.putString("id", "command_block_minecart");
        cartRider.putString("Command", "op @a");
        NbtList boatRiders = new NbtList();
        boatRiders.add(tntRider);
        boatRiders.add(cartRider);
        NbtCompound boat = new NbtCompound();
        boat.putBoolean("NoGravity", true);
        boat.put("Passengers", boatRiders);
        NbtCompound fireball = new NbtCompound();
        fireball.putByte("ExplosionPower", (byte) 127);
        NbtCompound outside = standData();
        outside.putInt("TileX", 0);
        outside.putInt("TileY", 1);
        outside.putInt("TileZ", 0);

        NbtList entities = new NbtList();
        entities.add(entry("minecraft:armor_stand", 1.5, 1, 1.5, loaded));
        entities.add(entry("minecraft:tnt", 2.5, 1, 2.5, new NbtCompound()));
        entities.add(entry("minecraft:boat", 3.5, 1, 3.5, boat));
        entities.add(entry("minecraft:wither", 4.5, 1, 4.5, new NbtCompound()));
        entities.add(entry("minecraft:fireball", 1.5, 2, 4.5, fireball));
        entities.add(entry("minecraft:armor_stand", -2.5, 1, 0.5, outside));

        NbtCompound schematic = new NbtCompound();
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", FabricDataFixHook.currentDataVersion());
        schematic.putShort("Width", (short) 6);
        schematic.putShort("Height", (short) 3);
        schematic.putShort("Length", (short) 6);
        schematic.putIntArray("Offset", new int[] {0, 0, 0});
        schematic.put("Blocks", blocks);
        schematic.put("Entities", entities);
        NbtCompound file = new NbtCompound();
        file.put("Schematic", schematic);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            net.minecraft.nbt.NbtIo.writeCompressed(file, out);
        } catch (IOException e) {
            throw new GameTestException("cannot write the file: " + e);
        }
        return out.toByteArray();
    }

    private static NbtCompound standData() {
        NbtCompound stand = new NbtCompound();
        stand.putString("id", "minecraft:armor_stand");
        stand.putBoolean("NoGravity", true);
        return stand;
    }

    private static NbtCompound entry(String id, double x, double y, double z, NbtCompound data) {
        NbtCompound entry = new NbtCompound();
        entry.putString("Id", id);
        NbtList pos = new NbtList();
        pos.add(NbtDouble.of(x));
        pos.add(NbtDouble.of(y));
        pos.add(NbtDouble.of(z));
        entry.put("Pos", pos);
        entry.put("Data", data);
        return entry;
    }

    // ------------------------------------------------------------------------------------------------ failures

    /**
     * A move is cancelled after it took its entities and before it placed them: they are gone from both places (the
     * record holds them), and undo puts them back exactly, blocks and entities alike.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_cancelled_move", tickLimit = LIMIT)
    public void aMoveCancelledAfterTakingItsEntitiesIsUndoneExactly(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 64);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 654);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box destination = source.offset(20, 0, 0);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 40, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    try {
                        Map<UUID, dev.sculptory.core.nbt.NbtCompound> before = comparable(decorate(h, x0, y0, z0));
                        WorldSnapshot blocks = capture(world, box(x0, y0, z0, x0 + 23, y0 + 2, z0 + 3));
                        RecordingListener move = new RecordingListener();
                        JobTicket ticket = h.service.run(h.player, new OpSpec.Move(new Region.Cuboid(source),
                                new BlockPos(20, 0, 0), Transform.IDENTITY,
                                new Pattern.Single(h.state("minecraft:air")), EntityFilter.DECORATIONS),
                                RunOptions.DEFAULT, move);
                        tickUntil(executor, () -> entitiesIn(world, source).isEmpty(), TICKS, "taking the entities");
                        check(move.result == null, "the move is still writing blocks");
                        check(h.service.cancel(h.player, ticket.jobId()), "the move could not be cancelled");
                        tickUntil(executor, () -> move.result != null, TICKS, "the cancel");
                        check(move.result.outcome() == JobOutcome.CANCELLED, "move " + move.result);
                        check(entitiesIn(world, source).isEmpty() && entitiesIn(world, destination).isEmpty(),
                                "taken and not placed");
                        RecordingListener undo = new RecordingListener();
                        h.undo(undo);
                        tickUntil(executor, () -> undo.result != null, TICKS, "the undo");
                        check(undo.result.skippedConflicts() == 0, "undo " + undo.result);
                        check(comparable(entitiesIn(world, source)).equals(before), "the same entities are back");
                        check(entitiesIn(world, destination).isEmpty(), "nothing at the destination");
                        checkSame(blocks, capture(world, box(x0, y0, z0, x0 + 23, y0 + 2, z0 + 3)), "the blocks");
                    } catch (EditRejected e) {
                        throw new GameTestException("refused: " + e.getMessage());
                    } finally {
                        executor.shutdown();
                        forceChunks(world, all, false);
                        h.close();
                    }
                }))
                .completeIfSuccessful();
    }

    /**
     * Placement failures are counted and change nothing undo does not know about: a clipboard entity of an unknown type
     * is left out while the armor stand beside it is placed and undone; a cut armor stand whose UUID another entity took
     * meanwhile is not put back (counted as kept), while the cut blocks are.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_failures", tickLimit = LIMIT)
    public void placementFailuresAreCountedAndUndoStaysExact(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 655);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box pasteArea = box(x0, y0, z0, x0 + 2, y0 + 2, z0 + 2);
        Box cutArea = box(x0 + 8, y0, z0, x0 + 10, y0 + 2, z0 + 2);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        RecordingListener paste = new RecordingListener();
        RecordingListener undoPaste = new RecordingListener();
        Captured<ClipboardService.ClipboardInfo> cut = new Captured<>();
        RecordingListener erase = new RecordingListener();
        RecordingListener undoCut = new RecordingListener();
        UUID[] cutStand = {null};
        Entity[] squatter = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    dev.sculptory.core.nbt.NbtCompound still = dev.sculptory.core.nbt.NbtCompound.builder()
                            .putByte("NoGravity", (byte) 1).build();
                    byte[] data = dev.sculptory.core.nbt.NbtIo.toBytes(still);
                    Clipboard clipboard = Clipboard.builder(h.runtime.states(), new BlockPos(3, 3, 3)).build()
                            .withEntities(List.of(
                                    new EntitySnapshot("minecraft:armor_stand", 1.5, 0, 1.5, 0f, 0f, null, data, true),
                                    new EntitySnapshot("minecraft:not_a_thing", 0.5, 0, 0.5, 0f, 0f, null, data, true)));
                    UUID id = h.service.clipboards().install(h.player.getUuid(), clipboard).id();
                    paste(h, id, pasteArea.min(), Transform.IDENTITY, paste);
                }))
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(once(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.changed() == 1,
                            "only the armor stand was placed: " + paste.result);
                    check(entitiesIn(world, pasteArea).size() == 1, "placed " + entitiesIn(world, pasteArea));
                    h.undo(undoPaste);
                }))
                .createAndAdd(() -> check(undoPaste.result != null, "undo running"))
                .createAndAdd(once(() -> {
                    check(undoPaste.result.skippedConflicts() == 0, "undo " + undoPaste.result);
                    check(entitiesIn(world, pasteArea).isEmpty(), "the undo left " + entitiesIn(world, pasteArea));
                    BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
                    for (int x = x0 + 8; x <= x0 + 10; x++) {
                        for (int z = z0; z <= z0 + 2; z++) writer.write(x, y0, z, h.state("minecraft:stone"), null);
                    }
                    Entity stand = load(world, "minecraft:armor_stand", x0 + 9.5, y0 + 1, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    check(world.spawnEntity(stand), "the stand spawned");
                    cutStand[0] = stand.getUuid();
                    try {
                        clips.copy(h.player, new Region.Cuboid(cutArea), cutArea.min(), true, CellMask.ANY,
                                EntityFilter.DECORATIONS, erase, cut);
                    } catch (EditRejected e) {
                        throw new GameTestException("cut refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(erase.result != null && cut.finished(), "cut running"))
                .createAndAdd(once(() -> {
                    check(cut.get("cut").entities() == 1, "cut " + cut.value);
                    check(FabricEntities.find(world, cutStand[0]) == null, "the stand was cut");
                    // Another entity takes the stand's UUID meanwhile.
                    int[] uuid = net.minecraft.util.Uuids.toIntArray(cutStand[0]);
                    squatter[0] = load(world, "minecraft:pig", x0 + 20.5, y0 + 1, z0 + 1.5, 0f, nbt -> {
                        nbt.putIntArray("UUID", uuid);
                        nbt.putBoolean("NoAI", true);
                    });
                    check(world.spawnEntity(squatter[0]), "the pig spawned");
                    h.undo(undoCut);
                }))
                .createAndAdd(() -> check(undoCut.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undoCut.result.skippedConflicts() == 1, "the stand is kept out: " + undoCut.result);
                    check(FabricEntities.find(world, cutStand[0]) == squatter[0], "the pig keeps its UUID");
                    check(entitiesIn(world, cutArea).isEmpty(), "no second entity with that UUID");
                    check(world.getBlockState(EngineTestSupport.pos(x0 + 9, y0, z0 + 1)).isOf(
                            net.minecraft.block.Blocks.STONE), "the floor is back");
                    squatter[0].discard();
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Entity work comes in bounded steps: 30 armor stands stacked 12 times (360 placements) with a block cap that lets
     * one step run per tick never place more than {@value dev.sculptory.fabric.engine.impl.EntityWork#ENTITIES_PER_STEP}
     * in one tick, and all 360 are placed.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_steps", tickLimit = LIMIT)
    public void entityWorkIsBoundedPerStep(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 1000);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 656);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 4, y0 + 1, z0 + 5);
        Box copies = box(x0 + 5, y0, z0, x0 + 64, y0 + 1, z0 + 5);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 80, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    try {
                        for (int i = 0; i < 30; i++) {
                            Entity stand = load(world, "minecraft:armor_stand", x0 + (i % 5) + 0.5, y0,
                                    z0 + (i / 5) + 0.5, 0f, nbt -> nbt.putBoolean("NoGravity", true));
                            check(world.spawnEntity(stand), "stand " + i);
                        }
                        RecordingListener stack = new RecordingListener();
                        h.service.run(h.player, new OpSpec.Stack(new Region.Cuboid(source), 5, 0, 0, 12,
                                EntityFilter.DECORATIONS), RunOptions.DEFAULT, stack);
                        int ticks = 0, last = 0, most = 0;
                        while (stack.result == null && ticks++ < TICKS) {
                            executor.tick();
                            int now = entitiesIn(world, copies).size();
                            most = Math.max(most, now - last);
                            last = now;
                        }
                        check(stack.result != null && stack.result.outcome() == JobOutcome.COMPLETED,
                                "stack " + stack.result);
                        check(last == 360, "stacked " + last);
                        check(most > 0 && most <= dev.sculptory.fabric.engine.impl.EntityWork.ENTITIES_PER_STEP,
                                "placed " + most + " in one tick");
                        entitiesIn(world, all).forEach(Entity::discard);
                    } catch (EditRejected e) {
                        throw new GameTestException("refused: " + e.getMessage());
                    } finally {
                        executor.shutdown();
                        forceChunks(world, all, false);
                        h.close();
                    }
                }))
                .completeIfSuccessful();
    }

    /**
     * An undo or redo of a move cut off between its entity stages loses and doubles nothing. The undo is cancelled after
     * it took the moved armor stand away and before it put the original back: undo again, and the original is back,
     * once. The redo is cancelled after it took the original away and before it placed the moved one: the redo counts as
     * done (it changed something), so undo brings the original back, once.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_cancelled_history", tickLimit = LIMIT)
    public void cancelledUndoAndRedoOfAMoveKeepTheEntityOnce(TestContext context) {
        EditExecutor executor = MultiplayerGameTest.executor(context, 64);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 657);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box destination = source.offset(20, 0, 0);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 40, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    try {
                        Entity original = load(world, "minecraft:armor_stand", x0 + 1.5, y0, z0 + 1.5, 0f,
                                nbt -> nbt.putBoolean("NoGravity", true));
                        check(world.spawnEntity(original), "the stand spawned");
                        UUID id = original.getUuid();
                        RecordingListener move = new RecordingListener();
                        h.service.run(h.player, new OpSpec.Move(new Region.Cuboid(source), new BlockPos(20, 0, 0),
                                Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air")),
                                EntityFilter.DECORATIONS), RunOptions.DEFAULT, move);
                        tickUntil(executor, () -> move.result != null, TICKS, "the move");
                        check(entitiesIn(world, destination).size() == 1, "moved");

                        // The undo, cut off between taking the moved stand and putting the original back.
                        RecordingListener undo = new RecordingListener();
                        JobTicket undoing = h.undo(undo);
                        tickUntil(executor, () -> entitiesIn(world, destination).isEmpty(), TICKS, "the undo taking it");
                        check(undo.result == null, "the undo is still putting blocks back");
                        check(h.service.cancel(h.player, undoing.jobId()), "the undo could not be cancelled");
                        tickUntil(executor, () -> undo.result != null, TICKS, "the cancelled undo");
                        check(undo.result.outcome() == JobOutcome.CANCELLED, "undo " + undo.result);
                        check(entitiesIn(world, source).isEmpty(), "put back before the blocks");
                        RecordingListener again = new RecordingListener();
                        h.undo(again);
                        tickUntil(executor, () -> again.result != null, TICKS, "the undo again");
                        check(ids(entitiesIn(world, all)).equals(java.util.Set.of(id)), "the original is back, once: "
                                + entitiesIn(world, all));

                        // The redo, cut off between taking the original and placing the moved one.
                        RecordingListener redo = new RecordingListener();
                        JobTicket redoing = h.redo(redo);
                        tickUntil(executor, () -> entitiesIn(world, source).isEmpty(), TICKS, "the redo taking it");
                        check(redo.result == null, "the redo is still moving blocks");
                        check(h.service.cancel(h.player, redoing.jobId()), "the redo could not be cancelled");
                        tickUntil(executor, () -> redo.result != null, TICKS, "the cancelled redo");
                        check(entitiesIn(world, all).isEmpty(), "taken, not placed yet: " + entitiesIn(world, all));
                        check(!h.history().undoLabel().isEmpty(), "a redo that changed something can be undone");
                        RecordingListener back = new RecordingListener();
                        h.undo(back);
                        tickUntil(executor, () -> back.result != null, TICKS, "the undo of the cut-off redo");
                        check(ids(entitiesIn(world, all)).equals(java.util.Set.of(id)), "the original is back, once: "
                                + entitiesIn(world, all));
                        entitiesIn(world, all).forEach(Entity::discard);
                    } catch (EditRejected e) {
                        throw new GameTestException("refused: " + e.getMessage());
                    } finally {
                        executor.shutdown();
                        forceChunks(world, all, false);
                        h.close();
                    }
                }))
                .completeIfSuccessful();
    }

    /**
     * Undo anyway over two chained moves (s1 to p1, then p1 to p2) restores the entity: the moved one is renamed, so the
     * first undo keeps it (and puts p1 back beside it, the duplicate a changed entity can leave), the second puts the
     * original back; Undo anyway over both takes the renamed one away and leaves exactly the original.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_chained_moves", tickLimit = LIMIT)
    public void undoAnywayOverTwoChainedMovesRestoresTheEntity(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 665);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 1, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 40, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        RecordingListener first = new RecordingListener();
        RecordingListener second = new RecordingListener();
        RecordingListener undo2 = new RecordingListener();
        RecordingListener undo1 = new RecordingListener();
        RecordingListener anyway = new RecordingListener();
        UUID[] original = {null};
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    Entity stand = load(world, "minecraft:armor_stand", x0 + 1.5, y0, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    check(world.spawnEntity(stand), "the stand spawned");
                    original[0] = stand.getUuid();
                    move(h, source, first);
                }))
                .createAndAdd(() -> check(first.result != null, "first move running"))
                .createAndAdd(once(() -> move(h, source.offset(8, 0, 0), second)))
                .createAndAdd(() -> check(second.result != null, "second move running"))
                .createAndAdd(once(() -> {
                    List<Entity> moved = entitiesIn(world, source.offset(16, 0, 0));
                    check(moved.size() == 1, "moved twice: " + entitiesIn(world, all));
                    moved.get(0).setCustomName(net.minecraft.text.Text.literal("renamed"));
                    h.undo(undo2);
                }))
                .createAndAdd(() -> check(undo2.result != null, "first undo running"))
                .createAndAdd(once(() -> {
                    check(undo2.result.skippedConflicts() == 1, "the renamed one is kept: " + undo2.result);
                    h.undo(undo1);
                }))
                .createAndAdd(() -> check(undo1.result != null, "second undo running"))
                .createAndAdd(once(() -> {
                    check(entitiesIn(world, source).size() == 1, "the original is back");
                    try {
                        h.service.historyOverwrite(h.player, false, 2, anyway);
                    } catch (EditRejected e) {
                        throw new GameTestException("Undo anyway refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(anyway.result != null, "Undo anyway running"))
                .createAndAdd(() -> {
                    check(ids(entitiesIn(world, all)).equals(java.util.Set.of(original[0])), "exactly the original is left: "
                            + entitiesIn(world, all));
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    private static void move(Harness h, Box box, RecordingListener listener) {
        try {
            h.service.run(h.player, new OpSpec.Move(new Region.Cuboid(box), new BlockPos(8, 0, 0), Transform.IDENTITY,
                    new Pattern.Single(h.state("minecraft:air")), EntityFilter.DECORATIONS), RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException("move refused: " + e.getMessage());
        }
    }

    private static java.util.Set<UUID> ids(List<Entity> entities) {
        return entities.stream().map(Entity::getUuid).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Nothing is taken that could not be put back. An armor stand holds an item with 230,000 tags of custom data: small
     * enough for the history's own decoder (1.8 MB), too large for the game's loader (which charges some 40 bytes per
     * tag). It is never recorded, never copied and never moved: a move takes the plain stand beside it and leaves this
     * one, and undo brings the plain one back. Everything recorded loads back with the loader that restores it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_restorable", tickLimit = LIMIT)
    public void entitiesThatCouldNotBePutBackAreNeverTaken(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 667);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 1, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        RecordingListener move = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        Entity[] fixture = new Entity[2];
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    fixture[0] = load(world, "minecraft:armor_stand", x0 + 1.5, y0, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    ArmorStandEntity heavy = (ArmorStandEntity) load(world, "minecraft:armor_stand", x0 + 2.5, y0,
                            z0 + 2.5, 0f, nbt -> nbt.putBoolean("NoGravity", true));
                    NbtCompound data = new NbtCompound();
                    for (int i = 0; i < 230_000; i++) data.putByte(Integer.toString(i, 36), (byte) 1);
                    net.minecraft.item.ItemStack stick = new net.minecraft.item.ItemStack(net.minecraft.item.Items.STICK);
                    stick.set(net.minecraft.component.DataComponentTypes.CUSTOM_DATA,
                            net.minecraft.component.type.NbtComponent.of(data));
                    heavy.equipStack(net.minecraft.entity.EquipmentSlot.MAINHAND, stick);
                    fixture[1] = heavy;
                    check(world.spawnEntity(fixture[0]) && world.spawnEntity(heavy), "spawned");

                    // The history's decoder takes it; the game's loader does not: so it is never recorded.
                    NbtCompound whole = FabricEntities.save(heavy, EntityFilter.ALL);
                    byte[] bytes = FabricEntities.bytes(whole);
                    try {
                        FabricEntities.fromBytes(bytes);
                    } catch (IOException e) {
                        throw new GameTestException("the fixture is too large even for the history's decoder: " + e);
                    }
                    check(!FabricEntities.restorable(bytes), "the game's loader takes the fixture after all");
                    check(FabricEntities.state(heavy, EntityFilter.ALL) == null, "an entity that cannot be put back "
                            + "was recorded");
                    dev.sculptory.core.history.EntityState plain = FabricEntities.state(fixture[0], EntityFilter.ALL);
                    check(plain != null && FabricEntities.restorable(plain.nbt()), "the plain stand is recorded");
                    check(refusal(() -> clips.copy(h.player, new Region.Cuboid(source), source.min(), false,
                            CellMask.ANY, EntityFilter.DECORATIONS, null, new Captured<>())) == RejectReason.TOO_LARGE,
                            "a copy took what it could not paste");
                    try {
                        h.service.run(h.player, new OpSpec.Move(new Region.Cuboid(source), new BlockPos(16, 0, 0),
                                Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air")),
                                EntityFilter.DECORATIONS), RunOptions.DEFAULT, move);
                    } catch (EditRejected e) {
                        throw new GameTestException("move refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(once(() -> {
                    check(!fixture[1].isRemoved(), "the heavy stand was taken");
                    check(fixture[0].isRemoved() && entitiesIn(world, source.offset(16, 0, 0)).size() == 1,
                            "the plain stand moved");
                    h.undo(undo);
                }))
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    check(FabricEntities.find(world, fixture[0].getUuid()) != null, "the plain stand is back");
                    check(entitiesIn(world, source).size() == 2, "both stands where they were: "
                            + entitiesIn(world, source));
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    // ------------------------------------------------------------------------------------------------ rules

    /**
     * A masked copy takes only the entities whose block the mask takes: with "only air", the frame, the painting (in the
     * air in front of their wall) and the display, not the armor stand, whose block is now glass.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_mask", tickLimit = LIMIT)
    public void maskedCopiesTakeTheEntitiesOfMaskedBlocks(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 658);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 16, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> airOnly = new Captured<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    decorate(h, x0, y0, z0);
                    h.runtime.writer(world, WriteOptions.DEFAULT).write(x0 + 1, y0 + 1, z0 + 3,
                            h.state("minecraft:glass"), null); // the armor stand's block
                    try {
                        clips.copy(h.player, new Region.Cuboid(source), source.min(), false,
                                new CellMask.States(new int[] {h.state("minecraft:air")}), EntityFilter.DECORATIONS,
                                null, airOnly);
                    } catch (EditRejected e) {
                        throw new GameTestException("copy refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> {
                    int taken = airOnly.get("the masked copy").entities();
                    check(taken == 3, "the frame, the painting and the display, not the stand: " + taken);
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Passengers turn with their vehicle: an armor stand (yaw 30) riding a minecart, pasted a quarter turn, faces 120. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_riders_turn", tickLimit = LIMIT)
    public void passengersTurnWithTheirVehicle(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 659);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 2, y0 + 2, z0 + 2);
        BlockPos origin = new BlockPos(x0 + 10, y0, z0);
        Box target = box(x0 + 8, y0 - 1, z0 - 2, x0 + 14, y0 + 3, z0 + 4);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        RecordingListener paste = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    Entity cart = load(world, "minecraft:minecart", x0 + 1.5, y0, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    Entity stand = load(world, "minecraft:armor_stand", x0 + 1.5, y0, z0 + 1.5, 30f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    check(world.spawnEntity(cart) && world.spawnEntity(stand), "spawned");
                    check(stand.startRiding(cart, true), "the stand rides the minecart");
                    copyInto(copied, clips, h.player, source, source.min(), EntityFilter.DECORATIONS);
                }))
                .createAndAdd(() -> check(copied.finished(), "copy running"))
                .createAndAdd(once(() -> {
                    check(copied.get("copy").entities() == 2, "copied " + copied.value);
                    paste(h, copied.value.clipboardId(), origin, Transform.rotation(1), paste);
                }))
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    List<Entity> riders = entitiesIn(world, target).stream()
                            .flatMap(e -> e.getPassengerList().stream()).toList();
                    check(riders.size() == 1 && riders.get(0) instanceof ArmorStandEntity, "riders " + riders);
                    float yaw = dev.sculptory.core.entity.EntityPlacement.wrap(riders.get(0).getYaw() - 120f);
                    check(Math.abs(yaw) < 0.01f, "the rider faces " + riders.get(0).getYaw() + ", not 120");
                    entitiesIn(world, all).forEach(e -> {
                        e.getPassengerList().forEach(Entity::discard);
                        e.discard();
                    });
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A pasted chicken left alone (its egg timer runs) is still the chicken the paste placed: undo takes it back. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_chicken", tickLimit = LIMIT)
    public void aChickenLeftAloneUndoesCleanly(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 660);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 2, y0 + 1, z0 + 2);
        BlockPos origin = new BlockPos(x0 + 8, y0, z0);
        Box target = source.offset(8, 0, 0);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        RecordingListener paste = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
                    for (int x = x0 - 1; x <= x0 + 11; x++) {
                        for (int z = z0 - 1; z <= z0 + 3; z++) writer.write(x, y0 - 1, z, h.state("minecraft:stone"), null);
                    }
                    Entity chicken = load(world, "minecraft:chicken", x0 + 1.5, y0, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoAI", true));
                    check(world.spawnEntity(chicken), "the chicken spawned");
                    copyInto(copied, clips, h.player, source, source.min(), EntityFilter.ALL);
                }))
                .createAndAdd(() -> check(copied.finished(), "copy running"))
                .createAndAdd(once(() -> {
                    check(copied.get("copy").entities() == 1, "copied " + copied.value);
                    paste(h, copied.value.clipboardId(), origin, Transform.IDENTITY, paste);
                }))
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> check(++waited[0] >= 40, "letting the chicken's timers run"))
                .createAndAdd(once(() -> {
                    check(entitiesIn(world, target).size() == 1, "pasted " + entitiesIn(world, target));
                    h.undo(undo);
                }))
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.skippedConflicts() == 0, "the chicken counted as changed: " + undo.result);
                    check(entitiesIn(world, target).isEmpty(), "the chicken stayed");
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A copy's entity data is capped for everyone (it is snapshotted in one tick): 22 armor stands with 30 tags of 55,000
     * letters each (36 MB; NBT strings hold at most 64 KB) are refused {@code TOO_LARGE}; the same copy without entities goes ahead.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_bytes", tickLimit = LIMIT)
    public void copiesRefuseTooMuchEntityData(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 661);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 10, y0 + 1, z0 + 1);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> blocksOnly = new Captured<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    String tag = "x".repeat(55_000);
                    for (int i = 0; i < 22; i++) {
                        Entity stand = load(world, "minecraft:armor_stand", x0 + (i % 11) + 0.5, y0, z0 + (i / 11) + 0.5,
                                0f, nbt -> nbt.putBoolean("NoGravity", true));
                        for (int t = 0; t < 30; t++) stand.addCommandTag(tag + t);
                        check(world.spawnEntity(stand), "stand " + i);
                    }
                    RejectReason reason = refusal(() -> clips.copy(h.player, new Region.Cuboid(source), source.min(),
                            false, CellMask.ANY, EntityFilter.DECORATIONS, null, new Captured<>()));
                    check(reason == RejectReason.TOO_LARGE, "refused " + reason);
                    copyInto(blocksOnly, clips, h.player, source, source.min(), EntityFilter.NONE);
                }))
                .createAndAdd(() -> {
                    check(blocksOnly.get("the copy without entities").entities() == 0, "no entities");
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }
}
