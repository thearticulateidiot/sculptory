package dev.sculptory.fabric.engine.impl;

import dev.sculptory.server.config.SculptoryConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Each player's budget of blocking time for scatter previews (time their holds keep someone else out): a token
 * bucket of {@code scatter.holdBudgetSeconds} that blocking uses up and that refills at
 * {@code scatter.holdRefillShare} seconds per second. A charge may overdraw it, down to minus one full budget, so one
 * long block is paid back before the next preview. Times are on the service clock ({@code System.nanoTime} scale).
 * Only players below a full budget are stored. Not thread-safe.
 */
final class HoldBudgets {
    /** The scatter settings in effect (a config reload replaces them). */
    private final Supplier<SculptoryConfig.ScatterConfig> settings;
    /** Per player: {nanos left as of the time, that time}. */
    private final Map<UUID, long[]> budgets = new HashMap<>();

    HoldBudgets(Supplier<SculptoryConfig.ScatterConfig> settings) {
        this.settings = Objects.requireNonNull(settings);
    }

    /** Fixed settings (tests). */
    HoldBudgets(SculptoryConfig.ScatterConfig settings) {
        this(() -> settings);
        Objects.requireNonNull(settings);
    }

    long burstNanos() {
        return (long) (settings.get().holdBudgetSeconds * 1e9);
    }

    /** The player's hold time left at {@code now}; at most the burst, negative when overdrawn. */
    long left(UUID player, long now) {
        long[] budget = budgets.get(player);
        long burst = burstNanos();
        if (budget == null) return burst;
        double refilled = budget[0] + Math.max(0, now - budget[1]) * settings.get().holdRefillShare;
        return (long) Math.min(burst, refilled);
    }

    /** Uses {@code nanos} of the player's hold time at {@code now}. */
    void charge(UUID player, long nanos, long now) {
        long left = Math.max(-burstNanos(), left(player, now) - Math.max(0, nanos));
        budgets.put(player, new long[] {left, now});
    }

    /** How long until a budget at {@code left} is above zero again. */
    long refillNanos(long left) {
        return left > 0 ? 0 : (long) Math.ceil((1 - left) / settings.get().holdRefillShare);
    }

    /** Forgets the full budgets of players {@code busy} does not name. */
    void sweep(long now, Predicate<UUID> busy) {
        long burst = burstNanos();
        budgets.keySet().removeIf(player -> !busy.test(player) && left(player, now) >= burst);
    }

    /** Players with a budget below full. */
    int size() {
        return budgets.size();
    }

    void clear() {
        budgets.clear();
    }
}
