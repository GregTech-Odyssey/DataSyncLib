package com.gto.datasynclib.datasream.data;

import io.netty.buffer.ByteBuf;

public record IntData(int value) implements NumericData {

    @Deprecated(forRemoval = true)
    public IntData {
    }

    public static IntData valueOf(int i) {
        return i >= Cache.LOW && i <= Cache.HIGH ? Cache.cache[i + 128] : new IntData(i);
    }

    @Override
    public int getInt() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        stream.writeInt(this.value);
    }

    @Override
    public byte getId() {
        return INT;
    }

    @Override
    public Integer box() {
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
        return (short) (this.value & 65535);
    }

    @Override
    public byte byteValue() {
        return (byte) (this.value & 0xFF);
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
        return obj == this || (obj instanceof IntData(int i) && i == this.value);
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
        static final IntData[] cache = new IntData[1153];

        static {
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new IntData(-128 + i);
            }
        }
    }
}
