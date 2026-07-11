package com.gto.datasynclib;

import com.gto.datasynclib.field.*;
import com.gto.datasynclib.field.access.*;
import com.gto.datasynclib.field.access.array.*;
import com.gto.datasynclib.field.object.CustomObjCodecField;
import com.gto.datasynclib.field.object.ObjCodecField;
import com.gto.datasynclib.network.DataSyncNetwork;
import com.gto.datasynclib.test.ModBlockEntities;
import com.gto.datasynclib.test.ModBlocks;
import com.gto.datasynclib.test.ModItems;
import com.gto.datasynclib.util.EnumUtil;
import com.gto.datasynclib.util.FluidStackHashStrategy;
import com.gto.datasynclib.util.ItemStackHashStrategy;
import com.gto.datasynclib.util.NbtUtil;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import static com.gto.datasynclib.FieldDefinitionStorage.*;

@Mod(DataSyncLib.MOD_ID)
public final class DataSyncLib {

    public static final String MOD_ID = "datasynclib";

    public static final Logger LOGGER = LoggerFactory.getLogger("Data Sync Lib");


    public DataSyncLib(FMLJavaModLoadingContext context) {
        DataSyncCodec.init();
        DataSyncNetwork.init();
        registerFactory(boolean.class, BooleanField::new);
        registerFactory(byte.class, ByteField::new);
        registerFactory(char.class, CharField::new);
        registerFactory(double.class, DoubleField::new);
        registerFactory(float.class, FloatField::new);
        registerFactory(int.class, IntField::new);
        registerFactory(long.class, LongField::new);
        registerFactory(short.class, ShortField::new);
        registerCustomFactory(DataSyncCodec::contains, k -> ObjCodecField::new, 100);

        registerCustomGenericFactory(DataSyncCodec::contains, (t, gt) -> {
            var codec = DataSyncCodec.get(t, gt);
            if (codec == null) throw new IllegalArgumentException("No codec for " + t + " " + Arrays.toString(gt));
            return d -> new CustomObjCodecField<>(d, codec);
        }, 100);

        registerAccessFactory(boolean[].class, BooleanArrayAccess::new);
        registerAccessFactory(byte[].class, ByteArrayAccess::new);
        registerAccessFactory(int[].class, IntArrayAccess::new);
        registerAccessFactory(long[].class, LongArrayAccess::new);

        registerAccessInterfaceFactory(IFieldDataHolder.class, k -> FieldDataHolderAccess::new, 1000);
        registerAccessInterfaceFactory(IDataSerializable.class, k -> SerializableAccess::new, 1000);

        registerAccessInterfaceFactory(IntCollection.class, k -> IntCollectionAccess::new, 1000);
        registerAccessInterfaceFactory(LongCollection.class, k -> LongCollectionAccess::new, 1000);
        registerAccessInterfaceFactory(Collection.class, k -> CollectionAccess::new, 100);

        registerAccessInterfaceFactory(Reference2LongMap.class, k -> Reference2LongMapAccess::new, 1000);
        registerAccessInterfaceFactory(Object2LongMap.class, k -> Object2LongMapAccess::new, 1000);
        registerAccessInterfaceFactory(Reference2IntMap.class, k -> Reference2IntMapAccess::new, 1000);
        registerAccessInterfaceFactory(Object2IntMap.class, k -> Object2IntMapAccess::new, 1000);
        registerAccessInterfaceFactory(Map.class, k -> MapAccess::new, 100);

        registerAccessCustomFactory(c -> c.isArray() && !c.componentType().isPrimitive() && IFieldDataHolder.class.isAssignableFrom(c.componentType()), c -> FieldDataHolderArrayAccess::new, 2000);
        registerAccessCustomFactory(c -> c.isArray() && !c.componentType().isPrimitive() && IDataSerializable.class.isAssignableFrom(c.componentType()), c -> SerializableArrayAccess::new, 2000);

        registerAccessCustomFactory(c -> c.isArray() && !c.componentType().isPrimitive(), c -> {
            var codec = DataSyncCodec.get(c.componentType());
            if (codec == null) throw new IllegalArgumentException("No codec for " + c);
            return d -> new ArrayAccess<>(d, codec);
        }, 200);

        registerStrategy(ItemStack.class, ItemStackHashStrategy.ALL);
        registerStrategy(FluidStack.class, FluidStackHashStrategy.ALL);

        EnumUtil.addFixedEnum(LogicalSide.class);
        EnumUtil.addFixedEnum(Direction.class);
        NbtUtil.init();

        if (FMLLoader.isProduction()) return;
        // Register test blocks and block entities
        var modEventBus = context.getModEventBus();
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
    }
}
