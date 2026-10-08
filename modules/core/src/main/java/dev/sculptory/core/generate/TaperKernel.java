package dev.sculptory.core.generate;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.state.StateSpace;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.util.Objects;

/**
 * Extrude to a point: a flat face tapered toward a tip, in layers parallel to it (a square face makes a pyramid, a round
 * one a cone, an off-centre tip a leaning spire). Commits like Generate's Line, as a generated clipboard pasted with the
 * {@code TAPER} label. Deterministic: {@code + - * /} and {@code StrictMath} only.
 *
 * <p>The tip must lie in front of the face: {@code L} layers past it along its normal, the last layer the tip's. Layer
 * {@code k} (1..L) is the face scaled by {@code s(k / L)} about a centre that moves in a straight line from the face's
 * middle (the middle of its bounds) to the tip's centre; {@link Curve} gives {@code s}. Each cell of a layer takes the
 * face cell its centre maps back to (the nearest sample of the scaled face): {@code (centre - layer centre) / s + face
 * middle}. A layer too small to hold a cell centre keeps one cell, the one holding its centre, of the face cell nearest
 * the face's middle, so the taper always reaches its tip. Face cells holding a block entity are left out. A point on a
 * cell edge goes to the higher cell. The cells looked at are capped as well as those laid (a sparse face looks at many).
 */
public final class TaperKernel {
    /** A point this close below a cell edge counts as on it, and goes to the higher cell. */
    private static final double ON_EDGE = 1e-7;
    /** Cells looked at per cell of the cap at most, before a taper is refused as too large. */
    private static final long WORK_PER_CELL = 64;

    private TaperKernel() {}

    /** How the face shrinks toward the tip, by the fraction {@code t} of the way there. */
    public enum Curve {
        /** {@code 1 - t}: straight sides (a pyramid, a cone). */
        STRAIGHT,
        /** {@code sqrt(1 - t²)}: sides bulging out (a dome). */
        BULGE,
        /** {@code (1 - t)²}: sides curving in (a spire). */
        PINCH;

        double scale(double t) {
            return switch (this) {
                case STRAIGHT -> 1 - t;
                case BULGE -> StrictMath.sqrt(StrictMath.max(0, 1 - t * t));
                case PINCH -> (1 - t) * (1 - t);
            };
        }
    }

    public record Spec(FaceCells face, BlockPos tip, Curve curve) {
        public Spec {
            Objects.requireNonNull(face);
            Objects.requireNonNull(tip);
            Objects.requireNonNull(curve);
            if (face.isEmpty()) throw new IllegalArgumentException("An empty face");
        }

        /** The layers from the face to the tip: how far the tip lies in front of the face (0 or less: behind it). */
        public int layers() {
            int tipAlong = FaceCells.coordinate(face.axis(), tip.x(), tip.y(), tip.z());
            return (int) StrictMath.max(Integer.MIN_VALUE, StrictMath.min(Integer.MAX_VALUE,
                    ((long) tipAlong - face.plane()) * face.sign()));
        }
    }

    /**
     * The taper's cells within {@code [bottomY, topYExclusive)}.
     *
     * @throws IllegalArgumentException when the tip is not in front of the face
     * @throws GeneratedTooLargeException over {@code maxCells}
     */
    public static GeneratedResult generate(Spec spec, StateSpace states, long maxCells, int bottomY, int topYExclusive) {
        Objects.requireNonNull(spec);
        Objects.requireNonNull(states);
        int layers = spec.layers();
        if (layers < 1) throw new IllegalArgumentException("The tip must lie in front of the face");
        FaceCells face = spec.face().without(states);
        if (face.isEmpty()) return new GeneratedResult(GeneratedSource.empty(), face.leftOut());
        // Every layer holds at least one cell.
        if (layers > maxCells) throw new GeneratedTooLargeException(maxCells);
        int axis = face.axis();
        int ua = axis == 0 ? 1 : 0, va = axis == 2 ? 1 : 2;
        // The face's bounds across, and its middle.
        int minU = Integer.MAX_VALUE, maxU = Integer.MIN_VALUE, minV = Integer.MAX_VALUE, maxV = Integer.MIN_VALUE;
        for (int i = 0; i < face.size(); i++) {
            int u = across(face, i, ua), v = across(face, i, va);
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        double midU = (minU + (double) maxU + 1) / 2, midV = (minV + (double) maxV + 1) / 2;
        int[] tip = {spec.tip().x(), spec.tip().y(), spec.tip().z()};
        double tipU = tip[ua] + 0.5, tipV = tip[va] + 0.5;
        Long2IntOpenHashMap cells = face.index();
        int middle = nearestToMiddle(face, ua, va, midU, midV);
        int plane = face.plane();
        GeneratedSource.Builder out = GeneratedSource.builder(maxCells);
        int[] at = new int[3];
        int[] probe = new int[3];
        // A sparse face (a ring, a scatter of blocks) scans far more cells than it lays: the scan is capped too.
        long workCap = maxCells > Long.MAX_VALUE / WORK_PER_CELL ? Long.MAX_VALUE : maxCells * WORK_PER_CELL + (1 << 20);
        long work = 0;
        for (int k = 1; k <= layers; k++) {
            double t = (double) k / layers;
            double s = spec.curve().scale(t);
            double cu = midU + t * (tipU - midU), cv = midV + t * (tipV - midV);
            at[axis] = plane + face.sign() * k;
            if (axis == 1 && (at[1] < bottomY || at[1] >= topYExclusive)) continue;
            boolean any = false;
            if (s > 0) {
                int u0 = floor(cu + s * (minU - midU)) - 1, u1 = floor(cu + s * (maxU + 1 - midU)) + 1;
                int v0 = floor(cv + s * (minV - midV)) - 1, v1 = floor(cv + s * (maxV + 1 - midV)) + 1;
                probe[axis] = plane;
                work += ((long) u1 - u0 + 1) * ((long) v1 - v0 + 1);
                if (work > workCap) throw new GeneratedTooLargeException(maxCells);
                for (int v = v0; v <= v1; v++) {
                    for (int u = u0; u <= u1; u++) {
                        double fu = (u + 0.5 - cu) / s + midU, fv = (v + 0.5 - cv) / s + midV;
                        probe[ua] = floor(fu + ON_EDGE);
                        probe[va] = floor(fv + ON_EDGE);
                        int index = cells.get(FaceCells.key(probe[0], probe[1], probe[2]));
                        if (index < 0) continue;
                        at[ua] = u;
                        at[va] = v;
                        if (at[1] < bottomY || at[1] >= topYExclusive) continue;
                        out.set(at[0], at[1], at[2], face.state(index));
                        any = true;
                    }
                }
            }
            if (!any) {
                at[ua] = floor(cu);
                at[va] = floor(cv);
                if (at[1] >= bottomY && at[1] < topYExclusive) out.set(at[0], at[1], at[2], face.state(middle));
            }
        }
        return new GeneratedResult(out.build(), face.leftOut());
    }

    /** The face cell whose centre lies nearest the middle (the first found on a tie). */
    private static int nearestToMiddle(FaceCells face, int ua, int va, double midU, double midV) {
        int best = 0;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < face.size(); i++) {
            double du = across(face, i, ua) + 0.5 - midU, dv = across(face, i, va) + 0.5 - midV;
            double d = du * du + dv * dv;
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }

    private static int across(FaceCells face, int i, int axis) {
        return axis == 0 ? face.x(i) : axis == 1 ? face.y(i) : face.z(i);
    }

    private static int floor(double v) {
        return (int) StrictMath.floor(v);
    }
}
