package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncLib;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Dev-only registration for the {@link TestEntity}.
 */
public final class ModEntityTypes {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, DataSyncLib.MOD_ID);

    public static final RegistryObject<EntityType<TestEntity>> TEST_ENTITY =
            ENTITY_TYPES.register("test_entity",
                    () -> EntityType.Builder.of(TestEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .build("test_entity"));

    private ModEntityTypes() {
    }
}
