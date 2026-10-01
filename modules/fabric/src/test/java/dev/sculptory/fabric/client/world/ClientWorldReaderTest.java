package dev.sculptory.fabric.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockDescriptor;
import dev.sculptory.core.NamespacedId;
import dev.sculptory.core.buffer.SectionBuffer;
import dev.sculptory.core.state.StateSpace;
import dev.sculptory.core.transform.Mirror;
import java.util.List;
import java.util.Random;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.world.chunk.PalettedContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The section copy of {@link ClientWorldReader} over real vanilla palettes (the rest needs a client world). */
class ClientWorldReaderTest {
    private static StateSpace space;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        space = new RawIdSpace(Block.STATE_IDS.size());
    }

    private static PalettedContainer<BlockState> container() {
        return new PalettedContainer<>(Block.STATE_IDS, Blocks.AIR.getDefaultState(), PalettedContainer.PaletteProvider.BLOCK_STATE);
    }

    private static SectionBuffer copy(PalettedContainer<BlockState> container, StateSpace states) {
        SectionBuffer into = new SectionBuffer();
        ClientWorldReader.copyStates(container, states, into);
        assertTrue(into.isDense(), "every cell is set");
        return into;
    }

    private static void assertMatches(PalettedContainer<BlockState> container, SectionBuffer copied) {
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(Block.getRawIdFromState(container.get(x, y, z)), copied.get(SectionBuffer.index(x, y, z)),
                            "cell " + x + "," + y + "," + z);
                }
            }
        }
    }

    @Test
    void aSingleStateSectionIsCopiedWhole() {
        PalettedContainer<BlockState> air = container();
        SectionBuffer copied = copy(air, space);
        assertEquals(1, copied.paletteSize());
        assertMatches(air, copied);

        // An all-cave-air section counts as "empty" in vanilla, but it is not plain air.
        PalettedContainer<BlockState> caveAir = new PalettedContainer<>(Block.STATE_IDS, Blocks.CAVE_AIR.getDefaultState(),
                PalettedContainer.PaletteProvider.BLOCK_STATE);
        assertEquals(Block.getRawIdFromState(Blocks.CAVE_AIR.getDefaultState()), copy(caveAir, space).get(0));
    }

    @Test
    void mixedSectionsAreCopiedCellByCell() {
        List<BlockState> blocks = List.of(Blocks.STONE.getDefaultState(), Blocks.DIRT.getDefaultState(),
                Blocks.GRASS_BLOCK.getDefaultState(), Blocks.WATER.getDefaultState(), Blocks.OAK_STAIRS.getDefaultState(),
                Blocks.CHEST.getDefaultState());
        PalettedContainer<BlockState> mixed = container();
        Random random = new Random(7);
        for (int i = 0; i < 3_000; i++) {
            mixed.set(random.nextInt(16), random.nextInt(16), random.nextInt(16), blocks.get(random.nextInt(blocks.size())));
        }
        assertMatches(mixed, copy(mixed, space));
    }

    @Test
    void stalePaletteEntriesDoNotConfuseTheUniformCheck() {
        PalettedContainer<BlockState> container = container();
        container.set(3, 4, 5, Blocks.DIRT.getDefaultState());
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) container.set(x, y, z, Blocks.STONE.getDefaultState());
            }
        }
        SectionBuffer copied = copy(container, space);
        assertMatches(container, copied);
        assertEquals(Block.getRawIdFromState(Blocks.STONE.getDefaultState()), copied.get(SectionBuffer.index(3, 4, 5)));
    }

    @Test
    void statesOutsideTheSpaceReadAsAir() {
        int stone = Block.getRawIdFromState(Blocks.STONE.getDefaultState());
        StateSpace small = new RawIdSpace(stone); // stone and everything after it are outside
        PalettedContainer<BlockState> container = container();
        container.set(0, 0, 0, Blocks.STONE.getDefaultState());
        SectionBuffer copied = copy(container, small);
        assertEquals(small.air(), copied.get(SectionBuffer.index(0, 0, 0)));
    }

    @Test
    void handlesAreRawStateIds() {
        BlockState stairs = Blocks.OAK_STAIRS.getDefaultState();
        assertSame(stairs, ClientWorldReader.blockState(ClientWorldReader.handle(stairs)));
        assertEquals(Block.getRawIdFromState(stairs), ClientWorldReader.handle(stairs));
    }

    /** Raw-id handles, like the client's FabricStateSpace; only size and air are needed here. */
    private record RawIdSpace(int size) implements StateSpace {
        @Override
        public int air() {
            return Block.getRawIdFromState(Blocks.AIR.getDefaultState());
        }

        @Override
        public int flags(int h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String format(int h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int parse(String spec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BlockDescriptor describe(int h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int resolve(BlockDescriptor d) {
            throw new UnsupportedOperationException();
        }

        @Override
        public NamespacedId blockId(int h) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean inTag(int h, NamespacedId tag) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int rotate(int h, int clockwiseQuarterTurns) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int mirror(int h, Mirror m) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int withWaterlogged(int h, boolean on) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int fluidSource(int h) {
            throw new UnsupportedOperationException();
        }
    }
}
