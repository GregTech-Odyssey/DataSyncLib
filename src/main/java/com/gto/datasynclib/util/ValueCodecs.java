package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.CustomTypes;
import com.gto.datasynclib.datastream.codec.JavaOps;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.StreamCodec;
import com.gto.datasynclib.datastream.codec.ValueCodec;
import com.gto.datasynclib.datastream.codec.ValueOps;
import com.mojang.serialization.Codec;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import lombok.experimental.UtilityClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.Nullable;

import java.util.function.Function;

/**
 * The Minecraft-typed {@link ValueCodec} constants — the twin of the network half in
 * {@link StreamCodecExtends}, and the replacement for the retired Data codec table. The carrier's own types
 * (the primitives and their arrays, String, UUID, BigInteger, the FastUtil primitive collections)
 * live on {@link ValueCodec} itself, next to the codec they are: these are the types that need a
 * game class to be built, so they live here.
 *
 * <p>Every constant writes exactly the bytes its retired counterpart wrote — a
 * {@code BlockPos} is still a three-int array, a {@code Vec2} still the raw bits of two floats, an
 * NBT tag still a registered custom payload, a {@link Component} still its JSON string — so a field
 * stored before the migration decodes through these unchanged. {@code DataSyncCodec} pairs each of
 * them with its stream half; nothing is registered in a per-codec table.</p>
 *
 * <p>They are hand-written {@code new ValueCodec<>()} implementations rather than
 * {@link ValueCodec#of}: a leaf codec is on the hot path of every field it backs, and {@code of}
 * would put a second, interface-dispatched call behind each {@code encode}/{@code decode}. They box
 * nothing either, since they talk to the carrier's primitives directly — see
 * {@link ValueCodec#composite} for when the convenience is worth more than that.</p>
 *
 * <p>{@link #fromCodec(Codec)} bridges a Mojang DFU codec, which is what the retired {@code Data} bridge
 * used to do — the value it produces is a carrier value, so no conversion
 * happens on a read.</p>
 */
@UtilityClass
public class ValueCodecs {

    /**
     * A registry key as its string, which is what a save file has always held.
     */
    public final ValueCodec<ResourceLocation> RESOURCE_LOCATION = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, ResourceLocation value) {
            return ops.createString(value.toString());
        }

        @Override
        public ResourceLocation decode(ValueOps ops, Object data) {
            return ResourceLocation.parse(ops.getString(data));
        }
    };

    /**
     * A three-int array; {@link #VEC3I} has the same shape.
     */
    public final ValueCodec<BlockPos> BLOCK_POS = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, BlockPos value) {
            return ops.createIntArray(new int[]{value.getX(), value.getY(), value.getZ()});
        }

        @Override
        public BlockPos decode(ValueOps ops, Object data) {
            var array = ops.getIntArray(data);
            return new BlockPos(array[0], array[1], array[2]);
        }
    };

    /**
     * Two floats as their raw bits, so {@code NaN} and {@code -0.0f} survive.
     */
    public final ValueCodec<Vec2> VEC2 = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Vec2 value) {
            return ops.createFloatArray(new float[]{value.x, value.y});
        }

        @Override
        public Vec2 decode(ValueOps ops, Object data) {
            var array = ops.getFloatArray(data);
            return new Vec2(array[0], array[1]);
        }
    };

    /**
     * Three doubles as their raw bits.
     */
    public final ValueCodec<Vec3> VEC3 = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Vec3 value) {
            return ops.createDoubleArray(new double[]{value.x, value.y, value.z});
        }

        @Override
        public Vec3 decode(ValueOps ops, Object data) {
            var array = ops.getDoubleArray(data);
            return new Vec3(array[0], array[1], array[2]);
        }
    };

    /**
     * A chunk position as its packed long.
     */
    public final ValueCodec<ChunkPos> CHUNK_POS = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, ChunkPos value) {
            return ops.createLong(value.toLong());
        }

        @Override
        public ChunkPos decode(ValueOps ops, Object data) {
            return new ChunkPos(ops.getLong(data));
        }
    };

    /**
     * Integer triple in an {@code int[]}; {@link #BLOCK_POS} uses the same shape.
     */
    public final ValueCodec<Vec3i> VEC3I = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Vec3i value) {
            return ops.createIntArray(new int[]{value.getX(), value.getY(), value.getZ()});
        }

        @Override
        public Vec3i decode(ValueOps ops, Object data) {
            var array = ops.getIntArray(data);
            return new Vec3i(array[0], array[1], array[2]);
        }
    };

    /**
     * Section (16³) position as its packed long.
     */
    public final ValueCodec<SectionPos> SECTION_POS = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, SectionPos value) {
            return ops.createLong(value.asLong());
        }

        @Override
        public SectionPos decode(ValueOps ops, Object data) {
            return SectionPos.of(ops.getLong(data));
        }
    };

    /**
     * Six doubles — min then max — in a single primitive-backed array.
     */
    public final ValueCodec<AABB> AABB = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, AABB value) {
            return ops.createDoubleArray(new double[]{value.minX, value.minY, value.minZ, value.maxX, value.maxY, value.maxZ});
        }

        @Override
        public AABB decode(ValueOps ops, Object data) {
            var array = ops.getDoubleArray(data);
            return new AABB(array[0], array[1], array[2], array[3], array[4], array[5]);
        }
    };

    // ---- NBT: a registered custom payload holding the tag itself, no conversion at all ----

    /**
     * A {@link ListTag} as a custom payload. A payload written before the migration holds a
     * converted tree instead, which the decode side still understands through
     * {@link NbtUtil#convertToTag(Object)}.
     */
    public final ValueCodec<ListTag> LIST_TAG = customCodec(NbtUtil.LIST_TAG_TYPE, value -> (ListTag) NbtUtil.convertToTag(value));

    /**
     * See {@link #LIST_TAG}.
     */
    public final ValueCodec<CompoundTag> COMPOUND_TAG = customCodec(NbtUtil.COMPOUND_TAG_TYPE, value -> (CompoundTag) NbtUtil.convertToTag(value));

    /**
     * See {@link #LIST_TAG}.
     */
    public final ValueCodec<Tag> TAG = customCodec(NbtUtil.TAG_TYPE, NbtUtil::convertToTag);

    public final ValueCodec<ItemStack> ITEM_STACK = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, ItemStack value) {
            return COMPOUND_TAG.encode(ops, value.save(new CompoundTag()));
        }

        @Override
        public ItemStack decode(ValueOps ops, Object data) {
            return ItemStack.of(COMPOUND_TAG.decode(ops, data));
        }
    };

    public final ValueCodec<FluidStack> FLUID_STACK = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, FluidStack value) {
            return COMPOUND_TAG.encode(ops, value.writeToNBT(new CompoundTag()));
        }

        @Override
        public FluidStack decode(ValueOps ops, Object data) {
            return FluidStack.loadFluidStackFromNBT(COMPOUND_TAG.decode(ops, data));
        }
    };

    /**
     * A component as its JSON string — the shape the persistence path has always used, which keeps
     * a component readable in a save file.
     */
    public final ValueCodec<Component> COMPONENT = new ValueCodec<>() {

        @Override
        public Object encode(ValueOps ops, Component value) {
            return ops.createString(Component.Serializer.toJson(value));
        }

        @Override
        public Component decode(ValueOps ops, Object data) {
            return Component.Serializer.fromJson(ops.getString(data));
        }
    };

    /**
     * Builds a codec for entries of a Minecraft {@link Registry}: values are written as their
     * registry key (self-describing, stable across game versions), and decoded by looking the key
     * back up.
     *
     * @param registry the registry the values belong to
     */
    public <T> ValueCodec<T> of(Registry<T> registry) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return ops.createString(registry.getKey(value).toString());
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return registry.get(ResourceLocation.parse(ops.getString(data)));
            }
        };
    }

    /**
     * A {@link StreamCodec} seen as a value codec: the wire bytes are stored as one
     * {@code BYTE_ARRAY} value, which is what the retired {@code Data} bridge used to do. Use it
     * for a quick persistence path; a type that is persisted often wants a codec of its own.
     */
    public <T> ValueCodec<T> fromStreamCodec(StreamCodec<? super FriendlyByteBuf, T> streamCodec) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                var buf = Unpooled.buffer();
                try {
                    streamCodec.encode(new FriendlyByteBuf(buf), value);
                    var bytes = new byte[buf.readableBytes()];
                    buf.getBytes(buf.readerIndex(), bytes);
                    return ops.createByteArray(bytes);
                } finally {
                    buf.release();
                }
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                var buf = Unpooled.wrappedBuffer(ops.getByteArray(data));
                try {
                    return streamCodec.decode(new FriendlyByteBuf(buf));
                } finally {
                    buf.release();
                }
            }
        };
    }

    /**
     * A Mojang DFU {@link Codec} seen as a value codec: it runs on {@link JavaOps}, so the carrier of
     * a DFU codec is a carrier value. This replaces
     * the retired {@code DataCodec.of(Codec)}, and it reads the same values that bridge produced.
     */
    public <T> ValueCodec<T> fromCodec(Codec<T> codec) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return codec.encodeStart(JavaOps.INSTANCE, value).result().orElseThrow();
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                return codec.parse(JavaOps.INSTANCE, data).result().orElseThrow();
            }
        };
    }

    /**
     * Reads one stored NBT tag from either side of the migration. Called when a payload is not this
     * build's {@code CUSTOM}-wrapped tag:
     * <ul>
     *   <li>the retired plain tag encoding — a plain NBT type byte ({@code 1…12}) followed by
     *       {@code name + payload};</li>
     *   <li>otherwise the converted carrier tree an older build wrote, which
     *       {@link NbtUtil#convertToTag(Object)} rebuilds.</li>
     * </ul>
     *
     * <p>The two cannot be confused: the retired encoding starts with an NBT type id
     * ({@code 1…12}), while a converted tree starts with one of the carrier's own container ids —
     * {@code LIST} (13), {@code STRING_MAP} (14), {@code OBJECT_MAP} (16) and friends — or with the
     * null id, all of which are outside that range.</p>
     *
     * @param ops  the carrier the value came from
     * @param data the stored value, which is not this build's custom tag payload
     */
    @Nullable
    private Tag readStoredTag(ValueOps ops, Object data) {
        if (ops.isNull(data)) return null;
        var id = ops.getTypeId(data);
        if (id >= Tag.TAG_BYTE && id <= Tag.TAG_LONG_ARRAY) {
            // the retired encoding: the tag's own bytes, name included, with no envelope
            var bytes = ops.toBytes(data);
            var payload = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, payload, 0, payload.length);
            return NbtUtil.readLegacy(id, payload);
        }
        return NbtUtil.convertToTag(data);
    }

    /**
     * The shared body of the three NBT codecs: encode the tag as a custom payload of {@code type},
     * and on decode take it back out — or, for a payload from before the migration, read the shape it
     * was stored in with {@link #readStoredTag(ValueOps, Object)}.
     */
    @SuppressWarnings("unchecked")
    private <T> ValueCodec<T> customCodec(CustomTypes.Type<T> type, Function<Object, T> fromTree) {
        return new ValueCodec<>() {

            @Override
            public Object encode(ValueOps ops, T value) {
                return ops.createCustom(type.id(), value);
            }

            @Override
            public T decode(ValueOps ops, Object data) {
                // one pattern match on the carrier value: isCustom + getCustomId + getCustom would ask
                // the same question three times
                if (data instanceof JavaValueOps.Custom(int typeId, Object value) && typeId == type.id()) {
                    return (T) value;
                }
                var tag = readStoredTag(ops, data);
                return tag == null ? fromTree.apply(data) : (T) tag;
            }
        };
    }
}
