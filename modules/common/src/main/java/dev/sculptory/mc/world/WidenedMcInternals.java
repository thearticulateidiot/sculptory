package dev.sculptory.mc.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * {@link McInternals} through the access widener (sculptory.accesswidener; NeoForge applies the access transformer
 * generated from it): plain field reads. The Fabric and NeoForge loaders install it.
 */
public final class WidenedMcInternals implements McInternals {
    public static final WidenedMcInternals INSTANCE = new WidenedMcInternals();

    private WidenedMcInternals() {
    }

    @Override
    public Direction growthDirection(GrowingPlantBlock block) {
        return block.growthDirection;
    }

    @Override
    public <T> PaletteData<T> paletteData(PalettedContainer<T> container) {
        PalettedContainer.Data<T> data = container.data;
        return new PaletteData<>(data.storage(), data.palette());
    }
}
