package dev.sculptory.fabric.client.session;

import java.util.concurrent.CompletionStage;

/**
 * An M2 request that moves a stream of bytes: a preview or a schematic file from the server, or a {@code .schem}
 * upload to it. Progress is readable every frame (for progress bars); {@link #result()} completes once, on the
 * render thread. Render thread only.
 *
 * @param <T> the value of a successful transfer
 */
public interface Transfer<T> {
    /** The largest file this client uploads, whatever the server allows. */
    long MAX_UPLOAD_BYTES = 64L << 20;

    /** What moves. */
    enum Kind {
        PREVIEW,
        EXPORT,
        UPLOAD
    }

    Kind kind();

    /** A short name for progress bars: a file name, or what is previewed. */
    String label();

    /** Bytes moved so far. */
    long doneBytes();

    /** Bytes in all; 0 while the size is not known yet (waiting for the server). */
    long totalBytes();

    /** Whether {@link #result()} has completed. */
    boolean finished();

    CompletionStage<Reply<T>> result();

    /**
     * Abandons the transfer: its result completes with {@link Reply.Failure#CANCELLED} (no toast) and the stream,
     * if one is open, is aborted. Nothing happens once it has finished.
     */
    void cancel();

    /** 0..1; 0 while the size is unknown. */
    default double progress() {
        long total = totalBytes();
        return total <= 0 ? 0 : Math.min(1.0, doneBytes() / (double) total);
    }
}
