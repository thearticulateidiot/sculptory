package dev.sculptory.core.history.store;

import dev.sculptory.core.history.EntityChange;
import dev.sculptory.core.history.EntityState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A batch of an entry's entity changes as bytes, the body of a journal {@code ENTITIES} record:
 * <pre>
 * u8 flags (bit 0: the rest is deflated) | [varint rawLength, if deflated] | raw
 * raw    = varint changes | changes × (entity UUID | u8 sides | [before state, if bit 0] | [after state, if bit 1])
 * state  = utf typeId | f64 x | f64 y | f64 z | varint n | n bytes NBT (the whole entity, as the game saved it)
 * </pre>
 * {@code sides} 0 is a change that cancels an earlier batch's change of the same entity (placed and removed again by the
 * same edit). A batch names each entity once. Entity data is always the server's own (read from its world), so no trust
 * is stored. Decoding checks every count, flag, text and position.
 */
public final class EntityCodec {
    /** Most changes one batch holds. */
    static final int MAX_CHANGES = 1 << 16;
    /** Longest type id stored. */
    static final int MAX_TEXT_BYTES = 1024;
    /** A batch's raw encoding is kept under this (a batch over it is split), well below a record's payload cap. */
    static final int BATCH_RAW_BYTES = 32 << 20;
    private static final int SIDE_BEFORE = 1;
    private static final int SIDE_AFTER = 2;

    private EntityCodec() {}

    /**
     * Splits {@code changes} into batches that each encode to at most {@value #BATCH_RAW_BYTES} raw bytes and hold at most
     * {@value #MAX_CHANGES} changes, in order.
     *
     * @throws IllegalArgumentException if one change alone is too large for a batch
     */
    public static List<List<EntityChange>> batches(List<EntityChange> changes) {
        List<List<EntityChange>> out = new ArrayList<>();
        List<EntityChange> batch = new ArrayList<>();
        long bytes = 5;
        for (EntityChange change : changes) {
            long size = rawSize(change);
            if (size + 5 > BATCH_RAW_BYTES) {
                throw new IllegalArgumentException("Entity " + change.id() + " is too large to save: " + size + " bytes");
            }
            if (!batch.isEmpty() && (bytes + size > BATCH_RAW_BYTES || batch.size() == MAX_CHANGES)) {
                out.add(batch);
                batch = new ArrayList<>();
                bytes = 5;
            }
            batch.add(change);
            bytes += size;
        }
        if (!batch.isEmpty()) out.add(batch);
        return out;
    }

    /** Raw bytes one change takes (an upper bound). */
    static long rawSize(EntityChange change) {
        return 17 + stateSize(change.before()) + stateSize(change.after());
    }

    private static long stateSize(EntityState state) {
        return state == null ? 0 : 2 + 5L + 3 * 8 + 5 + 3L * state.typeId().length() + state.nbtLength();
    }

    /**
     * Encodes one batch.
     *
     * @throws IllegalArgumentException if the batch holds an entity twice, more than {@value #MAX_CHANGES} changes, or is
     *     over {@value #BATCH_RAW_BYTES} raw bytes (split it with {@link #batches})
     */
    public static byte[] encode(List<EntityChange> changes) {
        if (changes.size() > MAX_CHANGES) throw new IllegalArgumentException(changes.size() + " entity changes");
        Bytes.Writer raw = new Bytes.Writer(256);
        raw.varint(changes.size());
        Set<UUID> seen = new HashSet<>();
        for (EntityChange change : changes) {
            if (!seen.add(change.id())) throw new IllegalArgumentException("Entity " + change.id() + " twice in a batch");
            raw.uuid(change.id());
            raw.u8((change.before() != null ? SIDE_BEFORE : 0) | (change.after() != null ? SIDE_AFTER : 0));
            if (change.before() != null) writeState(raw, change.before());
            if (change.after() != null) writeState(raw, change.after());
            if (raw.size() > BATCH_RAW_BYTES) throw new IllegalArgumentException("Entity batch too large: " + raw.size());
        }
        return SectionCodec.finish(raw);
    }

    private static void writeState(Bytes.Writer raw, EntityState state) {
        String typeId = state.typeId();
        if (typeId.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Entity type too long");
        }
        byte[] nbt = state.nbt();
        raw.utf(typeId)
                .i64(Double.doubleToRawLongBits(state.x()))
                .i64(Double.doubleToRawLongBits(state.y()))
                .i64(Double.doubleToRawLongBits(state.z()))
                .varint(nbt.length)
                .bytes(nbt);
    }

    /**
     * Decodes a batch of {@code expected} changes (the count the record's head gave).
     *
     * @throws CorruptDataException if the body is malformed
     */
    public static List<EntityChange> decode(byte[] body, int expected) throws CorruptDataException {
        Bytes.Reader in = new Bytes.Reader(SectionCodec.raw(body, BATCH_RAW_BYTES));
        int count = in.varint(MAX_CHANGES, "Entity change count");
        if (count != expected) throw new CorruptDataException("Entity batch holds " + count + " changes, not " + expected);
        List<EntityChange> out = new ArrayList<>(Math.min(count, 1024));
        Set<UUID> seen = new HashSet<>();
        for (int c = 0; c < count; c++) {
            UUID id = in.uuid();
            if (!seen.add(id)) throw new CorruptDataException("Entity " + id + " twice in a batch");
            int sides = in.u8();
            if ((sides & ~(SIDE_BEFORE | SIDE_AFTER)) != 0) throw new CorruptDataException("Unknown entity sides " + sides);
            EntityState before = (sides & SIDE_BEFORE) != 0 ? readState(in) : null;
            EntityState after = (sides & SIDE_AFTER) != 0 ? readState(in) : null;
            out.add(new EntityChange(id, before, after));
        }
        in.expectEnd();
        return out;
    }

    private static EntityState readState(Bytes.Reader in) throws CorruptDataException {
        String typeId = in.utf(MAX_TEXT_BYTES);
        double x = Double.longBitsToDouble(in.i64());
        double y = Double.longBitsToDouble(in.i64());
        double z = Double.longBitsToDouble(in.i64());
        byte[] nbt = in.bytes(in.varint(EntityState.MAX_NBT_BYTES, "Entity size"));
        try {
            return new EntityState(typeId, x, y, z, nbt);
        } catch (IllegalArgumentException e) {
            throw new CorruptDataException("Bad entity state: " + e.getMessage(), e);
        }
    }
}
