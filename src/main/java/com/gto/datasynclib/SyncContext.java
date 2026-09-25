package com.gto.datasynclib;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.Objects;

/**
 * Explicit context for a single sync operation: the registries used to resolve
 * registry-dependent values plus the {@link ConnectionType} the payload travels over.
 *
 * <p>1.21.1/NeoForge addition (same contract as MachineLib's adaptation). A
 * {@link RegistryFriendlyByteBuf} needs both pieces of information, and neither can be
 * recovered from a plain {@code byte[]}, so every
 * {@link FieldDataManager#writeToNetworkBuffer} /
 * {@link FieldDataManager#readFromNetworkBuffer} call carries one of these instead of
 * relying on an ambient thread-local.</p>
 *
 * <p>Child buffers created through {@link #buffer(ByteBuf)} inherit both values.</p>
 *
 * @param registries     the registry access of the side performing the operation
 * @param connectionType the connection type of the channel carrying the payload
 */
public record SyncContext(RegistryAccess registries, ConnectionType connectionType) {

    public SyncContext {
        Objects.requireNonNull(registries);
        Objects.requireNonNull(connectionType);
    }

    /**
     * Derives the context from a network buffer that already carries both values.
     *
     * @param buffer the buffer to read the context from
     * @return the context of that buffer
     */
    public static SyncContext of(RegistryFriendlyByteBuf buffer) {
        return new SyncContext(buffer.registryAccess(), buffer.getConnectionType());
    }

    /**
     * Creates a context for the library's own play channel, which always negotiates
     * NeoForge encoding.
     *
     * @param registries the registry access of the operation
     * @return a context using {@link ConnectionType#NEOFORGE}
     */
    public static SyncContext neoforge(RegistryAccess registries) {
        return new SyncContext(registries, ConnectionType.NEOFORGE);
    }

    /**
     * Wraps a raw buffer with this context's registries and connection type.
     *
     * @param bytes the backing buffer
     * @return a registry-aware buffer
     */
    public RegistryFriendlyByteBuf buffer(ByteBuf bytes) {
        return new RegistryFriendlyByteBuf(bytes, registries, connectionType);
    }
}
