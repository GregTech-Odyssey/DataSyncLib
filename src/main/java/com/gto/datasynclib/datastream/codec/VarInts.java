package com.gto.datasynclib.datastream.codec;

import io.netty.buffer.ByteBuf;
import lombok.experimental.UtilityClass;

/**
 * The VarInt / VarLong encoding on a plain {@link ByteBuf} — the seven-bit-per-byte,
 * high-bit-continues format Minecraft uses for lengths and small numbers.
 *
 * <p>1.20.1 keeps its own implementation as package-private statics on
 * {@link net.minecraft.network.FriendlyByteBuf}, so a codec written against {@link ByteBuf} cannot
 * reach it; 1.21 publishes the same methods as {@code net.minecraft.network.VarInt} and
 * {@code net.minecraft.network.VarLong}. These are that pair, with the identical wire format, and
 * they are what let the numeric codecs take the <strong>minimal</strong> buffer type instead of the
 * richest one — a {@code StreamCodec<ByteBuf, Integer>} works with a {@code FriendlyByteBuf}, a
 * wrapped buffer in a test, or anything else that extends {@link ByteBuf}.</p>
 */
@UtilityClass
public class VarInts {

    /**
     * Writes {@code value} as a VarInt.
     */
    public void write(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    /**
     * Reads a VarInt. A truncated or over-long encoding throws
     * {@link IndexOutOfBoundsException} / {@link IllegalArgumentException} the same way the vanilla
     * reader does, so a malformed payload fails rather than silently decoding to something else.
     */
    public int read(ByteBuf buf) {
        var result = 0;
        var shift = 0;
        while (true) {
            var b = buf.readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift >= 32) throw new IllegalArgumentException("VarInt is too big");
        }
    }

    /**
     * Writes {@code value} as a VarLong — the same seven-bit-per-byte form as {@link #write}, with
     * room for a full {@code long}.
     */
    public void writeVarLong(ByteBuf buf, long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte((int) value);
    }

    /**
     * Reads a VarLong. As on {@link #read}, a truncated encoding throws
     * {@link IndexOutOfBoundsException} and an over-long one {@link IllegalArgumentException}, so a
     * malformed payload fails rather than silently decoding to something else.
     */
    public long readVarLong(ByteBuf buf) {
        var result = 0L;
        var shift = 0;
        while (true) {
            var b = buf.readByte();
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift >= 64) throw new IllegalArgumentException("VarLong is too big");
        }
    }
}
