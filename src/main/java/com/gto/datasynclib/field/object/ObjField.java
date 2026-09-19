package com.gto.datasynclib.field.object;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.NullData;
import com.gto.datasynclib.field.AbstractField;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * Abstract base class for object-type fields. Uses a two-tier change detection strategy: first
 * compares hashCode() for quick mismatch detection, then falls back to the definition's
 * strategy.equals() for full equality comparison. Handles null-vs-value encoding in both
 * buffer and data serialization.
 *
 * <h3>Null handling:</h3>
 * <ul>
 *   <li>Network: a null value is written as a {@code false} presence flag, otherwise
 *       {@code true} followed by the encoded value.</li>
 *   <li>Disk: a null value becomes {@link NullData#INSTANCE} (and {@link NullData#INSTANCE}
 *       reads back as null); the {@code skipSave}/default-value filters are applied only to
 *       non-null values, and a suppressed field is signalled with {@link NullData#NONE}.</li>
 * </ul>
 */
public abstract class ObjField<T> extends AbstractField<T> {

    protected T lastValue;
    protected int lastHash;

    protected ObjField(DataFieldDefinition<T> definition) {
        super(definition);
    }

    @Override
    public final boolean hasChange(@NotNull LogicalSide side, Object source) {
        var definition = this.definition;
        var value = definition.get(source);
        if (definition.skipSync(side, source, value)) return false;
        var hash = definition.strategy.hashCode(value);
        if (hash != lastHash) {
            lastHash = hash;
            return true;
        }
        return !definition.strategy.equals(value, lastValue);
    }

    @Override
    public final void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean writeAll) {
        T value = definition.get(source);
        lastValue = value;
        if (value == null) {
            data.writeBoolean(false);
        } else {
            data.writeBoolean(true);
            write(source, data, value);
        }
    }

    @Override
    public void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data) {
        T value;
        if (data.readBoolean()) {
            value = read(source, data);
        } else {
            value = null;
        }
        definition.set(source, value);
        var listener = definition.getListener(side);
        if (listener != null) {
            try {
                listener.invokeExact(source, value, lastValue);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
            lastValue = value;
        }
    }

    @Override
    public final @NotNull Data writeToData(@NotNull Object source) {
        var definition = this.definition;
        T value = definition.get(source);
        if (value == null) {
            return NullData.INSTANCE;
        } else {
            if (definition.skipSave(source, value)) return NullData.NONE;
            if (definition.hasDefaultValue() && definition.strategy.equals(value, definition.getDefaultValue(source)))
                return NullData.NONE;
            return write(source, value);
        }
    }

    @Override
    public final void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion) {
        T value;
        if (data == NullData.INSTANCE) {
            value = null;
        } else {
            value = read(source, data, dataVersion);
        }
        definition.set(source, value);
        // Load listener (@SaveToDisk(listener = "...")): `value` is the decoded value, or null when
        // the stored entry is a null marker. Never called on the sync path.
        var listener = definition.getSaveListener();
        if (listener != null) {
            try {
                listener.invokeExact(source, value);
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Encodes a non-null value into the network buffer.
     * The {@code null} case is handled by {@link #writeToBuffer} before this is called.
     */
    protected abstract void write(@NotNull Object source, @NotNull FriendlyByteBuf data, @NotNull T value);

    /**
     * Decodes a value from the network buffer; never asked to decode {@code null}.
     */
    protected abstract @NotNull T read(@NotNull Object source, @NotNull FriendlyByteBuf data);

    /**
     * Encodes a non-null value into a {@link Data} tree. Returning {@link NullData#NONE}
     * suppresses the field; {@code null} is handled by {@link #writeToData} instead.
     */
    protected abstract @NotNull Data write(@NotNull Object source, @NotNull T value);

    /**
     * Decodes a value from a {@link Data} tree.
     *
     * @param dataVersion the format version recorded when the data was written, for migration
     */
    protected abstract @NotNull T read(@NotNull Object source, @NotNull Data data, int dataVersion);
}
