package dev.sculptory.core.history;

import java.util.Objects;
import java.util.UUID;

/** One undoable edit in a player's history. {@code world} is the dimension id. */
public record HistoryEntry(UUID id, UUID owner, String world, String label, EditRecord record, long createdMillis) {
    public HistoryEntry {
        Objects.requireNonNull(id);
        Objects.requireNonNull(owner);
        Objects.requireNonNull(world);
        Objects.requireNonNull(label);
        Objects.requireNonNull(record);
    }
}
