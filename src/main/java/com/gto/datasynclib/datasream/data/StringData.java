package com.gto.datasynclib.datasream.data;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

public record StringData(@NotNull String value) implements ImmutableData {

    public static final StringData EMPTY = new StringData("");

    @Deprecated(forRemoval = true)
    public StringData(String value) {
        this.value = value;
    }

    public static StringData valueOf(@NotNull String data) {
        return data.isEmpty() ? EMPTY : new StringData(data);
    }

    @NotNull
    @Override
    public String getString() {
        return value;
    }

    @Override
    public void write(ByteBuf stream) {
        Data.writeString(stream, value);
    }

    @Override
    public byte getId() {
        return STRING;
    }

    @Override
    public boolean equals(Object obj) {
        return obj == this || obj instanceof StringData(String i) && i.equals(this.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
