package dev.sculptory.neoforge.mixin;

import dev.sculptory.neoforge.NeoForgeServerLoader;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code ServerLoader.onServerStopping} and {@code onAfterSave} where Fabric API fires {@code SERVER_STOPPING} and
 * {@code AFTER_SAVE} (fabric-lifecycle-events-v1 in Fabric API 0.116.4, read with javap): the head of
 * {@code stopServer}, which runs on every stop including after a crashed tick loop (NeoForge's
 * {@code ServerStoppingEvent} does not), and the tail of {@code saveAllChunks} (NeoForge has no such event). Both
 * methods are unchanged in NeoForge 21.1's patched {@code MinecraftServer} (checked with javap).
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {
    @Inject(method = "stopServer", at = @At("HEAD"))
    private void sculptory$serverStopping(CallbackInfo ci) {
        NeoForgeServerLoader.serverStopping((MinecraftServer) (Object) this);
    }

    @Inject(method = "saveAllChunks", at = @At("TAIL"))
    private void sculptory$afterSave(boolean suppressLog, boolean flush, boolean forced,
                                     CallbackInfoReturnable<Boolean> cir) {
        NeoForgeServerLoader.afterSave((MinecraftServer) (Object) this);
    }
}
