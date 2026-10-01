package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.place.Placement;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.lwjgl.glfw.GLFW;

/**
 * Selections other than a plain box, through the real client: a sphere dragged between two posts (Fill, Erase), magic
 * select on a grass patch, a brush selection painted across the floor, a lasso loop; each filled, and the lasso's cells
 * moved, checked cell by cell against the selection the client holds.
 */
final class SelectModeChecks {
    static final String AREA = "select-modes";
    private static final String GLASS = "minecraft:glass";

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(AREA, b -> {
                // Two posts whose tops span a 7x7x7 box, for the sphere.
                b.set(4, 0, 4, "minecraft:stone_bricks");
                b.fill(10, 0, 10, 10, 6, 10, "minecraft:stone_bricks");
                // Two grass patches on the stone, the second not touching the first.
                b.fill(20, 0, 4, 25, 0, 9, "minecraft:grass_block");
                b.fill(28, 0, 4, 30, 0, 6, "minecraft:grass_block");
                // Bumps for the brush selection to paint over.
                b.fill(6, 0, 22, 14, 0, 22, "minecraft:cobblestone");
            })),
            List.of(Scenario.visual("select-shape-sphere", AREA,
                            "A Sphere selection dragged between two posts: Fill makes a sphere, Erase empties it",
                            SelectModeChecks::sphere),
                    Scenario.of("select-magic", AREA, "Magic select on a grass patch, then Fill: only that patch",
                            SelectModeChecks::magic),
                    Scenario.of("select-brush", AREA, "Brush selection painted across the floor, then Fill",
                            SelectModeChecks::brush),
                    Scenario.of("select-lasso", AREA, "A lasso loop on the floor, two high: Fill, then Move its cells",
                            SelectModeChecks::lasso)));

    private SelectModeChecks() {}

    private static void sphere(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 18, 10, 18);
        Cells before = run.server(area);
        d.view(a.x(7) + 0.5, a.y(9), a.z(24) + 0.5, a.x(7) + 0.5, a.y(3), a.z(7) + 0.5);
        d.selectTool(ToolId.SELECT);
        d.setting(ToolId.SELECT, "shape", "SPHERE");
        d.aim(a.x(4), a.y(0), a.z(4), Face.UP);
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.dragTo(a.x(10) + 0.5, a.y(7), a.z(10) + 0.5, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        Region region = selection(run, "Shape");
        run.require("the drag between the posts makes a sphere in their 7x7x7 box",
                region instanceof Region.Shape && region.bounds().equals(a.box(4, 0, 4, 10, 6, 10)),
                String.valueOf(region));
        d.activeBlock(GLASS);
        run.edit("Fill the sphere", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        Cells filled = run.exact("Fill writes exactly the sphere's cells",
                written(before, region, Fixtures.Build.state(GLASS)));
        run.picture("sphere-filled", "A glass ball between the two stone-brick posts; the selection outline hugs it");
        run.edit("Erase the sphere", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.erase"));
        run.exact("Erase empties exactly the sphere's cells", written(filled, region, Blocks.AIR.getDefaultState()));
        run.undo("undo the Erase");
        run.exact("undo brings the glass ball back", filled);

        // Hollow: the ball's inside goes, its shell stays.
        run.edit("Hollow the sphere", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.hollow"));
        Cells hollow = run.server(area);
        List<Cells.Change> hollowed = filled.diff(hollow);
        boolean inside = hollowed.stream().allMatch(c -> region.contains(c.x(), c.y(), c.z()) && c.after().isAir());
        run.check("Hollow empties the ball's inside and keeps its shell", !hollowed.isEmpty() && inside
                && hollowed.size() < region.cellCount() && hollow.at(a.x(7), a.y(3), a.z(7)).isAir()
                && !hollow.at(a.x(7), a.y(0), a.z(7)).isAir(), hollowed.size() + " of " + region.cellCount()
                + " cells emptied; " + Cells.summary(hollowed, 2));
        run.undo("undo the Hollow");
        run.undo("undo the Fill");
        run.exact("undos restore the posts", before);

        // The other shapes, each dragged between the same posts and filled.
        for (String shape : List.of("CYLINDER", "CONE", "PYRAMID")) {
            d.setting(ToolId.SELECT, "shape", shape);
            // Ctrl+D first: a press on the last selection's corner would grab its handle instead.
            d.action(KeyAction.DESELECT);
            d.aim(a.x(4), a.y(0), a.z(4), Face.UP);
            d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
            d.dragTo(a.x(10) + 0.5, a.y(7), a.z(10) + 0.5, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
            Region shaped = selection(run, "Shape");
            String name = shape.toLowerCase(java.util.Locale.ROOT);
            run.check("the drag makes a " + name + " in the posts' box", shaped instanceof Region.Shape s
                    && s.kind().name().equals(shape) && shaped.bounds().equals(region.bounds()), String.valueOf(shaped));
            run.edit("Fill the " + name, () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
            run.exact("Fill writes exactly the " + name + "'s cells", written(before, shaped,
                    Fixtures.Build.state(GLASS)));
            if (shape.equals("CONE")) {
                run.picture("cone-filled", "A glass cone standing between the posts, tip up");
            }
            run.undo("undo the " + name);
            run.exact("undo restores the posts", before);
        }
        d.setting(ToolId.SELECT, "shape", "BOX");
        d.activeBlock("minecraft:stone");
    }

    private static void magic(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(18, -1, 2, 32, 3, 12);
        Cells before = run.server(area);
        d.view(a.x(25) + 0.5, a.y(10), a.z(18) + 0.5, a.x(25) + 0.5, a.y(0), a.z(6) + 0.5);
        d.selectTool(ToolId.SELECT);
        d.setting(ToolId.SELECT, "mode", "MAGIC");
        d.aim(a.x(22), a.y(0), a.z(6), Face.UP);
        d.click(0);
        boolean done = d.until(10_000, () -> d.ctx().selectionRegion().map(r -> r.cellCount() > 0).orElse(false));
        run.require("magic select selects something", done, d.toastNote());
        d.millis(500);
        Region region = selection(run, "Cells");
        Box patch = a.box(20, 0, 4, 25, 0, 9);
        boolean exact = region.cellCount() == patch.volume() && region.bounds().equals(patch);
        run.check("magic select takes exactly the connected grass patch", exact,
                region.cellCount() + " cells in " + region.bounds() + ", the patch is " + patch.volume() + " in " + patch);
        run.picture("magic", "The 6x6 grass patch outlined as the selection; the small patch beside it not");
        d.activeBlock(GLASS);
        run.edit("Fill the magic selection", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        run.exact("Fill writes exactly the magic selection", written(before, region, Fixtures.Build.state(GLASS)));
        run.undo("undo the Fill");
        run.exact("undo restores the grass", before);
        d.setting(ToolId.SELECT, "mode", "BOX");
        d.activeBlock("minecraft:stone");
    }

    private static void brush(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -3, 14, 20, 4, 30);
        Cells before = run.server(area);
        d.view(a.x(10) + 0.5, a.y(10), a.z(34) + 0.5, a.x(10) + 0.5, a.y(0), a.z(22) + 0.5);
        d.selectTool(ToolId.SELECT);
        d.setting(ToolId.SELECT, "mode", "BRUSH");
        d.setting(ToolId.SELECT, "brush_radius", 2);
        d.aim(a.x(5), a.y(-1), a.z(20), Face.UP);
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.dragTo(a.x(15) + 0.5, a.y(0), a.z(20) + 0.5, 15, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.frames(5);
        Region region = selection(run, "Cells");
        long airCells = countIn(before, region, BlockState::isAir);
        run.check("the brush selection holds only solid blocks (Solid only)", region.cellCount() > 0 && airCells == 0,
                region.cellCount() + " cells, " + airCells + " of them air, in " + region.bounds());
        run.picture("brush-selection", "A band of floor cells painted into the selection along the drag");
        d.activeBlock(GLASS);
        run.edit("Fill the brush selection", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        run.exact("Fill writes exactly the brush selection", written(before, region, Fixtures.Build.state(GLASS)));
        run.undo("undo the Fill");
        run.exact("undo restores the floor", before);
        d.setting(ToolId.SELECT, "mode", "BOX");
        d.activeBlock("minecraft:stone");
    }

    private static void lasso(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(18, -1, 14, 39, 4, 39);
        Cells before = run.server(area);
        d.view(a.x(25) + 0.5, a.y(14), a.z(38) + 0.5, a.x(25) + 0.5, a.y(0), a.z(24) + 0.5);
        d.selectTool(ToolId.SELECT);
        d.setting(ToolId.SELECT, "mode", "LASSO");
        d.setting(ToolId.SELECT, "lasso_height", 2);
        d.aim(a.x(21), a.y(-1), a.z(18), Face.UP);
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        double top = a.y(0);
        d.dragTo(a.x(28) + 0.5, top, a.z(18) + 0.5, 6, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.dragTo(a.x(28) + 0.5, top, a.z(25) + 0.5, 6, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.dragTo(a.x(24) + 0.5, top, a.z(27) + 0.5, 6, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.dragTo(a.x(21) + 0.5, top, a.z(25) + 0.5, 6, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean done = d.until(10_000, () -> d.ctx().selectionRegion().map(r -> r.cellCount() > 0).orElse(false));
        run.require("the lasso selects something", done, d.toastNote());
        Region region = selection(run, "Cells");
        run.check("the lasso's cells are the floor layer and one above",
                region.bounds().min().y() == a.y(-1) && region.bounds().max().y() == a.y(0),
                String.valueOf(region.bounds()));
        run.picture("lasso", "A five-sided loop of floor selected (two layers high)");
        d.activeBlock(GLASS);
        run.edit("Fill the lasso selection", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        Cells filled = run.exact("Fill writes exactly the lasso selection",
                written(before, region, Fixtures.Build.state(GLASS)));

        // Move the lasso's cells 8 blocks further (a drop at the pointer, Enter).
        d.clickButton(EditorWindows.SELECTION, "sculptory.selection.move");
        PlaceTool place = d.onClient(() -> d.controller().placeTool().orElseThrow());
        run.require("Move starts the Place tool", d.until(10_000, () -> d.controller().activePlaceTool().isPresent()
                && place.placement().isPresent()), d.toastNote());
        BlockPos pivot = d.onClient(() -> place.placement().orElseThrow().pivotWorld());
        d.overlook(pivot.x() + 8, a.y(-1), pivot.z() + 8);
        d.aim(pivot.x() + 8, a.y(-1), pivot.z() + 8, Face.UP);
        d.frames(10);
        d.click(0);
        BlockPos offset = d.onClient(() -> place.placement().map(Placement::offset).orElse(null));
        run.require("the move's ghost dropped", offset != null && !offset.equals(BlockPos.ORIGIN), String.valueOf(offset));
        run.edit("Move the lasso selection", () -> d.action(KeyAction.COMMIT));
        Cells.Editor expected = filled.edit();
        BlockState air = Blocks.AIR.getDefaultState();
        forEachCell(region, (x, y, z) -> expected.set(x, y, z, air));
        forEachCell(region, (x, y, z) -> expected.set(x + offset.x(), y + offset.y(), z + offset.z(), filled.at(x, y, z)));
        run.exact("Move takes exactly the lasso's cells " + offset.x() + " " + offset.y() + " " + offset.z()
                + " and leaves air", expected.done());
        run.undo("undo the Move");
        run.undo("undo the Fill");
        run.exact("two undos restore the floor", before);
        d.setting(ToolId.SELECT, "mode", "BOX");
        d.activeBlock("minecraft:stone");
    }

    // ---- Helpers ----

    /** The client's selection now, required to be of the named kind ("Shape", "Cells"). */
    private static Region selection(CheckRun run, String kind) {
        Optional<Region> region = run.driver().onClient(() -> run.driver().ctx().selectionRegion());
        run.require("there is a " + kind + " selection", region.isPresent()
                && region.get().getClass().getSimpleName().equals(kind), region.map(String::valueOf).orElse("nothing"));
        return region.get();
    }

    /** {@code before} with every cell of {@code region} set to {@code state}. */
    static Cells written(Cells before, Region region, BlockState state) {
        Cells.Editor expected = before.edit();
        forEachCell(region, (x, y, z) -> expected.set(x, y, z, state));
        return expected.done();
    }

    private static long countIn(Cells cells, Region region, java.util.function.Predicate<BlockState> which) {
        long[] count = {0};
        forEachCell(region, (x, y, z) -> {
            if (which.test(cells.at(x, y, z))) {
                count[0]++;
            }
        });
        return count[0];
    }

    interface CellVisitor {
        void visit(int x, int y, int z);
    }

    static void forEachCell(Region region, CellVisitor visitor) {
        Box b = region.bounds();
        for (int y = b.min().y(); y <= b.max().y(); y++) {
            for (int z = b.min().z(); z <= b.max().z(); z++) {
                for (int x = b.min().x(); x <= b.max().x(); x++) {
                    if (region.contains(x, y, z)) {
                        visitor.visit(x, y, z);
                    }
                }
            }
        }
    }
}
