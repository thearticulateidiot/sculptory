package dev.sculptory.fabric.client.builder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.protocol.v2.BuilderPower;
import java.util.List;
import java.util.function.ToIntFunction;
import org.junit.jupiter.api.Test;

/**
 * The ring's chips never meet the centre plate, at the reference window sizes (1600×900 at GUI scale 3: 534×300 units;
 * 2560×1600 at GUI scale 3 with UI 100%: 854×498 units) with the English labels and the widest plate texts; the plate
 * stays a block in the middle (its text wrapped to 1.4 × radius), and the ring is only widened where it must be.
 */
class RingLayoutTest {
    /** Minecraft's font averages about 6 units per character (5 plus the gap); "W" and "M" are wider. */
    private static final ToIntFunction<String> WIDTH = text -> {
        int width = 0;
        for (char c : text.toCharArray()) width += c == 'W' || c == 'M' || c == 'm' || c == 'w' ? 7 : c == 'i' || c == 'l' || c == ' ' ? 4 : 6;
        return width;
    };
    private static final int FONT = 9;
    private static final List<String> NAMES = List.of("Long reach", "Place in air", "Replace", "Bulldozer", "Keep shape",
            "Force place", "Mirror", "Tinker");
    private static final String TITLE = "Builder powers";
    private static final String HINT = "Release on a power to switch it · tap: Place in air";
    private static final String LONGEST_DESCRIPTION = "Hold and sweep to break everything the crosshair crosses (Shift: only the "
            + "first block's kind)";

    /** The ring as BuilderClient opens it in a window of {@code width} × {@code height} units. */
    private static RingMenu ring(int width, int height) {
        return new RingMenu(List.of(BuilderPower.values()), width / 2.0, height / 2.0,
                Math.max(40, Math.min(width, height) * 0.28), 0);
    }

    private static void assertClear(RingLayout layout, int width, int height) {
        Rect plate = layout.plate();
        Rect screen = new Rect(0, 0, width, height);
        for (int i = 0; i < layout.chips().size(); i++) {
            Rect chip = layout.chip(i);
            assertFalse(chip.intersects(plate), NAMES.get(i) + " " + chip + " meets the plate " + plate);
            assertTrue(screen.contains(chip.x(), chip.y()) && screen.contains(chip.right() - 1, chip.bottom() - 1),
                    NAMES.get(i) + " " + chip + " leaves the " + width + "×" + height + " screen");
        }
        assertTrue(plate.width() <= Math.max(WIDTH.applyAsInt(TITLE), (int) Math.round(layout.menu().radius() * RingLayout.PLATE_SHARE))
                + RingLayout.PLATE_PADDING, "the plate is wrapped to its share of the radius: " + plate);
    }

    @Test
    void at1600x900GuiScale3TheSideChipsClearThePlate() {
        RingMenu menu = ring(534, 300);
        assertEquals(84, Math.round(menu.radius()));
        RingLayout layout = new RingLayout(menu, NAMES, TITLE, HINT, WIDTH, FONT);
        assertClear(layout, 534, 300);
        assertTrue(layout.lines().size() >= 3, "the hint wraps: " + layout.lines());
        assertTrue(menu.radiusX() > menu.radius(), "the ring is widened for the side chips");
        assertEquals(menu.radius(), menu.radiusY(), "and not heightened");
        // A hovered power's description is longer still.
        assertClear(new RingLayout(menu, NAMES, "Bulldozer", LONGEST_DESCRIPTION, WIDTH, FONT), 534, 300);
    }

    @Test
    void atTheOwnersSizeTheRingKeepsItsRadius() {
        RingMenu menu = ring(854, 498);
        assertEquals(139, Math.round(menu.radius()));
        RingLayout layout = new RingLayout(menu, NAMES, TITLE, HINT, WIDTH, FONT);
        assertClear(layout, 854, 498);
        assertEquals(menu.radius(), menu.radiusX(), "room enough: the ring stays a circle");
        assertClear(new RingLayout(menu, NAMES, "Bulldozer", LONGEST_DESCRIPTION, WIDTH, FONT), 854, 498);
    }

    @Test
    void hoverFollowsAWidenedRing() {
        RingMenu menu = ring(534, 300);
        new RingLayout(menu, NAMES, TITLE, HINT, WIDTH, FONT);
        double[] replace = menu.labelCentre(2);
        menu.pointer(replace[0], replace[1]);
        assertEquals(2, menu.hovered(), "the pointer on the Replace chip picks Replace");
        double[] mirror = menu.labelCentre(6);
        menu.pointer(mirror[0], mirror[1]);
        assertEquals(6, menu.hovered());
        double[] between = menu.labelCentre(1);
        menu.pointer(between[0], between[1]);
        assertEquals(1, menu.hovered());
    }

    @Test
    void wrappingSplitsAtSpacesOnly() {
        // "power to switch" is 82 units here, one word too many for 80.
        assertEquals(List.of("Release on a", "power to", "switch it ·", "tap: Long", "reach"),
                RingLayout.wrap("Release on a power to switch it · tap: Long reach", 80, WIDTH));
        assertEquals(List.of("Supercalifragilistic"), RingLayout.wrap("Supercalifragilistic", 20, WIDTH),
                "a word wider than the plate stays whole");
        assertEquals(List.of(), RingLayout.wrap("", 80, WIDTH));
    }
}
