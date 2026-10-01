package dev.sculptory.fabric.client.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.protocol.v2.BuilderPower;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PowerSetTest {
    /** A controller that records what it hears. */
    private static final class Recording implements PowerController {
        final List<String> events = new ArrayList<>();
        boolean takesScroll;
        boolean takesClick;

        @Override
        public void activate() {
            events.add("on");
        }

        @Override
        public void deactivate() {
            events.add("off");
        }

        @Override
        public boolean onScroll(double amount, int modifiers) {
            events.add("scroll " + amount);
            return takesScroll;
        }

        @Override
        public boolean onClick(int button, int modifiers) {
            events.add("click " + button);
            return takesClick;
        }
    }

    @Test
    void togglingSetsBitsAndRemembersTheLastPower() {
        PowerSet powers = new PowerSet();
        List<Integer> heard = new ArrayList<>();
        powers.addListener(() -> heard.add(powers.mask()));
        assertEquals(0, powers.mask());
        assertEquals(BuilderPower.LONG_REACH, powers.last(), "a tap before anything was toggled means Long reach");

        assertTrue(powers.toggle(BuilderPower.REPLACE));
        assertTrue(powers.on(BuilderPower.REPLACE));
        assertEquals(BuilderPower.REPLACE.bit(), powers.mask());
        assertEquals(BuilderPower.REPLACE, powers.last());

        assertTrue(powers.toggle(BuilderPower.MIRROR));
        assertEquals(BuilderPower.REPLACE.bit() | BuilderPower.MIRROR.bit(), powers.mask());
        assertEquals(BuilderPower.MIRROR, powers.last());

        assertTrue(powers.toggleLast());
        assertFalse(powers.on(BuilderPower.MIRROR));
        assertTrue(powers.on(BuilderPower.REPLACE));
        assertEquals(List.of(BuilderPower.REPLACE.bit(), BuilderPower.REPLACE.bit() | BuilderPower.MIRROR.bit(),
                BuilderPower.REPLACE.bit()), heard);

        assertTrue(powers.set(BuilderPower.REPLACE, true), "setting what is already set changes nothing");
        assertEquals(3, heard.size());
        assertTrue(BuilderPower.valid(powers.mask()));
    }

    @Test
    void tinkerIsUnavailableUntilItsControllerIsRegistered() {
        PowerSet powers = new PowerSet();
        for (BuilderPower power : BuilderPower.values()) {
            assertEquals(power != BuilderPower.TINKER, powers.available(power), power.name());
        }
        assertEquals(PowerSet.TINKER_UNAVAILABLE, powers.unavailableKey(BuilderPower.TINKER));
        assertNull(powers.unavailableKey(BuilderPower.BULLDOZER));
        assertFalse(powers.toggle(BuilderPower.TINKER), "greyed out: nothing happens");
        assertEquals(0, powers.mask());
        assertEquals(BuilderPower.LONG_REACH, powers.last(), "a refused toggle is not the last one");

        Recording tinker = new Recording();
        powers.register(BuilderPower.TINKER, tinker);
        assertTrue(powers.available(BuilderPower.TINKER));
        assertNull(powers.unavailableKey(BuilderPower.TINKER));
        assertTrue(powers.toggle(BuilderPower.TINKER));
        assertEquals(List.of("on"), tinker.events);
        assertTrue(powers.toggle(BuilderPower.TINKER));
        assertEquals(List.of("on", "off"), tinker.events);
    }

    @Test
    void controllersHearActivationScrollsAndClicksOnlyWhileOn() {
        PowerSet powers = new PowerSet();
        Recording tinker = new Recording();
        powers.register(BuilderPower.TINKER, tinker);
        assertFalse(powers.onScroll(1, 0), "off: nobody takes the scroll");
        assertFalse(powers.onClick(0, 0));
        assertTrue(tinker.events.isEmpty());

        powers.toggle(BuilderPower.TINKER);
        tinker.takesScroll = true;
        assertTrue(powers.onScroll(-1, 0));
        assertFalse(powers.onClick(1, 0), "offered but not taken");
        assertEquals(List.of("on", "scroll -1.0", "click 1"), tinker.events);

        // Registering again while on: the old controller goes off, the new one comes on.
        Recording replacement = new Recording();
        powers.register(BuilderPower.TINKER, replacement);
        assertEquals(List.of("on", "scroll -1.0", "click 1", "off"), tinker.events);
        assertEquals(List.of("on"), replacement.events);

        powers.toggle(BuilderPower.LONG_REACH);
        powers.clear();
        assertEquals(0, powers.mask());
        assertEquals(List.of("on", "off"), replacement.events);
        powers.clear();
        assertEquals(List.of("on", "off"), replacement.events, "clearing nothing says nothing");
    }
}
