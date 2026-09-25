package com.gto.datasynclib.datastream.data;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.util.Mth;

/**
 * Represents a float value in the Data type system. Wire format ID: 7.
 */
public record FloatData(float value) implements NumericData {

    public static final FloatData ZERO = new FloatData(0.0F);

    /**
     * Prefer the {@code valueOf(...)} factory below, which returns cached instances where the range
     * allows it. This constructor is public only because a record's canonical constructor cannot be
     * narrower than the record itself, so {@code new ...} cannot be hidden from callers; it is not
     * deprecated for removal and behaves exactly like the factory — it just allocates.
     */
    @Deprecated
    public FloatData {
    }

    public static FloatData valueOf(float data) {
        return data == 0.0F ? ZERO : new FloatData(data);
    }

    @Override
    public float getFloat() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        stream.writeFloat(this.value);
    }

    @Override
    public byte getId() {
        return FLOAT;
    }

    @Override
    public Float box() {
        return this.value;
    }

    @Override
    public long longValue() {
        return (long) this.value;
    }

    @Override
    public int intValue() {
        return Mth.floor(this.value);
    }

    @Override
    public short shortValue() {
        return (short) (Mth.floor(this.value) & 65535);
    }

    @Override
    public byte byteValue() {
        return (byte) (Mth.floor(this.value) & 0xFF);
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
        return obj == this || (obj instanceof FloatData(float i) && i == this.value);
    }

    @Override
    public int hashCode() {
        return HashCommon.float2int(this.value);
    }

    @Override
    public String toString() {
        return String.valueOf(this.value);
    }
}
