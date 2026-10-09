package com.gto.datasynclib.datastream.codec;

import io.netty.buffer.ByteBuf;

/**
 * Encodes a value through a <strong>member method of the value itself</strong> — the shape of 1.21's
 * {@code net.minecraft.network.codec.StreamMemberEncoder}. It is the value-first mirror of
 * {@link StreamEncoder}: {@code encode(T value, O buf)} where an encoder normally takes the buffer
 * first, and that argument order is the whole point.
 *
 * <p>Keeping the value first is what lets an unbound reference to one of the value's own methods
 * stand in for the write half — {@code Point::write} unbound is
 * {@code (Point receiver, FriendlyByteBuf arg)}, exactly this interface's parameter order:</p>
 *
 * <pre>{@code
 * record Point(int x, int y) {
 *     void write(FriendlyByteBuf buf) { buf.writeVarInt(x); buf.writeVarInt(y); }
 *     static Point read(FriendlyByteBuf buf) { return new Point(buf.readVarInt(), buf.readVarInt()); }
 * }
 *
 * StreamCodec<FriendlyByteBuf, Point> CODEC = StreamCodec.ofMember(Point::write, Point::read);
 * }</pre>
 *
 * <p>{@link StreamCodec#ofMember} is the builder that takes one. There is no decoder counterpart,
 * because {@link StreamDecoder#decode} already receives the buffer as its only argument, so a method
 * reference like {@code Point::read} binds to it directly.</p>
 *
 * @param <O> the buffer this encoder writes to — named {@code O} for "output" to keep 1.21's
 *            parameter order ({@code <O, T>}), and in practice a {@link ByteBuf}
 * @param <T> the type of objects to encode — the receiver of its own member method
 */
@FunctionalInterface
public interface StreamMemberEncoder<O, T> {

    /**
     * Writes {@code value} into {@code buf}.
     */
    void encode(T value, O buf);
}
