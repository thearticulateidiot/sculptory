package dev.sculptory.core.clipboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.SourceBlocks;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.testing.FakeWorld;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClipboardTest {
    private final FakeStateSpace states = new FakeStateSpace();
    private final int stone = states.state("minecraft:stone");
    private final int dirt = states.state("minecraft:dirt");
    private final int chest = states.state("minecraft:chest[facing=west]");
    private final NbtBytes tile = BlockEntityNbt.toNbtBytes("minecraft:chest",
            NbtCompound.builder().putString("CustomName", "\"Loot\"").putInt("Lock", 1).build());

    private Clipboard sample(BlockPos anchor) {
        return Clipboard.builder(states, new BlockPos(20, 3, 5)).anchor(anchor).source("test")
                .set(0, 0, 0, stone).set(19, 2, 4, dirt).set(17, 1, 1, chest).setTile(17, 1, 1, tile)
                .build();
    }

    @Test
    void holdsItsContent() {
        Clipboard clipboard = sample(new BlockPos(1, 0, 1));
        assertEquals(new BlockPos(20, 3, 5), clipboard.size());
        assertEquals(new BlockPos(1, 0, 1), clipboard.anchor());
        assertEquals(3, clipboard.cellCount());
        assertEquals(300, clipboard.volume());
        assertEquals(1, clipboard.tileCount());
        assertEquals(stone, clipboard.get(0, 0, 0));
        assertEquals(-1, clipboard.get(1, 0, 0), "absent");
        assertEquals(-1, clipboard.get(20, 0, 0), "outside");
        assertEquals(tile, clipboard.tile(17, 1, 1));
        assertNull(clipboard.tile(0, 0, 0));
        assertEquals("test", clipboard.source());
        assertEquals(states, clipboard.states());
    }

    @Test
    void findsTilesByStateAndContent() {
        Clipboard clipboard = sample(BlockPos.ORIGIN);
        assertEquals(true, clipboard.anyTile((state, t) -> state == chest && t.equals(tile)));
        assertEquals(false, clipboard.anyTile((state, t) -> state == stone));
        Clipboard plain = Clipboard.builder(states, new BlockPos(40, 2, 2)).set(33, 0, 0, stone).build();
        int[] calls = {0};
        assertEquals(false, plain.anyTile((state, t) -> ++calls[0] > 0));
        assertEquals(0, calls[0], "no tiles, no calls");
    }

    @Test
    void visitsCellsInSchematicOrder() {
        Clipboard clipboard = Clipboard.builder(states, new BlockPos(17, 2, 2)).set(16, 1, 1, stone).build();
        List<BlockPos> order = new ArrayList<>();
        int[] present = {0};
        clipboard.forEachCell((x, y, z, state, t) -> {
            order.add(new BlockPos(x, y, z));
            if (state >= 0) present[0]++;
        });
        assertEquals(17 * 2 * 2, order.size());
        assertEquals(new BlockPos(1, 0, 0), order.get(1), "x fastest");
        assertEquals(new BlockPos(0, 0, 1), order.get(17), "then z");
        assertEquals(new BlockPos(0, 1, 0), order.get(34), "then y");
        assertEquals(new BlockPos(16, 1, 1), order.get(order.size() - 1));
        assertEquals(1, present[0]);
    }

    @Test
    void isImmutable() {
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(2, 2, 2)).set(0, 0, 0, stone);
        Clipboard clipboard = builder.build();
        builder.set(1, 1, 1, dirt);
        assertEquals(-1, clipboard.get(1, 1, 1), "later builder changes do not leak in");
        SourceBlocks source = clipboard.toSource();
        source.cells().set(0, 0, 0, dirt);
        assertEquals(stone, clipboard.get(0, 0, 0), "the paste source is a copy");
        clipboard.copyBlocks().set(0, 0, 0, dirt);
        assertEquals(stone, clipboard.get(0, 0, 0));
        assertEquals(new BlockPos(2, 2, 2), source.size());
    }

    @Test
    void contentHashCoversContentNotStorage() {
        Clipboard a = sample(BlockPos.ORIGIN);
        // Same content, set in another order and with a detour through another state.
        Clipboard b = Clipboard.builder(states, new BlockPos(20, 3, 5))
                .set(17, 1, 1, chest).setTile(17, 1, 1, tile).set(19, 2, 4, stone).set(19, 2, 4, dirt).set(0, 0, 0, stone)
                .source("elsewhere").build();
        assertEquals(a.contentHash(), b.contentHash());
        assertNotEquals(a, b, "equal content, different source description");
        assertEquals(a, b.withSource("test"));
        // Equal content across state-space instances (handles are hashed as their text).
        FakeStateSpace otherSpace = new FakeStateSpace();
        Clipboard c = Clipboard.builder(otherSpace, new BlockPos(20, 3, 5))
                .set(0, 0, 0, otherSpace.state("minecraft:stone")).set(19, 2, 4, otherSpace.state("minecraft:dirt"))
                .set(17, 1, 1, otherSpace.state("minecraft:chest[facing=west]")).setTile(17, 1, 1, tile).build();
        assertEquals(a.contentHash(), c.contentHash());
        // Tile bytes with another key order are the same content.
        NbtCompound reordered = NbtCompound.builder().putInt("Lock", 1).putString("CustomName", "\"Loot\"")
                .putString("id", "minecraft:chest").build();
        Clipboard d = Clipboard.builder(states, new BlockPos(20, 3, 5)).set(0, 0, 0, stone).set(19, 2, 4, dirt)
                .set(17, 1, 1, chest).setTile(17, 1, 1, new NbtBytes("minecraft:chest", NbtIo.toBytes(reordered))).build();
        assertEquals(a.contentHash(), d.contentHash());

        assertNotEquals(a.contentHash(), sample(new BlockPos(0, 1, 0)).contentHash(), "the anchor is content");
        assertNotEquals(a.contentHash(), a.withAnchor(new BlockPos(5, 5, 5)).contentHash());
        assertEquals(a.contentHash(), a.withAnchor(new BlockPos(5, 5, 5)).withAnchor(BlockPos.ORIGIN).contentHash());
        Clipboard noTile = Clipboard.builder(states, new BlockPos(20, 3, 5)).set(0, 0, 0, stone).set(19, 2, 4, dirt)
                .set(17, 1, 1, chest).build();
        assertNotEquals(a.contentHash(), noTile.contentHash(), "tiles are content");
        Clipboard bigger = Clipboard.builder(states, new BlockPos(21, 3, 5)).set(0, 0, 0, stone).set(19, 2, 4, dirt)
                .set(17, 1, 1, chest).setTile(17, 1, 1, tile).build();
        assertNotEquals(a.contentHash(), bigger.contentHash(), "the size is content");
        Clipboard air = Clipboard.builder(states, new BlockPos(20, 3, 5)).set(0, 0, 0, stone).set(19, 2, 4, dirt)
                .set(17, 1, 1, chest).setTile(17, 1, 1, tile).set(5, 0, 0, states.air()).build();
        assertNotEquals(a.contentHash(), air.contentHash(), "present air differs from absent");
    }

    @Test
    void builderValidates() {
        assertThrows(IllegalArgumentException.class, () -> Clipboard.builder(states, new BlockPos(0, 1, 1)));
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(2, 2, 2));
        assertThrows(IllegalArgumentException.class, () -> builder.set(2, 0, 0, stone));
        assertThrows(IllegalArgumentException.class, () -> builder.set(0, -1, 0, stone));
        assertThrows(IllegalArgumentException.class, () -> builder.set(0, 0, 0, states.size()));
        assertThrows(IllegalStateException.class, () -> builder.setTile(1, 1, 1, tile));
        builder.set(0, 0, 0, chest);
        assertThrows(IllegalArgumentException.class,
                () -> builder.setTile(0, 0, 0, new NbtBytes("Not An Id", tile.nbtBytes())), "tile types are namespaced ids");
    }

    @Test
    void undecodableTilesStillHash() {
        NbtBytes junk = new NbtBytes("minecraft:chest", new byte[] {1, 2, 3});
        Clipboard a = Clipboard.builder(states, new BlockPos(1, 1, 1)).set(0, 0, 0, chest).setTile(0, 0, 0, junk).build();
        Clipboard b = Clipboard.builder(states, new BlockPos(1, 1, 1)).set(0, 0, 0, chest)
                .setTile(0, 0, 0, new NbtBytes("minecraft:chest", new byte[] {1, 2, 4})).build();
        assertEquals(a.contentHash(), Clipboard.builder(states, new BlockPos(1, 1, 1)).set(0, 0, 0, chest)
                .setTile(0, 0, 0, junk).build().contentHash());
        assertNotEquals(a.contentHash(), b.contentHash());
    }

    @Test
    void copiesOutOfAWorld() {
        FakeWorld world = new FakeWorld(states);
        Box box = Box.of(new BlockPos(-20, 60, 5), new BlockPos(-3, 62, 40));
        world.fill(Box.of(new BlockPos(-20, 60, 5), new BlockPos(-3, 60, 40)), stone);
        world.set(-10, 61, 20, chest);
        world.setTile(-10, 61, 20, tile);
        world.set(-4, 62, 39, dirt);
        Clipboard all = Clipboard.copyOf(world, box, new BlockPos(-10, 61, 20), null, "copy");
        assertEquals(new BlockPos(18, 3, 36), all.size());
        assertEquals(new BlockPos(10, 1, 15), all.anchor());
        assertEquals(all.volume(), all.cellCount(), "unmasked copies are dense (air included)");
        assertEquals(stone, all.get(0, 0, 0));
        assertEquals(chest, all.get(10, 1, 15));
        assertEquals(tile, all.tile(10, 1, 15));
        assertEquals(dirt, all.get(16, 2, 34));
        assertEquals(states.air(), all.get(0, 2, 0));

        Clipboard masked = Clipboard.copyOf(world, box, box.min(),
                new CellMask.Not(new CellMask.States(new int[] {states.air()})).bind(states), "masked");
        assertEquals(18 * 36 + 2, masked.cellCount());
        assertEquals(-1, masked.get(0, 2, 0), "masked-out cells are absent");
        assertEquals(BlockPos.ORIGIN, masked.anchor());
    }

    // ---- Entities ----

    private static EntitySnapshot entity(String type, double x, NbtCompound data, boolean trusted) {
        return new EntitySnapshot(type, x, 1, 2.5, 30f, 0f, null, NbtIo.toBytes(data), trusted);
    }

    @Test
    void aClipboardWithoutEntitiesHashesAsBeforeEntitiesExisted() {
        // Computed with the clipboard code before entities existed (main at contracts-v2): library hashes stay valid.
        assertEquals("e98a4138b0495ea753d305a05f65e6ff5c4c732cc25dd8cc669f19015e230913",
                sample(new BlockPos(1, 0, 1)).contentHash().hex());
        assertEquals(sample(BlockPos.ORIGIN).contentHash(), sample(BlockPos.ORIGIN).withEntities(List.of()).contentHash());
    }

    @Test
    void entitiesAreContentInCanonicalOrder() {
        NbtCompound stand = NbtCompound.builder().putString("CustomName", "\"Bob\"").putInt("DisabledSlots", 1).build();
        NbtCompound standReordered = NbtCompound.builder().putInt("DisabledSlots", 1)
                .putString("CustomName", "\"Bob\"").build();
        EntitySnapshot a = entity("minecraft:armor_stand", 1.5, stand, true);
        EntitySnapshot b = entity("minecraft:boat", 4.5, NbtCompound.EMPTY, true);
        Clipboard plain = sample(BlockPos.ORIGIN);
        Clipboard withTwo = plain.withEntities(List.of(b, a));
        assertEquals(2, withTwo.entityCount());
        assertEquals(List.of(a, b), withTwo.entities(), "canonical order");
        assertNotEquals(plain.contentHash(), withTwo.contentHash(), "entities are content");
        assertEquals(plain.cellCount(), withTwo.cellCount());

        Clipboard sameInOtherOrder = plain.withEntities(List.of(
                entity("minecraft:armor_stand", 1.5, standReordered, false), b));
        assertEquals(withTwo.contentHash(), sameInOtherOrder.contentHash(),
                "entity order, NBT key order and trust are not content");
        Clipboard built = Clipboard.builder(states, new BlockPos(20, 3, 5)).source("test")
                .set(0, 0, 0, stone).set(19, 2, 4, dirt).set(17, 1, 1, chest).setTile(17, 1, 1, tile)
                .addEntity(a).addEntity(b).build();
        assertEquals(withTwo.contentHash(), built.contentHash());

        Clipboard moved = plain.withEntities(List.of(entity("minecraft:armor_stand", 1.25, stand, true), b));
        assertNotEquals(withTwo.contentHash(), moved.contentHash(), "the position is content");
        Clipboard renamed = plain.withEntities(List.of(entity("minecraft:armor_stand", 1.5,
                stand.toBuilder().putString("CustomName", "\"Al\"").build(), true), b));
        assertNotEquals(withTwo.contentHash(), renamed.contentHash(), "the data is content");
        assertEquals(plain.contentHash(), withTwo.withEntities(List.of()).contentHash());
        assertEquals(2, withTwo.withAnchor(new BlockPos(3, 0, 3)).entityCount(), "entities survive an anchor change");
        assertThrows(UnsupportedOperationException.class, () -> withTwo.entities().add(a));
    }

    @Test
    void theEntityTotalCountsPassengers() {
        NbtCompound rider = NbtCompound.builder().putString("id", "minecraft:pig").build();
        NbtCompound boat = NbtCompound.builder()
                .put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(rider, rider))).build();
        Clipboard clipboard = sample(BlockPos.ORIGIN).withEntities(List.of(entity("minecraft:boat", 1.5, boat, true),
                entity("minecraft:armor_stand", 2.5, NbtCompound.EMPTY, true)));
        assertEquals(2, clipboard.entityCount(), "at the top");
        assertEquals(4, clipboard.entityTotal(), "with the boat's two riders");
        assertEquals(0, sample(BlockPos.ORIGIN).entityTotal());
        assertEquals(java.util.Set.of(), clipboard.untrustedEntityTypes(), "every entity is trusted");

        // Untrusted entities: their types and their passengers', as the game loads them, found once and kept.
        NbtCompound cart = NbtCompound.builder().putString("id", "command_block_minecart").build();
        NbtCompound carrying = NbtCompound.builder().put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(cart)))
                .build();
        Clipboard untrusted = clipboard.withEntities(List.of(entity("minecraft:boat", 3.5, carrying, false),
                entity("minecraft:armor_stand", 2.5, NbtCompound.EMPTY, true)));
        assertEquals(java.util.Set.of("minecraft:boat", "minecraft:command_block_minecart"),
                untrusted.untrustedEntityTypes());
        assertEquals(untrusted.untrustedEntityTypes(), untrusted.withAnchor(new BlockPos(1, 0, 1)).untrustedEntityTypes());
        assertEquals(3, untrusted.withSource("elsewhere").entityTotal(), "kept through a new anchor or source");
    }
}
