package dev.sculptory.fabric.client.editor.brush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.brush.Dab;
import dev.sculptory.fabric.client.session.StrokeHandle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StrokeControllerTest {
    private static final long MS = 1_000_000L;
    /** A dab rate high enough that pacing never gets in the way. */
    private static final int FAST = 10_000;

    private final List<FakeStroke> strokes = new ArrayList<>();
    private int beginCalls;
    private boolean refuse;
    /** Copies per dab the host reports in flight (brush symmetry). */
    private int copiesPerDab = 1;

    private final StrokeController.Host host = new StrokeController.Host() {
        @Override
        public StrokeHandle begin() {
            beginCalls++;
            if (refuse) {
                return null;
            }
            FakeStroke stroke = new FakeStroke(strokes.size() + 1);
            strokes.add(stroke);
            return stroke;
        }

        @Override
        public int inFlight(StrokeHandle stroke) {
            return ((FakeStroke) stroke).inFlight() * copiesPerDab;
        }
    };

    private StrokeController controller(boolean flow) {
        return new StrokeController(host, flow);
    }

    private FakeStroke only() {
        assertEquals(1, strokes.size(), "one server stroke");
        return strokes.get(0);
    }

    /** Block-space x of each dab (the controller's fixed point, back to blocks). */
    private static List<Double> xs(List<Dab> dabs) {
        return dabs.stream().map(d -> d.x16() / 16.0).toList();
    }

    // ---------------------------------------------------------------- sampling

    @Test
    void theFirstDabLandsOnTheHitInFixedPoint() {
        StrokeController c = controller(false);
        c.aim(10.5, 65, -3.25);
        c.press(8, FAST);
        assertTrue(strokes.isEmpty(), "the server stroke begins with the first dab");
        c.update(0);
        assertEquals(List.of(new Dab(0, 168, 1040, -52, Dab.FULL_PRESSURE)), only().dabs);
        assertEquals(Dab.of(0, 10.5, 65, -3.25, Dab.FULL_PRESSURE), only().dabs.get(0));
    }

    /** The Shape brush keeps the world a dab writes before anything of it is sent: the host hears of each dab first. */
    @Test
    void theHostHearsOfEachDabBeforeTheStrokeGetsIt() {
        List<String> heard = new ArrayList<>();
        StrokeController c = new StrokeController(new StrokeController.Host() {
            @Override
            public StrokeHandle begin() {
                FakeStroke stroke = new FakeStroke(strokes.size() + 1);
                strokes.add(stroke);
                return stroke;
            }

            @Override
            public int inFlight(StrokeHandle stroke) {
                return ((FakeStroke) stroke).inFlight();
            }

            @Override
            public void beforeDab(Dab dab) {
                heard.add(dab.index() + " after " + strokes.get(strokes.size() - 1).dabs.size());
            }
        }, false);
        c.aim(0.2, 64.7, 0.9);
        c.press(8, FAST);
        c.setSpacing(1.0);
        // Every two blocks of path fit to one point: the repeats in between are no dabs, and nothing is heard of them.
        c.setSnap((x, y, z) -> new double[] {Math.floor(x / 2) * 2 + 0.5, 65, 0.5});
        c.update(0);
        c.aim(1.3, 64.7, 0.9);
        c.update(10 * MS);
        c.aim(2.3, 64.7, 0.9);
        c.update(20 * MS);
        c.aim(4.3, 64.7, 0.9);
        c.update(30 * MS);
        assertEquals(List.of(0.5, 2.5, 4.5), xs(only().dabs));
        assertEquals(List.of("0 after 0", "1 after 1", "2 after 2"), heard);
    }

    @Test
    void aSetSpacingAndASnapFitEveryDabAndSkipRepeats() {
        StrokeController.Snap grid = (x, y, z) -> new double[] {Math.floor(x) + 0.5, Math.floor(y + 0.5), Math.floor(z) + 0.5};
        StrokeController c = controller(false);
        c.aim(0.2, 64.7, 0.9);
        c.press(8, FAST);
        c.setSpacing(1.0);
        assertEquals(1.0, c.spacing(), "the Shape brush's spacing replaces the radius's");
        c.setSnap(grid);
        c.update(0);
        assertEquals(List.of(new Dab(0, 8, 16 * 65, 8, Dab.FULL_PRESSURE)), only().dabs, "fitted to the grid");
        // The path points 1.2, 2.2 and 3.2 fit to 1.5, 2.5 and 3.5.
        c.aim(3.7, 64.7, 0.9);
        c.update(10 * MS);
        assertEquals(List.of(0.5, 1.5, 2.5, 3.5), xs(only().dabs));
        c.setRadius(8);
        assertEquals(2.0, c.spacing(), "a new radius sets the radius's spacing again");
        c.end(StrokeController.EndReason.RELEASED);

        // Diagonally, a block of path is under a block per axis: a point that fits where the last dab is gives no dab.
        StrokeController diagonal = controller(false);
        diagonal.aim(0.2, 64.7, 0.2);
        diagonal.press(8, FAST);
        diagonal.setSpacing(1.0);
        diagonal.setSnap(grid);
        diagonal.update(0);
        diagonal.aim(1.35, 65.85, 1.35); // one step: (0.78, 65.28, 0.78) fits to the first dab's (0.5, 65, 0.5)
        diagonal.update(10 * MS);
        diagonal.aim(2.5, 67, 2.5); // two steps: (1.35, 65.85, 1.35) and (1.93, 66.43, 1.93) both fit to (1.5, 66, 1.5)
        diagonal.update(20 * MS);
        List<Dab> dabs = strokes.get(1).dabs;
        assertEquals(List.of(new Dab(0, 8, 16 * 65, 8, Dab.FULL_PRESSURE), new Dab(1, 24, 16 * 66, 24, Dab.FULL_PRESSURE)), dabs,
                "each shape once, indices dense");
    }

    @Test
    void dabsAreSpacedAQuarterOfTheRadiusAlongThePath() {
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(8, FAST);
        assertEquals(2.0, c.spacing());
        c.update(0);
        c.aim(7, 64, 0);
        c.update(10 * MS);
        assertEquals(List.of(0.0, 2.0, 4.0, 6.0), xs(only().dabs));
        c.aim(7.9, 64, 0);
        c.update(20 * MS);
        assertEquals(4, only().dabs.size(), "1.9 blocks is short of the spacing");
        c.aim(8, 64, 0);
        c.update(30 * MS);
        assertEquals(List.of(0.0, 2.0, 4.0, 6.0, 8.0), xs(only().dabs));
        assertEquals(List.of(0, 1, 2, 3, 4), only().dabs.stream().map(Dab::index).toList(), "indices increase from 0");
    }

    @Test
    void smallBrushesSpaceDabsOneBlockApart() {
        StrokeController c = controller(false);
        c.press(2, FAST);
        assertEquals(1.0, c.spacing());
        c.setRadius(32);
        assertEquals(8.0, c.spacing());
        assertEquals(104.0, c.jumpDistance());
    }

    @Test
    void thePathIsInterpolatedInThreeDimensions() {
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(4, FAST);
        c.update(0);
        c.aim(3, 68, 0); // 5 blocks away: 3 across, 4 up
        c.update(10 * MS);
        List<Dab> dabs = only().dabs;
        assertEquals(6, dabs.size());
        for (int i = 1; i <= 5; i++) {
            Dab dab = dabs.get(i);
            assertEquals(0.6 * i, dab.x16() / 16.0, 1 / 16.0, "x of dab " + i);
            assertEquals(64 + 0.8 * i, dab.y16() / 16.0, 1 / 16.0, "y of dab " + i);
            assertEquals(0, dab.z16());
        }
    }

    @Test
    void aJumpIsNotInterpolated() {
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(4, FAST);
        assertEquals(20.0, c.jumpDistance());
        c.update(0);
        c.aim(30, 64, 0);
        c.update(10 * MS);
        assertEquals(List.of(0.0, 30.0), xs(only().dabs), "one dab where the cursor landed");
        only().acked = 2;
        c.aim(49, 64, 0); // 19 blocks: under the jump distance, so interpolated
        c.update(20 * MS);
        assertEquals(2 + 16, only().dabs.size(), "16 fit in the window: 15 along the path, then the hit");
        assertEquals(49.0, only().dabs.get(17).x16() / 16.0);
        assertEquals(45.0, only().dabs.get(16).x16() / 16.0);
    }

    @Test
    void aMissedRayEmitsNothingAndAPressInTheSkyWaitsForTheTerrain() {
        StrokeController c = controller(true);
        c.clearAim();
        c.press(4, FAST);
        c.update(0);
        c.update(500 * MS);
        assertTrue(strokes.isEmpty());
        c.aim(1, 64, 1);
        c.update(510 * MS);
        assertEquals(1, only().dabs.size());
        c.clearAim();
        c.update(1000 * MS);
        assertEquals(1, only().dabs.size(), "no flow while the ray misses");
    }

    // ---------------------------------------------------------------- flow

    @Test
    void flowRepeatsADabEvery150MillisecondsWhileHeldStill() {
        StrokeController c = controller(true);
        c.aim(0, 64, 0);
        c.press(4, 20);
        c.update(0);
        c.update(100 * MS);
        assertEquals(1, only().dabs.size());
        c.update(150 * MS);
        assertEquals(2, only().dabs.size());
        c.aim(0.5, 64, 0); // a small wobble is still "held still"
        c.update(200 * MS);
        c.update(299 * MS);
        assertEquals(2, only().dabs.size());
        c.update(300 * MS);
        assertEquals(3, only().dabs.size());
        assertEquals(0.5, only().dabs.get(2).x16() / 16.0, "the repeat lands on the cursor");
    }

    @Test
    void withoutFlowAStillCursorDabsOnce() {
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(4, 20);
        c.update(0);
        c.update(1000 * MS);
        c.update(2000 * MS);
        assertEquals(1, only().dabs.size());
    }

    // ---------------------------------------------------------------- pacing and backpressure

    @Test
    void theDabRateLimitCoalescesToTheLatestPosition() {
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(4, 20); // burst of 4 dabs, then one per 50 ms
        c.update(0);
        c.aim(10, 64, 0);
        c.update(0);
        assertEquals(List.of(0.0, 1.0, 2.0, 10.0), xs(only().dabs),
                "three more allowed: two along the path, the last on the latest hit");
        c.aim(12, 64, 0);
        c.update(10 * MS);
        assertEquals(4, only().dabs.size(), "the bucket is empty");
        c.update(50 * MS);
        assertEquals(List.of(0.0, 1.0, 2.0, 10.0, 12.0), xs(only().dabs), "one token: straight to the latest hit");
    }

    @Test
    void backpressureWaitsForAcknowledgementsThenCoalesces() {
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(8, FAST); // spacing 2
        c.update(0);
        c.aim(30, 64, 0);
        c.update(MS);
        FakeStroke stroke = only();
        assertEquals(StrokeController.MAX_IN_FLIGHT, stroke.dabs.size());
        assertEquals(StrokeController.MAX_IN_FLIGHT, stroke.inFlight());

        c.aim(40, 64, 0);
        c.update(2 * MS);
        assertEquals(StrokeController.MAX_IN_FLIGHT, stroke.dabs.size(), "nothing while 16 are unacknowledged");

        stroke.acked = 3;
        c.update(3 * MS);
        assertEquals(19, stroke.dabs.size());
        assertEquals(List.of(32.0, 34.0, 40.0), xs(stroke.dabs.subList(16, 19)),
                "three free slots: two along the path, then the latest hit");
    }

    /** With brush symmetry each dab costs its copies, in the dab rate and in the in-flight window, as on the server. */
    @Test
    void symmetricDabsCostTheirCopiesInTheRateAndTheWindow() {
        copiesPerDab = 4;
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(4, 20, 4); // 20 copies a second: one four-copy dab in the bucket, then one per 200 ms
        assertEquals(4, c.dabCost());
        c.update(0);
        c.aim(10, 64, 0);
        c.update(10 * MS);
        assertEquals(List.of(0.0), xs(only().dabs), "the bucket held one dab's copies");
        c.update(200 * MS);
        assertEquals(List.of(0.0, 10.0), xs(only().dabs), "four tokens later: straight to the latest hit");

        // The window: sixteen copies are four dabs in flight.
        StrokeController fast = controller(false);
        fast.aim(0, 64, 0);
        fast.press(8, FAST, 4);
        fast.update(0);
        fast.aim(30, 64, 0);
        fast.update(MS);
        FakeStroke stroke = strokes.get(1);
        assertEquals(4, stroke.dabs.size(), "sixteen copies in flight");
        stroke.acked = 1;
        fast.aim(40, 64, 0);
        fast.update(2 * MS);
        assertEquals(List.of(0.0, 2.0, 4.0, 30.0, 40.0), xs(stroke.dabs), "one dab's room: the latest hit");

        // A slow rate still lets a dab through: the bucket always holds one dab's copies.
        StrokeController slow = controller(false);
        slow.aim(0, 64, 0);
        slow.press(4, 1, 4);
        slow.update(0);
        assertEquals(1, strokes.get(2).dabs.size());
        slow.setDabCost(1);
        assertEquals(1, slow.dabCost());
    }

    // ---------------------------------------------------------------- ending

    @Test
    void releaseEndsTheServerStroke() {
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(4, FAST);
        c.update(0);
        c.end(StrokeController.EndReason.RELEASED);
        assertTrue(only().ended);
        assertFalse(only().cancelled);
        assertFalse(c.pressed());
        c.aim(5, 64, 0);
        c.update(MS);
        assertEquals(1, only().dabs.size(), "nothing after the end");
        c.end(StrokeController.EndReason.RELEASED);
        assertEquals(1, only().endCalls, "ending twice does nothing");
    }

    @Test
    void escapeCancelsAndEveryOtherReasonEnds() {
        for (StrokeController.EndReason reason : StrokeController.EndReason.values()) {
            strokes.clear();
            StrokeController c = controller(false);
            c.aim(0, 64, 0);
            c.press(4, FAST);
            c.update(0);
            c.end(reason);
            boolean cancel = reason == StrokeController.EndReason.CANCELLED;
            assertEquals(cancel, only().cancelled, reason.name());
            assertEquals(!cancel, only().ended, reason.name());
        }
    }

    @Test
    void aPressEndsTheStrokeInProgress() {
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(4, FAST);
        c.update(0);
        c.press(4, FAST);
        c.update(MS);
        assertEquals(2, strokes.size());
        assertTrue(strokes.get(0).ended);
        assertEquals(0, strokes.get(1).dabs.get(0).index(), "each stroke numbers its dabs from 0");
    }

    @Test
    void aRejectedStrokeEmitsNothingUntilTheNextPressButStillEnds() {
        StrokeController c = controller(true);
        c.aim(0, 64, 0);
        c.press(4, FAST);
        c.update(0);
        only().active = false; // the server refused a batch
        c.aim(10, 64, 0);
        c.update(MS);
        c.update(500 * MS);
        assertEquals(1, only().dabs.size());
        assertTrue(c.pressed());
        assertFalse(c.emitting());
        c.end(StrokeController.EndReason.RELEASED);
        assertTrue(only().ended, "the server still hears the end");

        c.press(4, FAST);
        c.update(600 * MS);
        assertEquals(2, strokes.size(), "a new press starts again");
    }

    @Test
    void aStrokeThatCannotBeginIsNotRetriedEveryFrame() {
        refuse = true;
        StrokeController c = controller(true);
        c.aim(0, 64, 0);
        c.press(4, FAST);
        c.update(0);
        c.update(200 * MS);
        c.update(400 * MS);
        assertEquals(1, beginCalls);
        assertFalse(c.emitting());
        c.end(StrokeController.EndReason.RELEASED);
    }

    // ---------------------------------------------------------------- settings changes mid-stroke

    @Test
    void aRadiusChangeMidStrokeRespacesAndRestartsTheServerStroke() {
        StrokeController c = controller(false);
        c.aim(0, 64, 0);
        c.press(4, FAST);
        c.update(0);
        c.aim(2, 64, 0);
        c.update(10 * MS);
        assertEquals(List.of(0.0, 1.0, 2.0), xs(only().dabs));

        c.setRadius(16);
        c.requestRestart();
        assertEquals(4.0, c.spacing(), "the new spacing applies at once");
        c.aim(5, 64, 0);
        c.update(100 * MS);
        assertEquals(1, strokes.size(), "restarts wait until the stroke is 250 ms old");
        assertEquals(3, strokes.get(0).dabs.size(), "3 blocks is short of the new spacing");

        c.aim(6, 64, 0);
        c.update(260 * MS);
        assertEquals(2, strokes.size());
        assertTrue(strokes.get(0).ended, "the old server stroke ended");
        assertEquals(List.of(new Dab(0, 96, 1024, 0, Dab.FULL_PRESSURE)), strokes.get(1).dabs,
                "the path continues in the new stroke, numbered from 0");
        assertEquals(2, c.strokesBegun());

        c.requestRestart();
        c.update(300 * MS);
        assertEquals(2, strokes.size(), "at most one restart per 250 ms");
        c.update(520 * MS);
        assertEquals(3, strokes.size());
    }

    @Test
    void aRestartBeforeTheFirstDabIsNotNeeded() {
        StrokeController c = controller(false);
        c.press(4, FAST);
        c.requestRestart(); // settings changed while pressing in the sky
        c.aim(0, 64, 0);
        c.update(0);
        c.update(500 * MS);
        assertEquals(1, strokes.size());
    }

    /** A stroke handle that records what it is told; in flight = dabs sent minus dabs acknowledged. */
    private static final class FakeStroke implements StrokeHandle {
        final int id;
        final List<Dab> dabs = new ArrayList<>();
        boolean active = true;
        boolean ended;
        boolean cancelled;
        int endCalls;
        int acked;

        FakeStroke(int id) {
            this.id = id;
        }

        int inFlight() {
            return dabs.size() - acked;
        }

        @Override
        public int strokeId() {
            return id;
        }

        @Override
        public void dab(Dab d) {
            if (active) {
                dabs.add(d);
            }
        }

        @Override
        public void end() {
            endCalls++;
            ended = true;
            active = false;
        }

        @Override
        public void cancel() {
            cancelled = true;
            active = false;
        }

        @Override
        public boolean active() {
            return active;
        }
    }
}
