package dev.sculptory.core.history;

import it.unimi.dsi.fastutil.longs.Long2IntLinkedOpenHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Undo and redo of biomes (protocol 6), with the rule {@link HistoryPrograms} applies to cells and {@link EntityHistory}
 * to entities. Per recorded quart, with {@code expected} the biome the step (or its undo) left, {@code target} the biome
 * being restored, and {@code live} the biome the world holds there now:
 * <ul>
 *   <li>{@code live} is already the target: nothing to do;</li>
 *   <li>{@code live} is what the step left: the target is written;</li>
 *   <li>anything else is a conflict (the quart was painted since the step): kept and counted with the kept blocks,
 *       unless the policy is {@link ConflictPolicy#OVERWRITE} (Undo anyway), which writes the target all the same.</li>
 * </ul>
 * A step's quarts are given as {@link BiomeChanges} whose {@code before} side is what is expected and whose
 * {@code after} side is the target, so millions of quarts take no more room than the record. Biomes are compared by
 * registry id. Pure decisions; the server reads, writes and records.
 */
public final class BiomeHistory {
    private BiomeHistory() {}

    /** What to do with one recorded quart. */
    public enum Action {
        /** The world already holds the target. */
        SKIP,
        /** Write the target. */
        APPLY,
        /** Keep the live biome and count it. */
        CONFLICT
    }

    /**
     * The quarts of an undo ({@code redo} false) or a redo of one entry, in the order the entry recorded them: each
     * change's {@code before} is what the world is expected to hold, its {@code after} what is restored.
     */
    public static BiomeChanges steps(HistoryEntry entry, boolean redo) {
        BiomeChanges changes = entry.record().biomeChanges();
        return redo ? changes : changes.swapped();
    }

    /**
     * The quarts of an Undo anyway (Redo anyway) over a run of entries, listed in the order their steps were applied: per
     * quart, the target of the last entry of the run recording it (the last writer wins, as for cells), with that entry's
     * expected side. Decided with {@link ConflictPolicy#OVERWRITE}.
     */
    public static BiomeChanges reapplySteps(List<HistoryEntry> run, boolean redo) {
        // Per quart: the run entry (high 32 bits) and change index (low) of its last recording.
        Long2IntLinkedOpenHashMap lastEntry = new Long2IntLinkedOpenHashMap();
        Long2IntLinkedOpenHashMap lastIndex = new Long2IntLinkedOpenHashMap();
        for (int e = 0; e < run.size(); e++) {
            BiomeChanges changes = steps(run.get(e), redo);
            for (int i = 0; i < changes.size(); i++) {
                long key = changes.key(i);
                // Removed first, so the quart takes the place of its last recording.
                lastEntry.remove(key);
                lastIndex.remove(key);
                lastEntry.put(key, e);
                lastIndex.put(key, i);
            }
        }
        BiomeChanges.Builder out = new BiomeChanges.Builder(lastEntry.size(), false);
        for (long key : lastEntry.keySet()) {
            BiomeChanges changes = steps(run.get(lastEntry.get(key)), redo);
            int i = lastIndex.get(key);
            out.add(changes.qx(i), changes.qy(i), changes.qz(i), changes.before(i), changes.after(i));
        }
        return out.build();
    }

    /**
     * Decides about one quart: {@code expected} is what the step left there, {@code target} what it restores and
     * {@code live} what the world holds now.
     */
    public static Action decide(String expected, String target, String live, ConflictPolicy policy) {
        Objects.requireNonNull(expected);
        Objects.requireNonNull(target);
        Objects.requireNonNull(live);
        Objects.requireNonNull(policy);
        if (live.equals(target)) return Action.SKIP;
        if (policy == ConflictPolicy.OVERWRITE || live.equals(expected)) return Action.APPLY;
        return Action.CONFLICT;
    }

    /**
     * A quart's packed key: 26 bits each for quart x and z (±2^25 quarts, beyond any world border) and 12 for quart y
     * (±2^11 quarts, beyond any build height).
     */
    public static long key(int qx, int qy, int qz) {
        return ((long) (qx & 0x3FFFFFF) << 38) | ((long) (qz & 0x3FFFFFF) << 12) | (qy & 0xFFF);
    }

    /** The quart x of a {@link #key}. */
    public static int keyX(long key) {
        return (int) (key >> 38);
    }

    /** The quart y of a {@link #key}. */
    public static int keyY(long key) {
        return (int) (key << 52 >> 52);
    }

    /** The quart z of a {@link #key}. */
    public static int keyZ(long key) {
        return (int) (key << 26 >> 38);
    }
}
