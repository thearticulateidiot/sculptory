package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.Box;
import dev.sculptory.core.schem.SchematicFormat;
import dev.sculptory.fabric.client.builder.BuilderClient;
import dev.sculptory.fabric.client.builder.RingScreen;
import dev.sculptory.fabric.client.editor.ExitReason;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.tool.WorldCursor.Face;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.place.Placement;
import dev.sculptory.fabric.client.editor.tools.tinker.TinkerTool;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.config.FolderMigration;
import dev.sculptory.protocol.v2.BuilderPower;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.block.BlockState;
import org.lwjgl.glfw.GLFW;

/**
 * More features through the real client: the key sheet (F1), the Tinker tool (slot 14, ]) with its
 * label on a stair and a Scroll that changes a property, the upside-down flip (V) of a paste ghost, builder mode's
 * ring of powers (G held outside the editor), history entries named after their tool, and the clipboard exported as
 * {@code .litematic} and {@code .nbt}. Not covered here: mix patterns (Palette Paint/Fill/Shape), builder mode's
 * powers themselves (GameTests cover them).
 */
final class FeatureChecks {
    static final String AREA = "features";
    private static final String PLANKS = "minecraft:oak_planks";
    /**
     * A bottom stair facing south: its half tells an upside-down flip from none, and its raised half is on the south,
     * the side the camera looks from, so a ray aimed at its top centre meets the top face (facing north, the ray met
     * the step's south side first and the aim failed).
     */
    private static final String STAIR = "minecraft:oak_stairs[facing=south]";
    /** The bottom stair as the flip should turn it (its half only). */
    private static final String STAIR_TOP = "minecraft:oak_stairs[facing=south,half=top]";
    private static final Box SOURCE = Box.of(new dev.sculptory.core.BlockPos(8, 0, 8),
            new dev.sculptory.core.BlockPos(10, 0, 10));

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(AREA, b -> {
                // Tinker's stair, on the floor.
                b.set(20, 0, 20, STAIR);
                // A 3x3 of bottom stairs to copy, paste flipped and export.
                b.fill(SOURCE.min().x(), SOURCE.min().y(), SOURCE.min().z(), SOURCE.max().x(), SOURCE.max().y(),
                        SOURCE.max().z(), STAIR);
            })),
            List.of(Scenario.visual("keys-sheet", AREA, "F1 opens the key sheet (up to three columns), F1 closes it",
                            FeatureChecks::keySheet),
                    Scenario.visual("tinker", AREA, "Tinker (slot 14, ]): its label on a stair; Scroll changes a"
                            + " property on the server; undo and redo", FeatureChecks::tinker),
                    Scenario.visual("paste-flip", AREA, "V flips a paste ghost upside down: twice is as it was, once"
                            + " pastes top-half stairs", FeatureChecks::pasteFlip),
                    Scenario.visual("builder-ring", AREA, "Outside the editor, holding G shows the ring of powers;"
                            + " releasing it closes the ring", FeatureChecks::builderRing),
                    Scenario.visual("history-names", AREA, "History entries carry their tool's name (Fill, Raise);"
                            + " the History window", FeatureChecks::historyNames),
                    Scenario.of("export-formats", AREA, "Export the clipboard as .litematic and .nbt into"
                            + " sculptory/exports", FeatureChecks::exportFormats)));

    private FeatureChecks() {}

    private static void keySheet(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        d.overlook(a.x(20), a.y(-1), a.z(20));
        d.key(GLFW.GLFW_KEY_F1, 0);
        run.check("F1 opens the key sheet", d.until(5_000, () -> d.ui().isHelpOpen()), d.toastNote());
        run.picture("key-sheet", "The key sheet over the editor: the keys listed in up to three columns, the filter"
                + " field at the top, nothing cut off");
        d.key(GLFW.GLFW_KEY_F1, 0);
        run.check("F1 again closes the key sheet", d.until(5_000, () -> !d.ui().isHelpOpen()), "");
    }

    private static void tinker(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box cell = a.box(20, 0, 20, 20, 0, 20);
        Cells before = run.server(cell);
        d.overlook(a.x(20), a.y(0), a.z(20));
        d.action(KeyAction.toolSlot(14));
        d.frames(2);
        run.require("] (palette slot 14) selects Tinker", d.onClient(() -> d.ctx().tools().isActive(ToolId.TINKER)),
                "Tinker is not the active tool" + d.toastNote());
        TinkerTool tool = (TinkerTool) d.onClient(() -> d.ctx().tools().get(ToolId.TINKER).orElseThrow());
        d.aim(a.x(20), a.y(0), a.z(20), Face.UP);
        d.frames(5);
        String label = d.onClient(() -> tool.controller().label());
        run.check("pointing at the stair shows its name and a property", label.toLowerCase(Locale.ROOT)
                .contains("stairs") && label.contains(":"), "label \"" + label + "\"");
        run.picture("tinker-label", "Tinker: the orange outline on the stair and the label beside the pointer,"
                + " \"" + label + "\"");

        // Scroll: the shown property takes its next value, on the server, as one history step.
        run.edit("Tinker scroll", () -> d.scroll(1, 0));
        Cells scrolled = run.server(cell);
        run.check("Scroll over the stair changes the shown property", !before.same(scrolled),
                "before " + Cells.describe(before.at(a.x(20), a.y(0), a.z(20))) + ", after "
                        + Cells.describe(scrolled.at(a.x(20), a.y(0), a.z(20))) + "; label was \"" + label + "\"");
        run.clientMatches("Tinker scroll", scrolled);
        String after = d.onClient(() -> tool.controller().label());
        run.note("Tinker: \"" + label + "\" -> Scroll -> \"" + after + "\"");
        run.undoRedo("Tinker scroll", before, scrolled);
    }

    private static void pasteFlip(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(0, -1, 0, 39, 6, 39);
        Cells before = run.server(area);
        Box source = a.box(SOURCE.min().x(), SOURCE.min().y(), SOURCE.min().z(), SOURCE.max().x(), SOURCE.max().y(),
                SOURCE.max().z());
        d.selectTool(ToolId.SELECT);
        selectStairs(run, source);
        PlaceChecks.copy(run);
        BlockState bottom = Fixtures.Build.state(STAIR);
        BlockState top = Fixtures.Build.state(STAIR_TOP);

        // V twice: as it was (whether or not the flip stays with the clipboard, this leaves it as found).
        PlaceTool place = paste(run, a);
        d.action(KeyAction.FLIP_UPSIDE_DOWN);
        d.frames(10);
        d.action(KeyAction.FLIP_UPSIDE_DOWN);
        d.frames(10);
        run.check("V twice keeps the placement", d.onClient(() -> place.placement().isPresent()), d.toastNote());
        Box twice = drop(run, place, "V twice");
        run.exact("V twice pastes the stairs as copied (bottom halves)", filled(before, twice, bottom));
        run.undo("undo the paste");
        run.exact("undo takes the paste back", before);

        // V once: the ghost hangs upside down, and the paste is top-half stairs.
        PlaceTool flipped = paste(run, a);
        run.picture("ghost", "The 3x3 ghost of bottom stairs at the pointer, the right way up");
        d.action(KeyAction.FLIP_UPSIDE_DOWN);
        d.frames(10);
        run.check("V keeps the placement", d.onClient(() -> flipped.placement().isPresent()), d.toastNote());
        run.picture("ghost-flipped", "The ghost after V: the stairs upside down (their steps hanging), the flip toast");
        Box once = drop(run, flipped, "V once");
        run.exact("the flipped paste places top-half stairs still facing south", filled(before, once, top));
        run.picture("pasted-flipped", "The 3x3 of top-half stairs pasted on the floor");
        run.undo("undo the flipped paste");
        run.exact("undo takes the flipped paste back", before);
    }

    /**
     * Selects the 3x3 of stairs by dragging across their tops: aimed at the middle of each stair's raised (south)
     * half, since its top centre lies on the step's edge, where the drag's end fell one row short.
     */
    private static void selectStairs(CheckRun run, Box box) {
        CheckDriver d = run.driver();
        d.overlook((box.min().x() + box.max().x()) / 2.0, box.max().y(), (box.min().z() + box.max().z()) / 2.0);
        d.pointAt(box.min().x() + 0.5, box.min().y() + 1, box.min().z() + 0.75);
        d.frames(3);
        WorldCursor cursor = d.cursor();
        run.require("the pointer is on the first stair's top", !cursor.missed()
                && cursor.pos().equals(box.min()) && cursor.face() == Face.UP,
                cursor.missed() ? "nothing" : cursor.pos() + " " + cursor.face());
        d.press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        d.dragTo(box.max().x() + 0.5, box.max().y() + 1, box.max().z() + 0.75, 8, GLFW.GLFW_MOUSE_BUTTON_LEFT);
        d.release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        Optional<Box> selected = d.onClient(() -> d.ctx().selection());
        run.require("the drag across the stairs' tops selects " + box, selected.equals(Optional.of(box)),
                "selected " + selected.map(Box::toString).orElse("nothing"));
    }

    /** Ctrl+V and a ghost aimed at the floor west of the source; the Place tool. */
    private static PlaceTool paste(CheckRun run, Area a) {
        CheckDriver d = run.driver();
        // Close and low, so the stairs' halves show in the ghost pictures.
        d.view(a.x(16) + 0.5, a.y(4), a.z(21) + 0.5, a.x(16) + 0.5, a.y(0), a.z(14) + 0.5);
        d.action(KeyAction.PASTE);
        PlaceTool place = d.onClient(() -> d.controller().placeTool().orElseThrow());
        boolean ready = d.until(20_000, () -> d.controller().activePlaceTool().isPresent()
                && place.placement().isPresent());
        run.require("the Place tool has the paste", ready, d.toastNote());
        d.aim(a.x(16), a.y(-1), a.z(14), Face.UP);
        d.frames(20);
        return place;
    }

    /** Clicks the ghost down and commits; the box it was placed in. */
    private static Box drop(CheckRun run, PlaceTool place, String what) {
        CheckDriver d = run.driver();
        d.click(0);
        Box target = d.onClient(() -> place.placement().map(Placement::targetBox).orElse(null));
        run.require(what + ": the ghost dropped where it was clicked", target != null, "no placement");
        run.edit(what + ": paste", () -> d.action(KeyAction.COMMIT));
        run.check(what + ": the target is one layer of 3x3", target.volume() == 9
                && target.min().y() == target.max().y(), target.toString());
        return target;
    }

    private static Cells filled(Cells before, Box box, BlockState state) {
        Cells.Editor expected = before.edit();
        for (int x = box.min().x(); x <= box.max().x(); x++) {
            for (int y = box.min().y(); y <= box.max().y(); y++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    expected.set(x, y, z, state);
                }
            }
        }
        return expected.done();
    }

    private static void builderRing(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        d.overlook(a.x(20), a.y(-1), a.z(20));
        BuilderClient builder = BuilderClient.instance()
                .orElseThrow(() -> new CheckDriver.Failed("builder mode is not initialised"));
        d.onClient(() -> {
            d.editor().mode().exit(ExitReason.TOGGLED);
            d.client().setScreen(null);
        });
        run.require("the editor is closed", d.until(5_000, () -> !d.editor().mode().isEditing()
                && d.client().currentScreen == null), "");
        List<String> unavailable = d.onClient(() -> java.util.Arrays.stream(BuilderPower.values())
                .filter(power -> !builder.powers().available(power))
                .map(power -> power.name() + " (" + builder.powers().unavailableKey(power) + ")").toList());
        run.check("every power is available (Tinker's controller is wired)", unavailable.isEmpty(),
                "unavailable: " + unavailable);
        int mark = d.toastMark();
        try {
            d.onClient(() -> builder.pinRingKey(true));
            d.keyDown(GLFW.GLFW_KEY_G);
            boolean ring = d.until(5_000, () -> d.client().currentScreen instanceof RingScreen);
            run.check("holding G outside the editor opens the ring of powers", ring, "screen: "
                    + d.onClient(() -> String.valueOf(d.client().currentScreen)) + "; on screen: " + d.shownMessages()
                    + d.toastNote());
            d.frames(10);
            run.picture("ring", "Builder mode's ring of powers over the world, no editor UI: the powers around the"
                    + " centre, the pointer's power lit if any");
        } finally {
            d.onClient(() -> builder.pinRingKey(false));
            d.keyUp(GLFW.GLFW_KEY_G);
        }
        run.check("releasing G closes the ring", d.until(5_000, () -> !(d.client().currentScreen instanceof RingScreen)),
                "");
        List<String> shown = d.shownMessages();
        run.check("no refusal was shown", shown.stream().noneMatch(s -> s.toLowerCase(Locale.ROOT).contains("builder")
                        || s.toLowerCase(Locale.ROOT).contains("permission")) && d.toastsSince(mark).isEmpty(),
                "on screen: " + shown + d.toastNote());
        // The release toggled the power under the pointer (a tap switches the last one): no power stays on for the
        // rest of the check, and its action-bar text is cleared so it doesn't linger into the next picture.
        d.onClient(() -> {
            builder.powers().clear();
            d.client().inGameHud.setOverlayMessage(net.minecraft.text.Text.empty(), false);
        });
        d.frames(2);
    }

    private static void historyNames(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box area = a.box(24, -1, 24, 36, 6, 36);
        Cells before = run.server(area);
        d.selectTool(ToolId.SELECT);
        Box floor = a.box(30, -1, 30, 32, -1, 32);
        PlaceChecks.selectTopFaces(run, floor);
        d.activeBlock(PLANKS);
        run.edit("Fill", () -> d.clickButton(EditorWindows.SELECTION, "sculptory.op.fill"));
        Cells filled = run.server(area);
        run.check("the Fill wrote planks", filled.at(a.x(31), a.y(-1), a.z(31)).isOf(net.minecraft.block.Blocks.OAK_PLANKS),
                Cells.describe(filled.at(a.x(31), a.y(-1), a.z(31))));
        d.onClient(() -> d.ctx().setSelection(null));
        d.selectTool(ToolId.RAISE);
        d.setting(ToolId.RAISE, "radius", 2);
        d.setting(ToolId.RAISE, "mode", "SURFACE");
        d.overlook(a.x(26), a.y(-1), a.z(26));
        Cells raised = BrushChecks.stroke(run, area, a.x(26), a.y(-1), a.z(26), Face.UP, 300, "Raise");
        run.check("Raise on the floor adds blocks", !filled.same(raised), "");

        List<String> labels = d.onClient(() -> d.session().orElseThrow().history().undoLabels());
        run.check("the history names the Raise as the newest entry", labels.size() >= 2
                && labels.get(0).toLowerCase(Locale.ROOT).contains("raise"), "undo labels (newest first): " + labels);
        run.check("the history names the Fill under it", labels.size() >= 2
                && labels.get(1).toLowerCase(Locale.ROOT).contains("fill"), "undo labels (newest first): " + labels);
        d.openWindow(EditorWindows.HISTORY);
        run.picture("history-window", "The History window: the newest entries named Raise and Fill (with their block"
                + " counts), Undo/Redo buttons");
        run.undo("undo the Raise");
        run.undo("undo the Fill");
        run.exact("two undos restore the area", before);
    }

    private static void exportFormats(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Box source = a.box(SOURCE.min().x(), SOURCE.min().y(), SOURCE.min().z(), SOURCE.max().x(), SOURCE.max().y(),
                SOURCE.max().z());
        d.selectTool(ToolId.SELECT);
        selectStairs(run, source);
        PlaceChecks.copy(run);
        Path exports = FolderMigration.gameDir().resolve("exports");
        for (SchematicFormat format : List.of(SchematicFormat.LITEMATIC, SchematicFormat.STRUCTURE)) {
            String name = "check-export-" + format.name().toLowerCase(Locale.ROOT);
            Path file = exports.resolve(name + format.extension());
            try {
                Files.deleteIfExists(file);
                int mark = d.toastMark();
                d.onClient(() -> d.controller().clipboard().orElseThrow().exportClipboard(format, name));
                Optional<CheckDriver.Toast> exported = d.awaitToast(mark, "sculptory.notice.exported", 20_000);
                boolean written = d.until(5_000, () -> Files.exists(file));
                long size = written ? Files.size(file) : 0;
                run.check("Export as " + format.extension() + " writes the file and says so", exported.isPresent()
                        && written && size > 0, "file " + file + (written ? " (" + size + " bytes)" : " missing")
                        + "; " + exported.map(CheckDriver.Toast::text).orElse("no toast") + d.toastNote());
            } catch (IOException e) {
                run.check("Export as " + format.extension() + " writes the file and says so", false, e.toString());
            } finally {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // Left for the next run to replace.
                }
            }
        }
    }
}
