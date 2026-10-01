package dev.sculptory.core.state;

/** Per-state flag bits returned by {@link StateSpace#flags(int)}. */
public final class StateFlags {
    private StateFlags() {}

    /** Any air block (air, cave air, void air). */
    public static final int AIR = 1;
    /** A fluid block such as water or lava, at any level. */
    public static final int FLUID_BLOCK = 2;
    /** A waterloggable state whose waterlogged property is true. */
    public static final int WATERLOGGED = 4;
    /** The state has a waterlogged property. */
    public static final int WATERLOGGABLE = 8;
    /** The state carries a block entity. */
    public static final int HAS_BLOCK_ENTITY = 16;
    /** Block-entity NBT needs operator rights to place (Fabric: {@code BlockEntity.copyItemDataRequiresOperator()}). */
    public static final int OPERATOR_NBT = 32;
    /** A full, movement-blocking, non-replaceable cube that terrain brushes treat as ground. */
    public static final int TERRAIN_SOLID = 64;
    /** Replaceable by placement (air, fluids, short grass...). */
    public static final int REPLACEABLE = 128;
    /** Plants and foliage that terrain brushes skip over when finding the surface. */
    public static final int VEGETATION = 256;
    /** Affected by gravity (sand, gravel...). */
    public static final int FALLING = 512;
    /**
     * The lower half of a block that stands two cells tall, its other half right above (Fabric: a vanilla two-block
     * plant, {@code TallPlantBlock} such as tall grass, sunflowers, small dripleaf, or a door, by its {@code half}).
     * Other blocks with a {@code half} property (stairs, modded vertical slabs) are not.
     */
    public static final int LOWER_HALF = 1024;
    /** The upper half of such a block, its other half right below. */
    public static final int UPPER_HALF = 2048;
    /** Either half: {@link #has} with this is true for both. */
    public static final int DOUBLE_TALL = LOWER_HALF | UPPER_HALF;
    /**
     * A block that lives only under water: it holds water whatever its properties (Fabric: a plant whose fluid is
     * water and that has no {@code waterlogged} property, such as seagrass, tall seagrass and kelp), or it dies out of
     * water (live coral: plants, fans, wall fans and coral blocks). Scatter places it under water only.
     */
    public static final int AQUATIC = 4096;
    /**
     * A block that goes on a water surface (Fabric: its item is a {@code PlaceableOnWaterItem}, as for lily pads and
     * frogspawn). Scatter places it on the air cell above still water.
     */
    public static final int ON_WATER = 8192;
    /**
     * The state holds water, still or flowing: a water block at any level, a waterlogged state, or a block that holds
     * water whatever its properties (seagrass, kelp, bubble columns). See {@link Water}.
     */
    public static final int WATER = 16384;
    /**
     * A player can stand in and walk through the state: it has no collision shape (Fabric: an empty
     * {@code getCollisionShape}: air, fluids, short grass, flowers, torches, open cells). Jump and Through land the player's feet and head in such cells.
     */
    public static final int NO_COLLISION = 32768;

    public static boolean has(int flags, int flag) {
        return (flags & flag) != 0;
    }
}
