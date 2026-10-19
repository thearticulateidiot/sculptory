package dev.sculptory.fabric.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** {@code /flyspeed}: what it says and what it sets. */
class FlySpeedCommandTest {
    private final FlySpeed speed = new FlySpeed();

    private FlySpeedCommand.Reply run(String argument) {
        return FlySpeedCommand.run(speed, Optional.ofNullable(argument), true);
    }

    @Test
    void flyspeedAloneShowsTheSpeedAndChangesNothing() {
        speed.set(1.5);
        FlySpeedCommand.Reply reply = run(null);
        assertEquals("sculptory.flyspeed.current", reply.key());
        assertEquals(List.of("1.5×"), reply.args());
        assertFalse(reply.error());
        assertEquals(1.5, speed.multiplier(), "unchanged");
    }

    @Test
    void aNumberSetsTheSpeed() {
        FlySpeedCommand.Reply reply = run("2.75");
        assertEquals(new FlySpeedCommand.Reply("sculptory.flyspeed.set", List.of("2.75×"), false, false), reply);
        assertEquals(2.75, speed.multiplier());
    }

    @Test
    void outOfRangeIsClampedAndSaysSo() {
        assertEquals(new FlySpeedCommand.Reply("sculptory.flyspeed.clamped", List.of("8×", "20"), false, false),
                run("20"));
        assertEquals(8, speed.multiplier());
        assertEquals(new FlySpeedCommand.Reply("sculptory.flyspeed.clamped", List.of("0.25×", "0"), false, false),
                run("0"));
        assertEquals(0.25, speed.multiplier());
    }

    @Test
    void resetSetsTheSpeedBackToOne() {
        speed.set(6);
        assertEquals(new FlySpeedCommand.Reply("sculptory.flyspeed.reset", List.of("1×"), false, false), run("reset"));
        assertEquals(1, speed.multiplier());
    }

    @Test
    void somethingElseIsRefusedAndChangesNothing() {
        speed.set(3);
        FlySpeedCommand.Reply reply = run("warp");
        assertEquals("sculptory.flyspeed.invalid", reply.key());
        assertEquals(List.of("warp"), reply.args());
        assertTrue(reply.error());
        assertEquals(3, speed.multiplier());
    }

    @Test
    void outsideCreativeAndSpectatorTheReplySaysWhenItApplies() {
        FlySpeedCommand.Reply reply = FlySpeedCommand.run(speed, Optional.of("4"), false);
        assertTrue(reply.notFlyingHint());
        assertEquals(4, speed.multiplier(), "still set");
        assertFalse(FlySpeedCommand.run(speed, Optional.empty(), false).notFlyingHint(), "not when only showing");
    }
}
