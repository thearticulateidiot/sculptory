package dev.sculptory.fabric.client.session;

import dev.sculptory.protocol.v2.JobOutcome;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.S2C;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link JobTracker} fed by {@code JobAccepted}/{@code JobProgress}/{@code JobFinished}, in any order.
 * Finished jobs stay visible for {@link #RETENTION_NANOS} (at most {@value #MAX_FINISHED} of them).
 * Render thread only.
 */
final class SessionJobTracker implements JobTracker {
    static final long RETENTION_NANOS = 10_000_000_000L;
    static final int MAX_FINISHED = 16;
    static final String UNKNOWN_LABEL = "Job";

    private final Listeners<Runnable> listeners = new Listeners<>();
    private final LinkedHashMap<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Long> finishedAt = new HashMap<>();
    private final Map<UUID, S2C.JobFinished> results = new HashMap<>();

    void accepted(UUID jobId, String label, long estimatedCells) {
        Job existing = jobs.get(jobId);
        if (existing != null) {
            jobs.put(jobId, new Job(jobId, label, existing.done(), existing.total(), existing.phase(), existing.outcome(),
                    existing.changed(), existing.skippedProtected(), existing.skippedConflicts(), existing.strippedNbt()));
        } else {
            jobs.put(jobId, new Job(jobId, label, 0, Math.max(0, estimatedCells), Phase.QUEUED, null));
        }
        Listeners.run(listeners);
    }

    void progress(S2C.JobProgress m) {
        Job existing = jobs.get(m.jobId());
        if (existing != null && existing.finished()) return;
        String label = existing != null ? existing.label() : UNKNOWN_LABEL;
        jobs.put(m.jobId(), new Job(m.jobId(), label, m.done(), m.total(), m.phase(), null));
        Listeners.run(listeners);
    }

    void finished(S2C.JobFinished m, long now) {
        Job existing = jobs.get(m.jobId());
        String label = existing != null ? existing.label() : UNKNOWN_LABEL;
        long total = existing != null ? existing.total() : m.changed();
        long done = m.outcome() == JobOutcome.COMPLETED ? total : existing != null ? existing.done() : 0;
        jobs.put(m.jobId(), new Job(m.jobId(), label, done, total, Phase.FINALIZE, m.outcome(),
                m.changed(), m.skippedProtected(), m.skippedConflicts(), m.strippedNbt()));
        finishedAt.put(m.jobId(), now);
        results.put(m.jobId(), m);
        trimFinished();
        Listeners.run(listeners);
    }

    /** Drops finished jobs older than the retention time. */
    void prune(long now) {
        boolean removed = false;
        Iterator<Map.Entry<UUID, Long>> it = finishedAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Long> entry = it.next();
            if (now - entry.getValue() > RETENTION_NANOS) {
                jobs.remove(entry.getKey());
                results.remove(entry.getKey());
                it.remove();
                removed = true;
            }
        }
        if (removed) Listeners.run(listeners);
    }

    void clear() {
        if (jobs.isEmpty()) return;
        jobs.clear();
        finishedAt.clear();
        results.clear();
        Listeners.run(listeners);
    }

    /** The server's report for a finished job still in the tracker. */
    Optional<S2C.JobFinished> result(UUID jobId) {
        return Optional.ofNullable(results.get(jobId));
    }

    private void trimFinished() {
        while (finishedAt.size() > MAX_FINISHED) {
            UUID oldest = null;
            for (Map.Entry<UUID, Job> entry : jobs.entrySet()) {
                if (entry.getValue().finished()) {
                    oldest = entry.getKey();
                    break;
                }
            }
            if (oldest == null) return;
            jobs.remove(oldest);
            finishedAt.remove(oldest);
            results.remove(oldest);
        }
    }

    @Override
    public List<Job> jobs() {
        return List.copyOf(jobs.values());
    }

    @Override
    public Optional<Job> job(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public Subscription onChange(Runnable listener) {
        return listeners.add(listener);
    }
}
