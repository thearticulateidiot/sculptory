package dev.sculptory.core.history;

import dev.sculptory.core.NamespacedId;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/**
 * The biome changes of one {@link EditRecord} (protocol 6), stored compactly: per quart a packed key
 * ({@link BiomeHistory#key}) and two 16-bit indices into a small palette of biome ids, about 12 bytes a quart where a
 * list of {@link BiomeChange} objects would take about 60. It is an immutable list of {@code BiomeChange} views (each
 * {@link #get} makes one); code over many quarts reads {@link #qx}, {@link #before} and the rest by index instead.
 * Each quart is held at most once, in the order given.
 */
public final class BiomeChanges extends AbstractList<BiomeChange> implements RandomAccess {
    /** No change. */
    public static final BiomeChanges EMPTY = new BiomeChanges(new long[0], new String[0], new char[0], new char[0]);
    /** Most distinct biome ids one record holds (the indices are 16 bits). */
    public static final int MAX_IDS = 1 << 16;

    private final long[] keys;
    private final String[] ids;
    private final char[] before;
    private final char[] after;

    private BiomeChanges(long[] keys, String[] ids, char[] before, char[] after) {
        this.keys = keys;
        this.ids = ids;
        this.before = before;
        this.after = after;
    }

    /**
     * {@code changes} as a compact list: itself when it already is one.
     *
     * @throws IllegalArgumentException if a quart is listed twice
     */
    public static BiomeChanges of(List<BiomeChange> changes) {
        Objects.requireNonNull(changes);
        if (changes instanceof BiomeChanges compact) return compact;
        if (changes.isEmpty()) return EMPTY;
        Builder builder = new Builder(changes.size(), true);
        for (BiomeChange change : changes) {
            builder.add(change.qx(), change.qy(), change.qz(), change.before(), change.after());
        }
        return builder.build();
    }

    @Override
    public BiomeChange get(int index) {
        return new BiomeChange(qx(index), qy(index), qz(index), before(index), after(index));
    }

    @Override
    public int size() {
        return keys.length;
    }

    /** The packed key ({@link BiomeHistory#key}) of change {@code index}. */
    public long key(int index) {
        return keys[index];
    }

    public int qx(int index) {
        return BiomeHistory.keyX(keys[index]);
    }

    public int qy(int index) {
        return BiomeHistory.keyY(keys[index]);
    }

    public int qz(int index) {
        return BiomeHistory.keyZ(keys[index]);
    }

    /** The biome quart {@code index} held before the edit. */
    public String before(int index) {
        return ids[before[index]];
    }

    /** The biome the edit left in quart {@code index}. */
    public String after(int index) {
        return ids[after[index]];
    }

    /**
     * The same quarts with before and after swapped: what an undo writes is the {@code after} side of
     * {@code record.biomes().swapped()}. Shares the keys and ids.
     */
    public BiomeChanges swapped() {
        return new BiomeChanges(keys, ids, after, before);
    }

    /** Approximate heap footprint. */
    public long estimatedBytes() {
        if (keys.length == 0) return 0;
        long bytes = 64 + 12L * keys.length;
        for (String id : ids) bytes += 40 + 2L * id.length();
        return bytes;
    }

    /** Builds a {@link BiomeChanges}, keeping the order quarts are added in. Not thread-safe. */
    public static final class Builder {
        private long[] keys;
        private char[] before;
        private char[] after;
        private int size;
        private final List<String> ids = new ArrayList<>();
        private final Object2IntOpenHashMap<String> index = new Object2IntOpenHashMap<>();
        /** Quarts added so far, to refuse one added twice; null when the caller adds each once by construction. */
        private final LongOpenHashSet seen;

        public Builder() {
            this(16, true);
        }

        /**
         * @param expected how many quarts will be added (the arrays grow past it)
         * @param checked whether a quart added twice is refused; false only for a caller that adds each quart once by
         *     construction (a record builder's map), which saves a set of every key
         */
        public Builder(int expected, boolean checked) {
            seen = checked ? new LongOpenHashSet() : null;
            int capacity = Math.max(1, expected);
            keys = new long[capacity];
            before = new char[capacity];
            after = new char[capacity];
            index.defaultReturnValue(-1);
        }

        /**
         * Adds quart {@code (qx, qy, qz)} going from {@code beforeId} to {@code afterId}.
         *
         * @throws IllegalArgumentException if the quart was added already, an id is malformed, or there are more than
         *     {@value #MAX_IDS} ids
         */
        public Builder add(int qx, int qy, int qz, String beforeId, String afterId) {
            long key = BiomeHistory.key(qx, qy, qz);
            if (seen != null && !seen.add(key)) {
                throw new IllegalArgumentException("Quart " + qx + "," + qy + "," + qz + " listed twice");
            }
            char b = id(beforeId), a = id(afterId);
            if (size == keys.length) {
                int grown = size * 2;
                keys = Arrays.copyOf(keys, grown);
                before = Arrays.copyOf(before, grown);
                after = Arrays.copyOf(after, grown);
            }
            keys[size] = key;
            before[size] = b;
            after[size] = a;
            size++;
            return this;
        }

        public int size() {
            return size;
        }

        public BiomeChanges build() {
            if (size == 0) return EMPTY;
            return new BiomeChanges(Arrays.copyOf(keys, size), ids.toArray(new String[0]), Arrays.copyOf(before, size),
                    Arrays.copyOf(after, size));
        }

        private char id(String id) {
            int i = index.getInt(Objects.requireNonNull(id));
            if (i >= 0) return (char) i;
            new NamespacedId(id); // refuses a malformed id
            if (ids.size() == MAX_IDS) throw new IllegalArgumentException("More than " + MAX_IDS + " biome ids");
            index.put(id, ids.size());
            ids.add(id);
            return (char) (ids.size() - 1);
        }
    }
}
