package dev.sculptory.protocol.v2;

/** Why the server refused an op, dab, undo or redo. Shared by the engine and the wire. Wire order: append only. */
public enum RejectReason {
    /** The player lacks the permission node. */
    NO_PERMISSION,
    /** Every cell is protected (spawn protection, claims). */
    PROTECTED,
    /** A running job holds a section lock. */
    AREA_BUSY,
    /** Over a volume or radius limit. */
    TOO_LARGE,
    /** A needed chunk is not loaded and may not be loaded. */
    UNLOADED,
    /** Over a token-bucket rate limit. */
    RATE_LIMITED,
    /** Structurally valid but not applicable (unknown source, outside the world...). */
    INVALID,
    /** Too many active or queued jobs. */
    QUEUE_FULL,
    /** Nothing to undo or redo. */
    HISTORY_EMPTY,
    /** Editing is disabled in the server config. */
    DISABLED,
    /**
     * A library asset the request names (a paste source, a scatter variant) is not loaded on the server; previewing the
     * asset loads it. Distinct from {@link #INVALID} so a client re-requests the asset only then.
     */
    ASSET_NOT_LOADED,
    /**
     * An op or copy named a {@code Region.Uploaded} cell set the server does not hold (never uploaded, or dropped
     * since). The client uploads the set again and retries once.
     */
    SELECTION_NOT_LOADED
}
