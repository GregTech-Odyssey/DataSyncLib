package com.gto.datasynclib.field;

import com.gto.datasynclib.DataFieldDefinition;
import com.gto.datasynclib.LogicalSide;
import com.gto.datasynclib.datastream.data.ByteData;
import com.gto.datasynclib.datastream.data.Data;
import com.gto.datasynclib.datastream.data.NullData;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;

/**
 * DataField implementation for byte values. Tracks the previous value for change detection
 * comparison, handles sync conditions via skipSync, default value filtering for persistence,
 * and optional listener notification on value changes.
 */
public final class ByteField extends AbstractField<Byte> {

    private byte lastValue;

    public ByteField(DataFieldDefinition<Byte> definition) {
        super(definition);
    }

    @Override
    public boolean hasChange(@NotNull LogicalSide side, Object source) {
        var definition = this.definition;
        var value = definition.getByte(source);
        if (definition.skipSync(side, source, value)) return false;
        return lastValue != value;
    }

    @Override
    public void writeToBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data, boolean writeAll) {
        var value = definition.getByte(source);
        lastValue = value;
        data.writeByte(value);
    }

    @Override
    public void readFromBuffer(@NotNull LogicalSide side, @NotNull Object source, @NotNull FriendlyByteBuf data) {
        var value = data.readByte();
        definition.setByte(source, value);
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
        var value = definition.getByte(source);
        if (definition.skipSave(source, value)) return NullData.NONE;
        if (definition.hasDefaultValue() && definition.getDefaultByteValue(source) == value) return NullData.NONE;
        return ByteData.valueOf(value);
    }

    @Override
    public void readFromData(@NotNull Object source, @NotNull Data data, int dataVersion) {
        var value = data.getByte();
        definition.setByte(source, value);
    }
}
