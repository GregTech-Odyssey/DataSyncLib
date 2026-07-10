package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.DataCodec;
import com.gto.datasynclib.datastream.data.*;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.experimental.UtilityClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;

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

    public static final DataCodec<ListTag> LIST_TAG_CODEC = new DataCodec<>() {

        @Override
        public ListTag decode(@NotNull Data data, int dataVersion) {
            var dataList = data.getList();
            var size = dataList.size();
            if (size == 0) return new ListTag();
            var tagList = new ObjectArrayList<Tag>(size);
            dataList.forEach(d -> tagList.add(TAG_CODEC.decode(d, dataVersion)));
            return new ListTag(tagList, tagList.getFirst().getId());
        }

        @Override
        public @NotNull Data encode(ListTag listTag) {
            var dataList = new ArrayList<Data>(listTag.size());
            for (var tag : listTag) {
                dataList.add(TAG_CODEC.encode(tag));
            }
            return new ListData(dataList);
        }

        static {
            DataCodec.registerCodec(ListTag.class, LIST_TAG_CODEC);
        }
    };

    public static final DataCodec<CompoundTag> COMPOUND_TAG_CODEC = new DataCodec<>() {

        @Override
        public CompoundTag decode(@NotNull Data data, int dataVersion) {
            var tagMap = new CompoundTag();
            var dataMap = data.getStringMap();
            var size = dataMap.size();
            if (size == 0) return tagMap;
            dataMap.forEach((k, v) -> tagMap.put(k, TAG_CODEC.decode(v, dataVersion)));
            return tagMap;
        }

        @Override
        public @NotNull Data encode(CompoundTag compoundTag) {
            var dataMap = new HashMap<String, Data>(compoundTag.tags.size());
            compoundTag.tags.forEach((key, tag) -> dataMap.put(key, TAG_CODEC.encode(tag)));
            return new StringMapData(dataMap);
        }

        static {
            DataCodec.registerCodec(CompoundTag.class, COMPOUND_TAG_CODEC);
        }
    };

    public static final DataCodec<Tag> TAG_CODEC = new DataCodec<>() {

        @Override
        public Tag decode(@NotNull Data data, int dataVersion) {
            return switch (data.getId()) {
                case Data.NULL -> EndTag.INSTANCE;
                case Data.BYTE -> ByteTag.valueOf(data.getByte());
                case Data.SHORT -> ShortTag.valueOf(data.getShort());
                case Data.INT -> IntTag.valueOf(data.getInt());
                case Data.LONG -> LongTag.valueOf(data.getLong());
                case Data.FLOAT -> FloatTag.valueOf(data.getFloat());
                case Data.DOUBLE -> DoubleTag.valueOf(data.getDouble());
                case Data.STRING -> StringTag.valueOf(data.getString());
                case Data.LIST -> LIST_TAG_CODEC.decode(data, dataVersion);
                case Data.STRING_MAP -> COMPOUND_TAG_CODEC.decode(data, dataVersion);
                case Data.BYTE_ARRAY -> new ByteArrayTag(data.getByteArray());
                case Data.INT_ARRAY -> new IntArrayTag(data.getIntArray());
                case Data.LONG_ARRAY -> new LongArrayTag(data.getLongArray());
                default -> throw new MatchException("Unknown Data type id for Tag conversion: " + data.getId(), null);
            };
        }

        @Override
        public @NotNull Data encode(Tag obj) {
            return switch (obj.getId()) {
                case Tag.TAG_END -> NullData.INSTANCE;
                case Tag.TAG_BYTE -> ByteData.valueOf(((ByteTag) obj).getAsByte());
                case Tag.TAG_SHORT -> ShortData.valueOf(((ShortTag) obj).getAsShort());
                case Tag.TAG_INT -> IntData.valueOf(((IntTag) obj).getAsInt());
                case Tag.TAG_LONG -> LongData.valueOf(((LongTag) obj).getAsLong());
                case Tag.TAG_FLOAT -> FloatData.valueOf(((FloatTag) obj).getAsFloat());
                case Tag.TAG_DOUBLE -> DoubleData.valueOf(((DoubleTag) obj).getAsDouble());
                case Tag.TAG_STRING -> StringData.valueOf(obj.getAsString());
                case Tag.TAG_LIST -> LIST_TAG_CODEC.encode((ListTag) obj);
                case Tag.TAG_COMPOUND -> COMPOUND_TAG_CODEC.encode((CompoundTag) obj);
                case Tag.TAG_BYTE_ARRAY -> ByteArrayData.valueOf(((ByteArrayTag) obj).getAsByteArray());
                case Tag.TAG_INT_ARRAY -> IntArrayData.valueOf(((IntArrayTag) obj).getAsIntArray());
                case Tag.TAG_LONG_ARRAY -> LongArrayData.valueOf(((LongArrayTag) obj).getAsLongArray());
                default -> throw new IllegalArgumentException("Unknown tag id: " + obj.getId());
            };
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
