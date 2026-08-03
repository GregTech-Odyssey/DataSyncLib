package com.gto.datasynclib.field;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.IntData;
import com.gto.datasynclib.datastream.data.NullData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * {@link DataField} implementation for {@code int} values.
 *
 * <h3>Change detection:</h3>
 * <p>Compares the current field value against {@link #lastValue}. The value is read
 * via {@link DataFieldDefinition#getInt(Object)} (VarHandle), so this is extremely fast.
 * Sync conditions ({@code @SyncToClient(condition = "...")}) are checked before comparison.</p>
 *
 * <h3>Persistence:</h3>
 * <p>Skips writing if the value matches the configured default (avoids storing redundant
 * data) or if the save condition returns {@code true}. Otherwise writes as {@link com.gto.datasynclib.datastream.data.IntData}.</p>
 *
 * <h3>Listener notification:</h3>
 * <p>On the receiving side, if a listener MethodHandle is configured, it is invoked
 * with {@code (source, newValue, lastValue)} after the value is set.</p>
 *
 * @see com.gto.datasynclib.field.AbstractField
 */
public final class IntField extends AbstractField<Integer> {

    private int lastValue;

    public IntField(DataFieldDefinition<Integer> definition) {
        super(definition);
    }

    @Override
    public boolean hasChange(@NotNull LogicalSide side, Object source) {
        var definition = this.definition;
        var value = definition.getInt(source);
        if (definition.skipSync(side, source, value)) return false;
        return lastValue != value;
    }

    @Override
    public void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean writeAll) {
        var value = definition.getInt(source);
        lastValue = value;
        data.writeVarInt(value);
    }

    @Override
    public void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data) {
        var value = data.readVarInt();
        definition.setInt(source, value);
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
    public @NotNull Data writeToData(@NotNull Object source) {
        var definition = this.definition;
        var value = definition.getInt(source);
        if (definition.skipSave(source, value)) return NullData.NONE;
        if (definition.hasDefaultValue() && definition.getDefaultIntValue(source) == value) return NullData.NONE;
        return IntData.valueOf(value);
    }

    @Override
    public void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion) {
        var value = data.getInt();
        definition.setInt(source, value);
    }
}
