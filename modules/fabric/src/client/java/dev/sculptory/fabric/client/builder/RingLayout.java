package dev.sculptory.fabric.client.builder;

import dev.sculptory.fabric.client.editor.ui.Rect;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/**
 * Where the ring's chips and its centre plate go, in screen units. The
 * plate's text is wrapped to {@value #PLATE_SHARE} of the ring's radius, so it stays a block in the middle; where the
 * plate would still meet a chip (a small window: 1600×900 at GUI scale 3 is 534×300 units, radius 84) the ring is
 * widened or heightened just enough ({@link RingMenu#setAxes}) that every chip clears the plate by {@value #GAP}.
 * Pure geometry over a text-width function, so it is unit-tested at the reference sizes.
 */
public final class RingLayout {
    /** The plate's text is wrapped to this share of the ring's radius (its width). */
    public static final double PLATE_SHARE = 1.4;
    /** Between a chip and the plate, at least. */
    public static final int GAP = 4;
    public static final int CHIP_HEIGHT = 14;
    static final int CHIP_PADDING = 8;
    static final int PLATE_PADDING = 12;
    static final int LINE_GAP = 2;

    private final RingMenu menu;
    private final List<Rect> chips;
    private final Rect plate;
    private final List<String> lines;

    /**
     * @param names the entries' labels, in ring order
     * @param title the plate's first line (never wrapped)
     * @param detail the plate's text under the title, wrapped at spaces to the plate's width
     * @param textWidth the width of a text in screen units
     * @param fontHeight the height of a text line
     */
    public RingLayout(RingMenu menu, List<String> names, String title, String detail, ToIntFunction<String> textWidth,
                      int fontHeight) {
        this.menu = Objects.requireNonNull(menu);
        if (names.size() != menu.entries().size()) throw new IllegalArgumentException("One name per entry");
        int maxWidth = Math.max(textWidth.applyAsInt(title), (int) Math.round(menu.radius() * PLATE_SHARE));
        lines = new ArrayList<>();
        lines.add(title);
        lines.addAll(wrap(detail, maxWidth, textWidth));
        int plateWidth = 0;
        for (String line : lines) plateWidth = Math.max(plateWidth, textWidth.applyAsInt(line));
        plateWidth += PLATE_PADDING;
        int plateHeight = lines.size() * fontHeight + (lines.size() - 1) * LINE_GAP + 10;
        // Chips on the ring must clear the plate: widen and heighten the ring as needed (the side and top chips are
        // the nearest; the ring's width also grows with the widest chip).
        int widestChip = 0;
        for (String name : names) widestChip = Math.max(widestChip, textWidth.applyAsInt(name) + CHIP_PADDING);
        double radiusX = plateWidth / 2.0 + widestChip / 2.0 + GAP;
        double radiusY = plateHeight / 2.0 + CHIP_HEIGHT / 2.0 + GAP;
        menu.setAxes(radiusX, radiusY);
        chips = new ArrayList<>(names.size());
        for (int i = 0; i < names.size(); i++) {
            double[] at = menu.labelCentre(i);
            int width = textWidth.applyAsInt(names.get(i)) + CHIP_PADDING;
            chips.add(new Rect((int) Math.round(at[0] - width / 2.0), (int) Math.round(at[1] - CHIP_HEIGHT / 2.0), width,
                    CHIP_HEIGHT));
        }
        plate = new Rect((int) Math.round(menu.centreX() - plateWidth / 2.0), (int) Math.round(menu.centreY() - plateHeight / 2.0),
                plateWidth, plateHeight);
    }

    /** Entry {@code index}'s chip. */
    public Rect chip(int index) {
        return chips.get(index);
    }

    public List<Rect> chips() {
        return chips;
    }

    /** The centre plate. */
    public Rect plate() {
        return plate;
    }

    /** The plate's lines: the title, then the detail wrapped. */
    public List<String> lines() {
        return lines;
    }

    public RingMenu menu() {
        return menu;
    }

    /** {@code text} split at spaces into lines no wider than {@code maxWidth} (a single word may exceed it). */
    static List<String> wrap(String text, int maxWidth, ToIntFunction<String> textWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (word.isEmpty()) continue;
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && textWidth.applyAsInt(candidate) > maxWidth) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }
}
