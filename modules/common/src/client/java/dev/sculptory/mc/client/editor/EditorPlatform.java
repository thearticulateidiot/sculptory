package dev.sculptory.mc.client.editor;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.mc.client.editor.tool.RayOverlay;
import dev.sculptory.mc.client.editor.tool.RaycastMode;
import dev.sculptory.mc.client.editor.world.ScreenProjector;
import java.util.List;
import java.util.Optional;

/**
 * The Minecraft-side services the editor controller needs. The game implementation is
 * {@code mc.McEditorPlatform}; tests use a fake.
 */
public interface EditorPlatform {
    /** Keys vanilla owns that also act in the editor. */
    enum SystemKey { NONE, TOGGLE_EDITOR, CHAT, COMMAND, INVENTORY }

    /**
     * Casts the cursor ray through a GUI-scaled screen point (or along the crosshair) and picks the
     * world with the tool's raycast mode, seeing the cells of {@code overlay} (the tool's {@link
     * dev.sculptory.mc.client.editor.tool.Tool#rayOverlay}, or {@code null}) as it says.
     */
    CursorPick pick(double x, double y, RaycastMode mode, boolean crosshair, RayOverlay overlay);

    /**
     * "Aim at water and lava": whether {@link #pick} also stops at water and lava surfaces rather than passing
     * through them to the blocks beneath. Off by default; kept for the game session, not saved.
     */
    boolean aimsAtFluids();

    /** Turns "Aim at water and lava" on or off for every later {@link #pick}. */
    void setAimAtFluids(boolean aim);

    /** The exact block state at a position of the client world, if loaded. */
    Optional<BlockDescriptor> blockAt(BlockPos pos);

    /** The block's display name ("Oak Planks"). */
    String blockName(BlockDescriptor block);

    /** Sets the player's fly speed to {@code multiplier} × their own while the editor is open (any game mode). */
    void applyFlySpeed(double multiplier);

    /**
     * The editor closed: from now on the game mode decides (the multiplier stays in creative and spectator flight;
     * the player's own speed comes back in the other modes).
     */
    void restoreFlySpeed();

    /** The camera yaw in degrees. */
    float cameraYaw();

    /** Projects world points into GUI-scaled screen pixels, from the last rendered frame. */
    Optional<ScreenProjector> projector();

    /** The camera position of the last rendered frame. */
    Optional<double[]> eye();

    /** Which vanilla key (by the player's own bindings) a key press is. */
    SystemKey systemKey(int key, int scanCode);

    /** The names of the players online now (the tab list), the local player included; for the Access dialog. */
    default List<String> onlinePlayerNames() {
        return List.of();
    }

    /** The painting variant ids the client knows (Tinker's painting picker), sorted; none by default. */
    default List<String> paintingVariants() {
        return List.of();
    }

    /** The item id of a block (Tinker's item display "Use active block"), or "" when it has no item. */
    default String itemOf(BlockDescriptor block) {
        return "";
    }
}
