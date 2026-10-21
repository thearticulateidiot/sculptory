package dev.sculptory.mc.world;

import java.util.Objects;
import net.minecraft.core.Direction;
import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * The non-public game members the world layer reads, behind one seam. Fabric and NeoForge read them directly
 * ({@link WidenedMcInternals}: the access widener, and the access transformer generated from it); a platform without
 * either (Paper, whose plugins get no access transformers) reads them its own way. The platform installs its
 * implementation before any of the mod's other code runs: the Fabric and NeoForge loaders do it in their
 * {@code install()}.
 */
public interface McInternals {
    /** {@code GrowingPlantBlock.growthDirection} (protected in the game). */
    Direction growthDirection(GrowingPlantBlock block);

    /**
     * A section container's packed indices and palette, from one read of its {@code data} field (the game replaces the
     * whole record when the container resizes, so both come from the same snapshot).
     */
    <T> PaletteData<T> paletteData(PalettedContainer<T> container);

    /** {@code PalettedContainer.Data}'s storage and palette ({@code Data} itself is package-private in the game). */
    record PaletteData<T>(BitStorage storage, Palette<T> palette) {
    }

    /** Installs the platform's implementation; once (installing the same instance again is allowed). */
    static void install(McInternals internals) {
        Holder.install(internals);
    }

    /** The installed implementation. */
    static McInternals get() {
        McInternals internals = Holder.installed;
        if (internals == null) {
            throw new IllegalStateException("no McInternals installed (the platform's loader installs it first)");
        }
        return internals;
    }

    /** The installed instance (an interface cannot hold a mutable static field itself). */
    final class Holder {
        private static volatile McInternals installed;

        private Holder() {
        }

        private static synchronized void install(McInternals internals) {
            Objects.requireNonNull(internals);
            if (installed != null && installed != internals) {
                throw new IllegalStateException("McInternals are already installed");
            }
            installed = internals;
        }
    }
}
