package com.gto.datasynclib.datasream.data;

import io.netty.buffer.ByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public final class LongArrayData implements CollectionData {

    private long[] value;

    public LongArrayData(long... value) {
        this.value = value;
    }

    public LongArrayData(int initialCapacity) {
        this.value = new long[initialCapacity];
    }

    public LongArrayData() {
        this.value = ArrayUtils.EMPTY_LONG_ARRAY;
    }

    public static LongArrayData valueOf(long... value) {
        return new LongArrayData(value);
    }

    public int size() {
        return this.value.length;
    }

    public boolean isEmpty() {
        return this.value.length == 0;
    }

    public long get(int index) {
        return this.value[index];
    }

    public void set(int index, long data) {
        this.value[index] = data;
    }

    public void add(int index, long data) {
        this.value = ArrayUtils.add(this.value, index, data);
    }

    public void add(long data) {
        this.value = ArrayUtils.add(this.value, data);
    }

    public long remove(int index) {
        var prev = this.value[index];
        this.value = ArrayUtils.remove(this.value, index);
        return prev;
    }

    public void clear() {
        this.value = ArrayUtils.EMPTY_LONG_ARRAY;
    }

    @Override
    public long @NotNull [] getLongArray() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeLongArray(stream, value);
    }

    @Override
    public byte getId() {
        return LONG_ARRAY;
    }

    @Override
    public Data copy() {
        long[] cp = new long[this.value.length];
        System.arraycopy(this.value, 0, cp, 0, this.value.length);
        return new LongArrayData(cp);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof LongArrayData arrayData && Arrays.equals(this.value, arrayData.value));
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(this.value);
    }

    @Override
    public String toString() {
        return Arrays.toString(value);
    }
}
