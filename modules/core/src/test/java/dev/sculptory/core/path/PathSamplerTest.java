package dev.sculptory.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Line paths: every kind is continuous, hits its points and is the same every time. */
class PathSamplerTest {
    private static final double EPS = 1e-9;

    private static List<BlockPos> points(int... xyz) {
        List<BlockPos> points = new ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) points.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return points;
    }

    private static PathSpec spec(PathKind kind, double sag, int... xyz) {
        return new PathSpec(points(xyz), kind, sag);
    }

    private static List<PathSpec> everyKind(int... xyz) {
        return List.of(spec(PathKind.STRAIGHT, 0, xyz), spec(PathKind.CURVE, 0, xyz), spec(PathKind.HANGING, 5, xyz));
    }

    private static void assertContinuous(List<PathSample> samples, double spacing) {
        for (int i = 1; i < samples.size(); i++) {
            PathSample a = samples.get(i - 1), b = samples.get(i);
            double d = PathSampler.distance(b.x() - a.x(), b.y() - a.y(), b.z() - a.z());
            assertTrue(d <= spacing + EPS, "gap of " + d + " at sample " + i);
            assertEquals(a.along() + d, b.along(), 1e-6, "along at sample " + i);
        }
    }

    private static boolean passesThrough(List<PathSample> samples, BlockPos p) {
        return samples.stream().anyMatch(s -> Math.abs(s.x() - (p.x() + 0.5)) < EPS && Math.abs(s.y() - (p.y() + 0.5)) < EPS
                && Math.abs(s.z() - (p.z() + 0.5)) < EPS);
    }

    @Test
    void everyKindStartsAndEndsAtItsPointsCentresAndHitsEachPoint() {
        int[] xyz = {0, 64, 0, 10, 70, 4, 20, 64, -6, 25, 60, 10};
        for (PathSpec spec : everyKind(xyz)) {
            List<PathSample> samples = PathSampler.sample(spec, 0.5);
            PathSample first = samples.get(0), last = samples.get(samples.size() - 1);
            assertEquals(0.5, first.x(), 0, spec.kind() + " start");
            assertEquals(64.5, first.y(), 0);
            assertEquals(0.5, first.z(), 0);
            assertEquals(0, first.along(), 0);
            assertEquals(25.5, last.x(), 0, spec.kind() + " end");
            assertEquals(60.5, last.y(), 0);
            assertEquals(10.5, last.z(), 0);
            for (BlockPos p : spec.points()) assertTrue(passesThrough(samples, p), spec.kind() + " misses " + p);
        }
    }

    @Test
    void everyKindIsContinuousAtTheAskedSpacing() {
        int[] xyz = {0, 64, 0, 3, 90, 1, -20, 64, 30, 40, 64, 30, 41, 64, 31};
        for (double spacing : new double[] {0.25, 0.5, 1, 3}) {
            for (PathSpec spec : everyKind(xyz)) assertContinuous(PathSampler.sample(spec, spacing), spacing);
        }
    }

    @Test
    void theSameSpecGivesTheSameSamples() {
        PathSpec spec = spec(PathKind.CURVE, 0, 0, 64, 0, 7, 71, 3, 13, 60, -9, 30, 64, 5);
        assertEquals(PathSampler.sample(spec, 0.5), PathSampler.sample(spec, 0.5));
        PathSpec hanging = spec(PathKind.HANGING, 7.25, 0, 64, 0, 30, 64, 0, 45, 80, 10);
        assertEquals(PathSampler.sample(hanging, 0.5), PathSampler.sample(hanging, 0.5));
    }

    @Test
    void aStraightLineRunsOnItsSegmentsAndIsAsLongAsItsChords() {
        PathSpec spec = spec(PathKind.STRAIGHT, 0, 0, 0, 0, 10, 0, 0, 10, 0, 10);
        List<PathSample> samples = PathSampler.sample(spec, 0.5);
        for (PathSample s : samples) {
            boolean first = Math.abs(s.z() - 0.5) < EPS && s.x() >= 0.5 - EPS && s.x() <= 10.5 + EPS;
            boolean second = Math.abs(s.x() - 10.5) < EPS && s.z() >= 0.5 - EPS && s.z() <= 10.5 + EPS;
            assertTrue(first || second, "off the segments: " + s);
            assertEquals(0.5, s.y(), 0);
        }
        assertEquals(20, PathSampler.length(spec), 1e-9);
        assertEquals(20, PathSampler.chordLength(spec), 1e-9);
        assertEquals(20, samples.get(samples.size() - 1).along(), 1e-9);
    }

    @Test
    void aCurveIsSmoothAndAtLeastAsLongAsItsChords() {
        PathSpec spec = spec(PathKind.CURVE, 0, 0, 64, 0, 10, 64, 0, 10, 64, 10);
        List<PathSample> samples = PathSampler.sample(spec, 0.25);
        assertTrue(PathSampler.length(spec) > PathSampler.chordLength(spec));
        // No sharp turn anywhere: consecutive steps turn less than 20 degrees at this spacing.
        for (int i = 2; i < samples.size(); i++) {
            double[] u = step(samples.get(i - 2), samples.get(i - 1)), v = step(samples.get(i - 1), samples.get(i));
            double cos = (u[0] * v[0] + u[1] * v[1] + u[2] * v[2]) / (norm(u) * norm(v));
            assertTrue(cos > Math.cos(Math.toRadians(20)), "a kink at sample " + i + ": " + cos);
        }
        // It bows outward past the corner (10, 10): the Catmull-Rom corner is rounded, not cut.
        assertTrue(samples.stream().anyMatch(s -> s.x() > 10.5 + 0.1));
    }

    @Test
    void aClosedCurveLoopsBackSmoothly() {
        PathSpec loop = spec(PathKind.CURVE, 0, 0, 64, 0, 10, 64, 0, 10, 64, 10, 0, 64, 10, 0, 64, 0);
        assertTrue(PathSampler.closed(loop));
        assertFalse(PathSampler.closed(spec(PathKind.STRAIGHT, 0, 0, 64, 0, 10, 64, 0, 10, 64, 10, 0, 64, 0)));
        assertFalse(PathSampler.closed(spec(PathKind.CURVE, 0, 0, 64, 0, 10, 64, 0, 0, 64, 0)));
        List<PathSample> samples = PathSampler.sample(loop, 0.25);
        double[] out = step(samples.get(0), samples.get(1));
        double[] in = step(samples.get(samples.size() - 2), samples.get(samples.size() - 1));
        double cos = (out[0] * in[0] + out[1] * in[1] + out[2] * in[2]) / (norm(out) * norm(in));
        assertTrue(cos > Math.cos(Math.toRadians(20)), "the loop closes with a kink: " + cos);
    }

    @Test
    void aHangingSpanSagsBySagAtItsMiddle() {
        PathSpec spec = spec(PathKind.HANGING, 6, 0, 80, 0, 20, 80, 0);
        List<PathSample> samples = PathSampler.sample(spec, 0.125);
        PathSample lowest = samples.stream().min((a, b) -> Double.compare(a.y(), b.y())).orElseThrow();
        assertEquals(80.5 - 6, lowest.y(), 0.01);
        assertEquals(10.5, lowest.x(), 0.2);
        // A catenary: a little flatter at the bottom and steeper at the ends than the parabola of the same sag.
        double quarter = samples.stream().filter(s -> Math.abs(s.x() - 5.5) < 0.07).findFirst().orElseThrow().y();
        double parabola = 80.5 - 6 * 4 * 0.25 * 0.75;
        assertTrue(quarter < parabola + 1e-6, "quarter " + quarter + " vs parabola " + parabola);
        assertTrue(quarter > 80.5 - 6, "quarter below the bottom");
        // Each span sags on its own; a span straight up does not.
        PathSpec two = spec(PathKind.HANGING, 3, 0, 80, 0, 10, 80, 0, 10, 90, 0, 20, 90, 0);
        List<PathSample> twoSamples = PathSampler.sample(two, 0.125);
        assertEquals(80.5 - 3, twoSamples.stream().filter(s -> s.x() < 10.4).mapToDouble(PathSample::y).min().orElseThrow(), 0.01);
        assertEquals(90.5 - 3, twoSamples.stream().filter(s -> s.x() > 10.6).mapToDouble(PathSample::y).min().orElseThrow(), 0.01);
        for (PathSample s : twoSamples) {
            if (Math.abs(s.x() - 10.5) < EPS) assertTrue(s.y() >= 80.5 - EPS && s.y() <= 90.5 + EPS, "the rise sagged: " + s);
        }
    }

    @Test
    void aHangingLineWithoutSagIsStraightAndOnlyHangingLinesSag() {
        List<PathSample> straight = PathSampler.sample(spec(PathKind.STRAIGHT, 0, 0, 64, 0, 9, 70, 3), 0.5);
        List<PathSample> slack = PathSampler.sample(spec(PathKind.HANGING, 0, 0, 64, 0, 9, 70, 3), 0.5);
        assertEquals(straight, slack);
        assertThrows(IllegalArgumentException.class, () -> spec(PathKind.CURVE, 2, 0, 64, 0, 9, 70, 3));
        assertThrows(IllegalArgumentException.class, () -> spec(PathKind.HANGING, 65, 0, 64, 0, 9, 70, 3));
        // The deepest sag over the shortest span stays finite.
        List<PathSample> deep = PathSampler.sample(spec(PathKind.HANGING, 64, 0, 200, 0, 1, 200, 0), 0.5);
        assertContinuous(deep, 0.5);
        assertEquals(200.5 - 64, deep.stream().mapToDouble(PathSample::y).min().orElseThrow(), 0.3);
    }

    @Test
    void repeatedPointsAddNothingAndOnePlaceIsOneSample() {
        for (PathKind kind : PathKind.values()) {
            double sag = kind == PathKind.HANGING ? 2 : 0;
            assertEquals(PathSampler.sample(spec(kind, sag, 0, 64, 0, 8, 64, 3, 12, 66, 9), 0.5),
                    PathSampler.sample(spec(kind, sag, 0, 64, 0, 0, 64, 0, 8, 64, 3, 8, 64, 3, 12, 66, 9, 12, 66, 9), 0.5), kind.name());
            List<PathSample> one = PathSampler.sample(spec(kind, sag, 4, 70, 4, 4, 70, 4, 4, 70, 4), 0.5);
            assertEquals(List.of(new PathSample(4.5, 70.5, 4.5, 0)), one, kind.name());
            assertEquals(0, PathSampler.length(spec(kind, sag, 4, 70, 4, 4, 70, 4)), 0);
        }
    }

    @Test
    void sixtyFourPointsSampleContinuously() {
        int[] xyz = new int[PathSpec.MAX_POINTS * 3];
        for (int i = 0; i < PathSpec.MAX_POINTS; i++) {
            xyz[3 * i] = i * 3;
            xyz[3 * i + 1] = 64 + (i % 5) * 2;
            xyz[3 * i + 2] = (i % 2) * 4;
        }
        for (PathSpec spec : everyKind(xyz)) {
            List<PathSample> samples = PathSampler.sample(spec, 0.5);
            assertContinuous(samples, 0.5);
            for (BlockPos p : spec.points()) assertTrue(passesThrough(samples, p), spec.kind() + " misses " + p);
        }
        int[] tooMany = new int[(PathSpec.MAX_POINTS + 1) * 3];
        assertThrows(IllegalArgumentException.class, () -> spec(PathKind.STRAIGHT, 0, tooMany));
    }

    @Test
    void badSpacingsAndHugeLinesAreRefused() {
        PathSpec spec = spec(PathKind.STRAIGHT, 0, 0, 64, 0, 10, 64, 0);
        assertThrows(IllegalArgumentException.class, () -> PathSampler.sample(spec, 0));
        assertThrows(IllegalArgumentException.class, () -> PathSampler.sample(spec, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> PathSampler.sample(spec, Double.POSITIVE_INFINITY));
        PathSpec huge = spec(PathKind.STRAIGHT, 0, -20_000_000, 64, 0, 20_000_000, 64, 0);
        assertThrows(IllegalArgumentException.class, () -> PathSampler.sample(huge, PathSampler.MIN_SPACING));
    }

    private static double[] step(PathSample a, PathSample b) {
        return new double[] {b.x() - a.x(), b.y() - a.y(), b.z() - a.z()};
    }

    private static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
