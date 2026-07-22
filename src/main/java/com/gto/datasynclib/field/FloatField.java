package com.gto.datasynclib.field;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.FloatData;
import com.gto.datasynclib.datastream.data.NullData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * DataField implementation for float values. Tracks the previous value for change detection
 * comparison, handles sync conditions via skipSync, default value filtering for persistence,
 * and optional listener notification on value changes.
 */
public final class FloatField extends AbstractField<Float> {

    private float lastValue;

    public FloatField(DataFieldDefinition<Float> definition) {
        super(definition);
    }

    @Override
    public boolean hasChange(@NotNull LogicalSide side, Object source) {
        var definition = this.definition;
        var value = definition.getFloat(source);
        if (definition.skipSync(side, source, value)) return false;
        return lastValue != value;
    }

    @Override
    public void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean writeAll) {
        var value = definition.getFloat(source);
        lastValue = value;
        data.writeFloat(value);
    }

    @Override
    public void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data) {
        var value = data.readFloat();
        definition.setFloat(source, value);
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
        var value = definition.getFloat(source);
        if (definition.skipSave(source, value)) return NullData.NONE;
        if (definition.hasDefaultValue() && definition.getDefaultFloatValue(source) == value) return NullData.NONE;
        return FloatData.valueOf(value);
    }

    @Override
    public void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion) {
        var value = data.getFloat();
        definition.setFloat(source, value);
    }
}
