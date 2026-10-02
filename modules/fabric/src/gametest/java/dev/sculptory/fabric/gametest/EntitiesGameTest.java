package dev.sculptory.fabric.gametest;

import static dev.sculptory.fabric.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.fabric.gametest.EngineTestSupport.box;
import static dev.sculptory.fabric.gametest.EngineTestSupport.check;
import static dev.sculptory.fabric.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.fabric.gametest.EngineTestSupport.regionCorner;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.PasteGeometry;
import dev.sculptory.core.edit.PasteOptions;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.edit.SourceRef;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntityPlacement;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.core.history.EntityChange;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import dev.sculptory.fabric.engine.impl.ServerClipboards;
import dev.sculptory.fabric.gametest.ClipTestSupport.Captured;
import dev.sculptory.fabric.gametest.EditTestSupport.Harness;
import dev.sculptory.fabric.gametest.EngineTestSupport.RecordingListener;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.FabricEntities;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ClipboardService;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.boss.dragon.EnderDragonPart;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.decoration.painting.PaintingVariants;
import net.minecraft.entity.vehicle.CommandBlockMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtDouble;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.border.WorldBorder;

/**
 * Entities in copies, cuts, pastes, moves and stacks, and their undo. Work
 * regions: {@code regionCorner} slots 640-679 (650 is DurableHistoryGameTest's).
 */
public final class EntitiesGameTest implements FabricGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;

    // ------------------------------------------------------------------------------------------------ fixtures

    /**
     * A stone floor (y0, x0..x0+3, z0..z0+3) and wall along x at {@code z0} (y0..y0+2) with, in front of the wall
     * (south), an item frame holding a diamond and a two-wide painting, and on the floor an armor stand wearing an iron
     * helmet and a block display.
     */
    static List<Entity> decorate(Harness h, int x0, int y0, int z0) {
        ServerWorld world = h.world;
        BlockWriter writer = h.runtime.writer(world, BlockWriter.Options.DEFAULT);
        for (int x = x0; x <= x0 + 3; x++) {
            for (int y = y0; y <= y0 + 2; y++) writer.write(x, y, z0, h.state("minecraft:stone"), null);
            for (int z = z0 + 1; z <= z0 + 3; z++) writer.write(x, y0, z, h.state("minecraft:stone"), null);
        }
        List<Entity> made = new ArrayList<>();
        ItemFrameEntity frame = new ItemFrameEntity(world, EngineTestSupport.pos(x0 + 1, y0 + 1, z0 + 1), Direction.SOUTH);
        frame.setHeldItemStack(new ItemStack(Items.DIAMOND), false);
        made.add(frame);
        PaintingEntity painting = new PaintingEntity(world, EngineTestSupport.pos(x0 + 2, y0 + 1, z0 + 1), Direction.SOUTH,
                world.getRegistryManager().get(RegistryKeys.PAINTING_VARIANT).entryOf(PaintingVariants.POOL));
        made.add(painting);
        ArmorStandEntity stand = new ArmorStandEntity(world, x0 + 1.5, y0 + 1, z0 + 3.5);
        stand.setYaw(30f);
        stand.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        made.add(stand);
        made.add(load(world, "minecraft:block_display", x0 + 3.5, y0 + 1, z0 + 3.25, 45f,
                nbt -> nbt.put("block_state", compound("Name", "minecraft:stone"))));
        for (Entity entity : made) check(world.spawnEntity(entity), "fixture " + entity + " spawned");
        return made;
    }

    static NbtCompound compound(String key, String value) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString(key, value);
        return nbt;
    }

    /** An entity of {@code type} loaded from NBT at a position and heading (not spawned yet). */
    static Entity load(ServerWorld world, String type, double x, double y, double z, float yaw,
                       java.util.function.Consumer<NbtCompound> data) {
        NbtCompound nbt = new NbtCompound();
        nbt.putString("id", type);
        NbtList pos = new NbtList();
        pos.add(NbtDouble.of(x));
        pos.add(NbtDouble.of(y));
        pos.add(NbtDouble.of(z));
        nbt.put("Pos", pos);
        NbtList rotation = new NbtList();
        rotation.add(NbtFloat.of(yaw));
        rotation.add(NbtFloat.of(0f));
        nbt.put("Rotation", rotation);
        data.accept(nbt);
        return EntityType.getEntityFromNbt(nbt, world).orElseThrow(() -> new GameTestException("no " + type));
    }

    /** The entities (players never) belonging to {@code area} as the server decides it: by {@link FabricEntities#cell}. */
    static List<Entity> entitiesIn(ServerWorld world, Box area) {
        int margin = FabricEntities.MARGIN + 1;
        net.minecraft.util.math.Box box = new net.minecraft.util.math.Box(area.min().x() - margin,
                area.min().y() - margin, area.min().z() - margin, area.max().x() + 1 + margin,
                area.max().y() + 1 + margin, area.max().z() + 1 + margin);
        Region region = new Region.Cuboid(area);
        return world.getOtherEntities(null, box, e -> !e.isPlayer() && !e.isRemoved() && FabricEntities.in(region, e));
    }

    /** Each entity's data as undo compares it (without volatile keys), by UUID. */
    static Map<UUID, dev.sculptory.core.nbt.NbtCompound> comparable(List<Entity> entities) {
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> out = new HashMap<>();
        for (Entity entity : entities) {
            try {
                out.put(entity.getUuid(), EntityNbt.withoutVolatile(FabricEntities.fromBytes(
                        FabricEntities.bytes(FabricEntities.save(entity, EntityFilter.ALL)))));
            } catch (IOException e) {
                throw new GameTestException("entity NBT: " + e);
            }
        }
        return out;
    }

    /** The entities the player's latest step placed, as it recorded them (without volatile keys), by UUID. */
    static Map<UUID, dev.sculptory.core.nbt.NbtCompound> recorded(Harness h) {
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> out = new HashMap<>();
        HistoryEntry latest = h.service.historyService().undoEntries(h.player.getUuid()).get(0);
        for (EntityChange change : latest.record().entities()) {
            if (change.after() == null) continue;
            try {
                out.put(change.id(), EntityNbt.withoutVolatile(EntityNbt.decode(change.after().nbt())));
            } catch (IOException e) {
                throw new GameTestException("recorded NBT: " + e);
            }
        }
        return out;
    }

    /** The cells of {@code box} that are not air, as "x,y,z=state" (for failure messages). */
    static List<String> nonAir(ServerWorld world, Box box) {
        List<String> out = new ArrayList<>();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    net.minecraft.block.BlockState state = world.getBlockState(EngineTestSupport.pos(x, y, z));
                    if (!state.isAir()) out.add(x + "," + y + "," + z + "=" + state);
                }
            }
        }
        return out;
    }

    /** Per entity present in both, the keys whose values differ (for failure messages). */
    static String changedSince(Map<UUID, dev.sculptory.core.nbt.NbtCompound> before,
                               Map<UUID, dev.sculptory.core.nbt.NbtCompound> now) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<UUID, dev.sculptory.core.nbt.NbtCompound> entry : now.entrySet()) {
            dev.sculptory.core.nbt.NbtCompound was = before.get(entry.getKey());
            if (was == null) {
                out.add(entry.getValue().getString("id") + " (new)");
                continue;
            }
            java.util.Set<String> keys = new java.util.TreeSet<>(was.keys());
            keys.addAll(entry.getValue().keys());
            for (String key : keys) {
                if (!java.util.Objects.equals(was.get(key), entry.getValue().get(key))) {
                    out.add(entry.getValue().getString("id") + "." + key + ": " + was.get(key) + " -> "
                            + entry.getValue().get(key));
                }
            }
        }
        return out.toString();
    }

    /**
     * Waits (as a timed-task check) until every chunk of {@code area} is loaded with its entities: vanilla reads a
     * chunk's entities some ticks after the chunk, and copies refuse what they cannot see.
     */
    static void ready(ServerWorld world, Box area) {
        String unloaded = FabricEntities.firstUnloaded(world, area);
        check(unloaded == null, "the entities of chunk " + unloaded + " are not loaded yet");
    }

    /** Copies {@code box} with the entities {@code filter} takes; the answer arrives in {@code reply} later. */
    static void copyInto(Captured<ClipboardService.ClipboardInfo> reply, ServerClipboards clips,
                         ServerPlayerEntity player, Box box, BlockPos origin, EntityFilter filter) {
        try {
            clips.copy(player, new Region.Cuboid(box), origin, false, CellMask.ANY, filter, null, reply);
        } catch (EditRejected e) {
            throw new GameTestException("copy refused: " + e.getMessage());
        }
    }

    static JobTicket paste(Harness h, UUID clipboardId, BlockPos origin, Transform t, RecordingListener listener) {
        try {
            return h.service.run(h.player, new OpSpec.Paste(new SourceRef.Clipboard(clipboardId), origin, t,
                    PasteOptions.DEFAULT), RunOptions.DEFAULT, listener);
        } catch (EditRejected e) {
            throw new GameTestException("paste refused: " + e.getMessage());
        }
    }

    static BlockMirror vanilla(Mirror mirror) {
        return switch (mirror) {
            case NONE -> BlockMirror.NONE;
            case X -> BlockMirror.FRONT_BACK;
            case Z -> BlockMirror.LEFT_RIGHT;
        };
    }

    static BlockRotation vanilla(int turns) {
        return switch (turns & 3) {
            case 0 -> BlockRotation.NONE;
            case 1 -> BlockRotation.CLOCKWISE_90;
            case 2 -> BlockRotation.CLOCKWISE_180;
            default -> BlockRotation.COUNTERCLOCKWISE_90;
        };
    }

    /** What a source entity should look like once its box (of {@code size}, from {@code min}) is placed. */
    record Expected(EntityType<?> type, Vec3d pos, Direction facing, float yaw) {}

    static Expected expected(Entity source, Box from, Transform t, BlockPos targetMin) {
        Vec3d local = source.getPos().subtract(from.min().x(), from.min().y(), from.min().z());
        int sx = from.sizeX(), sz = from.sizeZ();
        Vec3d pos = new Vec3d(targetMin.x() + EntityPlacement.mapX(local.x, local.z, t, sx, sz),
                targetMin.y() + local.y, targetMin.z() + EntityPlacement.mapZ(local.x, local.z, t, sx, sz));
        Direction facing = source instanceof AbstractDecorationEntity decoration
                ? vanilla(t.quarterTurnsCw()).rotate(vanilla(t.mirror()).apply(decoration.getHorizontalFacing()))
                : null;
        return new Expected(source.getType(), pos, facing, EntityPlacement.yaw(source.getYaw(), t));
    }

    /** The one entity of the expected type at the expected position, checked. */
    static Entity checkPlaced(ServerWorld world, Expected expected, String what) {
        net.minecraft.util.math.Box near = new net.minecraft.util.math.Box(expected.pos(), expected.pos()).expand(0.01);
        List<Entity> found = world.getOtherEntities(null, near, e -> e.getType() == expected.type() && !e.isRemoved());
        if (found.size() != 1) {
            List<String> around = new ArrayList<>();
            for (Entity e : world.getOtherEntities(null, near.expand(3), e -> e.getType() == expected.type())) {
                around.add(e.getPos().toString() + (e instanceof AbstractDecorationEntity d ? " " + d.getHorizontalFacing()
                        : " yaw " + e.getYaw()));
            }
            throw new GameTestException(what + ": " + found.size() + " " + EntityType.getId(expected.type()) + " at "
                    + expected.pos() + " (nearby: " + around + ")");
        }
        Entity entity = found.get(0);
        if (expected.facing() != null) {
            Direction facing = entity.getHorizontalFacing();
            check(facing == expected.facing(), what + ": " + EntityType.getId(expected.type()) + " faces " + facing
                    + ", expected " + expected.facing());
        } else {
            float diff = EntityPlacement.wrap(entity.getYaw() - expected.yaw());
            check(Math.abs(diff) < 0.01f, what + ": " + EntityType.getId(expected.type()) + " yaw " + entity.getYaw()
                    + ", expected " + expected.yaw());
        }
        return entity;
    }

    // ------------------------------------------------------------------------------------------------ tests

    /**
     * An item frame holding a diamond, a two-wide painting, an armor stand with a helmet and a block display, copied with
     * their wall and pasted with turns and mirrors: each lands where the transform puts it (hanging entities on their
     * turned wall, facing turned as vanilla turns them, mirror first), keeps its data, and the hanging ones still hang
     * after vanilla's support check (100 ticks).
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_paste", tickLimit = LIMIT)
    public void copiedDecorationsArePastedTurnedAndMirrored(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 640);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        List<Transform> transforms = List.of(Transform.IDENTITY, Transform.rotation(1), new Transform(0, Mirror.X),
                new Transform(1, Mirror.Z), Transform.rotation(2), new Transform(3, Mirror.X));
        int spacing = 16;
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + spacing * (transforms.size() + 1) + 16, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        List<Entity> fixture = new ArrayList<>();
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        List<RecordingListener> pastes = new ArrayList<>();
        List<Expected> expectations = new ArrayList<>();
        int[] waited = {0};
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    fixture.addAll(decorate(h, x0, y0, z0));
                    copyInto(copied, clips, h.player, source, source.min(), EntityFilter.DECORATIONS);
                }))
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copied.get("copy");
                    check(info.entities() == 4, "the clipboard holds " + info.entities() + " entities");
                    for (int k = 0; k < transforms.size(); k++) {
                        BlockPos origin = new BlockPos(x0 + spacing * (k + 1), y0, z0);
                        Transform t = transforms.get(k);
                        BlockPos targetMin = PasteGeometry.pasteTarget(new BlockPos(4, 3, 4), BlockPos.ORIGIN, t, origin)
                                .min();
                        for (Entity entity : fixture) expectations.add(expected(entity, source, t, targetMin));
                        RecordingListener listener = new RecordingListener();
                        paste(h, info.clipboardId(), origin, t, listener);
                        pastes.add(listener);
                    }
                })
                .createAndAdd(() -> {
                    for (RecordingListener listener : pastes) check(listener.result != null, "pastes running");
                })
                .createAndAdd(() -> {
                    for (int k = 0; k < pastes.size(); k++) {
                        check(pastes.get(k).result.outcome() == JobOutcome.COMPLETED, "paste " + k + " "
                                + pastes.get(k).result);
                    }
                    for (int i = 0; i < expectations.size(); i++) {
                        Transform t = transforms.get(i / fixture.size());
                        Entity placed = checkPlaced(world, expectations.get(i), t.toString());
                        switch (placed) {
                            case ItemFrameEntity frame -> check(frame.getHeldItemStack().isOf(Items.DIAMOND),
                                    t + ": the frame holds " + frame.getHeldItemStack());
                            case PaintingEntity painting -> check(painting.getVariant().matchesKey(PaintingVariants.POOL),
                                    t + ": painting " + painting.getVariant());
                            case ArmorStandEntity stand -> check(stand.getEquippedStack(EquipmentSlot.HEAD)
                                    .isOf(Items.IRON_HELMET), t + ": the stand wears " + stand.getEquippedStack(
                                    EquipmentSlot.HEAD));
                            default -> {}
                        }
                        check(!placed.getUuid().equals(fixture.get(i % fixture.size()).getUuid()), "a new UUID");
                    }
                })
                .createAndAdd(() -> check(++waited[0] > 110, "waiting past vanilla's support check"))
                .createAndAdd(() -> {
                    for (int i = 0; i < expectations.size(); i++) {
                        checkPlaced(world, expectations.get(i), "still there: " + transforms.get(i / fixture.size()));
                    }
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A paste undone removes exactly the entities it placed (the fixture stays); redone, they come back with their UUIDs.
     * A pasted frame given another item since is kept by the next undo and counted with the kept blocks; Undo anyway
     * takes it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_undo_paste", tickLimit = LIMIT)
    public void pasteUndoRemovesExactlyItsEntitiesAndKeepsChangedOnes(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 644);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box target = box(x0 + 20, y0, z0, x0 + 23, y0 + 2, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 48, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        List<Entity> fixture = new ArrayList<>();
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> sourceBefore = new HashMap<>();
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        RecordingListener paste = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        RecordingListener redo = new RecordingListener();
        RecordingListener undoAgain = new RecordingListener();
        RecordingListener anyway = new RecordingListener();
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> placed = new HashMap<>();
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> recordedAfter = new HashMap<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    fixture.addAll(decorate(h, x0, y0, z0));
                    sourceBefore.putAll(comparable(fixture));
                    copyInto(copied, clips, h.player, source, source.min(), EntityFilter.DECORATIONS);
                }))
                .createAndAdd(() -> paste(h, copied.get("copy").clipboardId(), target.min(), Transform.IDENTITY, paste))
                .createAndAdd(() -> check(paste.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(paste.result.outcome() == JobOutcome.COMPLETED, "paste " + paste.result);
                    List<Entity> there = entitiesIn(world, target);
                    check(there.size() == 4, "4 entities pasted, found " + there.size());
                    placed.putAll(comparable(there));
                    recordedAfter.putAll(recorded(h));
                    check(h.history().undoLabel().contains("4 entities"), "label " + h.history().undoLabel());
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.skippedConflicts() == 0, "nothing kept: " + undo.result + "; changed since: "
                            + changedSince(recordedAfter, comparable(entitiesIn(world, target))) + "; left: "
                            + entitiesIn(world, target) + ", blocks left: " + nonAir(world, target));
                    check(entitiesIn(world, target).isEmpty(), "the pasted entities are gone: "
                            + entitiesIn(world, target));
                    check(comparable(entitiesIn(world, source)).equals(sourceBefore), "the source is untouched");
                    h.redo(redo);
                })
                .createAndAdd(() -> check(redo.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redo.result.skippedConflicts() == 0, "redo kept nothing: " + redo.result);
                    List<Entity> back = entitiesIn(world, target);
                    check(comparable(back).equals(placed), "redo puts back the same entities with their UUIDs");
                    for (Entity entity : back) {
                        if (entity instanceof ItemFrameEntity frame) frame.setHeldItemStack(new ItemStack(Items.EMERALD));
                    }
                    h.undo(undoAgain);
                })
                .createAndAdd(() -> check(undoAgain.result != null, "second undo running"))
                .createAndAdd(() -> {
                    check(undoAgain.result.skippedConflicts() == 1, "the changed frame is kept and counted: "
                            + undoAgain.result);
                    List<Entity> left = entitiesIn(world, target);
                    check(left.size() == 1 && left.get(0) instanceof ItemFrameEntity, "only the frame stays: " + left);
                    try {
                        h.service.historyOverwrite(h.player, false, 1, anyway);
                    } catch (EditRejected e) {
                        throw new GameTestException("undo anyway refused: " + e.getMessage());
                    }
                })
                .createAndAdd(() -> check(anyway.result != null, "undo anyway running"))
                .createAndAdd(() -> {
                    check(anyway.result.outcome() == JobOutcome.COMPLETED, "undo anyway " + anyway.result);
                    check(entitiesIn(world, target).isEmpty(), "Undo anyway takes the frame: " + entitiesIn(world, target));
                    check(comparable(entitiesIn(world, source)).equals(sourceBefore), "the source is untouched");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A cut takes the frame, painting, stand and display away with their wall; undone, the same entities come back. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_cut", tickLimit = LIMIT)
    public void cutUndoRestoresTheSameEntities(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 641);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 16, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> before = new HashMap<>();
        Captured<ClipboardService.ClipboardInfo> cut = new Captured<>();
        RecordingListener erase = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        RecordingListener redo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    before.putAll(comparable(decorate(h, x0, y0, z0)));
                    try {
                        clips.copy(h.player, new Region.Cuboid(source), source.min(), true, CellMask.ANY,
                                EntityFilter.DECORATIONS, erase, cut);
                    } catch (EditRejected e) {
                        throw new GameTestException("cut refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(erase.result != null && cut.finished(), "cut running"))
                .createAndAdd(() -> {
                    check(cut.get("cut").entities() == 4, "the clipboard holds " + cut.value.entities());
                    check(erase.result.outcome() == JobOutcome.COMPLETED, "erase " + erase.result);
                    check(entitiesIn(world, source).isEmpty(), "the cut took the entities: " + entitiesIn(world, source));
                    check(nonAir(world, source).isEmpty(), "and the blocks");
                    check(h.history().undoLabel().contains("4 entities"), "one step: " + h.history().undoLabel());
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    check(comparable(entitiesIn(world, source)).equals(before), "the same entities are back: "
                            + changedSince(before, comparable(entitiesIn(world, source))));
                    check(nonAir(world, source).size() == 24, "and the wall and floor");
                    h.redo(redo);
                })
                .createAndAdd(() -> check(redo.result != null, "redo running"))
                .createAndAdd(() -> {
                    check(redo.result.skippedConflicts() == 0, "redo " + redo.result);
                    check(entitiesIn(world, source).isEmpty(), "redone, the cut takes them again");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A move with a quarter turn takes the entities to the destination (new UUIDs) and leaves none behind; undone, the
     * originals are back and the moved ones gone.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_move", tickLimit = LIMIT)
    public void moveTakesEntitiesAlongAndUndoBringsThemBack(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 642);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        BlockPos offset = new BlockPos(20, 0, 0);
        Transform t = Transform.rotation(1);
        Box destination = box(x0 + 20, y0, z0, x0 + 23, y0 + 2, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 40, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        List<Entity> fixture = new ArrayList<>();
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> before = new HashMap<>();
        List<Expected> expectations = new ArrayList<>();
        RecordingListener move = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    fixture.addAll(decorate(h, x0, y0, z0));
                    before.putAll(comparable(fixture));
                    for (Entity entity : fixture) expectations.add(expected(entity, source, t, destination.min()));
                    try {
                        h.service.run(h.player, new OpSpec.Move(new Region.Cuboid(source), offset, t,
                                new Pattern.Single(h.state("minecraft:air")), EntityFilter.DECORATIONS),
                                RunOptions.DEFAULT, move);
                    } catch (EditRejected e) {
                        throw new GameTestException("move refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    check(entitiesIn(world, source).isEmpty(), "nothing left behind: " + entitiesIn(world, source));
                    for (Expected expected : expectations) {
                        Entity moved = checkPlaced(world, expected, "moved");
                        check(!before.containsKey(moved.getUuid()), "a new UUID");
                    }
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    check(comparable(entitiesIn(world, source)).equals(before), "the originals are back: "
                            + changedSince(before, comparable(entitiesIn(world, source))));
                    check(entitiesIn(world, destination).isEmpty(), "the moved ones are gone");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** A stack copies the entities with the blocks, each copy shifted, the source untouched; undone, the copies go. */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_stack", tickLimit = LIMIT)
    public void stackCopiesEntities(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 643);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        Box copies = box(x0 + 4, y0, z0, x0 + 11, y0 + 2, z0 + 3);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 24, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        List<Entity> fixture = new ArrayList<>();
        Map<UUID, dev.sculptory.core.nbt.NbtCompound> before = new HashMap<>();
        RecordingListener stack = new RecordingListener();
        RecordingListener undo = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    fixture.addAll(decorate(h, x0, y0, z0));
                    before.putAll(comparable(fixture));
                    try {
                        h.service.run(h.player, new OpSpec.Stack(new Region.Cuboid(source), 4, 0, 0, 2,
                                EntityFilter.DECORATIONS), RunOptions.DEFAULT, stack);
                    } catch (EditRejected e) {
                        throw new GameTestException("stack refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(stack.result != null, "stack running"))
                .createAndAdd(() -> {
                    check(stack.result.outcome() == JobOutcome.COMPLETED, "stack " + stack.result);
                    for (int k = 1; k <= 2; k++) {
                        BlockPos copyMin = new BlockPos(x0 + 4 * k, y0, z0);
                        for (Entity entity : fixture) {
                            checkPlaced(world, expected(entity, source, Transform.IDENTITY, copyMin), "copy " + k);
                        }
                    }
                    check(comparable(entitiesIn(world, source)).equals(before), "the source is untouched");
                    h.undo(undo);
                })
                .createAndAdd(() -> check(undo.result != null, "undo running"))
                .createAndAdd(() -> {
                    check(undo.result.skippedConflicts() == 0, "undo " + undo.result);
                    check(entitiesIn(world, copies).isEmpty(), "the copies are gone: " + entitiesIn(world, copies));
                    check(comparable(entitiesIn(world, source)).equals(before), "the source is untouched");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * Which entities a copy takes: none with NONE; the frame, the armor stand and a minecart (without the cow riding
     * it) with DECORATIONS; the cow and the minecart's rider too with ALL. A dropped item and a player never.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_filter", tickLimit = LIMIT)
    public void theFilterDecidesWhichEntitiesACopyTakes(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 646);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 5, y0 + 2, z0 + 5);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 16, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Map<EntityFilter, Captured<ClipboardService.ClipboardInfo>> copies = new java.util.EnumMap<>(EntityFilter.class);
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    BlockWriter writer = h.runtime.writer(world, BlockWriter.Options.DEFAULT);
                    for (int x = x0; x <= x0 + 5; x++) {
                        for (int z = z0; z <= z0 + 5; z++) writer.write(x, y0, z, h.state("minecraft:stone"), null);
                    }
                    ItemFrameEntity frame = new ItemFrameEntity(world, EngineTestSupport.pos(x0 + 1, y0 + 1, z0 + 1),
                            Direction.UP);
                    ArmorStandEntity stand = new ArmorStandEntity(world, x0 + 2.5, y0 + 1, z0 + 2.5);
                    Entity cow = load(world, "minecraft:cow", x0 + 4.5, y0 + 1, z0 + 4.5, 0f,
                            nbt -> nbt.putBoolean("NoAI", true));
                    Entity cart = load(world, "minecraft:minecart", x0 + 1.5, y0 + 1, z0 + 4.5, 0f, nbt -> { });
                    Entity rider = load(world, "minecraft:cow", x0 + 1.5, y0 + 1, z0 + 4.5, 0f,
                            nbt -> nbt.putBoolean("NoAI", true));
                    Entity item = new net.minecraft.entity.ItemEntity(world, x0 + 3.5, y0 + 1, z0 + 1.5,
                            new ItemStack(Items.APPLE));
                    for (Entity entity : List.of(frame, stand, cow, cart, rider, item)) {
                        check(world.spawnEntity(entity), "spawned " + entity);
                    }
                    check(rider.startRiding(cart, true), "the cow rides the minecart");
                    h.player.refreshPositionAndAngles(x0 + 3.5, y0 + 1, z0 + 3.5, 0f, 0f);
                    copy(copies, clips, h, source, EntityFilter.NONE);
                }))
                .createAndAdd(() -> {
                    check(copies.get(EntityFilter.NONE).get("NONE").entities() == 0, "NONE takes none");
                    copy(copies, clips, h, source, EntityFilter.DECORATIONS);
                })
                .createAndAdd(() -> {
                    int taken = copies.get(EntityFilter.DECORATIONS).get("DECORATIONS").entities();
                    check(taken == 3, "DECORATIONS takes the frame, the stand and the minecart: " + taken);
                    copy(copies, clips, h, source, EntityFilter.ALL);
                })
                .createAndAdd(() -> {
                    ClipboardService.ClipboardInfo info = copies.get(EntityFilter.ALL).get("ALL");
                    check(info.entities() == 5, "ALL takes the cow too, and the one riding the minecart: "
                            + info.entities());
                    dev.sculptory.core.clipboard.Clipboard held = h.service
                            .sourceClipboard(h.player, new SourceRef.Clipboard(info.clipboardId())).orElseThrow();
                    check(held.entityCount() == 4, "the clipboard holds " + held.entityCount());
                    long carrying = held.entities().stream().filter(e -> e.typeId().equals("minecraft:minecart"))
                            .filter(EntitiesGameTest::hasRiders).count();
                    check(carrying == 1, "ALL keeps the minecart's rider");
                    check(held.entities().stream().noneMatch(e -> e.typeId().equals("minecraft:item")
                            || e.typeId().equals("minecraft:player")), "no dropped item, no player");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** Starts a copy of {@code source} with {@code filter} (once: a step retried after it started only waits). */
    private static void copy(Map<EntityFilter, Captured<ClipboardService.ClipboardInfo>> copies, ServerClipboards clips,
                             Harness h, Box source, EntityFilter filter) {
        if (copies.containsKey(filter)) return;
        Captured<ClipboardService.ClipboardInfo> reply = new Captured<>();
        copyInto(reply, clips, h.player, source, source.min(), filter);
        copies.put(filter, reply);
    }

    /**
     * A timed-task step that runs its body once: a step making a fixture must not make it again when it fails and the
     * runner tries it again. The failure is thrown again on every later try.
     */
    static Runnable once(Runnable body) {
        RuntimeException[] failure = {null};
        boolean[] ran = {false};
        return () -> {
            if (!ran[0]) {
                ran[0] = true;
                try {
                    body.run();
                } catch (RuntimeException e) {
                    failure[0] = e;
                }
            }
            if (failure[0] != null) throw failure[0];
        };
    }

    static boolean hasRiders(dev.sculptory.core.entity.EntitySnapshot entity) {
        try {
            return EntityNbt.decode(entity.nbt()).getList("Passengers") != null;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Entities are read with the blocks, so a copy, move or stack taking entities is refused {@code UNLOADED} while a
     * chunk's entities are not loaded (vanilla loads them a tick or more after the chunk); without entities they go
     * ahead. Once the entities are loaded the copy works.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_unloaded", tickLimit = LIMIT)
    public void entitiesThatAreNotLoadedAreRefusedNeverMissed(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 648);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0 + 2, y0, z0 + 2, x0 + 5, y0 + 2, z0 + 5);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        boolean[] checked = {false};
        Captured<ClipboardService.ClipboardInfo> blocksOnly = new Captured<>();
        if (FabricEntities.firstUnloaded(world, source) != null) {
            // The same tick the chunks were made: their entities are still being read.
            checked[0] = true;
            check(refusal(() -> clips.copy(h.player, new Region.Cuboid(source), source.min(), false, CellMask.ANY,
                    EntityFilter.DECORATIONS, null, new Captured<>())) == RejectReason.UNLOADED, "a copy with entities");
            check(refusal(() -> h.service.run(h.player, new OpSpec.Stack(new Region.Cuboid(source), 8, 0, 0, 1,
                    EntityFilter.ALL), RunOptions.DEFAULT, new RecordingListener())) == RejectReason.UNLOADED,
                    "a stack with entities");
            check(refusal(() -> h.service.run(h.player, new OpSpec.Move(new Region.Cuboid(source), new BlockPos(8, 0, 0),
                    Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air")), EntityFilter.DECORATIONS),
                    RunOptions.DEFAULT, new RecordingListener())) == RejectReason.UNLOADED, "a move with entities");
            copyInto(blocksOnly, clips, h.player, source, source.min(), EntityFilter.NONE);
        }
        Captured<ClipboardService.ClipboardInfo> later = new Captured<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(() -> {
                    check(checked[0], "the entities were loaded with the chunks: nothing was refused");
                    check(blocksOnly.get("the copy without entities").entities() == 0, "no entities");
                })
                .createAndAdd(once(() -> {
                    world.spawnEntity(load(world, "minecraft:armor_stand", x0 + 3.5, y0, z0 + 3.5, 0f,
                            nbt -> nbt.putBoolean("NoGravity", true)));
                    copyInto(later, clips, h.player, source, source.min(), EntityFilter.DECORATIONS);
                }))
                .createAndAdd(() -> {
                    check(later.get("copy").entities() == 1, "the loaded stand is copied");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }


    /**
     * Entities follow the blocks' protection. The border cuts a chunk (columns from x0 + 12 are outside it) for one
     * synchronous step; a command block minecart stands outside it. A non-op builder may not copy or stack it; a non-op
     * with {@code limit.bypass} may stack it, but the entities it captured are then untrusted: the minecart arrives
     * without its command. The same stack from an unprotected source keeps it.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_protect", tickLimit = LIMIT)
    public void protectedEntitiesAreRefusedOrArriveUntrusted(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        ServerPlayerEntity builder = h.addPlayer(false);
        EditTestSupport.grant(builder, Perm.USE, Perm.CLIPBOARD, Perm.REGION);
        ServerPlayerEntity bypasser = h.addPlayer(false);
        EditTestSupport.grant(bypasser, Perm.USE, Perm.CLIPBOARD, Perm.REGION, Perm.LIMIT_BYPASS);
        int[] at = regionCorner(context, 647);
        int x0 = at[0], z0 = at[1];
        Box all = box(x0 - 16, 99, z0, x0 + 15, 104, z0 + 31);
        loadAndForce(world, all);
        Box source = box(x0 + 8, 100, z0 + 2, x0 + 15, 102, z0 + 9);
        Box untrustedCopy = source.offset(-16, 0, 0);
        Box trustedCopy = source.offset(0, 0, 16);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        RecordingListener untrusted = new RecordingListener();
        RecordingListener trusted = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    Entity cart = load(world, "minecraft:command_block_minecart", x0 + 13.5, 101, z0 + 5.5, 0f, nbt -> {
                        nbt.putString("Command", "op me");
                        nbt.putBoolean("NoGravity", true);
                    });
                    check(world.spawnEntity(cart), "the minecart spawned");
                    WorldBorder border = world.getWorldBorder();
                    double centerX = border.getCenterX(), centerZ = border.getCenterZ(), size = border.getSize();
                    try {
                        border.setCenter(x0 + 12 - 100_000, z0);
                        border.setSize(200_000);
                        check(!world.canPlayerModifyAt(builder, cart.getBlockPos()), "the minecart is not protected");
                        check(refusal(() -> clips.copy(builder, new Region.Cuboid(source), source.min(), false,
                                CellMask.ANY, EntityFilter.DECORATIONS, null, new Captured<>())) == RejectReason.PROTECTED,
                                "a copy of the protected minecart");
                        check(refusal(() -> h.service.run(builder, new OpSpec.Stack(new Region.Cuboid(source), -16, 0, 0,
                                1, EntityFilter.DECORATIONS), RunOptions.DEFAULT, new RecordingListener()))
                                == RejectReason.PROTECTED, "a non-bypass stack of the protected minecart");
                        h.service.run(bypasser, new OpSpec.Stack(new Region.Cuboid(source), -16, 0, 0, 1,
                                EntityFilter.DECORATIONS), RunOptions.DEFAULT, untrusted);
                    } catch (EditRejected e) {
                        throw new GameTestException("unexpected refusal: " + e.getMessage());
                    } finally {
                        border.setCenter(centerX, centerZ);
                        border.setSize(size);
                    }
                }))
                .createAndAdd(() -> check(untrusted.result != null, "stack running"))
                .createAndAdd(once(() -> {
                    check(untrusted.result.outcome() == JobOutcome.COMPLETED && untrusted.result.strippedNbt() == 1,
                            "untrusted stack " + untrusted.result);
                    check(command(world, untrustedCopy).isEmpty(), "a bypass stack from a protected source kept the command");
                    check(command(world, source).equals("op me"), "the source minecart changed");
                    try {
                        h.service.run(bypasser, new OpSpec.Stack(new Region.Cuboid(source), 0, 0, 16, 1,
                                EntityFilter.DECORATIONS), RunOptions.DEFAULT, trusted);
                    } catch (EditRejected e) {
                        throw new GameTestException("control stack refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(trusted.result != null, "control stack running"))
                .createAndAdd(() -> {
                    check(trusted.result.strippedNbt() == 0, "control stack " + trusted.result);
                    check(command(world, trustedCopy).equals("op me"), "a stack from a writable source lost the command");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /** The command of the one command block minecart belonging to {@code area}. */
    private static String command(ServerWorld world, Box area) {
        List<Entity> carts = entitiesIn(world, area).stream()
                .filter(e -> e instanceof CommandBlockMinecartEntity).toList();
        check(carts.size() == 1, "command block minecarts in " + area + ": " + carts);
        return ((CommandBlockMinecartEntity) carts.get(0)).getCommandExecutor().getCommand();
    }

    /**
     * Schematic files carry entities. Our own export of copied decorations reads back with its four entities and pastes
     * them where they were copied from. A WorldEdit-style version 3 file from 1.20.4 (older item format) gives an armor
     * stand (named, turned, its helmet upgraded by the game's data fixer) and a command block minecart whose command is
     * removed on import, for operators too; an entity of an unknown type and one outside the box are skipped and
     * reported.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_schem", tickLimit = LIMIT)
    public void schematicsCarryEntities(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 649);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0, y0, z0, x0 + 3, y0 + 2, z0 + 3);
        BlockPos roundTrip = new BlockPos(x0 + 8, y0, z0);
        BlockPos weTarget = new BlockPos(x0 + 16, y0, z0 + 8);
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 32, y0 + 8, z0 + 24);
        loadAndForce(world, all);
        java.nio.file.Path root = ClipTestSupport.libraryRoot(context);
        ServerClipboards clips = ClipTestSupport.clipboards(h, root);
        List<Entity> fixture = new ArrayList<>();
        Captured<ClipboardService.ClipboardInfo> copied = new Captured<>();
        Captured<ClipboardService.Outbound> exported = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> uploaded = new Captured<>();
        Captured<ClipboardService.ClipboardInfo> worldEdit = new Captured<>();
        RecordingListener pasteBack = new RecordingListener();
        RecordingListener pasteWorldEdit = new RecordingListener();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    fixture.addAll(decorate(h, x0, y0, z0));
                    copyInto(copied, clips, h.player, source, source.min(), EntityFilter.DECORATIONS);
                }))
                .createAndAdd(() -> check(copied.finished(), "copy running"))
                .createAndAdd(once(() -> {
                    check(copied.get("copy").entities() == 4, "copied " + copied.value.entities());
                    try {
                        clips.export(h.player, copied.value.clipboardId(), exported);
                    } catch (EditRejected e) {
                        throw new GameTestException("export refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(exported.finished(), "export running"))
                .createAndAdd(once(() -> {
                    byte[] bytes = exported.get("export").payload();
                    try {
                        dev.sculptory.core.nbt.NbtCompound file = dev.sculptory.core.nbt.NbtIo.readAuto(
                                new java.io.ByteArrayInputStream(bytes), dev.sculptory.core.nbt.NbtLimits.DEFAULT)
                                .value();
                        dev.sculptory.core.nbt.NbtList entities = file.getCompound("Schematic").getList("Entities");
                        check(entities != null && entities.size() == 4, "the file's Entities: " + entities);
                        dev.sculptory.core.schem.Schematic read = dev.sculptory.core.schem.SchematicCodec.read(
                                new java.io.ByteArrayInputStream(bytes), h.runtime.states(),
                                dev.sculptory.core.schem.SchematicCodec.Limits.DEFAULT,
                                dev.sculptory.fabric.schem.FabricDataFixHook.get());
                        check(read.report().entities() == 4 && read.report().entitiesSkipped() == 0,
                                "report " + read.report());
                    } catch (IOException e) {
                        throw new GameTestException("cannot read our own file: " + e);
                    }
                    ClipTestSupport.upload(clips, h.player, "entities.schem", bytes, uploaded);
                }))
                .createAndAdd(() -> check(uploaded.finished(), "upload running"))
                .createAndAdd(once(() -> {
                    ClipboardService.ClipboardInfo info = uploaded.get("upload");
                    check(info.entities() == 4 && info.notices().isEmpty(), "uploaded " + info);
                    paste(h, info.clipboardId(), roundTrip, Transform.IDENTITY, pasteBack);
                }))
                .createAndAdd(() -> check(pasteBack.result != null, "paste running"))
                .createAndAdd(once(() -> {
                    check(pasteBack.result.outcome() == JobOutcome.COMPLETED, "paste " + pasteBack.result);
                    for (Entity entity : fixture) {
                        checkPlaced(world, expected(entity, source, Transform.IDENTITY, roundTrip), "from the file");
                    }
                    ClipTestSupport.upload(clips, h.player, "worldedit.schem", worldEditFile(), worldEdit);
                }))
                .createAndAdd(() -> check(worldEdit.finished(), "upload running"))
                .createAndAdd(once(() -> {
                    ClipboardService.ClipboardInfo info = worldEdit.get("the WorldEdit file");
                    check(info.entities() == 2, "entities read: " + info.entities());
                    List<String> keys = info.notices().stream().map(n -> n.key() + n.args()).toList();
                    check(keys.contains(ServerClipboards.NOTICE_IMPORT_ENTITY_DATA + "[1]")
                            && keys.contains("sculptory.notice.import_entities_skipped[2]"), "notices " + keys);
                    paste(h, info.clipboardId(), weTarget, Transform.IDENTITY, pasteWorldEdit);
                }))
                .createAndAdd(() -> check(pasteWorldEdit.result != null, "paste running"))
                .createAndAdd(() -> {
                    check(pasteWorldEdit.result.outcome() == JobOutcome.COMPLETED, "paste " + pasteWorldEdit.result);
                    Entity stand = checkPlaced(world, new Expected(EntityType.ARMOR_STAND,
                            new Vec3d(weTarget.x() + 1.5, weTarget.y() + 1, weTarget.z() + 1.5), null, 90f), "stand");
                    check(stand.getCustomName() != null && stand.getCustomName().getString().equals("Steve"),
                            "the stand's name " + stand.getCustomName());
                    ItemStack helmet = ((ArmorStandEntity) stand).getEquippedStack(EquipmentSlot.HEAD);
                    check(helmet.isOf(Items.IRON_HELMET), "the stand's helmet " + helmet);
                    Box weBox = box(weTarget.x(), weTarget.y(), weTarget.z(), weTarget.x() + 2, weTarget.y() + 2,
                            weTarget.z() + 2);
                    check(command(world, weBox).isEmpty(), "the file's command survived");
                    check(entitiesIn(world, weBox).size() == 2, "entities pasted " + entitiesIn(world, weBox));
                    forceChunks(world, all, false);
                    ClipTestSupport.deleteTree(root.getParent());
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * A 3×3×3 Sponge version 3 file as WorldEdit writes it from 1.20.4 (data version 3700): a stone floor and, in its
     * {@code Entities}, entries of {@code {Pos (relative to the minimum corner), Id, Data}} whose data still holds the
     * world position, UUID and 1.20.4's item format.
     */
    static byte[] worldEditFile() {
        NbtCompound palette = new NbtCompound();
        palette.putInt("minecraft:air", 0);
        palette.putInt("minecraft:stone", 1);
        byte[] data = new byte[27];
        for (int i = 0; i < 9; i++) data[i] = 1;        // y = 0: index = x + z * 3 + y * 9
        NbtCompound blocks = new NbtCompound();
        blocks.put("Palette", palette);
        blocks.putByteArray("Data", data);
        blocks.put("BlockEntities", new NbtList());

        NbtCompound helmet = new NbtCompound();
        helmet.putString("id", "minecraft:iron_helmet");
        helmet.putByte("Count", (byte) 1);
        NbtList armor = new NbtList();
        for (int i = 0; i < 3; i++) armor.add(new NbtCompound());
        armor.add(helmet);
        NbtCompound standData = new NbtCompound();
        standData.putString("id", "minecraft:armor_stand");
        standData.put("Pos", doubles(1001.5, 64, -2998.5));
        standData.putIntArray("UUID", new int[] {1, 2, 3, 4});
        standData.put("Rotation", floats(90f, 0f));
        standData.putString("CustomName", "{\"text\":\"Steve\"}");
        standData.put("ArmorItems", armor);
        NbtCompound cartData = new NbtCompound();
        cartData.putString("id", "minecraft:command_block_minecart");
        cartData.putString("Command", "op @a");
        cartData.putBoolean("NoGravity", true);
        cartData.put("Rotation", floats(0f, 0f));

        NbtList entities = new NbtList();
        entities.add(entry("minecraft:armor_stand", doubles(1.5, 1, 1.5), standData));
        entities.add(entry("minecraft:command_block_minecart", doubles(0.5, 1, 0.5), cartData));
        entities.add(entry("minecraft:no_such_thing", doubles(1.5, 1, 1.5), new NbtCompound()));
        entities.add(entry("minecraft:armor_stand", doubles(5.5, 1, 1.5), new NbtCompound()));

        NbtCompound schematic = new NbtCompound();
        schematic.putInt("Version", 3);
        schematic.putInt("DataVersion", 3700);
        schematic.putShort("Width", (short) 3);
        schematic.putShort("Height", (short) 3);
        schematic.putShort("Length", (short) 3);
        schematic.putIntArray("Offset", new int[] {0, 0, 0});
        schematic.put("Blocks", blocks);
        schematic.put("Entities", entities);
        NbtCompound rootTag = new NbtCompound();
        rootTag.put("Schematic", schematic);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try {
            net.minecraft.nbt.NbtIo.writeCompressed(rootTag, out);
        } catch (IOException e) {
            throw new GameTestException("cannot write the file: " + e);
        }
        return out.toByteArray();
    }

    private static NbtCompound entry(String id, NbtList pos, NbtCompound data) {
        NbtCompound entry = new NbtCompound();
        entry.putString("Id", id);
        entry.put("Pos", pos);
        entry.put("Data", data);
        return entry;
    }

    private static NbtList doubles(double x, double y, double z) {
        NbtList list = new NbtList();
        list.add(NbtDouble.of(x));
        list.add(NbtDouble.of(y));
        list.add(NbtDouble.of(z));
        return list;
    }

    private static NbtList floats(float a, float b) {
        NbtList list = new NbtList();
        list.add(NbtFloat.of(a));
        list.add(NbtFloat.of(b));
        return list;
    }


    /**
     * The parts of a multipart entity are not entities of their own, and bosses are never taken: a copy around an ender
     * dragon takes none of its eight parts (which the world's entity queries return, with the dragon's type) and not
     * the dragon, whatever the filter.
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_dragon", tickLimit = LIMIT)
    public void dragonPartsAreNeverTaken(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 651);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0 - 6, y0 - 4, z0 - 6, x0 + 6, y0 + 6, z0 + 6);
        Box all = box(x0 - 32, y0 - 8, z0 - 32, x0 + 32, y0 + 16, z0 + 32);
        loadAndForce(world, all);
        ServerClipboards clips = ClipTestSupport.clipboards(h, ClipTestSupport.libraryRoot(context));
        Map<EntityFilter, Captured<ClipboardService.ClipboardInfo>> copies = new java.util.EnumMap<>(EntityFilter.class);
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    Entity dragon = load(world, "minecraft:ender_dragon", x0 + 0.5, y0, z0 + 0.5, 0f,
                            nbt -> nbt.putBoolean("NoAI", true));
                    check(world.spawnEntity(dragon), "the dragon spawned");
                }))
                .createAndAdd(() -> {
                    net.minecraft.util.math.Box near = new net.minecraft.util.math.Box(x0 - 6, y0 - 4, z0 - 6, x0 + 7,
                            y0 + 7, z0 + 7);
                    check(!world.getOtherEntities(null, near, e -> e instanceof EnderDragonPart).isEmpty(),
                            "the world returns the dragon's parts");
                    copy(copies, clips, h, source, EntityFilter.DECORATIONS);
                })
                .createAndAdd(() -> {
                    int taken = copies.get(EntityFilter.DECORATIONS).get("DECORATIONS").entities();
                    check(taken == 0, "DECORATIONS took " + taken + " (dragon parts?)");
                    copy(copies, clips, h, source, EntityFilter.ALL);
                })
                .createAndAdd(() -> {
                    int taken = copies.get(EntityFilter.ALL).get("ALL").entities();
                    check(taken == 0, "ALL took " + taken + ": the ender dragon is never taken");
                    world.getEntitiesByType(EntityType.ENDER_DRAGON, e -> true).forEach(Entity::discard);
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    /**
     * An entity of no size (a block display) standing exactly on a chunk's edge is found like any other: a move takes it
     * along, a stack copies it. (Entity boxes touch a query box on its edge without intersecting it.)
     */
    @GameTest(templateName = EMPTY_STRUCTURE, batchId = "sculptory_entities_edge", tickLimit = LIMIT)
    public void entitiesWithoutSizeOnAChunkEdgeAreTaken(TestContext context) {
        Harness h = new Harness(context);
        ServerWorld world = h.world;
        int[] at = regionCorner(context, 652);
        int x0 = at[0], y0 = 100, z0 = at[1];
        Box source = box(x0 + 12, y0, z0 + 2, x0 + 19, y0 + 2, z0 + 5); // across the edge at x0 + 16
        Box all = box(x0 - 16, y0 - 2, z0 - 16, x0 + 80, y0 + 8, z0 + 16);
        loadAndForce(world, all);
        RecordingListener stack = new RecordingListener();
        RecordingListener move = new RecordingListener();
        List<Entity> displays = new ArrayList<>();
        context.createTimedTaskRunner()
                .createAndAdd(() -> ready(world, all))
                .createAndAdd(once(() -> {
                    Entity display = load(world, "minecraft:block_display", x0 + 16, y0 + 1, z0 + 3, 0f,
                            nbt -> nbt.put("block_state", compound("Name", "minecraft:stone")));
                    check(world.spawnEntity(display), "the display spawned");
                    check(display.getWidth() == 0f, "a display has no size");
                    displays.add(display);
                    try {
                        h.service.run(h.player, new OpSpec.Stack(new Region.Cuboid(source), 0, 0, 8, 1,
                                EntityFilter.DECORATIONS), RunOptions.DEFAULT, stack);
                    } catch (EditRejected e) {
                        throw new GameTestException("stack refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(stack.result != null, "stack running"))
                .createAndAdd(once(() -> {
                    check(stack.result.outcome() == JobOutcome.COMPLETED, "stack " + stack.result);
                    checkPlaced(world, new Expected(EntityType.BLOCK_DISPLAY, new Vec3d(x0 + 16, y0 + 1, z0 + 11), null,
                            0f), "the stacked copy");
                    try {
                        h.service.run(h.player, new OpSpec.Move(new Region.Cuboid(source), new BlockPos(32, 0, 0),
                                Transform.IDENTITY, new Pattern.Single(h.state("minecraft:air")),
                                EntityFilter.DECORATIONS), RunOptions.DEFAULT, move);
                    } catch (EditRejected e) {
                        throw new GameTestException("move refused: " + e.getMessage());
                    }
                }))
                .createAndAdd(() -> check(move.result != null, "move running"))
                .createAndAdd(() -> {
                    check(move.result.outcome() == JobOutcome.COMPLETED, "move " + move.result);
                    check(displays.get(0).isRemoved(), "the display was left behind");
                    checkPlaced(world, new Expected(EntityType.BLOCK_DISPLAY, new Vec3d(x0 + 48, y0 + 1, z0 + 3), null,
                            0f), "the moved display");
                    forceChunks(world, all, false);
                    h.close();
                })
                .completeIfSuccessful();
    }

    static RejectReason refusal(ThrowingRun run) {
        try {
            run.run();
        } catch (EditRejected e) {
            return e.reason();
        }
        throw new GameTestException("expected a refusal");
    }

    @FunctionalInterface
    interface ThrowingRun {
        void run() throws EditRejected;
    }
}
