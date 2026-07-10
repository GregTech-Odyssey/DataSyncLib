package com.gto.datasynclib.datasream.data;

import io.netty.buffer.ByteBuf;

public record CharData(char value) implements ImmutableData {

    @Deprecated(forRemoval = true)
    public CharData {
    }

    public static CharData valueOf(char i) {
        return i <= Cache.HIGH ? Cache.cache[i] : new CharData(i);
    }

    @Override
    public char getChar() {
        return this.value;
    }

    @Override
    public void write(ByteBuf stream) {
        stream.writeChar(this.value);
    }

    @Override
    public byte getId() {
        return CHAR;
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this || (obj instanceof CharData(char i) && i == this.value);
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

        private static final int HIGH = 127;
        static final CharData[] cache = new CharData[128];

        static {
            for (int i = 0; i < cache.length; i++) {
                cache[i] = new CharData((char) i);
            }
        }
    }
}
