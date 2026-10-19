package dev.sculptory.fabric.client.editor.tour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.sculptory.fabric.client.editor.ui.Rect;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** UI units to framebuffer pixels, crops on whole rows, and the size a wiki picture is written at. */
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

    // ---- Whole rows: a window's content scrolls in a pane from y 20 to 120; rows are 16 high, 6 apart ----

    private static final Rect PANE = Rect.ofEdges(10, 20, 110, 120);
    private static final Rect WINDOW = Rect.ofEdges(7, 5, 113, 126);

    private static List<TourCrop.Band> rows(int firstTop, int count) {
        List<TourCrop.Band> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new TourCrop.Band(firstTop + i * 22, firstTop + i * 22 + 16));
        }
        return rows;
    }

    @Test
    void aCropOnWholeRowsIsLeftAsItIs() {
        // Rows at 20..36, 42..58, 64..80, 86..102: the last one ends above the pane's bottom.
        assertEquals(WINDOW, TourCrop.snapToRows(WINDOW, PANE, rows(20, 4), 3, 48));
        // A row ending exactly on the pane's edge is whole.
        assertEquals(WINDOW, TourCrop.snapToRows(WINDOW, PANE, List.of(new TourCrop.Band(104, 120)), 3, 48));
    }

    @Test
    void aRowThePaneCutsAtTheBottomIsLeftOutAndTheWindowsBottomGoes() {
        // Rows from 20 down to 108..124: the pane ends at 120, through the last row.
        List<TourCrop.Band> rows = rows(20, 5);
        // The picture ends 3 under the row above it (86..102), in the gap.
        assertEquals(Rect.ofEdges(7, 5, 113, 105), TourCrop.snapToRows(WINDOW, PANE, rows, 3, 48));
    }

    @Test
    void aRowThePaneCutsAtTheTopIsLeftOutAndTheTitleBarGoes() {
        // Scrolled: rows at 10..26 (cut by the pane at 20), 32..48, 54..70.
        List<TourCrop.Band> rows = rows(10, 3);
        assertEquals(Rect.ofEdges(7, 29, 113, 126), TourCrop.snapToRows(WINDOW, PANE, rows, 3, 48));
    }

    @Test
    void aCropEdgeInsideTheRowsGoesToTheNearerSideOfTheRowItCuts() {
        List<TourCrop.Band> rows = rows(20, 4); // 20..36, 42..58, 64..80, 86..102
        // The top at 45 cuts 42..58 near its top: the row is taken in whole, 3 above it (the gap is 36..42).
        assertEquals(Rect.ofEdges(7, 39, 113, 126),
                TourCrop.snapToRows(Rect.ofEdges(7, 45, 113, 126), PANE, rows, 3, 48));
        // The top at 55 cuts it near its bottom: it is left out, the picture starts 3 above the next row.
        assertEquals(Rect.ofEdges(7, 61, 113, 126),
                TourCrop.snapToRows(Rect.ofEdges(7, 55, 113, 126), PANE, rows, 3, 48));
        // The bottom at 70 cuts 64..80 near its top: left out, 3 under the row above.
        assertEquals(Rect.ofEdges(7, 5, 113, 61), TourCrop.snapToRows(Rect.ofEdges(7, 5, 113, 70), PANE, rows, 3, 48));
        // The bottom at 76 cuts it near its bottom: taken in whole.
        assertEquals(Rect.ofEdges(7, 5, 113, 83), TourCrop.snapToRows(Rect.ofEdges(7, 5, 113, 76), PANE, rows, 3, 48));
        // A margin wider than the gap stops at the next row.
        assertEquals(Rect.ofEdges(7, 36, 113, 126),
                TourCrop.snapToRows(Rect.ofEdges(7, 45, 113, 126), PANE, rows, 10, 48));
    }

    @Test
    void aRowMostlyInsideButCutByThePaneIsStillLeftOut() {
        // 108..124 is cut by the pane at 120 although the crop's bottom (126) is under it.
        List<TourCrop.Band> rows = List.of(new TourCrop.Band(86, 102), new TourCrop.Band(108, 124));
        assertEquals(Rect.ofEdges(7, 5, 113, 105), TourCrop.snapToRows(WINDOW, PANE, rows, 3, 48));
        // A crop of that row alone would keep nothing: it stays as it is.
        Rect low = Rect.ofEdges(7, 106, 113, 126);
        assertEquals(low, TourCrop.snapToRows(low, PANE, rows, 3, 48));
    }

    @Test
    void theWidgetsOfOneRowCountAsOneRow() {
        // A 16-high chip and a 10-high label beside it, then a cut row: the edge goes above both.
        List<TourCrop.Band> rows = List.of(new TourCrop.Band(86, 102), new TourCrop.Band(89, 99),
                new TourCrop.Band(108, 124));
        assertEquals(Rect.ofEdges(7, 5, 113, 105), TourCrop.snapToRows(WINDOW, PANE, rows, 3, 48));
        assertEquals(List.of(new TourCrop.Band(0, 20), new TourCrop.Band(20, 30)),
                TourCrop.merged(List.of(new TourCrop.Band(20, 30), new TourCrop.Band(5, 20), new TourCrop.Band(0, 12))),
                "overlapping rows join, rows that only touch stay apart");
    }

    @Test
    void aTallBlockOrACropWithNoRowLeftStaysCut() {
        // A list 60 high cut by the pane: a list cut at its end shows that it scrolls.
        assertEquals(WINDOW, TourCrop.snapToRows(WINDOW, PANE, List.of(new TourCrop.Band(70, 130)), 3, 48));
        // A single row cut by the pane: leaving it out would leave nothing.
        assertEquals(WINDOW, TourCrop.snapToRows(WINDOW, PANE, List.of(new TourCrop.Band(110, 126)), 3, 48));
        // A crop beside the pane (above it) is not touched.
        Rect above = Rect.ofEdges(7, 0, 113, 15);
        assertEquals(above, TourCrop.snapToRows(above, PANE, rows(10, 3), 3, 48));
        assertThrows(IllegalArgumentException.class, () -> new TourCrop.Band(5, 5));
    }

    @Test
    void aPictureOverTheByteBudgetIsWrittenSmallerKeepingItsShape() {
        // Twice the budget: the area halves (width / sqrt 2), with a little to spare.
        TourCrop.Pixels smaller = TourCrop.shrunkForBytes(new TourCrop.Pixels(0, 0, 1200, 471), 600_000, 300_000);
        assertEquals(806, smaller.width());
        assertEquals(316, smaller.height());
        // Just over: still a twentieth narrower, so one retry does.
        assertEquals(1139, TourCrop.shrunkForBytes(new TourCrop.Pixels(0, 0, 1200, 400), 300_001, 300_000).width());
        // A picture one pixel wide stays one pixel wide.
        assertEquals(new TourCrop.Pixels(0, 0, 1, 50),
                TourCrop.shrunkForBytes(new TourCrop.Pixels(0, 0, 1, 50), 400_000, 300_000));
        assertThrows(IllegalArgumentException.class,
                () -> TourCrop.shrunkForBytes(new TourCrop.Pixels(0, 0, 10, 10), 100, 300));
    }
}
