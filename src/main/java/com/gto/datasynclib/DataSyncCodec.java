package com.gto.datasynclib;

import com.gto.datasynclib.datastream.codec.*;
import com.gto.datasynclib.datastream.data.*;
import com.gto.datasynclib.util.DataCodecs;
import com.gto.datasynclib.util.EnumUtil;
import com.gto.datasynclib.util.HashUtil;
import com.gto.datasynclib.util.StreamCodecs;
import com.gto.datasynclib.util.cache.ConcurrentHashMapCache;
import com.gto.datasynclib.util.cache.MapCache;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantment;
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
 * <p>Pre-registered codecs cover Java primitives and their arrays, String, UUID, BigInteger,
 * the {@code Data}/{@code StringMapData} types themselves, and Minecraft types: Item, Block,
 * Fluid, EntityType, BlockEntityType, MobEffect, Enchantment, SoundEvent, Attribute,
 * ParticleType, MenuType, RecipeType, ItemStack, FluidStack, ResourceLocation, Vec2, Vec3,
 * Vec3i, BlockPos, ChunkPos, SectionPos, GlobalPos, Tag, CompoundTag, ListTag, Component,
 * BlockState and AABB. They all register themselves while this class is initialized, so
 * {@link #init()} is a no-op kept for compatibility.</p>
 *
 * <p>Types whose payload is a handful of primitives (Vec3i, SectionPos, AABB) use hand-written
 * codec pairs instead of {@link CombinedCodec#composite} to avoid boxing; see that method for
 * the trade-off.</p>
 *
 * <p>Downstream mods add their own types with the static {@code register(...)} family —
 * {@link #register(Class, ByteStreamCodec, DataCodec)} for a plain runtime type,
 * {@link #register(Class, ByteStreamCodec, DataCodec, Class[])} for a parameterized one, and
 * {@link #register(Class, Registry)} for registry entries. Register during mod construction:
 * the field-definition layer caches the resolved factory per field type, so a registration that
 * arrives after that type was first scanned can be shadowed, and the tables are only
 * synchronized on the write side.</p>
 *
 * @param <T> the type this codec can encode and decode
 */
@SuppressWarnings("unchecked")
public final class DataSyncCodec<T> implements CombinedCodec<T> {

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

    @Override
    @SuppressWarnings("unchecked")
    public DataCodec<T> toDataCodec() {
        // dataWriter == dataReader means the same DataCodec was supplied (with matching type); return it directly.
        return dataWriter == dataReader ? (DataCodec<T>) dataWriter : this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ByteStreamCodec<T> toStreamCodec() {
        // streamWriter == streamReader means the same ByteStreamCodec was supplied; return it directly.
        return streamWriter == streamReader ? (ByteStreamCodec<T>) streamWriter : this;
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

    /**
     * Checks whether a codec can be resolved for the given type.
     *
     * <p>This mirrors {@link #get(Class)}: primitives always return {@code false}, while enum
     * and object-array types return {@code true} even without an explicit registration because
     * their codec is generated on demand.</p>
     *
     * @param type the class to check
     * @return {@code true} if {@link #get(Class)} would return a codec
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
     *   <li>Registered types — looked up in {@link #CODECS} by exact class match. There is no
     *       assignable-from fallback on purpose: a subtype must be registered explicitly (which
     *       also keeps the choice of codec for a subtype visible in one place).</li>
     * </ol>
     *
     * @param type the class type
     * @return the registered or auto-generated codec, or {@code null} if this exact type is not
     * registered (primitives always return null)
     * @throws IllegalArgumentException if {@code type} is {@code null} — normally an unresolved
     *                                  wildcard or type variable in a field declaration
     */
    @Nullable
    public static <T> DataSyncCodec<T> get(Class<T> type) {
        if (type == null)
            throw new IllegalArgumentException("Cannot resolve a codec for a null type (unresolved wildcard or type variable?)");
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

    /**
     * Registers <em>this</em> codec instance for the given runtime type.
     *
     * <p>Same effect as the static {@code register(...)} family, but reuses an already-built
     * codec — used for codecs that are composed at class-initialization time from other constants
     * (see {@link #registerComposed}).</p>
     *
     * @param type the exact runtime type this codec handles
     */
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
     * <p>Lookup goes through {@link #get(Class, Class[])} and the generic field factory, which
     * pass the field's resolved argument classes. Arguments are matched by identity, so pass the
     * very {@link Class} instances that appear in the field declaration.</p>
     *
     * @param type         the raw class type
     * @param streamWriter encoder for network buffers
     * @param streamReader decoder for network buffers
     * @param dataWriter   encoder for persistent Data objects
     * @param dataReader   decoder for persistent Data objects
     * @param genericTypes the generic type parameters, in declaration order
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

    /**
     * Convenience overload of {@link #register(Class, ByteStreamEncoder, ByteStreamDecoder, DataEncoder, DataDecoder, Class[])} using one codec per path.
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, ByteStreamCodec<T> streamCodec, DataCodec<T> dataCodec, Class<?>... genericTypes) {
        return register(type, streamCodec, streamCodec, dataCodec, dataCodec, genericTypes);
    }

    // ===== Pre-registered codec constants =====

    /**
     * Codec for the Data type system itself (passthrough).
     */
    public static final DataSyncCodec<Data> DATA_CODEC = register(Data.class, Data.BYTE_STREAM_CODEC, Data.DATA_CODEC);

    public static final DataSyncCodec<StringMapData> MAP_DATA_CODEC = register(StringMapData.class, StringMapData.BYTE_STREAM_CODEC, StringMapData.DATA_CODEC);

    // ---- primitive arrays ----
    public static final DataSyncCodec<boolean[]> BOOLEANS_CODEC = register(boolean[].class, ByteStreamCodec.BOOLEANS_CODEC, DataCodec.BOOLEANS_CODEC);
    public static final DataSyncCodec<byte[]> BYTES_CODEC = register(byte[].class, ByteStreamCodec.BYTES_CODEC, DataCodec.BYTES_CODEC);
    public static final DataSyncCodec<int[]> INTS_CODEC = register(int[].class, ByteStreamCodec.INTS_CODEC, DataCodec.INTS_CODEC);
    public static final DataSyncCodec<long[]> LONGS_CODEC = register(long[].class, ByteStreamCodec.LONGS_CODEC, DataCodec.LONGS_CODEC);
    public static final DataSyncCodec<float[]> FLOATS_CODEC = register(float[].class, ByteStreamCodec.FLOATS_CODEC, DataCodec.FLOATS_CODEC);
    public static final DataSyncCodec<double[]> DOUBLES_CODEC = register(double[].class, ByteStreamCodec.DOUBLES_CODEC, DataCodec.DOUBLES_CODEC);
    public static final DataSyncCodec<short[]> SHORTS_CODEC = register(short[].class, ByteStreamCodec.SHORTS_CODEC, DataCodec.SHORTS_CODEC);
    public static final DataSyncCodec<char[]> CHARS_CODEC = register(char[].class, ByteStreamCodec.CHARS_CODEC, DataCodec.CHARS_CODEC);

    // ---- boxed primitives, String, UUID, BigInteger ----
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

    // ---- Minecraft registry entries (registry id on the wire, key on disk) ----
    public static final DataSyncCodec<Item> ITEM_CODEC = register(Item.class, BuiltInRegistries.ITEM);
    public static final DataSyncCodec<Block> BLOCK_CODEC = register(Block.class, BuiltInRegistries.BLOCK);
    public static final DataSyncCodec<Fluid> FLUID_CODEC = register(Fluid.class, BuiltInRegistries.FLUID);
    public static final DataSyncCodec<EntityType<?>> ENTITY_TYPE_CODEC = register((Class<EntityType<?>>) (Class<?>) EntityType.class, BuiltInRegistries.ENTITY_TYPE);
    public static final DataSyncCodec<BlockEntityType<?>> BLOCK_ENTITY_TYPE_CODEC = register((Class<BlockEntityType<?>>) (Class<?>) BlockEntityType.class, BuiltInRegistries.BLOCK_ENTITY_TYPE);
    public static final DataSyncCodec<MobEffect> MOB_EFFECT_CODEC = register(MobEffect.class, BuiltInRegistries.MOB_EFFECT);
    public static final DataSyncCodec<Enchantment> ENCHANTMENT_CODEC = register(Enchantment.class, BuiltInRegistries.ENCHANTMENT);
    public static final DataSyncCodec<SoundEvent> SOUND_EVENT_CODEC = register(SoundEvent.class, BuiltInRegistries.SOUND_EVENT);
    public static final DataSyncCodec<Attribute> ATTRIBUTE_CODEC = register(Attribute.class, BuiltInRegistries.ATTRIBUTE);
    public static final DataSyncCodec<ParticleType<?>> PARTICLE_TYPE_CODEC = register((Class<ParticleType<?>>) (Class<?>) ParticleType.class, BuiltInRegistries.PARTICLE_TYPE);
    public static final DataSyncCodec<MenuType<?>> MENU_TYPE_CODEC = register((Class<MenuType<?>>) (Class<?>) MenuType.class, BuiltInRegistries.MENU);
    public static final DataSyncCodec<RecipeType<?>> RECIPE_TYPE_CODEC = register((Class<RecipeType<?>>) (Class<?>) RecipeType.class, BuiltInRegistries.RECIPE_TYPE);

    // ---- Minecraft value types ----
    public static final DataSyncCodec<ResourceLocation> RESOURCE_LOCATION_CODEC = register(ResourceLocation.class, StreamCodecs.RESOURCE_LOCATION_CODEC, DataCodecs.RESOURCE_LOCATION_CODEC);

    public static final DataSyncCodec<Vec2> VEC2_CODEC = register(Vec2.class, StreamCodecs.VEC2_CODEC, DataCodecs.VEC2_CODEC);
    public static final DataSyncCodec<Vec3> VEC3_CODEC = register(Vec3.class, StreamCodecs.VEC3_CODEC, DataCodecs.VEC3_CODEC);
    public static final DataSyncCodec<BlockPos> BLOCK_POS_CODEC = register(BlockPos.class, StreamCodecs.BLOCK_POS_CODEC, DataCodecs.BLOCK_POS_CODEC);
    public static final DataSyncCodec<ChunkPos> CHUNK_POS_CODEC = register(ChunkPos.class, StreamCodecs.CHUNK_POS_CODEC, DataCodecs.CHUNK_POS_CODEC);

    /**
     * Integer triple — hand-written pair (three VarInts on the wire, one {@code IntArrayData} on
     * disk), so no boxing happens on either path. {@link BlockPos} has its own (more compact)
     * codec and still wins by exact match.
     */
    public static final DataSyncCodec<Vec3i> VEC3I_CODEC = register(Vec3i.class, StreamCodecs.VEC3I_CODEC, DataCodecs.VEC3I_CODEC);

    /**
     * Section (16³ chunk section) position — hand-written pair carrying the packed long.
     */
    public static final DataSyncCodec<SectionPos> SECTION_POS_CODEC = register(SectionPos.class, StreamCodecs.SECTION_POS_CODEC, DataCodecs.SECTION_POS_CODEC);

    /**
     * Axis-aligned box — hand-written pair of six raw doubles rather than a
     * {@link CombinedCodec#composite} of {@code double} components, which would box every
     * coordinate on each encode/decode.
     */
    public static final DataSyncCodec<AABB> AABB_CODEC = register(AABB.class, StreamCodecs.AABB_CODEC, DataCodecs.AABB_CODEC);

    /**
     * Dimension + block position; the dimension is carried as its {@link ResourceLocation}.
     */
    public static final DataSyncCodec<GlobalPos> GLOBAL_POS_CODEC = registerComposed(GlobalPos.class, CombinedCodec.composite(
            RESOURCE_LOCATION_CODEC, p -> p.dimension().location(),
            BLOCK_POS_CODEC, GlobalPos::pos,
            (location, pos) -> GlobalPos.of(ResourceKey.create(Registries.DIMENSION, location), pos)));

    // ---- NBT ----
    public static final DataSyncCodec<Tag> TAG_CODEC = register(Tag.class, StreamCodecs.TAG_CODEC, DataCodecs.TAG_CODEC);
    public static final DataSyncCodec<CompoundTag> COMPOUND_TAG_CODEC = register(CompoundTag.class, StreamCodecs.COMPOUND_TAG_CODEC, DataCodecs.COMPOUND_TAG_CODEC);
    public static final DataSyncCodec<ListTag> LIST_TAG_CODEC = register(ListTag.class, StreamCodecs.LIST_TAG_CODEC, DataCodecs.LIST_TAG_CODEC);

    // ---- stacks and components ----
    public static final DataSyncCodec<ItemStack> ITEM_STACK_CODEC = register(ItemStack.class, StreamCodecs.ITEM_STACK_CODEC, DataCodecs.ITEM_STACK_CODEC);
    public static final DataSyncCodec<FluidStack> FLUID_STACK_CODEC = register(FluidStack.class, StreamCodecs.FLUID_STACK_CODEC, DataCodecs.FLUID_STACK_CODEC);

    public static final DataSyncCodec<Component> COMPONENT_CODEC = register(Component.class, StreamCodecs.COMPONENT_CODEC, DataCodecs.COMPONENT_CODEC);

    public static final DataSyncCodec<BlockState> BLOCK_STATE_CODEC = register(BlockState.class, ByteStreamCodec.of(BlockState.CODEC), BlockState.CODEC);

    /**
     * Registers a codec that was composed from other codec constants (see
     * {@link CombinedCodec#composite}), returning it for the constant declaration.
     *
     * <p>Such a codec is produced by a factory rather than by {@code register(...)}, so it has to
     * register itself here — which is why the composed constants live at the end of the constant
     * block, after every component they reference has been initialized.</p>
     */
    private static <T> DataSyncCodec<T> registerComposed(Class<T> type, DataSyncCodec<T> codec) {
        codec.register(type);
        return codec;
    }

    /**
     * Retained for compatibility and symmetry with {@code NbtUtil#init()}: every pre-registered
     * codec now registers itself while this class is initialized (composed ones included, via
     * {@link #registerComposed}), so this method has nothing left to do and is a no-op.
     * {@code DataSyncLib} still calls it during mod construction.
     */
    public static void init() {
    }
}
