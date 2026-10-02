package dev.sculptory.server.net;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * The payload of {@code CLIPBOARD_PREVIEW} and {@code ASSET_PREVIEW} streams (format {@value #FORMAT_NAME}): the
 * block states of a clipboard in its own local coordinates, untransformed (the client applies the paste transform
 * to its ghost), without block-entity data. Pure Java, so the client decodes it with the same class.
 *
 * <pre>
 * magic      4 bytes  "BSPV"
 * format     u8       1
 * body       zlib (java.util.zip Deflater/Inflater) of:
 *   dims       varint x, y, z             each 1..65535
 *   anchor     zigzag x, y, z             the local cell that lands on the paste origin (may be outside the box)
 *   cells      varlong                    present cells
 *   palette    varint n (0..2^20), then n x (varint length, UTF-8) block-state strings (StateSpace.format);
 *              wire index i+1 is palette entry i, wire index 0 means "absent" (not part of the clipboard)
 *   bits       u8                         bits per packed index: max(1, ceil(log2(n + 1)))
 *   sections   varint count, then per non-empty 16x16x16 section of the local box, ascending (sy, sz, sx):
 *     sx, sy, sz  varint                  section coordinates in the local box (cell = section * 16 + local)
 *     mode        u8                      0 = uniform: varint index (every cell of the section, including the
 *                                         part beyond dims, which is always absent, is that index);
 *                                         1 = packed: ceil(4096 / (64 / bits)) big-endian longs
 *   Packed cells: cell i = (y &lt;&lt; 8) | (z &lt;&lt; 4) | x (x fastest) sits in long i / (64 / bits) at bit
 *   (i % (64 / bits)) * bits; values never span two longs. Cells beyond dims are 0.
 *   entities   only for a clipboard holding entities; nothing else follows:
 *     varint count (1..65536) | varint n types, n x (varint length, UTF-8) entity type ids
 *     | count x (varint type index | f32 x, y, z | f32 width, height)
 *     x, y, z: the entity's position in local coordinates (it may lie up to 16 blocks outside the box); width and
 *     height: its type's size (0 &lt; size &lt;= 64), for an outline box around it
 * </pre>
 *
 * Sections missing from the list hold only absent cells. Integers are unsigned LEB128 varints; zigzag is the
 * protocol's signed varint; f32 is a big-endian IEEE float. A payload without entities is exactly what it was before
 * entities existed.
 */
public final class PreviewPayload {
    public static final String FORMAT_NAME = "bspv1";
    public static final int FORMAT = 1;
    public static final int ABSENT = 0;
    public static final int MAX_SIDE = 0xFFFF;
    public static final int MAX_PALETTE = 1 << 20;
    public static final int MAX_STATE_BYTES = 1024;
    /** Most entities a preview carries. */
    public static final int MAX_ENTITIES = 1 << 16;
    /** How far outside the box an entity's position may lie. */
    public static final int ENTITY_MARGIN = 16;
    /** Largest entity size (width or height) a preview carries. */
    public static final float MAX_ENTITY_SIZE = 64f;
    private static final byte[] MAGIC = {'B', 'S', 'P', 'V'};

    private PreviewPayload() {}

    /** An entity's type size (width, height), for its outline in a preview. */
    @FunctionalInterface
    public interface EntitySizes {
        /** Half a block square, for code that knows no entity types. */
        EntitySizes DEFAULT = typeId -> new float[] {0.5f, 0.5f};

        /** {@code {width, height}} of entities of type {@code typeId}. */
        float[] size(String typeId);
    }

    /** An entity of a preview: its type, position in local coordinates and size. */
    public record Entity(String typeId, float x, float y, float z, float width, float height) {
        public Entity {
            Objects.requireNonNull(typeId);
        }
    }

    /**
     * Decoded content. {@code sections} maps {@link BlockBuffer#key} (local section coordinates) to 4096 wire
     * indices (0 absent, else {@code palette.get(index - 1)}); {@code entities} the clipboard's entities.
     */
    public record Decoded(BlockPos dims, BlockPos anchor, long cells, List<String> palette, Map<Long, int[]> sections,
                          List<Entity> entities) {
        public Decoded {
            palette = List.copyOf(palette);
            sections = Collections.unmodifiableMap(new TreeMap<>(sections));
            entities = List.copyOf(entities);
        }

        /** Content without entities. */
        public Decoded(BlockPos dims, BlockPos anchor, long cells, List<String> palette, Map<Long, int[]> sections) {
            this(dims, anchor, cells, palette, sections, List.of());
        }

        /** The palette string at a local cell, or {@code null} when absent or outside. */
        public String get(int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x >= dims.x() || y >= dims.y() || z >= dims.z()) return null;
            int[] section = sections.get(BlockBuffer.key(x >> 4, y >> 4, z >> 4));
            if (section == null) return null;
            int index = section[SectionBuffer.index(x & 15, y & 15, z & 15)];
            return index == ABSENT ? null : palette.get(index - 1);
        }
    }

    // ================================================================== encoding

    /** Encodes a clipboard; handles are formatted with the clipboard's state space; entities half a block square. */
    public static byte[] encode(Clipboard clipboard) {
        return encode(clipboard, EntitySizes.DEFAULT);
    }

    /** Encodes a clipboard, its entities with the sizes {@code sizes} gives. */
    public static byte[] encode(Clipboard clipboard, EntitySizes sizes) {
        Objects.requireNonNull(clipboard);
        Objects.requireNonNull(sizes);
        StateSpace states = clipboard.states();
        BlockPos size = clipboard.size();
        BlockBuffer blocks = clipboard.copyBlocks();
        Int2IntOpenHashMap wire = new Int2IntOpenHashMap();
        wire.defaultReturnValue(-1);
        List<String> palette = new ArrayList<>();
        long[] keys = blocks.sortedKeys();
        // First pass: the palette, in first-use order over (sy, sz, sx, cell) so equal clipboards encode equally.
        long[] ordered = order(keys);
        for (long key : ordered) {
            SectionBuffer section = blocks.section(key);
            section.forEachPresent(i -> {
                int h = section.get(i);
                if (wire.get(h) < 0) {
                    wire.put(h, palette.size() + 1);
                    palette.add(states.format(h));
                }
            });
        }
        int bits = bitsFor(palette.size());
        ByteArrayOutputStream raw = new ByteArrayOutputStream(1 << 12);
        try {
            DataOutputStream out = new DataOutputStream(raw);
            writeVarint(out, size.x());
            writeVarint(out, size.y());
            writeVarint(out, size.z());
            writeZigzag(out, clipboard.anchor().x());
            writeZigzag(out, clipboard.anchor().y());
            writeZigzag(out, clipboard.anchor().z());
            writeVarlong(out, clipboard.cellCount());
            writeVarint(out, palette.size());
            for (String state : palette) {
                byte[] utf8 = state.getBytes(StandardCharsets.UTF_8);
                writeVarint(out, utf8.length);
                out.write(utf8);
            }
            out.writeByte(bits);
            List<Long> nonEmpty = new ArrayList<>();
            for (long key : ordered) {
                if (!blocks.section(key).isEmpty()) nonEmpty.add(key);
            }
            writeVarint(out, nonEmpty.size());
            int[] cells = new int[SectionBuffer.SIZE];
            for (long key : nonEmpty) {
                SectionBuffer section = blocks.section(key);
                Arrays.fill(cells, ABSENT);
                section.forEachPresent(i -> cells[i] = wire.get(section.get(i)));
                writeVarint(out, BlockBuffer.keyX(key));
                writeVarint(out, BlockBuffer.keyY(key));
                writeVarint(out, BlockBuffer.keyZ(key));
                boolean uniform = true;
                for (int i = 1; i < cells.length && uniform; i++) uniform = cells[i] == cells[0];
                if (uniform) {
                    out.writeByte(0);
                    writeVarint(out, cells[0]);
                } else {
                    out.writeByte(1);
                    for (long word : pack(cells, bits)) out.writeLong(word);
                }
            }
            writeEntities(out, clipboard, sizes);
            out.flush();
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        ByteArrayOutputStream payload = new ByteArrayOutputStream(raw.size() / 4 + 64);
        payload.writeBytes(MAGIC);
        payload.write(FORMAT);
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try (DeflaterOutputStream zip = new DeflaterOutputStream(payload, deflater, 1 << 16)) {
            raw.writeTo(zip);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        } finally {
            deflater.end();
        }
        return payload.toByteArray();
    }

    // ================================================================== decoding

    /**
     * Decodes a payload, checking every size against the format's caps and {@code maxInflatedBytes} before
     * allocating.
     *
     * @throws IOException for anything malformed or over a cap
     */
    public static Decoded decode(byte[] payload, long maxInflatedBytes) throws IOException {
        Objects.requireNonNull(payload);
        if (payload.length < MAGIC.length + 1 || !Arrays.equals(Arrays.copyOf(payload, MAGIC.length), MAGIC)) {
            throw new IOException("Not a Sculptory preview payload");
        }
        if (payload[MAGIC.length] != FORMAT) throw new IOException("Unsupported preview format " + payload[MAGIC.length]);
        Inflater inflater = new Inflater();
        try (DataInputStream in = new DataInputStream(new Bounded(new InflaterInputStream(
                new ByteArrayInputStream(payload, MAGIC.length + 1, payload.length - MAGIC.length - 1), inflater, 1 << 16),
                maxInflatedBytes))) {
            BlockPos dims = new BlockPos(side(in), side(in), side(in));
            BlockPos anchor = new BlockPos(readZigzag(in), readZigzag(in), readZigzag(in));
            long cells = readVarlong(in);
            long volume = (long) dims.x() * dims.y() * dims.z();
            if (cells < 0 || cells > volume) throw new IOException("Cell count " + cells + " outside the box");
            int n = readVarint(in);
            if (n < 0 || n > MAX_PALETTE) throw new IOException("Palette size " + n);
            List<String> palette = new ArrayList<>(Math.min(n, 4096));
            for (int i = 0; i < n; i++) {
                int length = readVarint(in);
                if (length < 1 || length > MAX_STATE_BYTES) throw new IOException("State string of " + length + " bytes");
                byte[] utf8 = new byte[length];
                in.readFully(utf8);
                palette.add(new String(utf8, StandardCharsets.UTF_8));
            }
            int bits = in.readUnsignedByte();
            if (bits != bitsFor(n)) throw new IOException("Bits " + bits + " do not match the palette");
            long maxSections = (long) ((dims.x() + 15) >> 4) * ((dims.y() + 15) >> 4) * ((dims.z() + 15) >> 4);
            int count = readVarint(in);
            if (count < 0 || count > maxSections) throw new IOException(count + " sections for a " + dims + " box");
            TreeMap<Long, int[]> sections = new TreeMap<>();
            int perLong = 64 / bits;
            int words = (SectionBuffer.SIZE + perLong - 1) / perLong;
            for (int s = 0; s < count; s++) {
                int sx = readVarint(in), sy = readVarint(in), sz = readVarint(in);
                if (sx < 0 || sy < 0 || sz < 0 || (long) sx << 4 >= dims.x() || (long) sy << 4 >= dims.y()
                        || (long) sz << 4 >= dims.z()) {
                    throw new IOException("Section " + sx + "," + sy + "," + sz + " outside the box");
                }
                long key = BlockBuffer.key(sx, sy, sz);
                if (sections.containsKey(key)) throw new IOException("Section listed twice");
                int[] section = new int[SectionBuffer.SIZE];
                int mode = in.readUnsignedByte();
                if (mode == 0) {
                    int index = readVarint(in);
                    if (index < 0 || index > n) throw new IOException("Index " + index + " outside the palette");
                    Arrays.fill(section, index);
                } else if (mode == 1) {
                    long mask = (1L << bits) - 1;
                    for (int w = 0; w < words; w++) {
                        long word = in.readLong();
                        for (int k = 0; k < perLong; k++) {
                            int i = w * perLong + k;
                            if (i >= SectionBuffer.SIZE) break;
                            int index = (int) ((word >>> (k * bits)) & mask);
                            if (index > n) throw new IOException("Index " + index + " outside the palette");
                            section[i] = index;
                        }
                    }
                } else {
                    throw new IOException("Unknown section mode " + mode);
                }
                clearOutside(section, sx, sy, sz, dims);
                sections.put(key, section);
            }
            List<Entity> entities = readEntities(in, dims);
            return new Decoded(dims, anchor, cells, palette, sections, entities);
        } catch (EOFException truncated) {
            throw new IOException("Truncated preview payload", truncated);
        } catch (UncheckedIOException e) {
            throw e.getCause();
        } finally {
            inflater.end();
        }
    }

    /** The entities trailer of a clipboard holding entities (nothing for one without). */
    private static void writeEntities(DataOutputStream out, Clipboard clipboard, EntitySizes sizes) throws IOException {
        List<EntitySnapshot> entities = clipboard.entities();
        if (entities.isEmpty()) return;
        if (entities.size() > MAX_ENTITIES) entities = entities.subList(0, MAX_ENTITIES);
        List<String> types = new ArrayList<>();
        Map<String, Integer> index = new java.util.HashMap<>();
        for (EntitySnapshot entity : entities) {
            if (index.putIfAbsent(entity.typeId(), types.size()) == null) types.add(entity.typeId());
        }
        writeVarint(out, entities.size());
        writeVarint(out, types.size());
        for (String type : types) {
            byte[] utf8 = type.getBytes(StandardCharsets.UTF_8);
            writeVarint(out, utf8.length);
            out.write(utf8);
        }
        for (EntitySnapshot entity : entities) {
            float[] size = sizes.size(entity.typeId());
            writeVarint(out, index.get(entity.typeId()));
            out.writeFloat((float) entity.x());
            out.writeFloat((float) entity.y());
            out.writeFloat((float) entity.z());
            out.writeFloat(clampSize(size == null ? 0.5f : size[0]));
            out.writeFloat(clampSize(size == null ? 0.5f : size[1]));
        }
    }

    private static float clampSize(float size) {
        return Float.isFinite(size) && size > 0 ? Math.min(MAX_ENTITY_SIZE, size) : 0.5f;
    }

    /**
     * Reads the entities trailer, if any, after the sections of a payload of a {@code dims} box (also the client's
     * streaming decoder); nothing may follow it.
     *
     * @throws IOException for anything malformed, over a cap, or trailing
     */
    public static List<Entity> readEntities(DataInputStream in, BlockPos dims) throws IOException {
        int first = in.read();
        if (first == -1) return List.of();
        long count = readVarlongFrom(first, in);
        if (count == 0) throw new IOException("Trailing bytes after the sections");
        if (count < 0 || count > MAX_ENTITIES) throw new IOException("Entity count " + count);
        int n = readVarint(in);
        if (n < 1 || n > count) throw new IOException("Entity type count " + n);
        List<String> types = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int length = readVarint(in);
            if (length < 1 || length > MAX_STATE_BYTES) throw new IOException("Entity type of " + length + " bytes");
            byte[] utf8 = new byte[length];
            in.readFully(utf8);
            types.add(new String(utf8, StandardCharsets.UTF_8));
        }
        List<Entity> entities = new ArrayList<>((int) Math.min(count, 4096));
        for (long e = 0; e < count; e++) {
            int type = readVarint(in);
            if (type >= n) throw new IOException("Entity type index " + type);
            float x = in.readFloat(), y = in.readFloat(), z = in.readFloat();
            float width = in.readFloat(), height = in.readFloat();
            if (!inBox(x, dims.x()) || !inBox(y, dims.y()) || !inBox(z, dims.z())) {
                throw new IOException("Entity at " + x + "," + y + "," + z + " far outside the box");
            }
            if (!(width > 0 && width <= MAX_ENTITY_SIZE) || !(height > 0 && height <= MAX_ENTITY_SIZE)) {
                throw new IOException("Entity size " + width + "x" + height);
            }
            entities.add(new Entity(types.get(type), x, y, z, width, height));
        }
        if (in.read() != -1) throw new IOException("Trailing bytes after the entities");
        return entities;
    }

    private static boolean inBox(float v, int side) {
        return Float.isFinite(v) && v >= -ENTITY_MARGIN && v <= side + ENTITY_MARGIN;
    }

    /** Bits per packed index for a palette of {@code n} entries (plus the absent index 0). */
    public static int bitsFor(int n) {
        return Math.max(1, 32 - Integer.numberOfLeadingZeros(n));
    }

    private static long[] order(long[] keys) {
        Long[] boxed = new Long[keys.length];
        for (int i = 0; i < keys.length; i++) boxed[i] = keys[i];
        Arrays.sort(boxed, (a, b) -> {
            int c = Integer.compare(BlockBuffer.keyY(a), BlockBuffer.keyY(b));
            if (c == 0) c = Integer.compare(BlockBuffer.keyZ(a), BlockBuffer.keyZ(b));
            if (c == 0) c = Integer.compare(BlockBuffer.keyX(a), BlockBuffer.keyX(b));
            return c;
        });
        long[] out = new long[keys.length];
        for (int i = 0; i < keys.length; i++) out[i] = boxed[i];
        return out;
    }

    private static long[] pack(int[] cells, int bits) {
        int perLong = 64 / bits;
        long[] words = new long[(cells.length + perLong - 1) / perLong];
        for (int i = 0; i < cells.length; i++) {
            words[i / perLong] |= ((long) cells[i]) << ((i % perLong) * bits);
        }
        return words;
    }

    private static void clearOutside(int[] section, int sx, int sy, int sz, BlockPos dims) {
        int xLimit = Math.min(16, dims.x() - (sx << 4));
        int yLimit = Math.min(16, dims.y() - (sy << 4));
        int zLimit = Math.min(16, dims.z() - (sz << 4));
        if (xLimit == 16 && yLimit == 16 && zLimit == 16) return;
        for (int i = 0; i < SectionBuffer.SIZE; i++) {
            if (SectionBuffer.localX(i) >= xLimit || SectionBuffer.localY(i) >= yLimit || SectionBuffer.localZ(i) >= zLimit) {
                section[i] = ABSENT;
            }
        }
    }

    private static int side(DataInputStream in) throws IOException {
        int v = readVarint(in);
        if (v < 1 || v > MAX_SIDE) throw new IOException("Box side " + v);
        return v;
    }

    private static void writeVarint(DataOutputStream out, int value) throws IOException {
        writeVarlong(out, value & 0xFFFFFFFFL);
    }

    private static void writeVarlong(DataOutputStream out, long value) throws IOException {
        while ((value & ~0x7FL) != 0) {
            out.writeByte((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        out.writeByte((int) value);
    }

    private static void writeZigzag(DataOutputStream out, int value) throws IOException {
        writeVarint(out, (value << 1) ^ (value >> 31));
    }

    private static int readVarint(DataInputStream in) throws IOException {
        long value = readVarlong(in);
        if (value > 0xFFFFFFFFL) throw new IOException("Varint out of range");
        return (int) value;
    }

    private static long readVarlong(DataInputStream in) throws IOException {
        return readVarlongFrom(in.readUnsignedByte(), in);
    }

    /** A varlong whose first byte, {@code first}, was read already. */
    private static long readVarlongFrom(int first, DataInputStream in) throws IOException {
        long value = 0;
        int b = first;
        for (int shift = 0; shift < 64; shift += 7) {
            if (shift > 0) b = in.readUnsignedByte();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IOException("Varint too long");
    }

    private static int readZigzag(DataInputStream in) throws IOException {
        int v = readVarint(in);
        return (v >>> 1) ^ -(v & 1);
    }

    /** Refuses to produce more than {@code max} bytes (inflation guard). */
    private static final class Bounded extends InputStream {
        private final InputStream in;
        private final long max;
        private long count;

        Bounded(InputStream in, long max) {
            this.in = in;
            this.max = max;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0 && ++count > max) throw new IOException("Preview payload inflates past " + max + " bytes");
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n;
            try {
                n = in.read(buffer, offset, (int) Math.min(length, Math.max(1, max - count + 1)));
            } catch (java.util.zip.ZipException e) {
                throw new IOException("Corrupt preview payload", e);
            }
            if (n > 0 && (count += n) > max) throw new IOException("Preview payload inflates past " + max + " bytes");
            return n;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
