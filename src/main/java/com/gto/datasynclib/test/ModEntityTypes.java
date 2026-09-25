package com.gto.datasynclib.test;

import com.gto.datasynclib.DataSyncLib;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Dev-only registration for the {@link TestEntity}.
 */
public final class ModEntityTypes {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, DataSyncLib.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<TestEntity>> TEST_ENTITY =
            ENTITY_TYPES.register("test_entity",
                    () -> EntityType.Builder.of(TestEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .build("test_entity"));

    private ModEntityTypes() {
    }
}
