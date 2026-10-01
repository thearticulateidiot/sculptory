package dev.sculptory.fabric.client.editor.tools.generate;

import dev.sculptory.core.generate.GeneratedSource;
import dev.sculptory.core.generate.GroundMap;
import dev.sculptory.fabric.client.editor.render.OverlayColors;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Objects;

/**
 * The top of a generated road, column by column, for the overlay: the road's cells sit at and under the terrain's
 * surface, so its ghost is hidden by the ground it replaces (only cells in the air show), and this draws the road's
 * footprint where the builder looks: a tint over each column's top generated block, and an outline around the
 * footprint stepping with the terrain. Built off the client thread from the preview's cells; a road over
 * {@link #MAX_COLUMNS} columns has none (its bounds are outlined instead).
 */
public final class RoadFootprint {
    /** The most columns drawn column by column (a 512-block road at the widest, or a 2 km lane). */
    public static final int MAX_COLUMNS = 16_384;
    /** Height above a column's top face the footprint floats at, as the brush outlines do. */
    public static final double LIFT = 0.05;

    /** Column key ({@link GroundMap#column}) to the y of the column's topmost generated block. */
    private final Long2IntOpenHashMap top;

    private RoadFootprint(Long2IntOpenHashMap top) {
        this.top = top;
    }

    /**
     * The footprint of {@code source}: every column with a generated block (not air), at its topmost one. Empty for a
     * source of air alone; null over {@link #MAX_COLUMNS} columns.
     */
    public static RoadFootprint of(GeneratedSource source, int air) {
        Objects.requireNonNull(source);
        Long2IntOpenHashMap top = new Long2IntOpenHashMap();
        top.defaultReturnValue(Integer.MIN_VALUE);
        boolean[] tooMany = {false};
        source.forEach((x, y, z, state) -> {
            if (state == air || tooMany[0]) return;
            long key = GroundMap.column(x, z);
            int known = top.get(key);
            if (known == Integer.MIN_VALUE) {
                if (top.size() >= MAX_COLUMNS) {
                    tooMany[0] = true;
                    return;
                }
                top.put(key, y);
            } else if (y > known) {
                top.put(key, y);
            }
        });
        return tooMany[0] ? null : new RoadFootprint(top);
    }

    public int columns() {
        return top.size();
    }

    public boolean isEmpty() {
        return top.isEmpty();
    }

    public boolean has(int x, int z) {
        return top.containsKey(GroundMap.column(x, z));
    }

    /** The y of the column's topmost generated block, or {@link Integer#MIN_VALUE} outside the footprint. */
    public int top(int x, int z) {
        return top.get(GroundMap.column(x, z));
    }

    /**
     * Draws the footprint: a translucent tint over each column's top face (depth-tested, so it lies on the ground)
     * and, along every side of a column with no footprint beside it, an outline segment at that column's height, seen
     * faintly through the terrain and fully where it is in view.
     */
    public void draw(WorldDraw d, int argb) {
        int tint = OverlayColors.scaleAlpha(argb, 0.28);
        int faint = OverlayColors.scaleAlpha(argb, 0.35);
        d.seeThrough(false);
        for (var entry : top.long2IntEntrySet()) {
            int x = GroundMap.columnX(entry.getLongKey());
            int z = GroundMap.columnZ(entry.getLongKey());
            d.surfaceQuad(x, z, x + 1, z + 1, entry.getIntValue() + 1 + LIFT, tint);
        }
        for (boolean through : new boolean[] {true, false}) {
            d.seeThrough(through);
            int colour = through ? faint : argb;
            for (var entry : top.long2IntEntrySet()) {
                int x = GroundMap.columnX(entry.getLongKey());
                int z = GroundMap.columnZ(entry.getLongKey());
                double y = entry.getIntValue() + 1 + LIFT;
                if (!has(x, z - 1)) d.line(x, y, z, x + 1, y, z, colour);
                if (!has(x, z + 1)) d.line(x, y, z + 1, x + 1, y, z + 1, colour);
                if (!has(x - 1, z)) d.line(x, y, z, x, y, z + 1, colour);
                if (!has(x + 1, z)) d.line(x + 1, y, z, x + 1, y, z + 1, colour);
            }
        }
        d.seeThrough(false);
    }
}
