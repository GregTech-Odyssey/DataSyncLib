package com.gto.datasynclib.network;

import com.gto.datasynclib.DataSyncLib;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Network payload for syncing entity field data between client and server.
 * <p>
 * Carries the dimension, the entity's network id, its UUID and the serialized field data
 * produced by {@link com.gto.datasynclib.FieldDataManager#writeToNetworkBuffer}. A single
 * payload type is used for both directions (C2S and S2C) since the wire format is identical —
 * the direction is resolved from {@code IPayloadContext#flow()} when the payload is received.
 * <p>
 * 1.21.1/NeoForge: payloads travel as a {@link CustomPacketPayload} registered through the
 * {@code RegisterPayloadHandlersEvent} registrar instead of a Forge {@code SimpleChannel}.
 * The network id alone is not stable across dimensions, so the receiver also validates the
 * dimension and the entity's UUID before touching any state.
 *
 * @param dimension the dimension the entity lives in; the receiver rejects payloads addressed
 *                  to another dimension
 * @param entityId  the network id of the target entity
 * @param identity  the UUID of the target entity, used to detect a recycled network id
 * @param data      the serialized field data (may be empty if no fields changed)
 */
public record EntitySyncPacket(
        ResourceLocation dimension,
        int entityId,
        UUID identity,
        byte[] data
) implements CustomPacketPayload {

    /**
     * Payload type id: {@code datasynclib:data_entity}.
     */
    public static final Type<EntitySyncPacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(DataSyncLib.MOD_ID, "data_entity"));

    /**
     * Codec used by the payload registrar. Reads are length-capped by
     * {@link BlockEntitySyncPacket#MAX_BYTES}.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, EntitySyncPacket> CODEC =
            StreamCodec.ofMember(EntitySyncPacket::write, EntitySyncPacket::read);

    public EntitySyncPacket {
        if (data.length > BlockEntitySyncPacket.MAX_BYTES) {
            throw new IllegalArgumentException("Sync payload exceeds 32 KiB");
        }
    }

    /**
     * Encodes this payload into a network buffer.
     *
     * @param buf the buffer to write to
     */
    private void write(RegistryFriendlyByteBuf buf) {
        buf.writeResourceLocation(dimension);
        buf.writeVarInt(entityId);
        buf.writeUUID(identity);
        buf.writeByteArray(data);
    }

    /**
     * Decodes a payload from a network buffer.
     *
     * @param buf the buffer to read from
     * @return the decoded payload
     */
    private static EntitySyncPacket read(RegistryFriendlyByteBuf buf) {
        return new EntitySyncPacket(
                buf.readResourceLocation(),
                buf.readVarInt(),
                buf.readUUID(),
                buf.readByteArray(BlockEntitySyncPacket.MAX_BYTES)
        );
    }

    @Override
    public Type<EntitySyncPacket> type() {
        return TYPE;
    }
}
