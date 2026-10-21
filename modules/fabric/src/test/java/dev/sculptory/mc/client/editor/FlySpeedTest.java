package dev.sculptory.mc.client.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The fly-speed multiplier: scroll steps, typed values ({@code /flyspeed}), the range and the display. */
class FlySpeedTest {
    @Test
    void typedNumbersResetAndATrailingXParseAsSpeeds() {
        assertEquals(Optional.of(2.0), FlySpeed.parse("2"));
        assertEquals(Optional.of(0.5), FlySpeed.parse("0.5"));
        assertEquals(Optional.of(0.5), FlySpeed.parse(".5"));
        assertEquals(Optional.of(3.0), FlySpeed.parse("3."));
        assertEquals(Optional.of(1.5), FlySpeed.parse("1,5"), "a decimal comma");
        assertEquals(Optional.of(4.0), FlySpeed.parse("4x"));
        assertEquals(Optional.of(4.0), FlySpeed.parse(" 4 × "));
        assertEquals(Optional.of(4.0), FlySpeed.parse("4X"));
        assertEquals(Optional.of(1.0), FlySpeed.parse("reset"));
        assertEquals(Optional.of(1.0), FlySpeed.parse("RESET"));
        assertEquals(Optional.of(20.0), FlySpeed.parse("20"), "out of range is parsed as typed; clamp() limits it");
        assertEquals(Optional.of(-1.0), FlySpeed.parse("-1"));
    }

    @Test
    void anythingElseIsNotASpeed() {
        for (String typed : List.of("", " ", "fast", "NaN", "Infinity", "-Infinity", "1e3", "0x10", "2..5", "x",
                "1/2", "2 3", "1.2.3", "reset now", "d")) {
            assertEquals(Optional.empty(), FlySpeed.parse(typed), typed);
        }
    }

    @Test
    void valuesAreClampedToTheScrollRangeAndRoundedToHundredths() {
        assertEquals(0.25, FlySpeed.clamp(0));
        assertEquals(0.25, FlySpeed.clamp(-5));
        assertEquals(0.25, FlySpeed.clamp(0.1));
        assertEquals(8, FlySpeed.clamp(8.0001));
        assertEquals(8, FlySpeed.clamp(1e9));
        assertEquals(8, FlySpeed.clamp(Double.POSITIVE_INFINITY));
        assertEquals(1, FlySpeed.clamp(Double.NaN));
        assertEquals(2.33, FlySpeed.clamp(2.333));
        assertEquals(2.5, FlySpeed.clamp(2.5));
    }

    @Test
    void theScrollStepsFromATypedValueToTheNeighbouringSteps() {
        FlySpeed speed = new FlySpeed();
        assertTrue(speed.set(2.5));
        assertTrue(speed.step(1));
        assertEquals(3, speed.multiplier(), "up from 2.5 is the step above");
        speed.set(2.5);
        assertTrue(speed.step(-1));
        assertEquals(2, speed.multiplier(), "down from 2.5 is the step below");
        speed.set(8);
        assertFalse(speed.step(1), "8× is the top");
        speed.set(0.25);
        assertFalse(speed.step(-1), "0.25× is the bottom");
        speed.set(1);
        assertTrue(speed.step(1));
        assertEquals(1.5, speed.multiplier());
        assertFalse(speed.step(0));
        speed.reset();
        assertEquals(1, speed.multiplier());
    }

    @Test
    void listenersHearEachChangeOnce() {
        FlySpeed speed = new FlySpeed();
        List<Double> heard = new ArrayList<>();
        speed.addListener(heard::add);
        speed.set(3);
        speed.set(3);
        speed.step(1);
        speed.set(100);
        speed.reset();
        speed.reset();
        assertEquals(List.of(3.0, 4.0, 8.0, 1.0), heard);
    }

    @Test
    void theDisplayDropsTrailingZeros() {
        assertEquals("1×", FlySpeed.display(1));
        assertEquals("8×", FlySpeed.display(8));
        assertEquals("0.25×", FlySpeed.display(0.25));
        assertEquals("1.5×", FlySpeed.display(1.5));
        assertEquals("2.33×", FlySpeed.display(2.33));
        assertEquals("0.3×", FlySpeed.display(0.3));
    }
}
