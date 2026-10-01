package dev.sculptory.fabric.client.editor.hud;

import dev.sculptory.fabric.client.editor.Translator;
import dev.sculptory.fabric.client.editor.ui.Insets;
import dev.sculptory.fabric.client.editor.ui.Node;
import dev.sculptory.fabric.client.editor.ui.layout.Column;
import dev.sculptory.fabric.client.editor.ui.layout.Row;
import dev.sculptory.fabric.client.editor.ui.widget.Button;
import dev.sculptory.fabric.client.editor.ui.widget.ProgressBar;
import dev.sculptory.fabric.client.session.JobTracker;
import dev.sculptory.protocol.v2.Phase;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The job bars at the bottom right: one progress bar per running or recently finished job, with a
 * Cancel button while it runs (in the editor). Rows are rebuilt when the set of jobs changes and
 * updated in place otherwise.
 */
public final class JobBars {
    public static final int WIDTH = 210;

    private final Translator translator;
    private final Consumer<UUID> cancel;
    private final Column column = new Column();
    private final Map<UUID, JobRow> rows = new LinkedHashMap<>();
    private boolean interactive = true;

    public JobBars(Translator translator, Consumer<UUID> cancel) {
        this.translator = Objects.requireNonNull(translator);
        this.cancel = Objects.requireNonNull(cancel);
        column.setGap(2);
        column.setFixedWidth(WIDTH);
    }

    public Node node() {
        return column;
    }

    public boolean isEmpty() {
        return rows.isEmpty();
    }

    /** Shows the jobs; {@code interactive} shows Cancel buttons (only in the editor, where the mouse is free). */
    public void update(List<JobTracker.Job> jobs, boolean interactive) {
        List<UUID> ids = jobs.stream().map(JobTracker.Job::jobId).toList();
        if (!ids.equals(new ArrayList<>(rows.keySet())) || interactive != this.interactive) {
            this.interactive = interactive;
            column.clear();
            rows.clear();
            for (JobTracker.Job job : jobs) {
                JobRow row = new JobRow(job.jobId());
                rows.put(job.jobId(), row);
                column.add(row.panel);
            }
        }
        for (JobTracker.Job job : jobs) {
            rows.get(job.jobId()).show(job);
        }
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
            panel = new Panel(Row.of(bar, cancelButton), Insets.all(2), 0xC0101216, 0);
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
