package com.gto.datasynclib.network;

import com.gto.datasynclib.DataSyncLib;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import lombok.experimental.UtilityClass;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * <p>
 * Provides a {@link SimpleChannel} with four registered message types:
 * <ul>
 *   <li>Index 0 — Block Entity C2S: client pushes field changes to the server</li>
 *   <li>Index 1 — Block Entity S2C: server pushes field changes to tracking clients</li>
 *   <li>Index 2 — Entity C2S: client pushes field changes to the server</li>
 *   <li>Index 3 — Entity S2C: server pushes field changes to tracking clients</li>
 * </ul>
 * <p>
 * The channel is initialized via {@link #init()} during mod construction.
 */
@UtilityClass
public class DataSyncNetwork {

    private final String PROTOCOL_VERSION = "1";
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
                DataSyncNetwork::handleBlockEntityC2S);

        // Index 1: Block Entity — Server → Client
        CHANNEL.registerMessage(1,
                BlockEntitySyncPacket.class,
                BlockEntitySyncPacket::encode,
                BlockEntitySyncPacket::decode,
                DataSyncNetwork::handleBlockEntityS2C);

        // Index 2: Entity — Client → Server
        CHANNEL.registerMessage(2,
                EntitySyncPacket.class,
                EntitySyncPacket::encode,
                EntitySyncPacket::decode,
                DataSyncNetwork::handleEntityC2S);

        // Index 3: Entity — Server → Client
        CHANNEL.registerMessage(3,
                EntitySyncPacket.class,
                EntitySyncPacket::encode,
                EntitySyncPacket::decode,
                DataSyncNetwork::handleEntityS2C);
    }

    // ==================== Block Entity Handlers ====================

    private void handleBlockEntityC2S(BlockEntitySyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) return;
            applyBlockEntitySyncData(sender.level(), packet.pos(), packet.data(), LogicalSide.SERVER);
        });
        context.setPacketHandled(true);
    }

    private void handleBlockEntityS2C(BlockEntitySyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            applyBlockEntitySyncData(player.level(), packet.pos(), packet.data(), LogicalSide.CLIENT);
        });
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

    private void handleEntityC2S(EntitySyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer sender = context.getSender();
            if (sender == null) return;
            applyEntitySyncData(sender.level(), packet.entityId(), packet.data(), LogicalSide.SERVER);
        });
        context.setPacketHandled(true);
    }

    private void handleEntityS2C(EntitySyncPacket packet, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            var player = Minecraft.getInstance().player;
            if (player == null) return;
            applyEntitySyncData(player.level(), packet.entityId(), packet.data(), LogicalSide.CLIENT);
        });
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
     * Call from the <strong>server</strong> side.
     * Support async calls.
     */
    public void syncBlockEntityToClient(@NotNull BlockEntity be, boolean all, boolean auto) {
        if (be instanceof IFieldDataHolder holder
                && be.getLevel() instanceof ServerLevel level
                && holder.getFieldDataManager().hasSyncFields(LogicalSide.SERVER)
                && holder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, auto)) {
            byte[] data = holder.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, all);
            var packet = new BlockEntitySyncPacket(be.getBlockPos(), data);
            level.getServer().execute(() ->
                    CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunkAt(be.getBlockPos())), packet));
        }
    }

    /**
     * Syncs a block entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncBlockEntityToServer(@NotNull BlockEntity be, boolean all, boolean auto) {
        var level = be.getLevel();
        if (level == null || !level.isClientSide()) return;
        if (be instanceof IFieldDataHolder holder
                && holder.getFieldDataManager().hasSyncFields(LogicalSide.CLIENT)
                && holder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.CLIENT, auto)) {
            byte[] data = holder.getFieldDataManager().writeToNetworkBuffer(LogicalSide.CLIENT, all);
            var packet = new BlockEntitySyncPacket(be.getBlockPos(), data);
            Minecraft.getInstance().execute(() -> CHANNEL.sendToServer(packet));
        }
    }

    /**
     * Syncs an entity's {@code @SyncToClient} fields to players tracking it.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     */
    public void syncEntityToClient(@NotNull Entity entity, boolean all, boolean auto) {
        if (entity.level().isClientSide()) return;
        if (entity instanceof IFieldDataHolder holder
                && entity.level() instanceof ServerLevel level
                && holder.getFieldDataManager().hasSyncFields(LogicalSide.SERVER)
                && holder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.SERVER, auto)) {
            byte[] data = holder.getFieldDataManager().writeToNetworkBuffer(LogicalSide.SERVER, all);
            var packet = new EntitySyncPacket(entity.getId(), data);
            level.getServer().execute(() ->
                    CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> entity), packet));
        }
    }

    /**
     * Syncs an entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncEntityToServer(@NotNull Entity entity, boolean all, boolean auto) {
        if (!entity.level().isClientSide()) return;
        if (entity instanceof IFieldDataHolder holder
                && holder.getFieldDataManager().hasSyncFields(LogicalSide.CLIENT)
                && holder.getFieldDataManager().updateFieldDirtyFlags(LogicalSide.CLIENT, auto)) {
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
        syncBlockEntityToClient(be,all,true);
    }

    /**
     * Syncs a block entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncBlockEntityToServer(@NotNull BlockEntity be, boolean all) {
        syncBlockEntityToServer(be,all,true);
    }

    /**
     * Syncs an entity's {@code @SyncToClient} fields to players tracking it.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     */
    public void syncEntityToClient(@NotNull Entity entity, boolean all) {
        syncEntityToClient(entity,all,true);
    }

    /**
     * Syncs an entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncEntityToServer(@NotNull Entity entity, boolean all) {
        syncEntityToServer(entity,all,true);
    }


    /**
     * Syncs a block entity's {@code @SyncToClient} fields to players tracking its chunk.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     */
    public void syncBlockEntityToClient(@NotNull BlockEntity be) {
        syncBlockEntityToClient(be,false,true);
    }

    /**
     * Syncs a block entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncBlockEntityToServer(@NotNull BlockEntity be) {
        syncBlockEntityToServer(be,false,true);
    }

    /**
     * Syncs an entity's {@code @SyncToClient} fields to players tracking it.
     * <p>
     * Call from the <strong>server</strong> side.
     * Support async calls.
     */
    public void syncEntityToClient(@NotNull Entity entity) {
        syncEntityToClient(entity,false,true);
    }

    /**
     * Syncs an entity's {@code @SyncToServer} fields to the server.
     * <p>
     * Call from the <strong>client</strong> side.
     * Support async calls.
     */
    public void syncEntityToServer(@NotNull Entity entity) {
        syncEntityToServer(entity,false,true);
    }
}
