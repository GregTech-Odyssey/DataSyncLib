package com.gto.datasynclib;

/**
 * Represents the logical side in a client-server architecture.
 * Used to distinguish between client-side and server-side operations
 * such as field synchronization and update scheduling.
 */
public enum LogicalSide {

    /**
     * The client side, typically responsible for rendering and user interaction.
     */
    CLIENT,

    /**
     * The server side, typically responsible for game logic and authoritative data.
     */
    SERVER;

    /**
     * Checks if this side is the client.
     *
     * @return true if this is the client side, false otherwise
     */
    public final boolean isClient() {
        return this == CLIENT;
    }

    /**
     * Checks if this side is the server.
     *
     * @return true if this is the server side, false otherwise
     */
    public final boolean isServer() {
        return this == SERVER;
    }
}
