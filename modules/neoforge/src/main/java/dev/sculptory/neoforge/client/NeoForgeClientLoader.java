package dev.sculptory.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.sculptory.mc.loader.ClientLoader;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.WorldFrame;
import dev.sculptory.mc.net.Frame;
import dev.sculptory.neoforge.NeoForgeServerLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.joml.Matrix4fStack;

/**
 * The {@link ClientLoader} on NeoForge. Most hooks are one listener on NeoForge's game bus, registered at the default
 * priority when the hook is, so they run in registration order. Where NeoForge has no event with Fabric's meaning, a
 * client mixin of this module calls the hooks (the {@code dev.sculptory.neoforge.client.mixin} package).
 * <ul>
 *   <li>{@link #onClientTickEnd}: {@code ClientTickEvent.Post}, at the tail of {@code Minecraft.tick} like Fabric's
 *       {@code END_CLIENT_TICK};</li>
 *   <li>{@link #onWorldRenderStart}: {@code RenderLevelStageEvent} at {@code AFTER_SKY}, the first stage of a world
 *       frame. Fabric's {@code START} is the head of {@code renderLevel}, before the sky;</li>
 *   <li>{@link #onWorldRenderLast}: {@code RenderLevelStageEvent} at {@code AFTER_LEVEL}, right after
 *       {@code renderLevel} returned, with the same camera, matrices and frustum. Fabric's {@code LAST} runs just before
 *       {@code renderLevel}'s debug renderer, while the world's model-view matrix (the camera rotation) is still on
 *       {@code RenderSystem}'s stack; the editor's line and quad batches draw through it, so it is pushed again around
 *       the hooks. The fog is off here (Fabric's {@code LAST} still has the terrain fog);</li>
 *   <li>{@link #onWorldRenderEnd}: the same {@code AFTER_LEVEL} event at the lowest priority, so it follows every
 *       {@code LAST} hook, as Fabric's {@code END} (the return of {@code renderLevel}) follows {@code LAST};</li>
 *   <li>{@link #suppressBlockOutline}: {@code RenderHighlightEvent.Block}, cancelled while {@code suppress} is true
 *       (vanilla then draws no block outline, as when Fabric's {@code BEFORE_BLOCK_OUTLINE} returns false);</li>
 *   <li>{@link #onHudRender}: {@code RenderGuiEvent.Post}, after every HUD layer. Fabric's {@code HudRenderCallback}
 *       runs at the tail of {@code Gui.render}, after it turned the depth test off; here it is still on and the layers
 *       wrote depth up to their z, so the depth test is turned off around the hooks, as Fabric's see it;</li>
 *   <li>{@link #onRenderStateInvalidated}: no NeoForge event; {@code LevelRendererMixin} calls it at the head of
 *       {@code LevelRenderer.allChanged}, where Fabric's {@code InvalidateRenderStateCallback} fires;</li>
 *   <li>{@link #onClientStopping}: {@code GameShuttingDownEvent} from {@code Minecraft.stop} (render thread). Fabric's
 *       {@code CLIENT_STOPPING} fires a little later, in {@code Minecraft.destroy}, with GL still current in both;</li>
 *   <li>{@link #onScreenAfterRender}: {@code ScreenEvent.Render.Post};</li>
 *   <li>{@link #onJoin}: {@code ClientPlayerNetworkEvent.LoggingIn}, on the render thread inside
 *       {@code ClientPacketListener.handleLogin}: after registry sync and the configuration phase's tags (both done
 *       before the play phase starts) and after the client world exists, but before the local player gets its entity
 *       id and is added to the world, which Fabric's {@code JOIN} (the end of {@code handleLogin}) comes after. Run
 *       there, not deferred, so the join hooks still run before any packet that follows the login packet;</li>
 *   <li>{@link #onDisconnect}: {@code ClientPlayerNetworkEvent.LoggingOut} from {@code Minecraft.disconnect}, on the
 *       render thread, only after a join (NeoForge fires it for every disconnect, also one before the play phase);</li>
 *   <li>{@link #onClientTagsLoaded}: {@code TagsUpdatedEvent} with cause {@code CLIENT_PACKET_RECEIVED}, fired by the
 *       client's {@code TagCollector} for the configuration phase's tags and for each {@code /reload};</li>
 *   <li>{@link #onChunkLoad}, {@link #onChunkUnload}: {@code ChunkEvent.Load} and {@code .Unload} for client worlds
 *       ({@code ClientChunkCache.replaceWithPacketData}, also for a column replaced in place, and {@code drop});</li>
 *   <li>{@link #registerKey}: collected and registered in {@code RegisterKeyMappingsEvent} (mod bus), which NeoForge
 *       fires after the mods are constructed, so keys must be registered during construction;
 *       {@link #boundKey}: NeoForge's public {@code KeyMapping.getKey()};</li>
 *   <li>{@link #onUseBlock}, {@link #onUseItem}, {@link #onAttackBlock}, {@link #onAttackEntity}: NeoForge's
 *       interaction events cannot say "nothing happens and no packet is sent": {@code RightClickBlock} and
 *       {@code RightClickItem} fire inside the prediction that always sends the use packet, {@code LeftClickBlock}
 *       never stops the start-destroy packet, and {@code AttackEntityEvent} fires after the attack packet went out. So
 *       {@code MultiPlayerGameModeMixin} asks the hooks at the very points Fabric API's own mixin does, with its
 *       semantics (see {@link Interactions});</li>
 *   <li>{@link #receiveS2C}: the payload registered by {@link NeoForgeServerLoader}, handled on the render thread;
 *       {@link #canSendC2S}, {@link #sendC2S}: the connection's negotiated channels ({@code hasChannel}) and
 *       {@code send}.</li>
 * </ul>
 */
public final class NeoForgeClientLoader implements ClientLoader {
    private final NeoForgeServerLoader server;
    /** Keys registered before {@code RegisterKeyMappingsEvent}; null once it ran. */
    private List<KeyMapping> pendingKeys = new ArrayList<>();
    /** Whether the play connection's join hooks ran (and its disconnect hooks have not yet). */
    private boolean joined;
    private final List<Runnable> joinHooks = new CopyOnWriteArrayList<>();
    private final List<Runnable> disconnectHooks = new CopyOnWriteArrayList<>();

    NeoForgeClientLoader(IEventBus modBus) {
        server = (NeoForgeServerLoader) Loaders.server();
        modBus.addListener(this::registerKeys);
        // One listener each, so joins and disconnects pair up whatever order hooks are registered in.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) -> {
            joined = true;
            joinHooks.forEach(Runnable::run);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            if (!joined) return;
            joined = false;
            disconnectHooks.forEach(Runnable::run);
        });
    }

    private void registerKeys(RegisterKeyMappingsEvent event) {
        pendingKeys.forEach(event::register);
        pendingKeys = null;
    }

    // ---- LoaderInfo: the server loader's answers ----

    @Override
    public Path configDir() {
        return server.configDir();
    }

    @Override
    public Path gameDir() {
        return server.gameDir();
    }

    @Override
    public String modVersion() {
        return server.modVersion();
    }

    @Override
    public boolean isModLoaded(String modId) {
        return server.isModLoaded(modId);
    }

    @Override
    public boolean isDevelopment() {
        return server.isDevelopment();
    }

    // ---- ticks and rendering ----

    @Override
    public void onClientTickEnd(Runnable hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> hook.run());
    }

    @Override
    public void onWorldRenderStart(Runnable hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_SKY) hook.run();
        });
    }

    @Override
    public void onWorldRenderLast(Consumer<WorldFrame> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) return;
            ClientLevel world = Minecraft.getInstance().level;
            if (world == null) return;
            WorldFrame frame = new WorldFrame(event.getCamera(), event.getModelViewMatrix(), event.getProjectionMatrix(),
                    event.getFrustum(), world);
            // renderLevel popped the world's model-view matrix before AFTER_LEVEL; Fabric's LAST still has it.
            Matrix4fStack modelView = RenderSystem.getModelViewStack();
            modelView.pushMatrix();
            modelView.mul(frame.positionMatrix());
            RenderSystem.applyModelViewMatrix();
            try {
                hook.accept(frame);
            } finally {
                modelView.popMatrix();
                RenderSystem.applyModelViewMatrix();
            }
        });
    }

    @Override
    public void onWorldRenderEnd(Runnable hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, (RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) hook.run();
        });
    }

    @Override
    public void suppressBlockOutline(BooleanSupplier suppress) {
        Objects.requireNonNull(suppress);
        NeoForge.EVENT_BUS.addListener((RenderHighlightEvent.Block event) -> {
            if (suppress.getAsBoolean()) event.setCanceled(true);
        });
    }

    @Override
    public void onHudRender(BiConsumer<GuiGraphics, DeltaTracker> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) -> {
            RenderSystem.disableDepthTest();
            try {
                hook.accept(event.getGuiGraphics(), event.getPartialTick());
            } finally {
                // Gui.render turns it off itself right after this event.
                RenderSystem.enableDepthTest();
            }
        });
    }

    @Override
    public void onRenderStateInvalidated(Runnable hook) {
        Interactions.renderStateInvalidated(Objects.requireNonNull(hook));
    }

    @Override
    public void onClientStopping(Runnable hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((GameShuttingDownEvent event) -> hook.run());
    }

    @Override
    public void onScreenAfterRender(Consumer<GuiGraphics> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Post event) -> hook.accept(event.getGuiGraphics()));
    }

    // ---- the connection and the world ----

    @Override
    public void onJoin(Runnable hook) {
        joinHooks.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onDisconnect(Runnable hook) {
        disconnectHooks.add(Objects.requireNonNull(hook));
    }

    @Override
    public void onClientTagsLoaded(Runnable hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent event) -> {
            if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) hook.run();
        });
    }

    @Override
    public void onChunkLoad(BiConsumer<ClientLevel, LevelChunk> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ClientLevel world && event.getChunk() instanceof LevelChunk chunk) {
                hook.accept(world, chunk);
            }
        });
    }

    @Override
    public void onChunkUnload(BiConsumer<ClientLevel, LevelChunk> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ClientLevel world && event.getChunk() instanceof LevelChunk chunk) {
                hook.accept(world, chunk);
            }
        });
    }

    // ---- keys ----

    @Override
    public KeyMapping registerKey(KeyMapping key) {
        Objects.requireNonNull(key);
        if (pendingKeys == null) {
            throw new IllegalStateException("key " + key.getName() + " registered after RegisterKeyMappingsEvent; "
                    + "NeoForge registers keys while the mods are constructed");
        }
        pendingKeys.add(key);
        return key;
    }

    @Override
    public InputConstants.Key boundKey(KeyMapping key) {
        return key.getKey();
    }

    // ---- the player's interactions ----

    @Override
    public void onUseBlock(UseBlock hook) {
        Interactions.useBlock(Objects.requireNonNull(hook));
    }

    @Override
    public void onUseItem(UseItem hook) {
        Interactions.useItem(Objects.requireNonNull(hook));
    }

    @Override
    public void onAttackBlock(AttackBlock hook) {
        Interactions.attackBlock(Objects.requireNonNull(hook));
    }

    @Override
    public void onAttackEntity(AttackEntity hook) {
        Interactions.attackEntity(Objects.requireNonNull(hook));
    }

    // ---- networking ----

    @Override
    public void receiveS2C(Consumer<byte[]> handler) {
        server.receiveS2C(handler);
    }

    @Override
    public boolean canSendC2S() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(Frame.C2S_ID);
    }

    @Override
    public void sendC2S(byte[] frame) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) throw new IllegalStateException("not connected");
        connection.send(Frame.c2s(frame));
    }
}
