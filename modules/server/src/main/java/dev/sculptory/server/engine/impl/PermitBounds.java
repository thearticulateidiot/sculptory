package dev.sculptory.server.engine.impl;

import dev.sculptory.core.Box;
import dev.sculptory.server.engine.ChunkPermit;

/**
 * A development check that every cell a permit is asked about lies inside the bounds the permit was asked for. A
 * permit answers only for those bounds ({@code PermissionService.chunk}): a per-cell permit denies outside its layers,
 * and a compact one is only known to be right inside them, so a program or brush writing outside its bounds would be
 * judged wrongly under protection that differs with height. On with {@code -Dsculptory.checkPermitBounds=true}, which
 * the GameTest runs (both loaders) and the JUnit runs set; off on servers, where an ask outside the bounds is judged
 * by the permit as it stands.
 */
final class PermitBounds {
    static final String PROPERTY = "sculptory.checkPermitBounds";
    static final boolean CHECKED = Boolean.getBoolean(PROPERTY);

    private PermitBounds() {}

    /**
     * Throws when the check is on and cell (x, y, z) lies outside {@code asked} (the box the permit was asked for; null:
     * no box reaches the cell), or outside the layers of {@code permit} when it is per cell. A job then fails loudly
     * (outcome FAILED, logged), a brush dab is refused and logged, so a test sees it. {@code what} names the asker in
     * the message, which is built only then.
     */
    static void check(Box asked, ChunkPermit permit, int x, int y, int z, Object what) {
        if (!CHECKED) return;
        if (asked == null || !asked.contains(x, y, z)) {
            throw new IllegalStateException(what + " asked a permit about " + x + "," + y + "," + z
                    + " outside the bounds it was asked for, " + asked);
        }
        if (permit instanceof ChunkPermit.Cells cells && (y < cells.minY() || y > cells.maxY())) {
            throw new IllegalStateException(what + " asked a permit about " + x + "," + y + "," + z
                    + " outside its layers " + cells.minY() + ".." + cells.maxY());
        }
    }

    /**
     * Throws when the check is on and {@code permit} is per cell without layers for every height from {@code minY} to
     * {@code maxY}: a source handing out a permit asked for other heights than those requested.
     */
    static void checkLayers(ChunkPermit permit, int minY, int maxY, Object what) {
        if (!CHECKED) return;
        if (permit instanceof ChunkPermit.Cells cells && (minY < cells.minY() || maxY > cells.maxY())) {
            throw new IllegalStateException(what + " answered heights " + minY + ".." + maxY
                    + " with a permit for the layers " + cells.minY() + ".." + cells.maxY());
        }
    }
}
