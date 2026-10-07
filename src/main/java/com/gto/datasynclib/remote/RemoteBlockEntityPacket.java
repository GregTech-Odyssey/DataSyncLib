package com.gto.datasynclib.remote;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Default remote call packet for a <strong>block entity</strong>: the target's block position plus
 * the payload produced by {@link RemoteInvoker#write(Object, String, Object...)}.
 *
 * <p>One record serves both directions — the wire format is identical, and the receiving side
 * resolves the block entity from its own level, invokes the call on it and (for a server-side
 * arrival) is the only side that may mutate it. Registration and the level lookup live in
 * {@link RemoteNetwork}; the packet itself stays channel-agnostic, so it can also be registered
 * on a channel of your own.</p>
 *
 * @param pos  the block position of the target block entity
 * @param data the serialized remote call (call prefix + arguments)
 * @see RemoteEntityPacket
 */
public record RemoteBlockEntityPacket(BlockPos pos, byte[] data) {

    /**
     * Encodes this packet into a network buffer.
     *
     * @param buf the buffer to write to
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeByteArray(data);
    }

    /**
     * Decodes a packet from a network buffer.
     *
     * @param buf the buffer to read from
     * @return the decoded packet
     */
    public static RemoteBlockEntityPacket decode(FriendlyByteBuf buf) {
        return new RemoteBlockEntityPacket(buf.readBlockPos(), buf.readByteArray());
    }
}
