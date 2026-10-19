package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.Theme;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.ProgressBar;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.protocol.v2.Phase;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The job bars at the bottom right: one progress bar per running or just finished job, with a Cancel button while it
 * runs (in the editor). A finished job's bar stays only {@link #FINISHED_SHOWN_MS} (its toast says what it did), and
 * at most {@link #maxRows} bars show, the room the column has: finished bars give way first, then the oldest running
 * ones. Rows are rebuilt when the set of bars changes and updated in place otherwise.
 */
public final class JobBars {
    public static final int WIDTH = 210;
    /** The most bars shown at once, however much room there is. */
    public static final int MAX_ROWS = 4;
    /** How long a finished job's bar stays, from the first update that saw it finished. */
    public static final long FINISHED_SHOWN_MS = 2_000;
    /** The gap between two bars. */
    public static final int GAP = 2;
    private static final Insets ROW_PADDING = Insets.all(2);

    private final Translator translator;
    private final Consumer<UUID> cancel;
    private final LongSupplier clock;
    private final Column column = new Column();
    private final Map<UUID, JobRow> rows = new LinkedHashMap<>();
    /** When each finished job was first seen finished (clock milliseconds). */
    private final Map<UUID, Long> finishedSince = new HashMap<>();
    private boolean interactive = true;
    private int maxRows = MAX_ROWS;

    /** @param clock milliseconds, for how long a finished bar has shown */
    public JobBars(Translator translator, Consumer<UUID> cancel, LongSupplier clock) {
        this.translator = Objects.requireNonNull(translator);
        this.cancel = Objects.requireNonNull(cancel);
        this.clock = Objects.requireNonNull(clock);
        column.setGap(GAP);
        column.setFixedWidth(WIDTH);
    }

    public Node node() {
        return column;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** How many bars show now. */
    public int rowCount() {
        return rows.size();
    }

    /** One bar's height. */
    public static int rowHeight(Theme theme) {
        return theme.controlHeight + ROW_PADDING.vertical();
    }

    /** How many bars fit in {@code height} units, at most {@link #MAX_ROWS} and at least one. */
    public static int rowsFitting(int height, Theme theme) {
        return Math.max(1, Math.min(MAX_ROWS, (height + GAP) / (rowHeight(theme) + GAP)));
    }

    /** The most bars shown from the next {@link #update} (at least one). */
    public void setMaxRows(int maxRows) {
        this.maxRows = Math.max(1, maxRows);
    }

    /** Shows the jobs; {@code interactive} shows Cancel buttons (only in the editor, where the mouse is free). */
    public void update(List<JobTracker.Job> jobs, boolean interactive) {
        List<JobTracker.Job> shown = shown(jobs, clock.getAsLong());
        List<UUID> ids = shown.stream().map(JobTracker.Job::jobId).toList();
        if (!ids.equals(new ArrayList<>(rows.keySet())) || interactive != this.interactive) {
            this.interactive = interactive;
            column.clear();
            rows.clear();
            for (JobTracker.Job job : shown) {
                JobRow row = new JobRow(job.jobId());
                rows.put(job.jobId(), row);
                column.add(row.panel);
            }
        }
        for (JobTracker.Job job : shown) {
            rows.get(job.jobId()).show(job);
        }
    }

    /**
     * The jobs that get a bar, oldest first: the running ones and those finished less than {@link #FINISHED_SHOWN_MS}
     * ago; past {@link #maxRows} the finished ones go first, then the oldest running ones.
     */
    private List<JobTracker.Job> shown(List<JobTracker.Job> jobs, long now) {
        Set<UUID> present = new HashSet<>();
        List<JobTracker.Job> fresh = new ArrayList<>();
        for (JobTracker.Job job : jobs) {
            present.add(job.jobId());
            if (job.finished()) {
                long since = finishedSince.computeIfAbsent(job.jobId(), id -> now);
                if (now - since >= FINISHED_SHOWN_MS) {
                    continue;
                }
            }
            fresh.add(job);
        }
        finishedSince.keySet().retainAll(present);
        List<JobTracker.Job> shown = new ArrayList<>(fresh);
        for (int i = 0; i < shown.size() && shown.size() > maxRows; ) {
            if (shown.get(i).finished()) {
                shown.remove(i);
            } else {
                i++;
            }
        }
        while (shown.size() > maxRows) {
            shown.remove(0);
        }
        return shown;
    }

    /** The bar text: "Fill  42%", "Fill  waiting", "Fill  done". */
    public static String describe(JobTracker.Job job, Translator tr) {
        String state;
        if (job.finished()) {
            state = tr.translate("sculptory.job." + job.outcome().name().toLowerCase(Locale.ROOT));
        } else if (job.phase() == Phase.QUEUED) {
            state = tr.translate("sculptory.job.queued");
        } else if (job.phase() == Phase.LOAD_CHUNKS) {
            state = tr.translate("sculptory.job.loading");
        } else {
            state = percent(job) + "%";
        }
        return job.label() + "  " + state;
    }

    public static int percent(JobTracker.Job job) {
        if (job.finished()) {
            return 100;
        }
        return job.total() <= 0 ? 0 : (int) Math.min(100, job.done() * 100 / job.total());
    }

    private final class JobRow {
        final UUID id;
        final ProgressBar bar = new ProgressBar();
        final Button cancelButton;
        final Panel panel;

        JobRow(UUID id) {
            this.id = id;
            bar.setGrow(1);
            cancelButton = new Button(translator.translate("sculptory.job.cancel"), () -> cancel.accept(this.id));
            cancelButton.setTooltip(translator.translate("sculptory.job.cancel.tooltip"));
            cancelButton.setVisible(interactive);
            panel = new Panel(Row.of(bar, cancelButton), ROW_PADDING, 0xC0101216, 0);
        }

        void show(JobTracker.Job job) {
            if (job.finished()) {
                bar.setProgress(1);
            } else if (job.phase() == Phase.QUEUED || job.phase() == Phase.LOAD_CHUNKS) {
                bar.setIndeterminate(true);
            } else {
                bar.setProgress(job.total() <= 0 ? 0 : (double) job.done() / job.total());
            }
            bar.setText(describe(job, translator));
            cancelButton.setVisible(interactive && !job.finished());
        }
    }
}
