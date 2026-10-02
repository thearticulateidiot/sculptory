package dev.sculptory.fabric.client.builder;

import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.session.EditorSession;
import dev.sculptory.fabric.client.tinker.TinkerController;
import dev.sculptory.server.engine.Perm;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.lwjgl.glfw.GLFW;

/**
 * The Tinker power ("Builder mode"): Tinker's {@link TinkerController} driven from
 * builder mode. While the power is on and Alt is held the crosshair is Tinker's: each frame the controller aims at
 * what the crosshair is on ({@link #frame}); a wheel notch goes to it when it changes something there (Alt+Scroll: the
 * shown property's next value, or an entity's turn; Alt+Shift+Scroll: another property), else the notch is the game's;
 * a left click with Alt opens the panel for the block or entity. Releasing Alt, or switching the power off, aims at
 * nothing. Minecraft-free; {@link BuilderClient} feeds it the crosshair and the keys. Client thread only.
 */
public final class TinkerPower implements PowerController {
    /** Why the power cannot be switched on: the session lacks {@code region}. */
    public static final String NEEDS_REGION = "sculptory.builder.power.tinker.needs_region";

    private final TinkerController controller;
    private final Consumer<TinkerController.Target> panels;
    private boolean on;
    private boolean aiming;

    /** @param panels opens the panel for the block or entity a click chose */
    public TinkerPower(TinkerController controller, Consumer<TinkerController.Target> panels) {
        this.controller = Objects.requireNonNull(controller);
        this.panels = Objects.requireNonNull(panels);
    }

    public TinkerController controller() {
        return controller;
    }

    public boolean on() {
        return on;
    }

    /** Whether Tinker owns the crosshair right now: the power on and Alt held since the last frame. */
    public boolean aiming() {
        return aiming;
    }

    /**
     * Why the power cannot be switched on ({@code BuilderClient.NOT_AVAILABLE} without a session, {@link #NEEDS_REGION},
     * {@link TinkerController#NOT_OFFERED}), or {@code null}.
     */
    public static String gate(Optional<? extends EditorSession> session) {
        if (session.isEmpty()) return BuilderClient.NOT_AVAILABLE;
        if (!session.get().permissions().has(Perm.REGION)) return NEEDS_REGION;
        if (!session.get().tinkerOffered()) return TinkerController.NOT_OFFERED;
        return null;
    }

    @Override
    public void activate() {
        on = true;
    }

    @Override
    public void deactivate() {
        on = false;
        release();
    }

    /**
     * One frame: with the power on and Alt held, Tinker aims at {@code cursor} (the crosshair's block, a miss, or
     * {@code null} for nothing); without Alt it aims at nothing again.
     */
    public void frame(boolean altDown, WorldCursor cursor) {
        if (!on) return;
        if (altDown) {
            aiming = true;
            controller.aim(cursor);
        } else {
            release();
        }
    }

    private void release() {
        if (!aiming) return;
        aiming = false;
        controller.aim(null);
    }

    /** A wheel notch: Tinker's while aiming at something Scroll changes; else the game's (the hotbar). */
    @Override
    public boolean onScroll(double amount, int modifiers) {
        if (!on || !aiming || !Modifiers.alt(modifiers) || !controller.takesScroll()) return false;
        return controller.scroll(amount, Modifiers.shift(modifiers));
    }

    /** A left click with Alt over a block or entity opens its panel (or toasts why not) and is Tinker's. */
    @Override
    public boolean onClick(int button, int modifiers) {
        if (!on || !aiming || button != GLFW.GLFW_MOUSE_BUTTON_LEFT || !Modifiers.alt(modifiers)) return false;
        if (controller.hovered().isEmpty()) return false;
        controller.click().ifPresent(panels);
        return true;
    }

    /** The label beside the crosshair, or "" while Tinker is not aiming. */
    public String label() {
        return aiming ? controller.label() : "";
    }

    /** The outline of what Tinker aims at (min x, y, z, max x, y, z), while it aims. */
    public Optional<double[]> outline() {
        return aiming ? controller.outline() : Optional.empty();
    }
}
