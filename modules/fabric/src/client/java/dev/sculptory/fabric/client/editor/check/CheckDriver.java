package dev.sculptory.fabric.client.editor.check;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.Box;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.fabric.SculptoryMod;
import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.EditorContext;
import dev.sculptory.fabric.client.editor.EditorController;
import dev.sculptory.fabric.client.editor.hud.EditorUi;
import dev.sculptory.fabric.client.editor.input.KeyAction;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.mc.McTranslator;
import dev.sculptory.fabric.client.editor.settings.SettingDef;
import dev.sculptory.fabric.client.editor.settings.SettingsValues;
import dev.sculptory.fabric.client.editor.tool.ToolId;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.window.SizedLayouts;
import dev.sculptory.fabric.client.editor.ui.window.Window;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.mixin.InGameHudAccessor;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.fabric.client.session.Notice;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Function;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

/**
 * What the play check's scenarios drive the real client with. Runs on the check's own thread and hops onto the client
 * (and, in singleplayer, the integrated server) thread for every step, waiting for it; so a scenario reads top to
 * bottom like a player's session: move the camera, point at a block, press, drag, release, press a key, wait for the
 * server, read the world.
 *
 * <p>Input goes in where the game's own does: mouse buttons, drags and scrolls through the editor screen's handlers
 * (what vanilla's mouse callbacks call) at a pinned pointer ({@link EditorClient#pinPointer}), with pinned modifier keys;
 * keys through vanilla's {@code Keyboard.onKey}. So the editor's router, the tool, the raycast, the prediction, the
 * network and the server's job all run as they do for a player.
 */
public final class CheckDriver {
    /** How long one hop onto the client or server thread may take. */
    private static final long HOP_TIMEOUT_MS = 60_000;
    /** How long an edit may take to finish on the server. */
    private static final long EDIT_TIMEOUT_MS = 120_000;
    /** How long the client's world may take to show what the server did. */
    private static final long SYNC_TIMEOUT_MS = 15_000;
    /** A player's eye above the feet (standing, flying). */
    private static final double EYE_HEIGHT = 1.62;

    /** A step the check can't take (a hop timed out, the aim missed): the scenario stops and is recorded as failed. */
    public static final class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }

        public Failed(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** A toast the editor showed, with when (ms since the check started). */
    public record Toast(Notice notice, String text, long atMs) {
    }

    /** Something to run on a game thread that may throw. */
    @FunctionalInterface
    public interface Step {
        void run() throws Exception;
    }

    private final MinecraftClient client;
    private final EditorClient editor;
    private final Path dir;
    private final CheckReport report;
    private final AtomicLong frames;
    private final McTranslator translator = new McTranslator();
    private final List<Toast> toasts = new CopyOnWriteArrayList<>();
    private final long startedMs = System.currentTimeMillis();
    /** Where the pointer is pinned now (GUI pixels). */
    private double pointerX;
    private double pointerY;
    private BiConsumer<Double, Double> pointerWatcher;
    private int pictureNumber;
    private String scenario = "setup";

    CheckDriver(MinecraftClient client, EditorClient editor, Path dir, CheckReport report, AtomicLong frames) {
        this.client = client;
        this.editor = editor;
        this.dir = dir;
        this.report = report;
        this.frames = frames;
        editor.watchToasts(notice -> toasts.add(new Toast(notice, translator.translate(notice.key(), notice.args()),
                System.currentTimeMillis() - startedMs)));
    }

    /**
     * A driver for another dev harness (the scripted demo): the same steps, with its pictures and their notes going to
     * {@code dir} and a report of its own that nobody writes.
     */
    public CheckDriver(MinecraftClient client, EditorClient editor, Path dir, AtomicLong frames) {
        this(client, editor, dir, new CheckReport("Sculptory driver"), frames);
    }

    /** Called on the client thread with every pin of the pointer (the demo's overlay and the OS cursor follow it). */
    public void watchPointer(BiConsumer<Double, Double> watcher) {
        pointerWatcher = watcher;
    }

    /** Where the pointer is pinned now (GUI pixels). */
    public double pointerX() {
        return pointerX;
    }

    public double pointerY() {
        return pointerY;
    }

    void setScenario(String name) {
        scenario = name;
    }

    public MinecraftClient client() {
        return client;
    }

    public EditorClient editor() {
        return editor;
    }

    public String translate(String key, Object... args) {
        return translator.translate(key, args);
    }

    // ---- Threads ----

    /** Runs {@code task} on the client thread between two frames and returns its result. */
    public <T> T onClient(Callable<T> task) {
        return await(client.submit(() -> {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new Failed(e.getMessage(), e);
            }
        }), "the client thread");
    }

    public void onClient(Step step) {
        onClient(() -> {
            step.run();
            return null;
        });
    }

    /** The integrated server (singleplayer only). */
    public Optional<IntegratedServer> server() {
        return Optional.ofNullable(client.getServer());
    }

    /** Runs {@code task} on the integrated server's thread and returns its result. Singleplayer only. */
    public <T> T onServer(Function<MinecraftServer, T> task) {
        IntegratedServer server = server().orElseThrow(() -> new Failed("no integrated server (not singleplayer)"));
        return await(server.submit(() -> task.apply(server)), "the server thread");
    }

    private static <T> T await(CompletableFuture<T> future, String where) {
        try {
            return future.get(HOP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new Failed(where + " did not run the step within " + HOP_TIMEOUT_MS / 1000 + " s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new Failed(String.valueOf(cause), cause);
        }
    }

    // ---- Time ----

    /** Waits until {@code count} more frames have been rendered (at most a few seconds each). */
    public void frames(int count) {
        long target = frames.get() + count;
        long deadline = System.currentTimeMillis() + 5_000L + count * 200L;
        while (frames.get() < target) {
            if (System.currentTimeMillis() > deadline) {
                throw new Failed("the game rendered no frame for a while (" + count + " frames wanted)");
            }
            sleep(2);
        }
    }

    /** Waits {@code ms} milliseconds (the game keeps running). */
    public void millis(long ms) {
        sleep(ms);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("interrupted");
        }
    }

    /**
     * Polls {@code probe} on the client thread once a frame until it returns true; false after {@code timeoutMs}.
     */
    public boolean until(long timeoutMs, Callable<Boolean> probe) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            if (Boolean.TRUE.equals(onClient(probe))) {
                return true;
            }
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            frames(1);
        }
    }

    // ---- The editor ----

    public EditorUi ui() {
        return editor.ui();
    }

    public EditorController controller() {
        return editor.controller();
    }

    public EditorContext ctx() {
        return editor.controller().context();
    }

    public Optional<EditorSession> session() {
        return ctx().session();
    }

    /** Opens the editor as the toggle key does, and waits until it is active. */
    public void enterEditor() {
        onClient(() -> {
            if (!editor.mode().isActive()) {
                if (client.currentScreen != null) {
                    client.setScreen(null);
                }
                editor.mode().enter();
            }
        });
        if (!until(10_000, () -> editor.mode().isActive() && client.currentScreen != null)) {
            throw new Failed("the editor did not open");
        }
    }

    /**
     * The editor as every scenario starts it: no popup, focus, key sheet or quick start card (a first run shows the
     * card in the middle of the screen), UI size {@link PlayCheck#UI_PERCENT} and the default window layout (the same
     * windows in the same places every run, whatever the player saved).
     */
    public void standardEditor() {
        onClient(() -> {
            EditorUi ui = ui();
            ui.windows().context().popups().closeAll();
            ui.windows().context().clearFocus();
            if (ui.isHelpOpen()) {
                ui.toggleHelp();
            }
            ui.quickStart().hide();
            ui.uiScale().set(PlayCheck.UI_PERCENT);
            ui.windows().restore(SizedLayouts.EMPTY, PlayCheck.UI_PERCENT);
        });
        frames(2);
    }

    /** Activates a tool as its palette slot does. */
    public void selectTool(ToolId id) {
        boolean ok = onClient(() -> controller().selectTool(id));
        if (!ok) {
            throw new Failed("the " + id.value() + " tool could not be selected");
        }
        frames(2);
    }

    /**
     * Sets one of a tool's settings as its control in Tool Settings does: a choice by its constant's name
     * ({@code "SURFACE"}), a whole number, a decimal, a switch, or a block by id.
     */
    public void setting(ToolId tool, String key, Object value) {
        onClient(() -> {
            SettingsValues values = ctx().settings(tool);
            SettingDef<?> def = values.schema().def(key)
                    .orElseThrow(() -> new Failed(tool.value() + " has no setting '" + key + "'"));
            ctx().updateSettings(tool, with(values, def, value));
        });
        frames(1);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SettingsValues with(SettingsValues values, SettingDef<?> def, Object value) {
        if (def instanceof SettingDef.Enum<?> choice) {
            return values.with((SettingDef.Enum) choice, Enum.valueOf((Class) choice.type(),
                    value.toString().toUpperCase(Locale.ROOT)));
        }
        if (def instanceof SettingDef.Int number) {
            return values.with(number, ((Number) value).intValue());
        }
        if (def instanceof SettingDef.Decimal decimal) {
            return values.with(decimal, ((Number) value).doubleValue());
        }
        if (def instanceof SettingDef.Bool bool) {
            return values.with(bool, (Boolean) value);
        }
        if (def instanceof SettingDef.Block block) {
            return values.with(block, block(value.toString()));
        }
        throw new Failed("the check can't set a " + def.getClass().getSimpleName() + " setting (" + def.key() + ")");
    }

    /** The block the tools place (the top bar's block chip). */
    public void activeBlock(String id) {
        onClient(() -> ctx().setActiveBlock(block(id)));
    }

    /** A block with its properties: {@code minecraft:oak_stairs[facing=east]}. */
    public static BlockDescriptor block(String spec) {
        return BlockDescriptor.parse(spec);
    }

    /** The history's change counter (every edit, undo and redo moves it). */
    public long historyVersion() {
        return onClient(() -> session().map(s -> s.history().version()).orElse(-1L));
    }

    /**
     * Waits until the history has moved past {@code version} and nothing of this player's is running any more (jobs,
     * strokes, history steps), then a few frames for the last packets.
     */
    public void awaitEdit(long version, String what) {
        long deadline = System.currentTimeMillis() + EDIT_TIMEOUT_MS;
        if (!until(EDIT_TIMEOUT_MS, () -> session().map(s -> s.history().version() != version).orElse(false))) {
            throw new Failed(what + ": the server's history did not change within " + EDIT_TIMEOUT_MS / 1000 + " s"
                    + toastNote());
        }
        awaitIdle(what, deadline);
    }

    /** Waits until no job, stroke or history step of this player's runs (three frames in a row). */
    public void awaitIdle(String what) {
        awaitIdle(what, System.currentTimeMillis() + EDIT_TIMEOUT_MS);
    }

    private void awaitIdle(String what, long deadline) {
        int quiet = 0;
        while (quiet < 3) {
            boolean idle = onClient(this::idle);
            quiet = idle ? quiet + 1 : 0;
            if (System.currentTimeMillis() > deadline) {
                throw new Failed(what + ": still running after " + EDIT_TIMEOUT_MS / 1000 + " s");
            }
            frames(1);
        }
        frames(3);
    }

    private boolean idle() {
        Optional<EditorSession> session = session();
        if (session.isEmpty()) {
            return true;
        }
        for (JobTracker.Job job : session.get().jobs().jobs()) {
            if (!job.finished()) {
                return false;
            }
        }
        return !session.get().strokesPending() && !session.get().historyBusy();
    }

    // ---- Toasts ----

    /** A mark for {@link #toastsSince}. */
    public int toastMark() {
        return toasts.size();
    }

    public List<Toast> toastsSince(int mark) {
        return List.copyOf(toasts.subList(Math.min(mark, toasts.size()), toasts.size()));
    }

    /** Waits for a toast with translation key {@code key} since {@code mark}. */
    public Optional<Toast> awaitToast(int mark, String key, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (Toast toast : toastsSince(mark)) {
                if (toast.notice().key().equals(key)) {
                    return Optional.of(toast);
                }
            }
            frames(1);
        }
        return Optional.empty();
    }

    /** " (toasts: ...)" for failure messages, or "". */
    public String toastNote() {
        List<Toast> recent = toasts.subList(Math.max(0, toasts.size() - 3), toasts.size());
        if (recent.isEmpty()) {
            return "";
        }
        return " (last toasts: " + String.join(" | ", recent.stream().map(Toast::text).toList()) + ")";
    }

    /**
     * The messages on screen now: the editor's toasts still shown (in the editor or over gameplay) and the action
     * bar's text while it lasts. What a player reads, as against what was sent.
     */
    public List<String> shownMessages() {
        return onClient(() -> {
            List<String> shown = new ArrayList<>();
            editor.toastStack().visible().forEach(toast -> shown.add(toast.text()));
            InGameHudAccessor hud = (InGameHudAccessor) client.inGameHud;
            if (hud.sculptory$overlayRemaining() > 0 && hud.sculptory$overlayMessage() != null) {
                shown.add(hud.sculptory$overlayMessage().getString());
            }
            return shown;
        });
    }

    /**
     * Waits until vanilla's "Chat messages can't be verified" toast (shown at the top right for ten seconds after
     * joining a server) is gone: it draws over the editor's own toasts there, which a picture must show.
     */
    public boolean awaitJoinToastGone(long timeoutMs) {
        return until(timeoutMs, () -> client.getToastManager().getToast(SystemToast.class,
                SystemToast.Type.UNSECURE_SERVER_WARNING) == null);
    }

    // ---- Weather ----

    /**
     * Ends the rain on the client at once, after the server has stopped it (singleplayer: the world set clear; a
     * server: {@code /weather clear}). Vanilla fades the client's rain out over five seconds, so the first pictures
     * would otherwise show rain or snow falling. Returns whether the client shows no rain; the reason if it does.
     */
    public String clearClientWeather() {
        boolean stopped = until(15_000, () -> !client.world.getLevelProperties().isRaining());
        if (!stopped) {
            return "the server has not told the client the rain stopped";
        }
        // A dedicated server's /weather clear still fades its own gradient down for five seconds, sending it every
        // tick: zero the client's until nothing raises it again for a second.
        long deadline = System.currentTimeMillis() + 15_000;
        int quiet = 0;
        float rain = 1f;
        while (quiet < 20 && System.currentTimeMillis() < deadline) {
            rain = onClient(() -> {
                ClientWorld world = client.world;
                float seen = Math.max(world.getRainGradient(1f), world.getThunderGradient(1f));
                world.setRainGradient(0f);
                world.setThunderGradient(0f);
                return seen;
            });
            quiet = rain == 0f ? quiet + 1 : 0;
            frames(1);
        }
        return quiet >= 20 ? "" : "rain gradient " + rain + " keeps coming back";
    }

    // ---- Camera ----

    /**
     * Moves the player so its eye is at {@code (ex, ey, ez)}, looking at {@code (tx, ty, tz)}, flying, and waits until
     * the client shows it: in singleplayer by the server's teleport, on a server with {@code /tp} (the op player).
     */
    public void view(double ex, double ey, double ez, double tx, double ty, double tz) {
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float pitch = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * MathHelper.DEGREES_PER_RADIAN);
        double feetY = ey - EYE_HEIGHT;
        if (server().isPresent()) {
            onServer(server -> {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(client.player.getUuid());
                if (player == null) {
                    throw new Failed("no server player");
                }
                player.getAbilities().flying = true;
                player.sendAbilitiesUpdate();
                player.networkHandler.requestTeleport(ex, feetY, ez, yaw, pitch);
                return null;
            });
        } else {
            command(String.format(Locale.ROOT, "tp @s %.3f %.3f %.3f %.2f %.2f", ex, feetY, ez, yaw, pitch));
            onClient(() -> {
                client.player.getAbilities().flying = true;
                client.player.sendAbilitiesUpdate();
            });
        }
        boolean arrived = until(15_000, () -> {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return false;
            }
            player.getAbilities().flying = true;
            player.setVelocity(0, 0, 0);
            return Math.abs(player.getX() - ex) < 0.01 && Math.abs(player.getY() - feetY) < 0.01
                    && Math.abs(player.getZ() - ez) < 0.01;
        });
        if (!arrived) {
            throw new Failed(String.format(Locale.ROOT, "the player did not arrive at %.1f %.1f %.1f", ex, ey, ez));
        }
        onClient(() -> look(yaw, pitch));
        // Chunks and their meshes around the new place.
        until(20_000, () -> client.worldRenderer.isTerrainRenderComplete());
        frames(10);
        onClient(() -> look(yaw, pitch));
        frames(2);
    }

    /**
     * A view down onto a cell from 11 blocks up and 11 blocks south, so it shows in the middle of the screen, clear of
     * the windows at the edges.
     */
    public void overlook(double x, double y, double z) {
        view(x + 0.5, y + 11, z + 11.5, x + 0.5, y, z + 0.5);
    }

    /**
     * A view of a point on a face from the side, so bulges, dents and depth show in a picture: the camera stands
     * {@code distance} blocks away along the face's normal turned {@code sideways} degrees about the vertical (for a
     * floor or ceiling: about the view's own compass bearing) and tilted {@code up} degrees toward the sky (negative:
     * from below).
     */
    public void viewAngled(double x, double y, double z, WorldCursor.Face face, double sideways, double up,
            double distance) {
        double yaw = Math.toRadians(sideways);
        double pitch = Math.toRadians(up);
        double dx;
        double dy;
        double dz;
        if (face.dy() == 0) {
            double nx = face.dx() * Math.cos(yaw) - face.dz() * Math.sin(yaw);
            double nz = face.dx() * Math.sin(yaw) + face.dz() * Math.cos(yaw);
            dx = nx * Math.cos(pitch);
            dz = nz * Math.cos(pitch);
            dy = Math.sin(pitch);
        } else {
            // A floor seen from above or a ceiling from below, at |up| degrees, from the south turned by sideways.
            double tilt = Math.toRadians(Math.abs(up));
            dx = Math.sin(yaw) * Math.cos(tilt);
            dz = Math.cos(yaw) * Math.cos(tilt);
            dy = face.dy() * Math.sin(tilt);
        }
        view(x + dx * distance, y + dy * distance, z + dz * distance, x, y, z);
    }

    /** A view almost straight down onto a cell, for aiming into a gap between blocks. */
    public void overhead(double x, double y, double z) {
        view(x + 0.5, y + 12, z + 2.5, x + 0.5, y, z + 0.5);
    }

    /** Turns the player (client side; the server follows with the next movement packet). */
    public void turn(double ex, double ey, double ez, double tx, double ty, double tz) {
        double dx = tx - ex;
        double dy = ty - ey;
        double dz = tz - ez;
        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90f;
        float pitch = (float) -(MathHelper.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * MathHelper.DEGREES_PER_RADIAN);
        onClient(() -> look(yaw, pitch));
        frames(3);
    }

    private void look(float yaw, float pitch) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.prevYaw = yaw;
        player.prevPitch = pitch;
        player.setHeadYaw(yaw);
        player.prevHeadYaw = yaw;
        player.setBodyYaw(yaw);
    }

    /** Sends a chat command ({@code "tp @s 0 100 0"}, without the slash) as the player typing it would. */
    public void command(String command) {
        onClient(() -> {
            if (client.player == null) {
                throw new Failed("no player");
            }
            client.player.networkHandler.sendChatCommand(command);
        });
        frames(2);
    }

    // ---- Pointer ----

    /** Pins the pointer where {@code (x, y, z)} shows on screen now. */
    public void pointAt(double x, double y, double z) {
        onClient(() -> {
            ScreenProjector projector = editor.projector()
                    .orElseThrow(() -> new Failed("no frame rendered yet to project with"));
            double[] out = new double[2];
            if (!projector.project(x, y, z, out)) {
                throw new Failed(String.format(Locale.ROOT, "%.1f %.1f %.1f is behind the camera", x, y, z));
            }
            int width = client.getWindow().getScaledWidth();
            int height = client.getWindow().getScaledHeight();
            if (out[0] < 0 || out[1] < 0 || out[0] >= width || out[1] >= height) {
                throw new Failed(String.format(Locale.ROOT, "%.1f %.1f %.1f is off screen (%.0f, %.0f)", x, y, z,
                        out[0], out[1]));
            }
            if (ui().isOverUi(out[0], out[1])) {
                // A click there would go to the window, a toast or the bars, not the world.
                throw new Failed(String.format(Locale.ROOT, "%.1f %.1f %.1f shows under the editor's UI (%.0f, %.0f)",
                        x, y, z, out[0], out[1]));
            }
            pin(out[0], out[1]);
        });
    }

    /** Pins the pointer at a screen position (GUI pixels). */
    public void pointScreen(double x, double y) {
        onClient(() -> pin(x, y));
    }

    private void pin(double x, double y) {
        pointerX = x;
        pointerY = y;
        editor.pinPointer(x, y);
        if (pointerWatcher != null) {
            pointerWatcher.accept(x, y);
        }
    }

    /**
     * Points at the middle of a block's face and checks that the editor's cursor hits exactly that block and face
     * (with the active tool's raycast); returns the cursor.
     */
    public WorldCursor aim(int x, int y, int z, WorldCursor.Face face) {
        pointAt(x + 0.5 + 0.5 * face.dx(), y + 0.5 + 0.5 * face.dy(), z + 0.5 + 0.5 * face.dz());
        frames(3);
        WorldCursor cursor = cursor();
        if (cursor.missed() || cursor.pos().x() != x || cursor.pos().y() != y || cursor.pos().z() != z
                || cursor.face() != face) {
            String hit = cursor.missed() ? "nothing" : onClient(() -> cursor.pos() + " " + cursor.face() + " ("
                    + client.world.getBlockState(new net.minecraft.util.math.BlockPos(cursor.pos().x(),
                            cursor.pos().y(), cursor.pos().z())) + ")");
            throw new Failed("aimed at " + x + " " + y + " " + z + " " + face + " but the editor's cursor is on " + hit);
        }
        return cursor;
    }

    /** The editor's world cursor as the last frame picked it. */
    public WorldCursor cursor() {
        return onClient(() -> ctx().cursor().cursor());
    }

    // ---- Mouse ----

    private Screen screen() {
        Screen screen = client.currentScreen;
        if (screen == null || !editor.mode().isActive()) {
            throw new Failed("the editor screen is not open");
        }
        return screen;
    }

    /** Presses a mouse button at the pointer with {@code modifiers} (GLFW bits) held. */
    public void press(int button, int modifiers) {
        onClient(() -> {
            editor.pinModifiers(modifiers);
            screen().mouseClicked(pointerX, pointerY, button);
        });
        frames(1);
    }

    /** Releases a mouse button at the pointer, and lets go of the pinned modifier keys. */
    public void release(int button) {
        onClient(() -> {
            screen().mouseReleased(pointerX, pointerY, button);
            editor.unpinModifiers();
        });
        frames(2);
    }

    /** A left click at the pointer. */
    public void click(int modifiers) {
        press(GLFW.GLFW_MOUSE_BUTTON_LEFT, modifiers);
        frames(1);
        release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /** Moves the held button's pointer to {@code (x, y, z)} on screen in {@code steps} drag events, one a frame. */
    public void dragTo(double x, double y, double z, int steps, int button) {
        double[] target = onClient(() -> {
            ScreenProjector projector = editor.projector().orElseThrow(() -> new Failed("no frame to project with"));
            double[] out = new double[2];
            if (!projector.project(x, y, z, out)) {
                throw new Failed("the drag's end is behind the camera");
            }
            return out;
        });
        dragScreen(target[0], target[1], steps, button);
    }

    /** Moves the held button's pointer to a screen position in {@code steps} drag events, one a frame. */
    public void dragScreen(double toX, double toY, int steps, int button) {
        double fromX = pointerX;
        double fromY = pointerY;
        for (int i = 1; i <= steps; i++) {
            double x = fromX + (toX - fromX) * i / steps;
            double y = fromY + (toY - fromY) * i / steps;
            onClient(() -> {
                double dx = x - pointerX;
                double dy = y - pointerY;
                pin(x, y);
                screen().mouseDragged(x, y, button, dx, dy);
            });
            frames(1);
        }
    }

    /** Scrolls the wheel at the pointer ({@code amount} > 0: up) with {@code modifiers} held. */
    public void scroll(double amount, int modifiers) {
        onClient(() -> {
            editor.pinModifiers(modifiers);
            screen().mouseScrolled(pointerX, pointerY, 0, amount);
            editor.unpinModifiers();
        });
        frames(2);
    }

    // ---- Keyboard ----

    /** Presses and releases a key with {@code modifiers}, through vanilla's keyboard handler. */
    public void key(int key, int modifiers) {
        onClient(() -> {
            long window = client.getWindow().getHandle();
            client.keyboard.onKey(window, key, GLFW.glfwGetKeyScancode(key), GLFW.GLFW_PRESS, modifiers);
        });
        frames(1);
        onClient(() -> {
            long window = client.getWindow().getHandle();
            client.keyboard.onKey(window, key, GLFW.glfwGetKeyScancode(key), GLFW.GLFW_RELEASE, modifiers);
        });
        frames(1);
    }

    /** Presses a key and holds it (until {@link #keyUp}), through vanilla's keyboard handler. */
    public void keyDown(int key) {
        onClient(() -> {
            long window = client.getWindow().getHandle();
            client.keyboard.onKey(window, key, GLFW.glfwGetKeyScancode(key), GLFW.GLFW_PRESS, 0);
        });
        frames(1);
    }

    /** Releases a key held by {@link #keyDown}. */
    public void keyUp(int key) {
        onClient(() -> {
            long window = client.getWindow().getHandle();
            client.keyboard.onKey(window, key, GLFW.glfwGetKeyScancode(key), GLFW.GLFW_RELEASE, 0);
        });
        frames(1);
    }

    /** Esc while a popup is open, which closes it (with none open, Esc would go on down the ladder and leave the editor). */
    public void closePopup() {
        if (onClient(() -> ui().windows().context().popups().isOpen())) {
            key(GLFW.GLFW_KEY_ESCAPE, 0);
        }
    }

    /** Presses the first key chord the keymap binds to {@code action} now (Ctrl+Z for Undo, unless rebound). */
    public void action(KeyAction action) {
        KeyChord chord = onClient(() -> controller().keymap().chords(action).stream()
                .filter(c -> c.input() == KeyChord.Input.KEY)
                .findFirst()
                .orElseThrow(() -> new Failed("no key is bound to " + action.id())));
        key(chord.code(), chord.modifiers());
    }

    /** Types text into the focused field, one character event each. */
    public void type(String text) {
        for (char c : text.toCharArray()) {
            onClient(() -> screen().charTyped(c, 0));
        }
        frames(1);
    }

    // ---- UI ----

    /** The first shown button whose text is {@code text} in an open window, or empty. */
    public Optional<Button> button(String windowId, String text) {
        return onClient(() -> {
            relayout();
            Window window = ui().windows().window(windowId).orElseThrow(() -> new Failed("no window " + windowId));
            if (!window.isOpen()) {
                return Optional.<Button>empty();
            }
            List<Button> found = new ArrayList<>();
            collect(window.content(), Button.class, found);
            return found.stream().filter(b -> b.text().equals(text) && b.isShown()).findFirst();
        });
    }

    /** Opens a window if it is closed (as the Windows menu does) and brings it to the front. */
    public void openWindow(String id) {
        onClient(() -> {
            if (!ui().windows().isOpen(id)) {
                ui().toggleWindow(id);
            }
            ui().windows().bringToFront(id);
            relayout();
        });
        frames(2);
    }

    /** Pins the pointer at a point in the editor UI's own units (hovering whatever is there). */
    public void pointUi(double uiX, double uiY) {
        float factor = onClient(() -> ui().uiScale().factor());
        pointScreen(uiX * factor, uiY * factor);
        frames(2);
    }

    /** A left click at a point in the editor UI's own units. */
    public void clickUi(double uiX, double uiY) {
        pointUi(uiX, uiY);
        press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /** Clicks the middle of a UI node with the left button, through the editor screen. */
    public void clickNode(Node node) {
        double[] at = onClient(() -> {
            relayout();
            Rect bounds = node.bounds();
            if (bounds.isEmpty() || !node.isShown()) {
                throw new Failed(node.getClass().getSimpleName() + " is not shown");
            }
            float factor = ui().uiScale().factor();
            return new double[] {(bounds.x() + bounds.width() / 2.0) * factor,
                    (bounds.y() + bounds.height() / 2.0) * factor};
        });
        pointScreen(at[0], at[1]);
        frames(1);
        press(GLFW.GLFW_MOUSE_BUTTON_LEFT, 0);
        release(GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    /** Clicks the button labelled with {@code labelKey}'s translation in a window (opened first if needed). */
    public void clickButton(String windowId, String labelKey) {
        openWindow(windowId);
        String text = translate(labelKey);
        Button button = button(windowId, text)
                .orElseThrow(() -> new Failed("no \"" + text + "\" button in the " + windowId + " window"));
        clickNode(button);
    }

    /** Lays the editor UI out now (it does so every frame anyway). Client thread. */
    public void relayout() {
        ui().layout(client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());
    }

    public static <T extends Node> void collect(Node node, Class<T> type, List<T> into) {
        if (type.isInstance(node)) {
            into.add(type.cast(node));
        }
        for (Node child : node.children()) {
            collect(child, type, into);
        }
    }

    // ---- Worlds ----

    /** The integrated server's cells of {@code box} (singleplayer). */
    public Cells serverCells(Box box) {
        return onServer(server -> Cells.of(server.getOverworld(), box));
    }

    /** The client's cells of {@code box}. */
    public Cells clientCells(Box box) {
        return onClient(() -> Cells.of(client.world, box));
    }

    /**
     * Waits until the client's world shows exactly {@code expected} in its box; the differences left when it doesn't
     * within the sync timeout.
     */
    public List<Cells.Change> awaitClient(Cells expected) {
        long deadline = System.currentTimeMillis() + SYNC_TIMEOUT_MS;
        while (true) {
            Cells seen = clientCells(expected.box());
            List<Cells.Change> changes = expected.diff(seen);
            if (changes.isEmpty() || System.currentTimeMillis() > deadline) {
                return changes;
            }
            frames(2);
        }
    }

    // ---- Pictures ----

    /**
     * Saves the last frame as {@code NN-<scenario>-<name>.png} and lists it in the report with what a reviewer should
     * look for. A pointer left on the editor's UI (a button just clicked) is parked over the world first, so no hover
     * highlight or tooltip covers the picture.
     */
    public void picture(String name, String lookFor) {
        // Over gameplay (a player whose editor was refused) there is no UI to hover.
        if (onClient(() -> editor.mode().isActive() && ui().isOverUi(pointerX, pointerY))) {
            parkPointer();
        }
        shoot(name, lookFor);
    }

    /** A picture with the pointer where it is: one about what the pointer hovers (a window's fade, a tooltip). */
    public void pictureAtPointer(String name, String lookFor) {
        shoot(name, lookFor);
    }

    /**
     * Parks the pointer over the world near the top middle of the screen (clear of the bars and windows), where it
     * hovers nothing of the UI, so no tooltip shows; it may still pick the world there.
     */
    public void parkPointer() {
        onClient(() -> {
            int width = client.getWindow().getScaledWidth();
            int height = client.getWindow().getScaledHeight();
            double[][] spots = {{0.5, 0.12}, {0.35, 0.12}, {0.65, 0.12}, {0.5, 0.3}, {0.5, 0.5}};
            for (double[] spot : spots) {
                double x = width * spot[0];
                double y = height * spot[1];
                if (!ui().isOverUi(x, y)) {
                    pin(x, y);
                    return;
                }
            }
        });
        frames(3);
    }

    private void shoot(String name, String lookFor) {
        frames(3);
        String file = String.format(Locale.ROOT, "%02d-%s-%s.png", ++pictureNumber, scenario, name);
        try {
            onClient(() -> {
                try (NativeImage frame = ScreenshotRecorder.takeScreenshot(client.getFramebuffer())) {
                    frame.writeTo(dir.resolve(file));
                }
            });
            report.picture(new CheckReport.Picture(file, scenario, lookFor));
        } catch (RuntimeException e) {
            SculptoryMod.LOG.warn("Play check: picture {} failed", file, e);
            report.fail(scenario, "picture " + name, "screenshot failed: " + e.getMessage());
        }
    }
}
