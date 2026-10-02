package dev.sculptory.server.platform;

/**
 * How the bulk jobs' writes in one world reach the players watching them ({@link Platform#clientUpdates}): cells
 * written through a {@link WorldWriter} {@link WorldWriter#syncThrough synced through} it are collected and sent by
 * {@link #flush}, heavily changed columns whole. Server thread only.
 */
public interface ClientUpdates {
    /**
     * Sends what was collected, except heavily changed columns resent recently, which are kept for a later flush.
     *
     * @param tick a counter that grows by one per server tick
     * @param force send every column now (server stop)
     */
    void flush(long tick, boolean force);

    /**
     * The next {@link #flush} sends the columns of these sections however recently they were resent (the job that
     * wrote them has ended, so its last cells go out now).
     */
    void flushNext(long[] sectionKeys);
}
