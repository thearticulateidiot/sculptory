package dev.sculptory.fabric.client.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.DoubleConsumer;

/**
 * The fly-speed multiplier: 1× is the player's own speed (vanilla creative flight unless something else changed it).
 * The editor's scroll wheel steps it through {@link #step}; {@code /flyspeed} sets any value in the range
 * ({@link #set}). One instance for the whole game: it applies in the editor and in creative and spectator flight
 * outside it ({@code FlySpeedApplier}), and is saved in {@code editor-ui.json} ({@code windows.UiSizeStore}).
 * Pure Java; client thread only.
 */
public final class FlySpeed {
    public static final double MIN = 0.25;
    public static final double MAX = 8;
    public static final double DEFAULT = 1;
    /** The scroll wheel's steps, from {@link #MIN} to {@link #MAX}. */
    private static final double[] STEPS = {0.25, 0.5, 0.75, 1, 1.5, 2, 3, 4, 6, 8};
    /** Values are kept to hundredths: {@code 2.333} is {@code 2.33}. */
    private static final double PRECISION = 100;

    private double value = DEFAULT;
    private final List<DoubleConsumer> listeners = new ArrayList<>();

    public double multiplier() {
        return value;
    }

    /** Adds a listener, called with the new multiplier after each change (the editor saves it). */
    public void addListener(DoubleConsumer listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /**
     * Moves up (positive) or down (negative) to the next scroll step: from a value between two steps (one set with
     * {@code /flyspeed}), the step above or below it. Returns true if it changed.
     */
    public boolean step(int direction) {
        int sign = Integer.signum(direction);
        double next = value;
        if (sign > 0) {
            for (double candidate : STEPS) {
                if (candidate > value) {
                    next = candidate;
                    break;
                }
            }
        } else if (sign < 0) {
            for (int i = STEPS.length - 1; i >= 0; i--) {
                if (STEPS[i] < value) {
                    next = STEPS[i];
                    break;
                }
            }
        }
        return change(next);
    }

    /**
     * Sets the multiplier, clamped to {@link #MIN}-{@link #MAX} and rounded to hundredths ({@link #clamp}). Returns
     * true if it changed.
     */
    public boolean set(double multiplier) {
        return change(clamp(multiplier));
    }

    public void reset() {
        change(DEFAULT);
    }

    /** "1.5×". */
    public String display() {
        return display(value);
    }

    /** {@code multiplier} as the top bar and {@code /flyspeed} show it: "1.5×", "2×", "0.25×". */
    public static String display(double multiplier) {
        String number = multiplier == Math.rint(multiplier)
                ? Long.toString((long) multiplier)
                : String.format(Locale.ROOT, "%.2f", multiplier).replaceAll("0+$", "");
        return number + "×";
    }

    /**
     * {@code multiplier} in the range: below {@link #MIN} is {@link #MIN}, above {@link #MAX} (or infinite) is
     * {@link #MAX}, NaN is {@link #DEFAULT}; otherwise rounded to hundredths.
     */
    public static double clamp(double multiplier) {
        if (Double.isNaN(multiplier)) {
            return DEFAULT;
        }
        double inRange = Math.max(MIN, Math.min(MAX, multiplier));
        return Math.round(inRange * PRECISION) / PRECISION;
    }

    /**
     * A typed fly speed: a number ("2", "0.5", "1,5"; a trailing "x" or "×" is allowed) or "reset" (= {@link #DEFAULT}),
     * any case, surrounding spaces ignored. Empty for anything else, including NaN and infinities. The number is
     * returned as typed: {@link #clamp} puts it in the range.
     */
    public static Optional<Double> parse(String text) {
        String typed = Objects.requireNonNull(text).strip().toLowerCase(Locale.ROOT);
        if (typed.equals("reset")) {
            return Optional.of(DEFAULT);
        }
        if (typed.endsWith("x") || typed.endsWith("×")) {
            typed = typed.substring(0, typed.length() - 1).strip();
        }
        typed = typed.replace(',', '.');
        if (typed.isEmpty() || !typed.matches("[+-]?(\\d+(\\.\\d*)?|\\.\\d+)")) {
            return Optional.empty();
        }
        try {
            double number = Double.parseDouble(typed);
            return Double.isFinite(number) ? Optional.of(number) : Optional.empty();
        } catch (NumberFormatException malformed) {
            return Optional.empty();
        }
    }

    private boolean change(double next) {
        if (next == value) {
            return false;
        }
        value = next;
        for (DoubleConsumer listener : listeners) {
            listener.accept(next);
        }
        return true;
    }
}
