package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.entity.EntityFilter;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.history.EditRecord;
import dev.sculptory.core.history.EntityChange;
import dev.sculptory.core.history.EntityState;
import dev.sculptory.core.history.HistoryEntry;
import dev.sculptory.core.history.RecordBuilder;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.tinker.EntityEdit;
import dev.sculptory.core.tinker.EntityEdits;
import dev.sculptory.core.tinker.EntityView;
import dev.sculptory.core.tinker.SignText;
import dev.sculptory.core.tinker.TinkerKind;
import dev.sculptory.core.tinker.TinkerProperties;
import dev.sculptory.fabric.perm.FabricPermissionService;
import dev.sculptory.fabric.world.BlockWriter;
import dev.sculptory.fabric.world.EntityTypeRules;
import dev.sculptory.fabric.world.EntityWriter;
import dev.sculptory.fabric.world.FabricEntities;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.fabric.world.FabricTile;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.TinkerService;
import dev.sculptory.server.engine.impl.HistoryService;
import dev.sculptory.server.engine.impl.RecordSink;
import dev.sculptory.server.platform.EntityPlacer;
import dev.sculptory.server.platform.WriteOptions;
import dev.sculptory.server.schem.TileSanitizer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.BlockAttachedEntity;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.DyeColor;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The server side of Tinker, for {@link EngineEditService}, which implements
 * {@link TinkerService} with it. Server thread only.
 *
 * <p>Each change is written at once, in the request's own tick (it is one block, its other half, or one entity), and
 * pushed as one history entry through {@link HistoryService#push}, which journals it and defers it behind an undo or
 * redo in flight like any other push. Blocks go through {@link BlockWriter} with physics off, recorded like a job's
 * cells (so undo and redo compare state and block-entity contents) and marked for fluid trails (a waterlogged
 * stair's water that later flows is taken back with the step). Entities are changed the way undo puts them back: the
 * live entity is removed and the edited one loaded with the same UUID ({@link EntityWriter#remove},
 * {@link EntityWriter#restore}), so every client sees the new entity at once, whatever it is; the step records the
 * entity before and after ({@link EntityChange}), and its undo compares where it stands too when that is all it changed
 * ({@code EntityMatcher.placementAware}).
 */
final class TinkerEdits implements TinkerService<ServerPlayerEntity> {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** Longest detail a refusal carries (the protocol's free-text cap is 1 KiB). */
    private static final int MAX_DETAIL_CHARS = 300;

    private final EngineEditService service;

    TinkerEdits(EngineEditService service) {
        this.service = Objects.requireNonNull(service);
    }

    // =================================================================== blocks

    @Override
    public void block(ServerPlayerEntity player, dev.sculptory.core.BlockPos at, int expected, int target,
                      SignText sign) throws EditRejected {
        checkThread();
        Objects.requireNonNull(at);
        FabricStateSpace states = service.runtime().states();
        requireAllowed(player);
        if (expected < 0 || expected >= states.size() || target < 0 || target >= states.size()) {
            throw new EditRejected(RejectReason.INVALID, "unknown block state");
        }
        if (!states.blockId(expected).equals(states.blockId(target))) {
            throw new EditRejected(RejectReason.INVALID, "Tinker changes a block's properties, not the block");
        }
        if (expected == target && sign == null) throw new EditRejected(RejectReason.INVALID, "nothing to change");
        ServerWorld world = player.getServerWorld();
        BlockPos pos = new BlockPos(at.x(), at.y(), at.z());
        requireWritableCell(player, world, pos);
        requireUnlocked(player, world, pos);
        int live = states.handle(world.getBlockState(pos));
        if (live != expected) {
            throw new EditRejected(RejectReason.INVALID, "the block changed meanwhile ("
                    + clip(live < 0 ? "an unknown state" : states.format(live)) + ")");
        }
        RegistryWrapper.WrapperLookup registries = world.getRegistryManager();
        FabricPermissionService permissions = service.runtime().permissions();
        boolean operatorNbt = permissions.mayWriteOperatorNbt(player);
        BlockEntityData tile = null;
        boolean textChanged = false;
        BlockEntity entity = world.getWorldChunk(pos).getBlockEntity(pos, WorldChunk.CreationType.CHECK);
        if (sign != null) {
            if (!(entity instanceof SignBlockEntity signEntity) || !isSign(states, signEntity)) {
                throw new EditRejected(RejectReason.INVALID, "the block has no sign text");
            }
            NbtCompound changed = signNbt(signEntity, sign, registries);
            if (changed != null) {
                textChanged = true;
                String typeId = FabricTile.capture(signEntity, registries).typeId();
                // The text is plain (literal components); the sign is cleaned as signs from files are, so nothing on it
                // runs a command for a player who may not write operator data. Operators keep the side they left.
                tile = operatorNbt ? FabricTile.of(typeId, changed)
                        : TileSanitizer.sanitizedSign(typeId, FabricTile.of(typeId, changed).nbtBytes());
            }
        }
        if (tile == null && entity != null && !entity.isRemoved()) tile = FabricTile.capture(entity, registries);
        if (expected == target && !textChanged) return; // the sign already reads so: nothing to do

        List<String> changedProperties = changedProperties(states, expected, target);
        // A bed's other half lies in its facing direction: turning one half alone leaves a broken bed, so it is refused
        // (move the bed instead). Other two-part blocks (double chests, pistons) are set one at a time (documented).
        if (changedProperties.contains("facing") && states.describe(expected).get("part") != null) {
            throw new EditRejected(RejectReason.INVALID, "a bed's facing cannot be changed in place (move it instead)");
        }

        // A door's or tall plant's other half follows the change: every changed property but the half itself.
        List<String> shared = changedProperties.stream().filter(property -> !property.equals("half")).toList();
        BlockPos partnerPos = null;
        int partnerTarget = -1;
        BlockEntityData partnerTile = null;
        if (!shared.isEmpty()) {
            int flags = states.flags(expected);
            BlockPos other = StateFlags.has(flags, StateFlags.LOWER_HALF) ? pos.up()
                    : StateFlags.has(flags, StateFlags.UPPER_HALF) ? pos.down() : null;
            if (other != null && WorldChecks.inBuildLimit(world, other.getX(), other.getY(), other.getZ())) {
                int partner = states.handle(world.getBlockState(other));
                if (partner >= 0 && partner != expected && states.blockId(partner).equals(states.blockId(expected))
                        && StateFlags.has(states.flags(partner), StateFlags.LOWER_HALF | StateFlags.UPPER_HALF)) {
                    int changedPartner = partner;
                    for (String property : shared) {
                        int next = states.withProperty(changedPartner, property, states.describe(target).get(property));
                        if (next >= 0) changedPartner = next;
                    }
                    if (changedPartner != partner) {
                        requireUnlocked(player, world, other);
                        partnerPos = other;
                        partnerTarget = changedPartner;
                        BlockEntity partnerEntity = world.getWorldChunk(other).getBlockEntity(other,
                                WorldChunk.CreationType.CHECK);
                        if (partnerEntity != null && !partnerEntity.isRemoved()) {
                            partnerTile = FabricTile.capture(partnerEntity, registries);
                        }
                    }
                }
            }
        }

        // The open brush stroke is older than this change: it goes into history first.
        service.commitStroke(player.getUuid());
        RecordBuilder builder = new RecordBuilder();
        RecordSink sink = service.runtime().fluidTrails().marking(RecordSink.into(builder), world, builder.id());
        BlockWriter writer = service.runtime().writer(world, new WriteOptions(false, operatorNbt));
        final int want = expected;
        writer.write(pos.getX(), pos.getY(), pos.getZ(), target, tile, sink, (state, liveTile) -> state == want);
        if (partnerPos != null) {
            final int partnerBefore = states.handle(world.getBlockState(partnerPos));
            writer.write(partnerPos.getX(), partnerPos.getY(), partnerPos.getZ(), partnerTarget, partnerTile, sink,
                    (state, liveTile) -> state == partnerBefore);
        }
        writer.clearTicksAtWrittenCells();
        EditRecord record = builder.build();
        if (record.isEmpty()) return;
        String label = "Tinker · " + blockName(states.state(target)) + " · "
                + blockLabel(states, expected, target, changedProperties, textChanged);
        push(player, builder.id(), world, label, record);
    }

    /**
     * The sign's NBT with the sides of {@code text} that differ from the live sign (plain lines, colour, glow) replaced
     * by literal text; {@code null} when both sides already read so.
     */
    private static NbtCompound signNbt(SignBlockEntity sign, SignText text, RegistryWrapper.WrapperLookup registries)
            throws EditRejected {
        NbtCompound nbt = sign.createNbtWithId(registries);
        boolean changed = false;
        for (boolean front : new boolean[] {true, false}) {
            net.minecraft.block.entity.SignText live = sign.getText(front);
            SignText.Side side = text.side(front);
            if (reads(live, side)) continue;
            net.minecraft.block.entity.SignText made = new net.minecraft.block.entity.SignText();
            for (int i = 0; i < SignText.LINES; i++) made = made.withMessage(i, Text.literal(side.lines().get(i)));
            made = made.withColor(DyeColor.byName(side.color(), DyeColor.BLACK)).withGlowing(side.glowing());
            NbtElement encoded = net.minecraft.block.entity.SignText.CODEC
                    .encodeStart(registries.getOps(NbtOps.INSTANCE), made).result().orElse(null);
            if (encoded == null) throw new EditRejected(RejectReason.INVALID, "the sign text could not be written");
            nbt.put(front ? "front_text" : "back_text", encoded);
            changed = true;
        }
        return changed ? nbt : null;
    }

    /** Whether a live side reads as {@code side}: the same plain lines, colour and glow. */
    private static boolean reads(net.minecraft.block.entity.SignText live, SignText.Side side) {
        for (int i = 0; i < SignText.LINES; i++) {
            if (!live.getMessage(i, false).getString().equals(side.lines().get(i))) return false;
        }
        return live.getColor().getName().equals(side.color()) && live.isGlowing() == side.glowing();
    }

    private static boolean isSign(FabricStateSpace states, SignBlockEntity sign) {
        Identifier type = net.minecraft.block.entity.BlockEntityType.getId(sign.getType());
        return type != null && (type.toString().equals("minecraft:sign") || type.toString().equals("minecraft:hanging_sign")
                || states.isSignBlockEntity(type.toString()));
    }

    private static List<String> changedProperties(FabricStateSpace states, int expected, int target) {
        BlockDescriptor before = states.describe(expected), after = states.describe(target);
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> entry : after.properties().entrySet()) {
            if (!entry.getValue().equals(before.get(entry.getKey()))) changed.add(entry.getKey());
        }
        return TinkerProperties.order(changed);
    }

    /** "shape: outer left", "2 properties", "text", "text and 1 property". */
    private static String blockLabel(FabricStateSpace states, int expected, int target, List<String> changed,
                                     boolean text) {
        String properties = changed.size() == 1
                ? TinkerProperties.shown(changed.get(0), states.describe(target).get(changed.get(0)))
                : changed.size() + " properties";
        if (!text) return properties;
        if (changed.isEmpty()) return "text";
        return "text and " + (changed.size() == 1 ? "1 property" : changed.size() + " properties");
    }

    // =================================================================== entities

    @Override
    public EntityView entity(ServerPlayerEntity player, UUID id, List<EntityEdit> edits) throws EditRejected {
        checkThread();
        Objects.requireNonNull(id);
        Objects.requireNonNull(edits);
        requireAllowed(player);
        if (edits.size() > EntityEdits.MAX_EDITS) throw new EditRejected(RejectReason.TOO_LARGE, "too many edits");
        ServerWorld world = player.getServerWorld();
        Entity entity = FabricEntities.find(world, id);
        if (entity == null || entity.isRemoved()) {
            throw new EditRejected(RejectReason.INVALID, "the entity is gone (removed or out of reach)");
        }
        String typeId = FabricEntities.typeId(entity);
        TinkerKind kind = TinkerKind.of(typeId).orElseThrow(() ->
                new EditRejected(RejectReason.INVALID, "Tinker does not change a " + typeId));
        EntityTypeRules rules = EntityTypeRules.scan(world);
        if (rules.operator(typeId)) throw new EditRejected(RejectReason.NO_PERMISSION, "operator-only entity data");
        if (entity.hasVehicle()) throw new EditRejected(RejectReason.INVALID, "it rides another entity");
        requireWritableCell(player, world, FabricEntities.cell(entity));
        requireUnlocked(player, world, FabricEntities.cell(entity));
        EntityState before = EntityWriter.live(entity);
        if (before == null) throw new EditRejected(RejectReason.INVALID, "the entity's data is too large");
        if (edits.isEmpty()) return view(kind, before, world);

        FabricStateSpace states = service.runtime().states();
        checkRegistries(world, edits);
        dev.sculptory.core.nbt.NbtCompound edited;
        try {
            edited = EntityEdits.apply(kind, EntityNbt.decode(before.nbt()), edits, states);
        } catch (IOException | IllegalArgumentException e) {
            throw new EditRejected(RejectReason.INVALID, clip(e.getMessage()));
        }
        double[] where = EntityNbt.position(edited);
        if (where == null) throw new EditRejected(RejectReason.INVALID, "the edited entity has no position");
        requireUnlocked(player, world, BlockPos.ofFloored(where[0], where[1], where[2]));
        EntityState target;
        try {
            target = new EntityState(typeId, where[0], where[1], where[2], NbtIo.toBytes(edited));
        } catch (IllegalArgumentException e) {
            throw new EditRejected(RejectReason.INVALID, clip(e.getMessage()));
        }
        if (!FabricEntities.restorable(target.nbt())) {
            throw new EditRejected(RejectReason.INVALID, "the edited entity's data is too large");
        }
        FabricPermissionService permissions = service.runtime().permissions();
        // Protection where the edited entity would stand, as for entities a job places; its chunk must be loaded.
        Predicate<Entity> allowed = placed -> {
            BlockPos cell = FabricEntities.cell(placed);
            if (!WorldChecks.editable(world, cell.getX(), cell.getY(), cell.getZ())) return false;
            if (!FabricEntities.loaded(world, cell.getX() >> 4, cell.getZ() >> 4)) return false;
            ChunkPermit permit = permissions.chunk(player, world, cell.getX() >> 4, cell.getZ() >> 4, box(cell));
            return permit != null && permit.allows(cell.getX(), cell.getZ());
        };

        service.commitStroke(player.getUuid());
        EntityWriter writer = new EntityWriter(world, new WriteOptions(false,
                permissions.mayWriteOperatorNbt(player)), rules);
        writer.remove(entity, before);
        EntityPlacer.Spawn<Entity> spawn = writer.restore(target, allowed);
        Entity placed = spawn.entity();
        String refusal = null;
        RejectReason reason = RejectReason.INVALID;
        if (placed == null) {
            refusal = spawn.refused() ? "protected, outside the world or not loaded where it would stand"
                    : "the edited entity could not be placed";
            if (spawn.refused()) reason = RejectReason.PROTECTED;
        } else if (placed instanceof BlockAttachedEntity hanging && !hanging.canStayAttached()) {
            refusal = "it would not hang there";
        }
        EntityState after = placed == null || refusal != null ? null : EntityWriter.live(placed);
        if (placed != null && refusal == null && after == null) refusal = "the edited entity's data is too large";
        if (refusal != null) {
            if (placed != null) {
                for (Entity rider : placed.getPassengersDeep()) {
                    if (!rider.isPlayer()) rider.discard();
                }
                placed.discard();
            }
            // The entity as it was, with its UUID: nothing changed.
            EntityPlacer.Spawn<Entity> back = writer.restore(before, e -> true);
            if (back.entity() == null) {
                LOG.error("Sculptory: a {} ({}) could not be put back after a refused Tinker edit", typeId, id);
            }
            throw new EditRejected(reason, refusal);
        }
        EditRecord record = new EditRecord(new dev.sculptory.core.buffer.BlockBuffer(),
                new dev.sculptory.core.buffer.BlockBuffer(), List.of(new EntityChange(id, before, after)));
        String label = "Tinker · " + placed.getName().getString() + " · " + entityLabel(edits);
        push(player, UUID.randomUUID(), world, label, record);
        return view(kind, after, world);
    }

    /** Painting variants and display items must be registered (air is no item to show). */
    private static void checkRegistries(ServerWorld world, List<EntityEdit> edits) throws EditRejected {
        for (EntityEdit edit : edits) {
            if (edit instanceof EntityEdit.PaintingVariant variant) {
                Identifier idOf = Identifier.tryParse(variant.id());
                if (idOf == null || !world.getRegistryManager().get(RegistryKeys.PAINTING_VARIANT).containsId(idOf)) {
                    throw new EditRejected(RejectReason.INVALID, "unknown painting " + clip(variant.id()));
                }
            } else if (edit instanceof EntityEdit.DisplayItem item) {
                Identifier idOf = Identifier.tryParse(item.itemId());
                if (idOf == null || !Registries.ITEM.containsId(idOf) || Registries.ITEM.get(idOf) == Items.AIR) {
                    throw new EditRejected(RejectReason.INVALID, "unknown item " + clip(item.itemId()));
                }
            }
        }
    }

    /** What the panel shows of an entity (a text display's text as plain text, read by the game). */
    private static EntityView view(TinkerKind kind, EntityState state, ServerWorld world) throws EditRejected {
        dev.sculptory.core.nbt.NbtCompound saved;
        try {
            saved = EntityNbt.decode(state.nbt());
        } catch (IOException e) {
            throw new EditRejected(RejectReason.INVALID, "the entity's data does not decode");
        }
        String text = "";
        String json = kind == TinkerKind.TEXT_DISPLAY ? saved.getString("text") : null;
        if (json != null) {
            try {
                Text parsed = Text.Serialization.fromJson(json, world.getRegistryManager());
                text = parsed == null ? "" : parsed.getString();
            } catch (RuntimeException unreadable) {
                text = "";
            }
        }
        return EntityView.fromSaved(kind, saved, blockStateText(saved), text);
    }

    /** A block display's {@code block_state} compound as state text, or "". */
    private static String blockStateText(dev.sculptory.core.nbt.NbtCompound saved) {
        dev.sculptory.core.nbt.NbtCompound block = saved.getCompound("block_state");
        String name = block == null ? null : block.getString("Name");
        if (name == null) return "";
        try {
            java.util.TreeMap<String, String> properties = new java.util.TreeMap<>();
            dev.sculptory.core.nbt.NbtCompound values = block.getCompound("Properties");
            if (values != null) {
                for (String key : values.keys()) {
                    String value = values.getString(key);
                    if (value != null) properties.put(key, value);
                }
            }
            return BlockDescriptor.of(new dev.sculptory.core.NamespacedId(name), properties).format();
        } catch (IllegalArgumentException malformed) {
            return "";
        }
    }

    /** "pose", "moved", "turned", "2 settings"... */
    static String entityLabel(List<EntityEdit> edits) {
        if (edits.size() != 1) return edits.size() + " settings";
        return switch (edits.get(0)) {
            case EntityEdit.Pose pose -> "pose";
            case EntityEdit.Toggle toggle -> TinkerProperties.shown(toggle.flag().name()) + (toggle.on() ? " on" : " off");
            case EntityEdit.Position position -> "moved";
            case EntityEdit.Yaw yaw -> "turned";
            case EntityEdit.ItemRotation rotation -> "item turned";
            case EntityEdit.PaintingVariant variant -> "picture";
            case EntityEdit.Transformation transformation -> "transformation";
            case EntityEdit.BillboardMode billboard -> "billboard";
            case EntityEdit.Brightness brightness -> "brightness";
            case EntityEdit.DisplayBlock block -> "block";
            case EntityEdit.DisplayItem item -> "item";
            case EntityEdit.DisplayText text -> "text";
        };
    }

    // =================================================================== shared

    private void push(ServerPlayerEntity player, UUID entryId, ServerWorld world, String label, EditRecord record) {
        HistoryService history = service.historyService();
        history.push(history.session(player.getUuid()), new HistoryEntry(entryId, player.getUuid(),
                EngineEditService.worldId(world), label, record, service.createdMillis()));
    }

    /** {@code use} and {@code region}, with editing enabled. */
    private void requireAllowed(ServerPlayerEntity player) throws EditRejected {
        if (!service.runtime().config().editingEnabled) throw new EditRejected(RejectReason.DISABLED);
        FabricPermissionService permissions = service.runtime().permissions();
        for (Perm node : new Perm[] {Perm.USE, Perm.REGION}) {
            if (!permissions.has(player, node)) throw new EditRejected(RejectReason.NO_PERMISSION, node.node());
        }
    }

    /** Inside the world and its border, loaded, and the player's to change. */
    private void requireWritableCell(ServerPlayerEntity player, ServerWorld world, BlockPos pos) throws EditRejected {
        if (!WorldChecks.inBuildLimit(world, pos.getX(), pos.getY(), pos.getZ())
                || !WorldChecks.insideBorder(world.getWorldBorder(), pos.getX(), pos.getZ())) {
            throw new EditRejected(RejectReason.INVALID, "outside the world");
        }
        if (!WorldChecks.isChunkLoaded(world, pos.getX() >> 4, pos.getZ() >> 4)) {
            throw new EditRejected(RejectReason.UNLOADED, "the chunk is not loaded");
        }
        ChunkPermit permit = service.runtime().permissions().chunk(player, world, pos.getX() >> 4, pos.getZ() >> 4,
                box(pos));
        if (permit == null || !permit.allows(pos.getX(), pos.getZ())) {
            throw new EditRejected(RejectReason.PROTECTED, "you may not change blocks here");
        }
    }

    /** No admitted job (a fill, a paste, an undo) holds the section of {@code pos}. */
    private void requireUnlocked(ServerPlayerEntity player, ServerWorld world, BlockPos pos) throws EditRejected {
        if (service.executor().isLockedFor(world, box(pos), player.getUuid())) {
            throw new EditRejected(RejectReason.AREA_BUSY, "a running edit holds this area");
        }
    }

    private static Box box(BlockPos pos) {
        dev.sculptory.core.BlockPos cell = new dev.sculptory.core.BlockPos(pos.getX(), pos.getY(), pos.getZ());
        return new Box(cell, cell);
    }

    private static String blockName(BlockState state) {
        return state.getBlock().getName().getString();
    }

    private void checkThread() {
        if (!service.server().isOnThread()) throw new IllegalStateException("Tinker must be used on the server thread");
    }

    private static String clip(String text) {
        if (text == null) return "";
        return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "...";
    }
}
