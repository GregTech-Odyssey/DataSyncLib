package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncLib;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Registry for DataSyncLib test block entity types.
 */
public final class ModBlockEntities {

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, DataSyncLib.MOD_ID);

    public static final RegistryObject<BlockEntityType<TestBlockEntity>> TEST_BLOCK_ENTITY =
            BLOCK_ENTITIES.register("test_block_entity",
                    () -> BlockEntityType.Builder.of(TestBlockEntity::new,
                            ModBlocks.TEST_BLOCK.get()).build(null));

    private ModBlockEntities() {
    }
}
