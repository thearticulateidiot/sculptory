package dev.sculptory.core.scatter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The vanilla trees and features Scatter may grow ({@link ScatterSource.Feature}). Every id is a configured feature of Minecraft 1.21.1 (checked against the game's
 * {@code TreeConfiguredFeatures}, {@code MiscConfiguredFeatures}, {@code VegetationConfiguredFeatures} and
 * {@code UndergroundConfiguredFeatures}); the server refuses any other.
 */
public final class FeatureCatalog {
    private FeatureCatalog() {}

    /** What the Scatter window lists it under: "+ Tree" or "+ Feature". */
    public enum Kind {
        TREE,
        FEATURE
    }

    /**
     * One entry.
     *
     * @param id the configured feature id ({@code minecraft:fancy_oak})
     * @param nameKey the translation key of its name in the Scatter window
     * @param radius how far, in blocks, it may reach from its spot horizontally (the planner's footprint estimate)
     * @param height how tall it may grow from its spot, in blocks
     */
    public record FeatureDef(String id, Kind kind, String nameKey, int radius, int height) {
        public FeatureDef {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(nameKey);
            if (radius < 0 || height < 1) throw new IllegalArgumentException("Invalid feature footprint");
        }
    }

    /** Every entry, trees first, in the order the Scatter window lists them. */
    public static final List<FeatureDef> ALL = List.of(
            tree("oak", 3, 8),
            tree("fancy_oak", 6, 18),
            tree("fancy_oak_bees", 6, 18),
            tree("birch", 3, 9),
            tree("spruce", 3, 11),
            tree("pine", 3, 15),
            tree("mega_spruce", 6, 34),
            tree("mega_pine", 6, 34),
            tree("jungle_tree", 4, 14),
            tree("mega_jungle_tree", 9, 36),
            tree("jungle_bush", 3, 4),
            tree("acacia", 6, 10),
            tree("dark_oak", 5, 11),
            tree("cherry", 6, 11),
            tree("mangrove", 6, 15),
            tree("tall_mangrove", 7, 22),
            tree("azalea_tree", 4, 8),
            tree("swamp_oak", 5, 10),
            tree("crimson_fungus", 4, 15),
            tree("warped_fungus", 4, 15),
            tree("huge_red_mushroom", 3, 9),
            tree("huge_brown_mushroom", 4, 9),
            feature("forest_rock", 2, 4),
            feature("ice_spike", 5, 64),
            feature("moss_patch", 4, 3),
            feature("flower_default", 7, 2),
            feature("flower_plain", 7, 2),
            feature("flower_meadow", 7, 2),
            feature("flower_cherry", 7, 2),
            feature("patch_grass", 7, 2),
            feature("patch_tall_grass", 7, 3),
            feature("patch_large_fern", 7, 3),
            feature("patch_berry_bush", 7, 2),
            feature("patch_pumpkin", 7, 2));

    /** The entry for {@code id}, or empty when Scatter may not grow it. */
    public static Optional<FeatureDef> find(String id) {
        for (FeatureDef def : ALL) {
            if (def.id().equals(id)) return Optional.of(def);
        }
        return Optional.empty();
    }

    private static FeatureDef tree(String path, int radius, int height) {
        return new FeatureDef("minecraft:" + path, Kind.TREE, "sculptory.scatter.feature." + path, radius, height);
    }

    private static FeatureDef feature(String path, int radius, int height) {
        return new FeatureDef("minecraft:" + path, Kind.FEATURE, "sculptory.scatter.feature." + path, radius, height);
    }
}
