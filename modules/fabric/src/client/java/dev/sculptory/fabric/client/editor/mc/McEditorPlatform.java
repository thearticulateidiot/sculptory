package dev.sculptory.fabric.client.editor.mc;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.BlockPos;
import dev.sculptory.fabric.client.editor.CursorPick;
import dev.sculptory.fabric.client.editor.EditorPlatform;
import dev.sculptory.fabric.client.editor.hud.HelpSheet;
import dev.sculptory.fabric.client.editor.input.CameraLook;
import dev.sculptory.fabric.client.editor.input.EditorKeymap;
import dev.sculptory.fabric.client.editor.input.KeyChord;
import dev.sculptory.fabric.client.editor.input.MovementForwarder;
import dev.sculptory.fabric.client.editor.render.OverlayRenderer;
import dev.sculptory.fabric.client.editor.tool.RayOverlay;
import dev.sculptory.fabric.client.editor.tool.RaycastMode;
import dev.sculptory.fabric.client.editor.tool.WorldCursor;
import dev.sculptory.fabric.client.editor.world.CameraSnapshot;
import dev.sculptory.fabric.client.editor.world.CursorRay;
import dev.sculptory.fabric.client.editor.world.Ray;
import dev.sculptory.fabric.client.editor.world.ScreenProjector;
import dev.sculptory.fabric.client.editor.world.WorldRaycaster;
import dev.sculptory.fabric.client.mixin.MouseAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * The editor's Minecraft side: cursor picking through {@link CursorRay} and {@link WorldRaycaster},
 * block lookups for the eyedropper, fly speed, camera facing, the player's vanilla keys, mouse look
 * and movement-key forwarding. Every call happens on the client thread.
 */
public final class McEditorPlatform implements EditorPlatform {
    private final MinecraftClient client;
    private final OverlayRenderer overlay;
    private final KeyBinding toggleKey;
    private final McBlockCatalog blocks;
    private final WorldRaycaster raycaster = new WorldRaycaster();
    private Float savedFlySpeed;
    /** "Aim at water and lava": lives as long as the editor (the game session); off at every game start. */
    private boolean aimAtFluids;

    public McEditorPlatform(MinecraftClient client, OverlayRenderer overlay, KeyBinding toggleKey, McBlockCatalog blocks) {
        this.client = Objects.requireNonNull(client);
        this.overlay = Objects.requireNonNull(overlay);
        this.toggleKey = Objects.requireNonNull(toggleKey);
        this.blocks = Objects.requireNonNull(blocks);
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
                : CursorRay.fromScaled(camera.get(), x, y, window.getScaledWidth(), window.getScaledHeight());
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
        if (client.world == null) {
            return Optional.empty();
        }
        net.minecraft.util.math.BlockPos at = new net.minecraft.util.math.BlockPos(pos.x(), pos.y(), pos.z());
        return McBlockCatalog.describe(client.world.getBlockState(at));
    }

    @Override
    public String blockName(BlockDescriptor block) {
        return blocks.name(block);
    }

    // ---- Player ----

    @Override
    public void applyFlySpeed(double multiplier) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        PlayerAbilities abilities = player.getAbilities();
        if (savedFlySpeed == null) {
            savedFlySpeed = abilities.getFlySpeed();
        }
        float speed = (float) (savedFlySpeed * multiplier);
        if (abilities.getFlySpeed() != speed) {
            abilities.setFlySpeed(speed);
        }
    }

    @Override
    public void restoreFlySpeed() {
        ClientPlayerEntity player = client.player;
        if (player != null && savedFlySpeed != null) {
            player.getAbilities().setFlySpeed(savedFlySpeed);
        }
        savedFlySpeed = null;
    }

    @Override
    public float cameraYaw() {
        return client.player != null ? client.player.getYaw() : 0f;
    }

    @Override
    public Optional<ScreenProjector> projector() {
        Window window = client.getWindow();
        return overlay.camera().map(camera -> camera.projector(window.getScaledWidth(), window.getScaledHeight()));
    }

    @Override
    public Optional<double[]> eye() {
        return overlay.camera().map(camera -> new double[] {camera.cameraX(), camera.cameraY(), camera.cameraZ()});
    }

    // ---- Keys ----

    @Override
    public SystemKey systemKey(int key, int scanCode) {
        GameOptions options = client.options;
        if (toggleKey.matchesKey(key, scanCode)) {
            return SystemKey.TOGGLE_EDITOR;
        }
        if (options.chatKey.matchesKey(key, scanCode)) {
            return SystemKey.CHAT;
        }
        if (options.commandKey.matchesKey(key, scanCode)) {
            return SystemKey.COMMAND;
        }
        if (options.inventoryKey.matchesKey(key, scanCode)) {
            return SystemKey.INVENTORY;
        }
        return SystemKey.NONE;
    }

    /** The tab list's names, sorted; empty when not connected. */
    @Override
    public List<String> onlinePlayerNames() {
        ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (handler == null) return List.of();
        List<String> names = new ArrayList<>();
        for (PlayerListEntry entry : handler.getPlayerList()) {
            String name = entry.getProfile().getName();
            if (name != null && !name.isEmpty()) names.add(name);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    @Override
    public List<String> paintingVariants() {
        if (client.world == null) return List.of();
        List<String> ids = new ArrayList<>();
        for (Identifier id : client.world.getRegistryManager().get(net.minecraft.registry.RegistryKeys.PAINTING_VARIANT)
                .getIds()) {
            ids.add(id.toString());
        }
        ids.sort(null);
        return ids;
    }

    @Override
    public String itemOf(BlockDescriptor block) {
        Identifier id = Identifier.tryParse(block.block().value());
        if (id == null || !Registries.BLOCK.containsId(id)) return "";
        net.minecraft.item.Item item = Registries.BLOCK.get(id).asItem();
        return item == net.minecraft.item.Items.AIR ? "" : Registries.ITEM.getId(item).toString();
    }

    private List<KeyBinding> movementBindings() {
        GameOptions options = client.options;
        return List.of(options.forwardKey, options.backKey, options.leftKey, options.rightKey, options.jumpKey,
                options.sneakKey, options.sprintKey);
    }

    /** The player's movement, chat, command, inventory and editor-toggle keys, which editor chords may not take. */
    public List<EditorKeymap.ReservedKey> reservedKeys() {
        List<KeyBinding> bindings = new ArrayList<>(movementBindings());
        GameOptions options = client.options;
        bindings.add(options.chatKey);
        bindings.add(options.commandKey);
        bindings.add(options.inventoryKey);
        bindings.add(toggleKey);
        List<EditorKeymap.ReservedKey> reserved = new ArrayList<>();
        for (KeyBinding binding : bindings) {
            InputUtil.Key bound = KeyBindingHelper.getBoundKeyOf(binding);
            String name = I18n.translate(binding.getTranslationKey());
            if (bound.getCategory() == InputUtil.Type.KEYSYM && bound.getCode() > 0) {
                reserved.add(new EditorKeymap.ReservedKey(KeyChord.Input.KEY, bound.getCode(), name));
            } else if (bound.getCategory() == InputUtil.Type.MOUSE) {
                reserved.add(new EditorKeymap.ReservedKey(KeyChord.Input.MOUSE, bound.getCode(), name));
            }
        }
        return reserved;
    }

    /** The player's vanilla keys as the help sheet shows them. */
    public HelpSheet.VanillaKeys vanillaKeys() {
        GameOptions options = client.options;
        String move = String.join(" ", keyName(options.forwardKey), keyName(options.leftKey),
                keyName(options.backKey), keyName(options.rightKey));
        return new HelpSheet.VanillaKeys(move, keyName(options.jumpKey), keyName(options.sneakKey),
                keyName(toggleKey), keyName(options.chatKey), keyName(options.commandKey), keyName(options.inventoryKey));
    }

    private static String keyName(KeyBinding binding) {
        return binding.getBoundKeyLocalizedText().getString();
    }

    /** An item icon by item id ("minecraft:wooden_axe"), or empty for an unknown id. */
    public static ItemStack itemIcon(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(Registries.ITEM.get(id));
    }

    // ---- Look and movement ----

    /** Right-button look on the real mouse and player. */
    public CameraLook.Backend lookBackend() {
        return new CameraLook.Backend() {
            @Override
            public double mouseX() {
                return client.mouse.getX();
            }

            @Override
            public double mouseY() {
                return client.mouse.getY();
            }

            @Override
            public void captureCursor(double x, double y) {
                InputUtil.setCursorParameters(client.getWindow().getHandle(), InputUtil.GLFW_CURSOR_DISABLED, x, y);
            }

            @Override
            public void releaseCursor(double x, double y) {
                InputUtil.setCursorParameters(client.getWindow().getHandle(), InputUtil.GLFW_CURSOR_NORMAL, x, y);
                MouseAccessor mouse = (MouseAccessor) client.mouse;
                mouse.sculptory$setX(x);
                mouse.sculptory$setY(y);
            }

            @Override
            public void turn(double yawDelta, double pitchDelta) {
                if (client.player != null) {
                    client.player.changeLookDirection(yawDelta, pitchDelta);
                }
            }

            @Override
            public double sensitivity() {
                return client.options.getMouseSensitivity().getValue();
            }

            @Override
            public boolean invertY() {
                return client.options.getInvertYMouse().getValue();
            }
        };
    }

    /** The player's movement bindings, pressed through {@code KeyBinding.setKeyPressed}. */
    public MovementForwarder.Keys movementKeys() {
        return new MovementForwarder.Keys() {
            @Override
            public boolean matches(int key, int scanCode) {
                for (KeyBinding binding : movementBindings()) {
                    if (binding.matchesKey(key, scanCode)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void press(int key, int scanCode) {
                KeyBinding.setKeyPressed(InputUtil.fromKeyCode(key, scanCode), true);
            }
        };
    }
}
