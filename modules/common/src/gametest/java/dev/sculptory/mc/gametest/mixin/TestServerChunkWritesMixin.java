package dev.sculptory.mc.gametest.mixin;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test mods only (sculptory-gametest, sculptory-fidelity): the headless GameTest server writes chunks without
 * {@code DSYNC}. {@code MinecraftServer.forceSynchronousWrites()} is true by default and {@code GameTestServer} does not override
 * it, so every chunk save waits for the disk; the GameTests generate and save chunks across hundreds of region files,
 * and where a synced 4 KiB write takes milliseconds (about 4.4 ms on a WSL2 virtual disk) the single storage thread
 * falls behind, queues the chunks' NBT, and the 4 GiB heap runs out. No GameTest simulates a power cut, so none
 * needs the writes durable. Descriptor checked with javap against Minecraft 1.21.1 (Mojang names).
 */
@Mixin(MinecraftServer.class)
public abstract class TestServerChunkWritesMixin {
    @Inject(method = "forceSynchronousWrites", at = @At("HEAD"), cancellable = true)
    private void sculptory$unsyncedTestChunkWrites(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof GameTestServer) cir.setReturnValue(false);
    }
}
