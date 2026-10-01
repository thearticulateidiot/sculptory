package dev.sculptory.fabric.client.editor.tools.brush;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.fabric.client.editor.tool.Modifiers;
import dev.sculptory.fabric.client.editor.tool.PointerEvent;
import dev.sculptory.fabric.client.editor.tool.ToolContext;
import dev.sculptory.fabric.client.editor.tool.WorldDraw;
import dev.sculptory.fabric.client.session.Notice;
import java.util.Locale;
import java.util.Objects;

/**
 * Drawing the Gradient pattern's line with Alt+drag, for a tool whose mix is set to Gradient: the press takes the block under the cursor as the start, the drag follows the block under the cursor,
 * and the release sets the shared {@link GradientLine} from the start to that block (a release on the start block sets
 * nothing and says so). Esc drops the drag. While the tool shows its pattern the line is drawn as an arrow: the drag in
 * progress, else the line set. One per tool; client thread only.
 */
public final class GradientDrag {
    /** Toast: the line was set, with its ends and length. */
    public static final String LINE_SET = "sculptory.notice.gradient_line_set";
    /** Toast: the release was on the start block. */
    public static final String LINE_TOO_SHORT = "sculptory.notice.gradient_line_short";
    /** Toast and refusal: a Gradient press or Fill without a line. */
    public static final String NO_LINE = "sculptory.notice.gradient_no_line";

    private final GradientLine line;
    private BlockPos start;
    private BlockPos end;

    public GradientDrag(GradientLine line) {
        this.line = Objects.requireNonNull(line);
    }

    /** Whether a line is being drawn. */
    public boolean active() {
        return start != null;
    }

    /**
     * An Alt+left press: starts drawing at the block under the cursor (true: the press is the line's), or does nothing
     * (false) without Alt or over the sky.
     */
    public boolean press(ToolContext c, PointerEvent e) {
        if (e.button() != PointerEvent.LEFT || !Modifiers.alt(e.modifiers()) || e.cursor().missed()) {
            return false;
        }
        start = e.cursor().pos();
        end = start;
        c.setPointerCapture(true);
        return true;
    }

    /** The drag follows the block under the cursor (the sky keeps the last block). */
    public void drag(PointerEvent e) {
        if (active() && !e.cursor().missed()) {
            end = e.cursor().pos();
        }
    }

    /** The release sets the line from the start to the block under the cursor, with a toast. */
    public void release(ToolContext c, PointerEvent e) {
        if (!active()) {
            return;
        }
        drag(e);
        BlockPos from = start, to = end;
        start = null;
        end = null;
        c.setPointerCapture(false);
        if (!line.set(from, to)) {
            c.notify(Notice.of(Notice.Level.INFO, LINE_TOO_SHORT));
            return;
        }
        c.notify(Notice.of(Notice.Level.INFO, LINE_SET, format(from), format(to),
                String.format(Locale.ROOT, "%.0f", line.length())));
    }

    /** Drops a drag in progress (Esc, the tool put away). True when there was one. */
    public boolean cancel(ToolContext c) {
        if (!active()) {
            return false;
        }
        start = null;
        end = null;
        c.setPointerCapture(false);
        return true;
    }

    /** The arrow of the drag in progress, else of the line set, else nothing. */
    public void render(WorldDraw d, int color) {
        if (active()) {
            arrow(d, start, end, color);
        } else if (line.isSet()) {
            arrow(d, line.from(), line.to(), color);
        }
    }

    /**
     * An arrow from block {@code from}'s centre to block {@code to}'s, seen through terrain: the blocks at both ends
     * outlined, and a head of four short lines at {@code to}.
     */
    static void arrow(WorldDraw d, BlockPos from, BlockPos to, int color) {
        d.seeThrough(true);
        d.boxOutline(Box.of(from), color);
        d.boxOutline(Box.of(to), color);
        double x0 = from.x() + 0.5, y0 = from.y() + 0.5, z0 = from.z() + 0.5;
        double x1 = to.x() + 0.5, y1 = to.y() + 0.5, z1 = to.z() + 0.5;
        d.line(x0, y0, z0, x1, y1, z1, color);
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length == 0) {
            d.seeThrough(false);
            return;
        }
        dx /= length;
        dy /= length;
        dz /= length;
        // Two directions across the line: one horizontal (or along x for a vertical line), one perpendicular to both.
        double ax = -dz, ay = 0, az = dx;
        double across = Math.sqrt(ax * ax + az * az);
        if (across < 1e-6) {
            ax = 1;
            az = 0;
        } else {
            ax /= across;
            az /= across;
        }
        double bx = dy * az - dz * ay, by = dz * ax - dx * az, bz = dx * ay - dy * ax;
        double back = Math.min(1.2, length / 3), side = back / 2;
        double hx = x1 - dx * back, hy = y1 - dy * back, hz = z1 - dz * back;
        d.line(x1, y1, z1, hx + ax * side, hy + ay * side, hz + az * side, color);
        d.line(x1, y1, z1, hx - ax * side, hy - ay * side, hz - az * side, color);
        d.line(x1, y1, z1, hx + bx * side, hy + by * side, hz + bz * side, color);
        d.line(x1, y1, z1, hx - bx * side, hy - by * side, hz - bz * side, color);
        d.seeThrough(false);
    }

    /** A block as the player reads it: "12 64 -3". */
    static String format(BlockPos pos) {
        return pos.x() + " " + pos.y() + " " + pos.z();
    }
}
