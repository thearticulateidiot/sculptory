package dev.sculptory.fabric.schem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntityNbt;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/** Entities from files follow the kind rules: refused types left out (roots and passengers), attachments checked. */
class FileEntitiesTest {
    private static final Set<String> NEVER = Set.of("minecraft:tnt", "minecraft:wither", "minecraft:fireball",
            "minecraft:item", "minecraft:lightning_bolt");
    private static final Predicate<String> REFUSED = NEVER::contains;
    private static final Predicate<String> HANGING = Set.of("minecraft:item_frame", "minecraft:painting")::contains;
    private final FakeStateSpace states = new FakeStateSpace();

    private Clipboard clipboard(EntitySnapshot... entities) {
        return Clipboard.builder(states, new BlockPos(4, 4, 4)).build().withEntities(List.of(entities));
    }

    private static EntitySnapshot entity(String type, double x, BlockPos attached, NbtCompound data) {
        return new EntitySnapshot(type, x, 1, 1.5, 0f, 0f, attached, NbtIo.toBytes(data), false);
    }

    private static NbtCompound riding(NbtCompound... riders) {
        return NbtCompound.builder().put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(riders))).build();
    }

    private static NbtCompound rider(String id) {
        return NbtCompound.builder().putString("id", id).build();
    }

    private static NbtCompound data(EntitySnapshot entity) {
        try {
            return EntityNbt.decode(entity.nbt());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void refusedTypesAreLeftOutWithTheirRiders() {
        Clipboard file = clipboard(
                entity("minecraft:tnt", 0.5, null, NbtCompound.EMPTY),
                entity("minecraft:wither", 1.5, null, riding(rider("minecraft:pig"))),       // counted with its rider
                entity("minecraft:armor_stand", 2.5, null, NbtCompound.EMPTY),
                entity("minecraft:boat", 3.5, null, riding(rider("fireball"), rider("minecraft:pig"))));
        FileEntities.Result result = FileEntities.clean(file, REFUSED, HANGING);
        assertEquals(4, result.skipped(), "the tnt, the wither and its pig, the fireball riding the boat");
        List<EntitySnapshot> kept = result.clipboard().entities();
        assertEquals(2, kept.size());
        assertEquals("minecraft:armor_stand", kept.get(0).typeId());
        NbtCompound boat = data(kept.get(1));
        assertEquals(List.of(rider("minecraft:pig")), EntityNbt.riders(boat), "the pig still rides");

        Clipboard allowed = clipboard(entity("minecraft:armor_stand", 2.5, null, NbtCompound.EMPTY));
        assertSame(allowed, FileEntities.clean(allowed, REFUSED, HANGING).clipboard());
        assertEquals(0, FileEntities.clean(allowed, REFUSED, HANGING).skipped());
    }

    @Test
    void onlyHangingEntitiesKeepTheirAttachment() {
        // An armor stand claiming to hang on a block: its block is the one holding its position.
        Clipboard file = clipboard(
                entity("minecraft:armor_stand", 1.5, new BlockPos(1, 1, 1), NbtCompound.EMPTY),
                entity("minecraft:armor_stand", 6.5, new BlockPos(2, 1, 1), NbtCompound.EMPTY), // outside the box
                entity("minecraft:item_frame", 1.5, new BlockPos(1, 1, 0), NbtCompound.EMPTY));
        FileEntities.Result result = FileEntities.clean(file, REFUSED, HANGING);
        assertEquals(1, result.skipped(), "the stand standing outside the box");
        List<EntitySnapshot> kept = result.clipboard().entities();
        assertEquals(2, kept.size());
        EntitySnapshot frame = kept.stream().filter(e -> e.typeId().equals("minecraft:item_frame")).findFirst()
                .orElseThrow();
        EntitySnapshot stand = kept.stream().filter(e -> e.typeId().equals("minecraft:armor_stand")).findFirst()
                .orElseThrow();
        assertEquals(new BlockPos(1, 1, 0), frame.attached(), "a hanging entity keeps its block");
        assertNull(stand.attached());
        assertEquals(new BlockPos(1, 1, 1), stand.cell());
    }

    @Test
    void everythingIsLeftOutWhenAllTypesAreRefused() {
        // Before the server knows its entity types, every type is refused: nothing from a file slips through.
        Clipboard file = clipboard(entity("minecraft:armor_stand", 1.5, null, riding(rider("minecraft:pig"))),
                entity("minecraft:boat", 2.5, null, NbtCompound.EMPTY));
        FileEntities.Result result = FileEntities.clean(file, type -> true, type -> false);
        assertEquals(0, result.clipboard().entityCount());
        assertEquals(3, result.skipped());
    }
}
