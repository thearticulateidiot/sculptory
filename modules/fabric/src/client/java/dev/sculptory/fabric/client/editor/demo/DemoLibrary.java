package dev.sculptory.fabric.client.editor.demo;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.palette.BlockPalette;
import dev.sculptory.core.palette.PalettePattern;
import dev.sculptory.core.schem.SchematicCodec;
import dev.sculptory.core.schem.SchematicMetadata;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.fabric.client.editor.mock.MockStateSpace;
import dev.sculptory.fabric.library.PaletteFile;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The shared library the demo starts with: a {@value #FOLDER} folder of three schematics (a cottage, a boulder, a lamp
 * post) and two palettes, as a server's builders would have put there. Written into the integrated server's library
 * before Enter, the folder emptied first: it is the demo's own (the run directory is the project's), so a run never
 * finds an earlier run's saves there. Nothing else in the library is touched.
 */
public final class DemoLibrary {
    public static final String FOLDER = "starter-kit";
    public static final String COTTAGE = FOLDER + "/cottage.schem";
    public static final String BOULDER = FOLDER + "/boulder.schem";
    public static final String LAMP_POST = FOLDER + "/lamp_post.schem";
    public static final String PALETTES = FOLDER + "/palettes";
    public static final String MOSSY_STONE = PALETTES + "/mossy_stone.palette.json";
    public static final String FOREST_FLOOR = PALETTES + "/forest_floor.palette.json";
    /** Where the demo saves the house it copied (the folder is emptied each run, so no overwrite question). */
    public static final String SAVED_HOUSE = FOLDER + "/house.schem";

    private static final String AUTHOR = "Sculptory demo";

    private DemoLibrary() {}

    /** Empties {@code <libraryRoot>/starter-kit} and writes the kit into it. */
    public static void seed(Path libraryRoot, int dataVersion) throws IOException {
        Path folder = libraryRoot.resolve(FOLDER);
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        Files.createDirectories(libraryRoot.resolve(PALETTES));
        StateSpace states = new MockStateSpace();
        writeSchematic(libraryRoot.resolve(COTTAGE), cottage(states), "Cottage", dataVersion);
        writeSchematic(libraryRoot.resolve(BOULDER), boulder(states), "Boulder", dataVersion);
        writeSchematic(libraryRoot.resolve(LAMP_POST), lampPost(states), "Lamp post", dataVersion);
        Files.write(libraryRoot.resolve(MOSSY_STONE), PaletteFile.encode(mossyStone(), dataVersion));
        Files.write(libraryRoot.resolve(FOREST_FLOOR), PaletteFile.encode(forestFloor(), dataVersion));
    }

    private static void writeSchematic(Path file, Clipboard clipboard, String name, int dataVersion)
            throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            SchematicCodec.write(out, clipboard, new SchematicMetadata(name, AUTHOR, null, List.of(), null),
                    dataVersion);
        }
    }

    // ---- The pieces (x east, z south, y up; anchored at the middle of the bottom layer) ----

    /**
     * A 7x7 oak cottage (walls four high) on a cobblestone floor under a gabled stair roof that overhangs a block all
     * round.
     */
    static Clipboard cottage(StateSpace states) {
        Shape s = new Shape(states, 9, 8, 9);
        s.fill(1, 0, 1, 7, 0, 7, "minecraft:cobblestone");
        s.fill(1, 1, 1, 7, 4, 1, "minecraft:oak_planks");
        s.fill(1, 1, 7, 7, 4, 7, "minecraft:oak_planks");
        s.fill(1, 1, 1, 1, 4, 7, "minecraft:oak_planks");
        s.fill(7, 1, 1, 7, 4, 7, "minecraft:oak_planks");
        for (int x : new int[] {1, 7}) {
            for (int z : new int[] {1, 7}) {
                s.fill(x, 1, z, x, 4, z, "minecraft:oak_log[axis=y]");
            }
        }
        s.set(2, 2, 7, "minecraft:glass_pane[east=true,west=true]");
        s.set(6, 2, 7, "minecraft:glass_pane[east=true,west=true]");
        s.set(2, 2, 1, "minecraft:glass_pane[east=true,west=true]");
        s.set(6, 2, 1, "minecraft:glass_pane[east=true,west=true]");
        s.set(1, 2, 4, "minecraft:glass_pane[north=true,south=true]");
        s.set(7, 2, 4, "minecraft:glass_pane[north=true,south=true]");
        s.set(4, 1, 7, "minecraft:spruce_door[facing=south,half=lower,hinge=left]");
        s.set(4, 2, 7, "minecraft:spruce_door[facing=south,half=upper,hinge=left]");
        // The roof: stair rows climbing from both long sides to a plank ridge, the gable ends in planks.
        for (int step = 0; step < 4; step++) {
            int y = 4 + step;
            s.fill(0, y, step, 8, y, step, "minecraft:spruce_stairs[facing=south]");
            s.fill(0, y, 8 - step, 8, y, 8 - step, "minecraft:spruce_stairs[facing=north]");
            if (step > 1) {
                s.fill(1, y - 1, step, 1, y - 1, 8 - step, "minecraft:oak_planks");
                s.fill(7, y - 1, step, 7, y - 1, 8 - step, "minecraft:oak_planks");
            }
        }
        s.fill(0, 7, 4, 8, 7, 4, "minecraft:spruce_planks");
        return s.build();
    }

    /** A rough 5x4x5 lump of stone, andesite and mossy cobblestone. */
    static Clipboard boulder(StateSpace states) {
        Shape s = new Shape(states, 5, 4, 5);
        String[] rock = {"minecraft:stone", "minecraft:andesite", "minecraft:cobblestone", "minecraft:stone",
                "minecraft:mossy_cobblestone"};
        for (int x = 0; x < 5; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 5; z++) {
                    double dx = (x - 2) / 2.6;
                    double dy = y / 3.4;
                    double dz = (z - 2) / 2.3;
                    if (dx * dx + dy * dy + dz * dz <= 1.0) {
                        s.set(x, y, z, rock[Math.floorMod(x * 7 + y * 3 + z * 5, rock.length)]);
                    }
                }
            }
        }
        return s.build();
    }

    /** A dark oak post on a stone brick foot with an arm and a hanging lantern. */
    static Clipboard lampPost(StateSpace states) {
        // Anchored at the post, not the middle of the two columns (the arm's side is air at the bottom).
        Shape s = new Shape(states, 2, 5, 1, BlockPos.ORIGIN);
        s.set(0, 0, 0, "minecraft:stone_bricks");
        s.fill(0, 1, 0, 0, 3, 0, "minecraft:dark_oak_fence");
        s.set(0, 4, 0, "minecraft:dark_oak_fence[east=true]");
        s.set(1, 4, 0, "minecraft:dark_oak_fence[west=true]");
        s.set(1, 3, 0, "minecraft:lantern[hanging=true]");
        return s.build();
    }

    static BlockPalette mossyStone() {
        return new BlockPalette(List.of(new BlockPalette.Entry("minecraft:stone_bricks", 4),
                new BlockPalette.Entry("minecraft:mossy_stone_bricks", 3),
                new BlockPalette.Entry("minecraft:cracked_stone_bricks", 2),
                new BlockPalette.Entry("minecraft:cobblestone", 1)));
    }

    static BlockPalette forestFloor() {
        PalettePattern patches = new PalettePattern(PalettePattern.Kind.PATCHES, 4, PalettePattern.RANDOM.edge(),
                PalettePattern.RANDOM.steepnessEdge(), 0);
        return new BlockPalette(List.of(new BlockPalette.Entry("minecraft:podzol", 3),
                new BlockPalette.Entry("minecraft:moss_block", 3),
                new BlockPalette.Entry("minecraft:coarse_dirt", 2),
                new BlockPalette.Entry("minecraft:rooted_dirt", 1)), patches);
    }

    /** A clipboard being drawn: block states by their text, anchored at the bottom layer's middle unless told. */
    private static final class Shape {
        private final StateSpace states;
        private final Clipboard.Builder builder;

        Shape(StateSpace states, int sx, int sy, int sz) {
            this(states, sx, sy, sz, new BlockPos(sx / 2, 0, sz / 2));
        }

        Shape(StateSpace states, int sx, int sy, int sz, BlockPos anchor) {
            this.states = states;
            this.builder = Clipboard.builder(states, new BlockPos(sx, sy, sz)).anchor(anchor).source("demo");
        }

        void set(int x, int y, int z, String state) {
            int handle = states.parse(state);
            if (handle < 0) {
                throw new IllegalArgumentException("not a block state: " + state);
            }
            builder.set(x, y, z, handle);
        }

        void fill(int ax, int ay, int az, int bx, int by, int bz, String state) {
            for (int x = Math.min(ax, bx); x <= Math.max(ax, bx); x++) {
                for (int y = Math.min(ay, by); y <= Math.max(ay, by); y++) {
                    for (int z = Math.min(az, bz); z <= Math.max(az, bz); z++) {
                        set(x, y, z, state);
                    }
                }
            }
        }

        Clipboard build() {
            return builder.build();
        }
    }
}
