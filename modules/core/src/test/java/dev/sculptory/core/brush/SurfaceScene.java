package dev.sculptory.core.brush;

import dev.sculptory.core.testing.FakeStateSpace;

/**
 * A rough scene for Surface-mode brush tests at large radii, written through {@link Setter}: rolling ground, a wall with
 * bumps and dents on its west face and a cellar behind it, an overhang with bumps and a dent underneath, a stalactite,
 * a pond, plants, a stair and a chest. Deterministic.
 */
final class SurfaceScene {
    /** Where the scene writes. */
    @FunctionalInterface
    interface Setter {
        void set(int x, int y, int z, int state);
    }

    /** The scene's extent: x and z from {@code -EXTENT} to {@code EXTENT}, y from {@value #BASE} up. */
    static final int EXTENT = 44;
    static final int BASE = 40;
    /** The wall's west face (x), and the height of the ball's centre on it in {@link #wallDab}. */
    static final int WALL_X = 4;

    private SurfaceScene() {}

    static void build(FakeStateSpace states, Setter out) {
        int stone = states.state("minecraft:stone");
        int dirt = states.state("minecraft:dirt");
        int grass = states.state("minecraft:grass_block");
        int sand = states.state("minecraft:sand");
        int water = states.state("minecraft:water");
        int air = states.air();
        int shortGrass = states.state("minecraft:short_grass");
        int tallLower = states.state("minecraft:tall_grass[half=lower]");
        int tallUpper = states.state("minecraft:tall_grass[half=upper]");
        int stairs = states.state("minecraft:oak_stairs");
        int chest = states.state("minecraft:chest");
        // Rolling ground, grass on top.
        for (int x = -EXTENT; x <= EXTENT; x++) {
            for (int z = -EXTENT; z <= EXTENT; z++) {
                int top = 60 + Math.floorMod(x / 3 + z / 4 + ((x * 7 + z * 3) & 1), 7) + ((x * x + z) % 5 == 0 ? 1 : 0);
                for (int y = BASE; y <= top; y++) out.set(x, y, z, y == top ? grass : stone);
                int plant = Math.floorMod(x * 31 + z * 17, 13);
                if (plant == 0) out.set(x, top + 1, z, shortGrass);
                if (plant == 1) {
                    out.set(x, top + 1, z, tallLower);
                    out.set(x, top + 2, z, tallUpper);
                }
                if (plant == 2) out.set(x, top, z, sand);
            }
        }
        // A wall (x WALL_X..WALL_X + 6, z -20..20, to y 100) with a dirt band, bumps and dents on its west face.
        for (int x = WALL_X; x <= WALL_X + 6; x++) {
            for (int z = -20; z <= 20; z++) {
                for (int y = 60; y <= 100; y++) out.set(x, y, z, x == WALL_X && y == 86 ? dirt : stone);
            }
        }
        for (int i = 0; i < 40; i++) {
            int y = 66 + Math.floorMod(i * 37, 30), z = -18 + Math.floorMod(i * 53, 37);
            if (i % 2 == 0) {
                out.set(WALL_X - 1, y, z, i % 4 == 0 ? stone : dirt);
            } else {
                out.set(WALL_X, y, z, air);
            }
        }
        // A cellar behind the wall's face: open cells the ball reaches but that are not on the dab's side.
        for (int x = WALL_X + 2; x <= WALL_X + 4; x++) {
            for (int z = -3; z <= 3; z++) {
                for (int y = 78; y <= 81; y++) out.set(x, y, z, air);
            }
        }
        // An overhang west of the wall (x -20..0, z -10..10, y 86-90), bumps hanging under it and a dent in it.
        for (int x = -20; x <= 0; x++) {
            for (int z = -10; z <= 10; z++) {
                for (int y = 86; y <= 90; y++) out.set(x, y, z, stone);
            }
        }
        out.set(-14, 85, -2, stone);
        out.set(-10, 85, 2, stone);
        out.set(-12, 86, -5, air);
        out.set(-5, 85, 5, stone);
        out.set(-5, 84, 5, stone);
        out.set(-4, 85, 7, stone);
        out.set(-7, 86, 7, air);
        // A pond: sand bed at 57, water to 64.
        for (int x = -30; x <= -22; x++) {
            for (int z = 12; z <= 20; z++) {
                for (int y = 57; y <= 72; y++) out.set(x, y, z, y == 57 ? sand : y <= 64 ? water : air);
            }
        }
        // A stair on the wall's face, and a chest standing on the ground by it.
        out.set(WALL_X - 1, 75, 4, stairs);
        out.set(WALL_X - 2, 70, -6, chest);
    }

    /** A full-pressure dab on the wall's west face at (y, z), a quarter block into the open. */
    static Dab wallDab(int index, int y, int z) {
        return new Dab(index, WALL_X * 16 - 4, y * 16 + 8, z * 16 + 8, Dab.FULL_PRESSURE);
    }
}
