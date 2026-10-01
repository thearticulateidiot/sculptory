package dev.sculptory.fabric.client.builder;

import dev.sculptory.protocol.v2.BuilderPower;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The builder powers the player has on: the mask the server is told, the last
 * power toggled (a tap of the ring key toggles it again) and which powers are available. A power with a
 * {@link PowerController} tells it when it goes on and off; Tinker is unavailable until its controller is registered.
 * Pure Java; client thread only.
 */
public final class PowerSet {
    public static final String TINKER_UNAVAILABLE = "sculptory.builder.power.tinker.unavailable";

    private final Map<BuilderPower, PowerController> controllers = new EnumMap<>(BuilderPower.class);
    private final Map<BuilderPower, Supplier<String>> gates = new EnumMap<>(BuilderPower.class);
    private final List<Runnable> listeners = new ArrayList<>();
    private int mask;
    private BuilderPower last = BuilderPower.LONG_REACH;

    /** The powers on, as {@link BuilderPower} bits. */
    public int mask() {
        return mask;
    }

    public boolean on(BuilderPower power) {
        return power.in(mask);
    }

    /** The power toggled last (Long reach before any was). */
    public BuilderPower last() {
        return last;
    }

    /** Whether the power can be switched on: every power but Tinker, which needs its controller and an open gate. */
    public boolean available(BuilderPower power) {
        return unavailableKey(power) == null;
    }

    /**
     * The translation key saying why {@code power} cannot be switched on right now (Tinker without its controller, or
     * what its gate says: no session, no {@code region}, a server without Tinker), or {@code null} when it can.
     */
    public String unavailableKey(BuilderPower power) {
        if (power == BuilderPower.TINKER && !controllers.containsKey(power)) return TINKER_UNAVAILABLE;
        Supplier<String> gate = gates.get(power);
        return gate == null ? null : gate.get();
    }

    /** A condition for switching {@code power} on: the key saying why not, or {@code null}; asked each time. */
    public void setGate(BuilderPower power, Supplier<String> unavailableKey) {
        gates.put(Objects.requireNonNull(power), Objects.requireNonNull(unavailableKey));
    }

    /** Hears every change of the mask. */
    public void addListener(Runnable listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    /** Switches the power the other way; false (and nothing changes) when it is unavailable. */
    public boolean toggle(BuilderPower power) {
        return set(power, !on(power));
    }

    /** Toggles the power toggled last. */
    public boolean toggleLast() {
        return toggle(last);
    }

    /**
     * Switches the power on or off; false when it is unavailable and asked on (switching off always works). Remembers
     * it as the last one either way.
     */
    public boolean set(BuilderPower power, boolean on) {
        if (on && !available(power)) return false;
        last = power;
        if (on == on(power)) return true;
        mask = on ? mask | power.bit() : mask & ~power.bit();
        PowerController controller = controllers.get(power);
        if (controller != null) {
            if (on) controller.activate();
            else controller.deactivate();
        }
        changed();
        return true;
    }

    /** Every power off (the player left the world); controllers of powers that were on are deactivated. */
    public void clear() {
        if (mask == 0) return;
        int was = mask;
        mask = 0;
        for (Map.Entry<BuilderPower, PowerController> entry : controllers.entrySet()) {
            if (entry.getKey().in(was)) entry.getValue().deactivate();
        }
        changed();
    }

    /** Gives {@code power} its controller; activated at once when the power is on. Replaces an earlier one. */
    public void register(BuilderPower power, PowerController controller) {
        PowerController previous = controllers.put(Objects.requireNonNull(power), Objects.requireNonNull(controller));
        if (on(power)) {
            if (previous != null) previous.deactivate();
            controller.activate();
        }
        changed();
    }

    /** Offers a scroll to the controllers of the powers on; true when one took it. */
    public boolean onScroll(double amount, int modifiers) {
        for (Map.Entry<BuilderPower, PowerController> entry : controllers.entrySet()) {
            if (on(entry.getKey()) && entry.getValue().onScroll(amount, modifiers)) return true;
        }
        return false;
    }

    /** Offers a click to the controllers of the powers on; true when one took it. */
    public boolean onClick(int button, int modifiers) {
        for (Map.Entry<BuilderPower, PowerController> entry : controllers.entrySet()) {
            if (on(entry.getKey()) && entry.getValue().onClick(button, modifiers)) return true;
        }
        return false;
    }

    private void changed() {
        for (Runnable listener : listeners) listener.run();
    }
}
