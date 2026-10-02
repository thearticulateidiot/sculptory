package dev.sculptory.server.platform;

/**
 * What the game says about each entity type, by type id ({@link Platform#entityRules}). Before the platform has
 * learned the types, every type is operator-only and never placed, and none hangs.
 */
public interface EntityRules {
    /** Whether only operators may write this type's data. */
    boolean operator(String typeId);

    /** Whether entities of this type are never placed from a clipboard or file. */
    boolean never(String typeId);

    /** Whether this type hangs on a block (its {@code TileX/Y/Z} count). */
    boolean hanging(String typeId);
}
