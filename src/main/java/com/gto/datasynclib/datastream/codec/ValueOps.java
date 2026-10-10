package com.gto.datasynclib.datastream.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The carrier abstraction behind {@link ValueCodec}: everything a codec may do to a value without
 * knowing what a value physically is — inspect it, build one, read one, and move one to and from a
 * buffer. This is the role Mojang's {@code DynamicOps} plays for DFU codecs, on top of this
 * library's own type set.
 *
 * <p>The carrier is plain Java objects, exactly what {@link JavaValueOps} builds — {@code Integer},
 * {@code String}, {@code List}, {@code Map}, … — which is why the interface names no type of its own:
 * like a DFU {@code Codec}, a {@link ValueCodec} has a single type parameter, the value it handles,
 * and the carrier is the runtime shape those values take on the way through. The interface is
 * written so another carrier (NBT, JSON, a wrapper type) can be added without touching a single
 * codec. Implementations are stateless, so each exposes a single {@code INSTANCE}.</p>
 *
 * <h3>The type set and its ids</h3>
 * <p>{@link Type} is the value model, and its ids are the <strong>Data wire ids</strong> — the ids
 * the Data type system has always used, with {@code BOOLEAN = 1} kept as the reserved slot Data's own
 * table has for it (a boolean travels as a byte, so nothing produces that id). The three container
 * maps are distinct types ({@code STRING_MAP}, {@code INT_MAP}, {@code LONG_MAP}) exactly as they are
 * on the wire, and {@code SELF} is the carrier's own escape hatch (see below). The id
 * {@code OBJECT_MAP} used to hold — a map keyed by carrier values — is retired: a map is a
 * {@code STRING_MAP} and its keys have to be strings.</p>
 *
 * <p>On top of that type set sit the <em>derived</em> views - {@code boolean[]}, {@code short[]},
 * {@code char[]}, {@code float[]}, {@code double[]}, {@link java.util.UUID} and
 * {@link java.math.BigInteger} - which are packings over the arrays above and add no id of their own
 * (see the end of this interface).</p>
 *
 * <table>
 *   <tr><th>id</th><th>Type</th><th>carrier</th><th>where the id came from</th></tr>
 *   <tr><td>0</td><td>NULL</td><td>{@link Null#INSTANCE}, and a Java {@code null} on the way in</td><td>the old null marker</td></tr>
 *   <tr><td>1</td><td>BOOLEAN</td><td>reserved — stored as a {@code Byte} of {@code 0}/{@code 1}</td><td>{@code ByteData} 0/1</td></tr>
 *   <tr><td>2…8</td><td>BYTE … DOUBLE</td><td>{@code Byte} … {@code Double}</td><td>the numeric records</td></tr>
 *   <tr><td>9…11</td><td>BYTE_ARRAY, INT_ARRAY, LONG_ARRAY</td><td>{@code byte[]}, {@code int[]}, {@code long[]}</td><td>the array Data</td></tr>
 *   <tr><td>12</td><td>STRING</td><td>{@code String}</td><td>{@code StringData}</td></tr>
 *   <tr><td>13</td><td>LIST</td><td>{@code List<>}</td><td>the old list type</td></tr>
 *   <tr><td>14</td><td>STRING_MAP</td><td>{@code Map<String, Object>}</td><td>the old string map</td></tr>
 *   <tr><td>15</td><td>CUSTOM</td><td>a registered {@link CustomTypes.Type}</td><td>the old custom wrapper</td></tr>
 *   <tr><td>16</td><td>retired</td><td>—</td><td>{@code DataMapData}, a map keyed by carrier values</td></tr>
 *   <tr><td>17</td><td>INT_MAP</td><td>{@code Int2ObjectMap<>}</td><td>{@code IntMapData}</td></tr>
 *   <tr><td>18</td><td>LONG_MAP</td><td>{@code Long2ObjectMap<>}</td><td>{@code LongMapData}</td></tr>
 *   <tr><td>19</td><td>SELF</td><td>the value itself</td><td>—</td></tr>
 * </table>
 *
 * <h3>The two read sets</h3>
 * <ul>
 *   <li><strong>{@code getXxx} — unchecked.</strong> One per type: cast the carrier and read it.
 *       Wrong type means a {@link ClassCastException}, which is the fast path for a carrier the
 *       caller has already validated (and the only path that never boxes).</li>
 *   <li><strong>{@code isXxx} — instanceof.</strong> One predicate per type, for validating
 *       untrusted input or dispatching on what the carrier holds:
 *       {@code if (ops.isInt(data)) return ops.getInt(data);} They exist so a caller never has to
 *       catch a cast failure to find out what it is holding.</li>
 * </ul>
 *
 * <h3>Boxed creators and readers</h3>
 * <p>A codec's type parameter is usually a wrapper — {@code ValueCodec<Integer>} has to come
 * back with an {@code Integer} — so both directions have a boxed form: {@code createInt(int)} and
 * {@code createInt(Integer)} on the way in, {@code getInt(Object)} and {@code getIntBoxed(Object)}
 * on the way out. The primitive form is for code that wants the primitive, the boxed form for code
 * that already holds (or must return) the wrapper: {@link JavaValueOps} overrides every boxed member
 * to hand the object over untouched, so a decode boxes exactly once — in the caller's own signature —
 * and never unboxes to box again.</p>
 *
 * <h3>Binary form</h3>
 * <p>{@link #getTypeId(Object)} reports the id, {@link #write(Object, ByteBuf)} writes the
 * <em>payload</em> and {@link #read(byte, ByteBuf)} reads one back for a given id — the id byte
 * itself belongs to the caller, exactly as {@code Data.writeData} / {@code Data.readData} split it.
 * A carrier written this way is byte-compatible with the Data encoding of the same value, so the
 * native implementation can take over from the Data one without changing a single stored file.</p>
 *
 * <h3>SELF</h3>
 * <p>{@code SELF} is the carrier's own opaque value: an object the ops recognizes as a value but
 * cannot break into primitives — for the native implementation, anything that is not one of the
 * types above. {@link #createSelf(Object)} and {@link #getSelf(Object)} are the identity there, and
 * a SELF value has no wire form of its own: a codec that knows how to serialize it is what turns it
 * into something writable. {@link ValueConverters} is the other way out — register a converter for the
 * class and the ops writes an ordinary carrier value in its place, {@link #getSelf(Object, Class)}
 * being the read side that asks for the value back as its own type.</p>
 */
public interface ValueOps {

    /**
     * The carrier's null value: <strong>one shared object</strong>, the object {@link #createNull()}
     * hands out and the {@code NULL} id reads back as. Absence is a value here rather than a Java
     * {@code null}, which is what lets the layer stop second-guessing: a container can hold it, a
     * codec can return it, {@link #isNull(Object)} is an identity test — and a Java {@code null} is
     * still accepted everywhere, so a caller that passes one is not punished for it.
     */
    enum Null {
        INSTANCE;

        @Override
        public String toString() {
            return "null";
        }
    }

    /**
     * The value model, as ids — the Data wire ids, so a carrier can be stored in the layout the Data
     * type system defined.
     *
     * <p>An interface rather than a class or enum on purpose: the constants are then implicitly
     * {@code public static final} bytes (the same shape {@code Data} itself uses) with no way to
     * reassign one by accident.</p>
     */
    interface Type {

        byte NULL = 0;
        byte BOOLEAN = 1;
        byte BYTE = 2;
        byte SHORT = 3;
        byte CHAR = 4;
        byte INT = 5;
        byte LONG = 6;
        byte FLOAT = 7;
        byte DOUBLE = 8;
        byte BYTE_ARRAY = 9;
        byte INT_ARRAY = 10;
        byte LONG_ARRAY = 11;
        byte STRING = 12;
        byte LIST = 13;
        byte STRING_MAP = 14;
        byte CUSTOM = 15;
        // 16 is retired: it held OBJECT_MAP, the map keyed by carrier values. Every map is a
        // STRING_MAP now, so nothing may take the id back — a stored 16 is not readable.
        byte INT_MAP = 17;
        byte LONG_MAP = 18;
        byte SELF = 19;
    }

    // ===== Type inspection (instanceof flavour) =====

    boolean isNull(Object data);

    boolean isBoolean(Object data);

    boolean isByte(Object data);

    boolean isShort(Object data);

    boolean isChar(Object data);

    boolean isInt(Object data);

    boolean isLong(Object data);

    boolean isFloat(Object data);

    boolean isDouble(Object data);

    boolean isString(Object data);

    boolean isByteArray(Object data);

    boolean isIntArray(Object data);

    boolean isLongArray(Object data);

    boolean isList(Object data);

    boolean isStringMap(Object data);

    boolean isIntMap(Object data);

    boolean isLongMap(Object data);

    /**
     * Whether {@code data} is this carrier's opaque value — see the class documentation.
     */
    boolean isSelf(Object data);

    boolean isCustom(Object data);

    /**
     * @return the id of the custom type {@code data} was created with
     */
    int getCustomId(Object data);

    // ===== Creation =====

    /**
     * Creates the null carrier — the absent value of the whole library.
     */
    Object createNull();

    /**
     * Creates a boolean, which is stored as a byte of {@code 0}/{@code 1} — the shape the Data encoding uses too.
     */
    Object createBoolean(boolean value);

    Object createByte(byte value);

    Object createShort(short value);

    Object createChar(char value);

    Object createInt(int value);

    Object createLong(long value);

    Object createFloat(float value);

    Object createDouble(double value);

    // ===== Boxed creators: the same values, taken as the wrapper a codec already holds =====
    // A ValueCodec<Integer> is generic over the wrapper, so by the time it encodes it holds an
    // Integer: going through the primitive method below costs an unbox and the carrier boxes it right
    // back. These overloads are the way out — the default unboxes into the primitive method, and a
    // carrier that can store the object as it stands (JavaValueOps) overrides them to return the
    // argument, which leaves encode with the single boxing the codec's own type parameter forced.

    /**
     * The boxed form of {@link #createBoolean(boolean)}.
     */
    default Object createBooleanBoxed(Boolean value) {
        return createBoolean(value);
    }

    /**
     * The boxed form of {@link #createByte(byte)}.
     */
    default Object createByteBoxed(Byte value) {
        return createByte(value);
    }

    /**
     * The boxed form of {@link #createShort(short)}.
     */
    default Object createShortBoxed(Short value) {
        return createShort(value);
    }

    /**
     * The boxed form of {@link #createChar(char)}.
     */
    default Object createCharBoxed(Character value) {
        return createChar(value);
    }

    /**
     * The boxed form of {@link #createInt(int)}.
     */
    default Object createIntBoxed(Integer value) {
        return createInt(value);
    }

    /**
     * The boxed form of {@link #createLong(long)}.
     */
    default Object createLongBoxed(Long value) {
        return createLong(value);
    }

    /**
     * The boxed form of {@link #createFloat(float)}.
     */
    default Object createFloatBoxed(Float value) {
        return createFloat(value);
    }

    /**
     * The boxed form of {@link #createDouble(double)}.
     */
    default Object createDoubleBoxed(Double value) {
        return createDouble(value);
    }

    Object createString(String value);

    Object createByteArray(byte[] value);

    Object createIntArray(int[] value);

    Object createLongArray(long[] value);

    /**
     * Creates a list from carrier values, in order.
     */
    Object createList(List<Object> elements);

    /**
     * Creates a map keyed by strings, from carrier values. Every map is this one: a map whose keys are
     * not strings has no id of its own and cannot be stored.
     */
    Object createStringMap(Map<String, Object> entries);

    Object createIntMap(Int2ObjectMap<Object> entries);

    Object createLongMap(Long2ObjectMap<Object> entries);

    /**
     * Wraps an opaque value — the carrier's own {@link Type#SELF} form.
     */
    <V> Object createSelf(V value);

    /**
     * Creates a custom value of the registered type {@code typeId} around {@code value}.
     *
     * @throws IllegalArgumentException if no custom type is registered under {@code typeId}
     */
    <V> Object createCustom(CustomTypes.Type<V> type, V value);

    // ===== Reads: unchecked (cast) flavour =====

    /**
     * @throws ClassCastException if {@code data} is not a boolean
     */
    boolean getBoolean(Object data);

    byte getByte(Object data);

    short getShort(Object data);

    char getChar(Object data);

    int getInt(Object data);

    long getLong(Object data);

    float getFloat(Object data);

    double getDouble(Object data);

    String getString(Object data);

    byte[] getByteArray(Object data);

    int[] getIntArray(Object data);

    long[] getLongArray(Object data);

    /**
     * @return the list's elements as carrier values — the list itself, not a copy
     */
    List<Object> getList(Object data);

    Map<String, Object> getStringMap(Object data);

    Int2ObjectMap<Object> getIntMap(Object data);

    Long2ObjectMap<Object> getLongMap(Object data);

    /**
     * @return the opaque value this carrier wraps
     */
    <V> V getSelf(Object data);

    /**
     * The opaque value behind {@code data}, read as {@code type}.
     *
     * <p>Two shapes reach here. A value the carrier already holds as {@code type} — the identity
     * case, and the one a {@code SELF} value a codec built itself has — is handed straight back. Any
     * other shape is a payload a registered {@link ValueConverters.Converter} produced on the way out:
     * the one that covers {@code type} turns it back, whether it was declared for {@code type} or
     * covers it through its predicate.</p>
     *
     * @param type the class the value should come back as, and the key the converter is looked up by
     * @return the value, or {@code null} when {@code data} is absent
     * @throws IllegalArgumentException if {@code data} is neither that type nor convertible to it
     */
    default <V> V getSelf(Object data, Class<V> type) {
        if (isNull(data)) return null;
        if (type.isInstance(data)) return type.cast(data);
        var converter = ValueConverters.converter(type);
        if (converter == null) {
            throw new IllegalArgumentException("No converter is registered for " + type.getName()
                    + " and the stored value is a " + describe(data));
        }
        return type.cast(converter.toValue(this, data));
    }

    /**
     * The class name of {@code data}, for the messages of the unchecked getters. A default so a
     * carrier that has no naming of its own still reports something useful.
     */
    default String describe(Object data) {
        return data == null ? "null" : data.getClass().getName();
    }

    /**
     * @return the payload of a custom value, as it was handed to {@link #createCustom(int, Object)}
     */
    <V> V getCustom(CustomTypes.Type<V> type, Object data);


    // ===== Boxed reads: the wrapper form of the primitive getters =====
    // The mirror of the boxed creators: a ValueCodec<Integer> has to return an Integer, so
    // reading through getInt(data) unboxes to int here and the codec boxes it straight back. A carrier
    // that already holds the wrapper (JavaValueOps) overrides these and hands the object over
    // untouched, which turns a decode into the single unboxing the codec's own type parameter forced.

    /**
     * The boxed form of {@link #getBoolean(Object)} — {@code null} is not a boolean, so this never is.
     */
    default Boolean getBooleanBoxed(Object data) {
        return getBoolean(data);
    }

    /**
     * The boxed form of {@link #getByte(Object)}.
     */
    default Byte getByteBoxed(Object data) {
        return getByte(data);
    }

    /**
     * The boxed form of {@link #getShort(Object)}.
     */
    default Short getShortBoxed(Object data) {
        return getShort(data);
    }

    /**
     * The boxed form of {@link #getChar(Object)}.
     */
    default Character getCharBoxed(Object data) {
        return getChar(data);
    }

    /**
     * The boxed form of {@link #getInt(Object)}.
     */
    default Integer getIntBoxed(Object data) {
        return getInt(data);
    }

    /**
     * The boxed form of {@link #getLong(Object)}.
     */
    default Long getLongBoxed(Object data) {
        return getLong(data);
    }

    /**
     * The boxed form of {@link #getFloat(Object)}.
     */
    default Float getFloatBoxed(Object data) {
        return getFloat(data);
    }

    /**
     * The boxed form of {@link #getDouble(Object)}.
     */
    default Double getDoubleBoxed(Object data) {
        return getDouble(data);
    }

    default Object createBooleanArray(boolean[] value) {
        return createSelf(value);
    }

    default boolean[] getBooleanArray(Object data) {
        return getSelf(data) instanceof boolean[] value ? value : ValueOpsConverters.BOOLEAN_ARRAY.toValue(this, data);
    }

    default Object createShortArray(short[] value) {
        return createSelf(value);
    }

    default short[] getShortArray(Object data) {
        return getSelf(data) instanceof short[] value ? value : ValueOpsConverters.SHORT_ARRAY.toValue(this, data);
    }

    default Object createCharArray(char[] value) {
        return createSelf(value);
    }

    default char[] getCharArray(Object data) {
        return getSelf(data) instanceof char[] value ? value : ValueOpsConverters.CHAR_ARRAY.toValue(this, data);
    }

    default Object createFloatArray(float[] value) {
        return createSelf(value);
    }

    default float[] getFloatArray(Object data) {
        return getSelf(data) instanceof float[] value ? value : ValueOpsConverters.FLOAT_ARRAY.toValue(this, data);
    }

    default Object createDoubleArray(double[] value) {
        return createSelf(value);
    }

    default double[] getDoubleArray(Object data) {
        return getSelf(data) instanceof double[] value ? value : ValueOpsConverters.DOUBLE_ARRAY.toValue(this, data);
    }

    default Object createUUID(UUID value) {
        return createSelf(value);
    }

    default UUID getUUID(Object data) {
        return getSelf(data) instanceof UUID value ? value : ValueOpsConverters.UUID.toValue(this, data);
    }

    default Object createBigInteger(BigInteger value) {
        return createSelf(value);
    }

    default BigInteger getBigInteger(Object data) {
        return getSelf(data) instanceof BigInteger value ? value : ValueOpsConverters.BIG_INTEGER.toValue(this, data);
    }

    default Object createBigDecimal(BigDecimal value) {
        return createSelf(value);
    }

    default BigDecimal getBigDecimal(Object data) {
        return getSelf(data) instanceof BigDecimal value ? value : ValueOpsConverters.BIG_DECIMAL.toValue(this, data);
    }

    /**
     * The format version this carrier reads at; {@code 0} when there is none.
     *
     * <p>The version belongs to the carrier rather than to a codec signature: a codec that has to read
     * an older shape asks the ops it was handed, so no {@code decode} overload has to thread a number
     * through every composite or container helper.
     * {@link JavaValueOps#create(int)} hands back the same carrier at another version, and
     * {@link JavaValueOps#INSTANCE} is version {@code 0}.</p>
     */
    default int dataVersion() {
        return 0;
    }
    // ===== Binary form =====

    /**
     * The id of the type {@code data} holds — one of {@link Type}.
     */
    byte getTypeId(Object data);

    /**
     * Writes a complete nested value: the id byte, then the payload of {@link #write(Object, ByteBuf)}.
     * This pair is what the wire format repeats for every list element and every map key and value, so
     * a carrier that can name a type without a second lookup should override this and fold the two
     * steps into one — {@link JavaValueOps} does, which is what keeps a long list of scalars cheap.
     */
    void writeValue(Object value, ByteBuf stream);

    /**
     * Reads a {@link #writeValue(Object, ByteBuf)} back. The id byte is on the wire, so this is a
     * single dispatch for every carrier.
     */
    Object readValue(ByteBuf stream);

    /**
     * The value as a standalone byte array: the id byte followed by the payload — exactly what
     * {@link #getTypeId(Object)} and {@link #write(Object, ByteBuf)} produce, and the layout the Data
     * type system stored a value in. This is what a field is persisted as, and because the ids are
     * Data's, a byte array written by one carrier reads in the other.
     */
    default byte[] toBytes(Object value) {
        var buf = Unpooled.buffer();
        try {
            writeValue(value, buf);
            var bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    /**
     * Reads a {@link #toBytes(Object)} byte array back, id byte included.
     *
     * @throws IllegalArgumentException if the id byte names no type
     */
    default Object fromBytes(byte[] bytes) {
        var buf = Unpooled.wrappedBuffer(bytes);
        try {
            return readValue(buf);
        } finally {
            buf.release();
        }
    }
}