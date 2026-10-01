package dev.sculptory.fabric.client.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.protocol.v2.BuilderPower;
import java.util.List;
import org.junit.jupiter.api.Test;

class RingMenuTest {
    private static final List<BuilderPower> ALL = List.of(BuilderPower.values());

    private static RingMenu ring() {
        return new RingMenu(ALL, 100, 100, 50, 1_000);
    }

    @Test
    void theSectorsGoClockwiseFromTheTop() {
        RingMenu ring = ring();
        assertEquals(8, ring.entries().size());
        assertEquals(0.0, ring.angleOf(0));
        assertEquals(90.0, ring.angleOf(2));
        assertEquals(315.0, ring.angleOf(7));
        assertThrows(IndexOutOfBoundsException.class, () -> ring.angleOf(8));

        ring.pointer(100, 40); // straight up
        assertEquals(0, ring.hovered());
        assertEquals(BuilderPower.LONG_REACH, ring.hoveredPower().orElseThrow());
        ring.pointer(160, 100); // right
        assertEquals(2, ring.hovered());
        assertEquals(BuilderPower.REPLACE, ring.hoveredPower().orElseThrow());
        ring.pointer(100, 160); // down
        assertEquals(4, ring.hovered());
        ring.pointer(40, 100); // left
        assertEquals(6, ring.hovered());
        ring.pointer(140, 60); // up-right: between 0 and 2, so 1
        assertEquals(1, ring.hovered());
        ring.pointer(60, 60); // up-left
        assertEquals(7, ring.hovered());
        // A sector is centred on its entry: just clockwise of "up" is still entry 0.
        ring.pointer(110, 40);
        assertEquals(0, ring.hovered());

        double[] top = ring.labelCentre(0);
        assertEquals(100.0, top[0], 1e-9);
        assertEquals(50.0, top[1], 1e-9);
        double[] right = ring.labelCentre(2);
        assertEquals(150.0, right[0], 1e-9);
        assertEquals(100.0, right[1], 1e-9);
    }

    @Test
    void theMiddlePicksNothingAndFarAwayStillPicksASector() {
        RingMenu ring = ring();
        ring.pointer(100, 100);
        assertEquals(-1, ring.hovered());
        assertTrue(ring.hoveredPower().isEmpty());
        ring.pointer(100, 100 - 50 * RingMenu.DEAD_ZONE + 0.5);
        assertEquals(-1, ring.hovered(), "just inside the dead zone");
        ring.pointer(100, 100 - 50 * RingMenu.DEAD_ZONE - 0.5);
        assertEquals(0, ring.hovered(), "just outside it");
        ring.pointer(100, -1000);
        assertEquals(0, ring.hovered(), "beyond the ring still picks by angle");
    }

    @Test
    void releaseOverAnEntryTogglesIt() {
        RingMenu ring = ring();
        ring.pointer(160, 100);
        RingMenu.Outcome outcome = ring.release(5_000);
        assertEquals(new RingMenu.Outcome.Toggle(BuilderPower.REPLACE), outcome);
    }

    @Test
    void aShortTapWithoutLeavingTheMiddleTogglesTheLastPower() {
        RingMenu ring = ring();
        ring.pointer(101, 99);
        assertInstanceOf(RingMenu.Outcome.TapLast.class, ring.release(1_000 + RingMenu.TAP_MILLIS));
        assertInstanceOf(RingMenu.Outcome.Nothing.class, ring().release(1_000 + RingMenu.TAP_MILLIS + 1),
                "a longer hold released in the middle does nothing");

        // Leaving the middle and coming back within the tap time is not a tap either.
        RingMenu wandered = ring();
        wandered.pointer(160, 100);
        wandered.pointer(100, 100);
        assertEquals(-1, wandered.hovered());
        assertInstanceOf(RingMenu.Outcome.Nothing.class, wandered.release(1_050));
    }

    @Test
    void anEmptyRingOrNoRadiusIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new RingMenu(List.of(), 0, 0, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> new RingMenu(ALL, 0, 0, 0, 0));
    }
}
