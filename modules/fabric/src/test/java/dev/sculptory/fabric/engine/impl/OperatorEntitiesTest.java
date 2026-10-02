package dev.sculptory.fabric.engine.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.sculptory.core.BlockPos;
import dev.sculptory.core.clipboard.Clipboard;
import dev.sculptory.core.entity.EntitySnapshot;
import dev.sculptory.core.nbt.NbtCompound;
import dev.sculptory.core.nbt.NbtIo;
import dev.sculptory.core.nbt.NbtList;
import dev.sculptory.core.nbt.NbtTag;
import dev.sculptory.core.testing.FakeStateSpace;
import dev.sculptory.fabric.world.EntityTypeRules;
import dev.sculptory.server.engine.impl.EngineEditService;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * A paste whose clipboard carries untrusted operator-only entity data (at the top or riding something) needs the
 * operator-NBT right for as long as it runs; and before the server has scanned its entity types, every type counts as
 * operator-only and never placed (fail closed).
 */
class OperatorEntitiesTest {
    private static final Predicate<String> OPERATOR = Set.of("minecraft:command_block_minecart")::contains;
    private final FakeStateSpace states = new FakeStateSpace();

    private Clipboard with(String type, NbtCompound data, boolean trusted) {
        return Clipboard.builder(states, new BlockPos(2, 2, 2)).build().withEntities(List.of(
                new EntitySnapshot(type, 0.5, 0, 0.5, 0f, 0f, null, NbtIo.toBytes(data), trusted)));
    }

    @Test
    void passengersCount() {
        NbtCompound cartRider = NbtCompound.builder().put("Passengers", NbtList.of(NbtTag.COMPOUND, List.of(
                NbtCompound.builder().putString("id", "command_block_minecart").putString("Command", "op @a").build())))
                .build();
        assertTrue(EngineEditService.hasUntrustedOperatorEntities(with("minecraft:boat", cartRider, false), OPERATOR),
                "a minecart riding a boat, its id without a namespace");
        assertFalse(EngineEditService.hasUntrustedOperatorEntities(with("minecraft:boat", cartRider, true), OPERATOR),
                "server-captured data is trusted");
        assertTrue(EngineEditService.hasUntrustedOperatorEntities(
                with("minecraft:command_block_minecart", NbtCompound.EMPTY, false), OPERATOR));
        assertFalse(EngineEditService.hasUntrustedOperatorEntities(with("minecraft:boat", NbtCompound.EMPTY, false),
                OPERATOR));
    }

    @Test
    void beforeTheScanEveryTypeIsRefused() {
        // No server has scanned in this JVM: the rules fail closed.
        EntityTypeRules rules = EntityTypeRules.current();
        assertFalse(rules.ready());
        assertTrue(rules.operator("minecraft:armor_stand"));
        assertTrue(rules.never("minecraft:armor_stand"));
        assertFalse(rules.hanging("minecraft:item_frame"));
    }
}
