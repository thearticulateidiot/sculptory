package dev.sculptory.fabric.client.editor.tutorial;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Each kind of step condition ticks on its own state or event and on nothing else: every other thing the probe can
 * report changes first (each followed by a frame), and only then the condition's own.
 */
class ConditionsTest {
    private final FakeProbe probe = new FakeProbe();

    /** A change to each thing the probe reports, keyed by what it touches. Two-part changes run a frame between. */
    private static Map<String, List<Consumer<FakeProbe>>> noise() {
        Map<String, List<Consumer<FakeProbe>>> noise = new LinkedHashMap<>();
        noise.put("activeTool", List.of(p -> p.activeTool = ToolId.RAISE));
        noise.put("settings", List.of(p -> set(p, SelectSettings.THICKNESS, 3)));
        noise.put("selection", List.of(p -> {
            p.selection = Selection.of(new Region.Cuboid(Box.of(new BlockPos(9, 9, 9))));
            p.selectionChanges++;
        }));
        noise.put("activeBlock", List.of(p -> p.activeBlock = BlockDescriptor.of(new NamespacedId("minecraft:dirt"))));
        noise.put("flySpeed", List.of(p -> p.flySpeed = 2));
        noise.put("looking", List.of(p -> p.looking = true, p -> p.looking = false));
        noise.put("eye", List.of(p -> p.eye = new double[] {0, 64, 0}, p -> p.eye = new double[] {1, 64, 1}));
        noise.put("uiSize", List.of(p -> p.uiSize = 75));
        noise.put("openMenu", List.of(p -> p.openMenu = CommandMenu.FILE.ordinal(), p -> p.openMenu = -1));
        noise.put("windows", List.of(p -> p.windows.add(EditorWindows.KEYS)));
        noise.put("windowsHidden", List.of(p -> p.windowsHidden = true, p -> p.windowsHidden = false));
        noise.put("keySheet", List.of(p -> p.keySheetOpen = true, p -> p.keySheetOpen = false));
        noise.put("commandSearch", List.of(p -> p.commandSearchOpen = true, p -> p.commandSearchOpen = false));
        noise.put("keyboardInWindow", List.of(p -> p.keyboardInWindow = true, p -> p.keyboardInWindow = false));
        noise.put("editorEntries", List.of(p -> p.editorEntries++));
        noise.put("pushes", List.of(p -> p.pushes++));
        noise.put("undos", List.of(p -> p.undos++));
        noise.put("redos", List.of(p -> p.redos++));
        noise.put("clipboard", List.of(p -> p.clipboard = new Object()));
        noise.put("library", List.of(p -> p.libraryChanges++));
        noise.put("presets", List.of(p -> p.presetsVersion++));
        noise.put("strokes", List.of(p -> p.stroke(ToolId.LOWER)));
        noise.put("symmetry", List.of(p -> p.symmetryCentreSet = true));
        noise.put("pathNodes", List.of(p -> p.pathNodes = 5));
        noise.put("scatterArea", List.of(p -> p.scatterAreaPainted = true));
        noise.put("scatterMix", List.of(p -> p.scatterMixSize = 3));
        noise.put("tinker", List.of(p -> p.tinkerAimsAtProperty = true));
        noise.put("gradientLine", List.of(p -> p.gradientLineSet = true));
        noise.put("placing", List.of(p -> p.placing = true));
        noise.put("flip", List.of(p -> p.placementUpsideDown = true));
        noise.put("builderPower", List.of(p -> p.builderPowerOn = true));
        noise.put("exportDialog", List.of(p -> p.exportDialogOpen = true));
        noise.put("exports", List.of(p -> p.exports++));
        return noise;
    }

    private static <T> void set(FakeProbe p, SettingDef<T> def, T value) {
        p.settings.put(ToolId.SELECT, p.settings.get(ToolId.SELECT).with(def, value));
    }

    /**
     * Starts {@code condition}, checks it is not met, runs every noise change but those in {@code own} (a frame after
     * each), checks it is still not met, then runs {@code ticks} (a frame after each) and checks it is met.
     */
    @SafeVarargs
    private void ticksOnlyOn(Condition condition, Set<String> own, Consumer<FakeProbe>... ticks) {
        ticksOnlyOn(p -> { }, condition, own, ticks);
    }

    /** As above, on a fresh probe set up by {@code setup} first. */
    @SafeVarargs
    private void ticksOnlyOn(Consumer<FakeProbe> setup, Condition condition, Set<String> own,
            Consumer<FakeProbe>... ticks) {
        FakeProbe probe = new FakeProbe();
        setup.accept(probe);
        Condition.Check check = condition.start(probe);
        assertFalse(check.met(probe), "not met at the start");
        noise().forEach((name, changes) -> {
            if (own.contains(name)) {
                return;
            }
            for (Consumer<FakeProbe> change : changes) {
                change.accept(probe);
                assertFalse(check.met(probe), "met after an unrelated change: " + name);
            }
        });
        boolean met = false;
        for (Consumer<FakeProbe> tick : ticks) {
            tick.accept(probe);
            met = check.met(probe);
        }
        assertTrue(met, "met after its own change");
    }

    @Test
    void toolAndSettingConditions() {
        ticksOnlyOn(Conditions.toolActive(ToolId.SELECT), Set.of("activeTool"), p -> p.activeTool = ToolId.SELECT);
        ticksOnlyOn(Conditions.setting(ToolId.SELECT, "shape", SelectSettings.SelectShape.SPHERE), Set.of(),
                p -> set(p, SelectSettings.SHAPE, SelectSettings.SelectShape.SPHERE));
        ticksOnlyOn(Conditions.settingNot(ToolId.SELECT, "mode", SelectSettings.Mode.BOX), Set.of(),
                p -> set(p, SelectSettings.MODE, SelectSettings.Mode.MAGIC));
        ticksOnlyOn(Conditions.settingChanged(ToolId.SELECT, "mode"), Set.of(),
                p -> set(p, SelectSettings.MODE, SelectSettings.Mode.LASSO));
        ticksOnlyOn(Conditions.anyToolSettingChanged("fill_with"), Set.of(),
                p -> set(p, SelectSettings.FILL_WITH, SelectSettings.FillWith.PALETTE));
        ticksOnlyOn(Conditions.anySettingsChanged(), Set.of("settings"),
                p -> set(p, SelectSettings.FILL_WITH, SelectSettings.FillWith.PALETTE));
    }

    @Test
    void aSettingResetTicksWhenAChangedSettingIsBackAtItsDefaultNotWhenOneIsChanged() {
        set(probe, SelectSettings.MODE, SelectSettings.Mode.MAGIC);
        Condition.Check check = Conditions.settingReset().start(probe);
        set(probe, SelectSettings.THICKNESS, 5);
        assertFalse(check.met(probe), "a setting moved away from its default");
        set(probe, SelectSettings.MODE, SelectSettings.Mode.LASSO);
        assertFalse(check.met(probe), "a changed setting changed again, not to its default");
        set(probe, SelectSettings.THICKNESS, SelectSettings.THICKNESS.defaultValue());
        assertFalse(check.met(probe), "a setting changed during the step and put back is not a reset of one before it");
        set(probe, SelectSettings.MODE, SelectSettings.Mode.BOX);
        assertTrue(check.met(probe), "↺ on the setting that differed");
    }

    @Test
    void aSettingKeyTheToolDoesNotHaveIsAMistakeFoundAsTheStepBegins() {
        assertThrows(IllegalArgumentException.class,
                () -> Conditions.setting(ToolId.SELECT, "no_such_key", 1).start(probe));
        assertThrows(IllegalArgumentException.class,
                () -> Conditions.settingChanged(ToolId.SELECT, "radius").start(probe));
        assertThrows(IllegalArgumentException.class, () -> Conditions.anyToolSettingChanged("radius").start(probe));
    }

    @Test
    void tinkerPatternsFlipBuilderModeAndExports() {
        ticksOnlyOn(Conditions.tinkerAimed(), Set.of("tinker"), p -> p.tinkerAimsAtProperty = true);
        ticksOnlyOn(Conditions.gradientLineSet(), Set.of("gradientLine"), p -> p.gradientLineSet = true);
        ticksOnlyOn(Conditions.placing(), Set.of("placing"), p -> p.placing = true);
        ticksOnlyOn(Conditions.placementUpsideDown(), Set.of("flip"), p -> p.placementUpsideDown = true);
        ticksOnlyOn(Conditions.builderPowerOn(), Set.of("builderPower"), p -> p.builderPowerOn = true);
        ticksOnlyOn(p -> p.builderPowerOn = true, Conditions.noBuilderPower(), Set.of("builderPower"),
                p -> p.builderPowerOn = false);
        ticksOnlyOn(Conditions.exportDialogOpen(), Set.of("exportDialog"), p -> p.exportDialogOpen = true);
        ticksOnlyOn(Conditions.exported(), Set.of("exports"), p -> p.exports++);
        // The builder-mode steps: back in the editor with a power on; a placement made and undone out there.
        ticksOnlyOn(Conditions.all(Conditions.reentered(), Conditions.builderPowerOn()),
                Set.of("editorEntries", "builderPower"), p -> p.builderPowerOn = true, p -> p.editorEntries++);
        ticksOnlyOn(Conditions.all(Conditions.edited(), Conditions.undone()), Set.of("pushes", "undos"),
                p -> p.pushes++, p -> p.undos++);
    }

    @Test
    void editsAndStrokes() {
        ticksOnlyOn(Conditions.edited(), Set.of("pushes"), p -> p.pushes++);
        ticksOnlyOn(Conditions.undone(), Set.of("undos"), p -> p.undos++);
        ticksOnlyOn(Conditions.redone(), Set.of("redos"), p -> p.redos++);
        ticksOnlyOn(Conditions.strokeEnded(ToolId.RAISE), Set.of(), p -> p.stroke(ToolId.RAISE));
    }

    @Test
    void selectionConditions() {
        Selection box = Selection.of(new Region.Cuboid(Box.of(new BlockPos(0, 0, 0))));
        ticksOnlyOn(Conditions.selectionChanged(), Set.of("selection"), p -> p.selectionChanges++);
        ticksOnlyOn(Conditions.hasSelection(), Set.of("selection"), p -> p.selection = box);
        ticksOnlyOn(Conditions.boxSelected(), Set.of("selection"), p -> {
            p.selection = box;
            p.selectionChanges++;
        });

        ticksOnlyOn(p -> p.selection = box, Conditions.noSelection(), Set.of("selection"), p -> p.selection = null);
    }

    @Test
    void aBoxDoesNotTickABlocksSelectionStepAndAnUnchangedBoxDoesNotTickABoxStep() {
        probe.selection = Selection.of(new Region.Cuboid(Box.of(new BlockPos(0, 0, 0))));
        Condition.Check box = Conditions.boxSelected().start(probe);
        Condition.Check blocks = Conditions.blocksSelected().start(probe);
        Condition.Check shape = Conditions.shapeSelected().start(probe);
        assertFalse(box.met(probe), "the box was there before the step");
        probe.selectionChanges++;
        probe.selection = Selection.of(new Region.Cuboid(Box.of(new BlockPos(1, 1, 1))));
        assertTrue(box.met(probe));
        assertFalse(blocks.met(probe), "a box is not a set of blocks");
        assertFalse(shape.met(probe), "a box is not a shape");
    }

    @Test
    void screenAndMovingConditions() {
        ticksOnlyOn(Conditions.activeBlockChanged(), Set.of("activeBlock"),
                p -> p.activeBlock = BlockDescriptor.of(new NamespacedId("minecraft:glass")));
        ticksOnlyOn(Conditions.clipboardChanged(), Set.of("clipboard"), p -> p.clipboard = "copied");
        ticksOnlyOn(Conditions.libraryChanged(), Set.of("library"), p -> p.libraryChanges++);
        ticksOnlyOn(Conditions.presetsChanged(), Set.of("presets"), p -> p.presetsVersion++);
        ticksOnlyOn(Conditions.looked(), Set.of("looking"), p -> p.looking = true);
        ticksOnlyOn(Conditions.flySpeedChanged(), Set.of("flySpeed"), p -> p.flySpeed = 0.5);
        ticksOnlyOn(Conditions.uiSizeChanged(), Set.of("uiSize"), p -> p.uiSize = 50);
        ticksOnlyOn(Conditions.reentered(), Set.of("editorEntries"), p -> p.editorEntries++);
        ticksOnlyOn(Conditions.menuOpen(CommandMenu.VIEW), Set.of(), p -> p.openMenu = CommandMenu.VIEW.ordinal());
        ticksOnlyOn(Conditions.windowShown(EditorWindows.LIBRARY), Set.of(), p -> p.windows.add(EditorWindows.LIBRARY));
        ticksOnlyOn(Conditions.keyboardInWindow(), Set.of("keyboardInWindow"), p -> p.keyboardInWindow = true);
        ticksOnlyOn(Conditions.windowsHiddenThenShown(), Set.of("windowsHidden"), p -> p.windowsHidden = true,
                p -> p.windowsHidden = false);
        ticksOnlyOn(Conditions.keySheetOpenedThenClosed(), Set.of("keySheet"), p -> p.keySheetOpen = true,
                p -> p.keySheetOpen = false);
        ticksOnlyOn(Conditions.commandSearchOpenedThenClosed(), Set.of("commandSearch"),
                p -> p.commandSearchOpen = true, p -> p.commandSearchOpen = false);
        ticksOnlyOn(Conditions.symmetryCentreSet(), Set.of("symmetry"), p -> p.symmetryCentreSet = true);
        ticksOnlyOn(Conditions.pathNodes(2), Set.of("pathNodes"), p -> p.pathNodes = 2);
        ticksOnlyOn(Conditions.scatterAreaPainted(), Set.of("scatterArea"), p -> p.scatterAreaPainted = true);
        ticksOnlyOn(Conditions.scatterMixChanged(), Set.of("scatterMix"), p -> p.scatterMixSize = 1);
    }

    @Test
    void movingTicksOnlyPastTheDistanceFromWhereTheStepBegan() {
        probe.eye = new double[] {100, 64, 100};
        Condition.Check check = Conditions.moved(3).start(probe);
        probe.eye = new double[] {102, 64, 101};
        assertFalse(check.met(probe), "2.2 blocks");
        probe.eye = new double[] {100, 67, 100};
        assertTrue(check.met(probe), "3 blocks up");

        FakeProbe unknown = new FakeProbe();
        Condition.Check fromFirstSight = Conditions.moved(3).start(unknown);
        unknown.eye = new double[] {50, 64, 50};
        assertFalse(fromFirstSight.met(unknown), "the first known place is where it starts");
        unknown.eye = new double[] {54, 64, 50};
        assertTrue(fromFirstSight.met(unknown));
    }

    @Test
    void anOpenedSheetMustAlsoCloseAndAClosedOneThatNeverOpenedDoesNotCount() {
        Condition.Check check = Conditions.keySheetOpenedThenClosed().start(probe);
        assertFalse(check.met(probe));
        probe.keySheetOpen = true;
        assertFalse(check.met(probe), "open, not yet closed");
        probe.keySheetOpen = false;
        assertTrue(check.met(probe));
    }

    @Test
    void aStateAlreadyTrueWhenTheStepBeginsIsMetAtOnceAChangeIsNot() {
        probe.activeTool = ToolId.SELECT;
        assertTrue(Conditions.toolActive(ToolId.SELECT).start(probe).met(probe));
        assertFalse(Conditions.flySpeedChanged().start(probe).met(probe));
        probe.pushes = 7;
        assertFalse(Conditions.edited().start(probe).met(probe), "only edits after the step began");
    }

    @Test
    void allNeedsEachAtOnceAndAnyNeedsOne() {
        Condition both = Conditions.all(Conditions.toolActive(ToolId.SELECT), Conditions.edited());
        Condition.Check all = both.start(probe);
        Condition.Check any = Conditions.any(Conditions.toolActive(ToolId.SELECT), Conditions.edited()).start(probe);
        probe.pushes++;
        assertFalse(all.met(probe));
        assertTrue(any.met(probe));
        probe.activeTool = ToolId.SELECT;
        assertTrue(all.met(probe));

        Condition.Check sequence = Conditions.all(Conditions.keySheetOpenedThenClosed(), Conditions.edited())
                .start(probe);
        probe.keySheetOpen = true;
        assertFalse(sequence.met(probe));
        probe.keySheetOpen = false;
        probe.pushes++;
        assertTrue(sequence.met(probe), "the sequence inside all() saw the frame where the sheet was open");
    }
}
