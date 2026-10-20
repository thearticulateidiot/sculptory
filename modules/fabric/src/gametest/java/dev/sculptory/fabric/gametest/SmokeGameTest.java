package dev.sculptory.fabric.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Proves the headless GameTest pipeline runs against a real server world. */
public final class SmokeGameTest implements FabricGameTest {
    @GameTest(template = EMPTY_STRUCTURE)
    public void placesAndReadsBack(GameTestHelper context) {
        BlockPos pos = new BlockPos(1, 2, 1);
        context.setBlock(pos, Blocks.STONE);
        context.assertBlockPresent(Blocks.STONE, pos);
        context.succeed();
    }
}
