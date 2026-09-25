package com.gto.datasynclib.network;

import com.gto.datasynclib.DataSyncLib;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Network payload for syncing block entity field data between client and server.
 * <p>
 * Carries the dimension, the block entity's position, its registry type name and the
 * serialized field data produced by
 * {@link com.gto.datasynclib.FieldDataManager#writeToNetworkBuffer}. A single payload type is
 * used for both directions (C2S and S2C) since the wire format is identical — the direction is
 * resolved from {@code IPayloadContext#flow()} when the payload is received.
 * <p>
 * 1.21.1/NeoForge: payloads travel as a {@link CustomPacketPayload} registered through the
 * {@code RegisterPayloadHandlersEvent} registrar instead of a Forge {@code SimpleChannel}, so
 * the type identity and the codec live on the payload itself. The receiver re-validates
 * dimension, position and type before touching any state.
 *
 * @param dimension       the dimension the block entity lives in; the receiver rejects payloads
 *                        addressed to another dimension
 * @param pos             the block position of the target block entity
 * @param blockEntityType the registry name of the target's block entity type; the receiver
 *                        rejects payloads that address another type at that position
 * @param data            the serialized field data (may be empty if no fields changed)
 */
public record BlockEntitySyncPacket(
        ResourceLocation dimension,
        BlockPos pos,
        ResourceLocation blockEntityType,
        byte[] data
) implements CustomPacketPayload {

    /**
     * Hard cap for a single payload (32 KiB). Views that can grow past it must be paginated,
     * because one payload is always sent as one packet.
     */
    public static final int MAX_BYTES = 32768;

    /**
     * Payload type id: {@code datasynclib:data_block}.
     */
    public static final Type<BlockEntitySyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DataSyncLib.MOD_ID, "data_block"));

    /**
     * Codec used by the payload registrar. Reads are length-capped by {@link #MAX_BYTES}.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, BlockEntitySyncPacket> CODEC =
            StreamCodec.ofMember(BlockEntitySyncPacket::write, BlockEntitySyncPacket::read);

    public BlockEntitySyncPacket {
        if (data.length > MAX_BYTES) {
            throw new IllegalArgumentException("Sync payload exceeds 32 KiB; paginate the view");
        }
    }

    /**
     * Encodes this payload into a network buffer.
     *
     * @param buf the buffer to write to
     */
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeBlockPos(pos);
        buf.writeResourceLocation(blockEntityType);
        buf.writeByteArray(data);
    }

    /**
     * Decodes a payload from a network buffer.
     *
     * @param buf the buffer to read from
     * @return the decoded payload
     */
    private static BlockEntitySyncPacket read(RegistryFriendlyByteBuf buf) {
        return new BlockEntitySyncPacket(
                buf.readResourceLocation(),
                buf.readBlockPos(),
                buf.readResourceLocation(),
                buf.readByteArray(MAX_BYTES)
        );
    }

    @Override
    public Type<BlockEntitySyncPacket> type() {
        return TYPE;
    }
}
