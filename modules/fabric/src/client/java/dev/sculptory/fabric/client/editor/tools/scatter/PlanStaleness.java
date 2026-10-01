package dev.sculptory.fabric.client.editor.tools.scatter;

/**
 * Whether a scatter plan shown by the client still stands. Pure.
 *
 * <ul>
 *   <li>{@link #EXPIRED}: the server drops a plan {@code EditorSession.SCATTER_PLAN_TTL_NANOS} after making it; the
 *       client counts from when the plan arrived, {@link #MARGIN_NANOS} early. It can no longer be committed.</li>
 *   <li>{@link #CHANGED}: blocks changed around the placements since the plan was made. It can still be committed
 *       (the server skips placements whose cells are no longer open) but may no longer be what the player wants.</li>
 * </ul>
 */
public enum PlanStaleness {
    FRESH,
    CHANGED,
    EXPIRED;

    /** A plan is treated as expired this long before the server's time limit. */
    public static final long MARGIN_NANOS = 15_000_000_000L;

    /**
     * @param receivedAt when the plan arrived
     * @param ttl the server's plan lifetime
     * @param changesAtPlan the watched region's change count when the plan was requested
     * @param changesNow its change count now
     */
    public static PlanStaleness of(long now, long receivedAt, long ttl, long changesAtPlan, long changesNow) {
        if (now - receivedAt >= ttl - MARGIN_NANOS) return EXPIRED;
        if (changesNow != changesAtPlan) return CHANGED;
        return FRESH;
    }

    public boolean stale() {
        return this != FRESH;
    }
}
