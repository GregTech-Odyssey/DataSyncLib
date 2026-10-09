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
 *
 * <h2>Conversion Direction &amp; Limitations</h2>
 * <p><strong>NBT → carrier ({@link #convertToValue(Tag)}):</strong> Generally safe. Every standard NBT
 * type has a carrier equivalent.</p>
 * <p><strong>carrier → NBT ({@link #convertToTag(Object)}):</strong> <em>Not all carrier types can be
 * converted back.</em> The carrier has more types (19) than NBT (13). Specifically, NBT has no
 * equivalent for: a value-keyed map ({@code OBJECT_MAP}), the two primitive-keyed maps, a custom
 * payload, a {@code Character} and a {@code SELF} value. Attempting to convert these throws
 * {@link MatchException}.</p>
 *
 * <h2>Performance: prefer a registered custom type over conversion</h2>
 * <p>The {@code convertToTag/convertToValue} methods iterate every element recursively, creating new
 * objects for the entire tree. This is O(n) and allocates heavily. For NBT fields that only need to be
 * stored and retrieved, use the pre-registered
 * {@link CustomTypes.Type CustomTypes.Type} instances instead — they <strong>wrap</strong> the original
 * NBT object directly without conversion.</p>
 *
 * <p>The three registered types and their IDs:
 * <ul>
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
    public final CustomTypes.Type<Tag> TAG_TYPE = CustomTypes.Type.<Tag>builder(0).copy(Tag::copy).write((t, b) -> {
        b.writeByte(t.getId());
        write(t, b);
    }).read(b -> read(b.readByte(), b)).build();

    /**
     * Wraps a {@link CompoundTag} directly without a type-ID prefix.
     * More compact than {@link #TAG_TYPE}. Use this when the type is statically known.
     */
    public final CustomTypes.Type<CompoundTag> COMPOUND_TAG_TYPE = CustomTypes.Type.<CompoundTag>builder(1).copy(CompoundTag::copy).write(NbtUtil::write).read(b -> (CompoundTag) read(Tag.TAG_COMPOUND, b)).build();

    /**
     * Wraps a {@link ListTag} directly without a type-ID prefix.
     * More compact than {@link #TAG_TYPE}. Use this when the type is statically known.
     */
    public final CustomTypes.Type<ListTag> LIST_TAG_TYPE = CustomTypes.Type.<ListTag>builder(2).copy(ListTag::copy).write(NbtUtil::write).read(b -> (ListTag) read(Tag.TAG_LIST, b)).build();

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
     * Converts an NBT {@link Tag} tree into the carrier's values — the shape
     * {@link JavaValueOps} stores directly, and what a payload written before the migration decodes
     * into.
     *
     * <p>A {@code TAG_END} becomes {@code null}; everything else maps to the carrier value of the
     * same shape — numbers to their boxed primitives, a string to a {@link String}, the three array
     * tags to the matching primitive array, a list to a {@code List} and a compound to a string map.
     * The result is a plain Java object graph, which is exactly what a codec that wraps a tag as a
     * custom payload hands to the layer above.</p>
     *
     * @param tag the NBT tree to convert
     * @return the equivalent carrier value
     * @throws IllegalArgumentException if the tag has an id this build does not know
     * @see #convertToTag(Object)
     */
    public Object convertToValue(Tag tag) {
        return switch (tag.getId()) {
            case Tag.TAG_END -> ValueOps.Null.INSTANCE;
            case Tag.TAG_BYTE -> ((NumericTag) tag).getAsByte();
            case Tag.TAG_SHORT -> ((NumericTag) tag).getAsShort();
            case Tag.TAG_INT -> ((NumericTag) tag).getAsInt();
            case Tag.TAG_LONG -> ((NumericTag) tag).getAsLong();
            case Tag.TAG_FLOAT -> ((NumericTag) tag).getAsFloat();
            case Tag.TAG_DOUBLE -> ((NumericTag) tag).getAsDouble();
            case Tag.TAG_BYTE_ARRAY -> ((ByteArrayTag) tag).getAsByteArray();
            case Tag.TAG_STRING -> tag.getAsString();
            case Tag.TAG_LIST -> {
                var listTag = (ListTag) tag;
                var values = new ArrayList<Object>(listTag.size());
                for (var element : listTag) {
                    values.add(convertToValue(element));
                }
                yield values;
            }
            case Tag.TAG_COMPOUND -> {
                var compoundTag = (CompoundTag) tag;
                var values = new HashMap<String, Object>(compoundTag.tags.size());
                compoundTag.tags.forEach((key, element) -> values.put(key, convertToValue(element)));
                yield values;
            }
            case Tag.TAG_INT_ARRAY -> ((IntArrayTag) tag).getAsIntArray();
            case Tag.TAG_LONG_ARRAY -> ((LongArrayTag) tag).getAsLongArray();
            default -> throw new IllegalArgumentException("Unknown tag id: " + tag.getId());
        };
    }

    /**
     * Reads a tag that an <em>older</em> build stored as its own type byte plus the tag's
     * {@code name + payload} encoding — what {@link #write(Tag, ByteBuf)} produces and what the
     * retired {@code NbtUtil.TAG_TYPE} wrote. {@link #read(byte, ByteBuf)} is the current shape and
     * expects no name.
     *
     * <p>{@code bytes} is exactly the tag's own encoding, so the read is length-checked and cannot
     * run past it into whatever the surrounding payload holds.</p>
     *
     * @param tagId the tag id that was read from the stream
     * @param bytes the tag's {@code name + payload} bytes
     */
    public Tag readLegacy(byte tagId, byte[] bytes) {
        var in = new DataInputStream(new BoundedInputStream(bytes));
        return switch (tagId) {
            case Tag.TAG_END -> EndTag.INSTANCE;
            case Tag.TAG_BYTE -> load(ByteTag.TYPE, in);
            case Tag.TAG_SHORT -> load(ShortTag.TYPE, in);
            case Tag.TAG_INT -> load(IntTag.TYPE, in);
            case Tag.TAG_LONG -> load(LongTag.TYPE, in);
            case Tag.TAG_FLOAT -> load(FloatTag.TYPE, in);
            case Tag.TAG_DOUBLE -> load(DoubleTag.TYPE, in);
            case Tag.TAG_BYTE_ARRAY -> load(ByteArrayTag.TYPE, in);
            case Tag.TAG_STRING -> load(StringTag.TYPE, in);
            case Tag.TAG_LIST -> load(ListTag.TYPE, in);
            case Tag.TAG_COMPOUND -> load(CompoundTag.TYPE, in);
            case Tag.TAG_INT_ARRAY -> load(IntArrayTag.TYPE, in);
            case Tag.TAG_LONG_ARRAY -> load(LongArrayTag.TYPE, in);
            default -> throw new IllegalArgumentException("Unknown tag id " + tagId);
        };
    }

    private Tag load(TagType<?> type, DataInput in) {
        try {
            return type.load(in, 0, NbtAccounter.UNLIMITED);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The tag's own bytes as an input stream that ends exactly at the last one, so a decoding mistake
     * fails here instead of reading the next value's bytes.
     */
    private static final class BoundedInputStream extends InputStream {

        private final byte[] bytes;
        private int index;

        private BoundedInputStream(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public int read() {
            return index < bytes.length ? bytes[index++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] into, int offset, int length) {
            if (index >= bytes.length) return -1;
            var count = Math.min(length, bytes.length - index);
            System.arraycopy(bytes, index, into, offset, count);
            index += count;
            return count;
        }

        @Override
        public int available() {
            return bytes.length - index;
        }
    }

    /**
     * Converts the carrier's values back into an NBT {@link Tag} tree — the native twin of
     * and what reads a payload written before the migration was
     * made (its id+payload bytes decode into exactly these values).
     *
     * <p><strong>Limitation:</strong> the carrier has more shapes than NBT has tags. The four the
     * conversion also refused are refused here — a value-keyed map ({@code OBJECT_MAP}), the two
     * primitive-keyed maps and a custom payload — and so is {@code SELF} (an opaque Java object),
     * all with {@link MatchException}. A character is refused as well, because NBT has no tag for it
     * and silently widening it to a short would change the type on the way back.</p>
     *
     * <p><strong>Caveats:</strong> {@code null} becomes {@link EndTag#INSTANCE} (not a Java
     * {@code null} tag); a list whose <em>first</em> element is null produces a {@code ListTag} of
     * element type {@code TAG_End}, and heterogeneous lists are typed after their first element, so
     * they do not survive a round-trip. A {@code null} inside a string map throws
     * {@link NullPointerException}.</p>
     *
     * @param value the carrier value to convert
     * @return the equivalent NBT tree
     * @throws MatchException if the value has no NBT equivalent
     * @see #convertToValue(Tag)
     */
    public Tag convertToTag(Object value) {
        // One pattern switch over the value: naming the type with getTypeId and then reading it back
        // through the ops consults the same table twice per node.
        return switch (value) {
            case null -> EndTag.INSTANCE;
            case ValueOps.Null ignored -> EndTag.INSTANCE;
            case Integer number -> IntTag.valueOf(number);
            case Long number -> LongTag.valueOf(number);
            case String text -> StringTag.valueOf(text);
            case Boolean flag -> ByteTag.valueOf(flag ? (byte) 1 : (byte) 0);
            case Byte number -> ByteTag.valueOf(number);
            case Short number -> ShortTag.valueOf(number);
            case Float number -> FloatTag.valueOf(number);
            case Double number -> DoubleTag.valueOf(number);
            case byte[] bytes -> new ByteArrayTag(bytes);
            case int[] numbers -> new IntArrayTag(numbers);
            case long[] numbers -> new LongArrayTag(numbers);
            case List<?> values -> {
                if (values.isEmpty()) yield new ListTag();
                var tags = new ObjectArrayList<Tag>(values.size());
                for (var element : values) {
                    tags.add(convertToTag(element));
                }
                yield new ListTag(tags, tags.getFirst().getId());
            }
            // NBT holds only string-keyed compounds, so the maps the old conversion refused are refused
            // here too — before the plain Map case, or they would match it
            case Reference2ObjectMap<?, ?> ignored -> throw unsupported(value);
            case Reference2ReferenceMap<?, ?> ignored -> throw unsupported(value);
            case Object2ReferenceMap<?, ?> ignored -> throw unsupported(value);
            case Object2ObjectMap<?, ?> ignored -> throw unsupported(value);
            case Int2ObjectMap<?> ignored -> throw unsupported(value);
            case Long2ObjectMap<?> ignored -> throw unsupported(value);
            case Map<?, ?> entries -> {
                var compoundTag = new CompoundTag();
                entries.forEach((key, element) -> compoundTag.put((String) key, convertToTag(element)));
                yield compoundTag;
            }
            default -> throw unsupported(value);
        };
    }

    /**
     * The refusal of a carrier value NBT has no tag for: {@code OBJECT_MAP}, {@code INT_MAP}, {@code LONG_MAP}, a custom payload, {@code SELF} or a {@code Character}.
     */
    private static MatchException unsupported(Object value) {
        return new MatchException("No Tag equivalent for carrier type " + value.getClass().getName(), null);
    }

    /**
     * No-op retained for initialization-call compatibility: this utility holds no state that
     * needs eager setup, and {@code DataSyncLib} calls it during mod construction. Safe to call
     * any number of times.
     */
    public void init() {
    }
}
