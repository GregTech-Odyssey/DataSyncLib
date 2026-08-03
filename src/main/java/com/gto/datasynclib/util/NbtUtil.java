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
import java.util.Map;
import java.util.function.Function;

/**
 * NBT ↔ Data conversion utilities and {@link com.gto.datasynclib.datastream.data.CustomData} type registrations.
 *
 * <h2>Conversion Direction &amp; Limitations</h2>
 * <p><strong>NBT → Data ({@link #convertToData(Tag)}):</strong> Generally safe. All standard
 * NBT types have a corresponding Data representation.</p>
 * <p><strong>Data → NBT ({@link #convertToTag(Data)}):</strong> <em>Not all Data types can be
 * converted back.</em> Data has more types (19) than NBT (13). Specifically, NBT has no
 * equivalent for: {@code DataMapData} (ID 16), {@code IntMapData} (ID 17),
 * {@code LongMapData} (ID 18), {@code CustomData} (ID 15). Attempting to convert these
 * will throw {@link MatchException}.</p>
 *
 * <h2>Performance: Prefer CustomData Over Conversion</h2>
 * <p>The {@code convertToTag/convertToData} methods iterate every element recursively,
 * creating new Data/Tag objects for the entire tree. This is O(n) and allocates heavily.
 * For NBT fields that only need to be stored and retrieved, use the pre-registered
 * {@link com.gto.datasynclib.datastream.data.CustomData.Type CustomData.Type} instances
 * instead — they <strong>wrap</strong> the original NBT object directly without conversion:</p>
 * <pre>{@code
 * // ❌ Slow: full conversion (allocates new objects for every element)
 * Data data = NbtUtil.convertToData(compoundTag);
 *
 * // ✅ Fast: zero-copy wrapping (just wraps the reference)
 * Data data = NbtUtil.COMPOUND_TAG_TYPE.create(compoundTag.copy());
 * }</pre>
 * <p>The three registered types and their IDs:
 * <ul>
 *   <li>{@link #TAG_TYPE} (ID 0) — wraps any {@link Tag} with a 1-byte type prefix</li>
 *   <li>{@link #COMPOUND_TAG_TYPE} (ID 1) — wraps {@link CompoundTag} directly (no type prefix, more compact)</li>
 *   <li>{@link #LIST_TAG_TYPE} (ID 2) — wraps {@link ListTag} directly (no type prefix, more compact)</li>
 * </ul>
 * <p>The type-specific wrappers ({@code COMPOUND_TAG_TYPE} and {@code LIST_TAG_TYPE}) are
 * more compact because they omit the type-ID byte that {@code TAG_TYPE} requires for
 * run-time dispatch.</p>
 *
 * @see com.gto.datasynclib.datastream.data.CustomData
 */
@UtilityClass
public class NbtUtil {

    /**
     * Convenience function that exposes a {@link CompoundTag}'s backing map.
     * Used with {@link com.gto.datasynclib.annotations.Conversion @Conversion} to
     * manage a {@code CompoundTag} field as a {@code Map<String, Tag>} for sync/persistence.
     *
     * <p>Usage:
     * <pre>{@code
     * @Conversion(getFunction = "COMPOUND_TAG_MAP")
     * private final CompoundTag data = new CompoundTag();
     * }</pre>
     */
    public final Function<CompoundTag, Map<String, Tag>> COMPOUND_TAG_MAP = t -> t.tags;

    /**
     * Wraps any {@link Tag} with a 1-byte type-ID prefix for run-time dispatch.
     * Supports all NBT types. Use this when the exact NBT type is unknown at codec-registration time.
     */
    public final CustomData.Type<Tag> TAG_TYPE = CustomData.Type.<Tag>builder(0).copy(Tag::copy).write((t, b) -> {
        b.writeByte(t.getId());
        write(t, b);
    }).read(b -> read(b.readByte(), b)).build();

    /**
     * Wraps a {@link CompoundTag} directly without a type-ID prefix.
     * More compact than {@link #TAG_TYPE}. Use this when the type is statically known.
     */
    public final CustomData.Type<CompoundTag> COMPOUND_TAG_TYPE = CustomData.Type.<CompoundTag>builder(1).copy(CompoundTag::copy).write(NbtUtil::write).read(b -> (CompoundTag) read(Tag.TAG_COMPOUND, b)).build();

    /**
     * Wraps a {@link ListTag} directly without a type-ID prefix.
     * More compact than {@link #TAG_TYPE}. Use this when the type is statically known.
     */
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

    /**
     * Converts a {@link Data} tree to an equivalent NBT {@link Tag} tree.
     *
     * <p><strong>Limitation:</strong> Not all Data types can be converted. Data has 19 types
     * while NBT has only 13. The following Data types have NO NBT equivalent and will throw
     * {@link MatchException}: {@code DataMapData} (ID 16), {@code IntMapData} (ID 17),
     * {@code LongMapData} (ID 18), {@code CustomData} (ID 15).</p>
     *
     * <p><strong>Performance:</strong> This method recursively converts every element, allocating
     * new Tag objects for the entire tree. For NBT fields, prefer using
     * {@link #COMPOUND_TAG_TYPE}{@code .create()} or {@link #LIST_TAG_TYPE}{@code .create()}
     * which wrap the original object with zero conversion overhead.</p>
     *
     * @param data the Data tree to convert (must not contain unsupported types)
     * @return the equivalent NBT Tag tree
     * @throws MatchException if the Data contains types with no NBT equivalent
     * @see #convertToData(Tag)
     */
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

    /**
     * Converts an NBT {@link Tag} tree to an equivalent {@link Data} tree.
     *
     * <p>All standard NBT types (13) have a corresponding Data representation, so this
     * direction is generally safe. However, prefer using the {@link CustomData.Type}
     * wrappers ({@link #TAG_TYPE}, {@link #COMPOUND_TAG_TYPE}, {@link #LIST_TAG_TYPE})
     * for NBT fields — they wrap the original object with zero conversion overhead,
     * avoiding the O(n) recursive allocation cost of this method.</p>
     *
     * <p>This method is mainly useful for compatibility/migration scenarios where
     * existing NBT data needs to be imported into the Data type system.</p>
     *
     * @param tag the NBT Tag tree to convert
     * @return the equivalent Data tree
     * @see #convertToTag(Data)
     */
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
