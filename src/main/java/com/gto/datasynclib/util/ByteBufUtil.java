package com.gto.datasynclib.util;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import lombok.experimental.UtilityClass;
import net.minecraft.network.FriendlyByteBuf;
import org.apache.commons.lang3.ArrayUtils;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Buffer helpers shared by the sync / save code paths.
 *
 * <p>There are two recurring needs:</p>
 * <ol>
 *   <li>Crossing a {@code byte[]} boundary (NBT byte arrays, packet payloads) without
 *       building throw-away wrapper objects all over the code base.</li>
 *   <li>Framing nested payloads, i.e. writing a length prefix and later reading exactly
 *       that many bytes as an independent "sub buffer".</li>
 * </ol>
 *
 * <p>Framed payloads are read with {@link ByteBuf#readSlice(int)}, so the sub buffer
 * shares the parent's backing storage — reading a nested payload never copies bytes.</p>
 */
@UtilityClass
public class ByteBufUtil {

    /** Wraps a {@link ByteBuf} into a {@link FriendlyByteBuf}; no copy, just a view. */
    public static final Function<ByteBuf, FriendlyByteBuf> FRIENDLY_BYTE_BUF_WRAPPER = FriendlyByteBuf::new;

    /**
     * Returns {@code buf} itself when it already is a {@link FriendlyByteBuf}, otherwise a view.
     *
     * @param buf the buffer to wrap, must not be {@code null}
     * @return a {@link FriendlyByteBuf} reading from the same storage as {@code buf}
     */
    public FriendlyByteBuf toFriendlyByteBuf(ByteBuf buf) {
        if (buf instanceof FriendlyByteBuf friendlyByteBuf) {
            return friendlyByteBuf;
        }
        return new FriendlyByteBuf(buf);
    }

    /**
     * Consumes all readable bytes of {@code buf} into a new array.
     *
     * @param buf the buffer to drain (its reader index advances past the returned bytes)
     * @return the remaining readable bytes, {@link ArrayUtils#EMPTY_BYTE_ARRAY} when empty
     */
    public byte[] getBytes(final ByteBuf buf) {
        var readableBytes = buf.readableBytes();
        if (readableBytes == 0) return ArrayUtils.EMPTY_BYTE_ARRAY;
        final byte[] bytes = new byte[readableBytes];
        buf.readBytes(bytes);
        return bytes;
    }

    /**
     * Writes into a fresh unpooled buffer and returns the resulting array.
     *
     * <p>Only use this at a real {@code byte[]} boundary; inside buffer-to-buffer code,
     * write into the caller's buffer instead.</p>
     *
     * @param consumer writes the payload into the given buffer
     * @return a new array holding everything the consumer wrote
     */
    public byte[] writeToBytes(Consumer<ByteBuf> consumer) {
        var buf = Unpooled.buffer();
        consumer.accept(buf);
        return getBytes(buf);
    }

    /**
     * Same as {@link #writeToBytes(Consumer)}, but hands a wrapping type (usually
     * {@link FriendlyByteBuf}) to the consumer.
     *
     * @param wrapper wraps the raw buffer for the consumer, e.g. {@link #FRIENDLY_BYTE_BUF_WRAPPER}
     * @param consumer writes the payload into the wrapped buffer
     * @return a new array holding everything the consumer wrote
     */
    public <T extends ByteBuf> byte[] writeToBytes(Function<ByteBuf, T> wrapper, Consumer<T> consumer) {
        var buf = Unpooled.buffer();
        consumer.accept(wrapper.apply(buf));
        return getBytes(buf);
    }

    /**
     * Reads {@code data} through a wrapping type without copying it.
     *
     * <p>An empty array is treated as "nothing was written" and skips the consumer
     * entirely, which matches how a zero-length {@code writeToBytes} result is stored
     * in NBT / packets.</p>
     *
     * @param wrapper wraps the buffer handed to the consumer, e.g. {@link #FRIENDLY_BYTE_BUF_WRAPPER}
     * @param data    bytes previously produced by {@link #writeToBytes}
     * @param consumer reads the payload from the wrapped buffer
     */
    public <T extends ByteBuf> void readFromBytes(Function<ByteBuf, T> wrapper, byte[] data, Consumer<T> consumer) {
        if (data.length == 0) return;
        var buf = Unpooled.wrappedBuffer(data);
        consumer.accept(wrapper.apply(buf));
    }

    /**
     * Reads {@code data} as a plain {@link ByteBuf} without copying it.
     *
     * @param data     bytes previously produced by {@link #writeToBytes}
     * @param consumer reads the payload from the buffer
     * @see #readFromBytes(Function, byte[], Consumer)
     */
    public void readFromBytes(byte[] data, Consumer<ByteBuf> consumer) {
        if (data.length == 0) return;
        var buf = Unpooled.wrappedBuffer(data);
        consumer.accept(buf);
    }

    /**
     * Writes a {@code short} length prefix followed by the payload.
     *
     * @param buf      the buffer receiving the framed payload
     * @param consumer writes the payload; must write at most {@code 65535} bytes
     * @throws IllegalArgumentException when the payload exceeds {@code 65535} bytes
     */
    public <T extends ByteBuf> void writePayload(T buf, Consumer<T> consumer) {
        int lenIndex = buf.writerIndex();
        buf.writeShort(0);
        int start = buf.writerIndex();
        consumer.accept(buf);
        int payloadLen = buf.writerIndex() - start;
        if (payloadLen > 0xFFFF) {
            throw new IllegalArgumentException("Payload too large for short prefix: " + payloadLen + " > 65535");
        }
        buf.setShort(lenIndex, payloadLen);
    }

    /**
     * Reads a payload previously framed by {@link #writePayload(ByteBuf, Consumer)}.
     *
     * <p>The consumer receives a {@link ByteBuf#readSlice(int) slice} of {@code buf},
     * so no bytes are copied and {@code buf} is advanced past exactly {@code payloadLen}
     * bytes when the consumer returns.</p>
     *
     * @param wrapper  wraps the slice for the consumer, e.g. {@link #FRIENDLY_BYTE_BUF_WRAPPER}
     * @param buf      the buffer holding the framed payload
     * @param consumer reads the payload; not called for an empty payload
     * @throws IndexOutOfBoundsException when the stored length exceeds the readable bytes
     */
    public <T extends ByteBuf> void readPayload(Function<ByteBuf, T> wrapper, T buf, Consumer<T> consumer) {
        readPayload0(wrapper, buf, buf.readUnsignedShort(), consumer);
    }

    /**
     * Reads a payload framed by {@link #writePayload(ByteBuf, Consumer)} as a plain {@link ByteBuf}.
     *
     * @param buf      the buffer holding the framed payload
     * @param consumer reads the payload slice; not called for an empty payload
     * @throws IndexOutOfBoundsException when the stored length exceeds the readable bytes
     */
    public <T extends ByteBuf> void readPayload(T buf, Consumer<ByteBuf> consumer) {
        readPayload0(buf, buf.readUnsignedShort(), consumer);
    }

    /**
     * Writes an {@code int} length prefix followed by the payload.
     *
     * <p>Use this instead of {@link #writePayload(ByteBuf, Consumer)} when a single nested
     * payload can legitimately exceed {@code 65535} bytes (e.g. a whole holder's saved data).</p>
     *
     * @param buf      the buffer receiving the framed payload
     * @param consumer writes the payload
     */
    public <T extends ByteBuf> void writeBigPayload(T buf, Consumer<T> consumer) {
        int lenIndex = buf.writerIndex();
        buf.writeInt(0);
        int start = buf.writerIndex();
        consumer.accept(buf);
        buf.setInt(lenIndex, buf.writerIndex() - start);
    }

    /**
     * Reads a payload previously framed by {@link #writeBigPayload(ByteBuf, Consumer)}.
     *
     * @param wrapper  wraps the slice for the consumer, e.g. {@link #FRIENDLY_BYTE_BUF_WRAPPER}
     * @param buf      the buffer holding the framed payload
     * @param consumer reads the payload slice; not called for an empty payload
     * @throws IndexOutOfBoundsException when the stored length exceeds the readable bytes
     */
    public <T extends ByteBuf> void readBigPayload(Function<ByteBuf, T> wrapper, T buf, Consumer<T> consumer) {
        readPayload0(wrapper, buf, buf.readInt(), consumer);
    }

    /**
     * Reads a payload framed by {@link #writeBigPayload(ByteBuf, Consumer)} as a plain {@link ByteBuf}.
     *
     * @param buf      the buffer holding the framed payload
     * @param consumer reads the payload slice; not called for an empty payload
     * @throws IndexOutOfBoundsException when the stored length exceeds the readable bytes
     */
    public <T extends ByteBuf> void readBigPayload(T buf, Consumer<ByteBuf> consumer) {
        readPayload0(buf, buf.readInt(), consumer);
    }

    private <T extends ByteBuf> void readPayload0(Function<ByteBuf, T> wrapper, T buf, int payloadLen, Consumer<T> consumer) {
        if (payloadLen < 0 || payloadLen > buf.readableBytes()) {
            throw new IndexOutOfBoundsException("Invalid payload length " + payloadLen + ", remaining " + buf.readableBytes());
        }
        if (payloadLen == 0) return;
        consumer.accept(wrapper.apply(buf.readSlice(payloadLen)));
    }

    private <T extends ByteBuf> void readPayload0(T buf, int payloadLen, Consumer<ByteBuf> consumer) {
        if (payloadLen < 0 || payloadLen > buf.readableBytes()) {
            throw new IndexOutOfBoundsException("Invalid payload length " + payloadLen + ", remaining " + buf.readableBytes());
        }
        if (payloadLen == 0) return;
        consumer.accept(buf.readSlice(payloadLen));
    }
}
