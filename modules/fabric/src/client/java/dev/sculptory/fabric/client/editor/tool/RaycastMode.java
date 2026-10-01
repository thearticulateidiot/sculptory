package dev.sculptory.fabric.client.editor.tool;

/** What the cursor ray hits for a tool. */
public enum RaycastMode {
    /** No world ray (e.g. a tool that only uses windows). */
    NONE,
    /** Collision shapes: terrain tools, so grass and flowers are ignored. */
    TERRAIN,
    /** Outline shapes: block tools, so every block is targetable. */
    BLOCKS,
    /**
     * Outline shapes and the surfaces of water and lava, whatever the "aim at water and lava" toggle says: the Fluid
     * tool's Drain, which needs the fluid under the cursor.
     */
    FLUIDS
}
