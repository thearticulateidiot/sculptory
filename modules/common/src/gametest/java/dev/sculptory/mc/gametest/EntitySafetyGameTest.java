package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EditTestSupport.capture;
import static dev.sculptory.mc.gametest.EditTestSupport.checkSame;
import static dev.sculptory.mc.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.mc.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.mc.gametest.EntitiesGameTest.comparable;
import static dev.sculptory.mc.gametest.EntitiesGameTest.compound;
import static dev.sculptory.mc.gametest.EntitiesGameTest.copyInto;
import static dev.sculptory.mc.gametest.EntitiesGameTest.decorate;
import static dev.sculptory.mc.gametest.EntitiesGameTest.entitiesIn;
import static dev.sculptory.mc.gametest.EntitiesGameTest.load;
import static dev.sculptory.mc.gametest.EntitiesGameTest.once;
import static dev.sculptory.mc.gametest.EntitiesGameTest.paste;
import static dev.sculptory.mc.gametest.EntitiesGameTest.ready;
import static dev.sculptory.mc.gametest.EntitiesGameTest.refusal;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;
import static dev.sculptory.mc.gametest.MultiplayerGameTest.tickUntil;

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
import dev.sculptory.mc.gametest.ClipTestSupport.Captured;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.mc.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.mc.schem.McDataFixHook;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.mc.world.McEntities;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.ServerClipboards;
import dev.sculptory.server.platform.WriteOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.vehicle.MinecartCommandBlock;

/**
 * Entities under hostile input and failure: files may not bring more passengers
 * than allowed, entities that are never placed, or operator data; entity work is bounded per step; placements and
 * restores that fail stay exact under undo; a cancelled move, and a cancelled undo or redo of one, lose no entity; Undo
 * anyway over chained moves restores the entity; masks, turns and the entity data cap apply to entities. Work regions:
 * {@code regionCorner} slots 653-661, 665, 667.
 */
public final class EntitySafetyGameTest {
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
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_file_rules", timeoutTicks = LIMIT)
    public void filesCannotBringNeverKindsOrMorePassengers(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 653);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 32);
        loadAndForce(world, all);
        java.nio.file.Path root = ClipTestSupport.libraryRoot(context);
        var clips = ClipTestSupport.clipboards(h, root);
        BlockPos target = new BlockPos(x0 + 4, y0, z0 + 4);
        Box pasted = box(target.x() - 4, y0 - 1, target.z() - 4, target.x() + 10, y0 + 6, target.z() + 10);
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        RecordingListener paste = new RecordingListener();
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> ClipTestSupport.upload(clips, h.player, "hostile.schem", hostileFile(),
                        uploaded)))
                .thenWaitUntil(() -> check(uploaded.finished(), "upload running"))
                .thenWaitUntil(once(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("the upload");
                    check(info.entities() == 19, "the stand with 16 riders and the boat with its minecart: "
                            + info.entities());
                    List<String> notices = info.notices().stream().map(n -> n.key() + n.args()).toList();
                    check(notices.contains("sculptory.notice.import_entities_skipped[9]"), "skipped: " + notices);
                    check(notices.contains(ServerClipboards.NOTICE_IMPORT_ENTITY_DATA + "[1]"), "stripped: " + notices);
                    paste(h, info.clipboardId(), target, Transform.IDENTITY, paste);
                }))
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    List<Entity> placed = new ArrayList<>();
                    world.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(Entity.class),
                            new net.minecraft.world.phys.AABB(pasted.min().x(), pasted.min().y(), pasted.min().z(),
                                    pasted.max().x() + 1, pasted.max().y() + 1, pasted.max().z() + 1),
                            e -> !e.isAlwaysTicking() && !e.isRemoved(), placed);
                    long stands = placed.stream().filter(e -> e instanceof ArmorStand).count();
                    check(stands == 1 + EntityNbt.MAX_RIDERS, "armor stands placed: " + stands);
                    List<Entity> carts = placed.stream().filter(e -> e instanceof MinecartCommandBlock).toList();
                    check(carts.size() == 1 && carts.get(0).getVehicle() != null, "the minecart rides the boat: " + carts);
                    check(((MinecartCommandBlock) carts.get(0)).getCommandBlock().getCommand().isEmpty(),
                            "the file's command survived, for an operator");
                    check(placed.stream().noneMatch(e -> McEntities.kind(e) == McEntities.Kind.NEVER
                            || e.getType() == EntityType.WITHER || e.getType() == EntityType.TNT
                            || e.getType() == EntityType.FIREBALL), "something never placed was: " + placed);
                    check(placed.size() == 1 + EntityNbt.MAX_RIDERS + 2, "entities placed: " + placed.size());
                    placed.forEach(Entity::discard);
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .thenSucceed();
    }

    /** The hostile file of {@link #filesCannotBringNeverKindsOrMorePassengers}: 6 × 3 × 6, a stone floor. */
    static byte[] hostileFile() {
        CompoundTag palette = new CompoundTag();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:stone", 1);
        byte[] data = new byte[6 * 3 * 6];
        for (int i = 0; i < 36; i++) data[i] = 1; // y = 0
        CompoundTag blocks = new CompoundTag();
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", data);
        blocks.put("BlockEntities", new ListTag());

        ListTag riders = new ListTag();
        for (int i = 0; i < 20; i++) riders.add(standData());
        CompoundTag loaded = standData();
        loaded.put("Passengers", riders);
        CompoundTag tntRider = new CompoundTag();
        tntRider.putString("id", "tnt");
        CompoundTag cartRider = new CompoundTag();
        cartRider.putString("id", "command_block_minecart");
        cartRider.putString("Command", "op @a");
        ListTag boatRiders = new ListTag();
        boatRiders.add(tntRider);
        boatRiders.add(cartRider);
        CompoundTag boat = new CompoundTag();
        boat.putBoolean("NoGravity", true);
        boat.put("Passengers", boatRiders);
        CompoundTag fireball = new CompoundTag();
        fireball.putByte("ExplosionPower", (byte) 127);
        CompoundTag outside = standData();
        outside.putInt("TileX", 0);
        outside.putInt("TileY", 1);
        outside.putInt("TileZ", 0);

        ListTag entities = new ListTag();
        entities.add(entry("minecraft:armor_stand", 1.5, 1, 1.5, loaded));
        entities.add(entry("minecraft:tnt", 2.5, 1, 2.5, new CompoundTag()));
        entities.add(entry("minecraft:boat", 3.5, 1, 3.5, boat));
        entities.add(entry("minecraft:wither", 4.5, 1, 4.5, new CompoundTag()));
        entities.add(entry("minecraft:fireball", 1.5, 2, 4.5, fireball));
        entities.add(entry("minecraft:armor_stand", -2.5, 1, 0.5, outside));

        CompoundTag schematic = new CompoundTag();
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", McDataFixHook.currentDataVersion());
        schematic.putShort("Width", (short) 6);
        schematic.putShort("Height", (short) 3);
        schematic.putShort("Length", (short) 6);
        schematic.putIntArray("Offset", new int[] {0, 0, 0});
        schematic.put("Blocks", blocks);
        schematic.put("Entities", entities);
        CompoundTag file = new CompoundTag();
        file.put("Schematic", schematic);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            net.minecraft.nbt.NbtIo.writeCompressed(file, out);
        } catch (IOException e) {
            throw new GameTestAssertException("cannot write the file: " + e);
        }
        return out.toByteArray();
    }

    private static CompoundTag standData() {
        CompoundTag stand = new CompoundTag();
        stand.putString("id", "minecraft:armor_stand");
        stand.putBoolean("NoGravity", true);
        return stand;
    }

    private static CompoundTag entry(String id, double x, double y, double z, CompoundTag data) {
        CompoundTag entry = new CompoundTag();
        entry.putString("Id", id);
        ListTag pos = new ListTag();
        pos.add(DoubleTag.valueOf(x));
        pos.add(DoubleTag.valueOf(y));
        pos.add(DoubleTag.valueOf(z));
        entry.put("Pos", pos);
        entry.put("Data", data);
        return entry;
    }

    // ------------------------------------------------------------------------------------------------ failures

    /**
     * A move is cancelled after it took its entities and before it placed them: they are gone from both places (the
     * record holds them), and undo puts them back exactly, blocks and entities alike.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_cancelled_move", timeoutTicks = LIMIT)
    public void aMoveCancelledAfterTakingItsEntitiesIsUndoneExactly(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = MultiplayerGameTest.executor(context, 64);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 654);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box destination = source.offset(20, 0, 0);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 40, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
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
                        throw new GameTestAssertException("refused: " + e.getMessage());
                    } finally {
                        executor.shutdown();
                        forceChunks(world, all, false);
                        h.close();
                    }
                }))
                .thenSucceed();
    }

    /**
     * Placement failures are counted and change nothing undo does not know about: a clipboard entity of an unknown type
     * is left out while the armor stand beside it is placed and undone; a cut armor stand whose UUID another entity took
     * meanwhile is not put back (counted as kept), while the cut blocks are.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_failures", timeoutTicks = LIMIT)
    public void placementFailuresAreCountedAndUndoStaysExact(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 655);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box pasteArea = box(x0, y0, z0, x0 + 2, y0 + 2, z0 + 2);
        Box cutArea = box(x0 + 8, y0, z0, x0 + 10, y0 + 2, z0 + 2);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        RecordingListener paste = new RecordingListener();
        RecordingListener undoPaste = new RecordingListener();
        Captured<ClipboardService.ClipboardInfo> cut = new Captured<>();
        RecordingListener erase = new RecordingListener();
        RecordingListener undoCut = new RecordingListener();
        UUID[] cutStand = {null};
        Entity[] squatter = {null};
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
                    dev.sculptory.core.nbt.NbtCompound still = dev.sculptory.core.nbt.NbtCompound.builder()
                            .putByte("NoGravity", (byte) 1).build();
                    byte[] data = dev.sculptory.core.nbt.NbtIo.toBytes(still);
                    Clipboard clipboard = Clipboard.builder(h.runtime.states(), new BlockPos(3, 3, 3)).build()
                            .withEntities(List.of(
                                    new EntitySnapshot("minecraft:armor_stand", 1.5, 0, 1.5, 0f, 0f, null, data, true),
                                    new EntitySnapshot("minecraft:not_a_thing", 0.5, 0, 0.5, 0f, 0f, null, data, true)));
                    UUID id = h.service.clipboards().install(h.player.getUUID(), clipboard).id();
                    paste(h, id, pasteArea.min(), Transform.IDENTITY, paste);
                }))
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(once(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED && paste.result.changed() == 1,
                            "only the armor stand was placed: " + paste.result);
                    check(entitiesIn(world, pasteArea).size() == 1, "placed " + entitiesIn(world, pasteArea));
                    h.undo(undoPaste);
                }))
                .thenWaitUntil(() -> check(undoPaste.result != null, "undo running"))
                .thenWaitUntil(once(() -> {
                    check(undoPaste.result.skippedConflicts() == 0, "undo " + undoPaste.result);
                    check(entitiesIn(world, pasteArea).isEmpty(), "the undo left " + entitiesIn(world, pasteArea));
                    BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
                    for (int x = x0 + 8; x <= x0 + 10; x++) {
                        for (int z = z0; z <= z0 + 2; z++) writer.write(x, y0, z, h.state("minecraft:stone"), null);
                    }
                    Entity stand = load(world, "minecraft:armor_stand", x0 + 9.5, y0 + 1, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    check(world.addFreshEntity(stand), "the stand spawned");
                    cutStand[0] = stand.getUUID();
                    try {
                        clips.copy(h.player, new Region.Cuboid(cutArea), cutArea.min(), true, CellMask.ANY,
                                EntityFilter.DECORATIONS, erase, cut);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("cut refused: " + e.getMessage());
                    }
                }))
                .thenWaitUntil(() -> check(erase.result != null && cut.finished(), "cut running"))
                .thenWaitUntil(once(() -> {
                    check(cut.get("cut").entities() == 1, "cut " + cut.value);
                    check(McEntities.find(world, cutStand[0]) == null, "the stand was cut");
                    // Another entity takes the stand's UUID meanwhile.
                    int[] uuid = net.minecraft.core.UUIDUtil.uuidToIntArray(cutStand[0]);
                    squatter[0] = load(world, "minecraft:pig", x0 + 20.5, y0 + 1, z0 + 1.5, 0f, nbt -> {
                        nbt.putIntArray("UUID", uuid);
                        nbt.putBoolean("NoAI", true);
                    });
                    check(world.addFreshEntity(squatter[0]), "the pig spawned");
                    h.undo(undoCut);
                }))
                .thenWaitUntil(() -> check(undoCut.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undoCut.result.skippedConflicts() == 1, "the stand is kept out: " + undoCut.result);
                    check(McEntities.find(world, cutStand[0]) == squatter[0], "the pig keeps its UUID");
                    check(entitiesIn(world, cutArea).isEmpty(), "no second entity with that UUID");
                    check(world.getBlockState(EngineTestSupport.pos(x0 + 9, y0, z0 + 1)).is(
                            net.minecraft.world.level.block.Blocks.STONE), "the floor is back");
                    squatter[0].discard();
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * Entity work comes in bounded steps: 30 armor stands stacked 12 times (360 placements) with a block cap that lets
     * one step run per tick never place more than
     * {@value dev.sculptory.server.engine.impl.EntityWork#ENTITIES_PER_STEP} in one tick, and all 360 are placed.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_steps", timeoutTicks = LIMIT)
    public void entityWorkIsBoundedPerStep(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = MultiplayerGameTest.executor(context, 1000);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 656);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 4, y0 + 1, z0 + 5);
        Box copies = box(x0 + 5, y0, z0, x0 + 64, y0 + 1, z0 + 5);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 80, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
                    try {
                        for (int i = 0; i < 30; i++) {
                            Entity stand = load(world, "minecraft:armor_stand", x0 + (i % 5) + 0.5, y0,
                                    z0 + (i / 5) + 0.5, 0f, nbt -> nbt.putBoolean("NoGravity", true));
                            check(world.addFreshEntity(stand), "stand " + i);
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
                        check(most > 0 && most <= dev.sculptory.server.engine.impl.EntityWork.ENTITIES_PER_STEP,
                                "placed " + most + " in one tick");
                        entitiesIn(world, all).forEach(Entity::discard);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("refused: " + e.getMessage());
                    } finally {
                        executor.shutdown();
                        forceChunks(world, all, false);
                        h.close();
                    }
                }))
                .thenSucceed();
    }

    /**
     * An undo or redo of a move cut off between its entity stages loses and doubles nothing. The undo is cancelled after
     * it took the moved armor stand away and before it put the original back: undo again, and the original is back,
     * once. The redo is cancelled after it took the original away and before it placed the moved one: the redo counts as
     * done (it changed something), so undo brings the original back, once.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_cancelled_history", timeoutTicks = LIMIT)
    public void cancelledUndoAndRedoOfAMoveKeepTheEntityOnce(GameTestHelper context) {
        EditExecutor<ServerLevel> executor = MultiplayerGameTest.executor(context, 64);
        Harness h = new Harness(context, null, System::nanoTime, executor);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 657);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box destination = source.offset(20, 0, 0);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 40, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
                    try {
                        Entity original = load(world, "minecraft:armor_stand", x0 + 1.5, y0, z0 + 1.5, 0f,
                                nbt -> nbt.putBoolean("NoGravity", true));
                        check(world.addFreshEntity(original), "the stand spawned");
                        UUID id = original.getUUID();
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
                        throw new GameTestAssertException("refused: " + e.getMessage());
                    } finally {
                        executor.shutdown();
                        forceChunks(world, all, false);
                        h.close();
                    }
                }))
                .thenSucceed();
    }

    /**
     * Undo anyway over two chained moves (s1 to p1, then p1 to p2) restores the entity: the moved one is renamed, so the
     * first undo keeps it (and puts p1 back beside it, the duplicate a changed entity can leave), the second puts the
     * original back; Undo anyway over both takes the renamed one away and leaves exactly the original.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_chained_moves", timeoutTicks = LIMIT)
    public void undoAnywayOverTwoChainedMovesRestoresTheEntity(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
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
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
                    Entity stand = load(world, "minecraft:armor_stand", x0 + 1.5, y0, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    check(world.addFreshEntity(stand), "the stand spawned");
                    original[0] = stand.getUUID();
                    move(h, source, first);
                }))
                .thenWaitUntil(() -> check(first.result != null, "first move running"))
                .thenWaitUntil(once(() -> move(h, source.offset(8, 0, 0), second)))
                .thenWaitUntil(() -> check(second.result != null, "second move running"))
                .thenWaitUntil(once(() -> {
                    List<Entity> moved = entitiesIn(world, source.offset(16, 0, 0));
                    check(moved.size() == 1, "moved twice: " + entitiesIn(world, all));
                    moved.get(0).setCustomName(net.minecraft.network.chat.Component.literal("renamed"));
                    h.undo(undo2);
                }))
                .thenWaitUntil(() -> check(undo2.result != null, "first undo running"))
                .thenWaitUntil(once(() -> {
                    check(undo2.result.skippedConflicts() == 1, "the renamed one is kept: " + undo2.result);
                    h.undo(undo1);
                }))
                .thenWaitUntil(() -> check(undo1.result != null, "second undo running"))
                .thenWaitUntil(once(() -> {
                    check(entitiesIn(world, source).size() == 1, "the original is back");
                    try {
                        h.service.historyOverwrite(h.player, false, 2, anyway);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("Undo anyway refused: " + e.getMessage());
                    }
                }))
                .thenWaitUntil(() -> check(anyway.result != null, "Undo anyway running"))
                .thenWaitUntil(() -> {
                    check(ids(entitiesIn(world, all)).equals(java.util.Set.of(original[0])), "exactly the original is left: "
                            + entitiesIn(world, all));
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    private static void move(Harness h, Box box, RecordingListener listener) {
        try {
            h.service.run(h.player, new OpSpec.Move(new Region.Cuboid(box), new BlockPos(8, 0, 0), Transform.IDENTITY,
                    new Pattern.Single(h.state("minecraft:air")), EntityFilter.DECORATIONS), RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestAssertException("move refused: " + e.getMessage());
        }
    }

    private static java.util.Set<UUID> ids(List<Entity> entities) {
        return entities.stream().map(Entity::getUUID).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Nothing is taken that could not be put back. An armor stand holds an item with 230,000 tags of custom data: small
     * enough for the history's own decoder (1.8 MB), too large for the game's loader (which charges some 40 bytes per
     * tag). It is never recorded, never copied and never moved: a move takes the plain stand beside it and leaves this
     * one, and undo brings the plain one back. Everything recorded loads back with the loader that restores it.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_restorable", timeoutTicks = LIMIT)
    public void entitiesThatCouldNotBePutBackAreNeverTaken(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 667);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 1, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        RecordingListener move = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        Entity[] fixture = new Entity[2];
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
                    fixture[0] = load(world, "minecraft:armor_stand", x0 + 1.5, y0, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    ArmorStand heavy = (ArmorStand) load(world, "minecraft:armor_stand", x0 + 2.5, y0,
                            z0 + 2.5, 0f, nbt -> nbt.putBoolean("NoGravity", true));
                    CompoundTag data = new CompoundTag();
                    for (int i = 0; i < 230_000; i++) data.putByte(Integer.toString(i, 36), (byte) 1);
                    net.minecraft.world.item.ItemStack stick = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK);
                    stick.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(data));
                    heavy.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stick);
                    fixture[1] = heavy;
                    check(world.addFreshEntity(fixture[0]) && world.addFreshEntity(heavy), "spawned");

                    // The history's decoder takes it; the game's loader does not: so it is never recorded.
                    CompoundTag whole = McEntities.save(heavy, EntityFilter.ALL);
                    byte[] bytes = McEntities.bytes(whole);
                    try {
                        McEntities.fromBytes(bytes);
                    } catch (IOException e) {
                        throw new GameTestAssertException("the fixture is too large even for the history's decoder: " + e);
                    }
                    check(!McEntities.restorable(bytes), "the game's loader takes the fixture after all");
                    check(McEntities.state(heavy, EntityFilter.ALL) == null, "an entity that cannot be put back "
                            + "was recorded");
                    dev.sculptory.core.history.EntityState plain = McEntities.state(fixture[0], EntityFilter.ALL);
                    check(plain != null && McEntities.restorable(plain.nbt()), "the plain stand is recorded");
                    check(refusal(() -> clips.copy(h.player, new Region.Cuboid(source), source.min(), false,
                            CellMask.ANY, EntityFilter.DECORATIONS, null, new Captured<>())) == RejectReason.TOO_LARGE,
                            "a copy took what it could not paste");
                    try {
                        h.service.run(h.player, new OpSpec.Move(new Region.Cuboid(source), new BlockPos(16, 0, 0),
                                Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air")),
                                EntityFilter.DECORATIONS), RunOptions.DEFAULT, move);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("move refused: " + e.getMessage());
                    }
                }))
                .thenWaitUntil(() -> check(move.result != null, "move running"))
                .thenWaitUntil(once(() -> {
                    check(!fixture[1].isRemoved(), "the heavy stand was taken");
                    check(fixture[0].isRemoved() && entitiesIn(world, source.offset(16, 0, 0)).size() == 1,
                            "the plain stand moved");
                    h.undo(undo);
                }))
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    check(McEntities.find(world, fixture[0].getUUID()) != null, "the plain stand is back");
                    check(entitiesIn(world, source).size() == 2, "both stands where they were: "
                            + entitiesIn(world, source));
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    // ------------------------------------------------------------------------------------------------ rules

    /**
     * A masked copy takes only the entities whose block the mask takes: with "only air", the frame, the painting (in the
     * air in front of their wall) and the display, not the armor stand, whose block is now glass.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_mask", timeoutTicks = LIMIT)
    public void maskedCopiesTakeTheEntitiesOfMaskedBlocks(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 658);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 16, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> airOnly = new Captured<>();
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
                    decorate(h, x0, y0, z0);
                    h.runtime.writer(world, WriteOptions.DEFAULT).write(x0 + 1, y0 + 1, z0 + 3,
                            h.state("minecraft:glass"), null); // the armor stand's block
                    try {
                        clips.copy(h.player, new Region.Cuboid(source), source.min(), false,
                                new CellMask.States(new int[] {h.state("minecraft:air")}), EntityFilter.DECORATIONS,
                                null, airOnly);
                    } catch (EditRejected e) {
                        throw new GameTestAssertException("copy refused: " + e.getMessage());
                    }
                }))
                .thenWaitUntil(() -> {
                    int taken = airOnly.get("the masked copy").entities();
                    check(taken == 3, "the frame, the painting and the display, not the stand: " + taken);
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** Passengers turn with their vehicle: an armor stand (yaw 30) riding a minecart, pasted a quarter turn, faces 120. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_riders_turn", timeoutTicks = LIMIT)
    public void passengersTurnWithTheirVehicle(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 659);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 2, y0 + 2, z0 + 2);
        BlockPos origin = new BlockPos(x0 + 10, y0, z0);
        Box target = box(x0 + 8, y0 - 1, z0 - 2, x0 + 14, y0 + 3, z0 + 4);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        RecordingListener paste = new RecordingListener();
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
                    Entity cart = load(world, "minecraft:minecart", x0 + 1.5, y0, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    Entity stand = load(world, "minecraft:armor_stand", x0 + 1.5, y0, z0 + 1.5, 30f,
                            nbt -> nbt.putBoolean("NoGravity", true));
                    check(world.addFreshEntity(cart) && world.addFreshEntity(stand), "spawned");
                    check(stand.startRiding(cart, true), "the stand rides the minecart");
                    copyInto(copied, clips, h.player, source, source.min(), EntityFilter.DECORATIONS);
                }))
                .thenWaitUntil(() -> check(copied.finished(), "copy running"))
                .thenWaitUntil(once(() -> {
                    check(copied.get("copy").entities() == 2, "copied " + copied.value);
                    paste(h, copied.value.clipboardId(), origin, Transform.rotation(1), paste);
                }))
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> {
                    List<Entity> riders = entitiesIn(world, target).stream()
                            .flatMap(e -> e.getPassengers().stream()).toList();
                    check(riders.size() == 1 && riders.get(0) instanceof ArmorStand, "riders " + riders);
                    float yaw = dev.sculptory.core.entity.EntityPlacement.wrap(riders.get(0).getYRot() - 120f);
                    check(Math.abs(yaw) < 0.01f, "the rider faces " + riders.get(0).getYRot() + ", not 120");
                    entitiesIn(world, all).forEach(e -> {
                        e.getPassengers().forEach(Entity::discard);
                        e.discard();
                    });
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /** A pasted chicken left alone (its egg timer runs) is still the chicken the paste placed: undo takes it back. */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_chicken", timeoutTicks = LIMIT)
    public void aChickenLeftAloneUndoesCleanly(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 660);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 2, y0 + 1, z0 + 2);
        BlockPos origin = new BlockPos(x0 + 8, y0, z0);
        Box target = source.offset(8, 0, 0);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        RecordingListener paste = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        int[] waited = {0};
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
                    BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
                    for (int x = x0 - 1; x <= x0 + 11; x++) {
                        for (int z = z0 - 1; z <= z0 + 3; z++) writer.write(x, y0 - 1, z, h.state("minecraft:stone"), null);
                    }
                    Entity chicken = load(world, "minecraft:chicken", x0 + 1.5, y0, z0 + 1.5, 0f,
                            nbt -> nbt.putBoolean("NoAI", true));
                    check(world.addFreshEntity(chicken), "the chicken spawned");
                    copyInto(copied, clips, h.player, source, source.min(), EntityFilter.ALL);
                }))
                .thenWaitUntil(() -> check(copied.finished(), "copy running"))
                .thenWaitUntil(once(() -> {
                    check(copied.get("copy").entities() == 1, "copied " + copied.value);
                    paste(h, copied.value.clipboardId(), origin, Transform.IDENTITY, paste);
                }))
                .thenWaitUntil(() -> check(paste.result != null, "paste running"))
                .thenWaitUntil(() -> check(++waited[0] >= 40, "letting the chicken's timers run"))
                .thenWaitUntil(once(() -> {
                    check(entitiesIn(world, target).size() == 1, "pasted " + entitiesIn(world, target));
                    h.undo(undo);
                }))
                .thenWaitUntil(() -> check(undo.result != null, "undo running"))
                .thenWaitUntil(() -> {
                    check(undo.result.skippedConflicts() == 0, "the chicken counted as changed: " + undo.result);
                    check(entitiesIn(world, target).isEmpty(), "the chicken stayed");
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }

    /**
     * A copy's entity data is capped for everyone (it is snapshotted in one tick): 22 armor stands with 30 tags of 55,000
     * letters each (36 MB; NBT strings hold at most 64 KB) are refused {@code TOO_LARGE}; the same copy without entities goes ahead.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_entities_bytes", timeoutTicks = LIMIT)
    public void copiesRefuseTooMuchEntityData(GameTestHelper context) {
        Harness h = new Harness(context);
        ServerLevel world = h.world;
        int[] at = regionCorner(context, 661);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 10, y0 + 1, z0 + 1);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        var clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> blocksOnly = new Captured<>();
        context.startSequence()
                .thenWaitUntil(() -> ready(world, all))
                .thenWaitUntil(once(() -> {
                    String tag = "x".repeat(55_000);
                    for (int i = 0; i < 22; i++) {
                        Entity stand = load(world, "minecraft:armor_stand", x0 + (i % 11) + 0.5, y0, z0 + (i / 11) + 0.5,
                                0f, nbt -> nbt.putBoolean("NoGravity", true));
                        for (int t = 0; t < 30; t++) stand.addTag(tag + t);
                        check(world.addFreshEntity(stand), "stand " + i);
                    }
                    RejectReason reason = refusal(() -> clips.copy(h.player, new Region.Cuboid(source), source.min(),
                            false, CellMask.ANY, EntityFilter.DECORATIONS, null, new Captured<>()));
                    check(reason == RejectReason.TOO_LARGE, "refused " + reason);
                    copyInto(blocksOnly, clips, h.player, source, source.min(), EntityFilter.NONE);
                }))
                .thenWaitUntil(() -> {
                    check(blocksOnly.get("the copy without entities").entities() == 0, "no entities");
                    entitiesIn(world, all).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .thenSucceed();
    }
}
