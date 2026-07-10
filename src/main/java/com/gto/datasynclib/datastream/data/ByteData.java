package com.gto.datasynclib.datastream.data;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a byte (and boolean) value in the Data type system. Wire format ID: 1 (boolean) / 2 (byte).
 * Also serves as the boolean representation via TRUE/FALSE constants.
 */
public record ByteData(byte value) implements NumericData {

    public static final ByteData TRUE = valueOf((byte) 1);
    public static final ByteData FALSE = valueOf((byte) 0);

    @Deprecated(forRemoval = true)
    public ByteData {
    }

    public static ByteData valueOf(boolean data) {
        return data ? TRUE : FALSE;
    }

    public static ByteData valueOf(byte data) {
        return Cache.cache[128 + data];
    }

    @Override
    public boolean getBoolean() {
        return value == 1;
    }

    @Override
    public byte getByte() {
        return value;
    }

    @Override
    public void write(ByteBuf stream) {
        stream.writeByte(value);
    }

    @Override
    public byte getId() {
        return BYTE;
    }

    @Override
    public Byte box() {
        return this.value;
    }

    @Override
    public long longValue() {
        return this.value;
    }

    @Override
    public int intValue() {
        return this.value;
    }

    @Override
    public short shortValue() {
        return this.value;
    }

    @Override
    public byte byteValue() {
        return this.value;
    }

    @Override
    public double doubleValue() {
        return this.value;
    }

    @Override
    public float floatValue() {
        return this.value;
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this || (obj instanceof ByteData(byte i) && i == this.value);
    }

    @Override
    public int hashCode() {
        return value;
    }

    @Override
    public @NotNull String toString() {
        return String.valueOf(this.value);
    }

    private static final class Cache {

        private static final ByteData[] cache = new ByteData[256];

        static {
            for (int i = 0; i < cache.length; i++) {
                var value = (byte) (i - 128);
                cache[i] = new ByteData(value);
            }
        }
    }
}
