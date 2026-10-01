package dev.sculptory.fabric.client.editor;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.region.Region;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.RegionWork;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.world.BoxFace;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.Notice;
import dev.sculptory.fabric.client.session.Subscription;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Editor-wide state shared by tools, windows and the HUD: the tool registry and each tool's
 * settings, the shared selection (a box, shape or cell set), the active block, the fly speed, held modifiers, pointer
 * capture and the current cursor pick. Hands each tool its own {@link ToolContext} view, whose
 * {@code settings()} are that tool's. Pure Java; client thread only.
 */
public final class EditorContext {
    public static final BlockDescriptor DEFAULT_BLOCK = BlockDescriptor.of(new NamespacedId("minecraft:stone"));

    private final Supplier<EditorBackend> backend;
    private final Consumer<Notice> notices;
    private final ToolRegistry tools = new ToolRegistry();
    private final Map<ToolId, SettingsValues> settings = new HashMap<>();
    private final Map<ToolId, View> views = new HashMap<>();
    private final Listeners<Runnable> selectionListeners = new Listeners<>();
    private final Listeners<Consumer<ToolId>> settingsListeners = new Listeners<>();
    private final Listeners<Runnable> activeBlockListeners = new Listeners<>();
    private final FlySpeed flySpeed = new FlySpeed();
    private Selection selection;
    private RegionWork regionWork = RegionWork.direct();
    private BlockDescriptor activeBlock = DEFAULT_BLOCK;
    private int modifiers;
    private boolean pointerCapture;
    private CursorPick cursor = CursorPick.NONE;
    private BoxFace hoveredFace;

    /** @param notices shows toasts */
    public EditorContext(Supplier<EditorBackend> backend, Consumer<Notice> notices) {
        this.backend = Objects.requireNonNull(backend);
        this.notices = Objects.requireNonNull(notices);
    }

    public ToolRegistry tools() {
        return tools;
    }

    public EditorBackend backend() {
        return backend.get();
    }

    public Optional<EditorSession> session() {
        return backend.get().session();
    }

    public void notify(Notice notice) {
        notices.accept(Objects.requireNonNull(notice));
    }

    // ---- Tool contexts and settings ----

    /** The context for {@code id}, created on first use. */
    public ToolContext contextFor(ToolId id) {
        return views.computeIfAbsent(Objects.requireNonNull(id), View::new);
    }

    /** The active tool's context, if a tool is active. */
    public Optional<ToolContext> activeContext() {
        return tools.active().map(tool -> contextFor(tool.descriptor().id()));
    }

    public SettingsValues settings(ToolId id) {
        return settings.computeIfAbsent(id, key -> SettingsValues.defaults(requireTool(key).schema()));
    }

    /**
     * Replaces a tool's settings. The active tool hears {@code onSettingsChanged}; listeners (the
     * Tool Settings window) hear the tool id.
     */
    public void updateSettings(ToolId id, SettingsValues values) {
        Objects.requireNonNull(values);
        Tool tool = requireTool(id);
        if (!values.schema().equals(tool.schema())) {
            throw new IllegalArgumentException("Settings for another schema given to " + id);
        }
        SettingsValues before = settings(id);
        if (before.equals(values)) {
            return;
        }
        settings.put(id, values);
        if (tools.isActive(id)) {
            tool.onSettingsChanged(contextFor(id), before, values);
        }
        settingsListeners.fire(listener -> listener.accept(id));
    }

    public Subscription onSettingsChanged(Consumer<ToolId> listener) {
        return settingsListeners.add(listener);
    }

    private Tool requireTool(ToolId id) {
        return tools.get(id).orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + id));
    }

    // ---- Selection ----

    /** The selection's bounding box (the selection itself for a box selection), if any. Cheap. */
    public Optional<Box> selection() {
        return Optional.ofNullable(selection).map(Selection::bounds);
    }

    /**
     * The selection: a box, a shape in its box, or a set of cells. Applies a pending move of a cell set (once), so
     * per-frame code uses {@link #selectionState()}.
     */
    public Optional<Region> selectionRegion() {
        return Optional.ofNullable(selection).map(Selection::region);
    }

    /** The selection as held: its region, a pending move, cheap bounds and kind. */
    public Optional<Selection> selectionState() {
        return Optional.ofNullable(selection);
    }

    /** Selects a box ({@code null} clears the selection). */
    public void setSelection(Box box) {
        setSelectionRegion(box == null ? null : new Region.Cuboid(box));
    }

    /**
     * Sets or (with {@code null}) clears the selection.
     *
     * @throws IllegalArgumentException for an {@link Region.Uploaded}, which is only a wire reference
     */
    public void setSelectionRegion(Region region) {
        if (region != null && selection != null && Arrays.equals(selection.offset(), new int[3])
                && (selection.base() == region || (!(region instanceof Region.Cells) && region.equals(selection.base())))) {
            return; // unchanged (value equality only for boxes and shapes, where it is cheap)
        }
        setSelectionState(region == null ? null : Selection.of(region));
    }

    /** Puts back a selection state (e.g. from before a drag), or clears the selection with {@code null}. */
    public void setSelectionState(Selection state) {
        if (state == selection) {
            return;
        }
        selection = state;
        if (state == null || !state.resizable()) {
            hoveredFace = null; // a cell set has no handles
        }
        selectionListeners.fire(Runnable::run);
    }

    /** Moves the selection (clamped); a cell set only moves its pending offset, so this is cheap for every kind. */
    public void moveSelection(int dx, int dy, int dz) {
        if (selection != null) {
            setSelectionState(selection.translate(dx, dy, dz));
        }
    }

    /** Exact cell counts and heavy region work, off the client thread (at once by default, as in tests). */
    public RegionWork regionWork() {
        return regionWork;
    }

    /** The region work the editor uses (the game gives it a background thread). */
    public void setRegionWork(RegionWork work) {
        regionWork = Objects.requireNonNull(work);
    }

    public Subscription onSelectionChanged(Runnable listener) {
        return selectionListeners.add(listener);
    }

    /** The selection face handle to highlight, or null. */
    public BoxFace hoveredFace() {
        return hoveredFace;
    }

    public void setHoveredFace(BoxFace face) {
        hoveredFace = face;
    }

    // ---- Active block, fly speed, input state ----

    public BlockDescriptor activeBlock() {
        return activeBlock;
    }

    public void setActiveBlock(BlockDescriptor block) {
        Objects.requireNonNull(block);
        if (!block.equals(activeBlock)) {
            activeBlock = block;
            activeBlockListeners.fire(Runnable::run);
        }
    }

    public Subscription onActiveBlockChanged(Runnable listener) {
        return activeBlockListeners.add(listener);
    }

    public FlySpeed flySpeed() {
        return flySpeed;
    }

    public int modifiers() {
        return modifiers;
    }

    public void setModifiers(int modifiers) {
        this.modifiers = modifiers;
    }

    public boolean pointerCapture() {
        return pointerCapture;
    }

    public void setPointerCapture(boolean captured) {
        pointerCapture = captured;
    }

    public CursorPick cursor() {
        return cursor;
    }

    public void setCursor(CursorPick cursor) {
        this.cursor = Objects.requireNonNull(cursor);
    }

    /** One tool's view of the editor. */
    private final class View implements ToolContext {
        private final ToolId id;

        View(ToolId id) {
            this.id = id;
        }

        @Override
        public EditorSession session() {
            return backend.get().session().orElseThrow(() -> new IllegalStateException("No Sculptory session"));
        }

        @Override
        public StateSpace states() {
            return backend.get().states();
        }

        @Override
        public WorldReader world() {
            return backend.get().world();
        }

        @Override
        public SettingsValues settings() {
            return EditorContext.this.settings(id);
        }

        @Override
        public void updateSettings(SettingsValues values) {
            EditorContext.this.updateSettings(id, values);
        }

        @Override
        public Optional<Box> selection() {
            return EditorContext.this.selection();
        }

        @Override
        public void setSelection(Box box) {
            EditorContext.this.setSelection(box);
        }

        @Override
        public Optional<Region> selectionRegion() {
            return EditorContext.this.selectionRegion();
        }

        @Override
        public void setSelectionRegion(Region region) {
            EditorContext.this.setSelectionRegion(region);
        }

        @Override
        public Optional<Selection> selectionState() {
            return EditorContext.this.selectionState();
        }

        @Override
        public void setSelectionState(Selection selection) {
            EditorContext.this.setSelectionState(selection);
        }

        @Override
        public void moveSelection(int dx, int dy, int dz) {
            EditorContext.this.moveSelection(dx, dy, dz);
        }

        @Override
        public RegionWork regionWork() {
            return EditorContext.this.regionWork();
        }

        @Override
        public int modifiers() {
            return modifiers;
        }

        @Override
        public void setPointerCapture(boolean captured) {
            EditorContext.this.setPointerCapture(captured);
        }

        @Override
        public void notify(Notice notice) {
            EditorContext.this.notify(notice);
        }

        @Override
        public String toString() {
            return "ToolContext[" + id + "]";
        }
    }
}
