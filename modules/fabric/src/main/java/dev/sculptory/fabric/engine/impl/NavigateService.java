package dev.sculptory.fabric.engine.impl;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.nav.Landing;
import dev.sculptory.fabric.net.ServerNet;
import dev.sculptory.fabric.perm.FabricPermissionService;
import dev.sculptory.fabric.world.FabricWorldReader;
import dev.sculptory.fabric.world.WorldChecks;
import dev.sculptory.protocol.v2.C2S;
import dev.sculptory.protocol.v2.NavigateMode;
import dev.sculptory.protocol.v2.RejectReason;
import dev.sculptory.protocol.v2.S2C;
import dev.sculptory.server.config.SculptoryConfig;
import dev.sculptory.server.engine.Perm;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/**
 * Jump and Through on the server: the client names the block it looks
 * at and the look direction; the server decides where the feet land ({@link Landing}, over the loaded world) and moves
 * the player there, keeping their facing. Server thread only.
 *
 * <p><b>Who may.</b> The {@code use} and {@code navigate} nodes and Creative or Spectator mode; without them the request
 * is refused {@code NO_PERMISSION} with a notice saying which is missing. <b>Limits</b> ({@code navigate.*}): the block
 * looked at must lie within {@code maxDistance} blocks of the player's eyes ({@code TOO_LARGE}), in a loaded chunk
 * ({@code UNLOADED}); Through walks at most {@code maxThroughDepth} blocks. No spot, or one outside the world border, is
 * {@code INVALID}. Every refusal comes with a {@code sculptory.navigate.*} notice; nothing is changed.
 * A Creative player put where nothing is under their feet (Through, under a floor) is set flying, so they don't fall.
 */
public final class NavigateService {
    public static final String NOTICE_PREFIX = "sculptory.navigate.";
    /** The node missing: [node]. */
    public static final String NEEDS_NODE = NOTICE_PREFIX + "needs_permission";
    public static final String NEEDS_MODE = NOTICE_PREFIX + "needs_creative";
    /** [distance, limit]. */
    public static final String TOO_FAR = NOTICE_PREFIX + "too_far";
    public static final String UNLOADED = NOTICE_PREFIX + "unloaded";
    public static final String NO_SPOT = NOTICE_PREFIX + "no_spot";
    /** [depth]. */
    public static final String NO_SPOT_THROUGH = NOTICE_PREFIX + "no_spot_through";
    public static final String OUTSIDE = NOTICE_PREFIX + "outside_world";

    private NavigateService() {}

    /** What a request comes to: where the feet go, or the refusal and its notice. */
    public record Decision(BlockPos feet, RejectReason reason, S2C.Notice notice) {
        public Decision {
            if ((feet == null) == (reason == null)) throw new IllegalArgumentException("Either a spot or a reason");
        }

        static Decision refused(RejectReason reason, String key, String... args) {
            return new Decision(null, reason, new S2C.Notice(S2C.Notice.Level.WARN, key, List.of(args)));
        }

        public boolean landed() {
            return feet != null;
        }
    }

    /** Decides the request and carries it out: the player moved to the spot, or the refusal's notice sent. */
    public static S2C.NavigateResult navigate(EngineRuntime runtime, ServerPlayerEntity player, C2S.Navigate request) {
        Decision decision = decide(runtime, player, request);
        if (!decision.landed()) {
            ServerNet.sendNotice(player, decision.notice());
            return S2C.NavigateResult.refused(request.reqId(), decision.reason());
        }
        BlockPos feet = decision.feet();
        player.stopRiding();
        player.teleport(player.getServerWorld(), feet.x() + 0.5, feet.y(), feet.z() + 0.5, player.getYaw(),
                player.getPitch());
        player.setVelocity(Vec3d.ZERO);
        player.velocityModified = true;
        player.fallDistance = 0;
        // Through may leave the feet over nothing (under a floor, in a cave's air): a Creative player flies there
        // instead of falling (into the void, past the End's islands).
        if (!player.isSpectator() && player.getAbilities().allowFlying && !player.getAbilities().flying
                && !Landing.grounded(runtime.reader(player.getServerWorld()), feet)) {
            player.getAbilities().flying = true;
            player.sendAbilitiesUpdate();
        }
        return S2C.NavigateResult.landed(request.reqId(), feet);
    }

    /** Where the request would put the player, or why not; changes nothing. */
    public static Decision decide(EngineRuntime runtime, ServerPlayerEntity player, C2S.Navigate request) {
        Objects.requireNonNull(runtime);
        Objects.requireNonNull(player);
        Objects.requireNonNull(request);
        FabricPermissionService permissions = runtime.permissions();
        for (Perm node : List.of(Perm.USE, Perm.NAVIGATE)) {
            if (!permissions.has(player, node)) return Decision.refused(RejectReason.NO_PERMISSION, NEEDS_NODE, node.node());
        }
        if (!player.isCreative() && !player.isSpectator()) return Decision.refused(RejectReason.NO_PERMISSION, NEEDS_MODE);
        SculptoryConfig.NavigateConfig config = runtime.config().navigate;
        BlockPos hit = request.hit();
        Vec3d eyes = player.getEyePos();
        double dx = hit.x() + 0.5 - eyes.x, dy = hit.y() + 0.5 - eyes.y, dz = hit.z() + 0.5 - eyes.z;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance > config.maxDistance) {
            return Decision.refused(RejectReason.TOO_LARGE, TOO_FAR, String.format(Locale.ROOT, "%.0f", distance),
                    Integer.toString(config.maxDistance));
        }
        ServerWorld world = player.getServerWorld();
        FabricWorldReader reader = runtime.reader(world);
        if (!reader.isLoaded(hit.x() >> 4, hit.z() >> 4)) return Decision.refused(RejectReason.UNLOADED, UNLOADED);
        Optional<BlockPos> spot = request.mode() == NavigateMode.JUMP
                ? Landing.onTop(reader, hit)
                : Landing.through(reader, hit, request.dirX(), request.dirY(), request.dirZ(), config.maxThroughDepth);
        if (spot.isEmpty()) {
            return request.mode() == NavigateMode.JUMP ? Decision.refused(RejectReason.INVALID, NO_SPOT)
                    : Decision.refused(RejectReason.INVALID, NO_SPOT_THROUGH, Integer.toString(config.maxThroughDepth));
        }
        BlockPos feet = spot.get();
        if (!WorldChecks.insideBorder(world.getWorldBorder(), feet.x(), feet.z())) {
            return Decision.refused(RejectReason.INVALID, OUTSIDE);
        }
        return new Decision(feet, null, null);
    }
}
