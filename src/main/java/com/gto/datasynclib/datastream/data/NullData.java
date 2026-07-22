package com.gto.datasynclib.datastream.data;

import io.netty.buffer.ByteBuf;
import org.jetbrains.annotations.ApiStatus;

/**
 * Represents a null/missing value in the Data type system. Wire format ID: 0.
 *
 * <p>Uses two sentinel values with different semantics:</p>
 * <ul>
 *   <li>{@link #INSTANCE} — An <strong>explicit</strong> null value that SHOULD be
 *       serialized. When a field write method returns {@code INSTANCE}, the field
 *       entry is written to the output (e.g., as a null entry in a StringMapData).</li>
 *   <li>{@link #NONE} — An <strong>internal</strong> signal to <em>suppress</em>
 *       serialization entirely. When a field write method returns {@code NONE},
 *       the field entry is <em>skipped</em> and nothing is written. This is the
 *       mechanism by which fields signal "I have nothing to write" (e.g., empty
 *       collections, default values, skip conditions).</li>
 * </ul>
 *
 * <p><strong>Important:</strong> Callers that process {@code Data} returned from
 * {@code writeToData()} methods MUST check for the NONE sentinel before processing
 * using {@link Data#isNone()} (preferred) or identity comparison
 * ({@code data == NullData.NONE}), or they risk serializing the suppression
 * signal as a regular null value.</p>
 *
 * @see com.gto.datasynclib.FieldDataManager#writeToData()
 */
public enum NullData implements ImmutableData {

    /**
     * Explicit null — will be serialized as a null entry when {@code saveNull} is true.
     * This is the wire-format null value (type ID 0).
     */
    INSTANCE,

    /**
     * Internal sentinel — signals "do not write this entry at all."
     * Used by field write methods to indicate that no data should be persisted
     * (e.g., empty collections, values matching defaults, or skip-condition-triggered).
     *
     * <p>This value should NEVER appear in serialized output. If it does, it
     * indicates a bug in a caller that failed to check for NONE before serializing.</p>
     */
    @ApiStatus.Internal
    NONE;

    @Override
    public byte[] writeToBytes() {
        return NULL_BYTES;
    }

    @Override
    public boolean isNull() {
        return true;
    }

    @Override
    public boolean isNone() {
        return this == NONE;
    }

    @Override
    public void write(ByteBuf stream) {
    }

    @Override
    public byte getId() {
        return NULL;
    }

    @Override
    public String toString() {
        return "null";
    }
}
