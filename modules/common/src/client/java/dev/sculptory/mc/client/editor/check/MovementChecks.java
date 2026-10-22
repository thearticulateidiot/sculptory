package dev.sculptory.mc.client.editor.check;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sculptory.mc.client.editor.EditorClient;
import dev.sculptory.mc.client.mixin.MouseAccessor;
import dev.sculptory.mc.loader.ClientLoaders;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * The player's own controls with the editor open: the keys bound to forward, jump, sneak and sprint go in through
 * vanilla's keyboard handler (as a player's keys do), through the editor screen and its router, and must move, lift,
 * lower and speed up the flying player, and stop it again once released; a right-drag turns it; the editor's own
 * toggle key leaves and reopens the editor. On NeoForge the movement bindings belong to a context that is switched off
 * under screens, so this is the scenario that shows the editor screen lets them through there.
 */
final class MovementChecks {
    static final String AREA = "movement";
    /** How long a key is held: about 15 ticks. */
    private static final long HOLD_MS = 750;
    /** How long a flying player takes to drift to a stop once its keys are up. */
    private static final long SETTLE_MS = 3_000;
    /** The player's eye height above the area's floor. */
    private static final int EYE = 12;

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(AREA, b -> {
            })),
            List.of(Scenario.of("editor-movement", AREA, "With the editor open, the player's bound keys: forward flies"
                    + " forward, jump up, sneak down, sprint with forward sprints, each stops once released; a"
                    + " right-drag turns the player", MovementChecks::movement)));

    private MovementChecks() {}

    private static void movement(CheckRun run) {
        CheckDriver d = run.driver();
        Area a = run.area();
        Options options = d.client().options;
        // In the middle of the area, flying, looking north (towards smaller z) along the level.
        double x = a.x(20) + 0.5;
        double z = a.z(30) + 0.5;
        d.view(x, a.y(EYE), z, x, a.y(EYE), z - 20);
        run.require("the editor screen is open", d.onClient(() -> d.client().screen != null
                && d.editor().mode().isActive()), "no editor screen");

        OptionalInt forward = key(run, d, options.keyUp);
        if (forward.isPresent()) {
            Vec3 start = position(d);
            d.keyDown(forward.getAsInt());
            d.millis(HOLD_MS);
            String held = d.onClient(() -> state(d.client(), options.keyUp));
            Vec3 moved = position(d);
            d.keyUp(forward.getAsInt());
            run.check("forward held under the editor screen flies the player forward", start.z - moved.z > 1.5
                    && Math.abs(moved.x - start.x) < 0.5, travel(start, moved) + "; " + held);
            stops(run, d, options.keyUp);
        }

        OptionalInt jump = key(run, d, options.keyJump);
        if (jump.isPresent()) {
            Vec3 start = position(d);
            d.keyDown(jump.getAsInt());
            d.millis(HOLD_MS);
            String held = d.onClient(() -> state(d.client(), options.keyJump));
            Vec3 moved = position(d);
            d.keyUp(jump.getAsInt());
            run.check("jump held under the editor screen lifts the flying player", moved.y - start.y > 1.0,
                    travel(start, moved) + "; " + held);
            stops(run, d, options.keyJump);
        }

        OptionalInt sneak = key(run, d, options.keyShift);
        if (sneak.isPresent()) {
            Vec3 start = position(d);
            d.keyDown(sneak.getAsInt());
            d.millis(HOLD_MS);
            String held = d.onClient(() -> state(d.client(), options.keyShift));
            Vec3 moved = position(d);
            d.keyUp(sneak.getAsInt());
            run.check("sneak held under the editor screen lowers the flying player", start.y - moved.y > 1.0,
                    travel(start, moved) + "; " + held);
            stops(run, d, options.keyShift);
        }

        OptionalInt sprint = key(run, d, options.keySprint);
        if (sprint.isPresent() && forward.isPresent()) {
            d.keyDown(sprint.getAsInt());
            d.keyDown(forward.getAsInt());
            boolean sprinting = d.until(2_000, () -> d.client().player.isSprinting());
            String held = d.onClient(() -> state(d.client(), options.keySprint));
            d.keyUp(forward.getAsInt());
            d.keyUp(sprint.getAsInt());
            run.check("sprint with forward under the editor screen sprints", sprinting, held);
            stops(run, d, options.keySprint);
        }

        look(run, d);
        toggle(run, d);
    }

    /** Right-drag: the cursor moves 200 window pixels right while the right button is held. */
    private static void look(CheckRun run, CheckDriver d) {
        d.pointScreen(d.onClient(() -> d.client().getWindow().getGuiScaledWidth() / 2.0),
                d.onClient(() -> d.client().getWindow().getGuiScaledHeight() / 2.0));
        if (d.onClient(() -> d.ui().isOverUi(d.pointerX(), d.pointerY()))) {
            run.skip("a right-drag turns the player", "the middle of the screen is under the editor's UI");
            return;
        }
        float yaw = d.onClient(() -> d.client().player.getYRot());
        d.press(GLFW.GLFW_MOUSE_BUTTON_RIGHT, 0);
        d.frames(3);
        d.onClient(() -> {
            MouseAccessor mouse = (MouseAccessor) d.client().mouseHandler;
            mouse.sculptory$setX(d.client().mouseHandler.xpos() + 200);
        });
        d.frames(3);
        float turned = d.onClient(() -> d.client().player.getYRot());
        d.release(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        run.check("a right-drag under the editor screen turns the player", turned - yaw > 5,
                String.format(Locale.ROOT, "yaw %.1f -> %.1f", yaw, turned));
    }

    /**
     * The editor's own toggle key (registered through the loader, B by default) leaves the editor from under its screen
     * and opens it again from the game, where the loader's key mapping takes the press.
     */
    private static void toggle(CheckRun run, CheckDriver d) {
        KeyMapping toggle = d.onClient(() -> Arrays.stream(d.client().options.keyMappings)
                .filter(binding -> binding.getName().equals(EditorClient.TOGGLE_KEY)).findFirst().orElse(null));
        run.require("the editor's toggle key is registered with the game", toggle != null, "not in the key list");
        OptionalInt key = key(run, d, toggle);
        if (key.isEmpty()) {
            return;
        }
        d.key(key.getAsInt(), 0);
        run.check("the toggle key leaves the editor", d.until(5_000, () -> !d.editor().mode().isActive()
                && d.client().screen == null), "screen: " + d.onClient(() -> String.valueOf(d.client().screen)));
        d.frames(5);
        d.key(key.getAsInt(), 0);
        run.check("the toggle key opens the editor again", d.until(5_000, () -> d.editor().mode().isActive()
                && d.client().screen != null), "screen: " + d.onClient(() -> String.valueOf(d.client().screen)));
    }

    /** The keyboard key the player bound to {@code binding}; empty (and the check skipped) for a mouse button or none. */
    private static OptionalInt key(CheckRun run, CheckDriver d, KeyMapping binding) {
        InputConstants.Key bound = d.onClient(() -> ClientLoaders.get().boundKey(binding));
        if (bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() > 0) {
            return OptionalInt.of(bound.getValue());
        }
        run.skip(binding.getName(), "not bound to a keyboard key: " + bound.getName());
        return OptionalInt.empty();
    }

    /** After a key is released its binding is up and the player comes to rest (no key stuck down). */
    private static void stops(CheckRun run, CheckDriver d, KeyMapping binding) {
        String up = d.onClient(() -> state(d.client(), binding));
        d.millis(SETTLE_MS);
        Vec3 before = position(d);
        d.millis(500);
        Vec3 after = position(d);
        boolean released = d.onClient(() -> !binding.isDown());
        run.check(binding.getName() + " released: its binding is up and the player stops", released
                && before.distanceTo(after) < 0.1, travel(before, after) + " in 0.5 s; after release " + up);
    }

    private static Vec3 position(CheckDriver d) {
        return d.onClient(() -> d.client().player.position());
    }

    private static String travel(Vec3 from, Vec3 to) {
        return String.format(Locale.ROOT, "moved %.2f %.2f %.2f", to.x - from.x, to.y - from.y, to.z - from.z);
    }

    /** What the game sees of a binding and the player's input. Client thread. */
    private static String state(Minecraft client, KeyMapping binding) {
        LocalPlayer player = client.player;
        return binding.getName() + " isDown " + binding.isDown() + ", input up " + player.input.up + " jump "
                + player.input.jumping + " sneak " + player.input.shiftKeyDown + ", sprinting " + player.isSprinting();
    }
}
