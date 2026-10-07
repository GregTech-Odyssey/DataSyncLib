package com.gto.datasynclib.remote;

import com.gto.datasynclib.DataSyncLib;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.annotations.RemoteCall;
import lombok.experimental.UtilityClass;
import net.minecraft.client.Minecraft;
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
 * Network layer of the remote call module: its own channel plus the two default packets
 * ({@link RemoteBlockEntityPacket}, {@link RemoteEntityPacket}) and the send helpers that tie them
 * to {@link RemoteInvoker}.
 *
 * <p>The channel is {@code datasynclib:remote} and carries its own {@link #PROTOCOL_VERSION},
 * independent of the field synchronization channel ({@code datasynclib:sync}), so the remote
 * module can be adopted without changing the sync protocol. Two messages are registered, each used
 * in both directions because the wire format is identical:</p>
 * <ul>
 *   <li>index 0 — {@link RemoteBlockEntityPacket}: block position + call payload</li>
 *   <li>index 1 — {@link RemoteEntityPacket}: entity network id + call payload</li>
 * </ul>
 *
 * <p>The receive side mirrors {@link com.gto.datasynclib.network.DataSyncNetwork}: direction comes
 * from {@code NetworkEvent.Context.getDirection().getOriginationSide()}, the target is looked up in
 * the receiving player's level, and the call runs on that side's thread
 * ({@code context.enqueueWork}). A failure while decoding or invoking is logged instead of being
 * propagated — a packet handler must not take the game down.</p>
 *
 * <p>Sending is push-based and split in two families: the four {@code callXxxOnServer/OnClients}
 * helpers encode the call ({@link RemoteInvoker#write}) and send it, while the four
 * {@code sendXxxToServer/ToClients} helpers take an already encoded payload — for instance from
 * {@link com.gto.datasynclib.FieldDataManager#writeRemoteCall(String, Object...)} — for callers that
 * build their own. Both schedule the send onto the owning side's executor, so they are safe to call
 * from off-thread code. Initialized once via {@link #init()} during mod construction (the mod calls it
 * next to the sync channel).</p>
 *
 * @see RemoteInvoker
 */
@UtilityClass
public class RemoteNetwork {

    private final String PROTOCOL_VERSION = "1";
    public final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(DataSyncLib.MOD_ID, "remote"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    private boolean initialized;

    /**
     * Registers the two default message handlers on {@link #CHANNEL}. Must be called once during
     * mod startup; repeated calls are ignored.
     */
    public void init() {
        if (initialized) return;
        initialized = true;

        // Index 0 — block entity target, both directions (see handleBlockEntity)
        CHANNEL.registerMessage(0,
                RemoteBlockEntityPacket.class,
                RemoteBlockEntityPacket::encode,
                RemoteBlockEntityPacket::decode,
                RemoteNetwork::handleBlockEntity);

        // Index 1 — entity target, both directions (see handleEntity)
        CHANNEL.registerMessage(1,
                RemoteEntityPacket.class,
                RemoteEntityPacket::encode,
                RemoteEntityPacket::decode,
                RemoteNetwork::handleEntity);
    }

    // ==================== Receive ====================

    private void handleBlockEntity(RemoteBlockEntityPacket packet, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getOriginationSide().isClient()) {
            context.enqueueWork(() -> {
                var sender = context.getSender();
                if (sender == null) return;
                applyBlockEntityCall(sender.level(), packet);
            });
        } else {
            context.enqueueWork(() -> {
                var player = Minecraft.getInstance().player;
                if (player == null) return;
                applyBlockEntityCall(player.level(), packet);
            });
        }
        context.setPacketHandled(true);
    }

    private void applyBlockEntityCall(@NotNull Level level, RemoteBlockEntityPacket packet) {
        var blockEntity = level.getBlockEntity(packet.pos());
        if (blockEntity == null) return;
        handleReceived(blockEntity, packet.data());
    }

    private void handleEntity(RemoteEntityPacket packet, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getOriginationSide().isClient()) {
            context.enqueueWork(() -> {
                var sender = context.getSender();
                if (sender == null) return;
                applyEntityCall(sender.level(), packet);
            });
        } else {
            context.enqueueWork(() -> {
                var player = Minecraft.getInstance().player;
                if (player == null) return;
                applyEntityCall(player.level(), packet);
            });
        }
        context.setPacketHandled(true);
    }

    private void applyEntityCall(@NotNull Level level, RemoteEntityPacket packet) {
        var entity = level.getEntity(packet.entityId());
        if (entity == null) return;
        handleReceived(entity, packet.data());
    }

    /**
     * Applies one received call to the object a packet resolved — the receive-side entry point, used
     * by both built-in handlers and available to a packet of your own.
     *
     * <p>Dispatch is {@link IFieldDataHolder#handleRemoteCall(Object, byte[])}: a target that is a
     * field holder (the library's
     * {@link com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity},
     * {@link com.gto.datasynclib.entity.FieldDataHolderEntity}, or any holder of your own) runs the call
     * through {@link com.gto.datasynclib.FieldDataManager#readRemoteCall(byte[])}, while any other
     * block entity or entity is invoked directly.</p>
     *
     * <p>A decode or invocation failure is logged instead of being propagated — a packet handler must
     * not take the game down.</p>
     *
     * @param target  the block entity or entity the packet addressed
     * @param payload the call payload; an empty one is ignored
     */
    public void handleReceived(@NotNull Object target, byte[] payload) {
        try {
            IFieldDataHolder.handleRemoteCall(target, payload);
        } catch (Throwable t) {
            DataSyncLib.LOGGER.error("Remote call failed for {} ({})", target.getClass().getName(), t);
        }
    }

}
