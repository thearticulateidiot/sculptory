package dev.sculptory.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;


import dev.sculptory.core.transform.CubeTurn;
import dev.sculptory.core.transform.CubeTurn.Tip;
import dev.sculptory.core.transform.Orientation;
import dev.sculptory.core.transform.Transform;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The tip tables ({@link StateTips}) and the per-copy state map of a free orientation ({@link OrientedStates}), on the
 * fake space's vanilla-like blocks.
 */
class StateTipsTest {
    private final TipTestStates states = new TipTestStates();

    private int s(String spec) {
        return states.state(spec);
    }

    private void tips(String from, Tip tip, String to, int kind) {
        int h = s(from);
        assertEquals(states.format(s(to)), states.format(states.tip(h, tip)), from + " " + tip);
        assertEquals(kind, states.tipKind(h, tip), from + " " + tip + " kind");
    }

    private void kept(String from, Tip tip) {
        tips(from, tip, from, StateTips.KEPT);
    }

    @Test
    void axesAndSixWayFacingsTurnOnBothTips() {
        tips("minecraft:oak_log[axis=y]", Tip.PITCH, "minecraft:oak_log[axis=z]", StateTips.TURNS);
        tips("minecraft:oak_log[axis=z]", Tip.PITCH, "minecraft:oak_log[axis=y]", StateTips.TURNS);
        tips("minecraft:oak_log[axis=x]", Tip.PITCH, "minecraft:oak_log[axis=x]", StateTips.TURNS);
        tips("minecraft:oak_log[axis=y]", Tip.ROLL, "minecraft:oak_log[axis=x]", StateTips.TURNS);
        tips("minecraft:chain[axis=x]", Tip.ROLL, "minecraft:chain[axis=y]", StateTips.TURNS);
        // Pitch: up goes south, south down, down north, north up. Roll: east goes up, up west, west down, down east.
        tips("minecraft:observer[facing=up]", Tip.PITCH, "minecraft:observer[facing=south]", StateTips.TURNS);
        tips("minecraft:observer[facing=north]", Tip.PITCH, "minecraft:observer[facing=up]", StateTips.TURNS);
        tips("minecraft:observer[facing=east]", Tip.PITCH, "minecraft:observer[facing=east]", StateTips.TURNS);
        tips("minecraft:observer[facing=east]", Tip.ROLL, "minecraft:observer[facing=up]", StateTips.TURNS);
        tips("minecraft:observer[facing=up]", Tip.ROLL, "minecraft:observer[facing=west]", StateTips.TURNS);
        tips("minecraft:observer[facing=down]", Tip.ROLL, "minecraft:observer[facing=east]", StateTips.TURNS);
        // A hopper cannot point up: kept.
        kept("minecraft:hopper[facing=north]", Tip.PITCH);
        tips("minecraft:hopper[facing=down]", Tip.PITCH, "minecraft:hopper[facing=north]", StateTips.TURNS);
    }

    @Test
    void stairsKeepTheirShapeWheneverTheTurnedStairIsAStair() {
        // Tipped about its long (east-west) axis, a stair facing north is still a stair: half and facing trade places.
        tips("minecraft:oak_stairs[facing=north,half=bottom]", Tip.PITCH, "minecraft:oak_stairs[facing=north,half=top]",
                StateTips.TURNS);
        tips("minecraft:oak_stairs[facing=north,half=top]", Tip.PITCH, "minecraft:oak_stairs[facing=south,half=top]",
                StateTips.TURNS);
        tips("minecraft:oak_stairs[facing=east,half=bottom]", Tip.ROLL, "minecraft:oak_stairs[facing=east,half=top]",
                StateTips.TURNS);
        // Four tips bring every stair that stays a stair back where it was.
        for (String facing : List.of("north", "south")) {
            for (String half : List.of("bottom", "top")) {
                int h = s("minecraft:oak_stairs[facing=" + facing + ",half=" + half + "]");
                int turned = h;
                for (int i = 0; i < 4; i++) {
                    assertEquals(StateTips.TURNS, states.tipKind(turned, Tip.PITCH));
                    turned = states.tip(turned, Tip.PITCH);
                }
                assertEquals(h, turned, facing + " " + half);
            }
        }
        // Tipped about its facing axis, its long axis stands up: no stair has that shape, the nearest is taken.
        int sideways = s("minecraft:oak_stairs[facing=east,half=bottom]");
        assertEquals(StateTips.KEPT, states.tipKind(sideways, Tip.PITCH));
        assertEquals("minecraft:oak_stairs", states.blockId(states.tip(sideways, Tip.PITCH)).value());
        // Waterlogging is kept.
        tips("minecraft:oak_stairs[facing=north,half=bottom,waterlogged=true]", Tip.PITCH,
                "minecraft:oak_stairs[facing=north,half=top,waterlogged=true]", StateTips.TURNS);
    }

    @Test
    void cornerStairsMatchByTheEighthsTheyFill() {
        // An inner corner fills three halves; tipped so one of them is a floor or ceiling it is a stair again.
        int inner = s("minecraft:oak_stairs[facing=north,half=bottom,shape=inner_left]");
        int tipped = states.tip(inner, Tip.PITCH);
        assertEquals(StateTips.TURNS, states.tipKind(inner, Tip.PITCH));
        assertEquals(StateTips.turnEighths(StateTips.stairEighths("north", "bottom", "inner_left"), Tip.PITCH),
                StateTips.stairEighths(states.describe(tipped).get("facing"), states.describe(tipped).get("half"),
                        states.describe(tipped).get("shape")));
    }

    @Test
    void trapdoorsOpenOrCloseAsTheirPanelTurns() {
        // A closed trapdoor on the floor, hinged on the south edge, tipped so the panel stands north: open, facing south.
        tips("minecraft:oak_trapdoor[facing=north,half=bottom,open=false]", Tip.PITCH,
                "minecraft:oak_trapdoor[facing=south,half=bottom,open=true]", StateTips.TURNS);
        // And back: four tips about the hinge axis close it again where it was.
        int h = s("minecraft:oak_trapdoor[facing=north,half=bottom,open=false]");
        int turned = h;
        for (int i = 0; i < 4; i++) turned = states.tip(turned, Tip.PITCH);
        assertEquals(h, turned);
        // Tipped about an axis that stands the hinge up: kept.
        kept("minecraft:oak_trapdoor[facing=north,half=bottom,open=false]", Tip.ROLL);
    }

    @Test
    void buttonsMoveBetweenWallFloorAndCeiling() {
        tips("minecraft:stone_button[face=wall,facing=north]", Tip.PITCH, "minecraft:stone_button[face=floor,facing=south]",
                StateTips.TURNS);
        // Tipped on: a wall button upside down, which no state is; the wall is taken and the button counted.
        tips("minecraft:stone_button[face=floor,facing=south]", Tip.PITCH,
                "minecraft:stone_button[face=wall,facing=south]", StateTips.KEPT);
        // On a wall, turned sideways: the wall is taken and the button counted.
        int wall = s("minecraft:stone_button[face=wall,facing=north]");
        assertEquals(states.format(wall), states.format(states.tip(wall, Tip.ROLL)));
        assertEquals(StateTips.KEPT, states.tipKind(wall, Tip.ROLL));
    }

    @Test
    void facesJigsawsAndUnturnableBlocks() {
        tips("minecraft:jigsaw[orientation=north_up]", Tip.PITCH, "minecraft:jigsaw[orientation=up_south]", StateTips.TURNS);
        tips("minecraft:jigsaw[orientation=east_up]", Tip.ROLL, "minecraft:jigsaw[orientation=up_west]", StateTips.TURNS);
        kept("minecraft:jigsaw[orientation=north_up]", Tip.ROLL); // north_west is no orientation
        tips("minecraft:brown_mushroom_block[up=true,down=false,north=false,south=false,east=false,west=false]",
                Tip.PITCH,
                "minecraft:brown_mushroom_block[up=false,down=false,north=false,south=true,east=false,west=false]",
                StateTips.TURNS);
        tips("minecraft:vine[up=true]", Tip.PITCH, "minecraft:vine[south=true]", StateTips.TURNS);
        kept("minecraft:vine[south=true]", Tip.PITCH); // no down face
        kept("minecraft:oak_door[facing=north,half=lower]", Tip.PITCH);
        kept("minecraft:oak_slab[type=bottom]", Tip.PITCH);
        tips("minecraft:oak_slab[type=double]", Tip.PITCH, "minecraft:oak_slab[type=double]", StateTips.TURNS);
        kept("minecraft:oak_sign[rotation=3]", Tip.ROLL);
        kept("minecraft:lantern[hanging=true]", Tip.PITCH);
        kept("minecraft:poppy", Tip.PITCH);
        kept("minecraft:chest[facing=east]", Tip.PITCH); // lopsided without a property saying so
        tips("minecraft:stone", Tip.PITCH, "minecraft:stone", StateTips.TURNS);
        assertEquals(s("minecraft:stone"), states.tip(s("minecraft:stone"), Tip.NONE));
    }

    @Test
    void moddedBlocksTipByKnownPropertiesWhileTheFallbackIsOn() {
        tips("testmod:widget[facing=up]", Tip.PITCH, "testmod:widget[facing=south]", StateTips.TURNS);
        tips("testmod:gizmo[facing=up,spin=true]", Tip.PITCH, "testmod:gizmo[facing=south,spin=true]",
                StateTips.UNKNOWN_PROPERTIES);
        StateTips tables = StateTips.build(states, VerticalFlip.Hints.NONE).moddedEnabled(false);
        int widget = s("testmod:widget[facing=up]");
        assertEquals(widget, tables.tip(widget, Tip.PITCH));
        assertEquals(StateTips.KEPT, tables.kind(widget, Tip.PITCH));
        int log = s("minecraft:oak_log[axis=y]");
        assertEquals(s("minecraft:oak_log[axis=z]"), tables.tip(log, Tip.PITCH), "vanilla blocks tip either way");
    }

    @Test
    void aStateLeftAsItIsButNotTheSameTippedIsKept() {
        // A furnace-like block tipped about its own facing: the rules leave it as it is. Whether that is honest is its
        // shape's to tell: a cube looks the same tipped, a campfire (lower half only) does not.
        int east = s("minecraft:chest[facing=east]");
        int stone = s("minecraft:stone");
        StateTips shapes = StateTips.build(states, VerticalFlip.StateView.of(states), VerticalFlip.Hints.NONE,
                (h, tip) -> h != east);
        assertEquals(east, shapes.tip(east, Tip.PITCH));
        assertEquals(StateTips.KEPT, shapes.kind(east, Tip.PITCH), "not the same tipped: kept and counted");
        assertEquals(StateTips.TURNS, shapes.kind(stone, Tip.PITCH));
        // Without lopsided hints the plain rules call it turned.
        assertEquals(StateTips.TURNS, StateTips.build(states, VerticalFlip.Hints.NONE).kind(east, Tip.PITCH));
    }

    // ---------------------------------------------------------------- OrientedStates

    @Test
    void anOrientedStateTakesTheNearestCubeTurn() {
        int stairs = s("minecraft:oak_stairs[facing=north]");
        // 30 degrees snaps to none; 60 to a quarter turn clockwise.
        assertEquals(stairs, new OrientedStates(states, Orientation.free(Transform.IDENTITY, 3_000, 0, 0)).map(stairs));
        assertEquals(s("minecraft:oak_stairs[facing=east]"),
                new OrientedStates(states, Orientation.free(Transform.IDENTITY, 6_000, 0, 0)).map(stairs));
        // A log tipped 80 degrees about east-west lies north-south.
        OrientedStates tipped = new OrientedStates(states, Orientation.free(Transform.IDENTITY, 0, 8_000, 0));
        assertEquals(s("minecraft:oak_log[axis=z]"), tipped.map(s("minecraft:oak_log[axis=y]")));
        assertEquals(StateTips.TURNS, tipped.kind(s("minecraft:oak_log[axis=y]")));
        assertEquals(StateTips.KEPT, tipped.kind(s("minecraft:oak_door[facing=north,half=lower]")));
        // An exact orientation maps as its transform.
        Transform t = new Transform(1, dev.sculptory.core.transform.Mirror.X);
        OrientedStates exact = new OrientedStates(states, Orientation.of(t));
        for (int h = 0; h < states.size(); h++) assertEquals(t.applyToState(states, h), exact.map(h));
        assertEquals(new CubeTurn(t, Tip.NONE), exact.turn());
    }

    @Test
    void standingSignsTakeTheNearestOfTheirSixteenHeadings() {
        int sign = s("minecraft:oak_sign[rotation=0]");
        assertEquals(s("minecraft:oak_sign[rotation=1]"),
                new OrientedStates(states, Orientation.free(Transform.IDENTITY, 3_000, 0, 0)).map(sign));
        assertEquals(s("minecraft:oak_sign[rotation=2]"),
                new OrientedStates(states, Orientation.free(Transform.IDENTITY, 5_000, 0, 0)).map(sign));
        assertEquals(s("minecraft:oak_sign[rotation=15]"),
                new OrientedStates(states, Orientation.free(Transform.IDENTITY, -2_000, 0, 0)).map(sign));
        // A little pitch keeps it standing and turns nothing.
        assertEquals(sign, new OrientedStates(states, Orientation.free(Transform.IDENTITY, 0, 1_000, 0)).map(sign));
    }
}
