package dev.sculptory.fabric.client.nav;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.region.Facing;
import dev.sculptory.fabric.client.editor.CursorPick;
import dev.sculptory.fabric.client.editor.EditorClient;
import dev.sculptory.fabric.client.editor.world.WorldRaycaster;
import dev.sculptory.fabric.client.net.ClientNet;
import dev.sculptory.fabric.client.session.FabricEditorSession;
import dev.sculptory.fabric.client.session.Notice;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/**
 * Jump and Through in normal play ({@link Navigation}): a Minecraft key binding, {@code J} by default (Options, Controls,
 * Sculptory), with Shift for Through. It aims along the crosshair as far as the editor's cursor picks (the render
 * distance, at most 512 blocks). The editor has its own keys ({@code KeyAction.JUMP}, {@code JUMP_THROUGH}); while it is
 * open this binding does nothing. Problems show as the editor's toasts, which are drawn outside the editor too.
 */
public final class JumpKey {
    public static final String KEY = "key.sculptory.jump";
    private static JumpKey instance;

    private final MinecraftClient client;
    private final KeyBinding key;
    private final EditorClient editor;

    private JumpKey(MinecraftClient client, KeyBinding key, EditorClient editor) {
        this.client = client;
        this.key = key;
        this.editor = editor;
    }

    /** Registers the key binding and its tick. Called once, from {@link EditorClient#init}. */
    public static void init(EditorClient editor) {
        if (instance != null) return;
        KeyBinding key = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(KEY, InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, EditorClient.CATEGORY));
        instance = new JumpKey(MinecraftClient.getInstance(), key, editor);
        ClientTickEvents.END_CLIENT_TICK.register(ignored -> instance.tick());
    }

    /** The editor's Jump and Through ({@code EditorController.setNavigator}): the cursor's pick, sent by this session. */
    public static BiConsumer<CursorPick, Boolean> editorNavigator(EditorClient editor) {
        return (pick, through) -> {
            FabricEditorSession session = ClientNet.session();
            Navigation.fromPick(session == null ? null : session::navigate, pick, through).ifPresent(editor::toast);
        };
    }

    private void tick() {
        while (key.wasPressed()) {
            if (editor.mode().isEditing() || client.currentScreen != null || client.player == null || client.world == null) {
                continue;
            }
            jump(Screen.hasShiftDown());
        }
    }

    private void jump(boolean through) {
        Entity camera = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
        double range = WorldRaycaster.maxDistance(client.options.getClampedViewDistance());
        HitResult hit = camera.raycast(range, 1f, false);
        Optional<Notice> problem;
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            problem = Optional.of(Notice.of(Notice.Level.INFO, Navigation.NOTHING_LOOKED_AT));
        } else {
            Vec3d look = camera.getRotationVec(1f);
            FabricEditorSession session = ClientNet.session();
            net.minecraft.util.math.BlockPos pos = block.getBlockPos();
            problem = Navigation.request(session == null ? null : session::navigate, through,
                    new BlockPos(pos.getX(), pos.getY(), pos.getZ()), Facing.valueOf(block.getSide().name()),
                    look.x, look.y, look.z);
        }
        problem.ifPresent(editor::toast);
    }
}
