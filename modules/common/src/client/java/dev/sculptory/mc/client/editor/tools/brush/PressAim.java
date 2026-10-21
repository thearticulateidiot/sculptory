package dev.sculptory.mc.client.editor.tools.brush;

import dev.sculptory.mc.client.editor.tool.WorldCursor;
import dev.sculptory.mc.client.editor.world.Ray;
import java.util.Objects;

/**
 * Where one press of a stamping brush (the Shape brush, the Heightmap brush) aims: its first hit until the cursor has
 * moved, the cursor from then on. The cursor has moved once its ray turned more than {@value #DRAG_START_DEGREES}° from
 * the press's, or the camera moved more than {@value #DRAG_START_BLOCKS} blocks. So a click held still, or with a
 * shaking hand, stamps once however long it is held, whatever its stamp does to the ground under the cursor; a drag
 * paints along. Client thread only.
 */
final class PressAim {
    /**
     * How far the cursor ray must turn from the press's (degrees) before a press paints along the drag: about 6 pixels
     * of a 1600-pixel-high window at the default field of view, more than a hand shakes on a click.
     */
    static final double DRAG_START_DEGREES = 0.25;
    /** How far the camera must move during a press (blocks) before it paints along: flying with the button held. */
    static final double DRAG_START_BLOCKS = 0.25;
    private static final double DRAG_START_COS = Math.cos(Math.toRadians(DRAG_START_DEGREES));

    private final BrushServices services;
    /** The cursor ray when the press began, or {@code null} without one. */
    private Ray pressRay;
    /** The press's first hit, where it aims until the cursor moves; {@code null} until the cursor hits. */
    private WorldCursor anchor;
    /** Whether the cursor has moved since the press began: the press paints along the drag. */
    private boolean dragging;

    PressAim(BrushServices services) {
        this.services = Objects.requireNonNull(services);
    }

    /** A press begins with the cursor at {@code cursor}. */
    void begin(WorldCursor cursor) {
        pressRay = services.cursorRay().orElse(null);
        anchor = cursor.missed() ? null : cursor;
        dragging = false;
    }

    /** The press ended. */
    void end() {
        pressRay = null;
        anchor = null;
        dragging = false;
    }

    /**
     * Where the press aims with the cursor at {@code next}: its first hit until the cursor moves ({@link #moved}), the
     * cursor from then on.
     */
    WorldCursor target(WorldCursor next) {
        if (!dragging && moved(next)) {
            dragging = true;
        }
        if (dragging) {
            return next;
        }
        if (anchor == null && !next.missed()) {
            anchor = next;
        }
        return anchor == null ? next : anchor;
    }

    /** Whether {@code target} ({@link #target}'s answer) is the press's first hit, held: the cursor has not moved. */
    boolean holding(WorldCursor target) {
        return !dragging && target == anchor;
    }

    /**
     * Whether the cursor has moved since the press began: its ray turned more than {@value #DRAG_START_DEGREES}° or
     * moved more than {@value #DRAG_START_BLOCKS} blocks. A pick without a ray (no camera for a moment) is no move.
     * Without a ray when the press began (tools built without the game) any hit other than the press's first is a move.
     */
    private boolean moved(WorldCursor next) {
        Ray now = services.cursorRay().orElse(null);
        if (pressRay == null) {
            return anchor != null && !anchor.equals(next);
        }
        if (now == null) {
            return false;
        }
        double turned = pressRay.dirX() * now.dirX() + pressRay.dirY() * now.dirY() + pressRay.dirZ() * now.dirZ();
        double dx = now.originX() - pressRay.originX();
        double dy = now.originY() - pressRay.originY();
        double dz = now.originZ() - pressRay.originZ();
        return turned < DRAG_START_COS || dx * dx + dy * dy + dz * dz > DRAG_START_BLOCKS * DRAG_START_BLOCKS;
    }
}
