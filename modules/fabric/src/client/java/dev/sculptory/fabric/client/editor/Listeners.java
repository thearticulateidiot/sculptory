package dev.sculptory.fabric.client.editor;

import dev.sculptory.fabric.client.session.Subscription;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** A listener list whose registrations are {@link Subscription}s. Listeners may unsubscribe while being notified. */
public final class Listeners<L> {
    private final List<L> listeners = new CopyOnWriteArrayList<>();

    public Subscription add(L listener) {
        Objects.requireNonNull(listener);
        listeners.add(listener);
        AtomicBoolean open = new AtomicBoolean(true);
        return () -> {
            if (open.compareAndSet(true, false)) {
                listeners.remove(listener);
            }
        };
    }

    public void fire(Consumer<? super L> call) {
        for (L listener : listeners) {
            call.accept(listener);
        }
    }

    public int size() {
        return listeners.size();
    }
}
