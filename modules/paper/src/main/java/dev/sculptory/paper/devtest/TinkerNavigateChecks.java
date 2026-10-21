package dev.sculptory.paper.devtest;

import static dev.sculptory.paper.devtest.SelfTestRun.box;
import static dev.sculptory.paper.devtest.SelfTestRun.ticks;

import dev.sculptory.core.Box;
import dev.sculptory.core.region.Facing;
import dev.sculptory.paper.SculptoryPlugin;
import dev.sculptory.paper.nms.PaperAccess;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.NavigateMode;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.engine.EditRejected;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Tinker (a block's state changed in place, one step, undone exactly) and Navigate (Jump moves the player through
 * Bukkit's teleport, so {@code PlayerTeleportEvent} fires; a plugin that cancels it keeps the player where they are and
 * the request is refused). Navigate needs a connected player: Bukkit does not teleport a player without a connection.
 */
final class TinkerNavigateChecks {
    private TinkerNavigateChecks() {
    }

    static CompletableFuture<Void> tinker(SelfTestRun r) {
        int x = r.baseX + 410, z = r.baseZ + 4;
        Box area = box(x - 1, 100, z - 1, x + 1, 102, z + 1);
        r.claim(area);
        BlockPos pos = new BlockPos(x, 101, z);
        BlockState north = Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH);
        BlockState south = north.setValue(StairBlock.FACING, Direction.SOUTH);
        PaperAccess.setBlockWithoutCallbacks(r.level, pos, north);
        r.level.getChunkSource().blockChanged(pos);
        int[] before = r.snapshot(area);
        String refusal = "";
        try {
            r.service.block(r.player, new dev.sculptory.core.BlockPos(x, 101, z), Block.getId(north), Block.getId(south),
                    null);
        } catch (EditRejected e) {
            refusal = e.getMessage();
        }
        boolean turned = r.level.getBlockState(pos) == south;
        r.record("tinker: a block's state is changed in place", refusal.isEmpty() && turned,
                refusal.isEmpty() ? "now " + r.level.getBlockState(pos) : "refused " + refusal);
        return r.undo().thenAccept(result -> r.record("tinker: its undo is exact", r.countDiffering(area, before) == 0
                && r.level.getBlockState(pos) == north, result.outcome() + ", " + r.countDiffering(area, before)
                + " cells differ"));
    }

    /** Teleport events seen, and whether this "plugin" cancels them. */
    static final class Teleports implements Listener {
        final List<PlayerTeleportEvent.TeleportCause> seen = new ArrayList<>();
        boolean cancel;

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onTeleport(PlayerTeleportEvent event) {
            seen.add(event.getCause());
            if (cancel) event.setCancelled(true);
        }
    }

    static CompletableFuture<Void> navigate(SelfTestRun r) {
        if (r.attachment != null || r.player.connection == null) {
            r.info("navigate: skipped", "needs a connected player (Bukkit does not teleport one without a connection)");
            return CompletableFuture.completedFuture(null);
        }
        int x = r.baseX + 420, z = r.baseZ + 4;
        Box area = box(x - 4, 99, z - 4, x + 8, 112, z + 4);
        r.claim(area);
        for (BlockPos cell : SelfTestRun.cells(area)) {
            BlockState state = cell.getY() == 100 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            if (r.level.getBlockState(cell) != state) PaperAccess.setBlockWithoutCallbacks(r.level, cell, state);
            r.level.getChunkSource().blockChanged(cell);
        }
        // A pillar to jump onto, 5 blocks east of where the player stands.
        BlockPos pillarTop = new BlockPos(x + 5, 104, z);
        for (int y = 101; y <= 104; y++) PaperAccess.setBlockWithoutCallbacks(r.level, new BlockPos(x + 5, y, z),
                Blocks.STONE.defaultBlockState());
        for (int y = 101; y <= 104; y++) r.level.getChunkSource().blockChanged(new BlockPos(x + 5, y, z));
        r.stand(x + 0.5, 101, z + 0.5, -90f, ItemStack.EMPTY);
        Teleports teleports = new Teleports();
        Bukkit.getPluginManager().registerEvents(teleports, SculptoryPlugin.instance());
        r.cleanup(() -> HandlerList.unregisterAll(teleports));
        C2S.Navigate jump = new C2S.Navigate(1, NavigateMode.JUMP,
                new dev.sculptory.core.BlockPos(pillarTop.getX(), pillarTop.getY(), pillarTop.getZ()), Facing.WEST,
                1f, 0f, 0f);
        // A tick for the teleport of stand() to settle on the client.
        return ticks(5).thenCompose(v -> {
            teleports.seen.clear();
            teleports.cancel = true;
            S2C.NavigateResult refused = r.service.navigate(r.player, jump);
            teleports.cancel = false;
            boolean stayed = Math.abs(r.player.getX() - (x + 0.5)) < 1e-6 && Math.abs(r.player.getY() - 101) < 1e-6;
            r.record("navigate: a plugin cancelling the teleport event keeps the player there",
                    refused.reason() == RejectReason.PROTECTED && stayed && teleports.seen.size() == 1,
                    refused + ", stayed " + stayed + ", events " + teleports.seen);
            teleports.seen.clear();
            S2C.NavigateResult landed = r.service.navigate(r.player, jump);
            boolean onTop = Math.abs(r.player.getY() - 105) < 1e-6 && r.player.blockPosition().getX() == x + 5;
            r.record("navigate: Jump moves the player through Bukkit's teleport (cause PLUGIN)",
                    landed.reason() == null && onTop
                            && teleports.seen.equals(List.of(PlayerTeleportEvent.TeleportCause.PLUGIN)),
                    landed + ", now at " + r.player.blockPosition().toShortString() + ", events " + teleports.seen);
            return CompletableFuture.completedFuture(null);
        });
    }
}
