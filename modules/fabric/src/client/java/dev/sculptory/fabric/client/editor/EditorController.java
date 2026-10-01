package dev.sculptory.fabric.client.editor;

import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.InputRouter;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.mask.EditMaskModel;
import dev.sculptory.fabric.client.editor.mask.MaskChip;
import dev.sculptory.fabric.client.editor.tool.DeactivateReason;
import dev.sculptory.fabric.client.editor.tool.EditorAction;
import dev.sculptory.fabric.client.editor.tool.FrameInfo;
import dev.sculptory.fabric.client.editor.tool.KeyHint;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.ScrollEvent;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.EditorToolSet;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.scatter.ScatterTool;
import dev.sculptory.fabric.client.editor.tools.select.SelectionActions;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.HistoryMirror;
import dev.sculptory.fabric.client.session.Notice;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The editor's behaviour, free of Minecraft types: tool switching and permission checks, routing
 * input to the active tool, the editor-level keymap actions (undo, redo, windows, help, UI size,
 * fly speed, eyedropper, aim at water and lava, selection keys with any tool), suspension keys and the per-frame
 * cursor pick.
 */
public final class EditorController implements InputRouter.Tools, InputRouter.Commands {
    /** The UI commands keymap actions trigger. */
    public interface Ui {
        void toggleHelp();

        void toggleWindow(String id);

        void toggleWindowsHidden();

        /** The active tool changed (the Tool Settings window rebuilds). */
        void toolChanged();

        /** One editor UI size step larger ({@code direction > 0}) or smaller. */
        void stepUiSize(int direction);

        /** Back to 100% editor UI size. */
        void resetUiSize();

        /** Opens the command search (Ctrl+K). */
        default void openCommandSearch() {}

        /** F6 (Shift+F6: backwards): the keyboard into the next open window's first control. */
        default void focusNextWindow(boolean forward) {}
    }

    public static final String HISTORY_WINDOW = "history";
    public static final String LIBRARY_WINDOW = "library";

    private static final Ui NO_UI = new Ui() {
        @Override
        public void toggleHelp() {}

        @Override
        public void toggleWindow(String id) {}

        @Override
        public void toggleWindowsHidden() {}

        @Override
        public void toolChanged() {}

        @Override
        public void stepUiSize(int direction) {}

        @Override
        public void resetUiSize() {}
    };

    private final EditorContext ctx;
    private final EditorMode mode;
    private final EditorPlatform platform;
    private final SelectionActions selection;
    private final EditorKeymap keymap;
    private final Translator translator;
    private final BooleanSupplier looking;
    private Ui ui = NO_UI;
    private ToolId lastTool = ToolId.SELECT;
    private ClipboardActions clipboard;
    /** The tool to go back to when a placement started from it ends. */
    private ToolId returnTool;
    /** The Place tool asked to go back (done at the next tick or frame, never inside a tool call). */
    private boolean returnPending;
    /** Jump and Through: the pointer of the last frame, and where the pick goes. */
    private double pointerX, pointerY;
    private java.util.function.BiConsumer<CursorPick, Boolean> navigator = (pick, through) -> { };

    /** @param looking true while right-button look is held (tools then aim along the crosshair) */
    public EditorController(EditorContext ctx, EditorMode mode, EditorPlatform platform, SelectionActions selection,
            EditorKeymap keymap, Translator translator, BooleanSupplier looking) {
        this.ctx = Objects.requireNonNull(ctx);
        this.mode = Objects.requireNonNull(mode);
        this.platform = Objects.requireNonNull(platform);
        this.selection = Objects.requireNonNull(selection);
        this.keymap = Objects.requireNonNull(keymap);
        this.translator = Objects.requireNonNull(translator);
        this.looking = Objects.requireNonNull(looking);
    }

    public void setUi(Ui ui) {
        this.ui = Objects.requireNonNull(ui);
    }

    /** The clipboard actions Ctrl+C, Ctrl+X and Ctrl+V run (without them those keys do nothing). */
    public void setClipboard(ClipboardActions clipboard) {
        this.clipboard = Objects.requireNonNull(clipboard);
    }

    public Optional<ClipboardActions> clipboard() {
        return Optional.ofNullable(clipboard);
    }

    // ---- Place tool ----

    /** The Place tool, if the palette has one. */
    public Optional<PlaceTool> placeTool() {
        return ctx.tools().get(ToolId.PLACE).filter(PlaceTool.class::isInstance).map(PlaceTool.class::cast);
    }

    /** The Place tool while it is the active tool. */
    public Optional<PlaceTool> activePlaceTool() {
        return ctx.tools().isActive(ToolId.PLACE) ? placeTool() : Optional.empty();
    }

    /**
     * Starts a placement (paste, move, stack) in the Place tool, activating it; the tool that was active comes back
     * when a move or stack is committed or the placement is cancelled. Toasts why not when the tool can't be used.
     */
    public boolean place(PlaceTool.Request request) {
        Optional<PlaceTool> place = placeTool();
        if (place.isEmpty()) {
            return false;
        }
        Optional<Notice> blocked = unavailability(place.get());
        if (blocked.isPresent()) {
            ctx.notify(blocked.get());
            return false;
        }
        Optional<Tool> active = ctx.tools().active();
        if (active.isPresent() && !active.get().descriptor().id().equals(ToolId.PLACE)) {
            returnTool = active.get().descriptor().id();
        }
        returnPending = false;
        place.get().request(request);
        activate(ToolId.PLACE);
        return true;
    }

    // ---- Scatter tool ----

    /** The Scatter tool, if the palette has one. */
    public Optional<ScatterTool> scatterTool() {
        return ctx.tools().get(ToolId.SCATTER).filter(ScatterTool.class::isInstance).map(ScatterTool.class::cast);
    }

    /** The Scatter tool while it is the active tool. */
    public Optional<ScatterTool> activeScatterTool() {
        return ctx.tools().isActive(ToolId.SCATTER) ? scatterTool() : Optional.empty();
    }

    /** Activates a tool by id, or toasts why it can't be used. Returns true if it is now active. */
    public boolean selectTool(ToolId id) {
        Optional<Tool> tool = ctx.tools().get(id);
        if (tool.isEmpty()) {
            return false;
        }
        Optional<Notice> blocked = unavailability(tool.get());
        if (blocked.isPresent()) {
            ctx.notify(blocked.get());
            return false;
        }
        activate(id);
        return true;
    }

    /** The Place tool finished a placement started from another tool: go back to it (at the next tick or frame). */
    public void placeFinished() {
        returnPending = true;
    }

    private void returnFromPlace() {
        if (!returnPending) {
            return;
        }
        returnPending = false;
        ToolId back = returnTool;
        returnTool = null;
        if (back == null || !ctx.tools().isActive(ToolId.PLACE)) {
            return;
        }
        boolean usable = ctx.tools().get(back).map(tool -> unavailability(tool).isEmpty()).orElse(false);
        if (usable) {
            activate(back);
        }
    }

    public EditorContext context() {
        return ctx;
    }

    public EditorKeymap keymap() {
        return keymap;
    }

    // ---- Lifecycle ----

    /** The editor opened or resumed: activate the last tool (or the first usable one) and the fly speed. */
    public void onEntered() {
        ToolId id = lastTool;
        if (ctx.tools().get(id).map(tool -> unavailability(tool).isPresent()).orElse(true)) {
            id = firstUsable().orElse(null);
        }
        if (id != null) {
            activate(id);
        }
        platform.applyFlySpeed(ctx.flySpeed().multiplier());
    }

    /**
     * The editor closed or was suspended: deactivate the tool (remembering it for next time) and forget queued
     * undo/redo presses, so they don't keep running after Esc.
     */
    public void onExited(DeactivateReason reason) {
        ctx.session().ifPresent(EditorSession::dropQueuedHistorySteps);
        Optional<Tool> active = ctx.tools().active();
        // A placement started from another tool does not survive closing the editor: reopen with that tool.
        active.ifPresent(tool -> lastTool = tool.descriptor().id().equals(ToolId.PLACE) && returnTool != null
                && reason != DeactivateReason.SUSPENDED ? returnTool : tool.descriptor().id());
        active.ifPresent(tool -> ctx.tools().deactivate(ctx.contextFor(tool.descriptor().id()), reason));
        ctx.setPointerCapture(false);
        ctx.setHoveredFace(null);
        if (reason != DeactivateReason.SUSPENDED) {
            platform.restoreFlySpeed();
        }
        ui.toolChanged();
    }

    /** Once per client tick while the editor is active: permission changes, fly speed. */
    public void tick() {
        returnFromPlace();
        Optional<Tool> active = ctx.tools().active();
        if (active.isPresent()) {
            Optional<Notice> blocked = unavailability(active.get());
            if (blocked.isPresent()) {
                ToolId id = active.get().descriptor().id();
                ctx.tools().deactivate(ctx.contextFor(id), DeactivateReason.PERMISSION_LOST);
                ctx.setPointerCapture(false);
                ctx.notify(blocked.get());
                firstUsable().ifPresent(this::activate);
                ui.toolChanged();
            }
        }
        platform.applyFlySpeed(ctx.flySpeed().multiplier());
    }

    /** Once per rendered frame: picks the world under the cursor and lets the tool update. */
    public void frame(double mouseX, double mouseY, long nanoTime, float tickDelta) {
        pointerX = mouseX;
        pointerY = mouseY;
        returnFromPlace();
        Optional<Tool> active = ctx.tools().active();
        if (active.isEmpty()) {
            return;
        }
        Tool tool = active.get();
        ToolContext view = ctx.contextFor(tool.descriptor().id());
        CursorPick pick = platform.pick(mouseX, mouseY, tool.raycastMode(view.settings()), looking.getAsBoolean(),
                tool.rayOverlay());
        ctx.setCursor(pick);
        tool.frame(view, new FrameInfo(nanoTime, tickDelta, mouseX, mouseY, pick.cursor()));
    }

    // ---- Tools ----

    /** Selects palette slot 1-9, or says why that tool can't be used. Returns true if it is now active. */
    public boolean selectSlot(int slot) {
        Optional<Tool> tool = ctx.tools().slot(slot);
        if (tool.isEmpty()) {
            return false;
        }
        Optional<Notice> blocked = unavailability(tool.get());
        if (blocked.isPresent()) {
            ctx.notify(blocked.get());
            return false;
        }
        activate(tool.get().descriptor().id());
        return true;
    }

    /** Why a tool can't be selected right now (not built yet, or no permission), as a toast. */
    public Optional<Notice> unavailability(Tool tool) {
        String name = translator.translate(tool.descriptor().nameKey());
        Optional<String> milestone = EditorToolSet.comingSoon(tool.descriptor().id());
        if (milestone.isPresent()) {
            return Optional.of(Notice.of(Notice.Level.INFO, "sculptory.notice.coming_soon", name, milestone.get()));
        }
        Optional<EditorSession> session = ctx.session();
        if (session.isPresent() && !session.get().permissions().has(tool.descriptor().permission())) {
            return Optional.of(Notice.of(Notice.Level.WARNING, "sculptory.notice.tool_needs_permission", name,
                    tool.descriptor().permission().node()));
        }
        return Optional.empty();
    }

    private Optional<ToolId> firstUsable() {
        for (Tool tool : ctx.tools().paletteOrder()) {
            if (unavailability(tool).isEmpty()) {
                return Optional.of(tool.descriptor().id());
            }
        }
        return Optional.empty();
    }

    private void activate(ToolId id) {
        if (ctx.tools().activate(id, ctx.contextFor(id))) {
            ctx.setPointerCapture(false);
            ctx.setHoveredFace(null);
            ui.toolChanged();
        }
    }

    /** The hint line's entries: "Aiming at water and lava" while that is on, the active tool's hints, then help. */
    public List<KeyHint> hints() {
        List<KeyHint> hints = new ArrayList<>();
        if (platform.aimsAtFluids()) {
            hints.add(KeyHint.text("sculptory.hint.fluid_aim"));
        }
        ctx.tools().active().ifPresent(tool -> hints.addAll(tool.hints(ctx.contextFor(tool.descriptor().id()))));
        String help = keymap.display(KeyAction.HELP);
        if (!help.isEmpty()) {
            hints.add(new KeyHint(help, "sculptory.hint.help"));
        }
        return hints;
    }

    // ---- InputRouter.Tools ----

    @Override
    public boolean pointer(PointerEvent.Kind kind, int button, double x, double y, int modifiers) {
        Optional<Tool> active = ctx.tools().active();
        if (active.isEmpty()) {
            return false;
        }
        Tool tool = active.get();
        ToolContext view = ctx.contextFor(tool.descriptor().id());
        int held = kind == PointerEvent.Kind.PRESS ? modifiers : ctx.modifiers();
        CursorPick pick = platform.pick(x, y, tool.raycastMode(view.settings()), looking.getAsBoolean(),
                tool.rayOverlay());
        ctx.setCursor(pick);
        return tool.onPointer(view, new PointerEvent(kind, button, x, y, held, pick.cursor()));
    }

    @Override
    public boolean scroll(double amount, int modifiers) {
        return ctx.tools().active()
                .map(tool -> tool.onScroll(ctx.contextFor(tool.descriptor().id()), new ScrollEvent(amount, modifiers)))
                .orElse(false);
    }

    @Override
    public boolean takesScroll(int modifiers) {
        return ctx.tools().active()
                .map(tool -> tool.takesScroll(ctx.contextFor(tool.descriptor().id()), modifiers))
                .orElse(false);
    }

    @Override
    public boolean action(EditorAction action, int modifiers) {
        return ctx.tools().active()
                .map(tool -> tool.onAction(ctx.contextFor(tool.descriptor().id()), action))
                .orElse(false);
    }

    @Override
    public boolean hasPointerCapture() {
        return ctx.pointerCapture() && ctx.tools().active().isPresent();
    }

    // ---- InputRouter.Commands ----

    @Override
    public void run(KeyAction action, int modifiers) {
        if (action.toolSlot() > 0) {
            selectSlot(action.toolSlot());
            return;
        }
        switch (action) {
            case UNDO -> undo();
            case REDO -> redo();
            case ERASE_SELECTION -> selection.erase();
            case DESELECT -> selection.deselect();
            case NUDGE_FORWARD, NUDGE_BACK, NUDGE_LEFT, NUDGE_RIGHT, NUDGE_UP, NUDGE_DOWN ->
                    action.editorAction().ifPresent(nudge ->
                            selection.nudge(nudge, Modifiers.shift(modifiers) ? 10 : 1, platform.cameraYaw()));
            case COPY -> clipboard().ifPresent(actions -> actions.copySelection(false));
            case CUT -> clipboard().ifPresent(actions -> actions.copySelection(true));
            case PASTE -> clipboard().ifPresent(ClipboardActions::paste);
            case ROTATE_CW, ROTATE_CCW, FLIP_LEFT_RIGHT, FLIP_FRONT_BACK, FLIP_UPSIDE_DOWN ->
                    ctx.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.transform_needs_place",
                            keymap.display(KeyAction.PASTE)));
            case LIBRARY -> ui.toggleWindow(LIBRARY_WINDOW);
            case HIDE_WINDOWS -> ui.toggleWindowsHidden();
            case HELP -> ui.toggleHelp();
            case HISTORY -> ui.toggleWindow(HISTORY_WINDOW);
            case UI_SMALLER -> ui.stepUiSize(-1);
            case UI_LARGER -> ui.stepUiSize(1);
            case UI_RESET -> ui.resetUiSize();
            case AIM_AT_FLUIDS -> setAimAtFluids(!aimsAtFluids());
            case COMMAND_SEARCH -> ui.openCommandSearch();
            case FOCUS_NEXT_WINDOW -> ui.focusNextWindow(!Modifiers.shift(modifiers));
            case JUMP -> jump(false);
            case JUMP_THROUGH -> jump(true);
            case TOGGLE_MASK -> toggleMask();
            default -> {
                // COMMIT with nothing to commit, and chords routed elsewhere (scroll, middle-click).
            }
        }
    }

    /**
     * The global mask on or off, keeping its rules (Ctrl+M). Without rules it opens
     * the Mask window to add one instead.
     */
    public void toggleMask() {
        EditMaskModel mask = EditMaskModel.global();
        if (mask.rules().isEmpty()) {
            ctx.notify(Notice.of(Notice.Level.INFO, MaskChip.NOTICE_NO_RULES));
            ui.toggleWindow(EditorWindows.MASK);
            return;
        }
        ctx.notify(Notice.of(Notice.Level.INFO, mask.toggle() ? MaskChip.NOTICE_ON : MaskChip.NOTICE_OFF));
    }

    /**
     * Jump ({@code through} false: onto the block looked at) or Through (past the wall looked at) (J, Shift+J): what the cursor (the crosshair while looking) is on, sent by
     * {@link #setNavigator the navigator}.
     */
    public void jump(boolean through) {
        navigator.accept(platform.pick(pointerX, pointerY, RaycastMode.TERRAIN, looking.getAsBoolean(), null), through);
    }

    /** Where {@link #jump} sends its pick (client/nav/Navigation); nothing without one. */
    public void setNavigator(java.util.function.BiConsumer<CursorPick, Boolean> navigator) {
        this.navigator = Objects.requireNonNull(navigator);
    }

    /**
     * Runs a keymap action exactly as its key does: the active tool gets its tool action first, then the editor runs
     * it (the menu bar and the command search use this for the commands that stand for a key).
     */
    public void runKeyAction(KeyAction action) {
        Optional<EditorAction> toolAction = action.editorAction();
        if (toolAction.isPresent() && action(toolAction.get(), 0)) {
            return;
        }
        run(action, 0);
    }

    /**
     * Undo (Ctrl+Z, the History window button). "Nothing to undo" is answered here when the history is idle; the
     * "Undo: label" toast comes from the session once the server accepts the step. While a step is in flight the
     * session queues the press (the mirror lags behind it then, so it is not consulted).
     */
    public void undo() {
        Optional<EditorSession> session = ctx.session();
        if (session.isEmpty()) {
            return;
        }
        HistoryMirror history = session.get().history();
        if (!session.get().historyBusy() && !history.canUndo()) {
            ctx.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_to_undo"));
            return;
        }
        session.get().undo();
    }

    /** Redo; see {@link #undo}. */
    public void redo() {
        Optional<EditorSession> session = ctx.session();
        if (session.isEmpty()) {
            return;
        }
        HistoryMirror history = session.get().history();
        if (!session.get().historyBusy() && !history.canRedo()) {
            ctx.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.nothing_to_redo"));
            return;
        }
        session.get().redo();
    }

    /** Whether tools aim at water and lava surfaces ("Aim at water and lava"). */
    public boolean aimsAtFluids() {
        return platform.aimsAtFluids();
    }

    /**
     * Turns "Aim at water and lava" on or off (its key, the top bar button) for every tool and the eyedropper, and
     * says so. Only the cursor pick changes; the next frame's pick uses it.
     */
    public void setAimAtFluids(boolean aim) {
        if (aim == platform.aimsAtFluids()) {
            return;
        }
        platform.setAimAtFluids(aim);
        ctx.notify(Notice.of(Notice.Level.INFO,
                aim ? "sculptory.notice.fluid_aim_on" : "sculptory.notice.fluid_aim_off"));
    }

    @Override
    public void flySpeed(double amount, int modifiers) {
        if (ctx.flySpeed().step(amount > 0 ? 1 : -1)) {
            platform.applyFlySpeed(ctx.flySpeed().multiplier());
        }
    }

    @Override
    public void eyedropper(double x, double y) {
        CursorPick pick = platform.pick(x, y, RaycastMode.BLOCKS, looking.getAsBoolean(), null);
        if (pick.cursor().missed()) {
            return;
        }
        platform.blockAt(pick.cursor().pos()).ifPresent(block -> {
            Optional<ScatterTool> scatter = activeScatterTool();
            if (scatter.isPresent()) {
                scatter.get().addBlock(block); // the Scatter tool takes it into its mix instead (with its own toast)
                return;
            }
            ctx.setActiveBlock(block);
            ctx.notify(Notice.of(Notice.Level.INFO, "sculptory.notice.picked_block", platform.blockName(block)));
        });
    }

    @Override
    public void exitEditor() {
        mode.escape();
    }

    @Override
    public boolean systemKey(int key, int scanCode) {
        switch (platform.systemKey(key, scanCode)) {
            case TOGGLE_EDITOR -> mode.toggle();
            case CHAT -> mode.suspend(EditorMode.SuspendTarget.CHAT);
            case COMMAND -> mode.suspend(EditorMode.SuspendTarget.COMMAND);
            case INVENTORY -> mode.suspend(EditorMode.SuspendTarget.INVENTORY);
            case NONE -> {
                return false;
            }
        }
        return true;
    }
}
