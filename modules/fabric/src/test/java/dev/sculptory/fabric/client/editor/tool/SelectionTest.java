package dev.sculptory.fabric.client.editor.tool;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.Sha256;
import dev.sculptory.core.region.CellSet;
import dev.sculptory.core.region.Facing;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.region.ShapeKind;
import org.junit.jupiter.api.Test;

class SelectionTest {
    private static final Box BOX = new Box(new BlockPos(0, 60, 0), new BlockPos(9, 69, 9));

    private static Region.Cells cells() {
        return new Region.Cells(CellSet.builder().add(0, 60, 0).add(5, 61, 2).add(17, 62, 3).build());
    }

    @Test
    void aMovedCellSetKeepsItsRegionAndOnlyAddsToItsOffset() {
        Region.Cells cells = cells();
        Selection moved = Selection.of(cells).translate(3, 0, -1).translate(1, 2, 0);
        assertSame(cells, moved.base(), "no cell set is rebuilt while it moves");
        assertArrayEquals(new int[] {4, 2, -1}, moved.offset());
        assertEquals(cells.bounds().offset(4, 2, -1), moved.bounds(), "the bounds are cheap");
        assertFalse(moved.resizable());
        Region applied = moved.region();
        assertEquals(cells.translate(4, 2, -1), applied);
        assertSame(applied, moved.region(), "the move is applied once");
        assertSame(cells, Selection.of(cells).region(), "without a move the region is the region");
    }

    @Test
    void aBoxOrShapeMovesAtOnce() {
        Region.Shape cone = new Region.Shape(BOX, ShapeKind.CONE, Facing.NORTH);
        Selection moved = Selection.of(cone).translate(5, 1, 2);
        assertEquals(cone.translate(5, 1, 2), moved.base());
        assertArrayEquals(new int[3], moved.offset());
        assertTrue(moved.resizable());
        assertEquals(new Region.Cuboid(BOX.offset(-3, 0, 0)), Selection.of(new Region.Cuboid(BOX)).translate(-3, 0, 0).region());
        assertEquals(cone.translate(1, 0, 0), Selection.of(cone).movedTo(BOX.min().offset(1, 0, 0)).region());
    }

    @Test
    void movesStopAtTheLimitsInsteadOfFailing() {
        Region.Cells cells = cells();
        // A grazing-angle drag can ask for any distance: a cell set can't hold sections past ±2^21, so this must clamp.
        Selection far = Selection.of(cells).translate(Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE);
        Box bounds = far.bounds();
        assertEquals(Selection.HORIZONTAL_LIMIT, bounds.max().x());
        assertEquals(-Selection.VERTICAL_LIMIT, bounds.min().y());
        assertEquals(Selection.HORIZONTAL_LIMIT, bounds.max().z());
        assertEquals(cells.cellCount(), far.region().cellCount(), "and the move can be applied");
        Selection box = Selection.of(new Region.Cuboid(BOX)).translate(-Integer.MAX_VALUE, 0, 0);
        assertEquals(-Selection.HORIZONTAL_LIMIT, box.bounds().min().x());
        Selection back = far.movedTo(new BlockPos(0, 0, 0));
        assertEquals(new BlockPos(0, 0, 0), back.bounds().min(), "and back");
    }

    @Test
    void aSelectionWiderThanTheRangeStaysPut() {
        Box wide = new Box(new BlockPos(-40_000_000, 0, 0), new BlockPos(40_000_000, 0, 0));
        Selection selection = Selection.of(new Region.Cuboid(wide));
        assertSame(selection, selection.translate(5, 0, 0), "it can't move along x at all");
        assertEquals(wide.offset(0, 3, 0), selection.translate(5, 3, 0).bounds());
    }

    @Test
    void noHostTurnsAShapeIntoItsBoxByDefault() throws NoSuchMethodException {
        // Every host must say what its selection is: a default that answered with the bounds silently lost shapes.
        assertFalse(ToolContext.class.getMethod("selectionRegion").isDefault());
        assertFalse(ToolContext.class.getMethod("setSelectionRegion", Region.class).isDefault());
        assertFalse(ToolContext.class.getMethod("selectionState").isDefault());
        assertFalse(dev.sculptory.fabric.client.editor.clipboard.ClipboardActions.Host.class
                .getMethod("selectionRegion").isDefault());
        assertFalse(dev.sculptory.fabric.client.editor.tools.select.SelectionWindow.Host.class
                .getMethod("setSelectionRegion", Region.class).isDefault());
        assertFalse(dev.sculptory.fabric.client.editor.tools.select.SelectionWindow.Host.class
                .getMethod("selectionState").isDefault());
    }

    @Test
    void anUploadedRegionIsNotASelection() {
        Region uploaded = new Region.Uploaded(new Sha256("ab".repeat(32)), BOX, 5);
        assertThrows(IllegalArgumentException.class, () -> Selection.of(uploaded));
    }
}
