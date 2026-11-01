package dev.sculptory.mc.client.builder;

import dev.sculptory.protocol.v2.BuilderPower;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The builder powers the player has on: the mask the server is told, the last
 * power toggled (a tap of the ring key toggles it again) and which powers are available. A power with a
 * {@link PowerController} tells it when it goes on and off; Tinker is unavailable until its controller is registered.
 *
 * <p>Record is the one power kept across restarts: it is on in a new set (every block and entity action goes into the
 * history by default), the client's settings put back what the player chose last ({@link #restoreRecord}) and hear
 * each later change. Record never becomes the power toggled last: it shows no tag, so a tap of the ring key toggles
 * the last power the player can see (Long reach the first time).
 * Pure Java; client thread only.
 */
public final class PowerSet {
    public static final String TINKER_UNAVAILABLE = "sculptory.builder.power.tinker.unavailable";
    /** Why Record is greyed out: the server has builder mode but does not know Record (an older Sculptory). */
    public static final String RECORD_NEEDS_SERVER = "sculptory.builder.power.record.needs_server";

    private final Map<BuilderPower, PowerController> controllers = new EnumMap<>(BuilderPower.class);
    private final Map<BuilderPower, Supplier<String>> gates = new EnumMap<>(BuilderPower.class);
    private final List<Runnable> listeners = new ArrayList<>();
    private int mask = BuilderPower.RECORD.bit();
    private BuilderPower last = BuilderPower.LONG_REACH;
    private Consumer<Boolean> recordChanged = on -> { };

    /**
     * Record's gate: closed ({@link #RECORD_NEEDS_SERVER}) on a connection whose server offers builder mode but not
     * Record; open otherwise (without builder mode at all the ring itself says why).
     */
    public static String recordGate(boolean builderOffered, boolean recordOffered) {
        return builderOffered && !recordOffered ? RECORD_NEEDS_SERVER : null;
    }

    /** The powers on, as {@link BuilderPower} bits (Record included while the player has it on). */
    public int mask() {
        return mask;
    }

    /**
     * The powers that take effect: {@link #mask()} without Record while Record is unavailable (a server that does not
     * know it), so its bit is never sent there and a remembered Record alone does not take the clicks from vanilla.
     */
    public int effective() {
        if (BuilderPower.RECORD.in(mask) && !available(BuilderPower.RECORD)) return mask & ~BuilderPower.RECORD.bit();
        return mask;
    }

    /**
     * The powers builder mode shows as on (the tags above the hotbar, the tutorial's "a power is on"): every power on
     * but Record, which is the default state and would otherwise mark builder mode as on all the time.
     */
    public int shown() {
        return mask & ~BuilderPower.RECORD.bit();
    }

    public boolean on(BuilderPower power) {
        return power.in(mask);
    }

    /** The power toggled last, the one a tap toggles (Long reach before any was; never Record). */
    public BuilderPower last() {
        return last;
    }

    /** Whether the power can be switched on: every power but Tinker, which needs its controller and an open gate. */
    public boolean available(BuilderPower power) {
        return unavailableKey(power) == null;
    }

    /**
     * The translation key saying why {@code power} cannot be switched on right now (Tinker without its controller, or
     * what its gate says: no session, no {@code region}, a server without Tinker or without Record), or {@code null}
     * when it can.
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

    /**
     * Puts Record back as the player left it ({@code on}: as saved, or on when nothing was saved) without counting as
     * a toggle, and hands every later change of Record to {@code changed} (the settings that remember it).
     */
    public void restoreRecord(boolean on, Consumer<Boolean> changed) {
        recordChanged = Objects.requireNonNull(changed);
        int was = mask;
        mask = on ? mask | BuilderPower.RECORD.bit() : mask & ~BuilderPower.RECORD.bit();
        if (mask != was) changed();
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
     * it as the last one either way, except Record: a tap of the ring key never toggles Record.
     */
    public boolean set(BuilderPower power, boolean on) {
        if (on && !available(power)) return false;
        // Record shows no tag, so a tap that switched it again would change something the player cannot see.
        if (power != BuilderPower.RECORD) last = power;
        if (on == on(power)) return true;
        mask = on ? mask | power.bit() : mask & ~power.bit();
        PowerController controller = controllers.get(power);
        if (controller != null) {
            if (on) controller.activate();
            else controller.deactivate();
        }
        if (power == BuilderPower.RECORD) recordChanged.accept(on);
        changed();
        return true;
    }

    /**
     * Every power but Record off (the scripted checks and tours reset to this); controllers of powers that were on are
     * deactivated. Record stays as the player chose it, since it is a setting kept across restarts.
     */
    public void clear() {
        int was = mask;
        mask &= BuilderPower.RECORD.bit();
        if (mask == was) return;
        for (Map.Entry<BuilderPower, PowerController> entry : controllers.entrySet()) {
            if (entry.getKey().in(was) && !entry.getKey().in(mask)) entry.getValue().deactivate();
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
