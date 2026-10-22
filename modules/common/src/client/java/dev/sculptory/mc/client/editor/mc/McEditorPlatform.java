package dev.sculptory.mc.client.editor.mc;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.mc.client.editor.CursorPick;
import dev.sculptory.mc.client.editor.EditorPlatform;
import dev.sculptory.mc.client.editor.Flight;
import dev.sculptory.mc.client.editor.hud.HelpSheet;
import dev.sculptory.mc.client.editor.input.CameraLook;
import dev.sculptory.mc.client.editor.input.EditorKeymap;
import dev.sculptory.mc.client.editor.input.KeyChord;
import dev.sculptory.mc.client.editor.input.MovementForwarder;
import dev.sculptory.mc.client.editor.render.OverlayRenderer;
import dev.sculptory.mc.client.editor.tool.RayOverlay;
import dev.sculptory.mc.client.editor.tool.RaycastMode;
import dev.sculptory.mc.client.editor.tool.WorldCursor;
import dev.sculptory.mc.client.editor.world.CameraSnapshot;
import dev.sculptory.mc.client.editor.world.CursorRay;
import dev.sculptory.mc.client.editor.world.Ray;
import dev.sculptory.mc.client.editor.world.ScreenProjector;
import dev.sculptory.mc.client.editor.world.WorldRaycaster;
import dev.sculptory.mc.client.mixin.MouseAccessor;
import dev.sculptory.mc.loader.ClientLoaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * The editor's Minecraft side: cursor picking through {@link CursorRay} and {@link WorldRaycaster},
 * block lookups for the eyedropper, fly speed, camera facing, the player's vanilla keys, mouse look
 * and movement-key forwarding. Every call happens on the client thread.
 */
public final class McEditorPlatform implements EditorPlatform {
    private final Minecraft client;
    private final OverlayRenderer overlay;
    private final KeyMapping toggleKey;
    private final McBlockCatalog blocks;
    private final WorldRaycaster raycaster = new WorldRaycaster();
    /** The fly speed, shared with creative flight outside the editor and {@code /flyspeed}. */
    private final Flight flight;
    /** "Aim at water and lava": lives as long as the editor (the game session); off at every game start. */
    private boolean aimAtFluids;

    public McEditorPlatform(Minecraft client, OverlayRenderer overlay, KeyMapping toggleKey, McBlockCatalog blocks,
            Flight flight) {
        this.client = Objects.requireNonNull(client);
        this.overlay = Objects.requireNonNull(overlay);
        this.toggleKey = Objects.requireNonNull(toggleKey);
        this.blocks = Objects.requireNonNull(blocks);
        this.flight = Objects.requireNonNull(flight);
    }

    // ---- Picking ----

    @Override
    public CursorPick pick(double x, double y, RaycastMode mode, boolean crosshair, RayOverlay rayOverlay) {
        Optional<CameraSnapshot> camera = overlay.camera();
        if (camera.isEmpty()) {
            return CursorPick.NONE;
        }
        Window window = client.getWindow();
        Optional<Ray> ray = crosshair
                ? CursorRay.center(camera.get())
                : CursorRay.fromScaled(camera.get(), x, y, window.getGuiScaledWidth(), window.getGuiScaledHeight());
        if (ray.isEmpty()) {
            return CursorPick.NONE;
        }
        if (mode == RaycastMode.NONE) {
            Ray r = ray.get();
            return new CursorPick(ray, WorldCursor.miss(r.pointX(64), r.pointY(64), r.pointZ(64)));
        }
        WorldRaycaster.Mode castMode = mode == RaycastMode.TERRAIN ? WorldRaycaster.Mode.TERRAIN : WorldRaycaster.Mode.BLOCK;
        WorldRaycaster.Hit hit = raycaster.pick(camera.get(), ray.get(), castMode,
                WorldRaycaster.Fluids.of(aimAtFluids || mode == RaycastMode.FLUIDS), rayOverlay);
        WorldCursor cursor = hit.kind() == WorldRaycaster.Hit.Kind.BLOCK
                ? new WorldCursor(
                        new BlockPos(hit.blockPos().getX(), hit.blockPos().getY(), hit.blockPos().getZ()),
                        WorldCursor.Face.valueOf(hit.face().name()),
                        hit.pos().x, hit.pos().y, hit.pos().z, false)
                : WorldCursor.miss(hit.pos().x, hit.pos().y, hit.pos().z);
        return new CursorPick(ray, cursor);
    }

    @Override
    public boolean aimsAtFluids() {
        return aimAtFluids;
    }

    @Override
    public void setAimAtFluids(boolean aim) {
        aimAtFluids = aim;
    }

    @Override
    public Optional<BlockDescriptor> blockAt(BlockPos pos) {
        if (client.level == null) {
            return Optional.empty();
        }
        net.minecraft.core.BlockPos at = new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z());
        return McBlockCatalog.describe(client.level.getBlockState(at));
    }

    @Override
    public String blockName(BlockDescriptor block) {
        return blocks.name(block);
    }

    // ---- Player ----

    @Override
    public void applyFlySpeed(double multiplier) {
        flight.editorApplies(multiplier);
    }

    @Override
    public void restoreFlySpeed() {
        flight.editorReleased();
    }

    @Override
    public float cameraYaw() {
        return client.player != null ? client.player.getYRot() : 0f;
    }

    @Override
    public Optional<ScreenProjector> projector() {
        Window window = client.getWindow();
        return overlay.camera().map(camera -> camera.projector(window.getGuiScaledWidth(), window.getGuiScaledHeight()));
    }

    @Override
    public Optional<double[]> eye() {
        return overlay.camera().map(camera -> new double[] {camera.cameraX(), camera.cameraY(), camera.cameraZ()});
    }

    // ---- Keys ----

    @Override
    public SystemKey systemKey(int key, int scanCode) {
        Options options = client.options;
        if (toggleKey.matches(key, scanCode)) {
            return SystemKey.TOGGLE_EDITOR;
        }
        if (options.keyChat.matches(key, scanCode)) {
            return SystemKey.CHAT;
        }
        if (options.keyCommand.matches(key, scanCode)) {
            return SystemKey.COMMAND;
        }
        if (options.keyInventory.matches(key, scanCode)) {
            return SystemKey.INVENTORY;
        }
        return SystemKey.NONE;
    }

    /** The tab list's names, sorted; empty when not connected. */
    @Override
    public List<String> onlinePlayerNames() {
        ClientPacketListener handler = client.getConnection();
        if (handler == null) return List.of();
        List<String> names = new ArrayList<>();
        for (PlayerInfo entry : handler.getOnlinePlayers()) {
            String name = entry.getProfile().getName();
            if (name != null && !name.isEmpty()) names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    @Override
    public List<String> paintingVariants() {
        if (client.level == null) return List.of();
        List<String> ids = new ArrayList<>();
        for (ResourceLocation id : client.level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.PAINTING_VARIANT)
                .keySet()) {
            ids.add(id.toString());
        }
        ids.sort(null);
        return ids;
    }

    @Override
    public String itemOf(BlockDescriptor block) {
        ResourceLocation id = ResourceLocation.tryParse(block.block().value());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return "";
        net.minecraft.world.item.Item item = BuiltInRegistries.BLOCK.get(id).asItem();
        return item == net.minecraft.world.item.Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(item).toString();
    }

    private List<KeyMapping> movementBindings() {
        return movementBindings(client.options);
    }

    /**
     * The player's movement bindings, which the editor screen lets through: forward, back, left, right, jump, sneak and
     * sprint. A loader that switches bindings off while a screen is open has to keep these on under the editor screen.
     */
    public static List<KeyMapping> movementBindings(Options options) {
        return List.of(options.keyUp, options.keyDown, options.keyLeft, options.keyRight, options.keyJump,
                options.keyShift, options.keySprint);
    }

    /** The player's movement, chat, command, inventory and editor-toggle keys, which editor chords may not take. */
    public List<EditorKeymap.ReservedKey> reservedKeys() {
        List<KeyMapping> bindings = new ArrayList<>(movementBindings());
        Options options = client.options;
        bindings.add(options.keyChat);
        bindings.add(options.keyCommand);
        bindings.add(options.keyInventory);
        bindings.add(toggleKey);
        List<EditorKeymap.ReservedKey> reserved = new ArrayList<>();
        for (KeyMapping binding : bindings) {
            InputConstants.Key bound = ClientLoaders.get().boundKey(binding);
            String name = I18n.get(binding.getName());
            // Only the inventory key may be shared (the insert-point key, E while a path point is selected).
            boolean shareable = binding == options.keyInventory;
            if (bound.getType() == InputConstants.Type.KEYSYM && bound.getValue() > 0) {
                reserved.add(new EditorKeymap.ReservedKey(KeyChord.Input.KEY, bound.getValue(), name, shareable));
            } else if (bound.getType() == InputConstants.Type.MOUSE) {
                reserved.add(new EditorKeymap.ReservedKey(KeyChord.Input.MOUSE, bound.getValue(), name, shareable));
            }
        }
        return reserved;
    }

    /** The player's vanilla keys as the help sheet shows them. */
    public HelpSheet.VanillaKeys vanillaKeys() {
        Options options = client.options;
        String move = String.join(" ", keyName(options.keyUp), keyName(options.keyLeft),
                keyName(options.keyDown), keyName(options.keyRight));
        return new HelpSheet.VanillaKeys(move, keyName(options.keyJump), keyName(options.keyShift),
                keyName(toggleKey), keyName(options.keyChat), keyName(options.keyCommand), keyName(options.keyInventory));
    }

    private static String keyName(KeyMapping binding) {
        return binding.getTranslatedKeyMessage().getString();
    }

    /** An item icon by item id ("minecraft:wooden_axe"), or empty for an unknown id. */
    public static ItemStack itemIcon(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.get(id));
    }

    // ---- Look and movement ----

    /** Right-button look on the real mouse and player. */
    public CameraLook.Backend lookBackend() {
        return new CameraLook.Backend() {
            @Override
            public double mouseX() {
                return client.mouseHandler.xpos();
            }

            @Override
            public double mouseY() {
                return client.mouseHandler.ypos();
            }

            @Override
            public void captureCursor(double x, double y) {
                InputConstants.grabOrReleaseMouse(client.getWindow().getWindow(), InputConstants.CURSOR_DISABLED, x, y);
            }

            @Override
            public void releaseCursor(double x, double y) {
                InputConstants.grabOrReleaseMouse(client.getWindow().getWindow(), InputConstants.CURSOR_NORMAL, x, y);
                MouseAccessor mouse = (MouseAccessor) client.mouseHandler;
                mouse.sculptory$setX(x);
                mouse.sculptory$setY(y);
            }

            @Override
            public void turn(double yawDelta, double pitchDelta) {
                if (client.player != null) {
                    client.player.turn(yawDelta, pitchDelta);
                }
            }

            @Override
            public double sensitivity() {
                return client.options.sensitivity().get();
            }

            @Override
            public boolean invertY() {
                return client.options.invertYMouse().get();
            }
        };
    }

    /** The player's movement bindings, pressed through {@code KeyBinding.setKeyPressed}. */
    public MovementForwarder.Keys movementKeys() {
        return new MovementForwarder.Keys() {
            @Override
            public boolean matches(int key, int scanCode) {
                for (KeyMapping binding : movementBindings()) {
                    if (binding.matches(key, scanCode)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void press(int key, int scanCode) {
                KeyMapping.set(InputConstants.getKey(key, scanCode), true);
            }
        };
    }
}
