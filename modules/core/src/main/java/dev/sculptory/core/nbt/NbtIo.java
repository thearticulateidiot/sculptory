package dev.sculptory.core.nbt;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UTFDataFormatException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * Binary NBT in the Java Edition file format: big-endian, strings in modified UTF-8, and a named root
 * compound ({@code type byte, name, payload}), which is also what Minecraft's {@code NbtIo.writeCompound}
 * produces (with the name {@code ""}).
 *
 * <p>Reading never trusts the input: every read is bounded by {@link NbtLimits}, and gzip input is inflated
 * only as far as the parser consumes it, so the uncompressed size cap is also the zip-bomb guard. Errors are
 * {@link NbtException} (malformed or truncated) or {@link NbtLimitException} (over a limit).
 */
public final class NbtIo {
    private static final int GZIP_MAGIC_0 = 0x1f;
    private static final int GZIP_MAGIC_1 = 0x8b;

    private NbtIo() {}

    /** A root compound and its name (files usually use {@code ""} or {@code "Schematic"}). */
    public record Root(String name, NbtCompound value) {
        public Root {
            Objects.requireNonNull(name);
            Objects.requireNonNull(value);
        }
    }

    // ------------------------------------------------------------------ reading

    /** Reads an uncompressed named root compound. Does not close {@code in}; may read ahead of the root. */
    public static Root read(InputStream in, NbtLimits limits) throws IOException {
        Objects.requireNonNull(in);
        Objects.requireNonNull(limits);
        return parse(new BufferedInputStream(in, 1 << 16), limits);
    }

    /** Reads a gzip-compressed named root compound, inflating at most {@code limits.maxBytes()} (plus read-ahead). */
    public static Root readGzip(InputStream in, NbtLimits limits) throws IOException {
        Objects.requireNonNull(in);
        Objects.requireNonNull(limits);
        return parse(new BufferedInputStream(gzipInput(in), 1 << 16), limits);
    }

    /** Reads a root compound that may or may not be gzip-compressed (detected by the gzip magic bytes). */
    public static Root readAuto(InputStream in, NbtLimits limits) throws IOException {
        Objects.requireNonNull(in);
        BufferedInputStream buffered = new BufferedInputStream(in, 1 << 16);
        return isGzip(buffered) ? readGzip(buffered, limits) : read(buffered, limits);
    }

    /** Decodes uncompressed bytes holding one named root compound; trailing bytes are an error. */
    public static NbtCompound fromBytes(byte[] bytes, NbtLimits limits) throws IOException {
        Objects.requireNonNull(bytes);
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        NbtCompound root = parse(in, limits).value();
        if (in.available() > 0) throw new NbtException(in.available() + " trailing bytes after the root compound");
        return root;
    }

    /** Whether the stream starts with the gzip magic bytes. Needs mark support; the stream is reset. */
    public static boolean isGzip(InputStream in) throws IOException {
        if (!in.markSupported()) throw new IllegalArgumentException("Stream must support mark");
        in.mark(2);
        int b0 = in.read();
        int b1 = in.read();
        in.reset();
        return b0 == GZIP_MAGIC_0 && b1 == GZIP_MAGIC_1;
    }

    /**
     * Inflates a whole gzip stream into memory, refusing to produce more than {@code maxBytes}.
     *
     * @throws NbtLimitException ({@code BYTES}) as soon as the inflated size would exceed {@code maxBytes}
     */
    public static byte[] gunzip(InputStream in, long maxBytes) throws IOException {
        Objects.requireNonNull(in);
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes");
        try (InputStream limited = new LimitedInputStream(gzipInput(in), maxBytes)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(maxBytes, 1 << 16));
            limited.transferTo(out);
            return out.toByteArray();
        } catch (ZipException | EOFException corrupt) {
            throw new NbtException("Corrupt gzip data", corrupt);
        }
    }

    private static InputStream gzipInput(InputStream in) throws IOException {
        try {
            return new GZIPInputStream(in, 1 << 16);
        } catch (ZipException | EOFException notGzip) {
            throw new NbtException("Not gzip data", notGzip);
        }
    }

    private static Root parse(InputStream source, NbtLimits limits) throws IOException {
        LimitedInputStream counted = new LimitedInputStream(source, limits.maxBytes());
        Parser parser = new Parser(new DataInputStream(counted), counted, limits);
        try {
            return parser.root();
        } catch (EOFException truncated) {
            throw new NbtException("Truncated NBT", truncated);
        } catch (UTFDataFormatException badString) {
            throw new NbtException("Malformed modified UTF-8 string", badString);
        } catch (ZipException corrupt) {
            throw new NbtException("Corrupt gzip data", corrupt);
        }
    }

    private static final class Parser {
        private final DataInputStream in;
        private final LimitedInputStream counted;
        private final NbtLimits limits;
        /** One instance per distinct compound key in the document. */
        private final HashMap<String, String> keys = new HashMap<>();
        private long tags;
        private long heap;

        Parser(DataInputStream in, LimitedInputStream counted, NbtLimits limits) {
            this.in = in;
            this.counted = counted;
            this.limits = limits;
        }

        Root root() throws IOException {
            byte type = in.readByte();
            if (type != NbtTag.COMPOUND) throw new NbtException("Root tag must be a compound, not type " + type);
            String name = in.readUTF();
            return new Root(name, (NbtCompound) payload(type, 1));
        }

        private NbtTag payload(byte type, int depth) throws IOException {
            if (++tags > limits.maxTags()) {
                throw new NbtLimitException(NbtLimitException.Limit.TAGS, "More than " + limits.maxTags() + " tags");
            }
            if (type >= NbtTag.BYTE && type <= NbtTag.DOUBLE) charge(NbtLimits.VALUE_COST);
            return switch (type) {
                case NbtTag.BYTE -> new NbtTag.NbtByte(in.readByte());
                case NbtTag.SHORT -> new NbtTag.NbtShort(in.readShort());
                case NbtTag.INT -> new NbtTag.NbtInt(in.readInt());
                case NbtTag.LONG -> new NbtTag.NbtLong(in.readLong());
                case NbtTag.FLOAT -> new NbtTag.NbtFloat(in.readFloat());
                case NbtTag.DOUBLE -> new NbtTag.NbtDouble(in.readDouble());
                case NbtTag.STRING -> {
                    String value = in.readUTF();
                    charge(NbtLimits.VALUE_COST + 2L * value.length());
                    yield new NbtTag.NbtString(value);
                }
                // Arrays are charged twice their size: the tag keeps a defensive copy of what was read.
                case NbtTag.BYTE_ARRAY -> {
                    byte[] value = new byte[arrayLength(1)];
                    in.readFully(value);
                    yield new NbtTag.NbtByteArray(value);
                }
                case NbtTag.INT_ARRAY -> {
                    int[] value = new int[arrayLength(4)];
                    for (int i = 0; i < value.length; i++) value[i] = in.readInt();
                    yield new NbtTag.NbtIntArray(value);
                }
                case NbtTag.LONG_ARRAY -> {
                    long[] value = new long[arrayLength(8)];
                    for (int i = 0; i < value.length; i++) value[i] = in.readLong();
                    yield new NbtTag.NbtLongArray(value);
                }
                case NbtTag.LIST -> list(depth);
                case NbtTag.COMPOUND -> compound(depth);
                default -> throw new NbtException("Unknown tag type " + type);
            };
        }

        private NbtCompound compound(int depth) throws IOException {
            checkDepth(depth);
            charge(NbtLimits.COMPOUND_COST);
            LinkedHashMap<String, NbtTag> entries = null;
            while (true) {
                byte type = in.readByte();
                if (type == NbtTag.END) break;
                String name = key(in.readUTF());
                charge(NbtLimits.ENTRY_COST);
                if (entries == null) entries = new LinkedHashMap<>(4);
                entries.put(name, payload(type, depth + 1));
            }
            // Every empty compound is the same immutable instance.
            return entries == null ? NbtCompound.EMPTY : NbtCompound.unchecked(entries);
        }

        /** The document's single instance of a key; a new key is charged for its string. */
        private String key(String name) throws NbtLimitException {
            String shared = keys.get(name);
            if (shared != null) return shared;
            charge(NbtLimits.VALUE_COST + NbtLimits.ENTRY_COST + 2L * name.length());
            keys.put(name, name);
            return name;
        }

        private void charge(long bytes) throws NbtLimitException {
            heap += bytes;
            if (heap > limits.maxHeapBytes()) {
                throw new NbtLimitException(NbtLimitException.Limit.HEAP,
                        "Decoded NBT would take more than " + limits.maxHeapBytes() + " bytes of heap");
            }
        }

        private NbtList list(int depth) throws IOException {
            checkDepth(depth);
            byte elementType = in.readByte();
            int length = in.readInt();
            if (elementType < NbtTag.END || elementType > NbtTag.LONG_ARRAY) {
                throw new NbtException("Unknown list element type " + elementType);
            }
            if (length < 0) throw new NbtException("Negative list length " + length);
            if (length > limits.maxListLength()) {
                throw new NbtLimitException(NbtLimitException.Limit.LIST_LENGTH,
                        "List of " + length + " > " + limits.maxListLength() + " elements");
            }
            if (elementType == NbtTag.END && length > 0) throw new NbtException("Non-empty list of END tags");
            if ((long) length * minPayloadBytes(elementType) > counted.remaining()) {
                throw new NbtLimitException(NbtLimitException.Limit.BYTES,
                        "List of " + length + " elements would exceed " + limits.maxBytes() + " bytes");
            }
            // The element slots are charged twice: the growing list and its immutable copy.
            charge(NbtLimits.LIST_COST + 16L * length);
            if (length == 0) return NbtList.emptyOf(elementType);
            List<NbtTag> items = new ArrayList<>(Math.min(length, 1024));
            for (int i = 0; i < length; i++) items.add(payload(elementType, depth + 1));
            return NbtList.unchecked(elementType, List.copyOf(items));
        }

        private int arrayLength(int elementBytes) throws IOException {
            int length = in.readInt();
            if (length < 0) throw new NbtException("Negative array length " + length);
            if (length > limits.maxArrayLength()) {
                throw new NbtLimitException(NbtLimitException.Limit.ARRAY_LENGTH,
                        "Array of " + length + " > " + limits.maxArrayLength() + " elements");
            }
            if ((long) length * elementBytes > counted.remaining()) {
                throw new NbtLimitException(NbtLimitException.Limit.BYTES,
                        "Array of " + length + " elements would exceed " + limits.maxBytes() + " bytes");
            }
            charge(NbtLimits.VALUE_COST + 2L * length * elementBytes);
            return length;
        }

        private void checkDepth(int depth) throws NbtLimitException {
            if (depth > limits.maxDepth()) {
                throw new NbtLimitException(NbtLimitException.Limit.DEPTH, "Nesting deeper than " + limits.maxDepth());
            }
        }

        private static int minPayloadBytes(byte type) {
            return switch (type) {
                case NbtTag.BYTE, NbtTag.COMPOUND -> 1;
                case NbtTag.SHORT, NbtTag.STRING -> 2;
                case NbtTag.INT, NbtTag.FLOAT, NbtTag.BYTE_ARRAY, NbtTag.INT_ARRAY, NbtTag.LONG_ARRAY -> 4;
                case NbtTag.LIST -> 5;
                case NbtTag.LONG, NbtTag.DOUBLE -> 8;
                default -> 0;
            };
        }
    }

    // ------------------------------------------------------------------ writing

    /** Writes an uncompressed named root compound. Does not close {@code out}. */
    public static void write(OutputStream out, String name, NbtCompound root) throws IOException {
        Objects.requireNonNull(out);
        Objects.requireNonNull(name);
        Objects.requireNonNull(root);
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(out, 1 << 16));
        writeRoot(data, name, root, false);
        data.flush();
    }

    /** Writes a gzip-compressed named root compound and finishes the gzip stream. Does not close {@code out}. */
    public static void writeGzip(OutputStream out, String name, NbtCompound root) throws IOException {
        Objects.requireNonNull(out);
        GZIPOutputStream gzip = new GZIPOutputStream(out, 1 << 16);
        write(gzip, name, root);
        gzip.finish();
    }

    /**
     * Writes a root compound named {@code ""} with every compound's keys in sorted order: equal compounds
     * give equal bytes, whatever their insertion order. Used for content hashing.
     */
    public static void writeCanonical(OutputStream out, NbtCompound root) throws IOException {
        DataOutputStream data = new DataOutputStream(new BufferedOutputStream(out, 1 << 12));
        writeRoot(data, "", root, true);
        data.flush();
    }

    /**
     * Encodes a root compound named {@code ""}: the {@code NbtBytes} format.
     *
     * @throws IllegalArgumentException if a string is too long to encode (over 65,535 modified UTF-8 bytes)
     */
    public static byte[] toBytes(NbtCompound root) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256);
        try {
            write(out, "", root);
        } catch (UTFDataFormatException tooLong) {
            throw new IllegalArgumentException("NBT string too long", tooLong);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return out.toByteArray();
    }

    /** Gzip-compresses {@code bytes}. */
    public static byte[] gzip(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, bytes.length / 4));
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(bytes);
        } catch (IOException impossible) {
            throw new UncheckedIOException(impossible);
        }
        return out.toByteArray();
    }

    private static void writeRoot(DataOutputStream out, String name, NbtCompound root, boolean sorted) throws IOException {
        out.writeByte(NbtTag.COMPOUND);
        out.writeUTF(name);
        writePayload(out, root, sorted);
    }

    private static void writePayload(DataOutputStream out, NbtTag tag, boolean sorted) throws IOException {
        switch (tag) {
            case NbtTag.End end -> {}
            case NbtTag.NbtByte b -> out.writeByte(b.value());
            case NbtTag.NbtShort s -> out.writeShort(s.value());
            case NbtTag.NbtInt i -> out.writeInt(i.value());
            case NbtTag.NbtLong l -> out.writeLong(l.value());
            case NbtTag.NbtFloat f -> out.writeFloat(f.value());
            case NbtTag.NbtDouble d -> out.writeDouble(d.value());
            case NbtTag.NbtString s -> out.writeUTF(s.value());
            case NbtTag.NbtByteArray array -> {
                byte[] value = array.raw();
                out.writeInt(value.length);
                out.write(value);
            }
            case NbtTag.NbtIntArray array -> {
                int[] value = array.raw();
                out.writeInt(value.length);
                for (int v : value) out.writeInt(v);
            }
            case NbtTag.NbtLongArray array -> {
                long[] value = array.raw();
                out.writeInt(value.length);
                for (long v : value) out.writeLong(v);
            }
            case NbtList list -> {
                // Canonically an empty list has no element type; otherwise keep the type as read.
                out.writeByte(sorted && list.isEmpty() ? NbtTag.END : list.elementType());
                out.writeInt(list.size());
                for (NbtTag item : list.items()) writePayload(out, item, sorted);
            }
            case NbtCompound compound -> {
                Iterable<Map.Entry<String, NbtTag>> entries = compound.entries().entrySet();
                if (sorted) {
                    List<Map.Entry<String, NbtTag>> list = new ArrayList<>(compound.entries().entrySet());
                    list.sort(Map.Entry.comparingByKey());
                    entries = list;
                }
                for (Map.Entry<String, NbtTag> entry : entries) {
                    out.writeByte(entry.getValue().type());
                    out.writeUTF(entry.getKey());
                    writePayload(out, entry.getValue(), sorted);
                }
                out.writeByte(NbtTag.END);
            }
        }
    }

    // ------------------------------------------------------------------ limits

    /** Counts consumed bytes and fails once more than {@code max} would be consumed. */
    private static final class LimitedInputStream extends FilterInputStream {
        private final long max;
        private long count;

        LimitedInputStream(InputStream in, long max) {
            super(in);
            this.max = max;
        }

        long remaining() {
            return max - count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) consumed(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) return 0;
            // Never ask for more than one byte past the limit, so a bomb is detected without inflating it.
            long allowed = Math.max(1, Math.min(length, remaining() + 1));
            int n = super.read(buffer, offset, (int) allowed);
            if (n > 0) consumed(n);
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining() + 1));
            if (skipped > 0) consumed(skipped);
            return skipped;
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        private void consumed(long n) throws NbtLimitException {
            count += n;
            if (count > max) {
                throw new NbtLimitException(NbtLimitException.Limit.BYTES, "More than " + max + " uncompressed bytes");
            }
        }
    }
}
