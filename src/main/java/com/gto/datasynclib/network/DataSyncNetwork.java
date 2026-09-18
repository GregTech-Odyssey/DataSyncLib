package com.gto.datasynclib.network;

import com.gto.datasynclib.DataSyncLib;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import lombok.experimental.UtilityClass;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import org.jetbrains.annotations.NotNull;

import java.util.function.Supplier;

/**
 * Central network channel for DataSyncLib synchronization.
 */
@UtilityClass
public class DataSyncNetwork {

    private final String PROTOCOL_VERSION = "2";
    public final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(DataSyncLib.MOD_ID, "sync"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private boolean initialized;

    /**
     * Initializes the network channel by registering all message handlers.
     * Must be called once during mod startup. Repeated calls are ignored.
     */
    public void init() {
        if (initialized) return;
        initialized = true;

        // Index 0: Block Entity — Client → Server
        CHANNEL.registerMessage(0,
                BlockEntitySyncPacket.class,
                BlockEntitySyncPacket::encode,
                BlockEntitySyncPacket::decode,
                DataSyncNetwork::handleBlockEntity);

        // Index 1: Entity — Client → Server
        CHANNEL.registerMessage(1,
                EntitySyncPacket.class,
                EntitySyncPacket::encode,
                EntitySyncPacket::decode,
                DataSyncNetwork::handleEntity);
    }

    // ==================== Block Entity Handlers ====================

    private void handleBlockEntity(BlockEntitySyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getOriginationSide().isClient()) {
            context.enqueueWork(() -> {
                var sender = context.getSender();
                if (sender == null) return;
                applyBlockEntitySyncData(sender.level(), packet.pos(), packet.data(), LogicalSide.SERVER);
            });
        } else {
            context.enqueueWork(() -> {
                var player = Minecraft.getInstance().player;
                if (player == null) return;
                applyBlockEntitySyncData(player.level(), packet.pos(), packet.data(), LogicalSide.CLIENT);
            });
        }
        context.setPacketHandled(true);
    }

    private void applyBlockEntitySyncData(@NotNull Level level, @NotNull BlockPos pos, byte @NotNull [] data, LogicalSide side) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof IFieldDataHolder holder && data.length > 0) {
            holder.getFieldDataManager().readFromNetworkBuffer(side, data);
            if (side.isServer()) {
                be.setChanged();
            }
        }
    }

    // ==================== Entity Handlers ====================

    private void handleEntity(EntitySyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getOriginationSide().isClient()) {
            context.enqueueWork(() -> {
                var sender = context.getSender();
                if (sender == null) return;
                applyEntitySyncData(sender.level(), packet.entityId(), packet.data(), LogicalSide.SERVER);
            });
        } else {
            context.enqueueWork(() -> {
                var player = Minecraft.getInstance().player;
                if (player == null) return;
                applyEntitySyncData(player.level(), packet.entityId(), packet.data(), LogicalSide.CLIENT);
            });
        }
        context.setPacketHandled(true);
    }

    private void applyEntitySyncData(@NotNull Level level, int entityId, byte @NotNull [] data, LogicalSide side) {
        Entity entity = level.getEntity(entityId);
        if (entity instanceof IFieldDataHolder holder && data.length > 0) {
            holder.getFieldDataManager().readFromNetworkBuffer(side, data);
        }
    }

    /**
     * Syncs a block entity's {@code @SyncToClient} fields to players tracking its chunk.
     * <p>
     * Call from the <strong>server</strong> side. Async-safe.
     *
     * @param be       the block entity to sync
     * @param all      {@code true} to write all fields regardless of dirty state (full sync);
     *                 {@code false} to write only changed fields (incremental sync)
     * @param autoOnly {@code true} to only detect changes on fields with
     *                 {@code autoUpdate = true}; {@code false} to force-detect all fields
     */
    public void syncBlockEntityToClient(@NotNull BlockEntity be, boolean all, boolean autoOnly) {
        if (be instanceof IFieldDataHolder holder
                && be.getLevel() instanceof ServerLevel level
                && holder.getFieldDataManager().hasSyncFields(LogicalSide.SERVER)
                && holder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, autoOnly)) {
            byte[] data = holder.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, all);
            var packet = new BlockEntitySyncPacket(be.getBlockPos(), data);
            level.getServer().execute(() ->
                    CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(be.getBlockPos())), packet));
        }
    }

    /**
     * Syncs a block entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side. Async-safe.
     *
     * @param be       the block entity to sync
     * @param all      {@code true} to write all fields (full sync);
     *                 {@code false} for incremental (only changed fields)
     * @param autoOnly {@code true} to only detect changes on fields with
     *                 {@code autoUpdate = true}; {@code false} to force-detect all fields
     */
    public void syncBlockEntityToServer(@NotNull BlockEntity be, boolean all, boolean autoOnly) {
        var level = be.getLevel();
        if (level == null || !level.isClientSide()) return;
        if (be instanceof IFieldDataHolder holder
                && holder.getFieldDataManager().hasSyncFields(LogicalSide.CLIENT)
                && holder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.CLIENT, autoOnly)) {
            byte[] data = holder.getFieldDataManager().writeToNetworkBuffer(LogicalSide.CLIENT, all);
            var packet = new BlockEntitySyncPacket(be.getBlockPos(), data);
            Minecraft.getInstance().execute(() -> CHANNEL.sendToServer(packet));
        }
    }

    /**
     * Syncs an entity's {@code @SyncToClient} fields to players tracking it.
     * <p>
     * Call from the <strong>server</strong> side. Async-safe.
     *
     * @param entity   the entity to sync
     * @param all      {@code true} to write all fields (full sync);
     *                 {@code false} for incremental (only changed fields)
     * @param autoOnly {@code true} to only detect changes on fields with
     *                 {@code autoUpdate = true}; {@code false} to force-detect all fields
     */
    public void syncEntityToClient(@NotNull Entity entity, boolean all, boolean autoOnly) {
        if (entity.level().isClientSide()) return;
        if (entity instanceof IFieldDataHolder holder
                && entity.level() instanceof ServerLevel level
                && holder.getFieldDataManager().hasSyncFields(LogicalSide.SERVER)
                && holder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, autoOnly)) {
            byte[] data = holder.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, all);
            var packet = new EntitySyncPacket(entity.getId(), data);
            level.getServer().execute(() ->
                    CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), packet));
        }
    }

    /**
     * Syncs an entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side. Async-safe.
     *
     * @param entity   the entity to sync
     * @param all      {@code true} to write all fields (full sync);
     *                 {@code false} for incremental (only changed fields)
     * @param autoOnly {@code true} to only detect changes on fields with
     *                 {@code autoUpdate = true}; {@code false} to force-detect all fields
     */
    public void syncEntityToServer(@NotNull Entity entity, boolean all, boolean autoOnly) {
        if (!entity.level().isClientSide()) return;
        if (entity instanceof IFieldDataHolder holder
                && holder.getFieldDataManager().hasSyncFields(LogicalSide.CLIENT)
                && holder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.CLIENT, autoOnly)) {
            byte[] data = holder.getFieldDataManager().writeToNetworkBuffer(LogicalSide.CLIENT, all);
            var packet = new EntitySyncPacket(entity.getId(), data);
            Minecraft.getInstance().execute(() -> CHANNEL.sendToServer(packet));
        }
    }


    /**
     * Syncs a block entity's {@code @SyncToClient} fields to players tracking its chunk.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     */
    public void syncBlockEntityToClient(@NotNull BlockEntity be, boolean all) {
        syncBlockEntityToClient(be, all, true);
    }

    /**
     * Syncs a block entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncBlockEntityToServer(@NotNull BlockEntity be, boolean all) {
        syncBlockEntityToServer(be, all, true);
    }

    /**
     * Syncs an entity's {@code @SyncToClient} fields to players tracking it.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     */
    public void syncEntityToClient(@NotNull Entity entity, boolean all) {
        syncEntityToClient(entity, all, true);
    }

    /**
     * Syncs an entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncEntityToServer(@NotNull Entity entity, boolean all) {
        syncEntityToServer(entity, all, true);
    }


    /**
     * Syncs a block entity's {@code @SyncToClient} fields to players tracking its chunk.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     */
    public void syncBlockEntityToClient(@NotNull BlockEntity be) {
        syncBlockEntityToClient(be, false, true);
    }

    /**
     * Syncs a block entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncBlockEntityToServer(@NotNull BlockEntity be) {
        syncBlockEntityToServer(be, false, true);
    }

    /**
     * Syncs an entity's {@code @SyncToClient} fields to players tracking it.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     */
    public void syncEntityToClient(@NotNull Entity entity) {
        syncEntityToClient(entity, false, true);
    }

    /**
     * Syncs an entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncEntityToServer(@NotNull Entity entity) {
        syncEntityToServer(entity, false, true);
    }
}
