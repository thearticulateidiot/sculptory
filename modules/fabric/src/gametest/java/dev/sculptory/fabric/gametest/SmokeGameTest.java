package dev.sculptory.fabric.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

/** Proves the headless GameTest pipeline runs against a real server world. */
public final class SmokeGameTest implements FabricGameTest {
    @GameTest(templateName = EMPTY_STRUCTURE)
    public void placesAndReadsBack(TestContext context) {
        BlockPos pos = new BlockPos(1, 2, 1);
        context.setBlockState(pos, Blocks.STONE);
        context.expectBlock(Blocks.STONE, pos);
        context.complete();
    }
}
