package com.gto.datasynclib.datasream.data;

import io.netty.buffer.ByteBuf;

public record ShortData(short value) implements NumericData {

    @Deprecated(forRemoval = true)
    public ShortData(short value) {
        this.value = value;
    }

    public static ShortData valueOf(short i) {
        return i >= Cache.LOW && i <= Cache.HIGH ? Cache.cache[i + 128] : new ShortData(i);
    }

    @Override
    public short getShort() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        stream.writeShort(this.value);
    }

    @Override
    public byte getId() {
        return SHORT;
    }

    @Override
    public Short box() {
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
        return (byte) (this.value & 255);
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
        return obj == this || (obj instanceof ShortData(short i) && i == this.value);
    }

    @Override
    public int hashCode() {
        return this.value;
    }

    @Override
    public String toString() {
        return String.valueOf(this.value);
    }

    private static class Cache {

        private static final int HIGH = 1024;
        private static final int LOW = -128;
        static final ShortData[] cache = new ShortData[1153];

        static {
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new ShortData((short) (-128 + i));
            }
        }
    }
}
