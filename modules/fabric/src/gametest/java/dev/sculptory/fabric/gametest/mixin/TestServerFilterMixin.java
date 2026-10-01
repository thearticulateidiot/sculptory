package dev.sculptory.fabric.gametest.mixin;

import dev.sculptory.fabric.gametest.GameTestFilter;
import java.util.Collection;
import net.minecraft.test.TestFunction;
import net.minecraft.test.TestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Test mods only (sculptory-gametest, sculptory-fidelity): narrows the tests the headless GameTest server runs to
 * {@link GameTestFilter}'s. {@code TestServer.create(Thread, Session, ResourcePackManager, Collection<TestFunction>,
 * BlockPos)} checked with javap against yarn 1.21.1+build.3; Fabric API's gametest module calls it with every
 * registered test.
 */
@Mixin(TestServer.class)
public abstract class TestServerFilterMixin {
    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true)
    private static Collection<TestFunction> sculptory$filterTests(Collection<TestFunction> tests) {
        return GameTestFilter.apply(tests);
    }
}
