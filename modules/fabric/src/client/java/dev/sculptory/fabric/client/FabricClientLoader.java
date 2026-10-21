package dev.sculptory.fabric.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.sculptory.mc.Sculptory;
import dev.sculptory.mc.loader.ClientLoader;
import dev.sculptory.mc.loader.WorldFrame;
import dev.sculptory.mc.net.Frame;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.InvalidateRenderStateCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The {@link ClientLoader} on Fabric: each hook is one Fabric API listener, registered in the default phase when the
 * hook is, so hooks run in the order they were registered (as each one's own listener did before the seam).
 * <ul>
 *   <li>{@link #onClientTickEnd}: {@code ClientTickEvents.END_CLIENT_TICK} (render thread);</li>
 *   <li>{@link #onWorldRenderStart}, {@link #onWorldRenderLast}, {@link #onWorldRenderEnd}:
 *       {@code WorldRenderEvents.START}, {@code LAST}, {@code END}; {@code LAST} gets the {@link WorldFrame} built from
 *       the {@code WorldRenderContext} (render thread);</li>
 *   <li>{@link #suppressBlockOutline}: {@code WorldRenderEvents.BEFORE_BLOCK_OUTLINE}, which draws the outline only when
 *       every listener returns true (render thread);</li>
 *   <li>{@link #onHudRender}: {@code HudRenderCallback.EVENT} (render thread);</li>
 *   <li>{@link #onRenderStateInvalidated}: {@code InvalidateRenderStateCallback.EVENT} (render thread);</li>
 *   <li>{@link #onClientStopping}: {@code ClientLifecycleEvents.CLIENT_STOPPING} (render thread);</li>
 *   <li>{@link #onScreenAfterRender}: {@code ScreenEvents.AFTER_INIT} registers {@code ScreenEvents.afterRender} on
 *       every screen as it is set up (render thread);</li>
 *   <li>{@link #onJoin}: {@code ClientPlayConnectionEvents.JOIN}. Checked against Minecraft 1.21.1 (Mojang names) and
 *       Fabric API 0.116.4: it fires on the render thread at the end of {@code handleLogin}, after registry sync (a
 *       configuration-phase receiver) and after the static registries' tags were bound (the server's tag packet, which
 *       also fires {@code TAGS_LOADED} with {@code client = true});</li>
 *   <li>{@link #onDisconnect}: {@code ClientPlayConnectionEvents.DISCONNECT}, which can fire on the network thread:
 *       each hook is handed to the render thread with {@code Minecraft.execute};</li>
 *   <li>{@link #onClientTagsLoaded}: {@code CommonLifecycleEvents.TAGS_LOADED} when {@code client} is true (render
 *       thread; the integrated server's own reloads pass {@code client = false});</li>
 *   <li>{@link #onChunkLoad}, {@link #onChunkUnload}: {@code ClientChunkEvents.CHUNK_LOAD}, {@code CHUNK_UNLOAD}
 *       (render thread; Fabric fires {@code CHUNK_LOAD} for a column replaced in place too);</li>
 *   <li>{@link #registerKey}, {@link #boundKey}: {@code KeyBindingHelper.registerKeyBinding}, {@code getBoundKeyOf};</li>
 *   <li>{@link #onUseBlock}, {@link #onUseItem}, {@link #onAttackBlock}, {@link #onAttackEntity}:
 *       {@code UseBlockCallback}, {@code UseItemCallback}, {@code AttackBlockCallback}, {@code AttackEntityCallback}.
 *       Fabric fires these on the server too (the integrated server's handling of the packet); there the hook is not
 *       asked and the listener passes, so the hooks only ever see the client's own clicks, on the render thread;</li>
 *   <li>{@link #receiveS2C}: {@code ClientPlayNetworking.registerGlobalReceiver} for {@code sculptory:s2c}. Fabric 1.21.1
 *       (networking API 4.3.0) runs play payload handlers through {@code Minecraft.execute}, i.e. on the render thread
 *       (checked with javap), so no extra hop is needed;</li>
 *   <li>{@link #canSendC2S}, {@link #sendC2S}: {@code ClientPlayNetworking.canSend} (which throws while not connected:
 *       false then) and {@code send}.</li>
 * </ul>
 * The payload types themselves are registered by the server loader ({@code FabricServerLoader}), which the common
 * entrypoint installs before any client entrypoint runs.
 */
public final class FabricClientLoader implements ClientLoader {
    // ---- LoaderInfo: Fabric Loader's answers, as the server loader gives them ----

    @Override
    public Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    @Override
    public Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public String modVersion() {
        return FabricLoader.getInstance().getModContainer(Sculptory.MOD_ID)
                .map(mod -> mod.getMetadata().getVersion().getFriendlyString())
                .orElse("unknown");
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    // ---- ticks and rendering ----

    @Override
    public void onClientTickEnd(Runnable hook) {
        ClientTickEvents.END_CLIENT_TICK.register(client -> hook.run());
    }

    @Override
    public void onWorldRenderStart(Runnable hook) {
        WorldRenderEvents.START.register(context -> hook.run());
    }

    @Override
    public void onWorldRenderLast(Consumer<WorldFrame> hook) {
        WorldRenderEvents.LAST.register(context -> hook.accept(frame(context)));
    }

    @Override
    public void onWorldRenderEnd(Runnable hook) {
        WorldRenderEvents.END.register(context -> hook.run());
    }

    @Override
    public void suppressBlockOutline(BooleanSupplier suppress) {
        WorldRenderEvents.BEFORE_BLOCK_OUTLINE.register((context, hit) -> !suppress.getAsBoolean());
    }

    @Override
    public void onHudRender(BiConsumer<GuiGraphics, DeltaTracker> hook) {
        HudRenderCallback.EVENT.register(hook::accept);
    }

    @Override
    public void onRenderStateInvalidated(Runnable hook) {
        InvalidateRenderStateCallback.EVENT.register(hook::run);
    }

    @Override
    public void onClientStopping(Runnable hook) {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> hook.run());
    }

    @Override
    public void onScreenAfterRender(Consumer<GuiGraphics> hook) {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) ->
                ScreenEvents.afterRender(screen).register((s, context, mouseX, mouseY, delta) -> hook.accept(context)));
    }

    /** The frame a world-render event describes; the context's world is the client's level. */
    private static WorldFrame frame(WorldRenderContext context) {
        return new WorldFrame(context.camera(), context.positionMatrix(), context.projectionMatrix(), context.frustum(),
                context.world());
    }

    // ---- the connection and the world ----

    @Override
    public void onJoin(Runnable hook) {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> hook.run());
    }

    @Override
    public void onDisconnect(Runnable hook) {
        // DISCONNECT can fire on the network thread.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(hook));
    }

    @Override
    public void onClientTagsLoaded(Runnable hook) {
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (client) hook.run();
        });
    }

    @Override
    public void onChunkLoad(BiConsumer<ClientLevel, LevelChunk> hook) {
        ClientChunkEvents.CHUNK_LOAD.register(hook::accept);
    }

    @Override
    public void onChunkUnload(BiConsumer<ClientLevel, LevelChunk> hook) {
        ClientChunkEvents.CHUNK_UNLOAD.register(hook::accept);
    }

    // ---- keys ----

    @Override
    public KeyMapping registerKey(KeyMapping key) {
        return KeyBindingHelper.registerKeyBinding(key);
    }

    @Override
    public InputConstants.Key boundKey(KeyMapping key) {
        return KeyBindingHelper.getBoundKeyOf(key);
    }

    // ---- the player's interactions ----

    @Override
    public void onUseBlock(UseBlock hook) {
        UseBlockCallback.EVENT.register((player, world, hand, hit) ->
                world.isClientSide() ? hook.use(player, world, hand, hit) : InteractionResult.PASS);
    }

    @Override
    public void onUseItem(UseItem hook) {
        UseItemCallback.EVENT.register((player, world, hand) -> world.isClientSide() ? hook.use(player, world, hand)
                : InteractionResultHolder.pass(player.getItemInHand(hand)));
    }

    @Override
    public void onAttackBlock(AttackBlock hook) {
        AttackBlockCallback.EVENT.register((player, world, hand, pos, face) ->
                world.isClientSide() ? hook.attack(player, world, hand, pos, face) : InteractionResult.PASS);
    }

    @Override
    public void onAttackEntity(AttackEntity hook) {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
                world.isClientSide() ? hook.attack(player, world, hand, entity, hit) : InteractionResult.PASS);
    }

    // ---- networking ----

    @Override
    public void receiveS2C(Consumer<byte[]> handler) {
        ClientPlayNetworking.registerGlobalReceiver(Frame.S2C_ID, (frame, context) -> handler.accept(frame.bytes()));
    }

    @Override
    public boolean canSendC2S() {
        try {
            return ClientPlayNetworking.canSend(Frame.C2S_ID);
        } catch (IllegalStateException notConnected) {
            return false;
        }
    }

    @Override
    public void sendC2S(byte[] frame) {
        ClientPlayNetworking.send(Frame.c2s(frame));
    }
}
