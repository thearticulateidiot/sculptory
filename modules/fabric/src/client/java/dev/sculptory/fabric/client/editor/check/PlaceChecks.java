package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.tools.brush.SymmetryCentre;
import dev.sculptory.fabric.client.editor.tools.place.Placement;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.BlockState;
import net.minecraft.util.BlockMirror;
import org.lwjgl.glfw.GLFW;

/**
 * Symmetry for Select and Place, Stack, and Paste into / Fill into, through the real client: the
 * symmetry centre set with M, a Fill mirrored with its stairs turned, one undo for both; a mirrored paste of a copied
 * structure (ghosts shown for every copy); Stack with Ctrl+Scroll copies; a mirrored Move of a mirrored pair; Paste
 * into existing blocks or air only, and the same for Fill.
 */
final class PlaceChecks {
    static final String SYM_FILL = "sym-fill";
    static final String SYM_PLACE = "sym-place";
    static final String STACK = "stack";
    static final String INTO = "into";
    /** The symmetry centre's block (relative x and z); cells mirror to {@code 2 * CENTRE - x}. */
    private static final int CENTRE = 20;
    private static final String STAIRS_EAST = "minecraft:oak_stairs[facing=east]";

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(SYM_FILL, b -> {
                    }),
                    new Fixtures.Fixture(SYM_PLACE, b -> {
                        structure(b, 8, 8);
                        // A structure and its mirror image about x = CENTRE + 0.5, for the mirrored Move.
                        structure(b, 8, 28);
                        mirrored(b, 8, 28);
                    }),
                    new Fixtures.Fixture(STACK, b -> structure(b, 8, 18)),
                    new Fixtures.Fixture(INTO, b -> {
                        b.fill(8, 0, 10, 12, 0, 14, "minecraft:oak_planks");
                        for (int x = 20; x <= 24; x++) {
                            for (int z = 10; z <= 14; z++) {
                                if ((x + z) % 2 == 1) {
                                    b.set(x, 0, z, "minecraft:stone_bricks");
                                }
                            }
                        }
                    })),
            List.of(Scenario.of("select-symmetry-fill", SYM_FILL,
                            "Select with Mirror east-west around a centre set with M: Fill fills the mirrored box too, stairs"
                                    + " turned; one undo; no centre says so",
                            PlaceChecks::symmetricFill),
                    Scenario.visual("place-symmetry-paste", SYM_PLACE,
                            "Copy a structure, paste it with Mirror east-west: ghosts for both copies, both placed exactly,"
                                    + " one undo",
                            PlaceChecks::symmetricPaste),
                    Scenario.of("place-symmetry-move", SYM_PLACE,
                            "Move one of a mirrored pair with Mirror east-west: both move, mirrored", PlaceChecks::symmetricMove),
                    Scenario.of("place-stack", STACK, "Stack a structure three times (Ctrl+Scroll) from the Selection window",
                            PlaceChecks::stack),
                    Scenario.of("place-paste-into", INTO,
                            "Paste into existing blocks only and into air only; Fill Into the same", PlaceChecks::pasteInto)));

    private PlaceChecks() {}

    /** A 3x1x3 structure at (x, 0, z): cobblestone, a gold block at its first corner, planks in the middle, stairs facing east at the corner beside it
     * (the drag that selects it runs corner to corner over full blocks). */
    private static void structure(Fixtures.Build b, int x, int z) {
        b.fill(x, 0, z, x + 2, 0, z + 2, "minecraft:cobblestone");
        b.set(x, 0, z, "minecraft:gold_block");
        b.set(x + 1, 0, z + 1, "minecraft:oak_planks");
        b.set(x + 2, 0, z, STAIRS_EAST);
    }

    /** The mirror image about x = CENTRE + 0.5 of {@link #structure} at (x, 0, z). */
    private static void mirrored(Fixtures.Build b, int x, int z) {
        for (int dx = 0; dx <= 2; dx++) {
            for (int dz = 0; dz <= 2; dz++) {
                BlockState state = b.world().getBlockState(b.pos(x + dx, 0, z + dz));
                b.set(2 * CENTRE - (x + dx), 0, z + dz, state.mirror(BlockMirror.FRONT_BACK));
            }
        }
    }

    // ---- Scenarios ----

    private static void symmetricFill(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 39, 3, 39);
        Cells before = run.server(area);
        d.view(a.x(20) + 0.5, a.y(12), a.z(34) + 0.5, a.x(20) + 0.5, a.y(0), a.z(17) + 0.5);
        d.selectTool(ToolId.SELECT);
        d.setting(ToolId.SELECT, "symmetry", "MIRROR_X");
        setCentre(run, a);
        Box box = a.box(23, 0, 15, 26, 0, 18);
        WaterChecks.selectLayerAbove(run, a, box);
        d.activeBlock(STAIRS_EAST);
        run.edit("mirrored Fill", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        Cells.Editor expected = before.edit();
        BlockState stairs = Fixtures.Build.state(STAIRS_EAST);
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                expected.set(x, box.min().y(), z, stairs);
                expected.set(2 * a.x(CENTRE) - x, box.min().y(), z, stairs.mirror(BlockMirror.FRONT_BACK));
            }
        }
        Cells filled = run.exact("the Fill fills the box and its mirror image, the mirrored stairs facing west",
                expected.done());
        run.picture("mirrored-fill", "Two 4x4 patches of oak stairs either side of the centre line: the right ones"
                + " facing east, the left ones west; the symmetry plane drawn");
        run.undo("one undo");
        run.exact("one undo takes back both copies", before);

        // With the mode on and no centre, an operation says to set it first. M on the centre clears it.
        int cleared = d.toastMark();
        pressCentreKey(run, a);
        run.check("M on the centre again clears it",
                d.awaitToast(cleared, "sculptory.notice.symmetry_centre_cleared", 3_000).isPresent(), d.toastNote());
        int mark = d.toastMark();
        d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill");
        Optional<CheckDriver.Toast> told = d.awaitToast(mark, "sculptory.notice.symmetry_centre_needed", 5_000);
        run.check("with no centre, Fill says \"Set the symmetry centre first (M)\"", told.isPresent(),
                told.map(CheckDriver.Toast::text).orElse("no such toast" + d.toastNote()));
        d.millis(500);
        run.exact("with no centre, Fill writes nothing", before);
        d.setting(ToolId.SELECT, "symmetry", "OFF");
        run.check("the mirrored Fill wrote " + 2 * box.volume() + " cells", before.diff(filled).size() == 2 * box.volume(),
                before.diff(filled).size() + " cells");
    }

    private static void symmetricPaste(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 39, 3, 25);
        Cells before = run.server(area);
        d.view(a.x(20) + 0.5, a.y(14), a.z(30) + 0.5, a.x(18) + 0.5, a.y(0), a.z(12) + 0.5);
        d.selectTool(ToolId.SELECT);
        Box source = a.box(8, 0, 8, 10, 0, 10);
        selectTopFaces(run, source);
        copy(run);
        setCentre(run, a);
        d.setting(ToolId.PLACE, "symmetry", "MIRROR_X");
        PlaceTool place = startPlacing(run, "paste");
        d.view(a.x(20) + 0.5, a.y(14), a.z(30) + 0.5, a.x(20) + 0.5, a.y(0), a.z(14) + 0.5);
        d.aim(a.x(14), a.y(-1), a.z(14), Face.UP);
        d.frames(20);
        run.picture("ghosts", "Two ghost copies of the 3x3 structure (gold corner, stairs): one at the pointer, one"
                + " mirrored across the centre line");
        d.click(0);
        Box target = d.onClient(() -> place.placement().map(Placement::targetBox).orElse(null));
        run.require("the paste's ghost dropped where it was clicked", target != null, "no placement");
        run.edit("mirrored paste", () -> d.action(KeyAction.COMMIT));
        Cells.Editor expected = before.edit();
        int dx = target.min().x() - source.min().x();
        int dz = target.min().z() - source.min().z();
        for (int x = source.min().x(); x <= source.max().x(); x++) {
            for (int z = source.min().z(); z <= source.max().z(); z++) {
                BlockState state = before.at(x, source.min().y(), z);
                expected.set(x + dx, target.min().y(), z + dz, state);
                expected.set(2 * a.x(CENTRE) - (x + dx), target.min().y(), z + dz, state.mirror(BlockMirror.FRONT_BACK));
            }
        }
        run.exact("the paste places the copy where the ghost was and its mirror image", expected.done());
        d.setting(ToolId.PLACE, "symmetry", "OFF");
        run.picture("pasted", "The structure pasted twice, mirrored: gold corners and stairs mirrored");
        run.undo("one undo");
        run.exact("one undo takes back both copies", before);
    }

    private static void symmetricMove(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 26, 39, 3, 39);
        Cells before = run.server(area);
        d.view(a.x(20) + 0.5, a.y(14), a.z(39) + 0.5, a.x(20) + 0.5, a.y(0), a.z(30) + 0.5);
        d.selectTool(ToolId.SELECT);
        Box source = a.box(8, 0, 28, 10, 0, 30);
        selectTopFaces(run, source);
        setCentre(run, a);
        d.setting(ToolId.PLACE, "symmetry", "MIRROR_X");
        PlaceTool place = startPlacing(run, "move");
        d.view(a.x(20) + 0.5, a.y(14), a.z(39) + 0.5, a.x(20) + 0.5, a.y(0), a.z(32) + 0.5);
        d.aim(a.x(13), a.y(-1), a.z(34), Face.UP);
        d.frames(10);
        d.click(0);
        Box target = d.onClient(() -> place.placement().map(Placement::targetBox).orElse(null));
        run.require("the move's ghost dropped where it was clicked", target != null, "no placement");
        run.edit("mirrored move", () -> d.action(KeyAction.COMMIT));
        Cells.Editor expected = before.edit();
        BlockState air = net.minecraft.block.Blocks.AIR.getDefaultState();
        int dx = target.min().x() - source.min().x();
        int dz = target.min().z() - source.min().z();
        for (int x = source.min().x(); x <= source.max().x(); x++) {
            for (int z = source.min().z(); z <= source.max().z(); z++) {
                expected.set(x, 0 + source.min().y(), z, air);
                expected.set(2 * a.x(CENTRE) - x, source.min().y(), z, air);
            }
        }
        for (int x = source.min().x(); x <= source.max().x(); x++) {
            for (int z = source.min().z(); z <= source.max().z(); z++) {
                BlockState state = before.at(x, source.min().y(), z);
                expected.set(x + dx, target.min().y(), z + dz, state);
                expected.set(2 * a.x(CENTRE) - (x + dx), target.min().y(), z + dz,
                        state.mirror(BlockMirror.FRONT_BACK));
            }
        }
        run.exact("the move takes both structures to the target and its mirror image", expected.done());
        d.setting(ToolId.PLACE, "symmetry", "OFF");
        run.picture("moved", "The mirrored pair moved: both structures now nearer the camera, still mirror images");
        run.undo("one undo");
        run.exact("one undo puts both back", before);
    }

    private static void stack(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 39, 3, 39);
        Cells before = run.server(area);
        d.view(a.x(9) + 0.5, a.y(16), a.z(34) + 0.5, a.x(16) + 0.5, a.y(0), a.z(19) + 0.5);
        d.selectTool(ToolId.SELECT);
        Box source = a.box(8, 0, 18, 10, 0, 20);
        selectTopFaces(run, source);
        PlaceTool place = startPlacing(run, "stack");
        d.overlook(a.x(16), a.y(-1), a.z(19));
        d.aim(a.x(16), a.y(-1), a.z(19), Face.UP);
        d.frames(5);
        d.click(0);
        d.scroll(1, GLFW.GLFW_MOD_CONTROL);
        d.scroll(1, GLFW.GLFW_MOD_CONTROL);
        List<Box> copies = d.onClient(() -> place.placement().map(p -> p.copies(16)).orElse(List.of()));
        run.require("Ctrl+Scroll twice makes three copies", copies.size() == 3, copies.size() + " copies");
        run.picture("stack-ghosts", "Three ghost copies of the structure in a row beside it");
        run.edit("Stack", () -> d.action(KeyAction.COMMIT));
        Cells.Editor expected = before.edit();
        for (Box copy : copies) {
            int dx = copy.min().x() - source.min().x();
            int dy = copy.min().y() - source.min().y();
            int dz = copy.min().z() - source.min().z();
            for (int x = source.min().x(); x <= source.max().x(); x++) {
                for (int z = source.min().z(); z <= source.max().z(); z++) {
                    expected.set(x + dx, source.min().y() + dy, z + dz, before.at(x, source.min().y(), z));
                }
            }
        }
        run.exact("Stack places the three copies the ghosts showed", expected.done());
        run.undo("undo the Stack");
        run.exact("undo takes the copies back", before);
    }

    private static void pasteInto(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 39, 3, 39);
        Box zone = a.box(20, 0, 10, 24, 0, 14);
        Cells before = run.server(area);
        d.view(a.x(17) + 0.5, a.y(14), a.z(28) + 0.5, a.x(17) + 0.5, a.y(0), a.z(12) + 0.5);
        d.selectTool(ToolId.SELECT);
        selectTopFaces(run, a.box(8, 0, 10, 12, 0, 14));
        copy(run);
        for (String into : List.of("EXISTING", "AIR")) {
            d.setting(ToolId.PLACE, "into", into);
            PlaceTool place = startPlacing(run, "paste");
            d.overhead(a.x(22), a.y(-1), a.z(12));
            d.aim(a.x(22), a.y(-1), a.z(12), Face.UP);
            d.frames(20);
            Box target = d.onClient(() -> place.placement().map(Placement::targetBox).orElse(null));
            run.require("the planks' ghost lies over the checkerboard (" + into + ")", zone.equals(target),
                    "ghost at " + target + ", checkerboard " + zone);
            run.picture("paste-into-" + into.toLowerCase(java.util.Locale.ROOT), into.equals("EXISTING")
                    ? "Paste into existing blocks: the ghost shows planks only on the stone-brick squares"
                    : "Paste into air: the ghost shows planks only on the empty squares");
            d.click(0);
            run.edit("paste into " + into, () -> d.action(KeyAction.COMMIT));
            run.exact("Paste into " + into + " writes only " + (into.equals("AIR") ? "the air cells" : "over blocks"),
                    intoExpected(before, zone, into, Fixtures.Build.state("minecraft:oak_planks")));
            run.undo("undo the paste into " + into);
            run.exact("undo restores the checkerboard", before);
        }
        d.setting(ToolId.PLACE, "into", "EVERYTHING");

        // Fill Into: the same choice for Fill, on the selected checkerboard.
        d.selectTool(ToolId.SELECT);
        WaterChecks.selectLayerAbove(run, a, zone);
        d.activeBlock("minecraft:glass");
        for (String into : List.of("EXISTING", "AIR")) {
            d.setting(ToolId.SELECT, "into", into);
            run.edit("Fill into " + into, () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
            run.exact("Fill Into " + into + " writes only " + (into.equals("AIR") ? "the air cells" : "over blocks"),
                    intoExpected(before, zone, into, Fixtures.Build.state("minecraft:glass")));
            run.undo("undo the Fill into " + into);
            run.exact("undo restores the checkerboard", before);
        }
        d.setting(ToolId.SELECT, "into", "EVERYTHING");
        d.activeBlock("minecraft:stone");
    }

    // ---- Helpers ----

    /** The world after writing {@code state} into {@code zone}'s cells that {@code into} allows. */
    private static Cells intoExpected(Cells before, Box zone, String into, BlockState state) {
        Cells.Editor expected = before.edit();
        for (int x = zone.min().x(); x <= zone.max().x(); x++) {
            for (int z = zone.min().z(); z <= zone.max().z(); z++) {
                boolean air = before.at(x, zone.min().y(), z).isAir();
                if (into.equals("AIR") == air) {
                    expected.set(x, zone.min().y(), z, state);
                }
            }
        }
        return expected.done();
    }

    /** Selects a one-high box by dragging across the top faces of its corner blocks. */
    static void selectTopFaces(CheckRun run, Box box) {
        CheckDriver d = run.driver();
        d.overlook((box.min().x() + box.max().x()) / 2.0, box.max().y(), (box.min().z() + box.max().z()) / 2.0);
        d.aim(box.min().x(), box.min().y(), box.min().z(), Face.UP);
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.dragTo(box.max().x() + 0.5, box.max().y() + 1, box.max().z() + 0.5, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        Optional<Box> selected = d.onClient(() -> d.ctx().selection());
        run.require("the drag across the top faces selects " + box, selected.equals(Optional.of(box)),
                "selected " + selected.map(Box::toString).orElse("nothing"));
    }

    /** Ctrl+C, waiting for the "Copied" toast. */
    static void copy(CheckRun run) {
        CheckDriver d = run.driver();
        int mark = d.toastMark();
        d.action(KeyAction.COPY);
        Optional<CheckDriver.Toast> copied = d.awaitToast(mark, "sculptory.notice.copied", 20_000);
        run.require("Ctrl+C copies the selection", copied.isPresent(), d.toastNote());
    }

    /**
     * Makes the floor block at the centre the symmetry centre with M (the active tool's key), unless it already is:
     * M on the centre again would clear it.
     */
    private static void setCentre(CheckRun run, Area a) {
        CheckDriver d = run.driver();
        int x2 = 2 * a.x(CENTRE) + 1;
        int z2 = 2 * a.z(20) + 1;
        boolean already = d.onClient(() -> {
            SymmetryCentre centre = d.controller().placeTool().orElseThrow().symmetryCentre();
            return centre.isSet() && centre.x2() == x2 && centre.z2() == z2;
        });
        if (!already) {
            pressCentreKey(run, a);
        }
    }

    /** M aimed at the floor block at the centre: sets the centre there, or clears it when it is there. */
    private static void pressCentreKey(CheckRun run, Area a) {
        CheckDriver d = run.driver();
        d.overlook(a.x(CENTRE), a.y(-1), a.z(20));
        d.aim(a.x(CENTRE), a.y(-1), a.z(20), Face.UP);
        d.action(KeyAction.SET_SYMMETRY_CENTRE);
    }

    /**
     * Starts a placement as the player does ({@code "paste"}: Ctrl+V; {@code "move"} or {@code "stack"}: the Selection
     * window's button) and waits until the Place tool has it.
     */
    private static PlaceTool startPlacing(CheckRun run, String how) {
        CheckDriver d = run.driver();
        switch (how) {
            case "paste" -> d.action(KeyAction.PASTE);
            case "move" -> d.clickButton(EditorWindows.SELECTION, "sculptory.selection.move");
            case "stack" -> d.clickButton(EditorWindows.SELECTION, "sculptory.selection.stack");
            default -> throw new IllegalArgumentException(how);
        }
        PlaceTool place = d.onClient(() -> d.controller().placeTool().orElseThrow());
        boolean ready = d.until(20_000, () -> d.controller().activePlaceTool().isPresent()
                && place.placement().isPresent());
        run.require("the Place tool has the placement (" + how + ")", ready, d.toastNote());
        return place;
    }
}
