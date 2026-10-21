package dev.sculptory.paper.devtest;

import com.mojang.authlib.GameProfile;
import dev.sculptory.core.Box;
import dev.sculptory.core.edit.CellMask;
import dev.sculptory.core.edit.OpSpec;
import dev.sculptory.core.edit.Pattern;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.engine.PaperEditHost;
import dev.sculptory.paper.perm.PaperPermissions;
import dev.sculptory.paper.perm.WorldGuardTestRegions;
import dev.sculptory.protocol.v2.Phase;
import dev.sculptory.server.engine.EditRejected;
import dev.sculptory.server.engine.JobListener;
import dev.sculptory.server.engine.JobResult;
import dev.sculptory.server.engine.JobTicket;
import dev.sculptory.server.engine.Perm;
import dev.sculptory.server.engine.RunOptions;
import dev.sculptory.server.engine.impl.EngineEditService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.bukkit.Bukkit;
import org.bukkit.permissions.PermissionAttachment;

/**
 * Dev only ({@code /sculptory wgbench [height]}, gated like the self-test): how long the region checks take at
 * admission. A 200x200 box {@code height} tall (y 100 up) over 13x13 chunks well away from spawn, as a fake player
 * (not op, no WorldGuard bypass): first with no region there, then with 20 overlapping WorldGuard cuboids of mixed
 * heights and priorities. Times, each the median of five runs: every chunk's permit ({@code PaperPermissions.chunk}, as
 * the engine asks it), and the whole admission of a fill of air over the box ({@code EditService.run}, through a
 * throwaway edit service; the job is cancelled before it runs, and air over air would change nothing anyway). The
 * regions and chunk tickets are removed afterwards.
 */
public final class RegionBench {
    private static final int SIZE = 200;
    private static final int REGIONS = 20;
    private static final int RUNS = 5;
    static final JobListener NO_LISTENER = new JobListener() {
        @Override
        public void progress(UUID job, long done, long total, Phase ph) {
        }

        @Override
        public void finished(JobResult r) {
        }
    };

    private RegionBench() {
    }

    public static int run(PaperEditHost host, CommandSourceStack source, int height) {
        if (!Bukkit.getPluginManager().isPluginEnabled("WorldGuard")) {
            source.sendFailure(Component.literal("WorldGuard is not installed"));
            return 0;
        }
        MinecraftServer server = host.engine().server();
        ServerLevel level = server.overworld();
        ServerPlayer player = new ServerPlayer(server, level, new GameProfile(
                UUID.nameUUIDFromBytes("sculptory-bench".getBytes(StandardCharsets.UTF_8)), "SculptoryBench"),
                ClientInformation.createDefault());
        PermissionAttachment attachment = player.getBukkitEntity().addAttachment(SculptoryPlugin.instance());
        for (Perm perm : Perm.values()) attachment.setPermission(perm.node(), true);
        EngineEditService<ServerPlayer, ServerLevel> service = new EngineEditService<>(host.engine(), null, null, null,
                null);
        BlockPos spawn = level.getSharedSpawnPos();
        int x0 = ((spawn.getX() >> 4) + 60) << 4, z0 = ((spawn.getZ() >> 4) + 60) << 4, y0 = 100;
        Box box = Box.of(new dev.sculptory.core.BlockPos(x0, y0, z0),
                new dev.sculptory.core.BlockPos(x0 + SIZE - 1, y0 + height - 1, z0 + SIZE - 1));
        String run = Long.toHexString(System.nanoTime() & 0xFFFFFFL);
        List<String> ids = new ArrayList<>();
        org.bukkit.World world = level.getWorld();
        int cx0 = x0 >> 4, cz0 = z0 >> 4, cx1 = (x0 + SIZE - 1) >> 4, cz1 = (z0 + SIZE - 1) >> 4;
        try {
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    world.addPluginChunkTicket(cx, cz, SculptoryPlugin.instance());
                    world.getChunkAt(cx, cz);
                }
            }
            String none = measure(host, service, player, level, box, cx0, cz0, cx1, cz1);
            for (int i = 0; i < REGIONS; i++) {
                String id = "sculptory_bench_" + run + "_" + i;
                int rx = x0 + 9 * i, rz = z0 + (i % 5) * 37, ry = y0 + (i * 7) % Math.max(1, height);
                WorldGuardTestRegions.addWithPriority(level, id, rx, ry, rz, rx + 39, ry + 3 + i % 6, rz + 39, i % 3);
                ids.add(id);
            }
            String regions = measure(host, service, player, level, box, cx0, cz0, cx1, cz1);
            String text = "Sculptory region bench (" + SIZE + "x" + SIZE + "x" + height + ", "
                    + ((cx1 - cx0 + 1) * (cz1 - cz0 + 1)) + " chunks, protection "
                    + host.engine().permissions().protection().name() + "): no region: " + none + "; " + REGIONS
                    + " overlapping regions: " + regions;
            Sculptory.LOG.info(text);
            source.sendSuccess(() -> Component.literal(text), false);
            return 1;
        } finally {
            for (String id : ids) WorldGuardTestRegions.remove(level, id);
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) world.removePluginChunkTicket(cx, cz, SculptoryPlugin.instance());
            }
            attachment.remove();
            service.shutdown();
        }
    }

    /** "permits <median> ms (<denied columns>), admission <median> ms (<outcome>)". */
    private static String measure(PaperEditHost host, EngineEditService<ServerPlayer, ServerLevel> service,
                                  ServerPlayer player, ServerLevel level, Box box, int cx0, int cz0, int cx1,
                                  int cz1) {
        PaperPermissions permissions = host.engine().permissions();
        long[] permitNanos = new long[RUNS];
        long[] admitNanos = new long[RUNS];
        int deniedChunks = 0;
        String outcome = "";
        for (int r = 0; r < RUNS; r++) {
            int denied = 0;
            long t0 = System.nanoTime();
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    if (!(permissions.chunk(player, level, cx, cz, box)
                            instanceof dev.sculptory.server.engine.ChunkPermit.Allow)) denied++;
                }
            }
            permitNanos[r] = System.nanoTime() - t0;
            deniedChunks = denied;
            OpSpec.Fill fill = new OpSpec.Fill(box, new Pattern.Single(Block.getId(Blocks.AIR.defaultBlockState())),
                    CellMask.ANY);
            long t1 = System.nanoTime();
            try {
                JobTicket ticket = service.run(player, fill, RunOptions.DEFAULT, NO_LISTENER);
                admitNanos[r] = System.nanoTime() - t1;
                service.cancel(player, ticket.jobId());
                outcome = "admitted";
            } catch (EditRejected e) {
                admitNanos[r] = System.nanoTime() - t1;
                outcome = "refused: " + e.getMessage();
            }
        }
        return "permits " + ms(median(permitNanos)) + " ms (" + deniedChunks + " chunks not all allowed), admission "
                + ms(median(admitNanos)) + " ms (" + outcome + ")";
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static String ms(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.2f", nanos / 1e6);
    }
}
