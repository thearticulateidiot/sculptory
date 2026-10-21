package dev.sculptory.mc.client.editor.tools.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.Box;
import dev.sculptory.core.world.WorldReader;
import dev.sculptory.mc.client.editor.render.OverlayColors;
import dev.sculptory.mc.client.editor.tool.HudDraw;
import dev.sculptory.mc.client.editor.tool.KeyHint;
import dev.sculptory.mc.client.editor.tool.Modifiers;
import dev.sculptory.mc.client.editor.tool.ScrollEvent;
import dev.sculptory.mc.client.editor.tool.ToolContext;
import dev.sculptory.mc.client.editor.tool.WorldCursor;
import dev.sculptory.mc.client.editor.tool.WorldDraw;
import dev.sculptory.mc.client.editor.world.Ray;
import java.util.List;
import java.util.function.Function;

/**
 * Points in the air, for the shared point editor ({@code PointEditor}) of the tools whose clicks make path points
 * (Extrude along a path and to a point; Generate's Road, Line, Sweep and Stamp).
 *
 * <ul>
 *   <li><b>A block wins.</b> With the cursor on a block a point goes where the tool puts it on a block.</li>
 *   <li><b>The marker.</b> With the cursor on open sky (the ray meets nothing within the picking range) a marker
 *   floats {@link #distance()} blocks ahead along the ray, in the cell it falls in; a click puts a point there.
 *   Alt+Scroll moves it nearer or farther, a block per notch (Shift: 4), between {@value #MIN_DISTANCE} and
 *   {@value #MAX_DISTANCE} blocks; the distance is kept for as long as the tool is (the game session). The marker never
 *   goes past the picking range (the ray tested nothing beyond it) nor out of the world's build height.</li>
 *   <li><b>Picking.</b> A press picks the nearest point whose cell the ray passes through before the block the cursor is
 *   on; over open sky a ray passing within {@value #PICK_MARGIN} of a cell picks it too, and while it would, that point
 *   is highlighted instead of the marker (aim beside it to place one farther out). A point whose cell holds the camera
 *   is never picked by its ray. The tools still pick a point on the block's column as before.</li>
 *   <li><b>Dragging.</b> A point dragged freely (the point editor's centre cube, or a point grabbed by a click) stays as
 *   far from the camera as it was when grabbed, whatever lies under the cursor (it never snaps onto terrain), and
 *   Alt+Scroll changes that distance ({@link #beginDrag}, {@link #dragDistance}).</li>
 * </ul>
 * Client thread only.
 */
public final class AirPoints {
    public static final int MIN_DISTANCE = 2;
    public static final int MAX_DISTANCE = 256;
    public static final int DEFAULT_DISTANCE = 16;
    /** Alt+Scroll moves the marker this many blocks a notch; Shift+Alt+Scroll {@link #BIG_STEP}. */
    public static final int STEP = 1;
    public static final int BIG_STEP = 4;
    /** The marker's outline: a pale blue, apart from the points' yellow. */
    public static final int MARKER_COLOUR = 0xFF8FE3FF;
    /** Over open sky, a ray passing this close to a point's cell picks it (blocks). */
    static final double PICK_MARGIN = 0.25;
    /** The keys the hint names. */
    public static final String KEYS = "Alt+Scroll";
    public static final String HINT = "sculptory.hint.air.distance";
    public static final String LABEL = "sculptory.hud.air.distance";

    private int distance = DEFAULT_DISTANCE;
    /** While a point is dragged: how far from the camera it stays over open sky; NaN otherwise. */
    private double dragDistance = Double.NaN;
    /** Where a click on the sky puts a point now (the marker), or null. */
    private BlockPos marker;
    /** The point a click on the sky picks now (highlighted instead of the marker), or null. */
    private BlockPos hovered;
    /** How far the last missed ray was tested (the picking range): the marker goes no farther. */
    private double range = Double.POSITIVE_INFINITY;
    /** The world's build height: points stay within it. */
    private int bottomY = Integer.MIN_VALUE, topYExclusive = Integer.MAX_VALUE;

    /** How far ahead the marker floats (blocks), as set. */
    public int distance() {
        return distance;
    }

    /** Sets the marker's distance, clamped to {@value #MIN_DISTANCE}-{@value #MAX_DISTANCE}. */
    public void setDistance(int blocks) {
        distance = clamp(blocks);
    }

    /** Whether a point is being dragged. */
    public boolean dragging() {
        return !Double.isNaN(dragDistance);
    }

    /** The dragged point's distance from the camera over open sky (NaN when no point is dragged). */
    public double dragDistance() {
        return dragDistance;
    }

    /** The distance shown: the drag's while a point is dragged, else the marker's (within the picking range). */
    public int shownDistance() {
        return (int) Math.round(Math.min(dragging() ? dragDistance : distance, range));
    }

    /** Keeps points within {@code world}'s build height from now on (nothing when there is no world). */
    public void useWorld(WorldReader world) {
        if (world == null) return;
        bottomY = world.bottomY();
        topYExclusive = world.topYExclusive();
    }

    /** {@link #useWorld} with the tool's world, when it has one. */
    public void useWorld(ToolContext c) {
        useWorld(GenerateTool.worldOrNull(c));
    }

    /** The cell {@code t} blocks along {@code ray}. */
    public static BlockPos cellAt(Ray ray, double t) {
        return new BlockPos((int) Math.floor(ray.pointX(t)), (int) Math.floor(ray.pointY(t)), (int) Math.floor(ray.pointZ(t)));
    }

    /** The cell {@code t} blocks along {@code ray}, within the picking range and the build height. */
    private BlockPos airCell(WorldCursor cursor, Ray ray, double t) {
        BlockPos cell = cellAt(ray, Math.min(t, reach(cursor, ray)));
        int y = Math.max(bottomY, Math.min(topYExclusive - 1, cell.y()));
        return y == cell.y() ? cell : new BlockPos(cell.x(), y, cell.z());
    }

    /** How far a missed ray was tested: from its origin to the end the cursor gives. */
    private static double reach(WorldCursor cursor, Ray ray) {
        double dx = cursor.hitX() - ray.originX(), dy = cursor.hitY() - ray.originY(), dz = cursor.hitZ() - ray.originZ();
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return length > 0 ? length : Double.POSITIVE_INFINITY;
    }

    /**
     * Where a press puts a new point: {@code pointOf} the cursor on a block, else the marker's cell along {@code ray};
     * null when the ray missed and is not known.
     */
    public BlockPos place(WorldCursor cursor, Ray ray, Function<WorldCursor, BlockPos> pointOf) {
        if (!cursor.missed()) return pointOf.apply(cursor);
        return ray == null ? null : airCell(cursor, ray, distance);
    }

    /**
     * The point a press on {@code ray} picks: of {@code points}, the nearest whose cell the ray enters no farther than
     * the block the cursor is on (over open sky: whose cell grown by {@link #PICK_MARGIN} it enters); a cell holding the
     * ray's origin is never picked. -1 for none.
     */
    public static int pick(List<BlockPos> points, Ray ray, WorldCursor cursor) {
        double limit = Double.POSITIVE_INFINITY;
        double margin = PICK_MARGIN;
        if (!cursor.missed()) {
            double dx = cursor.hitX() - ray.originX(), dy = cursor.hitY() - ray.originY(), dz = cursor.hitZ() - ray.originZ();
            limit = Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-6;
            margin = 0;
        }
        int best = -1;
        double bestT = limit;
        for (int i = points.size() - 1; i >= 0; i--) {
            double t = enter(ray, points.get(i), margin);
            if (t == Double.POSITIVE_INFINITY || t > limit) continue;
            if (best < 0 || t < bestT) {
                best = i;
                bestT = t;
            }
        }
        return best;
    }

    /**
     * How far along the ray it enters the cell grown by {@code margin}, or +infinity when it misses it or starts inside
     * it (the camera in a point's cell does not pick that point).
     */
    static double enter(Ray ray, BlockPos cell, double margin) {
        double near = Double.NEGATIVE_INFINITY, far = Double.POSITIVE_INFINITY;
        double[] origin = {ray.originX(), ray.originY(), ray.originZ()};
        double[] dir = {ray.dirX(), ray.dirY(), ray.dirZ()};
        int[] min = {cell.x(), cell.y(), cell.z()};
        for (int axis = 0; axis < 3; axis++) {
            double lo = min[axis] - margin, hi = min[axis] + 1 + margin;
            if (Math.abs(dir[axis]) < 1e-12) {
                if (origin[axis] < lo || origin[axis] > hi) return Double.POSITIVE_INFINITY;
                continue;
            }
            double a = (lo - origin[axis]) / dir[axis], b = (hi - origin[axis]) / dir[axis];
            near = Math.max(near, Math.min(a, b));
            far = Math.min(far, Math.max(a, b));
            if (near > far) return Double.POSITIVE_INFINITY;
        }
        // Behind the camera, or around it: not picked.
        return near <= 0 ? Double.POSITIVE_INFINITY : near;
    }

    /** A point at {@code point} is grabbed: over open sky it will stay as far from the camera as its cell's centre is. */
    public void beginDrag(Ray ray, BlockPos point) {
        if (ray == null) {
            dragDistance = distance;
            return;
        }
        double dx = point.x() + 0.5 - ray.originX(), dy = point.y() + 0.5 - ray.originY(), dz = point.z() + 0.5 - ray.originZ();
        dragDistance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, Math.sqrt(dx * dx + dy * dy + dz * dz)));
    }

    /** {@code cell} kept within the build height (its y clamped). */
    public BlockPos withinHeight(BlockPos cell) {
        int y = Math.max(bottomY, Math.min(topYExclusive - 1, cell.y()));
        return y == cell.y() ? cell : new BlockPos(cell.x(), y, cell.z());
    }

    public void endDrag() {
        dragDistance = Double.NaN;
    }

    /**
     * Alt+Scroll (without Ctrl): the marker, or the dragged point, a block nearer or farther a notch (Shift: 4). Returns
     * whether the scroll was taken.
     */
    public boolean scroll(ScrollEvent e) {
        if (e.amount() == 0 || !Modifiers.alt(e.modifiers()) || Modifiers.control(e.modifiers())) return false;
        int step = (e.amount() > 0 ? 1 : -1) * (Modifiers.shift(e.modifiers()) ? BIG_STEP : STEP);
        if (dragging()) {
            dragDistance = Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, Math.round(dragDistance) + (double) step));
        } else {
            distance = clamp(distance + step);
        }
        return true;
    }

    /**
     * Once per frame: over open sky with no point dragged, the point a click would pick ({@code points}) is highlighted,
     * else the marker shows.
     */
    public void frame(WorldCursor cursor, Ray ray, List<BlockPos> points) {
        marker = null;
        hovered = null;
        if (!cursor.missed() || ray == null) return;
        range = reach(cursor, ray);
        if (dragging()) return;
        int index = pick(points, ray, cursor);
        if (index >= 0) {
            hovered = points.get(index);
        } else {
            marker = airCell(cursor, ray, distance);
        }
    }

    /** Hides the marker and the highlight until the next frame says otherwise. */
    public void hide() {
        marker = null;
        hovered = null;
    }

    /** Where a click on the sky puts a point now, or null. */
    public BlockPos marker() {
        return marker;
    }

    /** The point a click on the sky picks now, or null. */
    public BlockPos hovered() {
        return hovered;
    }

    /** The marker (its cell outlined, a cross through it, seen through terrain), or the point a click would pick. */
    public void render(WorldDraw d) {
        BlockPos picked = hovered;
        if (picked != null) {
            Box box = Box.of(picked);
            d.seeThrough(true);
            d.boxOutline(box, OverlayColors.scaleAlpha(MARKER_COLOUR, 0.4));
            d.seeThrough(false);
            d.boxOutline(box, MARKER_COLOUR);
            d.boxFill(box, OverlayColors.scaleAlpha(MARKER_COLOUR, 0.35));
            return;
        }
        BlockPos at = marker;
        if (at == null) return;
        Box box = Box.of(at);
        double cx = at.x() + 0.5, cy = at.y() + 0.5, cz = at.z() + 0.5;
        d.seeThrough(true);
        d.boxOutline(box, OverlayColors.scaleAlpha(MARKER_COLOUR, 0.4));
        d.seeThrough(false);
        d.boxOutline(box, MARKER_COLOUR);
        d.boxFill(box, OverlayColors.scaleAlpha(MARKER_COLOUR, 0.15));
        d.line(cx - 0.75, cy, cz, cx + 0.75, cy, cz, MARKER_COLOUR);
        d.line(cx, cy - 0.75, cz, cx, cy + 0.75, cz, MARKER_COLOUR);
        d.line(cx, cy, cz - 0.75, cx, cy, cz + 0.75, MARKER_COLOUR);
    }

    /** Whether the distance label shows: the marker is up, or a point is dragged freely (at its distance). */
    public boolean labelShown(WorldCursor cursor) {
        return marker != null || dragging();
    }

    /**
     * The distance beside the cursor (where the coordinate readout stands over a block), on a dark plate kept on the
     * screen; {@code text} is the label, translated.
     */
    public static void renderLabel(HudDraw d, double mouseX, double mouseY, String text) {
        int width = d.textWidth(text) + 6, height = 12;
        int x = (int) Math.round(mouseX) + 12, y = (int) Math.round(mouseY) + 10;
        if (x + width > d.width()) x = (int) Math.round(mouseX) - 8 - width;
        if (y + height > d.height()) y = (int) Math.round(mouseY) - 8 - height;
        x = Math.max(0, x);
        y = Math.max(0, y);
        d.fill(x, y, x + width, y + height, 0xD0101216);
        d.text(text, x + 3, y + 2, MARKER_COLOUR);
    }

    /** The hint: Alt+Scroll and the distance now. */
    public KeyHint hint() {
        return new KeyHint(KEYS, HINT, List.of(Integer.toString(shownDistance())));
    }

    private static int clamp(int blocks) {
        return Math.max(MIN_DISTANCE, Math.min(MAX_DISTANCE, blocks));
    }
}
