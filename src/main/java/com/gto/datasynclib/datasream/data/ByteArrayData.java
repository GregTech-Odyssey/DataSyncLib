package com.gto.datasynclib.datasream.data;

import io.netty.buffer.ByteBuf;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

public final class ByteArrayData implements CollectionData {

    private byte[] value;

    public ByteArrayData(byte... value) {
        this.value = value;
    }

    public ByteArrayData(int initialCapacity) {
        this.value = new byte[initialCapacity];
    }

    public ByteArrayData() {
        this.value = ArrayUtils.EMPTY_BYTE_ARRAY;
    }

    public static ByteArrayData valueOf(byte... value) {
        return new ByteArrayData(value);
    }

    public int size() {
        return this.value.length;
    }

    public boolean isEmpty() {
        return this.value.length == 0;
    }

    public byte get(int index) {
        return this.value[index];
    }

    public void set(int index, byte data) {
        this.value[index] = data;
    }

    public void add(int index, byte data) {
        this.value = ArrayUtils.add(this.value, index, data);
    }

    public void add(byte data) {
        this.value = ArrayUtils.add(this.value, data);
    }

    public byte remove(int index) {
        byte prev = this.value[index];
        this.value = ArrayUtils.remove(this.value, index);
        return prev;
    }

    public void clear() {
        this.value = ArrayUtils.EMPTY_BYTE_ARRAY;
    }

    @Override
    public byte @NotNull [] getByteArray() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeByteArray(stream, value);
    }

    @Override
    public byte getId() {
        return BYTE_ARRAY;
    }

    @Override
    public Data copy() {
        byte[] cp = new byte[this.value.length];
        System.arraycopy(this.value, 0, cp, 0, this.value.length);
        return new ByteArrayData(cp);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof ByteArrayData arrayData && Arrays.equals(this.value, arrayData.value));
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
