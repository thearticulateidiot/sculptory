package dev.sculptory.core.generate;

import dev.sculptory.core.BlockPos;
import java.util.ArrayList;
import java.util.List;

/**
 * The build the free-rotation comparison turns: a small stone-brick house, 8 by 8 (an even footprint, to show the true
 * centre) and 9 tall, with oak log corners, window openings, a plank floor, a gable roof of oak stairs over planks, a
 * door, a bed, a chest, a lantern, a torch, a tall grass and a poppy: straight walls for jagged edges, a slope for the
 * stairs and slabs, two-cell blocks to keep whole and directional blocks to snap. Plain data in vanilla state text, the
 * same everywhere.
 *
 * <p>The comparison lays copies out in a grid ({@link #placement}): one column per resampling way (the unturned build
 * first, then nearest, exact count, smooth) and one row per angle set ({@link #ANGLES}), {@link #SPACING} blocks apart.
 */
public final class RotationShowcase {
    public static final int SIZE_X = 8;
    public static final int SIZE_Y = 9;
    public static final int SIZE_Z = 8;
    /** Blocks between the copies' corners: room for a 45° turn of the build with a gap. */
    public static final int SPACING = 18;
    /**
     * The angle sets compared, {yaw, pitch, roll} in 1/100°: a yaw of 30°, a yaw of 45° with a pitch of 20°, and a
     * roll of 25°.
     */
    public static final List<int[]> ANGLES = List.of(new int[] {3_000, 0, 0}, new int[] {4_500, 2_000, 0},
            new int[] {0, 0, 2_500});
    /** The columns after the unturned build, as their settings label them. */
    public static final List<String> WAYS = List.of("Nearest", "Exact count", "Smooth");

    /** One cell of the build: local position and vanilla state text. */
    public record Cell(int x, int y, int z, String state) {}

    private RotationShowcase() {}

    /** The build's cells (air left out), in a fixed order. */
    public static List<Cell> cells() {
        List<Cell> out = new ArrayList<>();
        // Floor.
        for (int x = 0; x < SIZE_X; x++) {
            for (int z = 0; z < SIZE_Z; z++) out.add(new Cell(x, 0, z, "minecraft:oak_planks"));
        }
        // Walls, logs at the corners, window openings in three walls, a door opening in the south wall.
        for (int y = 1; y <= 4; y++) {
            for (int x = 0; x < SIZE_X; x++) {
                for (int z = 0; z < SIZE_Z; z++) {
                    boolean edgeX = x == 0 || x == SIZE_X - 1, edgeZ = z == 0 || z == SIZE_Z - 1;
                    if (!edgeX && !edgeZ) continue;
                    if (edgeX && edgeZ) {
                        out.add(new Cell(x, y, z, "minecraft:oak_log[axis=y]"));
                        continue;
                    }
                    boolean window = (y == 2 || y == 3) && ((z == 0 && (x == 2 || x == 5))
                            || (x == 0 && (z == 3 || z == 4)) || (x == SIZE_X - 1 && (z == 3 || z == 4)));
                    boolean door = z == SIZE_Z - 1 && x == 3 && (y == 1 || y == 2);
                    if (window || door) continue;
                    out.add(new Cell(x, y, z, "minecraft:stone_bricks"));
                }
            }
        }
        out.add(new Cell(3, 1, SIZE_Z - 1, "minecraft:oak_door[facing=north,half=lower]"));
        out.add(new Cell(3, 2, SIZE_Z - 1, "minecraft:oak_door[facing=north,half=upper]"));
        // Inside: a bed (head to the north), a chest, a lantern, a torch, a tall grass and a poppy.
        out.add(new Cell(2, 1, 2, "minecraft:red_bed[facing=north,part=foot]"));
        out.add(new Cell(2, 1, 1, "minecraft:red_bed[facing=north,part=head]"));
        out.add(new Cell(5, 1, 1, "minecraft:chest[facing=south]"));
        out.add(new Cell(6, 1, 6, "minecraft:lantern[hanging=false]"));
        out.add(new Cell(1, 1, 6, "minecraft:torch"));
        out.add(new Cell(5, 1, 5, "minecraft:tall_grass[half=lower]"));
        out.add(new Cell(5, 2, 5, "minecraft:tall_grass[half=upper]"));
        out.add(new Cell(4, 1, 4, "minecraft:poppy"));
        // A gable roof, its ridge running east-west: stairs on each slope's edge row, planks under them.
        for (int k = 0; k < 4; k++) {
            int y = 5 + k;
            for (int x = 0; x < SIZE_X; x++) {
                out.add(new Cell(x, y, k, "minecraft:oak_stairs[facing=south,half=bottom]"));
                out.add(new Cell(x, y, SIZE_Z - 1 - k, "minecraft:oak_stairs[facing=north,half=bottom]"));
                for (int z = k + 1; z < SIZE_Z - 1 - k; z++) out.add(new Cell(x, y, z, "minecraft:oak_planks"));
            }
        }
        return List.copyOf(out);
    }

    /**
     * Where copy {@code column} (0 the unturned build, then one per {@link #WAYS}) of angle set {@code row} goes:
     * the corner of its cell in the grid, relative to the grid's corner.
     */
    public static BlockPos placement(int column, int row) {
        if (column < 0 || column > WAYS.size() || row < 0 || row >= ANGLES.size()) {
            throw new IllegalArgumentException("No copy " + column + "," + row);
        }
        return new BlockPos(column * SPACING, 0, row * SPACING);
    }
}
