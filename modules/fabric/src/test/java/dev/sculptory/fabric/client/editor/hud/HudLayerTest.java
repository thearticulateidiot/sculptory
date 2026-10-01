package dev.sculptory.fabric.client.editor.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.Size;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiGraphics;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A HUD element drawn around see-through covers ({@link HudLayer#render(UiGraphics, long, List)}): what it draws just
 * past its bounds (a text shadow, a border) is kept up to {@link HudLayer#COVER_MARGIN}, as when nothing covers it,
 * while what lies under a cover is not drawn.
 */
class HudLayerTest {
    /** A 40x10 element at (10, 10) that draws texts inside its bounds, just past its right edge, and farther. */
    private static final class Edgy extends Node {
        @Override
        protected Size measureContent(UiContext ctx, int maxWidth) {
            return new Size(40, 10);
        }

        @Override
        public void render(UiGraphics g, UiContext ctx) {
            g.fill(bounds, 0xFF202020);
            g.text("inside", bounds.x() + 2, bounds.y() + 1, 0xFFFFFFFF, false);
            g.text("under", bounds.x() + 2, bounds.bottom() - 2, 0xFFFFFFFF, false);
            g.text("edge", bounds.right() + HudLayer.COVER_MARGIN - 1, bounds.y() + 1, 0xFFFFFFFF, false);
            g.text("far", bounds.right() + HudLayer.COVER_MARGIN, bounds.y() + 1, 0xFFFFFFFF, false);
        }
    }

    private final HudLayer hud = new HudLayer(McFontText.INSTANCE, Theme.DARK);
    private final Edgy element = new Edgy();

    HudLayerTest() {
        hud.add(element, (size, w, h) -> new Rect(10, 10, size.width(), size.height()), () -> true);
        hud.layout(200, 100);
        assertEquals(new Rect(10, 10, 40, 10), element.bounds());
    }

    @Test
    void withoutACoverTheElementIsDrawnUnclipped() {
        RecordingGraphics g = new RecordingGraphics();
        hud.render(g, 0, List.of());
        assertEquals(List.of("inside", "under", "edge", "far"), g.shownTexts());
        assertTrue(g.isBalanced());
    }

    @Test
    void aroundACoverTheElementKeepsWhatItDrawsWithinTheMarginPastItsBounds() {
        RecordingGraphics g = new RecordingGraphics();
        // A see-through toast over the element's bottom-left corner: what lies under it is left out.
        hud.render(g, 0, List.of(new Rect(0, 17, 30, 20)));
        assertEquals(List.of("inside", "edge"), g.shownTexts(), "'under' is under the cover, 'far' past the margin");
        assertTrue(g.isBalanced());

        // A cover beside the element, not over it: drawn as if there were none.
        g = new RecordingGraphics();
        hud.render(g, 0, List.of(new Rect(100, 10, 30, 20)));
        assertEquals(List.of("inside", "under", "edge", "far"), g.shownTexts());
    }
}
