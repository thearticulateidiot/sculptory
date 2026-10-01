package dev.sculptory.fabric.world;

import dev.sculptory.core.scatter.FeatureCatalog;
import dev.sculptory.core.scatter.Outcome;
import dev.sculptory.core.scatter.ScatterPlanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grows the vanilla trees and features of a scatter preview: each spot's
 * configured feature ({@link FeatureCatalog}, looked up in the world's registry) generates into a fresh
 * {@link CapturingWorldAccess} with its own seeded random, so the world is never changed and the preview holds exactly
 * what the commit writes. Server thread only.
 *
 * <p>A growth's span is its catalog entry's reach plus {@value #SPAN_MARGIN} blocks around the spot, {@value #SPAN_DOWN}
 * below it (roots, the dirt under a trunk, an ice spike's base) and its height plus {@value #SPAN_MARGIN} above.
 * With {@code survive}, a tree grows only where its sapling (propagule, azalea, fungus) could stand
 * ({@link Outcome#SURVIVAL} otherwise), as in world generation; huge mushrooms and the other features check their own
 * ground. A feature that says it did not grow, or throws, is {@link Outcome#FEATURE_FAILED} (a throw is logged once
 * per feature).
 */
public final class FeatureGrower implements ScatterPlanner.Grower {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");

    /** Blocks a growth may write beyond its catalog entry's reach and height. */
    public static final int SPAN_MARGIN = 3;
    /** Blocks a growth may write below its spot. */
    public static final int SPAN_DOWN = 16;

    /** Each tree's sapling, by configured feature id. */
    private static final Map<String, Block> SAPLINGS = saplings();

    private final ServerWorld world;
    private final FabricStateSpace states;
    private final FeatureCatalog.FeatureDef[] defs;
    private final boolean survive;
    private final ChunkGenerator generator;
    private final Map<String, ConfiguredFeature<?, ?>> features = new HashMap<>();
    private final Set<String> warned = new HashSet<>();
    private int lastClipped;

    /**
     * @param defs per scatter source index, its catalog entry, or {@code null} for a held or block source
     * @param survive whether trees grow only where their sapling could stand
     */
    public FeatureGrower(ServerWorld world, FabricStateSpace states, List<FeatureCatalog.FeatureDef> defs,
                         boolean survive) {
        this.world = Objects.requireNonNull(world);
        this.states = Objects.requireNonNull(states);
        this.defs = new ArrayList<>(defs).toArray(new FeatureCatalog.FeatureDef[0]);
        this.survive = survive;
        this.generator = world.getChunkManager().getChunkGenerator();
    }

    /** How far from its spot a growth of {@code def} may write horizontally. */
    public static int reach(FeatureCatalog.FeatureDef def) {
        return def.radius() + SPAN_MARGIN;
    }

    /** How far above its spot a growth of {@code def} may write. */
    public static int above(FeatureCatalog.FeatureDef def) {
        return def.height() + SPAN_MARGIN;
    }

    /** Whether every catalog entry names a configured feature of this world (the GameTests check it). */
    public static List<String> missing(ServerWorld world) {
        Registry<ConfiguredFeature<?, ?>> registry = world.getRegistryManager().get(RegistryKeys.CONFIGURED_FEATURE);
        List<String> missing = new ArrayList<>();
        for (FeatureCatalog.FeatureDef def : FeatureCatalog.ALL) {
            if (registry.get(Identifier.of(def.id())) == null) missing.add(def.id());
        }
        return missing;
    }

    @Override
    public ScatterPlanner.Growth grow(int source, int x, int y, int z, long seed, ScatterPlanner.GrownView grown) {
        FeatureCatalog.FeatureDef def = source < defs.length ? defs[source] : null;
        if (def == null) throw new IllegalArgumentException("Source " + source + " is not a tree or feature");
        ConfiguredFeature<?, ?> feature = feature(def.id());
        if (feature == null) return ScatterPlanner.Growth.failed(Outcome.FEATURE_FAILED, 1);
        BlockPos origin = new BlockPos(x, y, z);
        lastClipped = 0;
        CapturingWorldAccess capture = new CapturingWorldAccess(world, states, grown, origin, reach(def), SPAN_DOWN,
                above(def), seed);
        if (survive && def.kind() == FeatureCatalog.Kind.TREE) {
            Block sapling = SAPLINGS.get(def.id());
            if (sapling != null && !sapling.getDefaultState().canPlaceAt(capture, origin)) {
                return capture.reachedUnloaded() ? ScatterPlanner.Growth.failed(Outcome.UNLOADED, capture.reads())
                        : ScatterPlanner.Growth.failed(Outcome.SURVIVAL, capture.reads());
            }
        }
        boolean generated;
        try {
            generated = feature.generate(capture, generator, capture.getRandom(), origin);
        } catch (RuntimeException e) {
            if (warned.add(def.id())) {
                LOG.warn("Sculptory: growing {} for a scatter preview failed: {}", def.id(), e.toString());
            }
            generated = false;
        }
        lastClipped = capture.clipped();
        return capture.finish(generated);
    }

    /** How many writes the last growth had clipped by its span (tests: the catalog's reach and height hold). */
    public int lastClipped() {
        return lastClipped;
    }

    private ConfiguredFeature<?, ?> feature(String id) {
        if (features.containsKey(id)) return features.get(id);
        Registry<ConfiguredFeature<?, ?>> registry = world.getRegistryManager().get(RegistryKeys.CONFIGURED_FEATURE);
        ConfiguredFeature<?, ?> feature = registry.get(Identifier.of(id));
        if (feature == null && warned.add(id)) LOG.warn("Sculptory: this world has no configured feature {}", id);
        features.put(id, feature);
        return feature;
    }

    private static Map<String, Block> saplings() {
        Map<String, Block> map = new HashMap<>();
        for (String oak : List.of("oak", "fancy_oak", "fancy_oak_bees", "swamp_oak")) {
            map.put("minecraft:" + oak, Blocks.OAK_SAPLING);
        }
        map.put("minecraft:birch", Blocks.BIRCH_SAPLING);
        for (String spruce : List.of("spruce", "pine", "mega_spruce", "mega_pine")) {
            map.put("minecraft:" + spruce, Blocks.SPRUCE_SAPLING);
        }
        for (String jungle : List.of("jungle_tree", "mega_jungle_tree", "jungle_bush")) {
            map.put("minecraft:" + jungle, Blocks.JUNGLE_SAPLING);
        }
        map.put("minecraft:acacia", Blocks.ACACIA_SAPLING);
        map.put("minecraft:dark_oak", Blocks.DARK_OAK_SAPLING);
        map.put("minecraft:cherry", Blocks.CHERRY_SAPLING);
        map.put("minecraft:mangrove", Blocks.MANGROVE_PROPAGULE);
        map.put("minecraft:tall_mangrove", Blocks.MANGROVE_PROPAGULE);
        map.put("minecraft:azalea_tree", Blocks.AZALEA);
        map.put("minecraft:crimson_fungus", Blocks.CRIMSON_FUNGUS);
        map.put("minecraft:warped_fungus", Blocks.WARPED_FUNGUS);
        return Map.copyOf(map);
    }
}
