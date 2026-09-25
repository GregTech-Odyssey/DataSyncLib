package com.gto.datasynclib.util;

import com.gto.datasynclib.RegistryContext;
import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.data.Data;
import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.UUID;
import lombok.experimental.UtilityClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Pre-registered {@link StreamCodec} instances for Minecraft types: ResourceLocation,
 * BlockPos, ChunkPos, Vec3i, SectionPos, Vec2, Vec3, AABB, Tag, CompoundTag, ListTag, ItemStack,
 * FluidStack and Component, plus {@link #of(net.minecraft.core.Registry)} for registry entries
 * (encoded as a VarInt id) and the primitive-array codecs the {@link DataSyncCodec} table needs.
 *
 * <p>These are hand-written on purpose: they read and write primitives straight on the buffer
 * instead of going through the boxed components of {@link com.gto.datasynclib.datastream.codec.CombinedCodec#composite}.
 * See that method's documentation for the trade-off.</p>
 *
 * <p>Where Minecraft already ships a matching codec ({@code ResourceLocation.STREAM_CODEC},
 * {@code ItemStack.OPTIONAL_STREAM_CODEC}, {@code ComponentSerialization.STREAM_CODEC}, ...) the
 * constant simply re-exposes it.</p>
 */
@UtilityClass
public class StreamCodecs {

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ResourceLocation> RESOURCE_LOCATION_CODEC = ResourceLocation.STREAM_CODEC;

    public static final StreamCodec<? super RegistryFriendlyByteBuf, BlockPos> BLOCK_POS_CODEC = BlockPos.STREAM_CODEC;

    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkPos> CHUNK_POS_CODEC = StreamCodec.of((stream, obj) -> {
        stream.writeLong(obj.toLong());
    }, stream -> new ChunkPos(stream.readLong()));

    /**
     * Integer triple, three VarInts. {@link BlockPos} keeps its own packed-long codec, and an
     * exact registration always wins over this one.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, Vec3i> VEC3I_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf stream, Vec3i obj) {
            stream.writeVarInt(obj.getX());
            stream.writeVarInt(obj.getY());
            stream.writeVarInt(obj.getZ());
        }

        @Override
        public Vec3i decode(RegistryFriendlyByteBuf stream) {
            return new Vec3i(stream.readVarInt(), stream.readVarInt(), stream.readVarInt());
        }
    };

    /**
     * Section (16³) position, as its packed long.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, SectionPos> SECTION_POS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf stream, SectionPos obj) {
            stream.writeLong(obj.asLong());
        }

        @Override
        public SectionPos decode(RegistryFriendlyByteBuf stream) {
            return SectionPos.of(stream.readLong());
        }
    };

    /**
     * Six raw doubles: minX, minY, minZ, maxX, maxY, maxZ.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, AABB> AABB_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf stream, AABB obj) {
            stream.writeDouble(obj.minX);
            stream.writeDouble(obj.minY);
            stream.writeDouble(obj.minZ);
            stream.writeDouble(obj.maxX);
            stream.writeDouble(obj.maxY);
            stream.writeDouble(obj.maxZ);
        }

        @Override
        public AABB decode(RegistryFriendlyByteBuf stream) {
            return new AABB(stream.readDouble(), stream.readDouble(), stream.readDouble(),
                    stream.readDouble(), stream.readDouble(), stream.readDouble());
        }
    };


    public static final StreamCodec<RegistryFriendlyByteBuf, Vec2> VEC2_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf stream, Vec2 obj) {
            stream.writeFloat(obj.x);
            stream.writeFloat(obj.y);
        }

        @Override
        public Vec2 decode(RegistryFriendlyByteBuf stream) {
            return new Vec2(stream.readFloat(), stream.readFloat());
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, Vec3> VEC3_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf stream, Vec3 obj) {
            stream.writeDouble(obj.x);
            stream.writeDouble(obj.y);
            stream.writeDouble(obj.z);
        }

        @Override
        public Vec3 decode(RegistryFriendlyByteBuf stream) {
            return new Vec3(stream.readDouble(), stream.readDouble(), stream.readDouble());
        }
    };


    public static final StreamCodec<RegistryFriendlyByteBuf, Tag> TAG_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf stream, Tag obj) {
            stream.writeByte(obj.getId());
            NbtUtil.write(obj, stream);
        }

        @Override
        public Tag decode(RegistryFriendlyByteBuf stream) {
            return NbtUtil.read(stream.readByte(), stream);
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, CompoundTag> COMPOUND_TAG_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf stream, CompoundTag obj) {
            NbtUtil.write(obj, stream);
        }

        @Override
        public CompoundTag decode(RegistryFriendlyByteBuf stream) {
            return (CompoundTag) NbtUtil.read(Tag.TAG_COMPOUND, stream);
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, ListTag> LIST_TAG_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf stream, ListTag obj) {
            NbtUtil.write(obj, stream);
        }

        @Override
        public ListTag decode(RegistryFriendlyByteBuf stream) {
            return (ListTag) NbtUtil.read(Tag.TAG_LIST, stream);
        }
    };

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ItemStack> ITEM_STACK_CODEC = ItemStack.OPTIONAL_STREAM_CODEC;

    public static final StreamCodec<? super RegistryFriendlyByteBuf, FluidStack> FLUID_STACK_CODEC = FluidStack.OPTIONAL_STREAM_CODEC;

    public static final StreamCodec<? super RegistryFriendlyByteBuf, Component> COMPONENT_CODEC = ComponentSerialization.STREAM_CODEC;

    /**
     * Builds a codec for entries of a Minecraft {@link Registry}: values are written as a VarInt
     * registry id, which is compact but only valid between peers whose registries match.
     *
     * @param registry the registry the values belong to
     */
    public static <T> StreamCodec<RegistryFriendlyByteBuf, T> of(Registry<T> registry) {
        return StreamCodec.of((stream, obj) -> stream.writeVarInt(registry.getId(obj)), stream -> registry.byId(stream.readVarInt()));
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, boolean[]> BOOLEANS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf buf, boolean[] obj) {
            buf.writeVarInt(obj.length);
            for (var b : obj) {
                buf.writeBoolean(b);
            }
        }

        @Override
        public boolean[] decode(RegistryFriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var booleans = new boolean[length];
            for (int i = 0; i < length; i++) {
                booleans[i] = buf.readBoolean();
            }
            return booleans;
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, byte[]> BYTES_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf buf, byte[] obj) {
            buf.writeByteArray(obj);
        }

        @Override
        public byte[] decode(RegistryFriendlyByteBuf buf) {
            return buf.readByteArray();
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, int[]> INTS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf buf, int[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeVarInt(i);
            }
        }

        @Override
        public int[] decode(RegistryFriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var ints = new int[length];
            for (int i = 0; i < length; i++) {
                ints[i] = buf.readVarInt();
            }
            return ints;
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, long[]> LONGS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf buf, long[] obj) {
            buf.writeLongArray(obj);
        }

        @Override
        public long[] decode(RegistryFriendlyByteBuf buf) {
            int length = buf.readVarInt();
            long[] values = new long[length];
            for (int i = 0; i < length; i++) values[i] = buf.readLong();
            return values;
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, short[]> SHORTS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf buf, short[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeShort(i);
            }
        }

        @Override
        public short[] decode(RegistryFriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var shorts = new short[length];
            for (int i = 0; i < length; i++) {
                shorts[i] = buf.readShort();
            }
            return shorts;
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, char[]> CHARS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf buf, char[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeChar(i);
            }
        }

        @Override
        public char[] decode(RegistryFriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var chars = new char[length];
            for (int i = 0; i < length; i++) {
                chars[i] = buf.readChar();
            }
            return chars;
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, float[]> FLOATS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf buf, float[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeFloat(i);
            }
        }

        @Override
        public float[] decode(RegistryFriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var floats = new float[length];
            for (int i = 0; i < length; i++) {
                floats[i] = buf.readFloat();
            }
            return floats;
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, double[]> DOUBLES_CODEC = new StreamCodec<>() {

        @Override
        public void encode(RegistryFriendlyByteBuf buf, double[] obj) {
            buf.writeVarInt(obj.length);
            for (var i : obj) {
                buf.writeDouble(i);
            }
        }

        @Override
        public double[] decode(RegistryFriendlyByteBuf buf) {
            var length = buf.readVarInt();
            var doubles = new double[length];
            for (int i = 0; i < length; i++) {
                doubles[i] = buf.readDouble();
            }
            return doubles;
        }
    };

    public static final StreamCodec<RegistryFriendlyByteBuf, Long> LONG_CODEC = StreamCodec.of((buf, value) -> buf.writeLong(value), buf -> buf.readLong());

    public static final StreamCodec<RegistryFriendlyByteBuf, Character> CHAR_CODEC = StreamCodec.of((buf, value) -> buf.writeChar(value), buf -> buf.readChar());

    public static final StreamCodec<RegistryFriendlyByteBuf, UUID> UUID_CODEC = StreamCodec.of((buf, value) -> buf.writeUUID(value), buf -> buf.readUUID());

    public static final StreamCodec<RegistryFriendlyByteBuf, BigInteger> BIG_INTEGER_CODEC = StreamCodec.of((buf, value) -> buf.writeByteArray(value.toByteArray()), buf -> new BigInteger(buf.readByteArray()));

    /**
     * Bridge persistent {@link Data} into a stream: the payload keeps the disk layout, so the
     * receiving side needs the same registry context. Only this boundary has to establish a
     * {@link RegistryContext} — the buffer carries the registry access on the network.
     */
    public static <T> StreamCodec<RegistryFriendlyByteBuf, T> fromData(DataCodec<T> codec) {
        return StreamCodec.of((buf, value) -> {
            try (var ignored = RegistryContext.use(buf.registryAccess())) {
                Data.writeData(buf, codec.encode(value));
            }
        }, buf -> {
            try (var ignored = RegistryContext.use(buf.registryAccess())) {
                return codec.decode(Data.readData(buf));
            }
        });
    }

    /**
     * Object array codec: a VarInt length followed by that many elements.
     */
    public static <T> StreamCodec<RegistryFriendlyByteBuf, T[]> array(Class<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        return StreamCodec.of((buf, values) -> {
            buf.writeVarInt(values.length);
            for (T value : values) {
                codec.encode(buf, value);
            }
        }, buf -> {
            int size = buf.readVarInt();
            @SuppressWarnings("unchecked")
            T[] result = (T[]) Array.newInstance(type, size);
            for (int i = 0; i < size; i++) {
                result[i] = codec.decode(buf);
            }
            return result;
        });
    }
}
