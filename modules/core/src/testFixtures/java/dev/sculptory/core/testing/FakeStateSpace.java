package dev.sculptory.core.testing;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.state.VerticalFlip;
import dev.sculptory.core.transform.Mirror;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.ToIntFunction;

/**
 * A small, deterministic {@link StateSpace} for tests. Handles are assigned in block declaration order,
 * then by property values (properties sorted by name, the last varying fastest). Handle 0 is air.
 *
 * <p>Blocks: air, stone, dirt, grass_block[snowy], water[level 0-15], oak_stairs[facing,half,shape,waterlogged],
 * oak_log[axis], oak_slab[type,waterlogged], chest[facing,waterlogged] (block entity), sand (falling),
 * short_grass (vegetation), the modded {@code testmod:widget[facing]} with six facings, the plants poppy,
 * tall_grass[half=lower|upper] (flagged as the two halves of a double-tall block) and pink_petals[facing,flower_amount],
 * seagrass (holds water, aquatic), sea_pickle[pickles,waterlogged] (waterlogged by default), the modded
 * {@code testmod:vertical_slab[half=lower|upper]} (not double-tall), and for water plants kelp[age 0-25] and kelp_plant
 * (aquatic, a column: kelp on top of kelp_plant), tall_seagrass[half] (aquatic, double-tall),
 * tube_coral_fan[waterlogged] (aquatic, waterlogged by default), lily_pad (goes on water), sugar_cane[age 0-15] (a
 * column of itself), lava[level 0-15] and bubble_column (holds water, not a plant); for the upside-down flip torch,
 * lantern[hanging], hopper[facing] (down and the sides), oak_door[facing,half] (double-tall) and the modded
 * {@code testmod:gizmo[facing,spin]}, whose {@code spin} no vanilla block has.
 * Tags: {@code minecraft:logs}, {@code minecraft:dirt}, {@code minecraft:sand}, {@code minecraft:stairs}.
 *
 * <p>Rotation turns horizontal facings clockwise (north, east, south, west) and swaps log axis x/z on odd
 * turns. Mirroring swaps east/west ({@link Mirror#X}) or north/south ({@link Mirror#Z}) facings and the
 * handedness of stair shapes. This is geometrically correct and does not reproduce vanilla quirks.
 */
public final class FakeStateSpace implements StateSpace {
    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");

    private record Property(String name, List<String> values) {
        String defaultValue() {
            return values.get(0);
        }
    }

    private record BlockDef(NamespacedId id, List<Property> properties, ToIntFunction<Map<String, String>> flags,
                            Set<NamespacedId> tags) {}

    private final Map<NamespacedId, BlockDef> blocks = new LinkedHashMap<>();
    private final List<BlockDescriptor> descriptors = new ArrayList<>();
    private final List<Integer> flagList = new ArrayList<>();
    private final Map<BlockDescriptor, Integer> handles = new HashMap<>();
    private final int[] flags;
    /** Built on first use. */
    private volatile VerticalFlip flip;

    public FakeStateSpace() {
        int solid = StateFlags.TERRAIN_SOLID;
        // What a player walks through (air, fluids, grass, flowers, torches): Jump and Through land in such cells.
        int open = StateFlags.NO_COLLISION;
        define("minecraft:air", s -> StateFlags.AIR | StateFlags.REPLACEABLE | open, Set.of());
        define("minecraft:stone", s -> solid, Set.of());
        define("minecraft:dirt", s -> solid, Set.of("minecraft:dirt"));
        define("minecraft:grass_block", s -> solid, Set.of("minecraft:dirt"),
                new Property("snowy", List.of("false", "true")));
        List<String> levels = new ArrayList<>();
        for (int level = 0; level <= 15; level++) levels.add(Integer.toString(level));
        define("minecraft:water", s -> StateFlags.FLUID_BLOCK | StateFlags.REPLACEABLE | StateFlags.WATER | open, Set.of(),
                new Property("level", levels));
        define("minecraft:oak_stairs", s -> 0, Set.of("minecraft:stairs"),
                new Property("facing", HORIZONTAL),
                new Property("half", List.of("bottom", "top")),
                new Property("shape", List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right")),
                new Property("waterlogged", List.of("false", "true")));
        define("minecraft:oak_log", s -> solid, Set.of("minecraft:logs"),
                new Property("axis", List.of("y", "x", "z")));
        define("minecraft:oak_slab", s -> "double".equals(s.get("type")) ? solid : 0, Set.of(),
                new Property("type", List.of("bottom", "top", "double")),
                new Property("waterlogged", List.of("false", "true")));
        define("minecraft:chest", s -> StateFlags.HAS_BLOCK_ENTITY, Set.of(),
                new Property("facing", HORIZONTAL),
                new Property("waterlogged", List.of("false", "true")));
        define("minecraft:sand", s -> StateFlags.FALLING | solid, Set.of("minecraft:sand"));
        define("minecraft:short_grass", s -> StateFlags.VEGETATION | StateFlags.REPLACEABLE | open, Set.of());
        define("testmod:widget", s -> 0, Set.of(),
                new Property("facing", List.of("north", "east", "south", "west", "up", "down")));
        // Plants for block variants (appended, so the handles above do not move).
        int plant = StateFlags.VEGETATION | StateFlags.REPLACEABLE;
        define("minecraft:poppy", s -> StateFlags.VEGETATION | open, Set.of());
        define("minecraft:tall_grass",
                s -> plant | ("lower".equals(s.get("half")) ? StateFlags.LOWER_HALF : StateFlags.UPPER_HALF), Set.of(),
                new Property("half", List.of("lower", "upper")));
        define("minecraft:pink_petals", s -> StateFlags.VEGETATION, Set.of(), new Property("facing", HORIZONTAL),
                new Property("flower_amount", List.of("1", "2", "3", "4")));
        // Water for block variants: seagrass holds water whatever its properties; a sea pickle is waterlogged by default.
        define("minecraft:seagrass", s -> plant | StateFlags.WATER | StateFlags.AQUATIC, Set.of());
        define("minecraft:sea_pickle", s -> StateFlags.VEGETATION, Set.of(),
                new Property("pickles", List.of("1", "2", "3", "4")),
                new Property("waterlogged", List.of("true", "false")));
        // A modded block with a lower/upper half that is not a two-block plant: placed as one cell.
        define("testmod:vertical_slab", s -> 0, Set.of(), new Property("half", List.of("lower", "upper")));
        // Water plants (appended, so the handles above do not move).
        int aquatic = StateFlags.VEGETATION | StateFlags.WATER | StateFlags.AQUATIC;
        define("minecraft:kelp", s -> aquatic, Set.of(), new Property("age", numbers(25)));
        define("minecraft:kelp_plant", s -> aquatic, Set.of());
        define("minecraft:tall_seagrass", s -> aquatic | StateFlags.REPLACEABLE
                | ("lower".equals(s.get("half")) ? StateFlags.LOWER_HALF : StateFlags.UPPER_HALF), Set.of(),
                new Property("half", List.of("lower", "upper")));
        define("minecraft:tube_coral_fan", s -> StateFlags.AQUATIC, Set.of(),
                new Property("waterlogged", List.of("true", "false")));
        define("minecraft:lily_pad", s -> StateFlags.VEGETATION | StateFlags.ON_WATER, Set.of());
        define("minecraft:sugar_cane", s -> StateFlags.VEGETATION, Set.of(), new Property("age", numbers(15)));
        define("minecraft:lava", s -> StateFlags.FLUID_BLOCK | StateFlags.REPLACEABLE | open, Set.of(),
                new Property("level", numbers(15)));
        define("minecraft:bubble_column", s -> StateFlags.WATER, Set.of());
        // Roof materials (generators; appended so the handles above do not move): the full blocks the stairs derive.
        define("minecraft:oak_planks", s -> solid, Set.of("minecraft:planks"));
        define("minecraft:stone_bricks", s -> solid, Set.of());
        define("minecraft:stone_brick_stairs", s -> 0, Set.of("minecraft:stairs"),
                new Property("facing", HORIZONTAL),
                new Property("half", List.of("bottom", "top")),
                new Property("shape", List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right")),
                new Property("waterlogged", List.of("false", "true")));
        define("minecraft:stone_brick_slab", s -> "double".equals(s.get("type")) ? solid : 0, Set.of(),
                new Property("type", List.of("bottom", "top", "double")),
                new Property("waterlogged", List.of("false", "true")));
        // Road materials (the Path generator's defaults).
        define("minecraft:cobblestone", s -> solid, Set.of());
        define("minecraft:andesite", s -> solid, Set.of());
        // The upside-down flip (appended): a torch, a hanging or standing lantern, a hopper, a door, and a modded block
        // with a property no vanilla block has.
        define("minecraft:torch", s -> open, Set.of());
        define("minecraft:lantern", s -> 0, Set.of(), new Property("hanging", List.of("false", "true")));
        define("minecraft:hopper", s -> StateFlags.HAS_BLOCK_ENTITY, Set.of(),
                new Property("facing", List.of("down", "north", "south", "west", "east")));
        define("minecraft:oak_door", s -> "lower".equals(s.get("half")) ? StateFlags.LOWER_HALF : StateFlags.UPPER_HALF,
                Set.of(), new Property("facing", HORIZONTAL), new Property("half", List.of("lower", "upper")));
        define("testmod:gizmo", s -> 0, Set.of(),
                new Property("facing", List.of("north", "east", "south", "west", "up", "down")),
                new Property("spin", List.of("false", "true")));
        flags = flagList.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The handle for {@code spec} (missing properties take defaults). Throws if unknown. */
    public int state(String spec) {
        int h = parse(spec);
        if (h < 0) throw new IllegalArgumentException("Unknown fake state: " + spec);
        return h;
    }

    /** All block ids, in declaration order. */
    public List<NamespacedId> blockIds() {
        return List.copyOf(blocks.keySet());
    }

    @Override
    public int size() {
        return descriptors.size();
    }

    @Override
    public int air() {
        return 0;
    }

    @Override
    public int flags(int h) {
        Objects.checkIndex(h, size());
        return flags[h];
    }

    @Override
    public String format(int h) {
        return describe(h).format();
    }

    @Override
    public int parse(String spec) {
        BlockDescriptor descriptor;
        try {
            descriptor = BlockDescriptor.parse(spec);
        } catch (IllegalArgumentException malformed) {
            return -1;
        }
        return resolve(descriptor);
    }

    @Override
    public BlockDescriptor describe(int h) {
        Objects.checkIndex(h, size());
        return descriptors.get(h);
    }

    @Override
    public int resolve(BlockDescriptor d) {
        BlockDef def = blocks.get(d.block());
        if (def == null) return -1;
        Map<String, String> full = new TreeMap<>();
        for (Property property : def.properties()) {
            String value = d.properties().getOrDefault(property.name(), property.defaultValue());
            if (!property.values().contains(value)) return -1;
            full.put(property.name(), value);
        }
        if (!full.keySet().containsAll(d.properties().keySet())) return -1;
        Integer h = handles.get(BlockDescriptor.of(d.block(), full));
        return h == null ? -1 : h;
    }

    @Override
    public NamespacedId blockId(int h) {
        return describe(h).block();
    }

    @Override
    public boolean inTag(int h, NamespacedId tag) {
        return blocks.get(blockId(h)).tags().contains(tag);
    }

    @Override
    public int rotate(int h, int clockwiseQuarterTurns) {
        int turns = Math.floorMod(clockwiseQuarterTurns, 4);
        BlockDescriptor d = describe(h);
        if (turns == 0) return h;
        String facing = d.get("facing");
        if (facing != null && HORIZONTAL.contains(facing)) {
            d = d.with("facing", HORIZONTAL.get((HORIZONTAL.indexOf(facing) + turns) % 4));
        }
        String axis = d.get("axis");
        if (axis != null && (turns & 1) == 1 && !axis.equals("y")) {
            d = d.with("axis", axis.equals("x") ? "z" : "x");
        }
        return known(d);
    }

    @Override
    public int mirror(int h, Mirror m) {
        BlockDescriptor d = describe(h);
        if (m == Mirror.NONE) return h;
        String facing = d.get("facing");
        if (facing != null) {
            String swapped = switch (m) {
                case X -> swap(facing, "east", "west");
                case Z -> swap(facing, "north", "south");
                case NONE -> facing;
            };
            d = d.with("facing", swapped);
        }
        String shape = d.get("shape");
        if (shape != null) {
            if (shape.endsWith("_left")) d = d.with("shape", shape.replace("_left", "_right"));
            else if (shape.endsWith("_right")) d = d.with("shape", shape.replace("_right", "_left"));
        }
        return known(d);
    }

    @Override
    public int withWaterlogged(int h, boolean on) {
        BlockDescriptor d = describe(h);
        if (d.get("waterlogged") == null) return h;
        return known(d.with("waterlogged", Boolean.toString(on)));
    }

    /** A fluid block's level-0 state; still water for every other state that holds water. */
    @Override
    public int fluidSource(int h) {
        int f = flags(h);
        if (StateFlags.has(f, StateFlags.FLUID_BLOCK)) return state(blockId(h).value() + "[level=0]");
        if (StateFlags.has(f, StateFlags.WATER)) return state("minecraft:water[level=0]");
        return -1;
    }

    /**
     * {@link VerticalFlip}'s rules, with the plants and the torch upright and the chest, lily pad, sea pickle and coral
     * fan lopsided (as their vanilla shapes are).
     */
    @Override
    public int flip(int h) {
        return verticalFlip().flip(h);
    }

    @Override
    public int flipKind(int h) {
        return verticalFlip().kind(h);
    }

    private VerticalFlip verticalFlip() {
        VerticalFlip built = flip;
        if (built == null) {
            Set<String> upright = Set.of("minecraft:poppy", "minecraft:tall_grass", "minecraft:pink_petals",
                    "minecraft:seagrass", "minecraft:kelp", "minecraft:kelp_plant", "minecraft:tall_seagrass",
                    "minecraft:short_grass", "minecraft:torch");
            Set<String> lopsided = Set.of("minecraft:chest", "minecraft:lily_pad", "minecraft:sea_pickle",
                    "minecraft:tube_coral_fan");
            built = VerticalFlip.build(this, new VerticalFlip.Hints() {
                @Override
                public boolean upright(int h) {
                    return upright.contains(blockId(h).value());
                }

                @Override
                public boolean lopsided(int h) {
                    return lopsided.contains(blockId(h).value());
                }
            });
            flip = built;
        }
        return built;
    }

    /** Kelp grows as kelp on top of kelp_plant, sugar cane as itself. */
    @Override
    public int columnPart(int h, boolean top) {
        String id = blockId(h).value();
        return switch (id) {
            case "minecraft:kelp" -> top ? h : state("minecraft:kelp_plant");
            case "minecraft:kelp_plant" -> top ? state("minecraft:kelp") : h;
            case "minecraft:sugar_cane" -> h;
            default -> -1;
        };
    }

    private static List<String> numbers(int max) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i <= max; i++) values.add(Integer.toString(i));
        return values;
    }

    private int known(BlockDescriptor d) {
        int h = resolve(d);
        if (h < 0) throw new AssertionError("Fake state space is missing " + d);
        return h;
    }

    private static String swap(String value, String a, String b) {
        if (value.equals(a)) return b;
        if (value.equals(b)) return a;
        return value;
    }

    private void define(String id, ToIntFunction<Map<String, String>> baseFlags, Set<String> tags,
                        Property... properties) {
        List<Property> sorted = new ArrayList<>(List.of(properties));
        sorted.sort(Comparator.comparing(Property::name));
        NamespacedId blockId = new NamespacedId(id);
        Set<NamespacedId> tagIds = Set.copyOf(tags.stream().map(NamespacedId::new).toList());
        BlockDef def = new BlockDef(blockId, List.copyOf(sorted), baseFlags, tagIds);
        blocks.put(blockId, def);
        expand(def, 0, new TreeMap<>());
    }

    private void expand(BlockDef def, int propertyIndex, TreeMap<String, String> values) {
        if (propertyIndex == def.properties().size()) {
            BlockDescriptor descriptor = BlockDescriptor.of(def.id(), values);
            int f = def.flags().applyAsInt(values);
            String waterlogged = values.get("waterlogged");
            if (waterlogged != null) {
                f |= StateFlags.WATERLOGGABLE;
                if (waterlogged.equals("true")) f |= StateFlags.WATERLOGGED | StateFlags.WATER;
            }
            handles.put(descriptor, descriptors.size());
            descriptors.add(descriptor);
            flagList.add(f);
            return;
        }
        Property property = def.properties().get(propertyIndex);
        for (String value : property.values()) {
            values.put(property.name(), value);
            expand(def, propertyIndex + 1, values);
        }
        values.remove(property.name());
    }
}
