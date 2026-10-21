package dev.sculptory.mc.loader;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/**
 * The client-side seam between the loader-free code and a mod loader. Every hook runs on the render thread (the
 * loader hops onto it where its own event does not): join and disconnect are delivered after the loader's own
 * handling of them (join after registry sync and tag binding, so block-state ids are final).
 */
public interface ClientLoader extends LoaderInfo {
    // ---- ticks and rendering ----

    /** The end of a client tick. */
    void onClientTickEnd(Runnable hook);

    /** The start of a world frame, before anything is drawn. */
    void onWorldRenderStart(Runnable hook);

    /** After vanilla drew the world (terrain, entities, particles, weather), before the HUD. */
    void onWorldRenderLast(Consumer<WorldFrame> hook);

    /** The world frame is complete. */
    void onWorldRenderEnd(Runnable hook);

    /** While {@code suppress} is true, vanilla does not draw its block outline. */
    void suppressBlockOutline(BooleanSupplier suppress);

    /** After vanilla drew the HUD (not while a screen covers it: screens draw themselves). */
    void onHudRender(BiConsumer<GuiGraphics, DeltaTracker> hook);

    /** Resources or the video settings were reloaded: GPU state built on them is stale. */
    void onRenderStateInvalidated(Runnable hook);

    /** The client is shutting down. */
    void onClientStopping(Runnable hook);

    /** Development harnesses only: after a screen drew itself. */
    void onScreenAfterRender(Consumer<GuiGraphics> hook);

    // ---- the connection and the world ----

    /** The play connection is set up: registry sync and tag binding are done, the player is in a world. */
    void onJoin(Runnable hook);

    /** The play connection ended. */
    void onDisconnect(Runnable hook);

    /** The server's tags were (re)applied to the static registries ({@code /reload} too). */
    void onClientTagsLoaded(Runnable hook);

    /** A chunk column was loaded into the client world, also when a new chunk packet replaced a loaded one in place. */
    void onChunkLoad(BiConsumer<ClientLevel, LevelChunk> hook);

    void onChunkUnload(BiConsumer<ClientLevel, LevelChunk> hook);

    // ---- keys ----

    /** Registers a key mapping with the game's controls screen and returns it. */
    KeyMapping registerKey(KeyMapping key);

    /** The key the player bound to the mapping (vanilla keeps it private). */
    InputConstants.Key boundKey(KeyMapping key);

    // ---- the player's interactions, before vanilla handles them ----
    // The hook answers first. PASS: vanilla goes on as if the hook were not there. SUCCESS: vanilla's own handling is
    // skipped but its interaction packet is still sent (builder mode relies on that: the server's block still takes
    // the click). CONSUME or FAIL: nothing happens and no packet is sent. This is what Fabric's interaction events do;
    // another loader's implementation must match it.

    /** Right-click on a block. */
    void onUseBlock(UseBlock hook);

    /** Right-click with the item in hand (no block hit). */
    void onUseItem(UseItem hook);

    /** Left-click on a block; any accepted result sends vanilla's start-destroy packet. */
    void onAttackBlock(AttackBlock hook);

    /** Left-click on an entity; {@code hit} may be null (the loader may not have one). */
    void onAttackEntity(AttackEntity hook);

    interface UseBlock {
        InteractionResult use(Player player, Level world, InteractionHand hand, BlockHitResult hit);
    }

    interface UseItem {
        InteractionResultHolder<ItemStack> use(Player player, Level world, InteractionHand hand);
    }

    interface AttackBlock {
        InteractionResult attack(Player player, Level world, InteractionHand hand, BlockPos pos, Direction face);
    }

    interface AttackEntity {
        InteractionResult attack(Player player, Level world, InteractionHand hand, Entity entity,
                                 EntityHitResult hit);
    }

    // ---- networking: the sculptory:s2c and sculptory:c2s payloads ----

    /** Receives every {@code sculptory:s2c} frame, on the render thread. */
    void receiveS2C(Consumer<byte[]> handler);

    /** Whether the server accepts {@code sculptory:c2s} (connected, and the server registered the channel). */
    boolean canSendC2S();

    /** Sends one frame on {@code sculptory:c2s}. */
    void sendC2S(byte[] frame);
}
