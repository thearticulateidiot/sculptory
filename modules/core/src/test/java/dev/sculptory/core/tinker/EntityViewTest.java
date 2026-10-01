package dev.sculptory.core.tinker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtLimits;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the panel shows of an entity: read from saved NBT with vanilla's defaults, and its wire compound. */
class EntityViewTest {
    @Test
    void anArmorStandWithDefaultsLeftOut() {
        NbtCompound saved = NbtCompound.builder().putString("id", "minecraft:armor_stand")
                .put("Pos", EntityNbt.doubles(1.5, 64, 2.5)).put("Rotation", EntityEdits.floats(90, 0))
                .putByte("Small", (byte) 1).putByte("NoGravity", (byte) 1).putByte("Invisible", (byte) 0)
                .put("Pose", NbtCompound.builder().put("RightArm", EntityEdits.floats(-90, 0, 0)).build())
                .putByte("Fixed", (byte) 1).build();
        EntityView view = EntityView.fromSaved(TinkerKind.ARMOR_STAND, saved, "", "");
        assertEquals(1.5, view.x());
        assertEquals(90, view.yaw());
        assertTrue(view.flag(EntityEdit.Flag.SMALL));
        assertTrue(view.flag(EntityEdit.Flag.NO_GRAVITY));
        assertFalse(view.flag(EntityEdit.Flag.INVISIBLE));
        assertFalse(view.flag(EntityEdit.Flag.FIXED), "not a flag armor stands have");
        assertArrayEquals(new float[] {-90, 0, 0}, view.pose(EntityEdit.Part.RIGHT_ARM));
        assertArrayEquals(new float[] {-10, 0, -10}, view.pose(EntityEdit.Part.LEFT_ARM), "vanilla's default");
        assertEquals(view, EntityView.read(view.write()));
    }

    @Test
    void framesPaintingsAndDisplays() throws IOException {
        NbtCompound frame = NbtCompound.builder().put("Pos", EntityNbt.doubles(4.5, 65.5, 7.03))
                .putByte("ItemRotation", (byte) 3).putByte("Fixed", (byte) 1)
                .put("Item", NbtCompound.builder().putString("id", "minecraft:apple").putInt("count", 1).build()).build();
        EntityView view = EntityView.fromSaved(TinkerKind.GLOW_ITEM_FRAME, frame, "", "");
        assertEquals(3, view.itemRotation());
        assertEquals("minecraft:apple", view.item());
        assertTrue(view.flag(EntityEdit.Flag.FIXED));
        EntityView painting = EntityView.fromSaved(TinkerKind.PAINTING, NbtCompound.builder()
                .put("Pos", EntityNbt.doubles(0, 0, 0)).putString("variant", "minecraft:kebab").build(), "", "");
        assertEquals("minecraft:kebab", painting.variant());

        NbtCompound display = NbtCompound.builder().put("Pos", EntityNbt.doubles(0.5, 70, 0.5))
                .put("transformation", NbtCompound.builder().put("translation", EntityEdits.floats(0, 1, 0))
                        .put("left_rotation", EntityEdits.floats(0, 0, 0, 1)).put("scale", EntityEdits.floats(2, 2, 2))
                        .put("right_rotation", EntityEdits.floats(0, 0, 0, 1)).build())
                .putString("billboard", "vertical")
                .put("brightness", NbtCompound.builder().putInt("block", 12).putInt("sky", 3).build())
                .put("item", NbtCompound.builder().putString("id", "minecraft:diamond").build()).build();
        EntityView item = EntityView.fromSaved(TinkerKind.ITEM_DISPLAY, display, "", "");
        assertArrayEquals(new float[] {0, 1, 0}, item.translation());
        assertArrayEquals(new float[] {2, 2, 2}, item.scale());
        assertEquals(EntityEdit.Billboard.VERTICAL, item.billboard());
        assertEquals(new EntityEdit.Brightness(12, 3), item.brightness());
        assertEquals("minecraft:diamond", item.item());
        EntityView bare = EntityView.fromSaved(TinkerKind.TEXT_DISPLAY,
                NbtCompound.builder().put("Pos", EntityNbt.doubles(0, 0, 0)).build(), "", "Hello\nthere");
        assertArrayEquals(new float[] {1, 1, 1}, bare.scale(), "no transformation: the identity");
        assertArrayEquals(new float[] {0, 0, 0, 1}, bare.rotation());
        assertEquals(EntityEdit.Brightness.AUTO, bare.brightness());
        assertEquals(EntityEdit.Billboard.FIXED, bare.billboard());
        assertEquals("Hello\nthere", bare.text());
        for (EntityView v : List.of(view, painting, item, bare)) {
            NbtCompound wire = NbtIo.fromBytes(NbtIo.toBytes(v.write()), NbtLimits.BLOCK_ENTITY);
            assertEquals(v, EntityView.read(wire), v.kind() + " through bytes");
        }
    }

    @Test
    void readingRefusesOtherFormatsAndMissingFields() {
        NbtCompound good = EntityView.fromSaved(TinkerKind.ARMOR_STAND,
                NbtCompound.builder().put("Pos", EntityNbt.doubles(0, 0, 0)).build(), "", "").write();
        assertThrows(IllegalArgumentException.class, () -> EntityView.read(good.toBuilder().putInt("v", 2).build()));
        assertThrows(IllegalArgumentException.class, () -> EntityView.read(good.toBuilder().putString("kind", "PIG").build()));
        assertThrows(IllegalArgumentException.class, () -> EntityView.read(good.toBuilder().remove("pose").build()));
        assertThrows(IllegalArgumentException.class, () -> EntityView.read(good.toBuilder().remove("text").build()));
        assertThrows(IllegalArgumentException.class,
                () -> EntityView.read(good.toBuilder().put("scale", EntityEdits.floats(1, Float.NaN, 1)).build()));
        assertThrows(IllegalArgumentException.class,
                () -> EntityView.read(good.toBuilder().putInt("block_light", 20).build()));
    }

    @Test
    void rotationsGoBetweenAnglesAndQuaternions() {
        for (float[] angles : List.of(new float[] {0, 0, 0}, new float[] {90, 0, 0}, new float[] {-45, 30, 10},
                new float[] {170, -80, -120})) {
            float[] back = DisplayRotation.toEuler(DisplayRotation.fromEuler(angles[0], angles[1], angles[2]));
            assertArrayEquals(angles, back, 1e-3f, java.util.Arrays.toString(angles));
        }
        float[] quarter = DisplayRotation.fromEuler(90, 0, 0);
        assertArrayEquals(new float[] {0, 0.70710677f, 0, 0.70710677f}, quarter, 1e-6f, "90° around y");
        assertArrayEquals(new float[] {0, 0, 0, 1}, DisplayRotation.normalized(new float[4]));
        assertArrayEquals(new float[3], DisplayRotation.toEuler(new float[4]));
    }
}
