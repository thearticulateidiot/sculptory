package dev.sculptory.paper.devtest;

import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.core.history.ConflictPolicy;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.engine.PaperEditHost;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.paper.perm.WorldGuardTestRegions;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EngineEditService;
import dev.sculptory.server.platform.WorldBiomes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionAttachment;

/**
 * One self-test run: what the checks share (the player, the throwaway edit service, the world, the results) and what
 * they leave behind, put back at the end whatever happened.
 *
 * <p><b>Areas.</b> A check claims every box it may change ({@link #claim}) before changing it: the box's chunks are held
 * by plugin tickets (loaded, and generated if they never were), and its blocks, block entities, biomes and entities are
 * noted. {@link #finish} first shuts the throwaway service down (a job still running stops), then puts every claimed
 * box back as it was, newest claim first: blocks and block entities physics-free ({@code PaperAccess}), scheduled ticks
 * in the box cleared, biomes set back, entities that were not there removed (players never). So a refused, failed or
 * timed-out check, or one whose own undo did not run, leaves nothing behind. Regions a check adds are named through
 * {@link #region} and removed by {@link #finish} too. {@link #finish} runs once: at the end of the run, on its timeout,
 * and when the plugin is disabled mid-run.
 */
final class SelfTestRun {
    static final int JOB_TIMEOUT_TICKS = 400;
    static final String REGION_PREFIX = "sculptory_selftest_";

    final PaperEditHost host;
    /** A throwaway edit service: history in memory only, shut down by {@link #finish}. */
    final EngineEditService<ServerPlayer, ServerLevel> service;
    final ServerLevel level;
    final ServerPlayer player;
    /** The fake player's grants (null for a named online player). */
    final PermissionAttachment attachment;
    final String runId = Long.toHexString(System.nanoTime() & 0xFFFFFFFFL);
    /** Chunk-aligned, 320 blocks from spawn (well outside spawn protection). */
    final int baseX;
    final int baseZ;
    private final List<SelfTest.Result> results = new ArrayList<>();
    private final List<long[]> tickets = new ArrayList<>();
    private final List<Area> areas = new ArrayList<>();
    private final List<String> regions = new ArrayList<>();
    private final List<Runnable> cleanups = new ArrayList<>();
    private boolean finished;

    SelfTestRun(PaperEditHost host, ServerPlayer player, PermissionAttachment attachment) {
        this.host = host;
        this.level = host.engine().server().overworld();
        this.player = player;
        this.attachment = attachment;
        this.service = new EngineEditService<>(host.engine(), null, null, null, null);
        BlockPos spawn = level.getSharedSpawnPos();
        this.baseX = ((spawn.getX() >> 4) + 20) << 4;
        this.baseZ = ((spawn.getZ() >> 4) + 20) << 4;
    }

    String name() {
        return player.getGameProfile().getName();
    }

    boolean worldGuard() {
        return Bukkit.getPluginManager().isPluginEnabled("WorldGuard");
    }

    // ================================================================== results

    void record(String name, boolean passed, String detail) {
        if (finished) return;
        results.add(new SelfTest.Result(name, passed, detail, false, false));
        Sculptory.LOG.info("Sculptory self-test: {} {}{}", passed ? "PASS" : "FAIL", name,
                detail.isEmpty() ? "" : " (" + detail + ")");
    }

    void info(String name, String detail) {
        if (finished) return;
        results.add(new SelfTest.Result(name, true, detail, true, false));
        Sculptory.LOG.info("Sculptory self-test: INFO {}{}", name, detail.isEmpty() ? "" : " (" + detail + ")");
    }

    void error(String name, Throwable error) {
        if (finished) return;
        results.add(new SelfTest.Result(name, false, String.valueOf(error), false, true));
        Sculptory.LOG.error("Sculptory self-test: ERROR {}", name, error);
    }

    List<SelfTest.Result> results() {
        return List.copyOf(results);
    }

    // ================================================================== areas, regions, clean-up

    /** Holds {@code box}'s chunks and notes the box as it is now; {@link #finish} puts it back. */
    Area claim(Box box) {
        hold(box);
        Area area = new Area(box);
        areas.add(area);
        return area;
    }

    /**
     * A new WorldGuard region id for this run ({@code suffix} names it), removed by {@link #finish}. Fails when one of
     * that id exists already (never touch a region this run did not make).
     */
    String region(String suffix) {
        String id = REGION_PREFIX + runId + "_" + suffix;
        if (WorldGuardTestRegions.exists(level, id)) throw new IllegalStateException("a WorldGuard region " + id + " exists");
        regions.add(id);
        return id;
    }

    /** Something {@link #finish} undoes after the areas are put back (a listener, a grant). */
    void cleanup(Runnable undo) {
        cleanups.add(Objects.requireNonNull(undo));
    }

    /** Whether {@link #finish} has run (checks still in flight stop recording). */
    boolean finished() {
        return finished;
    }

    /**
     * Ends the run, once: the throwaway service stops (its jobs with it), the regions go, every claimed area is put back
     * (newest first), the registered clean-ups run, the chunk tickets and the fake player's grants go. Each step runs
     * even when one before it throws; the first failure is rethrown at the end.
     */
    void finish() {
        if (finished) return;
        finished = true;
        RuntimeException failure = null;
        List<Runnable> steps = new ArrayList<>();
        steps.add(service::shutdown);
        if (worldGuard()) {
            for (String id : regions) {
                steps.add(() -> {
                    if (WorldGuardTestRegions.exists(level, id)) WorldGuardTestRegions.remove(level, id);
                });
            }
        }
        for (int i = areas.size() - 1; i >= 0; i--) steps.add(areas.get(i)::restore);
        for (int i = cleanups.size() - 1; i >= 0; i--) steps.add(cleanups.get(i));
        steps.add(() -> {
            org.bukkit.World world = level.getWorld();
            for (long[] t : tickets) world.removePluginChunkTicket((int) t[0], (int) t[1], SculptoryPlugin.instance());
        });
        if (attachment != null) steps.add(attachment::remove);
        for (Runnable step : steps) {
            try {
                step.run();
            } catch (RuntimeException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    private void hold(Box box) {
        org.bukkit.World world = level.getWorld();
        for (int cx = box.min().x() >> 4; cx <= box.max().x() >> 4; cx++) {
            for (int cz = box.min().z() >> 4; cz <= box.max().z() >> 4; cz++) {
                world.addPluginChunkTicket(cx, cz, SculptoryPlugin.instance());
                world.getChunkAt(cx, cz);
                tickets.add(new long[] {cx, cz});
            }
        }
    }

    /** A box as it was when claimed: its cells (state and block entity), its quarts' biomes, its entities. */
    final class Area {
        final Box box;
        private final int[] states;
        private final CompoundTag[] tiles;
        private final int[] biomes;
        private final Set<UUID> entities = new HashSet<>();

        private Area(Box box) {
            this.box = box;
            List<BlockPos> cells = cells(box);
            states = new int[cells.size()];
            tiles = new CompoundTag[cells.size()];
            for (int i = 0; i < states.length; i++) {
                BlockPos pos = cells.get(i);
                BlockState state = level.getBlockState(pos);
                states[i] = Block.getId(state);
                BlockEntity entity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
                tiles[i] = entity == null ? null : entity.saveWithFullMetadata(level.registryAccess());
            }
            WorldBiomes world = host.engine().biomes(level);
            List<int[]> quarts = quarts();
            biomes = new int[quarts.size()];
            for (int i = 0; i < biomes.length; i++) {
                int[] q = quarts.get(i);
                biomes[i] = world.get(q[0], q[1], q[2]);
            }
            for (Entity entity : level.getEntities((Entity) null, aabb(box), e -> !(e instanceof Player))) {
                entities.add(entity.getUUID());
            }
        }

        /** Whether the box held entities (not players) when it was claimed. */
        boolean hadEntities() {
            return !entities.isEmpty();
        }

        private List<int[]> quarts() {
            List<int[]> out = new ArrayList<>();
            for (int qx = box.min().x() >> 2; qx <= box.max().x() >> 2; qx++) {
                for (int qy = box.min().y() >> 2; qy <= box.max().y() >> 2; qy++) {
                    for (int qz = box.min().z() >> 2; qz <= box.max().z() >> 2; qz++) out.add(new int[] {qx, qy, qz});
                }
            }
            return out;
        }

        /** Puts the box back as it was claimed, physics-free; entities that came since are removed. */
        void restore() {
            for (Entity entity : level.getEntities((Entity) null, aabb(box),
                    e -> !(e instanceof Player) && !entities.contains(e.getUUID()))) {
                entity.discard();
            }
            List<BlockPos> cells = cells(box);
            for (int i = 0; i < states.length; i++) {
                BlockPos pos = cells.get(i);
                BlockState now = level.getBlockState(pos);
                BlockEntity entity = now.hasBlockEntity() ? level.getBlockEntity(pos) : null;
                CompoundTag tile = entity == null ? null : entity.saveWithFullMetadata(level.registryAccess());
                if (Block.getId(now) == states[i] && Objects.equals(tile, tiles[i])) continue;
                BlockState before = Block.stateById(states[i]);
                PaperAccess.setBlockWithoutCallbacks(level, pos, before);
                if (tiles[i] != null) {
                    // Through the chunk: no comparator update, nothing dropped from the block entity it replaces.
                    level.getChunkAt(pos).removeBlockEntity(pos);
                    BlockEntity restored = BlockEntity.loadStatic(pos, before, tiles[i].copy(), level.registryAccess());
                    if (restored != null) level.setBlockEntity(restored);
                }
                level.getChunkSource().blockChanged(pos);
            }
            BoundingBox bounds = new BoundingBox(box.min().x(), box.min().y(), box.min().z(), box.max().x(),
                    box.max().y(), box.max().z());
            level.getBlockTicks().clearArea(bounds);
            level.getFluidTicks().clearArea(bounds);
            WorldBiomes world = host.engine().biomes(level);
            List<int[]> quarts = quarts();
            for (int i = 0; i < biomes.length; i++) {
                int[] q = quarts.get(i);
                if (biomes[i] >= 0 && world.get(q[0], q[1], q[2]) != biomes[i]) world.set(q[0], q[1], q[2], biomes[i]);
            }
            world.flush();
        }
    }

    // ================================================================== the player

    /**
     * Puts the player in Creative at (x, y, z) looking along {@code yaw} (pitch 0), holding {@code held} in the main
     * hand. A real player is moved through Bukkit's teleport, and gets their game mode, place and main-hand item back
     * at the end of the run ({@link #cleanup}); the fake player is set directly.
     */
    void stand(double x, double y, double z, float yaw, ItemStack held) {
        if (attachment == null && !playerPrepared) {
            playerPrepared = true;
            GameType mode = player.gameMode.getGameModeForPlayer();
            ItemStack hand = player.getMainHandItem().copy();
            org.bukkit.Location back = player.getBukkitEntity().getLocation();
            cleanup(() -> {
                player.setItemInHand(InteractionHand.MAIN_HAND, hand);
                player.getBukkitEntity().teleport(back);
                player.setGameMode(mode);
            });
        }
        if (attachment == null) {
            player.setGameMode(GameType.CREATIVE);
            player.getBukkitEntity().teleport(new org.bukkit.Location(level.getWorld(), x, y, z, yaw, 0f));
        } else {
            // Not changeGameModeForPlayer: it tells every client the player's new mode, and a player without a
            // connection has no latency to send. loadGameTypes sets the mode alone.
            CompoundTag mode = new CompoundTag();
            mode.putInt("playerGameType", GameType.CREATIVE.getId());
            player.loadGameTypes(mode);
            player.moveTo(x, y, z, yaw, 0f);
            player.setYHeadRot(yaw);
        }
        player.setItemInHand(InteractionHand.MAIN_HAND, held);
    }

    private boolean playerPrepared;

    // ================================================================== jobs

    CompletableFuture<JobResult> fill(Box box, int state) {
        return job("fill", listener -> service.run(player,
                new OpSpec.Fill(box, new Pattern.Single(state), CellMask.ANY), RunOptions.DEFAULT, listener));
    }

    CompletableFuture<JobResult> undo() {
        return job("undo", listener -> service.undo(player, ConflictPolicy.SKIP_CONFLICTS, listener));
    }

    @FunctionalInterface
    interface Starter {
        JobTicket start(JobListener listener) throws EditRejected;
    }

    /**
     * Starts a job and completes with its result (or fails when it is refused or times out). It also fails when the run
     * has ended by the time the job finishes (the run's timeout put the areas back and stopped the service): the check's
     * next steps must not write into areas already put back.
     */
    CompletableFuture<JobResult> job(String what, Starter starter) {
        CompletableFuture<JobResult> done = new CompletableFuture<>();
        if (finished) {
            done.completeExceptionally(ended(what));
            return done;
        }
        try {
            JobTicket ticket = starter.start(new JobListener() {
                @Override
                public void progress(UUID job, long done, long total, Phase ph) {
                }

                @Override
                public void finished(JobResult r) {
                    if (SelfTestRun.this.finished) {
                        done.completeExceptionally(ended(what));
                    } else {
                        done.complete(r);
                    }
                }
            });
            Sculptory.LOG.info("Sculptory self-test: {} job {} ({} cells)", what, ticket.jobId(), ticket.estimatedCells());
        } catch (EditRejected e) {
            done.completeExceptionally(new IllegalStateException(what + " refused: " + e.getMessage(), e));
            return done;
        }
        Bukkit.getScheduler().runTaskLater(SculptoryPlugin.instance(), () -> done.completeExceptionally(
                new IllegalStateException(what + " did not finish within " + JOB_TIMEOUT_TICKS + " ticks")),
                JOB_TIMEOUT_TICKS);
        return done;
    }

    /**
     * Completes {@code n} ticks from now; fails instead when the run has ended by then, so what a check meant to do
     * next (write, undo) never touches areas already put back.
     */
    CompletableFuture<Void> ticks(int n) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskLater(SculptoryPlugin.instance(), () -> {
            if (finished) {
                done.completeExceptionally(ended(n + " ticks"));
            } else {
                done.complete(null);
            }
        }, n);
        return done;
    }

    private static IllegalStateException ended(String what) {
        return new IllegalStateException("the self-test run ended before " + what + " was done");
    }

    // ================================================================== cells

    static Box box(int x0, int y0, int z0, int x1, int y1, int z1) {
        return Box.of(new dev.sculptory.core.BlockPos(x0, y0, z0), new dev.sculptory.core.BlockPos(x1, y1, z1));
    }

    static AABB aabb(Box box) {
        return new AABB(box.min().x(), box.min().y(), box.min().z(), box.max().x() + 1, box.max().y() + 1,
                box.max().z() + 1);
    }

    static List<BlockPos> cells(Box box) {
        List<BlockPos> out = new ArrayList<>();
        for (int y = box.min().y(); y <= box.max().y(); y++) {
            for (int z = box.min().z(); z <= box.max().z(); z++) {
                for (int x = box.min().x(); x <= box.max().x(); x++) out.add(new BlockPos(x, y, z));
            }
        }
        return out;
    }

    int[] snapshot(Box box) {
        List<BlockPos> cells = cells(box);
        int[] ids = new int[cells.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = Block.getId(level.getBlockState(cells.get(i)));
        return ids;
    }

    int countNot(Box box, int state) {
        int n = 0;
        for (BlockPos pos : cells(box)) {
            if (Block.getId(level.getBlockState(pos)) != state) n++;
        }
        return n;
    }

    int countDiffering(Box box, int[] before) {
        int[] now = snapshot(box);
        int n = 0;
        for (int i = 0; i < now.length; i++) {
            if (now[i] != before[i]) n++;
        }
        return n;
    }
}
