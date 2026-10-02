package dev.sculptory.fabric.schem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.buffer.BlockEntityData;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.nbt.BlockEntityNbt;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.state.StateFlags;
import dev.sculptory.fabric.world.FabricStateSpace;
import dev.sculptory.server.schem.SanitizedTile;
import dev.sculptory.server.schem.TileSanitizer;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every vanilla state whose block-entity NBT is operator-only, sanitized as file content: signs and hanging signs
 * become {@link SanitizedTile} text; every other operator block entity loses its NBT. A new operator block entity
 * type in a later version fails the known-types check here until it is reviewed.
 */
class TileSanitizerCoverageTest {
    private static final Set<String> SIGNS = Set.of("minecraft:sign", "minecraft:hanging_sign");
    private static final Set<String> KNOWN = Set.of("minecraft:sign", "minecraft:hanging_sign", "minecraft:command_block",
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:mob_spawner", "minecraft:trial_spawner",
            "minecraft:lectern");
    private static FabricStateSpace states;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        states = FabricStateSpace.build();
    }

    @Test
    void onlySignTextSurvivesFromEveryOperatorBlockEntity() throws Exception {
        TreeSet<String> seen = new TreeSet<>();
        int checked = 0;
        for (int h = 0; h < states.size(); h++) {
            if (!StateFlags.has(states.flags(h), StateFlags.OPERATOR_NBT)) continue;
            BlockState state = states.state(h);
            if (!(state.getBlock() instanceof BlockEntityProvider provider)) continue;
            BlockEntity entity = provider.createBlockEntity(net.minecraft.util.math.BlockPos.ORIGIN, state);
            if (entity == null) continue;
            Identifier id = BlockEntityType.getId(entity.getType());
            String type = id.toString();
            seen.add(type);
            Clipboard.Builder builder = Clipboard.builder(states, new BlockPos(1, 1, 1));
            builder.set(0, 0, 0, h);
            builder.setTile(0, 0, 0, BlockEntityNbt.toNbtBytes(type, NbtCompound.builder()
                    .putString("Command", "op everyone").putString("SpawnData", "x").build()));
            BlockEntityData after = TileSanitizer.sanitize(builder.build()).clipboard().tile(0, 0, 0);
            if (SIGNS.contains(type)) {
                assertTrue(after instanceof SanitizedTile, state + " should keep sanitized text");
                assertNull(BlockEntityNbt.decode(after).get("Command"), state + " kept an unknown field");
            } else {
                assertNull(after, state + " (" + type + ") kept operator NBT");
            }
            checked++;
        }
        assertTrue(checked > 50, "only " + checked + " operator states");
        assertTrue(KNOWN.containsAll(seen), "unreviewed operator block entities: " + seen);
        assertEquals(KNOWN, seen, "operator block entities changed: " + seen);
    }

    /** In vanilla, exactly the sign and hanging sign are sign types (modded ones: see the fidelity GameTests). */
    @Test
    void theStateSpaceFindsExactlyTheVanillaSignTypes() {
        for (String type : SIGNS) assertTrue(states.isSignBlockEntity(type), type);
        for (String type : Set.of("minecraft:command_block", "minecraft:lectern", "minecraft:chest", "minecraft:sign ",
                "minecraft:hanging_sign_x", "")) {
            assertFalse(states.isSignBlockEntity(type), type);
        }
    }
}
