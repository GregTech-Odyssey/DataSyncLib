package com.gto.datasynclib.network;

import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.SyncContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Objects;

/**
 * Central network layer for DataSyncLib synchronization.
 *
 * <p>1.21.1/NeoForge: the Forge {@code SimpleChannel} is gone. Both payloads implement
 * {@link net.minecraft.network.protocol.common.custom.CustomPacketPayload} with their own
 * {@code Type<>} and {@code StreamCodec}, and are registered through the
 * {@link RegisterPayloadHandlersEvent} registrar under protocol version
 * {@link #PROTOCOL_VERSION}:</p>
 * <ul>
 *   <li>{@link BlockEntitySyncPacket} — dimension + block position + block entity type +
 *       serialized field payload</li>
 *   <li>{@link EntitySyncPacket} — dimension + entity network id + UUID + serialized field
 *       payload</li>
 * </ul>
 *
 * <p>Each payload type is registered <strong>bidirectionally</strong>, because the wire format
 * is identical for both directions; the direction is resolved at receive time from
 * {@link IPayloadContext#flow()}:</p>
 * <ul>
 *   <li>{@code CLIENTBOUND} (S2C) — applied on the client with {@link LogicalSide#CLIENT}</li>
 *   <li>{@code SERVERBOUND} (C2S) — validated on the server, decoded into a detached request
 *       holder and then applied with {@link LogicalSide#SERVER}</li>
 * </ul>
 *
 * <p>NeoForge runs these handlers on the game thread, so the mutable holders are only ever
 * touched on their owning thread. A received payload is always re-validated against the
 * receiver's own level before it is used: dimension, chunk presence, block entity type or
 * entity identity, and — for C2S — the sender's distance (at most 8 blocks) to the target.
 * A live holder is never decoded into directly for C2S: the payload goes into a detached
 * request holder created by the target (see {@link ClientSyncTarget}).</p>
 *
 * <p>Synchronization is push-based: nothing is sent automatically, callers drive it from
 * their own tick/logic through the {@code syncXxxToServer/Client} helpers below. Those helpers
 * are safe to call from off-thread code: the send, the dirty-flag update and the
 * serialization are all scheduled onto the owning side's executor.</p>
 *
 * <p>This library is a standalone mod, so the mod entry class must register the payloads on
 * the mod event bus:</p>
 * <pre>{@code
 * public DataSyncLib(IEventBus modEventBus, ...) {
 *     modEventBus.addListener(DataSyncNetwork::register);
 *     ...
 * }
 * }</pre>
 *
 * @see #syncBlockEntityToClient(BlockEntity, boolean, boolean)
 * @see #syncEntityToClient(Entity, boolean, boolean)
 */
public final class DataSyncNetwork {

    /**
     * Payload protocol version. NeoForge refuses a Neo-Neo connection when the two sides
     * register the same payload with different versions.
     */
    private static final String PROTOCOL_VERSION = "1";

    private DataSyncNetwork() {
    }

    /**
     * Registers both payload handlers with the NeoForge payload registrar.
     * Must be called once during mod construction, from the mod event bus:
     * {@code modEventBus.addListener(DataSyncNetwork::register)}.
     *
     * @param event the payload registration event fired on the mod event bus
     */
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playBidirectional(BlockEntitySyncPacket.TYPE, BlockEntitySyncPacket.CODEC, DataSyncNetwork::receiveBlock);
        registrar.playBidirectional(EntitySyncPacket.TYPE, EntitySyncPacket.CODEC, DataSyncNetwork::receiveEntity);
    }

    // ==================== Block Entity Handlers ====================

    /**
     * Handles a {@link BlockEntitySyncPacket} in both directions.
     * <p>
     * The payload is only used when the receiver's own level contains the addressed block
     * entity: same dimension, chunk loaded, block entity present and not removed, and the
     * registry type matching the one in the payload.
     *
     * @param packet  the received payload
     * @param context the payload context (player, flow, connection type)
     */
    private static void receiveBlock(BlockEntitySyncPacket packet, IPayloadContext context) {
        var level = context.player().level();
        if (!level.dimension().location().equals(packet.dimension()) || !level.hasChunkAt(packet.pos())) return;
        var entity = level.getBlockEntity(packet.pos());
        if (entity == null
                || entity.isRemoved()
                || !BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(entity.getType()).equals(packet.blockEntityType())) return;
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            if (entity instanceof IFieldDataHolder holder) {
                holder.getFieldDataManager().readFromNetworkBuffer(
                        LogicalSide.CLIENT,
                        new SyncContext(level.registryAccess(), context.listener().getConnectionType()),
                        packet.data());
            }
        } else if (context.player() instanceof ServerPlayer player
                && player.distanceToSqr(packet.pos().getCenter()) <= 64) {
            applyClientRequest(
                    entity,
                    player,
                    new SyncContext(player.registryAccess(), context.listener().getConnectionType()),
                    packet.data());
        }
    }

    // ==================== Entity Handlers ====================

    /**
     * Handles an {@link EntitySyncPacket} in both directions.
     * <p>
     * A network id can be recycled, so the receiver also matches the entity's UUID and its
     * dimension before using the payload.
     *
     * @param packet  the received payload
     * @param context the payload context (player, flow, connection type)
     */
    private static void receiveEntity(EntitySyncPacket packet, IPayloadContext context) {
        var level = context.player().level();
        if (!level.dimension().location().equals(packet.dimension())) return;
        var entity = level.getEntity(packet.entityId());
        if (entity == null || !entity.getUUID().equals(packet.identity())) return;
        if (context.flow() == PacketFlow.CLIENTBOUND) {
            if (entity instanceof IFieldDataHolder holder) {
                holder.getFieldDataManager().readFromNetworkBuffer(
                        LogicalSide.CLIENT,
                        new SyncContext(level.registryAccess(), context.listener().getConnectionType()),
                        packet.data());
            }
        } else if (context.player() instanceof ServerPlayer player
                && player.distanceToSqr(entity) <= 64) {
            applyClientRequest(
                    entity,
                    player,
                    new SyncContext(player.registryAccess(), context.listener().getConnectionType()),
                    packet.data());
        }
    }

    /**
     * Applies one client-to-server request that the receiver has already matched to a target
     * and distance-checked.
     * <p>
     * The target must opt in through {@link ClientSyncTarget}: it decides whether the player
     * may send, provides a <strong>detached</strong> request holder, and validates the decoded
     * values before they reach authoritative state. Decoding into the live holder itself is a
     * bug, so it is rejected here.
     *
     * @param target      the block entity or entity the payload addressed
     * @param player      the player that sent the request
     * @param syncContext registries and connection type of the sending connection
     * @param data        the serialized field data
     */
    public static void applyClientRequest(Object target, ServerPlayer player, SyncContext syncContext, byte[] data) {
        if (!(target instanceof ClientSyncTarget access) || !access.mayReceiveClientSync(player)) return;
        if (data.length > BlockEntitySyncPacket.MAX_BYTES) return;
        var request = access.createClientSyncRequest(player);
        if (request == null || request == target) {
            throw new IllegalStateException("C2S requires a detached request holder");
        }
        request.getFieldDataManager().readFromNetworkBuffer(LogicalSide.SERVER, syncContext, data);
        access.applyClientSyncRequest(player, request);
    }

    /**
     * Syncs a block entity's {@code @SyncToClient} fields to players tracking its chunk.
     * <p>
     * Call from the <strong>server</strong> side. Async-safe.
     *
     * @param be       the block entity to sync
     * @param all      {@code true} to write all fields regardless of dirty state (full sync);
     *                 {@code false} to write only changed fields (incremental sync)
     * @param autoDetectOnly {@code true} to only detect changes on fields with
     *                 {@code autoDetect = true}; {@code false} to force-detect all fields
     */
    public static void syncBlockEntityToClient(BlockEntity be, boolean all, boolean autoDetectOnly) {
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        level.getServer().execute(() -> {
            if (be.isRemoved()
                    || !level.hasChunkAt(be.getBlockPos())
                    || level.getBlockEntity(be.getBlockPos()) != be
                    || !(be instanceof IFieldDataHolder holder)) return;
            var manager = holder.getFieldDataManager();
            if (!manager.hasSyncFields(LogicalSide.SERVER)
                    || (!all && !manager.updateFieldDirtyFlags(LogicalSide.SERVER, autoDetectOnly))) return;
            PacketDistributor.sendToPlayersTrackingChunk(
                    level,
                    new ChunkPos(be.getBlockPos()),
                    packet(be, manager.writeToNetworkBuffer(LogicalSide.SERVER, SyncContext.neoforge(level.registryAccess()), all)));
        });
    }

    /**
     * Syncs a block entity's full {@code @SyncToClient} state to one player.
     * <p>
     * Call from the <strong>server</strong> side. The state is written as a
     * <strong>non-consuming</strong> snapshot ({@link com.gto.datasynclib.FieldDataManager#writeSnapshot}),
     * so the dirty baseline shared with the chunk observers is neither consumed nor cleared —
     * this is the intended path for a player that just opened a menu or started tracking.
     *
     * @param be     the block entity to sync
     * @param player the player that should receive the snapshot
     */
    public static void syncBlockEntityToPlayer(BlockEntity be, ServerPlayer player) {
        if (!(be.getLevel() instanceof ServerLevel level)) return;
        level.getServer().execute(() -> {
            if (player.serverLevel() != level
                    || be.isRemoved()
                    || !level.hasChunkAt(be.getBlockPos())
                    || level.getBlockEntity(be.getBlockPos()) != be
                    || !(be instanceof IFieldDataHolder holder)) return;
            PacketDistributor.sendToPlayer(
                    player,
                    packet(be, holder.getFieldDataManager().writeSnapshot(
                            LogicalSide.SERVER,
                            new SyncContext(player.registryAccess(), player.connection.getConnectionType()))));
        });
    }

    /**
     * Builds a block entity payload with the routing metadata the receiver validates against.
     *
     * @param be   the block entity the payload is about
     * @param data the serialized field data
     * @return the payload to send
     */
    private static BlockEntitySyncPacket packet(BlockEntity be, byte[] data) {
        return new BlockEntitySyncPacket(
                be.getLevel().dimension().location(),
                be.getBlockPos(),
                BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()),
                data);
    }

    /**
     * Syncs an entity's {@code @SyncToClient} fields to players tracking it.
     * <p>
     * Call from the <strong>server</strong> side. Async-safe.
     *
     * @param entity   the entity to sync
     * @param all      {@code true} to write all fields (full sync);
     *                 {@code false} for incremental (only changed fields)
     * @param autoDetectOnly {@code true} to only detect changes on fields with
     *                 {@code autoDetect = true}; {@code false} to force-detect all fields
     */
    public static void syncEntityToClient(Entity entity, boolean all, boolean autoDetectOnly) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        level.getServer().execute(() -> {
            if (entity.isRemoved()
                    || entity.level() != level
                    || !(entity instanceof IFieldDataHolder holder)) return;
            var manager = holder.getFieldDataManager();
            if (!manager.hasSyncFields(LogicalSide.SERVER)
                    || (!all && !manager.updateFieldDirtyFlags(LogicalSide.SERVER, autoDetectOnly))) return;
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                    entity,
                    new EntitySyncPacket(
                            level.dimension().location(),
                            entity.getId(),
                            entity.getUUID(),
                            manager.writeToNetworkBuffer(LogicalSide.SERVER, SyncContext.neoforge(level.registryAccess()), all)));
        });
    }

    /**
     * Syncs a block entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side. Async-safe.
     *
     * @param be       the block entity to sync
     * @param all      {@code true} to write all fields (full sync);
     *                 {@code false} for incremental (only changed fields)
     * @param autoDetectOnly {@code true} to only detect changes on fields with
     *                 {@code autoDetect = true}; {@code false} to force-detect all fields
     */
    public static void syncBlockEntityToServer(BlockEntity be, boolean all, boolean autoDetectOnly) {
        if (be.getLevel() == null || !be.getLevel().isClientSide()) return;
        Client.sendBlock(be, all, autoDetectOnly);
    }

    /**
     * Syncs an entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side. Async-safe.
     *
     * @param entity   the entity to sync
     * @param all      {@code true} to write all fields (full sync);
     *                 {@code false} for incremental (only changed fields)
     * @param autoDetectOnly {@code true} to only detect changes on fields with
     *                 {@code autoDetect = true}; {@code false} to force-detect all fields
     */
    public static void syncEntityToServer(Entity entity, boolean all, boolean autoDetectOnly) {
        if (!entity.level().isClientSide()) return;
        Client.sendEntity(entity, all, autoDetectOnly);
    }

    /**
     * Client-only sends. Kept in a separate holder class so the dedicated server never has to
     * load the client classes referenced here.
     */
    private static final class Client {

        /**
         * Builds the context of the client's own connection.
         *
         * @return the context used for client-to-server writes
         */
        private static SyncContext context() {
            var connection = Objects.requireNonNull(Minecraft.getInstance().getConnection());
            return new SyncContext(connection.registryAccess(), connection.getConnectionType());
        }

        private static void sendBlock(BlockEntity be, boolean all, boolean autoDetectOnly) {
            Minecraft.getInstance().execute(() -> {
                if (be.isRemoved() || !(be instanceof IFieldDataHolder holder)) return;
                var manager = holder.getFieldDataManager();
                if (manager.hasSyncFields(LogicalSide.CLIENT)
                        && (all || manager.updateFieldDirtyFlags(LogicalSide.CLIENT, autoDetectOnly))) {
                    PacketDistributor.sendToServer(packet(be, manager.writeToNetworkBuffer(LogicalSide.CLIENT, context(), all)));
                }
            });
        }

        private static void sendEntity(Entity entity, boolean all, boolean autoDetectOnly) {
            Minecraft.getInstance().execute(() -> {
                if (entity.isRemoved() || !(entity instanceof IFieldDataHolder holder)) return;
                var manager = holder.getFieldDataManager();
                if (manager.hasSyncFields(LogicalSide.CLIENT)
                        && (all || manager.updateFieldDirtyFlags(LogicalSide.CLIENT, autoDetectOnly))) {
                    PacketDistributor.sendToServer(new EntitySyncPacket(
                            entity.level().dimension().location(),
                            entity.getId(),
                            entity.getUUID(),
                            manager.writeToNetworkBuffer(LogicalSide.CLIENT, context(), all)));
                }
            });
        }
    }

    /**
     * Syncs a block entity's {@code @SyncToClient} fields to players tracking its chunk.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     *
     * @param be  the block entity to sync
     * @param all {@code true} to write all fields (full sync);
     *            {@code false} for incremental (only changed fields)
     */
    public static void syncBlockEntityToClient(BlockEntity be, boolean all) {
        syncBlockEntityToClient(be, all, true);
    }

    /**
     * Syncs an entity's {@code @SyncToClient} fields to players tracking it.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     *
     * @param entity the entity to sync
     * @param all    {@code true} to write all fields (full sync);
     *               {@code false} for incremental (only changed fields)
     */
    public static void syncEntityToClient(Entity entity, boolean all) {
        syncEntityToClient(entity, all, true);
    }

    /**
     * Syncs a block entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     *
     * @param be  the block entity to sync
     * @param all {@code true} to write all fields (full sync);
     *            {@code false} for incremental (only changed fields)
     */
    public static void syncBlockEntityToServer(BlockEntity be, boolean all) {
        syncBlockEntityToServer(be, all, true);
    }

    /**
     * Syncs an entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     *
     * @param entity the entity to sync
     * @param all    {@code true} to write all fields (full sync);
     *               {@code false} for incremental (only changed fields)
     */
    public static void syncEntityToServer(Entity entity, boolean all) {
        syncEntityToServer(entity, all, true);
    }

    /**
     * Syncs a block entity's {@code @SyncToClient} fields to players tracking its chunk.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     *
     * @param be the block entity to sync
     */
    public static void syncBlockEntityToClient(BlockEntity be) {
        syncBlockEntityToClient(be, false, true);
    }

    /**
     * Syncs an entity's {@code @SyncToClient} fields to players tracking it.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     *
     * @param entity the entity to sync
     */
    public static void syncEntityToClient(Entity entity) {
        syncEntityToClient(entity, false, true);
    }

    /**
     * Syncs a block entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     *
     * @param be the block entity to sync
     */
    public static void syncBlockEntityToServer(BlockEntity be) {
        syncBlockEntityToServer(be, false, true);
    }

    /**
     * Syncs an entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     *
     * @param entity the entity to sync
     */
    public static void syncEntityToServer(Entity entity) {
        syncEntityToServer(entity, false, true);
    }
}
