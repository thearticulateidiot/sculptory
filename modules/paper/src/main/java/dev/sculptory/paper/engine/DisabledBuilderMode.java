package dev.sculptory.paper.engine;

import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.server.engine.BuilderOutcome;
import dev.sculptory.server.engine.impl.BuilderMode;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;

/**
 * Builder mode is not on Paper yet: it places through vanilla's item placement with mixin accessors and a capture mixin
 * (BuilderPlacement, BuilderCaptureMixin), which need Paper replacements (place/break events, NMS calls). Every action
 * is refused as disabled; nothing is held.
 */
final class DisabledBuilderMode implements BuilderMode<ServerPlayer> {
    private static final String WHY = "builder mode is not available on Paper yet";

    @Override
    public void powers(ServerPlayer player, int powers) {
    }

    @Override
    public int powersOf(UUID player) {
        return 0;
    }

    @Override
    public void permissionsChanged(ServerPlayer player) {
    }

    @Override
    public BuilderOutcome place(ServerPlayer player, C2S.BuilderPlace place) {
        return BuilderOutcome.refused(BuilderOutcome.Refusal.DISABLED, WHY);
    }

    @Override
    public BuilderOutcome breakBlocks(ServerPlayer player, C2S.BuilderBreak breaks) {
        return BuilderOutcome.refused(BuilderOutcome.Refusal.DISABLED, WHY);
    }

    @Override
    public void dragEnd(ServerPlayer player, int dragId) {
    }

    @Override
    public void commitDrag(UUID player) {
    }

    @Override
    public void liveEntries(Set<UUID> ids) {
    }

    @Override
    public boolean dragOpen(UUID player) {
        return false;
    }

    @Override
    public boolean withinReach(ServerPlayer player, int x, int y, int z) {
        return false;
    }

    @Override
    public void tick() {
    }

    @Override
    public void playerLeft(UUID player, boolean keep) {
    }

    @Override
    public void shutdown(boolean keep) {
    }
}
