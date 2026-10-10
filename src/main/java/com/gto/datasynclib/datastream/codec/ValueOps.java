package com.gto.datasynclib.datastream.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
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
 *   <li><strong>{@code  getXxx} / {@code  getXxx} / {@code  getXxx} / {@code  getXxx}
 *       and the overloaded {@code addXxx} / {@code putXxx} — the element shortcuts.</strong> One
 *       conversion for a list element or a map entry, so a codec walking a stored list does not repeat
 *       {@code getInt(list.get(i))} at every element. The reads take the container the caller already
 *       holds and name it, because the key type alone cannot say which container it is — an int is a list
 *       index in one call and an int-map key in another. The writes are one name per value type, overloaded
 *       by the container, and build through the creators, which keeps a carrier that shapes its values
 *       shaping them.</li>
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
 * <p>{@link #getTypeId(Object)} reports the id, {@link #writeValue(Object, ByteBuf)} writes the
 * <em>payload</em> and {@link #readValue(ByteBuf)} reads one back for a given id — the id byte
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
        /**
         * A payload format the library registered with {@link CustomTypes} — NBT or JSON today. The registry
         * is the library's own rather than a mod's: a mod's value belongs in a
         * {@link ValueConverters.Converter}, which needs no id. See {@link CustomTypes}.
         */
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
     * @apiNote For the payloads the library registers — NBT, JSON. A mod's own object is stored as a
     * {@link ValueConverters.Converter} value rather than as a payload format of its own; see
     * {@link CustomTypes}.
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
     * @return the payload of a custom value, as it was handed to {@link #createCustom(CustomTypes.Type, Object)}
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

    default Object createList(Object... value) {
        return createList(Arrays.asList(value));
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
    // ===== Element access: the list and map shortcuts =====
    // A codec walking a stored list or map otherwise repeats the same conversion at every element —
    // {@code getInt(list.get(i))}, {@code map.put(key, createString(value))} — so the carrier offers the pair.
    // Both halves are one name per value type, overloaded by the container: a read takes the container the
    // caller already holds plus the index or key into it, a write takes the container plus the value. The
    // container parameter is what tells the overloads apart, because the key alone cannot say which one it
    // is: {@code 5} is a list index in one call and an int-map key in another. The writers build through the
    // creators, so a carrier that shapes its values keeps shaping them. A read of a value that is not that
    // type reports through the element getter, as it would there.

    /**
     * @return the boolean at {@code index} of the list — {@code getBoolean} of it
     */
    default boolean getBoolean(List<Object> list, int index) {
        return getBoolean(list.get(index));
    }

    /**
     * @return the byte at {@code index} of the list — {@code getByte} of it
     */
    default byte getByte(List<Object> list, int index) {
        return getByte(list.get(index));
    }

    /**
     * @return the short at {@code index} of the list — {@code getShort} of it
     */
    default short getShort(List<Object> list, int index) {
        return getShort(list.get(index));
    }

    /**
     * @return the char at {@code index} of the list — {@code getChar} of it
     */
    default char getChar(List<Object> list, int index) {
        return getChar(list.get(index));
    }

    /**
     * @return the int at {@code index} of the list — {@code getInt} of it
     */
    default int getInt(List<Object> list, int index) {
        return getInt(list.get(index));
    }

    /**
     * @return the long at {@code index} of the list — {@code getLong} of it
     */
    default long getLong(List<Object> list, int index) {
        return getLong(list.get(index));
    }

    /**
     * @return the float at {@code index} of the list — {@code getFloat} of it
     */
    default float getFloat(List<Object> list, int index) {
        return getFloat(list.get(index));
    }

    /**
     * @return the double at {@code index} of the list — {@code getDouble} of it
     */
    default double getDouble(List<Object> list, int index) {
        return getDouble(list.get(index));
    }

    /**
     * @return the String at {@code index} of the list — {@code getString} of it
     */
    default String getString(List<Object> list, int index) {
        return getString(list.get(index));
    }

    /**
     * @return the byte array at {@code index} of the list — {@code getByteArray} of it
     */
    default byte[] getByteArray(List<Object> list, int index) {
        return getByteArray(list.get(index));
    }

    /**
     * @return the int array at {@code index} of the list — {@code getIntArray} of it
     */
    default int[] getIntArray(List<Object> list, int index) {
        return getIntArray(list.get(index));
    }

    /**
     * @return the long array at {@code index} of the list — {@code getLongArray} of it
     */
    default long[] getLongArray(List<Object> list, int index) {
        return getLongArray(list.get(index));
    }

    /**
     * @return the list at {@code index} of the list — {@code  get} of it
     */
    default List<Object> getList(List<Object> list, int index) {
        return getList(list.get(index));
    }

    /**
     * @return the string map at {@code index} of the list — {@code getStringMap} of it
     */
    default Map<String, Object> getStringMap(List<Object> list, int index) {
        return getStringMap(list.get(index));
    }

    /**
     * @return the boolean at {@code key} of the string map — {@code getBoolean} of it
     */
    default boolean getBoolean(Map<String, Object> map, String key) {
        return getBoolean(map.get(key));
    }

    /**
     * @return the byte at {@code key} of the string map — {@code getByte} of it
     */
    default byte getByte(Map<String, Object> map, String key) {
        return getByte(map.get(key));
    }

    /**
     * @return the short at {@code key} of the string map — {@code getShort} of it
     */
    default short getShort(Map<String, Object> map, String key) {
        return getShort(map.get(key));
    }

    /**
     * @return the char at {@code key} of the string map — {@code getChar} of it
     */
    default char getChar(Map<String, Object> map, String key) {
        return getChar(map.get(key));
    }

    /**
     * @return the int at {@code key} of the string map — {@code getInt} of it
     */
    default int getInt(Map<String, Object> map, String key) {
        return getInt(map.get(key));
    }

    /**
     * @return the long at {@code key} of the string map — {@code getLong} of it
     */
    default long getLong(Map<String, Object> map, String key) {
        return getLong(map.get(key));
    }

    /**
     * @return the float at {@code key} of the string map — {@code getFloat} of it
     */
    default float getFloat(Map<String, Object> map, String key) {
        return getFloat(map.get(key));
    }

    /**
     * @return the double at {@code key} of the string map — {@code getDouble} of it
     */
    default double getDouble(Map<String, Object> map, String key) {
        return getDouble(map.get(key));
    }

    /**
     * @return the String at {@code key} of the string map — {@code getString} of it
     */
    default String getString(Map<String, Object> map, String key) {
        return getString(map.get(key));
    }

    /**
     * @return the byte array at {@code key} of the string map — {@code getByteArray} of it
     */
    default byte[] getByteArray(Map<String, Object> map, String key) {
        return getByteArray(map.get(key));
    }

    /**
     * @return the int array at {@code key} of the string map — {@code getIntArray} of it
     */
    default int[] getIntArray(Map<String, Object> map, String key) {
        return getIntArray(map.get(key));
    }

    /**
     * @return the long array at {@code key} of the string map — {@code getLongArray} of it
     */
    default long[] getLongArray(Map<String, Object> map, String key) {
        return getLongArray(map.get(key));
    }

    /**
     * @return the list at {@code key} of the string map — {@code  get} of it
     */
    default List<Object> getList(Map<String, Object> map, String key) {
        return getList(map.get(key));
    }

    /**
     * @return the string map at {@code key} of the string map — {@code getStringMap} of it
     */
    default Map<String, Object> getStringMap(Map<String, Object> map, String key) {
        return getStringMap(map.get(key));
    }

    /**
     * @return the boolean at {@code key} of the int map — {@code getBoolean} of it
     */
    default boolean getBoolean(Int2ObjectMap<Object> map, int key) {
        return getBoolean(map.get(key));
    }

    /**
     * @return the byte at {@code key} of the int map — {@code getByte} of it
     */
    default byte getByte(Int2ObjectMap<Object> map, int key) {
        return getByte(map.get(key));
    }

    /**
     * @return the short at {@code key} of the int map — {@code getShort} of it
     */
    default short getShort(Int2ObjectMap<Object> map, int key) {
        return getShort(map.get(key));
    }

    /**
     * @return the char at {@code key} of the int map — {@code getChar} of it
     */
    default char getChar(Int2ObjectMap<Object> map, int key) {
        return getChar(map.get(key));
    }

    /**
     * @return the int at {@code key} of the int map — {@code getInt} of it
     */
    default int getInt(Int2ObjectMap<Object> map, int key) {
        return getInt(map.get(key));
    }

    /**
     * @return the long at {@code key} of the int map — {@code getLong} of it
     */
    default long getLong(Int2ObjectMap<Object> map, int key) {
        return getLong(map.get(key));
    }

    /**
     * @return the float at {@code key} of the int map — {@code getFloat} of it
     */
    default float getFloat(Int2ObjectMap<Object> map, int key) {
        return getFloat(map.get(key));
    }

    /**
     * @return the double at {@code key} of the int map — {@code getDouble} of it
     */
    default double getDouble(Int2ObjectMap<Object> map, int key) {
        return getDouble(map.get(key));
    }

    /**
     * @return the String at {@code key} of the int map — {@code getString} of it
     */
    default String getString(Int2ObjectMap<Object> map, int key) {
        return getString(map.get(key));
    }

    /**
     * @return the byte array at {@code key} of the int map — {@code getByteArray} of it
     */
    default byte[] getByteArray(Int2ObjectMap<Object> map, int key) {
        return getByteArray(map.get(key));
    }

    /**
     * @return the int array at {@code key} of the int map — {@code getIntArray} of it
     */
    default int[] getIntArray(Int2ObjectMap<Object> map, int key) {
        return getIntArray(map.get(key));
    }

    /**
     * @return the long array at {@code key} of the int map — {@code getLongArray} of it
     */
    default long[] getLongArray(Int2ObjectMap<Object> map, int key) {
        return getLongArray(map.get(key));
    }

    /**
     * @return the list at {@code key} of the int map — {@code  get} of it
     */
    default List<Object> getList(Int2ObjectMap<Object> map, int key) {
        return getList(map.get(key));
    }

    /**
     * @return the string map at {@code key} of the int map — {@code getStringMap} of it
     */
    default Map<String, Object> getStringMap(Int2ObjectMap<Object> map, int key) {
        return getStringMap(map.get(key));
    }

    /**
     * @return the boolean at {@code key} of the long map — {@code getBoolean} of it
     */
    default boolean getBoolean(Long2ObjectMap<Object> map, long key) {
        return getBoolean(map.get(key));
    }

    /**
     * @return the byte at {@code key} of the long map — {@code getByte} of it
     */
    default byte getByte(Long2ObjectMap<Object> map, long key) {
        return getByte(map.get(key));
    }

    /**
     * @return the short at {@code key} of the long map — {@code getShort} of it
     */
    default short getShort(Long2ObjectMap<Object> map, long key) {
        return getShort(map.get(key));
    }

    /**
     * @return the char at {@code key} of the long map — {@code getChar} of it
     */
    default char getChar(Long2ObjectMap<Object> map, long key) {
        return getChar(map.get(key));
    }

    /**
     * @return the int at {@code key} of the long map — {@code getInt} of it
     */
    default int getInt(Long2ObjectMap<Object> map, long key) {
        return getInt(map.get(key));
    }

    /**
     * @return the long at {@code key} of the long map — {@code getLong} of it
     */
    default long getLong(Long2ObjectMap<Object> map, long key) {
        return getLong(map.get(key));
    }

    /**
     * @return the float at {@code key} of the long map — {@code getFloat} of it
     */
    default float getFloat(Long2ObjectMap<Object> map, long key) {
        return getFloat(map.get(key));
    }

    /**
     * @return the double at {@code key} of the long map — {@code getDouble} of it
     */
    default double getDouble(Long2ObjectMap<Object> map, long key) {
        return getDouble(map.get(key));
    }

    /**
     * @return the String at {@code key} of the long map — {@code getString} of it
     */
    default String getString(Long2ObjectMap<Object> map, long key) {
        return getString(map.get(key));
    }

    /**
     * @return the byte array at {@code key} of the long map — {@code getByteArray} of it
     */
    default byte[] getByteArray(Long2ObjectMap<Object> map, long key) {
        return getByteArray(map.get(key));
    }

    /**
     * @return the int array at {@code key} of the long map — {@code getIntArray} of it
     */
    default int[] getIntArray(Long2ObjectMap<Object> map, long key) {
        return getIntArray(map.get(key));
    }

    /**
     * @return the long array at {@code key} of the long map — {@code getLongArray} of it
     */
    default long[] getLongArray(Long2ObjectMap<Object> map, long key) {
        return getLongArray(map.get(key));
    }

    /**
     * @return the list at {@code key} of the long map — {@code  get} of it
     */
    default List<Object> getList(Long2ObjectMap<Object> map, long key) {
        return getList(map.get(key));
    }

    /**
     * @return the string map at {@code key} of the long map — {@code getStringMap} of it
     */
    default Map<String, Object> getStringMap(Long2ObjectMap<Object> map, long key) {
        return getStringMap(map.get(key));
    }

    /**
     * Appends a boolean built with {@code createBoolean} to the list.
     */
    default void addBoolean(List<Object> list, boolean value) {
        list.add(createBoolean(value));
    }

    /**
     * Appends a byte built with {@code createByte} to the list.
     */
    default void addByte(List<Object> list, byte value) {
        list.add(createByte(value));
    }

    /**
     * Appends a short built with {@code createShort} to the list.
     */
    default void addShort(List<Object> list, short value) {
        list.add(createShort(value));
    }

    /**
     * Appends a char built with {@code createChar} to the list.
     */
    default void addChar(List<Object> list, char value) {
        list.add(createChar(value));
    }

    /**
     * Appends a int built with {@code createInt} to the list.
     */
    default void addInt(List<Object> list, int value) {
        list.add(createInt(value));
    }

    /**
     * Appends a long built with {@code createLong} to the list.
     */
    default void addLong(List<Object> list, long value) {
        list.add(createLong(value));
    }

    /**
     * Appends a float built with {@code createFloat} to the list.
     */
    default void addFloat(List<Object> list, float value) {
        list.add(createFloat(value));
    }

    /**
     * Appends a double built with {@code createDouble} to the list.
     */
    default void addDouble(List<Object> list, double value) {
        list.add(createDouble(value));
    }

    /**
     * Appends a String built with {@code createString} to the list.
     */
    default void addString(List<Object> list, String value) {
        list.add(createString(value));
    }

    /**
     * Appends a byte array built with {@code createByteArray} to the list.
     */
    default void addByteArray(List<Object> list, byte[] value) {
        list.add(createByteArray(value));
    }

    /**
     * Appends a int array built with {@code createIntArray} to the list.
     */
    default void addIntArray(List<Object> list, int[] value) {
        list.add(createIntArray(value));
    }

    /**
     * Appends a long array built with {@code createLongArray} to the list.
     */
    default void addLongArray(List<Object> list, long[] value) {
        list.add(createLongArray(value));
    }

    /**
     * Appends a list built with {@code createList} to the list.
     */
    default void addList(List<Object> list, List<Object> value) {
        list.add(createList(value));
    }

    /**
     * Appends a string map built with {@code createStringMap} to the list.
     */
    default void addStringMap(List<Object> list, Map<String, Object> value) {
        list.add(createStringMap(value));
    }

    /**
     * Stores a boolean built with {@code createBoolean} under {@code key} of the string map.
     */
    default void putBoolean(Map<String, Object> map, String key, boolean value) {
        map.put(key, createBoolean(value));
    }

    /**
     * Stores a byte built with {@code createByte} under {@code key} of the string map.
     */
    default void putByte(Map<String, Object> map, String key, byte value) {
        map.put(key, createByte(value));
    }

    /**
     * Stores a short built with {@code createShort} under {@code key} of the string map.
     */
    default void putShort(Map<String, Object> map, String key, short value) {
        map.put(key, createShort(value));
    }

    /**
     * Stores a char built with {@code createChar} under {@code key} of the string map.
     */
    default void putChar(Map<String, Object> map, String key, char value) {
        map.put(key, createChar(value));
    }

    /**
     * Stores a int built with {@code createInt} under {@code key} of the string map.
     */
    default void putInt(Map<String, Object> map, String key, int value) {
        map.put(key, createInt(value));
    }

    /**
     * Stores a long built with {@code createLong} under {@code key} of the string map.
     */
    default void putLong(Map<String, Object> map, String key, long value) {
        map.put(key, createLong(value));
    }

    /**
     * Stores a float built with {@code createFloat} under {@code key} of the string map.
     */
    default void putFloat(Map<String, Object> map, String key, float value) {
        map.put(key, createFloat(value));
    }

    /**
     * Stores a double built with {@code createDouble} under {@code key} of the string map.
     */
    default void putDouble(Map<String, Object> map, String key, double value) {
        map.put(key, createDouble(value));
    }

    /**
     * Stores a String built with {@code createString} under {@code key} of the string map.
     */
    default void putString(Map<String, Object> map, String key, String value) {
        map.put(key, createString(value));
    }

    /**
     * Stores a byte array built with {@code createByteArray} under {@code key} of the string map.
     */
    default void putByteArray(Map<String, Object> map, String key, byte[] value) {
        map.put(key, createByteArray(value));
    }

    /**
     * Stores a int array built with {@code createIntArray} under {@code key} of the string map.
     */
    default void putIntArray(Map<String, Object> map, String key, int[] value) {
        map.put(key, createIntArray(value));
    }

    /**
     * Stores a long array built with {@code createLongArray} under {@code key} of the string map.
     */
    default void putLongArray(Map<String, Object> map, String key, long[] value) {
        map.put(key, createLongArray(value));
    }

    /**
     * Stores a list built with {@code createList} under {@code key} of the string map.
     */
    default void putList(Map<String, Object> map, String key, List<Object> value) {
        map.put(key, createList(value));
    }

    /**
     * Stores a string map built with {@code createStringMap} under {@code key} of the string map.
     */
    default void putStringMap(Map<String, Object> map, String key, Map<String, Object> value) {
        map.put(key, createStringMap(value));
    }

    /**
     * Stores a boolean built with {@code createBoolean} under {@code key} of the int map.
     */
    default void putBoolean(Int2ObjectMap<Object> map, int key, boolean value) {
        map.put(key, createBoolean(value));
    }

    /**
     * Stores a byte built with {@code createByte} under {@code key} of the int map.
     */
    default void putByte(Int2ObjectMap<Object> map, int key, byte value) {
        map.put(key, createByte(value));
    }

    /**
     * Stores a short built with {@code createShort} under {@code key} of the int map.
     */
    default void putShort(Int2ObjectMap<Object> map, int key, short value) {
        map.put(key, createShort(value));
    }

    /**
     * Stores a char built with {@code createChar} under {@code key} of the int map.
     */
    default void putChar(Int2ObjectMap<Object> map, int key, char value) {
        map.put(key, createChar(value));
    }

    /**
     * Stores a int built with {@code createInt} under {@code key} of the int map.
     */
    default void putInt(Int2ObjectMap<Object> map, int key, int value) {
        map.put(key, createInt(value));
    }

    /**
     * Stores a long built with {@code createLong} under {@code key} of the int map.
     */
    default void putLong(Int2ObjectMap<Object> map, int key, long value) {
        map.put(key, createLong(value));
    }

    /**
     * Stores a float built with {@code createFloat} under {@code key} of the int map.
     */
    default void putFloat(Int2ObjectMap<Object> map, int key, float value) {
        map.put(key, createFloat(value));
    }

    /**
     * Stores a double built with {@code createDouble} under {@code key} of the int map.
     */
    default void putDouble(Int2ObjectMap<Object> map, int key, double value) {
        map.put(key, createDouble(value));
    }

    /**
     * Stores a String built with {@code createString} under {@code key} of the int map.
     */
    default void putString(Int2ObjectMap<Object> map, int key, String value) {
        map.put(key, createString(value));
    }

    /**
     * Stores a byte array built with {@code createByteArray} under {@code key} of the int map.
     */
    default void putByteArray(Int2ObjectMap<Object> map, int key, byte[] value) {
        map.put(key, createByteArray(value));
    }

    /**
     * Stores a int array built with {@code createIntArray} under {@code key} of the int map.
     */
    default void putIntArray(Int2ObjectMap<Object> map, int key, int[] value) {
        map.put(key, createIntArray(value));
    }

    /**
     * Stores a long array built with {@code createLongArray} under {@code key} of the int map.
     */
    default void putLongArray(Int2ObjectMap<Object> map, int key, long[] value) {
        map.put(key, createLongArray(value));
    }

    /**
     * Stores a list built with {@code createList} under {@code key} of the int map.
     */
    default void putList(Int2ObjectMap<Object> map, int key, List<Object> value) {
        map.put(key, createList(value));
    }

    /**
     * Stores a string map built with {@code createStringMap} under {@code key} of the int map.
     */
    default void putStringMap(Int2ObjectMap<Object> map, int key, Map<String, Object> value) {
        map.put(key, createStringMap(value));
    }

    /**
     * Stores a boolean built with {@code createBoolean} under {@code key} of the long map.
     */
    default void putBoolean(Long2ObjectMap<Object> map, long key, boolean value) {
        map.put(key, createBoolean(value));
    }

    /**
     * Stores a byte built with {@code createByte} under {@code key} of the long map.
     */
    default void putByte(Long2ObjectMap<Object> map, long key, byte value) {
        map.put(key, createByte(value));
    }

    /**
     * Stores a short built with {@code createShort} under {@code key} of the long map.
     */
    default void putShort(Long2ObjectMap<Object> map, long key, short value) {
        map.put(key, createShort(value));
    }

    /**
     * Stores a char built with {@code createChar} under {@code key} of the long map.
     */
    default void putChar(Long2ObjectMap<Object> map, long key, char value) {
        map.put(key, createChar(value));
    }

    /**
     * Stores a int built with {@code createInt} under {@code key} of the long map.
     */
    default void putInt(Long2ObjectMap<Object> map, long key, int value) {
        map.put(key, createInt(value));
    }

    /**
     * Stores a long built with {@code createLong} under {@code key} of the long map.
     */
    default void putLong(Long2ObjectMap<Object> map, long key, long value) {
        map.put(key, createLong(value));
    }

    /**
     * Stores a float built with {@code createFloat} under {@code key} of the long map.
     */
    default void putFloat(Long2ObjectMap<Object> map, long key, float value) {
        map.put(key, createFloat(value));
    }

    /**
     * Stores a double built with {@code createDouble} under {@code key} of the long map.
     */
    default void putDouble(Long2ObjectMap<Object> map, long key, double value) {
        map.put(key, createDouble(value));
    }

    /**
     * Stores a String built with {@code createString} under {@code key} of the long map.
     */
    default void putString(Long2ObjectMap<Object> map, long key, String value) {
        map.put(key, createString(value));
    }

    /**
     * Stores a byte array built with {@code createByteArray} under {@code key} of the long map.
     */
    default void putByteArray(Long2ObjectMap<Object> map, long key, byte[] value) {
        map.put(key, createByteArray(value));
    }

    /**
     * Stores a int array built with {@code createIntArray} under {@code key} of the long map.
     */
    default void putIntArray(Long2ObjectMap<Object> map, long key, int[] value) {
        map.put(key, createIntArray(value));
    }

    /**
     * Stores a long array built with {@code createLongArray} under {@code key} of the long map.
     */
    default void putLongArray(Long2ObjectMap<Object> map, long key, long[] value) {
        map.put(key, createLongArray(value));
    }

    /**
     * Stores a list built with {@code createList} under {@code key} of the long map.
     */
    default void putList(Long2ObjectMap<Object> map, long key, List<Object> value) {
        map.put(key, createList(value));
    }

    /**
     * Stores a string map built with {@code createStringMap} under {@code key} of the long map.
     */
    default void putStringMap(Long2ObjectMap<Object> map, long key, Map<String, Object> value) {
        map.put(key, createStringMap(value));
    }


    // ===== Binary form =====

    /**
     * The id of the type {@code data} holds — one of {@link Type}.
     */
    byte getTypeId(Object data);

    /**
     * Writes a complete nested value: the id byte, then that type's payload.
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
     * {@link #getTypeId(Object)} and {@link #writeValue(Object, ByteBuf)} produce, and the layout the Data
     * type system stored a value in. This is what a field is persisted as, and because the ids are
     * Data's, a byte array written by one carrier reads in the other.
     */
    default byte[] toBytes(Object value) {
        var buf = ByteBufAllocator.DEFAULT.buffer();
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