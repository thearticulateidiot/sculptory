package dev.sculptory.core.schem;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.NbtBytes;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.testing.FakeStateSpace;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/** Clipboards and helpers the format tests share. */
final class SchematicSamples {
    static final int DATA_VERSION = 3955;
    static final SchematicMetadata METADATA = new SchematicMetadata("golden", "tester", 1234L, List.of(),
            new AssetInfo(List.of("tree", "oak"), new BlockPos(3, 1, 2), List.of(2, 0), 5), "a test build");

    private SchematicSamples() {}

    static NbtBytes chestTile(String item, int count) {
        return BlockEntityNbt.toNbtBytes("minecraft:chest", NbtCompound.builder()
                .put("Items", NbtList.of(NbtTag.COMPOUND, List.of(NbtCompound.builder()
                        .putByte("Slot", (byte) 0).putString("id", item).putInt("count", count).build())))
                .putString("CustomName", "\"Loot\"")
                .build());
    }

    /**
     * Every cell set, cycling through the state space; chests carry distinct tiles; an armor stand and an item frame
     * (hanging) come along.
     */
    static Clipboard richClipboard(FakeStateSpace states) {
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(6, 4, 5)).anchor(new BlockPos(3, 1, 2));
        int chests = 0;
        for (int y = 0; y < 4; y++) {
            for (int z = 0; z < 5; z++) {
                for (int x = 0; x < 6; x++) {
                    int state = (x + 6 * z + 30 * y + 7) % states.size();
                    builder.set(x, y, z, state);
                    if (StateFlags.has(states.flags(state), StateFlags.HAS_BLOCK_ENTITY)) {
                        builder.setTile(x, y, z, chestTile("minecraft:apple", ++chests));
                    }
                }
            }
        }
        builder.addEntity(entity("minecraft:armor_stand", 2.5, 1.0, 3.25, 90f, null,
                NbtCompound.builder().putString("CustomName", "\"Bob\"").build()));
        builder.addEntity(entity("minecraft:item_frame", 1.5, 2.5, 0.03125, 180f, new BlockPos(1, 2, 0),
                NbtCompound.builder().putByte("Facing", (byte) 3).build()));
        return builder.build();
    }

    /** Every state of the space once, in handle order (16 per row, the rest of the last row air), chests with tiles. */
    static Clipboard everyState(FakeStateSpace states) {
        int n = states.size();
        int rows = (n + 15) / 16;
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(16, 1, rows));
        for (int i = n; i < rows * 16; i++) builder.set(i % 16, 0, i / 16, states.air());
        for (int h = 0; h < n; h++) {
            builder.set(h % 16, 0, h / 16, h);
            if (StateFlags.has(states.flags(h), StateFlags.HAS_BLOCK_ENTITY)) {
                builder.setTile(h % 16, 0, h / 16, chestTile("minecraft:stick", h));
            }
        }
        return builder.build();
    }

    /** A clipboard with absent cells (a masked copy) and air present in others. */
    static Clipboard sparseClipboard(FakeStateSpace states) {
        Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(4, 3, 4)).anchor(new BlockPos(-2, 0, 1));
        int stone = states.state("minecraft:stone");
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 4; z++) {
                for (int x = 0; x < 4; x++) {
                    if ((x + z) % 3 == 0) continue; // absent
                    builder.set(x, y, z, (x + y) % 2 == 0 ? states.air() : stone);
                }
            }
        }
        return builder.build();
    }

    static EntitySnapshot entity(String type, double x, double y, double z, float yaw, BlockPos attached,
                                 NbtCompound data) {
        return new EntitySnapshot(type, x, y, z, yaw, 0f, attached, NbtIo.toBytes(data), false);
    }

    static NbtCompound decodeEntity(EntitySnapshot entity) {
        try {
            return NbtIo.fromBytes(entity.nbt(), dev.sculptory.core.nbt.NbtLimits.BLOCK_ENTITY);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A {@code {x, y, z}} compound of ints, as Litematica writes positions. */
    static NbtCompound xyz(int x, int y, int z) {
        return NbtCompound.builder().putInt("x", x).putInt("y", y).putInt("z", z).build();
    }

    /** A {@code {Name, Properties}} palette entry. */
    static NbtCompound state(String name, String... properties) {
        NbtCompound.Builder entry = NbtCompound.builder().putString("Name", name);
        if (properties.length > 0) {
            NbtCompound.Builder props = NbtCompound.builder();
            for (int i = 0; i < properties.length; i += 2) props.putString(properties[i], properties[i + 1]);
            entry.put("Properties", props.build());
        }
        return entry.build();
    }

    static NbtList compounds(NbtCompound... entries) {
        return NbtList.of(NbtTag.COMPOUND, List.of(entries));
    }

    static NbtList ints(int... values) {
        return NbtList.of(NbtTag.INT, java.util.Arrays.stream(values).mapToObj(NbtTag.NbtInt::new).toList());
    }

    /**
     * A port of Litematica's {@code LitematicaBitArray} ({@code setAt}/{@code getAt}), kept independent of the codec so
     * the tests check the codec against Litematica's own packing.
     */
    static final class ReferenceBitArray {
        final long[] longs;
        private final int bits;
        private final long max;

        ReferenceBitArray(int bits, long size) {
            this.bits = bits;
            this.max = (1L << bits) - 1L;
            this.longs = new long[(int) ((size * bits + 63L) / 64L)];
        }

        ReferenceBitArray(int bits, long[] longs) {
            this.bits = bits;
            this.max = (1L << bits) - 1L;
            this.longs = longs;
        }

        void set(long index, int value) {
            long startOffset = index * (long) bits;
            int startArrIndex = (int) (startOffset >> 6);
            int endArrIndex = (int) (((index + 1L) * (long) bits - 1L) >> 6);
            int startBitOffset = (int) (startOffset & 0x3F);
            longs[startArrIndex] = longs[startArrIndex] & ~(max << startBitOffset) | ((long) value & max) << startBitOffset;
            if (startArrIndex != endArrIndex) {
                int endOffset = 64 - startBitOffset;
                int j1 = bits - endOffset;
                longs[endArrIndex] = longs[endArrIndex] >>> j1 << j1 | ((long) value & max) >> endOffset;
            }
        }

        int get(long index) {
            long startOffset = index * (long) bits;
            int startArrIndex = (int) (startOffset >> 6);
            int endArrIndex = (int) (((index + 1L) * (long) bits - 1L) >> 6);
            int startBitOffset = (int) (startOffset & 0x3F);
            if (startArrIndex == endArrIndex) {
                return (int) (longs[startArrIndex] >>> startBitOffset & max);
            }
            int endOffset = 64 - startBitOffset;
            return (int) ((longs[startArrIndex] >>> startBitOffset | longs[endArrIndex] << endOffset) & max);
        }
    }

    /** Litematica's bits per entry for a palette of {@code n} entries. */
    static int bits(int n) {
        return Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(n - 1));
    }
}
