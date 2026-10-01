package dev.sculptory.fabric.client.session;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Simple {@link Transfer}s: already finished ones, and ones completed by hand (the mock session and tests). */
public final class Transfers {
    private Transfers() {}

    /** A transfer that has already finished with {@code reply}. */
    public static <T> Transfer<T> done(Transfer.Kind kind, String label, Reply<T> reply) {
        Manual<T> transfer = new Manual<>(kind, label, 0);
        transfer.finish(reply);
        return transfer;
    }

    /** A transfer whose progress and result are set by its owner. */
    public static final class Manual<T> implements Transfer<T> {
        private final Kind kind;
        private final String label;
        private final CompletableFuture<Reply<T>> future = new CompletableFuture<>();
        private long total;
        private long done;
        private boolean cancelled;

        public Manual(Kind kind, String label, long totalBytes) {
            this.kind = Objects.requireNonNull(kind);
            this.label = Objects.requireNonNull(label);
            this.total = totalBytes;
        }

        public void progress(long doneBytes, long totalBytes) {
            this.done = doneBytes;
            this.total = totalBytes;
        }

        /** Completes it (the first call wins). */
        public void finish(Reply<T> reply) {
            Objects.requireNonNull(reply);
            if (reply.isOk()) done = total;
            future.complete(reply);
        }

        public boolean cancelled() {
            return cancelled;
        }

        @Override
        public Kind kind() {
            return kind;
        }

        @Override
        public String label() {
            return label;
        }

        @Override
        public long doneBytes() {
            return done;
        }

        @Override
        public long totalBytes() {
            return total;
        }

        @Override
        public boolean finished() {
            return future.isDone();
        }

        @Override
        public CompletionStage<Reply<T>> result() {
            return future;
        }

        @Override
        public void cancel() {
            if (future.isDone()) return;
            cancelled = true;
            future.complete(Reply.failed(Reply.Failure.CANCELLED, ""));
        }
    }
}
