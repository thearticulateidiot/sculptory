package dev.sculptory.core.nbt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.buffer.NbtBytes;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class NbtIoTest {
    /** The classic example from the NBT specification: a root compound "hello world" holding name = "Bananrama". */
    private static final byte[] HELLO_WORLD = HexFormat.of().parseHex(
            "0a000b68656c6c6f20776f726c640800046e616d65000942616e616e72616d6100");

    private static NbtCompound everyType() {
        return NbtCompound.builder()
                .putByte("byte", (byte) -7)
                .putShort("short", (short) -300)
                .putInt("int", 0x01020304)
                .putLong("long", Long.MIN_VALUE + 5)
                .put("float", new NbtTag.NbtFloat(1.5f))
                .put("double", new NbtTag.NbtDouble(-2.25))
                .putByteArray("bytes", new byte[] {1, -2, 3})
                .putString("string", "plain")
                .put("list", NbtList.of(NbtTag.COMPOUND, List.of(
                        NbtCompound.builder().putString("id", "minecraft:stone").build(),
                        NbtCompound.EMPTY)))
                .put("empty", NbtList.EMPTY)
                .put("nested", NbtCompound.builder().put("deeper", NbtCompound.builder().putInt("x", 1).build()).build())
                .putIntArray("ints", new int[] {-1, 0, Integer.MAX_VALUE})
                .put("longs", new NbtTag.NbtLongArray(new long[] {Long.MAX_VALUE, 0}))
                .build();
    }

    private static byte[] bytes(NbtCompound root) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.write(out, "", root);
        return out.toByteArray();
    }

    @Test
    void readsAndWritesTheSpecificationExample() throws IOException {
        NbtIo.Root root = NbtIo.read(new ByteArrayInputStream(HELLO_WORLD), NbtLimits.DEFAULT);
        assertEquals("hello world", root.name());
        assertEquals("Bananrama", root.value().getString("name"));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.write(out, root.name(), root.value());
        assertArrayEquals(HELLO_WORLD, out.toByteArray());
    }

    @Test
    void everyTagTypeRoundTrips() throws IOException {
        NbtCompound root = everyType();
        byte[] encoded = bytes(root);
        NbtCompound decoded = NbtIo.fromBytes(encoded, NbtLimits.DEFAULT);
        assertEquals(root, decoded);
        assertEquals(List.copyOf(root.keys()), List.copyOf(decoded.keys()), "insertion order is kept");
        assertArrayEquals(encoded, bytes(decoded), "re-encoding is byte-identical");
        assertEquals(NbtTag.COMPOUND, decoded.getList("list").elementType());
    }

    @Test
    void numbersAreBigEndian() {
        byte[] encoded = NbtIo.toBytes(NbtCompound.builder().putInt("i", 0x01020304).build());
        // 0a 0000 | 03 0001 'i' | 01 02 03 04 | 00
        assertArrayEquals(HexFormat.of().parseHex("0a0000030001690102030400"), encoded);
    }

    @Test
    void stringsAreModifiedUtf8() throws IOException {
        String text = "a\u0000é😀";
        byte[] encoded = NbtIo.toBytes(NbtCompound.builder().putString("s", text).build());
        String hex = HexFormat.of().formatHex(encoded);
        assertTrue(hex.contains("c080"), "NUL is two bytes in modified UTF-8");
        assertTrue(hex.contains("eda0bdedb880"), "supplementary characters are surrogate pairs");
        assertEquals(text, NbtIo.fromBytes(encoded, NbtLimits.DEFAULT).getString("s"));
        String tooLong = "x".repeat(70_000);
        assertThrows(IllegalArgumentException.class,
                () -> NbtIo.toBytes(NbtCompound.builder().putString("s", tooLong).build()));
    }

    @Test
    void gzipRoundTripsAndIsDetected() throws IOException {
        NbtCompound root = everyType();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeGzip(out, "Schematic", root);
        byte[] gzip = out.toByteArray();
        assertEquals((byte) 0x1f, gzip[0]);
        NbtIo.Root viaGzip = NbtIo.readGzip(new ByteArrayInputStream(gzip), NbtLimits.DEFAULT);
        assertEquals("Schematic", viaGzip.name());
        assertEquals(root, viaGzip.value());
        assertEquals(root, NbtIo.readAuto(new ByteArrayInputStream(gzip), NbtLimits.DEFAULT).value());
        assertEquals(root, NbtIo.readAuto(new ByteArrayInputStream(bytes(root)), NbtLimits.DEFAULT).value());
        assertArrayEquals(bytes(root), NbtIo.gunzip(new ByteArrayInputStream(NbtIo.gzip(bytes(root))), 1 << 20));
        assertThrows(NbtException.class, () -> NbtIo.readGzip(new ByteArrayInputStream(bytes(root)), NbtLimits.DEFAULT));
    }

    @Test
    void canonicalEncodingIgnoresInsertionOrder() throws IOException {
        NbtCompound ab = NbtCompound.builder().putInt("a", 1).putInt("b", 2).build();
        NbtCompound ba = NbtCompound.builder().putInt("b", 2).putInt("a", 1).build();
        assertEquals(ab, ba);
        assertFalse(Arrays.equals(bytes(ab), bytes(ba)));
        ByteArrayOutputStream one = new ByteArrayOutputStream(), two = new ByteArrayOutputStream();
        NbtIo.writeCanonical(one, ab);
        NbtIo.writeCanonical(two, ba);
        assertArrayEquals(one.toByteArray(), two.toByteArray());
    }

    // ------------------------------------------------------------------ malformed input

    private static byte[] raw(Writer body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        body.write(out);
        out.flush();
        return bytes.toByteArray();
    }

    @FunctionalInterface
    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    /** Root compound header, one entry header, then {@code body}; the caller ends the compound. */
    private static Writer entry(byte type, Writer body) {
        return out -> {
            out.writeByte(NbtTag.COMPOUND);
            out.writeUTF("");
            out.writeByte(type);
            out.writeUTF("v");
            body.write(out);
        };
    }

    private static NbtLimitException.Limit limitOf(byte[] input, NbtLimits limits) {
        NbtLimitException e = assertThrows(NbtLimitException.class,
                () -> NbtIo.read(new ByteArrayInputStream(input), limits));
        return e.limit();
    }

    @Test
    void rejectsDeepNesting() throws IOException {
        NbtCompound deep = NbtCompound.builder().putInt("bottom", 1).build();
        for (int i = 0; i < 600; i++) deep = NbtCompound.builder().put("c", deep).build();
        byte[] encoded = bytes(deep);
        assertEquals(NbtLimitException.Limit.DEPTH, limitOf(encoded, NbtLimits.DEFAULT));
        NbtCompound shallow = NbtCompound.builder().putInt("bottom", 1).build();
        for (int i = 0; i < 510; i++) shallow = NbtCompound.builder().put("c", shallow).build();
        assertEquals(shallow, NbtIo.fromBytes(bytes(shallow), NbtLimits.DEFAULT), "511 levels are fine");
        // Lists count as nesting too.
        byte[] lists = raw(out -> {
            entry(NbtTag.LIST, o -> {}).write(out);
            for (int i = 0; i < 600; i++) {
                out.writeByte(NbtTag.LIST);
                out.writeInt(1);
            }
            out.writeByte(NbtTag.END);
            out.writeInt(0);
        });
        assertEquals(NbtLimitException.Limit.DEPTH, limitOf(lists, NbtLimits.DEFAULT));
    }

    @Test
    void rejectsHugeDeclaredLengthsBeforeAllocating() throws IOException {
        byte[] hugeArray = raw(entry(NbtTag.INT_ARRAY, out -> out.writeInt(1 << 30)));
        assertEquals(NbtLimitException.Limit.ARRAY_LENGTH, limitOf(hugeArray, NbtLimits.DEFAULT));
        // Under the length cap but more than the remaining byte budget: refused without allocating 256 MiB.
        byte[] longArray = raw(entry(NbtTag.LONG_ARRAY, out -> out.writeInt(1 << 25)));
        assertEquals(NbtLimitException.Limit.BYTES, limitOf(longArray, NbtLimits.DEFAULT));
        byte[] hugeList = raw(entry(NbtTag.LIST, out -> {
            out.writeByte(NbtTag.COMPOUND);
            out.writeInt(50_000_000);
        }));
        assertEquals(NbtLimitException.Limit.LIST_LENGTH, limitOf(hugeList, NbtLimits.DEFAULT));
        // Under the list cap, but 1M longs cannot fit in a 1 MiB budget.
        byte[] bigList = raw(entry(NbtTag.LIST, out -> {
            out.writeByte(NbtTag.LONG);
            out.writeInt(1_000_000);
        }));
        NbtLimits oneMiB = new NbtLimits(512, 1 << 20, 1 << 25, 1 << 22, 1L << 22);
        assertEquals(NbtLimitException.Limit.BYTES, limitOf(bigList, oneMiB));
    }

    @Test
    void enforcesTheByteAndTagBudgets() throws IOException {
        NbtCompound.Builder many = NbtCompound.builder();
        for (int i = 0; i < 20_000; i++) many.putInt("k" + i, i);
        byte[] encoded = bytes(many.build());
        NbtLimits small = new NbtLimits(512, 64 << 10, 1 << 20, 1 << 20, 1 << 20);
        assertEquals(NbtLimitException.Limit.BYTES, limitOf(encoded, small));
        NbtLimits fewTags = new NbtLimits(512, 1 << 30, 1 << 20, 1 << 20, 1000);
        assertEquals(NbtLimitException.Limit.TAGS, limitOf(encoded, fewTags));
        assertEquals(20_000, NbtIo.fromBytes(encoded, NbtLimits.DEFAULT).size());
    }

    @Test
    void zipBombStopsAtTheUncompressedCap() throws IOException {
        // 16 MiB of zeros in a byte array compresses to a few KiB.
        byte[] bomb = NbtIo.gzip(raw(entry(NbtTag.BYTE_ARRAY, out -> {
            out.writeInt(16 << 20);
            out.write(new byte[16 << 20]);
            out.writeByte(NbtTag.END);
        })));
        assertTrue(bomb.length < 100_000, "the bomb is small: " + bomb.length);
        NbtLimits limits = new NbtLimits(512, 1 << 20, 1 << 25, 1 << 20, 1 << 20);
        NbtLimitException refused = assertThrows(NbtLimitException.class,
                () -> NbtIo.readGzip(new ByteArrayInputStream(bomb), limits));
        assertEquals(NbtLimitException.Limit.BYTES, refused.limit());
        NbtLimitException inflate = assertThrows(NbtLimitException.class,
                () -> NbtIo.gunzip(new ByteArrayInputStream(bomb), 1 << 20));
        assertEquals(NbtLimitException.Limit.BYTES, inflate.limit());
    }

    // ------------------------------------------------------------------ heap amplification

    /** A gzip document: root {v: [count elements written by {@code element}]} of {@code type}. */
    private static byte[] gzipList(byte type, int count, Writer element) throws IOException {
        return NbtIo.gzip(raw(out -> {
            entry(NbtTag.LIST, o -> {}).write(out);
            out.writeByte(type);
            out.writeInt(count);
            for (int i = 0; i < count; i++) element.write(out);
            out.writeByte(NbtTag.END);
        }));
    }

    private static NbtLimitException.Limit refusedQuickly(byte[] gzip, NbtLimits limits) {
        long start = System.nanoTime();
        NbtLimitException e = assertThrows(NbtLimitException.class,
                () -> NbtIo.readGzip(new ByteArrayInputStream(gzip), limits));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(millis < 5_000, "refusal took " + millis + " ms");
        return e.limit();
    }

    /** The two review attack files: millions of tiny objects from a few KiB, within the tag and list caps. */
    @Test
    void heapAmplificationIsRefused() throws IOException {
        // 4,194,000 empty compounds: about 4 KiB of gzip, which used to decode to ~489 MB.
        byte[] empties = gzipList(NbtTag.COMPOUND, 4_194_000, out -> out.writeByte(NbtTag.END));
        assertTrue(empties.length < 20_000, "attack size " + empties.length);
        assertEquals(NbtLimitException.Limit.HEAP, refusedQuickly(empties, NbtLimits.DEFAULT));
        refusedQuickly(empties, NbtLimits.untrustedUpload());

        // 2,000,000 one-entry compounds {a: 0b}: about 15 KiB of gzip, which used to decode to ~581 MB.
        byte[] singles = gzipList(NbtTag.COMPOUND, 2_000_000, out -> {
            out.writeByte(NbtTag.BYTE);
            out.writeUTF("a");
            out.writeByte(0);
            out.writeByte(NbtTag.END);
        });
        assertTrue(singles.length < 40_000, "attack size " + singles.length);
        assertEquals(NbtLimitException.Limit.HEAP, refusedQuickly(singles, NbtLimits.DEFAULT));
        refusedQuickly(singles, NbtLimits.untrustedUpload());

        // Under the upload list cap: 16 lists of 250,000 empty compounds each.
        byte[] split = NbtIo.gzip(raw(out -> {
            out.writeByte(NbtTag.COMPOUND);
            out.writeUTF("");
            for (int list = 0; list < 16; list++) {
                out.writeByte(NbtTag.LIST);
                out.writeUTF("l" + list);
                out.writeByte(NbtTag.COMPOUND);
                out.writeInt(250_000);
                for (int i = 0; i < 250_000; i++) out.writeByte(NbtTag.END);
            }
            out.writeByte(NbtTag.END);
        }));
        assertEquals(NbtLimitException.Limit.HEAP, refusedQuickly(split, NbtLimits.untrustedUpload()));
    }

    @Test
    void heapBudgetCountsStringsAndArrays() throws IOException {
        NbtLimits tight = new NbtLimits(512, 1 << 20, 1 << 20, 1 << 20, 1 << 20, 10_000);
        byte[] string = bytes(NbtCompound.builder().putString("s", "x".repeat(6_000)).build());
        assertEquals(NbtLimitException.Limit.HEAP, limitOf(string, tight));
        byte[] array = bytes(NbtCompound.builder().putByteArray("b", new byte[6_000]).build());
        assertEquals(NbtLimitException.Limit.HEAP, limitOf(array, tight));
        byte[] small = bytes(NbtCompound.builder().putString("s", "x".repeat(1_000)).putByteArray("b", new byte[1_000]).build());
        assertEquals(2, NbtIo.fromBytes(small, tight).size());
        assertEquals(NbtLimits.DEFAULT_MAX_HEAP_BYTES, new NbtLimits(1, 1, 1, 1, 1).maxHeapBytes());
    }

    @Test
    void emptyContainersAndKeysAreShared() throws IOException {
        NbtCompound root = NbtCompound.builder()
                .put("list", NbtList.of(NbtTag.COMPOUND, List.of(
                        NbtCompound.EMPTY, NbtCompound.EMPTY,
                        NbtCompound.builder().putInt("key", 1).build(),
                        NbtCompound.builder().putInt("key", 2).build())))
                .put("e1", NbtList.of(NbtTag.INT, List.of()))
                .put("e2", NbtList.of(NbtTag.INT, List.of()))
                .build();
        NbtCompound decoded = NbtIo.fromBytes(bytes(root), NbtLimits.DEFAULT);
        List<NbtCompound> items = decoded.getList("list").compounds();
        assertSame(NbtCompound.EMPTY, items.get(0));
        assertSame(NbtCompound.EMPTY, items.get(1));
        assertSame(items.get(2).keys().iterator().next(), items.get(3).keys().iterator().next(), "keys are deduplicated");
        assertSame(decoded.getList("e1"), decoded.getList("e2"));
        assertEquals(NbtTag.INT, decoded.getList("e1").elementType(), "an empty list keeps its type");
        assertEquals(root, decoded);
    }

    @Test
    void arrayElementsReadWithoutCopying() {
        NbtTag.NbtIntArray ints = new NbtTag.NbtIntArray(new int[] {4, 5, 6});
        assertEquals(5, ints.get(1));
        assertEquals(7L, new NbtTag.NbtLongArray(new long[] {7}).get(0));
        assertEquals((byte) -1, new NbtTag.NbtByteArray(new byte[] {-1}).get(0));
    }

    @Test
    void rejectsMalformedInput() throws IOException {
        byte[] valid = bytes(everyType());
        for (int cut : new int[] {0, 1, 5, valid.length / 2, valid.length - 1}) {
            NbtException e = assertThrows(NbtException.class,
                    () -> NbtIo.fromBytes(Arrays.copyOf(valid, cut), NbtLimits.DEFAULT), "cut at " + cut);
            assertFalse(e instanceof NbtLimitException, "truncation is not a limit");
        }
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(NbtException.class, () -> NbtIo.fromBytes(trailing, NbtLimits.DEFAULT));
        assertThrows(NbtException.class, () -> NbtIo.fromBytes(raw(out -> {
            out.writeByte(NbtTag.STRING);
            out.writeUTF("");
            out.writeUTF("not a compound");
        }), NbtLimits.DEFAULT));
        assertThrows(NbtException.class, () -> NbtIo.fromBytes(raw(entry((byte) 13, out -> {})), NbtLimits.DEFAULT));
        assertThrows(NbtException.class, () -> NbtIo.fromBytes(raw(entry(NbtTag.BYTE_ARRAY, out -> out.writeInt(-1))),
                NbtLimits.DEFAULT));
        assertThrows(NbtException.class, () -> NbtIo.fromBytes(raw(entry(NbtTag.LIST, out -> {
            out.writeByte(NbtTag.END);
            out.writeInt(3);
        })), NbtLimits.DEFAULT));
        assertThrows(NbtException.class, () -> NbtIo.fromBytes(raw(entry(NbtTag.STRING, out -> {
            out.writeShort(2);
            out.write(new byte[] {(byte) 0xC0, 0x41});
            out.writeByte(0);
        })), NbtLimits.DEFAULT), "invalid modified UTF-8");
    }

    @Test
    void compoundAccessorsAreTypeSafe() {
        NbtCompound root = everyType();
        assertEquals(0x01020304, root.getInt("int"));
        assertEquals(-300, root.getInt("short"));
        assertNull(root.getInt("string"));
        assertNull(root.getString("int"));
        assertNull(root.getCompound("missing"));
        assertArrayEquals(new int[] {-1, 0, Integer.MAX_VALUE}, root.getIntArray("ints"));
        assertEquals(List.of("a", "b"), NbtList.ofStrings(List.of("a", "b")).strings());
        assertNull(root.getList("list").strings());
        assertThrows(IllegalArgumentException.class, () -> NbtList.of(NbtTag.INT, List.of(new NbtTag.NbtByte((byte) 1))));
        assertThrows(IllegalArgumentException.class, () -> NbtCompound.builder().put("end", NbtTag.End.INSTANCE));
        assertEquals(NbtList.EMPTY, NbtList.of(NbtTag.COMPOUND, List.of()), "empty lists are equal whatever their type");
        assertNotEquals(root, root.toBuilder().remove("int").build());
    }

    @Test
    void blockEntityBytesRoundTrip() throws IOException {
        NbtCompound items = NbtCompound.builder()
                .putInt("x", 5).putInt("y", 6).putInt("z", 7)
                .put("Items", NbtList.of(NbtTag.COMPOUND, List.of(NbtCompound.builder()
                        .putByte("Slot", (byte) 3).putString("id", "minecraft:apple").putInt("count", 2).build())))
                .putString("id", "minecraft:barrel")
                .build();
        NbtBytes tile = BlockEntityNbt.toNbtBytes("minecraft:chest", items);
        assertEquals("minecraft:chest", tile.typeId());
        NbtCompound decoded = BlockEntityNbt.decode(tile);
        assertEquals(List.of("id", "Items"), List.copyOf(decoded.keys()), "id first; x/y/z dropped");
        assertEquals("minecraft:chest", decoded.getString("id"));
        assertEquals(items.getList("Items"), decoded.getList("Items"));
        // Minecraft's NbtIo.writeCompound layout: type 10, empty name, payload.
        byte[] raw = tile.nbtBytes();
        assertEquals(NbtTag.COMPOUND, raw[0]);
        assertEquals(0, raw[1]);
        assertEquals(0, raw[2]);
        assertEquals(tile, BlockEntityNbt.toNbtBytes("minecraft:chest", decoded));

        NbtCompound reordered = NbtCompound.builder().put("Items", items.getList("Items")).putString("id", "minecraft:chest").build();
        NbtBytes other = new NbtBytes("minecraft:chest", NbtIo.toBytes(reordered));
        assertNotEquals(tile, other, "raw bytes differ with key order");
        assertArrayEquals(BlockEntityNbt.canonicalBytes(tile), BlockEntityNbt.canonicalBytes(other));
        NbtBytes junk = new NbtBytes("minecraft:chest", "junk".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(junk.nbtBytes(), BlockEntityNbt.canonicalBytes(junk), "undecodable bytes hash as they are");
        assertInstanceOf(NbtException.class, assertThrows(IOException.class, () -> BlockEntityNbt.decode(junk)));
    }
}
