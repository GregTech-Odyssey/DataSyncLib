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

/**
 * Main entry point for the DataSyncLib Forge mod.
 *
 * <p>During construction, this class initializes all subsystems in order:
 * <ol>
 *   <li><strong>Codec registry</strong> — {@link DataSyncCodec#init()} triggers static
 *       initialization of all pre-registered codecs (primitives, arrays, Minecraft types)</li>
 *   <li><strong>Network channel</strong> — {@link DataSyncNetwork#init()} registers
 *       the Forge SimpleChannel and message handlers</li>
 *   <li><strong>Field factories</strong> — registers {@link DataField} factories for
 *       primitive types (exact match) and codec-backed object types (predicate match)</li>
 *   <li><strong>Access factories</strong> — registers factories for container types
 *       (collections, maps, arrays) with priority-based predicate matching.
 *       More specific types (e.g., {@code IntCollection}) get higher priority than
 *       general ones (e.g., {@code Collection})</li>
 *   <li><strong>Hash strategies</strong> — registers {@link ItemStack} and
 *       {@link FluidStack} hash/equality strategies for change detection</li>
 *   <li><strong>Enum registration</strong> — marks {@link LogicalSide} and
 *       {@link Direction} as "fixed" enums for ordinal-based persistence</li>
 *   <li><strong>Test blocks</strong> — in development mode only, registers the
 *       {@code test} package blocks/entities/items for manual testing</li>
 * </ol>
 *
 * <h3>Factory priority system:</h3>
 * <p>When resolving a factory for an unknown field type, factories are checked in
 * descending priority order. Higher-priority predicates match first. For example,
 * {@code IntCollection} (priority 1000) matches before {@code Collection} (priority 100),
 * ensuring the more specific handler is used.</p>
 *
 * <h3>Extending with custom types:</h3>
 * <p>Downstream mods can register additional factories, access factories, codecs,
 * and strategies by calling the static registration methods in
 * {@link FieldDefinitionStorage} and {@link DataSyncCodec} during their own mod
 * initialization. All registries are thread-safe and support runtime registration.</p>
 *
 * @see FieldDefinitionStorage
 * @see DataSyncCodec
 * @see DataSyncNetwork
 */
@Mod(DataSyncLib.MOD_ID)
public final class DataSyncLib {

    public static final String MOD_ID = "datasynclib";

    public static final Logger LOGGER = LoggerFactory.getLogger("Data Sync Lib");


    public DataSyncLib(FMLJavaModLoadingContext context) {
        DataSyncCodec.init();
        DataSyncNetwork.init();
        // Primitive field factories (exact-type match)
        registerFactory(boolean.class, BooleanField::new);
        registerFactory(byte.class, ByteField::new);
        registerFactory(char.class, CharField::new);
        registerFactory(double.class, DoubleField::new);
        registerFactory(float.class, FloatField::new);
        registerFactory(int.class, IntField::new);
        registerFactory(long.class, LongField::new);
        registerFactory(short.class, ShortField::new);
        // Object field factory: matches any type with a registered DataSyncCodec
        registerCustomFactory(DataSyncCodec::contains, k -> ObjCodecField::new, 100);

        // Generic object field factory: matches parameterized types with registered generic codecs
        registerCustomGenericFactory(DataSyncCodec::contains, (t, gt) -> {
            var codec = DataSyncCodec.get(t, gt);
            if (codec == null) throw new IllegalArgumentException("No codec for " + t + " " + Arrays.toString(gt));
            return d -> new CustomObjCodecField<>(d, codec);
        }, 100);

        // Access-mode factories for primitive arrays
        registerAccessFactory(boolean[].class, BooleanArrayAccess::new);
        registerAccessFactory(byte[].class, ByteArrayAccess::new);
        registerAccessFactory(int[].class, IntArrayAccess::new);
        registerAccessFactory(long[].class, LongArrayAccess::new);

        // Access-mode factories: IFieldDataHolder/IDataSerializable (highest priority for containers)
        registerAccessInterfaceFactory(IFieldDataHolder.class, k -> FieldDataHolderAccess::new, 1000);
        registerAccessInterfaceFactory(IDataSerializable.class, k -> SerializableAccess::new, 1000);

        // Access-mode factories: FastUtil primitive collections → general Collection (descending specificity)
        registerAccessInterfaceFactory(IntCollection.class, k -> IntCollectionAccess::new, 1000);
        registerAccessInterfaceFactory(LongCollection.class, k -> LongCollectionAccess::new, 1000);
        registerAccessInterfaceFactory(Collection.class, k -> CollectionAccess::new, 100);

        // Access-mode factories: FastUtil primitive maps → general Map (descending specificity)
        registerAccessInterfaceFactory(Reference2LongMap.class, k -> Reference2LongMapAccess::new, 1000);
        registerAccessInterfaceFactory(Object2LongMap.class, k -> Object2LongMapAccess::new, 1000);
        registerAccessInterfaceFactory(Reference2IntMap.class, k -> Reference2IntMapAccess::new, 1000);
        registerAccessInterfaceFactory(Object2IntMap.class, k -> Object2IntMapAccess::new, 1000);
        registerAccessInterfaceFactory(Map.class, k -> MapAccess::new, 100);

        // Access-mode factories: non-primitive arrays (custom predicates with codec lookup)
        registerAccessCustomFactory(c -> c.isArray() && !c.componentType().isPrimitive() && IFieldDataHolder.class.isAssignableFrom(c.componentType()), c -> FieldDataHolderArrayAccess::new, 2000);
        registerAccessCustomFactory(c -> c.isArray() && !c.componentType().isPrimitive() && IDataSerializable.class.isAssignableFrom(c.componentType()), c -> SerializableArrayAccess::new, 2000);

        registerAccessCustomFactory(c -> c.isArray() && !c.componentType().isPrimitive(), c -> {
            var codec = DataSyncCodec.get(c.componentType());
            if (codec == null) throw new IllegalArgumentException("No codec for " + c);
            return d -> new ArrayAccess<>(d, codec);
        }, 200);

        // Hash strategies for mutable Minecraft types in change detection
        registerStrategy(ItemStack.class, ItemStackHashStrategy.ALL);
        registerStrategy(FluidStack.class, FluidStackHashStrategy.ALL);

        // "Fixed" enums use ordinal-based persistence (compact, but order-sensitive)
        EnumUtil.addFixedEnum(LogicalSide.class);
        EnumUtil.addFixedEnum(Direction.class);
        NbtUtil.init();

        if (FMLLoader.isProduction()) return;
        // Register test blocks and block entities (development mode only)
        var modEventBus = context.getModEventBus();
        ModBlocks.BLOCKS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modEventBus);
        ModItems.ITEMS.register(modEventBus);
    }
}
