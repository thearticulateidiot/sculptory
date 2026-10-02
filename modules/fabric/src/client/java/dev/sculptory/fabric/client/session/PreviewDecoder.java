package dev.sculptory.fabric.client.session;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.render.ghost.GhostVolume;
import dev.sculptory.server.net.PreviewPayload;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipException;

/**
 * Turns a {@code bspv1} preview payload (the format {@link PreviewPayload} documents) into a
 * {@link ClipboardCache.Preview}, treating the payload as hostile: a server can send anything.
 *
 * <p>The payload is read as a stream, straight into palette-compressed sections of the {@link GhostVolume}: no
 * per-section {@code int[4096]}, no list of palette strings (each resolves to a handle at once through the client's
 * {@link StateSpace}). Before anything is allocated, and again as sections arrive, every size is checked against
 * {@link Limits}: inflated bytes, palette entries, present cells, sections, and the memory the finished volume will
 * hold. Sections must come in strictly ascending (sy, sz, sx) order, as the server writes them, so none can repeat,
 * and they must hold exactly the present cells the header announces.
 * Air and states this client does not know are left out of the volume (unknown ones are counted).
 *
 * <p>Pure and thread-safe for a thread-safe state space, so it runs off the render thread; the volume it returns is
 * handed over to the render thread afterwards.
 */
public final class PreviewDecoder {
    /**
     * What a preview may cost this client.
     *
     * @param maxInflatedBytes bytes the payload may inflate to (bounds the work)
     * @param maxMemoryBytes estimated memory the decoded preview may hold
     * @param maxSections non-empty 16³ sections
     * @param maxCells present cells the header may announce
     * @param maxPalette distinct block states
     */
    public record Limits(long maxInflatedBytes, long maxMemoryBytes, int maxSections, long maxCells, int maxPalette) {
        /** 256 MiB inflated, 512 MiB decoded, 16,384 sections, 16M cells, 262,144 states. */
        public static final Limits DEFAULT = new Limits(256L << 20, 512L << 20, 16_384, 16L << 20, 1 << 18);

        public Limits {
            if (maxInflatedBytes < 1 || maxMemoryBytes < 1 || maxSections < 1 || maxCells < 1 || maxPalette < 1) {
                throw new IllegalArgumentException("Preview limits must be positive");
            }
        }
    }

    /** Memory of a decoded section besides its cells: the ghost section, its bounds and the map entry. */
    static final long SECTION_OVERHEAD = 320;
    private static final byte[] MAGIC = {'B', 'S', 'P', 'V'};
    private static final int ABSENT = PreviewPayload.ABSENT;
    /** Wire index -> handle markers. */
    private static final int SKIP = -1;
    private static final int UNKNOWN = -2;

    private PreviewDecoder() {}

    /** {@link #decode(String, byte[], StateSpace, Limits)} with {@link Limits#DEFAULT}. */
    public static ClipboardCache.Preview decode(String key, byte[] payload, StateSpace states) throws IOException {
        return decode(key, payload, states, Limits.DEFAULT);
    }

    /**
     * @param key the clipboard content hash the preview is cached under
     * @throws IOException if the payload is malformed or over a limit
     */
    public static ClipboardCache.Preview decode(String key, byte[] payload, StateSpace states, Limits limits)
            throws IOException {
        Objects.requireNonNull(key);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(states);
        Objects.requireNonNull(limits);
        if (payload.length < MAGIC.length + 1) throw new IOException("Not a Sculptory preview payload");
        for (int i = 0; i < MAGIC.length; i++) {
            if (payload[i] != MAGIC[i]) throw new IOException("Not a Sculptory preview payload");
        }
        if (payload[MAGIC.length] != PreviewPayload.FORMAT) {
            throw new IOException("Unsupported preview format " + payload[MAGIC.length]);
        }
        Inflater inflater = new Inflater();
        try (DataInputStream in = new DataInputStream(new Bounded(new InflaterInputStream(
                new ByteArrayInputStream(payload, MAGIC.length + 1, payload.length - MAGIC.length - 1), inflater, 1 << 16),
                limits.maxInflatedBytes()))) {
            return read(key, in, states, limits);
        } catch (EOFException truncated) {
            throw new IOException("Truncated preview payload", truncated);
        } finally {
            inflater.end();
        }
    }

    private static ClipboardCache.Preview read(String key, DataInputStream in, StateSpace states, Limits limits)
            throws IOException {
        BlockPos dims = new BlockPos(side(in), side(in), side(in));
        BlockPos anchor = new BlockPos(readZigzag(in), readZigzag(in), readZigzag(in));
        long cells = readVarlong(in);
        long volume = (long) dims.x() * dims.y() * dims.z();
        if (cells < 0 || cells > volume) throw new IOException("Cell count " + cells + " outside the box");
        if (cells > limits.maxCells()) throw new IOException("Preview of " + cells + " cells is over the " + limits.maxCells() + " cap");

        // Palette: resolved to handles as it is read.
        int n = readVarint(in);
        if (n < 0 || n > PreviewPayload.MAX_PALETTE || n > limits.maxPalette() || n > cells) {
            throw new IOException("Palette of " + n + " states for " + cells + " cells");
        }
        long bytes = 16 + 4L * (n + 1);
        checkMemory(bytes, limits);
        int[] handles = new int[n + 1];
        handles[ABSENT] = SKIP;
        for (int i = 1; i <= n; i++) {
            int length = readVarint(in);
            if (length < 1 || length > PreviewPayload.MAX_STATE_BYTES) throw new IOException("State string of " + length + " bytes");
            byte[] utf8 = new byte[length];
            in.readFully(utf8);
            int handle = states.parse(new String(utf8, StandardCharsets.UTF_8));
            if (handle < 0) {
                handles[i] = UNKNOWN;
            } else {
                handles[i] = StateFlags.has(states.flags(handle), StateFlags.AIR) ? SKIP : handle;
            }
        }
        int bits = in.readUnsignedByte();
        if (bits != PreviewPayload.bitsFor(n)) throw new IOException("Bits " + bits + " do not match the palette");

        // Sections: counted and costed before any is built.
        int sectionsX = (dims.x() + 15) >> 4;
        int sectionsY = (dims.y() + 15) >> 4;
        int sectionsZ = (dims.z() + 15) >> 4;
        long maxInBox = (long) sectionsX * sectionsY * sectionsZ;
        int count = readVarint(in);
        if (count < 0 || count > maxInBox) throw new IOException(count + " sections for a " + dims + " box");
        if (count > limits.maxSections()) {
            throw new IOException("Preview of " + count + " sections is over the " + limits.maxSections() + " cap");
        }
        checkMemory(bytes + (long) count * SECTION_OVERHEAD, limits);

        GhostVolume ghost = new GhostVolume(handle -> StateFlags.has(states.flags(handle), StateFlags.AIR));
        int perLong = 64 / bits;
        int words = (SectionBuffer.SIZE + perLong - 1) / perLong;
        long mask = (1L << bits) - 1;
        long unknown = 0;
        long present = 0;
        long previous = -1;
        for (int s = 0; s < count; s++) {
            int sx = readVarint(in);
            int sy = readVarint(in);
            int sz = readVarint(in);
            if (sx < 0 || sy < 0 || sz < 0 || sx >= sectionsX || sy >= sectionsY || sz >= sectionsZ) {
                throw new IOException("Section " + sx + "," + sy + "," + sz + " outside the box");
            }
            long order = ((long) sy * sectionsZ + sz) * sectionsX + sx;
            if (order <= previous) throw new IOException("Sections out of order or repeated");
            previous = order;
            int xLimit = Math.min(16, dims.x() - (sx << 4));
            int yLimit = Math.min(16, dims.y() - (sy << 4));
            int zLimit = Math.min(16, dims.z() - (sz << 4));
            boolean whole = xLimit == 16 && yLimit == 16 && zLimit == 16;
            SectionBuffer section;
            int mode = in.readUnsignedByte();
            if (mode == 0) {
                int index = readVarint(in);
                if (index < 0 || index > n) throw new IOException("Index " + index + " outside the palette");
                int handle = handles[index];
                int inside = xLimit * yLimit * zLimit;
                if (index != ABSENT) present += inside;
                if (handle == UNKNOWN) unknown += inside;
                if (handle < 0) continue;
                if (whole) {
                    section = SectionBuffer.uniform(handle);
                } else {
                    section = new SectionBuffer();
                    for (int y = 0; y < yLimit; y++) {
                        for (int z = 0; z < zLimit; z++) {
                            for (int x = 0; x < xLimit; x++) section.set(SectionBuffer.index(x, y, z), handle);
                        }
                    }
                }
            } else if (mode == 1) {
                section = new SectionBuffer();
                for (int w = 0; w < words; w++) {
                    long word = in.readLong();
                    for (int k = 0; k < perLong; k++) {
                        int i = w * perLong + k;
                        if (i >= SectionBuffer.SIZE) break;
                        int index = (int) ((word >>> (k * bits)) & mask);
                        if (index > n) throw new IOException("Index " + index + " outside the palette");
                        if (index == ABSENT) continue;
                        if (!whole && (SectionBuffer.localX(i) >= xLimit || SectionBuffer.localY(i) >= yLimit
                                || SectionBuffer.localZ(i) >= zLimit)) {
                            continue;
                        }
                        present++;
                        int handle = handles[index];
                        if (handle == UNKNOWN) {
                            unknown++;
                        } else if (handle >= 0) {
                            section.set(i, handle);
                        }
                    }
                }
                if (section.isEmpty()) continue;
            } else {
                throw new IOException("Unknown section mode " + mode);
            }
            bytes += section.estimatedBytes() + SECTION_OVERHEAD;
            checkMemory(bytes, limits);
            ghost.put(BlockBuffer.key(sx, sy, sz), section);
        }
        List<PreviewPayload.Entity> entities = PreviewPayload.readEntities(in, dims);
        if (present != cells) throw new IOException("The header says " + cells + " cells, the sections hold " + present);
        ghost.setFrame(new Box(BlockPos.ORIGIN, new BlockPos(dims.x() - 1, dims.y() - 1, dims.z() - 1)));
        return new ClipboardCache.Preview(key, dims, anchor, cells, ghost, bytes, unknown, entities);
    }

    private static void checkMemory(long bytes, Limits limits) throws IOException {
        if (bytes > limits.maxMemoryBytes()) {
            throw new IOException("Preview needs over " + limits.maxMemoryBytes() + " bytes of memory");
        }
    }

    private static int side(DataInputStream in) throws IOException {
        int v = readVarint(in);
        if (v < 1 || v > PreviewPayload.MAX_SIDE) throw new IOException("Box side " + v);
        return v;
    }

    /** An unsigned LEB128 varint of at most 32 bits. */
    private static int readVarint(DataInputStream in) throws IOException {
        long value = readVarlong(in);
        if (value < 0 || value > 0xFFFFFFFFL) throw new IOException("Varint out of range");
        return (int) value;
    }

    private static long readVarlong(DataInputStream in) throws IOException {
        long value = 0;
        for (int shift = 0; shift < 64; shift += 7) {
            int b = in.readUnsignedByte();
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
            int b;
            try {
                b = in.read();
            } catch (ZipException e) {
                throw new IOException("Corrupt preview payload", e);
            }
            if (b >= 0 && ++count > max) throw new IOException("Preview payload inflates past " + max + " bytes");
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n;
            try {
                n = in.read(buffer, offset, (int) Math.min(length, Math.max(1, max - count + 1)));
            } catch (ZipException e) {
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
