package dev.sculptory.fabric.client.session;

import dev.sculptory.fabric.SculptoryMod;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** A listener list whose subscriptions remove themselves; one failing listener does not stop the others. */
final class Listeners<T> {
    private final List<T> listeners = new CopyOnWriteArrayList<>();

    Subscription add(T listener) {
        Objects.requireNonNull(listener);
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    void forEach(Consumer<T> call) {
        for (T listener : listeners) {
            try {
                call.accept(listener);
            } catch (RuntimeException e) {
                SculptoryMod.LOG.error("Sculptory: session listener failed", e);
            }
        }
    }

    static void run(Listeners<Runnable> listeners) {
        listeners.forEach(Runnable::run);
    }
}
