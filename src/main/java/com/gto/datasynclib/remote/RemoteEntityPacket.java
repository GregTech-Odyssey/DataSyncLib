package com.gto.datasynclib.remote;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Default remote call packet for an <strong>entity</strong>: the target's network id plus the
 * payload produced by {@link RemoteInvoker#write(Object, String, Object...)}.
 *
 * <p>One record serves both directions — the wire format is identical, and the receiving side
 * resolves the entity from its own level by that id, invokes the call on it and (for a
 * server-side arrival) is the only side that may mutate it. Registration and the level lookup live
 * in {@link RemoteNetwork}; the packet itself stays channel-agnostic, so it can also be registered
 * on a channel of your own.</p>
 *
 * @param entityId the network id of the target entity
 * @param data     the serialized remote call (call prefix + arguments)
 * @see RemoteBlockEntityPacket
 */
public record RemoteEntityPacket(int entityId, byte[] data) {

    /**
     * Encodes this packet into a network buffer.
     *
     * @param buf the buffer to write to
     */
    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeByteArray(data);
    }

    /**
     * Decodes a packet from a network buffer.
     *
     * @param buf the buffer to read from
     * @return the decoded packet
     */
    public static RemoteEntityPacket decode(FriendlyByteBuf buf) {
        return new RemoteEntityPacket(buf.readVarInt(), buf.readByteArray());
    }
}
