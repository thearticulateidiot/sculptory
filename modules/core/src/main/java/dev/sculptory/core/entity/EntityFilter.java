package dev.sculptory.core.entity;

/**
 * Which entities a copy, cut, move, stack or export takes along. An entity belongs to a region when the block cell holding its position is in the region (a hanging
 * entity: its attached block). Players are never taken, and pasted entities always get new UUIDs.
 * Wire order: append only.
 */
public enum EntityFilter {
    /** Blocks only. */
    NONE,
    /**
     * Non-living entities (item frames, paintings, displays, interactions, markers, leash knots, minecarts, boats, end
     * crystals, modded non-living entities) and armor stands, minus transient ones (dropped items, XP orbs,
     * projectiles, falling blocks, primed TNT, lightning, area effect clouds, fishing bobbers, evoker fangs, eyes of
     * ender, fireworks). The editor's default.
     */
    DECORATIONS,
    /** {@link #DECORATIONS} plus mobs: every other living entity except players. */
    ALL
}
