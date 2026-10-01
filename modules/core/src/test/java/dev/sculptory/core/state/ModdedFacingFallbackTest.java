package dev.sculptory.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * The modded facing fallback's tables over a purpose-built space: modded blocks that do not turn themselves, each next
 * to a "reference" twin with the same properties that turns itself the way vanilla does. Expected results come from a
 * geometric oracle (direction vectors), not from the rules under test.
 */
class ModdedFacingFallbackTest {
    private static final List<String> HORIZONTAL = List.of("north", "east", "south", "west");
    private static final List<String> SIX = List.of("down", "north", "east", "south", "west", "up");
    private static final List<String> AXES = List.of("y", "x", "z");
    private static final List<String> SIXTEEN = range(16);

    /** Every transform: four turns, unmirrored and with each mirror. */
    private static final List<Transform> TRANSFORMS = transforms();

    private static final TurnSpace OWN = new TurnSpace()
            .block("minecraft:furnace", Own.TURNS, "facing", HORIZONTAL)
            .block("minecraft:stuck", Own.STUCK, "facing", HORIZONTAL)
            .block("testmod:pot", Own.STUCK, "facing", HORIZONTAL, "support", List.of("none", "tray"))
            .block("testmod:pot_reference", Own.TURNS, "facing", HORIZONTAL, "support", List.of("none", "tray"))
            .block("testmod:pillar", Own.STUCK, "axis", AXES)
            .block("testmod:pillar_reference", Own.TURNS, "axis", AXES)
            .block("testmod:beam", Own.STUCK, "horizontal_axis", List.of("x", "z"))
            .block("testmod:beam_reference", Own.TURNS, "horizontal_axis", List.of("x", "z"))
            .block("testmod:banner", Own.STUCK, "rotation", SIXTEEN, "waterlogged", List.of("false", "true"))
            .block("testmod:banner_reference", Own.TURNS, "rotation", SIXTEEN, "waterlogged", List.of("false", "true"))
            .block("testmod:post", Own.STUCK, "axis", AXES, "facing", HORIZONTAL)
            .block("testmod:post_reference", Own.TURNS, "axis", AXES, "facing", HORIZONTAL)
            .block("testmod:hopper", Own.STUCK, "facing", SIX)
            .block("testmod:hopper_reference", Own.TURNS, "facing", SIX)
            .block("testmod:dial", Own.STUCK, "rotation", range(8))
            .block("testmod:restricted", Own.STUCK, "facing", List.of("north", "south"))
            .block("testmod:quirky", Own.QUIRKY, "facing", HORIZONTAL)
            .block("testmod:rotates_only", Own.ROTATES_ONLY, "facing", HORIZONTAL)
            .block("testmod:throws", Own.THROWS, "facing", HORIZONTAL)
            .block("testmod:hybrid", Own.AXIS_ONLY, "axis", AXES, "facing", HORIZONTAL)
            .block("testmod:plain", Own.STUCK);

    private static final ModdedFacingFallback FALLBACK = ModdedFacingFallback.build(OWN);
    private static final StateSpace TURNING = new FallbackSpace(OWN, FALLBACK);

    @Test
    void stuckBlocksTurnExactlyAsTheirReferenceTwinsWithEveryTransform() {
        Map<String, String> pairs = Map.of("testmod:pot", "testmod:pot_reference", "testmod:pillar",
                "testmod:pillar_reference", "testmod:beam", "testmod:beam_reference", "testmod:banner",
                "testmod:banner_reference", "testmod:post", "testmod:post_reference", "testmod:hopper",
                "testmod:hopper_reference");
        int checked = 0;
        for (Map.Entry<String, String> pair : pairs.entrySet()) {
            for (int h : OWN.statesOf(pair.getKey())) {
                BlockDescriptor stuck = OWN.describe(h);
                int twin = OWN.resolve(BlockDescriptor.of(new NamespacedId(pair.getValue()), stuck.properties()));
                for (Transform t : TRANSFORMS) {
                    BlockDescriptor got = TURNING.describe(t.applyToState(TURNING, h));
                    BlockDescriptor reference = OWN.describe(t.applyToState(OWN, twin));
                    assertEquals(reference.properties(), got.properties(), stuck + " " + t);
                    assertEquals(Oracle.transform(stuck, t).properties(), got.properties(), stuck + " " + t);
                    assertEquals(stuck.block(), got.block());
                    checked++;
                }
            }
        }
        assertEquals(12 * (8 + 3 + 2 + 32 + 12 + 6), checked);
    }

    @Test
    void theNamedExamplesTurnAsVanillaWould() {
        int pot = OWN.state("testmod:pot[facing=south,support=tray]");
        assertEquals("testmod:pot[facing=west,support=tray]", TURNING.format(TURNING.rotate(pot, 1)));
        assertEquals("testmod:pot[facing=north,support=tray]", TURNING.format(TURNING.rotate(pot, 2)));
        assertEquals("testmod:pot[facing=east,support=tray]", TURNING.format(TURNING.rotate(pot, 3)));
        int east = OWN.state("testmod:pot[facing=east]");
        assertEquals("testmod:pot[facing=west,support=none]", TURNING.format(TURNING.mirror(east, Mirror.X)));
        assertEquals(east, TURNING.mirror(east, Mirror.Z));
        assertEquals(pot, TURNING.mirror(pot, Mirror.X));
        assertEquals("testmod:pot[facing=north,support=tray]", TURNING.format(TURNING.mirror(pot, Mirror.Z)));
        // Mirror first, then turn: east mirrored across x is west, a quarter turn clockwise makes it north.
        assertEquals("testmod:pot[facing=north,support=none]",
                TURNING.format(new Transform(1, Mirror.X).applyToState(TURNING, east)));

        int pillar = OWN.state("testmod:pillar[axis=x]");
        assertEquals("testmod:pillar[axis=z]", TURNING.format(TURNING.rotate(pillar, 1)));
        assertEquals(pillar, TURNING.rotate(pillar, 2));
        assertEquals("testmod:pillar[axis=z]", TURNING.format(TURNING.rotate(pillar, 3)));
        assertEquals(pillar, TURNING.mirror(pillar, Mirror.X));
        int upright = OWN.state("testmod:pillar[axis=y]");
        for (int turns = 0; turns < 4; turns++) assertEquals(upright, TURNING.rotate(upright, turns));

        int banner = OWN.state("testmod:banner[rotation=3]");
        assertEquals("testmod:banner[rotation=7,waterlogged=false]", TURNING.format(TURNING.rotate(banner, 1)));
        assertEquals("testmod:banner[rotation=15,waterlogged=false]", TURNING.format(TURNING.rotate(banner, 3)));
        assertEquals("testmod:banner[rotation=13,waterlogged=false]", TURNING.format(TURNING.mirror(banner, Mirror.X)));
        assertEquals("testmod:banner[rotation=5,waterlogged=false]", TURNING.format(TURNING.mirror(banner, Mirror.Z)));
        int south = OWN.state("testmod:banner[rotation=0]");
        assertEquals(south, TURNING.mirror(south, Mirror.X));
        assertEquals("testmod:banner[rotation=8,waterlogged=false]", TURNING.format(TURNING.mirror(south, Mirror.Z)));
    }

    @Test
    void fourTurnsAndTwoMirrorsGiveTheStateBack() {
        for (int h = 0; h < OWN.size(); h++) {
            if (OWN.own(h) == Own.THROWS) continue;
            int turned = h;
            for (int i = 0; i < 4; i++) turned = TURNING.rotate(turned, 1);
            assertEquals(h, turned, OWN.format(h));
            for (int turns = 0; turns < 4; turns++) {
                assertEquals(h, TURNING.rotate(TURNING.rotate(h, turns), 4 - turns), OWN.format(h) + " " + turns);
                assertEquals(TURNING.rotate(TURNING.rotate(h, 1), turns), TURNING.rotate(h, turns + 1), OWN.format(h));
            }
            for (Mirror mirror : List.of(Mirror.X, Mirror.Z)) {
                assertEquals(h, TURNING.mirror(TURNING.mirror(h, mirror), mirror), OWN.format(h) + " " + mirror);
            }
            assertEquals(h, TURNING.mirror(h, Mirror.NONE));
        }
    }

    @Test
    void transformsComposeAndInvertOnTheStates() {
        for (int h = 0; h < OWN.size(); h++) {
            if (OWN.own(h) == Own.THROWS) continue;
            for (Transform first : TRANSFORMS) {
                int once = first.applyToState(TURNING, h);
                assertEquals(h, first.inverse().applyToState(TURNING, once), OWN.format(h) + " " + first);
                for (Transform second : TRANSFORMS) {
                    assertEquals(first.compose(second).applyToState(TURNING, h), second.applyToState(TURNING, once),
                            OWN.format(h) + " " + first + " then " + second);
                }
            }
        }
    }

    @Test
    void vanillaBlocksAreNeverTouched() {
        for (String block : List.of("minecraft:stuck", "minecraft:furnace")) {
            for (int h : OWN.statesOf(block)) {
                for (Transform t : TRANSFORMS) assertEquals(t.applyToState(OWN, h), t.applyToState(TURNING, h));
                for (int turns = 1; turns < 4; turns++) assertEquals(-1, FALLBACK.rotate(h, turns));
                assertEquals(-1, FALLBACK.mirror(h, Mirror.X));
                assertEquals(-1, FALLBACK.mirror(h, Mirror.Z));
            }
        }
        int stuck = OWN.state("minecraft:stuck[facing=east]");
        assertEquals(stuck, TURNING.rotate(stuck, 1));
    }

    @Test
    void moddedBlocksThatTurnThemselvesKeepTheirOwnTurn() {
        // Quirky turns counterclockwise and mirrors itself: never overridden, even where the fallback would differ.
        for (int h : OWN.statesOf("testmod:quirky")) {
            for (Transform t : TRANSFORMS) assertEquals(t.applyToState(OWN, h), t.applyToState(TURNING, h));
        }
        int quirky = OWN.state("testmod:quirky[facing=north]");
        assertEquals("testmod:quirky[facing=west]", TURNING.format(TURNING.rotate(quirky, 1)));
        // A block that rotates itself but does not mirror: its rotation is its own, the mirror is the fallback's.
        int east = OWN.state("testmod:rotates_only[facing=east]");
        for (int turns = 0; turns < 4; turns++) assertEquals(OWN.rotate(east, turns), TURNING.rotate(east, turns));
        assertEquals(-1, FALLBACK.rotate(east, 1));
        assertEquals(east, OWN.mirror(east, Mirror.X));
        assertEquals("testmod:rotates_only[facing=west]", TURNING.format(TURNING.mirror(east, Mirror.X)));
        // A block whose own rotate throws is left to its own path (the fallback does not hide the failure).
        int throwing = OWN.state("testmod:throws[facing=east]");
        assertEquals(-1, FALLBACK.rotate(throwing, 1));
        assertEquals(-1, FALLBACK.mirror(throwing, Mirror.X));
    }

    /**
     * A block that turns only part of itself (its axis, not its facing) is left to its own turn wherever it turns at
     * all, so a half turn is still two quarter turns; where it does not turn at all (upright), the fallback turns it.
     */
    @Test
    void aBlockThatTurnsPartOfItselfKeepsItsOwnTurnsSoTurnsCompose() {
        int lying = OWN.state("testmod:hybrid[axis=x,facing=north]");
        for (Transform t : TRANSFORMS) assertEquals(t.applyToState(OWN, lying), t.applyToState(TURNING, lying), "" + t);
        assertEquals(TURNING.rotate(TURNING.rotate(lying, 1), 1), TURNING.rotate(lying, 2));
        assertEquals(lying, TURNING.rotate(lying, 2), "its own half turn keeps both axis and facing");
        assertEquals(-1, FALLBACK.rotate(lying, 2));
        assertEquals(-1, FALLBACK.mirror(OWN.state("testmod:hybrid[axis=z,facing=east]"), Mirror.X));
        int upright = OWN.state("testmod:hybrid[axis=y,facing=north]");
        assertEquals("testmod:hybrid[axis=y,facing=east]", TURNING.format(TURNING.rotate(upright, 1)));
        assertEquals("testmod:hybrid[axis=y,facing=south]", TURNING.format(TURNING.mirror(upright, Mirror.Z)));
    }

    @Test
    void onlyHorizontalFacingsAxesAndSixteenStepRotationsTurn() {
        int down = OWN.state("testmod:hopper[facing=down]");
        for (Transform t : TRANSFORMS) assertEquals(down, t.applyToState(TURNING, down));
        assertEquals("testmod:hopper[facing=south]",
                TURNING.format(TURNING.rotate(OWN.state("testmod:hopper[facing=east]"), 1)));
        for (int h : OWN.statesOf("testmod:dial")) {
            for (Transform t : TRANSFORMS) assertEquals(h, t.applyToState(TURNING, h), OWN.format(h));
        }
        int plain = OWN.state("testmod:plain");
        for (Transform t : TRANSFORMS) assertEquals(plain, t.applyToState(TURNING, plain));
        // A facing without east and west could turn by 2 but not by 1: left alone for every transform, so turns compose.
        for (int h : OWN.statesOf("testmod:restricted")) {
            for (Transform t : TRANSFORMS) assertEquals(h, t.applyToState(TURNING, h), OWN.format(h) + " " + t);
        }
    }

    @Test
    void switchedOffItTurnsNothing() {
        ModdedFacingFallback off = FALLBACK.enabled(false);
        assertFalse(off.enabled());
        assertTrue(FALLBACK.enabled());
        StateSpace offSpace = new FallbackSpace(OWN, off);
        for (int h = 0; h < OWN.size(); h++) {
            for (int turns = 0; turns < 4; turns++) assertEquals(-1, off.rotate(h, turns));
            assertEquals(-1, off.mirror(h, Mirror.X));
            assertEquals(-1, off.mirror(h, Mirror.Z));
            if (OWN.own(h) == Own.THROWS) continue;
            for (Transform t : TRANSFORMS) assertEquals(t.applyToState(OWN, h), t.applyToState(offSpace, h));
        }
        int pot = OWN.state("testmod:pot[facing=south]");
        assertEquals(pot, offSpace.rotate(pot, 1));
        // The same tables, switched back on.
        assertSame(FALLBACK, FALLBACK.enabled(true));
        assertEquals(FALLBACK.rotate(pot, 1), off.enabled(true).rotate(pot, 1));
        assertEquals(FALLBACK.turnedStates(), off.turnedStates());
    }

    @Test
    void countsWhatItTurnsAndSkipsWhatTheFilterRefuses() {
        // pot 8, pillar 2 (x, z), beam 2, banner 32, post 12, hopper 4 (horizontal), rotates_only 4 (mirrored only),
        // hybrid 4 (its upright states, which it does not turn at all)
        assertEquals(8 + 2 + 2 + 32 + 12 + 4 + 4 + 4, FALLBACK.turnedStates());
        assertEquals(8, FALLBACK.turnedBlocks());
        int pot = OWN.state("testmod:pot[facing=south]");
        ModdedFacingFallback filtered = ModdedFacingFallback.build(OWN,
                h -> !OWN.blockId(h).value().equals("testmod:pot"), ModdedFacingFallback.PropertyAccess.of(OWN));
        assertEquals(-1, filtered.rotate(pot, 1));
        assertEquals(FALLBACK.turnedStates() - 8, filtered.turnedStates());
        assertEquals(0, ModdedFacingFallback.NONE.turnedStates());
        assertEquals(-1, ModdedFacingFallback.NONE.rotate(pot, 1));
    }

    // ------------------------------------------------------------------ fixtures

    private static List<String> range(int n) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < n; i++) values.add(Integer.toString(i));
        return values;
    }

    private static List<Transform> transforms() {
        List<Transform> all = new ArrayList<>();
        for (Mirror mirror : Mirror.values()) {
            for (int turns = 0; turns < 4; turns++) all.add(new Transform(turns, mirror));
        }
        return List.copyOf(all);
    }

    /** How a test block turns itself. */
    private enum Own {
        /** As vanilla turns the equivalent block (the oracle). */
        TURNS,
        /** Not at all (the Farmer's Delight pot). */
        STUCK,
        /** Counterclockwise, mirroring as vanilla would: a block with its own idea of turning. */
        QUIRKY,
        /** Rotates as vanilla would but does not mirror. */
        ROTATES_ONLY,
        /** Its rotate and mirror throw. */
        THROWS,
        /** Rotate turns its axis but not its facing; it does not mirror (a pillar with a facing it forgets). */
        AXIS_ONLY
    }

    /**
     * Mirror, then quarter turns clockwise, by geometry: horizontal facings as unit vectors, 16-step rotations as angles
     * (0 south, 4 west, 8 north, 12 east), axes swapped on odd turns.
     */
    private static final class Oracle {
        static BlockDescriptor transform(BlockDescriptor d, Transform t) {
            BlockDescriptor result = d;
            String facing = d.get("facing");
            if (facing != null && HORIZONTAL.contains(facing)) {
                double[] v = facingVector(facing);
                result = result.with("facing", nearestFacing(apply(v, t)));
            }
            for (String axisName : List.of("axis", "horizontal_axis")) {
                String axis = d.get(axisName);
                if (axis != null && (t.quarterTurnsCw() & 1) == 1 && !axis.equals("y")) {
                    result = result.with(axisName, axis.equals("x") ? "z" : "x");
                }
            }
            String rotation = d.get("rotation");
            if (rotation != null && SIXTEEN.contains(rotation) && d.block().value().contains("banner")) {
                double angle = Integer.parseInt(rotation) * Math.PI / 8;
                double[] v = apply(new double[] {-Math.sin(angle), Math.cos(angle)}, t);
                result = result.with("rotation", Integer.toString(nearestRotation(v)));
            }
            return result;
        }

        /** (x, z): north is -z, east +x. */
        private static double[] facingVector(String facing) {
            return switch (facing) {
                case "north" -> new double[] {0, -1};
                case "east" -> new double[] {1, 0};
                case "south" -> new double[] {0, 1};
                default -> new double[] {-1, 0};
            };
        }

        /** Mirror (X negates x, Z negates z), then turn clockwise seen from above: (x, z) -> (-z, x). */
        private static double[] apply(double[] v, Transform t) {
            double x = t.mirror() == Mirror.X ? -v[0] : v[0];
            double z = t.mirror() == Mirror.Z ? -v[1] : v[1];
            for (int i = 0; i < t.quarterTurnsCw(); i++) {
                double turnedX = -z;
                z = x;
                x = turnedX;
            }
            return new double[] {x, z};
        }

        private static String nearestFacing(double[] v) {
            String best = null;
            double bestDot = -2;
            for (String facing : HORIZONTAL) {
                double[] f = facingVector(facing);
                double dot = f[0] * v[0] + f[1] * v[1];
                if (dot > bestDot) {
                    bestDot = dot;
                    best = facing;
                }
            }
            return best;
        }

        private static int nearestRotation(double[] v) {
            int best = -1;
            double bestDot = -2;
            for (int r = 0; r < 16; r++) {
                double angle = r * Math.PI / 8;
                double dot = -Math.sin(angle) * v[0] + Math.cos(angle) * v[1];
                if (dot > bestDot) {
                    bestDot = dot;
                    best = r;
                }
            }
            return best;
        }
    }

    /** Test blocks: every combination of their property values, handles in declaration order. */
    private static final class TurnSpace implements StateSpace {
        private record Block(NamespacedId id, Own own, LinkedHashMap<String, List<String>> properties) {}

        private final Map<NamespacedId, Block> blocks = new LinkedHashMap<>();
        private final List<BlockDescriptor> descriptors = new ArrayList<>();
        private final List<Own> owns = new ArrayList<>();
        private final Map<BlockDescriptor, Integer> handles = new HashMap<>();

        TurnSpace block(String id, Own own, Object... namesAndValues) {
            LinkedHashMap<String, List<String>> properties = new LinkedHashMap<>();
            for (int i = 0; i < namesAndValues.length; i += 2) {
                @SuppressWarnings("unchecked")
                List<String> values = (List<String>) namesAndValues[i + 1];
                properties.put((String) namesAndValues[i], values);
            }
            Block block = new Block(new NamespacedId(id), own, properties);
            blocks.put(block.id(), block);
            expand(block, new ArrayList<>(properties.keySet()), 0, new TreeMap<>());
            return this;
        }

        private void expand(Block block, List<String> names, int index, TreeMap<String, String> values) {
            if (index == names.size()) {
                BlockDescriptor d = BlockDescriptor.of(block.id(), values);
                handles.put(d, descriptors.size());
                descriptors.add(d);
                owns.add(block.own());
                return;
            }
            for (String value : block.properties().get(names.get(index))) {
                values.put(names.get(index), value);
                expand(block, names, index + 1, values);
            }
            values.remove(names.get(index));
        }

        int state(String spec) {
            int h = parse(spec);
            if (h < 0) throw new IllegalArgumentException("unknown test state " + spec);
            return h;
        }

        List<Integer> statesOf(String block) {
            List<Integer> result = new ArrayList<>();
            for (int h = 0; h < size(); h++) if (blockId(h).value().equals(block)) result.add(h);
            return result;
        }

        Own own(int h) {
            return owns.get(h);
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
            return 0;
        }

        @Override
        public String format(int h) {
            return describe(h).format();
        }

        @Override
        public int parse(String spec) {
            try {
                return resolve(BlockDescriptor.parse(spec));
            } catch (IllegalArgumentException e) {
                return -1;
            }
        }

        @Override
        public BlockDescriptor describe(int h) {
            return descriptors.get(h);
        }

        @Override
        public int resolve(BlockDescriptor d) {
            Block block = blocks.get(d.block());
            if (block == null || !block.properties().keySet().containsAll(d.properties().keySet())) return -1;
            TreeMap<String, String> full = new TreeMap<>();
            for (Map.Entry<String, List<String>> property : block.properties().entrySet()) {
                String value = d.properties().getOrDefault(property.getKey(), property.getValue().get(0));
                if (!property.getValue().contains(value)) return -1;
                full.put(property.getKey(), value);
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
            return switch (own(h)) {
                case TURNS, ROTATES_ONLY -> known(Oracle.transform(describe(h), Transform.rotation(turns)));
                case QUIRKY -> known(Oracle.transform(describe(h), Transform.rotation(-turns)));
                case AXIS_ONLY -> known(axisOnly(describe(h), turns));
                case STUCK -> h;
                case THROWS -> throw new IllegalStateException("this block cannot turn");
            };
        }

        @Override
        public int mirror(int h, Mirror m) {
            return switch (own(h)) {
                case TURNS, QUIRKY -> known(Oracle.transform(describe(h), new Transform(0, m)));
                case STUCK, ROTATES_ONLY, AXIS_ONLY -> h;
                case THROWS -> throw new IllegalStateException("this block cannot mirror");
            };
        }

        private static BlockDescriptor axisOnly(BlockDescriptor d, int turns) {
            String axis = d.get("axis");
            if ((turns & 1) == 0 || axis.equals("y")) return d;
            return d.with("axis", axis.equals("x") ? "z" : "x");
        }

        private int known(BlockDescriptor d) {
            int h = resolve(d);
            if (h < 0) throw new AssertionError("no test state " + d);
            return h;
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return h;
        }

        @Override
        public int fluidSource(int h) {
            return -1;
        }
    }

    /** {@code own} with the fallback applied the way the Fabric space applies it: the fallback's answer first. */
    private record FallbackSpace(StateSpace own, ModdedFacingFallback fallback) implements StateSpace {
        @Override
        public int rotate(int h, int clockwiseQuarterTurns) {
            int turned = fallback.rotate(h, clockwiseQuarterTurns);
            return turned >= 0 ? turned : own.rotate(h, clockwiseQuarterTurns);
        }

        @Override
        public int mirror(int h, Mirror m) {
            int mirrored = fallback.mirror(h, m);
            return mirrored >= 0 ? mirrored : own.mirror(h, m);
        }

        @Override
        public int size() {
            return own.size();
        }

        @Override
        public int air() {
            return own.air();
        }

        @Override
        public int flags(int h) {
            return own.flags(h);
        }

        @Override
        public String format(int h) {
            return own.format(h);
        }

        @Override
        public int parse(String spec) {
            return own.parse(spec);
        }

        @Override
        public BlockDescriptor describe(int h) {
            return own.describe(h);
        }

        @Override
        public int resolve(BlockDescriptor d) {
            return own.resolve(d);
        }

        @Override
        public NamespacedId blockId(int h) {
            return own.blockId(h);
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            return own.inTag(h, tag);
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            return own.withWaterlogged(h, on);
        }

        @Override
        public int fluidSource(int h) {
            return own.fluidSource(h);
        }
    }
}
