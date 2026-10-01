package dev.sculptory.core.history;

/**
 * What undo/redo does with cells changed since the edit: a different block state, or the same state holding other
 * block-entity contents ({@link TileMatcher}). Wire order: append only.
 */
public enum ConflictPolicy {
    /** Skip and count conflicting cells. */
    SKIP_CONFLICTS,
    /** Write every recorded cell regardless. */
    OVERWRITE
}
