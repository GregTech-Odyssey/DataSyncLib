package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncLib;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for DataSyncLib items.
 */
public final class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, DataSyncLib.MOD_ID);

    public static final RegistryObject<Item> TEST_BLOCK_ITEM = ITEMS.register("test_block",
            () -> new BlockItem(ModBlocks.TEST_BLOCK.get(), new Item.Properties()));

    private ModItems() {
    }
}
