package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.select.SelectSettings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A {@link TutorialProbe} whose every value the test sets. */
final class FakeProbe implements TutorialProbe {
    final Map<ToolId, SettingsValues> settings = new LinkedHashMap<>();
    ToolId activeTool;
    Selection selection;
    long selectionChanges;
    BlockDescriptor activeBlock = BlockDescriptor.of(new NamespacedId("minecraft:stone"));
    double flySpeed = 1;
    boolean looking;
    double[] eye;
    int uiSize = 100;
    int openMenu = -1;
    final Set<String> windows = new HashSet<>();
    boolean windowsHidden;
    boolean keySheetOpen;
    boolean commandSearchOpen;
    boolean keyboardInWindow;
    long editorEntries;
    long pushes;
    long undos;
    long redos;
    Object clipboard;
    long libraryChanges;
    int presetsVersion;
    final Map<ToolId, Long> strokes = new HashMap<>();
    boolean symmetryCentreSet;
    int pathNodes;
    boolean scatterAreaPainted;
    int scatterMixSize;
    boolean tinkerAimsAtProperty;
    boolean gradientLineSet;
    boolean placing;
    boolean placementUpsideDown;
    boolean builderPowerOn;
    boolean exportDialogOpen;
    long exports;

    FakeProbe() {
        settings.put(ToolId.SELECT, SettingsValues.defaults(SelectSettings.SCHEMA));
    }

    void stroke(ToolId tool) {
        strokes.merge(tool, 1L, Long::sum);
    }

    @Override
    public List<ToolId> tools() {
        return new ArrayList<>(settings.keySet());
    }

    @Override
    public Optional<ToolId> activeTool() {
        return Optional.ofNullable(activeTool);
    }

    @Override
    public SettingsValues settings(ToolId tool) {
        SettingsValues values = settings.get(tool);
        if (values == null) {
            throw new IllegalArgumentException("No settings for " + tool);
        }
        return values;
    }

    @Override
    public Optional<Selection> selection() {
        return Optional.ofNullable(selection);
    }

    @Override
    public long selectionChanges() {
        return selectionChanges;
    }

    @Override
    public BlockDescriptor activeBlock() {
        return activeBlock;
    }

    @Override
    public double flySpeed() {
        return flySpeed;
    }

    @Override
    public boolean looking() {
        return looking;
    }

    @Override
    public Optional<double[]> eye() {
        return Optional.ofNullable(eye);
    }

    @Override
    public int uiSizePercent() {
        return uiSize;
    }

    @Override
    public int openMenu() {
        return openMenu;
    }

    @Override
    public boolean windowShown(String windowId) {
        return windows.contains(windowId) && !windowsHidden;
    }

    @Override
    public boolean windowsHidden() {
        return windowsHidden;
    }

    @Override
    public boolean keySheetOpen() {
        return keySheetOpen;
    }

    @Override
    public boolean commandSearchOpen() {
        return commandSearchOpen;
    }

    @Override
    public boolean keyboardInWindow() {
        return keyboardInWindow;
    }

    @Override
    public long editorEntries() {
        return editorEntries;
    }

    @Override
    public long historyPushes() {
        return pushes;
    }

    @Override
    public long historyUndos() {
        return undos;
    }

    @Override
    public long historyRedos() {
        return redos;
    }

    @Override
    public Optional<Object> clipboard() {
        return Optional.ofNullable(clipboard);
    }

    @Override
    public long libraryChanges() {
        return libraryChanges;
    }

    @Override
    public int presetsVersion() {
        return presetsVersion;
    }

    @Override
    public long strokesEnded(ToolId tool) {
        return strokes.getOrDefault(tool, 0L);
    }

    @Override
    public boolean symmetryCentreSet() {
        return symmetryCentreSet;
    }

    @Override
    public int pathNodes() {
        return pathNodes;
    }

    @Override
    public boolean scatterAreaPainted() {
        return scatterAreaPainted;
    }

    @Override
    public int scatterMixSize() {
        return scatterMixSize;
    }

    @Override
    public boolean tinkerAimsAtProperty() {
        return tinkerAimsAtProperty;
    }

    @Override
    public boolean gradientLineSet() {
        return gradientLineSet;
    }

    @Override
    public boolean placing() {
        return placing;
    }

    @Override
    public boolean placementUpsideDown() {
        return placementUpsideDown;
    }

    @Override
    public boolean builderPowerOn() {
        return builderPowerOn;
    }

    @Override
    public boolean exportDialogOpen() {
        return exportDialogOpen;
    }

    @Override
    public long exports() {
        return exports;
    }
}
