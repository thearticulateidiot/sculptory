package dev.sculptory.fabric.client.editor.demo;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.builder.BuilderClient;
import dev.sculptory.fabric.client.builder.RingScreen;
import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.ExitReason;
import dev.sculptory.fabric.client.editor.check.CheckDriver;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.Tool;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.ToolRegistry;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.Menu;
import dev.sculptory.fabric.client.editor.ui.widget.MenuItem;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.protocol.v2.BuilderPower;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * What the demo's steps are written with: captions, waits, smooth camera moves, a pointer that glides to what it aims
 * at, presses, drags, keys, scrolls and clicks on the editor's UI, all at a pace a viewer can follow. Everything goes
 * through the play check's {@link CheckDriver} (the real input paths) on the demo's own thread; the caption bar and
 * the pointer ring are the {@link CaptionOverlay}'s. Distances in the world are the stage's, given relative through
 * {@link #stage()}.
 */
public final class Demo {
    /** A pointer move across the screen, and a camera move between two views. */
    static final long POINTER_MS = 550;
    static final long CAMERA_MS = 1_400;
    private static final double EYE_HEIGHT = 1.62;
    private static final int LEFT = GLFW.GLFW_MOUSE_BUTTON_LEFT;

    private final MinecraftClient client;
    private final EditorClient editor;
    private final CheckDriver driver;
    private final CaptionOverlay overlay;
    private final DemoStage stage;
    private final DemoPacing pacing;

    Demo(MinecraftClient client, EditorClient editor, CheckDriver driver, CaptionOverlay overlay, DemoStage stage,
            DemoPacing pacing) {
        this.client = client;
        this.editor = editor;
        this.driver = driver;
        this.overlay = overlay;
        this.stage = stage;
        this.pacing = pacing;
        driver.watchPointer((x, y) -> {
            overlay.pointer(x, y);
            moveOsCursor(x, y);
        });
    }

    public DemoPacing pacing() {
        return pacing;
    }

    public CheckDriver driver() {
        return driver;
    }

    public DemoStage stage() {
        return stage;
    }

    public EditorUi ui() {
        return editor.ui();
    }

    public EditorContext ctx() {
        return driver.ctx();
    }

    public MinecraftClient client() {
        return client;
    }

    public <T> T onClient(Callable<T> task) {
        return driver.onClient(task);
    }

    public void onClient(CheckDriver.Step step) {
        driver.onClient(step);
    }

    public String translate(String key, Object... args) {
        return driver.translate(key, args);
    }

    // ---- Captions and time ----

    /**
     * Shows a caption and gives the viewer time to read it before going on ({@link DemoPacing#readMs}: nothing
     * without captions).
     */
    public void say(String key, Object... args) {
        note(key, args);
        pause(pacing.readMs());
    }

    /** Shows a caption (with captions on; else nothing) and goes straight on. */
    public void note(String key, Object... args) {
        String text = translate(key, args);
        SculptoryMod.LOG.info("Demo caption: {}", text);
        if (pacing.showsCaptions()) {
            overlay.show(text);
        }
    }

    public void clearCaption() {
        overlay.clear();
    }

    /** Waits, the game running. */
    public void pause(long ms) {
        if (ms > 0) {
            driver.millis(ms);
        }
    }

    /** Holds a result on screen: {@code ms} with captions, longer without ({@link DemoPacing#holdMs}). */
    public void hold(long ms) {
        pause(pacing.holdMs(ms));
    }

    public void frames(int count) {
        driver.frames(count);
    }

    // ---- Camera ----

    /**
     * Moves the eye to {@code (ex, ey, ez)} looking at {@code (tx, ty, tz)} over {@code ms}, eased, position and view
     * interpolated frame by frame (the player flies; the server follows the movement packets).
     */
    public void glide(double ex, double ey, double ez, double tx, double ty, double tz, long ms) {
        double[] from = onClient(() -> {
            ClientPlayerEntity player = client.player;
            return new double[] {player.getX(), player.getY() + EYE_HEIGHT, player.getZ(), player.getYaw(),
                    player.getPitch()};
        });
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float pitch = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * MathHelper.DEGREES_PER_RADIAN);
        double yawTurn = MathHelper.wrapDegrees(yaw - from[3]);
        long started = System.currentTimeMillis();
        double t = 0;
        while (t < 1) {
            t = Math.min(1, (System.currentTimeMillis() - started) / (double) Math.max(1, ms));
            double s = t * t * (3 - 2 * t);
            double x = from[0] + (ex - from[0]) * s;
            double y = from[1] + (ey - from[1]) * s;
            double z = from[2] + (ez - from[2]) * s;
            float lookYaw = (float) (from[3] + yawTurn * s);
            float lookPitch = (float) (from[4] + (pitch - from[4]) * s);
            onClient(() -> place(x, y - EYE_HEIGHT, z, lookYaw, lookPitch));
            frames(1);
        }
        frames(2);
    }

    /** {@link #glide} with the usual camera time. */
    public void view(double ex, double ey, double ez, double tx, double ty, double tz) {
        glide(ex, ey, ez, tx, ty, tz, CAMERA_MS);
    }

    /** Turns the view to {@code (tx, ty, tz)} over {@code ms} without moving. */
    public void lookAt(double tx, double ty, double tz, long ms) {
        double[] eye = eye();
        glide(eye[0], eye[1], eye[2], tx, ty, tz, ms);
    }

    /** Turns to a compass yaw and pitch (degrees, vanilla's) over {@code ms}. */
    public void turnTo(float yaw, float pitch, long ms) {
        double[] eye = eye();
        double cy = Math.cos(Math.toRadians(pitch));
        double dx = -Math.sin(Math.toRadians(yaw)) * cy;
        double dz = Math.cos(Math.toRadians(yaw)) * cy;
        double dy = -Math.sin(Math.toRadians(pitch));
        glide(eye[0], eye[1], eye[2], eye[0] + dx * 20, eye[1] + dy * 20, eye[2] + dz * 20, ms);
    }

    /** The eye's position now. */
    public double[] eye() {
        return onClient(() -> {
            ClientPlayerEntity player = client.player;
            return new double[] {player.getX(), player.getY() + EYE_HEIGHT, player.getZ()};
        });
    }

    private void place(double x, double y, double z, float yaw, float pitch) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        player.getAbilities().flying = true;
        player.setVelocity(0, 0, 0);
        player.setPosition(x, y, z);
        player.prevX = x;
        player.prevY = y;
        player.prevZ = z;
        player.lastRenderX = x;
        player.lastRenderY = y;
        player.lastRenderZ = z;
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.prevYaw = yaw;
        player.prevPitch = pitch;
        player.setHeadYaw(yaw);
        player.prevHeadYaw = yaw;
        player.setBodyYaw(yaw);
    }

    // ---- Pointer ----

    /** Glides the pointer to a screen position (GUI pixels) over {@code ms}. */
    public void moveTo(double sx, double sy, long ms) {
        double fromX = driver.pointerX();
        double fromY = driver.pointerY();
        long started = System.currentTimeMillis();
        double t = 0;
        while (t < 1) {
            t = Math.min(1, (System.currentTimeMillis() - started) / (double) Math.max(1, ms));
            double s = t * t * (3 - 2 * t);
            driver.pointScreen(fromX + (sx - fromX) * s, fromY + (sy - fromY) * s);
            frames(1);
        }
    }

    /** Where a world point shows on screen now (GUI pixels). */
    public double[] project(double x, double y, double z) {
        return onClient(() -> {
            ScreenProjector projector = editor.projector()
                    .orElseThrow(() -> new CheckDriver.Failed("no frame rendered yet to project with"));
            double[] out = new double[2];
            if (!projector.project(x, y, z, out)) {
                throw new CheckDriver.Failed(String.format(Locale.ROOT, "%.1f %.1f %.1f is behind the camera", x, y, z));
            }
            return out;
        });
    }

    /** Glides the pointer to where a world point shows. */
    public void pointAt(double x, double y, double z) {
        double[] at = project(x, y, z);
        moveTo(at[0], at[1], POINTER_MS);
    }

    /**
     * Glides the pointer to the middle of a block's face and checks the editor's cursor is on exactly that block and
     * face (relative stage coordinates).
     */
    public WorldCursor aim(int dx, int dy, int dz, WorldCursor.Face face) {
        int x = stage.x(dx);
        int y = stage.y(dy);
        int z = stage.z(dz);
        pointAt(x + 0.5 + 0.5 * face.dx(), y + 0.5 + 0.5 * face.dy(), z + 0.5 + 0.5 * face.dz());
        return driver.aim(x, y, z, face);
    }

    /**
     * As {@link #aim}, but at a point {@code (fx, fy, fz)} within the block (fractions of it), for a block whose face
     * centre lies on an edge: a stair's top, aimed at its raised half.
     */
    public WorldCursor aimWithin(int dx, int dy, int dz, WorldCursor.Face face, double fx, double fy, double fz) {
        int x = stage.x(dx);
        int y = stage.y(dy);
        int z = stage.z(dz);
        pointAt(x + fx, y + fy, z + fz);
        frames(3);
        WorldCursor cursor = driver.cursor();
        if (cursor.missed() || cursor.pos().x() != x || cursor.pos().y() != y || cursor.pos().z() != z
                || cursor.face() != face) {
            throw new CheckDriver.Failed("aimed within " + x + " " + y + " " + z + " " + face
                    + " but the editor's cursor is on " + (cursor.missed() ? "nothing" : cursor.pos() + " "
                    + cursor.face()));
        }
        return cursor;
    }

    /** Parks the pointer over the world, clear of the UI. */
    public void park() {
        driver.parkPointer();
    }

    private void moveOsCursor(double x, double y) {
        if (client.mouse.isCursorLocked()) {
            return;
        }
        double factor = client.getWindow().getScaleFactor();
        GLFW.glfwSetCursorPos(client.getWindow().getHandle(), x * factor, y * factor);
    }

    // ---- Mouse ----

    public void press(int modifiers) {
        driver.press(LEFT, modifiers);
        overlay.pointerDown();
    }

    public void release() {
        driver.release(LEFT);
        overlay.pointerUp();
    }

    public void click() {
        press(0);
        frames(2);
        release();
    }

    /** Drags the held button to where a world point shows (relative stage coordinates, fractions allowed). */
    public void dragTo(double dx, double dy, double dz, long ms) {
        driver.dragTo(stage.x(0) + dx, stage.y(0) + dy, stage.z(0) + dz, (int) Math.max(4, ms / 16), LEFT);
    }

    public void dragScreen(double sx, double sy, long ms) {
        driver.dragScreen(sx, sy, (int) Math.max(4, ms / 16), LEFT);
    }

    /** Wheel notches at the pointer, a beat apart. */
    public void scroll(int notches, int modifiers) {
        for (int i = 0; i < Math.abs(notches); i++) {
            driver.scroll(notches > 0 ? 1 : -1, modifiers);
            pause(350);
        }
    }

    /** Aims at a face, holds the button {@code holdMs}, lets go and waits for the server. */
    public void stroke(int dx, int dy, int dz, WorldCursor.Face face, long holdMs) {
        aim(dx, dy, dz, face);
        edit(() -> {
            press(0);
            pause(holdMs);
            release();
        });
    }

    /**
     * Glides the pointer to where a world point shows (whatever block is there now: a lump a brush made), holds the
     * button {@code holdMs}, lets go and waits for the server.
     */
    public void holdAt(double x, double y, double z, long holdMs) {
        pointAt(x, y, z);
        frames(3);
        edit(() -> {
            press(0);
            pause(holdMs);
            release();
        });
    }

    /** Aims at a face, presses, drags to a world point over {@code ms}, lets go and waits for the server. */
    public void dragStroke(int dx, int dy, int dz, WorldCursor.Face face, double toX, double toY, double toZ, long ms,
            int modifiers) {
        aim(dx, dy, dz, face);
        edit(() -> {
            press(modifiers);
            dragTo(toX, toY, toZ, ms);
            release();
        });
    }

    /** Runs an edit and waits until the server has done it (or 1.5 s if it changed no history). */
    public void edit(Runnable action) {
        long version = driver.historyVersion();
        action.run();
        try {
            driver.awaitEdit(version, "the edit");
        } catch (CheckDriver.Failed e) {
            SculptoryMod.LOG.info("Demo: no history change after the edit ({})", e.getMessage());
        }
    }

    /** As {@link #edit}, but waits only for the tool to go idle (an edit that may change nothing). */
    public void tryEdit(Runnable action) {
        action.run();
        driver.awaitIdle("the edit");
    }

    // ---- Keys ----

    public void key(KeyAction action) {
        driver.action(action);
    }

    public void keyCode(int code, int modifiers) {
        driver.key(code, modifiers);
    }

    /** Types text into the focused field, a character every {@code msPerChar}. */
    public void typeSlowly(String text, long msPerChar) {
        for (char c : text.toCharArray()) {
            driver.type(String.valueOf(c));
            pause(msPerChar);
        }
    }

    // ---- The editor's UI ----

    /** The middle of a laid-out node in GUI pixels. */
    public double[] centre(Node node) {
        return onClient(() -> {
            driver.relayout();
            Rect bounds = node.bounds();
            if (bounds.isEmpty() || !node.isShown()) {
                throw new CheckDriver.Failed(node.getClass().getSimpleName() + " is not shown");
            }
            float factor = ui().uiScale().factor();
            return new double[] {(bounds.x() + bounds.width() / 2.0) * factor,
                    (bounds.y() + bounds.height() / 2.0) * factor};
        });
    }

    /** A point in UI units as GUI pixels. */
    public double[] screen(double uiX, double uiY) {
        float factor = onClient(() -> ui().uiScale().factor());
        return new double[] {uiX * factor, uiY * factor};
    }

    /** Glides the pointer to a node and rests there {@code restMs}. */
    public void hover(Node node, long restMs) {
        double[] at = centre(node);
        moveTo(at[0], at[1], POINTER_MS);
        pause(restMs);
    }

    public void hoverUi(double uiX, double uiY, long restMs) {
        double[] at = screen(uiX, uiY);
        moveTo(at[0], at[1], POINTER_MS);
        pause(restMs);
    }

    /** Glides the pointer to a node and clicks it. */
    public void clickNode(Node node) {
        double[] at = centre(node);
        moveTo(at[0], at[1], POINTER_MS);
        pause(150);
        click();
        frames(2);
    }

    /** Clicks the button labelled {@code labelKey} in a window, opening the window first if needed. */
    public void clickButton(String windowId, String labelKey) {
        driver.openWindow(windowId);
        String text = translate(labelKey);
        Button button = driver.button(windowId, text)
                .orElseThrow(() -> new CheckDriver.Failed("no \"" + text + "\" button in the " + windowId + " window"));
        clickNode(button);
    }

    /** Opens a top bar menu and clicks the row labelled {@code labelKey}. */
    public void clickMenuRow(String menuId, String labelKey) {
        String label = translate(labelKey);
        Rect row = onClient(() -> {
            ui().openMenu(menuId);
            driver.relayout();
            Menu menu = ui().menuBar().openMenu()
                    .orElseThrow(() -> new CheckDriver.Failed("the " + menuId + " menu did not open"));
            List<MenuItem> items = menu.items();
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).label().equals(label)) {
                    return menu.rowBounds(i);
                }
            }
            throw new CheckDriver.Failed("the " + menuId + " menu has no \"" + label + "\" row");
        });
        pause(500);
        double[] at = screen(row.x() + row.width() / 2.0, row.y() + row.height() / 2.0);
        moveTo(at[0], at[1], POINTER_MS);
        pause(300);
        click();
        frames(2);
    }

    /** The open window {@code id}. */
    public Window window(String id) {
        return onClient(() -> ui().windows().window(id)
                .orElseThrow(() -> new CheckDriver.Failed("no window " + id)));
    }

    /** The nodes of a type in an open window, in tree order. */
    public <T extends Node> List<T> nodes(String windowId, Class<T> type) {
        return onClient(() -> {
            driver.relayout();
            Window window = ui().windows().window(windowId)
                    .orElseThrow(() -> new CheckDriver.Failed("no window " + windowId));
            List<T> found = new ArrayList<>();
            CheckDriver.collect(window.content(), type, found);
            return found;
        });
    }

    /** The nodes of a type under the topmost popup. */
    public <T extends Node> List<T> popupNodes(Class<T> type) {
        return onClient(() -> {
            driver.relayout();
            var popups = ui().windows().context().popups().popups();
            if (popups.isEmpty()) {
                throw new CheckDriver.Failed("no popup is open");
            }
            List<T> found = new ArrayList<>();
            CheckDriver.collect(popups.get(popups.size() - 1).content(), type, found);
            return found;
        });
    }

    /** Closes the top popup with Esc, if one is open. */
    public void closePopup() {
        driver.closePopup();
    }

    public void closeWindow(String id) {
        onClient(() -> {
            if (ui().windows().isOpen(id)) {
                ui().toggleWindow(id);
            }
        });
        frames(2);
    }

    /** Activates a tool by clicking its palette slot. */
    public void selectTool(ToolId id) {
        int slot = onClient(() -> {
            ToolRegistry tools = ctx().tools();
            for (int i = 1; i <= ToolRegistry.PALETTE_SLOTS; i++) {
                if (tools.slot(i).map(Tool::descriptor).map(d -> d.id().equals(id)).orElse(false)) {
                    return i;
                }
            }
            return -1;
        });
        if (slot > 0) {
            Rect bounds = onClient(() -> {
                driver.relayout();
                return ui().paletteSlotBounds(slot).orElse(Rect.EMPTY);
            });
            if (!bounds.isEmpty()) {
                double[] at = screen(bounds.x() + bounds.width() / 2.0, bounds.y() + bounds.height() / 2.0);
                moveTo(at[0], at[1], POINTER_MS);
                click();
                frames(2);
            }
        }
        if (!onClient(() -> ctx().tools().isActive(id))) {
            driver.selectTool(id);
        }
    }

    public void setting(ToolId tool, String key, Object value) {
        driver.setting(tool, key, value);
    }

    public void activeBlock(String id) {
        driver.activeBlock(id);
        frames(1);
    }

    /** Sets a tool's weighted-block list (a mix or palette) from block specs, each with a weight. */
    public void mix(ToolId tool, String key, List<String> specs, List<Integer> weights) {
        onClient(() -> {
            SettingsValues values = ctx().settings(tool);
            if (!(values.schema().def(key).orElse(null) instanceof SettingDef.WeightedBlocks def)) {
                throw new CheckDriver.Failed(tool.value() + " has no block-list setting '" + key + "'");
            }
            List<SettingDef.WeightedBlock> blocks = new ArrayList<>();
            for (int i = 0; i < specs.size(); i++) {
                blocks.add(new SettingDef.WeightedBlock(BlockDescriptor.parse(specs.get(i)), weights.get(i)));
            }
            ctx().updateSettings(tool, values.with(def, List.copyOf(blocks)));
        });
        frames(1);
    }

    // ---- The editor itself ----

    /** Opens the editor with its key (B), as the player does, and waits for it. */
    public void openEditor() {
        if (!onClient(() -> editor.mode().isActive())) {
            keyCode(GLFW.GLFW_KEY_B, 0);
        }
        if (!driver.until(10_000, () -> editor.mode().isActive() && client.currentScreen != null)) {
            driver.enterEditor();
        }
        onClient(() -> ui().quickStart().hide());
        frames(2);
        overlay.setPointerShown(pacing.showsPointerRing());
    }

    /** Closes the editor with its key (B) and waits for the world to take the input again. */
    public void closeEditor() {
        overlay.setPointerShown(false);
        if (onClient(() -> editor.mode().isActive())) {
            keyCode(GLFW.GLFW_KEY_B, 0);
        }
        if (!driver.until(5_000, () -> !editor.mode().isEditing() && client.currentScreen == null)) {
            onClient(() -> {
                editor.mode().exit(ExitReason.TOGGLED);
                client.setScreen(null);
            });
        }
        frames(2);
    }

    /** The editor as a step starts: no popup, help or card, the Select tool, nothing selected, the pointer parked. */
    public void tidyEditor() {
        onClient(() -> {
            EditorUi ui = ui();
            ui.windows().context().popups().closeAll();
            ui.windows().context().clearFocus();
            if (ui.isHelpOpen()) {
                ui.toggleHelp();
            }
            ui.quickStart().hide();
            driver.controller().selectTool(ToolId.SELECT);
            ctx().setSelection(null);
        });
        frames(2);
        park();
    }

    /** The default window layout at the current UI size (what Reset layout gives). */
    public void defaultLayout() {
        onClient(() -> ui().windows().restore(SizedLayouts.EMPTY, ui().uiScale().percent()));
        frames(2);
    }

    // ---- Builder mode (outside the editor) ----

    public BuilderClient builder() {
        return BuilderClient.instance().orElseThrow(() -> new CheckDriver.Failed("builder mode is not initialised"));
    }

    /** Holds G (the ring key) until {@link #closeRing}; the ring of powers opens. */
    public void openRing() {
        onClient(() -> builder().pinRingKey(true));
        driver.keyDown(GLFW.GLFW_KEY_G);
        if (!driver.until(5_000, () -> client.currentScreen instanceof RingScreen)) {
            throw new CheckDriver.Failed("the ring of powers did not open");
        }
        overlay.setPointerShown(pacing.showsPointerRing());
        RingScreen ring = (RingScreen) client.currentScreen;
        double[] centre = onClient(() -> new double[] {ring.menuCentreX(), ring.menuCentreY()});
        driver.pointScreen(centre[0], centre[1]);
        ringPointer(centre[0], centre[1]);
        frames(10);
    }

    /** Glides the pointer to a power's chip on the open ring and clicks it (the ring stays open). */
    public void clickPower(BuilderPower power) {
        if (!(client.currentScreen instanceof RingScreen ring)) {
            throw new CheckDriver.Failed("the ring of powers is not open");
        }
        double[] at = onClient(() -> ring.chipCentre(power));
        double fromX = driver.pointerX();
        double fromY = driver.pointerY();
        long started = System.currentTimeMillis();
        double t = 0;
        while (t < 1) {
            t = Math.min(1, (System.currentTimeMillis() - started) / (double) POINTER_MS);
            double s = t * t * (3 - 2 * t);
            double x = fromX + (at[0] - fromX) * s;
            double y = fromY + (at[1] - fromY) * s;
            driver.pointScreen(x, y);
            ringPointer(x, y);
            frames(1);
        }
        pause(250);
        overlay.pointerDown();
        onClient(() -> ring.mouseClicked(at[0], at[1], LEFT));
        frames(3);
        overlay.pointerUp();
        pause(500);
    }

    private void ringPointer(double x, double y) {
        onClient(() -> {
            if (client.currentScreen instanceof RingScreen ring) {
                ring.mouseMoved(x, y);
            }
        });
    }

    /** Lets go of G with the pointer in the ring's middle, so nothing is toggled by the release. */
    public void closeRing() {
        if (client.currentScreen instanceof RingScreen ring) {
            double[] centre = onClient(() -> new double[] {ring.menuCentreX(), ring.menuCentreY()});
            double fromX = driver.pointerX();
            double fromY = driver.pointerY();
            long started = System.currentTimeMillis();
            double t = 0;
            while (t < 1) {
                t = Math.min(1, (System.currentTimeMillis() - started) / (double) POINTER_MS);
                double s = t * t * (3 - 2 * t);
                double x = fromX + (centre[0] - fromX) * s;
                double y = fromY + (centre[1] - fromY) * s;
                driver.pointScreen(x, y);
                ringPointer(x, y);
                frames(1);
            }
            pause(200);
        }
        onClient(() -> builder().pinRingKey(false));
        driver.keyUp(GLFW.GLFW_KEY_G);
        driver.until(5_000, () -> !(client.currentScreen instanceof RingScreen));
        overlay.setPointerShown(false);
        frames(2);
    }

    /** Keys that count as held outside the editor (Alt for the Tinker power, Ctrl+Z for undo); empty lets go. */
    public void holdKeys(Set<Integer> keys) {
        onClient(() -> builder().pinKeys(keys));
        frames(2);
    }

    /** One right click, as the player using the item in hand. */
    public void useItem() {
        onClient(() -> {
            KeyBinding.onKeyPressed(KeyBindingHelper.getBoundKeyOf(client.options.useKey));
        });
        frames(3);
    }

    /** Holds or lets go of the attack button (left click) outside the editor. */
    public void holdAttack(boolean held) {
        onClient(() -> {
            InputUtil.Key key = KeyBindingHelper.getBoundKeyOf(client.options.attackKey);
            KeyBinding.setKeyPressed(key, held);
            if (held) {
                KeyBinding.onKeyPressed(key);
            }
        });
        frames(2);
    }

    /** Makes hotbar slot {@code slot} the one in hand. */
    public void selectHotbar(int slot) {
        onClient(() -> {
            if (client.player != null) {
                client.player.getInventory().selectedSlot = slot;
            }
        });
        frames(2);
    }

    /** Whether the game shows a screen of this type now. */
    public boolean screenIs(Class<? extends Screen> type) {
        return onClient(() -> type.isInstance(client.currentScreen));
    }
}
