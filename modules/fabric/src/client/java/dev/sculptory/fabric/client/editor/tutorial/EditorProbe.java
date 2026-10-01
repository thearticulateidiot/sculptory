package dev.sculptory.fabric.client.editor.tutorial;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.EditorController;
import dev.sculptory.fabric.client.editor.EditorPlatform;
import dev.sculptory.fabric.client.editor.brush.StrokeController;
import dev.sculptory.fabric.client.editor.clipboard.ClipboardActions;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.presets.Presets;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.Selection;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tools.brush.ShapeBrushTool;
import dev.sculptory.fabric.client.editor.tools.brush.TerrainBrushTool;
import dev.sculptory.fabric.client.editor.tools.generate.GenerateTool;
import dev.sculptory.fabric.client.editor.tools.place.PlaceTool;
import dev.sculptory.fabric.client.editor.tools.tinker.TinkerTool;
import dev.sculptory.fabric.client.editor.windows.ExportDialog;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.tinker.TinkerController;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The tutorial's view of the real editor. Most of it reads the editor's state as it is; the selection's changes come
 * from its listener, the history's from {@link LessonEdits}, and brush strokes are counted by {@link #frame} from each
 * brush's own stroke count (a stroke is over once its press is let go), so a click begun and ended between two frames
 * still counts.
 */
public final class EditorProbe implements TutorialProbe {
    private final EditorUi ui;
    private final EditorContext ctx;
    private final EditorController controller;
    private final EditorPlatform platform;
    private final LessonEdits edits;
    private final Map<ToolId, StrokeCount> strokes = new HashMap<>();
    private long selectionChanges;
    private long editorEntries;
    private boolean looking;
    private BooleanSupplier builderPowerOn = () -> false;

    public EditorProbe(EditorUi ui, EditorContext ctx, EditorController controller, EditorPlatform platform,
            LessonEdits edits) {
        this.ui = Objects.requireNonNull(ui);
        this.ctx = Objects.requireNonNull(ctx);
        this.controller = Objects.requireNonNull(controller);
        this.platform = Objects.requireNonNull(platform);
        this.edits = Objects.requireNonNull(edits);
        ctx.onSelectionChanged(() -> selectionChanges++);
    }

    /** Once per frame, before the conditions are asked: the look state and the brushes' strokes. */
    public void frame(boolean looking) {
        this.looking = looking;
        for (Tool tool : ctx.tools().paletteOrder()) {
            StrokeController brush = strokeController(tool);
            if (brush == null) {
                continue;
            }
            strokes.computeIfAbsent(tool.descriptor().id(), id -> new StrokeCount())
                    .observe(brush.strokesBegun(), brush.pressed());
        }
    }

    /**
     * Counts a brush's ended strokes from its own count of strokes begun (restarts mid-press included), read once per
     * frame: the strokes begun since the last count are ended once the press is let go. What was begun before the
     * first frame seen doesn't count.
     */
    static final class StrokeCount {
        private int seen = -1;
        private long ended;

        void observe(int begun, boolean pressed) {
            if (seen < 0) {
                seen = begun;
            }
            if (begun > seen && !pressed) {
                ended += begun - seen;
                seen = begun;
            }
        }

        long ended() {
            return ended;
        }
    }

    /** The editor opened. */
    public void editorShown() {
        editorEntries++;
    }

    private static StrokeController strokeController(Tool tool) {
        if (tool instanceof TerrainBrushTool brush) {
            return brush.controller();
        }
        if (tool instanceof ShapeBrushTool shape) {
            return shape.controller();
        }
        return null;
    }

    @Override
    public List<ToolId> tools() {
        return ctx.tools().paletteOrder().stream().map(tool -> tool.descriptor().id()).toList();
    }

    @Override
    public Optional<ToolId> activeTool() {
        return ctx.tools().active().map(tool -> tool.descriptor().id());
    }

    @Override
    public SettingsValues settings(ToolId tool) {
        return ctx.settings(tool);
    }

    @Override
    public Optional<Selection> selection() {
        return ctx.selectionState();
    }

    @Override
    public long selectionChanges() {
        return selectionChanges;
    }

    @Override
    public BlockDescriptor activeBlock() {
        return ctx.activeBlock();
    }

    @Override
    public double flySpeed() {
        return ctx.flySpeed().multiplier();
    }

    @Override
    public boolean looking() {
        return looking;
    }

    @Override
    public Optional<double[]> eye() {
        return platform.eye();
    }

    @Override
    public int uiSizePercent() {
        return ui.uiScale().percent();
    }

    @Override
    public int openMenu() {
        return ui.menuBar().isMenuOpen() ? ui.menuBar().openIndex() : -1;
    }

    @Override
    public boolean windowShown(String windowId) {
        return ui.windows().isOpen(windowId) && !ui.windows().isAllHidden();
    }

    @Override
    public boolean windowsHidden() {
        return ui.windows().isAllHidden();
    }

    @Override
    public boolean keySheetOpen() {
        return ui.isHelpOpen();
    }

    @Override
    public boolean commandSearchOpen() {
        return ui.commandSearch().isOpen();
    }

    @Override
    public boolean keyboardInWindow() {
        return ui.windows().hasKeyboardFocus() && !ui.windows().context().popups().isOpen();
    }

    @Override
    public long editorEntries() {
        return editorEntries;
    }

    @Override
    public long historyPushes() {
        return edits.pushes();
    }

    @Override
    public long historyUndos() {
        return edits.undos();
    }

    @Override
    public long historyRedos() {
        return edits.redos();
    }

    @Override
    public Optional<Object> clipboard() {
        return ctx.session().flatMap(session -> session.clipboards().current()).map(Object.class::cast);
    }

    @Override
    public long libraryChanges() {
        return ctx.session().map(EditorSession::libraryChanges).map(changes -> changes.version()).orElse(0L);
    }

    @Override
    public int presetsVersion() {
        return ui.toolSettings().presets().map(Presets::version).orElse(0);
    }

    @Override
    public long strokesEnded(ToolId tool) {
        StrokeCount count = strokes.get(tool);
        return count == null ? 0 : count.ended();
    }

    @Override
    public boolean symmetryCentreSet() {
        return ctx.tools().paletteOrder().stream()
                .filter(TerrainBrushTool.class::isInstance)
                .map(TerrainBrushTool.class::cast)
                .findFirst()
                .map(brush -> brush.symmetryCentre().isSet())
                .orElse(false);
    }

    @Override
    public int pathNodes() {
        return ctx.tools().get(ToolId.GENERATE)
                .filter(GenerateTool.class::isInstance)
                .map(tool -> ((GenerateTool) tool).path().size())
                .orElse(0);
    }

    @Override
    public boolean scatterAreaPainted() {
        return controller.scatterTool().map(scatter -> !scatter.area().isEmpty()).orElse(false);
    }

    @Override
    public int scatterMixSize() {
        return controller.scatterTool().map(scatter -> scatter.mix().size()).orElse(0);
    }

    // ---- Tinker, mix patterns, the flip, builder mode, exports ----

    /** Builder mode's powers live outside the editor; the client passes in whether any is on. */
    public void setBuilderPowerOn(BooleanSupplier on) {
        this.builderPowerOn = Objects.requireNonNull(on);
    }

    @Override
    public boolean tinkerAimsAtProperty() {
        return ctx.tools().get(ToolId.TINKER)
                .filter(TinkerTool.class::isInstance)
                .map(tool -> ((TinkerTool) tool).controller())
                .filter(tinker -> tinker.hovered().filter(TinkerController.BlockTarget.class::isInstance).isPresent())
                .map(TinkerController::takesScroll)
                .orElse(false);
    }

    @Override
    public boolean gradientLineSet() {
        return ctx.tools().paletteOrder().stream()
                .filter(TerrainBrushTool.class::isInstance)
                .map(TerrainBrushTool.class::cast)
                .findFirst()
                .map(brush -> brush.symmetryCentre().gradientLine().isSet())
                .orElse(false);
    }

    @Override
    public boolean placing() {
        return placeTool().map(PlaceTool::placing).orElse(false);
    }

    @Override
    public boolean placementUpsideDown() {
        return placeTool().flatMap(PlaceTool::placement).map(placement -> placement.transform().upsideDown())
                .orElse(false);
    }

    private Optional<PlaceTool> placeTool() {
        return ctx.tools().get(ToolId.PLACE).filter(PlaceTool.class::isInstance).map(PlaceTool.class::cast);
    }

    @Override
    public boolean builderPowerOn() {
        return builderPowerOn.getAsBoolean();
    }

    @Override
    public boolean exportDialogOpen() {
        return ExportDialog.bounds(ui.windows().context().popups()).isPresent();
    }

    @Override
    public long exports() {
        return controller.clipboard().map(ClipboardActions::exports).orElse(0L);
    }
}
