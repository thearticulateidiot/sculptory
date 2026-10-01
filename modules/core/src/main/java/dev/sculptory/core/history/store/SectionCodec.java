package dev.sculptory.core.history.store;

import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.buffer.SectionBuffer;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import java.io.ByteArrayOutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * One section of an edit record (its {@code before} and {@code after} buffers, which hold the same cells) as bytes, the
 * body of a journal {@code SECTION} record:
 * <pre>
 * u8 flags (bit 0: the rest is deflated) | [varint rawLength, if deflated] | raw
 * raw = varint cells (0..4096)
 *     | if 0 &lt; cells &lt; 4096: u8 mode 0 (cells &lt;= 256): cells × u16 index, ascending
 *                             u8 mode 1: 64 × i64 presence bitmap (bit i of word i/64)
 *     | varint paletteSize | paletteSize × utf(state text)       (states in first-use order)
 *     | cells × (before, after) palette indices, u8 each (u16 when paletteSize &gt; 256), in ascending cell order
 *     | varint tiles | tiles × (u16 index, u8 side 0 before / 1 after, u8 trust, utf typeId, varint n, n bytes NBT)
 * </pre>
 * Tiles are in ascending (index, side) order and sit on present cells only. A section with no cells is {@code cells = 0}
 * and nothing more. Decoding checks every count, index and order, and resolves every state text through the codec.
 */
public final class SectionCodec {
    /** Longest state text or type id stored. */
    static final int MAX_TEXT_BYTES = 32 * 1024;
    /** Largest block-entity NBT stored for one cell. */
    static final int MAX_TILE_BYTES = 16 << 20;
    /** Largest raw section body accepted when inflating. */
    static final int MAX_RAW_BYTES = 256 << 20;
    private static final int FLAG_DEFLATED = 1;
    /** Bodies smaller than this are stored raw. */
    private static final int DEFLATE_MIN = 96;

    private SectionCodec() {}

    /**
     * Encodes one section's kept cells.
     *
     * @param before the cells' states and tiles before the edit, or {@code null} for a section with nothing kept
     * @param after the same cells after the edit ({@code null} with {@code before})
     * @throws IllegalArgumentException if the two buffers do not hold the same cells, or the section is larger than
     *     {@link #decode} accepts (a raw body over {@value #MAX_RAW_BYTES} bytes, a block entity over
     *     {@value #MAX_TILE_BYTES}, a text over {@value #MAX_TEXT_BYTES}): it is refused here rather than
     *     written and then dropped as damaged when read back
     */
    public static byte[] encode(SectionBuffer before, SectionBuffer after, HistoryCodec codec) {
        return encode(before, after, codec, MAX_RAW_BYTES);
    }

    /** {@link #encode(SectionBuffer, SectionBuffer, HistoryCodec)} with another raw size limit (tests). */
    static byte[] encode(SectionBuffer before, SectionBuffer after, HistoryCodec codec, int maxRawBytes) {
        Bytes.Writer raw = new Bytes.Writer(256);
        int cells = before == null ? 0 : before.presentCount();
        if (cells == 0 && after != null && !after.isEmpty()) throw new IllegalArgumentException("Unpaired section");
        raw.varint(cells);
        if (cells > 0) {
            if (after == null || after.presentCount() != cells) throw new IllegalArgumentException("Unpaired section");
            int[] index = new int[cells];
            int[] n = {0};
            before.forEachPresent(i -> index[n[0]++] = i);
            for (int i : index) {
                if (!after.has(i)) throw new IllegalArgumentException("Unpaired section: cell " + i);
            }
            if (cells < SectionBuffer.SIZE) {
                if (cells <= 256) {
                    raw.u8(0);
                    for (int i : index) raw.u16(i);
                } else {
                    raw.u8(1);
                    long[] mask = new long[SectionBuffer.SIZE / 64];
                    for (int i : index) mask[i >>> 6] |= 1L << i;
                    for (long word : mask) raw.i64(word);
                }
            }
            Int2IntOpenHashMap slots = new Int2IntOpenHashMap();
            slots.defaultReturnValue(-1);
            int[] palette = new int[16];
            int size = 0;
            int[] pairs = new int[cells * 2];
            for (int c = 0; c < cells; c++) {
                for (int side = 0; side < 2; side++) {
                    int handle = (side == 0 ? before : after).get(index[c]);
                    int slot = slots.get(handle);
                    if (slot < 0) {
                        slot = size++;
                        slots.put(handle, slot);
                        if (slot == palette.length) palette = java.util.Arrays.copyOf(palette, slot * 2);
                        palette[slot] = handle;
                    }
                    pairs[c * 2 + side] = slot;
                }
            }
            raw.varint(size);
            for (int p = 0; p < size; p++) {
                String text = codec.stateText(palette[p]);
                if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                    throw new IllegalArgumentException("State text too long: " + text.substring(0, 64) + "...");
                }
                raw.utf(text);
            }
            boolean wide = size > 256;
            for (int slot : pairs) {
                if (wide) {
                    raw.u16(slot);
                } else {
                    raw.u8(slot);
                }
            }
            writeTiles(raw, before, after, codec, maxRawBytes);
        }
        if (raw.size() > maxRawBytes) throw new IllegalArgumentException("Section too large: " + raw.size() + " bytes");
        return finish(raw);
    }

    private static void writeTiles(Bytes.Writer raw, SectionBuffer before, SectionBuffer after, HistoryCodec codec,
                                   int maxRawBytes) {
        int count = before.tileCount() + after.tileCount();
        raw.varint(count);
        if (count == 0) return;
        // Ascending (index, side): merge the two ascending tile walks.
        int[] beforeIndex = new int[before.tileCount()];
        BlockEntityData[] beforeTile = new BlockEntityData[beforeIndex.length];
        int[] afterIndex = new int[after.tileCount()];
        BlockEntityData[] afterTile = new BlockEntityData[afterIndex.length];
        int[] n = {0};
        before.forEachTile((i, t) -> {
            beforeIndex[n[0]] = i;
            beforeTile[n[0]++] = t;
        });
        n[0] = 0;
        after.forEachTile((i, t) -> {
            afterIndex[n[0]] = i;
            afterTile[n[0]++] = t;
        });
        int b = 0, a = 0;
        while (b < beforeIndex.length || a < afterIndex.length) {
            boolean takeBefore = a >= afterIndex.length || (b < beforeIndex.length && beforeIndex[b] <= afterIndex[a]);
            if (takeBefore) {
                writeTile(raw, beforeIndex[b], 0, beforeTile[b], codec, maxRawBytes);
                b++;
            } else {
                writeTile(raw, afterIndex[a], 1, afterTile[a], codec, maxRawBytes);
                a++;
            }
        }
    }

    private static void writeTile(Bytes.Writer raw, int index, int side, BlockEntityData tile, HistoryCodec codec,
                                  int maxRawBytes) {
        byte[] nbt = tile.nbtBytes();
        if (nbt.length > MAX_TILE_BYTES) throw new IllegalArgumentException("Block entity too large: " + nbt.length);
        String typeId = tile.typeId();
        if (typeId.isEmpty() || typeId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Block-entity type empty or too long");
        }
        // Checked before the body grows past the limit (a section of many large block entities).
        if ((long) raw.size() + nbt.length > maxRawBytes) {
            throw new IllegalArgumentException("Section too large: over " + maxRawBytes + " bytes");
        }
        raw.u16(index).u8(side).u8(codec.trust(tile).ordinal()).utf(typeId).varint(nbt.length).bytes(nbt);
    }

    /** The body: {@code raw} deflated when that saves space, flagged. Shared with {@link EntityCodec}. */
    static byte[] finish(Bytes.Writer raw) {
        int length = raw.size();
        if (length >= DEFLATE_MIN) {
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            try {
                deflater.setInput(raw.array(), 0, length);
                deflater.finish();
                ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, length / 4));
                byte[] chunk = new byte[8192];
                while (!deflater.finished()) {
                    int got = deflater.deflate(chunk);
                    out.write(chunk, 0, got);
                    if (out.size() >= length) break; // no gain: store raw
                }
                if (deflater.finished() && out.size() + 6 < length) {
                    Bytes.Writer body = new Bytes.Writer(out.size() + 6);
                    body.u8(FLAG_DEFLATED).varint(length);
                    byte[] packed = out.toByteArray();
                    return body.bytes(packed).toArray();
                }
            } finally {
                deflater.end();
            }
        }
        Bytes.Writer body = new Bytes.Writer(length + 1);
        body.u8(0).bytes(raw.array(), 0, length);
        return body.toArray();
    }

    /**
     * Decodes a body into {@code {before, after}}, or {@code null} for a section with nothing kept.
     *
     * @throws UnrestorableException if a state is unknown to the codec
     * @throws CorruptDataException if the body is malformed
     */
    public static SectionBuffer[] decode(byte[] body, HistoryCodec codec) throws CorruptDataException {
        Bytes.Reader in = new Bytes.Reader(raw(body));
        int cells = in.varint(SectionBuffer.SIZE, "Cell count");
        if (cells == 0) {
            in.expectEnd();
            return null;
        }
        int[] index = new int[cells];
        if (cells == SectionBuffer.SIZE) {
            for (int i = 0; i < cells; i++) index[i] = i;
        } else {
            int mode = in.u8();
            if (mode == 0) {
                if (cells > 256) throw new CorruptDataException("Index list for " + cells + " cells");
                int last = -1;
                for (int c = 0; c < cells; c++) {
                    int i = in.u16();
                    if (i <= last || i >= SectionBuffer.SIZE) throw new CorruptDataException("Cell index out of order");
                    index[c] = last = i;
                }
            } else if (mode == 1) {
                int c = 0;
                for (int w = 0; w < SectionBuffer.SIZE / 64; w++) {
                    long word = in.i64();
                    while (word != 0) {
                        if (c == cells) throw new CorruptDataException("Presence bitmap holds too many cells");
                        index[c++] = (w << 6) | Long.numberOfTrailingZeros(word);
                        word &= word - 1;
                    }
                }
                if (c != cells) throw new CorruptDataException("Presence bitmap holds " + c + " cells, not " + cells);
            } else {
                throw new CorruptDataException("Unknown presence mode " + mode);
            }
        }
        int size = in.varint(Math.min(2 * cells, 1 << 16), "Palette size");
        if (size < 1) throw new CorruptDataException("Empty palette");
        int[] palette = new int[size];
        for (int p = 0; p < size; p++) {
            String text = in.utf(MAX_TEXT_BYTES);
            int handle = codec.state(text);
            if (handle < 0) throw new UnrestorableException("unknown block state " + text);
            palette[p] = handle;
        }
        boolean wide = size > 256;
        SectionBuffer before = new SectionBuffer();
        SectionBuffer after = new SectionBuffer();
        for (int c = 0; c < cells; c++) {
            int b = wide ? in.u16() : in.u8();
            int a = wide ? in.u16() : in.u8();
            if (b >= size || a >= size) throw new CorruptDataException("Palette index out of range");
            before.set(index[c], palette[b]);
            after.set(index[c], palette[a]);
        }
        int tiles = in.varint(2 * cells, "Tile count");
        int lastKey = -1;
        for (int t = 0; t < tiles; t++) {
            int i = in.u16();
            int side = in.u8();
            if (side > 1) throw new CorruptDataException("Unknown tile side " + side);
            int key = (i << 1) | side;
            if (i >= SectionBuffer.SIZE || key <= lastKey) throw new CorruptDataException("Tile out of order");
            lastKey = key;
            HistoryCodec.Trust trust = HistoryCodec.Trust.of(in.u8());
            String typeId = in.utf(MAX_TEXT_BYTES);
            if (typeId.isEmpty()) throw new CorruptDataException("Empty block-entity type");
            byte[] nbt = in.bytes(in.varint(MAX_TILE_BYTES, "Block-entity size"));
            SectionBuffer target = side == 0 ? before : after;
            if (!target.has(i)) throw new CorruptDataException("Tile on an absent cell");
            target.setTile(i, codec.tile(typeId, nbt, trust));
        }
        in.expectEnd();
        return new SectionBuffer[] {before, after};
    }

    /** A body's raw bytes, inflated when flagged, checking every size. */
    static byte[] raw(byte[] body) throws CorruptDataException {
        return raw(body, MAX_RAW_BYTES);
    }

    /** {@link #raw(byte[])} of a body whose raw bytes are at most {@code maxRawBytes} (shared with {@link EntityCodec}). */
    static byte[] raw(byte[] body, int maxRawBytes) throws CorruptDataException {
        Bytes.Reader in = new Bytes.Reader(body);
        int flags = in.u8();
        if ((flags & ~FLAG_DEFLATED) != 0) throw new CorruptDataException("Unknown section flags " + flags);
        if ((flags & FLAG_DEFLATED) == 0) return java.util.Arrays.copyOfRange(body, 1, body.length);
        int length = in.varint(maxRawBytes, "Section size");
        // Grows with what actually inflates, so a damaged size never allocates more than the data holds.
        byte[] raw = new byte[Math.min(length, 64 * 1024)];
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(body, in.position(), in.remaining());
            int got = 0;
            while (got < length) {
                if (got == raw.length) raw = java.util.Arrays.copyOf(raw, (int) Math.min(length, 2L * raw.length));
                int n = inflater.inflate(raw, got, raw.length - got);
                if (n == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
                got += n;
            }
            // The stream's end may follow the last byte: it must end there, with nothing more to give.
            if (got == length && !inflater.finished() && inflater.inflate(new byte[1]) != 0) {
                throw new CorruptDataException("Deflated section is longer than its size");
            }
            if (got != length || !inflater.finished() || inflater.getRemaining() != 0) {
                throw new CorruptDataException("Deflated section does not match its size");
            }
        } catch (DataFormatException e) {
            throw new CorruptDataException("Bad deflated section", e);
        } finally {
            inflater.end();
        }
        return raw;
    }
}
