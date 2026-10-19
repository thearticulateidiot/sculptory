package dev.sculptory.fabric.client.editor.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The colours of a selection handle, in view and seen through blocks. */
class HandlePaintTest {
    @Test
    void aHiddenHandleIsFaintButVisibleInTheSameColours() {
        for (int color : new int[] {SelectionBoxRenderer.Style.DEFAULT.handle(),
                SelectionBoxRenderer.Style.DEFAULT.handleHovered()}) {
            SelectionBoxRenderer.HandlePaint paint = SelectionBoxRenderer.HandlePaint.of(color);
            assertEquals(color, paint.fill(), "in view: as before");
            assertEquals(0xFF, OverlayColors.alpha(paint.edge()));
            assertEquals(color & 0xFFFFFF, paint.hiddenFill() & 0xFFFFFF, "the same hue through blocks");
            assertEquals(paint.edge() & 0xFFFFFF, paint.hiddenEdge() & 0xFFFFFF);
            int hiddenFill = OverlayColors.alpha(paint.hiddenFill());
            int hiddenEdge = OverlayColors.alpha(paint.hiddenEdge());
            assertTrue(hiddenFill >= 0x3C && hiddenFill < OverlayColors.alpha(color) / 2, "faint fill: " + hiddenFill);
            assertTrue(hiddenEdge > OverlayColors.alpha(SelectionBoxRenderer.Style.DEFAULT.seeThroughEdge())
                    && hiddenEdge < 0xFF, "an outline readable through a wall, fainter than in view: " + hiddenEdge);
        }
    }

    @Test
    void theHoveredHandleStaysDistinctThroughBlocks() {
        SelectionBoxRenderer.HandlePaint plain = SelectionBoxRenderer.HandlePaint.of(
                SelectionBoxRenderer.Style.DEFAULT.handle());
        SelectionBoxRenderer.HandlePaint hovered = SelectionBoxRenderer.HandlePaint.of(
                SelectionBoxRenderer.Style.DEFAULT.handleHovered());
        assertTrue((plain.hiddenEdge() & 0xFFFFFF) != (hovered.hiddenEdge() & 0xFFFFFF));
    }
}
