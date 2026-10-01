package dev.sculptory.fabric.client.editor.tour;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.builder.BuilderClient;
import dev.sculptory.fabric.client.builder.RingScreen;
import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.EditorController;
import dev.sculptory.fabric.client.editor.ExitReason;
import dev.sculptory.fabric.client.editor.commands.Availability;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.settings.form.SettingsForm;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.PopupLayer;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.editor.ui.UiOpacity;
import dev.sculptory.fabric.client.editor.ui.UiScale;
import dev.sculptory.fabric.client.editor.ui.layout.Spacer;
import dev.sculptory.fabric.client.editor.ui.widget.CollapsibleSection;
import dev.sculptory.fabric.client.editor.ui.widget.ScrollPane;
import dev.sculptory.fabric.client.editor.ui.widget.Tooltip;
import dev.sculptory.fabric.client.editor.ui.window.Corner;
import dev.sculptory.fabric.client.editor.ui.window.LayoutState;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.ui.window.WindowManager;
import dev.sculptory.fabric.client.editor.windows.EditorWindows;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import org.lwjgl.glfw.GLFW;

/**
 * What the tour's steps work on: the real editor objects, plus small helpers that put the editor into a state the way
 * a player would get there (select a tool, open a window or menu, point at something). Every helper throws when the
 * state can't be reached, so the step is recorded as failed instead of taking a misleading picture. Client thread
 * only.
 */
public final class TourContext {
    /** Where the pointer rests between steps, as fractions of the screen: over the world, clear of the bars. */
    private static final double PARK_X = 0.5;
    private static final double PARK_Y = 0.55;
    /** The fixed view of the world behind the UI (degrees; the player is not moved). */
    private static final float YAW = 45f;
    static final float PITCH = 25f;
    /**
     * The wiki pictures look further down, so the ground under the pointer is nearer and a selection or a brush fills
     * more of its crop.
     */
    static final float WIKI_PITCH = 50f;
    /** Room left around a cropped window, menu or card (UI units), so its border and shadow show. */
    private static final int CROP_MARGIN = 3;

    private final MinecraftClient client;
    private final EditorClient editor;
    private SizedLayouts savedLayouts;
    private int savedUiPercent = UiScale.DEFAULT_PERCENT;
    /** View > Opacityâ€¦ as the player had it: every step starts from it, and it is put back at the end. */
    private UiOpacity.Values savedOpacity = UiOpacity.Values.DEFAULT;

    /** The UI size every step starts from. */
    private final int baseUiPercent;
    /** The window layouts every step starts from, one per UI size ({@link SizedLayouts#EMPTY}: the defaults). */
    private final SizedLayouts baseLayouts;
    /** The view's pitch (degrees down). */
    private final float pitch;

    TourContext(MinecraftClient client, EditorClient editor, int baseUiPercent, SizedLayouts baseLayouts,
            float pitch) {
        this.client = Objects.requireNonNull(client);
        this.editor = Objects.requireNonNull(editor);
        this.baseUiPercent = baseUiPercent;
        this.baseLayouts = Objects.requireNonNull(baseLayouts);
        this.pitch = pitch;
    }

    public MinecraftClient client() {
        return client;
    }

    public EditorClient editor() {
        return editor;
    }

    public EditorUi ui() {
        return editor.ui();
    }

    public EditorController controller() {
        return editor.controller();
    }

    public EditorContext editorContext() {
        return controller().context();
    }

    // ---- Tour lifecycle ----

    /** Remembers the window layouts (every UI size's) and UI size the player had, for {@link #restoreState}. */
    void saveState() {
        savedLayouts = ui().windows().layouts();
        savedUiPercent = ui().uiScale().percent();
        savedOpacity = ui().uiOpacity().values();
    }

    /** Puts back the player's window layouts and UI size and lets go of the pointer. */
    void restoreState() {
        ensureEditor();
        restoreView();
        editor.unpinPointer();
        ui().windows().context().popups().closeAll();
        showHelp(false);
        ui().uiScale().set(savedUiPercent);
        ui().uiOpacity().set(savedOpacity);
        if (savedLayouts != null) {
            ui().windows().restore(savedLayouts, savedUiPercent);
        }
    }

    /**
     * The state every step starts from: no popup, key sheet or quick start card, the base window layouts (the main
     * checkout's or the default ones) and UI size, the pointer parked over the world, no vanilla toasts, the fixed view. The
     * active tool and the selection carry over. A step about another UI size ({@code uiPercent}, not
     * {@link TourStep#BASE_UI_SIZE}) then switches to it, as the player would from the base size: the windows take
     * that size's arrangement in the base layouts, or the default layout.
     */
    void reset(int uiPercent) {
        ensureEditor();
        EditorUi ui = ui();
        ui.windows().context().popups().closeAll();
        ui.windows().context().clearFocus();
        showHelp(false);
        ui.quickStart().hide();
        ui.uiScale().set(baseUiPercent);
        ui.uiOpacity().set(savedOpacity);
        ui.windows().restore(baseLayouts, baseUiPercent);
        relayout();
        if (uiPercent != TourStep.BASE_UI_SIZE && uiPercent != baseUiPercent) {
            setUiSize(uiPercent);
        }
        client.getToastManager().clear();
        look();
        pointAt(PARK_X, PARK_Y);
    }

    /** Turns the player to the tour's fixed view (no teleport), or to the view {@link #overlook} set. */
    void look() {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        float yaw = overlookView == null ? YAW : overlookView[3];
        float lookPitch = overlookView == null ? pitch : overlookView[4];
        player.setYaw(yaw);
        player.setPitch(lookPitch);
        player.prevYaw = yaw;
        player.prevPitch = lookPitch;
        player.setHeadYaw(yaw);
    }

    /** The place and view {@link #overlook} put the player at (x, y, z, yaw, pitch), while it holds. */
    private float[] overlookView;
    /** Where the player stood before the first {@link #overlook} (x, y, z, yaw, pitch): put back at the end. */
    private float[] homeView;

    /**
     * Moves the player to a view down onto {@code box} from {@code height} blocks up and as many south of its centre,
     * so the box shows in the middle of the screen at a size a picture can read (the tour's fixed view is far above
     * the ground). Later resets keep this view; {@link #restoreState} puts the player back where they were. Through
     * the world's own server, so the client follows a moment later.
     */
    public void overlook(Box box, double height) {
        ClientPlayerEntity player = client.player;
        MinecraftServer server = client.getServer();
        if (player == null || server == null) {
            throw new IllegalStateException("Not in a singleplayer world: the tour can't move the player");
        }
        if (homeView == null) {
            homeView = new float[] {(float) player.getX(), (float) player.getY(), (float) player.getZ(),
                    player.getYaw(), player.getPitch()};
        }
        double tx = (box.min().x() + box.max().x()) / 2.0 + 0.5;
        double ty = box.min().y();
        double tz = (box.min().z() + box.max().z()) / 2.0 + 0.5;
        double ex = tx;
        double ey = ty + height;
        double ez = tz + height;
        double eyeY = ey + player.getStandingEyeHeight();
        float yaw = (float) (Math.toDegrees(Math.atan2(tz - ez, tx - ex)) - 90);
        float lookPitch = (float) -Math.toDegrees(Math.atan2(ty - eyeY, Math.hypot(tx - ex, tz - ez)));
        teleport(ex, ey, ez, yaw, lookPitch);
        overlookView = new float[] {(float) ex, (float) ey, (float) ez, yaw, lookPitch};
        look();
    }

    private void teleport(double x, double y, double z, float yaw, float lookPitch) {
        MinecraftServer server = client.getServer();
        ClientPlayerEntity player = client.player;
        if (server == null || player == null) {
            return;
        }
        java.util.UUID id = player.getUuid();
        server.execute(() -> {
            net.minecraft.server.network.ServerPlayerEntity serverPlayer = server.getPlayerManager().getPlayer(id);
            if (serverPlayer != null) {
                serverPlayer.getAbilities().flying = true;
                serverPlayer.sendAbilitiesUpdate();
                serverPlayer.networkHandler.requestTeleport(x, y, z, yaw, lookPitch);
            }
        });
    }

    /** Puts the player back where the tour found them, if {@link #overlook} moved them. */
    private void restoreView() {
        if (homeView != null) {
            teleport(homeView[0], homeView[1], homeView[2], homeView[3], homeView[4]);
            homeView = null;
        }
        overlookView = null;
    }

    // ---- Helpers for steps ----

    /** Activates a tool as its palette slot would. */
    public void selectTool(ToolId id) {
        if (!controller().selectTool(id)) {
            throw new IllegalStateException("Tool '" + id + "' could not be selected (missing or unavailable)");
        }
    }

    /** Opens a window (as the Windows menu does) and brings it to the front. */
    public void openWindow(String id) {
        if (ui().windows().window(id).isEmpty()) {
            throw new IllegalStateException("No window '" + id + "'");
        }
        if (!ui().windows().isOpen(id)) {
            ui().toggleWindow(id);
        }
        ui().windows().bringToFront(id);
    }

    /** Opens a top bar menu or picker (see {@link EditorUi#openMenu}). */
    public void openMenu(String id) {
        ui().openMenu(id);
        if (!ui().windows().context().popups().isOpen()) {
            throw new IllegalStateException("Menu '" + id + "' did not open");
        }
    }

    /** Opens Find a command (Ctrl+K) with {@code query} typed. */
    public void openCommandSearch(String query) {
        ui().openCommandSearch();
        ui().commandSearch().setQuery(query);
        if (!ui().commandSearch().isOpen()) {
            throw new IllegalStateException("Find a command did not open");
        }
    }

    /** Shows or hides the help sheet (F1). */
    public void showHelp(boolean shown) {
        if (ui().isHelpOpen() != shown) {
            ui().toggleHelp();
        }
    }

    /** Opens the key sheet (F1) with {@code filter} typed in its filter box. */
    public void showKeySheet(String filter) {
        showHelp(true);
        ui().keySheet().setFilter(filter);
        relayout();
    }

    /** Shows the quick start card (Help > Quick start). */
    public void showQuickStart() {
        ui().showQuickStart();
        if (!ui().quickStart().isShown()) {
            throw new IllegalStateException("The quick start card did not show");
        }
    }

    /**
     * Makes three real messages (aim at water and lava on and off, all windows hidden and shown again), then opens View
     * > Notifications and brings it to the front.
     */
    public void openNotificationsWithMessages() {
        EditorController controller = controller();
        controller.setAimAtFluids(!controller.aimsAtFluids());
        controller.setAimAtFluids(!controller.aimsAtFluids());
        ui().toggleWindowsHidden();
        ui().toggleWindowsHidden();
        openWindow(EditorWindows.NOTIFICATIONS);
        relayout();
    }

    /** Presses F6 once: the keyboard goes to the first control of the first window in screen order. */
    public void focusNextWindow() {
        relayout();
        ui().focusNextWindow(true);
        if (ui().windows().context().focused() == null) {
            throw new IllegalStateException("F6 focused nothing");
        }
    }

    /** Sets the editor UI size (one of {@link UiScale#steps()}), without the toast the keys show. */
    public void setUiSize(int percent) {
        if (!UiScale.steps().contains(percent)) {
            throw new IllegalArgumentException("Not a UI size: " + percent + "%");
        }
        ui().uiScale().set(percent);
        relayout();
    }

    /** Where the pointer is pinned, as fractions of the screen ({@link #pointAt}); the park point between steps. */
    private double pointerFracX = PARK_X;
    private double pointerFracY = PARK_Y;

    /** Puts the pointer at a fraction of the screen (0..1 across and down) and keeps it there. */
    public void pointAt(double fractionX, double fractionY) {
        pointerFracX = fractionX;
        pointerFracY = fractionY;
        editor.pinPointer(client.getWindow().getScaledWidth() * fractionX,
                client.getWindow().getScaledHeight() * fractionY);
    }

    /** Puts the pointer over the middle of a tool palette slot (its tooltip shows after the hover delay). */
    public void hoverPaletteSlot(int slot) {
        Rect bounds = ui().paletteSlotBounds(slot)
                .filter(rect -> !rect.isEmpty())
                .orElseThrow(() -> new IllegalStateException("Palette slot " + slot + " is not laid out"));
        float factor = ui().uiScale().factor();
        editor.pinPointer((bounds.x() + bounds.width() / 2.0) * factor, (bounds.y() + bounds.height() / 2.0) * factor);
    }

    /** Clears the selection. */
    public void clearSelection() {
        editorContext().setSelection(null);
    }

    /**
     * Selects a box of the given size standing on the block the pointer rests on (the editor's last cursor pick), so
     * it is in view.
     */
    public void selectBoxAtPointer(int sizeX, int sizeY, int sizeZ) {
        selectBoxAtPointer(sizeX, sizeY, sizeZ, 0);
    }

    /**
     * Selects a box of the given size around the block the pointer rests on, its bottom {@code down} blocks below that
     * block (a box reaching into the ground, so a copy of it holds terrain).
     */
    public void selectBoxAtPointer(int sizeX, int sizeY, int sizeZ, int down) {
        WorldCursor cursor = editorContext().cursor().cursor();
        if (cursor.missed()) {
            throw new IllegalStateException("The pointer is not over a block");
        }
        BlockPos min = cursor.pos().offset(-sizeX / 2, -down, -sizeZ / 2);
        editorContext().setSelection(Box.of(min, min.offset(sizeX - 1, sizeY - 1, sizeZ - 1)));
    }

    /**
     * Expands the Tool Settings section holding a setting and scrolls the window so the section's header is at the
     * top.
     */
    public void expandSectionOf(String settingKey) {
        EditorUi ui = ui();
        Window window = ui.windows().window(EditorWindows.TOOL_SETTINGS)
                .orElseThrow(() -> new IllegalStateException("No Tool Settings window"));
        window.content(); // builds the active tool's form now if the tool just changed
        SettingsForm form = ui.toolSettings().form()
                .orElseThrow(() -> new IllegalStateException("The active tool has no settings form"));
        Node field = form.field(settingKey)
                .orElseThrow(() -> new IllegalStateException("No setting '" + settingKey + "'"));
        CollapsibleSection section = ancestor(field, CollapsibleSection.class);
        section.setExpanded(true);
        relayout();
        ScrollPane pane = ancestor(section, ScrollPane.class);
        pane.scroll().setOffset(section.bounds().y() - pane.content().bounds().y());
    }

    /** The active tool's Tool Settings form, built and laid out now. */
    public SettingsForm toolSettingsForm() {
        Window window = ui().windows().window(EditorWindows.TOOL_SETTINGS)
                .orElseThrow(() -> new IllegalStateException("No Tool Settings window"));
        window.content(); // builds the active tool's form now if the tool just changed
        relayout();
        return ui().toolSettings().form()
                .orElseThrow(() -> new IllegalStateException("The active tool has no settings form"));
    }

    /**
     * Scrolls a node into view in its pane and puts the pointer over it, at a fraction of its width and height (0.5,
     * 0.5 is its middle), and keeps it there; its tooltip shows after the hover delay.
     */
    public void hover(Node node, double fractionX, double fractionY) {
        scrollIntoView(node);
        Rect bounds = node.bounds();
        if (bounds.isEmpty() || !node.isShown()) {
            throw new IllegalStateException(node.getClass().getSimpleName() + " is not shown");
        }
        float factor = ui().uiScale().factor();
        editor.pinPointer((bounds.x() + bounds.width() * fractionX) * factor,
                (bounds.y() + bounds.height() * fractionY) * factor);
    }

    /**
     * Scrolls a node into view in its pane (a tool left scrolled down), and lays out again so its bounds are where it
     * is drawn. A node outside any pane stays where it is.
     */
    public void scrollIntoView(Node node) {
        relayout();
        for (Node at = node.parent(); at != null; at = at.parent()) {
            if (at instanceof ScrollPane pane) {
                pane.scrollIntoView(node);
                relayout();
                relayout();
                break;
            }
        }
    }

    /** Sets one of a tool's decimal settings as its slider would (Tool Settings shows it changed, with its Ã¢â€ Âº). */
    public void setDecimalSetting(ToolId tool, String key, double value) {
        EditorContext editor = editorContext();
        SettingsValues values = editor.settings(tool);
        if (!(values.schema().def(key).orElse(null) instanceof SettingDef.Decimal def)) {
            throw new IllegalStateException(tool + " has no decimal setting '" + key + "'");
        }
        editor.updateSettings(tool, values.with(def, value));
        relayout();
    }

    /** Puts one of a tool's settings back to its default, as its Ã¢â€ Âº would. */
    public void resetSetting(ToolId tool, String key) {
        EditorContext editor = editorContext();
        SettingsValues values = editor.settings(tool);
        SettingDef<?> def = values.schema().def(key)
                .orElseThrow(() -> new IllegalStateException(tool + " has no setting '" + key + "'"));
        editor.updateSettings(tool, withDefault(values, def));
        relayout();
    }

    private static <T> SettingsValues withDefault(SettingsValues values, SettingDef<T> def) {
        return values.with(def, def.defaultValue());
    }

    /**
     * Opens a window and gives it this size (UI units) where it is drawn now, keeping the rest of the layout: it stays
     * anchored to the screen corner nearest where it opened (its default place, for a window the user hasn't placed)
     * and is then placed, as a resize by hand would leave it.
     */
    public void resizeWindow(String id, int width, int height) {
        openWindow(id);
        relayout();
        WindowManager windows = ui().windows();
        Rect drawn = windows.window(id).orElseThrow().rect();
        int screenWidth = windows.screenWidth();
        int screenHeight = windows.screenHeight();
        Corner corner = Corner.nearest(drawn, screenWidth, screenHeight);
        int offsetX = corner.offsetX(drawn.x(), drawn.width(), screenWidth);
        int offsetY = corner.offsetY(drawn.y(), drawn.height(), screenHeight);
        List<LayoutState.WindowState> states = new ArrayList<>();
        for (LayoutState.WindowState state : windows.snapshot().windows()) {
            states.add(state.id().equals(id)
                    ? new LayoutState.WindowState(id, true, false, corner, offsetX, offsetY, width, height)
                    : state);
        }
        windows.restore(new LayoutState(states));
        relayout();
    }


    /**
     * Sets one of a tool's choice settings to the option named {@code option} (its enum constant, "SPHERE") as its
     * buttons would.
     */
    public void setChoiceSetting(ToolId tool, String key, String option) {
        EditorContext editor = editorContext();
        SettingsValues values = editor.settings(tool);
        if (!(values.schema().def(key).orElse(null) instanceof SettingDef.Enum<?> def)) {
            throw new IllegalStateException(tool + " has no choice setting '" + key + "'");
        }
        editor.updateSettings(tool, withOption(values, def, option));
        relayout();
    }

    private static <E extends java.lang.Enum<E>> SettingsValues withOption(SettingsValues values,
            SettingDef.Enum<E> def, String option) {
        return values.with(def, java.lang.Enum.valueOf(def.type(), option));
    }

    /** Sets one of a tool's whole-number settings as its slider would. */
    public void setIntSetting(ToolId tool, String key, int value) {
        EditorContext editor = editorContext();
        SettingsValues values = editor.settings(tool);
        if (!(values.schema().def(key).orElse(null) instanceof SettingDef.Int def)) {
            throw new IllegalStateException(tool + " has no whole-number setting '" + key + "'");
        }
        editor.updateSettings(tool, values.with(def, value));
        relayout();
    }

    /**
     * Selects a box of the given size whose top layer is the ground under the pointer: the first full, solid block at or
     * below the block the pointer rests on that isn't a leaf or a log (so a copy of it holds earth or stone, not a
     * tree's crown and the air around it).
     */
    public void selectGroundBoxAtPointer(int sizeX, int sizeY, int sizeZ) {
        WorldCursor cursor = editorContext().cursor().cursor();
        ClientWorld world = client.world;
        if (cursor.missed() || world == null) {
            throw new IllegalStateException("The pointer is not over a block");
        }
        BlockPos top = groundUnder(cursor.pos(), world);
        BlockPos min = top.offset(-sizeX / 2, -(sizeY - 1), -sizeZ / 2);
        editorContext().setSelection(Box.of(min, min.offset(sizeX - 1, sizeY - 1, sizeZ - 1)));
    }

    /** Copies the selection into the clipboard, as Ctrl+C does (the server answers a few frames later). */
    public void copySelection() {
        if (editorContext().selection().isEmpty()) {
            throw new IllegalStateException("Nothing is selected to copy");
        }
        controller().run(KeyAction.COPY, 0);
    }

    /** Runs a menu command by id (File > Export clipboardâ€¦), as its menu row would. */
    public void runCommand(String id) {
        Availability result = ui().commands().run(id);
        if (!result.enabled()) {
            throw new IllegalStateException("Command '" + id + "' can't run now: " + result.reasonKey());
        }
        relayout();
    }

    /** Sets a tool's weighted-block list (a mix or palette) from block specs ("minecraft:stone"), each with a weight. */
    public void setMixSetting(ToolId tool, String key, List<String> specs, List<Integer> weights) {
        EditorContext editor = editorContext();
        SettingsValues values = editor.settings(tool);
        if (!(values.schema().def(key).orElse(null) instanceof SettingDef.WeightedBlocks def)) {
            throw new IllegalStateException(tool + " has no block-list setting '" + key + "'");
        }
        List<SettingDef.WeightedBlock> mix = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            mix.add(new SettingDef.WeightedBlock(BlockDescriptor.parse(specs.get(i)), weights.get(i)));
        }
        editor.updateSettings(tool, values.with(def, List.copyOf(mix)));
        relayout();
    }

    /** How far around the stairs the tour clears trees and other cover, and how high, so the view reaches them. */
    private static final int CLEARING_RADIUS = 15;
    private static final int CLEARING_HEIGHT = 18;

    /**
     * Puts a {@code size} x {@code size} square of oak stairs (facing south, the right way up) on the ground under the
     * pointer, through the world's own server (the tour runs in singleplayer), and returns its box. Everything above
     * the ground within {@link #CLEARING_RADIUS} is cleared first (the tour world's park point lies in a forest), so
     * the view reaches the stairs and the pointer rests on one of them. The tour world keeps the clearing and the
     * stairs; the next run puts the same ones there again. The blocks change on the server's next tick: a step that
     * needs them there (a copy) runs after the step that placed them.
     */
    public Box placeStairsUnderPointer(int size) {
        WorldCursor cursor = editorContext().cursor().cursor();
        MinecraftServer server = client.getServer();
        ClientWorld world = client.world;
        if (cursor.missed() || world == null) {
            throw new IllegalStateException("The pointer is not over a block");
        }
        if (server == null) {
            throw new IllegalStateException("Not in a singleplayer world: the tour can't place blocks");
        }
        BlockPos top = groundUnder(cursor.pos(), world);
        BlockPos min = top.offset(-size / 2, 1, -size / 2);
        Box box = Box.of(min, min.offset(size - 1, 0, size - 1));
        ServerWorld serverWorld = server.getWorld(world.getRegistryKey());
        if (serverWorld == null) {
            throw new IllegalStateException("The server has no world for the client's dimension");
        }
        BlockState stairs = Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.SOUTH);
        BlockState air = Blocks.AIR.getDefaultState();
        server.execute(() -> {
            net.minecraft.util.math.BlockPos.Mutable at = new net.minecraft.util.math.BlockPos.Mutable();
            for (int x = top.x() - CLEARING_RADIUS; x <= top.x() + CLEARING_RADIUS; x++) {
                for (int z = top.z() - CLEARING_RADIUS; z <= top.z() + CLEARING_RADIUS; z++) {
                    for (int y = top.y() + 1; y <= top.y() + CLEARING_HEIGHT; y++) {
                        at.set(x, y, z);
                        if (!serverWorld.getBlockState(at).isAir()) {
                            serverWorld.setBlockState(at, air, Block.NOTIFY_LISTENERS);
                        }
                    }
                }
            }
            for (int x = box.min().x(); x <= box.max().x(); x++) {
                for (int z = box.min().z(); z <= box.max().z(); z++) {
                    serverWorld.setBlockState(new net.minecraft.util.math.BlockPos(x, box.min().y(), z), stairs,
                            Block.NOTIFY_LISTENERS);
                }
            }
        });
        return box;
    }

    /** The topmost full solid block at or under {@code from} that isn't a leaf or a log (see {@link #selectGroundBoxAtPointer}). */
    private static BlockPos groundUnder(BlockPos from, ClientWorld world) {
        for (int down = 0; down < 64; down++) {
            BlockPos top = from.offset(0, -down, 0);
            net.minecraft.util.math.BlockPos at = new net.minecraft.util.math.BlockPos(top.x(), top.y(), top.z());
            BlockState state = world.getBlockState(at);
            if (!state.isAir() && state.isOpaqueFullCube(world, at) && !state.isIn(BlockTags.LEAVES)
                    && !state.isIn(BlockTags.LOGS)) {
                return top;
            }
        }
        throw new IllegalStateException("No ground within 64 blocks under the pointer");
    }

    // ---- Builder mode (outside the editor) ----

    /**
     * Leaves the editor and holds the builder ring key (G) so the ring of powers shows over the world; the key stays
     * held (pinned, as the play check does it) until {@link #ensureEditor} lets go of it, which every step's reset and
     * the end of the tour do.
     */
    public void openRing() {
        BuilderClient builder = BuilderClient.instance()
                .orElseThrow(() -> new IllegalStateException("Builder mode is not initialised"));
        editor.mode().exit(ExitReason.TOGGLED);
        client.setScreen(null);
        builder.pinRingKey(true);
        ringKey(GLFW.GLFW_PRESS);
    }

    /** Lets go of the ring key, switches every power off again and reopens the editor if it is closed. */
    void ensureEditor() {
        BuilderClient.instance().ifPresent(builder -> {
            if (builder.ringKeyPinned()) {
                builder.pinRingKey(false);
                ringKey(GLFW.GLFW_RELEASE);
                builder.powers().clear();
                client.inGameHud.setOverlayMessage(Text.empty(), false);
            }
        });
        if (client.currentScreen instanceof RingScreen) {
            client.setScreen(null);
        }
        if (!editor.mode().isEditing() && !editor.mode().enter()) {
            throw new IllegalStateException("The editor did not reopen");
        }
    }

    private void ringKey(int glfwAction) {
        long window = client.getWindow().getHandle();
        client.keyboard.onKey(window, GLFW.GLFW_KEY_G, GLFW.glfwGetKeyScancode(GLFW.GLFW_KEY_G), glfwAction, 0);
    }

    /**
     * The ring of powers while it is open: centred on the screen, its radius 28% of the screen's smaller side (as
     * {@code BuilderClient} opens it), with room for the chips' names around it.
     */
    public Rect ringArea() {
        if (!(client.currentScreen instanceof RingScreen)) {
            throw new IllegalStateException("The ring of powers is not open (screen: " + client.currentScreen + ")");
        }
        double width = client.getWindow().getScaledWidth();
        double height = client.getWindow().getScaledHeight();
        double radius = Math.max(40, Math.min(width, height) * 0.28);
        double halfWidth = radius * 1.3;
        double halfHeight = radius * 1.12;
        float factor = ui().uiScale().factor();
        return Rect.ofEdges((int) Math.floor((width / 2 - halfWidth) / factor),
                (int) Math.floor((height / 2 - halfHeight) / factor),
                (int) Math.ceil((width / 2 + halfWidth) / factor), (int) Math.ceil((height / 2 + halfHeight) / factor));
    }

    // ---- Crops for the wiki pictures, in UI units (see TourStep#cropped) ----

    /** The whole screen. */
    public Rect screenArea() {
        relayout();
        return new Rect(0, 0, ui().uiWidth(), ui().uiHeight());
    }

    /** An open window, with a small margin. */
    public Rect windowArea(String id) {
        relayout();
        Window window = ui().windows().window(id)
                .orElseThrow(() -> new IllegalStateException("No window '" + id + "'"));
        if (!window.isOpen() || window.rect().isEmpty()) {
            throw new IllegalStateException("The " + id + " window is not open");
        }
        return TourCrop.grow(window.rect(), CROP_MARGIN);
    }

    /** An open window from its top down to {@code below} UI units under {@code node} (a row near its top). */
    public Rect windowTopArea(String id, Node node, int below) {
        Rect window = windowArea(id);
        Rect last = shownBounds(node);
        return Rect.ofEdges(window.x(), window.y(), window.right(), Math.min(window.bottom(), last.bottom() + below));
    }

    /** The width of an open window, from {@code above} UI units over {@code node} to {@code below} under it. */
    public Rect windowRowsArea(String id, Node node, int above, int below) {
        Rect window = windowArea(id);
        Rect row = shownBounds(node);
        return Rect.ofEdges(window.x(), Math.max(window.y(), row.y() - above), window.right(),
                Math.min(window.bottom(), row.bottom() + below));
    }

    /** The open menus, pickers and popups (Find a command too), with what they hang from (a menu title, a button). */
    public Rect popupArea() {
        relayout();
        List<PopupLayer.Popup> popups = ui().windows().context().popups().popups();
        if (popups.isEmpty()) {
            throw new IllegalStateException("No menu or popup is open");
        }
        Rect area = Rect.EMPTY;
        for (PopupLayer.Popup popup : popups) {
            area = TourCrop.union(area, popup.rect(), popup.anchor());
        }
        return TourCrop.grow(area, CROP_MARGIN);
    }

    /**
     * The top bar's items on the left half of the screen (the menus, the active block, Undo and Redo) or on the right
     * (the water toggle, fly speed, status and UI size), the bar's full height.
     */
    public Rect topBarArea(boolean left) {
        relayout();
        Node row = ui().topBarRow();
        int middle = ui().uiWidth() / 2;
        Rect area = Rect.EMPTY;
        for (Node child : row.children()) {
            Rect bounds = child.bounds();
            if (child instanceof Spacer || !child.isShown() || bounds.isEmpty()) {
                continue;
            }
            if ((bounds.x() + bounds.width() / 2 < middle) == left) {
                area = TourCrop.union(area, bounds);
            }
        }
        if (area.isEmpty()) {
            throw new IllegalStateException("The top bar shows nothing on the " + (left ? "left" : "right"));
        }
        Rect bar = row.bounds();
        return TourCrop.grow(Rect.ofEdges(area.x(), bar.y(), area.right(), bar.bottom()), CROP_MARGIN);
    }

    /** The tool palette and the hint line above it. */
    public Rect paletteArea() {
        relayout();
        Rect area = Rect.EMPTY;
        for (int slot = 1; slot <= ToolRegistry.PALETTE_SLOTS; slot++) {
            area = TourCrop.union(area, ui().paletteSlotBounds(slot).orElse(Rect.EMPTY));
        }
        if (area.isEmpty()) {
            throw new IllegalStateException("The palette is not laid out");
        }
        return TourCrop.grow(TourCrop.union(area, ui().hintLine().bounds()), CROP_MARGIN);
    }

    /** The key sheet (F1). */
    public Rect keySheetArea() {
        relayout();
        Rect sheet = ui().keySheet().bounds();
        if (!ui().isHelpOpen() || sheet.isEmpty()) {
            throw new IllegalStateException("The key sheet is not open");
        }
        return TourCrop.grow(sheet, CROP_MARGIN);
    }

    /** The quick start card. */
    public Rect quickStartArea() {
        if (!ui().quickStart().isShown()) {
            throw new IllegalStateException("The quick start card is not shown");
        }
        return TourCrop.grow(shownBounds(ui().quickStart().node()), CROP_MARGIN);
    }

    /** A {@code width} Ãƒâ€” {@code height} box of the screen around where the pointer rests (the world under it). */
    public Rect pointerArea(int width, int height) {
        return pointerArea(width, height, 0.5, 0.5);
    }

    /**
     * The world around the pinned pointer (parked, or moved by {@link #pointAt}), with the pointer at {@code pointerX} across and {@code pointerY} down the
     * crop (0..1): a label that hangs to the lower right of the pointer wants it nearer the top left.
     */
    public Rect pointerArea(int width, int height, double pointerX, double pointerY) {
        relayout();
        int x = (int) Math.round(ui().uiWidth() * pointerFracX);
        int y = (int) Math.round(ui().uiHeight() * pointerFracY);
        return new Rect(x - (int) Math.round(width * pointerX), y - (int) Math.round(height * pointerY), width, height);
    }

    /**
     * Where the tooltip of {@code node} shows while the pointer rests at a fraction of it (as {@link #hover} puts it):
     * the same box the window manager draws.
     */
    public Rect tooltipArea(Node node, double fractionX, double fractionY) {
        String text = null;
        for (Node at = node; at != null && text == null; at = at.parent()) {
            text = at.tooltip();
        }
        if (text == null || text.isBlank()) {
            throw new IllegalStateException(node.getClass().getSimpleName() + " has no tooltip");
        }
        Rect bounds = shownBounds(node);
        UiContext ctx = ui().windows().context();
        int mouseX = (int) (bounds.x() + bounds.width() * fractionX);
        int mouseY = (int) (bounds.y() + bounds.height() * fractionY);
        List<String> lines = Tooltip.lines(ctx.text(), ctx.theme(), text);
        return TourCrop.grow(Tooltip.bounds(ctx.text(), ctx.theme(), lines, mouseX, mouseY, ctx.screenWidth(),
                ctx.screenHeight()), CROP_MARGIN);
    }

    private static Rect shownBounds(Node node) {
        Rect bounds = node.bounds();
        if (bounds.isEmpty() || !node.isShown()) {
            throw new IllegalStateException(node.getClass().getSimpleName() + " is not shown");
        }
        return bounds;
    }

    /**
     * Lays the UI out now for the current screen and UI size (the editor does it at the next frame), so a step can
     * use positions right after changing the size.
     */
    private void relayout() {
        ui().layout(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
    }

    private static <T extends Node> T ancestor(Node node, Class<T> type) {
        for (Node at = node.parent(); at != null; at = at.parent()) {
            if (type.isInstance(at)) {
                return type.cast(at);
            }
        }
        throw new IllegalStateException("No " + type.getSimpleName() + " around " + node.getClass().getSimpleName());
    }
}
