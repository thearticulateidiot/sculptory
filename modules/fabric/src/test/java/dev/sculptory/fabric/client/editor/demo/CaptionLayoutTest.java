package dev.sculptory.fabric.client.editor.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The caption bar's place and size, at the reference screen (about 854x498 GUI units at GUI scale 3) and others. */
class CaptionLayoutTest {
    /** Six pixels a character, nine tall: vanilla's font, near enough. */
    private static final TextMeasure TEXT = new TextMeasure() {
        @Override
        public int width(String text) {
            return text.length() * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        @Override
        public String trimToWidth(String text, int maxWidth) {
            return text.substring(0, Math.min(text.length(), maxWidth / 6));
        }
    };
    private static final int REFERENCE_WIDTH = 854;
    private static final int REFERENCE_HEIGHT = 498;
    /** Above the palette and hint line. */
    private static final int REFERENCE_BOTTOM = 440;

    @Test
    void theTextIsTwiceTheSizeOnTheReferenceScreenAndPlainOnASmallWindow() {
        assertEquals(2, CaptionLayout.scaleFor(REFERENCE_HEIGHT));
        assertEquals(1, CaptionLayout.scaleFor(300));
        assertEquals(3, CaptionLayout.scaleFor(800));
        assertEquals(3, CaptionLayout.scaleFor(2_000));
        assertEquals(1, CaptionLayout.scaleFor(100));
    }

    @Test
    void aShortCaptionSitsOnOneLineCentredAboveTheLimit() {
        CaptionLayout layout = CaptionLayout.of(REFERENCE_WIDTH, REFERENCE_HEIGHT, REFERENCE_BOTTOM, TEXT, "B opens the editor");
        assertEquals(List.of("B opens the editor"), layout.lines());
        Rect plate = layout.plate();
        assertEquals(REFERENCE_BOTTOM, plate.bottom());
        int centre = plate.x() + plate.width() / 2;
        assertTrue(Math.abs(centre - REFERENCE_WIDTH / 2) <= 1, "centre " + centre);
        // 18 characters of 6 px at scale 2, plus the padding.
        assertEquals((18 * 6 + 2 * CaptionLayout.PAD_X) * 2, plate.width());
        assertEquals((9 + 2 * CaptionLayout.PAD_Y) * 2, plate.height());
        // The text starts inside the plate and is centred in it.
        int textX = layout.lineX(0, TEXT);
        assertEquals(plate.x() + CaptionLayout.PAD_X * 2, textX);
        assertEquals(plate.y() + CaptionLayout.PAD_Y * 2, layout.lineY(0, TEXT));
    }

    @Test
    void aLongCaptionWrapsToTheWidthShareAndStaysOnScreen() {
        String caption = "The top bar: menus, the active block, undo and redo, the status and the fly speed, and then"
                + " some more words so that it must wrap onto several lines";
        CaptionLayout layout = CaptionLayout.of(REFERENCE_WIDTH, REFERENCE_HEIGHT, REFERENCE_BOTTOM, TEXT, caption);
        assertTrue(layout.lines().size() >= 2, layout.lines().toString());
        int maxWidth = (int) (REFERENCE_WIDTH * CaptionLayout.WIDTH_SHARE);
        for (String line : layout.lines()) {
            assertTrue(TEXT.width(line) * layout.scale() <= maxWidth, line);
        }
        assertEquals(caption.replace("  ", " "), String.join(" ", layout.lines()));
        Rect plate = layout.plate();
        assertTrue(plate.x() >= 0 && plate.right() <= REFERENCE_WIDTH, plate.toString());
        assertTrue(plate.y() >= 0 && plate.bottom() == REFERENCE_BOTTOM, plate.toString());
        assertEquals(layout.lineY(0, TEXT) + (9 + CaptionLayout.LINE_GAP) * 2, layout.lineY(1, TEXT));
    }

    @Test
    void ownLineBreaksAreKeptAndASmallWindowStillFitsTheCaption() {
        CaptionLayout layout = CaptionLayout.of(320, 240, 200, TEXT, "One\nTwo lines");
        assertEquals(List.of("One", "Two lines"), layout.lines());
        assertEquals(1, layout.scale());
        assertTrue(layout.plate().right() <= 320 && layout.plate().y() >= 0);
        // A plate taller than the room above the limit is clamped to the top rather than drawn off screen.
        CaptionLayout tall = CaptionLayout.of(320, 240, 20, TEXT, "a\nb\nc\nd");
        assertEquals(0, tall.plate().y());
    }
}
