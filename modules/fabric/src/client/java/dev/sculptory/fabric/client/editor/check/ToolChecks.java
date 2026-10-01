package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.lwjgl.glfw.GLFW;

/**
 * More tools through the real client: Generate (a road between two clicked
 * nodes, a roof over a selected footprint), Extrude (pull a face out, carve it in, smear it), the Fluid tool (flood a
 * basin, drain a pool, a fluid ball) and the Shape brush (one click is one shape, a drag paints several, one undo).
 */
final class ToolChecks {
    static final String GENERATE = "generate";
    static final String EXTRUDE = "extrude";
    static final String FLUID = "fluid";
    static final String SHAPE = "shape";
    private static final String PLANKS = "minecraft:oak_planks";

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(GENERATE, b -> {
                        // A hollow house of planks 4 high, for the roof.
                        b.fill(10, 0, 26, 16, 3, 32, PLANKS);
                        b.fill(11, 0, 27, 15, 3, 31, "minecraft:air");
                    }),
                    new Fixtures.Fixture(EXTRUDE, b -> b.fill(14, 0, 17, 20, 4, 20, PLANKS)),
                    new Fixtures.Fixture(FLUID, b -> {
                        // A dry basin (inside 21..26 x 21..26, 3 deep) and a pool (inside 5..10 x 21..26, 2 deep).
                        b.fill(20, 0, 20, 27, 2, 27, "minecraft:stone_bricks");
                        b.fill(21, 0, 21, 26, 2, 26, "minecraft:air");
                        b.fill(4, 0, 20, 11, 1, 27, "minecraft:stone_bricks");
                        b.fill(5, 0, 21, 10, 1, 26, "minecraft:water");
                        b.set(6, 0, 22, "minecraft:seagrass");
                        b.set(9, 0, 25, "minecraft:oak_stairs[facing=north,waterlogged=true]");
                    }),
                    new Fixtures.Fixture(SHAPE, b -> {
                    })),
            List.of(Scenario.visual("generate-road", GENERATE,
                            "Generate Path: two clicked nodes, the road's ghost, Enter builds it; one undo",
                            ToolChecks::road),
                    Scenario.of("generate-roof", GENERATE, "Generate Roof over a house's selected top: Enter; one undo",
                            ToolChecks::roof),
                    Scenario.visual("extrude", EXTRUDE, "Extrude a wall's face out by dragging, carve it in, smear it"
                            + " with Alt", ToolChecks::extrude),
                    Scenario.visual("fluid-tool", FLUID, "Fluid tool: flood a basin, drain a pool (seagrass, a"
                            + " waterlogged stair), a water ball", ToolChecks::fluid),
                    Scenario.visual("shape-brush", SHAPE, "Shape brush: one click is one sphere, a drag paints several,"
                            + " one undo takes the drag", ToolChecks::shapeBrush)));

    private ToolChecks() {}

    private static void road(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -3, 0, 39, 4, 24);
        Cells before = run.server(area);
        d.selectTool(ToolId.GENERATE);
        d.setting(ToolId.GENERATE, "kind", "PATH");
        d.setting(ToolId.GENERATE, "path.material", "BLOCK");
        d.setting(ToolId.GENERATE, "path.block", "minecraft:cobblestone");
        d.overlook(a.x(19), a.y(-1), a.z(20));
        d.aim(a.x(12), a.y(-1), a.z(20), Face.UP);
        d.click(0);
        d.aim(a.x(26), a.y(-1), a.z(20), Face.UP);
        d.click(0);
        d.frames(30);
        run.picture("road-ghost", "The ghost of a cobblestone road three wide between the two path nodes");
        run.edit("build the road", () -> d.action(KeyAction.COMMIT));
        Cells built = run.server(area);
        List<Cells.Change> changes = before.diff(built);
        Predicate<Cells.Change> onRoad = c -> Cells.describe(c.after()).equals("minecraft:cobblestone")
                && Math.abs(c.z() - a.z(20)) <= 2 && c.x() >= a.x(9) && c.x() <= a.x(29);
        run.check("Enter builds a cobblestone road between the nodes", changes.size() >= 30,
                changes.size() + " cells changed");
        run.check("the road lies only along the path", Changes.offenders(changes, onRoad).isEmpty(),
                Changes.offenders(changes, onRoad));
        run.clientMatches("the road", built);
        run.picture("road", "A straight cobblestone road set into the stone floor");
        run.undoRedo("the road", before, built);
        // Esc clears the path's nodes.
        d.key(GLFW.GLFW_KEY_ESCAPE, 0);
    }

    private static void roof(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(4, -1, 22, 22, 16, 36);
        Cells before = run.server(area);
        d.selectTool(ToolId.SELECT);
        PlaceChecks.selectTopFaces(run, a.box(10, 3, 26, 16, 3, 32));
        d.selectTool(ToolId.GENERATE);
        d.setting(ToolId.GENERATE, "kind", "ROOF");
        d.view(a.x(24) + 0.5, a.y(12), a.z(40) + 0.5, a.x(13) + 0.5, a.y(4), a.z(29) + 0.5);
        d.frames(30);
        run.picture("roof-ghost", "The ghost of an oak gable roof over the plank house");
        run.edit("build the roof", () -> d.action(KeyAction.COMMIT));
        Cells built = run.server(area);
        List<Cells.Change> changes = before.diff(built);
        long stairs = changes.stream().filter(c -> Cells.describe(c.after()).startsWith("minecraft:oak_stairs")).count();
        Predicate<Cells.Change> over = c -> c.x() >= a.x(9) && c.x() <= a.x(17) && c.z() >= a.z(25)
                && c.z() <= a.z(33) && c.y() >= a.y(3);
        run.check("Enter builds a roof of oak stairs", stairs > 10, changes.size() + " cells changed, " + stairs
                + " of them stairs");
        run.check("the roof covers the footprint and its overhang only", Changes.offenders(changes, over).isEmpty(),
                Changes.offenders(changes, over));
        run.clientMatches("the roof", built);
        run.picture("roof", "A gable roof of oak stairs on the plank house, one block of overhang");
        run.undoRedo("the roof", before, built);
        d.setting(ToolId.GENERATE, "kind", "PATH");
    }

    private static void extrude(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(8, -1, 12, 30, 8, 32);
        Box face = a.box(14, 0, 20, 20, 4, 20);
        Cells before = run.server(area);
        BlockState planks = Fixtures.Build.state(PLANKS);
        BlockState air = Blocks.AIR.getDefaultState();
        d.selectTool(ToolId.EXTRUDE);
        straightView(d, a);

        // Pull the face out about three layers.
        Cells pulled = faceDrag(run, area, 0, a.x(17) + 0.5, a.y(2) + 0.5, a.z(24) + 0.2, "extrude");
        OptionalInt out = match(pulled, 1, 8, n -> {
            Cells.Editor expected = before.edit();
            forEach(face, (x, y, z) -> {
                for (int k = 1; k <= n; k++) {
                    expected.set(x, y, z + k, planks);
                }
            });
            return expected.done();
        });
        run.check("dragging away from the face pulls it out by whole layers", out.isPresent(),
                out.isPresent() ? out.getAsInt() + " layers" : "no layer count explains " + Cells.summary(before.diff(pulled), 4));
        run.clientMatches("the extrusion", pulled);
        angledFaceView(d, a);
        run.picture("extruded", "From the side: the plank wall's face pulled out (south) by a few layers");
        run.undo("undo the extrusion");
        run.exact("undo takes the extrusion back", before);

        // Drag into the face: carve two layers (the face layer and the one behind it).
        Cells carved = faceDrag(run, area, 0, a.x(17) + 0.5, a.y(2) + 0.5, a.z(19) + 0.2, "carve");
        OptionalInt in = match(carved, 1, 4, n -> {
            Cells.Editor expected = before.edit();
            forEach(face, (x, y, z) -> {
                for (int k = 0; k < n; k++) {
                    expected.set(x, y, z - k, air);
                }
            });
            return expected.done();
        });
        run.check("dragging into the face carves whole layers, the face layer included", in.isPresent(),
                in.isPresent() ? in.getAsInt() + " layers" : "no layer count explains " + Cells.summary(before.diff(carved), 4));
        run.undo("undo the carve");
        run.exact("undo fills the carve back", before);

        // Alt+drag along the face: the face layer slides sideways, leaving air.
        Cells smeared = faceDrag(run, area, GLFW.GLFW_MOD_ALT, a.x(20) + 0.5, a.y(2) + 0.5, a.z(21), "smear");
        OptionalInt slid = match(smeared, 1, 8, n -> {
            Cells.Editor expected = before.edit();
            forEach(face, (x, y, z) -> expected.set(x, y, z, air));
            forEach(face, (x, y, z) -> expected.set(x + n, y, z, planks));
            return expected.done();
        });
        run.check("Alt+drag slides the face layer along the drag and leaves air", slid.isPresent(),
                slid.isPresent() ? slid.getAsInt() + " blocks east" : "no slide explains "
                        + Cells.summary(before.diff(smeared), 4));
        angledFaceView(d, a);
        run.picture("smeared", "From the side: the wall's front layer slid a few blocks east, air left where it was");
        run.undo("undo the smear");
        run.exact("undo puts the face layer back", before);
    }

    private static void fluid(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 39, 8, 39);
        Box basin = a.box(21, 0, 21, 26, 2, 26);
        Cells before = run.server(area);
        d.selectTool(ToolId.FLUID);
        d.setting(ToolId.FLUID, "mode", "FLOOD");
        d.setting(ToolId.FLUID, "fluid", "WATER");

        // Flood: aimed at the basin's inside wall one above its floor.
        d.view(a.x(23) + 0.5, a.y(9), a.z(15) + 0.5, a.x(23) + 0.5, a.y(0), a.z(24) + 0.5);
        d.aim(a.x(23), a.y(1), a.z(27), Face.NORTH);
        d.frames(30);
        run.picture("flood-preview", "The basin's air up to the aimed level shown blue before the click");
        run.edit("flood", () -> d.click(0));
        Cells flooded = run.server(area);
        List<Cells.Change> changes = before.diff(flooded);
        Predicate<Cells.Change> inBasin = c -> basin.contains(c.x(), c.y(), c.z()) && c.after().isOf(Blocks.WATER)
                && c.after().getFluidState().isStill() && c.y() <= a.y(1);
        run.check("Flood fills the basin with still water, never above the aimed level",
                !changes.isEmpty() && Changes.offenders(changes, inBasin).isEmpty(),
                changes.size() + " cells; " + Changes.offenders(changes, inBasin));
        run.check("Flood fills whole layers of the basin", changes.size() % 36 == 0,
                changes.size() + " cells (36 a layer)");
        run.clientMatches("the flood", flooded);
        run.undo("undo the flood");
        run.exact("undo drains the flood exactly", before);

        // Drain the pool: water gone, seagrass gone, the stair dry.
        d.setting(ToolId.FLUID, "mode", "DRAIN");
        d.view(a.x(7) + 0.5, a.y(9), a.z(15) + 0.5, a.x(7) + 0.5, a.y(0), a.z(24) + 0.5);
        d.aim(a.x(7), a.y(1), a.z(23), Face.UP);
        d.frames(30);
        run.picture("drain-preview", "The pool's water outlined as the body Drain will remove");
        run.edit("drain", () -> d.click(0));
        Cells.Editor dry = before.edit();
        for (int x = 5; x <= 10; x++) {
            for (int z = 21; z <= 26; z++) {
                for (int y = 0; y <= 1; y++) {
                    BlockState state = before.at(a.x(x), a.y(y), a.z(z));
                    if (state.isOf(Blocks.WATER) || state.isOf(Blocks.SEAGRASS)) {
                        dry.set(a.x(x), a.y(y), a.z(z), Blocks.AIR.getDefaultState());
                    }
                }
            }
        }
        dry.set(a.x(9), a.y(0), a.z(25), Fixtures.Build.state("minecraft:oak_stairs[facing=north,waterlogged=false]"));
        run.exact("Drain removes the pool's water and seagrass and dries the waterlogged stair", dry.done());
        run.undo("undo the drain");
        run.exact("undo refills the pool exactly", before);

        // A water ball on the floor.
        d.setting(ToolId.FLUID, "mode", "BALL");
        d.setting(ToolId.FLUID, "radius", 3);
        d.overlook(a.x(30), a.y(-1), a.z(8));
        Cells ball = BrushChecks.stroke(run, area, a.x(30), a.y(-1), a.z(8), Face.UP, 100, "water ball");
        List<Cells.Change> ballChanges = before.diff(ball);
        Predicate<Cells.Change> water = c -> c.after().isOf(Blocks.WATER) && c.x() >= a.x(26) && c.x() <= a.x(34)
                && c.z() >= a.z(4) && c.z() <= a.z(12);
        run.check("a click with the fluid ball places one ball of water", !ballChanges.isEmpty()
                && Changes.offenders(ballChanges, water).isEmpty(), ballChanges.size() + " cells; "
                + Changes.offenders(ballChanges, water));
        run.undo("undo the ball");
        run.exact("undo takes the ball back", before);
        d.setting(ToolId.FLUID, "mode", "FLOOD");
    }

    private static void shapeBrush(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 39, 12, 39);
        Cells before = run.server(area);
        d.activeBlock("minecraft:mossy_cobblestone");
        d.selectTool(ToolId.SHAPE);
        d.setting(ToolId.SHAPE, "kind", "SPHERE");
        d.setting(ToolId.SHAPE, "radius", 3);
        d.overlook(a.x(12), a.y(-1), a.z(12));
        Cells one = BrushChecks.click(run, area, a.x(12), a.y(-1), a.z(12), Face.UP, "one click");
        List<Cells.Change> changes = before.diff(one);
        Box bounds = bounds(changes);
        run.check("one click places one sphere", !changes.isEmpty() && bounds.sizeX() <= 7 && bounds.sizeY() <= 7
                && bounds.sizeZ() <= 7, changes.size() + " cells in " + bounds);
        run.check("the sphere rests on the clicked block", bounds.min().y() == a.y(0), "its bottom is at y "
                + bounds.min().y());
        run.picture("one-sphere", "One mossy cobblestone ball resting on the floor where it was clicked");

        d.overlook(a.x(15), a.y(-1), a.z(26));
        Cells dragged = BrushChecks.drag(run, area, a.x(9), a.y(-1), a.z(26), Face.UP, a.x(21) + 0.5, a.y(0),
                a.z(26) + 0.5, "a drag");
        List<Cells.Change> dragChanges = one.diff(dragged);
        Box dragBounds = bounds(dragChanges);
        run.check("a drag paints several spheres along it", dragBounds.sizeX() > 10,
                dragChanges.size() + " cells in " + dragBounds);
        run.picture("dragged", "A row of mossy cobblestone balls along the drag");
        run.undo("one undo");
        run.exact("one undo takes the whole drag back", one);
        run.undo("undo the click");
        run.exact("and the click", before);
        d.activeBlock("minecraft:stone");
    }

    // ---- Helpers ----

    /**
     * Hovers the Extrude face, waits for it to light up, presses (with {@code modifiers}), drags to a world point in 12
     * frames and releases; the area's cells once the server is done.
     */
    /** Looks straight at the plank wall's south face from 12 blocks south, a little above (for aiming and dragging). */
    private static void straightView(CheckDriver d, Area a) {
        d.view(a.x(17) + 0.5, a.y(7), a.z(33) + 0.5, a.x(17) + 0.5, a.y(2), a.z(21));
    }

    /** For a picture: the wall's face from the south-west and above, so what was pulled out or slid shows in depth. */
    private static void angledFaceView(CheckDriver d, Area a) {
        d.viewAngled(a.x(17) + 0.5, a.y(2) + 0.5, a.z(21), Face.SOUTH, 35, 25, 16);
    }

    private static Cells faceDrag(CheckRun run, Box area, int modifiers, double x, double y, double z, String what) {
        CheckDriver d = run.driver();
        Area a = run.area();
        straightView(d, a);
        d.aim(a.x(17), a.y(2), a.z(20), Face.SOUTH);
        d.frames(20);
        run.edit(what, () -> {
            d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, modifiers);
            d.dragTo(x, y, z, 12, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        });
        return run.server(area);
    }

    /** The first n in [from, to] whose expected cells equal {@code actual}. */
    private static OptionalInt match(Cells actual, int from, int to, IntFunction<Cells> expected) {
        for (int n = from; n <= to; n++) {
            if (expected.apply(n).same(actual)) {
                return OptionalInt.of(n);
            }
        }
        return OptionalInt.empty();
    }

    private static void forEach(Box box, SelectModeChecks.CellVisitor visitor) {
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    visitor.visit(x, y, z);
                }
            }
        }
    }

    private static Box bounds(List<Cells.Change> changes) {
        if (changes.isEmpty()) {
            return Box.of(new dev.sculptory.core.BlockPos(0, 0, 0));
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Cells.Change c : changes) {
            minX = Math.min(minX, c.x());
            minY = Math.min(minY, c.y());
            minZ = Math.min(minZ, c.z());
            maxX = Math.max(maxX, c.x());
            maxY = Math.max(maxY, c.y());
            maxZ = Math.max(maxZ, c.z());
        }
        return Box.of(new dev.sculptory.core.BlockPos(minX, minY, minZ),
                new dev.sculptory.core.BlockPos(maxX, maxY, maxZ));
    }
}
