package dev.sculptory.fabric.client.editor.brush;

import dev.sculptory.core.brush.Dab;
import dev.sculptory.fabric.client.session.StrokeHandle;
import java.util.Arrays;
import java.util.Objects;

/**
 * Turns one brush press into dabs. Pure logic: positions are block-space points
 * from the cursor hit, time is a monotonic nanosecond clock, and the server stroke is a {@link StrokeHandle}.
 *
 * <p>Per {@link #update}:
 * <ul>
 *   <li><b>Spacing.</b> Dabs are laid along the straight path from the last dab to the latest cursor hit,
 *       every {@code max(1, 0.25 × radius)} blocks (or what {@link #setSpacing} sets). A move shorter than that waits
 *       for more movement.</li>
 *   <li><b>Snap.</b> With a {@link Snap} (the Shape brush), each dab lands on the point it gives for its point of the
 *       path; a dab that would land where the stroke's previous dab did is left out (the path goes on).</li>
 *   <li><b>Jumps.</b> When the hit moves more than {@code 3 × radius + 8} blocks at once (the cursor slid
 *       onto a far hill), the path is not interpolated: one dab lands at the new hit.</li>
 *   <li><b>Flow.</b> With flow on (Raise, Lower, Smooth, Flatten, Weather), a cursor held still repeats a dab every
 *       {@value #FLOW_INTERVAL_MILLIS} ms.</li>
 *   <li><b>Limits.</b> Dabs are paced by the server's {@code maxDabRate} (a token bucket with a burst of a
 *       fifth of a second) and by the handle's backpressure ({@value #MAX_IN_FLIGHT} dabs queued or awaiting
 *       acknowledgement). When fewer dabs may go out than the path needs, the last one allowed lands on the
 *       latest hit: the path coalesces to the latest position rather than lagging behind the cursor. With brush
 *       symmetry each dab costs its copies ({@link #setDabCost}) in both, as the server counts them.</li>
 *   <li><b>Restarts.</b> A {@code BrushSpec} is fixed per server stroke, so a settings change mid-press
 *       (radius, strength, Alt invert) ends the server stroke and begins another at most every
 *       {@value #RESTART_INTERVAL_MILLIS} ms; the path continues without a break. Each server stroke numbers
 *       its dabs from 0.</li>
 * </ul>
 * The server stroke begins lazily, at the first dab, so a press in the sky begins nothing until the cursor
 * meets the terrain. A stroke the server rejects (or that could not begin) emits nothing more until the next
 * press. Render thread only.
 */
public final class StrokeController {
    public static final long FLOW_INTERVAL_MILLIS = 150;
    public static final long FLOW_INTERVAL_NANOS = FLOW_INTERVAL_MILLIS * 1_000_000L;
    public static final long RESTART_INTERVAL_MILLIS = 250;
    public static final long RESTART_INTERVAL_NANOS = RESTART_INTERVAL_MILLIS * 1_000_000L;
    /** Dabs a stroke may have queued or unacknowledged (the handle's window). */
    public static final int MAX_IN_FLIGHT = 16;
    /** The rate bucket holds this fraction of a second's worth of dabs. */
    private static final double BURST_SECONDS = 0.2;
    private static final double TOKEN_EPSILON = 1e-6;

    /** Why a press ended. Everything but {@link #CANCELLED} ends the server stroke normally. */
    public enum EndReason {
        RELEASED,
        /** Esc: stop now. The dabs already applied stay and form one history entry, as with an end. */
        CANCELLED,
        FOCUS_LOST,
        TOOL_SWITCHED,
        EDITOR_EXITED
    }

    /** Where a dab lands for a point of the path: a point fitted to a grid (the Shape brush's shape centres). */
    @FunctionalInterface
    public interface Snap {
        /** The dab's point {x, y, z} for path point (x, y, z). */
        double[] apply(double x, double y, double z);
    }

    /** The tool side of a stroke. */
    public interface Host {
        /**
         * Begins a server stroke with the brush as it is set now, or returns {@code null} when it cannot
         * (the host tells the player why).
         */
        StrokeHandle begin();

        /**
         * Dabs of {@code stroke} that are queued or awaiting the server's acknowledgement, each counted with its
         * symmetric copies.
         */
        int inFlight(StrokeHandle stroke);

        /**
         * What is still in flight before this press's stroke begins (the Shape brush: earlier presses' dabs the server
         * has not written yet), in the same units; the press's first dab waits for room. None by default.
         */
        default int inFlightBeforeStroke() {
            return 0;
        }

        /**
         * {@code dab} is about to go to the current server stroke (its spec is the last {@link #begin}'s): nothing it
         * writes has been sent or predicted yet. The Shape brush keeps the world there for its cursor ray. Nothing by
         * default.
         */
        default void beforeDab(Dab dab) {}
    }

    private final Host host;
    private final boolean flow;
    private Snap snap;

    private int radius = 1;
    private double spacing = 1;
    /** What one dab costs in the rate bucket and the in-flight window: its copies under the brush's symmetry. */
    private int dabCost = 1;

    private double tokensPerNano;
    private double rate = 1;
    private double burst;
    private double tokens;
    private long tokensAt;
    private boolean clockStarted;

    private boolean pressed;
    private boolean halted;
    private StrokeHandle stroke;
    private int nextIndex;
    private long strokeBeganAt;
    private boolean restartPending;
    private int strokesBegun;
    private long dabsEmitted;

    private boolean hasTarget;
    private double targetX;
    private double targetY;
    private double targetZ;

    private boolean hasLast;
    private double lastX;
    private double lastY;
    private double lastZ;
    private long lastDabAt;

    /** The point of the server stroke's last dab, with a {@link Snap}. */
    private boolean hasLastSnapped;
    private double[] lastSnapped;

    /** @param flow repeat dabs while the cursor is held still (Raise, Lower, Smooth, Flatten, Weather) */
    public StrokeController(Host host, boolean flow) {
        this.host = Objects.requireNonNull(host);
        this.flow = flow;
    }

    // ---- Input ----

    /** The cursor hit, in block coordinates. */
    public void aim(double x, double y, double z) {
        hasTarget = true;
        targetX = x;
        targetY = y;
        targetZ = z;
    }

    /** The cursor ray missed the terrain: no dabs until it hits again. */
    public void clearAim() {
        hasTarget = false;
    }

    /**
     * A press begins a stroke; a press during one ends it first. The first dab goes out at the next
     * {@link #update}, with the rate bucket full.
     *
     * @param maxDabRate the server's dab rate limit, dabs per second
     */
    public void press(int radius, int maxDabRate) {
        press(radius, maxDabRate, 1);
    }

    /**
     * A press of a brush whose dabs each cost {@code dabCost} (the copies its symmetry makes) in the dab rate and
     * the in-flight window.
     *
     * @param maxDabRate the server's dab rate limit, dabs (copies included) per second
     */
    public void press(int radius, int maxDabRate, int dabCost) {
        if (pressed) {
            end(EndReason.RELEASED);
        }
        pressed = true;
        halted = false;
        stroke = null;
        nextIndex = 0;
        restartPending = false;
        hasLast = false;
        hasLastSnapped = false;
        setRadius(radius);
        rate = Math.max(1, maxDabRate);
        tokensPerNano = rate / 1e9;
        setDabCost(dabCost);
        tokens = burst;
        clockStarted = false;
    }

    /**
     * What one dab costs from now on (its copies, 1 to 4). The rate bucket holds at least one such dab, so a slow rate
     * never stops the stroke.
     */
    public void setDabCost(int dabCost) {
        this.dabCost = Math.max(1, dabCost);
        burst = Math.max(this.dabCost, Math.ceil(rate * BURST_SECONDS));
        tokens = Math.min(tokens, burst);
    }

    /** What one dab costs in the rate bucket and the in-flight window. */
    public int dabCost() {
        return dabCost;
    }

    /** The brush radius, which sets the spacing and the jump distance. Takes effect immediately. */
    public void setRadius(int radius) {
        this.radius = Math.max(1, radius);
        this.spacing = Math.max(1.0, 0.25 * this.radius);
    }

    /** The distance between interpolated dabs, in blocks (at least 1), until the next {@link #setRadius}. */
    public void setSpacing(double blocks) {
        this.spacing = Math.max(1.0, blocks);
    }

    /** Fits every dab's point with {@code snap} from now on ({@code null}: the path's points as they are). */
    public void setSnap(Snap snap) {
        this.snap = snap;
        hasLastSnapped = false;
    }

    /**
     * The brush settings changed mid-press: the server stroke is ended and a new one begun (with the new
     * settings) once the current one is at least {@value #RESTART_INTERVAL_MILLIS} ms old.
     */
    public void requestRestart() {
        if (pressed && !halted && stroke != null) {
            restartPending = true;
        }
    }

    /** Emits the dabs due now. Call every frame. */
    public void update(long now) {
        if (!pressed || halted) {
            return;
        }
        if (stroke != null && !stroke.active()) {
            // Rejected by the server, or the session went away: nothing more until the next press.
            halted = true;
            return;
        }
        refill(now);
        if (restartPending && now - strokeBeganAt >= RESTART_INTERVAL_NANOS) {
            restart(now);
            if (halted) {
                return;
            }
        }
        if (!hasTarget) {
            return;
        }
        if (!hasLast) {
            emit(targetX, targetY, targetZ, now);
            return;
        }
        double dx = targetX - lastX;
        double dy = targetY - lastY;
        double dz = targetZ - lastZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > jumpDistance()) {
            emit(targetX, targetY, targetZ, now);
            return;
        }
        int samples = (int) Math.floor(distance / spacing);
        if (samples > 0) {
            int capacity = capacity();
            if (capacity <= 0) {
                return;
            }
            double startX = lastX;
            double startY = lastY;
            double startZ = lastZ;
            double ux = dx / distance;
            double uy = dy / distance;
            double uz = dz / distance;
            int alongPath = samples <= capacity ? samples : capacity - 1;
            for (int i = 1; i <= alongPath; i++) {
                double along = spacing * i;
                if (!emit(startX + ux * along, startY + uy * along, startZ + uz * along, now)) {
                    return;
                }
            }
            if (alongPath < samples) {
                emit(targetX, targetY, targetZ, now);
            }
            return;
        }
        if (flow && now - lastDabAt >= FLOW_INTERVAL_NANOS) {
            emit(targetX, targetY, targetZ, now);
        }
    }

    /**
     * Ends the press. The server turns each of its strokes into one history entry (a press split by a
     * mid-stroke settings change leaves one entry per server stroke).
     */
    public void end(EndReason reason) {
        Objects.requireNonNull(reason);
        if (!pressed) {
            return;
        }
        pressed = false;
        halted = false;
        restartPending = false;
        hasLast = false;
        StrokeHandle current = stroke;
        stroke = null;
        if (current != null) {
            if (reason == EndReason.CANCELLED) {
                current.cancel();
            } else {
                current.end();
            }
        }
    }

    // ---- State ----

    /** True between a press and its end (also while halted after a rejection). */
    public boolean pressed() {
        return pressed;
    }

    /** True while a press may still emit dabs. */
    public boolean emitting() {
        return pressed && !halted;
    }

    /** The server stroke dabs currently go to, or {@code null} before the first dab. */
    public StrokeHandle stroke() {
        return stroke;
    }

    /** Distance between interpolated dabs, in blocks. */
    public double spacing() {
        return spacing;
    }

    /** A move longer than this (in blocks) is not interpolated. */
    public double jumpDistance() {
        return 3.0 * radius + 8.0;
    }

    /** Server strokes begun since this controller was made (restarts included). */
    public int strokesBegun() {
        return strokesBegun;
    }

    /** Dabs emitted since this controller was made. */
    public long dabsEmitted() {
        return dabsEmitted;
    }

    // ---- Internals ----

    private void refill(long now) {
        if (clockStarted) {
            long elapsed = now - tokensAt;
            if (elapsed > 0) {
                tokens = Math.min(burst, tokens + elapsed * tokensPerNano);
            }
        }
        clockStarted = true;
        tokensAt = now;
    }

    /** How many dabs may go out now: the rate bucket and the handle's window, each dab costing {@link #dabCost}. */
    private int capacity() {
        int window = MAX_IN_FLIGHT - (stroke == null ? host.inFlightBeforeStroke() : host.inFlight(stroke));
        // The epsilon absorbs floating-point drift in the refill (0.2 + 0.8 tokens must make a whole one).
        return (int) Math.min(Math.floor(tokens + TOKEN_EPSILON), window) / dabCost;
    }

    private void restart(long now) {
        restartPending = false;
        StrokeHandle previous = stroke;
        stroke = null;
        if (previous != null) {
            previous.end();
        }
        begin(now);
    }

    private boolean begin(long now) {
        StrokeHandle next = host.begin();
        if (next == null || !next.active()) {
            halted = true;
            return false;
        }
        stroke = next;
        nextIndex = 0;
        strokeBeganAt = now;
        hasLastSnapped = false;
        strokesBegun++;
        return true;
    }

    private boolean emit(double x, double y, double z, long now) {
        if (capacity() <= 0) {
            return false;
        }
        if (stroke == null && !begin(now)) {
            return false;
        }
        double[] point = snap == null ? new double[] {x, y, z} : snap.apply(x, y, z);
        if (snap != null && hasLastSnapped && Arrays.equals(point, lastSnapped)) {
            // The same shape again: the path goes on without a dab.
            hasLast = true;
            lastX = x;
            lastY = y;
            lastZ = z;
            return true;
        }
        Dab dab;
        try {
            dab = Dab.of(nextIndex, point[0], point[1], point[2], Dab.FULL_PRESSURE);
        } catch (IllegalArgumentException outOfRange) {
            return false;
        }
        if (snap != null) {
            hasLastSnapped = true;
            lastSnapped = point;
        }
        nextIndex++;
        tokens -= dabCost;
        host.beforeDab(dab);
        stroke.dab(dab);
        dabsEmitted++;
        hasLast = true;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastDabAt = now;
        return true;
    }
}
