package com.gto.datasynclib;

import com.gto.datasynclib.datastream.codec.*;
import com.gto.datasynclib.datastream.data.*;
import com.gto.datasynclib.util.DataCodecs;
import com.gto.datasynclib.util.EnumUtil;
import com.gto.datasynclib.util.HashUtil;
import com.gto.datasynclib.util.StreamCodecs;
import com.gto.datasynclib.util.cache.ConcurrentHashMapCache;
import com.gto.datasynclib.util.cache.MapCache;
import com.mojang.datafixers.util.*;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Unified codec registry that pairs network stream codecs with persistent data codecs.
 *
 * <p>Each {@code DataSyncCodec} combines four functional components:
 * <ul>
 *   <li>{@link #streamWriter} / {@link #streamReader} — for live network synchronization via {@link FriendlyByteBuf}</li>
 *   <li>{@link #dataWriter} / {@link #dataReader} — for persistent storage via {@link com.gto.datasynclib.datastream.data.Data Data} objects</li>
 * </ul>
 *
 * <p>The registry supports both direct type-to-codec mappings ({@link #CODECS}) and
 * generic type mappings ({@link #GENERIC_CODECS}) for parameterized types.
 * Enum codecs and object-array codecs are auto-generated on first access via
 * {@link #ENUM_CACHE} and {@link #ARRAY_CACHE}.
 *
 * <p>Pre-registered codecs cover all Java primitives, String, UUID, BigInteger, arrays,
 * and Minecraft types (Item, Block, Fluid, ItemStack, FluidStack, BlockPos, CompoundTag, Component, BlockState).
 *
 * @param <T> the type this codec can encode and decode
 */
@SuppressWarnings("unchecked")
public final class DataSyncCodec<T> implements ByteStreamCodec<T>, DataCodec<T> {

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

    /**
     * Encoder for live network synchronization via {@link FriendlyByteBuf}.
     */
    public final ByteStreamEncoder<? super T> streamWriter;
    /**
     * Decoder for live network synchronization via {@link FriendlyByteBuf}.
     */
    public final ByteStreamDecoder<? extends T> streamReader;
    /**
     * Encoder for persistent storage via {@link com.gto.datasynclib.datastream.data.Data Data} objects.
     */
    public final DataEncoder<? super T> dataWriter;
    /**
     * Decoder for persistent storage via {@link com.gto.datasynclib.datastream.data.Data Data} objects.
     */
    public final DataDecoder<? extends T> dataReader;

    private DataSyncCodec(ByteStreamEncoder<? super T> streamWriter, ByteStreamDecoder<? extends T> streamReader, DataEncoder<? super T> dataWriter, DataDecoder<? extends T> dataReader) {
        this.streamWriter = streamWriter;
        this.streamReader = streamReader;
        this.dataWriter = dataWriter;
        this.dataReader = dataReader;
    }

    // ===== ByteStreamCodec implementation =====

    @Override
    public void encode(FriendlyByteBuf buf, T obj) {
        streamWriter.encode(buf, obj);
    }

    @Override
    public T decode(FriendlyByteBuf buf) {
        return streamReader.decode(buf);
    }

    // ===== DataCodec implementation =====

    @Override
    public @NotNull Data encode(T obj) {
        return dataWriter.encode(obj);
    }

    @Override
    public T decode(@NotNull Data data, int dataVersion) {
        return dataReader.decode(data, dataVersion);
    }

    /**
     * Creates a codec from <strong>separate</strong> stream and data encoder/decoder components.
     *
     * <p>This is the most flexible factory — use it when the network and persistence
     * formats differ (e.g., registry entries use integer IDs on the wire but string keys
     * on disk). Each component is independent: the stream writer/reader work with
     * {@link FriendlyByteBuf}, and the data writer/reader work with {@link com.gto.datasynclib.datastream.data.Data Data}.</p>
     *
     * @param streamWriter encodes to network buffer
     * @param streamReader decodes from network buffer
     * @param dataWriter   encodes to persistent Data
     * @param dataReader   decodes from persistent Data
     */
    public static <T> DataSyncCodec<T> of(ByteStreamEncoder<? super T> streamWriter, ByteStreamDecoder<? extends T> streamReader, DataEncoder<? super T> dataWriter, DataDecoder<? extends T> dataReader) {
        return new DataSyncCodec<>(streamWriter, streamReader, dataWriter, dataReader);
    }

    /**
     * Creates a codec from separate stream and data codecs (each implementing both
     * encode and decode for its respective medium).
     *
     * <p>Use when you have distinct optimized codecs for network and persistence.
     * If both paths use the same codec, use {@link #of(ByteStreamCodec)} or
     * {@link #of(DataCodec)} instead.</p>
     *
     * @param streamCodec bidirectional network serializer
     * @param dataCodec   bidirectional persistence serializer
     */
    public static <T> DataSyncCodec<T> of(ByteStreamCodec<T> streamCodec, DataCodec<T> dataCodec) {
        return new DataSyncCodec<>(streamCodec, streamCodec, dataCodec, dataCodec);
    }

    /**
     * Creates a codec from a network stream codec, <strong>automatically deriving</strong>
     * the data codec by serializing to/from byte arrays via {@link com.gto.datasynclib.datastream.data.Data#writeData Data.writeData}
     * / {@link com.gto.datasynclib.datastream.data.Data#readData Data.readData}.
     *
     * <p>Use when you have a working network codec and want a quick persistence path
     * without writing a separate Data codec. The data format will be a byte array.</p>
     */
    public static <T> DataSyncCodec<T> of(ByteStreamCodec<T> streamCodec) {
        return of(streamCodec, DataCodec.of(streamCodec));
    }

    /**
     * Creates a codec from a data codec, <strong>automatically deriving</strong>
     * the stream codec by serializing to/from bytes via
     * {@link com.gto.datasynclib.datastream.data.Data#writeData Data.writeData} /
     * {@link com.gto.datasynclib.datastream.data.Data#readData Data.readData}.
     *
     * <p>Use when you have a working persistence codec and want a quick network path.
     * The network format will be a length-prefixed byte array of the Data encoding.</p>
     */
    public static <T> DataSyncCodec<T> of(DataCodec<T> dataCodec) {
        return of(ByteStreamCodec.of(dataCodec), dataCodec);
    }

    // ===== Composite builders (unified stream + data) =====

    public static <T, F1> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            Function<? super F1, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, constructor),
                DataCodec.composite(c1, g1, constructor));
    }

    public static <T, F1, F2> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            BiFunction<? super F1, ? super F2, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, constructor),
                DataCodec.composite(c1, g1, c2, g2, constructor));
    }

    public static <T, F1, F2, F3> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            Function3<? super F1, ? super F2, ? super F3, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, constructor));
    }

    public static <T, F1, F2, F3, F4> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            Function4<? super F1, ? super F2, ? super F3, ? super F4, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, constructor));
    }

    public static <T, F1, F2, F3, F4, F5> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            Function5<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            Function6<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            Function7<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7, F8> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            DataSyncCodec<F8> c8, Function<? super T, ? extends F8> g8,
            Function8<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            DataSyncCodec<F8> c8, Function<? super T, ? extends F8> g8,
            DataSyncCodec<F9> c9, Function<? super T, ? extends F9> g9,
            Function9<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            DataSyncCodec<F8> c8, Function<? super T, ? extends F8> g8,
            DataSyncCodec<F9> c9, Function<? super T, ? extends F9> g9,
            DataSyncCodec<F10> c10, Function<? super T, ? extends F10> g10,
            Function10<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            DataSyncCodec<F8> c8, Function<? super T, ? extends F8> g8,
            DataSyncCodec<F9> c9, Function<? super T, ? extends F9> g9,
            DataSyncCodec<F10> c10, Function<? super T, ? extends F10> g10,
            DataSyncCodec<F11> c11, Function<? super T, ? extends F11> g11,
            Function11<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            DataSyncCodec<F8> c8, Function<? super T, ? extends F8> g8,
            DataSyncCodec<F9> c9, Function<? super T, ? extends F9> g9,
            DataSyncCodec<F10> c10, Function<? super T, ? extends F10> g10,
            DataSyncCodec<F11> c11, Function<? super T, ? extends F11> g11,
            DataSyncCodec<F12> c12, Function<? super T, ? extends F12> g12,
            Function12<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            DataSyncCodec<F8> c8, Function<? super T, ? extends F8> g8,
            DataSyncCodec<F9> c9, Function<? super T, ? extends F9> g9,
            DataSyncCodec<F10> c10, Function<? super T, ? extends F10> g10,
            DataSyncCodec<F11> c11, Function<? super T, ? extends F11> g11,
            DataSyncCodec<F12> c12, Function<? super T, ? extends F12> g12,
            DataSyncCodec<F13> c13, Function<? super T, ? extends F13> g13,
            Function13<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, c13, g13, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, c13, g13, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            DataSyncCodec<F8> c8, Function<? super T, ? extends F8> g8,
            DataSyncCodec<F9> c9, Function<? super T, ? extends F9> g9,
            DataSyncCodec<F10> c10, Function<? super T, ? extends F10> g10,
            DataSyncCodec<F11> c11, Function<? super T, ? extends F11> g11,
            DataSyncCodec<F12> c12, Function<? super T, ? extends F12> g12,
            DataSyncCodec<F13> c13, Function<? super T, ? extends F13> g13,
            DataSyncCodec<F14> c14, Function<? super T, ? extends F14> g14,
            Function14<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, c13, g13, c14, g14, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, c13, g13, c14, g14, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            DataSyncCodec<F8> c8, Function<? super T, ? extends F8> g8,
            DataSyncCodec<F9> c9, Function<? super T, ? extends F9> g9,
            DataSyncCodec<F10> c10, Function<? super T, ? extends F10> g10,
            DataSyncCodec<F11> c11, Function<? super T, ? extends F11> g11,
            DataSyncCodec<F12> c12, Function<? super T, ? extends F12> g12,
            DataSyncCodec<F13> c13, Function<? super T, ? extends F13> g13,
            DataSyncCodec<F14> c14, Function<? super T, ? extends F14> g14,
            DataSyncCodec<F15> c15, Function<? super T, ? extends F15> g15,
            Function15<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, c13, g13, c14, g14, c15, g15, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, c13, g13, c14, g14, c15, g15, constructor));
    }

    public static <T, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12, F13, F14, F15, F16> DataSyncCodec<T> composite(
            DataSyncCodec<F1> c1, Function<? super T, ? extends F1> g1,
            DataSyncCodec<F2> c2, Function<? super T, ? extends F2> g2,
            DataSyncCodec<F3> c3, Function<? super T, ? extends F3> g3,
            DataSyncCodec<F4> c4, Function<? super T, ? extends F4> g4,
            DataSyncCodec<F5> c5, Function<? super T, ? extends F5> g5,
            DataSyncCodec<F6> c6, Function<? super T, ? extends F6> g6,
            DataSyncCodec<F7> c7, Function<? super T, ? extends F7> g7,
            DataSyncCodec<F8> c8, Function<? super T, ? extends F8> g8,
            DataSyncCodec<F9> c9, Function<? super T, ? extends F9> g9,
            DataSyncCodec<F10> c10, Function<? super T, ? extends F10> g10,
            DataSyncCodec<F11> c11, Function<? super T, ? extends F11> g11,
            DataSyncCodec<F12> c12, Function<? super T, ? extends F12> g12,
            DataSyncCodec<F13> c13, Function<? super T, ? extends F13> g13,
            DataSyncCodec<F14> c14, Function<? super T, ? extends F14> g14,
            DataSyncCodec<F15> c15, Function<? super T, ? extends F15> g15,
            DataSyncCodec<F16> c16, Function<? super T, ? extends F16> g16,
            Function16<? super F1, ? super F2, ? super F3, ? super F4, ? super F5, ? super F6, ? super F7, ? super F8, ? super F9, ? super F10, ? super F11, ? super F12, ? super F13, ? super F14, ? super F15, ? super F16, ? extends T> constructor) {
        return of(
                ByteStreamCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, c13, g13, c14, g14, c15, g15, c16, g16, constructor),
                DataCodec.composite(c1, g1, c2, g2, c3, g3, c4, g4, c5, g5, c6, g6, c7, g7, c8, g8, c9, g9, c10, g10, c11, g11, c12, g12, c13, g13, c14, g14, c15, g15, c16, g16, constructor));
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
     * Retrieves the codec for the specified type.
     *
     * <p>Lookup order:
     * <ol>
     *   <li>Primitive types — always return {@code null} (primitives don't have codecs;
     *       use the wrapper type instead)</li>
     *   <li>Enum types — auto-generated on first access via {@link #ENUM_CACHE}. Uses
     *       ordinal-based encoding on the wire and either ordinal-based or name-based
     *       encoding on disk (depending on whether the enum type is "fixed")</li>
     *   <li>Non-primitive object arrays — auto-generated on first access via
     *       {@link #ARRAY_CACHE}. Encodes length-prefixed with null markers</li>
     *   <li>Registered types — looked up in {@link #CODECS} by exact class match</li>
     * </ol>
     *
     * @param type the class type
     * @return the registered or auto-generated codec, or {@code null} if not found
     * (primitives always return null)
     */
    @Nullable
    public static <T> DataSyncCodec<T> get(Class<T> type) {
        if (type.isPrimitive()) return null;
        DataSyncCodec<?> codec;
        if (type.isEnum()) {
            codec = ENUM_CACHE.getCache(type);
        } else if (type.isArray() && !type.componentType().isPrimitive()) {
            codec = ARRAY_CACHE.getCacheNonAtomic(type);
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

    public void register(Class<T> type) {
        synchronized (CODECS) {
            CODECS.put(type, this);
        }
    }

    /**
     * Registers a codec for the specified type with separate encoder/decoder components.
     *
     * <p>This is the most general registration method. Use when the network and persistence
     * formats differ. For simpler cases, see the convenience overloads.</p>
     *
     * @param type         the class to register for (exact match, not assignable-from)
     * @param streamWriter encoder for network buffers
     * @param streamReader decoder for network buffers
     * @param dataWriter   encoder for persistent Data objects
     * @param dataReader   decoder for persistent Data objects
     * @param <T>          the type
     * @return the registered codec (also returned by future {@link #get(Class)} calls)
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, ByteStreamEncoder<T> streamWriter, ByteStreamDecoder<T> streamReader, DataEncoder<T> dataWriter, DataDecoder<T> dataReader) {
        var codec = new DataSyncCodec<>(streamWriter, streamReader, dataWriter, dataReader);
        synchronized (CODECS) {
            CODECS.put(type, codec);
        }
        return codec;
    }

    /**
     * Registers a codec from a stream codec and a data codec.
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, ByteStreamCodec<T> streamCodec, DataCodec<T> dataCodec) {
        return register(type, streamCodec, streamCodec, dataCodec, dataCodec);
    }

    /**
     * Registers a codec from a data codec, deriving the stream codec automatically.
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, DataCodec<T> codec) {
        return register(type, ByteStreamCodec.of(codec), codec);
    }

    /**
     * Registers a codec from a stream codec and a Mojang {@link Codec}, deriving the data codec automatically.
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, ByteStreamCodec<T> streamCodec, Codec<T> codec) {
        return register(type, streamCodec, DataCodec.of(codec));
    }

    /**
     * Registers a codec for a Minecraft registry type.
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, Registry<T> registry) {
        return register(type, StreamCodecs.of(registry), DataCodecs.of(registry));
    }

    /**
     * Registers a codec for a parameterized (generic) type.
     *
     * @param type         the raw class type
     * @param streamWriter encoder for network buffers
     * @param streamReader decoder for network buffers
     * @param dataWriter   encoder for persistent Data objects
     * @param dataReader   decoder for persistent Data objects
     * @param genericTypes the generic type parameters
     * @param <T>          the type
     * @return the registered codec
     */
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

    // ===== Pre-registered codec constants =====

    /**
     * Codec for the Data type system itself (passthrough).
     */
    public static final DataSyncCodec<Data> DATA_CODEC = register(Data.class, Data.BYTE_STREAM_CODEC, Data.DATA_CODEC);

    public static final DataSyncCodec<StringMapData> MAP_DATA_CODEC = register(StringMapData.class, StringMapData.BYTE_STREAM_CODEC, StringMapData.DATA_CODEC);

    public static final DataSyncCodec<boolean[]> BOOLEANS_CODEC = register(boolean[].class, ByteStreamCodec.BOOLEANS_CODEC, DataCodec.BOOLEANS_CODEC);
    public static final DataSyncCodec<byte[]> BYTES_CODEC = register(byte[].class, ByteStreamCodec.BYTES_CODEC, DataCodec.BYTES_CODEC);
    public static final DataSyncCodec<int[]> INTS_CODEC = register(int[].class, ByteStreamCodec.INTS_CODEC, DataCodec.INTS_CODEC);
    public static final DataSyncCodec<long[]> LONGS_CODEC = register(long[].class, ByteStreamCodec.LONGS_CODEC, DataCodec.LONGS_CODEC);
    public static final DataSyncCodec<float[]> FLOATS_CODEC = register(float[].class, ByteStreamCodec.FLOATS_CODEC, DataCodec.FLOATS_CODEC);
    public static final DataSyncCodec<double[]> DOUBLES_CODEC = register(double[].class, ByteStreamCodec.DOUBLES_CODEC, DataCodec.DOUBLES_CODEC);
    public static final DataSyncCodec<short[]> SHORTS_CODEC = register(short[].class, ByteStreamCodec.SHORTS_CODEC, DataCodec.SHORTS_CODEC);
    public static final DataSyncCodec<char[]> CHARS_CODEC = register(char[].class, ByteStreamCodec.CHARS_CODEC, DataCodec.CHARS_CODEC);

    public static final DataSyncCodec<Boolean> BOOLEAN_CODEC = register(Boolean.class, ByteStreamCodec.BOOLEAN_CODEC, DataCodec.BOOLEAN_CODEC);

    public static final DataSyncCodec<Byte> BYTE_CODEC = register(Byte.class, ByteStreamCodec.BYTE_CODEC, DataCodec.BYTE_CODEC);

    public static final DataSyncCodec<Short> SHORT_CODEC = register(Short.class, ByteStreamCodec.SHORT_CODEC, DataCodec.SHORT_CODEC);

    public static final DataSyncCodec<Integer> INT_CODEC = register(Integer.class, ByteStreamCodec.INT_CODEC, DataCodec.INT_CODEC);

    public static final DataSyncCodec<Long> LONG_CODEC = register(Long.class, ByteStreamCodec.LONG_CODEC, DataCodec.LONG_CODEC);

    public static final DataSyncCodec<Float> FLOAT_CODEC = register(Float.class, ByteStreamCodec.FLOAT_CODEC, DataCodec.FLOAT_CODEC);

    public static final DataSyncCodec<Double> DOUBLE_CODEC = register(Double.class, ByteStreamCodec.DOUBLE_CODEC, DataCodec.DOUBLE_CODEC);

    public static final DataSyncCodec<Character> CHAR_CODEC = register(Character.class, ByteStreamCodec.CHAR_CODEC, DataCodec.CHAR_CODEC);

    public static final DataSyncCodec<String> STRING_CODEC = register(String.class, ByteStreamCodec.STRING_CODEC, DataCodec.STRING_CODEC);

    public static final DataSyncCodec<UUID> UUID_CODEC = register(UUID.class, ByteStreamCodec.UUID_CODEC, DataCodec.UUID_CODEC);

    public static final DataSyncCodec<BigInteger> BIG_INTEGER_CODEC = register(BigInteger.class, ByteStreamCodec.BIG_INTEGER_CODEC, DataCodec.BIG_INTEGER_CODEC);

    public static final DataSyncCodec<Item> ITEM_CODEC = register(Item.class, BuiltInRegistries.ITEM);
    public static final DataSyncCodec<Block> BLOCK_CODEC = register(Block.class, BuiltInRegistries.BLOCK);
    public static final DataSyncCodec<Fluid> FLUID_CODEC = register(Fluid.class, BuiltInRegistries.FLUID);
    public static final DataSyncCodec<EntityType<?>> ENTITY_TYPE_CODEC = register((Class<EntityType<?>>) (Class<?>) EntityType.class, BuiltInRegistries.ENTITY_TYPE);
    public static final DataSyncCodec<BlockEntityType<?>> BLOCK_ENTITY_TYPE_CODEC = register((Class<BlockEntityType<?>>) (Class<?>) BlockEntityType.class, BuiltInRegistries.BLOCK_ENTITY_TYPE);

    public static final DataSyncCodec<ResourceLocation> RESOURCE_LOCATION_CODEC = register(ResourceLocation.class, StreamCodecs.RESOURCE_LOCATION_CODEC, DataCodecs.RESOURCE_LOCATION_CODEC);

    public static final DataSyncCodec<Vec2> VEC2_CODEC = register(Vec2.class, StreamCodecs.VEC2_CODEC, DataCodecs.VEC2_CODEC);
    public static final DataSyncCodec<Vec3> VEC3_CODEC = register(Vec3.class, StreamCodecs.VEC3_CODEC, DataCodecs.VEC3_CODEC);
    public static final DataSyncCodec<BlockPos> BLOCK_POS_CODEC = register(BlockPos.class, StreamCodecs.BLOCK_POS_CODEC, DataCodecs.BLOCK_POS_CODEC);
    public static final DataSyncCodec<ChunkPos> CHUNK_POS_CODEC = register(ChunkPos.class, StreamCodecs.CHUNK_POS_CODEC, DataCodecs.CHUNK_POS_CODEC);

    public static final DataSyncCodec<Tag> TAG_CODEC = register(Tag.class, StreamCodecs.TAG_CODEC, DataCodecs.TAG_CODEC);
    public static final DataSyncCodec<CompoundTag> COMPOUND_TAG_CODEC = register(CompoundTag.class, StreamCodecs.COMPOUND_TAG_CODEC, DataCodecs.COMPOUND_TAG_CODEC);
    public static final DataSyncCodec<ListTag> LIST_TAG_CODEC = register(ListTag.class, StreamCodecs.LIST_TAG_CODEC, DataCodecs.LIST_TAG_CODEC);

    public static final DataSyncCodec<ItemStack> ITEM_STACK_CODEC = register(ItemStack.class, StreamCodecs.ITEM_STACK_CODEC, DataCodecs.ITEM_STACK_CODEC);
    public static final DataSyncCodec<FluidStack> FLUID_STACK_CODEC = register(FluidStack.class, StreamCodecs.FLUID_STACK_CODEC, DataCodecs.FLUID_STACK_CODEC);

    public static final DataSyncCodec<Component> COMPONENT_CODEC = register(Component.class, StreamCodecs.COMPONENT_CODEC, DataCodecs.COMPONENT_CODEC);

    public static final DataSyncCodec<BlockState> BLOCK_STATE_CODEC = register(BlockState.class, ByteStreamCodec.of(BlockState.CODEC), BlockState.CODEC);

    public static final DataSyncCodec<AABB> AABB_CODEC = DataSyncCodec.composite(DOUBLE_CODEC, a -> a.minX,
            DOUBLE_CODEC, a -> a.minY,
            DOUBLE_CODEC, a -> a.minZ,
            DOUBLE_CODEC, a -> a.maxX,
            DOUBLE_CODEC, a -> a.maxY,
            DOUBLE_CODEC, a -> a.maxZ, AABB::new);

    public static void init() {
        AABB_CODEC.register(AABB.class);
    }
}
