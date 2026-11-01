package dev.sculptory.mc.client.builder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Which held items builder mode places on a right-click: block items, but not those placed on water (lily pads,
 * frogspawn), and no other item; the rest go vanilla's way, unrecorded.
 */
class PlacesByClickTest {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void blockItemsYesWaterPlacedAndOtherItemsNo() {
        assertTrue(BuilderClient.placesByClick(new ItemStack(Items.STONE)), "a block item");
        assertTrue(BuilderClient.placesByClick(new ItemStack(Items.OAK_DOOR)), "a double block item");
        assertFalse(BuilderClient.placesByClick(new ItemStack(Items.LILY_PAD)), "placed on water by its own use");
        assertFalse(BuilderClient.placesByClick(new ItemStack(Items.FROGSPAWN)), "placed on water by its own use");
        assertFalse(BuilderClient.placesByClick(new ItemStack(Items.WATER_BUCKET)), "a bucket");
        assertFalse(BuilderClient.placesByClick(new ItemStack(Items.DEBUG_STICK)), "the debug stick");
        assertFalse(BuilderClient.placesByClick(new ItemStack(Items.ITEM_FRAME)), "an entity item");
        assertFalse(BuilderClient.placesByClick(ItemStack.EMPTY), "an empty hand");
    }
}
