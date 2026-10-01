package dev.sculptory.core.generate;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.CellSetFormatException;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The sparse upload payload ({@code BSGU} v1): how a
 * {@link GeneratedSource} travels from the client to the server, where it becomes the player's clipboard.
 *
 * <pre>
 * "BSGU" | u8 version=1 | varint n | n bytes: CellSet.encode() of the present cells (absolute coordinates)
 * | zlib( varint paletteCount (1..65536) | paletteCount × (varint len, UTF-8 StateSpace.format)
 *         | one varint palette index per present cell, in CellSet order )
 * </pre>
 *
 * <p>It carries block states only: there is no field for block entities or entities, and a state whose block has a
 * block entity is refused ({@link SparseUploadException.Kind#BLOCK_ENTITY}), never stripped. {@link #decode} checks
 * everything against the announced bounds and cell count and the caller's caps.
 */
public final class SparseUpload {
    public static final int FORMAT_VERSION = 1;
    /** Most distinct states one payload may carry. */
    public static final int MAX_PALETTE = 1 << 16;
    private static final byte[] MAGIC = {'B', 'S', 'G', 'U'};
    /** Inflated bytes allowed for the palette, over the cells' indices. */
    private static final long PALETTE_BYTES = 64L << 20;

    private SparseUpload() {}

    /** Encodes {@code source} (which must not be empty) with the states' {@link StateSpace#format} text. */
    public static byte[] encode(GeneratedSource source, StateSpace states) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(states);
        if (source.isEmpty()) throw new IllegalArgumentException("Nothing to upload");
        byte[] cells = source.cellSet().encode();
        ByteArrayOutputStream body = new ByteArrayOutputStream(64 + (int) Math.min(source.cells(), 1 << 20));
        Int2IntOpenHashMap indices = new Int2IntOpenHashMap();
        indices.defaultReturnValue(-1);
        List<String> palette = new ArrayList<>();
        ByteArrayOutputStream cellIndices = new ByteArrayOutputStream();
        source.forEach((x, y, z, state) -> {
            int index = indices.get(state);
            if (index < 0) {
                index = palette.size();
                indices.put(state, index);
                palette.add(states.format(state));
            }
            writeVarint(cellIndices, index);
        });
        if (palette.size() > MAX_PALETTE) throw new IllegalArgumentException("Over " + MAX_PALETTE + " block states");
        writeVarint(body, palette.size());
        for (String spec : palette) {
            byte[] text = spec.getBytes(StandardCharsets.UTF_8);
            writeVarint(body, text.length);
            body.write(text, 0, text.length);
        }
        byte[] indexBytes = cellIndices.toByteArray();
        body.write(indexBytes, 0, indexBytes.length);
        ByteArrayOutputStream out = new ByteArrayOutputStream(16 + cells.length + body.size() / 4);
        out.write(MAGIC, 0, MAGIC.length);
        out.write(FORMAT_VERSION);
        writeVarint(out, cells.length);
        out.write(cells, 0, cells.length);
        deflate(body.toByteArray(), out);
        return out.toByteArray();
    }

    /**
     * Decodes a payload announced as {@code cells} cells with exactly {@code bounds}, in {@code states}. Duplicate
     * palette entries (the same state twice) are accepted: only the indices' targets matter.
     *
     * @param maxCells the most cells accepted (the player's clipboard cap)
     * @param maxSections the most 16³ sections the cells may touch: what bounds the decode's memory (a bitmap and two
     *     section buffers per section) whatever the cell cap is
     * @throws SparseUploadException for anything off: see the class comment
     */
    public static GeneratedSource decode(byte[] data, StateSpace states, Box bounds, long cells, long maxCells,
                                         int maxSections) throws SparseUploadException {
        Objects.requireNonNull(bounds);
        if (cells < 1 || cells > maxCells) throw tooLarge(cells + " cells over the cap of " + maxCells);
        return decode(data, states, bounds, cells, maxCells, maxSections, false);
    }

    /**
     * Decodes a payload the server made of what a scatter plan's trees and features grow (the
     * {@code SCATTER_GENERATED} stream), for the ghost preview: its
     * bounds and cell count are what its cell set holds, and states with a block entity (a bee nest) are taken (the
     * payload carries no block-entity data; nothing decoded here is ever uploaded or written).
     *
     * @throws SparseUploadException for anything off, as {@link #decode(byte[], StateSpace, Box, long, long, int)}
     */
    public static GeneratedSource decodePreview(byte[] data, StateSpace states, long maxCells, int maxSections)
            throws SparseUploadException {
        return decode(data, states, null, -1, maxCells, maxSections, true);
    }

    /** With {@code bounds} null and {@code cells} -1, whatever the cell set holds within the caps. */
    private static GeneratedSource decode(byte[] data, StateSpace states, Box bounds, long cells, long maxCells,
                                          int maxSections, boolean blockEntities) throws SparseUploadException {
        Objects.requireNonNull(data);
        Objects.requireNonNull(states);
        if (maxSections < 1) throw new IllegalArgumentException("Section cap " + maxSections);
        Reader in = new Reader(data);
        if (data.length < MAGIC.length + 1 || !Arrays.equals(data, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw malformed("Not a sparse upload");
        }
        in.position = MAGIC.length;
        int version = in.u8();
        if (version != FORMAT_VERSION) throw malformed("Unsupported sparse upload version " + version);
        int cellBytes = in.varint();
        if (cellBytes > in.remaining()) throw malformed("Truncated cell set");
        byte[] cellData = Arrays.copyOfRange(data, in.position, in.position + cellBytes);
        in.position += cellBytes;
        CellSet set;
        try {
            set = CellSet.decode(cellData, CellSet.Limits.of(maxCells, maxSections));
        } catch (CellSetFormatException e) {
            throw e.tooLarge() ? tooLarge("cell set: " + e.getMessage()) : malformed("cell set: " + e.getMessage());
        }
        if (cells >= 0 && set.size() != cells) {
            throw malformed("The cell set holds " + set.size() + " cells, not " + cells);
        }
        if (set.isEmpty()) throw malformed("An empty cell set");
        if (bounds != null && !set.bounds().equals(bounds)) {
            throw malformed("The cell set's bounds " + set.bounds() + " are not " + bounds);
        }
        long inflatedCap = Math.min(Integer.MAX_VALUE - 8, 16 + 5L * set.size() + PALETTE_BYTES);
        Reader body = new Reader(inflate(data, in.position, (int) inflatedCap));
        int paletteSize = body.varint();
        if (paletteSize < 1 || paletteSize > MAX_PALETTE) throw malformed("Palette of " + paletteSize + " states");
        int[] palette = new int[paletteSize];
        for (int i = 0; i < paletteSize; i++) {
            int length = body.varint();
            if (length > BlockDescriptor.MAX_SPEC_BYTES || length > body.remaining()) throw malformed("Bad palette entry");
            String spec = new String(body.data, body.position, length, StandardCharsets.UTF_8);
            body.position += length;
            int handle;
            try {
                handle = states.parse(spec);
            } catch (RuntimeException e) {
                handle = -1;
            }
            if (handle < 0 || handle >= states.size()) {
                throw new SparseUploadException(SparseUploadException.Kind.UNKNOWN_STATE, "Unknown block state: " + clip(spec));
            }
            if (!blockEntities && StateFlags.has(states.flags(handle), StateFlags.HAS_BLOCK_ENTITY)) {
                throw new SparseUploadException(SparseUploadException.Kind.BLOCK_ENTITY,
                        "Block entities can't be generated: " + clip(spec));
            }
            palette[i] = handle;
        }
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        SparseUploadException[] failure = {null};
        GeneratedSource.forEachInOrder(set, null, (x, y, z, ignored) -> {
            if (failure[0] != null) return;
            try {
                int index = body.varint();
                if (index >= paletteSize) throw malformed("Palette index " + index + " out of range");
                out.set(x, y, z, palette[index]);
            } catch (SparseUploadException e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) throw failure[0];
        if (body.remaining() != 0) throw malformed(body.remaining() + " bytes after the last cell");
        return out.build();
    }

    private static void deflate(byte[] body, ByteArrayOutputStream out) {
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try {
            deflater.setInput(body);
            deflater.finish();
            byte[] chunk = new byte[8192];
            while (!deflater.finished()) {
                int got = deflater.deflate(chunk);
                out.write(chunk, 0, got);
            }
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] data, int offset, int max) throws SparseUploadException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, data.length - offset);
            byte[] out = new byte[Math.min(max, 64 << 10)];
            int got = 0;
            while (!inflater.finished()) {
                if (got == out.length) {
                    if (out.length == max) {
                        if (inflater.inflate(new byte[1]) > 0) throw tooLarge("Inflates past " + max + " bytes");
                        if (inflater.finished()) break;
                        throw malformed("Truncated compressed body");
                    }
                    out = Arrays.copyOf(out, (int) Math.min(max, 2L * out.length));
                }
                int n = inflater.inflate(out, got, out.length - got);
                if (n == 0 && !inflater.finished()) throw malformed("Truncated compressed body");
                got += n;
            }
            if (inflater.getRemaining() != 0) throw malformed("Bytes after the compressed body");
            return Arrays.copyOf(out, got);
        } catch (DataFormatException e) {
            throw malformed("Damaged compressed body: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private static void writeVarint(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static String clip(String spec) {
        return spec.length() <= 120 ? spec : spec.substring(0, 120) + "...";
    }

    private static SparseUploadException malformed(String message) {
        return new SparseUploadException(SparseUploadException.Kind.MALFORMED, message);
    }

    private static SparseUploadException tooLarge(String message) {
        return new SparseUploadException(SparseUploadException.Kind.TOO_LARGE, message);
    }

    /** Bounds-checked reads. */
    private static final class Reader {
        final byte[] data;
        int position;

        Reader(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - position;
        }

        int u8() throws SparseUploadException {
            if (position >= data.length) throw malformed("Truncated sparse upload");
            return data[position++] & 0xFF;
        }

        /** An unsigned LEB128 int in its shortest form, at most 5 bytes. */
        int varint() throws SparseUploadException {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = u8();
                if (shift == 28 && (b & 0x70) != 0) throw malformed("Varint too long");
                if (shift > 0 && b == 0) throw malformed("Varint not in its shortest form");
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    if (value < 0) throw malformed("Count out of range");
                    return value;
                }
            }
            throw malformed("Varint too long");
        }
    }
}
