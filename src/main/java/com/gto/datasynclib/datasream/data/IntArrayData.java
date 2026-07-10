package com.gto.datasynclib.datasream.data;

import io.netty.buffer.ByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public final class IntArrayData implements CollectionData {

    private int[] value;

    public IntArrayData(int... value) {
        this.value = value;
    }

    public IntArrayData(int initialCapacity) {
        this.value = new int[initialCapacity];
    }

    public IntArrayData() {
        this.value = ArrayUtils.EMPTY_INT_ARRAY;
    }

    public static IntArrayData valueOf(int... value) {
        return new IntArrayData(value);
    }

    public int size() {
        return this.value.length;
    }

    public boolean isEmpty() {
        return this.value.length == 0;
    }

    public int get(int index) {
        return this.value[index];
    }

    public void set(int index, int data) {
        this.value[index] = data;
    }

    public void add(int index, int data) {
        this.value = ArrayUtils.add(this.value, index, data);
    }

    public void add(int data) {
        this.value = ArrayUtils.add(this.value, data);
    }

    public int remove(int index) {
        var prev = this.value[index];
        this.value = ArrayUtils.remove(this.value, index);
        return prev;
    }

    public void clear() {
        this.value = ArrayUtils.EMPTY_INT_ARRAY;
    }

    @Override
    public int @NotNull [] getIntArray() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeIntArray(stream, value);
    }

    @Override
    public byte getId() {
        return INT_ARRAY;
    }

    @Override
    public Data copy() {
        int[] cp = new int[this.value.length];
        System.arraycopy(this.value, 0, cp, 0, this.value.length);
        return new IntArrayData(cp);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof IntArrayData arrayData && Arrays.equals(this.value, arrayData.value));
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
