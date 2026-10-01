package dev.sculptory.fabric.client.session;

/** A listener registration; {@link #close()} removes it and is idempotent. */
@FunctionalInterface
public interface Subscription extends AutoCloseable {
    @Override
    void close();
}
