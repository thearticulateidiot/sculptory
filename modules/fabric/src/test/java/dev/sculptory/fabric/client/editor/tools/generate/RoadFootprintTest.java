package dev.sculptory.fabric.client.editor.tools.generate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.Box;
import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoadFootprintTest {
    private static final int AIR = 0;
    private static final int STONE = 1;
    private static final int GRAVEL = 2;
    private static final int COLOUR = 0xFFFFC857;

    /** A 3×2 road at y 64 with fill below at 63 and cleared air above at 65, plus one lone column two blocks higher. */
    private static GeneratedSource road() {
        GeneratedSource.Builder builder = GeneratedSource.builder(1_000);
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 2; z++) {
                builder.set(x, 63, z, STONE).set(x, 64, z, GRAVEL).set(x, 65, z, AIR).set(x, 66, z, AIR);
            }
        }
        builder.set(5, 66, 0, GRAVEL).set(5, 67, 0, AIR);
        return builder.build();
    }

    @Test
    void eachColumnKeepsItsTopmostBlockAndAirDoesNotCount() {
        RoadFootprint footprint = RoadFootprint.of(road(), AIR);
        assertNotNull(footprint);
        assertEquals(7, footprint.columns());
        assertEquals(64, footprint.top(1, 1), "the gravel over the fill, under the cleared air");
        assertEquals(66, footprint.top(5, 0));
        assertFalse(footprint.has(3, 0));
        assertEquals(Integer.MIN_VALUE, footprint.top(3, 0));
        assertTrue(RoadFootprint.of(GeneratedSource.empty(), AIR).isEmpty());
        GeneratedSource airOnly = GeneratedSource.builder(10).set(0, 64, 0, AIR).build();
        assertTrue(RoadFootprint.of(airOnly, AIR).isEmpty(), "a clearing alone has no footprint");
    }

    @Test
    void aRoadOverTheColumnCapHasNoFootprint() {
        GeneratedSource.Builder builder = GeneratedSource.builder(RoadFootprint.MAX_COLUMNS + 10);
        int side = (int) Math.ceil(Math.sqrt(RoadFootprint.MAX_COLUMNS + 1));
        int placed = 0;
        for (int x = 0; x < side && placed <= RoadFootprint.MAX_COLUMNS; x++) {
            for (int z = 0; z < side && placed <= RoadFootprint.MAX_COLUMNS; z++) {
                builder.set(x, 64, z, STONE);
                placed++;
            }
        }
        assertNull(RoadFootprint.of(builder.build(), AIR));
        GeneratedSource.Builder atCap = GeneratedSource.builder(RoadFootprint.MAX_COLUMNS + 10);
        for (int i = 0; i < RoadFootprint.MAX_COLUMNS; i++) atCap.set(i, 64, 0, STONE);
        assertEquals(RoadFootprint.MAX_COLUMNS, RoadFootprint.of(atCap.build(), AIR).columns(), "the cap itself fits");
    }

    /** The tint lies on every column's top face; the outline runs only along sides with no footprint beside them. */
    @Test
    void theTintCoversEveryColumnAndTheOutlineFollowsTheEdge() {
        RoadFootprint footprint = RoadFootprint.of(road(), AIR);
        RecordingDraw draw = new RecordingDraw();
        footprint.draw(draw, COLOUR);
        assertEquals(7, draw.quads.size());
        assertTrue(draw.quads.contains("1.0,1.0,2.0,2.0," + (65 + RoadFootprint.LIFT)), draw.quads.toString());
        assertTrue(draw.quads.contains("5.0,0.0,6.0,1.0," + (67 + RoadFootprint.LIFT)));
        for (double alpha : draw.quadAlphas) assertTrue(alpha < 0.5, "a translucent tint");
        // The 3×2 block's edge is 10 unit segments, the lone column's 4: drawn twice (through terrain, then in view).
        assertEquals(2 * (10 + 4), draw.lines.size(), draw.lines.toString());
        long through = draw.lines.stream().filter(l -> l.seeThrough).count();
        assertEquals(14, through);
        assertTrue(draw.lines.stream().anyMatch(l -> l.seeThrough && l.color == OverlayColors.scaleAlpha(COLOUR, 0.35)));
        assertTrue(draw.lines.stream().anyMatch(l -> !l.seeThrough && l.color == COLOUR));
        // No segment between two footprint columns: the line x = 1 along z 0..2 is inside the 3×2 block.
        assertFalse(draw.lines.stream().anyMatch(l -> l.x1 == 1 && l.x2 == 1 && l.z1 == 0 && l.z2 == 1), "no inner edges");
        assertTrue(draw.lines.stream().anyMatch(l -> l.x1 == 0 && l.x2 == 0 && l.z1 == 0 && l.z2 == 1
                && l.y1 == 65 + RoadFootprint.LIFT), "the west edge at the road's height");
        assertFalse(draw.seeThrough, "left depth-tested");
    }

    private static final class RecordingDraw implements WorldDraw {
        record Line(double x1, double y1, double z1, double x2, double y2, double z2, int color, boolean seeThrough) {}

        final List<String> quads = new ArrayList<>();
        final List<Double> quadAlphas = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();
        boolean seeThrough;

        @Override
        public void boxOutline(Box box, int argb) {}

        @Override
        public void boxFill(Box box, int argb) {}

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2, int argb) {
            lines.add(new Line(x1, y1, z1, x2, y2, z2, argb, seeThrough));
        }

        @Override
        public void ring(double centerX, double y, double centerZ, double radius, int argb) {}

        @Override
        public void seeThrough(boolean enabled) {
            seeThrough = enabled;
        }

        @Override
        public void surfaceQuad(double minX, double minZ, double maxX, double maxZ, double y, int argb) {
            quads.add(minX + "," + minZ + "," + maxX + "," + maxZ + "," + y);
            quadAlphas.add((argb >>> 24) / 255.0);
        }

        @Override
        public void shapeOutlines(List<? extends Region> regions, int argb, int copyArgb) {}
    }
}
