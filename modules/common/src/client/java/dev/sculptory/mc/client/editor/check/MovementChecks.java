package dev.sculptory.mc.client.editor.check;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sculptory.mc.client.editor.EditorClient;
import dev.sculptory.mc.client.editor.EditorScreen;
import dev.sculptory.mc.client.mixin.MouseAccessor;
import dev.sculptory.mc.loader.ClientLoaders;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * The player's own controls with the editor open: the keys bound to forward, jump, sneak and sprint go in through
 * vanilla's keyboard handler (as a player's keys do), through the editor screen and its router, and must move, lift,
 * lower and speed up the flying player, and stop it again once released; a right-drag turns it; the editor's own
 * toggle key leaves and reopens the editor. Negative checks: under the chat screen the forward key does not move the
 * player, and (on NeoForge) another in-game mapping on the forward key is not pressed under the editor screen. On
 * NeoForge the movement bindings belong to a context that is switched off under screens, so this is the scenario that
 * shows the editor screen lets them, and only them, through there.
 */
final class MovementChecks {
    static final String AREA = "movement";
    /** How long a key is held: about 15 ticks. */
    private static final long HOLD_MS = 750;
    /** How long a flying player takes to drift to a stop once its keys are up. */
    private static final long SETTLE_MS = 3_000;
    /** The player's eye height above the area's floor. */
    private static final int EYE = 12;
    /** NeoForge's key conflict contexts, looked up by name: the common code doesn't compile against a loader. */
    private static final String CONTEXTS = "net.neoforged.neoforge.client.settings.KeyConflictContext";
    private static final String CONTEXT_TYPE = "net.neoforged.neoforge.client.settings.IKeyConflictContext";

    static final ScenarioGroup GROUP = ScenarioGroup.solo(
            List.of(new Fixtures.Fixture(AREA, b -> {
            })),
            List.of(Scenario.of("editor-movement", AREA, "With the editor open, the player's bound keys: forward flies"
                    + " forward, jump up, sneak down, sprint with forward sprints, each stops once released; a"
                    + " right-drag turns the player; the toggle key leaves and reopens the editor; chat still"
                    + " blocks the keys, and another in-game mapping on forward stays up", MovementChecks::movement)));

    private MovementChecks() {}

    /** A throwaway in-game mapping that records whether anything pressed it. */
    private static final class Probe extends KeyMapping {
        private volatile boolean pressed;

        Probe(int key) {
            super("key.sculptory.check.probe", InputConstants.Type.KEYSYM, key, KeyMapping.CATEGORY_MISC);
        }

        @Override
        public void setDown(boolean down) {
            if (down) {
                pressed = true;
            }
            super.setDown(down);
        }
    }

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

        // Toggle Sneak / Toggle Sprint turn a release into nothing (the key stays on until pressed again): the
        // scenario holds and releases, so it runs with both off and puts the player's choice back after.
        boolean toggleCrouch = d.onClient(() -> options.toggleCrouch().get());
        boolean toggleSprint = d.onClient(() -> options.toggleSprint().get());
        d.onClient(() -> {
            options.toggleCrouch().set(false);
            options.toggleSprint().set(false);
        });
        OptionalInt forward;
        try {
            forward = keys(run, d, options);
        } finally {
            d.onClient(() -> {
                options.toggleCrouch().set(toggleCrouch);
                options.toggleSprint().set(toggleSprint);
            });
        }
        // The right-drag first: leaving the editor (for chat or with the toggle key) and coming back shows the quick start
        // card again in a game directory where it was never dismissed, in the middle of the screen.
        look(run, d);
        if (forward.isPresent()) {
            otherMappingStaysUp(run, d, options, forward.getAsInt());
            chatBlocks(run, d, options, forward.getAsInt());
        }
        toggle(run, d);
    }

    /** The movement keys, each held and released; returns the forward key (empty when it was skipped). */
    private static OptionalInt keys(CheckRun run, CheckDriver d, Options options) {
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
        return forward;
    }

    /**
     * On NeoForge, a second in-game mapping on the forward key (another mod's, here a throwaway one) is not pressed
     * under the editor screen: only the movement bindings are let through. Fabric keeps one mapping per key, so there
     * is no second mapping to press there.
     */
    private static void otherMappingStaysUp(CheckRun run, CheckDriver d, Options options, int forward) {
        String what = "another in-game mapping on the forward key stays up under the editor screen";
        Object inGame;
        Class<?> contextType;
        try {
            inGame = Class.forName(CONTEXTS).getField("IN_GAME").get(null);
            contextType = Class.forName(CONTEXT_TYPE);
        } catch (ReflectiveOperationException noContexts) {
            run.skip(what, "this loader has no key conflict contexts: one mapping per key");
            return;
        }
        Probe probe = d.onClient(() -> {
            Probe made = new Probe(forward);
            made.getClass().getMethod("setKeyConflictContext", contextType).invoke(made, inGame);
            return made;
        });
        try {
            d.keyDown(forward);
            d.millis(300);
            boolean forwardDown = d.onClient(() -> options.keyUp.isDown());
            d.keyUp(forward);
            run.check(what, !probe.pressed && forwardDown, "probe pressed " + probe.pressed + ", forward down "
                    + forwardDown);
        } finally {
            // Off the key, out of the key lookup.
            d.onClient(() -> {
                probe.setKey(InputConstants.UNKNOWN);
                KeyMapping.resetMapping();
            });
        }
        d.millis(SETTLE_MS);
    }

    /** The chat screen (opened from the editor with the chat key) keeps the forward key from moving the player. */
    private static void chatBlocks(CheckRun run, CheckDriver d, Options options, int forward) {
        OptionalInt chat = key(run, d, options.keyChat);
        if (chat.isEmpty()) {
            return;
        }
        d.key(chat.getAsInt(), 0);
        boolean open = d.until(5_000, () -> d.client().screen instanceof ChatScreen);
        if (!run.check("the chat key opens chat from the editor", open,
                "screen: " + d.onClient(() -> String.valueOf(d.client().screen)))) {
            return;
        }
        Vec3 start = position(d);
        d.keyDown(forward);
        d.millis(HOLD_MS);
        String held = d.onClient(() -> state(d.client(), options.keyUp));
        Vec3 moved = position(d);
        d.keyUp(forward);
        run.check("forward held under the chat screen does not move the player", start.distanceTo(moved) < 0.1,
                travel(start, moved) + "; " + held);
        d.key(GLFW.GLFW_KEY_ESCAPE, 0);
        boolean back = d.until(5_000, () -> d.client().screen instanceof EditorScreen && d.editor().mode().isActive());
        if (!back) {
            run.note("the editor did not come back by itself after chat; reopened");
            d.enterEditor();
        }
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

    /**
     * The keyboard key the player bound to {@code binding}. Empty, with the binding's checks skipped, for a mouse
     * button, no key, or a key with a modifier (NeoForge's Ctrl+W): NeoForge reads a binding's modifier from the
     * keyboard itself ({@code Screen.hasControlDown}), which keys sent through the keyboard handler don't hold down.
     */
    private static OptionalInt key(CheckRun run, CheckDriver d, KeyMapping binding) {
        InputConstants.Key bound = d.onClient(() -> ClientLoaders.get().boundKey(binding));
        String modifier = modifier(binding);
        if (!modifier.equals("NONE")) {
            run.skip(binding.getName(), "bound with the modifier " + modifier + " (" + bound.getName() + "): the check"
                    + " can't hold a modifier key the way the game reads it");
            return OptionalInt.empty();
        }
        if (bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() > 0) {
            return OptionalInt.of(bound.getValue());
        }
        run.skip(binding.getName(), "not bound to a keyboard key: " + bound.getName());
        return OptionalInt.empty();
    }

    /** NeoForge's key modifier of a binding ({@code NONE}, {@code CONTROL}, ...); {@code NONE} on Fabric, which has none. */
    private static String modifier(KeyMapping binding) {
        try {
            return String.valueOf(binding.getClass().getMethod("getKeyModifier").invoke(binding));
        } catch (ReflectiveOperationException noModifiers) {
            return "NONE";
        }
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
