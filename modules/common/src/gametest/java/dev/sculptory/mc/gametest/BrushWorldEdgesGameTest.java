package dev.sculptory.mc.gametest;

import static dev.sculptory.mc.gametest.EditTestSupport.capture;
import static dev.sculptory.mc.gametest.EditTestSupport.checkSame;
import static dev.sculptory.mc.gametest.EditTestSupport.difference;
import static dev.sculptory.mc.gametest.EditTestSupport.loadAndForce;
import static dev.sculptory.mc.gametest.EngineTestSupport.box;
import static dev.sculptory.mc.gametest.EngineTestSupport.check;
import static dev.sculptory.mc.gametest.EngineTestSupport.forceChunks;
import static dev.sculptory.mc.gametest.EngineTestSupport.regionCorner;
import static dev.sculptory.mc.gametest.GameTestSuite.EMPTY_STRUCTURE;

import dev.sculptory.core.Box;
import dev.sculptory.core.brush.BiomeSpec;
import dev.sculptory.core.brush.BrushSpec;
import dev.sculptory.core.brush.BrushTool;
import dev.sculptory.core.brush.Dab;
import dev.sculptory.core.brush.Falloff;
import dev.sculptory.core.brush.SculptMode;
import dev.sculptory.core.brush.Shape;
import dev.sculptory.core.brush.SurfaceMask;
import dev.sculptory.core.brush.SurfacePlane;
import dev.sculptory.core.brush.Symmetry;
import dev.sculptory.core.brush.WeatherSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.region.Facing;
import dev.sculptory.mc.gametest.EditTestSupport.Harness;
import dev.sculptory.mc.gametest.EditTestSupport.WorldSnapshot;
import dev.sculptory.mc.world.BlockWriter;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.ChunkPermit;
import dev.sculptory.server.engine.DabOutcome;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.impl.EditExecutor;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.platform.WorldBiomes;
import dev.sculptory.server.platform.WriteOptions;
import java.util.List;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Every brush other than the Shape brush, dabbed on the world's highest layer and on its lowest, with the
 * permit-bounds check on ({@code -Dsculptory.checkPermitBounds=true}, which the GameTest runs set). Under that check a
 * brush that asks a permit about a cell outside the box it asked the permit for, or outside a per-cell permit's
 * layers, has its dab refused; near the build limits the boxes are clamped, so this is where such an ask would show.
 * Each tool gets ground of its own near both edges (stone, dirt and a grass top of uneven height, laid with the writer
 * so the world's own bottom, bedrock or a superflat floor, does not matter). For each brush setting, a dab at the top
 * and a dab at the bottom are refused nothing and run (the lane acknowledged them); each dab that is sure to act
 * changes its own edge's blocks or biomes ({@link Expect#CHANGE}); and undoing the strokes restores both areas' blocks
 * and their biomes exactly. A negative control proves the check would show: the same kind of dab under permits asked
 * for narrower layers than the brush writes is refused, and the edge check fails on it. Region slots 2600-2609.
 */
public final class BrushWorldEdgesGameTest {
    private static final int LIMIT = EngineTestSupport.CHUNK_GENERATION_TICK_LIMIT;
    /** Executor ticks allowed for a dab, a commit or an undo (a Heightmap step is worked out over several ticks). */
    private static final int TICKS = 2000;
    /** The side of the square area each edge's ground covers; the dabs land on its middle column. */
    private static final int SIDE = 33;
    private static final int MIDDLE = SIDE / 2;
    /** Layers of each edge's area: the ground's layers and the air above (or below) it, beyond any brush's writes. */
    private static final int LAYERS = 20;
    private static final String CHERRY = "minecraft:cherry_grove";
    private static final String DESERT = "minecraft:desert";

    /**
     * Raise in the Terrain mode (a mound pushed into the top layer, and one grown from the bottom layers) and in the
     * Surface mode: no dab is refused, each dab changes its edge, and the undo is exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_raise", timeoutTicks = LIMIT)
    public void raiseAtTheTopAndBottomOfTheWorldIsRefusedNothingAndUndoesExactly(GameTestHelper context) {
        try (Edges e = new Edges(context, 2600)) {
            e.run(Case.both("Raise", terrain(BrushTool.RAISE, 4, 1L), Expect.CHANGE));
            e.run(Case.both("Surface Raise", terrain(BrushTool.RAISE, 4, 2L).withSurface(null), Expect.CHANGE));
        }
        context.succeed();
    }

    /**
     * Lower in the Terrain mode (at the bottom it digs into the world's lowest layer) and in the Surface mode: no dab
     * is refused, each dab changes its edge, and the undo is exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_lower", timeoutTicks = LIMIT)
    public void lowerAtTheTopAndBottomOfTheWorldIsRefusedNothingAndUndoesExactly(GameTestHelper context) {
        try (Edges e = new Edges(context, 2601)) {
            e.run(Case.both("Lower", terrain(BrushTool.LOWER, 4, 3L), Expect.CHANGE));
            e.run(Case.both("Surface Lower", terrain(BrushTool.LOWER, 4, 4L).withSurface(null), Expect.CHANGE));
        }
        context.succeed();
    }

    /**
     * Smooth in the Terrain mode (the uneven ground evens out) and in the Surface mode (the volumetric vote, which
     * reads the cells around its ball on every axis): no dab is refused, each dab changes its edge, and the undo is
     * exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_smooth", timeoutTicks = LIMIT)
    public void smoothAtTheTopAndBottomOfTheWorldIsRefusedNothingAndUndoesExactly(GameTestHelper context) {
        try (Edges e = new Edges(context, 2602)) {
            e.run(Case.both("Smooth", terrain(BrushTool.SMOOTH, 5, 5L), Expect.CHANGE));
            e.run(Case.both("Surface Smooth", terrain(BrushTool.SMOOTH, 5, 6L).withSurface(null), Expect.CHANGE));
        }
        context.succeed();
    }

    /**
     * Flatten to the edges themselves: in the Terrain mode to flattenY = the top layer (filling up to the build limit)
     * and to the bottom layer (cutting down to it), and in the Surface mode to the planes on those layers. No dab is
     * refused, each dab changes its edge, and the undo is exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_flatten", timeoutTicks = LIMIT)
    public void flattenAtTheTopAndBottomOfTheWorldIsRefusedNothingAndUndoesExactly(GameTestHelper context) {
        try (Edges e = new Edges(context, 2603)) {
            e.run(new Case("Flatten", flatten(e.top, 7L), flatten(e.bottom, 7L), Expect.CHANGE));
            BrushSpec surface = terrain(BrushTool.FLATTEN, 4, 8L);
            e.run(new Case("Surface Flatten", surface.withSurface(new SurfacePlane(Facing.UP, e.top)),
                    surface.withSurface(new SurfacePlane(Facing.UP, e.bottom)), Expect.CHANGE));
        }
        context.succeed();
    }

    /**
     * Paint two blocks deep with cobblestone: at the bottom the repainted cells reach the world's lowest layer. No dab
     * is refused, each dab changes its edge, and the undo is exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_paint", timeoutTicks = LIMIT)
    public void paintAtTheTopAndBottomOfTheWorldIsRefusedNothingAndUndoesExactly(GameTestHelper context) {
        try (Edges e = new Edges(context, 2604)) {
            BrushSpec paint = new BrushSpec(BrushTool.PAINT, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE,
                    new Pattern.Single(e.h.state("minecraft:cobblestone")), SurfaceMask.ANY, 2, 0, 9L);
            e.run(Case.both("Paint", paint, Expect.CHANGE));
        }
        context.succeed();
    }

    /**
     * Palette Paint three blocks deep with a random mix of dirt and cobblestone. No dab is refused, each dab changes
     * its edge, and the undo is exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_palette", timeoutTicks = LIMIT)
    public void paletteAtTheTopAndBottomOfTheWorldIsRefusedNothingAndUndoesExactly(GameTestHelper context) {
        try (Edges e = new Edges(context, 2605)) {
            int dirt = e.h.state("minecraft:dirt"), cobble = e.h.state("minecraft:cobblestone");
            BrushSpec palette = new BrushSpec(BrushTool.PALETTE, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE,
                    new Pattern.Weighted(new int[] {dirt, cobble}, new int[] {2, 1}, 9L), SurfaceMask.ANY, 3, 0, 10L);
            e.run(Case.both("Palette", palette, Expect.CHANGE));
        }
        context.succeed();
    }

    /**
     * The Weather brush in each of its four modes in the Terrain mode, and Erode in the Surface mode, on the uneven
     * ground: no dab is refused, each dab changes its edge (except Melt at the bottom, which finds nothing to slump on
     * this ground, and Roughen, whose noise follows world coordinates, so what it finds depends on where the test
     * runs: those dabs only have to run), and the undo is exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_weather", timeoutTicks = LIMIT)
    public void weatherAtTheTopAndBottomOfTheWorldIsRefusedNothingAndUndoesExactly(GameTestHelper context) {
        try (Edges e = new Edges(context, 2606)) {
            for (WeatherSpec.Mode mode : WeatherSpec.Mode.values()) {
                BrushSpec spec = weather(mode, 11L + mode.ordinal());
                // Roughen's noise lies on the world's own lattice, so what it finds depends on where the test runs.
                Expect atTop = mode == WeatherSpec.Mode.ROUGHEN ? Expect.RAN : Expect.CHANGE;
                Expect atBottom = mode == WeatherSpec.Mode.ROUGHEN || mode == WeatherSpec.Mode.MELT ? Expect.RAN
                        : Expect.CHANGE;
                e.run(new Case("Weather " + mode, spec, spec, atTop, atBottom));
            }
            e.run(Case.both("Surface Weather ERODE", weather(WeatherSpec.Mode.ERODE, 20L).withSurface(null),
                    Expect.CHANGE));
        }
        context.succeed();
    }

    /**
     * The Heightmap brush, a cone six blocks tall: raising at the top (pushing columns into the build limit) and
     * lowering at the bottom (cutting toward the world's floor), then the other way round. Its dab box reaches the
     * stamp's height further up or down than the other brushes' do. No dab is refused, each pair of strokes changes
     * something, and the undo is exact.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_heightmap", timeoutTicks = LIMIT)
    public void heightmapAtTheTopAndBottomOfTheWorldIsRefusedNothingAndUndoesExactly(GameTestHelper context) {
        try (Edges e = new Edges(context, 2607)) {
            e.run(new Case("Heightmap raising at the top", heightmap(false), heightmap(true), Expect.CHANGE));
            e.run(new Case("Heightmap lowering at the top", heightmap(true), heightmap(false), Expect.CHANGE));
        }
        context.succeed();
    }

    /**
     * The Biome brush in 3D (a ball of quarts around the dab, part of it beyond the build height at the top and below
     * it at the bottom) and in columns mode (whole quart columns, the full build height). Each stroke paints a biome
     * other than the one under its dab. No dab is refused, the strokes change biomes and no blocks, and the undo
     * restores the biomes exactly.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_biome", timeoutTicks = LIMIT)
    public void biomeAtTheTopAndBottomOfTheWorldIsRefusedNothingAndUndoesExactly(GameTestHelper context) {
        try (Edges e = new Edges(context, 2608)) {
            String upper = e.otherBiome(e.top), lower = upper.equals(CHERRY) ? DESERT : CHERRY;
            e.run(new Case("Biome 3D", biome(4, upper, false), biome(4, lower, false), Expect.CHANGE));
            e.run(new Case("Biome columns", biome(3, upper, true), biome(3, lower, true), Expect.CHANGE));
        }
        context.succeed();
    }

    /**
     * The negative control: the edge check is only worth its "refused nothing" if a brush asking outside its permit's
     * layers is refused. The same Raise dab at the top and at the bottom, under per-cell permits that hold only the
     * lowest layer of the box each was asked for (a source whose bounds were narrowed on purpose; that layer denied),
     * must be refused: the permit-bounds check's exception inside the dab's step becomes the dab's refusal
     * ({@code INVALID}, reported through {@code dabRejected}), the edge check fails on it, nothing is written, and no
     * step is recorded.
     */
    @GameTest(template = EMPTY_STRUCTURE, batch = "sculptory_brush_edges_control", timeoutTicks = LIMIT)
    public void aPermitNarrowedOnPurposeIsCaughtByTheEdgeCheck(GameTestHelper context) {
        try (Edges e = new Edges(context, 2609, BrushWorldEdgesGameTest::lowestLayerDenied)) {
            WorldSnapshot upperBefore = capture(e.world, e.upper), lowerBefore = capture(e.world, e.lower);
            BrushSpec raise = terrain(BrushTool.RAISE, 4, 1L);
            GameTestAssertException caught = null;
            try {
                e.run(new Case("Raise under narrowed permits", raise, raise, Expect.CHANGE, Expect.CHANGE));
            } catch (GameTestAssertException expected) {
                caught = expected;
            }
            check(caught != null && caught.getMessage().contains("dabs rejected"),
                    "the edge check did not fail on narrowed permits at the top: " + caught);
            // The edge check stops at the top: the bottom dab on its own.
            GameTestAssertException caughtBelow = null;
            try {
                e.stroke("Raise under narrowed permits at the bottom", raise, e.dab(e.bottom));
            } catch (GameTestAssertException expected) {
                caughtBelow = expected;
            }
            check(caughtBelow != null && caughtBelow.getMessage().contains("dabs rejected"),
                    "the bottom dab was not refused under narrowed permits: " + caughtBelow);
            long invalid = e.h.events.dabRejections.stream().filter(reason -> reason == RejectReason.INVALID).count();
            check(invalid == 2 && e.h.events.dabRejections.size() == 2,
                    "both dabs refused INVALID: " + e.h.events.dabRejections);
            checkSame(upperBefore, capture(e.world, e.upper), "a refused dab wrote something at the top");
            checkSame(lowerBefore, capture(e.world, e.lower), "a refused dab wrote something at the bottom");
            check(e.undoEntries() == 0, "a refused stroke recorded " + e.undoEntries() + " entries");
        }
        context.succeed();
    }

    /**
     * A per-cell permit holding only the lowest layer of {@code bounds} (narrowed on purpose), denying it: no cell is
     * ever written, and asking about any other layer is outside the permit.
     */
    private static ChunkPermit lowestLayerDenied(int cx, int cz, Box bounds) {
        int y = bounds.min().y();
        return new ChunkPermit.Cells(y, y, new long[4]);
    }

    // ------------------------------------------------------------------ helpers

    /** What a dab of a {@link Case} must do at its edge, beyond being refused nothing. */
    private enum Expect {
        /** Change its own edge's blocks or biomes: the setting is sure to act on this ground. */
        CHANGE,
        /**
         * Run: accepted, refused nothing, and acknowledged by its lane once done. Whether it then finds work depends on
         * the ground (a Surface-mode or Weather setting may find no edge, pit or plane to act on).
         */
        RAN
    }

    /** One brush setting, dabbed once at the top ({@code top}) and once at the bottom ({@code bottom}). */
    private record Case(String name, BrushSpec top, BrushSpec bottom, Expect atTop, Expect atBottom) {
        Case(String name, BrushSpec top, BrushSpec bottom, Expect expect) {
            this(name, top, bottom, expect, expect);
        }

        static Case both(String name, BrushSpec spec, Expect expect) {
            return new Case(name, spec, spec, expect, expect);
        }
    }

    /** A terrain brush of {@code tool} at full strength with constant falloff, in the Terrain mode. */
    private static BrushSpec terrain(BrushTool tool, int radius, long seed) {
        return new BrushSpec(tool, radius, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, seed);
    }

    private static BrushSpec flatten(int flattenY, long seed) {
        return new BrushSpec(BrushTool.FLATTEN, 4, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0,
                flattenY, seed);
    }

    private static BrushSpec weather(WeatherSpec.Mode mode, long seed) {
        return new BrushSpec(BrushTool.WEATHER, 4, 1f, Falloff.SMOOTH, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0, seed,
                null, Symmetry.NONE, null, SculptMode.TERRAIN, null, new WeatherSpec(mode));
    }

    private static BrushSpec heightmap(boolean lower) {
        return HeightmapGameTest.heightmap(4, HeightmapGameTest.cone(4), 6, lower, SurfaceMask.ANY, null,
                Symmetry.NONE);
    }

    private static BrushSpec biome(int radius, String id, boolean columns) {
        return new BrushSpec(BrushTool.BIOME, radius, 1f, Falloff.CONSTANT, Shape.CIRCLE, null, SurfaceMask.ANY, 0, 0,
                1L, null, Symmetry.NONE, null, SculptMode.TERRAIN, null, null, null, new BiomeSpec(id, columns));
    }

    /**
     * One test's set-up: a mock player with an edit service over a private executor (ticked here), and an area of
     * {@value #SIDE} by {@value #SIDE} columns at each edge of the world, forced loaded and laid with uneven ground:
     * at the top the ground's surface lies one to four layers below the top layer, at the bottom it lies on the lowest
     * layer to three layers above it. The middle column's surface is the highest at the top (just under the top layer)
     * and the lowest at the bottom (the lowest layer itself). {@link #close} releases everything.
     */
    private static final class Edges implements AutoCloseable {
        final EditExecutor<ServerLevel> executor;
        final Harness h;
        final ServerLevel world;
        final int x0;
        final int z0;
        final int top;
        final int bottom;
        final Box upper;
        final Box lower;
        /** The edit service the strokes go through: the harness's, or one with a test's own permits. */
        final EngineEditService<ServerPlayer, ServerLevel> service;
        private int strokeId = 1;
        private int seq = 1;

        Edges(GameTestHelper context, int slot) {
            this(context, slot, null);
        }

        /** With {@code permits} (not null) the strokes' chunk permits come from it instead of the live protection. */
        Edges(GameTestHelper context, int slot, EditTestSupport.PermitMaker permits) {
            check(Boolean.getBoolean("sculptory.checkPermitBounds"), "the GameTest run does not check permit bounds");
            executor = MultiplayerGameTest.executor(context, 0);
            h = new Harness(context, null, System::nanoTime, executor);
            service = permits == null ? h.service : EditTestSupport.permitService(h, executor, permits);
            world = h.world;
            int[] corner = regionCorner(context, slot);
            x0 = corner[0];
            z0 = corner[1];
            bottom = world.getMinBuildHeight();
            top = world.getMaxBuildHeight() - 1;
            upper = box(x0, top - LAYERS + 1, z0, x0 + SIDE - 1, top, z0 + SIDE - 1);
            lower = box(x0, bottom, z0, x0 + SIDE - 1, bottom + LAYERS - 1, z0 + SIDE - 1);
            loadAndForce(world, upper);
            loadAndForce(world, lower);
            ground(upper, (dx, dz) -> top - 1 - bump(dx, dz));
            ground(lower, (dx, dz) -> bottom + bump(dx, dz));
        }

        /** 0 to 3 blocks of unevenness, 0 at the middle column. */
        private static int bump(int dx, int dz) {
            if (dx == MIDDLE && dz == MIDDLE) return 0;
            return Math.floorMod(dx / 3 + dz / 4 + ((dx * 7 + dz * 3) & 1), 4);
        }

        @FunctionalInterface
        private interface Surface {
            int y(int dx, int dz);
        }

        /** Writes every cell of {@code area}: stone, two layers of dirt, grass on the surface, air above it. */
        private void ground(Box area, Surface surface) {
            BlockWriter writer = h.runtime.writer(world, WriteOptions.DEFAULT);
            int stone = h.state("minecraft:stone"), dirt = h.state("minecraft:dirt");
            int grass = h.state("minecraft:grass_block"), air = h.state("minecraft:air");
            for (int dx = 0; dx < SIDE; dx++) {
                for (int dz = 0; dz < SIDE; dz++) {
                    int s = surface.y(dx, dz);
                    for (int y = area.min().y(); y <= area.max().y(); y++) {
                        int state = y > s ? air : y == s ? grass : y >= s - 2 ? dirt : stone;
                        writer.write(x0 + dx, y, z0 + dz, state, null);
                    }
                }
            }
        }

        /** The dab on the middle column at height {@code y}, centred in its block as the cursor would place it. */
        Dab dab(int y) {
            return ShapeBrushGameTest.at(0, x0 + MIDDLE, y, z0 + MIDDLE);
        }

        /**
         * Dabs {@code c} at the top and at the bottom, checks that nothing was refused, that each dab ran, and that
         * each {@link Expect#CHANGE} dab changed its own edge's blocks or biomes; undoes every entry the strokes made
         * and checks that both areas' blocks and biomes are exactly as before.
         */
        void run(Case c) {
            WorldSnapshot beforeUpper = capture(world, upper), beforeLower = capture(world, lower);
            int[] beforeBiomes = biomes();
            int entries = undoEntries();
            stroke(c.name() + " at the top", c.top(), dab(top));
            WorldSnapshot afterTop = capture(world, lower);
            int[] afterTopBiomes = biomes();
            boolean topChanged = difference(beforeUpper, capture(world, upper)) != null
                    || biomeDifference(beforeBiomes, afterTopBiomes) != null;
            stroke(c.name() + " at the bottom", c.bottom(), dab(bottom));
            boolean bottomChanged = difference(afterTop, capture(world, lower)) != null
                    || biomeDifference(afterTopBiomes, biomes()) != null;
            check(h.events.dabRejections.isEmpty(), c.name() + ": dabs rejected: " + h.events.dabRejections);
            int made = undoEntries() - entries;
            check(made >= 0 && made <= 2, c.name() + ": " + made + " history entries for two strokes");
            org.slf4j.LoggerFactory.getLogger("sculptory").info("Brush edges: {}: the top dab changed {}, the bottom "
                    + "dab changed {} ({} entries)", c.name(), topChanged, bottomChanged, made);
            check(c.atTop() != Expect.CHANGE || topChanged, c.name() + ": the dab at the top changed nothing there");
            check(c.atBottom() != Expect.CHANGE || bottomChanged,
                    c.name() + ": the dab at the bottom changed nothing there");
            check(!(topChanged || bottomChanged) || made > 0, c.name() + ": changes without a history entry");
            if (c.top().tool() == BrushTool.BIOME) {
                checkSame(beforeUpper, capture(world, upper), c.name() + ": blocks at the top");
                checkSame(beforeLower, capture(world, lower), c.name() + ": blocks at the bottom");
            }
            ShapeBrushGameTest.undoAll(h, executor, made, TICKS);
            checkSame(beforeUpper, capture(world, upper), c.name() + ": the top after the undo");
            checkSame(beforeLower, capture(world, lower), c.name() + ": the bottom after the undo");
            String biomes = biomeDifference(beforeBiomes, biomes());
            check(biomes == null, c.name() + ": the biomes after the undo: " + biomes);
        }

        /**
         * One stroke of one dab: begun, the dab applied, ended, and its record committed (a Heightmap step is worked
         * out and a large record built over executor ticks, so both are waited for rather than assumed done). The dab
         * must have run: its lane acknowledged it before the stroke ended (a dab dropped at the stroke's end is
         * acknowledged only then).
         */
        private void stroke(String what, BrushSpec spec, Dab dab) {
            int id = strokeId++;
            try {
                service.beginStroke(h.player, id, spec);
            } catch (EditRejected e) {
                throw new GameTestAssertException(what + ": the stroke was refused: " + e.getMessage());
            }
            DabOutcome outcome = service.dabs(h.player, id, seq++, List.of(dab));
            check(outcome.accepted(), what + ": " + outcome);
            MultiplayerGameTest.tickUntil(executor, () -> service.queuedDabs(h.player.getUUID()) == 0, TICKS, what);
            String applied = id + ":" + dab.index();
            check(!h.events.rejectedStrokes.contains(id), what + ": dabs rejected: " + h.events.dabRejections);
            check(h.events.dabsApplied.contains(applied), what + ": the dab was not run (acknowledged: "
                    + h.events.dabsApplied + ")");
            service.endStroke(h.player, id);
            MultiplayerGameTest.tickUntil(executor, () -> service.queuedDabs(h.player.getUUID()) == 0
                    && service.queuedCommits(h.player.getUUID()) == 0, TICKS, what + ": the commit");
            service.tick();
        }

        int undoEntries() {
            return service.historyService().undoEntries(h.player.getUUID()).size();
        }

        /** A biome other than the one of the middle column's quart at height {@code y}. */
        String otherBiome(int y) {
            WorldBiomes biomes = h.runtime.biomes(world);
            int handle = biomes.get((x0 + MIDDLE) >> 2, y >> 2, (z0 + MIDDLE) >> 2);
            return handle >= 0 && biomes.id(handle).equals(CHERRY) ? DESERT : CHERRY;
        }

        /** The biome handle of every quart of the areas' columns, over the whole build height. */
        private int[] biomes() {
            WorldBiomes biomes = h.runtime.biomes(world);
            int qx0 = x0 >> 2, qx1 = (x0 + SIDE - 1) >> 2, qz0 = z0 >> 2, qz1 = (z0 + SIDE - 1) >> 2;
            int qy0 = bottom >> 2, qy1 = ((top + 1) >> 2) - 1;
            int[] out = new int[(qx1 - qx0 + 1) * (qz1 - qz0 + 1) * (qy1 - qy0 + 1)];
            int i = 0;
            for (int qx = qx0; qx <= qx1; qx++) {
                for (int qz = qz0; qz <= qz1; qz++) {
                    for (int qy = qy0; qy <= qy1; qy++) out[i++] = biomes.get(qx, qy, qz);
                }
            }
            return out;
        }

        /** How many quarts differ and the first one's biomes, or {@code null} when none does. */
        private String biomeDifference(int[] expected, int[] actual) {
            WorldBiomes biomes = h.runtime.biomes(world);
            int wrong = 0;
            String first = null;
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] == actual[i]) continue;
                wrong++;
                if (first == null) {
                    first = "quart " + i + ": " + name(biomes, actual[i]) + " instead of " + name(biomes, expected[i]);
                }
            }
            return wrong == 0 ? null : wrong + " quarts differ; first " + first;
        }

        private static String name(WorldBiomes biomes, int handle) {
            return handle < 0 ? "an unlisted biome" : biomes.id(handle);
        }

        @Override
        public void close() {
            if (service != h.service) service.shutdown();
            executor.shutdown();
            forceChunks(world, upper, false);
            forceChunks(world, lower, false);
            h.close();
        }
    }
}
