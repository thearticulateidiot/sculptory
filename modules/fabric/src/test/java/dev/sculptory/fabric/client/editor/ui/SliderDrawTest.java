package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.widget.Slider;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The slider's handle never runs through its label or value (Strength at 0.80 read "0|80", Radius "Ra|dius"): where it
 * crosses the text only its ends above and below the text are drawn, measured like Minecraft's font at every value.
 */
class SliderDrawTest {
    private static final Theme THEME = Theme.DARK;
    private final UiContext ctx = new UiContext(McFontText.INSTANCE, THEME);

    /** The handle's fills: the accent colour, as drawn while not hovered. */
    private static List<Rect> handle(RecordingGraphics g) {
        return g.paints.stream().filter(paint -> paint.text() == null && paint.argb() == THEME.accent)
                .map(RecordingGraphics.Paint::rect).toList();
    }

    /** Where a drawn text's glyphs are: its anchor, its width and the font's line height. */
    private Rect textBox(RecordingGraphics g, String text) {
        Rect anchor = g.textAnchor(text);
        return new Rect(anchor.x(), anchor.y(), McFontText.INSTANCE.width(text), McFontText.INSTANCE.lineHeight() - 1);
    }

    private RecordingGraphics draw(Slider slider, int width) {
        slider.layout(ctx, new Rect(10, 20, width, THEME.controlHeight));
        RecordingGraphics g = new RecordingGraphics();
        slider.render(g, ctx);
        return g;
    }

    @Test
    void theHandleNeverCrossesTheLabelOrTheValue() {
        for (int width : new int[] {80, 120, 160}) {
            for (int step = 0; step <= 20; step++) {
                double value = step * 0.05;
                Slider strength = Slider.ofDecimal("Strength", 0, 1, 0.05, value, null);
                RecordingGraphics g = draw(strength, width);
                String shown = strength.formattedValue();
                List<Rect> handle = handle(g);
                assertFalse(handle.isEmpty(), "the handle shows at " + shown);
                assertEquals(2, g.drawnTexts().size(), "the label (cut short when narrow) and the value");
                for (String text : g.drawnTexts()) {
                    Rect box = textBox(g, text);
                    for (Rect part : handle) {
                        assertFalse(part.intersects(box), "the handle " + part + " over \"" + text + "\" " + box
                                + " at " + shown + ", " + width + " wide");
                    }
                }
            }
        }
    }

    @Test
    void awayFromTheTextTheHandleIsAsTallAsTheTrackAndOverItOnlyItsEndsShow() {
        Slider radius = Slider.ofInt("Radius", 1, 100, 60, null);
        List<Rect> clear = handle(draw(radius, 160));
        assertEquals(List.of(new Rect(clear.get(0).x(), 21, 2, THEME.controlHeight - 2)), clear,
                "between the label and the value: the whole handle");

        Slider under = Slider.ofDecimal("Strength", 0, 1, 0.05, 0.95, null);
        RecordingGraphics g = draw(under, 160);
        List<Rect> ends = handle(g);
        assertEquals(2, ends.size(), "over the value: above and below it " + ends);
        Rect value = textBox(g, "0.95");
        assertTrue(ends.get(0).bottom() <= value.y() && ends.get(1).y() >= value.bottom(), ends + " around " + value);
        assertTrue(ends.get(0).y() == 21 && ends.get(1).bottom() == 20 + THEME.controlHeight - 1,
                "from the track's top to its bottom: " + ends);
    }
}
