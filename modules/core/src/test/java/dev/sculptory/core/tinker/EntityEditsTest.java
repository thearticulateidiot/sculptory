package dev.sculptory.core.tinker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.testing.FakeStateSpace;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tinker's entity edits written into vanilla entity NBT, and what each kind refuses. */
class EntityEditsTest {
    private final FakeStateSpace states = new FakeStateSpace();

    private static NbtCompound stand() {
        return NbtCompound.builder().putString("id", "minecraft:armor_stand").put("Pos", EntityNbt.doubles(1.5, 64, -2.5))
                .put("Rotation", EntityEdits.floats(30, 0)).putByte("Small", (byte) 0)
                .put("Pose", NbtCompound.builder().put("Head", EntityEdits.floats(5, 0, 0)).build()).build();
    }

    private static NbtCompound frame() {
        return NbtCompound.builder().putString("id", "minecraft:item_frame").put("Pos", EntityNbt.doubles(4.5, 65.5, 7.03125))
                .putInt("TileX", 4).putInt("TileY", 65).putInt("TileZ", 7).putByte("Facing", (byte) 3)
                .putByte("ItemRotation", (byte) 0).build();
    }

    private static NbtCompound display(String type) {
        return NbtCompound.builder().putString("id", type).put("Pos", EntityNbt.doubles(0.5, 70, 0.5))
                .put("transformation", NbtCompound.builder().put("translation", EntityEdits.floats(0, 0, 0))
                        .put("left_rotation", EntityEdits.floats(0, 0, 0, 1)).put("scale", EntityEdits.floats(1, 1, 1))
                        .put("right_rotation", EntityEdits.floats(0, 0.6f, 0, 0.8f)).build())
                .putString("billboard", "fixed").build();
    }

    private NbtCompound apply(TinkerKind kind, NbtCompound entity, EntityEdit... edits) {
        return EntityEdits.apply(kind, entity, List.of(edits), states);
    }

    private static float[] floatList(NbtList list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = ((NbtTag.NbtFloat) list.get(i)).value();
        return out;
    }

    @Test
    void armorStandPoseFlagsAndTurns() {
        NbtCompound edited = apply(TinkerKind.ARMOR_STAND, stand(),
                new EntityEdit.Pose(EntityEdit.Part.LEFT_ARM, -30, 10, -5),
                new EntityEdit.Toggle(EntityEdit.Flag.SMALL, true),
                new EntityEdit.Toggle(EntityEdit.Flag.NO_GRAVITY, true),
                new EntityEdit.Toggle(EntityEdit.Flag.SHOW_ARMS, true),
                new EntityEdit.Yaw(200));
        NbtCompound pose = edited.getCompound("Pose");
        assertArrayEquals(new float[] {5, 0, 0}, floatList(pose.getList("Head")), "the other parts stay");
        assertArrayEquals(new float[] {-30, 10, -5}, floatList(pose.getList("LeftArm")));
        assertEquals(1, edited.getInt("Small"));
        assertEquals(1, edited.getInt("NoGravity"));
        assertEquals(1, edited.getInt("ShowArms"));
        assertArrayEquals(new float[] {-160, 0}, EntityNbt.rotation(edited), "the yaw wrapped as vanilla does");
        assertEquals(0, apply(TinkerKind.ARMOR_STAND, edited, new EntityEdit.Toggle(EntityEdit.Flag.SMALL, false))
                .getInt("Small"));
        assertArrayEquals(new double[] {1.5625, 64, -2.5},
                EntityNbt.position(apply(TinkerKind.ARMOR_STAND, stand(), new EntityEdit.Position(1.5625, 64, -2.5))));
    }

    @Test
    void editsAKindDoesNotHaveAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> apply(TinkerKind.PAINTING, frame(), new EntityEdit.Toggle(EntityEdit.Flag.INVISIBLE, true)));
        assertThrows(IllegalArgumentException.class,
                () -> apply(TinkerKind.ITEM_FRAME, frame(), new EntityEdit.Yaw(90)), "a frame faces its wall");
        assertThrows(IllegalArgumentException.class,
                () -> apply(TinkerKind.ARMOR_STAND, stand(), new EntityEdit.ItemRotation(2)));
        assertThrows(IllegalArgumentException.class,
                () -> apply(TinkerKind.TEXT_DISPLAY, display("minecraft:text_display"), new EntityEdit.DisplayItem("minecraft:apple")));
        assertThrows(IllegalArgumentException.class,
                () -> apply(TinkerKind.ARMOR_STAND, stand(), new EntityEdit.Position(1.5 + 17, 64, -2.5)), "too far");
        assertThrows(IllegalArgumentException.class,
                () -> apply(TinkerKind.ARMOR_STAND, NbtCompound.builder().build(), new EntityEdit.Position(0, 0, 0)),
                "no position");
        assertThrows(IllegalArgumentException.class, () -> EntityEdits.apply(TinkerKind.ARMOR_STAND, stand(),
                java.util.Collections.nCopies(EntityEdits.MAX_EDITS + 1, new EntityEdit.Yaw(0)), states));
    }

    @Test
    void hangingEntitiesMoveByWholeBlocksWithTheirBlock() {
        NbtCompound moved = apply(TinkerKind.ITEM_FRAME, frame(), new EntityEdit.Position(4.5, 66.5, 7.03125));
        assertEquals(66, moved.getInt("TileY"));
        assertEquals(4, moved.getInt("TileX"));
        assertArrayEquals(new double[] {4.5, 66.5, 7.03125}, EntityNbt.position(moved));
        assertThrows(IllegalArgumentException.class,
                () -> apply(TinkerKind.ITEM_FRAME, frame(), new EntityEdit.Position(4.5625, 65.5, 7.03125)), "a sixteenth");
        assertThrows(IllegalArgumentException.class, () -> apply(TinkerKind.PAINTING,
                NbtCompound.builder().put("Pos", EntityNbt.doubles(0, 0, 0)).build(), new EntityEdit.Position(1, 0, 0)),
                "no block to move");
        NbtCompound frame = apply(TinkerKind.ITEM_FRAME, frame(), new EntityEdit.ItemRotation(5),
                new EntityEdit.Toggle(EntityEdit.Flag.FIXED, true), new EntityEdit.Toggle(EntityEdit.Flag.INVISIBLE, true));
        assertEquals(5, frame.getInt("ItemRotation"));
        assertEquals(1, frame.getInt("Fixed"));
        assertEquals(1, frame.getInt("Invisible"));
        assertEquals("minecraft:kebab", apply(TinkerKind.PAINTING, frame(), new EntityEdit.PaintingVariant("minecraft:kebab"))
                .getString("variant"));
    }

    @Test
    void displaysTransformLightAndWhatTheyShow() {
        float[] rotation = DisplayRotation.fromEuler(90, 0, 0);
        NbtCompound edited = apply(TinkerKind.BLOCK_DISPLAY, display("minecraft:block_display"),
                new EntityEdit.Transformation(new float[] {0.5f, -1, 0}, new float[] {0, 2, 0, 2}, new float[] {2, 2, 0.5f}),
                new EntityEdit.BillboardMode(EntityEdit.Billboard.CENTER),
                new EntityEdit.Brightness(15, 7),
                new EntityEdit.DisplayBlock(states.state("minecraft:oak_stairs[facing=east,half=top]")));
        NbtCompound t = edited.getCompound("transformation");
        assertArrayEquals(new float[] {0.5f, -1, 0}, floatList(t.getList("translation")));
        float[] left = floatList(t.getList("left_rotation"));
        assertEquals(0.70710677f, left[1], 1e-6f, "normalized");
        assertEquals(0.70710677f, left[3], 1e-6f);
        assertArrayEquals(new float[] {2, 2, 0.5f}, floatList(t.getList("scale")));
        assertArrayEquals(new float[] {0, 0.6f, 0, 0.8f}, floatList(t.getList("right_rotation")), "kept");
        assertEquals("center", edited.getString("billboard"));
        assertEquals(15, edited.getCompound("brightness").getInt("block"));
        assertEquals(7, edited.getCompound("brightness").getInt("sky"));
        NbtCompound block = edited.getCompound("block_state");
        assertEquals("minecraft:oak_stairs", block.getString("Name"));
        assertEquals("east", block.getCompound("Properties").getString("facing"));
        assertEquals("top", block.getCompound("Properties").getString("half"));
        assertNull(apply(TinkerKind.BLOCK_DISPLAY, edited, EntityEdit.Brightness.AUTO).get("brightness"), "auto: no key");
        assertFalse(apply(TinkerKind.BLOCK_DISPLAY, display("minecraft:block_display"),
                new EntityEdit.DisplayBlock(states.state("minecraft:stone"))).getCompound("block_state").contains("Properties"));

        NbtCompound item = apply(TinkerKind.ITEM_DISPLAY, display("minecraft:item_display"),
                new EntityEdit.DisplayItem("minecraft:diamond_sword")).getCompound("item");
        assertEquals("minecraft:diamond_sword", item.getString("id"));
        assertEquals(1, item.getInt("count"));

        NbtCompound text = apply(TinkerKind.TEXT_DISPLAY, display("minecraft:text_display"),
                new EntityEdit.DisplayText("Say \"hi\"\n§cback\\slash"), new EntityEdit.Yaw(45));
        assertEquals("{\"text\":\"Say \\\"hi\\\"\\nback\\\\slash\"}", text.getString("text"), "plain, escaped JSON");
        assertEquals(45, EntityNbt.rotation(text)[0]);
        assertTrue(rotation.length == 4);
    }

    @Test
    void editsCheckTheirValues() {
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.Pose(EntityEdit.Part.HEAD, 361, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.Pose(EntityEdit.Part.HEAD, Float.NaN, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.ItemRotation(8));
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.Brightness(16, 0));
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.Brightness(-1, 3), "both or neither auto");
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.Position(Double.POSITIVE_INFINITY, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.Yaw(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.PaintingVariant("Not An Id"));
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.Transformation(new float[] {65, 0, 0},
                new float[] {0, 0, 0, 1}, new float[] {1, 1, 1}), "translated too far");
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.Transformation(new float[3],
                new float[] {0, 0, 0, 0}, new float[] {1, 1, 1}), "no rotation");
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.DisplayText("\n".repeat(EntityEdit.MAX_TEXT_LINES)));
        // Each line is cut to its cap first; the whole text has a cap of its own.
        assertThrows(IllegalArgumentException.class, () -> new EntityEdit.DisplayText(String.join("\n",
                java.util.Collections.nCopies(3, "x".repeat(SignText.MAX_LINE_CHARS)))));
        assertEquals("ab\ncd", new EntityEdit.DisplayText("a§lb\ncd").text());
        assertEquals(new EntityEdit.Transformation(new float[3], new float[] {0, 0, 0, 1}, new float[] {1, 1, 1}),
                new EntityEdit.Transformation(new float[3], new float[] {0, 0, 0, 1}, new float[] {1, 1, 1}));
    }

    @Test
    void wrapDegreesMatchesVanilla() {
        assertEquals(-160f, EntityEdits.wrapDegrees(200));
        assertEquals(-180f, EntityEdits.wrapDegrees(180));
        assertEquals(179f, EntityEdits.wrapDegrees(-181));
        assertEquals(0f, EntityEdits.wrapDegrees(720));
    }
}
