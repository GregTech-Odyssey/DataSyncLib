package com.gto.datasynclib.remote;

import com.gto.datasynclib.DataSyncLib;
import com.gto.datasynclib.IFieldDataHolder;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.network.DataSyncNetwork;
import lombok.experimental.UtilityClass;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;
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
 * <p>Sending is left to the caller: a call is built with
 * {@link com.gto.datasynclib.FieldDataManager#writeRemoteCall(Object, Object, String, Object...)}
 * (or {@link RemoteInvoker#write(Object, String, Object...)}) and put into whichever packet suits the
 * target — the two here, or one of your own — because the payload is opaque to the transport. This class
 * therefore only owns the channel, the two packets and the receive path. Initialized once via
 * {@link #init()} during mod construction (the mod calls it next to the sync channel).</p>
 *
 * @see RemoteInvoker
 */
@UtilityClass
public class RemoteNetwork {

    public void init() {
        DataSyncNetwork.CHANNEL.registerMessage(2,
                RemoteBlockEntityPacket.class,
                RemoteBlockEntityPacket::encode,
                RemoteBlockEntityPacket::decode,
                RemoteNetwork::handleBlockEntity);

        DataSyncNetwork.CHANNEL.registerMessage(3,
                RemoteEntityPacket.class,
                RemoteEntityPacket::encode,
                RemoteEntityPacket::decode,
                RemoteNetwork::handleEntity);
    }

    // ==================== Receive ====================

    private void handleBlockEntity(RemoteBlockEntityPacket packet, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getOriginationSide().isClient()) {
            // Originated on the client: this handler runs on the server.
            context.enqueueWork(() -> {
                var sender = context.getSender();
                if (sender == null) return;
                applyBlockEntityCall(sender.level(), packet, LogicalSide.SERVER);
            });
        } else {
            context.enqueueWork(() -> {
                var player = Minecraft.getInstance().player;
                if (player == null) return;
                applyBlockEntityCall(player.level(), packet, LogicalSide.CLIENT);
            });
        }
        context.setPacketHandled(true);
    }

    private void applyBlockEntityCall(@NotNull Level level, RemoteBlockEntityPacket packet, LogicalSide side) {
        var blockEntity = level.getBlockEntity(packet.pos());
        if (blockEntity == null) return;
        handleReceived(blockEntity, packet.data(), side);
    }

    private void handleEntity(RemoteEntityPacket packet, Supplier<NetworkEvent.Context> ctx) {
        var context = ctx.get();
        if (context.getDirection().getOriginationSide().isClient()) {
            context.enqueueWork(() -> {
                var sender = context.getSender();
                if (sender == null) return;
                applyEntityCall(sender.level(), packet, LogicalSide.SERVER);
            });
        } else {
            context.enqueueWork(() -> {
                var player = Minecraft.getInstance().player;
                if (player == null) return;
                applyEntityCall(player.level(), packet, LogicalSide.CLIENT);
            });
        }
        context.setPacketHandled(true);
    }

    private void applyEntityCall(@NotNull Level level, RemoteEntityPacket packet, LogicalSide side) {
        var entity = level.getEntity(packet.entityId());
        if (entity == null) return;
        handleReceived(entity, packet.data(), side);
    }

    /**
     * Applies one received call to the object a packet resolved — the receive-side entry point, used
     * by both built-in handlers and available to a packet of your own.
     *
     * <p>Dispatch is {@link IFieldDataHolder#handleRemoteCall(Object, byte[])}, which resolves the call's
     * node index against the object the packet addressed ({@code 0} being that object itself, see
     * {@link RemoteRouting}): the call runs on whichever holder in that tree the index names — the target
     * itself ({@link com.gto.datasynclib.blockentity.FieldDataHolderBlockEntity},
     * {@link com.gto.datasynclib.entity.FieldDataHolderEntity}, or any holder of your own), or a nested
     * one. The side the call arrived on is taken from the target's level, so
     * {@link com.gto.datasynclib.annotations.RemoteCall#side()} is enforced; pass the side explicitly with
     * {@link #handleReceived(Object, byte[], LogicalSide)} when the target has no level of its own.</p>
     *
     * <p>A decode, direction or invocation failure is logged instead of being propagated — a packet
     * handler must not take the game down.</p>
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

    /**
     * The side-aware form of {@link #handleReceived(Object, byte[])} — what the built-in handlers use,
     * because they know which side they are running on from the packet's direction.
     *
     * @param target  the block entity or entity the packet addressed
     * @param payload the call payload; an empty one is ignored
     * @param side    the side the call arrived on
     */
    public void handleReceived(@NotNull Object target, byte[] payload, LogicalSide side) {
        try {
            IFieldDataHolder.handleRemoteCall(target, payload, side);
        } catch (Throwable t) {
            DataSyncLib.LOGGER.error("Remote call failed for {} on {} ({})", target.getClass().getName(), side, t);
        }
    }

}
