package dev.sculptory.core.history.store;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.zip.CRC32C;

/**
 * The per-player history journal file format.
 *
 * <pre>
 * file   = header record*
 * header = "BSHJ" | u16 version (1) | u16 flags | player UUID (2 × i64) | u32 CRC32C of the 24 bytes before
 * record = u32 payloadLength | u8 type | u32 CRC32C(payloadLength, type, payload) | payload
 * </pre>
 * All numbers are big-endian. Record payloads by type:
 * <pre>
 * BEGIN      entry UUID | i64 createdMillis | utf world | utf base label       an entry's data starts
 * SECTION    entry UUID | i64 section key | i32 data version | varint cells | SectionCodec body
 * ENTITIES   entry UUID | i32 data version | varint changes | EntityCodec body  a batch of the entry's entities
 * SEAL       entry UUID | utf label | i64 createdMillis | i64 heap bytes        the entry's data is complete
 * PUSH, UNDONE, REDONE, REDO_BEGIN, REDO_ABORT, EVICT, ABORT    entry UUID     stack operations
 * </pre>
 * A later {@code SECTION} of the same entry and key replaces an earlier one; a later {@code ENTITIES} change of the
 * same entry and entity UUID replaces an earlier one. Reading stops at the first record that is cut short, fails its
 * checksum or does not parse (a torn tail after a crash): what follows it is ignored.
 *
 * <p><b>Flags.</b> Bit 0 ({@link #FLAG_ENTITIES}, entities, 2026-09-27) says the file may hold {@code ENTITIES}
 * records. It is set in place (the 28-byte header rewritten) right before the first such record is appended, and by
 * compactions and rewrites that copy one, so a file without entities keeps the header earlier builds read, and a build
 * that does not know the record type leaves a file that has one alone (unknown flags) instead of cutting it off there.
 * This build reads files with or without the flag.
 *
 * <p><b>Record sizes.</b> A payload's length is checked against its type before anything is read or allocated: a stack
 * operation is exactly {@value #MARK_PAYLOAD} bytes, a {@code BEGIN} or {@code SEAL} at most {@value #MAX_DATA_PAYLOAD}
 * bytes (a UUID, two longs and two labels of at most 64 KiB), a {@code SECTION} at most {@value #MAX_SECTION_PAYLOAD}
 * bytes. A section's body is deflated and holds at most 4,096 cells: 16 KiB of palette indices, a palette of states
 * (real ones are under 200 bytes each) and block entities of at most {@link SectionCodec#MAX_TILE_BYTES} each; real
 * sections take kilobytes, a section full of large containers a few megabytes. A section that does not fit is not
 * saved (its entry stays in memory only). A scan checks a section record's checksum in 1 MiB pieces, so a damaged
 * length field can never make it allocate more than a small buffer.
 */
final class Journal {
    static final int VERSION = 1;
    /** Header flag: the file may hold {@code ENTITIES} records. */
    static final int FLAG_ENTITIES = 1;
    /** Every flag this build knows. */
    static final int KNOWN_FLAGS = FLAG_ENTITIES;
    static final int HEADER_BYTES = 28;
    static final int FRAME_BYTES = 9;
    /** Payload of a stack operation (entry UUID only). */
    static final int MARK_PAYLOAD = 16;
    /** Largest {@code BEGIN} or {@code SEAL} payload. */
    static final int MAX_DATA_PAYLOAD = 256 * 1024;
    /** Largest {@code SECTION} payload. */
    static final int MAX_SECTION_PAYLOAD = 64 << 20;
    /** Bytes of a section payload before its body: entry, key, data version and the cell count's varint. */
    static final int SECTION_HEAD_BYTES = 16 + 8 + 4 + 5;
    /** Bytes of an entities payload before its body: entry, data version and the change count's varint. */
    static final int ENTITIES_HEAD_BYTES = 16 + 4 + 5;
    /** Frame bytes of a stack operation (entry UUID only). */
    static final int MARK_BYTES = FRAME_BYTES + MARK_PAYLOAD;
    private static final byte[] MAGIC = {'B', 'S', 'H', 'J'};
    private static final int MAX_LABEL_BYTES = 64 * 1024;

    private Journal() {}

    enum Type {
        BEGIN(1), SECTION(2), SEAL(3), PUSH(4), UNDONE(5), REDONE(6), REDO_BEGIN(7), REDO_ABORT(8), EVICT(9), ABORT(10),
        ENTITIES(11);

        final int code;

        Type(int code) {
            this.code = code;
        }

        static Type of(int code) {
            for (Type type : values()) {
                if (type.code == code) return type;
            }
            return null;
        }

        boolean mark() {
            return this != BEGIN && this != SECTION && this != SEAL && this != ENTITIES;
        }

        /** Whether the payload is streamed through the checksum by a scan, never held whole. */
        boolean large() {
            return this == SECTION || this == ENTITIES;
        }

        /** Whether a payload of {@code length} bytes can be a record of this type. */
        boolean fits(int length) {
            return switch (this) {
                case SECTION -> length >= 16 + 8 + 4 + 1 + 1 && length <= MAX_SECTION_PAYLOAD;
                case ENTITIES -> length >= 16 + 4 + 1 + 1 && length <= MAX_SECTION_PAYLOAD;
                case BEGIN -> length >= 16 + 8 + 1 + 1 && length <= MAX_DATA_PAYLOAD;
                case SEAL -> length >= 16 + 1 + 8 + 8 && length <= MAX_DATA_PAYLOAD;
                default -> length == MARK_PAYLOAD;
            };
        }
    }

    /**
     * A header this build does not write (another format version, or flags it does not know): the file is left alone,
     * so a newer build can still read it after a downgrade.
     */
    static final class UnsupportedVersionException extends CorruptDataException {
        UnsupportedVersionException(String what) {
            super(what + " (this build reads history files of version " + VERSION + ")");
        }
    }

    // ---------------------------------------------------------------- header

    /** A header without flags: what every file starts with until it holds an {@code ENTITIES} record. */
    static byte[] header(UUID player) {
        return header(player, 0);
    }

    static byte[] header(UUID player, int flags) {
        if ((flags & ~KNOWN_FLAGS) != 0) throw new IllegalArgumentException("Unknown header flags " + flags);
        Bytes.Writer w = new Bytes.Writer(HEADER_BYTES);
        w.bytes(MAGIC).u16(VERSION).u16(flags).uuid(player);
        CRC32C crc = new CRC32C();
        crc.update(w.array(), 0, w.size());
        w.i32((int) crc.getValue());
        return w.toArray();
    }

    /** A checked header: whose file it is and its flags. */
    record Header(UUID player, int flags) {}

    /**
     * Checks a header. The version is read right after the magic, before anything whose layout a later version could
     * change (the header's length, its checksum, its flags), so a file of another version is never mistaken for a
     * damaged one.
     *
     * @throws UnsupportedVersionException for another version, or version 1 with flags this build does not know
     * @throws CorruptDataException otherwise
     */
    static Header readHeader(byte[] header) throws CorruptDataException {
        if (header.length < 6) throw new CorruptDataException("history file header cut short");
        if (!Arrays.equals(Arrays.copyOf(header, 4), MAGIC)) throw new CorruptDataException("not a history file");
        int version = ((header[4] & 0xFF) << 8) | (header[5] & 0xFF);
        if (version != VERSION) throw new UnsupportedVersionException("history file format version " + version);
        if (header.length < HEADER_BYTES) throw new CorruptDataException("history file header cut short");
        CRC32C crc = new CRC32C();
        crc.update(header, 0, HEADER_BYTES - 4);
        Bytes.Reader in = new Bytes.Reader(header, 6, HEADER_BYTES - 6);
        int flags = in.u16();
        UUID player = in.uuid();
        if (in.i32() != (int) crc.getValue()) throw new CorruptDataException("history file header checksum");
        if ((flags & ~KNOWN_FLAGS) != 0) throw new UnsupportedVersionException("history file flags " + flags);
        return new Header(player, flags);
    }

    // ---------------------------------------------------------------- frames

    /**
     * A whole record: frame and payload.
     *
     * @throws IllegalArgumentException if the payload does not fit its type ({@link Type#fits})
     */
    static byte[] frame(Type type, byte[] payload) {
        if (!type.fits(payload.length)) {
            throw new IllegalArgumentException(type + " record of " + payload.length + " bytes does not fit");
        }
        byte[] out = new byte[FRAME_BYTES + payload.length];
        putInt(out, 0, payload.length);
        out[4] = (byte) type.code;
        System.arraycopy(payload, 0, out, FRAME_BYTES, payload.length);
        putInt(out, 5, crc(out, payload.length));
        return out;
    }

    /** The CRC a record's frame must carry: over the length, the type and the payload of {@code record}. */
    static int crc(byte[] record, int payloadLength) {
        CRC32C crc = new CRC32C();
        crc.update(record, 0, 5);
        crc.update(record, FRAME_BYTES, payloadLength);
        return (int) crc.getValue();
    }

    static int getInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    // ---------------------------------------------------------------- payloads

    /** A decoded record. */
    sealed interface Op permits Begin, Section, Entities, Seal, Mark {
        UUID entry();
    }

    record Begin(UUID entry, long created, String world, String label) implements Op {}

    /** {@code bodyOffset} is where the section body starts within the payload. */
    record Section(UUID entry, long key, int dataVersion, int cells, int bodyOffset) implements Op {}

    /** A batch of entity changes; {@code bodyOffset} is where its body starts within the payload. */
    record Entities(UUID entry, int dataVersion, int changes, int bodyOffset) implements Op {}

    record Seal(UUID entry, String label, long created, long bytes) implements Op {}

    record Mark(Type type, UUID entry) implements Op {}

    static byte[] begin(UUID entry, long created, String world, String label) {
        return new Bytes.Writer(64).uuid(entry).i64(created).utf(world).utf(label).toArray();
    }

    static byte[] section(UUID entry, long key, int dataVersion, int cells, byte[] body) {
        return new Bytes.Writer(body.length + 40).uuid(entry).i64(key).i32(dataVersion).varint(cells).bytes(body)
                .toArray();
    }

    static byte[] entities(UUID entry, int dataVersion, int changes, byte[] body) {
        return new Bytes.Writer(body.length + 32).uuid(entry).i32(dataVersion).varint(changes).bytes(body).toArray();
    }

    static byte[] seal(UUID entry, String label, long created, long bytes) {
        return new Bytes.Writer(64).uuid(entry).utf(label).i64(created).i64(bytes).toArray();
    }

    static byte[] mark(UUID entry) {
        return new Bytes.Writer(16).uuid(Objects.requireNonNull(entry)).toArray();
    }

    /**
     * A section record's fixed fields, from the first bytes of its payload ({@code head}, at most
     * {@link #SECTION_HEAD_BYTES}): the scan reads only these and streams the rest through the checksum.
     */
    static Section decodeSectionHead(byte[] head, int payloadLength) throws CorruptDataException {
        Bytes.Reader in = new Bytes.Reader(head);
        UUID entry = in.uuid();
        long key = in.i64();
        int dataVersion = in.i32();
        int cells = in.varint(4096, "Cell count");
        int bodyOffset = in.position();
        if (payloadLength <= bodyOffset) throw new CorruptDataException("Section without a body");
        return new Section(entry, key, dataVersion, cells, bodyOffset);
    }

    /** {@link #decodeSectionHead} for an {@code ENTITIES} record (at most {@link #ENTITIES_HEAD_BYTES}). */
    static Entities decodeEntitiesHead(byte[] head, int payloadLength) throws CorruptDataException {
        Bytes.Reader in = new Bytes.Reader(head);
        UUID entry = in.uuid();
        int dataVersion = in.i32();
        int changes = in.varint(EntityCodec.MAX_CHANGES, "Entity change count");
        int bodyOffset = in.position();
        if (payloadLength <= bodyOffset) throw new CorruptDataException("Entity batch without a body");
        return new Entities(entry, dataVersion, changes, bodyOffset);
    }

    /** Decodes a payload (the bytes after the frame of {@code record}). */
    static Op decode(Type type, byte[] record, int payloadLength) throws CorruptDataException {
        Bytes.Reader in = new Bytes.Reader(record, FRAME_BYTES, payloadLength);
        UUID entry = in.uuid();
        Op op = switch (type) {
            case BEGIN -> new Begin(entry, in.i64(), in.utf(MAX_LABEL_BYTES), in.utf(MAX_LABEL_BYTES));
            case SECTION -> {
                long key = in.i64();
                int dataVersion = in.i32();
                int cells = in.varint(4096, "Cell count");
                if (in.remaining() < 1) throw new CorruptDataException("Section without a body");
                yield new Section(entry, key, dataVersion, cells, in.position() - FRAME_BYTES);
            }
            case ENTITIES -> {
                int dataVersion = in.i32();
                int changes = in.varint(EntityCodec.MAX_CHANGES, "Entity change count");
                if (in.remaining() < 1) throw new CorruptDataException("Entity batch without a body");
                yield new Entities(entry, dataVersion, changes, in.position() - FRAME_BYTES);
            }
            case SEAL -> {
                String label = in.utf(MAX_LABEL_BYTES);
                long created = in.i64();
                long bytes = in.i64();
                if (bytes < 0) throw new CorruptDataException("Negative entry size");
                yield new Seal(entry, label, created, bytes);
            }
            default -> new Mark(type, entry);
        };
        if (!(op instanceof Section) && !(op instanceof Entities)) in.expectEnd();
        return op;
    }
}
