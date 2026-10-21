package dev.sculptory.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.sculptory.mc.loader.ClientLoader;
import dev.sculptory.mc.loader.Loaders;
import dev.sculptory.mc.loader.WorldFrame;
import dev.sculptory.mc.net.Frame;
import dev.sculptory.neoforge.NeoForgeServerLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHighlightEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.network.payload.MinecraftRegisterPayload;
import org.joml.Matrix4fStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *   <li>{@link #onClientStopping}: {@code MinecraftMixin} in {@code Minecraft.destroy} after the game loop ended, where
 *       Fabric fires {@code CLIENT_STOPPING} (render thread, GL current). Not NeoForge's {@code GameShuttingDownEvent},
 *       which {@code Minecraft.stop} posts before the loop's last tick and frame;</li>
 *   <li>{@link #onScreenAfterRender}: {@code ScreenEvent.Render.Post};</li>
 *   <li>{@link #onJoin}: {@code ClientPacketListenerMixin} at the end of {@code ClientPacketListener.handleLogin}, on
 *       the render thread, where Fabric API fires {@code ClientPlayConnectionEvents.JOIN} (its networking mixin injects
 *       at the method's RETURN; NeoForge 21.1's patched method has the one return, checked with javap): after registry
 *       sync and the configuration phase's tags, with the local player added to the client world. NeoForge's
 *       {@code LoggingIn} event comes earlier, before the player has its entity id and is in the world;</li>
 *   <li>{@link #onDisconnect}: {@code ClientPlayerNetworkEvent.LoggingOut} from {@code Minecraft.disconnect}, only
 *       after a join (NeoForge fires it for every disconnect, also one before the play phase). Each hook is queued as
 *       its own task on the render thread, as Fabric's {@code DISCONNECT} hooks reach it (see the listener);</li>
 *   <li>{@link #onClientTagsLoaded}: {@code TagsUpdatedEvent} with cause {@code CLIENT_PACKET_RECEIVED}, fired by the
 *       client's {@code TagCollector} for the configuration phase's tags and for each {@code /reload};</li>
 *   <li>{@link #onChunkLoad}: {@code ChunkEvent.Load} for client worlds, posted at the end of
 *       {@code ClientChunkCache.replaceWithPacketData} for a new column and for one updated in place alike, where
 *       Fabric API fires {@code CHUNK_LOAD} (the method's TAIL);</li>
 *   <li>{@link #onChunkUnload}: {@code ChunkEvent.Unload} for client worlds, which NeoForge posts only from
 *       {@code ClientChunkCache.drop}; {@code ClientChunkCacheMixin} adds the two other places Fabric API fires
 *       {@code CHUNK_UNLOAD} (a slot's column replaced by one at another position, columns outside a smaller view
 *       radius). One list of hooks serves all three, in registration order;</li>
 *   <li>{@link #registerKey}: registered with {@code RegisterKeyMappingsEvent} (mod bus), the event the client side
 *       is wired in ({@code SculptoryNeoForgeClient}); NeoForge posts it once, so a key registered after the wiring
 *       fails;
 *       {@link #boundKey}: NeoForge's public {@code KeyMapping.getKey()};</li>
 *   <li>{@link #onUseBlock}, {@link #onUseItem}, {@link #onAttackBlock}, {@link #onAttackEntity}: NeoForge's
 *       interaction events cannot say "nothing happens and no packet is sent": {@code RightClickBlock} and
 *       {@code RightClickItem} fire inside the prediction that always sends the use packet, {@code LeftClickBlock}
 *       never stops the start-destroy packet, and {@code AttackEntityEvent} fires after the attack packet went out. So
 *       {@code MultiPlayerGameModeMixin} asks the hooks at the very points Fabric API's own mixin does, with its
 *       semantics (see {@link Interactions});</li>
 *   <li>{@link #receiveS2C}: the payload registered by {@link NeoForgeServerLoader}, handled on the render thread, and
 *       announced to servers that are not NeoForge with a play-phase {@code minecraft:register} at join
 *       ({@link #joined});
 *       {@link #canSendC2S}, {@link #sendC2S}: the connection's negotiated channels ({@code hasChannel}) and
 *       {@code send}.</li>
 * </ul>
 */
public final class NeoForgeClientLoader implements ClientLoader {
    private static final Logger LOG = LoggerFactory.getLogger("sculptory");
    /** The join hooks, which {@code ClientPacketListenerMixin} runs ({@link #joined}). */
    private static final List<Runnable> JOIN_HOOKS = new CopyOnWriteArrayList<>();
    /** The chunk unload hooks, run from NeoForge's event and from {@code ClientChunkCacheMixin} ({@link #chunkUnloaded}). */
    private static final List<BiConsumer<ClientLevel, LevelChunk>> CHUNK_UNLOAD_HOOKS = new CopyOnWriteArrayList<>();
    /** The client stopping hooks, which {@code MinecraftMixin} runs ({@link #clientStopping}). */
    private static final List<Runnable> STOPPING_HOOKS = new CopyOnWriteArrayList<>();
    /** Whether the play connection's join hooks ran (and its disconnect hooks have not yet). Render thread only. */
    private static boolean joined;

    private final NeoForgeServerLoader server;
    /** The event keys are registered with while the client side is wired; null after ({@link #closeKeyRegistration}). */
    private RegisterKeyMappingsEvent keys;
    private final List<Runnable> disconnectHooks = new CopyOnWriteArrayList<>();

    NeoForgeClientLoader(RegisterKeyMappingsEvent keys) {
        server = (NeoForgeServerLoader) Loaders.server();
        this.keys = Objects.requireNonNull(keys);
        // One listener, so a disconnect pairs up with a join whatever order the hooks are registered in.
        //
        // The hooks are queued on the render thread, not run in the event: Minecraft.disconnect posts LoggingOut on the
        // render thread before it waits for the integrated server to stop, and an exception escaping a listener would
        // end disconnect there (at quit, Minecraft.destroy catches it and exits while a singleplayer world may still be
        // saving). Fabric API fires DISCONNECT from the connection (channelInactive on the network thread, or
        // Connection.handleDisconnection) and FabricClientLoader hands each hook to Minecraft.execute, so the hooks run
        // later as render-thread tasks, each on its own (BlockableEventLoop.doRunTask logs a task's exception and goes
        // on); at quit they run only if the integrated server's wait still processes tasks. Minecraft.execute itself
        // would run them at once here (already on the render thread, outside a task), so they are queued with tell.
        // Queued in order, they still run before the task that delivers a later login (and so before its join hooks).
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            if (!joined) return;
            joined = false;
            Minecraft minecraft = Minecraft.getInstance();
            disconnectHooks.forEach(minecraft::tell);
        });
        NeoForge.EVENT_BUS.addListener((ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ClientLevel world && event.getChunk() instanceof LevelChunk chunk) {
                chunkUnloaded(world, chunk);
            }
        });
    }

    /**
     * The end of {@code ClientPacketListener.handleLogin} ({@code ClientPacketListenerMixin}): announces
     * {@code sculptory:s2c} to a server that is not NeoForge, then runs the join hooks.
     *
     * <p>Why the announcement: a server that is not NeoForge learns which play channels this client receives from a
     * {@code minecraft:register} sent in the play phase (Fabric API's {@code ServerPlayNetworking.canSend} reads only
     * that; Fabric's own client sends it at this very point, {@code ClientPlayNetworkAddon.onServerReady}). NeoForge
     * 21.1 sends its "play" channel list in {@code NetworkRegistry.onConfigurationFinished}, still in the configuration
     * phase (before {@code ServerboundFinishConfigurationPacket}), where a Fabric server files it under configuration,
     * and sends nothing in play; so a Fabric server never saw {@code sculptory:s2c} and never answered the handshake.
     * It is sent before the join hooks, so it reaches the server ahead of the handshake's Hello. A vanilla server
     * discards the unknown payload; a NeoForge server negotiated the channel already and is not sent it.
     *
     * <p>Nothing here may throw out of {@code handleLogin}: vanilla would turn it into a packet error, and a server
     * that disconnects on those would drop the player. As Fabric's {@code ClientPlayNetworkAddon.onServerReady} does
     * for {@code JOIN}, failures are logged: a failed announcement (a {@code LinkageError} if a NeoForge update changes
     * the internal payload's constructor) leaves the join hooks to run, and a failing hook leaves the next one to run.
     */
    public static void joined(ClientPacketListener connection) {
        if (!connection.getConnectionType().isNeoForge()) {
            try {
                connection.send(new MinecraftRegisterPayload(Set.of(Frame.S2C_ID.id())));
            } catch (RuntimeException | LinkageError e) {
                LOG.warn("Sculptory: could not announce {} to the server; Fabric and vanilla servers will not see the "
                        + "s2c channel, so editing is unavailable on them this session", Frame.S2C_ID.id(), e);
            }
        }
        joined = true;
        for (Runnable hook : JOIN_HOOKS) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                LOG.error("Sculptory: a join hook failed", e);
            }
        }
    }

    /** The client side is wired: {@code RegisterKeyMappingsEvent} is over, later keys are refused. */
    void closeKeyRegistration() {
        keys = null;
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
        STOPPING_HOOKS.add(Objects.requireNonNull(hook));
    }

    /** {@code Minecraft.destroy}, after the game loop ended ({@code MinecraftMixin}): the stopping hooks. */
    public static void clientStopping() {
        STOPPING_HOOKS.forEach(Runnable::run);
    }

    @Override
    public void onScreenAfterRender(Consumer<GuiGraphics> hook) {
        Objects.requireNonNull(hook);
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Render.Post event) -> hook.accept(event.getGuiGraphics()));
    }

    // ---- the connection and the world ----

    @Override
    public void onJoin(Runnable hook) {
        JOIN_HOOKS.add(Objects.requireNonNull(hook));
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
        CHUNK_UNLOAD_HOOKS.add(Objects.requireNonNull(hook));
    }

    /** A client column left the world ({@code ChunkEvent.Unload} or {@code ClientChunkCacheMixin}): the unload hooks. */
    public static void chunkUnloaded(ClientLevel world, LevelChunk chunk) {
        CHUNK_UNLOAD_HOOKS.forEach(hook -> hook.accept(world, chunk));
    }

    // ---- keys ----

    @Override
    public KeyMapping registerKey(KeyMapping key) {
        Objects.requireNonNull(key);
        if (keys == null) {
            throw new IllegalStateException("key " + key.getName() + " registered after RegisterKeyMappingsEvent; "
                    + "NeoForge registers keys only while the client side is wired");
        }
        keys.register(key);
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
