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
    public final void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean force) {
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

    @SuppressWarnings("ConstantValue")
    @Override
    public final void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion) {
        T value;
        if (data == NullData.INSTANCE) {
            value = null;
        } else {
            value = read(source, data, dataVersion);
            if (value == null) return;
        }
        definition.set(source, value);
    }

    protected abstract void write(@NotNull Object source, @NotNull FriendlyByteBuf data, @NotNull T value);

    protected abstract @NotNull T read(@NotNull Object source, @NotNull FriendlyByteBuf data);

    protected abstract @NotNull Data write(@NotNull Object source, @NotNull T value);

    protected abstract @NotNull T read(@NotNull Object source, @NotNull Data data, int dataVersion);
}
