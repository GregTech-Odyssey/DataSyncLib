package com.gto.datasynclib.field;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.LongData;
import com.gto.datasynclib.datastream.data.NullData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * DataField implementation for long values. Tracks the previous value for change detection
 * comparison, handles sync conditions via skipSync, default value filtering for persistence,
 * and optional listener notification on value changes.
 */
public final class LongField extends AbstractField<Long> {

    private long lastValue;

    public LongField(DataFieldDefinition<Long> definition) {
        super(definition);
    }

    @Override
    public boolean hasChange(@NotNull LogicalSide side, Object source) {
        var definition = this.definition;
        var value = definition.getLong(source);
        if (definition.skipSync(side, source, value)) return false;
        return lastValue != value;
    }

    @Override
    public void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean writeAll) {
        var value = definition.getLong(source);
        lastValue = value;
        data.writeLong(value);
    }

    @Override
    public void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data) {
        var value = data.readLong();
        definition.setLong(source, value);
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
        var value = definition.getLong(source);
        if (definition.skipSave(source, value)) return NullData.NONE;
        if (definition.hasDefaultValue() && definition.getDefaultLongValue(source) == value) return NullData.NONE;
        return LongData.valueOf(value);
    }

    @Override
    public void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion) {
        var value = data.getLong();
        definition.setLong(source, value);
    }
}
