package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.core.region.Region;
import dev.sculptory.fabric.client.editor.commands.CommandMenu;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tutorial.Condition.Check;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * The lessons' step conditions. Three kinds:
 * <ul>
 *   <li><b>state</b> ({@link #toolActive}, {@link #setting}, {@link #windowShown}...): met while the editor is in that
 *       state, also at once when it already is as the step begins;</li>
 *   <li><b>change</b> ({@link #changed}, {@link #settingChanged}, {@link #activeBlockChanged}...): met once a value
 *       differs from its value when the step began;</li>
 *   <li><b>event</b> ({@link #edited}, {@link #undone}, {@link #strokeEnded}, {@link #selectionChanged}...): met once a
 *       counter has grown since the step began, so something that happened between two frames is not missed.</li>
 * </ul>
 */
public final class Conditions {
    private Conditions() {}

    // ---- Building blocks ----

    /** Met while {@code state} holds. */
    public static Condition state(Predicate<TutorialProbe> state) {
        Objects.requireNonNull(state);
        return probe -> state::test;
    }

    /** Met once {@code value} differs from what it was when the step began. */
    public static Condition changed(Function<TutorialProbe, ?> value) {
        Objects.requireNonNull(value);
        return probe -> {
            Object start = value.apply(probe);
            return now -> !Objects.equals(start, value.apply(now));
        };
    }

    /** Met once {@code counter} has grown since the step began. */
    public static Condition increased(ToLongFunction<TutorialProbe> counter) {
        Objects.requireNonNull(counter);
        return probe -> {
            long start = counter.applyAsLong(probe);
            return now -> counter.applyAsLong(now) > start;
        };
    }

    /** Met once {@code open} has held and then stopped holding (a sheet opened and closed again). */
    public static Condition openedThenClosed(Predicate<TutorialProbe> open) {
        Objects.requireNonNull(open);
        return probe -> {
            boolean[] seen = {false};
            return now -> {
                if (open.test(now)) {
                    seen[0] = true;
                    return false;
                }
                return seen[0];
            };
        };
    }

    /** Met when each condition is met at the same frame. */
    public static Condition all(Condition... conditions) {
        List<Condition> list = List.of(conditions);
        return probe -> {
            List<Check> checks = new ArrayList<>();
            for (Condition condition : list) {
                checks.add(condition.start(probe));
            }
            return now -> {
                boolean met = true;
                for (Check check : checks) {
                    // Every check sees every frame (a sequence must not miss one), so no early return.
                    met &= check.met(now);
                }
                return met;
            };
        };
    }

    /** Met when any of the conditions is met. */
    public static Condition any(Condition... conditions) {
        List<Condition> list = List.of(conditions);
        return probe -> {
            List<Check> checks = new ArrayList<>();
            for (Condition condition : list) {
                checks.add(condition.start(probe));
            }
            return now -> {
                boolean met = false;
                for (Check check : checks) {
                    met |= check.met(now);
                }
                return met;
            };
        };
    }

    // ---- Tools and settings ----

    public static Condition toolActive(ToolId tool) {
        Objects.requireNonNull(tool);
        return state(probe -> probe.activeTool().filter(tool::equals).isPresent());
    }

    /** The tool's setting {@code key} is {@code value}. */
    public static Condition setting(ToolId tool, String key, Object value) {
        return withSetting(tool, key, current -> Objects.equals(current, value));
    }

    /** The tool's setting {@code key} is anything but {@code value}. */
    public static Condition settingNot(ToolId tool, String key, Object value) {
        return withSetting(tool, key, current -> !Objects.equals(current, value));
    }

    private static Condition withSetting(ToolId tool, String key, Predicate<Object> test) {
        Objects.requireNonNull(tool);
        Objects.requireNonNull(key);
        return probe -> {
            // Checked as the step begins: a key the tool doesn't have is a mistake in the lesson.
            setting(probe.settings(tool), tool, key);
            return now -> test.test(setting(now.settings(tool), tool, key));
        };
    }

    /** The tool's setting {@code key} differs from its value when the step began. */
    public static Condition settingChanged(ToolId tool, String key) {
        Objects.requireNonNull(tool);
        Objects.requireNonNull(key);
        return probe -> {
            Object start = setting(probe.settings(tool), tool, key);
            return now -> !Objects.equals(start, setting(now.settings(tool), tool, key));
        };
    }

    /** Setting {@code key} of any tool that has one changed (the radius of whichever brush the player uses). */
    public static Condition anyToolSettingChanged(String key) {
        Objects.requireNonNull(key);
        return probe -> {
            Map<ToolId, Object> start = new HashMap<>();
            for (ToolId tool : probe.tools()) {
                SettingsValues values = probe.settings(tool);
                values.schema().def(key).ifPresent(def -> start.put(tool, values.get(def)));
            }
            if (start.isEmpty()) {
                throw new IllegalArgumentException("No tool has a setting " + key);
            }
            return now -> start.entrySet().stream().anyMatch(entry ->
                    !Objects.equals(entry.getValue(), setting(now.settings(entry.getKey()), entry.getKey(), key)));
        };
    }

    /** Any setting of any tool changed. */
    public static Condition anySettingsChanged() {
        return probe -> {
            Map<ToolId, SettingsValues> start = snapshot(probe);
            return now -> start.entrySet().stream().anyMatch(entry -> !entry.getValue().equals(now.settings(entry.getKey())));
        };
    }

    /**
     * A setting that differed from its default when the step began is back at its default (its ↺ was clicked), in
     * any tool.
     */
    public static Condition settingReset() {
        return probe -> {
            Map<ToolId, SettingsValues> start = snapshot(probe);
            return now -> {
                for (Map.Entry<ToolId, SettingsValues> entry : start.entrySet()) {
                    SettingsValues before = entry.getValue();
                    SettingsValues defaults = SettingsValues.defaults(before.schema());
                    SettingsValues current = now.settings(entry.getKey());
                    for (SettingDef<?> def : before.schema().defs()) {
                        Object initial = defaults.get(def);
                        if (!Objects.equals(before.get(def), initial) && Objects.equals(current.get(def), initial)) {
                            return true;
                        }
                    }
                }
                return false;
            };
        };
    }

    private static Map<ToolId, SettingsValues> snapshot(TutorialProbe probe) {
        Map<ToolId, SettingsValues> values = new HashMap<>();
        for (ToolId tool : probe.tools()) {
            values.put(tool, probe.settings(tool));
        }
        return values;
    }

    private static Object setting(SettingsValues values, ToolId tool, String key) {
        Optional<SettingDef<?>> def = values.schema().def(key);
        if (def.isEmpty()) {
            throw new IllegalArgumentException("Tool " + tool + " has no setting " + key);
        }
        return values.get(def.get());
    }

    /** A brush stroke of {@code tool} ended (the Shape brush: a click or a drag). */
    public static Condition strokeEnded(ToolId tool) {
        Objects.requireNonNull(tool);
        return increased(probe -> probe.strokesEnded(tool));
    }

    // ---- Edits and history ----

    /** A new history entry: the player made an edit (a stroke, a fill, a paste, a road...). */
    public static Condition edited() {
        return increased(TutorialProbe::historyPushes);
    }

    public static Condition undone() {
        return increased(TutorialProbe::historyUndos);
    }

    public static Condition redone() {
        return increased(TutorialProbe::historyRedos);
    }

    // ---- Selection, blocks, clipboard, library ----

    public static Condition selectionChanged() {
        return increased(TutorialProbe::selectionChanges);
    }

    public static Condition hasSelection() {
        return state(probe -> probe.selection().isPresent());
    }

    public static Condition noSelection() {
        return state(probe -> probe.selection().isEmpty());
    }

    /** The selection changed and is now a plain box. */
    public static Condition boxSelected() {
        return all(selectionChanged(), state(probe -> kind(probe) instanceof Region.Cuboid));
    }

    /** The selection changed and is now a set of blocks (magic, brush or lasso select). */
    public static Condition blocksSelected() {
        return all(selectionChanged(), state(probe -> kind(probe) instanceof Region.Cells));
    }

    /** The selection changed and is now a shape in its box (sphere, cylinder, cone, pyramid). */
    public static Condition shapeSelected() {
        return all(selectionChanged(), state(probe -> kind(probe) instanceof Region.Shape));
    }

    private static Region kind(TutorialProbe probe) {
        return probe.selection().map(Selection::base).orElse(null);
    }

    public static Condition activeBlockChanged() {
        return changed(TutorialProbe::activeBlock);
    }

    public static Condition clipboardChanged() {
        return changed(TutorialProbe::clipboard);
    }

    public static Condition libraryChanged() {
        return increased(TutorialProbe::libraryChanges);
    }

    public static Condition presetsChanged() {
        return changed(TutorialProbe::presetsVersion);
    }

    // ---- Moving around and the screen ----

    public static Condition looked() {
        return state(TutorialProbe::looking);
    }

    /** The camera moved at least {@code blocks} from where it was when the step began (or first became known). */
    public static Condition moved(double blocks) {
        return probe -> {
            double[][] start = {probe.eye().orElse(null)};
            return now -> {
                Optional<double[]> eye = now.eye();
                if (eye.isEmpty()) {
                    return false;
                }
                if (start[0] == null) {
                    start[0] = eye.get();
                    return false;
                }
                double dx = eye.get()[0] - start[0][0];
                double dy = eye.get()[1] - start[0][1];
                double dz = eye.get()[2] - start[0][2];
                return dx * dx + dy * dy + dz * dz >= blocks * blocks;
            };
        };
    }

    public static Condition flySpeedChanged() {
        return changed(TutorialProbe::flySpeed);
    }

    public static Condition uiSizeChanged() {
        return changed(TutorialProbe::uiSizePercent);
    }

    /** The editor was left and opened again since the step began. */
    public static Condition reentered() {
        return increased(TutorialProbe::editorEntries);
    }

    public static Condition menuOpen(CommandMenu menu) {
        Objects.requireNonNull(menu);
        return state(probe -> probe.openMenu() == menu.ordinal());
    }

    public static Condition windowShown(String windowId) {
        Objects.requireNonNull(windowId);
        return state(probe -> probe.windowShown(windowId));
    }

    public static Condition keyboardInWindow() {
        return state(TutorialProbe::keyboardInWindow);
    }

    /** The windows were hidden (Tab) and then shown again. */
    public static Condition windowsHiddenThenShown() {
        return openedThenClosed(TutorialProbe::windowsHidden);
    }

    public static Condition keySheetOpenedThenClosed() {
        return openedThenClosed(TutorialProbe::keySheetOpen);
    }

    public static Condition commandSearchOpenedThenClosed() {
        return openedThenClosed(TutorialProbe::commandSearchOpen);
    }

    // ---- Tools' own state ----

    public static Condition symmetryCentreSet() {
        return state(TutorialProbe::symmetryCentreSet);
    }

    public static Condition pathNodes(int atLeast) {
        return state(probe -> probe.pathNodes() >= atLeast);
    }

    public static Condition scatterAreaPainted() {
        return state(TutorialProbe::scatterAreaPainted);
    }

    public static Condition scatterMixChanged() {
        return changed(TutorialProbe::scatterMixSize);
    }

    // ---- Tinker, mix patterns, the flip, builder mode, exports ----

    /** Tinker points at a block whose shown property its Scroll changes. */
    public static Condition tinkerAimed() {
        return state(TutorialProbe::tinkerAimsAtProperty);
    }

    /** The Gradient pattern's line is drawn (Alt+drag). */
    public static Condition gradientLineSet() {
        return state(TutorialProbe::gradientLineSet);
    }

    /** The Place tool shows a ghost (a paste, move or stack in progress). */
    public static Condition placing() {
        return state(TutorialProbe::placing);
    }

    /** The ghost in progress is turned upside down. */
    public static Condition placementUpsideDown() {
        return state(TutorialProbe::placementUpsideDown);
    }

    /** A builder-mode power is on. */
    public static Condition builderPowerOn() {
        return state(TutorialProbe::builderPowerOn);
    }

    /** No builder-mode power is on. */
    public static Condition noBuilderPower() {
        return state(probe -> !probe.builderPowerOn());
    }

    /** The Export… dialog is open. */
    public static Condition exportDialogOpen() {
        return state(TutorialProbe::exportDialogOpen);
    }

    /** An export was confirmed in the Export… dialog. */
    public static Condition exported() {
        return increased(TutorialProbe::exports);
    }
}
