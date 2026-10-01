package dev.sculptory.fabric.client.editor.tool;

/**
 * Cells the cursor ray of a tool sees as other states than the world holds now ({@link Tool#rayOverlay}): a Shape brush
 * press sees the world as it was before it wrote, so the shapes it placed never become the surface it aims at. States
 * are handles of the client's state space (raw block-state ids in the game). Client thread only.
 */
@FunctionalInterface
public interface RayOverlay {
    /** {@link #stateAt}'s answer for a cell the ray sees as the world holds it. */
    int WORLD = -1;

    /** The state the ray sees at (x, y, z), or {@link #WORLD} for the world's own. */
    int stateAt(int x, int y, int z);
}
