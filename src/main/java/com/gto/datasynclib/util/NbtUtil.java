package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.data.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import lombok.experimental.UtilityClass;
import net.minecraft.nbt.*;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

@UtilityClass
public class NbtUtil {

    public final CustomData.Type<Tag> TAG_TYPE = CustomData.Type.<Tag>builder(0).copy(Tag::copy).write((t, b) -> {
        b.writeByte(t.getId());
        write(t, b);
    }).read(b -> read(b.readByte(), b)).build();

    public final CustomData.Type<CompoundTag> COMPOUND_TAG_TYPE = CustomData.Type.<CompoundTag>builder(1).copy(CompoundTag::copy).write(NbtUtil::write).read(b -> (CompoundTag) read(Tag.TAG_COMPOUND, b)).build();
    public final CustomData.Type<ListTag> LIST_TAG_TYPE = CustomData.Type.<ListTag>builder(2).copy(ListTag::copy).write(NbtUtil::write).read(b -> (ListTag) read(Tag.TAG_LIST, b)).build();

    public Tag read(byte id, ByteBuf byteBuf) {
        return read(id, new ByteBufInputStream(byteBuf));
    }

    public Tag read(byte id, DataInput in) {
        try {
            return switch (id) {
                case Tag.TAG_END -> EndTag.INSTANCE;
                case Tag.TAG_BYTE -> ByteTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_SHORT -> ShortTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_INT -> IntTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_LONG -> LongTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_FLOAT -> FloatTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_DOUBLE -> DoubleTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_BYTE_ARRAY -> ByteArrayTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_STRING -> StringTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_LIST -> ListTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_COMPOUND -> CompoundTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_INT_ARRAY -> IntArrayTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                case Tag.TAG_LONG_ARRAY -> LongArrayTag.TYPE.load(in, 0, NbtAccounter.UNLIMITED);
                default -> throw new IllegalArgumentException("Unknown tag id " + id);
            };
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void write(Tag tag, ByteBuf byteBuf) {
        try {
            tag.write(new ByteBufOutputStream(byteBuf));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void write(Tag tag, DataOutput out) {
        try {
            tag.write(out);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Tag convertToTag(Data data) {
        return switch (data.getId()) {
            case Data.NULL -> EndTag.INSTANCE;
            case Data.BYTE -> ByteTag.valueOf(data.getByte());
            case Data.SHORT -> ShortTag.valueOf(data.getShort());
            case Data.INT -> IntTag.valueOf(data.getInt());
            case Data.LONG -> LongTag.valueOf(data.getLong());
            case Data.FLOAT -> FloatTag.valueOf(data.getFloat());
            case Data.DOUBLE -> DoubleTag.valueOf(data.getDouble());
            case Data.STRING -> StringTag.valueOf(data.getString());
            case Data.LIST -> {
                var dataList = data.getList();
                var size = dataList.size();
                if (size == 0) yield new ListTag();
                var tagList = new ObjectArrayList<Tag>(size);
                dataList.forEach(d -> tagList.add(convertToTag(d)));
                yield new ListTag(tagList, tagList.getFirst().getId());
            }
            case Data.STRING_MAP -> {
                var tagMap = new CompoundTag();
                var dataMap = data.getStringMap();
                var size = dataMap.size();
                if (size == 0) yield tagMap;
                dataMap.forEach((k, v) -> tagMap.put(k, convertToTag(v)));
                yield tagMap;
            }
            case Data.BYTE_ARRAY -> new ByteArrayTag(data.getByteArray());
            case Data.INT_ARRAY -> new IntArrayTag(data.getIntArray());
            case Data.LONG_ARRAY -> new LongArrayTag(data.getLongArray());
            default -> throw new MatchException("Unknown Data type id for Tag conversion: " + data.getId(), null);
        };
    }

    public Data convertToData(Tag tag) {
        return switch (tag.getId()) {
            case Tag.TAG_END -> NullData.INSTANCE;
            case Tag.TAG_BYTE -> ByteData.valueOf(((ByteTag) tag).getAsByte());
            case Tag.TAG_SHORT -> ShortData.valueOf(((ShortTag) tag).getAsShort());
            case Tag.TAG_INT -> IntData.valueOf(((IntTag) tag).getAsInt());
            case Tag.TAG_LONG -> LongData.valueOf(((LongTag) tag).getAsLong());
            case Tag.TAG_FLOAT -> FloatData.valueOf(((FloatTag) tag).getAsFloat());
            case Tag.TAG_DOUBLE -> DoubleData.valueOf(((DoubleTag) tag).getAsDouble());
            case Tag.TAG_STRING -> StringData.valueOf(tag.getAsString());
            case Tag.TAG_LIST -> {
                var listTag = (ListTag) tag;
                var dataList = new ArrayList<Data>(listTag.size());
                for (var t : listTag) {
                    dataList.add(convertToData(t));
                }
                yield new ListData(dataList);
            }
            case Tag.TAG_COMPOUND -> {
                var compoundTag = (CompoundTag) tag;
                var dataMap = new HashMap<String, Data>(compoundTag.tags.size());
                compoundTag.tags.forEach((key, t) -> dataMap.put(key, convertToData(t)));
                yield new StringMapData(dataMap);
            }
            case Tag.TAG_BYTE_ARRAY -> ByteArrayData.valueOf(((ByteArrayTag) tag).getAsByteArray());
            case Tag.TAG_INT_ARRAY -> IntArrayData.valueOf(((IntArrayTag) tag).getAsIntArray());
            case Tag.TAG_LONG_ARRAY -> LongArrayData.valueOf(((LongArrayTag) tag).getAsLongArray());
            default -> throw new IllegalArgumentException("Unknown tag id: " + tag.getId());
        };
    }

    public void init() {
    }
}
