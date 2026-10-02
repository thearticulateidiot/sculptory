package dev.sculptory.server.engine.impl;

import dev.sculptory.core.Box;
import dev.sculptory.core.edit.EditProgram;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.platform.PlatformPermissions;
import dev.sculptory.server.platform.WriteOptions;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Everything the executor needs to run one bulk job. Permission decisions are resolved by the caller (see
 * {@link #forPlayer}); the executor only enforces the chunk policy, protection permits and limits.
 *
 * @param owner the player (or {@link #SYSTEM_OWNER}); the per-player active-job cap is counted per owner
 * @param mayLoadChunks the owner may load chunks beyond the loaded area ({@code sculptory.edit.unloaded})
 * @param seed per-job seed exposed through {@code ComputeContext.seed()}
 * @param records receives every changed cell (and entity); the history integration passes a {@code RecordBuilder}
 * @param entities the job's entity work, or {@code null} for none
 * @param <W> the platform's world type
 */
public record JobRequest<W>(UUID owner, W world, EditProgram program, WriteOptions writeOptions,
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
    public JobRequest(UUID owner, W world, EditProgram program, WriteOptions writeOptions, PermitSource permits,
                      boolean mayLoadChunks, long seed, JobListener listener, RecordSink records) {
        this(owner, world, program, writeOptions, permits, mayLoadChunks, seed, listener, records, null);
    }

    /** A server-owned job: physics off, no protection, may load chunks, no history. */
    public static <W> JobRequest<W> system(W world, EditProgram program, JobListener listener) {
        return new JobRequest<>(SYSTEM_OWNER, world, program, WriteOptions.DEFAULT, PermitSource.ALLOW_ALL, true, 0L,
                listener, RecordSink.NONE);
    }

    /**
     * A job request for a player in their world, resolving the permission-dependent parts: editing enabled, physics
     * ({@code sculptory.physics}), operator NBT, per-chunk protection and loading unloaded chunks. Does not check the
     * op-level nodes for the op itself ({@code use}/{@code region}) or volume limits.
     *
     * @throws EditRejected {@code DISABLED} when editing is off, {@code NO_PERMISSION} for physics without the node
     */
    public static <P, W> JobRequest<W> forPlayer(EngineHost<P, W> host, P player, EditProgram program,
                                                 RunOptions options, JobListener listener, RecordSink records)
            throws EditRejected {
        if (!host.config().editingEnabled) throw new EditRejected(RejectReason.DISABLED);
        PlatformPermissions<P, W> permissions = host.permissions();
        if (options.physics() && !permissions.has(player, Perm.PHYSICS)) {
            throw new EditRejected(RejectReason.NO_PERMISSION, Perm.PHYSICS.node());
        }
        W world = host.world(player);
        Box bounds = program.bounds();
        PermitSource permits = PermitSource.forPlayer(permissions, player, world, bounds);
        WriteOptions write = new WriteOptions(options.physics(), permissions.mayWriteOperatorNbt(player));
        return new JobRequest<>(host.id(player), world, program, write, permits,
                permissions.has(player, Perm.EDIT_UNLOADED), ThreadLocalRandom.current().nextLong(), listener, records);
    }

    public JobRequest<W> withRecords(RecordSink sink) {
        return new JobRequest<>(owner, world, program, writeOptions, permits, mayLoadChunks, seed, listener, sink,
                entities);
    }

    public JobRequest<W> withPermits(PermitSource source) {
        return new JobRequest<>(owner, world, program, writeOptions, source, mayLoadChunks, seed, listener, records,
                entities);
    }

    public JobRequest<W> withWriteOptions(WriteOptions options) {
        return new JobRequest<>(owner, world, program, options, permits, mayLoadChunks, seed, listener, records,
                entities);
    }

    /** This job with entity work ({@code null}: none). */
    public JobRequest<W> withEntities(EntityWork work) {
        return new JobRequest<>(owner, world, program, writeOptions, permits, mayLoadChunks, seed, listener, records,
                work);
    }
}
