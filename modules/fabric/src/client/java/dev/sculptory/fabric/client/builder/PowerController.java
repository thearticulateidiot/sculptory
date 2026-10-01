package dev.sculptory.fabric.client.builder;

/**
 * What a builder power that needs more than a bit on the wire does on the client. The powers the server carries out (Long reach, Replace, ...) have none; Tinker registers one
 * ({@link BuilderClient#registerTinker}) and until it does its entry in the ring is greyed out ("coming with Tinker").
 * Client thread only.
 */
public interface PowerController {
    /** The power was switched on (also right after registering while it is on). */
    void activate();

    /** The power was switched off, or the player left the world. */
    void deactivate();

    /**
     * The scroll wheel turned outside the editor while the power is on.
     *
     * @param amount the wheel's vertical amount (positive: up)
     * @param modifiers the GLFW modifier bits held
     * @return whether the controller took it (vanilla then keeps its hotbar slot)
     */
    default boolean onScroll(double amount, int modifiers) {
        return false;
    }

    /**
     * A mouse button was pressed on a block outside the editor while the power is on, before builder mode places or
     * breaks anything.
     *
     * @param button the GLFW mouse button
     * @param modifiers the GLFW modifier bits held
     * @return whether the controller took the click (nothing is placed or broken then)
     */
    default boolean onClick(int button, int modifiers) {
        return false;
    }
}
