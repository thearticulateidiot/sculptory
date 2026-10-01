package dev.sculptory.fabric.client.editor.tour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.fabric.client.editor.ui.Rect;
import org.junit.jupiter.api.Test;

/** UI units to framebuffer pixels, and the size a wiki picture is written at. */
class TourCropTest {
    @Test
    void uiUnitsBecomeFramebufferPixelsThroughTheUiSizeAndTheGuiScale() {
        // The reference view: UI 50% (0.5 GUI px per unit) at GUI scale 6: three framebuffer pixels per UI unit.
        assertEquals(new TourCrop.Pixels(30, 60, 510, 909),
                TourCrop.pixels(new Rect(10, 20, 170, 303), 0.5, 6, 2560, 1494));
        // At 100% and GUI scale 2, two pixels per unit.
        assertEquals(new TourCrop.Pixels(8, 4, 40, 20), TourCrop.pixels(new Rect(4, 2, 20, 10), 1, 2, 1600, 900));
    }

    @Test
    void fractionalEdgesWidenToWholePixels() {
        // 0.75 GUI px per unit at scale 1: 3 units are 2.25 px, so 1..4 units span pixels 0 to 3.
        assertEquals(new TourCrop.Pixels(0, 0, 3, 3), TourCrop.pixels(new Rect(1, 1, 3, 3), 0.75, 1, 100, 100));
    }

    @Test
    void theAreaIsClippedToTheFrame() {
        assertEquals(new TourCrop.Pixels(0, 0, 30, 30), TourCrop.pixels(new Rect(-5, -5, 20, 20), 1, 2, 100, 100));
        assertEquals(new TourCrop.Pixels(90, 80, 10, 20), TourCrop.pixels(new Rect(45, 40, 20, 20), 1, 2, 100, 100));
    }

    @Test
    void anAreaOffTheFrameOrABadScaleIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> TourCrop.pixels(new Rect(200, 0, 10, 10), 1, 1, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> TourCrop.pixels(new Rect(0, 0, 10, 10), 0, 1, 100, 100));
        assertThrows(IllegalArgumentException.class,
                () -> TourCrop.pixels(new Rect(0, 0, 10, 10), 1, Double.NaN, 100, 100));
        assertThrows(IllegalArgumentException.class, () -> TourCrop.pixels(Rect.EMPTY, 1, 1, 100, 100));
    }

    @Test
    void aPictureWiderThanTheLimitIsScaledDownKeepingItsShape() {
        assertEquals(new TourCrop.Pixels(0, 0, 510, 909), TourCrop.fitted(510, 909, TourCrop.MAX_WIDTH));
        assertEquals(new TourCrop.Pixels(0, 0, 1200, 700), TourCrop.fitted(2560, 1494, TourCrop.MAX_WIDTH));
        assertEquals(new TourCrop.Pixels(0, 0, 1200, 1), TourCrop.fitted(5000, 2, TourCrop.MAX_WIDTH),
                "at least one pixel high");
        assertEquals(new TourCrop.Pixels(0, 0, 2560, 1494), TourCrop.fitted(2560, 1494, TourStep.FULL_WIDTH));
        assertThrows(IllegalArgumentException.class, () -> TourCrop.fitted(10, 10, 0));
    }

    @Test
    void unionAndGrowMakeTheAreaOfSeveralParts() {
        assertEquals(new Rect(0, 0, 30, 25), TourCrop.union(new Rect(0, 0, 10, 10), Rect.EMPTY, null,
                new Rect(20, 15, 10, 10)));
        assertEquals(Rect.EMPTY, TourCrop.union(Rect.EMPTY));
        assertEquals(new Rect(7, 17, 16, 26), TourCrop.grow(new Rect(10, 20, 10, 20), 3));
    }
}
