package dev.sculptory.fabric.client.editor.input;

import java.util.Objects;

/**
 * Right-button look. While the button is held the cursor is hidden and captured, and raw cursor
 * movement turns the player exactly as vanilla mouse look does; on release the cursor comes back
 * where it was. Call {@link #frame()} once per rendered frame.
 */
public final class CameraLook implements InputRouter.Look {
    /** Minecraft-side effects. */
    public interface Backend {
        /** Raw cursor position in window coordinates ({@code Mouse.getX()/getY()}). */
        double mouseX();

        double mouseY();

        /** Hides and captures the cursor, starting from (x, y). */
        void captureCursor(double x, double y);

        /** Shows the cursor again at (x, y) and tells vanilla's mouse handler it is there. */
        void releaseCursor(double x, double y);

        /** {@code player.changeLookDirection(yaw, pitch)}. */
        void turn(double yawDelta, double pitchDelta);

        /** The mouse sensitivity option, 0..1. */
        double sensitivity();

        boolean invertY();
    }

    private final Backend backend;
    private boolean looking;
    private boolean skipNextFrame;
    private double startX;
    private double startY;
    private double lastX;
    private double lastY;

    public CameraLook(Backend backend) {
        this.backend = Objects.requireNonNull(backend);
    }

    /** Vanilla's sensitivity curve: {@code (s * 0.6 + 0.2)^3 * 8}. */
    public static double factor(double sensitivity) {
        double s = sensitivity * 0.6 + 0.2;
        return s * s * s * 8.0;
    }

    @Override
    public boolean isLooking() {
        return looking;
    }

    @Override
    public void begin() {
        if (looking) {
            return;
        }
        startX = backend.mouseX();
        startY = backend.mouseY();
        backend.captureCursor(startX, startY);
        looking = true;
        // Capturing can move the reported cursor once; measure from the next frame.
        skipNextFrame = true;
    }

    /** Applies the cursor movement since the last frame. */
    public void frame() {
        if (!looking) {
            return;
        }
        double x = backend.mouseX();
        double y = backend.mouseY();
        if (skipNextFrame) {
            skipNextFrame = false;
        } else {
            double dx = x - lastX;
            double dy = y - lastY;
            if (dx != 0 || dy != 0) {
                double g = factor(backend.sensitivity());
                backend.turn(dx * g, dy * g * (backend.invertY() ? -1 : 1));
            }
        }
        lastX = x;
        lastY = y;
    }

    @Override
    public void end() {
        if (!looking) {
            return;
        }
        looking = false;
        backend.releaseCursor(startX, startY);
    }
}
