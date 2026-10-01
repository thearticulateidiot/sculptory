package dev.sculptory.fabric.client.editor.tool;

import dev.sculptory.core.Box;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import java.util.Optional;

/** What the editor offers the active tool. Valid only between activate and deactivate, on the client thread. */
public interface ToolContext {
    EditorSession session();

    /** The client's state space for this connection. */
    StateSpace states();

    /** The client world, for prediction and previews. */
    WorldReader world();

    /** This tool's current settings. */
    SettingsValues settings();

    /** Replaces this tool's settings (the settings window updates, and presets can save them). */
    void updateSettings(SettingsValues values);

    /**
     * The shared selection's bounding box, if any. For a box selection that is the selection; brushes ("only inside
     * selection") and Scatter ("use selection") use it for every kind (a known limit for shapes and cell sets).
     */
    Optional<Box> selection();

    /** Sets or (with {@code null}) clears the shared selection, as a box. */
    void setSelection(Box box);

    /**
     * The shared selection's region: a box, a shape inscribed in its box, or a set of cells (magic select). Applies a
     * pending move of a cell set (a rebuild), so per-frame code uses {@link #selectionState()} instead.
     */
    Optional<Region> selectionRegion();

    /** Sets or (with {@code null}) clears the shared selection. */
    void setSelectionRegion(Region region);

    /** The shared selection as held, with its cheap bounds and kind (see {@link Selection}). */
    Optional<Selection> selectionState();

    /** Puts back a selection state (e.g. from before a drag), or clears it with {@code null}. */
    void setSelectionState(Selection selection);

    /** Moves the shared selection (clamped; a cell set only moves its offset, see {@link Selection}). */
    void moveSelection(int dx, int dy, int dz);

    /** Exact cell counts and heavy region work, off the client thread. */
    RegionWork regionWork();

    /** Modifier keys currently held ({@link Modifiers} bits). */
    int modifiers();

    /** While captured, pointer events go to this tool even over windows (e.g. during a drag). */
    void setPointerCapture(boolean captured);

    /** Shows a toast. */
    void notify(Notice notice);
}
