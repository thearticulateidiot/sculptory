package dev.sculptory.core.state;

import dev.sculptory.core.NamespacedId;
import java.util.List;
import java.util.Objects;

/**
 * The stair and slab of a full block's family, by vanilla's naming: {@code oak_planks} has {@code oak_stairs} and
 * {@code oak_slab}, {@code stone_bricks} {@code stone_brick_stairs}, {@code quartz_block} {@code quartz_stairs},
 * {@code cobblestone} {@code cobblestone_stairs}, {@code smooth_stone} only {@code smooth_stone_slab}. The family name is
 * tried as the block's path, then without a final {@code _planks} or {@code _block}, then with a final {@code bricks} or
 * {@code tiles} made singular; the first name that has a stair or a slab in the block's own namespace wins (modded
 * blocks named the same way are found too). Blocks that are not full ({@link StateFlags#TERRAIN_SOLID}), and stairs and
 * slabs themselves, have none. Cached per handle; concurrent use is safe because every writer stores the same
 * deterministic value.
 */
public final class StairSlabVariants {
    private final StateSpace states;
    /** Per handle: 0 = not looked up yet, else the stair's default handle + 2 (1 = none). */
    private final int[] stairs;
    /** As {@link #stairs}, for the slab. */
    private final int[] slabs;

    public StairSlabVariants(StateSpace states) {
        this.states = Objects.requireNonNull(states);
        this.stairs = new int[states.size()];
        this.slabs = new int[states.size()];
    }

    public StateSpace states() {
        return states;
    }

    /** The default state of the stair of {@code h}'s family, or -1 when it has none. */
    public int stairs(int h) {
        if (stairs[h] == 0) lookUp(h);
        return stairs[h] - 2;
    }

    /** The default state of the slab of {@code h}'s family, or -1 when it has none. */
    public int slab(int h) {
        if (slabs[h] == 0) lookUp(h);
        return slabs[h] - 2;
    }

    /**
     * {@code h}'s stair turned to {@code facing} ({@code north}, {@code east}, {@code south} or {@code west}), standing
     * on {@code half} ({@code bottom} or {@code top}), of {@code shape} ({@code straight}, {@code inner_left},
     * {@code inner_right}, {@code outer_left}, {@code outer_right}), not waterlogged; -1 when there is no such state.
     */
    public int stairs(int h, String facing, String half, String shape) {
        int s = stairs(h);
        if (s < 0) return -1;
        s = with(s, "facing", facing);
        s = with(s, "half", half);
        s = with(s, "shape", shape);
        return dry(s);
    }

    /** {@code h}'s slab of {@code type} ({@code bottom} or {@code top}), not waterlogged; -1 when there is none. */
    public int slab(int h, String type) {
        int s = slab(h);
        return dry(with(s, "type", type));
    }

    private int with(int h, String property, String value) {
        return h < 0 ? -1 : states.withProperty(h, property, value);
    }

    private int dry(int h) {
        if (h < 0) return -1;
        int dry = states.withProperty(h, "waterlogged", "false");
        return dry >= 0 ? dry : h;
    }

    private void lookUp(int h) {
        int stair = -1, slab = -1;
        if (StateFlags.has(states.flags(h), StateFlags.TERRAIN_SOLID)) {
            String id = states.blockId(h).value();
            String namespace = id.substring(0, id.indexOf(':'));
            String path = id.substring(id.indexOf(':') + 1);
            if (!path.endsWith("_stairs") && !path.endsWith("_slab")) {
                for (String family : families(path)) {
                    stair = known(namespace, family + "_stairs");
                    slab = known(namespace, family + "_slab");
                    if (stair >= 0 || slab >= 0) break;
                }
            }
        }
        stairs[h] = stair + 2;
        slabs[h] = slab + 2;
    }

    /** The family names to try for a block path, in order. */
    static List<String> families(String path) {
        String bare = path;
        for (String suffix : List.of("_planks", "_block")) {
            if (bare.endsWith(suffix) && bare.length() > suffix.length()) {
                bare = bare.substring(0, bare.length() - suffix.length());
                break;
            }
        }
        String singular = bare;
        if (singular.endsWith("bricks") || singular.endsWith("tiles")) singular = singular.substring(0, singular.length() - 1);
        return List.of(path, bare, singular);
    }

    private int known(String namespace, String path) {
        String text = namespace + ":" + path;
        try {
            new NamespacedId(text);
        } catch (IllegalArgumentException invalid) {
            return -1;
        }
        return states.parse(text);
    }
}
