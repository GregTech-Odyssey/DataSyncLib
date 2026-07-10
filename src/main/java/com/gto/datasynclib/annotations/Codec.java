package com.gto.datasynclib.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies the codec used for serializing and deserializing this field.
 * Allows customization of how the field value is encoded for disk storage,
 * network synchronization, and buffer operations.
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
