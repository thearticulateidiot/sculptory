package dev.sculptory.fabric.client.editor.tools.scatter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.scatter.ScatterArea;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Stamp painting: spacing along a path, erasing, the caps and local undo. */
class PaintedAreaTest {
    private final PaintedArea area = new PaintedArea();

    private static List<int[]> centres(StampPainter painter, double x, double z, double spacing) {
        return painter.moveTo(x, z, spacing);
    }

    // ---- Spacing along the path ----

    @Test
    void stampsFollowThePathHalfARadiusApart() {
        StampPainter painter = new StampPainter();
        double spacing = StampPainter.spacing(8);
        assertEquals(4.0, spacing);
        painter.press();
        assertArrayEquals(new int[] {0, 0}, centres(painter, 0.5, 0.5, spacing).get(0), "the press stamps at once");
        assertTrue(centres(painter, 3.0, 0.5, spacing).isEmpty(), "closer than the spacing: nothing yet");
        List<int[]> along = centres(painter, 20.5, 0.5, spacing);
        assertEquals(5, along.size(), "a fast drag is filled in");
        for (int i = 0; i < along.size(); i++) {
            assertArrayEquals(new int[] {4 * (i + 1), 0}, along.get(i));
        }
        assertTrue(centres(painter, 21.0, 0.5, spacing).isEmpty());
    }

    @Test
    void smallRadiiStillStampEveryBlockAndAPressOffTheTerrainStartsAtTheFirstHit() {
        assertEquals(1.0, StampPainter.spacing(1));
        StampPainter painter = new StampPainter();
        painter.press();
        List<int[]> first = centres(painter, 5.2, -3.7, 1);
        assertEquals(1, first.size(), "the first point on the terrain starts the path");
        assertArrayEquals(new int[] {5, -4}, first.get(0));
        assertEquals(3, centres(painter, 8.7, -3.7, 1).size(), "one stamp per block");
        painter.release();
        assertTrue(centres(painter, 9, 9, 1).isEmpty(), "nothing once released");
    }

    // ---- Raster, erase, repeats ----

    @Test
    void theRasterIsThePlannersDiscs() {
        area.add(ScatterArea.Stamp.paint(10, -4, 3));
        PaintedArea.Mask mask = area.mask();
        int count = 0;
        for (int dz = -5; dz <= 5; dz++) {
            for (int dx = -5; dx <= 5; dx++) {
                boolean inDisc = dx * dx + dz * dz <= 9;
                assertEquals(inDisc, mask.contains(10 + dx, -4 + dz), dx + "," + dz);
                if (inDisc) count++;
            }
        }
        assertEquals(count, area.columns());
    }

    @Test
    void erasingRemovesColumnsAndAnEraseOverNothingOrARepeatIsSkipped() {
        area.add(ScatterArea.Stamp.paint(0, 0, 4));
        long painted = area.columns();
        assertEquals(PaintedArea.AddResult.SKIPPED, area.add(ScatterArea.Stamp.paint(0, 0, 4)), "the same stamp again");
        assertEquals(PaintedArea.AddResult.SKIPPED, area.add(ScatterArea.Stamp.erase(40, 40, 3)), "nothing to erase there");
        assertEquals(PaintedArea.AddResult.ADDED, area.add(ScatterArea.Stamp.erase(0, 0, 1)));
        assertEquals(painted - 5, area.columns(), "the radius-1 disc is 5 columns");
        assertFalse(area.mask().contains(0, 0));
        assertTrue(area.mask().contains(2, 0));
        assertEquals(2, area.stampCount());
    }

    @Test
    void eraseStampsOnlyNeverMakeAnArea() {
        area.add(ScatterArea.Stamp.paint(0, 0, 1));
        area.add(ScatterArea.Stamp.erase(0, 0, 2));
        assertTrue(area.isEmpty(), "everything painted was erased");
        assertTrue(area.toArea().isEmpty());
    }

    // ---- Caps ----

    @Test
    void theAreaStopsAtTheStampCapAndWarnsNearIt() {
        for (int i = 0; i < PaintedArea.MAX_STAMPS; i++) {
            assertEquals(PaintedArea.AddResult.ADDED, area.add(ScatterArea.Stamp.paint(i, 0, 1)));
            assertEquals(i + 1 >= PaintedArea.WARN_STAMPS, area.nearCap(), "stamp " + (i + 1));
        }
        assertEquals(PaintedArea.AddResult.FULL, area.add(ScatterArea.Stamp.paint(-5, 0, 1)));
        assertEquals(PaintedArea.MAX_STAMPS, area.stampCount());
        ScatterArea.Stamps stamps = assertInstanceOf(ScatterArea.Stamps.class, area.toArea().orElseThrow());
        assertEquals(512, stamps.stamps().size(), "the whole area still fits the wire");
    }

    @Test
    void anAreaWiderThanTheColumnCapIsRefused() {
        assertEquals(PaintedArea.AddResult.ADDED, area.add(ScatterArea.Stamp.paint(0, 0, 0)));
        assertEquals(PaintedArea.AddResult.TOO_WIDE, area.add(ScatterArea.Stamp.paint(1100, 1100, 0)));
        assertEquals(PaintedArea.AddResult.ADDED, area.add(ScatterArea.Stamp.paint(1000, 1000, 0)), "1001² columns fit");
    }

    // ---- Groups: undo and cancel ----

    @Test
    void undoRemovesTheNewestStrokeAndCancelDropsTheOneBeingPainted() {
        area.beginGroup();
        area.add(ScatterArea.Stamp.paint(0, 0, 2));
        area.add(ScatterArea.Stamp.paint(2, 0, 2));
        area.endGroup();
        area.beginGroup();
        area.add(ScatterArea.Stamp.paint(10, 0, 2));
        area.endGroup();
        assertEquals(2, area.groupCount());

        area.beginGroup();
        area.add(ScatterArea.Stamp.paint(20, 0, 2));
        assertTrue(area.cancelGroup(), "Esc mid-stroke drops that stroke");
        assertEquals(3, area.stampCount());

        assertTrue(area.undoGroup());
        assertEquals(2, area.stampCount());
        assertFalse(area.mask().contains(10, 0));
        assertTrue(area.undoGroup());
        assertFalse(area.undoGroup(), "nothing left to undo");
        assertTrue(area.isEmpty());
    }

    /** B2: sealing keeps every stamp (and box mode) but puts them out of undo's reach; later strokes undo as usual. */
    @Test
    void aSealedAreaKeepsItsStampsButUndoNoLongerReachesThem() {
        area.add(ScatterArea.Stamp.paint(0, 0, 2));
        area.endGroup();
        Box box = Box.of(new BlockPos(0, 60, 0), new BlockPos(15, 80, 15));
        area.useBox(box);
        assertTrue(area.undoable());
        area.seal();
        assertFalse(area.undoable());
        assertFalse(area.undoGroup(), "box mode is sealed too");
        assertEquals(new ScatterArea.Region(box), area.toArea().orElseThrow());
        assertTrue(area.leaveBox(), "painting still takes the area back from the box");
        assertEquals(1, area.stampCount());

        area.add(ScatterArea.Stamp.paint(10, 0, 2));
        area.endGroup();
        assertTrue(area.undoable());
        assertTrue(area.undoGroup(), "a stroke after the seal is undone");
        assertFalse(area.undoGroup(), "the one before stays");
        assertEquals(List.of(ScatterArea.Stamp.paint(0, 0, 2)), area.stamps());
    }

    @Test
    void anEmptyPressLeavesNoUndoStep() {
        area.beginGroup();
        area.endGroup();
        assertEquals(0, area.groupCount());
    }

    @Test
    void theSelectionBoxReplacesTheStampsUntilUndoneOrPaintedOver() {
        area.add(ScatterArea.Stamp.paint(0, 0, 2));
        area.endGroup();
        Box box = Box.of(new BlockPos(0, 60, 0), new BlockPos(31, 80, 15));
        area.useBox(box);
        assertEquals(new ScatterArea.Region(box), area.toArea().orElseThrow());
        assertEquals(32 * 16, area.columns());
        assertTrue(area.undoGroup(), "undo leaves box mode first");
        assertInstanceOf(ScatterArea.Stamps.class, area.toArea().orElseThrow());
        area.useBox(box);
        assertTrue(area.leaveBox());
        assertEquals(1, area.stampCount(), "the painted stamps were kept");
        assertTrue(area.clear());
        assertTrue(area.toArea().isEmpty());
        assertFalse(area.clear());
    }

    @Test
    void everyChangeBumpsTheVersion() {
        int start = area.version();
        area.add(ScatterArea.Stamp.paint(0, 0, 2));
        assertTrue(area.version() > start);
        int before = area.version();
        area.add(ScatterArea.Stamp.paint(0, 0, 2)); // skipped
        assertEquals(before, area.version());
    }
}
