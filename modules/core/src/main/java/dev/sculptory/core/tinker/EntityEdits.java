package dev.sculptory.core.tinker;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.state.StateSpace;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes {@link EntityEdit}s into an entity's NBT, as the game saves it ({@code Entity.saveSelfNbt}; key names checked
 * with javap against 1.21.1's {@code ArmorStandEntity}, {@code ItemFrameEntity}, {@code PaintingEntity},
 * {@code BlockAttachedEntity}, {@code DisplayEntity} and {@code AffineTransformation}). The server loads the result
 * as the edited entity. Pure; refusals are {@link IllegalArgumentException}s.
 */
public final class EntityEdits {
    /** Most blocks one edit may move an entity from where it stands (the panel moves by 1/16 or 1). */
    public static final double MAX_MOVE = 16;
    /** Most edits in one request. */
    public static final int MAX_EDITS = 32;

    private EntityEdits() {}

    /**
     * {@code entity} (the whole compound, with {@code Pos}) with {@code edits} applied in order.
     *
     * @param states the space {@link EntityEdit.DisplayBlock} handles belong to
     * @throws IllegalArgumentException when an edit does not apply to {@code kind}, a move goes further than
     *     {@link #MAX_MOVE} or moves a hanging entity by part of a block, or the compound has no position
     */
    public static NbtCompound apply(TinkerKind kind, NbtCompound entity, List<EntityEdit> edits, StateSpace states) {
        Objects.requireNonNull(kind);
        Objects.requireNonNull(entity);
        Objects.requireNonNull(edits);
        if (edits.size() > MAX_EDITS) throw new IllegalArgumentException("More than " + MAX_EDITS + " edits");
        NbtCompound current = entity;
        for (EntityEdit edit : edits) {
            if (!edit.appliesTo(kind)) {
                throw new IllegalArgumentException(edit.getClass().getSimpleName() + " does not apply to " + kind);
            }
            current = apply(kind, current, edit, states);
        }
        return current;
    }

    private static NbtCompound apply(TinkerKind kind, NbtCompound entity, EntityEdit edit, StateSpace states) {
        NbtCompound.Builder out = entity.toBuilder();
        switch (edit) {
            case EntityEdit.Pose pose -> {
                NbtCompound parts = entity.getCompound("Pose");
                NbtCompound.Builder poses = parts == null ? NbtCompound.builder() : parts.toBuilder();
                poses.put(pose.part().nbtKey(), floats(pose.x(), pose.y(), pose.z()));
                out.put("Pose", poses.build());
            }
            case EntityEdit.Toggle toggle -> out.putByte(toggle.flag().nbtKey(), (byte) (toggle.on() ? 1 : 0));
            case EntityEdit.Position position -> move(kind, entity, out, position);
            case EntityEdit.Yaw yaw -> {
                float[] rotation = EntityNbt.rotation(entity);
                float pitch = rotation == null ? 0 : rotation[1];
                out.put("Rotation", NbtList.of(NbtTag.FLOAT, List.of(new NbtTag.NbtFloat(wrapDegrees(yaw.degrees())),
                        new NbtTag.NbtFloat(pitch))));
            }
            case EntityEdit.ItemRotation rotation -> out.putByte("ItemRotation", (byte) rotation.steps());
            case EntityEdit.PaintingVariant variant -> out.putString("variant", variant.id());
            case EntityEdit.Transformation t -> {
                NbtCompound old = entity.getCompound("transformation");
                NbtList right = old == null ? null : old.getList("right_rotation");
                NbtTag rightRotation = right != null && right.size() == 4 && right.elementType() == NbtTag.FLOAT
                        ? right : floats(0, 0, 0, 1);
                float[] rotation = DisplayRotation.normalized(t.rotation());
                out.put("transformation", NbtCompound.builder()
                        .put("translation", floats(t.translation()))
                        .put("left_rotation", floats(rotation))
                        .put("scale", floats(t.scale()))
                        .put("right_rotation", rightRotation)
                        .build());
            }
            case EntityEdit.BillboardMode billboard -> out.putString("billboard", billboard.mode().nbtName());
            case EntityEdit.Brightness brightness -> {
                if (brightness.auto()) {
                    out.remove("brightness");
                } else {
                    out.put("brightness", NbtCompound.builder().putInt("block", brightness.block())
                            .putInt("sky", brightness.sky()).build());
                }
            }
            case EntityEdit.DisplayBlock block -> out.put("block_state", blockState(states.describe(block.state())));
            case EntityEdit.DisplayItem item ->
                    out.put("item", NbtCompound.builder().putString("id", item.itemId()).putInt("count", 1).build());
            case EntityEdit.DisplayText text -> out.putString("text", textJson(text.text()));
        }
        return out.build();
    }

    private static void move(TinkerKind kind, NbtCompound entity, NbtCompound.Builder out, EntityEdit.Position to) {
        double[] from = EntityNbt.position(entity);
        if (from == null) throw new IllegalArgumentException("The entity has no position");
        double dx = to.x() - from[0], dy = to.y() - from[1], dz = to.z() - from[2];
        if (Math.abs(dx) > MAX_MOVE || Math.abs(dy) > MAX_MOVE || Math.abs(dz) > MAX_MOVE) {
            throw new IllegalArgumentException("A move of more than " + (int) MAX_MOVE + " blocks");
        }
        if (kind.hanging()) {
            long bx = Math.round(dx), by = Math.round(dy), bz = Math.round(dz);
            if (Math.abs(dx - bx) > 1e-6 || Math.abs(dy - by) > 1e-6 || Math.abs(dz - bz) > 1e-6) {
                throw new IllegalArgumentException(kind + " moves by whole blocks only");
            }
            Integer tx = entity.getInt("TileX"), ty = entity.getInt("TileY"), tz = entity.getInt("TileZ");
            if (tx == null || ty == null || tz == null) throw new IllegalArgumentException("The entity hangs nowhere");
            out.putInt("TileX", tx + (int) bx).putInt("TileY", ty + (int) by).putInt("TileZ", tz + (int) bz);
            out.put("Pos", EntityNbt.doubles(from[0] + bx, from[1] + by, from[2] + bz));
            return;
        }
        out.put("Pos", EntityNbt.doubles(to.x(), to.y(), to.z()));
    }

    /** The {@code block_state} compound of a state, as {@code NbtHelper.fromBlockState} writes it. */
    static NbtCompound blockState(BlockDescriptor state) {
        NbtCompound.Builder out = NbtCompound.builder().putString("Name", state.block().value());
        if (!state.properties().isEmpty()) {
            NbtCompound.Builder properties = NbtCompound.builder();
            for (Map.Entry<String, String> entry : state.properties().entrySet()) {
                properties.putString(entry.getKey(), entry.getValue());
            }
            out.put("Properties", properties.build());
        }
        return out.build();
    }

    /** A plain-text component as JSON ({@code {"text":"..."}}), every special character escaped. */
    public static String textJson(String text) {
        StringBuilder out = new StringBuilder(text.length() + 12).append("{\"text\":\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> {
                    if (c < 0x20 || c == 0x7f || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append("\"}").toString();
    }

    /** Degrees in [-180, 180), as vanilla's {@code MathHelper.wrapDegrees} gives them. */
    public static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) wrapped -= 360f;
        if (wrapped < -180f) wrapped += 360f;
        return wrapped;
    }

    static NbtList floats(float... values) {
        List<NbtTag> tags = new ArrayList<>(values.length);
        for (float v : values) tags.add(new NbtTag.NbtFloat(v));
        return NbtList.of(NbtTag.FLOAT, tags);
    }
}
