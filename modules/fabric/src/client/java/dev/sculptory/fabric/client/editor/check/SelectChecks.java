package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.BlockState;
import org.lwjgl.glfw.GLFW;

/**
 * The Select tool and its operations through the real client: a box dragged on the ground, Fill from the Selection
 * window, undo and redo.
 */
final class SelectChecks {
    static final String AREA = "select";

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(AREA, build -> {
            })),
            List.of(Scenario.of("select-fill", AREA,
                    "Drag a box on the ground with Select, Fill it with planks from the Selection window, undo, redo",
                    SelectChecks::boxFill)));

    private SelectChecks() {}

    /** A box dragged between two floor blocks, filled with planks; undo and redo are exact. */
    static void boxFill(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        d.view(a.x(20) + 0.5, a.y(10), a.z(34) + 0.5, a.x(18) + 0.5, a.y(0), a.z(18) + 0.5);
        Box expected = a.box(15, -1, 15, 20, -1, 19);
        Box around = a.box(10, -2, 10, 25, 3, 25);
        Cells before = run.server(around);
        d.selectTool(ToolId.SELECT);
        d.aim(a.x(15), a.y(-1), a.z(15), Face.UP);
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.dragTo(a.x(20) + 0.5, a.y(0), a.z(19) + 0.5, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        Optional<Box> selected = d.onClient(() -> d.ctx().selection());
        run.require("the drag selects the box between the two blocks", selected.equals(Optional.of(expected)),
                "selected " + selected.map(Box::toString).orElse("nothing") + ", expected " + expected);
        d.activeBlock("minecraft:oak_planks");
        run.edit("Fill", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        Cells after = run.server(around);
        List<Cells.Change> changes = before.diff(after);
        boolean exact = changes.size() == expected.volume() && changes.stream().allMatch(change ->
                expected.contains(change.x(), change.y(), change.z()) && isPlanks(change.after()));
        run.check("Fill writes exactly the selected cells", exact, Cells.summary(changes, 4));
        run.clientMatches("after Fill", after);
        run.picture("filled", "A 6x5 patch of oak planks in the stone floor, with the white selection outline around it");
        run.undoRedo("Fill", before, after);
    }

    private static boolean isPlanks(BlockState state) {
        return Cells.describe(state).equals("minecraft:oak_planks");
    }
}
