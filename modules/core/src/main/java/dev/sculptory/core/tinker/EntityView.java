package dev.sculptory.core.tinker;

import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What the Tinker panel shows of an entity: its kind, where it stands and looks, and
 * the settings the panel edits. The server makes it from the entity's saved NBT ({@link #fromSaved}), with the two
 * things only the game can read (a block display's state as text and a text display's text as plain text), and sends
 * it as {@link #write()}'s compound; the client reads it back with {@link #read}. Immutable; arrays are copied in and
 * out.
 */
public final class EntityView {
    /** The compound's format ({@code v}). */
    public static final int FORMAT = 1;

    private final TinkerKind kind;
    private final double[] position;
    private final float yaw;
    private final float pitch;
    private final Map<EntityEdit.Part, float[]> pose;
    private final Set<EntityEdit.Flag> flags;
    private final int itemRotation;
    private final String item;
    private final String variant;
    private final float[] translation;
    private final float[] rotation;
    private final float[] scale;
    private final EntityEdit.Billboard billboard;
    private final EntityEdit.Brightness brightness;
    private final String blockState;
    private final String text;

    private EntityView(TinkerKind kind, double[] position, float yaw, float pitch, Map<EntityEdit.Part, float[]> pose,
                       Set<EntityEdit.Flag> flags, int itemRotation, String item, String variant, float[] translation,
                       float[] rotation, float[] scale, EntityEdit.Billboard billboard,
                       EntityEdit.Brightness brightness, String blockState, String text) {
        this.kind = Objects.requireNonNull(kind);
        this.position = position.clone();
        this.yaw = yaw;
        this.pitch = pitch;
        this.pose = new EnumMap<>(EntityEdit.Part.class);
        for (EntityEdit.Part part : EntityEdit.Part.values()) {
            float[] angles = pose.get(part);
            this.pose.put(part, angles == null || angles.length != 3 ? part.standard() : angles.clone());
        }
        this.flags = flags.isEmpty() ? EnumSet.noneOf(EntityEdit.Flag.class) : EnumSet.copyOf(flags);
        this.itemRotation = Math.floorMod(itemRotation, 8);
        this.item = Objects.requireNonNull(item);
        this.variant = Objects.requireNonNull(variant);
        this.translation = translation.clone();
        this.rotation = rotation.clone();
        this.scale = scale.clone();
        this.billboard = Objects.requireNonNull(billboard);
        this.brightness = Objects.requireNonNull(brightness);
        this.blockState = Objects.requireNonNull(blockState);
        this.text = Objects.requireNonNull(text);
    }

    /**
     * The view of an entity of {@code kind} from its saved NBT ({@code saved}: the whole compound), with a block
     * display's state as {@code StateSpace.format} text ({@code blockState}, "" otherwise) and a text display's text
     * as plain text ({@code text}, "" otherwise).
     */
    public static EntityView fromSaved(TinkerKind kind, NbtCompound saved, String blockState, String text) {
        double[] position = EntityNbt.position(saved);
        float[] look = EntityNbt.rotation(saved);
        Map<EntityEdit.Part, float[]> pose = new EnumMap<>(EntityEdit.Part.class);
        NbtCompound parts = saved.getCompound("Pose");
        for (EntityEdit.Part part : EntityEdit.Part.values()) {
            float[] angles = parts == null ? null : floats(parts.getList(part.nbtKey()), 3);
            pose.put(part, angles != null ? angles : part.standard());
        }
        EnumSet<EntityEdit.Flag> flags = EnumSet.noneOf(EntityEdit.Flag.class);
        for (EntityEdit.Flag flag : EntityEdit.Flag.values()) {
            if (flag.appliesTo(kind) && isTrue(saved.get(flag.nbtKey()))) flags.add(flag);
        }
        Integer itemRotation = saved.getInt("ItemRotation");
        NbtCompound itemStack = saved.getCompound(kind.itemFrame() ? "Item" : "item");
        String item = itemStack == null || itemStack.getString("id") == null ? "" : itemStack.getString("id");
        String variant = saved.getString("variant");
        NbtCompound transformation = saved.getCompound("transformation");
        float[] translation = transformation == null ? null : floats(transformation.getList("translation"), 3);
        float[] rotation = transformation == null ? null : floats(transformation.getList("left_rotation"), 4);
        float[] scale = transformation == null ? null : floats(transformation.getList("scale"), 3);
        NbtCompound light = saved.getCompound("brightness");
        Integer block = light == null ? null : light.getInt("block");
        Integer sky = light == null ? null : light.getInt("sky");
        EntityEdit.Brightness brightness = block != null && sky != null && block >= 0 && block <= 15 && sky >= 0
                && sky <= 15 ? new EntityEdit.Brightness(block, sky) : EntityEdit.Brightness.AUTO;
        return new EntityView(kind, position == null ? new double[3] : position, look == null ? 0 : look[0],
                look == null ? 0 : look[1], pose, flags, itemRotation == null ? 0 : itemRotation, item,
                variant == null ? "" : variant, translation == null ? new float[3] : translation,
                rotation == null ? new float[] {0, 0, 0, 1} : rotation,
                scale == null ? new float[] {1, 1, 1} : scale,
                EntityEdit.Billboard.of(saved.getString("billboard")), brightness, blockState, text);
    }

    /** The compound the server sends. */
    public NbtCompound write() {
        NbtCompound.Builder out = NbtCompound.builder().putInt("v", FORMAT).putString("kind", kind.name())
                .put("pos", EntityNbt.doubles(position[0], position[1], position[2]))
                .put("look", EntityEdits.floats(yaw, pitch));
        NbtCompound.Builder parts = NbtCompound.builder();
        pose.forEach((part, angles) -> parts.put(part.nbtKey(), EntityEdits.floats(angles)));
        out.put("pose", parts.build());
        List<String> on = new ArrayList<>();
        for (EntityEdit.Flag flag : flags) on.add(flag.nbtKey());
        out.put("flags", NbtList.ofStrings(on));
        out.putInt("item_rotation", itemRotation).putString("item", item).putString("variant", variant);
        out.put("translation", EntityEdits.floats(translation)).put("rotation", EntityEdits.floats(rotation))
                .put("scale", EntityEdits.floats(scale));
        out.putString("billboard", billboard.nbtName());
        out.putInt("block_light", brightness.block()).putInt("sky_light", brightness.sky());
        out.putString("block_state", blockState).putString("text", text);
        return out.build();
    }

    /**
     * Reads {@link #write()}'s compound.
     *
     * @throws IllegalArgumentException for another format, an unknown kind or a missing or malformed field
     */
    public static EntityView read(NbtCompound in) {
        Integer format = in.getInt("v");
        if (format == null || format != FORMAT) throw new IllegalArgumentException("Entity view format " + format);
        TinkerKind kind;
        try {
            kind = TinkerKind.valueOf(Objects.requireNonNull(in.getString("kind")));
        } catch (NullPointerException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown entity kind " + in.getString("kind"));
        }
        double[] position = EntityNbt.position(NbtCompound.builder().put("Pos", require(in.getList("pos"))).build());
        if (position == null) throw new IllegalArgumentException("No position");
        float[] look = require(floats(in.getList("look"), 2));
        Map<EntityEdit.Part, float[]> pose = new EnumMap<>(EntityEdit.Part.class);
        NbtCompound parts = require(in.getCompound("pose"));
        for (EntityEdit.Part part : EntityEdit.Part.values()) {
            pose.put(part, require(finite(floats(parts.getList(part.nbtKey()), 3))));
        }
        EnumSet<EntityEdit.Flag> flags = EnumSet.noneOf(EntityEdit.Flag.class);
        NbtList on = require(in.getList("flags"));
        List<String> names = on.isEmpty() ? List.of() : on.strings();
        if (names == null) throw new IllegalArgumentException("Malformed flags");
        for (EntityEdit.Flag flag : EntityEdit.Flag.values()) {
            if (names.contains(flag.nbtKey())) flags.add(flag);
        }
        Integer blockLight = require(in.getInt("block_light"));
        Integer skyLight = require(in.getInt("sky_light"));
        return new EntityView(kind, position, look[0], look[1], pose, flags, require(in.getInt("item_rotation")),
                require(in.getString("item")), require(in.getString("variant")),
                require(finite(floats(in.getList("translation"), 3))), require(finite(floats(in.getList("rotation"), 4))),
                require(finite(floats(in.getList("scale"), 3))), EntityEdit.Billboard.of(in.getString("billboard")),
                new EntityEdit.Brightness(blockLight, skyLight), require(in.getString("block_state")),
                require(in.getString("text")));
    }

    public TinkerKind kind() {
        return kind;
    }

    public double x() {
        return position[0];
    }

    public double y() {
        return position[1];
    }

    public double z() {
        return position[2];
    }

    public float yaw() {
        return yaw;
    }

    public float pitch() {
        return pitch;
    }

    /** An armor stand part's angles (vanilla's standard ones when its pose leaves the part out). */
    public float[] pose(EntityEdit.Part part) {
        return pose.get(part).clone();
    }

    /** Whether {@code flag} is on (false for flags the kind does not have). */
    public boolean flag(EntityEdit.Flag flag) {
        return flags.contains(flag);
    }

    /** An item frame's item rotation, 0-7. */
    public int itemRotation() {
        return itemRotation;
    }

    /** An item frame's or item display's item id, "" when it holds none. */
    public String item() {
        return item;
    }

    /** A painting's variant id, "" when unknown. */
    public String variant() {
        return variant;
    }

    public float[] translation() {
        return translation.clone();
    }

    /** A display's left rotation (x, y, z, w). */
    public float[] rotation() {
        return rotation.clone();
    }

    public float[] scale() {
        return scale.clone();
    }

    public EntityEdit.Billboard billboard() {
        return billboard;
    }

    public EntityEdit.Brightness brightness() {
        return brightness;
    }

    /** A block display's state as text, "" otherwise. */
    public String blockState() {
        return blockState;
    }

    /** A text display's text as plain text (lines separated by {@code \n}), "" otherwise. */
    public String text() {
        return text;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof EntityView other)) return false;
        if (kind != other.kind || !Arrays.equals(position, other.position) || Float.compare(yaw, other.yaw) != 0
                || Float.compare(pitch, other.pitch) != 0 || !flags.equals(other.flags)
                || itemRotation != other.itemRotation || !item.equals(other.item) || !variant.equals(other.variant)
                || !Arrays.equals(translation, other.translation) || !Arrays.equals(rotation, other.rotation)
                || !Arrays.equals(scale, other.scale) || billboard != other.billboard
                || !brightness.equals(other.brightness) || !blockState.equals(other.blockState)
                || !text.equals(other.text)) {
            return false;
        }
        for (EntityEdit.Part part : EntityEdit.Part.values()) {
            if (!Arrays.equals(pose.get(part), other.pose.get(part))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, Arrays.hashCode(position), yaw, flags, item, variant, text);
    }

    @Override
    public String toString() {
        return "EntityView[" + kind + " at " + Arrays.toString(position) + ", yaw " + yaw + "]";
    }

    private static boolean isTrue(NbtTag tag) {
        Integer value = NbtTag.intValue(tag);
        return value != null && value != 0;
    }

    /** The list's {@code length} floats (float or double elements), or null when it is not such a list. */
    private static float[] floats(NbtList list, int length) {
        if (list == null || list.size() != length) return null;
        float[] out = new float[length];
        for (int i = 0; i < length; i++) {
            switch (list.get(i)) {
                case NbtTag.NbtFloat f -> out[i] = f.value();
                case NbtTag.NbtDouble d -> out[i] = (float) d.value();
                default -> {
                    return null;
                }
            }
        }
        return out;
    }

    private static float[] finite(float[] values) {
        if (values == null) return null;
        for (float v : values) {
            if (!Float.isFinite(v)) return null;
        }
        return values;
    }

    private static <T> T require(T value) {
        if (value == null) throw new IllegalArgumentException("Missing or malformed field");
        return value;
    }
}
