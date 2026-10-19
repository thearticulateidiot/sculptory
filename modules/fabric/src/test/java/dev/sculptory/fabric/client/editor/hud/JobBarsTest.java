package dev.sculptory.fabric.client.editor.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.fabric.client.editor.English;
import dev.sculptory.fabric.client.editor.ui.McFontText;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Rect;
import dev.sculptory.fabric.client.editor.ui.RecordingGraphics;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.UiContext;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The job bars: a finished job's bar goes after a short while (the tracker keeps the job longer), and never more bars
 * than the room allows, finished ones giving way first.
 */
class JobBarsTest {
    private long now = 10_000;
    private final JobBars bars = new JobBars(English.INSTANCE, id -> {}, () -> now);

    private static JobTracker.Job running(String label) {
        return new JobTracker.Job(UUID.randomUUID(), label, 5, 10, Phase.APPLY, null);
    }

    private static JobTracker.Job finished(JobTracker.Job job) {
        return new JobTracker.Job(job.jobId(), job.label(), job.total(), job.total(), Phase.FINALIZE,
                JobOutcome.COMPLETED);
    }

    @Test
    void aFinishedJobsBarGoesAfterAShortWhileThoughTheTrackerStillHasIt() {
        JobTracker.Job fill = running("Fill");
        bars.update(List.of(fill), true);
        assertEquals(1, bars.rowCount());
        bars.update(List.of(finished(fill)), true);
        assertEquals(1, bars.rowCount(), "just done: the full bar shows");
        now += JobBars.FINISHED_SHOWN_MS - 1;
        bars.update(List.of(finished(fill)), true);
        assertEquals(1, bars.rowCount());
        now += 1;
        bars.update(List.of(finished(fill)), true);
        assertTrue(bars.isEmpty(), "gone, while the tracker keeps the job for its result");
    }

    @Test
    void neverMoreBarsThanTheRoomFinishedOnesGivingWayFirst() {
        List<JobTracker.Job> jobs = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            jobs.add(finished(running("Undo " + i)));
        }
        bars.update(jobs, true);
        assertEquals(JobBars.MAX_ROWS, bars.rowCount(), "a burst of undos: at most " + JobBars.MAX_ROWS);

        JobTracker.Job big = running("Fill");
        jobs.add(0, big);
        bars.setMaxRows(2);
        bars.update(jobs, true);
        assertEquals(2, bars.rowCount());
        // The running job keeps its bar; the newest finished one takes the other.
        List<String> texts = bars.node().children().stream().map(JobBarsTest::text).toList();
        assertEquals(List.of("Fill  50%", "Undo 11  done"), texts);

        bars.setMaxRows(0);
        bars.update(jobs, true);
        assertEquals(List.of("Fill  50%"), bars.node().children().stream().map(JobBarsTest::text).toList(),
                "no room at all: still the running job");
    }

    @Test
    void rowsFittingCountsWholeBarsWithTheirGaps() {
        Theme theme = Theme.DARK;
        int row = JobBars.rowHeight(theme);
        assertEquals(1, JobBars.rowsFitting(0, theme));
        assertEquals(1, JobBars.rowsFitting(2 * row, theme), "two bars need a gap too");
        assertEquals(2, JobBars.rowsFitting(2 * row + JobBars.GAP, theme));
        assertEquals(JobBars.MAX_ROWS, JobBars.rowsFitting(1_000, theme));
    }

    /** The text on a row's progress bar. */
    private static String text(Node row) {
        RecordingGraphics g = new RecordingGraphics();
        UiContext ctx = new UiContext(McFontText.INSTANCE, Theme.DARK);
        row.measure(ctx, JobBars.WIDTH);
        row.layout(ctx, new Rect(0, 0, JobBars.WIDTH, 20));
        row.render(g, ctx);
        return g.drawnTexts().get(0);
    }
}
