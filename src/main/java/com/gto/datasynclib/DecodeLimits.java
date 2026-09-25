package com.gto.datasynclib;

import io.netty.buffer.ByteBuf;

/**
 * Bounds shared by the bundled decoders: a hard element cap and a nesting-depth cap.
 *
 * <p>1.21.1/NeoForge addition (same contract as MachineLib's adaptation). Network payloads
 * are untrusted input, so decoding runs inside {@link #enter()} and every declared
 * length is checked with {@link #size(int, ByteBuf, int)} before anything is allocated or
 * read. The depth counter is a thread-local that is restored by {@link #close()}.</p>
 *
 * <p>Custom codecs must still validate their own allocations — this class only provides the
 * shared limits and the recursion guard.</p>
 */
public final class DecodeLimits implements AutoCloseable {

    /** Maximum number of elements a single collection, map, array or string may declare. */
    public static final int MAX_ELEMENTS = 1048576;

    /** Maximum accepted nesting depth for recursive data structures. */
    private static final int MAX_DEPTH = 64;

    private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();

    private final Integer previous;

    private DecodeLimits() {
        previous = DEPTH.get();
        int next = previous == null ? 1 : previous + 1;
        if (next > MAX_DEPTH) {
            throw new IllegalArgumentException("Data nesting exceeds " + MAX_DEPTH + " levels");
        }
        DEPTH.set(next);
    }

    /**
     * Enters one decoding level. Use it in a try-with-resources block so the depth counter
     * is restored even when decoding fails.
     *
     * @return the guard to close once decoding of the current level is finished
     * @throws IllegalArgumentException if the nesting depth exceeds {@link #MAX_DEPTH}
     */
    public static DecodeLimits enter() {
        return new DecodeLimits();
    }

    /**
     * Validates a length or size read from the wire.
     *
     * @param value        the declared size
     * @param buffer       the buffer the elements are read from
     * @param minimumBytes the minimum number of bytes each element needs; {@code 0} to skip
     *                     the remaining-bytes sanity check
     * @return {@code value}, when it is valid
     * @throws IllegalArgumentException if the size is negative, above {@link #MAX_ELEMENTS},
     *                                  or larger than the remaining bytes can hold
     */
    public static int size(int value, ByteBuf buffer, int minimumBytes) {
        if (value < 0
                || value > MAX_ELEMENTS
                || (minimumBytes > 0 && value > buffer.readableBytes() / minimumBytes)) {
            throw new IllegalArgumentException("Invalid data length: " + value);
        }
        return value;
    }

    @Override
    public void close() {
        if (previous == null) {
            DEPTH.remove();
        } else {
            DEPTH.set(previous);
        }
    }
}
