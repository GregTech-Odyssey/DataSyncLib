package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies custom serialization for this field, overriding the default codec lookup.
 *
 * <p>Supports two modes of operation:</p>
 *
 * <h3>Mode 1: Static Codec Fields (recommended)</h3>
 * <p>Reference static fields of type {@link com.gto.datasynclib.datastream.codec.DataCodec}
 * and {@link com.gto.datasynclib.datastream.codec.ByteStreamCodec} declared in the same
 * class (or accessible from it). Use {@link #saveCodec()} for the persistence codec and
 * {@link #syncCodec()} for the network codec. If only {@code saveCodec} is specified,
 * the sync codec is automatically derived from it.</p>
 *
 * <h3>Mode 2: Instance Methods</h3>
 * <p>When {@code saveCodec} is empty, specify instance methods on the declaring class
 * for manual serialization:</p>
 * <ul>
 *   <li>{@link #writeToData()} / {@link #readFromData()} — for persistence (T → Data / Data → T)</li>
 *   <li>{@link #writeToBuffer()} / {@link #readFromBuffer()} — for network sync
 *       ((FriendlyByteBuf, T) → void / FriendlyByteBuf → T)</li>
 * </ul>
 *
 * <h3>Example:</h3>
 * <pre>{@code
 * // Using FieldDataCodec (a composite codec):
 * private static final FieldDataCodec<MyObj> MY_CODEC =
 *     FieldDataManager.createCodec(MyObj.class, MyObj::new);
 *
 * @Codec(saveCodec = "MY_CODEC", syncCodec = "MY_CODEC")
 * private MyObj data;
 *
 * // Using instance methods:
 * @Codec(writeToData = "myWriteToData", readFromData = "myReadFromData")
 * private MyObj data;
 * }</pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Codec {

    /**
     * Static field name from DataCodec class used for disk storage.
     * Specifies the codec for saving to and loading from disk.
     *
     * @return the DataCodec static field name
     */
    String saveCodec() default "";

    /**
     * Static field name from ByteStreamCodec class used for network synchronization.
     * Specifies the codec for syncing data over the network.
     *
     * @return the ByteStreamCodec static field name
     */
    String syncCodec() default "";

    /**
     * Instance method name for converting the field value to a Data object.
     * Method signature: T -> Data, where T is the field type.
     *
     * @return the method name for writing to Data
     */
    String writeToData() default "";

    /**
     * Instance method name for restoring the field value from a Data object.
     * Method signature: Data -> T, where T is the field type.
     *
     * @return the method name for reading from Data
     */
    String readFromData() default "";

    /**
     * Instance method name for writing the field value to a FriendlyByteBuf.
     * Method signature: (FriendlyByteBuf, T) -> void
     *
     * @return the method name for writing to buffer
     */
    String writeToBuffer() default "";

    /**
     * Instance method name for reading the field value from a FriendlyByteBuf.
     * Method signature: FriendlyByteBuf -> T
     *
     * @return the method name for reading from buffer
     */
    String readFromBuffer() default "";
}
