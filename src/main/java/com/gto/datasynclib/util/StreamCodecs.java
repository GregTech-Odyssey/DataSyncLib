package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.ByteStreamCodec;
import lombok.experimental.UtilityClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.FluidStack;

/**
 * Pre-registered ByteStreamCodec instances for Minecraft types including
 * ResourceLocation, BlockPos, CompoundTag, ItemStack, FluidStack, and Component.
 */
@UtilityClass
public class StreamCodecs {

    public final ByteStreamCodec<ResourceLocation> RESOURCE_LOCATION_CODEC = ByteStreamCodec.of((stream, obj) -> {
        stream.writeUtf(obj.getNamespace());
        stream.writeUtf(obj.getPath());
    }, stream -> ResourceLocation.fromNamespaceAndPath(stream.readUtf(), stream.readUtf()));

    public static final ByteStreamCodec<BlockPos> BLOCK_POS_CODEC = ByteStreamCodec.of((stream, obj) -> {
        stream.writeLong(obj.asLong());
    }, stream -> BlockPos.of(stream.readLong()));

    public static final ByteStreamCodec<ChunkPos> CHUNK_POS_CODEC = ByteStreamCodec.of((stream, obj) -> {
        stream.writeLong(obj.toLong());
    }, stream -> new ChunkPos(stream.readLong()));

    static {
        ByteStreamCodec.registerCodec(ResourceLocation.class, RESOURCE_LOCATION_CODEC);
        ByteStreamCodec.registerCodec(BlockPos.class, BLOCK_POS_CODEC);
        ByteStreamCodec.registerCodec(ChunkPos.class, CHUNK_POS_CODEC);
    }


    public static final ByteStreamCodec<Vec2> VEC2_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, Vec2 obj) {
            stream.writeFloat(obj.x);
            stream.writeFloat(obj.y);
        }

        @Override
        public Vec2 decode(FriendlyByteBuf stream) {
            return new Vec2(stream.readFloat(), stream.readFloat());
        }

        static {
            ByteStreamCodec.registerCodec(Vec2.class, VEC2_CODEC);
        }
    };

    public static final ByteStreamCodec<Vec3> VEC3_CODEC = new ByteStreamCodec<>() {

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

        static {
            ByteStreamCodec.registerCodec(Vec3.class, VEC3_CODEC);
        }
    };


    public static final ByteStreamCodec<Tag> TAG_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, Tag obj) {
            stream.writeByte(obj.getId());
            NbtUtil.write(obj, stream);
        }

        @Override
        public Tag decode(FriendlyByteBuf stream) {
            return NbtUtil.read(stream.readByte(), stream);
        }

        static {
            ByteStreamCodec.registerCodec(Tag.class, TAG_CODEC);
        }
    };

    public static final ByteStreamCodec<CompoundTag> COMPOUND_TAG_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, CompoundTag obj) {
            NbtUtil.write(obj, stream);
        }

        @Override
        public CompoundTag decode(FriendlyByteBuf stream) {
            return (CompoundTag) NbtUtil.read(Tag.TAG_COMPOUND, stream);
        }

        static {
            ByteStreamCodec.registerCodec(CompoundTag.class, COMPOUND_TAG_CODEC);
        }
    };

    public static final ByteStreamCodec<ListTag> LIST_TAG_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, ListTag obj) {
            NbtUtil.write(obj, stream);
        }

        @Override
        public ListTag decode(FriendlyByteBuf stream) {
            return (ListTag) NbtUtil.read(Tag.TAG_LIST, stream);
        }

        static {
            ByteStreamCodec.registerCodec(ListTag.class, LIST_TAG_CODEC);
        }
    };

    public static final ByteStreamCodec<ItemStack> ITEM_STACK_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, ItemStack obj) {
            stream.writeItem(obj);
        }

        @Override
        public ItemStack decode(FriendlyByteBuf stream) {
            return stream.readItem();
        }

        static {
            ByteStreamCodec.registerCodec(ItemStack.class, ITEM_STACK_CODEC);
        }
    };

    public static final ByteStreamCodec<FluidStack> FLUID_STACK_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf stream, FluidStack obj) {
            obj.writeToPacket(stream);
        }

        @Override
        public FluidStack decode(FriendlyByteBuf stream) {
            return FluidStack.readFromPacket(stream);
        }

        static {
            ByteStreamCodec.registerCodec(FluidStack.class, FLUID_STACK_CODEC);
        }
    };

    public static final ByteStreamCodec<Component> COMPONENT_CODEC = new ByteStreamCodec<>() {

        @Override
        public void encode(FriendlyByteBuf buf, Component obj) {
            buf.writeComponent(obj);
        }

        @Override
        public Component decode(FriendlyByteBuf buf) {
            return buf.readComponent();
        }

        static {
            ByteStreamCodec.registerCodec(Component.class, COMPONENT_CODEC);
        }
    };

    public static <T> ByteStreamCodec<T> of(Registry<T> registry) {
        return ByteStreamCodec.of((stream, obj) -> stream.writeVarInt(registry.getId(obj)), stream -> registry.byId(stream.readVarInt()));
    }
}
