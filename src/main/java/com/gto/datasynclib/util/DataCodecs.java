package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.IntArrayData;
import com.gto.datasynclib.datastream.data.LongData;
import com.gto.datasynclib.datastream.data.StringData;
import lombok.experimental.UtilityClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;

/**
 * Pre-registered DataCodec instances for Minecraft types including
 * ResourceLocation, BlockPos, CompoundTag, ItemStack, FluidStack, and Component.
 */
@UtilityClass
public class DataCodecs {

    public final DataCodec<ResourceLocation> RESOURCE_LOCATION_CODEC = DataCodec.of(obj -> StringData.valueOf(obj.toString()), (data, dataVersion) -> ResourceLocation.parse(data.getString()));

    static {
        DataCodec.registerCodec(ResourceLocation.class, RESOURCE_LOCATION_CODEC);
    }

    public static final DataCodec<BlockPos> BLOCK_POS_CODEC = new DataCodec<>() {

        @Override
        public BlockPos decode(@NotNull Data data, int dataVersion) {
            var array = data.getIntArray();
            return new BlockPos(array[0], array[1], array[2]);
        }

        @Override
        public @NotNull Data encode(BlockPos obj) {
            return new IntArrayData(obj.getX(), obj.getY(), obj.getZ());
        }

        static {
            DataCodec.registerCodec(BlockPos.class, BLOCK_POS_CODEC);
        }
    };

    public static final DataCodec<Vec2> VEC2_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Vec2 obj) {
            return Data.valueOf(new float[]{obj.x, obj.y});
        }

        @Override
        public Vec2 decode(@NotNull Data data, int dataVersion) {
            var array = data.getFloatArray();
            return new Vec2(array[0], array[1]);
        }

        static {
            DataCodec.registerCodec(Vec2.class, VEC2_CODEC);
        }
    };

    public static final DataCodec<Vec3> VEC3_CODEC = new DataCodec<>() {

        @Override
        public @NotNull Data encode(Vec3 obj) {
            return Data.valueOf(new double[]{obj.x, obj.y, obj.z});
        }

        @Override
        public Vec3 decode(@NotNull Data data, int dataVersion) {
            var array = data.getDoubleArray();
            return new Vec3(array[0], array[1], array[2]);
        }

        static {
            DataCodec.registerCodec(Vec3.class, VEC3_CODEC);
        }
    };


    public static final DataCodec<ChunkPos> CHUNK_POS_CODEC = new DataCodec<>() {

        @Override
        public ChunkPos decode(@NotNull Data data, int dataVersion) {
            return new ChunkPos(data.getLong());
        }

        @Override
        public @NotNull Data encode(ChunkPos obj) {
            return LongData.valueOf(obj.toLong());
        }

        static {
            DataCodec.registerCodec(ChunkPos.class, CHUNK_POS_CODEC);
        }
    };

    public static final DataCodec<ListTag> LIST_TAG_CODEC = new DataCodec<>() {

        @Override
        public ListTag decode(@NotNull Data data, int dataVersion) {
            var customdata = data.toCustomData(NbtUtil.LIST_TAG_TYPE);
            if (customdata != null) {
                return customdata.get();
            }
            return (ListTag) NbtUtil.convertToTag(data);
        }

        @Override
        public @NotNull Data encode(ListTag listTag) {
            return NbtUtil.LIST_TAG_TYPE.create(listTag);
        }

        static {
            DataCodec.registerCodec(ListTag.class, LIST_TAG_CODEC);
        }
    };

    public static final DataCodec<CompoundTag> COMPOUND_TAG_CODEC = new DataCodec<>() {

        @Override
        public CompoundTag decode(@NotNull Data data, int dataVersion) {
            var customdata = data.toCustomData(NbtUtil.COMPOUND_TAG_TYPE);
            if (customdata != null) {
                return customdata.get();
            }
            return (CompoundTag) NbtUtil.convertToTag(data);
        }

        @Override
        public @NotNull Data encode(CompoundTag compoundTag) {
            return NbtUtil.COMPOUND_TAG_TYPE.create(compoundTag);
        }

        static {
            DataCodec.registerCodec(CompoundTag.class, COMPOUND_TAG_CODEC);
        }
    };

    public static final DataCodec<Tag> TAG_CODEC = new DataCodec<>() {

        @Override
        public Tag decode(@NotNull Data data, int dataVersion) {
            var customdata = data.toCustomData(NbtUtil.TAG_TYPE);
            if (customdata != null) {
                return customdata.get();
            }
            return NbtUtil.convertToTag(data);
        }

        @Override
        public @NotNull Data encode(Tag obj) {
            return NbtUtil.TAG_TYPE.create(obj);
        }

        static {
            DataCodec.registerCodec(Tag.class, TAG_CODEC);
        }
    };

    public static final DataCodec<ItemStack> ITEM_STACK_CODEC = new DataCodec<>() {

        @Override
        public ItemStack decode(@NotNull Data data, int dataVersion) {
            return ItemStack.of(COMPOUND_TAG_CODEC.decode(data, dataVersion));
        }

        @Override
        public @NotNull Data encode(ItemStack obj) {
            return COMPOUND_TAG_CODEC.encode(obj.save(new CompoundTag()));
        }

        static {
            DataCodec.registerCodec(ItemStack.class, ITEM_STACK_CODEC);
        }
    };

    public static final DataCodec<FluidStack> FLUID_STACK_CODEC = new DataCodec<>() {

        @Override
        public FluidStack decode(@NotNull Data data, int dataVersion) {
            return FluidStack.loadFluidStackFromNBT(COMPOUND_TAG_CODEC.decode(data, dataVersion));
        }

        @Override
        public @NotNull Data encode(FluidStack obj) {
            return COMPOUND_TAG_CODEC.encode(obj.writeToNBT(new CompoundTag()));
        }

        static {
            DataCodec.registerCodec(FluidStack.class, FLUID_STACK_CODEC);
        }
    };

    public static final DataCodec<Component> COMPONENT_CODEC = new DataCodec<>() {

        @Override
        public Component decode(@NotNull Data data, int dataVersion) {
            return Component.Serializer.fromJson(data.getString());
        }

        @Override
        public @NotNull Data encode(Component obj) {
            return StringData.valueOf(Component.Serializer.toJson(obj));
        }

        static {
            DataCodec.registerCodec(Component.class, COMPONENT_CODEC);
        }
    };

    public <T> DataCodec<T> of(Registry<T> registry) {
        return DataCodec.of(obj -> RESOURCE_LOCATION_CODEC.encode(registry.getKey(obj)), (data, dataVersion) -> registry.get(RESOURCE_LOCATION_CODEC.decode(data, dataVersion)));
    }
}
