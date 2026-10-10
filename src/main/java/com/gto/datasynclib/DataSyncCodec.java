package com.gto.datasynclib;

import com.gto.datasynclib.datastream.codec.*;
import com.gto.datasynclib.util.ByteBufCodecExtends;
import com.gto.datasynclib.util.EnumUtil;
import com.gto.datasynclib.util.HashUtil;
import com.gto.datasynclib.util.ValueCodecs;
import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.LongList;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.core.*;
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
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;

/**
 * Unified codec registry that pairs network stream codecs with persistent value codecs.
 *
 * <p>Each {@code DataSyncCodec} combines three functional components:
 * <ul>
 *   <li>{@link #streamWriter} / {@link #streamReader} — for live network synchronization via {@link FriendlyByteBuf}</li>
 *   <li>{@link #valueWriter} / {@link #valueReader} — for persistent storage on the native carrier, which
 *       {@link com.gto.datasynclib.datastream.codec.JavaValueOps#toBytes(Object)} turns into the id+payload
 *       bytes a save file holds</li>
 *
 * </ul>
 *
 * <p>The registry supports both direct type-to-codec mappings ({@link #CODECS}) and
 * generic type mappings ({@link #GENERIC_CODECS}) for parameterized types.
 * Enum codecs and object-array codecs are auto-generated on first access via
 * {@link #ENUM_CACHE} and {@link #ARRAY_CACHE}.
 *
 * <p>Pre-registered codecs cover Java primitives and their arrays, String, UUID, BigInteger,
 * the FastUtil {@code IntList}/{@code IntSet}/
 * {@code LongList}/{@code LongSet} collections, and Minecraft types: Item, Block,
 * Fluid, EntityType, BlockEntityType, MobEffect, Enchantment, SoundEvent, Attribute,
 * ParticleType, MenuType, RecipeType, ItemStack, FluidStack, ResourceLocation, Vec2, Vec3,
 * Vec3i, BlockPos, ChunkPos, SectionPos, GlobalPos, Tag, CompoundTag, ListTag, Component,
 * BlockState and AABB. They all register themselves while this class is initialized, except the
 * primitive keys ({@code int.class} and friends), which {@link #init()} adds during mod
 * construction.</p>
 *
 * <p>Every disk half is a {@link ValueCodec} — {@link ValueCodecs} holds the constants, and they write
 * exactly the bytes the retired Data type system wrote, so a save from before the migration still
 * reads.</p>
 *
 * <p>Types whose payload is a handful of primitives (Vec3i, SectionPos, AABB) use hand-written
 * codec pairs instead of {@link CombinedCodec#composite} to avoid boxing; the FastUtil primitive
 * collections are hand-written for the same reason — a {@code collection(...)} codec would box every
 * element. See that method for the trade-off.</p>
 *
 * <p>Downstream mods add their own types with the static {@code register(...)} family —
 * {@link #register(Class, StreamCodec, ValueCodec)} for a plain runtime type,
 * {@link #register(Class, ValueCodec)} for one whose network form is the same bytes,
 * {@link #register(Class, StreamCodec, ValueCodec)} for a plain runtime type and
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

    /**
     * Auto-generated codecs for enum types. A {@link ClassValue} keeps the entry on the class
     * itself — no class-keyed map pinning mod classes, and a {@code get} without a map lookup.
     */
    private static final ClassValue<DataSyncCodec<?>> ENUM_CACHE = new ClassValue<>() {
        @Override
        protected DataSyncCodec<?> computeValue(Class<?> t) {
            if (t.isEnum()) {
                var constants = t.getEnumConstants();
                var len = constants.length;
                var isFixed = EnumUtil.isFixed((Class<? extends Enum<?>>) t);
                return of(new StreamCodec<>() {

                              @Override
                              public void encode(FriendlyByteBuf buf, Enum<?> obj) {
                                  buf.writeVarInt(obj.ordinal());
                              }

                              @Override
                              public Enum<?> decode(FriendlyByteBuf buf) {
                                  return (Enum<?>) constants[buf.readVarInt()];
                              }
                          },
                        new ValueCodec<Enum<?>>() {

                            @Override
                            public Object encode(ValueOps ops, Enum<?> value) {
                                // a fixed enum is stored by ordinal, the others by name, so an update
                                // that reorders the constants only breaks the fixed ones
                                return isFixed ? ops.createInt(value.ordinal()) : ops.createString(EnumUtil.getSerializedName(value));
                            }

                            @Override
                            public Enum<?> decode(ValueOps ops, Object data) {
                                if (ops.isInt(data)) {
                                    var value = ops.getInt(data);
                                    return value < len ? (Enum<?>) constants[value] : null;
                                }
                                return EnumUtil.getSerializedEnum((Class) t, ops.getString(data));
                            }
                        });
            }
            throw new RuntimeException("No codec registered for type " + t);
        }
    };

    /**
     * Auto-generated codecs for object (non-primitive) arrays. A {@link ClassValue} for the same
     * reason as {@link #ENUM_CACHE}.
     *
     * <p>A zero-length array has nothing to store, so it is written as the carrier's null value —
     * the shape the retired type system used — and a null-valued slot is kept as that same null
     * value. On the way back anything that is not a list decodes to a zero-length array and a
     * null-valued element decodes to a {@code null} slot, so an array with empty slots reads back as
     * it was written and the element codec is never handed a value it was not written for.</p>
     */
    private static final ClassValue<DataSyncCodec<?>> ARRAY_CACHE = new ClassValue<>() {
        @Override
        @SuppressWarnings("unchecked")
        protected DataSyncCodec<?> computeValue(Class<?> t) {
            if (t.isArray() && !t.componentType().isPrimitive()) {
                Class<?> type = t.getComponentType();
                // Fully qualified on purpose: inside an anonymous ClassValue an unqualified get(...)
                // would bind to the inherited ClassValue.get (i.e. recurse into this very cache), and
                // an explicit type witness would pick the varargs get(Class, Class...) overload and
                // silently return null.
                DataSyncCodec<Object> codec = (DataSyncCodec<Object>) DataSyncCodec.get(type);
                return of(new StreamCodec<>() {

                              @Override
                              public void encode(FriendlyByteBuf buf, Object[] obj) {
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
                              }

                              @Override
                              public Object[] decode(FriendlyByteBuf buf) {
                                  var decoder = codec.streamReader;
                                  var length = buf.readVarInt();
                                  var array = (Object[]) Array.newInstance(type, length);
                                  for (int i = 0; i < length; i++) {
                                      if (buf.readBoolean()) array[i] = decoder.decode(buf);
                                  }
                                  return array;
                              }
                          },
                        new ValueCodec<Object[]>() {

                            @Override
                            public Object encode(ValueOps ops, Object[] value) {
                                if (value.length == 0) return ops.createNull();
                                var list = new ArrayList<>(value.length);
                                for (var element : value) {
                                    list.add(ops.isNull(element) ? ops.createNull() : codec.encode(ops, element));
                                }
                                return ops.createList(list);
                            }

                            @Override
                            public Object[] decode(ValueOps ops, Object data) {
                                // the empty array this codec writes, or a payload that is not a list
                                // at all: both are a zero-length array, never a failure
                                if (ops.isNull(data)) return (Object[]) Array.newInstance(type, 0);
                                var list = ops.getList(data);
                                var size = list.size();
                                var array = (Object[]) Array.newInstance(type, size);
                                for (int i = 0; i < size; i++) {
                                    var element = list.get(i);
                                    // an empty slot stays a null slot instead of reaching the element
                                    // codec, which was never written for a value that is not there
                                    array[i] = ops.isNull(element) ? null : codec.decode(ops, element);
                                }
                                return array;
                            }
                        });
            }
            throw new RuntimeException("No codec registered for type " + t);
        }
    };

    /**
     * Encoder for live network synchronization via {@link FriendlyByteBuf}.
     */
    public final StreamEncoder<? super FriendlyByteBuf, ? super T> streamWriter;
    /**
     * Decoder for live network synchronization via {@link FriendlyByteBuf}.
     */
    public final StreamDecoder<? super FriendlyByteBuf, ? extends T> streamReader;
    /**
     * Encoder for persistent storage, on the native carrier: what this writes is what
     * {@link ValueOps#writeValue(Object, ByteBuf)} stores as the field's id and payload.
     */
    public final ValueEncoder<? super T> valueWriter;
    /**
     * Decoder for persistent storage, on the native carrier.
     */
    public final ValueDecoder<? extends T> valueReader;
    /**
     * The disk codec this was built from, so {@link #toValueCodec()} can hand it back untouched;
     * {@code null} when the disk half is the pair of writers above.
     */
    @Nullable
    private final ValueCodec<T> valueCodec;

    private DataSyncCodec(StreamEncoder<? super FriendlyByteBuf, ? super T> streamWriter,
                          StreamDecoder<? super FriendlyByteBuf, ? extends T> streamReader,
                          ValueEncoder<? super T> valueWriter,
                          ValueDecoder<? extends T> valueReader,
                          @Nullable ValueCodec<T> valueCodec) {
        this.streamWriter = streamWriter;
        this.streamReader = streamReader;
        this.valueWriter = valueWriter;
        this.valueReader = valueReader;
        this.valueCodec = valueCodec;
    }

    @Override
    public ValueCodec<T> toValueCodec() {
        // a ValueCodec that was supplied is the disk half itself, so hand it back rather than this
        return valueCodec != null ? valueCodec : this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public StreamCodec<? super FriendlyByteBuf, T> toStreamCodec() {
        // streamWriter == streamReader means the same StreamCodec was supplied; return it directly.
        return streamWriter == streamReader ? (StreamCodec<? super FriendlyByteBuf, T>) streamWriter : this;
    }

    /**
     * Returns this codec: it is already combined, so the instance-mirror default (which would wrap
     * the two halves into a fresh {@code DataSyncCodec}) has nothing to add.
     */
    @Override
    public DataSyncCodec<T> toDataSyncCodec() {
        return this;
    }

    // ===== StreamCodec implementation =====

    @Override
    public void encode(FriendlyByteBuf buf, T obj) {
        streamWriter.encode(buf, obj);
    }

    @Override
    public T decode(FriendlyByteBuf buf) {
        return streamReader.decode(buf);
    }

    // ===== ValueCodec implementation — the persistence half =====

    @Override
    public Object encode(ValueOps ops, T obj) {
        return valueWriter.encode(ops, obj);
    }

    @Override
    public T decode(ValueOps ops, Object data) {
        return valueReader.decode(ops, data);
    }


    /**
     * Creates a codec from a network codec and a disk codec — the pair every combined codec is built
     * on: {@link #streamWriter}/{@link #streamReader} for the wire, {@link #valueWriter}/{@link #valueReader}
     * for the id+payload bytes a save file holds.
     *
     * @param streamCodec bidirectional network serializer
     * @param valueCodec  bidirectional persistence serializer
     */
    public static <T> DataSyncCodec<T> of(StreamCodec<? super FriendlyByteBuf, T> streamCodec, ValueCodec<T> valueCodec) {
        return new DataSyncCodec<>(streamCodec, streamCodec, valueCodec, valueCodec, valueCodec);
    }

    /**
     * Creates a codec from a native disk codec, deriving the stream codec by writing its id+payload
     * bytes inline ({@link ByteBufCodecs#fromValueCodec}).
     */
    public static <T> DataSyncCodec<T> of(ValueCodec<T> valueCodec) {
        return of(ByteBufCodecs.fromValueCodec(valueCodec), valueCodec);
    }

    /**
     * Creates a codec from a network stream codec, <strong>automatically deriving</strong> the disk
     * codec by storing the stream bytes as a byte-array value.
     *
     * <p>Use when you have a working network codec and want a quick persistence path without writing a
     * separate disk codec: the stored form is one {@code BYTE_ARRAY} value holding the wire bytes.</p>
     */
    public static <T> DataSyncCodec<T> of(StreamCodec<? super FriendlyByteBuf, T> streamCodec) {
        return of(streamCodec, ValueCodec.fromStreamCodec(streamCodec));
    }

    public static <T extends Enum<T>> DataSyncCodec<T> ofEnum(Class<T> enumClass) {
        return (DataSyncCodec<T>) ENUM_CACHE.get(enumClass);
    }

    public static <T> DataSyncCodec<T> ofArray(Class<T> componentClass) {
        return (DataSyncCodec<T>) ARRAY_CACHE.get(componentClass);
    }

    /**
     * Checks whether a codec can be resolved for the given type.
     *
     * <p>This mirrors {@link #get(Class)}: a primitive resolves through the wrapper codec registered
     * under its own key, and enum and object-array types return {@code true} even without an explicit
     * registration because their codec is generated on demand.</p>
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
     * <p>A primitive type is an ordinary key here, not a special case: {@link #init()} registers each
     * one under its own class (the same codec instance as its wrapper), so {@code get(int.class)} is a
     * plain map hit with no per-call conversion. {@code void} has no codec and returns {@code null}.</p>
     *
     * @param type the class type
     * @return the registered or auto-generated codec, or {@code null} if this exact type is not
     * registered
     * @throws IllegalArgumentException if {@code type} is {@code null} — normally an unresolved
     *                                  wildcard or type variable in a field declaration
     */
    @Nullable
    public static <T> DataSyncCodec<T> get(Class<T> type) {
        if (type == null)
            throw new IllegalArgumentException("Cannot resolve a codec for a null type (unresolved wildcard or type variable?)");
        DataSyncCodec<?> codec;
        if (type.isEnum()) {
            codec = ENUM_CACHE.get(type);
        } else if (type.isArray() && !type.componentType().isPrimitive()) {
            codec = ARRAY_CACHE.get(type);
        } else {
            // Covers primitives too: int.class is put next to Integer.class at registration time.
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
    public DataSyncCodec<T> register(Class<T> type) {
        synchronized (CODECS) {
            CODECS.put(type, this);
        }
        return this;
    }

    /**
     * Registers a codec from a network codec and a disk codec — the registration form of
     * {@link #of(StreamCodec, ValueCodec)}.
     *
     * @param type        the class to register for (exact match, not assignable-from)
     * @param streamCodec encoder/decoder for network buffers
     * @param valueCodec  encoder/decoder for the id+payload bytes a save file holds
     * @param <T>         the type
     * @return the registered codec (also returned by future {@link #get(Class)} calls)
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, StreamCodec<? super FriendlyByteBuf, T> streamCodec, ValueCodec<T> valueCodec) {
        var codec = of(streamCodec, valueCodec);
        synchronized (CODECS) {
            CODECS.put(type, codec);
        }
        return codec;
    }

    /**
     * Registers a codec from a disk codec, deriving the stream codec automatically
     * ({@link #of(ValueCodec)}).
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, ValueCodec<T> valueCodec) {
        return register(type, ByteBufCodecs.fromValueCodec(valueCodec), valueCodec);
    }

    /**
     * Registers a codec from a stream codec and a Mojang {@link Codec}, deriving the disk half
     * automatically ({@link ValueCodecs#fromCodec(Codec)}).
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, StreamCodec<? super FriendlyByteBuf, T> streamCodec, Codec<T> codec) {
        return register(type, streamCodec, ValueCodecs.fromCodec(codec));
    }

    /**
     * Registers a codec for a Minecraft registry type: the wire form is the registry id
     * ({@link ByteBufCodecExtends#of(Registry)}) and the stored form the registry key
     * ({@link ValueCodecs#of(Registry)}).
     */
    public static <T> DataSyncCodec<T> register(Class<T> type, Registry<T> registry) {
        return register(type, ByteBufCodecExtends.of(registry), ValueCodecs.of(registry));
    }

    /**
     * Registers a codec for a parameterized (generic) type.
     *
     * <p>Lookup goes through {@link #get(Class, Class[])} and the generic field factory, which
     * pass the field's resolved argument classes. Arguments are matched by identity, so pass the
     * very {@link Class} instances that appear in the field declaration.</p>
     *
     * @param type         the raw class type
     * @param streamCodec  encoder/decoder for network buffers
     * @param valueCodec   encoder/decoder for the id+payload bytes a save file holds
     * @param genericTypes the generic type parameters, in declaration order
     * @param <T>          the type
     * @return the registered codec
     */
    public static <T> DataSyncCodec<T> register(Class<?> type, StreamCodec<? super FriendlyByteBuf, T> streamCodec, ValueCodec<T> valueCodec, Class<?>... genericTypes) {
        var codec = of(streamCodec, valueCodec);
        synchronized (GENERIC_CODECS) {
            GENERIC_CODECS.computeIfAbsent(type, k -> new HashMap<>()).put(HashUtil.arrayIdentityWrapper(genericTypes), codec);
        }
        return codec;
    }
    // ===== Pre-registered codec constants =====


    // ---- primitive arrays ----
    public static final DataSyncCodec<boolean[]> BOOLEANS_CODEC = register(boolean[].class, ByteBufCodecs.BOOLEAN_ARRAY, ValueCodec.BOOLEAN_ARRAY);
    public static final DataSyncCodec<byte[]> BYTES_CODEC = register(byte[].class, ByteBufCodecs.BYTE_ARRAY, ValueCodec.BYTE_ARRAY);
    public static final DataSyncCodec<int[]> INTS_CODEC = register(int[].class, ByteBufCodecs.VAR_INT_ARRAY, ValueCodec.INT_ARRAY);
    public static final DataSyncCodec<long[]> LONGS_CODEC = register(long[].class, ByteBufCodecs.LONG_ARRAY, ValueCodec.LONG_ARRAY);
    public static final DataSyncCodec<float[]> FLOATS_CODEC = register(float[].class, ByteBufCodecs.FLOAT_ARRAY, ValueCodec.FLOAT_ARRAY);
    public static final DataSyncCodec<double[]> DOUBLES_CODEC = register(double[].class, ByteBufCodecs.DOUBLE_ARRAY, ValueCodec.DOUBLE_ARRAY);
    public static final DataSyncCodec<short[]> SHORTS_CODEC = register(short[].class, ByteBufCodecs.SHORT_ARRAY, ValueCodec.SHORT_ARRAY);
    public static final DataSyncCodec<char[]> CHARS_CODEC = register(char[].class, ByteBufCodecs.CHAR_ARRAY, ValueCodec.CHAR_ARRAY);

    // ---- FastUtil primitive collections (hand-written pairs: the primitives never box) ----
    public static final DataSyncCodec<IntList> INT_LIST_CODEC = register(IntList.class, ByteBufCodecs.INT_LIST, ValueCodec.INT_LIST);
    public static final DataSyncCodec<IntSet> INT_SET_CODEC = register(IntSet.class, ByteBufCodecs.INT_SET, ValueCodec.INT_SET);
    public static final DataSyncCodec<LongList> LONG_LIST_CODEC = register(LongList.class, ByteBufCodecs.LONG_LIST, ValueCodec.LONG_LIST);
    public static final DataSyncCodec<LongSet> LONG_SET_CODEC = register(LongSet.class, ByteBufCodecs.LONG_SET, ValueCodec.LONG_SET);

    // ---- boxed primitives, String, UUID, BigInteger ----
    public static final DataSyncCodec<Boolean> BOOLEAN_CODEC = register(Boolean.class, ByteBufCodecs.BOOL, ValueCodec.BOOLEAN);

    public static final DataSyncCodec<Byte> BYTE_CODEC = register(Byte.class, ByteBufCodecs.BYTE, ValueCodec.BYTE);

    public static final DataSyncCodec<Short> SHORT_CODEC = register(Short.class, ByteBufCodecs.SHORT, ValueCodec.SHORT);

    public static final DataSyncCodec<Integer> INT_CODEC = register(Integer.class, ByteBufCodecs.VAR_INT, ValueCodec.INT);

    public static final DataSyncCodec<Long> LONG_CODEC = register(Long.class, ByteBufCodecs.LONG, ValueCodec.LONG);

    public static final DataSyncCodec<Float> FLOAT_CODEC = register(Float.class, ByteBufCodecs.FLOAT, ValueCodec.FLOAT);

    public static final DataSyncCodec<Double> DOUBLE_CODEC = register(Double.class, ByteBufCodecs.DOUBLE, ValueCodec.DOUBLE);

    public static final DataSyncCodec<Character> CHAR_CODEC = register(Character.class, ByteBufCodecs.CHAR, ValueCodec.CHAR);

    public static final DataSyncCodec<String> STRING_CODEC = register(String.class, ByteBufCodecs.STRING_UTF8, ValueCodec.STRING);

    public static final DataSyncCodec<UUID> UUID_CODEC = register(UUID.class, ByteBufCodecs.UUID, ValueCodec.UUID);

    public static final DataSyncCodec<BigInteger> BIG_INTEGER_CODEC = register(BigInteger.class, ByteBufCodecs.BIG_INTEGER, ValueCodec.BIG_INTEGER);

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
    public static final DataSyncCodec<ResourceLocation> RESOURCE_LOCATION_CODEC = register(ResourceLocation.class, ByteBufCodecExtends.RESOURCE_LOCATION_CODEC, ValueCodecs.RESOURCE_LOCATION);

    public static final DataSyncCodec<Vec2> VEC2_CODEC = register(Vec2.class, ByteBufCodecExtends.VEC2_CODEC, ValueCodecs.VEC2);
    public static final DataSyncCodec<Vec3> VEC3_CODEC = register(Vec3.class, ByteBufCodecExtends.VEC3_CODEC, ValueCodecs.VEC3);
    public static final DataSyncCodec<BlockPos> BLOCK_POS_CODEC = register(BlockPos.class, ByteBufCodecExtends.BLOCK_POS_CODEC, ValueCodecs.BLOCK_POS);
    public static final DataSyncCodec<ChunkPos> CHUNK_POS_CODEC = register(ChunkPos.class, ByteBufCodecExtends.CHUNK_POS_CODEC, ValueCodecs.CHUNK_POS);

    /**
     * Integer triple — hand-written pair (three VarInts on the wire, one {@code int[]} on
     * disk), so no boxing happens on either path. {@link BlockPos} has its own (more compact)
     * codec and still wins by exact match.
     */
    public static final DataSyncCodec<Vec3i> VEC3I_CODEC = register(Vec3i.class, ByteBufCodecExtends.VEC3I_CODEC, ValueCodecs.VEC3I);

    /**
     * Section (16³ chunk section) position — hand-written pair carrying the packed long.
     */
    public static final DataSyncCodec<SectionPos> SECTION_POS_CODEC = register(SectionPos.class, ByteBufCodecExtends.SECTION_POS_CODEC, ValueCodecs.SECTION_POS);

    /**
     * Axis-aligned box — hand-written pair of six raw doubles rather than a
     * {@link CombinedCodec#composite} of {@code double} components, which would box every
     * coordinate on each encode/decode.
     */
    public static final DataSyncCodec<AABB> AABB_CODEC = register(AABB.class, ByteBufCodecExtends.AABB_CODEC, ValueCodecs.AABB);

    /**
     * Dimension + block position; the dimension is carried as its {@link ResourceLocation}.
     */
    public static final DataSyncCodec<GlobalPos> GLOBAL_POS_CODEC = registerComposed(GlobalPos.class, CombinedCodec.composite(
            RESOURCE_LOCATION_CODEC, p -> p.dimension().location(),
            BLOCK_POS_CODEC, GlobalPos::pos,
            (location, pos) -> GlobalPos.of(ResourceKey.create(Registries.DIMENSION, location), pos)));

    // ---- NBT ----
    public static final DataSyncCodec<Tag> TAG_CODEC = register(Tag.class, ByteBufCodecExtends.TAG_CODEC, ValueCodecs.TAG);
    public static final DataSyncCodec<CompoundTag> COMPOUND_TAG_CODEC = register(CompoundTag.class, ByteBufCodecExtends.COMPOUND_TAG_CODEC, ValueCodecs.COMPOUND_TAG);
    public static final DataSyncCodec<ListTag> LIST_TAG_CODEC = register(ListTag.class, ByteBufCodecExtends.LIST_TAG_CODEC, ValueCodecs.LIST_TAG);

    // ---- stacks and components ----
    public static final DataSyncCodec<ItemStack> ITEM_STACK_CODEC = register(ItemStack.class, ByteBufCodecExtends.ITEM_STACK_CODEC, ValueCodecs.ITEM_STACK);
    public static final DataSyncCodec<FluidStack> FLUID_STACK_CODEC = register(FluidStack.class, ByteBufCodecExtends.FLUID_STACK_CODEC, ValueCodecs.FLUID_STACK);

    public static final DataSyncCodec<Component> COMPONENT_CODEC = register(Component.class, ByteBufCodecExtends.COMPONENT_CODEC, ValueCodecs.COMPONENT);

    public static final DataSyncCodec<BlockState> BLOCK_STATE_CODEC = register(BlockState.class, ByteBufCodecs.fromCodec(BlockState.CODEC), ValueCodecs.fromCodec(BlockState.CODEC));

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
     * Registers the primitive aliases of the pre-registered wrapper codecs — {@code int.class} and
     * {@link Integer} then resolve to the same codec, so {@link #get(Class)} needs no conversion and a
     * primitive lookup is an ordinary map hit.
     *
     * <p>The constants themselves register in this class's initializer; the primitives are added here
     * because a class constant cannot know it is the wrapper of a primitive. {@code DataSyncLib} calls
     * this first thing in mod construction, before anything can scan a field or a remote method — a
     * lookup that happens earlier (a static initializer of a downstream mod, say) still resolves the
     * wrapper, just not the primitive key.</p>
     */
    public static void init() {
        BOOLEAN_CODEC.register(boolean.class);
        BYTE_CODEC.register(byte.class);
        CHAR_CODEC.register(char.class);
        SHORT_CODEC.register(short.class);
        INT_CODEC.register(int.class);
        LONG_CODEC.register(long.class);
        FLOAT_CODEC.register(float.class);
        DOUBLE_CODEC.register(double.class);
    }

}
