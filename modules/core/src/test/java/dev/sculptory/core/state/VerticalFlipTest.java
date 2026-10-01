package dev.sculptory.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.core.transform.Mirror;
import java.util.Collection;
import org.junit.jupiter.api.Test;

class VerticalFlipTest {
    private final FakeStateSpace states = new FakeStateSpace();

    private int h(String spec) {
        return states.state(spec);
    }

    private void flips(String from, String to) {
        assertEquals(states.format(h(to)), states.format(states.flip(h(from))), from);
        assertEquals(states.format(h(from)), states.format(states.flip(h(to))), to);
        assertEquals(VerticalFlip.FLIPS, states.flipKind(h(from)), from);
    }

    private void kept(String spec, String result) {
        assertEquals(states.format(h(result)), states.format(states.flip(h(spec))), spec);
        assertEquals(VerticalFlip.KEPT, states.flipKind(h(spec)), spec);
    }

    @Test
    void propertiesWithAnUpOrDownTurnOver() {
        flips("minecraft:oak_stairs[facing=east,half=bottom,shape=inner_left]",
                "minecraft:oak_stairs[facing=east,half=top,shape=inner_left]");
        flips("minecraft:oak_slab[type=bottom]", "minecraft:oak_slab[type=top]");
        flips("minecraft:oak_slab[type=double]", "minecraft:oak_slab[type=double]");
        flips("minecraft:lantern[hanging=false]", "minecraft:lantern[hanging=true]");
        flips("minecraft:oak_door[facing=north,half=lower]", "minecraft:oak_door[facing=north,half=upper]");
        flips("testmod:widget[facing=up]", "testmod:widget[facing=down]");
        flips("testmod:widget[facing=north]", "testmod:widget[facing=north]");
        flips("minecraft:hopper[facing=north]", "minecraft:hopper[facing=north]");
        flips("minecraft:stone", "minecraft:stone");
        flips("minecraft:oak_log[axis=y]", "minecraft:oak_log[axis=y]");
        flips("minecraft:water[level=3]", "minecraft:water[level=3]");
    }

    @Test
    void blocksWithNoUpsideDownFormAreKept() {
        // A hopper cannot point up.
        kept("minecraft:hopper[facing=down]", "minecraft:hopper[facing=down]");
        kept("minecraft:torch", "minecraft:torch");
        kept("minecraft:poppy", "minecraft:poppy");
        kept("minecraft:chest[facing=west]", "minecraft:chest[facing=west]");
        kept("minecraft:lily_pad", "minecraft:lily_pad");
        // A two-block plant stays upright, whole: its halves swap places.
        kept("minecraft:tall_grass[half=lower]", "minecraft:tall_grass[half=upper]");
        kept("minecraft:tall_seagrass[half=upper]", "minecraft:tall_seagrass[half=lower]");
    }

    @Test
    void moddedPropertiesNoVanillaBlockHasAreCounted() {
        int up = h("testmod:gizmo[facing=up,spin=true]");
        assertEquals(h("testmod:gizmo[facing=down,spin=true]"), states.flip(up));
        assertEquals(VerticalFlip.UNKNOWN_PROPERTIES, states.flipKind(up));
        // A modded block with vanilla-named properties only is flipped like a vanilla one.
        assertEquals(VerticalFlip.FLIPS, states.flipKind(h("testmod:widget[facing=up]")));
        assertEquals(VerticalFlip.FLIPS, states.flipKind(h("testmod:vertical_slab[half=lower]")));
        assertEquals(h("testmod:vertical_slab[half=upper]"), states.flip(h("testmod:vertical_slab[half=lower]")));
    }

    @Test
    void everyFlipIsItsOwnInverseAndCommutesWithTurnsAndMirrors() {
        for (int h = 0; h < states.size(); h++) {
            int flipped = states.flip(h);
            assertEquals(h, states.flip(flipped), states.format(h));
            assertEquals(states.flipKind(h) == VerticalFlip.KEPT, states.flipKind(flipped) == VerticalFlip.KEPT,
                    states.format(h));
            for (int turns = 1; turns < 4; turns++) {
                assertEquals(states.rotate(flipped, turns), states.flip(states.rotate(h, turns)), states.format(h));
            }
            for (Mirror mirror : Mirror.values()) {
                assertEquals(states.mirror(flipped, mirror), states.flip(states.mirror(h, mirror)), states.format(h));
            }
        }
    }

    @Test
    void aStateWhosePartnerDoesNotFlipBackIsKept() {
        int top = h("minecraft:oak_slab[type=top,waterlogged=false]");
        int bottom = h("minecraft:oak_slab[type=bottom,waterlogged=false]");
        VerticalFlip.StateView text = VerticalFlip.StateView.of(states);
        // A block that answers inconsistently: its top slab turns into a bottom slab that is waterlogged.
        VerticalFlip.StateView lying = new VerticalFlip.StateView() {
            @Override
            public Collection<String> names(int handle) {
                return text.names(handle);
            }

            @Override
            public String value(int handle, String name) {
                return text.value(handle, name);
            }

            @Override
            public int with(int handle, String name, String value) {
                int result = text.with(handle, name, value);
                return handle == top && name.equals("type") ? text.with(result, "waterlogged", "true") : result;
            }
        };
        VerticalFlip flip = VerticalFlip.build(states, lying, VerticalFlip.Hints.NONE);
        assertEquals(top, flip.flip(top));
        assertEquals(VerticalFlip.KEPT, flip.kind(top));
        assertEquals(bottom, flip.flip(bottom), "its partner is kept too");
        for (int handle = 0; handle < states.size(); handle++) assertEquals(handle, flip.flip(flip.flip(handle)));
    }

    @Test
    void readFailuresKeepTheState() {
        int widget = h("testmod:widget[facing=up]");
        VerticalFlip.StateView text = VerticalFlip.StateView.of(states);
        VerticalFlip.StateView failing = new VerticalFlip.StateView() {
            @Override
            public Collection<String> names(int handle) {
                if (handle == widget) throw new IllegalStateException("broken");
                return text.names(handle);
            }

            @Override
            public String value(int handle, String name) {
                return text.value(handle, name);
            }

            @Override
            public int with(int handle, String name, String value) {
                return text.with(handle, name, value);
            }
        };
        VerticalFlip flip = VerticalFlip.build(states, failing, VerticalFlip.Hints.NONE);
        assertEquals(widget, flip.flip(widget));
        assertEquals(VerticalFlip.KEPT, flip.kind(widget));
        // Its partner no longer flips back to it, so it is kept as well.
        assertEquals(h("testmod:widget[facing=down]"), flip.flip(h("testmod:widget[facing=down]")));
    }

    @Test
    void noneFlipsNothing() {
        assertEquals(17, VerticalFlip.NONE.flip(17));
        assertEquals(VerticalFlip.FLIPS, VerticalFlip.NONE.kind(17));
        StateSpace plain = new StateSpace() {
            @Override
            public int size() {
                return states.size();
            }

            @Override
            public int air() {
                return 0;
            }

            @Override
            public int flags(int h) {
                return states.flags(h);
            }

            @Override
            public String format(int h) {
                return states.format(h);
            }

            @Override
            public int parse(String spec) {
                return states.parse(spec);
            }

            @Override
            public dev.sculptory.core.BlockDescriptor describe(int h) {
                return states.describe(h);
            }

            @Override
            public int resolve(dev.sculptory.core.BlockDescriptor d) {
                return states.resolve(d);
            }

            @Override
            public dev.sculptory.core.NamespacedId blockId(int h) {
                return states.blockId(h);
            }

            @Override
            public boolean inTag(int h, dev.sculptory.core.NamespacedId tag) {
                return states.inTag(h, tag);
            }

            @Override
            public int rotate(int h, int turns) {
                return states.rotate(h, turns);
            }

            @Override
            public int mirror(int h, Mirror m) {
                return states.mirror(h, m);
            }

            @Override
            public int withWaterlogged(int h, boolean on) {
                return states.withWaterlogged(h, on);
            }

            @Override
            public int fluidSource(int h) {
                return states.fluidSource(h);
            }
        };
        int stairs = h("minecraft:oak_stairs[half=bottom]");
        assertEquals(stairs, plain.flip(stairs), "a space that cannot flip leaves states alone");
        assertEquals(VerticalFlip.FLIPS, plain.flipKind(stairs));
    }
}
