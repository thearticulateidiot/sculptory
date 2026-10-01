package dev.sculptory.fabric.client.editor.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The -NoCaptions switch: nothing shows after Enter, nothing waits to be read, results are held longer. */
class DemoPacingTest {
    @Test
    void withCaptionsEveryCaptionGetsReadingTimeAndResultsKeepTheirLength() {
        DemoPacing pacing = DemoPacing.WITH_CAPTIONS;
        assertTrue(pacing.showsCaptions());
        assertTrue(pacing.showsPointerRing());
        assertEquals(DemoPacing.READ_MS, pacing.readMs());
        assertEquals(2_000, pacing.holdMs(2_000));
    }

    @Test
    void withoutCaptionsNothingShowsAndEachResultIsHeldAboutASecondLonger() {
        DemoPacing pacing = DemoPacing.WITHOUT_CAPTIONS;
        assertFalse(pacing.showsCaptions());
        assertFalse(pacing.showsPointerRing());
        assertEquals(0, pacing.readMs());
        assertEquals(2_000 + DemoPacing.EXTRA_HOLD_MS, pacing.holdMs(2_000));
        assertEquals(1_000, DemoPacing.EXTRA_HOLD_MS);
        assertEquals(new DemoPacing(false), pacing);
    }
}
