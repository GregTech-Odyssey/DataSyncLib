package com.gto.datasynclib.datastream.data;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Represents a String value in the Data type system. Wire format ID: 12.
 */
public record StringData(@NotNull String value) implements ImmutableData {

    public static final StringData EMPTY = new StringData("");

    /**
     * Prefer the {@code valueOf(...)} factory below, which returns cached instances where the range
     * allows it. This constructor is public only because a record's canonical constructor cannot be
     * narrower than the record itself, so {@code new ...} cannot be hidden from callers; it is not
     * deprecated for removal and behaves exactly like the factory — it just allocates.
     */
    @Deprecated
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
