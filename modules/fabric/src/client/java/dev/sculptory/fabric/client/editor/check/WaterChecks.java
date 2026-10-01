package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameRules;
import org.lwjgl.glfw.GLFW;

/**
 * The feedback round's water undo through the real client: a water Fill on grass that leaks once random ticks kill the
 * grass under it; undo takes back everything the water did and stays so, redo puts the water back as undo found it,
 * undo again. Then a Fill on stone that a player's block wakes, with a player's block where it flowed: undo keeps
 * both, and the Undo anyway toast's button takes them back too.
 */
final class WaterChecks {
    static final String FIELD = "water-grass";
    static final String STONE = "water-stone";

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(FIELD, b -> {
                        b.fill(0, -2, 0, 39, -2, 39, "minecraft:dirt");
                        b.fill(0, -1, 0, 39, -1, 39, "minecraft:grass_block");
                        lightAbove(b, 17, 17);
                    }),
                    new Fixtures.Fixture(STONE, b -> lightAbove(b, 17, 17))),
            List.of(Scenario.of("water-fill-undo", FIELD,
                            "A water Fill on grass leaks (random ticks); undo, redo, undo again are exact",
                            WaterChecks::fillUndo),
                    Scenario.of("water-undo-anyway", STONE,
                            "A woken water Fill with a player's blocks: undo keeps them, Undo anyway (the toast) takes"
                                    + " everything back",
                            WaterChecks::undoAnyway)));

    private WaterChecks() {}

    private static void fillUndo(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -3, 0, 39, 4, 39);
        Box fill = a.box(17, 0, 17, 22, 0, 22);
        Cells before = run.server(area);
        d.view(a.x(20) + 0.5, a.y(12), a.z(36) + 0.5, a.x(20) + 0.5, a.y(0), a.z(20) + 0.5);
        selectLayerAbove(run, a, fill);
        fillWater(run, area, before, fill);

        // Random ticks kill grass under the water (it turns to dirt), which wakes the water.
        Box under = a.box(17, -1, 17, 22, -1, 22);
        d.onServer(server -> {
            server.getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(400, server);
            return null;
        });
        boolean died = false;
        long deadline = System.currentTimeMillis() + 30_000;
        while (!died && System.currentTimeMillis() < deadline) {
            d.millis(250);
            died = run.server(under).count(state -> state.isOf(Blocks.DIRT)) > 0;
        }
        d.onServer(server -> {
            server.getGameRules().get(GameRules.RANDOM_TICK_SPEED).set(0, server);
            return null;
        });
        run.require("random ticks kill grass under the water", died, "no dirt under the water after 30 s");
        Cells flowed = settle(run, area);
        long outside = fluidOutside(flowed, fill);
        run.require("the water leaks out of the Fill", outside > 0, outside + " fluid cells outside the Fill");
        run.clientMatches("the water that leaked", flowed);
        run.picture("leaked", "The water square on the grass has run out in every direction");

        run.undo("undo the leaked Fill");
        run.exact("undo takes back the water and what it did (the grass included)", before);
        d.millis(3_000);
        run.exact("3 s after the undo the area is still as before", before);
        run.picture("undone", "Flat grass again: no water, no dirt patch");
        run.redo("redo the leaked Fill");
        run.exact("redo puts the water back as the undo found it", flowed);
        run.undo("undo again");
        run.exact("undo again restores the area exactly", before);
    }

    private static void undoAnyway(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -3, 0, 39, 4, 39);
        Box fill = a.box(17, 0, 17, 22, 0, 22);
        Cells before = run.server(area);
        d.view(a.x(20) + 0.5, a.y(12), a.z(36) + 0.5, a.x(20) + 0.5, a.y(0), a.z(20) + 0.5);
        selectLayerAbove(run, a, fill);
        fillWater(run, area, before, fill);

        // A player's block in the water wakes it (vanilla block updates).
        BlockPos inWater = new BlockPos(a.x(22), a.y(0), a.z(19));
        BlockPos flowedTo = new BlockPos(a.x(24), a.y(0), a.z(19));
        d.onServer(server -> server.getOverworld().setBlockState(inWater, Blocks.STONE.getDefaultState(),
                Block.NOTIFY_ALL));
        settle(run, area);
        BlockState reached = run.server(Box.of(a.at(24, 0, 19))).at(flowedTo.getX(), flowedTo.getY(), flowedTo.getZ());
        run.require("the water flowed out to where the player builds", Changes.fluid(reached),
                "24 0 19 is " + Cells.describe(reached));
        d.onServer(server -> server.getOverworld().setBlockState(flowedTo, Blocks.OAK_PLANKS.getDefaultState(),
                Block.NOTIFY_ALL));
        settle(run, area);

        run.undo("undo the woken Fill");
        Cells undone = run.server(area);
        List<Cells.Change> left = before.diff(undone);
        boolean onlyPlayers = left.size() == 2 && left.stream().allMatch(change ->
                change.x() == inWater.getX() && change.z() == inWater.getZ()
                        || change.x() == flowedTo.getX() && change.z() == flowedTo.getZ());
        run.check("undo keeps the player's two blocks and takes back all the water", onlyPlayers,
                Cells.summary(left, 4));
        run.clientMatches("after the undo", undone);
        boolean offered = d.until(5_000, () -> d.ui().historyOffer().isShown());
        run.require("the Undo anyway toast shows", offered, d.toastNote());
        run.picture("undo-anyway-toast", "The Undo anyway toast at the top right; the stone and the planks the"
                + " player placed are still on the dry floor");
        run.edit("Undo anyway", () -> d.clickNode(d.ui().historyOffer().button()));
        run.exact("Undo anyway takes the player's blocks back too", before);
    }

    // ---- Helpers ----

    /**
     * Light blocks (invisible, never aimed at) two above the corners of a 6x6 Fill: block light 11 or more on every
     * source, so the water can't freeze in the cold of the fixtures' height (random ticks turn still water under open sky
     * to ice where block light is under 10). What undo does with water that froze is a separate question.
     */
    static void lightAbove(Fixtures.Build b, int x0, int z0) {
        for (int[] at : new int[][] {{x0 + 1, z0 + 1}, {x0 + 4, z0 + 1}, {x0 + 1, z0 + 4}, {x0 + 4, z0 + 4}}) {
            b.set(at[0], 2, at[1], "minecraft:light[level=15]");
        }
    }

    /** Drags a Select box over the ground under {@code layer} (a one-high box), then nudges it up onto the layer. */
    static void selectLayerAbove(CheckRun run, Area a, Box layer) {
        CheckDriver d = run.driver();
        d.selectTool(ToolId.SELECT);
        d.aim(layer.min().x(), layer.min().y() - 1, layer.min().z(), Face.UP);
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.dragTo(layer.max().x() + 0.5, layer.min().y(), layer.max().z() + 0.5, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.action(KeyAction.NUDGE_UP);
        Optional<Box> selected = d.onClient(() -> d.ctx().selection());
        run.require("the box dragged on the ground and nudged up (PgUp) is the layer above it",
                selected.equals(Optional.of(layer)),
                "selected " + selected.map(Box::toString).orElse("nothing") + ", expected " + layer);
    }

    /** Fills the selection with water from the Selection window; checks it wrote exactly still water there. */
    static Cells fillWater(CheckRun run, Box area, Cells before, Box fill) {
        CheckDriver d = run.driver();
        d.activeBlock("minecraft:water");
        run.edit("water Fill", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        Cells filled = run.server(area);
        List<Cells.Change> changes = before.diff(filled);
        boolean exact = changes.size() == fill.volume() && changes.stream().allMatch(change ->
                fill.contains(change.x(), change.y(), change.z()) && change.after().isOf(Blocks.WATER)
                        && change.after().getFluidState().isStill());
        run.check("the water Fill writes still water into exactly the selection", exact, Cells.summary(changes, 4));
        run.clientMatches("after the water Fill", filled);
        d.activeBlock("minecraft:stone");
        return filled;
    }

    /** Waits until the area stops changing (a second without change, at most 40 s); its cells then. */
    static Cells settle(CheckRun run, Box area) {
        CheckDriver d = run.driver();
        Cells last = run.server(area);
        long deadline = System.currentTimeMillis() + 40_000;
        int still = 0;
        while (still < 4 && System.currentTimeMillis() < deadline) {
            d.millis(250);
            Cells now = run.server(area);
            still = now.same(last) ? still + 1 : 0;
            last = now;
        }
        return last;
    }

    /** Fluid cells (source or flowing) in the cells' box outside {@code fill}. */
    static long fluidOutside(Cells cells, Box fill) {
        Box box = cells.box();
        long count = 0;
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) {
                    if (!fill.contains(x, y, z) && Changes.fluid(cells.at(x, y, z))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
