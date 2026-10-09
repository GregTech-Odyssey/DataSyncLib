package com.gto.datasynclib.datastream.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufOutputStream;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * The {@link ValueOps} implementation over plain Java objects: the carrier is the value itself —
 * {@code Integer}, {@code String}, {@code List}, {@code Map}, … — with no wrapper and no tag of its
 * own. {@link #INSTANCE} is the whole implementation.
 *
 * <table>
 *   <tr><th>{@link Type}</th><th>carrier</th></tr>
 *   <tr><td>{@code NULL}</td><td>{@link Null#INSTANCE} (a Java {@code null} is accepted on the way in)</td></tr>
 *   <tr><td>{@code BOOLEAN}</td><td>reserved: a boolean is stored as a {@code BYTE} of {@code 0}/{@code 1}, see below</td></tr>
 *   <tr><td>{@code BYTE}…{@code DOUBLE}</td><td>{@link Byte}, {@link Short}, {@link Character}, {@link Integer}, {@link Long}, {@link Float}, {@link Double}</td></tr>
 *   <tr><td>{@code STRING}</td><td>{@link String}</td></tr>
 *   <tr><td>{@code BYTE_ARRAY}, {@code INT_ARRAY}, {@code LONG_ARRAY}</td><td>{@code byte[]}, {@code int[]}, {@code long[]}</td></tr>
 *   <tr><td>{@code LIST}</td><td>{@link List} — {@link ArrayList} when created here</td></tr>
 *   <tr><td>{@code STRING_MAP}</td><td>any other {@link Map} — {@link HashMap} when created here</td></tr>
 *   <tr><td>{@code OBJECT_MAP}</td><td>a FastUtil object-keyed map — {@link Object2ObjectMap}, {@link Object2ReferenceMap} or {@link Reference2ReferenceMap}</td></tr>
 *   <tr><td>{@code INT_MAP}, {@code LONG_MAP}</td><td>{@link Int2ObjectMap}, {@link Long2ObjectMap}</td></tr>
 *   <tr><td>{@code CUSTOM}</td><td>{@link Custom} — a registered custom type id plus its payload</td></tr>
 *   <tr><td>{@code SELF}</td><td>anything else — written with Java serialization, so it must implement {@link java.io.Serializable}</td></tr>
 * </table>
 *
 * <h3>Booleans are bytes</h3>
 * <p>{@code BOOLEAN} is declared because the id table has the slot - Data's own table does too - but
 * nothing here produces it: a boolean is a byte of {@code 0}/{@code 1} and travels as {@code BYTE},
 * exactly as the Data type system stores it, so the two carriers write the same bytes for the same
 * value and an old file keeps reading. {@link #createBoolean(boolean)} builds that byte and
 * {@link #getBoolean(Object)} accepts a byte or a {@link Boolean}; {@link #isBoolean(Object)} answers
 * for both shapes, while a byte that is neither {@code 0} nor {@code 1} is just a byte.
 * {@link #read(byte, ByteBuf)} still understands the reserved id, so a carrier that does write it is
 * readable too.</p>
 * * <h3>How the three map shapes are told apart</h3>
 * <p>{@code INT_MAP} and {@code LONG_MAP} are the FastUtil interfaces, and {@code OBJECT_MAP} is any of
 * the three FastUtil object-keyed map interfaces ({@link Object2ObjectMap}, {@link Object2ReferenceMap},
 * {@link Reference2ReferenceMap}) — every other {@code Map}, including a plain {@code HashMap}, is a
 * {@code STRING_MAP} whose keys are read as strings. Creation
 * and inspection therefore agree on every map, including the empty one, which a purely
 * key-based rule could not decide. Because {@code createValueMap} hands its argument straight back,
 * a value-keyed map has to be built as one of these types to be recognized as {@code OBJECT_MAP} —
 * {@link HashMap} is the one this implementation reads values back into.</p>
 *
 * <h3>Creation and reading hand the object straight back</h3>
 * <p>{@code createXxx} returns the collection it is given — the carrier <em>is</em> the Java object, so
 * there is nothing to wrap — and {@code getXxx} likewise returns the carrier's own collection. Neither
 * side copies, so copy a container yourself before mutating one you do not own.</p>
 *
 * <h3>Wire form</h3>
 * <p>{@link #read(byte, ByteBuf)} and {@link #write(Object, ByteBuf)} use the Data encoding of every
 * type ({@code VarInt} lengths, {@code id + payload} nesting, {@code VarLong} long-map keys), so a
 * value stored by the Data type system reads back here unchanged. {@code BOOLEAN} has a payload of
 * its own (one byte) — the id Data's own table reserved for it but never implemented.</p>
 *
 * <p>{@code SELF} is the carrier's escape hatch for whatever the type set does not cover, and it is
 * stored with Java's own serializer: the value has to implement {@link java.io.Serializable}, its
 * bytes travel length-prefixed (see {@link #writeSelf(ByteBuf, Object)}) and reading hands them to an
 * {@link ObjectInputStream}. That makes any serializable object storable without registering a
 * {@link CustomTypes.Type} — at the usual price of Java deserialization, so read only payloads you
 * trust. A value the ops cannot serialize at all (not {@code Serializable}) fails on the way out,
 * where the mistake is visible.</p>
 */
public final class JavaValueOps implements ValueOps {

    public static final JavaValueOps INSTANCE = new JavaValueOps(0);

    private final int dataVersion;

    private JavaValueOps(int dataVersion) {
        this.dataVersion = dataVersion;
    }

    /**
     * The same carrier at {@code dataVersion} — what a stored payload is read with, so a codec can
     * adapt to older shapes through {@link ValueOps#dataVersion()} instead of taking a version
     * parameter. Version {@code 0} is {@link #INSTANCE}.
     */
    public static JavaValueOps create(int dataVersion) {
        return dataVersion == 0 ? INSTANCE : new JavaValueOps(dataVersion);
    }

    @Override
    public int dataVersion() {
        return dataVersion;
    }

    /**
     * A custom value: the id of the registered {@link CustomTypes.Type} it belongs to, plus the
     * payload that type's functions write and read.
     */
    public record Custom(int typeId, Object value) {
    }

    // ===== Type inspection =====

    @Override
    public byte getTypeId(Object data) {
        // Ordered by how often the library actually sees each shape: the scalars and the two common
        // containers first, the rare ones after, and every concrete map interface before the generic
        // Map. javac compiles this into a chain of instanceof checks in source order, so the order is
        // the cost — and the map shapes have to come before Map regardless, or Map would dominate them.
        return switch (data) {
            case null -> Type.NULL;
            case Null ignored -> Type.NULL;
            case Boolean ignored -> Type.BYTE;
            case Integer ignored -> Type.INT;
            case Long ignored -> Type.LONG;
            case String ignored -> Type.STRING;
            case Byte ignored -> Type.BYTE;
            case Short ignored -> Type.SHORT;
            case Character ignored -> Type.CHAR;
            case Float ignored -> Type.FLOAT;
            case Double ignored -> Type.DOUBLE;
            case byte[] ignored -> Type.BYTE_ARRAY;
            case int[] ignored -> Type.INT_ARRAY;
            case long[] ignored -> Type.LONG_ARRAY;
            case List<?> ignored -> Type.LIST;
            case Reference2ObjectMap<?, ?> ignored -> Type.OBJECT_MAP;
            case Reference2ReferenceMap<?, ?> ignored -> Type.OBJECT_MAP;
            case Object2ReferenceMap<?, ?> ignored -> Type.OBJECT_MAP;
            case Object2ObjectMap<?, ?> ignored -> Type.OBJECT_MAP;
            case Int2ObjectMap<?> ignored -> Type.INT_MAP;
            case Long2ObjectMap<?> ignored -> Type.LONG_MAP;
            case Map<?, ?> ignored -> Type.STRING_MAP;
            case Custom ignored -> Type.CUSTOM;
            default -> Type.SELF;
        };
    }

    @Override
    public boolean isNull(Object data) {
        // one identity test, and a Java null still answers yes
        return data == null || data == Null.INSTANCE;
    }

    @Override
    public boolean isBoolean(Object data) {
        return data instanceof Boolean || (data instanceof Byte value && (value == 0 || value == 1));
    }

    @Override
    public boolean isByte(Object data) {
        return data instanceof Byte;
    }

    @Override
    public boolean isShort(Object data) {
        return data instanceof Short;
    }

    @Override
    public boolean isChar(Object data) {
        return data instanceof Character;
    }

    @Override
    public boolean isInt(Object data) {
        return data instanceof Integer;
    }

    @Override
    public boolean isLong(Object data) {
        return data instanceof Long;
    }

    @Override
    public boolean isFloat(Object data) {
        return data instanceof Float;
    }

    @Override
    public boolean isDouble(Object data) {
        return data instanceof Double;
    }

    @Override
    public boolean isString(Object data) {
        return data instanceof String;
    }

    @Override
    public boolean isByteArray(Object data) {
        return data instanceof byte[];
    }

    @Override
    public boolean isIntArray(Object data) {
        return data instanceof int[];
    }

    @Override
    public boolean isLongArray(Object data) {
        return data instanceof long[];
    }

    @Override
    public boolean isList(Object data) {
        return data instanceof List;
    }

    @Override
    public boolean isStringMap(Object data) {
        // asked directly instead of through getTypeId: a Map is a Map, and only the six value-keyed
        // map interfaces that would otherwise answer "yes" have to be ruled out first
        return data instanceof Map && !(data instanceof Reference2ObjectMap
                || data instanceof Reference2ReferenceMap
                || data instanceof Object2ReferenceMap
                || data instanceof Object2ObjectMap
                || data instanceof Int2ObjectMap
                || data instanceof Long2ObjectMap);
    }

    @Override
    public boolean isValueMap(Object data) {
        return data instanceof Reference2ObjectMap
                || data instanceof Reference2ReferenceMap
                || data instanceof Object2ReferenceMap
                || data instanceof Object2ObjectMap;
    }

    @Override
    public boolean isIntMap(Object data) {
        return data instanceof Int2ObjectMap;
    }

    @Override
    public boolean isLongMap(Object data) {
        return data instanceof Long2ObjectMap;
    }

    @Override
    public boolean isSelf(Object data) {
        return getTypeId(data) == Type.SELF;
    }

    @Override
    public boolean isCustom(Object data) {
        return data instanceof Custom;
    }

    // ===== Creation =====

    @Override
    public Object createNull() {
        return Null.INSTANCE;
    }

    @Override
    public Object createBoolean(boolean value) {
        return value;
    }

    @Override
    public Object createByte(byte value) {
        return value;
    }

    @Override
    public Object createShort(short value) {
        return value;
    }

    @Override
    public Object createChar(char value) {
        return value;
    }

    @Override
    public Object createInt(int value) {
        return value;
    }

    @Override
    public Object createLong(long value) {
        return value;
    }

    @Override
    public Object createFloat(float value) {
        return value;
    }

    @Override
    public Object createDouble(double value) {
        return value;
    }

    // ===== Boxed creators: the wrapper is already the carrier, so hand it straight back =====

    @Override
    public Object createBoolean(Boolean value) {
        return value;
    }

    @Override
    public Object createByte(Byte value) {
        return value;
    }

    @Override
    public Object createShort(Short value) {
        return value;
    }

    @Override
    public Object createChar(Character value) {
        return value;
    }

    @Override
    public Object createInt(Integer value) {
        return value;
    }

    @Override
    public Object createLong(Long value) {
        return value;
    }

    @Override
    public Object createFloat(Float value) {
        return value;
    }

    @Override
    public Object createDouble(Double value) {
        return value;
    }

    @Override
    public Object createString(String value) {
        return value;
    }

    @Override
    public Object createByteArray(byte[] value) {
        return value;
    }

    @Override
    public Object createIntArray(int[] value) {
        return value;
    }

    @Override
    public Object createLongArray(long[] value) {
        return value;
    }

    @Override
    public Object createList(List<Object> elements) {
        return elements;
    }

    @Override
    public Object createStringMap(Map<String, Object> entries) {
        return entries;
    }

    @Override
    public Object createValueMap(Map<Object, Object> entries) {
        return entries;
    }

    @Override
    public Object createIntMap(Int2ObjectMap<Object> entries) {
        return entries;
    }

    @Override
    public Object createLongMap(Long2ObjectMap<Object> entries) {
        return entries;
    }

    @Override
    public <V> Object createSelf(V value) {
        return value;
    }

    @Override
    public <V> Object createCustom(int typeId, V value) {
        if (CustomTypes.type(typeId) == null) {
            throw new IllegalArgumentException("No custom data type is registered for id " + typeId);
        }
        return new Custom(typeId, value);
    }

    // ===== Reads: unchecked (cast) flavour =====

    @Override
    public boolean getBoolean(Object data) {
        // a boolean is stored as a byte of 0/1, so that is the shape a decoded one arrives in: check it
        // first and the Boolean case stays for values the caller built itself
        if (data instanceof Byte value) {
            return value == 1;
        }
        if (data instanceof Boolean value) {
            return value;
        }
        throw new IllegalArgumentException("Not a boolean: " + describe(data));
    }

    @Override
    public byte getByte(Object data) {
        return (Byte) data;
    }

    @Override
    public short getShort(Object data) {
        return (Short) data;
    }

    @Override
    public char getChar(Object data) {
        return (Character) data;
    }

    @Override
    public int getInt(Object data) {
        return (Integer) data;
    }

    @Override
    public long getLong(Object data) {
        return (Long) data;
    }

    @Override
    public float getFloat(Object data) {
        return (Float) data;
    }

    @Override
    public double getDouble(Object data) {
        return (Double) data;
    }

    // ===== Boxed reads: the wrapper already is the carrier, so hand it over without unboxing =====

    @Override
    public Boolean getBooleanBoxed(Object data) {
        if (data instanceof Boolean value) {
            return value;
        }
        if (data instanceof Byte value) {
            return value == 1;
        }
        throw new IllegalArgumentException("Not a boolean: " + describe(data));
    }

    @Override
    public Byte getByteBoxed(Object data) {
        return (Byte) data;
    }

    @Override
    public Short getShortBoxed(Object data) {
        return (Short) data;
    }

    @Override
    public Character getCharBoxed(Object data) {
        return (Character) data;
    }

    @Override
    public Integer getIntBoxed(Object data) {
        return (Integer) data;
    }

    @Override
    public Long getLongBoxed(Object data) {
        return (Long) data;
    }

    @Override
    public Float getFloatBoxed(Object data) {
        return (Float) data;
    }

    @Override
    public Double getDoubleBoxed(Object data) {
        return (Double) data;
    }

    @Override
    public String getString(Object data) {
        return (String) data;
    }

    @Override
    public byte[] getByteArray(Object data) {
        return (byte[]) data;
    }

    @Override
    public int[] getIntArray(Object data) {
        return (int[]) data;
    }

    @Override
    public long[] getLongArray(Object data) {
        return (long[]) data;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<Object> getList(Object data) {
        return (List<Object>) data;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, Object> getStringMap(Object data) {
        return (Map<String, Object>) data;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<Object, Object> getValueMap(Object data) {
        return (Map<Object, Object>) data;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Int2ObjectMap<Object> getIntMap(Object data) {
        return (Int2ObjectMap<Object>) data;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Long2ObjectMap<Object> getLongMap(Object data) {
        return (Long2ObjectMap<Object>) data;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V getSelf(Object data) {
        return (V) data;
    }

    @Override
    public int getCustomId(Object data) {
        return ((Custom) data).typeId();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <V> V getCustom(Object data) {
        return (V) ((Custom) data).value();
    }

    // ===== Binary form =====

    @Override
    public void write(Object data, ByteBuf stream) {
        switch (data) {
            case null -> {
            }
            case Null ignored -> {
            }
            case Boolean value -> stream.writeByte(value ? 1 : 0);
            case Byte value -> stream.writeByte(value);
            case Short value -> stream.writeShort(value);
            case Character value -> stream.writeChar(value);
            case Integer value -> stream.writeInt(value);
            case Long value -> stream.writeLong(value);
            case Float value -> stream.writeFloat(value);
            case Double value -> stream.writeDouble(value);
            case String value -> writeString(stream, value);
            case byte[] value -> writeByteArray(stream, value);
            case int[] value -> writeIntArray(stream, value);
            case long[] value -> writeLongArray(stream, value);
            case List<?> elements -> {
                VarInts.write(stream, elements.size());
                for (var element : elements) {
                    writeValue(element, stream);
                }
            }
            // the value-keyed and the primitive-keyed maps are Maps as well, so they must come first
            case Reference2ReferenceMap<?, ?> entries -> writeValueMap(stream, entries);
            case Reference2ObjectMap<?, ?> entries -> writeValueMap(stream, entries);
            case Object2ReferenceMap<?, ?> entries -> writeValueMap(stream, entries);
            // Object2ObjectMap is a Map too, and getTypeId calls it OBJECT_MAP: without this case it
            // would fall into the string-keyed case below and be written with STRING_MAP framing
            // under the OBJECT_MAP id
            case Object2ObjectMap<?, ?> entries -> writeValueMap(stream, entries);
            case Int2ObjectMap<?> entries -> {
                VarInts.write(stream, entries.size());
                for (var entry : entries.int2ObjectEntrySet()) {
                    VarInts.write(stream, entry.getIntKey());
                    writeValue(entry.getValue(), stream);
                }
            }
            case Long2ObjectMap<?> entries -> {
                VarInts.write(stream, entries.size());
                for (var entry : entries.long2ObjectEntrySet()) {
                    VarInts.writeVarLong(stream, entry.getLongKey());
                    writeValue(entry.getValue(), stream);
                }
            }
            case Map<?, ?> entries -> {
                VarInts.write(stream, entries.size());
                entries.forEach((key, value) -> {
                    writeString(stream, (String) key);
                    writeValue(value, stream);
                });
            }
            case Custom custom -> {
                VarInts.write(stream, custom.typeId());
                writeCustom(custom, stream);
            }
            default -> writeSelf(stream, data);
        }
    }

    @Override
    public Object read(byte id, ByteBuf stream) {
        return switch (id) {
            case Type.NULL -> Null.INSTANCE;
            case Type.BOOLEAN -> stream.readBoolean();
            case Type.BYTE -> stream.readByte();
            case Type.SHORT -> stream.readShort();
            case Type.CHAR -> stream.readChar();
            case Type.INT -> stream.readInt();
            case Type.LONG -> stream.readLong();
            case Type.FLOAT -> stream.readFloat();
            case Type.DOUBLE -> stream.readDouble();
            case Type.STRING -> readString(stream);
            case Type.BYTE_ARRAY -> readByteArray(stream);
            case Type.INT_ARRAY -> readIntArray(stream);
            case Type.LONG_ARRAY -> readLongArray(stream);
            case Type.LIST -> readList(stream);
            case Type.STRING_MAP -> readStringMap(stream);
            case Type.OBJECT_MAP -> readValueMap(stream);
            case Type.INT_MAP -> readIntMap(stream);
            case Type.LONG_MAP -> readLongMap(stream);
            case Type.CUSTOM -> readCustom(stream);
            case Type.SELF -> readSelf(stream);
            default -> throw new IllegalArgumentException("Unknown type id: " + id);
        };
    }

    // ===== Nested values: id byte + payload, the layout Data.writeData / Data.readData use =====

    private static void writeValueMap(ByteBuf stream, Map<?, ?> entries) {
        VarInts.write(stream, entries.size());
        for (var entry : entries.entrySet()) {
            INSTANCE.writeValue(entry.getKey(), stream);
            INSTANCE.writeValue(entry.getValue(), stream);
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>The carrier can name the type of a scalar without a second lookup, so the id byte and the
     * payload come out of one switch here instead of {@link #getTypeId(Object)} followed by
     * {@link #write(Object, ByteBuf)}. That is exactly where the format is repetitive — every element
     * of a list, every key and every value of a map — so the scalars take the fused path and the
     * containers, whose cost is in their elements rather than in being named, keep the generic one.</p>
     */
    @Override
    public void writeValue(Object data, ByteBuf stream) {
        switch (data) {
            case null -> stream.writeByte(Type.NULL);
            case Null ignored -> stream.writeByte(Type.NULL);
            case Boolean value -> {
                stream.writeByte(Type.BYTE);
                stream.writeByte(value ? 1 : 0);
            }
            case Integer value -> {
                stream.writeByte(Type.INT);
                stream.writeInt(value);
            }
            case Long value -> {
                stream.writeByte(Type.LONG);
                stream.writeLong(value);
            }
            case String value -> {
                stream.writeByte(Type.STRING);
                writeString(stream, value);
            }
            case Byte value -> {
                stream.writeByte(Type.BYTE);
                stream.writeByte(value);
            }
            case Short value -> {
                stream.writeByte(Type.SHORT);
                stream.writeShort(value);
            }
            case Character value -> {
                stream.writeByte(Type.CHAR);
                stream.writeChar(value);
            }
            case Float value -> {
                stream.writeByte(Type.FLOAT);
                stream.writeFloat(value);
            }
            case Double value -> {
                stream.writeByte(Type.DOUBLE);
                stream.writeDouble(value);
            }
            case byte[] value -> {
                stream.writeByte(Type.BYTE_ARRAY);
                writeByteArray(stream, value);
            }
            case int[] value -> {
                stream.writeByte(Type.INT_ARRAY);
                writeIntArray(stream, value);
            }
            case long[] value -> {
                stream.writeByte(Type.LONG_ARRAY);
                writeLongArray(stream, value);
            }
            default -> {
                stream.writeByte(getTypeId(data));
                write(data, stream);
            }
        }
    }

    @Override
    public Object readValue(ByteBuf stream) {
        return read(stream.readByte(), stream);
    }

    public static int initialCapacity(int size) {
        return Math.min(size, ByteBufCodecs.MAX_INITIAL_COLLECTION_SIZE);
    }

    public static void writeString(ByteBuf stream, String value) {
        writeByteArray(stream, value.getBytes(StandardCharsets.UTF_8));
    }

    public static String readString(ByteBuf stream) {
        return new String(readByteArray(stream), StandardCharsets.UTF_8);
    }

    public static void writeByteArray(ByteBuf stream, byte[] value) {
        VarInts.write(stream, value.length);
        stream.writeBytes(value);
    }

    public static byte[] readByteArray(ByteBuf stream) {
        var value = new byte[VarInts.read(stream)];
        stream.readBytes(value);
        return value;
    }

    public static void writeIntArray(ByteBuf stream, int[] value) {
        VarInts.write(stream, value.length);
        for (var element : value) {
            stream.writeInt(element);
        }
    }

    public static int[] readIntArray(ByteBuf stream) {
        var value = new int[VarInts.read(stream)];
        for (int i = 0; i < value.length; i++) {
            value[i] = stream.readInt();
        }
        return value;
    }

    public static void writeLongArray(ByteBuf stream, long[] value) {
        VarInts.write(stream, value.length);
        for (var element : value) {
            stream.writeLong(element);
        }
    }

    public static long[] readLongArray(ByteBuf stream) {
        var value = new long[VarInts.read(stream)];
        for (int i = 0; i < value.length; i++) {
            value[i] = stream.readLong();
        }
        return value;
    }

    public List<Object> readList(ByteBuf stream) {
        var size = VarInts.read(stream);
        var list = new ArrayList<>(initialCapacity(size));
        for (int i = 0; i < size; i++) {
            list.add(readValue(stream));
        }
        return list;
    }

    public Map<String, Object> readStringMap(ByteBuf stream) {
        var size = VarInts.read(stream);
        var map = new HashMap<String, Object>(initialCapacity(size));
        for (int i = 0; i < size; i++) {
            var key = readString(stream);
            map.put(key, readValue(stream));
        }
        return map;
    }

    public Map<Object, Object> readValueMap(ByteBuf stream) {
        var size = VarInts.read(stream);
        var map = new HashMap<>(initialCapacity(size));
        for (int i = 0; i < size; i++) {
            var key = readValue(stream);
            map.put(key, readValue(stream));
        }
        return map;
    }

    public Int2ObjectMap<Object> readIntMap(ByteBuf stream) {
        var size = VarInts.read(stream);
        var map = new Int2ObjectOpenHashMap<>(initialCapacity(size));
        for (int i = 0; i < size; i++) {
            var key = VarInts.read(stream);
            map.put(key, readValue(stream));
        }
        return map;
    }

    public Long2ObjectMap<Object> readLongMap(ByteBuf stream) {
        var size = VarInts.read(stream);
        var map = new Long2ObjectOpenHashMap<>(initialCapacity(size));
        for (int i = 0; i < size; i++) {
            var key = VarInts.readVarLong(stream);
            map.put(key, readValue(stream));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    public static void writeCustom(Custom custom, ByteBuf stream) {
        var type = CustomTypes.type(custom.typeId());
        if (type == null) {
            throw new IllegalArgumentException("No custom data type is registered for id " + custom.typeId());
        }
        ((CustomTypes.Type<Object>) type).write().accept(custom.value(), stream);
    }

    @SuppressWarnings("unchecked")
    public static Custom readCustom(ByteBuf stream) {
        var typeId = VarInts.read(stream);
        var type = CustomTypes.type(typeId);
        if (type == null) {
            throw new IllegalArgumentException("No custom data type is registered for id " + typeId);
        }
        return new Custom(typeId, ((CustomTypes.Type<Object>) type).read().apply(stream));
    }

    /**
     * Writes a {@link Type#SELF} value with Java's own serializer, length-prefixed. The prefix is not
     * decoration: an {@link ObjectInputStream} reads ahead into a block buffer, so the payload has to
     * be bounded before it is handed over, or it would swallow the values framed after it.
     *
     * @throws UncheckedIOException if {@code data} does not implement {@link java.io.Serializable}
     */
    public static void writeSelf(ByteBuf stream, Object data) {
        try {
            var out = new ObjectOutputStream(new ByteBufOutputStream(stream));
            out.writeObject(data);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not serialize a SELF value (" + describe(data)
                    + "): it has to implement java.io.Serializable", e);
        }
    }

    /**
     * Reads back a {@link #writeSelf(ByteBuf, Object)} payload. Only trusted bytes belong here:
     * deserialization runs the payload's own {@code readObject}.
     *
     * @throws IllegalArgumentException if the payload names a class this side does not have
     * @throws UncheckedIOException     if the payload is not a readable serialized object
     */
    public static Object readSelf(ByteBuf stream) {
        try {
            return new ObjectInputStream(new ByteBufInputStream(stream)).readObject();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not deserialize a SELF value", e);
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("A SELF value names a class that is not on this classpath: " + e.getMessage(), e);
        }
    }

    public static String describe(Object data) {
        return data == null || data == Null.INSTANCE ? "null" : data.getClass().getName();
    }
}
