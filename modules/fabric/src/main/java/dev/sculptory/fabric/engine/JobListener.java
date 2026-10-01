package dev.sculptory.fabric.engine;

import dev.sculptory.protocol.v2.Phase;
import java.util.UUID;

/** Job progress callbacks, invoked on the server thread. */
public interface JobListener {
    /** Rate-limited by the executor (at most 4 per second or every 5%). */
    void progress(UUID job, long done, long total, Phase ph);

    /** Called exactly once per admitted job. */
    void finished(JobResult r);
}
