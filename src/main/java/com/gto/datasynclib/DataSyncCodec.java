package com.gto.datasynclib;

import com.gto.datasynclib.datasream.codec.*;
import com.gto.datasynclib.datasream.data.*;
import com.gto.datasynclib.util.*;
import com.gto.datasynclib.util.cache.ConcurrentHashMapCache;
import com.gto.datasynclib.util.cache.MapCache;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.UUID;

public final class DataSyncCodec<T> {

    private static final Reference2ReferenceOpenHashMap<Class<?>, DataSyncCodec<?>> CODECS = new Reference2ReferenceOpenHashMap<>();
    private static final Reference2ReferenceOpenHashMap<Class<?>, HashMap<Object, DataSyncCodec<?>>> GENERIC_CODECS = new Reference2ReferenceOpenHashMap<>();

    private static final MapCache<Class<?>, DataSyncCodec<?>> ENUM_CACHE = new ConcurrentHashMapCache<>(t -> {
        if (t.isEnum()) {
            var constants = t.getEnumConstants();
            var len = constants.length;
            var isFixed = EnumUtil.isFixed((Class<? extends Enum<?>>) t);
            return new DataSyncCodec<>((buf, obj) -> buf.writeVarInt(obj.ordinal()),
                    buf -> (Enum<?>) constants[buf.readVarInt()],
                    obj -> {
                        if (isFixed) return IntData.valueOf(obj.ordinal());
                        return StringData.valueOf(EnumUtil.getSerializedName(obj));
                    },
                    (data, dataVersion) -> {
                        if (data instanceof IntData(int value)) {
                            if (value < len) return (Enum<?>) constants[value];
                            return null;
                        }
                        return EnumUtil.getSerializedEnum((Class) t, data.getString());
                    });
        }
        throw new RuntimeException("No codec registered for type " + t);
    });

    private static final MapCache<Class<?>, DataSyncCodec<?>> ARRAY_CACHE = new ConcurrentHashMapCache<>(t -> {
        if (t.isArray() && !t.componentType().isPrimitive()) {
            Class type = t.getComponentType();
            DataSyncCodec<Object> codec = get(type);
            return new DataSyncCodec<>(
                    (buf, obj) -> {
                        buf.writeVarInt(obj.length);
                        var encoder = codec.streamWriter;
                        for (var element : obj) {
                            if (element == null) {
                                buf.writeBoolean(false);
                            } else {
                                buf.writeBoolean(true);
                                encoder.encode(buf, element);
                            }
                        }
                    },
                    buf -> {
                        var decoder = codec.streamReader;
                        var length = buf.readVarInt();
                        var array = (Object[]) Array.newInstance(type, length);
                        for (int i = 0; i < length; i++) {
                            if (buf.readBoolean()) array[i] = decoder.decode(buf);
                        }
                        return array;
                    },
                    obj -> {
                        if (obj.length == 0) return NullData.INSTANCE;
                        var encoder = codec.dataWriter;
                        var list = new ListData();
                        for (Object element : obj) {
                            if (element != null) {
                                list.add(encoder.encode(element));
                            } else {
                                list.addNull();
                            }
                        }
                        return list;
                    },
                    (data, dataVersion) -> {
                        var decoder = codec.dataReader;
                        var list = data.getList();
                        var size = list.size();
                        var array = (Object[]) Array.newInstance(type, size);
                        for (int i = 0; i < size; i++) {
                            array[i] = decoder.decode(list.get(i), dataVersion);
                        }
                        return array;
                    });
        }
        throw new RuntimeException("No codec registered for type " + t);
    });

    public final ByteStreamEncoder<? super T> streamWriter;
    public final ByteStreamDecoder<? extends T> streamReader;
    public final DataEncoder<? super T> dataWriter;
    public final DataDecoder<? extends T> dataReader;

    private DataSyncCodec(ByteStreamEncoder<? super T> streamWriter, ByteStreamDecoder<? extends T> streamReader, DataEncoder<? super T> dataWriter, DataDecoder<? extends T> dataReader) {
        this.streamWriter = streamWriter;
        this.streamReader = streamReader;
        this.dataWriter = dataWriter;
        this.dataReader = dataReader;
    }

    public static <T> DataSyncCodec<T> of(ByteStreamEncoder<? super T> streamWriter, ByteStreamDecoder<? extends T> streamReader, DataEncoder<? super T> dataWriter, DataDecoder<? extends T> dataReader) {
        return new DataSyncCodec<>(streamWriter, streamReader, dataWriter, dataReader);
    }

    public static <T> DataSyncCodec<T> of(ByteStreamCodec<T> streamCodec, DataCodec<T> dataCodec) {
        return new DataSyncCodec<>(streamCodec, streamCodec, dataCodec, dataCodec);
    }

    public static <T> DataSyncCodec<T> of(ByteStreamCodec<T> streamCodec) {
        return of(streamCodec, DataCodec.of(streamCodec));
    }

    public static <T> DataSyncCodec<T> of(DataCodec<T> dataCodec) {
        return of(ByteStreamCodec.of(dataCodec), dataCodec);
    }

    /**
     * Checks if a codec is registered for the given type
     *
     * @param type the class to check
     * @return true if codec exists, false otherwise
     */
    public static boolean contains(Class<?> type) {
        return get(type) != null;
    }

    /**
     * Checks if a generic codec is registered for the given type with specific generic parameters
     *
     * @param type         the raw class type
     * @param genericTypes the generic type parameters
     * @return true if codec exists, false otherwise
     */
    public static boolean contains(Class<?> type, Class<?>... genericTypes) {
        var map = GENERIC_CODECS.get(type);
        return map != null && map.containsKey(HashUtil.arrayIdentityWrapper(genericTypes));
    }

    /**
     * Retrieves codec for the specified type
     *
     * @param type the class type
     * @return the registered codec, or null if not found
     */
    @Nullable
    public static <T> DataSyncCodec<T> get(Class<T> type) {
        if (type.isPrimitive()) return null;
        DataSyncCodec<?> codec;
        if (type.isEnum()) {
            codec = ENUM_CACHE.getCache(type);
        } else if (type.isArray() && !type.componentType().isPrimitive()) {
            codec = ARRAY_CACHE.getCacheRecursion(type);
        } else {
            codec = CODECS.get(type);
        }
        return (DataSyncCodec<T>) codec;
    }

    /**
     * Retrieves generic codec for the specified type with specific generic parameters
     *
     * @param type         the raw class type
     * @param genericTypes the generic type parameters
     * @return the registered codec, or null if not found
     */
    @Nullable
    public static <T> DataSyncCodec<T> get(Class<?> type, Class<?>... genericTypes) {
        var map = GENERIC_CODECS.get(type);
        if (map == null) return null;
        return (DataSyncCodec<T>) map.get(HashUtil.arrayIdentityWrapper(genericTypes));
    }

    public static <T> DataSyncCodec<T> register(Class<T> type, ByteStreamEncoder<T> streamWriter, ByteStreamDecoder<T> streamReader, DataEncoder<T> dataWriter, DataDecoder<T> dataReader) {
        var codec = new DataSyncCodec<>(streamWriter, streamReader, dataWriter, dataReader);
        synchronized (CODECS) {
            CODECS.put(type, codec);
        }
        return codec;
    }

    public static <T> DataSyncCodec<T> register(Class<T> type, ByteStreamCodec<T> streamCodec, DataCodec<T> dataCodec) {
        return register(type, streamCodec, streamCodec, dataCodec, dataCodec);
    }

    public static <T> DataSyncCodec<T> register(Class<T> type, DataCodec<T> codec) {
        return register(type, ByteStreamCodec.of(codec), codec);
    }

    public static <T> DataSyncCodec<T> register(Class<T> type, ByteStreamCodec<T> streamCodec, Codec<T> codec) {
        return register(type, streamCodec, DataCodec.of(codec));
    }

    public static <T> DataSyncCodec<T> register(Class<T> type, Registry<T> registry) {
        return register(type, StreamCodecs.of(registry), DataCodecs.of(registry));
    }

    public static <T> DataSyncCodec<T> register(Class<?> type, ByteStreamEncoder<T> streamWriter, ByteStreamDecoder<T> streamReader, DataEncoder<T> dataWriter, DataDecoder<T> dataReader, Class<?>... genericTypes) {
        var codec = new DataSyncCodec<>(streamWriter, streamReader, dataWriter, dataReader);
        synchronized (GENERIC_CODECS) {
            GENERIC_CODECS.computeIfAbsent(type, k -> new HashMap<>()).put(HashUtil.arrayIdentityWrapper(genericTypes), codec);
        }
        return codec;
    }

    public static <T> DataSyncCodec<T> register(Class<T> type, ByteStreamCodec<T> streamCodec, DataCodec<T> dataCodec, Class<?>... genericTypes) {
        return register(type, streamCodec, streamCodec, dataCodec, dataCodec, genericTypes);
    }

    public final static DataSyncCodec<Data> DATA_CODEC = register(Data.class, Data.BYTE_STREAM_CODEC, Data.DATA_CODEC);

    public final static DataSyncCodec<StringMapData> MAP_DATA_CODEC = register(StringMapData.class, StringMapData.BYTE_STREAM_CODEC, StringMapData.DATA_CODEC);

    public final static DataSyncCodec<boolean[]> BOOLEANS_CODEC = register(boolean[].class, ByteStreamCodec.BOOLEANS_CODEC, DataCodec.BOOLEANS_CODEC);
    public final static DataSyncCodec<byte[]> BYTES_CODEC = register(byte[].class, ByteStreamCodec.BYTES_CODEC, DataCodec.BYTES_CODEC);
    public final static DataSyncCodec<int[]> INTS_CODEC = register(int[].class, ByteStreamCodec.INTS_CODEC, DataCodec.INTS_CODEC);
    public final static DataSyncCodec<long[]> LONGS_CODEC = register(long[].class, ByteStreamCodec.LONGS_CODEC, DataCodec.LONGS_CODEC);

    public final static DataSyncCodec<Boolean> BOOLEAN_CODEC = register(Boolean.class, ByteStreamCodec.BOOLEAN_CODEC, DataCodec.BOOLEAN_CODEC);

    public final static DataSyncCodec<Byte> BYTE_CODEC = register(Byte.class, ByteStreamCodec.BYTE_CODEC, DataCodec.BYTE_CODEC);

    public final static DataSyncCodec<Short> SHORT_CODEC = register(Short.class, ByteStreamCodec.SHORT_CODEC, DataCodec.SHORT_CODEC);

    public final static DataSyncCodec<Integer> INT_CODEC = register(Integer.class, ByteStreamCodec.INT_CODEC, DataCodec.INT_CODEC);

    public final static DataSyncCodec<Long> LONG_CODEC = register(Long.class, ByteStreamCodec.LONG_CODEC, DataCodec.LONG_CODEC);

    public final static DataSyncCodec<Float> FLOAT_CODEC = register(Float.class, ByteStreamCodec.FLOAT_CODEC, DataCodec.FLOAT_CODEC);

    public final static DataSyncCodec<Double> DOUBLE_CODEC = register(Double.class, ByteStreamCodec.DOUBLE_CODEC, DataCodec.DOUBLE_CODEC);

    public final static DataSyncCodec<Character> CHAR_CODEC = register(Character.class, ByteStreamCodec.CHAR_CODEC, DataCodec.CHAR_CODEC);

    public final static DataSyncCodec<String> STRING_CODEC = register(String.class, ByteStreamCodec.STRING_CODEC, DataCodec.STRING_CODEC);

    public final static DataSyncCodec<UUID> UUID_CODEC = register(UUID.class, ByteStreamCodec.UUID_CODEC, DataCodec.UUID_CODEC);

    public final static DataSyncCodec<BigInteger> BIG_INTEGER_CODEC = register(BigInteger.class, ByteStreamCodec.BIG_INTEGER_CODEC, DataCodec.BIG_INTEGER_CODEC);

    public final static DataSyncCodec<Item> ITEM_CODEC = register(Item.class, BuiltInRegistries.ITEM);
    public final static DataSyncCodec<Block> BLOCK_CODEC = register(Block.class, BuiltInRegistries.BLOCK);
    public final static DataSyncCodec<Fluid> FLUID_CODEC = register(Fluid.class, BuiltInRegistries.FLUID);

    public final static DataSyncCodec<ResourceLocation> RESOURCE_LOCATION_CODEC = register(ResourceLocation.class, StreamCodecs.RESOURCE_LOCATION_CODEC, DataCodecs.RESOURCE_LOCATION_CODEC);

    public final static DataSyncCodec<BlockPos> BLOCK_POS_CODEC = register(BlockPos.class, StreamCodecs.BLOCK_POS_CODEC, DataCodecs.BLOCK_POS_CODEC);

    public final static DataSyncCodec<CompoundTag> COMPOUND_TAG_CODEC = register(CompoundTag.class, StreamCodecs.COMPOUND_TAG_CODEC, DataCodecs.COMPOUND_TAG_CODEC);

    public final static DataSyncCodec<ItemStack> ITEM_STACK_CODEC = register(ItemStack.class, StreamCodecs.ITEM_STACK_CODEC, DataCodecs.ITEM_STACK_CODEC);
    public final static DataSyncCodec<FluidStack> FLUID_STACK_CODEC = register(FluidStack.class, StreamCodecs.FLUID_STACK_CODEC, DataCodecs.FLUID_STACK_CODEC);

    public final static DataSyncCodec<Component> COMPONENT_CODEC = register(Component.class, StreamCodecs.COMPONENT_CODEC, DataCodecs.COMPONENT_CODEC);

    public final static DataSyncCodec<BlockState> BLOCK_STATE_CODEC = register(BlockState.class, ByteStreamCodec.of(BlockState.CODEC), BlockState.CODEC);

    public static void init() {
    }
}
