package dev.sculptory.server.config;

/** What region ops do when they touch chunks that are not loaded. */
public enum UnloadedPolicy {
    /** Load them with an edit ticket; needs {@code sculptory.edit.unloaded}. */
    LOAD,
    /** Reject the op with {@code UNLOADED}. */
    REFUSE
}
