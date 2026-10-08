package dev.sculptory.core.history.store;

import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.history.BiomeChange;
import dev.sculptory.core.history.BiomeHistory;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * A batch of an entry's biome changes as bytes, the body of a journal {@code BIOMES} record (protocol 6):
 * <pre>
 * u8 flags (bit 0: the rest is deflated) | [varint rawLength, if deflated] | raw
 * raw = varint changes | varint ids | ids × utf biome id | changes × (zigzag qx | zigzag qy | zigzag qz | varint before
 *       | varint after)
 * </pre>
 * Coordinates are quart coordinates (block coordinates shifted right by 2), zigzag varints; {@code before} and
 * {@code after} index the batch's ids. A change with the same id on both sides cancels an earlier batch's change of the
 * same quart (painted back by the same edit). A batch names each quart once. Decoding checks every count, index, id and
 * coordinate.
 */
public final class BiomeCodec {
    /** Most changes one batch holds. */
    static final int MAX_CHANGES = 1 << 16;
    /** Most distinct ids one batch names. */
    static final int MAX_IDS = 1 << 16;
    /** Longest biome id stored. */
    static final int MAX_TEXT_BYTES = 1024;
    /** A batch's raw size bound (a full batch with long ids stays far below it). */
    static final int BATCH_RAW_BYTES = 32 << 20;
    /** A quart x or z must stay below this in size ({@link BiomeHistory#key}'s range). */
    static final int MAX_QUART = 1 << 25;

    private BiomeCodec() {}

    /** Splits {@code changes} into batches of at most {@value #MAX_CHANGES} changes, in order. */
    public static List<List<BiomeChange>> batches(List<BiomeChange> changes) {
        List<List<BiomeChange>> out = new ArrayList<>();
        for (int from = 0; from < changes.size(); from += MAX_CHANGES) {
            out.add(changes.subList(from, Math.min(changes.size(), from + MAX_CHANGES)));
        }
        return out;
    }

    /**
     * Encodes one batch.
     *
     * @throws IllegalArgumentException if the batch holds a quart twice, more than {@value #MAX_CHANGES} changes, an id
     *     longer than {@value #MAX_TEXT_BYTES} bytes or a coordinate out of range
     */
    public static byte[] encode(List<BiomeChange> changes) {
        if (changes.size() > MAX_CHANGES) throw new IllegalArgumentException(changes.size() + " biome changes");
        Object2IntOpenHashMap<String> index = new Object2IntOpenHashMap<>();
        index.defaultReturnValue(-1);
        List<String> ids = new ArrayList<>();
        for (BiomeChange change : changes) {
            for (String id : new String[] {change.before(), change.after()}) {
                if (index.getInt(id) >= 0) continue;
                if (id.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
                    throw new IllegalArgumentException("Biome id too long");
                }
                index.put(id, ids.size());
                ids.add(id);
            }
        }
        Bytes.Writer raw = new Bytes.Writer(64 + 8 * changes.size());
        raw.varint(changes.size());
        raw.varint(ids.size());
        for (String id : ids) raw.utf(id);
        LongOpenHashSet seen = new LongOpenHashSet(changes.size());
        for (BiomeChange change : changes) {
            checkQuart(change.qx(), change.qy(), change.qz());
            if (!seen.add(BiomeHistory.key(change.qx(), change.qy(), change.qz()))) {
                throw new IllegalArgumentException("Quart " + change.qx() + "," + change.qy() + "," + change.qz()
                        + " twice in a batch");
            }
            raw.varint(zigzag(change.qx())).varint(zigzag(change.qy())).varint(zigzag(change.qz()))
                    .varint(index.getInt(change.before())).varint(index.getInt(change.after()));
        }
        return SectionCodec.finish(raw);
    }

    /**
     * Decodes a batch of {@code expected} changes (the count the record's head gave).
     *
     * @throws CorruptDataException if the body is malformed
     */
    public static List<BiomeChange> decode(byte[] body, int expected) throws CorruptDataException {
        List<BiomeChange> out = new ArrayList<>(Math.min(expected, 4096));
        decode(body, expected, (qx, qy, qz, before, after) -> out.add(new BiomeChange(qx, qy, qz, before, after)));
        return out;
    }

    /** Receives a batch's changes one by one; the id strings are shared by every change of the batch naming them. */
    @FunctionalInterface
    public interface Sink {
        void change(int qx, int qy, int qz, String before, String after) throws CorruptDataException;
    }

    /**
     * Decodes a batch of {@code expected} changes into {@code sink}, without a {@link BiomeChange} a change (a history
     * load keeps them compact).
     *
     * @throws CorruptDataException if the body is malformed (changes passed on before the fault stay passed on)
     */
    public static void decode(byte[] body, int expected, Sink sink) throws CorruptDataException {
        Bytes.Reader in = new Bytes.Reader(SectionCodec.raw(body, BATCH_RAW_BYTES));
        int count = in.varint(MAX_CHANGES, "Biome change count");
        if (count != expected) throw new CorruptDataException("Biome batch holds " + count + " changes, not " + expected);
        int idCount = in.varint(MAX_IDS, "Biome id count");
        String[] ids = new String[idCount];
        for (int i = 0; i < idCount; i++) {
            ids[i] = in.utf(MAX_TEXT_BYTES);
            try {
                new NamespacedId(ids[i]);
            } catch (IllegalArgumentException e) {
                throw new CorruptDataException("Bad biome id: " + e.getMessage(), e);
            }
        }
        LongOpenHashSet seen = new LongOpenHashSet();
        for (int c = 0; c < count; c++) {
            int qx = unzigzag(in.varint()), qy = unzigzag(in.varint()), qz = unzigzag(in.varint());
            if (!inRange(qx, qy, qz)) throw new CorruptDataException("Quart out of range: " + qx + "," + qy + "," + qz);
            if (!seen.add(BiomeHistory.key(qx, qy, qz))) {
                throw new CorruptDataException("Quart " + qx + "," + qy + "," + qz + " twice in a batch");
            }
            int before = in.varint(), after = in.varint();
            if (before >= idCount || after >= idCount) throw new CorruptDataException("Biome index out of range");
            sink.change(qx, qy, qz, ids[before], ids[after]);
        }
        in.expectEnd();
    }

    /** Whether a quart fits {@link BiomeHistory#key}: |x| and |z| below 2^25, y in -2048..2047. */
    static boolean inRange(int qx, int qy, int qz) {
        return Math.abs((long) qx) < MAX_QUART && Math.abs((long) qz) < MAX_QUART && qy >= -2048 && qy <= 2047;
    }

    private static void checkQuart(int qx, int qy, int qz) {
        if (!inRange(qx, qy, qz)) throw new IllegalArgumentException("Quart out of range: " + qx + "," + qy + "," + qz);
    }

    private static int zigzag(int v) {
        return (v << 1) ^ (v >> 31);
    }

    private static int unzigzag(int v) {
        return (v >>> 1) ^ -(v & 1);
    }
}
