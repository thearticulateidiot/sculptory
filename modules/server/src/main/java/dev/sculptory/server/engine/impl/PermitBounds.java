package dev.sculptory.server.engine.impl;

import dev.sculptory.core.Box;

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
     * Throws when the check is on and cell (x, y, z) lies outside {@code asked} (null: no bounds reach it). A job then
     * fails loudly (outcome FAILED, logged), a brush dab is logged as failed, so a test sees it.
     */
    static void check(Box asked, int x, int y, int z, String what) {
        if (CHECKED && (asked == null || !asked.contains(x, y, z))) {
            throw new IllegalStateException(what + " asked a permit about " + x + "," + y + "," + z
                    + " outside the bounds it was asked for, " + asked);
        }
    }
}
