package dev.sculptory.fabric.client.editor.demo;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.TextMeasure;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the demo's caption goes: a dark plate at the bottom centre of the screen, its text enlarged whole steps
 * ({@link #scale}: 1 on a small window, 2 on the reference 2560x1600 at GUI scale 3, 3 on a very tall one) and wrapped
 * to at most {@value #WIDTH_SHARE} of the screen's width; the plate's bottom edge sits at {@code bottomLimit} (above
 * the editor's palette and hint line, or the hotbar). All in GUI pixels.
 *
 * @param scale the text's magnification
 * @param lines the caption's lines, after wrapping
 * @param plate the plate
 */
public record CaptionLayout(int scale, List<String> lines, Rect plate) {
    /** How much of the screen's width the text may take. */
    public static final double WIDTH_SHARE = 0.78;
    /** How many GUI pixels of height each magnification step wants. */
    static final int PIXELS_PER_STEP = 260;
    static final int MAX_SCALE = 3;
    /** The plate's padding around the text, in text pixels (multiplied by the scale). */
    static final int PAD_X = 7;
    static final int PAD_Y = 4;
    /** Space between lines, in text pixels. */
    static final int LINE_GAP = 2;

    public CaptionLayout {
        lines = List.copyOf(lines);
    }

    /** The magnification for a screen {@code height} GUI pixels tall. */
    public static int scaleFor(int height) {
        return Math.max(1, Math.min(MAX_SCALE, (int) Math.round(height / (double) PIXELS_PER_STEP)));
    }

    public static CaptionLayout of(int screenWidth, int screenHeight, int bottomLimit, TextMeasure text,
            String caption) {
        int scale = scaleFor(screenHeight);
        int maxTextWidth = Math.max(40, (int) (screenWidth * WIDTH_SHARE) / scale);
        List<String> lines = wrap(caption, text, maxTextWidth);
        int widest = 0;
        for (String line : lines) {
            widest = Math.max(widest, text.width(line));
        }
        int lineStep = text.lineHeight() + LINE_GAP;
        int plateWidth = (widest + 2 * PAD_X) * scale;
        int plateHeight = (lines.size() * lineStep - LINE_GAP + 2 * PAD_Y) * scale;
        int x = (screenWidth - plateWidth) / 2;
        int y = Math.max(0, bottomLimit - plateHeight);
        return new CaptionLayout(scale, lines, new Rect(x, y, plateWidth, plateHeight));
    }

    /** The y (GUI pixels) of line {@code index}'s top. */
    public int lineY(int index, TextMeasure text) {
        return plate.y() + (PAD_Y + index * (text.lineHeight() + LINE_GAP)) * scale;
    }

    /** The x (GUI pixels) of line {@code index}'s left edge, centred in the plate. */
    public int lineX(int index, TextMeasure text) {
        return plate.x() + (plate.width() - text.width(lines.get(index)) * scale) / 2;
    }

    /** Breaks {@code caption} at its own line breaks, then at spaces so no line is wider than {@code maxWidth}. */
    static List<String> wrap(String caption, TextMeasure text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : caption.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.trim().split(" +")) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (!line.isEmpty() && text.width(candidate) > maxWidth) {
                    lines.add(line.toString());
                    line = new StringBuilder(word);
                } else {
                    line = new StringBuilder(candidate);
                }
            }
            lines.add(line.toString());
        }
        return lines;
    }
}
