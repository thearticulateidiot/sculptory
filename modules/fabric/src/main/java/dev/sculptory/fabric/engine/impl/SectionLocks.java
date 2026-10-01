package dev.sculptory.fabric.engine.impl;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The executor's section-lock table. Every admitted job queues on each section it reads
 * or writes; a job may run once it heads the queue of every one of its sections. Because all of a job's sections
 * are queued at once, in admission order, overlapping jobs run in FIFO order and cannot deadlock.
 *
 * <p>Keys are {@code BlockBuffer.key} section keys, scoped by a world key. Not thread-safe.
 *
 * @param <J> the job type (compared by identity)
 */
public final class SectionLocks<J> {
    private final Map<Object, Long2ObjectOpenHashMap<ArrayDeque<J>>> worlds = new HashMap<>();

    /**
     * Queues {@code job} on every key (keys must be distinct).
     *
     * @return the number of keys where another job is ahead of it; the job may run when this reaches 0
     */
    public int acquire(Object world, long[] keys, J job) {
        Objects.requireNonNull(job);
        Long2ObjectOpenHashMap<ArrayDeque<J>> table = worlds.computeIfAbsent(world, w -> new Long2ObjectOpenHashMap<>());
        int blocked = 0;
        for (long key : keys) {
            ArrayDeque<J> queue = table.get(key);
            if (queue == null) {
                queue = new ArrayDeque<>(2);
                table.put(key, queue);
            }
            if (!queue.isEmpty()) blocked++;
            queue.addLast(job);
        }
        return blocked;
    }

    /**
     * Removes {@code job} from every key's queue. For each key it headed, the next job (if any) is passed to
     * {@code becameHead} once per key it now heads.
     */
    public void release(Object world, long[] keys, J job, Consumer<J> becameHead) {
        Long2ObjectOpenHashMap<ArrayDeque<J>> table = worlds.get(world);
        if (table == null) return;
        for (long key : keys) {
            ArrayDeque<J> queue = table.get(key);
            if (queue == null) continue;
            boolean wasHead = queue.peekFirst() == job;
            removeIdentity(queue, job);
            if (queue.isEmpty()) {
                table.remove(key);
            } else if (wasHead) {
                becameHead.accept(queue.peekFirst());
            }
        }
        if (table.isEmpty()) worlds.remove(world);
    }

    /** True when any admitted job (running or queued) holds or waits for this section. */
    public boolean isLocked(Object world, long key) {
        Long2ObjectOpenHashMap<ArrayDeque<J>> table = worlds.get(world);
        return table != null && table.containsKey(key);
    }

    /** Number of locked sections across all worlds. */
    public int lockedSections() {
        int count = 0;
        for (Long2ObjectOpenHashMap<ArrayDeque<J>> table : worlds.values()) count += table.size();
        return count;
    }

    private static <J> void removeIdentity(ArrayDeque<J> queue, J job) {
        var it = queue.iterator();
        while (it.hasNext()) {
            if (it.next() == job) {
                it.remove();
                return;
            }
        }
    }
}
