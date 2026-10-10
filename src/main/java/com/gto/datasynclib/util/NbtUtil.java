package com.gto.datasynclib.util;

import com.gto.datasynclib.datastream.codec.CustomTypes;
import com.gto.datasynclib.datastream.codec.JavaValueOps;
import com.gto.datasynclib.datastream.codec.ValueOps;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ReferenceMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceMap;
import lombok.experimental.UtilityClass;
import net.minecraft.nbt.*;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * NBT ↔ carrier conversion utilities, and the {@link CustomTypes.Type} registrations that let an NBT
 * tag be stored as a registered custom payload.
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
     * @Conversion(toManaged = "COMPOUND_TAG_MAP")
     * private final CompoundTag data = new CompoundTag();
     * }</pre>
     */
    public final Function<CompoundTag, Map<String, Tag>> COMPOUND_TAG_MAP = t -> t.tags;

    /**
     * Wraps any {@link Tag} with a 1-byte type-ID prefix for run-time dispatch.
     * Supports all NBT types. Use this when the exact NBT type is unknown at codec-registration time.
     */
    public final CustomTypes.Type<Tag> TAG_TYPE = CustomTypes.Type.<Tag>builder(0).write((t, b) -> {
        b.writeByte(t.getId());
        write(t, b);
    }).read(b -> read(b.readByte(), b)).build();

    /**
     * Wraps a {@link CompoundTag} directly without a type-ID prefix.
     * More compact than {@link #TAG_TYPE}. Use this when the type is statically known.
     */
    public final CustomTypes.Type<CompoundTag> COMPOUND_TAG_TYPE = CustomTypes.Type.<CompoundTag>builder(1).write(NbtUtil::write).read(b -> (CompoundTag) read(Tag.TAG_COMPOUND, b)).build();

    /**
     * Wraps a {@link ListTag} directly without a type-ID prefix.
     * More compact than {@link #TAG_TYPE}. Use this when the type is statically known.
     */
    public final CustomTypes.Type<ListTag> LIST_TAG_TYPE = CustomTypes.Type.<ListTag>builder(2).write(NbtUtil::write).read(b -> (ListTag) read(Tag.TAG_LIST, b)).build();

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

    public void init() {
    }
}
