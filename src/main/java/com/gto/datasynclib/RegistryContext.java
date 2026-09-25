package com.gto.datasynclib;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;

import java.util.Objects;

/**
 * Scoped lookup for persistence codecs. Network codecs receive their context in their
 * buffer instead — see {@link SyncContext}.
 *
 * <p>1.21.1/NeoForge addition (same contract as MachineLib's adaptation): in 1.21 the
 * {@code saveAdditional}/{@code loadAdditional}/{@code getUpdateTag} methods receive a
 * {@link HolderLookup.Provider}, but the nested field codecs have no parameter to receive it,
 * so the holder publishes it for the duration of one disk operation and
 * {@link #current()} reads it back.</p>
 *
 * <p>Scopes nest: {@link #close()} restores the provider that was active before.</p>
 */
public final class RegistryContext implements AutoCloseable {

    private static final ThreadLocal<HolderLookup.Provider> CURRENT = new ThreadLocal<>();

    private final HolderLookup.Provider previous;

    private RegistryContext(HolderLookup.Provider provider) {
        previous = CURRENT.get();
        CURRENT.set(Objects.requireNonNull(provider));
    }

    /**
     * Publishes a lookup for the current thread. Use it in a try-with-resources block.
     *
     * @param provider the lookup to publish
     * @return the guard to close once the operation is finished
     */
    public static RegistryContext use(HolderLookup.Provider provider) {
        return new RegistryContext(provider);
    }

    /**
     * Returns the lookup of the innermost active scope.
     *
     * @return the current provider, or {@link RegistryAccess#EMPTY} outside any scope
     */
    public static HolderLookup.Provider current() {
        var provider = CURRENT.get();
        return provider == null ? RegistryAccess.EMPTY : provider;
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
