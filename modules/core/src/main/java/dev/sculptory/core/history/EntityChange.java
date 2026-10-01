package dev.sculptory.core.history;

import java.util.Objects;
import java.util.UUID;

/**
 * What one step did to one entity, by its UUID: the entity as it was before ({@code null}: it was not there) and after
 * ({@code null}: the step removed it). A cut or a move's source records {@code (state, null)}, every placement
 * {@code (null, state)} with the new entity's UUID. Undo restores {@code before} where the world still holds
 * {@code after}; redo the other way (see {@link EntityHistory}).
 *
 * <p>Both sides may be {@code null} only in a saved batch of an unfinished record (an entity placed and removed again by
 * the same edit, which cancels what an earlier batch said); records never keep such a change.
 */
public record EntityChange(UUID id, EntityState before, EntityState after) {
    public EntityChange {
        Objects.requireNonNull(id);
    }

    /** Whether the step left the entity as it found it (so the change is not kept). */
    public boolean unchanged() {
        return Objects.equals(before, after);
    }

    /** What undo ({@code redo} false) or redo expects the world to hold: what the step (or its undo) left. */
    public EntityState expected(boolean redo) {
        return redo ? before : after;
    }

    /** What undo ({@code redo} false) or redo puts back. */
    public EntityState target(boolean redo) {
        return redo ? after : before;
    }

    /** Where the entity is recorded: after the step if it stayed, else before it. */
    public EntityState any() {
        return after != null ? after : before;
    }

    /** Approximate heap footprint. */
    public long estimatedBytes() {
        return 48 + (before == null ? 0 : before.estimatedBytes()) + (after == null ? 0 : after.estimatedBytes());
    }
}
