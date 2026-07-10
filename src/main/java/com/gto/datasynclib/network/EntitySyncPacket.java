package com.gto.datasynclib.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Network packet for syncing entity field data between client and server.
 * <p>
 * Carries the entity's network ID and the serialized field data produced by
 * {@link com.gto.datasynclib.FieldDataManager#writeToNetworkBuffer}.
 * A single packet record is used for both directions (C2S and S2C) since the wire format
 * is identical — direction is determined by the channel registration at send time.
 *
 * @param entityId the network ID of the target entity
 * @param data     the serialized field data (may be empty if no fields changed)
 */
public record EntitySyncPacket(int entityId, byte[] data) {

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
    public static EntitySyncPacket decode(FriendlyByteBuf buf) {
        return new EntitySyncPacket(buf.readVarInt(), buf.readByteArray());
    }
}
