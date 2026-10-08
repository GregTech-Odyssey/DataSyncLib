package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.StreamCodec;
import lombok.experimental.UtilityClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.FluidStack;

/**
 * Pre-registered {@link StreamCodec} instances for Minecraft types: ResourceLocation,
 * BlockPos, ChunkPos, Vec3i, SectionPos, Vec2, Vec3, AABB, Tag, CompoundTag, ListTag, ItemStack,
 * FluidStack and Component, plus {@link #of(net.minecraft.core.Registry)} for registry entries
 * (encoded as a VarInt id). The matching persist-side halves live in
 * {@link com.gto.datasynclib.util.DataCodecs}; both are registered in
 * {@link com.gto.datasynclib.DataSyncCodec} rather than in a per-interface table.
 *
 * <p>These are hand-written on purpose: they read and write primitives straight on the buffer
 * instead of going through the boxed components of {@link com.gto.datasynclib.datastream.codec.CombinedCodec#composite}.
 * See that method's documentation for the trade-off.</p>
 */
@UtilityClass
public class StreamCodecs {

    public final StreamCodec<FriendlyByteBuf, ResourceLocation> RESOURCE_LOCATION_CODEC = StreamCodec.of((stream, obj) -> {
        stream.writeUtf(obj.getNamespace());
        stream.writeUtf(obj.getPath());
    }, stream -> ResourceLocation.fromNamespaceAndPath(stream.readUtf(), stream.readUtf()));

    public static final StreamCodec<FriendlyByteBuf, BlockPos> BLOCK_POS_CODEC = StreamCodec.of((stream, obj) -> {
        stream.writeLong(obj.asLong());
    }, stream -> BlockPos.of(stream.readLong()));

    public static final StreamCodec<FriendlyByteBuf, ChunkPos> CHUNK_POS_CODEC = StreamCodec.of((stream, obj) -> {
        stream.writeLong(obj.toLong());
    }, stream -> new ChunkPos(stream.readLong()));

    /**
     * Integer triple, three VarInts. {@link BlockPos} keeps its own packed-long codec, and an
     * exact registration always wins over this one.
     */
    public static final StreamCodec<FriendlyByteBuf, Vec3i> VEC3I_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, Vec3i obj) {
            stream.writeVarInt(obj.getX());
            stream.writeVarInt(obj.getY());
            stream.writeVarInt(obj.getZ());
        }

        @Override
        public Vec3i decode(FriendlyByteBuf stream) {
            return new Vec3i(stream.readVarInt(), stream.readVarInt(), stream.readVarInt());
        }
    };

    /**
     * Section (16³) position, as its packed long.
     */
    public static final StreamCodec<FriendlyByteBuf, SectionPos> SECTION_POS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, SectionPos obj) {
            stream.writeLong(obj.asLong());
        }

        @Override
        public SectionPos decode(FriendlyByteBuf stream) {
            return SectionPos.of(stream.readLong());
        }
    };

    /**
     * Six raw doubles: minX, minY, minZ, maxX, maxY, maxZ.
     */
    public static final StreamCodec<FriendlyByteBuf, AABB> AABB_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, AABB obj) {
            stream.writeDouble(obj.minX);
            stream.writeDouble(obj.minY);
            stream.writeDouble(obj.minZ);
            stream.writeDouble(obj.maxX);
            stream.writeDouble(obj.maxY);
            stream.writeDouble(obj.maxZ);
        }

        @Override
        public AABB decode(FriendlyByteBuf stream) {
            return new AABB(stream.readDouble(), stream.readDouble(), stream.readDouble(),
                    stream.readDouble(), stream.readDouble(), stream.readDouble());
        }
    };


    public static final StreamCodec<FriendlyByteBuf, Vec2> VEC2_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, Vec2 obj) {
            stream.writeFloat(obj.x);
            stream.writeFloat(obj.y);
        }

        @Override
        public Vec2 decode(FriendlyByteBuf stream) {
            return new Vec2(stream.readFloat(), stream.readFloat());
        }
    };

    public static final StreamCodec<FriendlyByteBuf, Vec3> VEC3_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, Vec3 obj) {
            stream.writeDouble(obj.x);
            stream.writeDouble(obj.y);
            stream.writeDouble(obj.z);
        }

        @Override
        public Vec3 decode(FriendlyByteBuf stream) {
            return new Vec3(stream.readDouble(), stream.readDouble(), stream.readDouble());
        }
    };


    public static final StreamCodec<FriendlyByteBuf, Tag> TAG_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, Tag obj) {
            stream.writeByte(obj.getId());
            NbtUtil.write(obj, stream);
        }

        @Override
        public Tag decode(FriendlyByteBuf stream) {
            return NbtUtil.read(stream.readByte(), stream);
        }
    };

    public static final StreamCodec<FriendlyByteBuf, CompoundTag> COMPOUND_TAG_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, CompoundTag obj) {
            NbtUtil.write(obj, stream);
        }

        @Override
        public CompoundTag decode(FriendlyByteBuf stream) {
            return (CompoundTag) NbtUtil.read(Tag.TAG_COMPOUND, stream);
        }
    };

    public static final StreamCodec<FriendlyByteBuf, ListTag> LIST_TAG_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, ListTag obj) {
            NbtUtil.write(obj, stream);
        }

        @Override
        public ListTag decode(FriendlyByteBuf stream) {
            return (ListTag) NbtUtil.read(Tag.TAG_LIST, stream);
        }
    };

    public static final StreamCodec<FriendlyByteBuf, ItemStack> ITEM_STACK_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, ItemStack obj) {
            stream.writeItem(obj);
        }

        @Override
        public ItemStack decode(FriendlyByteBuf stream) {
            return stream.readItem();
        }
    };

    public static final StreamCodec<FriendlyByteBuf, FluidStack> FLUID_STACK_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, FluidStack obj) {
            obj.writeToPacket(stream);
        }

        @Override
        public FluidStack decode(FriendlyByteBuf stream) {
            return FluidStack.readFromPacket(stream);
        }
    };

    public static final StreamCodec<FriendlyByteBuf, Component> COMPONENT_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Component obj) {
            buf.writeComponent(obj);
        }

        @Override
        public Component decode(FriendlyByteBuf buf) {
            return buf.readComponent();
        }
    };

    /**
     * Builds a codec for entries of a Minecraft {@link Registry}: values are written as a VarInt
     * registry id, which is compact but only valid between peers whose registries match.
     *
     * @param registry the registry the values belong to
     */
    public static <T> StreamCodec<FriendlyByteBuf, T> of(Registry<T> registry) {
        return StreamCodec.of((stream, obj) -> stream.writeVarInt(registry.getId(obj)), stream -> registry.byId(stream.readVarInt()));
    }
}
