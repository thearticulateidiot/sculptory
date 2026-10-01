package dev.sculptory.fabric.client.editor.render.ghost;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.sculptory.core.Box;
import dev.sculptory.core.buffer.BlockBuffer;
import dev.sculptory.core.transform.Mirror;
import dev.sculptory.core.transform.Transform;
import java.util.List;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * A client command to try ghost previews without the Place tool: it copies the blocks around the player from the
 * client world and previews them beside the original.
 *
 * <ul>
 *   <li>{@code /bsclient ghost on [size]}: capture a cube of {@code size}³ (default 16, at most 128) centred on the
 *       player and preview it {@code max(20, size + 4)} blocks east (+X).</li>
 *   <li>{@code /bsclient ghost rotate}: step through the 8 transforms (4 clockwise turns, then the same mirrored).</li>
 *   <li>{@code /bsclient ghost light}: switch between full-bright and world light.</li>
 *   <li>{@code /bsclient ghost erase}: re-capture with air cells included, shown as red erase outlines (toggle).</li>
 *   <li>{@code /bsclient ghost off}</li>
 * </ul>
 * The HUD status ("Preview simplified: ...") shows in the action bar when it changes. Client-side only: nothing is
 * sent to the server. Client thread only.
 */
public final class GhostDebug {
    private static final int DEFAULT_SIZE = 16;
    private static final int MAX_SIZE = 128;
    private static final int MIN_OFFSET_X = 20;
    private static final List<Transform> TRANSFORMS = Transform.all();

    private static @Nullable GhostDebug instance;

    private final GhostRenderer renderer;
    private @Nullable GhostPlacement placement;
    private int size = DEFAULT_SIZE;
    private int transformIndex;
    private GhostPlacement.LightMode lightMode = GhostPlacement.LightMode.FLAT;
    private boolean includeAir;
    private String lastHudText = "";

    private GhostDebug(GhostRenderer renderer) {
        this.renderer = renderer;
    }

    /** Registers the command and its render hook. Call once from the client initializer. */
    public static void register() {
        if (instance != null) {
            return;
        }
        GhostDebug debug = new GhostDebug(GhostRenderer.shared());
        instance = debug;
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(debug.command()));
        WorldRenderEvents.LAST.register(debug::render);
        // DISCONNECT can fire on the network thread.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(debug::stop));
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> command() {
        return literal("bsclient").then(literal("ghost")
                .then(literal("on")
                        .executes(context -> on(context.getSource(), DEFAULT_SIZE))
                        .then(argument("size", IntegerArgumentType.integer(1, MAX_SIZE))
                                .executes(context -> on(context.getSource(), IntegerArgumentType.getInteger(context, "size")))))
                .then(literal("off").executes(context -> off(context.getSource())))
                .then(literal("rotate").executes(context -> rotate(context.getSource())))
                .then(literal("light").executes(context -> light(context.getSource())))
                .then(literal("erase").executes(context -> erase(context.getSource()))));
    }

    // ---- Commands ----

    private int on(FabricClientCommandSource source, int newSize) {
        ClientWorld world = source.getWorld();
        BlockPos centre = source.getPlayer().getBlockPos();
        size = newSize;
        int half = size / 2;
        int minX = centre.getX() - half;
        int minY = centre.getY() - half;
        int minZ = centre.getZ() - half;
        GhostVolume volume = capture(world, minX, minY, minZ, size, includeAir);
        int offsetX = Math.max(MIN_OFFSET_X, size + 4);
        replace(new GhostPlacement(volume, minX + offsetX, minY, minZ, TRANSFORMS.get(transformIndex),
                GhostPlacement.DEFAULT_ALPHA, lightMode));
        source.sendFeedback(Text.literal("Ghost preview on: " + size + "³ around you, shown " + offsetX + " blocks east ("
                + volume.blockCount() + " blocks, " + volume.eraseCount() + " erase cells, " + volume.sectionCount()
                + " sections). Also: rotate, light, erase, off."));
        return 1;
    }

    private int off(FabricClientCommandSource source) {
        boolean wasOn = placement != null;
        stop();
        source.sendFeedback(Text.literal(wasOn ? "Ghost preview off." : "Ghost preview was not on."));
        return 1;
    }

    private int rotate(FabricClientCommandSource source) {
        transformIndex = (transformIndex + 1) % TRANSFORMS.size();
        Transform transform = TRANSFORMS.get(transformIndex);
        if (placement != null) {
            placement = placement.withTransform(transform);
        }
        source.sendFeedback(Text.literal("Ghost transform: " + describe(transform)
                + (placement == null ? " (applies at the next 'on')" : "")));
        return 1;
    }

    private int light(FabricClientCommandSource source) {
        lightMode = lightMode == GhostPlacement.LightMode.FLAT ? GhostPlacement.LightMode.WORLD : GhostPlacement.LightMode.FLAT;
        if (placement != null) {
            placement = placement.withLightMode(lightMode);
        }
        source.sendFeedback(Text.literal("Ghost light: " + (lightMode == GhostPlacement.LightMode.FLAT ? "full-bright" : "world light")));
        return 1;
    }

    private int erase(FabricClientCommandSource source) {
        includeAir = !includeAir;
        source.sendFeedback(Text.literal("Ghost erase cells: " + (includeAir ? "air cells are captured as erase outlines" : "off")));
        return placement != null ? on(source, size) : 1;
    }

    // ---- Capture and drawing ----

    /** Copies a cube of the client world into a volume in cube-local coordinates 0..size-1. */
    private static GhostVolume capture(ClientWorld world, int minX, int minY, int minZ, int size, boolean includeAir) {
        BlockBuffer buffer = new BlockBuffer();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int y = 0; y < size; y++) {
            for (int z = 0; z < size; z++) {
                for (int x = 0; x < size; x++) {
                    BlockState state = world.getBlockState(pos.set(minX + x, minY + y, minZ + z));
                    if (includeAir || !state.isAir()) {
                        buffer.set(x, y, z, GhostStates.handle(state));
                    }
                }
            }
        }
        GhostVolume volume = GhostVolume.of(buffer, GhostStates::isAir);
        // Rotate the whole captured cube, not just the part that holds blocks.
        volume.setFrame(new Box(dev.sculptory.core.BlockPos.ORIGIN, new dev.sculptory.core.BlockPos(size - 1, size - 1, size - 1)));
        return volume;
    }

    private void render(WorldRenderContext context) {
        if (placement == null) {
            return;
        }
        String text = renderer.render(context, placement).hudText();
        if (!text.equals(lastHudText)) {
            lastHudText = text;
            if (!text.isEmpty()) {
                MinecraftClient.getInstance().inGameHud.setOverlayMessage(Text.literal(text), false);
            }
        }
    }

    private void replace(GhostPlacement next) {
        if (placement != null && placement.volume() != next.volume()) {
            renderer.release(placement.volume());
        }
        placement = next;
        lastHudText = "";
    }

    private void stop() {
        if (placement != null) {
            renderer.release(placement.volume());
            placement = null;
        }
        lastHudText = "";
    }

    private static String describe(Transform transform) {
        String turn = transform.quarterTurnsCw() * 90 + "° clockwise";
        return transform.mirror() == Mirror.NONE ? turn : "mirrored " + transform.mirror() + ", then " + turn;
    }
}
