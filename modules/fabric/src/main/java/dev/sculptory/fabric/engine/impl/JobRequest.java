package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.impl.RecordSink;
import dev.sculptory.server.platform.WriteOptions;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.server.world.ServerWorld;

/**
 * Everything the executor needs to run one bulk job. Permission decisions are resolved by the caller (see
 * {@link EngineRuntime#forPlayer}); the executor only enforces the chunk policy, protection permits and limits.
 *
 * @param owner the player (or {@link #SYSTEM_OWNER}); the per-player active-job cap is counted per owner
 * @param mayLoadChunks the owner may load chunks beyond the loaded area ({@code sculptory.edit.unloaded})
 * @param seed per-job seed exposed through {@code ComputeContext.seed()}
 * @param records receives every changed cell (and entity); the history integration passes a {@code RecordBuilder}
 * @param entities the job's entity work, or {@code null} for none
 */
public record JobRequest(UUID owner, ServerWorld world, EditProgram program, WriteOptions writeOptions,
                         PermitSource permits, boolean mayLoadChunks, long seed, JobListener listener,
                         RecordSink records, EntityWork entities) {
    /** Owner of jobs started by the server itself (tests, admin tools). */
    public static final UUID SYSTEM_OWNER = new UUID(0L, 0L);

    public static final JobListener NO_LISTENER = new JobListener() {
        @Override
        public void progress(UUID job, long done, long total, Phase ph) {}

        @Override
        public void finished(JobResult r) {}
    };

    public JobRequest {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(world);
        Objects.requireNonNull(program);
        Objects.requireNonNull(writeOptions);
        if (permits == null) permits = PermitSource.ALLOW_ALL;
        if (listener == null) listener = NO_LISTENER;
        if (records == null) records = RecordSink.NONE;
    }

    /** A job without entity work. */
    public JobRequest(UUID owner, ServerWorld world, EditProgram program, WriteOptions writeOptions,
                      PermitSource permits, boolean mayLoadChunks, long seed, JobListener listener, RecordSink records) {
        this(owner, world, program, writeOptions, permits, mayLoadChunks, seed, listener, records, null);
    }

    /** A server-owned job: physics off, no protection, may load chunks, no history. */
    public static JobRequest system(ServerWorld world, EditProgram program, JobListener listener) {
        return new JobRequest(SYSTEM_OWNER, world, program, WriteOptions.DEFAULT, PermitSource.ALLOW_ALL,
                true, 0L, listener, RecordSink.NONE);
    }

    public JobRequest withRecords(RecordSink sink) {
        return new JobRequest(owner, world, program, writeOptions, permits, mayLoadChunks, seed, listener, sink,
                entities);
    }

    public JobRequest withPermits(PermitSource source) {
        return new JobRequest(owner, world, program, writeOptions, source, mayLoadChunks, seed, listener, records,
                entities);
    }

    public JobRequest withWriteOptions(WriteOptions options) {
        return new JobRequest(owner, world, program, options, permits, mayLoadChunks, seed, listener, records,
                entities);
    }

    /** This job with entity work ({@code null}: none). */
    public JobRequest withEntities(EntityWork work) {
        return new JobRequest(owner, world, program, writeOptions, permits, mayLoadChunks, seed, listener, records,
                work);
    }
}
