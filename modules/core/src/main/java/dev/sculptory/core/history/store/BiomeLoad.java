package dev.sculptory.core.history.store;

import dev.sculptory.core.history.BiomeChanges;
import dev.sculptory.core.history.BiomeHistory;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;

/**
 * An entry's biome changes as its batches are read back from the journal (protocol 6): per quart its before and after
 * as indices into one id palette, like {@link dev.sculptory.core.history.RecordBuilder}, so a large entry loads without
 * an object per quart. A later batch's change of a quart replaces an earlier one in place (quarts keep the order they
 * were first recorded in). Not thread-safe.
 */
final class BiomeLoad {
    /** Per quart ({@link BiomeHistory#key}): before index in the high half, after index in the low half. */
    private final Long2LongLinkedOpenHashMap quarts = new Long2LongLinkedOpenHashMap();
    private final List<String> ids = new ArrayList<>();
    private final Object2IntOpenHashMap<String> index = new Object2IntOpenHashMap<>();

    BiomeLoad() {
        index.defaultReturnValue(-1);
    }

    /** One change read from a batch ({@link BiomeCodec.Sink}). */
    void put(int qx, int qy, int qz, String before, String after) throws CorruptDataException {
        quarts.put(BiomeHistory.key(qx, qy, qz), ((long) id(before) << 32) | id(after));
    }

    int size() {
        return quarts.size();
    }

    /** The changes the entry keeps: those painted back (the same biome on both sides) left out. */
    BiomeChanges kept() {
        if (quarts.isEmpty()) return BiomeChanges.EMPTY;
        // Each quart is in the map once: the builder need not check for repeats.
        BiomeChanges.Builder out = new BiomeChanges.Builder(quarts.size(), false);
        for (Long2LongMap.Entry quart : quarts.long2LongEntrySet()) {
            long pair = quart.getLongValue();
            int before = (int) (pair >>> 32), after = (int) pair;
            if (before == after) continue;
            long key = quart.getLongKey();
            out.add(BiomeHistory.keyX(key), BiomeHistory.keyY(key), BiomeHistory.keyZ(key), ids.get(before),
                    ids.get(after));
        }
        return out.build();
    }

    private int id(String id) throws CorruptDataException {
        int i = index.getInt(id);
        if (i >= 0) return i;
        if (ids.size() == BiomeChanges.MAX_IDS) {
            throw new CorruptDataException("More than " + BiomeChanges.MAX_IDS + " biome ids in an entry");
        }
        i = ids.size();
        ids.add(id);
        index.put(id, i);
        return i;
    }
}
