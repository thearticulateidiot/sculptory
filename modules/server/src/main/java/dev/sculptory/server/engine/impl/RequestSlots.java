package dev.sculptory.server.engine.impl;

import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-player limits on clipboard work in flight (previews, exports, listings, saves, loads, uploads, copies), so
 * one player cannot fill the I/O executor or hold unbounded memory. Every request holds a {@link Lease} from
 * admission until its answer; beyond the limits a request is refused with {@code QUEUE_FULL} before any work.
 * <ul>
 *   <li>at most {@value #MAX_TASKS} requests per player;</li>
 *   <li>at most one clipboard being made per player (copy, library load, upload from {@code UploadBegin} to
 *       its decode): so schematic decodes queue fairly, one per player, on the shared executor;</li>
 *   <li>at most one library write per player: a save, or a rename, move, delete or new folder (M4).</li>
 * </ul>
 * Thread-safe: leases are also released on I/O threads while the server stops.
 */
public final class RequestSlots {
    public static final int MAX_TASKS = 2;

    private final Map<UUID, Integer> tasks = new HashMap<>();
    private final Set<UUID> clipboards = new HashSet<>();
    private final Set<UUID> saves = new HashSet<>();

    /** What a request holds; {@link #release()} gives it back (once, later calls do nothing). */
    public final class Lease {
        private final UUID player;
        private final boolean clipboard;
        private final boolean save;
        private boolean released;

        private Lease(UUID player, boolean clipboard, boolean save) {
            this.player = player;
            this.clipboard = clipboard;
            this.save = save;
        }

        public void release() {
            synchronized (RequestSlots.this) {
                releaseLocked();
            }
        }

        private void releaseLocked() {
            if (released) return;
            released = true;
            tasks.computeIfPresent(player, (id, n) -> n <= 1 ? null : n - 1);
            if (clipboard) clipboards.remove(player);
            if (save) saves.remove(player);
        }

        public boolean released() {
            return released;
        }
    }

    /**
     * Takes a request slot, plus the player's clipboard or save slot.
     *
     * @throws EditRejected {@code QUEUE_FULL} when a limit is reached (nothing is taken)
     */
    public synchronized Lease acquire(UUID player, boolean clipboard, boolean save) throws EditRejected {
        if (clipboard && clipboards.contains(player)) {
            throw new EditRejected(RejectReason.QUEUE_FULL, "a clipboard is still being made");
        }
        if (save && saves.contains(player)) {
            throw new EditRejected(RejectReason.QUEUE_FULL, "a library save or change is still running");
        }
        int running = tasks.getOrDefault(player, 0);
        if (running >= MAX_TASKS) throw new EditRejected(RejectReason.QUEUE_FULL, running + " requests still running");
        tasks.put(player, running + 1);
        if (clipboard) clipboards.add(player);
        if (save) saves.add(player);
        return new Lease(player, clipboard, save);
    }

    /** Whether a clipboard of the player's is being made. */
    public synchronized boolean building(UUID player) {
        return clipboards.contains(player);
    }

    /** The player's requests in flight. */
    public synchronized int tasks(UUID player) {
        return tasks.getOrDefault(player, 0);
    }

    /** Requests in flight for everyone. */
    public synchronized int totalTasks() {
        int n = 0;
        for (int count : tasks.values()) n += count;
        return n;
    }
}
