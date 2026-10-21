package dev.sculptory.mc.gametest.mixin;

import dev.sculptory.mc.gametest.GameTestFilter;
import java.util.Collection;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Test mods only (sculptory-gametest, sculptory-fidelity): narrows the tests the headless GameTest server runs to
 * {@link GameTestFilter}'s. {@code GameTestServer.create(Thread, LevelStorageAccess, PackRepository, Collection<TestFunction>,
 * BlockPos)} checked with javap against Minecraft 1.21.1 (Mojang names); Fabric API's gametest module and NeoForge's
 * server {@code Main} call it with every registered test.
 */
@Mixin(GameTestServer.class)
public abstract class TestServerFilterMixin {
    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true)
    private static Collection<TestFunction> sculptory$filterTests(Collection<TestFunction> tests) {
        return GameTestFilter.apply(tests);
    }
}
