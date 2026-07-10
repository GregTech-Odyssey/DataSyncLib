package com.gto.datasynclib.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Network packet for syncing block entity field data between client and server.
 * <p>
 * Carries the block entity's position and the serialized field data produced by
 * {@link com.gto.datasynclib.FieldDataManager#writeToNetworkBuffer}.
 * A single packet record is used for both directions (C2S and S2C) since the wire format
 * is identical — direction is determined by the channel registration at send time.
 *
 * @param pos  the block position of the target block entity
 * @param data the serialized field data (may be empty if no fields changed)
 */
public record BlockEntitySyncPacket(BlockPos pos, byte[] data) {

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
    public static BlockEntitySyncPacket decode(FriendlyByteBuf buf) {
        return new BlockEntitySyncPacket(buf.readBlockPos(), buf.readByteArray());
    }
}
