package dev.sculptory.fabric.client.editor.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** {@link Covers}: what a panel's see-through covers leave of it, and drawing it only there. */
class CoversTest {
    @Test
    void thePartsAreExactlyTheAreaLessTheCoversAndDontOverlap() {
        Random random = new Random(42);
        for (int round = 0; round < 300; round++) {
            Rect area = new Rect(random.nextInt(20), random.nextInt(20), 1 + random.nextInt(40), 1 + random.nextInt(40));
            List<Rect> covers = new ArrayList<>();
            for (int i = random.nextInt(5); i > 0; i--) {
                covers.add(new Rect(random.nextInt(60) - 5, random.nextInt(60) - 5, random.nextInt(30),
                        random.nextInt(30)));
            }
            List<Rect> parts = Covers.uncovered(area, covers);
            for (int x = area.x(); x < area.right(); x++) {
                for (int y = area.y(); y < area.bottom(); y++) {
                    int px = x;
                    int py = y;
                    boolean covered = covers.stream().anyMatch(cover -> cover.contains(px, py));
                    long in = parts.stream().filter(part -> part.contains(px, py)).count();
                    assertEquals(covered ? 0 : 1, in, "(" + x + ", " + y + ") in " + area + " less " + covers);
                }
            }
            for (Rect part : parts) {
                assertFalse(part.isEmpty());
                assertEquals(part, area.intersect(part), "inside the area");
            }
        }
    }

    @Test
    void anAreaNothingCoversIsDrawnOnceWithoutAClipAndAHiddenOneNotAtAll() {
        Rect area = new Rect(10, 10, 100, 50);
        assertEquals(List.of(area), Covers.uncovered(area, List.of(new Rect(200, 0, 10, 10))));
        assertEquals(List.of(), Covers.uncovered(area, List.of(new Rect(0, 0, 200, 200))));

        RecordingGraphics g = new RecordingGraphics();
        int[] drawn = new int[1];
        Covers.drawUncovered(g, area, List.of(new Rect(200, 0, 10, 10)), () -> drawn[0]++);
        assertEquals(1, drawn[0]);
        assertEquals(0, g.maxClipDepth, "no clip when nothing covers it");
        Covers.drawUncovered(g, area, List.of(new Rect(0, 0, 200, 200)), () -> drawn[0]++);
        assertEquals(1, drawn[0], "hidden: not drawn");
        // A cover in the middle: the band above, the band below, the parts left and right of it.
        Covers.drawUncovered(g, area, List.of(new Rect(40, 20, 20, 20)), () -> drawn[0]++);
        assertEquals(5, drawn[0]);
        assertTrue(g.isBalanced());
    }
}
