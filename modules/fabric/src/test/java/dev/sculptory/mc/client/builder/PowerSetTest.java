package dev.sculptory.mc.client.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.protocol.v2.BuilderPower;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PowerSetTest {
    /** Record's bit: on in every new set. */
    private static final int RECORD = BuilderPower.RECORD.bit();

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
        assertEquals(RECORD, powers.mask(), "Record is on from the start");
        assertEquals(BuilderPower.LONG_REACH, powers.last(), "a tap before anything was toggled means Long reach");

        assertTrue(powers.toggle(BuilderPower.REPLACE));
        assertTrue(powers.on(BuilderPower.REPLACE));
        assertEquals(RECORD | BuilderPower.REPLACE.bit(), powers.mask());
        assertEquals(BuilderPower.REPLACE, powers.last());

        assertTrue(powers.toggle(BuilderPower.MIRROR));
        assertEquals(RECORD | BuilderPower.REPLACE.bit() | BuilderPower.MIRROR.bit(), powers.mask());
        assertEquals(BuilderPower.MIRROR, powers.last());

        assertTrue(powers.toggleLast());
        assertFalse(powers.on(BuilderPower.MIRROR));
        assertTrue(powers.on(BuilderPower.REPLACE));
        assertEquals(List.of(RECORD | BuilderPower.REPLACE.bit(),
                RECORD | BuilderPower.REPLACE.bit() | BuilderPower.MIRROR.bit(), RECORD | BuilderPower.REPLACE.bit()),
                heard);

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
        assertEquals(RECORD, powers.mask());
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
        assertEquals(RECORD, powers.mask(), "every power off but Record, a setting kept across restarts");
        assertEquals(List.of("on", "off"), replacement.events);
        powers.clear();
        assertEquals(List.of("on", "off"), replacement.events, "clearing nothing says nothing");
    }

    /**
     * Record is on in a new set and counts toward what takes effect, but not toward what builder mode shows as on;
     * putting back the saved state is not a toggle (the tap still means the power toggled last) and is not saved
     * again; each later switch is handed to the settings; clearing leaves it alone.
     */
    @Test
    void recordIsOnByDefaultRememberedAndNotTheLastToggledPower() {
        PowerSet powers = new PowerSet();
        assertTrue(powers.on(BuilderPower.RECORD));
        assertEquals(RECORD, powers.effective(), "Record alone takes the clicks into the history");
        assertEquals(0, powers.shown(), "no tag, and builder mode does not show as on for Record alone");

        List<Boolean> saved = new ArrayList<>();
        List<Integer> heard = new ArrayList<>();
        powers.addListener(() -> heard.add(powers.mask()));
        powers.restoreRecord(false, saved::add);
        assertEquals(0, powers.mask(), "saved off: off after a restart");
        assertEquals(0, powers.effective());
        assertEquals(List.of(), saved, "putting back what was saved saves nothing");
        assertEquals(List.of(0), heard, "the mask change is heard (the server is told)");
        assertEquals(BuilderPower.LONG_REACH, powers.last(), "putting Record back is not a toggle");

        assertTrue(powers.toggle(BuilderPower.PLACE_IN_AIR));
        assertEquals(BuilderPower.PLACE_IN_AIR.bit(), powers.shown());
        assertTrue(powers.toggle(BuilderPower.RECORD));
        assertEquals(List.of(true), saved);
        assertEquals(BuilderPower.PLACE_IN_AIR.bit(), powers.shown(), "Record shows nothing");
        assertEquals(RECORD | BuilderPower.PLACE_IN_AIR.bit(), powers.effective());
        assertEquals(BuilderPower.PLACE_IN_AIR, powers.last(), "switching Record is never the last toggle");
        assertTrue(powers.toggleLast(), "a tap after switching Record toggles the last visible power");
        assertFalse(powers.on(BuilderPower.PLACE_IN_AIR));
        assertTrue(powers.on(BuilderPower.RECORD), "and leaves Record alone");
        assertEquals(List.of(true), saved);

        PowerSet fresh = new PowerSet();
        fresh.toggle(BuilderPower.RECORD);
        assertEquals(BuilderPower.LONG_REACH, fresh.last(), "Record first: a tap still means Long reach");
        assertTrue(fresh.toggleLast());
        assertEquals(BuilderPower.LONG_REACH.bit(), fresh.mask(), "Long reach on, Record still off");

        powers.set(BuilderPower.RECORD, false);
        powers.set(BuilderPower.RECORD, true);
        powers.clear();
        assertEquals(RECORD, powers.mask(), "clearing keeps Record");
        assertEquals(List.of(true, false, true), saved, "clearing saves nothing");

        PowerSet savedOn = new PowerSet();
        savedOn.restoreRecord(true, on -> {
            throw new AssertionError("nothing to save");
        });
        assertEquals(RECORD, savedOn.mask(), "saved on (or nothing saved): on");
        assertEquals(BuilderPower.LONG_REACH, savedOn.last());
    }

    /**
     * Against a server with builder mode but without Record, Record's chip is greyed out with the reason, its bit is
     * dropped from what takes effect (and is sent) while the player's choice stays, switching it on is refused and
     * switching it off still works; the other powers are not affected.
     */
    @Test
    void recordIsGatedAndLeftOutOnAServerWithoutIt() {
        assertEquals(PowerSet.RECORD_NEEDS_SERVER, PowerSet.recordGate(true, false));
        assertNull(PowerSet.recordGate(true, true));
        assertNull(PowerSet.recordGate(false, false), "no builder mode at all: the ring itself says why");

        PowerSet powers = new PowerSet();
        boolean[] recordOffered = {false};
        powers.setGate(BuilderPower.RECORD, () -> PowerSet.recordGate(true, recordOffered[0]));
        List<Boolean> saved = new ArrayList<>();
        powers.restoreRecord(true, saved::add);
        assertFalse(powers.available(BuilderPower.RECORD));
        assertEquals(PowerSet.RECORD_NEEDS_SERVER, powers.unavailableKey(BuilderPower.RECORD));
        assertTrue(powers.on(BuilderPower.RECORD), "the player's choice is kept");
        assertEquals(0, powers.effective(), "but not sent, and the clicks stay vanilla's");
        assertTrue(powers.toggle(BuilderPower.LONG_REACH));
        assertEquals(BuilderPower.LONG_REACH.bit(), powers.effective(), "the other powers work as before");

        recordOffered[0] = true;
        assertEquals(RECORD | BuilderPower.LONG_REACH.bit(), powers.effective(), "a server with Record gets it");
        recordOffered[0] = false;
        assertTrue(powers.set(BuilderPower.RECORD, false), "switching off always works");
        assertEquals(List.of(false), saved);
        assertFalse(powers.toggle(BuilderPower.RECORD), "switching on is refused while greyed out");
        assertFalse(powers.on(BuilderPower.RECORD));
        assertEquals(List.of(false), saved);
    }
}
