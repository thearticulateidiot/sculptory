package dev.sculptory.core.generate;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A triangulation of points on an integer grid in the plane (Smart Surface's points seen from above), with exact
 * predicates in {@code long}: coordinates are small (within {@link SmartSurfaceKernel#MAX_SIDE} of each other), so
 * the orientation and in-circle determinants cannot overflow.
 *
 * <p>Two starts: a simple polygon cut into ears ({@link #earClip}, its edges then held fixed as constraints) or a
 * convex hull cut into a fan ({@link #fan}, nothing held). Points added later ({@link #insert}) split the triangle or
 * the edge they land on; {@link #legalize} then flips every edge that is not locally Delaunay and not held, which
 * ends at the constrained Delaunay triangulation (the Delaunay triangulation when nothing is held). Triangles are
 * kept counter-clockwise ({@link #orient} positive). Not thread-safe; deterministic for the same calls.
 */
final class PlaneTriangulation {
    /** More flips than this is a bug (Lawson's flips end); refused rather than looping. */
    private static final int MAX_FLIPS = 4_000_000;

    private final long[] xs;
    private final long[] zs;
    /** Three vertex indices per triangle; a removed triangle has -1 in its first slot. */
    private int[] tris = new int[48];
    private int triangleSlots;
    /** Directed edge (u, v), as {@link #key}, to the triangle holding it in counter-clockwise order. */
    private final Map<Long, Integer> edges = new HashMap<>();
    /** Held edges, as {@link #undirected}. */
    private final Set<Long> held = new HashSet<>();

    PlaneTriangulation(long[] xs, long[] zs) {
        if (xs.length != zs.length) throw new IllegalArgumentException("Coordinate arrays differ in length");
        this.xs = xs.clone();
        this.zs = zs.clone();
    }

    /** Twice the signed area of (a, b, c): positive when counter-clockwise (x right, z up). */
    static long orient(long ax, long az, long bx, long bz, long cx, long cz) {
        return (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
    }

    long orient(int a, int b, int c) {
        return orient(xs[a], zs[a], xs[b], zs[b], xs[c], zs[c]);
    }

    /** Positive when d lies strictly inside the circle through the counter-clockwise triangle (a, b, c). */
    long inCircle(int a, int b, int c, int d) {
        long adx = xs[a] - xs[d], adz = zs[a] - zs[d];
        long bdx = xs[b] - xs[d], bdz = zs[b] - zs[d];
        long cdx = xs[c] - xs[d], cdz = zs[c] - zs[d];
        long ad = adx * adx + adz * adz, bd = bdx * bdx + bdz * bdz, cd = cdx * cdx + cdz * cdz;
        return adx * (bdz * cd - bd * cdz) - adz * (bdx * cd - bd * cdx) + ad * (bdx * cdz - bdz * cdx);
    }

    private static long key(int u, int v) {
        return ((long) u << 32) | (v & 0xFFFFFFFFL);
    }

    private static long undirected(int u, int v) {
        return u < v ? key(u, v) : key(v, u);
    }

    /** Holds edge (u, v): {@link #legalize} never flips it. */
    void hold(int u, int v) {
        held.add(undirected(u, v));
    }

    boolean isHeld(int u, int v) {
        return held.contains(undirected(u, v));
    }

    private int addTriangle(int a, int b, int c) {
        if (orient(a, b, c) <= 0) throw new IllegalStateException("Triangle not counter-clockwise");
        if (triangleSlots * 3 + 3 > tris.length) tris = Arrays.copyOf(tris, tris.length * 2);
        int t = triangleSlots++;
        tris[3 * t] = a;
        tris[3 * t + 1] = b;
        tris[3 * t + 2] = c;
        edges.put(key(a, b), t);
        edges.put(key(b, c), t);
        edges.put(key(c, a), t);
        return t;
    }

    private void removeTriangle(int t) {
        int a = tris[3 * t], b = tris[3 * t + 1], c = tris[3 * t + 2];
        edges.remove(key(a, b));
        edges.remove(key(b, c));
        edges.remove(key(c, a));
        tris[3 * t] = -1;
    }

    /** The vertex of triangle t that is neither u nor v. */
    private int third(int t, int u, int v) {
        for (int i = 0; i < 3; i++) {
            int w = tris[3 * t + i];
            if (w != u && w != v) return w;
        }
        throw new IllegalStateException("Degenerate triangle");
    }

    /**
     * Cuts the simple polygon {@code polygon} (vertex indices, counter-clockwise, no vertex repeated) into ears and
     * holds its edges. An ear is a strictly convex corner whose triangle (edges included) holds no other vertex, so
     * a diagonal never runs through a vertex lying on it; the first ear in polygon order is cut each time.
     *
     * @throws IllegalStateException when no ear is found (the polygon is not simple)
     */
    void earClip(int[] polygon) {
        int n = polygon.length;
        for (int i = 0; i < n; i++) hold(polygon[i], polygon[(i + 1) % n]);
        int[] ring = polygon.clone();
        int size = n;
        while (size > 3) {
            boolean cut = false;
            for (int i = 0; i < size && !cut; i++) {
                int prev = ring[(i + size - 1) % size], cur = ring[i], next = ring[(i + 1) % size];
                if (orient(prev, cur, next) <= 0) continue;
                boolean empty = true;
                for (int j = 0; j < size && empty; j++) {
                    int p = ring[j];
                    if (p == prev || p == cur || p == next) continue;
                    if (orient(prev, cur, p) >= 0 && orient(cur, next, p) >= 0 && orient(next, prev, p) >= 0) {
                        empty = false;
                    }
                }
                if (!empty) continue;
                addTriangle(prev, cur, next);
                System.arraycopy(ring, i + 1, ring, i, size - i - 1);
                size--;
                cut = true;
            }
            if (!cut) throw new IllegalStateException("No ear: the polygon is not simple");
        }
        addTriangle(ring[0], ring[1], ring[2]);
    }

    /** Cuts the strictly convex counter-clockwise polygon {@code hull} into a fan from its first vertex. */
    void fan(int[] hull) {
        for (int i = 1; i + 1 < hull.length; i++) addTriangle(hull[0], hull[i], hull[i + 1]);
    }

    /**
     * Adds vertex p: splits the first triangle (in slot order) holding it in three, or, when it lies on an edge, the
     * one or two triangles beside that edge in two each (a held edge stays held in both halves). Then flips what the
     * split made non-Delaunay ({@link #legalize}).
     *
     * @return false when p lies in no triangle, or on a vertex (nothing changed)
     */
    boolean insert(int p) {
        for (int t = 0; t < triangleSlots; t++) {
            int a = tris[3 * t];
            if (a < 0) continue;
            int b = tris[3 * t + 1], c = tris[3 * t + 2];
            long oa = orient(b, c, p), ob = orient(c, a, p), oc = orient(a, b, p);
            if (oa < 0 || ob < 0 || oc < 0) continue;
            int zeros = (oa == 0 ? 1 : 0) + (ob == 0 ? 1 : 0) + (oc == 0 ? 1 : 0);
            if (zeros >= 2) return false;
            ArrayDeque<Long> stack = new ArrayDeque<>();
            if (zeros == 0) {
                removeTriangle(t);
                addTriangle(a, b, p);
                addTriangle(b, c, p);
                addTriangle(c, a, p);
                stack.push(key(a, b));
                stack.push(key(b, c));
                stack.push(key(c, a));
            } else {
                int u, v;
                if (oc == 0) {
                    u = a;
                    v = b;
                } else if (oa == 0) {
                    u = b;
                    v = c;
                } else {
                    u = c;
                    v = a;
                }
                splitEdge(t, u, v, p, stack);
            }
            flip(stack);
            return true;
        }
        return false;
    }

    /** Splits edge (u, v) of triangle t (and of its twin, when there is one) at p. */
    private void splitEdge(int t, int u, int v, int p, ArrayDeque<Long> stack) {
        int w = third(t, u, v);
        Integer twin = edges.get(key(v, u));
        boolean wasHeld = held.remove(undirected(u, v));
        removeTriangle(t);
        addTriangle(u, p, w);
        addTriangle(p, v, w);
        stack.push(key(v, w));
        stack.push(key(w, u));
        if (twin != null) {
            int x = third(twin, v, u);
            removeTriangle(twin);
            addTriangle(v, p, x);
            addTriangle(p, u, x);
            stack.push(key(u, x));
            stack.push(key(x, v));
        }
        if (wasHeld) {
            hold(u, p);
            hold(p, v);
        }
    }

    /** Flips every edge that is neither held nor locally Delaunay until none is left. */
    void legalize() {
        ArrayDeque<Long> stack = new ArrayDeque<>();
        for (int t = 0; t < triangleSlots; t++) {
            int a = tris[3 * t];
            if (a < 0) continue;
            int b = tris[3 * t + 1], c = tris[3 * t + 2];
            stack.push(key(a, b));
            stack.push(key(b, c));
            stack.push(key(c, a));
        }
        flip(stack);
    }

    private void flip(ArrayDeque<Long> stack) {
        int flips = 0;
        while (!stack.isEmpty()) {
            long edge = stack.pop();
            int u = (int) (edge >>> 32), v = (int) edge;
            if (isHeld(u, v)) continue;
            Integer t1 = edges.get(key(u, v)), t2 = edges.get(key(v, u));
            if (t1 == null || t2 == null) continue;
            int w = third(t1, u, v), x = third(t2, v, u);
            if (inCircle(u, v, w, x) <= 0) continue;
            // Quad u, x, v, w (counter-clockwise): the new diagonal x-w needs it strictly convex.
            if (orient(u, x, w) <= 0 || orient(x, v, w) <= 0) continue;
            if (++flips > MAX_FLIPS) throw new IllegalStateException("Too many flips");
            removeTriangle(t1);
            removeTriangle(t2);
            addTriangle(u, x, w);
            addTriangle(x, v, w);
            stack.push(key(u, x));
            stack.push(key(x, v));
            stack.push(key(v, w));
            stack.push(key(w, u));
        }
    }

    /** The triangles, three counter-clockwise vertex indices each, in slot order. */
    int[] triangles() {
        int alive = 0;
        for (int t = 0; t < triangleSlots; t++) if (tris[3 * t] >= 0) alive++;
        int[] out = new int[alive * 3];
        int k = 0;
        for (int t = 0; t < triangleSlots; t++) {
            if (tris[3 * t] < 0) continue;
            out[k++] = tris[3 * t];
            out[k++] = tris[3 * t + 1];
            out[k++] = tris[3 * t + 2];
        }
        return out;
    }

    /**
     * The strictly convex hull of {@code points} (indices into the coordinates), counter-clockwise from the lowest x
     * (then z): points on its edges are left out. At least three points, not all on one line.
     */
    static int[] convexHull(long[] xs, long[] zs, int[] points) {
        Integer[] order = new Integer[points.length];
        for (int i = 0; i < points.length; i++) order[i] = points[i];
        Arrays.sort(order, (p, q) -> {
            int c = Long.compare(xs[p], xs[q]);
            if (c == 0) c = Long.compare(zs[p], zs[q]);
            return c == 0 ? Integer.compare(p, q) : c;
        });
        int[] hull = new int[2 * points.length + 1];
        int k = 0;
        for (int pass = 0; pass < 2; pass++) {
            int start = k;
            for (int i = 0; i < order.length; i++) {
                int p = order[pass == 0 ? i : order.length - 1 - i];
                while (k >= start + 2 && orient(xs[hull[k - 2]], zs[hull[k - 2]], xs[hull[k - 1]], zs[hull[k - 1]],
                        xs[p], zs[p]) <= 0) {
                    k--;
                }
                hull[k++] = p;
            }
            k--; // the last point of a pass starts the next
        }
        return Arrays.copyOf(hull, k);
    }
}
