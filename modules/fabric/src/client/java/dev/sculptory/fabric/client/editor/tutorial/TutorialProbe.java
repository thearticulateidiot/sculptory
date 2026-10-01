package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import java.util.List;
import java.util.Optional;

/**
 * What a step's {@link Condition} reads from the editor, once per frame. State is read as it is now; the counters
 * ({@code ...Changes}, {@code history...}, {@link #strokesEnded}, {@link #editorEntries}) only grow, so a condition
 * compares them with their value when the step began and catches what happened between two frames.
 */
public interface TutorialProbe {
    /** The palette's tools. */
    List<ToolId> tools();

    Optional<ToolId> activeTool();

    SettingsValues settings(ToolId tool);

    Optional<Selection> selection();

    /** How many times the selection changed. */
    long selectionChanges();

    BlockDescriptor activeBlock();

    /** The fly speed multiplier. */
    double flySpeed();

    /** Whether the player is looking around (right-drag). */
    boolean looking();

    /** Where the camera is, in world coordinates, when known. */
    Optional<double[]> eye();

    int uiSizePercent();

    /** The open menu bar menu (a {@code CommandMenu} ordinal), or -1. */
    int openMenu();

    /** Whether the window is open and not hidden with the others. */
    boolean windowShown(String windowId);

    /** Whether all windows are hidden (Tab). */
    boolean windowsHidden();

    /** Whether the F1 key sheet is open. */
    boolean keySheetOpen();

    /** Whether the command search (Ctrl+K) is open. */
    boolean commandSearchOpen();

    /** Whether a control in a window has the keyboard (F6), no popup open. */
    boolean keyboardInWindow();

    /** How many times the editor opened. */
    long editorEntries();

    /** History entries the player made (new edits), undid and redid. */
    long historyPushes();

    long historyUndos();

    long historyRedos();

    /** The player's current clipboard, compared by identity of its contents (a new copy is a new clipboard). */
    Optional<Object> clipboard();

    /** Library changes seen (a save, rename, move, delete). */
    long libraryChanges();

    /** Changes to the saved presets. */
    int presetsVersion();

    /** Brush strokes of that tool that have ended (pressed and let go). */
    long strokesEnded(ToolId tool);

    /** Whether the symmetry centre (M) is set. */
    boolean symmetryCentreSet();

    /** The Generate tool's road nodes. */
    int pathNodes();

    /** Whether the Scatter tool has a painted area (or uses the selection). */
    boolean scatterAreaPainted();

    /** Variants in the Scatter tool's mix. */
    int scatterMixSize();

    /** Whether Tinker points at a block with a property its Scroll changes (the label beside the cursor names it). */
    boolean tinkerAimsAtProperty();

    /** Whether the Gradient pattern's line (Alt+drag) is drawn. */
    boolean gradientLineSet();

    /** Whether the Place tool has a placement in progress (a paste, move or stack ghost). */
    boolean placing();

    /** Whether the placement in progress is turned upside down (V). */
    boolean placementUpsideDown();

    /** Whether any builder-mode power is on (the G ring, outside the editor). */
    boolean builderPowerOn();

    /** Whether the Export… dialog is open. */
    boolean exportDialogOpen();

    /** Exports started (a file name and format confirmed in the Export… dialog). */
    long exports();
}
