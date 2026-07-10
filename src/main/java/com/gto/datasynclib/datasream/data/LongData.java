package com.gto.datasynclib.datasream.data;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.HashCommon;

public record LongData(long value) implements NumericData {

    @Deprecated(forRemoval = true)
    public LongData {
    }

    public static LongData valueOf(long i) {
        return i >= Cache.LOW && i <= Cache.HIGH ? Cache.cache[(int) i + 128] : new LongData(i);
    }

    @Override
    public long getLong() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        stream.writeLong(this.value);
    }

    @Override
    public byte getId() {
        return LONG;
    }

    @Override
    public Long box() {
        return this.value;
    }

    @Override
    public long longValue() {
        return this.value;
    }

    @Override
    public int intValue() {
        return (int) this.value;
    }

    @Override
    public short shortValue() {
        return (short) (this.value & 65535L);
    }

    @Override
    public byte byteValue() {
        return (byte) (this.value & 255L);
    }

    @Override
    public double doubleValue() {
        return this.value;
    }

    @Override
    public float floatValue() {
        return (float) this.value;
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this || (obj instanceof LongData(long i) && i == this.value);
    }

    @Override
    public int hashCode() {
        return HashCommon.long2int(this.value);
    }

    @Override
    public String toString() {
        return String.valueOf(this.value);
    }

    private static class Cache {

        private static final int HIGH = 1024;
        private static final int LOW = -128;
        static final LongData[] cache = new LongData[1153];

        static {
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new LongData(-128 + i);
            }
        }
    }
}
