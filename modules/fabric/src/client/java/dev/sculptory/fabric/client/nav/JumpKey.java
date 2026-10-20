package dev.sculptory.fabric.client.nav;

import com.mojang.blaze3d.platform.InputConstants;
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
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
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

    private final Minecraft client;
    private final KeyMapping key;
    private final EditorClient editor;

    private JumpKey(Minecraft client, KeyMapping key, EditorClient editor) {
        this.client = client;
        this.key = key;
        this.editor = editor;
    }

    /** Registers the key binding and its tick. Called once, from {@link EditorClient#init}. */
    public static void init(EditorClient editor) {
        if (instance != null) return;
        KeyMapping key = KeyBindingHelper.registerKeyBinding(
                new KeyMapping(KEY, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, EditorClient.CATEGORY));
        instance = new JumpKey(Minecraft.getInstance(), key, editor);
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
        while (key.consumeClick()) {
            if (editor.mode().isEditing() || client.screen != null || client.player == null || client.level == null) {
                continue;
            }
            jump(Screen.hasShiftDown());
        }
    }

    private void jump(boolean through) {
        Entity camera = client.getCameraEntity() != null ? client.getCameraEntity() : client.player;
        double range = WorldRaycaster.maxDistance(client.options.getEffectiveRenderDistance());
        HitResult hit = camera.pick(range, 1f, false);
        Optional<Notice> problem;
        if (!(hit instanceof BlockHitResult block) || hit.getType() != HitResult.Type.BLOCK) {
            problem = Optional.of(Notice.of(Notice.Level.INFO, Navigation.NOTHING_LOOKED_AT));
        } else {
            Vec3 look = camera.getViewVector(1f);
            FabricEditorSession session = ClientNet.session();
            net.minecraft.core.BlockPos pos = block.getBlockPos();
            problem = Navigation.request(session == null ? null : session::navigate, through,
                    new BlockPos(pos.getX(), pos.getY(), pos.getZ()), Facing.valueOf(block.getDirection().name()),
                    look.x, look.y, look.z);
        }
        problem.ifPresent(editor::toast);
    }
}
