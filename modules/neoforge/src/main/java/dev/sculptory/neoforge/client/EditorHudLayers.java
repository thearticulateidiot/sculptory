package dev.sculptory.neoforge.client;

import dev.sculptory.mc.client.editor.EditorVisuals;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Hides the hotbar and the status bars while the editor is open, as {@code InGameHudMixin} (modules/common) does on
 * Fabric by cancelling {@code Gui.renderHotbarAndDecorations}. NeoForge 21.1 split that method into separate HUD layers
 * and no longer calls it (it is kept, deprecated, and unused), so the mixin applies but has no effect here; the same
 * layers are cancelled by name instead. The crosshair is still hidden by the mixin ({@code renderCrosshair} is its own
 * layer and still called).
 */
final class EditorHudLayers {
    /** What vanilla's {@code renderHotbarAndDecorations} drew (NeoForge's deprecated copy of it lists the same). */
    private static final Set<ResourceLocation> HIDDEN_WHILE_EDITING = Set.of(VanillaGuiLayers.HOTBAR,
            VanillaGuiLayers.JUMP_METER, VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.PLAYER_HEALTH,
            VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.AIR_LEVEL,
            VanillaGuiLayers.VEHICLE_HEALTH, VanillaGuiLayers.SELECTED_ITEM_NAME, VanillaGuiLayers.SPECTATOR_TOOLTIP);

    private EditorHudLayers() {
    }

    static void install() {
        NeoForge.EVENT_BUS.addListener((RenderGuiLayerEvent.Pre event) -> {
            if (EditorVisuals.editing() && HIDDEN_WHILE_EDITING.contains(event.getName())) event.setCanceled(true);
        });
    }
}
