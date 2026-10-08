package dev.sculptory.core.state;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.transform.CubeTurn;
import dev.sculptory.core.transform.Mirror;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A small state space of vanilla-like directional blocks for the tip tables ({@link StateTips}) and
 * {@link OrientedStates}: stairs, logs, chains, observers, hoppers, trapdoors, buttons, signs, jigsaws, mushroom blocks,
 * vines, doors, slabs, lanterns, a chest (lopsided), a poppy (upright) and the modded {@code testmod:widget[facing]} and
 * {@code testmod:gizmo[facing,spin]}. Rotation and mirroring turn horizontal facings, swap x and z axes on odd turns and
 * turn a sign's 0–15 rotation as vanilla does. Handle 0 is air.
 */
final class TipTestStates implements StateSpace {
    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    private static final List<String> SIX = List.of("north", "east", "south", "west", "up", "down");
    private static final List<String> BOOLEAN = List.of("false", "true");

    private final List<BlockDescriptor> descriptors = new ArrayList<>();
    private final Map<BlockDescriptor, Integer> handles = new HashMap<>();
    private final Map<NamespacedId, Map<String, List<String>>> blocks = new LinkedHashMap<>();
    private final StateTips tips;
    private final VerticalFlip flip;

    TipTestStates() {
        define("minecraft:air");
        define("minecraft:stone");
        define("minecraft:oak_log", "axis", List.of("y", "x", "z"));
        define("minecraft:chain", "axis", List.of("y", "x", "z"));
        define("minecraft:oak_stairs", "facing", HORIZONTAL, "half", List.of("bottom", "top"), "shape",
                List.of("straight", "inner_left", "inner_right", "outer_left", "outer_right"), "waterlogged", BOOLEAN);
        define("minecraft:oak_slab", "type", List.of("bottom", "top", "double"));
        define("minecraft:oak_door", "facing", HORIZONTAL, "half", List.of("lower", "upper"));
        define("minecraft:hopper", "facing", List.of("down", "north", "south", "west", "east"));
        define("minecraft:observer", "facing", SIX);
        define("minecraft:oak_trapdoor", "facing", HORIZONTAL, "half", List.of("bottom", "top"), "open", BOOLEAN);
        define("minecraft:stone_button", "face", List.of("wall", "floor", "ceiling"), "facing", HORIZONTAL);
        List<String> sixteen = new ArrayList<>();
        for (int i = 0; i < 16; i++) sixteen.add(Integer.toString(i));
        define("minecraft:oak_sign", "rotation", sixteen);
        List<String> orientations = new ArrayList<>();
        for (String top : HORIZONTAL) orientations.add("down_" + top);
        for (String top : HORIZONTAL) orientations.add("up_" + top);
        for (String front : HORIZONTAL) orientations.add(front + "_up");
        define("minecraft:jigsaw", "orientation", orientations);
        define("minecraft:brown_mushroom_block", "down", BOOLEAN, "east", BOOLEAN, "north", BOOLEAN, "south", BOOLEAN,
                "up", BOOLEAN, "west", BOOLEAN);
        define("minecraft:vine", "east", BOOLEAN, "north", BOOLEAN, "south", BOOLEAN, "up", BOOLEAN, "west", BOOLEAN);
        define("minecraft:lantern", "hanging", BOOLEAN);
        define("minecraft:chest", "facing", HORIZONTAL);
        define("minecraft:poppy");
        define("testmod:widget", "facing", SIX);
        define("testmod:gizmo", "facing", SIX, "spin", BOOLEAN);
        VerticalFlip.Hints hints = new VerticalFlip.Hints() {
            @Override
            public boolean upright(int h) {
                return blockId(h).value().equals("minecraft:poppy");
            }

            @Override
            public boolean lopsided(int h) {
                return blockId(h).value().equals("minecraft:chest");
            }
        };
        this.flip = VerticalFlip.build(this, hints);
        this.tips = StateTips.build(this, hints);
    }

    /** The handle of {@code spec} (missing properties take their first value). */
    int state(String spec) {
        int h = parse(spec);
        if (h < 0) throw new IllegalArgumentException("Unknown state " + spec);
        return h;
    }

    StateTips tips() {
        return tips;
    }

    private void define(String id, Object... properties) {
        Map<String, List<String>> props = new TreeMap<>();
        for (int i = 0; i < properties.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<String> values = (List<String>) properties[i + 1];
            props.put((String) properties[i], values);
        }
        NamespacedId block = new NamespacedId(id);
        blocks.put(block, props);
        expand(block, new ArrayList<>(props.keySet()), 0, new TreeMap<>(), props);
    }

    private void expand(NamespacedId block, List<String> names, int index, TreeMap<String, String> values,
                        Map<String, List<String>> props) {
        if (index == names.size()) {
            BlockDescriptor d = BlockDescriptor.of(block, values);
            handles.put(d, descriptors.size());
            descriptors.add(d);
            return;
        }
        for (String value : props.get(names.get(index))) {
            values.put(names.get(index), value);
            expand(block, names, index + 1, values, props);
        }
        values.remove(names.get(index));
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
        return h == 0 ? StateFlags.AIR : 0;
    }

    @Override
    public String format(int h) {
        return describe(h).format();
    }

    @Override
    public int parse(String spec) {
        try {
            return resolve(BlockDescriptor.parse(spec));
        } catch (IllegalArgumentException malformed) {
            return -1;
        }
    }

    @Override
    public BlockDescriptor describe(int h) {
        Objects.checkIndex(h, size());
        return descriptors.get(h);
    }

    @Override
    public int resolve(BlockDescriptor d) {
        Map<String, List<String>> props = blocks.get(d.block());
        if (props == null || !props.keySet().containsAll(d.properties().keySet())) return -1;
        TreeMap<String, String> full = new TreeMap<>();
        for (Map.Entry<String, List<String>> p : props.entrySet()) {
            String value = d.properties().getOrDefault(p.getKey(), p.getValue().get(0));
            if (!p.getValue().contains(value)) return -1;
            full.put(p.getKey(), value);
        }
        Integer h = handles.get(BlockDescriptor.of(d.block(), full));
        return h == null ? -1 : h;
    }

    @Override
    public NamespacedId blockId(int h) {
        return describe(h).block();
    }

    @Override
    public boolean inTag(int h, NamespacedId tag) {
        return false;
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
        if (axis != null && (turns & 1) == 1 && !axis.equals("y")) d = d.with("axis", axis.equals("x") ? "z" : "x");
        String rotation = d.get("rotation");
        if (rotation != null) d = d.with("rotation", Integer.toString((Integer.parseInt(rotation) + 4 * turns) % 16));
        return resolve(d);
    }

    @Override
    public int mirror(int h, Mirror m) {
        BlockDescriptor d = describe(h);
        if (m == Mirror.NONE) return h;
        String facing = d.get("facing");
        if (facing != null) {
            String a = m == Mirror.X ? "east" : "north", b = m == Mirror.X ? "west" : "south";
            d = d.with("facing", facing.equals(a) ? b : facing.equals(b) ? a : facing);
        }
        String rotation = d.get("rotation");
        if (rotation != null) {
            int r = Integer.parseInt(rotation);
            d = d.with("rotation", Integer.toString(Math.floorMod(m == Mirror.X ? 16 - r : 8 - r, 16)));
        }
        return resolve(d);
    }

    @Override
    public int flip(int h) {
        return flip.flip(h);
    }

    @Override
    public int flipKind(int h) {
        return flip.kind(h);
    }

    @Override
    public int tip(int h, CubeTurn.Tip tip) {
        return tips.tip(h, tip);
    }

    @Override
    public int tipKind(int h, CubeTurn.Tip tip) {
        return tips.kind(h, tip);
    }

    @Override
    public int withWaterlogged(int h, boolean on) {
        BlockDescriptor d = describe(h);
        if (d.get("waterlogged") == null) return h;
        return resolve(d.with("waterlogged", Boolean.toString(on)));
    }

    @Override
    public int fluidSource(int h) {
        return -1;
    }
}
