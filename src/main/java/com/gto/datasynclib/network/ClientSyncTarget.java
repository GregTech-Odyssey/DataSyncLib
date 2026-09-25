package com.gto.datasynclib.network;

import com.gto.datasynclib.IFieldDataHolder;
import net.minecraft.server.level.ServerPlayer;

/**
 * Opt-in contract for holders that accept client-to-server field updates.
 *
 * <p>A server-bound payload is never decoded straight into the live holder. Instead
 * {@link DataSyncNetwork#applyClientRequest} asks the target for a <strong>detached</strong>
 * request holder, decodes the payload into that holder, and only then lets the target validate
 * and apply the decoded values. Implementations should therefore:</p>
 * <ul>
 *   <li>return {@code false} from {@link #mayReceiveClientSync} unless the player is still
 *       authorized (open menu, ownership, mod-specific permission)</li>
 *   <li>return a fresh holder from {@link #createClientSyncRequest} — never {@code this}, and
 *       never one that shares mutable state with the live holder</li>
 *   <li>validate every decoded value in {@link #applyClientSyncRequest} before copying it into
 *       authoritative state</li>
 * </ul>
 *
 * <p>1.21.1/NeoForge addition (same contract as MachineLib's adaptation).</p>
 */
public interface ClientSyncTarget {

    /**
     * Checks the current menu/session, ownership and any mod-specific permissions.
     *
     * @param player the player that sent the request
     * @return {@code true} when the request may be processed further
     */
    boolean mayReceiveClientSync(ServerPlayer player);

    /**
     * Creates the detached holder the payload is decoded into.
     *
     * @param player the player that sent the request
     * @return a request holder that is not the live target
     */
    IFieldDataHolder createClientSyncRequest(ServerPlayer player);

    /**
     * Validates all values before changing authoritative state. Requests are never the
     * machine itself.
     *
     * @param player  the player that sent the request
     * @param request the detached holder carrying the decoded values
     */
    void applyClientSyncRequest(ServerPlayer player, IFieldDataHolder request);
}
