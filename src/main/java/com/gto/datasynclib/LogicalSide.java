package com.gto.datasynclib;

/**
 * Represents the logical side in a client-server architecture, used to control
 * which fields are included in synchronization and persistence operations.
 *
 * <h3>Usage in sync direction:</h3>
 * <ul>
 *   <li>{@link #SERVER} — used when the server is sending sync data to clients
 *       (i.e., iterates {@code syncToClientFields})</li>
 *   <li>{@link #CLIENT} — used when the client is sending sync data to the server
 *       (i.e., iterates {@code syncToServerFields})</li>
 *   <li>{@link #BOTH} — used for full encode/decode in {@link com.gto.datasynclib.util.FieldDataCodec},
 *       iterates ALL fields regardless of sync direction</li>
 *   <li>{@link #NONE} — placeholder, currently unused in sync operations</li>
 * </ul>
 *
 * <h3>Important: BOTH is inclusive:</h3>
 * <p>{@link #isClient()} returns {@code true} for both {@code CLIENT} and {@code BOTH}.
 * {@link #isServer()} returns {@code true} for both {@code SERVER} and {@code BOTH}.
 * When checking for a specific side, use {@link #isBoth()} first if you need to
 * distinguish {@code BOTH} from the singular values.</p>
 */
public enum LogicalSide {

    /**
     * The client side, typically responsible for rendering and user interaction.
     */
    CLIENT,

    /**
     * The server side, typically responsible for game logic and authoritative data.
     */
    SERVER,

    /**
     * Represents both client and server simultaneously.
     * {@link #isClient()} and {@link #isServer()} both return {@code true} for this value.
     * Used in {@link com.gto.datasynclib.util.FieldDataCodec} for full bidirectional serialization.
     */
    BOTH,

    /**
     * Represents neither side. Currently a placeholder.
     */
    NONE;

    /**
     * Checks if this side includes the client role.
     *
     * @return {@code true} for {@link #CLIENT} and {@link #BOTH}
     */
    public final boolean isClient() {
        return this == CLIENT || this == BOTH;
    }

    /**
     * Checks if this side includes the server role.
     *
     * @return {@code true} for {@link #SERVER} and {@link #BOTH}
     */
    public final boolean isServer() {
        return this == SERVER || this == BOTH;
    }

    /**
     * Checks if this side is exactly {@link #BOTH} (not client-only or server-only).
     *
     * @return {@code true} only for {@link #BOTH}
     */
    public final boolean isBoth() {
        return this == BOTH;
    }
}
