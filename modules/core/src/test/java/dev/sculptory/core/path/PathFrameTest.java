package dev.sculptory.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Frames along a line: square, upright, following the slope and twisting the least. */
class PathFrameTest {
    private static final double EPS = 1e-9;

    private static List<PathSample> samples(PathKind kind, int... xyz) {
        List<BlockPos> points = new java.util.ArrayList<>();
        for (int i = 0; i < xyz.length; i += 3) points.add(new BlockPos(xyz[i], xyz[i + 1], xyz[i + 2]));
        return PathSampler.sample(new PathSpec(points, kind), 0.125);
    }

    private static void assertSquare(PathFrame.Frame f) {
        assertEquals(1, f.tx() * f.tx() + f.ty() * f.ty() + f.tz() * f.tz(), 1e-6);
        assertEquals(1, f.ux() * f.ux() + f.uy() * f.uy() + f.uz() * f.uz(), 1e-6);
        assertEquals(0, f.tx() * f.ux() + f.ty() * f.uy() + f.tz() * f.uz(), 1e-6);
        // v = u × t
        assertEquals(f.uy() * f.tz() - f.uz() * f.ty(), f.vx(), 1e-6);
        assertEquals(f.uz() * f.tx() - f.ux() * f.tz(), f.vy(), 1e-6);
        assertEquals(f.ux() * f.ty() - f.uy() * f.tx(), f.vz(), 1e-6);
    }

    @Test
    void theSquareFrameIsHorizontalAcrossAndUpIsUpHeadingEast() {
        double[] east = PathFrame.square(1, 0, 0);
        assertEquals(0, east[0], EPS);
        assertEquals(1, east[2], EPS, "right of east is south");
        assertEquals(1, east[4], EPS, "up is up");
        double[] up = PathFrame.square(0, 1, 0);
        assertEquals(1, up[0], EPS, "a vertical piece takes x across");
        double[] slope = PathFrame.square(StrictMath.sqrt(0.5), StrictMath.sqrt(0.5), 0);
        assertEquals(0, slope[1], EPS, "u is horizontal");
        assertTrue(slope[4] > 0 && slope[3] < 0, "v leans back from the climb");
    }

    @Test
    void uprightFramesTurnWithTheHeadingAndKeepItGoingStraightUp() {
        List<PathSample> line = samples(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0, 10, 74, 0, 10, 74, 10);
        List<PathFrame.Frame> frames = PathFrame.upright(line, false, new double[] {0, 0, 1});
        for (PathFrame.Frame f : frames) {
            assertSquare(f);
            assertEquals(0, f.ty(), EPS);
            assertEquals(1, f.vy(), EPS);
        }
        assertEquals(1, frames.get(0).tx(), EPS, "east first");
        // Straight up from (10, 64, 0): still heading east.
        PathFrame.Frame climbing = frames.stream().filter(f -> f.y() > 68 && f.y() < 70).findFirst().orElseThrow();
        assertEquals(1, climbing.tx(), EPS);
        assertEquals(1, frames.get(frames.size() - 1).tz(), EPS, "south at the end");
        // A line straight up from its start takes the fallback heading.
        List<PathFrame.Frame> up = PathFrame.upright(samples(PathKind.STRAIGHT, 0, 64, 0, 0, 74, 0), false,
                new double[] {0, 0, -1});
        assertEquals(-1, up.get(5).tz(), EPS);
    }

    @Test
    void framesFollowingTheSlopeTiltWithTheClimbAndStayLevelAcross() {
        List<PathSample> line = samples(PathKind.STRAIGHT, 0, 64, 0, 10, 74, 0);
        for (PathFrame.Frame f : PathFrame.followSlope(line, false, new double[] {1, 0, 0})) {
            assertSquare(f);
            assertEquals(0, f.uy(), EPS);
            assertEquals(StrictMath.sqrt(0.5), f.ty(), 1e-6);
            assertEquals(StrictMath.sqrt(0.5), f.vy(), 1e-6);
        }
        // Straight up: right stays the fallback heading's right; up turns to lie back along the heading.
        PathFrame.Frame vertical = PathFrame.followSlope(samples(PathKind.STRAIGHT, 0, 64, 0, 0, 74, 0), false,
                new double[] {1, 0, 0}).get(3);
        assertEquals(1, vertical.uz(), EPS);
        assertEquals(-1, vertical.vx(), EPS);
    }

    @Test
    void minimalTwistFramesDoNotRollAroundALevelCurve() {
        // A level closed curve: a frame starting with right pointing up keeps it pointing up all the way round.
        List<PathSample> loop = samples(PathKind.CURVE, 0, 64, 0, 10, 64, 0, 10, 64, 10, 0, 64, 10, 0, 64, 0);
        List<PathFrame.Frame> frames = PathFrame.minimalTwist(loop, true, new double[] {1, 0, 0}, new double[] {0, 1, 0});
        for (PathFrame.Frame f : frames) {
            assertSquare(f);
            assertEquals(1, f.uy(), 1e-6, "no roll at " + f.along());
        }
        // A face looking up carried along a line heading east turns onto it the least way (about north-south).
        List<PathFrame.Frame> east = PathFrame.minimalTwist(samples(PathKind.STRAIGHT, 0, 64, 0, 10, 64, 0), false,
                new double[] {0, 1, 0}, new double[] {1, 0, 0});
        assertEquals(1, east.get(0).tx(), EPS);
        assertEquals(-1, east.get(0).uy(), EPS, "the face's +x side now points down");
        assertEquals(1, east.get(0).vz(), EPS, "its +z side still points south");
    }

    @Test
    void turningTheLeastWayHandlesOppositeDirections() {
        double[] turned = PathFrame.turnLeastWay(new double[] {1, 0, 0}, new double[] {-1, 0, 0}, new double[] {0, 1, 0});
        assertEquals(1, turned[1], EPS, "a half turn about a vertical axis keeps up");
        double[] same = PathFrame.turnLeastWay(new double[] {0, 1, 0}, new double[] {0, 1, 0}, new double[] {1, 0, 0});
        assertEquals(1, same[0], EPS);
    }

    @Test
    void aSteepStretchNeverTurnsItsHeadingBackButALevelOneDoes() {
        double[] east = {1, 0, 0};
        double steepWest = StrictMath.sqrt(1 - 0.1 * 0.1);
        assertSame(east, PathFrame.nextHeading(east, new double[] {-0.1, steepWest, 0}), "steep and turning back: kept");
        double[] northish = PathFrame.nextHeading(east, new double[] {0.05, steepWest, -0.08});
        assertTrue(northish[2] < 0, "steep but not back: taken");
        assertSame(east, PathFrame.nextHeading(east, new double[] {0, 1, 0}), "straight up: kept");
        double[] west = PathFrame.nextHeading(east, new double[] {-StrictMath.sqrt(0.5), StrictMath.sqrt(0.5), 0});
        assertEquals(-1, west[0], EPS, "a 45 degree climb back west turns the heading round");
    }

    @Test
    void aCurveThatClimbsFirstStartsWithTheHeadingItLevelsOutTo() {
        // Up 32, then east: the Curve overshoots a little west on the way up.
        List<PathSample> samples = samples(PathKind.CURVE, 0, 64, 0, 0, 96, 0, 12, 96, 0);
        double[] first = PathFrame.firstHeading(PathFrame.tangents(samples, false), null);
        assertEquals(1, first[0], EPS, "east, not the west of the wobble");
        for (PathFrame.Frame f : PathFrame.upright(samples, false, null)) {
            assertTrue(f.tx() > 0.99, "never turned west at " + f.y());
        }
        for (PathFrame.Frame f : PathFrame.followSlope(samples, false, null)) {
            assertSquare(f);
            assertEquals(1, f.uz(), 1e-6, "right stays south at " + f.y());
        }
    }
}
