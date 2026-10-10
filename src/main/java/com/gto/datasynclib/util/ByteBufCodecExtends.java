package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.ByteBufCodecs;
import com.gto.datasynclib.datastream.codec.StreamCodec;
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
 * The <strong>extension half</strong> of {@link ByteBufCodecs}: the pre-registered {@link StreamCodec}
 * instances for Minecraft types — ResourceLocation, BlockPos, ChunkPos, Vec3i, SectionPos, Vec2, Vec3,
 * AABB, Tag, CompoundTag, ListTag, ItemStack, FluidStack and Component — plus
 * {@link #of(net.minecraft.core.Registry)} for registry entries (encoded as a VarInt id).
 *
 * <p>1.21 keeps the Minecraft value types in {@code ByteBufCodecs} itself, next to the primitives;
 * this library declares them in an <em>extension</em> of it instead, so that the 1.21-shaped core
 * stays free of Minecraft types without costing a second import: the constants of
 * {@link ByteBufCodecs} ({@code STRING_UTF8}, {@code VAR_INT}, {@code INT_LIST}, …) are inherited, so
 * {@code StreamCodecExtends.STRING_UTF8} resolves here too. Only <em>fields</em> are inherited, though — a
 * static interface method is not (JLS 9.4.1) — so the builders are still reached through
 * {@code ByteBufCodecs.list()}, {@code ByteBufCodecs.optional(…)} and friends.</p>
 *
 * <p>The matching persist-side halves live in {@link com.gto.datasynclib.util.ValueCodecs}; both are
 * registered in {@link com.gto.datasynclib.DataSyncCodec} rather than in a per-interface table.</p>
 *
 * <p>These are hand-written on purpose: they read and write primitives straight on the buffer
 * instead of going through the boxed components of {@link com.gto.datasynclib.datastream.codec.CombinedCodec#composite}.
 * See that method's documentation for the trade-off.</p>
 */
public interface ByteBufCodecExtends extends ByteBufCodecs {

    StreamCodec<FriendlyByteBuf, ResourceLocation> RESOURCE_LOCATION_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, ResourceLocation obj) {
            stream.writeUtf(obj.getNamespace());
            stream.writeUtf(obj.getPath());
        }

        @Override
        public ResourceLocation decode(FriendlyByteBuf stream) {
            return ResourceLocation.fromNamespaceAndPath(stream.readUtf(), stream.readUtf());
        }
    };

    StreamCodec<FriendlyByteBuf, BlockPos> BLOCK_POS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, BlockPos obj) {
            stream.writeLong(obj.asLong());
        }

        @Override
        public BlockPos decode(FriendlyByteBuf stream) {
            return BlockPos.of(stream.readLong());
        }
    };

    StreamCodec<FriendlyByteBuf, ChunkPos> CHUNK_POS_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, ChunkPos obj) {
            stream.writeLong(obj.toLong());
        }

        @Override
        public ChunkPos decode(FriendlyByteBuf stream) {
            return new ChunkPos(stream.readLong());
        }
    };

    /**
     * Integer triple, three VarInts. {@link BlockPos} keeps its own packed-long codec, and an
     * exact registration always wins over this one.
     */
    StreamCodec<FriendlyByteBuf, Vec3i> VEC3I_CODEC = new StreamCodec<>() {

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
    StreamCodec<FriendlyByteBuf, SectionPos> SECTION_POS_CODEC = new StreamCodec<>() {

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
    StreamCodec<FriendlyByteBuf, AABB> AABB_CODEC = new StreamCodec<>() {

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


    StreamCodec<FriendlyByteBuf, Vec2> VEC2_CODEC = new StreamCodec<>() {

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

    StreamCodec<FriendlyByteBuf, Vec3> VEC3_CODEC = new StreamCodec<>() {

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


    StreamCodec<FriendlyByteBuf, Tag> TAG_CODEC = new StreamCodec<>() {

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

    StreamCodec<FriendlyByteBuf, CompoundTag> COMPOUND_TAG_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, CompoundTag obj) {
            NbtUtil.write(obj, stream);
        }

        @Override
        public CompoundTag decode(FriendlyByteBuf stream) {
            return (CompoundTag) NbtUtil.read(Tag.TAG_COMPOUND, stream);
        }
    };

    StreamCodec<FriendlyByteBuf, ListTag> LIST_TAG_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, ListTag obj) {
            NbtUtil.write(obj, stream);
        }

        @Override
        public ListTag decode(FriendlyByteBuf stream) {
            return (ListTag) NbtUtil.read(Tag.TAG_LIST, stream);
        }
    };

    StreamCodec<FriendlyByteBuf, ItemStack> ITEM_STACK_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, ItemStack obj) {
            stream.writeItem(obj);
        }

        @Override
        public ItemStack decode(FriendlyByteBuf stream) {
            return stream.readItem();
        }
    };

    StreamCodec<FriendlyByteBuf, FluidStack> FLUID_STACK_CODEC = new StreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, FluidStack obj) {
            obj.writeToPacket(stream);
        }

        @Override
        public FluidStack decode(FriendlyByteBuf stream) {
            return FluidStack.readFromPacket(stream);
        }
    };

    StreamCodec<FriendlyByteBuf, Component> COMPONENT_CODEC = new StreamCodec<>() {

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
    static <T> StreamCodec<FriendlyByteBuf, T> of(Registry<T> registry) {
        return new StreamCodec<>() {

            @Override
            public void encode(FriendlyByteBuf stream, T obj) {
                stream.writeVarInt(registry.getId(obj));
            }

            @Override
            public T decode(FriendlyByteBuf stream) {
                return registry.byId(stream.readVarInt());
            }
        };
    }
}
